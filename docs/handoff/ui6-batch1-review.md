# UI6 first-batch independent review — 2026-10-29

Reviewer: GPT-6.1-sol, independent of the two implementing agents. Reviewed the
uncommitted implementation on `feat/figma-ui-redesign`, starting at `95d9cdf`.
No delegates. Scope accepted: slices **1, 2, 8, 10**. UI6 remains in progress;
slices 3/4/5/6/7/9/11 are still open. This accepts the implementation with the
explicit device/test limits below; it is not a full UI6 or release acceptance.

## Findings resolved in this review

1. **P1 — DM recipient list disappeared with 150% text and the IME.** The fixed
   description consumed the remaining list height. Hide that supporting description
   while the keyboard is open and bound the sender label to one line. Confirmed a
   visible, selectable Foxglove search result with the keyboard open on the new APK.
2. **P1 — Connected-account DM ignored the selected sender.** Account selection now
   requires a sender, validates that member against the latest own-account model
   before/after the request, and seeds only the destination's author. Native opening
   of the existing Junipers DM showed **Send message as Violet**, the chosen sender.
3. **P2 — Timeout/cancellation followed by retry could allocate another member-DM
   entity before a delayed model refresh.** Retain the participant/space key and
   entity ID in saveable state; repeated creation for that selection uses the same
   ID. Normal reuse still prefers the deterministic existing exact participant set.
4. **P2 — Navigation preview split Members midword at 150%.** Match the actual bar's
   one-line ellipsis behavior; the full label remains in the editor and semantics.

Expanded tests cover deterministic reuse among duplicate existing channels, rejecting
malformed duplicate participants, current member names, deleted/archived/foreign
selections, colour-only channel patches, omitted untouched/default colours and
concurrent metadata preservation. ChannelEditor now uses the tested colour patch
builder rather than adding that field separately in UI code.

## Source review

- Read as remains one submenu entry and a searchable keyed LazyColumn with selected
  radio state and Everyone present. It changes `viewingAs`, retaining the existing
  message filtering/read tracking/front-purpose rules. It adds no privacy enforcement.
- Navigation stores stable destination names per account on this device; resolution
  drops invalid/duplicate/system-only choices, bounds to four and retains fixed More.
  Explicit empty choices are valid; wholly invalid nonempty choices recover defaults.
  Person defaults remain Journal/Chat/Friends. Existing destination state holders,
  Chat/Journal snapshots, draft savers, focused chrome and child Back handlers stay.
- Colour picking stages HSV/hex locally; Cancel/Back do not call the parent callback.
  Apply changes only the parent draft (and device-local recents); editor Save persists.
  Member fields/sigils and channel fields compare with their saved opening baselines,
  so unrelated sync edits are omitted from writes. Channel colour remains an existing
  field. No new library, schema, server capability or core behavior was introduced.
- Internal DMs validate current own members and write ordinary offline `channel.create`
  ops in the internal space. Reuse requires equal full participant sets, kind and space.
  A bounded wait observes local projection; failures stay in the sheet for retry.
  Cancel/dismiss/back-to-choices cancel the request and preserve source drafts; coroutine
  cancellation is rethrown, and busy is cleared in finally. Account DMs retain the
  online API path. `member_dm` is the existing soft in-system convention, not slice 5.

## Native evidence

Evidence root: `F:/DunBuild/chorus-ui6-evidence`, fictional Lanterns demo on
`emulator-5554` only. No messages sent, member/channel editor saves or production
mutations. The reviewer created one empty Foxglove/Nairon/Violet group conversation;
earlier authors created the empty Amarynthe/Nairon/Violet group.

- `review-navigation-150-final.png`: no midword wrap, actions reachable; Cancel checked.
- Removed Chat from the bar, applied, opened it through More, opened an existing
  conversation; first Back returned to the chat list, second Back returned to More:
  `review-hidden-chat-back-child.png`, `review-hidden-chat-back-more.png`.
  Restored the prior demo bar order Members/Fronting/Journal/Chat afterward.
- `review-colour-150.png`, `review-colour-hex-ime-150.png`,
  `review-colour-actions-ime-150.png`: swatches/sliders readable; hex and Apply reachable
  above keyboard by scrolling. Back dismissed keyboard, picker and unsaved editor.
- `review-readers-ime-150.png`, `review-reader-selected-150.png`: fox search, Everyone
  present and Foxglove selection reachable; selection returned to the conversation.
- `review-dm-ime-150-final.png`, `review-dm-reused-150-final.png`: fixed IME layout,
  exact old Amarynthe/Foxglove two-message conversation reused with selected author.
- `review-dm-created-150-final.png`: new three-member DM, empty, Foxglove sender.
- `review-account-dm-sender-150-final.png`: existing Junipers account DM, Violet sender.

Earlier baseline evidence from the authors/parent records navigation Cancel/Apply and
relaunch persistence, colour Cancel preserving F28B82 versus draft-only Apply of 6D9ED2,
reader search, pair reuse and new group creation. These are distinguished from this
reviewer's checks above. `navigation-large.png` is invalid as large-text proof;
`navigation-150.png` and the review captures use the genuine debug modal override.

## Validation and limits

- `batch1-review-android.log`: offline Gradle `:app:assembleDebug`,
  `:app:compileReleaseKotlin`, `:app:lintDebug`, `:app:testDebugUnitTest` passed;
  **175 app JVM tests**, no failures. Existing draft/privacy/poll tests remain green.
- `batch1-review-verify.log`: required `pwsh -NoProfile -File
  F:/DunBuild/chorus-verify.ps1 --android` (WASM rebuild + verify quick/offline/Android)
  passed, exit 0. Rust fmt/clippy/workspace tests, fixture/schema/API checks, web
  typecheck/109 tests (1 skipped)/build/budgets, Android lint/tests passed. The bridge's
  7 JVM tests also passed. JS remains 129 KB gzip; WASM remains 300 KB within the cap.
- `batch1-full-verify.log`: inherited full release Rust/web/Android gate passed on
  this batch before review. Review changes are Android-only; no core/web or budgets
  changed. Web baseline: 109 tests passed/1 skipped; JS 129 KB gzip, WASM 300 KB.
- Debug-only modal density propagation is necessary because Material dialog owners
  replace LocalDensity. Release wrapper directly calls content. The 150% and
  reduce-motion extras are process-local; they do not change system settings.

No physical Samsung (absent), tablet, TalkBack traversal or native 500-member timing
test was run. Keyed lazy lists were reviewed, but that is not a device performance
measurement. No induced offline/cancellation race/15-second timeout was run natively;
those paths were reviewed in source, with member/payload/reuse checks in JVM tests.
The reduced-motion extra verifies the app override, not all Material animation timing.
No demo-server restart, service/system changes, keep-awake helper, push, merge or deploy.
Emulator relaunched normally without inspection extras before handback.

Final APK: `F:/DunBuild/chorus-ui6-evidence/batch1.apk`; checksum, source commit,
build version and evidence list: `batch1-manifest.json` beside it. The parent should
continue the remaining seven slices and request a fresh independent Sol-6.1 review.
