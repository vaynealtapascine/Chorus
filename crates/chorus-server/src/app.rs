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

/// Open sync sockets, for the per-address and per-account limits (OPS.md §9).
#[derive(Default)]
struct Sockets {
    next_id: u64,
    /// Sockets that haven't signed in yet, by client address.
    unsigned: HashMap<String, usize>,
    /// Signed-in sockets by account, oldest first, each with the way to close it.
    by_account: HashMap<String, Vec<(u64, Arc<tokio::sync::Notify>)>>,
}

pub struct Shared {
    pub db: Mutex<Connection>,
    pub cfg: Config,
    pub instance_id: String,
    peers: Mutex<HashMap<String, Peer>>,
    /// Live events for `/api/v1/stream`: (account id, event JSON).
    pub events: tokio::sync::broadcast::Sender<(String, serde_json::Value)>,
    /// Webhook deliveries (and their retries) for the delivery task (webhooks.rs).
    hooks: mpsc::UnboundedSender<crate::webhooks::Delivery>,
    hook_rx: Mutex<Option<mpsc::UnboundedReceiver<crate::webhooks::Delivery>>>,
    pub(crate) limiter: crate::ratelimit::Limiter,
    sockets: Mutex<Sockets>,
}

pub type AppState = Arc<Shared>;

impl Shared {
    pub fn new(conn: Connection, cfg: Config) -> anyhow::Result<AppState> {
        let instance_id = db::meta(&conn, "instance_id")?.unwrap_or_default();
        // browser push services want to know how to reach the operator (RFC 8292 `sub`)
        let url = &cfg.server.public_url;
        let sub = if url.starts_with("https://") { url.clone() } else { crate::push::DEFAULT_SUBJECT.into() };
        db::set_meta(&conn, "push_subject", &sub)?;
        let (events, _) = tokio::sync::broadcast::channel(256);
        let (hooks, hook_rx) = mpsc::unbounded_channel();
        let (burst, per_second) = cfg.security.rate();
        Ok(Arc::new(Shared {
            limiter: crate::ratelimit::Limiter::new(burst, per_second),
            db: Mutex::new(conn),
            cfg,
            instance_id,
            peers: Mutex::new(HashMap::new()),
            events,
            hooks,
            hook_rx: Mutex::new(Some(hook_rx)),
            sockets: Mutex::new(Sockets::default()),
        }))
    }

