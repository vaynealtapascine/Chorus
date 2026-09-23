//! A device's replica: the sync engine, the in-memory store and the HLC clock in one place.
//!
//! Apps keep one `Replica` in memory and persist it write-behind: after each call, take the
//! changes ([`Replica::take_changes`]) and write them to IndexedDB (web) or Room (Android). On
//! start, [`Replica::restore`] rebuilds it from what was persisted. All logic stays here, where
//! the convergence simulator exercises it.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::hlc::{Hlc, HlcClock};
use crate::model::{self, Projection};
use crate::op::{self, Op, OpError};
use crate::sync::{ClientEngine, ClientState, ClientStore, ClockReading, Frame, MemStore};
use crate::time::TimeSource;

pub struct Replica {
    pub engine: ClientEngine,
    pub store: MemStore,
    pub clock: HlcClock,
}

/// What to persist after a call.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Changes {
    /// Ops to upsert (by id).
    pub ops: Vec<Op>,
    /// The store's metadata (cursors, epoch, scopes, queues, rejected), when it changed.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub meta: Option<MemStore>,
    /// Last HLC, to persist so clocks never go backwards across restarts.
    pub hlc_last: Hlc,
}

/// Parameters for a new local op.
#[derive(Clone, Debug, Deserialize)]
pub struct NewOp {
    pub kind: String,
    pub scope: String,
    #[serde(default)]
    pub entity_id: Option<String>,
    #[serde(default)]
    pub payload: Value,
    #[serde(default)]
    pub member_id: Option<String>,
    /// A time the user typed ("switched at 13:40"); otherwise `now`.
    #[serde(default)]
    pub user_time: Option<i64>,
}

/// Device facts for a new op.
#[derive(Clone, Debug, Deserialize)]
pub struct DeviceNow {
    pub now: i64,
    #[serde(default)]
    pub tz_offset_min: i32,
    #[serde(default)]
    pub mono: Option<i64>,
    #[serde(default)]
    pub boot_id: Option<String>,
}

impl Replica {
    pub fn new(device_id: &str, node: u32) -> Replica {
        Replica { engine: ClientEngine::new(device_id), store: MemStore::default(), clock: HlcClock::new(node) }
    }

    /// Rebuild from persisted metadata and ops.
    pub fn restore(device_id: &str, node: u32, meta: Option<MemStore>, ops: Vec<Op>, hlc_last: Option<Hlc>) -> Replica {
        let mut store = meta.unwrap_or_default();
        store.ops = ops.into_iter().map(|o| (o.id.clone(), o)).collect();
        store.dirty.clear();
        store.meta_dirty = false;
        let clock = match hlc_last {
            Some(h) => HlcClock::resume(node, h),
            None => HlcClock::new(node),
        };
        Replica { engine: ClientEngine::new(device_id), store, clock }
    }

    pub fn state(&self) -> ClientState {
        self.engine.state
    }

    /// Locally accepted ops still awaiting a server stamp. Rejected ops need user attention,
    /// so they do not keep a bounded background sync worker alive indefinitely.
    pub fn pending_count(&self) -> usize {
        self.store.ops.values().filter(|o| o.seq.is_none() && !self.store.rejected.contains_key(&o.id)).count()
    }

    /// Create a local op (validated here, so the UI can report errors immediately), and the
    /// frames to send if connected.
    pub fn create(&mut self, n: NewOp, d: &DeviceNow, random: [u8; 10]) -> Result<(Op, Vec<Frame>), OpError> {
        let id = crate::id::new_id(d.now.max(0) as u64, random);
        let o = self.local_op(id, n, d)?;
        let frames = self.engine.pump(&self.store);
        Ok((o, frames))
    }

    /// Apply a PluralKit import plan (ids are fixed, so ops this device already has are
    /// skipped; the server does the same for ops other devices already sent).
    pub fn import(&mut self, plan: crate::import::Plan, d: &DeviceNow) -> Result<(usize, Vec<Frame>), OpError> {
        let mut added = 0;
        for p in plan.ops {
            if self.store.ops.contains_key(&p.id) {
                continue;
            }
            self.local_op(p.id, p.op, d)?;
            added += 1;
        }
        Ok((added, self.engine.pump(&self.store)))
    }

    fn local_op(&mut self, id: String, n: NewOp, d: &DeviceNow) -> Result<Op, OpError> {
        let hlc = self.clock.tick(d.now.max(0) as u64);
        let seen = self.store.cursor(&n.scope);
        let o = Op {
            id,
            kind: n.kind,
            v: op::CURRENT_V,
            scope: n.scope,
            entity_id: n.entity_id,
            hlc,
            device_at: n.user_time.unwrap_or(d.now),
            tz_offset_min: d.tz_offset_min,
            mono: d.mono,
            boot_id: d.boot_id.clone(),
            time_source: if n.user_time.is_some() { TimeSource::User } else { TimeSource::Auto },
            seen_seq: seen,
            member_id: n.member_id,
            payload: if n.payload.is_null() { Value::Object(Default::default()) } else { n.payload },
            seq: None,
            account_id: None,
            device_id: None,
            occurred_at: None,
            received_at: None,
        };
        op::validate(&o)?;
        self.store.add_local(o.clone());
        Ok(o)
    }

