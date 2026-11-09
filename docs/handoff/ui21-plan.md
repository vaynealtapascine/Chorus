# UI21 — Figma parity pass: plan and rules

Owner request 2026-11-08: get Android, then web, feature- and visually complete with the Flows
frames (F0–F11). Screens without a frame take the same styling. No explanatory text where the
UI explains itself; cut taps. Questions are batched for the owner at the end; until answered,
use the defaults in the audit docs.

Reference images and node trees: `F:/DunBuild/figma-cache` (README there). Audits:
`ui21-home-settings.md`, `ui21-members-journal.md`, `ui21-chat.md`,
`ui21-fronting-friends-stage-notifs.md` (this folder).

## Rules for each slice

- Read the audit rows for the slice and look at the frame PNG + XML before coding.
- Reuse the shared pieces: `ui/Drawers.kt` (D-124 drawers, form cards, value rows), existing
  chips, faces and top bars. Add a shared component instead of a one-off when two screens need it.
- No new explanatory copy. Subtitles only where they carry data (a count, a time, a name).
- `python scripts/verify.py --changed` before each commit (it includes the Android stage when
  Android changed). Commit per slice, author Vayne Altapascine with the Claude co-author line.
- Behaviour that the spec doesn't cover: take the audit's default and note it under *Owner
  questions* below; don't block.

## Android waves (in order; deploy after each wave)

1. **Sweep**: forward privacy fix (F5.20 (d)); every *Text to trim* row in the four audits;
   the small *Taps to cut* rows; connection banner + rate-limit toast (F2.5, F10.3, F10.7).
2. **Home** (F0.1–F0.6): new Home destination; app opens there.
3. **Members** (F4.1–F4.5): directory + Groups tab (groups can be filled), edit member, who can
   see, archive.
4. **Chat** slices 1–7 of `ui21-chat.md`.
5. **More/Settings/Data**: F10.1, F10.2, F9.5, F9.1, F9.6, F10.4, F10.5, onboarding F1.x.
6. **Journal**: composer/formatting, polls + system board, reading polish, feeds, lists.
7. **Fronting/Friends/Stage/Notifications** leftovers from that audit.
8. **New features the owner approved (2026-11-08)**, each end to end (core/server/both clients):
   ticks + opt-in friends' reads (D-131), headings H1/H2 (D-134), poll closing time + Nudge
   (D-136), system board (D-135), member states shareable (D-138), lock-screen front
   notification (D-137), handover + notes to ourselves full version (D-132), the full F5.11
   shared-space permissions (D-133). Then custom emoji on Android, tablet three-pane.

Then web, by the same waves, using each audit's *Web* section.

**Order changed (owner, 2026-11-09):** after Android wave 7 ships, go to web parity (waves 1–7 on web,
`ui21-web-plan.md`); Android wave 8 (the new approved features) comes after web parity.

## Owner questions (defaults in use)

Asked and answered 2026-11-08: D-130–D-138 (and Q24). The CHORUS wordmark is outlined from the
owner's logo file (`res/drawable/chorus_wordmark.xml`, the mark in place of the O as F1.1 draws
it); no serif for member names exists in that file, so they keep the app's sans.
F1.2 words (default in use): one word per row (the Figma shows two ticks per row; the app needs one
word to show), stored as `system.terminology.system` / `.front` (DATA_MODEL §4.3 gains `system`). Only
setup's titles, Home's "Fronting now", More's Fronting row and System settings read them so far;
the rest of the app still says system/fronting. Everything else in the audits' question sections: use the audit's default.
