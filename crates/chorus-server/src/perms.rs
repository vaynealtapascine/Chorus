//! Channel permissions (M5.10, D-047, SPEC §5.1): one rule, [`can_sql`], used by every path that
//! lets an account write to, receive, search or fetch something in a channel.
//!
//! - **Roles.** A space's owner (`space.owner_account_id`) can do everything, and so can an
//!   `admin`. The built-in `member` role may `view`, `send`, `react`, `thread` and `pin`;
//!   `read_only` may `view` and `react`; a custom role (`space.roles`, `{id, name, perms}`) has
//!   the perms it lists. In a DM every participant can do everything.
//! - **Overrides** (`channel_permission`, from `channel.set_permission`): per channel, for an
//!   account, for a role, or for the role `everyone` (every member of the space). The most
//!   specific level that says anything about a permission decides: account, then the account's
//!   role, then `everyone`, then the role's base. At one level, deny beats allow.
//! - **Every permission needs `view`.** A thread has its parent message's channel's permissions.
//! - **Guests.** An account override that allows `view` works for an account that isn't in the
//!   space at all: that is how one internal channel is shared with a partner. The server grants
//!   such a guest the space's scope ([`refresh_guest`]) and the sync rule
//!   (`visibility::op_visible_to`) lets through only the channels it may view, plus the space's
//!   name. Guests have no role, so only account overrides apply to them.
//! - An unknown channel grants nothing (ops that name it wait until it arrives).

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::Value;

use chorus_core::op::Op;

/// Every permission (D-133: the core list, `chorus_core::perms::PERMISSIONS`).
pub use chorus_core::perms::PERMISSIONS as PERMS;
use chorus_core::perms::{IMPLIED, SPACE_ONLY};

/// SQL: does an override at one level (`target_type`/`target_id` as SQL) allow or deny `perm`?
/// `table`/`key`/`place` say where: a channel's (`channel_permission`, `perm_c.id`) or the whole
/// space's (`space_permission`, the space's id). (Aliases start with `perm_`: the rule is nested in
/// queries with their own `c`, `m`, `s`…)
fn override_sql(
    table: &str,
    key: &str,
    place: &str,
    list: &str,
    target_type: &str,
    target_id: &str,
    perm: &str,
) -> String {
    format!(
        "EXISTS (SELECT 1 FROM {table} perm_o, json_each(perm_o.{list}) perm_x
                 WHERE perm_o.{key} = {place} AND perm_o.target_type = '{target_type}'
                   AND perm_o.target_id = {target_id} AND perm_x.value = '{perm}')"
    )
}

/// SQL boolean: the role's base set has `perm` (`chorus_core::perms::base`), for the role in the
/// SQL expression `role` of the space whose `roles` column is `roles`.
fn base_sql(role: &str, roles: &str, perm: &str) -> String {
    let has = |r: &str| chorus_core::perms::base(r, &serde_json::Value::Null).contains(&perm);
    let listed = if IMPLIED.contains(&perm) { "" } else { "AND perm_rp.value = '{perm}'" }.replace("{perm}", perm);
    let perms = if IMPLIED.contains(&perm) { "" } else { ", json_each(perm_r.value, '$.perms') perm_rp" };
    format!(
        "CASE WHEN {role} = 'admin' THEN 1
              WHEN {role} = 'member' THEN {member}
              WHEN {role} = 'read_only' THEN {read_only}
              ELSE EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid({roles}) THEN {roles} ELSE '[]' END) perm_r{perms}
                           WHERE json_extract(perm_r.value, '$.id') = {role} {listed}) END",
        member = u8::from(has("member")),
        read_only = u8::from(has("read_only")),
    )
}

