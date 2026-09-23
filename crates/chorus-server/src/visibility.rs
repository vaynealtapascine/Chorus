//! Structured chat visibility shared by notifications and cross-account reads.

use serde_json::Value;

/// A message that every member of its space may see. NULL is the legacy/default public value.
pub const PUBLIC_MESSAGE_SQL: &str = "(m.visibility IS NULL OR json_extract(m.visibility, '$.mode') = 'all')";

pub fn is_public(value: Option<&Value>) -> bool {
    value.is_none_or(|v| v.is_null() || v.get("mode").and_then(Value::as_str) == Some("all"))
}
