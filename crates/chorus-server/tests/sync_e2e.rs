//! End to end: the real client engine (`chorus_core::sync::ClientEngine`) against the real server
//! over a real WebSocket. Two devices of one account plus a friend; ops flow both ways, a device
//! catches up after being offline, and invalid ops come back rejected.

use std::time::Duration;

use base64::Engine as _;
use chorus_core::hlc::HlcClock;
use chorus_core::id::new_id;
use chorus_core::model;
use chorus_core::op::Op;
use chorus_core::sync::{ClientEngine, ClientStore, ClockReading, Frame, MemStore};
use chorus_core::time::TimeSource;
use chorus_server::{app, auth, config::Config, db};
use futures_util::{SinkExt, StreamExt};
use p256::pkcs8::EncodePublicKey;
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::Message;

type Ws = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

struct Server {
    base: String,
    state: app::AppState,
}

async fn start() -> Server {
    start_with(Config::default()).await
}

async fn start_with(cfg: Config) -> Server {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    db::set_meta(&conn, "instance_id", "test").unwrap();
    db::set_meta(&conn, "epoch", "1").unwrap();
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let router = app::router(state.clone());
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    Server { base: format!("127.0.0.1:{}", addr.port()), state }
}

fn pubkey(seed: u8) -> String {
    let sk = p256::ecdsa::SigningKey::from_slice(&[seed; 32]).unwrap();
    base64::engine::general_purpose::STANDARD.encode(sk.verifying_key().to_public_key_der().unwrap().as_bytes())
}

async fn enrol(s: &Server, kind: auth::InviteKind, account: Option<&str>, seed: u8, handle: &str) -> Value {
    let code = {
        let c = s.state.db.lock().unwrap();
        auth::create_invite(&c, kind, account, "t", auth::INVITE_TTL_MS, 1, chorus_server::now_ms()).unwrap()
    };
    let body = json!({
        "code": code,
        "device": {"name": format!("dev{seed}"), "platform": "cli", "public_key": pubkey(seed)},
        "account": {"display_name": handle, "handle": handle},
    });
    let r =
        reqwest::Client::new().post(format!("http://{}/api/v1/auth/redeem", s.base)).json(&body).send().await.unwrap();
    assert_eq!(r.status(), 201);
    r.json().await.unwrap()
}

struct Device {
    token: String,
    store: MemStore,
    engine: ClientEngine,
    clock: HlcClock,
    ws: Option<Ws>,
}

impl Device {
    fn new(e: &Value, node: u32) -> Device {
        Device {
            token: e["session"].as_str().unwrap().into(),
            store: MemStore::default(),
            engine: ClientEngine::new(e["device_id"].as_str().unwrap()),
            clock: HlcClock::new(node),
            ws: None,
        }
    }

    async fn send(&mut self, frames: Vec<Frame>) {
        let ws = self.ws.as_mut().unwrap();
        for f in frames {
            ws.send(Message::Text(serde_json::to_string(&f).unwrap().into())).await.unwrap();
        }
    }

