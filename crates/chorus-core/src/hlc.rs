//! Hybrid logical clock (SYNC.md §3.1).
//!
//! Encoded as `"<physical ms, 12 hex>-<counter, 4 hex>-<node, 8 hex>"`. Fixed width and
//! lowercase, so plain string comparison equals clock order; the node makes the order total.

use std::fmt;
use std::str::FromStr;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// Remote clocks further ahead than this are treated as broken (SYNC.md §3.1 guard).
pub const MAX_AHEAD_MS: u64 = 24 * 60 * 60 * 1000;
/// How far past `now` a broken remote clock may still push us.
pub const BROKEN_CLOCK_CAP_MS: u64 = 60 * 1000;
const MAX_PT: u64 = (1 << 48) - 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct Hlc {
    pub pt: u64,
    pub c: u16,
    pub node: u32,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
#[error("invalid HLC string: {0:?}")]
pub struct HlcParseError(pub String);

impl Hlc {
    pub const ZERO: Hlc = Hlc { pt: 0, c: 0, node: 0 };

    pub fn new(pt: u64, c: u16, node: u32) -> Self {
        Hlc { pt: pt.min(MAX_PT), c, node }
    }
}

impl fmt::Display for Hlc {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:012x}-{:04x}-{:08x}", self.pt, self.c, self.node)
    }
}

impl FromStr for Hlc {
    type Err = HlcParseError;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let err = || HlcParseError(s.to_string());
        let b = s.as_bytes();
        if b.len() != 26 || b[12] != b'-' || b[17] != b'-' {
            return Err(err());
        }
        let hex = |r: &str| -> Result<u64, HlcParseError> {
            if r.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)) {
                u64::from_str_radix(r, 16).map_err(|_| err())
            } else {
                Err(err())
            }
        };
        Ok(Hlc { pt: hex(&s[0..12])?, c: hex(&s[13..17])? as u16, node: hex(&s[18..26])? as u32 })
    }
}

impl Serialize for Hlc {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.collect_str(self)
    }
}

impl<'de> Deserialize<'de> for Hlc {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let s = String::deserialize(d)?;
        s.parse().map_err(serde::de::Error::custom)
    }
}

/// A device's clock state. Persist `last` between runs so clocks never go backwards.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HlcClock {
    pub last: Hlc,
    pub node: u32,
}

impl HlcClock {
    pub fn new(node: u32) -> Self {
        HlcClock { last: Hlc { pt: 0, c: 0, node }, node }
    }

    pub fn resume(node: u32, last: Hlc) -> Self {
        HlcClock { last: Hlc { node, ..last }, node }
    }

    /// Clock for a new local event.
    pub fn tick(&mut self, now_ms: u64) -> Hlc {
        let now = now_ms.min(MAX_PT);
        let (pt, c) = if now > self.last.pt { (now, 0) } else { bump(self.last.pt, self.last.c) };
        self.last = Hlc { pt, c, node: self.node };
        self.last
    }

    /// Merge a clock seen on a remote op. Returns the new local clock.
    pub fn observe(&mut self, remote: Hlc, now_ms: u64) -> Hlc {
        let now = now_ms.min(MAX_PT);
        let remote = if remote.pt > now.saturating_add(MAX_AHEAD_MS) {
            // A broken clock must not drag everyone into the future.
            Hlc { pt: now + BROKEN_CLOCK_CAP_MS, c: 0, node: remote.node }
        } else {
            remote
        };
        let l = self.last;
        let pt = l.pt.max(remote.pt).max(now);
        let (pt, c) = if pt == l.pt && pt == remote.pt {
            bump(pt, l.c.max(remote.c))
        } else if pt == l.pt {
            bump(pt, l.c)
        } else if pt == remote.pt {
            bump(pt, remote.c)
        } else {
            (pt, 0)
        };
        self.last = Hlc { pt, c, node: self.node };
        self.last
    }
}

fn bump(pt: u64, c: u16) -> (u64, u16) {
    match c.checked_add(1) {
        Some(c) => (pt, c),
        None => ((pt + 1).min(MAX_PT), 0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn format_and_parse() {
        let h = Hlc::new(0x01a0c27d1e3f, 3, 0xa1b2c3d4);
        assert_eq!(h.to_string(), "01a0c27d1e3f-0003-a1b2c3d4");
        assert_eq!("01a0c27d1e3f-0003-a1b2c3d4".parse::<Hlc>(), Ok(h));
        assert!("01A0C27D1E3F-0003-a1b2c3d4".parse::<Hlc>().is_err());
        assert!("01a0c27d1e3f-0003".parse::<Hlc>().is_err());
        assert!("01a0c27d1e3f_0003_a1b2c3d4".parse::<Hlc>().is_err());
    }

    #[test]
    fn serde_as_string() {
        let h = Hlc::new(5, 1, 2);
        let j = serde_json::to_string(&h).unwrap();
        assert_eq!(j, "\"000000000005-0001-00000002\"");
        assert_eq!(serde_json::from_str::<Hlc>(&j).unwrap(), h);
    }

    #[test]
    fn tick_is_monotonic_when_wall_clock_goes_back() {
        let mut c = HlcClock::new(1);
        let a = c.tick(1000);
        let b = c.tick(900);
        let d = c.tick(900);
        assert!(a < b && b < d);
        assert_eq!(b.pt, 1000);
    }

    #[test]
    fn observe_moves_past_remote() {
        let mut c = HlcClock::new(1);
        c.tick(1000);
        let r = Hlc::new(5000, 7, 2);
        let got = c.observe(r, 1200);
        assert!(got > r);
        assert_eq!((got.pt, got.c), (5000, 8));
        assert!(c.tick(1300) > got);
    }

    #[test]
    fn broken_remote_clock_is_capped() {
        let mut c = HlcClock::new(1);
        let now = 1_790_000_000_000;
        let far = Hlc::new(now + 10 * MAX_AHEAD_MS, 0, 2);
        let got = c.observe(far, now);
        assert!(got.pt <= now + BROKEN_CLOCK_CAP_MS + 1);
    }

    #[test]
    fn counter_overflow_rolls_physical_time() {
        let mut c = HlcClock::resume(1, Hlc::new(10, u16::MAX, 1));
        let h = c.tick(5);
        assert_eq!((h.pt, h.c), (11, 0));
    }

    proptest! {
        #[test]
        fn string_order_equals_clock_order(a in (0u64..1<<48, any::<u16>(), any::<u32>()),
                                           b in (0u64..1<<48, any::<u16>(), any::<u32>())) {
            let x = Hlc::new(a.0, a.1, a.2);
            let y = Hlc::new(b.0, b.1, b.2);
            prop_assert_eq!(x.cmp(&y), x.to_string().cmp(&y.to_string()));
            prop_assert_eq!(x.to_string().parse::<Hlc>().unwrap(), x);
        }

        #[test]
        fn clocks_never_go_backwards(events in proptest::collection::vec((any::<bool>(), 0u64..10_000, 0u64..10_000, any::<u16>()), 1..200)) {
            let mut c = HlcClock::new(9);
            let mut prev = Hlc::ZERO;
            for (is_remote, now, rpt, rc) in events {
                let h = if is_remote { c.observe(Hlc::new(rpt, rc, 3), now) } else { c.tick(now) };
                prop_assert!(h > prev);
                prev = h;
            }
        }
    }
}
