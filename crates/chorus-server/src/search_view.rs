//! Ephemeral current-front read view for scoped search (D-114), never a sync grant.
use crate::api_data::{DataError, Principal};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};

pub struct CurrentView<'a> {
    conn: &'a Connection,
    p: &'a Principal,
    front: HashSet<String>,
    channels: HashMap<String, bool>,
    authors: HashMap<String, Value>,
    selection: Option<(String, String, String)>,
    neighbors: HashMap<(String, bool), Option<String>>,
    audiences: HashMap<String, (bool, bool)>,
    cutoffs: HashMap<String, Option<i64>>,
    cards: HashMap<String, Value>,
}
impl<'a> CurrentView<'a> {
    pub fn new(conn: &'a Connection, p: &'a Principal) -> Result<Self, DataError> {
        let front = crate::api_data::current_front(conn, &Principal::owner(&p.account_id))?;
        let front = front["front"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|e| e["subject_type"] == "member" && matches!(e["level"].as_str(), Some("front" | "cocon")))
            .filter_map(|e| e["subject_id"].as_str().map(str::to_string))
            .collect();
        Ok(Self {
            conn,
            p,
            front,
            channels: HashMap::new(),
            authors: HashMap::new(),
            selection: None,
            neighbors: HashMap::new(),
            audiences: HashMap::new(),
            cutoffs: HashMap::new(),
            cards: HashMap::new(),
        })
    }
    pub fn channel_visible(&mut self, id: &str) -> Result<bool, DataError> {
        self.channel(id, &mut HashSet::new())
    }
    pub fn message(&mut self, id: &str) -> Result<bool, DataError> {
        self.message_inner(id, &mut HashSet::new())
    }
    fn message_inner(&mut self, id: &str, seen: &mut HashSet<String>) -> Result<bool, DataError> {
        if seen.len() > 256 || !seen.insert(format!("m:{id}")) {
            return Ok(false);
        }
        let Some((channel, owner, visibility, at)) = self.conn.query_row(
            "SELECT channel_id, account_id, visibility, occurred_at FROM message WHERE id=?1 AND deleted_at IS NULL", [id],
            |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,Option<String>>(2)?,r.get::<_,i64>(3)?))).optional()? else { return Ok(false) };
        if !self.p.is_device() && owner != self.p.account_id {
            return Ok(false);
        }
        if !self.channel(&channel, seen)? {
            return Ok(false);
        }
        if self.cutoff(&channel)?.is_some_and(|t| at <= t) {
            return Ok(false);
        }
        Ok(self.audience(&owner, visibility.as_deref()))
    }
    fn cutoff(&mut self, channel: &str) -> Result<Option<i64>, DataError> {
        if let Some(cutoff) = self.cutoffs.get(channel) {
            return Ok(*cutoff);
        }
        let cutoff = self.conn.query_row(
            "SELECT CAST(value AS INTEGER) FROM pref WHERE account_id=?1 AND device_id='' AND key='chat_hidden:'||?2 AND json_type(value) IN ('integer','real')",
            params![self.p.account_id,channel], |r| r.get(0)).optional()?;
        self.cutoffs.insert(channel.to_string(), cutoff);
        Ok(cutoff)
    }
    /// The view is immutable for one request. Parse each distinct stored audience once, keeping
    /// public and own-account answers separate so identical JSON cannot grant foreign asides.
    fn audience(&mut self, owner: &str, raw: Option<&str>) -> bool {
        let Some(raw) = raw else { return true };
        if let Some((public, own)) = self.audiences.get(raw) {
            return *public || (owner == self.p.account_id && *own);
        }
        let v = serde_json::from_str::<Value>(raw).ok();
        let public = v.as_ref().is_some_and(|v| v["mode"] == "all");
        let own = match v.as_ref().and_then(|v| v["mode"].as_str()) {
            Some("all" | "system_only") => true,
            Some("members") => v
                .as_ref()
                .and_then(|v| v["member_ids"].as_array())
                .is_some_and(|ids| ids.iter().filter_map(Value::as_str).any(|id| self.front.contains(id))),
            _ => false,
        };
        self.audiences.insert(raw.to_string(), (public, own));
        public || (owner == self.p.account_id && own)
    }
    fn channel(&mut self, id: &str, seen: &mut HashSet<String>) -> Result<bool, DataError> {
        if let Some(ok) = self.channels.get(id) {
            return Ok(*ok);
        }
        if !seen.insert(format!("c:{id}")) {
            return Ok(false);
        }
        let Some((kind, parent, listed, space_kind)) = self
            .conn
            .query_row(
                "SELECT c.kind,c.parent_message_id,c.member_ids,s.kind FROM channel c JOIN space s ON s.id=c.space_id
             WHERE c.id=?1 AND c.deleted_at IS NULL AND c.archived_at IS NULL AND s.deleted_at IS NULL
             AND EXISTS(SELECT 1 FROM scope_access sa WHERE sa.account_id=?2 AND sa.scope='space:'||s.id)",
                params![id, self.p.account_id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, Option<String>>(1)?,
                        r.get::<_, Option<String>>(2)?,
                        r.get::<_, String>(3)?,
                    ))
                },
            )
            .optional()?
        else {
            return Ok(false);
        };
        // Missing membership is compatible with ordinary channels; malformed stored membership
        // is never an empty/unrestricted list, including on a thread and its ancestors.
        let listed: Vec<String> = match listed.as_deref() {
            None => Vec::new(),
            Some(raw) => match serde_json::from_str(raw) {
                Ok(ids) => ids,
                Err(_) => {
                    self.channels.insert(id.to_string(), false);
                    return Ok(false);
                }
            },
        };
        let mut ok = crate::perms::can(self.conn, &self.p.account_id, id, "view")?;
        if ok && kind == "thread" {
            ok = match parent {
                Some(p) => self.message_inner(&p, seen)?,
                None => false,
            };
        } else if ok {
            let internal_private = space_kind == "internal" && (kind == "member_dm" || !listed.is_empty());
            let own = listed
                .into_iter()
                .filter(|id| {
                    self.conn
                        .query_row(
                            "SELECT EXISTS(SELECT 1 FROM member WHERE id=?1 AND account_id=?2)",
                            params![id, self.p.account_id],
                            |r| r.get::<_, bool>(0),
                        )
                        .unwrap_or(false)
                })
                .collect::<Vec<_>>();
            // Search always uses the recorded current front, regardless of the chat-list toggle.
            if internal_private || !own.is_empty() {
                let included = crate::perms::with_parts(self.conn, &self.p.account_id, own)?;
                ok = included.iter().any(|m| self.front.contains(m));
            }
        }
        self.channels.insert(id.to_string(), ok);
        Ok(ok)
    }
    pub fn card(&mut self, id: &str) -> Result<Option<Value>, DataError> {
        if let Some(card) = self.cards.get(id) {
            return Ok(Some(card.clone()));
        }
        if !self.message(id)? {
            return Ok(None);
        }
        let Some(mut m) = crate::messages::one(self.conn, self.p, id)? else { return Ok(None) };
        let space = m["space_id"].as_str().unwrap_or_default().to_string();
        if !self.authors.contains_key(&space) {
            let cards = crate::spaces::authors(self.conn, &self.p.account_id, &space)
                .map_err(|_| DataError::Bad("author cards unavailable".into()))?;
            self.authors.insert(space.clone(), cards);
        }
        m["author_cards"] = json!(
            self.authors[&space]["members"]
                .as_array()
                .into_iter()
                .flatten()
                .filter(|a| m["authors"].as_array().is_some_and(|ids| ids.contains(&a["id"])))
                .cloned()
                .collect::<Vec<_>>()
        );
        let extras: (Option<String>, Option<String>, Option<String>) =
            self.conn.query_row("SELECT quote,forward_snapshot,poll FROM message WHERE id=?1", [id], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?))
            })?;
        for (key, value) in [("quote", extras.0), ("forward_snapshot", extras.1), ("poll", extras.2)] {
            m[key] = value.and_then(|v| serde_json::from_str(&v).ok()).unwrap_or(Value::Null);
        }
        m["poll_tally"] = self
            .conn
            .query_row(
                "SELECT json_object('counts',json(counts),'voters',voters) FROM poll_tally WHERE poll_id=?1",
                [id],
                |r| r.get::<_, String>(0),
            )
            .optional()?
            .and_then(|v| serde_json::from_str::<Value>(&v).ok())
            .unwrap_or(Value::Null);
        // an early close (D-136), as on post rows
        m["poll_closed_at"] = self
            .conn
            .query_row("SELECT closed_at FROM poll_close WHERE poll_id=?1", [id], |r| r.get::<_, i64>(0))
            .optional()?
            .map_or(Value::Null, Value::from);
        if let Some(reply) = m["reply_to"].as_str().map(str::to_string)
            && !self.message(&reply)?
        {
            m["reply_to"] = Value::Null;
            m.as_object_mut().unwrap().remove("reply_to_channel_id");
        }
        let mut st = self.conn.prepare_cached("SELECT a.id,a.blob_hash,a.thumb_blob_hash,COALESCE(a.filename,'file'),COALESCE(a.mime,'application/octet-stream'),COALESCE(a.size,0),COALESCE(a.alt_text,''),a.is_spoiler
            FROM item_attachment ia JOIN attachment a ON a.id=ia.attachment_id WHERE ia.owner_type='message' AND ia.owner_id=?1 AND a.blob_hash IS NOT NULL ORDER BY ia.position,a.id")?;
        m["attachments"] = Value::Array(st.query_map([id], |r| Ok(json!({"id":r.get::<_,String>(0)?,"blob_hash":r.get::<_,Option<String>>(1)?,
            "thumb_blob_hash":r.get::<_,Option<String>>(2)?,"filename":r.get::<_,Option<String>>(3)?,"mime":r.get::<_,Option<String>>(4)?,
            "size":r.get::<_,Option<i64>>(5)?,"alt_text":r.get::<_,Option<String>>(6)?,"is_spoiler":r.get::<_,bool>(7)?})))?.collect::<Result<Vec<_>,_>>()?);
        let mut st = self.conn.prepare_cached("SELECT ms.offset_u16,ms.length_u16,COALESCE((SELECT json_group_array(member_id) FROM
            (SELECT member_id FROM message_segment_author WHERE message_id=ms.message_id AND idx=ms.idx ORDER BY position)),'[]') FROM message_segment ms WHERE message_id=?1 ORDER BY idx")?;
        m["segments"] = Value::Array(st.query_map([id], |r| { let authors:String=r.get(2)?; Ok(json!({"offset":r.get::<_,i64>(0)?,"length":r.get::<_,i64>(1)?,"authors":serde_json::from_str::<Value>(&authors).unwrap_or(json!([]))})) })?.collect::<Result<Vec<_>,_>>()?);
        self.cards.insert(id.to_string(), m.clone());
        Ok(Some(m))
    }
    /// Validate channel/ancestor permissions once; SQLite filters message audiences before LIMIT.
    pub fn selection(&mut self) -> Result<(String, String, String), DataError> {
        if let Some(s) = &self.selection {
            return Ok(s.clone());
        }
        let mut st=self.conn.prepare_cached("SELECT c.id FROM channel c JOIN space s ON s.id=c.space_id WHERE c.deleted_at IS NULL AND c.archived_at IS NULL AND s.deleted_at IS NULL AND EXISTS(SELECT 1 FROM scope_access WHERE account_id=?1 AND scope='space:'||s.id)")?;
        let ids = st.query_map([&self.p.account_id], |r| r.get::<_, String>(0))?.collect::<Result<Vec<_>, _>>()?;
        let mut allowed = Vec::new();
        for id in ids {
            if self.channel(&id, &mut HashSet::new())? {
                allowed.push(id);
            }
        }
        let mut st=self.conn.prepare_cached("SELECT substr(key,13),CAST(value AS INTEGER) FROM pref WHERE account_id=?1 AND device_id='' AND substr(key,1,12)='chat_hidden:' AND json_type(value) IN ('integer','real')")?;
        let cutoffs = st
            .query_map([&self.p.account_id], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))?
            .collect::<Result<HashMap<_, _>, _>>()?;
        let s = (json!(allowed).to_string(), json!(self.front).to_string(), json!(cutoffs).to_string());
        self.selection = Some(s.clone());
        Ok(s)
    }
    pub fn neighbor(&mut self, m: &Value, after: bool) -> Result<Option<Value>, DataError> {
        let anchor = m["id"].as_str().unwrap_or_default();
        let key = (anchor.to_string(), after);
        if let Some(id) = self.neighbors.get(&key).cloned() {
            return id.map(|id| self.card(&id)).transpose().map(Option::flatten);
        }
        let channel = m["channel_id"].as_str().unwrap_or_default();
        if !self.message(anchor)? {
            return Ok(None);
        }
        let cutoff = self.cutoff(channel)?;
        // Seek on message_channel_time rather than reading the entire channel for dense hits.
        // There is no candidate LIMIT: skip arbitrarily many hidden rows before taking one
        // visible neighbor. Channel/ancestor checks above and row guards match message_inner.
        let (op, direction) = if after { (">", "ASC") } else { ("<", "DESC") };
        let sql = format!(
            "SELECT m.id,m.account_id,m.visibility FROM message m
             WHERE m.channel_id=?1 AND (m.occurred_at,m.id){op}(?2,?3) AND m.deleted_at IS NULL
               AND (?4=0 OR m.account_id=?5) AND (?6 IS NULL OR m.occurred_at>?6)
             ORDER BY m.occurred_at {direction},m.id {direction}"
        );
        let mut st = self.conn.prepare_cached(&sql)?;
        let mut rows = st.query(params![
            channel,
            m["occurred_at"].as_i64().unwrap_or_default(),
            anchor,
            !self.p.is_device(),
            self.p.account_id,
            cutoff,
        ])?;
        let mut id = None;
        while let Some(row) = rows.next()? {
            let owner = row.get_ref(1)?.as_str().map_err(anyhow::Error::from)?;
            let visibility = row.get_ref(2)?.as_str_or_null().map_err(anyhow::Error::from)?;
            if self.audience(owner, visibility) {
                id = Some(row.get::<_, String>(0)?);
                break;
            }
        }
        // No visible row lies between these two anchors, so the reverse edge is also known.
        // This avoids scanning the same sparse interval twice for adjacent search hits.
        if let Some(id) = &id {
            self.neighbors.insert((id.clone(), !after), Some(anchor.to_string()));
        }
        self.neighbors.insert(key, id.clone());
        id.map(|id| self.card(&id)).transpose().map(Option::flatten)
    }
}

