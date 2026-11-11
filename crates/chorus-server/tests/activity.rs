//! Activity notifications across accounts (NOTIFICATIONS.md §7): a mention of your member or a
//! reply to your message in a shared space notifies you; your own messages never do; messages
//! with restricted visibility notify nobody.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{api_data, db, ingest, messages, notifier, posts, search, spaces};
use rusqlite::Connection;
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;
/// The shared space's #general (W::new makes it).
const GENERAL: &str = "0192f8c2-0000-7000-8000-0000000000e1";

struct W {
    c: Connection,
    a: String,
    b: String,
    space: String,
    n: u8,
    last_op: String,
    /// When the next op says it was written (default: now), for ops written offline earlier.
    written: Option<i64>,
}

impl W {
    fn new() -> W {
        W::with_space("shared")
    }

    /// A two-account space of `kind` (`shared`, `dm`) with a #general.
    fn with_space(kind: &str) -> W {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        let a = new_id(1, [1; 10]);
        let b = new_id(1, [2; 10]);
        let space = format!("space:{}", new_id(1, [3; 10]));
        for (id, h) in [(&a, "stars"), (&b, "alex")] {
            c.execute(
                "INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'system', ?2, 0)",
                [id, &h.to_string()],
            )
            .unwrap();
            ingest::grant(&c, id, &format!("account:{id}")).unwrap();
            ingest::grant(&c, id, &space).unwrap();
        }
        let mut w =
            W { c, a: a.clone(), b: b.clone(), space: space.clone(), n: 0, last_op: String::new(), written: None };
        // a real shared space with a #general, so channel permissions (perms.rs) have rows to read
        let id = space.strip_prefix("space:").unwrap().to_string();
        w.push(&a, "space.create", &space, &id, json!({"kind": kind, "name": "Club"}));
        for who in [&a, &b] {
            w.push(&a, "space.join", &space, &id, json!({"account_id": who}));
        }
        w.push(&a, "channel.create", &space, GENERAL, json!({"space_id": id, "kind": "text", "name": "general"}));
        w
    }

    fn push(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) {
        if let Some(e) = self.try_push(who, kind, scope, entity, payload) {
            panic!("{kind}: {e}");
        }
    }

    /// [`W::push`], answering the refusal instead of failing on one.
    fn try_push(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) -> Option<String> {
        self.n += 1;
        let at = NOW + i64::from(self.n) * 1000;
        let o = Op {
            id: new_id(at as u64, [self.n; 10]),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: Some(entity.into()),
            hlc: Hlc::new(at as u64, 0, u32::from(self.n)),
            device_at: self.written.take().unwrap_or(at),
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
        r.error.map(|e| e.message)
    }

    fn inbox(&self, account: &str) -> Vec<(String, String)> {
        notifier::process_due(&self.c, NOW + 1_000_000).unwrap();
        let l = notifier::list(&self.c, account, 50).unwrap();
        l["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|i| (i["kind"].as_str().unwrap().to_string(), i["text"].as_str().unwrap().to_string()))
            .collect()
    }
}

fn msg(text: &str, authors: &[&str], entities: Value) -> Value {
    json!({"channel_id": GENERAL, "authors": authors, "text": text, "entities": entities})
}

#[test]
fn mentions_and_replies_notify_the_other_account_only() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));

    // Kai mentions June: Alex's account hears about it; the Stars don't
    let m1 = new_id(NOW as u64, [50; 10]);
    let ent = json!([{"type": "mention", "offset": 3, "length": 5, "target_type": "member", "target_id": june}]);
    w.push(&a, "message.send", &space, &m1, msg("hi @June", &[&kai], ent));
    assert_eq!(w.inbox(&b), [("mention".to_string(), "hi @June".to_string())]);
    assert!(w.inbox(&a).is_empty());

    // June replies to Kai: now the Stars hear
    let m2 = new_id(NOW as u64, [51; 10]);
    let mut reply = msg("hello!", &[&june], json!([]));
    reply["reply_to"] = json!(m1);
    w.push(&b, "message.send", &space, &m2, reply);
    assert_eq!(w.inbox(&a), [("reply".to_string(), "hello!".to_string())]);
    // the projection keeps the link (the REST API and exports read reply_to_id)
    let linked = |w: &W| -> Option<String> {
        w.c.query_row("SELECT reply_to_id FROM message WHERE id = ?1", [&m2], |r| r.get(0)).unwrap()
    };
    assert_eq!(linked(&w).as_deref(), Some(m1.as_str()));
    // and migration 0006 fills it in for rows projected before the fix
    w.c.execute("UPDATE message SET reply_to_id = NULL", []).unwrap();
    w.c.execute_batch(include_str!("../migrations/0006_message_reply_to.sql")).unwrap();
    assert_eq!(linked(&w).as_deref(), Some(m1.as_str()));

    // plain chatter notifies nobody; restricted visibility never notifies
    let m3 = new_id(NOW as u64, [52; 10]);
    w.push(&a, "message.send", &space, &m3, msg("just talking", &[&kai], json!([])));
    let m4 = new_id(NOW as u64, [53; 10]);
    let ent = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    let mut hidden = msg("@June secret", &[&kai], ent);
    hidden["visibility"] = json!({"mode": "members", "member_ids": [kai]});
    w.push(&a, "message.send", &space, &m4, hidden);
    assert_eq!(w.inbox(&b).len(), 1);

    // Explicitly public visibility is as readable as the legacy unset value.
    let m5 = new_id(NOW as u64, [54; 10]);
    let ent = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    let mut public = msg("@June hello", &[&kai], ent);
    public["visibility"] = json!({"mode": "all"});
    w.push(&a, "message.send", &space, &m5, public);
    assert_eq!(w.inbox(&b).len(), 2);

    let m6 = new_id(NOW as u64, [55; 10]);
    let mut aside = msg(
        "@June aside",
        &[&kai],
        json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]),
    );
    aside["visibility"] = json!({"mode": "system_only"});
    w.push(&a, "message.send", &space, &m6, aside);
    assert_eq!(w.inbox(&b).len(), 2);
}

