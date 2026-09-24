//! SQLite: open with the documented pragmas and apply migrations (docs/DATA_MODEL.md §1).

use std::path::Path;

use anyhow::Context;
use rusqlite::{Connection, OptionalExtension, params};

/// Forward-only migrations, applied in order. Never edit one that has shipped; add a new one.
pub const MIGRATIONS: &[(&str, &str)] = &[
    ("0001_init", include_str!("../migrations/0001_init.sql")),
    ("0002_push_keys", include_str!("../migrations/0002_push_keys.sql")),
    ("0003_follower_history", include_str!("../migrations/0003_follower_history.sql")),
    ("0004_incremental_projections", include_str!("../migrations/0004_incremental_projections.sql")),
    ("0005_op_message_ref", include_str!("../migrations/0005_op_message_ref.sql")),
    ("0006_message_reply_to", include_str!("../migrations/0006_message_reply_to.sql")),
];

pub fn open(path: &Path) -> anyhow::Result<Connection> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let conn = Connection::open(path).with_context(|| format!("opening {}", path.display()))?;
    pragmas(&conn)?;
    Ok(conn)
}

pub fn open_memory() -> anyhow::Result<Connection> {
    let conn = Connection::open_in_memory()?;
    pragmas(&conn)?;
    Ok(conn)
}

fn pragmas(conn: &Connection) -> anyhow::Result<()> {
    // projections use a few dozen statement shapes per op mix; keep them all prepared
    conn.set_prepared_statement_cache_capacity(256);
    conn.execute_batch(
        "PRAGMA journal_mode = WAL;
         PRAGMA synchronous = NORMAL;
         PRAGMA foreign_keys = ON;
         PRAGMA busy_timeout = 5000;
         PRAGMA temp_store = MEMORY;
         PRAGMA mmap_size = 268435456;",
    )?;
    Ok(())
}

pub fn schema_version(conn: &Connection) -> anyhow::Result<usize> {
    let has_meta: bool = conn
        .query_row("SELECT 1 FROM sqlite_master WHERE type='table' AND name='server_meta'", [], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if !has_meta {
        return Ok(0);
    }
    let v: Option<String> =
        conn.query_row("SELECT value FROM server_meta WHERE key='schema_version'", [], |r| r.get(0)).optional()?;
    Ok(v.and_then(|v| v.parse().ok()).unwrap_or(0))
}

pub fn pending_migrations(conn: &Connection) -> anyhow::Result<usize> {
    Ok(MIGRATIONS.len().saturating_sub(schema_version(conn)?))
}

/// Apply pending migrations, each in its own transaction. The caller backs up first when the
/// database already holds data (OPS.md §5).
pub fn migrate(conn: &mut Connection) -> anyhow::Result<usize> {
    let from = schema_version(conn)?;
    for (i, (name, sql)) in MIGRATIONS.iter().enumerate().skip(from) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql).with_context(|| format!("migration {name}"))?;
        tx.execute(
            "INSERT INTO server_meta(key, value) VALUES ('schema_version', ?1)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![(i + 1).to_string()],
        )?;
        tx.commit()?;
        tracing::info!(migration = name, "applied");
    }
    Ok(MIGRATIONS.len() - from)
}

pub fn meta(conn: &Connection, key: &str) -> anyhow::Result<Option<String>> {
    Ok(conn.query_row("SELECT value FROM server_meta WHERE key = ?1", [key], |r| r.get(0)).optional()?)
}

pub fn set_meta(conn: &Connection, key: &str, value: &str) -> anyhow::Result<()> {
    conn.execute(
        "INSERT INTO server_meta(key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

/// Consistent online copy (used for backups and pre-migration safety).
pub fn backup_to(conn: &Connection, dest: &Path) -> anyhow::Result<()> {
    if let Some(dir) = dest.parent() {
        std::fs::create_dir_all(dir)?;
    }
    anyhow::ensure!(!dest.exists(), "backup destination already exists: {}", dest.display());
    let mut target = Connection::open(dest)?;
    let backup = rusqlite::backup::Backup::new(conn, &mut target)?;
    let result = backup.run_to_completion(100, std::time::Duration::from_millis(25), None);
    drop(backup);
    drop(target);
    if let Err(error) = result {
        let _ = std::fs::remove_file(dest);
        return Err(error.into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrates_fresh_and_is_idempotent() {
        let mut c = open_memory().unwrap();
        assert_eq!(schema_version(&c).unwrap(), 0);
        assert_eq!(migrate(&mut c).unwrap(), MIGRATIONS.len());
        assert_eq!(schema_version(&c).unwrap(), MIGRATIONS.len());
        assert_eq!(migrate(&mut c).unwrap(), 0);
        // generated columns and FTS exist
        c.execute("INSERT INTO group_membership(group_id, member_id, added_hlc) VALUES ('g','m','a')", []).unwrap();
        let present: i64 = c.query_row("SELECT is_present FROM group_membership", [], |r| r.get(0)).unwrap();
        assert_eq!(present, 1);
    }

    #[test]
    fn backup_copies_data() {
        let dir = std::env::temp_dir().join(format!("chorus-db-test-{}", std::process::id()));
        let mut c = open(&dir.join("a.db")).unwrap();
        migrate(&mut c).unwrap();
        set_meta(&c, "instance_id", "x").unwrap();
        backup_to(&c, &dir.join("b.db")).unwrap();
        let b = open(&dir.join("b.db")).unwrap();
        assert_eq!(meta(&b, "instance_id").unwrap().as_deref(), Some("x"));
        drop((c, b));
        let _ = std::fs::remove_dir_all(dir);
    }
}
