//! Channel permissions (M5.10, perms.rs) as a property: on random spaces, roles, overrides and
//! op logs, the server's rule equals an independent model of SPEC §5.1, and nothing reaches an
//! account the rule denies — not through sync (`op_visible_to`, the batched digest), not through
//! the REST reads, not through search.

use std::collections::{BTreeMap, BTreeSet};

use chorus_core::sync::Digest;
use chorus_server::{api_data::Principal, db, messages, oplog, perms, search, visibility};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn pick<'a, T>(&mut self, v: &'a [T]) -> &'a T {
        &v[self.below(v.len())]
    }
}

const ACCOUNTS: &[&str] = &["owner", "adm", "mem", "ro", "cus", "guest"];
const CHANNELS: &[&str] = &["c0", "c1", "c2"];
const PERMS: &[&str] = &["view", "send", "react", "thread", "pin", "manage"];

/// (channel, target type, target) → (allow, deny)
type Overrides = BTreeMap<(String, String, String), (BTreeSet<String>, BTreeSet<String>)>;

/// The rule as SPEC §5.1 states it, over plain data.
struct Model {
    kind: &'static str,
    roles: BTreeMap<&'static str, &'static str>,
    custom: BTreeSet<&'static str>,
    overrides: Overrides,
}

impl Model {
    fn one(&self, account: &str, channel: &str, perm: &str) -> bool {
        if account == "owner" {
            return true;
        }
        let role = self.roles.get(account).copied();
        if role.is_some() && self.kind == "dm" || role == Some("admin") {
            return true;
        }
        let level = |t: &str, id: &str| -> Option<bool> {
            let (allow, deny) = self.overrides.get(&(channel.to_string(), t.to_string(), id.to_string()))?;
            if deny.contains(perm) {
                Some(false)
            } else if allow.contains(perm) {
                Some(true)
            } else {
                None
            }
        };
        if let Some(v) = level("account", account) {
            return v;
        }
        let Some(role) = role else { return false };
        if let Some(v) = level("role", role).or_else(|| level("role", "everyone")) {
            return v;
        }
        match role {
            "member" => ["view", "send", "react", "thread", "pin"].contains(&perm),
            "read_only" => ["view", "react"].contains(&perm),
            r => self.custom.contains(perm) && r == "cus",
        }
    }
    fn can(&self, account: &str, channel: &str, perm: &str) -> bool {
        self.one(account, channel, perm) && self.one(account, channel, "view")
    }
}

fn put(c: &Connection, n: usize, account: &str, kind: &str, entity: Option<&str>, payload: Value) -> String {
    let id = format!("op-{n:04}");
    c.execute(
        "INSERT INTO op(id,scope,kind,entity_id,payload,hlc,account_id,device_id,occurred_at,device_at,tz_offset_min,received_at)
         VALUES (?1,'space:s',?2,?3,?4,'0:0:0',?5,'dev',?6,0,0,0)",
        params![id, kind, entity, payload.to_string(), account, n as i64],
    )
    .unwrap();
    id
}

