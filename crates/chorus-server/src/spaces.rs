//! Shared spaces and DMs between accounts (M6.2, SPEC §5, D-006).
//!
//! A new `space:` scope has nobody with access yet, so creating one goes through the server:
//! `POST /spaces` grants the creator, then writes ordinary ops as the creator (`space.create`,
//! `space.join`, a first channel, and a `space.join` per invited account). Joins by the space's
//! owner grant access (`project::space_access`), so invited devices get the new scope live.
//!
//! - You can only bring in accounts you're connected to: an active follow in either direction.
//! - A DM is a `dm` space with exactly two accounts; asking again returns the same one.
//! - Leaving writes `space.leave` for yourself; the owner of a shared space can't leave it.
//! - Other accounts' members appear in shared chat through **author cards**: the name, colour,
//!   pronouns, sigils and avatar of members who wrote a message everyone in the space can read
//!   (`visibility` unset). Anything else about them stays in their account (SYNC.md §4.3).

use chorus_core::op::Op;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::ingest;

#[derive(Debug, thiserror::Error)]
pub enum SpaceError {
    #[error("{0}")]
    Bad(String),
    #[error("no such space")]
    NotFound,
    #[error("{0}")]
    Forbidden(String),
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl From<rusqlite::Error> for SpaceError {
    fn from(e: rusqlite::Error) -> Self {
        SpaceError::Internal(e.into())
    }
}

/// Is there an active follow between the two accounts, either way?
pub fn connected(conn: &Connection, a: &str, b: &str) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM follow WHERE status = 'active'
           AND ((follower_account_id = ?1 AND target_account_id = ?2)
             OR (follower_account_id = ?2 AND target_account_id = ?1)))",
        [a, b],
        |r| r.get(0),
    )
}

fn check_invitees(conn: &Connection, me: &str, accounts: &[String]) -> Result<(), SpaceError> {
    for a in accounts {
        if a == me {
            return Err(SpaceError::Bad("you're already in it".into()));
        }
        let exists: bool = conn.query_row("SELECT EXISTS (SELECT 1 FROM account WHERE id = ?1)", [a], |r| r.get(0))?;
        if !exists || !connected(conn, me, a)? {
            return Err(SpaceError::Forbidden("you can only add people you follow or who follow you".into()));
        }
    }
    Ok(())
}

fn op(
    conn: &Connection,
    account: &str,
    kind: &str,
    space: &str,
    entity: &str,
    payload: Value,
    now: i64,
) -> anyhow::Result<Op> {
    match ingest::op_as(
        conn,
        account,
        ingest::SERVER_DEVICE,
        kind,
        &format!("space:{space}"),
        Some(entity),
        payload,
        now,
        None,
    )? {
        (_, Some(o)) => Ok(o),
        (r, None) => anyhow::bail!("{kind} rejected: {:?}", r.error),
    }
}

/// The DM between two accounts, if there is one they are both still in.
fn existing_dm(conn: &Connection, a: &str, b: &str) -> rusqlite::Result<Option<String>> {
    conn.query_row(
        "SELECT s.id FROM space s
         JOIN space_member x ON x.space_id = s.id AND x.account_id = ?1 AND x.is_present
         JOIN space_member y ON y.space_id = s.id AND y.account_id = ?2 AND y.is_present
         WHERE s.kind = 'dm' AND s.deleted_at IS NULL ORDER BY s.created_at LIMIT 1",
        [a, b],
        |r| r.get(0),
    )
    .optional()
}

