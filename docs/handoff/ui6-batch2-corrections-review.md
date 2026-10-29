# UI6 batch2 corrections — independent review, 2026-10-30

Reviewer: GPT-6.1-sol, independent of the original implementer, previous reviewer and
correction implementer. No delegates. Actual checkout `F:/DunBuild/chorus-ui-redesign`,
`feat/figma-ui-redesign`; reviewed `98c7b9e..a559ee0` (32 files), the original profile/order
paths, AGENTS/PROGRESS, D-090/D-091, owning specs, open questions and both preceding reviews.

**Verdict: changes required. R2 is resolved; R3 is resolved with the small additional fix
in this review. R1 remains blocked by undo identity loss and a native saved-state crash.**
Do not accept slices **7/9** or mark UI6 done. Slices **1/2/8/10** remain accepted;
**3/4/5/6/11** remain open. No implementation of those remaining slices was attempted.

## Blocking findings

### R1a — P1: text undo restores labels without their identities

`data/RichTextEdit.kt:update/matches`, `ui/MemberEditor.kt:editBio`, and the shared
`ui/Chat.kt` edit callbacks carry only the current provenance, with no provenance undo history.
Starting with `@Kai @Kai` targeting member **alpha** then group **beta**:

1. Select/delete the first token and its following space. The remaining occurrence correctly
   retains beta.
2. Undo that text deletion, restoring `@Kai @Kai`.
3. Append `!` and save. The first occurrence resolves to **directory**, not alpha; beta survives.

The actual-function probe `textUndoMustRestoreDeletedOccurrenceIdentityBeforeFurtherTyping`
fails with expected `[alpha, beta]`, actual `[directory, beta]`. The same transition path is
used by profile and chat edits. A formatting-only undo probe passes; that does not cover undo
of a deletion/replacement. This is a distinct case from intentionally deleting and retyping a
token, which must continue to resolve through the directory.

Required correction: restore editor text, selection and occurrence provenance together for undo
and redo, including author segments and recreation. Do not infer undo merely from matching
labels, because that would resurrect identities for deliberate retyping. Add regressions for
delete/undo/nearby edit, selected replacement/undo, redo, and same-label different targets for
member/group/custom emoji. The synthetic event-sequence test is source/host evidence; the
different-target case was not injected into the native account.
On final APK 0.1.570, native Ctrl+A/Delete/Ctrl+Z in the bio restored its text at 150%;
the same-target native fixture confirms text undo is available, not different-target identity safety.

### R1b — P1: a valid bio crashes the activity when it saves state

`ui/MemberEditor.kt` saves the opening member JSON (`snapshot`), `baselineBio`, `bio`, and the
full JSON `bioDraft`. `RichTextEdit.open` duplicates the original text/entities and serializes
one origin integer per UTF-16 unit. This exceeds Android's activity-state Binder transaction
limit for valid existing data; linear-space diff allocation does not address saved-state size.

Actual native reproduction on **0.1.568**, emulator-5554, fictional account only:

1. Temporarily rename fictional Violet to Kai, so newly typed `@Kai` resolves to a live member.
2. Save exactly `"@Kai "` repeated **2,000** times: **10,000 UTF-16 units**, within the editor's
   accepted limit, **2,000 mention entities**. A delayed read-only demo DB audit confirms both
   counts. Construct this with native selection/copy/paste and verify actual field lengths;
   rapid ADB `input text` drops characters and is not a valid long-input probe.
3. Reopen Edit profile, then press Home. No unsaved edit is needed.
4. The activity crashes on its main thread with **TransactionTooLargeException**,
   **1,151,760-byte** parcel. The saved-state registry accounts for **1,150,644 bytes**.

Evidence: external `large-verified-delayed.json`, `native.log`, `large-background.log`.
This is an observed crash, not an estimated Bundle size. The data was saved before the crash;
it is not evidence of deletion of the persisted bio. A host 9,999-unit/2,000-entity probe measured
308,707 characters for the initial provenance JSON alone, plus 235,779 characters of original
entities also present in the opening snapshot. Quote update/parse took 568/1,030 ms in the final
host run (earlier runs varied); these are host timings, not native frame or latency measurements.

