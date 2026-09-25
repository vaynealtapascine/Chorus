//! Account-scoped FTS search for message history beyond a local replica.

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

use chorus_core::search;

use crate::api_data::{DataError, Principal};
use crate::visibility::visible_message_sql;

#[derive(Default, serde::Deserialize)]
pub struct MessageQuery {
    pub q: String,
    #[serde(rename = "in")]
    pub channel: Option<String>,
    #[serde(rename = "from")]
    pub author: Option<String>,
    pub before: Option<i64>,
    pub after: Option<i64>,
    pub has: Option<String>,
    /// Minutes east of UTC, for dates in `q` (default 0).
    pub tz: Option<i32>,
    pub limit: Option<usize>,
    pub cursor: Option<String>,
}

#[derive(serde::Serialize, serde::Deserialize)]
struct Cursor {
    q: String,
    channel: Option<String>,
    author: Option<String>,
    before: Option<i64>,
    after: Option<i64>,
    has: Option<String>,
    score: f64,
    occurred_at: i64,
    id: String,
}

fn decode_cursor(query: &MessageQuery) -> Result<Option<Cursor>, DataError> {
    let Some(raw) = &query.cursor else { return Ok(None) };
    let bytes = URL_SAFE_NO_PAD.decode(raw).map_err(|_| DataError::Bad("invalid cursor".into()))?;
    let cursor: Cursor = serde_json::from_slice(&bytes).map_err(|_| DataError::Bad("invalid cursor".into()))?;
    if cursor.q != query.q.trim()
        || cursor.channel != query.channel
        || cursor.author != query.author
        || cursor.before != query.before
        || cursor.after != query.after
        || cursor.has != query.has
        || !cursor.score.is_finite()
        || cursor.id.is_empty()
    {
        return Err(DataError::Bad("cursor does not match search".into()));
    }
    Ok(Some(cursor))
}

/// Ids of the rows of `table` (`member`, `channel`) a `from:`/`in:` value names: its id, or its
/// name as core folds it (`chorus_core::search::names`).
fn named(conn: &Connection, table: &str, filters: &[String]) -> Result<Vec<String>, DataError> {
    if filters.is_empty() {
        return Ok(Vec::new());
    }
    let mut st = conn.prepare_cached(&format!("SELECT id, COALESCE(name, '') FROM {table}"))?;
    let rows = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
    let mut ids = Vec::new();
    for row in rows {
        let (id, name) = row?;
        if filters.iter().any(|f| search::names(f, &id, &name)) {
            ids.push(id);
        }
    }
    Ok(ids)
}

