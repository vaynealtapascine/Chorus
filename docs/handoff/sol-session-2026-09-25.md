# Sol Batch 5 handoff — 2026-09-25

The owner asked me to stop this session and write a handoff. Resume only after they ask. Work was confined to `F:\DunBuild\Chorus-sol`, branch `sol/batch-2`. Do not switch or edit the C: checkout, push, deploy, or touch Windows services. The owner authorized all work within this branch and folder, but took the phone off adb for tonight; do not attempt more phone checks until a later session.

## Commits this session

- `011384e` merged `main` at `03e05e1` into `sol/batch-2` with the owner's explicit approval. Rebuilt wasm and Android core, built Android APK, and passed `python scripts/verify.py --quick --android --offline` before committing.
- `fe280ff` persists core `Changes.removed` ids in Android Room in the same transaction as ops/meta. Android tests and full quick verifier passed. Phone restart after channel-view revocation is pending.
- `f5fb899` shows server-filtered posts from followed accounts in People, with CW body/title collapsed and stale previews cleared on refresh. Android tests, the server audience HTTP test and full quick verifier passed. Phone layout and revoke-follow behavior are pending.
- `8129b29` adds own-member profiles entered from Journal. Local posts and member details work offline; online profile bundles add counts, relationships and highlights. Android tests, server profile integration test and full quick verifier passed. Phone navigation/offline behavior are pending.

See `docs/handoff/sol-batch-2.md` under Batch 5 for each slice's audit details and what was actually run.

## Stopped mid-slice: V4 Advanced follower sharing

Three source files are changed: `data/FollowPresets.kt` adds `withSharing`, `ui/People.kt` adds a collapsed Advanced sharing section per active follower, and `data/FollowPresetsTest.kt` checks that one flag changes without losing the preset or the other flag. It writes `follow.set_ceiling` using the existing projected ceiling. This is intentionally behind Advanced. `:app:testDebugUnitTest :app:assembleDebug --offline`, `cargo test -p chorus-server --test notifier shared_history_and_stats_only_contain_revealed_whole_days --offline`, and `python scripts/verify.py --quick --android --offline` passed. No phone run. The next agent should inspect the UI/control state and either finish this slice or amend its `wip:` commit after re-verifying. In particular, confirm the toggle state updates after sync and test an active disposable follower later on the phone. Do not claim it is device-verified.

## Next work

1. Follow `docs/handoff/sol-batch-5.md` order and ground rules. V0 and V2 are done; V4 remains partial. Complete and audit the sharing controls, then follower history/stats readout, journal reactions and reply threads, lists/feeds, and remaining profiles before V5.
2. Batch phone checks for a later owner-approved device session: V0 removal surviving restart, followed-post CW/privacy, own profile navigation/offline fallback, sharing toggles, and confirmation that the earlier private test note reached the demo server. The phone's app data must stay intact; never unlock it via adb.
3. Use F: build paths in every shell: `CARGO_TARGET_DIR=F:\DunBuild\chorus-target-sol`, `CHORUS_GRADLE_BUILD_DIR=F:\DunBuild\chorus-gradle-sol`, `GRADLE_USER_HOME=F:\DunBuild\gradle`, `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `PYTHONIOENCODING=utf-8`. Gradle must run `--offline`. Dev ports remain 5261/5262. `verify.py --quick --android --offline` must pass before every commit.

No `chorus_core` sync/model/notify/stage/front/text semantics changed in this session. No proposed D-S2 decision. The owner said this session may have a bug; do not infer a code defect from that remark without reproducing one.

## Continuation after the owner's request to continue (same date)

The V4 sharing WIP above was finished in the later commits recorded in `docs/handoff/sol-batch-2.md`. That file is now the authoritative slice-by-slice log; the old "Stopped mid-slice" and "Next work" sections above describe the starting point, not remaining work. This continuation stayed in `F:\DunBuild\Chorus-sol` on `sol/batch-2`. No C: checkout edits, push, deploy, service change or adb session occurred.

The continuation added Android V4/V5 profile, settings and search work, Journal attachment display/composition and file opening, and the first V6 chat Stage, saved plans, widget pins and pinned launcher shortcuts. Each committed slice passed `python scripts/verify.py --quick --android --offline`; UI was compiled and unit-tested, not run on the phone. The latest concrete Stage work adds core-backed member/reply filters and bounds the Advanced panel so many author controls remain scrollable. See the end of `docs/handoff/sol-batch-2.md` for commits, tested scope and remaining limitations.

**Next concrete step:** finish the Android Stage render options (styles, attachment blur, wider plans and old-message paging) or the widget's per-instance scope from `docs/handoff/sol-batch-5.md`. On a future owner-approved phone session, audit the V3–V6 screens and widget/shortcut behavior with disposable data. Keep the phone's app data intact. The full verifier requires these additional F: environment locations while C: is full: `TEMP=TMP=TMPDIR=F:\DunBuild\temp-sol` and `npm_config_cache=F:\DunBuild\npm-cache-sol`, alongside the earlier Cargo/Gradle/JBR variables.

## Further continuation after additional usage (same date)

Work stayed in `F:\DunBuild\Chorus-sol` on `sol/batch-2`. Android Stage now renders attachments, all six style presets, light/dark themes, avatars with concealment, the channel heading and reply previews. It supports hide/shift/start-at times, saved web-compatible render flags, and paging older messages from the local replica. Capture refuses a saved selection whose picked rows are not loaded. The quick-switch widget now saves an account-bound scope per instance (all members, one subsystem or one group), with the scope picker behind Advanced and tile taps checked against the visible scope. See the final sections of `docs/handoff/sol-batch-2.md` for each atomic slice, tests and audit limits.

Every code slice passed `python scripts/verify.py --quick --android --offline` before its commit, with `git diff --check`. These are build/unit checks only; no phone, browser, push, deploy, service or C: checkout work occurred. The old V7 web leftovers appear already migrated: `web/src/lib/data.ts`, `ui/MemberEditor.svelte` and `ui/Profile.svelte` have no direct `fetch(...)` calls now.

**Next concrete step:** implement Android V8 “Keep everything on this device” file fill and re-check progress from `docs/CLIENTS.md` §4.3, or audit the remaining Stage gaps (quoted/forwarded snapshots, non-phone capture widths, entry points from posts/profiles). A later owner-approved phone session should exercise disposable Stage images/CWs, all six layouts, saved older selections, two widgets with distinct scopes, and launcher shortcuts. Do not touch the enrolled phone without that session; preserve its app data. Keep all build temp/cache paths on F: as above.

V8 implementation detail: `web/src/lib/sync/keep.ts` names the file catalogue and its 20 MB limit; `web/src/lib/sync/client.ts` `recheckAll` shows the caught/repair progress and 120-second timeout. Android already has `CoreReplica.recheck()`/`repairing()` in FFI and `Chorus.setting`/`putSetting` for device-local state. `docs/NOTES.md` records why `Blobs.file` needs a durable kept-files directory before the setting can promise offline availability.
