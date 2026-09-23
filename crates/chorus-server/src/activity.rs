//! Activity notifications to *other* accounts in a shared space (NOTIFICATIONS.md §7, M8.4):
//! direct messages, mentions of one of their members, and replies to their messages.
//!
//! Queued on live ingest of `message.send` (never on rebuild), delivered by the notifier loop
//! within seconds, as an inbox row plus an encrypted push. The author's own account is never
//! notified. Messages with a restricted `visibility` are skipped entirely until hidden messages
//! (M5.7) define who may see them — better silent than leaking.

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

/// Queue notifications for a freshly accepted op (inside the ingest transaction).
pub fn on_op(conn: &Connection, o: &Op, now: i64) -> anyhow::Result<()> {
    if o.kind != "message.send" {
        return Ok(());
    }
    let Some(author) = o.account_id.as_deref() else { return Ok(()) };
    let p = &o.payload;
    if p.get("visibility").is_some_and(|v| !v.is_null()) {
        return Ok(());
    }
    let channel_id = p.get("channel_id").and_then(Value::as_str).unwrap_or_default();
    let (channel_name, space_kind): (Option<String>, Option<String>) = conn
        .query_row(
            "SELECT c.name, s.kind FROM channel c LEFT JOIN space s ON s.id = c.space_id WHERE c.id = ?1",
            [channel_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?
        .unwrap_or((None, None));
    let is_dm = space_kind.as_deref() == Some("dm");

    // why each other account should hear about it
    let mut why: BTreeMap<String, &'static str> = BTreeMap::new();
    let mut st = conn.prepare_cached("SELECT account_id FROM scope_access WHERE scope = ?1 AND account_id <> ?2")?;
    let others: Vec<String> = st.query_map(params![o.scope, author], |r| r.get(0))?.collect::<Result<_, _>>()?;
    if others.is_empty() {
        return Ok(());
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
    for e in p.get("entities").and_then(Value::as_array).into_iter().flatten() {
        if e.get("type").and_then(Value::as_str) != Some("mention") {
            continue;
        }
        if e.get("target_type").and_then(Value::as_str) == Some("member")
            && let Some(m) = e.get("target_id").and_then(Value::as_str)
            && let Some(a) = account_of_member(conn, m)?.filter(|a| others.contains(a))
        {
            why.insert(a, "mention");
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
    if why.is_empty() {
        return Ok(());
    }

    let mut authors = Vec::new();
    for m in p.get("authors").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str) {
        authors.push(name_of(conn, m)?.unwrap_or_else(|| "Someone".into()));
    }
    let who = if authors.is_empty() { "Someone".to_string() } else { authors.join(" & ") };
    let text: String = p.get("text").and_then(Value::as_str).unwrap_or_default().chars().take(280).collect();
    for (recipient, kind) in why {
        let title = match (kind, &channel_name) {
            ("dm", _) => who.clone(),
            (_, Some(c)) => format!("{who} · #{c}"),
            _ => who.clone(),
        };
        let payload = json!({
            "t": "message", "kind": kind, "title": title, "text": text,
            "channel_id": channel_id, "message_id": o.entity_id, "scope": o.scope,
        });
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
         WHERE kind IN ('mention', 'dm', 'reply', 'message') AND delivered_at IS NULL AND cancelled_at IS NULL AND due_at <= ?1
         ORDER BY due_at, id LIMIT 500",
    )?;
    let rows: Vec<(String, String, String)> =
        st.query_map([now], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect::<Result<_, _>>()?;
    let mut pushes = Vec::new();
    for (id, recipient, payload) in rows {
        conn.execute("UPDATE notification SET delivered_at = ?2 WHERE id = ?1", params![id, now])?;
        let mut p: Value = serde_json::from_str(&payload).unwrap_or(json!({}));
        p["id"] = json!(id);
        pushes.extend(push::prepare(conn, &recipient, &p)?);
    }
    Ok(pushes)
}