/// Equivalent to CurrentView::message after channel/ancestor validation. CASE keeps malformed
/// JSON fail-closed without evaluating json_extract on invalid input. Context seeks use the
/// canonical guard above; tests compare both paths, including malformed stored audiences.
pub fn message_sql(account: &str, channels: &str, front: &str, cutoffs: &str, token: &str) -> String {
    format!(
        r#"m.deleted_at IS NULL AND m.channel_id IN (SELECT value FROM json_each({channels}))
      AND ({token}=0 OR m.account_id={account})
      AND NOT EXISTS(SELECT 1 FROM json_each({cutoffs}) h WHERE h.key=m.channel_id AND m.occurred_at<=h.value)
      AND CASE WHEN m.visibility IS NULL THEN 1 WHEN NOT json_valid(m.visibility) THEN 0 ELSE
        CASE json_extract(m.visibility,'$.mode') WHEN 'all' THEN 1
          WHEN 'system_only' THEN m.account_id={account}
          WHEN 'members' THEN CASE WHEN json_array_length({front})=0 THEN 0 ELSE
            m.account_id={account} AND json_type(m.visibility,'$.member_ids')='array'
            AND EXISTS(SELECT 1 FROM json_each(m.visibility,'$.member_ids') a WHERE a.type='text' AND a.value IN (SELECT value FROM json_each({front}))) END
          ELSE 0 END END"#
    )
}
