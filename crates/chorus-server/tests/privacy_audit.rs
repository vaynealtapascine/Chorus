//! Audit: an ordinary account (Mallory) pushes ops through the real ingest path at another
//! account's (Alice's) data. Each test asserts the attack fails, so a failing test is a hole.
//! The rule they pin down (ingest.rs): an entity lives in the scope it was made in, and the
//! members, fields, attachments and files an op names must be its author's to name.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::op::Op;
use chorus_core::time::{ClockSample, TimeSource};
use chorus_server::{db, follows, ingest, posts, profiles};
use rusqlite::{Connection, params};
use serde_json::{Value, json};
use std::cell::Cell;

const NOW: i64 = 1_790_000_000_000;

struct World {
    c: Connection,
    alice: String,
    mallory: String,
    bob: String,
    n: Cell<u8>,
}

impl World {
    fn new() -> World {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        let (alice, mallory, bob) = (new_id(1, [1; 10]), new_id(1, [2; 10]), new_id(1, [3; 10]));
        for (id, handle) in [(&alice, "alice"), (&mallory, "mallory"), (&bob, "bob")] {
            c.execute(
                "INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'system', ?2, 0)",
                params![id, handle],
            )
            .unwrap();
            ingest::grant(&c, id, &format!("account:{id}")).unwrap();
        }
        World { c, alice, mallory, bob, n: Cell::new(10) }
    }
    fn id(&self) -> String {
        self.n.set(self.n.get() + 1);
        new_id(NOW as u64, [self.n.get(); 10])
    }
    /// Push as `who`, in `who`'s own scope. Returns the refusal, if any.
    fn push(&self, who: &str, kind: &str, entity: &str, payload: Value) -> Option<String> {
        self.push_in(who, &format!("account:{who}"), kind, entity, payload)
    }
    fn push_in(&self, who: &str, scope: &str, kind: &str, entity: &str, payload: Value) -> Option<String> {
        self.n.set(self.n.get() + 1);
        let at = NOW + self.n.get() as i64 * 1000;
        let o = Op {
            id: new_id(at as u64, [self.n.get().wrapping_add(90); 10]),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: Some(entity.into()),
            hlc: Hlc::new(at as u64, 0, 1),
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
            device_id: "dev".into(),
            sample: ClockSample { server_time: at, mono: None, boot_id: None, offset_ms: 0 },
        };
        let (ack, _) = ingest::accept(&self.c, &s, o, at, false).unwrap();
        ack.error.map(|e| format!("{}: {}", e.code, e.message))
    }
    fn member(&self, owner: &str, name: &str, visibility: Value) -> String {
        let id = self.id();
        let err = self.push(owner, "member.create", &id, json!({"name": name, "visibility": visibility}));
        assert_eq!(err, None, "setup: member");
        id
    }
    fn post(&self, owner: &str, authors: &[&str], text: &str, visibility: Value) -> String {
        let id = self.id();
        let err = self.push(
            owner,
            "post.create",
            &id,
            json!({"kind": "note", "authors": authors, "text": text, "entities": [], "visibility": visibility}),
        );
        assert_eq!(err, None, "setup: post");
        id
    }
    /// An active follow of `target` by `follower`, approved by the target.
    fn follow(&self, follower: &str, target: &str) -> String {
        let handle: String =
            self.c.query_row("SELECT handle FROM account WHERE id=?1", [target], |r| r.get(0)).unwrap();
        let (id, _) = follows::request(&self.c, follower, &handle, NOW).map_err(|_| "request").unwrap();
        assert_eq!(self.push(target, "follow.accept", &id, json!({})), None, "setup: accept");
        id
    }
    fn status(&self, follow: &str) -> String {
        self.c.query_row("SELECT status FROM follow WHERE id=?1", [follow], |r| r.get(0)).unwrap()
    }
    fn readable(&self, viewer: &str, post: &str) -> bool {
        posts::one(&self.c, viewer, post).unwrap().is_some()
    }
}

#[test]
fn a01_a_stranger_cannot_approve_their_own_follow_request() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "private"}));
    let post = w.post(&w.alice, &[&kai], "for friends only", json!({"mode": "followers"}));
    assert!(!w.readable(&w.mallory, &post), "setup: unreadable before");
    let (follow, _) = follows::request(&w.c, &w.mallory, "alice", NOW).map_err(|_| "request").unwrap();
    assert!(!w.readable(&w.mallory, &post), "setup: a request alone grants nothing");
    let refused = w.push(&w.mallory, "follow.accept", &follow, json!({}));
    println!("ack: {refused:?}; status now {}", w.status(&follow));
    assert_eq!(w.status(&follow), "requested", "Mallory approved her own follow of Alice");
    assert!(!w.readable(&w.mallory, &post), "Mallory reads Alice's followers-only post");
}

#[test]
fn a02_a_follower_cannot_set_their_own_sharing_ceiling() {
    let w = World::new();
    let follow = w.follow(&w.mallory, &w.alice);
    let want = json!({"delay": {"min_s": 3600, "max_s": 3600}, "share_current_front": false});
    assert_eq!(w.push(&w.alice, "follow.set_ceiling", &follow, json!({"ceiling": want})), None, "setup");
    let refused = w.push(
        &w.mallory,
        "follow.set_ceiling",
        &follow,
        json!({"ceiling": {"delay": {"min_s": 0, "max_s": 0}, "share_current_front": true, "share_history": true, "share_stats": true}}),
    );
    let got: String = w.c.query_row("SELECT ceiling FROM follow WHERE id=?1", [&follow], |r| r.get(0)).unwrap();
    println!("ack: {refused:?}; ceiling now {got}");
    assert_eq!(serde_json::from_str::<Value>(&got).unwrap(), want, "Mallory rewrote what Alice shares with her");
}

#[test]
fn a03_nobody_else_can_make_a_private_profile_public() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "private"}));
    assert!(profiles::profile(&w.c, &w.mallory, &w.alice, &kai).unwrap().is_none(), "setup: hidden");
    let refused = w.push(&w.mallory, "member.set", &kai, json!({"visibility": {"mode": "server"}}));
    let got = profiles::profile(&w.c, &w.mallory, &w.alice, &kai).unwrap();
    println!("ack: {refused:?}; profile now {got:?}");
    assert!(got.is_none(), "Mallory opened Alice's private member by making it public");
}

#[test]
fn a04_a_follower_cannot_add_themselves_to_a_bucket() {
    let w = World::new();
    w.follow(&w.mallory, &w.alice);
    let bucket = w.id();
    assert_eq!(w.push(&w.alice, "bucket.set", &bucket, json!({"name": "Close"})), None, "setup");
    let kai = w.member(&w.alice, "Kai", json!({"mode": "private"}));
    let post = w.post(&w.alice, &[&kai], "close friends", json!({"mode": "buckets", "bucket_ids": [bucket]}));
    assert!(!w.readable(&w.mallory, &post), "setup: not in the bucket");
    let refused = w.push(&w.mallory, "bucket.assign", &bucket, json!({"follower_account_id": w.mallory}));
    println!("ack: {refused:?}");
    assert!(!w.readable(&w.mallory, &post), "Mallory joined Alice's bucket and reads its post");
}

