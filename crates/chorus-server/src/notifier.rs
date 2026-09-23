//! Switch notifications and follower views on the server (NOTIFICATIONS.md §2, §5, §6).
//!
//! The rules are `chorus_core::notify`; this module feeds them from SQL and keeps the queue:
//!
//! - [`on_front_change`] runs inside the ingest transaction after any `front.*` op (never during a
//!   rebuild). For each active follower it computes the privacy-filtered view; if that differs
//!   from what is already revealed or queued, it queues one `notification` row with a randomized
//!   `due_at` (settle + delay; late arrivals spread over 2 min) and collapses older pending rows.
//!   A retraction that brings the view back to what was revealed cancels what's pending.
//! - [`process_due`] runs every few seconds. At `due_at` it **reveals** the view (the follower
//!   view changes only here, §5) and then delivers, holds (quiet hours), queues for a digest, or
//!   just reveals (silent/muted/filtered).
//!
//! Random draws come from the OS CSPRNG once and are stored in the row, so restarts don't redraw.

use std::collections::{BTreeMap, BTreeSet};

use chorus_core::front::{Entry, Level, Notify, SubjectType};
use chorus_core::notify::{
    self, Audience, Ceiling, Diff, Draws, MemberPolicy, Pending, Precision, Prefs, Route, RouteIn, ScheduleIn, Seen,
};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// A subject as the follower sees it: names are snapshotted when the change is queued.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Shown {
    #[serde(flatten)]
    pub seen: Seen,
    pub name: String,
    pub color: Option<String>,
    pub glyph: Option<String>,
    #[serde(default)]
    pub avatar_blob: Option<String>,
}

/// subject id → (name, colour, glyph), snapshotted when a change is queued.
type Names = BTreeMap<String, (String, Option<String>, Option<String>)>;

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Queued {
    target_account_id: String,
    follow_id: String,
    view: Vec<Seen>,
    names: Names,
    diff_arrived: Vec<Seen>,
    diff_left: Vec<Seen>,
    occurred_at: i64,
    tz_offset_min: i32,
    notify: Notify,
    draws: Draws,
    /// pending → (held | digest) → delivered, or revealed (no ping)
    state: String,
    text: Option<String>,
    displayed: Option<notify::Displayed>,
}

fn json_col<T: for<'de> Deserialize<'de> + Default>(s: Option<String>) -> T {
    s.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

fn current_front(conn: &Connection, account: &str) -> anyhow::Result<Vec<Entry>> {
    let mut st = conn.prepare_cached(
        "SELECT subject_type, subject_id, level, is_primary FROM front_interval
         WHERE account_id = ?1 AND end_at IS NULL ORDER BY position",
    )?;
    let rows = st.query_map([account], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, bool>(3)?))
    })?;
    let mut out = Vec::new();
    for r in rows {
        let (t, id, level, primary) = r?;
        let subject_type = serde_json::from_value(json!(t))?;
        let level = serde_json::from_value(json!(level))?;
        out.push(Entry { subject_type, subject_id: id, level, is_primary: primary });
    }
    Ok(out)
}

struct Latest {
    id: String,
    occurred_at: i64,
    tz_offset_min: i32,
    notify: Notify,
}

fn latest_switch(conn: &Connection, account: &str) -> anyhow::Result<Option<Latest>> {
    Ok(conn
        .query_row(
            "SELECT id, occurred_at, tz_offset_min, notify FROM switch
             WHERE account_id = ?1 AND retracted = 0 ORDER BY occurred_at DESC, id DESC LIMIT 1",
            [account],
            |r| {
                Ok(Latest {
                    id: r.get(0)?,
                    occurred_at: r.get(1)?,
                    tz_offset_min: r.get(2)?,
                    notify: serde_json::from_value(json!(r.get::<_, String>(3)?)).unwrap_or_default(),
                })
            },
        )
        .optional()?)
}

struct Follow {
    id: String,
    follower: String,
    ceiling: Value,
    prefs: Prefs,
}

fn active_follows(conn: &Connection, account: &str) -> anyhow::Result<Vec<Follow>> {
    let mut st = conn.prepare_cached(
        "SELECT id, follower_account_id, ceiling, prefs FROM follow WHERE target_account_id = ?1 AND status = 'active'",
    )?;
    let rows = st.query_map([account], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?))
    })?;
    let mut out = Vec::new();
    for r in rows {
        let (id, follower, ceiling, prefs) = r?;
        out.push(Follow {
            id,
            follower,
            ceiling: serde_json::from_str(&ceiling).unwrap_or(json!({})),
            prefs: serde_json::from_str(&prefs).unwrap_or_default(),
        });
    }
    Ok(out)
}

