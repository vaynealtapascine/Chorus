//! Stage mode (SPEC §7, CLIENTS.md §5): which items a screenshot view shows, what collapses into
//! "context", and the view-only overrides (fake or redacted names, shifted/hidden times, D-050).
//!
//! A stage is a *view definition* over real items — nothing here writes data. It lives in core so
//! a saved stage renders the same on the web and on Android. Rendering (styles, blur, width) is
//! the client's; those options pass through in `render` untouched.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// One message or post, in display order.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Item {
    pub id: String,
    #[serde(default)]
    pub authors: Vec<String>,
    pub at: i64,
    #[serde(default)]
    pub reply_to: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Unselected {
    Hidden,
    #[default]
    Context,
    Visible,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FakeName {
    pub label: String,
    #[serde(default)]
    pub color: Option<String>,
}

/// How shown times change (D-050). Times never change on real data.
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum TimeOverride {
    #[default]
    Real,
    Hide,
    /// Move everything by `offset_ms`.
    Shift {
        offset_ms: i64,
    },
    /// The first shown item is at `start`; gaps are kept.
    Start {
        start: i64,
    },
    /// Per-item times; items not listed keep their real time.
    Manual {
        at: BTreeMap<String, i64>,
    },
}

#[derive(Clone, Debug, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Definition {
    /// Chosen items. Empty = everything is "selected" (a fresh stage shows the whole view).
    pub selected: Vec<String>,
    pub unselected: Unselected,
    /// "Only these members": items with any other author are dropped entirely.
    pub only_members: Option<Vec<String>>,
    /// Replace every author with "Member A", "Member B"… (fake names still win).
    pub redact_names: bool,
    pub fake_names: BTreeMap<String, FakeName>,
    pub time: TimeOverride,
    /// Client rendering options (style, theme, width, blur, hide_reactions…), passed through.
    pub render: Value,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Row {
    Item {
        id: String,
        /// false = shown only because unselected items are "visible".
        selected: bool,
        /// The time to show, after overrides; `None` = hidden.
        at: Option<i64>,
        /// Whether the item this replies to is on stage (else the client hides the reply bar).
        reply_shown: bool,
    },
    /// Consecutive unselected items folded into one quiet row ("3 messages").
    Context { ids: Vec<String>, count: usize },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Plan {
    pub rows: Vec<Row>,
    /// member id → the name/colour to show instead of the real one (only overridden members).
    pub names: BTreeMap<String, FakeName>,
}

fn placeholder(n: usize) -> String {
    // A…Z, then AA, AB…
    let mut s = String::new();
    let mut n = n + 1;
    while n > 0 {
        n -= 1;
        s.insert(0, (b'A' + (n % 26) as u8) as char);
        n /= 26;
    }
    format!("Member {s}")
}

/// Plan a stage over `items` (in display order).
pub fn plan(items: &[Item], def: &Definition) -> Plan {
    let chosen: BTreeSet<&str> = def.selected.iter().map(String::as_str).collect();
    let only: Option<BTreeSet<&str>> = def.only_members.as_ref().map(|v| v.iter().map(String::as_str).collect());
    let passes = |it: &Item| only.as_ref().is_none_or(|o| it.authors.iter().all(|a| o.contains(a.as_str())));

    // 1. decide each item: shown (selected?), context, or dropped
    enum Fate {
        Shown(bool),
        Context,
        Drop,
    }
    let fates: Vec<Fate> = items
        .iter()
        .map(|it| {
            if !passes(it) {
                return Fate::Drop;
            }
            if chosen.is_empty() || chosen.contains(it.id.as_str()) {
                return Fate::Shown(true);
            }
            match def.unselected {
                Unselected::Hidden => Fate::Drop,
                Unselected::Context => Fate::Context,
                Unselected::Visible => Fate::Shown(false),
            }
        })
        .collect();

    // 2. times: shift/start are relative to the first shown item
    let first_shown = items.iter().zip(&fates).find(|(_, f)| matches!(f, Fate::Shown(_))).map(|(it, _)| it.at);
    let shown_at = |it: &Item| -> Option<i64> {
        match &def.time {
            TimeOverride::Real => Some(it.at),
            TimeOverride::Hide => None,
            TimeOverride::Shift { offset_ms } => Some(it.at + offset_ms),
            TimeOverride::Start { start } => Some(it.at + (start - first_shown.unwrap_or(it.at))),
            TimeOverride::Manual { at } => Some(at.get(&it.id).copied().unwrap_or(it.at)),
        }
    };

    let on_stage: BTreeSet<&str> =
        items.iter().zip(&fates).filter(|(_, f)| matches!(f, Fate::Shown(_))).map(|(it, _)| it.id.as_str()).collect();

    // 3. rows, folding runs of context items
    let mut rows = Vec::new();
    let mut run: Vec<String> = Vec::new();
    let flush = |run: &mut Vec<String>, rows: &mut Vec<Row>| {
        if !run.is_empty() {
            let ids = std::mem::take(run);
            rows.push(Row::Context { count: ids.len(), ids });
        }
    };
    for (it, fate) in items.iter().zip(&fates) {
        match fate {
            Fate::Drop => {}
            Fate::Context => run.push(it.id.clone()),
            Fate::Shown(selected) => {
                flush(&mut run, &mut rows);
                rows.push(Row::Item {
                    id: it.id.clone(),
                    selected: *selected,
                    at: shown_at(it),
                    reply_shown: it.reply_to.as_deref().is_some_and(|r| on_stage.contains(r)),
                });
            }
        }
    }
    flush(&mut run, &mut rows);
    // context only matters around what is shown: trailing context after the last item is noise
    if matches!(rows.last(), Some(Row::Context { .. })) {
        rows.pop();
    }

    // 4. names: fake names win; redaction gives stable placeholders by first appearance
    let mut names: BTreeMap<String, FakeName> = BTreeMap::new();
    if def.redact_names {
        let mut n = 0;
        for it in items.iter().filter(|it| on_stage.contains(it.id.as_str())) {
            for a in &it.authors {
                if !names.contains_key(a) && !def.fake_names.contains_key(a) {
                    names.insert(a.clone(), FakeName { label: placeholder(n), color: Some("#A09184".into()) });
                    n += 1;
                }
            }
        }
    }
    for (id, f) in &def.fake_names {
        names.insert(id.clone(), f.clone());
    }
    Plan { rows, names }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn it(id: &str, authors: &[&str], at: i64) -> Item {
        Item { id: id.into(), authors: authors.iter().map(|s| s.to_string()).collect(), at, reply_to: None }
    }

    fn ids(p: &Plan) -> Vec<String> {
        p.rows
            .iter()
            .map(|r| match r {
                Row::Item { id, .. } => id.clone(),
                Row::Context { count, .. } => format!("({count})"),
            })
            .collect()
    }

    fn items() -> Vec<Item> {
        vec![
            it("1", &["kai"], 1_000),
            it("2", &["june"], 2_000),
            it("3", &["june"], 3_000),
            it("4", &["kai", "rin"], 4_000),
            it("5", &["moss"], 5_000),
        ]
    }

    #[test]
    fn empty_selection_shows_everything() {
        assert_eq!(ids(&plan(&items(), &Definition::default())), ["1", "2", "3", "4", "5"]);
    }

    #[test]
    fn unselected_modes() {
        let sel = |mode| Definition { selected: vec!["1".into(), "4".into()], unselected: mode, ..Default::default() };
        assert_eq!(ids(&plan(&items(), &sel(Unselected::Context))), ["1", "(2)", "4"]); // trailing context trimmed
        assert_eq!(ids(&plan(&items(), &sel(Unselected::Hidden))), ["1", "4"]);
        let p = plan(&items(), &sel(Unselected::Visible));
        assert_eq!(ids(&p), ["1", "2", "3", "4", "5"]);
        assert!(matches!(&p.rows[1], Row::Item { selected: false, .. }));
    }

    #[test]
    fn only_members_drops_other_members_replies() {
        let d = Definition { only_members: Some(vec!["kai".into(), "june".into()]), ..Default::default() };
        // 4 has rin as a co-author, 5 is moss
        assert_eq!(ids(&plan(&items(), &d)), ["1", "2", "3"]);
    }

    #[test]
    fn times_shift_start_manual_hide() {
        let at = |d: &Definition| {
            plan(&items(), d)
                .rows
                .iter()
                .filter_map(|r| match r {
                    Row::Item { at, .. } => Some(*at),
                    _ => None,
                })
                .collect::<Vec<_>>()
        };
        let sel = vec!["2".into(), "3".into()];
        let d = |time| Definition { selected: sel.clone(), unselected: Unselected::Hidden, time, ..Default::default() };
        assert_eq!(at(&d(TimeOverride::Start { start: 50_000 })), [Some(50_000), Some(51_000)]);
        assert_eq!(at(&d(TimeOverride::Shift { offset_ms: -500 })), [Some(1_500), Some(2_500)]);
        assert_eq!(at(&d(TimeOverride::Hide)), [None, None]);
        let manual = TimeOverride::Manual { at: BTreeMap::from([("3".to_string(), 9)]) };
        assert_eq!(at(&d(manual)), [Some(2_000), Some(9)]);
    }

    #[test]
    fn redaction_is_stable_and_fake_names_win() {
        let d = Definition {
            redact_names: true,
            fake_names: BTreeMap::from([("june".to_string(), FakeName { label: "Jay".into(), color: None })]),
            ..Default::default()
        };
        let p = plan(&items(), &d);
        assert_eq!(p.names["kai"].label, "Member A");
        assert_eq!(p.names["june"].label, "Jay");
        assert_eq!(p.names["rin"].label, "Member B");
        assert_eq!(p.names["moss"].label, "Member C");
        assert_eq!(placeholder(26), "Member AA");
    }

    #[test]
    fn reply_bars_only_point_at_items_on_stage() {
        let mut v = items();
        v[2].reply_to = Some("1".into());
        v[3].reply_to = Some("2".into());
        let d = Definition {
            selected: vec!["1".into(), "3".into(), "4".into()],
            unselected: Unselected::Hidden,
            ..Default::default()
        };
        let shown: Vec<bool> = plan(&v, &d)
            .rows
            .iter()
            .filter_map(|r| match r {
                Row::Item { reply_shown, .. } => Some(*reply_shown),
                _ => None,
            })
            .collect();
        assert_eq!(shown, [false, true, false]);
    }
}
