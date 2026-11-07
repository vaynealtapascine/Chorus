//! Post alerts (D-106): another account asks to hear about one member's new posts and journal
//! entries; the member's account says yes or no. Once yes, each new top-level post or entry that
//! member writes notifies the subscriber, if and only if the subscriber may read it then
//! (`posts::readable_sql`: server-wide, followers, or a bucket they are in). Private posts,
//! replies and reposts never do, and nothing is said about fronting.
//!
//! Requests live in `post_alert` (server-written, like follow requests; not ops). Asking needs a
//! profile the asker may already open (D-101), so member ids can't be probed; a person (singlet)
//! has no member pages, so their followers ask by account for their own member. A declined request
//! keeps looking "requested" to the asker and never pings the owner again; an owner removing a
//! subscriber declines them the same way.

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

/// Older posts (imports, a long-offline device catching up) don't ping anyone.
const FRESH_MS: i64 = 24 * 60 * 60 * 1000;

#[derive(Debug, thiserror::Error)]
pub enum AlertError {
    #[error("this profile isn't shared with you")]
    NotFound,
    #[error("you can't ask for alerts about your own members")]
    Own,
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

impl From<rusqlite::Error> for AlertError {
    fn from(e: rusqlite::Error) -> Self {
        AlertError::Internal(e.into())
    }
}

fn stored(conn: &Connection, subscriber: &str, member: &str) -> rusqlite::Result<Option<String>> {
    conn.query_row(
        "SELECT status FROM post_alert WHERE subscriber_account_id = ?1 AND member_id = ?2",
        params![subscriber, member],
        |r| r.get(0),
    )
    .optional()
}

/// What the asker sees: `none` · `requested` · `active` (a declined request stays `requested`).
pub fn status(conn: &Connection, subscriber: &str, member: &str) -> anyhow::Result<&'static str> {
    Ok(match stored(conn, subscriber, member)?.as_deref() {
        Some("active") => "active",
        Some(_) => "requested",
        None => "none",
    })
}

fn account_name(conn: &Connection, account: &str) -> anyhow::Result<String> {
    let (handle, display): (Option<String>, Option<String>) = conn
        .query_row("SELECT handle, display_name FROM account WHERE id = ?1", [account], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?
        .unwrap_or_default();
    Ok(display
        .filter(|s| !s.trim().is_empty())
        .or_else(|| handle.filter(|s| !s.trim().is_empty()).map(|h| format!("@{h}")))
        .unwrap_or_else(|| "Someone".into()))
}

fn member_name(conn: &Connection, member: &str) -> anyhow::Result<String> {
    Ok(conn
        .query_row("SELECT COALESCE(NULLIF(trim(display_name), ''), name) FROM member WHERE id = ?1", [member], |r| {
            r.get::<_, Option<String>>(0)
        })
        .optional()?
        .flatten()
        .unwrap_or_else(|| "Someone".into()))
}

fn notify(conn: &Connection, recipient: &str, kind: &str, payload: Value, now: i64) -> anyhow::Result<()> {
    notify_at(conn, recipient, kind, payload, now, now)
}

fn notify_at(conn: &Connection, recipient: &str, kind: &str, payload: Value, now: i64, due: i64) -> anyhow::Result<()> {
    let id = chorus_core::id::new_id(now as u64, rand::random());
    conn.execute(
        "INSERT INTO notification (id, recipient_account_id, kind, payload, created_at, due_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![id, recipient, kind, serde_json::to_string(&payload)?, now, due],
    )?;
    Ok(())
}

/// A post's own notification choice (D-127), from its `post.create` payload's `alerts`: `none`
/// pings nobody; `delayed` pings each recipient at its own random time 5–60 minutes later.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Alerts {
    Now,
    Delayed,
    None,
}

impl Alerts {
    pub fn of(payload: &Value) -> Self {
        match payload.get("alerts").and_then(Value::as_str) {
            Some("none") => Alerts::None,
            Some("delayed") => Alerts::Delayed,
            _ => Alerts::Now,
        }
    }