/// The follower's effective ceiling and the buckets they're in.
fn ceiling_for(conn: &Connection, account: &str, follow: &Follow) -> anyhow::Result<(Ceiling, BTreeSet<String>)> {
    let saved_pref: Option<String> = conn
        .query_row(
            "SELECT value FROM pref WHERE account_id = ?1 AND device_id = '' AND key = 'follow_ceiling'",
            [account],
            |r| r.get(0),
        )
        .optional()?;
    let legacy_setting: Option<String> = conn
        .query_row("SELECT json_extract(settings, '$.follow_ceiling') FROM account WHERE id = ?1", [account], |r| {
            r.get::<_, Option<String>>(0)
        })
        .optional()?
        .flatten();
    let default: Value = saved_pref.or(legacy_setting).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(json!({}));
    let mut st = conn.prepare_cached(
        "SELECT b.id, b.ceiling FROM bucket b JOIN bucket_assignment a ON a.bucket_id = b.id
         WHERE b.account_id = ?1 AND a.follower_account_id = ?2 AND a.is_present = 1 AND b.deleted_at IS NULL",
    )?;
    let rows: Vec<(String, String)> =
        st.query_map(params![account, follow.follower], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<Result<_, _>>()?;
    let ceilings: Vec<Value> = rows.iter().map(|(_, c)| serde_json::from_str(c).unwrap_or(json!({}))).collect();
    let refs: Vec<&Value> = ceilings.iter().collect();
    let buckets = rows.into_iter().map(|(id, _)| id).collect();
    Ok((notify::effective_ceiling(&default, &refs, &follow.ceiling), buckets))
}

fn policies(conn: &Connection, account: &str) -> anyhow::Result<BTreeMap<String, MemberPolicy>> {
    let mut st = conn.prepare_cached("SELECT id, notify_policy FROM member WHERE account_id = ?1")?;
    let rows = st.query_map([account], |r| Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?)))?;
    let mut out = BTreeMap::new();
    for r in rows {
        let (id, p) = r?;
        out.insert(id, json_col::<MemberPolicy>(p));
    }
    Ok(out)
}

fn groups(conn: &Connection, account: &str) -> anyhow::Result<BTreeMap<String, BTreeSet<String>>> {
    let mut st = conn.prepare_cached(
        "SELECT gm.group_id, gm.member_id FROM group_membership gm JOIN member_group g ON g.id = gm.group_id
         WHERE g.account_id = ?1 AND gm.is_present = 1",
    )?;
    let rows = st.query_map([account], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
    let mut out: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for r in rows {
        let (g, m) = r?;
        out.entry(g).or_default().insert(m);
    }
    Ok(out)
}

fn names_for(conn: &Connection, seen: &[&Seen]) -> anyhow::Result<Names> {
    let mut out = BTreeMap::new();
    for s in seen {
        let Seen::Subject { subject_type, subject_id, .. } = s else { continue };
        type NameRow = (Option<String>, Option<String>, Option<String>, Option<String>);
        let row: Option<NameRow> = match subject_type {
            SubjectType::Member => conn
                .query_row(
                    "SELECT name, display_name, color, json_extract(sigils, '$[0]') FROM member WHERE id = ?1",
                    [subject_id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                )
                .optional()?,
            SubjectType::Group => conn
                .query_row("SELECT name, NULL, color, NULL FROM member_group WHERE id = ?1", [subject_id], |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
                })
                .optional()?,
            SubjectType::State => conn
                .query_row("SELECT name, NULL, color, NULL FROM custom_state WHERE id = ?1", [subject_id], |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
                })
                .optional()?,
        };
        let (name, display, color, glyph) = row.unwrap_or((None, None, None, None));
        out.insert(subject_id.clone(), (display.or(name).unwrap_or_else(|| "Someone".into()), color, glyph));
    }
    Ok(out)
}

