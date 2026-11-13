//! Read receipts across accounts (D-131, NOTIFICATIONS.md §5.9, `receipts.rs`): another account's
//! read shows up as "read up to your message X" only when both accounts opted in, only for what
//! the reader could see, and only for reading done after both switched it on. Marks of our own
//! members never reach another account.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::reading::Mark;
use chorus_core::ticks::{Msg, Tick, ticks_for};
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::api_data::Principal;
use chorus_server::{db, ingest, receipts, visibility};
use rusqlite::Connection;
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;
const GENERAL: &str = "0192f8c2-0000-7000-8000-0000000000e1";
const DENY_VIEW: &str = "INSERT INTO channel_permission(channel_id,target_type,target_id,deny,hlc) VALUES (?1,'account',?2,'[\"view\"]','a')";

struct W {
    c: Connection,
    a: String,
    b: String,
    space: String,
    n: u8,
    kai: String,
    june: String,
}

impl W {
    fn new() -> W {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        let (a, b) = (new_id(1, [1; 10]), new_id(1, [2; 10]));
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
        let (kai, june) = (new_id(1, [4; 10]), new_id(1, [5; 10]));
        let mut w =
            W { c, a: a.clone(), b: b.clone(), space: space.clone(), n: 0, kai: kai.clone(), june: june.clone() };
        let id = space.strip_prefix("space:").unwrap().to_string();
        w.push(&a, "space.create", &space, &id, json!({"kind": "shared", "name": "Club"}));
        for who in [&a, &b] {
            w.push(&a, "space.join", &space, &id, json!({"account_id": who}));
        }
        w.push(&a, "channel.create", &space, GENERAL, json!({"space_id": id, "kind": "text", "name": "general"}));
        w.push(&a, "member.create", &format!("account:{a}"), &kai, json!({"name": "Kai"}));
        w.push(&b, "member.create", &format!("account:{b}"), &june, json!({"name": "June"}));
        w
    }

