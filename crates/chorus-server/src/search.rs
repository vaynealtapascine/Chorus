//! Account-scoped FTS search for message history beyond a local replica.

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
    let sql = format!(
        "SELECT m.id, m.channel_id, c.space_id, m.account_id, m.occurred_at, m.text, m.cw, m.visibility,
                COALESCE((SELECT json_group_array(member_id) FROM (SELECT member_id FROM message_author WHERE message_id=m.id
                    UNION SELECT member_id FROM message_segment_author WHERE message_id=m.id)), '[]')
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
                        (?7='file' AND a.mime NOT LIKE 'image/%'))))
         ORDER BY bm25(message_fts), m.occurred_at DESC LIMIT 100"
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
            !principal.is_device()
        ],
        |r| {
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
        },
    );
    let rows = match rows {
        Ok(rows) => rows,
        Err(rusqlite::Error::SqliteFailure(_, _)) => return Err(DataError::Bad("invalid search query".into())),
        Err(error) => return Err(error.into()),
    };
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
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
