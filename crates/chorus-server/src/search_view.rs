//! Ephemeral current-front read view for scoped search (D-110), never a sync grant.
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
        Ok(Self { conn, p, front, channels: HashMap::new(), authors: HashMap::new() })
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
        let cutoff: Option<i64> = self.conn.query_row(
            "SELECT CAST(value AS INTEGER) FROM pref WHERE account_id=?1 AND device_id='' AND key='chat_hidden:'||?2 AND json_type(value) IN ('integer','real')",
            params![self.p.account_id,channel], |r| r.get(0)).optional()?;
        if cutoff.is_some_and(|t| at <= t) {
            return Ok(false);
        }
        let v = visibility.as_deref().map(serde_json::from_str::<Value>).transpose().ok().flatten();
        if visibility.is_some() && v.is_none() {
            return Ok(false);
        }
        Ok(match v.as_ref().and_then(|v| v["mode"].as_str()) {
            None => visibility.is_none(),
            Some("all") => true,
            Some("system_only") => owner == self.p.account_id,
            Some("members") => {
                owner == self.p.account_id
                    && v.as_ref()
                        .and_then(|v| v["member_ids"].as_array())
                        .is_some_and(|ids| ids.iter().filter_map(Value::as_str).any(|id| self.front.contains(id)))
            }
            _ => false,
        })
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
        let mut ok = crate::perms::can(self.conn, &self.p.account_id, id, "view")?;
        if ok && kind == "thread" {
            ok = match parent {
                Some(p) => self.message_inner(&p, seen)?,
                None => false,
            };
        } else if ok {
            let listed: Vec<String> = listed.as_deref().and_then(|s| serde_json::from_str(s).ok()).unwrap_or_default();
            let internal_private = space_kind == "internal" && (kind == "member_dm" || !listed.is_empty());
            let own =
                listed
                    .into_iter()
                    .filter(|id| {
                        self.conn.query_row(
                "SELECT EXISTS(SELECT 1 FROM member WHERE id=?1 AND account_id=?2 AND deleted_at IS NULL)",
                params![id,self.p.account_id], |r| r.get::<_,bool>(0)).unwrap_or(false)
                    })
                    .collect::<Vec<_>>();
            // Search always uses the recorded current front, regardless of the chat-list toggle.
            if internal_private || !own.is_empty() {
                ok = own.iter().any(|m| self.front.contains(m));
            }
        }
        self.channels.insert(id.to_string(), ok);
        Ok(ok)
    }
    pub fn card(&mut self, id: &str) -> Result<Option<Value>, DataError> {
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
        Ok(Some(m))
    }
    pub fn neighbor(&mut self, m: &Value, after: bool) -> Result<Option<Value>, DataError> {
        let (cmp, order) = if after { (">", "ASC") } else { ("<", "DESC") };
        let sql = format!(
            "SELECT id FROM message WHERE channel_id=?1 AND deleted_at IS NULL AND (occurred_at,id){cmp}(?2,?3) ORDER BY occurred_at {order},id {order}"
        );
        let mut st = self.conn.prepare(&sql)?;
        let ids = st
            .query_map(params![m["channel_id"].as_str(), m["occurred_at"].as_i64(), m["id"].as_str()], |r| {
                r.get::<_, String>(0)
            })?;
        for id in ids {
            if let Some(card) = self.card(&id?)? {
                return Ok(Some(card));
            }
        }
        Ok(None)
    }
}
