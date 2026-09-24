use chorus_server::{app, auth, config::Config, db};
use rusqlite::params;
use serde_json::Value;
mod common;

#[tokio::test]
async fn health_requires_admin_session_and_reports_live_counts() {
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("health-http-test");
    let test_dir = cfg.server.data_dir.clone();
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
    assert_eq!(health["restore_window"], serde_json::json!({"open": false, "closes_at": null, "devices": []}));
    server.abort();
    let _ = server.await;
    drop(state);
    let _ = std::fs::remove_dir_all(test_dir);
}

#[tokio::test]
async fn restore_window_shows_in_health_and_closes_for_admins_only() {
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("health-restore-test");
    let test_dir = cfg.server.data_dir.clone();
    let mut conn = db::open(&cfg.db_path()).unwrap();
    db::migrate(&mut conn).unwrap();
    let now = chorus_server::now_ms();
    for (id, admin) in [("owner", true), ("friend", false)] {
        conn.execute(
            "INSERT INTO account(id,kind,handle,is_admin,created_at) VALUES (?1,'person',?1,?2,0)",
            params![id, admin],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at,last_seen_at)
             VALUES (?1,?1,?1,?1 || '-phone','android','test',0,?2)",
            params![id, now - 1000],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
            params![auth::hash(&format!("{id}-session")), id, now, now + 86_400_000],
        )
        .unwrap();
    }
    chorus_server::reconcile::start(&conn, now).unwrap();
    chorus_server::reconcile::hello(&conn, "friend", 0, now).unwrap();

    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/admin", listener.local_addr().unwrap());
    let running = state.clone();
    let server = tokio::spawn(async move { axum::serve(listener, app::router(running)).await.unwrap() });
    let client = reqwest::Client::new();

    let health: Value =
        client.get(format!("{base}/health")).bearer_auth("owner-session").send().await.unwrap().json().await.unwrap();
    let window = &health["restore_window"];
    assert_eq!(window["open"], true);
    assert_eq!(window["closes_at"], now + chorus_server::reconcile::WINDOW_MS);
    let devices = window["devices"].as_array().unwrap();
    assert_eq!(devices.len(), 2);
    assert_eq!(devices[0]["account"], "friend");
    assert_eq!(devices[0]["name"], "friend-phone");
    assert_eq!(devices[0]["platform"], "android");
    assert_eq!(devices[0]["last_seen_at"], now - 1000);
    assert_eq!(devices[0]["back_at"], now);
    assert_eq!(devices[1]["account"], "owner");
    assert_eq!(devices[1]["back_at"], Value::Null);

    let close = format!("{base}/reconcile/close");
    assert_eq!(client.post(&close).send().await.unwrap().status(), 401);
    assert_eq!(client.post(&close).bearer_auth("friend-session").send().await.unwrap().status(), 403);
    assert!(chorus_server::reconcile::open(&state.db.lock().unwrap(), now).unwrap(), "a 403 changes nothing");
    assert_eq!(client.post(&close).bearer_auth("owner-session").send().await.unwrap().status(), 204);
    assert!(!chorus_server::reconcile::open(&state.db.lock().unwrap(), now).unwrap());
    let health: Value =
        client.get(format!("{base}/health")).bearer_auth("owner-session").send().await.unwrap().json().await.unwrap();
    assert_eq!(health["restore_window"]["open"], false);
    assert_eq!(health["restore_window"]["devices"], serde_json::json!([]));
    server.abort();
    let _ = server.await;
    drop(state);
    let _ = std::fs::remove_dir_all(test_dir);
}
