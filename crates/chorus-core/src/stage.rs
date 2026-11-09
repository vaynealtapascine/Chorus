//! Stage mode (SPEC §7, CLIENTS.md §5): which items a screenshot view shows, what collapses into
//! "context", and the view-only overrides (fake or redacted names, shifted/hidden times, D-050).
//!
//! A stage is a *view definition* over real items — nothing here writes data. It lives in core so
//! a saved stage renders the same on the web and on Android. Rendering (styles, blur, width) is
//! the client's; those options pass through in `render` untouched, except a custom theme's three
//! colours, which core turns into a whole palette so both clients draw the same one.

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
#[serde(remote = "Self", rename_all = "snake_case")]
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
crate::tagged!(TimeOverride, "mode");

#[derive(Clone, Debug, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Definition {
    /// Chosen items. Empty = everything is "selected" (a fresh stage shows the whole view).
    pub selected: Vec<String>,
    pub unselected: Unselected,
    /// "Only these members": items with any other author are dropped entirely.
    pub only_members: Option<Vec<String>>,
    /// Crop reply chains to this many levels: an item that is a reply to a reply to … deeper
    /// than this is dropped (0 = no replies at all). Depth follows `reply_to` through `items`; a
    /// reply to something outside them counts as one level.
    pub reply_depth: Option<u32>,
    /// Replace every author with "Member A", "Member B"… (fake names still win).
    pub redact_names: bool,
    pub fake_names: BTreeMap<String, FakeName>,
    pub time: TimeOverride,
    /// Words blacked out, per item: `[start, end)` ranges in UTF-16 code units of the item's text
    /// (what Kotlin and JavaScript strings count). The plan hands them back sorted and merged.
    pub blackout: BTreeMap<String, Vec<[u32; 2]>>,
    /// Client rendering options, passed through: `style`, `theme` (`auto`/`light`/`dark`/`custom`
    /// with `palette: {bg, ink, accent}`), `width` (`phone`/`square`/`wide`), `blur_avatars`,
    /// `blur_attachments`, `hide_header`, `hide_reply_bars`, `hide_reactions`,
    /// `hide_thread_previews`, `hide_dates`, `hide_counts`.
    pub render: Value,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(remote = "Self", rename_all = "snake_case")]
pub enum Row {
    Item {
        id: String,
        /// false = shown only because unselected items are "visible".
        selected: bool,
        /// The time to show, after overrides; `None` = hidden.
        at: Option<i64>,
        /// Whether the item this replies to is on stage (else the client hides the reply bar).
        reply_shown: bool,
        /// Blacked-out ranges of its text, sorted and merged (omitted when none).
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        blackout: Vec<[u32; 2]>,
    },
    /// Consecutive unselected items folded into one quiet row ("3 messages").
    Context { ids: Vec<String>, count: usize },
}
crate::tagged!(Row, "kind");

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Plan {
    pub rows: Vec<Row>,
    /// member id → the name/colour to show instead of the real one (only overridden members).
    pub names: BTreeMap<String, FakeName>,
    /// A custom theme's whole palette (`render.theme: "custom"` with a valid `render.palette`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub palette: Option<Palette>,
}

/// Every colour a stage preview uses, derived from a custom theme's background, text and accent.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Palette {
    pub dark: bool,
    pub bg: String,
    pub surface: String,
    pub surface2: String,
    pub surface3: String,
    pub line: String,
    pub ink: String,
    pub ink2: String,
    pub ink3: String,
    pub accent: String,
    pub accent_soft: String,
    pub on_accent: String,
    pub bubble_out: String,
}

/// `{bg, ink, accent}` → the full palette: surfaces step from the background towards the text,
/// softer inks step back towards the background, and the ink on the accent is whichever of black
/// and white reads better.
pub fn custom_palette(render: &Value) -> Option<Palette> {
    if render.get("theme").and_then(Value::as_str) != Some("custom") {
        return None;
    }
    let colors = render.get("palette")?;
    let pick = |k: &str| colors.get(k).and_then(Value::as_str).and_then(crate::color::Rgb::parse);
    let (bg, ink, accent) = (pick("bg")?, pick("ink")?, pick("accent")?);
    let white = crate::color::Rgb { r: 1.0, g: 1.0, b: 1.0 };
    let black = crate::color::Rgb { r: 0.0, g: 0.0, b: 0.0 };
    let on_accent =
        if crate::color::contrast(accent, white) >= crate::color::contrast(accent, black) { white } else { black };
    Some(Palette {
        dark: crate::color::luminance(bg) < crate::color::luminance(ink),
        bg: bg.hex(),
        surface: bg.mix(ink, 0.05).hex(),
        surface2: bg.mix(ink, 0.09).hex(),
        surface3: bg.mix(ink, 0.15).hex(),
        line: bg.mix(ink, 0.14).hex(),
        ink: ink.hex(),
        ink2: ink.mix(bg, 0.25).hex(),
        ink3: ink.mix(bg, 0.45).hex(),
        accent: accent.hex(),
        accent_soft: bg.mix(accent, 0.18).hex(),
        on_accent: on_accent.hex(),
        bubble_out: bg.mix(accent, 0.25).hex(),
    })
}

