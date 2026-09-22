//! The front timeline (SYNC.md §5.6–5.7, DATA_MODEL.md `switch` / `front_interval` / `front_daily`).
//!
//! `fold` turns the set of an account's front ops into switch rows with resulting snapshots,
//! presence intervals and the current front. It depends only on the set of ops, never on the
//! order they arrived in.

use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::hlc::Hlc;
use crate::op::Op;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SubjectType {
    Member,
    Group,
    State,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Level {
    Front,
    Cocon,
    Present,
}

impl Level {
    pub fn as_str(self) -> &'static str {
        match self {
            Level::Front => "front",
            Level::Cocon => "cocon",
            Level::Present => "present",
        }
    }
}

impl SubjectType {
    pub fn as_str(self) -> &'static str {
        match self {
            SubjectType::Member => "member",
            SubjectType::Group => "group",
            SubjectType::State => "state",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Subject {
    pub subject_type: SubjectType,
    pub subject_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Entry {
    pub subject_type: SubjectType,
    pub subject_id: String,
    #[serde(default = "front_level")]
    pub level: Level,
    #[serde(default)]
    pub is_primary: bool,
}

fn front_level() -> Level {
    Level::Front
}

impl Entry {
    pub fn subject(&self) -> Subject {
        Subject { subject_type: self.subject_type, subject_id: self.subject_id.clone() }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Notify {
    #[default]
    Default,
    Silent,
    Now,
    ExtraDelay,
}

// ─── payloads ────────────────────────────────────────────────────────────────

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SwitchPayload {
    pub entries: Vec<Entry>,
    #[serde(default)]
    pub based_on: Option<String>,
    #[serde(default)]
    pub note: Option<String>,
    #[serde(default)]
    pub notify: Notify,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AddPayload {
    pub entry: Entry,
    #[serde(default)]
    pub position: Option<usize>,
    #[serde(default)]
    pub based_on: Option<String>,
    #[serde(default)]
    pub notify: Notify,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemovePayload {
    pub subject_type: SubjectType,
    pub subject_id: String,
    #[serde(default)]
    pub based_on: Option<String>,
    #[serde(default)]
    pub notify: Notify,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpdatePayload {
    pub subject_type: SubjectType,
    pub subject_id: String,
    #[serde(default)]
    pub level: Option<Level>,
    #[serde(default)]
    pub is_primary: Option<bool>,
    #[serde(default)]
    pub position: Option<usize>,
    #[serde(default)]
    pub based_on: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TargetPayload {
    pub target_op_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AmendPayload {
    pub target_op_id: String,
    #[serde(default)]
    pub occurred_at: Option<i64>,
    /// Only honoured when the target is a full `front.switch`.
    #[serde(default)]
    pub entries: Option<Vec<Entry>>,
    #[serde(default, with = "double_option")]
    pub note: Option<Option<String>>,
}

mod double_option {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(v: &Option<Option<String>>, s: S) -> Result<S::Ok, S::Error> {
        v.serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<Option<String>>, D::Error> {
        Option::<String>::deserialize(d).map(Some)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FrontAction {
    Switch(SwitchPayload),
    Add(AddPayload),
    Remove(RemovePayload),
    Update(UpdatePayload),
    Retract(TargetPayload),
    Unretract(TargetPayload),
    Amend(AmendPayload),
}

impl FrontAction {
    pub fn kind(&self) -> &'static str {
        match self {
            FrontAction::Switch(_) => "switch",
            FrontAction::Add(_) => "add",
            FrontAction::Remove(_) => "remove",
            FrontAction::Update(_) => "update",
            FrontAction::Retract(_) => "retract",
            FrontAction::Unretract(_) => "unretract",
            FrontAction::Amend(_) => "amend",
        }
    }

    fn based_on(&self) -> Option<&str> {
        match self {
            FrontAction::Switch(p) => p.based_on.as_deref(),
            FrontAction::Add(p) => p.based_on.as_deref(),
            FrontAction::Remove(p) => p.based_on.as_deref(),
            FrontAction::Update(p) => p.based_on.as_deref(),
            _ => None,
        }
    }

    fn notify(&self) -> Notify {
        match self {
            FrontAction::Switch(p) => p.notify,
            FrontAction::Add(p) => p.notify,
            FrontAction::Remove(p) => p.notify,
            _ => Notify::Default,
        }
    }
}

/// A front op ready for folding.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FrontOp {
    pub id: String,
    pub action: FrontAction,
    pub occurred_at: i64,
    pub tz_offset_min: i32,
    pub hlc: Hlc,
    pub device_id: String,
    /// Server seq; `None` while pending on a device.
    pub seq: Option<i64>,
    pub seen_seq: i64,
    pub was_offline: bool,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum FrontError {
    #[error("{0} is not a front op")]
    NotFront(String),
    #[error("bad payload for {0}: {1}")]
    Payload(String, String),
}

impl FrontOp {
    pub fn from_op(op: &Op) -> Result<FrontOp, FrontError> {
        let p = op.payload.clone();
        let bad = |e: serde_json::Error| FrontError::Payload(op.kind.clone(), e.to_string());
        let action = match op.kind.as_str() {
            "front.switch" => FrontAction::Switch(serde_json::from_value(p).map_err(bad)?),
            "front.add" => FrontAction::Add(serde_json::from_value(p).map_err(bad)?),
            "front.remove" => FrontAction::Remove(serde_json::from_value(p).map_err(bad)?),
            "front.update" => FrontAction::Update(serde_json::from_value(p).map_err(bad)?),
            "front.retract" => FrontAction::Retract(serde_json::from_value(p).map_err(bad)?),
            "front.unretract" => FrontAction::Unretract(serde_json::from_value(p).map_err(bad)?),
            "front.amend" => FrontAction::Amend(serde_json::from_value(p).map_err(bad)?),
            other => return Err(FrontError::NotFront(other.to_string())),
        };
        Ok(FrontOp {
            id: op.id.clone(),
            action,
            occurred_at: op.time(),
            tz_offset_min: op.tz_offset_min,
            hlc: op.hlc,
            device_id: op.device_id.clone().unwrap_or_else(|| format!("{:08x}", op.hlc.node)),
            seq: op.seq,
            seen_seq: op.seen_seq,
            was_offline: false,
        })
    }
}

// ─── fold output ─────────────────────────────────────────────────────────────

pub type Front = Vec<Entry>;

/// One row of the `switch` table.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct SwitchRow {
    pub id: String,
    pub kind: &'static str,
    pub occurred_at: i64,
    pub tz_offset_min: i32,
    pub device_id: String,
    pub based_on: Option<String>,
    /// The op's own entries (snapshot for `switch`, the single affected entry for deltas).
    pub entries: Vec<Entry>,
    pub resulting_front: Front,
    pub note: Option<String>,
    pub notify: Notify,
    pub was_offline: bool,
    pub retracted: bool,
    pub amended: bool,
}

/// One row of `front_interval`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Interval {
    pub id: String,
    pub subject_type: SubjectType,
    pub subject_id: String,
    pub level: Level,
    pub is_primary: bool,
    pub position: usize,
    pub start_at: i64,
    pub end_at: Option<i64>,
    pub start_switch_id: String,
    pub end_switch_id: Option<String>,
    pub start_tz_offset_min: i32,
}

#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize)]
pub struct FoldResult {
    /// All switch-like ops (switch/add/remove/update), sorted by timeline order, including
    /// retracted ones (flagged, and skipped by the fold).
    pub switches: Vec<SwitchRow>,
    pub intervals: Vec<Interval>,
    pub current: Front,
}

/// Normalize a front: dedupe subjects (first wins), order by level (front, cocon, present)
/// preserving relative order, and keep at most one primary, which must be at `front` level.
pub fn normalize(front: &mut Front) {
    let mut seen = std::collections::HashSet::new();
    front.retain(|e| seen.insert(e.subject()));
    front.sort_by_key(|e| e.level); // stable
    let mut have_primary = false;
    for e in front.iter_mut() {
        if e.is_primary && e.level == Level::Front && !have_primary {
            have_primary = true;
        } else {
            e.is_primary = false;
        }
    }
}

fn level_insert_index(front: &Front, level: Level, position: Option<usize>) -> usize {
    let start = front.iter().position(|e| e.level >= level).unwrap_or(front.len());
    let end = front.iter().position(|e| e.level > level).unwrap_or(front.len());
    match position {
        Some(p) => (start + p).min(end),
        None => end,
    }
}

fn apply(front: &mut Front, action: &FrontAction, amended_entries: Option<&Vec<Entry>>) {
    match action {
        FrontAction::Switch(p) => {
            *front = amended_entries.cloned().unwrap_or_else(|| p.entries.clone());
        }
        FrontAction::Add(p) => {
            let subj = p.entry.subject();
            if let Some(i) = front.iter().position(|e| e.subject() == subj) {
                front.remove(i);
            }
            if p.entry.is_primary {
                front.iter_mut().for_each(|e| e.is_primary = false);
            }
            let i = level_insert_index(front, p.entry.level, p.position);
            front.insert(i, p.entry.clone());
        }
        FrontAction::Remove(p) => {
            front.retain(|e| !(e.subject_type == p.subject_type && e.subject_id == p.subject_id));
        }
        FrontAction::Update(p) => {
            let Some(i) = front.iter().position(|e| e.subject_type == p.subject_type && e.subject_id == p.subject_id)
            else {
                return;
            };
            let mut e = front.remove(i);
            if let Some(l) = p.level {
                e.level = l;
            }
            if let Some(pr) = p.is_primary {
                if pr {
                    front.iter_mut().for_each(|x| x.is_primary = false);
                }
                e.is_primary = pr;
            }
            let at = match p.position {
                Some(_) => level_insert_index(front, e.level, p.position),
                None if p.level.is_some() => level_insert_index(front, e.level, None),
                None => i.min(front.len()),
            };
            front.insert(at, e);
        }
        FrontAction::Retract(_) | FrontAction::Unretract(_) | FrontAction::Amend(_) => {}
    }
    normalize(front);
}

fn op_entries(action: &FrontAction, amended: Option<&Vec<Entry>>) -> Vec<Entry> {
    match action {
        FrontAction::Switch(p) => amended.cloned().unwrap_or_else(|| p.entries.clone()),
        FrontAction::Add(p) => vec![p.entry.clone()],
        FrontAction::Remove(p) => vec![Entry {
            subject_type: p.subject_type,
            subject_id: p.subject_id.clone(),
            level: Level::Front,
            is_primary: false,
        }],
        FrontAction::Update(p) => vec![Entry {
            subject_type: p.subject_type,
            subject_id: p.subject_id.clone(),
            level: p.level.unwrap_or(Level::Front),
            is_primary: p.is_primary.unwrap_or(false),
        }],
        _ => vec![],
    }
}

pub fn interval_id(subject: &Subject, level: Level, start_switch_id: &str) -> String {
    let mut h = Sha256::new();
    h.update(subject.subject_type.as_str());
    h.update(b"|");
    h.update(&subject.subject_id);
    h.update(b"|");
    h.update(level.as_str());
    h.update(b"|");
    h.update(start_switch_id);
    let d = h.finalize();
    d[..16].iter().map(|b| format!("{b:02x}")).collect()
}

/// Fold the full set of an account's front ops.
pub fn fold(ops: &[FrontOp]) -> FoldResult {
    // 1. Retract / unretract: per target, the highest HLC wins.
    let mut retract: HashMap<&str, (Hlc, bool)> = HashMap::new();
    // 2. Amend: per target, the highest HLC wins.
    let mut amend: HashMap<&str, (Hlc, &AmendPayload)> = HashMap::new();
    for op in ops {
        match &op.action {
            FrontAction::Retract(t) | FrontAction::Unretract(t) => {
                let is_retract = matches!(op.action, FrontAction::Retract(_));
                let e = retract.entry(t.target_op_id.as_str()).or_insert((op.hlc, is_retract));
                if op.hlc >= e.0 {
                    *e = (op.hlc, is_retract);
                }
            }
            FrontAction::Amend(a) => {
                let e = amend.entry(a.target_op_id.as_str()).or_insert((op.hlc, a));
                if op.hlc >= e.0 {
                    *e = (op.hlc, a);
                }
            }
            _ => {}
        }
    }

    // 3. Sort switch-like ops by (occurred_at after amend, hlc, id).
    struct Item<'a> {
        op: &'a FrontOp,
        at: i64,
        entries_override: Option<&'a Vec<Entry>>,
        note: Option<String>,
        amended: bool,
        retracted: bool,
    }
    let mut items: Vec<Item> = ops
        .iter()
        .filter(|o| {
            matches!(o.action, FrontAction::Switch(_) | FrontAction::Add(_) | FrontAction::Remove(_) | FrontAction::Update(_))
        })
        .map(|o| {
            let am = amend.get(o.id.as_str()).map(|(_, a)| *a);
            let base_note = match &o.action {
                FrontAction::Switch(p) => p.note.clone(),
                _ => None,
            };
            Item {
                op: o,
                at: am.and_then(|a| a.occurred_at).unwrap_or(o.occurred_at),
                entries_override: am
                    .filter(|_| matches!(o.action, FrontAction::Switch(_)))
                    .and_then(|a| a.entries.as_ref()),
                note: am.and_then(|a| a.note.clone()).unwrap_or(base_note),
                amended: am.is_some(),
                retracted: retract.get(o.id.as_str()).is_some_and(|(_, r)| *r),
            }
        })
        .collect();
    items.sort_by(|a, b| (a.at, a.op.hlc, &a.op.id).cmp(&(b.at, b.op.hlc, &b.op.id)));

    // 4–6. Fold and diff into intervals.
    let mut front: Front = Vec::new();
    let mut open: BTreeMap<Subject, Interval> = BTreeMap::new();
    let mut result = FoldResult::default();
    for it in &items {
        let op = it.op;
        let mut row = SwitchRow {
            id: op.id.clone(),
            kind: op.action.kind(),
            occurred_at: it.at,
            tz_offset_min: op.tz_offset_min,
            device_id: op.device_id.clone(),
            based_on: op.action.based_on().map(str::to_string),
            entries: op_entries(&op.action, it.entries_override),
            resulting_front: Vec::new(),
            note: it.note.clone(),
            notify: op.action.notify(),
            was_offline: op.was_offline,
            retracted: it.retracted,
            amended: it.amended,
        };
        if it.retracted {
            row.resulting_front = front.clone();
            result.switches.push(row);
            continue;
        }
        let mut next = front.clone();
        apply(&mut next, &op.action, it.entries_override);

        // Close intervals whose (level, primary) changed or that left.
        let keys: Vec<Subject> = open.keys().cloned().collect();
        for s in keys {
            let still = next.iter().find(|e| e.subject() == s);
            let iv = &open[&s];
            if still.is_none_or(|e| e.level != iv.level || e.is_primary != iv.is_primary) {
                let mut iv = open.remove(&s).unwrap_or_else(|| unreachable!());
                iv.end_at = Some(it.at);
                iv.end_switch_id = Some(op.id.clone());
                result.intervals.push(iv);
            }
        }
        for (pos, e) in next.iter().enumerate() {
            let s = e.subject();
            if !open.contains_key(&s) {
                open.insert(
                    s.clone(),
                    Interval {
                        id: interval_id(&s, e.level, &op.id),
                        subject_type: e.subject_type,
                        subject_id: e.subject_id.clone(),
                        level: e.level,
                        is_primary: e.is_primary,
                        position: pos,
                        start_at: it.at,
                        end_at: None,
                        start_switch_id: op.id.clone(),
                        end_switch_id: None,
                        start_tz_offset_min: op.tz_offset_min,
                    },
                );
            }
        }
        front = next;
        row.resulting_front = front.clone();
        result.switches.push(row);
    }
    result.intervals.extend(open.into_values());
    result.intervals.sort_by(|a, b| (a.start_at, &a.id).cmp(&(b.start_at, &b.id)));
    result.current = front;
    result
}

// ─── daily totals ────────────────────────────────────────────────────────────

/// One row of `front_daily`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct DailyRow {
    pub day: String,
    pub subject_type: SubjectType,
    pub subject_id: String,
    pub level: Level,
    pub seconds: i64,
    pub as_primary_seconds: i64,
}

/// Split intervals into local days. `offset_min_at(t)` returns the UTC offset (minutes) of the
/// system timezone at instant `t`; core has no tz database, callers supply it. Ongoing intervals
/// are cut at `now`.
pub fn daily(intervals: &[Interval], now: i64, offset_min_at: impl Fn(i64) -> i32) -> Vec<DailyRow> {
    const DAY: i64 = 86_400_000;
    let mut acc: BTreeMap<(String, SubjectType, String, Level), (i64, i64)> = BTreeMap::new();
    for iv in intervals {
        let end = iv.end_at.unwrap_or(now).max(iv.start_at);
        let mut t = iv.start_at;
        while t < end {
            let off = i64::from(offset_min_at(t)) * 60_000;
            let local = t + off;
            let day_start_local = local.div_euclid(DAY) * DAY;
            let next_boundary = (day_start_local + DAY - off).min(end);
            // If the offset changes inside the day (DST), the next iteration re-evaluates.
            let seg_end = if next_boundary <= t { end } else { next_boundary };
            let ms = seg_end - t;
            let key = (civil_date(day_start_local / DAY), iv.subject_type, iv.subject_id.clone(), iv.level);
            let e = acc.entry(key).or_insert((0, 0));
            e.0 += ms;
            if iv.is_primary {
                e.1 += ms;
            }
            t = seg_end;
        }
    }
    acc.into_iter()
        .map(|((day, st, sid, level), (ms, pms))| DailyRow {
            day,
            subject_type: st,
            subject_id: sid,
            level,
            seconds: ms / 1000,
            as_primary_seconds: pms / 1000,
        })
        .collect()
}

/// Days since 1970-01-01 → "YYYY-MM-DD" (proleptic Gregorian; Howard Hinnant's algorithm).
pub fn civil_date(days: i64) -> String {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02}")
}

// ─── concurrent switch review ────────────────────────────────────────────────

pub const DEFAULT_REVIEW_WINDOW_MS: i64 = 120_000;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Review {
    pub id: String,
    pub switch_a: String,
    pub switch_b: String,
}

/// Concurrent, close-together switches from different devices whose results differ
/// (SYNC.md §5.7). Needs server seqs; pending ops (no seq) are skipped until acked.
pub fn reviews(ops: &[FrontOp], folded: &FoldResult, window_ms: i64) -> Vec<Review> {
    let by_id: HashMap<&str, &FrontOp> = ops.iter().map(|o| (o.id.as_str(), o)).collect();
    let rows: Vec<&SwitchRow> = folded.switches.iter().filter(|r| !r.retracted).collect();
    let mut out = Vec::new();
    for (i, a) in rows.iter().enumerate() {
        for b in rows[i + 1..].iter() {
            if b.occurred_at - a.occurred_at > window_ms {
                break; // rows are time-sorted
            }
            let (Some(oa), Some(ob)) = (by_id.get(a.id.as_str()), by_id.get(b.id.as_str())) else { continue };
            let (Some(sa), Some(sb)) = (oa.seq, ob.seq) else { continue };
            if oa.device_id == ob.device_id {
                continue;
            }
            let concurrent = sa > ob.seen_seq && sb > oa.seen_seq;
            if concurrent && a.resulting_front != b.resulting_front {
                let (x, y) = if a.id < b.id { (a, b) } else { (b, a) };
                let mut h = Sha256::new();
                h.update(&x.id);
                h.update(b"|");
                h.update(&y.id);
                let d = h.finalize();
                out.push(Review {
                    id: d[..16].iter().map(|b| format!("{b:02x}")).collect(),
                    switch_a: x.id.clone(),
                    switch_b: y.id.clone(),
                });
            }
        }
    }
    out.sort_by(|a, b| a.id.cmp(&b.id));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(id: &str) -> Entry {
        Entry { subject_type: SubjectType::Member, subject_id: id.into(), level: Level::Front, is_primary: false }
    }
    fn p(id: &str) -> Entry {
        Entry { is_primary: true, ..m(id) }
    }
    fn lvl(id: &str, l: Level) -> Entry {
        Entry { level: l, ..m(id) }
    }

    fn fop(id: &str, at: i64, node: u32, action: FrontAction) -> FrontOp {
        FrontOp {
            id: id.into(),
            action,
            occurred_at: at,
            tz_offset_min: 0,
            hlc: Hlc::new(at as u64, 0, node),
            device_id: format!("d{node}"),
            seq: None,
            seen_seq: 0,
            was_offline: false,
        }
    }
    fn sw(id: &str, at: i64, entries: Vec<Entry>) -> FrontOp {
        fop(id, at, 1, FrontAction::Switch(SwitchPayload { entries, based_on: None, note: None, notify: Notify::Default }))
    }
    fn add(id: &str, at: i64, e: Entry) -> FrontOp {
        fop(id, at, 1, FrontAction::Add(AddPayload { entry: e, position: None, based_on: None, notify: Notify::Default }))
    }
    fn rm(id: &str, at: i64, who: &str) -> FrontOp {
        fop(id, at, 1, FrontAction::Remove(RemovePayload { subject_type: SubjectType::Member, subject_id: who.into(), based_on: None, notify: Notify::Default }))
    }
    fn names(f: &Front) -> Vec<&str> {
        f.iter().map(|e| e.subject_id.as_str()).collect()
    }

    #[test]
    fn switch_then_add_then_remove() {
        let r = fold(&[sw("s1", 100, vec![p("kai")]), add("s2", 200, m("june")), rm("s3", 300, "kai")]);
        assert_eq!(names(&r.current), ["june"]);
        assert_eq!(r.switches.len(), 3);
        let kai: Vec<_> = r.intervals.iter().filter(|i| i.subject_id == "kai").collect();
        assert_eq!(kai.len(), 1);
        assert_eq!((kai[0].start_at, kai[0].end_at), (100, Some(300)));
        assert!(kai[0].is_primary);
        let june: Vec<_> = r.intervals.iter().filter(|i| i.subject_id == "june").collect();
        assert_eq!((june[0].start_at, june[0].end_at, june[0].position), (200, None, 1));
    }

    #[test]
    fn backdated_add_is_rebased_before_later_switch() {
        // switch to kai at 100, switch to rin at 300; an add of june arriving later but dated 200
        let ops = [sw("s1", 100, vec![p("kai")]), sw("s3", 300, vec![p("rin")]), add("s2", 200, m("june"))];
        let r = fold(&ops);
        assert_eq!(names(&r.current), ["rin"]);
        let june = r.intervals.iter().find(|i| i.subject_id == "june").unwrap();
        assert_eq!((june.start_at, june.end_at), (200, Some(300)));
    }

    #[test]
    fn order_of_arrival_does_not_matter() {
        let ops = vec![
            sw("a", 100, vec![p("kai"), m("june")]),
            add("b", 150, lvl("rin", Level::Cocon)),
            rm("c", 170, "june"),
            fop("d", 180, 2, FrontAction::Update(UpdatePayload { subject_type: SubjectType::Member, subject_id: "rin".into(), level: Some(Level::Front), is_primary: Some(true), position: None, based_on: None })),
            fop("e", 190, 3, FrontAction::Retract(TargetPayload { target_op_id: "c".into() })),
        ];
        let base = fold(&ops);
        let mut rev = ops.clone();
        rev.reverse();
        assert_eq!(fold(&rev), base);
        rev.rotate_left(2);
        assert_eq!(fold(&rev), base);
    }

    #[test]
    fn retract_and_unretract_latest_wins() {
        let s2 = sw("s2", 200, vec![p("june")]);
        let r1 = fop("r1", 210, 1, FrontAction::Retract(TargetPayload { target_op_id: "s2".into() }));
        let r = fold(&[sw("s1", 100, vec![p("kai")]), s2.clone(), r1.clone()]);
        assert_eq!(names(&r.current), ["kai"]);
        assert!(r.switches.iter().find(|s| s.id == "s2").unwrap().retracted);
        let u = fop("u1", 220, 1, FrontAction::Unretract(TargetPayload { target_op_id: "s2".into() }));
        let r = fold(&[sw("s1", 100, vec![p("kai")]), s2, r1, u]);
        assert_eq!(names(&r.current), ["june"]);
    }

    #[test]
    fn amend_moves_time_and_replaces_entries() {
        let ops = [
            sw("s1", 100, vec![p("kai")]),
            sw("s2", 300, vec![p("june")]),
            fop("a1", 400, 1, FrontAction::Amend(AmendPayload { target_op_id: "s2".into(), occurred_at: Some(50), entries: Some(vec![p("rin")]), note: Some(Some("oops".into())) })),
        ];
        let r = fold(&ops);
        // s2 now happens at 50 (as rin), before s1 → kai is current
        assert_eq!(names(&r.current), ["kai"]);
        let s2 = r.switches.iter().find(|s| s.id == "s2").unwrap();
        assert_eq!((s2.occurred_at, s2.amended, s2.note.as_deref()), (50, true, Some("oops")));
        assert_eq!(r.switches[0].id, "s2");
    }

    #[test]
    fn normalize_levels_and_primary() {
        let mut f = vec![lvl("a", Level::Present), p("b"), lvl("c", Level::Cocon), p("d"), m("b")];
        normalize(&mut f);
        assert_eq!(names(&f), ["b", "d", "c", "a"]);
        assert!(f[0].is_primary && !f[1].is_primary);
        let mut f = vec![Entry { is_primary: true, ..lvl("x", Level::Cocon) }];
        normalize(&mut f);
        assert!(!f[0].is_primary);
    }

    #[test]
    fn primary_change_splits_interval_order_change_does_not() {
        let ops = [
            sw("s1", 100, vec![p("kai"), m("june")]),
            fop("u1", 200, 1, FrontAction::Update(UpdatePayload { subject_type: SubjectType::Member, subject_id: "june".into(), level: None, is_primary: Some(true), position: None, based_on: None })),
            fop("u2", 300, 1, FrontAction::Update(UpdatePayload { subject_type: SubjectType::Member, subject_id: "kai".into(), level: None, is_primary: None, position: Some(1), based_on: None })),
        ];
        let r = fold(&ops);
        let kai: Vec<_> = r.intervals.iter().filter(|i| i.subject_id == "kai").collect();
        assert_eq!(kai.len(), 2, "kai primary→not primary splits once; reorder doesn't");
        assert_eq!(names(&r.current), ["june", "kai"]);
    }

    #[test]
    fn subsystem_and_state_subjects() {
        let g = Entry { subject_type: SubjectType::Group, subject_id: "stars".into(), level: Level::Front, is_primary: true };
        let s = Entry { subject_type: SubjectType::State, subject_id: "blurry".into(), level: Level::Cocon, is_primary: false };
        let r = fold(&[sw("s1", 100, vec![s.clone(), g.clone()])]);
        assert_eq!(r.current, vec![g, s]);
    }

    #[test]
    fn daily_split_across_midnight_with_offset() {
        let day = 86_400_000;
        let iv = Interval {
            id: "x".into(), subject_type: SubjectType::Member, subject_id: "kai".into(), level: Level::Front,
            is_primary: true, position: 0,
            start_at: day - 3_600_000 - 2 * 3_600_000, // 21:00 UTC day 0 = 23:00 local (+2h)
            end_at: Some(day + 3_600_000 - 2 * 3_600_000), // 23:00 UTC = 01:00 local day 1
            start_switch_id: "s".into(), end_switch_id: None, start_tz_offset_min: 120,
        };
        let rows = daily(&[iv], 0, |_| 120);
        assert_eq!(rows.len(), 2);
        assert_eq!((rows[0].day.as_str(), rows[0].seconds), ("1970-01-01", 3600));
        assert_eq!((rows[1].day.as_str(), rows[1].seconds, rows[1].as_primary_seconds), ("1970-01-02", 3600, 3600));
    }

    #[test]
    fn civil_dates() {
        assert_eq!(civil_date(0), "1970-01-01");
        assert_eq!(civil_date(20_719), "2026-09-23");
        assert_eq!(civil_date(-1), "1969-12-31");
        assert_eq!(civil_date(11_016), "2000-02-29");
    }

    #[test]
    fn concurrent_close_switches_make_a_review() {
        let mut a = sw("a", 1000, vec![p("kai")]);
        a.device_id = "phone".into();
        a.seq = Some(10);
        a.seen_seq = 5;
        let mut b = sw("b", 1060, vec![p("june")]);
        b.device_id = "desk".into();
        b.seq = Some(11);
        b.seen_seq = 5;
        let ops = vec![a.clone(), b.clone()];
        let r = fold(&ops);
        assert_eq!(reviews(&ops, &r, DEFAULT_REVIEW_WINDOW_MS).len(), 1);
        // b had seen a → causal, no review
        b.seen_seq = 10;
        let ops = vec![a.clone(), b.clone()];
        assert!(reviews(&ops, &fold(&ops), DEFAULT_REVIEW_WINDOW_MS).is_empty());
        // too far apart
        b.seen_seq = 5;
        b.occurred_at = 1000 + DEFAULT_REVIEW_WINDOW_MS + 1;
        let ops = vec![a, b];
        assert!(reviews(&ops, &fold(&ops), DEFAULT_REVIEW_WINDOW_MS).is_empty());
    }

    #[test]
    fn payload_json_shapes() {
        let o: SwitchPayload = serde_json::from_str(r#"{"entries":[{"subject_type":"member","subject_id":"k","is_primary":true}]}"#).unwrap();
        assert_eq!(o.entries[0].level, Level::Front);
        let a: AmendPayload = serde_json::from_str(r#"{"target_op_id":"x","note":null}"#).unwrap();
        assert_eq!(a.note, Some(None));
        let a: AmendPayload = serde_json::from_str(r#"{"target_op_id":"x"}"#).unwrap();
        assert_eq!(a.note, None);
    }
}
