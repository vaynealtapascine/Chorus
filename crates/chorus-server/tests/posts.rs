//! Public journal reads must enforce the stored audience at request time.

use chorus_core::{
    hlc::Hlc,
    id::new_id,
    op::Op,
    time::{ClockSample, TimeSource},
};
use chorus_server::{api_data, app, auth, config::Config, db, ingest, posts};
use rusqlite::{Connection, params};
use serde_json::Value;
use serde_json::json;
mod common;

const ALICE: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const BOB: &str = "0192f8c2-0000-7000-8000-0000000000b2";
const CAROL: &str = "0192f8c2-0000-7000-8000-0000000000c3";
const BUCKET: &str = "0192f8c2-0000-7000-8000-0000000000d4";

fn seed() -> Connection {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let now = chorus_server::now_ms();
    for (id, label) in [(ALICE, "alice"), (BOB, "bob"), (CAROL, "carol")] {
        conn.execute("INSERT INTO account(id,kind,created_at) VALUES (?1,'person',0)", [id]).unwrap();
        conn.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at)
             VALUES (?1,?1,?1,?1,'cli','test',0)",
            [id],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
            params![auth::hash(&format!("{label}-session")), id, now, now + 86_400_000],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO member(id,account_id,name,created_at) VALUES (?1,?2,?3,0)",
            params![label, id, label],
        )
        .unwrap();
    }
    conn.execute(
        "INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at)
         VALUES ('follow',?1,?2,'active',0)",
        params![BOB, ALICE],
    )
    .unwrap();
    conn.execute("INSERT INTO bucket(id,account_id,name) VALUES (?1,?2,'Close')", params![BUCKET, ALICE]).unwrap();
    conn.execute(
        "INSERT INTO bucket_assignment(bucket_id,follower_account_id,added_hlc) VALUES (?1,?2,'1:0:1')",
        params![BUCKET, BOB],
    )
    .unwrap();
    for (id, owner, visibility, at, reply, deleted) in [
        ("private", ALICE, r#"{"mode":"private"}"#, 100, None, None),
        ("followers", ALICE, r#"{"mode":"followers"}"#, 110, None, None),
        (
            "bucket",
            ALICE,
            r#"{"mode":"buckets","bucket_ids":["0192f8c2-0000-7000-8000-0000000000d4"]}"#,
            120,
            None,
            None,
        ),
        ("server", ALICE, r#"{"mode":"server"}"#, 130, None, None),
        ("deleted", ALICE, r#"{"mode":"server"}"#, 140, None, Some(150)),
        ("malformed", ALICE, "not-json", 150, None, None),
        ("visible-reply", ALICE, r#"{"mode":"followers"}"#, 160, Some("server"), None),
        ("hidden-reply", ALICE, r#"{"mode":"private"}"#, 170, Some("server"), None),
        ("orphan", ALICE, r#"{"mode":"followers"}"#, 175, Some("private"), None),
        ("own-bob", BOB, r#"{"mode":"private"}"#, 180, None, None),
    ] {
        conn.execute(
            "INSERT INTO post(id,account_id,device_id,kind,text,visibility,occurred_at,reply_to_id,deleted_at,front_snapshot)
             VALUES (?1,?2,?2,'note',?1,?3,?4,?5,?6,'[{\"subject_id\":\"secret-front\"}]')",
            params![id, owner, visibility, at, reply, deleted],
        )
        .unwrap();
        let member = if owner == ALICE { "alice" } else { "bob" };
        conn.execute("INSERT INTO post_author(post_id,member_id,position) VALUES (?1,?2,0)", params![id, member])
            .unwrap();
    }
    conn.execute(
        "INSERT INTO attachment(id,account_id,blob_hash,thumb_blob_hash,filename,mime,size,alt_text,is_spoiler,created_at)
         VALUES ('a','0192f8c2-0000-7000-8000-0000000000a1','full-hash','thumb-hash','photo.png','image/png',42,'A picture',1,0)",
        [],
    )
    .unwrap();
    for id in ["server", "private"] {
        conn.execute(
            "INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position) VALUES ('post',?1,'a',0)",
            [id],
        )
        .unwrap();
    }
    for (id, emoji, member, added, removed) in [
        ("server", "💜", "bob", Some("2:0:1"), None),
        ("server", "👍", "carol", Some("2:0:1"), Some("3:0:1")),
        ("private", "💜", "bob", Some("2:0:1"), None),
    ] {
        conn.execute(
            "INSERT INTO reaction(target_type,target_id,emoji,member_id,added_hlc,removed_hlc)
             VALUES ('post',?1,?2,?3,?4,?5)",
            params![id, emoji, member, added, removed],
        )
        .unwrap();
    }
    conn
}

#[tokio::test]
async fn http_posts_enforce_audience_and_hide_front_snapshot() {
    let conn = seed();
    let token = api_data::create_token(
        &conn,
        &api_data::Principal::owner(BOB),
        "own data",
        &["read:members".into()],
        chorus_server::now_ms(),
    )
    .unwrap()["token"]
        .as_str()
        .unwrap()
        .to_string();
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("posts-http-test");
    let test_dir = cfg.server.data_dir.clone();
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/posts", listener.local_addr().unwrap());
    let server = state.clone();
    let server = tokio::spawn(async move { axum::serve(listener, app::router(server)).await.unwrap() });
    let client = reqwest::Client::new();
    let get = |auth: &str, suffix: &str| client.get(format!("{base}{suffix}")).bearer_auth(auth);

    let ids = |body: &Value| {
        body["items"].as_array().unwrap().iter().map(|p| p["id"].as_str().unwrap().to_string()).collect::<Vec<_>>()
    };
    let bob = get("bob-session", "").send().await.unwrap().json::<Value>().await.unwrap();
    assert_eq!(ids(&bob), ["own-bob", "orphan", "visible-reply", "server", "bucket", "followers"]);
    assert!(bob["items"][0].get("front_snapshot").is_none());
    assert!(bob["items"][1]["reply_to"].is_null());
    assert_eq!(bob["items"][1]["author_cards"][0]["name"], "alice");
    let shared = bob["items"].as_array().unwrap().iter().find(|p| p["id"] == "server").unwrap();
    assert_eq!(shared["attachments"][0]["blob_hash"], "full-hash");
    assert_eq!(shared["attachments"][0]["thumb_blob_hash"], "thumb-hash");
    assert_eq!(shared["attachments"][0]["alt_text"], "A picture");
    assert_eq!(shared["attachments"][0]["is_spoiler"], true);
    assert_eq!(shared["attachments"].as_array().unwrap().len(), 1);
    assert_eq!(shared["reactions"].as_array().unwrap().len(), 1);
    assert_eq!(shared["reactions"][0]["emoji"], "💜");
    assert_eq!(shared["reactions"][0]["member_name"], "bob");
    let carol = get("carol-session", "").send().await.unwrap().json::<Value>().await.unwrap();
    assert_eq!(ids(&carol), ["server"]);
    let alice = get("alice-session", "?account=0192f8c2-0000-7000-8000-0000000000a1")
        .send()
        .await
        .unwrap()
        .json::<Value>()
        .await
        .unwrap();
    assert_eq!(ids(&alice).len(), 8);
    let owner_server = alice["items"].as_array().unwrap().iter().find(|p| p["id"] == "server").unwrap();
    assert_eq!(owner_server["reactions"][0]["member_id"], "bob");
    assert_eq!(get("bob-session", "/private").send().await.unwrap().status(), 404);
    assert_eq!(get("carol-session", "/bucket").send().await.unwrap().status(), 404);
    assert_eq!(
        get("bob-session", "/server?depth=2").send().await.unwrap().json::<Value>().await.unwrap()["replies"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        get("alice-session", "/server?depth=2").send().await.unwrap().json::<Value>().await.unwrap()["replies"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
    assert_eq!(
        ids(&get("bob-session", "?author=alice&before=130&limit=1")
            .send()
            .await
            .unwrap()
            .json::<Value>()
            .await
            .unwrap()),
        ["bucket"]
    );
    assert_eq!(
        get("bob-session", "?kind=entry").send().await.unwrap().json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
    assert_eq!(client.get(&base).send().await.unwrap().status(), 401);
    // API tokens: read:posts, and only their own account's posts
    assert_eq!(get(&token, "").send().await.unwrap().status(), 403, "read:members isn't read:posts");
    let posts_token = {
        let conn = state.db.lock().unwrap();
        api_data::create_token(
            &conn,
            &api_data::Principal::owner(BOB),
            "journal",
            &["read:posts".into()],
            chorus_server::now_ms(),
        )
        .unwrap()["token"]
            .as_str()
            .unwrap()
            .to_string()
    };
    let own = get(&posts_token, "").send().await.unwrap().json::<Value>().await.unwrap();
    assert_eq!(ids(&own), ["own-bob"], "a token sees its own account's posts, not what the account may read");
    assert_eq!(
        ids(&get(&posts_token, &format!("?account={ALICE}")).send().await.unwrap().json::<Value>().await.unwrap()),
        Vec::<String>::new()
    );
    assert_eq!(get(&posts_token, "/server").send().await.unwrap().status(), 404);
    assert_eq!(get(&posts_token, "/own-bob").send().await.unwrap().status(), 200);

    let ended = seed();
    ended.execute("UPDATE follow SET status='ended' WHERE id='follow'", []).unwrap();
    let after = chorus_server::posts::list(&ended, BOB, &Default::default()).unwrap();
    assert_eq!(ids(&after), ["own-bob", "server"]);
    assert!(chorus_server::posts::one(&ended, BOB, "bucket").unwrap().is_none());
    server.abort();
    let _ = server.await;
    drop(client);
    common::release(state, &test_dir).await;
}

#[test]
fn post_reactions_require_an_owned_member_and_a_readable_existing_post() {
    let conn = seed();
    let scope = format!("account:{BOB}");
    ingest::grant(&conn, BOB, &scope).unwrap();
    let now = chorus_server::now_ms();
    let session = ingest::Session {
        account_id: BOB.into(),
        device_id: BOB.into(),
        sample: ClockSample { server_time: now, mono: None, boot_id: None, offset_ms: 0 },
    };
    let make = |serial: u8, target: &str, member: &str| Op {
        id: new_id(now as u64, [serial; 10]),
        kind: "post.react".into(),
        v: 1,
        scope: scope.clone(),
        entity_id: Some(new_id(now as u64, [serial + 20; 10])),
        hlc: Hlc::new(now as u64, 0, 1),
        device_at: now,
        tz_offset_min: 0,
        mono: None,
        boot_id: None,
        time_source: TimeSource::Auto,
        seen_seq: 0,
        member_id: None,
        payload: json!({"target_type":"post","target_id":target,"emoji":"🎉","member_id":member}),
        seq: None,
        account_id: None,
        device_id: None,
        occurred_at: None,
        received_at: None,
    };
    let (private, _) = ingest::accept(&conn, &session, make(1, "private", "bob"), now, false).unwrap();
    assert_eq!(private.error.unwrap().code, "forbidden");
    let (forged, _) = ingest::accept(&conn, &session, make(2, "server", "alice"), now, false).unwrap();
    assert_eq!(forged.error.unwrap().code, "forbidden");
    let (valid, _) = ingest::accept(&conn, &session, make(3, "server", "bob"), now, false).unwrap();
    assert!(valid.error.is_none(), "{:?}", valid.error);
    let owner = posts::one(&conn, ALICE, "server").unwrap().unwrap();
    assert!(owner["reactions"].as_array().unwrap().iter().any(|r| r["emoji"] == "🎉" && r["member_id"] == "bob"));
}

#[tokio::test]
async fn cross_account_replies_require_a_readable_parent_and_reach_its_author() {
    let conn = seed();
    let scope = format!("account:{BOB}");
    ingest::grant(&conn, BOB, &scope).unwrap();
    let now = chorus_server::now_ms();
    let session = ingest::Session {
        account_id: BOB.into(),
        device_id: BOB.into(),
        sample: ClockSample { server_time: now, mono: None, boot_id: None, offset_ms: 0 },
    };
    let make = |serial: u8, parent: &str| Op {
        id: new_id(now as u64, [serial; 10]),
        kind: "post.create".into(),
        v: 1,
        scope: scope.clone(),
        entity_id: Some(new_id(now as u64, [serial + 20; 10])),
        hlc: Hlc::new(now as u64, 0, 1),
        device_at: now,
        tz_offset_min: 0,
        mono: None,
        boot_id: None,
        time_source: TimeSource::Auto,
        seen_seq: 0,
        member_id: None,
        payload: json!({"kind":"note","authors":["bob"],"text":"A reply from Bob",
            "entities":[],"visibility":{"mode":"server"},"reply_to":parent}),
        seq: None,
        account_id: None,
        device_id: None,
        occurred_at: None,
        received_at: None,
    };
    for (serial, target) in [(1, "private"), (2, "deleted")] {
        let (ack, _) = ingest::accept(&conn, &session, make(serial, target), now, false).unwrap();
        assert_eq!(ack.error.unwrap().code, "forbidden");
    }
    let (ack, reply) = ingest::accept(&conn, &session, make(3, "server"), now, false).unwrap();
    assert!(ack.error.is_none(), "{:?}", ack.error);
    let reply = reply.unwrap();
    let reply_id = reply.entity_id.unwrap();
    let owner_replies = posts::replies(&conn, ALICE, "server", 1).unwrap();
    assert!(owner_replies.iter().any(|p| p["id"] == reply_id));
    let follower_replies = posts::replies(&conn, BOB, "server", 1).unwrap();
    assert!(follower_replies.iter().any(|p| p["id"] == reply_id));

    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("post-reply-http-test");
    let test_dir = cfg.server.data_dir.clone();
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/posts/server?depth=1", listener.local_addr().unwrap());
    let serving = state.clone();
    let server = tokio::spawn(async move { axum::serve(listener, app::router(serving)).await.unwrap() });
    let client = reqwest::Client::new();
    for session in ["alice-session", "bob-session"] {
        let body: Value = client.get(&base).bearer_auth(session).send().await.unwrap().json().await.unwrap();
        assert!(body["replies"].as_array().unwrap().iter().any(|p| p["id"] == reply_id));
    }
    server.abort();
    let _ = server.await;
    drop(client);
    common::release(state, &test_dir).await;
}

/// `GET /search/posts`: projected posts are indexed (title, text, tags), a reader never matches a
/// post it can't read, tokens (`read:posts`) see only their own account, pages continue with the
/// cursor, and a rebuild keeps search working.
#[test]
fn post_search_matches_only_readable_posts_and_survives_a_rebuild() {
    let mut c = db::open_memory().unwrap();
    db::migrate(&mut c).unwrap();
    for id in [ALICE, BOB] {
        c.execute("INSERT INTO account(id,kind,created_at) VALUES (?1,'person',0)", [id]).unwrap();
        ingest::grant(&c, id, &format!("account:{id}")).unwrap();
    }
    let scope = format!("account:{ALICE}");
    let t0 = 1_790_000_000_000;
    let post = |c: &Connection, n: i64, id: &str, mode: &str, title: &str, tags: Value| {
        let payload = json!({"kind": "entry", "authors": [], "title": title, "text": format!("garden notes {n}"),
            "entities": [], "tags": tags, "visibility": {"mode": mode}});
        ingest::server_op(c, ALICE, "post.create", &scope, Some(id), payload, t0 + n).unwrap();
    };
    post(&c, 1, &new_id(1, [1; 10]), "server", "Tomatoes", json!(["harvest"]));
    post(&c, 2, &new_id(1, [2; 10]), "private", "Secret tomatoes", json!(["diary"]));
    post(&c, 3, &new_id(1, [3; 10]), "followers", "Followers only", json!([]));
    for n in 4..9 {
        post(&c, n, &new_id(1, [n as u8; 10]), "server", "Beans", json!([]));
    }
    let ids = |v: &Value| -> Vec<String> {
        v["items"].as_array().unwrap().iter().map(|i| i["title"].as_str().unwrap_or("").to_string()).collect()
    };
    let search = |c: &Connection, p: &api_data::Principal, q: &str| {
        posts::search(c, p, &posts::PostSearch { q: q.into(), ..Default::default() }).unwrap()
    };
    let alice = api_data::Principal::owner(ALICE);
    let bob = api_data::Principal::owner(BOB);
    let check = |c: &Connection| {
        assert_eq!(ids(&search(c, &alice, "tomatoes")).len(), 2, "the author finds their private post too");
        assert_eq!(ids(&search(c, &bob, "tomatoes")), ["Tomatoes"], "an unreadable post never matches");
        assert_eq!(ids(&search(c, &bob, "followers")), Vec::<String>::new(), "no follow, no followers post");
        assert_eq!(ids(&search(c, &bob, "harvest")), ["Tomatoes"], "tags are searched");
        assert_eq!(ids(&search(c, &bob, "diary")), Vec::<String>::new());
        // pages of two through the five beans, then the end
        let mut seen = Vec::new();
        let mut cursor = None;
        loop {
            let q =
                posts::PostSearch { q: "beans".into(), limit: Some(2), cursor: cursor.take(), ..Default::default() };
            let page = posts::search(c, &bob, &q).unwrap();
            seen.extend(page["items"].as_array().unwrap().iter().map(|i| i["id"].as_str().unwrap().to_string()));
            match page["next_cursor"].as_str() {
                Some(next) => cursor = Some(next.to_string()),
                None => break,
            }
        }
        seen.sort();
        seen.dedup();
        assert_eq!(seen.len(), 5, "every page is new and none is lost");
    };
    check(&c);
    // an API token with read:posts sees only its own account's posts
    let token = api_data::create_token(&c, &bob, "script", &["read:posts".to_string()], t0).unwrap();
    let tp = api_data::principal(&c, token["token"].as_str().unwrap(), t0, 86_400_000).unwrap();
    assert_eq!(ids(&search(&c, &tp, "tomatoes")), Vec::<String>::new());
    let front_only = api_data::create_token(&c, &bob, "front", &["read:front".to_string()], t0).unwrap();
    let fp = api_data::principal(&c, front_only["token"].as_str().unwrap(), t0, 86_400_000).unwrap();
    assert!(posts::search(&c, &fp, &posts::PostSearch { q: "tomatoes".into(), ..Default::default() }).is_err());
    // a cursor from another search, and a malformed query, are refused
    let bad = posts::PostSearch { q: "\"unclosed".into(), ..Default::default() };
    assert!(matches!(posts::search(&c, &bob, &bad), Err(api_data::DataError::Bad(_))));
    // deleting a post takes it out of the index
    ingest::server_op(&c, ALICE, "post.delete", &scope, Some(&new_id(1, [1; 10])), json!({}), t0 + 20).unwrap();
    assert_eq!(ids(&search(&c, &bob, "tomatoes")), Vec::<String>::new());

    chorus_server::project::rebuild(&mut c).unwrap();
    let after = search(&c, &alice, "garden");
    assert!(!after["items"].as_array().unwrap().is_empty(), "search works after a rebuild");
    assert_eq!(ids(&search(&c, &bob, "diary")), Vec::<String>::new(), "and still hides what it hid");
    assert_eq!(ids(&search(&c, &bob, "tomatoes")), Vec::<String>::new(), "deleted stays out");
    assert_eq!(ids(&search(&c, &bob, "beans")).len(), 5);
}

/// Shared feeds (M7.4, feeds.rs): a follower evaluates the owner's feed over posts the follower
/// can read, names resolve against the owner's members, a feed that filters by fronting
/// evaluates for the follower too (D-069), and an unshared feed is invisible.
#[test]
fn shared_feeds_evaluate_over_what_the_reader_can_read() {
    let mut c = db::open_memory().unwrap();
    db::migrate(&mut c).unwrap();
    for id in [ALICE, BOB, CAROL] {
        c.execute("INSERT INTO account(id,kind,handle,created_at) VALUES (?1,'person',?1,0)", [id]).unwrap();
        ingest::grant(&c, id, &format!("account:{id}")).unwrap();
    }
    c.execute(
        "INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at) VALUES ('f',?1,?2,'active',0)",
        params![BOB, ALICE],
    )
    .unwrap();
    let scope = format!("account:{ALICE}");
    let t0 = 1_790_000_000_000;
    let kai = new_id(1, [40; 10]);
    let rin = new_id(1, [41; 10]);
    for (id, name) in [(&kai, "Kai"), (&rin, "Rin")] {
        ingest::server_op(&c, ALICE, "member.create", &scope, Some(id), json!({"name": name}), t0).unwrap();
    }
    let post = |n: i64, author: &str, mode: &str, tags: Value| {
        let payload = json!({"kind": "note", "authors": [author], "text": format!("post {n}"), "entities": [],
            "tags": tags, "visibility": {"mode": mode}});
        ingest::server_op(&c, ALICE, "post.create", &scope, Some(&new_id(2, [n as u8; 10])), payload, t0 + n).unwrap();
    };
    post(1, &kai, "server", json!(["garden"]));
    post(2, &kai, "private", json!(["garden"]));
    post(3, &kai, "followers", json!(["garden"]));
    post(4, &rin, "followers", json!(["garden"]));
    post(5, &kai, "followers", json!(["kitchen"]));
    let feed = |id: &str, query: &str, mode: &str| {
        let ast: Value = serde_json::from_str(&chorus_core::api::feed_parse(query).unwrap()).unwrap();
        let payload = json!({"name": id, "query": query, "query_ast": ast, "visibility": {"mode": mode}});
        ingest::server_op(&c, ALICE, "feed.set", &scope, Some(id), payload, t0 + 50).unwrap();
    };
    let (garden, secret, fronting) = (new_id(3, [1; 10]), new_id(3, [2; 10]), new_id(3, [3; 10]));
    feed(&garden, "from:@kai tag:garden", "followers");
    feed(&secret, "tag:garden", "private");
    feed(&fronting, "tag:garden fronting:true", "followers");

    let texts = |v: Value| -> Vec<String> {
        v["items"].as_array().unwrap().iter().map(|i| i["text"].as_str().unwrap().to_string()).collect()
    };
    let items = |who: &str, id: &str| {
        chorus_server::feeds::items(&c, &api_data::Principal::owner(who), id, &Default::default())
    };
    assert_eq!(texts(items(ALICE, &garden).unwrap().unwrap()), ["post 3", "post 2", "post 1"], "the owner sees all");
    assert_eq!(
        texts(items(BOB, &garden).unwrap().unwrap()),
        ["post 3", "post 1"],
        "a follower: only posts it can read, names resolved as the owner wrote them"
    );
    assert!(items(CAROL, &garden).unwrap().is_none(), "no follow, no feed");
    assert!(items(BOB, &secret).unwrap().is_none(), "a private feed isn't shared");
    // nobody fronted and nothing was revealed to BOB: a fronting feed evaluates, matching nothing
    // (notifier.rs tests the reveal: tests/notifier.rs `a_shared_fronting_feed_waits_for_the_reveal`)
    assert_eq!(texts(items(BOB, &fronting).unwrap().unwrap()), Vec::<String>::new());
    assert!(items(ALICE, &fronting).unwrap().is_some());

    let listed = |who: &str| -> Vec<(String, bool)> {
        chorus_server::feeds::list(&c, &api_data::Principal::owner(who)).unwrap()["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|f| (f["id"].as_str().unwrap().to_string(), f["shared"].as_bool().unwrap()))
            .collect()
    };
    assert_eq!(listed(ALICE).len(), 3);
    let mut bob = listed(BOB);
    bob.sort();
    let mut want = vec![(garden.clone(), true), (fronting.clone(), true)];
    want.sort();
    assert_eq!(bob, want);
    assert!(listed(CAROL).is_empty());

    // paging: one at a time, the cursor walks the rest and ends
    let mut seen = Vec::new();
    let mut cursor = None;
    loop {
        let q = chorus_server::feeds::ItemsQuery { limit: Some(1), cursor: cursor.take() };
        let page = chorus_server::feeds::items(&c, &api_data::Principal::owner(ALICE), &garden, &q).unwrap().unwrap();
        seen.extend(texts(page.clone()));
        match page["next_cursor"].as_str() {
            Some(next) => cursor = Some(next.to_string()),
            None => break,
        }
    }
    assert_eq!(seen, ["post 3", "post 2", "post 1"]);
}

/// Journal reads for the REST API (api_journal.rs): a profile bundle, lists and a list timeline,
/// own account only, with the scopes API.md names.
#[test]
fn profiles_and_lists_read_through_the_api() {
    let mut c = db::open_memory().unwrap();
    db::migrate(&mut c).unwrap();
    for id in [ALICE, BOB] {
        c.execute("INSERT INTO account(id,kind,created_at) VALUES (?1,'person',0)", [id]).unwrap();
        ingest::grant(&c, id, &format!("account:{id}")).unwrap();
    }
    let scope = format!("account:{ALICE}");
    let t0 = 1_790_000_000_000;
    let (kai, rin, list, friends) =
        (new_id(1, [50; 10]), new_id(1, [51; 10]), new_id(1, [52; 10]), new_id(1, [53; 10]));
    let op = |kind: &str, entity: &str, payload: Value, n: i64| {
        ingest::server_op(&c, ALICE, kind, &scope, Some(entity), payload, t0 + n).unwrap();
    };
    op("member.create", &kai, json!({"name": "Kai"}), 1);
    op("member.create", &rin, json!({"name": "Rin"}), 2);
    op("reltype.set", &friends, json!({"name": "friend", "is_symmetric": true}), 3);
    op(
        "relationship.set",
        &new_id(1, [54; 10]),
        json!({"from_member_id": kai, "to_kind": "member", "to_id": rin, "type_id": friends}),
        4,
    );
    for (n, author, kind) in [(10, &kai, "entry"), (11, &kai, "note"), (12, &rin, "note")] {
        let payload = json!({"kind": kind, "authors": [author], "text": format!("post {n}"), "entities": [], "visibility": {"mode": "private"}});
        op("post.create", &new_id(2, [n as u8; 10]), payload, n);
    }
    op("highlight.add", &kai, json!({"profile_member_id": kai, "post_id": new_id(2, [10; 10])}), 20);
    op("list.set", &list, json!({"name": "Kai only"}), 21);
    op("list.add", &list, json!({"member_id": kai}), 22);

    let owner = api_data::Principal::owner(ALICE);
    let p = chorus_server::api_journal::profile(&c, &owner, &kai).unwrap().unwrap();
    assert_eq!(p["member"]["name"], "Kai");
    assert_eq!(p["stats"]["posts"], 2);
    assert_eq!(p["stats"]["entries"], 1);
    assert_eq!(p["relationships"][0]["to_name"], "Rin");
    assert_eq!(p["relationships"][0]["type"]["name"], "friend");
    assert_eq!(p["highlights"].as_array().unwrap().len(), 1);
    assert!(
        chorus_server::api_journal::profile(&c, &api_data::Principal::owner(BOB), &kai).unwrap().is_none(),
        "another account's member"
    );

    let lists = chorus_server::api_journal::lists(&c, &owner).unwrap();
    assert_eq!(lists["items"][0]["name"], "Kai only");
    assert_eq!(lists["items"][0]["member_ids"], json!([kai]));
    let timeline = chorus_server::api_journal::list_timeline(&c, &owner, &list, &Default::default()).unwrap().unwrap();
    let texts: Vec<&str> = timeline["items"].as_array().unwrap().iter().map(|i| i["text"].as_str().unwrap()).collect();
    assert_eq!(texts, ["post 11", "post 10"]);
    assert!(
        chorus_server::api_journal::list_timeline(&c, &api_data::Principal::owner(BOB), &list, &Default::default())
            .unwrap()
            .is_none()
    );

    // tokens: read:members gets the profile without highlights; lists need read:posts
    let members_only = api_data::create_token(&c, &owner, "m", &["read:members".to_string()], t0).unwrap();
    let mp = api_data::principal(&c, members_only["token"].as_str().unwrap(), t0, 86_400_000).unwrap();
    let p = chorus_server::api_journal::profile(&c, &mp, &kai).unwrap().unwrap();
    assert!(p["highlights"].is_null());
    assert!(chorus_server::api_journal::lists(&c, &mp).is_err());
}