    fn push(&mut self, who: &str, kind: &str, scope: &str, entity: &str, payload: Value) -> Op {
        self.n += 1;
        let at = NOW + i64::from(self.n) * 1000;
        let o = Op {
            id: new_id(at as u64, [self.n; 10]),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: (!entity.is_empty()).then(|| entity.to_string()),
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
        let (r, new) = ingest::accept(&self.c, &s, o, at, false).unwrap();
        assert_eq!(r.error.map(|e| e.message), None, "{kind}");
        new.expect("op accepted").op
    }

    fn share(&mut self, who: &str, on: bool) {
        let id = new_id(NOW as u64, [self.n.wrapping_add(100); 10]);
        self.push(
            who,
            "pref.set",
            &format!("account:{who}"),
            &id,
            json!({"device": "", "key": "share_reads", "value": on}),
        );
    }

    /// `who` writes a message as their member; `aside` makes it a private aside.
    fn send(&mut self, who: &str, text: &str, aside: bool) -> (String, i64) {
        let id = new_id(NOW as u64, [self.n.wrapping_add(150); 10]);
        let member = if who == self.a { self.kai.clone() } else { self.june.clone() };
        let mut p = json!({"channel_id": GENERAL, "authors": [member], "text": text, "entities": []});
        if aside {
            p["visibility"] = json!({"mode": "system_only"});
        }
        let space = self.space.clone();
        self.push(who, "message.send", &space, &id, p);
        let at = self.c.query_row("SELECT occurred_at FROM message WHERE id=?1", [&id], |r| r.get(0)).unwrap();
        (id, at)
    }

    /// `who` reads up to `message`, as the account (`""`) or as a member.
    fn read(&mut self, who: &str, message: &(String, i64), member: &str) -> Op {
        let space = self.space.clone();
        self.push(
            who,
            "read.mark",
            &space,
            "",
            json!({"channel_id": GENERAL, "message_id": message.0, "message_at": message.1, "reader_member_id": member}),
        )
    }

    /// What `GET /channels/{id}/reads` answers `who`: the reader and the message named.
    fn reads(&self, who: &str) -> Vec<(String, String)> {
        let v = receipts::reads(&self.c, &Principal::owner(who), GENERAL).unwrap().expect("viewable");
        v["reads"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| (r["account_id"].as_str().unwrap().into(), r["message_id"].as_str().unwrap().into()))
            .collect()
    }
}

#[test]
fn own_members_reading_needs_no_setting_and_never_leaves_the_account() {
    // (i) a member of our own system reading makes two ticks with share_reads off on both sides
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    let rin = new_id(1, [6; 10]);
    w.push(&a, "member.create", &format!("account:{a}"), &rin, json!({"name": "Rin"}));
    let m = w.send(&a, "hello", false);
    let op = w.read(&a, &m, &rin);
    let marks: Vec<Mark> = {
        let mut st =
            w.c.prepare(
                "SELECT reader_member_id, last_read_message_at, last_read_message_id FROM read_state
                 WHERE account_id=?1 AND reader_member_id<>''",
            )
            .unwrap();
        st.query_map([&a], |r| Ok(Mark { member: r.get(0)?, at: r.get(1)?, id: r.get(2)? }))
            .unwrap()
            .map(Result::unwrap)
            .collect()
    };
    let msg = |author: &String| Msg { at: m.1, id: m.0.clone(), authors: vec![author.clone()], acked: true };
    assert_eq!(ticks_for(&[msg(&w.kai)], &marks, &[]), vec![Tick::Read], "Rin read Kai's message");
    assert_eq!(ticks_for(&[msg(&rin)], &marks, &[]), vec![Tick::Saved], "nobody's own reading is a receipt");
    // the mark reaches our own devices, never the other account
    assert!(visibility::op_visible_to(&w.c, &a, &op).unwrap());
    assert!(!visibility::op_visible_to(&w.c, &b, &op).unwrap(), "read marks stay account-private");
    // and with both accounts opted in it still is not a cross-account receipt
    w.share(&a, true);
    w.share(&b, true);
    let m2 = w.send(&a, "again", false);
    w.read(&a, &m2, &rin);
    assert!(w.reads(&a).is_empty(), "own members are not a receipt");
    assert!(w.reads(&b).is_empty());
}

#[test]
fn another_accounts_read_is_invisible_unless_both_opted_in() {
    // (ii)
    for (on_a, on_b) in [(false, false), (true, false), (false, true)] {
        let mut w = W::new();
        let (a, b) = (w.a.clone(), w.b.clone());
        if on_a {
            w.share(&a, true);
        }
        if on_b {
            w.share(&b, true);
        }
        let m = w.send(&a, "hi", false);
        w.read(&b, &m, "");
        assert!(w.reads(&a).is_empty(), "a={on_a} b={on_b}");
        assert!(w.reads(&b).is_empty(), "a={on_a} b={on_b}");
    }
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    w.share(&a, true);
    w.share(&b, true);
    let m = w.send(&a, "hi", false);
    w.read(&b, &m, "");
    assert_eq!(w.reads(&a), vec![(b.clone(), m.0.clone())]);
    assert!(w.reads(&b).is_empty(), "Alex wrote nothing for Stars to have read");
}

#[test]
fn turning_it_off_on_either_side_hides_it_again_and_earlier_reads_stay_hidden() {
    // (iii) reads made while it was on, and reads made while it was off
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    w.share(&a, true);
    w.share(&b, true);
    let m = w.send(&a, "hi", false);
    w.read(&b, &m, "");
    assert_eq!(w.reads(&a).len(), 1);
    w.share(&b, false);
    assert!(w.reads(&a).is_empty(), "the reader turned it off");
    w.share(&b, true);
    assert!(w.reads(&a).is_empty(), "what was read before it came back on is not shown");
    let m2 = w.send(&a, "more", false);
    w.read(&b, &m2, "");
    assert_eq!(w.reads(&a), vec![(b.clone(), m2.0.clone())], "new reading is");
    w.share(&a, false);
    assert!(w.reads(&a).is_empty(), "the asker turned it off");
    w.share(&a, true);
    assert!(w.reads(&a).is_empty(), "the asker's later switch-on doesn't reveal earlier reading either");
    let m3 = w.send(&a, "last", false);
    w.read(&b, &m3, "");
    assert_eq!(w.reads(&a).len(), 1);
}

#[test]
fn reading_done_before_the_switches_were_on_is_not_revealed_by_them() {
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    let m = w.send(&a, "hi", false);
    w.read(&b, &m, "");
    w.share(&a, true);
    w.share(&b, true);
    assert!(w.reads(&a).is_empty());
}

#[test]
fn a_receipt_names_only_the_askers_message_the_reader_could_see() {
    // (iv) the clamp: never the reader's mark, an aside, a deleted message or someone else's
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    w.share(&a, true);
    w.share(&b, true);
    let first = w.send(&a, "first", false);
    w.send(&b, "reply", false);
    w.send(&a, "private aside", true);
    let deleted = w.send(&a, "oops", false);
    let space = w.space.clone();
    w.push(&a, "message.delete", &space, &deleted.0, json!({"message_id": deleted.0}));
    let last = w.send(&b, "the reader's own latest", false);
    w.read(&b, &last, "");
    assert_eq!(w.reads(&a), vec![(b.clone(), first.0.clone())], "not the aside, not the deleted one, not theirs");
    assert!(w.reads(&b).is_empty(), "Alex has no receipt for Alex's own messages");

    // a mark that has only reached their own messages names nothing
    let mut w = W::new();
    w.share(&a, true);
    w.share(&b, true);
    let early = w.send(&b, "earlier", false);
    w.send(&a, "later", false);
    w.read(&b, &early, "");
    assert!(w.reads(&a).is_empty());
}

#[test]
fn member_rows_never_make_a_receipt() {
    let mut w = W::new();
    let (a, b) = (w.a.clone(), w.b.clone());
    w.share(&a, true);
    w.share(&b, true);
    let m = w.send(&a, "hi", false);
    let june = w.june.clone();
    let op = w.read(&b, &m, &june);
    assert!(w.reads(&a).is_empty(), "June's own mark would say who was fronting");
    assert!(!visibility::op_visible_to(&w.c, &a, &op).unwrap());
    w.read(&b, &m, "");
    assert_eq!(w.reads(&a).len(), 1, "the account row does");
}

#[test]
fn nothing_flows_in_an_internal_space_or_to_or_from_someone_who_cannot_see_the_channel() {
    for case in ["internal", "no kind", "reader lost the space", "reader denied", "asker lost it"] {
        let mut w = W::new();
        let (a, b) = (w.a.clone(), w.b.clone());
        w.share(&a, true);
        w.share(&b, true);
        let m = w.send(&a, "hi", false);
        w.read(&b, &m, "");
        // the situation changes after the reading happened: receipts check it again on every ask
        match case {
            "internal" => {
                w.c.execute("UPDATE space SET kind='internal'", []).unwrap();
            }
            "no kind" => {
                w.c.execute("UPDATE space SET kind=NULL", []).unwrap();
            }
            "reader lost the space" => {
                w.c.execute("DELETE FROM scope_access WHERE account_id=?1 AND scope=?2", [&b, &w.space]).unwrap();
            }
            "reader denied" => {
                w.c.execute(DENY_VIEW, [GENERAL, &b]).unwrap();
            }
            _ => {
                w.c.execute("DELETE FROM scope_access WHERE account_id=?1 AND scope=?2", [&a, &w.space]).unwrap();
            }
        }
        if case == "asker lost it" {
            assert!(receipts::reads(&w.c, &Principal::owner(&a), GENERAL).unwrap().is_none(), "{case}");
        } else {
            assert!(w.reads(&a).is_empty(), "{case}");
        }
    }
    // an asker who cannot view the channel gets nothing, not even an empty list
    let w = W::new();
    let a = w.b.clone();
    w.c.execute(DENY_VIEW, [GENERAL, &a]).unwrap();
    assert!(receipts::reads(&w.c, &Principal::owner(&a), GENERAL).unwrap().is_none());
    assert!(receipts::reads(&w.c, &Principal::owner(&a), "no-such-channel").unwrap().is_none());
}

#[test]
fn no_other_path_carries_a_receipt() {
    // grep-style, like typing: the frame is built in one place and only goes to a socket, and the
    // event stream, webhooks, exports, push and notifications never mention receipts
    let src = |p: &str| std::fs::read_to_string(format!("{}/src/{p}", env!("CARGO_MANIFEST_DIR"))).unwrap();
    for file in [
        "webhooks.rs",
        "exports.rs",
        "export_job.rs",
        "api_data.rs",
        "feeds.rs",
        "backup.rs",
        "push.rs",
        "notifier.rs",
        "oplog.rs",
    ] {
        let s = src(file);
        assert!(!s.contains("receipts::") && !s.contains("share_reads"), "{file}");
    }
    assert_eq!(src("app.rs").matches("Outgoing::Read(").count(), 0, "only app/reads.rs builds the frame");
    assert_eq!(src("app/reads.rs").matches("Outgoing::Read(").count(), 1);
    assert!(!src("app/reads.rs").contains("events.send"), "the live event stream carries no receipt");
}
