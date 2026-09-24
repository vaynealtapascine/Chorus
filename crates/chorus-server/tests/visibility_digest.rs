use std::time::{Duration, Instant};

use chorus_core::sync::Digest;
use chorus_server::{db, oplog, visibility};
use rusqlite::{Connection, params};
use serde_json::json;

fn put(conn: &Connection, id: &str, account: &str, kind: &str, entity: Option<&str>, payload: serde_json::Value) {
    conn.execute(
        "INSERT INTO op(id,scope,kind,entity_id,payload,hlc,account_id,device_id,
         occurred_at,device_at,tz_offset_min,received_at)
         VALUES (?1,'space:s',?2,?3,?4,'0:0:0',?5,'dev',0,0,0,0)",
        params![id, kind, entity, payload.to_string(), account],
    )
    .unwrap();
}

#[test]
fn batched_digest_matches_individual_visibility_across_pages() {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    conn.execute("INSERT INTO space(id,owner_account_id,kind,created_at) VALUES ('s','alice','shared',0)", []).unwrap();
    conn.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('public','s','text',0)", []).unwrap();
    for who in ["alice", "bob"] {
        conn.execute("INSERT INTO space_member(space_id,account_id,joined_hlc) VALUES ('s',?1,'1:0:1')", [who])
            .unwrap();
    }
    for (id, rule) in [("open", None), ("aside", Some(r#"{"mode":"system_only"}"#))] {
        conn.execute(
            "INSERT INTO message(id,channel_id,account_id,text,visibility,occurred_at)
             VALUES (?1,'public','alice','hello',?2,0)",
            params![id, rule],
        )
        .unwrap();
    }
    conn.execute(
        "INSERT INTO channel(id,space_id,kind,parent_message_id,created_at) VALUES ('hidden','s','thread','aside',0)",
        [],
    )
    .unwrap();
    for (attachment, message) in [("open-file", "open"), ("aside-file", "aside")] {
        conn.execute(
            "INSERT INTO item_attachment(owner_type,owner_id,attachment_id,position) VALUES ('message',?1,?2,0)",
            params![message, attachment],
        )
        .unwrap();
    }
    let tx = conn.transaction().unwrap();
    for i in 0..1_050 {
        put(&tx, &format!("own-{i}"), "alice", "noop", None, json!({}));
    }
    for (id, kind, entity, payload) in [
        ("public-send", "message.send", Some("open"), json!({"channel_id":"public"})),
        (
            "private-send",
            "message.send",
            Some("aside"),
            json!({"channel_id":"public","visibility":{"mode":"system_only"}}),
        ),
        ("thread-send", "message.send", Some("thread-msg"), json!({"channel_id":"hidden"})),
        ("public-edit", "message.edit", Some("open"), json!({"message_id":"open"})),
        ("private-edit", "message.edit", Some("aside"), json!({"message_id":"aside"})),
        ("public-reaction", "reaction.add", Some("r1"), json!({"message_id":"open"})),
        ("private-reaction", "reaction.add", Some("r2"), json!({"message_id":"aside"})),
        ("private-thread", "channel.create", Some("hidden"), json!({"parent_message_id":"aside"})),
        ("public-file", "attachment.create", Some("open-file"), json!({})),
        ("private-file", "attachment.create", Some("aside-file"), json!({})),
        ("other", "unknown", None, json!({})),
    ] {
        put(&tx, id, "bob", kind, entity, payload);
    }
    tx.commit().unwrap();
    let mut baseline = Digest::default();
    for op in oplog::scope_after(&conn, "space:s", 0, 2_000).unwrap() {
        if visibility::op_visible_to(&conn, "alice", &op).unwrap() {
            baseline.add(&op.id);
        }
    }
    assert_eq!(visibility::visible_digest(&conn, "alice", "space:s").unwrap(), baseline);
    assert_eq!(visibility::visible_digest(&conn, "bob", "space:s").unwrap().count, 1_061);
}

#[test]
#[ignore = "manual 50,000-op digest timing check"]
fn own_authored_scope_digest_50k() {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    conn.execute_batch(
        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<50000)
         INSERT INTO op(id,scope,kind,payload,hlc,account_id,device_id,
                        occurred_at,device_at,tz_offset_min,received_at)
         SELECT printf('op-%05d',i),'space:s','noop','{}','0:0:0','alice','dev',0,0,0,0 FROM n",
    )
    .unwrap();
    let start = Instant::now();
    let digest = visibility::visible_digest(&conn, "alice", "space:s").unwrap();
    let elapsed = start.elapsed();
    assert_eq!(digest.count, 50_000);
    assert_eq!(digest, oplog::digest(&conn, "space:s").unwrap());
    eprintln!("50k own-authored digest: {elapsed:?}");
    assert!(elapsed < Duration::from_secs(5), "50k digest took {elapsed:?}");

    conn.execute("INSERT INTO space(id,owner_account_id,kind,created_at) VALUES ('s','alice','shared',0)", []).unwrap();
    conn.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('ch','s','text',0)", []).unwrap();
    conn.execute(
        "INSERT INTO message(id,channel_id,account_id,text,occurred_at) VALUES ('open','ch','alice','hello',0)",
        [],
    )
    .unwrap();
    conn.execute(
        r#"UPDATE op SET account_id='bob',kind='message.edit',payload='{"message_id":"open"}' WHERE seq%2=0"#,
        [],
    )
    .unwrap();
    let start = Instant::now();
    let mixed = visibility::visible_digest(&conn, "alice", "space:s").unwrap();
    let elapsed = start.elapsed();
    assert_eq!(mixed, oplog::digest(&conn, "space:s").unwrap());
    eprintln!("50k mixed-author digest: {elapsed:?}");
    assert!(elapsed < Duration::from_secs(5), "50k mixed digest took {elapsed:?}");
}
