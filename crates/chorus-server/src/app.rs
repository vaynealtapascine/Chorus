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
    /// Live events for `/api/v1/stream`: (account id, event JSON).
    pub events: tokio::sync::broadcast::Sender<(String, serde_json::Value)>,
}

pub type AppState = Arc<Shared>;

impl Shared {
    pub fn new(conn: Connection, cfg: Config) -> anyhow::Result<AppState> {
        let instance_id = db::meta(&conn, "instance_id")?.unwrap_or_default();
        let (events, _) = tokio::sync::broadcast::channel(256);
        Ok(Arc::new(Shared { db: Mutex::new(conn), cfg, instance_id, peers: Mutex::new(HashMap::new()), events }))
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
        .route("/devices/push", put(push_register).delete(push_unregister))
        .route("/android/latest", get(android_latest))
        .route("/notifications", get(notifications))
        .route("/tokens", get(tokens_list).post(tokens_create))
        .route("/tokens/{id}", delete(tokens_revoke))
        .route("/front", get(front_now))
        .route("/front/switches", get(front_switches))
        .route("/front/intervals", get(front_intervals))
        .route("/members", get(members_list))
        .route("/stream", get(stream))
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
    let mut app = Router::new()
        .nest("/api/v1", api)
        .route("/download/android", get(android_download))
        .route("/overlay/front", get(overlay_front))
        .with_state(state.clone());
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

/// This device's UnifiedPush endpoint and Web Push keys (NOTIFICATIONS.md §1).
async fn push_register(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(b): Json<crate::push::Registration>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    crate::push::register(&conn, &me.device_id, &b)
        .map_err(|e| ApiError(StatusCode::BAD_REQUEST, "bad_request", e.to_string()))?;
    Ok(StatusCode::NO_CONTENT)
}

async fn push_unregister(State(s): State<AppState>, headers: axum::http::HeaderMap) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    crate::push::unregister(&conn, &me.device_id)?;
    Ok(StatusCode::NO_CONTENT)
}

/// The released Android build, if the owner deployed one (`chorus.json` beside `chorus.apk`).
fn android_release(s: &AppState) -> Option<serde_json::Value> {
    let dir = s.cfg.android_dir()?;
    let meta: serde_json::Value = serde_json::from_slice(&std::fs::read(dir.join("chorus.json")).ok()?).ok()?;
    dir.join("chorus.apk").is_file().then_some(meta)
}

/// `{version_code, version_name, sha256, size, changelog}` for the in-app updater (CLIENTS.md §5a).
async fn android_latest(State(s): State<AppState>) -> Result<Json<serde_json::Value>, ApiError> {
    let mut meta = android_release(&s)
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "not_found", "no Android build published".into()))?;
    meta["url"] = json!(format!("{}/download/android", s.cfg.server.public_url.trim_end_matches('/')));
    Ok(Json(meta))
}

async fn android_download(State(s): State<AppState>) -> Result<Response, ApiError> {
    let dir =
        s.cfg.android_dir().ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "not_found", "no Android build".into()))?;
    let bytes = tokio::fs::read(dir.join("chorus.apk"))
        .await
        .map_err(|_| ApiError(StatusCode::NOT_FOUND, "not_found", "no Android build".into()))?;
    Ok((
        [
            (axum::http::header::CONTENT_TYPE, "application/vnd.android.package-archive"),
            (axum::http::header::CONTENT_DISPOSITION, "attachment; filename=\"chorus.apk\""),
        ],
        bytes,
    )
        .into_response())
}

// ─── your data: tokens, reads, stream (api_data.rs) ─────────────────────────

impl From<crate::api_data::DataError> for ApiError {
    fn from(e: crate::api_data::DataError) -> Self {
        use crate::api_data::DataError as D;
        let (s, c) = match &e {
            D::Unauthenticated => (StatusCode::UNAUTHORIZED, "unauthenticated"),
            D::Scope(_) => (StatusCode::FORBIDDEN, "forbidden"),
            D::Bad(_) => (StatusCode::BAD_REQUEST, "bad_request"),
            D::Internal(e) => {
                tracing::error!(error = %e, "internal error");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal")
            }
        };
        ApiError(s, c, e.to_string())
    }
}

fn principal(
    s: &AppState,
    conn: &Connection,
    headers: &axum::http::HeaderMap,
) -> Result<crate::api_data::Principal, ApiError> {
    Ok(crate::api_data::principal(conn, bearer(headers)?, now_ms(), s.session_ttl())?)
}

