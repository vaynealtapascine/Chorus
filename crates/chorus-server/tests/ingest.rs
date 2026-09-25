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
fn duplicate_live_emoji_names_are_rejected_without_losing_ops() {
    let (c, admin, other) = setup();
    let first = op(31, "emoji.create", "server", json!({"name":"ka_wave","blob_hash":"a".repeat(64)}), NOW);
    let first_id = first.entity_id.clone().unwrap();
    assert!(ingest::accept(&c, &session(&admin, 0), first, NOW, false).unwrap().0.error.is_none());
    let second = op(32, "emoji.create", "server", json!({"name":"ka_wave","blob_hash":"b".repeat(64)}), NOW + 1);
    let (rejected, _) = ingest::accept(&c, &session(&admin, 0), second.clone(), NOW + 1, false).unwrap();
    assert_eq!(rejected.error.unwrap().code, "conflict");
    assert_eq!(oplog::count(&c).unwrap(), 1);
    let mut retired = op(33, "emoji.delete", "server", json!({}), NOW + 2);
    retired.entity_id = Some(first_id.clone());
    assert!(ingest::accept(&c, &session(&admin, 0), retired, NOW + 2, false).unwrap().0.error.is_none());
    assert!(ingest::accept(&c, &session(&admin, 0), second, NOW + 3, false).unwrap().0.error.is_none());
    let mut restore = op(34, "emoji.restore", "server", json!({}), NOW + 4);
    restore.entity_id = Some(first_id);
    let (rejected, _) = ingest::accept(&c, &session(&admin, 0), restore, NOW + 4, false).unwrap();
    assert_eq!(rejected.error.unwrap().code, "conflict");
    let nonadmin = op(35, "emoji.create", "server", json!({"name":"other","blob_hash":"c".repeat(64)}), NOW + 5);
    let (rejected, _) = ingest::accept(&c, &session(&other, 0), nonadmin, NOW + 5, false).unwrap();
    assert_eq!(rejected.error.unwrap().code, "forbidden");
    assert_eq!(oplog::count(&c).unwrap(), 3);
}

