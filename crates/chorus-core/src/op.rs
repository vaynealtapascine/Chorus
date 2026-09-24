//! Op envelope and catalogue (DATA_MODEL.md §2–3).
//!
//! The catalogue is data: each op kind maps to an entity and an action, and field-setting kinds
//! list the fields they may touch. Server and clients project with the same table, so a field a
//! client may not set can never reach storage.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::hlc::Hlc;
use crate::id::is_valid_id;
use crate::time::TimeSource;

/// Current payload schema version produced by this core for every kind.
pub const CURRENT_V: u32 = 1;
/// Maximum serialized payload size accepted (bytes). Message text limits are enforced separately.
pub const MAX_PAYLOAD_BYTES: usize = 256 * 1024;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Op {
    pub id: String,
    pub kind: String,
    #[serde(default = "one")]
    pub v: u32,
    pub scope: String,
    #[serde(default)]
    pub entity_id: Option<String>,
    pub hlc: Hlc,
    pub device_at: i64,
    #[serde(default)]
    pub tz_offset_min: i32,
    #[serde(default)]
    pub mono: Option<i64>,
    #[serde(default)]
    pub boot_id: Option<String>,
    #[serde(default)]
    pub time_source: TimeSource,
    #[serde(default)]
    pub seen_seq: i64,
    #[serde(default)]
    pub member_id: Option<String>,
    #[serde(default)]
    pub payload: Value,

    // Assigned by the server. Clients never send them; the server ignores them if they do.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seq: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub account_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub occurred_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub received_at: Option<i64>,
}

fn one() -> u32 {
    1
}

impl Op {
    /// Best known human time: corrected if the server has seen it, device time otherwise.
    pub fn time(&self) -> i64 {
        self.occurred_at.unwrap_or(self.device_at)
    }

    pub fn payload_obj(&self) -> Option<&Map<String, Value>> {
        self.payload.as_object()
    }

    pub fn entity(&self) -> Option<&str> {
        self.entity_id.as_deref()
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Scope {
    Server,
    Account(String),
    Space(String),
}

impl Scope {
    pub fn parse(s: &str) -> Option<Scope> {
        if s == "server" {
            return Some(Scope::Server);
        }
        let (kind, id) = s.split_once(':')?;
        if !is_valid_id(id) {
            return None;
        }
        match kind {
            "account" => Some(Scope::Account(id.to_string())),
            "space" => Some(Scope::Space(id.to_string())),
            _ => None,
        }
    }
}

impl std::fmt::Display for Scope {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Scope::Server => f.write_str("server"),
            Scope::Account(id) => write!(f, "account:{id}"),
            Scope::Space(id) => write!(f, "space:{id}"),
        }
    }
}

/// Which kind of scope an entity lives in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ScopeKind {
    Server,
    Account,
    Space,
}

/// Merge strategy (DATA_MODEL.md §3).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    /// Create a row; payload is its initial fields (LWW-F from then on).
    Create,
    /// Set some fields (LWW-F).
    Set,
    /// Set `deleted_at` = op time (LWW-F).
    Delete,
    /// Clear `deleted_at` (LWW-F).
    Restore,
    Archive,
    Unarchive,
    /// LWW element set: add / remove the element named by the payload key fields.
    SetAdd,
    SetRemove,
    /// Append-only: a new row keyed by the op id (messages, posts, attachments…).
    Append,
    /// A new revision of an appended row.
    Revise,
    /// Front timeline ops.
    Front,
    /// Kind-specific logic (read marks, reviews, pins, drafts, prefs…).
    Special,
    /// Server-only administrative ops.
    Admin,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KindSpec {
    pub kind: &'static str,
    /// Table the op affects (DATA_MODEL.md §4).
    pub table: &'static str,
    pub scope: ScopeKind,
    pub action: Action,
    /// Fields a create/set may carry. Empty for actions that don't carry fields.
    pub fields: &'static [&'static str],
    /// Subset of `fields` accepted only on create.
    pub create_only: &'static [&'static str],
}

const MEMBER_FIELDS: &[&str] = &[
    "name",
    "display_name",
    "pronouns",
    "description",
    "description_entities",
    "color",
    "avatar_blob",
    "banner_blob",
    "birthday",
    "sigils",
    "proxy_tags",
    "pinned_post_id",
    "sort_key",
    "is_locked",
    "visibility",
    "field_visibility",
    "notify_policy",
    "short_id",
    "pk_id",
    "is_self",
];
const SYSTEM_FIELDS: &[&str] =
    &["name", "tag", "description", "description_entities", "color", "timezone", "terminology"];
