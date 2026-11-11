//! Activity notifications (NOTIFICATIONS.md §7, M8.4). To *other* accounts in a shared space:
//! direct messages, mentions of one of their members, and replies to their messages. Inside a
//! system's own internal space: mentions of a member (or `@front`) and member DMs, each under that
//! member's rule, pushed to the account's *other* devices only.
//!
//! Also, if asked for, the account's own switches made on another device; post alerts
//! (`post_alerts.rs`, D-106); and nudges to vote on a poll (D-136).
//!
//! Queued on live ingest of `message.send` and `front.*` (never on rebuild), delivered by the
//! notifier loop within seconds, as an inbox row plus an encrypted push. In a shared space the
//! author's own account is never notified. Another account hears only about a message sync would
//! send it (`visibility::op_visible_to`): restricted (not public) messages, and messages in a
//! thread under one, notify nobody else.

use std::collections::BTreeMap;

use chorus_core::op::Op;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::push;

fn name_of(conn: &Connection, member: &str) -> anyhow::Result<Option<String>> {
    Ok(conn
        .query_row("SELECT COALESCE(display_name, name) FROM member WHERE id = ?1", [member], |r| r.get(0))
        .optional()?
        .flatten())
}

fn account_of_member(conn: &Connection, member: &str) -> anyhow::Result<Option<String>> {
    Ok(conn.query_row("SELECT account_id FROM member WHERE id = ?1", [member], |r| r.get(0)).optional()?)
}

fn pref(conn: &Connection, account: &str, key: &str) -> anyhow::Result<Option<Value>> {
    let v: Option<String> = conn
        .query_row(
            "SELECT value FROM pref WHERE account_id = ?1 AND device_id = '' AND key = ?2",
            params![account, key],
            |r| r.get(0),
        )
        .optional()?;
    Ok(v.and_then(|s| serde_json::from_str(&s).ok()))
}

/// `all` · `mentions` · `none` for one channel (`pref` key `notify_channel:<id>`); DMs default to
/// `all`, everything else to `mentions` (NOTIFICATIONS §7).
pub fn channel_level(conn: &Connection, account: &str, channel: &str, is_dm: bool) -> anyhow::Result<String> {
    let set = pref(conn, account, &format!("notify_channel:{channel}"))?;
    Ok(match set.as_ref().and_then(Value::as_str) {
        Some(l @ ("all" | "mentions" | "none")) => l.to_string(),
        _ if is_dm => "all".into(),
        _ => "mentions".into(),
    })
}

/// Per-kind switches (`pref` key `notify_chat`: `{"mention": bool, "dm": bool, "reply": bool,
/// "message": bool}`), all on unless turned off.
pub(crate) fn kind_wanted(conn: &Connection, account: &str, kind: &str) -> anyhow::Result<bool> {
    Ok(pref(conn, account, "notify_chat")?.and_then(|v| v.get(kind).and_then(Value::as_bool)).unwrap_or(true))
}

/// Per-member rule for the account's own chat (`pref` key `notify_member:<id>`: `{"mentions":
/// rule, "dms": rule}` with rule `always` · `fronting` · `never`). Mentions default to `always`,
/// member DMs to `fronting` (§7).
fn member_rule(conn: &Connection, account: &str, member: &str, which: &str) -> anyhow::Result<String> {
    let set = pref(conn, account, &format!("notify_member:{member}"))?;
    Ok(match set.as_ref().and_then(|v| v.get(which)).and_then(Value::as_str) {
        Some(r @ ("always" | "fronting" | "never")) => r.to_string(),
        _ if which == "dms" => "fronting".into(),
        _ => "always".into(),
    })
}

/// `chorus_core::mentions` lookups over the projections, for a message in `channel`.
struct Directory<'a> {
    conn: &'a Connection,
    channel: &'a str,
}

impl chorus_core::mentions::Directory for Directory<'_> {
    fn member_account(&self, member: &str) -> Option<String> {
        account_of_member(self.conn, member).ok().flatten()
    }
    fn group(&self, group: &str) -> Option<(String, Vec<String>, Vec<String>)> {
        let c = self.conn;
        let account: String = c
            .query_row("SELECT account_id FROM member_group WHERE id = ?1 AND deleted_at IS NULL", [group], |r| {
                r.get(0)
            })
            .optional()
            .ok()
            .flatten()?;
        let list = |sql: &str| -> Vec<String> {
            c.prepare_cached(sql)
                .and_then(|mut st| st.query_map([group], |r| r.get(0))?.collect::<Result<Vec<String>, _>>())
                .unwrap_or_default()
        };
        let members = list("SELECT member_id FROM group_membership WHERE group_id = ?1 AND is_present");
        let subgroups = list("SELECT id FROM member_group WHERE effective_parent_id = ?1 AND deleted_at IS NULL");
        Some((account, members, subgroups))
    }
    /// D-085: a configured account's `@front` is the `mentions` pool of the channel's front.
    fn fronting_at(&self, account: &str, t: i64) -> Vec<String> {
        if let Ok(Some(members)) = crate::fronts::purpose_members(self.conn, account, self.channel, "mentions", t) {
            return members;
        }
        self.conn
            .prepare_cached(
                "SELECT subject_id FROM front_interval WHERE account_id = ?1 AND subject_type = 'member'
                 AND level IN ('front', 'cocon') AND start_at <= ?2 AND (end_at IS NULL OR end_at > ?2)
                 ORDER BY position",
            )
            .and_then(|mut st| st.query_map(params![account, t], |r| r.get(0))?.collect::<Result<Vec<String>, _>>())
            .unwrap_or_default()
    }
}