#[test]
fn a05_nobody_else_can_change_a_buckets_ceiling() {
    let w = World::new();
    let bucket = w.id();
    let want = json!({"delay": {"min_s": 3600, "max_s": 3600}});
    assert_eq!(w.push(&w.alice, "bucket.set", &bucket, json!({"name": "Close", "ceiling": want})), None, "setup");
    let refused = w.push(&w.mallory, "bucket.set", &bucket, json!({"ceiling": {"delay": {"min_s": 0, "max_s": 0}}}));
    let got: String = w.c.query_row("SELECT ceiling FROM bucket WHERE id=?1", [&bucket], |r| r.get(0)).unwrap();
    println!("ack: {refused:?}; ceiling now {got}");
    assert_eq!(serde_json::from_str::<Value>(&got).unwrap(), want, "Mallory rewrote Alice's bucket ceiling");
}

#[test]
fn a06_nobody_else_can_open_a_private_custom_field() {
    let w = World::new();
    w.follow(&w.mallory, &w.alice);
    let kai = w.member(&w.alice, "Kai", json!({"mode": "followers"}));
    let field = w.id();
    let def = json!({"name": "Secret", "type": "text", "default_visibility": {"mode": "private"}});
    assert_eq!(w.push(&w.alice, "field.define", &field, def), None, "setup");
    assert_eq!(
        w.push(&w.alice, "field.set_value", &w.id(), json!({"member_id": kai, "field_id": field, "value": "hidden"})),
        None,
        "setup"
    );
    let shown = |w: &World| profiles::profile(&w.c, &w.mallory, &w.alice, &kai).unwrap().unwrap()["fields"].clone();
    assert_eq!(shown(&w), json!([]), "setup: field hidden");
    let refused = w.push(&w.mallory, "field.set_def", &field, json!({"default_visibility": {"mode": "server"}}));
    println!("ack: {refused:?}; fields now {}", shown(&w));
    assert_eq!(shown(&w), json!([]), "Mallory opened Alice's private field");
}

#[test]
fn a07_nobody_else_can_share_a_private_feed() {
    let w = World::new();
    let feed = w.id();
    let mine = json!({"name": "Mine", "query": "kind:note", "visibility": {"mode": "private"}});
    assert_eq!(w.push(&w.alice, "feed.set", &feed, mine), None, "setup");
    let refused = w.push(&w.mallory, "feed.set", &feed, json!({"visibility": {"mode": "server"}}));
    let got: String = w.c.query_row("SELECT visibility FROM feed WHERE id=?1", [&feed], |r| r.get(0)).unwrap();
    println!("ack: {refused:?}; visibility now {got}");
    assert_eq!(serde_json::from_str::<Value>(&got).unwrap(), json!({"mode": "private"}), "Mallory shared Alice's feed");
}

#[test]
fn a08_nobody_else_can_write_a_members_field_value() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "followers"}));
    let field = w.id();
    let def = json!({"name": "Note", "type": "text", "default_visibility": {"mode": "server"}});
    assert_eq!(w.push(&w.alice, "field.define", &field, def), None, "setup");
    let refused =
        w.push(&w.mallory, "field.set_value", &w.id(), json!({"member_id": kai, "field_id": field, "value": "forged"}));
    let n: i64 = w.c.query_row("SELECT count(*) FROM field_value WHERE member_id=?1", [&kai], |r| r.get(0)).unwrap();
    println!("ack: {refused:?}; rows {n}");
    assert_eq!(n, 0, "Mallory wrote a value on Alice's member");
}

#[test]
fn a09_nobody_else_can_delete_or_rename_a_member() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "server"}));
    let renamed = w.push(&w.mallory, "member.set", &kai, json!({"name": "Forged"}));
    let deleted = w.push(&w.mallory, "member.delete", &kai, json!({}));
    let (name, gone): (String, Option<i64>) =
        w.c.query_row("SELECT name, deleted_at FROM member WHERE id=?1", [&kai], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap();
    println!("rename: {renamed:?}; delete: {deleted:?}; name {name}, deleted_at {gone:?}");
    assert_eq!((name.as_str(), gone), ("Kai", None), "Mallory changed Alice's member");
}

#[test]
fn a10_nobody_else_can_end_someone_elses_follow() {
    let w = World::new();
    let follow = w.follow(&w.bob, &w.alice);
    let refused = w.push(&w.mallory, "follow.end", &follow, json!({}));
    println!("ack: {refused:?}; status {}", w.status(&follow));
    assert_eq!(w.status(&follow), "active", "Mallory ended Bob's follow of Alice");
}

#[test]
fn a11_nobody_else_can_attach_to_read_someones_private_file() {
    let w = World::new();
    let attachment = w.id();
    let hash = "a".repeat(64);
    let file = json!({"blob_hash": hash, "filename": "private.png", "mime": "image/png", "size": 10});
    let setup = w.push(&w.alice, "post.attachment", &attachment, file);
    assert_eq!(setup, None, "setup");
    let kai = w.member(&w.alice, "Kai", json!({"mode": "private"}));
    let id = w.id();
    assert_eq!(
        w.push(
            &w.alice,
            "post.create",
            &id,
            json!({"kind":"note","authors":[kai],"text":"private","entities":[],"visibility":{"mode":"private"},"attachments":[attachment]})
        ),
        None,
        "setup"
    );
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let mine = w.id();
    let refused = w.push(
        &w.mallory,
        "post.create",
        &mine,
        json!({"kind":"note","authors":[rin],"text":"look","entities":[],"visibility":{"mode":"server"},"attachments":[attachment]}),
    );
    let got = posts::one(&w.c, &w.mallory, &mine).unwrap();
    let files = got.as_ref().map(|p| p["attachments"].clone()).unwrap_or(json!(null));
    println!("ack: {refused:?}; attachments {files}");
    assert!(!files.to_string().contains(&hash), "Mallory's post exposes Alice's private file");
}

#[test]
fn a12_nobody_else_can_put_a_post_on_someones_profile() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "server"}));
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let post = w.post(&w.mallory, &[&rin], "forged highlight", json!({"mode": "server"}));
    let refused = w.push(&w.mallory, "highlight.add", &w.id(), json!({"profile_member_id": kai, "post_id": post}));
    let n: i64 =
        w.c.query_row("SELECT count(*) FROM highlight WHERE profile_member_id=?1 AND is_present", [&kai], |r| r.get(0))
            .unwrap();
    println!("ack: {refused:?}; highlights on Kai {n}");
    assert_eq!(n, 0, "Mallory put a highlight on Alice's member");
}

impl World {
    /// A space owned by `owner`, writable by them.
    fn space(&self, owner: &str) -> String {
        let id = self.id();
        ingest::grant(&self.c, owner, &format!("space:{id}")).unwrap();
        let err =
            self.push_in(owner, &format!("space:{id}"), "space.create", &id, json!({"kind": "shared", "name": "S"}));
        assert_eq!(err, None, "setup: space");
        id
    }
    fn refused(&self, err: Option<String>) -> bool {
        err.is_some_and(|e| e.starts_with("forbidden") || e.starts_with("blob_pending"))
    }
    fn stored_blob(&self, hash: &str, uploader: &str) {
        self.c
            .execute(
                "INSERT INTO blob(hash, size, mime, stored_at, uploaded_by, received, is_complete)
                 VALUES (?1, 10, 'image/png', 0, ?2, 10, 1)",
                params![hash, uploader],
            )
            .unwrap();
    }
}

