//! Malformed ops never break ingest: every op kind in the catalogue, with payloads of the wrong
//! shape (wrong types, missing keys, huge numbers, ids of things that don't exist or exist in
//! another table), is either accepted and projected or refused *per op*. `ingest::accept` must
//! never fail: a failure rolls back the whole pushed batch, and the device would resend the same
//! batch forever (one bad op blocking its outbox). Ingest has a safety net for that (an op the
//! projection can't apply is refused as `unprocessable`), but every such op is a gap in
//! `op::validate`, which clients run too, so the test fails on those as well. Afterwards a rebuild from the log reproduces
//! the live projection exactly, so nothing accepted depends on the order it was projected in.
//!
//! `CHORUS_FUZZ_CASES=<n>` for a longer run (default 64 batches of 60 ops).

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::{CATALOGUE, Op};
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, ingest, project};
use proptest::prelude::*;
use proptest::test_runner::{Config, TestRunner};
use rusqlite::Connection;
use serde_json::{Map, Value, json};

const NOW: i64 = 1_790_000_000_000;

/// Keys the projections read from payloads, beyond each kind's declared fields.
const KEYS: &[&str] = &[
    "account_id",
    "allow",
    "attachments",
    "authors",
    "ceiling",
    "channel_id",
    "cocon",
    "deny",
    "device",
    "entities",
    "field_id",
    "follower_account_id",
    "front",
    "key",
    "length",
    "member_id",
    "message_at",
    "message_id",
    "notify",
    "offset",
    "parent_id",
    "parent_message_id",
    "post_id",
    "prefs",
    "profile_member_id",
    "reader_member_id",
    "reply_to",
    "repost_of",
    "resolution",
    "review_id",
    "role",
    "roles",
    "segments",
    "sort_key",
    "switch_id",
    "target_account_id",
    "target_id",
    "target_type",
    "text",
    "type",
    "value",
    "visibility",
    "kind",
    "name",
    "members",
    "member_ids",
    "fronters",
    "started_at",
    "ended_at",
    "occurred_at",
    "cw",
    "quote",
    "forward_of_id",
    "forward_snapshot",
    "blob_hash",
    "thumb_blob_hash",
    "emoji",
    "is_spoiler",
    "mode",
    "group_id",
    "tags",
    "title",
    "note",
    "items",
];

struct World {
    conn: Connection,
    account: String,
    other: String,
    space: String,
    /// ids ops point at: some become entities, the rest dangle
    pool: Vec<String>,
}

fn world() -> World {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let account = new_id(1, [1; 10]);
    let other = new_id(1, [2; 10]);
    for (id, admin) in [(&account, true), (&other, false)] {
        conn.execute(
            "INSERT INTO account(id, kind, created_at, is_admin, handle) VALUES (?1, 'system', 0, ?2, ?1)",
            rusqlite::params![id, admin],
        )
        .unwrap();
        ingest::grant(&conn, id, &format!("account:{id}")).unwrap();
    }
    let space = new_id(1, [3; 10]);
    ingest::grant(&conn, &account, &format!("space:{space}")).unwrap();
    let mut pool: Vec<String> = (0..12u8).map(|i| new_id(NOW as u64, [i + 10; 10])).collect();
    pool.extend([account.clone(), other.clone(), space.clone()]);
    World { conn, account, other, space, pool }
}

fn session(account: &str) -> ingest::Session {
    ingest::Session {
        account_id: account.into(),
        device_id: "fuzz".into(),
        sample: ClockSample { server_time: NOW, mono: None, boot_id: None, offset_ms: 0 },
    }
}

/// A value of any JSON shape, biased towards ones that look almost right.
fn value(pool: Vec<String>) -> impl Strategy<Value = Value> {
    let ids = prop::sample::select(pool);
    let leaf = prop_oneof![
        Just(Value::Null),
        any::<bool>().prop_map(Value::Bool),
        prop_oneof![Just(0i64), Just(-1), Just(i64::MAX), Just(i64::MIN), Just(NOW), any::<i64>()]
            .prop_map(|n| json!(n)),
        prop_oneof![Just(1.5f64), Just(-0.0), Just(1e300)].prop_map(|f| json!(f)),
        ids.clone().prop_map(Value::String),
        prop_oneof![
            Just(String::new()),
            Just("x".repeat(5000)),
            Just("🌌🔖❤️‍🔥 a\u{0}b".to_string()),
            Just("#zz".to_string()),
            Just("admin".to_string()),
            Just("view".to_string()),
            Just("role".to_string()),
            Just("account".to_string()),
            Just("everyone".to_string()),
            "[a-z_]{0,12}",
        ]
        .prop_map(Value::String),
    ];
    leaf.prop_recursive(3, 24, 6, move |inner| {
        prop_oneof![
            prop::collection::vec(inner.clone(), 0..5).prop_map(Value::Array),
            prop::collection::vec((prop::sample::select(KEYS), inner), 0..5)
                .prop_map(|kv| Value::Object(kv.into_iter().map(|(k, v)| (k.to_string(), v)).collect())),
        ]
    })
}

