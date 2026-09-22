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
            error: Some(AckError { code: code.into(), message, retry }),
        }
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
    in_flight: HashMap<String, Vec<String>>,
    next_batch: u64,
    pub last_offset_ms: i64,
    /// Scopes whose digest check failed and are being re-pulled.
    pub repairs: u64,
    pub account_id: Option<String>,
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
        }
    }

    pub fn on_connect(&mut self, store: &dyn ClientStore, clock: ClockReading, token: &str) -> Frame {
        self.state = ClientState::AwaitWelcome;
        self.in_flight.clear();
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
        // Ops being restored after a server restore go first, in their own batches.
        for restore in [true, false] {
            while self.in_flight.len() < MAX_IN_FLIGHT {
                let skip: BTreeSet<String> = self.in_flight.values().flatten().cloned().collect();
                let ops = store.pending(&skip, BATCH_OPS, restore);
                if ops.is_empty() {
                    break;
                }
                self.next_batch += 1;
                let batch = format!("b{}", self.next_batch);
                self.in_flight.insert(batch.clone(), ops.iter().map(|o| o.id.clone()).collect());
                out.push(Frame::Push { batch, ops, restore });
            }
        }
        out
    }

    pub fn on_frame(&mut self, store: &mut dyn ClientStore, frame: Frame) -> Vec<Frame> {
        let mut out = Vec::new();
        match frame {
            Frame::Welcome { epoch, account_id, offset_ms, scopes, max_seq, reconcile, .. } => {
                self.last_offset_ms = offset_ms;
                self.account_id = Some(account_id);
                store.set_epoch(&epoch);
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
                        _ => {} // retryable: stays pending
                    }
                }
                self.in_flight.remove(&batch);
                out.extend(self.pump(store));
            }
            Frame::Ops { scope, ops, to } => {
                for o in ops {
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
                if store.digest(&scope) != digest {
                    // Something diverged: re-pull the whole scope. Ops are idempotent.
                    self.repairs += 1;
                    store.reset_scope(&scope);
                    out.push(Frame::Pull { scope, after: 0 });
                }
            }
            Frame::Scope { add, remove } => {
                let mut s: BTreeSet<String> = store.scopes().into_iter().collect();
                for r in &remove {
                    s.remove(r);
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
}

impl MemStore {
    pub fn add_local(&mut self, op: Op) {
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
        self.ops.insert(op.id.clone(), op);
    }
    fn ack(&mut self, id: &str, stamp: &Stamp) {
        if let Some(o) = self.ops.get_mut(id) {
            stamp.apply(o);
            self.dirty.insert(id.into());
        }
        if self.restoring.iter().any(|x| x == id) {
            self.restoring.retain(|x| x != id);
            self.meta_dirty = true;
        }
    }
    fn reject(&mut self, id: &str, error: AckError) {
        if self.ops.get(id).is_some_and(|o| o.seq.is_none()) {
            self.rejected.insert(id.into(), error);
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
}

// ─── reference server ────────────────────────────────────────────────────────

#[derive(Clone, Debug)]
struct Conn {
    account: String,
    sample: ClockSample,
    /// Scopes the connection receives live ops for.
    scopes: BTreeSet<String>,
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

    fn scope_ops(&self, scope: &str, after: i64) -> impl Iterator<Item = &Op> {
        self.log.iter().filter(move |o| o.scope == scope && o.seq.unwrap_or(0) > after)
    }

    pub fn max_seq(&self, scope: &str) -> i64 {
        self.log.iter().rev().find(|o| o.scope == scope).and_then(|o| o.seq).unwrap_or(0)
    }

    pub fn digest(&self, scope: &str) -> Digest {
        Digest::of(self.scope_ops(scope, 0).map(|o| o.id.as_str()))
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

    fn catch_up(&self, scope: &str, after: i64) -> Vec<Frame> {
        let ops: Vec<Op> = self.scope_ops(scope, after).cloned().collect();
        let to = self.max_seq(scope);
        let mut out: Vec<Frame> = ops
            .chunks(PAGE_OPS)
            .map(|c| Frame::Ops {
                scope: scope.into(),
                ops: c.to_vec(),
                to: c.last().and_then(|o| o.seq).unwrap_or(to),
            })
            .collect();
        out.push(Frame::Caught { scope: scope.into(), to, digest: self.digest(scope) });
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
        if let Err(e) = op::validate(&o) {
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
            Frame::Hello { epoch, cursors, clock, .. } => {
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
                self.conns.insert(device.into(), Conn { account, sample, scopes: scopes.iter().cloned().collect() });
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
                        out.extend(self.catch_up(s, after).into_iter().map(|f| (device.to_string(), f)));
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
                        if d != device && c.scopes.contains(&o.scope) {
                            let seq = o.seq.unwrap_or(0);
                            out.push((d.clone(), Frame::Ops { scope: o.scope.clone(), ops: vec![o.clone()], to: seq }));
                        }
                    }
                }
            }
            Frame::Pull { scope, after } => {
                if self.conns.get(device).is_some_and(|c| c.scopes.contains(&scope)) {
                    out.extend(self.catch_up(&scope, after).into_iter().map(|f| (device.to_string(), f)));
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

    #[test]
    fn frames_are_tagged() {
        let f = Frame::Pull { scope: "space:x".into(), after: 3 };
        assert_eq!(serde_json::to_string(&f).unwrap(), r#"{"t":"pull","scope":"space:x","after":3}"#);
    }
}