/// Everyone a message's mentions reach (`chorus_core::mentions`): `@front` is who was here when
/// it was written (`at`, its `occurred_at`).
fn mentioned(
    conn: &Connection,
    p: &Value,
    author: &str,
    at: i64,
    channel: &str,
) -> Vec<chorus_core::mentions::Mentioned> {
    chorus_core::mentions::resolve(p.get("entities").unwrap_or(&Value::Null), author, at, &Directory { conn, channel })
}

/// Who a `fronting` chat rule counts at `at`: a configured account's `chat_alerts` pool for the
/// channel's front (D-085), else the members fronting or co-conscious right now.
fn fronting(conn: &Connection, account: &str, channel: &str, at: i64) -> anyhow::Result<Vec<String>> {
    if let Some(members) = crate::fronts::purpose_members(conn, account, channel, "chat_alerts", at)? {
        return Ok(members);
    }
    let mut st = conn.prepare_cached(
        "SELECT subject_id FROM front_interval WHERE account_id = ?1 AND subject_type = 'member'
         AND end_at IS NULL AND level IN ('front', 'cocon')",
    )?;
    Ok(st.query_map([account], |r| r.get(0))?.collect::<Result<_, _>>()?)
}

/// Why the author's own account should hear about a message in its internal space, if at all.
fn own_kind(
    conn: &Connection,
    p: &Value,
    at: i64,
    account: &str,
    channel_id: &str,
    member_dm: Option<Vec<String>>,
) -> anyhow::Result<Option<&'static str>> {
    let level = channel_level(conn, account, channel_id, false)?;
    if level == "none" {
        return Ok(None);
    }
    let authors: Vec<&str> =
        p.get("authors").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).collect();
    let front = fronting(conn, account, channel_id, at)?;
    let wants = |m: &str, which: &str| -> anyhow::Result<bool> {
        if authors.contains(&m) {
            return Ok(false);
        }
        Ok(match member_rule(conn, account, m, which)?.as_str() {
            "always" => true,
            "fronting" => front.iter().any(|f| f == m),
            _ => false,
        })
    };
    let mut kind = None;
    // its own members the message mentions: by name, through a group, or as `@front` (who was
    // fronting when it was written), each under that member's rule
    for m in mentioned(conn, p, account, at, channel_id) {
        if m.account == account
            && let Some(member) = &m.member
            && wants(member, "mentions")?
        {
            kind = Some("mention");
            break;
        }
    }
    if kind.is_none()
        && let Some(members) = member_dm
    {
        for m in &members {
            if wants(m, "dms")? {
                kind = Some("member_dm");
                break;
            }
        }
    }
    if kind.is_none() && level == "all" {
        kind = Some("message");
    }
    Ok(match kind {
        Some(k) if kind_wanted(conn, account, k)? => Some(k),
        _ => None,
    })
}

