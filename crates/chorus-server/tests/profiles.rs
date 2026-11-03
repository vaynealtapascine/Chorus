//! Friend profiles (D-101): a viewer sees a member only through its audience, and each part or
//! field only through its own audience too. Audiences of the wrong shape fail closed (the schema
//! already refuses JSON that doesn't parse).

use chorus_server::{db, posts, profiles};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

const ALICE: &str = "0192f8c2-0000-7000-8000-0000000000a1";
const BOB: &str = "0192f8c2-0000-7000-8000-0000000000b2";
const CAROL: &str = "0192f8c2-0000-7000-8000-0000000000c3";
const DAVE: &str = "0192f8c2-0000-7000-8000-0000000000d4";
const BUCKET: &str = "0192f8c2-0000-7000-8000-0000000000e5";
const GONE: &str = "0192f8c2-0000-7000-8000-0000000000f6";

fn seed() -> Connection {
    let mut conn = db::open_memory().unwrap();
    db::migrate(&mut conn).unwrap();
    for id in [ALICE, BOB, CAROL, DAVE] {
        conn.execute("INSERT INTO account(id,kind,created_at) VALUES (?1,'system',0)", [id]).unwrap();
    }
    // Bob and Carol follow Alice; Bob is in the live "Close" bucket, Carol only in a deleted one;
    // Dave's follow has ended
    for (id, who, status) in [("f1", BOB, "active"), ("f2", CAROL, "active"), ("f3", DAVE, "ended")] {
        conn.execute(
            "INSERT INTO follow(id,follower_account_id,target_account_id,status,created_at) VALUES (?1,?2,?3,?4,0)",
            params![id, who, ALICE, status],
        )
        .unwrap();
    }
    conn.execute("INSERT INTO bucket(id,account_id,name) VALUES (?1,?2,'Close')", params![BUCKET, ALICE]).unwrap();
    conn.execute("INSERT INTO bucket(id,account_id,name,deleted_at) VALUES (?1,?2,'Old',5)", params![GONE, ALICE])
        .unwrap();
    for (bucket, who) in [(BUCKET, BOB), (GONE, CAROL), (BUCKET, DAVE)] {
        conn.execute(
            "INSERT INTO bucket_assignment(bucket_id,follower_account_id,added_hlc) VALUES (?1,?2,'1:0:1')",
            params![bucket, who],
        )
        .unwrap();
    }
    let bucket = json!({"mode": "buckets", "bucket_ids": [BUCKET]});
    let close = bucket.to_string();
    let parts =
        json!({"pronouns": {"mode": "private"}, "banner": {"mode": "followers"}, "birthday": bucket}).to_string();
    for (id, name, visibility, field_visibility, sort) in [
        ("kai", "Kai", r#"{"mode":"followers"}"#.to_string(), parts.clone(), "a"),
        ("nai", "Nairon", close.clone(), "{}".to_string(), "b"),
        ("fox", "Foxglove", r#"{"mode":"private"}"#.to_string(), "{}".to_string(), "c"),
        ("amy", "Amarynthe", r#"{"mode":"server"}"#.to_string(), "[1]".to_string(), "d"),
        ("bad", "Broken", r#"{"mode":"everyone"}"#.to_string(), "{}".to_string(), "e"),
    ] {
        conn.execute(
            "INSERT INTO member(id,account_id,name,pronouns,description,birthday,avatar_blob,banner_blob,sigils,
                                visibility,field_visibility,sort_key,short_id,created_at)
             VALUES (?1,?2,?3,'she/her','Hello','2000-01-02',?4,?5,'[\"🌿\"]',?6,?7,?8,?1,0)",
            params![
                id,
                ALICE,
                name,
                format!("avatar-{id}"),
                format!("banner-{id}"),
                visibility,
                field_visibility,
                sort
            ],
        )
        .unwrap();
    }
    conn.execute(
        "INSERT INTO member(id,account_id,name,visibility,archived_at,short_id,created_at)
         VALUES ('old',?1,'Archived','{\"mode\":\"server\"}',9,'old',0)",
        [ALICE],
    )
    .unwrap();
    for (id, name, default) in [
        ("role", "Roles", r#"{"mode":"followers"}"#),
        ("loc", "Location", r#"{"mode":"private"}"#),
        ("site", "Website", r#"{"mode":"server"}"#),
        ("empty", "Empty", r#"{"mode":"server"}"#),
    ] {
        conn.execute(
            "INSERT INTO field_def(id,account_id,name,type,default_visibility) VALUES (?1,?2,?3,'text',?4)",
            params![id, ALICE, name, default],
        )
        .unwrap();
    }
    conn.execute(
        "INSERT INTO field_def(id,account_id,name,type,default_visibility,deleted_at) VALUES ('dead',?1,'Dead','text','{\"mode\":\"server\"}',3)",
        [ALICE],
    )
    .unwrap();
    for (member, field, value) in [
        ("kai", "role", r#"["Host"]"#),
        ("kai", "loc", r#""Wellington""#),
        ("kai", "site", r#""https://example.org""#),
        ("kai", "empty", r#""  ""#),
        ("kai", "dead", r#""boo""#),
        ("nai", "loc", r#""Auckland""#),
    ] {
        conn.execute(
            "INSERT INTO field_value(member_id,field_id,value,hlc) VALUES (?1,?2,?3,'1:0:1')",
            params![member, field, value],
        )
        .unwrap();
    }
    // Nairon shares Location with followers on their own profile
    conn.execute("UPDATE member SET field_visibility = '{\"loc\":{\"mode\":\"followers\"}}' WHERE id = 'nai'", [])
        .unwrap();
    conn
}

fn ids(v: &Value) -> Vec<&str> {
    v["items"].as_array().unwrap().iter().map(|m| m["id"].as_str().unwrap()).collect()
}

fn field_names(v: &Value) -> Vec<&str> {
    v["fields"].as_array().unwrap().iter().map(|f| f["name"].as_str().unwrap()).collect()
}

#[test]
fn each_viewer_sees_only_the_members_shared_with_them() {
    let conn = seed();
    assert_eq!(ids(&profiles::members(&conn, BOB, ALICE).unwrap()), ["kai", "nai", "amy"]);
    // in a deleted bucket only: followers-wide and server members, not the bucket one
    assert_eq!(ids(&profiles::members(&conn, CAROL, ALICE).unwrap()), ["kai", "amy"]);
    // an ended follow is no follow, whatever buckets it was in
    assert_eq!(ids(&profiles::members(&conn, DAVE, ALICE).unwrap()), ["amy"]);
    // the owner sees everyone who isn't archived, including malformed and private ones
    assert_eq!(ids(&profiles::members(&conn, ALICE, ALICE).unwrap()), ["kai", "nai", "fox", "amy", "bad"]);
}

#[test]
fn parts_and_fields_only_narrow() {
    let conn = seed();
    let kai = profiles::profile(&conn, BOB, ALICE, "kai").unwrap().unwrap();
    assert_eq!(kai["name"], "Kai");
    assert_eq!(kai["sigil"], "🌿");
    assert_eq!(kai["avatar_blob"], "avatar-kai");
    assert_eq!(kai["pronouns"], Value::Null, "pronouns are private");
    assert_eq!(kai["banner_blob"], "banner-kai");
    assert_eq!(kai["birthday"], "2000-01-02", "Bob is in the bucket");
    assert_eq!(kai["description"], "Hello", "an unset part follows the profile");
    // Location defaults private; empty values and deleted fields never show
    assert_eq!(field_names(&kai), ["Roles", "Website"]);
    assert_eq!(kai["fields"][0]["value"], json!(["Host"]));

    let kai = profiles::profile(&conn, CAROL, ALICE, "kai").unwrap().unwrap();
    assert_eq!(kai["birthday"], Value::Null, "Carol's only bucket was deleted");
    assert_eq!(field_names(&kai), ["Roles", "Website"]);

    // a member's own choice overrides the field's default
    let nai = profiles::profile(&conn, BOB, ALICE, "nai").unwrap().unwrap();
    assert_eq!(field_names(&nai), ["Location"]);
}

#[test]
fn malformed_and_unshared_profiles_fail_closed() {
    let conn = seed();
    for (viewer, member) in [(BOB, "fox"), (BOB, "bad"), (BOB, "old"), (BOB, "nobody"), (CAROL, "nai"), (DAVE, "kai")] {
        assert!(profiles::profile(&conn, viewer, ALICE, member).unwrap().is_none(), "{viewer} {member}");
    }
    // a parts map of the wrong shape shares the profile but none of the parts
    let amy = profiles::profile(&conn, DAVE, ALICE, "amy").unwrap().unwrap();
    assert_eq!(amy["name"], "Amarynthe");
    for part in ["pronouns", "description", "birthday", "banner_blob"] {
        assert_eq!(amy[part], Value::Null, "{part}");
    }
    // and never a word about fronting or timestamps
    for key in ["fronting", "last_fronted", "created_at", "updated_at", "visibility", "field_visibility"] {
        assert!(amy.get(key).is_none(), "{key}");
    }
}

#[test]
fn blobs_follow_the_profile_and_the_banner_part() {
    let conn = seed();
    assert!(profiles::blob_visible(&conn, BOB, "avatar-kai").unwrap());
    assert!(profiles::blob_visible(&conn, BOB, "banner-kai").unwrap());
    assert!(profiles::blob_visible(&conn, DAVE, "avatar-amy").unwrap());
    assert!(!profiles::blob_visible(&conn, DAVE, "banner-amy").unwrap(), "a wrong-shaped parts map hides the banner");
    assert!(!profiles::blob_visible(&conn, DAVE, "avatar-kai").unwrap());
    assert!(!profiles::blob_visible(&conn, BOB, "avatar-fox").unwrap());
    assert!(!profiles::blob_visible(&conn, CAROL, "banner-nai").unwrap());
}

#[test]
fn member_rows_carry_their_banner_and_role_pills_only_where_shared() {
    let conn = seed();
    let row = |viewer: &str, id: &str| {
        let list = profiles::members(&conn, viewer, ALICE).unwrap();
        list["items"].as_array().unwrap().iter().find(|m| m["id"] == id).cloned().unwrap()
    };
    // Kai shares the banner with followers; Roles (named so, no choice made) defaults to followers
    let kai = row(BOB, "kai");
    assert_eq!(kai["banner_blob"], "banner-kai");
    assert_eq!(kai["roles"], json!(["Host"]));
    assert_eq!(kai["pronouns"], Value::Null, "pronouns are private");
    // a wrong-shaped parts map hides the banner and every field
    let amy = row(DAVE, "amy");
    assert_eq!(amy["banner_blob"], Value::Null);
    assert_eq!(amy["roles"], json!([]));
    // a field that isn't shared shows no pills: the owner picks Location as the roles field
    conn.execute(
        "INSERT INTO pref(account_id,device_id,key,value,hlc) VALUES (?1,'','members.view',?2,'1:0:1')",
        params![ALICE, json!({"pills": "all", "role_field": "loc"}).to_string()],
    )
    .unwrap();
    assert_eq!(row(BOB, "kai")["roles"], json!([]), "Location is private");
    assert_eq!(row(BOB, "nai")["roles"], json!(["Auckland"]), "Nairon shares it with followers");
    // and pills turned off show none
    conn.execute("UPDATE pref SET value = '{\"pills\":\"off\"}' WHERE key = 'members.view'", []).unwrap();
    assert_eq!(row(BOB, "kai")["roles"], json!([]));
    // the owner sees their own as their Members list does
    conn.execute("UPDATE pref SET value = '{\"pills\":\"first\"}' WHERE key = 'members.view'", []).unwrap();
    conn.execute(
        "UPDATE field_value SET value = '[\"Host\",\"Protector\"]' WHERE member_id = 'kai' AND field_id = 'role'",
        [],
    )
    .unwrap();
    assert_eq!(row(ALICE, "kai")["roles"], json!(["Host"]));
}

#[test]
fn roles_read_like_the_members_list() {
    let fixture: Value =
        serde_json::from_str(include_str!("../../../fixtures/ui_member_list.json")).expect("member list fixture");
    for case in fixture["roles"].as_array().unwrap() {
        assert_eq!(json!(profiles::roles_of(&case["value"])), case["roles"], "{}", case["value"]);
    }
}

#[test]
fn post_author_cards_carry_a_picture_only_where_the_profile_opens() {
    let conn = seed();
    for (post, author) in [("p-kai", "kai"), ("p-fox", "fox"), ("p-nai", "nai")] {
        conn.execute(
            "INSERT INTO post(id,account_id,kind,text,visibility,occurred_at) VALUES (?1,?2,'note','hi','{\"mode\":\"followers\"}',1)",
            params![post, ALICE],
        )
        .unwrap();
        conn.execute("INSERT INTO post_author(post_id,member_id,position) VALUES (?1,?2,0)", params![post, author])
            .unwrap();
    }
    let card = |viewer: &str, post: &str| posts::one(&conn, viewer, post).unwrap().unwrap()["author_cards"][0].clone();
    let kai = card(BOB, "p-kai");
    assert_eq!((kai["profile"].clone(), kai["avatar_blob"].clone()), (json!(true), json!("avatar-kai")));
    // a private member still signs the post, but their profile and picture stay closed
    let fox = card(BOB, "p-fox");
    assert_eq!(fox["name"], "Foxglove");
    assert_eq!((fox["profile"].clone(), fox["avatar_blob"].clone()), (json!(false), Value::Null));
    // the bucket member only for those in the live bucket
    assert_eq!(card(BOB, "p-nai")["avatar_blob"], "avatar-nai");
    assert_eq!(card(CAROL, "p-nai")["profile"], false);
    // and exactly the pictures the blob read allows
    for (viewer, post) in [(BOB, "p-kai"), (BOB, "p-fox"), (BOB, "p-nai"), (CAROL, "p-nai"), (CAROL, "p-kai")] {
        let c = card(viewer, post);
        let hash = format!("avatar-{}", c["id"].as_str().unwrap());
        assert_eq!(profiles::blob_visible(&conn, viewer, &hash).unwrap(), c["profile"] == true, "{viewer} {post}");
    }
    // the owner's own members open, private or not
    assert_eq!(card(ALICE, "p-fox")["avatar_blob"], "avatar-fox");
}

#[test]
fn relationship_audience_and_target_must_both_allow_each_reader() {
    let conn = seed();
    conn.execute("INSERT INTO relationship_type(id,account_id,name) VALUES ('friend',?1,'Friend')", [ALICE]).unwrap();
    for (id, target, audience) in [
        ("shared", "amy", json!({"mode":"buckets","bucket_ids":[BUCKET]})),
        ("hidden-target", "fox", json!({"mode":"server"})),
        ("private-link", "amy", json!({"mode":"private"})),
    ] {
        conn.execute(
            "INSERT INTO relationship(id,account_id,from_member_id,to_kind,to_id,type_id,note,visibility,created_at)
            VALUES (?1,?2,'kai','member',?3,'friend','secret note',?4,0)",
            params![id, ALICE, target, audience.to_string()],
        )
        .unwrap();
    }
    let bob = profiles::profile(&conn, BOB, ALICE, "kai").unwrap().unwrap();
    assert_eq!(bob["relationships"].as_array().unwrap().len(), 1);
    assert_eq!(bob["relationships"][0]["target"]["name"], "Amarynthe");
    let carol = profiles::profile(&conn, CAROL, ALICE, "kai").unwrap().unwrap();
    assert!(carol["relationships"].as_array().unwrap().is_empty());
    assert!(!bob.to_string().contains("fox"));
    assert!(!profiles::blob_visible(&conn, BOB, "avatar-fox").unwrap());
    let targets = profiles::targets(&conn, BOB).unwrap();
    assert!(!targets.to_string().contains("Foxglove"));
    conn.execute("UPDATE member SET visibility='{\"mode\":\"private\"}' WHERE id='amy'", []).unwrap();
    assert!(
        profiles::profile(&conn, BOB, ALICE, "kai").unwrap().unwrap()["relationships"].as_array().unwrap().is_empty()
    );
    conn.execute("UPDATE member SET visibility='{\"mode\":\"server\"}' WHERE id='amy'", []).unwrap();
    conn.execute("UPDATE bucket SET deleted_at=9 WHERE id=?1", [BUCKET]).unwrap();
    assert!(
        profiles::profile(&conn, BOB, ALICE, "kai").unwrap().unwrap()["relationships"].as_array().unwrap().is_empty()
    );
}

#[test]
fn relationship_writes_link_visible_singlets_but_refuse_hidden_targets_and_foreign_sources() {
    use chorus_core::id::new_id;
    use chorus_server::ingest;
    let conn = seed();
    for account in [ALICE, BOB] {
        ingest::grant(&conn, account, &format!("account:{account}")).unwrap();
    }
    conn.execute("UPDATE account SET kind='person' WHERE id=?1", [BOB]).unwrap();
    conn.execute("INSERT INTO member(id,account_id,name,is_self,visibility,created_at) VALUES ('bob',?1,'Bob',1,'{\"mode\":\"server\"}',0)", [BOB]).unwrap();
    let write = |author: &str, n: u8, from: &str, kind: &str, to: &str| {
        ingest::server_op(
            &conn,
            author,
            "relationship.set",
            &format!("account:{author}"),
            Some(&new_id(1, [n; 10])),
            json!({"from_member_id":from,"to_kind":kind,"to_id":to,"type_id":new_id(1,[99;10]),"visibility":{"mode":"private"}}),
            1_790_000_000_000 + n as i64,
        )
    };
    assert!(write(ALICE, 1, "kai", "member", "bob").is_ok());
    assert!(write(ALICE, 2, "kai", "account", BOB).is_ok());
    assert!(write(BOB, 3, "bob", "member", "nai").is_ok(), "Bob can open this bucket-shared fragment");
    assert!(write(BOB, 4, "bob", "member", "fox").is_err());
    assert!(write(BOB, 5, "bob", "member", "missing").is_err());
    assert!(write(BOB, 6, "kai", "member", "bob").is_err(), "the source must be one's own");
}