    async fn connect(&mut self, s: &Server) {
        let (ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/api/v1/sync", s.base)).await.unwrap();
        self.ws = Some(ws);
        let clock = ClockReading { wall: chorus_server::now_ms(), mono: None, boot_id: None };
        let hello = self.engine.on_connect(&self.store, clock, &self.token);
        self.send(vec![hello]).await;
    }

    fn disconnect(&mut self) {
        self.ws = None;
        self.engine.on_disconnect();
    }

    /// Process incoming frames until the socket is quiet for `quiet`.
    async fn drain(&mut self, quiet: Duration) {
        loop {
            let ws = self.ws.as_mut().unwrap();
            let msg = match tokio::time::timeout(quiet, ws.next()).await {
                Ok(Some(Ok(Message::Text(t)))) => t,
                Ok(Some(Ok(_))) => continue,
                _ => return,
            };
            let f: Frame = serde_json::from_str(&msg).unwrap();
            if let Frame::Error { message, .. } = &f {
                panic!("server error: {message}");
            }
            let out = self.engine.on_frame(&mut self.store, f);
            self.send(out).await;
        }
    }

    fn scope(&self, prefix: &str) -> String {
        self.store.scopes.iter().find(|s| s.starts_with(prefix)).cloned().unwrap()
    }

    async fn create(&mut self, kind: &str, scope: &str, entity: &str, payload: Value) -> String {
        let now = chorus_server::now_ms();
        let o = Op {
            id: new_id(now as u64, rand::random()),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: Some(entity.into()),
            hlc: self.clock.tick(now as u64),
            device_at: now,
            tz_offset_min: 60,
            mono: None,
            boot_id: None,
            time_source: TimeSource::Auto,
            seen_seq: self.store.cursor(scope),
            member_id: None,
            payload,
            seq: None,
            account_id: None,
            device_id: None,
            occurred_at: None,
            received_at: None,
        };
        let id = o.id.clone();
        self.store.add_local(o);
        if self.ws.is_some() {
            let out = self.engine.pump(&self.store);
            self.send(out).await;
        }
        id
    }
}

const Q: Duration = Duration::from_millis(250);

#[tokio::test(flavor = "multi_thread")]
async fn devices_sync_through_the_real_server() {
    let s = start().await;
    let a = enrol(&s, auth::InviteKind::System, None, 1, "stars").await;
    let acct = a["account_id"].as_str().unwrap().to_string();
    let phone_e = a.clone();
    let desk_e = enrol(&s, auth::InviteKind::Device, Some(&acct), 2, "").await;

    let mut phone = Device::new(&phone_e, 1);
    let mut desk = Device::new(&desk_e, 2);
    phone.connect(&s).await;
    phone.drain(Q).await;
    desk.connect(&s).await;
    desk.drain(Q).await;
    // both see the server-created internal space and #general
    let space = phone.scope("space:");
    assert_eq!(phone.store.confirmed().filter(|o| o.scope == space).count(), 3);
    assert_eq!(desk.store.confirmed().filter(|o| o.scope == space).count(), 3);

    // phone creates a member; desk receives it live
    let acct_scope = format!("account:{acct}");
    let kai = new_id(1, [42; 10]);
    phone.create("member.create", &acct_scope, &kai, json!({"name": "Kai"})).await;
    phone.drain(Q).await;
    desk.drain(Q).await;
    assert!(desk.store.confirmed().any(|o| o.entity_id.as_deref() == Some(kai.as_str())), "live fan-out");

    // desk goes offline and switches; phone renames Kai meanwhile
    desk.disconnect();
    let sw = new_id(2, [43; 10]);
    desk.create(
        "front.switch",
        &acct_scope,
        &sw,
        json!({"entries": [{"subject_type": "member", "subject_id": kai, "is_primary": true}]}),
    )
    .await;
    phone.create("member.set", &acct_scope, &kai, json!({"name": "Kai R"})).await;
    phone.drain(Q).await;
    // an invalid op is rejected, not lost silently
    let bad = phone.create("member.set", &acct_scope, &kai, json!({"account_id": "evil"})).await;
    phone.drain(Q).await;
    assert!(phone.store.rejected.contains_key(&bad));

    // desk reconnects: pushes its switch, catches up on the rename
    desk.connect(&s).await;
    desk.drain(Q).await;
    phone.drain(Q).await;
    for d in [&phone, &desk] {
        assert!(d.store.pending(&Default::default(), 99, false).is_empty(), "nothing pending");
        let p = model::project(d.store.confirmed());
        assert_eq!(p.row("member", &kai).unwrap().fields["name"], "Kai R");
        let fold = &p.fronts[&acct];
        assert_eq!(fold.current[0].subject_id, kai);
    }
    assert_eq!(model::project(phone.store.confirmed()).canonical(), model::project(desk.store.confirmed()).canonical());
    // and the server's SQL agrees
    {
        let c = s.state.db.lock().unwrap();
        let name: String = c.query_row("SELECT name FROM member WHERE id = ?1", [&kai], |r| r.get(0)).unwrap();
        assert_eq!(name, "Kai R");
        let fronting: String =
            c.query_row("SELECT subject_id FROM front_interval WHERE end_at IS NULL", [], |r| r.get(0)).unwrap();
        assert_eq!(fronting, kai);
    }

    // a friend can't read the system's scopes
    let f = enrol(&s, auth::InviteKind::Person, None, 3, "friend").await;
    let mut friend = Device::new(&f, 3);
    friend.connect(&s).await;
    friend.drain(Q).await;
    assert!(!friend.store.scopes.contains(&acct_scope));
    assert!(friend.store.confirmed().all(|o| o.scope != acct_scope && o.scope != space));
}

#[tokio::test]
async fn bad_token_is_refused() {
    let s = start().await;
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/api/v1/sync", s.base)).await.unwrap();
    let hello = Frame::Hello {
        device_id: "x".into(),
        token: "nope".into(),
        epoch: None,
        cursors: Default::default(),
        view_seq: 0,
        digests: Default::default(),
        clock: ClockReading { wall: 0, mono: None, boot_id: None },
        core: String::new(),
        app: String::new(),
        outbox: 0,
    };
    ws.send(Message::Text(serde_json::to_string(&hello).unwrap().into())).await.unwrap();
    let Some(Ok(Message::Text(t))) = ws.next().await else { panic!("no reply") };
    let f: Frame = serde_json::from_str(&t).unwrap();
    assert!(matches!(f, Frame::Error { code, .. } if code == "unauthenticated"));
}

#[tokio::test]
async fn a_device_links_another_device_to_its_account() {
    let s = start().await;
    let e = enrol(&s, auth::InviteKind::System, None, 21, "stars").await;
    let http = reqwest::Client::new();
    let url = format!("http://{}/api/v1/devices/invite", s.base);
    assert_eq!(http.post(&url).send().await.unwrap().status(), 401);
    let inv: Value =
        http.post(&url).bearer_auth(e["session"].as_str().unwrap()).send().await.unwrap().json().await.unwrap();
    let code = inv["code"].as_str().unwrap();
    assert!(inv["url"].as_str().unwrap().ends_with(code));
    let body = json!({"code": code, "device": {"name": "phone", "platform": "android", "public_key": pubkey(22)}});
    let r = http.post(format!("http://{}/api/v1/auth/redeem", s.base)).json(&body).send().await.unwrap();
    assert_eq!(r.status(), 201);
    let phone: Value = r.json().await.unwrap();
    assert_eq!(phone["account_id"], e["account_id"]);
    // one use only
    let body = json!({"code": code, "device": {"name": "again", "platform": "android", "public_key": pubkey(23)}});
    let r = http.post(format!("http://{}/api/v1/auth/redeem", s.base)).json(&body).send().await.unwrap();
    assert_eq!(r.status(), 400);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_friend_follows_a_system() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 31, "stars").await;
    let friend = enrol(&s, auth::InviteKind::Person, None, 32, "alex").await;
    let mut phone = Device::new(&sys, 31);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let acct_scope = phone.scope("account:");

    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let tok = |e: &Value| e["session"].as_str().unwrap().to_string();

