//! Structured chat visibility shared by notifications and cross-account reads.

use std::collections::HashSet;

use chorus_core::op::Op;
use chorus_core::sync::Digest;
use rusqlite::{Connection, OptionalExtension, params, params_from_iter};
use serde_json::Value;

use crate::oplog;

/// A message that every member of its space may see: its own visibility is public (NULL is the
/// legacy/default public value), and it isn't in a thread under a message that isn't — a thread is
/// as private as its parent.
pub const PUBLIC_MESSAGE_SQL: &str = "((m.visibility IS NULL OR json_extract(m.visibility, '$.mode') = 'all')
    AND NOT EXISTS (SELECT 1 FROM channel tc JOIN message pm ON pm.id = tc.parent_message_id
        WHERE tc.id = m.channel_id AND tc.kind = 'thread'
          AND NOT (pm.visibility IS NULL OR json_extract(pm.visibility, '$.mode') = 'all')))";

pub fn is_public(value: Option<&Value>) -> bool {
    value.is_none_or(|v| v.is_null() || v.get("mode").and_then(Value::as_str) == Some("all"))
}

fn message_public(conn: &Connection, id: &str) -> anyhow::Result<bool> {
    // a related op that arrived before its message stays hidden; the public send reveals it
    Ok(conn.query_row(
        &format!("SELECT EXISTS(SELECT 1 FROM message m WHERE m.id = ?1 AND {PUBLIC_MESSAGE_SQL})"),
        [id],
        |r| r.get(0),
    )?)
}

/// A channel everyone in its space may see: anything but a thread under a non-public message
/// (a thread whose parent hasn't arrived yet stays hidden until it does).
fn channel_public(conn: &Connection, channel: &str) -> anyhow::Result<bool> {
    let parent: Option<Option<String>> = conn
        .query_row("SELECT parent_message_id FROM channel WHERE id = ?1 AND kind = 'thread'", [channel], |r| r.get(0))
        .optional()?;
    match parent.flatten() {
        Some(p) => message_public(conn, &p),
        None => Ok(true),
    }
}

/// A participant cannot mutate a private aside owned by another account, even with a guessed
/// message id. Normal public-message authorization remains in the existing scope rules.
pub fn related_write_allowed(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<bool> {
    if o.kind == "post.create"
        && let Some(parent) = o.payload.get("reply_to").and_then(Value::as_str)
    {
        let readable = crate::posts::readable_sql("?2");
        let sql = format!("SELECT p.deleted_at IS NULL AND {readable} FROM post p WHERE p.id=?1");
        // Own offline replies may reach the server before their parent. An existing parent,
        // including one owned by another account, must be readable when the reply is accepted.
        let visible: Option<bool> = conn.query_row(&sql, params![parent, author], |r| r.get(0)).optional()?;
        return Ok(visible.unwrap_or(true));
    }
    if matches!(o.kind.as_str(), "post.react" | "post.unreact") {
        let Some(target) = o.payload.get("target_id").and_then(Value::as_str) else { return Ok(false) };
        let Some(member) = o.payload.get("member_id").and_then(Value::as_str) else { return Ok(false) };
        let owned: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM member WHERE id=?1 AND account_id=?2 AND deleted_at IS NULL)",
            params![member, author],
            |r| r.get(0),
        )?;
        if !owned {
            return Ok(false);
        }
        let readable = crate::posts::readable_sql("?2");
        let sql = format!("SELECT p.deleted_at IS NULL AND {readable} FROM post p WHERE p.id=?1");
        // A reaction may sync before its post. The row, once present, is always checked through
        // the same audience predicate as GET /posts; an absent target reveals no post data.
        let visible: Option<bool> = conn.query_row(&sql, params![target, author], |r| r.get(0)).optional()?;
        return Ok(visible.unwrap_or(true));
    }
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
fn visible_with(
    account: &str,
    o: &Op,
    message_public: &mut impl FnMut(&str) -> anyhow::Result<bool>,
    channel_public: &mut impl FnMut(&str) -> anyhow::Result<bool>,
    attachment_public: &mut impl FnMut(&str) -> anyhow::Result<bool>,
) -> anyhow::Result<bool> {
    if o.account_id.as_deref() == Some(account) || !o.scope.starts_with("space:") {
        return Ok(true);
    }
    if o.kind == "message.send" || o.kind == "message.forward" {
        let channel = o.payload.get("channel_id").and_then(Value::as_str).unwrap_or_default();
        return Ok(is_public(o.payload.get("visibility")) && channel_public(channel)?);
    }
    if o.kind.starts_with("channel.") {
        // a new thread names its parent; later channel ops find it in the projection
        if let Some(parent) = o.payload.get("parent_message_id").and_then(Value::as_str) {
            return message_public(parent);
        }
        return o.entity().map_or(Ok(true), channel_public);
    }
    if o.kind.starts_with("message.") {
        let id = o.payload.get("message_id").and_then(Value::as_str).or_else(|| o.entity());
        return id.map_or(Ok(false), message_public);
    }
    if (o.kind.starts_with("reaction.") || o.kind.starts_with("read."))
        && let Some(id) = o.payload.get("message_id").and_then(Value::as_str)
    {
        return message_public(id);
    }
    if o.kind.starts_with("attachment.") {
        let Some(id) = o.entity() else { return Ok(false) };
        return attachment_public(id);
    }
    Ok(true)
}

fn attachment_public(conn: &Connection, id: &str) -> anyhow::Result<bool> {
    Ok(conn.query_row(
        &format!(
            "SELECT EXISTS(SELECT 1 FROM item_attachment ia JOIN message m ON m.id=ia.owner_id
             WHERE ia.owner_type='message' AND ia.attachment_id=?1 AND {PUBLIC_MESSAGE_SQL})"
        ),
        [id],
        |r| r.get(0),
    )?)
}