#[test]
fn each_recipient_chooses_per_channel_and_per_kind() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));
    let pref = |w: &mut W, key: &str, value: Value| {
        let id = new_id(NOW as u64, [w.n.wrapping_add(90); 10]);
        let scope = format!("account:{}", w.b);
        let who = w.b.clone();
        w.push(&who, "pref.set", &scope, &id, json!({"device": "", "key": key, "value": value}));
    };
    let mention = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    let mut n = 60u8;
    let mut send = |w: &mut W, text: &str, ent: Value| {
        n += 1;
        let id = new_id(NOW as u64, [n; 10]);
        w.push(&a, "message.send", &space, &id, msg(text, &[&kai], ent));
    };

    // "all": plain chatter in this channel now reaches Alex
    pref(&mut w, &format!("notify_channel:{GENERAL}"), json!("all"));
    send(&mut w, "chatter", json!([]));
    assert_eq!(w.inbox(&b)[0], ("message".to_string(), "chatter".to_string()));

    // "none": even a mention stays quiet
    pref(&mut w, &format!("notify_channel:{GENERAL}"), json!("none"));
    send(&mut w, "@June muted", mention.clone());
    assert_eq!(w.inbox(&b).len(), 1);

    // back to mentions, but mentions switched off as a kind
    pref(&mut w, &format!("notify_channel:{GENERAL}"), json!("mentions"));
    pref(&mut w, "notify_chat", json!({"mention": false}));
    send(&mut w, "@June again", mention.clone());
    assert_eq!(w.inbox(&b).len(), 1);
    pref(&mut w, "notify_chat", json!({}));
    send(&mut w, "@June now", mention);
    assert_eq!(w.inbox(&b)[0], ("mention".to_string(), "@June now".to_string()));
    assert!(w.inbox(&a).is_empty(), "the sender's own account is never notified");
}

#[test]
fn own_internal_chat_follows_each_members_rule() {
    let mut w = W::new();
    let a = w.a.clone();
    let acct = format!("account:{a}");
    let home_id = new_id(1, [4; 10]);
    let home = format!("space:{home_id}");
    ingest::grant(&w.c, &a, &home).unwrap();
    w.push(&a, "space.create", &home, &home_id, json!({"kind": "internal", "name": "Home"}));
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    let rin = new_id(NOW as u64, [42; 10]);
    for (id, name) in [(&kai, "Kai"), (&june, "June"), (&rin, "Rin")] {
        w.push(&a, "member.create", &acct, id, json!({"name": name}));
    }
    let general = new_id(NOW as u64, [30; 10]);
    let kj = new_id(NOW as u64, [31; 10]);
    w.push(&a, "channel.create", &home, &general, json!({"space_id": home_id, "kind": "text", "name": "general"}));
    w.push(
        &a,
        "channel.create",
        &home,
        &kj,
        json!({"space_id": home_id, "kind": "member_dm", "member_ids": [kai, june]}),
    );
    let sw = new_id(NOW as u64, [70; 10]);
    w.push(
        &a,
        "front.switch",
        &acct,
        &sw,
        json!({"entries": [{"subject_type": "member", "subject_id": rin, "level": "front"}]}),
    );
    let mut n = 60u8;
    let mut send = |w: &mut W, channel: &str, text: &str, ent: Value| {
        n += 1;
        let id = new_id(NOW as u64, [n; 10]);
        let mut m = msg(text, &[&kai], ent);
        m["channel_id"] = json!(channel);
        w.push(&a, "message.send", &home, &id, m);
    };
    let mention =
        |m: &str| json!([{"type": "mention", "offset": 0, "length": 4, "target_type": "member", "target_id": m}]);

    // a mention of June pings the system (default "always"); Kai naming themself doesn't
    send(&mut w, &general, "@June look", mention(&june));
    send(&mut w, &general, "@Kai me", mention(&kai));
    assert_eq!(w.inbox(&a), [("mention".to_string(), "@June look".to_string())]);

    // a member DM to June: June isn't fronting, so the default ("fronting") stays quiet
    send(&mut w, &kj, "psst", json!([]));
    assert_eq!(w.inbox(&a).len(), 1);
    // June asks for DMs always; June's mentions only while fronting
    let pref = new_id(NOW as u64, [80; 10]);
    w.push(
        &a,
        "pref.set",
        &acct,
        &pref,
        json!({"device": "", "key": format!("notify_member:{june}"),
        "value": {"dms": "always", "mentions": "fronting"}}),
    );
    send(&mut w, &kj, "psst again", json!([]));
    send(&mut w, &general, "@June hey", mention(&june));
    assert_eq!(w.inbox(&a)[0], ("member_dm".to_string(), "psst again".to_string()));
    assert_eq!(w.inbox(&a).len(), 2);

    // @front reaches Rin, who is fronting
    send(&mut w, &general, "@front hi", json!([{"type": "mention", "offset": 0, "length": 6, "target_type": "front"}]));
    assert_eq!(w.inbox(&a)[0], ("mention".to_string(), "@front hi".to_string()));
    assert!(w.inbox(&w.b.clone()).is_empty(), "nothing leaves the internal space");

    // Advanced "reply as mentioned member": the push names June as the inline reply's speaker
    let reply_as = |w: &W| -> Option<String> {
        let p: String =
            w.c.query_row("SELECT payload FROM notification WHERE source_op_id = ?1", [&w.last_op], |r| r.get(0))
                .unwrap();
        serde_json::from_str::<Value>(&p).unwrap()["reply_as"].as_str().map(str::to_string)
    };
    // June's mentions ping only while fronting (set above), so make June front
    let sw2 = new_id(NOW as u64, [71; 10]);
    w.push(
        &a,
        "front.switch",
        &acct,
        &sw2,
        json!({"entries": [{"subject_type": "member", "subject_id": june, "level": "front"}]}),
    );
    send(&mut w, &general, "@June one", mention(&june));
    assert_eq!(reply_as(&w), None, "off by default");
    let chat = new_id(NOW as u64, [81; 10]);
    w.push(
        &a,
        "pref.set",
        &acct,
        &chat,
        json!({"device": "", "key": "notify_chat", "value": {"reply_as_mentioned": true}}),
    );
    send(&mut w, &general, "@June two", mention(&june));
    assert_eq!(reply_as(&w), Some(june.clone()));
}

#[test]
fn own_switches_ping_only_when_asked_and_not_when_undone() {
    let mut w = W::new();
    let a = w.a.clone();
    let acct = format!("account:{a}");
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &acct, &kai, json!({"name": "Kai"}));
    w.push(&a, "member.create", &acct, &june, json!({"name": "June"}));
    let switch = |w: &mut W, m: &str, n: u8| -> String {
        let id = new_id(NOW as u64, [n; 10]);
        w.push(
            &a,
            "front.switch",
            &acct,
            &id,
            json!({"entries": [{"subject_type": "member", "subject_id": m, "level": "front"}]}),
        );
        w.last_op.clone()
    };

    // off by default
    switch(&mut w, &kai, 70);
    assert!(w.inbox(&a).is_empty());

    let p = new_id(NOW as u64, [80; 10]);
    w.push(&a, "pref.set", &acct, &p, json!({"device": "", "key": "notify_chat", "value": {"own_switch": true}}));
    switch(&mut w, &june, 71);
    assert_eq!(w.inbox(&a), [("own_switch".to_string(), "June".to_string())]);

    // undone before it settles: nothing new
    let sw = switch(&mut w, &kai, 72);
    let r = new_id(NOW as u64, [73; 10]);
    w.push(&a, "front.retract", &acct, &r, json!({"target_op_id": sw}));
    assert_eq!(w.inbox(&a).len(), 1);
}

