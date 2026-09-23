//! Follower notifications on the server (NOTIFICATIONS.md §2, §5): a follower learns nothing
//! before the reveal time, hidden members are never named, an undo before the reveal cancels it,
//! and bursts collapse into one notification.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, follows, ingest, notifier};
use rusqlite::Connection;
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;
const SETTLE: i64 = 15_000;
const DELAY: i64 = 60_000;

struct World {
    c: Connection,
    sys: String,
    friend: String,
    n: u8,
}

impl World {
    fn new() -> World {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        let sys = new_id(1, [1; 10]);
        let friend = new_id(1, [2; 10]);
        c.execute("INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'system', 'stars', 0)", [&sys])
            .unwrap();
        c.execute("INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'person', 'alex', 0)", [&friend])
            .unwrap();
        for a in [&sys, &friend] {
            ingest::grant(&c, a, &format!("account:{a}")).unwrap();
        }
        World { c, sys, friend, n: 0 }
    }

    /// Accept an op from the system's phone at `at` (device and server clocks agree).
    fn push(&mut self, kind: &str, entity: &str, payload: Value, at: i64) -> Op {
        self.n += 1;
        let o = Op {
            id: new_id(at as u64, [self.n; 10]),
            kind: kind.into(),
            v: 1,
            scope: format!("account:{}", self.sys),
            entity_id: Some(entity.into()),
            hlc: Hlc::new(at as u64, u16::from(self.n), 1),
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
            account_id: self.sys.clone(),
            device_id: "phone".into(),
            sample: ClockSample { server_time: at, mono: None, boot_id: None, offset_ms: 0 },
        };
        let (r, new) = ingest::accept(&self.c, &s, o, at, false).unwrap();
        assert!(r.error.is_none(), "{kind}: {:?}", r.error);
        new.unwrap()
    }

    fn member(&mut self, name: &str, policy: Value) -> String {
        let id = new_id(NOW as u64, [self.n.wrapping_add(50); 10]);
        self.push(
            "member.create",
            &id,
            json!({"name": name, "color": "#C0694E", "notify_policy": policy}),
            NOW - 10_000,
        );
        id
    }

    fn switch(&mut self, ids: &[&str], at: i64) -> String {
        let entries: Vec<Value> =
            ids.iter().map(|id| json!({"subject_type": "member", "subject_id": id, "level": "front"})).collect();
        let sw = new_id(at as u64, [self.n.wrapping_add(80); 10]);
        self.push("front.switch", &sw, json!({"entries": entries}), at).id
    }

    fn view(&self) -> Vec<String> {
        let v = notifier::follower_view(&self.c, &self.friend, &self.sys).unwrap().unwrap();
        v["entries"].as_array().unwrap().iter().map(|e| e["name"].as_str().unwrap().to_string()).collect()
    }

    fn inbox(&self) -> Vec<String> {
        let l = notifier::list(&self.c, &self.friend, 50).unwrap();
        l["items"].as_array().unwrap().iter().map(|i| i["text"].as_str().unwrap().to_string()).collect()
    }
}

fn world() -> (World, String, String, String) {
    let mut w = World::new();
    let kai = w.member("Kai", json!({"announce": "everyone"}));
    let june = w.member("June", json!({"announce": "everyone"}));
    let secret = w.member("Secret", json!({"announce": "nobody"}));
    let (follow, _) = follows::request(&w.c, &w.friend, "@stars", NOW - 5_000).unwrap();
    w.push("follow.accept", &follow, json!({}), NOW - 4_000);
    let ceiling = json!({"delay": {"min_s": DELAY / 1000, "max_s": DELAY / 1000}, "time": {"mode": "exact"}, "levels": ["front"]});
    w.push("follow.set_ceiling", &follow, json!({"ceiling": ceiling}), NOW - 3_000);
    (w, kai, june, secret)
}

#[test]
fn nothing_is_revealed_before_due_then_the_view_and_inbox_update_together() {
    let (mut w, kai, _, _) = world();
    w.switch(&[&kai], NOW);
    let due = NOW + SETTLE + DELAY;
    notifier::process_due(&w.c, due - 1).unwrap();
    assert!(w.view().is_empty(), "revealed early");
    assert!(w.inbox().is_empty());
    notifier::process_due(&w.c, due).unwrap();
    assert_eq!(w.view(), ["Kai"]);
    assert_eq!(w.inbox(), ["Kai is fronting"]);
    let v = notifier::follower_view(&w.c, &w.friend, &w.sys).unwrap().unwrap();
    assert_eq!(v["since"], NOW); // exact time rule, never after delivery
}

#[test]
fn follower_avatar_hash_is_only_exposed_after_reveal() {
    let (mut w, kai, _, _) = world();
    let hash = "a".repeat(64);
    w.push("member.set", &kai, json!({"avatar_blob": hash}), NOW - 1_000);
    w.switch(&[&kai], NOW);
    let before = notifier::follower_view(&w.c, &w.friend, &w.sys).unwrap().unwrap();
    assert!(before["entries"].as_array().unwrap().is_empty());
    notifier::process_due(&w.c, NOW + SETTLE + DELAY).unwrap();
    let after = notifier::follower_view(&w.c, &w.friend, &w.sys).unwrap().unwrap();
    assert_eq!(after["entries"][0]["avatar_blob"], hash);
}

#[test]
fn hidden_members_are_omitted_and_an_undo_cancels_whats_pending() {
    let (mut w, kai, june, secret) = world();
    w.switch(&[&kai], NOW);
    notifier::process_due(&w.c, NOW + SETTLE + DELAY).unwrap();

    // switch, then undo before the reveal: the follower never hears of it
    let t = NOW + 200_000;
    let sw = w.switch(&[&june, &secret], t);
    w.push("front.retract", &new_id(t as u64 + 1, [7; 10]), json!({"target_op_id": sw}), t + 5_000);
    notifier::process_due(&w.c, t + 10 * 60_000).unwrap();
    assert_eq!(w.view(), ["Kai"]);
    assert_eq!(w.inbox(), ["Kai is fronting"]);

    // for real this time: Secret is never named
    let t = NOW + 400_000;
    w.switch(&[&june, &secret], t);
    notifier::process_due(&w.c, t + SETTLE + DELAY).unwrap();
    assert_eq!(w.view(), ["June"]);
    assert_eq!(w.inbox()[0], "June is fronting");
    let all: String = serde_json::to_string(&notifier::list(&w.c, &w.friend, 50).unwrap()).unwrap();
    assert!(!all.contains("Secret"));
}

#[test]
fn a_burst_of_switches_collapses_into_the_latest_state() {
    let (mut w, kai, june, _) = world();
    w.switch(&[&kai], NOW);
    w.switch(&[&june], NOW + 20_000);
    w.switch(&[&kai, &june], NOW + 40_000);
    notifier::process_due(&w.c, NOW + 40_000 + SETTLE + DELAY).unwrap();
    assert_eq!(w.inbox(), ["Kai & June are fronting"]);
    assert_eq!(w.view().len(), 2);
}

#[test]
fn unfollowing_drops_pending_notifications() {
    let (mut w, kai, _, _) = world();
    w.switch(&[&kai], NOW);
    let follow: String = w.c.query_row("SELECT id FROM follow", [], |r| r.get(0)).unwrap();
    follows::end(&w.c, &w.friend, &follow, NOW + 1_000).unwrap();
    notifier::process_due(&w.c, NOW + SETTLE + DELAY).unwrap();
    assert!(w.inbox().is_empty());
    assert!(notifier::follower_view(&w.c, &w.friend, &w.sys).unwrap().is_none());
}
