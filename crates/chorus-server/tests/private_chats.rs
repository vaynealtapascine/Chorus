//! Private chats for one member (D-098): a `member_dm` channel in a DM space kept to some of an
//! account's members. That account speaks there only as them, the other side can't open the chat
//! to the rest of their system, the other side can name them before they write, and the system
//! hears about it under the member's DM rule.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, ingest, notifier, spaces};
use rusqlite::Connection;
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;

struct W {
    c: Connection,
    /// Lanterns, a system (Kai and June); Robin, a person; Outsider, not in the DM.
    lanterns: String,
    robin: String,
    outsider: String,
    dm: String,
    n: u8,
    last_op: String,
}

impl W {
    fn new() -> W {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        let lanterns = new_id(1, [1; 10]);
        let robin = new_id(1, [2; 10]);
        let outsider = new_id(1, [3; 10]);
        let dm = new_id(1, [4; 10]);
        for (id, kind, h) in
            [(&lanterns, "system", "lanterns"), (&robin, "person", "robin"), (&outsider, "system", "out")]
        {
            c.execute(
                "INSERT INTO account(id, kind, handle, created_at) VALUES (?1, ?2, ?3, 0)",
                [id.as_str(), kind, h],
            )
            .unwrap();
            ingest::grant(&c, id, &format!("account:{id}")).unwrap();
        }
        ingest::grant(&c, &lanterns, &format!("space:{dm}")).unwrap();
        let mut w = W {
            c,
            lanterns: lanterns.clone(),
            robin: robin.clone(),
            outsider,
            dm: dm.clone(),
            n: 0,
            last_op: String::new(),
        };
        let scope = w.scope();
        w.ok(&lanterns, "space.create", &scope, &dm, json!({"kind": "dm"}));
        for who in [&lanterns, &robin] {
            w.ok(&lanterns, "space.join", &scope, &dm, json!({"account_id": who}));
        }
        w
    }

    fn scope(&self) -> String {
        format!("space:{}", self.dm)
    }

    fn push(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) -> Option<String> {
        self.n += 1;
        let at = NOW + i64::from(self.n) * 1000;
        let o = Op {
            id: new_id(at as u64, [self.n; 10]),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: Some(entity.into()),
            hlc: Hlc::new(at as u64, 0, u32::from(self.n)),
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
            device_id: "d".into(),
            sample: ClockSample { server_time: at, mono: None, boot_id: None, offset_ms: 0 },
        };
        self.last_op = o.id.clone();
        let (r, _) = ingest::accept(&self.c, &s, o, at, false).unwrap();
        r.error.map(|e| format!("{e:?}"))
    }

    fn ok(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) {
        let e = self.push(who, kind, scope, entity, payload);
        assert!(e.is_none(), "{kind}: {e:?}");
    }

    fn refused(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) -> String {
        self.push(who, kind, scope, entity, payload).unwrap_or_else(|| panic!("{kind} was accepted"))
    }

    fn member(&mut self, account: &str, name: &str) -> String {
        let id = new_id(NOW as u64, [100 + self.n; 10]);
        self.ok(account, "member.create", &format!("account:{account}"), &id, json!({"name": name}));
        id
    }

    fn id(&mut self) -> String {
        self.n += 1;
        new_id(NOW as u64 + 7, [self.n; 10])
    }

    fn inbox(&self, account: &str) -> Vec<Value> {
        notifier::process_due(&self.c, NOW + 1_000_000).unwrap();
        notifier::list(&self.c, account, 50).unwrap()["items"].as_array().unwrap().clone()
    }

    fn payload_of_last(&self) -> Option<Value> {
        let p: Option<String> = self
            .c
            .query_row("SELECT payload FROM notification WHERE source_op_id = ?1", [&self.last_op], |r| r.get(0))
            .ok();
        p.map(|p| serde_json::from_str(&p).unwrap())
    }
}

