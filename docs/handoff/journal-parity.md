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

2026-11-02 claude-opus-5.5 (owner: implement in sequence, deploy after each slice; no more delegation
for now). Slice 1: reviewed and accepted the durable drafts above; deployed Android-only 0.1.675.
Slice 2, web post card: `PostCard.svelte` now reads like Android's `JournalPostFrame` (avatar column,
names · compact time with the full time on hover, a lock for private/followers posts, a ⋯ menu with
Open post / Copy text / Stage) and `JournalActions` (reply, repost with Undo, a single 💜 heart with
the once-per-device "readers see the member's name" notice, save as the `bookmark:` account pref,
share via `navigator.share` or the clipboard; the content warning always leads shared text). Reposts
render their original one level deep under "X reposted" (or "no longer available"). Post media
(Gemini's parked `PostMedia.svelte` + `journal-presentation.ts` + `blob-url.ts`, reviewed and
adopted; its unused `threadItemAttachments` dropped) shows the 1–4+ image grid with spoilers, ALT,
a viewer and file rows on own posts, server thread rows and shared feeds. `journal-social.ts`
mirrors Android's `JournalSocial` (tests in `journal-social.test.ts`). Parents build one
`socialIndex` per list and pass `social`/`saved`; a card without them hides repost/save.
Slices 3–6 (all deployed web-only, each demo-checked on F:/DunBuild/ui8-demo): 3 tabs (Our system,
Friends, Journals, Saved, Lists, Feeds; `JournalFriends`/`FriendPostCard`/`friends-feed.ts`); 4
composer files as `post.attachment` rows, Quote via `journal-compose.svelte.ts`, tab-kept drafts
(`journal-draft.ts`, sessionStorage; CLIENTS.md §4.2) and null author intent; 5 Change audience
(`PostAudienceDialog`, `possiblyWidens`) and the composer's AudienceChip; 6 the thread's focused own
post uses `PostCard full` (time · date · audience, counts, large icon row).

Still different from Android (next, in order): another account's focused post in a thread still
uses the older thread row (no large action row; hearts via the reply box only); a "new posts" pill
when the timeline grows while scrolled; the composer is inline rather than Android's sheet/FAB (a
deliberate desktop difference, not a gap); Android's "posting as"/offline subtitle under the
Journal title; friends' posts aren't kept offline on either client (server read).