    pub fn connect(&mut self, clock: ClockReading, token: &str) -> Frame {
        self.engine.on_connect(&self.store, clock, token)
    }

    pub fn on_frame(&mut self, frame: Frame, now: i64) -> Vec<Frame> {
        if let Frame::Ops { ops, .. } = &frame {
            for o in ops {
                self.clock.observe(o.hlc, now.max(0) as u64);
            }
        }
        self.engine.on_frame(&mut self.store, frame)
    }

    pub fn disconnect(&mut self) {
        self.engine.on_disconnect();
    }

    pub fn take_changes(&mut self) -> Changes {
        self.store.compact();
        let (ops, meta) = self.store.take_dirty();
        Changes { ops, meta: meta.then(|| self.store.clone_meta()), hlc_last: self.clock.last }
    }

    /// What the UI shows: confirmed + pending ops, minus rejected ones.
    pub fn projection(&self) -> Projection {
        model::project(self.store.visible())
    }
}

impl MemStore {
    /// The store without its ops (what `Changes::meta` carries).
    pub fn clone_meta(&self) -> MemStore {
        MemStore {
            epoch: self.epoch.clone(),
            cursors: self.cursors.clone(),
            scopes: self.scopes.clone(),
            ops: Default::default(),
            local_order: self.local_order.clone(),
            rejected: self.rejected.clone(),
            restoring: self.restoring.clone(),
            dirty: Default::default(),
            meta_dirty: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sync::MemServer;
    use serde_json::json;

    fn now(t: i64) -> DeviceNow {
        DeviceNow { now: t, tz_offset_min: 60, mono: None, boot_id: None }
    }

    #[test]
    fn create_validates_and_persists_round_trip() {
        let acct = crate::id::new_id(1, [1; 10]);
        let scope = format!("account:{acct}");
        let mut r = Replica::new("dev", 7);
        let member = crate::id::new_id(1, [2; 10]);
        let bad = NewOp {
            kind: "member.set".into(),
            scope: scope.clone(),
            entity_id: Some(member.clone()),
            payload: json!({"account_id": "x"}),
            member_id: None,
            user_time: None,
        };
        assert!(r.create(bad, &now(1000), [3; 10]).is_err());
        let good = NewOp {
            kind: "member.create".into(),
            scope: scope.clone(),
            entity_id: Some(member.clone()),
            payload: json!({"name": "Kai"}),
            member_id: None,
            user_time: None,
        };
        let (o, frames) = r.create(good, &now(1000), [4; 10]).unwrap();
        assert!(frames.is_empty(), "not connected yet");
        assert_eq!(r.pending_count(), 1);
        assert_eq!(r.projection().row("member", &member).unwrap().fields["name"], "Kai");
        let ch = r.take_changes();
        assert_eq!(ch.ops.len(), 1);
        assert!(ch.meta.is_some());
        assert!(r.take_changes().ops.is_empty(), "flags cleared");
        // restore from what was persisted
        let r2 = Replica::restore("dev", 7, ch.meta, ch.ops, Some(ch.hlc_last));
        assert_eq!(r2.store.ops[&o.id], o);
        assert_eq!(r2.store.local_order, vec![o.id.clone()]);
    }

    #[test]
    fn syncs_against_the_reference_server() {
        let acct = crate::id::new_id(1, [9; 10]);
        let scope = format!("account:{acct}");
        let mut server = MemServer::new("t");
        server.grant(&acct, &scope);
        server.devices.insert("dev".into(), acct.clone());
        let mut r = Replica::new("dev", 1);
        let hello = r.connect(ClockReading { wall: 5000, mono: None, boot_id: None }, "tok");
        let mut inbox: Vec<Frame> = server.on_frame("dev", hello, 5000).into_iter().map(|(_, f)| f).collect();
        let (_, mut outbox) = r
            .create(
                NewOp {
                    kind: "member.create".into(),
                    scope: scope.clone(),
                    entity_id: Some(crate::id::new_id(1, [8; 10])),
                    payload: json!({"name": "June"}),
                    member_id: None,
                    user_time: None,
                },
                &now(5000),
                [5; 10],
            )
            .unwrap();
        for _ in 0..10 {
            for f in std::mem::take(&mut inbox) {
                outbox.extend(r.on_frame(f, 5000));
            }
            for f in std::mem::take(&mut outbox) {
                inbox.extend(server.on_frame("dev", f, 5000).into_iter().map(|(_, f)| f));
            }
        }
        assert_eq!(r.store.confirmed().count(), 1);
        assert_eq!(r.pending_count(), 0);
        let ch = r.take_changes();
        assert!(ch.ops[0].seq.is_some(), "persisted copy carries the server stamp");
        assert!(r.store.local_order.is_empty(), "compacted");
    }
}
