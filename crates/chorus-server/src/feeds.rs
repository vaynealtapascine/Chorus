//! Feeds read through the server (SPEC §6.4, M7.4): a saved feed definition with a visibility,
//! evaluated for a reader over the posts that reader can read.
//!
//! A feed is shared the way a post is (`visibility` = `{mode: private|followers|buckets|server}`,
//! checked with [`crate::posts::readable_sql`] on the feed row), and its results are only ever
//! posts the *reader* may read, so sharing a feed shares a filter, never a post. Names in the
//! filter (`from:@kai`, `from:list:"close"`) resolve against the feed owner's members, groups and
//! lists, so a feed reads the way it was written. `fronting:` would tell others when the owner's
//! members fronted, which follow ceilings govern (NOTIFICATIONS §3), so such a feed only
//! evaluates for its owner.

use std::collections::{BTreeMap, HashMap, HashSet};

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chorus_core::feed::{self, Expr, FromRef, TimeRef};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::api_data::{DataError, Principal};
use crate::posts;

/// Posts a single items request may look at before it returns what it found (with a cursor).
const SCAN: usize = 2000;
const BATCH: i64 = 200;

fn row_json(r: &rusqlite::Row<'_>) -> rusqlite::Result<Value> {
    let parse = |s: Option<String>| s.and_then(|s| serde_json::from_str::<Value>(&s).ok()).unwrap_or(Value::Null);
    Ok(json!({
        "id": r.get::<_, String>(0)?,
        "account_id": r.get::<_, String>(1)?,
        "name": r.get::<_, Option<String>>(2)?,
        "description": r.get::<_, Option<String>>(3)?,
        "query": r.get::<_, Option<String>>(4)?,
        "visibility": parse(r.get(5)?),
        "owner": {"handle": r.get::<_, Option<String>>(6)?, "display_name": r.get::<_, Option<String>>(7)?},
    }))
}

const FEED_COLUMNS: &str = "p.id, p.account_id, p.name, p.description, p.query, p.visibility, a.handle, a.display_name";

