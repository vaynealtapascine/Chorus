//! REST fuzzing (R28): every route the router serves (read from app.rs the way
//! scripts/api-check.py reads it), called with random and malformed path ids, queries, bodies,
//! huge values and wrong content types, as an anonymous caller, a device session, an API token of
//! each scope, an admin and another account. Never a 5xx, never a panic, never a dropped
//! connection, and never a response that names something the caller may not see:
//!
//! - B's private things (member, message, post, group, space and channel ids, and texts marked
//!   `SECRETB`) reach nobody but B, unless the caller put that id in the request itself;
//! - A's message and post texts (`SECRETA-msg`, `SECRETA-post`) reach A's API tokens only with
//!   `read:messages` / `read:posts` (or `export`), and never an anonymous caller;
//! - and nothing A does changes B's rows.
//!
//! `CHORUS_FUZZ_SEED=<n>` reruns a seed, `CHORUS_FUZZ_ROUNDS=<n>` makes it longer (default 3
//! requests per route and caller).

use std::collections::BTreeSet;
use std::sync::{Arc, Mutex};

use chorus_server::{app, auth, config::Config, db, ingest};
use rusqlite::{Connection, params};
use serde_json::{Value, json};
mod common;

const A: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const B: &str = "0192f8c2-0000-7000-8000-0000000000b2";
const ROOT: &str = "0192f8c2-0000-7000-8000-0000000000c3";
const SCOPES: &[&str] =
    &["read:front", "read:members", "read:messages", "read:posts", "write:messages", "stream", "write:front", "export"];

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
    fn pick<'a, T>(&mut self, v: &'a [T]) -> &'a T {
        &v[self.below(v.len())]
    }
}

// ─── the routes, as scripts/api-check.py finds them ─────────────────────────

/// Index just past the parenthesis closing the one at `open` (string literals skipped).
fn call_end(src: &[u8], open: usize) -> usize {
    let (mut depth, mut i) = (0i32, open);
    while i < src.len() {
        match src[i] {
            b'"' => {
                i += 1;
                while src[i] != b'"' {
                    i += if src[i] == b'\\' { 2 } else { 1 };
                }
            }
            b'(' => depth += 1,
            b')' => {
                depth -= 1;
                if depth == 0 {
                    return i + 1;
                }
            }
            _ => {}
        }
        i += 1;
    }
    src.len()
}

fn routes() -> Vec<(String, String)> {
    let src = include_str!("../src/app.rs");
    let b = src.as_bytes();
    // `let [mut] name = Router::new()` positions and `.nest("prefix", name)`
    let mut lets = Vec::new();
    for (at, _) in src.match_indices("Router::new()") {
        let before = &src[..at];
        if let Some(l) = before.rfind("let ") {
            let decl = before[l + 4..].trim_start_matches("mut ");
            if let Some(name) = decl.split(|c: char| !c.is_alphanumeric() && c != '_').next()
                && before[l..].trim_end().ends_with('=')
            {
                lets.push((at, name.to_string()));
            }
        }
    }
    let mut prefixes = std::collections::HashMap::new();
    for (at, _) in src.match_indices(".nest(") {
        let rest = &src[at + 6..];
        let q1 = rest.find('"').unwrap();
        let q2 = q1 + 1 + rest[q1 + 1..].find('"').unwrap();
        let name = rest[q2 + 1..]
            .trim_start_matches([',', ' ', '\n'])
            .split(|c: char| !c.is_alphanumeric() && c != '_')
            .next();
        if let Some(name) = name {
            prefixes.insert(name.to_string(), rest[q1 + 1..q2].to_string());
        }
    }
    let mut out = Vec::new();
    for (at, _) in src.match_indices(".route(") {
        let rest = &src[at + 7..];
        let q1 = rest.find('"').unwrap();
        let q2 = q1 + 1 + rest[q1 + 1..].find('"').unwrap();
        let path = &rest[q1 + 1..q2];
        let body = &src[at + 7 + q2 + 1..call_end(b, at + 6)];
        let owner = lets.iter().rev().find(|(p, _)| *p < at).map(|(_, n)| n.clone());
        let prefix = owner.and_then(|o| prefixes.get(&o).cloned()).unwrap_or_default();
        for m in ["get", "post", "put", "delete", "head", "patch"] {
            let pat = format!("{m}(");
            for (i, _) in body.match_indices(&pat) {
                let prev = body[..i].chars().next_back();
                if prev.is_none_or(|c| !(c.is_alphanumeric() || c == '_' || c == ':')) {
                    out.push((m.to_uppercase(), format!("{prefix}{path}")));
                }
            }
        }
    }
    out.sort();
    out.dedup();
    out
}

