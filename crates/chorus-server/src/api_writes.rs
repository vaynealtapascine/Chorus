//! Writes through the API (API.md §4): `POST /front/switch` for NFC tags, Tasker, Home Assistant
//! and scripts. The request becomes an ordinary `front.switch` op in the account's scope, made on
//! the server and attributed to the token's pseudo-device `token:<id>` (or to `server` when a
//! signed-in session calls it), so devices, followers, the stream and webhooks see it exactly like a
//! switch from the app.

use chorus_core::op::Op;
use rusqlite::Connection;
use serde::Deserialize;
use serde_json::{Value, json};

use crate::api_data::{DataError, Principal};
use crate::ingest;

/// One entry: either ids (`subject_type` + `subject_id`), or a name for scripts (`member`,
/// `group` or `state`), matched without case against name and display name.
#[derive(Deserialize, Debug, Default)]
#[serde(deny_unknown_fields)]
pub struct EntryIn {
    pub subject_type: Option<String>,
    pub subject_id: Option<String>,
    pub member: Option<String>,
    pub group: Option<String>,
    pub state: Option<String>,
    pub level: Option<String>,
    pub is_primary: Option<bool>,
}

#[derive(Deserialize, Debug, Default)]
#[serde(deny_unknown_fields)]
pub struct SwitchIn {
    /// Who is here now; empty means "switch out" (nobody).
    pub entries: Vec<EntryIn>,
    /// A typed time (ms), up to a minute ahead; default now.
    pub occurred_at: Option<i64>,
    pub note: Option<String>,
    /// `default` | `silent` | `now` | `extra_delay` (NOTIFICATIONS.md).
    pub notify: Option<String>,
}

fn by_name(conn: &Connection, account: &str, table: &str, name: &str) -> Result<String, DataError> {
    let sql = match table {
        "member" => {
            "SELECT id FROM member WHERE account_id = ?1 AND deleted_at IS NULL
             AND (lower(name) = lower(?2) OR lower(display_name) = lower(?2))
             ORDER BY archived_at IS NOT NULL, id"
        }
        "group" => {
            "SELECT id FROM member_group WHERE account_id = ?1 AND deleted_at IS NULL AND lower(name) = lower(?2)
             ORDER BY id"
        }
        _ => {
            "SELECT id FROM custom_state WHERE account_id = ?1 AND deleted_at IS NULL AND lower(name) = lower(?2)
             ORDER BY id"
        }
    };
    let mut st = conn.prepare_cached(sql)?;
    let ids: Vec<String> = st.query_map([account, name.trim()], |r| r.get(0))?.collect::<Result<_, _>>()?;
    match ids.as_slice() {
        [one] => Ok(one.clone()),
        [] => Err(DataError::Bad(format!("no {table} called {name:?}"))),
        _ => Err(DataError::Bad(format!("more than one {table} is called {name:?}; use subject_id"))),
    }
}

fn owns(conn: &Connection, account: &str, subject_type: &str, id: &str) -> Result<bool, DataError> {
    let table = match subject_type {
        "member" => "member",
        "group" => "member_group",
        "state" => "custom_state",
        _ => return Err(DataError::Bad(format!("subject_type {subject_type:?} must be member, group or state"))),
    };
    let sql = format!("SELECT EXISTS (SELECT 1 FROM {table} WHERE id = ?1 AND account_id = ?2 AND deleted_at IS NULL)");
    Ok(conn.query_row(&sql, [id, account], |r| r.get(0))?)
}

/// Turn the request into `front.switch` entries (validated against the account's own subjects).
pub fn entries(conn: &Connection, account: &str, input: &[EntryIn]) -> Result<Vec<Value>, DataError> {
    let mut out = Vec::with_capacity(input.len());
    for e in input {
        let (t, id) = match (&e.subject_type, &e.subject_id, &e.member, &e.group, &e.state) {
            (Some(t), Some(id), None, None, None) => {
                if !owns(conn, account, t, id)? {
                    return Err(DataError::Bad(format!("no {t} with id {id}")));
                }
                (t.clone(), id.clone())
            }
            (None, None, Some(n), None, None) => ("member".into(), by_name(conn, account, "member", n)?),
            (None, None, None, Some(n), None) => ("group".into(), by_name(conn, account, "group", n)?),
            (None, None, None, None, Some(n)) => ("state".into(), by_name(conn, account, "state", n)?),
            _ => {
                return Err(DataError::Bad(
                    "each entry needs subject_type + subject_id, or exactly one of member/group/state".into(),
                ));
            }
        };
        let level = e.level.clone().unwrap_or_else(|| "front".into());
        if !matches!(level.as_str(), "front" | "cocon" | "present") {
            return Err(DataError::Bad(format!("level {level:?} must be front, cocon or present")));
        }
        out.push(
            json!({"subject_type": t, "subject_id": id, "level": level, "is_primary": e.is_primary.unwrap_or(false)}),
        );
    }
    // like the app: someone fronting is primary unless the caller said who is
    if !out.iter().any(|e| e["is_primary"] == true)
        && let Some(first) = out.iter_mut().find(|e| e["level"] == "front")
    {
        first["is_primary"] = json!(true);
    }
    Ok(out)
}

/// Record a switch for the caller's account. Returns the accepted op (for the fan-out).
pub fn switch(conn: &Connection, p: &Principal, input: &SwitchIn, now: i64) -> Result<Op, DataError> {
    if !p.allows("write:front") {
        return Err(DataError::Scope("write:front"));
    }
    if let Some(t) = input.occurred_at
        && t > now + 60_000
    {
        return Err(DataError::Bad("occurred_at is in the future".into()));
    }
    let notify = input.notify.clone().unwrap_or_else(|| "default".into());
    if !matches!(notify.as_str(), "default" | "silent" | "now" | "extra_delay") {
        return Err(DataError::Bad(format!("notify {notify:?} must be default, silent, now or extra_delay")));
    }
    let entries = entries(conn, &p.account_id, &input.entries)?;
    let mut payload = json!({"entries": entries, "notify": notify});
    if let Some(n) = input.note.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
        payload["note"] = json!(n);
    }
    let device = match &p.token_id {
        Some(id) => format!("token:{id}"),
        None => ingest::SERVER_DEVICE.to_string(),
    };
    let id = chorus_core::id::new_id(now as u64, rand::random());
    let scope = format!("account:{}", p.account_id);
    let (ack, op) = ingest::op_as(
        conn,
        &p.account_id,
        &device,
        "front.switch",
        &scope,
        Some(&id),
        payload,
        now,
        input.occurred_at,
    )?;
    op.ok_or_else(|| DataError::Bad(ack.error.map(|e| e.message).unwrap_or_else(|| "switch rejected".into())))
}
