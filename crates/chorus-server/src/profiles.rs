//! Friend profiles (D-101): what another account may see of a system's members, read on demand.
//!
//! `member.visibility` says who can open a member's profile at all; `member.field_visibility` maps
//! a part (`display_name`, `pronouns`, `description`, `birthday`, `banner`) or a custom field id to
//! its own audience. A viewer sees a part only if they may see the profile **and** the part. An
//! absent built-in part follows the profile; an absent custom field follows the field's
//! `default_visibility` (private unless chosen). Audiences use the post rule (`posts::readable_sql`):
//! `private` · `followers` · `buckets` · `server`; anything malformed fails closed.
//!
//! Nothing here says anything about fronting, history or when things changed (NOTIFICATIONS §5).

use std::collections::HashSet;

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

/// The built-in profile parts that carry their own audience.
pub const PARTS: &[&str] = &["display_name", "pronouns", "description", "birthday", "banner"];

/// What the owner's audiences need to know about one viewer.
pub struct Viewer {
    own: bool,
    follows: bool,
    buckets: HashSet<String>,
}

impl Viewer {
    pub fn of(conn: &Connection, viewer: &str, owner: &str) -> rusqlite::Result<Viewer> {
        if viewer == owner {
            return Ok(Viewer { own: true, follows: true, buckets: HashSet::new() });
        }
        let follows = conn
            .query_row(
                "SELECT 1 FROM follow WHERE follower_account_id = ?1 AND target_account_id = ?2 AND status = 'active'",
                params![viewer, owner],
                |_| Ok(()),
            )
            .optional()?
            .is_some();
        let mut buckets = HashSet::new();
        if follows {
            let mut st = conn.prepare_cached(
                "SELECT b.id FROM bucket_assignment ba JOIN bucket b ON b.id = ba.bucket_id
                 WHERE ba.follower_account_id = ?1 AND ba.is_present AND b.account_id = ?2 AND b.deleted_at IS NULL",
            )?;
            for id in st.query_map(params![viewer, owner], |r| r.get::<_, String>(0))? {
                buckets.insert(id?);
            }
        }
        Ok(Viewer { own: false, follows, buckets })
    }

    /// Whether this audience includes the viewer. Malformed or unknown audiences fail closed.
    pub fn allows(&self, audience: &Value) -> bool {
        if self.own {
            return true;
        }
        match audience.get("mode").and_then(Value::as_str) {
            Some("server") => true,
            Some("followers") => self.follows,
            Some("buckets") => {
                self.follows
                    && audience
                        .get("bucket_ids")
                        .and_then(Value::as_array)
                        .is_some_and(|ids| ids.iter().filter_map(Value::as_str).any(|id| self.buckets.contains(id)))
            }
            _ => false,
        }
    }
}

fn json_of(s: Option<String>) -> Value {
    s.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null)
}

struct Row {
    id: String,
    name: String,
    display_name: Option<String>,
    pronouns: Option<String>,
    description: Option<String>,
    description_entities: Value,
    color: Option<String>,
    avatar_blob: Option<String>,
    banner_blob: Option<String>,
    birthday: Option<String>,
    sigil: Option<String>,
    visibility: Value,
    field_visibility: Value,
}

const COLUMNS: &str = "id, name, display_name, pronouns, description, description_entities, color, avatar_blob,
    banner_blob, birthday, json_extract(CASE WHEN json_valid(sigils) THEN sigils ELSE '[]' END, '$[0]'),
    visibility, field_visibility";

fn row(r: &rusqlite::Row) -> rusqlite::Result<Row> {
    Ok(Row {
        id: r.get(0)?,
        name: r.get::<_, Option<String>>(1)?.unwrap_or_default(),
        display_name: r.get(2)?,
        pronouns: r.get(3)?,
        description: r.get(4)?,
        description_entities: json_of(r.get(5)?),
        color: r.get(6)?,
        avatar_blob: r.get(7)?,
        banner_blob: r.get(8)?,
        birthday: r.get(9)?,
        sigil: r.get(10)?,
        visibility: json_of(r.get(11)?),
        field_visibility: json_of(r.get(12)?),
    })
}

impl Row {
    fn open(&self, v: &Viewer) -> bool {
        v.allows(&self.visibility)
    }
    /// A built-in part: its own audience if set, else the profile's (already checked by the caller).
    fn part(&self, v: &Viewer, key: &str) -> bool {
        self.audience(key).is_none_or(|a| v.allows(&a))
    }
    /// A part's or field's own audience: `None` if unset; a parts map that isn't an object hides
    /// everything in it (fails closed).
    fn audience(&self, key: &str) -> Option<Value> {
        match &self.field_visibility {
            Value::Null => None,
            Value::Object(map) => map.get(key).filter(|a| !a.is_null()).cloned(),
            _ => Some(json!({ "mode": "private" })),
        }
    }
    fn shown<T: Clone>(&self, v: &Viewer, key: &str, value: &Option<T>) -> Option<T> {
        if self.part(v, key) { value.clone() } else { None }
    }
    fn card(&self, v: &Viewer) -> Value {
        json!({
            "id": self.id,
            "name": self.name,
            "display_name": self.shown(v, "display_name", &self.display_name).filter(|s| !s.trim().is_empty()),
            "pronouns": self.shown(v, "pronouns", &self.pronouns).filter(|s| !s.trim().is_empty()),
            "color": self.color,
            "sigil": self.sigil.clone().filter(|s| !s.trim().is_empty()),
            "avatar_blob": self.avatar_blob,
        })
    }
}

