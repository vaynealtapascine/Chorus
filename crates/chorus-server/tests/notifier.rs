//! Follower notifications on the server (NOTIFICATIONS.md §2, §5): a follower learns nothing
//! before the reveal time, hidden members are never named, an undo before the reveal cancels it,
//! and bursts collapse into one notification.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, follows, ingest, notifier, project};
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
        self.push_from("phone", kind, entity, payload, at)
    }

    fn push_from(&mut self, device: &str, kind: &str, entity: &str, payload: Value, at: i64) -> Op {
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
            device_id: device.into(),
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
fn two_devices_can_edit_different_account_prefs_without_clobbering() {
    let mut w = World::new();
    let sys = w.sys.clone();
    let friend = w.friend.clone();
    let (follow, _) = follows::request(&w.c, &friend, "@stars", NOW - 5_000).unwrap();
    w.push("follow.accept", &follow, json!({}), NOW - 4_000);

    let ceiling = json!({"time": {"mode": "exact"}, "delay": {"min_s": 0, "max_s": 0}});
    w.push_from("phone", "pref.set", &sys, json!({"device": "", "key": "follow_ceiling", "value": ceiling}), NOW);
    w.push_from("laptop", "pref.set", &sys, json!({"device": "", "key": "ui.density", "value": "compact"}), NOW);

    let saved: Vec<(String, String)> =
        w.c.prepare("SELECT key, value FROM pref WHERE account_id = ?1 ORDER BY key")
            .unwrap()
            .query_map([&sys], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
    assert_eq!(saved.len(), 2);
    assert_eq!(saved[0].0, "follow_ceiling");
    assert_eq!(serde_json::from_str::<Value>(&saved[0].1).unwrap(), ceiling);
    assert_eq!(saved[1], ("ui.density".into(), "\"compact\"".into()));
    let view = notifier::follower_view(&w.c, &friend, &sys).unwrap().unwrap();
    assert_eq!(view["time"]["mode"], "exact");

    project::rebuild(&mut w.c).unwrap();
    let rebuilt: i64 = w.c.query_row("SELECT count(*) FROM pref WHERE account_id = ?1", [&sys], |r| r.get(0)).unwrap();
    assert_eq!(rebuilt, 2);
    let view = notifier::follower_view(&w.c, &friend, &sys).unwrap().unwrap();
    assert_eq!(view["time"]["mode"], "exact");
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

#[test]
fn delivery_pushes_the_inbox_text_encrypted_to_the_followers_devices() {
    use chorus_server::push;
    use p256::elliptic_curve::sec1::ToSec1Point;
    let (mut w, kai, _, _) = world();
    // the friend's phone registers its UnifiedPush endpoint and keys
    w.c.execute(
        "INSERT INTO device (id, account_id, short_id, name, platform, public_key, created_at)
         VALUES ('phone', ?1, 'aabbccdd', 'Phone', 'android', 'k', 0)",
        [&w.friend],
    )
    .unwrap();
    let ua = p256::SecretKey::from_slice(&[5; 32]).unwrap();
    let reg = push::Registration {
        endpoint: "https://ntfy.example/upABC?up=1".into(),
        p256dh: push::b64url(ua.public_key().to_sec1_point(false).as_bytes()),
        auth: push::b64url(&[8; 16]),
    };
    push::register(&w.c, "phone", &reg).unwrap();

    w.switch(&[&kai], NOW);
    let early = notifier::process_due(&w.c, NOW + SETTLE + DELAY - 1).unwrap();
    assert!(early.pushes.is_empty(), "nothing may be pushed before the reveal");
    let out = notifier::process_due(&w.c, NOW + SETTLE + DELAY).unwrap();
    assert_eq!(out.pushes.len(), 1);
    assert_eq!(out.pushes[0].endpoint, reg.endpoint);
    let plain = push::decrypt(&ua, &[8; 16], &out.pushes[0].body).expect("decrypts with the device key");
    let v: Value = serde_json::from_slice(&plain).unwrap();
    assert_eq!(v["text"], "Kai is fronting");
    assert_eq!(v["title"], "stars");
    // a gone endpoint is forgotten
    push::record(&w.c, "phone", &push::Sent::Gone).unwrap();
    let left: Option<String> =
        w.c.query_row("SELECT push_endpoint FROM device WHERE id = 'phone'", [], |r| r.get(0)).unwrap();
    assert!(left.is_none());
}

#[test]
fn a_digest_follower_gets_one_summary_for_the_day() {
    let (mut w, kai, june, _) = world();
    let follow: String = w.c.query_row("SELECT id FROM follow", [], |r| r.get(0)).unwrap();
    let ceiling = json!({"delay": {"min_s": 60, "max_s": 60}, "time": {"mode": "part_of_day"},
                         "levels": ["front"], "digest_only": true});
    w.push("follow.set_ceiling", &follow, json!({"ceiling": ceiling}), NOW - 2_000);
    let day = NOW - NOW.rem_euclid(86_400_000) + 86_400_000; // next UTC midnight (tz offset 0)
    let h = 3_600_000;
    w.switch(&[&kai], day + 9 * h);
    notifier::process_due(&w.c, day + 9 * h + SETTLE + DELAY).unwrap(); // revealed, held for the digest
    w.switch(&[&june], day + 19 * h);
    notifier::process_due(&w.c, day + 19 * h + SETTLE + DELAY).unwrap();
    assert!(w.inbox().is_empty(), "nothing before the digest");
    assert_eq!(w.view(), ["June"], "the view is still revealed on schedule");
    // the digest goes out at 20:00 plus a stable 0–20 min spread
    let out = notifier::process_due(&w.c, day + 20 * h + 20 * 60_000).unwrap();
    assert_eq!(w.inbox(), ["Kai (morning) · June (evening)"]);
    assert_eq!(out.handled, 2, "both items were due in the same pass");
}

#[test]
fn bucket_ceiling_inherits_and_folds_while_member_policy_restricts_reveal() {
    let mut w = World::new();
    let sys = w.sys.clone();
    let friend = w.friend.clone();
    let close = new_id(2, [31; 10]);
    let inherited = new_id(2, [32; 10]);
    let restricted = w.member("Restricted", json!({"announce": {"buckets": [close]}}));
    let open = w.member("Open", json!({"announce": "everyone"}));
    w.push(
        "account.set",
        &sys,
        json!({"settings": {"follow_ceiling": {
            "delay": {"min_s": 120, "max_s": 120}, "time": {"mode": "part_of_day"}
        }}}),
        NOW - 9_000,
    );
    w.push(
        "bucket.set",
        &close,
        json!({"name": "Close", "ceiling": {
            "delay": {"min_s": 0, "max_s": 0}, "time": {"mode": "exact"}
        }}),
        NOW - 8_000,
    );
    w.push("bucket.set", &inherited, json!({"name": "Inherited", "ceiling": {}}), NOW - 7_000);
    let (follow, _) = follows::request(&w.c, &w.friend, "@stars", NOW - 5_000).unwrap();
    w.push("follow.accept", &follow, json!({}), NOW - 4_000);

    // A restricted member is hidden until this follower is assigned to its bucket.
    w.switch(&[&restricted], NOW);
    notifier::process_due(&w.c, NOW + SETTLE + 120_000).unwrap();
    assert!(w.view().is_empty());
    assert!(w.inbox().is_empty());

    w.push("bucket.assign", &close, json!({"follower_account_id": friend}), NOW + 200_000);
    w.push("bucket.assign", &inherited, json!({"follower_account_id": friend}), NOW + 201_000);
    let t = NOW + 220_000;
    w.switch(&[&open, &restricted], t);
    notifier::process_due(&w.c, t + SETTLE).unwrap();
    assert_eq!(w.view(), ["Restricted", "Open"]);
    let v = notifier::follower_view(&w.c, &w.friend, &w.sys).unwrap().unwrap();
    assert_eq!(v["since"], t);
    assert_eq!(v["time"]["mode"], "exact");

    // The remaining empty bucket inherits the account's 120-second, fuzzy default.
    w.push("bucket.unassign", &close, json!({"follower_account_id": friend}), t + 20_000);
    let later = t + 40_000;
    w.switch(&[&open], later);
    notifier::process_due(&w.c, later + SETTLE + 119_999).unwrap();
    assert_eq!(w.view(), ["Restricted", "Open"]);
    notifier::process_due(&w.c, later + SETTLE + 120_000).unwrap();
    assert_eq!(w.view(), ["Open"]);
    let v = notifier::follower_view(&w.c, &w.friend, &w.sys).unwrap().unwrap();
    assert_eq!(v["time"]["mode"], "part_of_day");
}

#[test]
fn a_followers_quiet_hours_are_in_their_own_time_zone() {
    let (mut w, kai, _, _) = world();
    let follow: String = w.c.query_row("SELECT id FROM follow", [], |r| r.get(0)).unwrap();
    // NOW is 14:13 UTC; the follower is at UTC+9, where it's 23:13 and "23:00–08:00" applies
    let prefs = json!({"quiet_hours": {"from": "23:00", "to": "08:00"}, "tz_offset_min": 540});
    follows::set_prefs(&w.c, &w.friend, &follow, prefs, NOW - 1_000).unwrap();
    w.switch(&[&kai], NOW);
    let due = NOW + SETTLE + DELAY;
    notifier::process_due(&w.c, due).unwrap();
    assert_eq!(w.view(), ["Kai"], "quiet hours hold the ping, not the reveal");
    assert!(w.inbox().is_empty());
    let morning = NOW - NOW.rem_euclid(86_400_000) + 23 * 3_600_000; // 08:00 at UTC+9
    notifier::process_due(&w.c, morning - 1).unwrap();
    assert!(w.inbox().is_empty());
    notifier::process_due(&w.c, morning).unwrap();
    assert_eq!(w.inbox(), ["Kai is fronting"]);
}

/// NOTIFICATIONS.md §5 over random switch sequences: recording a switch never changes what the
/// follower can see; anything they see changes only in a pass that handled something due; what
/// the view shows is a real past state at least settle + minimum delay old, in order; and a hidden
/// member is never named anywhere.
#[test]
fn follower_surfaces_only_change_at_reveal_times() {
    let tick = 30_000;
    let mut total_reveals = 0;
    for seed in 1..=12u64 {
        let (mut w, kai, june, secret) = world();
        let follow: String = w.c.query_row("SELECT id FROM follow", [], |r| r.get(0)).unwrap();
        let max_s = [60, 300, 900][(seed % 3) as usize];
        let stacking = if seed % 4 == 0 { "sequence" } else { "collapse" };
        let ceiling = json!({"delay": {"min_s": 60, "max_s": max_s}, "time": {"mode": "exact"},
                             "levels": ["front"], "stacking": stacking,
                             "share_history": true, "share_stats": true});
        w.push("follow.set_ceiling", &follow, json!({"ceiling": ceiling}), NOW - 2_000);

        let mut rng = seed.wrapping_mul(0x9e37_79b9_7f4a_7c15) | 1;
        let mut next = move || {
            rng ^= rng << 13;
            rng ^= rng >> 7;
            rng ^= rng << 17;
            rng
        };
        let surfaces = |w: &World, t: i64| {
            let v = notifier::follower_view_at(&w.c, &w.friend, &w.sys, t).unwrap();
            let l = notifier::list(&w.c, &w.friend, 200).unwrap();
            serde_json::to_string(&(v, l)).unwrap()
        };
        let named = |w: &World| -> Vec<String> {
            let mut v = w.view();
            v.sort();
            v
        };
        let who = [(kai.as_str(), Some("Kai")), (june.as_str(), Some("June")), (secret.as_str(), None)];
        // what really happened: (time, the names a follower may know about, sorted)
        let mut history: Vec<(i64, Vec<String>)> = vec![(NOW - 60_000, Vec::new())];
        let mut matched = 0;
        let mut reveals = 0;
        let mut t = NOW;
        for _ in 0..240 {
            t += tick;
            if next() % 8 == 0 {
                let at = t - (next() % tick as u64) as i64;
                let chosen: Vec<_> = who.iter().filter(|_| next() % 2 == 0).collect();
                let ids: Vec<&str> = chosen.iter().map(|(id, _)| *id).collect();
                let mut names: Vec<String> = chosen.iter().filter_map(|(_, n)| n.map(str::to_string)).collect();
                names.sort();
                let before = surfaces(&w, t);
                w.switch(&ids, at);
                assert_eq!(surfaces(&w, t), before, "seed {seed}: recording a switch changed a follower surface");
                history.push((at, names));
            }
            let before = surfaces(&w, t);
            let p = notifier::process_due(&w.c, t).unwrap();
            let after = surfaces(&w, t);
            assert!(!after.contains("Secret"), "seed {seed}: a hidden member was named");
            if after != before {
                reveals += 1;
                assert!(p.handled > 0, "seed {seed}: a surface changed with nothing due at {t}");
            }
            let shown = named(&w);
            let old_enough = t - SETTLE - 60_000;
            let at = (matched..history.len()).find(|&i| history[i].0 <= old_enough && history[i].1 == shown);
            let Some(i) = at else {
                panic!(
                    "seed {seed}: at {t} the view shows {shown:?}, not a past state old enough, in order: {history:?}"
                );
            };
            matched = i;
        }
        assert!(history.len() >= 12, "seed {seed}: only {} switches", history.len());
        total_reveals += reveals;
    }
    // delays are drawn on the server, so count reveals across all seeds, not per seed
    assert!(total_reveals >= 40, "too few reveals overall: {total_reveals}");
}

#[test]
fn shared_history_and_stats_only_contain_revealed_whole_days() {
    let (mut w, kai, june, _) = world();
    let follow: String = w.c.query_row("SELECT id FROM follow", [], |r| r.get(0)).unwrap();
    let view = |w: &World, at: i64| notifier::follower_view_at(&w.c, &w.friend, &w.sys, at).unwrap().unwrap();
    let h = 3_600_000;
    let today = NOW - NOW.rem_euclid(86_400_000); // NOW is 14:13 UTC
    let tomorrow = today + 24 * h;

    w.switch(&[&kai], NOW);
    notifier::process_due(&w.c, NOW + SETTLE + DELAY).unwrap();
    assert!(view(&w, NOW + h).get("history").is_none(), "not shared by default");
    assert!(view(&w, NOW + h).get("stats").is_none());

    let ceiling = json!({"delay": {"min_s": 60, "max_s": 60}, "time": {"mode": "exact"}, "levels": ["front"],
                         "share_history": true, "share_stats": true});
    w.push("follow.set_ceiling", &follow, json!({"ceiling": ceiling}), NOW + 60 * 60_000);
    let t = NOW + 2 * h;
    w.switch(&[&june], t);
    let names = |v: &Value| -> Vec<String> {
        v["history"]
            .as_array()
            .unwrap()
            .iter()
            .map(|i| i["entries"][0]["name"].as_str().unwrap_or("").to_string())
            .collect()
    };
    let before = view(&w, t + SETTLE + DELAY - 1);
    assert_eq!(names(&before), ["Kai"], "June isn't revealed yet");
    assert_eq!(before["stats"]["members"], json!([]), "nothing counts until a whole day has passed");

    notifier::process_due(&w.c, t + SETTLE + DELAY).unwrap();
    assert_eq!(names(&view(&w, t + SETTLE + DELAY)), ["June", "Kai"]);
    assert_eq!(view(&w, t + SETTLE + DELAY)["history"][1]["time"]["at"], NOW, "exact rule: the real start");

    // the next day: Kai 14:13–16:13 (2 h), June 16:13–midnight (7 h 47 min) → 20 % and 80 %
    let pending = tomorrow + 30 * 60_000;
    w.switch(&[&kai], pending); // recorded, not yet revealed
    let s = view(&w, tomorrow + h)["stats"].clone();
    assert_eq!(s["members"], json!([{"name": "June", "share_pct": 80}, {"name": "Kai", "share_pct": 20}]));
    assert_eq!(s["days"], 30);
}
