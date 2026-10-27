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
