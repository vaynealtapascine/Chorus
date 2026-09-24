//! SQL projections (docs/DATA_MODEL.md §4).
//!
//! Every accepted op re-projects what it touches by running `chorus_core::model` over the ops of
//! that entity (or element set, or account front). The SQL rows are therefore the reference
//! model's output by construction; `tests/projection.rs` checks it on random op sets. Two paths
//! go one op at a time instead, through the same core functions (SPEC §9 ingest budget): a switch
//! that extends the timeline (`front::append`), and read states (`model::read_best`).

use std::collections::{BTreeMap, HashMap};

use chorus_core::front::{self, FrontOp};
use chorus_core::lww::SetElem;
use chorus_core::model;
use chorus_core::op::{self, Action, Known, Op};
use chorus_core::text::{self, Entity};
use rusqlite::{Connection, OptionalExtension, params, params_from_iter, types::Value as Sql};
use serde_json::Value;

use crate::{ingest, oplog};

/// Re-project everything `o` touches. Runs inside the ingest transaction.
pub fn after_insert(conn: &Connection, o: &Op) -> anyhow::Result<()> {
    let Ok(Known::Yes(spec)) = op::validate(o) else { return Ok(()) };
    match spec.action {
        Action::Front => front_op(conn, o),
        Action::SetAdd | Action::SetRemove => element_set(conn, spec.table, o),
        Action::Special => special(conn, o),
        Action::Admin => Ok(()),
        Action::Set if FAST_SET.contains(&spec.table) && set_in_place(conn, spec.table, o)? => Ok(()),
        _ => {
            match SINGLE_OP.with(|m| m.borrow().as_ref().map(|multi| !o.entity().is_some_and(|e| multi.contains(e)))) {
                // a rebuild knows this is the entity's only op: project it from the op in hand
                Some(true) => entity_from(conn, spec.table, o.entity().unwrap_or(""), vec![o.clone()]),
                _ => entity(conn, spec.table, o.entity().unwrap_or("")),
            }
        }
    }
}

/// Tables whose `.set` has no effect beyond its own fields (no derived rows, links or parents),
/// so it can be applied to the stored row (SPEC §9 ingest budget).
const FAST_SET: &[&str] = &["member", "custom_state", "field_def", "bucket"];

/// Apply a `.set` straight onto the stored row with the model's own LWW rule
/// (`lww::apply_fields` over the row's `clocks`), instead of re-running the model over every op
/// of the entity. False if the row isn't there yet (the full path then waits for its create).
fn set_in_place(conn: &Connection, table: &str, o: &Op) -> anyhow::Result<bool> {
    let Some(id) = o.entity() else { return Ok(false) };
    let stored: Option<String> = conn
        .prepare_cached(&format!("SELECT clocks FROM {table} WHERE id = ?1"))?
        .query_row([id], |r| r.get(0))
        .optional()?;
    let Some(stored) = stored else { return Ok(false) };
    let mut clocks = chorus_core::lww::Clocks::from_json(&serde_json::from_str(&stored)?);
    let payload = o.payload_obj().cloned().unwrap_or_default();
    let won = chorus_core::lww::apply_fields(&mut clocks, o.hlc, &payload);
    let cols = writable(conn, table)?;
    let mut sets = vec!["clocks = ?1".to_string()];
    let mut vals = vec![Sql::Text(clocks.to_json().to_string())];
    for (k, v) in &won {
        if cols.contains(k) && !matches!(k.as_str(), "id" | "clocks" | "account_id" | "created_at") {
            vals.push(to_sql(v));
            sets.push(format!("{k} = ?{}", vals.len()));
        }
    }
    vals.push(Sql::Text(id.to_string()));
    exec(conn, &format!("UPDATE {table} SET {} WHERE id = ?{}", sets.join(", "), vals.len()), params_from_iter(vals))?;
    Ok(true)
}

// ─── entity rows ─────────────────────────────────────────────────────────────

fn columns(conn: &Connection, table: &str) -> anyhow::Result<Vec<String>> {
    let mut st = conn.prepare_cached(&format!("PRAGMA table_info({table})"))?;
    let v = st.query_map([], |r| r.get::<_, String>(1))?.collect::<Result<_, _>>()?;
    Ok(v)
}

thread_local! {
    /// Writable columns per table. Migrations only run before serving, so the schema a
    /// connection sees doesn't change while projecting; cached per thread (SPEC §9 ingest budget).
    static WRITABLE: std::cell::RefCell<HashMap<String, std::rc::Rc<Vec<String>>>> = Default::default();
}

/// Generated (virtual/stored) columns can't be written.
fn writable(conn: &Connection, table: &str) -> anyhow::Result<std::rc::Rc<Vec<String>>> {
    if let Some(v) = WRITABLE.with(|w| w.borrow().get(table).cloned()) {
        return Ok(v);
    }
    let mut st = conn.prepare_cached(&format!("PRAGMA table_xinfo({table})"))?;
    let v: Vec<String> = st
        .query_map([], |r| Ok((r.get::<_, String>(1)?, r.get::<_, i64>(6)?)))?
        .filter_map(|r| r.ok())
        .filter(|(_, hidden)| *hidden == 0)
        .map(|(n, _)| n)
        .collect();
    let v = std::rc::Rc::new(v);
    WRITABLE.with(|w| w.borrow_mut().insert(table.to_string(), v.clone()));
    Ok(v)
}

fn to_sql(v: &Value) -> Sql {
    match v {
        Value::Null => Sql::Null,
        Value::Bool(b) => Sql::Integer(i64::from(*b)),
        Value::Number(n) => n.as_i64().map(Sql::Integer).unwrap_or_else(|| Sql::Real(n.as_f64().unwrap_or(0.0))),
        Value::String(s) => Sql::Text(s.clone()),
        other => Sql::Text(other.to_string()),
    }
}

/// `execute` through the statement cache: projection SQL is built from a few shapes and runs for
/// every op, so re-preparing it each time is a large part of ingest and rebuild (SPEC §9).
fn exec<P: rusqlite::Params>(conn: &Connection, sql: &str, params: P) -> rusqlite::Result<usize> {
    conn.prepare_cached(sql)?.execute(params)
}

fn account_of(scope: &str) -> Option<&str> {
    scope.strip_prefix("account:")
}

/// Re-project one row of `table` from all ops on `id`.
pub fn entity(conn: &Connection, table: &str, id: &str) -> anyhow::Result<()> {
    if id.is_empty() {
        return Ok(());
    }
    entity_from(conn, table, id, oplog::for_entity(conn, id)?)
}

/// [`entity`] over a given set of the entity's ops (all of them).
fn entity_from(conn: &Connection, table: &str, id: &str, ops: Vec<Op>) -> anyhow::Result<()> {
    match prepare(conn, table, id, ops)? {
        Some(p) => write(conn, &p),
        None => Ok(()),
    }
}

/// An entity row computed from its ops, ready to write. Computing it doesn't touch the
/// projections, so a rebuild prepares single-op entities on its reader thread.
struct Prepared {
    table: String,
    id: String,
    fields: serde_json::Map<String, Value>,
    names: Vec<String>,
    values: Vec<Sql>,
    fresh: bool,
    first_scope: Option<String>,
}

