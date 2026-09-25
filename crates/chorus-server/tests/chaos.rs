//! End-to-end chaos test (SYNC.md §9.3): the real server binary, killed with `kill -9` and
//! restarted, restored from a backup taken mid-run, with six headless devices on the real client
//! engine (`chorus_core::sync::ClientEngine`) over real WebSockets.
//!
//! Three accounts (two systems and a person) share a space, a DM and one channel of an internal
//! space (a guest), and write random ops of every chat kind while their sockets drop mid-batch,
//! pushes are sent twice, devices stay offline for a while, follows and space membership change
//! and channel permissions churn. What a run *does* is decided by its seed; the timing is not.
//!
//! At quiescence, per device: its replica holds exactly the ops the server says its account may
//! see (same ids, same stamps) and projects like them, digests agree, the outbox is empty, every
//! rejected op has a reason, a final re-check repairs nothing, repairs stayed bounded, the
//! server's REST reads list the messages each phone projects, and no file a device could
//! re-send after the restore is missing. And no
//! device ever received an op its account wasn't allowed to see when it was sent: every `ops`
//! frame is recorded with its arrival time, then the server's op log is replayed through the
//! server's own projection and the rule (`ingest::can_access` + `visibility::op_visible_to`) is
//! asked at each state the frame may have been sent from (see [`leaks`]).
//!
//! `CHORUS_CHAOS_SEEDS=<n>` for a longer run (default 2 seeds), `CHORUS_CHAOS_SEED=<n>` to rerun
//! one, `CHORUS_CHAOS_STEPS=<n>` for longer seeds (default 160).

mod common;

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use base64::Engine as _;
use chorus_core::hlc::HlcClock;
use chorus_core::id::new_id;
use chorus_core::model;
use chorus_core::op::Op;
use chorus_core::projector::Projector;
use chorus_core::sync::{ClientEngine, ClientStore, ClockReading, Frame, MemStore};
use chorus_core::time::TimeSource;
use chorus_server::{db, ingest, oplog, project, visibility};
use futures_util::{SinkExt, StreamExt};
use p256::pkcs8::EncodePublicKey;
use rusqlite::{Connection, OpenFlags};
use serde_json::{Value, json};
use sha2::{Digest as _, Sha256};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;

type Ws = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n.max(1)
    }
    fn chance(&mut self, pct: u64) -> bool {
        self.below(100) < pct
    }
    fn pick<T: Clone>(&mut self, v: &[T]) -> Option<T> {
        if v.is_empty() { None } else { Some(v[self.below(v.len() as u64) as usize].clone()) }
    }
    fn bytes10(&mut self) -> [u8; 10] {
        let a = self.next().to_le_bytes();
        let b = self.next().to_le_bytes();
        [a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], b[0], b[1]]
    }
}

fn toml_path(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}

fn pubkey(seed: u8) -> String {
    let sk = p256::ecdsa::SigningKey::from_slice(&[seed; 32]).unwrap();
    base64::engine::general_purpose::STANDARD.encode(sk.verifying_key().to_public_key_der().unwrap().as_bytes())
}

// ─── the server process ──────────────────────────────────────────────────────

struct ServerProc {
    root: PathBuf,
    port: u16,
    /// The data directory of each op log: a restore starts a new one.
    data: Vec<PathBuf>,
    child: Option<Child>,
    starts: usize,
}

impl ServerProc {
    fn new(root: PathBuf) -> ServerProc {
        std::fs::create_dir_all(&root).unwrap();
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let s = ServerProc { data: vec![root.join("data-0")], root, port, child: None, starts: 0 };
        s.write_config();
        s
    }

    /// Which op log the server is on (bumps with a restore).
    fn generation(&self) -> usize {
        self.data.len() - 1
    }

    fn config(&self) -> PathBuf {
        self.root.join("chorus.toml")
    }

    fn db(&self, generation: usize) -> PathBuf {
        self.data[generation].join("chorus.db")
    }

    fn write_config(&self) {
        let toml = format!(
            "[server]\nlisten = \"127.0.0.1:{port}\"\npublic_url = \"http://127.0.0.1:{port}\"\ndata_dir = \"{data}\"\n\
             [backup]\ndir = \"{backups}\"\n\
             [security]\nrate_per_second = 0\nsync_sockets_per_address = 0\nsync_frames_per_second = 0\n",
            port = self.port,
            data = toml_path(self.data.last().unwrap()),
            backups = toml_path(&self.root.join("backups")),
        );
        std::fs::write(self.config(), toml).unwrap();
    }

    fn cli(&self, args: &[&str]) -> String {
        let out = Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .arg("--config")
            .arg(self.config())
            .args(args)
            .output()
            .unwrap();
        assert!(out.status.success(), "chorus-server {args:?}: {}", String::from_utf8_lossy(&out.stderr));
        String::from_utf8(out.stdout).unwrap()
    }

    fn base(&self) -> String {
        format!("127.0.0.1:{}", self.port)
    }

