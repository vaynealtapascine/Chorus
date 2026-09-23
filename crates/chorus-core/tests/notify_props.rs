//! NOTIFICATIONS.md §2.9 / §5: for any sequence of switches, the displayed times a follower sees
//! are never after delivery and never go backwards; hidden members never appear in a view.

use std::collections::{BTreeMap, BTreeSet};

use chorus_core::front::{Entry, Level, SubjectType};
use chorus_core::notify::{Announce, Audience, Ceiling, HiddenSubjects, MemberPolicy, Seen, TimeRule, displayed, view};
use proptest::prelude::*;

fn rule() -> impl Strategy<Value = TimeRule> {
    prop_oneof![
        Just(TimeRule::Exact),
        (1i64..120).prop_map(|round_min| TimeRule::Round { round_min }),
        (0i64..60).prop_map(|jitter_min| TimeRule::Jitter { jitter_min }),
        Just(TimeRule::PartOfDay),
        Just(TimeRule::Hidden),
    ]
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(2000))]

    #[test]
    fn displayed_is_bounded_and_monotonic(
        rule in rule(),
        tz in -720i32..840,
        steps in prop::collection::vec((0i64..6 * 3_600_000, 0i64..3 * 3_600_000, any::<u64>()), 1..30),
    ) {
        let mut t = 1_790_000_000_000i64;
        let mut delivered_prev = t;
        let mut prev: Option<i64> = None;
        for (gap, delay, draw) in steps {
            t += gap;
            // deliveries are in order per (follower, account)
            let delivered = (t + delay).max(delivered_prev);
            delivered_prev = delivered;
            let d = displayed(t, delivered, rule, tz, prev, draw);
            if let Some(at) = d.at {
                prop_assert!(at <= delivered, "{at} > delivered {delivered}");
                if let Some(p) = prev { prop_assert!(at >= p, "went backwards: {at} < {p}"); }
                prev = Some(at);
            }
        }
    }

    #[test]
    fn hidden_members_are_never_named(
        front in prop::collection::vec((0usize..6, 0usize..3), 0..8),
        hidden in prop::collection::btree_set(0usize..6, 0..6),
        someone in any::<bool>(),
    ) {
        let levels = [Level::Front, Level::Cocon, Level::Present];
        let front: Vec<Entry> = front.iter().map(|(m, l)| Entry {
            subject_type: SubjectType::Member, subject_id: format!("m{m}"), level: levels[*l], is_primary: false,
        }).collect();
        let policies: BTreeMap<String, MemberPolicy> = hidden.iter()
            .map(|m| (format!("m{m}"), MemberPolicy { announce: Announce::Nobody, ..Default::default() }))
            .collect();
        let ceiling = Ceiling {
            hidden_subjects: if someone { HiddenSubjects::Someone } else { HiddenSubjects::Omit },
            ..Ceiling::default()
        };
        let buckets = BTreeSet::new();
        let v = view(&front, &Audience { ceiling: &ceiling, follower_buckets: &buckets, policies: &policies });
        for s in &v {
            if let Seen::Subject { subject_id, level, .. } = s {
                prop_assert!(!policies.contains_key(subject_id));
                prop_assert!(ceiling.levels.contains(level));
            }
        }
    }
}
