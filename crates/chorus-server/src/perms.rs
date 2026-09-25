//! Channel permissions (M5.10, D-047, SPEC §5.1): one rule, [`can_sql`], used by every path that
//! lets an account write to, receive, search or fetch something in a channel.
//!
//! - **Roles.** A space's owner (`space.owner_account_id`) can do everything, and so can an
//!   `admin`. The built-in `member` role may `view`, `send`, `react`, `thread` and `pin`;
//!   `read_only` may `view` and `react`; a custom role (`space.roles`, `{id, name, perms}`) has
//!   the perms it lists. In a DM every participant can do everything.
//! - **Overrides** (`channel_permission`, from `channel.set_permission`): per channel, for an
//!   account, for a role, or for the role `everyone` (every member of the space). The most
//!   specific level that says anything about a permission decides: account, then the account's
//!   role, then `everyone`, then the role's base. At one level, deny beats allow.
//! - **Every permission needs `view`.** A thread has its parent message's channel's permissions.
//! - **Guests.** An account override that allows `view` works for an account that isn't in the
//!   space at all: that is how one internal channel is shared with a partner. The server grants
//!   such a guest the space's scope ([`refresh_guest`]) and the sync rule
//!   (`visibility::op_visible_to`) lets through only the channels it may view, plus the space's
//!   name. Guests have no role, so only account overrides apply to them.
//! - An unknown channel grants nothing (ops that name it wait until it arrives).

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::Value;

use chorus_core::op::Op;

pub const PERMS: &[&str] = &["view", "send", "react", "thread", "pin", "manage"];
const MEMBER: &str = "'view','send','react','thread','pin'";
const READ_ONLY: &str = "'view','react'";

/// SQL: does an override at one level (`target_type`/`target_id` as SQL) allow or deny `perm`?
/// (Aliases start with `perm_`: the rule is nested in queries with their own `c`, `m`, `s`…)
fn override_sql(list: &str, target_type: &str, target_id: &str, perm: &str) -> String {
    format!(
        "EXISTS (SELECT 1 FROM channel_permission perm_cp, json_each(perm_cp.{list}) perm_x
                 WHERE perm_cp.channel_id = perm_c.id AND perm_cp.target_type = '{target_type}'
                   AND perm_cp.target_id = {target_id} AND perm_x.value = '{perm}')"
    )
}

/// One permission, without the `view` requirement.
fn one_sql(account: &str, channel: &str, perm: &str) -> String {
    assert!(PERMS.contains(&perm), "unknown permission {perm}");
    let deny = |t: &str, id: &str| override_sql("deny", t, id, perm);
    let allow = |t: &str, id: &str| override_sql("allow", t, id, perm);
    format!(
        "coalesce((SELECT CASE
            WHEN perm_s.owner_account_id = {account} THEN 1
            WHEN perm_sm.role IS NOT NULL AND perm_s.kind = 'dm' THEN 1
            WHEN perm_sm.role = 'admin' THEN 1
            WHEN {acct_deny} THEN 0
            WHEN {acct_allow} THEN 1
            WHEN perm_sm.role IS NULL THEN 0
            WHEN {role_deny} THEN 0
            WHEN {role_allow} THEN 1
            WHEN {all_deny} THEN 0
            WHEN {all_allow} THEN 1
            WHEN perm_sm.role = 'member' THEN '{perm}' IN ({MEMBER})
            WHEN perm_sm.role = 'read_only' THEN '{perm}' IN ({READ_ONLY})
            ELSE EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid(perm_s.roles) THEN perm_s.roles ELSE '[]' END) perm_r,
                              json_each(perm_r.value, '$.perms') perm_rp
                         WHERE json_extract(perm_r.value, '$.id') = perm_sm.role AND perm_rp.value = '{perm}')
          END
          FROM channel perm_c JOIN space perm_s ON perm_s.id = perm_c.space_id
          LEFT JOIN space_member perm_sm
            ON perm_sm.space_id = perm_s.id AND perm_sm.account_id = {account} AND perm_sm.is_present
          WHERE perm_c.id = coalesce((SELECT perm_pm.channel_id FROM channel perm_t
                                      JOIN message perm_pm ON perm_pm.id = perm_t.parent_message_id
                                      WHERE perm_t.id = {channel} AND perm_t.kind = 'thread'), {channel})), 0)",
        acct_deny = deny("account", account),
        acct_allow = allow("account", account),
        role_deny = deny("role", "perm_sm.role"),
        role_allow = allow("role", "perm_sm.role"),
        all_deny = deny("role", "'everyone'"),
        all_allow = allow("role", "'everyone'"),
    )
}