    async fn start(&mut self) {
        assert!(self.child.is_none());
        let log = std::fs::File::create(self.root.join(format!("server-{}.log", self.starts))).unwrap();
        self.starts += 1;
        let child = Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .arg("--config")
            .arg(self.config())
            .arg("serve")
            .env("RUST_LOG", "warn")
            .stdout(Stdio::from(log.try_clone().unwrap()))
            .stderr(Stdio::from(log))
            .spawn()
            .unwrap();
        self.child = Some(child);
        let http = reqwest::Client::new();
        let url = format!("http://{}/api/v1/server", self.base());
        let end = Instant::now() + Duration::from_secs(30);
        loop {
            if let Ok(r) = http.get(&url).send().await
                && r.status().is_success()
            {
                return;
            }
            assert!(Instant::now() < end, "the server didn't come up (see {})", self.root.display());
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// `kill -9` (TerminateProcess on Windows): no graceful shutdown, whatever it was writing.
    fn kill(&mut self) {
        if let Some(mut c) = self.child.take() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }

    fn up(&self) -> bool {
        self.child.is_some()
    }
}

impl Drop for ServerProc {
    fn drop(&mut self) {
        self.kill();
    }
}

// ─── devices ─────────────────────────────────────────────────────────────────

struct Conn {
    sink: futures_util::stream::SplitSink<Ws, Message>,
    /// Frames as they arrived, with the (server-comparable) wall clock of their arrival.
    rx: mpsc::UnboundedReceiver<(i64, Frame)>,
    reader: tokio::task::JoinHandle<()>,
    generation: usize,
}

struct Dev {
    name: String,
    account: usize,
    token: String,
    store: MemStore,
    engine: ClientEngine,
    clock: HlcClock,
    projector: Projector,
    conn: Option<Conn>,
    /// Stays offline until this step.
    offline_until: usize,
    last_push: Option<Frame>,
    connects: u32,
    /// Files this device has a copy of (it uploaded them), by hash.
    files: HashMap<String, Vec<u8>>,
    /// Uploads to retry until they succeed (the apps keep a queue like this).
    uploads_due: BTreeSet<String>,
    /// "Keep everything on this device" (the desks): files named by ops it receives are
    /// downloaded, with the tries left.
    keep_all: bool,
    fetch_due: BTreeMap<String, u8>,
    /// Files it had at a reconcile, named by ops it held then: those must come back.
    held_at_reconcile: BTreeSet<String>,
}

impl Dev {
    fn drop_conn(&mut self) {
        if let Some(c) = self.conn.take() {
            c.reader.abort();
        }
        self.engine.on_disconnect();
    }

    async fn send(&mut self, frames: Vec<Frame>) {
        for f in frames {
            if matches!(f, Frame::Push { .. }) {
                self.last_push = Some(f.clone());
            }
            let Some(c) = self.conn.as_mut() else { return };
            let text = serde_json::to_string(&f).unwrap();
            if c.sink.send(Message::Text(text.into())).await.is_err() {
                self.drop_conn();
                return;
            }
        }
    }

    async fn flush(&mut self) {
        let out = self.engine.pump(&self.store);
        self.send(out).await;
    }

    fn projection(&mut self) -> &model::Projection {
        let touched = self.store.take_touched();
        if self.projector.is_empty() {
            self.projector.sync(self.store.visible());
        } else {
            let store = &self.store;
            self.projector.sync_ids(touched, |id| store.ops.get(id).filter(|o| !store.rejected.contains_key(&o.id)));
        }
        self.projector.projection()
    }
}

/// An `ops` frame as a device received it.
struct Delivery {
    dev: usize,
    generation: usize,
    at: i64,
    scope: String,
    to: i64,
    ops: Vec<String>,
}

struct Account {
    id: String,
    session: String,
}

#[derive(Default)]
struct Stats {
    ops: usize,
    kills: usize,
    drops: usize,
    duplicates: usize,
    offline: usize,
    uploads: usize,
    rest: usize,
    frames: usize,
    delivered: usize,
    rejected: BTreeMap<String, usize>,
    repairs: u64,
    /// Messages compared with the server's REST reads.
    compared: usize,
}

struct World {
    seed: u64,
    rng: Rng,
    server: ServerProc,
    http: reqwest::Client,
    accounts: Vec<Account>,
    devs: Vec<Dev>,
    deliveries: Vec<Delivery>,
    trace: Vec<String>,
    last_frame: Instant,
    stats: Stats,
    /// The shared space, the DM, A's internal space and its channel shared with C.
    shared: String,
    dm: String,
    home: String,
    news: String,
    /// Follows by (follower, target) account index.
    follows: BTreeMap<(usize, usize), String>,
    backup: Option<PathBuf>,
}

const A: usize = 0;
const B: usize = 1;
const C: usize = 2;
const PERMS: &[&str] = &["view", "send", "react", "thread", "pin"];

impl World {
    fn note(&mut self, s: String) {
        self.trace.push(s);
    }

    fn id(&mut self) -> String {
        new_id(chorus_server::now_ms() as u64, self.rng.bytes10())
    }

    fn url(&self, path: &str) -> String {
        format!("http://{}/api/v1{path}", self.server.base())
    }

    async fn connect(&mut self, d: usize) {
        if self.devs[d].conn.is_some() || !self.server.up() {
            return;
        }
        let Ok((ws, _)) = tokio_tungstenite::connect_async(format!("ws://{}/api/v1/sync", self.server.base())).await
        else {
            return;
        };
        let (sink, mut stream) = ws.split();
        let (tx, rx) = mpsc::unbounded_channel();
        let reader = tokio::spawn(async move {
            while let Some(Ok(msg)) = stream.next().await {
                if let Message::Text(t) = msg {
                    let at = chorus_server::now_ms();
                    let Ok(f) = serde_json::from_str::<Frame>(&t) else { continue };
                    if tx.send((at, f)).is_err() {
                        break;
                    }
                }
            }
        });
        let generation = self.server.generation();
        let dev = &mut self.devs[d];
        dev.conn = Some(Conn { sink, rx, reader, generation });
        dev.connects += 1;
        let clock = ClockReading { wall: chorus_server::now_ms(), mono: None, boot_id: None };
        let hello = dev.engine.on_connect(&dev.store, clock, &dev.token);
        dev.send(vec![hello]).await;
    }

    /// Handle what device `d` has received so far.
    async fn poll(&mut self, d: usize) {
        loop {
            let dev = &mut self.devs[d];
            let Some(c) = dev.conn.as_mut() else { return };
            let generation = c.generation;
            let (at, f) = match c.rx.try_recv() {
                Ok(x) => x,
                Err(mpsc::error::TryRecvError::Empty) => return,
                Err(mpsc::error::TryRecvError::Disconnected) => {
                    dev.drop_conn();
                    return;
                }
            };
            self.last_frame = Instant::now();
            self.stats.frames += 1;
            if let Frame::Ops { scope, ops, to } = &f {
                // like the apps' replica: the clock moves past what it receives
                for o in ops {
                    dev.clock.observe(o.hlc, at as u64);
                    if dev.keep_all {
                        for hash in chorus_core::restore::blob_hashes(o) {
                            if !dev.files.contains_key(&hash) {
                                dev.fetch_due.insert(hash, 5);
                            }
                        }
                    }
                }
                self.stats.delivered += ops.len();
                self.deliveries.push(Delivery {
                    dev: d,
                    generation,
                    at,
                    scope: scope.clone(),
                    to: *to,
                    ops: ops.iter().map(|o| o.id.clone()).collect(),
                });
            }
            if let Frame::Error { code, message } = &f {
                let line = format!("{}: error frame {code}: {message}", dev.name);
                self.trace.push(line);
            }
            let welcome = matches!(f, Frame::Welcome { .. });
            let reconcile = matches!(f, Frame::Welcome { reconcile: true, .. });
            let dev = &mut self.devs[d];
            let out = dev.engine.on_frame(&mut dev.store, f);
            dev.send(out).await;
            if reconcile {
                self.reupload(d).await;
            } else if welcome {
                self.flush_uploads(d).await;
            }
        }
    }

    /// After a reconcile, like the apps (`Replica::restoring_blobs`): upload again the files
    /// named by ops the restored server doesn't have yet that this device has (the restored server's files are as old as its backup; SYNC.md §7.3).
    async fn reupload(&mut self, d: usize) {
        let dev = &self.devs[d];
        let none = BTreeSet::new();
        let hashes: BTreeSet<String> = [true, false]
            .into_iter()
            .flat_map(|restore| dev.store.pending(&none, usize::MAX, restore))
            .flat_map(|o| chorus_core::restore::blob_hashes(&o))
            .filter(|h| dev.files.contains_key(h))
            .collect();
        // what the check expects back (from the ops it holds, not from the outbox logic above)
        let held: Vec<String> = dev
            .store
            .ops
            .values()
            .flat_map(chorus_core::restore::blob_hashes)
            .filter(|h| dev.files.contains_key(h))
            .collect();
        let line = format!("{}: uploads {} file(s) again after the restore", self.devs[d].name, hashes.len());
        self.devs[d].held_at_reconcile.extend(held);
        self.note(line);
        self.devs[d].uploads_due.extend(hashes);
        self.flush_uploads(d).await;
    }

    async fn flush_uploads(&mut self, d: usize) {
        for hash in self.devs[d].uploads_due.clone() {
            let bytes = self.devs[d].files[&hash].clone();
            let ok = self.put_blob(d, &hash, bytes).await;
            let line = format!("{}: re-sent {hash}: {ok}", self.devs[d].name);
            self.note(line);
            if !ok {
                return; // retried at the next connection
            }
            self.devs[d].uploads_due.remove(&hash);
        }
    }

    async fn settle(&mut self, ms: u64) {
        let end = Instant::now() + Duration::from_millis(ms);
        loop {
            for d in 0..self.devs.len() {
                self.poll(d).await;
            }
            if Instant::now() >= end {
                return;
            }
            tokio::time::sleep(Duration::from_millis(3)).await;
        }
    }

    /// A local op on device `d` (pushed at once when it's online).
    async fn op(&mut self, d: usize, kind: &str, scope: &str, entity: &str, payload: Value) -> String {
        let now = chorus_server::now_ms();
        let dev = &mut self.devs[d];
        let o = Op {
            id: new_id(now as u64, self.rng.bytes10()),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: Some(entity.into()),
            hlc: dev.clock.tick(now as u64),
            device_at: now,
            tz_offset_min: 60,
            mono: None,
            boot_id: None,
            time_source: TimeSource::Auto,
            seen_seq: dev.store.cursor(scope),
            member_id: None,
            payload,
            seq: None,
            account_id: None,
            device_id: None,
            occurred_at: None,
            received_at: None,
        };
        let id = o.id.clone();
        dev.store.add_local(o);
        dev.flush().await;
        self.stats.ops += 1;
        id
    }

    async fn post(&mut self, account: usize, path: &str, body: Value) -> Option<(u16, Value)> {
        if !self.server.up() {
            return None;
        }
        self.stats.rest += 1;
        let r = self.http.post(self.url(path)).bearer_auth(&self.accounts[account].session).json(&body).send().await;
        let r = r.ok()?;
        let status = r.status().as_u16();
        Some((status, r.json().await.unwrap_or(Value::Null)))
    }

    async fn delete(&mut self, account: usize, path: &str) -> Option<u16> {
        if !self.server.up() {
            return None;
        }
        self.stats.rest += 1;
        let r = self.http.delete(self.url(path)).bearer_auth(&self.accounts[account].session).send().await;
        Some(r.ok()?.status().as_u16())
    }

    // ─── setup ───────────────────────────────────────────────────────────────

    async fn new(seed: u64, root: PathBuf) -> World {
        let mut server = ServerProc::new(root);
        let invite = |kind: &str| -> String {
            let out = server.cli(&["invite", "--kind", kind]);
            out.lines().next().unwrap().rsplit('/').next().unwrap().trim().to_string()
        };
        let codes = [invite("system"), invite("system"), invite("person")];
        server.start().await;
        let http = reqwest::Client::builder().timeout(Duration::from_secs(10)).build().unwrap();
        let redeem = |code: String, key: u8, handle: &'static str| {
            let http = http.clone();
            let url = format!("http://{}/api/v1/auth/redeem", server.base());
            async move {
                let body = json!({
                    "code": code,
                    "device": {"name": format!("dev{key}"), "platform": "cli", "public_key": pubkey(key)},
                    "account": {"display_name": handle, "handle": handle},
                });
                let r = http.post(url).json(&body).send().await.unwrap();
                assert_eq!(r.status(), 201);
                r.json::<Value>().await.unwrap()
            }
        };
        let mut enrolled = Vec::new();
        for (i, (code, handle)) in codes.into_iter().zip(["stars", "moon", "alex"]).enumerate() {
            enrolled.push(redeem(code, 1 + i as u8, handle).await);
        }
        let accounts: Vec<Account> = enrolled
            .iter()
            .map(|e| Account {
                id: e["account_id"].as_str().unwrap().into(),
                session: e["session"].as_str().unwrap().into(),
            })
            .collect();
        // a second device for each account
        for (i, a) in accounts.iter().enumerate() {
            let url = format!("http://{}/api/v1/devices/invite", server.base());
            let inv: Value = http.post(url).bearer_auth(&a.session).send().await.unwrap().json().await.unwrap();
            let code = inv["code"].as_str().unwrap().to_string();
            enrolled.push(redeem(code, 11 + i as u8, "").await);
        }
        let names = ["a-phone", "b-phone", "c-phone", "a-desk", "b-desk", "c-desk"];
        let devs = enrolled
            .iter()
            .enumerate()
            .map(|(i, e)| Dev {
                name: names[i].into(),
                account: i % 3,
                token: e["session"].as_str().unwrap().into(),
                store: MemStore::default(),
                engine: ClientEngine::new(e["device_id"].as_str().unwrap()),
                clock: HlcClock::new(0xc000_0000 + i as u32),
                projector: Projector::new(),
                conn: None,
                offline_until: 0,
                last_push: None,
                connects: 0,
                files: HashMap::new(),
                uploads_due: BTreeSet::new(),
                keep_all: i >= 3,
                fetch_due: BTreeMap::new(),
                held_at_reconcile: BTreeSet::new(),
            })
            .collect();
        let mut w = World {
            seed,
            rng: Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1),
            server,
            http,
            accounts,
            devs,
            deliveries: Vec::new(),
            trace: Vec::new(),
            last_frame: Instant::now(),
            stats: Stats::default(),
            shared: String::new(),
            dm: String::new(),
            home: String::new(),
            news: String::new(),
            follows: BTreeMap::new(),
            backup: None,
        };
        for d in 0..w.devs.len() {
            w.connect(d).await;
        }
        w.settle(300).await;
        // members to write as
        for d in [A, B] {
            let scope = format!("account:{}", w.accounts[d].id);
            for n in 0..3 {
                let m = w.id();
                w.op(d, "member.create", &scope, &m, json!({"name": format!("{}{n}", ["Kai", "Rin"][d])})).await;
            }
        }
        // B and C follow A; A accepts from its phone
        for f in [B, C] {
            w.follow(f, A).await;
        }
        w.synced().await;
        let (_, club) = w
            .post(
                A,
                "/spaces",
                json!({"kind": "shared", "name": "Club", "accounts": [w.accounts[B].id, w.accounts[C].id]}),
            )
            .await
            .unwrap();
        w.shared = format!("space:{}", club["id"].as_str().unwrap_or_else(|| panic!("the shared space: {club}")));
        let (_, dm) = w.post(A, "/spaces", json!({"kind": "dm", "accounts": [w.accounts[C].id]})).await.unwrap();
        w.dm = format!("space:{}", dm["id"].as_str().unwrap_or_else(|| panic!("the DM: {dm}")));
        w.settle(300).await;
        w.home = w.devs[A]
            .store
            .scopes
            .iter()
            .find(|s| s.starts_with("space:") && **s != w.shared && **s != w.dm)
            .cloned()
            .unwrap();
        // one channel of A's internal space is shared with C (a guest)
        w.news = w.id();
        let (home, news) = (w.home.clone(), w.news.clone());
        let space_id = home.strip_prefix("space:").unwrap().to_string();
        w.op(A, "channel.create", &home, &news, json!({"space_id": space_id, "kind": "text", "name": "news"})).await;
        let c = w.accounts[C].id.clone();
        w.op(
            A,
            "channel.set_permission",
            &home,
            &news,
            json!({"target_type": "account", "target_id": c, "allow": ["view", "react"], "deny": []}),
        )
        .await;
        w.settle(400).await;
        assert!(w.devs[C].store.scopes.contains(&w.home), "C is a guest of A's internal space");
        w
    }