    fn due(self, now: i64) -> i64 {
        match self {
            Alerts::Delayed => now + rand::random_range(MIN_DELAY_MS..=MAX_DELAY_MS),
            _ => now,
        }
    }
}

/// The range of a delayed post's pings.
pub const MIN_DELAY_MS: i64 = 5 * 60 * 1000;
pub const MAX_DELAY_MS: i64 = 60 * 60 * 1000;

/// A person's own member, when `viewer` follows them (or is them): what asking by account means.
pub fn person_member(conn: &Connection, viewer: &str, owner: &str) -> anyhow::Result<Option<String>> {
    Ok(conn
        .query_row(
            "SELECT m.id FROM member m JOIN account a ON a.id = m.account_id
             WHERE a.id = ?2 AND a.kind = 'person' AND m.is_self AND m.deleted_at IS NULL
               AND (?1 = ?2 OR EXISTS (SELECT 1 FROM follow f WHERE f.follower_account_id = ?1
                                       AND f.target_account_id = ?2 AND f.status = 'active'))
             ORDER BY m.id LIMIT 1",
            params![viewer, owner],
            |r| r.get(0),
        )
        .optional()?)
}

/// Whether `subscriber` may ask about `member`: a profile they may open, or a person they follow.
fn may_ask(conn: &Connection, subscriber: &str, owner: &str, member: &str) -> anyhow::Result<bool> {
    Ok(crate::profiles::profile(conn, subscriber, owner, member)?.is_some()
        || person_member(conn, subscriber, owner)?.as_deref() == Some(member))
}

/// Ask for alerts about `owner`'s `member`. The owner hears about a new request once.
pub fn request(
    conn: &Connection,
    subscriber: &str,
    owner: &str,
    member: &str,
    now: i64,
) -> Result<&'static str, AlertError> {
    if subscriber == owner {
        return Err(AlertError::Own);
    }
    if !may_ask(conn, subscriber, owner, member)? {
        return Err(AlertError::NotFound);
    }
    if stored(conn, subscriber, member)?.is_some() {
        return Ok(status(conn, subscriber, member)?);
    }
    conn.execute(
        "INSERT INTO post_alert (subscriber_account_id, member_id, owner_account_id, status, created_at)
         VALUES (?1, ?2, ?3, 'requested', ?4)",
        params![subscriber, member, owner, now],
    )?;
    let who = account_name(conn, subscriber)?;
    let name = member_name(conn, member)?;
    notify(
        conn,
        owner,
        "alert_request",
        json!({"t": "alert", "kind": "alert_request", "title": format!("{who} asked for post alerts"),
               "text": format!("They'd like to hear when {name} posts."), "member_id": member, "account_id": subscriber}),
        now,
    )?;
    Ok("requested")
}

/// The asker stops asking (or turns alerts off). A declined request stays declined.
pub fn cancel(conn: &Connection, subscriber: &str, member: &str) -> anyhow::Result<()> {
    conn.execute(
        "DELETE FROM post_alert WHERE subscriber_account_id = ?1 AND member_id = ?2 AND status <> 'declined'",
        params![subscriber, member],
    )?;
    Ok(())
}

