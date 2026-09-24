//! FTS search is a history read: space access and structured message visibility apply.

use chorus_server::{api_data, app, auth, config::Config, db};
use rusqlite::{Connection, params};
use serde_json::Value;
mod common;

const ALICE: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const BOB: &str = "0192f8c2-0000-7000-8000-0000000000b2";
const OUTSIDER: &str = "0192f8c2-0000-7000-8000-0000000000c3";
const SPACE: &str = "0192f8c2-0000-7000-8000-0000000000d4";
const CHANNEL: &str = "0192f8c2-0000-7000-8000-0000000000e5";

fn seed() -> Connection {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let now = chorus_server::now_ms();
    for (id, label) in [(ALICE, "alice"), (BOB, "bob"), (OUTSIDER, "outsider")] {
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
    }
    conn.execute(
        "INSERT INTO space(id,kind,owner_account_id,created_at) VALUES (?1,'shared',?2,0)",
        params![SPACE, ALICE],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO channel(id,space_id,kind,name,created_at) VALUES (?1,?2,'text','garden',0)",
        params![CHANNEL, SPACE],
    )
    .unwrap();
    for account in [ALICE, BOB] {
        conn.execute(
            "INSERT INTO scope_access(account_id,scope) VALUES (?1,?2)",
            params![account, format!("space:{SPACE}")],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO space_member(space_id,account_id,joined_hlc) VALUES (?1,?2,'1:0:1')",
            params![SPACE, account],
        )
        .unwrap();
    }
    conn.execute("INSERT INTO member(id,account_id,name,created_at) VALUES ('rose',?1,'Rose',0)", [ALICE]).unwrap();
    for (id, owner, text, at, visibility, deleted) in [
        ("public", ALICE, "violet garden image", 100, None, None),
        ("aside", ALICE, "violet private aside", 110, Some(r#"{"mode":"system_only"}"#), None),
        ("members", ALICE, "violet chosen members", 120, Some(r#"{"mode":"members","member_ids":["rose"]}"#), None),
        ("bob", BOB, "violet bob message", 130, None, None),
        ("deleted", ALICE, "violet deleted", 140, None, Some(150)),
        ("segmented", ALICE, "violet in two voices", 160, None, None),
    ] {
        conn.execute(
            "INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text,visibility,deleted_at)
             VALUES (?1,?2,?3,?3,?4,?5,?6,?7)",
            params![id, CHANNEL, owner, at, text, visibility, deleted],
        )
        .unwrap();
        if deleted.is_none() {
            conn.execute("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message WHERE id=?1", [id])
                .unwrap();
        }
    }
    conn.execute("INSERT INTO message_author(message_id,member_id,position) VALUES ('public','rose',0)", []).unwrap();
    conn.execute(
        "INSERT INTO message_segment_author(message_id,idx,member_id,position) VALUES ('segmented',1,'rose',0)",
        [],
    )
    .unwrap();
    conn.execute("INSERT INTO attachment(id,mime,created_at) VALUES ('photo','image/png',0)", []).unwrap();
    conn.execute(
        "INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position)
         VALUES ('message','public','photo',0)",
        [],
    )
    .unwrap();
    conn
}

#[tokio::test]
async fn http_search_applies_account_visibility_and_filters() {
    let conn = seed();
    let token = api_data::create_token(
        &conn,
        &api_data::Principal::owner(BOB),
        "search",
        &["read:messages".into()],
        chorus_server::now_ms(),
    )
    .unwrap()["token"]
        .as_str()
        .unwrap()
        .to_string();
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("search-http-test");
    let test_dir = cfg.server.data_dir.clone();
    let state = app::Shared::new(conn, cfg).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/api/v1/search/messages", listener.local_addr().unwrap());
    let server = tokio::spawn(async move { axum::serve(listener, app::router(state)).await.unwrap() });
    let client = reqwest::Client::new();
    let get = |auth: &str, suffix: &str| client.get(format!("{base}?q=violet{suffix}")).bearer_auth(auth);

    assert_eq!(
        get("outsider-session", "").send().await.unwrap().json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
    assert_eq!(
        get("bob-session", "").send().await.unwrap().json::<Value>().await.unwrap()["items"].as_array().unwrap().len(),
        3
    );
    let first = get("bob-session", "&limit=2").send().await.unwrap().json::<Value>().await.unwrap();
    assert_eq!(first["items"].as_array().unwrap().len(), 2);
    let cursor = first["next_cursor"].as_str().unwrap();
    let second =
        get("bob-session", &format!("&limit=2&cursor={cursor}")).send().await.unwrap().json::<Value>().await.unwrap();
    assert_eq!(second["items"].as_array().unwrap().len(), 1);
    assert!(second["next_cursor"].is_null());
    let mut paged = first["items"]
        .as_array()
        .unwrap()
        .iter()
        .chain(second["items"].as_array().unwrap())
        .map(|v| v["id"].as_str().unwrap().to_string())
        .collect::<Vec<_>>();
    paged.sort();
    assert_eq!(paged, ["bob", "public", "segmented"]);
    assert_eq!(get("bob-session", "&cursor=bad").send().await.unwrap().status(), 400);
    assert_eq!(
        client
            .get(format!("{base}?q=garden&cursor={cursor}"))
            .bearer_auth("bob-session")
            .send()
            .await
            .unwrap()
            .status(),
        400
    );
    assert_eq!(
        get("alice-session", "").send().await.unwrap().json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .len(),
        5
    );
    // an API token reaches only its own account's messages (API.md §2.3), even in a shared space
    let items = |v: Value| {
        v["items"].as_array().unwrap().iter().map(|i| i["id"].as_str().unwrap().to_string()).collect::<Vec<_>>()
    };
    assert_eq!(items(get(&token, "").send().await.unwrap().json::<Value>().await.unwrap()), ["bob"]);
    assert!(items(get(&token, "&from=Rose").send().await.unwrap().json::<Value>().await.unwrap()).is_empty());
    assert_eq!(
        items(
            get("bob-session", "&from=Rose&has=image&in=garden&after=50&before=110")
                .send()
                .await
                .unwrap()
                .json::<Value>()
                .await
                .unwrap()
        ),
        ["public"]
    );
    // a segmented message counts every segment's author (D-045)
    assert_eq!(
        items(get("alice-session", "&from=Rose&after=155").send().await.unwrap().json::<Value>().await.unwrap()),
        ["segmented"]
    );
    assert_eq!(
        get("bob-session", "&has=image").send().await.unwrap().json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        get("bob-session", "&has=file").send().await.unwrap().json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
    assert_eq!(
        get("bob-session", "&before=120").send().await.unwrap().json::<Value>().await.unwrap()["items"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(client.get(format!("{base}?q=violet")).send().await.unwrap().status(), 401);
    assert_eq!(client.get(format!("{base}?q=violet")).bearer_auth("bad").send().await.unwrap().status(), 401);
    assert_eq!(get("bob-session", "&has=unknown").send().await.unwrap().status(), 400);
    let message_base = base.replace("/search/messages", "/messages");
    assert_eq!(
        client.get(format!("{message_base}/public")).bearer_auth("bob-session").send().await.unwrap().status(),
        200
    );
    assert_eq!(
        client.get(format!("{message_base}/aside")).bearer_auth("bob-session").send().await.unwrap().status(),
        404
    );
    assert_eq!(
        client.get(format!("{message_base}/aside")).bearer_auth("alice-session").send().await.unwrap().status(),
        200
    );
    assert_eq!(
        client.get(format!("{message_base}/public")).bearer_auth("outsider-session").send().await.unwrap().status(),
        404
    );
    assert_eq!(client.get(format!("{message_base}/public")).bearer_auth(&token).send().await.unwrap().status(), 404);
    assert_eq!(client.get(format!("{message_base}/bob")).bearer_auth(&token).send().await.unwrap().status(), 200);
    server.abort();
    let _ = server.await;
    let _ = std::fs::remove_dir_all(test_dir);
}
