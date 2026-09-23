//! Follows between accounts (SPEC §3, NOTIFICATIONS.md §3–§4, API.md §3).
//!
//! A follow lives in the **target's** `account:` scope (SYNC.md §3), so the target's devices see
//! requests and set the ceiling with ordinary ops. The follower can't write there, so the server
//! writes `follow.request`, `follow.set_prefs` and the follower's `follow.end` on their behalf
//! (`ingest` refuses those kinds from clients). Followers never get the target's ops; they read
//! their follows through this module and, later, follower views.

use chorus_core::notify::Prefs;
use chorus_core::op::Op;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::ingest;

#[derive(Debug, thiserror::Error)]
pub enum FollowError {
    #[error("no account with that handle")]
    NoSuchAccount,
    #[error("you can't follow yourself")]
    SelfFollow,
    #[error("no such follow")]
    NotFound,
    #[error("only the follower can change these preferences")]
    NotFollower,
    #[error("invalid preferences: {0}")]
    BadPrefs(String),
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl From<rusqlite::Error> for FollowError {
    fn from(e: rusqlite::Error) -> Self {
        FollowError::Internal(e.into())
    }
}

struct Row {
    follower: String,
    target: String,
    status: String,
}

fn row(conn: &Connection, id: &str) -> Result<Row, FollowError> {
    conn.query_row(
        "SELECT follower_account_id, target_account_id, COALESCE(status, '') FROM follow WHERE id = ?1",
        [id],
        |r| Ok(Row { follower: r.get(0)?, target: r.get(1)?, status: r.get(2)? }),
    )
    .optional()?
    .ok_or(FollowError::NotFound)
}

/// Find an account by handle (case-insensitive, optional leading `@`) or by id.
pub fn find_account(conn: &Connection, who: &str) -> Result<String, FollowError> {
    let who = who.trim().trim_start_matches('@');
    conn.query_row("SELECT id FROM account WHERE lower(handle) = lower(?1) OR id = ?1", [who], |r| {
        r.get::<_, String>(0)
    })
    .optional()?
    .ok_or(FollowError::NoSuchAccount)
}

/// Ask to follow `target`. Idempotent while a request or follow is open. → (follow id, new op).
pub fn request(
    conn: &Connection,
    follower: &str,
    target_ref: &str,
    now: i64,
) -> Result<(String, Option<Op>), FollowError> {
    let target = find_account(conn, target_ref)?;
    if target == follower {
        return Err(FollowError::SelfFollow);
    }
    let open: Option<String> = conn
        .query_row(
            "SELECT id FROM follow WHERE follower_account_id = ?1 AND target_account_id = ?2
             AND status IN ('requested', 'active') ORDER BY created_at DESC LIMIT 1",
            params![follower, target],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(id) = open {
        return Ok((id, None));
    }
    let id = chorus_core::id::new_id(now as u64, rand::random());
    let o = ingest::server_op(
        conn,
        &target,
        "follow.request",
        &format!("account:{target}"),
        Some(&id),
        json!({"follower_account_id": follower, "target_account_id": target}),
        now,
    )?;
    Ok((id, Some(o)))
}

/// The follower's own notification preferences for this follow (NOTIFICATIONS.md §4).
pub fn set_prefs(conn: &Connection, follower: &str, id: &str, prefs: Value, now: i64) -> Result<Op, FollowError> {
    let r = row(conn, id)?;
    if r.follower != follower {
        return Err(FollowError::NotFollower);
    }
    serde_json::from_value::<Prefs>(prefs.clone()).map_err(|e| FollowError::BadPrefs(e.to_string()))?;
    Ok(ingest::server_op(
        conn,
        &r.target,
        "follow.set_prefs",
        &format!("account:{}", r.target),
        Some(id),
        json!({"prefs": prefs}),
        now,
    )?)
}

/// Unfollow (the follower) — the target ends follows with its own `follow.end` op.
pub fn end(conn: &Connection, account: &str, id: &str, now: i64) -> Result<Option<Op>, FollowError> {
    let r = row(conn, id)?;
    if r.follower != account && r.target != account {
        return Err(FollowError::NotFound);
    }
    if r.status == "ended" {
        return Ok(None);
    }
    Ok(Some(ingest::server_op(
        conn,
        &r.target,
        "follow.end",
        &format!("account:{}", r.target),
        Some(id),
        json!({"by_account_id": account}),
        now,
    )?))
}

/// Who `account` follows (with status and its own prefs) and who follows it.
pub fn list(conn: &Connection, account: &str) -> Result<Value, FollowError> {
    let query = |sql: &str| -> Result<Vec<Value>, FollowError> {
        let mut st = conn.prepare(sql)?;
        let rows = st.query_map([account], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "account": {
                    "id": r.get::<_, String>(1)?,
                    "handle": r.get::<_, Option<String>>(2)?,
                    "display_name": r.get::<_, Option<String>>(3)?,
                    "kind": r.get::<_, String>(4)?,
                },
                "status": r.get::<_, Option<String>>(5)?,
                "prefs": serde_json::from_str::<Value>(&r.get::<_, String>(6)?).unwrap_or(json!({})),
                "created_at": r.get::<_, i64>(7)?,
            }))
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    };
    let following = query(
        "SELECT f.id, a.id, a.handle, a.display_name, a.kind, f.status, f.prefs, f.created_at
         FROM follow f JOIN account a ON a.id = f.target_account_id
         WHERE f.follower_account_id = ?1 AND f.status IN ('requested', 'active') ORDER BY f.created_at",
    )?;
    let followers = query(
        "SELECT f.id, a.id, a.handle, a.display_name, a.kind, f.status, f.prefs, f.created_at
         FROM follow f JOIN account a ON a.id = f.follower_account_id
         WHERE f.target_account_id = ?1 AND f.status IN ('requested', 'active') ORDER BY f.created_at",
    )?;
    Ok(json!({"following": following, "followers": followers}))
}