fn op_strategy(pool: Vec<String>, account: String, space: String) -> impl Strategy<Value = Op> {
    let kinds: Vec<usize> = (0..CATALOGUE.len()).collect();
    (
        prop::sample::select(kinds),
        prop::collection::vec((any::<prop::sample::Index>(), value(pool.clone())), 0..7),
        prop::option::weighted(0.9, prop::sample::select(pool.clone())),
        prop::option::weighted(0.2, prop::sample::select(pool)),
        any::<[u8; 10]>(),
        -86_400_000i64..86_400_000,
    )
        .prop_map(move |(k, fields, entity, member, rand, skew)| {
            let spec = &CATALOGUE[k];
            // the kind's own fields four times as often as the shared keys
            let names: Vec<&str> = (0..4).flat_map(|_| spec.fields.iter()).chain(KEYS).copied().collect();
            let mut payload = Map::new();
            for (i, v) in fields {
                payload.insert(i.get(&names).to_string(), v);
            }
            let scope = match spec.scope {
                chorus_core::op::ScopeKind::Account => format!("account:{account}"),
                chorus_core::op::ScopeKind::Space => format!("space:{space}"),
                chorus_core::op::ScopeKind::Server => "server".into(),
            };
            let at = NOW + skew;
            Op {
                id: new_id(at as u64, rand),
                kind: spec.kind.into(),
                v: 1,
                scope,
                entity_id: entity,
                hlc: Hlc::new(at as u64, 0, 1),
                device_at: at,
                tz_offset_min: 0,
                mono: None,
                boot_id: None,
                time_source: TimeSource::Auto,
                seen_seq: 0,
                member_id: member,
                payload: Value::Object(payload),
                seq: None,
                account_id: None,
                device_id: None,
                occurred_at: None,
                received_at: None,
            }
        })
}

