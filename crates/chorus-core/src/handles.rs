//! Member handles (D-126): every member has a default handle, `@member.subsystem….system`, made
//! from what everyone may already see of them, and may claim handles of their own.
//!
//! - **Default** (reserved, never stored): the member's shown name (display name, else name),
//!   then every subsystem on the path from the innermost one they are in out to the top, then the
//!   account's handle, as lowercase segments joined by dots: `foxglove.lanterns`,
//!   `kai.littles.stars`, `moss.tiny-ones.littles.stars`, so each member has exactly one path. A
//!   member in several unrelated subsystems takes the deepest (the smaller id breaks a tie). A
//!   person account's self member is the account handle alone. It follows renames and moves, so it is worked out wherever it is
//!   shown, the same way on every device and on the server, offline included.
//! - **Claimed** (`member.handles`, up to [`MAX_CLAIMS`]): 2–32 of `a-z`, `0-9`, `_`, the account
//!   handle rule. They never contain a dot, so they can't collide with a default; the server keeps
//!   them unique among themselves and account handles (ingest refuses a taken one).
//! - **Shown**: the first claimed handle, else the default. Other accounts see only this one and
//!   the claimed list (`chorus-server` `handles.rs`): claiming a handle is how a member stops
//!   showing the subsystem they are in.
//!
//! Two members whose defaults would be the same (two Kais in one subsystem) are told apart in
//! id order, the oldest keeping the plain one: `kai.stars`, `kai-2.stars`. Callers pass every
//! member they know, archived and deleted included, so a handle doesn't move when someone else
//! is archived.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

use serde::{Deserialize, Serialize};

/// How many handles one member may claim.
pub const MAX_CLAIMS: usize = 5;
/// The longest a segment of a default handle gets, in characters.
const SEGMENT_MAX: usize = 32;

