//! Property tests for the front fold: the result depends only on the set of ops (SYNC.md §1.3).

use chorus_core::front::*;
use chorus_core::hlc::Hlc;
use proptest::prelude::*;

const MEMBERS: [&str; 5] = ["kai", "june", "rin", "ash", "moss"];

fn entry() -> impl Strategy<Value = Entry> {
    (0usize..5, 0u8..3, any::<bool>()).prop_map(|(m, l, p)| Entry {
        subject_type: SubjectType::Member,
        subject_id: MEMBERS[m].into(),
        level: [Level::Front, Level::Cocon, Level::Present][l as usize],
        is_primary: p,
    })
}

fn action(n_prev: usize) -> impl Strategy<Value = FrontAction> {
    let target = move |i: usize| format!("op{}", i % n_prev.max(1));
    prop_oneof![
        proptest::collection::vec(entry(), 0..4).prop_map(|entries| FrontAction::Switch(SwitchPayload {
            entries,
            based_on: None,
            note: None,
            notify: Notify::Default
        })),
        (entry(), proptest::option::of(0usize..3)).prop_map(|(entry, position)| FrontAction::Add(AddPayload {
            entry,
            position,
            based_on: None,
            notify: Notify::Default
        })),
        (0usize..5).prop_map(|m| FrontAction::Remove(RemovePayload {
            subject_type: SubjectType::Member,
            subject_id: MEMBERS[m].into(),
            based_on: None,
            notify: Notify::Default
        })),
        (0usize..5, proptest::option::of(0u8..3), proptest::option::of(any::<bool>()), proptest::option::of(0usize..3))
            .prop_map(|(m, l, p, pos)| FrontAction::Update(UpdatePayload {
                subject_type: SubjectType::Member,
                subject_id: MEMBERS[m].into(),
                level: l.map(|l| [Level::Front, Level::Cocon, Level::Present][l as usize]),
                is_primary: p,
                position: pos,
                based_on: None
            })),
        (0usize..50).prop_map(move |i| FrontAction::Retract(TargetPayload { target_op_id: target(i) })),
        (0usize..50).prop_map(move |i| FrontAction::Unretract(TargetPayload { target_op_id: target(i) })),
        (0usize..50, proptest::option::of(0i64..1000)).prop_map(move |(i, at)| FrontAction::Amend(AmendPayload {
            target_op_id: target(i),
            occurred_at: at,
            entries: None,
            note: None
        })),
    ]
}

fn ops() -> impl Strategy<Value = Vec<FrontOp>> {
    proptest::collection::vec((action(20), 0i64..1000, 0u32..3), 1..20).prop_map(|v| {
        v.into_iter()
            .enumerate()
            .map(|(i, (action, at, node))| FrontOp {
                id: format!("op{i}"),
                action,
                occurred_at: at,
                tz_offset_min: 0,
                // unique HLC per op, like real ones
                hlc: Hlc::new(at as u64, i as u16, node),
                device_id: format!("d{node}"),
                seq: Some(i as i64 + 1),
                seen_seq: 0,
                was_offline: false,
            })
            .collect()
    })
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(512))]

    #[test]
    fn fold_is_order_independent(ops in ops(), seed in any::<u64>()) {
        let base = fold(&ops);
        let mut shuffled = ops.clone();
        let mut s = seed;
        for i in (1..shuffled.len()).rev() {
            s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            shuffled.swap(i, (s >> 33) as usize % (i + 1));
        }
        prop_assert_eq!(fold(&shuffled), base);
    }

    #[test]
    fn fold_invariants(ops in ops()) {
        let r = fold(&ops);
        // current front is normalized
        let mut n = r.current.clone();
        normalize(&mut n);
        prop_assert_eq!(&n, &r.current);
        // exactly one open interval per current subject, none for others
        let open: Vec<_> = r.intervals.iter().filter(|i| i.end_at.is_none()).collect();
        prop_assert_eq!(open.len(), r.current.len());
        for e in &r.current {
            prop_assert!(open.iter().any(|i| i.subject_id == e.subject_id && i.level == e.level));
        }
        // closed intervals have end >= start
        for i in &r.intervals {
            if let Some(end) = i.end_at { prop_assert!(end >= i.start_at); }
        }
        // interval ids unique
        let mut ids: Vec<_> = r.intervals.iter().map(|i| &i.id).collect();
        ids.sort();
        let len = ids.len();
        ids.dedup();
        prop_assert_eq!(ids.len(), len);
    }
}