/// Queue notifications for a freshly accepted op (inside the ingest transaction).
pub fn on_op(conn: &Connection, o: &Op, now: i64) -> anyhow::Result<()> {
    let Some(author) = o.account_id.as_deref() else { return Ok(()) };
    // a configured account's Body presence edits (D-080) count like the legacy switch kinds
    let body_change = o.kind == "front.change"
        && o.entity_id.as_deref() == Some(author)
        && o.payload.get("history_only").and_then(Value::as_bool) != Some(true)
        && matches!(o.payload.get("action").and_then(Value::as_str), Some("switch" | "add" | "remove" | "update"));
    if body_change || matches!(o.kind.as_str(), "front.switch" | "front.add" | "front.remove" | "front.update") {
        return own_switch(conn, o, author, now);
    }
    // post alerts (D-106): approved subscribers of its authors who may read it
    if o.kind == "post.create"
        && let Some(id) = o.entity_id.as_deref()
    {
        return crate::post_alerts::on_post(conn, id, crate::post_alerts::Alerts::of(&o.payload), now);
    }
    if o.kind == "poll.nudge" {
        return poll_nudge(conn, o, author, now);
    }
    if o.kind != "message.send" {
        return Ok(());
    }
    let p = &o.payload;
    if !crate::visibility::is_public(p.get("visibility")) {
        return Ok(());
    }
    let channel_id = p.get("channel_id").and_then(Value::as_str).unwrap_or_default();
    let meta = load_channel_meta(conn, channel_id)?;
    let is_dm = meta.space_kind.as_deref() == Some("dm");

    // why each account should hear about it
    let mut why: BTreeMap<String, &'static str> = BTreeMap::new();
    if meta.space_kind.as_deref() == Some("internal") && meta.space_owner.as_deref() == Some(author) {
        let member_dm = (meta.kind.as_deref() == Some("member_dm")).then(|| {
            meta.member_ids.as_deref().and_then(|s| serde_json::from_str::<Vec<String>>(s).ok()).unwrap_or_default()
        });
        if let Some(kind) = own_kind(conn, p, o.time(), author, channel_id, member_dm)? {
            why.insert(author.to_string(), kind);
        }
        // and below, the guests of a channel shared out of it (perms.rs): other accounts only
    }
    let mut st = conn.prepare_cached("SELECT account_id FROM scope_access WHERE scope = ?1 AND account_id <> ?2")?;
    let mut others: Vec<String> = st.query_map(params![o.scope, author], |r| r.get(0))?.collect::<Result<_, _>>()?;
    // only accounts sync would send the message to hear about it (`visibility::op_visible_to`):
    // public, in a channel they may view (perms.rs), and not in a thread under a message they
    // can't see (a thread is as private as its parent, D-147)
    let mut viewers = Vec::with_capacity(others.len());
    for a in others.drain(..) {
        if crate::visibility::op_visible_to(conn, &a, o)? {
            viewers.push(a);
        }
    }
    let others = viewers;
    if others.is_empty() {
        return queue(conn, o, why, &meta, now);
    }
    if let Some(reply_to) = p.get("reply_to").and_then(Value::as_str) {
        let owner: Option<String> = conn
            .query_row("SELECT account_id FROM message WHERE id = ?1", [reply_to], |r| r.get(0))
            .optional()?
            .flatten();
        if let Some(a) = owner.filter(|a| others.contains(a)) {
            why.insert(a, "reply");
        }
    }
    // a mention of one of their members, a group of theirs, their `@front` or the account
    // itself; only accounts that may view the channel (`others`) are asked
    for m in mentioned(conn, p, author, o.time(), channel_id) {
        if others.contains(&m.account) {
            why.insert(m.account, "mention");
        }
    }
    if is_dm {
        for a in &others {
            why.entry(a.clone()).or_insert("dm");
        }
    }
    // each recipient's own settings (§7): the channel's level, then which kinds they want
    for a in &others {
        let level = channel_level(conn, a, channel_id, is_dm)?;
        if level == "none" {
            why.remove(a);
            continue;
        }
        if let Some(kind) = why.get(a).copied()
            && !kind_wanted(conn, a, kind)?
        {
            why.remove(a);
        }
        if level == "all" && !why.contains_key(a) && !is_dm && kind_wanted(conn, a, "message")? {
            why.insert(a.clone(), "message");
        }
        // a private chat kept to some of their members (D-098) pings under those members' DM
        // rule (default: only while one of them fronts), whatever else it would have been
        let kept = crate::perms::kept_to(conn, a, channel_id)?;
        if !kept.is_empty() {
            why.remove(a);
            let front = fronting(conn, a, channel_id, o.time())?;
            let mut wanted = false;
            for m in &kept {
                wanted |= match member_rule(conn, a, m, "dms")?.as_str() {
                    "always" => true,
                    "fronting" => front.iter().any(|f| f == m),
                    _ => false,
                };
            }
            if wanted && kind_wanted(conn, a, "member_dm")? {
                why.insert(a.clone(), "member_dm");
            }
        }
    }
    queue(conn, o, why, &meta, now)
}

