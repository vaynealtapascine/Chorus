//! The full export bundle (D-068; design in docs/handoff/opus-remote-2.md, appendix; API.md §4):
//! a background job that writes `chorus-<handle>-<YYYYMMDD>.zip` —
//!
//! ```text
//! README.txt       what this is
//! ops.jsonl        exactly GET /exports/ops.jsonl (the account's authored ops, seq order)
//! csv/<name>.csv   the seven tidy tables (DATA_MODEL §7.1)
//! blobs/<sha256>   every file the account's own ops point at (checked against its hash)
//! manifest.json    format 1: account, times, ops {count, sha256}, blobs [{hash, size, mime,
//!                  filenames}], missing, damaged, csv names
//! ```
//!
//! The zip is streamed to `data/exports/<id>.zip.part` (never held in memory) and renamed when
//! complete. One job per account at a time, two server-wide. A finished file is kept 24 h, or
//! an hour after its first full download, then deleted. Jobs live in `export_job`, so a restart
//! fails the ones that were running instead of forgetting them. The download URL carries a
//! random key (so a browser can fetch it natively, with Range, without an Authorization header);
//! the key dies with the file.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use sha2::{Digest as _, Sha256};

use crate::config::Config;
use crate::exports;

/// A finished export is kept this long…
pub const KEEP_MS: i64 = 24 * 3_600_000;
/// …or this long after its first full download, whichever comes first.
pub const AFTER_DOWNLOAD_MS: i64 = 3_600_000;
/// Jobs running at once, server-wide.
pub const SLOTS: usize = 2;

pub fn dir(cfg: &Config) -> PathBuf {
    cfg.server.data_dir.join("exports")
}

fn file(cfg: &Config, id: &str) -> PathBuf {
    dir(cfg).join(format!("{id}.zip"))
}

fn part(cfg: &Config, id: &str) -> PathBuf {
    dir(cfg).join(format!("{id}.zip.part"))
}

pub enum Created {
    New(String),
    /// The account already has one queued or running.
    Busy(String),
}

