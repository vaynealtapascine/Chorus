//! Handover (D-132, Figma F5.15, F0.1): notes from one front to whoever fronts next.
//!
//! A handover is a `message.send` in the system's `handover` channel carrying
//! `handover: {deliver: "now" | "next_switch", checklist?: [{id, text}]}`. Members take it with
//! `handover.ack` (LWW per message and member) and tick its checklist with `checklist.set` (LWW per
//! message and item); both live in the internal space, and the projection keeps them as
//! `handover_ack` and `checklist_item_state` rows. This module answers, from those rows and the
//! Body front's folded history, when each handover was delivered, which are still unread, how the
//! checklist stands, and when a member last fronted. Pure: no IO, no clocks.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Layers whose members count for "the front changed": fronting and co-conscious (the legacy
/// levels, and the default layers of a configured Body, D-080).
pub const COUNTED_LAYERS: &[&str] = &["front", "cocon"];
/// Checklist limits, the poll option ones (`poll::MAX_OPTION_ID`, `MAX_OPTION_TEXT`).
pub const MAX_ITEMS: usize = 30;
/// The one message [`definition`] reports (one string keeps the wasm small, SPEC §9).
pub const RULE: &str = "handover must be {deliver: now or next_switch, checklist?: 1-30 items {id: 1-16 characters, text: 1-200}, unique ids}";

/// One interval of the Body front's folded history (`front_history`/`front_effective` intervals,
/// or the legacy timeline's with its level as the layer). Retracted switches are already gone from
/// it, and a back-dated one sits where it was dated.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct Interval {
    pub subject_id: String,
    #[serde(default)]
    pub layer: String,
    pub start_at: i64,
    #[serde(default)]
    pub end_at: Option<i64>,
    /// The named front it belongs to, when the caller mixes fronts: only Body's count.
    #[serde(default)]
    pub front: Option<String>,
}

/// A checklist item as written in the handover.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Item {
    pub id: String,
    pub text: String,
}

/// A `handover` field as fixed at creation.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Def {
    pub next_switch: bool,
    pub checklist: Vec<Item>,
}

/// The `handover` field of a new `message.send`, or `None` if it breaks [`RULE`].
pub fn definition(v: &Value) -> Option<Def> {
    let o = v.as_object()?;
    let next_switch = match o.get("deliver")?.as_str()? {
        "now" => false,
        "next_switch" => true,
        _ => return None,
    };
    let mut checklist: Vec<Item> = Vec::new();
    match o.get("checklist") {
        None | Some(Value::Null) => {}
        Some(Value::Array(list)) => {
            if list.is_empty() || list.len() > MAX_ITEMS {
                return None;
            }
            for item in list {
                let id = item.get("id")?.as_str()?;
                let text = item.get("text")?.as_str()?;
                let fits = |s: &str, max: usize| !s.trim().is_empty() && s.chars().count() <= max;
                if !fits(id, crate::poll::MAX_OPTION_ID)
                    || !fits(text, crate::poll::MAX_OPTION_TEXT)
                    || checklist.iter().any(|i| i.id == id)
                {
                    return None;
                }
                checklist.push(Item { id: id.into(), text: text.into() });
            }
        }
        Some(_) => return None,
    }
    Some(Def { next_switch, checklist })
}

/// Who fronts at `t`: the counted (subject, layer) pairs whose interval covers it.
fn state_at(intervals: &[&Interval], t: i64) -> BTreeSet<(String, String)> {
    crate::sort::set(
        intervals
            .iter()
            .filter(|i| i.start_at <= t && i.end_at.is_none_or(|e| t < e))
            .map(|i| (i.subject_id.clone(), i.layer.clone())),
    )
}

fn counted(intervals: &[Interval]) -> Vec<&Interval> {
    intervals.iter().filter(|i| COUNTED_LAYERS.contains(&i.layer.as_str())).collect()
}

