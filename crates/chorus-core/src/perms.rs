//! Shared-space permissions (SPEC §5.1, DATA_MODEL §4.4, D-047, D-133): the pure rule.
//!
//! The server is the authority (`chorus-server/src/perms.rs` decides in SQL, property-tested
//! against [`resolve`]); clients call [`allowed`] through wasm/UniFFI to hide controls an account
//! can't use. Callers gather the [`Levels`] for one account (and, for channel permissions, one
//! channel) from their rows:
//!
//! 1. The space's owner, an `admin`, and any participant of a `dm` space (`manager`): everything.
//! 2. The channel's override for the account.
//! 3. Not a present member of the space (a guest of one channel): nothing more.
//! 4. The space's override for the account, then the channel's for the account's role, the
//!    space's for that role, the channel's for `everyone`, the space's for `everyone`.
//!    The first level that mentions the permission decides; at one level `deny` beats `allow`.
//! 5. Otherwise the role's [`base`] set.
//!
//! Space-only permissions ([`SPACE_ONLY`]) read only the space levels. Every channel permission
//! also needs `view`; space-only ones don't.

use serde::Deserialize;
use serde_json::Value;

/// Every permission, in the order clients list them.
pub const PERMISSIONS: &[&str] =
    &["view", "send", "react", "thread", "pin", "manage", "attach", "poll", "aside", "invite", "history"];

/// Permissions that exist only for a whole space (never on a channel override).
pub const SPACE_ONLY: &[&str] = &["invite", "history"];

/// The permissions D-133 added that every role already had in practice before it (sending files,
/// polls and asides, reading history): every role starts with them, so no existing space narrows.
pub const IMPLIED: &[&str] = &["attach", "poll", "aside", "history"];

const MEMBER: &[&str] = &["view", "send", "react", "thread", "pin", "attach", "poll", "aside", "history"];
const READ_ONLY: &[&str] = &["view", "react", "attach", "poll", "aside", "history"];

/// One override row: `allow` and `deny` lists of permissions.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(default)]
pub struct Level {
    pub allow: Vec<String>,
    pub deny: Vec<String>,
}

impl Level {
    fn says(&self, perm: &str) -> Option<bool> {
        if self.deny.iter().any(|p| p == perm) {
            Some(false)
        } else if self.allow.iter().any(|p| p == perm) {
            Some(true)
        } else {
            None
        }
    }
}

/// The overrides at one place (a channel, or the whole space) that apply to one account.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(default)]
pub struct Place {
    /// `target_type: account`, `target_id`: the account
    pub account: Option<Level>,
    /// `target_type: role`, `target_id`: the account's role
    pub role: Option<Level>,
    /// `target_type: role`, `target_id: everyone`
    pub everyone: Option<Level>,
}

/// Everything the rule needs about one account in one space (and one channel).
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(default)]
pub struct Levels {
    /// The space's owner, an `admin`, or a participant of a `dm` space.
    pub manager: bool,
    /// The account's role while it is a present member (`member` when no `space.set_role` gave it
    /// one); `None` for an account that isn't in the space.
    pub role: Option<String>,
    /// The space's custom roles (`space.roles`: `[{id, name, perms}]`).
    pub roles: Value,
    /// The channel's overrides (channel permissions only; a thread uses its parent's channel).
    pub channel: Place,
    /// The space's overrides (`space.set_permission`).
    pub space: Place,
}

/// A role's permissions when no override mentions them. `admin` has everything; a custom role
/// (one of `roles`) has what it lists plus [`IMPLIED`]; an unknown role has nothing.
pub fn base(role: &str, roles: &Value) -> Vec<&'static str> {
    match role {
        "admin" => PERMISSIONS.to_vec(),
        "member" => MEMBER.to_vec(),
        "read_only" => READ_ONLY.to_vec(),
        custom => {
            let Some(r) =
                roles.as_array().into_iter().flatten().find(|r| r.get("id").and_then(Value::as_str) == Some(custom))
            else {
                return Vec::new();
            };
            let listed: Vec<&str> =
                r.get("perms").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).collect();
            PERMISSIONS.iter().copied().filter(|p| listed.contains(p) || IMPLIED.contains(p)).collect()
        }
    }
}

/// One permission, by the rule above, without the `view` requirement.
pub fn resolve(l: &Levels, perm: &str) -> bool {
    if l.manager {
        return true;
    }
    let space_only = SPACE_ONLY.contains(&perm);
    let at = |level: &Option<Level>| level.as_ref().and_then(|x| x.says(perm));
    if !space_only && let Some(v) = at(&l.channel.account) {
        return v;
    }
    let Some(role) = l.role.as_deref() else { return false };
    let mut order: Vec<&Option<Level>> = Vec::with_capacity(6);
    order.push(&l.space.account);
    if !space_only {
        order.push(&l.channel.role);
    }
    order.push(&l.space.role);
    if !space_only {
        order.push(&l.channel.everyone);
    }
    order.push(&l.space.everyone);
    for level in order {
        if let Some(v) = at(level) {
            return v;
        }
    }
    base(role, &l.roles).contains(&perm)
}

