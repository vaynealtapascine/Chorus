//! A device's replica: the sync engine, the in-memory store and the HLC clock in one place.
//!
//! Apps keep one `Replica` in memory and persist it write-behind: after each call, take the
//! changes ([`Replica::take_changes`]) and write them to IndexedDB (web) or Room (Android). On
//! start, [`Replica::restore`] rebuilds it from what was persisted. All logic stays here, where
//! the convergence simulator exercises it.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::hlc::{Hlc, HlcClock};
use crate::model::Projection;
use crate::op::{self, Op, OpError};
use crate::projector::{Delta, Projector};
use crate::sync::{ClientEngine, ClientState, ClientStore, ClockReading, Frame, MemStore};
use crate::time::TimeSource;

/// An op the server refused (SYNC §7 "Sync issues").
#[derive(Clone, Debug, PartialEq, serde::Serialize)]
pub struct SyncIssue {
    pub id: String,
    pub kind: String,
    pub scope: String,
    pub entity_id: Option<String>,
    pub payload: serde_json::Value,
    /// When it was written (device time).
    pub at: i64,
    pub code: String,
    pub message: String,
}

pub struct Replica {
    pub engine: ClientEngine,
    pub store: MemStore,
    pub clock: HlcClock,
    /// Incremental projection of `store.visible()` (projector.rs), refreshed on read.
    projector: Projector,
    /// Opening from a snapshot: the ops still to index, and how far along (see [`Replica::begin`]).
    indexing: Option<(Vec<String>, usize)>,
}