    fn db(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.db.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn sockets(&self) -> std::sync::MutexGuard<'_, Sockets> {
        self.sockets.lock().unwrap_or_else(|e| e.into_inner())
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
    if let Some(rx) = state.hook_rx.lock().unwrap_or_else(|e| e.into_inner()).take() {
        run_webhooks(state.clone(), rx);
    }
    let api = Router::new()
        .route("/server", get(server_info))
        .route("/auth/redeem", post(redeem))
        .route("/auth/challenge", post(challenge))
        .route("/auth/session", post(session))
        .route("/devices/invite", post(device_invite))
        .route("/devices/{id}/revoke", post(device_revoke))
        .route("/follows", get(follows_list).post(follow_request))
        .route("/follows/{id}/prefs", put(follow_prefs))
        .route("/follows/{id}", delete(follow_end))
        .route("/devices/push", put(push_register).delete(push_unregister))
        .route("/push/vapid", get(push_vapid))
        .route("/android/latest", get(android_latest))
        .route("/notifications", get(notifications))
        .route("/tokens", get(tokens_list).post(tokens_create))
        .route("/tokens/{id}", delete(tokens_revoke))
        .route("/exports/ops.jsonl", get(export_ops))
        .route("/exports/csv/{name}", get(export_csv))
        .route("/exports/account.sqlite", get(export_sqlite))
        .route("/admin/health", get(admin_health))
        .route("/admin/reconcile/close", post(admin_reconcile_close))
        .route("/webhooks", get(webhooks_list).post(webhooks_create))
        .route("/webhooks/{id}", put(webhooks_update).delete(webhooks_remove))
        .route("/webhooks/{id}/test", post(webhooks_test))
        .route("/front", get(front_now))
        .route("/front/switches", get(front_switches))
        .route("/front/intervals", get(front_intervals))
        .route("/members", get(members_list))
        .route("/members/{id}", get(member_one))
        .route("/groups", get(groups_list))
        .route("/fields", get(fields_list))
        .route("/states", get(states_list))
        .route("/front/switch", post(front_switch))
        .route("/front/daily", get(front_daily))
        .route("/front/reviews", get(front_reviews))
        .route("/search/messages", get(search_messages))
        .route("/search/posts", get(search_posts))
        .route("/messages/{id}", get(search_message))
        .route("/messages/{id}/thread", get(message_thread))
        .route("/spaces/{id}/channels", get(space_channels))
        .route("/channels/{id}/messages", get(channel_messages).post(channel_send))
        .route("/posts", get(posts_list))
        .route("/posts/{id}", get(post_one))
        .route("/me", get(me))
        .route("/stream", get(stream))
        .route("/spaces", get(spaces_list).post(spaces_create))
        .route("/spaces/{id}/members", post(spaces_add))
        .route("/spaces/{id}/members/me", delete(spaces_leave))
        .route("/spaces/{id}/authors", get(spaces_authors))
        .route("/emoji", get(emoji_list))
        .route("/accounts/{id}/view", get(account_view))
        .route(
            "/blobs/{hash}",
            get(blobs::get_blob)
                .head(blobs::head_blob)
                .put(blobs::put_blob)
                .layer(DefaultBodyLimit::max(4 * 1024 * 1024)),
        )
        .route("/sync", get(sync_ws))
        .layer(axum::middleware::from_fn_with_state(state.clone(), crate::ratelimit::limit));
    let mut app = Router::new()
        .nest("/api/v1", api)
        .route("/download/android", get(android_download))
        .route("/overlay/front", get(overlay_front))
        .with_state(state.clone());
    if let Some(dir) = state.cfg.server.web_dir.clone() {
        let index = dir.join("index.html");
        let spa = Router::new()
            .fallback_service(
                tower_http::services::ServeDir::new(dir).fallback(tower_http::services::ServeFile::new(index)),
            )
            .layer(axum::middleware::map_response(web_app_headers));
        app = app.fallback_service(spa);
    }
    app
}

/// The web app loads nothing from elsewhere: a strict policy keeps an injected script (or a
/// hostile page framing it) from reaching the session it keeps. `wasm-unsafe-eval` is the core.
const WEB_CSP: &str = concat!(
    "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; ",
    "img-src 'self' blob: data:; media-src 'self' blob:; font-src 'self' data:; connect-src 'self'; ",
    "worker-src 'self'; manifest-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; ",
    "frame-ancestors 'self'"
);

async fn web_app_headers(mut r: Response) -> Response {
    let h = r.headers_mut();
    h.insert(axum::http::header::CONTENT_SECURITY_POLICY, axum::http::HeaderValue::from_static(WEB_CSP));
    h.insert(axum::http::header::X_CONTENT_TYPE_OPTIONS, axum::http::HeaderValue::from_static("nosniff"));
    r
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
    // scan with the other device's camera instead of copying the link across (qr.rs)
    let qr_svg = crate::qr::encode(&url).map(|q| q.svg());
    Ok(Json(json!({"code": code, "url": url, "qr_svg": qr_svg, "expires_at": now + 86_400_000})))
}

/// Sign out another device of the same account (a lost phone): its sessions end, its socket is
/// dropped from the fan-out and closed on its next frame, and it can't renew.
async fn device_revoke(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    if id == me.device_id {
        return Err(ApiError(StatusCode::BAD_REQUEST, "bad_request", "this is the device you're using".into()));
    }
    let owner: Option<String> = rusqlite::OptionalExtension::optional(conn.query_row(
        "SELECT account_id FROM device WHERE id = ?1 AND revoked_at IS NULL",
        [&id],
        |r| r.get(0),
    ))
    .map_err(anyhow::Error::from)?;
    if owner.as_deref() != Some(me.account_id.as_str()) {
        return Err(ApiError(StatusCode::NOT_FOUND, "not_found", "no such device".into()));
    }
    auth::revoke_device(&conn, &id, now_ms())?;
    s.peers.lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
    Ok(StatusCode::NO_CONTENT)
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

/// The server's VAPID public key, for a browser's `pushManager.subscribe` (push.rs).
async fn push_vapid(State(s): State<AppState>) -> Result<Json<serde_json::Value>, ApiError> {
    Ok(Json(json!({"public_key": crate::push::vapid_public(&s.db())?})))
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

/// Admin endpoints take a signed-in device of an admin account (not API tokens).
fn require_admin(s: &AppState, conn: &Connection, headers: &axum::http::HeaderMap) -> Result<(), ApiError> {
    let who = auth::authenticate(conn, bearer(headers)?, now_ms(), s.session_ttl())?;
    let is_admin: bool = conn
        .query_row("SELECT is_admin FROM account WHERE id=?1", [&who.account_id], |r| r.get(0))
        .map_err(anyhow::Error::from)?;
    if !is_admin {
        return Err(ApiError(StatusCode::FORBIDDEN, "forbidden", "admin account required".into()));
    }
    Ok(())
}

async fn admin_health(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let mut health = {
        let conn = s.db();
        require_admin(&s, &conn, &headers)?;
        let connected = s.peers.lock().unwrap_or_else(|e| e.into_inner()).len();
        crate::health::snapshot(&conn, &s.cfg, connected)?
    };
    if let Some(url) = &s.cfg.push.ntfy_url {
        let reachable = reqwest::Client::new()
            .head(url)
            .timeout(std::time::Duration::from_secs(2))
            .send()
            .await
            .is_ok_and(|response| response.status().is_success());
        health["ntfy_reachable"] = json!(reachable);
    } else {
        health["ntfy_reachable"] = serde_json::Value::Null;
    }
    Ok(Json(health))
}

/// Close the restore window now (`chorus-server reconcile-close`; SYNC.md §7.3, D-067).
async fn admin_reconcile_close(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    require_admin(&s, &conn, &headers)?;
    crate::reconcile::close(&conn)?;
    tracing::info!("restore window closed from the admin view");
    Ok(StatusCode::NO_CONTENT)
}

async fn export_ops(State(s): State<AppState>, headers: axum::http::HeaderMap) -> Result<Response, ApiError> {
    let p = {
        let conn = s.db();
        let p = principal(&s, &conn, &headers)?;
        if !p.allows("export") {
            return Err(crate::api_data::DataError::Scope("export").into());
        }
        p
    };
    let path = s.cfg.db_path();
    let account = p.account_id;
    let bytes = tokio::task::spawn_blocking(move || {
        let reader = crate::exports::read_snapshot(&path)?;
        crate::exports::ops_jsonl(&reader, &account)
    })
    .await
    .map_err(anyhow::Error::from)??;
    Ok((
        [
            (axum::http::header::CONTENT_TYPE, "application/x-ndjson"),
            (axum::http::header::CONTENT_DISPOSITION, "attachment; filename=\"ops.jsonl\""),
        ],
        bytes,
    )
        .into_response())
}

async fn export_csv(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(name): Path<String>,
) -> Result<Response, ApiError> {
    let p = {
        let conn = s.db();
        let p = principal(&s, &conn, &headers)?;
        if !p.allows("export") {
            return Err(crate::api_data::DataError::Scope("export").into());
        }
        p
    };
    let path = s.cfg.db_path();
    let account = p.account_id;
    let csv_name = name.clone();
    let bytes = tokio::task::spawn_blocking(move || {
        let reader = crate::exports::read_snapshot(&path)?;
        crate::exports::csv(&reader, &account, &csv_name)
    })
    .await
    .map_err(anyhow::Error::from)??
    .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "not_found", "unknown CSV export".into()))?;
    let mut response = bytes.into_response();
    response
        .headers_mut()
        .insert(axum::http::header::CONTENT_TYPE, axum::http::HeaderValue::from_static("text/csv; charset=utf-8"));
    response.headers_mut().insert(
        axum::http::header::CONTENT_DISPOSITION,
        axum::http::HeaderValue::from_str(&format!("attachment; filename=\"{name}.csv\""))
            .map_err(anyhow::Error::from)?,
    );
    Ok(response)
}

async fn export_sqlite(State(s): State<AppState>, headers: axum::http::HeaderMap) -> Result<Response, ApiError> {
    let p = {
        let conn = s.db();
        let p = principal(&s, &conn, &headers)?;
        if !p.allows("export") {
            return Err(crate::api_data::DataError::Scope("export").into());
        }
        p
    };
    let path = s.cfg.db_path();
    let work_dir = s.cfg.server.data_dir.join("export-work");
    let account = p.account_id;
    let bytes = tokio::task::spawn_blocking(move || {
        let reader = crate::exports::read_snapshot(&path)?;
        crate::exports::sqlite_copy(&reader, &account, &work_dir)
    })
    .await
    .map_err(anyhow::Error::from)??;
    Ok((
        [
            (axum::http::header::CONTENT_TYPE, "application/vnd.sqlite3"),
            (axum::http::header::CONTENT_DISPOSITION, "attachment; filename=\"account.sqlite\""),
        ],
        bytes,
    )
        .into_response())
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
    subject: Option<String>,
    level: Option<String>,
    /// EventSource can't send headers, so the stream (only) also takes `?token=`.
    token: Option<String>,
    /// Stream: which events (`front`, `message`; comma-separated). Default: all you may read.
    events: Option<String>,
    /// Stream: only messages in these channels (ids, comma-separated).
    channels: Option<String>,
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
    Ok(Json(crate::api_data::intervals(&conn, &p, q.from, q.to, q.subject.as_deref(), q.level.as_deref())?))
}

async fn members_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_data::members(&conn, &p)?))
}

