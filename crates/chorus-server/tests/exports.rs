//! Export endpoints use the same session/API-token principal as other data reads.

use chorus_core::hlc::Hlc;
use chorus_server::{api_data, app, auth, config::Config, db};
use rusqlite::{Connection, params};
use std::process::Command;
mod common;

const ALICE: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const BOB: &str = "0192f8c2-0000-7000-8000-0000000000b2";

fn seed() -> Connection {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let now = chorus_server::now_ms();
    for (account, label, secret, seed) in
        [(ALICE, "alice", "Alice, \"own\"", 1u8), (BOB, "bob", "Bob private secret", 2u8)]
    {
        let member = chorus_core::id::new_id(seed as u64, [seed; 10]);
        let op = chorus_core::id::new_id(seed as u64 + 10, [seed + 10; 10]);
        let device = chorus_core::id::new_id(seed as u64 + 20, [seed + 20; 10]);
        conn.execute("INSERT INTO account(id,kind,handle,created_at) VALUES (?1,'person',?1,0)", [account]).unwrap();
        conn.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at)
             VALUES (?1,?2,?1,?1,'cli','test',0)",
            params![device, account],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
            params![auth::hash(&format!("{label}-session")), device, now, now + 86_400_000],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO member(id,account_id,name,created_at) VALUES (?1,?2,?3,0)",
            params![member, account, secret],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO op(id,scope,kind,entity_id,payload,v,hlc,account_id,device_id,
              occurred_at,device_at,tz_offset_min,seen_seq,received_at)
             VALUES (?1,?2,'member.create',?3,?4,1,?5,?6,?7,?8,?8,0,0,?8)",
            params![
                op,
                format!("account:{account}"),
                member,
                serde_json::json!({"name": secret}).to_string(),
                Hlc::new(now as u64, 0, 1).to_string(),
                account,
                device,
                now,
            ],
        )
        .unwrap();
    }
    conn
}

