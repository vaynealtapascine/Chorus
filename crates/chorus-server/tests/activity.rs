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
        W { c, a, b, space, n: 0 }
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

    // plain chatter notifies nobody; restricted visibility never notifies
    let m3 = new_id(NOW as u64, [52; 10]);
    w.push(&a, "message.send", &space, &m3, msg("just talking", &[&kai], json!([])));
    let m4 = new_id(NOW as u64, [53; 10]);
    let ent = json!([{"type": "mention", "offset": 0, "length": 5, "target_type": "member", "target_id": june}]);
    let mut hidden = msg("@June secret", &[&kai], ent);
    hidden["visibility"] = json!({"mode": "members", "member_ids": [kai]});
    w.push(&a, "message.send", &space, &m4, hidden);
    assert_eq!(w.inbox(&b).len(), 1);
}