/// `GET /search/messages`: `q` is a search box (`chorus_core::search`: words, `from:` `in:`
/// `has:` `before:` `after:` `is:pinned`), parsed by core so it finds what the apps' local
/// search finds; `tz` (minutes east of UTC) places dates. The older `in`/`from`/`has`/`before`/
/// `after` parameters still narrow it (`before`/`after` in epoch ms, exclusive).
pub fn messages(conn: &Connection, principal: &Principal, query: &MessageQuery) -> Result<Value, DataError> {
    if !principal.allows("read:messages") {
        return Err(DataError::Scope("read:messages"));
    }
    let q = query.q.trim();
    if q.len() > 200 {
        return Err(DataError::Bad("q must be at most 200 characters".into()));
    }
    let mut parsed = search::parse(q).map_err(|e| DataError::Bad(format!("search: {e}")))?;
    parsed.channels.extend(query.channel.as_deref().map(search::fold));
    parsed.from.extend(query.author.as_deref().map(search::fold));
    if let Some(has) = query.has.as_deref() {
        if !search::HAS.contains(&has) {
            return Err(DataError::Bad(format!("has must be one of {}", search::HAS.join(", "))));
        }
        parsed.has.push(has.to_string());
    }
    if parsed.is_empty() && query.before.is_none() && query.after.is_none() {
        return Err(DataError::Bad("search for some words or a filter".into()));
    }
    let ctx = search::Context::at(crate::now_ms(), query.tz.unwrap_or(0), &parsed);
    let before = [parsed.before.as_ref().and_then(|t| ctx.before(t)), query.before].into_iter().flatten().min();
    // `after:` is inclusive, the older `after` parameter exclusive
    let after =
        [parsed.after.as_ref().and_then(|t| ctx.after(t)), query.after.map(|a| a + 1)].into_iter().flatten().max();
    let unresolved = |t: &Option<search::TimeRef>| matches!(t, Some(search::TimeRef::Date { date }) if !ctx.dates.contains_key(date));
    if unresolved(&parsed.before) || unresolved(&parsed.after) {
        return Err(DataError::Bad("search: not a date".into()));
    }
    let authors = named(conn, "member", &parsed.from)?;
    let channels = named(conn, "channel", &parsed.channels)?;
    let cursor = decode_cursor(query)?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100);
    let visible = visible_message_sql("?2");
    let fts = parsed.fts();
    // words go through the FTS index; filters alone scan the readable messages, newest first
    let (source, score, matching) = if fts.is_some() {
        ("message_fts JOIN message m ON m.rowid=message_fts.rowid", "bm25(message_fts)", "message_fts MATCH ?1")
    } else {
        ("message m", "0.0", "?1 IS NULL")
    };
    let has_sql = |h: &str| match h {
        "image" => {
            "EXISTS (SELECT 1 FROM item_attachment ia JOIN attachment a ON a.id=ia.attachment_id
                     WHERE ia.owner_type='message' AND ia.owner_id=m.id AND a.mime LIKE 'image/%')"
        }
        "file" => {
            "EXISTS (SELECT 1 FROM item_attachment ia JOIN attachment a ON a.id=ia.attachment_id
                     WHERE ia.owner_type='message' AND ia.owner_id=m.id AND COALESCE(a.mime, '') NOT LIKE 'image/%')"
        }
        "attachment" => "EXISTS (SELECT 1 FROM item_attachment ia WHERE ia.owner_type='message' AND ia.owner_id=m.id)",
        _ => {
            "EXISTS (SELECT 1 FROM json_each(m.entities) e
                     WHERE json_extract(e.value, '$.type') IN ('url', 'text_link'))"
        }
    };
    let has: String = parsed.has.iter().map(|h| format!(" AND {}", has_sql(h))).collect();
    let sql = format!(
        "WITH hits AS (SELECT m.id, m.channel_id, c.space_id, m.account_id, m.occurred_at, m.text, m.cw, m.visibility,
                COALESCE((SELECT json_group_array(member_id) FROM (SELECT member_id FROM message_author WHERE message_id=m.id
                    UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id)), '[]') AS authors,
                {score} AS score
         FROM {source}
         JOIN channel c ON c.id=m.channel_id
         WHERE {matching} AND m.deleted_at IS NULL AND c.deleted_at IS NULL
           AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id=?2 AND sa.scope='space:'||c.space_id)
           AND (m.account_id=?2 OR ({visible} AND ?8 = 0))
           AND (?3 IS NULL OR c.id IN (SELECT value FROM json_each(?3)))
           AND (?4 IS NULL OR EXISTS (SELECT 1 FROM
                     (SELECT member_id FROM message_author WHERE message_id=m.id
                      UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id) ma
                     WHERE ma.member_id IN (SELECT value FROM json_each(?4))))
           AND (?5 IS NULL OR m.occurred_at<?5)
           AND (?6 IS NULL OR m.occurred_at>=?6)
           AND (?7 = 0 OR m.pinned_at IS NOT NULL){has})
         SELECT * FROM hits WHERE (?9 IS NULL OR score>?9 OR
           (score=?9 AND (occurred_at<?10 OR (occurred_at=?10 AND id<?11))))
         ORDER BY score,occurred_at DESC,id DESC LIMIT ?12"
    );
    let list = |ids: &[String], asked: bool| asked.then(|| serde_json::to_string(ids).unwrap_or_default());
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(
        params![
            fts,
            principal.account_id,
            list(&channels, !parsed.channels.is_empty()),
            list(&authors, !parsed.from.is_empty()),
            before,
            after,
            parsed.pinned,
            !principal.is_device(),
            cursor.as_ref().map(|c| c.score),
            cursor.as_ref().map(|c| c.occurred_at),
            cursor.as_ref().map(|c| c.id.as_str()),
            (limit + 1) as i64,
        ],
        |r| {
            let authors: String = r.get(8)?;
            let visibility: Option<String> = r.get(7)?;
            let item = json!({
                "id": r.get::<_, String>(0)?, "channel_id": r.get::<_, String>(1)?,
                "space_id": r.get::<_, String>(2)?, "account_id": r.get::<_, String>(3)?,
                "occurred_at": r.get::<_, i64>(4)?, "text": r.get::<_, String>(5)?,
                "cw": r.get::<_, Option<String>>(6)?,
                "visibility": visibility.and_then(|v| serde_json::from_str::<Value>(&v).ok()),
                "authors": serde_json::from_str::<Value>(&authors).unwrap_or(json!([])),
            });
            Ok((item, r.get::<_, f64>(9)?))
        },
    );
    let rows = match rows {
        Ok(rows) => rows,
        Err(rusqlite::Error::SqliteFailure(_, _)) => return Err(DataError::Bad("invalid search query".into())),
        Err(error) => return Err(error.into()),
    };
    let mut rows = rows.collect::<Result<Vec<_>, _>>()?;
    let next_cursor = if rows.len() > limit {
        rows.truncate(limit);
        let (last, score) = rows.last().expect("a paged search has a last row");
        let anchor = Cursor {
            q: q.to_string(),
            channel: query.channel.clone(),
            author: query.author.clone(),
            before: query.before,
            after: query.after,
            has: query.has.clone(),
            score: *score,
            occurred_at: last["occurred_at"].as_i64().unwrap_or_default(),
            id: last["id"].as_str().unwrap_or_default().to_string(),
        };
        Some(URL_SAFE_NO_PAD.encode(serde_json::to_vec(&anchor).map_err(|e| DataError::Bad(e.to_string()))?))
    } else {
        None
    };
    Ok(json!({"items": rows.into_iter().map(|(item, _)| item).collect::<Vec<_>>(), "next_cursor": next_cursor}))
}

