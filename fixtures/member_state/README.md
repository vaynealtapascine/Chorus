# Member state conformance (D-122, D-138, D-159)

The normal `{description, fn, args, expect}` format, run by `cargo test -p chorus-core --test
fixtures`. Expected results are hand-written, not blessed.

The Rust runner has three **test-only** adapters for `chorus_core::member_state` (no wasm or
UniFFI export, so no size cost; the clients keep their own readers and run the `parse_*` vectors):

| `fn` | `args` | Result |
| --- | --- | --- |
| `member_state_parse` | `[value]` | `{tags, note, at, clear_on_leave, audience}` (audience normalised: `{mode}` or `{mode: "buckets", bucket_ids}`, private when absent or malformed), or `null` when the value holds no state |
| `member_state_shown` | `[value, [[start_at, end_at or null], …]]` | `{since, shown}`: `since` is the earliest start among the open intervals, `shown` is `shown_own` |
| `member_state_shared` | `[value]` | the part friends may see, `{tags, note?}`, or `null` |

`validate_*` use the real `validate_op` (`op::validate_new`) on a `pref.set` of a
`member_status:` key.

Client readers: web `fronting-display.ts` `status()` (`web/src/lib/fronting-display.test.ts`) and
Android `FrontingDisplay.status()` (`FrontingDisplayTest`) read every `parse_*` case to the same
tags, note, at, clear-on-leave and audience.
