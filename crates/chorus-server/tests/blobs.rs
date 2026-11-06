use chorus_server::{app, auth, config::Config, db, follows};
use proptest::prelude::*;
use proptest::test_runner::{Config as PropConfig, TestCaseError, TestRunner};
use sha2::{Digest, Sha256};

struct Server {
    base: String,
    data: std::path::PathBuf,
    state: app::AppState,
}

impl Server {
    async fn new() -> Self {
        let mut conn = db::open_memory().unwrap();
        db::migrate(&mut conn).unwrap();
        db::set_meta(&conn, "instance_id", "blob-test").unwrap();
        let now = chorus_server::now_ms();
        for (id, token) in [("alice", "alice-token"), ("bob", "bob-token")] {
            conn.execute(
                "INSERT INTO account(id, kind, handle, created_at) VALUES (?1,'person',?1,?2)",
                rusqlite::params![id, now],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at) VALUES (?1,?2,?1,?1,'cli','test',?3)",
                rusqlite::params![format!("{id}-device"), id, now],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
                rusqlite::params![auth::hash(token), format!("{id}-device"), now, now + 86_400_000],
            )
            .unwrap();
        }
        let data = std::env::var_os("CARGO_TARGET_DIR")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(std::env::temp_dir)
            .join(format!("chorus-blob-test-{}", rand::random::<u64>()));
        let mut cfg = Config::default();
        cfg.server.data_dir = data.clone();
        let state = app::Shared::new(conn, cfg).unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}/api/v1/blobs", listener.local_addr().unwrap());
        let serving = state.clone();
        tokio::spawn(async move { axum::serve(listener, app::router(serving)).await.unwrap() });
        Server { base, data, state }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        if self.data.exists() {
            std::fs::remove_dir_all(&self.data).unwrap()
        }
    }
}

fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

async fn resume_case(bytes: Vec<u8>, chunks: Vec<usize>) -> Result<(), TestCaseError> {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let url = format!("{}/{}", s.base, hash(&bytes));
    let mut offset = 0usize;
    for wanted in chunks.into_iter().chain(std::iter::once(bytes.len())) {
        if offset == bytes.len() {
            break;
        }
        let end = (offset + wanted.max(1)).min(bytes.len());
        let range = format!("bytes {}-{}/{}", offset, end - 1, bytes.len());
        let response = client
            .put(&url)
            .bearer_auth("alice-token")
            .header("content-range", &range)
            .body(bytes[offset..end].to_vec())
            .send()
            .await
            .unwrap();
        prop_assert_eq!(response.status().as_u16(), if end == bytes.len() { 201 } else { 202 });
        if end < bytes.len() {
            let head = client.head(&url).bearer_auth("alice-token").send().await.unwrap();
            prop_assert_eq!(head.status().as_u16(), 206);
            prop_assert_eq!(head.headers()["upload-offset"].to_str().unwrap(), end.to_string());
            // A duplicate or stale chunk must not advance the cursor or alter the bytes.
            let stale = client
                .put(&url)
                .bearer_auth("alice-token")
                .header("content-range", &range)
                .body(bytes[offset..end].to_vec())
                .send()
                .await
                .unwrap();
            prop_assert_eq!(stale.status().as_u16(), 409);
            let head = client.head(&url).bearer_auth("alice-token").send().await.unwrap();
            prop_assert_eq!(head.headers()["upload-offset"].to_str().unwrap(), end.to_string());
        }
        offset = end;
    }
    prop_assert_eq!(offset, bytes.len());
    let response = client.get(&url).bearer_auth("alice-token").send().await.unwrap();
    prop_assert_eq!(response.status().as_u16(), 200);
    let downloaded = response.bytes().await.unwrap();
    prop_assert_eq!(downloaded.as_ref(), bytes.as_slice());
    prop_assert_eq!(client.head(&url).bearer_auth("alice-token").send().await.unwrap().status().as_u16(), 200);
    Ok(())
}

