#[cfg(feature = "editor")]
#[test]
fn editor_source_map_tracks_every_utf16_unit_through_markup() {
    use chorus_core::text::{NoNames, parse, parse_mapped};
    for source in [
        "😀 **@Kai** / @Kai :wave:",
        "@K**ai** and \\@Kai",
        "\\*😀\\* \\&x",
        "> 😀 hi\n>\n> **bye**\nnormal",
        ">> a\n>> b\n> c",
        "`\\`😀` ```lang\nx😀\n```",
        "[😀 **text**](https://example.org/a)",
        "**a _b_ c**",
        "> ```\n> 😀code\n> ```",
    ] {
        let (rich, sources) = parse_mapped(source, &NoNames);
        assert_eq!(rich, parse(source, &NoNames));
        let raw: Vec<_> = source.encode_utf16().collect();
        let plain: Vec<_> = rich.text.encode_utf16().collect();
        assert_eq!(plain.len(), sources.len());
        for (unit, at) in plain.iter().zip(sources) {
            assert_eq!(*unit, raw[at as usize], "{source:?}");
        }
    }
}
