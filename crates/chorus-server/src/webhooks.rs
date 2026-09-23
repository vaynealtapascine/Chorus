//! Webhooks (API.md §7, M10.2): POST a signed JSON event to the owner's own URL when something
//! happens in their account.
//!
//! - Configured from a signed-in device (never an API token); the signing secret is shown once.
//! - Events: `front.switch`, `member.created`, `member.updated`, `follow.requested`, plus `ping`
//!   from the "send a test" button. Message and post events wait for M5.7 visibility and M7.
//! - `Chorus-Signature: t=<unix s>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`.
//! - Retries after 1 m, 5 m, 30 m, 2 h, 12 h; after that the webhook is disabled and the reason is
//!   shown in the app. Pending retries live in memory, so a restart drops them (the next event
//!   still goes out).
//! - URLs must stay inside the tailnet/LAN unless `security.webhooks_allow_external` is on; the
//!   check runs when the webhook is saved and again before every delivery (names are resolved).

use std::net::IpAddr;

use chorus_core::op::Op;
use hkdf::hmac::{Hmac, Mac};
use rusqlite::{Connection, params};
use serde_json::{Value, json};
use sha2::Sha256;

use crate::api_data::{self, DataError, Principal};

pub const EVENTS: &[&str] = &["front.switch", "member.created", "member.updated", "follow.requested"];

/// Delay before retry n (after the first attempt fails).
pub const RETRY_MS: [i64; 5] = [60_000, 300_000, 1_800_000, 7_200_000, 43_200_000];

/// One POST to make (and possibly retry).
#[derive(Clone, Debug)]
pub struct Delivery {
    pub id: String,
    pub webhook_id: String,
    pub url: String,
    pub secret: String,
    pub event: String,
    pub body: String,
    /// 0 for the first attempt.
    pub attempt: usize,
    pub due: i64,
}

// ─── configuration ───────────────────────────────────────────────────────────

fn internal_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v) => {
            let [a, b, ..] = v.octets();
            v.is_loopback() || v.is_private() || v.is_link_local() || (a == 100 && (64..128).contains(&b))
        }
        IpAddr::V6(v) => {
            if let Some(v4) = v.to_ipv4_mapped() {
                return internal_ip(IpAddr::V4(v4));
            }
            let first = v.segments()[0];
            v.is_loopback() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
    }
}

const INTERNAL_SUFFIXES: &[&str] = &[".ts.net", ".local", ".lan", ".internal", ".home.arpa"];

/// Refuse URLs that leave the tailnet/LAN unless external webhooks are allowed. Host names are
/// resolved and every address must be internal (Caddy sites on the tailnet resolve to 100.x).
pub async fn check_url(url: &str, allow_external: bool) -> Result<reqwest::Url, String> {
    let u = reqwest::Url::parse(url).map_err(|e| format!("not a URL: {e}"))?;
    if !matches!(u.scheme(), "http" | "https") {
        return Err("webhook URLs must be http or https".into());
    }
    if allow_external {
        return Ok(u);
    }
    let outside = || "that address is outside the tailnet (an admin can allow external webhooks)".to_string();
    let host = u.host_str().unwrap_or_default().trim_start_matches('[').trim_end_matches(']').to_ascii_lowercase();
    if let Ok(ip) = host.parse::<IpAddr>() {
        return if internal_ip(ip) { Ok(u) } else { Err(outside()) };
    }
    if host.is_empty() {
        return Err(outside());
    }
    if host == "localhost" || !host.contains('.') || INTERNAL_SUFFIXES.iter().any(|s| host.ends_with(s)) {
        return Ok(u);
    }
    let port = u.port_or_known_default().unwrap_or(443);
    let addrs: Vec<_> = tokio::net::lookup_host((host.as_str(), port))
        .await
        .map_err(|e| format!("can't resolve {host}: {e}"))?
        .collect();
    if !addrs.is_empty() && addrs.iter().all(|a| internal_ip(a.ip())) { Ok(u) } else { Err(outside()) }
}

fn check_events(events: &[String]) -> Result<(), DataError> {
    if events.is_empty() || events.iter().any(|e| !EVENTS.contains(&e.as_str())) {
        return Err(DataError::Bad(format!("events must be some of {EVENTS:?}")));
    }
    Ok(())
}

fn owner_only(p: &Principal) -> Result<(), DataError> {
    if p.is_device() { Ok(()) } else { Err(DataError::Bad("webhooks are managed from a signed-in device".into())) }
}

