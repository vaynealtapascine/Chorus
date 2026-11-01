# Journal parity handoff

Gemini presentation work is parked after quota exhaustion. Preserve its dirty web files: data.ts, post-threads.ts, posts.ts, ui/AttachmentView.svelte, blob-url.ts, journal-presentation.ts, ui/PostMedia.svelte. They are not part of the Android slice.

Android slice: Journal composer and audience dialog use AudienceChip, carry bucket IDs through create and post.set_visibility, and reject empty or unavailable bucket selections. Model retains the full audience. Server remains under More. Reply, quote, media and poll assembly remain in place.

Ordered remainder: multi-author; drafts/editor; remote fidelity/offline feeds; web presentation/social actions. No deployment or device changes.

Validation evidence: Full `python scripts/verify.py --android` passed, exit 0 (F:/DunBuild/journal-full.log), after build-web-core prerequisites, using F: builds and Studio JBR. Separate `:app:testDebugUnitTest :app:assembleDebug` passed, exit 0 (F:/DunBuild/journal-android.log). Targeted tests cover create payload bucket preservation with quote/reply/media and visibility payload rejection of empty/missing/mixed missing selections. No device validation.

2026-11-01 Sol follow-up: Opus accepted bucket slice 617e168 and chat fixes fbb1f64. Fixed person-account audience wording, rotation preservation of selected bucket IDs, and widening warnings (bucket subset narrowing is quiet; incomparable sets warn). Multi-author creation remains the next implementation step; no drafts, web presentation, schema, deployment or device changes.
Validation for follow-up: full explicit python scripts/verify.py --android passed exit 0, F:/DunBuild/journal-full-r2.log (web-core prerequisite completed; F: environment and Studio JBR). Includes AudienceExpansionTest. Fixed pre-existing PostComposeTest Model.copy compile failure by constructing the fixture directly. No device validation.

2026-11-01 Sol multi-author: composer now toggles explicit authors, preserving selection order and rotation state. Payload deduplicates in first-occurrence order and rejects empty, foreign, missing or inactive authors against the live model after attachment staging. Defaults still use canonical Reply.speaker; explicit profile/reply/quote launches reset to single author; person accounts hide the picker. No subsystem expansion. Existing audience/buckets/poll/media assembly retained. Tests exercise picker transition through payload and malformed stored audience fail-closed. No web, draft, schema, lifecycle, deployment or devices.
Validation: build-web-core prerequisite exit 0; full explicit python scripts/verify.py --android exit 0, F:/DunBuild/journal-multi-full.log (release simulator, Rust/server/web checks, Android lint and unit tests; BUILD SUCCESSFUL). F: environment and Studio JBR used. No device validation.
