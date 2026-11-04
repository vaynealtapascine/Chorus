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
        4
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

/// SPEC §5.3: the server's SQL search finds exactly what `chorus_core::search::matches` finds
/// (what the apps' local search uses), over random messages and random search boxes.
#[test]
fn sql_search_agrees_with_core() {
    use chorus_core::search::{self, Candidate, Context};
    const DAY: i64 = 86_400_000;
    let words = ["lunch", "Café", "garden", "straße", "plans", "Ærø", "notes", "cafeteria"];
    let members = [("rose", "Rose"), ("kai", "Kai"), ("elise", "Élise")];
    let channels = [(CHANNEL, "garden"), ("0192f8c2-0000-7000-8000-0000000000e6", "general")];
    let mut rng = 0x5eed_u64;
    let mut next = |n: u64| {
        rng ^= rng << 13;
        rng ^= rng >> 7;
        rng ^= rng << 17;
        rng % n
    };
    let (mut found, mut asked) = (0, 0);
    for round in 0..12 {
        let conn = seed();
        conn.execute("DELETE FROM message", []).unwrap();
        conn.execute("DELETE FROM message_fts", []).unwrap();
        conn.execute("DELETE FROM message_author", []).unwrap();
        conn.execute("DELETE FROM message_segment_author", []).unwrap();
        conn.execute("DELETE FROM item_attachment", []).unwrap();
        conn.execute("DELETE FROM member", []).unwrap();
        for (id, name) in members {
            conn.execute(
                "INSERT INTO member(id,account_id,name,created_at) VALUES (?1,?2,?3,0)",
                params![id, ALICE, name],
            )
            .unwrap();
        }
        conn.execute(
            "INSERT OR IGNORE INTO channel(id,space_id,kind,name,created_at) VALUES (?1,?2,'text','general',0)",
            params![channels[1].0, SPACE],
        )
        .unwrap();
        for (i, mime) in ["image/png", "text/plain"].into_iter().enumerate() {
            conn.execute(
                "INSERT OR IGNORE INTO attachment(id,mime,created_at) VALUES (?1,?2,0)",
                params![format!("a{i}"), mime],
            )
            .unwrap();
        }
        let now = chorus_server::now_ms();
        let today = now.div_euclid(DAY);
        let mut candidates = Vec::new();
        for m in 0..40 {
            let text: Vec<&str> = (0..1 + next(4)).map(|_| words[next(words.len() as u64) as usize]).collect();
            let text = text.join(" ");
            let cw = (next(4) == 0).then(|| words[next(words.len() as u64) as usize].to_string());
            let author = members[next(3) as usize];
            let channel = channels[next(2) as usize];
            // noon of a day in the last three weeks: far from any date or age boundary
            let at = (today - 1 - next(20) as i64) * DAY + DAY / 2;
            let link = next(3) == 0;
            let entities = if link { r#"[{"type":"url","offset":0,"length":3}]"# } else { "[]" };
            let pinned = next(4) == 0;
            let id = format!("m{m:02}");
            conn.execute(
                "INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text,cw,entities,pinned_at)
                 VALUES (?1,?2,?3,?3,?4,?5,?6,?7,?8)",
                params![id, channel.0, ALICE, at, text, cw, entities, pinned.then_some(at)],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,COALESCE(cw,'') FROM message WHERE id=?1",
                [&id],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO message_author(message_id,member_id,position) VALUES (?1,?2,0)",
                params![id, author.0],
            )
            .unwrap();
            let mut mimes = Vec::new();
            match next(4) {
                0 => mimes.push("image/png"),
                1 => mimes.push("text/plain"),
                _ => {}
            }
            for (pos, mime) in mimes.iter().enumerate() {
                let a = if *mime == "image/png" { "a0" } else { "a1" };
                conn.execute(
                    "INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position) VALUES ('message',?1,?2,?3)",
                    params![id, a, pos as i64],
                )
                .unwrap();
            }
            candidates.push((
                id,
                Candidate {
                    text,
                    cw,
                    authors: vec![(author.0.into(), author.1.into())],
                    channel: (channel.0.into(), channel.1.into()),
                    at,
                    mimes: mimes.iter().map(|m| m.to_string()).collect(),
                    link,
                    pinned,
                },
            ));
        }
        for _ in 0..40 {
            let mut parts: Vec<String> = Vec::new();
            for _ in 0..next(3) {
                let w = search::fold(words[next(words.len() as u64) as usize]);
                let cut = 1 + next(w.chars().count() as u64) as usize;
                parts.push(w.chars().take(cut).collect());
            }
            if next(3) == 0 {
                parts.push(format!("from:{}", ["rose", "Kai", "@élise", "kai"][next(4) as usize]));
            }
            if next(3) == 0 {
                parts.push(format!("in:#{}", channels[next(2) as usize].1));
            }
            if next(3) == 0 {
                parts.push(format!("has:{}", search::HAS[next(4) as usize]));
            }
            if next(3) == 0 {
                let date = chorus_core::front::civil_date(today - 1 - next(20) as i64);
                parts.push(format!("{}:{date}", ["before", "after"][next(2) as usize]));
            }
            if next(4) == 0 {
                parts.push(format!("{}:{}d", ["before", "after"][next(2) as usize], 1 + next(20)));
            }
            if next(5) == 0 {
                parts.push("is:pinned".into());
            }
            let q = parts.join(" ");
            let parsed = search::parse(&q).unwrap();
            if parsed.is_empty() {
                continue;
            }
            let ctx = Context::at(now, 0, &parsed);
            let mut want: Vec<&str> = candidates
                .iter()
                .filter(|(_, c)| search::matches(&parsed, c, &ctx))
                .map(|(id, _)| id.as_str())
                .collect();
            want.sort();
            let query = chorus_server::search::MessageQuery { q: q.clone(), ..Default::default() };
            let got = chorus_server::search::messages(&conn, &api_data::Principal::owner(ALICE), &query).unwrap();
            let mut got: Vec<&str> =
                got["items"].as_array().unwrap().iter().map(|i| i["id"].as_str().unwrap()).collect();
            got.sort();
            assert_eq!(got, want, "round {round}: {q}");
            found += usize::from(!want.is_empty());
            asked += 1;
        }
    }
    assert!(found > asked / 4 && found < asked, "the queries should sometimes match: {found} of {asked}");
}

#[test]
fn current_search_filters_before_paging_and_context_and_rechecks_front_permissions() {
    use chorus_server::{
        search::{self, MessageQuery},
        search_view::CurrentView,
    };
    let conn = seed();
    let alice = api_data::Principal::owner(ALICE);
    let bob = api_data::Principal::owner(BOB);
    let query = MessageQuery { q: "violet".into(), current: true, limit: Some(1), ..Default::default() };
    let first = search::messages(&conn, &bob, &query).unwrap();
    assert_eq!(first["items"][0]["id"], "segmented");
    assert_eq!(first["items"][0]["context_before"]["id"], "bob");
    assert!(first["items"][0]["context_after"].is_null());
    // Hidden indexed rows cannot alter order, cursor scores, visible page counts or context.
    conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text,visibility) VALUES ('hidden-new',?1,?2,?2,999,'violet violet violet','{\"mode\":\"system_only\"}')",params![CHANNEL,ALICE]).unwrap();
    conn.execute("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message WHERE id='hidden-new'", [])
        .unwrap();
    assert_eq!(first, search::messages(&conn, &bob, &query).unwrap());
    let second = search::messages(
        &conn,
        &bob,
        &MessageQuery { cursor: Some(first["next_cursor"].as_str().unwrap().into()), ..query },
    )
    .unwrap();
    assert_eq!(second["items"][0]["id"], "bob");
    assert_eq!(second["items"][0]["context_before"]["id"], "public");
    assert!(!serde_json::to_string(&second).unwrap().contains("members"));
    assert!(CurrentView::new(&conn, &alice).unwrap().card("members").unwrap().is_none());
    conn.execute("INSERT INTO front_interval(id,account_id,subject_type,subject_id,level,is_primary,position,start_at,start_switch_id,start_tz_offset_min) VALUES ('live',?1,'member','rose','front',1,0,0,'switch',0)",[ALICE]).unwrap();
    assert!(CurrentView::new(&conn, &alice).unwrap().card("members").unwrap().is_some());
    conn.execute("UPDATE front_interval SET end_at=100 WHERE id='live'", []).unwrap();
    assert!(CurrentView::new(&conn, &alice).unwrap().card("members").unwrap().is_none());
    conn.execute("INSERT INTO channel_permission(channel_id,target_type,target_id,allow,deny,hlc) VALUES (?1,'account',?2,'[]','[\"view\"]','1')",params![CHANNEL,BOB]).unwrap();
    let denied =
        search::messages(&conn, &bob, &MessageQuery { q: "violet".into(), current: true, ..Default::default() })
            .unwrap();
    assert_eq!(denied["items"], serde_json::json!([]));
    assert!(denied["next_cursor"].is_null());
    assert!(CurrentView::new(&conn, &bob).unwrap().card("public").unwrap().is_none());
}

