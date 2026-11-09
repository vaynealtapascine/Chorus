//! Polls (D-081, D-086, docs/POLLS.md): votes are checked against the parent's audience and
//! poll, totals are published as counts-only tally ops, and no voter identity ever reaches
//! another account (sync scopes, REST views or exports).

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::sync::AckError;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, exports, ingest};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;
const GENERAL: &str = "0192f8c2-0000-7000-8000-0000000000e1";

struct W {
    c: Connection,
    a: String,
    b: String,
    space: String,
    n: u8,
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
        let mut w = W { c, a: a.clone(), b: b.clone(), space: space.clone(), n: 0 };
        let id = space.strip_prefix("space:").unwrap().to_string();
        w.ok(&a, "space.create", &space, &id, json!({"kind": "shared", "name": "Club"}));
        for who in [&a, &b] {
            w.ok(&a, "space.join", &space, &id, json!({"account_id": who}));
        }
        w.ok(&a, "channel.create", &space, GENERAL, json!({"space_id": id, "kind": "text", "name": "general"}));
        w
    }

    fn push(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) -> Option<AckError> {
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
        ingest::accept(&self.c, &s, o, at, false).unwrap().0.error
    }

    fn ok(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) {
        assert_eq!(self.push(who, kind, scope, entity, payload), None, "{kind}");
    }

    fn member(&mut self, who: &str, n: u8, name: &str) -> String {
        let id = new_id(NOW as u64, [n; 10]);
        self.ok(who, "member.create", &format!("account:{who}"), &id, json!({"name": name}));
        id
    }

    fn vote(&mut self, who: &str, poll: &str, member: &str, choices: Value) -> Option<AckError> {
        self.push(who, "poll.vote", &format!("account:{who}"), poll, json!({"member_id": member, "choices": choices}))
    }

    fn voted(&mut self, who: &str, poll: &str, member: &str, choices: Value) {
        assert_eq!(self.vote(who, poll, member, choices), None);
    }

    /// The latest published totals, read the way every client projects them.
    fn tally(&self, poll: &str) -> Value {
        self.c
            .query_row(
                "SELECT json_object('counts', json(counts), 'voters', voters) FROM poll_tally WHERE poll_id = ?1",
                [poll],
                |r| r.get::<_, String>(0),
            )
            .map(|s| serde_json::from_str(&s).unwrap())
            .unwrap_or(Value::Null)
    }

    /// Every op payload a device of `account` could receive outside its own account scope.
    fn others_see(&self, account: &str) -> String {
        let mut st = self
            .c
            .prepare(
                "SELECT o.payload || o.account_id FROM op o WHERE o.scope != 'account:' || ?1
                   AND (o.scope LIKE 'space:%' AND EXISTS (SELECT 1 FROM scope_access sa WHERE sa.account_id = ?1 AND sa.scope = o.scope))",
            )
            .unwrap();
        st.query_map([account], |r| r.get::<_, String>(0)).unwrap().collect::<Result<Vec<_>, _>>().unwrap().join("\n")
    }
}

impl W {
    fn nudge(&mut self, who: &str, poll: &str, members: Value, by: Option<&str>) -> Option<AckError> {
        let mut payload = json!({"member_ids": members});
        if let Some(by) = by {
            payload["by_member_id"] = json!(by);
        }
        self.push(who, "poll.nudge", &format!("account:{who}"), poll, payload)
    }

    /// The nudge notifications queued for `account`.
    fn nudges(&self, account: &str) -> Vec<Value> {
        let mut st = self
            .c
            .prepare("SELECT payload FROM notification WHERE recipient_account_id = ?1 AND kind = 'poll_nudge' ORDER BY created_at")
            .unwrap();
        st.query_map([account], |r| r.get::<_, String>(0))
            .unwrap()
            .map(|s| serde_json::from_str(&s.unwrap()).unwrap())
            .collect()
    }

    fn message_poll(&mut self, author: &str, by: &str, id: &str) {
        let space = self.space.clone();
        let poll: Value = serde_json::from_str(POLL).unwrap();
        self.ok(
            author,
            "message.send",
            &space,
            id,
            json!({"channel_id": GENERAL, "authors": [by], "text": "What about dinner?", "entities": [], "poll": poll}),
        );
    }
}