/// The row `ops` project to; `None` if the entity isn't created yet (fields wait in the log
/// until the create arrives). `conn` is only asked for the table's columns.
fn prepare(conn: &Connection, table: &str, id: &str, ops: Vec<Op>) -> anyhow::Result<Option<Prepared>> {
    if id.is_empty() {
        return Ok(None);
    }
    let ops: Vec<Op> = ops
        .into_iter()
        .filter(|o| {
            op::spec(&o.kind)
                .is_some_and(|s| s.table == table && s.action != Action::SetAdd && s.action != Action::SetRemove)
        })
        .collect();
    let proj = model::project(ops.iter());
    let Some(row) = proj.row(table, id).filter(|r| r.exists) else {
        return Ok(None);
    };
    // the create is the entity's only op: nothing derived from it has been written yet
    let fresh = ops.len() == 1;
    let first = ops.iter().min_by_key(|o| (o.hlc, o.id.clone()));
    let mut vals: BTreeMap<String, Value> = row.fields.clone().into_iter().collect();
    vals.insert("id".into(), Value::String(id.into()));
    vals.insert("clocks".into(), row.clocks.to_json());
    if let Some(f) = first {
        vals.entry("created_at".into()).or_insert(Value::from(f.time()));
        if let Some(a) = account_of(&f.scope) {
            vals.entry("account_id".into()).or_insert(Value::String(a.into()));
        }
        if table == "system" {
            vals.insert("account_id".into(), Value::String(id.into()));
        }
        if table == "space" {
            vals.entry("owner_account_id".into()).or_insert(Value::from(f.account_id.clone()));
        }
        if matches!(table, "message" | "post") {
            vals.insert("device_id".into(), Value::from(f.device_id.clone()));
            vals.insert("received_at".into(), Value::from(f.received_at));
            vals.insert("tz_offset_min".into(), Value::from(f.tz_offset_min));
            vals.insert("revision_count".into(), Value::from(row.edits + 1));
        }
        if matches!(table, "message" | "post") {
            // The op/model field is `reply_to`; the SQL column is `reply_to_id` (messages too:
            // until migration 0006 their column stayed empty).
            vals.insert("reply_to_id".into(), row.fields.get("reply_to").cloned().unwrap_or(Value::Null));
        }
        if table == "post" {
            vals.insert("repost_of_id".into(), row.fields.get("repost_of").cloned().unwrap_or(Value::Null));
        }
    }
    if table == "custom_emoji" {
        vals.entry("created_by".into()).or_insert(Value::from(first.and_then(|f| f.account_id.clone())));
    }
    let cols = writable(conn, table)?;
    let (names, values): (Vec<String>, Vec<Sql>) =
        vals.iter().filter(|(k, _)| cols.contains(k)).map(|(k, v)| (k.clone(), to_sql(v))).unzip();
    Ok(Some(Prepared {
        table: table.to_string(),
        id: id.to_string(),
        fields: row.fields.clone(),
        names,
        values,
        fresh,
        first_scope: first.map(|f| f.scope.clone()),
    }))
}

fn write(conn: &Connection, p: &Prepared) -> anyhow::Result<()> {
    let Prepared { table, id, fields, names, values, fresh, first_scope } = p;
    let (table, id, fresh) = (table.as_str(), id.as_str(), *fresh);
    let old_thread_parent = if table == "channel" { thread_parent(conn, id)? } else { None };
    match table {
        // one row per account, keyed by it
        "system" => upsert(conn, table, &["account_id"], names, values)?,
        // created by the server at enrolment; `account.set` only updates it
        "account" => {
            let sets: Vec<String> = names
                .iter()
                .enumerate()
                .filter(|(_, n)| !matches!(n.as_str(), "id" | "created_at"))
                .map(|(i, n)| format!("{n} = ?{}", i + 1))
                .collect();
            let id_at = names.iter().position(|n| n == "id").map_or(0, |i| i + 1);
            if !sets.is_empty() && id_at > 0 {
                exec(
                    conn,
                    &format!("UPDATE account SET {} WHERE id = ?{id_at}", sets.join(", ")),
                    params_from_iter(values),
                )?;
            }
        }
        _ => upsert(conn, table, &["id"], names, values)?,
    }
    match table {
        "message" => {
            message_extras(conn, id, fields.get("authors"), fields, fresh)?;
            item_attachments(conn, "message", id, fields.get("attachments"), fresh)?;
            refresh_thread_link(conn, id)?;
        }
        "channel" => {
            if let Some(parent) = old_thread_parent {
                refresh_thread_link(conn, &parent)?;
            }
            if let Some(parent) = thread_parent(conn, id)? {
                refresh_thread_link(conn, &parent)?;
            }
        }
        "post" => {
            authors(conn, "post_author", "post_id", id, fields.get("authors"), fresh)?;
            item_attachments(conn, "post", id, fields.get("attachments"), fresh)?;
            post_search(conn, id, fresh)?;
        }
        "member_group" => group_parents(conn, account_of(first_scope.as_deref().unwrap_or_default()).unwrap_or(""))?,
        _ => {}
    }
    Ok(())
}

fn thread_parent(conn: &Connection, channel_id: &str) -> anyhow::Result<Option<String>> {
    Ok(conn
        .query_row("SELECT parent_message_id FROM channel WHERE id = ?1 AND kind = 'thread'", [channel_id], |r| {
            r.get(0)
        })
        .optional()?
        .flatten())
}

/// A thread is a channel, and its parent message keeps a reverse pointer for read APIs. Refresh
/// on both channel and message projection so offline ops converge in either arrival order.
/// A rebuild links every thread once at the end instead ([`THREAD_LINK`] over all parents).
fn refresh_thread_link(conn: &Connection, parent_id: &str) -> anyhow::Result<()> {
    if REBUILDING.with(std::cell::Cell::get) {
        return Ok(());
    }
    exec(conn, &format!("{THREAD_LINK} WHERE id = ?1"), [parent_id])?;
    Ok(())
}

const THREAD_LINK: &str = "UPDATE message SET thread_channel_id = (
       SELECT t.id FROM channel t JOIN channel source ON source.id = message.channel_id
       WHERE t.kind = 'thread' AND t.parent_message_id = message.id AND t.space_id = source.space_id
         AND t.deleted_at IS NULL AND t.archived_at IS NULL
       ORDER BY t.created_at, t.id LIMIT 1
     )";

/// (table, columns) → statement text.
type SqlShapes = HashMap<(String, Vec<String>), std::rc::Rc<str>>;

thread_local! {
    /// A connection the next rebuild on this thread reads the op log through (rebuild_swap lends
    /// the locked source file); it is put back afterwards.
    static LOG_READER: std::cell::RefCell<Option<Connection>> = const { std::cell::RefCell::new(None) };
    /// Upsert SQL by table and column list: rows of a table come in a few shapes, and building
    /// the statement text for every row showed up in rebuild profiles (SPEC §9).
    static UPSERT_SQL: std::cell::RefCell<SqlShapes> = Default::default();
}

fn upsert(conn: &Connection, table: &str, key: &[&str], names: &[String], values: &[Sql]) -> anyhow::Result<()> {
    let hit =
        UPSERT_SQL.with(|c| c.borrow().iter().find(|((t, n), _)| t == table && n == names).map(|(_, sql)| sql.clone()));
    let sql = match hit {
        Some(sql) => sql,
        None => {
            let sql: std::rc::Rc<str> = upsert_sql(table, key, names).into();
            UPSERT_SQL.with(|c| {
                let mut c = c.borrow_mut();
                if c.len() >= 128 {
                    c.clear(); // an odd mix of optional fields; don't let the cache grow forever
                }
                c.insert((table.to_string(), names.to_vec()), sql.clone());
            });
            sql
        }
    };
    exec(conn, &sql, params_from_iter(values))?;
    Ok(())
}

fn upsert_sql(table: &str, key: &[&str], names: &[String]) -> String {
    let placeholders: Vec<String> = (1..=names.len()).map(|i| format!("?{i}")).collect();
    let updates: Vec<String> =
        names.iter().filter(|n| !key.contains(&n.as_str())).map(|n| format!("{n} = excluded.{n}")).collect();
    format!(
        "INSERT INTO {table} ({}) VALUES ({}) ON CONFLICT({}) DO {}",
        names.join(", "),
        placeholders.join(", "),
        key.join(", "),
        if updates.is_empty() { "NOTHING".into() } else { format!("UPDATE SET {}", updates.join(", ")) }
    )
}

/// `fresh`: the entity's only op is its create, so it has no derived rows to clear yet.
fn authors(conn: &Connection, table: &str, key: &str, id: &str, a: Option<&Value>, fresh: bool) -> anyhow::Result<()> {
    if !fresh {
        exec(conn, &format!("DELETE FROM {table} WHERE {key} = ?1"), [id])?;
    }
    for (i, m) in a.and_then(Value::as_array).into_iter().flatten().enumerate() {
        if let Some(m) = m.as_str() {
            exec(
                conn,
                &format!("INSERT OR IGNORE INTO {table} ({key}, member_id, position) VALUES (?1, ?2, ?3)"),
                params![id, m, i as i64],
            )?;
        }
    }
    Ok(())
}

/// Authors, segments (D-045), mentions and the FTS index for a message.
fn item_attachments(
    conn: &Connection,
    owner_type: &str,
    owner_id: &str,
    ids: Option<&Value>,
    fresh: bool,
) -> anyhow::Result<()> {
    if !fresh {
        exec(
            conn,
            "DELETE FROM item_attachment WHERE owner_type = ?1 AND owner_id = ?2",
            params![owner_type, owner_id],
        )?;
    }
    for (position, id) in ids.and_then(Value::as_array).into_iter().flatten().enumerate() {
        if let Some(id) = id.as_str() {
            exec(
                conn,
                "INSERT OR IGNORE INTO item_attachment(owner_type, owner_id, attachment_id, position) VALUES (?1, ?2, ?3, ?4)",
                params![owner_type, owner_id, id, position as i64],
            )?;
        }
    }
    Ok(())
}

