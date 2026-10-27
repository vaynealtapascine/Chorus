//! Polls (D-081, D-086, docs/POLLS.md): the definition carried by a post or message, the shape of
//! a member's vote, and the counts-only tally. Votes are account-scoped; nothing here names a
//! voter in its output.

use std::collections::BTreeMap;

use serde_json::{Map, Value, json};

pub const MIN_OPTIONS: usize = 2;
pub const MAX_OPTIONS: usize = 20;
pub const MAX_OPTION_ID: usize = 16;
pub const MAX_OPTION_TEXT: usize = 200;

/// A poll as fixed at creation: option IDs in order and single/multiple choice.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Poll {
    pub options: Vec<String>,
    pub multiple: bool,
}

/// What a valid `poll` field looks like; [`definition`] reports only this one message so the
/// shared core stays inside the wasm size budget (SPEC §9).
pub const POLL_RULE: &str = "poll must have 2–20 options with unique ids (1–16 characters) and text (1–200),      an optional text question and multiple: true or false";

/// The `poll` field of `post.create` / `message.send`. Options and `multiple` never change later.
pub fn definition(v: &Value) -> Option<Poll> {
    let o = v.as_object()?;
    let multiple = match (o.get("question"), o.get("multiple")) {
        (Some(q), _) if !q.is_null() && !q.is_string() => return None,
        (_, None | Some(Value::Null)) => false,
        (_, Some(m)) => m.as_bool()?,
    };
    let list = o.get("options")?.as_array()?;
    if !(MIN_OPTIONS..=MAX_OPTIONS).contains(&list.len()) {
        return None;
    }
    let mut options: Vec<String> = Vec::with_capacity(list.len());
    for opt in list {
        let id = opt.get("id")?.as_str()?;
        let text = opt.get("text")?.as_str()?;
        let fits = |s: &str, max: usize| !s.trim().is_empty() && s.chars().count() <= max;
        if !fits(id, MAX_OPTION_ID) || !fits(text, MAX_OPTION_TEXT) || options.iter().any(|o| o == id) {
            return None;
        }
        options.push(id.to_string());
    }
    Some(Poll { options, multiple })
}

/// The shape of a vote's `choices`: different option IDs; `[]` withdraws the vote.
pub fn choices(v: &Value) -> Option<Vec<String>> {
    let mut out: Vec<String> = Vec::new();
    for c in v.as_array()? {
        let id = c.as_str().filter(|id| !id.is_empty() && !out.iter().any(|o| o == id))?;
        out.push(id.to_string());
    }
    Some(out)
}

/// A vote's choices against its poll: known options, and at most one for single choice.
pub fn check_vote(poll: &Poll, choices: &[String]) -> Result<(), &'static str> {
    if !poll.multiple && choices.len() > 1 {
        return Err("this poll takes one choice");
    }
    if choices.iter().any(|c| !poll.options.contains(c)) {
        return Err("a choice is not an option of this poll");
    }
    Ok(())
}

/// Counts per option (every option, zeros included) and how many members voted.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Tally {
    pub counts: BTreeMap<String, u64>,
    pub voters: u64,
}

/// Totals over the current votes, one per member. Invalid votes (unknown options, too many
/// choices) are left out rather than partly counted.
pub fn tally<'a>(poll: &Poll, votes: impl IntoIterator<Item = &'a [String]>) -> Tally {
    let mut counts: BTreeMap<String, u64> = poll.options.iter().map(|o| (o.clone(), 0)).collect();
    let mut voters = 0;
    for vote in votes {
        if vote.is_empty() || check_vote(poll, vote).is_err() {
            continue;
        }
        voters += 1;
        for c in vote {
            *counts.entry(c.clone()).or_default() += 1;
        }
    }
    Tally { counts, voters }
}

/// The `poll.tally` payload: counts only, plus the parent's channel for space delivery.
pub fn tally_payload(t: &Tally, channel_id: Option<&str>) -> Value {
    let mut p = Map::new();
    if let Some(c) = channel_id {
        p.insert("channel_id".into(), json!(c));
    }
    p.insert("counts".into(), json!(t.counts));
    p.insert("voters".into(), json!(t.voters));
    Value::Object(p)
}

/// The shape of a tally payload. Devices can never push tallies (the server refuses them and
/// builds its own with [`tally_payload`]), so this is a server-side check, not part of `op::validate_new`.
pub fn check_tally(v: &Value) -> Result<(), &'static str> {
    let counts = v.get("counts").and_then(Value::as_object).ok_or("counts must be an object")?;
    if !counts.values().all(|n| n.as_u64().is_some()) || v.get("voters").and_then(Value::as_u64).is_none() {
        return Err("counts and voters must be whole numbers");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn poll(multiple: bool) -> Poll {
        definition(
            &json!({"options": [{"id": "a", "text": "Soup"}, {"id": "b", "text": "Rice"}, {"id": "c", "text": "Tea"}],
            "multiple": multiple}),
        )
        .unwrap()
    }
    fn v(ids: &[&str]) -> Vec<String> {
        ids.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn definitions_need_two_to_twenty_unique_options_with_text() {
        assert_eq!(poll(false), Poll { options: v(&["a", "b", "c"]), multiple: false });
        let one = json!({"options": [{"id": "a", "text": "x"}]});
        assert!(definition(&one).is_none());
        let dup = json!({"options": [{"id": "a", "text": "x"}, {"id": "a", "text": "y"}]});
        assert!(definition(&dup).is_none());
        let blank = json!({"options": [{"id": "a", "text": " "}, {"id": "b", "text": "y"}]});
        assert!(definition(&blank).is_none());
        let many: Vec<_> = (0..21).map(|i| json!({"id": i.to_string(), "text": "x"})).collect();
        assert!(definition(&json!({ "options": many })).is_none());
        assert!(
            definition(&json!({"question": 3, "options": [{"id": "a", "text": "x"}, {"id": "b", "text": "y"}]}))
                .is_none()
        );
        assert!(
            definition(&json!({"options": [{"id": "a", "text": "x"}, {"id": "b", "text": "y"}], "multiple": "yes"}))
                .is_none()
        );
    }

    #[test]
    fn votes_respect_single_or_multiple_choice() {
        assert!(check_vote(&poll(false), &v(&["a"])).is_ok());
        assert!(check_vote(&poll(false), &v(&["a", "b"])).is_err());
        assert!(check_vote(&poll(true), &v(&["a", "b"])).is_ok());
        assert!(check_vote(&poll(true), &v(&["z"])).is_err());
        assert!(check_vote(&poll(false), &[]).is_ok(), "withdrawing is always allowed");
        assert!(choices(&json!(["a", "a"])).is_none());
        assert!(choices(&json!([""])).is_none());
    }

    #[test]
    fn tallies_count_valid_votes_only_and_name_nobody() {
        let p = poll(true);
        let votes = [v(&["a", "b"]), v(&["a"]), v(&[]), v(&["z"])];
        let t = tally(&p, votes.iter().map(Vec::as_slice));
        assert_eq!(t.voters, 2);
        assert_eq!(t.counts, BTreeMap::from([("a".into(), 2), ("b".into(), 1), ("c".into(), 0)]));
        let payload = tally_payload(&t, Some("ch"));
        assert_eq!(payload, json!({"channel_id": "ch", "counts": {"a": 2, "b": 1, "c": 0}, "voters": 2}));
        assert!(check_tally(&payload).is_ok());
        assert!(check_tally(&json!({"counts": {"a": -1}, "voters": 1})).is_err());
    }
}
