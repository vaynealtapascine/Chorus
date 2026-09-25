//! Account import (R27): export an account from one server, import it into a fresh one, and its
//! history, tables and files are the same; the accounts it shares spaces with line up when they
//! come too; and a bundle that doesn't check out, or clashes, imports nothing.

use chorus_core::op::Op;
use chorus_server::config::Config;
use chorus_server::{db, export_job, exports, import, ingest};
use rusqlite::{Connection, params};
use serde_json::json;
use sha2::{Digest, Sha256};
mod common;

const ALICE: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const BOB: &str = "0192f8c2-0000-7000-8000-0000000000b2";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

struct Server {
    cfg: Config,
    conn: Connection,
}

fn server(prefix: &str) -> Server {
    let mut cfg = Config::default();
    cfg.server.data_dir = common::http_test_dir(prefix);
    let mut conn = db::open(&cfg.db_path()).unwrap();
    db::migrate(&mut conn).unwrap();
    Server { cfg, conn }
}

fn id(n: u8) -> String {
    chorus_core::id::new_id(1_790_000_000_000, [n; 10])
}

/// Write `bytes` into the server's file store as a complete upload by `account`.
fn store(s: &Server, account: &str, bytes: &[u8], mime: &str) -> String {
    let hash = hex(&Sha256::digest(bytes));
    let path = s.cfg.blob_dir().join(&hash[..2]).join(&hash[2..4]).join(&hash);
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(&path, bytes).unwrap();
    s.conn
        .execute(
            "INSERT INTO blob(hash,size,mime,stored_at,uploaded_by,received,is_complete) VALUES (?1,?2,?3,0,?4,?2,1)",
            params![hash, bytes.len() as i64, mime, account],
        )
        .unwrap();
    hash
}

/// Alice's year in miniature: her own space, members with an avatar, a switch, a message with a
/// file, an edit and a reaction; a message in Bob's shared space; a custom emoji (server-wide).
fn source() -> (Server, String, String) {
    let s = server("import-source");
    let now = chorus_server::now_ms();
    let mut t = now - 3_600_000;
    let mut op = |account: &str, kind: &str, scope: &str, entity: Option<&str>, payload: serde_json::Value| {
        t += 1000;
        let (ack, o) =
            ingest::op_as(&s.conn, account, &format!("dev-{account}"), kind, scope, entity, payload, t, None).unwrap();
        assert!(o.is_some(), "{kind}: {:?}", ack.error);
    };
    for (who, handle) in [(ALICE, "alice"), (BOB, "bob")] {
        s.conn
            .execute(
                "INSERT INTO account(id,kind,handle,is_admin,created_at) VALUES (?1,'system',?2,?3,0)",
                params![who, handle, who == ALICE],
            )
            .unwrap();
        ingest::grant(&s.conn, who, &format!("account:{who}")).unwrap();
    }
    let avatar = store(&s, ALICE, &[9u8; 3000], "image/png");
    let photo = store(&s, ALICE, b"a photo of tomatoes", "image/jpeg");
    let acct = format!("account:{ALICE}");
    let (home, general) = (id(1), id(2));
    let home_scope = format!("space:{home}");
    ingest::grant(&s.conn, ALICE, &home_scope).unwrap();
    op(ALICE, "space.create", &home_scope, Some(&home), json!({"kind": "internal", "name": "Home"}));
    op(ALICE, "space.join", &home_scope, Some(&home), json!({"account_id": ALICE}));
    op(
        ALICE,
        "channel.create",
        &home_scope,
        Some(&general),
        json!({"space_id": home, "kind": "text", "name": "general"}),
    );
    let (kai, mo) = (id(3), id(4));
    op(ALICE, "member.create", &acct, Some(&kai), json!({"name": "Kai", "avatar_blob": avatar}));
    op(ALICE, "member.create", &acct, Some(&mo), json!({"name": "Mo", "color": "#aabbcc"}));
    op(ALICE, "member.set", &acct, Some(&mo), json!({"pronouns": "they/them"}));
    op(
        ALICE,
        "front.switch",
        &acct,
        Some(&id(5)),
        json!({"entries": [{"subject_type": "member", "subject_id": kai, "is_primary": true}]}),
    );
    let (att, msg) = (id(6), id(7));
    op(
        ALICE,
        "attachment.create",
        &home_scope,
        Some(&att),
        json!({"blob_hash": photo, "filename": "tomatoes.jpg", "mime": "image/jpeg", "size": 19}),
    );
    op(
        ALICE,
        "message.send",
        &home_scope,
        Some(&msg),
        json!({"channel_id": general, "authors": [kai], "text": "look @Mo", "entities": [
            {"type": "mention", "offset": 5, "length": 3, "target_type": "member", "target_id": mo}],
            "attachments": [att]}),
    );
    op(
        ALICE,
        "message.edit",
        &home_scope,
        Some(&msg),
        json!({"message_id": msg, "text": "look at these, @Mo", "entities": []}),
    );
    op(
        ALICE,
        "reaction.add",
        &home_scope,
        Some(&msg),
        json!({"target_type": "message", "target_id": msg, "emoji": "🍅", "member_id": mo}),
    );
    op(ALICE, "emoji.create", "server", Some(&id(8)), json!({"name": "tomato_wave"}));
    // Bob's shared space, with Alice in it
    let (shared, lounge) = (id(10), id(11));
    let shared_scope = format!("space:{shared}");
    ingest::grant(&s.conn, BOB, &shared_scope).unwrap();
    op(BOB, "space.create", &shared_scope, Some(&shared), json!({"kind": "shared", "name": "Garden"}));
    op(BOB, "space.join", &shared_scope, Some(&shared), json!({"account_id": BOB}));
    op(BOB, "space.join", &shared_scope, Some(&shared), json!({"account_id": ALICE}));
    op(
        BOB,
        "channel.create",
        &shared_scope,
        Some(&lounge),
        json!({"space_id": shared, "kind": "text", "name": "lounge"}),
    );
    op(
        ALICE,
        "message.send",
        &shared_scope,
        Some(&id(12)),
        json!({"channel_id": lounge, "authors": [kai], "text": "hello garden", "entities": []}),
    );
    (s, avatar, photo)
}