fn message_extras(
    conn: &Connection,
    id: &str,
    a: Option<&Value>,
    f: &serde_json::Map<String, Value>,
    fresh: bool,
) -> anyhow::Result<()> {
    authors(conn, "message_author", "message_id", id, a, fresh)?;
    let text_s = f.get("text").and_then(Value::as_str).unwrap_or("");
    if !fresh {
        exec(conn, "DELETE FROM message_segment WHERE message_id = ?1", [id])?;
        exec(conn, "DELETE FROM message_segment_author WHERE message_id = ?1", [id])?;
    }
    let segs: Vec<Value> = match f.get("segments").and_then(Value::as_array) {
        Some(s) if !s.is_empty() => s.clone(),
        _ => vec![
            serde_json::json!({"offset": 0, "length": text::utf16_len(text_s), "authors": a.cloned().unwrap_or_default()}),
        ],
    };
    for (i, s) in segs.iter().enumerate() {
        let off = s.get("offset").and_then(Value::as_u64).unwrap_or(0) as u32;
        let len = s.get("length").and_then(Value::as_u64).unwrap_or(0) as u32;
        exec(
            conn,
            "INSERT INTO message_segment (message_id, idx, offset_u16, length_u16, text) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![id, i as i64, off, len, text::utf16_slice(text_s, off, len)],
        )?;
        for (p, m) in s.get("authors").and_then(Value::as_array).into_iter().flatten().enumerate() {
            if let Some(m) = m.as_str() {
                exec(
                    conn,
                    "INSERT OR IGNORE INTO message_segment_author (message_id, idx, member_id, position) VALUES (?1, ?2, ?3, ?4)",
                    params![id, i as i64, m, p as i64],
                )?;
            }
        }
    }
    if !fresh {
        exec(conn, "DELETE FROM mention WHERE source_type = 'message' AND source_id = ?1", [id])?;
    }
    let ents: Vec<Entity> = f.get("entities").and_then(|e| serde_json::from_value(e.clone()).ok()).unwrap_or_default();
    for e in ents {
        if let text::EntityKind::Mention { target_type, target_id } = e.kind {
            let tt = serde_json::to_value(target_type)?.as_str().unwrap_or("member").to_string();
            exec(
                conn,
                "INSERT OR IGNORE INTO mention (source_type, source_id, target_type, target_id) VALUES ('message', ?1, ?2, ?3)",
                params![id, tt, target_id.unwrap_or_default()],
            )?;
        }
    }
    // FTS keeps its own copy keyed by the message rowid; replace it (deleted messages drop out).
    // A rebuild fills the index in one pass at the end instead.
    if REBUILDING.with(std::cell::Cell::get) {
        return Ok(());
    }
    let rowid: Option<i64> =
        conn.prepare_cached("SELECT rowid FROM message WHERE id = ?1")?.query_row([id], |r| r.get(0)).optional()?;
    if let Some(rowid) = rowid {
        if !fresh {
            exec(conn, "DELETE FROM message_fts WHERE rowid = ?1", [rowid])?;
        }
        exec(
            conn,
            "INSERT INTO message_fts(rowid, text, cw) SELECT rowid, text, coalesce(cw, '') FROM message WHERE rowid = ?1 AND deleted_at IS NULL",
            [rowid],
        )?;
    }
    Ok(())
}

/// A post's row in `post_fts` (title, text and tags), what `GET /search/posts` matches. Like
/// `message_fts` it keeps its own copy keyed by the post's rowid; deleted posts drop out.
/// A rebuild fills it in one pass at the end instead.
const POST_FTS_FILL: &str = "INSERT INTO post_fts(rowid, title, text, tags)
     SELECT rowid, coalesce(title, ''), text,
            coalesce((SELECT group_concat(value, ' ') FROM json_each(CASE WHEN json_valid(tags) THEN tags ELSE '[]' END)), '')
     FROM post WHERE deleted_at IS NULL";

fn post_search(conn: &Connection, id: &str, fresh: bool) -> anyhow::Result<()> {
    if REBUILDING.with(std::cell::Cell::get) {
        return Ok(());
    }
    let rowid: Option<i64> =
        conn.prepare_cached("SELECT rowid FROM post WHERE id = ?1")?.query_row([id], |r| r.get(0)).optional()?;
    if let Some(rowid) = rowid {
        if !fresh {
            exec(conn, "DELETE FROM post_fts WHERE rowid = ?1", [rowid])?;
        }
        exec(conn, &format!("{POST_FTS_FILL} AND rowid = ?1"), [rowid])?;
    }
    Ok(())
}

/// Effective parents with the cycle guard (SYNC.md §5.3): in every cycle, the edge whose
/// `parent_id` clock is highest is ignored.
fn group_parents(conn: &Connection, account: &str) -> anyhow::Result<()> {
    let mut st = conn.prepare("SELECT id, parent_id, clocks FROM member_group WHERE account_id = ?1")?;
    let rows: Vec<(String, Option<String>, String)> =
        st.query_map([account], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect::<Result<_, _>>()?;
    let mut parent: HashMap<String, (String, String)> = HashMap::new(); // id → (parent, clock)
    for (id, p, clocks) in &rows {
        if let Some(p) = p.clone().filter(|p| !p.is_empty()) {
            let c = serde_json::from_str::<Value>(clocks)
                .ok()
                .and_then(|v| v.get("parent_id").and_then(Value::as_str).map(str::to_string))
                .unwrap_or_default();
            parent.insert(id.clone(), (p, c));
        }
    }
    let mut effective: HashMap<String, Option<String>> =
        rows.iter().map(|(id, p, _)| (id.clone(), p.clone())).collect();
    // repeatedly find a cycle and cut its newest edge
    loop {
        let mut cut: Option<String> = None;
        'outer: for start in parent.keys() {
            let mut seen = vec![start.clone()];
            let mut cur = start.clone();
            while let Some((p, _)) = parent.get(&cur) {
                if let Some(pos) = seen.iter().position(|s| s == p) {
                    let cycle = &seen[pos..];
                    cut = cycle.iter().max_by(|a, b| parent[*a].1.cmp(&parent[*b].1).then(a.cmp(b))).cloned();
                    break 'outer;
                }
                seen.push(p.clone());
                cur = p.clone();
            }
        }
        match cut {
            Some(id) => {
                parent.remove(&id);
                effective.insert(id, None);
            }
            None => break,
        }
    }
    for (id, p) in effective {
        exec(conn, "UPDATE member_group SET effective_parent_id = ?2 WHERE id = ?1", params![id, p])?;
    }
    Ok(())
}

// ─── element sets ────────────────────────────────────────────────────────────

