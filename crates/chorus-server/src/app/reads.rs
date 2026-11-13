//! Live read receipts (D-131, NOTIFICATIONS.md §5.9): WebSocket only, like typing. No op, no
//! persistence, no SSE, no webhook and no export carries them.
//!
//! After a `read.mark`/`read.set` of an account's own row is accepted, each connected device of
//! another account that may see the receipt (`receipts::upto_for`: both opted in, channel viewable
//! by both now, the mark made after both switch-ons) gets `{"t":"read","channel","account",
//! "message_id","at"}`, and only when what it may learn changed: a mark that moves over the reader's
//! own messages, or over nobody's, sends nothing. A pref flip sends nothing either; the next fetch
//! or relay checks the prefs again.
use std::collections::HashMap;

use chorus_core::op::Op;
use rusqlite::Connection;
use serde_json::json;

use super::Peer;

/// What each viewer was last told about each reader in each channel, so a frame goes out only
/// when it changed. Memory only; cleared when it grows large (a repeat frame is harmless).
#[derive(Default)]
pub(super) struct Sent(HashMap<(String, String, String), String>);

const SENT_MAX: usize = 20_000;

pub(super) fn relay(conn: &Connection, peers: &HashMap<String, Peer>, sent: &mut Sent, fresh: &[Op]) {
    for o in fresh {
        if !matches!(o.kind.as_str(), "read.mark" | "read.set") {
            continue;
        }
        // only the account's own row crosses: a member row would say who was fronting
        let member = o.payload.get("reader_member_id").and_then(|v| v.as_str()).unwrap_or("");
        let (Some(reader), Some(channel)) =
            (o.account_id.as_deref(), o.payload.get("channel_id").and_then(|v| v.as_str()))
        else {
            continue;
        };
        if !member.is_empty() {
            continue;
        }
        let Some(space) = crate::receipts::eligible_space(conn, reader, channel) else { continue };
        let scope = format!("space:{space}");
        let mut viewers: Vec<&str> = peers
            .values()
            .filter(|p| p.account != reader && p.scopes.contains(&scope))
            .map(|p| p.account.as_str())
            .collect();
        viewers.sort_unstable();
        viewers.dedup();
        for viewer in viewers {
            let Some((message_id, at)) = crate::receipts::upto_for(conn, viewer, reader, channel) else { continue };
            if sent.0.len() >= SENT_MAX {
                sent.0.clear();
            }
            let key = (reader.to_owned(), viewer.to_owned(), channel.to_owned());
            if sent.0.get(&key) == Some(&message_id) {
                continue;
            }
            sent.0.insert(key, message_id.clone());
            for (device, p) in peers {
                if p.account == viewer && p.scopes.contains(&scope) && crate::auth::device_active(conn, device) {
                    let _ = p.tx.send(super::Outgoing::Read(
                        json!({"t":"read", "channel":channel, "account":reader, "message_id":message_id, "at":at}),
                    ));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::params;
    use tokio::sync::mpsc;

    const EARLY: &str = "000000000001-0000-00000001";
    const LATE: &str = "000000000009-0000-00000001";

    struct Fixture {
        conn: Connection,
        peers: HashMap<String, Peer>,
        rx: HashMap<String, mpsc::UnboundedReceiver<super::super::Outgoing>>,
        sent: Sent,
    }

    impl Fixture {
        fn new() -> Self {
            let mut conn = crate::db::open_memory().unwrap();
            crate::db::migrate(&mut conn).unwrap();
            conn.execute("INSERT INTO space(id,kind,owner_account_id,created_at) VALUES ('sp','shared','a',0)", [])
                .unwrap();
            conn.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('ch','sp','text',0)", []).unwrap();
            for account in ["a", "b", "c"] {
                conn.execute("INSERT INTO account(id,kind,created_at) VALUES (?1,'system',0)", [account]).unwrap();
                crate::ingest::grant(&conn, account, "space:sp").unwrap();
                conn.execute(
                    "INSERT INTO space_member(space_id,account_id,joined_hlc) VALUES ('sp',?1,'a')",
                    [account],
                )
                .unwrap();
            }
            // a wrote m1 and m3, b wrote m2 and m4
            for (id, account, at) in [("m1", "a", 10), ("m2", "b", 20), ("m3", "a", 30), ("m4", "b", 40)] {
                conn.execute(
                    "INSERT INTO message(id,channel_id,account_id,occurred_at) VALUES (?1,'ch',?2,?3)",
                    params![id, account, at],
                )
                .unwrap();
            }
            let mut peers = HashMap::new();
            let mut rx = HashMap::new();
            for (device, account) in [("a1", "a"), ("a2", "a"), ("b1", "b"), ("c1", "c")] {
                conn.execute(
                    "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at) VALUES (?1,?2,?1,?1,'cli','key',0)",
                    params![device, account],
                )
                .unwrap();
                let (tx, receiver) = mpsc::unbounded_channel();
                peers.insert(
                    device.into(),
                    Peer {
                        account: account.into(),
                        scopes: ["space:sp".into()].into(),
                        window: None,
                        typing: super::super::typing::DeviceTyping::default(),
                        tx,
                    },
                );
                rx.insert(device.into(), receiver);
            }
            let f = Self { conn, peers, rx, sent: Sent::default() };
            f.share("a", true, EARLY);
            f.share("b", true, EARLY);
            f
        }
        fn share(&self, account: &str, on: bool, hlc: &str) {
            self.conn
                .execute(
                    "INSERT OR REPLACE INTO pref(account_id,device_id,key,value,hlc) VALUES (?1,'','share_reads',?2,?3)",
                    params![account, on.to_string(), hlc],
                )
                .unwrap();
        }
        /// `account` has read up to the message (the projection's row, as `read.mark` leaves it).
        fn mark(&self, account: &str, member: &str, id: &str, at: i64) -> Op {
            self.conn
                .execute(
                    "INSERT OR REPLACE INTO read_state(channel_id,account_id,reader_member_id,last_read_message_id,
                        last_read_message_at,mark_at,mark_id,mark_hlc,state_ok) VALUES ('ch',?1,?2,?3,?4,?4,?3,?5,1)",
                    params![account, member, id, at, LATE],
                )
                .unwrap();
            serde_json::from_value(json!({
                "id": "op", "kind": "read.mark", "v": 1, "scope": "space:sp", "hlc": LATE, "device_at": 0,
                "tz_offset_min": 0, "seen_seq": 0, "account_id": account,
                "payload": {"channel_id": "ch", "message_id": id, "message_at": at, "reader_member_id": member}
            }))
            .unwrap()
        }
        fn relay(&mut self, op: &Op) {
            relay(&self.conn, &self.peers, &mut self.sent, std::slice::from_ref(op));
        }
        fn take(&mut self, device: &str) -> Vec<serde_json::Value> {
            let mut out = Vec::new();
            while let Ok(frame) = self.rx.get_mut(device).unwrap().try_recv() {
                out.push(serde_json::to_value(frame).unwrap());
            }
            out
        }
        fn silent(&mut self) -> bool {
            ["a1", "a2", "b1", "c1"].into_iter().all(|d| self.take(d).is_empty())
        }
    }

    #[test]
    fn the_askers_devices_alone_hear_it_once_and_only_when_it_changed() {
        let mut f = Fixture::new();
        let op = f.mark("b", "", "m2", 20);
        f.relay(&op);
        let frame = json!({"t":"read","channel":"ch","account":"b","message_id":"m1","at":10});
        assert_eq!(f.take("a1"), vec![frame.clone()]);
        assert_eq!(f.take("a2"), vec![frame]);
        assert!(f.take("b1").is_empty(), "the reader hears nothing");
        assert!(f.take("c1").is_empty(), "an account that didn't opt in hears nothing");
        // the same answer again: nothing
        let op = f.mark("b", "", "m2", 20);
        f.relay(&op);
        assert!(f.silent());
        // a mark that moves over the reader's own message only: still the same answer, nothing
        let op = f.mark("b", "", "m2", 25);
        f.relay(&op);
        assert!(f.silent(), "a move that names no new message of ours leaks nothing");
        // reaching our next message sends it
        let op = f.mark("b", "", "m4", 40);
        f.relay(&op);
        assert_eq!(f.take("a1")[0]["message_id"], "m3");
    }

    #[test]
    fn nothing_is_sent_without_both_opt_ins_the_account_row_a_viewable_shared_channel_or_a_message_of_ours() {
        let mut f = Fixture::new();
        let member = f.mark("b", "june", "m4", 40);
        f.relay(&member);
        assert!(f.silent(), "a member's row never crosses");
        f.conn.execute("DELETE FROM read_state", []).unwrap();
        let only_theirs = f.mark("b", "", "m2", 5);
        f.relay(&only_theirs);
        assert!(f.silent(), "a mark before any message of ours");
        let op = f.mark("b", "", "m4", 40);
        f.share("b", false, LATE);
        f.relay(&op);
        assert!(f.silent(), "the reader's switch is off");
        f.share("b", true, EARLY);
        f.share("a", false, LATE);
        f.relay(&op);
        assert!(f.silent(), "the asker's switch is off");
        f.share("a", true, "00000000000a-0000-00000001");
        f.relay(&op);
        assert!(f.silent(), "reading from before the asker switched it on");
        f.share("a", true, EARLY);
        f.conn.execute("UPDATE space SET kind='internal'", []).unwrap();
        f.relay(&op);
        assert!(f.silent(), "an internal space");
        f.conn.execute("UPDATE space SET kind='shared'", []).unwrap();
        f.conn
            .execute(
                "INSERT INTO channel_permission(channel_id,target_type,target_id,deny,hlc) VALUES ('ch','account','a','[\"view\"]','a')",
                [],
            )
            .unwrap();
        f.conn.execute("UPDATE space SET owner_account_id='b'", []).unwrap();
        f.relay(&op);
        assert!(f.silent(), "the asker can no longer view the channel");
        f.conn.execute("DELETE FROM channel_permission", []).unwrap();
        f.peers.get_mut("a1").unwrap().scopes.clear();
        f.peers.get_mut("a2").unwrap().scopes.clear();
        f.relay(&op);
        assert!(f.silent(), "devices whose scope was lost");
        f.peers.get_mut("a1").unwrap().scopes.insert("space:sp".into());
        f.relay(&op);
        assert_eq!(f.take("a1").len(), 1, "and then it flows");
    }
}
