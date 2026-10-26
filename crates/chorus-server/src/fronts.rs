//! Rebuildable, own-account named-front projections. No function here is a follower view.
//!
//! The append-only log remains authoritative. SQL stores readable configuration/presence and
//! a private derived cache of the core state and append guards. History is read through core.

use std::{
    borrow::Cow,
    collections::{BTreeMap, BTreeSet},
};

use chorus_core::front_history::Event;
use chorus_core::front_projection::{AppendGuard, Current, Index};
use chorus_core::front_system::{Front, Presence, ResolvedFront};
use chorus_core::op::Op;
use rusqlite::{Connection, OptionalExtension, params};

use crate::oplog;

pub fn write_denied(conn: &Connection, author: &str, op: &Op) -> anyhow::Result<Option<String>> {
    if !op.kind.starts_with("front.") {
        return Ok(None);
    }
    if op.scope != format!("account:{author}") {
        return Ok(Some("front changes belong to the owning account".into()));
    }
    let Ok(Some(event)) = Event::from_op(author, op) else { return Ok(None) };
    use chorus_core::front_history::{Action, Change};
    let subjects: Vec<(chorus_core::front::SubjectType, &str)> = match &event.action {
        Action::Change(Change::Switch { entries, .. } | Change::Amend { entries: Some(entries), .. }) => {
            entries.iter().map(|e| (e.subject_type, e.subject_id.as_str())).collect()
        }
        Action::Change(Change::Add { entry, .. }) => vec![(entry.subject_type, &entry.subject_id)],
        Action::Change(Change::Update { subject_type, subject_id, .. }) => {
            vec![(*subject_type, subject_id)]
        }
        // Removing this account's saved reference never grants access to its subject, and
        // must remain possible when an offline reference later resolves to a foreign owner.
        Action::Change(Change::Remove { .. }) => vec![],
        _ => vec![],
    };
    for (kind, id) in subjects {
        if subject_owner(conn, kind, id)?.is_some_and(|owner| owner != author) {
            return Ok(Some("front subjects belong to the owning account".into()));
        }
    }
    Ok(None)
}

fn subject_owner(conn: &Connection, kind: chorus_core::front::SubjectType, id: &str) -> anyhow::Result<Option<String>> {
    let table = match kind {
        chorus_core::front::SubjectType::Member => "member",
        chorus_core::front::SubjectType::Group => "member_group",
        chorus_core::front::SubjectType::State => "custom_state",
    };
    Ok(conn
        .prepare_cached(&format!("SELECT account_id FROM {table} WHERE id=?1"))?
        .query_row([id], |r| r.get(0))
        .optional()?)
}

/// Unknown/foreign subjects can be retained for late offline parents, but never resolve their
/// names or become follower-visible through an account that does not own them.
pub(crate) fn subject_is_owned(
    conn: &Connection,
    account: &str,
    kind: chorus_core::front::SubjectType,
    id: &str,
) -> anyhow::Result<bool> {
    Ok(subject_owner(conn, kind, id)?.as_deref() == Some(account))
}

pub(crate) fn has_current_reference(conn: &Connection, account: &str, kind: &str, id: &str) -> anyhow::Result<bool> {
    Ok(conn.prepare_cached(
        "SELECT EXISTS(SELECT 1 FROM front_interval WHERE account_id=?1 AND end_at IS NULL AND subject_type=?2 AND subject_id=?3
         UNION ALL SELECT 1 FROM named_front_presence WHERE account_id=?1 AND kind='notifications' AND subject_type=?2 AND subject_id=?3)",
    )?.query_row(params![account,kind,id],|r|r.get(0))?)
}

pub fn owned_presence(conn: &Connection, account: &str, entries: &[Presence]) -> anyhow::Result<Vec<Presence>> {
    let mut own = Vec::new();
    for entry in entries {
        if subject_is_owned(conn, account, entry.subject_type, &entry.subject_id)? {
            own.push(entry.clone());
        }
    }
    Ok(own)
}

// Bump when the stored contract, recognized front kinds/versions or folding semantics change.
// An old cache rebuilds from the log; it is never a migration of user-authored data.
const CACHE_VERSION: i64 = 1;

type Cached = (i64, i64, String, String);