#[test]
fn a13_nobody_else_can_set_an_accounts_or_systems_row() {
    let w = World::new();
    let a = w.push(&w.mallory, "account.set", &w.alice, json!({"handle": "taken"}));
    let s = w.push(&w.mallory, "system.set", &w.alice, json!({"name": "Forged"}));
    let handle: String = w.c.query_row("SELECT handle FROM account WHERE id=?1", [&w.alice], |r| r.get(0)).unwrap();
    println!("account.set: {a:?}; system.set: {s:?}; handle {handle}");
    assert!(w.refused(a) && w.refused(s), "Mallory wrote Alice's account or system row");
    assert_eq!(handle, "alice");
    assert_eq!(w.push(&w.alice, "system.set", &w.alice, json!({"name": "Ours"})), None, "the owner still can");
}

#[test]
fn a14_nobody_else_can_change_a_group_or_put_someone_elses_member_in_one() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "private"}));
    let group = w.id();
    assert_eq!(w.push(&w.alice, "group.create", &group, json!({"name": "Inner", "kind": "group"})), None, "setup");
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let renamed = w.push(&w.mallory, "group.set", &group, json!({"name": "Forged"}));
    let joined = w.push(&w.mallory, "group.add_member", &group, json!({"member_id": rin}));
    let deleted = w.push(&w.mallory, "group.delete", &group, json!({}));
    let mine = w.id();
    assert_eq!(w.push(&w.mallory, "group.create", &mine, json!({"name": "Mine", "kind": "group"})), None, "setup");
    let taken = w.push(&w.mallory, "group.add_member", &mine, json!({"member_id": kai}));
    println!("set {renamed:?}; add {joined:?}; delete {deleted:?}; foreign member {taken:?}");
    assert!([renamed, joined, deleted, taken].into_iter().all(|e| w.refused(e)));
    let (name, gone): (String, Option<i64>) = w
        .c
        .query_row("SELECT name, deleted_at FROM member_group WHERE id=?1", [&group], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap();
    assert_eq!((name.as_str(), gone), ("Inner", None));
    let n: i64 = w.c.query_row("SELECT count(*) FROM group_membership", [], |r| r.get(0)).unwrap();
    assert_eq!(n, 0);
}

#[test]
fn a15_nobody_else_can_change_states_lists_stages_or_delete_buckets_and_feeds() {
    let w = World::new();
    let (state, list, stage, bucket, feed) = (w.id(), w.id(), w.id(), w.id(), w.id());
    assert_eq!(w.push(&w.alice, "state.create", &state, json!({"name": "Tired"})), None, "setup");
    assert_eq!(w.push(&w.alice, "list.set", &list, json!({"name": "Close"})), None, "setup");
    assert_eq!(w.push(&w.alice, "stage.save", &stage, json!({"name": "Stage", "definition": {}})), None, "setup");
    assert_eq!(w.push(&w.alice, "bucket.set", &bucket, json!({"name": "Close"})), None, "setup");
    assert_eq!(w.push(&w.alice, "feed.set", &feed, json!({"name": "Mine", "query": "kind:note"})), None, "setup");
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let tries = [
        w.push(&w.mallory, "state.set", &state, json!({"name": "Forged"})),
        w.push(&w.mallory, "list.set", &list, json!({"name": "Forged"})),
        w.push(&w.mallory, "list.add", &list, json!({"member_id": rin})),
        w.push(&w.mallory, "stage.save", &stage, json!({"name": "Forged"})),
        w.push(&w.mallory, "bucket.delete", &bucket, json!({})),
        w.push(&w.mallory, "feed.delete", &feed, json!({})),
    ];
    println!("{tries:?}");
    assert!(tries.into_iter().all(|e| w.refused(e)), "Mallory changed one of Alice's rows");
}

#[test]
fn a16_a_channel_or_membership_stays_in_its_own_space() {
    let w = World::new();
    let theirs = w.space(&w.alice);
    let mine = w.space(&w.mallory);
    let channel = w.id();
    let create = json!({"space_id": theirs, "kind": "text", "name": "private"});
    assert_eq!(w.push_in(&w.alice, &format!("space:{theirs}"), "channel.create", &channel, create), None, "setup");
    let renamed = w.push_in(&w.mallory, &format!("space:{mine}"), "channel.set", &channel, json!({"name": "Forged"}));
    let joined =
        w.push_in(&w.mallory, &format!("space:{mine}"), "space.join", &theirs, json!({"account_id": w.mallory}));
    println!("channel.set {renamed:?}; space.join {joined:?}");
    assert!(w.refused(renamed) && w.refused(joined));
    let name: String = w.c.query_row("SELECT name FROM channel WHERE id=?1", [&channel], |r| r.get(0)).unwrap();
    assert_eq!(name, "private");
    let n: i64 =
        w.c.query_row(
            "SELECT count(*) FROM space_member WHERE space_id=?1 AND account_id=?2",
            params![theirs, w.mallory],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(n, 0, "Mallory is listed in Alice's space");
}

#[test]
fn a17_nobody_else_can_send_someones_private_file_in_a_message() {
    let w = World::new();
    let theirs = w.space(&w.alice);
    let attachment = w.id();
    let file = json!({"blob_hash": "b".repeat(64), "filename": "x.png", "mime": "image/png", "size": 10});
    assert_eq!(w.push_in(&w.alice, &format!("space:{theirs}"), "attachment.create", &attachment, file), None);
    let mine = w.space(&w.mallory);
    let channel = w.id();
    let create = json!({"space_id": mine, "kind": "text", "name": "open"});
    assert_eq!(w.push_in(&w.mallory, &format!("space:{mine}"), "channel.create", &channel, create), None, "setup");
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let msg =
        json!({"channel_id": channel, "authors": [rin], "text": "look", "entities": [], "attachments": [attachment]});
    let refused = w.push_in(&w.mallory, &format!("space:{mine}"), "message.send", &w.id(), msg);
    println!("ack: {refused:?}");
    assert!(w.refused(refused), "Mallory's message carries Alice's attachment");
}

#[test]
fn a18_naming_a_stored_file_takes_having_it() {
    let w = World::new();
    let hash = "c".repeat(64);
    w.stored_blob(&hash, &w.alice);
    let file = json!({"blob_hash": hash, "filename": "x.png", "mime": "image/png", "size": 10});
    let held = w.push(&w.mallory, "post.attachment", &w.id(), file.clone());
    println!("ack: {held:?}");
    assert!(held.as_deref().is_some_and(|e| e.starts_with("blob_pending")), "held until the bytes are sent");
    let avatar = w.push(&w.mallory, "member.create", &w.id(), json!({"name": "Rin", "avatar_blob": hash}));
    assert!(w.refused(avatar), "an avatar naming Alice's file");
    assert_eq!(w.push(&w.alice, "post.attachment", &w.id(), file.clone()), None, "the uploader may");
    // having sent the same bytes (blobs.rs `prove`) makes the file Mallory's to name too
    w.c.execute(
        "INSERT INTO blob_holder(hash, account_id, received, matches, is_complete) VALUES (?1, ?2, 10, 1, 1)",
        params![hash, w.mallory],
    )
    .unwrap();
    assert_eq!(w.push(&w.mallory, "post.attachment", &w.id(), file), None, "a holder may");
    let fresh = json!({"blob_hash": "d".repeat(64), "filename": "y.png", "mime": "image/png", "size": 10});
    assert_eq!(w.push(&w.mallory, "post.attachment", &w.id(), fresh), None, "a file still on its way passes");
}

#[test]
fn a19_an_edit_or_send_cannot_inject_managed_columns_or_move_a_message() {
    let w = World::new();
    let space = w.space(&w.alice);
    let scope = format!("space:{space}");
    ingest::grant(&w.c, &w.mallory, &scope).unwrap();
    assert_eq!(w.push_in(&w.alice, &scope, "space.join", &space, json!({"account_id": w.mallory})), None);
    let (ch1, ch2) = (w.id(), w.id());
    for ch in [&ch1, &ch2] {
        assert_eq!(
            w.push_in(&w.alice, &scope, "channel.create", ch, json!({"space_id": space, "kind": "text", "name": "c"})),
            None
        );
    }
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let msg = w.id();
    assert_eq!(
        w.push_in(
            &w.mallory,
            &scope,
            "message.send",
            &msg,
            json!({
                "channel_id": ch1,
                "authors": [rin],
                "text": "hi",
                "entities": [],
                "account_id": w.alice,
                "pinned_at": 12345
            })
        ),
        None
    );
    assert_eq!(
        w.push_in(
            &w.mallory,
            &scope,
            "message.edit",
            &msg,
            json!({
                "message_id": msg,
                "text": "moved?",
                "entities": [],
                "account_id": w.alice,
                "channel_id": ch2,
                "pinned_at": 99999,
                "visibility": {"mode": "private"}
            })
        ),
        None
    );
    let (owner, channel, pinned, vis): (String, String, Option<i64>, Option<String>) =
        w.c.query_row("SELECT account_id, channel_id, pinned_at, visibility FROM message WHERE id = ?1", [&msg], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
        })
        .unwrap();
    assert_eq!(owner, w.mallory, "an edit or send cannot forge message ownership");
    assert_eq!(channel, ch1, "an edit cannot move a message to another channel");
    assert_eq!(pinned, None, "payloads cannot inject pinned_at");
    assert_eq!(vis, None, "an edit cannot mutate message visibility");
}

