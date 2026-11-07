//! Member handles on the server (D-126): the same defaults the apps work out
//! (`chorus_core::handles`), what other accounts see of them, and the one namespace claimed
//! handles share with account handles.
//!
//! Other accounts see a member's **shown** handle (the first claimed one, else the default) and
//! the claimed list, never a default the member has claimed past: claiming is how a member stops
//! showing which subsystem they are in.

use std::collections::{BTreeMap, HashMap};

use chorus_core::handles::{self, Account, Group, Handles, Input, Member};
use chorus_core::op::Op;
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

/// Every member's handles in one account, archived and deleted included (their defaults hold
/// their place, so nobody else's moves when they leave).
pub fn of_account(conn: &Connection, account: &str) -> rusqlite::Result<BTreeMap<String, Handles>> {
    let (handle, name): (Option<String>, Option<String>) = conn
        .query_row(
            "SELECT a.handle, COALESCE(NULLIF(trim(s.name), ''), NULLIF(trim(a.display_name), ''))
             FROM account a LEFT JOIN system s ON s.account_id = a.id WHERE a.id = ?1",
            [account],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?
        .unwrap_or((None, None));
    let mut st = conn.prepare_cached(
        "SELECT id, name, display_name, short_id, is_self, handles FROM member WHERE account_id = ?1",
    )?;
    let members = st
        .query_map([account], |r| {
            Ok(Member {
                id: r.get(0)?,
                name: r.get(1)?,
                display_name: r.get(2)?,
                short_id: r.get(3)?,
                is_self: r.get::<_, Option<bool>>(4)?.unwrap_or(false),
                handles: serde_json::from_str(&r.get::<_, Option<String>>(5)?.unwrap_or_default()).unwrap_or(Value::Null),
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    let mut st = conn.prepare_cached(
        "SELECT id, kind, parent_id, name FROM member_group WHERE account_id = ?1 AND deleted_at IS NULL",
    )?;
    let groups = st
        .query_map([account], |r| Ok(Group { id: r.get(0)?, kind: r.get(1)?, parent_id: r.get(2)?, name: r.get(3)? }))?
        .collect::<Result<Vec<_>, _>>()?;
    let mut st = conn.prepare_cached(
        "SELECT gm.group_id, gm.member_id FROM group_membership gm JOIN member_group g ON g.id = gm.group_id
         WHERE g.account_id = ?1 AND g.deleted_at IS NULL AND gm.is_present",
    )?;
    let membership = st.query_map([account], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<Result<Vec<_>, _>>()?;
    let input = Input { account: Account { id: account.to_string(), handle, name }, members, groups, membership };
    Ok(handles::member_handles(&input))
}

/// Handles worked out once per account for one response.
#[derive(Default)]
pub struct Book {
    accounts: HashMap<String, BTreeMap<String, Handles>>,
}

impl Book {
    pub fn get(&mut self, conn: &Connection, account: &str, member: &str) -> rusqlite::Result<Option<Handles>> {
        if !self.accounts.contains_key(account) {
            self.accounts.insert(account.to_string(), of_account(conn, account)?);
        }
        Ok(self.accounts[account].get(member).cloned())
    }

    /// What `viewer` sees: everything for their own members; the shown handle and the claimed
    /// list for anyone else's.
    pub fn card_fields(&mut self, conn: &Connection, viewer: &str, account: &str, member: &str) -> rusqlite::Result<Value> {
        Ok(match self.get(conn, account, member)? {
            Some(h) if viewer == account => json!({"handle": h.shown, "handles": h.claimed, "default_handle": h.default}),
            Some(h) => json!({"handle": h.shown, "handles": h.claimed}),
            None => json!({"handle": null, "handles": []}),
        })
    }
}

/// Copy [`Book::card_fields`] into a member card that carries `id` (and `account_id`, else
/// `account`).
pub fn stamp(conn: &Connection, book: &mut Book, viewer: &str, account: &str, card: &mut Value) -> rusqlite::Result<()> {
    let Some(id) = card["id"].as_str().map(str::to_string) else { return Ok(()) };
    if let Value::Object(fields) = book.card_fields(conn, viewer, account, &id)?
        && let Some(obj) = card.as_object_mut()
    {
        obj.extend(fields);
    }
    Ok(())
}

/// Whether a handle is free for `account` (its own account handle) or for `member` of `account`
/// (a claim): no other account has it as its handle and no other member claims it.
pub fn taken(conn: &Connection, handle: &str, account: &str, member: Option<&str>) -> rusqlite::Result<bool> {
    let account_has = conn
        .query_row(
            "SELECT 1 FROM account WHERE lower(handle) = ?1 AND (id <> ?2 OR ?3 IS NOT NULL)",
            params![handle, account, member],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
    if account_has {
        return Ok(true);
    }
    Ok(conn
        .query_row(
            "SELECT 1 FROM member m, json_each(CASE WHEN json_valid(m.handles) THEN m.handles ELSE '[]' END) h
             WHERE h.value = ?1 AND m.id IS NOT ?2 AND m.deleted_at IS NULL LIMIT 1",
            params![handle, member],
            |_| Ok(()),
        )
        .optional()?
        .is_some())
}

/// Why `o` can't take the handle it names, if it can't: an account's new handle, or a member's
/// claims. Checked at ingest, so of two devices that claimed the same handle offline the later
/// one hears it is taken (a sync issue on that device).
pub fn refused(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<Option<String>> {
    match o.kind.as_str() {
        "account.set" => {
            let Some(h) = o.payload.get("handle").filter(|h| !h.is_null()) else { return Ok(None) };
            let Some(h) = h.as_str().filter(|h| handles::valid_claim(h)) else {
                return Ok(Some("a handle is 2–32 of a-z, 0-9 and _".into()));
            };
            Ok(taken(conn, h, author, None)?.then(|| format!("@{h} is taken")))
        }
        "member.create" | "member.set" => {
            let (Some(list), Some(member)) = (o.payload.get("handles"), o.entity()) else { return Ok(None) };
            for h in handles::claims(list) {
                if taken(conn, &h, author, Some(member))? {
                    return Ok(Some(format!("@{h} is taken")));
                }
            }
            Ok(None)
        }
        _ => Ok(None),
    }
}

/// Give every account with a handle an `account.set {handle}` op, so its devices know it offline
/// (the `.system` part of every default). Enrolment writes one from now on; this covers older
/// accounts and is a no-op once each has one.
pub fn backfill_account_handles(conn: &Connection, now: i64) -> anyhow::Result<usize> {
    let mut st = conn.prepare(
        "SELECT a.id, a.handle FROM account a WHERE a.handle IS NOT NULL AND a.handle <> ''
         AND NOT EXISTS (SELECT 1 FROM op WHERE op.scope = 'account:' || a.id AND op.kind = 'account.set'
                         AND op.status = 'applied' AND json_extract(op.payload, '$.handle') IS NOT NULL)",
    )?;
    let rows: Vec<(String, String)> = st.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<Result<_, _>>()?;
    for (id, handle) in &rows {
        crate::ingest::server_op(conn, id, "account.set", &format!("account:{id}"), Some(id), json!({"handle": handle}), now)?;
    }
    Ok(rows.len())
}