/// A configured account's Body edits (`front.change`, D-080) ping like legacy switches, are
/// described by exact layer IDs/names, and stay quiet when undone or about another front.
#[test]
fn named_body_changes_ping_own_devices() {
    let mut w = W::new();
    let a = w.a.clone();
    let acct = format!("account:{a}");
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &acct, &kai, json!({"name": "Kai"}));
    w.push(&a, "member.create", &acct, &june, json!({"name": "June"}));
    let p = new_id(NOW as u64, [80; 10]);
    w.push(&a, "pref.set", &acct, &p, json!({"device": "", "key": "notify_chat", "value": {"own_switch": true}}));
    w.push(&a, "front.layer", &acct, &a, json!({"layer_id": "quiet", "name": "Quiet", "level": 5}));
    let change = |w: &mut W, front: &str, payload: Value| -> String {
        w.push(&a, "front.change", &acct, front, payload);
        w.last_op.clone()
    };
    change(
        &mut w,
        &a,
        json!({"action": "switch", "entries": [
        {"subject_type": "member", "subject_id": june, "layer_id": "front", "is_primary": true},
        {"subject_type": "member", "subject_id": kai, "layer_id": "quiet", "is_primary": false}]}),
    );
    assert_eq!(w.inbox(&a), [("own_switch".to_string(), "June · Quiet Kai".to_string())]);

    // undone before it settles: nothing new
    let sw = change(
        &mut w,
        &a,
        json!({"action": "switch", "entries": [
        {"subject_type": "member", "subject_id": kai, "layer_id": "front", "is_primary": true}]}),
    );
    change(&mut w, &a, json!({"action": "retract", "target_op_id": sw}));
    assert_eq!(w.inbox(&a).len(), 1);

    // another front's change is not a Body switch
    let other = new_id(NOW as u64, [90; 10]);
    w.push(&a, "front.define", &acct, &other, json!({"name": "Work"}));
    change(
        &mut w,
        &other,
        json!({"action": "switch", "entries": [
        {"subject_type": "member", "subject_id": kai, "layer_id": "front", "is_primary": true}]}),
    );
    assert_eq!(w.inbox(&a).len(), 1);
}

/// Webhooks (API.md §7): `message.created` and `post.created` go to the author's own account's
/// hooks, never to another account in the same space.
#[test]
fn message_and_post_webhooks_go_to_their_author() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    for (id, acct) in [("hook-a", &a), ("hook-b", &b)] {
        w.c.execute(
            "INSERT INTO webhook (id, account_id, url, secret, events, created_at)
             VALUES (?1, ?2, 'https://example.org/h', 's', '[\"message.created\",\"post.created\"]', 0)",
            [id, acct.as_str()],
        )
        .unwrap();
    }
    let kai = new_id(NOW as u64, [40; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    let space_id = space.strip_prefix("space:").unwrap().to_string();
    w.push(&a, "space.create", &space, &space_id, json!({"kind": "shared", "name": "Club"}));
    let chan = new_id(NOW as u64, [49; 10]);
    w.push(&a, "channel.create", &space, &chan, json!({"space_id": space_id, "kind": "text", "name": "general"}));
    let m = new_id(NOW as u64, [50; 10]);
    let mut body = msg("hello hooks", &[&kai], json!([]));
    body["channel_id"] = json!(chan);
    w.push(&a, "message.send", &space, &m, body);
    let p = new_id(NOW as u64, [51; 10]);
    w.push(
        &a,
        "post.create",
        &format!("account:{a}"),
        &p,
        json!({"kind": "note", "authors": [kai], "text": "a note", "entities": [], "visibility": {"mode": "private"}}),
    );
    let ops: Vec<chorus_core::op::Op> = chorus_server::oplog::scope_after(&w.c, &space, 0, 100)
        .unwrap()
        .into_iter()
        .chain(chorus_server::oplog::scope_after(&w.c, &format!("account:{a}"), 0, 100).unwrap())
        .collect();
    let d = chorus_server::webhooks::deliveries_for(&w.c, &ops, NOW).unwrap();
    let got: Vec<(String, String)> = d.iter().map(|d| (d.webhook_id.clone(), d.event.clone())).collect();
    assert!(got.contains(&("hook-a".into(), "message.created".into())), "{got:?}");
    assert!(got.contains(&("hook-a".into(), "post.created".into())), "{got:?}");
    assert!(!got.iter().any(|(h, _)| h == "hook-b"), "another account in the space gets nothing: {got:?}");
    let body = &d.iter().find(|d| d.event == "message.created").unwrap().body;
    assert!(body.contains("hello hooks"), "{body}");
}

/// Reposts keep their link in SQL (the posts API reads `repost_of_id`), and migration 0006 fills
/// it in for posts projected before.
#[test]
fn reposts_keep_their_source() {
    let mut w = W::new();
    let a = w.a.clone();
    let acct = format!("account:{a}");
    let kai = new_id(NOW as u64, [40; 10]);
    w.push(&a, "member.create", &acct, &kai, json!({"name": "Kai"}));
    let note = |text: &str| json!({"kind": "note", "authors": [kai], "text": text, "entities": [], "visibility": {"mode": "private"}});
    let first = new_id(NOW as u64, [60; 10]);
    w.push(&a, "post.create", &acct, &first, note("original"));
    let second = new_id(NOW as u64, [61; 10]);
    let mut repost = note("");
    repost["repost_of"] = json!(first);
    w.push(&a, "post.create", &acct, &second, repost);
    let link = |w: &W| -> Option<String> {
        w.c.query_row("SELECT repost_of_id FROM post WHERE id = ?1", [&second], |r| r.get(0)).unwrap()
    };
    assert_eq!(link(&w).as_deref(), Some(first.as_str()));
    w.c.execute("UPDATE post SET repost_of_id = NULL", []).unwrap();
    w.c.execute_batch(include_str!("../migrations/0006_message_reply_to.sql")).unwrap();
    assert_eq!(link(&w).as_deref(), Some(first.as_str()));
}

/// SPEC §5.3 mentions: `@group` reaches every member in it, `@account` the account, `@front` who
/// was fronting when the message was written; a guest of a channel hears about a mention there,
/// and nobody hears about one in a channel they can't view.
#[test]
fn every_kind_of_mention_reaches_whom_it_names() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let (acct_a, acct_b) = (format!("account:{a}"), format!("account:{b}"));
    let kai = new_id(NOW as u64, [40; 10]);
    let rin = new_id(NOW as u64, [41; 10]);
    let june = new_id(NOW as u64, [42; 10]);
    let mo = new_id(NOW as u64, [44; 10]); // writes; authors never ping themselves
    w.push(&a, "member.create", &acct_a, &mo, json!({"name": "Mo"}));
    w.push(&a, "member.create", &acct_a, &kai, json!({"name": "Kai"}));
    w.push(&a, "member.create", &acct_a, &rin, json!({"name": "Rin"}));
    w.push(&b, "member.create", &acct_b, &june, json!({"name": "June"}));
    let friends = new_id(NOW as u64, [43; 10]);
    w.push(&b, "group.create", &acct_b, &friends, json!({"name": "Friends", "kind": "group"}));
    w.push(&b, "group.add_member", &acct_b, &friends, json!({"member_id": june}));
    let mut n = 90u8;
    let mut send = |w: &mut W, scope: &str, channel: &str, text: &str, target: (&str, Option<&str>)| {
        n += 1;
        let id = new_id(NOW as u64, [n; 10]);
        let mut e = json!({"type": "mention", "offset": 0, "length": 3, "target_type": target.0});
        if let Some(t) = target.1 {
            e["target_id"] = json!(t);
        }
        let mut m = msg(text, &[&mo], json!([e]));
        m["channel_id"] = json!(channel);
        w.push(&a, "message.send", scope, &id, m);
    };
    let latest = |w: &W, who: &str| w.inbox(who).first().cloned();
    let mention = |t: &str| Some(("mention".to_string(), t.to_string()));

    // in the shared space: Alex's group, the account itself, and Alex's front
    send(&mut w, &space, GENERAL, "@Friends lunch?", ("group", Some(&friends)));
    assert_eq!(latest(&w, &b), mention("@Friends lunch?"));
    send(&mut w, &space, GENERAL, "@alex hello", ("account", Some(&b)));
    assert_eq!(latest(&w, &b), mention("@alex hello"));
    send(&mut w, &space, GENERAL, "@front anyone?", ("front", Some(&b)));
    assert_eq!(latest(&w, &b), mention("@alex hello"), "nobody of Alex's was fronting");
    let sw = new_id(NOW as u64, [60; 10]);
    w.push(
        &b,
        "front.switch",
        &acct_b,
        &sw,
        json!({"entries": [{"subject_type": "member", "subject_id": june, "level": "front"}]}),
    );
    send(&mut w, &space, GENERAL, "@front there?", ("front", Some(&b)));
    assert_eq!(latest(&w, &b), mention("@front there?"));
    assert!(w.inbox(&a).is_empty(), "the author's account never hears its own");

    // A's internal space: `@front` is who was fronting when the message was written
    let home_id = new_id(1, [4; 10]);
    let home = format!("space:{home_id}");
    ingest::grant(&w.c, &a, &home).unwrap();
    w.push(&a, "space.create", &home, &home_id, json!({"kind": "internal", "name": "Home"}));
    let (general, news) = (new_id(NOW as u64, [31; 10]), new_id(NOW as u64, [32; 10]));
    for (c, name) in [(&general, "general"), (&news, "news")] {
        w.push(&a, "channel.create", &home, c, json!({"space_id": home_id, "kind": "text", "name": name}));
    }
    let pref = new_id(NOW as u64, [61; 10]);
    w.push(
        &a,
        "pref.set",
        &acct_a,
        &pref,
        json!({"device": "", "key": format!("notify_member:{rin}"), "value": {"mentions": "never"}}),
    );
    let with_kai = NOW + i64::from(w.n + 1) * 1000; // Kai fronts from here …
    let sw = new_id(NOW as u64, [62; 10]);
    w.push(
        &a,
        "front.switch",
        &acct_a,
        &sw,
        json!({"entries": [{"subject_type": "member", "subject_id": kai, "level": "front"}]}),
    );
    let sw = new_id(NOW as u64, [63; 10]);
    w.push(
        &a,
        "front.switch",
        &acct_a,
        &sw,
        json!({"entries": [{"subject_type": "member", "subject_id": rin, "level": "front"}]}),
    );
    send(&mut w, &home, &general, "@front now", ("front", None));
    assert!(w.inbox(&a).is_empty(), "Rin is fronting now, and Rin's mentions are off");
    w.written = Some(with_kai + 500); // … written offline while Kai fronted, arriving now
    send(&mut w, &home, &general, "@front then", ("front", None));
    assert_eq!(latest(&w, &a), mention("@front then"));

    // a guest of #news hears about a mention there, not about one in #general
    w.push(
        &a,
        "channel.set_permission",
        &home,
        &news,
        json!({"target_type": "account", "target_id": b, "allow": ["view"], "deny": []}),
    );
    let before = w.inbox(&b).len();
    send(&mut w, &home, &general, "@June inside", ("member", Some(&june)));
    assert_eq!(w.inbox(&b).len(), before, "Alex can't view #general");
    send(&mut w, &home, &news, "@June news!", ("member", Some(&june)));
    assert_eq!(latest(&w, &b), mention("@June news!"));
}

