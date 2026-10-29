# UI6 batch 2 — member profiles and ordering

Implementer: GPT-6.1-sol, no delegates. Checkout `F:/DunBuild/chorus-ui-redesign`,
branch `feat/figma-ui-redesign`, clean starting
`32790135f25b4eecb0d8b3675c9f720ba73cf141`. Scope: slices **7 and 9** only.
Independent Sol-6.1 review/acceptance belongs to the parent and is **pending**.
UI6 remains open for slices **3/4/5/6/11**, plus this batch's independent review.
Accepted slices 1/2/8/10 are retained.

## Result and contracts

- Members opens the existing Journal profile route/presentation with a shared banner/avatar
  identity header, rich bio, names/pronouns, location/websites and existing profile content.
  Edit profile is explicit; Open journal connects to the existing Journal profile without
  replacing its composer data. Focused member routes hide the compact destination bar.
- The editor stages changes until Save, keeps an immutable opening snapshot through sync and
  recreation, preserves secondary sigils and untouched metadata, and guards unsaved Back/Cancel.
  Rich bio uses core markup/entity conversion and the existing selection formatting toolbar.
  More details retains birthday (including yearless dates), representative characters, colour
  and archive/unarchive. Archive is disabled while a draft has changes.
- Location (`text`) and Website/Website 2/Website 3 (`url`) are **real typed custom fields**.
  Existing matching live definitions are reused deterministically; new IDs are account-specific
  deterministic UUIDv3 IDs and default private. Unchanged legacy values are not validated or
  written on unrelated saves. Definition privacy and member field-visibility maps are untouched.
  There are no new schema columns, op kinds, capabilities or core/WASM changes (D-090).
- Media selection uses Android's picker, bounded 10 MB copying and EXIF-aware ImageDecoder
  decoding (100 MP source cap, 1600 px decoded edge). Square avatar / 3:1 banner framing uses
  zoom and position sliders. Apply crop stages a PNG and durable account-local copy; Save writes
  existing avatar_blob/banner_blob fields. Cancel crop/remove/unsaved discard are distinct.
  Existing upload queue/private blob reads, export references and restore remain in use.
  Banners now participate in Android's offline catalogue and profile cache restoration.
- Reorder has stable keyed lazy rows, real handle dragging, 180 ms displacement, frame-clock edge
  scrolling, Earlier/Later controls and accessibility actions. Reduced motion disables placement
  movement. Preview is local until Apply; Cancel/Back discard it, cancelled gestures restore the
  pre-drag draft, and recreation retains completed draft moves while cancelling an interrupted
  gesture. Concurrent eligible-list/order changes require reopening instead of stale writes.
- Fractional printable-ASCII positions use existing member.set.sort_key and an ID suffix for
  deterministic ties. A single move among 500 members writes one member, without rebalancing.
  Android/web Members apply manual order; search relevance/name ties and explicit front/recent
  ordering remain independent. Search/archive views disable drag and offer a way to the full list.
- Own member REST reads now include nullable sort_key. Metadata/profile routes remain own-account
  only, including for followers; public author cards and revealed-front metadata are not expanded.
  The existing demo server was **not restarted**. Its old REST responses omit the added sort_key
  property, but its already-supported member/field sync and blob contracts exercise this UI.

## Verification

`full-verify.log`: full `python scripts/verify.py --offline --android` after the F: environment
and WASM rebuild passed (exit 0): release Rust workspace tests, fmt/clippy, fixture/schema/API
checks, web typecheck + **110 passed / 1 skipped**, web build/budgets (**129 KB JS gzip,
300 KB WASM gzip**), Android lint and **185 app + 7 bridge JVM tests**, no failures.
`android-offline-correction.log` separately passed debug APK, release Kotlin compilation, app
JVM tests and lint after the crop viewport/cache correction. Final pre-commit gate and committed
APK metadata are in the external manifest. `final-gate.log` also passed the full release
verification after the final rich-text resolver coverage (**186 app + 7 bridge tests**).
No budget was raised.

New checks cover a 500-member sparse move, repeated/draft moves, deterministic ties/Unicode,
projection persistence, foreign/archived/stale targets and no-op/cancelled drafts. Android/web
share `fixtures/ui_member_order.json`. Profile tests cover minimal concurrent patches, preserving
untouched legacy values, typed-field definitions, URL/date validation and crop geometry. The
bio mention/custom-emoji IDs remain stable when nearby text is edited. The
server test reads/exports/rebuilds profile fields/media/positions and rejects foreign/follower
metadata reads. The blob test confirms a revealed avatar does not grant the member's banner.
Offline catalogue/verified-copy/restore tests include the profile path. Existing drafts, sender,
poll, spoiler, navigation and privacy suites remain required in the gate.

