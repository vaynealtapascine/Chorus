//! Read-only post views. Cross-account reads apply the post's stored audience at query time.

use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::Deserialize;
use serde_json::{Value, json};

/// A malformed visibility value fails closed. An ended follow removes follower and bucket access.
pub(crate) fn readable_sql(viewer: &str) -> String {
    audience_sql(viewer, "p.account_id", "p.visibility")
}

/// Whether `viewer` is in an audience (`private` · `followers` · `buckets` · `server`) that
/// `owner` set on something of theirs: posts, and member profiles (`profiles::Viewer::allows`).
pub(crate) fn audience_sql(viewer: &str, owner: &str, audience: &str) -> String {
    format!(
        "({owner}={viewer} OR CASE WHEN json_valid({audience}) THEN
    (json_extract({audience},'$.mode')='server' OR
     (json_extract({audience},'$.mode') IN ('followers','buckets') AND
      EXISTS (SELECT 1 FROM follow f WHERE f.follower_account_id={viewer}
              AND f.target_account_id={owner} AND f.status='active') AND
      (json_extract({audience},'$.mode')='followers' OR
       EXISTS (SELECT 1 FROM json_each({audience},'$.bucket_ids') ids
               JOIN bucket_assignment ba ON ba.bucket_id=ids.value AND ba.follower_account_id={viewer} AND ba.is_present
               JOIN bucket b ON b.id=ba.bucket_id AND b.account_id={owner} AND b.deleted_at IS NULL))))
    ELSE 0 END)"
    )
}

/// The first column after [`COLUMNS`] (search appends its score there).
const SCORE: usize = 21;

pub(crate) const COLUMNS: &str = "p.id,p.account_id,p.kind,p.title,p.text,p.entities,p.mood,p.tags,p.cw,
    p.reply_to_id,p.quote,p.repost_of_id,p.visibility,p.occurred_at,p.edited_at,
    COALESCE((SELECT json_group_array(member_id) FROM
       (SELECT member_id FROM post_author WHERE post_id=p.id ORDER BY position)), '[]'),
    COALESCE((SELECT json_group_array(json_object('id',id,'name',name,'display_name',display_name,'color',color))
       FROM (SELECT m.id,m.name,m.display_name,m.color FROM post_author pa JOIN member m ON m.id=pa.member_id
             WHERE pa.post_id=p.id ORDER BY pa.position)), '[]'),
    COALESCE((SELECT json_group_array(json_object('id',id,'blob_hash',blob_hash,
        'thumb_blob_hash',thumb_blob_hash,'filename',filename,'mime',mime,'size',size,
        'width',width,'height',height,'alt_text',alt_text,
        'is_spoiler',json(CASE WHEN is_spoiler THEN 'true' ELSE 'false' END)))
       FROM (SELECT a.id,a.blob_hash,a.thumb_blob_hash,COALESCE(a.filename,'attachment') AS filename,
                    COALESCE(a.mime,'application/octet-stream') AS mime,COALESCE(a.size,0) AS size,
                    a.width,a.height,COALESCE(a.alt_text,'') AS alt_text,a.is_spoiler
             FROM item_attachment ia JOIN attachment a ON a.id=ia.attachment_id
             WHERE ia.owner_type='post' AND ia.owner_id=p.id AND a.blob_hash IS NOT NULL
             ORDER BY ia.position,a.id)), '[]'),
    COALESCE((SELECT json_group_array(json_object('emoji',emoji,'member_id',member_id,'member_name',member_name))
       FROM (SELECT r.emoji,r.member_id,COALESCE(m.display_name,m.name) AS member_name
             FROM reaction r JOIN member m ON m.id=r.member_id
             WHERE r.target_type='post' AND r.target_id=p.id AND r.is_present
             ORDER BY r.emoji,r.member_id)), '[]'),
    p.poll,
    (SELECT json_object('counts',json(t.counts),'voters',t.voters) FROM poll_tally t WHERE t.poll_id=p.id)";

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

