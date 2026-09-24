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
    cfg.server.data_dir = std::env::var_os("CARGO_TARGET_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join(format!("posts-http-test-{:016x}", rand::random::<u64>()));
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/posts", listener.local_addr().unwrap());
    let server = state.clone();
    tokio::spawn(async move { axum::serve(listener, app::router(server)).await.unwrap() });
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
    assert_eq!(get(&token, "").send().await.unwrap().status(), 401);

    let ended = seed();
    ended.execute("UPDATE follow SET status='ended' WHERE id='follow'", []).unwrap();
    let after = chorus_server::posts::list(&ended, BOB, &Default::default()).unwrap();
    assert_eq!(ids(&after), ["own-bob", "server"]);
    assert!(chorus_server::posts::one(&ended, BOB, "bucket").unwrap().is_none());
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
    cfg.server.data_dir = std::env::var_os("CARGO_TARGET_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join(format!("post-reply-http-test-{:016x}", rand::random::<u64>()));
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/posts/server?depth=1", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app::router(state)).await.unwrap() });
    let client = reqwest::Client::new();
    for session in ["alice-session", "bob-session"] {
        let body: Value = client.get(&base).bearer_auth(session).send().await.unwrap().json().await.unwrap();
        assert!(body["replies"].as_array().unwrap().iter().any(|p| p["id"] == reply_id));
    }
}
