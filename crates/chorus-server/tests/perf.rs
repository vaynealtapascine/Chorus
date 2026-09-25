//! Server budgets (SPEC.md §9): op ingestion ≥ 2 000 ops/s sustained, and rebuilding every
//! projection from a 1M-op log ≤ 60 s. Ignored by default (release build, a file-backed database,
//! minutes of work):
//!
//! `cargo test --release -p chorus-server --test perf -- --ignored --nocapture`
//!
//! `CHORUS_PERF_OPS` sets the log size (default 1 000 000), `CHORUS_PERF_DIR` where the database
//! goes (default: the system temp directory). To time rebuilds without ingesting every time, set
//! `CHORUS_PERF_DB=<file>`: the first run keeps a copy of the ingested database there, later runs
//! start from that copy and only rebuild.

use std::time::Instant;

// the server binary's allocator (main.rs), so the budgets measure what runs
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, ingest, project};
use serde_json::{Value, json};

const T0: i64 = 1_790_000_000_000;
/// Time between ops: 30 s, so the 3 % switches come ~90 a day (a heavy switcher; 1M ops ≈ a year).
const STEP: i64 = 30_000;

fn op(n: u64, kind: &str, scope: &str, entity: Option<String>, payload: Value) -> Op {
    let at = T0 + n as i64 * STEP;
    let mut r = [0u8; 10];
    r[..8].copy_from_slice(&n.to_be_bytes());
    Op {
        id: new_id(at as u64, r),
        kind: kind.into(),
        v: 1,
        scope: scope.into(),
        entity_id: entity,
        hlc: Hlc::new(at as u64, 0, 1),
        device_at: at,
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
    }
}

/// A plausible busy system: 20 members, 8 channels; mostly messages, with the switches, reads,
/// reactions, edits and member changes that come with them.
fn history(total: u64, acct: &str, space: &str) -> Vec<Op> {
    let acct_scope = format!("account:{acct}");
    let space_scope = format!("space:{space}");
    let mut ops = Vec::with_capacity(total as usize);
    let members: Vec<String> = (0..20u64).map(|i| new_id(T0 as u64, [i as u8 + 1; 10])).collect();
    let channels: Vec<String> = (0..8u64).map(|i| new_id(T0 as u64, [i as u8 + 100; 10])).collect();
    let mut n = 0u64;
    let mut next = || {
        n += 1;
        n
    };
    ops.push(op(next(), "space.create", &space_scope, Some(space.into()), json!({"kind": "internal", "name": "Home"})));
    for (i, m) in members.iter().enumerate() {
        ops.push(op(
            next(),
            "member.create",
            &acct_scope,
            Some(m.clone()),
            json!({"name": format!("m{i}"), "color": "#C0694E"}),
        ));
    }
    for (i, c) in channels.iter().enumerate() {
        ops.push(op(
            next(),
            "channel.create",
            &space_scope,
            Some(c.clone()),
            json!({"space_id": space, "kind": "text", "name": format!("c{i}")}),
        ));
    }
    let mut last_msg: Vec<Option<(String, i64)>> = vec![None; channels.len()];
    while (ops.len() as u64) < total {
        let k = next();
        let ch = (k % channels.len() as u64) as usize;
        let who = &members[(k / 7 % members.len() as u64) as usize];
        let at = T0 + k as i64 * STEP;
        match k % 100 {
            0..=2 => {
                let e = json!([{"subject_type": "member", "subject_id": who, "level": "front", "is_primary": true}]);
                ops.push(op(
                    k,
                    "front.switch",
                    &acct_scope,
                    Some(new_id(at as u64, [(k % 241) as u8; 10])),
                    json!({"entries": e}),
                ));
            }
            3..=9 => {
                if let Some((id, at)) = &last_msg[ch] {
                    ops.push(op(
                        k,
                        "read.mark",
                        &space_scope,
                        None,
                        json!({"channel_id": channels[ch], "message_id": id, "message_at": at, "reader_member_id": ""}),
                    ));
                }
            }
            10..=13 => {
                if let Some((id, _)) = &last_msg[ch] {
                    ops.push(op(
                        k,
                        "reaction.add",
                        &space_scope,
                        Some(id.clone()),
                        json!({"target_type": "message", "target_id": id, "emoji": "💜", "member_id": who}),
                    ));
                }
            }
            14..=15 => {
                if let Some((id, _)) = &last_msg[ch] {
                    ops.push(op(
                        k,
                        "message.edit",
                        &space_scope,
                        Some(id.clone()),
                        json!({"text": "edited", "entities": []}),
                    ));
                }
            }
            16 => ops.push(op(k, "member.set", &acct_scope, Some(who.clone()), json!({"pronouns": format!("p{k}")}))),
            _ => {
                let id = new_id(at as u64, [(k % 251) as u8; 10]);
                let text = format!("message {k} with a few words in it, like people write");
                ops.push(op(
                    k,
                    "message.send",
                    &space_scope,
                    Some(id.clone()),
                    json!({"channel_id": channels[ch], "authors": [who], "text": text, "entities": []}),
                ));
                last_msg[ch] = Some((id, at));
            }
        }
    }
    ops
}

