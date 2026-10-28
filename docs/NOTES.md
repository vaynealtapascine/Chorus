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
- 2026-09-25 claude-opus-5.5 — server — R19 re-measured on the owner's Windows PC (release build,
  1M ops, `tests/perf.rs` with `CHORUS_PERF_DB`): in place 63.2 s (commit 20.2 s); into a fresh
  file and swap 58.5 s (commit 0.4 s, swap 0.1 s). The commit problem is gone, but the replay
  itself takes ~58 s here (31 s on the remote's Linux box), so the 60 s budget holds by a small
  margin on Windows. Set `CHORUS_PERF_DIR` to a roomy drive: the working copy (~2 GB plus WAL)
  goes to the temp folder otherwise, and a failed run leaves it there (it filled C: once).
- 2026-09-25 gpt-6-sol — Android V8 offline retention — `data/Blobs.kt` currently puts fetched
  `chat`, `avatar` and other blobs under `Context.cacheDir`. Android may evict that directory,
  so merely walking the projection and calling `Blobs.file` cannot fulfill “Keep everything on
  this device.” Add a durable kept-files location under `filesDir` for the opt-in fill, and make
  blob reads check it before the evictable cache. Reuse the web `sync/keep.ts` hash catalogue:
  live attachments (thumbnail plus original up to 20 MB), member/member-group avatars and custom
  emoji. Deduplicate hashes, limit concurrent downloads to three, and report missing files.
  Existing queued uploads must remain in their separate pending directory.
- 2026-09-25 claude-opus-5.5 — wasm size (R25) — The web core went from 288 to 241 KB gz (the
  SPEC §9 budget is 300; R25's target was ≤ 250). Measured with `twiggy top` on an unstripped
  build (`CARGO_PROFILE_WASM_STRIP=false`); gzip -9 of the `wasm-bindgen` output, as `verify.py`
  measures it. Serde was ~40 % of the code, and most of it was copies, not logic:
  - **Internally tagged enums** (`#[serde(tag = …)]`) buffer input into serde's private
    `Content` tree, with a copy of that code per enum and per input type (~150 KB raw). They
    now derive the plain externally tagged form (`#[serde(remote = "Self")]`), and `tagged!`
    (`chorus_core::tagged`) adds impls that write the tag first (same bytes) and read through a
    `serde_json::Value`. `flatten` does the same buffering, so `text::Entity` reads by hand.
    Trap: with `remote = "Self"`, a path call like `EntityKind::deserialize(v)` is the
    *inherent* externally tagged function. Call `<EntityKind as Deserialize>::deserialize`.
    **−31 KB gz.**
  - **One deserializer per type.** `api::parse` goes text → `Value` → `T`, so a type isn't
    compiled for both `from_str` and `from_value` (core payload code already used
    `from_value`). −4 KB gz. But bulk op lists stay on `from_str` (`parse_ops`): through a
    `Value`, opening a year of 100k ops was 9.7 → 11.7 s cold.
  - **Sorts.** Every `sort_by` over a new type or closure is its own ~4 KB copy of the standard
    sort, and `collect()` into a `BTreeMap`/`BTreeSet` sorts too. `chorus_core::sort` (`by`,
    `by_key`, `ord`, `map`, `set`) sorts a list of indices through one shared comparator call on
    wasm, and inserts one by one instead of collecting. Elsewhere it is the plain std call, so
    the server's hot paths are unchanged. −13 KB gz. Use these helpers for new sorts in core.
  - **What didn't help:**
    - `wasm-opt -Oz` (binaryen 132): the raw file is 15 % smaller, but gzip is ~20 % bigger;
      even a plain round trip through binaryen grows it after gzip.
    - `lto = "fat"`: no change.
    - `opt-level = "s"`: +15 %.
  - **Left, if more is ever needed:**
    - `core` float formatting (16 KB raw), not called from our code.
    - `serde_json::Value` serialization (26 KB raw).
    - Visitors and field identifiers generated per type (~150 KB raw): the real cost of
      derive.
- 2026-09-25 claude-opus-5.5 — server rebuild (R26) — A 1M-op `rebuild` into a fresh file went
  from 58.0 s to ~40–41.6 s on the remote Linux box (4 cores; the box changed mid-task, so the
  baseline is HEAD re-measured on the same box). The replay went from 32.0 s to 16.3 s. What's
  left is mostly disk-bound and varies ±2 s per run here: copy 4.5–8 s, check ~5 s, indexes ~5 s,
  search index ~4 s. Profiled with `perf` (linux-tools, frame pointers, `line-tables-only`).
  - **Writer, the critical path:**
    - Reactions counted as ops of their message's entity, so 60k reacted-to messages took the
      slow re-read path. Element-set kinds no longer count toward "multi".
    - `entity()` reads the whole log, so an edited message was fully projected at each of its
      ops. A rebuild now projects each entity row once (`PROJECTED`).
    - New messages are written in runs (`write_new_messages`, multi-row INSERTs of up to 64 rows,
      power-of-two chunks). Their derived rows are computed with the row (`MessageRows`).
    - Migration 0009 makes the six per-item key tables `WITHOUT ROWID`: one B-tree per row
      instead of two.
    - Read-state and a few other per-op queries went through the statement cache
      (`query_cached`). Before, SQLite re-prepared them on every op.
    - Reactions read only their set ops (`for_entity_of_kinds`).
  - **Reader:**
    - Row preparation and JSON payload parsing run on `cores − 2` worker threads.
    - The next log slice is read while the last one is prepared, so the writer no longer waits.
  - **Everywhere:**
    - mimalloc as the global allocator. Malloc and free were ~45 % of the reader and ~20 % of
      the writer.
    - SQLite built without memory statistics and memory management (`.cargo/config.toml`):
      −12 %.
    - The fresh file uses 8 KB pages. 16 KB was a hair faster but writes bigger WAL frames for
      every live commit after the swap.
    - The copy skips CHECK constraints the source already enforced (`ignore_check_constraints`).
  - **What didn't help:**
    - Copying the log without its indexes and building them afterwards: the copy got 3 s faster,
      but building the indexes (the `json_extract` one especially) took 4.5 s.
    - `PRAGMA threads` for index builds: no change.
  - A third of this box's time is `pwrite` from the page cache spilling mid-transaction. Those
    bytes would be written at commit anyway, so a bigger cache only moves them. The owner's SSD
    should show more of the CPU gains.
  - `tests/projection.rs` now also compares segments, segment authors, mentions, attachments and
    replies. Its generator makes one-op messages, so the batched path is covered: skipping the
    mention inserts fails it.
- 2026-09-25 claude-opus-5.5 — REST fuzzing (R28, `tests/rest_fuzz.rs`) — Every route in
  app.rs (76, read the way api-check.py reads them) gets random and malformed path ids, queries,
  bodies (raw junk, a 3 MB text, wrong content types, and "plausible" bodies with real keys and
  both accounts' ids). Callers: anonymous, a device session, an admin, another account, and an
  API token of each scope. Seven seeds × 13 500 requests (~15 % succeed) gave no 5xx, no panics,
  no leaks and no change to the other account's rows. The traps were all in the harness:
  - A request body the server doesn't read, because it refused first (413, 401): the client
    then gets "broken pipe" writing it. That's expected for oversized bodies.
  - A GET with a body plus a large response: the server closes over unread bytes, TCP sends a
    reset, and the client sees "connection reset" mid-response. The fuzzer sends no GET bodies.
  - Pooled connections compound both, so the fuzzer doesn't pool.
  - API tokens only authenticate as `chorus_…` (api_data.rs). A fuzzer that makes up its own
    tokens silently tests as anonymous, so the test first checks its oracle sees the markers.
  - The chaos test's oracle is per op for sync. For REST, unique marker texts in private rows
    and private ids (skipped when the request named them) are the direct check.
- 2026-09-25 claude-opus-5.5 — web under failure (R29, `web/e2e/failure.spec.ts`) — A real
  browser, with the server `kill -9`ed, found three bugs:
  1. **Two tabs of one browser lost messages.** Every tab ran its own replica and socket on the
     same IndexedDB, and each saved the whole metadata. The tab that saved last didn't list
     another tab's unsent op in the outbox order, so that op was never sent, even after a
     reload. The fix is the Web Locks leader CLIENTS §4.1 describes (other tabs hand ops over a
     `BroadcastChannel`), plus a core safety net: opening relists any unconfirmed op the
     metadata forgot.
  2. **A failed save (storage full) dropped its changes.** `takeChanges` had already cleared
     them. Ops written then and not yet sent were gone after a reload. They're now kept and
     retried with the next save, and the app says the storage is full.
  3. **A history request that died** (server gone mid-request) was an unhandled rejection with
     nothing shown. Also, the chat read `sync.windowed` / `sync.status` straight from the client,
     which isn't reactive state, so the older-messages button didn't update. They're copied into
     `$state` on each change now.

  Test traps:
  - `page.goto('/#/chat')` can report ERR_ABORTED, because the app rewrites the hash at once;
    click through instead.
  - A test's message text must not contain the warning it waits for.
  - The failure suite keeps its own console-problem list, or v1's "no console errors" would see
    the killed server's refused requests.
- 2026-09-26 claude-opus-5.5 — server — R26 re-measured on the owner's Windows PC (release, 1M
  ops, `tests/perf.rs`, `CHORUS_PERF_DIR` on F:): **rebuild into a fresh file and swap 58.5 →
  27.4 s** (commit 0.2 s, swap 0.1 s), half the 60 s budget. The in-place path went the other way,
  63.2 → 78.9 s, 52 s of it the single commit (the batched message writes make one bigger
  transaction for SQLite to write back into the WAL). Only purge and restore still rebuild in
  place, on copies; if that ever matters, give them the swap path too.

- 2026-09-26 claude-opus-5.5 — server — **Early answers reset connections (R35, `drain.rs`).**
  `rest_fuzz` failed about half its runs on the owner's PC with os error 10053/10054 on a
  different request each time. The R28 entry above called the "broken pipe" a harness trap; it
  was a server behaviour real clients hit. Routes that answer before reading the body (401 from a
  handler that authenticates first, 415 from `Json` on a wrong content type, 404, 429 from the
  rate limiter) left the body unread. hyper drains only what has already arrived, then closes.
  The rest of the body reaches a closed socket, the TCP stack answers with a reset, and
  **Windows discards the answer the client had already received**: the client sees "connection
  aborted" instead of a 401. Linux keeps the answer readable, which is why only the PC saw it.
  - Fix: an outermost layer keeps a handle on each request body. When the handler has answered
    and dropped a body it didn't finish, a background task reads and discards the rest (≤ 8 MiB,
    ≤ 10 s). The connection then stays open for the next request.
  - Tests: `tests/early_answer.rs` sends the body late over a raw socket and fails on the reset
    the late part meets. `rest_fuzz` pools connections again, as browsers do, and no longer
    excuses failed body writes. Without the layer both fail on Linux in every run.
  - Ruled out: pooled-connection reuse (the fuzzer didn't pool) and TIME_WAIT churn (the
    client closes first, so nothing collides on the server side).
- 2026-09-27 gpt-6 — read-state projection — A rescan fetched matching read-op IDs and used
  `.ok().flatten()` for each op-log lookup. A row read failure silently omitted that mark or
  manual set, so the saved read position could differ from the op log. The scan now returns the
  error and leaves the previous projection intact. For the regression test, corrupt a typed
  column such as `device_at`: the op decoder deliberately defaults a malformed HLC string, so
  changing `hlc` would not exercise the read-error path.

- 2026-10-02 gpt-6 — **PluralSpec local editor** (`tools/pluralspec-editor`). Its API is restricted
  to existing text files beneath `spec/`, requires a per-run link key, rejects foreign origins
  and unknown Host headers, and only accepts loopback/RFC1918 clients. No changes to Windows
  services or firewall. Runtime links and pre-save byte-for-byte backups are gitignored under
  `.pluralspec-editor/`; README documents restart and restore. Browser drafts retain the file
  revision they started from, so recovering a draft cannot silently overwrite an external edit.
  - Browser textareas normalize CRLF to LF. Compare normalized source in the UI and restore the
    original file's newline convention on save; otherwise merely opening a Windows file looks
    like an edit. The isolated browser smoke test used CRLF and confirmed it survives a save.
  - Node 22's `fetch` normalizes/ignores a supplied Host header. The DNS-rebinding regression uses
    `http.get` instead, so it actually exercises a foreign Host value.
  - The existing search WIP causes two `web/src/lib/search.test.ts` failures (`after:150`,
    `before:1500`). Verify this tool in a clean HEAD checkout with the tool copied in, rather
    than changing or hiding that unrelated work. Shared build/dependency caches stay on F:.

- 2026-10-24 GPT-6-sol — UI1 build/review — In isolated Windows worktrees, set CARGO_TARGET_DIR
  and CHORUS_GRADLE_BUILD_DIR to F: as above, use Android Studio's JBR, and give the build
  process the Windows SDK 10.0.22000.0 and MSVC 14.51.36231 LIB/INCLUDE paths if the shell
  has no developer environment (`kernel32.lib` / `windows.h` failures). These were process-only
  environment values; no system setting or service changed. Escape the SDK drive colon in
  ignored `android/local.properties` (`C\:/...`) for Android lint.
  - Build a local `web/src/lib/core/pkg`; a junction to another checkout makes Vite serve the
    wasm through an external @fs path that can return HTML instead of wasm. Dependencies and
    generated native/wasm bindings remain ignored.
  - UI fonts/icons in public/ must be explicitly listed in the generated service-worker cache;
    public assets are absent from Vite's bundle manifest. Navigation now falls back to the saved
    app after 1.5 s if a home server's connection hangs.
  - Web review uses the repo's Playwright workflow. Collaborative preview repeatedly produced
    mismatched dimensions/tiled screenshots; captures at 1440×900 and 390×844 were made locally.
    Android build checks run, but native device access is disabled and automatic approval
    rejected the isolated emulator app launch as `blocked by policy`. No real phone or service
    was changed. Native visual parity is still unverified.
  - Channel permission drafts used to reset on unrelated projection updates. Their source is now
    a content-stable selected-override snapshot; the browser check sends from a second device
    while a permission change is unsaved and verifies the choice survives.
  - Windows locks a running executable/DLL. Run the isolated test server from a copy outside
    CARGO_TARGET_DIR before `verify.py` rebuilds the workspace; never stop the owner's service.
    App-module JVM tests that call the real composer need the plain pinned JNA jar and the host
    `jna.library.path`, as core-bridge tests already do; the Android AAR has no Windows dispatcher.

- 2026-10-25 — Android host-core test caching: setting `jna.library.path` does not make Gradle
  track the Rust DLL's contents. A Rust-only change could leave both app/core-bridge unit tests
  `UP-TO-DATE`, even though they load native code. Both test tasks now declare the platform host
  library as a file input. Core-bridge also declares `fixtures/` as an input because its JSON
  conformance cases are read directly from the repo, outside the JVM classpath. Keep those inputs
  when moving build directories or adding fixture-driven native tests.

- 2026-10-25 — Named-front history integration traps:
  - Effective history needs separate record-time controls and effective-time presence. Replaying
    all operations by only one of those times changes sticky override behavior. Batch equal-time
    points and retain canonical write precedence when server correction reorders record times.
  - Even during a manual override, the resolver reports whether the configured immediate source
    is unavailable. The effective-history dependency watch must retain that source; a 512-case
    time-slice comparison caught a stale issue boundary. The saved regression is checked in.
  - Serde's derived internally tagged action decoder pushed the wasm export build to 306 KB gz.
    Decoding its existing JSON object directly, with strict per-action fields and nullable-note
    tests, brings the effective-history/statistics build to 296 KB gz (300 KB limit).
    The generic `tagged!` adapter currently accepts extra fields on unit variants; `front.change`
    requires strict Resume payloads, so its direct decoder also enforces that difference.
  - Cross-language JSON fixtures must use JS-safe timestamps. `i64::MIN` rounds through
    `JSON.stringify` into a floating-point exponent rejected by Rust's integer decoder; use the
    exact JS Date minimum as an offset-schedule sentinel. Native-only limit tests can use i64.

- 2026-10-25 — Wire ordering positions are decoded as u64 and normalized to a portable u32
  ceiling before converting to usize. A large JS-safe position previously decoded on 64-bit
  native but failed on wasm32; adding it to a non-zero legacy layer start could also overflow.
  Both legacy Add/Update and independent actions now retain their existing end-of-set/layer
  behavior for oversized positions. Legacy layer offsets use saturating addition. Two shared
  fixtures exercise the real 32-bit wasm and 64-bit native paths.

- 2026-10-26 — A structurally valid derived front cache can still be stale when an older build
  stored newer front kinds as opaque operations. Version checks alone do not detect that.
  Migration 0010 records the latest applied front-op sequence per account; reads, startup and
  the append path verify the relevant log prefix before reusing it. Unsupported/malformed ops
  stay in the append-only log and are excluded from the core state. Restore tests now construct
  the actual historical schema from the migration prefix: lowering a current database's schema
  version leaves later additive tables in place and is not a faithful upgrade fixture.

- 2026-10-26 — The network digest cannot identify projection semantics: unchanged op IDs and
  stamps can acquire new meaning after an app upgrade. UI snapshots now use a separate versioned
  digest, checked before display as well as adoption. Bump `PROJECTION_VERSION` for recognized
  kind or projection shape/behavior changes. Old caches rebuild from retained ops. While testing
  malformed cached digests, a 32-byte string with a mixed ASCII/UTF-8 prefix panicked in the old
  hex decoder's byte-indexed string slicing; decoding hex directly from bytes returns an error
  without panicking and retains the existing wire format.

- 2026-10-26 — Named current projections use an account dependency key spanning legacy and new
  front events. A live append candidate is valid only for one genuinely new identity; multiple
  writes, corrected stamps, backdates, targets and rejection rebuild the surviving set. First
  load retains the current fold's append guards. `front_systems` is optional for legacy-only
  accounts, and deleting the final named event removes it from both native/web delta clients.
  Its arrival bumps the UI projection schema to 2; the SQL current cache remains version 1
  because its fold/wire contract is unchanged. Bump that SQL version too when recognition or
  folding semantics change, even if the operation-sequence watermark still matches.

- 2026-10-26 — Reuse `crate::sort::map` / `set` on wasm, including small default collections and
  readers' sets. Direct BTree collection constructors in the new front helpers instantiated
  additional standard-library sorting code. Reusing the existing helpers and sharing the
  model's non-generic sorted projection body takes the runtime build from ~307 KB to 298.6 KB
  gz while retaining native std collection/sort behavior. The 21 Rust/wasm/JNI fixtures and
  shuffled reference/incremental checks cover the contract. Fat LTO barely reduced gzip size;
  a temporary Binaryen 132 optimization experiment made gzip size larger. Neither a new build
  dependency nor a profile change was retained.

- 2026-10-26 — Advancing a current-cache watermark for an ignored/newer event must first verify
  the preceding applied-front prefix. Otherwise an ignored event can conceal a valid event that
  an older build never projected. The 512-case ingestion fuzzer caught this stale-cache case.
  Unknown offline subject parents stay in the log; resolve names and follower candidates only
  against the owning account. A later own parent refreshes reveals; a foreign parent cannot.
  Remove actions may repair an old foreign reference without re-authorizing that reference.
  Migration 0012 checks purpose-ID suffixes with BLOB byte length, preserving rows and cache,
  because SQLite length(TEXT) stops at NUL while JSON/Rust accept that opaque string.

- 2026-10-26 — Owner history endpoints fold the complete account front log before applying time
  filters: a late amendment outside the requested window must still correct the saved row.
  Saved IDs are editable; effective transition IDs are synthetic and must not be passed to
  amend/retract. Named review resolutions come from the account log with HLC/id precedence,
  because the resolution may precede the switches and no legacy SQL review row may exist.
  Named daily reads clip closed future ends as well as open ends to now, and clip intervals
  before aggregating days. The server still has no IANA timezone database: its API accepts one
  explicit/latest fixed UTC offset; web/native clients already supply transition schedules to
  core for their offline charts. Named SQL/export history remains a separate pending slice.

- 2026-10-26 — Offline named history uses explicit replica read methods, with caching separate
  from current-state projection. A history amendment can leave Current byte-for-byte equal;
  invalidation must follow the front dependency delta/revision, not JSON equality of current
  presence. Unrelated member/chat deltas must not trigger the full history fold. Both clients
  test this boundary against actual Rust bindings. Named histories keep original independent
  layer IDs and saved IDs; effective IDs must never become undo/amend targets.
  A browser fixture written through a separate socket using the app device's ID must reconnect
  the app afterwards: live fan-out deliberately skips that same device, whose normal writer
  already owns its local operations. The ordinary hash-only route helper does not reconnect.

- 2026-10-26 — Legacy review-resolution SQL must constrain the target card by both review ID
  and the operation's owning account scope. Account-scope write permission alone is insufficient
  when a payload names a known card from another system. A regression test retains the other
  system's open card unchanged, then verifies that its actual owner can resolve it. Named owner
  history reads already derive resolutions only from their account's log.

- 2026-10-26 — Named statistics must pass canonical `layer_id` intervals to `namedFrontDaily`,
  not custom IDs in the legacy Level decoder. Existing chart DTO fields called `level` may
  carry an actual independent ID internally; week/pair filters must receive that ID explicitly.
  An empty canonical result must not fall back to old Body intervals. Empty web selections
  also return before building a timezone schedule (a broad empty 1970–2026 request otherwise
  spends seconds formatting hourly offsets with Intl). Both clients retain their legacy
  defaults and the existing fixed chart window behavior until the named UI is connected.

- 2026-10-26 — After History's verified browser run, automatic approval review rejected a later
  attempt to restart the isolated F:/DunBuild/chorus-ui-runtime test server with only the reason
  `blocked by policy`. The rejected command did not run and was not retried or bypassed. Fresh
  Insights captures remain pending. Data tests/normal verify continue; no new Insights UI has
  been exposed. The earlier native emulator-launch policy block is also still unresolved.

- 2026-10-26 — Insights UI was subsequently connected without restarting the blocked server.
  The actual Svelte component can be compiled for SSR with the already-installed Svelte compiler
  and Rolldown, supplied actual wasm replica operations from `web/src/lib/testing/insights-fixture.ts`,
  and inspected as self-contained HTML via T3 `html_preview`. Existing CSS/tokens and the local
  Hanken font were embedded. This establishes component layout/state evidence only, not hydrated
  controls, live application/sync or Android rendering. The fixture's exact-layer totals and
  own/effective differences also run as a normal `Insights.test.ts` test. Both policy blocks
  remain unresolved; no retry/bypass or owner service/device change occurred.
  Compose `produceState` retains its previous value while a new keyed coroutine starts. Insights
  therefore keys the result's composition by a fresh remembered request identity, clearing old
  front/layer/view charts and export actions in the same composition as the selection change.

- 2026-10-27 Claude — F: filled to 0 bytes mid-build (`rustc-LLVM ERROR: IO failure on output stream: no space on device`). `chorus-target\debug\incremental.kache-auto` (~10 GB) is Chorus's regenerable incremental cache and is safe to delete; `F:\DunBuild\target` (~63 GB) is not obviously Chorus's, so it was left for the owner. Building with `CARGO_INCREMENTAL=0` keeps the cache from regrowing. A process-only SDK/MSVC environment script lives at `F:\DunBuild\chorus-env.ps1` (dot-source it).
  The full debug profile still refilled F: (LNK1318 PDB errors); `cargo clean --profile dev` freed 20 GB and `CARGO_PROFILE_DEV_DEBUG=line-tables-only` + `CARGO_PROFILE_TEST_DEBUG=line-tables-only` keep test PDBs small. After a chorus-core change, run `scripts/build-web-core.ps1` before `verify.py --quick`: web tests otherwise run the previous wasm and fail new fixtures.

- `scripts/board.py start` rewrites the whole *Now* block: it replaces the first *In progress*
  line and sets the **Owner:** line to the agent's name, which erases the owner's standing
  instructions (deploy steps). After running it, restore those lines from `git show HEAD:PROGRESS.md`
  (Claude, 2026-10-27).

- The wasm core is at the SPEC §9 limit: 307,007 of 307,200 gzipped bytes after UI3 slice 1
  (2026-10-27). `lto = "fat"` in `[profile.wasm]` saves ~160 bytes over thin LTO. New shared-core
  code must be small: prefer `Option`/static messages over `format!`, and keep server-only logic
  (e.g. `poll::tally`, `check_tally`) out of paths the wasm exports reach. A real feature that
  needs more must either find savings or ask the owner about the budget (Claude).

- 2026-10-27 GPT-6-sol — Ingest returns an `Accepted` bundle: its `op` is the input and
  `derived` contains server writes made inside its savepoint. Retain the whole bundle until
  post-commit fan-out, including REST helper returns; dropping it loses live derived updates.
  The pusher skips only ops it already owns, not derived server ops. Tally generation alone
  uses the private derived append; normal server/API writes still pass all author permissions.
  HTTP sync push/pull fallback is specified but not implemented in the current router.

- 2026-10-27 GPT-6-sol — Rejecting null poll edits adds 3 gzipped wasm bytes: 307,010 /
  307,200 after the actual bindgen build and gzip level 9 gate. Keep null allowed on creation;
  check for the edit's key before ignoring a null definition. Two poll fixtures cover this.

- 2026-10-27 Claude — Live browser checks are not policy-blocked for Claude sessions: from Git Bash,
  after `cargo build -p chorus-server` and `npm run build`, run
  `CHORUS_E2E_CHANNEL=chrome CARGO_TARGET_DIR='F:\DunBuild\chorus-target' bash scripts/e2e-web.sh --no-build`.
  PowerShell's `bash` is WSL and cannot see the Windows build. Playwright's pinned Chromium (1194)
  is absent, so use the installed Chrome channel. Each run uses a temporary data dir; nothing persists.


### 2026-10-27 — Git Bash path in Android chat implementation

On this host bare `bash` resolves to `C:/Windows/system32/bash.exe` (WSL), which does
not understand the supplied `/f/DunBuild/chorus-commit.sh` path. Invoke
`C:/Program Files/Git/bin/bash.exe` explicitly for the owner's commit wrapper. This
changes only the invoked process; no PATH or other system settings were changed.

- 2026-10-27 GPT-6-sol — Typing (D-087) deliberately bypasses `chorus-core`'s Frame decoder:
  server `app/typing.rs`, web `sync/typing.ts`, Android `data/Typing.kt`. No wasm/core changes.
  `Peer` queues retain typed durable frames until the writer, alongside ephemeral typing JSON; only the latter's
  direct socket relay is used, never the events/hooks pipeline. A short per-device/channel
  start budget survives socket replacement/disconnect (2 s); same-identity presence from several
  devices is aggregated without counting the receiving device's own activity. Clients use the
  welcome server clock offset and clear/expire state in memory. Draft changes alone refresh
  starts; unrelated projection changes must not keep idle drafts alive. Private thread replies
  use audience `system`, as do chosen-member/system-only drafts.
  Android UI handoff (the four chat UI files were intentionally untouched): collect
  `chorus.typing: StateFlow<Map<String, List<TypingEntry>>>` alongside `chorus.model`, then call
  `TypingText.label(model, typing[channelId].orEmpty()): String?` for the header subtitle.
  `TypingEntry(account: String, member: String?, until: Long)` uses server milliseconds.
  `chorus.sendTyping(channelId: String, member: String?, audience: String, start: Boolean)` is
  non-suspending and throttled. Call with `start=true` on actual draft changes; send `false`
  before send/clear/channel or speaker/audience changes and on disposal. Audience is `all`
  for an ordinary draft, `system` for system-only/chosen-member drafts or a private thread.
  Never schedule idle refreshes. Offline sends are dropped, not queued. Names use own members
  or authorized author cards; hidden/unsigned speakers fall back to account display names.
  The shared Cargo target can contain another worktree's `debug/chorus-server.exe` even when
  this worktree's library/socket tests pass. For an isolated browser run, explicitly
  `cargo build -p chorus-server` here and immediately copy that executable to a test-only
  runtime path; don't hold the shared binary open. An old snapshot answered typing with
  `bad frame: unknown variant typing`. The browser regression records typing/error frames only
  (never hello/session frames), so this mismatch is visible in its attachment.
  Verification: worktree-root `scripts/build-web-core.ps1` and
  `python scripts/verify.py --quick --offline --android` pass; 109 web unit tests, 134 Android
  unit tests plus lint. `typing.spec.ts` passes in installed Chrome against a verified snapshot
  of this branch's server: other-device relay, self exclusion, expiry, send/clear/channel-switch
  stops, and a second tab receiving through the browser sync leader. Mobile/desktop subtitle
  captures were inspected; no Android device install/visual claim. Fresh worktrees need
  generated UniFFI Kotlin bindings (the shared host DLL + uniffi-bindgen can generate them for
  JVM tests without building/installing Android native libraries). Wasm remains 307,010 /
  307,200 gzip-level-9 bytes; no shared-core changes or new dependencies.

### 2026-10-28 — UI5 Android inspection and build notes (gpt-6.1-sol)

- Hanken's vendored variable TTF needs explicit `FontVariation.Settings(FontVariation.weight(...))`
  for the registered 400/500/600/700 variants in the pinned Compose 1.7.6 resource-font API. Native
  captures exposed the old near-identical weights; the explicit axes visibly restore bold/semibold.
  See [Android font guidance](https://developer.android.com/develop/ui/compose/text/fonts#variable-fonts).
- `F:/DunBuild/chorus-verify.ps1` selects the correct worktree but always appends `--quick`. For the
  required full release gate, source `chorus-env.ps1`, set the F: target/Gradle paths and Studio JBR,
  build the web core and run `python scripts/verify.py --android --offline` directly.
- Full release linking initially spawned many Rust compiler processes while native inspection ran.
  Host memory pressure stalled UiAutomation; restarting the same AVD without wiping data and using
  process-only `CARGO_BUILD_JOBS=2` resolved it. Only this task's verified Cargo descendants and
  emulator process were stopped; owner services/settings were not touched.
- The final wasm gzip-9 payload is 307111 bytes against 307200 allowed. It passes, but the 89-byte
  headroom is small: keep checking the existing bundle gate when core code grows.
- `member_dm` is the schema kind. Legacy Android checks for only `dm` missed participant avatars
  and Direct-folder classification; both are now accepted for presentation.
- Native evidence, build logs, review APK and short navigation recording live under
  `F:/DunBuild/chorus-ui5-evidence/`; see the committed UI5 handoff. Phone-private captures remain
  outside the repository. The normal app data on both devices was retained throughout.
