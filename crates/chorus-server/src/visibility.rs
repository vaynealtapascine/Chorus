//! Structured chat visibility shared by notifications and cross-account reads.

use std::collections::HashSet;

use chorus_core::op::Op;
use chorus_core::sync::Digest;
use rusqlite::{Connection, OptionalExtension, params, params_from_iter};
use serde_json::Value;

use crate::oplog;

/// A message that every member of its space may see: its own visibility is public (NULL is the
/// legacy/default public value), and it isn't in a thread under a message that isn't — a thread is
/// as private as its parent.
pub const PUBLIC_MESSAGE_SQL: &str = "((m.visibility IS NULL OR json_extract(m.visibility, '$.mode') = 'all')
    AND NOT EXISTS (SELECT 1 FROM channel tc JOIN message pm ON pm.id = tc.parent_message_id
        WHERE tc.id = m.channel_id AND tc.kind = 'thread'
          AND NOT (pm.visibility IS NULL OR json_extract(pm.visibility, '$.mode') = 'all')))";

pub fn is_public(value: Option<&Value>) -> bool {
    value.is_none_or(|v| v.is_null() || v.get("mode").and_then(Value::as_str) == Some("all"))
}

/// A message every account that may view its channel may see (not a private aside), for the
/// account in the SQL expression `account` (e.g. `?2`): public, and the channel's `view`
/// permission (perms.rs). Readers add "or it's their own".
pub fn visible_message_sql(account: &str) -> String {
    format!("({PUBLIC_MESSAGE_SQL} AND {})", crate::perms::can_sql(account, "m.channel_id", "view"))
}

/// A channel the account may view that isn't a thread under a message it can't see (a thread
/// whose parent hasn't arrived yet stays hidden until it does). `c` is the channel.
fn visible_channel_sql(account: &str) -> String {
    format!(
        "({} AND NOT (c.kind = 'thread' AND c.parent_message_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM message m WHERE m.id = c.parent_message_id AND {PUBLIC_MESSAGE_SQL})))",
        crate::perms::can_sql(account, "c.id", "view")
    )
}

fn visible_attachment_sql(account: &str) -> String {
    format!(
        "EXISTS (SELECT 1 FROM item_attachment ia JOIN message m ON m.id = ia.owner_id
                 WHERE ia.owner_type = 'message' AND ia.attachment_id = a.id AND {})",
        visible_message_sql(account)
    )
}

/// A participant cannot mutate a private aside owned by another account, even with a guessed
/// message id. Normal public-message authorization remains in the existing scope rules.
pub fn related_write_allowed(conn: &Connection, author: &str, o: &Op) -> anyhow::Result<bool> {
    if o.kind == "post.create"
        && let Some(parent) = o.payload.get("reply_to").and_then(Value::as_str)
    {
        let readable = crate::posts::readable_sql("?2");
        let sql = format!("SELECT p.deleted_at IS NULL AND {readable} FROM post p WHERE p.id=?1");
        // Own offline replies may reach the server before their parent. An existing parent,
        // including one owned by another account, must be readable when the reply is accepted.
        let visible: Option<bool> = conn.query_row(&sql, params![parent, author], |r| r.get(0)).optional()?;
        return Ok(visible.unwrap_or(true));
    }
    if matches!(o.kind.as_str(), "post.react" | "post.unreact") {
        let Some(target) = o.payload.get("target_id").and_then(Value::as_str) else { return Ok(false) };
        let Some(member) = o.payload.get("member_id").and_then(Value::as_str) else { return Ok(false) };
        let owned: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM member WHERE id=?1 AND account_id=?2 AND deleted_at IS NULL)",
            params![member, author],
            |r| r.get(0),
        )?;
        if !owned {
            return Ok(false);
        }
        let readable = crate::posts::readable_sql("?2");
        let sql = format!("SELECT p.deleted_at IS NULL AND {readable} FROM post p WHERE p.id=?1");
        // A reaction may sync before its post. The row, once present, is always checked through
        // the same audience predicate as GET /posts; an absent target reveals no post data.
        let visible: Option<bool> = conn.query_row(&sql, params![target, author], |r| r.get(0)).optional()?;
        return Ok(visible.unwrap_or(true));
    }
    let message_id = if matches!(o.kind.as_str(), "message.send" | "message.forward") {
        o.payload.get("reply_to").and_then(Value::as_str)
    } else if o.kind.starts_with("message.") || o.kind.starts_with("reaction.") || o.kind.starts_with("read.") {
        o.payload
            .get("message_id")
            .and_then(Value::as_str)
            .or_else(|| o.kind.starts_with("message.").then(|| o.entity()).flatten())
    } else {
        None
    };
    let Some(id) = message_id else { return Ok(true) };
    let row: Option<(Option<String>, Option<String>)> = conn
        .query_row("SELECT account_id, visibility FROM message WHERE id=?1", [id], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    let Some((owner, visibility)) = row else { return Ok(true) };
    if owner.as_deref() == Some(author) {
        return Ok(true);
    }
    Ok(visibility.as_deref().is_none_or(|s| serde_json::from_str::<Value>(s).is_ok_and(|v| is_public(Some(&v)))))
}