    /// Until every device's outbox is empty (setup only).
    async fn synced(&mut self) {
        let end = Instant::now() + Duration::from_secs(20);
        loop {
            self.settle(20).await;
            if self.devs.iter().all(|d| d.store.pending(&BTreeSet::new(), 1, false).is_empty()) {
                self.settle(100).await;
                for d in &self.devs {
                    assert!(d.store.rejected.is_empty(), "setup: {} had ops rejected: {:?}", d.name, d.store.rejected);
                }
                return;
            }
            assert!(Instant::now() < end, "setup: outboxes never emptied");
        }
    }

    /// `follower` asks to follow `target`; a device of the target accepts.
    async fn follow(&mut self, follower: usize, target: usize) {
        let handle = ["stars", "moon", "alex"][target];
        let Some((st, f)) = self.post(follower, "/follows", json!({"target": handle})).await else { return };
        let Some(id) = f["id"].as_str().map(str::to_string) else { return };
        self.note(format!("follow {follower}→{target}: {st}"));
        self.follows.insert((follower, target), id.clone());
        // the target's phone accepts once it has the request (as its UI would)
        let end = Instant::now() + Duration::from_secs(5);
        while !self.devs[target].store.ops.values().any(|o| o.entity() == Some(id.as_str())) {
            if Instant::now() > end {
                return;
            }
            self.settle(10).await;
        }
        let scope = format!("account:{}", self.accounts[target].id);
        self.op(target, "follow.accept", &scope, &id, json!({})).await;
    }

