//! Fronting status shared with a space (D-133, NOTIFICATIONS §5 rule 11).
//!
//! An account that writes `space.set_sharing {fronts: true}` in a shared space lets everyone in
//! it see its current front, followers or not. It is a sharing path of its own, built like the
//! follower view and never from the live front:
//!
//! - **What:** the Body front as a follower with only the account's *default* ceiling would see
//!   it (no bucket, no per-follow override, no additional fronts): the ceiling's levels and
//!   layers, members' `announce` policies (a member announced to nobody or only to buckets never
//!   appears), `hidden_subjects`. Names, colours and glyphs are snapshotted when a change is
//!   queued; no avatars, no states, no times, no history, no stats, no pings.
//! - **When:** a change is queued as `pending` and becomes the shown `entries` only at `due_at`
//!   ([`process_due`]): settle (15 s) + the space's own delay (`fronts_delay_min`, default 15;
//!   drawn between it and 1.5 times it) + a member's extra delay, counted from when the server
//!   accepted the change, never from an earlier (offline) switch time. A newer change replaces
//!   what is pending and is due from its own acceptance, so nothing shows before its delay.
//! - **Who:** `GET /spaces/{id}/fronts` shows a sharing account to present members of the space
//!   only, while the sharer is still in it and still sharing. Switching it off, leaving or being
//!   removed deletes the view at once; switching it on again starts from a fresh reveal.

use std::collections::BTreeSet;

use chorus_core::notify::{self, Audience, DelayRange, Draws, ScheduleIn, Seen};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::notifier::Shown;

/// Default delay of a space's fronting status, minutes (`space_sharing.fronts_delay_min` NULL).
pub const DEFAULT_DELAY_MIN: i64 = 15;

