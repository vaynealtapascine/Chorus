//! Channel and message reads, and message writes, over the API (API.md §3–§4, M2.7).
//!
//! Reads use the same rule as search (`search.rs`): the channel's space must be one the account
//! is in (`scope_access`), the channel one it may view (`perms.rs`), and a message must be the
//! account's own or visible (`visibility::visible_message_sql`: public, in a channel the account
//! may view; it also hides threads under private asides). An API
//! token only ever reaches its own account's messages (API.md §2.3); device sessions see
//! everything their account can read. Writes become ordinary `message.send` ops made on the
//! server for the account, attributed to the token's pseudo-device (like `POST /front/switch`).

use chorus_core::op::Op;
use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::api_data::{DataError, Principal};
use crate::ingest;
use crate::perms::can_sql;
use crate::visibility::visible_message_sql;

const COLS: &str = "m.id, m.channel_id, c.space_id, m.account_id, m.occurred_at, m.text, m.cw, m.visibility,
    COALESCE((SELECT json_group_array(member_id) FROM (SELECT member_id FROM message_author WHERE message_id=m.id
        UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id)), '[]'),
    m.entities, m.reply_to_id, m.thread_channel_id, m.edited_at, m.pinned_at";

/// `?2` = the account, `?3` = 1 for an API token (own messages only).
fn readable() -> String {
    format!(
        "m.deleted_at IS NULL AND c.deleted_at IS NULL
         AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id=?2 AND sa.scope='space:'||c.space_id)
         AND (m.account_id=?2 OR ({} AND ?3 = 0))",
        visible_message_sql("?2")
    )
}

fn row(r: &Row<'_>) -> rusqlite::Result<Value> {
    let json_of = |s: Option<String>| s.and_then(|v| serde_json::from_str::<Value>(&v).ok());
    Ok(json!({
        "id": r.get::<_, String>(0)?, "channel_id": r.get::<_, String>(1)?,
        "space_id": r.get::<_, String>(2)?, "account_id": r.get::<_, String>(3)?,
        "occurred_at": r.get::<_, i64>(4)?, "text": r.get::<_, String>(5)?,
        "cw": r.get::<_, Option<String>>(6)?,
        "visibility": json_of(r.get(7)?),
        "authors": json_of(r.get(8)?).unwrap_or(json!([])),
        "entities": json_of(r.get(9)?).unwrap_or(json!([])),
        "reply_to": r.get::<_, Option<String>>(10)?,
        "thread_channel_id": r.get::<_, Option<String>>(11)?,
        "edited_at": r.get::<_, Option<i64>>(12)?,
        "pinned_at": r.get::<_, Option<i64>>(13)?,
    }))
}

fn need_read(p: &Principal) -> Result<(), DataError> {
    if p.allows("read:messages") { Ok(()) } else { Err(DataError::Scope("read:messages")) }
}

fn in_space(conn: &Connection, p: &Principal, space: &str) -> Result<bool, DataError> {
    Ok(conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM scope_access WHERE account_id=?1 AND scope='space:'||?2)",
        params![p.account_id, space],
        |r| r.get(0),
    )?)
}

/// A channel the caller may read: in one of its spaces, not deleted, and not a thread under a
/// message it can't see. `None` = 404.
fn channel_space(conn: &Connection, p: &Principal, channel: &str) -> Result<Option<String>, DataError> {
    let sql = format!(
        "SELECT c.space_id FROM channel c WHERE c.id=?1 AND c.deleted_at IS NULL
           AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id=?2 AND sa.scope='space:'||c.space_id)
           AND {view}
           AND (c.kind IS NOT 'thread' OR c.parent_message_id IS NULL OR EXISTS (
                SELECT 1 FROM message m WHERE m.id=c.parent_message_id AND (m.account_id=?2 OR ({visible} AND ?3 = 0))))",
        view = can_sql("?2", "c.id", "view"),
        visible = visible_message_sql("?2"),
    );
    Ok(conn.query_row(&sql, params![channel, p.account_id, !p.is_device()], |r| r.get(0)).optional()?)
}

