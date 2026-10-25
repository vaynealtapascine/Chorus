//! Append-only own-front history and configuration for D-080.
//!
//! Presence uses human time (including amendments); following controls use write order. Editing
//! yesterday's presence must not move today's Resume command into yesterday, or silently resume
//! a manual override. This fold retains both orders and never mutates the source operations.
//! Effective/inherited history is a separate projection; these intervals describe OWN presence.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::front::{self, FrontAction, FrontOp, Notify, Subject, SubjectType};
use crate::front_system::{self, Front, Layer, Presence, ResolvedFront, System};
use crate::hlc::Hlc;
use crate::lww::Clocks;
use crate::op::{self, Op};

/// A sparse field patch. Explicit null restores a purpose's legacy default; missing leaves it
/// unchanged. New fronts still start with explicit defaults (including no notification layers).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Definition {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "nullable", skip_serializing_if = "Option::is_none")]
    pub follow_source: Option<Option<String>>,
    #[serde(default, deserialize_with = "nullable", skip_serializing_if = "Option::is_none")]
    pub authorship_layers: Option<Option<BTreeSet<String>>>,
    #[serde(default, deserialize_with = "nullable", skip_serializing_if = "Option::is_none")]
    pub notification_layers: Option<Option<BTreeSet<String>>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deleted: Option<bool>,
}

fn nullable<'de, D: serde::Deserializer<'de>, T: Deserialize<'de>>(d: D) -> Result<Option<Option<T>>, D::Error> {
    Option::<T>::deserialize(d).map(Some)
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayerPatch {
    pub layer_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub level: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deleted: Option<bool>,
}

/// `front.change` always names the front in entity_id. The op ID identifies this history item;
/// its targets must belong to this same front, even when a target arrives after its amendment.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum Change {
    Switch {
        entries: Vec<Presence>,
        #[serde(default)]
        based_on: Option<String>,
        #[serde(default)]
        note: Option<String>,
        #[serde(default)]
        notify: Notify,
    },
    Add {
        entry: Presence,
        #[serde(default)]
        position: Option<usize>,
        #[serde(default)]
        based_on: Option<String>,
        #[serde(default)]
        notify: Notify,
    },
    Remove {
        subject_type: SubjectType,
        subject_id: String,
        #[serde(default)]
        based_on: Option<String>,
        #[serde(default)]
        notify: Notify,
    },
    Update {
        subject_type: SubjectType,
        subject_id: String,
        #[serde(default)]
        layer_id: Option<String>,
        #[serde(default)]
        is_primary: Option<bool>,
        #[serde(default)]
        position: Option<usize>,
        #[serde(default)]
        based_on: Option<String>,
    },
    Resume,
    Retract {
        target_op_id: String,
    },
    Unretract {
        target_op_id: String,
    },
    Amend {
        target_op_id: String,
        #[serde(default)]
        occurred_at: Option<i64>,
        #[serde(default)]
        entries: Option<Vec<Presence>>,
        #[serde(default, deserialize_with = "nullable", skip_serializing_if = "Option::is_none")]
        note: Option<Option<String>>,
    },
}

// Decode from the existing JSON value rather than serde's internally tagged content buffer.
// This keeps the strict action schema while avoiding a large per-variant wasm visitor.
impl<'de> Deserialize<'de> for Change {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let fields = serde_json::Map::<String, serde_json::Value>::deserialize(deserializer)?;
        parse_change(fields).map_err(serde::de::Error::custom)
    }
}

