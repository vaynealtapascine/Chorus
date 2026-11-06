# Notes

Non-obvious findings: library traps, platform limits, reasons a design had to change. Grouped by
area; add new findings to the right section (one or two lines, date + agent if useful). Keep the
conclusion here and the investigation in the commit. The pre-2026-11-05 notes, with the full
perf and review write-ups, are in `git show a9c3c2e:docs/NOTES.md`.

## Disks (owner's PC)

- **C: is always nearly full** (~26 MB free on 2026-11-05). Build on F:: `CARGO_TARGET_DIR=F:\DunBuild\chorus-target`,
  `CHORUS_GRADLE_BUILD_DIR=F:\DunBuild\chorus-gradle`, `GRADLE_USER_HOME=F:\DunBuild\gradle`.
  `verify.py` defaults the Cargo target there; `deploy.ps1` uses `F:\DunBuild\chorus-target-deploy`.
  A `Chorus\target` on C: has filled the drive twice; delete it if it appears.
- `deploy.ps1 -AndroidOnly` (called by `deploy-linux.ps1 -Android`) copies the APK into
  `C:\Users\pcuser\selfhost\chorus\app\android`, so an Android deploy needs ~50 MB free on C:.
- F: fills too. Safe to delete: `F:\DunBuild\chorus-target\debug` (46 GB once), any `incremental`
  folder, `cargo clean --profile dev`. Not Chorus's, leave alone: `F:\DunBuild\target`.
  Keep usage down with `CARGO_INCREMENTAL=0` and `CARGO_PROFILE_DEV_DEBUG=line-tables-only`,
  `CARGO_PROFILE_TEST_DEBUG=line-tables-only` (full debug info hit LNK1318 PDB errors).
- The ignored 1M-op perf tests need ~5 GB: set `CHORUS_PERF_DIR` to a roomy drive (a failed run
  leaves its copy in the temp folder otherwise).

## Build environment (owner's PC)

- A fresh shell has no MSVC environment: `aws-lc-sys` fails on `windows.h` / `kernel32.lib`.
  Dot-source `F:\DunBuild\chorus-env.ps1` (process-only LIB/INCLUDE for Windows SDK 10.0.22000.0
  and MSVC 14.51.36231, plus the F: target dirs).
- `pwsh -File F:\DunBuild\chorus-verify.ps1 [args]` = env + `scripts/build-web-core.ps1` +
  `verify.py --quick --offline [args]`, logged to `F:\DunBuild\chorus-verify.log`
  (`CHORUS_TREE=<worktree>` to run elsewhere). It always adds `--quick`; for the full pre-deploy
  gate source `chorus-env.ps1` and run `python scripts/verify.py --android --offline` yourself.
- `JAVA_HOME` may point at Java 8: Gradle needs Android Studio's JBR
  (`C:\Program Files\Android\Android Studio\jbr`; `verify.py` sets it).
- `android/local.properties` needs the escaped drive colon for lint:
  `sdk.dir=C\:/Users/pcuser/AppData/Local/Android/Sdk`.
- dl.google.com is sometimes unreachable: run Gradle `--offline` (Google artifacts must be
  cached). `settings.gradle.kts` limits `google()` to androidx/com.android/com.google groups, so
  Maven Central libraries still resolve online. No new Android dependencies without a reason.
- A Gradle daemon inherits the shell's output handles: piping `gradlew` or wrapping it in a
  backgrounded `pwsh -File` can hold the shell open long after the build. Redirect to a file or
  end with `gradlew --stop`. `--parallel` gains nothing here; measure with a warm daemon.
- Agents must not install services or change system settings. The PC's own Chorus/ChorusHome
  services are Manual + stopped since 2026-10-31 (the server runs on ns2). Some agent harnesses
  refuse emulator launches or server restarts as "blocked by policy": don't retry or bypass,
  record it and move on.

## Windows and shell traps

- In PowerShell, bare `bash` is WSL and can't see the Windows build. Use
  `C:/Program Files/Git/bin/bash.exe` for Git Bash scripts (e2e, `F:/DunBuild/chorus-commit.sh`).