/// (table, key columns from entity/payload, clock columns)
/// Key columns with their source (`"@"` = entity id, else a payload field), and the add/remove
/// clock columns.
type SetShape = (&'static [(&'static str, &'static str)], (&'static str, &'static str));

fn set_shape(table: &str) -> Option<SetShape> {
    // (column, source): source "@" = entity id, otherwise a payload field
    Some(match table {
        "group_membership" => (&[("group_id", "@"), ("member_id", "member_id")], ("added_hlc", "removed_hlc")),
        "space_member" => (&[("space_id", "@"), ("account_id", "account_id")], ("joined_hlc", "left_hlc")),
        "reaction" => (
            &[
                ("target_type", "target_type"),
                ("target_id", "target_id"),
                ("emoji", "emoji"),
                ("member_id", "member_id"),
            ],
            ("added_hlc", "removed_hlc"),
        ),
        "highlight" => {
            (&[("profile_member_id", "profile_member_id"), ("post_id", "post_id")], ("added_hlc", "removed_hlc"))
        }
        "member_list_item" => (&[("list_id", "@"), ("member_id", "member_id")], ("added_hlc", "removed_hlc")),
        "bucket_assignment" => {
            (&[("bucket_id", "@"), ("follower_account_id", "follower_account_id")], ("added_hlc", "removed_hlc"))
        }
        _ => return None,
    })
}

fn element_set(conn: &Connection, table: &str, o: &Op) -> anyhow::Result<()> {
    let Some((keys, (add_col, rem_col))) = set_shape(table) else { return Ok(()) };
    let key_of = |x: &Op| -> Vec<String> {
        keys.iter()
            .map(|(_, src)| {
                if *src == "@" {
                    x.entity().unwrap_or("").to_string()
                } else {
                    x.payload.get(*src).and_then(Value::as_str).unwrap_or("").to_string()
                }
            })
            .collect()
    };
    let want = key_of(o);
    // All ops on the same element: same entity, same table, same key.
    let ops: Vec<Op> = oplog::for_entity(conn, o.entity().unwrap_or(""))?
        .into_iter()
        .filter(|x| op::spec(&x.kind).is_some_and(|s| s.table == table))
        .filter(|x| key_of(x) == want)
        .collect();
    let mut e = SetElem::default();
    for x in &ops {
        if op::spec(&x.kind).is_some_and(|s| s.action == Action::SetAdd) { e.add(x.hlc) } else { e.remove(x.hlc) }
    }
    let mut names: Vec<String> = keys.iter().map(|(c, _)| c.to_string()).collect();
    let mut values: Vec<Sql> = want.iter().map(|k| Sql::Text(k.clone())).collect();
    names.push(add_col.into());
    values.push(e.added.map(|h| Sql::Text(h.to_string())).unwrap_or(Sql::Null));
    names.push(rem_col.into());
    values.push(e.removed.map(|h| Sql::Text(h.to_string())).unwrap_or(Sql::Null));
    if table == "highlight" {
        names.push("added_by_member".into());
        values.push(o.member_id.clone().map(Sql::Text).unwrap_or(Sql::Null));
    }
    let key_cols: Vec<&str> = keys.iter().map(|(c, _)| *c).collect();
    upsert(conn, table, &key_cols, &names, &values)?;
    if table == "space_member" {
        space_access(conn, o, &want[1], e.is_present())?;
    }
    Ok(())
}

/// Space membership grants scope access — but only when the op's author may manage the space
/// (its owner, or the account joining a DM/space it created itself). Access ends when the owner
/// removes someone, or when someone leaves a shared space or DM themselves (M6.2); nobody loses
/// their own internal space, and the owner of a shared space can't strand it by leaving.
fn space_access(conn: &Connection, o: &Op, account: &str, present: bool) -> anyhow::Result<()> {
    let space = o.entity().unwrap_or("");
    let row: Option<(String, Option<String>)> = conn
        .query_row("SELECT owner_account_id, kind FROM space WHERE id = ?1", [space], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    let (owner, kind) = row.map_or((None, None), |(o, k)| (Some(o), k));
    let author = o.account_id.clone().unwrap_or_default();
    let manages = match &owner {
        Some(ow) => *ow == author,
        None => author == account,
    };
    let self_leave = !present
        && account == author
        && match kind.as_deref() {
            Some("dm") => true,
            Some("shared") => owner.as_deref() != Some(author.as_str()),
            _ => false,
        };
    if present && manages {
        ingest::grant(conn, account, &o.scope)?;
    } else if !present && ((manages && account != author) || self_leave) {
        exec(conn, "DELETE FROM scope_access WHERE account_id = ?1 AND scope = ?2", params![account, o.scope])?;
        // still a guest of one of its channels?
        crate::perms::refresh_guest(conn, account, space)?;
    }
    Ok(())
}

// ─── front ───────────────────────────────────────────────────────────────────

thread_local! {
    static FRONT_FAST_PATH: std::cell::Cell<bool> = const { std::cell::Cell::new(true) };
}

/// Tests only: turn the one-step front path off on this thread (every front op refolds), to
/// compare against it.
#[doc(hidden)]
pub fn set_front_fast_path(on: bool) {
    FRONT_FAST_PATH.with(|c| c.set(on));
}

/// Re-project the account front after `o`: one fold step when it simply extends the timeline (a
/// live switch, the common case), else a full refold.
fn front_op(conn: &Connection, o: &Op) -> anyhow::Result<()> {
    if !FRONT_FAST_PATH.with(std::cell::Cell::get) || !append_front(conn, o)? {
        account_front(conn, &o.scope)?;
    }
    Ok(())
}

fn insert_switch(conn: &Connection, account: &str, s: &front::SwitchRow) -> anyhow::Result<()> {
    let mut ins = conn.prepare_cached(
        "INSERT INTO switch (id, account_id, kind, occurred_at, tz_offset_min, device_id, based_on, entries,
            resulting_front, note, notify, was_offline, retracted, amended)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)",
    )?;
    ins.execute(params![
        s.id,
        account,
        s.kind,
        s.occurred_at,
        s.tz_offset_min,
        s.device_id,
        s.based_on,
        serde_json::to_string(&s.entries)?,
        serde_json::to_string(&s.resulting_front)?,
        s.note,
        serde_json::to_value(s.notify)?.as_str().unwrap_or("default"),
        s.was_offline,
        s.retracted,
        s.amended,
    ])?;
    Ok(())
}

fn insert_interval(conn: &Connection, account: &str, i: &front::Interval) -> anyhow::Result<()> {
    let mut ins = conn.prepare_cached(
        "INSERT INTO front_interval (id, account_id, subject_type, subject_id, level, is_primary, position,
            start_at, end_at, start_switch_id, end_switch_id, start_tz_offset_min)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
    )?;
    ins.execute(params![
        i.id,
        account,
        i.subject_type.as_str(),
        i.subject_id,
        i.level.as_str(),
        i.is_primary,
        i.position as i64,
        i.start_at,
        i.end_at,
        i.start_switch_id,
        i.end_switch_id,
        i.start_tz_offset_min,
    ])?;
    Ok(())
}

/// Intervals from `front_interval`: the open ones, or (with `open_only` false) also every closed
/// one that ends after `after`.
fn load_intervals(
    conn: &Connection,
    account: &str,
    open_only: bool,
    after: i64,
) -> anyhow::Result<Vec<front::Interval>> {
    let mut st = conn.prepare_cached(
        "SELECT id, subject_type, subject_id, level, is_primary, position, start_at, end_at, start_switch_id,
                end_switch_id, start_tz_offset_min
         FROM front_interval WHERE account_id = ?1 AND end_at IS NULL
         UNION ALL
         SELECT id, subject_type, subject_id, level, is_primary, position, start_at, end_at, start_switch_id,
                end_switch_id, start_tz_offset_min
         FROM front_interval WHERE ?2 = 0 AND account_id = ?1 AND end_at > ?3",
    )?;
    type Raw = (String, String, String, String, bool, i64, i64, Option<i64>, String, Option<String>, i32);
    let rows: Vec<Raw> = st
        .query_map(params![account, open_only, after], |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                r.get(2)?,
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
                r.get(6)?,
                r.get(7)?,
                r.get(8)?,
                r.get(9)?,
                r.get(10)?,
            ))
        })?
        .collect::<Result<_, _>>()?;
    let mut out = Vec::with_capacity(rows.len());
    for (id, st, sid, level, primary, pos, start, end, start_sw, end_sw, tz) in rows {
        out.push(front::Interval {
            id,
            subject_type: serde_json::from_value(Value::String(st))?,
            subject_id: sid,
            level: serde_json::from_value(Value::String(level))?,
            is_primary: primary,
            position: pos as usize,
            start_at: start,
            end_at: end,
            start_switch_id: start_sw,
            end_switch_id: end_sw,
            start_tz_offset_min: tz,
        });
    }
    Ok(out)
}

/// Write `front_daily` for the local days from the one starting at `from_day_start` (the UTC
/// instant of a local midnight), replacing what was there; `None` rewrites every day.
fn write_daily(conn: &Connection, account: &str, tz: i32, from_day_start: Option<i64>) -> anyhow::Result<()> {
    const DAY: i64 = 86_400_000;
    let now = crate::now_ms();
    let intervals = match from_day_start {
        None => {
            exec(conn, "DELETE FROM front_daily WHERE account_id = ?1", [account])?;
            load_intervals(conn, account, false, i64::MIN)?
        }
        Some(t0) => {
            let day = front::civil_date((t0 + i64::from(tz) * 60_000).div_euclid(DAY));
            exec(conn, "DELETE FROM front_daily WHERE account_id = ?1 AND day >= ?2", params![account, day])?;
            // only what falls on those days: daily() splits at the same local midnights
            let mut v = load_intervals(conn, account, false, t0)?;
            for iv in &mut v {
                iv.start_at = iv.start_at.max(t0);
            }
            v
        }
    };
    let mut ins = conn.prepare_cached(
        "INSERT INTO front_daily (account_id, day, subject_type, subject_id, level, seconds, as_primary_seconds)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
    )?;
    for d in front::daily(&intervals, now, |_| tz) {
        ins.execute(params![
            account,
            d.day,
            d.subject_type.as_str(),
            d.subject_id,
            d.level.as_str(),
            d.seconds,
            d.as_primary_seconds
        ])?;
    }
    Ok(())
}