const ACCOUNT_FIELDS: &[&str] = &["display_name", "handle", "avatar_blob", "settings"];
const GROUP_FIELDS: &[&str] = &[
    "kind",
    "parent_id",
    "name",
    "description",
    "description_entities",
    "color",
    "icon",
    "avatar_blob",
    "can_front",
    "sort_key",
    "visibility",
];
const STATE_FIELDS: &[&str] = &["name", "description", "color", "icon", "sort_key"];
const FIELD_DEF_FIELDS: &[&str] = &["name", "type", "options", "default_visibility", "sort_key"];
const SPACE_FIELDS: &[&str] = &["kind", "name", "icon", "color", "description", "settings"];
const CHANNEL_FIELDS: &[&str] = &[
    "space_id",
    "kind",
    "category",
    "name",
    "topic",
    "icon",
    "color",
    "parent_message_id",
    "member_ids",
    "settings",
    "sort_key",
];
const RELTYPE_FIELDS: &[&str] = &["name", "inverse_name", "is_symmetric", "color", "icon"];
const REL_FIELDS: &[&str] = &["from_member_id", "to_kind", "to_id", "to_label", "type_id", "note", "visibility"];
const LIST_FIELDS: &[&str] = &["name", "description", "visibility"];
const FEED_FIELDS: &[&str] = &["name", "description", "query", "query_ast", "visibility"];
const BUCKET_FIELDS: &[&str] = &["name", "color", "sort_key", "ceiling"];
const STAGE_FIELDS: &[&str] = &["name", "definition"];
const DRAFT_FIELDS: &[&str] = &["context", "authors", "text", "entities"];
const EMOJI_FIELDS: &[&str] = &["name", "aliases", "category", "blob_hash", "is_animated"];
const ATTACHMENT_SET_FIELDS: &[&str] = &["alt_text", "is_spoiler"];
const POST_SET_FIELDS: &[&str] = &["visibility"];

macro_rules! k {
    ($kind:literal, $table:literal, $scope:ident, $action:ident) => {
        KindSpec {
            kind: $kind,
            table: $table,
            scope: ScopeKind::$scope,
            action: Action::$action,
            fields: &[],
            create_only: &[],
        }
    };
    ($kind:literal, $table:literal, $scope:ident, $action:ident, $fields:expr) => {
        KindSpec {
            kind: $kind,
            table: $table,
            scope: ScopeKind::$scope,
            action: Action::$action,
            fields: $fields,
            create_only: &[],
        }
    };
    ($kind:literal, $table:literal, $scope:ident, $action:ident, $fields:expr, $co:expr) => {
        KindSpec {
            kind: $kind,
            table: $table,
            scope: ScopeKind::$scope,
            action: Action::$action,
            fields: $fields,
            create_only: $co,
        }
    };
}