fn say(channel: &str, authors: &[&str], text: &str) -> Value {
    json!({"channel_id": channel, "authors": authors, "text": text, "entities": []})
}

#[test]
fn only_the_member_speaks_and_only_their_account_changes_whose_it_is() {
    let mut w = W::new();
    let (l, r, scope, dm) = (w.lanterns.clone(), w.robin.clone(), w.scope(), w.dm.clone());
    let kai = w.member(&l, "Kai");
    let june = w.member(&l, "June");
    let robin_self = w.member(&r, "Robin");
    let chat = w.id();
    w.ok(
        &l,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "member_dm", "name": "Kai", "member_ids": [kai]}),
    );

    let m1 = w.id();
    w.ok(&l, "message.send", &scope, &m1, say(&chat, &[&kai], "hi, it's Kai"));
    let m2 = w.id();
    assert!(w.refused(&l, "message.send", &scope, &m2, say(&chat, &[&june], "June here")).contains("private chat"));
    let m3 = w.id();
    let mut joint = say(&chat, &[&kai], "us\nJune");
    joint["segments"] =
        json!([{"offset": 0, "length": 2, "authors": [kai]}, {"offset": 3, "length": 4, "authors": [june]}]);
    assert!(w.refused(&l, "message.send", &scope, &m3, joint).contains("private chat"));
    // the other side isn't limited: Robin has no member listed
    let m4 = w.id();
    w.ok(&r, "message.send", &scope, &m4, say(&chat, &[&robin_self], "hey Kai"));
    // reactions speak too
    let react = |m: &str, who: &str| json!({"target_type": "message", "target_id": m, "emoji": "💜", "member_id": who});
    w.ok(&l, "reaction.add", &scope, &m4, react(&m4, &kai));
    assert!(w.refused(&l, "reaction.add", &scope, &m4, react(&m4, &june)).contains("private chat"));
    w.ok(&r, "reaction.add", &scope, &m1, react(&m1, &robin_self));
    // a forward into it, and a thread under one of its messages, follow the same rule
    let f = w.id();
    let mut fwd = say(&chat, &[&june], "");
    fwd["forward_of_id"] = json!(m4);
    fwd["forward_snapshot"] =
        json!([{"message_id": m4, "authors": [robin_self], "text": "hey Kai", "entities": [], "occurred_at": NOW}]);
    assert!(w.refused(&l, "message.forward", &scope, &f, fwd).contains("private chat"));
    w.ok(
        &l,
        "channel.create",
        &scope,
        &m1,
        json!({"space_id": dm, "kind": "thread", "name": "Thread", "parent_message_id": m1}),
    );
    let t1 = w.id();
    assert!(w.refused(&l, "message.send", &scope, &t1, say(&m1, &[&june], "in the thread")).contains("private chat"));
    let t2 = w.id();
    w.ok(&l, "message.send", &scope, &t2, say(&m1, &[&kai], "in the thread"));

    // Robin can't open Kai's chat to the rest of Lanterns, but may keep it to themself too
    assert!(w.refused(&r, "channel.set", &scope, &chat, json!({"member_ids": []})).contains("only an account"));
    w.ok(&r, "channel.set", &scope, &chat, json!({"member_ids": [kai, robin_self]}));
    // Lanterns may bring June in, and must keep Robin's listing as it is
    w.refused(&l, "channel.set", &scope, &chat, json!({"member_ids": [kai, june]}));
    w.ok(&l, "channel.set", &scope, &chat, json!({"member_ids": [kai, june, robin_self]}));
    let m5 = w.id();
    w.ok(&l, "message.send", &scope, &m5, say(&chat, &[&june], "June's in now"));
    // now Robin speaks there only as the listed self member
    let robin_alt = w.member(&r, "Robin's alias");
    let m6 = w.id();
    w.refused(&r, "message.send", &scope, &m6, say(&chat, &[&robin_alt], "as someone else"));

    // nobody outside the DM can be named
    let stranger = {
        let o = w.outsider.clone();
        w.member(&o, "Stranger")
    };
    let other = w.id();
    assert!(
        w.refused(
            &l,
            "channel.create",
            &scope,
            &other,
            json!({"space_id": dm, "kind": "member_dm", "name": "x", "member_ids": [kai, stranger]})
        )
        .contains("accounts in it")
    );
    // the DM's own channel stays open to everyone
    let general = w.id();
    w.ok(&l, "channel.create", &scope, &general, json!({"space_id": dm, "kind": "text", "name": "dm"}));
    let m7 = w.id();
    w.ok(&l, "message.send", &scope, &m7, say(&general, &[&june], "anyone may speak here"));
}

