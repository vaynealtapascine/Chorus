//! The full export bundle (D-068, export_job.rs) over HTTP: a job builds a zip of the account's
//! own history and files, and nothing of anyone else's; progress, Range download by key, expiry
//! after a download, delete, and the job's life across restarts and cancels.

use chorus_core::hlc::Hlc;
use chorus_server::{api_data, app, auth, config::Config, db, export_job};
use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};
mod common;

const ALICE: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const BOB: &str = "0192f8c2-0000-7000-8000-0000000000b2";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

struct Seeded {
    conn: Connection,
    /// hash → bytes to write into the blob store
    files: Vec<(String, Vec<u8>)>,
    alice_blobs: Vec<String>,
    bob_blob: String,
    missing: String,
    damaged: String,
}

fn seed() -> Seeded {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    let now = chorus_server::now_ms();
    let mut n = 0u8;
    let mut op = |conn: &Connection, account: &str, device: &str, kind: &str, payload: serde_json::Value| {
        n += 1;
        conn.execute(
            "INSERT INTO op(id,scope,kind,entity_id,payload,v,hlc,account_id,device_id,
              occurred_at,device_at,tz_offset_min,seen_seq,received_at)
             VALUES (?1,?2,?3,?4,?5,1,?6,?7,?8,?9,?9,0,0,?9)",
            params![
                chorus_core::id::new_id(n as u64, [n; 10]),
                format!("account:{account}"),
                kind,
                chorus_core::id::new_id(n as u64, [n + 100; 10]),
                payload.to_string(),
                Hlc::new(now as u64 + u64::from(n), 0, 1).to_string(),
                account,
                device,
                now + i64::from(n),
            ],
        )
        .unwrap();
    };
    let mut devices = Vec::new();
    for (account, label, seed) in [(ALICE, "alice", 1u8), (BOB, "bob", 2u8)] {
        let device = chorus_core::id::new_id(seed as u64 + 20, [seed + 20; 10]);
        conn.execute(
            "INSERT INTO account(id,kind,handle,created_at) VALUES (?1,'system',?2,0)",
            params![account, label],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at) VALUES (?1,?2,?1,?1,'cli','test',0)",
            params![device, account],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES (?1,?2,?3,?4)",
            params![auth::hash(&format!("{label}-session")), device, now, now + 86_400_000],
        )
        .unwrap();
        devices.push(device);
    }
    let blob = |conn: &Connection, bytes: &[u8], mime: &str, by: &str, hash: Option<String>| {
        let hash = hash.unwrap_or_else(|| hex(&Sha256::digest(bytes)));
        conn.execute(
            "INSERT INTO blob(hash,size,mime,stored_at,uploaded_by,received,is_complete) VALUES (?1,?2,?3,0,?4,?2,1)",
            params![hash, bytes.len() as i64, mime, by],
        )
        .unwrap();
        hash
    };
    let photo = b"a photo of tomatoes".to_vec();
    let avatar = vec![7u8; 5000];
    let bobs = b"bob's secret file".to_vec();
    let damaged_bytes = b"not what the hash says".to_vec();
    let photo_h = blob(&conn, &photo, "image/jpeg", &devices[0], None);
    let avatar_h = blob(&conn, &avatar, "image/png", &devices[0], None);
    let bob_h = blob(&conn, &bobs, "text/plain", &devices[1], None);
    let damaged_h = blob(&conn, &damaged_bytes, "text/plain", &devices[0], Some("ab".repeat(32)));
    let missing_h = "cd".repeat(32);
    op(&conn, ALICE, &devices[0], "member.create", serde_json::json!({"name": "Kai", "avatar_blob": avatar_h}));
    op(
        &conn,
        ALICE,
        &devices[0],
        "attachment.create",
        serde_json::json!({"blob_hash": photo_h, "filename": "tomatoes.jpg", "mime": "image/jpeg", "size": photo.len()}),
    );
    op(
        &conn,
        ALICE,
        &devices[0],
        "attachment.create",
        serde_json::json!({"blob_hash": photo_h, "filename": "again.jpg", "mime": "image/jpeg", "size": photo.len()}),
    );
    op(
        &conn,
        ALICE,
        &devices[0],
        "attachment.create",
        serde_json::json!({"blob_hash": missing_h, "filename": "lost.txt"}),
    );
    op(
        &conn,
        ALICE,
        &devices[0],
        "attachment.create",
        serde_json::json!({"blob_hash": damaged_h, "filename": "bad.txt"}),
    );
    op(&conn, BOB, &devices[1], "attachment.create", serde_json::json!({"blob_hash": bob_h, "filename": "secret.txt"}));
    Seeded {
        conn,
        files: vec![
            (photo_h.clone(), photo),
            (avatar_h.clone(), avatar),
            (bob_h.clone(), bobs),
            (damaged_h.clone(), damaged_bytes),
        ],
        alice_blobs: vec![photo_h, avatar_h],
        bob_blob: bob_h,
        missing: missing_h,
        damaged: damaged_h,
    }
}