/// What the sync rule needs to know about the things an op points at, for one account.
trait Lookups {
    /// The account is in the op's space (its owner or a present member), not just a guest.
    fn member(&mut self) -> anyhow::Result<bool>;
    fn message(&mut self, id: &str) -> anyhow::Result<bool>;
    fn channel(&mut self, id: &str) -> anyhow::Result<bool>;
    fn attachment(&mut self, id: &str) -> anyhow::Result<bool>;
}

/// The effective per-account sync rule for a shared-space op. The sender always retains their
/// own ops; read marks go nowhere else. Everything tied to a channel needs that channel's `view`
/// permission (perms.rs) and,
/// for messages, public visibility; attachment metadata waits for a visible message link. A guest
/// of a channel (not in the space) gets that channel's ops and the space's name, nothing else.
fn visible_with(account: &str, o: &Op, look: &mut dyn Lookups) -> anyhow::Result<bool> {
    if o.account_id.as_deref() == Some(account) || !o.scope.starts_with("space:") {
        return Ok(true);
    }
    let str_of = |key: &str| o.payload.get(key).and_then(Value::as_str);
    let k = o.kind.as_str();
    if k.starts_with("space.") {
        return Ok(matches!(k, "space.create" | "space.set") || look.member()?);
    }
    if k == "message.send" || k == "message.forward" {
        return Ok(is_public(o.payload.get("visibility")) && look.channel(str_of("channel_id").unwrap_or_default())?);
    }
    if k.starts_with("channel.") {
        // a new thread names its parent; later channel ops find it in the projection
        if let Some(parent) = str_of("parent_message_id") {
            return look.message(parent);
        }
        return o.entity().map_or(Ok(false), |c| look.channel(c));
    }
    if k.starts_with("message.") {
        let id = str_of("message_id").or_else(|| o.entity());
        return id.map_or(Ok(false), |m| look.message(m));
    }
    if k.starts_with("reaction.") {
        let id = str_of("target_id").or_else(|| str_of("message_id")).or_else(|| o.entity());
        return id.map_or(Ok(false), |m| look.message(m));
    }
    if k.starts_with("read.") {
        // read marks are the account's own (unread counts, "track reading per member"): nobody
        // else gets them, or another account would see when it read and, per member, who of it
        // was fronting (NOTIFICATIONS §5)
        return Ok(false);
    }
    if k.starts_with("attachment.") {
        return o.entity().map_or(Ok(false), |a| look.attachment(a));
    }
    look.member()
}

/// Straight from the database, one question at a time.
struct Direct<'a> {
    conn: &'a Connection,
    account: &'a str,
    space: &'a str,
}