#[test]
#[ignore]
fn ingest_and_rebuild_budgets() {
    let total: u64 = std::env::var("CHORUS_PERF_OPS").ok().and_then(|s| s.parse().ok()).unwrap_or(1_000_000);
    let dir = std::env::var("CHORUS_PERF_DIR").map(std::path::PathBuf::from).unwrap_or_else(|_| std::env::temp_dir());
    let path = dir.join(format!("chorus-perf-{}.db", std::process::id()));
    let saved = std::env::var("CHORUS_PERF_DB").ok().map(std::path::PathBuf::from);
    if let Some(saved) = saved.as_ref().filter(|p| p.exists()) {
        std::fs::copy(saved, &path).unwrap();
        let mut c = db::open(&path).unwrap();
        db::migrate(&mut c).unwrap(); // a copy kept by an older build
        println!("rebuilding a copy of {} in place", saved.display());
        let (in_place, _) = timed_rebuild(&mut c);
        drop(c);
        remove_db(&path);
        // what `chorus-server rebuild` does (R19): into a fresh file, then swapped in
        std::fs::copy(saved, &path).unwrap();
        db::migrate(&mut db::open(&path).unwrap()).unwrap();
        println!("rebuilding a copy of {} into a fresh file", saved.display());
        let (swapped, _) = timed_rebuild_swap(&path);
        remove_db(&path);
        if total >= 1_000_000 {
            assert!(in_place.min(swapped) <= 60.0, "SPEC §9: rebuild of a 1M-op log ≤ 60 s");
        }
        return;
    }
    let mut c = db::open(&path).unwrap();
    db::migrate(&mut c).unwrap();
    let acct = new_id(1, [9; 10]);
    let space = new_id(1, [8; 10]);
    c.execute("INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'system', 'perf', 0)", [&acct]).unwrap();
    ingest::grant(&c, &acct, &format!("account:{acct}")).unwrap();
    ingest::grant(&c, &acct, &format!("space:{space}")).unwrap();
    let ops = history(total, &acct, &space);
    let s = ingest::Session {
        account_id: acct.clone(),
        device_id: "dev".into(),
        sample: ClockSample { server_time: T0, mono: None, boot_id: None, offset_ms: 0 },
    };

    // ingestion: push frames of 100 ops, one transaction each, as the sync socket does
    let t = Instant::now();
    let mut window = Instant::now();
    let mut worst = f64::MAX;
    let mut by_kind: std::collections::BTreeMap<String, (u64, f64)> = Default::default();
    for (i, chunk) in ops.chunks(100).enumerate() {
        let tx = c.transaction().unwrap();
        for o in chunk {
            let now = o.device_at;
            let one = Instant::now();
            let (r, _) = ingest::accept(&tx, &s, o.clone(), now, false).unwrap();
            let e = by_kind.entry(o.kind.clone()).or_default();
            e.0 += 1;
            e.1 += one.elapsed().as_secs_f64();
            assert!(r.error.is_none(), "{}: {:?}", o.kind, r.error);
        }
        tx.commit().unwrap();
        if (i + 1) % 100 == 0 {
            let rate = 10_000.0 / window.elapsed().as_secs_f64();
            worst = worst.min(rate);
            println!("ingested {:>8}: {rate:>7.0} ops/s over the last 10k", (i + 1) * 100);
            window = Instant::now();
        }
    }
    let secs = t.elapsed().as_secs_f64();
    println!(
        "ingest: {} ops in {secs:.1} s = {:.0} ops/s (slowest 10k window {worst:.0} ops/s)",
        ops.len(),
        ops.len() as f64 / secs
    );
    for (kind, (n, t)) in &by_kind {
        println!("  {kind:<14} {n:>8} ops  {:>8.1} s total  {:>8.3} ms each", t, t * 1000.0 / *n as f64);
    }

    let (sw, distinct): (i64, i64) = c
        .query_row("SELECT count(*), count(DISTINCT occurred_at) FROM switch", [], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap();
    println!("switch rows {sw}, distinct times {distinct}");
    if let Some(saved) = &saved {
        c.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)").unwrap();
        std::fs::copy(&path, saved).unwrap();
        println!("kept the ingested database as {}", saved.display());
    }
    let (rebuild, _) = timed_rebuild(&mut c);

    drop(c);
    remove_db(&path);
    assert!(worst >= 2000.0, "SPEC §9: ingestion ≥ 2 000 ops/s sustained");
    if total >= 1_000_000 {
        assert!(rebuild <= 60.0, "SPEC §9: rebuild of a 1M-op log ≤ 60 s");
    }
}