async fn search_messages(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<crate::search::MessageQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::search::messages(&conn, &p, &q)?))
}

async fn search_posts(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<crate::posts::PostSearch>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::posts::search(&conn, &p, &q)?))
}

async fn search_message(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    let message = crate::search::message_by_id(&conn, &p, &id)?
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "not_found", "message unavailable".into()))?;
    Ok(Json(message))
}

fn not_found(what: &str) -> ApiError {
    ApiError(StatusCode::NOT_FOUND, "not_found", format!("{what} unavailable"))
}

async fn space_channels(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::messages::channels(&conn, &p, &id)?.ok_or_else(|| not_found("space"))?))
}

async fn channel_messages(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
    axum::extract::Query(q): axum::extract::Query<crate::messages::Page>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::messages::list(&conn, &p, &id, &q)?.ok_or_else(|| not_found("channel"))?))
}

async fn message_thread(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
    axum::extract::Query(q): axum::extract::Query<crate::messages::Page>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::messages::thread(&conn, &p, &id, &q)?.ok_or_else(|| not_found("message"))?))
}

async fn channel_send(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
    Json(b): Json<crate::messages::MessageIn>,
) -> Result<Response, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    let o = crate::messages::send(&conn, &p, &id, &b, now_ms())?.ok_or_else(|| not_found("channel"))?;
    fan_out(&s, &conn, std::slice::from_ref(&o), None)?;
    let message = crate::messages::one(&conn, &p, o.entity().unwrap_or_default())?;
    Ok((StatusCode::CREATED, Json(json!({"message_id": o.entity_id, "op_id": o.id, "message": message})))
        .into_response())
}

