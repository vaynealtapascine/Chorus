use std::process::Command;

use chorus_core::op::{Known, validate_new};
use chorus_server::{db, oplog, project};
use rusqlite::Connection;
use serde_json::{Value, json};

#[test]
fn seed_cli_ingests_into_new_directory_and_refuses_existing_data() {
    let target = std::env::var_os("CARGO_TARGET_DIR").map(std::path::PathBuf::from).unwrap_or_else(std::env::temp_dir);
    let root = target.join(format!("seed-cli-test-{:016x}", rand::random::<u64>()));
    let dest = root.join("data");
    let missing_config = root.join("missing.toml");
    let run = || {
        Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .args([
                "--config",
                &missing_config.to_string_lossy(),
                "seed",
                "--members",
                "3",
                "--switches",
                "4",
                "--messages",
                "5",
                "--to",
                &dest.to_string_lossy(),
            ])
            .output()
            .unwrap()
    };
    let first = run();
    assert!(first.status.success(), "{}", String::from_utf8_lossy(&first.stderr));
    assert!(String::from_utf8_lossy(&first.stdout).contains("seeded 14 ops"));
    let mut conn = db::open(&dest.join("chorus.db")).unwrap();
    for (table, expected) in [("op", 14), ("member", 3), ("switch", 4), ("message", 5)] {
        let actual: i64 = conn.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0)).unwrap();
        assert_eq!(actual, expected, "{table}");
    }
    assert_eq!(project::rebuild(&mut conn).unwrap(), 14);
    assert_eq!(conn.query_row("SELECT count(*) FROM message", [], |r| r.get::<_, i64>(0)).unwrap(), 5);
    drop(conn);
    let second = run();
    assert!(!second.status.success(), "seed must refuse to overwrite an existing directory");
    let canonical_target = target.canonicalize().unwrap();
    let canonical_root = root.canonicalize().unwrap();
    assert!(canonical_root.starts_with(&canonical_target));
    std::fs::remove_dir_all(root).unwrap();
}

fn count(conn: &Connection, sql: &str) -> i64 {
    conn.query_row(sql, [], |r| r.get(0)).unwrap()
}