pub(crate) fn row(r: &Row<'_>) -> rusqlite::Result<Value> {
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
        "attachments": parsed(r.get(17)?),
        "reactions": parsed(r.get(18)?),
        // the poll and its counts-only totals; voters are never listed (D-081, D-086)
        "poll": parsed(r.get(19)?),
        "poll_tally": parsed(r.get(20)?),
    }))
}

fn readable_id(conn: &Connection, viewer: &str, id: &str) -> rusqlite::Result<bool> {
    let readable = readable_sql("?1");
    let sql = format!("SELECT EXISTS(SELECT 1 FROM post p WHERE p.id=?2 AND p.deleted_at IS NULL AND {readable})");
    conn.query_row(&sql, params![viewer, id], |r| r.get(0))
}

/// A post as `viewer` gets it: links to posts they can't read are hidden, and each author card
/// says whether its profile opens for them (`profile`) and, if so, carries its picture. That is
/// the friend-profile rule (`profiles`), so the blob read allows exactly these pictures.
pub(crate) fn for_viewer(conn: &Connection, viewer: &str, item: &mut Value) -> rusqlite::Result<()> {
    for key in ["reply_to", "repost_of"] {
        if let Some(id) = item[key].as_str()
            && !readable_id(conn, viewer, id)?
        {
            item[key] = Value::Null;
        }
    }
    static AUTHOR: std::sync::LazyLock<String> = std::sync::LazyLock::new(|| {
        format!(
            "SELECT m.avatar_blob FROM member m WHERE m.id=?2 AND m.deleted_at IS NULL AND m.archived_at IS NULL AND {}",
            audience_sql("?1", "m.account_id", "m.visibility")
        )
    });
    if let Some(cards) = item["author_cards"].as_array_mut() {
        let mut st = conn.prepare_cached(&AUTHOR)?;
        for card in cards {
            let Some(id) = card["id"].as_str().map(str::to_string) else { continue };
            let open: Option<Option<String>> = st.query_row(params![viewer, id], |r| r.get(0)).optional()?;
            card["profile"] = json!(open.is_some());
            card["avatar_blob"] = json!(open.flatten());
        }
    }
    Ok(())
}

pub fn list(conn: &Connection, viewer: &str, q: &PostQuery) -> anyhow::Result<Value> {
    let limit = q.limit.unwrap_or(50).clamp(1, 100) as i64;
    let readable = readable_sql("?1");
    let sql = format!(
        "SELECT {COLUMNS} FROM post p WHERE p.deleted_at IS NULL AND {readable}
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
        for_viewer(conn, viewer, item)?;
    }
    Ok(json!({"items": items}))
}

pub fn one(conn: &Connection, viewer: &str, id: &str) -> anyhow::Result<Option<Value>> {
    let readable = readable_sql("?1");
    let sql = format!("SELECT {COLUMNS} FROM post p WHERE p.id=?2 AND p.deleted_at IS NULL AND {readable}");
    let mut item = conn.query_row(&sql, params![viewer, id], row).optional()?;
    if let Some(item) = &mut item {
        for_viewer(conn, viewer, item)?;
    }
    Ok(item)
}

pub fn replies(conn: &Connection, viewer: &str, parent: &str, depth: usize) -> anyhow::Result<Vec<Value>> {
    if depth == 0 {
        return Ok(Vec::new());
    }
    let readable = readable_sql("?1");
    let sql = format!(
        "SELECT {COLUMNS} FROM post p WHERE p.reply_to_id=?2 AND p.deleted_at IS NULL AND {readable}
         ORDER BY p.occurred_at,p.id LIMIT 50"
    );
    let mut st = conn.prepare(&sql)?;
    let mut rows = st.query_map(params![viewer, parent], row)?.collect::<Result<Vec<_>, _>>()?;
    for item in &mut rows {
        for_viewer(conn, viewer, item)?;
        let id = item["id"].as_str().unwrap_or("").to_string();
        item["reply_count"] = json!(reply_count(conn, viewer, &id)?);
        item["replies"] = json!(replies(conn, viewer, &id, depth - 1)?);
    }
    Ok(rows)
}

