//! The incremental projector equals the reference projection, always (projector.rs).
//!
//! Random histories over many op kinds go through random sync steps: ops arrive in any order,
//! local (unstamped) copies are later replaced by server-stamped ones, and some ops get rejected
//! (disappear). After every step the projector must equal `model::project` over the same ops, and
//! a client that only ever applied the deltas must hold the same state.

use std::collections::BTreeMap;

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::model;
use chorus_core::op::Op;
use chorus_core::projector::{Delta, Projector};
use chorus_core::time::TimeSource;
use proptest::prelude::*;
use serde_json::{Value, json};

const A: &str = "0192f8c2-0000-7000-8000-0000000000a1";

fn ids(prefix: u8, n: u8) -> Vec<String> {
    (0..n).map(|i| new_id(1, [prefix, i, 0, 0, 0, 0, 0, 0, 0, 1])).collect()
}

/// One op from a small universe so keys collide often.
fn make(i: usize, pick: u8, x: u8, t: u64, node: u32) -> Op {
    let members = ids(1, 3);
    let msgs = ids(2, 3);
    let groups = ids(3, 2);
    let acct = format!("account:{A}");
    let space = format!("space:{}", new_id(1, [9; 10]));
    let m = members[(x % 3) as usize].clone();
    let msg = msgs[(x % 3) as usize].clone();
    let (kind, scope, entity, payload): (&str, &str, String, Value) = match pick % 27 {
        0 => ("member.create", &acct, m, json!({"name": format!("n{i}")})),
        1 => ("member.set", &acct, m, json!({"color": format!("#0000{:02x}", x)})),
        2 => (
            ["member.delete", "member.restore", "member.archive", "member.unarchive"][(x % 4) as usize],
            &acct,
            m,
            json!({}),
        ),
        3 => (
            "group.add_member",
            &acct,
            groups[(x % 2) as usize].clone(),
            json!({"member_id": members[(x % 3) as usize]}),
        ),
        4 => (
            "group.remove_member",
            &acct,
            groups[(x % 2) as usize].clone(),
            json!({"member_id": members[(x % 3) as usize]}),
        ),
        5 => (
            "front.switch",
            &acct,
            new_id(t, [5, x, 0, 0, 0, 0, 0, 0, 0, i as u8]),
            json!({"entries": [{"subject_type": "member", "subject_id": m, "is_primary": true}]}),
        ),
        6 => (
            "front.add",
            &acct,
            new_id(t, [6, x, 0, 0, 0, 0, 0, 0, 0, i as u8]),
            json!({"entry": {"subject_type": "member", "subject_id": m, "level": "cocon"}}),
        ),
        7 => (
            "message.send",
            &space,
            msg,
            json!({"channel_id": "c", "authors": [m], "text": format!("m{i}"), "entities": []}),
        ),
        8 => ("message.edit", &space, msg.clone(), json!({"message_id": msg, "text": format!("e{i}"), "entities": []})),
        9 => (if x.is_multiple_of(2) { "message.pin" } else { "message.unpin" }, &space, msg, json!({})),
        10 => ("message.delete", &space, msg, json!({})),
        11 => (
            if x.is_multiple_of(2) { "reaction.add" } else { "reaction.remove" },
            &space,
            msg.clone(),
            json!({"message_id": msg, "member_id": m, "emoji": if x.is_multiple_of(3) { "👍" } else { "🎉" }}),
        ),
        12 => (
            "read.mark",
            &space,
            msg.clone(),
            json!({"channel_id": "c", "reader_member_id": m, "message_id": msg, "message_at": x as i64}),
        ),
        13 => (
            "read.set",
            &space,
            msg.clone(),
            json!({"channel_id": "c", "reader_member_id": m, "message_id": msg, "message_at": x as i64}),
        ),
        14 => ("field.set_value", &acct, m.clone(), json!({"member_id": m, "field_id": "f", "value": x})),
        15 => (
            "stage.save",
            &acct,
            ids(4, 2)[(x % 2) as usize].clone(),
            json!({"name": format!("s{i}"), "definition": {}}),
        ),
        16 => ("bucket.set", &acct, ids(5, 2)[(x % 2) as usize].clone(), json!({"name": format!("b{i}")})),
        17 => ("follow.accept", &acct, ids(6, 1)[0].clone(), json!({})),
        18 => ("pref.set", &acct, m, json!({"device": "", "key": "k", "value": x})),
        19 => (
            "draft.set",
            &acct,
            ids(7, 2)[(x % 2) as usize].clone(),
            json!({"context": "post", "text": format!("draft{i}")}),
        ),
        20 => (
            "feed.set",
            &acct,
            ids(8, 2)[(x % 2) as usize].clone(),
            json!({"name": format!("feed{i}"), "query": "kind:entry"}),
        ),
        21 => ("list.set", &acct, ids(9, 2)[(x % 2) as usize].clone(), json!({"name": format!("list{i}")})),
        22 => ("reltype.set", &acct, ids(10, 2)[(x % 2) as usize].clone(), json!({"name": format!("friend{i}")})),
        23 => (
            "relationship.set",
            &acct,
            ids(11, 2)[(x % 2) as usize].clone(),
            json!({"from_member_id": m, "to_kind": "external", "to_label": format!("person{i}")}),
        ),
        24 => (
            "post.create",
            &acct,
            ids(12, 2)[(x % 2) as usize].clone(),
            json!({"kind": "note", "authors": [m], "text": format!("post{i}"), "entities": [], "visibility": {"mode":"private"}}),
        ),
        25 => (
            "post.react",
            &acct,
            ids(12, 2)[(x % 2) as usize].clone(),
            json!({"target_type":"post","target_id":ids(12, 2)[(x % 2) as usize],"emoji":"💜","member_id":m}),
        ),
        _ => ("mystery.kind", &acct, m, json!({})),
    };
    Op {
        id: new_id(t, [7, (i >> 8) as u8, i as u8, pick, x, 0, 0, 0, 0, node as u8]),
        kind: kind.into(),
        v: 1,
        scope: scope.to_string(),
        entity_id: Some(entity),
        hlc: Hlc::new(t, 0, node),
        device_at: t as i64,
        tz_offset_min: 0,
        mono: None,
        boot_id: None,
        time_source: TimeSource::Auto,
        seen_seq: 0,
        member_id: None,
        payload,
        // local copy: no stamp yet
        seq: None,
        account_id: None,
        device_id: None,
        occurred_at: None,
        received_at: None,
    }
}