#[derive(Deserialize)]
struct TokenIn {
    name: String,
    scopes: Vec<String>,
}

async fn tokens_create(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(b): Json<TokenIn>,
) -> Result<Response, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    let v = crate::api_data::create_token(&conn, &p, &b.name, &b.scopes, now_ms())?;
    Ok((StatusCode::CREATED, Json(v)).into_response())
}

async fn tokens_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_data::list_tokens(&conn, &p)?))
}

async fn tokens_revoke(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    crate::api_data::revoke_token(&conn, &p, &id, now_ms())?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize, Default)]
struct Range {
    from: Option<i64>,
    to: Option<i64>,
    limit: Option<i64>,
    /// EventSource can't send headers, so the stream (only) also takes `?token=`.
    token: Option<String>,
}

async fn front_now(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_data::current_front(&conn, &p)?))
}

async fn front_switches(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<Range>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_data::switches(&conn, &p, q.from, q.to, q.limit)?))
}

async fn front_intervals(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<Range>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_data::intervals(&conn, &p, q.from, q.to)?))
}

async fn members_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_data::members(&conn, &p)?))
}

/// Server-sent events of the caller's own front (`stream` + `read:front`). The first event is the
/// current front, then one per change.
async fn stream(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<Range>,
) -> Result<Response, ApiError> {
    use axum::response::sse::{Event, KeepAlive, Sse};
    let (account, first) = {
        let conn = s.db();
        let p = match q.token.as_deref() {
            Some(t) => crate::api_data::principal(&conn, t, now_ms(), s.session_ttl())?,
            None => principal(&s, &conn, &headers)?,
        };
        if !(p.allows("stream") && p.allows("read:front")) {
            return Err(crate::api_data::DataError::Scope("stream").into());
        }
        let v = crate::api_data::current_front(&conn, &p)?;
        (p.account_id.clone(), json!({"type": "front", "at": now_ms(), "front": v["front"], "since": v["since"]}))
    };
    let rx = s.events.subscribe();
    let first = futures_util::stream::once(async move {
        Ok::<_, std::convert::Infallible>(Event::default().event("front").data(first.to_string()))
    });
    let rest = futures_util::stream::unfold((rx, account), |(mut rx, account)| async move {
        loop {
            match rx.recv().await {
                Ok((a, v)) if a == account => {
                    return Some((Ok(Event::default().event("front").data(v.to_string())), (rx, account)));
                }
                Ok(_) | Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => return None,
            }
        }
    });
    Ok(Sse::new(futures_util::StreamExt::chain(first, rest)).keep_alive(KeepAlive::default()).into_response())
}

/// A transparent "who's fronting" pill for OBS: `/overlay/front?token=chorus_…` (API.md §6).
async fn overlay_front() -> Response {
    ([(axum::http::header::CONTENT_TYPE, "text/html; charset=utf-8")], include_str!("../assets/overlay-front.html"))
        .into_response()
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
        let http = reqwest::Client::builder().timeout(std::time::Duration::from_secs(15)).build().unwrap_or_default();
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(5));
        loop {
            tick.tick().await;
            let pushes = {
                let conn = state.db();
                match crate::notifier::process_due(&conn, now_ms()) {
                    Ok(p) => p.pushes,
                    Err(e) => {
                        tracing::error!(error = %e, "notifier failed");
                        continue;
                    }
                }
            };
            // send without holding the database lock
            for o in pushes {
                let sent = crate::push::send(&http, &o).await;
                if let Err(e) = crate::push::record(&state.db(), &o.device_id, &sent) {
                    tracing::error!(error = %e, "push: can't record outcome");
                }
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
    // the owner's live stream: one front event per account that just changed
    let changed: BTreeSet<&str> = fresh
        .iter()
        .filter(|o| o.kind.starts_with("front."))
        .filter_map(|o| o.scope.strip_prefix("account:"))
        .collect();
    for account in changed {
        if s.events.receiver_count() > 0
            && let Ok(v) = crate::api_data::current_front(conn, &crate::api_data::Principal::owner(account))
        {
            let event = json!({"type": "front", "at": now_ms(), "front": v["front"], "since": v["since"]});
            let _ = s.events.send((account.to_string(), event));
        }
    }
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