/// Save a webhook (the URL was already checked with [`check_url`]). The secret is returned once.
pub fn create(conn: &Connection, p: &Principal, url: &str, events: &[String], now: i64) -> Result<Value, DataError> {
    owner_only(p)?;
    check_events(events)?;
    let id = chorus_core::id::new_id(now as u64, rand::random());
    let bytes: [u8; 24] = rand::random();
    let secret = format!("whsec_{}", hex(&bytes));
    conn.execute(
        "INSERT INTO webhook (id, account_id, url, secret, events, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![id, p.account_id, url, secret, serde_json::to_string(events).map_err(anyhow::Error::from)?, now],
    )?;
    Ok(json!({"id": id, "url": url, "events": events, "secret": secret, "is_enabled": true}))
}

pub fn list(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    owner_only(p)?;
    let mut st = conn.prepare(
        "SELECT id, url, events, is_enabled, last_status, last_error, created_at FROM webhook
         WHERE account_id = ?1 ORDER BY created_at",
    )?;
    let rows = st.query_map([&p.account_id], |r| {
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "url": r.get::<_, String>(1)?,
            "events": serde_json::from_str::<Value>(&r.get::<_, String>(2)?).unwrap_or(json!([])),
            "is_enabled": r.get::<_, bool>(3)?,
            "last_status": r.get::<_, Option<i64>>(4)?,
            "last_error": r.get::<_, Option<String>>(5)?,
            "created_at": r.get::<_, i64>(6)?,
        }))
    })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

/// Turn a webhook on or off (turning it on clears the last error), or change its events.
pub fn update(
    conn: &Connection,
    p: &Principal,
    id: &str,
    enabled: Option<bool>,
    events: Option<&[String]>,
) -> Result<(), DataError> {
    owner_only(p)?;
    if let Some(e) = events {
        check_events(e)?;
        conn.execute(
            "UPDATE webhook SET events = ?3 WHERE id = ?1 AND account_id = ?2",
            params![id, p.account_id, serde_json::to_string(e).map_err(anyhow::Error::from)?],
        )?;
    }
    if let Some(on) = enabled {
        conn.execute(
            "UPDATE webhook SET is_enabled = ?3, last_error = CASE WHEN ?3 THEN NULL ELSE last_error END
             WHERE id = ?1 AND account_id = ?2",
            params![id, p.account_id, on],
        )?;
    }
    Ok(())
}

/// Remove a webhook (configuration, not user data).
pub fn remove(conn: &Connection, p: &Principal, id: &str) -> Result<(), DataError> {
    owner_only(p)?;
    conn.execute("DELETE FROM webhook WHERE id = ?1 AND account_id = ?2", params![id, p.account_id])?;
    Ok(())
}