#[test]
fn trash_restore_is_limited_to_creator_or_deleter() {
    let (c, a, b) = setup();
    let third = new_id(1, [3; 10]);
    c.execute("INSERT INTO account(id, kind, created_at) VALUES (?1, 'system', 0)", [&third]).unwrap();
    let shared = format!("space:{}", new_id(1, [9; 10]));
    for account in [&a, &b, &third] {
        ingest::grant(&c, account, &shared).unwrap();
    }
    let created =
        op(20, "message.send", &shared, json!({"channel_id":"c","authors":[],"text":"hi","entities":[]}), NOW);
    let message_id = created.entity_id.clone().unwrap();
    assert!(ingest::accept(&c, &session(&a, 0), created, NOW, false).unwrap().0.error.is_none());
    let mut deleted = op(21, "message.delete", &shared, json!({}), NOW + 1);
    deleted.entity_id = Some(message_id.clone());
    assert!(ingest::accept(&c, &session(&b, 0), deleted, NOW + 1, false).unwrap().0.error.is_none());

    let restore = |n, account: &str| {
        let mut request = op(n, "message.restore", &shared, json!({}), NOW + i64::from(n));
        request.entity_id = Some(message_id.clone());
        ingest::accept(&c, &session(account, 0), request, NOW + i64::from(n), false).unwrap().0
    };
    assert_eq!(restore(22, &third).error.unwrap().code, "forbidden");
    assert!(restore(23, &b).error.is_none());
    assert!(restore(24, &a).error.is_none());
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
    // a window left open closes by itself (reconcile::WINDOW_MS): the pusher is the author again
    let mut o3 = o.clone();
    o3.id = new_id(NOW as u64, [8; 10]);
    let later = NOW + chorus_server::reconcile::WINDOW_MS + 1;
    let (r, _) = ingest::accept(&c, &session(&a, 0), o3, later, true).unwrap();
    assert_eq!(r.account_id.as_deref(), Some(a.as_str()));
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

/// An op in one space can't reach into another space the author is also in: not a message into
/// its channel, a channel created in it, a reaction or a pin on its messages, or a thread under
/// one of them — not even from the first space's owner, whom the channel rules let do anything.
#[test]
fn ops_stay_in_their_own_space() {
    let (c, a, b) = setup();
    let (x, y) = (new_id(2, [30; 10]), new_id(2, [31; 10]));
    let (sx, sy) = (format!("space:{x}"), format!("space:{y}"));
    let (cx, cy, my) = (new_id(2, [32; 10]), new_id(2, [33; 10]), new_id(2, [34; 10]));
    // a owns X; b owns Y and lets a in (spaces.rs grants the scope before the ops)
    ingest::grant(&c, &a, &sx).unwrap();
    ingest::grant(&c, &b, &sy).unwrap();
    ingest::grant(&c, &a, &sy).unwrap();
    ingest::server_op(&c, &a, "space.create", &sx, Some(&x), json!({"kind": "shared", "name": "X"}), NOW).unwrap();
    ingest::server_op(&c, &a, "space.join", &sx, Some(&x), json!({"account_id": a}), NOW).unwrap();
    ingest::server_op(&c, &b, "space.create", &sy, Some(&y), json!({"kind": "shared", "name": "Y"}), NOW).unwrap();
    ingest::server_op(&c, &b, "space.join", &sy, Some(&y), json!({"account_id": b}), NOW).unwrap();
    ingest::server_op(&c, &b, "space.join", &sy, Some(&y), json!({"account_id": a}), NOW).unwrap();
    ingest::server_op(&c, &a, "channel.create", &sx, Some(&cx), json!({"space_id": x, "name": "x"}), NOW).unwrap();
    ingest::server_op(&c, &b, "channel.create", &sy, Some(&cy), json!({"space_id": y, "name": "y"}), NOW).unwrap();
    ingest::server_op(
        &c,
        &b,
        "message.send",
        &sy,
        Some(&my),
        json!({"channel_id": cy, "text": "hi", "authors": []}),
        NOW,
    )
    .unwrap();

    let s = session(&a, 0);
    let mut n = 60u8;
    let mut push = |scope: &str, kind: &str, entity: Option<String>, payload: Value| {
        n += 1;
        let mut o = op(n, kind, scope, payload, NOW);
        o.entity_id = entity.or(o.entity_id);
        let (r, _) = ingest::accept(&c, &s, o, NOW, false).unwrap();
        r.error.map(|e| e.message)
    };
    let refused = |r: Option<String>| r.is_some_and(|m| m.contains("in another space"));
    assert!(refused(push(&sx, "message.send", None, json!({"channel_id": cy, "text": "in", "authors": []}))));
    assert!(refused(push(&sx, "channel.create", None, json!({"space_id": y, "name": "planted"}))));
    assert!(refused(push(
        &sx,
        "channel.create",
        None,
        json!({"space_id": x, "name": "t", "kind": "thread", "parent_message_id": my})
    )));
    assert!(refused(push(
        &sx,
        "reaction.add",
        Some(my.clone()),
        json!({"target_type": "message", "target_id": my, "emoji": "👍", "member_id": a})
    )));
    assert!(refused(push(&sx, "message.pin", Some(my.clone()), json!({}))));
    assert!(refused(push(&sx, "channel.set", Some(cy.clone()), json!({"name": "renamed"}))));
    // the same ops in their own spaces are fine
    assert_eq!(push(&sx, "message.send", None, json!({"channel_id": cx, "text": "ok", "authors": []})), None);
    assert_eq!(push(&sy, "message.send", None, json!({"channel_id": cy, "text": "ok", "authors": []})), None);
    assert_eq!(
        push(
            &sy,
            "reaction.add",
            Some(my.clone()),
            json!({"target_type": "message", "target_id": my, "emoji": "👍", "member_id": a})
        ),
        None
    );
}

/// Nobody speaks as another account's member: message authors, segment authors, reactions and
/// the envelope's acting member must be the sender's own (or not known yet: created offline).
#[test]
fn nobody_speaks_as_another_accounts_member() {
    let (c, a, b) = setup();
    let y = new_id(2, [40; 10]);
    let sy = format!("space:{y}");
    let (cy, my) = (new_id(2, [41; 10]), new_id(2, [42; 10]));
    let (ka, kb) = (new_id(2, [43; 10]), new_id(2, [44; 10]));
    ingest::grant(&c, &b, &sy).unwrap();
    ingest::grant(&c, &a, &sy).unwrap();
    ingest::server_op(&c, &a, "member.create", &format!("account:{a}"), Some(&ka), json!({"name": "Kai"}), NOW)
        .unwrap();
    ingest::server_op(&c, &b, "member.create", &format!("account:{b}"), Some(&kb), json!({"name": "Bee"}), NOW)
        .unwrap();
    ingest::server_op(&c, &b, "space.create", &sy, Some(&y), json!({"kind": "shared", "name": "Y"}), NOW).unwrap();
    ingest::server_op(&c, &b, "space.join", &sy, Some(&y), json!({"account_id": b}), NOW).unwrap();
    ingest::server_op(&c, &b, "space.join", &sy, Some(&y), json!({"account_id": a}), NOW).unwrap();
    ingest::server_op(&c, &b, "channel.create", &sy, Some(&cy), json!({"space_id": y, "name": "y"}), NOW).unwrap();
    ingest::server_op(
        &c,
        &b,
        "message.send",
        &sy,
        Some(&my),
        json!({"channel_id": cy, "text": "hi", "authors": [kb]}),
        NOW,
    )
    .unwrap();

    let s = session(&a, 0);
    let mut n = 80u8;
    let mut push = |kind: &str, entity: Option<String>, member: Option<&str>, payload: Value| {
        n += 1;
        let mut o = op(n, kind, &sy, payload, NOW);
        o.entity_id = entity.or(o.entity_id);
        o.member_id = member.map(str::to_string);
        let (r, _) = ingest::accept(&c, &s, o, NOW, false).unwrap();
        r.error.map(|e| e.message)
    };
    let refused = |r: Option<String>| r.is_some_and(|m| m.contains("another account"));
    assert!(refused(push("message.send", None, None, json!({"channel_id": cy, "text": "as Bee", "authors": [kb]}))));
    assert!(refused(push(
        "message.send",
        None,
        None,
        json!({"channel_id": cy, "text": "ab", "authors": [ka], "segments": [{"offset": 0, "length": 1, "authors": [ka]}, {"offset": 1, "length": 1, "authors": [kb]}]})
    )));
    assert!(refused(push(
        "reaction.add",
        Some(my.clone()),
        None,
        json!({"target_type": "message", "target_id": my, "emoji": "👍", "member_id": kb})
    )));
    assert!(refused(push("message.send", None, Some(&kb), json!({"channel_id": cy, "text": "x", "authors": [ka]}))));
    // own members, and members the server hasn't seen yet, are fine
    assert_eq!(
        push("message.send", None, Some(&ka), json!({"channel_id": cy, "text": "as Kai", "authors": [ka]})),
        None
    );
    let offline = new_id(2, [45; 10]);
    assert_eq!(push("message.send", None, None, json!({"channel_id": cy, "text": "new", "authors": [offline]})), None);
    assert_eq!(
        push(
            "reaction.add",
            Some(my.clone()),
            None,
            json!({"target_type": "message", "target_id": my, "emoji": "👍", "member_id": ka})
        ),
        None
    );
}

/// Moderators delete and pin other people's messages; nobody edits them, the space's owner
/// included (D-073).
#[test]
fn only_the_author_edits_a_message() {
    let (c, a, b) = setup();
    let y = new_id(2, [50; 10]);
    let sy = format!("space:{y}");
    let (cy, mb, ma) = (new_id(2, [51; 10]), new_id(2, [52; 10]), new_id(2, [53; 10]));
    ingest::grant(&c, &a, &sy).unwrap();
    ingest::grant(&c, &b, &sy).unwrap();
    // a owns the space; b is a member and writes a message
    ingest::server_op(&c, &a, "space.create", &sy, Some(&y), json!({"kind": "shared", "name": "Y"}), NOW).unwrap();
    ingest::server_op(&c, &a, "space.join", &sy, Some(&y), json!({"account_id": a}), NOW).unwrap();
    ingest::server_op(&c, &a, "space.join", &sy, Some(&y), json!({"account_id": b}), NOW).unwrap();
    ingest::server_op(&c, &a, "channel.create", &sy, Some(&cy), json!({"space_id": y, "name": "y"}), NOW).unwrap();
    ingest::server_op(
        &c,
        &b,
        "message.send",
        &sy,
        Some(&mb),
        json!({"channel_id": cy, "text": "b's words", "authors": []}),
        NOW,
    )
    .unwrap();
    ingest::server_op(
        &c,
        &a,
        "message.send",
        &sy,
        Some(&ma),
        json!({"channel_id": cy, "text": "a's words", "authors": []}),
        NOW,
    )
    .unwrap();
    let mut n = 90u8;
    let mut push = |who: &str, kind: &str, msg: &str, payload: Value| {
        n += 1;
        let mut o = op(n, kind, &sy, payload, NOW);
        o.entity_id = Some(msg.to_string());
        let (r, _) = ingest::accept(&c, &session(who, 0), o, NOW, false).unwrap();
        r.error.map(|e| e.message)
    };
    assert!(
        push(&a, "message.edit", &mb, json!({"message_id": mb, "text": "rewritten"}))
            .is_some_and(|m| m.contains("only its author"))
    );
    assert_eq!(push(&a, "message.pin", &mb, json!({})), None, "the owner still moderates");
    assert_eq!(push(&a, "message.delete", &mb, json!({})), None);
    assert_eq!(push(&b, "message.edit", &mb, json!({"message_id": mb, "text": "my fix"})), None);
    assert!(push(&b, "message.edit", &ma, json!({"message_id": ma, "text": "nope"})).is_some());
}
