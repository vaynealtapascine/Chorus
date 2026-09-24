//! The operator commands run against a real on-disk SQLite database and immutable blob pool.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use chorus_server::db;
use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};

struct Fixture {
    root: PathBuf,
    hash: String,
}

impl Fixture {
    fn new() -> Self {
        let target = std::env::var_os("CARGO_TARGET_DIR").expect("test requires CARGO_TARGET_DIR on F:");
        let root = PathBuf::from(target).join(format!("backup-cli-test-{:016x}", rand::random::<u64>()));
        let data = root.join("data");
        fs::create_dir_all(&data).unwrap();
        let mut conn = db::open(&data.join("chorus.db")).unwrap();
        db::migrate(&mut conn).unwrap();
        db::set_meta(&conn, "epoch", "3").unwrap();
        conn.execute("INSERT INTO account(id,kind,handle,created_at) VALUES ('alice','person','alice',0)", []).unwrap();
        let body = b"immutable backup blob";
        let hash = format!("{:x}", Sha256::digest(body));
        let blob = blob_path(&data.join("blobs"), &hash);
        fs::create_dir_all(blob.parent().unwrap()).unwrap();
        fs::write(blob, body).unwrap();
        conn.execute(
            "INSERT INTO blob(hash,size,mime,stored_at,uploaded_by,received,is_complete)
             VALUES (?1,?2,'text/plain',0,'alice',?2,1)",
            params![hash, body.len() as i64],
        )
        .unwrap();
        drop(conn);
        let config = format!(
            "[server]\ndata_dir = '{}'\n[backup]\ndir = '{}'\n",
            toml_path(&data),
            toml_path(&root.join("backups"))
        );
        fs::write(root.join("chorus.toml"), config).unwrap();
        Self { root, hash }
    }

    fn run(&self, args: &[&str]) -> std::process::Output {
        Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .arg("--config")
            .arg(self.root.join("chorus.toml"))
            .args(args)
            .output()
            .unwrap()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.root).unwrap();
    }
}

fn toml_path(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}

fn blob_path(root: &Path, hash: &str) -> PathBuf {
    root.join(&hash[..2]).join(&hash[2..4]).join(hash)
}