pub static CATALOGUE: &[KindSpec] = &[
    k!("account.set", "account", Account, Set, ACCOUNT_FIELDS),
    k!("system.set", "system", Account, Set, SYSTEM_FIELDS),
    k!("member.create", "member", Account, Create, MEMBER_FIELDS, &["is_self"]),
    k!("member.set", "member", Account, Set, MEMBER_FIELDS, &["is_self"]),
    k!("member.archive", "member", Account, Archive),
    k!("member.unarchive", "member", Account, Unarchive),
    k!("member.delete", "member", Account, Delete),
    k!("member.restore", "member", Account, Restore),
    k!("group.create", "member_group", Account, Create, GROUP_FIELDS),
    k!("group.set", "member_group", Account, Set, GROUP_FIELDS),
    k!("group.delete", "member_group", Account, Delete),
    k!("group.restore", "member_group", Account, Restore),
    k!("group.add_member", "group_membership", Account, SetAdd),
    k!("group.remove_member", "group_membership", Account, SetRemove),
    k!("state.create", "custom_state", Account, Create, STATE_FIELDS),
    k!("state.set", "custom_state", Account, Set, STATE_FIELDS),
    k!("state.delete", "custom_state", Account, Delete),
    k!("state.restore", "custom_state", Account, Restore),
    k!("field.define", "field_def", Account, Create, FIELD_DEF_FIELDS, &["type"]),
    k!("field.set_def", "field_def", Account, Set, FIELD_DEF_FIELDS, &["type"]),
    k!("field.delete_def", "field_def", Account, Delete),
    k!("field.set_value", "field_value", Account, Special),
    k!("front.switch", "switch", Account, Front),
    k!("front.add", "switch", Account, Front),
    k!("front.remove", "switch", Account, Front),
    k!("front.update", "switch", Account, Front),
    k!("front.retract", "switch", Account, Front),
    k!("front.unretract", "switch", Account, Front),
    k!("front.amend", "switch", Account, Front),
    k!("front.review_resolve", "front_review", Account, Special),
    k!("space.create", "space", Space, Create, SPACE_FIELDS, &["kind"]),
    k!("space.set", "space", Space, Set, SPACE_FIELDS, &["kind"]),
    k!("space.delete", "space", Space, Delete),
    k!("space.restore", "space", Space, Restore),
    k!("space.join", "space_member", Space, SetAdd),
    k!("space.leave", "space_member", Space, SetRemove),
    k!("space.set_role", "space_member", Space, Special),
    k!("space.set_roles", "space", Space, Special),
    k!("channel.create", "channel", Space, Create, CHANNEL_FIELDS, &["space_id", "kind", "parent_message_id"]),
    k!("channel.set", "channel", Space, Set, CHANNEL_FIELDS, &["space_id", "kind", "parent_message_id"]),
    k!("channel.archive", "channel", Space, Archive),
    k!("channel.unarchive", "channel", Space, Unarchive),
    k!("channel.delete", "channel", Space, Delete),
    k!("channel.restore", "channel", Space, Restore),
    k!("channel.set_permission", "channel_permission", Space, Special),
    k!("message.send", "message", Space, Append),
    k!("message.forward", "message", Space, Append),
    k!("message.edit", "message", Space, Revise),
    k!("message.delete", "message", Space, Delete),
    k!("message.restore", "message", Space, Restore),
    k!("message.pin", "message", Space, Special),
    k!("message.unpin", "message", Space, Special),
    k!("reaction.add", "reaction", Space, SetAdd),
    k!("reaction.remove", "reaction", Space, SetRemove),
    k!("read.mark", "read_state", Space, Special),
    k!("read.set", "read_state", Space, Special),
    k!("attachment.create", "attachment", Space, Append),
    k!("attachment.set", "attachment", Space, Set, ATTACHMENT_SET_FIELDS),
    k!("post.create", "post", Account, Append),
    k!("post.edit", "post", Account, Revise),
    k!("post.delete", "post", Account, Delete),
    k!("post.restore", "post", Account, Restore),
    k!("post.set_visibility", "post", Account, Set, POST_SET_FIELDS),
    k!("post.react", "reaction", Account, SetAdd),
    k!("post.unreact", "reaction", Account, SetRemove),
    k!("post.attachment", "attachment", Account, Append),
    k!("draft.set", "draft", Account, Set, DRAFT_FIELDS),
    k!("draft.delete", "draft", Account, Delete),
    k!("highlight.add", "highlight", Account, SetAdd),
    k!("highlight.remove", "highlight", Account, SetRemove),
    k!("highlight.reorder", "highlight", Account, Special),
    k!("reltype.set", "relationship_type", Account, Set, RELTYPE_FIELDS),
    k!("reltype.delete", "relationship_type", Account, Delete),
    k!("relationship.set", "relationship", Account, Set, REL_FIELDS),
    k!("relationship.delete", "relationship", Account, Delete),
    k!("list.set", "member_list", Account, Set, LIST_FIELDS),
    k!("list.delete", "member_list", Account, Delete),
    k!("list.add", "member_list_item", Account, SetAdd),
    k!("list.remove", "member_list_item", Account, SetRemove),
    k!("feed.set", "feed", Account, Set, FEED_FIELDS),
    k!("feed.delete", "feed", Account, Delete),
    k!("bucket.set", "bucket", Account, Set, BUCKET_FIELDS),
    k!("bucket.delete", "bucket", Account, Delete),
    k!("bucket.assign", "bucket_assignment", Account, SetAdd),
    k!("bucket.unassign", "bucket_assignment", Account, SetRemove),
    k!("follow.request", "follow", Account, Special),
    k!("follow.accept", "follow", Account, Special),
    k!("follow.set_ceiling", "follow", Account, Special),
    k!("follow.set_prefs", "follow", Account, Special),
    k!("follow.end", "follow", Account, Special),
    k!("stage.save", "stage", Account, Set, STAGE_FIELDS),
    k!("stage.delete", "stage", Account, Delete),
    k!("pref.set", "pref", Account, Special),
    k!("emoji.create", "custom_emoji", Server, Create, EMOJI_FIELDS),
    k!("emoji.set", "custom_emoji", Server, Set, EMOJI_FIELDS),
    k!("emoji.delete", "custom_emoji", Server, Delete),
    k!("emoji.restore", "custom_emoji", Server, Restore),
];

