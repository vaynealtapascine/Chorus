//! Last-writer-wins merge helpers (SYNC.md §5.1–5.2).
//!
//! Storage-agnostic: callers hand in the row's current `clocks` map and get back which fields to
//! write. The server applies the result to SQLite, clients to Room/IndexedDB, and the reference
//! model (`model.rs`) to memory.

use serde_json::{Map, Value};

use crate::hlc::Hlc;

/// A row's per-field clocks, stored as JSON `{"field": "<hlc>"}` in the `clocks` column.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Clocks(pub std::collections::BTreeMap<String, Hlc>);

impl Clocks {
    pub fn from_json(v: &Value) -> Clocks {
        let mut m = std::collections::BTreeMap::new();
        if let Some(obj) = v.as_object() {
            for (k, v) in obj {
                if let Some(h) = v.as_str().and_then(|s| s.parse().ok()) {
                    m.insert(k.clone(), h);
                }
            }
        }
        Clocks(m)
    }

    pub fn to_json(&self) -> Value {
        Value::Object(self.0.iter().map(|(k, h)| (k.clone(), Value::String(h.to_string()))).collect())
    }

    pub fn get(&self, field: &str) -> Option<Hlc> {
        self.0.get(field).copied()
    }

    /// True if a write at `hlc` beats what the field holds.
    pub fn wins(&self, field: &str, hlc: Hlc) -> bool {
        self.get(field).is_none_or(|cur| hlc > cur)
    }
}

/// Apply a field map written at `hlc`. Returns the fields that won (to be written) and updates
/// `clocks`. Fields that lost are dropped — they remain in the op log.
pub fn apply_fields(clocks: &mut Clocks, hlc: Hlc, fields: &Map<String, Value>) -> Map<String, Value> {
    let mut won = Map::new();
    for (k, v) in fields {
        if clocks.wins(k, hlc) {
            clocks.0.insert(k.clone(), hlc);
            won.insert(k.clone(), v.clone());
        }
    }
    won
}

/// Apply one field.
pub fn apply_field(clocks: &mut Clocks, hlc: Hlc, field: &str, value: Value) -> Option<Value> {
    let mut m = Map::new();
    m.insert(field.to_string(), value);
    apply_fields(clocks, hlc, &m).remove(field)
}

/// State of one element of an LWW element set (group membership, reactions, …).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct SetElem {
    pub added: Option<Hlc>,
    pub removed: Option<Hlc>,
}

impl SetElem {
    pub fn add(&mut self, hlc: Hlc) {
        if self.added.is_none_or(|a| hlc > a) {
            self.added = Some(hlc);
        }
    }

    pub fn remove(&mut self, hlc: Hlc) {
        if self.removed.is_none_or(|r| hlc > r) {
            self.removed = Some(hlc);
        }
    }

    /// Present iff added after the latest removal (same rule as the `is_present` SQL columns).
    pub fn is_present(&self) -> bool {
        match (self.added, self.removed) {
            (Some(a), Some(r)) => a > r,
            (Some(_), None) => true,
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use serde_json::json;

    fn h(pt: u64, node: u32) -> Hlc {
        Hlc::new(pt, 0, node)
    }

    fn obj(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }

    #[test]
    fn different_fields_both_survive_same_field_later_wins() {
        let mut c = Clocks::default();
        let a = apply_fields(&mut c, h(10, 1), &obj(json!({"name": "Kai", "color": "#111111"})));
        assert_eq!(a.len(), 2);
        let b = apply_fields(&mut c, h(5, 2), &obj(json!({"name": "Old", "pronouns": "they"})));
        assert_eq!(b, obj(json!({"pronouns": "they"})));
        let d = apply_fields(&mut c, h(11, 2), &obj(json!({"name": "Kai R"})));
        assert_eq!(d, obj(json!({"name": "Kai R"})));
    }

    #[test]
    fn clocks_json_roundtrip() {
        let mut c = Clocks::default();
        apply_field(&mut c, h(3, 4), "name", json!("x"));
        assert_eq!(Clocks::from_json(&c.to_json()), c);
    }

    #[test]
    fn set_elem_add_remove() {
        let mut e = SetElem::default();
        assert!(!e.is_present());
        e.add(h(1, 1));
        assert!(e.is_present());
        e.remove(h(2, 1));
        assert!(!e.is_present());
        e.add(h(1, 9)); // older concurrent add loses
        assert!(!e.is_present());
        e.add(h(3, 1));
        assert!(e.is_present());
    }

    proptest! {
        /// Final field values are independent of the order writes are applied in.
        #[test]
        fn lww_is_order_independent(writes in proptest::collection::vec((0u64..50, 0u32..4, 0usize..3, 0i64..100), 1..40),
                                    seed in any::<u64>()) {
            let names = ["a", "b", "c"];
            let apply_all = |ws: &[(u64, u32, usize, i64)]| {
                let mut c = Clocks::default();
                let mut row = Map::new();
                for (pt, node, f, v) in ws {
                    if let Some(val) = apply_field(&mut c, h(*pt, *node), names[*f], json!(v)) {
                        row.insert(names[*f].into(), val);
                    }
                }
                row
            };
            let mut shuffled = writes.clone();
            // deterministic shuffle
            let mut s = seed;
            for i in (1..shuffled.len()).rev() {
                s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                shuffled.swap(i, (s >> 33) as usize % (i + 1));
            }
            // Equal HLCs with different values would be ambiguous; real HLCs are unique per op.
            let mut seen = std::collections::HashSet::new();
            prop_assume!(writes.iter().all(|(pt, n, f, _)| seen.insert((*pt, *n, *f))));
            prop_assert_eq!(apply_all(&writes), apply_all(&shuffled));
        }
    }
}
