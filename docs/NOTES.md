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
  design: `\&` empty separator (Telegram uses `
` for the same problem), whitespace at entity
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
  (1) the front is refolded in full per front op (fine for thousands of switches; make it
  incremental from the op's time when accounts reach tens of thousands);
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
- 2026-09-23 gpt-6-sol — insights — `front::daily` expects a caller-supplied UTC offset. The web
  dashboard derives the account system's IANA zone with `Intl`, splits intervals at offset
  transitions in its wasm adapter, and lets core assign each segment to a local day. This keeps
  DST handling outside the pure core; clients using `front::daily` need the same transition-aware
  adapter to produce matching charts.
- 2026-09-23 gpt-6-sol — journals — Post ops and the core model call the parent field `reply_to`,
  while the SQLite projection column is `reply_to_id`; the server projector must map it explicitly.
  The existing server still has no `GET /posts` or follower post view, so a post's visibility is
  stored but cross-account publication is not yet served. Any future route must apply the
  follower-view privacy ceiling before returning posts or reactions.