#[test]
fn a20_a_relationship_cannot_name_or_read_another_accounts_relationship_type() {
    let w = World::new();
    let rt = w.id();
    assert_eq!(w.push(&w.alice, "reltype.set", &rt, json!({"name": "SecretBond", "inverse_name": "Secret"})), None);
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let kai = w.member(&w.alice, "Kai", json!({"mode": "server"}));
    let rel = w.id();
    let refused = w.push(
        &w.mallory,
        "relationship.set",
        &rel,
        json!({"from_member_id": rin, "to_kind": "member", "to_id": kai, "type_id": rt}),
    );
    assert!(w.refused(refused), "Mallory named Alice's relationship_type");
    let foreign_from = w.push(
        &w.mallory,
        "relationship.set",
        &w.id(),
        json!({"from_member_id": kai, "to_kind": "external", "to_label": "X"}),
    );
    assert!(w.refused(foreign_from), "Mallory named Alice's member as from_member_id");
}

#[test]
fn a21_private_asides_and_threads_refuse_foreign_reactions_threads_and_forwards() {
    let w = World::new();
    let space = w.space(&w.alice);
    let scope = format!("space:{space}");
    ingest::grant(&w.c, &w.mallory, &scope).unwrap();
    assert_eq!(w.push_in(&w.alice, &scope, "space.join", &space, json!({"account_id": w.mallory})), None);
    let ch = w.id();
    assert_eq!(
        w.push_in(&w.alice, &scope, "channel.create", &ch, json!({"space_id": space, "kind": "text", "name": "c"})),
        None
    );
    let kai = w.member(&w.alice, "Kai", json!({"mode": "server"}));
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let aside = w.id();
    assert_eq!(
        w.push_in(
            &w.alice,
            &scope,
            "message.send",
            &aside,
            json!({"channel_id": ch, "authors": [kai], "text": "private note", "entities": [], "visibility": {"mode": "private"}})
        ),
        None
    );
    let thread = w.id();
    assert_eq!(
        w.push_in(
            &w.alice,
            &scope,
            "channel.create",
            &thread,
            json!({"space_id": space, "kind": "thread", "name": "T", "parent_message_id": aside})
        ),
        None
    );
    let in_thread = w.id();
    assert_eq!(
        w.push_in(
            &w.alice,
            &scope,
            "message.send",
            &in_thread,
            json!({"channel_id": thread, "authors": [kai], "text": "inside private thread", "entities": []})
        ),
        None
    );
    let tries = [
        w.push_in(
            &w.mallory,
            &scope,
            "reaction.add",
            &aside,
            json!({"target_type": "message", "target_id": aside, "emoji": "👀", "member_id": rin}),
        ),
        w.push_in(
            &w.mallory,
            &scope,
            "channel.create",
            &w.id(),
            json!({"space_id": space, "kind": "thread", "name": "Spy", "parent_message_id": aside}),
        ),
        w.push_in(
            &w.mallory,
            &scope,
            "message.forward",
            &w.id(),
            json!({"channel_id": ch, "authors": [rin], "text": "", "entities": [], "forward_of_id": aside}),
        ),
        w.push_in(
            &w.mallory,
            &scope,
            "message.send",
            &w.id(),
            json!({"channel_id": thread, "authors": [rin], "text": "intrude", "entities": []}),
        ),
        w.push_in(
            &w.mallory,
            &scope,
            "reaction.add",
            &in_thread,
            json!({"target_type": "message", "target_id": in_thread, "emoji": "👀", "member_id": rin}),
        ),
    ];
    assert!(tries.into_iter().all(|e| w.refused(e)), "Mallory interacted with Alice's private aside or thread");
}

#[test]
fn a22_private_posts_refuse_foreign_reposts_quotes_highlights_and_space_reactions() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "private"}));
    let secret = w.post(&w.alice, &[&kai], "secret entry", json!({"mode": "private"}));
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let mine = w.space(&w.mallory);
    let tries = [
        w.push(
            &w.mallory,
            "post.create",
            &w.id(),
            json!({"kind": "note", "authors": [rin], "text": "", "entities": [], "repost_of": secret, "visibility": {"mode": "server"}}),
        ),
        w.push(
            &w.mallory,
            "post.create",
            &w.id(),
            json!({"kind": "note", "authors": [rin], "text": "q", "entities": [], "quote": {"post_id": secret}, "visibility": {"mode": "server"}}),
        ),
        w.push(&w.mallory, "highlight.add", &w.id(), json!({"profile_member_id": rin, "post_id": secret})),
        w.push_in(
            &w.mallory,
            &format!("space:{mine}"),
            "reaction.add",
            &secret,
            json!({"target_type": "post", "target_id": secret, "emoji": "👀", "member_id": rin}),
        ),
    ];
    assert!(tries.into_iter().all(|e| w.refused(e)), "Mallory referenced or reacted to Alice's private post");
}