Required correction: keep large draft/baseline/provenance data outside activity saved-state
transactions, with bounded saveable references and durable, account-scoped restoration. Preserve
the immutable opening snapshot and identity semantics; do not silently discard rich entities or
lower the supported legacy-data size to evade the crash. Add a real native background/recreation
check at the supported maximum, with a shared chat draft present, and measure/bound formatting
work on long bios instead of doing unbounded quadratic LCS work on the UI thread.

## Small correction made here: complete R3's reserved-ID guard

An absent-at-open reserved Website ID can acquire a **value** before its definition arrives.
If a matching definition at another ID also arrives, the planner checked the opening old value
and latest destination value, but missed the **latest old-ID value**. It emitted `member.set`
and a write to the other ID, silently moving the visible binding away from the concurrent value.

`MemberProfileSave.plan` now rejects adoption when `details.values[member][reservedId]` is
nonempty. The existing normal-suite adoption test now covers this arrival order. The independent
probe failed before this change and passes afterward. It remains a complete preflight: no
operations are emitted on rejection. No metadata, privacy or existing value is rewritten.

The worker is an actual single-thread executor; `awaitModel` reads `r.projection()` directly,
not the asynchronously rebuilt StateFlow. Nested `withContext` calls use the same dispatcher and
do not yield between preflight and synchronous creates. Incoming sync work also runs there.
Validation rejection is all-or-none; the op sequence is not a new transactional disk batch.
Existing storage failures stop writes with StorageError, so a hardware/storage failure can leave
earlier operations persisted. No stronger storage-atomicity claim is made.

## Corrections that hold up

| Before | After reviewed | Why |
| --- | --- | --- |
| One identity per visible label | Original UTF-16 occurrence provenance plus parser source maps | Nearby edits and tested formatting preserve distinct member/group/emoji identities; R1a/R1b qualify acceptance |
| Runtime Unicode lowercase fallback | D-091 ASCII A–Z fold only, raw UTF-16 hex and ID suffix | JVM, web and pure-core reference share explicit Unicode/surrogate/legacy fixtures |
| Absent-at-open fields bypassed type guard | All changed slots preflight latest definitions and values on the serial worker | Tombstones, incompatible IDs and unsafe other-ID adoption reject before ops, including the added latest-orphan case |

R2's valid fractional keys remain unchanged, non-null legacy keys encode verbatim, and unset
keys use non-null display name (even empty) or name. Prefix/tie ordering is strict. The 600-move
single-write and old 500-member sparse-write tests remain in the normal suite. Members-only
consumers use these keys; fronts, recents and search paths retain their previous semantics.
There is no persisted fractional-key migration or budget increase.

The parser change only adds feature-gated mapping data: 600 independent native cases have
matching ordinary/mapped rich output, monotone in-bounds sources and matching UTF-16 units.
Another 600 cases compare actual native editor-feature output with actual no-editor WASM output;
text/entities agree structurally. An early assertion incorrectly compared JSON object key order;
it was corrected before recording these passing results. No panic/OOB occurred in these probes.
The unchanged size gate uses **gzip level 9**: **307,189 / 307,200 bytes** (11 bytes headroom).

## Validation, artifacts and native evidence

Evidence is separate at `F:/DunBuild/chorus-ui6-evidence/batch2/corrections/review/manifest.json`.
The inherited correction manifest was audited: **31 source hashes** (excluding the review's
PROGRESS edit) and **97 evidence hashes**, zero mismatches. The original `corrections.apk` is
debug **0.1.568**, SHA-256 `e1c0700ad3c7f6a7025afef5440101967d6d55a20958ef68f427c4813bc50242`.
Its native-final logs/final-* captures were inspected as final artifact evidence; first-pass and
strengthened-pass captures remain historical. The inherited full release gate passes on those
pinned sources; this review does not relabel that gate as a rerun on its small Android fix.

- `start-verify-full.log`: required quick/offline/Android gate passed before the start commit.
- `probes-final-results.xml`: **8 independent probes, 7 passed / 1 failed** (R1a). Includes the
  fixed latest-orphan check, 500 random monotone diff checks, both parser corpora, boundary edits,
  formatting undo and max-bio measurements. The failed probe stays outside normal test sources.
- `planner-fixed-results.xml`: **8 planner tests passed** after the small R3 correction.
- `android-final.log`: debug APK assembly, release Kotlin compilation, lint and normal app JVM
  tests passed. No new Rust/core code or dependencies were added by this review.
