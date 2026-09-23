//! Your data for scripts, dashboards and overlays (API.md §2.3, §3, §6; M10.2 + M2.7).
//!
//! - **API tokens**: `chorus_<random>`, stored hashed, scoped (`read:front`, `read:members`,
//!   `stream`). Created and revoked by a signed-in device; shown once.
//! - **Reads**: current front, switches, intervals and members of the token's own account.
//! - **Stream**: server-sent events for front changes of the token's own account, fed from the
//!   same place the sync fan-out is (so the stream never runs ahead of devices).
//!
//! Only the account's own data is ever exposed here. Followers see other accounts through the
//! follower view only (NOTIFICATIONS.md §5), never through tokens.

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::auth;

pub const SCOPES: &[&str] = &["read:front", "read:members", "stream"];

/// Who is calling: a device session (everything) or an API token (its scopes).
pub struct Principal {
    pub account_id: String,
    scopes: Option<Vec<String>>,
}

impl Principal {
    /// The account itself (server-internal use, e.g. building stream events).
    pub fn owner(account_id: &str) -> Principal {
        Principal { account_id: account_id.into(), scopes: None }
    }

    pub fn allows(&self, scope: &str) -> bool {
        self.scopes.as_ref().is_none_or(|s| s.iter().any(|x| x == scope))
    }

    /// A signed-in device rather than an API token (tokens can't manage tokens or webhooks).
    pub fn is_device(&self) -> bool {
        self.scopes.is_none()
    }

    /// A token's scopes (`None` for a device, which may do everything its account can).
    pub fn scopes(&self) -> Option<&[String]> {
        self.scopes.as_deref()
    }
}

#[derive(Debug, thiserror::Error)]
pub enum DataError {
    #[error("not signed in")]
    Unauthenticated,
    #[error("this token lacks the {0} scope")]
    Scope(&'static str),
    #[error("{0}")]
    Bad(String),
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl From<rusqlite::Error> for DataError {
    fn from(e: rusqlite::Error) -> Self {
        DataError::Internal(e.into())
    }
}

/// Resolve a bearer credential: an API token (`chorus_…`) or a device session.
pub fn principal(conn: &Connection, bearer: &str, now: i64, session_ttl: i64) -> Result<Principal, DataError> {
    if bearer.starts_with("chorus_") {
        let row: Option<(String, String)> = conn
            .query_row(
                "SELECT account_id, scopes FROM api_token WHERE token_hash = ?1 AND revoked_at IS NULL",
                [auth::hash(bearer)],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let (account_id, scopes) = row.ok_or(DataError::Unauthenticated)?;
        conn.execute("UPDATE api_token SET last_used_at = ?2 WHERE token_hash = ?1", params![auth::hash(bearer), now])?;
        let scopes: Vec<String> = serde_json::from_str(&scopes).unwrap_or_default();
        return Ok(Principal { account_id, scopes: Some(scopes) });
    }
    let who = auth::authenticate(conn, bearer, now, session_ttl).map_err(|_| DataError::Unauthenticated)?;
    Ok(Principal { account_id: who.account_id, scopes: None })
}

fn random_token() -> String {
    let bytes: [u8; 24] = rand::random();
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!("chorus_{hex}")
}

/// Create a token for a signed-in device's account. The secret is returned once.
pub fn create_token(
    conn: &Connection,
    p: &Principal,
    name: &str,
    scopes: &[String],
    now: i64,
) -> Result<Value, DataError> {
    if p.scopes.is_some() {
        return Err(DataError::Bad("tokens can't create tokens".into()));
    }
    if name.trim().is_empty() || scopes.is_empty() || scopes.iter().any(|s| !SCOPES.contains(&s.as_str())) {
        return Err(DataError::Bad(format!("a name and scopes from {SCOPES:?} are needed")));
    }
    let id = chorus_core::id::new_id(now as u64, rand::random());
    let token = random_token();
    conn.execute(
        "INSERT INTO api_token (id, account_id, name, token_hash, scopes, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            id,
            p.account_id,
            name.trim(),
            auth::hash(&token),
            serde_json::to_string(scopes).map_err(anyhow::Error::from)?,
            now
        ],
    )?;
    Ok(json!({"id": id, "token": token, "name": name.trim(), "scopes": scopes}))
}

pub fn list_tokens(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    let mut st = conn.prepare(
        "SELECT id, name, scopes, created_at, last_used_at FROM api_token
         WHERE account_id = ?1 AND revoked_at IS NULL ORDER BY created_at",
    )?;
    let rows = st.query_map([&p.account_id], |r| {
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "name": r.get::<_, String>(1)?,
            "scopes": serde_json::from_str::<Value>(&r.get::<_, String>(2)?).unwrap_or(json!([])),
            "created_at": r.get::<_, i64>(3)?,
            "last_used_at": r.get::<_, Option<i64>>(4)?,
        }))
    })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

pub fn revoke_token(conn: &Connection, p: &Principal, id: &str, now: i64) -> Result<(), DataError> {
    if p.scopes.is_some() {
        return Err(DataError::Bad("tokens can't revoke tokens".into()));
    }
    conn.execute(
        "UPDATE api_token SET revoked_at = ?3 WHERE id = ?1 AND account_id = ?2 AND revoked_at IS NULL",
        params![id, p.account_id, now],
    )?;
    Ok(())
}

// ─── reads ───────────────────────────────────────────────────────────────────

fn subject_name(conn: &Connection, t: &str, id: &str) -> rusqlite::Result<(Option<String>, Option<String>)> {
    let sql = match t {
        "member" => "SELECT COALESCE(display_name, name), color FROM member WHERE id = ?1",
        "group" => "SELECT name, color FROM member_group WHERE id = ?1",
        _ => "SELECT name, color FROM custom_state WHERE id = ?1",
    };
    Ok(conn.query_row(sql, [id], |r| Ok((r.get(0)?, r.get(1)?))).optional()?.unwrap_or((None, None)))
}

