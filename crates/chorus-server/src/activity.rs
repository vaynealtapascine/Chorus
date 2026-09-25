//! Activity notifications (NOTIFICATIONS.md §7, M8.4). To *other* accounts in a shared space:
//! direct messages, mentions of one of their members, and replies to their messages. Inside a
//! system's own internal space: mentions of a member (or `@front`) and member DMs, each under that
//! member's rule, pushed to the account's *other* devices only.
//!
//! Also, if asked for, the account's own switches made on another device.
//!
//! Queued on live ingest of `message.send` and `front.*` (never on rebuild), delivered by the
//! notifier loop within seconds, as an inbox row plus an encrypted push. In a shared space the
//! author's own account is never notified. Restricted (not public) messages notify nobody.

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
fn kind_wanted(conn: &Connection, account: &str, kind: &str) -> anyhow::Result<bool> {
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

/// `chorus_core::mentions` lookups over the projections.
struct Directory<'a>(&'a Connection);

impl chorus_core::mentions::Directory for Directory<'_> {
    fn member_account(&self, member: &str) -> Option<String> {
        account_of_member(self.0, member).ok().flatten()
    }
    fn group(&self, group: &str) -> Option<(String, Vec<String>, Vec<String>)> {
        let c = self.0;
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
    fn fronting_at(&self, account: &str, t: i64) -> Vec<String> {
        self.0
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
fn mentioned(conn: &Connection, p: &Value, author: &str, at: i64) -> Vec<chorus_core::mentions::Mentioned> {
    chorus_core::mentions::resolve(p.get("entities").unwrap_or(&Value::Null), author, at, &Directory(conn))
}

/// Members fronting or co-conscious right now.
fn fronting(conn: &Connection, account: &str) -> anyhow::Result<Vec<String>> {
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
    let front = fronting(conn, account)?;
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
    for m in mentioned(conn, p, account, at) {
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
    if matches!(o.kind.as_str(), "front.switch" | "front.add" | "front.remove" | "front.update") {
        return own_switch(conn, o, author, now);
    }
    if o.kind != "message.send" {
        return Ok(());
    }
    let p = &o.payload;
    if !crate::visibility::is_public(p.get("visibility")) {
        return Ok(());
    }
    let channel_id = p.get("channel_id").and_then(Value::as_str).unwrap_or_default();
    type Row = (Option<String>, Option<String>, Option<String>, Option<String>, Option<String>);
    let (channel_name, space_kind, space_owner, channel_kind, member_ids): Row = conn
        .query_row(
            "SELECT c.name, s.kind, s.owner_account_id, c.kind, c.member_ids
             FROM channel c LEFT JOIN space s ON s.id = c.space_id WHERE c.id = ?1",
            [channel_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()?
        .unwrap_or_default();
    let is_dm = space_kind.as_deref() == Some("dm");

    // why each account should hear about it
    let mut why: BTreeMap<String, &'static str> = BTreeMap::new();
    if space_kind.as_deref() == Some("internal") && space_owner.as_deref() == Some(author) {
        let member_dm = (channel_kind.as_deref() == Some("member_dm"))
            .then(|| member_ids.and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok()).unwrap_or_default());
        if let Some(kind) = own_kind(conn, p, o.time(), author, channel_id, member_dm)? {
            why.insert(author.to_string(), kind);
        }
        // and below, the guests of a channel shared out of it (perms.rs): other accounts only
    }
    let mut st = conn.prepare_cached("SELECT account_id FROM scope_access WHERE scope = ?1 AND account_id <> ?2")?;
    let mut others: Vec<String> = st.query_map(params![o.scope, author], |r| r.get(0))?.collect::<Result<_, _>>()?;
    // only accounts that may view the channel hear about it (perms.rs)
    let mut viewers = Vec::with_capacity(others.len());
    for a in others.drain(..) {
        if crate::perms::can(conn, &a, channel_id, "view")? {
            viewers.push(a);
        }
    }
    let others = viewers;
    if others.is_empty() {
        return queue(conn, o, why, channel_name, now);
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
    for m in mentioned(conn, p, author, o.time()) {
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
    }
    queue(conn, o, why, channel_name, now)
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

/// Title and text for a settled own switch, or `None` if it was undone.
fn own_switch_text(conn: &Connection, account: &str, p: &Value) -> anyhow::Result<Option<(String, String)>> {
    let switch = p.get("switch_id").and_then(Value::as_str).unwrap_or_default();
    let live: Option<bool> =
        conn.query_row("SELECT retracted = 0 FROM switch WHERE id = ?1", [switch], |r| r.get(0)).optional()?;
    if live != Some(true) {
        return Ok(None);
    }
    let device: Option<String> = match p.get("from_device").and_then(Value::as_str) {
        Some(d) => conn.query_row("SELECT name FROM device WHERE id = ?1", [d], |r| r.get(0)).optional()?,
        None => None,
    };
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
    let title = match device {
        Some(d) => format!("Front changed on {d}"),
        None => "Front changed".to_string(),
    };
    Ok(Some((title, text)))
}

fn queue(
    conn: &Connection,
    o: &Op,
    why: BTreeMap<String, &'static str>,
    channel_name: Option<String>,
    now: i64,
) -> anyhow::Result<()> {
    if why.is_empty() {
        return Ok(());
    }
    let p = &o.payload;
    let channel_id = p.get("channel_id").and_then(Value::as_str).unwrap_or_default();
    let mut authors = Vec::new();
    for m in p.get("authors").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str) {
        authors.push(name_of(conn, m)?.unwrap_or_else(|| "Someone".into()));
    }
    let who = if authors.is_empty() { "Someone".to_string() } else { authors.join(" & ") };
    let text: String = p.get("text").and_then(Value::as_str).unwrap_or_default().chars().take(280).collect();
    for (recipient, kind) in why {
        let title = match (kind, &channel_name) {
            ("dm" | "member_dm", _) => who.clone(),
            (_, Some(c)) => format!("{who} · #{c}"),
            _ => who.clone(),
        };
        let mut payload = json!({
            "t": "message", "kind": kind, "title": title, "text": text,
            "channel_id": channel_id, "message_id": o.entity_id, "scope": o.scope,
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
        "SELECT id, recipient_account_id, payload FROM notification
         WHERE kind IN ('mention', 'dm', 'reply', 'message', 'member_dm', 'own_switch')
           AND delivered_at IS NULL AND cancelled_at IS NULL AND due_at <= ?1
         ORDER BY due_at, id LIMIT 500",
    )?;
    let rows: Vec<(String, String, String)> =
        st.query_map([now], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect::<Result<_, _>>()?;
    let mut pushes = Vec::new();
    for (id, recipient, payload) in rows {
        let mut p: Value = serde_json::from_str(&payload).unwrap_or(json!({}));
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