/// One permission, without the `view` requirement (DATA_MODEL §4.4, `chorus_core::perms::resolve`).
fn one_sql(account: &str, channel: &str, perm: &str) -> String {
    assert!(PERMS.contains(&perm) && !SPACE_ONLY.contains(&perm), "not a channel permission: {perm}");
    let ch = |list: &str, t: &str, id: &str| {
        override_sql("channel_permission", "channel_id", "perm_c.id", list, t, id, perm)
    };
    let sp =
        |list: &str, t: &str, id: &str| override_sql("space_permission", "space_id", "perm_s.id", list, t, id, perm);
    format!(
        "coalesce((SELECT CASE
            WHEN perm_s.owner_account_id = {account} THEN 1
            WHEN perm_sm.role IS NOT NULL AND perm_s.kind = 'dm' THEN 1
            WHEN perm_sm.role = 'admin' THEN 1
            WHEN {acct_deny} THEN 0
            WHEN {acct_allow} THEN 1
            WHEN perm_sm.role IS NULL THEN 0
            WHEN {sp_acct_deny} THEN 0
            WHEN {sp_acct_allow} THEN 1
            WHEN {role_deny} THEN 0
            WHEN {role_allow} THEN 1
            WHEN {sp_role_deny} THEN 0
            WHEN {sp_role_allow} THEN 1
            WHEN {all_deny} THEN 0
            WHEN {all_allow} THEN 1
            WHEN {sp_all_deny} THEN 0
            WHEN {sp_all_allow} THEN 1
            ELSE {base}
          END
          FROM channel perm_c JOIN space perm_s ON perm_s.id = perm_c.space_id
          LEFT JOIN space_member perm_sm
            ON perm_sm.space_id = perm_s.id AND perm_sm.account_id = {account} AND perm_sm.is_present
          WHERE perm_c.id = coalesce((SELECT perm_pm.channel_id FROM channel perm_t
                                      JOIN message perm_pm ON perm_pm.id = perm_t.parent_message_id
                                      WHERE perm_t.id = {channel} AND perm_t.kind = 'thread'), {channel})), 0)",
        acct_deny = ch("deny", "account", account),
        acct_allow = ch("allow", "account", account),
        sp_acct_deny = sp("deny", "account", account),
        sp_acct_allow = sp("allow", "account", account),
        role_deny = ch("deny", "role", "perm_sm.role"),
        role_allow = ch("allow", "role", "perm_sm.role"),
        sp_role_deny = sp("deny", "role", "perm_sm.role"),
        sp_role_allow = sp("allow", "role", "perm_sm.role"),
        all_deny = ch("deny", "role", "'everyone'"),
        all_allow = ch("allow", "role", "'everyone'"),
        sp_all_deny = sp("deny", "role", "'everyone'"),
        sp_all_allow = sp("allow", "role", "'everyone'"),
        base = base_sql("perm_sm.role", "perm_s.roles", perm),
    )
}

/// SQL boolean: may the account (an SQL expression, e.g. `?2`) use `perm` in the channel (an SQL
/// expression, e.g. `m.channel_id`)? `perm` is a channel permission (not in `SPACE_ONLY`).
pub fn can_sql(account: &str, channel: &str, perm: &str) -> String {
    if perm == "view" {
        one_sql(account, channel, "view")
    } else {
        format!("({} AND {})", one_sql(account, channel, perm), one_sql(account, channel, "view"))
    }
}

/// [`can_sql`] for one account and channel.
pub fn can(conn: &Connection, account: &str, channel: &str, perm: &str) -> rusqlite::Result<bool> {
    conn.prepare_cached(&format!("SELECT {}", can_sql("?1", "?2", perm)))?
        .query_row(params![account, channel], |r| r.get(0))
}

/// SQL boolean: may the account use the whole-space permission `perm` (`invite`, `history`) in the
/// space (SQL expressions)? Only the space levels count; there is no `view` requirement. Aliases
/// differ from [`can_sql`]'s so the two can nest.
pub fn can_space_sql(account: &str, space: &str, perm: &str) -> String {
    assert!(SPACE_ONLY.contains(&perm), "not a space permission: {perm}");
    let sp =
        |list: &str, t: &str, id: &str| override_sql("space_permission", "space_id", "perm_ss.id", list, t, id, perm);
    format!(
        "coalesce((SELECT CASE
            WHEN perm_ss.owner_account_id = {account} THEN 1
            WHEN perm_ssm.role IS NOT NULL AND perm_ss.kind = 'dm' THEN 1
            WHEN perm_ssm.role = 'admin' THEN 1
            WHEN perm_ssm.role IS NULL THEN 0
            WHEN {acct_deny} THEN 0
            WHEN {acct_allow} THEN 1
            WHEN {role_deny} THEN 0
            WHEN {role_allow} THEN 1
            WHEN {all_deny} THEN 0
            WHEN {all_allow} THEN 1
            ELSE {base}
          END
          FROM space perm_ss LEFT JOIN space_member perm_ssm
            ON perm_ssm.space_id = perm_ss.id AND perm_ssm.account_id = {account} AND perm_ssm.is_present
          WHERE perm_ss.id = {space}), 0)",
        acct_deny = sp("deny", "account", account),
        acct_allow = sp("allow", "account", account),
        role_deny = sp("deny", "role", "perm_ssm.role"),
        role_allow = sp("allow", "role", "perm_ssm.role"),
        all_deny = sp("deny", "role", "'everyone'"),
        all_allow = sp("allow", "role", "'everyone'"),
        base = base_sql("perm_ssm.role", "perm_ss.roles", perm),
    )
}

/// [`can_space_sql`] for one account and space.
pub fn can_space(conn: &Connection, account: &str, space: &str, perm: &str) -> rusqlite::Result<bool> {
    conn.prepare_cached(&format!("SELECT {}", can_space_sql("?1", "?2", perm)))?
        .query_row(params![account, space], |r| r.get(0))
}