## Native evidence and reproduction

Evidence root: `F:/DunBuild/chorus-ui6-evidence/batch2`, fictional Lanterns demo on
`emulator-5554`. Helper `inspect.py` always refreshes XML before labelled taps.
Images/logs/APKs stay outside the repo. The generated botanical fixture is not an owner photo;
the generation prompt and provenance are in the external manifest. No messages/posts were sent.

- `profile-images-saved.png`, `profile-fields-saved.png`: actual avatar/banner selection, crop
  Apply then profile Save, rich bio, Location and website persisted. `avatar-exif6.png` shows the
  EXIF=6 JPEG rotated before crop. `avatar-crop-real.png` shows the ordinary square crop.
- `avatar-draft.png`, `discard.png`, `media-removed-draft.png`: image Apply changes only the draft;
  removing media then Cancel/Discard preserves saved images. Decode errors are visible; the first
  `avatar-crop.png` deliberately records an incomplete temporary screenshot selected in error,
  not a successful crop. It is not image-quality evidence.
  `banner-removed-saved-150.png` and `banner-restored-150.png` additionally exercise actual
  persisted banner removal/restoration; `banner-crop-150-corrected.png` checks the fixed crop
  viewport at genuine 150% text.
- `order-relaunch.png`: Violet remains first after a real drag, Apply and cold relaunch.
  `reorder-drag-draft.png` / `reorder-cancelled.png` distinguish draft moves from saved order.
- Eight temporary members **Batch2 Garden 01–08** were created through Add/Save on the fictional
  account for a 12-member list (`native-long-list.log`). `drag-edge-held.png` and
  `drag-edge-drop.png` show actual edge scrolling to the later rows while Violet follows the
  held finger. Cancel restores saved order. `drag-motion.mp4` is a partial recording of the
  beginning of that gesture, not a complete recording of the whole edge hold.
- `drop-cancelled-back.png`: Back during a held gesture cancels it, retaining reorder mode;
  the viewport may remain scrolled. Canceling the mode returns to the saved list.
- `reorder-restored-top.png`: Earlier/Later draft move retained across actual process death.
  `profile-draft-restored-final.png`: an unsaved display-name draft retained across actual process
  death, then discarded. `recreation-pids.txt` records changed process IDs. Background `am kill`
  initially did not kill the process; the valid checks backgrounded the app, then used its own
  debug UID to kill its process and reopened the existing task. `profile-draft-recreated.png`
  captured cold-load blank chrome too early and is **not** restoration proof.
- `profile-150.png`, `editor-150.png`, `editor-ime-150.png`, `crop-150-actions.png`,
  `reorder-150-reduced.png`, `reorder-drag-150-reduced.png`, `filtered-search-150.png`,
  `archived-150.png`: real 150% text/reduced-motion override, scroll/IME/actions, actual drag,
  filtered disable/clear and archived empty state. Crop content uses InspectionDialogContent
  across Material's dialog density reset; discard-dialog slots do too. Use process-only extras
  `--ef chorus_test_font_scale 1.5 --ez chorus_test_reduce_motion true`; no global settings changed.

- `offline-root-proof.png`, `offline-crop-actions-final.png`, `offline-profile-saved-final.png`,
  `offline-profile-cold-final.png`, `native-offline-final.log`: real disconnected Save and cold
  reopen of a new crop, with queued upload and a verified durable account-local copy. The
  existing emulator tcp:5265 reverse mapping was temporarily removed, then restored exactly;
  the server was not restarted. `offline-upload-completed.txt` confirms the prepared hash left
  the upload queue after reconnect. A fresh process is essential because removing the mapping does
  not kill already-open connections. Early attempts/`offline-profile-saved.png` are not offline
  success evidence. The loading-anchor defect found in that check was corrected by reserving
  a bounded crop viewport; scroll from the image/label area to reach actions.

No Samsung (absent from ADB), tablet, TalkBack traversal or native 500-member timing claim.
The 500-member result is a JVM persistence/sparse-write check. Full motion timing is not measured.
Demo temporary members/profile edits remain for the independent reviewer. No owner-account edits,
server restart, service/system-setting changes, keep-awake helper, push, merge or deployment.
