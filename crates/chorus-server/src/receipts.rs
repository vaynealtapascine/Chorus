//! Read receipts across accounts (D-131, NOTIFICATIONS.md §5.9): a view computed on request and
//! relayed live over the socket, never ops and never stored.
//!
//! Read marks stay account-private ops (SYNC.md §4.2, `visibility.rs` refuses `read.*` to other
//! accounts). What crosses is one fact: "this account has read up to your message X", and only when
//!
//! - the channel is in a `shared` or `dm` space (never `internal`) that both accounts can still
//!   reach and whose channel both can view now (`perms::can`, `scope_access`);
//! - **both** accounts have `share_reads` on (account pref, `device_id = ''`, default off); and
//! - the reader's mark was made **after** both accounts' latest switch-on of it, so turning it on
//!   later never reveals earlier reading, and turning either off hides everything again (the next
//!   query or relay checks the prefs again).
//!
//! Only the reader's account row (`reader_member_id = ''`) is ever used: a member row would say who
//! was fronting. The mark itself is never returned: `chorus_core::ticks::upto` clamps it to the
//! asker's latest message that the reader could see and that isn't deleted, so it can't point at the
//! reader's own aside or at anything the asker may not name.

use chorus_core::ticks::upto;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::api_data::{DataError, Principal};

/// `share_reads` is on for `account`; returns the switch-on's clock (its HLC text sorts as time).
fn opted_in(conn: &Connection, account: &str) -> Option<String> {
    let (value, hlc): (String, String) = conn
        .prepare_cached("SELECT value, hlc FROM pref WHERE account_id=?1 AND device_id='' AND key='share_reads'")
        .ok()?
        .query_row([account], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()
        .ok()??;
    (serde_json::from_str::<Value>(&value).ok()? == json!(true)).then_some(hlc)
}

/// The space of `channel` when receipts may flow in it for `account` right now.
pub(crate) fn eligible_space(conn: &Connection, account: &str, channel: &str) -> Option<String> {
    if !crate::perms::can(conn, account, channel, "view").unwrap_or(false) {
        return None;
    }
    let (space, kind): (String, Option<String>) = conn
        .query_row(
            "SELECT s.id, s.kind FROM channel c JOIN space s ON s.id=c.space_id
             WHERE c.id=?1 AND c.deleted_at IS NULL AND s.deleted_at IS NULL",
            [channel],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .ok()??;
    // an unknown kind fails closed: only shared and DM spaces
    if !matches!(kind.as_deref(), Some("shared" | "dm")) {
        return None;
    }
    crate::ingest::can_access(conn, account, &format!("space:{space}")).unwrap_or(false).then_some(space)
}

/// Where the reader's own account has read up to in the channel, with the clock of the op that
/// put it there. Marks from before migration 0004's bookkeeping carry no clock and never count.
fn reader_mark(conn: &Connection, reader: &str, channel: &str) -> Option<(i64, String, String)> {
    let (at, id, mark_at, mark_id, mark_hlc, set_hlc): (
        i64,
        String,
        Option<i64>,
        Option<String>,
        Option<String>,
        Option<String>,
    ) = conn
        .prepare_cached(
            "SELECT last_read_message_at, last_read_message_id, mark_at, mark_id, mark_hlc, set_hlc FROM read_state
             WHERE channel_id=?1 AND account_id=?2 AND reader_member_id='' AND state_ok=1",
        )
        .ok()?
        .query_row(params![channel, reader], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?)))
        .optional()
        .ok()??;
    let hlc = if mark_at == Some(at) && mark_id.as_deref() == Some(id.as_str()) { mark_hlc } else { set_hlc };
    Some((at, id, hlc?))
}

/// The latest message of `viewer` in the channel that `reader` has read up to, when receipts may
/// flow between them: `(message id, message time)`. `None` for any reason at all.
pub(crate) fn upto_for(conn: &Connection, viewer: &str, reader: &str, channel: &str) -> Option<(String, i64)> {
    if viewer == reader {
        return None;
    }
    let (viewer_on, reader_on) = (opted_in(conn, viewer)?, opted_in(conn, reader)?);
    eligible_space(conn, viewer, channel)?;
    eligible_space(conn, reader, channel)?;
    let (at, id, hlc) = reader_mark(conn, reader, channel)?;
    if hlc < viewer_on || hlc < reader_on {
        return None; // read before the switch was on for both: not shared
    }
    let sql = format!(
        "SELECT m.occurred_at, m.id FROM message m
         WHERE m.channel_id=?1 AND m.account_id=?2 AND m.deleted_at IS NULL AND (m.occurred_at, m.id) <= (?3, ?4)
           AND {}
         ORDER BY m.occurred_at DESC, m.id DESC LIMIT 1",
        crate::visibility::visible_message_sql("?5")
    );
    let own: Vec<(i64, String)> = conn
        .prepare_cached(&sql)
        .ok()?
        .query_map(params![channel, viewer, at, id, reader], |r| Ok((r.get(0)?, r.get(1)?)))
        .ok()?
        .filter_map(Result::ok)
        .collect();
    upto((at, &id), &own).map(|(at, id)| (id, at))
}

/// `GET /channels/{id}/reads`: one row for each other account that has read past at least one of
/// the caller's messages here. 404 if the caller can't view the channel; an empty list otherwise
/// (including when the caller hasn't opted in, so the answer never hints at the other side).
pub fn reads(conn: &Connection, p: &Principal, channel: &str) -> Result<Option<Value>, DataError> {
    if !p.allows("read:messages") {
        return Err(DataError::Scope("read:messages"));
    }
    let viewable = crate::perms::can(conn, &p.account_id, channel, "view")?
        && conn
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM channel c JOIN space s ON s.id=c.space_id
                 JOIN scope_access sa ON sa.account_id=?2 AND sa.scope='space:'||s.id
                 WHERE c.id=?1 AND c.deleted_at IS NULL AND s.deleted_at IS NULL)",
                params![channel, p.account_id],
                |r| r.get::<_, bool>(0),
            )
            .unwrap_or(false);
    if !viewable {
        return Ok(None);
    }
    let mut rows = Vec::new();
    for reader in readers_in(conn, &p.account_id, channel)? {
        if let Some((message_id, at)) = upto_for(conn, &p.account_id, &reader, channel) {
            rows.push(json!({"account_id": reader, "message_id": message_id, "at": at}));
        }
    }
    Ok(Some(json!({"reads": rows})))
}

/// Other accounts that can reach the channel's space, in a stable order.
fn readers_in(conn: &Connection, viewer: &str, channel: &str) -> rusqlite::Result<Vec<String>> {
    conn.prepare_cached(
        "SELECT sa.account_id FROM channel c JOIN scope_access sa ON sa.scope='space:'||c.space_id
         WHERE c.id=?1 AND sa.account_id<>?2 ORDER BY sa.account_id",
    )?
    .query_map(params![channel, viewer], |r| r.get(0))?
    .collect()
}