#[test]
fn arbitrary_chunk_partitions_resume_without_duplication() {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let mut runner = TestRunner::new(PropConfig::with_cases(30));
    runner
        .run(
            &(prop::collection::vec(any::<u8>(), 2..2048), prop::collection::vec(1usize..512, 1..12)),
            |(bytes, chunks)| runtime.block_on(resume_case(bytes, chunks)),
        )
        .unwrap();
}

#[tokio::test]
async fn emoji_catalogue_needs_auth_and_excludes_retired_rows() {
    let s = Server::new().await;
    {
        let conn = s.state.db.lock().unwrap();
        for (id, name, deleted) in [("one", "wave", None), ("two", "old", Some(1))] {
            conn.execute(
                "INSERT INTO custom_emoji(id,name,blob_hash,created_by,created_at,deleted_at)
                 VALUES (?1,?2,'hash','alice',0,?3)",
                rusqlite::params![id, name, deleted],
            )
            .unwrap();
        }
    }
    let url = s.base.replace("/blobs", "/emoji");
    let client = reqwest::Client::new();
    assert_eq!(client.get(&url).send().await.unwrap().status(), 401);
    let response = client.get(&url).bearer_auth("bob-token").send().await.unwrap();
    assert_eq!(response.status(), 200);
    let body: serde_json::Value = response.json().await.unwrap();
    assert_eq!(body["emoji"].as_array().unwrap().len(), 1);
    assert_eq!(body["emoji"][0]["name"], "wave");
}

#[tokio::test]
async fn resume_range_and_stranger_denied() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let data = b"hello, chorus";
    let url = format!("{}/{}", s.base, hash(data));
    let put = |range: &str, body: &'static [u8]| {
        client.put(&url).bearer_auth("alice-token").header("content-range", range).body(body)
    };
    let r = put("bytes 0-5/13", b"hello,").send().await.unwrap();
    assert_eq!(r.status(), 202);
    assert_eq!(r.headers()["upload-offset"], "6");
    let r = client.head(&url).bearer_auth("alice-token").send().await.unwrap();
    assert_eq!(r.status(), 206);
    assert_eq!(r.headers()["upload-offset"], "6");
    assert_eq!(client.head(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
    let bob_put = |range: &str, body: &'static [u8]| {
        client.put(&url).bearer_auth("bob-token").header("content-range", range).body(body)
    };
    assert_eq!(bob_put("bytes 6-12/13", b" chorus").send().await.unwrap().status(), 403, "not bob's upload");
    let r = put("bytes 6-12/13", b" chorus").send().await.unwrap();
    assert_eq!(r.status(), 201);
    // Someone else's file answers like a new upload, so a PUT says nothing about what the server
    // has: wrong bytes are taken chunk by chunk and refused at the end...
    assert_eq!(bob_put("bytes 0-5/13", b"HELLO,").send().await.unwrap().status(), 202);
    let r = client.head(&url).bearer_auth("bob-token").send().await.unwrap();
    assert_eq!((r.status().as_u16(), r.headers()["upload-offset"].to_str().unwrap()), (206, "6"), "resumable");
    assert_eq!(bob_put("bytes 6-12/13", b" chorus").send().await.unwrap().status(), 400, "hash_mismatch");
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
    // ...and the same bytes (a device re-sending its files after a restore) are done, not refused
    // (a refusal kept that device retrying forever); Bob now holds the file and may read it
    assert_eq!(bob_put("bytes 0-12/13", b"hello, chorus").send().await.unwrap().status(), 201);
    assert_eq!(bob_put("bytes 0-12/13", b"hello, chorus").send().await.unwrap().status(), 200);
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200);
    assert_eq!(client.head(&url).bearer_auth("alice-token").send().await.unwrap().status(), 200);
    let r = client.get(&url).bearer_auth("alice-token").header("range", "bytes=7-12").send().await.unwrap();
    assert_eq!(r.status(), 206);
    assert_eq!(r.headers()["content-range"], "bytes 7-12/13");
    assert!(r.headers()["cache-control"].to_str().unwrap().contains("immutable"));
    assert_eq!(r.bytes().await.unwrap(), "chorus");
}