#[test]
fn the_other_side_can_name_the_member_before_they_write() {
    let mut w = W::new();
    let (l, r, scope, dm) = (w.lanterns.clone(), w.robin.clone(), w.scope(), w.dm.clone());
    let kai = w.member(&l, "Kai");
    let _june = w.member(&l, "June");
    let robin_self = w.member(&r, "Robin");
    let names = |w: &W, who: &str| -> Vec<String> {
        let mut v: Vec<String> = spaces::authors(&w.c, who, &dm).unwrap()["members"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| m["name"].as_str().unwrap().to_string())
            .collect();
        v.sort();
        v
    };
    assert!(names(&w, &r).is_empty(), "nobody has written or been listed yet");
    // Robin starts a private chat with Kai: it's titled for Robin at once, and June stays unknown
    let chat = w.id();
    w.ok(
        &r,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "member_dm", "name": "Kai", "member_ids": [kai]}),
    );
    assert_eq!(names(&w, &r), ["Kai"]);
    assert!(names(&w, &l).is_empty(), "Robin's self member hasn't written or been listed");
    let m = w.id();
    w.ok(&r, "message.send", &scope, &m, say(&chat, &[&robin_self], "hi Kai"));
    assert_eq!(names(&w, &l), ["Robin"]);
}

#[test]
fn the_system_hears_under_the_members_dm_rule_and_replies_as_them() {
    let mut w = W::new();
    let (l, r, scope, dm) = (w.lanterns.clone(), w.robin.clone(), w.scope(), w.dm.clone());
    let acct = format!("account:{l}");
    let kai = w.member(&l, "Kai");
    let june = w.member(&l, "June");
    let robin_self = w.member(&r, "Robin");
    let chat = w.id();
    w.ok(
        &l,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "member_dm", "name": "Kai", "member_ids": [kai]}),
    );
    let general = w.id();
    w.ok(&l, "channel.create", &scope, &general, json!({"space_id": dm, "kind": "text", "name": "dm"}));
    let sw = w.id();
    w.ok(
        &l,
        "front.switch",
        &acct,
        &sw,
        json!({"entries": [{"subject_type": "member", "subject_id": june, "level": "front"}]}),
    );

    // June is fronting, not Kai: the private chat stays quiet (DM rule default "fronting")
    let m1 = w.id();
    w.ok(&r, "message.send", &scope, &m1, say(&chat, &[&robin_self], "for Kai only"));
    assert!(w.payload_of_last().is_none());
    // the shared DM still pings as a DM
    let m2 = w.id();
    w.ok(&r, "message.send", &scope, &m2, say(&general, &[&robin_self], "hello all"));
    assert_eq!(w.inbox(&l)[0]["kind"], "dm");
    // Kai comes to the front: it pings, says whose it is, and Reply speaks as Kai
    let sw2 = w.id();
    w.ok(
        &l,
        "front.switch",
        &acct,
        &sw2,
        json!({"entries": [{"subject_type": "member", "subject_id": kai, "level": "front"}]}),
    );
    let m3 = w.id();
    w.ok(&r, "message.send", &scope, &m3, say(&chat, &[&robin_self], "there you are"));
    let p = w.payload_of_last().expect("Kai is fronting");
    assert_eq!(p["kind"], "member_dm");
    assert_eq!(p["reply_as"], json!(kai));
    assert!(p["title"].as_str().unwrap().ends_with("· for Kai"), "{}", p["title"]);
    // muting the chat silences it; Kai choosing "never" silences it too
    let pref = w.id();
    w.ok(
        &l,
        "pref.set",
        &acct,
        &pref,
        json!({"device": "", "key": format!("notify_member:{kai}"), "value": {"dms": "never"}}),
    );
    let m4 = w.id();
    w.ok(&r, "message.send", &scope, &m4, say(&chat, &[&robin_self], "still there?"));
    assert!(w.payload_of_last().is_none());
    // Robin isn't limited there and hears as in any DM
    let m5 = w.id();
    w.ok(&l, "message.send", &scope, &m5, say(&chat, &[&kai], "yes"));
    assert_eq!(w.payload_of_last().unwrap()["kind"], "dm");
}