- Git Bash: set `MSYS_NO_PATHCONV=1` for `adb shell` device paths; heredocs mangle backslashes —
  edit Rust source with the editor tool, not sed/python.
- Run the deploy scripts from PowerShell. `pack-linux.ps1` calls `tar.exe` expecting Windows'
  bsdtar; a deploy where GNU tar answered failed packing (2026-11-03).
- Python helper scripts: Windows paths in ordinary string literals turn `\a \b \r \0` into
  control bytes (this corrupted `deploy.ps1` once). Use raw strings.
- PowerShell: `$x = if (...) { @() } else { @('--release') }` unwraps to a string and `@x` splats
  characters; write `$x = @(if (...) { '--release' })`. Don't name a variable `$profile`.
- Windows locks a running exe/DLL (rename works, overwrite doesn't). Run test servers from a copy
  outside the target dir; `deploy.ps1` swaps the PC binary by rename (the server exits when its
  binary changes and NSSM restarts it).
- Windows discards a response the client already received when the server closes with unread
  request body (RST). `drain.rs` reads leftovers after early answers (401/404/413/415/429): keep
  that layer outermost. Sync sockets send Close and drain before dropping for the same reason.
- No git identity is configured globally: commits use the owner identity through env vars
  (`GIT_AUTHOR_NAME="Vayne Altapascine"`, `GIT_AUTHOR_EMAIL=vaynealtapascine@proton.me`, same
  for committer), as `F:/DunBuild/chorus-commit.sh` does.

## Gate (`scripts/verify.py`)

- `--changed` (stages a change can affect, D-118) is enough to commit; `--quick` while iterating;
  a **full run with `--android` before any deploy**. The `gate` profile's first build takes ~8 min
  and 4 GB, then ~100 s after a server change.
- After a `chorus-core` change run `scripts/build-web-core.ps1` before web tests (they otherwise
  run the old wasm and fail new fixtures). After changing `chorus-ffi` exports run
  `scripts/build-android-core.ps1 -Debug`: the UniFFI Kotlin bindings and `.so` files are ignored
  build outputs; rebuild both ABIs and the bindings together (checksums change with any exported
  type). A fresh worktree needs generated bindings before JVM tests.
- Budgets checked by `verify.py`: web JS ≤ 300 KB gz summed over all chunks (D-116), wasm
  ≤ 500 KB gz (D-108). Notes or handoffs quoting 200 KB JS / 300 KB wasm predate those decisions.
- Several worktrees sharing `F:\DunBuild\chorus-target` can see each other's artifacts as fresh
  (clippy: `no profiles in the root` with the module present; CLI tests running another tree's
  `debug/chorus-server.exe`; `os error 32` on a test exe). `touch` the crate's `lib.rs`, serialize
  gates, or give the worktree its own `CARGO_TARGET_DIR` (OPS.md §8).
- Android JVM tests load the host `chorus-ffi` DLL through JNA; their Gradle tasks declare that
  DLL and `fixtures/` as inputs. Keep those inputs when moving build dirs, or Rust-only changes
  leave the tests `UP-TO-DATE`. App-module tests calling the real core need the plain JNA jar and
  `jna.library.path`.
- A web message-search timing test (16 ms) can fail under Gradle load; rerun before chasing it.

## Fixtures and the shared core

- **When client code reads projection rows, build its test model from ops through `project`,
  never from hand-written rows.** 0.1.706 opened every chat as "Private chat" because the clients
  and the hand-written `ui_search*.json` fixtures used the server's SQL shape
  (`owner_account_id`, `space_member.is_present`) that the core projection never had.
- Cross-language JSON fixtures need JS-safe integers (`i64::MIN` breaks through
  `JSON.stringify`). Ordering positions decode as u64 and clamp to a u32 ceiling so wasm32 and
  native agree.
- Bump `PROJECTION_VERSION` when a projection's shape or behaviour changes (clients rebuild their
  cached UI snapshot from retained ops); the SQL front cache has its own version.