fn cached(conn: &Connection, account: &str) -> anyhow::Result<Option<Cached>> {
    Ok(conn
        .prepare_cached(
            "SELECT projection_version, last_front_seq, projection, append_guard FROM front_system_cache WHERE account_id = ?1",
        )?
        .query_row([account], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
        .optional()?)
}

fn front_seq(conn: &Connection, account: &str, before: Option<i64>) -> anyhow::Result<i64> {
    Ok(conn
        .prepare_cached(
            "SELECT coalesce(max(seq),0) FROM op
             WHERE scope=?1 AND status='applied' AND kind GLOB 'front.*'
               AND (?2 IS NULL OR seq < ?2)",
        )?
        .query_row(params![format!("account:{account}"), before], |r| r.get(0))?)
}

fn current_cache(conn: &Connection, account: &str, row: &Cached) -> anyhow::Result<bool> {
    Ok(row.1 == front_seq(conn, account, None)? && decode(account, row).is_some())
}

fn decode(account: &str, row: &Cached) -> Option<Index> {
    if row.0 != CACHE_VERSION {
        return None;
    }
    let current: Current = serde_json::from_str(&row.2).ok()?;
    let guard: AppendGuard = serde_json::from_str(&row.3).ok()?;
    if !current.system.fronts.contains_key(account)
        || guard.last_write.is_none()
        || current.system.fronts.iter().any(|(id, f)| id != &f.id)
        || current.system.layers.iter().any(|(id, l)| id != &l.id)
        || current.system.resolve() != current.resolved
    {
        return None;
    }
    Some(Index::from_cached(account, current, guard))
}

pub fn account_ops(conn: &Connection, account: &str) -> anyhow::Result<Vec<Op>> {
    oplog::for_scope_kinds(conn, &format!("account:{account}"), "front.")
}

/// Called only after insertion of a new op identity inside the ingest transaction. Legacy
/// switches keep their existing path for unconfigured accounts, without scanning their log.
pub fn after_op(conn: &Connection, op: &Op) -> anyhow::Result<()> {
    let Some(account) = op.scope.strip_prefix("account:") else { return Ok(()) };
    let row = cached(conn, account)?;
    if !matches!(Event::from_op(account, op), Ok(Some(_))) {
        // The watermark covers every applied front op, including opaque/malformed history
        // and reviews. Advance it only after establishing that the preceding prefix is
        // complete; otherwise an ignored event could conceal earlier unprojected valid ops.
        if let Some(row) = row {
            if let Some(seq) = op.seq
                && row.1 == front_seq(conn, account, Some(seq))?
                && decode(account, &row).is_some()
            {
                conn.prepare_cached("UPDATE front_system_cache SET last_front_seq=?2 WHERE account_id=?1")?
                    .execute(params![account, front_seq(conn, account, None)?])?;
            } else {
                rebuild_account(conn, account)?;
            }
        }
        return Ok(());
    }
    if row.is_none() && !chorus_core::front_history::is_named_kind(&op.kind) {
        return Ok(());
    }
    if let (Some(row), Some(seq)) = (&row, op.seq)
        && row.1 == front_seq(conn, account, Some(seq))?
        && let Some(mut index) = decode(account, row)
    {
        let previous = index.current.clone();
        if index.append(op) {
            return write(conn, account, &index, Some(&previous));
        }
    }
    rebuild_account(conn, account)?;
    Ok(())
}

/// Own-account read only. Callers authorize the account before reaching this function. Invalid
/// derived caches and pre-upgrade opaque named-front operations are rebuilt, never discarded.
pub fn current(conn: &Connection, account: &str) -> anyhow::Result<Option<Current>> {
    let row = cached(conn, account)?;
    if let Some(row) = &row
        && row.1 == front_seq(conn, account, None)?
        && let Some(index) = decode(account, row)
    {
        return Ok(Some(index.current));
    }
    if row.is_none() && !has_named_ops(conn, account)? {
        return Ok(None);
    }
    rebuild_account(conn, account)
}

fn has_named_ops(conn: &Connection, account: &str) -> anyhow::Result<bool> {
    Ok(conn
        .prepare_cached(
            "SELECT EXISTS(SELECT 1 FROM op WHERE scope=?1 AND status='applied'
         AND kind IN ('front.define','front.layer','front.change'))",
        )?
        .query_row([format!("account:{account}")], |r| r.get(0))?)
}

/// A bulk rebuild calls this once per configured account after replaying the log, rather than
/// rebuilding its state at every event. Startup also uses it to backfill newly recognized ops.
pub fn rebuild_account(conn: &Connection, account: &str) -> anyhow::Result<Option<Current>> {
    if conn.is_autocommit() {
        let tx = conn.unchecked_transaction()?;
        let current = rebuild_account_in(&tx, account)?;
        tx.commit()?;
        Ok(current)
    } else {
        rebuild_account_in(conn, account)
    }
}

