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