/// The fast path of [`front_op`]: `o` is a switch-like op that sorts after every folded one and
/// that nothing amends or retracts yet, so the fold needs only one more step (`front::append`,
/// property-tested against `fold`). Returns false when a full refold is needed instead.
fn append_front(conn: &Connection, o: &Op) -> anyhow::Result<bool> {
    const DAY: i64 = 86_400_000;
    let Some(account) = account_of(&o.scope) else { return Ok(false) };
    let Ok(fo) = FrontOp::from_op(o) else { return Ok(false) };
    // the last row in fold order: time, then HLC, then id
    let last: Option<(i64, i32, String)> = conn
        .prepare_cached(
            "SELECT s.occurred_at, s.tz_offset_min, s.resulting_front FROM switch s JOIN op ON op.id = s.id
             WHERE s.account_id = ?1 AND s.occurred_at = (SELECT max(occurred_at) FROM switch WHERE account_id = ?1)
             ORDER BY op.hlc DESC, s.id DESC LIMIT 1",
        )?
        .query_row([account], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
        .optional()?;
    if last.as_ref().is_some_and(|(at, _, _)| *at >= fo.occurred_at) {
        return Ok(false);
    }
    let targeted: bool = conn
        .prepare_cached(
            "SELECT EXISTS (SELECT 1 FROM op WHERE kind IN ('front.retract', 'front.unretract', 'front.amend')
               AND +scope = ?1 AND status = 'applied' AND json_extract(payload, '$.target_op_id') = ?2
               AND seq <= ?3)",
        )?
        .query_row(params![o.scope, o.id, REPLAYED_UPTO.with(std::cell::Cell::get)], |r| r.get(0))?;
    if targeted {
        return Ok(false);
    }
    let current: front::Front = match &last {
        Some((_, _, f)) => serde_json::from_str(f)?,
        None => Vec::new(),
    };
    let open = load_intervals(conn, account, true, 0)?;
    let earliest_open = open.iter().map(|i| i.start_at).min();
    let Some(a) = front::append(&current, open, &fo) else { return Ok(false) };

    insert_switch(conn, account, &a.row)?;
    for c in &a.closed {
        exec(
            conn,
            "UPDATE front_interval SET end_at = ?2, end_switch_id = ?3 WHERE id = ?1",
            params![c.id, c.end_at, c.end_switch_id],
        )?;
    }
    for n in &a.opened {
        insert_interval(conn, account, n)?;
    }

    // Daily totals change from the first day of anything open until now; every day if the
    // offset moved (days are local to the latest switch's offset). A rebuild writes them once
    // at the end instead ([`rebuild_daily`]).
    let tz = fo.tz_offset_min;
    let rebuilding = REBUILDING.with(std::cell::Cell::get);
    if rebuilding {
        // rebuild_daily
    } else if last.as_ref().is_none_or(|(_, prev, _)| *prev != tz) {
        write_daily(conn, account, tz, None)?;
    } else {
        let from = earliest_open.map_or(fo.occurred_at, |e| e.min(fo.occurred_at));
        let off = i64::from(tz) * 60_000;
        write_daily(conn, account, tz, Some((from + off).div_euclid(DAY) * DAY - off))?;
    }

    // review cards pair the new switch with the earlier ones inside the window
    let now = crate::now_ms();
    let earlier: Vec<(String, String)> = {
        let mut st = conn.prepare_cached(
            "SELECT id, resulting_front FROM switch WHERE account_id = ?1 AND retracted = 0 AND id <> ?2
               AND occurred_at >= ?3",
        )?;
        st.query_map(params![account, fo.id, fo.occurred_at - front::DEFAULT_REVIEW_WINDOW_MS], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })?
        .collect::<Result<_, _>>()?
    };
    for (id, f) in earlier {
        let Some(other) = oplog::by_id(conn, &id)?.and_then(|op| FrontOp::from_op(&op).ok()) else { continue };
        let other_front: front::Front = serde_json::from_str(&f)?;
        if let Some(r) = front::review_of((&other, &other_front), (&fo, &a.row.resulting_front)) {
            exec(
                conn,
                "INSERT OR IGNORE INTO front_review (id, account_id, switch_a, switch_b, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![r.id, account, r.switch_a, r.switch_b, now],
            )?;
        }
    }
    Ok(true)
}

/// Daily totals of every account with switches, for the end of a rebuild: what the last front op
/// of each would have written (days local to its latest switch's offset), done once instead of
/// per switch. Closed days don't depend on when they're written, and open ones are cut at "now".
fn rebuild_daily(conn: &Connection) -> anyhow::Result<()> {
    let accounts: Vec<(String, i32)> = conn
        .prepare(
            "SELECT s.account_id, s.tz_offset_min FROM switch s JOIN op ON op.id = s.id
             WHERE s.occurred_at = (SELECT max(occurred_at) FROM switch WHERE account_id = s.account_id)
             ORDER BY s.account_id, op.hlc DESC, s.id DESC",
        )?
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    let mut done = std::collections::HashSet::new();
    for (account, tz) in accounts {
        // the first row per account is its last switch in fold order (time, HLC, id)
        if done.insert(account.clone()) {
            write_daily(conn, &account, tz, None)?;
        }
    }
    Ok(())
}

/// Rewrite an account's switch log, intervals, daily totals and review cards from all its front
/// ops: a full refold, for amends, retracts and switches that arrive out of order.
pub fn account_front(conn: &Connection, scope: &str) -> anyhow::Result<()> {
    let Some(account) = account_of(scope) else { return Ok(()) };
    let upto = REPLAYED_UPTO.with(std::cell::Cell::get);
    let ops: Vec<FrontOp> = oplog::for_scope_kinds(conn, scope, "front.")?
        .iter()
        .filter(|o| o.seq.is_none_or(|seq| seq <= upto))
        .filter_map(|o| FrontOp::from_op(o).ok())
        .collect();
    let folded = front::fold(&ops);
    exec(conn, "DELETE FROM switch WHERE account_id = ?1", [account])?;
    exec(conn, "DELETE FROM front_interval WHERE account_id = ?1", [account])?;
    for s in &folded.switches {
        insert_switch(conn, account, s)?;
    }
    for i in &folded.intervals {
        insert_interval(conn, account, i)?;
    }
    // Local days: the account's most recent UTC offset (no tz database in core; D-058 note).
    let tz = folded.switches.last().map(|s| s.tz_offset_min).unwrap_or(0);
    if !REBUILDING.with(std::cell::Cell::get) {
        write_daily(conn, account, tz, None)?;
    }
    let now = crate::now_ms();
    for r in front::reviews(&ops, &folded, front::DEFAULT_REVIEW_WINDOW_MS) {
        exec(
            conn,
            "INSERT OR IGNORE INTO front_review (id, account_id, switch_a, switch_b, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![r.id, account, r.switch_a, r.switch_b, now],
        )?;
    }
    Ok(())
}

// ─── special kinds ───────────────────────────────────────────────────────────

fn special(conn: &Connection, o: &Op) -> anyhow::Result<()> {
    let p = |k: &str| o.payload.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    match o.kind.as_str() {
        "message.pin" | "message.unpin" => entity(conn, "message", o.entity().unwrap_or("")),
        "follow.request" | "follow.accept" | "follow.end" | "follow.set_ceiling" | "follow.set_prefs" => {
            entity(conn, "follow", o.entity().unwrap_or(""))?;
            if o.kind == "follow.request" {
                exec(
                    conn,
                    "UPDATE follow SET target_account_id = ?2 WHERE id = ?1 AND target_account_id IS NULL",
                    params![o.entity(), p("target_account_id")],
                )?;
            }
            Ok(())
        }
        "field.set_value" => {
            lww_keyed(conn, o, "field_value", &[("member_id", p("member_id")), ("field_id", p("field_id"))], "value")
        }
        "pref.set" => lww_keyed(
            conn,
            o,
            "pref",
            &[("account_id", o.account_id.clone().unwrap_or_default()), ("device_id", p("device")), ("key", p("key"))],
            "value",
        ),
        "channel.set_permission" => {
            let key = [
                ("channel_id", o.entity().unwrap_or("").to_string()),
                ("target_type", p("target_type")),
                ("target_id", p("target_id")),
            ];
            let newer = newer_than_stored(conn, "channel_permission", &key, o)?;
            if newer {
                let allow = o.payload.get("allow").cloned().unwrap_or(Value::Array(vec![]));
                let deny = o.payload.get("deny").cloned().unwrap_or(Value::Array(vec![]));
                let mut names: Vec<String> = key.iter().map(|(c, _)| c.to_string()).collect();
                let mut vals: Vec<Sql> = key.iter().map(|(_, v)| Sql::Text(v.clone())).collect();
                names.extend(["allow".into(), "deny".into(), "hlc".into()]);
                vals.extend([Sql::Text(allow.to_string()), Sql::Text(deny.to_string()), Sql::Text(o.hlc.to_string())]);
                upsert(conn, "channel_permission", &["channel_id", "target_type", "target_id"], &names, &vals)?;
                // an account override can make an outside account a guest of the space (perms.rs)
                if p("target_type") == "account"
                    && let Some(space) = conn
                        .query_row("SELECT space_id FROM channel WHERE id = ?1", [o.entity().unwrap_or("")], |r| {
                            r.get::<_, Option<String>>(0)
                        })
                        .optional()?
                        .flatten()
                {
                    crate::perms::refresh_guest(conn, &p("target_id"), &space)?;
                }
            }
            Ok(())
        }
        "space.set_role" => {
            exec(
                conn,
                "UPDATE space_member SET role = ?3 WHERE space_id = ?1 AND account_id = ?2",
                params![o.entity(), p("account_id"), p("role")],
            )?;
            Ok(())
        }
        "space.set_roles" => {
            exec(
                conn,
                "UPDATE space SET roles = ?2 WHERE id = ?1",
                params![o.entity(), o.payload.get("roles").cloned().unwrap_or_default().to_string()],
            )?;
            Ok(())
        }
        "front.review_resolve" => {
            exec(
                conn,
                "UPDATE front_review SET resolution = ?2, resolved_at = ?3 WHERE id = ?1",
                params![p("review_id"), p("resolution"), o.time()],
            )?;
            Ok(())
        }
        "read.mark" | "read.set" => read_state(conn, o),
        "highlight.reorder" => {
            exec(
                conn,
                "UPDATE highlight SET sort_key = ?3 WHERE profile_member_id = ?1 AND post_id = ?2",
                params![p("profile_member_id"), p("post_id"), p("sort_key")],
            )?;
            Ok(())
        }
        _ => Ok(()),
    }
}

