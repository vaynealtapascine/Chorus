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
    let (_, mine) = get(url(&format!("/channels/{chan}/messages")), bot.clone()).await;
    assert!(mine["items"].as_array().unwrap().iter().all(|m| m["account_id"] == id_of(&sys).as_str()));
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