fn export(s: &Server, account: &str, job: &str) -> std::path::PathBuf {
    export_job::build(&s.cfg, &s.conn, job, account, chorus_server::now_ms(), &mut |_| true).unwrap();
    export_job::dir(&s.cfg).join(format!("{job}.zip"))
}

/// The account's ops as the other server has them, without the seq it gave them.
fn ops_of(c: &Connection, account: &str) -> Vec<Op> {
    let text = String::from_utf8(exports::ops_jsonl(c, account).unwrap()).unwrap();
    text.lines()
        .map(|l| {
            let mut o: Op = serde_json::from_str(l).unwrap();
            o.seq = None;
            o
        })
        .collect()
}

fn release(s: Server) {
    let dir = s.cfg.server.data_dir.clone();
    drop(s);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn an_account_moves_with_its_history_tables_and_files() {
    let (a, avatar, photo) = source();
    let bundle = export(&a, ALICE, "alice");
    let mut b = server("import-target");
    let now = chorus_server::now_ms();

    // a check writes nothing
    let checked = import::import_account(
        &b.cfg,
        &mut b.conn,
        &bundle,
        &import::Options { check_only: true, ..Default::default() },
        now,
    )
    .unwrap();
    assert!(checked.invite.is_none());
    let accounts: i64 = b.conn.query_row("SELECT count(*) FROM account", [], |r| r.get(0)).unwrap();
    assert_eq!(accounts, 0, "a check imports nothing");

    let report = import::import_account(&b.cfg, &mut b.conn, &bundle, &import::Options::default(), now).unwrap();
    assert_eq!(report.handle.as_deref(), Some("alice"));
    assert_eq!(report.server_ops_left_out, 1, "the custom emoji stays with the old server");
    assert_eq!(report.files, 2);
    let others: Vec<_> = report.spaces.iter().filter(|(_, (_, own))| !own).collect();
    assert_eq!(others.len(), 1, "one space someone else owns: {report}");
    assert!(report.invite.is_some(), "a device invite to get in");

    // the same history: every op, its id, author, device and times (the emoji aside)
    let before: Vec<Op> = ops_of(&a.conn, ALICE).into_iter().filter(|o| o.scope != "server").collect();
    assert_eq!(ops_of(&b.conn, ALICE), before);
    // the same tables (daily totals depend on "now", so they're left out here)
    for name in exports::CSV_NAMES.iter().filter(|n| **n != "front_daily") {
        let (x, y) = (exports::csv(&a.conn, ALICE, name).unwrap(), exports::csv(&b.conn, ALICE, name).unwrap());
        assert_eq!(x, y, "{name}.csv differs after the import");
    }
    for q in [
        "SELECT id, text, revision_count, reply_to_id FROM message ORDER BY id",
        "SELECT source_id, target_id FROM mention ORDER BY source_id",
        "SELECT owner_id, attachment_id FROM item_attachment ORDER BY owner_id",
        "SELECT target_id, emoji, member_id, is_present FROM reaction ORDER BY target_id",
        "SELECT message_id, rev, text FROM message_revision ORDER BY message_id, rev",
    ] {
        let dump = |c: &Connection| -> Vec<String> {
            let mut st = c.prepare(q).unwrap();
            let n = st.column_count();
            st.query_map([], |r| {
                Ok((0..n)
                    .map(|i| format!("{:?}", r.get::<_, rusqlite::types::Value>(i).unwrap()))
                    .collect::<Vec<_>>()
                    .join("|"))
            })
            .unwrap()
            .map(Result::unwrap)
            .collect()
        };
        // Bob's messages don't come along: compare Alice's
        let alice_only =
            |rows: Vec<String>| -> Vec<String> { rows.into_iter().filter(|r| !r.contains("Bob")).collect() };
        assert_eq!(alice_only(dump(&a.conn)), alice_only(dump(&b.conn)), "{q}");
    }
    // the files, byte for byte, and on record as complete
    for hash in [&avatar, &photo] {
        let path = |s: &Server| s.cfg.blob_dir().join(&hash[..2]).join(&hash[2..4]).join(hash.as_str());
        assert_eq!(std::fs::read(path(&a)).unwrap(), std::fs::read(path(&b)).unwrap());
        let complete: bool =
            b.conn.query_row("SELECT is_complete FROM blob WHERE hash = ?1", [hash], |r| r.get(0)).unwrap();
        assert!(complete);
    }
    // her own space is hers; Bob's isn't readable until Bob comes too
    let shared = format!("space:{}", id(10));
    assert!(ingest::can_access(&b.conn, ALICE, &format!("space:{}", id(1))).unwrap());
    assert!(!ingest::can_access(&b.conn, ALICE, &shared).unwrap());
    let bob = export(&a, BOB, "bob");
    import::import_account(&b.cfg, &mut b.conn, &bob, &import::Options::default(), now).unwrap();
    assert!(ingest::can_access(&b.conn, ALICE, &shared).unwrap(), "Bob's space.join lets her back in");

    // again: refused, and nothing changes
    let ops: i64 = b.conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
    let err = import::import_account(&b.cfg, &mut b.conn, &bundle, &import::Options::default(), now).unwrap_err();
    assert!(format!("{err:#}").contains("already on this server"), "{err:#}");
    let after: i64 = b.conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
    assert_eq!(ops, after);

    release(b);
    release(a);
    let _ = std::fs::remove_file(bundle);
}

#[test]
fn a_damaged_or_clashing_bundle_imports_nothing() {
    let (a, _, _) = source();
    let bundle = export(&a, ALICE, "alice");
    let now = chorus_server::now_ms();

    // a byte of the history changed: the zip's CRC and the manifest's hash both catch it
    let mut bytes = std::fs::read(&bundle).unwrap();
    let at = bytes.windows(10).position(|w| w == b"look at th").unwrap();
    bytes[at] = b'L';
    let damaged = bundle.with_extension("damaged.zip");
    std::fs::write(&damaged, &bytes).unwrap();
    let mut b = server("import-damaged");
    assert!(import::import_account(&b.cfg, &mut b.conn, &damaged, &import::Options::default(), now).is_err());
    let n: i64 = b.conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
    assert_eq!(n, 0);

    // the handle is taken here: refused unless mapped
    b.conn.execute("INSERT INTO account(id,kind,handle,created_at) VALUES ('someone','person','alice',0)", []).unwrap();
    let err = import::import_account(&b.cfg, &mut b.conn, &bundle, &import::Options::default(), now).unwrap_err();
    assert!(format!("{err:#}").contains("@alice is taken"), "{err:#}");
    let n: i64 = b.conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap();
    assert_eq!(n, 0);
    let opts = import::Options { handle: Some("alice_home".into()), ..Default::default() };
    let report = import::import_account(&b.cfg, &mut b.conn, &bundle, &opts, now).unwrap();
    assert_eq!(report.handle.as_deref(), Some("alice_home"));
    let handle: String = b.conn.query_row("SELECT handle FROM account WHERE id = ?1", [ALICE], |r| r.get(0)).unwrap();
    assert_eq!(handle, "alice_home");

    release(b);
    release(a);
    let _ = std::fs::remove_file(bundle);
    let _ = std::fs::remove_file(damaged);
}