const POLL: &str = r#"{"question": "Dinner?", "options": [{"id": "a", "text": "Soup"}, {"id": "b", "text": "Rice"}]}"#;

#[test]
fn message_polls_count_votes_without_naming_voters() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = w.member(&a, 40, "Kai");
    let june = w.member(&b, 41, "June");
    let rin = w.member(&b, 42, "Rin");
    let msg = new_id(NOW as u64, [50; 10]);
    let poll: Value = serde_json::from_str(POLL).unwrap();
    w.ok(
        &a,
        "message.send",
        &space,
        &msg,
        json!({"channel_id": GENERAL, "authors": [kai], "text": "Dinner?", "entities": [], "poll": poll}),
    );

    // each member votes once; Bob's members vote privately in Bob's account
    w.voted(&b, &msg, &june, json!(["a"]));
    assert_eq!(w.tally(&msg), json!({"counts": {"a": 1, "b": 0}, "voters": 1}));
    w.voted(&b, &msg, &rin, json!(["b"]));
    w.voted(&a, &msg, &kai, json!(["b"]));
    assert_eq!(w.tally(&msg), json!({"counts": {"a": 1, "b": 2}, "voters": 3}));
    // a vote is editable and withdrawable
    w.voted(&b, &msg, &june, json!(["b"]));
    w.voted(&b, &msg, &rin, json!([]));
    assert_eq!(w.tally(&msg), json!({"counts": {"a": 0, "b": 2}, "voters": 2}));

    // refused: single choice, someone else's member, an unknown option
    assert_eq!(w.vote(&b, &msg, &june, json!(["a", "b"])).unwrap().code, "forbidden");
    assert_eq!(w.vote(&b, &msg, &kai, json!(["a"])).unwrap().code, "forbidden");
    assert_eq!(w.vote(&b, &msg, &june, json!(["z"])).unwrap().code, "forbidden");
    // a poll the server hasn't seen: held and sent again later, never counted early
    let early = w.vote(&b, &new_id(NOW as u64, [60; 10]), &june, json!(["a"])).unwrap();
    assert!(early.retry && early.retry_after_ms.is_some(), "{early:?}");

    // the tally ops each account receives carry counts only: nobody else's member or account
    let alice_sees = w.others_see(&a);
    assert!(alice_sees.contains("\"voters\""), "Alice gets the totals");
    for id in [&june, &rin] {
        assert!(!alice_sees.contains(id.as_str()), "a voter leaked to the other account");
    }
    let votes_outside: i64 = w
        .c
        .query_row("SELECT count(*) FROM op WHERE kind = 'poll.vote' AND scope != 'account:' || account_id", [], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(votes_outside, 0, "votes live in the voter's own account scope");

    // deleting the message stops counting; the published totals follow
    w.ok(&a, "message.delete", &space, &msg, json!({}));
    assert_eq!(w.tally(&msg)["voters"], 0);
}

