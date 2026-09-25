//! The op log table (docs/DATA_MODEL.md §2).

use chorus_core::op::Op;
use chorus_core::sync::Digest;
use chorus_core::time::TimeSource;
use rusqlite::{Connection, OptionalExtension, Row, params};

const COLS: &str = "seq, id, scope, kind, entity_id, payload, v, hlc, account_id, device_id, member_id, \
    occurred_at, device_at, tz_offset_min, mono, boot_id, time_source, seen_seq, received_at";

fn from_row(r: &Row) -> rusqlite::Result<Op> {
    let (mut o, payload) = from_row_raw(r)?;
    o.payload = parse_payload(&payload);
    Ok(o)
}

/// An op's payload as stored (checked JSON when it was written).
pub fn parse_payload(payload: &str) -> serde_json::Value {
    serde_json::from_str(payload).unwrap_or_default()
}

/// [`from_row`] with the payload left as text (`Value::Null` in the op), for a caller that parses
/// it elsewhere: a rebuild does that on its worker threads (R26).
fn from_row_raw(r: &Row) -> rusqlite::Result<(Op, String)> {
    let payload: String = r.get(5)?;
    let hlc: String = r.get(7)?;
    let ts: String = r.get(16)?;
    let o = Op {
        seq: Some(r.get(0)?),
        id: r.get(1)?,
        scope: r.get(2)?,
        kind: r.get(3)?,
        entity_id: r.get(4)?,
        payload: serde_json::Value::Null,
        v: r.get(6)?,
        hlc: hlc.parse().unwrap_or_default(),
        account_id: Some(r.get(8)?),
        device_id: Some(r.get(9)?),
        member_id: r.get(10)?,
        occurred_at: Some(r.get(11)?),
        device_at: r.get(12)?,
        tz_offset_min: r.get(13)?,
        mono: r.get(14)?,
        boot_id: r.get(15)?,
        time_source: if ts == "user" { TimeSource::User } else { TimeSource::Auto },
        seen_seq: r.get(17)?,
        received_at: Some(r.get(18)?),
    };
    Ok((o, payload))
}

/// Insert a fully stamped op; returns its new seq.
pub fn insert(conn: &Connection, o: &Op, time_suspect: bool, restored: bool) -> anyhow::Result<i64> {
    conn.prepare_cached(
        "INSERT INTO op (id, scope, kind, entity_id, payload, v, hlc, account_id, device_id, member_id,
            occurred_at, device_at, tz_offset_min, mono, boot_id, time_source, time_suspect, seen_seq,
            received_at, restored)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20)",
    )?
    .execute(params![
        o.id,
        o.scope,
        o.kind,
        o.entity_id,
        serde_json::to_string(&o.payload)?,
        o.v,
        o.hlc.to_string(),
        o.account_id,
        o.device_id,
        o.member_id,
        o.occurred_at,
        o.device_at,
        o.tz_offset_min,
        o.mono,
        o.boot_id,
        if o.time_source == TimeSource::User { "user" } else { "auto" },
        time_suspect,
        o.seen_seq,
        o.received_at,
        restored,
    ])?;
    Ok(conn.last_insert_rowid())
}

pub fn by_id(conn: &Connection, id: &str) -> anyhow::Result<Option<Op>> {
    Ok(conn.prepare_cached(&format!("SELECT {COLS} FROM op WHERE id = ?1"))?.query_row([id], from_row).optional()?)
}

/// Ops of a scope after `after`, oldest first.
pub fn scope_after(conn: &Connection, scope: &str, after: i64, limit: usize) -> anyhow::Result<Vec<Op>> {
    let mut st = conn.prepare_cached(&format!(
        "SELECT {COLS} FROM op WHERE scope = ?1 AND seq > ?2 AND status = 'applied' ORDER BY seq LIMIT ?3"
    ))?;
    let rows = st.query_map(params![scope, after, limit as i64], from_row)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Applied ops of every scope after `after`, in seq order (for rebuilds).
pub fn applied_after(conn: &Connection, after: i64, limit: usize) -> anyhow::Result<Vec<Op>> {
    let mut st = conn.prepare_cached(&format!(
        "SELECT {COLS} FROM op WHERE seq > ?1 AND status = 'applied' ORDER BY seq LIMIT ?2"
    ))?;
    let rows = st.query_map(params![after, limit as i64], from_row)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// [`applied_after`], each payload still text ([`from_row_raw`]).
pub fn applied_after_raw(conn: &Connection, after: i64, limit: usize) -> anyhow::Result<Vec<(Op, String)>> {
    let mut st = conn.prepare_cached(&format!(
        "SELECT {COLS} FROM op WHERE seq > ?1 AND status = 'applied' ORDER BY seq LIMIT ?2"
    ))?;
    let rows = st.query_map(params![after, limit as i64], from_row_raw)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

pub fn for_entity(conn: &Connection, entity_id: &str) -> anyhow::Result<Vec<Op>> {
    let mut st = conn.prepare_cached(&format!("SELECT {COLS} FROM op WHERE entity_id = ?1 AND status = 'applied'"))?;
    let rows = st.query_map([entity_id], from_row)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// [`for_entity`], only ops of these kinds (e.g. a reaction's adds and removes, not the message's
/// own ops). Kinds are catalogue names, so they're written into the SQL as they are.
pub fn for_entity_of_kinds(conn: &Connection, entity_id: &str, kinds: &[&str]) -> anyhow::Result<Vec<Op>> {
    let list: Vec<String> = kinds.iter().map(|k| format!("'{k}'")).collect();
    let mut st = conn.prepare_cached(&format!(
        "SELECT {COLS} FROM op WHERE entity_id = ?1 AND status = 'applied' AND kind IN ({})",
        list.join(", ")
    ))?;
    let rows = st.query_map([entity_id], from_row)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

pub fn for_scope_kinds(conn: &Connection, scope: &str, kind_prefix: &str) -> anyhow::Result<Vec<Op>> {
    let mut st = conn.prepare_cached(&format!(
        "SELECT {COLS} FROM op WHERE scope = ?1 AND kind LIKE ?2 AND status = 'applied' ORDER BY seq"
    ))?;
    let rows = st.query_map(params![scope, format!("{kind_prefix}%")], from_row)?;
    Ok(rows.collect::<Result<_, _>>()?)
}

pub fn max_seq(conn: &Connection, scope: &str) -> anyhow::Result<i64> {
    Ok(conn.query_row("SELECT coalesce(max(seq), 0) FROM op WHERE scope = ?1", [scope], |r| r.get(0))?)
}

/// Digest of a scope (SYNC.md §6.4). Computed on demand; cache per scope if it shows up in
/// profiles (it's O(ops in scope)).
pub fn digest(conn: &Connection, scope: &str) -> anyhow::Result<Digest> {
    let mut st = conn.prepare_cached("SELECT id FROM op WHERE scope = ?1 AND status = 'applied'")?;
    let mut d = Digest::default();
    let mut rows = st.query([scope])?;
    while let Some(r) = rows.next()? {
        let id: String = r.get(0)?;
        d.add(&id);
    }
    Ok(d)
}

pub fn count(conn: &Connection) -> anyhow::Result<i64> {
    Ok(conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0))?)
}
