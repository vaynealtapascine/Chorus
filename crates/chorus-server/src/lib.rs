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
pub mod feeds;
pub mod follows;
pub mod health;
pub mod home;
pub mod home_install;
pub mod ingest;
pub mod messages;
pub mod notifier;
pub mod oplog;
pub mod posts;
pub mod project;
pub mod purge;
pub mod push;
pub mod qr;
pub mod ratelimit;
pub mod reconcile;
pub mod search;
pub mod seed;
pub mod spaces;
pub mod tls;
pub mod visibility;
pub mod webhooks;

use rusqlite::Connection;

/// Open the database, backing it up before any pending migration (docs/OPS.md §5).
/// Run the server from `config` until stopped, starting again whenever Chorus Home's settings
/// page changed the file (home.rs). What the Windows service runs (home_install.rs).
pub fn serve_until_stopped(config: &std::path::Path) -> anyhow::Result<()> {
    loop {
        let cfg = config::Config::load(Some(config))?;
        let conn = open_and_migrate(&cfg)?;
        auth::backfill_self_members(&conn, now_ms())?;
        let state = app::Shared::new(conn, cfg)?;
        // a fresh runtime each time: dropping it ends the last run's background tasks
        tokio::runtime::Runtime::new()?.block_on(app::serve(state))?;
        if !home::take_restart() {
            return Ok(());
        }
    }
}

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
