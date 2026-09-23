use chorus_server::{app, auth, config::Config, db, follows};
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
    assert_eq!(client.head(&url).bearer_auth("bob-token").send().await.unwrap().status(), 403);
    let r = put("bytes 6-12/13", b" chorus").send().await.unwrap();
    assert_eq!(r.status(), 201);
    assert_eq!(client.head(&url).bearer_auth("alice-token").send().await.unwrap().status(), 200);
    let r = client.get(&url).bearer_auth("alice-token").header("range", "bytes=7-12").send().await.unwrap();
    assert_eq!(r.status(), 206);
    assert_eq!(r.headers()["content-range"], "bytes 7-12/13");
    assert!(r.headers()["cache-control"].to_str().unwrap().contains("immutable"));
    assert_eq!(r.bytes().await.unwrap(), "chorus");
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 403);
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
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200);
    for (rule, expected) in
        [(r#"{"mode":"all"}"#, 200), (r#"{"mode":"system_only"}"#, 403), (r#"{"mode":"members","member_ids":[]}"#, 403)]
    {
        s.state.db.lock().unwrap().execute("UPDATE message SET visibility=?1 WHERE id='m'", [rule]).unwrap();
        assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), expected, "{rule}");
    }
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
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 403);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("INSERT INTO follower_front_view(follower_account_id,target_account_id,entries,revealed_at) VALUES ('bob','alice',?1,1)",
            [r#"[{"t":"subject","subject_type":"member","subject_id":"m1","level":"front","is_primary":true,"name":"Visible"}]"#]).unwrap();
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200);
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
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 403);
    {
        let c = s.state.db.lock().unwrap();
        c.execute("INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at) VALUES ('f1','bob','alice','active',0)", []).unwrap();
        assert_eq!(follows::list(&c, "bob").unwrap()["following"][0]["account"]["avatar_blob"], digest);
    }
    assert_eq!(client.get(&url).bearer_auth("bob-token").send().await.unwrap().status(), 200);
}
