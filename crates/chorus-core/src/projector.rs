//! Incremental projection (SPEC.md §9: "send message → rendered locally ≤ 50 ms").
//!
//! [`model::project`] is the obvious reference: fold every op, every time. That costs hundreds of
//! milliseconds on a large history, so replicas keep a [`Projector`] instead. It is exact by
//! construction, not by cleverness:
//!
//! - every piece of state is owned by one **key**: a row, a set element, a read-state row, or an
//!   account's front;
//! - an op's keys are found by running the op, through the same [`model::apply_op`], on an empty
//!   scratch projection and seeing what it touched;
//! - when an op is added, replaced (a local copy superseded by the server-stamped one) or removed
//!   (rejected), each of its keys is recomputed from *that key's* ops alone, in the same
//!   `(hlc, id)` order the reference uses.
//!
//! Because every op affects state only through its own keys, the result equals
//! `model::project` over the same ops; `tests/projector_props.rs` checks that on random histories.
//! [`Projector::take_delta`] reports what changed, so the UI doesn't re-read everything either.
//!
//! **Opening from a snapshot** (CLIENTS.md §4.3, D-070): a big replica takes seconds to project,
//! so the app keeps the last projection it showed (with [`Projector::digest`], which identifies
//! the exact op copies it came from) and opens from that. The ops are then indexed a slice at a
//! time ([`Projector::index_op`]) and [`Projector::adopt`] checks the digest: if it matches, the
//! projector goes on from the snapshot without projecting anything — its own projection stays
//! *partial*, holding only keys recomputed since, which is all [`Projector::take_delta`] reads.
//! A mismatch (ops saved after the snapshot was) projects everything, as before.

use std::collections::{BTreeMap, BTreeSet, HashMap};

use serde::Serialize;
use serde_json::Value;

use crate::front::{self, FrontOp};
use crate::hlc::Hlc;
use crate::model::{self, Projection, Reads, Row};
use crate::op::Op;
use crate::sync::Digest;

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
enum Key {
    Row(String, String),
    Set(String, String),
    Read(String),
    Front(String),
}

/// Two copies of one op id differ only in the server's stamp (no allocation: this runs per op).
fn same_stamp(a: &Op, b: &Op) -> bool {
    a.seq == b.seq
        && a.occurred_at == b.occurred_at
        && a.received_at == b.received_at
        && a.account_id == b.account_id
        && a.device_id == b.device_id
}

/// What identifies one copy of an op in [`Projector::digest`]: its id and the server's stamp.
fn stamp_key(o: &Op) -> String {
    format!(
        "{}|{:?}|{:?}|{:?}|{}|{}",
        o.id,
        o.seq,
        o.occurred_at,
        o.received_at,
        o.account_id.as_deref().unwrap_or(""),
        o.device_id.as_deref().unwrap_or("")
    )
}

/// The keys an op touches, and whether it counts as opaque.
fn keys_of(o: &Op) -> (Vec<Key>, bool) {
    let mut p = Projection::default();
    let mut fronts: BTreeMap<String, Vec<FrontOp>> = BTreeMap::new();
    let mut reads: Reads = BTreeMap::new();
    model::apply_op(&mut p, o, &mut fronts, &mut reads);
    let mut keys = Vec::new();
    for (table, rows) in &p.rows {
        for id in rows.keys() {
            keys.push(Key::Row(table.clone(), id.clone()));
        }
    }
    for (table, elems) in &p.sets {
        for k in elems.keys() {
            keys.push(Key::Set(table.clone(), k.clone()));
        }
    }
    keys.extend(reads.into_keys().map(Key::Read));
    keys.extend(fronts.into_keys().map(Key::Front));
    (keys, p.opaque > 0)
}

