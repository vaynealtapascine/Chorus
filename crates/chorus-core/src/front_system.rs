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
    /// None preserves the legacy Body rule (the `front` layer). Some(empty) selects nobody.
    #[serde(default = "default_authorship_layers")]
    pub authorship_layers: Option<BTreeSet<String>>,
    /// None preserves the legacy unfiltered input to follower ceilings. A new front starts
    /// with Some(empty), keeping it private until its notification layers are configured.
    #[serde(default = "default_notification_layers")]
    pub notification_layers: Option<BTreeSet<String>>,
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
        let edges: BTreeMap<&str, &str> = self
            .fronts
            .iter()
            .filter(|(_, f)| !f.deleted && !f.overridden)
            .filter_map(|(id, f)| {
                let link = f.follow.as_ref()?;
                self.fronts.get(&link.source).filter(|s| !s.deleted)?;
                Some((id.as_str(), link.source.as_str()))
            })
            .collect();
        let mut cuts: BTreeSet<&str> = BTreeSet::new();
        let mut done: BTreeSet<&str> = BTreeSet::new();
        for start in self.fronts.keys().map(String::as_str) {
            let mut path = vec![];
            let mut positions = BTreeMap::new();
            let mut cursor = start;
            while !done.contains(cursor) && !cuts.contains(cursor) {
                let Some(&next) = edges.get(cursor) else { break };
                if let Some(&at) = positions.get(cursor) {
                    let ignored = path[at..].iter().copied().max_by_key(|id: &&str| {
                        (self.fronts[*id].follow.as_ref().map(|f| f.clock).unwrap_or_default(), *id)
                    });
                    if let Some(id) = ignored {
                        cuts.insert(id);
                    }
                    break;
                }
                positions.insert(cursor, path.len());
                path.push(cursor);
                cursor = next;
            }
            done.extend(path);
        }

        let mut effective: BTreeMap<&str, Vec<Presence>> = BTreeMap::new();
        let mut settled: BTreeMap<&str, bool> = BTreeMap::new();
        for start in self.fronts.keys().map(String::as_str) {
            let mut path = vec![];
            let mut cursor = start;
            while !effective.contains_key(cursor) {
                if !cuts.contains(cursor)
                    && let Some(&next) = edges.get(cursor)
                {
                    path.push(cursor);
                    cursor = next;
                } else {
                    let mut own = self.fronts[cursor].own.clone();
                    normalize(&mut own);
                    effective.insert(cursor, own);
                    let f = &self.fronts[cursor];
                    settled.insert(cursor, !f.deleted && (f.follow.is_none() || f.overridden));
                }
            }
            for id in path.into_iter().rev() {
                let inherited = edges.get(id).and_then(|source| effective.get(source)).cloned().unwrap_or_default();
                let available = edges.get(id).and_then(|source| settled.get(source)).copied().unwrap_or(false);
                effective.insert(id, inherited);
                settled.insert(id, available);
            }
        }

        self.fronts
            .iter()
            .map(|(id, f)| {
                let source = f.follow.as_ref().map(|link| link.source.clone());
                let issue = if cuts.contains(id.as_str()) {
                    Some(FollowIssue::Cycle)
                } else if source.as_ref().is_some_and(|id| self.fronts.get(id).is_none_or(|s| s.deleted))
                    || (f.follow.is_some() && !f.overridden && !settled.get(id.as_str()).copied().unwrap_or(false))
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
                let entries = effective.remove(id.as_str()).unwrap_or_default();
                let eligible = state != FollowState::Unavailable && !f.deleted;
                let authorship = entries
                    .iter()
                    .filter(|e| {
                        eligible
                            && f.authorship_layers
                                .as_ref()
                                .map_or(e.layer_id == "front", |ids| ids.contains(&e.layer_id))
                    })
                    .cloned()
                    .collect();
                let notifications = entries
                    .iter()
                    .filter(|e| eligible && f.notification_layers.as_ref().is_none_or(|ids| ids.contains(&e.layer_id)))
                    .cloned()
                    .collect();
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
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(resolved["body"].authorship, vec![entry("fox", "front")]);
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
}
