//! Post alerts (D-106): a friend asks to hear about one member's posts, the member's account
//! answers, and then each new top-level post or entry that friend may read pings them once.
//! Nothing private, no replies or reposts, and nothing for a profile the asker can't open.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, ingest, notifier, post_alerts};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

const NOW: i64 = 1_790_000_000_000;

struct W {
    c: Connection,
    n: u8,
    /// When the next op says it was written (default: now), for posts written offline long ago.
    written: Option<i64>,
}

impl W {
    fn new(accounts: &[(&str, &str)]) -> W {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        for (id, handle) in accounts {
            c.execute(
                "INSERT INTO account(id, kind, handle, display_name, created_at) VALUES (?1, 'system', ?2, ?2, 0)",
                [id, handle],
            )
            .unwrap();
            ingest::grant(&c, id, &format!("account:{id}")).unwrap();
        }
        W { c, n: 0, written: None }
    }

    fn at(&self) -> i64 {
        NOW + i64::from(self.n) * 1000
    }

    fn push(&mut self, who: &str, kind: &str, entity: &str, payload: Value) {
        self.n += 1;
        let at = self.at();
        let o = Op {
            id: new_id(at as u64, [self.n; 10]),
            kind: kind.into(),
            v: 1,
            scope: format!("account:{who}"),
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
        let (r, _) = ingest::accept(&self.c, &s, o, at, false).unwrap();
        assert!(r.error.is_none(), "{kind}: {:?}", r.error);
    }

    fn follow(&self, follower: &str, target: &str) {
        self.c
            .execute(
                "INSERT INTO follow(id, follower_account_id, target_account_id, status, created_at)
                 VALUES (?1, ?2, ?3, 'active', 0)",
                params![format!("{follower}>{target}"), follower, target],
            )
            .unwrap();
    }

    fn post(&mut self, who: &str, author: &str, n: u8, extra: Value) -> String {
        let id = new_id(NOW as u64, [100 + n; 10]);
        let mut p = json!({"kind": "note", "authors": [author], "text": format!("post {n}"), "entities": [],
                           "visibility": {"mode": "followers"}});
        for (k, v) in extra.as_object().unwrap() {
            p[k] = v.clone();
        }
        self.push(who, "post.create", &id, p);
        id
    }

    /// (kind, title, text) of what `account` has been told, newest first.
    fn inbox(&self, account: &str) -> Vec<(String, String, String)> {
        notifier::process_due(&self.c, NOW + 10_000_000).unwrap();
        let l = notifier::list(&self.c, account, 50).unwrap();
        l["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|i| {
                let s = |k: &str| i[k].as_str().unwrap_or_default().to_string();
                (s("kind"), s("title"), s("text"))
            })
            .collect()
    }
}

const STARS: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const ALEX: &str = "0192f8c2-0000-7000-8000-0000000000b2";
const SAM: &str = "0192f8c2-0000-7000-8000-0000000000c3";

#[test]
fn asked_approved_and_then_each_readable_post_pings_once() {
    let mut w = W::new(&[(STARS, "stars"), (ALEX, "alex"), (SAM, "sam")]);
    let kai = new_id(NOW as u64, [40; 10]);
    let fox = new_id(NOW as u64, [41; 10]);
    w.push(STARS, "member.create", &kai, json!({"name": "Kai", "visibility": {"mode": "followers"}}));
    w.push(STARS, "member.create", &fox, json!({"name": "Fox", "visibility": {"mode": "private"}}));
    w.follow(ALEX, STARS);

    // a profile Alex can't open can't be asked about (not even whether it exists); nor one's own
    assert!(matches!(post_alerts::request(&w.c, ALEX, STARS, &fox, w.at()), Err(post_alerts::AlertError::NotFound)));
    assert!(matches!(post_alerts::request(&w.c, SAM, STARS, &kai, w.at()), Err(post_alerts::AlertError::NotFound)));
    assert!(matches!(post_alerts::request(&w.c, STARS, STARS, &kai, w.at()), Err(post_alerts::AlertError::Own)));

    // Alex asks: the Stars hear once, however often Alex asks
    assert_eq!(post_alerts::request(&w.c, ALEX, STARS, &kai, w.at()).unwrap(), "requested");
    assert_eq!(post_alerts::request(&w.c, ALEX, STARS, &kai, w.at()).unwrap(), "requested");
    let asked = w.inbox(STARS);
    assert_eq!(asked.len(), 1);
    assert_eq!(asked[0].0, "alert_request");
    assert_eq!(asked[0].1, "alex asked for post alerts");
    assert_eq!(post_alerts::incoming(&w.c, STARS).unwrap()["items"][0]["status"], "requested");

    // nothing before a yes
    w.post(STARS, &kai, 1, json!({}));
    assert!(w.inbox(ALEX).is_empty());

    post_alerts::decide(&w.c, STARS, ALEX, &kai, true, w.at()).unwrap();
    assert_eq!(post_alerts::status(&w.c, ALEX, &kai).unwrap(), "active");
    assert_eq!(w.inbox(ALEX)[0].0, "alert_accepted");

    // a followers post and a server-wide entry ping; the entry says so and shows its title
    w.post(STARS, &kai, 2, json!({}));
    w.post(
        STARS,
        &kai,
        3,
        json!({"kind": "entry", "title": "A long day", "text": "…", "visibility": {"mode": "server"}}),
    );
    let told = w.inbox(ALEX);
    assert_eq!(told.len(), 3);
    assert_eq!(told[0], ("post".into(), "Kai wrote a journal entry".into(), "A long day".into()));
    assert_eq!(told[1], ("post".into(), "Kai posted".into(), "post 2".into()));

    // never: private, a reply, a repost, someone else's post, or one written long ago; a
    // warning stands in for the text
    w.post(STARS, &kai, 4, json!({"visibility": {"mode": "private"}}));
    let first = new_id(NOW as u64, [102; 10]);
    w.post(STARS, &kai, 5, json!({"reply_to": first}));
    w.post(STARS, &kai, 6, json!({"repost_of": first}));
    w.post(STARS, &fox, 7, json!({}));
    w.post(STARS, &kai, 8, json!({"cw": "spoilers"}));
    w.written = Some(NOW - 3 * 24 * 60 * 60 * 1000);
    w.post(STARS, &kai, 11, json!({}));
    assert_eq!(w.inbox(ALEX).len(), 4);
    assert_eq!(w.inbox(ALEX)[0].2, "Content warning: spoilers");

    // the follow ends: followers posts stop, server-wide ones still reach Alex
    w.c.execute("UPDATE follow SET status = 'ended' WHERE follower_account_id = ?1", [ALEX]).unwrap();
    w.post(STARS, &kai, 9, json!({}));
    w.post(STARS, &kai, 10, json!({"visibility": {"mode": "server"}}));
    assert_eq!(w.inbox(ALEX).len(), 5);
}

