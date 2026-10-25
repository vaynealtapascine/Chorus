//! Current named-front projection and conservative append acceleration.
//!
//! History is read separately. Live clients/server can append a genuinely newest presence or
//! control without reconstructing all intervals; late, corrected, targeted or rejected writes
//! use the reference current-state fold. Neither path changes the source log.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use crate::front_history::{self, Action, Change, Event};
use crate::front_system::{Front, ResolvedFront, System};
use crate::hlc::Hlc;
use crate::lww::Clocks;
use crate::op::Op;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Current {
    pub system: System,
    pub resolved: std::collections::BTreeMap<String, ResolvedFront>,
}

/// Derived append guards. This is private replica/server cache metadata, never a follower view.
/// It is rebuildable from the original account operations.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AppendGuard {
    pub last_write: Option<(Hlc, String)>,
    pub latest_presence_at: Option<i64>,
    pub targets: BTreeSet<String>,
}

impl AppendGuard {
    fn observe(&mut self, e: &Event) {
        let key = (e.hlc, e.id.clone());
        if self.last_write.as_ref().is_none_or(|old| key > *old) {
            self.last_write = Some(key);
        }
        let at = match &e.action {
            Action::Change(Change::Amend { target_op_id, occurred_at, .. }) => {
                self.targets.insert(target_op_id.clone());
                *occurred_at
            }
            Action::Change(Change::Retract { target_op_id } | Change::Unretract { target_op_id }) => {
                self.targets.insert(target_op_id.clone());
                None
            }
            Action::Change(change) if change.is_presence() => Some(e.occurred_at),
            _ => None,
        };
        if let Some(at) = at {
            self.latest_presence_at = Some(self.latest_presence_at.map_or(at, |old| old.max(at)));
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Index {
    account_id: String,
    pub current: Current,
    pub guard: AppendGuard,
}

impl Index {
    pub fn from_ops<'a>(account: &str, ops: impl IntoIterator<Item = &'a Op>) -> Self {
        let events = front_history::parse_events(account, ops);
        let mut guard = AppendGuard::default();
        for e in &events {
            guard.observe(e);
        }
        Self { account_id: account.into(), current: front_history::current_from_events(account, &events), guard }
    }

    /// Restore derived cache state whose owning account is established by the caller's storage
    /// key. The cache is discarded/rebuilt whenever its projection schema or op digest changes.
    pub fn from_cached(account: &str, current: Current, guard: AppendGuard) -> Self {
        Self { account_id: account.into(), current, guard }
    }

    /// The caller establishes that this op ID is new; replacements/removals always rebuild.
    /// False leaves self untouched. Callers then rebuild from the complete surviving op set.
    /// This rejects corrected copies (same write key), backdates and late amendment targets.
    pub fn append(&mut self, op: &Op) -> bool {
        let Ok(Some(e)) = Event::from_op(&self.account_id, op) else { return false };
        // Equal HLCs can still differ by op ID, but strict field LWW keeps the first same-clock
        // field write. Rebuild that exceptional case rather than treating all fields as new.
        if self.guard.last_write.as_ref().is_some_and(|last| e.hlc <= last.0) {
            return false;
        }
        if let Action::Change(change) = &e.action {
            if matches!(change, Change::Amend { .. } | Change::Retract { .. } | Change::Unretract { .. }) {
                return false;
            }
            if change.is_presence()
                && (self.guard.targets.contains(&e.id)
                    || self.guard.latest_presence_at.is_some_and(|at| e.occurred_at < at))
            {
                return false;
            }
        }
        match &e.action {
            Action::Layer(p) => front_history::apply_layer(&mut self.current.system, &mut Clocks::default(), p, e.hlc),
            _ => {
                let front = self
                    .current
                    .system
                    .fronts
                    .entry(e.front_id.clone())
                    .or_insert_with(|| Front::new(e.front_id.clone(), "Front".into()));
                front_history::apply_control(front, &mut Clocks::default(), &e);
                if let Action::Change(change) = &e.action
                    && change.is_presence()
                {
                    front_history::apply_presence(&mut front.own, &e);
                }
            }
        }
        self.current.resolved = self.current.system.resolve();
        self.guard.observe(&e);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use serde_json::{Value, json};

    const BODY: &str = "0192f8c2-0000-7000-8000-0000000000a1";
    const CHORUS: &str = "0192f8c2-0000-7000-8000-0000000000a2";

    fn op(i: u64, kind: &str, front: &str, payload: Value) -> Op {
        serde_json::from_value(json!({"id":crate::id::new_id(i,[i as u8;10]),"scope":format!("account:{BODY}"),
            "kind":kind,"entity_id":front,"hlc":Hlc::new(i*1000,0,1),"device_at":i*1000,"payload":payload}))
        .unwrap()
    }
    fn switch(i: u64, front: &str, member: &str) -> Op {
        op(
            i,
            "front.change",
            front,
            json!({"action":"switch","entries":[{"subject_type":"member","subject_id":member,"layer_id":"quiet"}]}),
        )
    }
    fn reference(ops: &[Op]) -> Current {
        let full = front_history::fold(BODY, ops);
        Current { system: full.system, resolved: full.resolved }
    }

    #[test]
    fn append_preserves_following_legacy_deltas_and_sparse_configuration() {
        let mut index = Index::from_ops(BODY, []);
        let mut ops = vec![];
        for o in [
            switch(1, BODY, "source"),
            op(2, "front.define", CHORUS, json!({"follow_source":BODY})),
            op(3, "front.layer", BODY, json!({"layer_id":"quiet","name":"Quiet","level":-2})),
            switch(4, CHORUS, "own"),
            op(5, "front.define", CHORUS, json!({"name":"Renamed","authorship_layers":["quiet"]})),
            op(6, "front.change", CHORUS, json!({"action":"resume"})),
            op(
                7,
                "front.add",
                BODY,
                json!({"entry":{"subject_type":"member","subject_id":"legacy","level":"present"}}),
            ),
            op(8, "front.define", BODY, json!({"deleted":true})),
            op(9, "front.define", BODY, json!({"deleted":false})),
        ] {
            assert!(index.append(&o), "{}", o.kind);
            ops.push(o);
            assert_eq!(index.current, reference(&ops));
            assert_eq!(index, Index::from_ops(BODY, &ops));
        }
    }

    #[test]
    fn late_targets_backdates_equal_clocks_and_corrected_copies_require_rebuild() {
        let future = switch(9, CHORUS, "late");
        let target =
            op(1, "front.change", CHORUS, json!({"action":"amend","target_op_id":future.id,"occurred_at":50000}));
        let base = vec![target, switch(2, CHORUS, "current")];
        let index = Index::from_ops(BODY, &base);
        let mut backdated = switch(10, CHORUS, "past");
        backdated.payload["effective_at"] = json!(500);
        let mut same_clock = switch(11, CHORUS, "equal");
        same_clock.hlc = base[1].hlc;
        let mut corrected = base[1].clone();
        corrected.seq = Some(50);
        corrected.occurred_at = Some(2500);
        for o in [
            future,
            backdated,
            same_clock,
            corrected,
            op(12, "front.change", CHORUS, json!({"action":"retract","target_op_id":base[1].id})),
        ] {
            let mut copy = index.clone();
            assert!(!copy.append(&o));
            assert_eq!(copy, index, "failed append must be atomic");
            let mut all = base.clone();
            all.push(o);
            assert_eq!(Index::from_ops(BODY, &all).current, reference(&all));
        }
    }

    #[test]
    fn restored_cache_retains_guards_and_foreign_scopes_cannot_mutate_it() {
        let ops = vec![switch(1, BODY, "source"), op(2, "front.define", CHORUS, json!({"follow_source":BODY}))];
        let index = Index::from_ops(BODY, &ops);
        let guard: AppendGuard = serde_json::from_str(&serde_json::to_string(&index.guard).unwrap()).unwrap();
        let current: Current = serde_json::from_str(&serde_json::to_string(&index.current).unwrap()).unwrap();
        let mut restored = Index::from_cached(BODY, current, guard);
        let mut other = switch(3, CHORUS, "foreign");
        other.scope = format!("account:{CHORUS}");
        assert!(!restored.append(&other));
        assert_eq!(restored, index);
        let mut next = switch(4, CHORUS, "missed");
        next.payload["history_only"] = json!(true);
        assert!(restored.append(&next));
        let mut all = ops;
        all.push(next);
        assert_eq!(restored.current, reference(&all));
        assert_eq!(restored.current.resolved[CHORUS].state, crate::front_system::FollowState::Following);
    }

    #[test]
    fn rebuilding_after_rejection_removes_follow_controls_and_append_high_water() {
        let own = switch(1, CHORUS, "own");
        let follow = op(2, "front.define", CHORUS, json!({"follow_source":BODY}));
        let mut rejected = switch(3, CHORUS, "rejected");
        rejected.payload["effective_at"] = json!(50_000);
        let mut index = Index::from_ops(BODY, [&own, &follow, &rejected]);
        assert_eq!(index.guard.latest_presence_at, Some(50_000));
        let next = switch(4, CHORUS, "next");
        assert!(!index.append(&next));
        index = Index::from_ops(BODY, [&own, &follow]);
        assert_eq!(index.current.resolved[CHORUS].state, crate::front_system::FollowState::Following);
        assert!(index.append(&next));
        let all = vec![own.clone(), follow, next];
        assert_eq!(index.current, reference(&all));
        let independent = Index::from_ops(BODY, [&own]);
        assert_eq!(independent.current.resolved[CHORUS].state, crate::front_system::FollowState::Independent);
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(512))]
        #[test]
        fn append_or_rebuild_matches_the_full_history_fold(
            picks in prop::collection::vec((0u8..11,0u8..2,0u8..4,0u16..70,any::<bool>()),1..90),
        ) {
            let mut ops=Vec::new();
            let mut index=Index::from_ops(BODY,[]);
            for (i,(kind,front,member,at,flag)) in picks.into_iter().enumerate(){
                let i=i as u64+1;
                let front=if front==0{BODY}else{CHORUS};
                let target=crate::id::new_id(1,[1;10]);
                let mut o=match kind {
                    0..=2=>switch(i,front,&format!("m{member}")),
                    3=>op(i,"front.define",front,json!({"follow_source":if flag{Some(if front==BODY{CHORUS}else{BODY})}else{None}})),
                    4=>op(i,"front.change",front,json!({"action":"resume"})),
                    5=>op(i,"front.change",front,json!({"action":"amend","target_op_id":target,"occurred_at":i64::from(at)*1000})),
                    6=>op(i,"front.change",front,json!({"action":if flag{"retract"}else{"unretract"},"target_op_id":target})),
                    7=>op(i,"front.define",front,json!({"deleted":flag,"authorship_layers":if flag{Some(vec!["quiet"])}else{None},"purpose_layers":{"reading":if flag{Some(vec!["quiet"])}else{None}}})),
                    8=>op(i,"front.layer",BODY,json!({"layer_id":"quiet","name":format!("Layer{member}"),"deleted":flag,"level":member})),
                    9=>op(i,"front.add",BODY,json!({"entry":{"subject_type":"member","subject_id":format!("m{member}"),"level":"present"}})),
                    _=>op(i,"front.change",front,json!({"action":"update","subject_type":"member","subject_id":format!("m{member}"),"is_primary":flag,"position":member})),
                };
                o.device_at=i64::from(at)*1000;
                let before=index.clone();
                let appended=index.append(&o);
                ops.push(o);
                if !appended {prop_assert_eq!(&index,&before);index=Index::from_ops(BODY,&ops);}
                prop_assert_eq!(&index.current,&reference(&ops));
                prop_assert_eq!(&index.guard,&Index::from_ops(BODY,&ops).guard);
            }
        }
    }

    #[test]
    #[ignore = "release performance check"]
    fn current_and_live_append_budgets() {
        let mut ops = vec![op(1, "front.define", CHORUS, json!({"follow_source":BODY}))];
        ops.extend((2..=10_001).map(|i| switch(i, BODY, &format!("m{}", i % 500))));
        let start = std::time::Instant::now();
        let mut index = Index::from_ops(BODY, &ops);
        let cold = start.elapsed();
        assert!(cold < std::time::Duration::from_millis(50), "current fold took {cold:?}");
        let live: Vec<_> = (10_002..=11_001).map(|i| switch(i, BODY, &format!("m{}", i % 500))).collect();
        let start = std::time::Instant::now();
        for o in &live {
            assert!(index.append(o));
        }
        let append = start.elapsed();
        eprintln!("current 10,000 switches: {cold:?}; 1,000 live appends: {append:?}");
        assert!(append < std::time::Duration::from_millis(100));
    }
}
