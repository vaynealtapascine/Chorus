//! Own-account effective history for named fronts (D-080, D-083).
//!
//! Saved presence is corrected retrospectively in human time. Configuration, manual override
//! activation and Resume take effect at record time, retaining canonical write precedence.
//! This is an on-demand history read, not the current-state render/composer path. Never expose
//! these source IDs, causes or intervals as a follower view (D-016, D-084).

use std::collections::{BTreeMap, BTreeSet};

use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::front::Subject;
use crate::front_history::{self, Action, Event, HistoryRow, Interval};
use crate::front_system::{FollowIssue, FollowState, Front, Presence, ResolvedFront, System};
use crate::lww::Clocks;
use crate::op::Op;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Cause {
    pub op_id: String,
    pub front_id: String,
    /// True only for a control boundary whose legacy record time had to be estimated.
    pub recorded_time_estimated: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Change {
    pub id: String,
    pub front_id: String,
    pub occurred_at: i64,
    pub tz_offset_min: i32,
    /// Confirmed effective presence only. Unavailable/deleted recovery fallbacks are excluded.
    pub entries: Vec<Presence>,
    pub source: Option<String>,
    pub state: FollowState,
    pub issue: Option<FollowIssue>,
    pub deleted: bool,
    pub causes: Vec<Cause>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct History {
    pub changes: Vec<Change>,
    /// start/end_switch_id reference effective change IDs, not raw own-operation IDs.
    pub intervals: Vec<Interval>,
    pub current: Option<ResolvedFront>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Snapshot {
    entries: Vec<Presence>,
    source: Option<String>,
    state: FollowState,
    issue: Option<FollowIssue>,
    deleted: bool,
}

impl From<&ResolvedFront> for Snapshot {
    fn from(f: &ResolvedFront) -> Self {
        Self {
            entries: if f.deleted || f.state == FollowState::Unavailable { vec![] } else { f.effective.clone() },
            source: f.source.clone(),
            state: f.state,
            issue: f.issue,
            deleted: f.deleted,
        }
    }
}

struct Point<'a> {
    at: i64,
    event: usize,
    /// None is the event's record-time control; Some is a corrected own-presence snapshot.
    own: Option<&'a HistoryRow>,
}

/// Raw reachable chain, including missing/deleted sources and all cycle edges. Watching only
/// a resolver's cut graph could miss the later edit that repairs the cut or restores a source.
fn chain(system: &System, selected: &str) -> BTreeSet<String> {
    let mut ids = BTreeSet::new();
    let mut cursor = selected;
    while ids.insert(cursor.into()) {
        let Some(f) = system.fronts.get(cursor) else { break };
        let Some(link) = &f.follow else { break };
        if f.deleted || f.overridden {
            // An override uses own presence, but the resolver still reports whether its
            // configured immediate source is missing/deleted. Preserve that status history.
            ids.insert(link.source.clone());
            break;
        }
        cursor = &link.source;
    }
    ids
}

fn empty_front(account: &str, id: &str) -> Front {
    if account == id {
        System::legacy(account, vec![]).fronts.remove(account).unwrap_or_else(|| unreachable!())
    } else {
        Front::new(id.into(), "Front".into())
    }
}

#[derive(Default)]
struct Controls {
    /// Global event indexes are already in canonical HLC/id order.
    seen: Vec<usize>,
    clocks: Clocks,
}

impl Controls {
    fn insert(&mut self, account: &str, f: &mut Front, index: usize, events: &[Event]) {
        if self.seen.last().is_none_or(|last| *last < index) {
            self.seen.push(index);
            front_history::apply_control(f, &mut self.clocks, &events[index]);
            return;
        }
        // Corrected record time can reorder writes from different devices. Replaying this
        // front's controls in HLC order preserves sticky overrides and sparse LWW semantics.
        let position = self.seen.partition_point(|i| *i < index);
        self.seen.insert(position, index);
        let own = std::mem::take(&mut f.own);
        *f = empty_front(account, &f.id);
        f.own = own;
        self.clocks = Clocks::default();
        for &i in &self.seen {
            front_history::apply_control(f, &mut self.clocks, &events[i]);
        }
    }
}

/// Derive one front's effective history, using the full account log so followed changes retain
/// their real causes. Unknown, malformed, purged and foreign-scope operations stay unprojected.
pub fn fold<'a>(account: &str, selected: &str, ops: impl IntoIterator<Item = &'a Op>) -> History {
    let events = front_history::parse_events(account, ops);
    let mut by_front: BTreeMap<&str, Vec<&Event>> = BTreeMap::new();
    let mut by_id = BTreeMap::new();
    let mut points = Vec::new();
    for (index, e) in events.iter().enumerate() {
        by_id.insert(e.id.as_str(), index);
        if matches!(e.action, Action::Change(_)) {
            by_front.entry(&e.front_id).or_default().push(e);
        }
        if !matches!(e.action, Action::Layer(_)) {
            points.push(Point { at: e.recorded_at, event: index, own: None });
        }
    }
    let own: Vec<_> = by_front.iter().map(|(id, events)| front_history::fold_own(id, events)).collect();
    for h in &own {
        for row in h.switches.iter().filter(|row| !row.retracted) {
            points.push(Point { at: row.occurred_at, event: by_id[row.id.as_str()], own: Some(row) });
        }
    }
    crate::sort::by(&mut points, |a, b| (a.at, a.event, a.own.is_some()).cmp(&(b.at, b.event, b.own.is_some())));
    let mut system = System::legacy(account, vec![]);
    let mut controls: BTreeMap<&str, Controls> = BTreeMap::new();
    let mut result = History { current: system.resolve().remove(selected), ..Default::default() };
    let mut previous = result.current.as_ref().map(Snapshot::from);
    let mut watched = chain(&system, selected);
    let mut open: BTreeMap<Subject, Interval> = BTreeMap::new();
    let mut start = 0;
    while start < points.len() {
        let at = points[start].at;
        let end = start + points[start..].partition_point(|p| p.at == at);
        let batch = &points[start..end];
        let relevant = batch.iter().any(|p| watched.contains(&events[p.event].front_id));
        for p in batch {
            let e = &events[p.event];
            let f = system.fronts.entry(e.front_id.clone()).or_insert_with(|| empty_front(account, &e.front_id));
            if let Some(row) = p.own {
                f.own = row.resulting_front.clone();
            } else {
                controls.entry(&e.front_id).or_default().insert(account, f, p.event, &events);
            }
        }
        if relevant {
            let after = chain(&system, selected);
            result.current = system.resolve().remove(selected);
            let snapshot = result.current.as_ref().map(Snapshot::from);
            if snapshot != previous {
                // Fronts are tombstoned, never physically removed, so a changed snapshot exists.
                if let Some(s) = &snapshot {
                    let mut causes: BTreeMap<usize, Cause> = BTreeMap::new();
                    for p in batch {
                        let e = &events[p.event];
                        if watched.contains(&e.front_id) || after.contains(&e.front_id) {
                            let cause = causes.entry(p.event).or_insert_with(|| Cause {
                                op_id: e.id.clone(),
                                front_id: e.front_id.clone(),
                                recorded_time_estimated: false,
                            });
                            cause.recorded_time_estimated |= p.own.is_none() && e.recorded_time_estimated;
                        }
                    }
                    let tz_offset_min = causes.last_key_value().map_or(0, |(i, _)| events[*i].tz_offset_min);
                    let causes: Vec<_> = causes.into_values().collect();
                    let mut hash = Sha256::new();
                    hash.update(serde_json::to_vec(&(selected, at, &causes)).unwrap_or_default());
                    let id: String = hash.finalize()[..16].iter().map(|b| format!("{b:02x}")).collect();
                    front_history::transition_intervals(
                        selected,
                        &s.entries,
                        at,
                        &id,
                        tz_offset_min,
                        &mut open,
                        &mut result.intervals,
                    );
                    result.changes.push(Change {
                        id,
                        front_id: selected.into(),
                        occurred_at: at,
                        tz_offset_min,
                        entries: s.entries.clone(),
                        source: s.source.clone(),
                        state: s.state,
                        issue: s.issue,
                        deleted: s.deleted,
                        causes,
                    });
                }
                previous = snapshot;
            }
            watched = after;
        }
        start = end;
    }
    result.intervals.extend(open.into_values());
    crate::sort::by(&mut result.intervals, |a, b| (a.start_at, &a.id).cmp(&(b.start_at, &b.id)));
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::front_history;
    use crate::hlc::Hlc;
    use proptest::prelude::*;
    use serde_json::{Value, json};

    const BODY: &str = "0192f8c2-0000-7000-8000-0000000000a1";
    const CHORUS: &str = "0192f8c2-0000-7000-8000-0000000000a2";
    const HEADSPACE: &str = "0192f8c2-0000-7000-8000-0000000000a3";

    fn op(i: u64, kind: &str, front: &str, payload: Value) -> Op {
        serde_json::from_value(json!({
            "id": crate::id::new_id(i, [i as u8; 10]), "kind":kind,
            "scope": format!("account:{BODY}"), "entity_id":front,
            "hlc": Hlc::new(i * 1000, 0, 1), "device_at": i as i64 * 1000,
            "payload":payload
        }))
        .unwrap()
    }

    fn switch(i: u64, front: &str, member: &str) -> Op {
        op(
            i,
            "front.change",
            front,
            json!({"action":"switch","entries":[{
                "subject_type":"member","subject_id":member,"layer_id":"quiet"
            }]}),
        )
    }

    fn define(i: u64, front: &str, source: Option<&str>) -> Op {
        op(i, "front.define", front, json!({"follow_source":source}))
    }

    fn trace(history: &History) -> Vec<(i64, FollowState, Vec<&str>)> {
        history
            .changes
            .iter()
            .map(|c| (c.occurred_at, c.state, c.entries.iter().map(|e| e.subject_id.as_str()).collect()))
            .collect()
    }

    #[test]
    fn follows_sources_only_during_following_periods_and_keeps_overrides() {
        let ops = vec![
            switch(1, CHORUS, "own"),
            switch(2, BODY, "source-a"),
            define(3, CHORUS, Some(BODY)),
            switch(4, BODY, "source-b"),
            switch(5, CHORUS, "override"),
            switch(6, BODY, "source-c"),
            op(7, "front.change", CHORUS, json!({"action":"resume"})),
            define(8, CHORUS, None),
        ];
        let h = fold(BODY, CHORUS, &ops);
        use FollowState::*;
        assert_eq!(
            trace(&h),
            vec![
                (1000, Independent, vec!["own"]),
                (3000, Following, vec!["source-a"]),
                (4000, Following, vec!["source-b"]),
                (5000, Overridden, vec!["override"]),
                (7000, Following, vec!["source-c"]),
                (8000, Independent, vec!["override"])
            ]
        );
        assert_eq!(h.changes[2].causes[0].op_id, ops[3].id);
        assert_eq!(h.changes[2].causes[0].front_id, BODY);
        assert_eq!(
            h.intervals.iter().map(|iv| (iv.subject_id.as_str(), iv.start_at, iv.end_at)).collect::<Vec<_>>(),
            vec![
                ("own", 1000, Some(3000)),
                ("source-a", 3000, Some(4000)),
                ("source-b", 4000, Some(5000)),
                ("override", 5000, Some(7000)),
                ("source-c", 7000, Some(8000)),
                ("override", 8000, None)
            ]
        );
        assert_eq!(h.current, front_history::fold(BODY, &ops).resolved.remove(CHORUS));
    }

    #[test]
    fn backdated_live_change_activates_override_at_record_time() {
        let mut backdated = switch(6, CHORUS, "past");
        backdated.payload["effective_at"] = json!(500);
        let ops =
            vec![switch(1, CHORUS, "later-own"), switch(2, BODY, "source"), define(3, CHORUS, Some(BODY)), backdated];
        let h = fold(BODY, CHORUS, &ops);
        use FollowState::*;
        assert_eq!(
            trace(&h),
            vec![
                (500, Independent, vec!["past"]),
                (1000, Independent, vec!["later-own"]),
                (3000, Following, vec!["source"]),
                (6000, Overridden, vec!["later-own"])
            ]
        );
        assert_eq!(h.changes.first().unwrap().causes[0].op_id, h.changes.last().unwrap().causes[0].op_id);
        assert_ne!(h.changes.first().unwrap().id, h.changes.last().unwrap().id);
        let mut missed = ops.clone();
        missed.last_mut().unwrap().payload["history_only"] = json!(true);
        let kept = fold(BODY, CHORUS, &missed);
        assert_eq!(kept.changes.len(), 3);
        assert_eq!(kept.current.unwrap().state, Following);
    }

    #[test]
    fn corrections_rewrite_saved_periods_without_replaying_a_live_override() {
        let old = switch(1, CHORUS, "old");
        let mut ops = vec![
            old.clone(),
            switch(2, BODY, "source"),
            define(3, CHORUS, Some(BODY)),
            op(
                4,
                "front.change",
                CHORUS,
                json!({"action":"amend","target_op_id":old.id,
                "occurred_at":500,"entries":[{"subject_type":"member","subject_id":"corrected","layer_id":"new"}]}),
            ),
        ];
        let h = fold(BODY, CHORUS, &ops);
        assert_eq!(h.changes[0].occurred_at, 500);
        assert_eq!(h.changes[0].entries[0].subject_id, "corrected");
        assert_eq!(h.changes.last().unwrap().occurred_at, 3000);
        for (i, action) in [(5, "retract"), (6, "unretract")] {
            ops.push(op(i, "front.change", CHORUS, json!({"action":action,"target_op_id":old.id})));
            let result = fold(BODY, CHORUS, &ops);
            assert_eq!(result.current.unwrap().state, FollowState::Following);
            assert!(result.changes.iter().all(|c| c.occurred_at < 4000));
            assert_eq!(result.intervals.iter().any(|iv| iv.subject_id == "corrected"), action == "unretract");
        }
    }

    #[test]
    fn deleted_or_missing_sources_create_gaps_and_restore_without_counting_fallbacks() {
        let ops = vec![
            switch(1, CHORUS, "fallback"),
            define(2, CHORUS, Some(HEADSPACE)),
            switch(3, HEADSPACE, "source"),
            op(4, "front.define", HEADSPACE, json!({"deleted":true})),
            op(5, "front.define", HEADSPACE, json!({"deleted":false})),
            op(6, "front.define", CHORUS, json!({"deleted":true})),
        ];
        let h = fold(BODY, CHORUS, &ops);
        assert_eq!(
            h.intervals.iter().map(|iv| (iv.subject_id.as_str(), iv.start_at, iv.end_at)).collect::<Vec<_>>(),
            vec![("fallback", 1000, Some(2000)), ("source", 3000, Some(4000)), ("source", 5000, Some(6000))]
        );
        for change in h.changes.iter().filter(|c| c.state == FollowState::Unavailable || c.deleted) {
            assert!(change.entries.is_empty());
        }
        let totals = front_history::own_daily(&h.intervals, 7000, |_| 0);
        assert_eq!(totals.iter().map(|r| r.seconds).sum::<i64>(), 3);
        assert_eq!(totals.iter().find(|r| r.subject_id == "source").unwrap().seconds, 2);
    }

    #[test]
    fn chains_cycles_and_repairs_retain_the_source_causes() {
        let ops = vec![
            switch(1, BODY, "a"),
            define(2, CHORUS, Some(BODY)),
            define(3, HEADSPACE, Some(CHORUS)),
            define(4, BODY, Some(HEADSPACE)),
            define(5, BODY, None),
            switch(6, BODY, "b"),
        ];
        let h = fold(BODY, HEADSPACE, &ops);
        assert_eq!(h.changes[1].state, FollowState::Unavailable);
        assert!(h.changes[1].entries.is_empty());
        assert_eq!(h.changes[2].entries[0].subject_id, "a");
        assert_eq!(h.changes[3].entries[0].subject_id, "b");
        assert_eq!(h.changes[1].causes[0].op_id, ops[3].id);
        assert_eq!(
            h.intervals.iter().map(|iv| (iv.start_at, iv.end_at)).collect::<Vec<_>>(),
            vec![(3000, Some(4000)), (5000, Some(6000)), (6000, None)]
        );
    }

    #[test]
    fn equal_time_changes_are_batched_and_order_alone_keeps_intervals() {
        let mut a = switch(1, BODY, "a");
        a.payload["entries"]
            .as_array_mut()
            .unwrap()
            .push(json!({"subject_type":"member","subject_id":"b","layer_id":"quiet"}));
        let mut reorder = op(
            2,
            "front.change",
            BODY,
            json!({"action":"update","subject_type":"member","subject_id":"b","position":0}),
        );
        reorder.device_at = 1000;
        let mut follow = define(3, CHORUS, Some(BODY));
        follow.device_at = 1000;
        let mut own = switch(4, CHORUS, "own");
        own.payload["history_only"] = json!(true);
        own.device_at = 1000;
        let reorder2 = op(
            5,
            "front.change",
            BODY,
            json!({"action":"update","subject_type":"member","subject_id":"a","position":0}),
        );
        let h = fold(BODY, CHORUS, [&a, &reorder, &follow, &own, &reorder2]);
        assert_eq!(h.changes.len(), 2);
        assert_eq!(h.changes[0].entries[0].subject_id, "b");
        assert_eq!(h.changes[0].causes.len(), 4);
        assert_eq!(h.changes[1].entries[0].subject_id, "a");
        assert_eq!(h.intervals.len(), 2);
        assert!(h.intervals.iter().all(|iv| iv.start_at == 1000 && iv.end_at.is_none()));
    }

    #[test]
    fn record_clock_reordering_and_server_stamps_keep_canonical_control_precedence() {
        let source = switch(1, BODY, "source");
        let mut follow = define(2, CHORUS, Some(BODY));
        follow.device_at = 5000;
        let manual = switch(3, CHORUS, "own");
        let ops = vec![source, follow, manual];
        let h = fold(BODY, CHORUS, &ops);
        // The manual write happened after follow in HLC order. When the corrected follow time
        // is reached, inserting its earlier write must retain the later manual override.
        assert_eq!(h.changes.last().unwrap().state, FollowState::Overridden);
        assert_eq!(h.changes.last().unwrap().occurred_at, 5000);
        let mut confirmed = ops[1].clone();
        confirmed.seq = Some(20);
        confirmed.occurred_at = Some(2000);
        let mut acknowledged = ops.clone();
        acknowledged.push(confirmed);
        let result = fold(BODY, CHORUS, &acknowledged);
        assert_eq!(result.changes.last().unwrap().occurred_at, 3000);
        assert_eq!(result.current, h.current);
    }

    #[test]
    fn legacy_record_estimates_are_flagged_only_at_control_boundaries() {
        let mut follow = define(3, CHORUS, Some(BODY));
        follow.time_source = crate::time::TimeSource::User;
        follow.device_at = 100;
        let h = fold(BODY, CHORUS, [&switch(1, BODY, "source"), &follow]);
        assert_eq!(h.changes[0].occurred_at, 3000);
        assert!(h.changes[0].causes[0].recorded_time_estimated);
        let mut legacy = op(
            4,
            "front.switch",
            BODY,
            json!({"entries":[{"subject_type":"member","subject_id":"old","level":"front"}]}),
        );
        legacy.time_source = crate::time::TimeSource::User;
        legacy.device_at = 500;
        let h = fold(BODY, BODY, [&legacy]);
        assert_eq!(h.changes.len(), 1);
        assert_eq!(h.changes[0].occurred_at, 500);
        assert!(!h.changes[0].causes[0].recorded_time_estimated);
    }

    #[test]
    fn unrelated_fronts_and_metadata_do_not_invent_presence_changes() {
        let a = switch(1, HEADSPACE, "other");
        assert!(fold(BODY, BODY, [&a]).changes.is_empty());
        assert_eq!(fold(BODY, CHORUS, [&a]), History::default());
        let ops = vec![
            switch(1, BODY, "a"),
            op(2, "front.layer", BODY, json!({"layer_id":"quiet","name":"Rename","level":8})),
            op(3, "front.define", BODY, json!({"name":"New body","authorship_layers":[]})),
        ];
        let h = fold(BODY, BODY, &ops);
        assert_eq!(h.changes.len(), 1);
        assert_eq!(h.current.unwrap().name, "New body");
    }

    #[test]
    fn source_availability_status_still_changes_during_an_override() {
        let ops = vec![
            switch(1, BODY, "source"),
            define(2, CHORUS, Some(BODY)),
            switch(3, CHORUS, "own"),
            op(4, "front.define", BODY, json!({"deleted":true})),
            op(5, "front.define", BODY, json!({"deleted":false})),
        ];
        let h = fold(BODY, CHORUS, &ops);
        assert_eq!(h.changes.last().unwrap().occurred_at, 5000);
        assert_eq!(h.changes[h.changes.len() - 2].issue, Some(FollowIssue::SourceUnavailable));
        assert_eq!(h.changes.last().unwrap().issue, None);
        assert_eq!(h.intervals.iter().filter(|iv| iv.subject_id == "own").count(), 1);
        assert_eq!(h.current, front_history::fold(BODY, &ops).resolved.remove(CHORUS));
    }

    /// Independent time-slice reconstruction: fold all controls recorded by T, replace their
    /// presence with the corrected own-history snapshot at T, then resolve from scratch.
    fn reference(account: &str, selected: &str, ops: &[Op]) -> Vec<(i64, Snapshot)> {
        let full = front_history::fold(account, ops);
        let events = front_history::parse_events(account, ops);
        let rows: Vec<_> = full
            .histories
            .iter()
            .flat_map(|(id, h)| h.switches.iter().filter(|r| !r.retracted).map(move |r| (id, r)))
            .collect();
        let times: BTreeSet<_> = events
            .iter()
            .filter(|e| !matches!(e.action, Action::Layer(_)))
            .map(|e| e.recorded_at)
            .chain(rows.iter().map(|(_, r)| r.occurred_at))
            .collect();
        let mut previous = System::legacy(account, vec![]).resolve().get(selected).map(Snapshot::from);
        let mut changes = Vec::new();
        for at in times {
            let prefix: Vec<_> = ops
                .iter()
                .filter(|o| Event::from_op(account, o).ok().flatten().is_some_and(|e| e.recorded_at <= at))
                .collect();
            let mut s = front_history::fold(account, prefix).system;
            for f in s.fronts.values_mut() {
                f.own.clear();
            }
            for (id, row) in rows.iter().filter(|(_, r)| r.occurred_at <= at) {
                s.fronts.entry((*id).clone()).or_insert_with(|| empty_front(account, id)).own =
                    row.resulting_front.clone();
            }
            let snapshot = s.resolve().get(selected).map(Snapshot::from);
            if snapshot != previous {
                if let Some(value) = &snapshot {
                    changes.push((at, value.clone()));
                }
                previous = snapshot;
            }
        }
        changes
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(512))]
        #[test]
        fn matches_independent_time_slices_and_converges(
            picks in prop::collection::vec((0u8..10,0u8..3,0u8..3,0u16..30,0u16..30,any::<bool>()),1..45),
        ) {
            let ids=[BODY,CHORUS,HEADSPACE];
            let mut ops=Vec::new();
            for (i,(kind,target,source,recorded,effective,history)) in picks.into_iter().enumerate(){
                let i=i as u64+1;
                let id=ids[target as usize];
                let mut o=match kind {
                    0..=2=>{let mut o=switch(i,id,&format!("m{source}"));o.payload["effective_at"]=json!(i64::from(effective)*100);o.payload["history_only"]=json!(history);o},
                    3=>define(i,id,Some(ids[source as usize])),
                    4=>define(i,id,None),
                    5=>op(i,"front.change",id,json!({"action":"resume"})),
                    6=>op(i,"front.define",id,json!({"deleted":history})),
                    7=>op(i,"front.change",id,json!({"action":"amend","target_op_id":crate::id::new_id(1,[1;10]),"occurred_at":i64::from(effective)*100})),
                    _=>op(i,"front.change",id,json!({"action":if history{"retract"}else{"unretract"},"target_op_id":crate::id::new_id(1,[1;10])})),
                };
                o.device_at=i64::from(recorded)*100;
                if source==target { o.hlc=Hlc::new(i/3,0,1); }
                ops.push(o);
            }
            let selected=ids[ops.len()%3];
            let result=fold(BODY,selected,&ops);
            let actual:Vec<_>=result.changes.iter().map(|c|(c.occurred_at,Snapshot{entries:c.entries.clone(),source:c.source.clone(),state:c.state,issue:c.issue,deleted:c.deleted})).collect();
            prop_assert_eq!(actual,reference(BODY,selected,&ops));
            prop_assert_eq!(&result.current,&front_history::fold(BODY,&ops).resolved.remove(selected));
            ops.reverse();
            prop_assert_eq!(fold(BODY,selected,&ops),result.clone());
            prop_assert!(result.intervals.iter().all(|iv|iv.end_at.is_none_or(|end|end>iv.start_at)));
            let keys:BTreeSet<_>=result.changes.iter().map(|c|&c.id).collect();
            prop_assert_eq!(keys.len(),result.changes.len());
        }
    }

    #[test]
    #[ignore = "release performance check"]
    fn ten_thousand_followed_switches() {
        let mut ops = vec![define(1, CHORUS, Some(BODY))];
        ops.extend((2..=10_001).map(|i| switch(i, BODY, &format!("m{}", i % 500))));
        let start = std::time::Instant::now();
        let result = fold(BODY, CHORUS, &ops);
        let elapsed = start.elapsed();
        assert_eq!(result.intervals.len(), 10_000);
        eprintln!("effective history: 10,000 followed switches in {elapsed:?}");
        assert!(elapsed < std::time::Duration::from_millis(300));
    }
}
