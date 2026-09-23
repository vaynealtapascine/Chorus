//! HTTP + WebSocket server (docs/API.md, docs/SYNC.md §6).

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::{Arc, Mutex};

use axum::extract::State;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{DefaultBodyLimit, Path};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post, put};
use axum::{Json, Router};
use chorus_core::op::Op;
use chorus_core::sync::{Frame, PAGE_OPS};
use chorus_core::time::ClockSample;
use rusqlite::Connection;
use serde::Deserialize;
use serde_json::json;
use tokio::sync::mpsc;

use crate::auth::{self, AuthError};
use crate::config::Config;
use crate::follows::{self, FollowError};
use crate::{blobs, db, ingest, now_ms, oplog};

/// A connected device.
struct Peer {
    account: String,
    scopes: BTreeSet<String>,
    tx: mpsc::UnboundedSender<Frame>,
}

pub struct Shared {
    pub db: Mutex<Connection>,
    pub cfg: Config,
    pub instance_id: String,
    peers: Mutex<HashMap<String, Peer>>,
}

pub type AppState = Arc<Shared>;

impl Shared {
    pub fn new(conn: Connection, cfg: Config) -> anyhow::Result<AppState> {
        let instance_id = db::meta(&conn, "instance_id")?.unwrap_or_default();
        Ok(Arc::new(Shared { db: Mutex::new(conn), cfg, instance_id, peers: Mutex::new(HashMap::new()) }))
    }

    fn db(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.db.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn session_ttl(&self) -> i64 {
        self.cfg.limits.session_days as i64 * 86_400_000
    }

    fn epoch(&self, conn: &Connection) -> String {
        let e = db::meta(conn, "epoch").ok().flatten().unwrap_or_else(|| "1".into());
        format!("{}:{e}", self.instance_id)
    }
}

pub fn router(state: AppState) -> Router {
    let api = Router::new()
        .route("/server", get(server_info))
        .route("/auth/redeem", post(redeem))
        .route("/auth/challenge", post(challenge))
        .route("/auth/session", post(session))
        .route("/devices/invite", post(device_invite))
        .route("/follows", get(follows_list).post(follow_request))
        .route("/follows/{id}/prefs", put(follow_prefs))
        .route("/follows/{id}", delete(follow_end))
        .route("/notifications", get(notifications))
        .route("/emoji", get(emoji_list))
        .route("/accounts/{id}/view", get(account_view))
        .route(
            "/blobs/{hash}",
            get(blobs::get_blob)
                .head(blobs::head_blob)
                .put(blobs::put_blob)
                .layer(DefaultBodyLimit::max(4 * 1024 * 1024)),
        )
        .route("/sync", get(sync_ws));
    let mut app = Router::new().nest("/api/v1", api).with_state(state.clone());
    if let Some(dir) = state.cfg.server.web_dir.clone() {
        let index = dir.join("index.html");
        app = app.fallback_service(
            tower_http::services::ServeDir::new(dir).fallback(tower_http::services::ServeFile::new(index)),
        );
    }
    app
}

// ─── errors ──────────────────────────────────────────────────────────────────

pub struct ApiError(StatusCode, &'static str, String);

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"error": {"code": self.1, "message": self.2, "retry": self.0.is_server_error()}})))
            .into_response()
    }
}

impl From<AuthError> for ApiError {
    fn from(e: AuthError) -> Self {
        let (s, c) = match &e {
            AuthError::BadInvite | AuthError::BadRequest(_) | AuthError::BadKey => {
                (StatusCode::BAD_REQUEST, "bad_request")
            }
            AuthError::HandleTaken => (StatusCode::CONFLICT, "conflict"),
            AuthError::BadSignature | AuthError::BadSession => (StatusCode::UNAUTHORIZED, "unauthenticated"),
            AuthError::UnknownDevice => (StatusCode::FORBIDDEN, "forbidden"),
            AuthError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, "internal"),
        };
        if matches!(e, AuthError::Internal(_)) {
            tracing::error!(error = %e, "internal error");
        }
        ApiError(s, c, e.to_string())
    }
}