/// Our own members nudged to vote (D-136): one notification for the account's other devices, naming
/// those who haven't voted and whose mentions rule lets it ring. Nobody outside the account hears.
fn poll_nudge(conn: &Connection, o: &Op, author: &str, now: i64) -> anyhow::Result<()> {
    let Some(poll) = o.entity_id.as_deref() else { return Ok(()) };
    if !kind_wanted(conn, author, "poll_nudge")? {
        return Ok(());
    }
    let message: Option<(String, String, String, String)> = conn
        .prepare_cached(
            "SELECT m.poll, m.text, m.channel_id, c.space_id FROM message m JOIN channel c ON c.id = m.channel_id
             WHERE m.id = ?1 AND m.poll IS NOT NULL",
        )?
        .query_row([poll], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
        .optional()?;
    let (definition, body) = match &message {
        Some((definition, body, ..)) => (definition.clone(), body.clone()),
        None => {
            let post: Option<(String, String)> = conn
                .prepare_cached("SELECT poll, text FROM post WHERE id = ?1 AND poll IS NOT NULL")?
                .query_row([poll], |r| Ok((r.get(0)?, r.get(1)?)))
                .optional()?;
            let Some(post) = post else { return Ok(()) };
            post
        }
    };
    let channel = message.as_ref().map_or("", |m| m.2.as_str());
    let front = fronting(conn, author, channel, now)?;
    let mut voted = conn.prepare_cached(
        "SELECT EXISTS(SELECT 1 FROM poll_vote WHERE poll_id = ?1 AND account_id = ?2 AND member_id = ?3
           AND json_array_length(choices) > 0)",
    )?;
    let mut names = Vec::new();
    for member in o.payload.get("member_ids").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str)
    {
        let rings = match member_rule(conn, author, member, "mentions")?.as_str() {
            "always" => true,
            "fronting" => front.iter().any(|f| f == member),
            _ => false,
        };
        if rings && !voted.query_row(params![poll, author, member], |r| r.get::<_, bool>(0))? {
            names.push(name_of(conn, member)?.unwrap_or_else(|| "Someone".into()));
        }
    }
    let whom = match names.as_slice() {
        [] => return Ok(()),
        [one] => one.clone(),
        [a, b] => format!("{a} and {b}"),
        [a, rest @ ..] => format!("{a} and {} others", rest.len()),
    };
    let by = o.payload.get("by_member_id").and_then(Value::as_str);
    let who = by.map(|m| name_of(conn, m)).transpose()?.flatten().unwrap_or_else(|| "Someone".into());
    let card = by.map(|m| author_card(conn, m)).transpose()?.unwrap_or_default();
    let question = serde_json::from_str::<Value>(&definition)
        .ok()
        .and_then(|d| d.get("question").and_then(Value::as_str).map(String::from))
        .filter(|q| !q.trim().is_empty())
        .unwrap_or(body);
    let mut payload = json!({
        "t": "poll", "kind": "poll_nudge", "title": format!("{who} nudged {whom}"),
        "text": question.chars().take(120).collect::<String>(), "sender": sender_of(&who, &card),
    });
    match &message {
        Some((.., channel, space)) => {
            payload["message_id"] = json!(poll);
            payload["channel_id"] = json!(channel);
            payload["scope"] = json!(format!("space:{space}"));
        }
        None => payload["post_id"] = json!(poll),
    }
    if let Some(d) = &o.device_id {
        payload["from_device"] = json!(d);
    }
    let id = chorus_core::id::new_id(now as u64, rand::random());
    conn.execute(
        "INSERT INTO notification (id, recipient_account_id, kind, source_op_id, payload, created_at, due_at)
         VALUES (?1, ?2, 'poll_nudge', ?3, ?4, ?5, ?5)",
        params![id, author, o.id, serde_json::to_string(&payload)?, now],
    )?;
    Ok(())
}

/// Steady fallback disc palette matching `web/src/lib/chat-list.ts` (`discColor`).
const DISCS: [&str; 6] = ["#B99CE8", "#F08C7F", "#EEAA62", "#DCA3CC", "#8FB8E8", "#86BFA5"];

fn disc_color(id: &str) -> &'static str {
    let mut h: u32 = 0;
    for u in id.encode_utf16() {
        h = h.wrapping_mul(31).wrapping_add(u32::from(u));
    }
    DISCS[(h as usize) % DISCS.len()]
}

fn first_glyph(text: &str, general_hash: bool) -> String {
    let trimmed = text.trim();
    if general_hash && trimmed.eq_ignore_ascii_case("general") {
        return "#".into();
    }
    trimmed.chars().next().map(|c| c.to_uppercase().collect::<String>()).unwrap_or_else(|| "#".into())
}

#[derive(Default)]
struct ChannelMeta {
    space_id: Option<String>,
    space_kind: Option<String>,
    space_owner: Option<String>,
    space_name: Option<String>,
    space_icon: Option<String>,
    space_color: Option<String>,
    kind: Option<String>,
    name: Option<String>,
    icon: Option<String>,
    color: Option<String>,
    avatar_blob: Option<String>,
    parent_message_id: Option<String>,
    member_ids: Option<String>,
}