/// Tables with no create op (drafts, buckets, stages, feeds…): their `.set` is an upsert, so the
/// first set makes the row exist.
pub fn set_upserts(table: &str) -> bool {
    !CATALOGUE.iter().any(|k| k.table == table && matches!(k.action, Action::Create | Action::Append))
}

pub fn spec(kind: &str) -> Option<&'static KindSpec> {
    CATALOGUE.iter().find(|k| k.kind == kind)
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum OpError {
    #[error("invalid id: {0}")]
    BadId(String),
    #[error("invalid scope: {0}")]
    BadScope(String),
    #[error("op kind {kind} does not belong in scope {scope}")]
    WrongScope { kind: String, scope: String },
    #[error("missing entity_id")]
    MissingEntity,
    #[error("payload must be a JSON object")]
    PayloadNotObject,
    #[error("payload too large")]
    PayloadTooLarge,
    #[error("field {0} is not settable by {1}")]
    FieldNotAllowed(String, String),
    #[error("field {0} can only be set when creating")]
    CreateOnly(String),
    #[error("admin ops cannot be sent by clients")]
    AdminOnly,
    #[error("invalid payload: {0}")]
    BadPayload(String),
}

impl OpError {
    /// API error code (API.md §1).
    pub fn code(&self) -> &'static str {
        match self {
            OpError::PayloadTooLarge => "too_large",
            OpError::AdminOnly => "forbidden",
            _ => "bad_request",
        }
    }
}