/// The revealed view for (follower, target): `(view, displayed_since)`.
fn revealed(conn: &Connection, follower: &str, target: &str) -> anyhow::Result<(Vec<Seen>, Option<i64>)> {
    let row: Option<(String, Option<i64>)> = conn
        .query_row(
            "SELECT entries, displayed_since FROM follower_front_view WHERE follower_account_id = ?1 AND target_account_id = ?2",
            params![follower, target],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    Ok(match row {
        Some((entries, since)) => {
            let shown: Vec<Shown> = serde_json::from_str(&entries).unwrap_or_default();
            (shown.into_iter().map(|s| s.seen).collect(), since)
        }
        None => (Vec::new(), None),
    })
}

/// Pending (not yet revealed) rows for (follower, target), oldest first.
fn pending(conn: &Connection, follower: &str, target: &str) -> anyhow::Result<Vec<(String, i64, Queued)>> {
    let mut st = conn.prepare_cached(
        "SELECT id, due_at, payload FROM notification
         WHERE recipient_account_id = ?1 AND kind = 'switch' AND delivered_at IS NULL AND cancelled_at IS NULL
           AND json_extract(payload, '$.target_account_id') = ?2 AND json_extract(payload, '$.state') = 'pending'
         ORDER BY due_at, id",
    )?;
    let rows = st.query_map(params![follower, target], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, String>(2)?))
    })?;
    let mut out = Vec::new();
    for r in rows {
        let (id, due, payload) = r?;
        if let Ok(q) = serde_json::from_str::<Queued>(&payload) {
            out.push((id, due, q));
        }
    }
    Ok(out)
}

fn draws() -> Draws {
    Draws { delay: rand::random(), extra: rand::random(), late: rand::random(), jitter: rand::random() }
}

/// Queue follower notifications after a front change of `account` (inside the ingest tx).
pub fn on_front_change(conn: &Connection, account: &str, now: i64) -> anyhow::Result<()> {
    let follows = active_follows(conn, account)?;
    if follows.is_empty() {
        return Ok(());
    }
    let front = current_front(conn, account)?;
    let latest = latest_switch(conn, account)?;
    let policies = policies(conn, account)?;
    let groups = groups(conn, account)?;
    for f in follows {
        let (ceiling, buckets) = ceiling_for(conn, account, &f)?;
        let audience = Audience { ceiling: &ceiling, follower_buckets: &buckets, policies: &policies };
        let after = notify::view(&front, &audience);
        let (shown, _) = revealed(conn, &f.follower, account)?;
        let queued = pending(conn, &f.follower, account)?;
        let base = queued.last().map(|(_, _, q)| q.view.clone()).unwrap_or_else(|| shown.clone());
        if after == base {
            continue;
        }
        if after == shown {
            // e.g. an undo: back to what the follower already knows — nothing left to tell
            for (id, _, _) in &queued {
                conn.execute("UPDATE notification SET cancelled_at = ?2 WHERE id = ?1", params![id, now])?;
            }
            continue;
        }
        // collapse: the survivor replaces everything pending, so it is described relative to what
        // the follower actually knows; sequence: relative to the previous queued item
        let since = if ceiling.stacking == notify::Stacking::Collapse { &shown } else { &base };
        let Diff { arrived, left } = notify::diff(since, &after, &audience, &f.prefs, &groups);
        let d = draws();
        let (occurred_at, tz, how) =
            latest.as_ref().map_or((now, 0, Notify::Default), |l| (l.occurred_at, l.tz_offset_min, l.notify));
        let member_extra: Vec<_> = arrived
            .iter()
            .filter_map(|s| match s {
                Seen::Subject { subject_type: SubjectType::Member, subject_id, .. } => {
                    policies.get(subject_id).and_then(|p| p.extra_delay_range)
                }
                _ => None,
            })
            .collect();
        let due = notify::due_at(
            &ceiling,
            &ScheduleIn {
                occurred_at,
                accepted_at: now,
                notify: how,
                settle_ms: notify::DEFAULT_SETTLE_MS,
                member_extra: &member_extra,
                draws: d,
            },
        );
        let pend: Vec<Pending> = queued.iter().map(|(id, due, _)| Pending { id: id.clone(), due_at: *due }).collect();
        let (cancel, due) = notify::supersede(ceiling.stacking, &pend, due);
        let id = chorus_core::id::new_id(now as u64, rand::random());
        for c in cancel {
            conn.execute(
                "UPDATE notification SET cancelled_at = ?2, collapsed_into = ?3 WHERE id = ?1",
                params![c, now, id],
            )?;
        }
        let all: Vec<&Seen> = after.iter().chain(left.iter()).collect();
        let q = Queued {
            target_account_id: account.into(),
            follow_id: f.id.clone(),
            names: names_for(conn, &all)?,
            view: after,
            diff_arrived: arrived,
            diff_left: left,
            occurred_at,
            tz_offset_min: tz,
            notify: how,
            draws: d,
            state: "pending".into(),
            text: None,
            displayed: None,
        };
        conn.execute(
            "INSERT INTO notification (id, recipient_account_id, kind, source_op_id, payload, created_at, due_at)
             VALUES (?1, ?2, 'switch', ?3, ?4, ?5, ?6)",
            params![id, f.follower, latest.as_ref().map(|l| l.id.clone()), serde_json::to_string(&q)?, now, due],
        )?;
    }
    Ok(())
}