impl Lookups for Direct<'_> {
    fn member(&mut self) -> anyhow::Result<bool> {
        Ok(crate::perms::is_member(self.conn, self.account, self.space)?)
    }
    fn message(&mut self, id: &str) -> anyhow::Result<bool> {
        let sql = format!("SELECT EXISTS(SELECT 1 FROM message m WHERE m.id = ?2 AND {})", visible_message_sql("?1"));
        Ok(self.conn.prepare_cached(&sql)?.query_row(params![self.account, id], |r| r.get(0))?)
    }
    fn channel(&mut self, id: &str) -> anyhow::Result<bool> {
        let sql = format!("SELECT EXISTS(SELECT 1 FROM channel c WHERE c.id = ?2 AND {})", visible_channel_sql("?1"));
        Ok(self.conn.prepare_cached(&sql)?.query_row(params![self.account, id], |r| r.get(0))?)
    }
    fn attachment(&mut self, id: &str) -> anyhow::Result<bool> {
        let sql = format!("SELECT {} FROM (SELECT ?2 AS id) a", visible_attachment_sql("?1"));
        Ok(self.conn.prepare_cached(&sql)?.query_row(params![self.account, id], |r| r.get(0))?)
    }
}

pub fn op_visible_to(conn: &Connection, account: &str, o: &Op) -> anyhow::Result<bool> {
    let space = o.scope.strip_prefix("space:").unwrap_or_default();
    visible_with(account, o, &mut Direct { conn, account, space })
}

/// First pass over a page: answer yes to everything and note what was asked.
#[derive(Default)]
struct Record {
    messages: HashSet<String>,
    channels: HashSet<String>,
    attachments: HashSet<String>,
}

impl Lookups for Record {
    fn member(&mut self) -> anyhow::Result<bool> {
        Ok(true)
    }
    fn message(&mut self, id: &str) -> anyhow::Result<bool> {
        self.messages.insert(id.to_string());
        Ok(true)
    }
    fn channel(&mut self, id: &str) -> anyhow::Result<bool> {
        self.channels.insert(id.to_string());
        Ok(true)
    }
    fn attachment(&mut self, id: &str) -> anyhow::Result<bool> {
        self.attachments.insert(id.to_string());
        Ok(true)
    }
}

/// Second pass: the answers, fetched in one query per kind.
struct Known {
    member: bool,
    messages: HashSet<String>,
    channels: HashSet<String>,
    attachments: HashSet<String>,
}

impl Lookups for Known {
    fn member(&mut self) -> anyhow::Result<bool> {
        Ok(self.member)
    }
    fn message(&mut self, id: &str) -> anyhow::Result<bool> {
        Ok(self.messages.contains(id))
    }
    fn channel(&mut self, id: &str) -> anyhow::Result<bool> {
        Ok(self.channels.contains(id))
    }
    fn attachment(&mut self, id: &str) -> anyhow::Result<bool> {
        Ok(self.attachments.contains(id))
    }
}