/// `GET /spaces/{id}/channels`: the space's channels, threads the caller can see included.
pub fn channels(conn: &Connection, p: &Principal, space: &str) -> Result<Option<Value>, DataError> {
    need_read(p)?;
    if !in_space(conn, p, space)? {
        return Ok(None);
    }
    let sql = format!(
        "SELECT c.id, c.kind, c.category, c.name, c.topic, c.parent_message_id, c.archived_at FROM channel c
         WHERE c.space_id=?1 AND c.deleted_at IS NULL AND {view}
           AND (c.kind IS NOT 'thread' OR c.parent_message_id IS NULL OR EXISTS (
                SELECT 1 FROM message m WHERE m.id=c.parent_message_id AND (m.account_id=?2 OR ({visible} AND ?3 = 0))))
         ORDER BY c.kind = 'thread', c.sort_key, c.name, c.id",
        view = can_sql("?2", "c.id", "view"),
        visible = visible_message_sql("?2"),
    );
    let mut st = conn.prepare(&sql)?;
    let items = st
        .query_map(params![space, p.account_id, !p.is_device()], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?, "kind": r.get::<_, Option<String>>(1)?,
                "category": r.get::<_, Option<String>>(2)?, "name": r.get::<_, Option<String>>(3)?,
                "topic": r.get::<_, Option<String>>(4)?, "parent_message_id": r.get::<_, Option<String>>(5)?,
                "archived": r.get::<_, Option<i64>>(6)?.is_some(),
            }))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(Some(json!({"items": items})))
}

#[derive(Deserialize, Default)]
pub struct Page {
    /// Messages strictly before this time (epoch ms): the newest page by default.
    pub before: Option<i64>,
    /// Messages strictly after this time, oldest first.
    pub after: Option<i64>,
    /// A message id: that message with up to `limit / 2` on each side.
    pub around: Option<String>,
    pub limit: Option<usize>,
}

fn page(
    conn: &Connection,
    p: &Principal,
    channel: &str,
    older_than: Option<(i64, &str)>,
    newer_than: Option<(i64, &str)>,
    limit: i64,
) -> Result<Vec<Value>, DataError> {
    let readable = readable();
    let (cmp, order) = if newer_than.is_some() {
        ("(m.occurred_at, m.id) > (?4, ?5)", "ASC")
    } else {
        ("(m.occurred_at, m.id) < (?4, ?5)", "DESC")
    };
    let (t, id) = newer_than.or(older_than).unwrap_or((i64::MAX, ""));
    let sql = format!(
        "SELECT {COLS} FROM message m JOIN channel c ON c.id=m.channel_id
         WHERE m.channel_id=?1 AND {readable} AND {cmp}
         ORDER BY m.occurred_at {order}, m.id {order} LIMIT ?6"
    );
    let mut st = conn.prepare(&sql)?;
    let mut items = st
        .query_map(params![channel, p.account_id, !p.is_device(), t, id, limit], row)?
        .collect::<Result<Vec<_>, _>>()?;
    if order == "DESC" {
        items.reverse();
    }
    Ok(items)
}

/// `GET /channels/{id}/messages`: oldest first, 1–100 per page (default 50). `None` = 404.
pub fn list(conn: &Connection, p: &Principal, channel: &str, q: &Page) -> Result<Option<Value>, DataError> {
    need_read(p)?;
    if channel_space(conn, p, channel)?.is_none() {
        return Ok(None);
    }
    let limit = q.limit.unwrap_or(50).clamp(1, 100) as i64;
    let items = if let Some(around) = &q.around {
        let Some(m) = one(conn, p, around)?.filter(|m| m["channel_id"] == channel) else { return Ok(None) };
        let at = m["occurred_at"].as_i64().unwrap_or(0);
        let mut v = page(conn, p, channel, Some((at, around)), None, limit / 2)?;
        v.push(m);
        v.extend(page(conn, p, channel, None, Some((at, around)), limit / 2)?);
        v
    } else if let Some(after) = q.after {
        page(conn, p, channel, None, Some((after, "\u{10FFFF}")), limit)?
    } else {
        page(conn, p, channel, q.before.map(|b| (b, "")), None, limit)?
    };
    let mut items = items;
    link_replies(conn, p, &mut items)?;
    Ok(Some(json!({"items": items})))
}

/// Replies elsewhere (SPEC §5.3): a reply names its original (`reply_to`) and, for the reference
/// card linking back, the original's channel (`reply_to_channel_id`) — only for a reader who can
/// read the original. Anyone else sees a plain message: not even that the original exists.
fn link_replies(conn: &Connection, p: &Principal, items: &mut [Value]) -> Result<(), DataError> {
    let sql = format!(
        "SELECT m.channel_id FROM message m JOIN channel c ON c.id=m.channel_id WHERE m.id=?1 AND {}",
        readable()
    );
    let mut st = conn.prepare_cached(&sql)?;
    for item in items {
        let Some(original) = item["reply_to"].as_str().map(str::to_string) else { continue };
        let channel: Option<String> =
            st.query_row(params![original, p.account_id, !p.is_device()], |r| r.get(0)).optional()?;
        match channel {
            Some(c) => item["reply_to_channel_id"] = json!(c),
            None => item["reply_to"] = Value::Null,
        }
    }
    Ok(())
}