#[test]
fn a23_an_open_restore_window_still_refuses_cross_account_writes() {
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "private"}));
    let secret = w.post(&w.alice, &[&kai], "secret", json!({"mode": "private"}));
    db::set_meta(&w.c, "restore_open", "1").unwrap();
    let mut o = Op {
        id: w.id(),
        kind: "member.set".into(),
        v: 1,
        scope: format!("account:{}", w.mallory),
        entity_id: Some(kai),
        hlc: Hlc::new(NOW as u64 + 50_000, 0, 1),
        device_at: NOW,
        tz_offset_min: 0,
        mono: None,
        boot_id: None,
        time_source: TimeSource::Auto,
        seen_seq: 0,
        member_id: None,
        payload: json!({"name": "Hijacked"}),
        seq: None,
        account_id: Some(w.mallory.clone()),
        device_id: Some("dev".into()),
        occurred_at: Some(NOW),
        received_at: Some(NOW),
    };
    let s = ingest::Session {
        account_id: w.mallory.clone(),
        device_id: "dev".into(),
        sample: ClockSample { server_time: NOW, mono: None, boot_id: None, offset_ms: 0 },
    };
    let (ack1, _) = ingest::accept(&w.c, &s, o.clone(), NOW, true).unwrap();
    assert!(ack1.error.is_some(), "restore push must not cross scopes");
    o.id = w.id();
    o.kind = "post.edit".into();
    o.entity_id = Some(secret.clone());
    o.payload = json!({"post_id": secret, "text": "overwritten", "entities": []});
    let (ack2, _) = ingest::accept(&w.c, &s, o, NOW, true).unwrap();
    assert!(ack2.error.is_some(), "restore push must not edit another account's post");
}

#[test]
fn a24_a_relationship_set_without_a_target_is_answered_not_an_error() {
    // found by ingest_fuzz: the first empty set upserts a row whose to_kind is empty
    let w = World::new();
    let rel = w.id();
    assert_eq!(w.push(&w.mallory, "relationship.set", &rel, json!({})), None);
    assert_eq!(w.push(&w.mallory, "relationship.set", &rel, json!({})), None);
}

#[test]
fn a25_a_stranger_who_opts_in_still_gets_no_read_receipts_from_a_space_they_are_not_in() {
    // D-131: `share_reads` is only half of the gate; the asker must reach the channel too
    let w = World::new();
    w.c.execute("INSERT INTO space(id,kind,owner_account_id,created_at) VALUES ('sp','shared',?1,0)", [&w.alice])
        .unwrap();
    w.c.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('ch','sp','text',0)", []).unwrap();
    ingest::grant(&w.c, &w.alice, "space:sp").unwrap();
    for who in [&w.alice, &w.mallory] {
        let id = w.id();
        assert_eq!(w.push(who, "pref.set", &id, json!({"device": "", "key": "share_reads", "value": true})), None);
    }
    let asked = chorus_server::receipts::reads(&w.c, &chorus_server::api_data::Principal::owner(&w.mallory), "ch");
    assert!(asked.unwrap().is_none(), "Mallory is told the channel isn't there");
}

#[test]
fn a26_a_member_state_names_only_our_own_members_and_reaches_no_one_outside_a_reveal() {
    // D-138, D-119: a state is about one of the account's own members, its audience is one the
    // server understands, and the member endpoints never carry it (D-101: no fronting data)
    let w = World::new();
    let kai = w.member(&w.alice, "Kai", json!({"mode": "followers"}));
    w.follow(&w.mallory, &w.alice);
    let state = json!({"tags": ["tired"], "note": "Typing only", "at": NOW, "clear_on_leave": false,
                       "audience": {"mode": "followers"}});
    let key = format!("member_status:{kai}");
    let id = w.id();
    let refused = w.push(&w.mallory, "pref.set", &id, json!({"device": "", "key": key, "value": state}));
    assert!(refused.is_some(), "Mallory set a state on Alice's member");
    let id = w.id();
    let odd = json!({"tags": ["tired"], "at": NOW, "audience": {"mode": "everyone"}});
    let refused = w.push(&w.alice, "pref.set", &id, json!({"device": "", "key": key, "value": odd}));
    assert!(refused.is_some(), "a state went in with an audience the server can't check");
    let id = w.id();
    assert_eq!(w.push(&w.alice, "pref.set", &id, json!({"device": "", "key": key, "value": state})), None);
    let listed = profiles::members(&w.c, &w.mallory, &w.alice).unwrap().to_string();
    let profile = profiles::profile(&w.c, &w.mallory, &w.alice, &kai).unwrap().unwrap().to_string();
    for shown in [listed, profile] {
        assert!(!shown.contains("Typing only") && !shown.contains("tired"), "a state leaked: {shown}");
    }
}

/// Alice's shared space with a general channel, Mallory in it as a member; Mallory follows Bob.
fn shared_with_mallory(w: &World) -> (String, String, String) {
    let space = w.space(&w.alice);
    let scope = format!("space:{space}");
    for who in [&w.alice, &w.mallory] {
        assert_eq!(w.push_in(&w.alice, &scope, "space.join", &space, json!({"account_id": who})), None, "setup: join");
    }
    let ch = w.id();
    let made =
        w.push_in(&w.alice, &scope, "channel.create", &ch, json!({"space_id": space, "kind": "text", "name": "c"}));
    assert_eq!(made, None, "setup: channel");
    (space, scope, ch)
}

