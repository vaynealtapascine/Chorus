//! Sync protocol (SYNC.md §4–7), sans-IO.
//!
//! - [`Frame`]: the wire frames.
//! - [`Digest`]: order-independent summary of a replica's ops for a scope.
//! - [`ClientEngine`]: the device side. Pure state machine over a [`ClientStore`]; Android and
//!   web drive it through FFI and give it their database.
//! - [`MemServer`]: an in-memory reference server with the exact semantics the real server must
//!   have. The convergence simulator runs clients against it; the real server is tested against
//!   the same scenarios end to end.

use std::collections::{BTreeMap, BTreeSet, HashMap};

use serde::{Deserialize, Serialize};
use sha2::{Digest as _, Sha256};

use crate::op::{self, Op, OpError, Scope};
use crate::time::{self, ClockSample};

/// Max ops per push batch.
pub const BATCH_OPS: usize = 500;
/// Batches in flight per connection.
pub const MAX_IN_FLIGHT: usize = 4;
/// Catch-up page size.
pub const PAGE_OPS: usize = 1000;

// ─── digest ──────────────────────────────────────────────────────────────────

/// `count` + XOR of truncated sha256(op id) over a set of ops (SYNC.md §6.4).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Digest {
    pub count: u64,
    #[serde(with = "hex16")]
    pub xor: [u8; 16],
}

impl Digest {
    /// Toggle one op id in. Call exactly once per op (adding twice removes it).
    pub fn add(&mut self, op_id: &str) {
        let h = Sha256::digest(op_id.as_bytes());
        for (x, b) in self.xor.iter_mut().zip(h.iter()) {
            *x ^= b;
        }
        self.count += 1;
    }

    /// Toggle one op id back out (the inverse of [`Digest::add`]).
    pub fn remove(&mut self, op_id: &str) {
        let h = Sha256::digest(op_id.as_bytes());
        for (x, b) in self.xor.iter_mut().zip(h.iter()) {
            *x ^= b;
        }
        self.count = self.count.wrapping_sub(1);
    }

    pub fn of<'a>(ids: impl IntoIterator<Item = &'a str>) -> Digest {
        let mut d = Digest::default();
        for id in ids {
            d.add(id);
        }
        d
    }
}

mod hex16 {
    use serde::{Deserialize, Deserializer, Serializer};
    pub fn serialize<S: Serializer>(v: &[u8; 16], s: S) -> Result<S::Ok, S::Error> {
        s.collect_str(&v.iter().map(|b| format!("{b:02x}")).collect::<String>())
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[u8; 16], D::Error> {
        let s = String::deserialize(d)?;
        let mut out = [0u8; 16];
        if s.len() != 32 {
            return Err(serde::de::Error::custom("digest must be 32 hex chars"));
        }
        for (i, o) in out.iter_mut().enumerate() {
            *o = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).map_err(serde::de::Error::custom)?;
        }
        Ok(out)
    }
}

// ─── frames ──────────────────────────────────────────────────────────────────

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClockReading {
    pub wall: i64,
    #[serde(default)]
    pub mono: Option<i64>,
    #[serde(default)]
    pub boot_id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AckError {
    pub code: String,
    pub message: String,
    pub retry: bool,
    /// With `retry`: send it again no sooner than this many ms from now (slow mode, D-076). The
    /// device holds the op until then instead of re-sending it at once.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AckResult {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seq: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub occurred_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub received_at: Option<i64>,
    /// Author account/device as stored (differs from the pusher for reconcile re-pushes).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub account_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<AckError>,
}

impl AckResult {
    pub fn ok(o: &Op) -> AckResult {
        AckResult {
            id: o.id.clone(),
            seq: o.seq,
            occurred_at: o.occurred_at,
            received_at: o.received_at,
            account_id: o.account_id.clone(),
            device_id: o.device_id.clone(),
            error: None,
        }
    }

    pub fn err(id: String, code: &str, message: String, retry: bool) -> AckResult {
        AckResult {
            id,
            seq: None,
            occurred_at: None,
            received_at: None,
            account_id: None,
            device_id: None,
            error: Some(AckError { code: code.into(), message, retry, retry_after_ms: None }),
        }
    }

    /// Refused for now: the device holds the op and sends it again after `after_ms` (D-076).
    pub fn later(id: String, code: &str, message: String, after_ms: i64) -> AckResult {
        let mut r = AckResult::err(id, code, message, true);
        if let Some(e) = r.error.as_mut() {
            e.retry_after_ms = Some(after_ms.max(0));
        }
        r
    }
}

/// Everything the server adds to an op when it accepts it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Stamp {
    pub seq: i64,
    pub occurred_at: i64,
    pub received_at: i64,
    pub account_id: String,
    pub device_id: String,
}

