//! Chorus Home's setup and settings (D-071, docs/HOME.md §2): what the web app's setup page and
//! *This computer* settings call, instead of anyone editing `chorus.toml` by hand. Only on a
//! Chorus Home install (`[server] home = true`), and only for requests from the PC itself.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};

use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::app::{ApiError, AppState};

/// Set when the settings changed: `serve` stops, and `main` starts again with the new config.
static RESTART: tokio::sync::Notify = tokio::sync::Notify::const_new();
static RESTARTING: AtomicBool = AtomicBool::new(false);

/// Asked to stop (the Windows service manager, home_install.rs): `serve` ends and doesn't restart.
static STOP: tokio::sync::Notify = tokio::sync::Notify::const_new();
static STOPPING: AtomicBool = AtomicBool::new(false);

pub fn request_stop() {
    STOPPING.store(true, Ordering::SeqCst);
    STOP.notify_one();
}

/// Resolves once a stop was asked for (at once if it already was).
pub async fn stop_requested() {
    let notified = STOP.notified();
    if STOPPING.load(Ordering::SeqCst) {
        return;
    }
    notified.await;
}

/// Resolves when the settings page asked for a restart.
pub async fn restart_requested() {
    RESTART.notified().await;
}

/// After `serve` returns: was that a restart (start again) rather than a stop?
pub fn take_restart() -> bool {
    RESTARTING.swap(false, Ordering::SeqCst)
}

fn request_restart() {
    RESTARTING.store(true, Ordering::SeqCst);
    RESTART.notify_one();
}

pub fn routes() -> Router<AppState> {
    Router::new().route("/home", get(status)).route("/home/setup", post(setup)).route("/home/settings", put(settings))
}

/// From this PC itself: a loopback peer, and nothing forwarded (a Home install has no proxy).
fn local_only(peer: &SocketAddr, headers: &HeaderMap) -> Result<(), ApiError> {
    if peer.ip().is_loopback() && !headers.contains_key("x-forwarded-for") && !headers.contains_key("forwarded") {
        return Ok(());
    }
    Err(ApiError(
        StatusCode::FORBIDDEN,
        "not_this_computer",
        "these settings can only be changed on the computer Chorus runs on".into(),
    ))
}

fn port_of(listen: &str) -> Option<u16> {
    listen.rsplit(':').next()?.parse().ok()
}

/// `GET /home`: what the setup page and *This computer* settings show.
async fn status(
    State(s): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    local_only(&peer, &headers)?;
    let cfg = &s.cfg;
    let needs_setup = {
        let conn = s.db();
        conn.query_row("SELECT count(*) = 0 FROM account", [], |r| r.get::<_, bool>(0)).map_err(anyhow::Error::from)?
    };
    let lan = cfg.server.lan_listen.as_deref();
    Ok(Json(json!({
        "needs_setup": needs_setup,
        "version": env!("CARGO_PKG_VERSION"),
        "port": port_of(&cfg.server.listen),
        "lan": lan.is_some(),
        "lan_port": lan.and_then(port_of),
        "lan_address": lan.and_then(crate::tls::lan_base),
        "pin": lan.and_then(|_| crate::tls::load_or_create(&cfg.server.data_dir).ok()).map(|id| id.pin),
        "data_dir": cfg.server.data_dir,
        "backup": {"dir": cfg.backup_dir(), "keep_daily": cfg.backup.keep_daily},
    })))
}

/// `POST /home/setup`: the very first account. Only while there is none; it returns a one-use
/// system invite that this browser then redeems like any other (its own device key, API.md §2).
async fn setup(
    State(s): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    local_only(&peer, &headers)?;
    let conn = s.db();
    let empty: bool =
        conn.query_row("SELECT count(*) = 0 FROM account", [], |r| r.get(0)).map_err(anyhow::Error::from)?;
    if !empty {
        return Err(ApiError(
            StatusCode::CONFLICT,
            "already_set_up",
            "Chorus is already set up on this computer".into(),
        ));
    }
    let code = crate::auth::create_invite(
        &conn,
        crate::auth::InviteKind::System,
        None,
        "home-setup",
        3_600_000,
        1,
        crate::now_ms(),
    )?;
    Ok(Json(json!({"code": code})))
}