// ─── the world ─────────────────────────────────────────────────────────────

struct World {
    /// B's private ids (a leak if they reach someone else unasked)
    b_ids: Vec<String>,
    /// ids of every kind, both accounts', for path parameters and bodies
    ids: Vec<String>,
}

fn id(n: u8) -> String {
    chorus_core::id::new_id(1_790_000_000_000 + u64::from(n), [n; 10])
}

fn seed(conn: &Connection, now: i64) -> World {
    for (who, handle, admin) in [(A, "alice", false), (B, "bob", false), (ROOT, "root", true)] {
        conn.execute(
            "INSERT INTO account(id,kind,handle,is_admin,created_at) VALUES (?1,'system',?2,?3,0)",
            params![who, handle, admin],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at) VALUES (?1,?2,?1,?1,'cli','test',0)",
            params![format!("dev-{handle}"), who],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
            params![auth::hash(&format!("{handle}-session")), format!("dev-{handle}"), now, now + 86_400_000],
        )
        .unwrap();
        ingest::grant(conn, who, &format!("account:{who}")).unwrap();
    }
    for scope in SCOPES {
        conn.execute(
            "INSERT INTO api_token (id, account_id, name, token_hash, scopes, created_at) VALUES (?1, ?2, ?1, ?3, ?4, ?5)",
            params![format!("tok-{scope}"), A, auth::hash(&token_of(scope)), json!([scope]).to_string(), now],
        )
        .unwrap();
    }
    let mut t = now - 3_600_000;
    let mut op = |who: &str, kind: &str, scope: &str, entity: &str, payload: Value| {
        t += 1000;
        let (ack, o) = ingest::op_as(
            conn,
            who,
            &format!("dev-{}", if who == A { "alice" } else { "bob" }),
            kind,
            scope,
            Some(entity),
            payload,
            t,
            None,
        )
        .unwrap();
        assert!(o.is_some(), "{kind}: {:?}", ack.error);
    };
    let mut ids = Vec::new();
    let mut b_ids = Vec::new();
    for (who, tag, base) in [(A, "SECRETA", 10u8), (B, "SECRETB", 50u8)] {
        let acct = format!("account:{who}");
        let (space, chan, member, group, msg, post) =
            (id(base), id(base + 1), id(base + 2), id(base + 3), id(base + 4), id(base + 5));
        let scope = format!("space:{space}");
        ingest::grant(conn, who, &scope).unwrap();
        op(who, "space.create", &scope, &space, json!({"kind": "internal", "name": format!("{tag}-space")}));
        op(who, "space.join", &scope, &space, json!({"account_id": who}));
        op(
            who,
            "channel.create",
            &scope,
            &chan,
            json!({"space_id": space, "kind": "text", "name": format!("{tag}-channel")}),
        );
        op(
            who,
            "member.create",
            &acct,
            &member,
            json!({"name": format!("{tag}-member"), "pronouns": format!("{tag}-pronouns")}),
        );
        op(who, "group.create", &acct, &group, json!({"name": format!("{tag}-group"), "kind": "group"}));
        op(
            who,
            "front.switch",
            &acct,
            &id(base + 6),
            json!({"entries": [{"subject_type": "member", "subject_id": member, "is_primary": true}], "note": format!("{tag}-note")}),
        );
        op(
            who,
            "message.send",
            &scope,
            &msg,
            json!({"channel_id": chan, "authors": [member], "text": format!("{tag}-msg hello"), "entities": []}),
        );
        op(
            who,
            "post.create",
            &acct,
            &post,
            json!({"kind": "note", "authors": [member], "text": format!("{tag}-post", ), "entities": [], "visibility": {"mode": "private"}}),
        );
        let mine = [space, chan, member, group, msg, post];
        ids.extend(mine.iter().cloned());
        if who == B {
            b_ids.extend(mine.iter().cloned());
        }
    }
    ids.extend([A.to_string(), B.to_string(), ROOT.to_string(), "tok-read:front".into(), "dev-alice".into()]);
    World { b_ids, ids }
}

