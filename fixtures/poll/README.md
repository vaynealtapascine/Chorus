# Poll conformance (D-081, D-086)

These use the normal `{description, fn, args, expect}` fixture format. Run them with
`cargo test -p chorus-core --test fixtures` (also part of the verification gate).
Expected results are hand-authored, not blessed from the implementation.

The Rust fixture runner provides four **test-only** adapters for helpers without bridge
exports. They add no production code or wasm exports:

| `fn` | `args` | Result |
| --- | --- | --- |
| `poll_definition` | `[definition]` | `{options: [option IDs in order], multiple: bool, closes_at?: ms}` (`closes_at` only when set, D-136), or `null` for an invalid definition |
| `poll_closed_at` | `[definition, [early close ms or null, …], now]` | `{closed_at: [poll::closed_at for each], open_at: [poll::is_open at now for each]}` (D-136) |
| `poll_vote` | `[definition, choices]` | The choices in input order after `definition`, `choices` and `check_vote` succeed; otherwise `{error: true}` |
| `poll_tally` | `[definition, vote arrays, channel ID or null]` | The counts-only `tally_payload` from `tally`; malformed choice arrays are filtered through `choices` first, as at ingestion |

`validate_op` vectors exercise the actual envelope/payload validator: valid parent creation,
invalid definitions, immutable poll edits, ordinary text edits, vote shape and account scope.
`project_lww.json` uses the existing `project` API and pins the complete reference projection:
separate `<poll>|<member>` registers, replacement, withdrawal, full-HLC ordering, duplicate
delivery, and independent latest message/post tallies (including replacement by zero counts).
`project_close_earliest*.json` pin that the earliest early close wins in either order, whatever the HLCs (D-136).

The TypeScript and Kotlin clients use the Rust wasm/UniFFI core for validation and projection;
they do not reimplement these poll algorithms. Their poll helpers decode projected rows,
construct composer payloads and toggle local selections. Therefore these vectors run in the
Rust runner only; a future fallback for these algorithms must consume the same vectors
(SYNC.md §9.1). No Android source or runner change is needed.

These vectors do not replace server authorization/privacy tests: member ownership, audience
eligibility, server-only tally authorship and cross-account delivery are covered by
`crates/chorus-server/tests/polls.rs`.
