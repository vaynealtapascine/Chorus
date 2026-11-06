# UI6 — Android feature pass (remaining slices)

Owner-authorised (2026-10-29). Slices 1 (searchable "Read as" sheet), 2 (custom bottom
destinations), 7 (rich member profiles, crop, location/website fields), 8 (shared colour picker),
9 (drag member ordering, D-090/D-091) and 10 (new direct message) are done and live. The review
history of slices 7/9 (rich-text provenance, durable drafts) is in git:
`git show a9c3c2e:docs/handoff/ui6-batch2-corrections-review.md` and siblings; the resulting
traps are in `docs/NOTES.md` §Android.

## Open slices

- [ ] **3 Member-to-member containment:** an independent subsystem front, nested eligibility,
  deterministic cycle/removal/archive enforcement in core, group compatibility kept. Decide and
  document direct versus descendant eligibility with core fixtures. D-092/D-102/D-110 already
  cover display, bubble sides and members' chats; UI7 slice 9 builds on this.
- [ ] **4 Real handles:** stable offline default identities, canonical Unicode/duplicate
  migration and backfill, account namespace collisions, server uniqueness/claim resolution,
  connected claim pending/errors, privacy-filtered lookup, mentions/search. Also the missing
  "set or change a handle after joining" (today only a manual server edit can do it).
- [ ] **5 Internal per-member restrictions:** write/read/hidden per member, capped by account
  permissions; same-account soft-privacy wording (D-010/D-021). Core/server sends, segments,
  edits, threads and polls; hidden in lists/search/previews/readers; define "everyone present".
- [ ] **6 Channel sides:** named groups, searchable membership with avatar stacks, distinct
  Follow front / Locked preference, preview, a deterministic co-front/multi-author policy; legacy
  behaviour unchanged until configured; authors and read perspective preserved.
- [ ] **11 Personal per-channel wallpaper** (= UI7 slice 10): image/colour, blur, opacity, tint,
  presets, reset, staged Apply/Cancel with live preview; offline blobs, bounded EXIF decoding,
  API 29 fallback, contrast, no per-frame decode. Document personal versus shared.

## Rules

- New schema or ops need capabilities, projection/cache invalidation, export/restore/rebuild
  coverage and an old-server explanation. Preserve route/draft/scroll restoration, spoiler
  safety, concurrent-edit handling and independent front-purpose policies.
- Native checks (keyboard, Back, Apply/Cancel, 150 % text) on the emulator's fictional demo; the
  owner's phone is read-only. Evidence stays outside the repo (`F:/DunBuild/chorus-ui6-evidence`).
- Design references: Telegram backgrounds 2.0 / custom wallpapers
  (telegram.org/blog/backgrounds-2-0, core.telegram.org/api/wallpapers), Discord role
  permissions (view vs send), X profile editing. Channel sides is a Chorus adaptation.
