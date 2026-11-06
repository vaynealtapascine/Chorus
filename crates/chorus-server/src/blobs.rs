//! Content-addressed, resumable blobs (API.md §5).

use std::fs::{self, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::PathBuf;

use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use rusqlite::{OptionalExtension, params};
use serde_json::json;
use sha2::{Digest, Sha256};

use crate::app::AppState;
use crate::{auth, now_ms};

const CHUNK_MAX: usize = 4 * 1024 * 1024;

#[derive(Debug)]
pub struct BlobError(StatusCode, &'static str);

impl IntoResponse for BlobError {
    fn into_response(self) -> Response {
        (self.0, axum::Json(json!({"error": {"code": self.1, "message": self.1, "retry": self.0.is_server_error()}})))
            .into_response()
    }
}

fn bad(reason: &'static str) -> BlobError {
    BlobError(StatusCode::BAD_REQUEST, reason)
}

fn internal(e: impl std::fmt::Display) -> BlobError {
    tracing::error!(error = %e, "blob store error");
    BlobError(StatusCode::INTERNAL_SERVER_ERROR, "internal")
}

fn authenticate(s: &AppState, conn: &rusqlite::Connection, h: &HeaderMap) -> Result<auth::Authed, BlobError> {
    let token = auth::bearer(h).ok_or(BlobError(StatusCode::UNAUTHORIZED, "unauthenticated"))?;
    auth::authenticate(conn, token, now_ms(), s.cfg.limits.session_days as i64 * 86_400_000)
        .map_err(|_| BlobError(StatusCode::UNAUTHORIZED, "unauthenticated"))
}

/// A blob name: a lowercase sha256 hex digest.
pub(crate) fn valid_hash(hash: &str) -> bool {
    hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

fn path(s: &AppState, hash: &str) -> Result<PathBuf, BlobError> {
    if !valid_hash(hash) {
        return Err(bad("invalid_hash"));
    }
    Ok(sharded(&s.cfg.blob_dir(), hash))
}

#[derive(Clone)]
struct Row {
    size: u64,
    received: u64,
    mime: String,
    uploader: String,
    complete: bool,
}

/// Where a blob's file lives under a blob directory (`ab/cd/abcd…`); `hash` is a [`valid_hash`].
pub(crate) fn sharded(root: &std::path::Path, hash: &str) -> PathBuf {
    root.join(&hash[..2]).join(&hash[2..4]).join(hash)
}

fn row(conn: &rusqlite::Connection, hash: &str) -> Result<Option<Row>, BlobError> {
    conn.query_row("SELECT size, received, mime, uploaded_by, is_complete FROM blob WHERE hash = ?1", [hash], |r| {
        Ok(Row {
            size: r.get::<_, i64>(0)? as u64,
            received: r.get::<_, i64>(1)? as u64,
            mime: r.get(2)?,
            uploader: r.get(3)?,
            complete: r.get::<_, i64>(4)? != 0,
        })
    })
    .optional()
    .map_err(internal)
}

fn recover_complete(
    conn: &rusqlite::Connection,
    hash: &str,
    dest: &std::path::Path,
    r: &mut Row,
) -> Result<(), BlobError> {
    if !r.complete && dest.metadata().is_ok_and(|m| m.len() == r.size) {
        // A crash may happen after the verified part is renamed but before SQLite is updated.
        conn.execute("UPDATE blob SET is_complete = 1, received = size WHERE hash = ?1", [hash]).map_err(internal)?;
        r.complete = true;
        r.received = r.size;
    }
    Ok(())
}

/// The owner sees their own references; readers see attachments of readable messages or posts.
/// Follower avatars are restricted to entries already revealed by the notifier.
fn can_read(conn: &rusqlite::Connection, account: &str, hash: &str, uploader: &str) -> Result<bool, BlobError> {
    if account == uploader || holds(conn, hash, account)? {
        return Ok(true);
    }
    if crate::spaces::author_avatar_visible(conn, account, hash).map_err(internal)? {
        return Ok(true);
    }
    if crate::profiles::blob_visible(conn, account, hash).map_err(internal)? {
        return Ok(true);
    }
    // A retained hash is not a grant after a private-chat/front/permission change.
    let principal = crate::api_data::Principal::owner(account);
    let mut view = crate::search_view::CurrentView::new(conn, &principal).map_err(internal)?;
    let mut st = conn
        .prepare_cached(
            "SELECT m.id FROM attachment a JOIN item_attachment ia ON ia.attachment_id=a.id
        AND ia.owner_type='message' JOIN message m ON m.id=ia.owner_id WHERE a.blob_hash=?1 OR a.thumb_blob_hash=?1",
        )
        .map_err(internal)?;
    let ids = st.query_map([hash], |r| r.get::<_, String>(0)).map_err(internal)?;
    for id in ids {
        if view.message(&id.map_err(internal)?).map_err(internal)? {
            return Ok(true);
        }
    }
    let mut st = conn.prepare_cached("SELECT id FROM channel WHERE avatar_blob=?1").map_err(internal)?;
    let ids = st.query_map([hash], |r| r.get::<_, String>(0)).map_err(internal)?;
    for id in ids {
        if view.channel_visible(&id.map_err(internal)?).map_err(internal)? {
            return Ok(true);
        }
    }
    conn.query_row(
        &format!("SELECT EXISTS (
           SELECT 1 FROM custom_emoji e WHERE e.blob_hash = ?1
           UNION ALL SELECT 1 FROM account a WHERE a.avatar_blob = ?1 AND a.id = ?2
           UNION ALL SELECT 1 FROM account a JOIN follow f ON
             ((f.target_account_id = a.id AND f.follower_account_id = ?2 AND f.status = 'active')
              OR (f.follower_account_id = a.id AND f.target_account_id = ?2 AND f.status IN ('requested','active')))
             WHERE a.avatar_blob = ?1
           UNION ALL SELECT 1 FROM account a
             JOIN space_member sm1 ON sm1.account_id = a.id AND sm1.is_present
             JOIN space_member sm2 ON sm2.space_id = sm1.space_id AND sm2.account_id = ?2 AND sm2.is_present
             WHERE a.avatar_blob = ?1
           UNION ALL SELECT 1 FROM member m WHERE (m.avatar_blob = ?1 OR m.banner_blob = ?1)
              AND m.account_id = ?2 AND m.deleted_at IS NULL
           UNION ALL SELECT 1 FROM member_group g WHERE g.avatar_blob = ?1 AND g.account_id = ?2 AND g.deleted_at IS NULL
           UNION ALL SELECT 1 FROM attachment a WHERE (a.blob_hash = ?1 OR a.thumb_blob_hash = ?1)
              AND a.account_id = ?2
           UNION ALL SELECT 1 FROM attachment a
              JOIN item_attachment ia ON ia.attachment_id = a.id AND ia.owner_type = 'post'
              JOIN post p ON p.id = ia.owner_id AND p.deleted_at IS NULL
              WHERE (a.blob_hash = ?1 OR a.thumb_blob_hash = ?1)
                AND {}
           UNION ALL SELECT 1 FROM member m
              -- Body's view or a granted front's (D-084; a revoked grant's row is deleted)
              JOIN follower_front_view fv ON fv.target_account_id = m.account_id AND fv.follower_account_id = ?2
              JOIN follow f ON f.target_account_id = m.account_id AND f.follower_account_id = ?2 AND f.status = 'active'
              WHERE m.avatar_blob = ?1 AND m.deleted_at IS NULL
                AND EXISTS (SELECT 1 FROM json_each(fv.entries) je
                    WHERE json_extract(je.value, '$.subject_type') = 'member'
                      AND json_extract(je.value, '$.subject_id') = m.id)
         )", crate::posts::readable_sql("?2")),
        params![hash, account],
        |r| r.get(0),
    )
    .map_err(internal)
}

/// Whether `account` sent all of a stored file's bytes after someone else uploaded it.
fn holds(conn: &rusqlite::Connection, hash: &str, account: &str) -> Result<bool, BlobError> {
    conn.prepare_cached("SELECT 1 FROM blob_holder WHERE hash = ?1 AND account_id = ?2 AND is_complete")
        .and_then(|mut st| st.exists(params![hash, account]))
        .map_err(internal)
}

/// Whether an op by `account` may name this file (ingest `unproven_blob`): one the server doesn't
/// have, or one the account uploaded, holds, or can read now.
pub(crate) fn may_name(conn: &rusqlite::Connection, account: &str, hash: &str) -> anyhow::Result<bool> {
    let uploader: Option<String> =
        conn.query_row("SELECT uploaded_by FROM blob WHERE hash = ?1", [hash], |r| r.get(0)).optional()?;
    let Some(uploader) = uploader else { return Ok(true) };
    can_read(conn, account, hash, &uploader).map_err(|e| anyhow::anyhow!("blob check: {}", e.1))
}

/// Someone else stored this file. Answering 200 to the first chunk told anyone who asked that the
/// server has it (a dedup oracle), and let them name it in an op without having it. So the
/// bytes are taken as for a new upload and compared with the stored file, and the account then
/// holds it. The answers are the ones a new upload gets: 202 per chunk, 201 at the end, and
/// `hash_mismatch` there if any chunk differed.
#[allow(clippy::too_many_arguments)]
fn prove(
    conn: &rusqlite::Connection,
    dest: &std::path::Path,
    hash: &str,
    account: &str,
    size: u64,
    (start, end, total): (u64, u64, u64),
    bytes: &[u8],
) -> Result<Response, BlobError> {
    let held: Option<(i64, bool)> = conn
        .query_row(
            "SELECT received, matches FROM blob_holder WHERE hash = ?1 AND account_id = ?2",
            params![hash, account],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(internal)?;
    // starting again from the beginning is always allowed, as for a new upload
    let (received, mut matches) = held.filter(|_| start != 0).map_or((0, true), |(r, m)| (r as u64, m));
    if start != received {
        return Err(BlobError(StatusCode::CONFLICT, "upload_offset_conflict"));
    }
    matches &= total == size && {
        let mut stored = vec![0u8; bytes.len()];
        let mut f = fs::File::open(dest).map_err(internal)?;
        f.seek(SeekFrom::Start(start)).map_err(internal)?;
        f.read_exact(&mut stored).is_ok() && stored == bytes
    };
    let done = end + 1 == total;
    if done && !matches {
        conn.execute("DELETE FROM blob_holder WHERE hash = ?1 AND account_id = ?2", params![hash, account])
            .map_err(internal)?;
        return Err(bad("hash_mismatch"));
    }
    conn.execute(
        "INSERT INTO blob_holder(hash, account_id, received, matches, is_complete) VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(hash, account_id) DO UPDATE SET received = ?3, matches = ?4, is_complete = ?5",
        params![hash, account, (end + 1) as i64, matches, done],
    )
    .map_err(internal)?;
    if done {
        return Ok(StatusCode::CREATED.into_response());
    }
    Ok((StatusCode::ACCEPTED, [("upload-offset", (end + 1).to_string())]).into_response())
}

fn content_range(h: &HeaderMap) -> Result<(u64, u64, u64), BlobError> {
    let text = h.get(header::CONTENT_RANGE).and_then(|v| v.to_str().ok()).ok_or(bad("missing_content_range"))?;
    let rest = text.strip_prefix("bytes ").ok_or(bad("invalid_content_range"))?;
    let (span, total) = rest.split_once('/').ok_or(bad("invalid_content_range"))?;
    let (start, end) = span.split_once('-').ok_or(bad("invalid_content_range"))?;
    let (start, end, total) = (
        start.parse::<u64>().map_err(|_| bad("invalid_content_range"))?,
        end.parse::<u64>().map_err(|_| bad("invalid_content_range"))?,
        total.parse::<u64>().map_err(|_| bad("invalid_content_range"))?,
    );
    if total == 0 || end < start || end >= total {
        return Err(bad("invalid_content_range"));
    }
    Ok((start, end, total))
}

pub async fn head_blob(
    State(s): State<AppState>,
    Path(hash): Path<String>,
    h: HeaderMap,
) -> Result<Response, BlobError> {
    let dest = path(&s, &hash)?;
    let conn = s.db.lock().map_err(internal)?;
    let me = authenticate(&s, &conn, &h)?;
    let Some(mut r) = row(&conn, &hash)? else { return Ok(StatusCode::NOT_FOUND.into_response()) };
    recover_complete(&conn, &hash, &dest, &mut r)?;
    if !can_read(&conn, &me.account_id, &hash, &r.uploader)? {
        // part way through sending the bytes of someone else's file (`prove`): resume like any upload
        let sent: Option<i64> = conn
            .query_row(
                "SELECT received FROM blob_holder WHERE hash = ?1 AND account_id = ?2 AND NOT is_complete",
                params![hash, me.account_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(internal)?;
        return Ok(match sent {
            Some(n) if r.complete => (StatusCode::PARTIAL_CONTENT, [("upload-offset", n.to_string())]).into_response(),
            _ => StatusCode::NOT_FOUND.into_response(),
        });
    }
    if r.complete && dest.is_file() {
        return Ok((StatusCode::OK, [(header::CONTENT_LENGTH, r.size.to_string())]).into_response());
    }
    if !r.complete && dest.with_extension("part").is_file() {
        return Ok((StatusCode::PARTIAL_CONTENT, [("upload-offset", r.received.to_string())]).into_response());
    }
    Ok(StatusCode::NOT_FOUND.into_response())
}

pub async fn put_blob(
    State(s): State<AppState>,
    Path(hash): Path<String>,
    h: HeaderMap,
    bytes: Bytes,
) -> Result<Response, BlobError> {
    let dest = path(&s, &hash)?;
    let (start, end, total) = content_range(&h)?;
    if bytes.is_empty() || bytes.len() > CHUNK_MAX || end - start + 1 != bytes.len() as u64 {
        return Err(bad("invalid_chunk"));
    }
    let limit = s.cfg.limits.max_blob_mb.saturating_mul(1024 * 1024);
    if total > limit {
        return Err(BlobError(StatusCode::PAYLOAD_TOO_LARGE, "blob_too_large"));
    }
    let conn = s.db.lock().map_err(internal)?;
    let me = authenticate(&s, &conn, &h)?;
    let mut current = row(&conn, &hash)?;
    if let Some(r) = &mut current {
        recover_complete(&conn, &hash, &dest, r)?
    }
    if let Some(r) = &current {
        // content-addressed: a finished blob is the same bytes whoever sends them again (a
        // device re-uploading its files after a restore, SYNC.md §7.3), so that's a success.
        // Refusing it made the device retry forever. Another account proves it has the bytes.
        if r.complete {
            if r.uploader == me.account_id || holds(&conn, &hash, &me.account_id)? {
                return Ok(StatusCode::OK.into_response());
            }
            return prove(&conn, &dest, &hash, &me.account_id, r.size, (start, end, total), &bytes);
        }
        if r.uploader != me.account_id {
            return Err(BlobError(StatusCode::FORBIDDEN, "forbidden"));
        }
        if r.size != total || r.received != start {
            return Err(BlobError(StatusCode::CONFLICT, "upload_offset_conflict"));
        }
    } else if start != 0 {
        return Err(BlobError(StatusCode::CONFLICT, "upload_offset_conflict"));
    }
    let part = dest.with_extension("part");
    if current.is_some() && part.metadata().map(|m| m.len() < start).unwrap_or(true) {
        return Err(BlobError(StatusCode::CONFLICT, "upload_partial_missing"));
    }
    fs::create_dir_all(dest.parent().ok_or_else(|| internal("missing blob parent"))?).map_err(internal)?;
    let mut f = OpenOptions::new().create(true).truncate(false).write(true).open(&part).map_err(internal)?;
    if current.is_some() {
        f.set_len(start).map_err(internal)?; // recover when a crash wrote bytes before updating SQLite
    } else {
        f.set_len(0).map_err(internal)?;
    }
    f.seek(SeekFrom::Start(start)).map_err(internal)?;
    f.write_all(&bytes).map_err(internal)?;
    f.sync_all().map_err(internal)?;
    drop(f);
    let mime = h.get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or("application/octet-stream");
    if current.is_none() {
        conn.execute(
            "INSERT INTO blob(hash,size,mime,stored_at,uploaded_by,received,is_complete) VALUES (?1,?2,?3,?4,?5,?6,0)",
            params![hash, total as i64, mime, now_ms(), me.account_id, (end + 1) as i64],
        )
        .map_err(internal)?;
    } else {
        conn.execute("UPDATE blob SET received = ?2 WHERE hash = ?1", params![hash, (end + 1) as i64])
            .map_err(internal)?;
    }
    if end + 1 < total {
        return Ok((StatusCode::ACCEPTED, [("upload-offset", (end + 1).to_string())]).into_response());
    }
    let mut f = fs::File::open(&part).map_err(internal)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = f.read(&mut buf).map_err(internal)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    drop(f);
    if format!("{:x}", hasher.finalize()) != hash {
        fs::remove_file(&part).map_err(internal)?;
        conn.execute("DELETE FROM blob WHERE hash = ?1 AND is_complete = 0", [&hash]).map_err(internal)?;
        return Err(bad("hash_mismatch"));
    }
    fs::rename(&part, &dest).map_err(internal)?;
    conn.execute("UPDATE blob SET is_complete = 1, received = size WHERE hash = ?1", [&hash]).map_err(internal)?;
    Ok(StatusCode::CREATED.into_response())
}

pub(crate) fn read_range(h: &HeaderMap, size: u64) -> Result<Option<(u64, u64)>, BlobError> {
    let Some(raw) = h.get(header::RANGE) else { return Ok(None) };
    let text = raw.to_str().map_err(|_| bad("invalid_range"))?;
    let span = text.strip_prefix("bytes=").ok_or(bad("invalid_range"))?;
    if span.contains(',') {
        return Err(bad("invalid_range"));
    }
    let (start, end) = span.split_once('-').ok_or(bad("invalid_range"))?;
    if size == 0 {
        return Err(BlobError(StatusCode::RANGE_NOT_SATISFIABLE, "range_not_satisfiable"));
    }
    let (start, end) = if start.is_empty() {
        let suffix = end.parse::<u64>().map_err(|_| bad("invalid_range"))?;
        (size.saturating_sub(suffix), size - 1)
    } else {
        let start = start.parse::<u64>().map_err(|_| bad("invalid_range"))?;
        let end = if end.is_empty() { size - 1 } else { end.parse::<u64>().map_err(|_| bad("invalid_range"))? };
        (start, end.min(size - 1))
    };
    if start > end || start >= size {
        return Err(BlobError(StatusCode::RANGE_NOT_SATISFIABLE, "range_not_satisfiable"));
    }
    Ok(Some((start, end)))
}

pub async fn get_blob(
    State(s): State<AppState>,
    Path(hash): Path<String>,
    h: HeaderMap,
) -> Result<Response, BlobError> {
    let dest = path(&s, &hash)?;
    let conn = s.db.lock().map_err(internal)?;
    let me = authenticate(&s, &conn, &h)?;
    let mut r = row(&conn, &hash)?.ok_or(BlobError(StatusCode::NOT_FOUND, "not_found"))?;
    recover_complete(&conn, &hash, &dest, &mut r)?;
    if !can_read(&conn, &me.account_id, &hash, &r.uploader)? {
        return Err(BlobError(StatusCode::NOT_FOUND, "not_found"));
    }
    if !r.complete || !dest.is_file() {
        return Err(BlobError(StatusCode::NOT_FOUND, "not_found"));
    }
    let range = read_range(&h, r.size)?;
    drop(conn);
    let (start, end) = range.unwrap_or((0, r.size.saturating_sub(1)));
    let mut f = fs::File::open(&dest).map_err(internal)?;
    f.seek(SeekFrom::Start(start)).map_err(internal)?;
    let mut data = Vec::new();
    f.take(if range.is_some() { end - start + 1 } else { r.size }).read_to_end(&mut data).map_err(internal)?;
    let mut response =
        (if range.is_some() { StatusCode::PARTIAL_CONTENT } else { StatusCode::OK }, data).into_response();
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&r.mime).unwrap_or(HeaderValue::from_static("application/octet-stream")),
    );
    // private: blobs are access-checked, so no shared cache (a CDN in front) may keep them
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("private, max-age=31536000, immutable"));
    // the type is the uploader's word: never sniffed, and never a page that runs script
    headers.insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    headers.insert(header::CONTENT_SECURITY_POLICY, HeaderValue::from_static("default-src 'none'; sandbox"));
    headers.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    if range.is_some() {
        headers.insert(
            header::CONTENT_RANGE,
            HeaderValue::from_str(&format!("bytes {start}-{end}/{}", r.size)).map_err(internal)?,
        );
    }
    Ok(response)
}