fn newer_than_stored(conn: &Connection, table: &str, key: &[(&str, String)], o: &Op) -> anyhow::Result<bool> {
    let cond: Vec<String> = key.iter().enumerate().map(|(i, (c, _))| format!("{c} = ?{}", i + 1)).collect();
    let cur: Option<String> = conn
        .query_row(
            &format!("SELECT hlc FROM {table} WHERE {}", cond.join(" AND ")),
            params_from_iter(key.iter().map(|(_, v)| v)),
            |r| r.get(0),
        )
        .optional()?;
    Ok(cur.is_none_or(|c| o.hlc.to_string() > c))
}

fn lww_keyed(conn: &Connection, o: &Op, table: &str, key: &[(&str, String)], field: &str) -> anyhow::Result<()> {
    if !newer_than_stored(conn, table, key, o)? {
        return Ok(());
    }
    let mut names: Vec<String> = key.iter().map(|(c, _)| c.to_string()).collect();
    let mut vals: Vec<Sql> = key.iter().map(|(_, v)| Sql::Text(v.clone())).collect();
    names.push(field.into());
    vals.push(Sql::Text(o.payload.get(field).cloned().unwrap_or(Value::Null).to_string()));
    names.push("hlc".into());
    vals.push(Sql::Text(o.hlc.to_string()));
    let key_cols: Vec<&str> = key.iter().map(|(c, _)| *c).collect();
    upsert(conn, table, &key_cols, &names, &vals)
}

/// Read states (SYNC.md §5.5), one op at a time: a mark folds into the stored best mark
/// (`model::read_best`), a newer manual `read.set` raises the floor and rescans that key's marks.
fn read_state(conn: &Connection, o: &Op) -> anyhow::Result<()> {
    type Stored = (Option<i64>, Option<String>, Option<String>, Option<i64>, Option<String>, Option<String>, bool);
    let channel = o.payload.get("channel_id").and_then(Value::as_str).unwrap_or("");
    let reader = o.payload.get("reader_member_id").and_then(Value::as_str).unwrap_or("");
    let account = o.account_id.clone().unwrap_or_default();
    let stored: Option<Stored> = conn
        .query_row(
            "SELECT mark_at, mark_id, mark_hlc, set_at, set_id, set_hlc, state_ok FROM read_state
             WHERE channel_id = ?1 AND account_id = ?2 AND reader_member_id = ?3",
            params![channel, account, reader],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?)),
        )
        .optional()?;
    let mark = |at: Option<i64>, id: Option<String>, hlc: Option<String>| -> Option<model::ReadMark> {
        Some((at?, id?, hlc?.parse().ok()?))
    };
    let this = model::read_mark_of(o);
    let (best, manual) = match stored {
        // from before migration 0004: rebuild this key from the log
        Some((.., false)) => read_state_scan(conn, &account, channel, reader)?,
        Some((ma, mi, mh, sa, si, sh, true)) => {
            let (best, manual) = (mark(ma, mi, mh), mark(sa, si, sh));
            if o.kind == "read.mark" {
                (model::read_best(best.into_iter().chain([this]), manual.as_ref()), manual)
            } else if manual.as_ref().is_none_or(|m| this.2 > m.2) {
                read_state_scan(conn, &account, channel, reader)?
            } else {
                return Ok(());
            }
        }
        None if o.kind == "read.mark" => (Some(this), None),
        None => (None, Some(this)),
    };
    let Some(effective) = model::read_effective(best.clone(), manual.clone()) else { return Ok(()) };
    let parts = |m: &Option<model::ReadMark>| {
        (m.as_ref().map(|m| m.0), m.as_ref().map(|m| m.1.clone()), m.as_ref().map(|m| m.2.to_string()))
    };
    let ((ma, mi, mh), (sa, si, sh)) = (parts(&best), parts(&manual));
    exec(
        conn,
        "INSERT INTO read_state (channel_id, account_id, reader_member_id, last_read_message_id, last_read_message_at,
            mark_at, mark_id, mark_hlc, set_at, set_id, set_hlc, state_ok)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, 1)
         ON CONFLICT(channel_id, account_id, reader_member_id) DO UPDATE SET
           last_read_message_id = excluded.last_read_message_id, last_read_message_at = excluded.last_read_message_at,
           mark_at = excluded.mark_at, mark_id = excluded.mark_id, mark_hlc = excluded.mark_hlc,
           set_at = excluded.set_at, set_id = excluded.set_id, set_hlc = excluded.set_hlc, state_ok = 1",
        params![channel, account, reader, effective.1, effective.0, ma, mi, mh, sa, si, sh],
    )?;
    Ok(())
}

/// Best mark and latest manual set for one read-state key, from every read op in the log.
fn read_state_scan(
    conn: &Connection,
    account: &str,
    channel: &str,
    reader: &str,
) -> anyhow::Result<(Option<model::ReadMark>, Option<model::ReadMark>)> {
    let mut st = conn.prepare_cached(
        "SELECT id FROM op WHERE kind IN ('read.mark','read.set') AND account_id = ?1 AND status = 'applied'
            AND json_extract(payload, '$.channel_id') = ?2 AND coalesce(json_extract(payload, '$.reader_member_id'), '') = ?3",
    )?;
    let ids: Vec<String> = st.query_map(params![account, channel, reader], |r| r.get(0))?.collect::<Result<_, _>>()?;
    let mut marks = Vec::new();
    let mut manual: Option<model::ReadMark> = None;
    for o in ids.iter().filter_map(|i| oplog::by_id(conn, i).ok().flatten()) {
        let m = model::read_mark_of(&o);
        if o.kind == "read.mark" {
            marks.push(m);
        } else if manual.as_ref().is_none_or(|cur| m.2 > cur.2) {
            manual = Some(m);
        }
    }
    Ok((model::read_best(marks, manual.as_ref()), manual))
}

// ─── rebuild ─────────────────────────────────────────────────────────────────

/// Tables `rebuild` clears (everything derived from the op log).
pub(crate) const DERIVED: &[&str] = &[
    "system",
    "member",
    "member_group",
    "group_membership",
    "custom_state",
    "field_def",
    "field_value",
    "switch",
    "front_interval",
    "front_daily",
    "front_review",
    "space",
    "space_member",
    "channel",
    "channel_permission",
    "message",
    "message_segment",
    "message_segment_author",
    "message_author",
    "message_revision",
    "reaction",
    "mention",
    "read_state",
    "attachment",
    "item_attachment",
    "post",
    "post_author",
    "post_revision",
    "highlight",
    "relationship_type",
    "relationship",
    "member_list",
    "member_list_item",
    "feed",
    "bucket",
    "bucket_assignment",
    "follow",
    "draft",
    "stage",
    "pref",
    "custom_emoji",
];

/// Secondary indexes on derived tables that projecting never reads through (only the read APIs
/// do). A rebuild drops them for the replay and creates them again at the end (SPEC §9).
const BULK_INDEXES: &[&str] = &["message_channel_time", "message_reply", "message_author_member", "msa_member"];

