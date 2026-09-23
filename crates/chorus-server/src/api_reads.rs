//! More of the read API (API.md §3, M2.7): who am I, one member in full, groups, fields, custom
//! front states, daily totals and open reviews. Like `api_data`, only the caller's own account.

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::api_data::{DataError, Principal};

fn need(p: &Principal, scope: &'static str) -> Result<(), DataError> {
    if p.allows(scope) { Ok(()) } else { Err(DataError::Scope(scope)) }
}

fn parse(s: Option<String>) -> Value {
    s.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null)
}

/// `GET /me`: the account, how the caller is signed in, and (for devices) the account's devices.
pub fn me(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    let account = conn.query_row(
        "SELECT id, kind, handle, display_name, is_admin, created_at FROM account WHERE id = ?1",
        [&p.account_id],
        |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "kind": r.get::<_, String>(1)?,
                "handle": r.get::<_, Option<String>>(2)?,
                "display_name": r.get::<_, Option<String>>(3)?,
                "is_admin": r.get::<_, bool>(4)?,
                "created_at": r.get::<_, i64>(5)?,
            }))
        },
    )?;
    let devices = if p.is_device() {
        let mut st = conn.prepare(
            "SELECT id, name, platform, created_at, last_seen_at FROM device
             WHERE account_id = ?1 AND revoked_at IS NULL AND platform != 'token' ORDER BY created_at",
        )?;
        let rows = st.query_map([&p.account_id], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "name": r.get::<_, String>(1)?,
                "platform": r.get::<_, String>(2)?,
                "created_at": r.get::<_, i64>(3)?,
                "last_seen_at": r.get::<_, Option<i64>>(4)?,
            }))
        })?;
        Some(rows.collect::<Result<Vec<_>, _>>()?)
    } else {
        None
    };
    Ok(json!({
        "account": account,
        "via": if p.is_device() { "device" } else { "token" },
        "scopes": p.scopes(),
        "devices": devices,
    }))
}

/// `GET /members/{id}`: everything about one member, with group ids and custom field values.
pub fn member(conn: &Connection, p: &Principal, id: &str) -> Result<Option<Value>, DataError> {
    need(p, "read:members")?;
    let m = conn
        .query_row(
            "SELECT id, name, display_name, pronouns, description, description_entities, color, avatar_blob,
                    banner_blob, birthday, sigils, proxy_tags, is_self, short_id, pk_id, created_at, archived_at
             FROM member WHERE id = ?1 AND account_id = ?2 AND deleted_at IS NULL",
            params![id, p.account_id],
            |r| {
                Ok(json!({
                    "id": r.get::<_, String>(0)?,
                    "name": r.get::<_, Option<String>>(1)?,
                    "display_name": r.get::<_, Option<String>>(2)?,
                    "pronouns": r.get::<_, Option<String>>(3)?,
                    "description": r.get::<_, Option<String>>(4)?,
                    "description_entities": parse(r.get(5)?),
                    "color": r.get::<_, Option<String>>(6)?,
                    "avatar_blob": r.get::<_, Option<String>>(7)?,
                    "banner_blob": r.get::<_, Option<String>>(8)?,
                    "birthday": r.get::<_, Option<String>>(9)?,
                    "sigils": parse(r.get(10)?),
                    "proxy_tags": parse(r.get(11)?),
                    "is_self": r.get::<_, bool>(12)?,
                    "short_id": r.get::<_, Option<String>>(13)?,
                    "pk_id": r.get::<_, Option<String>>(14)?,
                    "created_at": r.get::<_, i64>(15)?,
                    "archived": r.get::<_, Option<i64>>(16)?.is_some(),
                }))
            },
        )
        .optional()?;
    let Some(mut m) = m else { return Ok(None) };
    let mut st = conn.prepare_cached(
        "SELECT gm.group_id FROM group_membership gm JOIN member_group g ON g.id = gm.group_id
         WHERE gm.member_id = ?1 AND gm.is_present AND g.deleted_at IS NULL ORDER BY g.sort_key, g.name",
    )?;
    let groups: Vec<String> = st.query_map([id], |r| r.get(0))?.collect::<Result<_, _>>()?;
    let mut st = conn.prepare_cached(
        "SELECT f.id, f.name, f.type, v.value FROM field_value v JOIN field_def f ON f.id = v.field_id
         WHERE v.member_id = ?1 AND f.account_id = ?2 AND f.deleted_at IS NULL ORDER BY f.sort_key, f.name",
    )?;
    let fields: Vec<Value> = st
        .query_map(params![id, p.account_id], |r| {
            Ok(json!({
                "field_id": r.get::<_, String>(0)?,
                "name": r.get::<_, Option<String>>(1)?,
                "type": r.get::<_, Option<String>>(2)?,
                "value": parse(r.get(3)?),
            }))
        })?
        .collect::<Result<_, _>>()?;
    m["groups"] = json!(groups);
    m["fields"] = json!(fields);
    Ok(Some(m))
}