fn demo_content(conn: &Connection) {
    assert_eq!(count(conn, "SELECT count(*) FROM account"), 3);
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM account WHERE handle='lanterns' AND is_admin=1 AND display_name='The Lanterns'"
        ),
        1
    );
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM system WHERE name IN ('The Lanterns','The Junipers','Driftwood') AND timezone='Asia/Manila'"
        ),
        3
    );
    let mut st = conn.prepare("SELECT name,pronouns,color FROM member ORDER BY sort_key").unwrap();
    let members: Vec<(String, String, String)> =
        st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).unwrap().collect::<Result<_, _>>().unwrap();
    assert_eq!(
        members[..4],
        [
            ("Foxglove".into(), "they/them".into(), "#B79CED".into()),
            ("Amarynthe".into(), "she/her".into(), "#F28B82".into()),
            ("Nairon".into(), "he/they".into(), "#F4B26B".into()),
            ("Violet".into(), "she/her".into(), "#E7A4D3".into()),
        ]
    );
    assert_eq!(members.len(), 8);
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM group_membership gm JOIN member_group g ON g.id=gm.group_id WHERE g.name='Day-to-day' AND gm.is_present"
        ),
        3
    );
    let mut st = conn.prepare("SELECT m.name,fi.level,fi.is_primary FROM front_interval fi JOIN member m ON m.id=fi.subject_id WHERE fi.end_at IS NULL ORDER BY m.sort_key").unwrap();
    let fronts: Vec<(String, String, bool)> =
        st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).unwrap().collect::<Result<_, _>>().unwrap();
    assert_eq!(
        fronts,
        [
            ("Foxglove".into(), "front".into(), true),
            ("Amarynthe".into(), "front".into(), false),
            ("Nairon".into(), "cocon".into(), false),
            ("Violet".into(), "present".into(), false),
            ("Juniper".into(), "front".into(), true),
            ("River".into(), "front".into(), true),
        ]
    );
    assert_eq!(count(conn, "SELECT count(*) FROM follow WHERE status='active'"), 4);
    assert_eq!(count(conn, "SELECT count(*) FROM space"), 5);
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM space WHERE kind='shared' AND name IN ('Lanterns + Friends','Art corner','Book club')"
        ),
        3
    );
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM space_member sm JOIN space s ON s.id=sm.space_id WHERE s.kind='shared' AND sm.is_present"
        ),
        9
    );
    assert_eq!(count(conn, "SELECT count(*) FROM channel"), 11);
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM channel c JOIN space s ON s.id=c.space_id WHERE s.kind='internal' AND c.name IN ('General','Handover','Quiet room','Notes to ourselves','System board')"
        ),
        5
    );
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM message WHERE pinned_at IS NOT NULL AND text='Appointment at 2 pm · details in Handover'"
        ),
        1
    );
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM message WHERE reply_to_id IS NOT NULL AND json_extract(quote,'$.text')='Morning! The appointment details are in Handover.'"
        ),
        1
    );
    assert_eq!(count(conn, "SELECT count(*) FROM reaction WHERE emoji='❤️' AND is_present"), 2);
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM message WHERE forward_of_id IS NOT NULL AND json_extract(forward_snapshot,'$[0].channel_name')='General'"
        ),
        1
    );
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM message m JOIN channel c ON c.id=m.channel_id WHERE c.kind='thread' AND c.parent_message_id=(SELECT id FROM message WHERE text='Morning! The appointment details are in Handover.')"
        ),
        3
    );
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM message m JOIN channel c ON c.id=m.thread_channel_id WHERE c.kind='thread' AND c.parent_message_id=m.id"
        ),
        1
    );
    assert_eq!(
        count(conn, "SELECT count(*) FROM channel WHERE kind='member_dm' AND json_array_length(member_ids)=2"),
        1
    );
    for text in [
        "Keys are by the door.",
        "Library card: 2209 4471",
        "Would Saturday work for you?",
        "The sketch is ready.",
        "Chapter 6 by Sunday?",
        "Thank you for checking in.",
    ] {
        assert_eq!(
            conn.query_row("SELECT count(*) FROM message WHERE text=?1", [text], |r| r.get::<_, i64>(0)).unwrap(),
            1,
            "{text}"
        );
    }
    let (definition, counts, voters): (String, String, i64) = conn.query_row(
        "SELECT m.poll,t.counts,t.voters FROM message m JOIN poll_tally t ON t.poll_id=m.id WHERE m.text='Dinner plan'", [],
        |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?))).unwrap();
    let definition: Value = serde_json::from_str(&definition).unwrap();
    assert_eq!(definition["multiple"], false);
    assert_eq!(
        definition["options"],
        json!([{"id":"soup","text":"Soup"},{"id":"rice","text":"Rice"},{"id":"pasta","text":"Pasta"}])
    );
    assert_eq!(serde_json::from_str::<Value>(&counts).unwrap(), json!({"soup":2,"rice":1,"pasta":0}));
    assert_eq!(voters, 3);
    assert_eq!(count(conn, "SELECT count(*) FROM poll_vote"), 3);
    assert_eq!(count(conn, "SELECT count(*) FROM op WHERE kind='message.poll_tally' AND device_id='server'"), 3);
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM pref p JOIN channel c ON p.key='notify_channel:'||c.id WHERE c.name='Quiet room' AND p.device_id='' AND p.value='\"none\"'"
        ),
        1
    );
    assert_eq!(
        count(conn, "SELECT count(*) FROM pref WHERE key='chat.read_per_member' AND value='true' AND device_id=''"),
        1
    );
    // Match the clients' account unread rule: exclude own-account messages, even in shared chat.
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM message m JOIN read_state r ON r.channel_id=m.channel_id AND r.reader_member_id='' WHERE m.account_id<>r.account_id AND m.occurred_at>r.last_read_message_at AND m.deleted_at IS NULL"
        ),
        4
    );
    assert_eq!(
        count(
            conn,
            "SELECT count(*) FROM message m JOIN read_state r ON r.channel_id=m.channel_id JOIN member reader ON reader.id=r.reader_member_id JOIN channel c ON c.id=m.channel_id WHERE reader.name='Amarynthe' AND c.name='General' AND (m.occurred_at,m.id)>(r.last_read_message_at,r.last_read_message_id)"
        ),
        12
    );
    assert_eq!(
        count(conn, "SELECT count(*) FROM message m JOIN channel c ON c.id=m.channel_id WHERE c.name='General'"),
        13
    );
    assert_eq!(count(conn, "SELECT count(*) FROM message"), 34);
}

/// Compare the reproducible content graph, ignoring time and production-generated tally UUIDs.
/// Notification delivery metadata and DB epochs are operational randomness, not fixture content.
fn content_graph(conn: &Connection) -> Vec<Value> {
    fn without_times(value: &mut Value) {
        match value {
            Value::Object(fields) => {
                for (key, value) in fields {
                    if matches!(key.as_str(), "message_at" | "occurred_at") {
                        *value = json!("<time>");
                    } else {
                        without_times(value);
                    }
                }
            }
            Value::Array(items) => {
                for value in items {
                    without_times(value);
                }
            }
            _ => {}
        }
    }
    oplog::applied_after(conn, 0, 1000)
        .unwrap()
        .into_iter()
        .map(|mut op| {
            without_times(&mut op.payload);
            json!({"id":if op.kind.ends_with(".poll_tally") { "<server tally>" } else { &op.id },
            "kind":op.kind,"v":op.v,"scope":op.scope,"entity":op.entity_id,"payload":op.payload,
            "account":op.account_id,"device":op.device_id,"member":op.member_id})
        })
        .collect()
}

