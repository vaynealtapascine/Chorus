//! Switch-notification rules (NOTIFICATIONS.md §2–§5): who may see what, when it is revealed,
//! how the time is shown. Pure: randomness and "now" are passed in, so the server, tests and any
//! client preview agree exactly.
//!
//! The pipeline for one switch and one follower:
//! `effective ceiling → view (reveal) → diff vs. previous view → prefs → schedule → fuzz time`.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::front::{Entry, Level, Notify, SubjectType};

const MIN: i64 = 60_000;
const HOUR: i64 = 60 * MIN;
const DAY: i64 = 24 * HOUR;

// ─── ceiling (set by the followed account, §3) ───────────────────────────────

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HiddenSubjects {
    #[default]
    Omit,
    Someone,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Stacking {
    #[default]
    Collapse,
    Sequence,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DelayRange {
    pub min_s: i64,
    pub max_s: i64,
}

impl DelayRange {
    /// A value in `[min, max]` seconds, as ms, from one random draw.
    pub fn draw_ms(self, r: u64) -> i64 {
        let (lo, hi) = (self.min_s.max(0), self.max_s.max(self.min_s.max(0)));
        let span = (hi - lo) as u64 * 1000 + 1;
        lo * 1000 + (r % span) as i64
    }
}

/// How the time of a switch is shown to a follower (§3 `time`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum TimeRule {
    Exact,
    Round {
        #[serde(default = "fifteen")]
        round_min: i64,
    },
    Jitter {
        #[serde(default = "fifteen")]
        jitter_min: i64,
    },
    PartOfDay,
    Hidden,
}

fn fifteen() -> i64 {
    15
}

impl TimeRule {
    /// Higher = reveals more. Used to pick the most permissive among buckets.
    fn openness(self) -> i64 {
        match self {
            TimeRule::Exact => 10_000,
            TimeRule::Jitter { jitter_min } => 5_000 - jitter_min.clamp(0, 999),
            TimeRule::Round { round_min } => 4_000 - round_min.clamp(0, 999),
            TimeRule::PartOfDay => 1_000,
            TimeRule::Hidden => 0,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuietHours {
    /// "HH:MM" local time.
    pub from: String,
    pub to: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Ceiling {
    pub switch_notifications: bool,
    pub levels: Vec<Level>,
    pub hidden_subjects: HiddenSubjects,
    pub announce_leaving: bool,
    pub delay: DelayRange,
    /// Added on top when a switch is sent with `notify: extra_delay`.
    pub extra_delay: DelayRange,
    pub allow_now: bool,
    pub time: TimeRule,
    pub stacking: Stacking,
    pub digest_only: bool,
    pub max_per_day: Option<u32>,
    pub quiet_hours: Option<QuietHours>,
    pub share_current_front: bool,
    pub share_history: bool,
    pub share_stats: bool,
}

impl Default for Ceiling {
    /// The "Gentle" preset (§3), which is the account default.
    fn default() -> Self {
        Ceiling {
            switch_notifications: true,
            levels: vec![Level::Front, Level::Cocon],
            hidden_subjects: HiddenSubjects::Omit,
            announce_leaving: false,
            delay: DelayRange { min_s: 300, max_s: 1200 },
            extra_delay: DelayRange { min_s: 1800, max_s: 7200 },
            allow_now: true,
            time: TimeRule::Round { round_min: 15 },
            stacking: Stacking::Collapse,
            digest_only: false,
            max_per_day: None,
            quiet_hours: None,
            share_current_front: true,
            share_history: false,
            share_stats: false,
        }
    }
}

/// The Basic-UI presets (§3 table).
pub fn preset(name: &str) -> Option<Value> {
    let v = match name {
        "close" => serde_json::json!({
            "switch_notifications": true, "levels": ["front", "cocon", "present"], "announce_leaving": true,
            "delay": {"min_s": 0, "max_s": 0}, "time": {"mode": "exact"}, "digest_only": false,
        }),
        "gentle" => serde_json::json!({
            "switch_notifications": true, "levels": ["front", "cocon"], "delay": {"min_s": 300, "max_s": 1200},
            "time": {"mode": "round", "round_min": 15}, "digest_only": false,
        }),
        "private" => serde_json::json!({
            "switch_notifications": true, "levels": ["front"], "delay": {"min_s": 1800, "max_s": 5400},
            "time": {"mode": "part_of_day"}, "digest_only": false,
        }),
        "digest" => serde_json::json!({
            "switch_notifications": true, "levels": ["front"], "time": {"mode": "part_of_day"}, "digest_only": true,
        }),
        "off" => serde_json::json!({"switch_notifications": false}),
        _ => return None,
    };
    Some(v)
}

/// Field-wise "most permissive" of two partial ceilings (a follower in several buckets gets the
/// most open value each bucket grants, §3).
fn permissive(field: &str, a: &Value, b: &Value) -> Value {
    let t = |v: &Value| v.as_bool().unwrap_or(false);
    match field {
        "switch_notifications"
        | "announce_leaving"
        | "allow_now"
        | "share_current_front"
        | "share_history"
        | "share_stats" => Value::Bool(t(a) || t(b)),
        "digest_only" => Value::Bool(t(a) && t(b)),
        "levels" => {
            let mut s: BTreeSet<String> = BTreeSet::new();
            for v in [a, b] {
                for l in v.as_array().into_iter().flatten() {
                    if let Some(l) = l.as_str() {
                        s.insert(l.to_string());
                    }
                }
            }
            Value::Array(s.into_iter().map(Value::String).collect())
        }
        "hidden_subjects" => {
            if a == "someone" || b == "someone" {
                "someone".into()
            } else {
                "omit".into()
            }
        }
        "stacking" => {
            if a == "sequence" || b == "sequence" {
                "sequence".into()
            } else {
                "collapse".into()
            }
        }
        "delay" | "extra_delay" => {
            let g = |v: &Value, k: &str| v.get(k).and_then(Value::as_i64).unwrap_or(i64::MAX);
            serde_json::json!({"min_s": g(a, "min_s").min(g(b, "min_s")), "max_s": g(a, "max_s").min(g(b, "max_s"))})
        }
        "time" => {
            let open = |v: &Value| serde_json::from_value::<TimeRule>(v.clone()).map(TimeRule::openness).unwrap_or(-1);
            if open(a) >= open(b) { a.clone() } else { b.clone() }
        }
        "max_per_day" | "quiet_hours" => {
            // null (no limit / no quiet hours) is the most open
            if a.is_null() || b.is_null() {
                Value::Null
            } else if field == "max_per_day" {
                Value::from(a.as_u64().unwrap_or(0).max(b.as_u64().unwrap_or(0)))
            } else {
                a.clone()
            }
        }
        _ => a.clone(),
    }
}

/// Effective ceiling for one follower: follow override → most permissive among the follower's
/// buckets → account default → built-in default. Inputs are the stored partial JSON objects.
pub fn effective_ceiling(account_default: &Value, buckets: &[&Value], follow_override: &Value) -> Ceiling {
    let mut out: Map<String, Value> = match serde_json::to_value(Ceiling::default()) {
        Ok(Value::Object(m)) => m,
        _ => Map::new(),
    };
    fn layer(out: &mut Map<String, Value>, v: &Value) {
        if let Some(o) = v.as_object() {
            for (k, x) in o {
                out.insert(k.clone(), x.clone());
            }
        }
    }
    layer(&mut out, account_default);
    // A bucket that leaves a field unset inherits the account default for it, so the default takes
    // part in the "most permissive" fold for every field any bucket sets.
    let mut from_buckets: Map<String, Value> = Map::new();
    let set: BTreeSet<&String> =
        buckets.iter().flat_map(|b| b.as_object().into_iter().flatten().map(|(k, _)| k)).collect();
    for k in set {
        let inherited = out.get(k.as_str()).cloned().unwrap_or(Value::Null);
        let mut acc: Option<Value> = None;
        for b in buckets {
            let x = b.get(k.as_str()).cloned().unwrap_or_else(|| inherited.clone());
            acc = Some(match acc {
                Some(prev) => permissive(k, &prev, &x),
                None => x,
            });
        }
        if let Some(v) = acc {
            from_buckets.insert(k.clone(), v);
        }
    }
    layer(&mut out, &Value::Object(from_buckets));
    layer(&mut out, follow_override);
    serde_json::from_value(Value::Object(out)).unwrap_or_default()
}

// ─── member policy and follower prefs (§2.4, §4) ─────────────────────────────

#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Announce {
    #[default]
    Everyone,
    Nobody,
    Buckets(Vec<String>),
}

#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct MemberPolicy {
    pub announce: Announce,
    pub announce_leaving: bool,
    pub extra_delay_range: Option<DelayRange>,
}

#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(tag = "mode", content = "ids", rename_all = "snake_case")]
pub enum SubjectFilter {
    #[default]
    All,
    Only(Vec<String>),
    Except(Vec<String>),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Delivery {
    #[default]
    Each,
    DigestHourly,
    DigestDaily,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum QuietBehaviour {
    #[default]
    Hold,
    Drop,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Prefs {
    pub enabled: bool,
    pub subjects: SubjectFilter,
    pub levels: Vec<Level>,
    pub switch_outs: bool,
    pub delivery: Delivery,
    pub digest_time: String,
    pub quiet_hours: Option<QuietHours>,
    pub quiet_behaviour: QuietBehaviour,
    pub mute_until: Option<i64>,
}

impl Default for Prefs {
    fn default() -> Self {
        Prefs {
            enabled: true,
            subjects: SubjectFilter::All,
            levels: vec![Level::Front],
            switch_outs: false,
            delivery: Delivery::Each,
            digest_time: "20:00".into(),
            quiet_hours: None,
            quiet_behaviour: QuietBehaviour::Hold,
            mute_until: None,
        }
    }
}

// ─── the follower's view (§5) ────────────────────────────────────────────────

/// One thing a follower may know is in front.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Seen {
    Subject {
        subject_type: SubjectType,
        subject_id: String,
        level: Level,
        is_primary: bool,
    },
    /// A hidden subject shown as "someone" (ceiling `hidden_subjects: someone`).
    Someone {
        level: Level,
    },
}

impl Seen {
    pub fn level(&self) -> Level {
        match self {
            Seen::Subject { level, .. } | Seen::Someone { level } => *level,
        }
    }
}

/// Everything about the followed account the view needs.
pub struct Audience<'a> {
    pub ceiling: &'a Ceiling,
    /// Buckets the follower is in (for `announce: {buckets: …}`).
    pub follower_buckets: &'a BTreeSet<String>,
    /// member id → policy (missing = announce to everyone).
    pub policies: &'a BTreeMap<String, MemberPolicy>,
}

impl Audience<'_> {
    fn member_visible(&self, id: &str) -> bool {
        match self.policies.get(id).map(|p| &p.announce) {
            None | Some(Announce::Everyone) => true,
            Some(Announce::Nobody) => false,
            Some(Announce::Buckets(b)) => b.iter().any(|x| self.follower_buckets.contains(x)),
        }
    }
}

/// What a follower may know is in front (the reveal, independent of their own prefs).
/// Empty when the ceiling shares nothing.
pub fn view(front: &[Entry], a: &Audience) -> Vec<Seen> {
    let c = a.ceiling;
    if !c.switch_notifications && !c.share_current_front {
        return Vec::new();
    }
    let mut out: Vec<Seen> = Vec::new();
    for e in front {
        if !c.levels.contains(&e.level) {
            continue;
        }
        let visible = e.subject_type != SubjectType::Member || a.member_visible(&e.subject_id);
        if visible {
            out.push(Seen::Subject {
                subject_type: e.subject_type,
                subject_id: e.subject_id.clone(),
                level: e.level,
                is_primary: e.is_primary,
            });
        } else if c.hidden_subjects == HiddenSubjects::Someone {
            let s = Seen::Someone { level: e.level };
            if !out.contains(&s) {
                out.push(s);
            }
        }
    }
    out.sort();
    out
}

/// What changed between two views, as the follower's prefs and the leaving rules allow.
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize)]
pub struct Diff {
    pub arrived: Vec<Seen>,
    pub left: Vec<Seen>,
}

impl Diff {
    pub fn is_empty(&self) -> bool {
        self.arrived.is_empty() && self.left.is_empty()
    }
}

fn id_of(s: &Seen) -> Option<&str> {
    match s {
        Seen::Subject { subject_id, .. } => Some(subject_id),
        Seen::Someone { .. } => None,
    }
}

/// `groups` maps a group id to its member ids, so prefs can name a subsystem.
pub fn wanted(s: &Seen, prefs: &Prefs, groups: &BTreeMap<String, BTreeSet<String>>) -> bool {
    if !prefs.levels.contains(&s.level()) {
        return false;
    }
    let covers = |ids: &[String]| {
        id_of(s).is_some_and(|id| ids.iter().any(|x| x == id || groups.get(x).is_some_and(|g| g.contains(id))))
    };
    match &prefs.subjects {
        SubjectFilter::All => true,
        SubjectFilter::Only(ids) => covers(ids),
        SubjectFilter::Except(ids) => !covers(ids),
    }
}

/// The notification-worthy part of a view change for this follower.
pub fn diff(
    before: &[Seen],
    after: &[Seen],
    a: &Audience,
    prefs: &Prefs,
    groups: &BTreeMap<String, BTreeSet<String>>,
) -> Diff {
    let key = |s: &Seen| match s {
        Seen::Subject { subject_type, subject_id, level, .. } => (Some((*subject_type, subject_id.clone())), *level),
        Seen::Someone { level } => (None, *level),
    };
    let had: BTreeSet<_> = before.iter().map(key).collect();
    let has: BTreeSet<_> = after.iter().map(key).collect();
    let arrived = after.iter().filter(|s| !had.contains(&key(s)) && wanted(s, prefs, groups)).cloned().collect();
    let leaving_ok = a.ceiling.announce_leaving && prefs.switch_outs;
    let left = before
        .iter()
        .filter(|s| !has.contains(&key(s)) && leaving_ok && wanted(s, prefs, groups))
        .filter(|s| match s {
            Seen::Subject { subject_type: SubjectType::Member, subject_id, .. } => {
                a.policies.get(subject_id).is_none_or(|p| p.announce_leaving || p.announce == Announce::Everyone)
            }
            _ => true,
        })
        .cloned()
        .collect();
    Diff { arrived, left }
}

// ─── scheduling (§2.7, §6) ───────────────────────────────────────────────────

/// Random draws for one (switch, follower), drawn once from a CSPRNG and persisted (restarts
/// don't redraw).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Draws {
    pub delay: u64,
    pub extra: u64,
    pub late: u64,
    pub jitter: u64,
}

