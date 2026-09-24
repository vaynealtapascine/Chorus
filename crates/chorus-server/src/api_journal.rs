//! Journal reads (M7) for the REST API (API.md §3, M2.7): a member's profile bundle, member lists
//! and their timelines. Like `api_reads`, only the caller's own account's profiles and lists;
//! posts in a list timeline are the ones the caller can read (`posts::readable_sql`), and API
//! tokens (`read:posts`) see only their own account's posts.

use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::api_data::{DataError, Principal};
use crate::posts;

fn need(p: &Principal, scope: &'static str) -> Result<(), DataError> {
    if p.allows(scope) { Ok(()) } else { Err(DataError::Scope(scope)) }
}

fn parse(s: Option<String>) -> Value {
    s.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null)
}

/// `GET /profiles/{member_id}` (`read:members`): the member as `/members/{id}` has it (groups,
/// fields), its relationships, post counts, and — with `read:posts` too — its highlighted posts.
pub fn profile(conn: &Connection, p: &Principal, member_id: &str) -> Result<Option<Value>, DataError> {
    need(p, "read:members")?;
    let Some(member) = crate::api_reads::member(conn, p, member_id)? else { return Ok(None) };
    let mut st = conn.prepare(
        "SELECT r.id, r.to_kind, r.to_id, r.to_label, r.note, r.type_id, t.name, t.inverse_name, t.is_symmetric,
                coalesce(m.display_name, m.name)
         FROM relationship r
         LEFT JOIN relationship_type t ON t.id = r.type_id AND t.deleted_at IS NULL
         LEFT JOIN member m ON r.to_kind = 'member' AND m.id = r.to_id AND m.account_id = r.account_id
         WHERE r.account_id = ?1 AND r.from_member_id = ?2 AND r.deleted_at IS NULL
         ORDER BY t.name, r.created_at, r.id",
    )?;
    let relationships = st
        .query_map(params![p.account_id, member_id], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "to_kind": r.get::<_, Option<String>>(1)?,
                "to_id": r.get::<_, Option<String>>(2)?,
                "to_label": r.get::<_, Option<String>>(3)?,
                "to_name": r.get::<_, Option<String>>(9)?,
                "note": r.get::<_, Option<String>>(4)?,
                "type": {
                    "id": r.get::<_, Option<String>>(5)?,
                    "name": r.get::<_, Option<String>>(6)?,
                    "inverse_name": r.get::<_, Option<String>>(7)?,
                    "symmetric": r.get::<_, Option<bool>>(8)?.unwrap_or(false),
                },
            }))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    let stats = conn.query_row(
        "SELECT count(*), coalesce(sum(p.kind = 'entry'), 0), coalesce(sum(p.kind = 'note'), 0),
                min(p.occurred_at), max(p.occurred_at)
         FROM post p JOIN post_author pa ON pa.post_id = p.id
         WHERE pa.member_id = ?1 AND p.account_id = ?2 AND p.deleted_at IS NULL",
        params![member_id, p.account_id],
        |r| {
            Ok(json!({
                "posts": r.get::<_, i64>(0)?,
                "entries": r.get::<_, i64>(1)?,
                "notes": r.get::<_, i64>(2)?,
                "first_post_at": r.get::<_, Option<i64>>(3)?,
                "last_post_at": r.get::<_, Option<i64>>(4)?,
            }))
        },
    )?;
    let highlights = if p.allows("read:posts") {
        let readable = posts::readable_sql("?1");
        let sql = format!(
            "SELECT {} FROM highlight h JOIN post p ON p.id = h.post_id
             WHERE h.profile_member_id = ?2 AND h.is_present AND p.deleted_at IS NULL AND {readable}
               AND (?3 = 0 OR p.account_id = ?1)
             ORDER BY h.sort_key, p.occurred_at DESC LIMIT 50",
            posts::COLUMNS
        );
        let mut st = conn.prepare(&sql)?;
        let mut rows = st
            .query_map(params![p.account_id, member_id, !p.is_device()], posts::row)?
            .collect::<Result<Vec<_>, _>>()?;
        for item in &mut rows {
            posts::hide_unreadable_links(conn, &p.account_id, item)?;
        }
        Some(rows)
    } else {
        None
    };
    Ok(Some(json!({"member": member, "relationships": relationships, "stats": stats, "highlights": highlights})))
}

/// `GET /lists` (`read:posts`): the account's member lists with their members.
pub fn lists(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    need(p, "read:posts")?;
    let mut st = conn.prepare(
        "SELECT l.id, l.name, l.description, l.visibility,
                coalesce((SELECT json_group_array(member_id) FROM
                   (SELECT member_id FROM member_list_item WHERE list_id = l.id AND is_present ORDER BY member_id)), '[]')
         FROM member_list l WHERE l.account_id = ?1 AND l.deleted_at IS NULL ORDER BY l.name, l.id",
    )?;
    let items = st
        .query_map([&p.account_id], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "name": r.get::<_, Option<String>>(1)?,
                "description": r.get::<_, Option<String>>(2)?,
                "visibility": parse(r.get(3)?),
                "member_ids": parse(r.get(4)?),
            }))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(json!({"items": items}))
}

#[derive(Default, Deserialize)]
pub struct TimelineQuery {
    pub before: Option<i64>,
    pub limit: Option<usize>,
}

/// `GET /lists/{id}/timeline?before=&limit=` (`read:posts`): posts by the list's members that the
/// caller can read, newest first (`before` is an exclusive occurred-at, as `/posts`).
pub fn list_timeline(
    conn: &Connection,
    p: &Principal,
    id: &str,
    q: &TimelineQuery,
) -> Result<Option<Value>, DataError> {
    need(p, "read:posts")?;
    let exists: Option<String> = conn
        .query_row(
            "SELECT id FROM member_list WHERE id = ?1 AND account_id = ?2 AND deleted_at IS NULL",
            params![id, p.account_id],
            |r| r.get(0),
        )
        .optional()?;
    if exists.is_none() {
        return Ok(None);
    }
    let limit = q.limit.unwrap_or(50).clamp(1, 100) as i64;
    let readable = posts::readable_sql("?1");
    let sql = format!(
        "SELECT {} FROM post p WHERE p.deleted_at IS NULL AND {readable} AND (?3 = 0 OR p.account_id = ?1)
           AND EXISTS (SELECT 1 FROM post_author pa JOIN member_list_item i ON i.member_id = pa.member_id
                       WHERE pa.post_id = p.id AND i.list_id = ?2 AND i.is_present)
           AND (?4 IS NULL OR p.occurred_at < ?4)
         ORDER BY p.occurred_at DESC, p.id DESC LIMIT ?5",
        posts::COLUMNS
    );
    let mut st = conn.prepare(&sql)?;
    let mut items = st
        .query_map(params![p.account_id, id, !p.is_device(), q.before, limit], posts::row)?
        .collect::<Result<Vec<_>, _>>()?;
    for item in &mut items {
        posts::hide_unreadable_links(conn, &p.account_id, item)?;
    }
    Ok(Some(json!({"items": items})))
}