#[test]
fn a_followed_person_is_asked_by_account() {
    let w = W::new(&[(STARS, "stars"), (ALEX, "alex"), (SAM, "sam")]);
    w.c.execute("UPDATE account SET kind = 'person' WHERE id = ?1", [SAM]).unwrap();
    let me = new_id(NOW as u64, [42; 10]);
    w.c.execute(
        "INSERT INTO member(id, account_id, name, is_self, visibility, short_id, created_at)
         VALUES (?1, ?2, 'Sam', 1, '{\"mode\":\"private\"}', 'sam', 0)",
        params![me, SAM],
    )
    .unwrap();
    // Sam's profile is private, and Alex doesn't follow Sam yet
    assert_eq!(post_alerts::person_member(&w.c, ALEX, SAM).unwrap(), None);
    assert!(matches!(post_alerts::request(&w.c, ALEX, SAM, &me, w.at()), Err(post_alerts::AlertError::NotFound)));
    // following Sam is enough to ask about Sam (only Sam's own member, and never a system's)
    w.follow(ALEX, SAM);
    w.follow(ALEX, STARS);
    assert_eq!(post_alerts::person_member(&w.c, ALEX, SAM).unwrap().as_deref(), Some(me.as_str()));
    assert_eq!(post_alerts::person_member(&w.c, ALEX, STARS).unwrap(), None);
    assert_eq!(post_alerts::request(&w.c, ALEX, SAM, &me, w.at()).unwrap(), "requested");
    assert_eq!(w.inbox(SAM)[0].1, "alex asked for post alerts");
}

#[test]
fn a_no_is_quiet_and_sticks() {
    let mut w = W::new(&[(STARS, "stars"), (ALEX, "alex")]);
    let kai = new_id(NOW as u64, [40; 10]);
    w.push(STARS, "member.create", &kai, json!({"name": "Kai", "visibility": {"mode": "server"}}));
    post_alerts::request(&w.c, ALEX, STARS, &kai, w.at()).unwrap();
    post_alerts::decide(&w.c, STARS, ALEX, &kai, false, w.at()).unwrap();
    // Alex isn't told and still sees "requested"; asking again doesn't ping the Stars again
    assert!(w.inbox(ALEX).is_empty());
    assert_eq!(post_alerts::status(&w.c, ALEX, &kai).unwrap(), "requested");
    post_alerts::cancel(&w.c, ALEX, &kai).unwrap();
    assert_eq!(post_alerts::request(&w.c, ALEX, STARS, &kai, w.at()).unwrap(), "requested");
    assert_eq!(w.inbox(STARS).len(), 1);
    assert!(post_alerts::incoming(&w.c, STARS).unwrap()["items"].as_array().unwrap().is_empty());
    w.post(STARS, &kai, 1, json!({"visibility": {"mode": "server"}}));
    assert!(w.inbox(ALEX).is_empty());

    // someone else's members can't be decided on
    assert!(matches!(
        post_alerts::decide(&w.c, ALEX, ALEX, &kai, true, w.at()),
        Err(post_alerts::AlertError::NotFound)
    ));
}

#[test]
fn turning_alerts_off_stops_them_and_removal_is_a_no() {
    let mut w = W::new(&[(STARS, "stars"), (ALEX, "alex")]);
    let kai = new_id(NOW as u64, [40; 10]);
    w.push(STARS, "member.create", &kai, json!({"name": "Kai", "visibility": {"mode": "server"}}));
    let on = |w: &W| {
        post_alerts::request(&w.c, ALEX, STARS, &kai, w.at()).unwrap();
        post_alerts::decide(&w.c, STARS, ALEX, &kai, true, w.at()).unwrap();
    };
    on(&w);
    post_alerts::cancel(&w.c, ALEX, &kai).unwrap();
    assert_eq!(post_alerts::status(&w.c, ALEX, &kai).unwrap(), "none");
    w.post(STARS, &kai, 1, json!({"visibility": {"mode": "server"}}));
    assert_eq!(w.inbox(ALEX).len(), 1, "only the earlier yes");

    // the Stars stop Alex's alerts: no more posts, and asking again stays "requested"
    on(&w);
    post_alerts::decide(&w.c, STARS, ALEX, &kai, false, w.at()).unwrap();
    w.post(STARS, &kai, 2, json!({"visibility": {"mode": "server"}}));
    assert_eq!(w.inbox(ALEX).len(), 2);
    assert_eq!(post_alerts::request(&w.c, ALEX, STARS, &kai, w.at()).unwrap(), "requested");
}