/// D-085: a configured account's `@front` uses the `mentions` pool of the channel's selected front
/// and a `fronting` rule its `chat_alerts` pool; another channel keeps Body's legacy pools.
#[test]
fn channel_front_purposes_drive_front_mentions_and_fronting_alerts() {
    let mut w = W::new();
    let a = w.a.clone();
    let acct = format!("account:{a}");
    let (mo, kai, rin) = (new_id(NOW as u64, [40; 10]), new_id(NOW as u64, [41; 10]), new_id(NOW as u64, [42; 10]));
    for (m, name) in [(&mo, "Mo"), (&kai, "Kai"), (&rin, "Rin")] {
        w.push(&a, "member.create", &acct, m, json!({"name": name}));
    }
    let home_id = new_id(1, [4; 10]);
    let home = format!("space:{home_id}");
    ingest::grant(&w.c, &a, &home).unwrap();
    w.push(&a, "space.create", &home, &home_id, json!({"kind": "internal", "name": "Home"}));
    let (general, news) = (new_id(NOW as u64, [31; 10]), new_id(NOW as u64, [32; 10]));
    for (c, name) in [(&general, "general"), (&news, "news")] {
        w.push(&a, "channel.create", &home, c, json!({"space_id": home_id, "kind": "text", "name": name}));
    }
    let head = new_id(NOW as u64, [50; 10]);
    let mut n = 70u8;
    let mut id = || {
        n += 1;
        new_id(NOW as u64, [n; 10])
    };
    w.push(&a, "front.layer", &acct, &a, json!({"layer_id": "quiet", "name": "Quiet", "level": 5}));
    w.push(&a, "front.define", &acct, &head, json!({"name": "Head", "purpose_layers": {"mentions": ["quiet"]}}));
    let entry = |m: &str, layer: &str| json!({"subject_type": "member", "subject_id": m, "layer_id": layer, "is_primary": false});
    w.push(&a, "front.change", &acct, &a, json!({"action": "switch", "entries": [entry(&kai, "front")]}));
    let rin_joins = NOW + i64::from(w.n + 1) * 1000;
    w.push(&a, "front.change", &acct, &head, json!({"action": "switch", "entries": [entry(&rin, "quiet")]}));
    let key = format!("authorship_front:{general}");
    w.push(&a, "pref.set", &acct, &id(), json!({"device": "", "key": key, "value": head}));
    w.push(
        &a,
        "pref.set",
        &acct,
        &id(),
        json!({"device": "", "key": format!("notify_member:{kai}"), "value": {"mentions": "never"}}),
    );
    let send = |w: &mut W, channel: &str, text: &str, id: String| {
        let e = json!([{"type": "mention", "offset": 0, "length": 6, "target_type": "front"}]);
        let mut m = msg(text, &[&mo], e);
        m["channel_id"] = json!(channel);
        w.push(&a, "message.send", &home, &id, m);
    };
    send(&mut w, &news, "@front body", id());
    assert!(w.inbox(&a).is_empty(), "Body's legacy pool is Kai, whose mentions are off");
    send(&mut w, &general, "@front head", id());
    assert_eq!(w.inbox(&a).first(), Some(&("mention".to_string(), "@front head".to_string())), "Head's quiet Rin");
    w.written = Some(rin_joins - 500); // written offline before Rin joined Head, arriving now
    send(&mut w, &general, "@front earlier", id());
    assert_eq!(w.inbox(&a).first().unwrap().1, "@front head", "Head's pool as it stood then was empty");

    // Rin hears mentions only while fronting: Head's chat-alert pool keeps the legacy layers
    w.push(
        &a,
        "pref.set",
        &acct,
        &id(),
        json!({"device": "", "key": format!("notify_member:{rin}"), "value": {"mentions": "fronting"}}),
    );
    let before = w.inbox(&a).len();
    send(&mut w, &general, "@front quiet", id());
    assert_eq!(w.inbox(&a).len(), before, "quiet is not a chat-alert layer until selected");
    w.push(&a, "front.define", &acct, &head, json!({"purpose_layers": {"chat_alerts": ["quiet"]}}));
    send(&mut w, &general, "@front alert", id());
    assert_eq!(w.inbox(&a).first(), Some(&("mention".to_string(), "@front alert".to_string())));
}