/// Outcome of envelope validation.
#[derive(Debug, PartialEq)]
pub enum Known {
    /// A kind this core understands, at a version it can project.
    Yes(&'static KindSpec),
    /// Unknown kind or newer payload version: store and forward, don't project (DATA_MODEL.md §2).
    Opaque,
}

/// A payload the server admin erased (`{"purged": true}` and nothing else; DATA_MODEL.md §2).
pub fn is_purged(payload: &serde_json::Value) -> bool {
    payload.as_object().is_some_and(|m| m.len() == 1 && m.get("purged") == Some(&serde_json::Value::Bool(true)))
}

/// Structural validation every op passes before permission checks.
pub fn validate(op: &Op) -> Result<Known, OpError> {
    if !is_valid_id(&op.id) {
        return Err(OpError::BadId(op.id.clone()));
    }
    let scope = Scope::parse(&op.scope).ok_or_else(|| OpError::BadScope(op.scope.clone()))?;
    if let Some(e) = &op.entity_id
        && !is_valid_id(e)
    {
        return Err(OpError::BadId(e.clone()));
    }
    if let Some(m) = &op.member_id
        && !is_valid_id(m)
    {
        return Err(OpError::BadId(m.clone()));
    }
    if !(op.payload.is_object() || op.payload.is_null()) {
        return Err(OpError::PayloadNotObject);
    }
    if serde_json::to_vec(&op.payload).map(|v| v.len()).unwrap_or(usize::MAX) > MAX_PAYLOAD_BYTES {
        return Err(OpError::PayloadTooLarge);
    }
    if op.kind.starts_with("admin.") {
        return Err(OpError::AdminOnly);
    }
    // erased by the server admin (`chorus-server purge`, D-053): kept for its id, never projected
    if is_purged(&op.payload) {
        return Ok(Known::Opaque);
    }
    let Some(spec) = spec(&op.kind) else { return Ok(Known::Opaque) };
    if op.v > CURRENT_V {
        return Ok(Known::Opaque);
    }
    let scope_ok = matches!(
        (spec.scope, &scope),
        (ScopeKind::Server, Scope::Server)
            | (ScopeKind::Account, Scope::Account(_))
            | (ScopeKind::Space, Scope::Space(_))
    );
    if !scope_ok {
        return Err(OpError::WrongScope { kind: op.kind.clone(), scope: op.scope.clone() });
    }
    // Every op names the object it touches, except a few whose key lives in the payload.
    let entity_in_payload = matches!(spec.action, Action::SetAdd | Action::SetRemove | Action::Special)
        || matches!(op.kind.as_str(), "front.retract" | "front.unretract" | "front.amend");
    if op.entity_id.is_none() && !entity_in_payload {
        return Err(OpError::MissingEntity);
    }
    if matches!(spec.action, Action::Create | Action::Set) {
        let obj = op.payload_obj().ok_or(OpError::PayloadNotObject)?;
        for key in obj.keys() {
            if !spec.fields.contains(&key.as_str()) {
                return Err(OpError::FieldNotAllowed(key.clone(), op.kind.clone()));
            }
            if spec.action == Action::Set && spec.create_only.contains(&key.as_str()) {
                return Err(OpError::CreateOnly(key.clone()));
            }
        }
        if matches!(op.kind.as_str(), "emoji.create" | "emoji.set")
            && (op.kind == "emoji.create" || obj.contains_key("name"))
            && !obj.get("name").and_then(Value::as_str).is_some_and(crate::emoji::valid_name)
        {
            return Err(OpError::BadPayload("emoji name must be 2–32 lowercase letters, digits or underscores".into()));
        }
    }
    payload_shape(op)?;
    Ok(Known::Yes(spec))
}

/// Value rules for payload keys whose projection can only store certain values (DATA_MODEL.md
/// §2, "Payload rules"). Found by `chorus-server/tests/ingest_fuzz.rs`: an op that passes here must
/// project on every client and on the server.
fn payload_shape(op: &Op) -> Result<(), OpError> {
    let p = &op.payload;
    let bad = |msg: &str| Err(OpError::BadPayload(msg.into()));
    let one_of = |key: &str, allowed: &[&str], required: bool| -> Result<(), OpError> {
        match p.get(key) {
            None | Some(Value::Null) if !required => Ok(()),
            Some(Value::String(v)) if allowed.contains(&v.as_str()) => Ok(()),
            _ => Err(OpError::BadPayload(format!("{key} must be one of {}", allowed.join(", ")))),
        }
    };
    let nonempty = |key: &str| -> Result<(), OpError> {
        match p.get(key) {
            Some(Value::String(v)) if !v.is_empty() => Ok(()),
            _ => Err(OpError::BadPayload(format!("{key} is required"))),
        }
    };
    // present means of the right type: `null` is not "leave it out" for these
    let typed = |key: &str, ok: fn(&Value) -> bool, what: &str| -> Result<(), OpError> {
        match p.get(key) {
            None => Ok(()),
            Some(v) if ok(v) => Ok(()),
            _ => Err(OpError::BadPayload(format!("{key} must be {what}"))),
        }
    };
    let strings = |v: &Value| v.as_array().is_some_and(|a| a.iter().all(Value::is_string));
    if let Some(spec) = spec(&op.kind)
        && matches!(spec.action, Action::Create | Action::Set | Action::Append | Action::Revise)
        && let Some(obj) = p.as_object()
    {
        for (table, field, rules) in FIELD_RULES {
            if *table != spec.table {
                continue;
            }
            if let Some(v) = obj.get(*field)
                && let Some(why) = broken(v, rules)
            {
                return Err(OpError::BadPayload(format!("{field} must be {why}")));
            }
        }
    }
    match op.kind.as_str() {
        "message.send" | "message.forward" | "message.edit" | "post.create" | "post.edit" => {
            typed("text", Value::is_string, "text")?;
            typed("entities", Value::is_array, "a list")?;
            typed("tags", strings, "a list of tags")?;
            ranges(p)?;
        }
        "field.define" | "field.set_def" => one_of("type", FIELD_TYPES, false)?,
        "space.set_role" => {
            nonempty("account_id")?;
            nonempty("role")?;
        }
        "space.set_roles" => {
            let role_ok = |r: &Value| {
                r.get("id").and_then(Value::as_str).is_some_and(|id| !id.is_empty())
                    && r.get("perms").is_none_or(|x| {
                        x.as_array()
                            .is_some_and(|a| a.iter().all(|x| x.as_str().is_some_and(|x| PERMISSIONS.contains(&x))))
                    })
            };
            if !p.get("roles").and_then(Value::as_array).is_some_and(|a| a.iter().all(role_ok)) {
                return bad("roles must be a list of {id, name, perms}");
            }
        }
        "channel.create" | "channel.set" => one_of("kind", &["text", "thread", "member_dm"], false)?,
        "channel.set_permission" => {
            if op.entity_id.is_none() {
                return Err(OpError::MissingEntity);
            }
            one_of("target_type", &["role", "account"], true)?;
            nonempty("target_id")?;
            for key in ["allow", "deny"] {
                match p.get(key) {
                    None | Some(Value::Null) => {}
                    Some(Value::Array(a)) if a.iter().all(|x| x.as_str().is_some_and(|x| PERMISSIONS.contains(&x))) => {
                    }
                    _ => return bad(&format!("{key} must be a list of {}", PERMISSIONS.join(", "))),
                }
            }
        }
        "reaction.add" | "reaction.remove" => {
            one_of("target_type", &["message", "post"], true)?;
            nonempty("target_id")?;
            nonempty("emoji")?;
        }
        _ => {}
    }
    Ok(())
}

/// What a payload field may hold, from the column it is stored in.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rule {
    /// never `null` (the column is NOT NULL)
    NotNull,
    /// a JSON object or list (the column holds JSON)
    Json,
    /// `true` or `false`
    Bool,
    /// one of these strings
    OneOf(&'static [&'static str]),
}

