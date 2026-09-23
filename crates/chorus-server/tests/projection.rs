//! SQL projections equal the reference model (`chorus_core::model`), whatever order ops arrive in,
//! and `rebuild` reproduces them exactly.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::model;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, ingest, oplog, project};
use rusqlite::Connection;
use serde_json::{Value, json};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
    fn b10(&mut self) -> [u8; 10] {
        let a = self.next().to_le_bytes();
        let b = self.next().to_le_bytes();
        [a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], b[0], b[1]]
    }
}

const T: i64 = 1_790_000_000_000;

fn gen_ops(seed: u64, n: usize, acct_scope: &str, space_scope: &str) -> Vec<Op> {
    let mut r = Rng((seed * 2654435761) | 1);
    let members: Vec<String> = (0..4).map(|i| new_id(1, [i as u8 + 1; 10])).collect();
    let groups: Vec<String> = (0..2).map(|i| new_id(1, [i as u8 + 50; 10])).collect();
    let msgs: Vec<String> = (0..4).map(|i| new_id(1, [i as u8 + 90; 10])).collect();
    let mut front_ids: Vec<String> = Vec::new();
    let mut out = Vec::new();
    for i in 0..n {
        let at = T + (r.below(100_000) as i64);
        let m = members[r.below(4)].clone();
        let id = new_id(at as u64, r.b10());
        let (kind, scope, entity, payload): (&str, &str, Option<String>, Value) = match r.below(16) {
            0 => ("member.create", acct_scope, Some(m), json!({"name": format!("n{i}"), "sigils": ["🌌"]})),
            1 | 2 => ("member.set", acct_scope, Some(m), json!({"name": format!("s{i}"), "color": "#aabbcc"})),
            3 => (["member.delete", "member.restore", "member.archive"][r.below(3)], acct_scope, Some(m), json!({})),
            4 => (
                "group.create",
                acct_scope,
                Some(groups[r.below(2)].clone()),
                json!({"name": "g", "kind": "subsystem", "parent_id": groups[r.below(2)]}),
            ),
            5 => (
                if r.below(2) == 0 { "group.add_member" } else { "group.remove_member" },
                acct_scope,
                Some(groups[r.below(2)].clone()),
                json!({"member_id": m}),
            ),
            6..=8 => {
                front_ids.push(id.clone());
                let p = match r.below(4) {
                    0 => json!({"entries": [{"subject_type": "member", "subject_id": m, "is_primary": true}]}),
                    1 => json!({"entry": {"subject_type": "member", "subject_id": m, "level": "cocon"}}),
                    2 => json!({"subject_type": "member", "subject_id": m}),
                    _ => json!({"target_op_id": front_ids[r.below(front_ids.len())]}),
                };
                let k = if p.get("entries").is_some() {
                    "front.switch"
                } else if p.get("entry").is_some() {
                    "front.add"
                } else if p.get("target_op_id").is_some() {
                    "front.retract"
                } else {
                    "front.remove"
                };
                (k, acct_scope, Some(id.clone()), p)
            }
            9 | 10 => (
                "message.send",
                space_scope,
                Some(msgs[r.below(4)].clone()),
                json!({"channel_id": "c", "authors": [m], "text": format!("hello {i}"), "entities": []}),
            ),
            11 => {
                let mid = msgs[r.below(4)].clone();
                (
                    "message.edit",
                    space_scope,
                    Some(mid.clone()),
                    json!({"message_id": mid, "text": format!("edit {i}"), "entities": []}),
                )
            }
            12 => (
                ["message.delete", "message.restore", "message.pin", "message.unpin"][r.below(4)],
                space_scope,
                Some(msgs[r.below(4)].clone()),
                json!({}),
            ),
            13 => {
                let mid = msgs[r.below(4)].clone();
                (
                    if r.below(2) == 0 { "reaction.add" } else { "reaction.remove" },
                    space_scope,
                    Some(mid.clone()),
                    json!({"target_type": "message", "target_id": mid, "emoji": "💜", "member_id": m}),
                )
            }
            14 => (
                "read.mark",
                space_scope,
                None,
                json!({"channel_id": "c", "message_id": msgs[r.below(4)], "message_at": r.below(1000), "reader_member_id": ""}),
            ),
            _ => ("field.set_value", acct_scope, None, json!({"member_id": m, "field_id": "f", "value": r.below(9)})),
        };
        out.push(Op {
            id,
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: entity,
            hlc: Hlc::new(at as u64, (i % 60000) as u16, 1 + r.below(3) as u32),
            device_at: at,
            tz_offset_min: 60,
            mono: None,
            boot_id: None,
            time_source: TimeSource::User, // keep times exact so the model sees what SQL sees
            seen_seq: 0,
            member_id: None,
            payload,
            seq: None,
            account_id: None,
            device_id: None,
            occurred_at: None,
            received_at: None,
        });
    }
    out
}

