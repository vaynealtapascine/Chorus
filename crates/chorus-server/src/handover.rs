//! Handover and Notes to ourselves (D-132, D-162, DATA_MODEL §4.5): the server makes each account's
//! special channels, as ordinary ops so every device converges on the same two.
//!
//! A system gets `handover` and `notes` in its own (internal) space; a person gets `notes` only,
//! in an internal space of its own made for it (persons had none before D-132; clients show it
//! only as the Notes row, D-093). Called at enrolment and once at start-up for older accounts.
//! Idempotent: a role the space has ever had (a deleted channel included: it is restored from
//! Trash, never made again) is left alone, and so is any ordinary channel, whatever its name.

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::json;

use crate::ingest;

/// The roles an account kind gets, with the new channel's name.
fn roles(kind: &str) -> &'static [(&'static str, &'static str)] {
    if kind == "person" {
        &[("notes", "Notes to self")]
    } else {
        &[("handover", "Handover"), ("notes", "Notes to ourselves")]
    }
}

/// The account's own space: the earliest live internal space it owns.
fn own_space(conn: &Connection, account: &str) -> rusqlite::Result<Option<String>> {
    conn.query_row(
        "SELECT id FROM space WHERE owner_account_id = ?1 AND kind = 'internal' AND deleted_at IS NULL
         ORDER BY created_at, id LIMIT 1",
        [account],
        |r| r.get(0),
    )
    .optional()
}

/// Make whatever special channels the account is missing (and, for a person, the space to hold
/// them). Inside the caller's transaction, or in a savepoint of its own. Returns how many
/// channels it made.
pub fn ensure(conn: &Connection, account: &str, now: i64) -> anyhow::Result<usize> {
    let kind: Option<String> =
        conn.query_row("SELECT kind FROM account WHERE id = ?1", [account], |r| r.get(0)).optional()?;
    let Some(kind) = kind else { return Ok(0) };
    conn.execute_batch("SAVEPOINT specials")?;
    match make(conn, account, &kind, now) {
        Ok(n) => {
            conn.execute_batch("RELEASE specials")?;
            Ok(n)
        }
        Err(e) => {
            conn.execute_batch("ROLLBACK TO specials; RELEASE specials")?;
            Err(e)
        }
    }
}

fn make(conn: &Connection, account: &str, kind: &str, now: i64) -> anyhow::Result<usize> {
    let space = match own_space(conn, account)? {
        Some(s) => s,
        // a system without its own space (it deleted it) gets nothing back
        None if kind != "person" => return Ok(0),
        None => {
            let space = chorus_core::id::new_id(now as u64, rand::random());
            let scope = format!("space:{space}");
            ingest::grant(conn, account, &scope)?;
            ingest::server_op(
                conn,
                account,
                "space.create",
                &scope,
                Some(&space),
                json!({"kind": "internal", "name": "Notes"}),
                now,
            )?;
            ingest::server_op(conn, account, "space.join", &scope, Some(&space), json!({"account_id": account}), now)?;
            space
        }
    };
    let scope = format!("space:{space}");
    let mut made = 0;
    for (role, name) in roles(kind) {
        let had: bool = conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM channel WHERE space_id = ?1 AND special = ?2)",
            params![space, role],
            |r| r.get(0),
        )?;
        if had {
            continue;
        }
        let channel = chorus_core::id::new_id(now as u64, rand::random());
        ingest::server_op(
            conn,
            account,
            "channel.create",
            &scope,
            Some(&channel),
            json!({"space_id": space, "kind": "text", "name": name, "special": role}),
            now,
        )?;
        made += 1;
    }
    Ok(made)
}

/// Start-up: every account gets the special channels it is missing (D-162). Never keeps the
/// server from starting: an account that can't be done is logged and left as it was. Returns how
/// many channels were made.
pub fn backfill(conn: &Connection, now: i64) -> anyhow::Result<usize> {
    let accounts: Vec<String> =
        conn.prepare("SELECT id FROM account ORDER BY id")?.query_map([], |r| r.get(0))?.collect::<Result<_, _>>()?;
    let mut made = 0;
    for account in &accounts {
        match ensure(conn, account, now) {
            Ok(n) => made += n,
            Err(e) => {
                tracing::warn!(account = %account, error = %format!("{e:#}"), "special channels not made (D-132)")
            }
        }
    }
    Ok(made)
}