fn level_phrase(level: Level, n: usize) -> &'static str {
    match (level, n > 1) {
        (Level::Front, false) => "is fronting",
        (Level::Front, true) => "are fronting",
        (Level::Cocon, false) => "is co-conscious",
        (Level::Cocon, true) => "are co-conscious",
        (Level::Present, false) => "is around",
        (Level::Present, true) => "are around",
    }
}

fn join_names(names: &[String]) -> String {
    match names.len() {
        0 => String::new(),
        1 => names[0].clone(),
        n => format!("{} & {}", names[..n - 1].join(", "), names[n - 1]),
    }
}

/// "Kai & June are fronting · Rin left"
fn describe(q: &Queued) -> String {
    let name = |s: &Seen| match s {
        Seen::Subject { subject_id, .. } => {
            q.names.get(subject_id).map(|n| n.0.clone()).unwrap_or_else(|| "Someone".into())
        }
        Seen::Someone { .. } => "Someone".into(),
    };
    let mut parts = Vec::new();
    for level in [Level::Front, Level::Cocon, Level::Present] {
        let who: Vec<String> = q.diff_arrived.iter().filter(|s| s.level() == level).map(name).collect();
        if !who.is_empty() {
            parts.push(format!("{} {}", join_names(&who), level_phrase(level, who.len())));
        }
    }
    let gone: Vec<String> = q.diff_left.iter().map(name).collect();
    if !gone.is_empty() {
        parts.push(format!("{} left", join_names(&gone)));
    }
    if parts.is_empty() {
        let front: Vec<String> = q.view.iter().filter(|s| s.level() == Level::Front).map(name).collect();
        return if front.is_empty() {
            "No one is fronting".into()
        } else {
            format!("{} {}", join_names(&front), level_phrase(Level::Front, front.len()))
        };
    }
    parts.join(" · ")
}

fn sent_today(conn: &Connection, follower: &str, target: &str, now: i64) -> anyhow::Result<u32> {
    Ok(conn.query_row(
        "SELECT COUNT(*) FROM notification WHERE recipient_account_id = ?1 AND kind = 'switch'
         AND json_extract(payload, '$.target_account_id') = ?2 AND delivered_at > ?3",
        params![follower, target, now - 86_400_000],
        |r| r.get(0),
    )?)
}