/// Rebuild every projection, printing the time per op kind and phase: (seconds, ops).
fn timed_rebuild(c: &mut rusqlite::Connection) -> (f64, u64) {
    let t = Instant::now();
    let mut by_kind: std::collections::BTreeMap<String, (u64, f64)> = Default::default();
    let n = project::rebuild_timed(c, &mut |kind, d| {
        let e = by_kind.entry(kind.to_string()).or_default();
        e.0 += 1;
        e.1 += d.as_secs_f64();
    })
    .unwrap();
    let rebuild = t.elapsed().as_secs_f64();
    println!("rebuild: {n} ops in {rebuild:.1} s");
    for (kind, (n, t)) in &by_kind {
        println!("  {kind:<14} {n:>8} ops  {:>8.1} s total  {:>8.3} ms each", t, t * 1000.0 / *n as f64);
    }
    (rebuild, n)
}

/// [`timed_rebuild`] the `chorus-server rebuild` way (`project::rebuild_swap`).
fn timed_rebuild_swap(path: &std::path::Path) -> (f64, u64) {
    let t = Instant::now();
    let mut by_kind: std::collections::BTreeMap<String, (u64, f64)> = Default::default();
    let n = project::rebuild_swap_timed(path, &mut |kind, d| {
        let e = by_kind.entry(kind.to_string()).or_default();
        e.0 += 1;
        e.1 += d.as_secs_f64();
    })
    .unwrap();
    let rebuild = t.elapsed().as_secs_f64();
    println!("rebuild into a fresh file and swap: {n} ops in {rebuild:.1} s");
    for (kind, (n, t)) in by_kind.iter().filter(|(k, _)| k.starts_with('(')) {
        println!("  {kind:<34} {n:>3}  {t:>8.1} s");
    }
    (rebuild, n)
}

fn remove_db(path: &std::path::Path) {
    for ext in ["", "-wal", "-shm"] {
        let _ = std::fs::remove_file(format!("{}{ext}", path.display()));
    }
}