/// Posts are account-scoped data with an explicit audience. Only signed-in devices can use the
/// cross-account view; API tokens remain limited to their own-account data APIs.
async fn posts_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<crate::posts::PostQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    Ok(Json(crate::posts::list(&conn, &me.account_id, &q)?))
}

#[derive(Deserialize)]
struct PostDetailQuery {
    depth: Option<usize>,
}

async fn post_one(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
    axum::extract::Query(q): axum::extract::Query<PostDetailQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    let mut item = crate::posts::one(&conn, &me.account_id, &id)?
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "not_found", "post unavailable".into()))?;
    item["replies"] = json!(crate::posts::replies(&conn, &me.account_id, &id, q.depth.unwrap_or(1).min(3))?);
    Ok(Json(item))
}

/// Log a switch from a script, NFC tag or Tasker (`write:front`, api_writes.rs).
async fn front_switch(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(b): Json<crate::api_writes::SwitchIn>,
) -> Result<Response, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    let o = crate::api_writes::switch(&conn, &p, &b, now_ms())?;
    fan_out(&s, &conn, std::slice::from_ref(&o), None)?;
    let mut v = json!({"switch_id": o.entity_id, "op_id": o.id, "occurred_at": o.occurred_at});
    if p.allows("read:front") {
        v["front"] = crate::api_data::current_front(&conn, &p)?["front"].take();
    }
    Ok((StatusCode::CREATED, Json(v)).into_response())
}

// ─── shared spaces and DMs (spaces.rs) ──────────────────────────────────────

impl From<crate::spaces::SpaceError> for ApiError {
    fn from(e: crate::spaces::SpaceError) -> Self {
        use crate::spaces::SpaceError as E;
        match e {
            E::Bad(m) => ApiError(StatusCode::BAD_REQUEST, "bad_request", m),
            E::NotFound => ApiError(StatusCode::NOT_FOUND, "not_found", "no such space".into()),
            E::Forbidden(m) => ApiError(StatusCode::FORBIDDEN, "forbidden", m),
            E::Internal(e) => e.into(),
        }
    }
}

#[derive(Deserialize)]
struct SpaceIn {
    kind: String,
    name: Option<String>,
    #[serde(default)]
    accounts: Vec<String>,
}

#[derive(Deserialize)]
struct AccountsIn {
    accounts: Vec<String>,
}

async fn spaces_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    Ok(Json(crate::spaces::list(&conn, &me.account_id)?))
}

async fn spaces_create(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(b): Json<SpaceIn>,
) -> Result<Response, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    let (id, ops) = crate::spaces::create(&conn, &me.account_id, &b.kind, b.name.as_deref(), &b.accounts, now_ms())?;
    let created = !ops.is_empty();
    fan_out(&s, &conn, &ops, None)?;
    let status = if created { StatusCode::CREATED } else { StatusCode::OK };
    Ok((status, Json(json!({"id": id}))).into_response())
}

async fn spaces_add(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
    Json(b): Json<AccountsIn>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    let ops = crate::spaces::add(&conn, &me.account_id, &id, &b.accounts, now_ms())?;
    fan_out(&s, &conn, &ops, None)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn spaces_leave(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    let ops = crate::spaces::leave(&conn, &me.account_id, &id, now_ms())?;
    fan_out(&s, &conn, &ops, None)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn spaces_authors(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let me = who(&s, &conn, &headers)?;
    Ok(Json(crate::spaces::authors(&conn, &me.account_id, &id)?))
}

// ─── more reads (api_reads.rs) ───────────────────────────────────────────────

async fn me(State(s): State<AppState>, headers: axum::http::HeaderMap) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_reads::me(&conn, &p)?))
}

