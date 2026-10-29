# UI6 batch 2 independent review — 2026-10-29

Reviewer: GPT-6.1-sol, different from the implementing agent; no delegates.
Reviewed the actual `3279013..193d91f` diff (39 files), D-090, the task/spec
changes, implementation handoff, batch 1 review, source and native artifacts.

**Verdict: changes required. Slices 7 and 9 are not accepted.** Two executable
adversarial checks fail on the implementation. A third correctness defect was
identified in the field-save preflight. These require a corrective implementation
and fresh independent review, rather than a cosmetic review fix. Runtime source,
spec behavior and the acceptance checklist are unchanged by this review.
Slices 1/2/8/10 retain their prior acceptance; 3/4/5/6/11 remain open. UI6 is open.

## Findings and required resolution

### R1 — P1: editing nearby bio text changes existing mention identities

Location: `data/ChatEdit.kt:37–57`, called by `ui/MemberEditor.kt:125–127` and
`data/MemberCustomization.kt` when parsing the edited bio.

The resolver is a map from visible token to one target. An existing bio with two
`@Kai` entities targeting different members is valid (names are not unique).
The second entity overwrites the first resolver entry. Append only `!` to this
bio and Save: both entities now target the second member. This silently corrupts
existing rich text even though neither mention was edited. Group/member label
collisions have the same structural problem. The new single-mention regression
test does not exercise this case.

Actual-function probe `distinctMentionTargetsSurviveNearbyEdit` failed:
expected first target `alpha`, actual `beta`; both resulting targets were `beta`.
The full source and exact failure are retained below/in the external XML.

Required: preserve identity per original entity occurrence across nearby edits,
including duplicate visible labels, while resolving newly entered tokens normally.
Do not merely choose a different winner for the token map. Add passing tests for
duplicate names, differently targeted same-label entities, custom emoji identity,
and inserted/deleted text around them. Retain the existing chat-edit and author
segment regressions because this resolver is shared with chat.

**Resolution: open; blocks slice 7.** No owner data was used or mutated to reproduce.

### R2 — P1: Unicode fallback order differs between Android/JVM and web

Location: `data/MemberOrdering.kt:12–17`, `web/src/lib/member-order.ts:7–12`.

Locale-independent comparison is insufficient when the position is derived using
each runtime's Unicode lowercase tables. On the configured JBR 21.0.10, U+1C89
stays U+1C89; on the installed Node v26.12.1 it lowercases to U+1C8A. U+A7CB also
differs (JBR keeps A7CB; Node produces 0264). Browser/Android OS Unicode versions
can likewise differ; neither runtime's tables are pinned by this wire contract.

Actual keys for `(id=z,name=U+1C89)` and `(id=a,name=U+1C8A)`, unset sort keys:

| Runtime | Keys | Order |
| --- | --- | --- |
| Actual Kotlin `MemberOrdering` on JBR | `1c89!007a`, `1c8a!0061` | z, a |
| Actual web module on Node | `1c8a!007a`, `1c8a!0061` | a, z |

Thus identical replicated rows have different order and insertion neighbours.
The executable Kotlin parity probe fails; `review/web-unicode.log` imports the
actual TypeScript module and records the opposite order. This is not a locale
preference. The shared fixture's É/emoji coverage misses Unicode-version skew.

Required: make the fallback transform a specified, stable cross-client algorithm,
with identical results on the supported Android versions and web. Preserve existing
fractional positions and sparse writes; extend the shared fixture with these pairs,
empty/invalid/legacy keys and casing edge cases. If core/WASM changes are chosen,
run the full budget gate: current WASM headroom is only 89 bytes. Do not silently
change D-090's contract or widen manual ordering to fronts/recents/search.

**Resolution: open; blocks slice 9.**

### R3 — P2: a newly appearing field bypasses the type-change guard

Location: `ui/MemberEditor.kt:134–153`, especially line 139.

Reproduction from the save code: open the editor when its deterministic Website
definition is absent (`exists=false`). Another device defines that same ID and
changes its type to `number` before this editor saves. Enter a URL here and Save.
`!def.getBoolean("exists") || existing?.type == expected` succeeds because the
opening definition was absent. The following loop sees the ID in `latest`, skips
`field.define`, and writes the URL string into the incompatible number field.
`field.set_def` supports type changes. This also applies to Location and the other
website slots. The existing guard protects only definitions present at opening.