    // ─── what devices do ─────────────────────────────────────────────────────

    /// Ids a device's own projection offers for `space` (like its UI would).
    fn offer(&mut self, d: usize, space: &str) -> (Vec<String>, Vec<(String, String)>, Vec<String>) {
        let space_id = space.strip_prefix("space:").unwrap_or_default().to_string();
        let p = self.devs[d].projection();
        let str_of = |r: &model::Row, k: &str| r.fields.get(k).and_then(Value::as_str).map(str::to_string);
        let channels: Vec<String> = p
            .rows
            .get("channel")
            .map(|t| {
                t.iter()
                    .filter(|(_, r)| str_of(r, "space_id").as_deref() == Some(&space_id))
                    .map(|(id, _)| id.clone())
                    .collect()
            })
            .unwrap_or_default();
        let messages: Vec<(String, String)> = p
            .rows
            .get("message")
            .map(|t| {
                t.iter()
                    .filter_map(|(id, r)| str_of(r, "channel_id").map(|c| (id.clone(), c)))
                    .filter(|(_, c)| channels.contains(c))
                    .collect()
            })
            .unwrap_or_default();
        let members: Vec<String> = p
            .rows
            .get("member")
            .map(|t| {
                t.iter()
                    .filter(|(_, r)| r.fields.get("deleted_at").is_none_or(Value::is_null))
                    .map(|(id, _)| id.clone())
                    .collect()
            })
            .unwrap_or_default();
        (channels, messages, members)
    }

    async fn random_op(&mut self, d: usize) {
        let acct = self.devs[d].account;
        let own = format!("account:{}", self.accounts[acct].id);
        let spaces: Vec<String> =
            self.devs[d].store.scopes.iter().filter(|s| s.starts_with("space:")).cloned().collect();
        let Some(space) = self.rng.pick(&spaces) else { return };
        let space_id = space.strip_prefix("space:").unwrap().to_string();
        let (channels, messages, members) = self.offer(d, &space);
        let fake = self.id();
        let member = self.rng.pick(&members).unwrap_or(fake);
        let channel = match self.rng.pick(&channels) {
            Some(c) => c,
            None => return,
        };
        let target = self.rng.pick(&messages);
        let text = format!("note {}", self.rng.below(10_000));
        let roll = self.rng.below(100);
        let (kind, scope, entity, payload): (&str, String, String, Value) = match roll {
            0..=3 => {
                let id = self.id();
                ("member.create", own, id, json!({"name": format!("m{}", self.rng.below(100))}))
            }
            4..=11 => {
                let n = 1 + self.rng.below(2) as usize;
                let entries: Vec<Value> = (0..n)
                    .map(|i| {
                        let m = self.rng.pick(&members).unwrap_or_default();
                        json!({"subject_type": "member", "subject_id": m, "level": "front", "is_primary": i == 0})
                    })
                    .collect();
                let id = self.id();
                ("front.switch", own, id, json!({"entries": entries}))
            }
            12..=36 => {
                let id = self.id();
                let mut p = json!({"channel_id": channel, "authors": [member], "text": text, "entities": []});
                if let Some((reply, _)) = &target
                    && self.rng.chance(20)
                {
                    p["reply_to"] = json!(reply);
                }
                if self.rng.chance(10) {
                    p["visibility"] = json!({"mode": "system_only"});
                }
                ("message.send", space, id, p)
            }
            37..=44 => {
                let Some((m, _)) = target else { return };
                ("message.edit", space, m.clone(), json!({"message_id": m, "text": text, "entities": []}))
            }
            45..=48 => {
                let Some((m, _)) = target else { return };
                let k = if self.rng.chance(60) { "message.delete" } else { "message.restore" };
                (k, space, m.clone(), json!({"message_id": m}))
            }
            49..=51 => {
                let Some((m, _)) = target else { return };
                let id = self.id();
                let snap = json!([{"message_id": m, "authors": [member], "text": "fwd", "entities": [], "occurred_at": chorus_server::now_ms()}]);
                (
                    "message.forward",
                    space,
                    id,
                    json!({"channel_id": channel, "authors": [member], "text": "", "entities": [], "forward_of_id": m, "forward_snapshot": snap}),
                )
            }
            52..=57 => {
                let Some((m, _)) = target else { return };
                let k = if self.rng.chance(65) { "reaction.add" } else { "reaction.remove" };
                let emoji = ["💜", "✨", "👍"][self.rng.below(3) as usize];
                (
                    k,
                    space,
                    m.clone(),
                    json!({"target_type": "message", "target_id": m, "emoji": emoji, "member_id": member}),
                )
            }
            58..=60 => {
                let Some((m, _)) = target else { return };
                let k = if self.rng.chance(60) { "message.pin" } else { "message.unpin" };
                (k, space, m.clone(), json!({"message_id": m}))
            }
            61..=65 => {
                let Some((m, c)) = target else { return };
                ("read.mark", space, m.clone(), json!({"channel_id": c, "message_id": m}))
            }
            66..=69 => {
                let id = self.id();
                match target {
                    Some((m, _)) if self.rng.chance(50) => (
                        "channel.create",
                        space,
                        id,
                        json!({"space_id": space_id, "kind": "thread", "name": "thread", "parent_message_id": m}),
                    ),
                    _ => ("channel.create", space, id, json!({"space_id": space_id, "kind": "text", "name": text})),
                }
            }
            70..=73 => {
                let (k, p) = match self.rng.below(6) {
                    0 | 1 => ("channel.set", json!({"name": format!("renamed {}", self.rng.below(100))})),
                    2 => ("channel.archive", json!({})),
                    3 => ("channel.unarchive", json!({})),
                    4 => ("channel.delete", json!({})),
                    _ => ("channel.restore", json!({})),
                };
                (k, space, channel, p)
            }
            74..=81 => {
                // permission churn: the guest channel, or any channel of the space
                let (scope, channel) = if acct == A && self.rng.chance(30) {
                    (self.home.clone(), self.news.clone())
                } else {
                    (space, channel)
                };
                let (tt, tid) = match self.rng.below(4) {
                    0 => ("role", "everyone".to_string()),
                    1 => ("role", ["member", "read_only"][self.rng.below(2) as usize].to_string()),
                    _ => ("account", self.accounts[self.rng.below(3) as usize].id.clone()),
                };
                let (allow, deny) = if self.rng.chance(30) {
                    (vec![], vec![])
                } else {
                    let mut allow = Vec::new();
                    let mut deny = Vec::new();
                    for p in PERMS {
                        match self.rng.below(4) {
                            0 => allow.push(*p),
                            1 => deny.push(*p),
                            _ => {}
                        }
                    }
                    (allow, deny)
                };
                (
                    "channel.set_permission",
                    scope,
                    channel,
                    json!({"target_type": tt, "target_id": tid, "allow": allow, "deny": deny}),
                )
            }
            82..=83 => {
                let who = self.accounts[self.rng.below(3) as usize].id.clone();
                let role = ["member", "admin", "read_only"][self.rng.below(3) as usize];
                let shared = self.shared.clone();
                let id = shared.strip_prefix("space:").unwrap().to_string();
                ("space.set_role", shared, id, json!({"account_id": who, "role": role}))
            }
            84..=89 => {
                let Some(hash) = self.upload(d).await else { return };
                let att = self.id();
                self.op(
                    d,
                    "attachment.create",
                    &space,
                    &att,
                    json!({"blob_hash": hash, "filename": "photo.png", "mime": "image/png", "size": 64}),
                )
                .await;
                let id = self.id();
                (
                    "message.send",
                    space,
                    id,
                    json!({"channel_id": channel, "authors": [member], "text": text, "entities": [], "attachments": [att]}),
                )
            }
            _ => return,
        };
        self.op(d, kind, &scope, &entity, payload).await;
    }

