//! Handover and Notes to ourselves (D-132, DATA_MODEL "Special channels"): the special channels
//! live only in a system's own space, one live channel of each, never shared out; a handover only
//! in the Handover channel; acks and ticks only by the system, as its own live members, on a
//! handover it has (and an item its checklist has).

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{auth, db, handover, ingest, perms, project};
use rusqlite::{Connection, params};
use serde_json::{Value, json};
use std::cell::Cell;

const NOW: i64 = 1_790_000_000_000;

struct World {
    c: Connection,
    alice: String,
    bob: String,
    n: Cell<u8>,
}

/// Alice's own space and its two special channels, as the server makes them.
struct Home {
    space: String,
    scope: String,
    handover: String,
    notes: String,
}

impl World {
    fn new() -> World {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        let (alice, bob) = (new_id(1, [1; 10]), new_id(1, [2; 10]));
        for (id, handle) in [(&alice, "alice"), (&bob, "bob")] {
            c.execute(
                "INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'system', ?2, 0)",
                params![id, handle],
            )
            .unwrap();
            ingest::grant(&c, id, &format!("account:{id}")).unwrap();
        }
        World { c, alice, bob, n: Cell::new(10) }
    }
    fn id(&self) -> String {
        self.n.set(self.n.get() + 1);
        new_id(NOW as u64, [self.n.get(); 10])
    }
    fn at(&self) -> i64 {
        NOW + self.n.get() as i64 * 1000
    }
    /// Push as `who` into `scope`. Returns the refusal, if any.
    fn push(&self, who: &str, scope: &str, kind: &str, entity: &str, payload: Value) -> Option<String> {
        self.n.set(self.n.get() + 1);
        let at = self.at();
        let o = Op {
            id: new_id(at as u64, [self.n.get().wrapping_add(90); 10]),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: Some(entity.into()),
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
        };
        let s = ingest::Session {
            account_id: who.into(),
            device_id: "dev".into(),
            sample: ClockSample { server_time: at, mono: None, boot_id: None, offset_ms: 0 },
        };
        let (ack, _) = ingest::accept(&self.c, &s, o, at, false).unwrap();
        ack.error.map(|e| format!("{}: {}", e.code, e.message))
    }
    fn server(&self, who: &str, scope: &str, kind: &str, entity: &str, payload: Value) {
        self.n.set(self.n.get() + 1);
        ingest::server_op(&self.c, who, kind, scope, Some(entity), payload, self.at()).unwrap();
    }
    fn member(&self, owner: &str, name: &str) -> String {
        let id = self.id();
        assert_eq!(self.push(owner, &format!("account:{owner}"), "member.create", &id, json!({"name": name})), None);
        id
    }
    /// A system's own space with its Handover and Notes, written as the server writes them.
    fn home(&self, owner: &str) -> Home {
        let space = self.id();
        let scope = format!("space:{space}");
        ingest::grant(&self.c, owner, &scope).unwrap();
        self.server(owner, &scope, "space.create", &space, json!({"kind": "internal", "name": "Home"}));
        self.server(owner, &scope, "space.join", &space, json!({"account_id": owner}));
        let (handover, notes) = (self.id(), self.id());
        for (id, special, name) in [(&handover, "handover", "Handover"), (&notes, "notes", "Notes to ourselves")] {
            self.server(
                owner,
                &scope,
                "channel.create",
                id,
                json!({"space_id": space, "kind": "text", "name": name, "special": special}),
            );
        }
        Home { space, scope, handover, notes }
    }
    fn send(&self, who: &str, h: &Home, channel: &str, author: &str, handover: Value) -> (String, Option<String>) {
        let id = self.id();
        let mut p = json!({"channel_id": channel, "authors": [author], "text": "Keys are by the door", "entities": []});
        if !handover.is_null() {
            p["handover"] = handover;
        }
        let refused = self.push(who, &h.scope, "message.send", &id, p);
        (id, refused)
    }
    fn count(&self, sql: &str, args: impl rusqlite::Params) -> i64 {
        self.c.query_row(sql, args, |r| r.get(0)).unwrap()
    }
}