async fn member_one(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    crate::api_reads::member(&conn, &p, &id)?
        .map(Json)
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "not_found", "no such member".into()))
}

async fn groups_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_reads::groups(&conn, &p)?))
}

async fn fields_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_reads::fields(&conn, &p)?))
}

async fn states_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_reads::states(&conn, &p)?))
}

#[derive(Deserialize, Default)]
struct DayRange {
    from: Option<String>,
    to: Option<String>,
    level: Option<String>,
    open: Option<u8>,
}

async fn front_daily(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<DayRange>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_reads::daily(&conn, &p, q.from.as_deref(), q.to.as_deref(), q.level.as_deref())?))
}

async fn front_reviews(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    axum::extract::Query(q): axum::extract::Query<DayRange>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::api_reads::reviews(&conn, &p, q.open.unwrap_or(0) != 0)?))
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
        if !p.allows("stream") {
            return Err(crate::api_data::DataError::Scope("stream").into());
        }
        // each event type needs its read scope; asking for one you can't read is a 403
        let may = |e: &str| match e {
            "front" => p.allows("read:front"),
            "message" => p.allows("read:messages"),
            _ => false,
        };
        let wanted: Vec<String> = match q.events.as_deref() {
            Some(list) => list.split(',').map(|e| e.trim().to_string()).filter(|e| !e.is_empty()).collect(),
            None => ["front", "message"].iter().filter(|e| may(e)).map(|e| e.to_string()).collect(),
        };
        if wanted.is_empty() {
            return Err(crate::api_data::DataError::Scope("read:front").into());
        }
        if let Some(e) = wanted.iter().find(|e| !may(e)) {
            return Err(match e.as_str() {
                "front" => crate::api_data::DataError::Scope("read:front"),
                "message" => crate::api_data::DataError::Scope("read:messages"),
                other => crate::api_data::DataError::Bad(format!("unknown event {other:?}; use front or message")),
            }
            .into());
        }
        let first = if wanted.iter().any(|e| e == "front") {
            let v = crate::api_data::current_front(&conn, &p)?;
            Some(json!({"type": "front", "at": now_ms(), "front": v["front"], "since": v["since"]}))
        } else {
            None
        };
        (p.account_id.clone(), (first, wanted))
    };
    let (first, wanted) = first;
    let channels: Option<Vec<String>> =
        q.channels.as_deref().map(|c| c.split(',').map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect());
    let rx = s.events.subscribe();
    let first = futures_util::stream::iter(
        first.map(|v| Ok::<_, std::convert::Infallible>(Event::default().event("front").data(v.to_string()))),
    );
    let rest = futures_util::stream::unfold(
        (rx, account, wanted, channels),
        |(mut rx, account, wanted, channels)| async move {
            loop {
                match rx.recv().await {
                    Ok((a, v)) if a == account => {
                        let kind = v["type"].as_str().unwrap_or("front").to_string();
                        let in_channel = kind != "message"
                            || channels.as_ref().is_none_or(|c| c.iter().any(|x| v["channel_id"] == x.as_str()));
                        if wanted.contains(&kind) && in_channel {
                            let event = Event::default().event(kind).data(v.to_string());
                            return Some((Ok(event), (rx, account, wanted, channels)));
                        }
                    }
                    Ok(_) | Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => return None,
                }
            }
        },
    );
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

// ─── webhooks (webhooks.rs) ──────────────────────────────────────────────────

#[derive(Deserialize)]
struct WebhookIn {
    url: String,
    events: Vec<String>,
}

#[derive(Deserialize)]
struct WebhookPatch {
    enabled: Option<bool>,
    events: Option<Vec<String>>,
}

async fn webhooks_list(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<serde_json::Value>, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    Ok(Json(crate::webhooks::list(&conn, &p)?))
}

async fn webhooks_create(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(b): Json<WebhookIn>,
) -> Result<Response, ApiError> {
    // resolve the host before taking the db lock
    let url = crate::webhooks::check_url(b.url.trim(), s.cfg.security.webhook_targets())
        .await
        .map_err(|e| ApiError(StatusCode::BAD_REQUEST, "bad_request", e))?
        .url;
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    let v = crate::webhooks::create(&conn, &p, url.as_str(), &b.events, now_ms())?;
    Ok((StatusCode::CREATED, Json(v)).into_response())
}

async fn webhooks_update(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
    Json(b): Json<WebhookPatch>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    crate::webhooks::update(&conn, &p, &id, b.enabled, b.events.as_deref())?;
    Ok(StatusCode::NO_CONTENT)
}