/// SQL boolean: may the account (an SQL expression, e.g. `?2`) use `perm` in the channel (an SQL
/// expression, e.g. `m.channel_id`)? `perm` is one of [`PERMS`].
pub fn can_sql(account: &str, channel: &str, perm: &str) -> String {
    if perm == "view" {
        one_sql(account, channel, "view")
    } else {
        format!("({} AND {})", one_sql(account, channel, perm), one_sql(account, channel, "view"))
    }
}

/// [`can_sql`] for one account and channel.
pub fn can(conn: &Connection, account: &str, channel: &str, perm: &str) -> rusqlite::Result<bool> {
    conn.prepare_cached(&format!("SELECT {}", can_sql("?1", "?2", perm)))?
        .query_row(params![account, channel], |r| r.get(0))
}

/// The space's owner, or an account present in it (not a guest of one of its channels).
pub fn is_member(conn: &Connection, account: &str, space: &str) -> rusqlite::Result<bool> {
    conn.prepare_cached(
        "SELECT EXISTS (SELECT 1 FROM space WHERE id = ?2 AND owner_account_id = ?1)
             OR EXISTS (SELECT 1 FROM space_member WHERE space_id = ?2 AND account_id = ?1 AND is_present)",
    )?
    .query_row(params![account, space], |r| r.get(0))
}

fn role(conn: &Connection, account: &str, space: &str) -> rusqlite::Result<Option<String>> {
    conn.prepare_cached("SELECT role FROM space_member WHERE space_id = ?1 AND account_id = ?2 AND is_present")?
        .query_row(params![space, account], |r| r.get(0))
        .optional()
}

fn message_channel(conn: &Connection, message: &str) -> rusqlite::Result<Option<(String, Option<String>)>> {
    conn.prepare_cached("SELECT channel_id, account_id FROM message WHERE id = ?1")?
        .query_row([message], |r| Ok((r.get::<_, Option<String>>(0)?.unwrap_or_default(), r.get(1)?)))
        .optional()
}

fn str_of<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
}