- `chorus-core` must stay pure. For new sorts/collections in core use `chorus_core::sort`
  (`by`, `by_key`, `map`, `set`): each std sort instantiation costs ~4 KB of wasm. Avoid serde
  internally tagged enums and `flatten` (use `tagged!`); with `#[serde(remote = "Self")]` call
  `<T as Deserialize>::deserialize`, not `T::deserialize`. `wasm-opt`, `opt-level = "s"` and fat
  LTO don't help gzip size. The core `editor` feature is enabled by `chorus-ffi` only.
- UniFFI: an error field named `message` clashes with `Throwable.message` (call it `reason`);
  `uniffi.toml` sets `disable_java_cleaner` for minSdk 29; `cargo ndk` needs `--lib`; the host
  bindgen must use the same profile as its DLL.
- Android, JBR and Node ship different Unicode case tables: fallback name ordering uses
  ASCII-only folding (D-091). Android's tables can also lag Rust's for new characters.

## Server and SQLite

- Migrations must be idempotent (`IF NOT EXISTS`, delete-then-fill): restore tests rerun them by
  lowering `schema_version`. SQLite has no `ADD COLUMN IF NOT EXISTS`, so three `tests/posts.rs`
  cases that lower it to 17 also drop `bucket.icon` (0019); undo any new `ADD COLUMN` there too.
  Prefer building a real old schema from the migration prefix for upgrade tests.
- The workspace forbids `unsafe_code`; platform calls go through safe crates already locked.
- Op field names differ from columns (`reply_to` → `reply_to_id`, `repost_of` →
  `repost_of_id`); `scripts/projection-check.py` fails when a catalogue field has no column or
  handler.
- Query traps: rusqlite's `conn.execute`/`query_row` re-prepare every time — use the statement
  cache (`query_cached`) on hot paths; a `json_extract` index is used only with the identical
  expression; steer `kind IN (…) AND scope = ?` with `+scope`. `perms::can_sql` uses `perm_*`
  aliases because a nested `channel c` silently shadowed the outer query's.
- FTS5 tables are plain (own text copy), not external-content; `DELETE FROM` one is slow, so
  rebuild drops and recreates it.
- Ingest returns an `Accepted` bundle (the op plus server-derived ops from its savepoint): keep it
  until post-commit fan-out or live clients miss derived updates. Each op has its own savepoint;
  value rules apply to new ops only (`validate_new`), so old logs still replay.
- `chorus-server rebuild` builds into a fresh file and swaps (~27 s for 1M ops on the PC; budget
  60 s). In-place rebuilds (purge, restore) are slow on Windows because of the single commit.
  A rebuild folds the front only up to the op being replayed (`REPLAYED_UPTO`): review cards are
  never withdrawn, so folding the whole log would differ from live ingest. Free buffers on the
  thread that allocated them (glibc cross-thread frees cost ~5 µs each).
- The server has no IANA timezone database: daily totals use a fixed UTC offset; clients split
  intervals at DST transitions before calling core.
- Search cursors carry no bm25 score (it depends on unreadable rows); visibility filtering runs
  before LIMIT. Blob GET/HEAD return the same "missing" for unreadable and absent hashes.
- Post history (D-107, migration 0018) is derived: a base plus compact diffs regenerated by
  rebuild; the log keeps every full edit payload.
- `account` (handles, kind) is a server-owned table, not derived: rebuilds keep manual changes,
  so back it up before editing it on the server.
- HTTP push/pull sync fallback is specified in SYNC.md but not implemented.

## Sync

- Acks carry the full stamp (account, device, received_at); after a restore old-epoch seqs are
  reused, so reconcile re-pushes everything; unconfirmed reconcile pushes are demoted to the
  outbox, never fire-and-forget. A digest repair sweeps ops the re-pull didn't deliver.
- Live fan-out skips the writing device: a test fixture that writes through a separate socket
  with the app's device id must reconnect the app afterwards.
- Typing (D-087) is WebSocket-only and bypasses the core Frame decoder (server `app/typing.rs`,
  web `sync/typing.ts`, Android `data/Typing.kt`).

## Web

- One browser, several tabs: a Web Locks leader owns the replica and socket; other tabs hand ops
  over a `BroadcastChannel`. State read from the sync client must be copied into `$state` to be
  reactive.
