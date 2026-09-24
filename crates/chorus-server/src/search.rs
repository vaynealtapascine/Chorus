//! Account-scoped FTS search for message history beyond a local replica.

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

use crate::api_data::{DataError, Principal};
use crate::visibility::PUBLIC_MESSAGE_SQL;

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

pub fn messages(conn: &Connection, principal: &Principal, query: &MessageQuery) -> Result<Value, DataError> {
    if !principal.allows("read:messages") {
        return Err(DataError::Scope("read:messages"));
    }
    let q = query.q.trim();
    if q.is_empty() || q.len() > 200 {
        return Err(DataError::Bad("q must be 1–200 characters".into()));
    }
    if query.has.as_deref().is_some_and(|h| !matches!(h, "attachment" | "image" | "file")) {
        return Err(DataError::Bad("has must be attachment, image, or file".into()));
    }
    let cursor = decode_cursor(query)?;
    let limit = query.limit.unwrap_or(100).clamp(1, 100);
    let sql = format!(
        "WITH hits AS (SELECT m.id, m.channel_id, c.space_id, m.account_id, m.occurred_at, m.text, m.cw, m.visibility,
                COALESCE((SELECT json_group_array(member_id) FROM (SELECT member_id FROM message_author WHERE message_id=m.id
                    UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id)), '[]') AS authors,
                bm25(message_fts) AS score
         FROM message_fts JOIN message m ON m.rowid=message_fts.rowid
         JOIN channel c ON c.id=m.channel_id
         WHERE message_fts MATCH ?1 AND m.deleted_at IS NULL AND c.deleted_at IS NULL
           AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id=?2 AND sa.scope='space:'||c.space_id)
           AND (m.account_id=?2 OR ({PUBLIC_MESSAGE_SQL} AND ?8 = 0))
           AND (?3 IS NULL OR c.id=?3 OR c.name=?3)
           AND (?4 IS NULL OR EXISTS (SELECT 1 FROM
                     (SELECT member_id FROM message_author WHERE message_id=m.id
                      UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id) ma
                     LEFT JOIN member author ON author.id=ma.member_id
                     WHERE ma.member_id=?4 OR author.name=?4 COLLATE NOCASE))
           AND (?5 IS NULL OR m.occurred_at<?5)
           AND (?6 IS NULL OR m.occurred_at>?6)
           AND (?7 IS NULL OR EXISTS (SELECT 1 FROM item_attachment ia JOIN attachment a ON a.id=ia.attachment_id
                     WHERE ia.owner_type='message' AND ia.owner_id=m.id AND
                       (?7='attachment' OR (?7='image' AND a.mime LIKE 'image/%') OR
                        (?7='file' AND a.mime NOT LIKE 'image/%')))))
         SELECT * FROM hits WHERE (?9 IS NULL OR score>?9 OR
           (score=?9 AND (occurred_at<?10 OR (occurred_at=?10 AND id<?11))))
         ORDER BY score,occurred_at DESC,id DESC LIMIT ?12"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(
        params![
            q,
            principal.account_id,
            query.channel,
            query.author,
            query.before,
            query.after,
            query.has,
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
    let sql = format!(
        "SELECT m.id, m.channel_id, c.space_id, m.account_id, m.occurred_at, m.text, m.cw, m.visibility,
                COALESCE((SELECT json_group_array(member_id) FROM (SELECT member_id FROM message_author WHERE message_id=m.id
                    UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id)), '[]')
         FROM message m JOIN channel c ON c.id=m.channel_id
         WHERE m.id=?1 AND m.deleted_at IS NULL AND c.deleted_at IS NULL
           AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id=?2 AND sa.scope='space:'||c.space_id)
           AND (m.account_id=?2 OR ({PUBLIC_MESSAGE_SQL} AND ?3 = 0))"
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