impl From<anyhow::Error> for ApiError {
    fn from(e: anyhow::Error) -> Self {
        tracing::error!(error = %e, "internal error");
        ApiError(StatusCode::INTERNAL_SERVER_ERROR, "internal", "internal error".into())
    }
}

// ─── http ────────────────────────────────────────────────────────────────────

async fn server_info(State(s): State<AppState>) -> Json<serde_json::Value> {
    Json(json!({
        "name": "Chorus",
        "version": env!("CARGO_PKG_VERSION"),
        "core": chorus_core::api::version(),
        "instance_id": s.instance_id,
        "core_min": "0.1.0",
    }))
}

#[derive(Deserialize)]
struct RedeemIn {
    code: String,
    device: auth::DeviceIn,
    #[serde(default)]
    account: Option<auth::AccountIn>,
}

async fn redeem(State(s): State<AppState>, Json(b): Json<RedeemIn>) -> Result<Response, ApiError> {
    let e = {
        let mut conn = s.db();
        auth::redeem(&mut conn, &b.code, &b.device, b.account.as_ref(), now_ms(), s.session_ttl())?
    };
    Ok((StatusCode::CREATED, Json(e)).into_response())
}

#[derive(Deserialize)]
struct ChallengeIn {
    device_id: String,
}

async fn challenge(State(s): State<AppState>, Json(b): Json<ChallengeIn>) -> Result<Json<serde_json::Value>, ApiError> {
    let nonce = auth::challenge(&s.db(), &b.device_id, now_ms())?;
    Ok(Json(json!({"nonce": nonce, "instance_id": s.instance_id})))
}

#[derive(Deserialize)]
struct SessionIn {
    device_id: String,
    nonce: String,
    signature: String,
}

async fn session(State(s): State<AppState>, Json(b): Json<SessionIn>) -> Result<Json<serde_json::Value>, ApiError> {
    let ttl = s.session_ttl();
    let now = now_ms();
    let token = auth::verify(&s.db(), &b.device_id, &b.nonce, &b.signature, &s.instance_id, now, ttl)?;
    let is_admin: bool = s
        .db()
        .query_row(
            "SELECT a.is_admin FROM account a JOIN device d ON d.account_id = a.id WHERE d.id = ?1",
            [&b.device_id],
            |r| r.get(0),
        )
        .map_err(anyhow::Error::from)?;
    Ok(Json(json!({"session": token, "expires_at": now + ttl, "is_admin": is_admin})))
}

fn bearer(headers: &axum::http::HeaderMap) -> Result<&str, ApiError> {
    headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| ApiError(StatusCode::UNAUTHORIZED, "unauthenticated", "missing session".into()))
}

/// Server-wide active emoji for clients and integrations that do not read the sync log.
async fn emoji_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    auth::authenticate(&conn, bearer(&headers)?, now_ms(), s.session_ttl())?;
    let mut stmt = conn
        .prepare(
            "SELECT id, name, aliases, category, blob_hash, is_animated FROM custom_emoji
         WHERE deleted_at IS NULL ORDER BY coalesce(category, ''), name",
        )
        .map_err(anyhow::Error::from)?;
    let rows = stmt
        .query_map([], |r| {
            let aliases: String = r.get(2)?;
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "name": r.get::<_, String>(1)?,
                "aliases": serde_json::from_str::<serde_json::Value>(&aliases).unwrap_or_else(|_| json!([])),
                "category": r.get::<_, Option<String>>(3)?,
                "blob_hash": r.get::<_, String>(4)?,
                "is_animated": r.get::<_, bool>(5)?,
            }))
        })
        .map_err(anyhow::Error::from)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(anyhow::Error::from)?;
    Ok(Json(json!({"emoji": rows})))
}

/// A one-use, 1-day invite that links another device to the caller's account (API.md §2.1).
async fn device_invite(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let now = now_ms();
    let conn = s.db();
    let who = auth::authenticate(&conn, bearer(&headers)?, now, s.session_ttl())?;
    let code = auth::create_invite(
        &conn,
        auth::InviteKind::Device,
        Some(&who.account_id),
        &who.device_id,
        86_400_000,
        1,
        now,
    )?;
    let url = format!("{}/i/{code}", s.cfg.server.public_url.trim_end_matches('/'));
    Ok(Json(json!({"code": code, "url": url, "expires_at": now + 86_400_000})))
}

