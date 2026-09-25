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
