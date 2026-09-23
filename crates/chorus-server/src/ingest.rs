//! Accepting ops (docs/SYNC.md §6.3). Same semantics as `chorus_core::sync::MemServer::accept`,
//! which the convergence simulator verifies; keep the two in step.

use chorus_core::hlc::{Hlc, HlcClock};
use chorus_core::op::{self, Op, Scope};
use chorus_core::restore;
use chorus_core::sync::AckResult;
use chorus_core::time::{self, ClockSample, TimeSource};
use rusqlite::{Connection, OptionalExtension, params};

use crate::{db, oplog, project};

/// Who is pushing, and their connection's clock sample.
#[derive(Clone, Debug)]
pub struct Session {
    pub account_id: String,
    pub device_id: String,
    pub sample: ClockSample,
}

pub fn can_access(conn: &Connection, account: &str, scope: &str) -> anyhow::Result<bool> {
    if scope == "server" {
        return Ok(true); // everyone reads the server scope (custom emoji)
    }
    Ok(conn
        .query_row("SELECT 1 FROM scope_access WHERE account_id = ?1 AND scope = ?2", params![account, scope], |_| {
            Ok(())
        })
        .optional()?
        .is_some())
}

fn can_write(conn: &Connection, account: &str, scope: &str) -> anyhow::Result<bool> {
    if scope == "server" {
        let admin: bool = conn
            .query_row("SELECT is_admin FROM account WHERE id = ?1", [account], |r| r.get(0))
            .optional()?
            .unwrap_or(false);
        return Ok(admin);
    }
    can_access(conn, account, scope)
}

pub fn scopes_of(conn: &Connection, account: &str) -> anyhow::Result<Vec<String>> {
    let mut st = conn.prepare_cached("SELECT scope FROM scope_access WHERE account_id = ?1 ORDER BY scope")?;
    let mut v: Vec<String> = st.query_map([account], |r| r.get(0))?.collect::<Result<_, _>>()?;
    v.push("server".into());
    Ok(v)
}

pub fn grant(conn: &Connection, account: &str, scope: &str) -> anyhow::Result<()> {
    conn.execute("INSERT OR IGNORE INTO scope_access(account_id, scope) VALUES (?1, ?2)", params![account, scope])?;
    Ok(())
}

pub fn restore_open(conn: &Connection) -> anyhow::Result<bool> {
    Ok(db::meta(conn, "restore_open")?.as_deref() == Some("1"))
}

