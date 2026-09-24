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

#[test]
fn post_reply_link_survives_projection_rebuild() {
    let (mut c, account, scope, _) = setup();
    let member = new_id(1, [241; 10]);
    let parent = new_id(1, [242; 10]);
    let reply = new_id(1, [243; 10]);
    ingest::server_op(&c, &account, "post.create", &scope, Some(&parent),
        json!({"kind":"entry","authors":[member],"title":"Day","text":"Body","entities":[],"visibility":{"mode":"private"}}), T).unwrap();
    ingest::server_op(&c, &account, "post.create", &scope, Some(&reply),
        json!({"kind":"note","authors":[member],"text":"Reply","entities":[],"reply_to":parent,"visibility":{"mode":"private"}}), T + 1).unwrap();
    let target = |db: &Connection| -> Option<String> {
        db.query_row("SELECT reply_to_id FROM post WHERE id = ?1", [&reply], |r| r.get(0)).unwrap()
    };
    assert_eq!(target(&c), Some(parent.clone()));
    project::rebuild(&mut c).unwrap();
    assert_eq!(target(&c), Some(parent));
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
fn message_attachment_links_survive_out_of_order_delivery_and_rebuild() {
    let (mut c, account, _, scope) = setup();
    let attachment = new_id(1, [71; 10]);
    let message = new_id(1, [72; 10]);
    let session = ingest::Session {
        account_id: account,
        device_id: "d".into(),
        sample: ClockSample { server_time: T, mono: None, boot_id: None, offset_ms: 0 },
    };
    for (i, (kind, entity, payload)) in [
        (
            "message.send",
            message.as_str(),
            json!({"channel_id":"c", "authors":[], "text":"", "entities":[], "attachments":[attachment]}),
        ),
        (
            "attachment.create",
            attachment.as_str(),
            json!({"blob_hash":"0".repeat(64), "filename":"a.png", "mime":"image/png", "size":1}),
        ),
    ]
    .into_iter()
    .enumerate()
    {
        let op = Op {
            id: new_id((T + i as i64) as u64, [i as u8 + 41; 10]),
            kind: kind.into(),
            v: 1,
            scope: scope.clone(),
            entity_id: Some(entity.into()),
            hlc: Hlc::new((T + i as i64) as u64, 0, 1),
            device_at: T + i as i64,
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
        let (ack, _) = ingest::accept(&c, &session, op, T + i as i64, false).unwrap();
        assert!(ack.error.is_none(), "{:?}", ack.error);
    }
    let sql = "SELECT owner_type, owner_id, attachment_id, position FROM item_attachment";
    let before = dump(&c, sql);
    assert_eq!(before.len(), 1);
    assert!(before[0].contains(&attachment));
    project::rebuild(&mut c).unwrap();
    assert_eq!(dump(&c, sql), before);
}

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
            let (name, deleted, color, clocks): (Option<String>, Option<i64>, Option<String>, String) = c
                .query_row("SELECT name, deleted_at, color, clocks FROM member WHERE id = ?1", [id], |x| {
                    Ok((x.get(0)?, x.get(1)?, x.get(2)?, x.get(3)?))
                })
                .unwrap();
            // `.set` is applied in place (project.rs set_in_place): same fields and clocks as the model
            assert_eq!(
                color.as_deref(),
                row.fields.get("color").and_then(Value::as_str),
                "seed {seed} member {id} color"
            );
            assert_eq!(
                serde_json::from_str::<Value>(&clocks).unwrap(),
                row.clocks.to_json(),
                "seed {seed} member {id} clocks"
            );
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

/// A rebuild of a database file reads the log on a second thread, drops the message indexes and
/// the search index while it replays, and makes them again: same rows, same schema, search works.
#[test]
fn a_file_rebuild_is_identical_and_keeps_its_indexes() {
    let path = std::path::PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join(format!(
        "projection-file-{}-{:x}.db",
        std::process::id(),
        T
    ));
    let (mut c, a, acct, space) = {
        let mut c = db::open(&path).unwrap();
        db::migrate(&mut c).unwrap();
        let a = new_id(1, [200; 10]);
        c.execute("INSERT INTO account(id, kind, created_at) VALUES (?1, 'system', 0)", [&a]).unwrap();
        let acct = format!("account:{a}");
        let space = format!("space:{}", new_id(1, [201; 10]));
        ingest::grant(&c, &a, &acct).unwrap();
        ingest::grant(&c, &a, &space).unwrap();
        (c, a, acct, space)
    };
    let s = ingest::Session {
        account_id: a,
        device_id: "d".into(),
        sample: ClockSample { server_time: T, mono: None, boot_id: None, offset_ms: 0 },
    };
    for seed in 1..=3u64 {
        let tx = c.transaction().unwrap();
        for o in gen_ops(seed, 400, &acct, &space) {
            let (res, _) = ingest::accept(&tx, &s, o, T + 200_000, false).unwrap();
            assert!(res.error.is_none(), "seed {seed}: {:?}", res.error);
        }
        tx.commit().unwrap();
    }
    let schema = |c: &Connection| dump(c, "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'");
    let before: Vec<Vec<String>> = TABLES.iter().map(|q| dump(&c, q)).collect();
    let before_schema = schema(&c);
    assert!(!before[5].is_empty() && !before[10].is_empty(), "messages and their search index");
    project::rebuild(&mut c).unwrap();
    for (i, q) in TABLES.iter().enumerate() {
        assert_eq!(before[i], dump(&c, q), "rebuild differs for {q}");
    }
    assert_eq!(before_schema, schema(&c), "the same tables and indexes afterwards");
    let word: String = c
        .query_row("SELECT text FROM message WHERE deleted_at IS NULL AND text <> '' LIMIT 1", [], |r| r.get(0))
        .unwrap();
    let word = word.split_whitespace().next().unwrap().trim_matches(|ch: char| !ch.is_alphanumeric()).to_string();
    let hits: i64 = c
        .query_row("SELECT count(*) FROM message_fts WHERE message_fts MATCH ?1", [format!("\"{word}\"")], |r| r.get(0))
        .unwrap();
    assert!(hits > 0, "search finds {word:?} after the rebuild");
    drop(c);
    for ext in ["", "-wal", "-shm"] {
        let _ = std::fs::remove_file(format!("{}{ext}", path.display()));
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

#[test]
fn editing_segmented_message_updates_text_and_attribution() {
    let (mut c, account, _, scope) = setup();
    let message = new_id(1, [221; 10]);
    let first = new_id(1, [222; 10]);
    let second = new_id(1, [223; 10]);
    ingest::server_op(
        &c,
        &account,
        "message.send",
        &scope,
        Some(&message),
        json!({"channel_id":"c","authors":[first,second],"text":"A\nB","entities":[],"segments":[
            {"offset":0,"length":1,"authors":[first]},
            {"offset":2,"length":1,"authors":[second]}
        ]}),
        T,
    )
    .unwrap();
    ingest::server_op(
        &c,
        &account,
        "message.edit",
        &scope,
        Some(&message),
        json!({"message_id":message,"text":"A😀!\nB?","entities":[],"segments":[
            {"offset":0,"length":4,"authors":[first]},
            {"offset":5,"length":2,"authors":[second]}
        ]}),
        T + 1,
    )
    .unwrap();
    let query = "SELECT s.idx, s.offset_u16, s.length_u16, s.text, a.member_id FROM message_segment s \
                 JOIN message_segment_author a ON a.message_id = s.message_id AND a.idx = s.idx \
                 WHERE s.message_id = ?1 ORDER BY s.idx";
    let rows = |db: &Connection| {
        db.prepare(query)
            .unwrap()
            .query_map([&message], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, String>(4)?,
                ))
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap()
    };
    let expected = vec![(0, 0, 4, "A😀!".to_string(), first), (1, 5, 2, "B?".to_string(), second)];
    assert_eq!(rows(&c), expected);
    project::rebuild(&mut c).unwrap();
    assert_eq!(rows(&c), expected);
}

#[test]
fn selection_snapshots_survive_projection_rebuild() {
    let (mut c, account, _, scope) = setup();
    let source = new_id(1, [231; 10]);
    let quote_id = new_id(1, [232; 10]);
    let forward_id = new_id(1, [233; 10]);
    let author = new_id(1, [234; 10]);
    let item = json!({
        "message_id": source, "channel_name": "general", "authors": [author],
        "text": "part", "entities": [], "offset": 2, "length": 4, "occurred_at": T
    });
    ingest::server_op(
        &c,
        &account,
        "message.send",
        &scope,
        Some(&quote_id),
        json!({"channel_id":"c","authors":[author],"text":"see this","entities":[],"quote":{"items":[item]}}),
        T,
    )
    .unwrap();
    ingest::server_op(
        &c,
        &account,
        "message.forward",
        &scope,
        Some(&forward_id),
        json!({"channel_id":"c","authors":[author],"text":"","entities":[],
            "forward_of_id":source,"forward_snapshot":[item]}),
        T + 1,
    )
    .unwrap();
    let read = |db: &Connection, id: &str, col: &str| -> serde_json::Value {
        let raw: String =
            db.query_row(&format!("SELECT {col} FROM message WHERE id = ?1"), [id], |r| r.get(0)).unwrap();
        serde_json::from_str(&raw).unwrap()
    };
    assert_eq!(read(&c, &quote_id, "quote"), json!({"items":[item]}));
    assert_eq!(read(&c, &forward_id, "forward_snapshot"), json!([item]));
    project::rebuild(&mut c).unwrap();
    assert_eq!(read(&c, &quote_id, "quote"), json!({"items":[item]}));
    assert_eq!(read(&c, &forward_id, "forward_snapshot"), json!([item]));
}

/// Live switches in time order take the one-step path (project.rs `append_front`). Whatever mix of
/// devices, offsets, retracts and amends comes along, the tables equal what refolding on every
/// op gives, and switches, intervals and daily totals equal one final refold.
#[test]
fn in_order_switches_match_refolding() {
    const FRONT_TABLES: &[&str] = &[
        "SELECT id, kind, occurred_at, tz_offset_min, device_id, entries, resulting_front, note, retracted, amended FROM switch",
        "SELECT id, subject_type, subject_id, level, is_primary, position, start_at, end_at, start_switch_id, end_switch_id, start_tz_offset_min FROM front_interval",
        "SELECT day, subject_type, subject_id, level, seconds, as_primary_seconds FROM front_daily",
        // cards are never withdrawn, so these compare against refolding on every op only
        "SELECT id, switch_a, switch_b FROM front_review",
    ];
    for seed in 1..=30u64 {
        let mut r = Rng((seed * 0x9E37_79B9) | 1);
        let members: Vec<String> = (0..5).map(|i| new_id(1, [i as u8 + 1; 10])).collect();
        let mut at = T - 40 * 86_400_000;
        let mut switches: Vec<String> = Vec::new();
        let mut tz = 60;
        let steps = 60 + r.below(60);
        let mut ops: Vec<(Op, &str)> = Vec::new();
        for i in 0..steps {
            // minutes to a couple of days apart; sometimes the same instant
            at += [0, 60_000, 3_600_000, 20 * 3_600_000, 50 * 3_600_000][r.below(5)] as i64;
            if r.below(15) == 0 {
                tz = [60, 120, -300][r.below(3)];
            }
            let m = |r: &mut Rng| members[r.below(5)].clone();
            let level = |r: &mut Rng| ["front", "cocon", "present"][r.below(3)];
            let id = new_id(at as u64, r.b10());
            // the last op empties the front, so daily totals don't depend on the clock
            let pick = if i + 1 == steps { 99 } else { r.below(12) };
            let (kind, payload) = match pick {
                99 => ("front.switch", json!({"entries": []})),
                0..=3 => {
                    let n = r.below(3);
                    let entries: Vec<Value> = (0..n)
                        .map(|k| json!({"subject_type": "member", "subject_id": m(&mut r), "level": level(&mut r), "is_primary": k == 0}))
                        .collect();
                    ("front.switch", json!({"entries": entries}))
                }
                4 | 5 => (
                    "front.add",
                    json!({"entry": {"subject_type": "member", "subject_id": m(&mut r), "level": level(&mut r)}}),
                ),
                6 => ("front.remove", json!({"subject_type": "member", "subject_id": m(&mut r)})),
                7 => {
                    ("front.update", json!({"subject_type": "member", "subject_id": m(&mut r), "level": level(&mut r)}))
                }
                8 if !switches.is_empty() => {
                    ("front.retract", json!({"target_op_id": switches[r.below(switches.len())]}))
                }
                9 if !switches.is_empty() => {
                    ("front.unretract", json!({"target_op_id": switches[r.below(switches.len())]}))
                }
                10 if !switches.is_empty() => (
                    "front.amend",
                    json!({"target_op_id": switches[r.below(switches.len())], "occurred_at": at - r.below(90_000_000) as i64}),
                ),
                _ => (
                    "front.switch",
                    json!({"entries": [{"subject_type": "member", "subject_id": m(&mut r), "is_primary": true}]}),
                ),
            };
            if matches!(kind, "front.switch" | "front.add" | "front.remove" | "front.update") {
                switches.push(id.clone());
            }
            let o = Op {
                id,
                kind: kind.into(),
                v: 1,
                scope: String::new(),
                entity_id: Some(new_id(at as u64, r.b10())),
                hlc: Hlc::new(at as u64, i as u16, 1 + r.below(2) as u32),
                device_at: at,
                tz_offset_min: tz,
                mono: None,
                boot_id: None,
                time_source: TimeSource::User,
                seen_seq: r.below(i + 1) as i64,
                member_id: None,
                payload,
                seq: None,
                account_id: None,
                device_id: None,
                occurred_at: None,
                received_at: None,
            };
            // two devices, so concurrent switches make review cards
            ops.push((o, if r.below(2) == 0 { "d1" } else { "d2" }));
        }

        let run = |fast: bool| -> (Connection, String) {
            project::set_front_fast_path(fast);
            let (c, a, acct, _) = setup();
            let mut c = c;
            for (o, dev) in &ops {
                let mut o = o.clone();
                o.scope = acct.clone();
                let s = ingest::Session {
                    account_id: a.clone(),
                    device_id: (*dev).into(),
                    sample: ClockSample { server_time: o.device_at, mono: None, boot_id: None, offset_ms: 0 },
                };
                let now = o.device_at + 1000;
                let tx = c.transaction().unwrap();
                let (res, _) = ingest::accept(&tx, &s, o, now, false).unwrap();
                assert!(res.error.is_none(), "seed {seed}: {:?}", res.error);
                tx.commit().unwrap();
            }
            project::set_front_fast_path(true);
            (c, acct)
        };
        let (fast, acct) = run(true);
        let (slow, _) = run(false);
        for q in FRONT_TABLES {
            assert_eq!(dump(&fast, q), dump(&slow, q), "seed {seed}: {q}");
        }
        let live: Vec<Vec<String>> = FRONT_TABLES[..3].iter().map(|q| dump(&fast, q)).collect();
        // a rebuild replays the same order, writing daily totals once at the end
        let mut fast = fast;
        project::rebuild(&mut fast).unwrap();
        for (i, q) in FRONT_TABLES[..3].iter().enumerate() {
            assert_eq!(live[i], dump(&fast, q), "seed {seed}: rebuild, {q}");
        }
        project::account_front(&fast, &acct).unwrap();
        for (i, q) in FRONT_TABLES[..3].iter().enumerate() {
            assert_eq!(live[i], dump(&fast, q), "seed {seed}: final refold, {q}");
        }
        let days: i64 = fast.query_row("SELECT count(*) FROM front_daily", [], |x| x.get(0)).unwrap();
        assert!(days > 0, "seed {seed}: daily totals were written");
    }
}

/// Read states are kept one op at a time (a running best mark, rescans on a newer manual set);
/// in any arrival order they equal the model's, and a rebuild reproduces them.
#[test]
fn read_states_match_the_model_in_any_order() {
    for seed in 1..=40u64 {
        let (mut c, a, acct, space) = setup();
        let mut r = Rng((seed * 0x2545_F491) | 1);
        let mut ops: Vec<Op> = Vec::new();
        for i in 0..80 {
            let at = T + r.below(1_000_000) as i64;
            let kind = if r.below(6) == 0 { "read.set" } else { "read.mark" };
            let channel = ["c1", "c2"][r.below(2)];
            let reader = ["", "kai"][r.below(2)];
            let message = format!("m{}", r.below(6));
            let message_at = r.below(6) as i64 * 1000;
            ops.push(Op {
                id: new_id(at as u64, r.b10()),
                kind: kind.into(),
                v: 1,
                scope: space.clone(),
                entity_id: None,
                hlc: Hlc::new(at as u64, i as u16, 1 + r.below(3) as u32),
                device_at: at,
                tz_offset_min: 0,
                mono: None,
                boot_id: None,
                time_source: TimeSource::User,
                seen_seq: 0,
                member_id: None,
                payload: json!({
                    "channel_id": channel,
                    "message_id": message,
                    "message_at": message_at,
                    "reader_member_id": reader,
                }),
                seq: None,
                account_id: None,
                device_id: None,
                occurred_at: None,
                received_at: None,
            });
        }
        let s = ingest::Session {
            account_id: a.clone(),
            device_id: "d".into(),
            sample: ClockSample { server_time: T, mono: None, boot_id: None, offset_ms: 0 },
        };
        for (i, o) in ops.into_iter().enumerate() {
            if i == 40 {
                // halfway, the rows look like ones written before migration 0004
                c.execute(
                    "UPDATE read_state SET state_ok = 0, mark_at = NULL, mark_id = NULL, mark_hlc = NULL,
                       set_at = NULL, set_id = NULL, set_hlc = NULL",
                    [],
                )
                .unwrap();
            }
            let tx = c.transaction().unwrap();
            let (res, _) = ingest::accept(&tx, &s, o, T + 2_000_000, false).unwrap();
            assert!(res.error.is_none(), "seed {seed}: {:?}", res.error);
            tx.commit().unwrap();
        }
        let log = oplog::scope_after(&c, &space, 0, 100_000).unwrap();
        let m = model::project(log.iter());
        let mut want: Vec<String> = m
            .rows
            .get("read_state")
            .into_iter()
            .flatten()
            .map(|(k, row)| {
                format!(
                    "{k}|{}|{}",
                    row.fields["last_read_message_id"].as_str().unwrap(),
                    row.fields["last_read_message_at"]
                )
            })
            .collect();
        want.sort();
        let q = "SELECT channel_id || '|' || account_id || '|' || reader_member_id || '|' || last_read_message_id || '|' || last_read_message_at FROM read_state";
        let got: Vec<String> = dump(&c, q)
            .into_iter()
            .map(|s| s.trim_start_matches("Text(\"").trim_end_matches("\")").to_string())
            .collect();
        assert_eq!(got, want, "seed {seed}");
        let _ = acct;
        project::rebuild(&mut c).unwrap();
        let rebuilt: Vec<String> = dump(&c, q)
            .into_iter()
            .map(|s| s.trim_start_matches("Text(\"").trim_end_matches("\")").to_string())
            .collect();
        assert_eq!(rebuilt, want, "seed {seed}: rebuild");
    }
}