async fn webhooks_remove(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let conn = s.db();
    let p = principal(&s, &conn, &headers)?;
    crate::webhooks::remove(&conn, &p, &id)?;
    Ok(StatusCode::NO_CONTENT)
}

/// Send a `ping` now and say how it went. A failed test is shown but never retried and never
/// counts towards turning the webhook off.
async fn webhooks_test(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let d = {
        let conn = s.db();
        let p = principal(&s, &conn, &headers)?;
        crate::webhooks::test(&conn, &p, &id, now_ms())?
    };
    let outcome = crate::webhooks::send(&d, s.cfg.security.webhook_targets(), now_ms()).await;
    let (status, error) = match &outcome {
        Ok(st) => (Some(*st), None),
        Err((st, e)) => (*st, Some(e.clone())),
    };
    s.db()
        .execute(
            "UPDATE webhook SET last_status = ?2, last_error = ?3 WHERE id = ?1",
            rusqlite::params![d.webhook_id, status, error],
        )
        .map_err(anyhow::Error::from)?;
    Ok(Json(json!({"ok": error.is_none(), "status": status, "error": error})))
}

/// Deliver webhooks: new deliveries arrive on `rx`; due ones go out once a second, each on its own
/// task so a slow receiver can't hold up others. Retries come back through the same channel.
fn run_webhooks(state: AppState, mut rx: mpsc::UnboundedReceiver<crate::webhooks::Delivery>) {
    tokio::spawn(async move {
        let mut pending: Vec<crate::webhooks::Delivery> = Vec::new();
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(1));
        loop {
            tokio::select! {
                d = rx.recv() => match d {
                    Some(d) => pending.push(d),
                    None => break,
                },
                _ = tick.tick() => {}
            }
            let now = now_ms();
            let (due, later): (Vec<_>, Vec<_>) = pending.drain(..).partition(|d| d.due <= now);
            pending = later;
            for d in due {
                let state = state.clone();
                tokio::spawn(async move {
                    if !crate::webhooks::still_enabled(&state.db(), &d.webhook_id) {
                        return;
                    }
                    let outcome = crate::webhooks::send(&d, state.cfg.security.webhook_targets(), now_ms()).await;
                    match crate::webhooks::record(&state.db(), &d, &outcome, now_ms()) {
                        Ok(Some(retry)) => {
                            let _ = state.hooks.send(retry);
                        }
                        Ok(None) => {}
                        Err(e) => tracing::error!(error = %e, "webhooks: can't record outcome"),
                    }
                });
            }
        }
    });
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

/// How long a new sync socket may stay open without a Hello.
const HELLO_WITHIN: std::time::Duration = std::time::Duration::from_secs(15);

async fn sync_ws(
    State(s): State<AppState>,
    headers: axum::http::HeaderMap,
    extensions: axum::http::Extensions,
    ws: WebSocketUpgrade,
) -> Response {
    let peer = extensions.get::<axum::extract::ConnectInfo<std::net::SocketAddr>>().map(|c| c.0);
    let addr = crate::ratelimit::client_addr(&headers, peer);
    let Some(slot) = Unsigned::take(&s, addr) else {
        let message = "too many sync connections from this address that haven't signed in".to_string();
        return ApiError(StatusCode::TOO_MANY_REQUESTS, "too_many_connections", message).into_response();
    };
    ws.max_message_size(8 << 20).on_upgrade(move |socket| run_socket(s, socket, slot))
}

/// A sync socket that hasn't signed in yet, counted against its address until it does (or goes).
struct Unsigned {
    s: AppState,
    addr: Option<String>,
}

impl Unsigned {
    /// `None` when the address already has as many unsigned sockets as it may.
    fn take(s: &AppState, addr: String) -> Option<Unsigned> {
        let limit = s.cfg.security.sync_limits().per_address;
        // no address to tell clients apart (an embedded router): nothing to count
        if limit == 0 || addr == "unknown" {
            return Some(Unsigned { s: s.clone(), addr: None });
        }
        let mut sockets = s.sockets();
        let n = sockets.unsigned.entry(addr.clone()).or_default();
        if *n >= limit {
            return None;
        }
        *n += 1;
        Some(Unsigned { s: s.clone(), addr: Some(addr) })
    }
}

impl Drop for Unsigned {
    fn drop(&mut self) {
        let Some(addr) = self.addr.take() else { return };
        let mut sockets = self.s.sockets();
        if let Some(n) = sockets.unsigned.get_mut(&addr) {
            *n -= 1;
            if *n == 0 {
                sockets.unsigned.remove(&addr);
            }
        }
    }
}

/// A signed-in sync socket of an account; dropping it takes the socket off the account's list.
struct Signed {
    s: AppState,
    account: String,
    id: u64,
}