// ─── requests ──────────────────────────────────────────────────────────────

fn garbage(r: &mut Rng) -> String {
    match r.below(12) {
        0 => String::new(),
        1 => "..".into(),
        2 => "%00".into(),
        3 => "x".repeat(10_000),
        4 => "ünïcødé 🍅".into(),
        5 => "' OR 1=1 --".into(),
        6 => "-1".into(),
        7 => "99999999999999999999999".into(),
        8 => "%2e%2e%2f%2e%2e%2fetc%2fpasswd".into(),
        9 => chorus_core::id::new_id(r.next(), [r.below(256) as u8; 10]),
        10 => "null".into(),
        _ => format!("{:x}", r.next()),
    }
}

fn value(r: &mut Rng, w: &World, depth: u32) -> Value {
    match r.below(if depth > 2 { 8 } else { 11 }) {
        0 => Value::Null,
        1 => json!(r.below(2) == 0),
        2 => json!([0i64, -1, i64::MAX, i64::MIN, 1_790_000_000_000][r.below(5)]),
        3 => json!(1e308),
        4 | 5 => json!(r.pick(&w.ids)),
        6 => json!(garbage(r)),
        7 => json!(
            ["text", "note", "member", "group", "front", "shared", "dm", "private", "read:front", "🍅"][r.below(10)]
        ),
        8 => Value::Array((0..r.below(4)).map(|_| value(r, w, depth + 1)).collect()),
        9 => object(r, w, depth + 1),
        _ => json!(vec![r.pick(&w.ids).clone(); 2000]),
    }
}

const KEYS: &[&str] = &[
    "name",
    "text",
    "channel_id",
    "authors",
    "entities",
    "kind",
    "scopes",
    "url",
    "events",
    "emoji",
    "target_id",
    "member_id",
    "account_id",
    "handle",
    "visibility",
    "payload",
    "ops",
    "space_id",
    "accounts",
    "q",
    "limit",
    "before",
    "after",
    "reply_to",
    "id",
    "ids",
    "code",
    "device",
    "public_key",
    "platform",
    "subject_id",
    "entries",
    "at",
    "tz",
    "display_name",
    "mode",
    "value",
    "field_id",
    "list_id",
    "query",
    "title",
    "tags",
    "mood",
    "role",
];

/// A body shaped like a real one (the right keys, plausible values, A's and B's ids mixed): gets
/// past parsing to the rules behind it.
fn plausible(r: &mut Rng, w: &World) -> Value {
    let id = |r: &mut Rng| json!(r.pick(&w.ids));
    let ids = |r: &mut Rng| json!((0..1 + r.below(2)).map(|_| r.pick(&w.ids).clone()).collect::<Vec<_>>());
    let mut m = serde_json::Map::new();
    let text = ["hi", "", "x".repeat(5000).as_str(), "@kai hello", "🍅🍅"][r.below(5)].to_string();
    let fields: Vec<(&str, Value)> = vec![
        ("channel_id", id(r)),
        ("space_id", id(r)),
        ("member_id", id(r)),
        ("target_id", id(r)),
        ("account_id", id(r)),
        ("reply_to", id(r)),
        ("list_id", id(r)),
        ("authors", ids(r)),
        ("accounts", ids(r)),
        ("ids", ids(r)),
        ("text", json!(text)),
        ("name", json!(format!("n{}", r.below(1000)))),
        ("title", json!("t")),
        (
            "entities",
            json!([{"type": "mention", "offset": r.below(20), "length": r.below(20), "target_type": "member", "target_id": r.pick(&w.ids)}]),
        ),
        ("kind", json!(["text", "note", "shared", "dm", "group", "full", "thread", "entry"][r.below(8)])),
        ("visibility", {
            let mode = ["private", "followers", "public", "members"][r.below(4)];
            json!({"mode": mode, "ids": [r.pick(&w.ids)]})
        }),
        ("scopes", json!([SCOPES[r.below(SCOPES.len())], "admin"])),
        ("events", json!(["message.created", "front.changed", "*"])),
        (
            "url",
            json!(
                ["http://127.0.0.1:1/hook", "https://example.invalid/x", "file:///etc/passwd", "javascript:1"]
                    [r.below(4)]
            ),
        ),
        ("emoji", json!("🍅")),
        ("entries", json!([{"subject_type": "member", "subject_id": r.pick(&w.ids), "is_primary": true}])),
        ("q", json!(format!("from:{} in:{} has:image", r.pick(&w.ids), r.pick(&w.ids)))),
        ("role", json!(["admin", "read_only", "owner", "member"][r.below(4)])),
        ("value", json!(r.below(100))),
        ("at", json!(1_790_000_000_000i64 + r.below(1_000_000) as i64)),
        ("handle", json!(["bob", "alice", "root", "new_one"][r.below(4)])),
    ];
    for _ in 0..2 + r.below(6) {
        let (k, v) = &fields[r.below(fields.len())];
        m.insert((*k).to_string(), v.clone());
    }
    Value::Object(m)
}

