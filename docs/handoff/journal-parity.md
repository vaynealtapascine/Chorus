# Journal parity handoff

Gemini presentation work is parked after quota exhaustion. Preserve its dirty web files: data.ts, post-threads.ts, posts.ts, ui/AttachmentView.svelte, blob-url.ts, journal-presentation.ts, ui/PostMedia.svelte. They are not part of the Android slice.

Android slice: Journal composer and audience dialog use AudienceChip, carry bucket IDs through create and post.set_visibility, and reject empty or unavailable bucket selections. Model retains the full audience. Server remains under More. Reply, quote, media and poll assembly remain in place.

Ordered remainder: multi-author; drafts/editor; remote fidelity/offline feeds; web presentation/social actions. No deployment or device changes.

Validation evidence: Full `python scripts/verify.py --android` passed, exit 0 (F:/DunBuild/journal-full.log), after build-web-core prerequisites, using F: builds and Studio JBR. Separate `:app:testDebugUnitTest :app:assembleDebug` passed, exit 0 (F:/DunBuild/journal-android.log). Targeted tests cover create payload bucket preservation with quote/reply/media and visibility payload rejection of empty/missing/mixed missing selections. No device validation.

2026-11-01 Sol follow-up: Opus accepted bucket slice 617e168 and chat fixes fbb1f64. Fixed person-account audience wording, rotation preservation of selected bucket IDs, and widening warnings (bucket subset narrowing is quiet; incomparable sets warn). Multi-author creation remains the next implementation step; no drafts, web presentation, schema, deployment or device changes.
Validation for follow-up: full explicit python scripts/verify.py --android passed exit 0, F:/DunBuild/journal-full-r2.log (web-core prerequisite completed; F: environment and Studio JBR). Includes AudienceExpansionTest. Fixed pre-existing PostComposeTest Model.copy compile failure by constructing the fixture directly. No device validation.

2026-11-01 Sol multi-author: composer now toggles explicit authors, preserving selection order and rotation state. Payload deduplicates in first-occurrence order and rejects empty, foreign, missing or inactive authors against the live model after attachment staging. Defaults still use canonical Reply.speaker; explicit profile/reply/quote launches reset to single author; person accounts hide the picker. No subsystem expansion. Existing audience/buckets/poll/media assembly retained. Tests exercise picker transition through payload and malformed stored audience fail-closed. No web, draft, schema, lifecycle, deployment or devices.
Validation: build-web-core prerequisite exit 0; full explicit python scripts/verify.py --android exit 0, F:/DunBuild/journal-multi-full.log (release simulator, Rust/server/web checks, Android lint and unit tests; BUILD SUCCESSFUL). F: environment and Studio JBR used. No device validation.

2026-11-01 Sol implementation of Opus findings on fc08fe2: removed unused single-author picker; unavailable selections have removal menu items. Explicit selection saved state is keyed by account and person status; person sends ignore explicit member selections. New post/share/new entry, Friends replies and successful sends reset selection; failed sends retain it. Cancel retains the current in-memory draft. Empty explicit selection disables Post. Current account, active authors and audience are checked before attachment staging and checked again afterward by payload construction. No durable drafts. UI-consumed JournalSelection tests cover person/empty/default behavior and ordered stale-member removal. Remaining review coverage limitation: no Compose launch integration or suspended-upload race test. No web edits, deployment, push or device changes.
Validation: web-core prerequisite succeeded; full explicit python scripts/verify.py --android passed exit 0, F:/DunBuild/journal-review-full.log, including Android unit tests/lint. F: build environment and Studio JBR. Independent Opus re-review requested.