#[test]
fn current_search_keeps_exact_chat_scope_and_private_thread_ancestry() {
    use chorus_server::search::{self, MessageQuery};
    let conn = seed();
    conn.execute("INSERT INTO channel(id,space_id,kind,name,member_ids,created_at) VALUES ('private',?1,'member_dm','private','[\"rose\"]',0)",[SPACE]).unwrap();
    conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text) VALUES ('private-msg','private',?1,?1,200,'violet private')",[ALICE]).unwrap();
    conn.execute("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message WHERE id='private-msg'", [])
        .unwrap();
    conn.execute("INSERT INTO channel(id,space_id,kind,name,parent_message_id,created_at) VALUES ('thread',?1,'thread','thread','private-msg',0)",[SPACE]).unwrap();
    conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text) VALUES ('thread-msg','thread',?1,?1,210,'violet thread')",[ALICE]).unwrap();
    conn.execute("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message WHERE id='thread-msg'", [])
        .unwrap();
    let alice = api_data::Principal::owner(ALICE);
    let q =
        MessageQuery { q: "violet".into(), current: true, channel_id: Some("private".into()), ..Default::default() };
    assert_eq!(search::messages(&conn, &alice, &q).unwrap()["items"], serde_json::json!([]));
    let thread = MessageQuery { channel_id: Some("thread".into()), ..q };
    assert_eq!(search::messages(&conn, &alice, &thread).unwrap()["items"], serde_json::json!([]));
    conn.execute("INSERT INTO front_interval(id,account_id,subject_type,subject_id,level,is_primary,position,start_at,start_switch_id,start_tz_offset_min) VALUES ('live',?1,'member','rose','cocon',0,0,0,'switch',0)",[ALICE]).unwrap();
    assert_eq!(search::messages(&conn, &alice, &thread).unwrap()["items"][0]["id"], "thread-msg");
    assert_eq!(
        search::messages(&conn, &alice, &MessageQuery { q: "violet in:garden".into(), ..thread }).unwrap()["items"],
        serde_json::json!([])
    );
}