fn object(r: &mut Rng, w: &World, depth: u32) -> Value {
    let mut m = serde_json::Map::new();
    for _ in 0..r.below(6) {
        m.insert(r.pick(KEYS).to_string(), value(r, w, depth));
    }
    Value::Object(m)
}

struct Request {
    method: String,
    path: String,
    body: Option<(Vec<u8>, &'static str)>,
}

fn request(r: &mut Rng, w: &World, method: &str, template: &str) -> Request {
    let mut path = String::new();
    for seg in template.split('/').skip(1) {
        path.push('/');
        if seg.starts_with('{') {
            let v = if r.below(3) > 0 { r.pick(&w.ids).clone() } else { garbage(r) };
            path.push_str(&url_encode(&v));
        } else {
            path.push_str(seg);
        }
    }
    if r.below(2) == 0 {
        let mut q = Vec::new();
        for _ in 0..r.below(4) {
            let k =
                ["limit", "before", "after", "q", "cursor", "tz", "events", "thumb", "token", "channel_id", "since"]
                    [r.below(11)];
            let v = match r.below(4) {
                0 => r.pick(&w.ids).clone(),
                1 => ["-1", "0", "999999999999", "abc", "1e9"][r.below(5)].to_string(),
                _ => garbage(r),
            };
            q.push(format!("{k}={}", url_encode(&v)));
        }
        path.push('?');
        path.push_str(&q.join("&"));
    }
    let body = match method {
        // a GET body goes unread, and the server closing over unread bytes resets the connection
        "GET" | "HEAD" => None,
        "DELETE" if r.below(4) > 0 => None,
        _ => Some(match r.below(9) {
            0 => (b"{not json".to_vec(), "application/json"),
            1 => (object(r, w, 0).to_string().into_bytes(), "text/plain"),
            2 => (vec![0xff, 0xfe, 0x00, 0x01], "application/octet-stream"),
            3 => (format!("{{\"text\": \"{}\"}}", "y".repeat(3 << 20)).into_bytes(), "application/json"),
            4 => (value(r, w, 0).to_string().into_bytes(), "application/json"),
            5 => (object(r, w, 0).to_string().into_bytes(), "application/json"),
            _ => (plausible(r, w).to_string().into_bytes(), "application/json"),
        }),
    };
    Request { method: method.into(), path, body }
}

fn url_encode(s: &str) -> String {
    s.bytes()
        .map(|b| {
            if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
                (b as char).to_string()
            } else {
                format!("%{b:02X}")
            }
        })
        .collect()
}