#[derive(Deserialize)]
struct SettingsIn {
    port: Option<u16>,
    /// Let phones on the home wifi connect (the TLS listener on `port + 1`).
    lan: Option<bool>,
    keep_daily: Option<u32>,
}

/// `PUT /home/settings`: an admin on this PC changes the port, home-wifi access or backups. The
/// file is rewritten and the server starts again with it (`202`, then the new address).
async fn settings(
    State(s): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(b): Json<SettingsIn>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    local_only(&peer, &headers)?;
    {
        let conn = s.db();
        let token = headers
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .ok_or_else(|| ApiError(StatusCode::UNAUTHORIZED, "unauthorized", "sign in first".into()))?;
        let who =
            crate::auth::authenticate(&conn, token, crate::now_ms(), s.cfg.limits.session_days as i64 * 86_400_000)?;
        let admin: bool = conn
            .query_row("SELECT is_admin FROM account WHERE id = ?1", [&who.account_id], |r| r.get(0))
            .map_err(anyhow::Error::from)?;
        if !admin {
            return Err(ApiError(StatusCode::FORBIDDEN, "forbidden", "admin account required".into()));
        }
    }
    let bad = |m: &str| ApiError(StatusCode::BAD_REQUEST, "bad_request", m.into());
    if b.port.is_some_and(|p| p < 1024 || p == u16::MAX) {
        return Err(bad("choose a port from 1024 to 65534"));
    }
    if b.keep_daily.is_some_and(|k| k == 0 || k > 365) {
        return Err(bad("keep between 1 and 365 daily backups"));
    }
    let Some(path) = s.cfg.source.clone() else {
        return Err(ApiError(
            StatusCode::CONFLICT,
            "no_config_file",
            "this server wasn't started from a chorus.toml".into(),
        ));
    };
    let port = b.port.or_else(|| port_of(&s.cfg.server.listen)).unwrap_or(5250);
    let lan = b.lan.unwrap_or(s.cfg.server.lan_listen.is_some());
    let text = std::fs::read_to_string(&path).unwrap_or_default();
    let mut doc: toml::Table = text.parse().map_err(|e| bad(&format!("{}: {e}", path.display())))?;
    let server = doc.entry("server").or_insert_with(|| toml::Value::Table(Default::default()));
    let server = server.as_table_mut().ok_or_else(|| bad("[server] isn't a table"))?;
    server.insert("listen".into(), format!("127.0.0.1:{port}").into());
    server.insert("public_url".into(), format!("http://localhost:{port}").into());
    if lan {
        server.insert("lan_listen".into(), format!("0.0.0.0:{}", port + 1).into());
    } else {
        server.remove("lan_listen");
    }
    if let Some(k) = b.keep_daily {
        let backup = doc.entry("backup").or_insert_with(|| toml::Value::Table(Default::default()));
        if let Some(t) = backup.as_table_mut() {
            t.insert("keep_daily".into(), i64::from(k).into());
        }
    }
    let out = toml::to_string_pretty(&doc).map_err(anyhow::Error::from)?;
    // the new file must load, or the restart would leave Chorus down
    toml::from_str::<crate::config::Config>(&out).map_err(|e| bad(&e.to_string()))?;
    let tmp = path.with_extension("toml.tmp");
    std::fs::write(&tmp, &out).map_err(anyhow::Error::from)?;
    std::fs::rename(&tmp, &path).map_err(anyhow::Error::from)?;
    tracing::info!(port, lan, "settings changed on this computer; starting again");
    // answer first, then restart
    tokio::spawn(async {
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
        request_restart();
    });
    Ok((StatusCode::ACCEPTED, Json(json!({"url": format!("http://localhost:{port}/")}))))
}