#[test]
fn internal_member_chat_with_no_eligible_members_fails_closed() {
    use chorus_server::search::{self, MessageQuery};
    let conn = seed();
    conn.execute("UPDATE space SET kind='internal' WHERE id=?1", [SPACE]).unwrap();
    conn.execute("UPDATE channel SET kind='member_dm',member_ids='[]' WHERE id=?1", [CHANNEL]).unwrap();
    let page = search::messages(
        &conn,
        &api_data::Principal::owner(ALICE),
        &MessageQuery { q: "violet".into(), ..Default::default() },
    )
    .unwrap();
    assert_eq!(page["items"], serde_json::json!([]));
    assert!(page["next_cursor"].is_null());
}

#[test]
fn sparse_current_view_50k_first_screen_stays_within_budget() {
    use chorus_server::search::{self, MessageQuery};
    let conn = seed();
    conn.execute_batch("DELETE FROM item_attachment; DELETE FROM message_author; DELETE FROM message_segment_author; DELETE FROM message; DELETE FROM message_fts; BEGIN;").unwrap();
    for i in 0..50_000 {
        conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text,visibility) VALUES (?1,?2,?3,?3,?4,'needle',?5)",params![format!("m{i:08}"),CHANNEL,ALICE,i,if i%2000==0 {None} else {Some(r#"{"mode":"members","member_ids":["rose"]}"#)}]).unwrap();
    }
    conn.execute_batch("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message; COMMIT;").unwrap();
    let query = MessageQuery { q: "needle".into(), limit: Some(25), ..Default::default() };
    let start = std::time::Instant::now();
    let page = search::messages(&conn, &api_data::Principal::owner(ALICE), &query).unwrap();
    let elapsed = start.elapsed();
    let items = page["items"].as_array().unwrap();
    assert_eq!(items.len(), 25); // No pre-visibility candidate cap may silently omit authorized hits.
    assert!(page["next_cursor"].is_null());
    for (i, item) in items.iter().enumerate() {
        assert_eq!(item["id"], format!("m{:08}", 48_000 - i * 2000));
        if i > 0 {
            assert_eq!(item["context_after"]["id"], items[i - 1]["id"]);
        }
        if i + 1 < items.len() {
            assert_eq!(item["context_before"]["id"], items[i + 1]["id"]);
        }
    }
    eprintln!("50k sparse search: {elapsed:?}");
    // SPEC §9 first-screen budget; optimised builds (the gate profile, release) enforce it.
    if !cfg!(debug_assertions) {
        assert!(elapsed < std::time::Duration::from_millis(150), "shared DB held for {elapsed:?}");
    }
    let one =
        search::messages(&conn, &api_data::Principal::owner(ALICE), &MessageQuery { limit: Some(1), ..query.clone() })
            .unwrap();
    let second = search::messages(
        &conn,
        &api_data::Principal::owner(ALICE),
        &MessageQuery { cursor: Some(one["next_cursor"].as_str().unwrap().into()), limit: Some(1), ..query },
    )
    .unwrap();
    assert_eq!(second["items"][0]["id"], "m00046000");
    conn.execute("INSERT INTO member(id,account_id,name,created_at) VALUES ('other-fronter',?1,'Other',0)", [ALICE])
        .unwrap();
    conn.execute("INSERT INTO front_interval(id,account_id,subject_type,subject_id,level,is_primary,position,start_at,start_switch_id,start_tz_offset_min) VALUES ('perf-front',?1,'member','other-fronter','front',1,0,0,'switch',0)",[ALICE]).unwrap();
    for dense in [false, true] {
        if dense {
            conn.execute("UPDATE message SET visibility=NULL", []).unwrap();
        }
        let start = std::time::Instant::now();
        let page = search::messages(
            &conn,
            &api_data::Principal::owner(ALICE),
            &MessageQuery { q: "needle".into(), limit: Some(25), ..Default::default() },
        )
        .unwrap();
        let elapsed = start.elapsed();
        assert_eq!(page["items"].as_array().unwrap().len(), 25);
        assert_eq!(page["items"][0]["id"], if dense { "m00049999" } else { "m00048000" });
        assert_eq!(page["next_cursor"].is_string(), dense);
        for (i, item) in page["items"].as_array().unwrap().iter().enumerate() {
            let at = if dense { 49_999 - i } else { 48_000 - i * 2000 };
            assert_eq!(item["id"], format!("m{at:08}"));
            let before = if dense { at.checked_sub(1) } else { at.checked_sub(2000) };
            assert_eq!(item["context_before"]["id"].as_str().map(str::to_string), before.map(|i| format!("m{i:08}")));
            let after = if dense { at + 1 } else { at + 2000 };
            assert_eq!(
                item["context_after"]["id"].as_str().map(str::to_string),
                (after < 50_000).then(|| format!("m{after:08}"))
            );
        }
        eprintln!("50k nonempty front, dense={dense}: {elapsed:?}");
        if !cfg!(debug_assertions) {
            assert!(elapsed < std::time::Duration::from_millis(150), "shared DB held for {elapsed:?}, dense={dense}");
        }
    }
}