/// Payload fields whose column constrains them (DATA_MODEL.md §4), per table. Every op that
/// writes the table obeys them, so what the server accepts it can also store and rebuild.
/// `chorus-server/tests/ingest_fuzz.rs` checks this list against the SQL schema.
pub const FIELD_RULES: &[(&str, &str, &[Rule])] = {
    use Rule::*;
    &[
        ("account", "settings", &[Json, NotNull]),
        ("attachment", "is_spoiler", &[Bool, NotNull]),
        ("bucket", "ceiling", &[Json, NotNull]),
        ("channel", "kind", &[OneOf(&["text", "thread", "member_dm"])]),
        ("channel", "member_ids", &[Json]),
        ("channel", "settings", &[Json, NotNull]),
        ("custom_emoji", "aliases", &[Json, NotNull]),
        ("custom_emoji", "is_animated", &[Bool, NotNull]),
        ("draft", "entities", &[Json, NotNull]),
        ("feed", "visibility", &[Json, NotNull]),
        ("field_def", "type", &[OneOf(FIELD_TYPES)]),
        ("field_def", "options", &[Json, NotNull]),
        ("field_def", "default_visibility", &[Json, NotNull]),
        ("member", "sigils", &[Json, NotNull]),
        ("member", "proxy_tags", &[Json, NotNull]),
        ("member", "is_self", &[Bool, NotNull]),
        ("member", "is_locked", &[Bool, NotNull]),
        ("member", "visibility", &[Json, NotNull]),
        ("member", "field_visibility", &[Json, NotNull]),
        ("member", "notify_policy", &[Json, NotNull]),
        ("member_group", "kind", &[OneOf(&["subsystem", "group"])]),
        ("member_group", "can_front", &[Bool, NotNull]),
        ("member_group", "visibility", &[Json, NotNull]),
        ("member_list", "visibility", &[Json, NotNull]),
        ("message", "sent_offline", &[Bool, NotNull]),
        ("message", "kind", &[NotNull]),
        ("message", "text", &[NotNull]),
        ("message", "entities", &[Json, NotNull]),
        ("post", "text", &[NotNull]),
        ("post", "entities", &[Json, NotNull]),
        ("post", "tags", &[Json, NotNull]),
        ("post", "visibility", &[Json, NotNull]),
        ("post", "sent_offline", &[Bool, NotNull]),
        ("relationship", "visibility", &[Json, NotNull]),
        ("relationship_type", "is_symmetric", &[Bool, NotNull]),
        ("space", "kind", &[OneOf(&["internal", "shared", "dm"])]),
        ("space", "settings", &[Json, NotNull]),
        ("space", "roles", &[Json, NotNull]),
        ("system", "terminology", &[Json, NotNull]),
    ]
};