/// Changes since the last [`Projector::take_delta`]: `null` means "gone".
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct Delta {
    pub rows: BTreeMap<String, BTreeMap<String, Option<Row>>>,
    pub sets: BTreeMap<String, BTreeMap<String, Option<bool>>>,
    pub fronts: BTreeMap<String, Option<front::FoldResult>>,
    pub reviews: BTreeMap<String, Option<Vec<front::Review>>>,
    pub opaque: usize,
    /// True when the caller should re-read the whole projection instead (first call).
    pub full: bool,
}

#[derive(Default)]
pub struct Projector {
    proj: Projection,
    /// best copy of each op, by id
    ops: HashMap<String, Op>,
    op_keys: HashMap<String, Vec<Key>>,
    by_key: HashMap<Key, BTreeSet<(Hlc, String)>>,
    opaque: BTreeSet<String>,
    changed: BTreeSet<Key>,
    delivered_once: bool,
    /// Of the op copies projected (see [`stamp_key`]).
    digest: Digest,
    /// `proj` holds only keys recomputed since adopting a snapshot (module docs).
    partial: bool,
}

impl Projector {
    pub fn new() -> Projector {
        Projector::default()
    }

    pub fn projection(&self) -> &Projection {
        &self.proj
    }

    pub fn len(&self) -> usize {
        self.ops.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ops.is_empty()
    }

    /// Identifies the exact op copies the projection reflects (saved with a snapshot of it).
    pub fn digest(&self) -> Digest {
        self.digest
    }

    /// Index one op for later updates without projecting it (opening from a snapshot). Call once
    /// per visible op, then [`Projector::adopt`].
    pub fn index_op(&mut self, o: &Op) {
        if self.ops.contains_key(&o.id) {
            return;
        }
        let (keys, opaque) = keys_of(o);
        if opaque {
            self.opaque.insert(o.id.clone());
        }
        for k in &keys {
            self.by_key.entry(k.clone()).or_default().insert((o.hlc, o.id.clone()));
        }
        self.op_keys.insert(o.id.clone(), keys);
        self.digest.add(&stamp_key(o));
        self.ops.insert(o.id.clone(), o.clone());
    }

    /// Everything is indexed; the reader holds a snapshot made at `snapshot` (a [`Projector::digest`]),
    /// or nothing. `fresh`: ops created since the snapshot's ops were loaded, which the reader
    /// hasn't seen. If what was indexed, minus `fresh`, is exactly the snapshot's ops, continue
    /// from it (fresh ops' keys come as the next delta) and return true; otherwise project
    /// everything, and the next delta says `full`.
    pub fn adopt(&mut self, snapshot: Option<Digest>, fresh: &BTreeSet<String>) -> bool {
        let mut d = self.digest;
        for id in fresh {
            if let Some(o) = self.ops.get(id) {
                d.remove(&stamp_key(o));
            }
        }
        self.proj.opaque = self.opaque.len();
        if snapshot == Some(d) {
            self.partial = true;
            self.delivered_once = true;
            let dirty: BTreeSet<Key> =
                crate::sort::set(fresh.iter().filter_map(|id| self.op_keys.get(id)).flatten().cloned());
            self.changed.clear();
            self.recompute(&dirty);
            self.changed.extend(dirty);
            return true;
        }
        self.partial = true; // nothing projected yet
        self.materialize();
        self.delivered_once = false;
        self.changed.clear();
        false
    }

    /// Make a partial projection whole (only needed if someone reads all of it).
    pub fn materialize(&mut self) {
        if self.partial {
            let ops = model::dedupe(self.ops.values());
            let opaque = self.opaque.len();
            self.proj = model::project(ops.iter().copied());
            self.proj.opaque = opaque;
            self.partial = false;
        }
    }

