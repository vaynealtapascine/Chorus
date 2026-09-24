//! Chorus Home's setup and settings (D-071, home.rs): the first account from this PC, then an
//! admin changing the port and home-wifi access without touching `chorus.toml` by hand.

use base64::Engine as _;
use chorus_server::{app, config::Config, db, home};
use p256::pkcs8::EncodePublicKey;
use serde_json::{Value, json};
mod common;

fn pubkey(seed: u8) -> String {
    let sk = p256::ecdsa::SigningKey::from_slice(&[seed; 32]).unwrap();
    base64::engine::general_purpose::STANDARD.encode(sk.verifying_key().to_public_key_der().unwrap().as_bytes())
}

#[tokio::test]
async fn a_home_install_is_set_up_and_configured_from_this_pc() {
    let dir = common::http_test_dir("home-setup-test");
    std::fs::create_dir_all(&dir).unwrap();
    let file = dir.join("chorus.toml");
    let data = dir.join("data").to_string_lossy().replace('\\', "/");
    std::fs::write(&file, format!("[server]\nhome = true\nlisten = \"127.0.0.1:5250\"\ndata_dir = \"{data}\"\n"))
        .unwrap();
    let cfg = Config::load(Some(&file)).unwrap();
    std::fs::create_dir_all(&cfg.server.data_dir).unwrap();
    let mut conn = db::open(&cfg.db_path()).unwrap();
    db::migrate(&mut conn).unwrap();
    db::set_meta(&conn, "instance_id", "test").unwrap();
    db::set_meta(&conn, "epoch", "1").unwrap();
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1", listener.local_addr().unwrap());
    let router = app::router(state.clone()).into_make_service_with_connect_info::<std::net::SocketAddr>();
    let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    let http = reqwest::Client::new();

    let status: Value = http.get(format!("{base}/home")).send().await.unwrap().json().await.unwrap();
    assert_eq!(status["needs_setup"], true);
    assert_eq!(status["port"], 5250);
    assert_eq!(status["lan"], false);
    // anything that came through a proxy isn't "this PC"
    let proxied = http.get(format!("{base}/home")).header("x-forwarded-for", "192.168.1.9").send().await.unwrap();
    assert_eq!(proxied.status(), 403);

    // first run: a one-use invite this browser redeems with its own key
    let setup: Value = http.post(format!("{base}/home/setup")).send().await.unwrap().json().await.unwrap();
    let code = setup["code"].as_str().unwrap().to_string();
    let body = json!({
        "code": code,
        "device": {"name": "This PC", "platform": "web", "public_key": pubkey(7)},
        "account": {"display_name": "The Stars", "handle": "stars"},
    });
    let redeemed = http.post(format!("{base}/auth/redeem")).json(&body).send().await.unwrap();
    assert_eq!(redeemed.status(), 201);
    let session = redeemed.json::<Value>().await.unwrap()["session"].as_str().unwrap().to_string();
    let again = http.post(format!("{base}/home/setup")).send().await.unwrap();
    assert_eq!(again.status(), 409, "setup runs once");
    let status: Value = http.get(format!("{base}/home")).send().await.unwrap().json().await.unwrap();
    assert_eq!(status["needs_setup"], false);

    // settings need the admin (the first account is one) and a valid value
    let put = |b: Value| http.put(format!("{base}/home/settings")).json(&b);
    assert_eq!(put(json!({"port": 6000})).send().await.unwrap().status(), 401);
    assert_eq!(put(json!({"port": 80})).bearer_auth(&session).send().await.unwrap().status(), 400);
    let ok = put(json!({"port": 6000, "lan": true, "keep_daily": 7})).bearer_auth(&session).send().await.unwrap();
    assert_eq!(ok.status(), 202);
    assert_eq!(ok.json::<Value>().await.unwrap()["url"], "http://localhost:6000/");
    let saved = Config::load(Some(&file)).unwrap();
    assert!(saved.server.home, "the rest of the file is kept");
    assert_eq!(saved.server.listen, "127.0.0.1:6000");
    assert_eq!(saved.server.lan_listen.as_deref(), Some("0.0.0.0:6001"));
    assert_eq!(saved.backup.keep_daily, 7);
    tokio::time::sleep(std::time::Duration::from_millis(600)).await;
    assert!(home::take_restart(), "the server is asked to start again with the new file");

    server.abort();
    let _ = server.await;
    drop((http, state));
    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn a_technical_install_has_no_home_routes() {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let state = app::Shared::new(conn, Config::default()).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1", listener.local_addr().unwrap());
    let router = app::router(state).into_make_service_with_connect_info::<std::net::SocketAddr>();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    // behind Caddy every request looks local, so the routes must not exist at all
    let r = reqwest::Client::new().post(format!("{base}/home/setup")).send().await.unwrap();
    assert_eq!(r.status(), 404);
}
