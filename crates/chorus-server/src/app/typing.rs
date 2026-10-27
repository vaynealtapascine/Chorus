//! WebSocket-only presence. No op, persistence, event stream or webhook path.
use std::collections::HashMap;

use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;
use serde_json::json;

use super::Peer;

#[derive(Clone, Copy, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub(super) enum Audience {
    All,
    System,
}

#[derive(Clone, Copy, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub(super) enum State {
    Start,
    Stop,
}

#[derive(Deserialize)]
pub(super) struct Input {
    pub channel: String,
    pub member: Option<String>,
    pub audience: Audience,
    pub state: State,
}

#[derive(Clone)]
struct Active {
    member: Option<String>,
    audience: Audience,
    until: i64,
}

#[derive(Default)]
pub(super) struct DeviceTyping {
    active: HashMap<String, Active>,
}

/// A short-lived per-device limit survives disconnect/replacement of that device's socket.
#[derive(Default)]
pub(super) struct Starts(HashMap<(String, String), i64>);

fn channel(conn: &Connection, account: &str, channel: &str) -> Option<(String, String)> {
    if !crate::perms::can(conn, account, channel, "view").unwrap_or(false) {
        return None;
    }
    let row: (String, String) = conn.query_row(
        "SELECT s.id, s.kind FROM channel c JOIN space s ON s.id=c.space_id WHERE c.id=?1 AND c.deleted_at IS NULL AND s.deleted_at IS NULL",
        [channel], |r| Ok((r.get(0)?, r.get(1)?)),
    ).optional().ok()??;
    crate::ingest::can_access(conn, account, &format!("space:{}", row.0)).unwrap_or(false).then_some(row)
}

fn shared(conn: &Connection, account: &str) -> bool {
    conn.query_row(
        "SELECT value FROM pref WHERE account_id=?1 AND device_id='' AND key='share_typing'",
        [account],
        |r| r.get::<_, String>(0),
    )
    .ok()
    .and_then(|v| serde_json::from_str::<serde_json::Value>(&v).ok())
        == Some(json!(true))
}

pub(super) fn receive(
    conn: &Connection,
    peers: &mut HashMap<String, Peer>,
    device: &str,
    f: Input,
    now: i64,
    starts: &mut Starts,
) {
    let Some(peer) = peers.get(device) else { return };
    let account = peer.account.clone();
    if channel(conn, &account, &f.channel).is_none() {
        return;
    }
    if let Some(member) = &f.member {
        let valid = conn
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM member WHERE id=?1 AND account_id=?2 AND deleted_at IS NULL)",
                params![member, account],
                |r| r.get::<_, bool>(0),
            )
            .unwrap_or(false);
        if !valid {
            return;
        }
    }
    if f.state == State::Start {
        starts.0.retain(|_, last| now - *last < 2000);
        let key = (device.to_owned(), f.channel.clone());
        if starts.0.contains_key(&key) {
            return;
        }
        starts.0.insert(key, now);
    }
    let old = peers.get_mut(device).expect("sender").typing.active.remove(&f.channel);
    if let Some(old) = old {
        // Clear the old identity/audience before a speaker or privacy change.
        if f.state == State::Stop || old.member != f.member || old.audience != f.audience {
            relay(conn, peers, device, &f.channel, &old, false, now);
        }
    }
    if f.state == State::Start {
        let active = Active { member: f.member, audience: f.audience, until: now + 6000 };
        peers.get_mut(device).expect("sender").typing.active.insert(f.channel.clone(), active.clone());
        relay(conn, peers, device, &f.channel, &active, true, now);
    }
}

fn relay(
    conn: &Connection,
    peers: &HashMap<String, Peer>,
    device: &str,
    ch: &str,
    active: &Active,
    start: bool,
    now: i64,
) {
    let Some(account) = peers.get(device).map(|p| p.account.as_str()) else { return };
    let Some((space, kind)) = channel(conn, account, ch) else { return };
    let external = active.audience == Audience::All && kind != "internal" && shared(conn, account);
    for (id, p) in peers {
        if id == device
            || !p.scopes.contains(&format!("space:{space}"))
            || (p.account != account && !external)
            || !crate::auth::device_active(conn, id)
            || channel(conn, &p.account, ch).is_none()
        {
            continue;
        }
        // The wire has no device identifier: retain another device's same identity on stop.
        let other = peers
            .iter()
            .filter(|(d, q)| d.as_str() != device && *d != id && q.account == account)
            .filter_map(|(_, q)| q.typing.active.get(ch))
            .filter(|a| {
                a.member == active.member && a.until > now && (p.account == account || a.audience == Audience::All)
            })
            .map(|a| a.until)
            .max();
        let until = if start { Some(active.until.max(other.unwrap_or(0))) } else { other };
        let _ = p.tx.send(super::Outgoing::Typing(json!({"t":"typing", "channel":ch, "account":account,
            "member":active.member, "state":if until.is_some() { "start" } else { "stop" },
            "until":until.unwrap_or(now)})));
    }
}