impl Stamp {
    pub fn apply(&self, o: &mut Op) {
        o.seq = Some(self.seq);
        o.occurred_at = Some(self.occurred_at);
        o.received_at = Some(self.received_at);
        o.account_id = Some(self.account_id.clone());
        o.device_id = Some(self.device_id.clone());
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Frame {
    Hello {
        device_id: String,
        #[serde(default)]
        token: String,
        #[serde(default)]
        epoch: Option<String>,
        cursors: BTreeMap<String, i64>,
        #[serde(default)]
        view_seq: i64,
        #[serde(default)]
        digests: BTreeMap<String, Digest>,
        clock: ClockReading,
        #[serde(default)]
        core: String,
        #[serde(default)]
        app: String,
        #[serde(default)]
        outbox: usize,
        /// A windowed replica (SYNC §6.5): message-family ops written before this time are left
        /// out of this connection's catch-up, live ops, repairs and digests; the device keeps
        /// none of them either, and reads older history over REST. `None`: everything.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        window: Option<i64>,
    },
    Welcome {
        server_time: i64,
        epoch: String,
        /// The account this device belongs to (the server is the authority).
        account_id: String,
        offset_ms: i64,
        scopes: Vec<String>,
        /// Server's highest seq per granted scope (used by reconcile).
        max_seq: BTreeMap<String, i64>,
        reconcile: bool,
        core_min: String,
    },
    Push {
        batch: String,
        ops: Vec<Op>,
        /// Reconcile re-push of already-accepted ops (SYNC.md §7.3).
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        restore: bool,
    },
    Ack {
        batch: String,
        results: Vec<AckResult>,
    },
    Ops {
        scope: String,
        ops: Vec<Op>,
        to: i64,
    },
    /// End of catch-up for a scope, with the server's digest of what the device should now hold.
    Caught {
        scope: String,
        to: i64,
        digest: Digest,
    },
    /// Client asks for a scope from `after` (used to repair after a digest mismatch).
    Pull {
        scope: String,
        after: i64,
    },
    Scope {
        #[serde(default)]
        add: Vec<String>,
        #[serde(default)]
        remove: Vec<String>,
    },
    Ping {
        clock: ClockReading,
    },
    Pong {
        server_time: i64,
    },
    Error {
        code: String,
        message: String,
    },
}

/// Whether a windowed replica leaves `o` out (SYNC §6.5): a message, reaction, attachment or
/// read mark the server received before `window`. Received, not written: a message written
/// offline long ago that arrived today stays on the device (it's newer than any backup, so the
/// device may be what brings it back after a restore). Channels, spaces, permissions and the
/// account scope are never left out: they're small and everything else hangs on them.
pub fn outside_window(o: &Op, window: Option<i64>) -> bool {
    let Some(w) = window else { return false };
    let k = o.kind.as_str();
    (k.starts_with("message.") || k.starts_with("reaction.") || k.starts_with("attachment.") || k.starts_with("read."))
        && o.received_at.or(o.occurred_at).unwrap_or(o.device_at) < w
}

// ─── client ──────────────────────────────────────────────────────────────────

/// What the client engine needs from the device's database. Implemented over Room (Android),
/// IndexedDB (web) and memory (tests).
pub trait ClientStore {
    fn epoch(&self) -> Option<String>;
    fn set_epoch(&mut self, epoch: &str);
    fn cursor(&self, scope: &str) -> i64;
    fn set_cursor(&mut self, scope: &str, seq: i64);
    fn scopes(&self) -> Vec<String>;
    fn set_scopes(&mut self, scopes: &[String]);
    /// Insert a server-confirmed op, replacing a local copy with the same id.
    fn put_remote(&mut self, op: Op);
    /// Mark an op confirmed with the server's stamp.
    fn ack(&mut self, id: &str, stamp: &Stamp);
    /// Move a pending op to "sync issues" (it stays out of projections and digests).
    fn reject(&mut self, id: &str, error: AckError);
    /// Pending ops excluding `skip`: new local ops in creation order (`restore == false`) or
    /// ops being restored to the server after a restore (`restore == true`).
    fn pending(&self, skip: &BTreeSet<String>, limit: usize, restore: bool) -> Vec<Op>;
    /// Put every confirmed op of a scope back into the outbox, flagged restore, keeping its
    /// stamps except `seq` (SYNC.md §7.3).
    fn demote_for_restore(&mut self, scope: &str);
    /// Digest of confirmed ops in a scope.
    fn digest(&self, scope: &str) -> Digest;
    /// Forget confirmed-state bookkeeping so the scope can be re-pulled (ops are kept).
    fn reset_scope(&mut self, scope: &str);
    /// Drop the scope's confirmed ops whose ids aren't in `keep` (the sweep that ends a repair).
    /// Pending and restoring ops stay.
    fn evict(&mut self, scope: &str, keep: &BTreeSet<String>);
    /// A scope the account no longer reads: drop its confirmed ops and the copies being restored
    /// to a restored server (the server would refuse them now, or ack ones it has, which would
    /// confirm them here again). The device's own unsent ops stay, to be refused with a reason.
    fn forget(&mut self, scope: &str);
    /// A windowed replica (SYNC §6.5): drop confirmed ops [`outside_window`] (not pending or
    /// restoring ones), reporting them as removed.
    fn trim(&mut self, window: i64);
    /// [`ClientStore::trim`] for just these ops (ones confirmed a moment ago).
    fn trim_ids(&mut self, window: i64, ids: &[String]);
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClientState {
    Disconnected,
    AwaitWelcome,
    Live,
}

/// Device-side sync state machine. Feed it events; send the frames it returns.
#[derive(Debug)]
pub struct ClientEngine {
    pub device_id: String,
    pub state: ClientState,
    /// batch → (op id, scope) of the ops it carries
    in_flight: HashMap<String, Vec<(String, String)>>,
    next_batch: u64,
    pub last_offset_ms: i64,
    /// Scopes whose digest check failed and are being re-pulled.
    pub repairs: u64,
    pub account_id: Option<String>,
    /// Scopes being re-pulled after a digest mismatch, with the ids the re-pull has delivered so
    /// far: at the next `caught` for the scope, confirmed ops not among them are swept (the
    /// account may no longer see them, e.g. it lost `view` on a channel).
    repairing: HashMap<String, BTreeSet<String>>,
    /// Keep only message-family ops written since this time (SYNC §6.5), `None`: everything.
    /// Takes effect at the next connect.
    pub window: Option<i64>,
    /// Ops the server asked to get again later (slow mode, D-076): op id → device time (ms) it
    /// may be sent again. [`ClientEngine::pump`] skips them until then; `now` is the device
    /// time of the latest event, set by the caller (`Replica::on_frame`, `tick`, `create`).
    pub held: BTreeMap<String, i64>,
    pub now: i64,
}

impl ClientEngine {
    pub fn new(device_id: &str) -> Self {
        ClientEngine {
            device_id: device_id.into(),
            state: ClientState::Disconnected,
            in_flight: HashMap::new(),
            next_batch: 0,
            last_offset_ms: 0,
            repairs: 0,
            account_id: None,
            repairing: HashMap::new(),
            window: None,
            held: BTreeMap::new(),
            now: 0,
        }
    }

    pub fn on_connect(&mut self, store: &mut dyn ClientStore, clock: ClockReading, token: &str) -> Frame {
        self.state = ClientState::AwaitWelcome;
        self.in_flight.clear();
        // what the window left behind goes before the digests are taken
        if let Some(w) = self.window {
            store.trim(w);
        }
        let scopes = store.scopes();
        Frame::Hello {
            device_id: self.device_id.clone(),
            token: token.into(),
            epoch: store.epoch(),
            cursors: scopes.iter().map(|s| (s.clone(), store.cursor(s))).collect(),
            view_seq: 0,
            digests: scopes.iter().map(|s| (s.clone(), store.digest(s))).collect(),
            clock,
            core: env!("CARGO_PKG_VERSION").into(),
            app: String::new(),
            outbox: store.pending(&BTreeSet::new(), usize::MAX, false).len(),
            window: self.window,
        }
    }

    pub fn on_disconnect(&mut self) {
        self.state = ClientState::Disconnected;
        self.in_flight.clear(); // unacked ops are simply re-sent next time
    }

    /// Push pending ops if there's room in the pipeline.
    pub fn pump(&mut self, store: &dyn ClientStore) -> Vec<Frame> {
        let mut out = Vec::new();
        if self.state != ClientState::Live {
            return out;
        }
        // held ops whose time has come go out again with the rest
        let now = self.now;
        self.held.retain(|_, until| *until > now);
        // Ops being restored after a server restore go first, in their own batches.
        for restore in [true, false] {
            while self.in_flight.len() < MAX_IN_FLIGHT {
                let skip: BTreeSet<String> = self
                    .in_flight
                    .values()
                    .flatten()
                    .map(|(id, _)| id.clone())
                    .chain(self.held.keys().cloned())
                    .collect();
                let ops = store.pending(&skip, BATCH_OPS, restore);
                if ops.is_empty() {
                    break;
                }
                self.next_batch += 1;
                let batch = format!("b{}", self.next_batch);
                self.in_flight.insert(batch.clone(), ops.iter().map(|o| (o.id.clone(), o.scope.clone())).collect());
                out.push(Frame::Push { batch, ops, restore });
            }
        }
        out
    }

    /// "Sync everything now" (CLIENTS.md §4.3): ask for every scope from where this device is.
    /// Each answer ends with the server's digest, so a scope that diverged is repaired.
    pub fn recheck(&self, store: &dyn ClientStore) -> Vec<Frame> {
        if self.state != ClientState::Live {
            return Vec::new();
        }
        store.scopes().into_iter().map(|scope| Frame::Pull { after: store.cursor(&scope), scope }).collect()
    }

    /// Scopes being re-pulled after a digest mismatch.
    pub fn repairing(&self) -> Vec<String> {
        let mut v: Vec<String> = self.repairing.keys().cloned().collect();
        v.sort();
        v
    }

    pub fn on_frame(&mut self, store: &mut dyn ClientStore, frame: Frame) -> Vec<Frame> {
        let mut out = Vec::new();
        match frame {
            Frame::Welcome { epoch, account_id, offset_ms, scopes, max_seq, reconcile, .. } => {
                self.last_offset_ms = offset_ms;
                self.account_id = Some(account_id);
                store.set_epoch(&epoch);
                // scopes the account lost while this device was away
                for gone in store.scopes().into_iter().filter(|s| !scopes.contains(s)) {
                    store.forget(&gone);
                }
                self.repairing.clear();
                store.set_scopes(&scopes);
                self.state = ClientState::Live;
                if reconcile {
                    // The server lost ops (restored from a backup). Seqs from the old epoch mean
                    // nothing now — they get reused — so every confirmed op goes back into the
                    // outbox (flagged restore) and is retried like any pending op until the server
                    // has it; the server keeps ones it already has. Then pull from zero.
                    let _ = &max_seq;
                    for s in &scopes {
                        store.demote_for_restore(s);
                        store.reset_scope(s);
                    }
                    out.extend(self.pump(store));
                    for s in &scopes {
                        out.push(Frame::Pull { scope: s.clone(), after: 0 });
                    }
                    return out;
                }
                out.extend(self.pump(store));
            }
            Frame::Ack { batch, results } => {
                let flown = self.in_flight.remove(&batch).unwrap_or_default();
                for r in results {
                    match (r.seq, r.occurred_at, r.error) {
                        (Some(seq), Some(at), None) => {
                            let stamp = Stamp {
                                seq,
                                occurred_at: at,
                                received_at: r.received_at.unwrap_or(at),
                                account_id: r.account_id.or_else(|| self.account_id.clone()).unwrap_or_default(),
                                device_id: r.device_id.unwrap_or_else(|| self.device_id.clone()),
                            };
                            store.ack(&r.id, &stamp)
                        }
                        (_, _, Some(e)) if !e.retry => store.reject(&r.id, e),
                        (_, _, Some(AckError { retry_after_ms: Some(ms), .. })) => {
                            self.held.insert(r.id.clone(), self.now.saturating_add(ms));
                        }
                        _ => {} // retryable: stays pending
                    }
                }
                // a scope that went away while the batch was in flight: the server acks ops it
                // already has by id (a restore push of a copy), but the device no longer reads it
                let held: BTreeSet<String> = store.scopes().into_iter().collect();
                let gone: BTreeSet<&String> = flown.iter().map(|(_, s)| s).filter(|s| !held.contains(*s)).collect();
                for scope in gone {
                    store.forget(scope);
                }
                // one written long ago (offline) and only now confirmed: outside the window
                if let Some(w) = self.window {
                    let ids: Vec<String> = flown.iter().map(|(id, _)| id.clone()).collect();
                    store.trim_ids(w, &ids);
                }
                out.extend(self.pump(store));
            }
            Frame::Ops { scope, ops, to } => {
                let repair = self.repairing.get_mut(&scope);
                if let Some(seen) = repair {
                    seen.extend(ops.iter().map(|o| o.id.clone()));
                }
                for o in ops.into_iter().filter(|o| !outside_window(o, self.window)) {
                    store.put_remote(o);
                }
                if to > store.cursor(&scope) {
                    store.set_cursor(&scope, to);
                }
            }
            Frame::Caught { scope, to, digest } => {
                if to > store.cursor(&scope) {
                    store.set_cursor(&scope, to);
                }
                if let Some(seen) = self.repairing.remove(&scope) {
                    // a re-pull ended: what it didn't deliver, the account no longer sees. If the
                    // digests still differ, stop here rather than loop; the next catch-up retries.
                    store.evict(&scope, &seen);
                } else if store.digest(&scope) != digest {
                    // Something diverged: re-pull the whole scope. Ops are idempotent.
                    self.repairs += 1;
                    store.reset_scope(&scope);
                    self.repairing.insert(scope.clone(), BTreeSet::new());
                    out.push(Frame::Pull { scope, after: 0 });
                }
            }
            Frame::Scope { add, remove } => {
                let mut s: BTreeSet<String> = store.scopes().into_iter().collect();
                for r in &remove {
                    s.remove(r);
                    self.repairing.remove(r);
                    store.forget(r);
                }
                for a in &add {
                    s.insert(a.clone());
                    out.push(Frame::Pull { scope: a.clone(), after: store.cursor(a) });
                }
                store.set_scopes(&s.into_iter().collect::<Vec<_>>());
            }
            Frame::Pong { .. } | Frame::Error { .. } => {}
            _ => {}
        }
        out
    }
}

/// In-memory client store: the simulator's store, and the replica the apps keep in memory while
/// persisting changes write-behind (see [`crate::replica`]).
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct MemStore {
    pub epoch: Option<String>,
    pub cursors: BTreeMap<String, i64>,
    pub scopes: Vec<String>,
    /// All ops this device knows, by id. Pending ones have `seq == None`.
    #[serde(skip)]
    pub ops: BTreeMap<String, Op>,
    /// Creation order of local ops not yet confirmed (compacted by [`MemStore::compact`]).
    pub local_order: Vec<String>,
    pub rejected: BTreeMap<String, AckError>,
    /// Ops being restored to a restored server (demoted from confirmed).
    pub restoring: Vec<String>,
    /// Op ids changed since the last [`MemStore::take_dirty`].
    #[serde(skip)]
    pub dirty: BTreeSet<String>,
    /// Cursors/epoch/scopes/queues changed since the last take.
    #[serde(skip)]
    pub meta_dirty: bool,
    /// Op ids whose visible copy may have changed since the projector last looked
    /// ([`MemStore::take_touched`]); every op mutation below records here.
    #[serde(skip)]
    pub touched: BTreeSet<String>,
    /// Op ids evicted since the last [`MemStore::take_dirty`] (to delete from storage).
    #[serde(skip)]
    pub removed: BTreeSet<String>,
}

impl MemStore {
    pub fn add_local(&mut self, op: Op) {
        self.touched.insert(op.id.clone());
        self.local_order.push(op.id.clone());
        self.dirty.insert(op.id.clone());
        self.meta_dirty = true;
        self.ops.insert(op.id.clone(), op);
    }

    /// Drop confirmed ids from the local order queue.
    pub fn compact(&mut self) {
        let ops = &self.ops;
        let before = self.local_order.len();
        self.local_order.retain(|id| ops.get(id).is_some_and(|o| o.seq.is_none()));
        if self.local_order.len() != before {
            self.meta_dirty = true;
        }
    }

    /// Op ids that may look different to the projection since the last call.
    pub fn take_touched(&mut self) -> BTreeSet<String> {
        std::mem::take(&mut self.touched)
    }

    /// Changed ops (current versions) and whether the meta changed, clearing the flags.
    pub fn take_dirty(&mut self) -> (Vec<Op>, bool) {
        let ids = std::mem::take(&mut self.dirty);
        let meta = std::mem::replace(&mut self.meta_dirty, false);
        (ids.iter().filter_map(|id| self.ops.get(id).cloned()).collect(), meta)
    }

    /// Confirmed ops for projection/comparison.
    pub fn confirmed(&self) -> impl Iterator<Item = &Op> {
        self.ops.values().filter(|o| o.seq.is_some())
    }

    /// What the UI would project: confirmed + pending, minus rejected.
    pub fn visible(&self) -> impl Iterator<Item = &Op> {
        self.ops.values().filter(|o| !self.rejected.contains_key(&o.id))
    }
}

impl ClientStore for MemStore {
    fn epoch(&self) -> Option<String> {
        self.epoch.clone()
    }
    fn set_epoch(&mut self, epoch: &str) {
        self.epoch = Some(epoch.into());
        self.meta_dirty = true;
    }
    fn cursor(&self, scope: &str) -> i64 {
        *self.cursors.get(scope).unwrap_or(&0)
    }
    fn set_cursor(&mut self, scope: &str, seq: i64) {
        self.cursors.insert(scope.into(), seq);
        self.meta_dirty = true;
    }
    fn scopes(&self) -> Vec<String> {
        self.scopes.clone()
    }
    fn set_scopes(&mut self, scopes: &[String]) {
        self.scopes = scopes.to_vec();
        self.meta_dirty = true;
    }
    fn put_remote(&mut self, op: Op) {
        if self.rejected.remove(&op.id).is_some() || self.restoring.contains(&op.id) {
            self.restoring.retain(|id| *id != op.id);
            self.meta_dirty = true;
        }
        self.dirty.insert(op.id.clone());
        self.touched.insert(op.id.clone());
        self.ops.insert(op.id.clone(), op);
    }
    fn ack(&mut self, id: &str, stamp: &Stamp) {
        if let Some(o) = self.ops.get_mut(id) {
            stamp.apply(o);
            self.dirty.insert(id.into());
            self.touched.insert(id.into());
        }
        if self.restoring.iter().any(|x| x == id) {
            self.restoring.retain(|x| x != id);
            self.meta_dirty = true;
        }
    }
    fn reject(&mut self, id: &str, error: AckError) {
        if self.ops.get(id).is_some_and(|o| o.seq.is_none()) {
            self.rejected.insert(id.into(), error);
            self.touched.insert(id.into());
            self.meta_dirty = true;
        }
    }
    fn pending(&self, skip: &BTreeSet<String>, limit: usize, restore: bool) -> Vec<Op> {
        let order = if restore { &self.restoring } else { &self.local_order };
        order
            .iter()
            .filter(|id| !skip.contains(*id) && !self.rejected.contains_key(*id))
            .filter(|id| restore || !self.restoring.contains(id))
            .filter_map(|id| self.ops.get(id))
            .filter(|o| o.seq.is_none())
            .take(limit)
            .cloned()
            .collect()
    }
    fn demote_for_restore(&mut self, scope: &str) {
        let mut ids: Vec<(i64, String)> = self
            .ops
            .values()
            .filter(|o| o.scope == scope && o.seq.is_some())
            .map(|o| (o.seq.unwrap_or(0), o.id.clone()))
            .collect();
        ids.sort();
        for (_, id) in ids {
            if let Some(o) = self.ops.get_mut(&id) {
                o.seq = None;
            }
            self.dirty.insert(id.clone());
            self.touched.insert(id.clone());
            self.restoring.push(id);
        }
        self.meta_dirty = true;
    }
    fn digest(&self, scope: &str) -> Digest {
        Digest::of(self.ops.values().filter(|o| o.scope == scope && o.seq.is_some()).map(|o| o.id.as_str()))
    }
    fn reset_scope(&mut self, scope: &str) {
        self.cursors.insert(scope.into(), 0);
        self.meta_dirty = true;
    }
    fn trim(&mut self, window: i64) {
        let gone: Vec<String> = self
            .ops
            .values()
            .filter(|o| o.seq.is_some() && outside_window(o, Some(window)) && !self.restoring.contains(&o.id))
            .map(|o| o.id.clone())
            .collect();
        for id in gone {
            self.ops.remove(&id);
            self.dirty.remove(&id);
            self.touched.insert(id.clone());
            self.removed.insert(id);
        }
    }
    fn trim_ids(&mut self, window: i64, ids: &[String]) {
        for id in ids {
            let out = self
                .ops
                .get(id)
                .is_some_and(|o| o.seq.is_some() && outside_window(o, Some(window)) && !self.restoring.contains(id));
            if out {
                self.ops.remove(id);
                self.dirty.remove(id);
                self.touched.insert(id.clone());
                self.removed.insert(id.clone());
            }
        }
    }
    fn forget(&mut self, scope: &str) {
        self.evict(scope, &BTreeSet::new());
        let restoring: Vec<String> =
            self.restoring.iter().filter(|id| self.ops.get(*id).is_some_and(|o| o.scope == scope)).cloned().collect();
        if restoring.is_empty() {
            return;
        }
        self.restoring.retain(|id| !restoring.contains(id));
        self.meta_dirty = true;
        for id in restoring {
            self.ops.remove(&id);
            self.dirty.remove(&id);
            self.touched.insert(id.clone());
            self.removed.insert(id);
        }
    }
    fn evict(&mut self, scope: &str, keep: &BTreeSet<String>) {
        let gone: Vec<String> = self
            .ops
            .values()
            .filter(|o| o.scope == scope && o.seq.is_some() && !keep.contains(&o.id) && !self.restoring.contains(&o.id))
            .map(|o| o.id.clone())
            .collect();
        for id in gone {
            self.ops.remove(&id);
            self.dirty.remove(&id);
            self.touched.insert(id.clone());
            self.removed.insert(id);
        }
    }
}

// ─── reference server ────────────────────────────────────────────────────────

#[derive(Clone, Debug)]
struct Conn {
    account: String,
    sample: ClockSample,
    /// Scopes the connection receives live ops for.
    scopes: BTreeSet<String>,
    /// Its window (SYNC §6.5), from its hello.
    window: Option<i64>,
}

/// In-memory reference server.
#[derive(Clone, Debug)]
pub struct MemServer {
    pub instance: String,
    pub epoch: u64,
    /// The op log; `log[i].seq == i + 1`.
    pub log: Vec<Op>,
    by_id: HashMap<String, usize>,
    /// account → scopes it may read/write
    pub access: BTreeMap<String, BTreeSet<String>>,
    /// device → account
    pub devices: BTreeMap<String, String>,
    conns: BTreeMap<String, Conn>,
    pub rejected: Vec<(String, OpError)>,
    /// After a restore, devices may re-push ops with their original stamps (SYNC.md §7.3).
    /// Closed by an admin once every device has reconciled.
    pub restore_open: bool,
}

impl MemServer {
    pub fn new(instance: &str) -> Self {
        MemServer {
            instance: instance.into(),
            epoch: 1,
            log: Vec::new(),
            by_id: HashMap::new(),
            access: BTreeMap::new(),
            devices: BTreeMap::new(),
            conns: BTreeMap::new(),
            rejected: Vec::new(),
            restore_open: false,
        }
    }

    pub fn epoch_str(&self) -> String {
        format!("{}:{}", self.instance, self.epoch)
    }

    pub fn grant(&mut self, account: &str, scope: &str) {
        self.access.entry(account.into()).or_default().insert(scope.into());
    }

    /// Give or take away an account's scope while it may be connected (joining or leaving a
    /// space, a guest gaining or losing a channel): its connected devices get a `scope` frame,
    /// like the server's fan-out sends one (SYNC.md §4.2, §6.5).
    pub fn set_access(&mut self, account: &str, scope: &str, on: bool) -> Vec<(String, Frame)> {
        let set = self.access.entry(account.into()).or_default();
        let changed = if on { set.insert(scope.into()) } else { set.remove(scope) };
        let mut out = Vec::new();
        if !changed {
            return out;
        }
        for (device, c) in self.conns.iter_mut().filter(|(_, c)| c.account == account) {
            let (add, remove) = if on {
                c.scopes.insert(scope.into());
                (vec![scope.to_string()], vec![])
            } else {
                c.scopes.remove(scope);
                (vec![], vec![scope.to_string()])
            };
            out.push((device.clone(), Frame::Scope { add, remove }));
        }
        out
    }

    fn scope_ops(&self, scope: &str, after: i64) -> impl Iterator<Item = &Op> {
        self.log.iter().filter(move |o| o.scope == scope && o.seq.unwrap_or(0) > after)
    }

    pub fn max_seq(&self, scope: &str) -> i64 {
        self.log.iter().rev().find(|o| o.scope == scope).and_then(|o| o.seq).unwrap_or(0)
    }

    pub fn digest(&self, scope: &str) -> Digest {
        self.digest_in(scope, None)
    }

    /// The digest a windowed device holds (SYNC §6.5).
    pub fn digest_in(&self, scope: &str, window: Option<i64>) -> Digest {
        Digest::of(self.scope_ops(scope, 0).filter(|o| !outside_window(o, window)).map(|o| o.id.as_str()))
    }

    pub fn disconnect(&mut self, device: &str) {
        self.conns.remove(device);
    }

    pub fn disconnect_all(&mut self) {
        self.conns.clear();
    }

    /// Simulate restoring a backup taken when the log had `keep` ops: later ops are gone and the
    /// epoch changes (SYNC.md §7.3).
    pub fn restore(&mut self, keep: usize) {
        self.log.truncate(keep);
        self.by_id = self.log.iter().enumerate().map(|(i, o)| (o.id.clone(), i)).collect();
        self.epoch += 1;
        self.restore_open = true;
        self.conns.clear();
    }

    fn catch_up(&self, scope: &str, after: i64, window: Option<i64>) -> Vec<Frame> {
        let ops: Vec<Op> = self.scope_ops(scope, after).filter(|o| !outside_window(o, window)).cloned().collect();
        let to = self.max_seq(scope);
        let mut out: Vec<Frame> = ops
            .chunks(PAGE_OPS)
            .map(|c| Frame::Ops {
                scope: scope.into(),
                ops: c.to_vec(),
                to: c.last().and_then(|o| o.seq).unwrap_or(to),
            })
            .collect();
        out.push(Frame::Caught { scope: scope.into(), to, digest: self.digest_in(scope, window) });
        out
    }

    /// Accept one op from a device. Idempotent by id.
    ///
    /// `restore` marks a reconcile re-push (SYNC.md §7.3): the op was already accepted in an
    /// earlier epoch, so its original author and times are kept and only `seq` is new.
    fn accept(&mut self, device: &str, mut o: Op, now: i64, restore: bool) -> (AckResult, Option<Op>) {
        let conn = self.conns.get(device).cloned();
        let Some(conn) = conn else {
            return (AckResult::err(o.id, "unauthenticated", "no session".into(), true), None);
        };
        if let Some(&i) = self.by_id.get(&o.id) {
            return (AckResult::ok(&self.log[i]), None);
        }
        if let Err(e) = op::validate_new(&o) {
            self.rejected.push((o.id.clone(), e.clone()));
            return (AckResult::err(o.id, e.code(), e.to_string(), false), None);
        }
        let can = |acct: &str| self.access.get(acct).is_some_and(|s| s.contains(&o.scope));
        let preserved = restore && o.account_id.is_some() && o.occurred_at.is_some();
        // Authorship: a fresh op is the pusher's; a restored op keeps its author, who must
        // still have access, and the pusher must be able to read the scope too.
        let author = if preserved { o.account_id.clone().unwrap_or_default() } else { conn.account.clone() };
        let allowed = Scope::parse(&o.scope).is_some() && can(&author) && can(&conn.account);
        if !allowed {
            let e = OpError::BadScope(format!("{} not writable", o.scope));
            self.rejected.push((o.id.clone(), e.clone()));
            return (AckResult::err(o.id, "forbidden", e.to_string(), false), None);
        }
        if !preserved
            && o.kind.ends_with(".restore")
            && !crate::restore::allowed(&o.kind, &o.scope, o.entity().unwrap_or_default(), &author, self.log.iter())
        {
            return (
                AckResult::err(
                    o.id,
                    "forbidden",
                    "only the creator or deleting account may restore this item".into(),
                    false,
                ),
                None,
            );
        }
        o.seq = Some(self.log.len() as i64 + 1);
        if !preserved {
            let t = time::OpTime {
                device_at: o.device_at,
                mono: o.mono,
                boot_id: o.boot_id.clone(),
                time_source: o.time_source,
            };
            o.occurred_at = Some(time::correct(&t, &conn.sample, now).occurred_at);
            o.account_id = Some(conn.account.clone());
            o.device_id = Some(device.into());
            o.received_at = Some(now);
        }
        self.by_id.insert(o.id.clone(), self.log.len());
        self.log.push(o.clone());
        (AckResult::ok(&o), Some(o))
    }

    /// Handle a frame from `device`. Returns frames to deliver: `(device, frame)`.
    pub fn on_frame(&mut self, device: &str, frame: Frame, now: i64) -> Vec<(String, Frame)> {
        let mut out = Vec::new();
        match frame {
            Frame::Hello { epoch, cursors, clock, window, .. } => {
                let Some(account) = self.devices.get(device).cloned() else {
                    out.push((
                        device.into(),
                        Frame::Error { code: "unauthenticated".into(), message: "unknown device".into() },
                    ));
                    return out;
                };
                let scopes: Vec<String> =
                    self.access.get(&account).map(|s| s.iter().cloned().collect()).unwrap_or_default();
                let offset = now - clock.wall;
                let sample = ClockSample {
                    server_time: now,
                    mono: clock.mono,
                    boot_id: clock.boot_id.clone(),
                    offset_ms: offset,
                };
                let max_seq: BTreeMap<String, i64> = scopes.iter().map(|s| (s.clone(), self.max_seq(s))).collect();
                let reconcile = epoch.as_ref().is_some_and(|e| *e != self.epoch_str())
                    || cursors.iter().any(|(s, c)| *c > *max_seq.get(s).unwrap_or(&0));
                self.conns
                    .insert(device.into(), Conn { account, sample, scopes: scopes.iter().cloned().collect(), window });
                out.push((
                    device.into(),
                    Frame::Welcome {
                        server_time: now,
                        epoch: self.epoch_str(),
                        account_id: self.devices.get(device).cloned().unwrap_or_default(),
                        offset_ms: offset,
                        scopes: scopes.clone(),
                        max_seq,
                        reconcile,
                        core_min: "0.1.0".into(),
                    },
                ));
                if !reconcile {
                    for s in &scopes {
                        let after = *cursors.get(s).unwrap_or(&0);
                        out.extend(self.catch_up(s, after, window).into_iter().map(|f| (device.to_string(), f)));
                    }
                }
            }
            Frame::Push { batch, ops, restore } => {
                let restore = restore && self.restore_open;
                if !self.conns.contains_key(device) {
                    return out;
                }
                let mut results = Vec::new();
                let mut fresh: Vec<Op> = Vec::new();
                for o in ops {
                    let (r, new) = self.accept(device, o, now, restore);
                    results.push(r);
                    fresh.extend(new);
                }
                out.push((device.into(), Frame::Ack { batch, results }));
                // fan out to every other connected device that reads the scope
                for o in fresh {
                    for (d, c) in &self.conns {
                        if d != device && c.scopes.contains(&o.scope) && !outside_window(&o, c.window) {
                            let seq = o.seq.unwrap_or(0);
                            out.push((d.clone(), Frame::Ops { scope: o.scope.clone(), ops: vec![o.clone()], to: seq }));
                        }
                    }
                }
            }
            Frame::Pull { scope, after } => {
                if let Some(c) = self.conns.get(device).filter(|c| c.scopes.contains(&scope)) {
                    let window = c.window;
                    out.extend(self.catch_up(&scope, after, window).into_iter().map(|f| (device.to_string(), f)));
                }
            }
            Frame::Ping { .. } => out.push((device.into(), Frame::Pong { server_time: now })),
            _ => {}
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digest_is_order_independent_and_serializes() {
        let a = Digest::of(["x", "y", "z"]);
        let b = Digest::of(["z", "x", "y"]);
        assert_eq!(a, b);
        assert_ne!(a, Digest::of(["x", "y"]));
        let j = serde_json::to_string(&a).unwrap();
        assert_eq!(serde_json::from_str::<Digest>(&j).unwrap(), a);
    }

    fn confirmed(n: i64, scope: &str) -> Op {
        Op {
            id: crate::id::new_id(n as u64, [n as u8; 10]),
            kind: "message.send".into(),
            v: 1,
            scope: scope.into(),
            entity_id: None,
            hlc: crate::hlc::Hlc::new(n as u64, 0, 1),
            device_at: n,
            tz_offset_min: 0,
            mono: None,
            boot_id: None,
            time_source: time::TimeSource::Auto,
            seen_seq: 0,
            member_id: None,
            payload: serde_json::json!({}),
            seq: Some(n),
            account_id: Some("a".into()),
            device_id: Some("d".into()),
            occurred_at: Some(n),
            received_at: Some(n),
        }
    }

    /// Losing sight of ops (a channel's `view` taken away, SYNC.md §4.2): the mismatch starts a
    /// re-pull, and what the re-pull doesn't deliver is swept, once — never a pull loop.
    #[test]
    fn a_repair_sweeps_what_the_account_no_longer_sees() {
        let scope = "space:s";
        let mut store = MemStore::default();
        let mut engine = ClientEngine::new("d");
        let ops: Vec<Op> = (1..=3).map(|n| confirmed(n, scope)).collect();
        for o in &ops {
            store.put_remote(o.clone());
        }
        let kept = Digest::of([ops[0].id.as_str(), ops[2].id.as_str()]);
        let caught = |d: Digest| Frame::Caught { scope: scope.into(), to: 3, digest: d };
        let out = engine.on_frame(&mut store, caught(kept));
        assert!(matches!(&out[..], [Frame::Pull { after: 0, .. }]), "a mismatch re-pulls");
        let redelivered = Frame::Ops { scope: scope.into(), ops: vec![ops[0].clone(), ops[2].clone()], to: 3 };
        assert!(engine.on_frame(&mut store, redelivered).is_empty());
        assert!(engine.on_frame(&mut store, caught(kept)).is_empty());
        assert_eq!(store.digest(scope), kept, "swept down to what the server sends");
        assert!(!store.ops.contains_key(&ops[1].id));
        assert_eq!(store.removed, BTreeSet::from([ops[1].id.clone()]), "and told to storage");
        // a digest that still differs after a sweep ends the repair instead of looping
        let out = engine.on_frame(&mut store, caught(Digest::of(["other"])));
        assert_eq!(out.len(), 1, "one new repair");
        assert!(engine.on_frame(&mut store, caught(Digest::of(["other"]))).is_empty(), "and it ends");
        assert_eq!(engine.repairs, 2);
    }

    #[test]
    fn a_removed_scope_is_evicted_but_pending_ops_stay() {
        let mut store = MemStore::default();
        let mut engine = ClientEngine::new("d");
        store.put_remote(confirmed(1, "space:s"));
        store.put_remote(confirmed(2, "space:t"));
        let mut pending = confirmed(3, "space:s");
        pending.seq = None;
        store.add_local(pending.clone());
        store.set_scopes(&["space:s".into(), "space:t".into()]);
        engine.on_frame(&mut store, Frame::Scope { add: vec![], remove: vec!["space:s".into()] });
        assert_eq!(
            store.ops.keys().cloned().collect::<BTreeSet<_>>(),
            BTreeSet::from([confirmed(2, "").id, pending.id])
        );
    }

    /// After a restore, copies of other accounts' ops are pushed back (restoring). If the
    /// scope goes away meanwhile, they go with it, and an ack still in flight for one (the
    /// server acks an op it already has by id) doesn't bring it back as confirmed.
    #[test]
    fn a_lost_scope_takes_its_restoring_copies_along() {
        let mut store = MemStore::default();
        let mut engine = ClientEngine::new("d");
        let scopes = vec!["space:s".to_string(), "space:t".to_string()];
        store.set_scopes(&scopes);
        for n in 1..=3 {
            store.put_remote(confirmed(n, if n == 3 { "space:t" } else { "space:s" }));
        }
        // and one of its own, sent before but never acked (the server has it)
        let mut own = confirmed(4, "space:s");
        own.seq = None;
        store.add_local(own.clone());
        let welcome = |reconcile| Frame::Welcome {
            server_time: 0,
            epoch: "e2".into(),
            account_id: "a".into(),
            offset_ms: 0,
            scopes: scopes.clone(),
            max_seq: BTreeMap::new(),
            reconcile,
            core_min: String::new(),
        };
        let out = engine.on_frame(&mut store, welcome(true));
        let pushed: Vec<(String, Vec<Op>)> = out
            .into_iter()
            .filter_map(|f| match f {
                Frame::Push { batch, ops, .. } => Some((batch, ops)),
                _ => None,
            })
            .collect();
        assert_eq!(pushed.iter().map(|(_, ops)| ops.len()).sum::<usize>(), 4, "all go back, and the unsent one");
        engine.on_frame(&mut store, Frame::Scope { add: vec![], remove: vec!["space:s".into()] });
        assert_eq!(store.restoring, vec![confirmed(3, "").id], "space:s's copies are gone");
        assert!(store.ops.contains_key(&own.id), "its own unsent op stays, to be refused with a reason");
        // the server had them and acks them all
        for (batch, ops) in pushed {
            let results = ops
                .iter()
                .map(|o| AckResult { seq: Some(o.seq.unwrap_or(0) + 10), ..AckResult::ok(&confirmed(1, "")) })
                .zip(&ops)
                .map(|(r, o)| AckResult { id: o.id.clone(), ..r })
                .collect();
            engine.on_frame(&mut store, Frame::Ack { batch, results });
        }
        let held: Vec<&str> = store.confirmed().map(|o| o.scope.as_str()).collect();
        assert_eq!(held, vec!["space:t"], "nothing of space:s is confirmed again");
        assert!(store.pending(&BTreeSet::new(), usize::MAX, false).is_empty());
    }

    /// SYNC §6.5: turning a window on drops, at the next connect, the confirmed message-family
    /// ops that arrived before it (reported as removed), keeps everything else, and says so in
    /// the hello so the server leaves them out too.
    #[test]
    fn a_window_trims_at_connect() {
        let mut store = MemStore::default();
        let mut old = confirmed(1, "space:s");
        old.received_at = Some(100);
        let mut new = confirmed(2, "space:s");
        new.received_at = Some(300);
        let mut channel = confirmed(3, "space:s");
        channel.kind = "channel.create".into();
        channel.received_at = Some(50);
        let mut pending = confirmed(4, "space:s");
        pending.seq = None;
        pending.received_at = Some(10);
        for o in [&old, &new, &channel] {
            store.put_remote((*o).clone());
        }
        store.add_local(pending.clone());
        let mut engine = ClientEngine::new("d");
        engine.window = Some(200);
        let hello = engine.on_connect(&mut store, ClockReading { wall: 0, mono: None, boot_id: None }, "t");
        assert!(matches!(hello, Frame::Hello { window: Some(200), .. }));
        let kept: BTreeSet<String> = store.ops.keys().cloned().collect();
        assert_eq!(kept, BTreeSet::from([new.id, channel.id, pending.id]));
        assert_eq!(store.removed, BTreeSet::from([old.id]));
    }

    #[test]
    fn recheck_asks_for_every_scope_from_its_cursor_once_live() {
        let mut store = MemStore::default();
        let mut engine = ClientEngine::new("d");
        assert!(engine.recheck(&store).is_empty(), "not before the server answers");
        let scopes = vec!["account:a".to_string(), "space:s".to_string()];
        let welcome = Frame::Welcome {
            server_time: 0,
            epoch: "1".into(),
            account_id: "a".into(),
            offset_ms: 0,
            scopes: scopes.clone(),
            max_seq: BTreeMap::new(),
            reconcile: false,
            core_min: String::new(),
        };
        engine.on_frame(&mut store, welcome);
        store.set_cursor("space:s", 7);
        let frames = engine.recheck(&store);
        assert_eq!(
            frames,
            vec![
                Frame::Pull { scope: "account:a".into(), after: 0 },
                Frame::Pull { scope: "space:s".into(), after: 7 }
            ]
        );
        assert!(engine.repairing().is_empty());
    }

    #[test]
    fn frames_are_tagged() {
        let f = Frame::Pull { scope: "space:x".into(), after: 3 };
        assert_eq!(serde_json::to_string(&f).unwrap(), r#"{"t":"pull","scope":"space:x","after":3}"#);
    }
}
