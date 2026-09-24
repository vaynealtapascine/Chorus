//! `chorus-server purge` (D-053): the one true erase, run as the operator would.

use std::fs;
use std::path::PathBuf;
use std::process::Command;

use chorus_server::{db, ingest};
use rusqlite::{Connection, params};
use serde_json::json;
use sha2::{Digest, Sha256};

const SYS: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const SPACE: &str = "0192f8c2-0000-7000-8000-0000000000d1";
const CHAN: &str = "0192f8c2-0000-7000-8000-0000000000c1";
const KAI: &str = "0192f8c2-0000-7000-8000-0000000000b1";
const SECRET: &str = "0192f8c2-0000-7000-8000-000000000101";
const KEEP: &str = "0192f8c2-0000-7000-8000-000000000102";
const PHOTO: &str = "0192f8c2-0000-7000-8000-000000000201";

#[test]
fn purge_erases_a_message_its_edits_attachment_and_file() {
    let target = env!("CARGO_TARGET_TMPDIR");
    let root = PathBuf::from(target).join(format!("purge-cli-test-{:016x}", rand::random::<u64>()));
    let data = root.join("data");
    fs::create_dir_all(&data).unwrap();
    let body = b"a private photo";
    let hash = format!("{:x}", Sha256::digest(body));
    let file = data.join("blobs").join(&hash[..2]).join(&hash[2..4]).join(&hash);
    {
        let mut c = db::open(&data.join("chorus.db")).unwrap();
        db::migrate(&mut c).unwrap();
        c.execute("INSERT INTO account(id,kind,handle,created_at) VALUES (?1,'system','sys',0)", [SYS]).unwrap();
        let (acct, space) = (format!("account:{SYS}"), format!("space:{SPACE}"));
        ingest::grant(&c, SYS, &acct).unwrap();
        ingest::grant(&c, SYS, &space).unwrap();
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(&file, body).unwrap();
        c.execute(
            "INSERT INTO blob(hash,size,mime,stored_at,uploaded_by,received,is_complete) VALUES (?1,?2,'image/png',0,?3,?2,1)",
            params![hash, body.len() as i64, SYS],
        )
        .unwrap();
        let op = |c: &Connection, kind: &str, scope: &str, id: &str, p: serde_json::Value| {
            ingest::server_op(c, SYS, kind, scope, Some(id), p, chorus_server::now_ms()).unwrap();
        };
        op(&c, "member.create", &acct, KAI, json!({"name": "Kai"}));
        op(&c, "space.create", &space, SPACE, json!({"kind": "internal", "name": "Home"}));
        op(&c, "channel.create", &space, CHAN, json!({"space_id": SPACE, "kind": "text", "name": "general"}));
        op(
            &c,
            "attachment.create",
            &space,
            PHOTO,
            json!({"blob_hash": hash, "filename": "private.png", "mime": "image/png", "size": body.len()}),
        );
        op(
            &c,
            "message.send",
            &space,
            SECRET,
            json!({"channel_id": CHAN, "authors": [KAI], "text": "the secret plan", "entities": [], "attachments": [PHOTO]}),
        );
        op(
            &c,
            "message.edit",
            &space,
            SECRET,
            json!({"message_id": SECRET, "text": "the secret plan, edited", "entities": []}),
        );
        op(
            &c,
            "message.send",
            &space,
            KEEP,
            json!({"channel_id": CHAN, "authors": [KAI], "text": "keep this plan", "entities": []}),
        );
    }
    fs::write(
        root.join("chorus.toml"),
        format!("[server]\ndata_dir = '{}'\n", data.to_string_lossy().replace('\\', "/")),
    )
    .unwrap();
    let run = |args: &[&str]| {
        Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .arg("--config")
            .arg(root.join("chorus.toml"))
            .args(args)
            .output()
            .unwrap()
    };
    let before: i64 = {
        let c = Connection::open(data.join("chorus.db")).unwrap();
        c.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap()
    };

    let out = run(&["purge", "--message", SECRET, "--yes"]);
    assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
    assert!(
        String::from_utf8_lossy(&out.stdout).contains("purged 3 op(s)"),
        "{}",
        String::from_utf8_lossy(&out.stdout)
    );

    let c = Connection::open(data.join("chorus.db")).unwrap();
    let count: i64 = c.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
    assert_eq!(count, before, "op ids stay, so sync digests don't change");
    let leaks: i64 = c
        .query_row(
            "SELECT count(*) FROM op WHERE instr(payload, 'secret') > 0 OR instr(payload, 'private.png') > 0",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(leaks, 0, "no purged content left in the log");
    let rows: i64 = c.query_row("SELECT count(*) FROM message WHERE id = ?1", [SECRET], |r| r.get(0)).unwrap();
    assert_eq!(rows, 0);
    let attachments: i64 = c.query_row("SELECT count(*) FROM attachment", [], |r| r.get(0)).unwrap();
    assert_eq!(attachments, 0);
    let found: i64 =
        c.query_row("SELECT count(*) FROM message_fts WHERE message_fts MATCH 'secret'", [], |r| r.get(0)).unwrap();
    assert_eq!(found, 0, "gone from search");
    let kept: String = c.query_row("SELECT text FROM message WHERE id = ?1", [KEEP], |r| r.get(0)).unwrap();
    assert_eq!(kept, "keep this plan");
    assert!(!file.exists(), "the photo nothing uses any more is deleted");
    let log = fs::read_to_string(data.join("purge.log")).unwrap();
    assert!(log.contains(SECRET) && log.contains(&hash), "{log}");

    // nothing left to purge; without --yes and no confirmation nothing happens
    assert!(
        String::from_utf8_lossy(&run(&["purge", "--message", SECRET, "--yes"]).stdout).contains("nothing to purge")
    );
    let out = run(&["purge", "--message", KEEP]);
    assert!(String::from_utf8_lossy(&out.stdout).contains("nothing changed"));
    let kept: String = c.query_row("SELECT text FROM message WHERE id = ?1", [KEEP], |r| r.get(0)).unwrap();
    assert_eq!(kept, "keep this plan");
    drop(c);
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn purge_removes_a_test_account_and_refuses_an_admin() {
    const ADMIN: &str = "0192f8c2-0000-7000-8000-0000000000aa";
    const TEST: &str = "0192f8c2-0000-7000-8000-0000000000bb";
    const HOME: &str = "0192f8c2-0000-7000-8000-0000000000dd";
    const TEST_HOME: &str = "0192f8c2-0000-7000-8000-0000000000de";
    const FOLLOW: &str = "0192f8c2-0000-7000-8000-0000000000f1";
    let target = env!("CARGO_TARGET_TMPDIR");
    let root = PathBuf::from(target).join(format!("purge-account-test-{:016x}", rand::random::<u64>()));
    let data = root.join("data");
    fs::create_dir_all(&data).unwrap();
    {
        let mut c = db::open(&data.join("chorus.db")).unwrap();
        db::migrate(&mut c).unwrap();
        for (id, admin, handle) in [(ADMIN, true, "owner"), (TEST, false, "tester")] {
            c.execute(
                "INSERT INTO account(id,kind,handle,is_admin,created_at) VALUES (?1,'system',?2,?3,0)",
                params![id, handle, admin],
            )
            .unwrap();
            ingest::grant(&c, id, &format!("account:{id}")).unwrap();
        }
        c.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at) VALUES ('dev-test',?1,'dt','Phone','android','k',0)",
            [TEST],
        )
        .unwrap();
        c.execute(
            "INSERT INTO session(token_hash,device_id,created_at,expires_at) VALUES ('h','dev-test',0,9999999999999)",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO api_token(id,account_id,name,token_hash,scopes,created_at) VALUES ('tok',?1,'t','th','[]',0)",
            [TEST],
        )
        .unwrap();
        let now = chorus_server::now_ms();
        let op = |who: &str, kind: &str, scope: &str, id: &str, p: serde_json::Value| {
            ingest::server_op(&c, who, kind, scope, Some(id), p, now).unwrap();
        };
        for (who, home, text) in [(ADMIN, HOME, "the owner's note"), (TEST, TEST_HOME, "a tester's note")] {
            let space = format!("space:{home}");
            ingest::grant(&c, who, &space).unwrap();
            op(who, "space.create", &space, home, json!({"kind": "internal", "name": "Home"}));
            let chan = if who == ADMIN {
                "0192f8c2-0000-7000-8000-0000000000c1"
            } else {
                "0192f8c2-0000-7000-8000-0000000000c2"
            };
            op(who, "channel.create", &space, chan, json!({"space_id": home, "kind": "text", "name": "general"}));
            let msg = if who == ADMIN {
                "0192f8c2-0000-7000-8000-000000000301"
            } else {
                "0192f8c2-0000-7000-8000-000000000302"
            };
            op(
                who,
                "message.send",
                &space,
                msg,
                json!({"channel_id": chan, "authors": [], "text": text, "entities": []}),
            );
        }
        // the tester follows the owner (the request lives in the owner's scope)
        op(
            ADMIN,
            "follow.request",
            &format!("account:{ADMIN}"),
            FOLLOW,
            json!({"follower_account_id": TEST, "target_account_id": ADMIN}),
        );
    }
    fs::write(
        root.join("chorus.toml"),
        format!("[server]\ndata_dir = '{}'\n", data.to_string_lossy().replace('\\', "/")),
    )
    .unwrap();
    let run = |args: &[&str]| {
        Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .arg("--config")
            .arg(root.join("chorus.toml"))
            .args(args)
            .output()
            .unwrap()
    };

    let refused = run(&["purge", "--account", ADMIN, "--yes"]);
    assert!(!refused.status.success());
    assert!(String::from_utf8_lossy(&refused.stderr).contains("admin"), "{}", String::from_utf8_lossy(&refused.stderr));

    let out = run(&["purge", "--account", TEST, "--yes"]);
    assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
    let c = Connection::open(data.join("chorus.db")).unwrap();
    let count = |sql: &str| -> i64 { c.query_row(sql, [TEST], |r| r.get(0)).unwrap() };
    assert_eq!(count("SELECT count(*) FROM account WHERE id = ?1"), 0);
    assert_eq!(count("SELECT count(*) FROM device WHERE account_id = ?1"), 0);
    assert_eq!(count("SELECT count(*) FROM api_token WHERE account_id = ?1"), 0);
    assert_eq!(count("SELECT count(*) FROM scope_access WHERE account_id = ?1"), 0);
    assert_eq!(count("SELECT count(*) FROM follow WHERE follower_account_id = ?1"), 0);
    assert_eq!(c.query_row("SELECT count(*) FROM session", [], |r| r.get::<_, i64>(0)).unwrap(), 0);
    let leaks: i64 = c
        .query_row(
            "SELECT count(*) FROM op WHERE instr(payload, 'tester') > 0 OR instr(payload, ?1) > 0",
            [TEST],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(leaks, 0, "nothing of the tester left in the log");
    let kept: String = c.query_row("SELECT text FROM message WHERE text LIKE 'the owner%'", [], |r| r.get(0)).unwrap();
    assert_eq!(kept, "the owner's note");
    assert_eq!(c.query_row("SELECT count(*) FROM account WHERE id = ?1", [ADMIN], |r| r.get::<_, i64>(0)).unwrap(), 1);
    drop(c);
    let _ = fs::remove_dir_all(&root);
}
