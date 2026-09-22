//! Human time for ops: `occurred_at` (SYNC.md §3.2).
//!
//! All times are i64 milliseconds since the Unix epoch, UTC.

use serde::{Deserialize, Serialize};

/// Offsets smaller than this are treated as "clock is fine" and not applied.
pub const NEGLIGIBLE_OFFSET_MS: i64 = 2_000;
/// Results further than this from `received_at` mean the clock was broken.
pub const SUSPECT_MS: i64 = 365 * 24 * 60 * 60 * 1000;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum TimeSource {
    /// The op happened "now" on the device.
    #[default]
    Auto,
    /// The user typed the time (e.g. "switched at 13:40"); taken as-is.
    User,
}

/// Clock sample taken on a connection (NTP-style exchange).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClockSample {
    /// Server time at the sample.
    pub server_time: i64,
    /// Device monotonic clock at the sample, if the platform has one that survives app restarts.
    pub mono: Option<i64>,
    pub boot_id: Option<String>,
    /// `server_time - device_wall_time`, estimated.
    pub offset_ms: i64,
}

/// `offset = t1 - (t0 + t2) / 2`: `t0` device send, `t1` server, `t2` device receive.
pub fn ntp_offset(t0: i64, t1: i64, t2: i64) -> i64 {
    t1 - (t0 + (t2 - t0) / 2)
}

/// Time fields an op carries from its device.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct OpTime {
    pub device_at: i64,
    pub mono: Option<i64>,
    pub boot_id: Option<String>,
    pub time_source: TimeSource,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Corrected {
    pub occurred_at: i64,
    pub suspect: bool,
}

/// Compute `occurred_at` for an op uploaded on a connection whose latest sample is `sample`.
pub fn correct(op: &OpTime, sample: &ClockSample, received_at: i64) -> Corrected {
    if op.time_source == TimeSource::User {
        return Corrected { occurred_at: op.device_at, suspect: false };
    }
    let same_boot = matches!((&op.boot_id, &sample.boot_id), (Some(a), Some(b)) if a == b);
    let estimate = match (op.mono, sample.mono) {
        (Some(op_mono), Some(sample_mono)) if same_boot && op_mono <= sample_mono => {
            sample.server_time - (sample_mono - op_mono)
        }
        _ if sample.offset_ms.abs() < NEGLIGIBLE_OFFSET_MS => op.device_at,
        _ => op.device_at + sample.offset_ms,
    };
    if (estimate - received_at).abs() > SUSPECT_MS {
        return Corrected { occurred_at: received_at, suspect: true };
    }
    Corrected { occurred_at: estimate.min(received_at), suspect: false }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(offset: i64, mono: Option<i64>, boot: Option<&str>) -> ClockSample {
        ClockSample { server_time: 1_000_000, mono, boot_id: boot.map(str::to_string), offset_ms: offset }
    }

    fn op(device_at: i64, mono: Option<i64>, boot: Option<&str>) -> OpTime {
        OpTime { device_at, mono, boot_id: boot.map(str::to_string), time_source: TimeSource::Auto }
    }

    #[test]
    fn ntp() {
        // device is 500 ms behind; 100 ms round trip
        assert_eq!(ntp_offset(1000, 1550, 1100), 500);
    }

    #[test]
    fn mono_same_boot_ignores_wall_clock() {
        // wall clock wildly wrong, but mono says the op was 60 s before the sample
        let c = correct(&op(5, Some(40_000), Some("7")), &sample(999_000, Some(100_000), Some("7")), 1_000_500);
        assert_eq!(c, Corrected { occurred_at: 940_000, suspect: false });
    }

    #[test]
    fn different_boot_uses_offset() {
        let c = correct(&op(900_000, Some(1), Some("6")), &sample(5_000, Some(100_000), Some("7")), 1_000_500);
        assert_eq!(c.occurred_at, 905_000);
    }

    #[test]
    fn small_offset_is_ignored() {
        let c = correct(&op(900_000, None, None), &sample(1_500, None, None), 1_000_500);
        assert_eq!(c.occurred_at, 900_000);
    }

    #[test]
    fn clamped_to_received_and_suspect_when_absurd() {
        let c = correct(&op(2_000_000, None, None), &sample(0, None, None), 1_000_500);
        assert_eq!(c.occurred_at, 1_000_500);
        let c = correct(&op(10, None, None), &sample(0, None, None), 2 * SUSPECT_MS);
        assert!(c.suspect);
        assert_eq!(c.occurred_at, 2 * SUSPECT_MS);
    }

    #[test]
    fn user_time_is_taken_as_is() {
        let mut o = op(123, Some(1), Some("7"));
        o.time_source = TimeSource::User;
        assert_eq!(correct(&o, &sample(99_999, Some(5), Some("7")), 1_000_000).occurred_at, 123);
    }
}
