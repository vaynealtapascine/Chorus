//! Identifiers (DATA_MODEL.md §1).
//!
//! Object ids are UUIDv7 strings generated on the device that creates the object. Core never
//! reads the clock or an RNG itself: callers pass both in.

use uuid::{Builder, Uuid};

/// A new UUIDv7 as a lowercase hyphenated string.
pub fn new_id(unix_ms: u64, random: [u8; 10]) -> String {
    Builder::from_unix_timestamp_millis(unix_ms, &random)
        .into_uuid()
        .hyphenated()
        .to_string()
}

/// True if `s` is a lowercase hyphenated UUID (any version). Ops from older importers may carry
/// v5 ids, so the version is not checked.
pub fn is_valid_id(s: &str) -> bool {
    s.len() == 36
        && s.bytes().all(|b| b == b'-' || b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        && Uuid::try_parse(s).is_ok()
}

/// Milliseconds encoded in a UUIDv7, if it is one.
pub fn id_time_ms(s: &str) -> Option<u64> {
    let u = Uuid::try_parse(s).ok()?;
    if u.get_version_num() != 7 {
        return None;
    }
    let (secs, nanos) = u.get_timestamp()?.to_unix();
    Some(secs * 1000 + u64::from(nanos) / 1_000_000)
}

/// A member's short id: 7 lowercase letters (D-049). `random` supplies one byte per letter;
/// rejection sampling would need more bytes, so the tiny modulo bias (256 % 26) is accepted —
/// short ids are for convenience, not security.
pub fn member_short_id(random: [u8; 7]) -> String {
    random.iter().map(|b| (b'a' + b % 26) as char).collect()
}

/// A device's short id: 8 lowercase hex chars, used as the node part of HLCs.
pub fn device_short_id(random: [u8; 4]) -> String {
    format!("{:08x}", u32::from_be_bytes(random))
}

pub fn is_valid_device_short_id(s: &str) -> bool {
    s.len() == 8 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn v7_roundtrips_time_and_sorts_by_time() {
        let a = new_id(1_790_000_000_000, [0xff; 10]);
        let b = new_id(1_790_000_000_001, [0x00; 10]);
        assert!(is_valid_id(&a));
        assert_eq!(id_time_ms(&a), Some(1_790_000_000_000));
        assert!(a < b, "string order follows time");
    }

    #[test]
    fn rejects_uppercase_and_garbage() {
        let a = new_id(1, [0xab; 10]);
        assert!(!is_valid_id(&a.to_uppercase()));
        assert!(!is_valid_id("not-an-id"));
        assert!(!is_valid_id(""));
    }

    #[test]
    fn short_ids() {
        let s = member_short_id([0, 25, 26, 51, 255, 100, 7]);
        assert_eq!(s, "azazvwh");
        assert_eq!(device_short_id([0xa1, 0xb2, 0xc3, 0xd4]), "a1b2c3d4");
        assert!(is_valid_device_short_id("a1b2c3d4"));
        assert!(!is_valid_device_short_id("A1B2C3D4"));
    }
}