/// Why `author` may not write `o` in a space (`None`: it may). Checked at ingest (not for restore
/// pushes, which were checked when first accepted). Only space scopes have permissions; a space
/// that isn't projected yet (its own `space.create`) is left to the scope rules.
pub fn write_denied(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<Option<String>> {
    let Some(space) = o.scope.strip_prefix("space:") else { return Ok(None) };
    let row: Option<(String, Option<String>)> = conn
        .prepare_cached("SELECT owner_account_id, kind FROM space WHERE id = ?1")?
        .query_row([space], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    let Some((owner, kind)) = row else { return Ok(None) };
    // Whatever the op points at (a channel, a message, a thread's parent) must be in the op's own
    // space, whoever the author is: otherwise an op in one space could put a message, reaction,
    // pin or thread into another space's channel.
    if let Some(elsewhere) = foreign_space(conn, o, space)? {
        return Ok(Some(format!("that {elsewhere} is in another space")));
    }
    // Only a message's author edits it, whoever they are to the space: `manage` moderates
    // (delete, restore, pin), it doesn't put words in someone else's mouth (D-073).
    if o.kind == "message.edit"
        && let Some(id) = str_of(&o.payload, "message_id").or_else(|| o.entity())
        && let Some((_, Some(sender))) = message_channel(conn, id)?
        && sender != author
    {
        return Ok(Some("only its author can edit a message".into()));
    }
    if owner == author {
        return Ok(None);
    }
    let role = role(conn, author, space)?;
    let manager = role.as_deref() == Some("admin") || (kind.as_deref() == Some("dm") && role.is_some());
    let need = |channel: Option<&str>, perm: &str| -> anyhow::Result<Option<String>> {
        let Some(channel) = channel else { return Ok(Some("no such channel".into())) };
        Ok((!can(conn, author, channel, perm)?)
            .then(|| format!("you don't have the {perm} permission in this channel")))
    };
    let p = &o.payload;
    let k = o.kind.as_str();
    Ok(match k {
        "space.leave" if str_of(p, "account_id") == Some(author) && role.is_some() => None,
        _ if k.starts_with("space.") => {
            (!manager || kind.as_deref() == Some("dm")).then(|| "only the space's owner or an admin can do that".into())
        }
        "channel.create" => match str_of(p, "parent_message_id") {
            Some(parent) => match message_channel(conn, parent)? {
                Some((channel, _)) => need(Some(&channel), "thread")?,
                None => Some("the thread's message isn't here yet".into()),
            },
            None => (!manager).then(|| "only the space's owner or an admin can add channels".into()),
        },
        _ if k.starts_with("channel.") => need(o.entity(), "manage")?,
        "message.send" | "message.forward" => need(str_of(p, "channel_id"), "send")?,
        "message.pin" | "message.unpin" => {
            let channel = o.entity().map(|m| message_channel(conn, m)).transpose()?.flatten().map(|(c, _)| c);
            need(channel.as_deref(), "pin")?
        }
        _ if k.starts_with("message.") => {
            let id = str_of(p, "message_id").or_else(|| o.entity()).unwrap_or_default();
            match message_channel(conn, id)? {
                Some((channel, owner)) if owner.as_deref() == Some(author) => need(Some(&channel), "view")?,
                Some((channel, _)) => need(Some(&channel), "manage")?,
                // an own edit that arrived before its send: members may, guests wait
                None => role.is_none().then(|| "no such message".into()),
            }
        }
        _ if k.starts_with("reaction.") => {
            let id = str_of(p, "target_id").or_else(|| str_of(p, "message_id")).or_else(|| o.entity());
            match id.map(|m| message_channel(conn, m)).transpose()?.flatten() {
                Some((channel, _)) => need(Some(&channel), "react")?,
                None => Some("no such message".into()),
            }
        }
        _ if k.starts_with("read.") => {
            let channel = match str_of(p, "channel_id") {
                Some(c) => Some(c.to_string()),
                None => {
                    str_of(p, "message_id").map(|m| message_channel(conn, m)).transpose()?.flatten().map(|(c, _)| c)
                }
            };
            need(channel.as_deref(), "view")?
        }
        // files are only ever reachable through a message someone may view
        _ if k.starts_with("attachment.") => None,
        _ => role.is_none().then(|| "only the space's members can do that".into()),
    })
}

/// What `o` refers to in a space other than `space` ("channel" or "message"), if anything.
/// Things not projected yet are left alone (their op waits in the log like any early op).
fn foreign_space(conn: &Connection, o: &Op, space: &str) -> anyhow::Result<Option<&'static str>> {
    let p = &o.payload;
    let k = o.kind.as_str();
    let channel_space = |id: &str| -> rusqlite::Result<Option<String>> {
        conn.prepare_cached(
            "SELECT c.space_id FROM channel c WHERE c.id = coalesce((SELECT pm.channel_id FROM channel t
               JOIN message pm ON pm.id = t.parent_message_id WHERE t.id = ?1 AND t.kind = 'thread'), ?1)",
        )?
        .query_row([id], |r| r.get(0))
        .optional()
        .map(Option::flatten)
    };
    let message_space = |id: &str| -> rusqlite::Result<Option<String>> {
        match message_channel(conn, id)? {
            Some((channel, _)) => channel_space(&channel),
            None => Ok(None),
        }
    };
    let (what, home) = if k == "channel.create" {
        // a channel is created in the op's space: `space_id` may only repeat it
        if str_of(p, "space_id").is_some_and(|s| s != space) {
            return Ok(Some("channel"));
        }
        match str_of(p, "parent_message_id") {
            Some(parent) => ("message", message_space(parent)?),
            None => return Ok(None),
        }
    } else if k.starts_with("channel.") {
        ("channel", o.entity().map(channel_space).transpose()?.flatten())
    } else if matches!(k, "message.send" | "message.forward")
        || (k.starts_with("read.") && str_of(p, "channel_id").is_some())
    {
        ("channel", str_of(p, "channel_id").map(channel_space).transpose()?.flatten())
    } else if k.starts_with("message.") || k.starts_with("reaction.") || k.starts_with("read.") {
        let id = str_of(p, "target_id").or_else(|| str_of(p, "message_id")).or_else(|| o.entity());
        if k.starts_with("reaction.") && str_of(p, "target_type") == Some("post") {
            return Ok(None);
        }
        ("message", id.map(message_space).transpose()?.flatten())
    } else {
        return Ok(None);
    };
    Ok(home.is_some_and(|h| h != space).then_some(what))
}