- Files in `public/` (fonts, icons) must be listed in the service-worker cache explicitly.
- Build a local `web/src/lib/core/pkg`; a junction to another checkout makes Vite serve HTML for
  the wasm.
- The main checkout's `web/node_modules` has repeatedly been found incomplete (no vite) before a
  deploy: `npm ci --prefer-offline`.
- Live e2e from Git Bash, after `cargo build -p chorus-server` and `npm run build`:
  `CHORUS_E2E_CHANNEL=chrome CARGO_TARGET_DIR='F:\DunBuild\chorus-target' bash scripts/e2e-web.sh --no-build`.
  Playwright's pinned Chromium is absent; use installed Chrome. `page.goto('/#/chat')` can report
  ERR_ABORTED (the app rewrites the hash): click through instead.
- Typed row caches assume rows are replaced, not mutated: fixtures must replace a changed row.

## Android

- The owner's phone is a Samsung Galaxy A56 (SM-A566B) with their **real account**: inspect only.
  Mutations go to the emulator (headless with 3 GB; Gboard hangs under memory pressure, so cap
  `CARGO_BUILD_JOBS=2` while it runs) against a demo server (OPS.md §8, "Demo data").
- Phone/emulator against a local server: `adb reverse tcp:<port> tcp:<port>`, a device invite
  from `chorus-server --config <toml> invite --kind device --account <id>`, then open
  `http://127.0.0.1:<port>/i/<code>`. Debug builds allow cleartext to loopback only. Removing the
  reverse doesn't close open sockets: force-stop the app to test offline.
- Push without a server (debug builds): write a payload to `/data/local/tmp/p.json` and
  `am broadcast -n garden.vayne.chorus/.data.DebugPushReceiver --es payload "$(cat …)"`.
- FCM is the default whenever a Firebase token exists, unless a UnifiedPush distributor is picked
  in Settings → Advanced (an installed ntfy app used to take over silently). Firebase files live
  in `C:\Users\pcuser\selfhost\fcm\` (`google-services.json` is read by the build, no
  google-services plugin); the service account is on ns2 at `/etc/chorus/fcm-service-account.json`.
  UnifiedPush discovery needs the `<queries>` manifest entry.
- Self-update: a phone set up by `adb install` has no installer of record; the first in-app update
  needs "Install unknown apps", later ones install silently when the app is left.
- Battery Saver cuts background network: WorkManager defers sync until the app opens.
- `member_dm` is the schema's kind for members' DMs, not `dm`.
- Compose (pinned 1.7.6): `produceState` keeps its old value while a new key loads — key results
  by request identity; a `ModalBottomSheet` keeps the anchor of small loading content (reserve the
  final size); the Hanken variable font needs explicit `FontVariation` weights; rich-text undo
  provenance needs `TextFieldState`/`InputTransformation`, never inferred from text.
- Never put large drafts in saved-state Bundles (Binder ~1 MB limit crashed a big bio): drafts
  live in the encrypted Store KV with only a reference in the Bundle.
- `Chorus.create` can throw after its SQLite commit (cancellable `withContext`): Journal
  reconciles a send by looking for the chosen post id in the op log, never by retrying blindly.
- `am kill` right after Home may leave the process alive; check the PID changed.

## Deploy and ops

- Production is `ns2-up-net-nz` (Linux, Caddy, public; SSH tailnet-only as root).
  `pwsh scripts/deploy-linux.ps1 [-Android] [-NoBuild]` builds the static musl server in Docker
  under WSL (`pack-linux.ps1`, bundles in `F:\DunBuild\chorus-linux`), copies it over and runs
  `install.sh`. `deploy.ps1` alone only updates the stopped PC copy. See OPS.md.
- Migrations take a pre-migrate backup on ns2 (`/opt/chorus/backups/pre-migrate-*.db`); take a
  verified backup by hand before risky manual changes.
- What's live: `GET https://chorus.vayne.garden/api/v1/server` and `/api/v1/android/latest`
  (version and changelog).
- Invites: `pwsh scripts/invite.ps1` (over ssh, or `-Local`).