#[test]
fn sql_current_selection_agrees_with_guard_for_malformed_audiences_and_cutoffs() {
    use chorus_server::search_view::{CurrentView, message_sql};
    let conn = seed();
    for (i, visibility) in [
        "null",
        "[]",
        r#"{"mode":"unknown"}"#,
        r#"{"mode":"members","member_ids":{}}"#,
        r#"{"mode":"members","member_ids":[true,42,"rose"]}"#,
        r#"{"mode":"members","member_ids":[42]}"#,
        r#"{"mode":"members","member_ids":["42"]}"#,
        r#"{"mode":"system_only"}"#,
        r#"{"mode":"all"}"#,
    ]
    .iter()
    .enumerate()
    {
        // Repeated JSON across accounts must not share an own-account audience decision.
        // Equal timestamps exercise the id half of the chronological seek in both directions.
        for owner in [ALICE, BOB] {
            conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text,visibility) VALUES (?1,?2,?3,?3,200,'needle',?4)",params![format!("malformed{i}-{owner}"),CHANNEL,owner,visibility]).unwrap();
        }
    }
    let ids = conn
        .prepare("SELECT id FROM message")
        .unwrap()
        .query_map([], |r| r.get::<_, String>(0))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    for account in [ALICE, BOB] {
        let owner = api_data::Principal::owner(account);
        let token = api_data::create_token(&conn, &owner, "context", &["read:messages".into()], 0).unwrap();
        let token = api_data::principal(&conn, token["token"].as_str().unwrap(), 0, 0).unwrap();
        for front in [false, true] {
            if front {
                conn.execute("INSERT OR REPLACE INTO front_interval(id,account_id,subject_type,subject_id,level,is_primary,position,start_at,start_switch_id,start_tz_offset_min) VALUES ('live',?1,'member','rose','front',1,0,0,'switch',0)",[ALICE]).unwrap();
            }
            for cutoff in [0, 125, 200] {
                conn.execute(
                    "INSERT OR REPLACE INTO pref(account_id,device_id,key,value,hlc) VALUES (?1,'',?2,?3,'1')",
                    params![account, format!("chat_hidden:{CHANNEL}"), cutoff.to_string()],
                )
                .unwrap();
                for p in [&owner, &token] {
                    let mut view = CurrentView::new(&conn, p).unwrap();
                    let (channels, front, cutoffs) = view.selection().unwrap();
                    let sql = format!(
                        "SELECT m.id FROM message m WHERE {} ORDER BY m.id",
                        message_sql("?1", "?2", "?3", "?4", "?5")
                    );
                    let got = conn
                        .prepare(&sql)
                        .unwrap()
                        .query_map(params![account, channels, front, cutoffs, !p.is_device()], |r| {
                            r.get::<_, String>(0)
                        })
                        .unwrap()
                        .collect::<Result<Vec<_>, _>>()
                        .unwrap();
                    let mut want = ids.iter().filter(|id| view.message(id).unwrap()).cloned().collect::<Vec<_>>();
                    want.sort();
                    assert_eq!(got, want);
                    let mut cards = want.iter().map(|id| view.card(id).unwrap().unwrap()).collect::<Vec<_>>();
                    cards.sort_by_key(|m| (m["occurred_at"].as_i64().unwrap(), m["id"].as_str().unwrap().to_string()));
                    // Start a fresh view so the first seek performs audience checks itself. Revisit
                    // both ways to exercise reverse-edge reuse and cached absent end neighbors.
                    let mut context = CurrentView::new(&conn, p).unwrap();
                    for i in (0..cards.len()).rev().chain(0..cards.len()) {
                        for after in [false, true] {
                            let index = if after { i.checked_add(1) } else { i.checked_sub(1) };
                            let expected = index.and_then(|i| cards.get(i)).map(|m| m["id"].clone());
                            let actual = context.neighbor(&cards[i], after).unwrap().map(|m| m["id"].clone());
                            assert_eq!(
                                actual,
                                expected,
                                "context account={account}, token={}, front={front}, cutoff={cutoff}, after={after}",
                                !p.is_device()
                            );
                        }
                    }
                }
            }
        }
    }
    // Syntactically invalid JSON is also fail-closed in both selectors. Keep this separate
    // from rich-card context: the legacy space author-card projection assumes valid JSON.
    conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text,visibility) VALUES ('invalid-json',?1,?2,?2,201,'needle','{')", params![CHANNEL, ALICE]).unwrap();
    let p = api_data::Principal::owner(ALICE);
    let mut view = CurrentView::new(&conn, &p).unwrap();
    assert!(!view.message("invalid-json").unwrap());
    let (channels, front, cutoffs) = view.selection().unwrap();
    let sql = format!(
        "SELECT count(*) FROM message m WHERE m.id='invalid-json' AND {}",
        message_sql("?1", "?2", "?3", "?4", "?5")
    );
    let selected: i64 = conn.query_row(&sql, params![ALICE, channels, front, cutoffs, false], |r| r.get(0)).unwrap();
    assert_eq!(selected, 0);
}