/// Who is here now, with names: `{front: [{name, subject_type, subject_id, level, is_primary, since}], since}`.
pub fn current_front(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    if !p.allows("read:front") {
        return Err(DataError::Scope("read:front"));
    }
    let mut st = conn.prepare_cached(
        "SELECT subject_type, subject_id, level, is_primary, start_at FROM front_interval
         WHERE account_id = ?1 AND end_at IS NULL ORDER BY position",
    )?;
    let rows: Vec<(String, String, String, bool, i64)> = st
        .query_map([&p.account_id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)))?
        .collect::<Result<_, _>>()?;
    let mut front = Vec::new();
    let mut since: Option<i64> = None;
    for (t, id, level, primary, start) in rows {
        let (name, color) = subject_name(conn, &t, &id)?;
        since = Some(since.map_or(start, |s: i64| s.min(start)));
        front.push(json!({"name": name, "color": color, "subject_type": t, "subject_id": id, "level": level, "is_primary": primary, "since": start}));
    }
    Ok(json!({"front": front, "since": since}))
}

/// Switch log, newest first: `?from=&to=&limit=` (ms; limit ≤ 1000).
pub fn switches(
    conn: &Connection,
    p: &Principal,
    from: Option<i64>,
    to: Option<i64>,
    limit: Option<i64>,
) -> Result<Value, DataError> {
    if !p.allows("read:front") {
        return Err(DataError::Scope("read:front"));
    }
    let mut st = conn.prepare_cached(
        "SELECT id, kind, occurred_at, tz_offset_min, entries, resulting_front, note, retracted FROM switch
         WHERE account_id = ?1 AND occurred_at >= ?2 AND occurred_at < ?3 ORDER BY occurred_at DESC, id DESC LIMIT ?4",
    )?;
    let rows = st.query_map(
        params![p.account_id, from.unwrap_or(i64::MIN), to.unwrap_or(i64::MAX), limit.unwrap_or(200).clamp(1, 1000)],
        |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "kind": r.get::<_, String>(1)?,
                "occurred_at": r.get::<_, i64>(2)?,
                "tz_offset_min": r.get::<_, i64>(3)?,
                "entries": serde_json::from_str::<Value>(&r.get::<_, String>(4)?).unwrap_or(json!([])),
                "resulting_front": serde_json::from_str::<Value>(&r.get::<_, String>(5)?).unwrap_or(json!([])),
                "note": r.get::<_, Option<String>>(6)?,
                "retracted": r.get::<_, bool>(7)?,
            }))
        },
    )?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

/// Front intervals overlapping `[from, to)`: the table graphs are made from (DATA_MODEL §4).
pub fn intervals(conn: &Connection, p: &Principal, from: Option<i64>, to: Option<i64>) -> Result<Value, DataError> {
    if !p.allows("read:front") {
        return Err(DataError::Scope("read:front"));
    }
    let mut st = conn.prepare_cached(
        "SELECT subject_type, subject_id, level, is_primary, start_at, end_at FROM front_interval
         WHERE account_id = ?1 AND start_at < ?3 AND (end_at IS NULL OR end_at > ?2) ORDER BY start_at, subject_id",
    )?;
    let rows = st.query_map(params![p.account_id, from.unwrap_or(i64::MIN), to.unwrap_or(i64::MAX)], |r| {
        Ok(json!({
            "subject_type": r.get::<_, String>(0)?,
            "subject_id": r.get::<_, String>(1)?,
            "level": r.get::<_, String>(2)?,
            "is_primary": r.get::<_, bool>(3)?,
            "start_at": r.get::<_, i64>(4)?,
            "end_at": r.get::<_, Option<i64>>(5)?,
        }))
    })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

/// One member in the `/members` shape (webhook payloads).
pub fn member(conn: &Connection, account_id: &str, id: &str) -> rusqlite::Result<Option<Value>> {
    conn.query_row(
        "SELECT id, name, display_name, pronouns, color, sigils, archived_at, deleted_at FROM member
         WHERE id = ?1 AND account_id = ?2",
        [id, account_id],
        |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "name": r.get::<_, Option<String>>(1)?,
                "display_name": r.get::<_, Option<String>>(2)?,
                "pronouns": r.get::<_, Option<String>>(3)?,
                "color": r.get::<_, Option<String>>(4)?,
                "sigils": serde_json::from_str::<Value>(&r.get::<_, String>(5)?).unwrap_or(json!([])),
                "archived": r.get::<_, Option<i64>>(6)?.is_some(),
                "deleted": r.get::<_, Option<i64>>(7)?.is_some(),
            }))
        },
    )
    .optional()
}

pub fn members(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    if !p.allows("read:members") {
        return Err(DataError::Scope("read:members"));
    }
    let mut st = conn.prepare_cached(
        "SELECT id, name, display_name, pronouns, color, sigils, archived_at FROM member
         WHERE account_id = ?1 AND deleted_at IS NULL ORDER BY name",
    )?;
    let rows = st.query_map([&p.account_id], |r| {
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "name": r.get::<_, Option<String>>(1)?,
            "display_name": r.get::<_, Option<String>>(2)?,
            "pronouns": r.get::<_, Option<String>>(3)?,
            "color": r.get::<_, Option<String>>(4)?,
            "sigils": serde_json::from_str::<Value>(&r.get::<_, String>(5)?).unwrap_or(json!([])),
            "archived": r.get::<_, Option<i64>>(6)?.is_some(),
        }))
    })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}
