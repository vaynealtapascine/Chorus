//! Activity notifications across accounts (NOTIFICATIONS.md §7): a mention of your member or a
//! reply to your message in a shared space notifies you; your own messages never do; messages
//! with restricted visibility notify nobody.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, ingest, notifier};
use rusqlite::Connection;
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;

struct W {
    c: Connection,
    a: String,
    b: String,
    space: String,
    n: u8,
    last_op: String,
}

impl W {
    fn new() -> W {
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
        W { c, a, b, space, n: 0, last_op: String::new() }
    }

    fn push(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) {
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
        assert!(r.error.is_none(), "{kind}: {:?}", r.error);
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
    json!({"channel_id": "general", "authors": authors, "text": text, "entities": entities})
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
    pref(&mut w, "notify_channel:general", json!("all"));
    send(&mut w, "chatter", json!([]));
    assert_eq!(w.inbox(&b)[0], ("message".to_string(), "chatter".to_string()));

    // "none": even a mention stays quiet
    pref(&mut w, "notify_channel:general", json!("none"));
    send(&mut w, "@June muted", mention.clone());
    assert_eq!(w.inbox(&b).len(), 1);

    // back to mentions, but mentions switched off as a kind
    pref(&mut w, "notify_channel:general", json!("mentions"));
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