// ─── follows (API.md §3) ────────────────────────────────────────────────────

impl From<FollowError> for ApiError {
    fn from(e: FollowError) -> Self {
        let (s, c) = match &e {
            FollowError::NoSuchAccount | FollowError::NotFound => (StatusCode::NOT_FOUND, "not_found"),
            FollowError::SelfFollow | FollowError::BadPrefs(_) => (StatusCode::BAD_REQUEST, "bad_request"),
            FollowError::NotFollower => (StatusCode::FORBIDDEN, "forbidden"),
            FollowError::Internal(e) => {
                tracing::error!(error = %e, "internal error");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal")
            }
        };
        ApiError(s, c, e.to_string())
    }
}

fn who(s: &AppState, conn: &Connection, headers: &axum::http::HeaderMap) -> Result<auth::Authed, ApiError> {
    Ok(auth::authenticate(conn, bearer(headers)?, now_ms(), s.session_ttl())?)
}

async fn follows_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    Ok(Json(follows::list(&conn, &me.account_id)?))
}

#[derive(Deserialize)]
struct FollowIn {
    /// A handle (`@stars`) or account id.
    target: String,
}

async fn follow_request(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(b): Json<FollowIn>,
) -> Result<Response, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    let (id, op) = follows::request(&conn, &me.account_id, &b.target, now_ms())?;
    let created = op.is_some();
    if let Some(o) = op {
        fan_out(&s, &conn, &[o], None)?;
    }
    let status = if created { StatusCode::CREATED } else { StatusCode::OK };
    Ok((status, Json(json!({"id": id}))).into_response())
}

async fn follow_prefs(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
    Json(prefs): Json<serde_json::Value>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    let o = follows::set_prefs(&conn, &me.account_id, &id, prefs, now_ms())?;
    fan_out(&s, &conn, &[o], None)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn follow_end(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    if let Some(o) = follows::end(&conn, &me.account_id, &id, now_ms())? {
        fan_out(&s, &conn, &[o], None)?;
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn notifications(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    Ok(Json(crate::notifier::list(&conn, &me.account_id, 100)?))
}

/// A follower's view of another account: only what has been revealed (NOTIFICATIONS.md §5).
async fn account_view(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    crate::notifier::follower_view(&conn, &me.account_id, &id)?
        .map(Json)
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "not_found", "you don't follow this account".into()))
}

/// Reveal and deliver due follower notifications every few seconds (notifier.rs).
fn run_notifier(state: AppState) {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(5));
        loop {
            tick.tick().await;
            let conn = state.db();
            if let Err(e) = crate::notifier::process_due(&conn, now_ms()) {
                tracing::error!(error = %e, "notifier failed");
            }
        }
    });
}

// ─── sync socket ─────────────────────────────────────────────────────────────

async fn sync_ws(State(s): State<AppState>, ws: WebSocketUpgrade) -> Response {
    ws.max_message_size(8 << 20).on_upgrade(move |socket| run_socket(s, socket))
}

fn send(tx: &mpsc::UnboundedSender<Frame>, f: Frame) {
    let _ = tx.send(f);
}

/// Catch-up for one scope, queued on `tx` (caller holds the db lock, so nothing can interleave).
fn catch_up(conn: &Connection, tx: &mpsc::UnboundedSender<Frame>, scope: &str, after: i64) -> anyhow::Result<()> {
    let mut cursor = after;
    loop {
        let ops = oplog::scope_after(conn, scope, cursor, PAGE_OPS)?;
        let Some(last) = ops.last().and_then(|o| o.seq) else { break };
        cursor = last;
        let n = ops.len();
        send(tx, Frame::Ops { scope: scope.into(), ops, to: last });
        if n < PAGE_OPS {
            break;
        }
    }
    let to = oplog::max_seq(conn, scope)?;
    send(tx, Frame::Caught { scope: scope.into(), to, digest: oplog::digest(conn, scope)? });
    Ok(())
}

