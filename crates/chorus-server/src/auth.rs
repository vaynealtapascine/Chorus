//! Invites, device enrolment and sessions (docs/API.md §2, D-034). No passwords: each device holds
//! a P-256 key; sessions are renewed by signing a server nonce.

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use chorus_core::id;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};

use crate::ingest;

pub const INVITE_TTL_MS: i64 = 7 * 24 * 3_600_000;
pub const NONCE_TTL_MS: i64 = 5 * 60_000;

#[derive(Debug, thiserror::Error)]
pub enum AuthError {
    #[error("invite not found, expired or used up")]
    BadInvite,
    #[error("handle already taken")]
    HandleTaken,
    #[error("invalid public key")]
    BadKey,
    #[error("invalid signature")]
    BadSignature,
    #[error("unknown or revoked device")]
    UnknownDevice,
    #[error("session expired or unknown")]
    BadSession,
    #[error("bad request: {0}")]
    BadRequest(String),
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl From<rusqlite::Error> for AuthError {
    fn from(e: rusqlite::Error) -> Self {
        AuthError::Internal(e.into())
    }
}

pub fn hash(s: &str) -> String {
    Sha256::digest(s.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

fn random_token(bytes: usize) -> String {
    let v: Vec<u8> = (0..bytes).map(|_| rand::random::<u8>()).collect();
    B64.encode(v)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum InviteKind {
    System,
    Person,
    Device,
}

impl InviteKind {
    fn as_str(self) -> &'static str {
        match self {
            InviteKind::System => "system",
            InviteKind::Person => "person",
            InviteKind::Device => "device",
        }
    }
}

/// Create an invite; returns the one-time code (only its hash is stored).
pub fn create_invite(
    conn: &Connection,
    kind: InviteKind,
    account_id: Option<&str>,
    created_by: &str,
    ttl_ms: i64,
    max_uses: u32,
    now: i64,
) -> anyhow::Result<String> {
    let code = random_token(18);
    conn.execute(
        "INSERT INTO invite (code_hash, kind, account_id, created_by, created_at, expires_at, max_uses)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![hash(&code), kind.as_str(), account_id, created_by, now, now + ttl_ms, max_uses],
    )?;
    Ok(code)
}

#[derive(Clone, Debug, Deserialize)]
pub struct DeviceIn {
    pub name: String,
    pub platform: String,
    /// SPKI DER, base64 (standard or url-safe).
    pub public_key: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct AccountIn {
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub handle: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct Enrolled {
    pub account_id: String,
    pub device_id: String,
    pub short_id: String,
    pub session: String,
    pub expires_at: i64,
}

fn decode_b64(s: &str) -> Option<Vec<u8>> {
    use base64::engine::general_purpose::{STANDARD, URL_SAFE};
    let t = s.trim();
    [STANDARD.decode(t).ok(), URL_SAFE.decode(t).ok(), B64.decode(t).ok()].into_iter().flatten().next()
}

pub fn parse_key(spki_b64: &str) -> Result<VerifyingKey, AuthError> {
    let der = decode_b64(spki_b64).ok_or(AuthError::BadKey)?;
    VerifyingKey::from_public_key_der(&der).map_err(|_| AuthError::BadKey)
}

fn valid_handle(h: &str) -> bool {
    (2..=32).contains(&h.len()) && h.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

/// Redeem an invite: a new account (system/person) with its first device, or a new device on an
/// existing account.
pub fn redeem(
    conn: &mut Connection,
    code: &str,
    device: &DeviceIn,
    account: Option<&AccountIn>,
    now: i64,
    session_ttl_ms: i64,
) -> Result<Enrolled, AuthError> {
    parse_key(&device.public_key)?;
    if !matches!(device.platform.as_str(), "android" | "web" | "cli") {
        return Err(AuthError::BadRequest("platform must be android, web or cli".into()));
    }
    let tx = conn.transaction()?;
    let inv: Option<(String, Option<String>, i64, i64, i64)> = tx
        .query_row(
            "SELECT kind, account_id, expires_at, max_uses, uses FROM invite WHERE code_hash = ?1",
            [hash(code)],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()?;
    let Some((kind, inv_account, expires_at, max_uses, uses)) = inv else { return Err(AuthError::BadInvite) };
    if expires_at < now || uses >= max_uses {
        return Err(AuthError::BadInvite);
    }
    tx.execute("UPDATE invite SET uses = uses + 1 WHERE code_hash = ?1", [hash(code)])?;

    let account_id = if kind == "device" {
        inv_account.ok_or(AuthError::BadInvite)?
    } else {
        let a = account.ok_or_else(|| AuthError::BadRequest("account details required".into()))?;
        let handle = a.handle.clone().unwrap_or_default().to_lowercase();
        if !handle.is_empty() && !valid_handle(&handle) {
            return Err(AuthError::BadRequest("handle: 2–32 of a-z, 0-9, _".into()));
        }
        let taken = !handle.is_empty()
            && tx.query_row("SELECT 1 FROM account WHERE handle = ?1", [&handle], |_| Ok(())).optional()?.is_some();
        if taken {
            return Err(AuthError::HandleTaken);
        }
        let first: bool = tx.query_row("SELECT count(*) = 0 FROM account", [], |r| r.get(0))?;
        let id = id::new_id(now as u64, rand::random());
        tx.execute(
            "INSERT INTO account (id, kind, handle, display_name, is_admin, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![id, kind, (!handle.is_empty()).then_some(&handle), a.display_name, first, now],
        )?;
        let acct_scope = format!("account:{id}");
        ingest::grant(&tx, &id, &acct_scope)?;
        if kind == "system" {
            // Every system gets a private internal space (SPEC §5.1), created as ordinary ops so
            // devices receive it through sync.
            let space = id::new_id(now as u64, rand::random());
            let space_scope = format!("space:{space}");
            ingest::grant(&tx, &id, &space_scope)?;
            ingest::server_op(
                &tx,
                &id,
                "space.create",
                &space_scope,
                Some(&space),
                json!({"kind": "internal", "name": "Home"}),
                now,
            )?;
            ingest::server_op(&tx, &id, "space.join", &space_scope, Some(&space), json!({"account_id": id}), now)?;
            let chan = id::new_id(now as u64, rand::random());
            ingest::server_op(
                &tx,
                &id,
                "channel.create",
                &space_scope,
                Some(&chan),
                json!({"space_id": space, "kind": "text", "name": "general"}),
                now,
            )?;
        }
        id
    };

    let device_id = id::new_id(now as u64, rand::random());
    let short_id = loop {
        let s = id::device_short_id(rand::random());
        let used = tx.query_row("SELECT 1 FROM device WHERE short_id = ?1", [&s], |_| Ok(())).optional()?.is_some();
        if !used && s != format!("{:08x}", ingest::SERVER_NODE) {
            break s;
        }
    };
    tx.execute(
        "INSERT INTO device (id, account_id, short_id, name, platform, public_key, created_at, last_seen_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7)",
        params![device_id, account_id, short_id, device.name, device.platform, device.public_key, now],
    )?;
    let session = new_session(&tx, &device_id, now, session_ttl_ms)?;
    tx.commit()?;
    Ok(Enrolled { account_id, device_id, short_id, session, expires_at: now + session_ttl_ms })
}

fn new_session(conn: &Connection, device_id: &str, now: i64, ttl_ms: i64) -> anyhow::Result<String> {
    let token = random_token(32);
    conn.execute(
        "INSERT INTO session (token_hash, device_id, created_at, expires_at) VALUES (?1, ?2, ?3, ?4)",
        params![hash(&token), device_id, now, now + ttl_ms],
    )?;
    Ok(token)
}

/// Who a session token belongs to. Sliding expiry: use extends it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Authed {
    pub account_id: String,
    pub device_id: String,
}

pub fn authenticate(conn: &Connection, token: &str, now: i64, ttl_ms: i64) -> Result<Authed, AuthError> {
    let row: Option<(String, String, i64, Option<i64>)> = conn
        .query_row(
            "SELECT d.id, d.account_id, s.expires_at, d.revoked_at FROM session s JOIN device d ON d.id = s.device_id
             WHERE s.token_hash = ?1",
            [hash(token)],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .optional()?;
    let Some((device_id, account_id, expires_at, revoked)) = row else { return Err(AuthError::BadSession) };
    if revoked.is_some() {
        return Err(AuthError::UnknownDevice);
    }
    if expires_at < now {
        return Err(AuthError::BadSession);
    }
    conn.execute("UPDATE session SET expires_at = ?2 WHERE token_hash = ?1", params![hash(token), now + ttl_ms])?;
    conn.execute("UPDATE device SET last_seen_at = ?2 WHERE id = ?1", params![device_id, now])?;
    Ok(Authed { account_id, device_id })
}

pub fn challenge(conn: &Connection, device_id: &str, now: i64) -> Result<String, AuthError> {
    let ok = conn
        .query_row("SELECT 1 FROM device WHERE id = ?1 AND revoked_at IS NULL", [device_id], |_| Ok(()))
        .optional()?
        .is_some();
    if !ok {
        return Err(AuthError::UnknownDevice);
    }
    conn.execute("DELETE FROM auth_nonce WHERE expires_at < ?1", [now])?;
    let nonce = random_token(24);
    conn.execute(
        "INSERT INTO auth_nonce (nonce, device_id, expires_at) VALUES (?1, ?2, ?3)",
        params![nonce, device_id, now + NONCE_TTL_MS],
    )?;
    Ok(nonce)
}

/// The bytes a device signs to renew its session.
pub fn challenge_message(nonce: &str, device_id: &str, instance_id: &str) -> String {
    format!("chorus-auth\n{nonce}\n{device_id}\n{instance_id}")
}

/// Verify a signed challenge and issue a new session. Accepts raw r‖s (WebCrypto) or DER
/// (Android Keystore) signatures, base64.
pub fn verify(
    conn: &Connection,
    device_id: &str,
    nonce: &str,
    signature_b64: &str,
    instance_id: &str,
    now: i64,
    ttl_ms: i64,
) -> Result<String, AuthError> {
    let found: Option<i64> = conn
        .query_row(
            "SELECT expires_at FROM auth_nonce WHERE nonce = ?1 AND device_id = ?2",
            params![nonce, device_id],
            |r| r.get(0),
        )
        .optional()?;
    conn.execute("DELETE FROM auth_nonce WHERE nonce = ?1", [nonce])?; // single use
    if found.is_none_or(|exp| exp < now) {
        return Err(AuthError::BadSignature);
    }
    let key: Option<String> = conn
        .query_row("SELECT public_key FROM device WHERE id = ?1 AND revoked_at IS NULL", [device_id], |r| r.get(0))
        .optional()?;
    let key = parse_key(&key.ok_or(AuthError::UnknownDevice)?)?;
    let sig_bytes = decode_b64(signature_b64).ok_or(AuthError::BadSignature)?;
    let sig = Signature::from_slice(&sig_bytes)
        .or_else(|_| Signature::from_der(&sig_bytes))
        .map_err(|_| AuthError::BadSignature)?;
    key.verify(challenge_message(nonce, device_id, instance_id).as_bytes(), &sig)
        .map_err(|_| AuthError::BadSignature)?;
    Ok(new_session(conn, device_id, now, ttl_ms)?)
}

pub fn revoke_device(conn: &Connection, device_id: &str, now: i64) -> anyhow::Result<()> {
    conn.execute("UPDATE device SET revoked_at = ?2 WHERE id = ?1", params![device_id, now])?;
    conn.execute("DELETE FROM session WHERE device_id = ?1", [device_id])?;
    Ok(())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use p256::ecdsa::SigningKey;
    use p256::ecdsa::signature::Signer;
    use p256::pkcs8::EncodePublicKey;

    pub fn key(seed: u8) -> (SigningKey, String) {
        let sk = SigningKey::from_slice(&[seed.max(1); 32]).unwrap();
        let der = sk.verifying_key().to_public_key_der().unwrap();
        (sk, base64::engine::general_purpose::STANDARD.encode(der.as_bytes()))
    }

    pub fn db() -> Connection {
        let mut c = crate::db::open_memory().unwrap();
        crate::db::migrate(&mut c).unwrap();
        c
    }

    #[test]
    fn enrol_system_then_device_then_renew_session() {
        let mut c = db();
        let now = 1_790_000_000_000;
        let code = create_invite(&c, InviteKind::System, None, "cli", INVITE_TTL_MS, 1, now).unwrap();
        let (sk, pk) = key(7);
        let dev = DeviceIn { name: "Pixel".into(), platform: "android".into(), public_key: pk };
        let acct = AccountIn { display_name: Some("The Stars".into()), handle: Some("stars".into()) };
        let e = redeem(&mut c, &code, &dev, Some(&acct), now, 1000).unwrap();
        // invite is single-use
        assert!(matches!(redeem(&mut c, &code, &dev, Some(&acct), now, 1000), Err(AuthError::BadInvite)));
        // first account is admin; internal space + channel created as ops
        let admin: bool = c.query_row("SELECT is_admin FROM account", [], |r| r.get(0)).unwrap();
        assert!(admin);
        let kinds: Vec<String> = c
            .prepare("SELECT kind FROM op ORDER BY seq")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        assert_eq!(kinds, ["space.create", "space.join", "channel.create"]);
        assert_eq!(ingest::scopes_of(&c, &e.account_id).unwrap().len(), 3);
        // session works, then expires
        assert_eq!(authenticate(&c, &e.session, now + 10, 1000).unwrap().device_id, e.device_id);
        assert!(authenticate(&c, &e.session, now + 5000, 1000).is_err());
        // renew by signing a challenge
        let nonce = challenge(&c, &e.device_id, now).unwrap();
        let sig: Signature = sk.sign(challenge_message(&nonce, &e.device_id, "inst").as_bytes());
        let b64 = base64::engine::general_purpose::STANDARD.encode(sig.to_bytes());
        let s2 = verify(&c, &e.device_id, &nonce, &b64, "inst", now, 1000).unwrap();
        assert!(authenticate(&c, &s2, now + 1, 1000).is_ok());
        // nonce is single use; wrong key fails
        assert!(verify(&c, &e.device_id, &nonce, &b64, "inst", now, 1000).is_err());
        let (other, _) = key(9);
        let n2 = challenge(&c, &e.device_id, now).unwrap();
        let bad: Signature = other.sign(challenge_message(&n2, &e.device_id, "inst").as_bytes());
        let bad = base64::engine::general_purpose::STANDARD.encode(bad.to_der().as_bytes());
        assert!(matches!(verify(&c, &e.device_id, &n2, &bad, "inst", now, 1000), Err(AuthError::BadSignature)));
        // a second device on the same account
        let dcode =
            create_invite(&c, InviteKind::Device, Some(&e.account_id), &e.device_id, INVITE_TTL_MS, 1, now).unwrap();
        let (_, pk2) = key(8);
        let e2 = redeem(
            &mut c,
            &dcode,
            &DeviceIn { name: "Desk".into(), platform: "web".into(), public_key: pk2 },
            None,
            now,
            1000,
        )
        .unwrap();
        assert_eq!(e2.account_id, e.account_id);
        // revoke
        revoke_device(&c, &e2.device_id, now).unwrap();
        assert!(authenticate(&c, &e2.session, now, 1000).is_err());
    }

    #[test]
    fn handles_are_unique_and_validated() {
        let mut c = db();
        let now = 1;
        let (_, pk) = key(3);
        let dev = DeviceIn { name: "x".into(), platform: "web".into(), public_key: pk };
        let a = AccountIn { display_name: None, handle: Some("kai".into()) };
        let c1 = create_invite(&c, InviteKind::Person, None, "cli", INVITE_TTL_MS, 5, now).unwrap();
        redeem(&mut c, &c1, &dev, Some(&a), now, 1000).unwrap();
        assert!(matches!(redeem(&mut c, &c1, &dev, Some(&a), now, 1000), Err(AuthError::HandleTaken)));
        let bad = AccountIn { display_name: None, handle: Some("Bad Handle!".into()) };
        assert!(matches!(redeem(&mut c, &c1, &dev, Some(&bad), now, 1000), Err(AuthError::BadRequest(_))));
        let badkey = DeviceIn { name: "x".into(), platform: "web".into(), public_key: "nope".into() };
        assert!(matches!(redeem(&mut c, &c1, &badkey, Some(&a), now, 1000), Err(AuthError::BadKey)));
    }
}