pub struct ScheduleIn<'a> {
    pub occurred_at: i64,
    /// When the server accepted the switch.
    pub accepted_at: i64,
    pub notify: Notify,
    pub settle_ms: i64,
    /// Per-member extra delays of the subjects that arrived (the largest applies).
    pub member_extra: &'a [DelayRange],
    pub draws: Draws,
}

pub const DEFAULT_SETTLE_MS: i64 = 15_000;
const LATE_SPREAD_MS: u64 = 120_000;

/// `due_at`: when this switch is revealed to (and, if wanted, notifies) the follower.
pub fn due_at(c: &Ceiling, s: &ScheduleIn) -> i64 {
    let mut delay = if s.notify == Notify::Now && c.allow_now { 0 } else { c.delay.draw_ms(s.draws.delay) };
    if s.notify == Notify::ExtraDelay {
        delay += c.extra_delay.draw_ms(s.draws.extra);
    }
    if let Some(m) = s.member_extra.iter().max_by_key(|r| (r.max_s, r.min_s)) {
        delay += m.draw_ms(s.draws.extra.rotate_left(17));
    }
    let due = s.occurred_at + s.settle_ms + delay;
    let earliest = s.accepted_at + s.settle_ms;
    if due < earliest {
        // arrived late (offline phone): deliver soon, spread out so a backlog doesn't flood
        earliest + (s.draws.late % LATE_SPREAD_MS) as i64
    } else {
        due
    }
}