#[tokio::test]
async fn exports_exclude_other_accounts_and_require_export_scope() {
    let conn = seed();
    let export_token = api_data::create_token(
        &conn,
        &api_data::Principal::owner(ALICE),
        "export",
        &["export".into()],
        chorus_server::now_ms(),
    )
    .unwrap()["token"]
        .as_str()
        .unwrap()
        .to_string();
    let read_token = api_data::create_token(
        &conn,
        &api_data::Principal::owner(ALICE),
        "front",
        &["read:front".into()],
        chorus_server::now_ms(),
    )
    .unwrap()["token"]
        .as_str()
        .unwrap()
        .to_string();
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("export-http-test");
    let test_dir = cfg.server.data_dir.clone();
    let sqlite_path = cfg.server.data_dir.join("download.sqlite");
    std::fs::create_dir_all(&cfg.server.data_dir).unwrap();
    db::backup_to(&conn, &cfg.db_path()).unwrap();
    drop(conn);
    let state = app::Shared::new(db::open(&cfg.db_path()).unwrap(), cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/exports", listener.local_addr().unwrap());
    let serving = state.clone();
    let server = tokio::spawn(async move { axum::serve(listener, app::router(serving)).await.unwrap() });
    let client = reqwest::Client::new();

    let denied = client.get(format!("{base}/ops.jsonl")).bearer_auth(&read_token).send().await.unwrap();
    assert_eq!(denied.status(), 403);
    assert_eq!(client.get(format!("{base}/ops.jsonl")).send().await.unwrap().status(), 401);
    let ops = client.get(format!("{base}/ops.jsonl")).bearer_auth(&export_token).send().await.unwrap();
    assert_eq!(ops.status(), 200);
    let text = ops.text().await.unwrap();
    assert_eq!(text.lines().count(), 1);
    assert!(text.contains("Alice"));
    assert!(!text.contains("Bob private secret"));

    let csv = client.get(format!("{base}/csv/members")).bearer_auth("alice-session").send().await.unwrap();
    assert_eq!(csv.status(), 200);
    let text = csv.text().await.unwrap();
    assert!(text.starts_with("id,name,display_name"));
    assert!(text.contains("\"Alice, \"\"own\"\"\""));
    assert!(!text.contains("Bob private secret"));
    for name in chorus_server::exports::CSV_NAMES {
        let result = client.get(format!("{base}/csv/{name}")).bearer_auth(&export_token).send().await.unwrap();
        assert_eq!(result.status(), 200, "CSV {name}: {}", result.text().await.unwrap());
    }
    let bob = client.get(format!("{base}/csv/members")).bearer_auth("bob-session").send().await.unwrap();
    assert!(bob.text().await.unwrap().contains("Bob private secret"));
    assert_eq!(
        client.get(format!("{base}/csv/unknown")).bearer_auth(&export_token).send().await.unwrap().status(),
        404
    );

    let sqlite = client.get(format!("{base}/account.sqlite")).bearer_auth(&export_token).send().await.unwrap();
    assert_eq!(sqlite.status(), 200);
    let bytes = sqlite.bytes().await.unwrap();
    std::fs::write(&sqlite_path, bytes).unwrap();
    let copy = Connection::open(&sqlite_path).unwrap();
    assert_eq!(copy.query_row("SELECT count(*) FROM account", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
    assert_eq!(copy.query_row("SELECT count(*) FROM op", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
    assert_eq!(copy.query_row("SELECT count(*) FROM v_member", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
    assert_eq!(copy.query_row("SELECT name FROM v_member", [], |r| r.get::<_, String>(0)).unwrap(), "Alice, \"own\"");
    assert!(
        !std::fs::read(&sqlite_path).unwrap().windows(b"Bob private secret".len()).any(|w| w == b"Bob private secret")
    );
    drop(copy);
    std::fs::remove_file(sqlite_path).unwrap();
    server.abort();
    let _ = server.await;
    drop(client);
    common::release(state, &test_dir).await;
}

#[test]
fn export_reader_keeps_one_snapshot_without_blocking_a_writer() {
    let root = std::env::var_os("CARGO_TARGET_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join(format!("export-snapshot-test-{:016x}", rand::random::<u64>()));
    std::fs::create_dir_all(&root).unwrap();
    let path = root.join("chorus.db");
    db::backup_to(&seed(), &path).unwrap();
    let writer = db::open(&path).unwrap();
    let reader = chorus_server::exports::read_snapshot(&path).unwrap();
    let count = || {
        reader.query_row("SELECT count(*) FROM member WHERE account_id=?1", [ALICE], |r| r.get::<_, i64>(0)).unwrap()
    };
    assert_eq!(count(), 1);
    writer.execute("INSERT INTO member(id,account_id,name,created_at) VALUES ('later',?1,'later',1)", [ALICE]).unwrap();
    assert_eq!(count(), 1, "reader should keep its initial WAL snapshot");
    assert_eq!(
        writer.query_row("SELECT count(*) FROM member WHERE account_id=?1", [ALICE], |r| r.get::<_, i64>(0)).unwrap(),
        2
    );
    drop(reader);
    drop(writer);
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn cli_exports_each_format_for_only_the_requested_account() {
    let root = std::env::var_os("CARGO_TARGET_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join(format!("export-cli-test-{:016x}", rand::random::<u64>()));
    std::fs::create_dir_all(&root).unwrap();
    let source = seed();
    db::backup_to(&source, &root.join("chorus.db")).unwrap();
    drop(source);
    let config = format!("[server]\ndata_dir = '{}'\n", root.to_string_lossy().replace('\\', "/"));
    std::fs::write(root.join("chorus.toml"), config).unwrap();
    for kind in ["full", "csv", "sqlite"] {
        let dest = root.join(kind);
        let result = Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .args([
                "--config",
                &root.join("chorus.toml").to_string_lossy(),
                "export",
                "--account",
                ALICE,
                "--kind",
                kind,
                "--to",
                &dest.to_string_lossy(),
            ])
            .output()
            .unwrap();
        assert!(result.status.success(), "{kind}: {}", String::from_utf8_lossy(&result.stderr));
    }
    let jsonl = std::fs::read_to_string(root.join("full/ops.jsonl")).unwrap();
    assert_eq!(jsonl.lines().count(), 1);
    assert!(jsonl.contains("Alice"));
    assert!(!jsonl.contains("Bob private secret"));
    let overwrite = Command::new(env!("CARGO_BIN_EXE_chorus-server"))
        .args([
            "--config",
            &root.join("chorus.toml").to_string_lossy(),
            "export",
            "--account",
            ALICE,
            "--kind",
            "full",
            "--to",
            &root.join("full").to_string_lossy(),
        ])
        .output()
        .unwrap();
    assert!(!overwrite.status.success(), "CLI must not overwrite an existing export");
    assert_eq!(std::fs::read_to_string(root.join("full/ops.jsonl")).unwrap(), jsonl);
    let members = std::fs::read_to_string(root.join("csv/members.csv")).unwrap();
    assert!(members.contains("Alice"));
    assert!(!members.contains("Bob private secret"));
    assert_eq!(std::fs::read_dir(root.join("csv")).unwrap().count(), 7);
    let copy = Connection::open(root.join("sqlite/account.sqlite")).unwrap();
    assert_eq!(copy.query_row("SELECT count(*) FROM account", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
    assert_eq!(copy.query_row("SELECT count(*) FROM v_member", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
    drop(copy);
    std::fs::remove_dir_all(root).unwrap();
}