/// Accept one op inside the caller's transaction. Idempotent by id.
pub fn accept(
    conn: &Connection,
    s: &Session,
    mut o: Op,
    now: i64,
    restore: bool,
) -> anyhow::Result<(AckResult, Option<Op>)> {
    if let Some(existing) = oplog::by_id(conn, &o.id)? {
        return Ok((AckResult::ok(&existing), None));
    }
    if let Err(e) = op::validate(&o) {
        return Ok((AckResult::err(o.id, e.code(), e.to_string(), false), None));
    }
    let preserved = restore && restore_open(conn)? && o.account_id.is_some() && o.occurred_at.is_some();
    let author = if preserved { o.account_id.clone().unwrap_or_default() } else { s.account_id.clone() };
    let allowed = Scope::parse(&o.scope).is_some()
        && can_write(conn, &author, &o.scope)?
        && can_access(conn, &s.account_id, &o.scope)?;
    if !allowed {
        return Ok((AckResult::err(o.id, "forbidden", format!("{} not writable", o.scope), false), None));
    }
    // Follow requests and a follower's prefs are written by the server on the follower's behalf
    // (follows.rs); a client forging one could make someone else receive its switches.
    if !preserved && matches!(o.kind.as_str(), "follow.request" | "follow.set_prefs") && s.device_id != SERVER_DEVICE {
        return Ok((
            AckResult::err(o.id, "forbidden", format!("{} goes through /api/v1/follows", o.kind), false),
            None,
        ));
    }
    if !preserved && o.kind.ends_with(".restore") {
        let related = oplog::for_entity(conn, o.entity().unwrap_or_default())?;
        if !restore::allowed(&o.kind, &o.scope, o.entity().unwrap_or_default(), &author, related.iter()) {
            return Ok((
                AckResult::err(
                    o.id,
                    "forbidden",
                    "only the creator or deleting account may restore this item".into(),
                    false,
                ),
                None,
            ));
        }
    }
    // The SQL partial unique index is the last line of defense. Return a normal rejected ack
    // before inserting the op so concurrent admin edits cannot abort the ingest transaction.
    if matches!(o.kind.as_str(), "emoji.create" | "emoji.set" | "emoji.restore") {
        let candidate = if let Some(name) = o.payload.get("name").and_then(serde_json::Value::as_str) {
            Some(name.to_string())
        } else if o.kind == "emoji.restore" {
            conn.query_row("SELECT name FROM custom_emoji WHERE id = ?1", [o.entity().unwrap_or_default()], |r| {
                r.get(0)
            })
            .optional()?
        } else {
            None
        };
        if let Some(name) = candidate {
            let mut stmt = conn
                .prepare_cached("SELECT id, name, deleted_at IS NOT NULL FROM custom_emoji WHERE name IS NOT NULL")?;
            let rows: Vec<(String, String, bool)> =
                stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect::<Result<_, _>>()?;
            if !chorus_core::emoji::name_available(
                &name,
                o.entity().unwrap_or_default(),
                rows.iter().map(|r| (r.0.as_str(), r.1.as_str(), r.2)),
            ) {
                return Ok((
                    AckResult::err(o.id, "conflict", format!("emoji name :{name}: is already in use"), false),
                    None,
                ));
            }
        }
    }
    let mut suspect = false;
    if !preserved {
        let t = time::OpTime {
            device_at: o.device_at,
            mono: o.mono,
            boot_id: o.boot_id.clone(),
            time_source: o.time_source,
        };
        let c = time::correct(&t, &s.sample, now);
        suspect = c.suspect;
        o.occurred_at = Some(c.occurred_at);
        o.account_id = Some(s.account_id.clone());
        o.device_id = Some(s.device_id.clone());
        o.received_at = Some(now);
    }
    let seq = oplog::insert(conn, &o, suspect, preserved)?;
    o.seq = Some(seq);
    project::after_insert(conn, &o)?;
    // follower notifications are queued from live ingest only (never from a rebuild or a restore)
    if !preserved
        && o.kind.starts_with("front.")
        && let Some(account) = o.scope.strip_prefix("account:")
    {
        crate::notifier::on_front_change(conn, account, now)?;
    }
    Ok((AckResult::ok(&o), Some(o)))
}

/// The server's own HLC, persisted in `server_meta`.
fn server_hlc(conn: &Connection, now: i64) -> anyhow::Result<Hlc> {
    let last: Option<Hlc> = db::meta(conn, "hlc_last")?.and_then(|s| s.parse().ok());
    let mut clock = match last {
        Some(h) => HlcClock::resume(SERVER_NODE, h),
        None => HlcClock::new(SERVER_NODE),
    };
    let h = clock.tick(now as u64);
    db::set_meta(conn, "hlc_last", &h.to_string())?;
    Ok(h)
}

/// HLC node id used by server-authored ops.
pub const SERVER_NODE: u32 = 0xffff_fffe;
pub const SERVER_DEVICE: &str = "server";

/// Create and accept an op authored by the server on behalf of `account` (e.g. a new account's
/// internal space).
pub fn server_op(
    conn: &Connection,
    account: &str,
    kind: &str,
    scope: &str,
    entity: Option<&str>,
    payload: serde_json::Value,
    now: i64,
) -> anyhow::Result<Op> {
    let hlc = server_hlc(conn, now)?;
    let o = Op {
        id: chorus_core::id::new_id(now as u64, rand::random()),
        kind: kind.into(),
        v: op::CURRENT_V,
        scope: scope.into(),
        entity_id: entity.map(str::to_string),
        hlc,
        device_at: now,
        tz_offset_min: 0,
        mono: None,
        boot_id: None,
        time_source: TimeSource::Auto,
        seen_seq: 0,
        member_id: None,
        payload,
        seq: None,
        account_id: None,
        device_id: None,
        occurred_at: None,
        received_at: None,
    };
    let s = Session {
        account_id: account.into(),
        device_id: SERVER_DEVICE.into(),
        sample: ClockSample { server_time: now, mono: None, boot_id: None, offset_ms: 0 },
    };
    match accept(conn, &s, o, now, false)? {
        (_, Some(o)) => Ok(o),
        (r, None) => anyhow::bail!("server op {kind} rejected: {:?}", r.error),
    }
}