/// Keep a guest's scope in step with its overrides: an account that isn't in the space has the
/// space's scope exactly while some channel of it has an account override allowing it `view`
/// (and not denying it). Members and the owner are left alone (`project::space_access`).
pub fn refresh_guest(conn: &Connection, account: &str, space: &str) -> rusqlite::Result<()> {
    if is_member(conn, account, space)? {
        return Ok(());
    }
    let guest: bool = conn
        .prepare_cached(
            "SELECT EXISTS (SELECT 1 FROM channel_permission cp JOIN channel c ON c.id = cp.channel_id
               WHERE c.space_id = ?2 AND cp.target_type = 'account' AND cp.target_id = ?1
                 AND EXISTS (SELECT 1 FROM json_each(cp.allow) WHERE value = 'view')
                 AND NOT EXISTS (SELECT 1 FROM json_each(cp.deny) WHERE value = 'view'))",
        )?
        .query_row(params![account, space], |r| r.get(0))?;
    let scope = format!("space:{space}");
    if guest {
        conn.execute("INSERT OR IGNORE INTO scope_access(account_id, scope) VALUES (?1, ?2)", params![account, scope])?;
    } else {
        conn.execute("DELETE FROM scope_access WHERE account_id = ?1 AND scope = ?2", params![account, scope])?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db;

    /// A shared space `s` owned by `owner` with channel `ch`, a thread `th` under message `m` in it,
    /// and members with the given roles.
    fn space(kind: &str, roles: &[(&str, &str)]) -> Connection {
        let mut c = db::open_memory().unwrap();
        db::migrate(&mut c).unwrap();
        c.execute(
            "INSERT INTO space(id,kind,owner_account_id,roles,created_at) VALUES ('s',?1,'owner',
               '[{\"id\":\"mod\",\"name\":\"Mods\",\"perms\":[\"view\",\"send\",\"pin\",\"manage\"]}]',0)",
            [kind],
        )
        .unwrap();
        c.execute("INSERT INTO channel(id,space_id,kind,created_at) VALUES ('ch','s','text',0)", []).unwrap();
        c.execute("INSERT INTO message(id,channel_id,account_id,occurred_at) VALUES ('m','ch','owner',0)", []).unwrap();
        c.execute(
            "INSERT INTO channel(id,space_id,kind,parent_message_id,created_at) VALUES ('th','s','thread','m',0)",
            [],
        )
        .unwrap();
        for (account, role) in roles {
            c.execute(
                "INSERT INTO space_member(space_id,account_id,role,joined_hlc) VALUES ('s',?1,?2,'1:0:1')",
                [account, role],
            )
            .unwrap();
        }
        c
    }

    fn set(c: &Connection, target_type: &str, target: &str, allow: &[&str], deny: &[&str]) {
        c.execute(
            "INSERT OR REPLACE INTO channel_permission(channel_id,target_type,target_id,allow,deny,hlc)
             VALUES ('ch',?1,?2,?3,?4,'1:0:1')",
            params![target_type, target, serde_json::json!(allow).to_string(), serde_json::json!(deny).to_string()],
        )
        .unwrap();
    }

    fn perms_of(c: &Connection, account: &str, channel: &str) -> Vec<&'static str> {
        PERMS.iter().copied().filter(|p| can(c, account, channel, p).unwrap()).collect()
    }

    #[test]
    fn roles_give_their_base_permissions() {
        let c = space(
            "shared",
            &[("mem", "member"), ("ro", "read_only"), ("adm", "admin"), ("m2", "mod"), ("odd", "nope")],
        );
        assert_eq!(perms_of(&c, "owner", "ch"), PERMS);
        assert_eq!(perms_of(&c, "adm", "ch"), PERMS);
        assert_eq!(perms_of(&c, "mem", "ch"), ["view", "send", "react", "thread", "pin"]);
        assert_eq!(perms_of(&c, "ro", "ch"), ["view", "react"]);
        assert_eq!(perms_of(&c, "m2", "ch"), ["view", "send", "pin", "manage"], "a custom role has what it lists");
        assert!(perms_of(&c, "odd", "ch").is_empty(), "an unknown role has nothing");
        assert!(perms_of(&c, "stranger", "ch").is_empty());
        assert!(perms_of(&c, "mem", "missing").is_empty(), "an unknown channel grants nothing");
        assert_eq!(perms_of(&c, "mem", "th"), perms_of(&c, "mem", "ch"), "a thread has its parent's channel's");
    }

    #[test]
    fn the_most_specific_override_decides_and_deny_wins_a_tie() {
        let c = space("shared", &[("mem", "member"), ("ro", "read_only"), ("adm", "admin")]);
        // a private channel: nobody but the owner and admins…
        set(&c, "role", "everyone", &[], &["view"]);
        assert!(perms_of(&c, "mem", "ch").is_empty(), "no view, nothing else either");
        assert!(perms_of(&c, "ro", "th").is_empty());
        assert_eq!(perms_of(&c, "adm", "ch"), PERMS, "admins aren't bound by overrides");
        // …but read_only members may look, and one member may talk
        set(&c, "role", "read_only", &["view"], &[]);
        assert_eq!(perms_of(&c, "ro", "ch"), ["view", "react"]);
        set(&c, "account", "mem", &["view", "manage"], &[]);
        assert_eq!(perms_of(&c, "mem", "ch"), PERMS, "the account level beats the everyone level");
        // at one level, deny beats allow
        set(&c, "account", "mem", &["view", "send"], &["send"]);
        assert_eq!(perms_of(&c, "mem", "ch"), ["view", "react", "thread", "pin"]);
        // a role deny beats an everyone allow, and an account allow beats a role deny
        set(&c, "role", "everyone", &["manage"], &[]);
        set(&c, "role", "member", &[], &["manage", "pin"]);
        set(&c, "account", "mem", &["pin"], &[]);
        assert_eq!(perms_of(&c, "mem", "ch"), ["view", "send", "react", "thread", "pin"]);
    }

    #[test]
    fn guests_and_dms() {
        let c = space("shared", &[("mem", "member")]);
        set(&c, "role", "everyone", &["view"], &[]);
        assert!(perms_of(&c, "guest", "ch").is_empty(), "role overrides never reach a guest");
        set(&c, "account", "guest", &["view", "react"], &[]);
        assert_eq!(perms_of(&c, "guest", "ch"), ["view", "react"]);
        assert!(!is_member(&c, "guest", "s").unwrap());
        refresh_guest(&c, "guest", "s").unwrap();
        let has = |c: &Connection| -> bool {
            c.query_row(
                "SELECT EXISTS(SELECT 1 FROM scope_access WHERE account_id='guest' AND scope='space:s')",
                [],
                |r| r.get(0),
            )
            .unwrap()
        };
        assert!(has(&c), "an account allowed to view a channel gets the space's scope");
        set(&c, "account", "guest", &["view"], &["view"]);
        refresh_guest(&c, "guest", "s").unwrap();
        assert!(!has(&c), "and loses it with the last such override");

        let dm = space("dm", &[("a", "member"), ("b", "member")]);
        set(&dm, "account", "b", &[], &["send"]);
        assert_eq!(perms_of(&dm, "b", "ch"), PERMS, "in a DM both sides can do everything");
    }
}