/// Longest slow mode a channel can set: 6 hours.
pub const SLOW_MODE_MAX_S: u64 = 6 * 3600;

/// Slow mode (SPEC §5.1, OPEN_QUESTIONS Q17 default): a channel's `settings.slow_mode_s` lets each
/// account send one message there every so many seconds, counted on the server's clock when the
/// sends arrive (`received_at`) — a message written offline at 10:00 and synced at 12:00 counts
/// at 12:00, and a device syncing several at once gets the later ones refused. Accounts that may
/// `manage` the channel (its space's owner and admins) are exempt. `Some(reason)` = refused.
pub fn slow_mode(conn: &Connection, author: &str, o: &Op, now: i64) -> anyhow::Result<Option<(String, i64)>> {
    if !matches!(o.kind.as_str(), "message.send" | "message.forward") {
        return Ok(None);
    }
    let Some(channel) = str_of(&o.payload, "channel_id") else { return Ok(None) };
    let settings: Option<String> = conn
        .prepare_cached("SELECT settings FROM channel WHERE id = ?1")?
        .query_row([channel], |r| r.get(0))
        .optional()?;
    let seconds = settings
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.get("slow_mode_s").and_then(Value::as_u64))
        .unwrap_or(0)
        .min(SLOW_MODE_MAX_S);
    if seconds == 0 || can(conn, author, channel, "manage")? {
        return Ok(None);
    }
    let last: Option<i64> = conn
        .prepare_cached("SELECT max(received_at) FROM message WHERE channel_id = ?1 AND account_id = ?2")?
        .query_row(params![channel, author], |r| r.get(0))?;
    let wait = last.map_or(0, |at| at + seconds as i64 * 1000 - now);
    Ok((wait > 0).then(|| {
        (format!("slow mode: one message every {seconds} s here; sending in {} s", (wait + 999) / 1000), wait)
    }))
}

/// The space's owner, or an account present in it (not a guest of one of its channels).
pub fn is_member(conn: &Connection, account: &str, space: &str) -> rusqlite::Result<bool> {
    conn.prepare_cached(
        "SELECT EXISTS (SELECT 1 FROM space WHERE id = ?2 AND owner_account_id = ?1)
             OR EXISTS (SELECT 1 FROM space_member WHERE space_id = ?2 AND account_id = ?1 AND is_present)",
    )?
    .query_row(params![account, space], |r| r.get(0))
}

fn role(conn: &Connection, account: &str, space: &str) -> rusqlite::Result<Option<String>> {
    conn.prepare_cached("SELECT role FROM space_member WHERE space_id = ?1 AND account_id = ?2 AND is_present")?
        .query_row(params![space, account], |r| r.get(0))
        .optional()
}

fn message_channel(conn: &Connection, message: &str) -> rusqlite::Result<Option<(String, Option<String>)>> {
    conn.prepare_cached("SELECT channel_id, account_id FROM message WHERE id = ?1")?
        .query_row([message], |r| Ok((r.get::<_, Option<String>>(0)?.unwrap_or_default(), r.get(1)?)))
        .optional()
}

fn str_of<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
}

