//! Request rate limits for `/api/v1` (API.md §1, OPS.md §9): a token bucket per credential —
//! the API token or session in the request, else the client address — with a stricter bucket per
//! address for the sign-in endpoints. Over the limit: `429 rate_limited` with `Retry-After`.
//!
//! Blob reads are left out (a channel full of images loads many at once; they are immutable and
//! authenticated anyway). Behind Caddy every request comes from loopback, so the address is the
//! last `X-Forwarded-For` hop — trusted only when the connection itself is from loopback.

use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::net::{IpAddr, SocketAddr};
use std::sync::Mutex;
use std::time::Instant;

use axum::extract::{ConnectInfo, Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use serde_json::json;

use crate::app::AppState;

/// Sign-in (`/auth/*`): per address, whatever credential is sent.
const AUTH_BURST: f64 = 20.0;
const AUTH_PER_SECOND: f64 = 1.0;
/// Forget buckets past this many (the full ones: they carry no state worth keeping).
const MAX_BUCKETS: usize = 10_000;

#[derive(Clone, Copy)]
struct Bucket {
    tokens: f64,
    at: Instant,
}

pub struct Limiter {
    burst: f64,
    per_second: f64,
    buckets: Mutex<HashMap<String, Bucket>>,
}

impl Limiter {
    /// `per_second <= 0` turns limiting off.
    pub fn new(burst: u32, per_second: f64) -> Limiter {
        Limiter { burst: f64::from(burst.max(1)), per_second, buckets: Mutex::new(HashMap::new()) }
    }

    /// Take one request from `key`'s bucket; `Err(seconds)` until the next one is allowed.
    fn take(&self, key: String, burst: f64, per_second: f64, now: Instant) -> Result<(), u64> {
        let mut map = self.buckets.lock().unwrap_or_else(|e| e.into_inner());
        if map.len() >= MAX_BUCKETS {
            map.retain(|_, b| b.tokens + now.duration_since(b.at).as_secs_f64() * per_second < burst);
        }
        let b = map.entry(key).or_insert(Bucket { tokens: burst, at: now });
        b.tokens = (b.tokens + now.duration_since(b.at).as_secs_f64() * per_second).min(burst);
        b.at = now;
        if b.tokens >= 1.0 {
            b.tokens -= 1.0;
            Ok(())
        } else {
            Err(((1.0 - b.tokens) / per_second).ceil().max(1.0) as u64)
        }
    }

    fn check(
        &self,
        path: &str,
        method: &Method,
        headers: &HeaderMap,
        query: Option<&str>,
        peer: Option<SocketAddr>,
    ) -> Result<(), u64> {
        if self.per_second <= 0.0 || (path.contains("/blobs/") && matches!(*method, Method::GET | Method::HEAD)) {
            return Ok(());
        }
        let addr = client_addr(headers, peer);
        let now = Instant::now();
        // no address to tell clients apart (an embedded router): only credentials are limited
        let known = addr != "unknown";
        if path.contains("/auth/") {
            if !known {
                return Ok(());
            }
            return self.take(format!("auth:{addr}"), AUTH_BURST, AUTH_PER_SECOND.min(self.per_second), now);
        }
        let key = match credential(headers, query) {
            // hashed, so the table doesn't hold secrets
            Some(c) => {
                let mut h = std::collections::hash_map::DefaultHasher::new();
                c.hash(&mut h);
                format!("cred:{:016x}", h.finish())
            }
            None if known => format!("addr:{addr}"),
            None => return Ok(()),
        };
        self.take(key, self.burst, self.per_second, now)
    }
}

fn credential<'a>(headers: &'a HeaderMap, query: Option<&'a str>) -> Option<&'a str> {
    if let Some(t) =
        headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()).and_then(|v| v.strip_prefix("Bearer "))
    {
        return Some(t);
    }
    query?.split('&').find_map(|kv| kv.strip_prefix("token=")).filter(|t| !t.is_empty())
}

/// The client's address: the connection's, or through a local reverse proxy the last hop it
/// added to `X-Forwarded-For`.
fn client_addr(headers: &HeaderMap, peer: Option<SocketAddr>) -> String {
    let Some(peer) = peer else { return "unknown".into() };
    if peer.ip().is_loopback()
        && let Some(ip) = headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.rsplit(',').next())
            .and_then(|v| v.trim().parse::<IpAddr>().ok())
    {
        return ip.to_string();
    }
    peer.ip().to_string()
}

/// Middleware for the API router.
pub async fn limit(State(s): State<AppState>, req: Request, next: Next) -> Response {
    let peer = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|c| c.0);
    match s.limiter.check(req.uri().path(), req.method(), req.headers(), req.uri().query(), peer) {
        Ok(()) => next.run(req).await,
        Err(wait) => {
            let body =
                json!({"error": {"code": "rate_limited", "message": "too many requests; slow down", "retry": true}});
            let mut r = (StatusCode::TOO_MANY_REQUESTS, axum::Json(body)).into_response();
            r.headers_mut().insert(header::RETRY_AFTER, HeaderValue::from(wait));
            r
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn bucket_allows_a_burst_then_the_sustained_rate() {
        let l = Limiter::new(3, 2.0);
        let t0 = Instant::now();
        for _ in 0..3 {
            assert!(l.take("k".into(), 3.0, 2.0, t0).is_ok());
        }
        assert_eq!(l.take("k".into(), 3.0, 2.0, t0), Err(1));
        // half a second later one more request's worth has come back
        assert!(l.take("k".into(), 3.0, 2.0, t0 + Duration::from_millis(500)).is_ok());
        assert!(l.take("k".into(), 3.0, 2.0, t0 + Duration::from_millis(500)).is_err());
        // other keys have their own bucket
        assert!(l.take("other".into(), 3.0, 2.0, t0).is_ok());
    }

    #[test]
    fn forwarded_address_is_trusted_only_from_loopback() {
        let mut h = HeaderMap::new();
        h.insert("x-forwarded-for", HeaderValue::from_static("203.0.113.9, 198.51.100.7"));
        let local: SocketAddr = "127.0.0.1:5000".parse().unwrap();
        let remote: SocketAddr = "192.0.2.1:5000".parse().unwrap();
        assert_eq!(client_addr(&h, Some(local)), "198.51.100.7");
        assert_eq!(client_addr(&h, Some(remote)), "192.0.2.1");
        assert_eq!(client_addr(&HeaderMap::new(), Some(local)), "127.0.0.1");
    }

    #[test]
    fn credentials_come_from_the_header_or_the_token_query() {
        let mut h = HeaderMap::new();
        assert_eq!(credential(&h, Some("a=1&token=abc")), Some("abc"));
        assert_eq!(credential(&h, Some("a=1")), None);
        h.insert(header::AUTHORIZATION, HeaderValue::from_static("Bearer xyz"));
        assert_eq!(credential(&h, Some("token=abc")), Some("xyz"));
    }

    #[test]
    fn blob_reads_and_a_zero_rate_are_never_limited() {
        let l = Limiter::new(1, 1.0);
        let h = HeaderMap::new();
        for _ in 0..5 {
            assert!(l.check("/blobs/ab", &Method::GET, &h, None, None).is_ok());
        }
        let peer = Some("192.0.2.1:9".parse().unwrap());
        assert!(l.check("/me", &Method::GET, &h, None, peer).is_ok());
        assert!(l.check("/me", &Method::GET, &h, None, peer).is_err());
        let off = Limiter::new(1, 0.0);
        for _ in 0..5 {
            assert!(off.check("/me", &Method::GET, &h, None, peer).is_ok());
        }
    }
}