pub(super) fn disconnect(conn: &Connection, peers: &mut HashMap<String, Peer>, device: &str, now: i64) {
    let Some(p) = peers.get_mut(device) else { return };
    let active = std::mem::take(&mut p.typing.active);
    for (ch, a) in active {
        relay(conn, peers, device, &ch, &a, false, now);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc;

    struct Fixture {
        conn: Connection,
        peers: HashMap<String, Peer>,
        rx: HashMap<String, mpsc::UnboundedReceiver<super::super::Outgoing>>,
        starts: Starts,
    }
    impl Fixture {
        fn new(kind: &str) -> Self {
            let mut conn = crate::db::open_memory().unwrap();
            crate::db::migrate(&mut conn).unwrap();
            for account in ["own", "other", "denied"] {
                conn.execute("INSERT INTO account(id,kind,created_at) VALUES (?1,'system',0)", [account]).unwrap();
                crate::ingest::grant(&conn, account, "space:space").unwrap();
                conn.execute(
                    "INSERT INTO space_member(space_id,account_id,joined_hlc) VALUES ('space',?1,'a')",
                    [account],
                )
                .unwrap();
            }
            conn.execute("INSERT INTO space(id,kind,owner_account_id,created_at) VALUES ('space',?1,'own',0)", [kind])
                .unwrap();
            conn.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('ch','space','text',0)", [])
                .unwrap();
            conn.execute("INSERT INTO channel_permission(channel_id,target_type,target_id,deny,hlc) VALUES ('ch','account','denied','[\"view\"]','a')", []).unwrap();
            conn.execute("INSERT INTO member(id,account_id,created_at) VALUES ('kai','own',0),('foreign','other',0),('gone','own',0)", []).unwrap();
            conn.execute("UPDATE member SET deleted_at=1 WHERE id='gone'", []).unwrap();
            let mut peers = HashMap::new();
            let mut rx = HashMap::new();
            for (device, account) in
                [("sender", "own"), ("desk", "own"), ("tablet", "own"), ("friend", "other"), ("blocked", "denied")]
            {
                conn.execute("INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at) VALUES (?1,?2,?1,?1,'cli','key',0)", params![device,account]).unwrap();
                let (tx, receiver) = mpsc::unbounded_channel();
                peers.insert(
                    device.into(),
                    Peer {
                        account: account.into(),
                        scopes: ["space:space".into()].into(),
                        window: None,
                        typing: DeviceTyping::default(),
                        tx,
                    },
                );
                rx.insert(device.into(), receiver);
            }
            Self { conn, peers, rx, starts: Starts::default() }
        }
        fn send(&mut self, member: Option<&str>, audience: Audience, state: State, now: i64) {
            receive(
                &self.conn,
                &mut self.peers,
                "sender",
                Input { channel: "ch".into(), member: member.map(str::to_owned), audience, state },
                now,
                &mut self.starts,
            );
        }
        fn take(&mut self, device: &str) -> Vec<serde_json::Value> {
            let mut out = Vec::new();
            while let Ok(frame) = self.rx.get_mut(device).unwrap().try_recv() {
                out.push(serde_json::to_value(frame).unwrap());
            }
            out
        }
        fn opt_in(&self) {
            self.conn
                .execute(
                    "INSERT INTO pref(account_id,device_id,key,value,hlc) VALUES ('own','','share_typing','true','a')",
                    [],
                )
                .unwrap();
        }
    }

    #[test]
    fn internal_is_own_devices_only_even_with_opt_in_and_no_storage_or_events() {
        let mut f = Fixture::new("internal");
        f.opt_in();
        f.send(Some("kai"), Audience::All, State::Start, 1000);
        assert_eq!(
            f.take("desk"),
            vec![json!({"t":"typing","channel":"ch","account":"own","member":"kai","state":"start","until":7000})]
        );
        for device in ["sender", "friend", "blocked"] {
            assert!(f.take(device).is_empty());
        }
        disconnect(&f.conn, &mut f.peers, "sender", 2000);
        assert_eq!(f.take("desk")[0]["state"], "stop");
        assert!(f.take("friend").is_empty());
        assert_eq!(f.conn.query_row("SELECT count(*) FROM op", [], |r| r.get::<_, i64>(0)).unwrap(), 0);
    }

    #[test]
    fn shared_opt_in_audience_and_current_permissions() {
        let mut f = Fixture::new("shared");
        f.send(None, Audience::All, State::Start, 1000);
        assert!(f.take("friend").is_empty());
        assert_eq!(f.take("desk").len(), 1);
        f.opt_in();
        f.send(None, Audience::All, State::Start, 3000);
        assert_eq!(f.take("friend")[0]["state"], "start");
        assert!(f.take("blocked").is_empty());
        f.send(None, Audience::All, State::Stop, 3001);
        assert_eq!(f.take("friend")[0]["state"], "stop");
        f.send(None, Audience::System, State::Start, 5000);
        assert!(f.take("friend").is_empty());
        f.send(None, Audience::System, State::Stop, 5001);
        assert!(f.take("friend").is_empty());
        // A permission lost on an already-open connection is effective immediately.
        f.conn.execute("INSERT INTO channel_permission(channel_id,target_type,target_id,deny,hlc) VALUES ('ch','account','other','[\"view\"]','b')", []).unwrap();
        f.send(None, Audience::All, State::Start, 7000);
        assert!(f.take("friend").is_empty());
        // A stale scope on an open socket cannot grant access either.
        f.conn.execute("DELETE FROM scope_access WHERE account_id='own'", []).unwrap();
        f.take("desk");
        f.send(None, Audience::All, State::Start, 9000);
        assert!(f.take("desk").is_empty());
    }

    #[test]
    fn invalid_members_and_senders_are_silent_and_do_not_consume_the_limit() {
        let mut f = Fixture::new("shared");
        for member in ["foreign", "gone", "missing"] {
            f.send(Some(member), Audience::All, State::Start, 1000);
        }
        assert!(f.take("desk").is_empty());
        f.send(Some("kai"), Audience::All, State::Start, 1000);
        assert_eq!(f.take("desk").len(), 1);
        f.conn.execute("UPDATE space SET owner_account_id='other'", []).unwrap();
        f.conn.execute("INSERT INTO channel_permission(channel_id,target_type,target_id,deny,hlc) VALUES ('ch','account','own','[\"view\"]','a')", []).unwrap();
        f.send(Some("kai"), Audience::All, State::Start, 3000);
        assert!(f.take("desk").is_empty());
    }

    #[test]
    fn throttle_boundary_stops_do_not_reset_it_and_other_devices_remain_live() {
        let mut f = Fixture::new("shared");
        f.send(Some("kai"), Audience::All, State::Start, 1000);
        assert_eq!(f.take("desk").len(), 1);
        f.send(Some("kai"), Audience::All, State::Start, 2999);
        assert!(f.take("desk").is_empty());
        f.send(Some("kai"), Audience::All, State::Stop, 2999);
        assert_eq!(f.take("desk")[0]["state"], "stop");
        f.send(Some("kai"), Audience::All, State::Start, 2999);
        assert!(f.take("desk").is_empty());
        f.send(Some("kai"), Audience::All, State::Start, 3000);
        assert_eq!(f.take("desk")[0]["until"], 9000);
        receive(
            &f.conn,
            &mut f.peers,
            "desk",
            Input { channel: "ch".into(), member: Some("kai".into()), audience: Audience::All, state: State::Start },
            4000,
            &mut f.starts,
        );
        disconnect(&f.conn, &mut f.peers, "sender", 4500);
        assert_eq!(f.take("desk")[0]["state"], "stop");
        assert_eq!(f.take("tablet").last().unwrap()["until"], 10000);
        disconnect(&f.conn, &mut f.peers, "desk", 4501);
        assert_eq!(f.take("sender").last().unwrap()["state"], "stop");
        // A reconnect cannot reset a device's rate budget.
        f.peers.get_mut("sender").unwrap().typing = DeviceTyping::default();
        f.send(Some("kai"), Audience::All, State::Start, 4999);
        assert!(f.take("desk").is_empty());
        f.send(Some("kai"), Audience::All, State::Start, 5000);
        assert_eq!(f.take("desk")[0]["state"], "start");
    }
}