/// Why `author` may not write `o` in a space (`None`: it may). Checked at ingest (not for restore
/// pushes, which were checked when first accepted). Only space scopes have permissions; a space
/// that isn't projected yet (its own `space.create`) is left to the scope rules.
pub fn write_denied(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<Option<String>> {
    let Some(space) = o.scope.strip_prefix("space:") else { return Ok(None) };
    let row: Option<(String, Option<String>)> = conn
        .prepare_cached("SELECT owner_account_id, kind FROM space WHERE id = ?1")?
        .query_row([space], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    let Some((owner, kind)) = row else { return Ok(None) };
    // Whatever the op points at (a channel, a message, a thread's parent) must be in the op's own
    // space, whoever the author is: otherwise an op in one space could put a message, reaction,
    // pin or thread into another space's channel.
    if let Some(elsewhere) = foreign_space(conn, o, space)? {
        return Ok(Some(format!("that {elsewhere} is in another space")));
    }
    // Only a message's author edits it, whoever they are to the space: `manage` moderates
    // (delete, restore, pin), it doesn't put words in someone else's mouth (D-073).
    if o.kind == "message.edit"
        && let Some(id) = str_of(&o.payload, "message_id").or_else(|| o.entity())
        && let Some((_, Some(sender))) = message_channel(conn, id)?
        && sender != author
    {
        return Ok(Some("only its author can edit a message".into()));
    }
    // private chats (D-098) are a DM's only (D-155): elsewhere `member_ids` has no effect
    if kind.as_deref() == Some("dm")
        && let Some(why) = private_chat(conn, author, o, space)?
    {
        return Ok(Some(why));
    }
    // what an account shares in a space is its own business, and only shared spaces have it (D-133)
    if o.kind == "space.set_sharing" {
        return Ok(if kind.as_deref() != Some("shared") {
            Some("only shared spaces have sharing settings".into())
        } else if owner != author && role(conn, author, space)?.is_none() {
            Some("only the space's members can do that".into())
        } else {
            None
        });
    }
    if owner == author {
        return Ok(None);
    }
    let role = role(conn, author, space)?;
    // D-133: bringing someone else in takes `invite` (admins have it), in a shared space, and
    // only someone the inviter is connected to (as `POST /spaces/{id}/members` checks)
    if o.kind == "space.join"
        && let Some(invitee) = str_of(&o.payload, "account_id")
        && invitee != author
        && kind.as_deref() == Some("shared")
        && role.as_deref() != Some("admin")
    {
        return Ok(if !can_space(conn, author, space, "invite")? {
            Some("you don't have the invite permission in this space".into())
        } else if !crate::spaces::connected(conn, author, invitee)? {
            Some("you can only add people you follow or who follow you".into())
        } else {
            None
        });
    }
    let manager = role.as_deref() == Some("admin") || (kind.as_deref() == Some("dm") && role.is_some());
    let need = |channel: Option<&str>, perm: &str| -> anyhow::Result<Option<String>> {
        let Some(channel) = channel else { return Ok(Some("no such channel".into())) };
        Ok((!can(conn, author, channel, perm)?)
            .then(|| format!("you don't have the {perm} permission in this channel")))
    };
    let p = &o.payload;
    let k = o.kind.as_str();
    Ok(match k {
        "space.leave" if str_of(p, "account_id") == Some(author) && role.is_some() => None,
        _ if k.starts_with("space.") => {
            (!manager || kind.as_deref() == Some("dm")).then(|| "only the space's owner or an admin can do that".into())
        }
        "channel.create" => match str_of(p, "parent_message_id") {
            Some(parent) => match message_channel(conn, parent)? {
                Some((channel, _)) => need(Some(&channel), "thread")?,
                None => Some("the thread's message isn't here yet".into()),
            },
            None => (!manager).then(|| "only the space's owner or an admin can add channels".into()),
        },
        _ if k.starts_with("channel.") => need(o.entity(), "manage")?,
        // D-133: files, polls and asides each need their own permission on top of `send`
        "message.send" | "message.forward" => {
            let channel = str_of(p, "channel_id");
            let mut wanted = vec!["send"];
            if p.get("attachments").and_then(Value::as_array).is_some_and(|a| !a.is_empty()) {
                wanted.push("attach");
            }
            if p.get("poll").is_some_and(|v| !v.is_null()) {
                wanted.push("poll");
            }
            if !crate::visibility::is_public(p.get("visibility")) {
                wanted.push("aside");
            }
            let mut refused = None;
            for perm in wanted {
                refused = need(channel, perm)?;
                if refused.is_some() {
                    break;
                }
            }
            refused
        }
        "message.pin" | "message.unpin" => {
            let channel = o.entity().map(|m| message_channel(conn, m)).transpose()?.flatten().map(|(c, _)| c);
            need(channel.as_deref(), "pin")?
        }
        _ if k.starts_with("message.") => {
            let id = str_of(p, "message_id").or_else(|| o.entity()).unwrap_or_default();
            match message_channel(conn, id)? {
                Some((channel, owner)) if owner.as_deref() == Some(author) => need(Some(&channel), "view")?,
                Some((channel, _)) => need(Some(&channel), "manage")?,
                // an own edit that arrived before its send: members may, guests wait
                None => role.is_none().then(|| "no such message".into()),
            }
        }
        _ if k.starts_with("reaction.") => {
            let id = str_of(p, "target_id").or_else(|| str_of(p, "message_id")).or_else(|| o.entity());
            match id.map(|m| message_channel(conn, m)).transpose()?.flatten() {
                Some((channel, _)) => need(Some(&channel), "react")?,
                None => Some("no such message".into()),
            }
        }
        _ if k.starts_with("read.") => {
            let channel = match str_of(p, "channel_id") {
                Some(c) => Some(c.to_string()),
                None => {
                    str_of(p, "message_id").map(|m| message_channel(conn, m)).transpose()?.flatten().map(|(c, _)| c)
                }
            };
            need(channel.as_deref(), "view")?
        }
        // files are only ever reachable through a message someone may view
        _ if k.starts_with("attachment.") => None,
        _ => role.is_none().then(|| "only the space's members can do that".into()),
    })
}

/// D-098: the members of `account` a chat in a DM space is kept to (its `member_ids`, in their
/// order; a thread uses its parent message's channel), then everyone inside any of them who is a
/// subsystem ([with_parts]). Empty: the account isn't limited there. Only DM spaces have private
/// chats (D-155): a channel listing members in a shared space is an ordinary channel, and the
/// internal space's members' DMs stay soft (D-097) and never count.
pub fn kept_to(conn: &Connection, account: &str, channel: &str) -> rusqlite::Result<Vec<String>> {
    let listed = listed_in(conn, account, channel)?;
    with_parts(conn, account, listed)
}

fn listed_in(conn: &Connection, account: &str, channel: &str) -> rusqlite::Result<Vec<String>> {
    conn.prepare_cached(
        "SELECT x.value FROM channel c JOIN space s ON s.id = c.space_id AND s.kind = 'dm'
         CROSS JOIN json_each(CASE WHEN json_valid(c.member_ids) THEN c.member_ids ELSE '[]' END) x
         JOIN member mb ON mb.id = x.value AND mb.account_id = ?1
         WHERE c.id = coalesce((SELECT pm.channel_id FROM channel t JOIN message pm ON pm.id = t.parent_message_id
                                WHERE t.id = ?2 AND t.kind = 'thread'), ?2)
         ORDER BY x.key",
    )?
    .query_map(params![account, channel], |r| r.get(0))?
    .collect()
}

/// A chat with a member who is a subsystem (D-092) includes everyone inside them, recursively
/// (owner, 2026-11-02): the `member_subsystem:<id>` account pref names their subsystem group, and
/// its present, active members are inside. One with someone inside never reaches the subsystem.
pub(crate) fn with_parts(conn: &Connection, account: &str, mut ids: Vec<String>) -> rusqlite::Result<Vec<String>> {
    if ids.is_empty() {
        return Ok(ids);
    }
    let mut inside = conn.prepare_cached(
        "SELECT gm.member_id FROM pref p
         JOIN member_group g ON g.id = json_extract(p.value, '$') AND g.account_id = ?1
              AND g.kind = 'subsystem' AND g.deleted_at IS NULL
         JOIN group_membership gm ON gm.group_id = g.id AND gm.is_present
         JOIN member m ON m.id = gm.member_id AND m.account_id = ?1 AND m.archived_at IS NULL AND m.deleted_at IS NULL
         WHERE p.account_id = ?1 AND p.device_id = '' AND p.key = 'member_subsystem:' || ?2
         ORDER BY gm.member_id",
    )?;
    let mut i = 0;
    while i < ids.len() {
        let found: Vec<String> =
            inside.query_map(params![account, ids[i]], |r| r.get(0))?.collect::<rusqlite::Result<_>>()?;
        for id in found {
            if !ids.contains(&id) {
                ids.push(id);
            }
        }
        i += 1;
    }
    Ok(ids)
}

fn member_account(conn: &Connection, member: &str) -> rusqlite::Result<Option<String>> {
    conn.prepare_cached("SELECT account_id FROM member WHERE id = ?1")?
        .query_row([member], |r| r.get(0))
        .optional()
        .map(Option::flatten)
}

fn string_list(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).map(str::to_string).collect()
}

/// D-098, private chats in a DM space (D-155): an account whose members a chat is kept
/// to (`member_ids`) speaks there only as them; `member_ids` names only members of the space's
/// accounts; and only an account changes which of its own members are listed, so the other side
/// can't open someone's private chat to the rest of their system. `Some(reason)`: refused.
fn private_chat(conn: &Connection, author: &str, o: &Op, space: &str) -> anyhow::Result<Option<String>> {
    let p = &o.payload;
    let k = o.kind.as_str();
    if matches!(k, "channel.create" | "channel.set") {
        let Some(listed) = p.get("member_ids") else { return Ok(None) };
        let listed = string_list(Some(listed));
        for id in &listed {
            // a member not projected yet is left alone, like any early reference
            if let Some(account) = member_account(conn, id)?
                && !is_member(conn, &account, space)?
            {
                return Ok(Some("a private chat can only name members of the accounts in it".into()));
            }
        }
        if let Some(channel) = o.entity() {
            let before: Option<Option<String>> = conn
                .prepare_cached("SELECT member_ids FROM channel WHERE id = ?1")?
                .query_row([channel], |r| r.get(0))
                .optional()?;
            // Create is a field-level LWW upsert too. A fresh op id targeting an existing
            // channel must preserve the peer's entries exactly as a set would. A first create
            // may still name either side, and an omitted list does not write this field.
            let Some(before) = before else { return Ok(None) };
            let before = string_list(before.and_then(|s| serde_json::from_str::<Value>(&s).ok()).as_ref());
            let others = |ids: &[String]| -> rusqlite::Result<std::collections::BTreeSet<String>> {
                let mut out = std::collections::BTreeSet::new();
                for id in ids {
                    if member_account(conn, id)?.as_deref() != Some(author) {
                        out.insert(id.clone());
                    }
                }
                Ok(out)
            };
            if others(&before)? != others(&listed)? {
                return Ok(Some("only an account changes which of its members a private chat is kept to".into()));
            }
        }
        return Ok(None);
    }
    let target = |id: Option<&str>| -> rusqlite::Result<Option<String>> {
        Ok(match id {
            Some(m) => message_channel(conn, m)?.map(|(c, _)| c),
            None => None,
        })
    };
    let channel = match k {
        "message.send" | "message.forward" => str_of(p, "channel_id").map(str::to_string),
        "message.edit" => target(str_of(p, "message_id").or_else(|| o.entity()))?,
        "reaction.add" | "reaction.remove" => {
            target(str_of(p, "target_id").or_else(|| str_of(p, "message_id")).or_else(|| o.entity()))?
        }
        _ => return Ok(None),
    };
    let Some(channel) = channel else { return Ok(None) };
    let kept = kept_to(conn, author, &channel)?;
    if kept.is_empty() {
        return Ok(None);
    }
    let mut speakers = string_list(p.get("authors"));
    for s in p.get("segments").and_then(Value::as_array).into_iter().flatten() {
        speakers.extend(string_list(s.get("authors")));
    }
    speakers.extend(str_of(p, "member_id").map(str::to_string));
    Ok((!speakers.iter().all(|s| kept.contains(s)))
        .then(|| "this private chat is kept to some of your members: only they can speak here".into()))
}

/// What `o` refers to in a space other than `space` ("channel" or "message"), if anything.
/// Things not projected yet are left alone (their op waits in the log like any early op).
fn foreign_space(conn: &Connection, o: &Op, space: &str) -> anyhow::Result<Option<&'static str>> {
    let p = &o.payload;
    let k = o.kind.as_str();
    let channel_space = |id: &str| -> rusqlite::Result<Option<String>> {
        conn.prepare_cached(
            "SELECT c.space_id FROM channel c WHERE c.id = coalesce((SELECT pm.channel_id FROM channel t
               JOIN message pm ON pm.id = t.parent_message_id WHERE t.id = ?1 AND t.kind = 'thread'), ?1)",
        )?
        .query_row([id], |r| r.get(0))
        .optional()
        .map(Option::flatten)
    };
    let message_space = |id: &str| -> rusqlite::Result<Option<String>> {
        match message_channel(conn, id)? {
            Some((channel, _)) => channel_space(&channel),
            None => Ok(None),
        }
    };
    let (what, home) = if k == "channel.create" {
        // a channel is created in the op's space: `space_id` may only repeat it
        if str_of(p, "space_id").is_some_and(|s| s != space) {
            return Ok(Some("channel"));
        }
        if let Some(id) = o.entity() {
            let existing: Option<(Option<String>, Option<String>, Option<String>)> = conn
                .prepare_cached("SELECT space_id, kind, parent_message_id FROM channel WHERE id = ?1")?
                .query_row([id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
                .optional()?;
            if let Some((home, kind, parent)) = existing {
                if home.as_deref().is_some_and(|home| home != space) {
                    return Ok(Some("channel"));
                }
                // These are create-only fields on channel.set. Recreating the same id must
                // not move a channel across scopes or redirect a private thread to an open
                // parent (or turn a private channel into such a thread). Missing fields are
                // untouched by the core's LWW fold; matching repeated creates remain valid.
                for (key, before) in [("space_id", home), ("kind", kind), ("parent_message_id", parent)] {
                    if let Some(after) = p.get(key)
                        && after.as_str() != before.as_deref()
                    {
                        return Ok(Some("channel (its structural fields are create-only)"));
                    }
                }
            }
        }
        match str_of(p, "parent_message_id") {
            Some(parent) => ("message", message_space(parent)?),
            None => return Ok(None),
        }
    } else if k.starts_with("channel.") {
        ("channel", o.entity().map(channel_space).transpose()?.flatten())
    } else if matches!(k, "message.send" | "message.forward")
        || (k.starts_with("read.") && str_of(p, "channel_id").is_some())
    {
        ("channel", str_of(p, "channel_id").map(channel_space).transpose()?.flatten())
    } else if k.starts_with("message.") || k.starts_with("reaction.") || k.starts_with("read.") {
        let id = str_of(p, "target_id").or_else(|| str_of(p, "message_id")).or_else(|| o.entity());
        if k.starts_with("reaction.") && str_of(p, "target_type") == Some("post") {
            return Ok(None);
        }
        ("message", id.map(message_space).transpose()?.flatten())
    } else {
        return Ok(None);
    };
    Ok(home.is_some_and(|h| h != space).then_some(what))
}

/// Keep a guest's scope in step with its overrides: an account that isn't in the space has the
/// space's scope exactly while some channel of it has an account override allowing it `view`
/// (and not denying it). Members and the owner are left alone (`project::space_access`).
pub fn refresh_guest(conn: &Connection, account: &str, space: &str) -> rusqlite::Result<()> {
    if is_member(conn, account, space)? {
        return Ok(());
    }
    let guest: bool = conn
        .prepare_cached(
            "SELECT EXISTS (SELECT 1 FROM channel_permission cp JOIN channel c ON c.id = cp.channel_id
               WHERE c.space_id = ?2 AND cp.target_type = 'account' AND cp.target_id = ?1
                 AND EXISTS (SELECT 1 FROM json_each(cp.allow) WHERE value = 'view')
                 AND NOT EXISTS (SELECT 1 FROM json_each(cp.deny) WHERE value = 'view'))",
        )?
        .query_row(params![account, space], |r| r.get(0))?;
    let scope = format!("space:{space}");
    if guest {
        conn.execute("INSERT OR IGNORE INTO scope_access(account_id, scope) VALUES (?1, ?2)", params![account, scope])?;
    } else {
        conn.execute("DELETE FROM scope_access WHERE account_id = ?1 AND scope = ?2", params![account, scope])?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db;

    /// A shared space `s` owned by `owner` with channel `ch`, a thread `th` under message `m` in it,
    /// and members with the given roles.
    fn space(kind: &str, roles: &[(&str, &str)]) -> Connection {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        c.execute(
            "INSERT INTO space(id,kind,owner_account_id,roles,created_at) VALUES ('s',?1,'owner',
               '[{\"id\":\"mod\",\"name\":\"Mods\",\"perms\":[\"view\",\"send\",\"pin\",\"manage\"]}]',0)",
            [kind],
        )
        .unwrap();
        c.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('ch','s','text',0)", []).unwrap();
        c.execute("INSERT INTO message(id,channel_id,account_id,occurred_at) VALUES ('m','ch','owner',0)", []).unwrap();
        c.execute(
            "INSERT INTO channel(id,space_id,kind,parent_message_id,created_at) VALUES ('th','s','thread','m',0)",
            [],
        )
        .unwrap();
        for (account, role) in roles {
            c.execute(
                "INSERT INTO space_member(space_id,account_id,role,joined_hlc) VALUES ('s',?1,?2,'1:0:1')",
                [account, role],
            )
            .unwrap();
        }
        c
    }

    fn set(c: &Connection, target_type: &str, target: &str, allow: &[&str], deny: &[&str]) {
        c.execute(
            "INSERT OR REPLACE INTO channel_permission(channel_id,target_type,target_id,allow,deny,hlc)
             VALUES ('ch',?1,?2,?3,?4,'1:0:1')",
            params![target_type, target, serde_json::json!(allow).to_string(), serde_json::json!(deny).to_string()],
        )
        .unwrap();
    }

    /// The channel permissions the account has there.
    fn perms_of(c: &Connection, account: &str, channel: &str) -> Vec<&'static str> {
        CHANNEL.iter().copied().filter(|p| can(c, account, channel, p).unwrap()).collect()
    }

    const CHANNEL: &[&str] = &["view", "send", "react", "thread", "pin", "manage", "attach", "poll", "aside"];
    const MEMBER: &[&str] = &["view", "send", "react", "thread", "pin", "attach", "poll", "aside"];

    #[test]
    fn roles_give_their_base_permissions() {
        let c = space(
            "shared",
            &[("mem", "member"), ("ro", "read_only"), ("adm", "admin"), ("m2", "mod"), ("odd", "nope")],
        );
        assert_eq!(perms_of(&c, "owner", "ch"), CHANNEL);
        assert_eq!(perms_of(&c, "adm", "ch"), CHANNEL);
        assert_eq!(perms_of(&c, "mem", "ch"), MEMBER);
        assert_eq!(perms_of(&c, "ro", "ch"), ["view", "react", "attach", "poll", "aside"]);
        assert_eq!(
            perms_of(&c, "m2", "ch"),
            ["view", "send", "pin", "manage", "attach", "poll", "aside"],
            "a custom role has what it lists, and what every role had before D-133"
        );
        assert!(perms_of(&c, "odd", "ch").is_empty(), "an unknown role has nothing");
        assert!(perms_of(&c, "stranger", "ch").is_empty());
        assert!(perms_of(&c, "mem", "missing").is_empty(), "an unknown channel grants nothing");
        assert_eq!(perms_of(&c, "mem", "th"), perms_of(&c, "mem", "ch"), "a thread has its parent's channel's");
    }

    #[test]
    fn the_most_specific_override_decides_and_deny_wins_a_tie() {
        let c = space("shared", &[("mem", "member"), ("ro", "read_only"), ("adm", "admin")]);
        // a private channel: nobody but the owner and admins…
        set(&c, "role", "everyone", &[], &["view"]);
        assert!(perms_of(&c, "mem", "ch").is_empty(), "no view, nothing else either");
        assert!(perms_of(&c, "ro", "th").is_empty());
        assert_eq!(perms_of(&c, "adm", "ch"), CHANNEL, "admins aren't bound by overrides");
        // …but read_only members may look, and one member may talk
        set(&c, "role", "read_only", &["view"], &[]);
        assert_eq!(perms_of(&c, "ro", "ch"), ["view", "react", "attach", "poll", "aside"]);
        set(&c, "account", "mem", &["view", "manage"], &[]);
        assert_eq!(perms_of(&c, "mem", "ch"), CHANNEL, "the account level beats the everyone level");
        // at one level, deny beats allow
        set(&c, "account", "mem", &["view", "send"], &["send"]);
        assert_eq!(perms_of(&c, "mem", "ch"), ["view", "react", "thread", "pin", "attach", "poll", "aside"]);
        // a role deny beats an everyone allow, and an account allow beats a role deny
        set(&c, "role", "everyone", &["manage"], &[]);
        set(&c, "role", "member", &[], &["manage", "pin"]);
        set(&c, "account", "mem", &["pin"], &[]);
        assert_eq!(perms_of(&c, "mem", "ch"), MEMBER);
    }

    fn set_space(c: &Connection, target_type: &str, target: &str, allow: &[&str], deny: &[&str]) {
        c.execute(
            "INSERT OR REPLACE INTO space_permission(space_id,target_type,target_id,allow,deny,hlc)
             VALUES ('s',?1,?2,?3,?4,'1:0:1')",
            params![target_type, target, serde_json::json!(allow).to_string(), serde_json::json!(deny).to_string()],
        )
        .unwrap();
    }

    #[test]
    fn space_levels_sit_under_the_channel_ones() {
        let c = space("shared", &[("mem", "member"), ("ro", "read_only"), ("adm", "admin")]);
        assert!(!can_space(&c, "mem", "s", "invite").unwrap(), "inviting starts off");
        assert!(can_space(&c, "mem", "s", "history").unwrap(), "history starts on, as before");
        assert!(can_space(&c, "adm", "s", "invite").unwrap() && can_space(&c, "owner", "s", "invite").unwrap());
        assert!(!can_space(&c, "stranger", "s", "history").unwrap());
        set_space(&c, "role", "everyone", &["invite"], &["history", "attach"]);
        assert!(can_space(&c, "mem", "s", "invite").unwrap() && !can_space(&c, "ro", "s", "history").unwrap());
        assert!(!can(&c, "mem", "ch", "attach").unwrap(), "a space-wide deny reaches every channel");
        assert!(!can(&c, "mem", "th", "attach").unwrap(), "and its threads");
        set(&c, "role", "everyone", &["attach"], &[]);
        assert!(can(&c, "mem", "ch", "attach").unwrap(), "a channel's override beats the space's");
        set_space(&c, "role", "member", &[], &["attach"]);
        assert!(!can(&c, "mem", "ch", "attach").unwrap(), "a role beats everyone, wherever set");
        set_space(&c, "account", "mem", &["attach"], &[]);
        assert!(can(&c, "mem", "ch", "attach").unwrap(), "the account beats its role");
        assert!(can(&c, "adm", "ch", "attach").unwrap() && can_space(&c, "adm", "s", "history").unwrap());
    }

    #[test]
    fn guests_and_dms() {
        let c = space("shared", &[("mem", "member")]);
        set(&c, "role", "everyone", &["view"], &[]);
        assert!(perms_of(&c, "guest", "ch").is_empty(), "role overrides never reach a guest");
        set(&c, "account", "guest", &["view", "react"], &[]);
        assert_eq!(perms_of(&c, "guest", "ch"), ["view", "react"]);
        c.execute(
            "INSERT INTO space_permission(space_id,target_type,target_id,allow,deny,hlc) VALUES ('s','account','guest','[\"send\"]','[]','1:0:1')",
            [],
        )
        .unwrap();
        assert_eq!(perms_of(&c, "guest", "ch"), ["view", "react"], "the space's overrides never reach a guest");
        assert!(!is_member(&c, "guest", "s").unwrap());
        refresh_guest(&c, "guest", "s").unwrap();
        let has = |c: &Connection| -> bool {
            c.query_row(
                "SELECT EXISTS(SELECT 1 FROM scope_access WHERE account_id='guest' AND scope='space:s')",
                [],
                |r| r.get(0),
            )
            .unwrap()
        };
        assert!(has(&c), "an account allowed to view a channel gets the space's scope");
        set(&c, "account", "guest", &["view"], &["view"]);
        refresh_guest(&c, "guest", "s").unwrap();
        assert!(!has(&c), "and loses it with the last such override");

        let dm = space("dm", &[("a", "member"), ("b", "member")]);
        set(&dm, "account", "b", &[], &["send"]);
        assert_eq!(perms_of(&dm, "b", "ch"), CHANNEL, "in a DM both sides can do everything");
        assert!(can_space(&dm, "b", "s", "history").unwrap() && can_space(&dm, "b", "s", "invite").unwrap());
    }
}