/// A pending item for the same (follower, account), for supersede decisions.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pending {
    pub id: String,
    pub due_at: i64,
}

/// §2.8: `collapse` cancels older pending items (the new one describes the latest state);
/// `sequence` keeps them and pushes the new one after the last, preserving order.
pub fn supersede(stacking: Stacking, pending: &[Pending], due: i64) -> (Vec<String>, i64) {
    match stacking {
        Stacking::Collapse => (pending.iter().map(|p| p.id.clone()).collect(), due),
        Stacking::Sequence => {
            let last = pending.iter().map(|p| p.due_at).max();
            (Vec::new(), last.map_or(due, |l| due.max(l + 1000)))
        }
    }
}

// ─── quiet hours, digests, routing (§2.10–§2.12) ─────────────────────────────

fn hhmm(s: &str) -> Option<i64> {
    let (h, m) = s.split_once(':')?;
    let (h, m): (i64, i64) = (h.trim().parse().ok()?, m.trim().parse().ok()?);
    ((0..24).contains(&h) && (0..60).contains(&m)).then_some(h * HOUR + m * MIN)
}

/// Local midnight (as UTC ms) of the day containing `t`.
fn local_day_start(t: i64, tz_offset_min: i32) -> i64 {
    let off = tz_offset_min as i64 * MIN;
    (t + off).div_euclid(DAY) * DAY - off
}