#[test]
fn malformed_channel_membership_never_becomes_unrestricted() {
    use chorus_server::{
        search::{self, MessageQuery},
        search_view::{CurrentView, message_sql},
    };
    let conn = seed();
    conn.execute("UPDATE space SET kind='internal' WHERE id=?1", [SPACE]).unwrap();
    conn.execute("INSERT INTO channel(id,space_id,kind,name,parent_message_id,created_at) VALUES ('child',?1,'thread','Child','public',0)",[SPACE]).unwrap();
    conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text) VALUES ('child-msg','child',?1,?1,200,'violet child')",[ALICE]).unwrap();
    conn.execute("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message WHERE id='child-msg'", [])
        .unwrap();
    let p = api_data::Principal::owner(ALICE);
    for raw in [r#"["rose",42]"#, r#"[42]"#, r#"{}"#, r#""rose""#, r#"null"#] {
        conn.execute("UPDATE channel SET member_ids=?1 WHERE id=?2", params![raw, CHANNEL]).unwrap();
        for front in [false, true] {
            conn.execute("DELETE FROM front_interval", []).unwrap();
            if front {
                conn.execute("INSERT INTO front_interval(id,account_id,subject_type,subject_id,level,is_primary,position,start_at,start_switch_id,start_tz_offset_min) VALUES ('live',?1,'member','rose','front',1,0,0,'switch',0)",[ALICE]).unwrap();
            }
            let mut view = CurrentView::new(&conn, &p).unwrap();
            assert!(!view.message("public").unwrap(), "{raw}, front={front}");
            assert!(!view.message("child-msg").unwrap(), "ancestor {raw}");
            assert!(search::message_by_id(&conn, &p, "public").unwrap().is_none());
            let (channels, front, cutoffs) = view.selection().unwrap();
            let sql = format!("SELECT COUNT(*) FROM message m WHERE {}", message_sql("?1", "?2", "?3", "?4", "?5"));
            let count: i64 =
                conn.query_row(&sql, params![ALICE, channels, front, cutoffs, false], |r| r.get(0)).unwrap();
            assert_eq!(count, 0, "SQL selection {raw}");
            let page = search::messages(&conn, &p, &MessageQuery { q: "violet".into(), ..Default::default() }).unwrap();
            assert_eq!(page["items"], serde_json::json!([]));
            assert!(page["next_cursor"].is_null());
        }
    }
    // SQL NULL and a valid empty text-channel list retain their established meaning.
    for raw in [None, Some("[]")] {
        conn.execute("UPDATE channel SET member_ids=?1 WHERE id=?2", params![raw, CHANNEL]).unwrap();
        assert!(CurrentView::new(&conn, &p).unwrap().message("public").unwrap());
    }
}

