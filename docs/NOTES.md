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
  Later on 2026-09-24 (commits 91a3ea5, ec96958, c3c5676): `.set` applied in place; projection
  statements cached (rusqlite re-prepares on every `conn.execute`; the cache is now 256); fresh
  entities skip clearing derived rows; rebuild loads ops in one query per page, fills
  `message_fts` in one pass and runs with a 256 MB page cache. Perf test ops are 30 s apart
  (~90 switches a day). **At 1M ops: ingest 4 465 ops/s (slowest 10k window 3 613): met.
  Rebuild 89.8 s: still 1.5× the 60 s budget** (message projection 57 s at 68 µs each, commit
  11 s, search index 5 s). Next: in a rebuild, count ops per entity up front and project
  single-op entities from the op in hand (no `for_entity` query + JSON parse); consider rebuilding
  into fresh tables instead of DELETE + INSERT (halves the WAL). The 1M test needs ~5 GB free
  for the database and its WAL; F: ran out, so run it with `CHORUS_PERF_DIR` on a roomier drive.
  After 5a91ab5 (single-op entities projected from the op in hand): **rebuild 84 s at 1M**
  (messages 45 s, commit 11 s, switches 7–11 s, search index 5 s, clear 1.4 s). Timings on this
  PC are noisy while gpt-6-sol builds in parallel (one ingest window fell to 261 ops/s in that
  run, average 3 817). Don't fold the front once at the end of a rebuild to save the switch
  time: review cards accumulate per switch (INSERT OR IGNORE, never withdrawn), a final fold
  alone would drop ones that later retracts made moot, and restore compares `front_review`.
  **Still over budget: rebuild** (~26 s per 100k, so ~4–5× the 60 s for 1M), because it replays
  `after_insert` op by op, each one re-running the model over its entity. A batch rebuild
  (project whole scopes in memory, bulk insert) is the fix.
  2026-09-24 (Claude): **63 s at 1M** after linking threads once at the end of a rebuild,
  decoding the log on a second read-only connection while the writer projects, and caching
  upsert SQL per column shape. Profile at 1M: message upsert 13 s, segments 6.6 s, authors 4 s,
  model + field building 6 s, commit 11 s (about half of it the WAL checkpoint, which has to
  happen anyway), search index 4.4 s, switches 6 s. Segment rows can't be skipped for plain
  messages: DATA_MODEL promises ≥ 1 segment per message and `v_message_segment` reads them.
  Then **57 s at 1M (budget met)**: the reader thread also runs the model for single-op
  entities (`prepare_single` → `Prepared`, written by `write`), taking ~6 s of model and field
  building off the writer. Margin is small; the commit (~10 s) and search index (~5 s) are next. Clients' core projector still
  refolds the front per front op (fine at their sizes; `front::append` is there when needed).
- 2026-09-23 gpt-6-sol — insights — `front::daily` expects a caller-supplied UTC offset. The web
  dashboard derives the account system's IANA zone with `Intl`, splits intervals at offset
  transitions in its wasm adapter, and lets core assign each segment to a local day. This keeps
  DST handling outside the pure core; clients using `front::daily` need the same transition-aware
  adapter to produce matching charts.
- 2026-09-23 gpt-6-sol — journals — Post ops and the core model call the parent field `reply_to`,
  while the SQLite projection column is `reply_to_id`; the server projector must map it explicitly.
  Cross-account publication later arrived through `GET /posts`; its audience is checked at read
  time through `posts::readable_sql`.
- 2026-09-23 gpt-6-sol — journals — The later `GET /posts` and `/posts/{id}` implementation now
  checks the stored post audience against active follows and live bucket assignments on every
  read. It omits `front_snapshot`, which could disclose an unrevealed front state even on an
  otherwise visible post. The read API now serves audience-checked attachments and present reaction
  detail; the reactor's op remains in the reactor's account scope, so owners see it through the
  post read view rather than their own replica.
- 2026-09-24 claude-opus-5.5 — perf — SPEC §9 sync budgets now have a test
  (`tests/sync_e2e.rs` `sync_budgets`, ignored, release): switch → other online device ~1 ms p95
  on loopback; a device back with 5 000 queued ops (+1 000 to fetch) fully synced in ~1.3 s.
  It was 11 s: every public `message.send` in `fan_out` ran `backfill_for_public_send`, whose
  `json_extract(payload,'$.message_id')` lookup scanned the scope's whole log — quadratic over a
  catch-up. Migration 0005 indexes that expression (the query must use the identical expression
  for SQLite to pick the index).