/// `GET /accounts/{id}/members`: the members of `owner` that `viewer` may open, in their order.
pub fn members(conn: &Connection, viewer: &str, owner: &str) -> anyhow::Result<Value> {
    let v = Viewer::of(conn, viewer, owner)?;
    let mut st = conn.prepare(&format!(
        "SELECT {COLUMNS} FROM member WHERE account_id = ?1 AND deleted_at IS NULL AND archived_at IS NULL
         ORDER BY sort_key IS NULL, sort_key, name COLLATE NOCASE, id"
    ))?;
    let rows = st.query_map([owner], row)?.collect::<Result<Vec<_>, _>>()?;
    let items: Vec<Value> = rows.iter().filter(|m| m.open(&v)).map(|m| m.card(&v)).collect();
    Ok(json!({ "items": items }))
}

/// `GET /accounts/{id}/members/{member}`: the parts and fields of one profile `viewer` may see,
/// or `None` (the same for a member that doesn't exist and one that isn't shared).
pub fn profile(conn: &Connection, viewer: &str, owner: &str, member: &str) -> anyhow::Result<Option<Value>> {
    let v = Viewer::of(conn, viewer, owner)?;
    let m = conn
        .query_row(
            &format!("SELECT {COLUMNS} FROM member WHERE id = ?1 AND account_id = ?2 AND deleted_at IS NULL AND archived_at IS NULL"),
            params![member, owner],
            row,
        )
        .optional()?;
    let Some(m) = m.filter(|m| m.open(&v)) else { return Ok(None) };
    let mut out = m.card(&v);
    let bio = m.part(&v, "description");
    out["description"] = json!(if bio { m.description.clone() } else { None });
    out["description_entities"] = if bio && m.description.is_some() { m.description_entities.clone() } else { json!([]) };
    out["birthday"] = json!(m.shown(&v, "birthday", &m.birthday));
    out["banner_blob"] = json!(m.shown(&v, "banner", &m.banner_blob));
    let mut st = conn.prepare_cached(
        "SELECT f.id, f.name, f.type, v.value, f.default_visibility FROM field_value v JOIN field_def f ON f.id = v.field_id
         WHERE v.member_id = ?1 AND f.account_id = ?2 AND f.deleted_at IS NULL
         ORDER BY f.sort_key IS NULL, f.sort_key, f.name COLLATE NOCASE, f.id",
    )?;
    let mut fields = Vec::new();
    for r in st.query_map(params![member, owner], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?, r.get::<_, Option<String>>(2)?, json_of(r.get(3)?), json_of(r.get(4)?)))
    })? {
        let (id, name, kind, value, default) = r?;
        let audience = m.audience(&id).unwrap_or(default);
        let empty = value.is_null() || value.as_str().is_some_and(|s| s.trim().is_empty()) || value.as_array().is_some_and(Vec::is_empty);
        if empty || !v.allows(&audience) {
            continue;
        }
        fields.push(json!({ "id": id, "name": name.unwrap_or_default(), "type": kind.unwrap_or_else(|| "text".into()), "value": value }));
    }
    out["fields"] = json!(fields);
    Ok(Some(out))
}

/// A shared member's avatar, or their banner when the banner part is shared too (blob reads).
pub fn blob_visible(conn: &Connection, viewer: &str, hash: &str) -> anyhow::Result<bool> {
    let mut st = conn.prepare_cached(&format!(
        "SELECT {COLUMNS}, account_id FROM member WHERE (avatar_blob = ?1 OR banner_blob = ?1)
         AND account_id <> ?2 AND deleted_at IS NULL AND archived_at IS NULL"
    ))?;
    let rows = st
        .query_map(params![hash, viewer], |r| Ok((r.get::<_, String>(13)?, row(r)?)))?
        .collect::<Result<Vec<_>, _>>()?;
    for (owner, m) in rows {
        let v = Viewer::of(conn, viewer, &owner)?;
        if !m.open(&v) {
            continue;
        }
        if m.avatar_blob.as_deref() == Some(hash) || (m.banner_blob.as_deref() == Some(hash) && m.part(&v, "banner")) {
            return Ok(true);
        }
    }
    Ok(false)
}