    /// Upload a small random blob as account of device `d` (only while the server is up).
    async fn upload(&mut self, d: usize) -> Option<String> {
        if !self.server.up() || self.devs[d].conn.is_none() {
            return None;
        }
        let bytes: Vec<u8> = (0..64).map(|_| self.rng.below(256) as u8).collect();
        let hash = format!("{:x}", Sha256::digest(&bytes));
        self.devs[d].files.insert(hash.clone(), bytes.clone());
        self.put_blob(d, &hash, bytes).await.then_some(hash)
    }

    /// Download a few of the files a keep-everything device wants.
    async fn fetch_files(&mut self, d: usize) {
        if !self.server.up() || self.devs[d].conn.is_none() {
            return;
        }
        let due: Vec<String> = self.devs[d].fetch_due.keys().take(3).cloned().collect();
        for hash in due {
            let r = self.http.get(self.url(&format!("/blobs/{hash}"))).bearer_auth(&self.devs[d].token).send().await;
            let dev = &mut self.devs[d];
            let got = match r {
                Ok(r) if r.status().is_success() => r.bytes().await.ok().map(|b| b.to_vec()),
                _ => None,
            };
            match got {
                Some(bytes) => {
                    dev.fetch_due.remove(&hash);
                    dev.files.insert(hash, bytes);
                }
                None => {
                    // not uploaded yet, or not visible to it: a few more tries
                    let tries = dev.fetch_due.get_mut(&hash).unwrap();
                    *tries -= 1;
                    if *tries == 0 {
                        dev.fetch_due.remove(&hash);
                    }
                }
            }
        }
    }

    /// Like the apps (uploads.ts): `HEAD` first; the server having it (200, or 403: another
    /// account's) is done.
    async fn put_blob(&mut self, d: usize, hash: &str, bytes: Vec<u8>) -> bool {
        let head = self.http.head(self.url(&format!("/blobs/{hash}"))).bearer_auth(&self.devs[d].token).send().await;
        match head.map(|r| r.status().as_u16()) {
            Ok(code @ (200 | 403)) => {
                let line = format!("{}: HEAD {hash}: {code}", self.devs[d].name);
                self.note(line);
                return true;
            }
            Ok(404) => {}
            _ => return false,
        }
        let r = self
            .http
            .put(self.url(&format!("/blobs/{hash}")))
            .bearer_auth(&self.devs[d].token)
            .header("content-range", format!("bytes 0-{}/{}", bytes.len() - 1, bytes.len()))
            .header("content-type", "image/png")
            .body(bytes)
            .send()
            .await;
        self.stats.uploads += 1;
        r.is_ok_and(|r| r.status().is_success())
    }

    /// Follows and space membership change over REST (the server writes those ops).
    async fn social(&mut self) {
        match self.rng.below(4) {
            0 => {
                // C unfollows A, or follows again
                if let Some(id) = self.follows.remove(&(C, A)) {
                    let st = self.delete(C, &format!("/follows/{id}")).await;
                    self.note(format!("C unfollows A: {st:?}"));
                } else {
                    self.follow(C, A).await;
                }
            }
            1 => {
                let st = self
                    .delete(C, &format!("/spaces/{}/members/me", self.shared.strip_prefix("space:").unwrap()))
                    .await;
                self.note(format!("C leaves the shared space: {st:?}"));
            }
            _ => {
                let who = self.accounts[[B, C][self.rng.below(2) as usize]].id.clone();
                let path = format!("/spaces/{}/members", self.shared.strip_prefix("space:").unwrap());
                let r = self.post(A, &path, json!({"accounts": [who]})).await;
                self.note(format!("A adds {who} to the shared space: {:?}", r.map(|r| r.0)));
            }
        }
    }

    // ─── the run ─────────────────────────────────────────────────────────────