#[test]
fn a27_a_member_without_invite_brings_no_one_in_and_permissions_gate_sends() {
    // D-133: inviting is off unless the space allows it, through REST and through a forged op;
    // files, polls and asides each need their own permission; only admins set them
    let w = World::new();
    let (space, scope, ch) = shared_with_mallory(&w);
    w.follow(&w.bob, &w.mallory);
    let added = chorus_server::spaces::add(&w.c, &w.mallory, &space, std::slice::from_ref(&w.bob), NOW);
    assert!(added.is_err(), "Mallory added Bob without the invite permission");
    let forged = w.push_in(&w.mallory, &scope, "space.join", &space, json!({"account_id": w.bob}));
    assert!(w.refused(forged), "Mallory's forged join brought Bob in");
    let grant = json!({"target_type": "role", "target_id": "everyone", "allow": ["invite"]});
    assert!(w.refused(w.push_in(&w.mallory, &scope, "space.set_permission", &space, grant.clone())));
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_permission", &space, grant), None);
    let stranger = new_id(1, [4; 10]);
    w.c.execute("INSERT INTO account(id, kind, handle, created_at) VALUES (?1, 'system', 'eve', 0)", [&stranger])
        .unwrap();
    let forged = w.push_in(&w.mallory, &scope, "space.join", &space, json!({"account_id": stranger}));
    assert!(w.refused(forged), "invite still only reaches people the inviter is connected to");
    assert!(chorus_server::spaces::add(&w.c, &w.mallory, &space, std::slice::from_ref(&w.bob), NOW).is_ok());
    assert!(ingest::can_access(&w.c, &w.bob, &scope).unwrap(), "an invited account gets the space");
    // files, polls and asides refused one by one
    let deny = json!({"target_type": "role", "target_id": "everyone", "deny": ["attach", "poll", "aside"]});
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_permission", &space, deny), None);
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let file = w.id();
    let send = |extra: Value| {
        let mut p = json!({"channel_id": ch, "authors": [rin], "text": "hi", "entities": []});
        for (k, v) in extra.as_object().unwrap() {
            p[k] = v.clone();
        }
        w.push_in(&w.mallory, &scope, "message.send", &w.id(), p)
    };
    assert!(w.refused(send(json!({"attachments": [file]}))), "a file went in");
    let poll = json!({"options": [{"id": "a", "text": "A"}, {"id": "b", "text": "B"}]});
    assert!(w.refused(send(json!({"poll": poll}))), "a poll went in");
    assert!(w.refused(send(json!({"visibility": {"mode": "system_only"}}))), "an aside went in");
    assert_eq!(send(json!({})), None, "plain messages still go");
    // what Mallory shares is Mallory's own business, but only where Mallory is
    assert_eq!(w.push_in(&w.mallory, &scope, "space.set_sharing", &space, json!({"member_names": false})), None);
    let elsewhere = w.space(&w.alice);
    let theirs = format!("space:{elsewhere}");
    ingest::grant(&w.c, &w.mallory, &theirs).unwrap();
    assert!(w.refused(w.push_in(&w.mallory, &theirs, "space.set_sharing", &elsewhere, json!({"fronts": true}))));
}

#[test]
fn a28_history_from_before_joining_stays_unreadable_by_every_path() {
    // D-133: with `history` denied, a member reads only messages the server received since its
    // latest join: not through REST, search, files, catch-up, the digest, reactions, threads, poll
    // tallies or read receipts, and it can't act on them either
    use chorus_server::{api_data::Principal, messages, oplog, receipts, search, search_view, visibility};
    let w = World::new();
    let space = w.space(&w.alice);
    let scope = format!("space:{space}");
    assert_eq!(w.push_in(&w.alice, &scope, "space.join", &space, json!({"account_id": w.alice})), None);
    let ch = w.id();
    let made =
        w.push_in(&w.alice, &scope, "channel.create", &ch, json!({"space_id": space, "kind": "text", "name": "c"}));
    assert_eq!(made, None);
    let deny = json!({"target_type": "role", "target_id": "everyone", "deny": ["history"]});
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_permission", &space, deny), None);
    let kai = w.member(&w.alice, "Kai", json!({"mode": "server"}));
    // before Mallory joins: a message with a file and a poll, a vote, a thread under it
    let file = w.id();
    let hash = "ab".repeat(32);
    let att = json!({"blob_hash": hash, "filename": "old.png", "mime": "image/png", "size": 10});
    assert_eq!(w.push_in(&w.alice, &scope, "attachment.create", &file, att), None);
    let old = w.id();
    let poll = json!({"options": [{"id": "a", "text": "Soup"}, {"id": "b", "text": "Rice"}]});
    let send = json!({"channel_id": ch, "authors": [kai], "text": "violet secret from before", "entities": [],
                      "attachments": [file], "poll": poll});
    assert_eq!(w.push_in(&w.alice, &scope, "message.send", &old, send), None);
    assert_eq!(w.push(&w.alice, "poll.vote", &old, json!({"member_id": kai, "choices": ["a"]})), None);
    let thread = w.id();
    let t = json!({"space_id": space, "kind": "thread", "name": "T", "parent_message_id": old});
    assert_eq!(w.push_in(&w.alice, &scope, "channel.create", &thread, t), None);
    // Mallory joins, on the server's clock between the pushes before and after
    w.follow(&w.mallory, &w.alice);
    let joined_now = NOW + w.n.get() as i64 * 1000 + 500;
    let added = chorus_server::spaces::add(&w.c, &w.alice, &space, std::slice::from_ref(&w.mallory), joined_now);
    assert!(added.is_ok());
    let joined: i64 =
        w.c.query_row(
            "SELECT joined_at FROM space_member WHERE space_id=?1 AND account_id=?2",
            params![space, w.mallory],
            |r| r.get(0),
        )
        .unwrap();
    let old_at: i64 = w.c.query_row("SELECT received_at FROM message WHERE id=?1", [&old], |r| r.get(0)).unwrap();
    assert!(old_at < joined, "setup: the old message came first");
    // after: a reaction and a thread reply on the old message, a new vote, and a new message
    let react = json!({"target_type": "message", "target_id": old, "emoji": "💜", "member_id": kai});
    assert_eq!(w.push_in(&w.alice, &scope, "reaction.add", &old, react), None);
    let reply = w.id();
    let r = json!({"channel_id": thread, "authors": [kai], "text": "violet reply in the old thread", "entities": []});
    assert_eq!(w.push_in(&w.alice, &scope, "message.send", &reply, r), None);
    assert_eq!(w.push(&w.alice, "poll.vote", &old, json!({"member_id": kai, "choices": ["b"]})), None);
    let new = w.id();
    let n = json!({"channel_id": ch, "authors": [kai], "text": "violet hello after", "entities": []});
    assert_eq!(w.push_in(&w.alice, &scope, "message.send", &new, n), None);

    let p = Principal::owner(&w.mallory);
    // REST
    assert!(messages::one(&w.c, &p, &old).unwrap().is_none(), "REST read an old message");
    assert!(messages::one(&w.c, &p, &reply).unwrap().is_none(), "REST read a reply under an old message");
    assert!(messages::one(&w.c, &p, &new).unwrap().is_some());
    let page = messages::list(&w.c, &p, &ch, &messages::Page::default()).unwrap().unwrap().to_string();
    assert!(!page.contains(&old) && page.contains(&new), "the channel page showed history: {page}");
    let opened = messages::thread(&w.c, &p, &old, &messages::Page::default()).unwrap();
    assert!(opened.is_none_or(|t| !t.to_string().contains("violet reply")), "the old thread opened");
    // search, and the file rule (blobs::can_read reads message attachments through this view)
    let q = search::MessageQuery { q: "violet".into(), limit: Some(50), ..Default::default() };
    let hits = search::messages(&w.c, &p, &q).unwrap().to_string();
    assert!(!hits.contains("secret") && !hits.contains("old thread") && hits.contains("hello after"), "{hits}");
    assert!(search::message_by_id(&w.c, &p, &old).unwrap().is_none());
    let mut view = search_view::CurrentView::new(&w.c, &p).unwrap();
    assert!(!view.message(&old).unwrap(), "the old message's file would be readable");
    // catch-up and the digest
    let log = oplog::scope_after(&w.c, &scope, 0, 10_000).unwrap();
    let mut digest = chorus_core::sync::Digest::default();
    for o in &log {
        let shown = visibility::op_visible_to(&w.c, &w.mallory, o).unwrap();
        if shown {
            digest.add(&o.id);
        }
        let about_old = [&old, &file, &thread, &reply].iter().any(|id| o.entity() == Some(id.as_str()));
        assert!(!(shown && about_old), "catch-up sent {} about the old message", o.kind);
    }
    assert!(log.iter().any(|o| o.kind == "message.poll_tally"), "setup: tallies were published");
    assert_eq!(visibility::visible_digest(&w.c, &w.mallory, &scope).unwrap(), digest, "the digest disagrees");
    // acting on it: no reaction, reply, thread or vote
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let tries = [
        w.push_in(
            &w.mallory,
            &scope,
            "reaction.add",
            &old,
            json!({"target_type": "message", "target_id": old, "emoji": "👀", "member_id": rin}),
        ),
        w.push_in(
            &w.mallory,
            &scope,
            "message.send",
            &w.id(),
            json!({"channel_id": ch, "authors": [rin], "text": "re", "entities": [], "reply_to": old}),
        ),
        w.push_in(
            &w.mallory,
            &scope,
            "channel.create",
            &w.id(),
            json!({"space_id": space, "kind": "thread", "name": "Spy", "parent_message_id": old}),
        ),
        w.push(&w.mallory, "poll.vote", &old, json!({"member_id": rin, "choices": ["a"]})),
    ];
    assert!(tries.iter().all(Option::is_some), "Mallory acted on a message from before she joined: {tries:?}");
    // read receipts never name it: both opt in, Mallory reads up to her own later message
    for who in [&w.alice, &w.mallory] {
        let id = w.id();
        assert_eq!(w.push(who, "pref.set", &id, json!({"device": "", "key": "share_reads", "value": true})), None);
    }
    let mine = w.id();
    let m = json!({"channel_id": ch, "authors": [rin], "text": "hi", "entities": []});
    assert_eq!(w.push_in(&w.mallory, &scope, "message.send", &mine, m), None);
    let at: i64 = w.c.query_row("SELECT occurred_at FROM message WHERE id=?1", [&mine], |r| r.get(0)).unwrap();
    let mark = json!({"channel_id": ch, "message_id": mine, "message_at": at, "reader_member_id": ""});
    assert_eq!(w.push_in(&w.mallory, &scope, "read.mark", &w.id(), mark), None);
    let reads = receipts::reads(&w.c, &Principal::owner(&w.alice), &ch).unwrap().unwrap().to_string();
    assert!(!reads.contains(&old), "a receipt named a message Mallory can't read: {reads}");
    assert!(reads.contains(&new), "the receipt names the latest message Mallory can read: {reads}");
    // with history allowed, it all comes back
    let allow = json!({"target_type": "role", "target_id": "everyone", "allow": ["history"]});
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_permission", &space, allow), None);
    assert!(messages::one(&w.c, &p, &old).unwrap().is_some());
}