/// If `t` falls inside the quiet window, when it ends; else `None`. Windows may cross midnight.
pub fn quiet_until(t: i64, q: &QuietHours, tz_offset_min: i32) -> Option<i64> {
    let (from, to) = (hhmm(&q.from)?, hhmm(&q.to)?);
    if from == to {
        return None;
    }
    let day = local_day_start(t, tz_offset_min);
    let at = t - day;
    if from < to {
        (at >= from && at < to).then_some(day + to)
    } else if at >= from {
        Some(day + DAY + to)
    } else {
        (at < to).then_some(day + to)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "route", rename_all = "snake_case")]
pub enum Route {
    /// Notify at `at`.
    Deliver { at: i64 },
    /// Add to the digest going out at `at`.
    Digest { at: i64 },
    /// Reveal only (no ping): muted, silent, filtered or dropped by quiet hours.
    RevealOnly,
}

pub struct RouteIn<'a> {
    pub due_at: i64,
    pub notify: Notify,
    pub diff_empty: bool,
    pub sent_today: u32,
    pub tz_offset_min: i32,
    pub digest_draw: u64,
    pub ceiling: &'a Ceiling,
    pub prefs: &'a Prefs,
}

/// Next digest time at/after `t`: hourly on the hour, or daily at `digest_time` ± 0–20 min.
pub fn next_digest(t: i64, daily: bool, digest_time: &str, tz_offset_min: i32, draw: u64) -> i64 {
    let spread = (draw % (20 * MIN as u64)) as i64;
    if !daily {
        return (t.div_euclid(HOUR) + 1) * HOUR;
    }
    let at = hhmm(digest_time).unwrap_or(20 * HOUR);
    let day = local_day_start(t, tz_offset_min);
    let today = day + at + spread;
    if today >= t { today } else { today + DAY }
}