/// The ids in `ids` for which `query` (with `?1` = the account and `{ids}` = the list) holds.
fn matching_ids(
    conn: &Connection,
    account: &str,
    ids: &HashSet<String>,
    query: &str,
) -> anyhow::Result<HashSet<String>> {
    if ids.is_empty() {
        return Ok(HashSet::new());
    }
    let marks = (2..ids.len() + 2).map(|i| format!("?{i}")).collect::<Vec<_>>().join(",");
    let sql = query.replace("{ids}", &marks);
    let mut statement = conn.prepare(&sql)?;
    let args = std::iter::once(account).chain(ids.iter().map(String::as_str));
    let rows = statement.query_map(params_from_iter(args), |r| r.get::<_, String>(0))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// A page's digest for one account: the same rule as [`op_visible_to`], with one query per kind
/// of thing the page points at instead of one per op.
fn visible_page(conn: &Connection, account: &str, space: &str, page: &[Op]) -> anyhow::Result<Digest> {
    let mut asked = Record::default();
    for o in page {
        visible_with(account, o, &mut asked)?;
    }
    let mut known = Known {
        member: crate::perms::is_member(conn, account, space)?,
        messages: matching_ids(
            conn,
            account,
            &asked.messages,
            &format!("SELECT m.id FROM message m WHERE m.id IN ({{ids}}) AND {}", visible_message_sql("?1")),
        )?,
        channels: matching_ids(
            conn,
            account,
            &asked.channels,
            &format!("SELECT c.id FROM channel c WHERE c.id IN ({{ids}}) AND {}", visible_channel_sql("?1")),
        )?,
        attachments: matching_ids(
            conn,
            account,
            &asked.attachments,
            &format!(
                "SELECT a.id FROM (SELECT value AS id FROM json_each(json_array({{ids}}))) a WHERE {}",
                visible_attachment_sql("?1")
            ),
        )?,
    };
    let mut digest = Digest::default();
    for o in page {
        if visible_with(account, o, &mut known)? {
            digest.add(&o.id);
        }
    }
    Ok(digest)
}

/// The digest must contain exactly the op ids this account can receive in catch-up.
pub fn visible_digest(conn: &Connection, account: &str, scope: &str) -> anyhow::Result<Digest> {
    if !scope.starts_with("space:")
        || !conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM op WHERE scope=?1 AND status='applied' AND account_id<>?2)",
            params![scope, account],
            |r| r.get::<_, bool>(0),
        )?
    {
        return oplog::digest(conn, scope);
    }
    let mut digest = Digest::default();
    let mut cursor = 0;
    loop {
        let page = oplog::scope_after(conn, scope, cursor, 1000)?;
        let Some(last) = page.last().and_then(|o| o.seq) else { break };
        cursor = last;
        let short = page.len() < 1000;
        let part = visible_page(conn, account, scope.strip_prefix("space:").unwrap_or_default(), &page)?;
        for (total, page_part) in digest.xor.iter_mut().zip(part.xor) {
            *total ^= page_part;
        }
        digest.count += part.count;
        if short {
            break;
        }
    }
    Ok(digest)
}

/// A public send can make earlier out-of-order edits and pre-send attachment ops readable.
/// Re-send them live; op ids make duplicates harmless and catch-up already includes them.
pub fn backfill_for_public_send(conn: &Connection, o: &Op) -> anyhow::Result<Vec<Op>> {
    if !matches!(o.kind.as_str(), "message.send" | "message.forward") || !is_public(o.payload.get("visibility")) {
        return Ok(Vec::new());
    }
    let Some(message_id) = o.entity() else { return Ok(Vec::new()) };
    let before = o.seq.unwrap_or(i64::MAX);
    let mut earlier = oplog::for_entity(conn, message_id)?;
    for id in o.payload.get("attachments").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str) {
        earlier.extend(oplog::for_entity(conn, id)?);
    }
    // (by the op_message_ref index: this runs for every public send)
    let mut st = conn.prepare_cached(
        "SELECT id FROM op WHERE json_extract(payload, '$.message_id')=?3
         AND scope=?1 AND seq<?2 AND status='applied'",
    )?;
    let ids: Vec<String> =
        st.query_map(params![o.scope, before, message_id], |r| r.get(0))?.collect::<Result<_, _>>()?;
    for id in ids {
        if let Some(op) = oplog::by_id(conn, &id)? {
            earlier.push(op);
        }
    }
    // a thread started under this message before it arrived, and what was said in it
    let mut st =
        conn.prepare("SELECT id FROM channel WHERE kind = 'thread' AND parent_message_id = ?1 AND space_id = ?2")?;
    let space = o.scope.strip_prefix("space:").unwrap_or_default();
    let threads: Vec<String> = st.query_map(params![message_id, space], |r| r.get(0))?.collect::<Result<_, _>>()?;
    for thread in threads {
        earlier.extend(oplog::for_entity(conn, &thread)?);
        let mut st = conn.prepare_cached("SELECT id FROM message WHERE channel_id = ?1")?;
        let msgs: Vec<String> = st.query_map([&thread], |r| r.get(0))?.collect::<Result<_, _>>()?;
        for m in msgs {
            earlier.extend(oplog::for_entity(conn, &m)?);
        }
    }
    Ok(earlier.into_iter().filter(|op| op.scope == o.scope && op.seq.is_some_and(|seq| seq < before)).collect())
}
