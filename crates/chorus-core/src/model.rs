//! Reference projection: state as a pure function of a set of ops (SYNC.md §1.3).
//!
//! This is the executable definition of what the server's SQL projection and the clients' local
//! projections must produce. It is not fast; it is obvious. Tests project shuffled op sets and
//! compare, and the simulator compares every replica against it.

use std::collections::BTreeMap;

use serde::Serialize;
use serde_json::{Map, Value, json};

use crate::front::{self, FrontOp};
use crate::hlc::Hlc;
use crate::lww::{Clocks, SetElem};
use crate::op::{self, Action, Known, Op};

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct Row {
    /// A create/append op for this row has been seen.
    pub exists: bool,
    pub fields: Map<String, Value>,
    #[serde(skip)]
    pub clocks: Clocks,
    /// Number of revise ops (edits) applied, for `revision_count`.
    #[serde(skip_serializing_if = "is_zero")]
    pub edits: u32,
}

fn is_zero(n: &u32) -> bool {
    *n == 0
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct Projection {
    /// table → id → row
    pub rows: BTreeMap<String, BTreeMap<String, Row>>,
    /// table → element key → present
    pub sets: BTreeMap<String, BTreeMap<String, bool>>,
    /// account id → folded front
    pub fronts: BTreeMap<String, front::FoldResult>,
    /// account id → concurrent-switch review cards (SYNC.md §5.7); resolutions are in
    /// `rows.front_review`.
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub reviews: BTreeMap<String, Vec<front::Review>>,
    /// ops that weren't projected (unknown kind / newer version / bad payload)
    pub opaque: usize,
    /// LWW element-set clocks while projecting; cleared at the end.
    #[serde(skip)]
    set_clocks: BTreeMap<(String, String), SetElem>,
}

impl Projection {
    pub fn row(&self, table: &str, id: &str) -> Option<&Row> {
        self.rows.get(table)?.get(id)
    }

    /// Canonical JSON for comparisons.
    pub fn canonical(&self) -> Value {
        serde_json::to_value(self).unwrap_or(Value::Null)
    }
}

/// Keep the best copy of each op: the one the server has stamped (seq) wins over a local copy.
pub fn dedupe<'a>(ops: impl IntoIterator<Item = &'a Op>) -> Vec<&'a Op> {
    let mut by_id: BTreeMap<&str, &Op> = BTreeMap::new();
    for o in ops {
        by_id
            .entry(o.id.as_str())
            .and_modify(|cur| {
                if cur.seq.is_none() && o.seq.is_some() {
                    *cur = o;
                }
            })
            .or_insert(o);
    }
    let mut v: Vec<&Op> = by_id.into_values().collect();
    v.sort_by(|a, b| (a.hlc, &a.id).cmp(&(b.hlc, &b.id)));
    v
}

/// A read mark: (message time, message id, op clock).
type ReadMark = (i64, String, Hlc);
/// Per read-state key: all `read.mark`s, and the latest `read.set`.
type Reads = BTreeMap<String, (Vec<ReadMark>, Option<ReadMark>)>;

fn scope_account(op: &Op) -> Option<&str> {
    op.scope.strip_prefix("account:")
}

fn set_fields<'a>(p: &'a mut Projection, table: &str, id: &str, hlc: Hlc, fields: &Map<String, Value>) -> &'a mut Row {
    let row = p.rows.entry(table.to_string()).or_default().entry(id.to_string()).or_default();
    let won = crate::lww::apply_fields(&mut row.clocks, hlc, fields);
    for (k, v) in won {
        row.fields.insert(k, v);
    }
    row
}

fn one(k: &str, v: Value) -> Map<String, Value> {
    let mut m = Map::new();
    m.insert(k.to_string(), v);
    m
}

fn str_field<'a>(o: &'a Op, k: &str) -> &'a str {
    o.payload.get(k).and_then(Value::as_str).unwrap_or("")
}