#[test]
fn nothing_reaches_an_account_the_rule_denies() {
    for seed in 1..=60u64 {
        let mut r = Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1);
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        let kind = if seed % 7 == 0 { "dm" } else { "shared" };
        c.execute(
            "INSERT INTO space(id,kind,owner_account_id,roles,created_at) VALUES ('s',?1,'owner',
               '[{\"id\":\"cus\",\"name\":\"Custom\",\"perms\":[\"view\",\"send\",\"pin\"]}]',0)",
            [kind],
        )
        .unwrap();
        let mut model = Model {
            kind,
            roles: BTreeMap::new(),
            custom: ["view", "send", "pin"].into_iter().collect(),
            overrides: BTreeMap::new(),
        };
        for (account, role) in
            [("owner", "member"), ("adm", "admin"), ("mem", "member"), ("ro", "read_only"), ("cus", "cus")]
        {
            if account != "owner" && r.below(5) == 0 {
                continue; // not everyone is in every space
            }
            c.execute(
                "INSERT INTO space_member(space_id,account_id,role,joined_hlc) VALUES ('s',?1,?2,'1:0:1')",
                [account, role],
            )
            .unwrap();
            model.roles.insert(account, role);
        }
        for a in ACCOUNTS {
            c.execute("INSERT INTO scope_access(account_id,scope) VALUES (?1,'space:s')", [a]).unwrap();
        }
        for ch in CHANNELS {
            c.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES (?1,'s','text',0)", [ch]).unwrap();
            for _ in 0..r.below(4) {
                let (t, target) = match r.below(3) {
                    0 => ("account", *r.pick(ACCOUNTS)),
                    1 => ("role", *r.pick(&["member", "read_only", "cus", "admin"])),
                    _ => ("role", "everyone"),
                };
                let mut allow = BTreeSet::new();
                let mut deny = BTreeSet::new();
                for p in PERMS {
                    match r.below(4) {
                        0 => {
                            allow.insert(p.to_string());
                        }
                        1 => {
                            deny.insert(p.to_string());
                        }
                        _ => {}
                    }
                }
                c.execute(
                    "INSERT OR REPLACE INTO channel_permission(channel_id,target_type,target_id,allow,deny,hlc) VALUES (?1,?2,?3,?4,?5,'1:0:1')",
                    params![ch, t, target, json!(allow).to_string(), json!(deny).to_string()],
                )
                .unwrap();
                model.overrides.insert((ch.to_string(), t.into(), target.into()), (allow, deny));
            }
        }
        // the rule itself
        for a in ACCOUNTS {
            for ch in CHANNELS {
                for p in PERMS {
                    assert_eq!(perms::can(&c, a, ch, p).unwrap(), model.can(a, ch, p), "seed {seed}: {a} {p} in {ch}");
                }
            }
        }
        // a log: messages (some private asides), threads, edits, reactions, reads, files
        let mut ops: Vec<(String, &'static str, Option<String>)> = Vec::new(); // (op id, author, channel it belongs to)
        let mut n = 0;
        for i in 0..40 {
            n += 1;
            let author = *r.pick(ACCOUNTS);
            let ch = *r.pick(CHANNELS);
            let msg = format!("msg{i}");
            let aside = r.below(5) == 0;
            let visibility: Option<&str> = aside.then_some(r#"{"mode":"system_only"}"#);
            c.execute(
                "INSERT INTO message(id,channel_id,account_id,occurred_at,text,visibility) VALUES (?1,?2,?3,?4,?5,?6)",
                params![msg, ch, author, i, format!("violet note {i}"), visibility],
            )
            .unwrap();
            c.execute("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message WHERE id=?1", [&msg])
                .unwrap();
            let mut payload = json!({"channel_id": ch, "text": "x"});
            if aside {
                payload["visibility"] = json!({"mode": "system_only"});
            }
            ops.push((put(&c, n, author, "message.send", Some(&msg), payload), author, Some(ch.to_string())));
            let other = *r.pick(ACCOUNTS);
            n += 1;
            let (kind, payload) = match r.below(4) {
                0 => ("message.edit", json!({"message_id": msg})),
                1 => ("reaction.add", json!({"target_type": "message", "target_id": msg, "emoji": "💜"})),
                2 => ("read.mark", json!({"channel_id": ch, "message_id": msg})),
                _ => ("message.pin", json!({})),
            };
            ops.push((put(&c, n, other, kind, Some(&msg), payload), other, Some(ch.to_string())));
            if r.below(6) == 0 {
                let th = format!("th{i}");
                c.execute(
                    "INSERT INTO channel(id,space_id,kind,parent_message_id,created_at) VALUES (?1,'s','thread',?2,0)",
                    [&th, &msg],
                )
                .unwrap();
                n += 1;
                ops.push((
                    put(
                        &c,
                        n,
                        author,
                        "channel.create",
                        Some(&th),
                        json!({"parent_message_id": msg, "kind": "thread"}),
                    ),
                    author,
                    Some(ch.to_string()),
                ));
                n += 1;
                ops.push((
                    put(&c, n, other, "message.send", Some(&format!("t{i}")), json!({"channel_id": th})),
                    other,
                    Some(ch.to_string()),
                ));
            }
            if r.below(5) == 0 {
                let file = format!("file{i}");
                c.execute("INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position) VALUES ('message',?1,?2,0)", [&msg, &file])
                    .unwrap();
                n += 1;
                ops.push((
                    put(&c, n, author, "attachment.create", Some(&file), json!({})),
                    author,
                    Some(ch.to_string()),
                ));
            }
        }
        for (kind, author) in [("space.join", "owner"), ("space.set", "owner"), ("unknown", "owner")] {
            n += 1;
            ops.push((put(&c, n, author, kind, Some("s"), json!({"account_id": "mem"})), author, None));
        }
        let log = oplog::scope_after(&c, "space:s", 0, 10_000).unwrap();
        for a in ACCOUNTS {
            let member = a == &"owner" || model.roles.contains_key(a);
            let mut per_op = Digest::default();
            for (o, (id, author, channel)) in log.iter().zip(&ops) {
                assert_eq!(&o.id, id);
                let visible = visibility::op_visible_to(&c, a, o).unwrap();
                if visible {
                    per_op.add(&o.id);
                }
                if author == a {
                    assert!(visible, "seed {seed}: own ops always come back");
                    continue;
                }
                match channel {
                    // never past the rule
                    Some(ch) if visible => assert!(model.can(a, ch, "view"), "seed {seed}: {} leaked to {a}", o.kind),
                    None if visible => assert!(
                        member || matches!(o.kind.as_str(), "space.create" | "space.set"),
                        "seed {seed}: {} leaked to guest {a}",
                        o.kind
                    ),
                    _ => {}
                }
                // and not hiding what the rule allows: a public send in a channel you may view
                if o.kind == "message.send"
                    && o.payload.get("visibility").is_none()
                    && o.payload["channel_id"].as_str().is_some_and(|ch| ch.starts_with('c'))
                {
                    let ch = o.payload["channel_id"].as_str().unwrap();
                    assert_eq!(visible, model.can(a, ch, "view"), "seed {seed}: public send in {ch} for {a}");
                }
            }
            assert_eq!(
                visibility::visible_digest(&c, a, "space:s").unwrap(),
                per_op,
                "seed {seed}: batched digest for {a}"
            );
            // REST and search: own, or public in a channel the account may view
            let p = Principal::owner(a);
            for i in 0..40 {
                let (ch, author, aside): (String, String, bool) = c
                    .query_row(
                        "SELECT channel_id, account_id, visibility IS NOT NULL FROM message WHERE id=?1",
                        [format!("msg{i}")],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                    )
                    .unwrap();
                let readable = author == *a || (!aside && model.can(a, &ch, "view"));
                assert_eq!(
                    messages::one(&c, &p, &format!("msg{i}")).unwrap().is_some(),
                    readable,
                    "seed {seed}: REST msg{i} for {a}"
                );
            }
            let q = search::MessageQuery { q: "violet".into(), limit: Some(100), ..Default::default() };
            for hit in search::messages(&c, &p, &q).unwrap()["items"].as_array().unwrap() {
                let ch = hit["channel_id"].as_str().unwrap();
                assert!(hit["account_id"] == *a || model.can(a, ch, "view"), "seed {seed}: search leaked {hit} to {a}");
            }
        }
    }
}
