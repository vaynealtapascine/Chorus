# UI6 implementation baton

Claimed GPT-6.1-sol, 2026-10-29, existing F:/DunBuild/chorus-ui-redesign,
feat/figma-ui-redesign, clean starting c46363c. Owner explicitly authorized all
features below. No delegates, deployment, push, checkout creation or system changes.

Status: slices 1, 2, 8 and 10 passed independent Sol-6.1 source/native review and
small corrections. See `ui6-batch1-review.md` for evidence and validation limits.
The remaining seven slices have no implementation.
D-089 and DESIGN/CLIENTS describe navigation/colour behavior. Do not mark UI6 done.

## Required slices and acceptance matrix

- [x] 1 Read as: replace flat overflow with searchable lazy sheet; 500 members,
  current choice, everyone-present, Back, keyboard and privacy regression checks.
- [x] 2 Custom bottom destinations: Journal replaces Friends by default; Friends in
  More; bounded chosen/reordered tabs, persistent settings, More always available;
  route snapshots, drafts and scroll must survive changes/recreation.
- [ ] 3 Member-to-member containment: independent subsystem front, nested eligibility,
  cycle/removal/archive deterministic core enforcement; retain group compatibility.
  Decide/document direct versus descendant eligibility with core fixtures.
- [ ] 4 Real handles: stable offline default identities, canonical Unicode/duplicate
  migration/backfill, account namespace collisions, server uniqueness/claim resolution,
  explicit connected claim pending/errors, privacy-filtered lookup, mentions/search.
- [ ] 5 Internal per-member write/read/hidden restrictions capped by account permissions;
  same-account D-010/021 soft privacy wording. Core/server sends, segments, edits,
  threads and polls; hidden lists/search/previews/readers; define everyone-present.
- [ ] 6 Channel sides: named groups, searchable membership/avatar stacks, distinct
  Follow front/Locked preference, preview, deterministic cofront/multi-author policy;
  legacy unchanged until configured, authors and read perspective preserved.
- [ ] 7 Rich member profiles: avatar/banner pick/crop/remove, bio/links/location,
  privacy-aware editor/presentation and actual offline persisted media.
- [x] 8 Shared colour picker: swatches/recent/hue/saturation/live preview, optional
  hex, accessible alternatives, no dependency needed for Compose primitives.
- [ ] 9 Drag member ordering: explicit handle/mode, persisted sort_key, convergence,
  accessible moves; filtered/search lists cannot corrupt full ordering.
- [x] 10 New Direct message: own-member and connected-account recipient sections,
  search, explicit member_dm participants, reuse/open, offline/empty/errors;
  preserve account DM flow; existing generic chats are not recipient creation.
- [ ] 11 Personal per-channel wallpaper: image/colour, blur, opacity, tint, presets,
  reset, staged Apply/Cancel and live preview; offline blobs, bounded EXIF decoding,
  API29 fallback, contrast and no per-frame decode. Document personal versus shared.

## Cross-cutting gate and native matrix

Every commit requires scripts/verify.py (quick permitted); full offline Android gate
before final handoff. Use F: build/cache paths and Android Studio JBR, serial Rust
harness with CARGO_BUILD_JOBS=2. WASM budget remains 300 KiB (prior nearly full).
New schema/ops require capabilities, projection/cache invalidation, export/restore/
rebuild blob coverage and old-server explanation. Preserve UI5 route/draft/scroll,
spoiler and concurrent-edit fixes, and independent front-purpose policies.

Native inspection for the first four slices is recorded in `ui6-batch1-review.md`:
keyboard, Back, Apply/Cancel, 150% text and actual DM create/reuse. Native 500-member
timing, tablet and TalkBack were not run; reviewed lazy-list structure is not a timing
claim. Remaining seven slices still need implementation and native checks. Evidence
and APK belong outside repo at F:/DunBuild/chorus-ui6-evidence; phone images stay private
and are never committed. Do not infer visual success from tests.

Never mutate owner's account. Emulator fictional demo only for mutations; earlier
approval rejection of updated demo-server restart remains in force, do not bypass.
Keep-awake helper stays stopped unless actively inspecting physical device, and
must stop before handback. No service or global device settings changes.

## Design rationale and supplied research

Preserve D-079 charcoal/teal/Hanken and compact Telegram-like density. Applied skill
reading: impeccable and emil-design-eng; impeccable context loaded Android guidance.
Use interruptible transitions and reduced motion; no empty actions. Existing UI5
native evidence is baseline, not UI6 validation. Parent supplied these primary
references; this claim has not independently inspected their images:

- https://telegram.org/blog/backgrounds-2-0 — wallpaper controls.
- https://telegram.org/blog/shareable-folders-custom-wallpapers — per-chat entry.
- https://core.telegram.org/api/wallpapers — blur/pattern implementation reference.
- https://support.discord.com/hc/en-us/articles/214836687-Discord-Roles-and-Permissions
  — separate view/send permissions.
- https://help.x.com/en/managing-your-account/how-to-customize-your-profile
  — avatar/header/bio/location/site editing.

Channel sides is a Chorus adaptation, not an asserted Telegram feature. Mobbin
public fetch required login; no Mobbin screenshot inspection is claimed.

## Resume

Continue slices 9/7 (ordering/profiles), then the remaining authorized features;
do not reimplement or widen the accepted four slices. Source/native review, four
resolved findings and exact checks are in `ui6-batch1-review.md`. Final artifact and
commit metadata live outside the repo in `batch1-manifest.json`. Required final
quick/offline Android verification passed (`batch1-review-verify.log`, exit 0;
175 app + 7 bridge JVM tests). UI6 stays
in progress; no push, merge or deployment is authorized by this checkpoint.

## Earlier implementation checkpoint (retained for provenance)

Working-tree checkpoint (2026-10-29, parent coordination): Sol implemented a shared searchable
Read as sheet; configurable device/account-local navigation and staged preview editor;
staged visual colour picker integrated with members/channels; and searchable internal/group
DM creation/reuse alongside connected-account DMs. New helpers/tests are currently untracked;
retain all files shown by git status. No new server/schema changes in this batch.

Evidence: F:/DunBuild/chorus-ui6-evidence. `batch1-full-verify.log` passed the full release
Rust/web/Android gate; after the debug dialog inspection helper changed,
`batch1-commit-verify.log` passed quick verification including Android. `batch1-dialog-checks.log`
passed APK build, release Kotlin compilation, lint and JVM tests (172 app + 7 bridge).
Current debug APK is F:/DunBuild/chorus-gradle/app/outputs/apk/debug/app-debug.apk (0.1.563),
installed on emulator-5554 over existing fictional demo data. No production mutations.

Parent and implementing Sol inspected baseline navigation/colour/reader/DM captures. Native
checks confirmed navigation Cancel/Apply and persistence after relaunch, colour Cancel versus
draft-only Apply, reader search with IME, existing Amarynthe/Foxglove DM reuse, and creation of
Amarynthe/Nairon/Violet group DM with correct sender. No messages were sent. Samsung absent
from ADB; keep-awake helper remains stopped. Actual large-text modal checks continue: initial
`navigation-large.png` used an ineffective dialog density override and is NOT large-text proof.
Debug-only InspectionDialogContent now reapplies the test scale inside sheets; release is a no-op.
`navigation-150.png` is genuine 150% (preview Members wraps midword; inspect/fix this minor issue).
Capture colour/reader/DM at 150%, inspect hex/IME, finish independent review, update unchecked
slices only when accepted, run any checks needed after fixes, and commit a clean implementation.
Then continue remaining slices, beginning member ordering/profile customization.