/// When a handover reaches whoever fronts next. `deliver` other than `next_switch` (`now`, or a
/// value from a newer client) is delivered when sent. `next_switch` waits for the first moment
/// after `sent_at` at which the Body front's fronting and co-conscious members (who, and on which
/// of the two layers) differ from those at `sent_at`; `None` = still waiting.
pub fn delivered_at(sent_at: i64, deliver: &str, intervals: &[Interval]) -> Option<i64> {
    if deliver != "next_switch" {
        return Some(sent_at);
    }
    let body = counted(intervals);
    let before = state_at(&body, sent_at);
    let times =
        crate::sort::set(body.iter().flat_map(|i| [Some(i.start_at), i.end_at]).flatten().filter(|t| *t > sent_at));
    times.into_iter().find(|t| state_at(&body, *t) != before)
}

/// When `member` last stopped fronting (front or co-con, back-to-back intervals as one stretch),
/// not counting a stretch still open: "Since Foxglove last fronted". `None` if never.
pub fn last_fronted(member: &str, intervals: &[Interval]) -> Option<i64> {
    let mut mine: Vec<(i64, Option<i64>)> =
        counted(intervals).into_iter().filter(|i| i.subject_id == member).map(|i| (i.start_at, i.end_at)).collect();
    crate::sort::by(&mut mine, |a, b| a.0.cmp(&b.0));
    // merge overlapping or touching intervals into stretches
    let mut stretches: Vec<(i64, Option<i64>)> = Vec::new();
    for (start, end) in mine {
        match stretches.last_mut() {
            Some(last) if last.1.is_none_or(|e| start <= e) => {
                last.1 = match (last.1, end) {
                    (Some(a), Some(b)) => Some(a.max(b)),
                    _ => None,
                };
            }
            _ => stretches.push((start, end)),
        }
    }
    stretches.iter().filter_map(|s| s.1).max()
}

/// A member's tick on an item, as projected (`checklist_item_state`).
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct Check {
    #[serde(default)]
    pub message_id: String,
    pub item_id: String,
    #[serde(default)]
    pub done: bool,
    #[serde(default)]
    pub member_id: Option<String>,
}

/// An item and how it stands.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ItemState {
    pub id: String,
    pub text: String,
    pub done: bool,
    /// Who ticked (or unticked) it last.
    pub by: Option<String>,
}

/// The checklist in its written order with each item's state; states for unknown items are
/// ignored, and a later state for the same item wins.
pub fn checklist(def: &[Item], states: &[Check]) -> Vec<ItemState> {
    def.iter()
        .map(|item| {
            let s = states.iter().rev().find(|s| s.item_id == item.id);
            ItemState {
                id: item.id.clone(),
                text: item.text.clone(),
                done: s.is_some_and(|s| s.done),
                by: s.and_then(|s| s.member_id.clone()),
            }
        })
        .collect()
}

/// A member having taken a handover (`handover_ack`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Ack {
    #[serde(default, skip_serializing)]
    pub message_id: String,
    pub member_id: String,
    pub acked_at: i64,
}

/// A handover message, as the clients read it from the projection.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct Handover {
    pub id: String,
    pub sent_at: i64,
    #[serde(default)]
    pub deliver: String,
    #[serde(default)]
    pub authors: Vec<String>,
    #[serde(default)]
    pub checklist: Vec<Item>,
}

/// Whether someone other than its authors took it: one ack closes it for the whole system
/// ("Foxglove acknowledged the handover").
pub fn acked(h: &Handover, acks: &[Ack]) -> bool {
    acks.iter().any(|a| a.message_id == h.id && !h.authors.contains(&a.member_id))
}

/// The handovers delivered and taken by nobody outside their authors, by delivery time then id.
pub fn unread(handovers: &[Handover], acks: &[Ack], intervals: &[Interval]) -> Vec<String> {
    let mut out: Vec<(i64, String)> = handovers
        .iter()
        .filter(|h| !acked(h, acks))
        .filter_map(|h| delivered_at(h.sent_at, &h.deliver, intervals).map(|t| (t, h.id.clone())))
        .collect();
    crate::sort::ord(&mut out);
    out.into_iter().map(|(_, id)| id).collect()
}