/// The shared spaces where `account` is present and shares its fronting status, with the delay.
fn sharing_spaces(conn: &Connection, account: &str) -> anyhow::Result<Vec<(String, i64)>> {
    let mut st = conn.prepare_cached(
        "SELECT ss.space_id, coalesce(ss.fronts_delay_min, ?2) FROM space_sharing ss
         JOIN space s ON s.id = ss.space_id AND s.kind = 'shared' AND s.deleted_at IS NULL
         JOIN space_member sm ON sm.space_id = ss.space_id AND sm.account_id = ss.account_id AND sm.is_present
         WHERE ss.account_id = ?1 AND ss.fronts = 1",
    )?;
    let rows = st.query_map(params![account, DEFAULT_DELAY_MIN], |r| Ok((r.get(0)?, r.get(1)?)))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// The view a space may have of `account`'s Body front now (see the module docs).
fn view_now(conn: &Connection, account: &str) -> anyhow::Result<(Vec<Seen>, notify::Ceiling)> {
    let mut ceiling = notify::effective_ceiling(&crate::notifier::default_ceiling(conn, account)?, &[], &json!({}));
    ceiling.fronts.clear();
    let current = crate::fronts::current(conn, account)?;
    let presence = crate::notifier::front_presence(conn, account, current.as_ref(), "")?;
    let policies = crate::notifier::policies(conn, account)?;
    let none = BTreeSet::new();
    let view =
        notify::view_layers(&presence, &Audience { ceiling: &ceiling, follower_buckets: &none, policies: &policies });
    Ok((view, ceiling))
}

fn shown(conn: &Connection, account: &str, view: &[Seen]) -> anyhow::Result<Vec<Shown>> {
    let refs: Vec<&Seen> = view.iter().collect();
    let names = crate::notifier::names_for(conn, account, &refs)?;
    Ok(view
        .iter()
        .map(|s| {
            let (name, color, glyph) = match s {
                Seen::Subject { subject_id, .. } => {
                    names.get(subject_id).cloned().unwrap_or(("Someone".into(), None, None))
                }
                Seen::Someone { .. } => ("Someone".into(), None, None),
            };
            Shown { seen: s.clone(), name, color, glyph, avatar_blob: None, state: None }
        })
        .collect())
}

fn parse(s: Option<String>) -> Option<Vec<Shown>> {
    s.and_then(|s| serde_json::from_str(&s).ok())
}

/// Something about `account`'s front, its sharing policy or its spaces changed (inside the ingest
/// transaction, live ingest only): views of spaces it no longer shares with are withdrawn at once,
/// and each space it shares with gets a pending reveal if what it may see changed. `extra` is a
/// switch sent with `notify: extra_delay`.
pub fn on_change(conn: &Connection, account: &str, extra: bool, now: i64) -> anyhow::Result<()> {
    let spaces = sharing_spaces(conn, account)?;
    let keep = serde_json::to_string(&spaces.iter().map(|(s, _)| s).collect::<Vec<_>>())?;
    conn.execute(
        "DELETE FROM space_front_view WHERE account_id = ?1 AND space_id NOT IN (SELECT value FROM json_each(?2))",
        params![account, keep],
    )?;
    if spaces.is_empty() {
        return Ok(());
    }
    let (view, ceiling) = view_now(conn, account)?;
    let policies = crate::notifier::policies(conn, account)?;
    for (space, delay_min) in spaces {
        let row: Option<(Option<String>, Option<String>)> = conn
            .prepare_cached("SELECT entries, pending FROM space_front_view WHERE space_id = ?1 AND account_id = ?2")?
            .query_row(params![space, account], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?;
        let (entries, pending) = row.map(|(e, p)| (parse(e), parse(p))).unwrap_or((None, None));
        let seen = |v: &Option<Vec<Shown>>| v.as_ref().map(|v| v.iter().map(|s| s.seen.clone()).collect::<Vec<_>>());
        let (shown_seen, pending_seen) = (seen(&entries), seen(&pending));
        if pending_seen.as_ref().or(shown_seen.as_ref()) == Some(&view) {
            continue; // nothing new to tell
        }
        if pending.is_some() && shown_seen.as_ref() == Some(&view) {
            // back to what the space already knows: drop what was pending
            conn.execute(
                "UPDATE space_front_view SET pending = NULL, due_at = NULL WHERE space_id = ?1 AND account_id = ?2",
                params![space, account],
            )?;
            continue;
        }
        let before: BTreeSet<&Seen> = shown_seen.iter().flatten().collect();
        let member_extra: Vec<DelayRange> = view
            .iter()
            .filter(|s| !before.contains(s))
            .filter_map(|s| match s {
                Seen::Subject { subject_type: chorus_core::front::SubjectType::Member, subject_id, .. } => {
                    policies.get(subject_id).and_then(|p| p.extra_delay_range)
                }
                _ => None,
            })
            .collect();
        let mut c = ceiling.clone();
        c.delay = DelayRange { min_s: delay_min * 60, max_s: delay_min * 90 };
        c.allow_now = false;
        let d = Draws { delay: rand::random(), extra: rand::random(), late: rand::random(), jitter: rand::random() };
        let how = if extra { chorus_core::front::Notify::ExtraDelay } else { chorus_core::front::Notify::Default };
        let due = notify::due_at(
            &c,
            &ScheduleIn {
                occurred_at: now,
                accepted_at: now,
                notify: how,
                settle_ms: notify::DEFAULT_SETTLE_MS,
                member_extra: &member_extra,
                draws: d,
            },
        );
        let pending = serde_json::to_string(&shown(conn, account, &view)?)?;
        conn.execute(
            "INSERT INTO space_front_view(space_id, account_id, pending, due_at) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(space_id, account_id) DO UPDATE SET pending = excluded.pending, due_at = excluded.due_at",
            params![space, account, pending, due],
        )?;
    }
    Ok(())
}

/// Does `o` change who or what a space may see of its author's front (besides front ops)?
/// The sharing setting itself, membership, and the follower policy the view is built from.
pub fn affected(o: &chorus_core::op::Op) -> Option<String> {
    match o.kind.as_str() {
        "space.set_sharing" => o.account_id.clone(),
        "space.join" | "space.leave" => o.payload.get("account_id").and_then(Value::as_str).map(str::to_string),
        _ if crate::notifier::changes_policy(o) => o.scope.strip_prefix("account:").map(str::to_string),
        _ => None,
    }
}

/// Reveal what is due (from the notifier loop): the shown entries change here and nowhere else.
pub fn process_due(conn: &Connection, now: i64) -> anyhow::Result<usize> {
    Ok(conn.execute(
        "UPDATE space_front_view SET entries = pending, revealed_at = due_at, pending = NULL, due_at = NULL
         WHERE due_at IS NOT NULL AND due_at <= ?1",
        [now],
    )?)
}

/// `GET /spaces/{id}/fronts`: for a present member of a shared space, each *other* account in it
/// that shares its fronting status there, as last revealed. Anyone else: not found.
pub fn list(conn: &Connection, viewer: &str, space: &str) -> Result<Value, crate::spaces::SpaceError> {
    let member: bool = conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM space s JOIN space_member sm ON sm.space_id = s.id
           WHERE s.id = ?1 AND s.kind = 'shared' AND s.deleted_at IS NULL AND sm.account_id = ?2 AND sm.is_present)",
        params![space, viewer],
        |r| r.get(0),
    )?;
    if !member {
        return Err(crate::spaces::SpaceError::NotFound);
    }
    let mut st = conn.prepare_cached(
        "SELECT v.account_id, v.entries FROM space_front_view v
         JOIN space_sharing ss ON ss.space_id = v.space_id AND ss.account_id = v.account_id AND ss.fronts = 1
         JOIN space_member sm ON sm.space_id = v.space_id AND sm.account_id = v.account_id AND sm.is_present
         WHERE v.space_id = ?1 AND v.account_id <> ?2 AND v.entries IS NOT NULL
         ORDER BY v.account_id",
    )?;
    let rows: Vec<(String, String)> =
        st.query_map(params![space, viewer], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<Result<_, _>>()?;
    let items: Vec<Value> = rows
        .into_iter()
        .map(|(account, entries)| {
            let entries: Vec<Value> = serde_json::from_str::<Vec<Shown>>(&entries)
                .unwrap_or_default()
                .into_iter()
                .map(|s| {
                    let mut e = serde_json::to_value(&s.seen).unwrap_or(Value::Null);
                    e["name"] = json!(s.name);
                    e["color"] = json!(s.color);
                    e["glyph"] = json!(s.glyph);
                    e
                })
                .collect();
            json!({"account_id": account, "entries": entries})
        })
        .collect();
    Ok(json!({"items": items}))
}