impl Signed {
    /// Count a socket that just signed in; past the account's limit, the oldest are told to close.
    fn add(s: &AppState, account: &str, close: Arc<tokio::sync::Notify>) -> Signed {
        let limit = s.cfg.security.sync_limits().per_account;
        let mut sockets = s.sockets();
        sockets.next_id += 1;
        let id = sockets.next_id;
        let list = sockets.by_account.entry(account.to_string()).or_default();
        list.push((id, close));
        if limit > 0 && list.len() > limit {
            for (_, oldest) in list.drain(..list.len() - limit) {
                oldest.notify_one();
            }
        }
        Signed { s: s.clone(), account: account.to_string(), id }
    }
}

impl Drop for Signed {
    fn drop(&mut self) {
        let mut sockets = self.s.sockets();
        if let Some(list) = sockets.by_account.get_mut(&self.account) {
            list.retain(|(id, _)| *id != self.id);
            if list.is_empty() {
                sockets.by_account.remove(&self.account);
            }
        }
    }
}

fn send(tx: &mpsc::UnboundedSender<Frame>, f: Frame) {
    let _ = tx.send(f);
}

/// Catch-up for one scope, queued on `tx` (caller holds the db lock, so nothing can interleave).
fn catch_up(
    conn: &Connection,
    tx: &mpsc::UnboundedSender<Frame>,
    account: &str,
    scope: &str,
    after: i64,
) -> anyhow::Result<()> {
    let mut cursor = after;
    loop {
        let ops = oplog::scope_after(conn, scope, cursor, PAGE_OPS)?;
        let Some(last) = ops.last().and_then(|o| o.seq) else { break };
        cursor = last;
        let n = ops.len();
        let visible = ops
            .into_iter()
            .filter_map(|o| match crate::visibility::op_visible_to(conn, account, &o) {
                Ok(true) => Some(Ok(o)),
                Ok(false) => None,
                Err(e) => Some(Err(e)),
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        if !visible.is_empty() {
            send(tx, Frame::Ops { scope: scope.into(), ops: visible, to: last });
        }
        if n < PAGE_OPS {
            break;
        }
    }
    let to = oplog::max_seq(conn, scope)?;
    send(
        tx,
        Frame::Caught { scope: scope.into(), to, digest: crate::visibility::visible_digest(conn, account, scope)? },
    );
    Ok(())
}

async fn run_socket(s: AppState, socket: WebSocket, unsigned: Unsigned) {
    use futures_util::{SinkExt, StreamExt};
    let limits = s.cfg.security.sync_limits();
    // every frame the device sends takes one from its budget (OPS.md §9)
    let mut budget = crate::ratelimit::Bucket::full(limits.frame_burst, std::time::Instant::now());
    let close = Arc::new(tokio::sync::Notify::new());
    let mut unsigned = Some(unsigned);
    let mut signed: Option<Signed> = None;
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Frame>();
    let writer = tokio::spawn(async move {
        while let Some(f) = rx.recv().await {
            let Ok(text) = serde_json::to_string(&f) else { continue };
            if sink.send(Message::Text(text.into())).await.is_err() {
                break;
            }
        }
        sink
    });

    let mut me: Option<(String, ingest::Session)> = None; // (device, session)
    loop {
        // on a public server anyone can open a socket: one that hasn't signed in with a Hello
        // within HELLO_WITHIN is closed rather than held open
        let next = if me.is_none() {
            match tokio::time::timeout(HELLO_WITHIN, stream.next()).await {
                Ok(n) => n,
                Err(_) => break,
            }
        } else {
            tokio::select! {
                n = stream.next() => n,
                // a newer socket of this account went over its limit: this one is the oldest
                () = close.notified() => {
                    let message = "too many open connections for this account; closing the oldest".into();
                    send(&tx, Frame::Error { code: "too_many_connections".into(), message });
                    break;
                }
            }
        };
        let Some(Ok(msg)) = next else { break };
        if matches!(msg, Message::Close(_)) {
            break;
        }
        if limits.frames_per_second > 0.0
            && budget.take(limits.frame_burst, limits.frames_per_second, std::time::Instant::now()).is_err()
        {
            send(&tx, Frame::Error { code: "rate_limited".into(), message: "too many frames; slow down".into() });
            break;
        }
        let Message::Text(text) = msg else { continue };
        let text = text.to_string();
        let frame: Frame = match serde_json::from_str(&text) {
            Ok(f) => f,
            Err(e) => {
                send(&tx, Frame::Error { code: "bad_request".into(), message: format!("bad frame: {e}") });
                continue;
            }
        };
        // a device signed out from another one stops here, even mid-connection
        if let Some((device, _)) = &me
            && !auth::device_active(&s.db(), device)
        {
            send(&tx, Frame::Error { code: "unauthenticated".into(), message: "this device was signed out".into() });
            break;
        }
        let result = match (&me, frame) {
            (None, Frame::Hello { token, epoch, cursors, clock, outbox, .. }) => {
                match hello(&s, &tx, &token, epoch, cursors, clock, outbox) {
                    Ok(session) => {
                        unsigned = None;
                        signed = Some(Signed::add(&s, &session.account_id, close.clone()));
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
                    catch_up(&conn, &tx, &sess.account_id, &scope, after)
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
        // the device may have connected again meanwhile: leave its newer socket registered
        let mut peers = s.peers.lock().unwrap_or_else(|e| e.into_inner());
        if peers.get(&device).is_some_and(|p| p.tx.same_channel(&tx)) {
            peers.remove(&device);
        }
    }
    drop((unsigned, signed));
    drop(tx);
    if let Ok(mut sink) = writer.await {
        // Close politely and read until the client closes too: dropping a socket with unread
        // data resets it (Windows), and the client loses the error frame that says why.
        let _ = sink.send(Message::Close(None)).await;
        let drain = async { while let Some(Ok(_)) = stream.next().await {} };
        let _ = tokio::time::timeout(std::time::Duration::from_secs(2), drain).await;
    }
}

fn hello(
    s: &AppState,
    tx: &mpsc::UnboundedSender<Frame>,
    token: &str,
    epoch: Option<String>,
    cursors: BTreeMap<String, i64>,
    clock: chorus_core::sync::ClockReading,
    outbox: usize,
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
        // after a restore: this device is back once it has nothing left to hand over
        if let Err(e) = crate::reconcile::hello(&conn, &who.device_id, outbox, now) {
            tracing::warn!(error = %e, "can't note a reconciled device");
        }
        for sc in &scopes {
            catch_up(&conn, tx, &who.account_id, sc, *cursors.get(sc).unwrap_or(&0))?;
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
    // and each of its own new messages (API.md §6), as its author sees them
    if s.events.receiver_count() > 0 {
        for o in fresh.iter().filter(|o| matches!(o.kind.as_str(), "message.send" | "message.forward")) {
            let (Some(author), Some(id)) = (o.account_id.as_deref(), o.entity()) else { continue };
            let owner = crate::api_data::Principal::owner(author);
            if let Ok(Some(mut m)) = crate::messages::one(conn, &owner, id) {
                m["type"] = json!("message");
                m["at"] = m["occurred_at"].clone();
                m["channel"] = conn
                    .query_row(
                        "SELECT name FROM channel WHERE id = ?1",
                        [m["channel_id"].as_str().unwrap_or("")],
                        |r| r.get::<_, Option<String>>(0),
                    )
                    .ok()
                    .flatten()
                    .map_or(serde_json::Value::Null, |n| json!(n));
                let _ = s.events.send((author.to_string(), m));
            }
        }
    }
    match crate::webhooks::deliveries_for(conn, fresh, now_ms()) {
        Ok(ds) => ds.into_iter().for_each(|d| {
            let _ = s.hooks.send(d);
        }),
        Err(e) => tracing::error!(error = %e, "webhooks: can't build deliveries"),
    }
    let mut deliver: BTreeMap<i64, Op> = fresh.iter().filter_map(|o| o.seq.map(|seq| (seq, o.clone()))).collect();
    for o in fresh {
        for earlier in crate::visibility::backfill_for_public_send(conn, o)? {
            if let Some(seq) = earlier.seq {
                deliver.entry(seq).or_insert(earlier);
            }
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
        for o in deliver.values().filter(|o| p.scopes.contains(&o.scope)) {
            if crate::visibility::op_visible_to(conn, &p.account, o)? {
                by_scope.entry(o.scope.as_str()).or_default().push(o.clone());
            }
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

/// Ctrl-C, or SIGTERM on Unix (what systemd sends to stop the service).
async fn stop_signal() {
    #[cfg(unix)]
    {
        let mut term = match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!(error = %e, "can't listen for SIGTERM; only Ctrl-C stops cleanly");
                let _ = tokio::signal::ctrl_c().await;
                return;
            }
        };
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = term.recv() => {}
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

/// Bind and serve until Ctrl-C (or SIGTERM).
pub async fn serve(state: AppState) -> anyhow::Result<()> {
    let addr = state.cfg.server.listen.clone();
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!(%addr, "chorus-server listening");
    watch_own_binary();
    run_notifier(state.clone());
    crate::backup::start_nightly(state.cfg.clone())?;
    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
    let app = router(state).into_make_service_with_connect_info::<std::net::SocketAddr>();
    let server = axum::serve(listener, app).with_graceful_shutdown(async {
        stop_signal().await;
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