/// How many replies to `parent` this viewer may read (a thread view's "N replies").
pub fn reply_count(conn: &Connection, viewer: &str, parent: &str) -> rusqlite::Result<i64> {
    let readable = readable_sql("?1");
    let sql = format!("SELECT count(*) FROM post p WHERE p.reply_to_id=?2 AND p.deleted_at IS NULL AND {readable}");
    conn.query_row(&sql, params![viewer, parent], |r| r.get(0))
}

/// What a reply answers, oldest first: its parent, the parent's parent… while this viewer may read
/// them (an unreadable one ends the chain, as `reply_to` is hidden there), at most 20 up.
pub fn ancestors(conn: &Connection, viewer: &str, item: &Value) -> anyhow::Result<Vec<Value>> {
    let mut chain = Vec::new();
    // a looping chain never lists the post above itself
    let mut seen: std::collections::HashSet<String> = item["id"].as_str().map(str::to_string).into_iter().collect();
    let mut next = item["reply_to"].as_str().map(str::to_string);
    while let Some(id) = next {
        if chain.len() >= 20 || !seen.insert(id.clone()) {
            break;
        }
        let Some(parent) = one(conn, viewer, &id)? else { break };
        next = parent["reply_to"].as_str().map(str::to_string);
        chain.push(parent);
    }
    chain.reverse();
    Ok(chain)
}

/// A quoting post carries what it quotes (`quoted`) when this viewer may read that post; otherwise
/// only the quoted id remains and clients say it's unavailable. One level: the quoted post's own
/// quote and replies aren't followed.
pub fn attach_quoted(conn: &Connection, viewer: &str, item: &mut Value) -> anyhow::Result<()> {
    let Some(id) = item["quote"]["post_id"].as_str().map(str::to_string) else { return Ok(()) };
    if let Some(mut quoted) = one(conn, viewer, &id)? {
        if let Some(fields) = quoted.as_object_mut() {
            fields.remove("quote");
            fields.remove("reactions");
        }
        item["quoted"] = quoted;
    }
    Ok(())
}

/// Posts quoting `id` that this viewer may read, newest first (a thread view's Quotes). Reposts
/// aren't quotes.
pub fn quotes(conn: &Connection, viewer: &str, id: &str) -> anyhow::Result<Vec<Value>> {
    let readable = readable_sql("?1");
    let sql = format!(
        "SELECT {COLUMNS} FROM post p WHERE json_valid(p.quote) AND json_extract(p.quote,'$.post_id')=?2
         AND p.repost_of_id IS NULL AND p.deleted_at IS NULL AND {readable}
         ORDER BY p.occurred_at DESC,p.id DESC LIMIT 50"
    );
    let mut st = conn.prepare(&sql)?;
    let mut rows = st.query_map(params![viewer, id], row)?.collect::<Result<Vec<_>, _>>()?;
    for item in &mut rows {
        for_viewer(conn, viewer, item)?;
        item["reply_count"] = json!(reply_count(conn, viewer, item["id"].as_str().unwrap_or(""))?);
    }
    Ok(rows)
}

#[derive(Default, Deserialize)]
pub struct PostSearch {
    #[serde(default)]
    pub saved: bool,
    pub q: String,
    /// Only this account's posts.
    pub account: Option<String>,
    pub kind: Option<String>,
    pub before: Option<i64>,
    pub after: Option<i64>,
    pub limit: Option<usize>,
    pub cursor: Option<String>,
}

/// Where a page of post search results ended; it only continues the same search.
#[derive(serde::Serialize, Deserialize)]
struct SearchCursor {
    #[serde(default)]
    saved: bool,
    q: String,
    account: Option<String>,
    kind: Option<String>,
    before: Option<i64>,
    after: Option<i64>,
    score: f64,
    occurred_at: i64,
    id: String,
}

