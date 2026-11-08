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
8. Later: offline ticks (core), handover + notes, custom emoji, permissions, heading entity,
   tablet three-pane.

Then web, by the same waves, using each audit's *Web* section.

## Owner questions (defaults in use)

Collected from the audits; asked in one batch. See each audit's questions section.
