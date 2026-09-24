use chorus_server::{app, auth, config::Config, db};
use rusqlite::params;
use serde_json::Value;

#[tokio::test]
async fn health_requires_admin_session_and_reports_live_counts() {
    let mut cfg = Config::default();
    cfg.server.data_dir = std::path::PathBuf::from(std::env::var_os("CARGO_TARGET_DIR").unwrap())
        .join(format!("health-http-test-{:016x}", rand::random::<u64>()));
    let mut conn = db::open(&cfg.db_path()).unwrap();
    db::migrate(&mut conn).unwrap();
    let now = chorus_server::now_ms();
    for (id, admin) in [("owner", true), ("friend", false)] {
        conn.execute("INSERT INTO account(id,kind,is_admin,created_at) VALUES (?1,'person',?2,0)", params![id, admin])
            .unwrap();
        conn.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at)
             VALUES (?1,?1,?1,?1,'cli','test',0)",
            [id],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
            params![auth::hash(&format!("{id}-session")), id, now, now + 86_400_000],
        )
        .unwrap();
    }
    conn.execute(
        "INSERT INTO op(id,scope,kind,payload,hlc,account_id,device_id,occurred_at,device_at,tz_offset_min,received_at)
         VALUES ('op','account:owner','noop','{}','0:0:0','owner','owner',0,0,0,0)",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO notification(id,recipient_account_id,kind,payload,created_at,due_at)
         VALUES ('pending','owner','switch','{}',0,0)",
        [],
    )
    .unwrap();
    db::set_meta(&conn, "last_error", r#"{"source":"backup","at":12,"message":"test failure"}"#).unwrap();
    let snapshot = cfg.backup_dir().join("chorus-test");
    std::fs::create_dir_all(&snapshot).unwrap();
    std::fs::write(snapshot.join("chorus.db"), b"db!").unwrap();
    std::fs::write(snapshot.join("manifest.json"), r#"{"created_at_ms":1234,"blobs":[{"size":10}]}"#).unwrap();

    let ntfy_listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    cfg.push.ntfy_url = Some(format!("http://{}", ntfy_listener.local_addr().unwrap()));
    tokio::spawn(async move {
        axum::serve(ntfy_listener, axum::Router::new().route("/", axum::routing::get(|| async { "ok" }))).await.unwrap()
    });

    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/api/v1/admin/health", listener.local_addr().unwrap());
    let running = state.clone();
    let server = tokio::spawn(async move { axum::serve(listener, app::router(running)).await.unwrap() });
    let client = reqwest::Client::new();
    assert_eq!(client.get(&url).send().await.unwrap().status(), 401);
    assert_eq!(client.get(&url).bearer_auth("friend-session").send().await.unwrap().status(), 403);
    let response = client.get(&url).bearer_auth("owner-session").send().await.unwrap();
    assert_eq!(response.status(), 200);
    let health: Value = response.json().await.unwrap();
    assert_eq!(health["op_count"], 1);
    assert_eq!(health["pending_notifications"], 1);
    assert_eq!(health["connected_devices"], 0);
    assert!(health["db_bytes"].as_u64().unwrap() > 0);
    assert_eq!(health["last_backup"]["at"], 1234);
    assert_eq!(health["last_backup"]["size_bytes"], 13);
    assert_eq!(health["last_error"]["source"], "backup");
    assert_eq!(health["ntfy_reachable"], true);
    server.abort();
}