/// Why `v` breaks `rules`, if it does.
fn broken(v: &Value, rules: &[Rule]) -> Option<String> {
    for r in rules {
        let ok = match r {
            Rule::NotNull => !v.is_null(),
            Rule::Json => v.is_null() || v.is_object() || v.is_array(),
            Rule::Bool => v.is_null() || v.is_boolean(),
            Rule::OneOf(allowed) => v.is_null() || v.as_str().is_some_and(|s| allowed.contains(&s)),
        };
        if !ok {
            return Some(match r {
                Rule::NotNull => "set (not null)".into(),
                Rule::Json => "an object or a list".into(),
                Rule::Bool => "true or false".into(),
                Rule::OneOf(allowed) => format!("one of {}", allowed.join(", ")),
            });
        }
    }
    None
}

/// Custom field types (DATA_MODEL `field_def.type`).
pub const FIELD_TYPES: &[&str] = &[
    "text",
    "long_text",
    "number",
    "date",
    "select",
    "multi_select",
    "boolean",
    "color",
    "url",
    "member_ref",
    "rating",
];

/// Entities and segments are `{offset, length}` ranges in UTF-16 units that stay inside the text
/// (when the op carries it), so every renderer can slice with them. Entities are objects with a
/// `type` (unknown types from newer clients pass); segments name their `authors`.
fn ranges(p: &Value) -> Result<(), OpError> {
    let len = p.get("text").and_then(Value::as_str).map(crate::text::utf16_len);
    let range = |x: &Value, what: &str| -> Result<(), OpError> {
        let n = |k: &str| x.get(k).and_then(Value::as_u64).filter(|v| *v <= u32::MAX as u64);
        let (Some(offset), Some(length)) = (n("offset"), n("length")) else {
            return Err(OpError::BadPayload(format!("each {what} needs a whole-number offset and length")));
        };
        if len.is_some_and(|len| offset + length > len as u64) {
            return Err(OpError::BadPayload(format!("a {what} runs past the end of the text")));
        }
        Ok(())
    };
    for e in p.get("entities").and_then(Value::as_array).into_iter().flatten() {
        if !e.get("type").is_some_and(Value::is_string) {
            return Err(OpError::BadPayload("each entity needs a type".into()));
        }
        range(e, "entity")?;
    }
    match p.get("segments") {
        None | Some(Value::Null) => {}
        Some(Value::Array(segs)) => {
            for g in segs {
                if !g.get("authors").and_then(Value::as_array).is_some_and(|a| a.iter().all(Value::is_string)) {
                    return Err(OpError::BadPayload("each segment needs a list of authors".into()));
                }
                range(g, "segment")?;
            }
        }
        _ => return Err(OpError::BadPayload("segments must be a list".into())),
    }
    Ok(())
}

/// Channel permissions (SPEC §5.1, D-047).
pub const PERMISSIONS: &[&str] = &["view", "send", "react", "thread", "pin", "manage"];

#[cfg(test)]
mod tests {
    use super::*;
    use crate::id::new_id;
    use serde_json::json;

    pub(crate) fn op(kind: &str, scope: &str, payload: Value) -> Op {
        Op {
            id: new_id(1_790_000_000_000, [1; 10]),
            kind: kind.into(),
            v: 1,
            scope: scope.into(),
            entity_id: Some(new_id(1_790_000_000_000, [2; 10])),
            hlc: Hlc::new(1, 0, 1),
            device_at: 1_790_000_000_000,
            tz_offset_min: 0,
            mono: None,
            boot_id: None,
            time_source: TimeSource::Auto,
            seen_seq: 0,
            member_id: None,
            payload,
            seq: None,
            account_id: None,
            device_id: None,
            occurred_at: None,
            received_at: None,
        }
    }

    fn acct() -> String {
        format!("account:{}", new_id(1, [3; 10]))
    }

    #[test]
    fn catalogue_kinds_are_unique_and_create_only_is_subset() {
        let mut kinds: Vec<_> = CATALOGUE.iter().map(|k| k.kind).collect();
        kinds.sort();
        let n = kinds.len();
        kinds.dedup();
        assert_eq!(n, kinds.len());
        for k in CATALOGUE {
            for c in k.create_only {
                assert!(k.fields.contains(c), "{} create_only {c} not in fields", k.kind);
            }
        }
    }

