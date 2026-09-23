//! Export endpoints use the same session/API-token principal as other data reads.

use chorus_core::hlc::Hlc;
use chorus_server::{api_data, app, auth, config::Config, db};
use rusqlite::{Connection, params};

fn seed() -> Connection {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let now = chorus_server::now_ms();
    for (account, secret) in [("alice", "Alice, \"own\""), ("bob", "Bob private secret")] {
        conn.execute("INSERT INTO account(id,kind,handle,created_at) VALUES (?1,'person',?1,0)", [account]).unwrap();
        conn.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at)
             VALUES (?1,?2,?1,?1,'cli','test',0)",
            params![format!("{account}-device"), account],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
            params![auth::hash(&format!("{account}-session")), format!("{account}-device"), now, now + 86_400_000],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO member(id,account_id,name,created_at) VALUES (?1,?2,?3,0)",
            params![format!("{account}-member"), account, secret],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO op(id,scope,kind,entity_id,payload,v,hlc,account_id,device_id,
              occurred_at,device_at,tz_offset_min,seen_seq,received_at)
             VALUES (?1,?2,'member.create',?3,?4,1,?5,?6,?7,?8,?8,0,0,?8)",
            params![
                format!("{account}-op"),
                format!("account:{account}"),
                format!("{account}-member"),
                serde_json::json!({"name": secret}).to_string(),
                Hlc::new(now as u64, 0, 1).to_string(),
                account,
                format!("{account}-device"),
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
        &api_data::Principal::owner("alice"),
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
        &api_data::Principal::owner("alice"),
        "front",
        &["read:front".into()],
        chorus_server::now_ms(),
    )
    .unwrap()["token"]
        .as_str()
        .unwrap()
        .to_string();
    let state = app::Shared::new(conn, Config::default()).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/exports", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app::router(state)).await.unwrap() });
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
}