/// `GET /search/posts` (API.md §3): FTS over titles, text and tags, ranked like message search
/// (`bm25`, then newest first), pages of 1–100 with the same opaque `next_cursor`. Every hit goes
/// through [`readable_sql`]; API tokens (`read:posts`) search only their own account's posts.
pub fn search(
    conn: &Connection,
    principal: &crate::api_data::Principal,
    query: &PostSearch,
) -> Result<Value, crate::api_data::DataError> {
    use crate::api_data::DataError;
    use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
    if !principal.allows("read:posts") {
        return Err(DataError::Scope("read:posts"));
    }
    let q = query.q.trim();
    if q.is_empty() || q.len() > 200 {
        return Err(DataError::Bad("q must be 1–200 characters".into()));
    }
    let cursor: Option<SearchCursor> = match &query.cursor {
        None => None,
        Some(raw) => {
            let bad = || DataError::Bad("invalid cursor".into());
            let c: SearchCursor =
                serde_json::from_slice(&URL_SAFE_NO_PAD.decode(raw).map_err(|_| bad())?).map_err(|_| bad())?;
            if c.saved != query.saved
                || c.q != q
                || c.account != query.account
                || c.kind != query.kind
                || c.before != query.before
                || c.after != query.after
                || !c.score.is_finite()
                || c.id.is_empty()
            {
                return Err(DataError::Bad("cursor does not match search".into()));
            }
            Some(c)
        }
    };
    let limit = query.limit.unwrap_or(100).clamp(1, 100);
    let readable = readable_sql("?1");
    let sql = format!(
        "WITH hits AS (SELECT {COLUMNS}, 0.0 AS score
           FROM post_fts JOIN post p ON p.rowid = post_fts.rowid
           WHERE post_fts MATCH ?2 AND p.deleted_at IS NULL AND {readable}
             AND (?3 = 0 OR p.account_id = ?1)
             AND (?4 IS NULL OR p.account_id = ?4)
             AND (?5 IS NULL OR p.kind = ?5)
             AND (?6 IS NULL OR p.occurred_at < ?6)
             AND (?7 IS NULL OR p.occurred_at > ?7)
             AND (?12=0 OR EXISTS(SELECT 1 FROM pref WHERE account_id=?1 AND device_id='' AND key='bookmark:'||p.id AND value='true')))
         SELECT * FROM hits WHERE (?8 IS NULL OR score > ?8 OR
           (score = ?8 AND (occurred_at < ?9 OR (occurred_at = ?9 AND id < ?10))))
         ORDER BY score, occurred_at DESC, id DESC LIMIT ?11"
    );
    let mut st = conn.prepare(&sql)?;
    let rows = st.query_map(
        params![
            principal.account_id,
            q,
            !principal.is_device(),
            query.account,
            query.kind,
            query.before,
            query.after,
            cursor.as_ref().map(|c| c.score),
            cursor.as_ref().map(|c| c.occurred_at),
            cursor.as_ref().map(|c| c.id.as_str()),
            (limit + 1) as i64,
            query.saved,
        ],
        |r| Ok((row(r)?, r.get::<_, f64>(SCORE)?)),
    );
    let rows = match rows {
        Ok(rows) => rows,
        Err(rusqlite::Error::SqliteFailure(_, _)) => return Err(DataError::Bad("invalid search query".into())),
        Err(e) => return Err(e.into()),
    };
    let mut rows = match rows.collect::<Result<Vec<_>, _>>() {
        Ok(rows) => rows,
        // FTS5 reports a malformed MATCH expression when the rows are stepped
        Err(rusqlite::Error::SqliteFailure(_, _)) => return Err(DataError::Bad("invalid search query".into())),
        Err(e) => return Err(e.into()),
    };
    let next_cursor = if rows.len() > limit {
        rows.truncate(limit);
        let (last, score) = rows.last().expect("a paged search has a last row");
        let anchor = SearchCursor {
            saved: query.saved,
            q: q.to_string(),
            account: query.account.clone(),
            kind: query.kind.clone(),
            before: query.before,
            after: query.after,
            score: *score,
            occurred_at: last["occurred_at"].as_i64().unwrap_or_default(),
            id: last["id"].as_str().unwrap_or_default().to_string(),
        };
        Some(URL_SAFE_NO_PAD.encode(serde_json::to_vec(&anchor).map_err(|e| DataError::Bad(e.to_string()))?))
    } else {
        None
    };
    let mut items = Vec::with_capacity(rows.len());
    for (mut item, _) in rows {
        for_viewer(conn, &principal.account_id, &mut item)?;
        items.push(item);
    }
    Ok(json!({"items": items, "next_cursor": next_cursor}))
}