#[test]
fn post_polls_follow_the_post_audience_and_stay_out_of_exports() {
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    let kai = w.member(&a, 40, "Kai");
    let june = w.member(&b, 41, "June");
    let post = new_id(NOW as u64, [70; 10]);
    let poll: Value = serde_json::from_str(POLL).unwrap();
    let scope = format!("account:{a}");
    w.ok(
        &a,
        "post.create",
        &scope,
        &post,
        json!({"kind": "note", "authors": [kai], "text": "Dinner?", "entities": [],
        "visibility": {"mode": "followers"}, "poll": poll}),
    );
    // not a follower yet: it can't read the post, so it can't vote
    assert_eq!(w.vote(&b, &post, &june, json!(["a"])).unwrap().code, "forbidden");
    w.c.execute(
        "INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at) VALUES ('f',?1,?2,'active',0)",
        params![b, a],
    )
    .unwrap();
    w.voted(&b, &post, &june, json!(["a"]));
    assert_eq!(w.tally(&post), json!({"counts": {"a": 1, "b": 0}, "voters": 1}));

    // the follower's REST view of the post has the totals, never who voted
    let items = chorus_server::posts::list(&w.c, &b, &Default::default()).unwrap();
    let item = items["items"].as_array().unwrap().iter().find(|i| i["id"] == json!(post)).unwrap().clone();
    assert_eq!(item["poll_tally"], json!({"counts": {"a": 1, "b": 0}, "voters": 1}));
    assert_eq!(item["poll"]["options"][1]["text"], "Rice");
    // the author's account receives the tally op in its own scope, with no voter in it
    let author_scope: String =
        w.c.query_row(
            "SELECT group_concat(payload || account_id) FROM op WHERE scope = ?1 AND kind = 'post.poll_tally'",
            [&scope],
            |r| r.get(0),
        )
        .unwrap();
    assert!(!author_scope.contains(june.as_str()) && !author_scope.contains(b.as_str()), "{author_scope}");

    // exports hold what the account wrote, not the server's derived totals
    let ops = String::from_utf8(exports::ops_jsonl(&w.c, &a).unwrap()).unwrap();
    assert!(ops.contains("post.create") && !ops.contains("poll_tally"));
    let bob_ops = String::from_utf8(exports::ops_jsonl(&w.c, &b).unwrap()).unwrap();
    assert!(bob_ops.contains("poll.vote"), "a system's own votes are its data");

    // narrowing the audience drops a vote that can no longer read it
    w.ok(&a, "post.set_visibility", &scope, &post, json!({"visibility": {"mode": "private"}}));
    assert_eq!(w.tally(&post)["voters"], 0);
}

#[test]
fn tallies_outlive_the_parent_authors_scope_access() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = w.member(&a, 40, "Kai");
    let june = w.member(&b, 41, "June");
    let msg = new_id(NOW as u64, [80; 10]);
    w.ok(&a, "message.send", &space, &msg, json!({"channel_id":GENERAL,"authors":[kai],"text":"Dinner?","poll":serde_json::from_str::<Value>(POLL).unwrap()}));
    w.voted(&b, &msg, &june, json!(["a"]));
    // The author's access can disappear while other readers still vote.
    w.c.execute("DELETE FROM scope_access WHERE account_id=?1 AND scope=?2", params![a, space]).unwrap();
    w.voted(&b, &msg, &june, json!(["b"]));
    assert_eq!(w.tally(&msg), json!({"counts":{"a":0,"b":1},"voters":1}));
    let tally = chorus_server::oplog::for_entity(&w.c, &msg)
        .unwrap()
        .into_iter()
        .rfind(|o| o.kind == "message.poll_tally")
        .unwrap();
    assert_eq!(tally.account_id.as_deref(), Some(a.as_str()));
    assert_eq!(tally.scope, space);
    assert_eq!(tally.payload["channel_id"], GENERAL);
    assert_eq!(w.push(&b, "message.poll_tally", &space, &msg, tally.payload.clone()).unwrap().code, "forbidden");
}

#[test]
fn server_writes_retain_tallies_and_projection_failures_roll_back_the_whole_bundle() {
    let mut w = W::new();
    let a = w.a.clone();
    let scope = format!("account:{a}");
    let kai = w.member(&a, 40, "Kai");
    let post = new_id(NOW as u64, [81; 10]);
    w.ok(
        &a,
        "post.create",
        &scope,
        &post,
        json!({"kind":"note","authors":[kai],"text":"Dinner?","poll":serde_json::from_str::<Value>(POLL).unwrap()}),
    );
    w.voted(&a, &post, &kai, json!(["a"]));
    let before = chorus_server::oplog::count(&w.c).unwrap();
    // Failure of a derived projection refuses the input and leaves neither log nor vote changed.
    w.c.execute_batch(
        "CREATE TRIGGER fail_tally BEFORE UPDATE ON poll_tally BEGIN SELECT RAISE(ABORT, 'test tally failure'); END",
    )
    .unwrap();
    assert_eq!(w.vote(&a, &post, &kai, json!(["b"])).unwrap().code, "unprocessable");
    assert_eq!(chorus_server::oplog::count(&w.c).unwrap(), before);
    assert_eq!(w.tally(&post)["counts"]["a"], 1);
    w.c.execute_batch("DROP TRIGGER fail_tally").unwrap();
    let accepted = ingest::server_op(&w.c, &a, "post.delete", &scope, Some(&post), json!({}), NOW + 100_000).unwrap();
    assert_eq!(accepted.derived.len(), 1);
    assert_eq!(accepted.derived[0].kind, "post.poll_tally");
    assert_eq!(accepted.derived[0].payload["voters"], 0);
    assert!(accepted.seq < accepted.derived[0].seq);
}

