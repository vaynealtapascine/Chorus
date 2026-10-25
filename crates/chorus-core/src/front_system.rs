//! Named fronts and independent layers (D-080).
//!
//! This is the current-state resolver. Event folding supplies each front's own ordered presence,
//! winning configuration and sticky override state. Following never overwrites that own state.
//! Layer display levels do not imply authorship, notification or legacy presence semantics.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::front::SubjectType;
use crate::hlc::Hlc;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Layer {
    pub id: String,
    pub name: String,
    /// Display containment/order only; never a purpose or a semantic role.
    pub level: i32,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Presence {
    pub subject_type: SubjectType,
    pub subject_id: String,
    pub layer_id: String,
    #[serde(default)]
    pub is_primary: bool,
}

/// One subject once, in its first position, and at most one primary across all independent
/// layers. Do not sort members by layer: a front owns an explicit order of its own.
pub fn normalize(entries: &mut Vec<Presence>) {
    let mut seen = BTreeSet::new();
    entries.retain(|e| seen.insert((e.subject_type, e.subject_id.clone())));
    let mut primary = false;
    for e in entries {
        if e.is_primary && !primary {
            primary = true;
        } else {
            e.is_primary = false;
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FollowLink {
    pub source: String,
    /// The winning source-setting clock, supplied by the event fold. Break cycles by this
    /// total order, then front ID, so replicas choose the same ignored edge.
    pub clock: Hlc,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Front {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub own: Vec<Presence>,
    #[serde(default)]
    pub follow: Option<FollowLink>,
    #[serde(default)]
    pub overridden: bool,
    /// None supplies the three original layer IDs to the legacy speaker rule (primary/front,
    /// then its existing fallback). Some(empty) selects nobody; custom IDs require selection.
    #[serde(default = "default_authorship_layers")]
    pub authorship_layers: Option<BTreeSet<String>>,
    /// None preserves the legacy unfiltered input to follower ceilings. A new front starts
    /// with Some(empty), keeping it private until its notification layers are configured.
    #[serde(default = "default_notification_layers")]
    pub notification_layers: Option<BTreeSet<String>>,
    /// Independent channel purposes (D-085). Missing/null keeps that purpose's legacy default;
    /// an explicit empty selection selects nobody. Unknown future purposes default to nobody.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub purpose_layers: BTreeMap<String, Option<BTreeSet<String>>>,
    #[serde(default)]
    pub deleted: bool,
}

fn default_authorship_layers() -> Option<BTreeSet<String>> {
    Some(BTreeSet::from(["front".into()]))
}

fn default_notification_layers() -> Option<BTreeSet<String>> {
    Some(BTreeSet::new())
}

impl Front {
    pub fn new(id: String, name: String) -> Self {
        Self {
            id,
            name,
            own: vec![],
            follow: None,
            overridden: false,
            authorship_layers: default_authorship_layers(),
            notification_layers: default_notification_layers(),
            purpose_layers: BTreeMap::new(),
            deleted: false,
        }
    }

    /// Manual edits preserve a configured follow source and make its override sticky.
    pub fn set_presence(&mut self, mut entries: Vec<Presence>) {
        normalize(&mut entries);
        self.own = entries;
        self.overridden = self.follow.is_some();
    }

    pub fn set_follow(&mut self, source: Option<String>, clock: Hlc) {
        // Changing the source (or its clock) while following must not resume an override.
        if self.follow.is_none() || source.is_none() {
            self.overridden = false;
        }
        self.follow = source.map(|source| FollowLink { source, clock });
    }

    pub fn resume_following(&mut self) {
        self.overridden = false;
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct System {
    pub layers: BTreeMap<String, Layer>,
    pub fronts: BTreeMap<String, Front>,
}

impl System {
    /// The account ID is also its stable compatibility Body-front ID.
    pub fn legacy(body_id: &str, entries: Vec<Presence>) -> Self {
        let layers = [("front", "Fronting"), ("cocon", "Co-conscious"), ("present", "Present")]
            .into_iter()
            .enumerate()
            .map(|(i, (id, name))| {
                (id.into(), Layer { id: id.into(), name: name.into(), level: i as i32, deleted: false })
            })
            .collect();
        let mut body = Front::new(body_id.into(), "Body".into());
        body.own = entries;
        normalize(&mut body.own);
        body.authorship_layers = None;
        body.notification_layers = None;
        Self { layers, fronts: BTreeMap::from([(body_id.into(), body)]) }
    }

    pub fn ordered_layers(&self) -> Vec<&Layer> {
        let mut layers: Vec<_> = self.layers.values().collect();
        layers.sort_by(|a, b| (a.level, &a.id).cmp(&(b.level, &b.id)));
        layers
    }

    /// Resolve without recursion, including very deep chains. Missing/deleted sources and the
    /// deterministically ignored edge of a cycle use the front's retained own presence.
    pub fn resolve(&self) -> BTreeMap<String, ResolvedFront> {
        // Dense indexes avoid a separate tree allocation/monomorphization for every kind of
        // graph bookkeeping. The input is already ID-sorted; binary search preserves that
        // canonical order without a second lookup map (important to the wasm budget).
        let fronts: Vec<_> = self.fronts.iter().collect();
        let count = fronts.len();
        let edges: Vec<Option<usize>> = fronts
            .iter()
            .map(|(_, f)| {
                if f.deleted || f.overridden {
                    return None;
                }
                let link = f.follow.as_ref()?;
                fronts
                    .binary_search_by(|(id, _)| id.as_str().cmp(link.source.as_str()))
                    .ok()
                    .filter(|&i| !fronts[i].1.deleted)
            })
            .collect();
        let mut cuts = vec![false; count];
        let mut done = vec![false; count];
        let mut seen_in = vec![0; count];
        let mut positions = vec![0; count];
        for start in 0..count {
            let mut path = vec![];
            let mut cursor = start;
            while !done[cursor] && !cuts[cursor] {
                let Some(next) = edges[cursor] else { break };
                if seen_in[cursor] == start + 1 {
                    let ignored = path[positions[cursor]..].iter().copied().max_by_key(|&i: &usize| {
                        (fronts[i].1.follow.as_ref().map(|f| f.clock).unwrap_or_default(), fronts[i].0)
                    });
                    if let Some(i) = ignored {
                        cuts[i] = true;
                    }
                    break;
                }
                seen_in[cursor] = start + 1;
                positions[cursor] = path.len();
                path.push(cursor);
                cursor = next;
            }
            for i in path {
                done[i] = true;
            }
        }

        let mut effective: Vec<Option<Vec<Presence>>> = vec![None; count];
        let mut settled = vec![false; count];
        for start in 0..count {
            let mut path = vec![];
            let mut cursor = start;
            while effective[cursor].is_none() {
                if !cuts[cursor]
                    && let Some(next) = edges[cursor]
                {
                    path.push(cursor);
                    cursor = next;
                } else {
                    let f = fronts[cursor].1;
                    let mut own = f.own.clone();
                    normalize(&mut own);
                    effective[cursor] = Some(own);
                    settled[cursor] = !f.deleted && (f.follow.is_none() || f.overridden);
                }
            }
            for i in path.into_iter().rev() {
                if let Some(source) = edges[i] {
                    effective[i] = effective[source].clone();
                    settled[i] = settled[source];
                }
            }
        }

        fronts
            .into_iter()
            .enumerate()
            .map(|(i, (id, f))| {
                let source = f.follow.as_ref().map(|link| link.source.clone());
                let issue = if cuts[i] {
                    Some(FollowIssue::Cycle)
                } else if source.as_ref().is_some_and(|id| self.fronts.get(id).is_none_or(|s| s.deleted))
                    || (f.follow.is_some() && !f.overridden && !settled[i])
                {
                    Some(FollowIssue::SourceUnavailable)
                } else {
                    None
                };
                let state = if f.follow.is_none() {
                    FollowState::Independent
                } else if f.overridden {
                    FollowState::Overridden
                } else if issue.is_some() || f.deleted {
                    FollowState::Unavailable
                } else {
                    FollowState::Following
                };
                let entries = effective[i].take().unwrap_or_default();
                let eligible = state != FollowState::Unavailable && !f.deleted;
                let authorship = entries
                    .iter()
                    .filter(|e| {
                        eligible
                            && f.authorship_layers
                                .as_ref()
                                .map_or(matches!(e.layer_id.as_str(), "front" | "cocon" | "present"), |ids| {
                                    ids.contains(&e.layer_id)
                                })
                    })
                    .cloned()
                    .collect();
                let notifications = entries
                    .iter()
                    .filter(|e| eligible && f.notification_layers.as_ref().is_none_or(|ids| ids.contains(&e.layer_id)))
                    .cloned()
                    .collect();
                let mut purpose_candidates = BTreeMap::new();
                for purpose in CHANNEL_PURPOSES.into_iter().chain(f.purpose_layers.keys().map(String::as_str)) {
                    if purpose_candidates.contains_key(purpose) {
                        continue;
                    }
                    let layers = f.purpose_layers.get(purpose).and_then(Option::as_ref);
                    let legacy = CHANNEL_PURPOSES.contains(&purpose);
                    let selected = entries
                        .iter()
                        .filter(|e| {
                            eligible
                                && layers.map_or(legacy && matches!(e.layer_id.as_str(), "front" | "cocon"), |ids| {
                                    ids.contains(&e.layer_id)
                                })
                        })
                        .cloned()
                        .collect();
                    purpose_candidates.insert(purpose.into(), selected);
                }
                let mut own = f.own.clone();
                normalize(&mut own);
                (
                    id.clone(),
                    ResolvedFront {
                        id: id.clone(),
                        name: f.name.clone(),
                        deleted: f.deleted,
                        own,
                        effective: entries,
                        source,
                        state,
                        issue,
                        authorship,
                        notifications,
                        purpose_candidates,
                    },
                )
            })
            .collect()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FollowState {
    Independent,
    Following,
    Overridden,
    Unavailable,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FollowIssue {
    Cycle,
    SourceUnavailable,
}

pub const CHANNEL_PURPOSES: [&str; 3] = ["mentions", "reading", "chat_alerts"];

/// Own-account data only. Follower APIs must build their reveal-time view separately (D-016).
/// An unavailable front retains a recovery/display fallback, with no default authorship or
/// notification candidates. Consumers must keep its unavailable state visible.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResolvedFront {
    pub id: String,
    pub name: String,
    pub deleted: bool,
    pub own: Vec<Presence>,
    pub effective: Vec<Presence>,
    pub source: Option<String>,
    pub state: FollowState,
    pub issue: Option<FollowIssue>,
    pub authorship: Vec<Presence>,
    pub notifications: Vec<Presence>,
    #[serde(default)]
    pub purpose_candidates: BTreeMap<String, Vec<Presence>>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    fn entry(id: &str, layer: &str) -> Presence {
        Presence { subject_type: SubjectType::Member, subject_id: id.into(), layer_id: layer.into(), is_primary: false }
    }

    fn front(id: &str, entries: Vec<Presence>) -> Front {
        let mut f = Front::new(id.into(), id.into());
        f.set_presence(entries);
        f
    }

    fn system(fronts: Vec<Front>) -> System {
        System { fronts: fronts.into_iter().map(|f| (f.id.clone(), f)).collect(), ..Default::default() }
    }

    /// Deliberately slow single-start walk, independent of the dense graph's shared traversal
    /// state. Re-derives the cycle cut for every start to check disjoint cycles and tail chains.
    fn reference(s: &System, start: &str) -> (Vec<Presence>, bool, Option<String>) {
        let mut seen = BTreeMap::new();
        let mut path: Vec<&str> = vec![];
        let mut cursor = start;
        loop {
            let f = &s.fronts[cursor];
            if f.deleted || f.follow.is_none() || f.overridden {
                let mut own = f.own.clone();
                normalize(&mut own);
                return (own, !f.deleted, None);
            }
            if let Some(&at) = seen.get(cursor) {
                let cut =
                    *path[at..].iter().max_by_key(|id| (s.fronts[**id].follow.as_ref().unwrap().clock, **id)).unwrap();
                let mut own = s.fronts[cut].own.clone();
                normalize(&mut own);
                return (own, false, Some(cut.into()));
            }
            seen.insert(cursor, path.len());
            path.push(cursor);
            let source = &f.follow.as_ref().unwrap().source;
            if s.fronts.get(source).is_none_or(|f| f.deleted) {
                let mut own = f.own.clone();
                normalize(&mut own);
                return (own, false, None);
            }
            cursor = source;
        }
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(512))]
        #[test]
        fn dense_graph_matches_independent_walks(nodes in prop::collection::vec((0u8..40,0u8..8,any::<bool>(),any::<bool>()),1..36)) {
            let fronts = nodes.iter().enumerate().map(|(i,(source,clock,overridden,deleted))| {
                let id = format!("f{i:02}");
                let mut f = front(&id,vec![entry(&format!("m{i}"),"front")]);
                if source%4!=0 {
                    f.set_follow(Some(format!("f{:02}",usize::from(*source)%(nodes.len()+1))),Hlc::new(u64::from(*clock),0,1));
                }
                f.overridden = *overridden && f.follow.is_some();
                f.deleted = *deleted;
                f.notification_layers = Some(BTreeSet::from(["front".into()]));
                f
            }).collect();
            let s = system(fronts);
            let actual = s.resolve();
            for (id,f) in &s.fronts {
                let (entries,available,cut) = reference(&s,id);
                let direct_missing = f.follow.as_ref().is_some_and(|link|s.fronts.get(&link.source).is_none_or(|f|f.deleted));
                let issue = if cut.as_deref()==Some(id.as_str()) {Some(FollowIssue::Cycle)}
                    else if direct_missing || (f.follow.is_some() && !f.overridden && !available) {Some(FollowIssue::SourceUnavailable)} else {None};
                let state = if f.follow.is_none() {FollowState::Independent} else if f.overridden {FollowState::Overridden}
                    else if issue.is_some() || f.deleted {FollowState::Unavailable} else {FollowState::Following};
                prop_assert_eq!(&actual[id].effective,&entries);
                prop_assert_eq!(actual[id].issue,issue);
                prop_assert_eq!(actual[id].state,state);
                let candidates = if available {entries} else {vec![]};
                prop_assert_eq!(&actual[id].authorship,&candidates);
                prop_assert_eq!(&actual[id].notifications,&candidates);
            }
        }
    }

    #[test]
    fn independent_order_and_purposes_do_not_come_from_layer_names_or_levels() {
        let mut body = front("body", vec![entry("fox", "quiet"), entry("moon", "front"), entry("space", "third")]);
        body.authorship_layers = Some(BTreeSet::from(["quiet".into(), "third".into()]));
        body.notification_layers = Some(BTreeSet::from(["front".into()]));
        let chorus = front("chorus", vec![entry("space", "quiet"), entry("fox", "front")]);
        let mut s = system(vec![body, chorus]);
        s.layers
            .insert("quiet".into(), Layer { id: "quiet".into(), name: "Fronting".into(), level: -10, deleted: false });
        s.layers
            .insert("third".into(), Layer { id: "third".into(), name: "Present".into(), level: 500, deleted: false });
        let before = s.resolve();
        assert_eq!(before["body"].authorship, vec![entry("fox", "quiet"), entry("space", "third")]);
        assert_eq!(before["body"].notifications, vec![entry("moon", "front")]);
        assert_eq!(before["chorus"].effective, vec![entry("space", "quiet"), entry("fox", "front")]);
        s.layers.get_mut("quiet").unwrap().name = "Any other name".into();
        s.layers.get_mut("quiet").unwrap().level = 600;
        assert_eq!(s.resolve(), before, "display configuration does not change semantics or member order");
    }

    #[test]
    fn overrides_survive_source_updates_and_source_changes_until_resume() {
        let source = front("a", vec![entry("fox", "custom")]);
        let mut follower = front("b", vec![entry("own", "other")]);
        follower.set_follow(Some("a".into()), Hlc::new(1, 0, 1));
        let mut chained = front("c", vec![]);
        chained.set_follow(Some("b".into()), Hlc::new(2, 0, 1));
        let mut s = system(vec![source, follower, chained]);
        assert_eq!(s.resolve()["c"].effective, vec![entry("fox", "custom")]);
        s.fronts.get_mut("b").unwrap().set_presence(vec![entry("override", "third")]);
        s.fronts.get_mut("a").unwrap().set_presence(vec![entry("moon", "different")]);
        assert_eq!(s.resolve()["c"].effective, vec![entry("override", "third")]);
        s.fronts.insert("other".into(), front("other", vec![entry("space", "new")]));
        s.fronts.get_mut("b").unwrap().set_follow(Some("other".into()), Hlc::new(3, 0, 1));
        assert_eq!(s.resolve()["b"].state, FollowState::Overridden);
        let saved = serde_json::to_string(&s).unwrap();
        let mut s: System = serde_json::from_str(&saved).unwrap();
        assert_eq!(s.resolve()["b"].effective, vec![entry("override", "third")]);
        s.fronts.get_mut("b").unwrap().resume_following();
        assert_eq!(s.resolve()["c"].effective, vec![entry("space", "new")]);
        assert_eq!(s.fronts["b"].own, vec![entry("override", "third")], "following retains the independent set");
    }

    #[test]
    fn cycles_cut_the_latest_edge_and_missing_sources_keep_own_data() {
        let mut a = front("a", vec![entry("a", "alpha")]);
        let mut b = front("b", vec![entry("b", "beta")]);
        a.set_follow(Some("b".into()), Hlc::new(1, 0, 1));
        b.set_follow(Some("a".into()), Hlc::new(2, 0, 1));
        let s = system(vec![b.clone(), a.clone()]);
        let resolved = s.resolve();
        assert_eq!(resolved["b"].issue, Some(FollowIssue::Cycle));
        assert_eq!(resolved["a"].effective, b.own);
        assert_eq!(system(vec![a, b]).resolve(), resolved);
        let mut s = s;
        s.fronts.get_mut("b").unwrap().deleted = true;
        assert_eq!(s.resolve()["a"].issue, Some(FollowIssue::SourceUnavailable));
        assert_eq!(s.resolve()["a"].effective, vec![entry("a", "alpha")]);
        s.fronts.get_mut("b").unwrap().deleted = false;
        s.fronts.get_mut("b").unwrap().set_follow(None, Hlc::new(3, 0, 1));
        assert_eq!(s.resolve()["a"].effective, vec![entry("b", "beta")]);
    }

    #[test]
    fn cycle_clocks_precede_ids_and_self_links_are_visible_errors() {
        let mut a = front("a", vec![entry("a", "alpha")]);
        let mut b = front("b", vec![entry("b", "beta")]);
        a.set_follow(Some("b".into()), Hlc::new(10, 0, 1));
        b.set_follow(Some("a".into()), Hlc::new(1, 0, 1));
        let resolved = system(vec![a.clone(), b]).resolve();
        assert_eq!(resolved["a"].issue, Some(FollowIssue::Cycle), "clock wins over ID order");
        assert_eq!(resolved["b"].effective, a.own);
        a.set_follow(Some("a".into()), Hlc::new(11, 0, 1));
        assert_eq!(system(vec![a.clone()]).resolve()["a"].issue, Some(FollowIssue::Cycle));
        a.set_follow(Some("missing".into()), Hlc::new(12, 0, 1));
        let resolved = system(vec![a.clone()]).resolve();
        assert_eq!(resolved["a"].issue, Some(FollowIssue::SourceUnavailable));
        assert_eq!(resolved["a"].effective, a.own);
    }

    #[test]
    fn unresolved_sources_cannot_choose_a_speaker_or_notification_candidate() {
        let mut a = front("a", vec![entry("old", "front")]);
        a.notification_layers = Some(BTreeSet::from(["front".into()]));
        a.set_follow(Some("missing".into()), Hlc::new(1, 0, 1));
        let mut b = front("b", vec![]);
        b.notification_layers = Some(BTreeSet::from(["front".into()]));
        b.set_follow(Some("a".into()), Hlc::new(2, 0, 1));
        let mut s = system(vec![a, b]);
        for f in s.resolve().values() {
            assert_eq!(f.state, FollowState::Unavailable);
            assert!(f.authorship.is_empty());
            assert!(f.notifications.is_empty());
        }
        s.fronts.get_mut("a").unwrap().set_presence(vec![entry("explicit", "front")]);
        let resolved = s.resolve();
        assert_eq!(resolved["a"].state, FollowState::Overridden);
        assert_eq!(resolved["b"].state, FollowState::Following);
        assert_eq!(resolved["b"].authorship, vec![entry("explicit", "front")]);
        assert_eq!(resolved["b"].notifications, vec![entry("explicit", "front")]);
    }

    #[test]
    fn legacy_defaults_and_explicit_empty_selections_are_different() {
        let entries = vec![entry("fox", "front"), entry("moon", "cocon"), entry("space", "present")];
        let mut s = System::legacy("body", entries.clone());
        let resolved = s.resolve();
        assert_eq!(resolved["body"].authorship, entries);
        assert_eq!(resolved["body"].notifications, entries);
        assert_eq!(s.ordered_layers().iter().map(|l| l.id.as_str()).collect::<Vec<_>>(), ["front", "cocon", "present"]);
        s.fronts.get_mut("body").unwrap().authorship_layers = Some(BTreeSet::new());
        s.fronts.get_mut("body").unwrap().notification_layers = Some(BTreeSet::new());
        assert!(s.resolve()["body"].authorship.is_empty());
        assert!(s.resolve()["body"].notifications.is_empty());
        let new = front("new", vec![entry("fox", "front")]);
        assert!(system(vec![new]).resolve()["new"].notifications.is_empty());
    }

    #[test]
    fn normalization_keeps_first_subject_and_all_layer_ids_are_independent() {
        let mut entries = vec![entry("a", "custom"), entry("b", "front"), entry("a", "present")];
        entries[0].is_primary = true;
        entries[1].is_primary = true;
        normalize(&mut entries);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].layer_id, "custom");
        assert!(entries[0].is_primary);
        assert!(!entries[1].is_primary);
        let many: Vec<_> = (0..64).map(|i| entry(&format!("m{i}"), &format!("layer{i}"))).collect();
        assert_eq!(system(vec![front("many", many.clone())]).resolve()["many"].effective, many);
    }

    #[test]
    fn deep_chains_do_not_use_the_call_stack() {
        let mut fronts = vec![front("0", vec![entry("source", "custom")])];
        for i in 1..5000 {
            let mut f = front(&i.to_string(), vec![]);
            f.set_follow(Some((i - 1).to_string()), Hlc::new(i, 0, 1));
            fronts.push(f);
        }
        let resolved = system(fronts).resolve();
        assert_eq!(resolved["4999"].effective, vec![entry("source", "custom")]);
    }

    #[test]
    #[ignore = "release performance check"]
    fn large_graph_budget() {
        let mut fronts = vec![front("0", vec![entry("source", "custom")])];
        for i in 1..5000 {
            let mut f = front(&i.to_string(), vec![]);
            f.set_follow(Some((i - 1).to_string()), Hlc::new(i, 0, 1));
            fronts.push(f);
        }
        let s = system(fronts);
        let start = std::time::Instant::now();
        let resolved = s.resolve();
        let elapsed = start.elapsed();
        assert_eq!(resolved["4999"].effective, vec![entry("source", "custom")]);
        eprintln!("resolve 5,000-front chain: {elapsed:?}");
        assert!(elapsed < std::time::Duration::from_millis(150));
    }
}