#[tokio::test]
async fn shared_attachment_requires_public_structured_visibility() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let bytes = b"shared attachment";
    let digest = hash(bytes);
    let url = format!("{}/{digest}", s.base);
    assert_eq!(
        client
            .put(&url)
            .bearer_auth("alice-token")
            .header("content-range", "bytes 0-16/17")
            .body(bytes.as_slice())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    {
        let c = s.state.db.lock().unwrap();
        c.execute("INSERT INTO space(id,owner_account_id,kind,created_at) VALUES ('s','alice','shared',0)", [])
            .unwrap();
        c.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('ch','s','text',0)", []).unwrap();
        c.execute("INSERT INTO scope_access(account_id,scope) VALUES ('bob','space:s')", []).unwrap();
        c.execute("INSERT INTO space_member(space_id,account_id,joined_hlc) VALUES ('s','bob','1:0:1')", []).unwrap();
        c.execute(
            "INSERT INTO message(id,channel_id,account_id,occurred_at,text) VALUES ('m','ch','alice',0,'hello')",
            [],
        )
        .unwrap();
        c.execute("INSERT INTO attachment(id,account_id,blob_hash,created_at) VALUES ('a','alice',?1,0)", [&digest])
            .unwrap();
        c.execute(
            "INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position) VALUES ('message','m','a',0)",
            [],
        )
        .unwrap();
    }
    let r = client.get(&url).bearer_auth("bob-token").send().await.unwrap();
    assert_eq!(r.status(), 200);
    // access-checked files: no shared caches, no sniffing, never a page that runs script
    assert!(r.headers()["cache-control"].to_str().unwrap().starts_with("private"));
    assert_eq!(r.headers()["x-content-type-options"], "nosniff");
    assert!(r.headers()["content-security-policy"].to_str().unwrap().contains("sandbox"));
    for (rule, expected) in
        [(r#"{"mode":"all"}"#, 200), (r#"{"mode":"system_only"}"#, 404), (r#"{"mode":"members","member_ids":[]}"#, 404)]
    {
        s.state.db.lock().unwrap().execute("UPDATE message SET visibility=?1 WHERE id='m'", [rule]).unwrap();
        assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), expected, "{rule}");
    }
}

#[tokio::test]
async fn post_attachment_tracks_current_post_audience() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let bytes = b"journal attachment";
    let digest = hash(bytes);
    let url = format!("{}/{digest}", s.base);
    assert_eq!(
        client
            .put(&url)
            .bearer_auth("alice-token")
            .header("content-range", format!("bytes 0-{}/{}", bytes.len() - 1, bytes.len()))
            .body(bytes.as_slice())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    {
        let c = s.state.db.lock().unwrap();
        c.execute(
            "INSERT INTO post(id,account_id,device_id,kind,text,visibility,occurred_at)
                   VALUES ('p','alice','alice-device','note','hi','{\"mode\":\"private\"}',0)",
            [],
        )
        .unwrap();
        c.execute("INSERT INTO attachment(id,account_id,blob_hash,created_at) VALUES ('a','alice',?1,0)", [&digest])
            .unwrap();
        c.execute(
            "INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position)
                   VALUES ('post','p','a',0)",
            [],
        )
        .unwrap();
    }
    let read = || client.get(&url).bearer_auth("bob-token");
    assert_eq!(read().send().await.unwrap().status(), 404);
    assert_eq!(client.head(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE post SET visibility='{\"mode\":\"server\"}' WHERE id='p'", []).unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 200);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE post SET visibility='{\"mode\":\"followers\"}' WHERE id='p'", []).unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute(
            "INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at)
                   VALUES ('f','bob','alice','active',0)",
            [],
        )
        .unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 200);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE follow SET status='ended' WHERE id='f'", []).unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE follow SET status='active' WHERE id='f'", []).unwrap();
    }
    {
        let c = s.state.db.lock().unwrap();
        c.execute("INSERT INTO bucket(id,account_id,name) VALUES ('b','alice','Close')", []).unwrap();
        c.execute("UPDATE post SET visibility='{\"mode\":\"buckets\",\"bucket_ids\":[\"b\"]}' WHERE id='p'", [])
            .unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute(
            "INSERT INTO bucket_assignment(bucket_id,follower_account_id,added_hlc)
                   VALUES ('b','bob','1:0:1')",
            [],
        )
        .unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 200);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE bucket_assignment SET removed_hlc='2:0:1' WHERE bucket_id='b'", []).unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE post SET visibility='not-json' WHERE id='p'", []).unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE post SET visibility='{\"mode\":\"server\"}',deleted_at=1 WHERE id='p'", []).unwrap();
    }
    assert_eq!(read().send().await.unwrap().status(), 404);
}

