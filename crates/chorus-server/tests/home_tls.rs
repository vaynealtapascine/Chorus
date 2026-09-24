//! Chorus Home's home-wifi listener (D-071, tls.rs): the same app over TLS with a self-signed
//! certificate, which `/server` and phone invites name by its pin.

use chorus_server::{app, auth, config::Config, db, tls};
use serde_json::Value;
mod common;

#[tokio::test]
async fn phones_reach_the_app_over_tls_and_invites_carry_the_pin() {
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("home-tls-test");
    cfg.server.lan_listen = Some("127.0.0.1:0".into());
    std::fs::create_dir_all(&cfg.server.data_dir).unwrap();
    let mut conn = db::open(&cfg.db_path()).unwrap();
    db::migrate(&mut conn).unwrap();
    db::set_meta(&conn, "instance_id", "test").unwrap();
    let id = tls::load_or_create(&cfg.server.data_dir).unwrap();
    let state = app::Shared::new(conn, cfg.clone()).unwrap();

    let listener = tls::TlsListener::bind("127.0.0.1:0", &id).await.unwrap();
    let addr = axum::serve::Listener::local_addr(&listener).unwrap();
    let router = tls::with_peer(app::router(state.clone())).into_make_service_with_connect_info::<tls::Peer>();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });

    // the phone trusts the pinned certificate, not a CA; this client just skips the CA check
    let http = reqwest::Client::builder().danger_accept_invalid_certs(true).build().unwrap();
    let info: Value = http.get(format!("https://{addr}/api/v1/server")).send().await.unwrap().json().await.unwrap();
    assert_eq!(info["tls_pin"], id.pin, "/server names the certificate phones pin");
    // plain HTTP on the TLS port is refused, never answered in the clear
    assert!(reqwest::get(format!("http://{addr}/api/v1/server")).await.is_err());

    // a device invite made on this server tells a phone where to go on the wifi, and what to pin
    let code = auth::create_invite(
        &state.db.lock().unwrap(),
        auth::InviteKind::System,
        None,
        "t",
        auth::INVITE_TTL_MS,
        1,
        chorus_server::now_ms(),
    )
    .unwrap();
    if let Some(link) = tls::home_invite(&cfg, &code) {
        assert!(link.starts_with("https://"), "{link}");
        assert!(link.ends_with(&format!("/i/{code}#pin={}", id.pin)), "{link}");
    } // (no LAN address on an offline machine: then there's nothing to invite over)
    drop((http, state));
    // the server task still holds the database here; http_test_dir sweeps the folder on a later run
    let _ = std::fs::remove_dir_all(&cfg.server.data_dir);
}
