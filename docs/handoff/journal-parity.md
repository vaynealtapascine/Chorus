# Journal parity handoff

Gemini presentation work is parked after quota exhaustion. Preserve its dirty web files: data.ts, post-threads.ts, posts.ts, ui/AttachmentView.svelte, blob-url.ts, journal-presentation.ts, ui/PostMedia.svelte. They are not part of the Android slice.

Android slice: Journal composer and audience dialog use AudienceChip, carry bucket IDs through create and post.set_visibility, and reject empty or unavailable bucket selections. Model retains the full audience. Server remains under More. Reply, quote, media and poll assembly remain in place.

Ordered remainder: multi-author; drafts/editor; remote fidelity/offline feeds; web presentation/social actions. No deployment or device changes.

Validation evidence: Full `python scripts/verify.py --android` passed, exit 0 (F:/DunBuild/journal-full.log), after build-web-core prerequisites, using F: builds and Studio JBR. Separate `:app:testDebugUnitTest :app:assembleDebug` passed, exit 0 (F:/DunBuild/journal-android.log). Targeted tests cover create payload bucket preservation with quote/reply/media and visibility payload rejection of empty/missing/mixed missing selections. No device validation.