#[test]
fn a29_member_names_withheld_in_a_space_reach_no_other_account() {
    // D-133: with "member names on each message" off, other accounts in the space get no name,
    // avatar or card for our members: not in author cards, search, files, notifications or typing
    use chorus_server::{api_data::Principal, search, spaces};
    let w = World::new();
    let (space, scope, ch) = shared_with_mallory(&w);
    let avatar = "cd".repeat(32);
    let kai = w.id();
    let made = w.push(
        &w.alice,
        "member.create",
        &kai,
        json!({"name": "Kairos", "avatar_blob": avatar, "visibility": {"mode": "server"}}),
    );
    assert_eq!(made, None);
    let rin = w.member(&w.mallory, "Rin", json!({"mode": "server"}));
    let theirs = w.id();
    let m = json!({"channel_id": ch, "authors": [rin], "text": "violet question", "entities": []});
    assert_eq!(w.push_in(&w.mallory, &scope, "message.send", &theirs, m), None);
    let ours = w.id();
    let send = json!({"channel_id": ch, "authors": [kai], "text": "violet answer", "entities": [], "reply_to": theirs});
    let cards = |who: &str| spaces::authors(&w.c, who, &space).unwrap()["members"].to_string();
    // on (the default): Mallory sees Kairos
    assert_eq!(w.push_in(&w.alice, &scope, "message.send", &ours, send), None);
    assert!(cards(&w.mallory).contains("Kairos"), "setup: names show by default");
    // off: Alice's own choice, nobody else's
    let off = json!({"member_names": false});
    let bad = json!({"member_names": true, "fronts": "x"});
    assert!(w.push_in(&w.mallory, &scope, "space.set_sharing", &space, bad).is_some(), "a malformed setting went in");
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_sharing", &space, off), None);
    assert!(!cards(&w.mallory).contains("Kairos"), "an author card named a withheld member");
    assert!(!spaces::author_avatar_visible(&w.c, &w.mallory, &avatar).unwrap(), "the member's avatar stayed readable");
    let p = Principal::owner(&w.mallory);
    let q = search::MessageQuery { q: "violet".into(), limit: Some(50), ..Default::default() };
    let hits = search::messages(&w.c, &p, &q).unwrap().to_string();
    assert!(hits.contains("violet answer") && !hits.contains("Kairos"), "search named the member: {hits}");
    // a new reply notifies Mallory as the system, not as Kairos
    let again = w.id();
    let send = json!({"channel_id": ch, "authors": [kai], "text": "violet again", "entities": [], "reply_to": theirs});
    assert_eq!(w.push_in(&w.alice, &scope, "message.send", &again, send), None);
    let pushed: Vec<String> =
        w.c.prepare("SELECT payload FROM notification WHERE recipient_account_id = ?1 AND source_op_id IS NOT NULL")
            .unwrap()
            .query_map([&w.mallory], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
    let last = pushed.iter().find(|n| n.contains("violet again")).expect("the reply notified Mallory");
    assert!(!last.contains("Kairos") && !last.contains(&avatar) && last.contains("alice"), "{last}");
}

#[test]
fn a30_a_space_sees_our_front_only_when_shared_only_after_its_delay_and_only_while_in_it() {
    // D-133, NOTIFICATIONS §5 rule 11: the fronting status shared with a space is its own
    // delayed reveal: never early, never to non-members, never a hidden member, withdrawn at once
    use chorus_server::space_fronts;
    let w = World::new();
    let (space, scope, _) = shared_with_mallory(&w);
    let kai = w.member(&w.alice, "Kairos", json!({"mode": "server"}));
    let shy = w.id();
    let made =
        w.push(&w.alice, "member.create", &shy, json!({"name": "Shyla", "notify_policy": {"announce": "nobody"}}));
    assert_eq!(made, None);
    let switch = |who: &[&str]| {
        let entries: Vec<Value> = who
            .iter()
            .enumerate()
            .map(|(i, m)| json!({"subject_type": "member", "subject_id": m, "level": "front", "is_primary": i == 0}))
            .collect();
        assert_eq!(w.push(&w.alice, "front.switch", &w.id(), json!({"entries": entries})), None);
        NOW + w.n.get() as i64 * 1000 // the server's clock when it was accepted
    };
    let seen = |viewer: &str| space_fronts::list(&w.c, viewer, &space).map(|v| v["items"].to_string());
    switch(&[&kai, &shy]);
    space_fronts::process_due(&w.c, i64::MAX / 2).unwrap();
    assert_eq!(seen(&w.mallory).unwrap(), "[]", "shared before it was switched on");
    // on, with a 10-minute delay: nothing until then
    let on = json!({"fronts": true, "fronts_delay_min": 10});
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_sharing", &space, on), None);
    let accepted = NOW + w.n.get() as i64 * 1000;
    let due: i64 =
        w.c.query_row(
            "SELECT due_at FROM space_front_view WHERE space_id=?1 AND account_id=?2",
            params![space, w.alice],
            |r| r.get(0),
        )
        .unwrap();
    assert!(due >= accepted + 15_000 + 600_000, "due {} ms after it was turned on", due - accepted);
    space_fronts::process_due(&w.c, due - 1).unwrap();
    assert_eq!(seen(&w.mallory).unwrap(), "[]", "revealed before its delay");
    space_fronts::process_due(&w.c, due).unwrap();
    let shown = seen(&w.mallory).unwrap();
    assert!(shown.contains("Kairos") && !shown.contains("Shyla") && !shown.contains(&shy), "{shown}");
    assert!(!shown.contains("since") && !shown.contains("at\""), "no times leave: {shown}");
    assert!(seen(&w.bob).is_err(), "a non-member saw the space's fronts");
    assert_eq!(seen(&w.alice).unwrap(), "[]", "an account isn't shown its own");
    // a switch: the old front stays until the new reveal is due
    let at = switch(&[&shy]);
    let due: i64 =
        w.c.query_row(
            "SELECT due_at FROM space_front_view WHERE space_id=?1 AND account_id=?2",
            params![space, w.alice],
            |r| r.get(0),
        )
        .unwrap();
    assert!(due >= at + 615_000);
    space_fronts::process_due(&w.c, due - 1).unwrap();
    assert!(seen(&w.mallory).unwrap().contains("Kairos"), "the switch showed early");
    space_fronts::process_due(&w.c, due).unwrap();
    assert!(!seen(&w.mallory).unwrap().contains("Kairos"));
    // off: withdrawn at once; on again: nothing until a new reveal
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_sharing", &space, json!({"fronts": false})), None);
    assert_eq!(seen(&w.mallory).unwrap(), "[]", "still shown after switching it off");
    assert_eq!(w.push_in(&w.alice, &scope, "space.set_sharing", &space, json!({"fronts": true})), None);
    assert_eq!(seen(&w.mallory).unwrap(), "[]", "an old view came back at once");
    space_fronts::process_due(&w.c, i64::MAX / 2).unwrap();
    assert!(seen(&w.mallory).unwrap().contains(&w.alice));
    // once Mallory leaves she sees nothing; back in, she sees it again
    chorus_server::spaces::leave(&w.c, &w.mallory, &space, NOW + w.n.get() as i64 * 1000 + 500).unwrap();
    assert!(seen(&w.mallory).is_err(), "a former member still sees the space's fronts");
    assert_eq!(w.push_in(&w.alice, &scope, "space.join", &space, json!({"account_id": w.mallory})), None);
    assert!(seen(&w.mallory).unwrap().contains(&w.alice));
}

#[test]
fn a31_another_account_cannot_take_tick_see_or_be_given_our_handover() {
    // D-132: Handover and Notes are the system's alone: no guest grant, no join, no ack or tick
    // from another account by any scope, and none of it syncs to anyone else
    let w = World::new();
    let home = w.id();
    let scope = format!("space:{home}");
    ingest::grant(&w.c, &w.alice, &scope).unwrap();
    for (kind, entity, p) in [
        ("space.create", home.clone(), json!({"kind": "internal", "name": "Home"})),
        ("space.join", home.clone(), json!({"account_id": w.alice})),
    ] {
        ingest::server_op(&w.c, &w.alice, kind, &scope, Some(&entity), p, NOW).unwrap();
    }
    let handover = w.id();
    let made = json!({"space_id": home, "kind": "text", "name": "Handover", "special": "handover"});
    ingest::server_op(&w.c, &w.alice, "channel.create", &scope, Some(&handover), made, NOW).unwrap();
    let amy = w.member(&w.alice, "Amarynthe", json!({"mode": "private"}));
    let msg = w.id();
    let send = json!({"channel_id": handover, "authors": [amy], "text": "Keys by the door", "entities": [],
                      "handover": {"deliver": "now", "checklist": [{"id": "a", "text": "Keys"}]}});
    assert_eq!(w.push_in(&w.alice, &scope, "message.send", &msg, send), None, "setup: handover");
    let mal = w.member(&w.mallory, "Mal", json!({"mode": "private"}));
    let ack = json!({"message_id": msg, "member_id": mal});
    let tick = json!({"message_id": msg, "item_id": "a", "done": true, "member_id": mal});
    // in her own scope, in Alice's own space, and in a space she shares with Alice
    assert!(w.push(&w.mallory, "handover.ack", &msg, ack.clone()).is_some());
    assert!(w.refused(w.push_in(&w.mallory, &scope, "handover.ack", &msg, ack.clone())));
    assert!(w.refused(w.push_in(&w.mallory, &scope, "checklist.set", &msg, tick.clone())));
    let (_, shared_scope, _) = shared_with_mallory(&w);
    assert!(w.refused(w.push_in(&w.mallory, &shared_scope, "handover.ack", &msg, ack)));
    assert!(w.refused(w.push_in(&w.mallory, &shared_scope, "checklist.set", &msg, tick)));
    // as one of Alice's members, from the shared space
    let as_amy = json!({"message_id": msg, "member_id": amy});
    assert!(w.refused(w.push_in(&w.mallory, &shared_scope, "handover.ack", &msg, as_amy)));
    let n: i64 = w.c.query_row("SELECT count(*) FROM handover_ack", [], |r| r.get(0)).unwrap();
    assert_eq!(n, 0, "someone else took Alice's handover");
    // Alice can't share it out either: no guest grant, no join into her own space
    let guest = json!({"target_type": "account", "target_id": w.mallory, "allow": ["view"], "deny": []});
    assert!(w.refused(w.push_in(&w.alice, &scope, "channel.set_permission", &handover, guest)));
    assert!(w.refused(w.push_in(&w.alice, &scope, "space.join", &home, json!({"account_id": w.mallory}))));
    let access: i64 =
        w.c.query_row(
            "SELECT count(*) FROM scope_access WHERE account_id = ?1 AND scope = ?2",
            params![w.mallory, scope],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(access, 0, "Mallory got Alice's own space");
    // and were she let into the scope, nothing in the channel would sync to her (the space's own
    // name and kind go to anyone with the scope, as for any guest)
    let ops = chorus_server::oplog::scope_after(&w.c, &scope, 0, 1000).unwrap();
    for o in ops.iter().filter(|o| !o.kind.starts_with("space.")) {
        assert!(!chorus_server::visibility::op_visible_to(&w.c, &w.mallory, o).unwrap(), "{} reached Mallory", o.kind);
    }
}