fn follow_prefs_and_ceiling(conn: &Connection, q: &Queued) -> anyhow::Result<Option<(Ceiling, Prefs)>> {
    let row: Option<(String, String, String, Option<String>)> = conn
        .query_row(
            "SELECT id, follower_account_id, ceiling, prefs FROM follow WHERE id = ?1 AND status = 'active'",
            [&q.follow_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .optional()?;
    let Some((id, follower, ceiling, prefs)) = row else { return Ok(None) };
    let f =
        Follow { id, follower, ceiling: serde_json::from_str(&ceiling).unwrap_or(json!({})), prefs: json_col(prefs) };
    let (c, _) = ceiling_for(conn, &q.target_account_id, &f)?;
    Ok(Some((c, f.prefs)))
}

/// What one pass produced: rows handled, and encrypted pushes to send once the lock is released.
#[derive(Default)]
pub struct Processed {
    pub handled: usize,
    pub pushes: Vec<crate::push::Outbound>,
}

/// Reveal and deliver everything due at `now`.
pub fn process_due(conn: &Connection, now: i64) -> anyhow::Result<Processed> {
    let mut pushes = Vec::new();
    let mut st = conn.prepare_cached(
        "SELECT id, recipient_account_id, payload FROM notification
         WHERE kind = 'switch' AND delivered_at IS NULL AND cancelled_at IS NULL AND due_at <= ?1 ORDER BY due_at, id LIMIT 500",
    )?;
    let rows: Vec<(String, String, String)> =
        st.query_map([now], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect::<Result<_, _>>()?;
    let n = rows.len();
    let mut digests: BTreeMap<(String, String), Vec<(String, Queued)>> = BTreeMap::new();
    for (id, follower, payload) in rows {
        let Ok(mut q) = serde_json::from_str::<Queued>(&payload) else {
            conn.execute("UPDATE notification SET cancelled_at = ?2 WHERE id = ?1", params![id, now])?;
            continue;
        };
        // the follow ended (or was never accepted) since this was queued: drop it silently
        let Some((ceiling, prefs)) = follow_prefs_and_ceiling(conn, &q)? else {
            conn.execute("UPDATE notification SET cancelled_at = ?2 WHERE id = ?1", params![id, now])?;
            continue;
        };
        if q.state == "pending" {
            // §5: the follower view changes here and only here
            let (_, prev_since) = revealed(conn, &follower, &q.target_account_id)?;
            let shown_since =
                notify::displayed(q.occurred_at, now, ceiling.time, q.tz_offset_min, prev_since, q.draws.jitter);
            let shown: Vec<Shown> = q
                .view
                .iter()
                .map(|s| -> anyhow::Result<Shown> {
                    let (name, color, glyph) = match s {
                        Seen::Subject { subject_id, .. } => {
                            q.names.get(subject_id).cloned().unwrap_or(("Someone".into(), None, None))
                        }
                        Seen::Someone { .. } => ("Someone".into(), None, None),
                    };
                    let avatar_blob = match s {
                        Seen::Subject { subject_type: SubjectType::Member, subject_id, .. } => conn
                            .query_row("SELECT avatar_blob FROM member WHERE id = ?1", [subject_id], |r| {
                                r.get::<_, Option<String>>(0)
                            })
                            .optional()?
                            .flatten(),
                        _ => None,
                    };
                    Ok(Shown { seen: s.clone(), name, color, glyph, avatar_blob })
                })
                .collect::<anyhow::Result<_>>()?;
            conn.execute(
                "INSERT INTO follower_front_view (follower_account_id, target_account_id, entries, displayed_since, revealed_at)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT (follower_account_id, target_account_id)
                 DO UPDATE SET entries = excluded.entries, displayed_since = excluded.displayed_since, revealed_at = excluded.revealed_at",
                params![follower, q.target_account_id, serde_json::to_string(&shown)?, shown_since.at, now],
            )?;
            // …and the history/stats log grows here and only here (§5 rule 4); avatars stay out,
            // since blob access follows the *current* view
            let logged: Vec<Shown> = shown.iter().cloned().map(|s| Shown { avatar_blob: None, ..s }).collect();
            conn.execute(
                "INSERT INTO follower_front_log (follower_account_id, target_account_id, revealed_at, displayed, entries)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                params![
                    follower,
                    q.target_account_id,
                    now,
                    serde_json::to_string(&shown_since)?,
                    serde_json::to_string(&logged)?
                ],
            )?;
            q.displayed = Some(shown_since);
            q.text = Some(describe(&q));
            let diff_empty = q.diff_arrived.is_empty() && q.diff_left.is_empty();
            let route = notify::route(&RouteIn {
                due_at: now,
                notify: q.notify,
                diff_empty,
                sent_today: sent_today(conn, &follower, &q.target_account_id, now)?,
                tz_offset_min: q.tz_offset_min,
                // one stable spread per (follower, account), so a day's items share one digest
                digest_draw: digest_draw(&follower, &q.target_account_id),
                ceiling: &ceiling,
                prefs: &prefs,
            });
            match route {
                Route::Deliver { at } if at <= now => pushes.extend(deliver(conn, &id, &follower, &mut q, now)?),
                Route::Deliver { at } => hold(conn, &id, &mut q, "held", at)?,
                Route::Digest { at } => hold(conn, &id, &mut q, "digest", at)?,
                Route::RevealOnly => {
                    q.state = "revealed".into();
                    conn.execute(
                        "UPDATE notification SET cancelled_at = ?2, payload = ?3 WHERE id = ?1",
                        params![id, now, serde_json::to_string(&q)?],
                    )?;
                }
            }
        } else {
            if q.state == "digest" {
                digests.entry((follower.clone(), q.target_account_id.clone())).or_default().push((id, q));
            } else {
                // held by quiet hours: its time has come
                pushes.extend(deliver(conn, &id, &follower, &mut q, now)?);
            }
        }
    }
    // §2.11: everything due for one (follower, account) digest goes out as one summary
    for ((follower, _), mut items) in digests {
        let Some((first_id, _)) = items.first().cloned() else { continue };
        let text = digest_text(&items);
        let (_, head) = items.last_mut().expect("non-empty");
        let mut summary = head.clone();
        summary.text = Some(text);
        for (id, _) in &items {
            if *id != first_id {
                conn.execute(
                    "UPDATE notification SET cancelled_at = ?2, collapsed_into = ?3 WHERE id = ?1",
                    params![id, now, first_id],
                )?;
            }
        }
        pushes.extend(deliver(conn, &first_id, &follower, &mut summary, now)?);
    }
    pushes.extend(crate::activity::process_due(conn, now)?);
    Ok(Processed { handled: n, pushes })
}

fn digest_draw(follower: &str, target: &str) -> u64 {
    use sha2::Digest as _;
    let h = sha2::Sha256::digest(format!("digest|{follower}|{target}").as_bytes());
    u64::from_be_bytes(h[..8].try_into().unwrap_or_default())
}

/// "Kai (morning) · June & Rin (evening)": who arrived, with the fuzzed part of day.
fn digest_text(items: &[(String, Queued)]) -> String {
    let parts: Vec<String> = items
        .iter()
        .filter_map(|(_, q)| {
            let who: Vec<String> = q
                .diff_arrived
                .iter()
                .filter(|s| s.level() == Level::Front)
                .map(|s| match s {
                    Seen::Subject { subject_id, .. } => {
                        q.names.get(subject_id).map(|n| n.0.clone()).unwrap_or_else(|| "Someone".into())
                    }
                    Seen::Someone { .. } => "Someone".into(),
                })
                .collect();
            if who.is_empty() {
                return None;
            }
            let when = q.displayed.and_then(|d| d.part).map(|p| match p {
                notify::PartOfDay::Night => "night",
                notify::PartOfDay::Morning => "morning",
                notify::PartOfDay::Afternoon => "afternoon",
                notify::PartOfDay::Evening => "evening",
            });
            Some(match when {
                Some(w) => format!("{} ({w})", join_names(&who)),
                None => join_names(&who),
            })
        })
        .collect();
    if parts.is_empty() { "Switches today".into() } else { parts.join(" · ") }
}

fn hold(conn: &Connection, id: &str, q: &mut Queued, state: &str, at: i64) -> anyhow::Result<()> {
    q.state = state.into();
    conn.execute(
        "UPDATE notification SET due_at = ?2, payload = ?3 WHERE id = ?1",
        params![id, at, serde_json::to_string(q)?],
    )?;
    Ok(())
}

fn deliver(
    conn: &Connection,
    id: &str,
    follower: &str,
    q: &mut Queued,
    now: i64,
) -> anyhow::Result<Vec<crate::push::Outbound>> {
    q.state = "delivered".into();
    let at = q.displayed.and_then(|d| d.at);
    conn.execute(
        "UPDATE notification SET delivered_at = ?2, displayed_time = ?3, payload = ?4 WHERE id = ?1",
        params![id, now, at, serde_json::to_string(q)?],
    )?;
    // the push carries exactly what the inbox shows: already filtered and fuzzed
    let title: Option<String> = conn
        .query_row("SELECT COALESCE(display_name, handle) FROM account WHERE id = ?1", [&q.target_account_id], |r| {
            r.get(0)
        })
        .optional()?
        .flatten();
    let payload = json!({
        "t": "switch",
        "id": id,
        "account_id": q.target_account_id,
        "title": title.unwrap_or_else(|| "Chorus".into()),
        "text": q.text,
        "time": precision_of(&q.displayed),
    });
    crate::push::prepare(conn, follower, &payload)
}

// ─── reads for the follower (API.md §3) ─────────────────────────────────────

fn precision_of(d: &Option<notify::Displayed>) -> Value {
    d.map(|d| json!({"at": d.at, "precision": d.precision, "part": d.part}))
        .unwrap_or(json!({"at": null, "precision": Precision::None}))
}

/// Delivered notifications for `follower`, newest first: switches of accounts they follow, and
/// activity (mentions, DMs, replies) from shared spaces.
pub fn list(conn: &Connection, follower: &str, limit: i64) -> anyhow::Result<Value> {
    let mut items = switch_items(conn, follower, limit)?;
    let mut st = conn.prepare_cached(
        "SELECT id, kind, payload, delivered_at FROM notification
         WHERE recipient_account_id = ?1 AND kind IN ('mention', 'dm', 'reply', 'message', 'member_dm', 'own_switch') AND delivered_at IS NOT NULL
         ORDER BY delivered_at DESC, id DESC LIMIT ?2",
    )?;
    let rows = st.query_map(params![follower, limit], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, i64>(3)?))
    })?;
    for r in rows {
        let (id, kind, payload, delivered) = r?;
        let p: Value = serde_json::from_str(&payload).unwrap_or(json!({}));
        items.push(json!({
            "id": id, "kind": kind, "title": p["title"], "text": p["text"],
            "channel_id": p["channel_id"], "message_id": p["message_id"],
            "time": {"at": delivered, "precision": "exact"}, "delivered_at": delivered,
        }));
    }
    items.sort_by_key(|i| std::cmp::Reverse(i["delivered_at"].as_i64().unwrap_or(0)));
    items.truncate(limit as usize);
    Ok(json!({"items": items}))
}

