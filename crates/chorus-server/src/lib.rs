//! Chorus server: op log, projections, sync, API (docs/SYNC.md, docs/API.md).

pub mod activity;
pub mod api_data;
pub mod api_reads;
pub mod api_writes;
pub mod app;
pub mod auth;
pub mod backup;
pub mod blobs;
pub mod config;
pub mod db;
pub mod exports;
pub mod follows;
pub mod ingest;
pub mod notifier;
pub mod oplog;
pub mod posts;
pub mod project;
pub mod push;
pub mod qr;
pub mod search;
pub mod spaces;
pub mod visibility;
pub mod webhooks;

use rusqlite::Connection;

/// Open the database, backing it up before any pending migration (docs/OPS.md §5).
pub fn open_and_migrate(cfg: &config::Config) -> anyhow::Result<Connection> {
    let path = cfg.db_path();
    let existed = path.exists();
    let mut conn = db::open(&path)?;
    if existed && db::schema_version(&conn)? > 0 && db::pending_migrations(&conn)? > 0 {
        let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_millis();
        let dest = cfg.backup_dir().join(format!("pre-migrate-{stamp}.db"));
        db::backup_to(&conn, &dest)?;
        tracing::info!(backup = %dest.display(), "backed up before migrating");
    }
    db::migrate(&mut conn)?;
    if db::meta(&conn, "instance_id")?.is_none() {
        let id = chorus_core::id::new_id(now_ms() as u64, rand::random());
        db::set_meta(&conn, "instance_id", &id)?;
        db::set_meta(&conn, "epoch", "1")?;
    }
    Ok(conn)
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}