/// Deliver, digest or reveal-only, after quiet hours, mute, digests and the daily cap.
pub fn route(r: &RouteIn) -> Route {
    let (c, p) = (r.ceiling, r.prefs);
    if !c.switch_notifications || !p.enabled || r.notify == Notify::Silent || r.diff_empty {
        return Route::RevealOnly;
    }
    if p.mute_until.is_some_and(|m| m > r.due_at) {
        return Route::RevealOnly;
    }
    let daily = matches!(p.delivery, Delivery::DigestDaily) || c.digest_only;
    let over_cap = c.max_per_day.is_some_and(|m| r.sent_today >= m);
    if daily || over_cap || matches!(p.delivery, Delivery::DigestHourly) {
        let at = next_digest(r.due_at, daily || over_cap, &p.digest_time, r.tz_offset_min, r.digest_draw);
        return Route::Digest { at };
    }
    // the stricter of the two quiet windows wins: hold until the later end
    let hold = [c.quiet_hours.as_ref(), p.quiet_hours.as_ref()]
        .into_iter()
        .flatten()
        .filter_map(|q| quiet_until(r.due_at, q, r.tz_offset_min))
        .max();
    match hold {
        Some(_) if p.quiet_behaviour == QuietBehaviour::Drop => Route::RevealOnly,
        Some(end) => Route::Deliver { at: end },
        None => Route::Deliver { at: r.due_at },
    }
}