#[test]
fn configured_own_switch_liveness_uses_canonical_body_for_old_and_new_targets() {
    for legacy_target in [true, false] {
        for legacy_undo in [false, true] {
            for restored in [false, true] {
                let mut w = W::new();
                let a = w.a.clone();
                let acct = format!("account:{a}");
                let kai = new_id(NOW as u64, [40; 10]);
                w.push(&a, "member.create", &acct, &kai, json!({"name":"Kai"}));
                w.push(&a, "front.layer", &acct, &a, json!({"layer_id":"quiet","name":"Quiet","level":5}));
                w.push(&a, "pref.set", &acct, &a, json!({"device":"","key":"notify_chat","value":{"own_switch":true}}));
                if legacy_target {
                    w.push(
                        &a,
                        "front.switch",
                        &acct,
                        &a,
                        json!({"entries":[{"subject_type":"member","subject_id":kai,"level":"front"}]}),
                    );
                } else {
                    w.push(&a,"front.change",&acct,&a,json!({"action":"switch","entries":[{"subject_type":"member","subject_id":kai,"layer_id":"front"}]}));
                }
                let target = w.last_op.clone();
                if legacy_undo {
                    w.push(&a, "front.retract", &acct, &new_id(NOW as u64, [73; 10]), json!({"target_op_id":target}));
                } else {
                    w.push(&a, "front.change", &acct, &a, json!({"action":"retract","target_op_id":target}));
                }
                if restored {
                    w.push(&a, "front.change", &acct, &a, json!({"action":"unretract","target_op_id":target}));
                }
                assert_eq!(
                    w.inbox(&a).len(),
                    usize::from(restored),
                    "legacy target={legacy_target}, legacy undo={legacy_undo}, restored={restored}"
                );
            }
        }
    }
}

#[test]
fn a_phone_without_push_asks_for_what_is_new_and_not_its_own() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));
    let m1 = new_id(NOW as u64, [50; 10]);
    let ent = json!([{"type": "mention", "offset": 3, "length": 5, "target_type": "member", "target_id": june}]);
    w.push(&a, "message.send", &space, &m1, msg("hi @June", &[&kai], ent));
    assert_eq!(w.inbox(&b).len(), 1);

    let ids = |after: i64, device: &str| -> Vec<String> {
        notifier::list_for(&w.c, &b, Some(device), after, 50).unwrap()["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|i| i["text"].as_str().unwrap().to_string())
            .collect()
    };
    let delivered: i64 =
        w.c.query_row("SELECT delivered_at FROM notification WHERE recipient_account_id = ?1", [&b], |r| r.get(0))
            .unwrap();
    // new since the last check, and nothing once it has been seen
    assert_eq!(ids(delivered - 1, "phone"), ["hi @June"]);
    assert!(ids(delivered, "phone").is_empty());
    // a ping about something this device wrote isn't shown on it (the push skips it too)
    w.c.execute("UPDATE notification SET payload = json_set(payload, '$.from_device', 'phone')", []).unwrap();
    assert!(ids(0, "phone").is_empty());
    assert_eq!(ids(0, "laptop"), ["hi @June"]);
}

#[test]
fn chat_and_sender_icons_follow_space_channel_and_dm_rules() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let space_id = space.strip_prefix("space:").unwrap().to_string();
    let kai_avatar = "a".repeat(64);
    let chan_avatar = "b".repeat(64);
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(
        &a,
        "member.create",
        &format!("account:{a}"),
        &kai,
        json!({"name": "Kai", "color": "#EEAA62", "sigils": ["✦"], "avatar_blob": kai_avatar}),
    );
    w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));
    let mention = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);

    // 1. Single-channel shared space (#general in "Club") uses the space's title and initial disc
    let m1 = new_id(NOW as u64, [50; 10]);
    w.push(&a, "message.send", &space, &m1, msg("@June welcome", &[&kai], mention.clone()));
    notifier::process_due(&w.c, NOW + 1_000_000).unwrap();
    let items = notifier::list_for(&w.c, &b, None, 0, 10).unwrap()["items"].as_array().unwrap().clone();
    assert_eq!(items[0]["title"], "Kai · Club");
    assert_eq!(items[0]["chat"]["title"], "Club");
    assert_eq!(items[0]["chat"]["glyph"], "C");
    assert_eq!(items[0]["chat"]["direct"], false);
    assert!(items[0]["chat"]["color"].as_str().unwrap().starts_with('#'));
    assert_eq!(items[0]["sender"]["name"], "Kai");
    assert_eq!(items[0]["sender"]["glyph"], "✦");
    assert_eq!(items[0]["sender"]["color"], "#EEAA62");
    assert_eq!(items[0]["sender"]["avatar_blob"], kai_avatar);

    // 2. Second channel with custom avatar_blob and colour carries both space+channel title and channel avatar
    let spoilers = new_id(NOW as u64, [35; 10]);
    w.push(
        &a,
        "channel.create",
        &space,
        &spoilers,
        json!({"space_id": space_id, "kind": "text", "name": "spoilers", "color": "#8FB8E8", "avatar_blob": chan_avatar}),
    );
    let m2 = new_id(NOW as u64, [51; 10]);
    let mut sp_msg = msg("@June ch4!", &[&kai], mention);
    sp_msg["channel_id"] = json!(spoilers);
    w.push(&a, "message.send", &space, &m2, sp_msg);
    notifier::process_due(&w.c, NOW + 1_000_000).unwrap();
    let items = notifier::list_for(&w.c, &b, None, 0, 10).unwrap()["items"].as_array().unwrap().clone();
    assert_eq!(items[0]["chat"]["title"], "Club · #spoilers");
    assert_eq!(items[0]["chat"]["glyph"], "S");
    assert_eq!(items[0]["chat"]["color"], "#8FB8E8");
    assert_eq!(items[0]["chat"]["avatar_blob"], chan_avatar);
    assert_eq!(items[0]["chat"]["direct"], false);
}

