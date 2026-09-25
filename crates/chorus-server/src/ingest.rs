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
        .prepare_cached("SELECT 1 FROM scope_access WHERE account_id = ?1 AND scope = ?2")?
        .query_row(params![account, scope], |_| Ok(()))
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

/// Whether restore pushes keep their original authorship (reconcile.rs).
pub fn restore_open(conn: &Connection, now: i64) -> anyhow::Result<bool> {
    crate::reconcile::open(conn, now)
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
    // restore pushes re-send history, which only has to be what was valid when it was written
    let checked = if restore { op::validate(&o) } else { op::validate_new(&o) };
    if let Err(e) = checked {
        return Ok((AckResult::err(o.id, e.code(), e.to_string(), false), None));
    }
    let preserved = restore && restore_open(conn, now)? && o.account_id.is_some() && o.occurred_at.is_some();
    let author = if preserved { o.account_id.clone().unwrap_or_default() } else { s.account_id.clone() };
    let allowed = Scope::parse(&o.scope).is_some()
        && can_write(conn, &author, &o.scope)?
        && can_access(conn, &s.account_id, &o.scope)?;
    if !allowed {
        return Ok((AckResult::err(o.id, "forbidden", format!("{} not writable", o.scope), false), None));
    }
    if !preserved && !crate::visibility::related_write_allowed(conn, &author, &o)? {
        return Ok((AckResult::err(o.id, "forbidden", "message is private to another account".into(), false), None));
    }
    // speaking as someone else's member (impersonation in a shared space)
    if !preserved && let Some(why) = foreign_item(conn, &author, &o)? {
        return Ok((AckResult::err(o.id, "forbidden", why, false), None));
    }
    if !preserved && let Some(why) = foreign_speaker(conn, &author, &o)? {
        return Ok((AckResult::err(o.id, "forbidden", why, false), None));
    }
    // channel permissions (perms.rs, D-047)
    if !preserved && let Some(why) = crate::perms::write_denied(conn, &author, &o)? {
        return Ok((AckResult::err(o.id, "forbidden", why, false), None));
    }
    // slow mode (perms.rs, R22.7 default): on when the server receives it, not when it was written
    if !preserved && let Some(why) = crate::perms::slow_mode(conn, &author, &o, now)? {
        return Ok((AckResult::err(o.id, "slow_mode", why, false), None));
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
    // Storing and projecting run in a savepoint: an op the projection can't apply (a payload of a
    // shape `op::validate` let through) is refused on its own. Failing here would roll back the
    // whole pushed batch, and the device would resend it forever, its outbox stuck behind one op.
    conn.execute_batch("SAVEPOINT accept_op")?;
    match store_and_project(conn, &mut o, suspect, preserved, now) {
        Ok(()) => {
            conn.execute_batch("RELEASE accept_op")?;
            Ok((AckResult::ok(&o), Some(o)))
        }
        Err(e) if !is_transient(&e) => {
            conn.execute_batch("ROLLBACK TO accept_op; RELEASE accept_op")?;
            tracing::warn!(op = %o.id, kind = %o.kind, error = %format!("{e:#}"), "ingest: op refused, the projection can't apply it");
            Ok((AckResult::err(o.id, "unprocessable", format!("{} can't be applied: {e:#}", o.kind), false), None))
        }
        Err(e) => {
            conn.execute_batch("ROLLBACK TO accept_op; RELEASE accept_op")?;
            Err(e)
        }
    }
}

fn store_and_project(conn: &Connection, o: &mut Op, suspect: bool, preserved: bool, now: i64) -> anyhow::Result<()> {
    let seq = oplog::insert(conn, o, suspect, preserved)?;
    o.seq = Some(seq);
    project::after_insert(conn, o)?;
    // follower notifications are queued from live ingest only (never from a rebuild or a restore)
    if !preserved
        && o.kind.starts_with("front.")
        && let Some(account) = o.scope.strip_prefix("account:")
    {
        crate::notifier::on_front_change(conn, account, now)?;
    }
    if !preserved {
        crate::activity::on_op(conn, o, now)?;
    }
    Ok(())
}

/// A message or attachment belongs to the account that created it: another account can't
/// change an attachment's fields (alt text, spoiler) or send a second create for an existing id,
/// which would overwrite its text or file. (Moderation of messages goes through the channel
/// rules in `perms.rs`: delete, restore and pin.)
fn foreign_item(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<Option<String>> {
    let Some(spec) = op::spec(&o.kind) else { return Ok(None) };
    let table = match (spec.table, spec.action) {
        ("message" | "attachment", op::Action::Append) | ("attachment", op::Action::Set) => spec.table,
        _ => return Ok(None),
    };
    let Some(id) = o.entity() else { return Ok(None) };
    let owner: Option<Option<String>> = conn
        .prepare_cached(&format!("SELECT account_id FROM {table} WHERE id = ?1"))?
        .query_row([id], |r| r.get(0))
        .optional()?;
    Ok(owner.flatten().filter(|a| a != author).map(|_| format!("that {table} belongs to another account")))
}

/// Messages, reactions and posts speak as members (`authors`, each segment's `authors`, a
/// reaction's `member_id`, the envelope's `member_id`): those must be the author account's own.
/// An id the server doesn't know yet passes (a member created offline, still on its way); one
/// that belongs to another account is refused, or anyone in a shared space could post as someone
/// else's member.
fn foreign_speaker(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<Option<String>> {
    let k = o.kind.as_str();
    if !(k.starts_with("message.") || k.starts_with("reaction.") || k.starts_with("post.")) {
        return Ok(None);
    }
    let p = &o.payload;
    let ids = |v: Option<&serde_json::Value>| -> Vec<String> {
        v.and_then(serde_json::Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|x| x.as_str().map(str::to_string))
            .collect()
    };
    let mut named: Vec<String> = ids(p.get("authors"));
    for seg in p.get("segments").and_then(serde_json::Value::as_array).into_iter().flatten() {
        named.extend(ids(seg.get("authors")));
    }
    named.extend(p.get("member_id").and_then(serde_json::Value::as_str).map(str::to_string));
    named.extend(o.member_id.clone());
    let mut st = conn.prepare_cached("SELECT account_id FROM member WHERE id = ?1")?;
    for id in named {
        let owner: Option<Option<String>> = st.query_row([&id], |r| r.get(0)).optional()?;
        if owner.flatten().is_some_and(|a| a != author) {
            return Ok(Some("that member belongs to another account".into()));
        }
    }
    Ok(None)
}

/// A database failure that says nothing about the op (a full disk, I/O, a lock): the batch fails
/// and the device retries it later, rather than the op being refused for good.
fn is_transient(e: &anyhow::Error) -> bool {
    use rusqlite::ErrorCode::*;
    e.chain().any(|c| {
        matches!(
            c.downcast_ref::<rusqlite::Error>(),
            Some(rusqlite::Error::SqliteFailure(f, _))
                if matches!(f.code, DiskFull | SystemIoFailure | DatabaseBusy | DatabaseLocked | OutOfMemory
                    | ReadOnly | DatabaseCorrupt | NotADatabase | CannotOpen | FileLockingProtocolFailed)
        )
    })
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
    match op_as(conn, account, SERVER_DEVICE, kind, scope, entity, payload, now, None)? {
        (_, Some(o)) => Ok(o),
        (r, None) => anyhow::bail!("server op {kind} rejected: {:?}", r.error),
    }
}

/// Create and accept an op made on the server for `account` as `device` — the server itself, or
/// an API token's pseudo-device (`token:<id>`, API.md §2.3). `user_time` is a typed time
/// (`TimeSource::User`), taken as-is. Rejections come back as the ack, like a pushed op.
#[allow(clippy::too_many_arguments)]
pub fn op_as(
    conn: &Connection,
    account: &str,
    device: &str,
    kind: &str,
    scope: &str,
    entity: Option<&str>,
    payload: serde_json::Value,
    now: i64,
    user_time: Option<i64>,
) -> anyhow::Result<(AckResult, Option<Op>)> {
    let hlc = server_hlc(conn, now)?;
    let o = Op {
        id: chorus_core::id::new_id(now as u64, rand::random()),
        kind: kind.into(),
        v: op::CURRENT_V,
        scope: scope.into(),
        entity_id: entity.map(str::to_string),
        hlc,
        device_at: user_time.unwrap_or(now),
        tz_offset_min: 0,
        mono: None,
        boot_id: None,
        time_source: if user_time.is_some() { TimeSource::User } else { TimeSource::Auto },
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
        device_id: device.into(),
        sample: ClockSample { server_time: now, mono: None, boot_id: None, offset_ms: 0 },
    };
    accept(conn, &s, o, now, false)
}