#[test]
fn recreating_a_channel_cannot_remove_peer_privacy_or_reroute_a_thread() {
    let mut w = W::new();
    let (l, r, scope, dm) = (w.lanterns.clone(), w.robin.clone(), w.scope(), w.dm.clone());
    let kai = w.member(&l, "Kai");
    let june = w.member(&l, "June");
    let robin = w.member(&r, "Robin");
    let chat = w.id();
    w.ok(
        &l,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "member_dm", "name": "Kai", "member_ids": [kai]}),
    );
    // A new op id must not turn create's LWW upsert into a way to clear the peer's entries.
    assert!(
        w.refused(
            &r,
            "channel.create",
            &scope,
            &chat,
            json!({"space_id": dm, "kind": "member_dm", "name": "open", "member_ids": []})
        )
        .contains("only an account")
    );
    let m = w.id();
    w.refused(&l, "message.send", &scope, &m, say(&chat, &[&june], "still private"));
    // Repeated creates that omit the list leave it alone, and each account may change its own entries.
    w.ok(&r, "channel.create", &scope, &chat, json!({"space_id": dm, "kind": "member_dm", "name": "renamed"}));
    w.ok(
        &r,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "member_dm", "name": "both", "member_ids": [kai, robin]}),
    );
    let m = w.id();
    w.refused(&l, "message.send", &scope, &m, say(&chat, &[&june], "still private after rename"));
    let open = w.id();
    w.ok(&r, "channel.create", &scope, &open, json!({"space_id": dm, "kind": "text", "name": "open"}));
    let parent = w.id();
    w.ok(&r, "message.send", &scope, &parent, say(&open, &[&robin], "public parent"));
    // Omit member_ids but change kind: kept_to would now follow the unrestricted parent.
    w.refused(
        &r,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "thread", "parent_message_id": parent, "name": "open"}),
    );
    let private_parent = w.id();
    w.ok(&l, "message.send", &scope, &private_parent, say(&chat, &[&kai], "private parent"));
    let thread = w.id();
    w.ok(
        &r,
        "channel.create",
        &scope,
        &thread,
        json!({"space_id": dm, "kind": "thread", "parent_message_id": private_parent, "name": "Thread"}),
    );
    w.refused(
        &r,
        "channel.create",
        &scope,
        &thread,
        json!({"space_id": dm, "kind": "thread", "parent_message_id": parent, "name": "Rerouted"}),
    );
    w.refused(&r, "channel.create", &scope, &thread, json!({"space_id": dm, "kind": "text", "name": "Detached"}));
    let m = w.id();
    w.refused(&l, "message.send", &scope, &m, say(&thread, &[&june], "thread still private"));
    let m = w.id();
    w.ok(&l, "message.send", &scope, &m, say(&thread, &[&kai], "Kai is allowed"));
    // Structural fields are already create-only on set.
    for payload in [json!({"kind": "thread"}), json!({"parent_message_id": parent}), json!({"space_id": dm})] {
        w.refused(&r, "channel.set", &scope, &chat, payload);
    }
}