/// Create a shared space (named, any connected accounts) or a DM (exactly one other account).
/// Returns the space id and the ops to fan out (none when the DM already existed).
pub fn create(
    conn: &Connection,
    me: &str,
    kind: &str,
    name: Option<&str>,
    accounts: &[String],
    now: i64,
) -> Result<(String, Vec<Op>), SpaceError> {
    let mut accounts = accounts.to_vec();
    accounts.sort();
    accounts.dedup();
    let name = name.map(str::trim).filter(|n| !n.is_empty());
    match kind {
        "dm" => {
            if accounts.len() != 1 {
                return Err(SpaceError::Bad("a DM is with exactly one other account".into()));
            }
        }
        "shared" => {
            if name.is_none_or(|n| n.chars().count() > 80) {
                return Err(SpaceError::Bad("a shared space needs a name (up to 80 characters)".into()));
            }
        }
        _ => return Err(SpaceError::Bad("kind must be shared or dm".into())),
    }
    check_invitees(conn, me, &accounts)?;
    if kind == "dm"
        && let Some(id) = existing_dm(conn, me, &accounts[0])?
    {
        return Ok((id, Vec::new()));
    }
    let space = chorus_core::id::new_id(now as u64, rand::random());
    ingest::grant(conn, me, &format!("space:{space}"))?;
    let mut ops = vec![
        op(conn, me, "space.create", &space, &space, json!({"kind": kind, "name": name}), now)?,
        op(conn, me, "space.join", &space, &space, json!({"account_id": me}), now)?,
    ];
    let chan = chorus_core::id::new_id(now as u64, rand::random());
    let chan_name = if kind == "dm" { "dm" } else { "general" };
    ops.push(op(
        conn,
        me,
        "channel.create",
        &space,
        &chan,
        json!({"space_id": space, "kind": "text", "name": chan_name}),
        now,
    )?);
    for a in &accounts {
        ops.push(op(conn, me, "space.join", &space, &space, json!({"account_id": a}), now)?);
    }
    Ok((space, ops))
}

fn space_row(conn: &Connection, space: &str) -> Result<(String, String), SpaceError> {
    conn.query_row("SELECT kind, owner_account_id FROM space WHERE id = ?1 AND deleted_at IS NULL", [space], |r| {
        Ok((r.get::<_, Option<String>>(0)?.unwrap_or_default(), r.get(1)?))
    })
    .optional()?
    .ok_or(SpaceError::NotFound)
}

fn present(conn: &Connection, space: &str, account: &str) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM space_member WHERE space_id = ?1 AND account_id = ?2 AND is_present)",
        [space, account],
        |r| r.get(0),
    )
}

/// Bring more connected accounts into a shared space (its owner only).
pub fn add(conn: &Connection, me: &str, space: &str, accounts: &[String], now: i64) -> Result<Vec<Op>, SpaceError> {
    let (kind, owner) = space_row(conn, space)?;
    if !present(conn, space, me)? {
        return Err(SpaceError::NotFound); // outsiders don't learn it exists
    }
    if kind != "shared" {
        return Err(SpaceError::Bad("people can only be added to shared spaces".into()));
    }
    if owner != me {
        return Err(SpaceError::Forbidden("only the space's owner can add people".into()));
    }
    check_invitees(conn, me, accounts)?;
    let mut ops = Vec::new();
    for a in accounts {
        if !present(conn, space, a)? {
            ops.push(op(conn, me, "space.join", space, space, json!({"account_id": a}), now)?);
        }
    }
    Ok(ops)
}

/// Leave a shared space or DM. The owner of a shared space can't (they would strand it).
pub fn leave(conn: &Connection, me: &str, space: &str, now: i64) -> Result<Vec<Op>, SpaceError> {
    let (kind, owner) = space_row(conn, space)?;
    if !present(conn, space, me)? {
        return Err(SpaceError::NotFound);
    }
    match kind.as_str() {
        "internal" => Err(SpaceError::Bad("you can't leave your own home space".into())),
        "shared" if owner == me => Err(SpaceError::Bad("the owner can't leave a shared space".into())),
        _ => Ok(vec![op(conn, me, "space.leave", space, space, json!({"account_id": me}), now)?]),
    }
}