#[derive(Clone, Copy, PartialEq)]
enum Caller {
    Anonymous,
    Alice,
    Token(&'static str),
    Root,
    Bob,
}

impl Caller {
    fn bearer(self) -> Option<String> {
        match self {
            Caller::Anonymous => None,
            Caller::Alice => Some("alice-session".into()),
            Caller::Token(s) => Some(token_of(s)),
            Caller::Root => Some("root-session".into()),
            Caller::Bob => Some("bob-session".into()),
        }
    }
}

/// What in `body` the caller may not see; empty if nothing.
fn leaks(caller: Caller, body: &str, asked: &str, w: &World) -> Vec<String> {
    let mut out = Vec::new();
    if caller == Caller::Bob {
        return out;
    }
    if body.contains("SECRETB") {
        out.push("B's text".into());
    }
    for id in &w.b_ids {
        if body.contains(id.as_str()) && !asked.contains(id.as_str()) {
            out.push(format!("B's id {id}"));
        }
    }
    match caller {
        Caller::Anonymous if body.contains("SECRETA") => out.push("A's text to an anonymous caller".into()),
        Caller::Token(s) => {
            if body.contains("SECRETA-msg") && !matches!(s, "read:messages" | "export" | "write:messages") {
                out.push(format!("A's message to a {s} token"));
            }
            if body.contains("SECRETA-post") && !matches!(s, "read:posts" | "export") {
                out.push(format!("A's post to a {s} token"));
            }
        }
        _ => {}
    }
    out
}

fn b_rows(c: &Connection) -> Vec<String> {
    let mut out = Vec::new();
    for q in [
        "SELECT id, name, pronouns, deleted_at FROM member WHERE account_id = ?1",
        "SELECT id, name, deleted_at FROM member_group WHERE account_id = ?1",
        "SELECT id, text, deleted_at, pinned_at FROM message WHERE account_id = ?1",
        "SELECT id, text, deleted_at FROM post WHERE account_id = ?1",
        "SELECT id, note, retracted FROM switch WHERE account_id = ?1",
        "SELECT scope FROM scope_access WHERE account_id = ?1",
    ] {
        let mut st = c.prepare(q).unwrap();
        let n = st.column_count();
        let rows: Vec<String> = st
            .query_map([B], |r| {
                Ok((0..n)
                    .map(|i| format!("{:?}", r.get::<_, rusqlite::types::Value>(i).unwrap()))
                    .collect::<Vec<_>>()
                    .join("|"))
            })
            .unwrap()
            .map(Result::unwrap)
            .collect();
        out.extend(rows);
    }
    out.sort();
    out
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn every_route_survives_what_callers_can_throw_at_it() {
    let seed_n: u64 =
        std::env::var("CHORUS_FUZZ_SEED").ok().and_then(|s| s.parse().ok()).unwrap_or_else(rand::random::<u64>) | 1;
    let rounds: usize = std::env::var("CHORUS_FUZZ_ROUNDS").ok().and_then(|s| s.parse().ok()).unwrap_or(3);
    let panics: Arc<Mutex<Vec<String>>> = Arc::default();
    {
        let panics = panics.clone();
        let default = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            panics.lock().unwrap().push(info.to_string());
            default(info);
        }));
    }
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("rest-fuzz");
    cfg.security.rate_burst = Some(1_000_000);
    cfg.security.rate_per_second = Some(1_000_000.0);
    let test_dir = cfg.server.data_dir.clone();
    let mut conn = db::open(&cfg.db_path()).unwrap();
    db::migrate(&mut conn).unwrap();
    let w = seed(&conn, chorus_server::now_ms());
    let before = b_rows(&conn);
    let state = app::Shared::new(conn, cfg.clone()).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let running = state.clone();
    let server = tokio::spawn(async move {
        axum::serve(listener, app::router(running).into_make_service_with_connect_info::<std::net::SocketAddr>())
            .await
            .unwrap()
    });
    // pooled keep-alive connections, as browsers use them: an answer that comes before the body
    // (401, 413, 415) must still leave the connection usable (drain.rs, R35)
    let client = reqwest::Client::builder().timeout(std::time::Duration::from_secs(10)).build().unwrap();
    let callers: Vec<Caller> = [Caller::Anonymous, Caller::Alice, Caller::Root, Caller::Bob]
        .into_iter()
        .chain(SCOPES.iter().map(|s| Caller::Token(s)))
        .collect();
    // the oracle can see what it looks for: the markers reach their owners
    let own = |path: String, bearer: &'static str| {
        let (client, base) = (client.clone(), base.clone());
        async move { client.get(format!("{base}{path}")).bearer_auth(bearer).send().await.unwrap().text().await.unwrap() }
    };
    assert!(own("/api/v1/members".into(), "bob-session").await.contains("SECRETB-member"), "B sees his members");
    let a_chan = &w.ids[1];
    let text = own(format!("/api/v1/channels/{a_chan}/messages"), "chorus_read_messages").await;
    assert!(text.contains("SECRETA-msg"), "A's read:messages token sees A's messages: {text}");
    let routes = routes();
    assert!(routes.len() > 60, "found only {} routes in app.rs", routes.len());
    let mut r = Rng(seed_n);
    let mut problems = Vec::new();
    let mut sent = 0usize;
    let mut statuses = std::collections::BTreeMap::new();
    for (method, template) in &routes {
        // the sync socket and Chorus Home's install page are other tests' business
        if template.ends_with("/sync") {
            continue;
        }
        for &caller in &callers {
            for _ in 0..rounds {
                let req = request(&mut r, &w, method, template);
                let m = reqwest::Method::from_bytes(req.method.as_bytes()).unwrap();
                let mut rb = client.request(m, format!("{base}{}", req.path));
                if let Some(token) = caller.bearer() {
                    rb = rb.bearer_auth(token);
                }
                let asked = format!(
                    "{} {}",
                    req.path,
                    req.body.as_ref().map(|(b, _)| String::from_utf8_lossy(b).into_owned()).unwrap_or_default()
                );
                if let Some((bytes, ct)) = req.body {
                    rb = rb.header("content-type", ct).body(bytes);
                }
                sent += 1;
                let what = || format!("{} {} as {}", req.method, &req.path[..req.path.len().min(160)], label(caller));
                let resp = match rb.send().await {
                    Ok(resp) => resp,
                    Err(e) if e.is_timeout() => {
                        problems.push(format!("{}: no answer in 10 s", what()));
                        continue;
                    }
                    Err(e) => {
                        let mut chain = e.to_string();
                        let mut src = std::error::Error::source(&e);
                        while let Some(s) = src {
                            chain.push_str(&format!(" / {s}"));
                            src = s.source();
                        }
                        problems.push(format!("{}: the connection failed ({chain})", what()));
                        continue;
                    }
                };
                let status = resp.status();
                *statuses.entry(status.as_u16()).or_insert(0usize) += 1;
                if status.is_server_error() {
                    let text = resp.text().await.unwrap_or_default();
                    problems.push(format!("{}: {status} {}", what(), &text[..text.len().min(300)]));
                    continue;
                }
                let streaming = resp
                    .headers()
                    .get("content-type")
                    .and_then(|v| v.to_str().ok())
                    .is_some_and(|ct| ct.starts_with("text/event-stream"));
                if streaming {
                    continue; // an open stream: the SSE tests read those
                }
                let body = match resp.bytes().await {
                    Ok(b) => String::from_utf8_lossy(&b).into_owned(),
                    Err(e) => {
                        let mut chain = e.to_string();
                        let mut src = std::error::Error::source(&e);
                        while let Some(s) = src {
                            chain.push_str(&format!(" / {s}"));
                            src = s.source();
                        }
                        problems.push(format!("{}: the body broke off ({chain})", what()));
                        continue;
                    }
                };
                for leak in leaks(caller, &body, &asked, &w) {
                    problems.push(format!("{}: {status} shows {leak}", what()));
                }
            }
        }
    }
    let panicked = panics.lock().unwrap().clone();
    for p in panicked {
        problems.push(format!("panic: {p}"));
    }
    let after = {
        let c = db::open(&test_dir.join("chorus.db")).unwrap();
        b_rows(&c)
    };
    if before != after {
        problems.push(format!("B's rows changed:\n  before {before:?}\n  after  {after:?}"));
    }
    // open streams and background jobs may still hold the state: no strict release here
    server.abort();
    let _ = server.await;
    drop(client);
    drop(state);
    let _ = std::fs::remove_dir_all(&test_dir);
    // panics first: they explain the rest
    problems.sort_by_key(|p| !p.starts_with("panic"));
    let distinct: Vec<&String> = problems.iter().collect::<BTreeSet<_>>().into_iter().collect();
    assert!(
        problems.is_empty(),
        "seed {seed_n}: {} problems in {sent} requests over {} routes:\n{}",
        problems.len(),
        routes.len(),
        distinct.iter().take(60).map(|p| format!("  - {p}")).collect::<Vec<_>>().join("\n")
    );
    println!("seed {seed_n}: {sent} requests over {} routes, all well; statuses {statuses:?}", routes.len());
}

/// API tokens look like `chorus_…` (api_data.rs).
fn token_of(scope: &str) -> String {
    format!("chorus_{}", scope.replace(':', "_"))
}

fn label(c: Caller) -> String {
    match c {
        Caller::Anonymous => "anonymous".into(),
        Caller::Alice => "A's device".into(),
        Caller::Token(s) => format!("A's {s} token"),
        Caller::Root => "an admin".into(),
        Caller::Bob => "B".into(),
    }
}