fn load_channel_meta(conn: &Connection, channel_id: &str) -> anyhow::Result<ChannelMeta> {
    let mut m = conn
        .query_row(
            "SELECT c.space_id, s.kind, s.owner_account_id, s.name, s.icon, s.color,
                    c.kind, c.name, c.icon, c.color, c.avatar_blob, c.parent_message_id, c.member_ids
             FROM channel c LEFT JOIN space s ON s.id = c.space_id WHERE c.id = ?1",
            [channel_id],
            |r| {
                Ok(ChannelMeta {
                    space_id: r.get(0)?,
                    space_kind: r.get(1)?,
                    space_owner: r.get(2)?,
                    space_name: r.get(3)?,
                    space_icon: r.get(4)?,
                    space_color: r.get(5)?,
                    kind: r.get(6)?,
                    name: r.get(7)?,
                    icon: r.get(8)?,
                    color: r.get(9)?,
                    avatar_blob: r.get(10)?,
                    parent_message_id: r.get(11)?,
                    member_ids: r.get(12)?,
                })
            },
        )
        .optional()?
        .unwrap_or_default();
    if m.kind.as_deref() == Some("thread")
        && let Some(parent_msg) = m.parent_message_id.as_deref()
        && let Some((pname, picon, pcolor, pavatar)) = conn
            .query_row(
                "SELECT ch.name, ch.icon, ch.color, ch.avatar_blob
                 FROM message msg JOIN channel ch ON ch.id = msg.channel_id WHERE msg.id = ?1",
                [parent_msg],
                |r| Ok((r.get::<_, Option<String>>(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
            )
            .optional()?
    {
        if m.name.as_deref().is_none_or(|n| n.eq_ignore_ascii_case("thread") || n.trim().is_empty()) {
            m.name = pname;
        }
        if m.icon.as_deref().is_none_or(|s| s.trim().is_empty()) {
            m.icon = picon;
        }
        if m.color.is_none() {
            m.color = pcolor;
        }
        if m.avatar_blob.is_none() {
            m.avatar_blob = pavatar;
        }
    }
    Ok(m)
}

#[derive(Clone, Default)]
struct AuthorCard {
    glyph: Option<String>,
    color: Option<String>,
    avatar_blob: Option<String>,
}

fn author_card(conn: &Connection, member_id: &str) -> anyhow::Result<AuthorCard> {
    type Row = (Option<String>, Option<String>, Option<String>, Option<String>, Option<String>);
    let row: Option<Row> = conn
        .query_row(
            "SELECT name, display_name, color, json_extract(sigils, '$[0]'), avatar_blob
             FROM member WHERE id = ?1 AND deleted_at IS NULL",
            [member_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()?;
    let Some((name, display, color, sigil, avatar_blob)) = row else {
        return Ok(AuthorCard::default());
    };
    let shown = display.filter(|s| !s.trim().is_empty()).or(name).unwrap_or_default();
    let glyph = sigil
        .filter(|s| !s.trim().is_empty())
        .or_else(|| (!shown.trim().is_empty()).then(|| first_glyph(&shown, false)));
    Ok(AuthorCard { glyph, color, avatar_blob })
}

fn chat_visual(
    conn: &Connection,
    meta: &ChannelMeta,
    channel_id: &str,
    recipient: &str,
    author_account: Option<&str>,
    who: &str,
    first_author: &AuthorCard,
) -> anyhow::Result<(String, Value)> {
    let clean = |opt: &Option<String>| opt.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
    let c_icon = clean(&meta.icon);
    let c_color = clean(&meta.color);
    let c_avatar = clean(&meta.avatar_blob);
    let c_name = clean(&meta.name);
    let s_name = clean(&meta.space_name);
    let s_icon = clean(&meta.space_icon);
    let s_color = clean(&meta.space_color);
    let space_id = meta.space_id.as_deref().unwrap_or(channel_id);

    let (chat_title, glyph, color, avatar_blob, direct, headline) = match meta.space_kind.as_deref() {
        Some("dm") => {
            type PeerRow = (Option<String>, Option<String>, Option<String>, String);
            let peer: Option<PeerRow> = if let Some(sid) = meta.space_id.as_deref() {
                conn.query_row(
                    "SELECT a.handle, a.display_name, a.avatar_blob, a.kind
                     FROM space_member sm JOIN account a ON a.id = sm.account_id
                     WHERE sm.space_id = ?1 AND sm.is_present AND a.id <> ?2 LIMIT 1",
                    params![sid, recipient],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                )
                .optional()?
            } else {
                None
            }
            .or_else(|| {
                author_account.filter(|a| *a != recipient).and_then(|aid| {
                    conn.query_row(
                        "SELECT handle, display_name, avatar_blob, kind FROM account WHERE id = ?1",
                        [aid],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                    )
                    .optional()
                    .ok()
                    .flatten()
                })
            });
            let (handle, display, acct_avatar, acct_kind) = peer.unwrap_or_default();
            let display_clean = clean(&display);
            let handle_clean = clean(&handle);
            let peer_label = display_clean
                .clone()
                .or_else(|| handle_clean.as_ref().map(|h| format!("@{h}")))
                .unwrap_or_else(|| who.to_string());
            let glyph_seed = display_clean.or(handle_clean).unwrap_or_else(|| peer_label.clone());
            let is_person = acct_kind == "person";
            let avatar = c_avatar
                .or_else(|| clean(&acct_avatar))
                .or_else(|| if is_person { first_author.avatar_blob.clone() } else { None });
            let col = c_color
                .or_else(|| if is_person { first_author.color.clone() } else { None })
                .or_else(|| Some(disc_color(space_id).to_string()));
            let gl = c_icon
                .or_else(|| if is_person { first_author.glyph.clone() } else { None })
                .unwrap_or_else(|| first_glyph(&glyph_seed, false));
            let top = if peer_label == who { who.to_string() } else { format!("{who} · {peer_label}") };
            (peer_label, gl, col, avatar, true, top)
        }
        Some("internal") if meta.kind.as_deref() == Some("member_dm") => {
            let avatar = c_avatar.or_else(|| first_author.avatar_blob.clone());
            let col = c_color.or_else(|| first_author.color.clone());
            let gl = c_icon.or_else(|| first_author.glyph.clone()).unwrap_or_default();
            (who.to_string(), gl, col, avatar, true, who.to_string())
        }
        Some("shared") => {
            let chan_count: i64 = if let Some(sid) = meta.space_id.as_deref() {
                conn.query_row(
                    "SELECT COUNT(*) FROM channel WHERE space_id = ?1 AND kind <> 'thread' AND deleted_at IS NULL",
                    [sid],
                    |r| r.get(0),
                )
                .unwrap_or(1)
            } else {
                1
            };
            let is_general = c_name.as_deref().is_none_or(|c| c.eq_ignore_ascii_case("general"));
            let single_general = chan_count <= 1 && is_general;
            let label = match (&s_name, &c_name) {
                (Some(s), _) if single_general => s.clone(),
                (Some(s), Some(c)) => format!("{s} · #{c}"),
                (_, Some(c)) => format!("#{c}"),
                (Some(s), None) => s.clone(),
                (None, None) => "Chorus".into(),
            };
            let top = match (&s_name, &c_name) {
                (Some(s), _) if single_general => format!("{who} · {s}"),
                (Some(s), Some(c)) => format!("{who} · {s} #{c}"),
                (_, Some(c)) => format!("{who} · #{c}"),
                _ => who.to_string(),
            };
            let gl = c_icon.unwrap_or_else(|| {
                if is_general {
                    s_icon.unwrap_or_else(|| {
                        s_name.as_deref().map(|s| first_glyph(s, false)).unwrap_or_else(|| "#".into())
                    })
                } else {
                    c_name.as_deref().map(|c| first_glyph(c, true)).unwrap_or_else(|| "#".into())
                }
            });
            let col = c_color.or(s_color).or_else(|| Some(disc_color(space_id).to_string()));
            (label, gl, col, c_avatar, false, top)
        }
        _ => {
            let guest = meta.space_owner.as_deref().is_some_and(|o| o != recipient);
            let label = c_name.as_ref().map(|c| format!("#{c}")).unwrap_or_else(|| "Chorus".into());
            let top = c_name.as_ref().map(|c| format!("{who} · #{c}")).unwrap_or_else(|| who.to_string());
            let gl =
                c_icon.unwrap_or_else(|| c_name.as_deref().map(|c| first_glyph(c, true)).unwrap_or_else(|| "#".into()));
            let col = c_color.or_else(|| guest.then(|| disc_color(channel_id).to_string()));
            (label, gl, col, c_avatar, false, top)
        }
    };
    let mut chat = json!({"title": chat_title, "glyph": glyph, "direct": direct});
    if let Some(c) = color {
        chat["color"] = json!(c);
    }
    if let Some(a) = avatar_blob {
        chat["avatar_blob"] = json!(a);
    }
    Ok((headline, chat))
}

/// "Own switches from other devices" (§7, off unless `notify_chat.own_switch` is true): queued
/// after the settle so an undo stays quiet; a newer switch replaces a pending one. The text is
/// filled at delivery from the front as it is then.
fn own_switch(conn: &Connection, o: &Op, author: &str, now: i64) -> anyhow::Result<()> {
    if o.scope != format!("account:{author}")
        || o.payload.get("notify").and_then(Value::as_str) == Some("silent")
        || pref(conn, author, "notify_chat")?.and_then(|v| v.get("own_switch").and_then(Value::as_bool)) != Some(true)
    {
        return Ok(());
    }
    conn.execute(
        "UPDATE notification SET cancelled_at = ?2 WHERE recipient_account_id = ?1 AND kind = 'own_switch'
         AND delivered_at IS NULL AND cancelled_at IS NULL",
        params![author, now],
    )?;
    let payload = json!({"t": "own_switch", "switch_id": o.id, "from_device": o.device_id});
    let id = chorus_core::id::new_id(now as u64, rand::random());
    conn.execute(
        "INSERT INTO notification (id, recipient_account_id, kind, source_op_id, payload, created_at, due_at)
         VALUES (?1, ?2, 'own_switch', ?3, ?4, ?5, ?6)",
        params![id, author, o.id, serde_json::to_string(&payload)?, now, now + chorus_core::notify::DEFAULT_SETTLE_MS],
    )?;
    Ok(())
}

fn subject_name(conn: &Connection, kind: chorus_core::front::SubjectType, id: &str) -> anyhow::Result<String> {
    use chorus_core::front::SubjectType;
    let sql = match kind {
        SubjectType::Member => "SELECT COALESCE(display_name, name) FROM member WHERE id = ?1",
        SubjectType::Group => "SELECT name FROM member_group WHERE id = ?1",
        SubjectType::State => "SELECT name FROM custom_state WHERE id = ?1",
    };
    let name: Option<Option<String>> = conn.prepare_cached(sql)?.query_row([id], |r| r.get(0)).optional()?;
    Ok(name.flatten().unwrap_or_else(|| "?".into()))
}

/// Body's confirmed presence: the Fronting layer by its exact ID, then each other layer by its
/// own name (inner to outer). A custom layer is never described as fronting or co-conscious.
fn named_front_text(
    conn: &Connection,
    account: &str,
    current: &chorus_core::front_projection::Current,
) -> anyhow::Result<String> {
    use chorus_core::front_system::FollowState;
    let entries = match current.resolved.get(account) {
        Some(b) if !b.deleted && b.state != FollowState::Unavailable => {
            crate::fronts::owned_presence(conn, account, &b.effective)?
        }
        _ => Vec::new(),
    };
    let names = |layer: &str| -> anyhow::Result<Vec<String>> {
        entries
            .iter()
            .filter(|e| e.layer_id == layer)
            .map(|e| subject_name(conn, e.subject_type, &e.subject_id))
            .collect()
    };
    let front = names("front")?;
    let mut text = if front.is_empty() { "No one fronting".to_string() } else { front.join(" & ") };
    let mut layers: Vec<_> = current.system.layers.values().filter(|l| l.id != "front").collect();
    layers.sort_by(|a, b| a.level.cmp(&b.level).then_with(|| a.id.cmp(&b.id)));
    for layer in layers {
        let here = names(&layer.id)?;
        if !here.is_empty() {
            text.push_str(&format!(" · {} {}", layer.name, here.join(" & ")));
        }
    }
    Ok(text)
}

fn legacy_front_text(conn: &Connection, account: &str) -> anyhow::Result<String> {
    let mut st = conn.prepare_cached(
        "SELECT CASE subject_type
                  WHEN 'member' THEN (SELECT COALESCE(display_name, name) FROM member WHERE id = subject_id)
                  WHEN 'group' THEN (SELECT name FROM member_group WHERE id = subject_id)
                  ELSE (SELECT name FROM custom_state WHERE id = subject_id) END, level
         FROM front_interval WHERE account_id = ?1 AND end_at IS NULL ORDER BY position",
    )?;
    let rows: Vec<(Option<String>, String)> =
        st.query_map([account], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<Result<_, _>>()?;
    let names = |level: &str| {
        rows.iter()
            .filter(|(_, l)| l == level)
            .map(|(n, _)| n.clone().unwrap_or_else(|| "?".into()))
            .collect::<Vec<_>>()
    };
    let front = names("front");
    let mut text = if front.is_empty() { "No one fronting".to_string() } else { front.join(" & ") };
    let cocon = names("cocon");
    if !cocon.is_empty() {
        text.push_str(&format!(" · co-con {}", cocon.join(" & ")));
    }
    Ok(text)
}

/// Title and text for a settled own switch, or `None` if it was undone.
fn own_switch_text(conn: &Connection, account: &str, p: &Value) -> anyhow::Result<Option<(String, String)>> {
    let switch = p.get("switch_id").and_then(Value::as_str).unwrap_or_default();
    let current = crate::fronts::current(conn, account)?;
    let live = if current.is_some() {
        // D-083 controls are canonical for both legacy and named Body targets. Neither the
        // legacy switch row nor a scan of only new-kind retracts can answer this alone.
        let ops = crate::fronts::account_ops(conn, account)?;
        chorus_core::front_history::fold(account, &ops)
            .histories
            .get(account)
            .is_some_and(|h| h.switches.iter().any(|s| s.id == switch && !s.retracted))
    } else {
        conn.query_row(
            "SELECT retracted = 0 FROM switch WHERE id = ?1 AND account_id = ?2",
            params![switch, account],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(false)
    };
    if !live {
        return Ok(None);
    }
    let device: Option<String> = match p.get("from_device").and_then(Value::as_str) {
        Some(d) => conn.query_row("SELECT name FROM device WHERE id = ?1", [d], |r| r.get(0)).optional()?,
        None => None,
    };
    let text = match current {
        Some(current) => named_front_text(conn, account, &current)?,
        None => legacy_front_text(conn, account)?,
    };
    let title = match device {
        Some(d) => format!("Front changed on {d}"),
        None => "Front changed".to_string(),
    };
    Ok(Some((title, text)))
}

/// The `sender` of a notification: a name and what to draw for them.
fn sender_of(name: &str, card: &AuthorCard) -> Value {
    let mut sender = json!({"name": name});
    if let Some(g) = &card.glyph {
        sender["glyph"] = json!(g);
    }
    if let Some(c) = &card.color {
        sender["color"] = json!(c);
    }
    if let Some(a) = &card.avatar_blob {
        sender["avatar_blob"] = json!(a);
    }
    sender
}

fn queue(
    conn: &Connection,
    o: &Op,
    why: BTreeMap<String, &'static str>,
    meta: &ChannelMeta,
    now: i64,
) -> anyhow::Result<()> {
    if why.is_empty() {
        return Ok(());
    }
    let p = &o.payload;
    let channel_id = p.get("channel_id").and_then(Value::as_str).unwrap_or_default();
    let author_ids: Vec<&str> =
        p.get("authors").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).collect();
    let mut authors = Vec::new();
    for m in &author_ids {
        authors.push(name_of(conn, m)?.unwrap_or_else(|| "Someone".into()));
    }
    let who = if authors.is_empty() { "Someone".to_string() } else { authors.join(" & ") };
    let first_author = match author_ids.first() {
        Some(m) => author_card(conn, m)?,
        None => AuthorCard::default(),
    };
    let sender = sender_of(&who, &first_author);
    let text: String = p.get("text").and_then(Value::as_str).unwrap_or_default().chars().take(280).collect();
    for (recipient, kind) in why {
        let (title, chat) =
            chat_visual(conn, meta, channel_id, &recipient, o.account_id.as_deref(), &who, &first_author)?;
        let mut payload = json!({
            "t": "message", "kind": kind, "title": title, "text": text,
            "channel_id": channel_id, "message_id": o.entity_id, "scope": o.scope,
            "chat": chat, "sender": sender,
        });
        // Advanced "reply as mentioned member" (§7): an inline reply speaks as the recipient's
        // member this message names (not one of its authors) instead of whoever is fronting
        if pref(conn, &recipient, "notify_chat")?.and_then(|v| v.get("reply_as_mentioned").and_then(Value::as_bool))
            == Some(true)
        {
            let written_by =
                |m: &str| p.get("authors").and_then(Value::as_array).is_some_and(|a| a.iter().any(|x| x == m));
            for e in p.get("entities").and_then(Value::as_array).into_iter().flatten() {
                if e.get("type").and_then(Value::as_str) == Some("mention")
                    && e.get("target_type").and_then(Value::as_str) == Some("member")
                    && let Some(m) = e.get("target_id").and_then(Value::as_str)
                    && !written_by(m)
                    && account_of_member(conn, m)?.as_deref() == Some(recipient.as_str())
                {
                    payload["reply_as"] = json!(m);
                    break;
                }
            }
        }
        // a private chat (D-098): say whose it is, and an inline reply speaks as them (anyone
        // else would be refused there)
        if let Some(m) = crate::perms::kept_to(conn, &recipient, channel_id)?.first() {
            let name = name_of(conn, m)?.unwrap_or_else(|| "Someone".into());
            payload["title"] = json!(format!("{title} · for {name}"));
            payload["reply_as"] = json!(m);
        }
        // your own message: the device it was written on doesn't need the ping
        if o.account_id.as_deref() == Some(recipient.as_str())
            && let Some(d) = &o.device_id
        {
            payload["from_device"] = json!(d);
        }
        let id = chorus_core::id::new_id(now as u64, rand::random());
        conn.execute(
            "INSERT INTO notification (id, recipient_account_id, kind, source_op_id, payload, created_at, due_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)",
            params![id, recipient, kind, o.id, serde_json::to_string(&payload)?, now],
        )?;
    }
    Ok(())
}

/// Deliver due activity notifications; returns the pushes to send after the lock is released.
pub fn process_due(conn: &Connection, now: i64) -> anyhow::Result<Vec<push::Outbound>> {
    let mut st = conn.prepare_cached(
        "SELECT id, recipient_account_id, payload, kind FROM notification
         WHERE kind IN ('mention', 'dm', 'reply', 'message', 'member_dm', 'own_switch', 'post', 'post_reply', 'poll_nudge', 'alert_request', 'alert_accepted')
           AND delivered_at IS NULL AND cancelled_at IS NULL AND due_at <= ?1
         ORDER BY due_at, id LIMIT 500",
    )?;
    let rows: Vec<(String, String, String, String)> =
        st.query_map([now], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?.collect::<Result<_, _>>()?;
    let mut pushes = Vec::new();
    for (id, recipient, payload, kind) in rows {
        let mut p: Value = serde_json::from_str(&payload).unwrap_or(json!({}));
        // a post alert or reply ping waits up to an hour (D-127): only if the post can still be
        // read by its recipient then (D-106, D-147)
        if matches!(kind.as_str(), "post" | "post_reply")
            && !crate::post_alerts::still_deliverable(conn, &recipient, &kind, &p)?
        {
            conn.execute("UPDATE notification SET cancelled_at = ?2 WHERE id = ?1", params![id, now])?;
            continue;
        }
        if p.get("t").and_then(Value::as_str) == Some("own_switch") {
            let Some((title, text)) = own_switch_text(conn, &recipient, &p)? else {
                conn.execute("UPDATE notification SET cancelled_at = ?2 WHERE id = ?1", params![id, now])?;
                continue;
            };
            p["title"] = json!(title);
            p["text"] = json!(text);
            conn.execute(
                "UPDATE notification SET payload = ?2 WHERE id = ?1",
                params![id, serde_json::to_string(&p)?],
            )?;
        }
        conn.execute("UPDATE notification SET delivered_at = ?2 WHERE id = ?1", params![id, now])?;
        p["id"] = json!(id);
        let skip = p.as_object_mut().and_then(|m| m.remove("from_device")).and_then(|d| d.as_str().map(String::from));
        pushes.extend(push::prepare_except(conn, &recipient, &p, skip.as_deref())?);
    }
    Ok(pushes)
}