/// A thread is as private as its parent (`visibility::PUBLIC_MESSAGE_SQL`): what the Stars say in a
/// thread under their own system-only aside never reaches Alex's account, as sync never sends it.
#[test]
fn a_thread_under_a_private_aside_notifies_nobody_else() {
    let mut w = W::with_space("dm");
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let space_id = space.strip_prefix("space:").unwrap().to_string();
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));
    let mention = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    let thread_under = |w: &mut W, parent: &str, n: u8| -> String {
        let thread = new_id(NOW as u64, [n; 10]);
        w.push(
            &a,
            "channel.create",
            &space,
            &thread,
            json!({"space_id": space_id, "kind": "thread", "name": "t", "parent_message_id": parent}),
        );
        thread
    };
    let in_thread = |text: &str, thread: &str, entities: Value| {
        let mut m = msg(text, &[&kai], entities);
        m["channel_id"] = json!(thread);
        m
    };

    let aside = new_id(NOW as u64, [50; 10]);
    let mut p = msg("just us", &[&kai], json!([]));
    p["visibility"] = json!({"mode": "system_only"});
    w.push(&a, "message.send", &space, &aside, p);
    let hidden = thread_under(&mut w, &aside, 60);
    // a plain message (a DM's "dm" kind) and a mention of June, both inside the hidden thread
    w.push(&a, "message.send", &space, &new_id(NOW as u64, [51; 10]), in_thread("thread chatter", &hidden, json!([])));
    w.push(
        &a,
        "message.send",
        &space,
        &new_id(NOW as u64, [52; 10]),
        in_thread("@June secret", &hidden, mention.clone()),
    );
    assert!(w.inbox(&b).is_empty(), "{:?}", w.inbox(&b));
    let rows: i64 =
        w.c.query_row("SELECT count(*) FROM notification WHERE recipient_account_id = ?1", [&b], |r| r.get(0)).unwrap();
    assert_eq!(rows, 0);

    // under a public message the thread notifies as usual
    let open = new_id(NOW as u64, [53; 10]);
    w.push(&a, "message.send", &space, &open, msg("hello", &[&kai], json!([])));
    let shown = thread_under(&mut w, &open, 61);
    w.push(&a, "message.send", &space, &new_id(NOW as u64, [54; 10]), in_thread("in the open", &shown, json!([])));
    w.push(&a, "message.send", &space, &new_id(NOW as u64, [55; 10]), in_thread("@June look", &shown, mention));
    let mut got = w.inbox(&b);
    got.sort();
    assert_eq!(
        got,
        [
            ("dm".to_string(), "hello".to_string()),
            ("dm".to_string(), "in the open".to_string()),
            ("mention".to_string(), "@June look".to_string()),
        ]
    );
}

/// D-101/D-126/D-147: an author card in shared chat carries pronouns only where the profile and its
/// `pronouns` part reach the reader; name, colour, sigil and avatar follow the member regardless.
#[test]
fn shared_chat_author_cards_carry_pronouns_only_under_the_profile_rule() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let space_id = space.strip_prefix("space:").unwrap().to_string();
    let kai = new_id(NOW as u64, [40; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai", "pronouns": "they/them"}));
    w.push(&a, "message.send", &space, &new_id(NOW as u64, [50; 10]), msg("hello", &[&kai], json!([])));
    let card = |w: &W| -> Value {
        let all = spaces::authors(&w.c, &b, &space_id).unwrap();
        all["members"].as_array().unwrap().iter().find(|m| m["id"] == kai.as_str()).cloned().unwrap()
    };
    // a private profile (the default): the card is there, its pronouns aren't
    assert_eq!(card(&w)["name"], "Kai");
    assert_eq!(card(&w)["pronouns"], Value::Null);
    // the profile open to followers but pronouns kept private: still nothing
    w.c.execute(
        "INSERT INTO follow(id, follower_account_id, target_account_id, status, created_at) VALUES ('f', ?1, ?2, 'active', 0)",
        [&b, &a],
    )
    .unwrap();
    w.c.execute(
        "UPDATE member SET visibility = '{\"mode\":\"followers\"}', field_visibility = '{\"pronouns\":{\"mode\":\"private\"}}' WHERE id = ?1",
        [&kai],
    )
    .unwrap();
    assert_eq!(card(&w)["pronouns"], Value::Null);
    // and both shared with Alex's account: they appear
    w.c.execute("UPDATE member SET field_visibility = '{\"pronouns\":{\"mode\":\"followers\"}}' WHERE id = ?1", [&kai])
        .unwrap();
    assert_eq!(card(&w)["pronouns"], "they/them");
}

/// One rule for a member's shown name: the display name unless it is blank, else the name, in
/// notification titles and reaction lists alike (`profiles::shown_name_sql`).
#[test]
fn a_blank_display_name_falls_back_to_the_name() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai", "display_name": ""}));
    w.push(
        &b,
        "member.create",
        &format!("account:{b}"),
        &june,
        json!({"name": "June", "display_name": " ", "visibility": {"mode": "server"}}),
    );
    let mention = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    w.push(&a, "message.send", &space, &new_id(NOW as u64, [50; 10]), msg("@June hi", &[&kai], mention));
    notifier::process_due(&w.c, NOW + 1_000_000).unwrap();
    let items = notifier::list_for(&w.c, &b, None, 0, 10).unwrap()["items"].as_array().unwrap().clone();
    assert_eq!(items[0]["title"], "Kai · Club");

    // June (her profile open to all) hearts a post of the Stars: its reactions and the Stars'
    // activity name her
    let post = new_id(NOW as u64, [60; 10]);
    w.push(
        &a,
        "post.create",
        &format!("account:{a}"),
        &post,
        json!({"kind": "note", "authors": [kai], "text": "hello", "entities": [], "visibility": {"mode": "server"}}),
    );
    w.push(
        &b,
        "post.react",
        &format!("account:{b}"),
        &new_id(NOW as u64, [61; 10]),
        json!({"target_type": "post", "target_id": post, "emoji": "💜", "member_id": june}),
    );
    let read = posts::one(&w.c, &a, &post).unwrap().unwrap();
    assert_eq!(read["reactions"][0]["member_name"], "June");
    let activity = posts::activity(&w.c, &a, None, 50).unwrap();
    assert_eq!(activity["reactions"][&post][0]["member_name"], "June");
}

