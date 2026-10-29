# UI6 batch2 R1–R3 corrections — 2026-10-29/30

Implementer: GPT-6.1-sol, working directly without delegates in
`F:/DunBuild/chorus-ui-redesign`, branch `feat/figma-ui-redesign`.
Starting HEAD: `98c7b9ebc067312372441008f1aca5e59c4e7750`; start checkpoint `44f5ac8`.
This is corrective implementation, **not independent acceptance**. A NEW independent Sol-6.1
reviewer must review this result against `ui6-batch2-review.md` and its probes. Slices 7/9 stay
`[~]`; 1/2/8/10 remain accepted; 3/4/5/6/11 remain unimplemented. UI6 is not complete.

## R1 — occurrence identity and editor offsets

**Root cause:** `ChatEdit.names` stored one target per visible label. Re-parsing a valid bio
containing two differently targeted `@Kai` occurrences assigned both the map winner. The same
problem affected group/member collisions, custom emoji, and the shared chat edit path.

**Fix:** `RichTextEdit` retains saveable character provenance for each original UTF-16 unit,
independent of the name directory. The pure core parser now provides a native source map
(`parse_markup_mapped`, enabled by chorus-ffi's `editor` feature). Escapes, formatting markers,
quotes, links, code and supplementary characters map emitted units to their actual source
positions. On Save, an original atom keeps its identity only when its original characters still
form the same complete occurrence. Offsets come from the newly parsed text, never stale spans.
Formatting can split an atom's label in markup while leaving its plain-text identity intact.
Deleted/retyped/extended, escaped and literal-code tokens do not inherit a stale target. Newly
typed tokens use the normal directory; legacy labels supplement it only when unambiguous.

Profile TextField/toolbar updates preserve the provenance through saved-state recreation.
Chat edits use the same mechanism per author segment and retain TextFieldValue selection and
IME composition. Selection disambiguates identical-token insertions/deletions. A bounded Myers
fast path and linear-space LCS fallback handle bulk formatting without an
O(bio-length²) allocation; a 180-line quote regression exceeds the fast path and still retains
every identity. Explicit toolbar formatting retains selected characters; a selected TextField
replacement is new input and resolves normally. Shorter parsed tokens inside an unchanged
formatted occurrence cannot steal its identity. Restored entities keep core's canonical order.
No identity is selected by a label-map winner.

**Regression evidence:** `RichTextEditTest` has twelve actual-FFI tests, including the original
failing member-patch probe; duplicate member names, member/group same-label targets, distinct
custom-emoji IDs, insertions/deletions before/between/after, selection-sensitive duplicate removal,
delete/retype, escaping/code, nested/partial formatting, 9,000-character formatting, saved-state
JSON, supplementary mention characters, selected replacement versus formatting, a known `@K`
prefix inside `@K**ai**`, canonical
same-range entity ordering, bulk quotes, and chat author/offset preservation. Existing
`ChatEditTest` and `MemberCustomizationTest` remain in the passing normal suite.
`editor_source_map.rs` checks actual parser/source-unit agreement through escapes, quotes,
links, code and surrogate pairs. The source-map feature has no WASM export.

**Native:** on the fresh APK, Violet's fictional bio was saved as bold text plus two live
Amarynthe mentions, reopened, appended with `!`, and saved at 150% text with IME. Read-only
fictional demo DB assertions confirm text, bold entity, both mention IDs and offsets 11/22.
Cancel/Discard of a further edit left that saved state unchanged. Cold and normal relaunches
retain it. This native case uses two occurrences of one live target; the differently targeted
same-label cases are executable regressions, not an induced native fixture.

## R2 — portable member positions

**Root cause:** JVM `lowercase(Locale.ROOT)` and JS `toLowerCase()` use different Unicode
versions. U+1C89/U+1C8A and U+A7CB therefore changed sort order and insertion neighbours.

**Fix:** D-091 precisely corrects D-090's fallback contract: fold only ASCII A–Z units for an
unset position, preserve all other UTF-16 units, and encode units as lowercase four-digit hex.
Non-null legacy keys (including empty/invalid keys) encode verbatim. Valid `~ui6:` fractional
positions and identity suffixes remain unchanged. No locale collation, Unicode tables or
normalization participate. The pure `chorus_core::member_order` reference accepts UTF-16 units;
Android and web fallbacks pass the same fixture. Keeping this reference unexported avoids a new
WASM API/dependency. Ordinary manual-only Members ordering, sparse writes, eligibility and stale
Apply guards remain intact; fronts, recents and search semantics were not changed.

**Regression evidence:** expanded `fixtures/ui_member_order.json` covers the exact Unicode
version-skew pairs, Turkish/Greek/sharp-S/accent/combining/Deseret casing, display-name fallback,
empty/invalid/control/legacy/duplicate/prefix positions, ID ties, emoji, surrogate pairs and
isolated surrogate units. Rust/JBR/Node all check the same expected keys/order. The original
Unicode probe now asserts the explicit portable order (`z,a` for U+1C89/U+1C8A), rather than
adopting one runtime's previous casing table. The original 600-move pathological-position probe
is promoted unchanged into the normal suite: strict ordering and at most one write per single
move. Existing 500-member projection/sparse-write, 100 draft moves, 400 repeated moves,
foreign/archive/stale/no-op tests also pass.

**Native:** actual handle drag changed the 12-member preview; Cancel preserved all 12 persisted
keys. An accessible Later move of Violet, Apply and cold relaunch persisted Foxglove before
Violet. A read-only before/after comparison shows **only Violet's key changed**. Earlier/Apply
restored the original visible order. This is 12-member native evidence; the 500-member check is
JVM evidence, not native timing.

## R3 — complete field-save preflight

**Root cause:** `!opening.exists || latest.type == expected` bypassed validation precisely when
an ID appeared after opening. Save then skipped `field.define` and wrote a URL into a live number
field. Dropping tombstoned definitions from ProfileDetails also hid occupied/deleted IDs.

**Fix:** `MemberProfileSave.plan` builds the whole operation plan before any member/field op.
Every changed reused live ID must have the expected type, including absent-at-open IDs.
Formerly live missing definitions, tombstones and absent-slot IDs now used for a different
field reject with a clear cancel/reopen message. ProfileDetails retains unavailable IDs.
Established bindings never jump to another matching ID. An absent slot may reuse a newly
appearing matching definition at another ID only when neither the opening nor destination
value would be rebound/overwritten; otherwise it asks to reopen. Live metadata/privacy is never
redefined. New definitions remain private. Untouched invalid legacy values are neither
validated nor written. `Chorus.saveMemberProfile` runs planning and creates on the serial
replica worker, closing the gap for sync between preflight and writes.

**Regression evidence:** eight real save-planner tests cover absent-at-open compatible and
incompatible IDs (all four slots), deleted/redefined definitions, another-ID matching races in
both arrival orders, metadata/privacy changes, established/orphan/destination values, private
new definitions, untouched invalid legacy values, foreign/missing members and new-member
preflight. Failure tests assert that no operation plan is emitted, including when member/name
and several field changes would otherwise precede the bad slot.

**Native:** Website Save on Violet reused the existing definition; a read-only DB comparison
confirms the URL value and unchanged definition IDs/types/default visibility, without a duplicate
definition. Incompatible/deleted concurrent-definition races are planner tests, not induced native
sync races. Original immutable snapshots/minimal member patches and preservation tests remain.

## Validation and artifacts

Evidence root: `F:/DunBuild/chorus-ui6-evidence/batch2/corrections/`.
The external `manifest.json` pins source/evidence hashes, test totals, commands and outcomes.

- `android-final-4.log`, `adversarial-results/`: **35 targeted tests
  passed, zero failures/errors** (12 rich edit, 8 save planner, 7 ordering, 2 chat edit, 6 member
  customization). These results are distinct from the ordinary whole-app gate.
- `core-regressions.log`: source-map and portable-order reference tests passed. `web-order.log`
  passes the actual TypeScript fixture. `web-check.log`: zero errors/warnings.
- `android-final-4.log`: assembleDebug, compileReleaseKotlin, lint and targeted JVM tests pass.
  `native-core.log`: ARM64/x86-64 release core libraries and generated Kotlin bindings rebuilt.
- `full-verify.log`: required full `python scripts/verify.py --offline --android` passed,
  exit 0, with the F: environment, serial Rust tests and two build jobs. Rust release:
  **398 passed / 12 ignored**, zero failures; web **110 passed / 1 skipped**; Android lint and
  **208 app + 7 bridge JVM tests**, zero failures/errors. This gate includes the final runtime
  source and all 35 targeted cases in the normal suite.
- WASM gzip is **307,189 / 307,200 bytes** (11 bytes headroom, 78 bytes above the old 307,111).
  No budget was raised and no dependency/toolchain version changed.

APK: `corrections.apk`, debug **0.1.568**, SHA-256
`e1c0700ad3c7f6a7025afef5440101967d6d55a20958ef68f427c4813bc50242`.
Built from the final runtime source before the correction commit; the manifest pins its source
hashes instead of pretending the dirty build had a later commit ID.

Native evidence includes `editor-bio-ime-150.png`, `bio-saved-150.png`,
`punctuation-saved-150.png`, `bio-discard-150.png`, `profile-cold-150.png`,
`field-ime-150.png`, `field-saved-150.png`, `reorder-drag-draft-150.png`,
`reorder-accessible-draft-150.png`, `order-cold-150.png`, `chat-draft-ime-150.png`,
`chat-draft-restored-150.png`, `members-normal-restored.png`, `profile-normal-final.png`.
Final-artifact checks are explicitly logged in `native-final.log` and
`native-final-remaining.log`, with `final-italic-draft-150.png`, `final-italic-saved-150.png`,
`final-bio-restored-150.png`, `final-reorder-150.png`, `final-drag-preview-150.png`,
`final-normal-order.png` and `final-normal-profile.png`. They repeat real selected-text toolbar
formatting/Save, selected replacement/Save, handle drag/Cancel and sparse Apply/restore on the
final APK. Read-only assertions confirm the same text/mention IDs/ranges and unchanged Cancel
positions. `first-pass.apk` (01585b55…) and `strengthened-pass.apk` (84af32ca…) retain earlier
artifact provenance; earlier unprefixed captures are evidence for those builds, with unchanged
ordering/planner/navigation source, not falsely labelled final-APK screenshots.
Read-only DB audit JSONs prove saved data/sparse writes; screenshots alone are not that proof.
A Chat draft survived a Members/profile round trip and was then cleared without sending.
Focused routes hide navigation chrome and root Back restores it. No message or post was sent.

`partial-format-prefix-red.log` is a real red/green adversarial regression: the stronger known
prefix case failed before the nested-token fix and passes in the final suite.
Early compile attempts (missing import/test Model.copy), one incorrectly offset new test fixture,
transition-time empty XML dumps, a read-only audit's incorrect `archived` column and an exploratory
scroll/focus capture are recorded as invalid attempts in the external manifest. Later explicit
passing tests/audits/captures are the evidence; failed attempts are not success proof.

## Limits and handback

No finding is left knowingly unimplemented; independent acceptance remains pending. Native
races/differently targeted duplicate-label setup, physical Samsung, tablet, TalkBack, native
500-member performance and frame timing were not measured. The Samsung was absent from ADB.
No new crop/offline-image claim is made; earlier batch2 evidence remains historical.

The fictional Violet bio/website remain as test data; its original visible list position was
restored. All existing device data was retained. The emulator was force-stopped/relaunched
normally without test extras at the end; the exact existing tcp:5265 mapping stayed unchanged.
No keep-awake helper, owner account mutation, message/post send, credential output, server
restart, service/system-setting change, push, merge or deployment. The earlier server-restart
approval block was not retried or bypassed.

Next: the parent launches a NEW independent Sol-6.1 review of this corrective commit and its
regressions/native evidence. Do not mark UI6 or slices 7/9 accepted from this implementer report.
