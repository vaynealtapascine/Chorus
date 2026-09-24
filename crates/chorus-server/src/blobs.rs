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
    let token = h
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or(BlobError(StatusCode::UNAUTHORIZED, "unauthenticated"))?;
    auth::authenticate(conn, token, now_ms(), s.cfg.limits.session_days as i64 * 86_400_000)
        .map_err(|_| BlobError(StatusCode::UNAUTHORIZED, "unauthenticated"))
}

fn valid_hash(hash: &str) -> bool {
    hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

fn path(s: &AppState, hash: &str) -> Result<PathBuf, BlobError> {
    if !valid_hash(hash) {
        return Err(bad("invalid_hash"));
    }
    Ok(s.cfg.blob_dir().join(&hash[..2]).join(&hash[2..4]).join(hash))
}

#[derive(Clone)]
struct Row {
    size: u64,
    received: u64,
    mime: String,
    uploader: String,
    complete: bool,
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
    if account == uploader {
        return Ok(true);
    }
    if crate::spaces::author_avatar_visible(conn, account, hash).map_err(internal)? {
        return Ok(true);
    }
    conn.query_row(
        &format!("SELECT EXISTS (
           SELECT 1 FROM custom_emoji e WHERE e.blob_hash = ?1
           UNION ALL SELECT 1 FROM account a WHERE a.avatar_blob = ?1 AND a.id = ?2
           UNION ALL SELECT 1 FROM account a JOIN follow f ON
             ((f.target_account_id = a.id AND f.follower_account_id = ?2 AND f.status = 'active')
              OR (f.follower_account_id = a.id AND f.target_account_id = ?2 AND f.status IN ('requested','active')))
             WHERE a.avatar_blob = ?1
           UNION ALL SELECT 1 FROM member m WHERE (m.avatar_blob = ?1 OR m.banner_blob = ?1)
              AND m.account_id = ?2 AND m.deleted_at IS NULL
           UNION ALL SELECT 1 FROM member_group g WHERE g.avatar_blob = ?1 AND g.account_id = ?2 AND g.deleted_at IS NULL
           UNION ALL SELECT 1 FROM attachment a WHERE (a.blob_hash = ?1 OR a.thumb_blob_hash = ?1)
              AND a.account_id = ?2
           UNION ALL SELECT 1 FROM attachment a
              JOIN item_attachment ia ON ia.attachment_id = a.id AND ia.owner_type = 'message'
              JOIN message m ON m.id = ia.owner_id AND m.deleted_at IS NULL
              JOIN channel c ON c.id = m.channel_id AND c.deleted_at IS NULL
              JOIN scope_access sa ON sa.scope = 'space:' || c.space_id AND sa.account_id = ?2
              WHERE (a.blob_hash = ?1 OR a.thumb_blob_hash = ?1)
                AND {}
           UNION ALL SELECT 1 FROM attachment a
              JOIN item_attachment ia ON ia.attachment_id = a.id AND ia.owner_type = 'post'
              JOIN post p ON p.id = ia.owner_id AND p.deleted_at IS NULL
              WHERE (a.blob_hash = ?1 OR a.thumb_blob_hash = ?1)
                AND {}
           UNION ALL SELECT 1 FROM member m
              JOIN follower_front_view fv ON fv.target_account_id = m.account_id AND fv.follower_account_id = ?2
              JOIN follow f ON f.target_account_id = m.account_id AND f.follower_account_id = ?2 AND f.status = 'active'
              WHERE m.avatar_blob = ?1 AND m.deleted_at IS NULL
                AND EXISTS (SELECT 1 FROM json_each(fv.entries) je
                    WHERE json_extract(je.value, '$.subject_type') = 'member'
                      AND json_extract(je.value, '$.subject_id') = m.id)
         )", crate::visibility::PUBLIC_MESSAGE_SQL, crate::posts::readable_sql("?2")),
        params![hash, account],
        |r| r.get(0),
    )
    .map_err(internal)
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
        return Err(BlobError(StatusCode::FORBIDDEN, "forbidden"));
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
        if r.uploader != me.account_id {
            return Err(BlobError(StatusCode::FORBIDDEN, "forbidden"));
        }
        if r.complete {
            return Ok(StatusCode::OK.into_response());
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

fn read_range(h: &HeaderMap, size: u64) -> Result<Option<(u64, u64)>, BlobError> {
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
        return Err(BlobError(StatusCode::FORBIDDEN, "forbidden"));
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