2026-11-01 Sol launch lifecycle first slice: external share/new-entry/reply effects defer consumption while send is busy and rerun on completion. This prevents a successful old upload from clearing those queued launches; a failed upload retains its original fields before the launch runs. Write reopens retained explicit co-authors. Send cancellation propagates. Limits: this is not durable Journal storage; implement an account-owned UI-consumed lifecycle using EditorDraftStore next, with suspended-upload/restore/stale-write tests. Chorus.create uses cancellable withContext around serial replica persistence: cancellation can occur after commit, so durable send recovery must reconcile the chosen post ID instead of treating every cancellation as uncommitted. Do not cherry-pick stale WIP. Account switches during uploads and multiple replacement launch intents still need explicit lifecycle coverage; no claim of complete draft safety.
Validation: explicit full python scripts/verify.py --android passed exit 0 (F:/DunBuild/journal-launch-full.log), with wrapper environment, web-core prerequisite, F: builds and Studio JBR. Existing unit tests/lint only; deterministic lifecycle tests remain outstanding. Concurrent quick wrapper failed on a locked chorus_ffi.dll; full run completed successfully. No web edits or device changes.

2026-11-02 GPT-6-sol durable Android slice (starting HEAD 6dd5e91): the composer now consumes
`JournalDraftSession` for restore, every field edit, cancel/reopen, external/local launches and
send. Full immutable `JournalDraft` snapshots use the existing encrypted Store/EditorDraftStore.
Restore precedes editing/autosave; leased serialized writes reject stale editors and preserve
accepted pending edits during Activity overlap. Fields include source text/selection, kind/title,
null default versus ordered explicit author intent, audience bucket IDs, CW/tags/mood, reply/quote,
poll and ordered URI/alt/spoiler references. Metadata salvage keeps the exact raw original with an
explicit recovery step; unavailable files remain visible/removable and fail preflight.

Send freezes default authors at Post, validates before and after staging, and guards attachment
and post creates by expected editor owner on Chorus's serial worker. A durable chosen post ID
precedes create; the persisted own-account op query reconciles normal success, failure after
commit, cancellation and restart, including posts absent from live Model because of tombstones.
Failure/query uncertainty retains the draft; cancellation propagates. Pending launch receipts
persist before acknowledgement, queue while busy and survive consumption/reconciliation. Existing
content never silently resets or takes the author of a newly opened profile/reply; context changes
ask, and shares append with metadata retained. Technical rationale and API traps: NOTES.md,
“Android Journal draft/send lifecycle”.

Review focus: inspect the UI bindings in Journal.kt/JournalDraftEditor.kt, lease transfer in
EditorDraftStore, chosen-ID write ordering and noncancellable reconciliation in JournalDraftSession,
and account guards in Chorus/create + sendAttachment. JournalDraftSessionTest and
JournalDraftSendTest use deterministic suspended load/write/staging/commit gates, not sleeps.
Limits: no Compose instrumentation/native visual or real process-kill check; transient picker
grants can still require reselection; staging can leave harmless orphan upload records; these
drafts are device-local, with SPEC §6.2 cross-device sync still pending. Seven Gemini web WIP
files remain untouched/unstaged. Parent should obtain consolidated Opus review of this slice and
6dd5e91 before continuing remote fidelity/offline feeds and parked web parity work. No delegates,
push, deploy, system changes or device mutation.

Validation for durable slice: prerequisite `scripts/build-web-core.ps1` passed, followed by
explicit full `python scripts/verify.py --android --offline`, exit 0 (release Rust simulator/server
tests, web checks/tests/build/bundle budgets, Android lint and JVM tests), final log
`F:/DunBuild/journal-durable-final-full.log`. All 23 new JournalDraftSession/JournalDraftSend
tests pass (19 + 4); existing EditorDraftStore tests and the full Android suite pass too.
Separate `:app:assembleDebug --offline` passed, exit 0 (`F:/DunBuild/journal-durable-apk.log`);
APK at `F:/DunBuild/chorus-gradle/app/outputs/apk/debug/app-debug.apk`. All builds use F:,
Studio JBR and the process-scoped wrapper environment; no quick/full concurrency. Start/handoff
commit b9ef139 was also gated by the explicit full verifier (`journal-durable-start.log`).