thread_local! {
    /// While a rebuild replays the log: the seq of the op being projected. The front is refolded
    /// from the log as it was then, not from ops that came later, so review cards (never
    /// withdrawn) come out as they did live.
    static REPLAYED_UPTO: std::cell::Cell<i64> = const { std::cell::Cell::new(i64::MAX) };
    /// Set while [`rebuild_timed`] replays the log on this thread.
    static REBUILDING: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    /// During a rebuild: the entities with more than one op. Any other entity's op is its only
    /// one, so it can be projected without reading it back from the log.
    static SINGLE_OP: std::cell::RefCell<Option<std::sync::Arc<std::collections::HashSet<String>>>> = const { std::cell::RefCell::new(None) };
}

/// Rebuild every projection from the op log (`chorus-server rebuild`).
/// A slice of the log for a rebuild: each op, with its entity's row when already prepared.
type Batch = Vec<(Op, Option<Option<Prepared>>)>;

/// For a rebuild's reader thread: the row of an entity whose create is its only op, as
/// [`after_insert`] would project it (`Some(None)`: nothing to write), or `None` to leave the op
/// to `after_insert`.
fn prepare_single(
    conn: &Connection,
    multi: &std::collections::HashSet<String>,
    o: &Op,
) -> anyhow::Result<Option<Option<Prepared>>> {
    let Ok(Known::Yes(spec)) = op::validate(o) else { return Ok(None) };
    if !matches!(spec.action, Action::Create | Action::Append) {
        return Ok(None);
    }
    match o.entity() {
        Some(id) if !id.is_empty() && !multi.contains(id) => Ok(Some(prepare(conn, spec.table, id, vec![o.clone()])?)),
        _ => Ok(None),
    }
}

pub fn rebuild(conn: &mut Connection) -> anyhow::Result<u64> {
    rebuild_timed(conn, &mut |_, _| {})
}

/// Where an interrupted [`rebuild_swap`] left the database it was replacing.
pub fn set_aside_path(path: &std::path::Path) -> std::path::PathBuf {
    path.with_extension("db.before-rebuild")
}

/// Rebuild the database file at `path` with the server stopped (`chorus-server rebuild`,
/// R19): the projections are built in a fresh file with no journal and no syncing, checked,
/// then swapped in — so no WAL of every projected row is written and committed (that commit
/// alone took 20 s at 1M ops on Windows). The result is the same as [`rebuild`]; `path` is
/// only replaced once the fresh file is complete. Refuses if anything else has the database
/// open (a running server). Returns the ops re-projected.
pub fn rebuild_swap(path: &std::path::Path) -> anyhow::Result<u64> {
    rebuild_swap_timed(path, &mut |_, _| {})
}

pub fn rebuild_swap_timed(
    path: &std::path::Path,
    time: &mut dyn FnMut(&str, std::time::Duration),
) -> anyhow::Result<u64> {
    use anyhow::{Context, ensure};
    let fresh = path.with_extension("db.rebuild");
    let aside = set_aside_path(path);
    ensure!(
        !aside.exists(),
        "{} exists: an earlier rebuild was interrupted; start the server once (it puts it back) or move it",
        aside.display()
    );
    for stale in [fresh.clone(), fresh.with_extension("rebuild-journal")] {
        let _ = std::fs::remove_file(stale);
    }
    // the source, locked for the whole run: an idle connection elsewhere (a running server)
    // makes this fail instead of racing the swap
    let mut src = Connection::open(path).with_context(|| format!("opening {}", path.display()))?;
    src.execute_batch("PRAGMA busy_timeout = 0; PRAGMA main.locking_mode = EXCLUSIVE")?;
    src.execute_batch("BEGIN EXCLUSIVE; COMMIT")
        .map_err(|_| anyhow::anyhow!("the database is in use (is the server running?); stop it first"))?;
    // the fresh file gets this build's schema, so the source must have it too
    ensure!(crate::db::pending_migrations(&src)? == 0, "the database needs migrating first (start the server once)");
    // fold the WAL into the file, so the old file is complete on its own while it waits aside
    src.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()))?;
    let t = std::time::Instant::now();
    let result = (|src: &mut Connection| -> anyhow::Result<u64> {
        {
            let mut out = Connection::open(&fresh)?;
            out.set_prepared_statement_cache_capacity(256);
            out.execute_batch(
                "PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF; PRAGMA foreign_keys = OFF;
                 PRAGMA temp_store = MEMORY; PRAGMA locking_mode = EXCLUSIVE",
            )?;
            crate::db::migrate(&mut out)?;
        }
        // everything no projection derives, copied as it is (in one pass, through the source's
        // connection, which holds the lock)
        src.execute("ATTACH DATABASE ?1 AS fresh", [fresh.to_string_lossy().as_ref()])?;
        src.execute_batch("PRAGMA fresh.journal_mode = OFF; PRAGMA fresh.synchronous = OFF")?;
        let copied = (|| -> anyhow::Result<Vec<(String, i64)>> {
            let tables: Vec<(String, String)> = src
                .prepare("SELECT name, coalesce(sql, '') FROM fresh.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")?
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect::<Result<_, _>>()?;
            let virtuals: Vec<&str> = tables
                .iter()
                .filter(|(_, sql)| sql.to_ascii_uppercase().starts_with("CREATE VIRTUAL TABLE"))
                .map(|(n, _)| n.as_str())
                .collect();
            let mut counts = Vec::new();
            src.execute_batch("BEGIN")?;
            for (name, sql) in &tables {
                let shadow = virtuals.iter().any(|v| name.starts_with(&format!("{v}_")));
                if DERIVED.contains(&name.as_str())
                    || shadow
                    || sql.to_ascii_uppercase().starts_with("CREATE VIRTUAL TABLE")
                {
                    continue;
                }
                let cols: Vec<String> = src
                    .prepare(&format!("PRAGMA fresh.table_info(\"{name}\")"))?
                    .query_map([], |r| r.get::<_, String>(1))?
                    .collect::<Result<_, _>>()?;
                let cols = cols.iter().map(|c| format!("\"{c}\"")).collect::<Vec<_>>().join(", ");
                src.execute(
                    &format!("INSERT OR REPLACE INTO fresh.\"{name}\" ({cols}) SELECT {cols} FROM main.\"{name}\""),
                    [],
                )?;
                let n: i64 = src.query_row(&format!("SELECT count(*) FROM main.\"{name}\""), [], |r| r.get(0))?;
                counts.push((name.clone(), n));
            }
            src.execute_batch("COMMIT")?;
            Ok(counts)
        })();
        if copied.is_err() && !src.is_autocommit() {
            let _ = src.execute_batch("ROLLBACK");
        }
        src.execute_batch("DETACH DATABASE fresh")?;
        let copied = copied?;
        time("(copy the log and server tables)", t.elapsed());
        let n = {
            let mut out = Connection::open(&fresh)?;
            out.set_prepared_statement_cache_capacity(256);
            out.execute_batch(
                "PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF; PRAGMA foreign_keys = ON;
                 PRAGMA temp_store = MEMORY; PRAGMA locking_mode = EXCLUSIVE",
            )?;
            // the op log is read from the source (the same log, and held locked) while the
            // fresh file is written: a second connection to a journal-less file would block
            let lent = std::mem::replace(src, Connection::open_in_memory()?);
            LOG_READER.with(|r| *r.borrow_mut() = Some(lent));
            let rebuilt = rebuild_timed(&mut out, time);
            *src = LOG_READER.with(|r| r.borrow_mut().take()).context("the rebuild kept the source connection")?;
            let n = rebuilt?;
            let t = std::time::Instant::now();
            let check: String = out.query_row("PRAGMA quick_check", [], |r| r.get(0))?;
            ensure!(check == "ok", "the rebuilt database fails its check: {check}");
            for (name, want) in &copied {
                let got: i64 = out.query_row(&format!("SELECT count(*) FROM \"{name}\""), [], |r| r.get(0))?;
                ensure!(got == *want, "{name} lost rows in the rebuild ({got} of {want})");
            }
            time("(check)", t.elapsed());
            // the server runs it in WAL mode; switching is instant on a file nobody else has open
            out.execute_batch("PRAGMA locking_mode = NORMAL")?;
            let mode: String = out.query_row("PRAGMA journal_mode = WAL", [], |r| r.get(0))?;
            ensure!(mode.eq_ignore_ascii_case("wal"), "couldn't switch the rebuilt database to WAL ({mode})");
            n
        };
        Ok(n)
    })(&mut src);
    drop(src);
    let n = match result {
        Ok(n) => n,
        Err(e) => {
            let _ = std::fs::remove_file(&fresh);
            return Err(e);
        }
    };
    // swap: the old file aside, the new one in, then the old one gone (renaming over an existing
    // file fails on Windows; an interruption here is undone at the next start, see lib.rs)
    let t = std::time::Instant::now();
    // the old file's WAL must not outlive it: under the same name, SQLite would replay it into
    // the new file. It was checkpointed and truncated above; anything in it now is a writer
    // that got in after the lock was let go, so stop rather than lose that
    for ext in ["-wal", "-shm"] {
        let side = std::path::PathBuf::from(format!("{}{ext}", path.display()));
        if let Ok(meta) = std::fs::metadata(&side) {
            if ext == "-wal" && meta.len() > 0 {
                let _ = std::fs::remove_file(&fresh);
                anyhow::bail!(
                    "{} was written to during the rebuild; nothing was changed, run it again with the server stopped",
                    path.display()
                );
            }
            std::fs::remove_file(&side)?;
        }
    }
    std::fs::rename(path, &aside).with_context(|| format!("moving {} aside", path.display()))?;
    if let Err(e) = std::fs::rename(&fresh, path) {
        std::fs::rename(&aside, path)?;
        return Err(e).context("putting the rebuilt database in place");
    }
    std::fs::remove_file(&aside)?;
    time("(swap)", t.elapsed());
    Ok(n)
}

