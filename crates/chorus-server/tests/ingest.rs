//! Op ingestion semantics (must match `chorus_core::sync::MemServer`, which the simulator checks).

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{auth, db, ingest, oplog};
use rusqlite::Connection;
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;

fn setup() -> (Connection, String, String) {
    let mut c = db::open_memory().unwrap();
    db::migrate(&mut c).unwrap();
    let a = new_id(1, [1; 10]);
    let b = new_id(1, [2; 10]);
    for (id, admin) in [(&a, true), (&b, false)] {
        c.execute(
            "INSERT INTO account(id, kind, created_at, is_admin) VALUES (?1, 'system', 0, ?2)",
            rusqlite::params![id, admin],
        )
        .unwrap();
        ingest::grant(&c, id, &format!("account:{id}")).unwrap();
    }
    (c, a, b)
}

fn session(account: &str, offset_ms: i64) -> ingest::Session {
    ingest::Session {
        account_id: account.into(),
        device_id: "dev".into(),
        sample: ClockSample { server_time: NOW, mono: None, boot_id: None, offset_ms },
    }
}

fn op(n: u8, kind: &str, scope: &str, payload: Value, device_at: i64) -> Op {
    Op {
        id: new_id(NOW as u64, [n; 10]),
        kind: kind.into(),
        v: 1,
        scope: scope.into(),
        entity_id: Some(new_id(NOW as u64, [n.wrapping_add(100); 10])),
        hlc: Hlc::new(device_at as u64, 0, 1),
        device_at,
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

#[test]
fn accepts_stamps_and_is_idempotent() {
    let (c, a, _) = setup();
    let s = session(&a, 10_000); // device clock 10 s behind
    let o = op(1, "member.create", &format!("account:{a}"), json!({"name": "Kai"}), NOW - 60_000);
    let (r1, new) = ingest::accept(&c, &s, o.clone(), NOW, false).unwrap();
    assert!(new.is_some());
    assert_eq!(r1.seq, Some(1));
    assert_eq!(r1.occurred_at, Some(NOW - 50_000), "offset applied");
    assert_eq!(r1.account_id.as_deref(), Some(a.as_str()));
    let (r2, again) = ingest::accept(&c, &s, o, NOW + 5, false).unwrap();
    assert!(again.is_none());
    assert_eq!(r2, r1, "duplicate returns the stored stamp");
    assert_eq!(oplog::count(&c).unwrap(), 1);
}

#[test]
fn rejects_invalid_and_forbidden() {
    let (c, a, b) = setup();
    let s = session(&a, 0);
    let bad = op(2, "member.set", &format!("account:{a}"), json!({"account_id": "evil"}), NOW);
    let (r, _) = ingest::accept(&c, &s, bad, NOW, false).unwrap();
    assert_eq!(r.error.unwrap().code, "bad_request");
    let foreign = op(3, "member.create", &format!("account:{b}"), json!({"name": "x"}), NOW);
    let (r, _) = ingest::accept(&c, &s, foreign, NOW, false).unwrap();
    assert_eq!(r.error.unwrap().code, "forbidden");
    assert_eq!(oplog::count(&c).unwrap(), 0);
}

#[test]
fn server_scope_is_admin_only() {
    let (c, a, b) = setup();
    let emoji = |n| op(n, "emoji.create", "server", json!({"name": "kai_wave", "blob_hash": "x"}), NOW);
    let (r, _) = ingest::accept(&c, &session(&b, 0), emoji(4), NOW, false).unwrap();
    assert_eq!(r.error.unwrap().code, "forbidden");
    let (r, _) = ingest::accept(&c, &session(&a, 0), emoji(5), NOW, false).unwrap();
    assert!(r.error.is_none());
}

#[test]
fn restore_window_preserves_stamps_only_while_open() {
    let (c, a, b) = setup();
    ingest::grant(&c, &a, "space:shared").ok();
    let shared = format!("space:{}", new_id(1, [9; 10]));
    for acct in [&a, &b] {
        ingest::grant(&c, acct, &shared).unwrap();
    }
    // an op B wrote in an earlier epoch, re-pushed by A's device
    let mut o = op(
        6,
        "message.send",
        &shared,
        json!({"channel_id": "c", "authors": [], "text": "hi", "entities": []}),
        NOW - 1000,
    );
    o.account_id = Some(b.clone());
    o.device_id = Some("b-phone".into());
    o.occurred_at = Some(NOW - 999);
    o.received_at = Some(NOW - 998);
    // window closed: treated as a fresh op from the pusher
    let (r, _) = ingest::accept(&c, &session(&a, 0), o.clone(), NOW, true).unwrap();
    assert_eq!(r.account_id.as_deref(), Some(a.as_str()));
    // window open: authorship and times kept
    db::set_meta(&c, "restore_open", "1").unwrap();
    let mut o2 = o.clone();
    o2.id = new_id(NOW as u64, [7; 10]);
    let (r, _) = ingest::accept(&c, &session(&a, 0), o2, NOW, true).unwrap();
    assert_eq!(r.account_id.as_deref(), Some(b.as_str()));
    assert_eq!(r.occurred_at, Some(NOW - 999));
    let restored: bool = c.query_row("SELECT restored FROM op WHERE seq = ?1", [r.seq.unwrap()], |x| x.get(0)).unwrap();
    assert!(restored);
}

#[test]
fn enrolment_ops_are_readable_through_the_log() {
    let mut c = db::open_memory().unwrap();
    db::migrate(&mut c).unwrap();
    let code = auth::create_invite(&c, auth::InviteKind::System, None, "t", auth::INVITE_TTL_MS, 1, NOW).unwrap();
    use base64::Engine as _;
    use p256::pkcs8::EncodePublicKey;
    let sk = p256::ecdsa::SigningKey::from_slice(&[5; 32]).unwrap();
    let pk =
        base64::engine::general_purpose::STANDARD.encode(sk.verifying_key().to_public_key_der().unwrap().as_bytes());
    let e = auth::redeem(
        &mut c,
        &code,
        &auth::DeviceIn { name: "p".into(), platform: "android".into(), public_key: pk },
        Some(&auth::AccountIn { display_name: None, handle: None }),
        NOW,
        1000,
    )
    .unwrap();
    let scopes = ingest::scopes_of(&c, &e.account_id).unwrap();
    let space = scopes.iter().find(|s| s.starts_with("space:")).unwrap();
    assert_eq!(oplog::scope_after(&c, space, 0, 100).unwrap().len(), 3);
    assert_eq!(oplog::digest(&c, space).unwrap().count, 3);
}
