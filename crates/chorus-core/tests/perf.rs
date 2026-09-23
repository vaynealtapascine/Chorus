//! Performance budgets that live in the core (SPEC.md §9). Ignored by default because they only
//! mean something in release builds: `cargo test --release -p chorus-core --test perf -- --ignored --nocapture`.

use std::time::Instant;

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::model;
use chorus_core::op::Op;
use chorus_core::replica::{DeviceNow, NewOp, Replica};
use chorus_core::time::TimeSource;
use serde_json::json;

const ACCT: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const SPACE: &str = "0192f8c2-0000-7000-8000-0000000000d1";
const T0: i64 = 1_790_000_000_000;

fn op(n: u64, kind: &str, scope: &str, entity: String, payload: serde_json::Value) -> Op {
    let at = T0 + n as i64 * 1000;
    Op {
        id: new_id(at as u64, (n as u128).to_be_bytes()[6..].try_into().unwrap()),
        kind: kind.into(),
        v: 1,
        scope: scope.into(),
        entity_id: Some(entity),
        hlc: Hlc::new(at as u64, 0, 1),
        device_at: at,
        tz_offset_min: 0,
        mono: None,
        boot_id: None,
        time_source: TimeSource::Auto,
        seen_seq: 0,
        member_id: None,
        payload,
        seq: Some(n as i64),
        account_id: Some(ACCT.into()),
        device_id: Some("dev".into()),
        occurred_at: Some(at),
        received_at: Some(at),
    }
}

/// A big, realistic account: 300 members, 5 000 switches, 50 000 messages.
fn history() -> Vec<Op> {
    let acct = format!("account:{ACCT}");
    let space = format!("space:{SPACE}");
    let mut ops = Vec::new();
    let mut n = 0u64;
    let mut next = || {
        n += 1;
        n
    };
    let members: Vec<String> =
        (0..300).map(|i| new_id(T0 as u64, [1, 0, 0, 0, 0, 0, 0, 0, (i >> 8) as u8, i as u8])).collect();
    for (i, m) in members.iter().enumerate() {
        ops.push(op(next(), "member.create", &acct, m.clone(), json!({"name": format!("M{i}"), "color": "#C0694E"})));
    }
    for i in 0..5_000u64 {
        let m = &members[(i * 7 % 300) as usize];
        let e = json!({"entries": [{"subject_type": "member", "subject_id": m, "level": "front", "is_primary": true}]});
        ops.push(op(next(), "front.switch", &acct, new_id(T0 as u64 + i, [2; 10]), e));
    }
    for i in 0..50_000u64 {
        let m = &members[(i * 13 % 300) as usize];
        let text = format!("message number {i} with a little bit of text in it");
        ops.push(op(
            next(),
            "message.send",
            &space,
            new_id(T0 as u64 + i, [3, 0, 0, 0, 0, 0, (i >> 24) as u8, (i >> 16) as u8, (i >> 8) as u8, i as u8]),
            json!({"channel_id": "general", "authors": [m], "text": text, "entities": []}),
        ));
    }
    ops
}

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

#[test]
#[ignore]
fn budgets() {
    let ops = history();
    let t = Instant::now();
    let p = model::project(ops.iter());
    let full = ms(t);
    assert!(p.rows["message"].len() == 50_000);
    println!("full projection of {} ops: {full:.1} ms", ops.len());

    let t = Instant::now();
    let mut r = Replica::restore("dev", 1, None, ops, None);
    println!("replica restore: {:.1} ms", ms(t));

    let t = Instant::now();
    r.projection();
    println!("first projection (build + index): {:.1} ms", ms(t));
    let _ = r.projection_delta(); // the UI read everything once

    // "send message → rendered locally ≤ 50 ms": create the op, then what the UI reads
    let t = Instant::now();
    let d = DeviceNow { now: T0 + 999_999_999, tz_offset_min: 0, mono: None, boot_id: None };
    let n = NewOp {
        kind: "message.send".into(),
        scope: format!("space:{SPACE}"),
        entity_id: Some(new_id(T0 as u64 + 999_999, [9; 10])),
        payload: json!({"channel_id": "general", "authors": [], "text": "hello", "entities": []}),
        member_id: None,
        user_time: None,
    };
    r.create(n, &d, [7; 10]).unwrap();
    let create = ms(t);
    let t = Instant::now();
    let delta = r.projection_delta();
    let project = ms(t);
    let t = Instant::now();
    let json = serde_json::to_string(&delta).unwrap();
    let serialize = ms(t);
    let total = create + project + serialize;
    println!(
        "send: create {create:.2} ms, incremental projection {project:.2} ms, delta JSON {serialize:.2} ms ({} bytes) = {total:.2} ms",
        json.len(),
    );
    assert!(total < 50.0, "SPEC §9: send → rendered locally ≤ 50 ms (native; wasm is slower)");
}
