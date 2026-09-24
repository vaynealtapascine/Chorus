//! Webhooks (API.md §7, M10.2): POST a signed JSON event to the owner's own URL when something
//! happens in their account.
//!
//! - Configured from a signed-in device (never an API token); the signing secret is shown once.
//! - Events: `front.switch`, `member.created`, `member.updated`, `follow.requested`,
//!   `message.created` and `post.created` (the account's own messages and posts, as its author
//!   sees them), plus `ping` from the "send a test" button.
//! - `Chorus-Signature: t=<unix s>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`.
//! - Retries after 1 m, 5 m, 30 m, 2 h, 12 h; after that the webhook is disabled and the reason is
//!   shown in the app. Pending retries live in memory, so a restart drops them (the next event
//!   still goes out).
//! - Where a URL may point is `security.webhook_targets`: tailnet/LAN only (`internal`, the
//!   default; never loopback), public addresses only (`public`, for a server on the internet), or
//!   `any`. The check runs when the webhook is saved and again before every delivery; names are
//!   resolved, the delivery connects to exactly the address that was checked (no DNS rebinding),
//!   and redirects are not followed.

use std::net::{IpAddr, SocketAddr};

use chorus_core::op::Op;
use hkdf::hmac::{Hmac, Mac};
use rusqlite::{Connection, params};
use serde_json::{Value, json};
use sha2::Sha256;

use crate::api_data::{self, DataError, Principal};
use crate::config::WebhookTargets;

pub const EVENTS: &[&str] =
    &["front.switch", "member.created", "member.updated", "follow.requested", "message.created", "post.created"];

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

