//! Stable sorts that cost the web core one sort instantiation instead of one per call site.
//!
//! Every `sort_by` over a new element type or closure is its own copy of the standard library's
//! sort, ~4 KB of wasm each (R25, NOTES.md). On wasm these sort a list of indices through one
//! shared, non-generic comparator call, then move the elements into place. Elsewhere (the
//! server, Android) they are the standard `sort_by`: same order, no indirection on hot paths.
//! Both are stable, so the result is the same everywhere.

use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};

/// `v.sort_by(cmp)`.
pub fn by<T>(v: &mut [T], cmp: impl FnMut(&T, &T) -> Ordering) {
    #[cfg(target_arch = "wasm32")]
    by_index(v, cmp);
    #[cfg(not(target_arch = "wasm32"))]
    v.sort_by(cmp);
}

/// `v.sort_by_key(key)`.
pub fn by_key<T, K: Ord>(v: &mut [T], mut key: impl FnMut(&T) -> K) {
    by(v, |a, b| key(a).cmp(&key(b)));
}

/// `v.sort()`.
pub fn ord<T: Ord>(v: &mut [T]) {
    by(v, T::cmp);
}

/// `iter.collect::<BTreeMap<_, _>>()`. `collect` sorts first, one more sort per key and value
/// type; on wasm this inserts one by one instead (the last value for a key wins either way).
pub fn map<K: Ord, V>(iter: impl IntoIterator<Item = (K, V)>) -> BTreeMap<K, V> {
    #[cfg(target_arch = "wasm32")]
    {
        let mut out = BTreeMap::new();
        for (k, v) in iter {
            out.insert(k, v);
        }
        out
    }
    #[cfg(not(target_arch = "wasm32"))]
    iter.into_iter().collect()
}

/// `iter.collect::<BTreeSet<_>>()`, the same way as [`map`].
pub fn set<T: Ord>(iter: impl IntoIterator<Item = T>) -> BTreeSet<T> {
    #[cfg(target_arch = "wasm32")]
    {
        let mut out = BTreeSet::new();
        for t in iter {
            out.insert(t);
        }
        out
    }
    #[cfg(not(target_arch = "wasm32"))]
    iter.into_iter().collect()
}

#[cfg_attr(not(target_arch = "wasm32"), allow(dead_code))]
fn by_index<T>(v: &mut [T], mut cmp: impl FnMut(&T, &T) -> Ordering) {
    if v.len() < 2 {
        return;
    }
    let mut order: Vec<u32> = (0..v.len() as u32).collect();
    sort_indices(&mut order, &mut |a, b| cmp(&v[a as usize], &v[b as usize]));
    // order[i] is where the element for position i is now: follow each cycle once
    for start in 0..order.len() {
        let mut at = start;
        while order[at] as usize != at {
            let next = order[at] as usize;
            order[at] = at as u32;
            if next == start {
                break;
            }
            v.swap(at, next);
            at = next;
        }
    }
}

#[cfg_attr(not(target_arch = "wasm32"), allow(dead_code))]
#[inline(never)]
fn sort_indices(order: &mut [u32], cmp: &mut dyn FnMut(u32, u32) -> Ordering) {
    order.sort_by(|a, b| cmp(*a, *b));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_index_sort_is_the_standard_stable_sort() {
        let mut seed = 11u64;
        let mut rand = |n: u64| {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            (seed >> 33) % n
        };
        for len in 0..200 {
            let v: Vec<(u64, usize)> = (0..len).map(|i| (rand(8), i)).collect();
            let mut a = v.clone();
            let mut b = v.clone();
            a.sort_by_key(|x| x.0);
            by_index(&mut b, |x, y| x.0.cmp(&y.0));
            assert_eq!(a, b, "len {len}");
            let mut c = v.clone();
            by_key(&mut c, |x| std::cmp::Reverse(x.0));
            let mut d = v;
            d.sort_by_key(|x| std::cmp::Reverse(x.0));
            assert_eq!(c, d);
        }
    }
}
