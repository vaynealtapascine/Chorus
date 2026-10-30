# UI7 — Android polish toward Telegram-level chat and Twitter-class journals

Owner request, 2026-10-30 (claude-opus-5.5 coordinating; Sonnet 5.5 implements the simpler
slices). Goal, in the owner's words: Chorus should be *more pleasant and convenient to use than
talking to ourselves in Telegram chats with other people*. Android first. Friendly animation and
transitions for actions. Implement Figma features (Chorus — Flows Copy, `389A1Z2QPTRpZw6d79Cz7k`,
page `3:5`) that are not built yet. Account for subsystems (groups that front as a unit, and later
members containing members) everywhere members are chosen or shown.

Each slice must install over the owner's phone without losing data: commit, run the gate, then
`scripts/deploy.ps1 -Android` from the main checkout after fast-forwarding `main` to
`feat/figma-ui-redesign` (worktree `F:/DunBuild/chorus-ui-redesign`). Phones pick the APK up on
the next app start. The owner's phone is logged in to the real account: inspect it read-only
(no sends, edits or opening unread chats); do mutations on the emulator's fictional demo
(`docs/OPS.md`, demo server on 5265).

## Settled for UI7 (no owner question needed)

- Motion uses `rememberChorusMotion()` and the `ChorusMotion` tokens in `ui/ChorusMotion.kt`:
  springs for gestures and pops, 140/220/320 ms tweens on the shared decelerating ease, nothing
  animates when the system animator scale is off.
- **Bookmarks (Journal "save")** are private per account: one `pref.set` per post,
  key `bookmark:<post id>`, value `true` or null (D-063 style), so they sync to the account's own
  devices only and never reach the post's author. No schema change.
- **Reposts** use the existing `post.create` `repost_of` field (projected to `post.repost_of_id`,
  hidden by the server when the reader can't read the original). **Quote posts** link the quoted
  post in `quote` as `{post_id}`. Undoing a repost deletes (tombstones) the repost post.
- **Share** hands plain post text to the Android share sheet; nothing leaves the app otherwise.
- Chat composer drafts persist per account across restarts (see CLIENTS.md); the chat list shows
  them as "Draft:" like other messengers.
- System/unsigned authorship (Figma F5.3 "The Lanterns"/"Unsigned") stays out: D-003 makes every
  message authored by members. Voice messages stay L1 (later).

## Slices

| # | Slice | Who | State |
| --- | --- | --- | --- |
| 0 | UI6 batch 2 wrap-up (Sol's R1 follow-up + chat-draft cache fix); launcher/notification icon from the owner's wordmark mark (Figma 72:1330); dark splash | Claude | done, deployed |
| 1 | Chat feel: swipe to reply, double-tap to react, message enter/exit animation, reaction pop, send-button morph, animated jump-to-latest with unread badge, haptics, chat list sorted by activity with Draft:/typing previews | Sonnet (xhigh) | |
| 2 | Journal feed (F6.3/F6.4): Twitter-class post cards — action row (reply/repost/like/bookmark/share with counts and animations), reposted-by header, quote card, media grid; post detail with stats and a sticky reply bar; Saved | Sonnet (xhigh) | done, pending review (+ change a post's audience) |
| 3 | Journal composer (F6.2/F6.11): full-screen, Post / Journal entry, posting-as (subsystem-grouped), audience + replies chips, media row, selection formatting, word count, drafts | Sonnet (high) | |
| 4 | Member profile (F6.1): banner + overlapping avatar, Message / Edit profile, status chip, stats, tabs Posts / Replies / Journal / Media / About, subsystem chips | Sonnet (high) | |
| 5 | Mentions autocomplete in chat and posts: members, `@front`, subsystems; keyboard-friendly | Sonnet (high) | |
| 6 | Photos (F5.8): attach sheet with recent-photo grid and multi-select, album bubbles, full-screen viewer (pinch, swipe between, swipe down to close) | Sonnet (xhigh) | |
| 7 | Chat list and info (F5.1/F5.25/F5.26): folder editor, compact rows, swipe actions, pinned chats, info quick actions (Mute/Search/Pinned) | Sonnet (high) | |
| 8 | Within our system (F5.15/F5.19): handover "since X last fronted" divider, notes tag chips | Sonnet (high) | |
| 9 | Subsystems everywhere: send-as/posting-as/switcher grouping by subsystem tree; then UI6 slice 3 (members containing members) | Claude + Sonnet | |
| 10 | Per-chat wallpaper (UI6 slice 11) | Sonnet (high) | |
| 11 | Widget palette to the charcoal/teal system; in-app text size (owner: last priority) | Sonnet (high) | |

UI6 slices 4 (handles), 5 (per-member restrictions) and 6 (channel sides) remain on the UI6
board; handles would make the Figma `@member.system` lines real.

## Rules for implementers

Read `AGENTS.md`, this file, `docs/UI_REDESIGN.md` and the Android parts of `docs/DESIGN.md`.
Match the surrounding Kotlin (compact, existing helpers such as `LocalChorusPalette`, `ChatIcon`,
`ChatAvatar`). No new dependencies (Gradle is offline). Keep `chorus-core` pure and unchanged
unless the slice says otherwise. Atomic commits with tests for logic (`data/` helpers get JVM
tests). Do not touch devices, deploy, push or merge; the coordinator inspects and ships.
Gate: Android lint + JVM tests (`lintDebug testDebugUnitTest --offline`) with
`CARGO_TARGET_DIR=F:\DunBuild\chorus-target` (prebuilt host core library; don't run cargo) and
your own `CHORUS_GRADLE_BUILD_DIR`.