#[tokio::test]
async fn mismatch_discards_only_partial_and_limit_is_enforced() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let good = hash(b"good");
    let url = format!("{}/{good}", s.base);
    let r = client
        .put(&url)
        .bearer_auth("alice-token")
        .header("content-range", "bytes 0-3/4")
        .body("bad!")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 400);
    assert_eq!(client.head(&url).bearer_auth("alice-token").send().await.unwrap().status(), 404);
    assert!(!s.data.join("blobs").join(&good[..2]).join(&good[2..4]).join(format!("{good}.part")).exists());
    let r = client
        .put(&url)
        .bearer_auth("alice-token")
        .header("content-range", "bytes 0-3/200000000")
        .body("good")
        .send()
        .await
        .unwrap();
    assert_eq!(r.status(), 413);
}

#[tokio::test]
async fn follower_avatar_is_unavailable_until_member_is_revealed() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let bytes = b"avatar";
    let digest = hash(bytes);
    let url = format!("{}/{digest}", s.base);
    assert_eq!(
        client
            .put(&url)
            .bearer_auth("alice-token")
            .header("content-range", "bytes 0-5/6")
            .body(bytes.as_slice())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    {
        let c = s.state.db.lock().unwrap();
        c.execute(
            "INSERT INTO member(id,account_id,name,avatar_blob,created_at) VALUES ('m1','alice','Visible',?1,0)",
            [&digest],
        )
        .unwrap();
        c.execute("INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at) VALUES ('f1','bob','alice','active',0)", []).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("INSERT INTO follower_front_view(follower_account_id,target_account_id,entries,revealed_at) VALUES ('bob','alice',?1,1)",
            [r#"[{"t":"subject","subject_type":"member","subject_id":"m1","level":"front","is_primary":true,"name":"Visible"}]"#]).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200);
    // Revealing this member's front does not grant their private profile banner.
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE member SET avatar_blob = NULL, banner_blob = ?1 WHERE id = 'm1'", [&digest]).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
    assert_eq!(client.get(&url).bearer_auth("alice-token").send().await.unwrap().status(), 200);
}

#[tokio::test]
async fn account_avatar_is_readable_to_an_active_follower() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let bytes = b"account-avatar";
    let digest = hash(bytes);
    let url = format!("{}/{digest}", s.base);
    assert_eq!(
        client
            .put(&url)
            .bearer_auth("alice-token")
            .header("content-range", "bytes 0-13/14")
            .body(bytes.as_slice())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    {
        let c = s.state.db.lock().unwrap();
        c.execute("UPDATE account SET avatar_blob = ?1 WHERE id = 'alice'", [&digest]).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at) VALUES ('f1','bob','alice','active',0)", []).unwrap();
        assert_eq!(follows::list(&c, "bob").unwrap()["following"][0]["account"]["avatar_blob"], digest);
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200);
}

#[tokio::test]
async fn channel_images_follow_current_view_permission_and_thread_parent_privacy() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let bytes = b"channel image";
    let digest = hash(bytes);
    let url = format!("{}/{digest}", s.base);
    assert_eq!(
        client
            .put(&url)
            .bearer_auth("alice-token")
            .header("content-range", "bytes 0-12/13")
            .body(bytes.as_slice())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    {
        let c = s.state.db.lock().unwrap();
        c.execute("INSERT INTO space(id,kind,owner_account_id,created_at) VALUES ('s','internal','alice',0)", [])
            .unwrap();
        c.execute(
            "INSERT INTO channel(id,space_id,kind,name,avatar_blob,created_at) VALUES ('c','s','text','Garden',?1,0)",
            [&digest],
        )
        .unwrap();
        c.execute("INSERT INTO scope_access(account_id,scope) VALUES ('bob','space:s')", []).unwrap();
    }
    let status = || client.get(&url).bearer_auth("bob-token").send();
    assert_eq!(status().await.unwrap().status(), 404, "scope access alone is insufficient");
    {
        let c = s.state.db.lock().unwrap();
        c.execute(r#"INSERT INTO channel_permission(channel_id,target_type,target_id,allow,deny,hlc) VALUES ('c','account','bob','["view"]','[]','0:0:0')"#, []).unwrap();
    }
    assert_eq!(status().await.unwrap().status(), 200, "an explicit channel guest sees its image");
    {
        let c = s.state.db.lock().unwrap();
        c.execute(r#"UPDATE channel_permission SET allow='[]',deny='["view"]' WHERE channel_id='c'"#, []).unwrap();
    }
    assert_eq!(status().await.unwrap().status(), 404, "revoking view revokes the image");
    {
        let c = s.state.db.lock().unwrap();
        c.execute(r#"UPDATE channel_permission SET allow='["view"]',deny='[]' WHERE channel_id='c'"#, []).unwrap();
        c.execute(r#"INSERT INTO message(id,channel_id,account_id,text,occurred_at,received_at,visibility) VALUES ('private','c','alice','private',0,0,'{"mode":"system_only"}')"#, []).unwrap();
        c.execute("UPDATE channel SET avatar_blob=NULL WHERE id='c'", []).unwrap();
        c.execute("INSERT INTO channel(id,space_id,kind,name,parent_message_id,avatar_blob,created_at) VALUES ('t','s','thread','Thread','private',?1,0)", [&digest]).unwrap();
    }
    assert_eq!(status().await.unwrap().status(), 404, "a private thread parent keeps its image private");
}

#[tokio::test]
async fn private_chat_attachment_rechecks_recipient_front_even_with_a_retained_hash() {
    let s = Server::new().await;
    let client = reqwest::Client::new();
    let bytes = b"current-front-only-media";
    let blob = hash(bytes);
    let url = format!("{}/{}", s.base, blob);
    assert_eq!(
        client
            .put(&url)
            .bearer_auth("alice-token")
            .header("content-range", format!("bytes 0-{}/{}", bytes.len() - 1, bytes.len()))
            .body(bytes.to_vec())
            .send()
            .await
            .unwrap()
            .status(),
        201
    );
    {
        let conn = s.state.db.lock().unwrap();
        for (member, group, part) in
            [("bob-garden", "garden-group", "bob-nested"), ("bob-nested", "nested-group", "bob-member")]
        {
            conn.execute("INSERT INTO member(id,account_id,name,created_at) VALUES (?1,'bob',?1,0)", [member]).unwrap();
            conn.execute(
                "INSERT INTO member_group(id,account_id,name,kind,created_at) VALUES (?1,'bob',?1,'subsystem',0)",
                [group],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO group_membership(group_id,member_id,added_hlc) VALUES (?1,?2,'1')",
                rusqlite::params![group, part],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO pref(account_id,device_id,key,value,hlc) VALUES ('bob','',?1,?2,'1')",
                rusqlite::params![format!("member_subsystem:{member}"), serde_json::json!(group).to_string()],
            )
            .unwrap();
        }
        conn.execute("INSERT INTO member(id,account_id,name,created_at) VALUES ('bob-member','bob','Bob',0)", [])
            .unwrap();
        conn.execute(
            "INSERT INTO space(id,kind,owner_account_id,created_at) VALUES ('private-space','dm','alice',0)",
            [],
        )
        .unwrap();
        for account in ["alice", "bob"] {
            conn.execute("INSERT INTO scope_access(account_id,scope) VALUES (?1,'space:private-space')", [account])
                .unwrap();
            conn.execute("INSERT INTO space_member(space_id,account_id,role,joined_hlc) VALUES ('private-space',?1,'member','1')",[account]).unwrap();
        }
        conn.execute("INSERT INTO channel(id,space_id,kind,name,member_ids,created_at) VALUES ('private-channel','private-space','member_dm','Private','[\"bob-garden\"]',0)",[]).unwrap();
        conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text) VALUES ('private-message','private-channel','alice','alice-device',1,'photo')",[]).unwrap();
        conn.execute("INSERT INTO channel(id,space_id,kind,name,parent_message_id,created_at) VALUES ('private-thread','private-space','thread','Thread','private-message',0)",[]).unwrap();
        conn.execute("INSERT INTO message(id,channel_id,account_id,device_id,occurred_at,text) VALUES ('private-thread-message','private-thread','alice','alice-device',2,'thread photo')",[]).unwrap();
        conn.execute("INSERT INTO attachment(id,account_id,blob_hash,mime,created_at) VALUES ('private-photo','alice',?1,'image/png',0)",[&blob]).unwrap();
        conn.execute("INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position) VALUES ('message','private-thread-message','private-photo',0)",[]).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
    {
        let conn = s.state.db.lock().unwrap();
        conn.execute("INSERT INTO front_interval(id,account_id,subject_type,subject_id,level,is_primary,position,start_at,start_switch_id,start_tz_offset_min) VALUES ('bob-live','bob','member','bob-member','front',1,0,0,'switch',0)",[]).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200);
    // Each revocation independently hides the retained hash through inherited thread privacy.
    for (deny, restore) in [
        (
            "UPDATE group_membership SET removed_hlc='2' WHERE member_id='bob-member'",
            "UPDATE group_membership SET removed_hlc=NULL WHERE member_id='bob-member'",
        ),
        (
            "UPDATE member SET archived_at=3 WHERE id='bob-member'",
            "UPDATE member SET archived_at=NULL WHERE id='bob-member'",
        ),
        (
            "UPDATE member SET deleted_at=3 WHERE id='bob-member'",
            "UPDATE member SET deleted_at=NULL WHERE id='bob-member'",
        ),
        (
            "UPDATE member SET archived_at=3 WHERE id='bob-nested'",
            "UPDATE member SET archived_at=NULL WHERE id='bob-nested'",
        ),
        (
            "UPDATE member_group SET deleted_at=3 WHERE id='nested-group'",
            "UPDATE member_group SET deleted_at=NULL WHERE id='nested-group'",
        ),
        (
            "UPDATE channel SET member_ids='[\"bob-garden\",42]' WHERE id='private-channel'",
            "UPDATE channel SET member_ids='[\"bob-garden\"]' WHERE id='private-channel'",
        ),
        (
            "UPDATE channel SET archived_at=3 WHERE id='private-channel'",
            "UPDATE channel SET archived_at=NULL WHERE id='private-channel'",
        ),
        (
            "UPDATE channel SET archived_at=3 WHERE id='private-thread'",
            "UPDATE channel SET archived_at=NULL WHERE id='private-thread'",
        ),
        (
            "UPDATE channel SET member_ids='[\"bob-member\"]' WHERE id='private-channel'; UPDATE front_interval SET subject_id='bob-garden' WHERE id='bob-live'",
            "UPDATE channel SET member_ids='[\"bob-garden\"]' WHERE id='private-channel'; UPDATE front_interval SET subject_id='bob-member' WHERE id='bob-live'",
        ),
    ] {
        s.state.db.lock().unwrap().execute_batch(deny).unwrap();
        for method in [reqwest::Method::GET, reqwest::Method::HEAD] {
            assert_eq!(
                client.request(method, &url).bearer_auth("bob-token").send().await.unwrap().status(),
                404,
                "{deny}"
            );
        }
        s.state.db.lock().unwrap().execute_batch(restore).unwrap();
        assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200, "{restore}");
    }
    {
        s.state.db.lock().unwrap().execute("UPDATE front_interval SET end_at=2 WHERE id='bob-live'", []).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 404);
}
