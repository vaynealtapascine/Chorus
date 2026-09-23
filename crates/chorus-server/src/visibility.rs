//! Structured chat visibility shared by notifications and cross-account reads.

use chorus_core::op::Op;
use chorus_core::sync::Digest;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::Value;

use crate::oplog;

/// A message that every member of its space may see. NULL is the legacy/default public value.
pub const PUBLIC_MESSAGE_SQL: &str = "(m.visibility IS NULL OR json_extract(m.visibility, '$.mode') = 'all')";

pub fn is_public(value: Option<&Value>) -> bool {
    value.is_none_or(|v| v.is_null() || v.get("mode").and_then(Value::as_str) == Some("all"))
}

fn message_public(conn: &Connection, id: &str) -> anyhow::Result<bool> {
    let row: Option<Option<String>> =
        conn.query_row("SELECT visibility FROM message WHERE id = ?1", [id], |r| r.get(0)).optional()?;
    Ok(match row {
        Some(None) => true,
        Some(Some(s)) => serde_json::from_str::<Value>(&s).is_ok_and(|v| is_public(Some(&v))),
        None => false, // A related op arrived before its message; reveal it with the public send.
    })
}

/// A participant cannot mutate a private aside owned by another account, even with a guessed
/// message id. Normal public-message authorization remains in the existing scope rules.
pub fn related_write_allowed(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<bool> {
    let message_id = if matches!(o.kind.as_str(), "message.send" | "message.forward") {
        o.payload.get("reply_to").and_then(Value::as_str)
    } else if o.kind.starts_with("message.") || o.kind.starts_with("reaction.") || o.kind.starts_with("read.") {
        o.payload
            .get("message_id")
            .and_then(Value::as_str)
            .or_else(|| o.kind.starts_with("message.").then(|| o.entity()).flatten())
    } else {
        None
    };
    let Some(id) = message_id else { return Ok(true) };
    let row: Option<(Option<String>, Option<String>)> = conn
        .query_row("SELECT account_id, visibility FROM message WHERE id=?1", [id], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    let Some((owner, visibility)) = row else { return Ok(true) };
    if owner.as_deref() == Some(author) {
        return Ok(true);
    }
    Ok(visibility.as_deref().is_none_or(|s| serde_json::from_str::<Value>(s).is_ok_and(|v| is_public(Some(&v)))))
}

/// The effective per-account sync rule for a shared-space op. The sender always retains their
/// own ops. Attachment metadata waits for a public message link, avoiding pre-send leakage.
pub fn op_visible_to(conn: &Connection, account: &str, o: &Op) -> anyhow::Result<bool> {
    if o.account_id.as_deref() == Some(account) || !o.scope.starts_with("space:") {
        return Ok(true);
    }
    if o.kind == "message.send" || o.kind == "message.forward" {
        return Ok(is_public(o.payload.get("visibility")));
    }
    if o.kind.starts_with("message.") {
        let id = o.payload.get("message_id").and_then(Value::as_str).or_else(|| o.entity());
        return id.map_or(Ok(false), |id| message_public(conn, id));
    }
    if (o.kind.starts_with("reaction.") || o.kind.starts_with("read."))
        && let Some(id) = o.payload.get("message_id").and_then(Value::as_str)
    {
        return message_public(conn, id);
    }
    if o.kind.starts_with("attachment.") {
        let Some(id) = o.entity() else { return Ok(false) };
        return Ok(conn.query_row(
            &format!(
                "SELECT EXISTS(
                SELECT 1 FROM item_attachment ia JOIN message m ON m.id=ia.owner_id
                WHERE ia.owner_type='message' AND ia.attachment_id=?1 AND {})",
                PUBLIC_MESSAGE_SQL
            ),
            [id],
            |r| r.get(0),
        )?);
    }
    Ok(true)
}

/// The digest must contain exactly the op ids this account can receive in catch-up.
pub fn visible_digest(conn: &Connection, account: &str, scope: &str) -> anyhow::Result<Digest> {
    let mut digest = Digest::default();
    let mut cursor = 0;
    loop {
        let page = oplog::scope_after(conn, scope, cursor, 1000)?;
        let Some(last) = page.last().and_then(|o| o.seq) else { break };
        cursor = last;
        let short = page.len() < 1000;
        for op in page {
            if op_visible_to(conn, account, &op)? {
                digest.add(&op.id);
            }
        }
        if short {
            break;
        }
    }
    Ok(digest)
}

/// A public send can make earlier out-of-order edits and pre-send attachment ops readable.
/// Re-send them live; op ids make duplicates harmless and catch-up already includes them.
pub fn backfill_for_public_send(conn: &Connection, o: &Op) -> anyhow::Result<Vec<Op>> {
    if !matches!(o.kind.as_str(), "message.send" | "message.forward") || !is_public(o.payload.get("visibility")) {
        return Ok(Vec::new());
    }
    let Some(message_id) = o.entity() else { return Ok(Vec::new()) };
    let before = o.seq.unwrap_or(i64::MAX);
    let mut earlier = oplog::for_entity(conn, message_id)?;
    for id in o.payload.get("attachments").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str) {
        earlier.extend(oplog::for_entity(conn, id)?);
    }
    let mut st = conn.prepare(
        "SELECT id FROM op WHERE scope=?1 AND seq<?2 AND status='applied'
         AND json_extract(payload, '$.message_id')=?3",
    )?;
    let ids: Vec<String> =
        st.query_map(params![o.scope, before, message_id], |r| r.get(0))?.collect::<Result<_, _>>()?;
    for id in ids {
        if let Some(op) = oplog::by_id(conn, &id)? {
            earlier.push(op);
        }
    }
    Ok(earlier.into_iter().filter(|op| op.scope == o.scope && op.seq.is_some_and(|seq| seq < before)).collect())
}