/// Queue a full export for `account`.
pub fn create(conn: &Connection, account: &str, now: i64) -> rusqlite::Result<Created> {
    let busy: Option<String> = conn
        .query_row(
            "SELECT id FROM export_job WHERE account_id = ?1 AND status IN ('queued','running')",
            [account],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(id) = busy {
        return Ok(Created::Busy(id));
    }
    let id = chorus_core::id::new_id(now as u64, rand::random());
    conn.execute(
        "INSERT INTO export_job (id, account_id, kind, status, created_at) VALUES (?1, ?2, 'full', 'queued', ?3)",
        params![id, account, now],
    )?;
    Ok(Created::New(id))
}

/// `GET /jobs/{id}` for its owner; `None` for anyone else.
pub fn view(conn: &Connection, account: &str, id: &str) -> rusqlite::Result<Option<Value>> {
    conn.query_row(
        "SELECT id, kind, status, phase, done, total, bytes, error, file_name, download_key, created_at,
                finished_at, expires_at FROM export_job WHERE id = ?1 AND account_id = ?2",
        params![id, account],
        |r| {
            let status: String = r.get(2)?;
            let key: Option<String> = r.get(9)?;
            let result_url = (status == "done")
                .then(|| {
                    key.map(|k| {
                        format!("/api/v1/exports/{}/download?key={k}", r.get::<_, String>(0).unwrap_or_default())
                    })
                })
                .flatten();
            Ok(json!({
                "id": r.get::<_, String>(0)?, "kind": r.get::<_, String>(1)?, "status": status,
                "phase": r.get::<_, Option<String>>(3)?, "done": r.get::<_, i64>(4)?, "total": r.get::<_, i64>(5)?,
                "bytes": r.get::<_, i64>(6)?, "error": r.get::<_, Option<String>>(7)?,
                "file_name": r.get::<_, Option<String>>(8)?, "result_url": result_url,
                "created_at": r.get::<_, i64>(10)?, "finished_at": r.get::<_, Option<i64>>(11)?,
                "expires_at": r.get::<_, Option<i64>>(12)?,
            }))
        },
    )
    .optional()
}

/// The account's latest job, if any (the web page picks up where it left off).
pub fn latest(conn: &Connection, account: &str) -> rusqlite::Result<Option<Value>> {
    let id: Option<String> = conn
        .query_row(
            "SELECT id FROM export_job WHERE account_id = ?1 ORDER BY created_at DESC, id DESC LIMIT 1",
            [account],
            |r| r.get(0),
        )
        .optional()?;
    match id {
        Some(id) => view(conn, account, &id),
        None => Ok(None),
    }
}

/// `DELETE /jobs/{id}`: cancel a queued or running job, or delete a finished file now.
/// False when the job isn't the account's.
pub fn cancel(conn: &Connection, cfg: &Config, account: &str, id: &str, now: i64) -> rusqlite::Result<bool> {
    let status: Option<String> = conn
        .query_row("SELECT status FROM export_job WHERE id = ?1 AND account_id = ?2", params![id, account], |r| {
            r.get(0)
        })
        .optional()?;
    match status.as_deref() {
        None => return Ok(false),
        Some("queued" | "running") => {
            conn.execute(
                "UPDATE export_job SET status = 'cancelled', finished_at = ?2 WHERE id = ?1",
                params![id, now],
            )?;
        }
        Some("done") => {
            let _ = fs::remove_file(file(cfg, id));
            conn.execute(
                "UPDATE export_job SET status = 'expired', download_key = NULL, expires_at = ?2 WHERE id = ?1",
                params![id, now],
            )?;
        }
        Some(_) => {}
    }
    Ok(true)
}

/// At start: jobs that were queued or running when the server stopped won't finish.
pub fn fail_interrupted(conn: &Connection, cfg: &Config, now: i64) -> rusqlite::Result<()> {
    let ids: Vec<String> = conn
        .prepare("SELECT id FROM export_job WHERE status IN ('queued','running')")?
        .query_map([], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    for id in ids {
        let _ = fs::remove_file(part(cfg, &id));
        conn.execute(
            "UPDATE export_job SET status = 'failed', error = 'the server restarted; start it again', finished_at = ?2
             WHERE id = ?1",
            params![id, now],
        )?;
    }
    Ok(())
}

/// Delete finished files past their time.
pub fn expire(conn: &Connection, cfg: &Config, now: i64) -> rusqlite::Result<usize> {
    let ids: Vec<String> = conn
        .prepare("SELECT id FROM export_job WHERE status = 'done' AND expires_at <= ?1")?
        .query_map([now], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    for id in &ids {
        let _ = fs::remove_file(file(cfg, id));
        conn.execute("UPDATE export_job SET status = 'expired', download_key = NULL WHERE id = ?1", [id])?;
    }
    Ok(ids.len())
}

/// Drop an account's jobs and files (purge).
pub fn forget_account(conn: &Connection, cfg: &Config, account: &str) -> rusqlite::Result<()> {
    let ids: Vec<String> = conn
        .prepare("SELECT id FROM export_job WHERE account_id = ?1")?
        .query_map([account], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    for id in ids {
        let _ = fs::remove_file(file(cfg, &id));
        let _ = fs::remove_file(part(cfg, &id));
    }
    conn.execute("DELETE FROM export_job WHERE account_id = ?1", [account])?;
    Ok(())
}

/// A finished file and its name, if `key` opens it.
pub fn download(conn: &Connection, cfg: &Config, id: &str, key: &str) -> rusqlite::Result<Option<(PathBuf, String)>> {
    let row: Option<(Option<String>, Option<String>)> = conn
        .query_row("SELECT download_key, file_name FROM export_job WHERE id = ?1 AND status = 'done'", [id], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .optional()?;
    let Some((Some(want), name)) = row else { return Ok(None) };
    // compared in constant time: the key is the only thing guarding the file
    let same = want.len() == key.len() && want.bytes().zip(key.bytes()).fold(0u8, |acc, (a, b)| acc | (a ^ b)) == 0;
    let path = file(cfg, id);
    Ok((same && path.is_file()).then(|| (path, name.unwrap_or_else(|| format!("{id}.zip")))))
}

/// A complete download happened: the file goes an hour later (if not sooner).
pub fn downloaded(conn: &Connection, id: &str, now: i64) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE export_job SET downloaded_at = ?2, expires_at = min(coalesce(expires_at, ?3), ?3)
         WHERE id = ?1 AND downloaded_at IS NULL",
        params![id, now, now + AFTER_DOWNLOAD_MS],
    )?;
    Ok(())
}

/// How far a running job is.
#[derive(Clone, Debug, Default)]
pub struct Progress {
    pub phase: &'static str,
    pub done: u64,
    pub total: u64,
    pub bytes: u64,
}

/// Write the progress; false when the job was cancelled meanwhile.
pub fn report(conn: &Connection, id: &str, p: &Progress) -> rusqlite::Result<bool> {
    let n = conn.execute(
        "UPDATE export_job SET status = 'running', phase = ?2, done = ?3, total = ?4, bytes = ?5
         WHERE id = ?1 AND status IN ('queued','running')",
        params![id, p.phase, p.done as i64, p.total as i64, p.bytes as i64],
    )?;
    Ok(n == 1)
}

/// The job ended: done (with the file's name and size), or failed.
pub fn finish(conn: &Connection, id: &str, outcome: Result<(String, u64), String>, now: i64) -> rusqlite::Result<()> {
    let account: Option<String> = conn
        .query_row("SELECT account_id FROM export_job WHERE id = ?1 AND status IN ('queued','running')", [id], |r| {
            r.get(0)
        })
        .optional()?;
    let Some(account) = account else { return Ok(()) }; // cancelled meanwhile
    match outcome {
        Ok((name, bytes)) => {
            let key: String = (0..16).map(|_| format!("{:02x}", rand::random::<u8>())).collect();
            conn.execute(
                "UPDATE export_job SET status = 'done', phase = NULL, bytes = ?2, file_name = ?3, download_key = ?4,
                   finished_at = ?5, expires_at = ?6, error = NULL WHERE id = ?1",
                params![id, bytes as i64, name, key, now, now + KEEP_MS],
            )?;
            // an in-app notice, not a push: the account asked for it moments ago
            let payload = json!({"t": "export_ready", "title": "Your export is ready",
                "text": format!("{name} · {:.1} MB, on the Your data page for a day", bytes as f64 / 1_048_576.0),
                "job_id": id});
            conn.execute(
                "INSERT INTO notification (id, recipient_account_id, kind, payload, created_at, due_at, delivered_at)
                 VALUES (?1, ?2, 'export_ready', ?3, ?4, ?4, ?4)",
                params![chorus_core::id::new_id(now as u64, rand::random()), account, payload.to_string(), now],
            )?;
        }
        Err(e) => {
            conn.execute(
                "UPDATE export_job SET status = 'failed', error = ?2, finished_at = ?3 WHERE id = ?1",
                params![id, e, now],
            )?;
        }
    }
    Ok(())
}

// ─── building the bundle ─────────────────────────────────────────────────────

/// Payload keys that name a blob (attachments, avatars, banners, custom emoji).
const BLOB_KEYS: &[&str] = &["blob_hash", "thumb_blob_hash", "avatar_blob", "banner_blob"];

struct BlobInfo {
    size: u64,
    mime: String,
    filenames: BTreeSet<String>,
}

fn valid_hash(h: &str) -> bool {
    h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

/// Every blob the account's own applied ops point at, with the names it was sent under.
fn own_blobs(conn: &Connection, account: &str) -> rusqlite::Result<BTreeMap<String, BTreeSet<String>>> {
    let mut out: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    let mut st = conn.prepare("SELECT payload FROM op WHERE account_id = ?1 AND status = 'applied' ORDER BY seq")?;
    let rows = st.query_map([account], |r| r.get::<_, String>(0))?;
    for payload in rows {
        let Ok(v) = serde_json::from_str::<Value>(&payload?) else { continue };
        for key in BLOB_KEYS {
            if let Some(h) = v.get(*key).and_then(Value::as_str).filter(|h| valid_hash(h)) {
                let names = out.entry(h.to_string()).or_default();
                if *key == "blob_hash"
                    && let Some(name) = v.get("filename").and_then(Value::as_str).filter(|n| !n.is_empty())
                {
                    names.insert(name.to_string());
                }
            }
        }
    }
    Ok(out)
}

/// Free bytes where `path` lives, if the platform says (Unix, through `rustix`; no safe call
/// on Windows without a new dependency, so there a full disk just fails the job).
pub fn free_space(path: &Path) -> Option<u64> {
    #[cfg(unix)]
    {
        let s = rustix::fs::statvfs(path).ok()?;
        Some(s.f_bavail.saturating_mul(s.f_frsize))
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        None
    }
}

/// Hash while writing (for `ops.jsonl`'s manifest entry).
struct Hashing<'a> {
    out: &'a mut dyn Write,
    sha: Sha256,
}

impl Write for Hashing<'_> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = self.out.write(buf)?;
        self.sha.update(&buf[..n]);
        Ok(n)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.out.flush()
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn ymd(unix_ms: i64) -> String {
    let (_, date) = crate::zip::dos_time(unix_ms);
    format!("{:04}{:02}{:02}", (date >> 9) + 1980, (date >> 5) & 0xF, date & 0x1F)
}

/// Build the bundle for `account` from `reader` (a read snapshot), reporting through `progress`
/// (false = cancelled). Returns the file name and size. Blocking; run it off the async runtime.
pub fn build(
    cfg: &Config,
    reader: &Connection,
    id: &str,
    account: &str,
    now: i64,
    progress: &mut dyn FnMut(&Progress) -> bool,
) -> anyhow::Result<(String, u64)> {
    let (handle, kind): (Option<String>, String) =
        reader
            .query_row("SELECT handle, kind FROM account WHERE id = ?1", [account], |r| Ok((r.get(0)?, r.get(1)?)))?;
    let name = format!("chorus-{}-{}.zip", handle.as_deref().unwrap_or("account"), ymd(now));
    let op_count: u64 =
        reader.query_row("SELECT count(*) FROM op WHERE account_id = ?1 AND status = 'applied'", [account], |r| {
            r.get::<_, i64>(0)
        })? as u64;
    let op_bytes: u64 = reader.query_row(
        "SELECT coalesce(sum(length(payload)), 0) + count(*) * 400 FROM op WHERE account_id = ?1 AND status = 'applied'",
        [account],
        |r| r.get::<_, i64>(0),
    )? as u64;
    let names = own_blobs(reader, account)?;
    let mut blobs: BTreeMap<String, BlobInfo> = BTreeMap::new();
    let mut missing: Vec<String> = Vec::new();
    for (hash, filenames) in names {
        let row: Option<(i64, String, bool)> = reader
            .query_row("SELECT size, mime, is_complete FROM blob WHERE hash = ?1", [&hash], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?))
            })
            .optional()?;
        match row {
            Some((size, mime, true)) if blob_path(cfg, &hash).is_file() => {
                blobs.insert(hash, BlobInfo { size: size as u64, mime, filenames });
            }
            _ => missing.push(hash),
        }
    }
    // protect the server's disk: refuse if the export would leave less than its own size free
    let estimate = op_bytes * 2 + blobs.values().map(|b| b.size).sum::<u64>() + 1_048_576;
    fs::create_dir_all(dir(cfg))?;
    if let Some(free) = free_space(&dir(cfg))
        && free < estimate * 2
    {
        anyhow::bail!(
            "not enough free disk space on the server for this export (about {} MB needed, {} MB free)",
            estimate / 1_048_576,
            free / 1_048_576
        );
    }
    let total = op_count + exports::CSV_NAMES.len() as u64 + blobs.len() as u64;
    let mut p = Progress { phase: "ops", done: 0, total, bytes: 0 };
    if !progress(&p) {
        anyhow::bail!("cancelled");
    }
    let tmp = part(cfg, id);
    let result = (|| -> anyhow::Result<u64> {
        let out = BufWriter::with_capacity(1 << 20, fs::File::create(&tmp)?);
        let mut z = crate::zip::ZipWriter::new(out, now);
        let readme = "This is a Chorus export: your account's own history (ops.jsonl, the op log a Chorus server can\n\
                      import), the same data as tables (csv/), and your files (blobs/, named by their SHA-256;\n\
                      manifest.json lists every name each file was sent under).\n";
        z.add("README.txt", readme.as_bytes(), readme.len() as u64, |_| {})?;
        // ops.jsonl, streamed; the op log is the account's history, so it is the one that may be huge
        let mut sha = Sha256::new();
        let mut last = std::time::Instant::now();
        let mut stopped = false;
        z.add_with("ops.jsonl", u64::MAX, |w| {
            let mut h = Hashing { out: w, sha: Sha256::new() };
            let r = exports::write_ops_jsonl(reader, account, &mut h, |n| {
                if last.elapsed().as_millis() >= 500 {
                    last = std::time::Instant::now();
                    p.done = n;
                    if !progress(&p) {
                        stopped = true;
                        return false;
                    }
                }
                true
            });
            sha = h.sha;
            r.map(|_| ()).map_err(std::io::Error::other)
        })?;
        let ops_sha = hex(&sha.finalize());
        p.done = op_count;
        p.phase = "csv";
        p.bytes = z.written();
        if !progress(&p) {
            anyhow::bail!("cancelled");
        }
        for table in exports::CSV_NAMES {
            let bytes = exports::csv(reader, account, table)?.unwrap_or_default();
            z.add(&format!("csv/{table}.csv"), &bytes[..], bytes.len() as u64, |_| {})?;
            p.done += 1;
            p.bytes = z.written();
            if !progress(&p) {
                anyhow::bail!("cancelled");
            }
        }
        p.phase = "blobs";
        let mut damaged: Vec<String> = Vec::new();
        for (hash, info) in &blobs {
            let f = fs::File::open(blob_path(cfg, hash))?;
            let mut sha = Sha256::new();
            z.add(&format!("blobs/{hash}"), f, info.size, |chunk| sha.update(chunk))?;
            if hex(&sha.finalize()) != *hash {
                damaged.push(hash.clone());
            }
            p.done += 1;
            p.bytes = z.written();
            if last.elapsed().as_millis() >= 500 || p.done == p.total {
                last = std::time::Instant::now();
                if !progress(&p) {
                    anyhow::bail!("cancelled");
                }
            }
        }
        p.phase = "zip";
        let manifest = json!({
            "format": 1,
            "account": {"id": account, "handle": handle, "kind": kind},
            "created_at": now,
            "server_version": env!("CARGO_PKG_VERSION"),
            "instance_id": crate::db::meta(reader, "instance_id")?.unwrap_or_default(),
            "ops": {"count": op_count, "sha256": ops_sha, "file": "ops.jsonl"},
            "csv": exports::CSV_NAMES.iter().map(|n| format!("csv/{n}.csv")).collect::<Vec<_>>(),
            "blobs": blobs.iter().map(|(h, b)| json!({"hash": h, "size": b.size, "mime": b.mime,
                "filenames": b.filenames, "file": format!("blobs/{h}")})).collect::<Vec<_>>(),
            "missing": missing,
            "damaged": damaged,
        });
        let text = serde_json::to_vec_pretty(&manifest)?;
        z.add("manifest.json", &text[..], text.len() as u64, |_| {})?;
        let mut out = z.finish()?;
        out.flush()?;
        let file = out.into_inner().map_err(|e| e.into_error())?;
        file.sync_all()?;
        Ok(file.metadata()?.len())
    })();
    match result {
        Ok(size) => {
            fs::rename(&tmp, file(cfg, id))?;
            Ok((name, size))
        }
        Err(e) => {
            let _ = fs::remove_file(&tmp);
            Err(e)
        }
    }
}

fn blob_path(cfg: &Config, hash: &str) -> PathBuf {
    cfg.blob_dir().join(&hash[..2]).join(&hash[2..4]).join(hash)
}