/// Same fixture as web/Android: canonical point reads, optimized SQL, FTS paging and context
/// must agree after every subsystem/archive mutation. No front or message audience expansion.
#[test]
fn subsystem_search_fixture_matches_canonical_sql_and_context() {
    use chorus_server::search::{self, MessageQuery};
    use chorus_server::search_view::{CurrentView, message_sql};
    let f: Value = serde_json::from_str(include_str!("../../../fixtures/ui_search_subsystems.json")).unwrap();
    for case in f["cases"].as_array().unwrap() {
        let mut projection = f["projection"].clone();
        for (table, rows) in case["rows"].as_object().unwrap() {
            for (id, fields) in rows.as_object().unwrap() {
                if projection["rows"][table][id].is_null() {
                    projection["rows"][table][id] = serde_json::json!({"fields":{}});
                }
                for (key, value) in fields.as_object().unwrap() {
                    projection["rows"][table][id]["fields"][key] = value.clone();
                }
            }
        }
        for (key, value) in case["sets"].as_object().unwrap() {
            projection["sets"]["group_membership"][key] = value.clone();
        }
        let mut conn = db::open_memory().unwrap();
        db::migrate(&mut conn).unwrap();
        for account in ["account", "other"] {
            conn.execute("INSERT INTO account(id,kind,created_at) VALUES (?1,'system',0)", [account]).unwrap();
            conn.execute("INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at) VALUES (?1,?1,?1,?1,'cli','test',0)", [account]).unwrap();
        }
        let rows = &projection["rows"];
        for (id, row) in rows["space"].as_object().unwrap() {
            let fields = &row["fields"];
            conn.execute(
                "INSERT INTO space(id,kind,owner_account_id,created_at) VALUES (?1,?2,?3,0)",
                params![id, fields["kind"].as_str(), fields["created_by_account_id"].as_str()],
            )
            .unwrap();
            conn.execute("INSERT INTO scope_access(account_id,scope) VALUES ('account',?1)", [format!("space:{id}")])
                .unwrap();
            conn.execute(
                "INSERT INTO space_member(space_id,account_id,role,joined_hlc) VALUES (?1,'account','member','1')",
                [id],
            )
            .unwrap();
        }
        for (id, row) in rows["member"].as_object().unwrap() {
            let fields = &row["fields"];
            conn.execute("INSERT INTO member(id,account_id,name,created_at,archived_at,deleted_at) VALUES (?1,'account',?1,0,?2,?3)", params![id,fields["archived_at"].as_i64(),fields["deleted_at"].as_i64()]).unwrap();
        }
        for (id, row) in rows["member_group"].as_object().unwrap() {
            let fields = &row["fields"];
            conn.execute("INSERT INTO member_group(id,account_id,name,kind,created_at,deleted_at) VALUES (?1,'account',?1,?2,0,?3)", params![id,fields["kind"].as_str(),fields["deleted_at"].as_i64()]).unwrap();
        }
        for (id, row) in rows["pref"].as_object().unwrap() {
            let key = id.split('|').nth(2).unwrap();
            conn.execute(
                "INSERT INTO pref(account_id,device_id,key,value,hlc) VALUES ('account','',?1,?2,'1')",
                params![key, row["fields"]["value"].to_string()],
            )
            .unwrap();
        }
        for (key, present) in projection["sets"]["group_membership"].as_object().unwrap() {
            let (group, member) = key.split_once('|').unwrap();
            let member: Value = serde_json::from_str(member).unwrap();
            conn.execute(
                "INSERT INTO group_membership(group_id,member_id,added_hlc,removed_hlc) VALUES (?1,?2,'1',CASE WHEN ?3 THEN NULL ELSE '2' END)",
                params![group, member["member_id"].as_str(), present.as_bool()],
            )
            .unwrap();
        }
        for (id, row) in rows["channel"].as_object().unwrap() {
            let fields = &row["fields"];
            let members = fields.get("member_ids").map(Value::to_string);
            conn.execute("INSERT INTO channel(id,space_id,kind,name,parent_message_id,member_ids,created_at,archived_at) VALUES (?1,?2,?3,?1,?4,?5,0,?6)",params![id,fields["space_id"].as_str(),fields["kind"].as_str(),fields["parent_message_id"].as_str(),members,fields["archived_at"].as_i64()]).unwrap();
        }
        for (id, row) in rows["message"].as_object().unwrap() {
            let fields = &row["fields"];
            let visibility = fields.get("visibility").map(Value::to_string);
            conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text,visibility) VALUES (?1,?2,'account','account',?3,?4,?5)",params![id,fields["channel_id"].as_str(),fields["occurred_at"].as_i64(),fields["text"].as_str(),visibility]).unwrap();
        }
        conn.execute_batch("INSERT INTO message_fts(rowid,text,cw) SELECT rowid,text,'' FROM message").unwrap();
        for id in case["front"].as_array().unwrap() {
            conn.execute("INSERT INTO front_interval(id,account_id,subject_type,subject_id,level,is_primary,position,start_at,start_switch_id,start_tz_offset_min) VALUES (?1,'account','member',?1,?2,1,0,0,'switch',0)",params![id.as_str(),case["level"].as_str()]).unwrap();
        }
        let principal = api_data::Principal::owner("account");
        let mut view = CurrentView::new(&conn, &principal).unwrap();
        let mut canonical = rows["message"]
            .as_object()
            .unwrap()
            .keys()
            .filter(|id| view.message(id).unwrap())
            .cloned()
            .collect::<Vec<_>>();
        canonical.sort();
        assert_eq!(serde_json::json!(canonical), case["visible"], "canonical: {}", case["name"]);
        let (channels, front, cutoffs) = view.selection().unwrap();
        let sql =
            format!("SELECT m.id FROM message m WHERE {} ORDER BY m.id", message_sql("?1", "?2", "?3", "?4", "?5"));
        let selected = conn
            .prepare(&sql)
            .unwrap()
            .query_map(params!["account", channels, front, cutoffs, false], |r| r.get::<_, String>(0))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(serde_json::json!(selected), case["visible"], "SQL: {}", case["name"]);
        let page =
            search::messages(&conn, &principal, &MessageQuery { q: "needle".into(), ..Default::default() }).unwrap();
        let mut hits = page["items"].as_array().unwrap().iter().map(|m| m["id"].as_str().unwrap()).collect::<Vec<_>>();
        hits.sort();
        assert_eq!(serde_json::json!(hits), case["visible"], "FTS: {}", case["name"]);
        assert!(page["next_cursor"].is_null());
        let context = page["items"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["id"] == "hit")
            .map(|m| {
                [&m["context_before"], m, &m["context_after"]]
                    .iter()
                    .filter_map(|m| m["id"].as_str())
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        assert_eq!(serde_json::json!(context), case["context"], "context: {}", case["name"]);
    }
}