/// Project a set of ops. Duplicates (same id) are collapsed first.
pub fn project<'a>(ops: impl IntoIterator<Item = &'a Op>) -> Projection {
    let ops = dedupe(ops);
    let mut p = Projection::default();
    let mut front_ops: BTreeMap<String, Vec<FrontOp>> = BTreeMap::new();
    // read.mark maxima and read.set, resolved after the loop
    let mut reads: Reads = BTreeMap::new();

    for o in ops {
        let Ok(Known::Yes(spec)) = op::validate(o) else {
            p.opaque += 1;
            continue;
        };
        let entity = o.entity().unwrap_or("");
        let payload = o.payload_obj().cloned().unwrap_or_default();
        let trash_item = matches!(spec.table, "message" | "post" | "member" | "member_group" | "channel");
        match spec.action {
            Action::Create | Action::Append => {
                let mut fields = payload.clone();
                if trash_item {
                    fields.insert("created_by_account_id".into(), json!(o.account_id));
                }
                if spec.action == Action::Append {
                    fields.insert("occurred_at".into(), json!(o.time()));
                    fields.insert("account_id".into(), json!(o.account_id));
                }
                let row = set_fields(&mut p, spec.table, entity, o.hlc, &fields);
                row.exists = true;
            }
            Action::Set => {
                set_fields(&mut p, spec.table, entity, o.hlc, &payload);
            }
            Action::Revise => {
                let mut fields = payload.clone();
                fields.remove("message_id");
                fields.remove("post_id");
                fields.insert("edited_at".into(), json!(o.time()));
                let row = set_fields(&mut p, spec.table, entity, o.hlc, &fields);
                row.edits += 1;
            }
            Action::Delete => {
                let mut fields = one("deleted_at", json!(o.time()));
                if trash_item {
                    fields.insert("deleted_by_account_id".into(), json!(o.account_id));
                }
                set_fields(&mut p, spec.table, entity, o.hlc, &fields);
            }
            Action::Restore => {
                let mut fields = one("deleted_at", Value::Null);
                if trash_item {
                    fields.insert("deleted_by_account_id".into(), Value::Null);
                }
                set_fields(&mut p, spec.table, entity, o.hlc, &fields);
            }
            Action::Archive => {
                set_fields(&mut p, spec.table, entity, o.hlc, &one("archived_at", json!(o.time())));
            }
            Action::Unarchive => {
                set_fields(&mut p, spec.table, entity, o.hlc, &one("archived_at", Value::Null));
            }
            Action::SetAdd | Action::SetRemove => {
                // element key: entity + canonical payload (serde_json maps are sorted)
                let key = format!("{entity}|{}", Value::Object(payload.clone()));
                let elem = p.set_clocks.entry((spec.table.to_string(), key.clone())).or_default();
                if spec.action == Action::SetAdd {
                    elem.add(o.hlc);
                } else {
                    elem.remove(o.hlc);
                }
                let present = elem.is_present();
                p.sets.entry(spec.table.to_string()).or_default().insert(key, present);
            }
            Action::Front => {
                if let (Some(acct), Ok(f)) = (scope_account(o), FrontOp::from_op(o)) {
                    front_ops.entry(acct.to_string()).or_default().push(f);
                } else {
                    p.opaque += 1;
                }
            }
            Action::Special => special(&mut p, o, &payload, &mut reads),
            Action::Admin => p.opaque += 1,
        }
    }

    for (acct, ops) in front_ops {
        let folded = front::fold(&ops);
        let reviews = front::reviews(&ops, &folded, front::DEFAULT_REVIEW_WINDOW_MS);
        if !reviews.is_empty() {
            p.reviews.insert(acct.clone(), reviews);
        }
        p.fronts.insert(acct, folded);
    }
    for (key, (marks, manual)) in reads {
        let floor = manual.as_ref().map(|m| m.2);
        let best_mark =
            marks.into_iter().filter(|m| floor.is_none_or(|f| m.2 > f)).max_by(|a, b| (a.0, &a.1).cmp(&(b.0, &b.1)));
        let effective = match (best_mark, manual) {
            (Some(m), Some(s)) => {
                if (m.0, &m.1) > (s.0, &s.1) {
                    m
                } else {
                    s
                }
            }
            (Some(m), None) => m,
            (None, Some(s)) => s,
            (None, None) => continue,
        };
        let row = p.rows.entry("read_state".into()).or_default().entry(key).or_default();
        row.exists = true;
        row.fields.insert("last_read_message_id".into(), json!(effective.1));
        row.fields.insert("last_read_message_at".into(), json!(effective.0));
    }
    p.set_clocks.clear();
    p
}