/// D-007/D-082: REST message reads keep authors in order (the first is the primary speaker), then
/// any segment-only authors in segment order, each once; never in id order.
#[test]
fn rest_message_reads_keep_author_order() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    // ids that sort opposite to how they speak
    let first = new_id(NOW as u64, [45; 10]);
    let second = new_id(NOW as u64, [44; 10]);
    let in_segment = new_id(NOW as u64, [38; 10]);
    for (id, name) in [(&first, "Kai"), (&second, "Rin"), (&in_segment, "Ash")] {
        w.push(&a, "member.create", &format!("account:{a}"), id, json!({"name": name}));
    }
    assert!(in_segment < second && second < first);
    let m = new_id(NOW as u64, [50; 10]);
    let mut p = msg(
        "us
Ash",
        &[&first, &second],
        json!([]),
    );
    p["segments"] = json!([
        {"offset": 0, "length": 2, "authors": [first, second]},
        {"offset": 3, "length": 3, "authors": [in_segment, first]}
    ]);
    w.push(&a, "message.send", &space, &m, p);
    let want = json!([first, second, in_segment]);
    for who in [&a, &b] {
        let principal = api_data::Principal::owner(who);
        let one = messages::one(&w.c, &principal, &m).unwrap().unwrap();
        assert_eq!(one["authors"], want, "GET /messages/{{id}} for {who}");
        let page = messages::list(&w.c, &principal, GENERAL, &Default::default()).unwrap().unwrap();
        assert_eq!(page["items"][0]["authors"], want, "GET /channels/{{id}}/messages for {who}");
    }
}

/// Q25/D-149: a delivered notification leaves the reader's list while its source is gone for
/// them (deleted, or no longer theirs to read) and comes back with it; nothing is erased.
#[test]
fn a_delivered_notification_follows_its_source() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));
    let mention = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    let m = new_id(NOW as u64, [50; 10]);
    w.push(&a, "message.send", &space, &m, msg("@June hi", &[&kai], mention));
    assert_eq!(w.inbox(&b).len(), 1);
    let rows = |w: &W| -> i64 {
        w.c.query_row("SELECT count(*) FROM notification WHERE recipient_account_id = ?1", [&b], |r| r.get(0)).unwrap()
    };

    // deleted: gone from the list (and from what a phone without push asks for), not the table
    w.push(&a, "message.delete", &space, &m, json!({}));
    assert!(w.inbox(&b).is_empty());
    assert!(notifier::list_for(&w.c, &b, Some("phone"), 0, 50).unwrap()["items"].as_array().unwrap().is_empty());
    assert_eq!(rows(&w), 1);
    w.push(&a, "message.restore", &space, &m, json!({}));
    assert_eq!(w.inbox(&b).len(), 1, "restored, it is back");

    // no longer allowed to view the channel: gone; allowed again: back
    let deny =
        |allow: Value, deny: Value| json!({"target_type": "account", "target_id": b, "allow": allow, "deny": deny});
    w.push(&a, "channel.set_permission", &space, GENERAL, deny(json!([]), json!(["view"])));
    assert!(w.inbox(&b).is_empty());
    w.push(&a, "channel.set_permission", &space, GENERAL, deny(json!(["view"]), json!([])));
    assert_eq!(w.inbox(&b).len(), 1);

    // a reply to the Stars' post: gone while the reply can't be read by them, back after
    let ash = new_id(NOW as u64, [42; 10]);
    w.push(&b, "member.create", &format!("account:{b}"), &ash, json!({"name": "Ash"}));
    let root = new_id(NOW as u64, [60; 10]);
    let post = |authors: &str, extra: Value| {
        let mut p = json!({"kind": "note", "authors": [authors], "text": "words", "entities": [],
                           "visibility": {"mode": "server"}});
        for (k, v) in extra.as_object().unwrap() {
            p[k] = v.clone();
        }
        p
    };
    w.push(&a, "post.create", &format!("account:{a}"), &root, post(&kai, json!({})));
    let reply = new_id(NOW as u64, [61; 10]);
    w.push(&b, "post.create", &format!("account:{b}"), &reply, post(&ash, json!({"reply_to": root})));
    let replies = |w: &W| w.inbox(&a).iter().filter(|(k, _)| k == "post_reply").count();
    assert_eq!(replies(&w), 1);
    w.push(&b, "post.set_visibility", &format!("account:{b}"), &reply, json!({"visibility": {"mode": "private"}}));
    assert_eq!(replies(&w), 0);
    w.push(&b, "post.set_visibility", &format!("account:{b}"), &reply, json!({"visibility": {"mode": "server"}}));
    assert_eq!(replies(&w), 1);
    w.push(&b, "post.delete", &format!("account:{b}"), &reply, json!({}));
    assert_eq!(replies(&w), 0);
}