/// One message the caller can read.
pub fn one(conn: &Connection, p: &Principal, id: &str) -> Result<Option<Value>, DataError> {
    need_read(p)?;
    let sql =
        format!("SELECT {COLS} FROM message m JOIN channel c ON c.id=m.channel_id WHERE m.id=?1 AND {}", readable());
    let Some(item) = conn.query_row(&sql, params![id, p.account_id, !p.is_device()], row).optional()? else {
        return Ok(None);
    };
    let mut items = [item];
    link_replies(conn, p, &mut items)?;
    let [item] = items;
    Ok(Some(item))
}

/// Versions of an item, oldest first, from its `<table>_revision` rows; an unedited item's only
/// version is its row (`current`).
fn versions(conn: &Connection, table: &str, id: &str, current: Value) -> Result<Value, DataError> {
    let title = if table == "post" { "title" } else { "NULL" };
    let cw = if table == "message" { "cw" } else { "NULL" };
    let sql = format!(
        "SELECT rev, {title}, text, entities, {cw}, edited_at FROM {table}_revision WHERE {table}_id = ?1 ORDER BY rev"
    );
    let mut st = conn.prepare(&sql)?;
    let mut items = st
        .query_map([id], |r| {
            let entities: String = r.get(3)?;
            Ok(json!({
                "rev": r.get::<_, i64>(0)?, "title": r.get::<_, Option<String>>(1)?, "text": r.get::<_, String>(2)?,
                "entities": serde_json::from_str::<Value>(&entities).unwrap_or(json!([])),
                "cw": r.get::<_, Option<String>>(4)?, "at": r.get::<_, i64>(5)?,
            }))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    if items.is_empty() {
        items.push(current);
    }
    if table == "message" {
        items.iter_mut().filter_map(Value::as_object_mut).for_each(|m| {
            m.remove("title");
        });
    }
    Ok(json!({"items": items}))
}

/// `GET /messages/{id}/revisions`: what a readable message said before each edit (SPEC §5.3),
/// oldest first; the last is what it says now. Same read rule as the message.
pub fn revisions(conn: &Connection, p: &Principal, id: &str) -> Result<Option<Value>, DataError> {
    let Some(m) = one(conn, p, id)? else { return Ok(None) };
    let current =
        json!({"rev": 0, "text": m["text"], "entities": m["entities"], "cw": m["cw"], "at": m["occurred_at"]});
    Ok(Some(versions(conn, "message", id, current)?))
}

/// `GET /posts/{id}/revisions` (the caller checked the post is readable): its versions.
pub fn post_revisions(conn: &Connection, post: &Value) -> Result<Value, DataError> {
    let id = post["id"].as_str().unwrap_or_default();
    let current = json!({"rev": 0, "title": post["title"], "text": post["text"], "entities": post["entities"], "at": post["occurred_at"]});
    versions(conn, "post", id, current)
}

/// `GET /messages/{id}/thread`: the thread started under a readable message.
pub fn thread(conn: &Connection, p: &Principal, id: &str, q: &Page) -> Result<Option<Value>, DataError> {
    let Some(m) = one(conn, p, id)? else { return Ok(None) };
    let Some(thread) = m["thread_channel_id"].as_str() else { return Ok(Some(json!({"items": []}))) };
    list(conn, p, thread, q)
}

#[derive(Deserialize, Debug, Default)]
#[serde(deny_unknown_fields)]
pub struct MessageIn {
    /// Member ids or names of the account's own members; default: who is fronting (primary
    /// first), or a person account's own member.
    pub authors: Option<Vec<String>>,
    pub text: String,
    /// `markup` (default: Chorus markup, parsed like the apps do), `plain`, or `entities`.
    pub format: Option<String>,
    pub entities: Option<Value>,
    pub reply_to: Option<String>,
}

fn member_id(conn: &Connection, account: &str, who: &str) -> Result<String, DataError> {
    let mut st = conn.prepare_cached(
        "SELECT id FROM member WHERE account_id=?1 AND deleted_at IS NULL
           AND (id=?2 OR lower(name)=lower(?2) OR lower(display_name)=lower(?2)) ORDER BY id = ?2 DESC, id",
    )?;
    let ids: Vec<String> = st.query_map(params![account, who.trim()], |r| r.get(0))?.collect::<Result<_, _>>()?;
    match ids.as_slice() {
        [one] => Ok(one.clone()),
        [first, ..] if first == who.trim() => Ok(first.clone()),
        [] => Err(DataError::Bad(format!("no member called {who:?}"))),
        _ => Err(DataError::Bad(format!("more than one member is called {who:?}; use its id"))),
    }
}

/// Who speaks by default: the primary fronter, else the first fronting member, else the one
/// member of a person account.
fn default_author(conn: &Connection, account: &str) -> Result<String, DataError> {
    let fronting: Option<String> = conn
        .query_row(
            "SELECT subject_id FROM front_interval WHERE account_id=?1 AND subject_type='member' AND end_at IS NULL
               AND level='front' ORDER BY is_primary DESC, position LIMIT 1",
            [account],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(m) = fronting {
        return Ok(m);
    }
    let own: Vec<String> = conn
        .prepare("SELECT id FROM member WHERE account_id=?1 AND deleted_at IS NULL AND archived_at IS NULL")?
        .query_map([account], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    match own.as_slice() {
        [one] => Ok(one.clone()),
        _ => Err(DataError::Bad("nobody is fronting; say who writes it with authors".into())),
    }
}

/// Names markup can mention: the account's own members, and the server's custom emoji.
fn markup_names(conn: &Connection, account: &str) -> Result<String, DataError> {
    let mut mentions = serde_json::Map::new();
    let mut st = conn.prepare("SELECT id, name FROM member WHERE account_id=?1 AND deleted_at IS NULL")?;
    for r in st.query_map([account], |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?)))? {
        let (id, name) = r?;
        if let Some(n) = name {
            mentions.insert(n.to_lowercase(), json!({"target_type": "member", "target_id": id}));
        }
    }
    let mut emoji = serde_json::Map::new();
    let mut st = conn.prepare("SELECT id, name FROM custom_emoji WHERE deleted_at IS NULL")?;
    for r in st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?)))? {
        let (id, name) = r?;
        if let Some(n) = name {
            emoji.insert(n, json!(id));
        }
    }
    Ok(json!({"mentions": mentions, "emoji": emoji}).to_string())
}