/// `GET /groups`: flat list with `effective_parent_id` (build the tree client-side) and members.
pub fn groups(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    need(p, "read:members")?;
    let mut st = conn.prepare(
        "SELECT id, kind, parent_id, effective_parent_id, name, description, color, icon, can_front, sort_key
         FROM member_group WHERE account_id = ?1 AND deleted_at IS NULL ORDER BY sort_key, name",
    )?;
    let mut members = conn.prepare_cached(
        "SELECT gm.member_id FROM group_membership gm JOIN member m ON m.id = gm.member_id
         WHERE gm.group_id = ?1 AND gm.is_present AND m.deleted_at IS NULL ORDER BY m.name",
    )?;
    let rows: Vec<Value> = st
        .query_map([&p.account_id], |r| {
            Ok(json!({
                "id": r.get::<_, String>(0)?,
                "kind": r.get::<_, Option<String>>(1)?,
                "parent_id": r.get::<_, Option<String>>(2)?,
                "effective_parent_id": r.get::<_, Option<String>>(3)?,
                "name": r.get::<_, Option<String>>(4)?,
                "description": r.get::<_, Option<String>>(5)?,
                "color": r.get::<_, Option<String>>(6)?,
                "icon": r.get::<_, Option<String>>(7)?,
                "can_front": r.get::<_, bool>(8)?,
                "sort_key": r.get::<_, Option<String>>(9)?,
            }))
        })?
        .collect::<Result<_, _>>()?;
    let mut items = Vec::with_capacity(rows.len());
    for mut g in rows {
        let id = g["id"].as_str().unwrap_or_default().to_string();
        let ids: Vec<String> = members.query_map([&id], |r| r.get(0))?.collect::<Result<_, _>>()?;
        g["member_ids"] = json!(ids);
        items.push(g);
    }
    Ok(json!({"items": items}))
}

/// `GET /fields`: custom field definitions.
pub fn fields(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    need(p, "read:members")?;
    let mut st = conn.prepare(
        "SELECT id, name, type, options, sort_key FROM field_def
         WHERE account_id = ?1 AND deleted_at IS NULL ORDER BY sort_key, name",
    )?;
    let rows = st.query_map([&p.account_id], |r| {
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "name": r.get::<_, Option<String>>(1)?,
            "type": r.get::<_, Option<String>>(2)?,
            "options": parse(r.get(3)?),
            "sort_key": r.get::<_, Option<String>>(4)?,
        }))
    })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

/// `GET /states`: custom front states (the "who's here" entries that aren't members or groups).
pub fn states(conn: &Connection, p: &Principal) -> Result<Value, DataError> {
    need(p, "read:front")?;
    let mut st = conn.prepare(
        "SELECT id, name, description, color, icon, sort_key FROM custom_state
         WHERE account_id = ?1 AND deleted_at IS NULL ORDER BY sort_key, name",
    )?;
    let rows = st.query_map([&p.account_id], |r| {
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "name": r.get::<_, Option<String>>(1)?,
            "description": r.get::<_, Option<String>>(2)?,
            "color": r.get::<_, Option<String>>(3)?,
            "icon": r.get::<_, Option<String>>(4)?,
            "sort_key": r.get::<_, Option<String>>(5)?,
        }))
    })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

fn day_ok(d: &str) -> bool {
    let b = d.as_bytes();
    b.len() == 10 && b.iter().enumerate().all(|(i, c)| if i == 4 || i == 7 { *c == b'-' } else { c.is_ascii_digit() })
}

/// `GET /front/daily?from=YYYY-MM-DD&to=YYYY-MM-DD&level=`: seconds per subject per local day
/// (`to` inclusive), from the same table the insights use.
pub fn daily(
    conn: &Connection,
    p: &Principal,
    from: Option<&str>,
    to: Option<&str>,
    level: Option<&str>,
) -> Result<Value, DataError> {
    need(p, "read:front")?;
    for d in [from, to].into_iter().flatten() {
        if !day_ok(d) {
            return Err(DataError::Bad(format!("{d:?} isn't a YYYY-MM-DD day")));
        }
    }
    let mut st = conn.prepare_cached(
        "SELECT day, subject_type, subject_id, level, seconds, as_primary_seconds FROM front_daily
         WHERE account_id = ?1 AND day >= ?2 AND day <= ?3 AND (?4 IS NULL OR level = ?4)
         ORDER BY day, subject_type, subject_id, level",
    )?;
    let rows =
        st.query_map(params![p.account_id, from.unwrap_or("0000-00-00"), to.unwrap_or("9999-99-99"), level], |r| {
            Ok(json!({
                "day": r.get::<_, String>(0)?,
                "subject_type": r.get::<_, String>(1)?,
                "subject_id": r.get::<_, String>(2)?,
                "level": r.get::<_, String>(3)?,
                "seconds": r.get::<_, i64>(4)?,
                "as_primary_seconds": r.get::<_, i64>(5)?,
            }))
        })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

/// `GET /front/reviews?open=1`: switches recorded close together from different devices that
/// someone should look at (SYNC.md reviews).
pub fn reviews(conn: &Connection, p: &Principal, open_only: bool) -> Result<Value, DataError> {
    need(p, "read:front")?;
    let mut st = conn.prepare_cached(
        "SELECT id, switch_a, switch_b, created_at, resolution, resolved_at FROM front_review
         WHERE account_id = ?1 AND (?2 = 0 OR resolved_at IS NULL) ORDER BY created_at DESC",
    )?;
    let rows = st.query_map(params![p.account_id, open_only], |r| {
        Ok(json!({
            "id": r.get::<_, String>(0)?,
            "switch_a": r.get::<_, String>(1)?,
            "switch_b": r.get::<_, String>(2)?,
            "created_at": r.get::<_, i64>(3)?,
            "resolution": r.get::<_, Option<String>>(4)?,
            "resolved_at": r.get::<_, Option<i64>>(5)?,
        }))
    })?;
    Ok(json!({"items": rows.collect::<Result<Vec<_>, _>>()?}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn days() {
        assert!(day_ok("2026-09-23"));
        assert!(!day_ok("2026-9-23"));
        assert!(!day_ok("2026-09-23'--"));
    }
}