/// `GET /feeds`: the caller's own feeds and the ones others share with it (`shared: true`).
/// API tokens (`read:posts`) list only their own account's.
pub fn list(conn: &Connection, principal: &Principal) -> Result<Value, DataError> {
    if !principal.allows("read:posts") {
        return Err(DataError::Scope("read:posts"));
    }
    let readable = posts::readable_sql("?1");
    let sql = format!(
        "SELECT {FEED_COLUMNS} FROM feed p JOIN account a ON a.id = p.account_id
         WHERE p.deleted_at IS NULL AND p.query_ast IS NOT NULL AND {readable} AND (?2 = 0 OR p.account_id = ?1)
         ORDER BY p.account_id <> ?1, a.handle, p.name, p.id"
    );
    let mut st = conn.prepare(&sql)?;
    let items = st
        .query_map(params![principal.account_id, !principal.is_device()], |r| {
            let mut v = row_json(r)?;
            v["shared"] = json!(v["account_id"].as_str() != Some(principal.account_id.as_str()));
            Ok(v)
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(json!({"items": items}))
}

#[derive(Default, Deserialize)]
pub struct ItemsQuery {
    pub limit: Option<usize>,
    pub cursor: Option<String>,
}

#[derive(serde::Serialize, Deserialize)]
struct Cursor {
    at: i64,
    id: String,
}

/// `GET /feeds/{id}/items`: the feed's matches, newest first, among the posts the caller can
/// read. `None` when the feed doesn't exist or isn't shared with the caller.
pub fn items(conn: &Connection, principal: &Principal, id: &str, q: &ItemsQuery) -> Result<Option<Value>, DataError> {
    if !principal.allows("read:posts") {
        return Err(DataError::Scope("read:posts"));
    }
    let viewer = principal.account_id.as_str();
    let readable = posts::readable_sql("?1");
    let found: Option<(String, String)> = conn
        .query_row(
            &format!(
                "SELECT p.account_id, p.query_ast FROM feed p
                 WHERE p.id = ?2 AND p.deleted_at IS NULL AND p.query_ast IS NOT NULL AND {readable}
                   AND (?3 = 0 OR p.account_id = ?1)"
            ),
            params![viewer, id, !principal.is_device()],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let Some((owner, ast)) = found else { return Ok(None) };
    let ast: Expr =
        serde_json::from_str(&ast).map_err(|_| DataError::Bad("this feed's filter can't be read".into()))?;
    if owner != viewer && uses_fronting(&ast) {
        return Err(DataError::Bad("this feed filters by who was fronting, which only its owner can see".into()));
    }
    let ctx = OwnerContext::load(conn, &owner, &ast, crate::now_ms())?;
    let limit = q.limit.unwrap_or(50).clamp(1, 100);
    let mut cursor = match &q.cursor {
        None => None,
        Some(raw) => Some(
            URL_SAFE_NO_PAD
                .decode(raw)
                .ok()
                .and_then(|b| serde_json::from_slice::<Cursor>(&b).ok())
                .ok_or_else(|| DataError::Bad("invalid cursor".into()))?,
        ),
    };
    let sql = format!(
        "SELECT {} FROM post p WHERE p.deleted_at IS NULL AND {readable} AND (?4 = 0 OR p.account_id = ?1)
           AND (?2 IS NULL OR p.occurred_at < ?2 OR (p.occurred_at = ?2 AND p.id < ?3))
         ORDER BY p.occurred_at DESC, p.id DESC LIMIT ?5",
        posts::COLUMNS
    );
    let mut st = conn.prepare(&sql)?;
    let mut matched = Vec::new();
    let mut scanned = 0usize;
    // whether the posts ran out (no next page), rather than the page or the scan budget filling
    let mut ran_out = false;
    loop {
        let batch = st
            .query_map(
                params![
                    viewer,
                    cursor.as_ref().map(|c| c.at),
                    cursor.as_ref().map(|c| c.id.as_str()),
                    !principal.is_device(),
                    BATCH
                ],
                posts::row,
            )?
            .collect::<Result<Vec<_>, _>>()?;
        let last_batch = (batch.len() as i64) < BATCH;
        for mut post in batch {
            scanned += 1;
            cursor = Some(Cursor {
                at: post["occurred_at"].as_i64().unwrap_or_default(),
                id: post["id"].as_str().unwrap_or_default().to_string(),
            });
            posts::hide_unreadable_links(conn, viewer, &mut post)?;
            let item = item_of(conn, &post, owner == viewer)?;
            if feed::eval(&ast, &item, &ctx) {
                matched.push(post);
                if matched.len() == limit {
                    break;
                }
            }
        }
        if matched.len() == limit || scanned >= SCAN {
            break;
        }
        if last_batch {
            ran_out = true;
            break;
        }
    }
    let next_cursor = match (&cursor, ran_out) {
        (Some(c), false) => {
            Some(URL_SAFE_NO_PAD.encode(serde_json::to_vec(c).map_err(|e| DataError::Bad(e.to_string()))?))
        }
        _ => None,
    };
    Ok(Some(json!({"items": matched, "next_cursor": next_cursor})))
}

fn uses_fronting(e: &Expr) -> bool {
    match e {
        Expr::And { args } | Expr::Or { args } => args.iter().any(uses_fronting),
        Expr::Not { arg } => uses_fronting(arg),
        Expr::Fronting { .. } => true,
        _ => false,
    }
}

/// A post as the feed evaluator sees it (the same fields the web app builds, feeds.ts).
fn item_of(conn: &Connection, post: &Value, fronting_visible: bool) -> rusqlite::Result<feed::Item> {
    let strings = |v: &Value| -> Vec<String> {
        v.as_array().into_iter().flatten().filter_map(Value::as_str).map(str::to_string).collect()
    };
    let authors = strings(&post["authors"]);
    let text = post["text"].as_str().unwrap_or("");
    let mut has = HashSet::new();
    for a in post["attachments"].as_array().into_iter().flatten() {
        has.insert("attachment");
        let mime = a["mime"].as_str().unwrap_or("");
        for kind in ["image", "video", "audio"] {
            if mime.starts_with(&format!("{kind}/")) {
                has.insert(kind);
            }
        }
    }
    let link_entity = post["entities"].as_array().into_iter().flatten().any(|e| e["type"].as_str() == Some("link"));
    if link_entity || text.to_ascii_lowercase().contains("http://") || text.to_ascii_lowercase().contains("https://") {
        has.insert("link");
    }
    let occurred_at = post["occurred_at"].as_i64().unwrap_or_default();
    let author_fronting = fronting_visible && !authors.is_empty() && {
        let mut st = conn.prepare_cached(
            "SELECT EXISTS (SELECT 1 FROM front_interval, json_each(?1) a
               WHERE subject_type = 'member' AND subject_id = a.value AND level = 'front'
                 AND start_at <= ?2 AND (end_at IS NULL OR end_at > ?2))",
        )?;
        st.query_row(params![Value::from(authors.clone()).to_string(), occurred_at], |r| r.get(0))?
    };
    Ok(feed::Item {
        kind: post["kind"].as_str().unwrap_or("").to_string(),
        author_ids: authors,
        tags: strings(&post["tags"]),
        mood: post["mood"].as_str().map(str::to_string),
        has: has.into_iter().map(str::to_string).collect(),
        is_reply: !post["reply_to"].is_null(),
        channel: None,
        occurred_at,
        author_fronting,
        text: format!("{} {text}", post["title"].as_str().unwrap_or("")),
    })
}

/// Names and dates resolved the way the feed's owner wrote them (web: `feedContext`).
struct OwnerContext {
    now: i64,
    names: HashMap<String, Vec<String>>,
    lists: HashMap<String, Vec<String>>,
    dates: BTreeMap<String, i64>,
}

impl OwnerContext {
    fn load(conn: &Connection, owner: &str, ast: &Expr, now: i64) -> rusqlite::Result<OwnerContext> {
        let mut names: HashMap<String, Vec<String>> = HashMap::new();
        let add = |map: &mut HashMap<String, Vec<String>>, name: String, id: String| {
            let ids = map.entry(name.to_lowercase()).or_default();
            if !ids.contains(&id) {
                ids.push(id);
            }
        };
        let mut st = conn
            .prepare_cached("SELECT id, name, display_name FROM member WHERE account_id = ?1 AND deleted_at IS NULL")?;
        for r in st.query_map([owner], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?, r.get::<_, Option<String>>(2)?))
        })? {
            let (id, name, display) = r?;
            for n in [Some(id.clone()), name, display].into_iter().flatten() {
                add(&mut names, n, id.clone());
            }
        }
        let mut st = conn.prepare_cached(
            "SELECT g.name, gm.member_id FROM member_group g JOIN group_membership gm ON gm.group_id = g.id
             WHERE g.account_id = ?1 AND g.deleted_at IS NULL AND gm.is_present AND g.name IS NOT NULL",
        )?;
        for r in st.query_map([owner], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))? {
            let (name, id) = r?;
            add(&mut names, name, id);
        }
        let mut lists = HashMap::new();
        let mut st = conn.prepare_cached(
            "SELECT l.name, i.member_id FROM member_list l JOIN member_list_item i ON i.list_id = l.id
             WHERE l.account_id = ?1 AND l.deleted_at IS NULL AND i.is_present AND l.name IS NOT NULL",
        )?;
        for r in st.query_map([owner], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))? {
            let (name, id) = r?;
            add(&mut lists, name, id);
        }
        // local dates in the owner's latest UTC offset (as front_daily; no tz database on the server)
        let offset_min: i64 = conn
            .query_row(
                "SELECT tz_offset_min FROM switch WHERE account_id = ?1 ORDER BY occurred_at DESC LIMIT 1",
                [owner],
                |r| r.get(0),
            )
            .optional()?
            .unwrap_or(0);
        let mut dates = BTreeMap::new();
        dates_in(ast, &mut |d| {
            if let Some(days) = days_from_civil(d) {
                dates.insert(d.to_string(), days * 86_400_000 - offset_min * 60_000);
            }
        });
        Ok(OwnerContext { now, names, lists, dates })
    }
}