/// One history message for a jump target that is no longer in the device's local replica.
pub fn message_by_id(conn: &Connection, principal: &Principal, id: &str) -> Result<Option<Value>, DataError> {
    if !principal.allows("read:messages") {
        return Err(DataError::Scope("read:messages"));
    }
    use rusqlite::OptionalExtension;
    let visible = visible_message_sql("?2");
    let sql = format!(
        "SELECT m.id, m.channel_id, c.space_id, m.account_id, m.occurred_at, m.text, m.cw, m.visibility,
                COALESCE((SELECT json_group_array(member_id) FROM (SELECT member_id FROM message_author WHERE message_id=m.id
                    UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id)), '[]')
         FROM message m JOIN channel c ON c.id=m.channel_id
         WHERE m.id=?1 AND m.deleted_at IS NULL AND c.deleted_at IS NULL
           AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id=?2 AND sa.scope='space:'||c.space_id)
           AND (m.account_id=?2 OR ({visible} AND ?3 = 0))"
    );
    let row = conn
        .query_row(&sql, params![id, principal.account_id, !principal.is_device()], |r| {
            let authors: String = r.get(8)?;
            let visibility: Option<String> = r.get(7)?;
            Ok(json!({
                "id": r.get::<_, String>(0)?, "channel_id": r.get::<_, String>(1)?,
                "space_id": r.get::<_, String>(2)?, "account_id": r.get::<_, String>(3)?,
                "occurred_at": r.get::<_, i64>(4)?, "text": r.get::<_, String>(5)?,
                "cw": r.get::<_, Option<String>>(6)?,
                "visibility": visibility.and_then(|v| serde_json::from_str::<Value>(&v).ok()),
                "authors": serde_json::from_str::<Value>(&authors).unwrap_or(json!([])),
            }))
        })
        .optional()?;
    Ok(row)
}