/// A handle anyone may claim (and an account's handle): 2–32 of `a-z`, `0-9`, `_`.
pub fn valid_claim(h: &str) -> bool {
    (2..=32).contains(&h.len()) && h.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

/// What a claim box turns typing into: lowercase, no leading `@`, spaces and dashes as `_`.
pub fn normalize_claim(h: &str) -> String {
    h.trim()
        .trim_start_matches('@')
        .to_lowercase()
        .chars()
        .map(|c| if c == ' ' || c == '-' { '_' } else { c })
        .collect()
}

/// One part of a default handle: lowercase letters and digits of any script and `_` stay, every
/// other run of characters becomes one `-`, and none leads or trails. Empty when nothing is left
/// (a name of emoji only).
pub fn segment(s: &str) -> String {
    let mut out = String::new();
    let mut dash = false;
    for c in s.chars().flat_map(char::to_lowercase) {
        if c.is_alphanumeric() || c == '_' {
            if dash && !out.is_empty() {
                out.push('-');
            }
            dash = false;
            out.push(c);
        } else {
            dash = true;
        }
    }
    let cut: String = out.chars().take(SEGMENT_MAX).collect();
    cut.trim_end_matches('-').to_string()
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct Account {
    pub id: String,
    #[serde(default)]
    pub handle: Option<String>,
    /// The system's (or person's) name, for an account without a handle.
    #[serde(default)]
    pub name: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct Member {
    pub id: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub short_id: Option<String>,
    #[serde(default)]
    pub is_self: bool,
    /// `member.handles` as stored; anything that isn't a valid claim is left out.
    #[serde(default)]
    pub handles: serde_json::Value,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct Group {
    pub id: String,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub parent_id: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
}

/// One account's members with what their defaults are made from. Live groups only; `membership`
/// is `[group_id, member_id]` pairs of present memberships.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct Input {
    pub account: Account,
    #[serde(default)]
    pub members: Vec<Member>,
    #[serde(default)]
    pub groups: Vec<Group>,
    #[serde(default)]
    pub membership: Vec<(String, String)>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Handles {
    /// Without the `@`.
    pub default: String,
    pub claimed: Vec<String>,
    /// The first claimed handle, else the default.
    pub shown: String,
}

/// A member's claims as stored: valid ones only, once each, at most [`MAX_CLAIMS`].
pub fn claims(v: &serde_json::Value) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for h in v.as_array().into_iter().flatten().filter_map(serde_json::Value::as_str) {
        if valid_claim(h) && !out.iter().any(|o| o == h) && out.len() < MAX_CLAIMS {
            out.push(h.to_string());
        }
    }
    out
}

/// Why a list of claims can't be stored, if it can't (`member.create`/`member.set` `handles`).
pub fn claims_problem(v: &serde_json::Value) -> Option<&'static str> {
    let Some(list) = v.as_array() else { return Some("handles must be a list") };
    if list.len() > MAX_CLAIMS {
        return Some("a member can claim at most 5 handles");
    }
    let mut seen = HashSet::new();
    for h in list {
        let Some(h) = h.as_str().filter(|h| valid_claim(h)) else {
            return Some("a handle is 2–32 of a-z, 0-9 and _");
        };
        if !seen.insert(h) {
            return Some("a handle is listed twice");
        }
    }
    None
}

/// The account's own segment: its handle, else its name, else the start of its id.
pub fn system_segment(a: &Account) -> String {
    let handle = a.handle.as_deref().map(str::trim).filter(|h| !h.is_empty()).map(segment).unwrap_or_default();
    if !handle.is_empty() {
        return handle;
    }
    let named = a.name.as_deref().map(segment).unwrap_or_default();
    if !named.is_empty() { named } else { a.id.chars().filter(char::is_ascii_alphanumeric).take(8).collect() }
}

fn shown_name(m: &Member) -> &str {
    m.display_name.as_deref().filter(|s| !s.trim().is_empty()).or(m.name.as_deref()).unwrap_or("")
}

/// Every member's handles, by member id.
pub fn member_handles(input: &Input) -> BTreeMap<String, Handles> {
    let system = system_segment(&input.account);
    let groups: HashMap<&str, &Group> = input.groups.iter().map(|g| (g.id.as_str(), g)).collect();
    let depth = |id: &str| {
        let mut seen = HashSet::new();
        let mut at = groups.get(id).and_then(|g| g.parent_id.as_deref());
        while let Some(p) = at.filter(|p| seen.insert(*p)) {
            at = groups.get(p).and_then(|g| g.parent_id.as_deref());
        }
        seen.len()
    };
    // the innermost subsystem each member is in; the smaller id breaks a tie
    let mut inner: HashMap<&str, (usize, &str)> = HashMap::new();
    for (g, m) in &input.membership {
        let Some(group) = groups.get(g.as_str()).filter(|g| g.kind.as_deref() == Some("subsystem")) else { continue };
        let candidate = (depth(&group.id), group.id.as_str());
        let e = inner.entry(m.as_str()).or_insert(candidate);
        if candidate.0 > e.0 || (candidate.0 == e.0 && candidate.1 < e.1) {
            *e = candidate;
        }
    }
    let mut ordered: Vec<&Member> = input.members.iter().collect();
    ordered.sort_by(|a, b| a.id.cmp(&b.id));
    let mut taken: BTreeSet<String> = BTreeSet::new();
    let mut out = BTreeMap::new();
    for m in ordered {
        let default = if m.is_self {
            system.clone()
        } else {
            let mut own = segment(shown_name(m));
            if own.is_empty() {
                own = m.short_id.as_deref().map(segment).filter(|s| !s.is_empty()).unwrap_or_else(|| {
                    m.id.chars().rev().filter(char::is_ascii_alphanumeric).take(7).collect::<String>()
                });
            }
            // the innermost subsystem, then each subsystem holding it, out to the top
            let mut path: Vec<String> = Vec::new();
            let mut seen = HashSet::new();
            let mut at = inner.get(m.id.as_str()).map(|(_, g)| *g);
            while let Some(g) = at.filter(|g| seen.insert(*g)).and_then(|g| groups.get(g)) {
                if g.kind.as_deref() == Some("subsystem") {
                    path.extend(g.name.as_deref().map(segment).filter(|s| !s.is_empty()));
                }
                at = g.parent_id.as_deref();
            }
            path.push(system.clone());
            let rest = path.join(".");
            let mut n = 1;
            loop {
                let first = if n == 1 { own.clone() } else { format!("{own}-{n}") };
                let candidate = format!("{first}.{rest}");
                if !taken.contains(&candidate) {
                    break candidate;
                }
                n += 1;
            }
        };
        taken.insert(default.clone());
        let claimed = claims(&m.handles);
        let shown = claimed.first().cloned().unwrap_or_else(|| default.clone());
        out.insert(m.id.clone(), Handles { default, claimed, shown });
    }
    out
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn input(v: serde_json::Value) -> Input {
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn segments() {
        assert_eq!(segment("Foxglove"), "foxglove");
        assert_eq!(segment("  Mx. Rowan  Ash "), "mx-rowan-ash");
        assert_eq!(segment("Zoë"), "zoë");
        assert_eq!(segment("the_little ones!"), "the_little-ones");
        assert_eq!(segment("🌌✨"), "");
        assert_eq!(segment(&"a".repeat(40)).len(), 32);
        assert_eq!(segment("abcdefghijklmnopqrstuvwxyzabcde fgh"), "abcdefghijklmnopqrstuvwxyzabcde");
    }

    #[test]
    fn claims_are_account_handle_shaped() {
        assert!(valid_claim("fox_2"));
        assert!(!valid_claim("f"));
        assert!(!valid_claim("fox.stars"));
        assert!(!valid_claim("Fox"));
        assert_eq!(normalize_claim(" @Fox Glove-2 "), "fox_glove_2");
        assert_eq!(claims_problem(&json!(["fox", "glove"])), None);
        assert!(claims_problem(&json!(["fox", "fox"])).is_some());
        assert!(claims_problem(&json!(["fox.stars"])).is_some());
        assert!(claims_problem(&json!("fox")).is_some());
        assert!(claims_problem(&json!(["a1", "a2", "a3", "a4", "a5", "a6"])).is_some());
        assert_eq!(claims(&json!(["fox", "Bad!", "fox", 3, "glove"])), vec!["fox", "glove"]);
    }

    #[test]
    fn defaults_name_the_member_every_subsystem_on_their_path_and_system() {
        let h = member_handles(&input(json!({
            "account": {"id": "a", "handle": "lanterns"},
            "members": [
                {"id": "m1", "name": "Foxglove Marsh", "display_name": "Foxglove"},
                {"id": "m2", "name": "Kai"},
                {"id": "m3", "name": "🌌", "short_id": "qwertyu"},
                {"id": "m4", "name": "Ren", "handles": ["ren", "renny"]},
                {"id": "m5", "name": "Moss"},
                {"id": "m6", "name": "Wren"}
            ],
            "groups": [
                {"id": "g1", "kind": "subsystem", "name": "Littles"},
                {"id": "g2", "kind": "subsystem", "name": "Tiny Ones", "parent_id": "g1"},
                {"id": "g3", "kind": "group", "name": "Hosts"},
                {"id": "g4", "kind": "subsystem", "name": "Burrow", "parent_id": "g2"},
                {"id": "g5", "kind": "group", "name": "Circle", "parent_id": "g1"},
                {"id": "g6", "kind": "subsystem", "name": "Inner", "parent_id": "g5"}
            ],
            "membership": [["g1", "m2"], ["g2", "m2"], ["g3", "m1"], ["g1", "m4"], ["g4", "m5"], ["g6", "m6"]]
        })));
        assert_eq!(h["m5"].default, "moss.burrow.tiny-ones.littles.lanterns");
        assert_eq!(h["m6"].default, "wren.inner.littles.lanterns", "a plain group on the path names nothing");
        assert_eq!(h["m1"].default, "foxglove.lanterns", "the display name, never the complete name");
        assert_eq!(h["m2"].default, "kai.tiny-ones.littles.lanterns", "every subsystem on the path, innermost first");
        assert_eq!(h["m3"].default, "qwertyu.lanterns");
        assert_eq!(h["m4"].default, "ren.littles.lanterns");
        assert_eq!(h["m4"].claimed, vec!["ren", "renny"]);
        assert_eq!(h["m4"].shown, "ren");
        assert_eq!(h["m1"].shown, "foxglove.lanterns");
    }

    #[test]
    fn twins_are_told_apart_oldest_first_and_people_are_their_account() {
        let h = member_handles(&input(json!({
            "account": {"id": "a", "handle": "stars"},
            "members": [{"id": "b", "name": "Kai"}, {"id": "a", "name": "kai"}, {"id": "c", "name": "Kai-2"}, {"id": "d", "name": "Kai"}]
        })));
        assert_eq!(h["a"].default, "kai.stars");
        assert_eq!(h["b"].default, "kai-2.stars");
        assert_eq!(h["c"].default, "kai-2-2.stars", "a member literally named Kai 2 keeps clear of the twin");
        assert_eq!(h["d"].default, "kai-3.stars");
        let person = member_handles(&input(json!({
            "account": {"id": "p", "handle": "alex"}, "members": [{"id": "s", "name": "Alex", "is_self": true}]
        })));
        assert_eq!(person["s"].default, "alex");
    }

    #[test]
    fn an_account_without_a_handle_uses_its_name_and_cycles_end() {
        let h = member_handles(&input(json!({
            "account": {"id": "0192f8c2-7d1e", "name": "The Stars"},
            "members": [{"id": "m", "name": "Kai"}],
            "groups": [{"id": "g1", "kind": "subsystem", "name": "Loop", "parent_id": "g2"},
                       {"id": "g2", "kind": "subsystem", "name": "Pool", "parent_id": "g1"}],
            "membership": [["g1", "m"], ["g2", "m"]]
        })));
        assert_eq!(h["m"].default, "kai.loop.pool.the-stars", "a cycle ends where it repeats");
        assert_eq!(system_segment(&Account { id: "0192f8c2-7d1e".into(), ..Default::default() }), "0192f8c2");
    }
}