// ─── the displayed time (§2.9) ───────────────────────────────────────────────

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PartOfDay {
    Night,
    Morning,
    Afternoon,
    Evening,
}

/// The time a follower sees. `at` orders items (and is what `displayed_since` stores);
/// `precision` tells the client how to render it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Displayed {
    pub at: Option<i64>,
    pub precision: Precision,
    pub part: Option<PartOfDay>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Precision {
    Exact,
    Approx,
    PartOfDay,
    None,
}

fn part_of(t: i64, tz_offset_min: i32) -> (PartOfDay, i64) {
    let day = local_day_start(t, tz_offset_min);
    let h = (t - day) / HOUR;
    let (part, start_h) = match h {
        0..=4 => (PartOfDay::Night, 0),
        5..=11 => (PartOfDay::Morning, 5),
        12..=16 => (PartOfDay::Afternoon, 12),
        17..=21 => (PartOfDay::Evening, 17),
        _ => (PartOfDay::Night, 22),
    };
    (part, day + start_h * HOUR)
}

/// Fuzz the real switch time `t` for display. Guarantees `at ≤ delivered_at` and, given the
/// previous displayed time for this (follower, account), `at ≥ previous` (non-decreasing).
pub fn displayed(
    t: i64,
    delivered_at: i64,
    rule: TimeRule,
    tz_offset_min: i32,
    previous: Option<i64>,
    draw: u64,
) -> Displayed {
    let clamp = |x: i64| {
        let x = x.min(delivered_at);
        previous.map_or(x, |p| x.max(p.min(delivered_at)))
    };
    match rule {
        TimeRule::Exact => Displayed { at: Some(clamp(t)), precision: Precision::Exact, part: None },
        TimeRule::Round { round_min } => {
            let step = round_min.max(1) * MIN;
            Displayed { at: Some(clamp(t.div_euclid(step) * step)), precision: Precision::Approx, part: None }
        }
        TimeRule::Jitter { jitter_min } => {
            let j = jitter_min.max(0) * MIN;
            let offset = (draw % (2 * j as u64 + 1)) as i64 - j;
            // whole minutes, so the shown time doesn't look suspiciously precise
            let x = (t + offset).div_euclid(MIN) * MIN;
            Displayed { at: Some(clamp(x)), precision: Precision::Approx, part: None }
        }
        TimeRule::PartOfDay => {
            let at = clamp(part_of(t, tz_offset_min).1);
            Displayed { at: Some(at), precision: Precision::PartOfDay, part: Some(part_of(at, tz_offset_min).0) }
        }
        TimeRule::Hidden => Displayed { at: previous, precision: Precision::None, part: None },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn e(id: &str, level: Level) -> Entry {
        Entry { subject_type: SubjectType::Member, subject_id: id.into(), level, is_primary: false }
    }

    #[test]
    fn ceiling_layers_and_buckets_are_most_permissive() {
        let default = json!({"delay": {"min_s": 600, "max_s": 1800}, "time": {"mode": "part_of_day"}});
        let close =
            json!({"delay": {"min_s": 0, "max_s": 60}, "levels": ["front"], "time": {"mode": "round", "round_min": 5}});
        let other = json!({"levels": ["cocon"], "time": {"mode": "round", "round_min": 30}, "digest_only": true});
        let c = effective_ceiling(&default, &[&close, &other], &json!({}));
        assert_eq!(c.delay, DelayRange { min_s: 0, max_s: 60 });
        assert_eq!(c.levels.iter().collect::<BTreeSet<_>>(), BTreeSet::from([&Level::Front, &Level::Cocon]));
        assert_eq!(c.time, TimeRule::Round { round_min: 5 });
        assert!(!c.digest_only); // the other bucket inherits the default (false), which is more open
        let c = effective_ceiling(&default, &[&other], &json!({}));
        assert!(c.digest_only);
        // a per-follow override wins over everything
        let c = effective_ceiling(&default, &[&close], &json!({"switch_notifications": false}));
        assert!(!c.switch_notifications);
        // no input → the Gentle default
        assert_eq!(effective_ceiling(&json!({}), &[], &json!({})), Ceiling::default());
    }

    #[test]
    fn presets_parse() {
        for p in ["close", "gentle", "private", "digest", "off"] {
            let c = effective_ceiling(&preset(p).unwrap(), &[], &json!({}));
            assert_eq!(c.switch_notifications, p != "off", "{p}");
        }
    }

    #[test]
    fn view_hides_members_and_levels() {
        let ceiling = Ceiling { hidden_subjects: HiddenSubjects::Someone, ..Ceiling::default() };
        let mut policies = BTreeMap::new();
        policies.insert("rin".to_string(), MemberPolicy { announce: Announce::Nobody, ..Default::default() });
        policies.insert(
            "ash".to_string(),
            MemberPolicy { announce: Announce::Buckets(vec!["close".into()]), ..Default::default() },
        );
        let buckets = BTreeSet::new();
        let a = Audience { ceiling: &ceiling, follower_buckets: &buckets, policies: &policies };
        let v = view(
            &[e("kai", Level::Front), e("rin", Level::Front), e("ash", Level::Front), e("moss", Level::Present)],
            &a,
        );
        assert_eq!(
            v,
            vec![
                Seen::Subject {
                    subject_type: SubjectType::Member,
                    subject_id: "kai".into(),
                    level: Level::Front,
                    is_primary: false
                },
                Seen::Someone { level: Level::Front },
            ]
        );
        let close: BTreeSet<String> = ["close".to_string()].into();
        let a = Audience { ceiling: &ceiling, follower_buckets: &close, policies: &policies };
        assert_eq!(view(&[e("ash", Level::Front)], &a).len(), 1);
        let off = Ceiling { switch_notifications: false, share_current_front: false, ..Ceiling::default() };
        let a = Audience { ceiling: &off, follower_buckets: &buckets, policies: &policies };
        assert!(view(&[e("kai", Level::Front)], &a).is_empty());
    }

    #[test]
    fn diff_respects_prefs_and_leaving_rules() {
        let ceiling = Ceiling { announce_leaving: true, ..Ceiling::default() };
        let policies = BTreeMap::new();
        let buckets = BTreeSet::new();
        let a = Audience { ceiling: &ceiling, follower_buckets: &buckets, policies: &policies };
        let before = view(&[e("kai", Level::Front)], &a);
        let after = view(&[e("june", Level::Front), e("rin", Level::Cocon)], &a);
        let groups = BTreeMap::from([("stars".to_string(), BTreeSet::from(["june".to_string()]))]);
        let prefs = Prefs::default(); // front only, no switch-outs
        let d = diff(&before, &after, &a, &prefs, &groups);
        assert_eq!(d.arrived.len(), 1);
        assert!(d.left.is_empty());
        let prefs =
            Prefs { switch_outs: true, subjects: SubjectFilter::Only(vec!["stars".into()]), ..Prefs::default() };
        let d = diff(&before, &after, &a, &prefs, &groups);
        assert_eq!((d.arrived.len(), d.left.len()), (1, 0)); // kai isn't in stars
        let prefs = Prefs { switch_outs: true, ..Prefs::default() };
        assert_eq!(diff(&before, &after, &a, &prefs, &groups).left.len(), 1);
    }

    #[test]
    fn schedule_settles_delays_and_spreads_late_arrivals() {
        let c = Ceiling::default();
        let s = |notify, occurred, accepted, draws| ScheduleIn {
            occurred_at: occurred,
            accepted_at: accepted,
            notify,
            settle_ms: DEFAULT_SETTLE_MS,
            member_extra: &[],
            draws,
        };
        let d = Draws { delay: 0, ..Default::default() };
        assert_eq!(due_at(&c, &s(Notify::Default, 1_000_000, 1_000_000, d)), 1_000_000 + 15_000 + 300_000);
        assert_eq!(due_at(&c, &s(Notify::Now, 1_000_000, 1_000_000, d)), 1_015_000);
        let no_now = Ceiling { allow_now: false, ..c.clone() };
        assert_eq!(due_at(&no_now, &s(Notify::Now, 1_000_000, 1_000_000, d)), 1_315_000);
        // a switch from 3 h ago arriving now is delivered within 2 min of settling
        let late = due_at(&c, &s(Notify::Default, 0, 3 * HOUR, Draws { late: 50_000, ..d }));
        assert_eq!(late, 3 * HOUR + 15_000 + 50_000);
    }

    #[test]
    fn supersede_collapses_or_sequences() {
        let p = vec![Pending { id: "a".into(), due_at: 100_000 }, Pending { id: "b".into(), due_at: 200_000 }];
        assert_eq!(supersede(Stacking::Collapse, &p, 150_000), (vec!["a".into(), "b".into()], 150_000));
        assert_eq!(supersede(Stacking::Sequence, &p, 150_000), (vec![], 201_000));
    }

    #[test]
    fn quiet_hours_cross_midnight() {
        let q = QuietHours { from: "23:00".into(), to: "08:00".into() };
        let day = 10 * DAY;
        assert_eq!(quiet_until(day + 23 * HOUR + 30 * MIN, &q, 0), Some(day + DAY + 8 * HOUR));
        assert_eq!(quiet_until(day + 2 * HOUR, &q, 0), Some(day + 8 * HOUR));
        assert_eq!(quiet_until(day + 12 * HOUR, &q, 0), None);
        // local time: 23:30 at UTC+2 is 21:30 UTC
        assert_eq!(quiet_until(day + 21 * HOUR + 30 * MIN, &q, 120), Some(day + DAY + 6 * HOUR));
    }

    #[test]
    fn routing() {
        let c = Ceiling::default();
        let p = Prefs::default();
        let r = |c: &Ceiling, p: &Prefs, notify, empty, sent| {
            route(&RouteIn {
                due_at: 10 * DAY + 12 * HOUR,
                notify,
                diff_empty: empty,
                sent_today: sent,
                tz_offset_min: 0,
                digest_draw: 0,
                ceiling: c,
                prefs: p,
            })
        };
        assert_eq!(r(&c, &p, Notify::Default, false, 0), Route::Deliver { at: 10 * DAY + 12 * HOUR });
        assert_eq!(r(&c, &p, Notify::Silent, false, 0), Route::RevealOnly);
        assert_eq!(r(&c, &p, Notify::Default, true, 0), Route::RevealOnly);
        let capped = Ceiling { max_per_day: Some(3), ..c.clone() };
        assert_eq!(r(&capped, &p, Notify::Default, false, 3), Route::Digest { at: 10 * DAY + 20 * HOUR });
        let quiet = Prefs { quiet_hours: Some(QuietHours { from: "11:00".into(), to: "13:00".into() }), ..p.clone() };
        assert_eq!(r(&c, &quiet, Notify::Default, false, 0), Route::Deliver { at: 10 * DAY + 13 * HOUR });
        let drop = Prefs { quiet_behaviour: QuietBehaviour::Drop, ..quiet };
        assert_eq!(r(&c, &drop, Notify::Default, false, 0), Route::RevealOnly);
    }

    #[test]
    fn displayed_times() {
        let t = 10 * DAY + 14 * HOUR + 37 * MIN + 12_000;
        let d = displayed(t, t + HOUR, TimeRule::Round { round_min: 15 }, 0, None, 0);
        assert_eq!(d.at, Some(10 * DAY + 14 * HOUR + 30 * MIN));
        let d = displayed(t, t + HOUR, TimeRule::PartOfDay, 0, None, 0);
        assert_eq!((d.at, d.part), (Some(10 * DAY + 12 * HOUR), Some(PartOfDay::Afternoon)));
        // never after delivery, never before the previous one
        assert_eq!(displayed(t, t - 5_000, TimeRule::Exact, 0, None, 0).at, Some(t - 5_000));
        assert_eq!(displayed(t, t + HOUR, TimeRule::PartOfDay, 0, Some(t), 0).at, Some(t));
        assert_eq!(displayed(t, t, TimeRule::Hidden, 0, None, 0).precision, Precision::None);
    }
}