/// What to persist after a call.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Changes {
    /// Ops to upsert (by id).
    pub ops: Vec<Op>,
    /// Op ids to delete: confirmed ops the account may no longer see (a repair's sweep, a scope
    /// taken away). The server still has them; this device just stops holding them.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub removed: Vec<String>,
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
        Replica {
            engine: ClientEngine::new(device_id),
            store: MemStore::default(),
            clock: HlcClock::new(node),
            projector: Projector::new(),
            indexing: None,
        }
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
        Replica { engine: ClientEngine::new(device_id), store, clock, projector: Projector::new(), indexing: None }
    }

    /// Open from a snapshot (CLIENTS.md §4.3): start with the persisted metadata only, feed the
    /// ops in slices ([`Replica::add_ops`]), index them a slice at a time
    /// ([`Replica::index_step`]), then [`Replica::adopt`] the snapshot the UI is already showing.
    /// Local ops can be created meanwhile; don't read the projection or sync until adopted.
    pub fn begin(device_id: &str, node: u32, meta: Option<MemStore>, hlc_last: Option<Hlc>) -> Replica {
        Replica::restore(device_id, node, meta, Vec::new(), hlc_last)
    }

    /// Persisted ops (a slice of them). A copy this session already holds wins.
    pub fn add_ops(&mut self, ops: Vec<Op>) {
        for o in ops {
            self.store.ops.entry(o.id.clone()).or_insert(o);
        }
    }

    /// Index up to `n` more ops; true when all are.
    pub fn index_step(&mut self, n: usize) -> bool {
        let (ids, at) = self.indexing.get_or_insert_with(|| {
            let ids = self.store.visible().map(|o| o.id.clone()).collect();
            (ids, 0)
        });
        let end = at.saturating_add(n).min(ids.len());
        for id in &ids[*at..end] {
            if let Some(o) = self.store.ops.get(id).filter(|o| !self.store.rejected.contains_key(&o.id)) {
                self.projector.index_op(o);
            }
        }
        *at = end;
        end == ids.len()
    }

    /// Continue from the snapshot the UI shows (made at `snapshot`, a
    /// [`Replica::projection_digest`]) if the ops match it; true if so. Otherwise the whole
    /// projection is computed and the next delta says `full`.
    pub fn adopt(&mut self, snapshot: Option<crate::sync::Digest>) -> bool {
        while !self.index_step(usize::MAX) {}
        self.indexing = None;
        // ops created or changed while opening: the snapshot can't include them
        let fresh = self.store.take_touched();
        let ok = self.projector.adopt(snapshot, &fresh);
        let store = &self.store;
        self.projector.sync_ids(fresh, |id| store.ops.get(id).filter(|o| !store.rejected.contains_key(&o.id)));
        ok
    }

    /// Identifies the op copies the current projection reflects: save it with a snapshot.
    pub fn projection_digest(&mut self) -> crate::sync::Digest {
        self.refresh();
        self.projector.digest()
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
        op::validate_new(&o)?;
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

    /// Frames asking for every scope again (see [`ClientEngine::recheck`]).
    pub fn recheck(&self) -> Vec<Frame> {
        self.engine.recheck(&self.store)
    }

    pub fn disconnect(&mut self) {
        self.engine.on_disconnect();
    }

    /// Ops the server refused, with why (SYNC §7 "Sync issues": shown with the reason, the text of
    /// a refused message can be copied), oldest first.
    pub fn sync_issues(&self) -> Vec<SyncIssue> {
        let mut v: Vec<SyncIssue> = self
            .store
            .rejected
            .iter()
            .filter_map(|(id, e)| {
                self.store.ops.get(id).map(|o| SyncIssue {
                    id: id.clone(),
                    kind: o.kind.clone(),
                    scope: o.scope.clone(),
                    entity_id: o.entity_id.clone(),
                    payload: o.payload.clone(),
                    at: o.device_at,
                    code: e.code.clone(),
                    message: e.message.clone(),
                })
            })
            .collect();
        v.sort_by_key(|i| (i.at, i.id.clone()));
        v
    }

    /// Let go of a refused op once the person has seen it (it's deleted from storage too).
    pub fn dismiss_issue(&mut self, id: &str) -> bool {
        if self.store.rejected.remove(id).is_none() {
            return false;
        }
        self.store.ops.remove(id);
        self.store.local_order.retain(|x| x != id);
        self.store.dirty.remove(id);
        self.store.touched.insert(id.into());
        self.store.removed.insert(id.into());
        self.store.meta_dirty = true;
        true
    }

    /// Every version of an edited message or post, oldest first ([`crate::revisions`]), from the
    /// ops this device has (so it works offline).
    pub fn revisions(&self, entity: &str) -> Vec<crate::revisions::Revision> {
        crate::revisions::revisions(self.store.visible().filter(|o| o.entity() == Some(entity)))
    }

    /// After a reconcile: blobs named by ops the restored server doesn't have yet, being
    /// restored or still waiting to be sent. The device re-uploads the ones it has (SYNC.md
    /// §7.3): the server's files are as old as its backup, and a file uploaded to it since (for
    /// an op sent since, or one still queued) is gone.
    pub fn restoring_blobs(&self) -> Vec<String> {
        let none = std::collections::BTreeSet::new();
        let mut v: Vec<String> = [true, false]
            .into_iter()
            .flat_map(|restore| self.store.pending(&none, usize::MAX, restore))
            .flat_map(|o| crate::restore::blob_hashes(&o))
            .collect();
        v.sort();
        v.dedup();
        v
    }

    pub fn take_changes(&mut self) -> Changes {
        self.store.compact();
        let (ops, meta) = self.store.take_dirty();
        let removed = std::mem::take(&mut self.store.removed).into_iter().collect();
        Changes { ops, removed, meta: meta.then(|| self.store.clone_meta()), hlc_last: self.clock.last }
    }

    /// What the UI shows: confirmed + pending ops, minus rejected ones. Equal to
    /// `model::project(self.store.visible())`, kept up to date incrementally.
    pub fn projection(&mut self) -> &Projection {
        self.refresh();
        self.projector.materialize();
        self.projector.projection()
    }

    /// Bring the projector up to date with only the ops the store says changed.
    fn refresh(&mut self) {
        let touched = self.store.take_touched();
        if self.projector.is_empty() {
            self.projector.sync(self.store.visible());
            return;
        }
        let store = &self.store;
        self.projector.sync_ids(touched, |id| store.ops.get(id).filter(|o| !store.rejected.contains_key(&o.id)));
    }

    /// What changed in [`Replica::projection`] since the last call (`full` the first time).
    pub fn projection_delta(&mut self) -> Delta {
        self.refresh();
        self.projector.take_delta()
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
            touched: Default::default(),
            removed: Default::default(),
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

    /// CLIENTS.md §4.3: open from a snapshot — metadata, ops in slices, a local op created while
    /// opening — and go on from the snapshot without projecting; a stale one projects it all.
    #[test]
    fn opens_from_a_snapshot() {
        let scope = format!("account:{}", crate::id::new_id(1, [1; 10]));
        let member = |name: &str, n: u8| NewOp {
            kind: "member.create".into(),
            scope: scope.clone(),
            entity_id: Some(crate::id::new_id(1, [n; 10])),
            payload: json!({"name": name}),
            member_id: None,
            user_time: None,
        };
        let mut r = Replica::new("dev", 7);
        for n in 0..5u8 {
            r.create(member(&format!("M{n}"), n + 10), &now(1000 + i64::from(n)), [n; 10]).unwrap();
        }
        let snapshot = r.projection().canonical();
        let digest = r.projection_digest();
        let ch = r.take_changes();
        for stale in [false, true] {
            let mut o = Replica::begin("dev", 7, ch.meta.clone(), Some(ch.hlc_last));
            o.add_ops(ch.ops[..2].to_vec());
            o.add_ops(ch.ops[2..].to_vec());
            assert!(!o.index_step(2));
            o.create(member("June", 40), &now(2000), [9; 10]).unwrap();
            let snap = if stale { crate::sync::Digest::of(["something else"]) } else { digest };
            assert_eq!(o.adopt(Some(snap)), !stale);
            let d = o.projection_delta();
            assert_eq!(d.full, stale);
            if !stale {
                assert_eq!(d.rows["member"].len(), 1, "only the op created while opening");
            }
            let whole = crate::model::project(o.store.visible()).canonical();
            assert_ne!(whole, snapshot);
            assert_eq!(o.projection().canonical(), whole);
        }
    }

    /// SYNC §7: a refused op is listed with its reason until dismissed, then gone for good.
    #[test]
    fn refused_ops_are_listed_until_dismissed() {
        let scope = format!("account:{}", crate::id::new_id(1, [1; 10]));
        let mut r = Replica::new("dev", 7);
        let new = NewOp {
            kind: "member.create".into(),
            scope,
            entity_id: Some(crate::id::new_id(1, [2; 10])),
            payload: json!({"name": "Kai"}),
            member_id: None,
            user_time: None,
        };
        let (o, _) = r.create(new, &now(1000), [3; 10]).unwrap();
        r.take_changes();
        assert!(r.sync_issues().is_empty());
        r.store.reject(&o.id, crate::sync::AckError { code: "forbidden".into(), message: "no".into(), retry: false });
        let issues = r.sync_issues();
        assert_eq!(
            (issues.len(), issues[0].code.as_str(), issues[0].payload["name"].as_str()),
            (1, "forbidden", Some("Kai"))
        );
        assert!(r.dismiss_issue(&o.id));
        assert!(!r.dismiss_issue(&o.id));
        assert!(r.sync_issues().is_empty() && !r.store.ops.contains_key(&o.id));
        let ch = r.take_changes();
        assert!(ch.removed.contains(&o.id), "storage deletes it too");
    }

    /// SYNC.md §7.3: after a reconcile, the files named by the restoring ops are listed so the
    /// device can upload them again.
    #[test]
    fn restoring_ops_list_their_blobs() {
        let space = format!("space:{}", crate::id::new_id(1, [1; 10]));
        let hash = "ab".repeat(32);
        let thumb = "cd".repeat(32);
        let mut r = Replica::new("dev", 7);
        let payloads = [
            json!({"blob_hash": hash, "thumb_blob_hash": thumb, "filename": "a.png", "mime": "image/png", "size": 3}),
            json!({"blob_hash": hash, "filename": "again.png", "mime": "image/png", "size": 3}),
            json!({"blob_hash": "not a hash", "filename": "b", "mime": "text/plain", "size": 1}),
        ];
        for (n, payload) in payloads.into_iter().enumerate() {
            let n = n as u8;
            let mut o = r
                .create(
                    NewOp {
                        kind: "attachment.create".into(),
                        scope: space.clone(),
                        entity_id: Some(crate::id::new_id(1, [n + 20; 10])),
                        payload,
                        member_id: None,
                        user_time: None,
                    },
                    &now(1000),
                    [n; 10],
                )
                .unwrap()
                .0;
            o.seq = Some(i64::from(n) + 1);
            r.store.put_remote(o);
        }
        assert!(r.restoring_blobs().is_empty(), "nothing is being restored");
        r.store.demote_for_restore(&space);
        assert_eq!(r.restoring_blobs(), vec![hash.clone(), thumb.clone()]);
        // an op still queued names its file too
        let queued = "ef".repeat(32);
        let payload = json!({"blob_hash": queued, "filename": "q.png", "mime": "image/png", "size": 3});
        let new = NewOp {
            kind: "attachment.create".into(),
            scope: space.clone(),
            entity_id: Some(crate::id::new_id(1, [30; 10])),
            payload,
            member_id: None,
            user_time: None,
        };
        r.create(new, &now(2000), [30; 10]).unwrap();
        assert_eq!(r.restoring_blobs(), vec![hash, thumb, queued]);
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