#[test]
fn early_channel_fields_and_matching_creates_still_converge_without_moving_scopes() {
    let mut w = W::new();
    let (l, r, scope, dm) = (w.lanterns.clone(), w.robin.clone(), w.scope(), w.dm.clone());
    let kai = w.member(&l, "Kai");
    let chat = w.id();
    // A set can wait in the log for the first create; the guard must not mistake it for a recreation.
    w.ok(&l, "channel.set", &scope, &chat, json!({"name": "early"}));
    w.ok(
        &r,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "member_dm", "name": "Kai", "member_ids": [kai]}),
    );
    w.ok(
        &r,
        "channel.create",
        &scope,
        &chat,
        json!({"space_id": dm, "kind": "member_dm", "name": "matching", "member_ids": [kai]}),
    );
    let other_space = w.id();
    let other_scope = format!("space:{other_space}");
    ingest::grant(&w.c, &l, &other_scope).unwrap();
    w.ok(&l, "space.create", &other_scope, &other_space, json!({"kind": "internal"}));
    // Even an internal space's owner cannot reuse an existing DM id to move its channel.
    w.refused(
        &l,
        "channel.create",
        &other_scope,
        &chat,
        json!({"space_id": other_space, "kind": "member_dm", "name": "moved"}),
    );
    w.refused(
        &l,
        "channel.create",
        &other_scope,
        &chat,
        json!({"kind": "member_dm", "name": "wrong scope without space_id"}),
    );
    let actual: String = w.c.query_row("SELECT space_id FROM channel WHERE id = ?1", [&chat], |r| r.get(0)).unwrap();
    assert_eq!(actual, dm);
}

/// A private chat with a member who is a subsystem (D-092) includes everyone inside them,
/// recursively; one with someone inside doesn't include the subsystem (owner, 2026-11-02).
#[test]
fn a_subsystem_s_chat_includes_who_is_inside_it() {
    let mut w = W::new();
    let (l, scope, dm) = (w.lanterns.clone(), w.scope(), w.dm.clone());
    let acct = format!("account:{l}");
    let garden = w.member(&l, "Garden");
    let moss = w.member(&l, "Moss");
    let fern = w.member(&l, "Fern");
    let june = w.member(&l, "June");
    // Garden is a subsystem with Moss inside; Moss is one too, with Fern inside
    for (who, name) in [(&garden, "Garden"), (&moss, "Moss")] {
        let group = w.id();
        w.ok(&l, "group.create", &acct, &group, json!({"name": name, "kind": "subsystem", "can_front": false}));
        let inside = if who == &garden { &moss } else { &fern };
        w.ok(&l, "group.add_member", &acct, &group, json!({"member_id": inside}));
        let pref = w.id();
        w.ok(
            &l,
            "pref.set",
            &acct,
            &pref,
            json!({"device": "", "key": format!("member_subsystem:{who}"), "value": group}),
        );
    }
    let gardens = w.id();
    w.ok(
        &l,
        "channel.create",
        &scope,
        &gardens,
        json!({"space_id": dm, "kind": "member_dm", "name": "Garden", "member_ids": [garden]}),
    );
    for who in [&garden, &moss, &fern] {
        let m = w.id();
        w.ok(&l, "message.send", &scope, &m, say(&gardens, &[who], "inside"));
    }
    let m = w.id();
    assert!(w.refused(&l, "message.send", &scope, &m, say(&gardens, &[&june], "outside")).contains("private chat"));
    let ferns = w.id();
    w.ok(
        &l,
        "channel.create",
        &scope,
        &ferns,
        json!({"space_id": dm, "kind": "member_dm", "name": "Fern", "member_ids": [fern]}),
    );
    let m = w.id();
    w.ok(&l, "message.send", &scope, &m, say(&ferns, &[&fern], "just me"));
    for who in [&garden, &moss] {
        let m = w.id();
        assert!(w.refused(&l, "message.send", &scope, &m, say(&ferns, &[who], "parent")).contains("private chat"));
    }
}