/// What a client asks in one call (`api::handover_view`).
#[derive(Clone, Debug, Default, Deserialize)]
pub struct ViewIn {
    /// The Body front's id: intervals naming another front are left out.
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub intervals: Vec<Interval>,
    #[serde(default)]
    pub handovers: Vec<Handover>,
    #[serde(default)]
    pub acks: Vec<Ack>,
    #[serde(default)]
    pub checks: Vec<Check>,
    /// For [`last_fronted`]: usually the member speaking now.
    #[serde(default)]
    pub member: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct HandoverOut {
    pub id: String,
    pub delivered_at: Option<i64>,
    pub acks: Vec<Ack>,
    pub acked: bool,
    pub checklist: Vec<ItemState>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ViewOut {
    pub handovers: Vec<HandoverOut>,
    pub unread: Vec<String>,
    pub last_fronted: Option<i64>,
}

/// Everything the Handover chat and the Home card show, in one pass.
pub fn view(input: &ViewIn) -> ViewOut {
    let intervals: Vec<Interval> = input
        .intervals
        .iter()
        .filter(|i| match (&input.body, &i.front) {
            (Some(body), Some(front)) => body == front,
            _ => true,
        })
        .cloned()
        .collect();
    let handovers = input
        .handovers
        .iter()
        .map(|h| {
            let mut acks: Vec<Ack> = input.acks.iter().filter(|a| a.message_id == h.id).cloned().collect();
            crate::sort::by(&mut acks, |a, b| (a.acked_at, &a.member_id).cmp(&(b.acked_at, &b.member_id)));
            let states: Vec<Check> = input.checks.iter().filter(|c| c.message_id == h.id).cloned().collect();
            HandoverOut {
                id: h.id.clone(),
                delivered_at: delivered_at(h.sent_at, &h.deliver, &intervals),
                acked: acked(h, &input.acks),
                acks,
                checklist: checklist(&h.checklist, &states),
            }
        })
        .collect();
    ViewOut {
        handovers,
        unread: unread(&input.handovers, &input.acks, &intervals),
        last_fronted: input.member.as_deref().and_then(|m| last_fronted(m, &intervals)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn iv(who: &str, layer: &str, start: i64, end: Option<i64>) -> Interval {
        Interval { subject_id: who.into(), layer: layer.into(), start_at: start, end_at: end, front: None }
    }

    #[test]
    fn next_switch_waits_for_a_different_front() {
        let h = [iv("kai", "front", 0, Some(100)), iv("rose", "front", 100, None)];
        assert_eq!(delivered_at(50, "now", &h), Some(50));
        assert_eq!(delivered_at(50, "next_switch", &h), Some(100));
        assert_eq!(delivered_at(150, "next_switch", &h), None);
        // the same members moving between layers is a change; one only present is not
        let swap = [iv("kai", "front", 0, Some(100)), iv("kai", "cocon", 100, None), iv("x", "present", 120, None)];
        assert_eq!(delivered_at(50, "next_switch", &swap), Some(100));
        assert_eq!(delivered_at(110, "next_switch", &swap), None);
    }

    #[test]
    fn last_fronted_skips_the_open_stretch_and_joins_touching_ones() {
        let h = [
            iv("fox", "front", 0, Some(10)),
            iv("fox", "front", 20, Some(30)),
            iv("fox", "cocon", 30, Some(40)),
            iv("fox", "front", 50, None),
        ];
        assert_eq!(last_fronted("fox", &h), Some(40));
        assert_eq!(last_fronted("kai", &h), None);
    }

    #[test]
    fn definitions_follow_the_rule() {
        assert!(definition(&json!({"deliver": "now"})).is_some());
        let def = definition(&json!({"deliver": "next_switch", "checklist": [{"id": "a", "text": "Keys"}]}));
        assert_eq!(def.map(|d| (d.next_switch, d.checklist.len())), Some((true, 1)));
        for bad in [
            json!({}),
            json!({"deliver": "later"}),
            json!({"deliver": "now", "checklist": []}),
            json!({"deliver": "now", "checklist": [{"id": "a", "text": "x"}, {"id": "a", "text": "y"}]}),
            json!({"deliver": "now", "checklist": [{"id": "a", "text": " "}]}),
            json!({"deliver": "now", "checklist": "a"}),
        ] {
            assert!(definition(&bad).is_none(), "{bad}");
        }
    }
}
