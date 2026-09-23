//! Push delivery to devices through UnifiedPush (NOTIFICATIONS.md §1).
//!
//! Each Android device registers an endpoint from its distributor (the ntfy app pointed at the
//! owner's ntfy server) plus Web Push keys. The server encrypts every payload with RFC 8291
//! (`aes128gcm`), so ntfy only ever relays ciphertext, and POSTs it to the endpoint.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes128Gcm, Nonce};
use base64::Engine as _;
use hkdf::Hkdf;
use p256::elliptic_curve::sec1::ToSec1Point;
use p256::{PublicKey, SecretKey};
use rusqlite::{Connection, params};
use serde::Deserialize;
use sha2::Sha256;

/// Record size advertised in the header; payloads are always a single record.
const RS: u32 = 4096;
/// Largest plaintext we send; above this the app gets a tickle and syncs instead (§1).
pub const MAX_PAYLOAD: usize = 3000;

#[derive(Debug, thiserror::Error)]
pub enum PushError {
    #[error("bad push key: {0}")]
    BadKey(&'static str),
    #[error("encryption failed")]
    Crypto,
}

pub fn b64url(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

pub fn unb64url(s: &str) -> Option<Vec<u8>> {
    let s = s.trim().trim_end_matches('=');
    base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(s).ok()
}

fn hkdf(salt: &[u8], ikm: &[u8], info: &[u8], len: usize) -> Result<Vec<u8>, PushError> {
    let mut out = vec![0; len];
    Hkdf::<Sha256>::new(Some(salt), ikm).expand(info, &mut out).map_err(|_| PushError::Crypto)?;
    Ok(out)
}

/// RFC 8291 `aes128gcm` encryption with a given sender key and salt (tests pin both).
pub fn encrypt_with(
    ua_public: &[u8],
    auth_secret: &[u8],
    plaintext: &[u8],
    as_secret: &SecretKey,
    salt: &[u8; 16],
) -> Result<Vec<u8>, PushError> {
    let ua = PublicKey::from_sec1_bytes(ua_public).map_err(|_| PushError::BadKey("p256dh"))?;
    if auth_secret.len() != 16 {
        return Err(PushError::BadKey("auth"));
    }
    let as_public = as_secret.public_key().to_sec1_point(false);
    let as_public = as_public.as_bytes();
    let shared = p256::ecdh::diffie_hellman(as_secret.to_nonzero_scalar(), ua.as_affine());

    // IKM = HKDF(auth, ecdh, "WebPush: info\0" || ua_public || as_public, 32)
    let mut key_info = b"WebPush: info\0".to_vec();
    key_info.extend_from_slice(ua_public);
    key_info.extend_from_slice(as_public);
    let ikm = hkdf(auth_secret, shared.raw_secret_bytes().as_slice(), &key_info, 32)?;
    let cek = hkdf(salt, &ikm, b"Content-Encoding: aes128gcm\0", 16)?;
    let nonce = hkdf(salt, &ikm, b"Content-Encoding: nonce\0", 12)?;

    let mut padded = plaintext.to_vec();
    padded.push(0x02); // last (and only) record delimiter
    let cipher = Aes128Gcm::new_from_slice(&cek).map_err(|_| PushError::Crypto)?;
    let body =
        cipher.encrypt(Nonce::from_slice(&nonce), Payload { msg: &padded, aad: b"" }).map_err(|_| PushError::Crypto)?;

    let mut out = Vec::with_capacity(16 + 4 + 1 + as_public.len() + body.len());
    out.extend_from_slice(salt);
    out.extend_from_slice(&RS.to_be_bytes());
    out.push(as_public.len() as u8);
    out.extend_from_slice(as_public);
    out.extend_from_slice(&body);
    Ok(out)
}

/// Encrypt for a device with a fresh sender key and salt.
pub fn encrypt(ua_public: &[u8], auth_secret: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, PushError> {
    let mut seed = [0u8; 32];
    let secret = loop {
        rand::fill(&mut seed);
        if let Ok(k) = SecretKey::from_slice(&seed) {
            break k;
        }
    };
    let mut salt = [0u8; 16];
    rand::fill(&mut salt);
    encrypt_with(ua_public, auth_secret, plaintext, &secret, &salt)
}

// ─── registration (API.md §2) ───────────────────────────────────────────────

#[derive(Deserialize)]
pub struct Registration {
    pub endpoint: String,
    /// The device's P-256 public key, uncompressed, base64url.
    pub p256dh: String,
    /// 16-byte auth secret, base64url.
    pub auth: String,
}

pub fn register(conn: &Connection, device_id: &str, r: &Registration) -> Result<(), PushError> {
    if !(r.endpoint.starts_with("https://") || r.endpoint.starts_with("http://")) {
        return Err(PushError::BadKey("endpoint"));
    }
    let key = unb64url(&r.p256dh).ok_or(PushError::BadKey("p256dh"))?;
    PublicKey::from_sec1_bytes(&key).map_err(|_| PushError::BadKey("p256dh"))?;
    if unb64url(&r.auth).is_none_or(|a| a.len() != 16) {
        return Err(PushError::BadKey("auth"));
    }
    conn.execute(
        "UPDATE device SET push_endpoint = ?2, push_p256dh = ?3, push_auth = ?4, push_failures = 0 WHERE id = ?1",
        params![device_id, r.endpoint, r.p256dh, r.auth],
    )
    .map_err(|_| PushError::Crypto)?;
    Ok(())
}

pub fn unregister(conn: &Connection, device_id: &str) -> anyhow::Result<()> {
    conn.execute(
        "UPDATE device SET push_endpoint = NULL, push_p256dh = NULL, push_auth = NULL WHERE id = ?1",
        [device_id],
    )?;
    Ok(())
}

/// One encrypted push, ready to send without holding the database lock.
#[derive(Debug, Clone)]
pub struct Outbound {
    pub device_id: String,
    pub endpoint: String,
    pub body: Vec<u8>,
}

/// Encrypt `payload` (JSON) for every registered device of `account`. A payload that is too big
/// becomes a tickle (`{"t":"sync"}`) so the app fetches it itself.
pub fn prepare(conn: &Connection, account: &str, payload: &serde_json::Value) -> anyhow::Result<Vec<Outbound>> {
    let mut text = serde_json::to_vec(payload)?;
    if text.len() > MAX_PAYLOAD {
        text = br#"{"t":"sync"}"#.to_vec();
    }
    let mut st = conn.prepare_cached(
        "SELECT id, push_endpoint, push_p256dh, push_auth FROM device
         WHERE account_id = ?1 AND revoked_at IS NULL AND push_endpoint IS NOT NULL",
    )?;
    let rows: Vec<(String, String, String, String)> =
        st.query_map([account], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?.collect::<Result<_, _>>()?;
    let mut out = Vec::new();
    for (device_id, endpoint, key, auth) in rows {
        let (Some(key), Some(auth)) = (unb64url(&key), unb64url(&auth)) else { continue };
        match encrypt(&key, &auth, &text) {
            Ok(body) => out.push(Outbound { device_id, endpoint, body }),
            Err(e) => tracing::warn!(device = %device_id, error = %e, "push: can't encrypt"),
        }
    }
    Ok(out)
}

/// What to do with a device after a send attempt.
pub enum Sent {
    Ok,
    /// 404/410: the endpoint is gone (app uninstalled or re-registered).
    Gone,
    Failed,
}

pub async fn send(http: &reqwest::Client, o: &Outbound) -> Sent {
    let r = http
        .post(&o.endpoint)
        .header("Content-Encoding", "aes128gcm")
        .header("Content-Type", "application/octet-stream")
        .header("TTL", "86400")
        .header("Urgency", "normal")
        .body(o.body.clone())
        .send()
        .await;
    match r {
        Ok(r) if r.status().is_success() => Sent::Ok,
        Ok(r) if matches!(r.status().as_u16(), 404 | 410) => Sent::Gone,
        Ok(r) => {
            tracing::warn!(device = %o.device_id, status = %r.status(), "push: endpoint refused");
            Sent::Failed
        }
        Err(e) => {
            tracing::warn!(device = %o.device_id, error = %e, "push: send failed");
            Sent::Failed
        }
    }
}

/// Record the outcome: gone endpoints are cleared; repeated failures are counted.
pub fn record(conn: &Connection, device_id: &str, sent: &Sent) -> anyhow::Result<()> {
    match sent {
        Sent::Ok => conn.execute("UPDATE device SET push_failures = 0 WHERE id = ?1", [device_id])?,
        Sent::Gone => conn.execute(
            "UPDATE device SET push_endpoint = NULL, push_p256dh = NULL, push_auth = NULL WHERE id = ?1",
            [device_id],
        )?,
        Sent::Failed => {
            conn.execute("UPDATE device SET push_failures = push_failures + 1 WHERE id = ?1", [device_id])?
        }
    };
    Ok(())
}

/// The receiving side of RFC 8291 (what the Android app does). Used by tests and diagnostics.
pub fn decrypt(ua_secret: &SecretKey, auth: &[u8], msg: &[u8]) -> Option<Vec<u8>> {
    if msg.len() < 21 {
        return None;
    }
    let salt = &msg[..16];
    let idlen = msg[20] as usize;
    let as_public = msg.get(21..21 + idlen)?;
    let body = msg.get(21 + idlen..)?;
    let ua_public = ua_secret.public_key().to_sec1_point(false);
    let as_key = PublicKey::from_sec1_bytes(as_public).ok()?;
    let shared = p256::ecdh::diffie_hellman(ua_secret.to_nonzero_scalar(), as_key.as_affine());
    let mut key_info = b"WebPush: info\0".to_vec();
    key_info.extend_from_slice(ua_public.as_bytes());
    key_info.extend_from_slice(as_public);
    let ikm = hkdf(auth, shared.raw_secret_bytes().as_slice(), &key_info, 32).ok()?;
    let cek = hkdf(salt, &ikm, b"Content-Encoding: aes128gcm\0", 16).ok()?;
    let nonce = hkdf(salt, &ikm, b"Content-Encoding: nonce\0", 12).ok()?;
    let mut plain = Aes128Gcm::new_from_slice(&cek).ok()?.decrypt(Nonce::from_slice(&nonce), body).ok()?;
    (plain.pop() == Some(0x02)).then_some(plain)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(seed: u8) -> SecretKey {
        SecretKey::from_slice(&[seed; 32]).unwrap()
    }

    #[test]
    fn round_trips_and_uses_the_aes128gcm_layout() {
        let ua = key(7);
        let ua_public = ua.public_key().to_sec1_point(false);
        let auth = [9u8; 16];
        let msg = br#"{"t":"switch","text":"Kai is fronting"}"#;
        let out = encrypt(ua_public.as_bytes(), &auth, msg).unwrap();
        assert_eq!(out[20], 65); // uncompressed P-256 key id
        assert_eq!(decrypt(&ua, &auth, &out).unwrap(), msg);
        // fresh sender key and salt every time
        assert_ne!(encrypt(ua_public.as_bytes(), &auth, msg).unwrap(), out);
        // deterministic with pinned inputs
        let a = encrypt_with(ua_public.as_bytes(), &auth, msg, &key(3), &[1; 16]).unwrap();
        let b = encrypt_with(ua_public.as_bytes(), &auth, msg, &key(3), &[1; 16]).unwrap();
        assert_eq!(a, b);
    }

    /// RFC 8291 §5 / Appendix A: fixed keys and salt give exactly the published message.
    #[test]
    fn matches_the_rfc_8291_example() {
        let d = |s: &str| unb64url(&s.replace(' ', "")).unwrap();
        let ua_public = d("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcx aOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        let auth = d("BTBZMqHH6r4Tts7J_aSIgg");
        let as_secret = SecretKey::from_slice(&d("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")).unwrap();
        let salt: [u8; 16] = d("DGv6ra1nlYgDCS1FRnbzlw").try_into().unwrap();
        let out =
            encrypt_with(&ua_public, &auth, b"When I grow up, I want to be a watermelon", &as_secret, &salt).unwrap();
        let expected = d(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml            mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT            pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
        );
        assert_eq!(b64url(&out), b64url(&expected));
        // and the receiver's private key from the RFC decrypts it
        let ua_secret = SecretKey::from_slice(&d("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")).unwrap();
        assert_eq!(decrypt(&ua_secret, &auth, &out).unwrap(), b"When I grow up, I want to be a watermelon");
    }

    #[test]
    fn rejects_bad_keys() {
        assert!(encrypt(&[4; 65], &[0; 16], b"x").is_err());
        let ua = key(7).public_key().to_sec1_point(false);
        assert!(encrypt(ua.as_bytes(), &[0; 15], b"x").is_err());
    }
}
