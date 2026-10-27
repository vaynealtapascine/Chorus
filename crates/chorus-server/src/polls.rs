//! Polls on the server (D-081, D-086, docs/POLLS.md). A vote is checked against its parent's
//! audience and poll; after each vote (or a change to the parent) the server appends a
//! counts-only tally op in the parent's scope. Nothing here ever puts a voter into a tally.

use std::collections::HashMap;

use chorus_core::op::Op;
use chorus_core::poll::{self, Poll};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::Value;

/// A poll's parent and where its totals are published.
struct Parent {
    poll: Poll,
    author: String,
    /// `Some(channel)` for a message (tally in the space), `None` for a post (tally in the author's account).
    channel: Option<String>,
    scope: String,
}

fn parent(conn: &Connection, id: &str) -> anyhow::Result<Option<Parent>> {
    let message: Option<(String, String, String, String)> = conn
        .prepare_cached(
            "SELECT m.poll, m.account_id, m.channel_id, c.space_id FROM message m JOIN channel c ON c.id = m.channel_id
             WHERE m.id = ?1 AND m.poll IS NOT NULL",
        )?
        .query_row([id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
        .optional()?;
    if let Some((def, author, channel, space)) = message {
        let Some(poll) = definition(&def) else { return Ok(None) };
        return Ok(Some(Parent { poll, author, channel: Some(channel), scope: format!("space:{space}") }));
    }
    let post: Option<(String, String)> = conn
        .prepare_cached("SELECT poll, account_id FROM post WHERE id = ?1 AND poll IS NOT NULL")?
        .query_row([id], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    Ok(post.and_then(|(def, author)| {
        let poll = definition(&def)?;
        let scope = format!("account:{author}");
        Some(Parent { poll, author, channel: None, scope })
    }))
}

fn definition(stored: &str) -> Option<Poll> {
    poll::definition(&serde_json::from_str::<Value>(stored).ok()?)
}

/// Whether `account` may read the poll's parent now (the same rule as reading it).
fn can_read(conn: &Connection, account: &str, id: &str, parent: &Parent) -> anyhow::Result<bool> {
    let sql = if parent.channel.is_some() {
        format!(
            "SELECT EXISTS(SELECT 1 FROM message m JOIN channel c ON c.id = m.channel_id
               WHERE m.id = ?1 AND m.deleted_at IS NULL AND c.deleted_at IS NULL
                 AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id = ?2 AND sa.scope = 'space:'||c.space_id)
                 AND (m.account_id = ?2 OR {}))",
            crate::visibility::visible_message_sql("?2")
        )
    } else {
        format!(
            "SELECT EXISTS(SELECT 1 FROM post p WHERE p.id = ?1 AND p.deleted_at IS NULL AND {})",
            crate::posts::readable_sql("?2")
        )
    };
    Ok(conn.prepare_cached(&sql)?.query_row(params![id, account], |r| r.get(0))?)
}

/// Why a live `poll.vote` from `author` is refused, if it is. `Err` with `retry` for a parent the
/// server hasn't seen yet: the device sends it again later.
pub fn vote_denied(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<Option<(String, bool)>> {
    if o.kind != "poll.vote" {
        return Ok(None);
    }
    let member = o.payload.get("member_id").and_then(Value::as_str).unwrap_or_default();
    let owned: bool = conn
        .prepare_cached("SELECT EXISTS(SELECT 1 FROM member WHERE id = ?1 AND account_id = ?2 AND deleted_at IS NULL)")?
        .query_row(params![member, author], |r| r.get(0))?;
    if !owned {
        return Ok(Some(("a vote must be cast as one of your own members".into(), false)));
    }
    let id = o.entity().unwrap_or_default();
    let Some(parent) = parent(conn, id)? else {
        return Ok(Some(("that poll isn't here yet".into(), true)));
    };
    if !can_read(conn, author, id, &parent)? {
        return Ok(Some(("you can't vote on a poll you can't read".into(), false)));
    }
    let choices = poll::choices(o.payload.get("choices").unwrap_or(&Value::Null)).unwrap_or_default();
    Ok(poll::check_vote(&parent.poll, &choices).err().map(|why| (why.to_string(), false)))
}

/// After a live op is accepted: recompute and publish the totals it may have changed.
pub fn after_op(conn: &Connection, o: &Op, now: i64) -> anyhow::Result<()> {
    let id = match o.kind.as_str() {
        "poll.vote" | "message.delete" | "message.restore" | "post.delete" | "post.restore" | "post.set_visibility" => {
            o.entity().unwrap_or_default()
        }
        _ => return Ok(()),
    };
    publish(conn, id, now)
}

/// Append a tally op for `id` when its totals differ from the latest one published.
pub fn publish(conn: &Connection, id: &str, now: i64) -> anyhow::Result<()> {
    let Some(parent) = parent(conn, id)? else { return Ok(()) };
    let votes: Vec<(String, String)> = conn
        .prepare_cached("SELECT account_id, choices FROM poll_vote WHERE poll_id = ?1")?
        .query_map([id], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    // only votes whose account can still read the parent count
    let mut readable = HashMap::new();
    let mut counted = Vec::with_capacity(votes.len());
    for (account, choices) in votes {
        let ok = match readable.get(&account) {
            Some(ok) => *ok,
            None => *readable.entry(account.clone()).or_insert(can_read(conn, &account, id, &parent)?),
        };
        if ok && let Some(c) = serde_json::from_str::<Value>(&choices).ok().as_ref().and_then(poll::choices) {
            counted.push(c);
        }
    }
    let tally = poll::tally(&parent.poll, counted.iter().map(Vec::as_slice));
    let payload = poll::tally_payload(&tally, parent.channel.as_deref());
    let latest: Option<(String, i64)> = conn
        .prepare_cached("SELECT counts, voters FROM poll_tally WHERE poll_id = ?1")?
        .query_row([id], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    if let Some((counts, voters)) = latest
        && serde_json::from_str::<Value>(&counts).ok().as_ref() == payload.get("counts")
        && Some(voters as u64) == payload["voters"].as_u64()
    {
        return Ok(());
    }
    let kind = if parent.channel.is_some() { "message.poll_tally" } else { "post.poll_tally" };
    let (ack, _) = crate::ingest::op_as(
        conn,
        &parent.author,
        crate::ingest::SERVER_DEVICE,
        kind,
        &parent.scope,
        Some(id),
        payload,
        now,
        None,
    )?;
    if let Some(e) = ack.error {
        // e.g. the author has left the space: the last published totals stay
        tracing::warn!(poll = id, code = %e.code, "poll totals not published: {}", e.message);
    }
    Ok(())
}