/// `GET /alerts`: who asked about, or hears about, this account's members (declined ones aren't
/// listed), newest first.
pub fn incoming(conn: &Connection, owner: &str) -> anyhow::Result<Value> {
    let mut st = conn.prepare_cached(
        "SELECT pa.member_id, pa.subscriber_account_id, pa.status, pa.created_at, a.handle, a.display_name, a.avatar_blob, a.kind
         FROM post_alert pa JOIN account a ON a.id = pa.subscriber_account_id
         WHERE pa.owner_account_id = ?1 AND pa.status IN ('requested', 'active')
         ORDER BY pa.created_at DESC, pa.subscriber_account_id",
    )?;
    let items = st
        .query_map([owner], |r| {
            Ok(json!({
                "member_id": r.get::<_, String>(0)?,
                "account": {"id": r.get::<_, String>(1)?, "handle": r.get::<_, Option<String>>(4)?,
                            "display_name": r.get::<_, Option<String>>(5)?, "avatar_blob": r.get::<_, Option<String>>(6)?,
                            "kind": r.get::<_, Option<String>>(7)?},
                "status": r.get::<_, String>(2)?,
                "created_at": r.get::<_, i64>(3)?,
            }))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(json!({"items": items}))
}

/// The owner says yes (the asker hears so) or no / stops someone's alerts (silently).
pub fn decide(
    conn: &Connection,
    owner: &str,
    subscriber: &str,
    member: &str,
    approve: bool,
    now: i64,
) -> Result<(), AlertError> {
    let current: Option<String> = conn
        .query_row(
            "SELECT status FROM post_alert WHERE subscriber_account_id = ?1 AND member_id = ?2 AND owner_account_id = ?3",
            params![subscriber, member, owner],
            |r| r.get(0),
        )
        .optional()?;
    let Some(current) = current else { return Err(AlertError::NotFound) };
    if !approve {
        conn.execute(
            "UPDATE post_alert SET status = 'declined', decided_at = ?3 WHERE subscriber_account_id = ?1 AND member_id = ?2",
            params![subscriber, member, now],
        )?;
        return Ok(());
    }
    if current != "requested" {
        return Ok(());
    }
    conn.execute(
        "UPDATE post_alert SET status = 'active', decided_at = ?3 WHERE subscriber_account_id = ?1 AND member_id = ?2",
        params![subscriber, member, now],
    )?;
    let name = member_name(conn, member)?;
    notify(
        conn,
        subscriber,
        "alert_accepted",
        json!({"t": "alert", "kind": "alert_accepted", "title": format!("Post alerts for {name}"),
               "text": format!("{name} said yes: you'll hear when they post."), "member_id": member, "account_id": owner}),
        now,
    )?;
    Ok(())
}

/// After a live `post.create` is projected: ping each approved subscriber of its authors who may
/// read it now. One notification per subscriber, however many of its authors they follow.
pub fn on_post(conn: &Connection, post_id: &str, alerts: Alerts, now: i64) -> anyhow::Result<()> {
    if alerts == Alerts::None {
        return Ok(());
    }
    type Post = (String, Option<String>, Option<String>, String, Option<String>, Option<String>, Option<String>, i64);
    let post: Option<Post> = conn
        .query_row(
            "SELECT account_id, kind, title, text, cw, reply_to_id, repost_of_id, occurred_at
             FROM post WHERE id = ?1 AND deleted_at IS NULL",
            [post_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?, r.get(7)?)),
        )
        .optional()?;
    let Some((account, kind, title, text, cw, reply_to, repost_of, at)) = post else { return Ok(()) };
    if repost_of.is_some() || now - at > FRESH_MS {
        return Ok(());
    }
    if let Some(parent) = reply_to {
        return on_reply(conn, post_id, &account, &parent, cw, text, alerts, now);
    }
    let readable = crate::posts::readable_sql("pa.subscriber_account_id");
    let mut st = conn.prepare(&format!(
        "SELECT DISTINCT pa.subscriber_account_id FROM post_alert pa JOIN post_author au ON au.member_id = pa.member_id
         JOIN post p ON p.id = au.post_id
         WHERE au.post_id = ?1 AND pa.status = 'active' AND pa.owner_account_id = ?2
           AND pa.subscriber_account_id <> ?2 AND {readable}"
    ))?;
    let subscribers: Vec<String> = st.query_map(params![post_id, account], |r| r.get(0))?.collect::<Result<_, _>>()?;
    if subscribers.is_empty() {
        return Ok(());
    }
    let mut st = conn.prepare_cached("SELECT member_id FROM post_author WHERE post_id = ?1 ORDER BY position")?;
    let authors: Vec<String> = st.query_map([post_id], |r| r.get(0))?.collect::<Result<_, _>>()?;
    let mut names = Vec::new();
    for m in &authors {
        names.push(member_name(conn, m)?);
    }
    let who = if names.is_empty() { "Someone".to_string() } else { names.join(" & ") };
    let entry = kind.as_deref() == Some("entry");
    let headline = if entry { format!("{who} wrote a journal entry") } else { format!("{who} posted") };
    // a warning stands in for what it covers
    let body = match cw.filter(|c| !c.trim().is_empty()) {
        Some(c) => format!("Content warning: {c}"),
        None => title.filter(|t| entry && !t.trim().is_empty()).unwrap_or(text),
    };
    let body: String = body.chars().take(280).collect();
    let mut followed = conn.prepare_cached(
        "SELECT 1 FROM post_alert WHERE subscriber_account_id = ?1 AND owner_account_id = ?2 AND member_id = ?3
         AND status = 'active'",
    )?;
    for subscriber in subscribers {
        // the face is the first author this subscriber asked to hear about (the title names all)
        let mut face = None;
        for m in &authors {
            if followed.exists(params![subscriber, account, m])? {
                face = Some(m);
                break;
            }
        }
        let face = face.or(authors.first());
        let mut sender = json!({"name": who});
        if let Some(m) = face
            && let Some((color, avatar)) = conn
                .query_row("SELECT color, avatar_blob FROM member WHERE id = ?1", [m], |r| {
                    Ok((r.get::<_, Option<String>>(0)?, r.get::<_, Option<String>>(1)?))
                })
                .optional()?
        {
            sender["color"] = json!(color);
            sender["avatar_blob"] = json!(avatar);
        }
        notify_at(
            conn,
            &subscriber,
            "post",
            json!({"t": "post", "kind": "post", "title": headline, "text": body, "post_id": post_id,
                   "account_id": account, "member_id": face, "sender": sender}),
            now,
            alerts.due(now),
        )?;
    }
    Ok(())
}

/// A reply to another account's post tells that account (D-127, kind `post_reply`), if it may read
/// the reply and hasn't turned journal replies off (`notify_chat.post_reply`). Opens the reply.
#[allow(clippy::too_many_arguments)]
fn on_reply(
    conn: &Connection,
    post_id: &str,
    account: &str,
    parent: &str,
    cw: Option<String>,
    text: String,
    alerts: Alerts,
    now: i64,
) -> anyhow::Result<()> {
    let Some(owner) = conn
        .query_row("SELECT account_id FROM post WHERE id = ?1 AND deleted_at IS NULL", [parent], |r| {
            r.get::<_, String>(0)
        })
        .optional()?
        .filter(|o| o != account)
    else {
        return Ok(());
    };
    let readable = crate::posts::readable_sql("?1");
    let may_read: bool = conn.query_row(
        &format!("SELECT EXISTS(SELECT 1 FROM post p WHERE p.id = ?2 AND p.deleted_at IS NULL AND {readable})"),
        params![owner, post_id],
        |r| r.get(0),
    )?;
    if !may_read || !crate::activity::kind_wanted(conn, &owner, "post_reply")? {
        return Ok(());
    }
    let mut st = conn.prepare_cached("SELECT member_id FROM post_author WHERE post_id = ?1 ORDER BY position")?;
    let authors: Vec<String> = st.query_map([post_id], |r| r.get(0))?.collect::<Result<_, _>>()?;
    let mut names = Vec::new();
    for m in &authors {
        names.push(member_name(conn, m)?);
    }
    let who = if names.is_empty() { "Someone".to_string() } else { names.join(" & ") };
    let body = match cw.filter(|c| !c.trim().is_empty()) {
        Some(c) => format!("Content warning: {c}"),
        None => text,
    };
    let body: String = body.chars().take(280).collect();
    let mut sender = json!({"name": who});
    if let Some(m) = authors.first()
        && let Some((color, avatar)) = conn
            .query_row("SELECT color, avatar_blob FROM member WHERE id = ?1", [m], |r| {
                Ok((r.get::<_, Option<String>>(0)?, r.get::<_, Option<String>>(1)?))
            })
            .optional()?
    {
        sender["color"] = json!(color);
        sender["avatar_blob"] = json!(avatar);
    }
    notify_at(
        conn,
        &owner,
        "post_reply",
        json!({"t": "post", "kind": "post_reply", "title": format!("{who} replied to your post"), "text": body,
               "post_id": post_id, "parent_id": parent, "account_id": account, "member_id": authors.first(),
               "sender": sender}),
        now,
        alerts.due(now),
    )
}
