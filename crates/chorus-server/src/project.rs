//! SQL projections (docs/DATA_MODEL.md §4).
//!
//! Every accepted op re-projects what it touches by running `chorus_core::model` over the ops of
//! that entity (or element set, or account front). The SQL rows are therefore the reference
//! model's output by construction; `tests/projection.rs` checks it on random op sets.

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
        Action::Front => account_front(conn, &o.scope),
        Action::SetAdd | Action::SetRemove => element_set(conn, spec.table, o),
        Action::Special => special(conn, o),
        Action::Admin => Ok(()),
        _ => entity(conn, spec.table, o.entity().unwrap_or("")),
    }
}

// ─── entity rows ─────────────────────────────────────────────────────────────

fn columns(conn: &Connection, table: &str) -> anyhow::Result<Vec<String>> {
    let mut st = conn.prepare_cached(&format!("PRAGMA table_info({table})"))?;
    let v = st.query_map([], |r| r.get::<_, String>(1))?.collect::<Result<_, _>>()?;
    Ok(v)
}

/// Generated (virtual/stored) columns can't be written.
fn writable(conn: &Connection, table: &str) -> anyhow::Result<Vec<String>> {
    let mut st = conn.prepare_cached(&format!("PRAGMA table_xinfo({table})"))?;
    let v = st
        .query_map([], |r| Ok((r.get::<_, String>(1)?, r.get::<_, i64>(6)?)))?
        .filter_map(|r| r.ok())
        .filter(|(_, hidden)| *hidden == 0)
        .map(|(n, _)| n)
        .collect();
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

fn account_of(scope: &str) -> Option<&str> {
    scope.strip_prefix("account:")
}

/// Re-project one row of `table` from all ops on `id`.
pub fn entity(conn: &Connection, table: &str, id: &str) -> anyhow::Result<()> {
    if id.is_empty() {
        return Ok(());
    }
    let ops: Vec<Op> = oplog::for_entity(conn, id)?
        .into_iter()
        .filter(|o| {
            op::spec(&o.kind)
                .is_some_and(|s| s.table == table && s.action != Action::SetAdd && s.action != Action::SetRemove)
        })
        .collect();
    let proj = model::project(ops.iter());
    let Some(row) = proj.row(table, id).filter(|r| r.exists) else {
        return Ok(()); // not created yet (fields wait in the log until the create arrives)
    };
    let old_thread_parent = if table == "channel" { thread_parent(conn, id)? } else { None };
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
    }
    if table == "custom_emoji" {
        vals.entry("created_by".into()).or_insert(Value::from(first.and_then(|f| f.account_id.clone())));
    }
    let cols = writable(conn, table)?;
    let (names, values): (Vec<String>, Vec<Sql>) =
        vals.iter().filter(|(k, _)| cols.contains(k)).map(|(k, v)| (k.clone(), to_sql(v))).unzip();
    upsert(conn, table, &["id"], &names, values)?;
    match table {
        "message" => {
            message_extras(conn, id, row.fields.get("authors"), &row.fields)?;
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
        "post" => authors(conn, "post_author", "post_id", id, row.fields.get("authors"))?,
        "member_group" => {
            group_parents(conn, account_of(&first.map(|f| f.scope.clone()).unwrap_or_default()).unwrap_or(""))?
        }
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
fn refresh_thread_link(conn: &Connection, parent_id: &str) -> anyhow::Result<()> {
    conn.execute(
        "UPDATE message SET thread_channel_id = (
           SELECT t.id FROM channel t JOIN channel source ON source.id = message.channel_id
           WHERE t.kind = 'thread' AND t.parent_message_id = message.id AND t.space_id = source.space_id
             AND t.deleted_at IS NULL AND t.archived_at IS NULL
           ORDER BY t.created_at, t.id LIMIT 1
         ) WHERE id = ?1",
        [parent_id],
    )?;
    Ok(())
}

fn upsert(conn: &Connection, table: &str, key: &[&str], names: &[String], values: Vec<Sql>) -> anyhow::Result<()> {
    let placeholders: Vec<String> = (1..=names.len()).map(|i| format!("?{i}")).collect();
    let updates: Vec<String> =
        names.iter().filter(|n| !key.contains(&n.as_str())).map(|n| format!("{n} = excluded.{n}")).collect();
    let sql = format!(
        "INSERT INTO {table} ({}) VALUES ({}) ON CONFLICT({}) DO {}",
        names.join(", "),
        placeholders.join(", "),
        key.join(", "),
        if updates.is_empty() { "NOTHING".into() } else { format!("UPDATE SET {}", updates.join(", ")) }
    );
    conn.execute(&sql, params_from_iter(values))?;
    Ok(())
}

fn authors(conn: &Connection, table: &str, key: &str, id: &str, a: Option<&Value>) -> anyhow::Result<()> {
    conn.execute(&format!("DELETE FROM {table} WHERE {key} = ?1"), [id])?;
    for (i, m) in a.and_then(Value::as_array).into_iter().flatten().enumerate() {
        if let Some(m) = m.as_str() {
            conn.execute(
                &format!("INSERT OR IGNORE INTO {table} ({key}, member_id, position) VALUES (?1, ?2, ?3)"),
                params![id, m, i as i64],
            )?;
        }
    }
    Ok(())
}

/// Authors, segments (D-045), mentions and the FTS index for a message.
fn message_extras(
    conn: &Connection,
    id: &str,
    a: Option<&Value>,
    f: &serde_json::Map<String, Value>,
) -> anyhow::Result<()> {
    authors(conn, "message_author", "message_id", id, a)?;
    let text_s = f.get("text").and_then(Value::as_str).unwrap_or("");
    conn.execute("DELETE FROM message_segment WHERE message_id = ?1", [id])?;
    conn.execute("DELETE FROM message_segment_author WHERE message_id = ?1", [id])?;
    let segs: Vec<Value> = match f.get("segments").and_then(Value::as_array) {
        Some(s) if !s.is_empty() => s.clone(),
        _ => vec![
            serde_json::json!({"offset": 0, "length": text::utf16_len(text_s), "authors": a.cloned().unwrap_or_default()}),
        ],
    };
    for (i, s) in segs.iter().enumerate() {
        let off = s.get("offset").and_then(Value::as_u64).unwrap_or(0) as u32;
        let len = s.get("length").and_then(Value::as_u64).unwrap_or(0) as u32;
        conn.execute(
            "INSERT INTO message_segment (message_id, idx, offset_u16, length_u16, text) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![id, i as i64, off, len, text::utf16_slice(text_s, off, len)],
        )?;
        for (p, m) in s.get("authors").and_then(Value::as_array).into_iter().flatten().enumerate() {
            if let Some(m) = m.as_str() {
                conn.execute(
                    "INSERT OR IGNORE INTO message_segment_author (message_id, idx, member_id, position) VALUES (?1, ?2, ?3, ?4)",
                    params![id, i as i64, m, p as i64],
                )?;
            }
        }
    }
    conn.execute("DELETE FROM mention WHERE source_type = 'message' AND source_id = ?1", [id])?;
    let ents: Vec<Entity> = f.get("entities").and_then(|e| serde_json::from_value(e.clone()).ok()).unwrap_or_default();
    for e in ents {
        if let text::EntityKind::Mention { target_type, target_id } = e.kind {
            let tt = serde_json::to_value(target_type)?.as_str().unwrap_or("member").to_string();
            conn.execute(
                "INSERT OR IGNORE INTO mention (source_type, source_id, target_type, target_id) VALUES ('message', ?1, ?2, ?3)",
                params![id, tt, target_id.unwrap_or_default()],
            )?;
        }
    }
    // FTS keeps its own copy keyed by the message rowid; replace it (deleted messages drop out)
    let rowid: Option<i64> =
        conn.query_row("SELECT rowid FROM message WHERE id = ?1", [id], |r| r.get(0)).optional()?;
    if let Some(rowid) = rowid {
        conn.execute("DELETE FROM message_fts WHERE rowid = ?1", [rowid])?;
        conn.execute(
            "INSERT INTO message_fts(rowid, text, cw) SELECT rowid, text, coalesce(cw, '') FROM message WHERE rowid = ?1 AND deleted_at IS NULL",
            [rowid],
        )?;
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
        conn.execute("UPDATE member_group SET effective_parent_id = ?2 WHERE id = ?1", params![id, p])?;
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
    upsert(conn, table, &key_cols, &names, values)?;
    if table == "space_member" {
        space_access(conn, o, &want[1], e.is_present())?;
    }
    Ok(())
}

/// Space membership grants scope access — but only when the op's author may manage the space
/// (its owner, or the account joining a DM/space it created itself).
fn space_access(conn: &Connection, o: &Op, account: &str, present: bool) -> anyhow::Result<()> {
    let space = o.entity().unwrap_or("");
    let owner: Option<String> =
        conn.query_row("SELECT owner_account_id FROM space WHERE id = ?1", [space], |r| r.get(0)).optional()?;
    let author = o.account_id.clone().unwrap_or_default();
    let manages = match &owner {
        Some(ow) => *ow == author,
        None => author == account,
    };
    if manages {
        if present {
            ingest::grant(conn, account, &o.scope)?;
        } else if Some(account) != Some(author.as_str()) {
            conn.execute("DELETE FROM scope_access WHERE account_id = ?1 AND scope = ?2", params![account, o.scope])?;
        }
    }
    Ok(())
}

// ─── front ───────────────────────────────────────────────────────────────────

/// Rewrite an account's switch log, intervals, daily totals and review cards. Full refold: fine
/// at thousands of switches; see NOTES for the incremental plan when it isn't.
pub fn account_front(conn: &Connection, scope: &str) -> anyhow::Result<()> {
    let Some(account) = account_of(scope) else { return Ok(()) };
    let ops: Vec<FrontOp> =
        oplog::for_scope_kinds(conn, scope, "front.")?.iter().filter_map(|o| FrontOp::from_op(o).ok()).collect();
    let folded = front::fold(&ops);
    conn.execute("DELETE FROM switch WHERE account_id = ?1", [account])?;
    conn.execute("DELETE FROM front_interval WHERE account_id = ?1", [account])?;
    conn.execute("DELETE FROM front_daily WHERE account_id = ?1", [account])?;
    {
        let mut ins = conn.prepare_cached(
            "INSERT INTO switch (id, account_id, kind, occurred_at, tz_offset_min, device_id, based_on, entries,
                resulting_front, note, notify, was_offline, retracted, amended)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)",
        )?;
        for s in &folded.switches {
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
        }
        let mut ins = conn.prepare_cached(
            "INSERT INTO front_interval (id, account_id, subject_type, subject_id, level, is_primary, position,
                start_at, end_at, start_switch_id, end_switch_id, start_tz_offset_min)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
        )?;
        for i in &folded.intervals {
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
        }
    }
    // Local days: the account's most recent UTC offset (no tz database in core; D-058 note).
    let tz = folded.switches.last().map(|s| s.tz_offset_min).unwrap_or(0);
    let now = crate::now_ms();
    let mut ins = conn.prepare_cached(
        "INSERT INTO front_daily (account_id, day, subject_type, subject_id, level, seconds, as_primary_seconds)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
    )?;
    for d in front::daily(&folded.intervals, now, |_| tz) {
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
    for r in front::reviews(&ops, &folded, front::DEFAULT_REVIEW_WINDOW_MS) {
        conn.execute(
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
                conn.execute(
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
                upsert(conn, "channel_permission", &["channel_id", "target_type", "target_id"], &names, vals)?;
            }
            Ok(())
        }
        "space.set_role" => {
            conn.execute(
                "UPDATE space_member SET role = ?3 WHERE space_id = ?1 AND account_id = ?2",
                params![o.entity(), p("account_id"), p("role")],
            )?;
            Ok(())
        }
        "space.set_roles" => {
            conn.execute(
                "UPDATE space SET roles = ?2 WHERE id = ?1",
                params![o.entity(), o.payload.get("roles").cloned().unwrap_or_default().to_string()],
            )?;
            Ok(())
        }
        "front.review_resolve" => {
            conn.execute(
                "UPDATE front_review SET resolution = ?2, resolved_at = ?3 WHERE id = ?1",
                params![p("review_id"), p("resolution"), o.time()],
            )?;
            Ok(())
        }
        "read.mark" | "read.set" => read_state(conn, o),
        "highlight.reorder" => {
            conn.execute(
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
    upsert(conn, table, &key_cols, &names, vals)
}

/// Read states: all read ops for (channel, account, reader) through the model (SYNC.md §5.5).
fn read_state(conn: &Connection, o: &Op) -> anyhow::Result<()> {
    let channel = o.payload.get("channel_id").and_then(Value::as_str).unwrap_or("");
    let reader = o.payload.get("reader_member_id").and_then(Value::as_str).unwrap_or("");
    let account = o.account_id.clone().unwrap_or_default();
    let mut st = conn.prepare_cached(
        "SELECT id FROM op WHERE kind IN ('read.mark','read.set') AND account_id = ?1
            AND json_extract(payload, '$.channel_id') = ?2 AND coalesce(json_extract(payload, '$.reader_member_id'), '') = ?3",
    )?;
    let ids: Vec<String> = st.query_map(params![account, channel, reader], |r| r.get(0))?.collect::<Result<_, _>>()?;
    let ops: Vec<Op> = ids.iter().filter_map(|i| oplog::by_id(conn, i).ok().flatten()).collect();
    let proj = model::project(ops.iter());
    let key = format!("{channel}|{account}|{reader}");
    if let Some(row) = proj.row("read_state", &key) {
        conn.execute(
            "INSERT INTO read_state (channel_id, account_id, reader_member_id, last_read_message_id, last_read_message_at)
             VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(channel_id, account_id, reader_member_id) DO UPDATE SET
               last_read_message_id = excluded.last_read_message_id, last_read_message_at = excluded.last_read_message_at",
            params![
                channel,
                account,
                reader,
                row.fields.get("last_read_message_id").and_then(Value::as_str).unwrap_or(""),
                row.fields.get("last_read_message_at").and_then(Value::as_i64).unwrap_or(0)
            ],
        )?;
    }
    Ok(())
}

// ─── rebuild ─────────────────────────────────────────────────────────────────

/// Tables `rebuild` clears (everything derived from the op log).
const DERIVED: &[&str] = &[
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

/// Rebuild every projection from the op log (`chorus-server rebuild`).
pub fn rebuild(conn: &mut Connection) -> anyhow::Result<u64> {
    let tx = conn.transaction()?;
    for t in DERIVED {
        tx.execute(&format!("DELETE FROM {t}"), [])?;
    }
    tx.execute("INSERT INTO message_fts(message_fts) VALUES ('delete-all')", []).ok();
    let mut n = 0u64;
    let mut seq = 0i64;
    loop {
        let batch: Vec<Op> = {
            let mut st =
                tx.prepare("SELECT id FROM op WHERE seq > ?1 AND status = 'applied' ORDER BY seq LIMIT 1000")?;
            let ids: Vec<String> = st.query_map([seq], |r| r.get(0))?.collect::<Result<_, _>>()?;
            ids.iter().filter_map(|i| oplog::by_id(&tx, i).ok().flatten()).collect()
        };
        if batch.is_empty() {
            break;
        }
        for o in &batch {
            seq = o.seq.unwrap_or(seq);
            after_insert(&tx, o)?;
            n += 1;
        }
    }
    tx.commit()?;
    let _ = columns; // kept for diagnostics
    Ok(n)
}