fn switch_items(conn: &Connection, follower: &str, limit: i64) -> anyhow::Result<Vec<Value>> {
    let mut st = conn.prepare_cached(
        "SELECT n.id, n.payload, n.delivered_at, a.id, a.handle, a.display_name FROM notification n
         JOIN account a ON a.id = json_extract(n.payload, '$.target_account_id')
         WHERE n.recipient_account_id = ?1 AND n.kind = 'switch' AND n.delivered_at IS NOT NULL
         ORDER BY n.delivered_at DESC, n.id DESC LIMIT ?2",
    )?;
    let rows = st.query_map(params![follower, limit], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, i64>(2)?,
            r.get::<_, String>(3)?,
            r.get::<_, Option<String>>(4)?,
            r.get::<_, Option<String>>(5)?,
        ))
    })?;
    let mut items = Vec::new();
    for r in rows {
        let (id, payload, delivered, aid, handle, display) = r?;
        let Ok(q) = serde_json::from_str::<Queued>(&payload) else { continue };
        items.push(json!({
            "id": id,
            "kind": "switch",
            "text": q.text,
            "time": precision_of(&q.displayed),
            "delivered_at": delivered,
            "account": {"id": aid, "handle": handle, "display_name": display},
        }));
    }
    Ok(items)
}