pub fn op_visible_to(conn: &Connection, account: &str, o: &Op) -> anyhow::Result<bool> {
    visible_with(account, o, &mut |id| message_public(conn, id), &mut |id| channel_public(conn, id), &mut |id| {
        attachment_public(conn, id)
    })
}

fn matching_ids(conn: &Connection, ids: &HashSet<String>, query: &str) -> anyhow::Result<HashSet<String>> {
    if ids.is_empty() {
        return Ok(HashSet::new());
    }
    let marks = std::iter::repeat_n("?", ids.len()).collect::<Vec<_>>().join(",");
    let sql = query.replace("{ids}", &marks).replace("{public}", PUBLIC_MESSAGE_SQL);
    let mut statement = conn.prepare(&sql)?;
    let rows = statement.query_map(params_from_iter(ids.iter()), |r| r.get::<_, String>(0))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Three set lookups for a page, regardless of how many message/reaction/attachment ops it holds.
fn visible_page(conn: &Connection, account: &str, page: &[Op]) -> anyhow::Result<Digest> {
    let mut messages = HashSet::new();
    let mut channels = HashSet::new();
    let mut attachments = HashSet::new();
    for o in page {
        if o.account_id.as_deref() == Some(account) || !o.scope.starts_with("space:") {
            continue;
        }
        if matches!(o.kind.as_str(), "message.send" | "message.forward") {
            if is_public(o.payload.get("visibility")) {
                channels.insert(o.payload.get("channel_id").and_then(Value::as_str).unwrap_or_default().to_string());
            }
        } else if o.kind.starts_with("channel.") {
            if let Some(id) = o.payload.get("parent_message_id").and_then(Value::as_str) {
                messages.insert(id.to_string());
            } else if let Some(id) = o.entity() {
                channels.insert(id.to_string());
            }
        } else if o.kind.starts_with("message.") {
            if let Some(id) = o.payload.get("message_id").and_then(Value::as_str).or_else(|| o.entity()) {
                messages.insert(id.to_string());
            }
        } else if o.kind.starts_with("reaction.") || o.kind.starts_with("read.") {
            if let Some(id) = o.payload.get("message_id").and_then(Value::as_str) {
                messages.insert(id.to_string());
            }
        } else if o.kind.starts_with("attachment.")
            && let Some(id) = o.entity()
        {
            attachments.insert(id.to_string());
        }
    }
    let public_messages =
        matching_ids(conn, &messages, "SELECT m.id FROM message m WHERE m.id IN ({ids}) AND {public}")?;
    let hidden_channels = matching_ids(
        conn,
        &channels,
        "SELECT c.id FROM channel c WHERE c.id IN ({ids}) AND c.kind='thread' AND c.parent_message_id IS NOT NULL
         AND NOT EXISTS (SELECT 1 FROM message m WHERE m.id=c.parent_message_id AND {public})",
    )?;
    let public_attachments = matching_ids(
        conn,
        &attachments,
        "SELECT DISTINCT ia.attachment_id FROM item_attachment ia JOIN message m ON m.id=ia.owner_id
         WHERE ia.owner_type='message' AND ia.attachment_id IN ({ids}) AND {public}",
    )?;
    let mut digest = Digest::default();
    for o in page {
        if visible_with(
            account,
            o,
            &mut |id| Ok(public_messages.contains(id)),
            &mut |id| Ok(!hidden_channels.contains(id)),
            &mut |id| Ok(public_attachments.contains(id)),
        )? {
            digest.add(&o.id);
        }
    }
    Ok(digest)
}

/// The digest must contain exactly the op ids this account can receive in catch-up.
pub fn visible_digest(conn: &Connection, account: &str, scope: &str) -> anyhow::Result<Digest> {
    if !scope.starts_with("space:")
        || !conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM op WHERE scope=?1 AND status='applied' AND account_id<>?2)",
            params![scope, account],
            |r| r.get::<_, bool>(0),
        )?
    {
        return oplog::digest(conn, scope);
    }
    let mut digest = Digest::default();
    let mut cursor = 0;
    loop {
        let page = oplog::scope_after(conn, scope, cursor, 1000)?;
        let Some(last) = page.last().and_then(|o| o.seq) else { break };
        cursor = last;
        let short = page.len() < 1000;
        let part = visible_page(conn, account, &page)?;
        for (total, page_part) in digest.xor.iter_mut().zip(part.xor) {
            *total ^= page_part;
        }
        digest.count += part.count;
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
    // a thread started under this message before it arrived, and what was said in it
    let mut st =
        conn.prepare("SELECT id FROM channel WHERE kind = 'thread' AND parent_message_id = ?1 AND space_id = ?2")?;
    let space = o.scope.strip_prefix("space:").unwrap_or_default();
    let threads: Vec<String> = st.query_map(params![message_id, space], |r| r.get(0))?.collect::<Result<_, _>>()?;
    for thread in threads {
        earlier.extend(oplog::for_entity(conn, &thread)?);
        let mut st = conn.prepare_cached("SELECT id FROM message WHERE channel_id = ?1")?;
        let msgs: Vec<String> = st.query_map([&thread], |r| r.get(0))?.collect::<Result<_, _>>()?;
        for m in msgs {
            earlier.extend(oplog::for_entity(conn, &m)?);
        }
    }
    Ok(earlier.into_iter().filter(|op| op.scope == o.scope && op.seq.is_some_and(|seq| seq < before)).collect())
}