fn setup() -> (Connection, String, String, String) {
    let mut c = db::open_memory().unwrap();
    db::migrate(&mut c).unwrap();
    let a = new_id(1, [200; 10]);
    c.execute("INSERT INTO account(id, kind, created_at) VALUES (?1, 'system', 0)", [&a]).unwrap();
    let acct = format!("account:{a}");
    let space = format!("space:{}", new_id(1, [201; 10]));
    ingest::grant(&c, &a, &acct).unwrap();
    ingest::grant(&c, &a, &space).unwrap();
    (c, a, acct, space)
}

fn dump(c: &Connection, sql: &str) -> Vec<String> {
    let mut st = c.prepare(sql).unwrap();
    let n = st.column_count();
    let mut rows: Vec<String> = st
        .query_map([], |r| {
            let v: Vec<String> =
                (0..n).map(|i| format!("{:?}", r.get::<_, rusqlite::types::Value>(i).unwrap())).collect();
            Ok(v.join("|"))
        })
        .unwrap()
        .map(Result::unwrap)
        .collect();
    rows.sort();
    rows
}

const TABLES: &[&str] = &[
    "SELECT id, name, color, sigils, archived_at, deleted_at, clocks FROM member",
    "SELECT id, name, parent_id, effective_parent_id FROM member_group",
    "SELECT group_id, member_id, is_present FROM group_membership",
    "SELECT id, kind, occurred_at, resulting_front, retracted FROM switch",
    "SELECT id, subject_id, level, is_primary, start_at, end_at FROM front_interval",
    "SELECT id, text, deleted_at, pinned_at, revision_count FROM message",
    "SELECT message_id, member_id, position FROM message_author",
    "SELECT target_id, member_id, is_present FROM reaction",
    "SELECT channel_id, last_read_message_id, last_read_message_at FROM read_state",
    "SELECT member_id, field_id, value FROM field_value",
    // rowids follow insertion order, so compare the index by message id
    "SELECT m.id, f.text FROM message_fts f JOIN message m ON m.rowid = f.rowid",
];

#[test]
fn sql_matches_model_and_rebuild_is_identical() {
    for seed in 1..=40u64 {
        let (mut c, a, acct, space) = setup();
        let mut ops = gen_ops(seed, 150, &acct, &space);
        // arrive in a scrambled order
        let mut r = Rng(seed | 7);
        for i in (1..ops.len()).rev() {
            ops.swap(i, r.below(i + 1));
        }
        let s = ingest::Session {
            account_id: a.clone(),
            device_id: "d".into(),
            sample: ClockSample { server_time: T, mono: None, boot_id: None, offset_ms: 0 },
        };
        for o in ops {
            let tx = c.transaction().unwrap();
            let (res, _) = ingest::accept(&tx, &s, o, T + 200_000, false).unwrap();
            assert!(res.error.is_none(), "seed {seed}: {:?}", res.error);
            tx.commit().unwrap();
        }
        // model over the stamped log
        let log = oplog::scope_after(&c, &acct, 0, 100_000).unwrap();
        let log_space = oplog::scope_after(&c, &space, 0, 100_000).unwrap();
        let all: Vec<Op> = log.iter().chain(log_space.iter()).cloned().collect();
        let m = model::project(all.iter());
        // members: every existing model row is in SQL with the same fields
        for (id, row) in m.rows.get("member").into_iter().flatten().filter(|(_, r)| r.exists) {
            let (name, deleted): (Option<String>, Option<i64>) = c
                .query_row("SELECT name, deleted_at FROM member WHERE id = ?1", [id], |x| Ok((x.get(0)?, x.get(1)?)))
                .unwrap();
            assert_eq!(name.as_deref(), row.fields.get("name").and_then(Value::as_str), "seed {seed} member {id}");
            assert_eq!(
                deleted,
                row.fields.get("deleted_at").and_then(Value::as_i64),
                "seed {seed} member {id} deleted_at"
            );
        }
        let sql_members: i64 = c.query_row("SELECT count(*) FROM member", [], |x| x.get(0)).unwrap();
        let model_members = m.rows.get("member").map(|t| t.values().filter(|r| r.exists).count()).unwrap_or(0);
        assert_eq!(sql_members as usize, model_members, "seed {seed}");
        // messages
        for (id, row) in m.rows.get("message").into_iter().flatten().filter(|(_, r)| r.exists) {
            let (text, pinned, revs): (String, Option<i64>, i64) = c
                .query_row("SELECT text, pinned_at, revision_count FROM message WHERE id = ?1", [id], |x| {
                    Ok((x.get(0)?, x.get(1)?, x.get(2)?))
                })
                .unwrap();
            assert_eq!(Some(text.as_str()), row.fields.get("text").and_then(Value::as_str), "seed {seed}");
            assert_eq!(pinned, row.fields.get("pinned_at").and_then(Value::as_i64), "seed {seed}");
            assert_eq!(revs, i64::from(row.edits) + 1, "seed {seed}");
        }
        // front intervals equal the fold
        let fold = m.fronts.get(&a).cloned().unwrap_or_default();
        let mut want: Vec<String> =
            fold.intervals.iter().map(|i| format!("{}|{}|{:?}", i.id, i.start_at, i.end_at)).collect();
        want.sort();
        let mut got: Vec<String> = dump(&c, "SELECT id, start_at, end_at FROM front_interval")
            .into_iter()
            .map(|r| {
                let p: Vec<&str> = r.split('|').collect();
                let text = |s: &str| s.trim_start_matches("Text(\"").trim_end_matches("\")").to_string();
                let int = |s: &str| s.trim_start_matches("Integer(").trim_end_matches(')').to_string();
                let end = if p[2] == "Null" { "None".to_string() } else { format!("Some({})", int(p[2])) };
                format!("{}|{}|{}", text(p[0]), int(p[1]), end)
            })
            .collect();
        got.sort();
        assert_eq!(got, want, "seed {seed}: front intervals");

        // rebuild reproduces every table exactly
        let before: Vec<Vec<String>> = TABLES.iter().map(|q| dump(&c, q)).collect();
        project::rebuild(&mut c).unwrap();
        let after: Vec<Vec<String>> = TABLES.iter().map(|q| dump(&c, q)).collect();
        for (i, q) in TABLES.iter().enumerate() {
            assert_eq!(before[i], after[i], "seed {seed}: rebuild differs for {q}");
        }
    }
}

