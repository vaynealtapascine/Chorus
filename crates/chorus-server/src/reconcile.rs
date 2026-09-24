//! The restore window (SYNC.md §7.3, D-067). A restore opens it: while open, ops re-pushed with
//! `restore: true` keep their original author, device and times, so devices can hand back what
//! the backup missed. That trust is also a way to forge authorship, so the window is short: an
//! admin closes it once `reconcile-status` shows every device back, and it closes by itself
//! [`WINDOW_MS`] after the restore at the latest.
//!
//! A device counts as back when it says hello with the new epoch and an empty outbox: it has
//! been through reconcile (its restore pushes are acknowledged) and holds nothing unsent.

use rusqlite::{Connection, params};

use crate::db;

/// How long a restore window stays open unless closed sooner.
pub const WINDOW_MS: i64 = 7 * 86_400_000;

/// Open the window (called by a restore, on the restored copy).
pub fn start(conn: &Connection, now: i64) -> anyhow::Result<()> {
    conn.execute("DELETE FROM server_meta WHERE key LIKE 'reconciled:%'", [])?;
    db::set_meta(conn, "restore_open", "1")?;
    db::set_meta(conn, "restore_at", &now.to_string())?;
    Ok(())
}

/// When the open window closes by itself; `None` when it's closed.
pub fn closes_at(conn: &Connection, now: i64) -> anyhow::Result<Option<i64>> {
    if db::meta(conn, "restore_open")?.as_deref() != Some("1") {
        return Ok(None);
    }
    let at = match db::meta(conn, "restore_at")?.and_then(|s| s.parse::<i64>().ok()) {
        Some(at) => at,
        None => {
            // opened before restores were timed: the clock starts now
            db::set_meta(conn, "restore_at", &now.to_string())?;
            now
        }
    };
    Ok(Some(at + WINDOW_MS).filter(|end| now < *end))
}

pub fn open(conn: &Connection, now: i64) -> anyhow::Result<bool> {
    Ok(closes_at(conn, now)?.is_some())
}

pub fn close(conn: &Connection) -> anyhow::Result<()> {
    db::set_meta(conn, "restore_open", "0")?;
    conn.execute("DELETE FROM server_meta WHERE key LIKE 'reconciled:%'", [])?;
    Ok(())
}

/// A device said hello with the current epoch; `outbox` is what it still holds unsent.
pub fn hello(conn: &Connection, device: &str, outbox: usize, now: i64) -> anyhow::Result<()> {
    if outbox == 0 && open(conn, now)? {
        conn.execute(
            "INSERT OR IGNORE INTO server_meta(key, value) VALUES (?1, ?2)",
            params![format!("reconciled:{device}"), now.to_string()],
        )?;
    }
    Ok(())
}

pub struct DeviceStatus {
    pub account: String,
    pub name: String,
    pub platform: String,
    pub last_seen_at: Option<i64>,
    pub back_at: Option<i64>,
}

/// Every signed-in device (API tokens aren't devices that hold data), and whether it's back.
pub fn devices(conn: &Connection) -> anyhow::Result<Vec<DeviceStatus>> {
    let mut st = conn.prepare(
        "SELECT coalesce(a.handle, a.id), d.name, d.platform, d.last_seen_at,
                (SELECT CAST(value AS INTEGER) FROM server_meta WHERE key = 'reconciled:' || d.id)
         FROM device d JOIN account a ON a.id = d.account_id
         WHERE d.revoked_at IS NULL AND d.platform <> 'token'
         ORDER BY 1, d.name",
    )?;
    let rows = st.query_map([], |r| {
        Ok(DeviceStatus {
            account: r.get(0)?,
            name: r.get(1)?,
            platform: r.get(2)?,
            last_seen_at: r.get(3)?,
            back_at: r.get(4)?,
        })
    })?;
    Ok(rows.collect::<Result<_, _>>()?)
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_790_000_000_000;

    fn db() -> Connection {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        c.execute("INSERT INTO account(id, kind, handle, created_at) VALUES ('a', 'system', 'stars', 0)", []).unwrap();
        for (id, platform) in [("phone", "android"), ("web", "web"), ("bot", "token")] {
            c.execute(
                "INSERT INTO device(id, account_id, short_id, name, platform, public_key, created_at)
                 VALUES (?1, 'a', ?1, ?1, ?2, 'k', 0)",
                [id, platform],
            )
            .unwrap();
        }
        c
    }

    #[test]
    fn devices_come_back_once_their_outbox_is_empty() {
        let c = db();
        assert!(!open(&c, NOW).unwrap());
        start(&c, NOW).unwrap();
        assert!(open(&c, NOW).unwrap());
        hello(&c, "phone", 3, NOW + 1).unwrap(); // still handing over
        hello(&c, "web", 0, NOW + 2).unwrap();
        let back: Vec<(String, Option<i64>)> = devices(&c).unwrap().into_iter().map(|d| (d.name, d.back_at)).collect();
        assert_eq!(back, [("phone".to_string(), None), ("web".to_string(), Some(NOW + 2))], "tokens aren't listed");
        hello(&c, "phone", 0, NOW + 3).unwrap();
        assert!(devices(&c).unwrap().iter().all(|d| d.back_at.is_some()));
        close(&c).unwrap();
        assert!(!open(&c, NOW + 4).unwrap());
        assert!(devices(&c).unwrap().iter().all(|d| d.back_at.is_none()));
    }

    #[test]
    fn the_window_closes_by_itself() {
        let c = db();
        start(&c, NOW).unwrap();
        assert_eq!(closes_at(&c, NOW).unwrap(), Some(NOW + WINDOW_MS));
        assert!(open(&c, NOW + WINDOW_MS - 1).unwrap());
        assert!(!open(&c, NOW + WINDOW_MS).unwrap());
        // a window opened before restores were timed starts its clock at the first look
        db::set_meta(&c, "restore_at", "x").unwrap();
        assert_eq!(closes_at(&c, NOW + WINDOW_MS).unwrap(), Some(NOW + 2 * WINDOW_MS));
    }
}