#[test]
fn backup_and_restore_commands_verify_blobs_and_refuse_overwrite() {
    let fixture = Fixture::new();
    let backup = fixture.run(&["backup"]);
    assert!(backup.status.success(), "{}", String::from_utf8_lossy(&backup.stderr));
    let snapshot = PathBuf::from(String::from_utf8(backup.stdout).unwrap().trim());
    assert!(snapshot.join("manifest.json").is_file());
    assert!(snapshot.join("chorus.db").is_file());
    let saved_blob = blob_path(&fixture.root.join("backups/blobs"), &fixture.hash);
    assert_eq!(fs::read(&saved_blob).unwrap(), b"immutable backup blob");

    let destination = fixture.root.join("restored");
    let restore =
        fixture.run(&["restore", "--from", &snapshot.to_string_lossy(), "--into", &destination.to_string_lossy()]);
    assert!(restore.status.success(), "{}", String::from_utf8_lossy(&restore.stderr));
    let restored = Connection::open(destination.join("chorus.db")).unwrap();
    assert_eq!(db::meta(&restored, "epoch").unwrap().as_deref(), Some("4"));
    assert_eq!(db::meta(&restored, "restore_open").unwrap().as_deref(), Some("1"));
    assert_eq!(restored.query_row("SELECT count(*) FROM account", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
    assert_eq!(fs::read(blob_path(&destination.join("blobs"), &fixture.hash)).unwrap(), b"immutable backup blob");
    drop(restored);

    let again =
        fixture.run(&["restore", "--from", &snapshot.to_string_lossy(), "--into", &destination.to_string_lossy()]);
    assert!(!again.status.success());
    assert!(String::from_utf8_lossy(&again.stderr).contains("already exists"));

    fs::write(&saved_blob, b"corrupt").unwrap();
    let rejected = fixture.root.join("rejected");
    let corrupt =
        fixture.run(&["restore", "--from", &snapshot.to_string_lossy(), "--into", &rejected.to_string_lossy()]);
    assert!(!corrupt.status.success());
    assert!(String::from_utf8_lossy(&corrupt.stderr).contains("backup blob mismatch"));
    assert!(!rejected.exists(), "failed restore must leave the destination absent");
    assert!(
        !fs::read_dir(&fixture.root)
            .unwrap()
            .flatten()
            .any(|e| e.file_name().to_string_lossy().starts_with("chorus-restore-"))
    );

    // A valid checksum and healthy SQLite file still fail if materialized state cannot be
    // recreated from the append-only op log.
    fs::write(&saved_blob, b"immutable backup blob").unwrap();
    let snapshot_db = snapshot.join("chorus.db");
    let edited = Connection::open(&snapshot_db).unwrap();
    edited
        .execute("INSERT INTO pref(account_id,device_id,key,value,hlc) VALUES ('alice','','stray','true','1-0-0')", [])
        .unwrap();
    drop(edited);
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&fs::read(snapshot.join("manifest.json")).unwrap()).unwrap();
    manifest["database_sha256"] = format!("{:x}", Sha256::digest(fs::read(&snapshot_db).unwrap())).into();
    fs::write(snapshot.join("manifest.json"), serde_json::to_vec(&manifest).unwrap()).unwrap();
    let inconsistent = fixture.root.join("inconsistent");
    let bad_projection =
        fixture.run(&["restore", "--from", &snapshot.to_string_lossy(), "--into", &inconsistent.to_string_lossy()]);
    assert!(!bad_projection.status.success());
    assert!(String::from_utf8_lossy(&bad_projection.stderr).contains("projection mismatch"));
    assert!(!inconsistent.exists());
}

/// Restore rebuilds the snapshot and compares it, but not what depends on when projections ran:
/// daily totals (open intervals are cut at "now"), when review cards were made, and the read
/// state's running parts, which rows from before migration 0004 don't have yet.
#[test]
fn restore_accepts_time_dependent_and_pre_0004_projections() {
    let fixture = Fixture::new();
    {
        let conn = db::open(&fixture.root.join("data/chorus.db")).unwrap();
        let sys = "0192f8c2-0000-7000-8000-0000000000a1";
        let (acct, home) = (format!("account:{sys}"), "space:0192f8c2-0000-7000-8000-0000000000d1".to_string());
        conn.execute("INSERT INTO account(id,kind,handle,created_at) VALUES (?1,'system','sys',0)", [sys]).unwrap();
        chorus_server::ingest::grant(&conn, sys, &acct).unwrap();
        chorus_server::ingest::grant(&conn, sys, &home).unwrap();
        let t = chorus_server::now_ms() - 3 * 86_400_000;
        let kai = "0192f8c2-0000-7000-8000-00000000000a";
        chorus_server::ingest::server_op(
            &conn,
            sys,
            "member.create",
            &acct,
            Some(kai),
            serde_json::json!({"name": "Kai"}),
            t,
        )
        .unwrap();
        let sw = "0192f8c2-0000-7000-8000-00000000000b";
        let entries = serde_json::json!({"entries": [{"subject_type": "member", "subject_id": kai, "level": "front"}]});
        chorus_server::ingest::server_op(&conn, sys, "front.switch", &acct, Some(sw), entries, t).unwrap();
        chorus_server::ingest::server_op(
            &conn,
            sys,
            "read.mark",
            &home,
            None,
            serde_json::json!({"channel_id": "c", "message_id": "m", "message_at": 5, "reader_member_id": ""}),
            t,
        )
        .unwrap();
        // as if computed at another time, and written before migration 0004
        assert!(conn.execute("UPDATE front_daily SET seconds = seconds + 7", []).unwrap() > 0);
        let stale = "UPDATE read_state SET state_ok = 0, mark_at = NULL, mark_id = NULL, mark_hlc = NULL";
        assert_eq!(conn.execute(stale, []).unwrap(), 1);
    }
    let backup = fixture.run(&["backup"]);
    assert!(backup.status.success(), "{}", String::from_utf8_lossy(&backup.stderr));
    let snapshot = String::from_utf8(backup.stdout).unwrap().trim().to_string();
    let into = fixture.root.join("restored");
    let restored = fixture.run(&["restore", "--from", &snapshot, "--into", &into.to_string_lossy()]);
    assert!(restored.status.success(), "{}", String::from_utf8_lossy(&restored.stderr));
}

/// A snapshot taken by an older build (here: before migration 0004) restores with this one: the
/// restored copy is migrated before it's verified. The snapshot itself stays as it was.
#[test]
fn restore_migrates_a_snapshot_from_an_older_schema() {
    let fixture = Fixture::new();
    {
        let conn = db::open(&fixture.root.join("data/chorus.db")).unwrap();
        conn.execute_batch(
            "DROP INDEX fi_open; DROP INDEX fi_account_end;
             ALTER TABLE read_state DROP COLUMN mark_at; ALTER TABLE read_state DROP COLUMN mark_id;
             ALTER TABLE read_state DROP COLUMN mark_hlc; ALTER TABLE read_state DROP COLUMN set_at;
             ALTER TABLE read_state DROP COLUMN set_id; ALTER TABLE read_state DROP COLUMN set_hlc;
             ALTER TABLE read_state DROP COLUMN state_ok;",
        )
        .unwrap();
        db::set_meta(&conn, "schema_version", "3").unwrap();
    }
    let backup = fixture.run(&["backup"]);
    assert!(backup.status.success(), "{}", String::from_utf8_lossy(&backup.stderr));
    let snapshot = PathBuf::from(String::from_utf8(backup.stdout).unwrap().trim());
    let into = fixture.root.join("restored");
    let restored = fixture.run(&["restore", "--from", &snapshot.to_string_lossy(), "--into", &into.to_string_lossy()]);
    assert!(restored.status.success(), "{}", String::from_utf8_lossy(&restored.stderr));
    let conn = Connection::open(into.join("chorus.db")).unwrap();
    assert_eq!(db::schema_version(&conn).unwrap(), db::MIGRATIONS.len());
    let old = Connection::open(snapshot.join("chorus.db")).unwrap();
    assert_eq!(db::schema_version(&old).unwrap(), 3, "the snapshot itself is untouched");
}