impl feed::Context for OwnerContext {
    fn now(&self) -> i64 {
        self.now
    }
    fn members(&self, from: &FromRef) -> Vec<String> {
        match from {
            FromRef::Name { name } => self.names.get(&name.to_lowercase()),
            FromRef::List { name } => self.lists.get(&name.to_lowercase()),
        }
        .cloned()
        .unwrap_or_default()
    }
    fn date_start(&self, date: &str) -> Option<i64> {
        self.dates.get(date).copied()
    }
}

fn dates_in(e: &Expr, f: &mut dyn FnMut(&str)) {
    match e {
        Expr::And { args } | Expr::Or { args } => args.iter().for_each(|a| dates_in(a, f)),
        Expr::Not { arg } => dates_in(arg, f),
        Expr::Since { at: TimeRef::Date { date } } | Expr::Until { at: TimeRef::Date { date } } => f(date),
        _ => {}
    }
}

/// Days since 1970-01-01 for `YYYY-MM-DD` (proleptic Gregorian).
fn days_from_civil(date: &str) -> Option<i64> {
    let mut parts = date.split('-');
    let (y, m, d): (i64, i64, i64) =
        (parts.next()?.parse().ok()?, parts.next()?.parse().ok()?, parts.next()?.parse().ok()?);
    if parts.next().is_some() || !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    Some(era * 146_097 + doe - 719_468)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_days() {
        assert_eq!(days_from_civil("1970-01-01"), Some(0));
        assert_eq!(days_from_civil("2000-03-01"), Some(11_017));
        assert_eq!(days_from_civil("2026-09-24"), Some(20_720));
        assert_eq!(days_from_civil("2026-13-01"), None);
        assert_eq!(days_from_civil("soon"), None);
    }

    #[test]
    fn fronting_is_found_anywhere_in_a_filter() {
        assert!(uses_fronting(&feed::parse("kind:entry (tag:a or -fronting:true)").unwrap()));
        assert!(!uses_fronting(&feed::parse("kind:entry tag:a").unwrap()));
    }
}