/// Whether the account may use `perm`: [`resolve`], and for a channel permission `view` too.
pub fn can(l: &Levels, perm: &str) -> bool {
    resolve(l, perm) && (SPACE_ONLY.contains(&perm) || resolve(l, "view"))
}

/// Every permission the account has, in [`PERMISSIONS`] order.
pub fn allowed(l: &Levels) -> Vec<&'static str> {
    PERMISSIONS.iter().copied().filter(|p| can(l, p)).collect()
}

/// Why a `channel.set_permission` / `space.set_permission` list is invalid, if it is.
pub fn list_problem(v: Option<&Value>, space: bool) -> Option<String> {
    let ok = |p: &str| PERMISSIONS.contains(&p) && (space || !SPACE_ONLY.contains(&p));
    match v {
        None | Some(Value::Null) => None,
        Some(Value::Array(a)) if a.iter().all(|x| x.as_str().is_some_and(ok)) => None,
        _ => {
            let names: Vec<&str> = PERMISSIONS.iter().copied().filter(|p| ok(p)).collect();
            Some(format!("must be a list of {}", names.join(", ")))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn lv(allow: &[&str], deny: &[&str]) -> Option<Level> {
        Some(Level {
            allow: allow.iter().map(|s| s.to_string()).collect(),
            deny: deny.iter().map(|s| s.to_string()).collect(),
        })
    }

    #[test]
    fn existing_roles_keep_what_they_could_do() {
        let member = Levels { role: Some("member".into()), ..Default::default() };
        assert_eq!(allowed(&member), MEMBER, "members attach, poll, aside and read history as before");
        let ro = Levels { role: Some("read_only".into()), ..Default::default() };
        assert_eq!(allowed(&ro), READ_ONLY);
        let roles = json!([{"id": "mod", "perms": ["view", "send", "manage"]}]);
        let custom = Levels { role: Some("mod".into()), roles, ..Default::default() };
        assert_eq!(allowed(&custom), ["view", "send", "manage", "attach", "poll", "aside", "history"]);
        assert!(allowed(&Levels { role: Some("gone".into()), ..Default::default() }).is_empty());
        assert_eq!(allowed(&Levels { manager: true, ..Default::default() }), PERMISSIONS);
        assert!(!can(&member, "invite"), "inviting is off unless allowed");
    }

    #[test]
    fn the_most_specific_level_decides() {
        let mut l = Levels { role: Some("member".into()), ..Default::default() };
        l.space.everyone = lv(&["invite"], &["history", "attach"]);
        assert!(can(&l, "invite") && !can(&l, "history") && !can(&l, "attach"));
        l.channel.everyone = lv(&["attach"], &[]);
        assert!(can(&l, "attach"), "the channel beats the space at the same target");
        l.space.role = lv(&[], &["attach"]);
        assert!(!can(&l, "attach"), "a role beats everyone, wherever it is set");
        l.space.account = lv(&["attach", "history"], &[]);
        assert!(can(&l, "attach") && can(&l, "history"), "the account beats roles");
        l.channel.account = lv(&[], &["attach"]);
        assert!(!can(&l, "attach"));
        l.channel.account = lv(&["history"], &["invite"]);
        assert!(can(&l, "invite"), "space-only permissions ignore channel levels");
        l.space.account = lv(&["view"], &["view"]);
        assert!(!can(&l, "send"), "deny beats allow at one level, and everything needs view");
        assert!(can(&l, "invite"), "space-only permissions don't need view");
    }

    #[test]
    fn guests_get_only_their_channel_override() {
        let mut l = Levels::default();
        l.space.account = lv(&["view", "send", "history"], &[]);
        l.space.everyone = lv(&["view"], &[]);
        assert!(allowed(&l).is_empty());
        l.channel.account = lv(&["view", "attach"], &[]);
        assert_eq!(allowed(&l), ["view", "attach"]);
    }

    #[test]
    fn lists_are_checked_per_place() {
        assert!(list_problem(Some(&json!(["view", "history"])), true).is_none());
        assert!(list_problem(Some(&json!(["history"])), false).is_some(), "history is space-only");
        assert!(list_problem(Some(&json!(["rename"])), true).is_some());
        assert!(list_problem(None, false).is_none());
    }
}