- 2026-09-24 claude-opus-5.5 — android — Testing the phone without touching real data: run the dev
  server (port 5251, ./data-dev), `adb reverse tcp:5251 tcp:5251`, create a device invite with
  `chorus-server --dev invite --kind device --account <id>`, and open
  `http://127.0.0.1:5251/i/<code>` in the app (`am start -n garden.vayne.chorus/.MainActivity
  -a android.intent.action.VIEW -d <link>`). Debug builds allow cleartext to loopback only
  (`app/src/debug/res/xml/network_security_config.xml`). Removing the reverse doesn't cut an open
  socket; stop the server to test offline queueing. The quick-switch grid reorders by recency after
  each switch, so re-read the screen before tapping again.
- 2026-09-24 claude-opus-5.5 — android — Notifications without a push server (debug builds):
  write a payload like the server's (`{"t":"message","kind":"mention","title":…,"text":…,
  "channel_id":…,"message_id":…,"scope":…,"reply_as":…}`) to `/data/local/tmp/p.json`, then
  `adb shell 'am broadcast -n garden.vayne.chorus/.data.DebugPushReceiver --es payload "$(cat
  /data/local/tmp/p.json)"'`. In Git Bash set `MSYS_NO_PATHCONV=1` or device paths get mangled.
  With Battery Saver on, background apps lose network: WorkManager stops SyncWork ("Constraints
  not met") and reruns it later, so a background reply syncs when the app opens or the saver ends.
- 2026-09-24 claude-opus-5.5 — perf — wasm size (SPEC §9: ≤ 300 KB gz; 260 KB after the feed
  filter). Code by crate (unstripped build, before wasm-bindgen): alloc 237 KB (generic
  collections monomorphised per type), chorus_core 196 KB (sync 36, feed 35, text 23, front 17,
  api 16, float formatting 16), serde_json 157 KB, core 150 KB, serde 143 KB. Fat LTO saves
  ~0.1 % (codegen-units is already 1). `wasm-opt` (binaryen 133, re-measured on the 267 KB gz
  build): `-Oz` 313 KB gz and `-Os` 312 KB — smaller raw, but its inlining compresses worse, as
  the 2026-09-23 note found; with inlining off (`-aimfs 0 -fimfs 0 -ocimfs 0`) `-Os` gives
  264 KB (−1 %), too little to add a tool to the build. It needs the Rust-default feature flags
  (`--enable-bulk-memory --enable-bulk-memory-opt --enable-nontrapping-float-to-int
  --enable-sign-ext --enable-mutable-globals --enable-reference-types --enable-multivalue`).
  The remaining levers are in the code: fewer serde-derived types crossing the wasm boundary
  (pass JSON strings through, as most exports already do), avoiding f64 formatting in core. To measure: build with `CARGO_PROFILE_WASM_STRIP=false` into
  a spare target dir and rank function bodies by the name section.
- 2026-09-24 claude-opus-5.5 (remote) — perf — **Rebuild at 1M: 63.8 s → ~31 s** on a 4-core
  Linux container (Xeon 2.1 GHz; ingest there ~7 400 ops/s, so this box is faster at ingest than
  the owner's PC but was over budget on rebuild). Found by timing everything, not just op kinds
  (`(replay in all)` vs the per-kind sum left 6 s unexplained):
  (1) **Cross-thread frees.** The reader thread decodes ops and prepares rows; the writer freed
  them, and glibc makes a free from another thread take the owning arena's lock, which the reader
  is busy allocating from: 5.5 µs per op (5.5 s), plus about as much again inside the message
  writes (the prepared rows). Applied batches now go back over a channel and are freed by the
  reader. Worth remembering for any producer/consumer thread pair in this codebase.
  (2) `DELETE FROM message_fts` on a plain FTS5 table takes it apart row by row (2.7 s): the
  table is dropped and created again from its `sqlite_master` SQL.
  (3) Per-switch daily totals are skipped during a rebuild and written once per account at the
  end (`rebuild_daily`; restore verification doesn't compare `front_daily`, it depends on "now").
  Two per-switch queries in `append_front` used `conn.query_row`, which prepares the SQL every
  time; through the statement cache the switch went 0.28 → 0.09 ms (live ingest gains too).
  (4) `BULK_INDEXES` (`message_channel_time`, `message_reply`, `message_author_member`,
  `msa_member`) are dropped for the replay and created again at the end: 3.6 s to build, ~7 s
  saved. Keeping `message_channel_time` live instead was 3 s worse.
  Tried and not kept: FTS5 `hashsize` 64 MB for the bulk insert (noise), a 1 GB page cache
  (−1.6 s, but too much memory for a small VPS). What's left at 1M: replay ~18 s (message writes
  12 s at 14 µs each), commit ~4.5 s, index builds 3.6 s, search index 3.5 s, clear 0.9 s.
  `CHORUS_PERF_DB=<file>` makes `tests/perf.rs` keep the ingested database and later runs only
  rebuild a copy (a 1M database is ~1.9 GB).
- 2026-09-24 claude-opus-5.5 (remote) — server — A rebuild refolded the front from the **whole**
  log, later ops included (`for_scope_kinds`, and the "is this switch retracted/amended" check),
  while live ingest had only seen the log so far. Switches, intervals and totals still converge,
  but review cards are never withdrawn, so a log with retracts or amends got different cards
  after a rebuild, and restore verification (which compares `front_review`) would have refused
  that backup. The replay now folds the log up to the op being projected (`REPLAYED_UPTO`);
  `tests/projection.rs` `in_order_switches_match_refolding` rebuilds and compares all four front
  tables. Entity rows don't need this: they're a function of the full op set either way.
- 2026-09-24 claude-opus-5.5 (remote) — core/server — A client's digest repair used to re-pull a
  scope without dropping anything, so a device holding ops it may no longer see (a channel whose
  `view` was taken away — R9 made that possible) never matched and re-pulled forever on every
  catch-up. The repair now sweeps what the re-pull didn't deliver and ends after one pass
  (SYNC §6.5). And `perms::can_sql` is spliced into queries that already use `c`, `m`, `s`;
  its own aliases are all `perm_*`, because a nested `channel c` silently shadowed the outer one
  (a DM message stopped reaching the other participant).
- 2026-09-24 claude-opus-5.5 — perf — The R1 rebuild changes measured on the owner's Windows PC
  (1M ops, release): **rebuild 58.7 s** (57 s before R1; budget 60 s), ingest 3 897 ops/s. The
  replay itself fell to 27.1 s, but the single rebuild transaction's **commit takes 20.0 s** here
  (it was small on the remote Linux box), plus indexes 4.6 s and search index 4.4 s. The win on
  Linux was eaten by Windows' commit of a ~GB WAL. Next lever: rebuild into a fresh database file
  with `journal_mode=OFF`/`synchronous=OFF` (nothing to protect until it's swapped in), then
  swap files atomically, instead of one giant WAL transaction on the live file.
- 2026-09-24 claude-opus-5.5 (remote) — server — Migrations must be idempotent (`IF NOT EXISTS`,
  delete-then-fill): `backup_cli.rs` `restore_migrates_a_snapshot_from_an_older_schema` fakes an
  old schema by setting `schema_version` back on a current database, so every later migration
  runs again. And the workspace sets `unsafe_code = "forbid"`, which a local `allow` can't lift:
  platform calls go through safe wrappers already in the lockfile (e.g. `rustix` for `statvfs`).
- 2026-09-24 claude-opus-5.5 (remote) — server — `chorus-server rebuild` now builds into a fresh
  file (`project::rebuild_swap`): the schema by migration, the op log and server tables copied
  through the source connection (`ATTACH`), `journal_mode=OFF`/`synchronous=OFF`, the usual
  rebuild, `quick_check` + row counts of everything copied, `journal_mode=WAL`, then swap
  (old aside → new in → old deleted; `open_and_migrate` puts an aside file back if a swap was cut
  short). Traps: (1) the rebuild reads the log on a second connection, and a second connection
  to a journal-less file blocks the writer, so the locked *source* connection is lent to the
  reader thread (`LOG_READER`); (2) the source is held with `locking_mode=EXCLUSIVE`, which fails
  at once if a server has the file open (even idle), so a running server can't race the swap;
  (3) the old file's `-wal` must be gone before the new file takes its name, or SQLite would
  replay it into the new file. 1M ops on the remote Linux box: 35.9 s in place (commit 5.8 s) →
  31.3 s swapped (commit 0.3 s, copy 4.1 s, check 4.0 s, replay 16.9 s instead of 20.1 s without
  the WAL). The owner's Windows commit was 20 s, so the gain there should be larger.