fn python() -> Option<&'static str> {
    // `--version` must succeed: on Windows `python3` may be the Store's installer stub
    ["python3", "python"]
        .into_iter()
        .find(|p| std::process::Command::new(p).arg("--version").output().is_ok_and(|o| o.status.success()))
}

#[tokio::test(flavor = "multi_thread")]
async fn a_full_export_bundles_the_accounts_own_history_and_files() {
    let s = seed();
    let read_token = api_data::create_token(
        &s.conn,
        &api_data::Principal::owner(ALICE),
        "front",
        &["read:front".into()],
        chorus_server::now_ms(),
    )
    .unwrap()["token"]
        .as_str()
        .unwrap()
        .to_string();
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir("export-bundle-test");
    let test_dir = cfg.server.data_dir.clone();
    std::fs::create_dir_all(&cfg.server.data_dir).unwrap();
    for (hash, bytes) in &s.files {
        let path = cfg.blob_dir().join(&hash[..2]).join(&hash[2..4]).join(hash);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, bytes).unwrap();
    }
    db::backup_to(&s.conn, &cfg.db_path()).unwrap();
    let state = app::Shared::new(db::open(&cfg.db_path()).unwrap(), cfg.clone()).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let root = format!("http://{}", listener.local_addr().unwrap());
    let api = format!("{root}/api/v1");
    let serving = state.clone();
    let server = tokio::spawn(async move { axum::serve(listener, app::router(serving)).await.unwrap() });
    let client = reqwest::Client::new();

    // only a session or a token with `export`
    let r = client.post(format!("{api}/exports")).bearer_auth(&read_token).json(&serde_json::json!({"kind": "full"}));
    assert_eq!(r.send().await.unwrap().status(), 403);
    let r =
        client.post(format!("{api}/exports")).bearer_auth("alice-session").json(&serde_json::json!({"kind": "nope"}));
    assert_eq!(r.send().await.unwrap().status(), 400);
    let r =
        client.post(format!("{api}/exports")).bearer_auth("alice-session").json(&serde_json::json!({"kind": "full"}));
    let r = r.send().await.unwrap();
    assert_eq!(r.status(), 202);
    let id = r.json::<serde_json::Value>().await.unwrap()["id"].as_str().unwrap().to_string();
    // it's Alice's: Bob can't see it
    let bob = client.get(format!("{api}/jobs/{id}")).bearer_auth("bob-session").send().await.unwrap();
    assert_eq!(bob.status(), 404);
    let mut job = serde_json::Value::Null;
    for _ in 0..200 {
        job = client
            .get(format!("{api}/jobs/{id}"))
            .bearer_auth("alice-session")
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        if job["status"] != "queued" && job["status"] != "running" {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    assert_eq!(job["status"], "done", "{job}");
    let today = chorus_server::zip::dos_time(chorus_server::now_ms()).1;
    assert!(job["file_name"].as_str().unwrap().starts_with("chorus-alice-"), "{job}");
    assert!(job["file_name"].as_str().unwrap().contains(&format!("{:04}", (today >> 9) + 1980)));
    let latest: serde_json::Value = client
        .get(format!("{api}/exports/latest"))
        .bearer_auth("alice-session")
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(latest["id"], id);
    let url = format!("{root}{}", job["result_url"].as_str().unwrap());

    // the key is the credential
    let (no_key, _) = url.split_once("?key=").unwrap();
    assert_eq!(client.get(no_key).send().await.unwrap().status(), 404);
    assert_eq!(client.get(format!("{no_key}?key=00")).send().await.unwrap().status(), 404);
    // a phone resumes with Range
    let part = client.get(&url).header("range", "bytes=0-99").send().await.unwrap();
    assert_eq!(part.status(), 206);
    assert_eq!(part.bytes().await.unwrap().len(), 100);
    let whole = client.get(&url).send().await.unwrap();
    assert_eq!(whole.status(), 200);
    assert_eq!(whole.headers()["content-type"], "application/zip");
    let bytes = whole.bytes().await.unwrap();
    assert_eq!(bytes.len() as i64, job["bytes"].as_i64().unwrap());
    // a complete download: the file goes within the hour
    let after: serde_json::Value =
        client.get(format!("{api}/jobs/{id}")).bearer_auth("alice-session").send().await.unwrap().json().await.unwrap();
    assert!(after["expires_at"].as_i64().unwrap() <= chorus_server::now_ms() + export_job::AFTER_DOWNLOAD_MS);
    // and the account was told in the app
    let told: i64 = state
        .db
        .lock()
        .unwrap()
        .query_row(
            "SELECT count(*) FROM notification WHERE recipient_account_id = ?1 AND kind = 'export_ready' AND delivered_at IS NOT NULL",
            [ALICE],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(told, 1);

    // what's inside, read back by Python's zipfile
    if let Some(py) = python() {
        let zip_path = test_dir.join("check.zip");
        std::fs::write(&zip_path, &bytes).unwrap();
        let script = format!(
            "import zipfile,hashlib,json\nz=zipfile.ZipFile(r'{}')\nassert z.testzip() is None\n\
             m=json.loads(z.read('manifest.json'))\nops=z.read('ops.jsonl')\n\
             print(json.dumps({{'names':sorted(z.namelist()),'m':m,'ops_sha':hashlib.sha256(ops).hexdigest(),\
             'ops_lines':len(ops.splitlines()),'blob_ok':all(hashlib.sha256(z.read(n)).hexdigest()==n[6:] \
             for n in z.namelist() if n.startswith('blobs/') and n[6:]!='{}')}}))",
            zip_path.display(),
            s.damaged
        );
        let out = std::process::Command::new(py).arg("-c").arg(&script).output().unwrap();
        assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
        let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
        let names: Vec<String> = serde_json::from_value(v["names"].clone()).unwrap();
        for want in ["README.txt", "manifest.json", "ops.jsonl"] {
            assert!(names.contains(&want.to_string()), "{names:?}");
        }
        for table in chorus_server::exports::CSV_NAMES {
            assert!(names.contains(&format!("csv/{table}.csv")), "{names:?}");
        }
        for h in &s.alice_blobs {
            assert!(names.contains(&format!("blobs/{h}")), "{names:?}");
        }
        assert!(!names.contains(&format!("blobs/{}", s.bob_blob)), "never another account's files");
        assert!(!names.contains(&format!("blobs/{}", s.missing)));
        let m = &v["m"];
        assert_eq!(m["format"], 1);
        assert_eq!(m["account"]["handle"], "alice");
        assert_eq!(m["ops"]["count"], 5);
        assert_eq!(v["ops_lines"], 5, "Alice's ops only");
        assert_eq!(m["ops"]["sha256"], v["ops_sha"]);
        assert_eq!(m["missing"], serde_json::json!([s.missing]));
        assert_eq!(m["damaged"], serde_json::json!([s.damaged]));
        assert_eq!(v["blob_ok"], true);
        let photo = m["blobs"].as_array().unwrap().iter().find(|b| b["hash"] == s.alice_blobs[0]).unwrap();
        assert_eq!(photo["filenames"], serde_json::json!(["again.jpg", "tomatoes.jpg"]));
        assert_eq!(photo["mime"], "image/jpeg");
    } else {
        eprintln!("no python: the zip's contents weren't checked");
    }

    // delete now: the file and its link are gone
    let del = client.delete(format!("{api}/jobs/{id}")).bearer_auth("alice-session").send().await.unwrap();
    assert_eq!(del.status(), 204);
    assert_eq!(client.get(&url).send().await.unwrap().status(), 404);
    let gone: serde_json::Value =
        client.get(format!("{api}/jobs/{id}")).bearer_auth("alice-session").send().await.unwrap().json().await.unwrap();
    assert_eq!(gone["status"], "expired");
    assert!(!export_job::dir(&cfg).join(format!("{id}.zip")).exists());

    server.abort();
    let _ = server.await;
    drop(client);
    common::release(state, &test_dir).await;
}

/// The job table on its own: one job per account, cancel stops a running one, a restart fails
/// what was running, and finished files expire.
#[test]
fn jobs_are_one_at_a_time_cancellable_and_survive_restarts_as_failures() {
    let s = seed();
    let conn = &s.conn;
    let dir = std::env::temp_dir().join(format!("chorus-export-jobs-{:016x}", rand::random::<u64>()));
    let mut cfg = Config::default();
    cfg.server.data_dir = dir.clone();
    std::fs::create_dir_all(export_job::dir(&cfg)).unwrap();
    let now = chorus_server::now_ms();
    let export_job::Created::New(a) = export_job::create(conn, ALICE, now).unwrap() else { panic!() };
    assert!(matches!(export_job::create(conn, ALICE, now).unwrap(), export_job::Created::Busy(ref b) if *b == a));
    assert!(matches!(export_job::create(conn, BOB, now).unwrap(), export_job::Created::New(_)));
    let p = export_job::Progress { phase: "ops", done: 1, total: 9, bytes: 10 };
    assert!(export_job::report(conn, &a, &p).unwrap());
    assert!(!export_job::cancel(conn, &cfg, BOB, &a, now).unwrap(), "not Bob's to cancel");
    assert!(export_job::cancel(conn, &cfg, ALICE, &a, now).unwrap());
    assert!(!export_job::report(conn, &a, &p).unwrap(), "the builder is told to stop");
    export_job::finish(conn, &a, Ok(("x.zip".into(), 1)), now).unwrap();
    assert_eq!(
        export_job::view(conn, ALICE, &a).unwrap().unwrap()["status"],
        "cancelled",
        "a late finish changes nothing"
    );
    // restart
    let export_job::Created::New(b) = export_job::create(conn, ALICE, now).unwrap() else { panic!() };
    export_job::report(conn, &b, &p).unwrap();
    export_job::fail_interrupted(conn, &cfg, now).unwrap();
    let v = export_job::view(conn, ALICE, &b).unwrap().unwrap();
    assert_eq!(v["status"], "failed");
    assert!(v["error"].as_str().unwrap().contains("restarted"));
    // expiry
    let export_job::Created::New(c) = export_job::create(conn, ALICE, now).unwrap() else { panic!() };
    export_job::finish(conn, &c, Ok(("c.zip".into(), 3)), now).unwrap();
    let path = export_job::dir(&cfg).join(format!("{c}.zip"));
    std::fs::write(&path, b"zip").unwrap();
    assert_eq!(export_job::expire(conn, &cfg, now + export_job::KEEP_MS - 1).unwrap(), 0);
    assert_eq!(export_job::expire(conn, &cfg, now + export_job::KEEP_MS).unwrap(), 1);
    assert!(!path.exists());
    assert_eq!(export_job::view(conn, ALICE, &c).unwrap().unwrap()["result_url"], serde_json::Value::Null);
    std::fs::remove_dir_all(dir).ok();
}