Required: validate every live ID reused at Save, including those first observed
after opening, before emitting any member/field op. Reject incompatible definitions
with the existing reopen message and preserve their metadata/privacy. Test the
absent-at-open/compatible-now, absent-at-open/incompatible-now, deleted/redefined,
and simultaneous matching-definition cases through the save planner, not just
deterministic UUID generation. Consider newly appearing matching definitions with
other IDs without silently duplicating or rebinding existing values.

**Resolution: open; source-confirmed, not an induced native sync-race test.**

The UI review corrections are proposed, not implemented:

| Before | After required | Why |
| --- | --- | --- |
| One resolver target per visible bio token | Retain original entity identity per occurrence | Editing punctuation must not retarget a mention |
| Runtime-dependent lowercase position fallback | One stable specified transform and shared fixtures | Replicas must agree on order and neighbours |
| Type guard only for definitions present at opening | Preflight all reused live field IDs | Concurrent sync must not put a URL into a number field |

## Checks that held up

- Opening member snapshots and field values are saveable and immutable; minimal
  member patches omit untouched images, privacy maps, sigils, ordering and legacy
  values. Removing an untouched image through concurrent sync is not reintroduced
  by an unrelated Save. Profile metadata routes remain own-account only.
- Field matching is deterministic by ID, case-insensitive by name with exact kind;
  newly generated IDs include the account. New definition visibility is private.
  Save does not explicitly overwrite existing default/member field visibility.
  Website/date validation and yearless leap-day coverage are present. R3 qualifies
  the concurrent definition claim.
- Source copying/decode bounds are 10 MB/100 MP/1600 px. EXIF-oriented dimensions
  feed the same crop geometry in preview/output. New PNGs have an immediate
  hash-verified copy in the account's durable kept directory; banners join the
  offline/restore catalogue. The broader pre-existing transient blob caches are
  shared app caches; this is not a new claim of isolation for every cache path.
- The reorder planner rejects foreign/archived/stale targets, checks current full
  eligibility/order at Apply and uses sparse writes. A separate 600-move probe
  covering empty/missing/invalid/duplicate/legacy/prefix keys passed strict-order
  and at-most-one-write assertions. The existing 500-member persistence test passed
  in the required gate. This is not native 500-member performance evidence.
- Manual order is applied in Android/web Members. Model/member data do not globally
  switch to manual order; explicit front, recent and search semantics remain separate.
- The server addition is nullable own-member `sort_key`. Read/export/rebuild and
  follower/foreign-denial tests are meaningful. Revealing an avatar does not grant
  its member's banner. No public author-card/follower metadata was expanded.

## Independent native inspection

Installed artifact, unchanged runtime source: `F:/DunBuild/chorus-ui6-evidence/batch2/batch2.apk`,
debug **0.1.565**, SHA-256
`da9fb4294f46e899c453ea4e0ca19ca71930ac63bb448eaec3a8687dffa70a2a`.
I independently checked this hash. Original `manifest.json` pins 39 source hashes;
all runtime/test/doc hashes match except the deliberately updated `PROGRESS.md`.

Own evidence is under `F:/DunBuild/chorus-ui6-evidence/batch2/review/`:

- Actual handle drag with edge scrolling on the 12-member fictional Lanterns list;
  Apply and cold relaunch: `drag-small-draft.png`, `order-cold-saved.png`.
  Violet was temporarily moved down, then returned to first with an upward held
  drag and Apply. `up-edge-before/held-half/held-full/after-release.png` and
  `up-edge.py`/`.log` retain the full second gesture sequence.
- Genuine 150% text and process-local reduced motion: `profile-150.png`,
  `editor-long-ime-150.png`, `crop-banner-150.png`, `crop-actions-150.png`,
  `discard-150.png`, `reorder-150.png`, `reorder-drag-150.png`. Long display-name
  draft, keyboard, Save/Cancel, crop sliders/actions and discard remain reachable.
  Cancel crop preserved the editor draft; Discard restored the saved profile.
- Completed drag draft survived actual process death: `recreation-pids.log`
  (6265 → 6475), `reorder-draft-restored.png`; Cancel then returned to saved Violet
  first order (`reorder-cancel-saved.png`). An immediate XML dump after restart
  failed while loading; the later inspected PNG contains the restored list and
  is the evidence, not that failed dump. The restored launch used normal font.