pub(crate) fn internal_ip(ip: IpAddr) -> bool {
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

/// Neither internal nor special-purpose: somewhere out on the internet.
pub(crate) fn public_ip(ip: IpAddr) -> bool {
    if internal_ip(ip) || ip.is_unspecified() || ip.is_multicast() {
        return false;
    }
    match ip {
        IpAddr::V4(v) => {
            let [a, b, c, _] = v.octets();
            !(v.is_broadcast()
                || v.is_documentation()
                || a == 0
                || (a == 192 && b == 0 && c == 0)
                || (a == 198 && (18..20).contains(&b))
                || a >= 240)
        }
        IpAddr::V6(v) => {
            let s = v.segments();
            // documentation, NAT64 (could wrap an internal v4 address), 6to4 / Teredo relays
            !((s[0] == 0x2001 && s[1] == 0x0db8)
                || (s[0] == 0x64 && s[1] == 0xff9b)
                || s[0] == 0x2002
                || (s[0] == 0x2001 && s[1] == 0))
        }
    }
}

fn allowed(ip: IpAddr, targets: WebhookTargets) -> bool {
    match targets {
        WebhookTargets::Internal => internal_ip(ip) && !ip.to_canonical().is_loopback(),
        WebhookTargets::Public => public_ip(ip),
        WebhookTargets::Any => true,
    }
}

const INTERNAL_SUFFIXES: &[&str] = &[".ts.net", ".local", ".lan", ".internal", ".home.arpa"];

/// A checked webhook URL, and the address the delivery must connect to (host names only).
#[derive(Debug)]
pub struct Target {
    pub url: reqwest::Url,
    pub pin: Option<SocketAddr>,
}

/// Refuse URLs outside what `targets` allows. Host names are resolved and every address must
/// be allowed; the first one is pinned for the connection (Caddy sites on the tailnet resolve to
/// 100.x, so `internal` accepts them).
pub async fn check_url(url: &str, targets: WebhookTargets) -> Result<Target, String> {
    let u = reqwest::Url::parse(url).map_err(|e| format!("not a URL: {e}"))?;
    if !matches!(u.scheme(), "http" | "https") {
        return Err("webhook URLs must be http or https".into());
    }
    if targets == WebhookTargets::Any {
        return Ok(Target { url: u, pin: None });
    }
    let refused = match targets {
        WebhookTargets::Public => "webhooks on this server may only point to public internet addresses",
        _ => "that address is outside the tailnet (an admin can allow external webhooks)",
    };
    resolve_checked(u, move |ip| allowed(ip, targets), targets == WebhookTargets::Internal, false, refused).await
}

/// Resolve `u`'s host and require `ok` of every address (and of an IP literal); pin the first.
/// `names_ok`: tailnet/LAN names that don't resolve from here (MagicDNS, mDNS) pass unpinned.
/// `local_ok`: `localhost` may be named (only for a host the operator configured).
pub(crate) async fn resolve_checked(
    u: reqwest::Url,
    ok: impl Fn(IpAddr) -> bool + Send,
    names_ok: bool,
    local_ok: bool,
    refused: &str,
) -> Result<Target, String> {
    let refused = || refused.to_string();
    let host = u.host_str().unwrap_or_default().trim_start_matches('[').trim_end_matches(']').to_ascii_lowercase();
    if let Ok(ip) = host.parse::<IpAddr>() {
        return if ok(ip) { Ok(Target { url: u, pin: None }) } else { Err(refused()) };
    }
    if host.is_empty() || (!local_ok && (host == "localhost" || host.ends_with(".localhost"))) {
        return Err(refused());
    }
    let plausible = names_ok && (!host.contains('.') || INTERNAL_SUFFIXES.iter().any(|s| host.ends_with(s)));
    let port = u.port_or_known_default().unwrap_or(443);
    let addrs: Vec<SocketAddr> = match tokio::net::lookup_host((host.as_str(), port)).await {
        Ok(a) => a.collect(),
        Err(_) if plausible => return Ok(Target { url: u, pin: None }),
        Err(e) => return Err(format!("can't resolve {host}: {e}")),
    };
    if addrs.is_empty() && plausible {
        return Ok(Target { url: u, pin: None });
    }
    match addrs.first() {
        Some(first) if addrs.iter().all(|a| ok(a.ip())) => Ok(Target { url: u, pin: Some(*first) }),
        _ => Err(refused()),
    }
}

/// An HTTP client that connects only to the checked address and never follows a redirect.
pub(crate) fn pinned_client(target: &Target) -> Result<reqwest::Client, String> {
    let mut http = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::none());
    if let (Some(pin), Some(host)) = (target.pin, target.url.host_str()) {
        http = http.resolve(host, pin);
    }
    http.build().map_err(|e| format!("can't build the request: {e}"))
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
        // a message is its author's event, in whatever space it was said
        if matches!(o.kind.as_str(), "message.send" | "message.forward")
            && let (Some(author), Some(id)) = (o.account_id.as_deref(), o.entity_id.as_deref())
        {
            let owner = Principal::owner(author);
            if let Some(m) = crate::search::message_by_id(conn, &owner, id).map_err(|e| anyhow::anyhow!("{e}"))? {
                out.push((author.to_string(), "message.created", m));
            }
            continue;
        }
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
            ("post.create", Some(id)) => {
                if let Some(post) = crate::posts::one(conn, account, id)? {
                    out.push((account.to_string(), "post.created", post));
                }
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
pub async fn send(d: &Delivery, targets: WebhookTargets, now: i64) -> Result<u16, (Option<u16>, String)> {
    let target = check_url(&d.url, targets).await.map_err(|e| (None, e))?;
    // connect to exactly the address that passed the check, and never follow a redirect
    let http = pinned_client(&target).map_err(|e| (None, e))?;
    let r = http
        .post(target.url)
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

    #[test]
    fn public_addresses() {
        for ok in ["8.8.8.8", "1.1.1.1", "2606:4700::1111"] {
            assert!(public_ip(ok.parse().unwrap()), "{ok}");
        }
        for bad in [
            "127.0.0.1",
            "10.1.2.3",
            "192.168.1.9",
            "100.101.102.103",
            "169.254.169.254",
            "0.0.0.0",
            "255.255.255.255",
            "224.0.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "::1",
            "::",
            "fd00::1",
            "fe80::1",
            "::ffff:127.0.0.1",
            "64:ff9b::a01:203",
            "2001:db8::1",
        ] {
            assert!(!public_ip(bad.parse().unwrap()), "{bad}");
        }
    }

    #[tokio::test]
    async fn url_rules() {
        use WebhookTargets::*;
        // internal: the tailnet and LAN, but not this host's own loopback services
        assert!(check_url("http://10.1.2.3:9/x", Internal).await.is_ok());
        assert!(check_url("http://127.0.0.1:9/x", Internal).await.is_err());
        assert!(check_url("http://localhost:2019/load", Internal).await.is_err());
        assert!(check_url("http://[::ffff:127.0.0.1]/x", Internal).await.is_err());
        assert!(check_url("https://box.tail1234.ts.net/hook", Internal).await.is_ok());
        assert!(check_url("http://nas/hook", Internal).await.is_ok());
        assert!(check_url("http://8.8.8.8/hook", Internal).await.is_err());
        // public: the internet only
        assert!(check_url("http://8.8.8.8/hook", Public).await.is_ok());
        for bad in [
            "http://127.0.0.1:2019/",
            "http://10.0.0.1/",
            "http://169.254.169.254/latest",
            "http://nas/hook",
            "http://localhost/",
        ] {
            assert!(check_url(bad, Public).await.is_err(), "{bad}");
        }
        assert!(check_url("http://127.0.0.1:9/x", Any).await.is_ok());
        assert!(check_url("ftp://127.0.0.1/x", Any).await.is_err());
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