/// What `follower` may see of `target` right now (only what has been revealed).
pub fn follower_view(conn: &Connection, follower: &str, target: &str) -> anyhow::Result<Option<Value>> {
    follower_view_at(conn, follower, target, crate::now_ms())
}

/// [`follower_view`] at a given time (stats count whole days before `now`).
pub fn follower_view_at(conn: &Connection, follower: &str, target: &str, now: i64) -> anyhow::Result<Option<Value>> {
    let active: bool = conn
        .query_row(
            "SELECT 1 FROM follow WHERE follower_account_id = ?1 AND target_account_id = ?2 AND status = 'active'",
            params![follower, target],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
    if !active {
        return Ok(None);
    }
    let row: Option<(String, Option<i64>, i64)> = conn
        .query_row(
            "SELECT entries, displayed_since, revealed_at FROM follower_front_view WHERE follower_account_id = ?1 AND target_account_id = ?2",
            params![follower, target],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?;
    // how precise `since` is (the ceiling's time rule), so clients can say "around" or "this evening"
    let follow: Option<(String, String)> = conn
        .query_row(
            "SELECT id, ceiling FROM follow WHERE follower_account_id = ?1 AND target_account_id = ?2 AND status = 'active'",
            params![follower, target],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let (time, history, stats) = match follow {
        Some((id, ceiling)) => {
            let f = Follow {
                id,
                follower: follower.into(),
                ceiling: serde_json::from_str(&ceiling).unwrap_or(json!({})),
                prefs: Prefs::default(),
            };
            let (c, _) = ceiling_for(conn, target, &f)?;
            if !c.share_current_front {
                return Ok(Some(json!({"entries": [], "since": null, "revealed_at": null, "shared": false})));
            }
            (serde_json::to_value(c.time)?, c.share_history, c.share_stats)
        }
        None => (json!({"mode": "hidden"}), false, false),
    };
    let mut v = match row {
        Some((entries, since, revealed_at)) => {
            let entries: Vec<Shown> = serde_json::from_str(&entries).unwrap_or_default();
            json!({"entries": entries, "since": since, "revealed_at": revealed_at, "time": time})
        }
        None => json!({"entries": [], "since": null, "revealed_at": null, "time": time}),
    };
    if history || stats {
        let log = revealed_log(conn, follower, target)?;
        if history {
            v["history"] = history_of(&log);
        }
        if stats {
            v["stats"] = stats_of(&log, now);
        }
    }
    Ok(Some(v))
}

/// One revealed state: its (fuzzed) start and who was shown.
struct Logged {
    displayed: notify::Displayed,
    entries: Vec<Shown>,
}

/// How far back follower history and stats go.
const HISTORY_DAYS: i64 = 30;
const HISTORY_ITEMS: usize = 50;

fn revealed_log(conn: &Connection, follower: &str, target: &str) -> anyhow::Result<Vec<Logged>> {
    let mut st = conn.prepare_cached(
        "SELECT displayed, entries FROM follower_front_log
         WHERE follower_account_id = ?1 AND target_account_id = ?2
           AND revealed_at >= (SELECT max(revealed_at) FROM follower_front_log
                               WHERE follower_account_id = ?1 AND target_account_id = ?2) - ?3
         ORDER BY revealed_at, rowid",
    )?;
    let rows = st.query_map(params![follower, target, HISTORY_DAYS * 86_400_000], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
    })?;
    let mut out = Vec::new();
    for r in rows {
        let (displayed, entries) = r?;
        let (Ok(displayed), Ok(entries)) = (serde_json::from_str(&displayed), serde_json::from_str(&entries)) else {
            continue;
        };
        out.push(Logged { displayed, entries });
    }
    Ok(out)
}

fn names(entries: &[Shown]) -> Vec<Value> {
    entries.iter().map(|e| json!({"name": e.name, "color": e.color, "glyph": e.glyph, "seen": e.seen})).collect()
}

/// Newest first; consecutive reveals that show the same people are one item.
fn history_of(log: &[Logged]) -> Value {
    let mut items: Vec<Value> = Vec::new();
    let mut last: Option<Vec<String>> = None;
    for l in log {
        let key: Vec<String> = l.entries.iter().map(|e| e.name.clone()).collect();
        if last.as_ref() == Some(&key) {
            continue;
        }
        items.push(json!({"entries": names(&l.entries), "time": precision_of(&Some(l.displayed))}));
        last = Some(key);
    }
    items.reverse();
    items.truncate(HISTORY_ITEMS);
    Value::Array(items)
}

/// Share of revealed front time per name over the log window, rounded to 5 %, from the fuzzed
/// times. Whole days only: each state counts until the next revealed one, and nothing counts past
/// the start of today (UTC), so the numbers move at a reveal or at midnight and never at a switch.
fn stats_of(log: &[Logged], now: i64) -> Value {
    let today = now - now.rem_euclid(86_400_000);
    let mut spans: BTreeMap<String, i64> = BTreeMap::new();
    let mut total = 0;
    for (i, l) in log.iter().enumerate() {
        let Some(start) = l.displayed.at else { continue };
        let end = log.get(i + 1).and_then(|n| n.displayed.at).unwrap_or(today).min(today);
        let d = (end - start).max(0);
        let front: Vec<&Shown> = l.entries.iter().filter(|e| e.seen.level() == Level::Front).collect();
        if d == 0 || front.is_empty() {
            continue;
        }
        total += d;
        for e in front {
            *spans.entry(e.name.clone()).or_default() += d;
        }
    }
    if total == 0 {
        return json!({"days": HISTORY_DAYS, "members": []});
    }
    let mut members: Vec<(String, i64)> =
        spans.into_iter().map(|(n, d)| (n, ((d as f64 / total as f64 * 20.0).round() as i64) * 5)).collect();
    members.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    json!({"days": HISTORY_DAYS, "members": members.iter().map(|(n, p)| json!({"name": n, "share_pct": p})).collect::<Vec<_>>()})
}