    /// Bring the projection to exactly `visible` (the replica's confirmed + pending ops, minus
    /// rejected ones). Cheap when little changed: one hash lookup per op.
    pub fn sync<'a>(&mut self, visible: impl IntoIterator<Item = &'a Op>) {
        if self.ops.is_empty() {
            return self.load(visible);
        }
        let mut seen: std::collections::HashSet<&str> = std::collections::HashSet::new();
        let mut dirty: BTreeSet<Key> = BTreeSet::new();
        let mut upserts: Vec<&Op> = Vec::new();
        // how many of our ops are still visible; if all are, nothing was removed
        let mut still = 0usize;
        for o in visible {
            if !seen.insert(o.id.as_str()) {
                // two copies in one batch: keep the stamped one, like model::dedupe
                if o.seq.is_some() {
                    upserts.push(o);
                }
                continue;
            }
            match self.ops.get(&o.id) {
                Some(cur) => {
                    still += 1;
                    // the store holds one copy per id and it is authoritative: a stamped op can
                    // become unstamped again (demoted for a server restore), and that matters
                    if !same_stamp(cur, o) {
                        upserts.push(o);
                    }
                }
                None => upserts.push(o),
            }
        }
        if still < self.ops.len() {
            let gone: Vec<String> = self.ops.keys().filter(|id| !seen.contains(id.as_str())).cloned().collect();
            for id in gone {
                self.remove_op(&id, &mut dirty);
            }
        }
        for o in upserts {
            self.insert_op(o.clone(), &mut dirty);
        }
        self.recompute(&dirty);
        self.proj.opaque = self.opaque.len();
        self.changed.extend(dirty);
    }

    /// Apply changes for just these op ids: `current(id)` is the op's visible copy now, or
    /// `None` if it's gone (rejected). Use after the first [`Projector::sync`].
    pub fn sync_ids<'a>(&mut self, ids: impl IntoIterator<Item = String>, current: impl Fn(&str) -> Option<&'a Op>) {
        let mut dirty: BTreeSet<Key> = BTreeSet::new();
        for id in ids {
            match current(&id) {
                Some(o) => match self.ops.get(&id) {
                    Some(cur) if same_stamp(cur, o) => {}
                    _ => self.insert_op(o.clone(), &mut dirty),
                },
                None => self.remove_op(&id, &mut dirty),
            }
        }
        self.recompute(&dirty);
        self.proj.opaque = self.opaque.len();
        self.changed.extend(dirty);
    }

    /// First load: the reference projection in one pass, plus the key index for later updates.
    fn load<'a>(&mut self, visible: impl IntoIterator<Item = &'a Op>) {
        let ops = model::dedupe(visible);
        self.proj = model::project(ops.iter().copied());
        for o in ops {
            self.index_op(o);
            // a reader that already saw an (empty) projection needs these as a delta
            self.changed.extend(self.op_keys[&o.id].iter().cloned());
        }
        self.proj.opaque = self.opaque.len();
    }

    fn insert_op(&mut self, o: Op, dirty: &mut BTreeSet<Key>) {
        self.remove_op(&o.id, dirty);
        let (keys, opaque) = keys_of(&o);
        if opaque {
            self.opaque.insert(o.id.clone());
        }
        for k in &keys {
            self.by_key.entry(k.clone()).or_default().insert((o.hlc, o.id.clone()));
            dirty.insert(k.clone());
        }
        self.op_keys.insert(o.id.clone(), keys);
        self.digest.add(&stamp_key(&o));
        self.ops.insert(o.id.clone(), o);
    }

    fn remove_op(&mut self, id: &str, dirty: &mut BTreeSet<Key>) {
        let Some(o) = self.ops.remove(id) else { return };
        self.digest.remove(&stamp_key(&o));
        self.opaque.remove(id);
        for k in self.op_keys.remove(id).unwrap_or_default() {
            if let Some(set) = self.by_key.get_mut(&k) {
                set.remove(&(o.hlc, o.id.clone()));
                if set.is_empty() {
                    self.by_key.remove(&k);
                }
            }
            dirty.insert(k);
        }
    }

    /// Recompute each key from its own ops, in reference order.
    fn recompute(&mut self, keys: &BTreeSet<Key>) {
        for key in keys {
            let ids: Vec<&Op> = self
                .by_key
                .get(key)
                .map(|s| s.iter().filter_map(|(_, id)| self.ops.get(id)).collect())
                .unwrap_or_default();
            let mut p = Projection::default();
            let mut fronts: BTreeMap<String, Vec<FrontOp>> = BTreeMap::new();
            let mut reads: Reads = BTreeMap::new();
            for o in &ids {
                model::apply_op(&mut p, o, &mut fronts, &mut reads);
            }
            match key {
                Key::Row(table, id) => {
                    let row = p.rows.get_mut(table).and_then(|t| t.remove(id));
                    let t = self.proj.rows.entry(table.clone()).or_default();
                    match row {
                        Some(r) => {
                            t.insert(id.clone(), r);
                        }
                        None => {
                            t.remove(id);
                        }
                    }
                    if t.is_empty() {
                        self.proj.rows.remove(table);
                    }
                }
                Key::Set(table, k) => {
                    let v = p.sets.get(table).and_then(|s| s.get(k)).copied();
                    let t = self.proj.sets.entry(table.clone()).or_default();
                    match v {
                        Some(b) => {
                            t.insert(k.clone(), b);
                        }
                        None => {
                            t.remove(k);
                        }
                    }
                    if t.is_empty() {
                        self.proj.sets.remove(table);
                    }
                }
                Key::Read(k) => {
                    let row = reads.remove(k).and_then(|(marks, manual)| model::read_row(marks, manual));
                    let t = self.proj.rows.entry("read_state".into()).or_default();
                    match row {
                        Some(r) => {
                            t.insert(k.clone(), r);
                        }
                        None => {
                            t.remove(k);
                        }
                    }
                    if t.is_empty() {
                        self.proj.rows.remove("read_state");
                    }
                }
                Key::Front(acct) => {
                    let ops = fronts.remove(acct).unwrap_or_default();
                    if ops.is_empty() {
                        self.proj.fronts.remove(acct);
                        self.proj.reviews.remove(acct);
                        continue;
                    }
                    let folded = front::fold(&ops);
                    let reviews = front::reviews(&ops, &folded, front::DEFAULT_REVIEW_WINDOW_MS);
                    if reviews.is_empty() {
                        self.proj.reviews.remove(acct);
                    } else {
                        self.proj.reviews.insert(acct.clone(), reviews);
                    }
                    self.proj.fronts.insert(acct.clone(), folded);
                }
            }
        }
    }

    /// What changed since the last call. The first call says `full` (read everything once).
    pub fn take_delta(&mut self) -> Delta {
        let mut d = Delta { opaque: self.proj.opaque, ..Default::default() };
        if !self.delivered_once {
            self.delivered_once = true;
            self.changed.clear();
            d.full = true;
            return d;
        }
        for key in std::mem::take(&mut self.changed) {
            match key {
                Key::Row(t, id) => {
                    let row = self.proj.row(&t, &id).cloned();
                    d.rows.entry(t).or_default().insert(id, row);
                }
                Key::Read(k) => {
                    let row = self.proj.row("read_state", &k).cloned();
                    d.rows.entry("read_state".into()).or_default().insert(k, row);
                }
                Key::Set(t, k) => {
                    let v = self.proj.sets.get(&t).and_then(|s| s.get(&k)).copied();
                    d.sets.entry(t).or_default().insert(k, v);
                }
                Key::Front(a) => {
                    d.fronts.insert(a.clone(), self.proj.fronts.get(&a).cloned());
                    d.reviews.insert(a.clone(), self.proj.reviews.get(&a).cloned());
                }
            }
        }
        d
    }
}

/// JSON for [`Delta`] (rows serialize like the projection's own).
pub fn delta_json(d: &Delta) -> Value {
    serde_json::to_value(d).unwrap_or(Value::Null)
}