async fn run_socket(s: AppState, socket: WebSocket) {
    use futures_util::{SinkExt, StreamExt};
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Frame>();
    let writer = tokio::spawn(async move {
        while let Some(f) = rx.recv().await {
            let Ok(text) = serde_json::to_string(&f) else { continue };
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
    });

    let mut me: Option<(String, ingest::Session)> = None; // (device, session)
    while let Some(Ok(msg)) = stream.next().await {
        let text = match msg {
            Message::Text(t) => t.to_string(),
            Message::Close(_) => break,
            _ => continue,
        };
        let frame: Frame = match serde_json::from_str(&text) {
            Ok(f) => f,
            Err(e) => {
                send(&tx, Frame::Error { code: "bad_request".into(), message: format!("bad frame: {e}") });
                continue;
            }
        };
        let result = match (&me, frame) {
            (None, Frame::Hello { token, epoch, cursors, clock, .. }) => {
                match hello(&s, &tx, &token, epoch, cursors, clock) {
                    Ok(session) => {
                        me = Some((session.device_id.clone(), session));
                        Ok(())
                    }
                    Err(e) => {
                        let code = if matches!(e, AuthError::Internal(_)) { "internal" } else { "unauthenticated" };
                        send(&tx, Frame::Error { code: code.into(), message: e.to_string() });
                        break;
                    }
                }
            }
            (None, _) => {
                send(&tx, Frame::Error { code: "unauthenticated".into(), message: "send hello first".into() });
                break;
            }
            (Some((_, sess)), Frame::Push { batch, ops, restore }) => push(&s, &tx, sess, batch, ops, restore),
            (Some((_, sess)), Frame::Pull { scope, after }) => {
                let conn = s.db();
                if ingest::can_access(&conn, &sess.account_id, &scope).unwrap_or(false) {
                    catch_up(&conn, &tx, &scope, after)
                } else {
                    Ok(())
                }
            }
            (Some(_), Frame::Ping { .. }) => {
                send(&tx, Frame::Pong { server_time: now_ms() });
                Ok(())
            }
            (Some(_), _) => Ok(()),
        };
        if let Err(e) = result {
            tracing::error!(error = %e, "sync error");
            send(&tx, Frame::Error { code: "internal".into(), message: "internal error".into() });
        }
    }
    if let Some((device, _)) = me {
        s.peers.lock().unwrap_or_else(|e| e.into_inner()).remove(&device);
    }
    drop(tx);
    let _ = writer.await;
}

fn hello(
    s: &AppState,
    tx: &mpsc::UnboundedSender<Frame>,
    token: &str,
    epoch: Option<String>,
    cursors: BTreeMap<String, i64>,
    clock: chorus_core::sync::ClockReading,
) -> Result<ingest::Session, AuthError> {
    let now = now_ms();
    let conn = s.db();
    let who = auth::authenticate(&conn, token, now, s.session_ttl())?;
    let scopes = ingest::scopes_of(&conn, &who.account_id)?;
    let offset = now - clock.wall;
    conn.execute("UPDATE device SET clock_offset_ms = ?2 WHERE id = ?1", rusqlite::params![who.device_id, offset])?;
    let mut max_seq = BTreeMap::new();
    for sc in &scopes {
        max_seq.insert(sc.clone(), oplog::max_seq(&conn, sc)?);
    }
    let server_epoch = s.epoch(&conn);
    let reconcile = epoch.as_ref().is_some_and(|e| *e != server_epoch)
        || cursors.iter().any(|(sc, c)| *c > *max_seq.get(sc).unwrap_or(&0));
    send(
        tx,
        Frame::Welcome {
            server_time: now,
            epoch: server_epoch,
            account_id: who.account_id.clone(),
            offset_ms: offset,
            scopes: scopes.clone(),
            max_seq,
            reconcile,
            core_min: "0.1.0".into(),
        },
    );
    if !reconcile {
        for sc in &scopes {
            catch_up(&conn, tx, sc, *cursors.get(sc).unwrap_or(&0))?;
        }
    }
    // registered while still holding the db lock: live ops can't overtake the catch-up
    s.peers.lock().unwrap_or_else(|e| e.into_inner()).insert(
        who.device_id.clone(),
        Peer { account: who.account_id.clone(), scopes: scopes.into_iter().collect(), tx: tx.clone() },
    );
    Ok(ingest::Session {
        account_id: who.account_id,
        device_id: who.device_id,
        sample: ClockSample { server_time: now, mono: clock.mono, boot_id: clock.boot_id, offset_ms: offset },
    })
}

fn push(
    s: &AppState,
    tx: &mpsc::UnboundedSender<Frame>,
    sess: &ingest::Session,
    batch: String,
    ops: Vec<Op>,
    restore: bool,
) -> anyhow::Result<()> {
    let now = now_ms();
    let mut conn = s.db();
    let t = conn.transaction()?;
    let mut results = Vec::with_capacity(ops.len());
    let mut fresh: Vec<Op> = Vec::new();
    for o in ops {
        let (r, new) = ingest::accept(&t, sess, o, now, restore)?;
        results.push(r);
        fresh.extend(new);
    }
    t.commit()?;
    send(tx, Frame::Ack { batch, results });
    if fresh.is_empty() {
        return Ok(());
    }
    fan_out(s, &conn, &fresh, Some(&sess.device_id))
}

/// Send freshly accepted ops to every connected device that reads their scope (and tell devices
/// whose scopes changed). Call it while still holding the db lock so per-connection order matches
/// seq order. `skip` is the device that pushed them (it already has them).
pub(crate) fn fan_out(s: &AppState, conn: &Connection, fresh: &[Op], skip: Option<&str>) -> anyhow::Result<()> {
    let mut peers = s.peers.lock().unwrap_or_else(|e| e.into_inner());
    for (device, p) in peers.iter_mut() {
        // scope changes (e.g. someone was added to a space)
        let now_scopes: BTreeSet<String> = ingest::scopes_of(conn, &p.account)?.into_iter().collect();
        if now_scopes != p.scopes {
            let add: Vec<String> = now_scopes.difference(&p.scopes).cloned().collect();
            let remove: Vec<String> = p.scopes.difference(&now_scopes).cloned().collect();
            p.scopes = now_scopes;
            send(&p.tx, Frame::Scope { add, remove });
        }
        if skip == Some(device.as_str()) {
            continue;
        }
        let mut by_scope: BTreeMap<&str, Vec<Op>> = BTreeMap::new();
        for o in fresh.iter().filter(|o| p.scopes.contains(&o.scope)) {
            by_scope.entry(o.scope.as_str()).or_default().push(o.clone());
        }
        for (scope, ops) in by_scope {
            let to = ops.last().and_then(|o| o.seq).unwrap_or(0);
            send(&p.tx, Frame::Ops { scope: scope.into(), ops, to });
        }
    }
    Ok(())
}

/// When `CHORUS_RESTART_ON_CHANGE=1` (set by the service installer), exit as soon as our own
/// executable is replaced, so the service manager starts the new build. `scripts/deploy.ps1`
/// renames the running exe (Windows allows that) and copies the new one into its place.
fn watch_own_binary() {
    if std::env::var("CHORUS_RESTART_ON_CHANGE").as_deref() != Ok("1") {
        return;
    }
    let Ok(exe) = std::env::current_exe() else { return };
    let stamp = |p: &std::path::Path| std::fs::metadata(p).ok().map(|m| (m.len(), m.modified().ok()));
    let start = stamp(&exe);
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            let now = stamp(&exe);
            if now.is_some() && now != start {
                tracing::info!("new build deployed; exiting so the service restarts it");
                std::process::exit(0);
            }
        }
    });
}

/// Bind and serve until Ctrl-C.
pub async fn serve(state: AppState) -> anyhow::Result<()> {
    let addr = state.cfg.server.listen.clone();
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!(%addr, "chorus-server listening");
    watch_own_binary();
    run_notifier(state.clone());
    crate::backup::start_nightly(state.cfg.clone())?;
    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
    let server = axum::serve(listener, router(state)).with_graceful_shutdown(async {
        let _ = tokio::signal::ctrl_c().await;
        tracing::info!("stopping");
        let _ = stop_tx.send(());
    });
    // Open sync sockets would hold a graceful shutdown forever; give them a moment, then go.
    let deadline = async {
        let _ = stop_rx.await;
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    };
    tokio::select! {
        r = server => r?,
        _ = deadline => tracing::info!("sockets still open after 2 s; exiting anyway"),
    }
    Ok(())
}