    async fn run(&mut self, steps: usize) {
        let kills: BTreeSet<usize> = (0..2).map(|_| 10 + self.rng.below(steps as u64 - 20) as usize).collect();
        let backup_at = steps * 3 / 10;
        let restore_at = steps * 7 / 10;
        let mut restart_at: Option<usize> = None;
        for step in 0..steps {
            // devices come back
            for d in 0..self.devs.len() {
                if self.devs[d].conn.is_none() && step >= self.devs[d].offline_until {
                    self.connect(d).await;
                }
            }
            if restart_at == Some(step) {
                self.server.start().await;
                self.note(format!("step {step}: server restarted"));
                restart_at = None;
            }
            if step == backup_at && self.server.up() {
                let out = self.server.cli(&["backup", "--to", &toml_path(&self.server.root.join("snapshots"))]);
                self.backup = Some(PathBuf::from(out.trim()));
                self.note(format!("step {step}: backup taken"));
            }
            if step == restore_at
                && let Some(snapshot) = self.backup.take()
            {
                self.restore(&snapshot).await;
                self.note(format!("step {step}: restored from the backup"));
                restart_at = None;
            }
            if kills.contains(&step) && self.server.up() {
                // mid-write: two devices push a burst and the server dies under it
                for _ in 0..2 {
                    let d = self.rng.below(self.devs.len() as u64) as usize;
                    for _ in 0..(10 + self.rng.below(20)) {
                        self.random_op(d).await;
                    }
                }
                tokio::time::sleep(Duration::from_millis(self.rng.below(15))).await;
                self.server.kill();
                self.stats.kills += 1;
                restart_at = Some(step + 1 + self.rng.below(6) as usize);
                self.note(format!("step {step}: server killed"));
            }
            let d = self.rng.below(self.devs.len() as u64) as usize;
            match self.rng.below(100) {
                0..=3 => {
                    self.devs[d].drop_conn();
                    self.devs[d].offline_until = step + 3 + self.rng.below(12) as usize;
                    self.stats.offline += 1;
                    let line = format!("step {step}: {} offline", self.devs[d].name);
                    self.note(line);
                }
                4..=6 if self.devs[d].conn.is_some() => {
                    // the socket drops mid-batch: pushes sent, acks never read
                    for _ in 0..(5 + self.rng.below(20)) {
                        self.random_op(d).await;
                    }
                    self.devs[d].drop_conn();
                    self.stats.drops += 1;
                    let line = format!("step {step}: {} dropped mid-batch", self.devs[d].name);
                    self.note(line);
                }
                7..=9 if self.devs[d].conn.is_some() => {
                    if let Some(f) = self.devs[d].last_push.clone() {
                        self.devs[d].send(vec![f]).await;
                        self.stats.duplicates += 1;
                    }
                }
                10..=12 => self.social().await,
                _ => {
                    for _ in 0..(1 + self.rng.below(3)) {
                        self.random_op(d).await;
                    }
                }
            }
            let wait = 2 + self.rng.below(15);
            self.settle(wait).await;
            for d in 0..self.devs.len() {
                if self.devs[d].keep_all {
                    self.fetch_files(d).await;
                }
                if !self.devs[d].uploads_due.is_empty() && self.devs[d].conn.is_some() {
                    self.flush_uploads(d).await;
                }
            }
        }
        if !self.server.up() {
            self.server.start().await;
        }
    }

    /// Kill the server and bring it back from the snapshot, in a new data directory (a new op
    /// log: the epoch changes and devices reconcile, SYNC.md §7.3).
    async fn restore(&mut self, snapshot: &Path) {
        self.server.kill();
        let into = self.server.root.join(format!("data-{}", self.server.data.len()));
        self.server.cli(&["restore", "--from", &toml_path(snapshot), "--into", &toml_path(&into)]);
        self.server.data.push(into);
        self.server.write_config();
        self.server.start().await;
    }

    /// Everyone online until nothing moves: no frames for a while and every outbox empty.
    async fn quiesce(&mut self) {
        let end = Instant::now() + Duration::from_secs(90);
        loop {
            for d in 0..self.devs.len() {
                self.connect(d).await;
                if !self.devs[d].uploads_due.is_empty() && self.devs[d].conn.is_some() {
                    self.flush_uploads(d).await;
                }
            }
            self.settle(50).await;
            let idle = self.last_frame.elapsed() > Duration::from_millis(600);
            let empty = self.devs.iter().all(|d| {
                d.conn.is_some()
                    && d.store.pending(&BTreeSet::new(), 1, false).is_empty()
                    && d.store.pending(&BTreeSet::new(), 1, true).is_empty()
                    && d.uploads_due.is_empty()
            });
            if idle && empty {
                return;
            }
            if Instant::now() >= end {
                let state: Vec<String> = self
                    .devs
                    .iter()
                    .map(|d| {
                        format!(
                            "{}: connected {}, outbox {}, restoring {}, uploads due {:?}",
                            d.name,
                            d.conn.is_some(),
                            d.store.pending(&BTreeSet::new(), usize::MAX, false).len(),
                            d.store.pending(&BTreeSet::new(), usize::MAX, true).len(),
                            d.uploads_due
                        )
                    })
                    .collect();
                panic!("seed {}: no quiescence after 90 s\n{}\n{}", self.seed, state.join("\n"), self.tail());
            }
        }
    }

    /// The server's SQL projection, read over REST as each account, lists the same messages
    /// (and texts) per channel as the account's phone projects from its ops.
    async fn rest_agrees(&mut self) -> Vec<String> {
        let mut problems = Vec::new();
        for d in [A, B, C] {
            let str_of = |r: &model::Row, k: &str| r.fields.get(k).and_then(Value::as_str).map(str::to_string);
            let gone = |r: &model::Row, k: &str| r.fields.get(k).is_some_and(|v| !v.is_null());
            let scopes = self.devs[d].store.scopes.clone();
            let p = self.devs[d].projection().clone();
            let channels: Vec<String> = p
                .rows
                .get("channel")
                .into_iter()
                .flatten()
                .filter(|(_, r)| r.exists && !gone(r, "deleted_at"))
                .filter(|(_, r)| str_of(r, "space_id").is_some_and(|s| scopes.contains(&format!("space:{s}"))))
                .map(|(id, _)| id.clone())
                .collect();
            for channel in channels {
                let mine: BTreeMap<String, String> = p
                    .rows
                    .get("message")
                    .into_iter()
                    .flatten()
                    .filter(|(_, r)| r.exists && !gone(r, "deleted_at"))
                    .filter(|(_, r)| str_of(r, "channel_id").as_deref() == Some(channel.as_str()))
                    .map(|(id, r)| (id.clone(), str_of(r, "text").unwrap_or_default()))
                    .collect();
                let url = self.url(&format!("/channels/{channel}/messages?limit=100"));
                let r = self.http.get(url).bearer_auth(&self.devs[d].token).send().await.unwrap();
                let name = &self.devs[d].name;
                if r.status() == 404 && mine.is_empty() {
                    continue; // e.g. a thread under a message it can't see
                }
                if !r.status().is_success() {
                    problems.push(format!("{name}: GET messages of {channel}: {}", r.status()));
                    continue;
                }
                let items: Value = r.json().await.unwrap();
                let items = items["items"].as_array().cloned().unwrap_or_default();
                if items.len() == 100 {
                    continue; // more than a page: not compared
                }
                let theirs: BTreeMap<String, String> = items
                    .iter()
                    .map(|m| {
                        (m["id"].as_str().unwrap().to_string(), m["text"].as_str().unwrap_or_default().to_string())
                    })
                    .collect();
                self.stats.compared += theirs.len();
                if mine != theirs {
                    let only_mine: Vec<_> = mine.iter().filter(|(k, v)| theirs.get(*k) != Some(v)).collect();
                    let only_theirs: Vec<_> = theirs.iter().filter(|(k, v)| mine.get(*k) != Some(v)).collect();
                    problems.push(format!(
                        "{name}: channel {channel}: projected {only_mine:?}, the server lists {only_theirs:?}"
                    ));
                }
            }
        }
        problems
    }

    fn tail(&self) -> String {
        let n = self
            .trace
            .len()
            .saturating_sub(std::env::var("CHORUS_CHAOS_TRACE").ok().and_then(|v| v.parse().ok()).unwrap_or(40));
        self.trace[n..].join("\n")
    }
}

// ─── checks ──────────────────────────────────────────────────────────────────