#[test]
fn demo_cli_is_isolated_valid_rebuildable_and_reproducible() {
    let target = std::env::var_os("CARGO_TARGET_DIR").map(std::path::PathBuf::from).unwrap_or_else(std::env::temp_dir);
    let root = target.join(format!("seed-demo-cli-test-{:016x}", rand::random::<u64>()));
    let protected = root.join("configured-data");
    std::fs::create_dir_all(&protected).unwrap();
    std::fs::write(protected.join("sentinel"), b"leave configured data alone").unwrap();
    let config = root.join("demo.toml");
    std::fs::write(&config, format!("[server]\ndata_dir = '{}'\n", protected.display())).unwrap();
    let run = |dest: Option<&std::path::Path>| {
        let mut command = Command::new(env!("CARGO_BIN_EXE_chorus-server"));
        command.arg("--config").arg(&config).arg("seed-demo");
        if let Some(dest) = dest {
            command.arg("--to").arg(dest);
        }
        command.output().unwrap()
    };
    assert!(!run(None).status.success(), "--to is required; never fall back to config.data_dir");
    let first_dest = root.join("first");
    let before = chorus_server::now_ms();
    let first = run(Some(&first_dest));
    let after = chorus_server::now_ms();
    assert!(first.status.success(), "{}", String::from_utf8_lossy(&first.stderr));
    let stdout = String::from_utf8(first.stdout).unwrap();
    assert!(stdout.contains("DEMO ONLY"));
    let mut conn = db::open(&first_dest.join("chorus.db")).unwrap();
    demo_content(&conn);
    for handle in ["lanterns", "junipers", "driftwood"] {
        let id: String = conn.query_row("SELECT id FROM account WHERE handle=?1", [handle], |r| r.get(0)).unwrap();
        assert!(stdout.contains(&format!("account {handle}: {id}")));
        if handle == "lanterns" {
            assert!(stdout.contains(&format!("chorus-server --config <toml> invite --kind device --account {id}")));
        }
    }
    assert_eq!(count(&conn, "SELECT count(*) FROM follower_front_view"), 4);
    let ops = oplog::applied_after(&conn, 0, 1000).unwrap();
    assert_eq!(ops.len() as i64, count(&conn, "SELECT count(*) FROM op"));
    for op in &ops {
        assert!(matches!(validate_new(op).unwrap(), Known::Yes(_)), "{}", op.kind);
        if op.kind == "front.switch" {
            assert!((before - 2 * 3_600_000..=after - 2 * 3_600_000).contains(&op.time()));
        }
    }
    // General has exactly yesterday + today in the fixture's Asia/Manila timezone.
    assert_eq!(
        count(
            &conn,
            "SELECT count(DISTINCT (m.occurred_at+28800000)/86400000) FROM message m JOIN channel c ON c.id=m.channel_id WHERE c.name='General'"
        ),
        2
    );
    assert!(ops.iter().all(|op| op.time() <= after));
    let graph = content_graph(&conn);
    let count_before = ops.len() as u64;
    assert_eq!(project::rebuild(&mut conn).unwrap(), count_before);
    demo_content(&conn);
    assert_eq!(content_graph(&conn), graph, "a rebuild must not rewrite any op");
    drop(conn);
    let second_dest = root.join("second");
    let second = run(Some(&second_dest));
    assert!(second.status.success(), "{}", String::from_utf8_lossy(&second.stderr));
    let conn = db::open(&second_dest.join("chorus.db")).unwrap();
    assert_eq!(content_graph(&conn), graph);
    drop(conn);
    let refused = run(Some(&first_dest));
    assert!(!refused.status.success());
    assert!(String::from_utf8_lossy(&refused.stderr).contains("demo destination must be new"));
    let conn = db::open(&first_dest.join("chorus.db")).unwrap();
    assert_eq!(content_graph(&conn), graph, "refusal must leave the existing DB intact");
    drop(conn);
    let empty = root.join("empty");
    std::fs::create_dir(&empty).unwrap();
    assert!(!run(Some(&empty)).status.success(), "even an empty existing directory is refused");
    assert!(!empty.join("chorus.db").exists());
    assert_eq!(std::fs::read(protected.join("sentinel")).unwrap(), b"leave configured data alone");
    assert_eq!(std::fs::read_dir(&protected).unwrap().count(), 1);
    let canonical_root = root.canonicalize().unwrap();
    assert!(canonical_root.starts_with(target.canonicalize().unwrap()));
    std::fs::remove_dir_all(root).unwrap();
}
