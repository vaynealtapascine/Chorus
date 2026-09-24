//! Online SQLite snapshots, immutable blob copies, retention and non-overwriting restore.

use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, ensure};
use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{config::Config, db, now_ms, project};

#[derive(Debug, Serialize, Deserialize)]
struct BlobEntry {
    hash: String,
    size: u64,
}

#[derive(Debug, Serialize, Deserialize)]
struct Manifest {
    format: u32,
    created_at_ms: i64,
    database_sha256: String,
    blobs: Vec<BlobEntry>,
}

fn digest(path: &Path) -> anyhow::Result<(String, u64)> {
    let mut file = File::open(path).with_context(|| format!("opening {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut size = 0u64;
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        size += n as u64;
    }
    Ok((format!("{:x}", hasher.finalize()), size))
}

fn blob_path(root: &Path, hash: &str) -> anyhow::Result<PathBuf> {
    ensure!(
        hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()),
        "invalid blob hash in manifest"
    );
    Ok(root.join(&hash[..2]).join(&hash[2..4]).join(hash))
}

fn local_stamp() -> anyhow::Result<(String, String)> {
    let conn = Connection::open_in_memory()?;
    Ok(conn.query_row(
        "SELECT strftime('%Y%m%d-%H%M%S','now','localtime'), strftime('%H:%M','now','localtime')",
        [],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?)
}

fn safe_remove_snapshot(root: &Path, snapshot: &Path) -> anyhow::Result<()> {
    let root = root.canonicalize()?;
    let snapshot = snapshot.canonicalize()?;
    ensure!(
        snapshot.parent() == Some(root.as_path())
            && snapshot.file_name().is_some_and(|n| n.to_string_lossy().starts_with("chorus-")),
        "refusing to remove snapshot outside backup directory"
    );
    fs::remove_dir_all(snapshot)?;
    Ok(())
}

/// Create one self-describing snapshot directory inside `to` (or configured `backup.dir`).
/// The DB copy is made through SQLite's online backup API; blobs are copied by content hash.
pub fn create(cfg: &Config, to: Option<&Path>) -> anyhow::Result<PathBuf> {
    ensure!(cfg.db_path().is_file(), "database does not exist: {}", cfg.db_path().display());
    let root = to.map(Path::to_path_buf).unwrap_or_else(|| cfg.backup_dir());
    fs::create_dir_all(&root)?;
    let (stamp, _) = local_stamp()?;
    let snapshot = root.join(format!("chorus-{stamp}-{:08x}", rand::random::<u32>()));
    fs::create_dir(&snapshot)?;
    let result = (|| -> anyhow::Result<()> {
        let source = db::open(&cfg.db_path())?;
        let database = snapshot.join("chorus.db");
        db::backup_to(&source, &database)?;
        let (database_sha256, _) = digest(&database)?;
        let copied = Connection::open_with_flags(&database, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        let mut stmt = copied.prepare("SELECT hash, size FROM blob WHERE is_complete = 1 ORDER BY hash")?;
        let rows = stmt.query_map([], |r| Ok(BlobEntry { hash: r.get(0)?, size: r.get::<_, i64>(1)? as u64 }))?;
        let mut blobs = Vec::new();
        for row in rows {
            let entry = row?;
            let source = blob_path(&cfg.blob_dir(), &entry.hash)?;
            let target = blob_path(&root.join("blobs"), &entry.hash)?;
            let (actual_hash, actual_size) = digest(&source)?;
            ensure!(actual_hash == entry.hash && actual_size == entry.size, "source blob mismatch: {}", entry.hash);
            if target.exists() {
                let (existing_hash, existing_size) = digest(&target)?;
                ensure!(
                    existing_hash == entry.hash && existing_size == entry.size,
                    "backup blob mismatch: {}",
                    entry.hash
                );
            } else {
                fs::create_dir_all(target.parent().unwrap())?;
                let part = target.with_extension(format!("part-{:08x}", rand::random::<u32>()));
                fs::copy(&source, &part)?;
                let (copied_hash, copied_size) = digest(&part)?;
                ensure!(
                    copied_hash == entry.hash && copied_size == entry.size,
                    "blob changed during backup: {}",
                    entry.hash
                );
                fs::rename(part, target)?;
            }
            blobs.push(entry);
        }
        let manifest = Manifest { format: 1, created_at_ms: now_ms(), database_sha256, blobs };
        let mut file = File::create(snapshot.join("manifest.json"))?;
        file.write_all(&serde_json::to_vec_pretty(&manifest)?)?;
        file.sync_all()?;
        Ok(())
    })();
    if let Err(error) = result {
        safe_remove_snapshot(&root, &snapshot)?;
        return Err(error);
    }
    Ok(snapshot)
}

fn verify_rebuild(database: &Path) -> anyhow::Result<()> {
    let verify = database.with_extension("verify.db");
    let result = (|| -> anyhow::Result<()> {
        let source = Connection::open_with_flags(database, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        db::backup_to(&source, &verify)?;
        let mut rebuilt = db::open(&verify)?;
        project::rebuild(&mut rebuilt)?;
        rebuilt.execute("ATTACH DATABASE ?1 AS source", [database.to_string_lossy().as_ref()])?;
        for table in project::DERIVED {
            // What a rebuild must reproduce exactly. Daily totals cut open intervals at "now" and
            // review cards stamp when they were made, so those depend on when they ran; the read
            // state's running parts are bookkeeping (migration 0004), not the position itself.
            let cols = match *table {
                "front_daily" => continue,
                "front_review" => "id, account_id, switch_a, switch_b, resolution, resolved_at",
                "read_state" => "channel_id, account_id, reader_member_id, last_read_message_id, last_read_message_at",
                _ => "*",
            };
            let differs: bool = rebuilt.query_row(
                &format!(
                    "SELECT EXISTS(SELECT {cols} FROM main.{table} EXCEPT SELECT {cols} FROM source.{table}) \
                          OR EXISTS(SELECT {cols} FROM source.{table} EXCEPT SELECT {cols} FROM main.{table})"
                ),
                [],
                |r| r.get(0),
            )?;
            ensure!(!differs, "projection mismatch after rebuild: {table}");
        }
        drop(rebuilt);
        Ok(())
    })();
    if verify.exists() {
        fs::remove_file(&verify)?;
    }
    result
}

/// Restore into a new directory. A failed verification leaves `into` untouched.
pub fn restore(from: &Path, into: &Path) -> anyhow::Result<()> {
    ensure!(from.is_dir(), "backup directory does not exist: {}", from.display());
    ensure!(!into.exists(), "restore destination already exists: {}", into.display());
    let parent = into.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent)?;
    let staging = parent.join(format!("chorus-restore-{:08x}", rand::random::<u32>()));
    fs::create_dir(&staging)?;
    let result = (|| -> anyhow::Result<()> {
        let manifest: Manifest = serde_json::from_slice(&fs::read(from.join("manifest.json"))?)?;
        ensure!(manifest.format == 1, "unsupported backup manifest format");
        let source_db = from.join("chorus.db");
        ensure!(digest(&source_db)?.0 == manifest.database_sha256, "database checksum mismatch");
        let database = staging.join("chorus.db");
        fs::copy(&source_db, &database)?;
        for entry in &manifest.blobs {
            let source = blob_path(&from.parent().unwrap_or(from).join("blobs"), &entry.hash)?;
            let target = blob_path(&staging.join("blobs"), &entry.hash)?;
            let (hash, size) = digest(&source)?;
            ensure!(hash == entry.hash && size == entry.size, "backup blob mismatch: {}", entry.hash);
            fs::create_dir_all(target.parent().unwrap())?;
            fs::copy(source, target)?;
        }
        let mut conn = db::open(&database)?;
        let integrity: String = conn.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
        ensure!(integrity == "ok", "SQLite integrity check failed: {integrity}");
        // a snapshot from an older build: bring the copy (never the snapshot) to this schema
        // first, so the rebuild below and the server that opens it agree on the tables
        db::migrate(&mut conn)?;
        verify_rebuild(&database)?;
        let epoch = db::meta(&conn, "epoch")?.and_then(|s| s.parse::<u64>().ok()).unwrap_or(1) + 1;
        db::set_meta(&conn, "epoch", &epoch.to_string())?;
        crate::reconcile::start(&conn, crate::now_ms())?;
        drop(conn);
        ensure!(!into.exists(), "restore destination appeared during verification");
        fs::rename(&staging, into)?;
        Ok(())
    })();
    if let Err(error) = result {
        safe_remove_snapshot(parent, &staging)?;
        return Err(error);
    }
    Ok(())
}

fn valid_time(time: &str) -> bool {
    let Some((hour, minute)) = time.split_once(':') else { return false };
    hour.len() == 2
        && minute.len() == 2
        && hour.parse::<u8>().is_ok_and(|h| h < 24)
        && minute.parse::<u8>().is_ok_and(|m| m < 60)
}

fn day_number(date: &str) -> Option<i64> {
    if date.len() != 8 {
        return None;
    }
    let y: i64 = date[..4].parse().ok()?;
    let m: i64 = date[4..6].parse().ok()?;
    let d: i64 = date[6..8].parse().ok()?;
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    let y = y - i64::from(m <= 2);
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = m + if m > 2 { -3 } else { 9 };
    let doy = (153 * mp + 2) / 5 + d - 1;
    Some(era * 146097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468)
}

fn snapshots(root: &Path) -> anyhow::Result<Vec<PathBuf>> {
    if !root.exists() {
        return Ok(Vec::new());
    }
    let mut found: Vec<PathBuf> = fs::read_dir(root)?
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| {
            p.is_dir()
                && p.file_name().is_some_and(|n| n.to_string_lossy().starts_with("chorus-"))
                && p.join("manifest.json").is_file()
        })
        .collect();
    found.sort_by(|a, b| b.file_name().cmp(&a.file_name()));
    Ok(found)
}

/// Keep the newest snapshot for each day, Monday-based week and month up to each configured count.
pub fn rotate(cfg: &Config) -> anyhow::Result<()> {
    let root = cfg.backup_dir();
    let mut days = HashSet::new();
    let mut weeks = HashSet::new();
    let mut months = HashSet::new();
    for path in snapshots(&root)? {
        let name = path.file_name().unwrap().to_string_lossy();
        let date = name.get(7..15).unwrap_or("");
        let Some(day) = day_number(date) else { continue };
        let week = (day + 3).div_euclid(7);
        let month = &date[..6];
        let daily = !days.contains(date) && days.len() < cfg.backup.keep_daily.max(1) as usize;
        let weekly = !weeks.contains(&week) && weeks.len() < cfg.backup.keep_weekly as usize;
        let monthly = !months.contains(month) && months.len() < cfg.backup.keep_monthly as usize;
        if daily || weekly || monthly {
            days.insert(date.to_string());
            weeks.insert(week);
            months.insert(month.to_string());
        } else {
            safe_remove_snapshot(&root, &path)?;
        }
    }
    Ok(())
}

/// Check once a minute; missed backup time is caught up when `serve` starts later that day.
pub fn start_nightly(cfg: Config) -> anyhow::Result<()> {
    ensure!(valid_time(&cfg.backup.time), "backup.time must be HH:MM");
    tokio::spawn(async move {
        loop {
            let cfg = cfg.clone();
            let error_cfg = cfg.clone();
            if let Err(error) = tokio::task::spawn_blocking(move || {
                let (stamp, time) = local_stamp()?;
                let date = &stamp[..8];
                if time >= cfg.backup.time
                    && !snapshots(&cfg.backup_dir())?.iter().any(|p| {
                        p.file_name().is_some_and(|n| n.to_string_lossy().starts_with(&format!("chorus-{date}")))
                    })
                {
                    let snapshot = create(&cfg, None)?;
                    tracing::info!(backup = %snapshot.display(), "nightly backup complete");
                    rotate(&cfg)?;
                }
                Ok::<_, anyhow::Error>(())
            })
            .await
            .unwrap_or_else(|e| Err(e.into()))
            {
                tracing::error!(error = %error, "nightly backup failed");
                if let Ok(conn) = db::open(&error_cfg.db_path()) {
                    let detail = serde_json::json!({"source":"backup","at":now_ms(),"message":error.to_string()});
                    let _ = db::set_meta(&conn, "last_error", &detail.to_string());
                }
            }
            tokio::time::sleep(Duration::from_secs(60)).await;
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rotation_keeps_configured_daily_count_and_never_touches_blob_pool() {
        // unit tests get no CARGO_TARGET_TMPDIR; the owner's C: is nearly full, so prefer the target dir
        let target = std::env::var_os("CARGO_TARGET_DIR").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
        let root = target.join(format!("backup-rotation-test-{:016x}", rand::random::<u64>()));
        fs::create_dir_all(root.join("blobs")).unwrap();
        fs::write(root.join("blobs/keep"), b"immutable").unwrap();
        for day in 1..=4 {
            let snapshot = root.join(format!("chorus-202601{day:02}-040000-00000001"));
            fs::create_dir(&snapshot).unwrap();
            fs::write(snapshot.join("manifest.json"), b"{}").unwrap();
        }
        let mut cfg = Config::default();
        cfg.backup.dir = Some(root.clone());
        cfg.backup.keep_daily = 2;
        cfg.backup.keep_weekly = 0;
        cfg.backup.keep_monthly = 0;
        rotate(&cfg).unwrap();
        assert!(!root.join("chorus-20260101-040000-00000001").exists());
        assert!(!root.join("chorus-20260102-040000-00000001").exists());
        assert!(root.join("chorus-20260103-040000-00000001").exists());
        assert!(root.join("chorus-20260104-040000-00000001").exists());
        assert_eq!(fs::read(root.join("blobs/keep")).unwrap(), b"immutable");
        fs::remove_dir_all(root).unwrap();
    }
}