/// `POST /channels/{id}/messages` (API tokens need `write:messages`). Returns the accepted op.
pub fn send(
    conn: &Connection,
    p: &Principal,
    channel: &str,
    input: &MessageIn,
    now: i64,
) -> Result<Option<Op>, DataError> {
    if !p.allows("write:messages") {
        return Err(DataError::Scope("write:messages"));
    }
    let Some(space) = channel_space(conn, p, channel)? else { return Ok(None) };
    let (text, entities) = match input.format.as_deref().unwrap_or("markup") {
        "markup" => {
            let rich: Value = serde_json::from_str(
                &chorus_core::api::parse_markup(&input.text, &markup_names(conn, &p.account_id)?)
                    .map_err(DataError::Bad)?,
            )
            .map_err(anyhow::Error::from)?;
            (rich["text"].as_str().unwrap_or_default().to_string(), rich["entities"].clone())
        }
        "plain" => (input.text.clone(), json!([])),
        "entities" => (input.text.clone(), input.entities.clone().unwrap_or(json!([]))),
        other => return Err(DataError::Bad(format!("format {other:?} must be markup, plain or entities"))),
    };
    if text.trim().is_empty() {
        return Err(DataError::Bad("the message is empty".into()));
    }
    let authors = match &input.authors {
        Some(list) if !list.is_empty() => {
            list.iter().map(|a| member_id(conn, &p.account_id, a)).collect::<Result<Vec<_>, _>>()?
        }
        _ => vec![default_author(conn, &p.account_id)?],
    };
    let mut payload = json!({"channel_id": channel, "authors": authors, "text": text, "entities": entities});
    if let Some(r) = &input.reply_to {
        if one(conn, p, r)?.is_none() {
            return Err(DataError::Bad("reply_to isn't a message you can see".into()));
        }
        payload["reply_to"] = json!(r);
    }
    let device = match &p.token_id {
        Some(id) => format!("token:{id}"),
        None => ingest::SERVER_DEVICE.to_string(),
    };
    let id = chorus_core::id::new_id(now as u64, rand::random());
    let (ack, op) = ingest::op_as(
        conn,
        &p.account_id,
        &device,
        "message.send",
        &format!("space:{space}"),
        Some(&id),
        payload,
        now,
        None,
    )?;
    match op {
        Some(o) => Ok(Some(o)),
        None => Err(DataError::Bad(ack.error.map(|e| e.message).unwrap_or_else(|| "message rejected".into()))),
    }
}