    // the friend asks; the system's connected phone sees the request live
    let r =
        http.post(url("/follows")).bearer_auth(tok(&friend)).json(&json!({"target": "@Stars"})).send().await.unwrap();
    assert_eq!(r.status(), 201);
    let id = r.json::<Value>().await.unwrap()["id"].as_str().unwrap().to_string();
    // asking again while open returns the same follow
    let again: Value = http
        .post(url("/follows"))
        .bearer_auth(tok(&friend))
        .json(&json!({"target": "stars"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(again["id"], id.as_str());
    phone.drain(Q).await;
    let p = model::project(phone.store.confirmed());
    let row = &p.rows["follow"][&id];
    assert_eq!(row.fields["status"], "requested");
    assert_eq!(row.fields["follower_account_id"], friend["account_id"]);

    // the system accepts from its own device
    phone.create("follow.accept", &acct_scope, &id, json!({})).await;
    phone.drain(Q).await;
    let list: Value = http.get(url("/follows")).bearer_auth(tok(&friend)).send().await.unwrap().json().await.unwrap();
    assert_eq!(list["following"][0]["status"], "active");
    assert_eq!(list["following"][0]["account"]["handle"], "stars");
    let list: Value = http.get(url("/follows")).bearer_auth(tok(&sys)).send().await.unwrap().json().await.unwrap();
    assert_eq!(list["followers"][0]["account"]["handle"], "alex");

    // prefs: only the follower, and they must parse
    let prefs = json!({"levels": ["front"], "quiet_hours": {"from": "23:00", "to": "08:00"}});
    let r = http.put(url(&format!("/follows/{id}/prefs"))).bearer_auth(tok(&sys)).json(&prefs).send().await.unwrap();
    assert_eq!(r.status(), 403);
    let r = http
        .put(url(&format!("/follows/{id}/prefs")))
        .bearer_auth(tok(&friend))
        .json(&json!({"levels": 3}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 400);
    let r = http.put(url(&format!("/follows/{id}/prefs"))).bearer_auth(tok(&friend)).json(&prefs).send().await.unwrap();
    assert_eq!(r.status(), 204);

    // a client can't forge a follow request into its own scope for someone else
    let forged = phone
        .create("follow.request", &acct_scope, &new_id(9, [9; 10]), json!({"follower_account_id": "someone-else"}))
        .await;
    phone.drain(Q).await;
    assert!(phone.store.rejected.iter().any(|(op, _)| *op == forged));

    // unfollow
    let r = http.delete(url(&format!("/follows/{id}"))).bearer_auth(tok(&friend)).send().await.unwrap();
    assert_eq!(r.status(), 204);
    phone.drain(Q).await;
    assert_eq!(model::project(phone.store.confirmed()).rows["follow"][&id].fields["status"], "ended");
    let list: Value = http.get(url("/follows")).bearer_auth(tok(&friend)).send().await.unwrap().json().await.unwrap();
    assert_eq!(list["following"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn the_server_hosts_the_android_update() {
    let dir = std::env::temp_dir().join(format!("chorus-apk-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut cfg = Config::default();
    cfg.server.android_dir = Some(dir.clone());
    let s = start_with(cfg).await;
    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}{p}", s.base);
    assert_eq!(http.get(url("/api/v1/android/latest")).send().await.unwrap().status(), 404);
    std::fs::write(dir.join("chorus.apk"), b"PK fake apk").unwrap();
    std::fs::write(
        dir.join("chorus.json"),
        br#"{"version_code": 42, "version_name": "0.2.0", "sha256": "ab", "size": 11}"#,
    )
    .unwrap();
    let meta: Value = http.get(url("/api/v1/android/latest")).send().await.unwrap().json().await.unwrap();
    assert_eq!(meta["version_code"], 42);
    assert!(meta["url"].as_str().unwrap().ends_with("/download/android"));
    let r = http.get(url("/download/android")).send().await.unwrap();
    assert_eq!(r.headers()["content-type"], "application/vnd.android.package-archive");
    assert_eq!(r.bytes().await.unwrap().as_ref(), b"PK fake apk");
    let _ = std::fs::remove_dir_all(&dir);
}