fn required<T: serde::de::DeserializeOwned>(
    fields: &mut serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Result<T, String> {
    serde_json::from_value(fields.remove(key).ok_or_else(|| format!("missing {key}"))?)
        .map_err(|e| format!("{key}: {e}"))
}

fn optional<T: serde::de::DeserializeOwned>(
    fields: &mut serde_json::Map<String, serde_json::Value>,
    key: &str,
) -> Result<Option<T>, String> {
    fields
        .remove(key)
        .map(serde_json::from_value::<Option<T>>)
        .transpose()
        .map(Option::flatten)
        .map_err(|e| format!("{key}: {e}"))
}

fn notification(fields: &mut serde_json::Map<String, serde_json::Value>) -> Result<Notify, String> {
    if fields.contains_key("notify") { required(fields, "notify") } else { Ok(Notify::Default) }
}

fn parse_change(mut fields: serde_json::Map<String, serde_json::Value>) -> Result<Change, String> {
    let action: String = required(&mut fields, "action")?;
    let change = match action.as_str() {
        "switch" => Change::Switch {
            entries: required(&mut fields, "entries")?,
            based_on: optional(&mut fields, "based_on")?,
            note: optional(&mut fields, "note")?,
            notify: notification(&mut fields)?,
        },
        "add" => Change::Add {
            entry: required(&mut fields, "entry")?,
            position: front::wire_position(optional(&mut fields, "position")?),
            based_on: optional(&mut fields, "based_on")?,
            notify: notification(&mut fields)?,
        },
        "remove" => Change::Remove {
            subject_type: required(&mut fields, "subject_type")?,
            subject_id: required(&mut fields, "subject_id")?,
            based_on: optional(&mut fields, "based_on")?,
            notify: notification(&mut fields)?,
        },
        "update" => Change::Update {
            subject_type: required(&mut fields, "subject_type")?,
            subject_id: required(&mut fields, "subject_id")?,
            layer_id: optional(&mut fields, "layer_id")?,
            is_primary: optional(&mut fields, "is_primary")?,
            position: front::wire_position(optional(&mut fields, "position")?),
            based_on: optional(&mut fields, "based_on")?,
        },
        "resume" => Change::Resume,
        "retract" => Change::Retract { target_op_id: required(&mut fields, "target_op_id")? },
        "unretract" => Change::Unretract { target_op_id: required(&mut fields, "target_op_id")? },
        "amend" => Change::Amend {
            target_op_id: required(&mut fields, "target_op_id")?,
            occurred_at: optional(&mut fields, "occurred_at")?,
            entries: optional(&mut fields, "entries")?,
            note: fields
                .remove("note")
                .map(serde_json::from_value::<Option<String>>)
                .transpose()
                .map_err(|e| e.to_string())?,
        },
        _ => return Err(format!("unknown front action {action}")),
    };
    if let Some(field) = fields.keys().next() {
        return Err(format!("unknown field {field} for {action}"));
    }
    Ok(change)
}

impl Change {
    pub fn is_presence(&self) -> bool {
        matches!(self, Self::Switch { .. } | Self::Add { .. } | Self::Remove { .. } | Self::Update { .. })
    }

    fn kind(&self) -> &'static str {
        match self {
            Self::Switch { .. } => "switch",
            Self::Add { .. } => "add",
            Self::Remove { .. } => "remove",
            Self::Update { .. } => "update",
            Self::Resume => "resume",
            Self::Retract { .. } => "retract",
            Self::Unretract { .. } => "unretract",
            Self::Amend { .. } => "amend",
        }
    }

    fn based_on(&self) -> Option<&str> {
        match self {
            Self::Switch { based_on, .. }
            | Self::Add { based_on, .. }
            | Self::Remove { based_on, .. }
            | Self::Update { based_on, .. } => based_on.as_deref(),
            _ => None,
        }
    }

    fn notify(&self) -> Notify {
        match self {
            Self::Switch { notify, .. } | Self::Add { notify, .. } | Self::Remove { notify, .. } => *notify,
            _ => Notify::Default,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    Define(Definition),
    Layer(LayerPatch),
    Change(Change),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Event {
    pub id: String,
    pub front_id: String,
    pub hlc: Hlc,
    pub occurred_at: i64,
    /// Corrected operation/record time, distinct from a backdated presence effective time.
    pub recorded_at: i64,
    /// Legacy `time_source:user` envelopes did not retain a corrected creation time. Their
    /// HLC estimate is marked, and bounded by receipt when the server stamp is available.
    pub recorded_time_estimated: bool,
    pub history_only: bool,
    pub tz_offset_min: i32,
    pub device_id: String,
    pub seq: Option<i64>,
    pub seen_seq: i64,
    pub action: Action,
    /// Retain legacy normalization and position rules for old clients' Body operations.
    pub legacy: Option<FrontAction>,
}

pub fn is_named_kind(kind: &str) -> bool {
    matches!(kind, "front.define" | "front.layer" | "front.change")
}

impl Event {
    /// Parse only this account's supported front operations. Unknown versions and purged payloads
    /// remain opaque. This parser is not an authorization check and never reads another scope.
    pub fn from_op(account: &str, o: &Op) -> Result<Option<Self>, String> {
        if o.scope != format!("account:{account}") || o.v > op::CURRENT_V || op::is_purged(&o.payload) {
            return Ok(None);
        }
        let mut legacy = None;
        let mut effective_at = None;
        let mut history_only = false;
        let action = match o.kind.as_str() {
            "front.define" => Action::Define(serde_json::from_value(o.payload.clone()).map_err(|e| e.to_string())?),
            "front.layer" => Action::Layer(serde_json::from_value(o.payload.clone()).map_err(|e| e.to_string())?),
            "front.change" => {
                let (change, at, history) = change_payload(&o.payload)?;
                effective_at = at;
                history_only = history;
                Action::Change(change)
            }
            _ => match FrontOp::from_op(o) {
                Ok(f) => {
                    let change = legacy_change(&f.action);
                    legacy = Some(f.action);
                    Action::Change(change)
                }
                Err(front::FrontError::NotFront(_)) => return Ok(None),
                Err(e) => return Err(e.to_string()),
            },
        };
        validate_action(&action)?;
        let front_id = if legacy.is_some() || matches!(action, Action::Layer(_)) {
            account.into()
        } else {
            o.entity().ok_or("named-front operation needs entity_id")?.into()
        };
        let recorded_time_estimated = o.time_source == crate::time::TimeSource::User;
        let recorded_at = if recorded_time_estimated {
            (o.hlc.pt.min(i64::MAX as u64) as i64).min(o.received_at.unwrap_or(i64::MAX))
        } else {
            o.time()
        };
        Ok(Some(Self {
            id: o.id.clone(),
            front_id,
            hlc: o.hlc,
            occurred_at: effective_at.unwrap_or(o.time()),
            recorded_at,
            recorded_time_estimated,
            history_only,
            tz_offset_min: o.tz_offset_min,
            device_id: o.device_id.clone().unwrap_or_else(|| format!("{:08x}", o.hlc.node)),
            seq: o.seq,
            seen_seq: o.seen_seq,
            action,
            legacy,
        }))
    }
}

/// Presence timing/intent is orthogonal to the action's member/layer payload. New writers keep
/// the envelope in auto time so the server corrects record time, and use effective_at for an
/// explicitly chosen presence time. History editors can insert a missed entry without a live
/// override. Amend/undo/redo always follow D-083 and cannot opt into live override controls.
fn change_payload(value: &serde_json::Value) -> Result<(Change, Option<i64>, bool), String> {
    let mut payload = value.clone();
    let fields = payload.as_object_mut().ok_or("front.change payload must be an object")?;
    let effective_at = fields
        .remove("effective_at")
        .map(|v| v.as_i64().ok_or("effective_at must be an integer timestamp"))
        .transpose()?;
    let history = fields.remove("history_only");
    let history_only =
        history.as_ref().map(|v| v.as_bool().ok_or("history_only must be a boolean")).transpose()?.unwrap_or(false);
    let change = parse_change(std::mem::take(fields))?;
    if !change.is_presence() && (effective_at.is_some() || history.is_some()) {
        return Err("effective_at and history_only apply only to switch/add/remove/update".into());
    }
    Ok((change, effective_at, history_only))
}

fn validate_action(action: &Action) -> Result<(), String> {
    let id = |s: &str| -> Result<(), String> {
        if s.is_empty() { Err("identifiers must not be empty".into()) } else { Ok(()) }
    };
    let entries = |entries: &[Presence]| -> Result<(), String> {
        for e in entries {
            id(&e.subject_id)?;
            id(&e.layer_id)?;
        }
        Ok(())
    };
    match action {
        Action::Define(p) => {
            if let Some(Some(source)) = &p.follow_source {
                id(source)?;
            }
            for ids in [&p.authorship_layers, &p.notification_layers].into_iter().flatten().flatten() {
                for s in ids {
                    id(s)?;
                }
            }
        }
        Action::Layer(p) => id(&p.layer_id)?,
        Action::Change(change) => match change {
            Change::Switch { entries: es, .. } => entries(es)?,
            Change::Add { entry, .. } => entries(std::slice::from_ref(entry))?,
            Change::Remove { subject_id, .. } | Change::Update { subject_id, .. } => id(subject_id)?,
            Change::Retract { target_op_id } | Change::Unretract { target_op_id } => id(target_op_id)?,
            Change::Amend { target_op_id, entries: es, .. } => {
                id(target_op_id)?;
                if let Some(es) = es {
                    entries(es)?;
                }
            }
            Change::Resume => {}
        },
    }
    if let Action::Change(Change::Update { layer_id: Some(layer), .. }) = action {
        id(layer)?;
    }
    Ok(())
}

fn presence(e: &front::Entry) -> Presence {
    Presence {
        subject_type: e.subject_type,
        subject_id: e.subject_id.clone(),
        layer_id: e.level.as_str().into(),
        is_primary: e.is_primary,
    }
}

fn legacy_change(a: &FrontAction) -> Change {
    match a {
        FrontAction::Switch(p) => Change::Switch {
            entries: p.entries.iter().map(presence).collect(),
            based_on: p.based_on.clone(),
            note: p.note.clone(),
            notify: p.notify,
        },
        FrontAction::Add(p) => Change::Add {
            entry: presence(&p.entry),
            position: p.position,
            based_on: p.based_on.clone(),
            notify: p.notify,
        },
        FrontAction::Remove(p) => Change::Remove {
            subject_type: p.subject_type,
            subject_id: p.subject_id.clone(),
            based_on: p.based_on.clone(),
            notify: p.notify,
        },
        FrontAction::Update(p) => Change::Update {
            subject_type: p.subject_type,
            subject_id: p.subject_id.clone(),
            layer_id: p.level.map(|l| l.as_str().into()),
            is_primary: p.is_primary,
            position: p.position,
            based_on: p.based_on.clone(),
        },
        FrontAction::Retract(p) => Change::Retract { target_op_id: p.target_op_id.clone() },
        FrontAction::Unretract(p) => Change::Unretract { target_op_id: p.target_op_id.clone() },
        FrontAction::Amend(p) => Change::Amend {
            target_op_id: p.target_op_id.clone(),
            occurred_at: p.occurred_at,
            entries: p.entries.as_ref().map(|es| es.iter().map(presence).collect()),
            note: p.note.clone(),
        },
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct HistoryRow {
    pub id: String,
    pub kind: &'static str,
    pub occurred_at: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub recorded_at: Option<i64>,
    #[serde(skip_serializing_if = "is_false")]
    pub recorded_time_estimated: bool,
    #[serde(skip_serializing_if = "is_false")]
    pub history_only: bool,
    pub hlc: Hlc,
    pub tz_offset_min: i32,
    pub device_id: String,
    pub based_on: Option<String>,
    pub change: Change,
    pub entries: Vec<Presence>,
    pub resulting_front: Vec<Presence>,
    pub note: Option<String>,
    pub notify: Notify,
    pub retracted: bool,
    pub amended: bool,
}

fn is_false(value: &bool) -> bool {
    !*value
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Interval {
    pub id: String,
    pub front_id: String,
    pub subject_type: SubjectType,
    pub subject_id: String,
    pub layer_id: String,
    pub is_primary: bool,
    pub position: usize,
    pub start_at: i64,
    pub end_at: Option<i64>,
    pub start_switch_id: String,
    pub end_switch_id: Option<String>,
    pub start_tz_offset_min: i32,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct History {
    pub switches: Vec<HistoryRow>,
    pub intervals: Vec<Interval>,
    pub current: Vec<Presence>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct FoldResult {
    pub system: System,
    pub resolved: BTreeMap<String, ResolvedFront>,
    /// Own history only, including deleted fronts. Inherited history must not be inferred by
    /// applying the current follow source retrospectively to these rows.
    pub histories: BTreeMap<String, History>,
    pub reviews: BTreeMap<String, Vec<front::Review>>,
}

/// Fold a set of supported operations, retaining malformed/unknown operations in the caller's
/// log rather than projecting them. Call Event::from_op when a validation error is needed.
pub fn fold<'a>(account: &str, ops: impl IntoIterator<Item = &'a Op>) -> FoldResult {
    fold_events(account, &parse_events(account, ops), true)
}

pub(crate) fn current_from_events(account: &str, events: &[Event]) -> crate::front_projection::Current {
    let f = fold_events(account, events, false);
    crate::front_projection::Current { system: f.system, resolved: f.resolved }
}

fn fold_events(account: &str, events: &[Event], collect_history: bool) -> FoldResult {
    let mut system = System::legacy(account, vec![]);
    let mut clocks: BTreeMap<String, Clocks> = BTreeMap::new();
    let mut layer_clocks: BTreeMap<String, Clocks> = BTreeMap::new();
    let mut changes: BTreeMap<String, Vec<&Event>> = BTreeMap::new();
    // dedupe gives canonical write order. Sparse definitions merge fieldwise; presence edits
    // and explicit Resume commands use this order only for their sticky control state.
    for e in events {
        match &e.action {
            Action::Layer(p) => apply_layer(&mut system, layer_clocks.entry(p.layer_id.clone()).or_default(), p, e.hlc),
            _ => {
                let f = system
                    .fronts
                    .entry(e.front_id.clone())
                    .or_insert_with(|| Front::new(e.front_id.clone(), "Front".into()));
                apply_control(f, clocks.entry(e.front_id.clone()).or_default(), e);
                if matches!(e.action, Action::Change(_)) {
                    changes.entry(e.front_id.clone()).or_default().push(e);
                }
            }
        }
    }
    let mut histories = BTreeMap::new();
    let mut reviews = BTreeMap::new();
    for (id, changes) in changes {
        let history = fold_own_mode(&id, &changes, collect_history);
        if collect_history {
            let review = own_reviews(&changes, &history, front::DEFAULT_REVIEW_WINDOW_MS);
            if !review.is_empty() {
                reviews.insert(id.clone(), review);
            }
        }
        if let Some(front) = system.fronts.get_mut(&id) {
            front.own = history.current.clone();
        }
        if collect_history {
            histories.insert(id, history);
        }
    }
    let resolved = system.resolve();
    FoldResult { system, resolved, histories, reviews }
}

pub(crate) fn apply_layer(system: &mut System, clocks: &mut Clocks, p: &LayerPatch, hlc: Hlc) {
    let layer = system.layers.entry(p.layer_id.clone()).or_insert_with(|| Layer {
        id: p.layer_id.clone(),
        name: p.layer_id.clone(),
        level: 0,
        deleted: false,
    });
    if let Some(name) = &p.name
        && clocks.wins("name", hlc)
    {
        layer.name = name.clone();
        clocks.0.insert("name".into(), hlc);
    }
    if let Some(level) = p.level
        && clocks.wins("level", hlc)
    {
        layer.level = level;
        clocks.0.insert("level".into(), hlc);
    }
    if let Some(deleted) = p.deleted
        && clocks.wins("deleted", hlc)
    {
        layer.deleted = deleted;
        clocks.0.insert("deleted".into(), hlc);
    }
}

pub(crate) fn parse_events<'a>(account: &str, ops: impl IntoIterator<Item = &'a Op>) -> Vec<Event> {
    crate::model::dedupe(ops).into_iter().filter_map(|o| Event::from_op(account, o).ok().flatten()).collect()
}

/// Apply one event's configuration/control effect in canonical HLC/id order. Presence is folded
/// separately in human time. Effective history reuses this rule at each corrected record time.
pub(crate) fn apply_control(f: &mut Front, clocks: &mut Clocks, e: &Event) {
    match &e.action {
        Action::Define(p) => {
            if let Some(name) = &p.name
                && clocks.wins("name", e.hlc)
            {
                f.name = name.clone();
                clocks.0.insert("name".into(), e.hlc);
            }
            if let Some(source) = &p.follow_source
                && clocks.wins("follow_source", e.hlc)
            {
                f.set_follow(source.clone(), e.hlc);
                clocks.0.insert("follow_source".into(), e.hlc);
            }
            if let Some(layers) = &p.authorship_layers
                && clocks.wins("authorship_layers", e.hlc)
            {
                f.authorship_layers = layers.clone();
                clocks.0.insert("authorship_layers".into(), e.hlc);
            }
            if let Some(layers) = &p.notification_layers
                && clocks.wins("notification_layers", e.hlc)
            {
                f.notification_layers = layers.clone();
                clocks.0.insert("notification_layers".into(), e.hlc);
            }
            if let Some(deleted) = p.deleted
                && clocks.wins("deleted", e.hlc)
            {
                f.deleted = deleted;
                clocks.0.insert("deleted".into(), e.hlc);
            }
        }
        Action::Change(Change::Resume) => f.resume_following(),
        Action::Change(change) if change.is_presence() && !e.history_only => {
            // D-083: history corrections neither pause following nor resume an override.
            f.overridden = f.follow.is_some();
        }
        _ => {}
    }
}

/// Apply a new, unamended presence action. Append callers guard against backdates and known
/// targets first; the own-history fold uses the same action rule with its amendment bundle.
pub(crate) fn apply_presence(entries: &mut Vec<Presence>, e: &Event) {
    if let Action::Change(change) = &e.action {
        apply_change(entries, e, change);
    }
}

fn apply_change(entries: &mut Vec<Presence>, e: &Event, change: &Change) {
    if let Some(action) = &e.legacy {
        let amended = match change {
            Change::Switch { entries, .. } => Some(entries),
            _ => None,
        };
        apply_legacy(entries, action, amended);
    } else {
        apply(entries, change);
    }
}

fn subject(e: &Presence) -> Subject {
    Subject { subject_type: e.subject_type, subject_id: e.subject_id.clone() }
}

/// New positions are absolute positions in the independent ordered set, never positions within
/// a semantic layer. Changing a layer does not move a member unless a position is supplied.
fn apply(entries: &mut Vec<Presence>, change: &Change) {
    match change {
        Change::Switch { entries: next, .. } => *entries = next.clone(),
        Change::Add { entry, position, .. } => {
            entries.retain(|e| subject(e) != subject(entry));
            if entry.is_primary {
                entries.iter_mut().for_each(|e| e.is_primary = false);
            }
            entries.insert(position.unwrap_or(entries.len()).min(entries.len()), entry.clone());
        }
        Change::Remove { subject_type, subject_id, .. } => {
            entries.retain(|e| !(e.subject_type == *subject_type && e.subject_id == *subject_id));
        }
        Change::Update { subject_type, subject_id, layer_id, is_primary, position, .. } => {
            let Some(i) = entries.iter().position(|e| e.subject_type == *subject_type && e.subject_id == *subject_id)
            else {
                return;
            };
            let mut e = entries.remove(i);
            if let Some(layer) = layer_id {
                e.layer_id = layer.clone();
            }
            if let Some(primary) = is_primary {
                if *primary {
                    entries.iter_mut().for_each(|e| e.is_primary = false);
                }
                e.is_primary = *primary;
            }
            entries.insert(position.unwrap_or(i).min(entries.len()), e);
        }
        _ => {}
    }
    front_system::normalize(entries);
}

/// Old clients know only three layers. Run their existing delta semantics on those entries,
/// then merge into their former slots, retaining custom entries in order. A full old-client
/// switch remains an explicit replacement; it never overwrites any other named front.
fn apply_legacy(entries: &mut Vec<Presence>, action: &FrontAction, amended: Option<&Vec<Presence>>) {
    if let FrontAction::Switch(_) = action {
        let mut next = amended.cloned().unwrap_or_else(|| match legacy_change(action) {
            Change::Switch { entries, .. } => entries,
            _ => unreachable!(),
        });
        normalize_legacy_slots(&mut next);
        *entries = next;
        return;
    }
    if let FrontAction::Remove(p) = action {
        entries.retain(|e| !(e.subject_type == p.subject_type && e.subject_id == p.subject_id));
        return;
    }
    if let FrontAction::Update(p) = action
        && !entries.iter().any(|e| e.subject_type == p.subject_type && e.subject_id == p.subject_id)
    {
        // The legacy operation is a complete no-op for an absent target, including its primary
        // flag. Do not clear the current primary while merging the unchanged default slots.
        return;
    }
    // Include the legacy delta's target even if a newer client put it in a custom layer.
    let target = match action {
        FrontAction::Add(p) => Some(p.entry.subject()),
        FrontAction::Update(p) => Some(Subject { subject_type: p.subject_type, subject_id: p.subject_id.clone() }),
        _ => None,
    };
    let mut old = Vec::new();
    for e in entries.iter() {
        if let Some(level) = legacy_level(&e.layer_id) {
            old.push(front::Entry {
                subject_type: e.subject_type,
                subject_id: e.subject_id.clone(),
                level,
                is_primary: e.is_primary,
            });
        } else if target.as_ref() == Some(&subject(e)) {
            old.push(front::Entry {
                subject_type: e.subject_type,
                subject_id: e.subject_id.clone(),
                level: front::Level::Front,
                is_primary: e.is_primary,
            });
        }
    }
    front::apply(&mut old, action, None);
    let mut old = old.iter().map(presence);
    let mut merged = Vec::new();
    for e in entries.iter() {
        if legacy_level(&e.layer_id).is_some() || target.as_ref() == Some(&subject(e)) {
            if let Some(e) = old.next() {
                merged.push(e);
            }
        } else {
            merged.push(e.clone());
        }
    }
    merged.extend(old);
    // An explicit primary chosen by an old client wins over an unrelated custom primary.
    let primary = match action {
        FrontAction::Add(p) if p.entry.is_primary && p.entry.level == front::Level::Front => Some(p.entry.subject()),
        FrontAction::Update(p) if p.is_primary == Some(true) => target,
        _ => None,
    };
    if let Some(primary) = primary {
        for e in &mut merged {
            e.is_primary &= subject(e) == primary;
        }
    }
    front_system::normalize(&mut merged);
    *entries = merged;
}

fn legacy_level(id: &str) -> Option<front::Level> {
    match id {
        "front" => Some(front::Level::Front),
        "cocon" => Some(front::Level::Cocon),
        "present" => Some(front::Level::Present),
        _ => None,
    }
}

fn normalize_legacy_slots(entries: &mut Vec<Presence>) {
    let mut old: Vec<_> = entries
        .iter()
        .filter_map(|e| {
            Some(front::Entry {
                subject_type: e.subject_type,
                subject_id: e.subject_id.clone(),
                level: legacy_level(&e.layer_id)?,
                is_primary: e.is_primary,
            })
        })
        .collect();
    front::normalize(&mut old);
    let mut old = old.iter().map(presence);
    let mut merged: Vec<_> = entries
        .iter()
        .filter_map(|e| if legacy_level(&e.layer_id).is_some() { old.next() } else { Some(e.clone()) })
        .collect();
    front_system::normalize(&mut merged);
    *entries = merged;
}

pub(crate) fn fold_own(front_id: &str, events: &[&Event]) -> History {
    fold_own_mode(front_id, events, true)
}

fn fold_own_mode(front_id: &str, events: &[&Event], collect_history: bool) -> History {
    let mut retracted = BTreeMap::new();
    let mut amendments = BTreeMap::new();
    // events are in canonical HLC/id order; targets are looked up only within this front.
    for e in events {
        match &e.action {
            Action::Change(Change::Retract { target_op_id } | Change::Unretract { target_op_id }) => {
                retracted.insert(target_op_id.as_str(), matches!(e.action, Action::Change(Change::Retract { .. })));
            }
            Action::Change(Change::Amend { target_op_id, .. }) => {
                amendments.insert(target_op_id.as_str(), *e);
            }
            _ => {}
        }
    }
    let mut items: Vec<_> = events
        .iter()
        .filter_map(|e| {
            let Action::Change(change) = &e.action else { return None };
            if !change.is_presence() {
                return None;
            }
            let amend = amendments.get(e.id.as_str()).map(|e| &e.action);
            let at = match amend {
                Some(Action::Change(Change::Amend { occurred_at: Some(at), .. })) => *at,
                _ => e.occurred_at,
            };
            Some((*e, change, amend, at))
        })
        .collect();
    crate::sort::by(&mut items, |a, b| (a.3, a.0.hlc, &a.0.id).cmp(&(b.3, b.0.hlc, &b.0.id)));
    let mut result = History::default();
    let mut open: BTreeMap<Subject, Interval> = BTreeMap::new();
    for (e, change, amend, at) in items {
        let mut amended_change = None;
        if let Some(Action::Change(Change::Amend { entries, note, .. })) = amend
            && matches!(change, Change::Switch { .. })
        {
            let mut next = change.clone();
            if let Change::Switch { entries: es, note: n, .. } = &mut next {
                if let Some(entries) = entries {
                    *es = entries.clone();
                }
                if let Some(note) = note {
                    *n = note.clone();
                }
            }
            amended_change = Some(next);
        }
        let change = amended_change.as_ref().unwrap_or(change);
        let retracted = retracted.get(e.id.as_str()).copied().unwrap_or(false);
        if !retracted {
            apply_change(&mut result.current, e, change);
            if collect_history {
                transition_intervals(
                    front_id,
                    &result.current,
                    at,
                    &e.id,
                    e.tz_offset_min,
                    &mut open,
                    &mut result.intervals,
                );
            }
        }
        if !collect_history {
            continue;
        }
        let base_note = match &change {
            Change::Switch { note, .. } => note.clone(),
            _ => None,
        };
        let note = match amend {
            Some(Action::Change(Change::Amend { note: Some(note), .. })) => note.clone(),
            _ => base_note,
        };
        let row = HistoryRow {
            id: e.id.clone(),
            kind: change.kind(),
            occurred_at: at,
            recorded_at: (e.recorded_at != at).then_some(e.recorded_at),
            recorded_time_estimated: e.recorded_time_estimated,
            history_only: e.history_only,
            hlc: e.hlc,
            tz_offset_min: e.tz_offset_min,
            device_id: e.device_id.clone(),
            based_on: change.based_on().map(str::to_string),
            change: change.clone(),
            entries: match &change {
                Change::Switch { entries, .. } => entries.clone(),
                Change::Add { entry, .. } => vec![entry.clone()],
                _ => vec![],
            },
            resulting_front: result.current.clone(),
            note,
            notify: change.notify(),
            retracted,
            amended: amend.is_some(),
        };
        result.switches.push(row);
    }
    result.intervals.extend(open.into_values());
    crate::sort::by(&mut result.intervals, |a, b| (a.start_at, &a.id).cmp(&(b.start_at, &b.id)));
    result
}

/// Order changes alone retain an interval; changing layer/primary closes it. Both own and
/// effective histories use this transition, but effective history batches equal-time causes.
pub(crate) fn transition_intervals(
    front_id: &str,
    entries: &[Presence],
    at: i64,
    change_id: &str,
    tz_offset_min: i32,
    open: &mut BTreeMap<Subject, Interval>,
    intervals: &mut Vec<Interval>,
) {
    let next: BTreeMap<_, _> = entries.iter().enumerate().map(|(i, e)| (subject(e), (i, e))).collect();
    let close: Vec<_> = open
        .iter()
        .filter(|(s, iv)| next.get(*s).is_none_or(|(_, e)| e.layer_id != iv.layer_id || e.is_primary != iv.is_primary))
        .map(|(s, _)| s.clone())
        .collect();
    for s in close {
        let mut iv = open.remove(&s).unwrap_or_else(|| unreachable!());
        iv.end_at = Some(at);
        iv.end_switch_id = Some(change_id.into());
        intervals.push(iv);
    }
    for (s, (position, entry)) in next {
        open.entry(s).or_insert_with(|| Interval {
            id: interval_id(front_id, entry, change_id),
            front_id: front_id.into(),
            subject_type: entry.subject_type,
            subject_id: entry.subject_id.clone(),
            layer_id: entry.layer_id.clone(),
            is_primary: entry.is_primary,
            position,
            start_at: at,
            end_at: None,
            start_switch_id: change_id.into(),
            end_switch_id: None,
            start_tz_offset_min: tz_offset_min,
        });
    }
}

fn interval_id(front_id: &str, entry: &Presence, op_id: &str) -> String {
    let mut hash = Sha256::new();
    // JSON tuple encoding avoids delimiter collisions in independent layer IDs.
    hash.update(
        serde_json::to_vec(&(front_id, entry.subject_type, &entry.subject_id, &entry.layer_id, op_id))
            .unwrap_or_default(),
    );
    hash.finalize()[..16].iter().map(|b| format!("{b:02x}")).collect()
}

fn own_reviews(events: &[&Event], history: &History, window_ms: i64) -> Vec<front::Review> {
    let by_id: BTreeMap<_, _> = events.iter().map(|e| (e.id.as_str(), *e)).collect();
    let devices: BTreeSet<_> = history
        .switches
        .iter()
        .filter(|r| !r.retracted)
        .filter_map(|r| by_id.get(r.id.as_str()))
        .filter(|e| e.seq.is_some())
        .map(|e| e.device_id.as_str())
        .collect();
    if devices.len() < 2 {
        return vec![];
    }
    let rows: Vec<_> = history
        .switches
        .iter()
        .filter(|r| !r.retracted && by_id.get(r.id.as_str()).is_some_and(|e| e.seq.is_some()))
        .collect();
    let mut out = Vec::new();
    for (i, a) in rows.iter().enumerate() {
        for b in &rows[i + 1..] {
            if b.occurred_at.saturating_sub(a.occurred_at) > window_ms {
                break;
            }
            if a.resulting_front == b.resulting_front {
                continue;
            }
            let (Some(oa), Some(ob)) = (by_id.get(a.id.as_str()), by_id.get(b.id.as_str())) else { continue };
            out.extend(front::concurrent_review(
                front::ReviewCandidate { id: &oa.id, device_id: &oa.device_id, seq: oa.seq, seen_seq: oa.seen_seq },
                front::ReviewCandidate { id: &ob.id, device_id: &ob.device_id, seq: ob.seq, seen_seq: ob.seen_seq },
            ));
        }
    }
    crate::sort::by(&mut out, |a, b| a.id.cmp(&b.id));
    out
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct DailyRow {
    pub front_id: String,
    pub day: String,
    pub subject_type: SubjectType,
    pub subject_id: String,
    pub layer_id: String,
    pub seconds: i64,
    pub as_primary_seconds: i64,
}

/// Daily totals for the supplied OWN intervals. Keep front and layer identities in the key;
/// sharing a display name/level never combines them. As in the legacy daily helper, callers
/// split intervals at timezone transitions, provide the timezone schedule and clamp the requested
/// interval window. Wider arithmetic keeps boundary calculations safe near timestamp limits.
pub fn own_daily(intervals: &[Interval], now: i64, offset_min_at: impl Fn(i64) -> i32) -> Vec<DailyRow> {
    const DAY: i128 = 86_400_000;
    type Key = (String, String, SubjectType, String, String);
    let mut acc: BTreeMap<Key, (i128, i128)> = BTreeMap::new();
    for iv in intervals {
        let end = i128::from(iv.end_at.unwrap_or(now).max(iv.start_at));
        let mut t = i128::from(iv.start_at);
        while t < end {
            let offset = i128::from(offset_min_at(t as i64)) * 60_000;
            let day = (t + offset).div_euclid(DAY);
            let boundary = ((day + 1) * DAY - offset).min(end);
            let until = if boundary <= t { end } else { boundary };
            let ms = until - t;
            let key = (
                iv.front_id.clone(),
                front::civil_date(day as i64),
                iv.subject_type,
                iv.subject_id.clone(),
                iv.layer_id.clone(),
            );
            let totals = acc.entry(key).or_default();
            totals.0 += ms;
            if iv.is_primary {
                totals.1 += ms;
            }
            t = until;
        }
    }
    acc.into_iter()
        .map(|((front_id, day, subject_type, subject_id, layer_id), (ms, primary))| DailyRow {
            front_id,
            day,
            subject_type,
            subject_id,
            layer_id,
            seconds: (ms / 1000).min(i128::from(i64::MAX)) as i64,
            as_primary_seconds: (primary / 1000).min(i128::from(i64::MAX)) as i64,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use serde_json::{Value, json};

    const BODY: &str = "0192f8c2-0000-7000-8000-0000000000a1";
    const CHORUS: &str = "0192f8c2-0000-7000-8000-0000000000a2";
    const HEADSPACE: &str = "0192f8c2-0000-7000-8000-0000000000a3";

    fn op(i: u64, kind: &str, front: &str, payload: Value) -> Op {
        serde_json::from_value(json!({
            "id": crate::id::new_id(i, [i as u8; 10]), "kind":kind,
            "scope": format!("account:{BODY}"), "entity_id": front,
            "hlc": Hlc::new(i * 1000, 0, 1), "device_at": i as i64 * 1000,
            "payload":payload
        }))
        .unwrap()
    }

    fn entry(member: &str, layer: &str) -> Value {
        json!({"subject_type":"member","subject_id":member,"layer_id":layer})
    }

    fn switch(i: u64, front: &str, member: &str, layer: &str) -> Op {
        op(i, "front.change", front, json!({"action":"switch","entries":[entry(member, layer)]}))
    }

    fn assert_permutations(ops: &[Op]) -> FoldResult {
        let result = fold(BODY, ops);
        let mut reversed = ops.to_vec();
        reversed.reverse();
        reversed.extend(ops.iter().cloned());
        assert_eq!(fold(BODY, &reversed), result, "shuffling and duplicate delivery converge");
        result
    }

    #[test]
    fn sparse_definitions_and_tombstones_keep_independent_purposes_and_history() {
        let ops = vec![
            op(1, "front.define", CHORUS, json!({"name":"Chorus","authorship_layers":["quiet"]})),
            op(2, "front.layer", BODY, json!({"layer_id":"quiet","name":"Fronting","level":-10})),
            switch(3, CHORUS, "fox", "quiet"),
            op(4, "front.define", CHORUS, json!({"notification_layers":["quiet"]})),
            op(5, "front.layer", BODY, json!({"layer_id":"quiet","name":"Anything","deleted":true})),
            op(6, "front.define", CHORUS, json!({"deleted":true})),
        ];
        let deleted = assert_permutations(&ops);
        assert!(deleted.system.fronts[CHORUS].deleted);
        assert!(deleted.resolved[CHORUS].authorship.is_empty());
        assert_eq!(deleted.histories[CHORUS].current[0].layer_id, "quiet");
        let mut ops = ops;
        ops.push(op(7, "front.define", CHORUS, json!({"deleted":false,"name":"Renamed"})));
        ops.push(op(8, "front.layer", BODY, json!({"layer_id":"quiet","deleted":false,"level":999})));
        let restored = assert_permutations(&ops);
        assert_eq!(restored.resolved[CHORUS].name, "Renamed");
        assert_eq!(restored.resolved[CHORUS].authorship, restored.resolved[CHORUS].notifications);
        assert_eq!(restored.resolved[CHORUS].authorship[0].subject_id, "fox");
        assert_eq!(restored.system.layers["quiet"].name, "Anything");
        assert_eq!(restored.histories, deleted.histories, "display edits and tombstones never rewrite own history");
    }

    #[test]
    fn following_controls_use_write_order_even_when_a_switch_is_backdated() {
        let mut old_switch = switch(4, CHORUS, "override", "quiet");
        old_switch.payload["effective_at"] = json!(500);
        let mut ops = vec![
            switch(1, BODY, "source", "front"),
            op(2, "front.define", CHORUS, json!({"name":"Chorus","follow_source":BODY})),
            op(3, "front.define", HEADSPACE, json!({"follow_source":CHORUS})),
            old_switch,
            switch(5, BODY, "new-source", "front"),
            op(6, "front.define", CHORUS, json!({"name":"Still overridden","follow_source":BODY})),
        ];
        let before = assert_permutations(&ops);
        assert_eq!(before.histories[CHORUS].switches[0].occurred_at, 500);
        assert_eq!(before.histories[CHORUS].switches[0].recorded_at, Some(4000));
        assert!(!before.histories[CHORUS].switches[0].recorded_time_estimated);
        assert_eq!(before.resolved[CHORUS].state, front_system::FollowState::Overridden);
        assert_eq!(before.resolved[HEADSPACE].effective[0].subject_id, "override");
        ops.push(op(7, "front.change", CHORUS, json!({"action":"resume"})));
        let resumed = assert_permutations(&ops);
        assert_eq!(resumed.resolved[HEADSPACE].effective[0].subject_id, "new-source");
        assert_eq!(resumed.histories[CHORUS], before.histories[CHORUS], "Resume does not overwrite saved presence");
        assert_eq!(fold(BODY, &ops[..ops.len() - 1]), before, "rejecting Resume restores the exact prior state");
        ops.push(switch(8, CHORUS, "second-override", "third"));
        assert_eq!(assert_permutations(&ops).resolved[HEADSPACE].effective[0].subject_id, "second-override");
    }

    #[test]
    fn late_amendments_retractions_and_foreign_front_targets_are_isolated() {
        let a = switch(1, CHORUS, "a", "quiet");
        let b = switch(2, CHORUS, "b", "third");
        let mut ops = vec![
            a.clone(),
            b.clone(),
            op(
                3,
                "front.change",
                CHORUS,
                json!({"action":"amend","target_op_id":a.id,"occurred_at":3000,"entries":[entry("edited","custom")],"note":null}),
            ),
            op(4, "front.change", HEADSPACE, json!({"action":"retract","target_op_id":a.id})),
            op(5, "front.change", CHORUS, json!({"action":"retract","target_op_id":b.id})),
        ];
        let result = assert_permutations(&ops);
        let history = &result.histories[CHORUS];
        assert_eq!(history.switches[0].id, b.id);
        assert!(history.switches[0].retracted);
        assert_eq!(history.switches[1].occurred_at, 3000);
        assert!(history.switches[1].amended);
        assert!(!history.switches[1].retracted);
        assert_eq!(history.current[0].subject_id, "edited");
        assert_eq!(history.intervals.len(), 1);
        ops.push(op(6, "front.change", CHORUS, json!({"action":"unretract","target_op_id":b.id})));
        let result = assert_permutations(&ops);
        assert_eq!(result.histories[CHORUS].intervals.len(), 2);
        assert_eq!(
            result.histories[CHORUS].intervals.iter().find(|iv| iv.subject_id == "b").unwrap().end_at,
            Some(3000)
        );
    }

    #[test]
    fn positions_are_absolute_and_layer_changes_do_not_reorder_members() {
        let mut ops = vec![
            op(
                1,
                "front.change",
                CHORUS,
                json!({"action":"switch","entries":[entry("a","third"),entry("b","first"),entry("c","third")]}),
            ),
            op(
                2,
                "front.change",
                CHORUS,
                json!({"action":"update","subject_type":"member","subject_id":"b","layer_id":"fourth","is_primary":true}),
            ),
            op(3, "front.change", CHORUS, json!({"action":"add","entry":entry("d","fifth"),"position":1})),
            op(
                4,
                "front.change",
                CHORUS,
                json!({"action":"update","subject_type":"member","subject_id":"c","position":0}),
            ),
        ];
        let before = assert_permutations(&ops);
        let current = &before.histories[CHORUS].current;
        assert_eq!(current.iter().map(|e| e.subject_id.as_str()).collect::<Vec<_>>(), ["c", "a", "d", "b"]);
        assert!(current[3].is_primary);
        assert_eq!(current[3].layer_id, "fourth");
        assert_eq!(
            before.histories[CHORUS].intervals.iter().filter(|iv| iv.subject_id == "c").count(),
            1,
            "order alone does not split presence"
        );
        ops.push(op(5,"front.change",CHORUS,json!({"action":"add","entry":{"subject_type":"member","subject_id":"d","layer_id":"fifth","is_primary":true},"position":0})));
        let result = assert_permutations(&ops);
        assert_eq!(result.histories[CHORUS].current.iter().filter(|e| e.is_primary).count(), 1);
        assert!(result.histories[CHORUS].current[0].is_primary);
    }

    #[test]
    fn legacy_fold_is_preserved_and_old_deltas_keep_unrelated_custom_entries() {
        let initial = op(
            1,
            "front.switch",
            BODY,
            json!({"entries":[
                {"subject_type":"member","subject_id":"p","level":"present","is_primary":true},
                {"subject_type":"member","subject_id":"a","level":"front","is_primary":true},
                {"subject_type":"member","subject_id":"b","level":"cocon"}
            ]}),
        );
        let add = op(
            2,
            "front.add",
            BODY,
            json!({"entry":{"subject_type":"member","subject_id":"c","level":"front"},"position":0}),
        );
        let update = op(
            3,
            "front.update",
            BODY,
            json!({"subject_type":"member","subject_id":"b","level":"front","is_primary":true,"position":1}),
        );
        let remove = op(4, "front.remove", BODY, json!({"subject_type":"member","subject_id":"a"}));
        let ops = vec![initial, add, update, remove];
        let legacy_ops: Vec<_> = ops.iter().map(|o| FrontOp::from_op(o).unwrap()).collect();
        let legacy = front::fold(&legacy_ops);
        let result = assert_permutations(&ops);
        assert_eq!(result.histories[BODY].current, legacy.current.iter().map(presence).collect::<Vec<_>>());
        for (old, new) in legacy.switches.iter().zip(&result.histories[BODY].switches) {
            assert_eq!(old.resulting_front.iter().map(presence).collect::<Vec<_>>(), new.resulting_front);
        }
        for old in &legacy.intervals {
            let new = result.histories[BODY]
                .intervals
                .iter()
                .find(|iv| iv.subject_id == old.subject_id && iv.start_switch_id == old.start_switch_id)
                .unwrap();
            assert_eq!(
                (new.start_at, new.end_at, new.is_primary, new.position),
                (old.start_at, old.end_at, old.is_primary, old.position)
            );
            assert_eq!(new.layer_id, old.level.as_str());
        }
        let mut mixed = vec![op(
            1,
            "front.change",
            BODY,
            json!({"action":"switch","entries":[entry("custom","quiet"),entry("a","front")]}),
        )];
        mixed.extend(ops.into_iter().skip(1));
        let result = assert_permutations(&mixed);
        assert_eq!(result.histories[BODY].current[0].subject_id, "custom");
        assert_eq!(result.histories[BODY].current[0].layer_id, "quiet");
        assert_eq!(
            result.histories[BODY].current.iter().map(|e| e.subject_id.as_str()).collect::<Vec<_>>(),
            ["custom", "c"]
        );
    }

    #[test]
    fn explicit_empty_null_and_missing_settings_remain_distinct() {
        let mut ops = vec![
            switch(1, CHORUS, "a", "front"),
            op(2, "front.define", CHORUS, json!({"authorship_layers":[],"notification_layers":[]})),
        ];
        let none = assert_permutations(&ops);
        assert!(none.resolved[CHORUS].authorship.is_empty());
        ops.push(op(3, "front.define", CHORUS, json!({"name":"Unrelated"})));
        assert!(assert_permutations(&ops).resolved[CHORUS].authorship.is_empty());
        ops.push(op(4, "front.define", CHORUS, json!({"authorship_layers":null,"notification_layers":null})));
        let defaults = assert_permutations(&ops);
        assert_eq!(defaults.resolved[CHORUS].authorship.len(), 1);
        assert_eq!(defaults.resolved[CHORUS].notifications.len(), 1);
        let patch: Definition = serde_json::from_value(json!({"follow_source":null})).unwrap();
        assert_eq!(patch.follow_source, Some(None));
        assert_eq!(serde_json::to_value(patch).unwrap(), json!({"follow_source":null}));
    }

    #[test]
    fn past_history_corrections_keep_following_and_do_not_resume_overrides() {
        let old = switch(1, CHORUS, "old", "custom");
        let mut ops = vec![
            old.clone(),
            op(2, "front.define", CHORUS, json!({"follow_source":BODY})),
            switch(3, BODY, "source", "front"),
            op(4, "front.change", CHORUS, json!({"action":"amend","target_op_id":old.id,"note":"Correction"})),
        ];
        let keep = assert_permutations(&ops);
        assert_eq!(keep.resolved[CHORUS].state, front_system::FollowState::Following);
        for (i, action) in [(5, "retract"), (6, "unretract")] {
            ops.push(op(i, "front.change", CHORUS, json!({"action":action,"target_op_id":old.id})));
            assert_eq!(assert_permutations(&ops).resolved[CHORUS].state, front_system::FollowState::Following);
        }
        let manual = switch(7, CHORUS, "manual", "custom");
        ops.push(manual.clone());
        ops.push(op(8, "front.change", CHORUS, json!({"action":"retract","target_op_id":manual.id})));
        assert_eq!(assert_permutations(&ops).resolved[CHORUS].state, front_system::FollowState::Overridden);
    }

    #[test]
    fn missed_entries_keep_following_and_corrected_record_time_is_separate() {
        let mut missed = switch(4, CHORUS, "missed", "quiet");
        missed.payload["effective_at"] = json!(500);
        missed.payload["history_only"] = json!(true);
        let mut ops = vec![
            switch(1, BODY, "source", "front"),
            op(2, "front.define", CHORUS, json!({"follow_source":BODY})),
            missed.clone(),
        ];
        let pending = assert_permutations(&ops);
        assert_eq!(pending.resolved[CHORUS].state, front_system::FollowState::Following);
        assert_eq!(pending.resolved[CHORUS].effective[0].subject_id, "source");
        assert_eq!(pending.histories[CHORUS].current[0].subject_id, "missed");
        let row = &pending.histories[CHORUS].switches[0];
        assert_eq!((row.occurred_at, row.recorded_at, row.history_only), (500, Some(4000), true));
        missed.seq = Some(10);
        missed.occurred_at = Some(4300);
        missed.received_at = Some(4500);
        ops.push(missed);
        let confirmed = assert_permutations(&ops);
        let row = &confirmed.histories[CHORUS].switches[0];
        assert_eq!((row.occurred_at, row.recorded_at), (500, Some(4300)));
        assert_eq!(confirmed.resolved[CHORUS], pending.resolved[CHORUS]);
    }

    #[test]
    fn legacy_user_time_retains_an_explicit_bounded_record_time_estimate() {
        let mut old = op(
            4,
            "front.switch",
            BODY,
            json!({"entries":[{"subject_type":"member","subject_id":"a","level":"front"}]}),
        );
        old.device_at = 500;
        old.time_source = crate::time::TimeSource::User;
        old.received_at = Some(3500);
        let result = assert_permutations(&[old]);
        let row = &result.histories[BODY].switches[0];
        assert_eq!((row.occurred_at, row.recorded_at), (500, Some(3500)));
        assert!(row.recorded_time_estimated);
        let json = serde_json::to_value(row).unwrap();
        assert_eq!(json["recorded_time_estimated"], true);
    }

    #[test]
    fn own_daily_totals_keep_fronts_and_independent_layers_separate_across_midnight() {
        let mut a = switch(1, CHORUS, "same-member", "quiet");
        a.device_at = 86_399_500;
        a.payload["entries"][0]["is_primary"] = json!(true);
        let mut b = switch(2, CHORUS, "same-member", "third");
        b.device_at = 86_400_500;
        let mut c = switch(3, BODY, "same-member", "quiet");
        c.device_at = 86_399_000;
        let result = assert_permutations(&[a, b, c]);
        let intervals: Vec<_> = result.histories.values().flat_map(|h| h.intervals.iter().cloned()).collect();
        let totals = own_daily(&intervals, 86_402_000, |_| 0);
        assert_eq!(totals.iter().filter(|r| r.front_id == BODY).map(|r| r.seconds).sum::<i64>(), 3);
        assert_eq!(totals.iter().find(|r| r.front_id == CHORUS && r.layer_id == "third").unwrap().seconds, 1);
        assert_eq!(totals.iter().filter(|r| r.front_id == CHORUS && r.layer_id == "quiet").count(), 2);
        let shifted = own_daily(&intervals, 86_402_000, |_| 60);
        let quiet = shifted.iter().find(|r| r.front_id == CHORUS && r.layer_id == "quiet").unwrap();
        assert_eq!((quiet.day.as_str(), quiet.seconds, quiet.as_primary_seconds), ("1970-01-02", 1, 1));
        let mut near_limit = intervals[0].clone();
        near_limit.start_at = i64::MAX - 2000;
        near_limit.end_at = Some(i64::MAX);
        assert_eq!(own_daily(&[near_limit], i64::MAX, |_| i32::MAX).iter().map(|r| r.seconds).sum::<i64>(), 2);
    }

    #[test]
    fn invalid_payloads_and_other_account_scopes_are_never_folded() {
        for (kind, payload) in [
            ("front.define", json!({"follow_source":""})),
            ("front.define", json!({"authorship_layers":[""]})),
            ("front.define", json!({"unknown":true})),
            ("front.layer", json!({"layer_id":"","level":1})),
            ("front.layer", json!({"layer_id":"a","level":1.5})),
            ("front.change", json!({"action":"switch","entries":[entry("a","")]})),
            ("front.change", json!({"action":"update","subject_type":"member","subject_id":"a","layer_id":""})),
            ("front.change", json!({"action":"unknown"})),
            ("front.change", json!({"action":"switch","entries":[],"effective_at":null})),
            ("front.change", json!({"action":"switch","entries":[],"history_only":"yes"})),
            ("front.change", json!({"action":"resume","history_only":false})),
            ("front.change", json!({"action":"amend","target_op_id":"a","effective_at":10})),
        ] {
            assert!(Event::from_op(BODY, &op(1, kind, CHORUS, payload)).is_err(), "{kind}");
        }
        let mut foreign = switch(1, CHORUS, "foreign", "front");
        foreign.scope = format!("account:{CHORUS}");
        assert_eq!(fold(BODY, [&foreign]), fold(BODY, []));
        let mut future = switch(2, CHORUS, "future", "front");
        future.v = op::CURRENT_V + 1;
        assert_eq!(fold(BODY, [&future]), fold(BODY, []));
    }

    #[test]
    fn action_decoder_retains_nullable_defaults_and_rejects_unexpected_fields() {
        for payload in [
            json!({"action":"switch","entries":[],"based_on":null,"note":null}),
            json!({"action":"add","entry":entry("a","quiet"),"position":null,"notify":"silent"}),
            json!({"action":"remove","subject_type":"group","subject_id":"a","notify":"extra_delay"}),
            json!({"action":"update","subject_type":"state","subject_id":"a","position":0,"is_primary":false,"layer_id":null}),
            json!({"action":"resume"}),
            json!({"action":"retract","target_op_id":"a"}),
            json!({"action":"unretract","target_op_id":"a"}),
            json!({"action":"amend","target_op_id":"a"}),
            json!({"action":"amend","target_op_id":"a","occurred_at":null,"entries":null,"note":null}),
            json!({"action":"amend","target_op_id":"a","entries":[],"note":"new"}),
        ] {
            let change: Change = serde_json::from_value(payload.clone()).unwrap();
            let serialized = serde_json::to_value(&change).unwrap();
            assert_eq!(serde_json::from_value::<Change>(serialized).unwrap(), change);
            let mut unknown = payload;
            unknown["unexpected"] = json!(true);
            assert!(serde_json::from_value::<Change>(unknown).is_err());
        }
        let missing: Change = serde_json::from_value(json!({"action":"amend","target_op_id":"a"})).unwrap();
        let clear: Change = serde_json::from_value(json!({"action":"amend","target_op_id":"a","note":null})).unwrap();
        assert!(matches!(missing, Change::Amend { note: None, .. }));
        assert!(matches!(clear, Change::Amend { note: Some(None), .. }));
        for bad in [
            json!({"action":"switch","entries":[],"notify":null}),
            json!({"action":"switch"}),
            json!({"action":"resume","note":null}),
            json!({"action":"add","entry":entry("a","quiet"),"position":-1}),
            json!({"action":"update","subject_type":"member","subject_id":"a","is_primary":1}),
            json!({"action":"amend","target_op_id":"a","note":false}),
        ] {
            assert!(serde_json::from_value::<Change>(bad).is_err());
        }
    }

    #[test]
    fn review_cards_compare_custom_layer_outcomes_only_within_the_same_front() {
        let mut a = switch(1, CHORUS, "a", "quiet");
        a.seq = Some(10);
        a.device_id = Some("a".into());
        let mut b = switch(2, CHORUS, "b", "third");
        b.seq = Some(11);
        b.device_id = Some("b".into());
        let mut other = switch(3, HEADSPACE, "c", "quiet");
        other.seq = Some(12);
        other.device_id = Some("c".into());
        let mut ops = vec![a.clone(), b.clone(), other];
        let result = assert_permutations(&ops);
        assert_eq!(result.reviews.len(), 1);
        assert_eq!(result.reviews[CHORUS].len(), 1);
        assert_eq!(result.reviews[CHORUS][0].switch_a, a.id);
        assert_eq!(result.reviews[CHORUS][0].switch_b, b.id);
        ops[1].seen_seq = 10;
        assert!(assert_permutations(&ops).reviews.is_empty(), "observed prior switches are not concurrent");
        ops[1].seen_seq = 0;
        ops.push(op(4, "front.change", CHORUS, json!({"action":"retract","target_op_id":a.id})));
        assert!(assert_permutations(&ops).reviews.is_empty(), "retracted outcomes do not create review cards");
    }

    #[test]
    fn server_stamped_copy_corrects_history_without_losing_override_control() {
        let local = switch(2, CHORUS, "a", "custom");
        let mut stamped = local.clone();
        stamped.seq = Some(20);
        stamped.occurred_at = Some(500);
        stamped.device_id = Some("confirmed-device".into());
        let definition = op(1, "front.define", CHORUS, json!({"follow_source":BODY}));
        let result = assert_permutations(&[local, stamped, definition]);
        assert_eq!(result.histories[CHORUS].switches.len(), 1);
        assert_eq!(result.histories[CHORUS].switches[0].occurred_at, 500);
        assert_eq!(result.histories[CHORUS].switches[0].device_id, "confirmed-device");
        assert_eq!(result.resolved[CHORUS].state, front_system::FollowState::Overridden);
    }

    /// Run explicitly in release mode; pending local operations must not incur a quadratic
    /// concurrent-review scan. Runtime append/replay budgets will also be checked at integration.
    #[test]
    #[ignore = "release performance check"]
    fn ten_thousand_own_switches() {
        let ops: Vec<_> = (1..=10_000).map(|i| switch(i, CHORUS, &format!("m{}", i % 500), "custom")).collect();
        let start = std::time::Instant::now();
        let result = fold(BODY, &ops);
        let elapsed = start.elapsed();
        assert_eq!(result.histories[CHORUS].switches.len(), 10_000);
        assert_eq!(result.histories[CHORUS].intervals.len(), 10_000);
        eprintln!("named own history: 10,000 switches in {elapsed:?}");
        assert!(elapsed < std::time::Duration::from_secs(1));
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(64))]
        #[test]
        fn shuffled_mixed_legacy_and_named_histories_converge(picks in prop::collection::vec((0u8..9,0u8..5,0u16..500),1..100)) {
            let mut ops = Vec::new();
            for (i,(kind,member,at)) in picks.iter().enumerate() {
                let front = if member%2==0 { BODY } else { CHORUS };
                let target = crate::id::new_id(1,[1;10]);
                let mut o = match kind {
                    0 => switch(i as u64+1,front,&member.to_string(),"custom"),
                    1 => op(i as u64+1,"front.change",front,json!({"action":"add","entry":entry(&member.to_string(),"other"),"position":member})),
                    2 => op(i as u64+1,"front.change",front,json!({"action":"remove","subject_type":"member","subject_id":member.to_string()})),
                    3 => op(i as u64+1,"front.change",front,json!({"action":"retract","target_op_id":target})),
                    4 => op(i as u64+1,"front.change",front,json!({"action":"amend","target_op_id":target,"occurred_at":at})),
                    5 => op(i as u64+1,"front.define",front,json!({"follow_source":if member%2==0 {CHORUS} else {BODY}})),
                    6 => op(i as u64+1,"front.change",front,json!({"action":"resume"})),
                    7 => op(i as u64+1,"front.switch",BODY,json!({"entries":[{"subject_type":"member","subject_id":member.to_string(),"level":"present"}]})),
                    _ => op(i as u64+1,"front.layer",BODY,json!({"layer_id":"custom","name":member.to_string(),"level":at})),
                };
                o.device_at = i64::from(*at);
                ops.push(o);
            }
            assert_permutations(&ops);
        }
    }
}