fn special(p: &mut Projection, o: &Op, payload: &Map<String, Value>, reads: &mut Reads) {
    let entity = o.entity().unwrap_or("");
    let acct = o.account_id.clone().unwrap_or_default();
    match o.kind.as_str() {
        "field.set_value" => {
            let id = format!("{}|{}", str_field(o, "member_id"), str_field(o, "field_id"));
            let v = payload.get("value").cloned().unwrap_or(Value::Null);
            set_fields(p, "field_value", &id, o.hlc, &one("value", v)).exists = true;
        }
        "front.review_resolve" => {
            let id = str_field(o, "review_id").to_string();
            let v = payload.get("resolution").cloned().unwrap_or(Value::Null);
            set_fields(p, "front_review", &id, o.hlc, &one("resolution", v));
        }
        "space.set_role" => {
            let id = format!("{entity}|{}", str_field(o, "account_id"));
            let v = payload.get("role").cloned().unwrap_or(Value::Null);
            set_fields(p, "space_member", &id, o.hlc, &one("role", v));
        }
        "space.set_roles" => {
            let v = payload.get("roles").cloned().unwrap_or(Value::Null);
            set_fields(p, "space", entity, o.hlc, &one("roles", v));
        }
        "channel.set_permission" => {
            let id = format!("{entity}|{}|{}", str_field(o, "target_type"), str_field(o, "target_id"));
            let mut f = Map::new();
            f.insert("allow".into(), payload.get("allow").cloned().unwrap_or(json!([])));
            f.insert("deny".into(), payload.get("deny").cloned().unwrap_or(json!([])));
            set_fields(p, "channel_permission", &id, o.hlc, &f).exists = true;
        }
        "message.pin" | "message.unpin" => {
            let pin = o.kind == "message.pin";
            let mut f = Map::new();
            f.insert("pinned_at".into(), if pin { json!(o.time()) } else { Value::Null });
            f.insert("pinned_by_member".into(), if pin { json!(o.member_id) } else { Value::Null });
            set_fields(p, "message", entity, o.hlc, &f);
        }
        "read.mark" | "read.set" => {
            let key = format!("{}|{acct}|{}", str_field(o, "channel_id"), str_field(o, "reader_member_id"));
            let at = payload.get("message_at").and_then(Value::as_i64).unwrap_or(0);
            let v = (at, str_field(o, "message_id").to_string(), o.hlc);
            let e = reads.entry(key).or_default();
            if o.kind == "read.mark" {
                e.0.push(v);
            } else if e.1.as_ref().is_none_or(|cur| v.2 > cur.2) {
                e.1 = Some(v);
            }
        }
        "highlight.reorder" => {
            let id = format!("{}|{}", str_field(o, "profile_member_id"), str_field(o, "post_id"));
            let v = payload.get("sort_key").cloned().unwrap_or(Value::Null);
            set_fields(p, "highlight_order", &id, o.hlc, &one("sort_key", v));
        }
        "follow.request" => {
            let mut f = payload.clone();
            f.insert("follower_account_id".into(), json!(acct));
            f.insert("status".into(), json!("requested"));
            set_fields(p, "follow", entity, o.hlc, &f).exists = true;
        }
        "follow.accept" => {
            set_fields(p, "follow", entity, o.hlc, &one("status", json!("active")));
        }
        "follow.end" => {
            set_fields(p, "follow", entity, o.hlc, &one("status", json!("ended")));
        }
        "follow.set_ceiling" => {
            let v = payload.get("ceiling").cloned().unwrap_or(json!({}));
            set_fields(p, "follow", entity, o.hlc, &one("ceiling", v));
        }
        "follow.set_prefs" => {
            let v = payload.get("prefs").cloned().unwrap_or(json!({}));
            set_fields(p, "follow", entity, o.hlc, &one("prefs", v));
        }
        "pref.set" => {
            let id = format!("{acct}|{}|{}", str_field(o, "device"), str_field(o, "key"));
            let v = payload.get("value").cloned().unwrap_or(Value::Null);
            set_fields(p, "pref", &id, o.hlc, &one("value", v)).exists = true;
        }
        _ => p.opaque += 1,
    }
}