/// The W clock: each push happens 1 s after the last one.
fn next_at(w: &W) -> i64 {
    NOW + i64::from(w.n + 1) * 1000
}

#[test]
fn votes_after_the_closing_time_are_refused_and_the_tally_is_final() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = w.member(&a, 40, "Kai");
    let june = w.member(&b, 41, "June");
    let rin = w.member(&b, 42, "Rin");
    let msg = new_id(NOW as u64, [90; 10]);
    let mut poll: Value = serde_json::from_str(POLL).unwrap();
    // closes after two more ops (the send is the first)
    poll["closes_at"] = json!(next_at(&w) + 2_500);
    w.ok(&a, "message.send", &space, &msg, json!({"channel_id": GENERAL, "authors": [kai], "text": "?", "poll": poll}));
    w.voted(&b, &msg, &june, json!(["a"]));
    w.voted(&b, &msg, &rin, json!(["b"]));
    // the server's clock has passed closes_at: refused, not held
    let late = w.vote(&b, &msg, &june, json!(["b"])).unwrap();
    assert_eq!((late.code.as_str(), late.retry), ("forbidden", false), "{late:?}");
    assert!(late.message.contains("closed"));
    assert_eq!(w.tally(&msg), json!({"counts": {"a": 1, "b": 1}, "voters": 2}));
    // final: deleting the message after the close publishes nothing new
    w.ok(&a, "message.delete", &space, &msg, json!({}));
    assert_eq!(w.tally(&msg)["voters"], 2);
    // a closing time can't be added or moved by an edit
    assert_eq!(
        w.push(&a, "message.edit", &space, &msg, json!({"text": "?", "poll": poll})).unwrap().code,
        "bad_request"
    );
}

#[test]
fn only_the_author_closes_a_poll_early_where_it_was_posted() {
    let mut w = W::new();
    let (a, b, space) = (w.a.clone(), w.b.clone(), w.space.clone());
    let kai = w.member(&a, 40, "Kai");
    let june = w.member(&b, 41, "June");
    let post = new_id(NOW as u64, [91; 10]);
    let scope = format!("account:{a}");
    w.ok(
        &a,
        "post.create",
        &scope,
        &post,
        json!({"kind": "note", "authors": [kai], "text": "?", "visibility": {"mode": "followers"},
        "poll": serde_json::from_str::<Value>(POLL).unwrap()}),
    );
    w.c.execute(
        "INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at) VALUES ('f',?1,?2,'active',0)",
        params![b, a],
    )
    .unwrap();
    w.voted(&b, &post, &june, json!(["a"]));
    // another account can't close it, in its own scope or anywhere else
    assert_eq!(w.push(&b, "post.poll_close", &format!("account:{b}"), &post, json!({})).unwrap().code, "forbidden");
    assert_eq!(w.push(&b, "post.poll_close", &scope, &post, json!({})).unwrap().code, "forbidden");
    // a close for a poll the server hasn't seen waits for it
    let early = w.push(&a, "post.poll_close", &scope, &new_id(NOW as u64, [92; 10]), json!({})).unwrap();
    assert!(early.retry, "{early:?}");
    // the author closes it: votes stop, the tally is final, and followers see the close
    w.ok(&a, "post.poll_close", &scope, &post, json!({}));
    assert_eq!(w.vote(&b, &post, &june, json!(["b"])).unwrap().code, "forbidden");
    w.ok(&a, "post.set_visibility", &scope, &post, json!({"visibility": {"mode": "private"}}));
    assert_eq!(w.tally(&post), json!({"counts": {"a": 1, "b": 0}, "voters": 1}), "final after the close");
    let closed_at: i64 =
        w.c.query_row("SELECT closed_at FROM poll_close WHERE poll_id = ?1", [&post], |r| r.get(0)).unwrap();
    // closing again later keeps the first close
    w.ok(&a, "post.poll_close", &scope, &post, json!({}));
    let again: i64 =
        w.c.query_row("SELECT closed_at FROM poll_close WHERE poll_id = ?1", [&post], |r| r.get(0)).unwrap();
    assert_eq!(again, closed_at);
    let mine = chorus_server::posts::list(&w.c, &a, &Default::default()).unwrap();
    let item = mine["items"].as_array().unwrap().iter().find(|i| i["id"] == json!(post)).unwrap().clone();
    assert_eq!(item["poll_closed_at"], json!(closed_at));

    // a message's close carries its channel, in its space
    let msg = new_id(NOW as u64, [93; 10]);
    w.ok(
        &a,
        "message.send",
        &space,
        &msg,
        json!({"channel_id": GENERAL, "authors": [kai], "text": "?", "poll": serde_json::from_str::<Value>(POLL).unwrap()}),
    );
    assert_eq!(
        w.push(&b, "message.poll_close", &space, &msg, json!({"channel_id": GENERAL})).unwrap().code,
        "forbidden"
    );
    assert_eq!(
        w.push(&a, "message.poll_close", &space, &msg, json!({"channel_id": "other"})).unwrap().code,
        "forbidden"
    );
    assert_eq!(w.push(&a, "message.poll_close", &space, &msg, json!({})).unwrap().code, "bad_request");
    w.ok(&a, "message.poll_close", &space, &msg, json!({"channel_id": GENERAL}));
    assert_eq!(w.vote(&b, &msg, &june, json!(["a"])).unwrap().code, "forbidden");
}

