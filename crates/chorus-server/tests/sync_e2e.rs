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
use sha2::{Digest, Sha256};
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
    // the follower's prefs (quiet hours, mutes, time zone) never reach the followed system's
    // devices, but the server and the follower's own view have them
    phone.drain(Q).await;
    assert!(!phone.store.confirmed().any(|o| o.kind == "follow.set_prefs"), "prefs leaked to the followed system");
    let stored: String =
        s.state.db.lock().unwrap().query_row("SELECT prefs FROM follow WHERE id = ?1", [&id], |r| r.get(0)).unwrap();
    assert!(stored.contains("23:00"), "{stored}");
    let mine: Value = http.get(url("/follows")).bearer_auth(tok(&friend)).send().await.unwrap().json().await.unwrap();
    assert_eq!(mine["following"][0]["prefs"]["quiet_hours"]["from"], "23:00");
    let theirs: Value = http.get(url("/follows")).bearer_auth(tok(&sys)).send().await.unwrap().json().await.unwrap();
    assert_eq!(theirs["followers"][0]["prefs"], json!({}), "the followed account doesn't get them from the API either");

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

#[tokio::test(flavor = "multi_thread")]
async fn api_tokens_read_the_front_and_stream_switches() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 41, "stars").await;
    let mut phone = Device::new(&sys, 41);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let acct = phone.scope("account:");
    let kai = new_id(1, [42; 10]);
    phone.create("member.create", &acct, &kai, json!({"name": "Kai", "color": "#C0694E"})).await;
    phone.drain(Q).await;

    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let session = sys["session"].as_str().unwrap().to_string();
    let r = http
        .post(url("/tokens"))
        .bearer_auth(&session)
        .json(&json!({"name": "obs", "scopes": ["read:front", "stream"]}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 201);
    let created: Value = r.json().await.unwrap();
    let token = created["token"].as_str().unwrap().to_string();
    assert!(token.starts_with("chorus_"));
    // tokens can't mint tokens, and scopes are enforced
    let r = http
        .post(url("/tokens"))
        .bearer_auth(&token)
        .json(&json!({"name": "x", "scopes": ["stream"]}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 400);
    assert_eq!(http.get(url("/members")).bearer_auth(&token).send().await.unwrap().status(), 403);
    let front: Value = http.get(url("/front")).bearer_auth(&token).send().await.unwrap().json().await.unwrap();
    assert_eq!(front["front"].as_array().unwrap().len(), 0);

    // the stream: first event is the current front, then a live switch from the phone
    let mut sse = http.get(url(&format!("/stream?token={token}"))).send().await.unwrap();
    assert_eq!(sse.status(), 200);
    let mut buf = String::new();
    async fn until(sse: &mut reqwest::Response, buf: &mut String, needle: &str) {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        while !buf.contains(needle) {
            let chunk =
                tokio::time::timeout_at(deadline, sse.chunk()).await.expect("stream timed out").unwrap().unwrap();
            buf.push_str(&String::from_utf8_lossy(&chunk));
        }
    }
    until(&mut sse, &mut buf, "\n\n").await;
    assert!(buf.starts_with("event: front"), "{buf}");
    let sw = new_id(2, [43; 10]);
    phone
        .create(
            "front.switch",
            &acct,
            &sw,
            json!({"entries": [{"subject_type": "member", "subject_id": kai, "level": "front", "is_primary": true}]}),
        )
        .await;
    phone.drain(Q).await;
    until(&mut sse, &mut buf, "\"Kai\"").await;
    let front: Value = http.get(url("/front")).bearer_auth(&token).send().await.unwrap().json().await.unwrap();
    assert_eq!(front["front"][0]["name"], "Kai");
    let switches: Value =
        http.get(url("/front/switches")).bearer_auth(&token).send().await.unwrap().json().await.unwrap();
    assert_eq!(switches["items"].as_array().unwrap().len(), 1);

    // messages on the stream need read:messages; a message-only stream gets just messages
    assert_eq!(http.get(url(&format!("/stream?token={token}&events=message"))).send().await.unwrap().status(), 403);
    let (_, t) = {
        let r = http
            .post(url("/tokens"))
            .bearer_auth(&session)
            .json(&json!({"name": "log", "scopes": ["read:messages", "stream"]}))
            .send()
            .await
            .unwrap();
        (r.status(), r.json::<Value>().await.unwrap())
    };
    let logger = t["token"].as_str().unwrap().to_string();
    let mut msse = http.get(url(&format!("/stream?token={logger}&events=message"))).send().await.unwrap();
    assert_eq!(msse.status(), 200);
    let home = phone.scope("space:");
    let p = model::project(phone.store.confirmed());
    let general = p.rows["channel"].iter().find(|(_, r)| r.fields["name"] == "general").unwrap().0.clone();
    let m = new_id(3, [44; 10]);
    phone
        .create(
            "message.send",
            &home,
            &m,
            json!({"channel_id": general, "authors": [kai], "text": "on the stream", "entities": []}),
        )
        .await;
    phone.drain(Q).await;
    let mut mbuf = String::new();
    until(&mut msse, &mut mbuf, "on the stream").await;
    assert!(mbuf.starts_with("event: message"), "no front event first on a message-only stream: {mbuf}");
    assert!(mbuf.contains("\"channel\":\"general\""), "{mbuf}");

    // revoke: the token stops working
    let list: Value = http.get(url("/tokens")).bearer_auth(&session).send().await.unwrap().json().await.unwrap();
    let id = list["items"][0]["id"].as_str().unwrap().to_string();
    assert_eq!(http.delete(url(&format!("/tokens/{id}"))).bearer_auth(&session).send().await.unwrap().status(), 204);
    assert_eq!(http.get(url("/front")).bearer_auth(&token).send().await.unwrap().status(), 401);
}

#[tokio::test(flavor = "multi_thread")]
async fn webhooks_post_signed_events() {
    // a receiver on the loopback
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(axum::http::HeaderMap, String)>();
    let receiver = axum::Router::new().route(
        "/hook",
        axum::routing::post(move |h: axum::http::HeaderMap, body: String| {
            let tx = tx.clone();
            async move {
                let _ = tx.send((h, body));
                axum::http::StatusCode::NO_CONTENT
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let hook = format!("http://{}/hook", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, receiver).await.unwrap() });

    // the receiver is on loopback, which only `any` allows
    let mut cfg = Config::default();
    cfg.security.webhook_targets = Some(chorus_server::config::WebhookTargets::Any);
    let s = start_with(cfg).await;
    let sys = enrol(&s, auth::InviteKind::System, None, 51, "stars").await;
    let mut phone = Device::new(&sys, 51);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let acct = phone.scope("account:");
    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let session = sys["session"].as_str().unwrap().to_string();

    // (which targets each `webhook_targets` mode refuses is covered by webhooks::tests::url_rules)
    let created: Value = http
        .post(url("/webhooks"))
        .bearer_auth(&session)
        .json(&json!({"url": hook, "events": ["front.switch", "member.created"]}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let secret = created["secret"].as_str().unwrap().to_string();
    let id = created["id"].as_str().unwrap().to_string();
    let listed: Value = http.get(url("/webhooks")).bearer_auth(&session).send().await.unwrap().json().await.unwrap();
    assert!(listed["items"][0].get("secret").is_none(), "the secret is shown once");

    async fn next(
        rx: &mut tokio::sync::mpsc::UnboundedReceiver<(axum::http::HeaderMap, String)>,
    ) -> (axum::http::HeaderMap, Value, String) {
        let (h, body) = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await.expect("no webhook").unwrap();
        let v: Value = serde_json::from_str(&body).unwrap();
        (h, v, body)
    }
    let check = |h: &axum::http::HeaderMap, body: &str| {
        let sig = h["chorus-signature"].to_str().unwrap();
        let t: i64 = sig.split(',').next().unwrap().trim_start_matches("t=").parse().unwrap();
        assert_eq!(sig, chorus_server::webhooks::signature(&secret, t, body));
    };

    let kai = new_id(1, [52; 10]);
    phone.create("member.create", &acct, &kai, json!({"name": "Kai"})).await;
    phone.drain(Q).await;
    let (h, v, body) = next(&mut rx).await;
    check(&h, &body);
    assert_eq!(h["chorus-event"], "member.created");
    assert_eq!(v["data"]["name"], "Kai");

    // not subscribed to member.updated: nothing is sent for the edit; the switch is
    phone.create("member.set", &acct, &kai, json!({"pronouns": "they/them"})).await;
    phone
        .create(
            "front.switch",
            &acct,
            &new_id(2, [53; 10]),
            json!({"entries": [{"subject_type": "member", "subject_id": kai, "level": "front", "is_primary": true}]}),
        )
        .await;
    phone.drain(Q).await;
    let (h, v, body) = next(&mut rx).await;
    check(&h, &body);
    assert_eq!(v["event"], "front.switch");
    assert_eq!(v["data"]["front"][0]["name"], "Kai");

    // the test button
    let t: Value = http
        .post(url(&format!("/webhooks/{id}/test")))
        .bearer_auth(&session)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(t["ok"], true);
    let (h, _, body) = next(&mut rx).await;
    check(&h, &body);
    assert_eq!(h["chorus-event"], "ping");

    // turned off: no more deliveries
    let r = http
        .put(url(&format!("/webhooks/{id}")))
        .bearer_auth(&session)
        .json(&json!({"enabled": false}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 204);
    phone.create("member.create", &acct, &new_id(3, [54; 10]), json!({"name": "Rin"})).await;
    phone.drain(Q).await;
    assert!(tokio::time::timeout(Duration::from_millis(1500), rx.recv()).await.is_err());
    assert_eq!(http.delete(url(&format!("/webhooks/{id}"))).bearer_auth(&session).send().await.unwrap().status(), 204);
}

#[tokio::test(flavor = "multi_thread")]
async fn rest_reads_cover_members_groups_fields_and_days() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 61, "stars").await;
    let mut phone = Device::new(&sys, 61);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let acct = phone.scope("account:");
    let (kai, grp, fld) = (new_id(1, [62; 10]), new_id(2, [63; 10]), new_id(3, [64; 10]));
    phone.create("member.create", &acct, &kai, json!({"name": "Kai", "pronouns": "they/them"})).await;
    phone.create("group.create", &acct, &grp, json!({"name": "Littles", "kind": "group"})).await;
    phone.create("group.add_member", &acct, &grp, json!({"member_id": kai})).await;
    phone.create("field.define", &acct, &fld, json!({"name": "Role", "type": "text"})).await;
    phone
        .create(
            "field.set_value",
            &acct,
            &new_id(4, [65; 10]),
            json!({"member_id": kai, "field_id": fld, "value": "host"}),
        )
        .await;
    phone
        .create(
            "front.switch",
            &acct,
            &new_id(5, [66; 10]),
            json!({"entries": [{"subject_type": "member", "subject_id": kai, "level": "front", "is_primary": true}]}),
        )
        .await;
    phone.drain(Q).await;

    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let session = sys["session"].as_str().unwrap().to_string();
    let get = |path: String, auth: String| {
        let http = http.clone();
        async move {
            let r = http.get(path).bearer_auth(auth).send().await.unwrap();
            (r.status().as_u16(), r.json::<Value>().await.unwrap_or(Value::Null))
        }
    };

    let (st, m) = get(url(&format!("/members/{kai}")), session.clone()).await;
    assert_eq!(st, 200);
    assert_eq!(m["pronouns"], "they/them");
    assert_eq!(m["groups"], json!([grp]));
    assert_eq!(m["fields"][0]["name"], "Role");
    assert_eq!(m["fields"][0]["value"], "host");
    assert_eq!(get(url("/members/nope"), session.clone()).await.0, 404);
    let (_, g) = get(url("/groups"), session.clone()).await;
    assert_eq!(g["items"][0]["name"], "Littles");
    assert_eq!(g["items"][0]["member_ids"], json!([kai]));
    let (_, f) = get(url("/fields"), session.clone()).await;
    assert_eq!(f["items"][0]["type"], "text");
    let (_, i) = get(url(&format!("/front/intervals?subject={kai}&level=front")), session.clone()).await;
    assert_eq!(i["items"].as_array().unwrap().len(), 1);
    let (_, i) = get(url("/front/intervals?level=cocon"), session.clone()).await;
    assert_eq!(i["items"].as_array().unwrap().len(), 0);
    let (_, d) = get(url("/front/daily?level=front"), session.clone()).await;
    assert_eq!(d["items"][0]["subject_id"], kai.as_str());
    assert_eq!(get(url("/front/daily?from=yesterday"), session.clone()).await.0, 400);
    let (st, r) = get(url("/front/reviews?open=1"), session.clone()).await;
    assert_eq!((st, r["items"].as_array().unwrap().len()), (200, 0));
    let (_, me) = get(url("/me"), session.clone()).await;
    assert_eq!((me["via"].as_str(), me["account"]["handle"].as_str()), (Some("device"), Some("stars")));
    assert_eq!(me["devices"].as_array().unwrap().len(), 1);

    // a members-only token: members yes, front no; /me shows its scopes and no devices
    let created: Value = http
        .post(url("/tokens"))
        .bearer_auth(&session)
        .json(&json!({"name": "sheet", "scopes": ["read:members"]}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let token = created["token"].as_str().unwrap().to_string();
    assert_eq!(get(url(&format!("/members/{kai}")), token.clone()).await.0, 200);
    assert_eq!(get(url("/front/daily"), token.clone()).await.0, 403);
    assert_eq!(get(url("/states"), token.clone()).await.0, 403);
    let (_, me) = get(url("/me"), token.clone()).await;
    assert_eq!(me["scopes"], json!(["read:members"]));
    assert!(me["devices"].is_null());
}

#[tokio::test(flavor = "multi_thread")]
async fn a_write_token_logs_switches_by_name() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 71, "stars").await;
    let mut phone = Device::new(&sys, 71);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let acct = phone.scope("account:");
    let kai = new_id(1, [72; 10]);
    phone.create("member.create", &acct, &kai, json!({"name": "Kai"})).await;
    phone.create("member.create", &acct, &new_id(2, [73; 10]), json!({"name": "Rin"})).await;
    phone.create("member.create", &acct, &new_id(3, [74; 10]), json!({"name": "rin"})).await;
    phone.drain(Q).await;

    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let session = sys["session"].as_str().unwrap().to_string();
    let mint = |scopes: Value| {
        let (http, url, session) = (http.clone(), url("/tokens"), session.clone());
        async move {
            let v: Value = http
                .post(url)
                .bearer_auth(session)
                .json(&json!({"name": "nfc", "scopes": scopes}))
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            v["token"].as_str().unwrap().to_string()
        }
    };
    let writer = mint(json!(["write:front"])).await;
    let reader = mint(json!(["read:front"])).await;
    let post = |token: String, body: Value| {
        let (http, url) = (http.clone(), url("/front/switch"));
        async move {
            let r = http.post(url).bearer_auth(token).json(&body).send().await.unwrap();
            (r.status().as_u16(), r.json::<Value>().await.unwrap_or(Value::Null))
        }
    };

    assert_eq!(post(reader.clone(), json!({"entries": [{"member": "Kai"}]})).await.0, 403);
    assert_eq!(post(writer.clone(), json!({"entries": [{"member": "Nobody"}]})).await.0, 400);
    assert_eq!(post(writer.clone(), json!({"entries": [{"member": "RIN"}]})).await.0, 400, "ambiguous");
    assert_eq!(post(writer.clone(), json!({"entries": [{"subject_type": "member", "subject_id": "x"}]})).await.0, 400);
    let future = chorus_server::now_ms() + 3_600_000;
    assert_eq!(post(writer.clone(), json!({"entries": [], "occurred_at": future})).await.0, 400);

    let (st, v) = post(writer.clone(), json!({"entries": [{"member": "kai"}], "note": "tapped the tag"})).await;
    assert_eq!(st, 201);
    assert!(v.get("front").is_none(), "a write-only token doesn't read the front back");
    // the phone gets it like any other switch, attributed to the token's pseudo-device
    phone.drain(Q).await;
    let op = phone.store.confirmed().find(|o| o.kind == "front.switch").unwrap().clone();
    assert!(op.device_id.as_deref().unwrap().starts_with("token:"));
    assert_eq!(op.payload["entries"][0]["subject_id"], kai.as_str());
    assert_eq!(op.payload["entries"][0]["is_primary"], true);
    let front: Value = http.get(url("/front")).bearer_auth(&reader).send().await.unwrap().json().await.unwrap();
    assert_eq!(front["front"][0]["name"], "Kai");

    // a device session may switch out through the same endpoint and reads the result
    let (st, v) = post(session.clone(), json!({"entries": []})).await;
    assert_eq!(st, 201);
    assert_eq!(v["front"].as_array().unwrap().len(), 0);
}

#[tokio::test(flavor = "multi_thread")]
async fn friends_share_spaces_and_dms() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 81, "stars").await;
    let friend = enrol(&s, auth::InviteKind::Person, None, 82, "alex").await;
    let stranger = enrol(&s, auth::InviteKind::Person, None, 83, "sam").await;
    let mut phone = Device::new(&sys, 81);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let mut laptop = Device::new(&friend, 82);
    laptop.connect(&s).await;
    laptop.drain(Q).await;
    let acct = phone.scope("account:");
    let kai = new_id(1, [84; 10]);
    phone.create("member.create", &acct, &kai, json!({"name": "Kai", "color": "#C0694E"})).await;

    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let tok = |e: &Value| e["session"].as_str().unwrap().to_string();
    let id_of = |e: &Value| e["account_id"].as_str().unwrap().to_string();
    let avatar = b"shared-avatar";
    let avatar_hash = format!("{:x}", Sha256::digest(avatar));
    let avatar_url = url(&format!("/blobs/{avatar_hash}"));
    let upload = http
        .put(&avatar_url)
        .bearer_auth(tok(&sys))
        .header("content-range", format!("bytes 0-{}/{}", avatar.len() - 1, avatar.len()))
        .header("content-type", "image/png")
        .body(avatar.as_slice())
        .send()
        .await
        .unwrap();
    assert_eq!(upload.status(), 201);
    phone.create("member.set", &acct, &kai, json!({"avatar_blob": avatar_hash})).await;
    phone.drain(Q).await;
    let post = |path: String, auth: String, body: Value| {
        let http = http.clone();
        async move {
            let r = http.post(path).bearer_auth(auth).json(&body).send().await.unwrap();
            (r.status().as_u16(), r.json::<Value>().await.unwrap_or(Value::Null))
        }
    };

    // alex follows stars and stars accepts: now they're connected
    let (_, f) = post(url("/follows"), tok(&friend), json!({"target": "stars"})).await;
    phone.drain(Q).await;
    phone.create("follow.accept", &acct, f["id"].as_str().unwrap(), json!({})).await;
    phone.drain(Q).await;

    // strangers can't be pulled into a DM
    let (st, _) = post(url("/spaces"), tok(&sys), json!({"kind": "dm", "accounts": [id_of(&stranger)]})).await;
    assert_eq!(st, 403);
    let (st, dm) = post(url("/spaces"), tok(&sys), json!({"kind": "dm", "accounts": [id_of(&friend)]})).await;
    assert_eq!(st, 201);
    let dm = dm["id"].as_str().unwrap().to_string();
    let (st, again) = post(url("/spaces"), tok(&sys), json!({"kind": "dm", "accounts": [id_of(&friend)]})).await;
    assert_eq!((st, again["id"].as_str()), (200, Some(dm.as_str())), "one DM per pair");

    // both sides get the space live; a message from Kai reaches alex
    phone.drain(Q).await;
    laptop.drain(Q).await;
    let scope = format!("space:{dm}");
    assert!(laptop.store.scopes.contains(&scope));
    let p = model::project(phone.store.confirmed());
    let chan = p.rows["channel"].iter().find(|(_, r)| r.fields["space_id"] == dm.as_str()).unwrap().0.clone();
    assert_eq!(
        http.get(&avatar_url).bearer_auth(tok(&friend)).send().await.unwrap().status(),
        403,
        "DM membership alone does not expose unrelated member avatars"
    );
    phone
        .create(
            "message.send",
            &scope,
            &new_id(2, [85; 10]),
            json!({"channel_id": chan, "authors": [kai], "text": "hi alex", "entities": []}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    let p = model::project(laptop.store.confirmed());
    assert!(p.rows["message"].values().any(|m| m.fields["text"] == "hi alex"));

    // alex sees Kai's author card, and nothing about stars' other members
    let cards: Value = http
        .get(url(&format!("/spaces/{dm}/authors")))
        .bearer_auth(tok(&friend))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(cards["members"].as_array().unwrap().len(), 1);
    assert_eq!(cards["members"][0]["name"], "Kai");
    assert_eq!(cards["members"][0]["avatar_blob"], avatar_hash);
    assert_eq!(http.get(&avatar_url).bearer_auth(tok(&friend)).send().await.unwrap().status(), 200);
    assert_eq!(http.get(&avatar_url).bearer_auth(tok(&stranger)).send().await.unwrap().status(), 403);
    assert_eq!(cards["accounts"].as_array().unwrap().len(), 2);
    let r = http.get(url(&format!("/spaces/{dm}/authors"))).bearer_auth(tok(&stranger)).send().await.unwrap();
    assert_eq!(r.status(), 404);
    let list: Value = http.get(url("/spaces")).bearer_auth(tok(&friend)).send().await.unwrap().json().await.unwrap();
    assert!(list["items"].as_array().unwrap().iter().any(|i| i["id"] == dm.as_str() && i["kind"] == "dm"));

    // An aside and its pre-send attachment metadata stay on Stars' devices. A public send
    // backfills its attachment op to Alex; a fresh catch-up has the same filtered digest.
    let private_attachment = new_id(3, [91; 10]);
    phone
        .create(
            "attachment.create",
            &scope,
            &private_attachment,
            json!({"blob_hash": avatar_hash, "filename": "aside.png", "mime": "image/png", "size": avatar.len()}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(!laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(private_attachment.as_str())));
    let aside = new_id(3, [92; 10]);
    phone
        .create(
            "message.send",
            &scope,
            &aside,
            json!({"channel_id": chan, "authors": [kai], "text": "system-only aside", "entities": [],
               "visibility": {"mode": "system_only"}, "attachments": [private_attachment]}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(phone.store.confirmed().any(|o| o.entity_id.as_deref() == Some(aside.as_str())));
    assert!(!laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(aside.as_str())));
    assert!(!laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(private_attachment.as_str())));
    // a thread under the aside is as private as the aside: neither it nor what's said in it leaks
    let aside_thread = new_id(3, [96; 10]);
    phone
        .create(
            "channel.create",
            &scope,
            &aside_thread,
            json!({"space_id": dm, "kind": "thread", "name": "about the aside", "parent_message_id": aside}),
        )
        .await;
    let in_thread = new_id(3, [97; 10]);
    phone
        .create(
            "message.send",
            &scope,
            &in_thread,
            json!({"channel_id": aside_thread, "authors": [kai], "text": "said in the aside's thread", "entities": []}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    for hidden in [&aside_thread, &in_thread] {
        assert!(phone.store.confirmed().any(|o| o.entity_id.as_deref() == Some(hidden.as_str())));
        assert!(!laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(hidden.as_str())), "{hidden}");
    }
    laptop
        .create("message.edit", &scope, &aside, json!({"message_id": aside, "text": "guessed edit", "entities": []}))
        .await;
    laptop.drain(Q).await;
    let stored: String =
        s.state.db.lock().unwrap().query_row("SELECT text FROM message WHERE id=?1", [&aside], |r| r.get(0)).unwrap();
    assert_eq!(stored, "system-only aside", "a guessed id cannot change another account's aside");
    phone
        .create("message.edit", &scope, &aside, json!({"message_id": aside, "text": "aside revised", "entities": []}))
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(!laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(aside.as_str())));

    let public_attachment = new_id(3, [93; 10]);
    phone
        .create(
            "attachment.create",
            &scope,
            &public_attachment,
            json!({"blob_hash": avatar_hash, "filename": "hello.png", "mime": "image/png", "size": avatar.len()}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(!laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(public_attachment.as_str())));
    let public = new_id(3, [94; 10]);
    phone
        .create(
            "message.send",
            &scope,
            &public,
            json!({"channel_id": chan, "authors": [kai], "text": "public with image", "entities": [],
               "visibility": {"mode": "all"}, "attachments": [public_attachment]}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(public.as_str())));
    assert!(laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(public_attachment.as_str())));
    // a thread under a public message is public
    let public_thread = new_id(3, [98; 10]);
    phone
        .create(
            "channel.create",
            &scope,
            &public_thread,
            json!({"space_id": dm, "kind": "thread", "name": "about the image", "parent_message_id": public}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(public_thread.as_str())));

    // REST reads (API.md §3): the friend sees the channels and public messages, not the aside
    // or its thread
    let get = |path: String, auth: String| {
        let http = http.clone();
        async move {
            let r = http.get(path).bearer_auth(auth).send().await.unwrap();
            (r.status().as_u16(), r.json::<Value>().await.unwrap_or(Value::Null))
        }
    };
    let (st, chans) = get(url(&format!("/spaces/{dm}/channels")), tok(&friend)).await;
    assert_eq!(st, 200);
    let ids: Vec<&str> = chans["items"].as_array().unwrap().iter().map(|c| c["id"].as_str().unwrap()).collect();
    assert!(ids.contains(&chan.as_str()) && ids.contains(&public_thread.as_str()), "{ids:?}");
    assert!(!ids.contains(&aside_thread.as_str()), "a thread under an aside isn't listed");
    let (st, msgs) = get(url(&format!("/channels/{chan}/messages")), tok(&friend)).await;
    assert_eq!(st, 200);
    let texts: Vec<&str> = msgs["items"].as_array().unwrap().iter().map(|m| m["text"].as_str().unwrap()).collect();
    assert!(texts.contains(&"hi alex") && texts.contains(&"public with image"), "{texts:?}");
    assert!(!texts.iter().any(|t| t.contains("aside")), "{texts:?}");
    assert_eq!(get(url(&format!("/channels/{aside_thread}/messages")), tok(&friend)).await.0, 404);
    assert_eq!(get(url(&format!("/channels/{chan}/messages")), tok(&stranger)).await.0, 404);
    // `around` a message, and one page back
    let (_, around) = get(url(&format!("/channels/{chan}/messages?around={public}&limit=2")), tok(&friend)).await;
    assert!(around["items"].as_array().unwrap().iter().any(|m| m["id"] == public.as_str()));

    // a bot token writes as Kai (markup parsed); it reads only its own account's messages
    let (_, t) =
        post(url("/tokens"), tok(&sys), json!({"name": "bot", "scopes": ["read:messages", "write:messages"]})).await;
    let bot = t["token"].as_str().unwrap().to_string();
    let (st, sent) = post(
        url(&format!("/channels/{chan}/messages")),
        bot.clone(),
        json!({"text": "from a **bot**", "authors": ["Kai"]}),
    )
    .await;
    assert_eq!(st, 201, "{sent}");
    assert_eq!(sent["message"]["text"], "from a bot");
    assert_eq!(sent["message"]["entities"][0]["type"], "bold");
    laptop.drain(Q).await;
    assert!(laptop.store.confirmed().any(|o| o.entity_id.as_deref() == sent["message_id"].as_str()), "fanned out live");
    // a reply reads back with its link (reply_to_id was never projected before migration 0006)
    let first = sent["message_id"].as_str().unwrap().to_string();
    let (st, reply) = post(
        url(&format!("/channels/{chan}/messages")),
        bot.clone(),
        json!({"text": "and a reply", "authors": ["Kai"], "reply_to": first}),
    )
    .await;
    assert_eq!(st, 201, "{reply}");
    let (_, mine) = get(url(&format!("/channels/{chan}/messages")), bot.clone()).await;
    assert!(mine["items"].as_array().unwrap().iter().all(|m| m["account_id"] == id_of(&sys).as_str()));
    let got = mine["items"].as_array().unwrap().iter().find(|m| m["id"] == reply["message_id"]).unwrap();
    assert_eq!(got["reply_to"], first.as_str(), "{got}");
    let (st, _) = post(url(&format!("/channels/{chan}/messages")), tok(&stranger), json!({"text": "hi"})).await;
    assert_eq!(st, 404, "not in the space");

    laptop.disconnect();
    let later_aside = new_id(3, [95; 10]);
    phone
        .create(
            "message.send",
            &scope,
            &later_aside,
            json!({"channel_id": chan, "authors": [kai], "text": "offline aside", "entities": [],
               "visibility": {"mode": "system_only"}}),
        )
        .await;
    phone.drain(Q).await;
    laptop.connect(&s).await;
    laptop.drain(Q).await;
    assert!(!laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(later_aside.as_str())));
    assert_eq!(laptop.engine.repairs, 0, "visible digest must match the received ops");
    let sys_id = id_of(&sys);
    let second = enrol(&s, auth::InviteKind::Device, Some(&sys_id), 89, "").await;
    let mut other_device = Device::new(&second, 89);
    other_device.connect(&s).await;
    other_device.drain(Q).await;
    assert!(other_device.store.confirmed().any(|o| o.entity_id.as_deref() == Some(aside.as_str())));
    assert!(other_device.store.confirmed().any(|o| o.entity_id.as_deref() == Some(later_aside.as_str())));

    // a shared space: only its owner adds people, and the owner can't leave it
    let (st, club) =
        post(url("/spaces"), tok(&sys), json!({"kind": "shared", "name": "Book club", "accounts": []})).await;
    assert_eq!(st, 201);
    let club = club["id"].as_str().unwrap().to_string();
    let (st, _) =
        post(url(&format!("/spaces/{club}/members")), tok(&friend), json!({"accounts": [id_of(&friend)]})).await;
    assert_eq!(st, 404, "alex isn't in it, so for alex it doesn't exist");
    let r = http
        .post(url(&format!("/spaces/{club}/members")))
        .bearer_auth(tok(&sys))
        .json(&json!({"accounts": [id_of(&friend)]}))
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 204);
    laptop.drain(Q).await;
    assert!(laptop.store.scopes.contains(&format!("space:{club}")));
    let r = http.delete(url(&format!("/spaces/{club}/members/me"))).bearer_auth(tok(&sys)).send().await.unwrap();
    assert_eq!(r.status(), 400);

    // alex leaves the DM: the scope goes away and the cards with it
    let r = http.delete(url(&format!("/spaces/{dm}/members/me"))).bearer_auth(tok(&friend)).send().await.unwrap();
    assert_eq!(r.status(), 204);
    laptop.drain(Q).await;
    assert!(!laptop.store.scopes.contains(&scope));
    let r = http.get(url(&format!("/spaces/{dm}/authors"))).bearer_auth(tok(&friend)).send().await.unwrap();
    assert_eq!(r.status(), 404);
    assert_eq!(
        http.get(&avatar_url).bearer_auth(tok(&friend)).send().await.unwrap().status(),
        403,
        "leaving the space revokes access to its authors' avatars"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn a_lost_device_can_be_signed_out() {
    let s = start().await;
    let a = enrol(&s, auth::InviteKind::System, None, 91, "stars").await;
    let acct = a["account_id"].as_str().unwrap().to_string();
    let lost_e = enrol(&s, auth::InviteKind::Device, Some(&acct), 92, "").await;
    let other = enrol(&s, auth::InviteKind::Person, None, 93, "sam").await;
    let mut lost = Device::new(&lost_e, 92);
    lost.connect(&s).await;
    lost.drain(Q).await;

    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let tok = |e: &Value| e["session"].as_str().unwrap().to_string();
    let dev = |e: &Value| e["device_id"].as_str().unwrap().to_string();
    let revoke = |who: &Value, id: String| {
        let (http, url, t) = (http.clone(), url(&format!("/devices/{id}/revoke")), tok(who));
        async move { http.post(url).bearer_auth(t).send().await.unwrap().status().as_u16() }
    };

    let me: Value = http.get(url("/me")).bearer_auth(tok(&a)).send().await.unwrap().json().await.unwrap();
    assert_eq!(me["devices"].as_array().unwrap().len(), 2);
    assert_eq!(revoke(&a, dev(&a)).await, 400, "not the device you're on");
    assert_eq!(revoke(&other, dev(&lost_e)).await, 404, "not someone else's device");
    assert_eq!(revoke(&a, dev(&lost_e)).await, 204);

    // its session no longer works, and its open socket is closed on the next frame
    assert_eq!(http.get(url("/me")).bearer_auth(tok(&lost_e)).send().await.unwrap().status(), 401);
    let ws = lost.ws.as_mut().unwrap();
    let ping = Frame::Ping { clock: ClockReading { wall: chorus_server::now_ms(), mono: None, boot_id: None } };
    ws.send(Message::Text(serde_json::to_string(&ping).unwrap().into())).await.unwrap();
    let reply = tokio::time::timeout(Duration::from_secs(2), ws.next()).await.unwrap().unwrap().unwrap();
    let f: Frame = serde_json::from_str(reply.to_text().unwrap()).unwrap();
    assert!(matches!(f, Frame::Error { ref code, .. } if code == "unauthenticated"), "{f:?}");
    let me: Value = http.get(url("/me")).bearer_auth(tok(&a)).send().await.unwrap().json().await.unwrap();
    assert_eq!(me["devices"].as_array().unwrap().len(), 1);
}

/// A socket that never says Hello is closed, not held open (a public server gets strangers).
#[tokio::test(flavor = "multi_thread")]
async fn a_silent_socket_is_closed() {
    let s = start().await;
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/api/v1/sync", s.base)).await.unwrap();
    let t = std::time::Instant::now();
    let end = tokio::time::timeout(Duration::from_secs(25), async {
        while let Some(Ok(m)) = ws.next().await {
            if matches!(m, Message::Close(_)) {
                break;
            }
        }
    })
    .await;
    assert!(end.is_ok(), "still open after 25 s");
    assert!(t.elapsed() >= Duration::from_secs(14), "closed too early: {:?}", t.elapsed());
}

/// API.md §1: each credential gets a burst, then 429 `rate_limited` with Retry-After.
#[tokio::test]
async fn requests_over_the_rate_limit_get_429() {
    let mut cfg = Config::default();
    cfg.security.rate_burst = Some(3);
    cfg.security.rate_per_second = Some(0.01);
    let s = start_with(cfg).await;
    let http = reqwest::Client::new();
    let me = |token: &str| http.get(format!("http://{}/api/v1/me", s.base)).bearer_auth(token.to_string()).send();
    for _ in 0..3 {
        assert_ne!(me("one").await.unwrap().status(), 429);
    }
    let r = me("one").await.unwrap();
    assert_eq!(r.status(), 429);
    assert!(r.headers()["retry-after"].to_str().unwrap().parse::<u64>().unwrap() >= 1);
    let body: Value = r.json().await.unwrap();
    assert_eq!(body["error"]["code"], "rate_limited");
    // another credential has its own bucket
    assert_ne!(me("two").await.unwrap().status(), 429);
}

/// Sign-in is limited per client address (the served app knows the peer address).
#[tokio::test]
async fn sign_in_is_limited_per_address() {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let state = app::Shared::new(conn, Config::default()).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let router = app::router(state).into_make_service_with_connect_info::<std::net::SocketAddr>();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    let http = reqwest::Client::new();
    let mut codes = Vec::new();
    for _ in 0..25 {
        let r = http.post(format!("http://{addr}/api/v1/auth/challenge")).json(&json!({})).send().await.unwrap();
        codes.push(r.status().as_u16());
    }
    assert!(codes[..20].iter().all(|c| *c != 429), "{codes:?}");
    assert_eq!(codes[24], 429, "{codes:?}");
}

impl Device {
    /// Process frames until `done` holds (true) or `max` passes (false).
    async fn until(&mut self, max: Duration, done: impl Fn(&MemStore) -> bool) -> bool {
        let end = tokio::time::Instant::now() + max;
        while !done(&self.store) {
            let ws = self.ws.as_mut().unwrap();
            let msg = match tokio::time::timeout_at(end, ws.next()).await {
                Ok(Some(Ok(Message::Text(t)))) => t,
                Ok(Some(Ok(_))) => continue,
                _ => return false,
            };
            let f: Frame = serde_json::from_str(&msg).unwrap();
            let out = self.engine.on_frame(&mut self.store, f);
            self.send(out).await;
        }
        true
    }
}

/// SPEC §9: a switch reaches the account's other online device within 1 s (p95), and a device
/// back from a week offline with 5 000 queued ops is fully synced within 10 s. Release build:
/// `cargo test --release -p chorus-server --test sync_e2e sync_budgets -- --ignored --nocapture`
#[tokio::test(flavor = "multi_thread")]
#[ignore]
async fn sync_budgets() {
    let s = start().await;
    let a = enrol(&s, auth::InviteKind::System, None, 1, "stars").await;
    let acct = a["account_id"].as_str().unwrap().to_string();
    let desk_e = enrol(&s, auth::InviteKind::Device, Some(&acct), 2, "").await;
    let mut phone = Device::new(&a, 1);
    let mut desk = Device::new(&desk_e, 2);
    phone.connect(&s).await;
    phone.drain(Q).await;
    desk.connect(&s).await;
    desk.drain(Q).await;
    let acct_scope = format!("account:{acct}");
    let space = phone.scope("space:");
    let general =
        phone.store.confirmed().find(|o| o.kind == "channel.create").and_then(|o| o.entity_id.clone()).unwrap();
    let mut members = Vec::new();
    for i in 0..20u8 {
        let m = new_id(1, [i + 1; 10]);
        phone.create("member.create", &acct_scope, &m, json!({"name": format!("M{i}")})).await;
        members.push(m);
    }
    phone.drain(Q).await;
    desk.drain(Q).await;

    // switch → the other device
    let mut lat = Vec::new();
    for i in 0..40usize {
        let t = std::time::Instant::now();
        let sw = new_id(chorus_server::now_ms() as u64, rand::random());
        let entries = json!([{"subject_type": "member", "subject_id": members[i % 20], "is_primary": true}]);
        let op = phone.create("front.switch", &acct_scope, &sw, json!({"entries": entries})).await;
        let seen = desk.until(Duration::from_secs(5), |st| st.confirmed().any(|o| o.id == op)).await;
        assert!(seen, "switch {i} never arrived");
        lat.push(t.elapsed());
        phone.drain(Duration::from_millis(20)).await;
    }
    lat.sort();
    let p95 = lat[lat.len() * 95 / 100 - 1];
    println!("switch → other device: median {:?}, p95 {:?}", lat[lat.len() / 2], p95);

    // a week offline: 5 000 queued on the desk while the phone writes 1 000
    desk.disconnect();
    let msg = |i: usize| json!({"channel_id": general, "authors": [members[i % 20]], "text": format!("offline note {i}"), "entities": []});
    for i in 0..5000usize {
        let id = new_id(chorus_server::now_ms() as u64, rand::random());
        if i % 25 == 0 {
            let entries = json!([{"subject_type": "member", "subject_id": members[i % 20], "is_primary": true}]);
            desk.create("front.switch", &acct_scope, &id, json!({"entries": entries})).await;
        } else {
            desk.create("message.send", &space, &id, msg(i)).await;
        }
    }
    for i in 0..1000usize {
        let id = new_id(chorus_server::now_ms() as u64, rand::random());
        phone.create("message.send", &space, &id, msg(i)).await;
    }
    phone.drain(Duration::from_millis(200)).await;
    let total = phone.store.confirmed().count() + 5000;
    let t = std::time::Instant::now();
    desk.connect(&s).await;
    let synced = desk
        .until(Duration::from_secs(60), |st| {
            st.pending(&Default::default(), 1, false).is_empty() && st.confirmed().count() >= total
        })
        .await;
    let took = t.elapsed();
    assert!(synced, "desk never caught up");
    assert!(phone.until(Duration::from_secs(60), |st| st.confirmed().count() >= total).await, "phone never caught up");
    let both = t.elapsed();
    println!("reconnect with 5 000 queued (+1 000 to fetch): desk synced in {took:?}, phone has them in {both:?}");
    assert!(p95 <= Duration::from_secs(1), "SPEC §9: switch visible on other devices ≤ 1 s p95");
    assert!(both <= Duration::from_secs(10), "SPEC §9: reconnect with 5 000 queued ops ≤ 10 s");
}

/// The web app is served with a strict Content-Security-Policy; API responses aren't pages.
#[tokio::test]
async fn the_web_app_carries_a_content_security_policy() {
    let dir = std::env::temp_dir().join(format!("chorus-web-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("index.html"), "<!doctype html><title>Chorus</title>").unwrap();
    let mut cfg = Config::default();
    cfg.server.web_dir = Some(dir.clone());
    let s = start_with(cfg).await;
    let http = reqwest::Client::new();
    for path in ["/", "/some/app/route"] {
        let r = http.get(format!("http://{}{path}", s.base)).send().await.unwrap();
        assert_eq!(r.status(), 200);
        let csp = r.headers()["content-security-policy"].to_str().unwrap().to_string();
        assert!(csp.contains("default-src 'self'") && csp.contains("'wasm-unsafe-eval'"), "{csp}");
    }
    let api = http.get(format!("http://{}/api/v1/server", s.base)).send().await.unwrap();
    assert!(api.headers().get("content-security-policy").is_none());
    let _ = std::fs::remove_dir_all(dir);
}

/// [`start_with`], but the server knows each client's address (as behind `serve`).
async fn start_with_addresses(cfg: Config) -> Server {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    db::set_meta(&conn, "instance_id", "test").unwrap();
    db::set_meta(&conn, "epoch", "1").unwrap();
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let router = app::router(state.clone()).into_make_service_with_connect_info::<std::net::SocketAddr>();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    Server { base: format!("127.0.0.1:{}", addr.port()), state }
}

/// A raw sync socket signed in with `token`, read up to its Welcome.
async fn signed_socket(s: &Server, token: &str, device: &str) -> Ws {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/api/v1/sync", s.base)).await.unwrap();
    let clock = ClockReading { wall: chorus_server::now_ms(), mono: None, boot_id: None };
    let hello = ClientEngine::new(device).on_connect(&MemStore::default(), clock, token);
    ws.send(Message::Text(serde_json::to_string(&hello).unwrap().into())).await.unwrap();
    loop {
        match next_frame(&mut ws).await {
            Some(Frame::Welcome { .. }) => return ws,
            Some(Frame::Error { message, .. }) => panic!("hello refused: {message}"),
            Some(_) => {}
            None => panic!("closed before welcome"),
        }
    }
}

/// The next frame, or `None` once the socket is closed (or quiet for 5 s).
async fn next_frame(ws: &mut Ws) -> Option<Frame> {
    loop {
        match tokio::time::timeout(Duration::from_secs(5), ws.next()).await {
            Ok(Some(Ok(Message::Text(t)))) => return Some(serde_json::from_str(&t).unwrap()),
            Ok(Some(Ok(Message::Close(_)) | Err(_))) | Ok(None) | Err(_) => return None,
            Ok(Some(Ok(_))) => {}
        }
    }
}

/// Is the socket still served? (A ping gets its pong.)
async fn answers(ws: &mut Ws) -> bool {
    let ping =
        serde_json::to_string(&Frame::Ping { clock: ClockReading { wall: 0, mono: None, boot_id: None } }).unwrap();
    if ws.send(Message::Text(ping.into())).await.is_err() {
        return false;
    }
    loop {
        match next_frame(ws).await {
            Some(Frame::Pong { .. }) => return true,
            Some(Frame::Error { .. }) | None => return false,
            Some(_) => {}
        }
    }
}

/// OPS.md §9: an account keeps at most `sync_sockets_per_account` sockets; a new one closes the
/// oldest with `too_many_connections`.
#[tokio::test(flavor = "multi_thread")]
async fn an_account_has_a_limited_number_of_sync_sockets() {
    let mut cfg = Config::default();
    cfg.security.sync_sockets_per_account = Some(2);
    let s = start_with(cfg).await;
    let a = enrol(&s, auth::InviteKind::System, None, 1, "stars").await;
    let (token, device) = (a["session"].as_str().unwrap(), a["device_id"].as_str().unwrap());
    let mut first = signed_socket(&s, token, device).await;
    let mut second = signed_socket(&s, token, device).await;
    assert!(answers(&mut first).await && answers(&mut second).await);
    let mut third = signed_socket(&s, token, device).await;
    let closed = loop {
        match next_frame(&mut first).await {
            Some(Frame::Error { code, .. }) => break code,
            Some(_) => {}
            None => panic!("closed without saying why"),
        }
    };
    assert_eq!(closed, "too_many_connections");
    assert!(next_frame(&mut first).await.is_none(), "the oldest socket is closed");
    assert!(answers(&mut second).await && answers(&mut third).await, "the newer ones stay");

    // the device's newest socket still gets live ops after the oldest went away
    let friend_e = enrol(&s, auth::InviteKind::Device, Some(a["account_id"].as_str().unwrap()), 2, "").await;
    let mut other = Device::new(&friend_e, 2);
    other.connect(&s).await;
    other.drain(Q).await;
    let acct_scope = format!("account:{}", a["account_id"].as_str().unwrap());
    other.create("member.create", &acct_scope, &new_id(1, [7; 10]), json!({"name": "Kai"})).await;
    let delivered = loop {
        match next_frame(&mut third).await {
            Some(Frame::Ops { ops, .. }) if ops.iter().any(|o| o.kind == "member.create") => break true,
            Some(_) => {}
            None => break false,
        }
    };
    assert!(delivered, "the newest socket stays registered for fan-out");
}

/// OPS.md §9: sockets that haven't signed in are limited per client address; signing in frees
/// the slot.
#[tokio::test(flavor = "multi_thread")]
async fn unsigned_sync_sockets_are_limited_per_address() {
    let mut cfg = Config::default();
    cfg.security.sync_sockets_per_address = Some(2);
    let s = start_with_addresses(cfg).await;
    let a = enrol(&s, auth::InviteKind::System, None, 1, "stars").await;
    let url = format!("ws://{}/api/v1/sync", s.base);
    let (mut one, _) = tokio_tungstenite::connect_async(&url).await.unwrap();
    let (_two, _) = tokio_tungstenite::connect_async(&url).await.unwrap();
    match tokio_tungstenite::connect_async(&url).await {
        Err(tokio_tungstenite::tungstenite::Error::Http(r)) => assert_eq!(r.status(), 429),
        other => panic!("a third unsigned socket was let in: {:?}", other.map(|(_, r)| r.status())),
    }
    // signing in frees a slot
    let clock = ClockReading { wall: chorus_server::now_ms(), mono: None, boot_id: None };
    let hello = ClientEngine::new(a["device_id"].as_str().unwrap()).on_connect(
        &MemStore::default(),
        clock,
        a["session"].as_str().unwrap(),
    );
    one.send(Message::Text(serde_json::to_string(&hello).unwrap().into())).await.unwrap();
    assert!(matches!(next_frame(&mut one).await, Some(Frame::Welcome { .. })));
    let (_three, _) = tokio_tungstenite::connect_async(&url).await.unwrap();
    // and so does going away
    drop(_two);
    let mut let_in = false;
    for _ in 0..50 {
        if tokio_tungstenite::connect_async(&url).await.is_ok() {
            let_in = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(let_in, "a closed socket's slot comes back");
}

/// OPS.md §9: a socket sending frames faster than its budget is closed with `rate_limited`.
#[tokio::test(flavor = "multi_thread")]
async fn a_socket_over_its_frame_budget_is_closed() {
    let mut cfg = Config::default();
    cfg.security.sync_frame_burst = Some(5);
    cfg.security.sync_frames_per_second = Some(1.0);
    let s = start_with(cfg).await;
    let a = enrol(&s, auth::InviteKind::System, None, 1, "stars").await;
    let mut ws = signed_socket(&s, a["session"].as_str().unwrap(), a["device_id"].as_str().unwrap()).await;
    let ping =
        serde_json::to_string(&Frame::Ping { clock: ClockReading { wall: 0, mono: None, boot_id: None } }).unwrap();
    for _ in 0..10 {
        if ws.send(Message::Text(ping.clone().into())).await.is_err() {
            break;
        }
    }
    let mut pongs = 0;
    let code = loop {
        match next_frame(&mut ws).await {
            Some(Frame::Pong { .. }) => pongs += 1,
            Some(Frame::Error { code, .. }) => break code,
            Some(_) => {}
            None => panic!("closed without saying why"),
        }
    };
    assert_eq!(code, "rate_limited");
    assert_eq!(pongs, 4, "hello + 4 pings fit the burst of 5");
    assert!(next_frame(&mut ws).await.is_none(), "then the socket is closed");
}

/// API.md §2: "Send a test" pushes to the calling device only, encrypted to its keys.
#[tokio::test]
async fn a_device_can_send_itself_a_test_notification() {
    use chorus_server::push;
    use p256::elliptic_curve::sec1::ToSec1Point;
    // a push service on the loopback
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Vec<u8>>();
    let service = axum::Router::new().route(
        "/push/{who}",
        axum::routing::post(move |body: axum::body::Bytes| {
            let tx = tx.clone();
            async move {
                let _ = tx.send(body.to_vec());
                axum::http::StatusCode::CREATED
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let endpoint = format!("http://{}/push/", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, service).await.unwrap() });

    // a default server refuses a push endpoint on its own machine (R16, push.rs `check_endpoint`)
    let http = reqwest::Client::new();
    let session = |d: &Value| d["session"].as_str().unwrap().to_string();
    let keys: Vec<p256::SecretKey> = [7u8, 9].iter().map(|b| p256::SecretKey::from_slice(&[*b; 32]).unwrap()).collect();
    let reg = |endpoint: String, key: &p256::SecretKey| {
        json!({
            "endpoint": endpoint,
            "p256dh": push::b64url(key.public_key().to_sec1_point(false).as_bytes()),
            "auth": push::b64url(&[3; 16]),
        })
    };
    let strict = start().await;
    let someone = enrol(&strict, auth::InviteKind::System, None, 63, "moon").await;
    for bad in [format!("{endpoint}x"), "https://127.0.0.1:9/push/x".to_string()] {
        let put = http.put(format!("http://{}/api/v1/devices/push", strict.base)).bearer_auth(session(&someone));
        let r = put.json(&reg(bad.clone(), &keys[0])).send().await.unwrap();
        assert_eq!(r.status(), 400, "{bad} refused");
    }

    // the rest runs with `webhook_targets = any` (tests, or an owner who knows what's listening)
    let mut cfg = Config::default();
    cfg.security.webhook_targets = Some(chorus_server::config::WebhookTargets::Any);
    let s = start_with(cfg).await;
    let phone = enrol(&s, auth::InviteKind::System, None, 61, "stars").await;
    let account = phone["account_id"].as_str().unwrap();
    let laptop = enrol(&s, auth::InviteKind::Device, Some(account), 62, "stars").await;
    let url = format!("http://{}/api/v1/devices/push/test", s.base);

    // no registration yet
    let r = http.post(&url).bearer_auth(session(&phone)).send().await.unwrap();
    assert_eq!(r.status(), 409);
    let body: Value = r.json().await.unwrap();
    assert_eq!(body["error"]["code"], "no_push");

    // both devices register; only the caller gets the test
    for (dev, (key, who)) in [&phone, &laptop].into_iter().zip(keys.iter().zip(["phone", "laptop"])) {
        let put = http
            .put(format!("http://{}/api/v1/devices/push", s.base))
            .bearer_auth(session(dev))
            .json(&reg(format!("{endpoint}{who}"), key));
        assert_eq!(put.send().await.unwrap().status(), 204);
    }
    assert_eq!(http.post(&url).bearer_auth(session(&phone)).send().await.unwrap().status(), 204);
    let got = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await.unwrap().unwrap();
    let plain = push::decrypt(&keys[0], &[3; 16], &got).expect("encrypted to the phone's keys");
    let v: Value = serde_json::from_slice(&plain).unwrap();
    assert_eq!(v["t"], "test");
    assert!(push::decrypt(&keys[1], &[3; 16], &got).is_none(), "not to the laptop's");
    assert!(rx.try_recv().is_err(), "one push, to the caller only");
}

/// Channel permissions (M5.10, perms.rs) end to end: a private channel in a shared space stays
/// out of a member's sync, REST and search; a member without `manage` can't make channels; and
/// one channel of the owner's internal space is shared with an outside account, which then gets
/// that channel and nothing else of the space, and can talk there once allowed to.
#[tokio::test(flavor = "multi_thread")]
async fn channel_permissions_through_the_real_server() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 91, "stars").await;
    let friend = enrol(&s, auth::InviteKind::Person, None, 92, "alex").await;
    let mut phone = Device::new(&sys, 91);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let mut laptop = Device::new(&friend, 92);
    laptop.connect(&s).await;
    laptop.drain(Q).await;
    let acct = phone.scope("account:");
    let home = phone.scope("space:");
    let kai = new_id(1, [93; 10]);
    phone.create("member.create", &acct, &kai, json!({"name": "Kai"})).await;
    let alex_member = model::project(laptop.store.confirmed())
        .rows
        .get("member")
        .and_then(|m| m.keys().next().cloned())
        .expect("a person account has its own member");
    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let tok = |e: &Value| e["session"].as_str().unwrap().to_string();
    let alex = friend["account_id"].as_str().unwrap().to_string();
    let r =
        http.post(url("/follows")).bearer_auth(tok(&friend)).json(&json!({"target": "stars"})).send().await.unwrap();
    let f: Value = r.json().await.unwrap();
    phone.drain(Q).await;
    phone.create("follow.accept", &acct, f["id"].as_str().unwrap(), json!({})).await;
    phone.drain(Q).await;
    let texts = |d: &Device| -> Vec<String> {
        model::project(d.store.confirmed())
            .rows
            .get("message")
            .map(|m| {
                m.values().filter_map(|r| r.fields.get("text").and_then(Value::as_str).map(str::to_string)).collect()
            })
            .unwrap_or_default()
    };
    let say = |id: u8, channel: &str, text: &str, author: &str| {
        (new_id(3, [id; 10]), json!({"channel_id": channel, "authors": [author], "text": text, "entities": []}))
    };

    // a shared space with a private channel only the owner (and admins) can see
    let r = http
        .post(url("/spaces"))
        .bearer_auth(tok(&sys))
        .json(&json!({"kind": "shared", "name": "Club", "accounts": [alex]}))
        .send()
        .await
        .unwrap();
    let club = r.json::<Value>().await.unwrap()["id"].as_str().unwrap().to_string();
    let club_scope = format!("space:{club}");
    phone.drain(Q).await;
    laptop.drain(Q).await;
    let secret = new_id(3, [1; 10]);
    phone
        .create("channel.create", &club_scope, &secret, json!({"space_id": club, "kind": "text", "name": "mods"}))
        .await;
    phone
        .create(
            "channel.set_permission",
            &club_scope,
            &secret,
            json!({"target_type": "role", "target_id": "everyone", "allow": [], "deny": ["view"]}),
        )
        .await;
    let (m, p) = say(2, &secret, "mods only", &kai);
    phone.create("message.send", &club_scope, &m, p).await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(!texts(&laptop).contains(&"mods only".to_string()), "not synced live");
    let r = http.get(url(&format!("/channels/{secret}/messages"))).bearer_auth(tok(&friend)).send().await.unwrap();
    assert_eq!(r.status(), 404, "nor over REST");
    let hits: Value =
        http.get(url("/search/messages?q=mods")).bearer_auth(tok(&friend)).send().await.unwrap().json().await.unwrap();
    assert_eq!(hits["items"], json!([]), "nor in search");
    // alex can't post there, nor make channels (members don't have `manage`)
    let (m, p) = say(3, &secret, "let me in", &alex_member);
    let refused = laptop.create("message.send", &club_scope, &m, p).await;
    let made = new_id(3, [4; 10]);
    let refused_channel = laptop
        .create("channel.create", &club_scope, &made, json!({"space_id": club, "kind": "text", "name": "mine"}))
        .await;
    laptop.drain(Q).await;
    assert_eq!(laptop.store.rejected.get(&refused).map(|e| e.code.as_str()), Some("forbidden"));
    assert_eq!(laptop.store.rejected.get(&refused_channel).map(|e| e.code.as_str()), Some("forbidden"));
    // once allowed, a connected device repairs onto it live, and a fresh device catches up on it
    phone
        .create(
            "channel.set_permission",
            &club_scope,
            &secret,
            json!({"target_type": "account", "target_id": alex, "allow": ["view"], "deny": []}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(texts(&laptop).contains(&"mods only".to_string()), "gaining view repairs a connected device");
    let fresh = enrol(&s, auth::InviteKind::Device, Some(&alex), 94, "").await;
    let mut tablet = Device::new(&fresh, 94);
    tablet.connect(&s).await;
    tablet.drain(Q).await;
    assert!(texts(&tablet).contains(&"mods only".to_string()), "an allowed account gets it in catch-up");
    // and losing it again evicts the channel from every connected device
    phone
        .create(
            "channel.set_permission",
            &club_scope,
            &secret,
            json!({"target_type": "account", "target_id": alex, "allow": [], "deny": ["view"]}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    tablet.drain(Q).await;
    assert!(!texts(&laptop).contains(&"mods only".to_string()), "losing view evicts it");
    assert!(!texts(&tablet).contains(&"mods only".to_string()), "on every device");

    // one channel of the internal space, shared with alex (a guest: not in the space)
    let news = new_id(3, [5; 10]);
    phone
        .create(
            "channel.create",
            &home,
            &news,
            json!({"space_id": home.strip_prefix("space:").unwrap(), "kind": "text", "name": "news"}),
        )
        .await;
    let general = model::project(phone.store.confirmed()).rows["channel"]
        .iter()
        .find(|(_, r)| {
            r.fields.get("name").and_then(Value::as_str) == Some("general")
                && r.fields.get("space_id").and_then(Value::as_str) == home.strip_prefix("space:")
        })
        .map(|(id, _)| id.clone())
        .unwrap();
    for (i, (channel, text)) in [(&general, "inside only"), (&news, "we moved!")].into_iter().enumerate() {
        let (m, p) = say(10 + i as u8, channel, text, &kai);
        phone.create("message.send", &home, &m, p).await;
    }
    phone
        .create(
            "channel.set_permission",
            &home,
            &news,
            json!({"target_type": "account", "target_id": alex, "allow": ["view", "react"], "deny": []}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(laptop.store.scopes.contains(&home), "the guest gets the space's scope");
    let spaces: Value = http.get(url("/spaces")).bearer_auth(tok(&friend)).send().await.unwrap().json().await.unwrap();
    let listed =
        spaces["items"].as_array().unwrap().iter().find(|i| format!("space:{}", i["id"].as_str().unwrap()) == home);
    assert_eq!(listed.map(|i| i["guest"].clone()), Some(json!(true)), "listed as a space alex is a guest in");
    let seen = texts(&laptop);
    assert!(seen.contains(&"we moved!".to_string()), "and the shared channel");
    assert!(!seen.contains(&"inside only".to_string()), "but nothing else of the space");
    let r = http.get(url(&format!("/channels/{general}/messages"))).bearer_auth(tok(&friend)).send().await.unwrap();
    assert_eq!(r.status(), 404);
    // a guest may react but not talk until allowed
    let (m, p) = say(20, &news, "congrats", &alex_member);
    let refused = laptop.create("message.send", &home, &m, p).await;
    laptop.drain(Q).await;
    assert_eq!(laptop.store.rejected.get(&refused).map(|e| e.code.as_str()), Some("forbidden"));
    phone
        .create(
            "channel.set_permission",
            &home,
            &news,
            json!({"target_type": "account", "target_id": alex, "allow": ["view", "react", "send"], "deny": []}),
        )
        .await;
    phone.drain(Q).await;
    let (m, p) = say(21, &news, "congrats!", &alex_member);
    laptop.create("message.send", &home, &m, p).await;
    laptop.drain(Q).await;
    phone.drain(Q).await;
    assert!(texts(&phone).contains(&"congrats!".to_string()), "the owner hears the guest");
    // taking view away ends the guest's scope
    phone
        .create(
            "channel.set_permission",
            &home,
            &news,
            json!({"target_type": "account", "target_id": alex, "allow": [], "deny": []}),
        )
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;
    assert!(!laptop.store.scopes.contains(&home), "no view left: no scope");
}

/// SPEC §5.3: an edited message's history, over REST with the message's own read rule (API.md
/// §3): the friend sees every version of a public message, nothing of an aside's; a stranger
/// nothing. Posts keep theirs too.
#[tokio::test(flavor = "multi_thread")]
async fn edit_history_follows_the_message_read_rule() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 101, "stars").await;
    let friend = enrol(&s, auth::InviteKind::Person, None, 102, "alex").await;
    let stranger = enrol(&s, auth::InviteKind::Person, None, 103, "sam").await;
    let mut phone = Device::new(&sys, 101);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let acct = phone.scope("account:");
    let kai = new_id(1, [104; 10]);
    phone.create("member.create", &acct, &kai, json!({"name": "Kai"})).await;
    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let tok = |e: &Value| e["session"].as_str().unwrap().to_string();
    let f: Value = http
        .post(url("/follows"))
        .bearer_auth(tok(&friend))
        .json(&json!({"target": "stars"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    phone.drain(Q).await;
    phone.create("follow.accept", &acct, f["id"].as_str().unwrap(), json!({})).await;
    phone.drain(Q).await;
    let dm: Value = http
        .post(url("/spaces"))
        .bearer_auth(tok(&sys))
        .json(&json!({"kind": "dm", "accounts": [friend["account_id"]]}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    phone.drain(Q).await;
    let scope = format!("space:{}", dm["id"].as_str().unwrap());
    let chan = model::project(phone.store.confirmed()).rows["channel"]
        .iter()
        .find(|(_, r)| r.fields["space_id"] == dm["id"])
        .map(|(id, _)| id.clone())
        .unwrap();
    let say = |text: &str, aside: bool| {
        let mut p = json!({"channel_id": chan, "authors": [kai], "text": text, "entities": []});
        if aside {
            p["visibility"] = json!({"mode": "system_only"});
        }
        p
    };
    let public = new_id(2, [105; 10]);
    let aside = new_id(2, [106; 10]);
    phone.create("message.send", &scope, &public, say("helo", false)).await;
    phone.create("message.send", &scope, &aside, say("secret", true)).await;
    for (m, text) in [(&public, "hello"), (&public, "hello!"), (&aside, "still secret")] {
        phone.create("message.edit", &scope, m, json!({"message_id": m, "text": text, "entities": []})).await;
    }
    phone.drain(Q).await;
    let get = |path: String, auth: String| {
        let http = http.clone();
        async move {
            let r = http.get(path).bearer_auth(auth).send().await.unwrap();
            (r.status().as_u16(), r.json::<Value>().await.unwrap_or(Value::Null))
        }
    };
    let texts = |v: &Value| -> Vec<String> {
        v["items"].as_array().unwrap().iter().map(|i| i["text"].as_str().unwrap().to_string()).collect()
    };
    let (st, h) = get(url(&format!("/messages/{public}/revisions")), tok(&friend)).await;
    assert_eq!(st, 200, "{h}");
    assert_eq!(texts(&h), vec!["helo", "hello", "hello!"]);
    assert_eq!(h["items"][2]["rev"], 2);
    assert_eq!(get(url(&format!("/messages/{aside}/revisions")), tok(&friend)).await.0, 404, "not an aside's");
    assert_eq!(get(url(&format!("/messages/{public}/revisions")), tok(&stranger)).await.0, 404);
    let (_, mine) = get(url(&format!("/messages/{aside}/revisions")), tok(&sys)).await;
    assert_eq!(texts(&mine), vec!["secret", "still secret"], "its own account sees it");
    // an unedited message has one version: itself
    let plain = new_id(2, [107; 10]);
    phone.create("message.send", &scope, &plain, say("just once", false)).await;
    phone.drain(Q).await;
    let (_, once) = get(url(&format!("/messages/{plain}/revisions")), tok(&friend)).await;
    assert_eq!(texts(&once), vec!["just once"]);

    // posts: the author's own device reads a post's versions (titles included)
    let post = new_id(3, [108; 10]);
    let entry = |title: &str, text: &str| {
        json!({"kind": "entry", "authors": [kai], "title": title, "text": text, "entities": [],
               "visibility": {"mode": "private"}})
    };
    phone.create("post.create", &acct, &post, entry("Day", "a good day")).await;
    phone
        .create(
            "post.edit",
            &acct,
            &post,
            json!({"post_id": post, "title": "Day one", "text": "a good day", "entities": []}),
        )
        .await;
    phone.drain(Q).await;
    let (st, h) = get(url(&format!("/posts/{post}/revisions")), tok(&sys)).await;
    assert_eq!(st, 200, "{h}");
    let titles: Vec<&str> = h["items"].as_array().unwrap().iter().map(|i| i["title"].as_str().unwrap()).collect();
    assert_eq!(titles, vec!["Day", "Day one"]);
    assert_eq!(get(url(&format!("/posts/{post}/revisions")), tok(&friend)).await.0, 404, "a private entry");
}

/// SPEC §5.3 reply elsewhere / reply privately: a reply in another channel (or another space's
/// DM) links back to its original only for readers who can read the original; nobody else learns
/// it exists, over REST or sync.
#[tokio::test(flavor = "multi_thread")]
async fn a_reply_elsewhere_links_back_only_for_those_who_can_read_the_original() {
    let s = start().await;
    let sys = enrol(&s, auth::InviteKind::System, None, 111, "stars").await;
    let friend = enrol(&s, auth::InviteKind::Person, None, 112, "alex").await;
    let mut phone = Device::new(&sys, 111);
    phone.connect(&s).await;
    phone.drain(Q).await;
    let mut laptop = Device::new(&friend, 112);
    laptop.connect(&s).await;
    laptop.drain(Q).await;
    let acct = phone.scope("account:");
    let kai = new_id(1, [113; 10]);
    phone.create("member.create", &acct, &kai, json!({"name": "Kai"})).await;
    let http = reqwest::Client::new();
    let url = |p: &str| format!("http://{}/api/v1{p}", s.base);
    let tok = |e: &Value| e["session"].as_str().unwrap().to_string();
    let alex = friend["account_id"].as_str().unwrap().to_string();
    let f: Value = http
        .post(url("/follows"))
        .bearer_auth(tok(&friend))
        .json(&json!({"target": "stars"}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    phone.drain(Q).await;
    phone.create("follow.accept", &acct, f["id"].as_str().unwrap(), json!({})).await;
    phone.drain(Q).await;
    let post = |path: String, body: Value| {
        let http = http.clone();
        let t = tok(&sys);
        async move { http.post(path).bearer_auth(t).json(&body).send().await.unwrap().json::<Value>().await.unwrap() }
    };
    let club = post(url("/spaces"), json!({"kind": "shared", "name": "Club", "accounts": [alex]})).await;
    let club_id = club["id"].as_str().unwrap().to_string();
    let dm = post(url("/spaces"), json!({"kind": "dm", "accounts": [alex]})).await;
    let dm_id = dm["id"].as_str().unwrap().to_string();
    phone.drain(Q).await;
    laptop.drain(Q).await;
    let club_scope = format!("space:{club_id}");
    let channel_in = |d: &Device, space: &str, name: Option<&str>| {
        model::project(d.store.confirmed()).rows["channel"]
            .iter()
            .find(|(_, r)| {
                r.fields["space_id"] == space
                    && name.is_none_or(|n| r.fields.get("name").and_then(Value::as_str) == Some(n))
            })
            .map(|(id, _)| id.clone())
            .unwrap()
    };
    let general = channel_in(&phone, &club_id, Some("general"));
    let dm_chan = channel_in(&phone, &dm_id, None);
    // #mods: nobody but the owner may view it
    let mods = new_id(4, [1; 10]);
    phone
        .create("channel.create", &club_scope, &mods, json!({"space_id": club_id, "kind": "text", "name": "mods"}))
        .await;
    phone
        .create(
            "channel.set_permission",
            &club_scope,
            &mods,
            json!({"target_type": "role", "target_id": "everyone", "allow": [], "deny": ["view"]}),
        )
        .await;
    let say = |channel: &str, text: &str, reply_to: Option<&str>| {
        let mut p = json!({"channel_id": channel, "authors": [kai], "text": text, "entities": []});
        if let Some(r) = reply_to {
            p["reply_to"] = json!(r);
        }
        p
    };
    let secret = new_id(4, [2; 10]);
    let open = new_id(4, [3; 10]);
    phone.create("message.send", &club_scope, &secret, say(&mods, "the secret plan", None)).await;
    phone.create("message.send", &club_scope, &open, say(&general, "an open question", None)).await;
    // replies in #general to #mods (elsewhere), and privately in the DM to #general
    let reply_mods = new_id(4, [4; 10]);
    phone.create("message.send", &club_scope, &reply_mods, say(&general, "about that plan", Some(&secret))).await;
    let private = new_id(4, [5; 10]);
    phone
        .create("message.send", &format!("space:{dm_id}"), &private, say(&dm_chan, "just between us", Some(&open)))
        .await;
    phone.drain(Q).await;
    laptop.drain(Q).await;

    let get = |path: String, e: Value| {
        let http = http.clone();
        async move { http.get(path).bearer_auth(tok(&e)).send().await.unwrap().json::<Value>().await.unwrap() }
    };
    let find = |v: &Value, id: &str| v["items"].as_array().unwrap().iter().find(|m| m["id"] == id).cloned().unwrap();
    let theirs = get(url(&format!("/channels/{general}/messages")), friend.clone()).await;
    let r = find(&theirs, &reply_mods);
    assert_eq!((r["reply_to"].clone(), r.get("reply_to_channel_id").cloned()), (Value::Null, None), "{r}");
    let mine = get(url(&format!("/channels/{general}/messages")), sys.clone()).await;
    let r = find(&mine, &reply_mods);
    assert_eq!(
        (r["reply_to"].as_str(), r["reply_to_channel_id"].as_str()),
        (Some(secret.as_str()), Some(mods.as_str()))
    );
    // the private reply links back to #general for the friend, who can read it
    let dm_msgs = get(url(&format!("/channels/{dm_chan}/messages")), friend.clone()).await;
    let r = find(&dm_msgs, &private);
    assert_eq!(
        (r["reply_to"].as_str(), r["reply_to_channel_id"].as_str()),
        (Some(open.as_str()), Some(general.as_str()))
    );
    // and over sync: the friend has the reply, never the original it can't see
    let held = |id: &str| laptop.store.confirmed().any(|o| o.entity_id.as_deref() == Some(id));
    assert!(held(&reply_mods) && held(&open) && !held(&secret));
}