fn stamped(o: &Op, seq: i64) -> Op {
    let mut s = o.clone();
    s.seq = Some(seq);
    s.account_id = Some(A.into());
    s.device_id = Some("d".into());
    s.occurred_at = Some(o.device_at + 7);
    s.received_at = Some(o.device_at + 9);
    s
}

/// Apply a delta to a client-side JSON copy (what the web client does).
fn apply(copy: &mut Value, d: &Delta, full: &Value) {
    if d.full {
        *copy = full.clone();
        return;
    }
    let dj = serde_json::to_value(d).unwrap();
    for (section, key) in [("rows", "rows"), ("sets", "sets")] {
        for (table, entries) in dj[section].as_object().unwrap() {
            for (id, v) in entries.as_object().unwrap() {
                let t = &mut copy[key][table];
                if t.is_null() {
                    *t = json!({});
                }
                if v.is_null() {
                    t.as_object_mut().unwrap().remove(id);
                } else {
                    t[id] = v.clone();
                }
                if t.as_object().unwrap().is_empty() {
                    copy[key].as_object_mut().unwrap().remove(table);
                }
            }
        }
    }
    for section in ["fronts", "reviews"] {
        for (acct, v) in dj[section].as_object().unwrap() {
            if copy[section].is_null() {
                copy[section] = json!({});
            }
            if v.is_null() {
                copy[section].as_object_mut().unwrap().remove(acct);
            } else {
                copy[section][acct] = v.clone();
            }
        }
    }
    if copy.get("reviews").is_some_and(|r| r.as_object().is_some_and(|o| o.is_empty())) {
        copy.as_object_mut().unwrap().remove("reviews");
    }
    copy["opaque"] = json!(d.opaque);
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(300))]

    #[test]
    fn incremental_equals_reference(
        raw in prop::collection::vec((any::<u8>(), any::<u8>(), 0u64..5_000, 1u32..4), 1..60),
        steps in prop::collection::vec((0usize..60, any::<bool>(), 0u8..10), 1..25),
    ) {
        let all: Vec<Op> = raw.iter().enumerate()
            .map(|(i, (pick, x, dt, node))| make(i, *pick, *x, 1_790_000_000_000 + dt * 1000, *node))
            .collect();
        // visible state: id → current copy
        let mut visible: BTreeMap<String, Op> = BTreeMap::new();
        let mut p = Projector::new();
        let mut client = Value::Null;
        let mut seq = 0i64;
        for (idx, stamp, action) in steps {
            let o = &all[idx % all.len()];
            match action {
                // reject (disappears)
                0 => { visible.remove(&o.id); }
                // arrives stamped (server copy)
                1..=3 => { seq += 1; visible.insert(o.id.clone(), stamped(o, seq)); }
                // arrives as a local copy (unless already stamped)
                _ => {
                    let keep = visible.get(&o.id).is_some_and(|c| c.seq.is_some());
                    if !keep {
                        let copy = if stamp { seq += 1; stamped(o, seq) } else { o.clone() };
                        visible.insert(o.id.clone(), copy);
                    }
                }
            }
            p.sync(visible.values());
            let reference = model::project(visible.values()).canonical();
            prop_assert_eq!(p.projection().canonical(), reference.clone());
            let d = p.take_delta();
            apply(&mut client, &d, &reference);
            prop_assert_eq!(&client, &reference);
        }
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(300))]

    /// Opening from a snapshot (CLIENTS.md §4.3): index the persisted ops, adopt the projection
    /// the UI saved earlier — exact, stale (ops changed after it), with ops created while opening
    /// — and a client that started from that snapshot and applies the deltas stays equal to the
    /// reference through later steps.
    #[test]
    fn opening_from_a_snapshot_continues_exactly(
        raw in prop::collection::vec((any::<u8>(), any::<u8>(), 0u64..5_000, 1u32..4), 1..60),
        before in prop::collection::vec((0usize..60, 0u8..10), 1..25),
        stale in prop::collection::vec((0usize..60, 0u8..10), 0..4),
        fresh in prop::collection::vec(0usize..60, 0..4),
        after in prop::collection::vec((0usize..60, 0u8..10), 0..12),
    ) {
        let all: Vec<Op> = raw.iter().enumerate()
            .map(|(i, (pick, x, dt, node))| make(i, *pick, *x, 1_790_000_000_000 + dt * 1000, *node))
            .collect();
        let mut seq = 0i64;
        let mut step = |visible: &mut BTreeMap<String, Op>, idx: usize, action: u8| {
            let o = &all[idx % all.len()];
            match action {
                0 => { visible.remove(&o.id); }
                1..=5 => { seq += 1; visible.insert(o.id.clone(), stamped(o, seq)); }
                _ => { visible.entry(o.id.clone()).or_insert_with(|| o.clone()); }
            }
        };
        // the session that saved the snapshot
        let mut visible: BTreeMap<String, Op> = BTreeMap::new();
        for (idx, action) in &before {
            step(&mut visible, *idx, *action);
        }
        let mut first = Projector::new();
        first.sync(visible.values());
        let snapshot = model::project(visible.values()).canonical();
        let digest = first.digest();
        // ops saved after the snapshot was (it's stale then)
        for (idx, action) in &stale {
            step(&mut visible, *idx, *action);
        }
        // opening: index what was persisted, in any order; some ops get created meanwhile
        let mut p = Projector::new();
        for o in visible.values().rev() {
            p.index_op(o);
        }
        let mut created = std::collections::BTreeSet::new();
        for (n, idx) in fresh.iter().enumerate() {
            let mut o = all[idx % all.len()].clone();
            o.id = new_id(9, [200, n as u8, 0, 0, 0, 0, 0, 0, 0, 1]);
            if n % 2 == 0 {
                p.index_op(&o); // created before its slice was indexed
            }
            created.insert(o.id.clone());
            visible.insert(o.id.clone(), o);
        }
        let exact = stale.is_empty();
        let adopted = p.adopt(Some(digest), &created);
        prop_assert!(!exact || adopted, "an exact snapshot is adopted");
        p.sync(visible.values());
        let mut client = snapshot;
        let reference = model::project(visible.values()).canonical();
        let d = p.take_delta();
        prop_assert_eq!(d.full, !adopted);
        apply(&mut client, &d, &reference);
        prop_assert_eq!(&client, &reference);
        for (idx, action) in &after {
            step(&mut visible, *idx, *action);
            p.sync(visible.values());
            let reference = model::project(visible.values()).canonical();
            let d = p.take_delta();
            apply(&mut client, &d, &reference);
            prop_assert_eq!(&client, &reference);
        }
        p.materialize();
        prop_assert_eq!(p.projection().canonical(), model::project(visible.values()).canonical());
    }
}
