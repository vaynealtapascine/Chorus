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

use crate::op::{self, Known, Op, OpError, Scope};
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
    pub error: Option<AckError>,
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
    /// Mark a pending op confirmed.
    fn ack(&mut self, id: &str, seq: i64, occurred_at: i64);
    /// Move a pending op to "sync issues" (it stays out of projections and digests).
    fn reject(&mut self, id: &str, error: AckError);
    /// Pending ops in creation order, excluding `skip`.
    fn pending(&self, skip: &BTreeSet<String>, limit: usize) -> Vec<Op>;
    /// Confirmed ops of a scope with seq > `after` (for reconcile re-push).
    fn confirmed_after(&self, scope: &str, after: i64) -> Vec<Op>;
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
            outbox: store.pending(&BTreeSet::new(), usize::MAX).len(),
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
        while self.in_flight.len() < MAX_IN_FLIGHT {
            let skip: BTreeSet<String> = self.in_flight.values().flatten().cloned().collect();
            let ops = store.pending(&skip, BATCH_OPS);
            if ops.is_empty() {
                break;
            }
            self.next_batch += 1;
            let batch = format!("b{}", self.next_batch);
            self.in_flight.insert(batch.clone(), ops.iter().map(|o| o.id.clone()).collect());
            out.push(Frame::Push { batch, ops });
        }
        out
    }

    pub fn on_frame(&mut self, store: &mut dyn ClientStore, frame: Frame) -> Vec<Frame> {
        let mut out = Vec::new();
        match frame {
            Frame::Welcome { epoch, offset_ms, scopes, max_seq, reconcile, .. } => {
                self.last_offset_ms = offset_ms;
                if reconcile {
                    // The server lost ops (restored from a backup). Seqs from the old epoch mean
                    // nothing now — they get reused — so re-push every confirmed op; the server
                    // keeps the ones it has (by id) and re-stamps the rest. Then pull from zero.
                    let _ = &max_seq;
                    let mut ops = Vec::new();
                    for s in &scopes {
                        ops.extend(store.confirmed_after(s, 0));
                        store.reset_scope(s);
                    }
                    for chunk in ops.chunks(BATCH_OPS) {
                        self.next_batch += 1;
                        let batch = format!("r{}", self.next_batch);
                        // not tracked as in-flight: their acks just restamp them
                        out.push(Frame::Push { batch, ops: chunk.to_vec() });
                    }
                    for s in &scopes {
                        out.push(Frame::Pull { scope: s.clone(), after: 0 });
                    }
                }
                store.set_epoch(&epoch);
                store.set_scopes(&scopes);
                self.state = ClientState::Live;
                out.extend(self.pump(store));
            }
            Frame::Ack { batch, results } => {
                for r in results {
                    match (r.seq, r.occurred_at, r.error) {
                        (Some(seq), Some(at), None) => store.ack(&r.id, seq, at),
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

/// In-memory client store (tests, simulator, and a reference for real implementations).
#[derive(Clone, Debug, Default)]
pub struct MemStore {
    pub epoch: Option<String>,
    pub cursors: BTreeMap<String, i64>,
    pub scopes: Vec<String>,
    /// All ops this device knows, by id. Pending ones have `seq == None`.
    pub ops: BTreeMap<String, Op>,
    /// Creation order of local ops.
    pub local_order: Vec<String>,
    pub rejected: BTreeMap<String, AckError>,
}

impl MemStore {
    pub fn add_local(&mut self, op: Op) {
        self.local_order.push(op.id.clone());
        self.ops.insert(op.id.clone(), op);
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
    }
    fn cursor(&self, scope: &str) -> i64 {
        *self.cursors.get(scope).unwrap_or(&0)
    }
    fn set_cursor(&mut self, scope: &str, seq: i64) {
        self.cursors.insert(scope.into(), seq);
    }
    fn scopes(&self) -> Vec<String> {
        self.scopes.clone()
    }
    fn set_scopes(&mut self, scopes: &[String]) {
        self.scopes = scopes.to_vec();
    }
    fn put_remote(&mut self, op: Op) {
        self.rejected.remove(&op.id);
        self.ops.insert(op.id.clone(), op);
    }
    fn ack(&mut self, id: &str, seq: i64, occurred_at: i64) {
        if let Some(o) = self.ops.get_mut(id) {
            o.seq = Some(seq);
            o.occurred_at = Some(occurred_at);
        }
    }
    fn reject(&mut self, id: &str, error: AckError) {
        if self.ops.get(id).is_some_and(|o| o.seq.is_none()) {
            self.rejected.insert(id.into(), error);
        }
    }
    fn pending(&self, skip: &BTreeSet<String>, limit: usize) -> Vec<Op> {
        self.local_order
            .iter()
            .filter(|id| !skip.contains(*id) && !self.rejected.contains_key(*id))
            .filter_map(|id| self.ops.get(id))
            .filter(|o| o.seq.is_none())
            .take(limit)
            .cloned()
            .collect()
    }
    fn confirmed_after(&self, scope: &str, after: i64) -> Vec<Op> {
        let mut v: Vec<Op> =
            self.ops.values().filter(|o| o.scope == scope && o.seq.is_some_and(|s| s > after)).cloned().collect();
        v.sort_by_key(|o| o.seq);
        v
    }
    fn digest(&self, scope: &str) -> Digest {
        Digest::of(self.ops.values().filter(|o| o.scope == scope && o.seq.is_some()).map(|o| o.id.as_str()))
    }
    fn reset_scope(&mut self, scope: &str) {
        self.cursors.insert(scope.into(), 0);
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
        self.conns.clear();
    }

    fn catch_up(&self, scope: &str, after: i64) -> Vec<Frame> {
        let ops: Vec<Op> = self.scope_ops(scope, after).cloned().collect();
        let to = self.max_seq(scope);
        let mut out: Vec<Frame> =
            ops.chunks(PAGE_OPS).map(|c| Frame::Ops { scope: scope.into(), ops: c.to_vec(), to: c.last().and_then(|o| o.seq).unwrap_or(to) }).collect();
        out.push(Frame::Caught { scope: scope.into(), to, digest: self.digest(scope) });
        out
    }

    /// Accept one op from a device. Idempotent by id.
    fn accept(&mut self, device: &str, mut o: Op, now: i64) -> (AckResult, Option<Op>) {
        let conn = self.conns.get(device).cloned();
        let Some(conn) = conn else {
            return (AckResult { id: o.id, seq: None, occurred_at: None, error: Some(AckError { code: "unauthenticated".into(), message: "no session".into(), retry: true }) }, None);
        };
        if let Some(&i) = self.by_id.get(&o.id) {
            let e = &self.log[i];
            return (AckResult { id: o.id, seq: e.seq, occurred_at: e.occurred_at, error: None }, None);
        }
        let reject = |id: String, e: OpError| AckResult {
            id,
            seq: None,
            occurred_at: None,
            error: Some(AckError { code: e.code().into(), message: e.to_string(), retry: false }),
        };
        match op::validate(&o) {
            Err(e) => {
                self.rejected.push((o.id.clone(), e.clone()));
                return (reject(o.id, e), None);
            }
            Ok(Known::Yes(_) | Known::Opaque) => {}
        }
        let allowed = Scope::parse(&o.scope).is_some() && self.access.get(&conn.account).is_some_and(|s| s.contains(&o.scope));
        if !allowed {
            let e = OpError::BadScope(format!("{} not writable", o.scope));
            self.rejected.push((o.id.clone(), e.clone()));
            let mut r = reject(o.id, e);
            if let Some(err) = r.error.as_mut() {
                err.code = "forbidden".into();
            }
            return (r, None);
        }
        let t = time::OpTime { device_at: o.device_at, mono: o.mono, boot_id: o.boot_id.clone(), time_source: o.time_source };
        let c = time::correct(&t, &conn.sample, now);
        o.seq = Some(self.log.len() as i64 + 1);
        o.account_id = Some(conn.account.clone());
        o.device_id = Some(device.into());
        o.received_at = Some(now);
        o.occurred_at = Some(c.occurred_at);
        self.by_id.insert(o.id.clone(), self.log.len());
        self.log.push(o.clone());
        (AckResult { id: o.id.clone(), seq: o.seq, occurred_at: o.occurred_at, error: None }, Some(o))
    }

    /// Handle a frame from `device`. Returns frames to deliver: `(device, frame)`.
    pub fn on_frame(&mut self, device: &str, frame: Frame, now: i64) -> Vec<(String, Frame)> {
        let mut out = Vec::new();
        match frame {
            Frame::Hello { epoch, cursors, clock, .. } => {
                let Some(account) = self.devices.get(device).cloned() else {
                    out.push((device.into(), Frame::Error { code: "unauthenticated".into(), message: "unknown device".into() }));
                    return out;
                };
                let scopes: Vec<String> = self.access.get(&account).map(|s| s.iter().cloned().collect()).unwrap_or_default();
                let offset = now - clock.wall;
                let sample = ClockSample { server_time: now, mono: clock.mono, boot_id: clock.boot_id.clone(), offset_ms: offset };
                let max_seq: BTreeMap<String, i64> = scopes.iter().map(|s| (s.clone(), self.max_seq(s))).collect();
                let reconcile = epoch.as_ref().is_some_and(|e| *e != self.epoch_str())
                    || cursors.iter().any(|(s, c)| *c > *max_seq.get(s).unwrap_or(&0));
                self.conns.insert(device.into(), Conn { account, sample, scopes: scopes.iter().cloned().collect() });
                out.push((
                    device.into(),
                    Frame::Welcome {
                        server_time: now,
                        epoch: self.epoch_str(),
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
            Frame::Push { batch, ops } => {
                if !self.conns.contains_key(device) {
                    return out;
                }
                let mut results = Vec::new();
                let mut fresh: Vec<Op> = Vec::new();
                for o in ops {
                    let (r, new) = self.accept(device, o, now);
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