fn open_ro(path: &Path) -> Connection {
    Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap()
}

/// Every recorded `ops` frame of one op log against the rule's history. A frame was sent from
/// some server state between the frame's own `to` (the state includes the ops it carries) and
/// the last op the server had accepted when it arrived: `received_at` is stamped before an op
/// commits and the test shares the server's clock, so ops accepted after the frame's arrival have
/// a later `received_at`. The log is replayed through the server's projection one op at a time;
/// each frame's ops must be visible to its account (scope access and `op_visible_to`) at one of
/// the states in that window. Visibility in a scope depends only on that scope's ops (the
/// channel, member and permission rows all live in it), so only states after an op of the
/// frame's scope are asked.
fn leaks(log: &Path, deliveries: &[&Delivery], account_of: &dyn Fn(usize) -> String) -> Vec<String> {
    let src = open_ro(log);
    let log_ops: Vec<(i64, String, String, i64)> = src
        .prepare("SELECT seq, id, scope, received_at FROM op WHERE status = 'applied' ORDER BY seq")
        .unwrap()
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    drop(src);
    // hi(t): the highest seq whose op was received by t
    let mut by_time: Vec<(i64, i64)> = log_ops.iter().map(|(seq, _, _, at)| (*at, *seq)).collect();
    by_time.sort();
    let mut running = 0;
    let prefix_max: Vec<i64> = by_time
        .iter()
        .map(|(_, seq)| {
            running = running.max(*seq);
            running
        })
        .collect();
    let hi_of = |t: i64| -> i64 {
        let n = by_time.partition_point(|(at, _)| *at <= t);
        if n == 0 { 0 } else { prefix_max[n - 1] }
    };
    let known: BTreeSet<&str> = log_ops.iter().map(|(_, id, _, _)| id.as_str()).collect();

    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    conn.execute("ATTACH DATABASE ?1 AS src", [log.to_string_lossy()]).unwrap();
    conn.execute_batch(
        "INSERT INTO account SELECT * FROM src.account;
         INSERT INTO scope_access SELECT * FROM src.scope_access WHERE scope NOT LIKE 'space:%';",
    )
    .unwrap();

    let mut problems = Vec::new();
    // per scope: (lo, hi, delivery index), in order of lo
    let mut waiting: HashMap<&str, Vec<(i64, i64, usize)>> = HashMap::new();
    for (i, d) in deliveries.iter().enumerate() {
        for id in &d.ops {
            if !known.contains(id.as_str()) {
                problems.push(format!("delivered op {id} ({}) isn't in the server's log", d.scope));
            }
        }
        waiting.entry(d.scope.as_str()).or_default().push((d.to, hi_of(d.at).max(d.to), i));
    }
    for v in waiting.values_mut() {
        v.sort();
        v.reverse(); // pop from the back in order of lo
    }
    // per scope: frames that may have been sent from the current state: (hi, delivery index,
    // ops not seen visible yet)
    type Open = Vec<(i64, usize, BTreeSet<String>)>;
    let mut open: HashMap<&str, Open> = HashMap::new();
    let tx = conn.transaction().unwrap();
    for (seq, id, scope, _) in &log_ops {
        tx.execute("INSERT INTO main.op SELECT * FROM src.op WHERE seq = ?1", [seq]).unwrap();
        let o = oplog::by_id(&tx, id).unwrap().unwrap();
        project::after_insert(&tx, &o).unwrap();
        let queue = waiting.entry(scope.as_str()).or_default();
        let active = open.entry(scope.as_str()).or_default();
        while queue.last().is_some_and(|(lo, _, _)| lo <= seq) {
            let (_, hi, i) = queue.pop().unwrap();
            active.push((hi, i, deliveries[i].ops.iter().cloned().collect()));
        }
        active.retain_mut(|(hi, i, unseen)| {
            let d = deliveries[*i];
            let account = account_of(d.dev);
            let access = scope == "server" || ingest::can_access(&tx, &account, scope).unwrap();
            unseen.retain(|op_id| {
                let visible = access
                    && oplog::by_id(&tx, op_id)
                        .unwrap()
                        .is_some_and(|op| visibility::op_visible_to(&tx, &account, &op).unwrap());
                !visible
            });
            if unseen.is_empty() {
                return false;
            }
            if *hi <= *seq {
                for op_id in unseen.iter() {
                    let kind = oplog::by_id(&tx, op_id).unwrap().map(|o| o.kind).unwrap_or_default();
                    problems.push(format!(
                        "device {} (account {account}) received {kind} {op_id} in {scope}, not visible to it at \
                         any state it could have been sent from (seq {}..={hi})",
                        d.dev, d.to
                    ));
                }
                return false;
            }
            true
        });
    }
    for (scope, active) in &open {
        for (hi, i, unseen) in active {
            let d = deliveries[*i];
            problems.push(format!(
                "device {} received {unseen:?} in {scope}, still not visible at the end of its window (..={hi})",
                d.dev
            ));
        }
    }
    for (scope, queue) in &waiting {
        for (lo, _, i) in queue {
            problems.push(format!("delivery {i} in {scope} names seq {lo}, past the end of the log"));
        }
    }
    tx.rollback().unwrap();
    problems
}

/// The end state, per device, against the server's final database.
fn converged(w: &World) -> Vec<String> {
    let conn = open_ro(&w.server.db(w.server.generation()));
    let mut problems = Vec::new();
    for dev in &w.devs {
        let account = &w.accounts[dev.account].id;
        let name = &dev.name;
        let scopes = ingest::scopes_of(&conn, account).unwrap();
        let mine: BTreeSet<&String> = dev.store.scopes.iter().collect();
        let theirs: BTreeSet<&String> = scopes.iter().collect();
        if mine != theirs {
            problems.push(format!("{name}: scopes {mine:?}, the server says {theirs:?}"));
        }
        for o in dev.store.confirmed() {
            if !scopes.contains(&o.scope) {
                problems.push(format!("{name}: holds {} {} of {}, a scope it doesn't have", o.kind, o.id, o.scope));
            }
        }
        for scope in &scopes {
            let mut want = BTreeMap::new();
            let mut after = 0;
            loop {
                let page = oplog::scope_after(&conn, scope, after, 1000).unwrap();
                let Some(last) = page.last().and_then(|o| o.seq) else { break };
                after = last;
                for o in page {
                    if visibility::op_visible_to(&conn, account, &o).unwrap() {
                        want.insert(o.id.clone(), o);
                    }
                }
            }
            let have: BTreeMap<&String, &Op> =
                dev.store.confirmed().filter(|o| o.scope == *scope).map(|o| (&o.id, o)).collect();
            for (id, o) in &want {
                match have.get(id) {
                    None => problems.push(format!("{name}: missing {} {id} in {scope}", o.kind)),
                    Some(mine) => {
                        let (a, b) = (serde_json::to_value(mine).unwrap(), serde_json::to_value(o).unwrap());
                        if a != b {
                            problems
                                .push(format!("{name}: {} {id} differs from the server's copy:\n  {a}\n  {b}", o.kind));
                        }
                    }
                }
            }
            for (id, o) in &have {
                if !want.contains_key(*id) {
                    problems.push(format!("{name}: holds {} {id} in {scope}, which it may not see", o.kind));
                }
            }
            let digest = visibility::visible_digest(&conn, account, scope).unwrap();
            if dev.store.digest(scope) != digest {
                problems.push(format!("{name}: digest of {scope} differs from the server's"));
            }
        }
        if !dev.store.pending(&BTreeSet::new(), usize::MAX, false).is_empty()
            || !dev.store.pending(&BTreeSet::new(), usize::MAX, true).is_empty()
        {
            problems.push(format!("{name}: outbox not empty"));
        }
        for (id, e) in &dev.store.rejected {
            if e.code.is_empty() || e.message.is_empty() {
                problems.push(format!("{name}: {id} rejected without a reason"));
            }
        }
    }
    // every file an attachment names is on the server if, at a reconcile, a device held both an
    // op naming it and a copy (a file whose only copy is on a device that couldn't see the op
    // then, e.g. a guest who had lost the channel, can't come back)
    let blobs = w.server.data[w.server.generation()].join("blobs");
    let mut st = conn.prepare("SELECT id, blob_hash FROM attachment WHERE blob_hash IS NOT NULL").unwrap();
    let rows: Vec<(String, String)> =
        st.query_map([], |r| Ok((r.get(0)?, r.get(1)?))).unwrap().collect::<Result<_, _>>().unwrap();
    for (id, hash) in rows {
        let recoverable = w.devs.iter().any(|d| d.held_at_reconcile.contains(&hash));
        if recoverable && !blobs.join(&hash[..2]).join(&hash[2..4]).join(&hash).exists() {
            let holders: Vec<String> =
                w.devs
                    .iter()
                    .map(|d| {
                        let op = d.store.ops.values().find(|o| o.entity() == Some(id.as_str())).map(|o| {
                            format!("{} seq {:?} rejected {}", o.id, o.seq, d.store.rejected.contains_key(&o.id))
                        });
                        format!("{}: op {op:?}, file {}", d.name, d.files.contains_key(&hash))
                    })
                    .collect();
            problems.push(format!("attachment {id}: its file {hash} isn't on the server ({holders:?})"));
        }
    }
    problems
}