- Repeated disconnected media Save independently: `offline-root.png` shows the
  native Offline caption after force-stop/removing only the existing tcp:5265
  reverse mapping. Selected the botanical photo, changed crop zoom, applied and
  saved a new avatar; `offline-saved.png` and `offline-cold-profile.png` show it
  before/after another cold launch while still disconnected. `offline-review.log`
  records the queued new hash, account-local durable copy, and exact mapping
  restoration in `finally`. `offline-copy-verified.txt` verifies SHA-256
  `858ea47babb3a65881bd1ef24fb9ea03e48243ff3f4f94e8246447e0ebfccce9` at the
  durable path. The temporary profile-source file was gone after Apply; the upload
  queue emptied after reconnect (`pending-after-reconnect.txt`). The new fictional
  avatar remains saved. No server restart or device connectivity settings changed.
- Members → Open journal opened the matching Journal profile; system Back returned
  to the Journal timeline with its bar restored (`journal-from-members.png`,
  `journal-back-root.png`). No post was composed or submitted in this navigation check.

An early partial-row regrab (`drag-held-top.png`/`held-release.png`) was exploratory;
its drop result was ambiguous. It is neither successful full-distance drag proof nor
a confirmed defect. The later full-row `up-edge-*` sequence has an explicit DOWN,
MOVE, held captures and UP, with the expected final draft order.

I also independently viewed the implementer's successful saved-field/media,
EXIF=6, corrected banner crop, large-font/IME, held-edge, offline cold-profile PNGs
and read `native-offline-final.log`, rather than treating their summary as proof.
All 83 original evidence files exist; hashes match except the mutable
`emulator-5554-current.xml`, overwritten by my initial inspector calls. Invalid first
crop/cold-load/offline attempts and the partial motion video are not success proof.

## Validation, reproduction and handback

- Read `final-gate.log`: full release Rust fmt/clippy/workspace, schema/API checks,
  web **110 passed/1 skipped**, JS 129 KB gzip and WASM 300 KB within existing caps,
  Android lint/tests completed with exit 0. Manifest records **186 app + 7 bridge**
  JVM tests. Runtime code is unchanged, so an identical full gate was not rerun.
- `review-start-gate.log` is this reviewer's required quick/offline/Android gate:
  WASM rebuild, fmt/clippy/workspace tests, web checks/tests/build/budgets and
  Android lint/tests passed, exit 0, before start commit `b45852e`.
- `review-commit-gate.log` / `review-commit-verify.log`: required verification
  repeated before this handoff commit and passed, exit 0. App XML totals are
  **186 tests, zero failures/errors**, bridge **7, zero failures/errors**; web
  **110 passed/1 skipped**, JS 129 KB/WASM 300 KB within unchanged budgets.
- `review/adversarial-tests.log` and `adversarial-results.xml`: three additional
  actual-function probes, **one passed/two failed**. These failures are the reason
  for withholding acceptance; a green ordinary gate does not resolve them.
- [ui6-batch2-review-probes.kt](ui6-batch2-review-probes.kt) is a durable reproducer,
  deliberately outside the normal test sources. To reproduce, copy it to
  `android/app/src/test/kotlin/garden/vayne/chorus/data/ReviewBatch2AdversarialTest.kt`,
  source `F:/DunBuild/chorus-env.ps1`, set Android Studio JBR and the F: Gradle cache,
  then run `android/gradlew.bat -p android :app:testDebugUnitTest --tests
  garden.vayne.chorus.data.ReviewBatch2AdversarialTest --offline --console=plain`.
  Remove the temporary copy afterward, or promote corrected tests with the fix.
  My temporary app-test copy was removed; no failing tests are hidden in the gate.

No physical Samsung UI/captures (it became connected, but was not used), tablet,
TalkBack traversal, native 500-member timing or precise frame-timing measurement.
No messages/posts or owner-account edits, services/system settings, keep-awake
helper, push, merge or deployment. Production and the existing demo server were
not restarted. The exact emulator tcp:5265 mapping was restored and the app was
relaunched normally without inspection extras. Remaining independent concurrency cases were source/test review,
not induced live races. Remaining features 3/4/5/6/11 were not implemented.

Next: correct R1–R3 on this branch, promote the repro cases to passing regressions,
recheck affected native flows and request a different independent Sol-6.1 reviewer.
Do not mark slices 7/9 or whole UI6 complete on this report.