- `final-verify-full.log`: final required `F:/DunBuild/chorus-verify.ps1 --android` quick/offline
  gate passed with serial Rust tests/two build jobs: **398 Rust passed / 12 ignored**, web
  **110 passed / 1 skipped**, **208 app + 7 bridge JVM tests**, zero failures/errors, lint and
  unchanged budgets. The deliberately failing review-only undo probe is excluded from that suite.

This review's final artifact is **review-r3.apk**, debug **0.1.570**, SHA-256
`b376829e2d4a8786bf3ed47743e950b3766a6963a2815acf3ace29750b11c3b9`.
It includes only the small R3 runtime correction described above. Installed without clearing data;
the pulled installed base APK has the identical hash. Built before this review's final commit;
the external manifest pins runtime source hashes. No Rust/FFI runtime was changed by this review.

The reviewer independently exercised and inspected:

- Original final 0.1.568: 150% selected-text Italic/IME/Save, read-only confirmation of the bold,
  italic and two mention IDs/ranges, then selected replacement/Save restoring the original bio.
  A small unsaved bio draft survived actual process death and restored task reopening, then
  Cancel/Discard preserved saved data. Restoration capture is normal font because the new
  launcher intent removed the process inspection extras; it is not a 150% restoration capture.
- Original 0.1.568: the exact large-bio saved-state crash above. A first rapid synthetic input
  attempt and an initial copy/paste attempt produced short text, so neither was used as proof.
  The successful paste builder checked observed field lengths at every step, reaching 10,000.
- Final 0.1.570: normal-font profile Save restored Violet's name and original rich bio; delayed
  read-only audits match the original text and entities exactly. 150% native text undo,
  Cancel/Discard, focused chrome and root Back were checked again.
- Final 0.1.570 at 150% / reduced motion: actual drag preview/Cancel retained all 12 persisted
  keys. Later/Apply changed only Violet's key. A real upward handle drag/Apply changed only
  Violet's key again, restoring the original order **and original keys**. Final DB comparison
  confirms every original key. Early downward swipe/Apply attempts ended as no-ops and are not
  persisted-drag proof; `final-up-drag-*` plus its DB audit are the valid Apply evidence.
- Final 0.1.570: an initially empty General composer retained `Review draft keep` through a
  Members/profile/reorder round trip, including its sender. The test draft was cleared without
  sending. Scoped fields/actions retain the charcoal/teal/Hanken presentation; toolbar icons
  and drag handles use explicit 48 dp targets. Reduced motion disables row placement movement
  in source; no frame timing or complete Material animation claim is made.

The first post-install launch also recorded an input-focus ANR; a normal force-stop/relaunch
recovered. This was not root-caused or scored as another implementation finding. Empty/transient
UI dumps and an initial incorrect process-reopen attempt are recorded as invalid attempts in
the manifest. Final native checks above occurred after recovery.

Durable reproducer: [ui6-batch2-corrections-review-probes.kt](ui6-batch2-corrections-review-probes.kt).
Copy to `android/app/src/test/kotlin/garden/vayne/chorus/data/CorrectionReviewProbeTest.kt`, source
the documented F: environment/JBR/Gradle cache, and run `:app:testDebugUnitTest --tests
garden.vayne.chorus.data.CorrectionReviewProbeTest --offline --console=plain`. The parity case
uses external `parser-corpus.json`, generated by `review/parser-corpus.mjs`; the undo and
reserved-ID cases have no external fixture dependency. Remove the temporary copy afterward.

## Scope and handoff

Native differently targeted duplicate labels and induced definition/sync races were not set up.
No Samsung/physical/tablet/TalkBack, native 500-member timing or frame-timing claim. The earlier
crop/offline review remains valid historical evidence; those paths were not changed here.
No messages/posts, owner-account changes, server restart, system settings, services, push, merge
or deployment. No keep-awake helper was started.
Final fictional name/bio and all 12 order keys match their opening saved values; the existing
Website value was retained. The emulator was force-stopped/relaunched normally without test
extras, with its original `host-17 tcp:5265 tcp:5265` reverse mapping unchanged. Samsung was absent.

Next: correct both R1 findings and request a NEW independent Sol-6.1 review. Keep slices 7/9
pending together until all three original blockers and these remaining R1 cases are clear.