async fn one_seed(seed: u64, steps: usize) -> Stats {
    let started = Instant::now();
    let root = common::http_test_dir(&format!("chaos-{seed}"));
    let mut w = World::new(seed, root.clone()).await;
    w.run(steps).await;
    w.quiesce().await;
    // a final re-check of every scope: nothing may need repairing any more
    let repairs: Vec<u64> = w.devs.iter().map(|d| d.engine.repairs).collect();
    for d in 0..w.devs.len() {
        let frames = w.devs[d].engine.recheck(&w.devs[d].store);
        w.devs[d].send(frames).await;
    }
    w.quiesce().await;
    let mut problems = Vec::new();
    for (d, before) in w.devs.iter().zip(&repairs) {
        if d.engine.repairs != *before {
            problems.push(format!("{}: the final re-check repaired {} scope(s)", d.name, d.engine.repairs - before));
        }
        // a repair per permission change, reconnect or restore is expected; a loop is not
        let bound = 40 + 4 * u64::from(d.connects);
        if d.engine.repairs > bound {
            problems.push(format!("{}: {} repairs (bound {bound})", d.name, d.engine.repairs));
        }
    }
    // each device's live projection agrees with a fresh one over the same ops
    for d in 0..w.devs.len() {
        let mine = w.devs[d].projection().canonical();
        let reference = model::project(w.devs[d].store.visible()).canonical();
        if mine != reference {
            problems.push(format!("{}: incremental projection differs from a fresh one", w.devs[d].name));
        }
    }
    for d in 0..w.devs.len() {
        w.devs[d].drop_conn();
    }
    problems.extend(w.rest_agrees().await);
    w.server.kill();
    problems.extend(converged(&w));
    let accounts: Vec<String> = w.devs.iter().map(|d| w.accounts[d.account].id.clone()).collect();
    for generation in 0..w.server.data.len() {
        let mine: Vec<&Delivery> = w.deliveries.iter().filter(|d| d.generation == generation).collect();
        problems.extend(leaks(&w.server.db(generation), &mine, &|dev| accounts[dev].clone()));
    }
    // the checker itself: an op of A's account scope, and an aside of A's if there is one,
    // "delivered" to C's phone must be reported
    let last = w.server.generation();
    let planted: Vec<(String, String, i64)> = open_ro(&w.server.db(last))
        .prepare(
            "SELECT id, scope, seq FROM op WHERE account_id = ?1 AND status = 'applied'
               AND (scope = 'account:' || ?1 OR json_extract(payload, '$.visibility.mode') = 'system_only')
             GROUP BY scope",
        )
        .unwrap()
        .query_map([&w.accounts[A].id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert!(!planted.is_empty());
    let fake: Vec<Delivery> = planted
        .iter()
        .map(|(id, scope, seq)| Delivery {
            dev: C,
            generation: last,
            at: chorus_server::now_ms(),
            scope: scope.clone(),
            to: *seq,
            ops: vec![id.clone()],
        })
        .collect();
    let caught = leaks(&w.server.db(last), &fake.iter().collect::<Vec<_>>(), &|dev| accounts[dev].clone());
    assert_eq!(caught.len(), fake.len(), "the leak check must catch planted leaks: {caught:?}");
    let mut rejected: BTreeMap<String, usize> = BTreeMap::new();
    for d in &w.devs {
        for e in d.store.rejected.values() {
            *rejected.entry(e.code.clone()).or_default() += 1;
        }
    }
    w.stats.rejected = rejected;
    w.stats.repairs = w.devs.iter().map(|d| d.engine.repairs).sum();
    let took = started.elapsed();
    if !problems.is_empty() {
        problems.truncate(30);
        panic!(
            "seed {seed}: {} problem(s) (data in {}):\n{}\n--- last actions ---\n{}",
            problems.len(),
            root.display(),
            problems.join("\n"),
            w.tail()
        );
    }
    let stats = std::mem::take(&mut w.stats);
    drop(w);
    let _ = std::fs::remove_dir_all(&root);
    println!(
        "seed {seed}: {} ops, {} frames ({} ops delivered), {} kills, {} drops, {} duplicate pushes, {} offline, \
         {} uploads, {} REST calls, {} repairs, rejected {:?}, {} messages compared over REST in {took:.1?}",
        stats.ops,
        stats.frames,
        stats.delivered,
        stats.kills,
        stats.drops,
        stats.duplicates,
        stats.offline,
        stats.uploads,
        stats.rest,
        stats.repairs,
        stats.rejected,
        stats.compared
    );
    stats
}

#[tokio::test(flavor = "multi_thread")]
async fn chaos() {
    let env = |k: &str| std::env::var(k).ok().and_then(|v| v.parse::<u64>().ok());
    let steps = env("CHORUS_CHAOS_STEPS").unwrap_or(160) as usize;
    let seeds: Vec<u64> = match env("CHORUS_CHAOS_SEED") {
        Some(s) => vec![s],
        None => (1..=env("CHORUS_CHAOS_SEEDS").unwrap_or(2)).collect(),
    };
    let started = Instant::now();
    for seed in seeds {
        one_seed(seed, steps).await;
    }
    println!("chaos: {:.1?}", started.elapsed());
}