/// [`rebuild`], reporting how long each op's projection took (by kind; `tests/perf.rs`).
pub fn rebuild_timed(conn: &mut Connection, time: &mut dyn FnMut(&str, std::time::Duration)) -> anyhow::Result<u64> {
    // one transaction rewrites every projection: with the default 2 MB page cache it spills
    // to the WAL over and over, so give it room for the duration (256 MB at most)
    let cache: i64 = conn.query_row("PRAGMA cache_size", [], |r| r.get(0))?;
    conn.execute_batch("PRAGMA cache_size = -262144")?;
    let result = rebuild_in(conn, time);
    conn.execute_batch(&format!("PRAGMA cache_size = {cache}"))?;
    result
}

fn rebuild_in(conn: &mut Connection, time: &mut dyn FnMut(&str, std::time::Duration)) -> anyhow::Result<u64> {
    let tx = conn.transaction()?;
    let t = std::time::Instant::now();
    for t in DERIVED {
        tx.execute(&format!("DELETE FROM {t}"), [])?;
    }
    time("(clear)", t.elapsed());
    // message_fts keeps its own copy of the text (not external content), so 'delete-all' doesn't
    // apply to it, and a DELETE takes it apart row by row (3 s at 1M ops): make it anew instead
    let t = std::time::Instant::now();
    for name in ["message_fts", "post_fts"] {
        let fts: String =
            tx.query_row("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?1", [name], |r| r.get(0))?;
        tx.execute_batch(&format!("DROP TABLE {name}; {fts};"))?;
    }
    time("(clear search index)", t.elapsed());
    // indexes no projection reads through: built again after the replay, one sort each instead
    // of a million scattered inserts
    let t = std::time::Instant::now();
    let mut dropped = Vec::new();
    for name in BULK_INDEXES {
        let sql: Option<String> = tx
            .query_row("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?1", [name], |r| r.get(0))
            .optional()?;
        if let Some(sql) = sql {
            tx.execute_batch(&format!("DROP INDEX {name}"))?;
            dropped.push(sql);
        }
    }
    time("(drop indexes)", t.elapsed());
    let mut n = 0u64;
    let mut seq = 0i64;
    REBUILDING.with(|r| r.set(true));
    let t = std::time::Instant::now();
    let multi: std::sync::Arc<std::collections::HashSet<String>> = {
        let mut st = tx.prepare(
            "SELECT entity_id FROM op WHERE status = 'applied' AND entity_id IS NOT NULL
             GROUP BY entity_id HAVING count(*) > 1",
        )?;
        std::sync::Arc::new(st.query_map([], |r| r.get(0))?.collect::<Result<_, _>>()?)
    };
    time("(entity counts)", t.elapsed());
    SINGLE_OP.with(|m| *m.borrow_mut() = Some(multi.clone()));
    // time the writer spends waiting for the reader thread
    let mut waited = std::time::Duration::ZERO;
    let replay_t = std::time::Instant::now();
    let replayed = (|| -> anyhow::Result<()> {
        // an op, and its entity's row if the reader thread already prepared it
        let mut apply = |batch: &Batch| -> anyhow::Result<()> {
            for (o, ready) in batch {
                let t = std::time::Instant::now();
                REPLAYED_UPTO.with(|u| u.set(o.seq.unwrap_or(i64::MAX)));
                match ready {
                    Some(Some(p)) => write(&tx, p)?,
                    Some(None) => {}
                    None => after_insert(&tx, o)?,
                }
                time(&o.kind, t.elapsed());
                n += 1;
            }
            Ok(())
        };
        // decode the log on a second (read-only) connection while this one writes; the op table
        // doesn't change during a rebuild, so its committed snapshot is the same log. A caller
        // may lend one (rebuild_swap: the source file, which it holds locked)
        let lent = LOG_READER.with(|r| r.borrow_mut().take());
        let is_lent = lent.is_some();
        let reader = match lent {
            Some(c) => Some(c),
            None => match tx.path().filter(|p| !p.is_empty()) {
                Some(path) => Some(Connection::open_with_flags(
                    path,
                    rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
                )?),
                None => None,
            },
        };
        match reader {
            Some(conn) => std::thread::scope(|s| {
                let (send, recv) = std::sync::mpsc::sync_channel::<anyhow::Result<Batch>>(4);
                // Applied batches go back to be freed on the thread that allocated them: freeing
                // another thread's allocations contends for its allocator lock (5 s at 1M ops).
                let (spent, spent_back) = std::sync::mpsc::channel::<Batch>();
                let multi = multi.clone();
                let handle = s.spawn(move || {
                    let read = || -> anyhow::Result<()> {
                        let mut seq = 0;
                        loop {
                            spent_back.try_iter().for_each(drop);
                            let batch = oplog::applied_after(&conn, seq, 1000)?;
                            let Some(last) = batch.last() else { return Ok(()) };
                            seq = last.seq.unwrap_or(seq);
                            let mut ready = Batch::with_capacity(batch.len());
                            for o in batch {
                                let p = prepare_single(&conn, &multi, &o)?;
                                ready.push((o, p));
                            }
                            if send.send(Ok(ready)).is_err() {
                                return Ok(()); // the writer stopped early
                            }
                        }
                    };
                    if let Err(e) = read() {
                        let _ = send.send(Err(e));
                    }
                    drop(send);
                    spent_back.into_iter().for_each(drop); // until the writer is done
                    conn
                });
                let applied = (|| -> anyhow::Result<()> {
                    loop {
                        let t = std::time::Instant::now();
                        let Ok(batch) = recv.recv() else { return Ok(()) };
                        waited += t.elapsed();
                        let batch = batch?;
                        apply(&batch)?;
                        let _ = spent.send(batch);
                    }
                })();
                // let the reader finish (it stops once nobody listens), then take its connection back
                drop(recv);
                drop(spent);
                let conn = handle.join().unwrap_or_else(|e| std::panic::resume_unwind(e));
                if is_lent {
                    LOG_READER.with(|r| *r.borrow_mut() = Some(conn));
                }
                applied
            }),
            None => loop {
                let batch = oplog::applied_after(&tx, seq, 1000)?;
                let Some(last) = batch.last() else { return Ok(()) };
                seq = last.seq.unwrap_or(seq);
                apply(&batch.into_iter().map(|o| (o, None)).collect())?;
            },
        }
    })();
    REBUILDING.with(|r| r.set(false));
    REPLAYED_UPTO.with(|u| u.set(i64::MAX));
    SINGLE_OP.with(|m| *m.borrow_mut() = None);
    replayed?;
    time("(waiting for the log)", waited);
    time("(replay in all)", replay_t.elapsed());
    let t = std::time::Instant::now();
    for sql in dropped {
        tx.execute_batch(&sql)?;
    }
    time("(indexes)", t.elapsed());
    let t = std::time::Instant::now();
    tx.execute(
        &format!("{THREAD_LINK} WHERE id IN (SELECT parent_message_id FROM channel WHERE kind = 'thread')"),
        [],
    )?;
    time("(thread links)", t.elapsed());
    let t = std::time::Instant::now();
    rebuild_daily(&tx)?;
    time("(daily totals)", t.elapsed());
    let t = std::time::Instant::now();
    tx.execute(
        "INSERT INTO message_fts(rowid, text, cw) SELECT rowid, text, coalesce(cw, '') FROM message WHERE deleted_at IS NULL",
        [],
    )?;
    tx.execute(POST_FTS_FILL, [])?;
    time("(search index)", t.elapsed());
    let t = std::time::Instant::now();
    tx.commit()?;
    time("(commit)", t.elapsed());
    let _ = columns; // kept for diagnostics
    Ok(n)
}