/// Every projected table, as sorted rows, for comparing live against rebuilt.
fn snapshot(conn: &Connection) -> Vec<(String, Vec<String>)> {
    let tables: Vec<String> = conn
        .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%fts%' ORDER BY name")
        .unwrap()
        .query_map([], |r| r.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    let skip = ["op", "server_meta", "account", "scope_access", "schema_migrations", "device", "session"];
    tables
        .into_iter()
        .filter(|t| !skip.contains(&t.as_str()))
        .map(|t| {
            let mut rows: Vec<String> = conn
                .prepare(&format!("SELECT * FROM \"{t}\""))
                .unwrap()
                .query_map([], |r| {
                    let n = r.as_ref().column_count();
                    Ok((0..n)
                        .map(|i| match r.get_ref(i).unwrap() {
                            rusqlite::types::ValueRef::Text(t) => String::from_utf8_lossy(t).into_owned(),
                            v => format!("{v:?}"),
                        })
                        .collect::<Vec<_>>()
                        .join("|"))
                })
                .unwrap()
                .collect::<Result<_, _>>()
                .unwrap();
            rows.sort();
            (t, rows)
        })
        .collect()
}

#[test]
fn malformed_ops_never_fail_a_batch() {
    let cases = std::env::var("CHORUS_FUZZ_CASES").ok().and_then(|n| n.parse().ok()).unwrap_or(64);
    let w0 = world();
    let strategy = prop::collection::vec(
        (op_strategy(w0.pool.clone(), w0.account.clone(), w0.space.clone()), any::<bool>()),
        1..60,
    );
    drop(w0);
    let gaps = std::sync::Mutex::new(std::collections::BTreeSet::<String>::new());
    let mut runner = TestRunner::new(Config { cases, failure_persistence: None, ..Config::default() });
    runner
        .run(&strategy, |ops| {
            let mut w = world();
            let mut accepted = 0;
            for (o, as_other) in ops {
                let who = if as_other { w.other.clone() } else { w.account.clone() };
                let t = w.conn.transaction().unwrap();
                let (kind, payload) = (o.kind.clone(), o.payload.clone());
                match ingest::accept(&t, &session(&who), o, NOW, false) {
                    Ok((r, _)) => match &r.error {
                        None => accepted += 1,
                        Some(e) if e.code == "unprocessable" => {
                            gaps.lock().unwrap().insert(format!("{kind}: {}", e.message));
                        }
                        Some(_) => {}
                    },
                    Err(e) => {
                        return Err(TestCaseError::fail(format!("accept failed on {kind} {payload}: {e:#}")));
                    }
                }
                t.commit().unwrap();
            }
            let live = snapshot(&w.conn);
            project::rebuild(&mut w.conn).map_err(|e| TestCaseError::fail(format!("rebuild failed: {e:#}")))?;
            let rebuilt = snapshot(&w.conn);
            for ((t, a), (_, b)) in live.iter().zip(&rebuilt) {
                prop_assert_eq!(a, b, "table {} differs after a rebuild ({} ops accepted)", t, accepted);
            }
            Ok(())
        })
        .unwrap();
    let gaps = gaps.into_inner().unwrap();
    assert!(
        gaps.is_empty(),
        "op::validate let these through, and the projection couldn't apply them:
{}",
        gaps.into_iter().collect::<Vec<_>>().join(
            "
"
        )
    );
}

/// `op::FIELD_RULES` (checked by every client and at ingest) says everything the SQL schema
/// constrains in a column an op may write: JSON columns, NOT NULL, `IN (…)` checks and flags.
/// A migration that adds a constraint without a rule makes ops the server can't store.
#[test]
fn field_rules_match_the_schema() {
    use chorus_core::op::{Action, FIELD_RULES, Rule};
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    // columns the server fills, never a payload field
    let managed = [
        "id",
        "clocks",
        "created_at",
        "account_id",
        "updated_at",
        "deleted_at",
        "archived_at",
        "revision_count",
        "received_at",
        "device_id",
        "occurred_at",
        "occurred_local",
        "tz_offset_min",
        "edited_at",
        "owner_account_id",
        "created_by",
        "hlc",
        "seq",
        "reply_to_id",
        "repost_of_id",
    ];
    let tables: std::collections::BTreeSet<&str> = CATALOGUE
        .iter()
        .filter(|k| matches!(k.action, Action::Create | Action::Set | Action::Append | Action::Revise))
        .map(|k| k.table)
        .collect();
    // a column an op can write: a declared field of a create/set, or any column of an append
    let writable = |t: &str, c: &str| {
        CATALOGUE.iter().filter(|k| k.table == t).any(|k| match k.action {
            Action::Create | Action::Set => k.fields.contains(&c),
            Action::Append | Action::Revise => true,
            _ => false,
        })
    };
    let rules =
        |t: &str, c: &str| FIELD_RULES.iter().find(|(tt, cc, _)| *tt == t && *cc == c).map(|r| r.2).unwrap_or(&[]);
    let mut missing = Vec::new();
    for t in tables {
        let sql: String = conn.query_row("SELECT sql FROM sqlite_master WHERE name = ?1", [t], |r| r.get(0)).unwrap();
        let cols: Vec<(String, String, bool, Option<String>, bool)> = conn
            .prepare(&format!("PRAGMA table_info({t})"))
            .unwrap()
            .query_map([], |r| Ok((r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get::<_, i64>(5)? > 0)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        for (name, ty, not_null, default, pk) in cols {
            if pk || managed.contains(&name.as_str()) || !writable(t, &name) {
                continue;
            }
            let have = rules(t, &name);
            let json = sql.contains(&format!("json_valid({name})"))
                || default.as_deref().is_some_and(|d| d.starts_with("'{") || d.starts_with("'["));
            if json && !have.contains(&Rule::Json) {
                missing.push(format!("{t}.{name}: Json"));
            }
            if not_null && !have.contains(&Rule::NotNull) {
                missing.push(format!("{t}.{name}: NotNull"));
            }
            let flag = ty == "INTEGER" && ["is_", "can_", "has_"].iter().any(|p| name.starts_with(p));
            if flag && !have.contains(&Rule::Bool) {
                missing.push(format!("{t}.{name}: Bool"));
            }
            if sql.contains(&format!("{name} IN (")) && !have.iter().any(|r| matches!(r, Rule::OneOf(_))) {
                missing.push(format!("{t}.{name}: OneOf"));
            }
        }
    }
    assert!(missing.is_empty(), "add these to op::FIELD_RULES:\n{}", missing.join("\n"));
}

/// How many stored ops of a real database the value rules would refuse today, by kind and reason
/// (never their content): `CHORUS_CHECK_DB=<a copy of chorus.db> cargo test -p chorus-server
/// --test ingest_fuzz stored_ops -- --ignored --nocapture`. Stored ops keep projecting either way
/// (the rules are for new ops); a count here points at a client that wrote a bad shape.
#[test]
#[ignore]
fn stored_ops_against_the_value_rules() {
    let Ok(path) = std::env::var("CHORUS_CHECK_DB") else { return };
    let conn = Connection::open_with_flags(&path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY).unwrap();
    let mut counts = std::collections::BTreeMap::<String, usize>::new();
    let mut total = 0;
    let mut after = 0;
    loop {
        let page = chorus_server::oplog::applied_after(&conn, after, 5000).unwrap();
        let Some(last) = page.last() else { break };
        after = last.seq.unwrap();
        for o in page {
            total += 1;
            if let Err(e) = chorus_core::op::validate_new(&o) {
                *counts.entry(format!("{} — {e}", o.kind)).or_default() += 1;
            }
        }
    }
    println!("{total} ops; refused by today's rules:");
    for (k, n) in &counts {
        println!("  {n:>6}  {k}");
    }
}