#[test]
fn special_channels_only_in_an_internal_space_and_one_live_of_each() {
    let w = World::new();
    let h = w.home(&w.alice);
    let roles: Vec<(String, String)> =
        w.c.prepare("SELECT id, special FROM channel WHERE space_id = ?1 AND special IS NOT NULL ORDER BY special")
            .unwrap()
            .query_map([&h.space], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
    assert_eq!(roles, vec![(h.handover.clone(), "handover".into()), (h.notes.clone(), "notes".into())]);
    // a second live one, or one in a shared space, is refused
    let again = w.push(
        &w.alice,
        &h.scope,
        "channel.create",
        &w.id(),
        json!({"space_id": h.space, "kind": "text", "name": "Handover 2", "special": "handover"}),
    );
    assert!(again.is_some_and(|e| e.contains("already has")), "a second live Handover");
    let shared = w.id();
    let shared_scope = format!("space:{shared}");
    ingest::grant(&w.c, &w.alice, &shared_scope).unwrap();
    w.server(&w.alice, &shared_scope, "space.create", &shared, json!({"kind": "shared", "name": "Garden"}));
    let out = w.push(
        &w.alice,
        &shared_scope,
        "channel.create",
        &w.id(),
        json!({"space_id": shared, "kind": "text", "special": "notes"}),
    );
    assert!(out.is_some(), "Notes in a shared space");
    // the role is fixed: no channel.set, no re-create giving or taking it
    assert!(w.push(&w.alice, &h.scope, "channel.set", &h.notes, json!({"special": null})).is_some());
    let plain = w.id();
    let make = json!({"space_id": h.space, "kind": "text", "name": "Handover"});
    assert_eq!(w.push(&w.alice, &h.scope, "channel.create", &plain, make), None, "an ordinary channel named Handover");
    let convert = json!({"space_id": h.space, "kind": "text", "special": "handover"});
    assert!(w.push(&w.alice, &h.scope, "channel.create", &plain, convert).is_some(), "converted");
    let strip = json!({"space_id": h.space, "kind": "text", "special": null});
    assert!(w.push(&w.alice, &h.scope, "channel.create", &h.notes, strip).is_some(), "role taken away");
    assert_eq!(w.push(&w.alice, &h.scope, "channel.set", &h.notes, json!({"name": "Our notes"})), None, "renaming");
    // deleted: a new one may be made, and then the old one can't come back beside it
    assert_eq!(w.push(&w.alice, &h.scope, "channel.delete", &h.handover, json!({})), None);
    let fresh = w.id();
    let make = json!({"space_id": h.space, "kind": "text", "name": "Handover", "special": "handover"});
    assert_eq!(w.push(&w.alice, &h.scope, "channel.create", &fresh, make), None);
    let back = w.push(&w.alice, &h.scope, "channel.restore", &h.handover, json!({}));
    assert!(back.is_some_and(|e| e.contains("another handover")), "two live Handovers");
    assert_eq!(w.push(&w.alice, &h.scope, "channel.delete", &fresh, json!({})), None);
    assert_eq!(w.push(&w.alice, &h.scope, "channel.restore", &h.handover, json!({})), None);
}

#[test]
fn a_handover_goes_only_in_the_handover_channel_and_is_fixed_when_sent() {
    let w = World::new();
    let h = w.home(&w.alice);
    let fox = w.member(&w.alice, "Foxglove");
    let general = w.id();
    let make = json!({"space_id": h.space, "kind": "text", "name": "general"});
    assert_eq!(w.push(&w.alice, &h.scope, "channel.create", &general, make), None);
    let def = json!({"deliver": "next_switch", "checklist": [{"id": "a", "text": "Keys"}]});
    let (sent, refused) = w.send(&w.alice, &h, &h.handover, &fox, def.clone());
    assert_eq!(refused, None);
    let stored: String = w.c.query_row("SELECT handover FROM message WHERE id = ?1", [&sent], |r| r.get(0)).unwrap();
    assert_eq!(serde_json::from_str::<Value>(&stored).unwrap(), def);
    assert!(w.send(&w.alice, &h, &h.notes, &fox, def.clone()).1.is_some(), "a handover in Notes");
    assert!(w.send(&w.alice, &h, &general, &fox, def.clone()).1.is_some(), "a handover in general");
    assert!(w.send(&w.alice, &h, &h.handover, &fox, json!({"deliver": "someday"})).1.is_some());
    // ordinary messages are fine in both
    assert_eq!(w.send(&w.alice, &h, &h.handover, &fox, Value::Null).1, None);
    assert_eq!(w.send(&w.alice, &h, &h.notes, &fox, Value::Null).1, None);
    let edit = json!({"message_id": sent, "text": "changed", "handover": {"deliver": "now"}});
    assert!(w.push(&w.alice, &h.scope, "message.edit", &sent, edit).is_some(), "an edit changing it");
}

#[test]
fn acks_and_ticks_are_the_systems_own_on_a_real_handover() {
    let w = World::new();
    let h = w.home(&w.alice);
    let (amy, fox) = (w.member(&w.alice, "Amarynthe"), w.member(&w.alice, "Foxglove"));
    let gone = w.member(&w.alice, "Gone");
    assert_eq!(w.push(&w.alice, &format!("account:{}", w.alice), "member.delete", &gone, json!({})), None);
    let def = json!({"deliver": "now", "checklist": [{"id": "a", "text": "Keys"}, {"id": "b", "text": "Meds"}]});
    let (ho, _) = w.send(&w.alice, &h, &h.handover, &amy, def);
    let (plain, _) = w.send(&w.alice, &h, &h.handover, &amy, Value::Null);
    let ack = |m: &str, member: &str| {
        w.push(&w.alice, &h.scope, "handover.ack", m, json!({"message_id": m, "member_id": member}))
    };
    let tick = |item: &str, done: bool, member: &str| {
        w.push(
            &w.alice,
            &h.scope,
            "checklist.set",
            &ho,
            json!({"message_id": ho, "item_id": item, "done": done, "member_id": member}),
        )
    };
    assert_eq!(ack(&ho, &fox), None);
    assert!(ack(&plain, &fox).is_some(), "an ack on a message that isn't a handover");
    assert!(ack(&w.id(), &fox).is_some(), "an ack on an unknown message");
    assert!(ack(&ho, &gone).is_some(), "an ack as a deleted member");
    assert!(ack(&ho, &w.id()).is_some(), "an ack as an unknown member");
    assert_eq!(tick("a", true, &fox), None);
    assert!(tick("zz", true, &fox).is_some(), "a tick on an item the checklist doesn't have");
    assert_eq!(tick("a", false, &amy), None);
    let state: (bool, String) =
        w.c.query_row(
            "SELECT done, member_id FROM checklist_item_state WHERE message_id = ?1 AND item_id = 'a'",
            [&ho],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .unwrap();
    assert_eq!(state, (false, amy.clone()), "the later tick wins");
    assert_eq!(w.count("SELECT count(*) FROM handover_ack WHERE message_id = ?1", [&ho]), 1);
    // the rows come back the same from the log
    let before: Vec<String> = dump(&w.c);
    let mut c = w.c;
    project::rebuild(&mut c).unwrap();
    assert_eq!(dump(&c), before);
}

fn dump(c: &Connection) -> Vec<String> {
    let mut out = Vec::new();
    for sql in [
        "SELECT message_id || member_id || acked_at FROM handover_ack ORDER BY 1",
        "SELECT message_id || item_id || done || member_id FROM checklist_item_state ORDER BY 1",
        "SELECT id || coalesce(special, '-') FROM channel ORDER BY 1",
        "SELECT id || coalesce(handover, '-') FROM message ORDER BY 1",
    ] {
        out.extend(c.prepare(sql).unwrap().query_map([], |r| r.get::<_, String>(0)).unwrap().map(Result::unwrap));
    }
    out
}

#[test]
fn special_channels_are_never_shared_out() {
    let w = World::new();
    let h = w.home(&w.alice);
    let general = w.id();
    let make = json!({"space_id": h.space, "kind": "text", "name": "general"});
    assert_eq!(w.push(&w.alice, &h.scope, "channel.create", &general, make), None);
    let grant = |channel: &str| {
        w.push(
            &w.alice,
            &h.scope,
            "channel.set_permission",
            channel,
            json!({"target_type": "account", "target_id": w.bob, "allow": ["view", "send"], "deny": []}),
        )
    };
    assert_eq!(grant(&general), None, "an ordinary channel can be shared with a partner");
    assert!(grant(&h.handover).is_some(), "a guest grant on Handover");
    assert!(grant(&h.notes).is_some(), "a guest grant on Notes");
    let everyone = json!({"target_type": "role", "target_id": "everyone", "allow": ["view"], "deny": []});
    assert!(w.push(&w.alice, &h.scope, "channel.set_permission", &h.notes, everyone).is_some());
    // nobody else joins a system's own space
    let join = w.push(&w.alice, &h.scope, "space.join", &h.space, json!({"account_id": w.bob}));
    assert!(join.is_some(), "Bob joined Alice's own space");
    // and even an override or membership that got in some other way gives nothing
    w.c.execute(
        "INSERT INTO channel_permission (channel_id, target_type, target_id, allow, deny, hlc)
         VALUES (?1, 'account', ?2, '[\"view\"]', '[]', '0')",
        params![h.handover, w.bob],
    )
    .unwrap();
    w.c.execute(
        "INSERT INTO space_member (space_id, account_id, role, joined_hlc) VALUES (?1, ?2, 'admin', '1')",
        params![h.space, w.bob],
    )
    .unwrap();
    assert!(!perms::can(&w.c, &w.bob, &h.handover, "view").unwrap(), "Bob views Handover");
    assert!(!perms::can(&w.c, &w.bob, &h.notes, "view").unwrap(), "Bob views Notes");
    assert!(perms::can(&w.c, &w.bob, &general, "view").unwrap(), "setup: the rest of the space");
    assert!(perms::can(&w.c, &w.alice, &h.handover, "send").unwrap());
}

fn enrol(c: &mut Connection, kind: auth::InviteKind, seed: u8, handle: &str) -> String {
    use base64::Engine as _;
    use p256::pkcs8::EncodePublicKey;
    let code = auth::create_invite(c, kind, None, "t", auth::INVITE_TTL_MS, 1, NOW).unwrap();
    let sk = p256::ecdsa::SigningKey::from_slice(&[seed; 32]).unwrap();
    let pk =
        base64::engine::general_purpose::STANDARD.encode(sk.verifying_key().to_public_key_der().unwrap().as_bytes());
    let device = auth::DeviceIn { name: "p".into(), platform: "android".into(), public_key: pk };
    let account = auth::AccountIn { display_name: None, handle: Some(handle.into()), kind: None };
    auth::redeem(c, &code, &device, Some(&account), NOW, 1000).unwrap().account_id
}

/// (space kind, channel name, role) of every channel in spaces the account owns, deleted included.
fn specials(c: &Connection, account: &str) -> Vec<(String, String, Option<String>)> {
    c.prepare(
        "SELECT s.kind, ch.name, ch.special FROM channel ch JOIN space s ON s.id = ch.space_id
         WHERE s.owner_account_id = ?1 ORDER BY ch.special, ch.name",
    )
    .unwrap()
    .query_map([account], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
    .unwrap()
    .collect::<Result<_, _>>()
    .unwrap()
}

#[test]
fn enrolment_makes_a_systems_pair_and_a_persons_notes_in_a_space_of_its_own() {
    let mut c = db::open_memory().unwrap();
    db::migrate(&mut c).unwrap();
    let sys = enrol(&mut c, auth::InviteKind::System, 5, "stars");
    let s = |k: &str, n: &str, r: Option<&str>| (k.to_string(), n.to_string(), r.map(str::to_string));
    assert_eq!(
        specials(&c, &sys),
        vec![
            s("internal", "general", None),
            s("internal", "Handover", Some("handover")),
            s("internal", "Notes to ourselves", Some("notes"))
        ]
    );
    let person = enrol(&mut c, auth::InviteKind::Person, 6, "kit");
    assert_eq!(specials(&c, &person), vec![s("internal", "Notes to self", Some("notes"))]);
    // the person is a present member of that space, and nobody else is
    let members: Vec<String> = c
        .prepare("SELECT sm.account_id FROM space_member sm JOIN space s ON s.id = sm.space_id WHERE s.owner_account_id = ?1 AND sm.is_present")
        .unwrap()
        .query_map([&person], |r| r.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert_eq!(members, vec![person.clone()]);
    // and doing it again changes nothing
    assert_eq!(handover::backfill(&c, NOW + 1).unwrap(), 0);
}

#[test]
fn the_start_up_backfill_is_idempotent_and_leaves_named_channels_and_deleted_ones_alone() {
    let w = World::new();
    // an older system: its own space, an ordinary channel a person named "Handover", no specials
    let space = w.id();
    let scope = format!("space:{space}");
    ingest::grant(&w.c, &w.alice, &scope).unwrap();
    w.server(&w.alice, &scope, "space.create", &space, json!({"kind": "internal", "name": "Home"}));
    w.server(&w.alice, &scope, "space.join", &space, json!({"account_id": w.alice}));
    let named = w.id();
    w.server(
        &w.alice,
        &scope,
        "channel.create",
        &named,
        json!({"space_id": space, "kind": "text", "name": "Handover"}),
    );
    // an older person with no space at all, and a system that has none (Bob) gets none
    let kit = new_id(1, [9; 10]);
    w.c.execute("INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'person', 'kit', 0)", [&kit]).unwrap();
    ingest::grant(&w.c, &kit, &format!("account:{kit}")).unwrap();
    assert_eq!(handover::backfill(&w.c, NOW).unwrap(), 3);
    assert_eq!(handover::backfill(&w.c, NOW).unwrap(), 0, "a second start made more");
    let mine = specials(&w.c, &w.alice);
    assert_eq!(mine.iter().filter(|r| r.2.is_some()).count(), 2);
    let old: (String, Option<String>) =
        w.c.query_row("SELECT name, special FROM channel WHERE id = ?1", [&named], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap();
    assert_eq!(old, ("Handover".into(), None), "the ordinary channel was converted or renamed");
    assert_eq!(specials(&w.c, &kit).len(), 1);
    assert!(specials(&w.c, &w.bob).is_empty());
    // a deleted special is restored from Trash, never made again; a rebuild changes nothing
    let notes: String =
        w.c.query_row("SELECT id FROM channel WHERE space_id = ?1 AND special = 'notes'", [&space], |r| r.get(0))
            .unwrap();
    assert_eq!(w.push(&w.alice, &scope, "channel.delete", &notes, json!({})), None);
    let mut c = w.c;
    project::rebuild(&mut c).unwrap();
    assert_eq!(handover::backfill(&c, NOW).unwrap(), 0);
    let n: i64 = c.query_row("SELECT count(*) FROM channel WHERE special IS NOT NULL", [], |r| r.get(0)).unwrap();
    assert_eq!(n, 3);
}

fn switch(w: &World, who: &[&str]) {
    let entries: Vec<Value> = who
        .iter()
        .enumerate()
        .map(|(i, m)| json!({"subject_type": "member", "subject_id": m, "level": "front", "is_primary": i == 0}))
        .collect();
    let acct = format!("account:{}", w.alice);
    assert_eq!(w.push(&w.alice, &acct, "front.switch", &w.id(), json!({"entries": entries})), None);
}

/// (due_at, from_device, delivered) of each handover notification.
fn rung(c: &Connection) -> Vec<(i64, Option<String>, bool)> {
    c.prepare(
        "SELECT due_at, json_extract(payload, '$.from_device'), delivered_at IS NOT NULL FROM notification
         WHERE kind = 'handover' ORDER BY created_at",
    )
    .unwrap()
    .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
    .unwrap()
    .collect::<Result<_, _>>()
    .unwrap()
}

#[test]
fn a_next_switch_handover_rings_once_when_the_front_changes_and_never_from_a_rebuild() {
    use chorus_server::activity::{self, HANDOVER_WAITING};
    let w = World::new();
    let h = w.home(&w.alice);
    let (amy, fox) = (w.member(&w.alice, "Amarynthe"), w.member(&w.alice, "Foxglove"));
    switch(&w, &[&amy]);
    let def = json!({"deliver": "next_switch", "checklist": [{"id": "a", "text": "Keys"}]});
    let (_, refused) = w.send(&w.alice, &h, &h.handover, &amy, def.clone());
    assert_eq!(refused, None);
    assert_eq!(rung(&w.c), vec![(HANDOVER_WAITING, None, false)], "waiting, not rung at sending");
    assert!(activity::process_due(&w.c, NOW * 2).unwrap().is_empty() && !rung(&w.c)[0].2);
    // the same front again changes nothing; Foxglove fronting delivers it, skipping that device
    switch(&w, &[&amy]);
    assert_eq!(rung(&w.c)[0].0, HANDOVER_WAITING);
    switch(&w, &[&fox]);
    let at = w.at();
    assert_eq!(rung(&w.c), vec![(at, Some("dev".into()), false)]);
    let payload: String =
        w.c.query_row("SELECT payload FROM notification WHERE kind = 'handover'", [], |r| r.get(0)).unwrap();
    let p: Value = serde_json::from_str(&payload).unwrap();
    assert_eq!(p["title"], "Handover from Amarynthe");
    assert_eq!(p["text"], "Keys are by the door");
    activity::process_due(&w.c, at).unwrap();
    assert!(rung(&w.c)[0].2, "delivered");
    // exactly once: more switches, and a rebuild, ring nothing more
    switch(&w, &[&amy]);
    switch(&w, &[&fox]);
    let mut c = w.c;
    project::rebuild(&mut c).unwrap();
    assert_eq!(rung(&c).len(), 1);
}

#[test]
fn handover_rings_follow_the_switch_and_channel_levels() {
    let w = World::new();
    let h = w.home(&w.alice);
    let (amy, fox) = (w.member(&w.alice, "Amarynthe"), w.member(&w.alice, "Foxglove"));
    let count = |kind: &str| -> i64 {
        w.c.query_row("SELECT count(*) FROM notification WHERE kind = ?1", [kind], |r| r.get(0)).unwrap()
    };
    // `now` rings like any message in Handover (its level is `all`); Notes is `none`
    assert_eq!(w.send(&w.alice, &h, &h.handover, &amy, json!({"deliver": "now"})).1, None);
    assert_eq!(count("message"), 1);
    assert_eq!(w.send(&w.alice, &h, &h.notes, &amy, Value::Null).1, None);
    assert_eq!(count("message"), 1, "a note rang");
    // switched off: no next-switch ring at all
    let acct = format!("account:{}", w.alice);
    let off = json!({"key": "notify_chat", "value": {"handover": false}});
    assert_eq!(w.push(&w.alice, &acct, "pref.set", &w.id(), off), None);
    switch(&w, &[&amy]);
    assert_eq!(w.send(&w.alice, &h, &h.handover, &amy, json!({"deliver": "next_switch"})).1, None);
    switch(&w, &[&fox]);
    assert_eq!(count("handover"), 0);
    // a deleted handover never rings
    let on = json!({"key": "notify_chat", "value": {}});
    assert_eq!(w.push(&w.alice, &acct, "pref.set", &w.id(), on), None);
    let (gone, _) = w.send(&w.alice, &h, &h.handover, &amy, json!({"deliver": "next_switch"}));
    assert_eq!(w.push(&w.alice, &h.scope, "message.delete", &gone, json!({"message_id": gone})), None);
    switch(&w, &[&amy]);
    let cancelled: i64 = w
        .c
        .query_row("SELECT count(*) FROM notification WHERE kind = 'handover' AND cancelled_at IS NOT NULL", [], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!((count("handover"), cancelled), (1, 1));
}
