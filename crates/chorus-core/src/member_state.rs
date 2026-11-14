//! Member states (D-122, D-138, Figma F3.10): what a member is up to while here, one account pref
//! per member, `member_status:<member id>`:
//!
//! ```json
//! {"tags": ["tired", "state:<custom state id>", "own words"], "note": "Typing only today",
//!  "at": 1790000000000, "clear_on_leave": true, "audience": {"mode": "private"}, "text": "…"}
//! ```
//!
//! `text` is the one-line form written for older readers; a legacy `{text, at}` (no `tags`) reads
//! as a note. `null` clears the state. The account's own devices see all of it; friends in the
//! `audience` see [`shared_part`] (tags and note, never `at`) next to the member's name, and only
//! through the follower front view at reveal time (NOTIFICATIONS §5). Display only: nothing here
//! writes an op when someone leaves the front.

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub const KEY_PREFIX: &str = "member_status:";
pub const MAX_TAGS: usize = 8;
pub const MAX_TAG_CHARS: usize = 40;
pub const MAX_NOTE_CHARS: usize = 200;
/// A state set up to this long before the member's current stretch began still counts as set
/// during it (the clock of the device that set it may be a little off).
pub const CLEAR_SLACK_MS: i64 = 60_000;
/// The audience modes of profile parts and posts (D-101, D-129). Anything else is private.
pub const AUDIENCE_MODES: &[&str] = &["private", "friends", "followers", "following", "buckets", "server"];

/// The one message [`shape_problem`] reports (one string keeps the wasm small, SPEC §9).
pub const RULE: &str = "member_status must be null or {tags: up to 8 texts of 1-40 characters, note: up to 200 characters, at: ms, clear_on_leave: true or false, audience: {mode, bucket_ids}}";

/// A member's state as read from the pref.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct State {
    pub tags: Vec<String>,
    pub note: Option<String>,
    pub at: i64,
    pub clear_on_leave: bool,
    /// Normalised: `{"mode": m}` or `{"mode": "buckets", "bucket_ids": [...]}`; private when absent
    /// or malformed.
    pub audience: Value,
}

/// What a friend in the audience sees: tags and note only.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Shared {
    pub tags: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

/// The member a `member_status:` pref key is about.
pub fn member_of(key: &str) -> Option<&str> {
    key.strip_prefix(KEY_PREFIX).filter(|m| !m.is_empty())
}

fn clip(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

/// The audience, failing closed: a missing, unknown or malformed one is private.
pub fn audience(v: Option<&Value>) -> Value {
    let private = json!({"mode": "private"});
    let Some(mode) = v.and_then(|a| a.get("mode")).and_then(Value::as_str).filter(|m| AUDIENCE_MODES.contains(m))
    else {
        return private;
    };
    if mode != "buckets" {
        return json!({"mode": mode});
    }
    match v.and_then(|a| a.get("bucket_ids")).and_then(Value::as_array) {
        Some(ids) if ids.iter().all(Value::is_string) => json!({"mode": "buckets", "bucket_ids": ids}),
        _ => private,
    }
}

/// Reads a stored value. `None` when it holds no state (null, not an object, no tags and no
/// note). Tags past [`MAX_TAGS`] and text past the limits are cut, as the clients do.
pub fn parse(value: &Value) -> Option<State> {
    let o = value.as_object()?;
    let mut tags: Vec<String> = Vec::new();
    for t in o.get("tags").and_then(Value::as_array).into_iter().flatten() {
        let t = match t {
            Value::String(s) if !s.trim().is_empty() => clip(s, MAX_TAG_CHARS),
            _ => continue,
        };
        if !tags.contains(&t) && tags.len() < MAX_TAGS {
            tags.push(t);
        }
    }
    // a legacy `{text, at}` is a note; once `tags` is written, `text` is only the one-line form
    let note_key = if o.contains_key("tags") { "note" } else { "text" };
    let note =
        o.get(note_key).and_then(Value::as_str).map(|s| clip(s.trim(), MAX_NOTE_CHARS)).filter(|s| !s.is_empty());
    if tags.is_empty() && note.is_none() {
        return None;
    }
    Some(State {
        tags,
        note,
        at: o.get("at").and_then(Value::as_i64).unwrap_or(0),
        clear_on_leave: o.get("clear_on_leave") == Some(&Value::Bool(true)),
        audience: audience(o.get("audience")),
    })
}

/// When the member's current stretch in a front began, as Fronting computes it: the earliest
/// start among their open intervals there (`(start_at, end_at)`); `None` when they aren't in it.
pub fn since(intervals: impl IntoIterator<Item = (i64, Option<i64>)>) -> Option<i64> {
    intervals.into_iter().filter(|(_, end)| end.is_none()).map(|(start, _)| start).min()
}

/// Whether the state still shows, given [`since`] for the member in the front being shown. A
/// state meant to clear when they stop fronting is gone once they left and came back after it was
/// set; display only, so no op is written at switch time.
pub fn shown_own(state: &State, since: Option<i64>) -> bool {
    !state.clear_on_leave || since.is_none_or(|s| s <= state.at.saturating_add(CLEAR_SLACK_MS))
}

/// What may leave the account's devices: tags and note, never `at` or the audience.
pub fn shared_part(state: &State) -> Shared {
    Shared { tags: state.tags.clone(), note: state.note.clone() }
}

/// The shape a new `pref.set` of a `member_status:` key must have (`op::validate_new`).
pub fn shape_problem(value: &Value) -> Option<&'static str> {
    let ok = match value {
        Value::Null => true,
        Value::Object(o) => {
            let text = |k: &str, max: Option<usize>| match o.get(k) {
                None | Some(Value::Null) => true,
                Some(Value::String(s)) => max.is_none_or(|m| s.chars().count() <= m),
                _ => false,
            };
            let tags = match o.get("tags") {
                None => true,
                Some(Value::Array(a)) => {
                    a.len() <= MAX_TAGS
                        && a.iter().all(|t| {
                            t.as_str().is_some_and(|s| !s.trim().is_empty() && s.chars().count() <= MAX_TAG_CHARS)
                        })
                }
                _ => false,
            };
            let audience = match o.get("audience") {
                None | Some(Value::Null) => true,
                Some(a) => audience(Some(a)).get("mode") == a.get("mode") && a.get("mode").is_some(),
            };
            tags && audience
                && text("note", Some(MAX_NOTE_CHARS))
                && text("text", None)
                && o.get("at").is_none_or(|v| v.is_i64())
                && o.get("clear_on_leave").is_none_or(Value::is_boolean)
        }
        _ => false,
    };
    (!ok).then_some(RULE)
}
