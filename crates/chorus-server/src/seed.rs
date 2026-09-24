//! Reproducible, deliberately new-directory seed data for manual SPEC §9 measurements.
//! These are ordinary validated ops, ingested and projected through the production path.

use anyhow::{Context, ensure};
use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

use crate::{config::Config, db, ingest, now_ms};

pub struct Counts {
    pub members: usize,
    pub switches: usize,
    pub messages: usize,
}

pub struct Seeded {
    pub account_id: String,
    pub ops: usize,
}

fn id(at: u64, serial: u64) -> String {
    let mut bytes = [0u8; 10];
    bytes[..8].copy_from_slice(&serial.to_be_bytes());
    new_id(at, bytes)
}

fn op(serial: u64, at: i64, kind: &str, scope: &str, entity: String, payload: Value) -> Op {
    Op {
        id: id(at as u64, serial * 2 + 1),
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
        seq: None,
        account_id: None,
        device_id: None,
        occurred_at: None,
        received_at: None,
    }
}

fn flush(conn: &mut Connection, session: &ingest::Session, pending: &mut Vec<Op>) -> anyhow::Result<()> {
    if pending.is_empty() {
        return Ok(());
    }
    let tx = conn.transaction()?;
    for item in pending.drain(..) {
        let now = item.device_at;
        let kind = item.kind.clone();
        let (ack, _) = ingest::accept(&tx, session, item, now, false)?;
        ensure!(ack.error.is_none(), "seed {kind} rejected: {:?}", ack.error);
    }
    tx.commit()?;
    Ok(())
}

/// Refuses an existing directory, including an empty one. Failure leaves the partial seed for
/// inspection rather than silently deleting an operator's data.
pub fn run(cfg: &Config, counts: Counts) -> anyhow::Result<Seeded> {
    if let Some(parent) = cfg.server.data_dir.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::create_dir(&cfg.server.data_dir)
        .with_context(|| format!("seed destination must be new: {}", cfg.server.data_dir.display()))?;
    let mut conn = db::open(&cfg.db_path())?;
    db::migrate(&mut conn)?;
    let base = now_ms();
    let account = id(base as u64, 1);
    let device = id(base as u64, 2);
    let space = id(base as u64, 3);
    let channel = id(base as u64, 4);
    conn.execute(
        "INSERT INTO account(id,kind,handle,is_admin,created_at) VALUES (?1,'system','seed',1,?2)",
        params![account, base],
    )?;
    conn.execute(
        "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at)
         VALUES (?1,?2,'seed','Seed generator','cli','seed',?3)",
        params![device, account, base],
    )?;
    let account_scope = format!("account:{account}");
    let space_scope = format!("space:{space}");
    ingest::grant(&conn, &account, &account_scope)?;
    ingest::grant(&conn, &account, &space_scope)?;
    let session = ingest::Session {
        account_id: account.clone(),
        device_id: device,
        sample: ClockSample { server_time: base, mono: None, boot_id: None, offset_ms: 0 },
    };
    let mut pending = Vec::with_capacity(100);
    let mut serial = 10u64;
    let mut push = |kind: &str, scope: &str, entity: String, payload: Value| -> anyhow::Result<()> {
        serial += 1;
        let at = base + serial as i64;
        pending.push(op(serial, at, kind, scope, entity, payload));
        if pending.len() == 100 {
            flush(&mut conn, &session, &mut pending)?;
        }
        Ok(())
    };
    push("space.create", &space_scope, space.clone(), json!({"kind":"internal","name":"Seed space"}))?;
    push("channel.create", &space_scope, channel.clone(), json!({"space_id":space,"kind":"text","name":"Seed chat"}))?;
    let mut members = Vec::with_capacity(counts.members);
    for index in 0..counts.members {
        let member = id(base as u64, 1_000_000 + index as u64);
        push(
            "member.create",
            &account_scope,
            member.clone(),
            json!({"name":format!("Member {}", index + 1),"color":"#C0694E"}),
        )?;
        members.push(member);
    }
    for index in 0..counts.switches {
        let entries = members
            .get(index % members.len().max(1))
            .map(|member| json!([{"subject_type":"member","subject_id":member,"level":"front","is_primary":true}]))
            .unwrap_or_else(|| json!([]));
        let entity = id(base as u64, 2_000_000 + index as u64);
        push("front.switch", &account_scope, entity, json!({"entries":entries}))?;
    }
    for index in 0..counts.messages {
        let authors = members.get(index % members.len().max(1)).map(|member| vec![member]).unwrap_or_default();
        let entity = id(base as u64, 3_000_000 + index as u64);
        push(
            "message.send",
            &space_scope,
            entity,
            json!({"channel_id":channel,"authors":authors,"text":format!("Seed message {} for search and sync", index + 1),"entities":[]}),
        )?;
    }
    flush(&mut conn, &session, &mut pending)?;
    Ok(Seeded { account_id: account, ops: 2 + counts.members + counts.switches + counts.messages })
}
