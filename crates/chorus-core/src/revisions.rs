//! Edit history of messages and posts (SPEC §5.3): every version of the text, oldest first.
//!
//! The shown text is the model's LWW fold of the item's append op (`message.send`,
//! `message.forward`, `post.create`) and its revise ops (`message.edit`, `post.edit`) by HLC
//! (`model.rs`). A version is that fold after each of those ops in HLC order, so the last
//! version is always what the item shows now. The server projects these rows
//! (`message_revision`, `post_revision`) for edited items; apps compute them from their own ops.

use serde::Serialize;
use serde_json::{Map, Value};

use crate::hlc::Hlc;
use crate::op::{self, Action, Op};

/// The fields a version carries (the rest of an item doesn't change with an edit).
const TEXT_FIELDS: &[&str] = &["text", "entities", "cw", "title", "segments"];

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Revision {
    /// 0 for the oldest version.
    pub rev: u32,
    pub op_id: String,
    /// The send/create rather than an edit.
    pub original: bool,
    /// `text`, `entities`, `cw`, `title`, `segments` as they stood after this op.
    pub fields: Map<String, Value>,
    /// When it was written (the op's `occurred_at`, else its device time).
    pub at: i64,
    pub hlc: Hlc,
    pub device_id: Option<String>,
}

/// The versions of one item from its ops (any order, duplicates allowed; other ops are
/// ignored). Empty if there is no append or revise op.
pub fn revisions<'a>(ops: impl IntoIterator<Item = &'a Op>) -> Vec<Revision> {
    let mut texts: Vec<(&Op, bool)> = crate::model::dedupe(ops)
        .into_iter()
        .filter_map(|o| match op::spec(&o.kind).map(|s| s.action) {
            Some(Action::Append) => Some((o, true)),
            Some(Action::Revise) => Some((o, false)),
            _ => None,
        })
        .collect();
    texts.sort_by(|a, b| (a.0.hlc, &a.0.id).cmp(&(b.0.hlc, &b.0.id)));
    let mut state = Map::new();
    texts
        .into_iter()
        .enumerate()
        .map(|(i, (o, original))| {
            for k in TEXT_FIELDS {
                if let Some(v) = o.payload.get(*k) {
                    state.insert((*k).into(), v.clone());
                }
            }
            Revision {
                rev: i as u32,
                op_id: o.id.clone(),
                original,
                fields: state.clone(),
                at: o.time(),
                hlc: o.hlc,
                device_id: o.device_id.clone(),
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::time::TimeSource;
    use serde_json::json;

    fn item() -> String {
        crate::id::new_id(1, [4; 10])
    }

    fn op(n: u64, kind: &str, payload: Value) -> Op {
        Op {
            id: crate::id::new_id(n, [n as u8; 10]),
            kind: kind.into(),
            v: 1,
            scope: format!("space:{}", crate::id::new_id(1, [3; 10])),
            entity_id: Some(item()),
            hlc: Hlc::new(n, 0, 1),
            device_at: n as i64,
            tz_offset_min: 0,
            mono: None,
            boot_id: None,
            time_source: TimeSource::Auto,
            seen_seq: 0,
            member_id: None,
            payload,
            seq: None,
            account_id: None,
            device_id: Some("d".into()),
            occurred_at: None,
            received_at: None,
        }
    }

    #[test]
    fn versions_fold_in_clock_order_and_end_at_what_is_shown() {
        let (c, a) = (crate::id::new_id(1, [1; 10]), crate::id::new_id(1, [2; 10]));
        let send = op(
            10,
            "message.send",
            json!({"channel_id": c, "authors": [a], "text": "helo", "entities": [], "cw": "spoiler"}),
        );
        let fix = op(20, "message.edit", json!({"message_id": item(), "text": "hello", "entities": []}));
        // an edit written offline with an earlier clock than a later one still sorts by clock
        let late = op(30, "message.edit", json!({"message_id": item(), "text": "hello!", "entities": []}));
        let pin = op(25, "message.pin", json!({"message_id": item()}));
        let v = revisions([&late, &pin, &send, &fix, &fix]);
        assert_eq!(v.len(), 3, "the pin isn't a version and the duplicate counts once");
        assert_eq!(v.iter().map(|r| r.rev).collect::<Vec<_>>(), vec![0, 1, 2]);
        assert!(v[0].original && !v[1].original);
        assert_eq!(v[0].fields["text"], "helo");
        assert_eq!(v[1].fields["text"], "hello");
        assert_eq!(v[1].fields["cw"], "spoiler", "an edit without cw keeps it");
        assert_eq!(v[2].at, 30);
        let shown = crate::model::project([&send, &fix, &late]);
        let row = shown.row("message", &item()).unwrap_or_else(|| panic!("{:?}", op::validate(&send)));
        assert_eq!(row.fields["text"], v[2].fields["text"]);
    }

    #[test]
    fn post_titles_are_versioned_too() {
        let create = op(1, "post.create", json!({"kind": "entry", "title": "Day", "text": "a", "entities": []}));
        let edit = op(2, "post.edit", json!({"post_id": item(), "title": "Day one", "text": "a", "entities": []}));
        let v = revisions([&create, &edit]);
        assert_eq!((v[0].fields["title"].clone(), v[1].fields["title"].clone()), (json!("Day"), json!("Day one")));
        assert!(revisions([&op(3, "message.pin", json!({}))]).is_empty());
    }
}