#[test]
fn a_nudge_tells_our_own_members_who_have_not_voted_and_nobody_else() {
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    let kai = w.member(&a, 40, "Kai");
    let rin = w.member(&a, 41, "Rin");
    let ash = w.member(&a, 42, "Ash");
    let june = w.member(&b, 43, "June");
    let msg = new_id(NOW as u64, [50; 10]);
    w.message_poll(&a, &kai, &msg);
    w.voted(&a, &msg, &ash, json!(["a"]));

    // Ash has voted, so the nudge rings only for Rin, and not on the device that sent it
    assert_eq!(w.nudge(&a, &msg, json!([rin, ash]), Some(&kai)), None);
    let sent = w.nudges(&a);
    assert_eq!(sent.len(), 1);
    assert_eq!(sent[0]["title"], "Kai nudged Rin");
    assert_eq!(sent[0]["text"], "Dinner?");
    assert_eq!(sent[0]["t"], "poll");
    assert_eq!(sent[0]["message_id"], json!(msg));
    assert_eq!(sent[0]["channel_id"], GENERAL);
    assert_eq!(sent[0]["from_device"], "d");

    // when everyone named has voted nothing rings, though the nudge is recorded
    w.c.execute("UPDATE poll_nudge SET nudged_at = 0", []).unwrap();
    assert_eq!(w.nudge(&a, &msg, json!([ash]), None), None);
    assert_eq!(w.nudges(&a).len(), 1);

    // refused: another account's member, as either end of the nudge
    assert_eq!(w.nudge(&a, &msg, json!([june]), Some(&kai)).unwrap().code, "forbidden");
    assert_eq!(w.nudge(&a, &msg, json!([kai]), Some(&june)).unwrap().code, "forbidden");
    // an archived member can't be reminded
    w.ok(&a, "member.archive", &format!("account:{a}"), &kai, json!({}));
    assert_eq!(w.nudge(&a, &msg, json!([kai]), None).unwrap().code, "forbidden");
    // nothing about it reaches the other account
    assert!(w.nudges(&b).is_empty());
    assert!(!w.others_see(&b).contains("poll.nudge"));
    let outside: i64 = w
        .c
        .query_row("SELECT count(*) FROM op WHERE kind = 'poll.nudge' AND scope != 'account:' || account_id", [], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(outside, 0);
}

#[test]
fn nudges_wait_four_hours_per_member_and_thirty_an_hour() {
    let mut w = W::new();
    let a = w.a.clone();
    let kai = w.member(&a, 40, "Kai");
    let rin = w.member(&a, 41, "Rin");
    let msg = new_id(NOW as u64, [50; 10]);
    w.message_poll(&a, &kai, &msg);
    assert_eq!(w.nudge(&a, &msg, json!([rin]), None), None);
    let again = w.nudge(&a, &msg, json!([rin]), None).unwrap();
    assert_eq!(again.code, "nudge_cooldown");
    assert!(again.retry && again.retry_after_ms.unwrap() > 3 * 3_600_000, "{again:?}");
    // the same member on another poll is a different question
    let other = new_id(NOW as u64, [51; 10]);
    w.message_poll(&a, &kai, &other);
    assert_eq!(w.nudge(&a, &other, json!([rin]), None), None);

    // four hours on, it goes through
    w.c.execute("UPDATE poll_nudge SET nudged_at = nudged_at - ?1", [chorus_core::poll::NUDGE_COOLDOWN_MS]).unwrap();
    assert_eq!(w.nudge(&a, &msg, json!([rin]), None), None);

    // the account's hourly cap counts members, whatever the poll
    w.c.execute("UPDATE poll_nudge SET nudged_at = nudged_at - ?1", [chorus_core::poll::NUDGE_COOLDOWN_MS]).unwrap();
    let many: Vec<String> = (0..30).map(|i| w.member(&a, 100 + i, "Someone")).collect();
    assert_eq!(w.nudge(&a, &msg, json!(many), None), None);
    let over = w.nudge(&a, &other, json!([rin]), None).unwrap();
    assert_eq!(over.code, "rate_limited");
    assert!(over.retry && over.retry_after_ms.unwrap() <= 3_600_000, "{over:?}");
}

#[test]
fn nudges_follow_the_members_rules_and_stop_when_the_poll_closes() {
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    let scope = format!("account:{a}");
    let kai = w.member(&a, 40, "Kai");
    let rin = w.member(&a, 41, "Rin");
    let ash = w.member(&a, 42, "Ash");
    let june = w.member(&b, 43, "June");
    let msg = new_id(NOW as u64, [50; 10]);
    w.message_poll(&a, &kai, &msg);

    // a member's "never" for mentions also covers nudges; "fronting" needs them here
    for (n, member, rule) in [(60u8, &rin, "never"), (61, &ash, "fronting")] {
        let p = new_id(NOW as u64, [n; 10]);
        let key = format!("notify_member:{member}");
        w.ok(&a, "pref.set", &scope, &p, json!({"device": "", "key": key, "value": {"mentions": rule}}));
    }
    assert_eq!(w.nudge(&a, &msg, json!([rin, ash]), Some(&kai)), None);
    assert!(w.nudges(&a).is_empty());

    // nudges can be turned off altogether
    w.c.execute("UPDATE poll_nudge SET nudged_at = 0", []).unwrap();
    let p = new_id(NOW as u64, [62; 10]);
    w.ok(&a, "pref.set", &scope, &p, json!({"device": "", "key": "notify_chat", "value": {"poll_nudge": false}}));
    assert_eq!(w.nudge(&a, &msg, json!([kai]), None), None);
    assert!(w.nudges(&a).is_empty());

    // a poll the server hasn't seen waits for it; one the account can't read is refused
    let early = w.nudge(&a, &new_id(NOW as u64, [63; 10]), json!([kai]), None).unwrap();
    assert!(early.retry, "{early:?}");
    let private = new_id(NOW as u64, [64; 10]);
    let poll: Value = serde_json::from_str(POLL).unwrap();
    w.ok(
        &b,
        "post.create",
        &format!("account:{b}"),
        &private,
        json!({"authors": [june], "text": "?", "entities": [],
        "kind": "note", "visibility": {"mode": "private"}, "poll": poll}),
    );
    assert_eq!(w.nudge(&a, &private, json!([kai]), None).unwrap().code, "forbidden");

    // closed: no more nudges
    w.c.execute("UPDATE poll_nudge SET nudged_at = 0", []).unwrap();
    let space = w.space.clone();
    w.ok(&a, "message.poll_close", &space, &msg, json!({"channel_id": GENERAL}));
    assert_eq!(w.nudge(&a, &msg, json!([kai]), None).unwrap().code, "forbidden");
}
