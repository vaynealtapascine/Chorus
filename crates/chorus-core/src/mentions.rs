//! Who a message mentions (SPEC §5.3), from its `mention` entities:
//!
//! - `@member`: that member.
//! - `@group`: every member in the group and in its subgroups.
//! - `@account` (shared spaces): that account, no member in particular.
//! - `@front`: whoever was fronting or co-conscious in the named account (`target_id`), else
//!   in the author's, when the message was *written* (its `occurred_at`), not when it arrives:
//!   a message written offline at 10:00 pings who was here at 10:00.
//!
//! The server notifies from this (`activity.rs`); apps can use it for a mention inbox. Callers
//! supply the lookups ([`Directory`]) from their own data.

use std::collections::BTreeSet;

use serde::Serialize;
use serde_json::Value;

/// What resolving needs to know.
pub trait Directory {
    /// The account a member belongs to.
    fn member_account(&self, member: &str) -> Option<String>;
    /// A group's account, its members and its direct subgroups (`None` if unknown or deleted).
    fn group(&self, group: &str) -> Option<(String, Vec<String>, Vec<String>)>;
    /// Members fronting or co-conscious in `account` at `t`.
    fn fronting_at(&self, account: &str, t: i64) -> Vec<String>;
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize)]
pub struct Mentioned {
    pub account: String,
    /// The member reached, if the mention reaches a member rather than an account.
    pub member: Option<String>,
    /// How: `member`, `group`, `account` or `front`.
    pub via: String,
}

/// Everyone the entities mention, once each (the first way they're reached wins), in entity
/// order. `author` is the writing account, `at` the message's `occurred_at`.
pub fn resolve(entities: &Value, author: &str, at: i64, dir: &dyn Directory) -> Vec<Mentioned> {
    let mut out: Vec<Mentioned> = Vec::new();
    let mut seen: BTreeSet<(String, Option<String>)> = BTreeSet::new();
    let mut add = |account: String, member: Option<String>, via: &str| {
        if seen.insert((account.clone(), member.clone())) {
            out.push(Mentioned { account, member, via: via.into() });
        }
    };
    for e in entities.as_array().into_iter().flatten() {
        if e.get("type").and_then(Value::as_str) != Some("mention") {
            continue;
        }
        let target = e.get("target_id").and_then(Value::as_str);
        match e.get("target_type").and_then(Value::as_str) {
            Some("member") => {
                if let Some(m) = target
                    && let Some(a) = dir.member_account(m)
                {
                    add(a, Some(m.to_string()), "member");
                }
            }
            Some("group") => {
                let Some(g) = target else { continue };
                let mut todo = vec![g.to_string()];
                let mut visited = BTreeSet::new();
                while let Some(g) = todo.pop() {
                    if !visited.insert(g.clone()) {
                        continue; // a cycle, or a group reached twice
                    }
                    if let Some((account, members, subgroups)) = dir.group(&g) {
                        for m in members {
                            add(account.clone(), Some(m), "group");
                        }
                        todo.extend(subgroups);
                    }
                }
            }
            Some("account") => {
                if let Some(a) = target {
                    add(a.to_string(), None, "account");
                }
            }
            Some("front") => {
                let account = target.unwrap_or(author);
                for m in dir.fronting_at(account, at) {
                    add(account.to_string(), Some(m), "front");
                }
            }
            _ => {}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    struct Dir;
    impl Directory for Dir {
        fn member_account(&self, m: &str) -> Option<String> {
            m.split_once('.').map(|(a, _)| a.to_string())
        }
        fn group(&self, g: &str) -> Option<(String, Vec<String>, Vec<String>)> {
            match g {
                "inner" => Some(("a".into(), vec!["a.kai".into()], vec!["core".into()])),
                "core" => Some(("a".into(), vec!["a.rin".into()], vec!["inner".into()])), // a cycle
                _ => None,
            }
        }
        fn fronting_at(&self, account: &str, t: i64) -> Vec<String> {
            match (account, t) {
                ("a", 10) => vec!["a.june".into()],
                ("a", _) => vec!["a.rin".into()],
                ("b", _) => vec!["b.sol".into()],
                _ => vec![],
            }
        }
    }

    fn mention(kind: &str, id: Option<&str>) -> Value {
        let mut e = json!({"type": "mention", "target_type": kind, "offset": 0, "length": 1});
        if let Some(id) = id {
            e["target_id"] = json!(id);
        }
        e
    }

    #[test]
    fn every_kind_resolves_once() {
        let e = json!([
            {"type": "bold", "offset": 0, "length": 1},
            mention("member", Some("a.kai")),
            mention("group", Some("inner")),
            mention("account", Some("b")),
            mention("front", None),
            mention("front", Some("b")),
            mention("member", Some("unknown")),
        ]);
        let got: Vec<(String, Option<String>, String)> =
            resolve(&e, "a", 10, &Dir).into_iter().map(|m| (m.account, m.member, m.via)).collect();
        let s = |x: &str| x.to_string();
        assert_eq!(
            got,
            vec![
                (s("a"), Some(s("a.kai")), s("member")),
                (s("a"), Some(s("a.rin")), s("group")),
                (s("b"), None, s("account")),
                (s("a"), Some(s("a.june")), s("front")),
                (s("b"), Some(s("b.sol")), s("front")),
            ]
        );
    }

    #[test]
    fn front_is_who_was_here_when_it_was_written() {
        let e = json!([mention("front", None)]);
        assert_eq!(resolve(&e, "a", 10, &Dir)[0].member.as_deref(), Some("a.june"));
        assert_eq!(resolve(&e, "a", 99, &Dir)[0].member.as_deref(), Some("a.rin"));
    }
}