/// Q27/D-151: a forward notifies like a message. Its text is the forwarder's own comment, or a
/// neutral line, never the forwarded text (the snapshot).
#[test]
fn forwards_notify_like_messages() {
    let mut w = W::with_space("dm");
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = new_id(NOW as u64, [40; 10]);
    let june = new_id(NOW as u64, [41; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));
    let original = new_id(NOW as u64, [50; 10]);
    w.push(&a, "message.send", &space, &original, msg("the original words", &[&kai], json!([])));
    let base = w.inbox(&b).len();
    let forward = |text: &str, entities: Value| {
        let mut p = msg(text, &[&kai], entities);
        p["forward_of_id"] = json!(original);
        p["forward_snapshot"] = json!([{"message_id": original, "authors": [kai], "text": "the original words",
                                        "entities": [], "occurred_at": NOW}]);
        p
    };
    w.push(&a, "message.forward", &space, &new_id(NOW as u64, [51; 10]), forward("", json!([])));
    let got = w.inbox(&b);
    assert_eq!(got.len(), base + 1);
    assert_eq!(got[0], ("dm".to_string(), "Forwarded a message".to_string()));
    // a comment is the text; a mention in it is a mention
    let mention = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    w.push(&a, "message.forward", &space, &new_id(NOW as u64, [52; 10]), forward("@June see this", mention));
    assert_eq!(w.inbox(&b)[0], ("mention".to_string(), "@June see this".to_string()));
    // never from a system-only aside
    let mut aside = forward("just us", json!([]));
    aside["visibility"] = json!({"mode": "system_only"});
    w.push(&a, "message.forward", &space, &new_id(NOW as u64, [53; 10]), aside);
    assert_eq!(w.inbox(&b).len(), base + 2);

    // in the system's own space, under each member's rule like a message
    let home_id = new_id(1, [4; 10]);
    let home = format!("space:{home_id}");
    ingest::grant(&w.c, &a, &home).unwrap();
    w.push(&a, "space.create", &home, &home_id, json!({"kind": "internal", "name": "Home"}));
    let rin = new_id(NOW as u64, [42; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &rin, json!({"name": "Rin"}));
    let general = new_id(NOW as u64, [30; 10]);
    w.push(&a, "channel.create", &home, &general, json!({"space_id": home_id, "kind": "text", "name": "general"}));
    let mut own = forward(
        "@Rin look",
        json!([{"type": "mention", "offset": 0, "length": 4, "target_type": "member", "target_id": rin}]),
    );
    own["channel_id"] = json!(general);
    w.push(&a, "message.forward", &home, &new_id(NOW as u64, [54; 10]), own);
    assert_eq!(w.inbox(&a)[0], ("mention".to_string(), "@Rin look".to_string()));
}

/// D-154: a reaction from another account's member is always counted, but names its member only
/// where that member's profile is open to the reader, and then by the shown name (display name),
/// never a full name behind it. The reacting account sees its own members complete.
#[test]
fn reaction_names_follow_the_members_profile() {
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    let kai = new_id(NOW as u64, [40; 10]);
    let hidden = new_id(NOW as u64, [41; 10]);
    let open = new_id(NOW as u64, [42; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
    w.push(&b, "member.create", &format!("account:{b}"), &hidden, json!({"name": "June Hidden"}));
    w.push(
        &b,
        "member.create",
        &format!("account:{b}"),
        &open,
        json!({"name": "Rin Fullname", "display_name": "Rin", "visibility": {"mode": "server"}}),
    );
    let post = new_id(NOW as u64, [60; 10]);
    w.push(
        &a,
        "post.create",
        &format!("account:{a}"),
        &post,
        json!({"kind": "note", "authors": [kai], "text": "hello", "entities": [], "visibility": {"mode": "server"}}),
    );
    for (n, member, emoji) in [(61u8, &hidden, "🌙"), (62, &open, "🌿")] {
        w.push(
            &b,
            "post.react",
            &format!("account:{b}"),
            &new_id(NOW as u64, [n; 10]),
            json!({"target_type": "post", "target_id": post, "emoji": emoji, "member_id": member}),
        );
    }
    let by_emoji =
        |list: &Value, emoji: &str| list.as_array().unwrap().iter().find(|r| r["emoji"] == emoji).cloned().unwrap();

    // the Stars: both counted, the hidden one unnamed, the open one by display name
    let read = posts::one(&w.c, &a, &post).unwrap().unwrap();
    assert_eq!(read["reactions"].as_array().unwrap().len(), 2);
    assert_eq!(by_emoji(&read["reactions"], "🌙"), json!({"emoji": "🌙", "member_id": null, "member_name": null}));
    assert_eq!(by_emoji(&read["reactions"], "🌿"), json!({"emoji": "🌿", "member_id": open, "member_name": "Rin"}));
    let activity = posts::activity(&w.c, &a, None, 50).unwrap();
    assert_eq!(by_emoji(&activity["reactions"][&post], "🌙")["member_id"], Value::Null);
    assert_eq!(by_emoji(&activity["reactions"][&post], "🌿")["member_name"], "Rin");
    let items: Vec<&Value> = activity["items"].as_array().unwrap().iter().filter(|i| i["kind"] == "reaction").collect();
    assert_eq!(items.len(), 2, "a hidden member's reaction still shows");
    let moon = items.iter().find(|i| i["emoji"] == "🌙").unwrap();
    assert_eq!((&moon["member_id"], &moon["member_name"]), (&Value::Null, &Value::Null));
    let leaf = items.iter().find(|i| i["emoji"] == "🌿").unwrap();
    assert_eq!((&leaf["member_id"], &leaf["member_name"]), (&json!(open), &json!("Rin")));

    // Alex's account sees its own members complete
    let own = posts::one(&w.c, &b, &post).unwrap().unwrap();
    assert_eq!(
        by_emoji(&own["reactions"], "🌙"),
        json!({"emoji": "🌙", "member_id": hidden, "member_name": "June Hidden"})
    );
    assert_eq!(by_emoji(&own["reactions"], "🌿")["member_name"], "Rin");
}

/// D-155: a private chat (D-098) is a DM's. A channel kept to some members in a shared space
/// is an ordinary channel: anyone speaks there, the front hides nothing, and it notifies under
/// the ordinary rules; the same channel in a DM still is private.
#[test]
fn only_a_dm_has_private_chats() {
    for kind in ["shared", "dm"] {
        let dm = kind == "dm";
        let mut w = W::with_space(kind);
        let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
        let space_id = space.strip_prefix("space:").unwrap().to_string();
        let kai = new_id(NOW as u64, [40; 10]);
        let june = new_id(NOW as u64, [41; 10]);
        let rin = new_id(NOW as u64, [42; 10]);
        w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
        w.push(&a, "member.create", &format!("account:{a}"), &june, json!({"name": "June"}));
        w.push(&b, "member.create", &format!("account:{b}"), &rin, json!({"name": "Rin"}));
        let chat = new_id(NOW as u64, [30; 10]);
        w.push(
            &a,
            "channel.create",
            &space,
            &chat,
            json!({"space_id": space_id, "kind": "member_dm", "name": "Kai", "member_ids": [kai, rin]}),
        );
        // titled before anyone writes, in a DM only
        let cards = spaces::authors(&w.c, &b, &space_id).unwrap();
        let carded = cards["members"].as_array().unwrap().iter().any(|m| m["id"] == kai.as_str());
        assert_eq!(carded, dm, "{kind}: kept-to members' cards");

        // June isn't listed: refused in a DM, an ordinary speaker in a shared space
        let mut said = msg("June here", &[&june], json!([]));
        said["channel_id"] = json!(chat);
        let refused = w.try_push(&a, "message.send", &space, &new_id(NOW as u64, [50; 10]), said);
        assert_eq!(refused.is_some(), dm, "{kind}: {refused:?}");

        // Kai mentions Rin (who isn't fronting): a DM's private chat follows Rin's DM rule (only
        // while fronting), a shared space's channel is a mention like anywhere else
        let m = new_id(NOW as u64, [51; 10]);
        let mut said = msg(
            "@Rin hi",
            &[&kai],
            json!([{"type": "mention", "offset": 0, "length": 4, "target_type": "member", "target_id": rin}]),
        );
        said["channel_id"] = json!(chat);
        w.push(&a, "message.send", &space, &m, said);
        let inbox = w.inbox(&b);
        assert_eq!(inbox.is_empty(), dm, "{kind}: {inbox:?}");
        if !dm {
            assert_eq!(inbox[0], ("mention".to_string(), "@Rin hi".to_string()));
        }
        // and search hides it by Rin's front only in a DM
        let found = search::message_by_id(&w.c, &api_data::Principal::owner(&b), &m).unwrap();
        assert_eq!(found.is_none(), dm, "{kind}: search");
    }
}
