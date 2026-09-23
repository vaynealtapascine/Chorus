//! Read-only post views. Cross-account reads apply the post's stored audience at query time.

use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::Deserialize;
use serde_json::{Value, json};

/// A malformed visibility value fails closed. An ended follow removes follower and bucket access.
const READABLE: &str = "(p.account_id=?1 OR CASE WHEN json_valid(p.visibility) THEN
    (json_extract(p.visibility,'$.mode')='server' OR
     (json_extract(p.visibility,'$.mode') IN ('followers','buckets') AND
      EXISTS (SELECT 1 FROM follow f WHERE f.follower_account_id=?1
              AND f.target_account_id=p.account_id AND f.status='active') AND
      (json_extract(p.visibility,'$.mode')='followers' OR
       EXISTS (SELECT 1 FROM json_each(p.visibility,'$.bucket_ids') ids
               JOIN bucket_assignment ba ON ba.bucket_id=ids.value AND ba.follower_account_id=?1 AND ba.is_present
               JOIN bucket b ON b.id=ba.bucket_id AND b.account_id=p.account_id AND b.deleted_at IS NULL))))
    ELSE 0 END)";

const COLUMNS: &str = "p.id,p.account_id,p.kind,p.title,p.text,p.entities,p.mood,p.tags,p.cw,
    p.reply_to_id,p.quote,p.repost_of_id,p.visibility,p.occurred_at,p.edited_at,
    COALESCE((SELECT json_group_array(member_id) FROM
       (SELECT member_id FROM post_author WHERE post_id=p.id ORDER BY position)), '[]'),
    COALESCE((SELECT json_group_array(json_object('id',id,'name',name,'display_name',display_name,'color',color))
       FROM (SELECT m.id,m.name,m.display_name,m.color FROM post_author pa JOIN member m ON m.id=pa.member_id
             WHERE pa.post_id=p.id ORDER BY pa.position)), '[]')";

#[derive(Default, Deserialize)]
pub struct PostQuery {
    pub author: Option<String>,
    pub kind: Option<String>,
    pub before: Option<i64>,
    pub limit: Option<usize>,
    pub account: Option<String>,
}

fn parsed(s: Option<String>) -> Value {
    s.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null)
}

fn row(r: &Row<'_>) -> rusqlite::Result<Value> {
    Ok(json!({
        "id": r.get::<_, String>(0)?,
        "account_id": r.get::<_, String>(1)?,
        "kind": r.get::<_, Option<String>>(2)?,
        "title": r.get::<_, Option<String>>(3)?,
        "text": r.get::<_, String>(4)?,
        "entities": parsed(r.get(5)?),
        "mood": r.get::<_, Option<String>>(6)?,
        "tags": parsed(r.get(7)?),
        "cw": r.get::<_, Option<String>>(8)?,
        "reply_to": r.get::<_, Option<String>>(9)?,
        "quote": parsed(r.get(10)?),
        "repost_of": r.get::<_, Option<String>>(11)?,
        "visibility": parsed(r.get(12)?),
        "occurred_at": r.get::<_, i64>(13)?,
        "edited_at": r.get::<_, Option<i64>>(14)?,
        "authors": parsed(r.get(15)?),
        "author_cards": parsed(r.get(16)?),
    }))
}

fn readable_id(conn: &Connection, viewer: &str, id: &str) -> rusqlite::Result<bool> {
    let sql = format!("SELECT EXISTS(SELECT 1 FROM post p WHERE p.id=?2 AND p.deleted_at IS NULL AND {READABLE})");
    conn.query_row(&sql, params![viewer, id], |r| r.get(0))
}

fn hide_unreadable_links(conn: &Connection, viewer: &str, item: &mut Value) -> rusqlite::Result<()> {
    for key in ["reply_to", "repost_of"] {
        if let Some(id) = item[key].as_str()
            && !readable_id(conn, viewer, id)?
        {
            item[key] = Value::Null;
        }
    }
    Ok(())
}

pub fn list(conn: &Connection, viewer: &str, q: &PostQuery) -> anyhow::Result<Value> {
    let limit = q.limit.unwrap_or(50).clamp(1, 100) as i64;
    let sql = format!(
        "SELECT {COLUMNS} FROM post p WHERE p.deleted_at IS NULL AND {READABLE}
         AND (?2 IS NULL OR p.account_id=?2)
         AND (?3 IS NULL OR p.kind=?3)
         AND (?4 IS NULL OR p.occurred_at<?4)
         AND (?5 IS NULL OR EXISTS (SELECT 1 FROM post_author pa WHERE pa.post_id=p.id AND pa.member_id=?5))
         ORDER BY p.occurred_at DESC,p.id DESC LIMIT ?6"
    );
    let mut st = conn.prepare(&sql)?;
    let mut items = st
        .query_map(params![viewer, q.account, q.kind, q.before, q.author, limit], row)?
        .collect::<Result<Vec<_>, _>>()?;
    for item in &mut items {
        hide_unreadable_links(conn, viewer, item)?;
    }
    Ok(json!({"items": items}))
}

pub fn one(conn: &Connection, viewer: &str, id: &str) -> anyhow::Result<Option<Value>> {
    let sql = format!("SELECT {COLUMNS} FROM post p WHERE p.id=?2 AND p.deleted_at IS NULL AND {READABLE}");
    let mut item = conn.query_row(&sql, params![viewer, id], row).optional()?;
    if let Some(item) = &mut item {
        hide_unreadable_links(conn, viewer, item)?;
    }
    Ok(item)
}

pub fn replies(conn: &Connection, viewer: &str, parent: &str, depth: usize) -> anyhow::Result<Vec<Value>> {
    if depth == 0 {
        return Ok(Vec::new());
    }
    let sql = format!(
        "SELECT {COLUMNS} FROM post p WHERE p.reply_to_id=?2 AND p.deleted_at IS NULL AND {READABLE}
         ORDER BY p.occurred_at,p.id LIMIT 50"
    );
    let mut st = conn.prepare(&sql)?;
    let mut rows = st.query_map(params![viewer, parent], row)?.collect::<Result<Vec<_>, _>>()?;
    for item in &mut rows {
        hide_unreadable_links(conn, viewer, item)?;
        item["replies"] = json!(replies(conn, viewer, item["id"].as_str().unwrap_or(""), depth - 1)?);
    }
    Ok(rows)
}
