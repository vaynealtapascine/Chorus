//! Malformed payloads never break a client's projection. Ops of every kind in the catalogue with
//! payloads of the wrong shape (wrong types, missing keys, dangling or mismatched ids) that pass
//! `op::validate` — the same check the server runs, so these are ops a device may really hold —
//! must project without panicking, to the same state whatever order they arrive in, and the
//! incremental projector must agree with the reference projection.
//!
//! The server side of this is `chorus-server/tests/ingest_fuzz.rs`.

use chorus_core::hlc::Hlc;
use chorus_core::id::new_id;
use chorus_core::model;
use chorus_core::op::{self, CATALOGUE, Known, Op, ScopeKind};
use chorus_core::projector::Projector;
use chorus_core::time::TimeSource;
use proptest::prelude::*;
use serde_json::{Map, Value, json};

const NOW: i64 = 1_790_000_000_000;
const KEYS: &[&str] = &[
    "account_id",
    "allow",
    "attachments",
    "authors",
    "channel_id",
    "deny",
    "entities",
    "field_id",
    "front",
    "key",
    "length",
    "member_id",
    "message_id",
    "offset",
    "parent_id",
    "parent_message_id",
    "post_id",
    "reply_to",
    "repost_of",
    "role",
    "roles",
    "segments",
    "switch_id",
    "target_id",
    "target_type",
    "text",
    "value",
    "visibility",
    "kind",
    "name",
    "members",
    "member_ids",
    "fronters",
    "started_at",
    "ended_at",
    "occurred_at",
    "cw",
    "quote",
    "forward_of_id",
    "forward_snapshot",
    "blob_hash",
    "emoji",
    "mode",
    "group_id",
    "tags",
    "title",
    "items",
];

fn pool() -> Vec<String> {
    (0..8u8).map(|i| new_id(NOW as u64, [i + 10; 10])).collect()
}

fn value() -> impl Strategy<Value = Value> {
    let leaf = prop_oneof![
        Just(Value::Null),
        any::<bool>().prop_map(Value::Bool),
        prop_oneof![Just(0i64), Just(-1), Just(i64::MAX), Just(i64::MIN), Just(NOW), any::<i64>()]
            .prop_map(|n| json!(n)),
        prop_oneof![Just(1.5f64), Just(1e300)].prop_map(|f| json!(f)),
        prop::sample::select(pool()).prop_map(Value::String),
        prop_oneof![Just(String::new()), Just("🌌 a\u{0}b".to_string()), Just("message".to_string()), "[a-z_]{0,8}"]
            .prop_map(Value::String),
    ];
    leaf.prop_recursive(3, 24, 6, |inner| {
        prop_oneof![
            prop::collection::vec(inner.clone(), 0..5).prop_map(Value::Array),
            prop::collection::vec((prop::sample::select(KEYS), inner), 0..5)
                .prop_map(|kv| Value::Object(kv.into_iter().map(|(k, v)| (k.to_string(), v)).collect())),
        ]
    })
}

fn op_strategy() -> impl Strategy<Value = Op> {
    (
        0..CATALOGUE.len(),
        prop::collection::vec((any::<prop::sample::Index>(), value()), 0..7),
        prop::option::weighted(0.9, prop::sample::select(pool())),
        prop::option::weighted(0.2, prop::sample::select(pool())),
        any::<[u8; 10]>(),
        0u64..5_000,
    )
        .prop_map(|(k, fields, entity, member, rand, dt)| {
            let spec = &CATALOGUE[k];
            // the kind's own fields four times as often as the shared keys
            let names: Vec<&str> = (0..4).flat_map(|_| spec.fields.iter()).chain(KEYS).copied().collect();
            let mut payload = Map::new();
            for (i, v) in fields {
                payload.insert(i.get(&names).to_string(), v);
            }
            let scope = match spec.scope {
                ScopeKind::Account => format!("account:{}", new_id(1, [1; 10])),
                ScopeKind::Space => format!("space:{}", new_id(1, [3; 10])),
                ScopeKind::Server => "server".into(),
            };
            let at = NOW as u64 + dt;
            Op {
                id: new_id(at, rand),
                kind: spec.kind.into(),
                v: 1,
                scope,
                entity_id: entity,
                hlc: Hlc::new(at, (dt % 3) as u16, 1),
                device_at: at as i64,
                tz_offset_min: 0,
                mono: None,
                boot_id: None,
                time_source: TimeSource::Auto,
                seen_seq: 0,
                member_id: member,
                payload: Value::Object(payload),
                seq: None,
                account_id: Some(new_id(1, [1; 10])),
                device_id: Some("d".into()),
                occurred_at: Some(at as i64),
                received_at: Some(at as i64),
            }
        })
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 256, failure_persistence: None, ..ProptestConfig::default() })]

    #[test]
    fn malformed_payloads_project_the_same_in_any_order(
        ops in prop::collection::vec(op_strategy(), 1..50),
        seed in any::<u64>(),
    ) {
        let ops: Vec<Op> = ops.into_iter().filter(|o| matches!(op::validate(o), Ok(Known::Yes(_)))).collect();
        let forward = model::project(ops.iter()).canonical();
        let mut shuffled = ops.clone();
        let mut s = seed | 1;
        for i in (1..shuffled.len()).rev() {
            s ^= s << 13; s ^= s >> 7; s ^= s << 17;
            shuffled.swap(i, (s % (i as u64 + 1)) as usize);
        }
        prop_assert_eq!(&forward, &model::project(shuffled.iter()).canonical(), "order changed the projection");
        let mut p = Projector::new();
        p.sync(shuffled.iter());
        prop_assert_eq!(&forward, &p.projection().canonical(), "the incremental projector disagrees");
    }
}