    #[test]
    fn member_set_accepts_known_fields_only() {
        let ok = op("member.set", &acct(), json!({"name": "Kai", "color": "#aa5500"}));
        assert!(matches!(validate(&ok), Ok(Known::Yes(_))));
        let bad = op("member.set", &acct(), json!({"account_id": "x"}));
        assert_eq!(validate(&bad), Err(OpError::FieldNotAllowed("account_id".into(), "member.set".into())));
        let co = op("member.set", &acct(), json!({"is_self": true}));
        assert_eq!(validate(&co), Err(OpError::CreateOnly("is_self".into())));
    }

    #[test]
    fn scope_must_match_kind() {
        let o = op("message.send", &acct(), json!({}));
        assert!(matches!(validate(&o), Err(OpError::WrongScope { .. })));
        let o = op("emoji.create", "server", json!({"name": "kai_wave"}));
        assert!(matches!(validate(&o), Ok(Known::Yes(_))));
    }

    #[test]
    fn text_ranges_stay_inside_the_text() {
        let space = format!("space:{}", new_id(1, [4; 10]));
        let send = |payload: Value| validate(&op("message.send", &space, payload));
        let ok = send(json!({"text": "hé 🌌", "entities": [{"type": "bold", "offset": 3, "length": 2}],
            "segments": [{"offset": 0, "length": 5, "authors": ["a"]}]}));
        assert!(matches!(ok, Ok(Known::Yes(_))), "{ok:?}");
        for bad in [
            json!({"text": "hi", "entities": [{"type": "bold", "offset": 1, "length": 2}]}),
            json!({"text": "hi", "entities": [null]}),
            json!({"text": "hi", "entities": [{"offset": 0, "length": 1}]}),
            json!({"text": "hi", "entities": [{"type": "bold", "offset": -1, "length": 1}]}),
            json!({"text": "hi", "entities": [{"type": "bold", "offset": "0", "length": 1}]}),
            json!({"text": "hi", "segments": [{"offset": 0, "length": 3, "authors": ["a"]}]}),
            json!({"text": "hi", "segments": [{"offset": 0, "length": 2}]}),
            json!({"text": "hi", "segments": {}}),
        ] {
            assert!(matches!(send(bad.clone()), Err(OpError::BadPayload(_))), "{bad}");
        }
        // an edit without its text can't be bounds-checked, only shape-checked
        let edit = op("message.edit", &space, json!({"entities": [{"type": "x_new_kind", "offset": 9, "length": 1}]}));
        assert!(matches!(validate(&edit), Ok(Known::Yes(_))));
    }

    #[test]
    fn emoji_name_shape_is_checked_by_the_core() {
        assert!(matches!(validate(&op("emoji.create", "server", json!({"name":"ok_2"}))), Ok(Known::Yes(_))));
        for payload in [json!({}), json!({"name":"Nope"}), json!({"name":"x"})] {
            assert!(matches!(validate(&op("emoji.create", "server", payload)), Err(OpError::BadPayload(_))));
        }
    }

    #[test]
    fn unknown_and_newer_are_opaque() {
        assert_eq!(validate(&op("future.thing", &acct(), json!({}))), Ok(Known::Opaque));
        let mut o = op("member.set", &acct(), json!({"whatever": 1}));
        o.v = 2;
        assert_eq!(validate(&o), Ok(Known::Opaque));
    }

    #[test]
    fn admin_ops_rejected_and_bad_ids() {
        assert_eq!(validate(&op("admin.purge", &acct(), json!({}))), Err(OpError::AdminOnly));
        let mut o = op("member.set", &acct(), json!({}));
        o.id = "nope".into();
        assert!(matches!(validate(&o), Err(OpError::BadId(_))));
        assert!(matches!(validate(&op("member.set", "account:nope", json!({}))), Err(OpError::BadScope(_))));
    }

    #[test]
    fn envelope_json_roundtrip_skips_server_fields() {
        let o = op("member.set", &acct(), json!({"name": "Kai"}));
        let j = serde_json::to_value(&o).unwrap();
        assert!(j.get("seq").is_none());
        assert_eq!(serde_json::from_value::<Op>(j).unwrap(), o);
    }
}
