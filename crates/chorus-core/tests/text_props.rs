//! Property tests for the markup parser and serializer.

use chorus_core::text::*;
use proptest::prelude::*;

struct Names;
impl Resolver for Names {
    fn mention(&self, name: &str) -> Option<(MentionTarget, Option<String>)> {
        match name {
            "kai" => Some((MentionTarget::Member, Some("m1".into()))),
            "front" => Some((MentionTarget::Front, None)),
            _ => None,
        }
    }
    fn emoji(&self, name: &str) -> Option<String> {
        (name == "wave").then(|| "e1".into())
    }
}

/// Markup-heavy random input.
fn src() -> impl Strategy<Value = String> {
    let atoms = prop_oneof![
        Just("*"),
        Just("**"),
        Just("_"),
        Just("__"),
        Just("~~"),
        Just("||"),
        Just("`"),
        Just("```"),
        Just("["),
        Just("]"),
        Just("("),
        Just(")"),
        Just("](https://a.b)"),
        Just("> "),
        Just(">> "),
        Just("\n"),
        Just(" "),
        Just("\\"),
        Just("\\&"),
        Just("@kai"),
        Just("@x"),
        Just(":wave:"),
        Just(":"),
        Just("https://x.y/z"),
        Just("a"),
        Just("bc"),
        Just("🌌"),
        Just("é"),
        Just("snake_case"),
        Just("rust\n"),
    ];
    proptest::collection::vec(atoms, 0..24).prop_map(|v| v.concat())
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(2000))]

    #[test]
    fn entities_in_bounds_and_sorted(s in src()) {
        let r = parse(&s, &Names);
        let len = utf16_len(&r.text);
        for e in &r.entities {
            prop_assert!(e.length > 0 && e.end() <= len, "{:?} out of bounds in {:?}", e, r.text);
        }
        let mut sorted = r.entities.clone();
        sort_entities(&mut sorted);
        prop_assert_eq!(sorted, r.entities);
    }

    #[test]
    fn serialize_then_parse_is_stable(s in src()) {
        let a = parse(&s, &Names);
        let m = to_markup(&a);
        let b = parse(&m, &Names);
        prop_assert_eq!(&a, &b, "src={:?} markup={:?}", s, m);
    }
}
