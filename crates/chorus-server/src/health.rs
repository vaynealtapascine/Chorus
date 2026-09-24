//! Admin-only operational snapshot. File sizes and backup metadata come from the configured
//! data directory; database counts come from the already-open SQLite connection.

use std::fs;

use rusqlite::Connection;
use serde_json::{Value, json};

use crate::{config::Config, db};

fn latest_backup(cfg: &Config) -> anyhow::Result<Option<Value>> {
    let root = cfg.backup_dir();
    if !root.exists() {
        return Ok(None);
    }
    let mut latest: Option<(i64, Value)> = None;
    for entry in fs::read_dir(root)? {
        let path = entry?.path();
        if !path.is_dir() || !path.file_name().is_some_and(|n| n.to_string_lossy().starts_with("chorus-")) {
            continue;
        }
        let Ok(bytes) = fs::read(path.join("manifest.json")) else { continue };
        let Ok(manifest) = serde_json::from_slice::<Value>(&bytes) else { continue };
        let Some(at) = manifest["created_at_ms"].as_i64() else { continue };
        let Ok(database) = fs::metadata(path.join("chorus.db")) else { continue };
        let db_bytes = database.len();
        let blob_bytes: u64 =
            manifest["blobs"].as_array().into_iter().flatten().filter_map(|item| item["size"].as_u64()).sum();
        let value = json!({"at": at, "size_bytes": db_bytes + blob_bytes});
        if latest.as_ref().is_none_or(|(old, _)| at > *old) {
            latest = Some((at, value));
        }
    }
    Ok(latest.map(|(_, value)| value))
}

/// The restore window (SYNC.md §7.3, D-067): open until it's closed or times out, and which
/// signed-in devices have come back since the restore. Closed, it lists no devices.
pub fn restore_window(conn: &Connection, now: i64) -> anyhow::Result<Value> {
    let Some(closes_at) = crate::reconcile::closes_at(conn, now)? else {
        return Ok(json!({"open": false, "closes_at": null, "devices": []}));
    };
    let devices: Vec<Value> = crate::reconcile::devices(conn)?
        .into_iter()
        .map(|d| {
            json!({
                "account": d.account,
                "name": d.name,
                "platform": d.platform,
                "last_seen_at": d.last_seen_at,
                "back_at": d.back_at,
            })
        })
        .collect();
    Ok(json!({"open": true, "closes_at": closes_at, "devices": devices}))
}

pub fn snapshot(conn: &Connection, cfg: &Config, connected_devices: usize) -> anyhow::Result<Value> {
    let db_path = cfg.db_path();
    let db_bytes = fs::metadata(&db_path).map(|m| m.len()).unwrap_or(0);
    let wal_bytes = fs::metadata(db_path.with_extension("db-wal")).map(|m| m.len()).unwrap_or(0);
    let op_count: i64 = conn.query_row("SELECT count(*) FROM op WHERE status='applied'", [], |r| r.get(0))?;
    let pending_notifications: i64 = conn.query_row(
        "SELECT count(*) FROM notification WHERE delivered_at IS NULL AND cancelled_at IS NULL",
        [],
        |r| r.get(0),
    )?;
    let last_error = db::meta(conn, "last_error")?.and_then(|raw| serde_json::from_str::<Value>(&raw).ok());
    Ok(json!({
        "db_bytes": db_bytes,
        "wal_bytes": wal_bytes,
        "op_count": op_count,
        "connected_devices": connected_devices,
        "pending_notifications": pending_notifications,
        "last_backup": latest_backup(cfg)?,
        "last_error": last_error,
        "ntfy_configured": cfg.push.ntfy_url.is_some(),
        "restore_window": restore_window(conn, crate::now_ms())?,
    }))
}
