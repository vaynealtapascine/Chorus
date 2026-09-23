# Notes

Non-obvious findings from implementation: library traps, platform limits, reasons a design had
to change. Newest last. Format: `YYYY-MM-DD agent — area — finding`.

- 2026-09-23 claude-opus-5.5 — sync — The convergence simulator found three protocol bugs before any
  server code existed: (1) acks must carry the full stamp (account, device, received_at) or local
  projections differ from the server's; (2) after a restore, old-epoch seqs are reused, so
  reconcile can't pick ops by seq — re-push everything; (3) fire-and-forget reconcile pushes are
  lost on disconnect — demote confirmed ops into the outbox instead. Digest-mismatch repairs occur
  only around restores (race between other devices' restore pushes and Caught); they self-heal.
- 2026-09-23 claude-opus-5.5 — text — The markup round-trip property test drove the serializer
  design: `\&` empty separator (Telegram uses `\r` for the same problem), whitespace at entity
  edges, url/mention atoms that would re-grow, code blocks only at top level, escape-aware scans.
- 2026-09-23 claude-opus-5.5 — tooling — Git Bash here mangles backslashes in heredocs passed to
  python; for Rust source edits use the Edit tool, not sed/python.
- 2026-09-23 claude-opus-5.5 — tooling — PowerShell: `$x = if (...) { @() } else { @('--release') }` unwraps
  the one-element array into a string, and `@x` then splats it as single characters (cargo saw
  `-`). Use `$x = @(if (...) { '--release' })`. Also avoid naming a variable `$profile`.
- 2026-09-23 claude-opus-5.5 — web — The browser pane's colour-scheme emulation doesn't fire
  `matchMedia` change events; reload after switching it when testing theme-dependent colours.
- 2026-09-23 claude-opus-5.5 — server — Projections re-project the touched entity through
  `chorus_core::model` (SQL = model by construction; `tests/projection.rs` checks 40 scrambled
  seeds and that `rebuild` is byte-identical). Known shortcuts to revisit when they cost:
  (1) *done 2026-09-23, see below:* the front was refolded in full per front op;
  (2) `front_daily` uses the account's latest UTC offset, not the system's IANA timezone (add
  `jiff` with tzdb on the server when DST-exact local days matter);
  (3) digests are computed per Hello by scanning a scope's op ids (cache per scope if slow).
- 2026-09-23 claude-opus-5.5 — server — FTS5 tables are plain (own copy of the text, rowid =
  message rowid), not external-content: a projection rewrite can't supply the old text that
  external-content deletes need.
- 2026-09-23 claude-opus-5.5 — android — UniFFI: an error variant field named `message` clashes with
  Kotlin's `Throwable.message` ("overload resolution ambiguity"); name it `reason`. Changing any
  exported type changes UniFFI checksums, so rebuild the .so and the bindings together.
- 2026-09-23 claude-opus-5.5 — web — Wasm size: `--profile wasm` (opt-level "z", panic=abort, all
  crates) gives ~209 KB gzipped vs ~308 KB for `release`; `wasm-opt -Oz` (binaryen 132) made the z
  build *larger*, so it isn't used. Per-package opt-level on the wrapper crate alone does nothing —
  the size is in chorus-core.
- 2026-09-23 claude-opus-5.5 — ops — Deploys swap the server binary without admin rights: Windows
  lets you rename a running exe; the server (CHORUS_RESTART_ON_CHANGE=1, set by install.ps1)
  notices its binary changed and exits, and NSSM starts the new one.
- 2026-09-23 gpt-6-sol — android — UniFFI bindings and `.so` files are ignored build outputs.
  After adding exports to `chorus-ffi`, run `scripts/build-android-core.ps1 -Debug` (or release)
  before Gradle; merely rebuilding the host Rust crate leaves Kotlin with stale generated APIs.
  Android lint/unit tests currently need AndroidX Activity 1.8.2 artifacts not in the local Gradle
  cache; dl.google.com was unreachable, though offline `assembleDebug` passed.
- 2026-09-23 gpt-6-sol — android — `cargo ndk build -p chorus-ffi` also attempts to link the
  host-only `uniffi-bindgen` binary for Android; pass `--lib` for target builds. The host bindgen
  run must use the same debug/release profile as its input DLL. `CARGO_TARGET_DIR` can point at
  a roomy drive when C: cannot hold another Rust build; the Android build script and JVM test
  library path now honor it. Set `CHORUS_GRADLE_BUILD_DIR` for Gradle's project outputs on that
  drive too (the root build script honors it); generated bindings and `.so` source outputs remain
  in the repository.
- 2026-09-23 gpt-6-sol — android — UniFFI's default Kotlin cleaner references
  `java.lang.ref.Cleaner`, which Android exposes only at API 33. `chorus-ffi/uniffi.toml` sets
  `disable_java_cleaner = true`, making generated bindings use the JNA cleaner on minSdk 29 and
  removing the Android lint errors. Regenerate bindings after changing this file.
- 2026-09-23 claude-opus-5.5 — android — Finding UnifiedPush distributors with
  `queryBroadcastReceivers` returns nothing on Android 11+ unless the manifest declares a
  `<queries>` intent for `org.unifiedpush.android.distributor.REGISTER`. ntfy relays binary UP
  posts as base64 (`"encoding":"base64"`) and its app hands them to us as `bytesMessage`.
- 2026-09-23 claude-opus-5.5 — tooling — Never put Windows paths or `\0` inside ordinary
  Python string literals in helper scripts: `\a`, `\b`, `\r`, `\0` silently become control bytes
  (this corrupted `deploy.ps1` and a Rust source once). Use raw strings (`r'...'`) or the Edit tool.
- 2026-09-23 claude-opus-5.5 — android — `settings.gradle.kts` limits `google()` to
  androidx/com.android/com.google groups, so libraries from Maven Central (e.g. `org.json` for JVM
  tests) resolve online even when dl.google.com is unreachable; Google artifacts still need to be
  in the cache (`--offline`). Android's own `org.json` is a stub in JVM unit tests — tests use the
  real one via `testImplementation(libs.orgjson)`.
- 2026-09-23 gpt-6-sol — tooling — On this PowerShell PTY, `python scripts/verify.py` may fail
  before running checks because cp1252 cannot print its Unicode progress symbols. Set
  `PYTHONIOENCODING=utf-8` for the verifier. Android lint also needs an escaped drive colon in
  ignored `android/local.properties`, such as `sdk.dir=C\:/Users/pcuser/AppData/Local/Android/Sdk`.
- 2026-09-23 gpt-6-sol — emoji — Retired custom emoji still need their blob readable to every
  authenticated account: text entities and reactions store a stable emoji ID and must keep
  rendering after a name is retired or reused. The active `/emoji` catalogue excludes retired
  rows, while sync keeps the tombstoned row. Existing web device records lack `is_admin`; the
  client renews their signed session once to learn it before showing the emoji manager.
- 2026-09-23 gpt-6-sol — sharing — `follow.ceiling = {}` is an intentional "inherit default";
  setting every newly accepted follower to the Gentle preset would mask
  `account.settings.follow_ceiling`. Bucket assignments use follower **account IDs** in the
  element-set payload, while follow IDs are used only for per-follow ceiling ops.
- 2026-09-23 claude-opus-5.5 — server — Ingest budget (SPEC §9, `tests/perf.rs`, ignored; run it
  with `--release -- --ignored --nocapture`). Two projections were quadratic: every front op
  refolded the account's whole front (9.6 ms per switch at 900 switches, growing), and every
  `read.mark` re-projected every read op of its channel (4.9 ms at 2k). Now:
  (a) a switch-like op that sorts after every folded one and that nothing amends or retracts is one
  `front::append` step (the fold's loop body, shared; `front_props` checks append = fold) on the
  stored last `resulting_front` and open intervals. Daily totals are redone from the first local
  day of anything open, or all days if the offset moved; review cards pair the new switch with
  the window. Anything else still refolds. `tests/projection.rs` compares against refolding on
  every op (`project::set_front_fast_path(false)`).
  (b) `read_state` keeps the running best mark and the manual set (migration 0004); only a newer
  `read.set` rescans its key.
  Traps found on the way: a `kind IN (…) AND scope = ?` lookup on `op` picked the scope index and
  scanned the whole account (steer it with `+scope`); `ORDER BY occurred_at, op.hlc` over a join
  sorted every switch (take `max(occurred_at)` first).
  Result at 100k ops: ~2 500 ops/s flat (switch 0.8 ms, read mark 0.08 ms, message 0.29 ms).
  At 1M ops (2026-09-24): 1 734 ops/s on average, drifting to ~1 150 at the end; still short.
  What's left is `member.set` at 8 ms (every `.set` re-runs the model over all ops of that entity,
  ~500 edits per member here) and `front.switch` at 1.1 ms. Next step: apply a `Set` op straight
  onto the stored row's LWW `clocks` when the row exists (same `lww::apply_fields` as the model).
  **Still over budget: rebuild** (~26 s per 100k, so ~4–5× the 60 s for 1M), because it replays
  `after_insert` op by op, each one re-running the model over its entity. A batch rebuild
  (project whole scopes in memory, bulk insert) is the fix. Clients' core projector still
  refolds the front per front op (fine at their sizes; `front::append` is there when needed).
