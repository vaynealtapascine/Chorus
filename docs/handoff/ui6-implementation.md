# UI6 implementation baton

Claimed GPT-6.1-sol, 2026-10-29, existing F:/DunBuild/chorus-ui-redesign,
feat/figma-ui-redesign, clean starting c46363c. Owner explicitly authorized all
features below. No delegates, deployment, push, checkout creation or system changes.

Status: scope recorded only. ALL implementation and native inspection remain pending.
No new product decisions have been implemented; add D entries alongside each slice
and update owning specs before changing their behavior. Do not mark UI6 done.

## Required slices and acceptance matrix

- [ ] 1 Read as: replace flat overflow with searchable lazy sheet; 500 members,
  current choice, everyone-present, Back, keyboard and privacy regression checks.
- [ ] 2 Custom bottom destinations: Journal replaces Friends by default; Friends in
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
- [ ] 8 Shared colour picker: swatches/recent/hue/saturation/live preview, optional
  hex, accessible alternatives, no dependency needed for Compose primitives.
- [ ] 9 Drag member ordering: explicit handle/mode, persisted sort_key, convergence,
  accessible moves; filtered/search lists cannot corrupt full ordering.
- [ ] 10 New Direct message: own-member and connected-account recipient sections,
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

Actual native inspection pending for every slice: keyboard, rapid Back, drafts,
Apply/Cancel, 150% text, reduced motion, 500 members, permission/read perspective,
sides and all editor flows. Evidence belongs outside repo at
F:/DunBuild/chorus-ui6-evidence; phone images private and never committed. No APK
or fresh screenshots produced yet. Do not infer visual success from tests.

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

Only PROGRESS.md and this brief changed. No code, schema or product decision changes.
Next: inspect Chat.kt Read as menu and current reader filtering, read craft-floor
before UI edits, implement slice 1 with native inspection, then the remaining ten.