fn rebuild_account_in(conn: &Connection, account: &str) -> anyhow::Result<Option<Current>> {
    let ops = account_ops(conn, account)?;
    let configured = ops.iter().any(|o| {
        chorus_core::front_history::is_named_kind(&o.kind) && Event::from_op(account, o).ok().flatten().is_some()
    });
    if !configured {
        clear_account(conn, account)?;
        return Ok(None);
    }
    let index = Index::from_ops(account, &ops);
    clear_account(conn, account)?;
    write(conn, account, &index, None)?;
    Ok(Some(index.current))
}

pub fn backfill(conn: &Connection) -> anyhow::Result<usize> {
    let accounts: Vec<String> = conn
        .prepare(
            "SELECT DISTINCT substr(scope,9) FROM op
             WHERE status='applied' AND scope LIKE 'account:%'
               AND kind IN ('front.define','front.layer','front.change') ORDER BY scope",
        )?
        .query_map([], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    let mut rebuilt = 0;
    for account in accounts {
        let valid = match cached(conn, &account)? {
            Some(row) => current_cache(conn, &account, &row)?,
            None => false,
        };
        if !valid && rebuild_account(conn, &account)?.is_some() {
            rebuilt += 1;
        }
    }
    Ok(rebuilt)
}

fn clear_account(conn: &Connection, account: &str) -> anyhow::Result<()> {
    for table in [
        "named_front_presence",
        "named_front_purpose",
        "named_front_purpose_config",
        "named_front",
        "front_layer",
        "front_system_cache",
    ] {
        conn.prepare_cached(&format!("DELETE FROM {table} WHERE account_id=?1"))?.execute([account])?;
    }
    Ok(())
}

fn write(conn: &Connection, account: &str, index: &Index, previous: Option<&Current>) -> anyhow::Result<()> {
    for (id, layer) in &index.current.system.layers {
        if previous.is_some_and(|old| old.system.layers.get(id) == Some(layer)) {
            continue;
        }
        conn.prepare_cached("INSERT INTO front_layer(account_id,id,name,level,is_deleted) VALUES(?1,?2,?3,?4,?5) ON CONFLICT(account_id,id) DO UPDATE SET name=excluded.name,level=excluded.level,is_deleted=excluded.is_deleted")?
            .execute(params![account,id,layer.name,layer.level,layer.deleted])?;
    }
    for (id, front) in &index.current.system.fronts {
        let resolved = &index.current.resolved[id];
        let old = previous.and_then(|p| p.system.fronts.get(id));
        let old_resolved = previous.and_then(|p| p.resolved.get(id));
        if old != Some(front) || old_resolved.is_none_or(|r| r.state != resolved.state || r.issue != resolved.issue) {
            write_front(conn, account, front, resolved)?;
        }
        let old_purposes = old.map(purpose_selections).unwrap_or_default();
        for (purpose, layers) in purpose_selections(front) {
            if old_purposes.get(purpose) == Some(&layers) {
                continue;
            }
            conn.prepare_cached("INSERT INTO named_front_purpose_config(account_id,front_id,purpose,use_legacy) VALUES(?1,?2,?3,?4) ON CONFLICT(account_id,front_id,purpose) DO UPDATE SET use_legacy=excluded.use_legacy")?.execute(params![account,id,purpose,layers.is_none()])?;
            conn.prepare_cached("DELETE FROM named_front_purpose WHERE account_id=?1 AND front_id=?2 AND purpose=?3")?
                .execute(params![account, id, purpose])?;
            if let Some(layers) = layers {
                for layer in layers {
                    conn.prepare_cached(
                        "INSERT INTO named_front_purpose(account_id,front_id,purpose,layer_id) VALUES(?1,?2,?3,?4)",
                    )?
                    .execute(params![account, id, purpose, layer])?;
                }
            }
        }
        let mut pools = vec![
            (Cow::Borrowed("own"), &front.own, old.map(|f| &f.own)),
            (Cow::Borrowed("effective"), &resolved.effective, old_resolved.map(|f| &f.effective)),
            (Cow::Borrowed("authorship"), &resolved.authorship, old_resolved.map(|f| &f.authorship)),
            (Cow::Borrowed("notifications"), &resolved.notifications, old_resolved.map(|f| &f.notifications)),
        ];
        for (purpose, entries) in &resolved.purpose_candidates {
            pools.push((
                Cow::Owned(format!("purpose:{purpose}")),
                entries,
                old_resolved.and_then(|r| r.purpose_candidates.get(purpose)),
            ));
        }
        for (kind, entries, old_entries) in pools {
            if old_entries == Some(entries) {
                continue;
            }
            write_presence(conn, account, id, &kind, entries)?;
        }
    }
    conn.prepare_cached("INSERT INTO front_system_cache(account_id,projection_version,last_front_seq,projection,append_guard) VALUES(?1,?2,?3,?4,?5) ON CONFLICT(account_id) DO UPDATE SET projection_version=excluded.projection_version,last_front_seq=excluded.last_front_seq,projection=excluded.projection,append_guard=excluded.append_guard")?
        .execute(params![account,CACHE_VERSION,front_seq(conn,account,None)?,serde_json::to_string(&index.current)?,serde_json::to_string(&index.guard)?])?;
    Ok(())
}

fn purpose_selections(f: &Front) -> BTreeMap<&str, Option<&BTreeSet<String>>> {
    let mut purposes = BTreeMap::from([
        ("authorship", f.authorship_layers.as_ref()),
        ("notifications", f.notification_layers.as_ref()),
    ]);
    for purpose in chorus_core::front_system::CHANNEL_PURPOSES {
        purposes.insert(purpose, f.purpose_layers.get(purpose).and_then(Option::as_ref));
    }
    for (purpose, layers) in &f.purpose_layers {
        purposes.insert(purpose, layers.as_ref());
    }
    purposes
}

fn write_front(conn: &Connection, account: &str, f: &Front, r: &ResolvedFront) -> anyhow::Result<()> {
    let state = match r.state {
        chorus_core::front_system::FollowState::Independent => "independent",
        chorus_core::front_system::FollowState::Following => "following",
        chorus_core::front_system::FollowState::Overridden => "overridden",
        chorus_core::front_system::FollowState::Unavailable => "unavailable",
    };
    let issue = r.issue.map(|i| match i {
        chorus_core::front_system::FollowIssue::Cycle => "cycle",
        chorus_core::front_system::FollowIssue::SourceUnavailable => "source_unavailable",
    });
    conn.prepare_cached("INSERT INTO named_front(account_id,id,name,follow_source_id,follow_hlc,is_overridden,follow_state,follow_issue,is_deleted) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9) ON CONFLICT(account_id,id) DO UPDATE SET name=excluded.name,follow_source_id=excluded.follow_source_id,follow_hlc=excluded.follow_hlc,is_overridden=excluded.is_overridden,follow_state=excluded.follow_state,follow_issue=excluded.follow_issue,is_deleted=excluded.is_deleted")?
        .execute(params![account,f.id,f.name,f.follow.as_ref().map(|link|&link.source),f.follow.as_ref().map(|link|link.clock.to_string()),f.overridden,state,issue,f.deleted])?;
    Ok(())
}

fn write_presence(
    conn: &Connection,
    account: &str,
    front: &str,
    kind: &str,
    entries: &[Presence],
) -> anyhow::Result<()> {
    conn.prepare_cached("DELETE FROM named_front_presence WHERE account_id=?1 AND front_id=?2 AND kind=?3")?
        .execute(params![account, front, kind])?;
    for (position, entry) in entries.iter().enumerate() {
        let subject_type = match entry.subject_type {
            chorus_core::front::SubjectType::Member => "member",
            chorus_core::front::SubjectType::Group => "group",
            chorus_core::front::SubjectType::State => "state",
        };
        conn.prepare_cached("INSERT INTO named_front_presence(account_id,front_id,kind,subject_type,subject_id,layer_id,is_primary,position) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)")?
            .execute(params![account,front,kind,subject_type,entry.subject_id,entry.layer_id,entry.is_primary,position as i64])?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use chorus_core::{front_history, hlc::Hlc};
    use serde_json::{Value, json};

    const BODY: &str = "0192f8c2-0000-7000-8000-0000000000a1";
    const CHORUS: &str = "0192f8c2-0000-7000-8000-0000000000a2";
    const OTHER: &str = "0192f8c2-0000-7000-8000-0000000000a3";
    const T: u64 = 1_790_000_000_000;

    fn database() -> Connection {
        let mut conn = crate::db::open_memory().unwrap();
        crate::db::migrate(&mut conn).unwrap();
        conn
    }
    fn op(i: u64, account: &str, front: &str, kind: &str, payload: Value) -> Op {
        serde_json::from_value(json!({"id":chorus_core::id::new_id(T+i*1000,[i as u8;10]),
            "scope":format!("account:{account}"),"kind":kind,"entity_id":front,
            "hlc":Hlc::new(T+i*1000,0,1),"device_at":T+i*1000,"occurred_at":T+i*1000,"received_at":T+i*1000,
            "account_id":account,"device_id":"0192f8c2-0000-7000-8000-0000000000d1","payload":payload}))
        .unwrap()
    }
    fn presence(member: &str, layer: &str) -> Value {
        json!({"subject_type":"member","subject_id":member,"layer_id":layer})
    }
    fn insert(conn: &Connection, mut o: Op, project: bool) -> Op {
        o.seq = Some(oplog::insert(conn, &o, false, false).unwrap());
        if project {
            after_op(conn, &o).unwrap();
        }
        o
    }
    fn assert_current(conn: &Connection, account: &str, ops: &[Op]) {
        let expected = front_history::fold(account, ops);
        assert_eq!(
            current(conn, account).unwrap().unwrap(),
            Current { system: expected.system, resolved: expected.resolved }
        );
    }

    #[test]
    fn readable_projection_matches_core_through_following_overrides_and_history_corrections() {
        let conn = database();
        let first = insert(
            &conn,
            op(
                1,
                BODY,
                BODY,
                "front.switch",
                json!({"entries":[{"subject_type":"member","subject_id":"fox","level":"front"}]}),
            ),
            true,
        );
        assert!(cached(&conn, BODY).unwrap().is_none(), "legacy accounts keep their old projection path");
        let mut ops = vec![first];
        for o in [
            op(
                2,
                BODY,
                CHORUS,
                "front.define",
                json!({"name":"Chorus","follow_source":BODY,"authorship_layers":["quiet"],"purpose_layers":{"mentions":["quiet"],"reading":[],"chat_alerts":["quiet"]}}),
            ),
            op(3, BODY, CHORUS, "front.change", json!({"action":"switch","entries":[presence("own","quiet")]})),
            op(4, BODY, CHORUS, "front.change", json!({"action":"resume"})),
            op(5, BODY, BODY, "front.layer", json!({"layer_id":"quiet","name":"Fronting","level":0})),
            op(
                6,
                BODY,
                BODY,
                "front.add",
                json!({"entry":{"subject_type":"member","subject_id":"moon","level":"cocon"}}),
            ),
            op(7, BODY, BODY, "front.define", json!({"deleted":true})),
            op(8, BODY, BODY, "front.define", json!({"deleted":false})),
            op(9, BODY, CHORUS, "front.define", json!({"purpose_layers":{"reading":null}})),
        ] {
            let deleted = o.payload.get("deleted") == Some(&Value::Bool(true));
            ops.push(insert(&conn, o, true));
            assert_current(&conn, BODY, &ops);
            if deleted {
                let confirmed: i64 = conn
                    .query_row("SELECT count(*) FROM v_named_front_current WHERE account_id=?1", [BODY], |r| r.get(0))
                    .unwrap();
                assert_eq!(confirmed, 0, "recovery fallback is not confirmed effective presence");
                let saved: i64 = conn
                    .query_row(
                        "SELECT count(*) FROM named_front_presence WHERE account_id=?1 AND front_id=?2 AND kind='own'",
                        params![BODY, CHORUS],
                        |r| r.get(0),
                    )
                    .unwrap();
                assert_eq!(saved, 1, "source deletion retains independent presence");
            }
        }
        let reading_default:bool=conn.query_row("SELECT use_legacy FROM named_front_purpose_config WHERE account_id=?1 AND front_id=?2 AND purpose='reading'",params![BODY,CHORUS],|r|r.get(0)).unwrap();
        assert!(reading_default);
        let retract = op(10, BODY, BODY, "front.retract", json!({"target_op_id":ops[5].id}));
        ops.push(insert(&conn, retract, true));
        assert_current(&conn, BODY, &ops);
        let saved_before = serde_json::to_value(current(&conn, BODY).unwrap()).unwrap();
        rebuild_account(&conn, BODY).unwrap();
        assert_eq!(serde_json::to_value(current(&conn, BODY).unwrap()).unwrap(), saved_before);
        assert_eq!(oplog::for_scope_kinds(&conn, &format!("account:{BODY}"), "front.").unwrap().len(), ops.len());
    }

    #[test]
    fn accounts_namespaces_and_future_purposes_stay_independent() {
        let conn = database();
        let mut all = vec![];
        for (i, account, member) in [(1, BODY, "first"), (3, OTHER, "second")] {
            all.push(insert(
                &conn,
                op(i, account, CHORUS, "front.change", json!({"action":"switch","entries":[presence(member,"quiet")]})),
                true,
            ));
            all.push(insert(
                &conn,
                op(
                    i + 1,
                    account,
                    CHORUS,
                    "front.define",
                    json!({"purpose_layers":{"future:purpose":["quiet"],"\u{0}future":["quiet"],"reading":[]}}),
                ),
                true,
            ));
        }
        assert_current(&conn, BODY, &all);
        assert_current(&conn, OTHER, &all);
        let rows:Vec<(String,String)>=conn.prepare("SELECT account_id,subject_id FROM named_front_presence WHERE kind='purpose:future:purpose' ORDER BY account_id").unwrap()
            .query_map([],|r|Ok((r.get(0)?,r.get(1)?))).unwrap().collect::<Result<_,_>>().unwrap();
        assert_eq!(rows, vec![(BODY.into(), "first".into()), (OTHER.into(), "second".into())]);
        let binary_key_rows: i64 = conn
            .query_row("SELECT count(*) FROM named_front_presence WHERE kind=?1", ["purpose:\0future"], |r| r.get(0))
            .unwrap();
        assert_eq!(binary_key_rows, 2, "opaque purpose IDs retain their complete byte identity");
        let readers: i64 = conn
            .query_row("SELECT count(*) FROM named_front_presence WHERE kind='purpose:reading'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(readers, 0, "an explicit empty selection is not a legacy default");
    }

    #[test]
    fn purpose_constraint_upgrade_keeps_existing_rows_cache_and_analysis_view() {
        let mut conn = crate::db::open_memory().unwrap();
        for (_, sql) in &crate::db::MIGRATIONS[..11] {
            conn.execute_batch(sql).unwrap();
        }
        crate::db::set_meta(&conn, "schema_version", "11").unwrap();
        let first = insert(
            &conn,
            op(1, BODY, CHORUS, "front.change", json!({"action":"switch","entries":[presence("own","quiet")]})),
            true,
        );
        let before = cached(&conn, BODY).unwrap();
        assert_eq!(crate::db::migrate(&mut conn).unwrap(), 1);
        assert_eq!(cached(&conn, BODY).unwrap(), before);
        assert_current(&conn, BODY, &[first]);
        assert_eq!(
            conn.query_row("SELECT count(*) FROM v_named_front_current", [], |r| r.get::<_, i64>(0)).unwrap(),
            1
        );
        insert(&conn, op(2, BODY, CHORUS, "front.define", json!({"purpose_layers":{"\u{0}future":["quiet"]}})), true);
        let count: i64 = conn
            .query_row("SELECT count(*) FROM named_front_presence WHERE kind=?1", ["purpose:\0future"], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 1);
        assert_eq!(conn.query_row("SELECT count(*) FROM op", [], |r| r.get::<_, i64>(0)).unwrap(), 2);
    }

    #[test]
    fn pre_upgrade_ops_and_invalid_caches_rebuild_without_changing_the_log() {
        let conn = database();
        let ops = vec![insert(
            &conn,
            op(1, BODY, CHORUS, "front.change", json!({"action":"switch","entries":[presence("own","quiet")]})),
            false,
        )];
        assert!(cached(&conn, BODY).unwrap().is_none());
        assert_eq!(backfill(&conn).unwrap(), 1);
        assert_eq!(backfill(&conn).unwrap(), 0);
        assert_current(&conn, BODY, &ops);
        conn.execute("UPDATE front_system_cache SET projection_version=0,projection='{}'", []).unwrap();
        assert_current(&conn, BODY, &ops);
        let count: i64 = conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
        assert_eq!(count, ops.len() as i64);
    }

    #[test]
    fn unsupported_or_malformed_new_kinds_remain_opaque_in_the_log() {
        let conn = database();
        let mut future = op(1, BODY, CHORUS, "front.define", json!({"name":"Future"}));
        future.v = 2;
        let _ = insert(&conn, future, true);
        let _ = insert(&conn, op(2, BODY, CHORUS, "front.define", json!({"unexpected":"field"})), true);
        assert_eq!(backfill(&conn).unwrap(), 0);
        assert!(current(&conn, BODY).unwrap().is_none());
        let count: i64 = conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
        assert_eq!(count, 2);
    }

    #[test]
    fn valid_but_stale_caches_refresh_when_previously_unprojected_ops_arrive() {
        let conn = database();
        let mut ops = vec![insert(
            &conn,
            op(1, BODY, CHORUS, "front.change", json!({"action":"switch","entries":[presence("own","quiet")]})),
            true,
        )];
        ops.push(insert(
            &conn,
            op(2, BODY, BODY, "front.layer", json!({"layer_id":"quiet","name":"Recognized later","level":-4})),
            false,
        ));
        ops.push(insert(&conn, op(3, BODY, BODY, "front.switch", json!({"entries":[]})), true));
        assert_current(&conn, BODY, &ops);
        assert_eq!(current(&conn, BODY).unwrap().unwrap().system.layers["quiet"].name, "Recognized later");
        ops.push(insert(&conn, op(4, BODY, CHORUS, "front.define", json!({"name":"Late recognition"})), false));
        assert_eq!(backfill(&conn).unwrap(), 1);
        assert_current(&conn, BODY, &ops);
        ops.push(insert(
            &conn,
            op(5, BODY, CHORUS, "front.define", json!({"purpose_layers":{"reading":["quiet"]}})),
            false,
        ));
        assert_current(&conn, BODY, &ops);
    }

    #[test]
    fn ignored_front_events_advance_only_a_complete_prefix_and_match_a_rebuild() {
        let conn = database();
        let mut ops = vec![insert(&conn, op(1, BODY, CHORUS, "front.define", json!({"name":"Chorus"})), true)];
        // A legacy shape that is accepted as a stored op but has no parseable front action.
        ops.push(insert(&conn, op(2, BODY, BODY, "front.switch", json!({})), true));
        let before = cached(&conn, BODY).unwrap();
        rebuild_account(&conn, BODY).unwrap();
        assert_eq!(cached(&conn, BODY).unwrap(), before);
        // An opaque operation cannot move the watermark past an earlier unprojected valid one.
        ops.push(insert(&conn, op(3, BODY, CHORUS, "front.define", json!({"name":"Arrived earlier"})), false));
        let mut future = op(4, BODY, CHORUS, "front.define", json!({"name":"Future"}));
        future.v = 2;
        let future = insert(&conn, future, false);
        crate::project::after_insert(&conn, &future).unwrap();
        ops.push(future);
        assert_current(&conn, BODY, &ops);
        assert_eq!(current(&conn, BODY).unwrap().unwrap().system.fronts[CHORUS].name, "Arrived earlier");
        let before = cached(&conn, BODY).unwrap();
        rebuild_account(&conn, BODY).unwrap();
        assert_eq!(cached(&conn, BODY).unwrap(), before);
    }

    #[test]
    fn whole_database_rebuild_reproduces_named_state_without_copying_bad_projections() {
        let mut conn = database();
        let ops = vec![
            insert(
                &conn,
                op(1, BODY, BODY, "front.layer", json!({"layer_id":"quiet","name":"Quiet","level":-4})),
                true,
            ),
            insert(
                &conn,
                op(2, BODY, CHORUS, "front.change", json!({"action":"switch","entries":[presence("own","quiet")]})),
                true,
            ),
            insert(
                &conn,
                op(3, BODY, CHORUS, "front.define", json!({"name":"Chorus","purpose_layers":{"reading":["quiet"]}})),
                true,
            ),
        ];
        let expected = current(&conn, BODY).unwrap();
        conn.execute("UPDATE named_front SET name='Incorrect derived row' WHERE id=?1", [CHORUS]).unwrap();
        assert_eq!(crate::project::rebuild(&mut conn).unwrap(), 3);
        assert_eq!(current(&conn, BODY).unwrap(), expected);
        let name: String = conn
            .query_row("SELECT name FROM named_front WHERE account_id=?1 AND id=?2", params![BODY, CHORUS], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(name, "Chorus");
        assert_current(&conn, BODY, &ops);
        let count: i64 = conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
        assert_eq!(count, 3);
    }

    #[test]
    fn ingest_transaction_rolls_back_log_configuration_presence_and_cache_together() {
        let mut conn = database();
        insert(&conn, op(1, BODY, CHORUS, "front.define", json!({"name":"Chorus"})), true);
        let before = current(&conn, BODY).unwrap();
        let cache_before = cached(&conn, BODY).unwrap();
        {
            let tx = conn.transaction().unwrap();
            insert(
                &tx,
                op(2, BODY, CHORUS, "front.change", json!({"action":"switch","entries":[presence("own","front")]})),
                true,
            );
            assert_eq!(current(&tx, BODY).unwrap().unwrap().resolved[CHORUS].effective.len(), 1);
            // Simulate a later ingestion failure after projection but before commit.
            tx.rollback().unwrap();
        }
        assert_eq!(current(&conn, BODY).unwrap(), before);
        assert_eq!(cached(&conn, BODY).unwrap(), cache_before);
        let count: i64 = conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
        assert_eq!(count, 1);
        let count: i64 = conn.query_row("SELECT count(*) FROM named_front_presence", [], |r| r.get(0)).unwrap();
        assert_eq!(count, 0);
    }

    #[test]
    fn live_ingestion_and_owner_reads_use_canonical_body_and_enforce_account_ownership() {
        let mut conn = database();
        for (account, handle) in [(BODY, "owner"), (OTHER, "other")] {
            conn.execute(
                "INSERT INTO account(id,kind,handle,created_at) VALUES(?1,'system',?2,0)",
                params![account, handle],
            )
            .unwrap();
            crate::ingest::grant(&conn, account, &format!("account:{account}")).unwrap();
        }
        let own = chorus_core::id::new_id(T, [51; 10]);
        let foreign = chorus_core::id::new_id(T, [52; 10]);
        for (account, member, name) in [(BODY, &own, "Own member"), (OTHER, &foreign, "Private other name")] {
            crate::ingest::server_op(
                &conn,
                account,
                "member.create",
                &format!("account:{account}"),
                Some(member),
                json!({"name":name}),
                T as i64,
            )
            .unwrap();
        }
        let scope = format!("account:{BODY}");
        crate::ingest::server_op(
            &conn,
            BODY,
            "front.change",
            &scope,
            Some(CHORUS),
            json!({"action":"switch","entries":[presence(&own,"quiet")]}),
            T as i64 + 1000,
        )
        .unwrap();
        crate::ingest::server_op(
            &conn,
            BODY,
            "front.define",
            &scope,
            Some(BODY),
            json!({"follow_source":CHORUS}),
            T as i64 + 2000,
        )
        .unwrap();
        let view = crate::api_data::current_front(&conn, &crate::api_data::Principal::owner(BODY)).unwrap();
        assert_eq!(view["front"][0]["subject_id"], own);
        assert_eq!(view["front"][0]["layer_id"], "quiet");
        assert_eq!(view["front"][0]["name"], "Own member");
        assert!(view["front"][0].get("level").is_none());
        let expected = current(&conn, BODY).unwrap();
        let before: i64 = conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
        let (rejected, new) = crate::ingest::op_as(
            &conn,
            BODY,
            "phone",
            "front.change",
            &scope,
            Some(BODY),
            json!({"action":"switch","entries":[presence(&foreign,"front")]}),
            T as i64 + 3000,
            None,
        )
        .unwrap();
        assert!(new.is_none());
        assert_eq!(rejected.error.unwrap().code, "forbidden");
        assert_eq!(current(&conn, BODY).unwrap(), expected);
        assert_eq!(conn.query_row("SELECT count(*) FROM op", [], |r| r.get::<_, i64>(0)).unwrap(), before);
        // Even an accidental scope-access grant is not authority to change another account's front.
        crate::ingest::grant(&conn, OTHER, &scope).unwrap();
        let (rejected, new) = crate::ingest::op_as(
            &conn,
            OTHER,
            "phone",
            "front.define",
            &scope,
            Some(CHORUS),
            json!({"name":"Forged"}),
            T as i64 + 4000,
            None,
        )
        .unwrap();
        assert!(new.is_none());
        assert_eq!(rejected.error.unwrap().code, "forbidden");
        let other = crate::api_data::current_front(&conn, &crate::api_data::Principal::owner(OTHER)).unwrap();
        assert!(!other.to_string().contains(CHORUS));
        crate::project::rebuild(&mut conn).unwrap();
        assert_eq!(current(&conn, BODY).unwrap(), expected);
    }

    #[test]
    #[ignore = "release-mode SQL current/append budget; cargo test -p chorus-server --lib fronts::tests::current_storage_budgets --release -- --ignored --nocapture"]
    fn current_storage_budgets() {
        let conn = database();
        let tx = conn.unchecked_transaction().unwrap();
        for i in 1..=10_000 {
            insert(&tx, op(i, BODY, BODY, "front.switch", json!({"entries":[]})), false);
        }
        tx.commit().unwrap();
        let start = std::time::Instant::now();
        for _ in 0..1000 {
            assert!(current(&conn, BODY).unwrap().is_none());
        }
        let legacy = start.elapsed();
        insert(&conn, op(10_001, BODY, CHORUS, "front.define", json!({"name":"Chorus"})), true);
        let next: Vec<_> = (10_002..=11_001)
            .map(|i| {
                op(i, BODY, CHORUS, "front.change", json!({"action":"switch","entries":[presence("owned","front")]}))
            })
            .collect();
        let tx = conn.unchecked_transaction().unwrap();
        let start = std::time::Instant::now();
        for o in next {
            insert(&tx, o, true);
        }
        let live = start.elapsed();
        tx.commit().unwrap();
        eprintln!("1,000 legacy-only reads after 10,000 ops: {legacy:?}; 1,000 current SQL appends: {live:?}");
        assert!(legacy < std::time::Duration::from_millis(100));
        assert!(live < std::time::Duration::from_millis(500));
    }
}