/// Sorted, merged, non-empty ranges.
fn merge_ranges(ranges: &[[u32; 2]]) -> Vec<[u32; 2]> {
    let mut v: Vec<[u32; 2]> = ranges.iter().filter(|r| r[0] < r[1]).copied().collect();
    crate::sort::by_key(&mut v, |r| (r[0], r[1]));
    let mut out: Vec<[u32; 2]> = Vec::with_capacity(v.len());
    for r in v {
        match out.last_mut() {
            Some(last) if r[0] <= last[1] => last[1] = last[1].max(r[1]),
            _ => out.push(r),
        }
    }
    out
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
    let chosen: BTreeSet<&str> = crate::sort::set(def.selected.iter().map(String::as_str));
    let only: Option<BTreeSet<&str>> =
        def.only_members.as_ref().map(|v| crate::sort::set(v.iter().map(String::as_str)));
    let passes = |it: &Item| only.as_ref().is_none_or(|o| it.authors.iter().all(|a| o.contains(a.as_str())));

    // reply depth of each item (hop-limited, so a malformed cycle can't loop)
    let index: BTreeMap<&str, &Item> = crate::sort::map(items.iter().map(|it| (it.id.as_str(), it)));
    let depth = |it: &Item| -> u32 {
        let mut d = 0;
        let mut cur = it;
        while let Some(parent) = cur.reply_to.as_deref() {
            d += 1;
            match index.get(parent) {
                Some(p) if (d as usize) <= items.len() => cur = p,
                _ => break,
            }
        }
        d
    };
    let cropped = |it: &Item| def.reply_depth.is_some_and(|n| depth(it) > n);

    // 1. decide each item: shown (selected?), context, or dropped
    enum Fate {
        Shown(bool),
        Context,
        Drop,
    }
    let fates: Vec<Fate> = items
        .iter()
        .map(|it| {
            if !passes(it) || cropped(it) {
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

    let on_stage: BTreeSet<&str> = crate::sort::set(
        items.iter().zip(&fates).filter(|(_, f)| matches!(f, Fate::Shown(_))).map(|(it, _)| it.id.as_str()),
    );

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
                    blackout: def.blackout.get(&it.id).map(|r| merge_ranges(r)).unwrap_or_default(),
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
    Plan { rows, names, palette: custom_palette(&def.render) }
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
    fn reply_chains_crop_to_n_levels() {
        let mut v = items();
        v[1].reply_to = Some("1".into()); // depth 1
        v[2].reply_to = Some("2".into()); // depth 2
        v[3].reply_to = Some("3".into()); // depth 3
        v[4].reply_to = Some("gone".into()); // parent not on this view: depth 1
        let d = |n| Definition { reply_depth: n, ..Default::default() };
        assert_eq!(ids(&plan(&v, &d(None))), ["1", "2", "3", "4", "5"]);
        assert_eq!(ids(&plan(&v, &d(Some(2)))), ["1", "2", "3", "5"]);
        assert_eq!(ids(&plan(&v, &d(Some(0)))), ["1"]);
        // a cycle terminates
        v[0].reply_to = Some("4".into());
        assert_eq!(ids(&plan(&v, &d(Some(0)))), Vec::<String>::new());
    }

    #[test]
    fn blackout_ranges_merge_and_custom_palettes_derive() {
        let d = Definition {
            blackout: BTreeMap::from([("2".to_string(), vec![[8, 12], [0, 3], [2, 5], [7, 7]])]),
            render: serde_json::json!({"theme": "custom", "palette": {"bg": "#102030", "ink": "#ffffff", "accent": "#42a397"}}),
            ..Default::default()
        };
        let p = plan(&items(), &d);
        assert!(matches!(&p.rows[1], Row::Item { blackout, .. } if blackout == &vec![[0, 5], [8, 12]]));
        assert!(matches!(&p.rows[0], Row::Item { blackout, .. } if blackout.is_empty()));
        let palette = p.palette.expect("custom palette");
        assert!(palette.dark);
        assert_eq!(palette.bg, "#102030");
        assert_eq!(palette.on_accent, "#000000");
        let plain = Definition {
            render: serde_json::json!({"theme": "custom", "palette": {"bg": "nope"}}),
            ..Default::default()
        };
        assert_eq!(plan(&items(), &plain).palette, None);
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