/// The spaces an account is in, with the other accounts in each (for names and DM titles).
pub fn list(conn: &Connection, me: &str) -> Result<Value, SpaceError> {
    let mut st = conn.prepare(
        "SELECT s.id, s.kind, s.name, s.owner_account_id FROM space s
         JOIN space_member m ON m.space_id = s.id AND m.account_id = ?1 AND m.is_present
         WHERE s.deleted_at IS NULL ORDER BY s.kind = 'internal' DESC, s.kind, s.created_at",
    )?;
    let rows: Vec<(String, Option<String>, Option<String>, String)> =
        st.query_map([me], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?.collect::<Result<_, _>>()?;
    let mut items = Vec::new();
    for (id, kind, name, owner) in rows {
        items.push(json!({"id": id, "kind": kind, "name": name, "owner_account_id": owner, "accounts": accounts_in(conn, &id)?}));
    }
    Ok(json!({"items": items}))
}

fn accounts_in(conn: &Connection, space: &str) -> rusqlite::Result<Vec<Value>> {
    let mut st = conn.prepare_cached(
        "SELECT a.id, a.handle, a.display_name, a.kind FROM space_member m JOIN account a ON a.id = m.account_id
         WHERE m.space_id = ?1 AND m.is_present ORDER BY a.display_name, a.handle",
    )?;
    st.query_map([space], |r| {
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "handle": r.get::<_, Option<String>>(1)?,
            "display_name": r.get::<_, Option<String>>(2)?,
            "kind": r.get::<_, String>(3)?,
        }))
    })?
    .collect()
}

/// Author cards for a space the reader is in: its accounts, and the members of *other* accounts
/// who wrote a message everyone in the space can read.
const PUBLIC_AUTHOR_SOURCE: &str = "FROM channel c
 JOIN message m ON m.channel_id = c.id AND m.deleted_at IS NULL AND m.visibility IS NULL
 JOIN (SELECT message_id, member_id FROM message_author
       UNION SELECT message_id, member_id FROM message_segment_author) au ON au.message_id = m.id
 JOIN member mb ON mb.id = au.member_id AND mb.deleted_at IS NULL
 WHERE c.deleted_at IS NULL";

/// Match the author-card rule when deciding whether a reader may fetch another account's avatar.
pub fn author_avatar_visible(conn: &Connection, viewer: &str, hash: &str) -> rusqlite::Result<bool> {
    conn.query_row(
        &format!(
            "SELECT EXISTS(SELECT 1 {PUBLIC_AUTHOR_SOURCE}
          AND mb.avatar_blob = ?1 AND mb.account_id != ?2
          AND EXISTS (SELECT 1 FROM scope_access sa
                      WHERE sa.scope = 'space:' || c.space_id AND sa.account_id = ?2))"
        ),
        params![hash, viewer],
        |r| r.get(0),
    )
}

pub fn authors(conn: &Connection, me: &str, space: &str) -> Result<Value, SpaceError> {
    if !ingest::can_access(conn, me, &format!("space:{space}"))? {
        return Err(SpaceError::NotFound);
    }
    let mut st = conn.prepare_cached(&format!(
        "SELECT DISTINCT mb.id, mb.account_id, mb.name, mb.display_name, mb.pronouns, mb.color, mb.sigils, mb.avatar_blob
         {PUBLIC_AUTHOR_SOURCE} AND c.space_id = ?1 AND mb.account_id != ?2"
    ))?;
    let members: Vec<Value> = st
        .query_map([space, me], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "account_id": r.get::<_, String>(1)?,
                "name": r.get::<_, Option<String>>(2)?,
                "display_name": r.get::<_, Option<String>>(3)?,
                "pronouns": r.get::<_, Option<String>>(4)?,
                "color": r.get::<_, Option<String>>(5)?,
                "sigils": serde_json::from_str::<Value>(&r.get::<_, String>(6)?).unwrap_or(json!([])),
                "avatar_blob": r.get::<_, Option<String>>(7)?,
            }))
        })?
        .collect::<Result<_, _>>()?;
    Ok(json!({"accounts": accounts_in(conn, space)?, "members": members}))
}