/// A `ping` delivery for the "send a test" button.
pub fn test(conn: &Connection, p: &Principal, id: &str, now: i64) -> Result<Delivery, DataError> {
    owner_only(p)?;
    let (url, secret): (String, String) = conn
        .query_row(
            "SELECT url, secret FROM webhook WHERE id = ?1 AND account_id = ?2",
            params![id, p.account_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(|_| DataError::Bad("no such webhook".into()))?;
    Ok(delivery(id, url, secret, "ping", &p.account_id, json!({"hello": "from Chorus"}), now))
}

// ─── events ──────────────────────────────────────────────────────────────────

fn delivery(
    webhook_id: &str,
    url: String,
    secret: String,
    event: &str,
    account: &str,
    data: Value,
    now: i64,
) -> Delivery {
    let id = chorus_core::id::new_id(now as u64, rand::random());
    let body = json!({"event": event, "account_id": account, "occurred_at": now, "delivery": id, "data": data});
    Delivery {
        id,
        webhook_id: webhook_id.into(),
        url,
        secret,
        event: event.into(),
        body: body.to_string(),
        attempt: 0,
        due: now,
    }
}

/// Events for freshly accepted ops, per account: `(account, event, data)`.
fn events_for(conn: &Connection, fresh: &[Op]) -> anyhow::Result<Vec<(String, &'static str, Value)>> {
    use std::collections::BTreeSet;
    let mut out = Vec::new();
    let mut fronts: BTreeSet<&str> = BTreeSet::new();
    let mut created: BTreeSet<(&str, &str)> = BTreeSet::new();
    let mut updated: BTreeSet<(&str, &str)> = BTreeSet::new();
    for o in fresh {
        let Some(account) = o.scope.strip_prefix("account:") else { continue };
        match (o.kind.as_str(), o.entity_id.as_deref()) {
            (k, _) if k.starts_with("front.") => {
                fronts.insert(account);
            }
            ("member.create", Some(id)) => {
                created.insert((account, id));
            }
            (k, Some(id)) if k.starts_with("member.") => {
                updated.insert((account, id));
            }
            ("follow.request", Some(id)) => {
                let follower = o.payload.get("follower_account_id").and_then(Value::as_str).unwrap_or_default();
                let who: Option<(Option<String>, Option<String>)> = conn
                    .query_row("SELECT handle, display_name FROM account WHERE id = ?1", [follower], |r| {
                        Ok((r.get(0)?, r.get(1)?))
                    })
                    .ok();
                let (handle, name) = who.unwrap_or((None, None));
                out.push((
                    account.to_string(),
                    "follow.requested",
                    json!({"follow_id": id, "follower": {"account_id": follower, "handle": handle, "display_name": name}}),
                ));
            }
            _ => {}
        }
    }
    for account in fronts {
        let v = api_data::current_front(conn, &Principal::owner(account)).map_err(|e| anyhow::anyhow!("{e}"))?;
        out.push((account.to_string(), "front.switch", json!({"front": v["front"], "since": v["since"]})));
    }
    for (account, id) in &created {
        if let Some(m) = api_data::member(conn, account, id)? {
            out.push((account.to_string(), "member.created", m));
        }
    }
    for (account, id) in updated.difference(&created) {
        if let Some(m) = api_data::member(conn, account, id)? {
            out.push((account.to_string(), "member.updated", m));
        }
    }
    Ok(out)
}

/// Deliveries for freshly accepted ops (called from the sync fan-out, under the db lock).
pub fn deliveries_for(conn: &Connection, fresh: &[Op], now: i64) -> anyhow::Result<Vec<Delivery>> {
    let any: bool = conn.query_row("SELECT EXISTS (SELECT 1 FROM webhook WHERE is_enabled = 1)", [], |r| r.get(0))?;
    if !any {
        return Ok(Vec::new());
    }
    let mut hooks =
        conn.prepare_cached("SELECT id, url, secret, events FROM webhook WHERE account_id = ?1 AND is_enabled = 1")?;
    let mut out = Vec::new();
    for (account, event, data) in events_for(conn, fresh)? {
        let rows: Vec<(String, String, String, String)> = hooks
            .query_map([&account], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?
            .collect::<Result<_, _>>()?;
        for (id, url, secret, events) in rows {
            let events: Vec<String> = serde_json::from_str(&events).unwrap_or_default();
            if events.iter().any(|e| e == event) {
                out.push(delivery(&id, url, secret, event, &account, data.clone(), now));
            }
        }
    }
    Ok(out)
}

// ─── delivery ────────────────────────────────────────────────────────────────

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// `t=<secs>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`.
pub fn signature(secret: &str, t_secs: i64, body: &str) -> String {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(secret.as_bytes()).expect("HMAC takes any key length");
    mac.update(format!("{t_secs}.{body}").as_bytes());
    format!("t={t_secs},v1={}", hex(&mac.finalize().into_bytes()))
}

/// Make one attempt: `Ok(status)` for 2xx, `Err((status, reason))` otherwise.
pub async fn send(
    http: &reqwest::Client,
    d: &Delivery,
    allow_external: bool,
    now: i64,
) -> Result<u16, (Option<u16>, String)> {
    let url = check_url(&d.url, allow_external).await.map_err(|e| (None, e))?;
    let r = http
        .post(url)
        .header("content-type", "application/json")
        .header("user-agent", concat!("Chorus/", env!("CARGO_PKG_VERSION")))
        .header("chorus-event", &d.event)
        .header("chorus-delivery", &d.id)
        .header("chorus-signature", signature(&d.secret, now / 1000, &d.body))
        .body(d.body.clone())
        .send()
        .await
        .map_err(|e| (None, format!("can't reach it: {e}")))?;
    let status = r.status().as_u16();
    if r.status().is_success() { Ok(status) } else { Err((Some(status), format!("it answered HTTP {status}"))) }
}

/// Is the webhook still there and on? (Checked before each attempt, so removing one stops retries.)
pub fn still_enabled(conn: &Connection, webhook_id: &str) -> bool {
    conn.query_row("SELECT is_enabled FROM webhook WHERE id = ?1", [webhook_id], |r| r.get::<_, bool>(0))
        .unwrap_or(false)
}

/// Record an attempt. Returns the retry to schedule, if any; after the last retry fails, the
/// webhook is disabled with the reason.
pub fn record(
    conn: &Connection,
    d: &Delivery,
    outcome: &Result<u16, (Option<u16>, String)>,
    now: i64,
) -> anyhow::Result<Option<Delivery>> {
    match outcome {
        Ok(status) => {
            conn.execute(
                "UPDATE webhook SET last_status = ?2, last_error = NULL WHERE id = ?1",
                params![d.webhook_id, status],
            )?;
            Ok(None)
        }
        Err((status, reason)) => match RETRY_MS.get(d.attempt) {
            Some(wait) => {
                conn.execute(
                    "UPDATE webhook SET last_status = ?2, last_error = ?3 WHERE id = ?1",
                    params![d.webhook_id, status, reason],
                )?;
                Ok(Some(Delivery { attempt: d.attempt + 1, due: now + wait, ..d.clone() }))
            }
            None => {
                let why = format!("turned off after {} failed attempts: {reason}", d.attempt + 1);
                conn.execute(
                    "UPDATE webhook SET is_enabled = 0, last_status = ?2, last_error = ?3 WHERE id = ?1",
                    params![d.webhook_id, status, why],
                )?;
                Ok(None)
            }
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signature_signs_t_dot_body() {
        let s = signature("key", 0, "The quick brown fox jumps over the lazy dog");
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(b"key").unwrap();
        mac.update(b"0.The quick brown fox jumps over the lazy dog");
        assert_eq!(s, format!("t=0,v1={}", hex(&mac.finalize().into_bytes())));
        let known = signature("key", 1, "x");
        assert!(known.starts_with("t=1,v1=") && known.len() == "t=1,v1=".len() + 64);
    }

    #[test]
    fn hmac_itself_matches_the_published_vector() {
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(b"key").unwrap();
        mac.update(b"The quick brown fox jumps over the lazy dog");
        assert_eq!(
            hex(&mac.finalize().into_bytes()),
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
        );
    }

    #[test]
    fn internal_addresses() {
        for ok in ["127.0.0.1", "10.1.2.3", "192.168.1.9", "172.20.0.1", "100.101.102.103", "::1", "fd7a:115c:a1e0::1"]
        {
            assert!(internal_ip(ok.parse().unwrap()), "{ok}");
        }
        for bad in ["8.8.8.8", "100.128.0.1", "1.1.1.1", "2606:4700::1111"] {
            assert!(!internal_ip(bad.parse().unwrap()), "{bad}");
        }
    }

    #[tokio::test]
    async fn url_rules() {
        assert!(check_url("http://127.0.0.1:9/x", false).await.is_ok());
        assert!(check_url("https://box.tail1234.ts.net/hook", false).await.is_ok());
        assert!(check_url("http://nas/hook", false).await.is_ok());
        assert!(check_url("http://8.8.8.8/hook", false).await.is_err());
        assert!(check_url("http://8.8.8.8/hook", true).await.is_ok());
        assert!(check_url("ftp://127.0.0.1/x", true).await.is_err());
    }

    #[test]
    fn retries_then_disables() {
        let conn = crate::db::open_memory().unwrap();
        let mut conn = conn;
        crate::db::migrate(&mut conn).unwrap();
        conn.execute(
            "INSERT INTO webhook (id, account_id, url, secret, events, created_at) VALUES ('w', 'a', 'http://x', 's', '[]', 0)",
            [],
        )
        .unwrap();
        let mut d = delivery("w", "http://x".into(), "s".into(), "ping", "a", json!({}), 0);
        let fail: Result<u16, (Option<u16>, String)> = Err((Some(500), "it answered HTTP 500".into()));
        let mut dues = Vec::new();
        while let Some(next) = record(&conn, &d, &fail, d.due).unwrap() {
            dues.push(next.due - d.due);
            d = next;
        }
        assert_eq!(dues, RETRY_MS.to_vec());
        assert!(!still_enabled(&conn, "w"));
        let err: String = conn.query_row("SELECT last_error FROM webhook", [], |r| r.get(0)).unwrap();
        assert!(err.starts_with("turned off after 6 failed attempts"), "{err}");
        // turning it back on clears the error; a success records the status
        let p = Principal::owner("a");
        update(&conn, &p, "w", Some(true), None).unwrap();
        assert!(still_enabled(&conn, "w"));
        assert_eq!(record(&conn, &d, &Ok(204), 0).unwrap().map(|d| d.id), None);
        let st: i64 = conn.query_row("SELECT last_status FROM webhook", [], |r| r.get(0)).unwrap();
        assert_eq!(st, 204);
    }
}