#[test]
fn thread_reverse_link_survives_arrival_order_and_rebuild() {
    let (mut c, account, _, scope) = setup();
    let space_id = scope.strip_prefix("space:").unwrap();
    let channel = new_id(1, [211; 10]);
    let parent = new_id(1, [212; 10]);
    let later_parent = new_id(1, [213; 10]);
    let author = new_id(1, [214; 10]);
    let link = |db: &Connection, id: &str| -> Option<String> {
        db.query_row("SELECT thread_channel_id FROM message WHERE id = ?1", [id], |r| r.get(0)).unwrap()
    };

    ingest::server_op(
        &c,
        &account,
        "space.create",
        &scope,
        Some(space_id),
        json!({"kind":"internal","name":"Home"}),
        T,
    )
    .unwrap();
    ingest::server_op(
        &c,
        &account,
        "channel.create",
        &scope,
        Some(&channel),
        json!({"space_id":space_id,"kind":"text","name":"general"}),
        T + 1,
    )
    .unwrap();
    // The thread can arrive before its parent message. Reusing the parent's UUID makes a
    // concurrent Start thread action converge on one channel across devices.
    ingest::server_op(
        &c,
        &account,
        "channel.create",
        &scope,
        Some(&parent),
        json!({"space_id":space_id,"kind":"thread","name":"Thread","parent_message_id":parent}),
        T + 2,
    )
    .unwrap();
    ingest::server_op(
        &c,
        &account,
        "message.send",
        &scope,
        Some(&parent),
        json!({"channel_id":channel,"authors":[author],"text":"parent","entities":[]}),
        T + 3,
    )
    .unwrap();
    assert_eq!(link(&c, &parent), Some(parent.clone()));

    ingest::server_op(
        &c,
        &account,
        "message.send",
        &scope,
        Some(&later_parent),
        json!({"channel_id":channel,"authors":[author],"text":"later","entities":[]}),
        T + 4,
    )
    .unwrap();
    assert_eq!(link(&c, &later_parent), None);
    ingest::server_op(
        &c,
        &account,
        "channel.create",
        &scope,
        Some(&later_parent),
        json!({"space_id":space_id,"kind":"thread","name":"Thread","parent_message_id":later_parent}),
        T + 5,
    )
    .unwrap();
    assert_eq!(link(&c, &later_parent), Some(later_parent.clone()));

    ingest::server_op(&c, &account, "channel.archive", &scope, Some(&parent), json!({}), T + 6).unwrap();
    assert_eq!(link(&c, &parent), None);
    ingest::server_op(&c, &account, "channel.unarchive", &scope, Some(&parent), json!({}), T + 7).unwrap();
    assert_eq!(link(&c, &parent), Some(parent.clone()));

    project::rebuild(&mut c).unwrap();
    assert_eq!(link(&c, &parent), Some(parent));
    assert_eq!(link(&c, &later_parent), Some(later_parent));
}
