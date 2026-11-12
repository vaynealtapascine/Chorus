# Chorus — progress & handoff board

This file is the baton. Two agents (Claude Opus 5.5 and GPT-6-sol) take turns on this repo, and
either may run out of usage mid-task. **Read [AGENTS.md](AGENTS.md) first**, then
[docs/NOTES.md](docs/NOTES.md) (environment and traps) before building anything.

Rules (short form):

1. Before starting a task, set **Now** to that task (id, who, when, next concrete step).
2. After each atomic accomplishment: tick it on the board, add a line to the log, commit.
3. If you stop mid-task, leave **Now** pointing at it with enough notes that a cold agent can finish it.
4. Only one task is "in progress" at a time unless the notes say why.

`scripts/board.py` rewrites only the four bullets under *Now* and appends to the end of the log;
keep standing instructions in the sections below them, not on those lines.

History: this file and `docs/NOTES.md` were compacted on 2026-11-05. The full log (2026-09-23 →
2026-11-04), the old notes and the finished batch handoffs are in git:
`git show a9c3c2e:PROGRESS.md`, `git show a9c3c2e:docs/NOTES.md`, `git show a9c3c2e:docs/handoff/<file>`.

---

## Now

- **In progress:** UI21
- **Owner:** gemini-3.8-flash-high, 2026-11-10
- **Next concrete step:** Wave 7 deployed (b4bc942, Android 0.1.883). Next: web parity (ui21-web-plan.md).
- **Notes:** Waves 1-7 live (Android 0.1.883, b4bc942: Stage tabs & post staging, member states & note, custom notification ring kinds, drawers, launcher & unread widget). Plan: docs/handoff/ui21-plan.md; design for approved features: docs/handoff/ui21-design.md.

### SEC1: what is done and what is left

Done (D-119, `tests/privacy_audit.rs` a01–a18 in the gate): ingest refuses an op on an entity
made in another scope, `account.set`/`system.set` for another account, payload ids of another
account (field values, highlights, group members, attachments), and blob hashes the author neither
uploaded, holds nor can read (`blob_pending`, held). Re-uploads of another account's blob prove the
bytes (`blob_holder`, migration 0020; ends the dedup oracle). Exports pack only readable files.
Also from Gemini's review: rate limiter bypass, Chorus Home DNS rebinding, webhook DNS rebinding
and link-local targets, per-request auth writes, restore window reopening, post alert faces,
named-front avatars, search channel scoping, helper consolidation, three Android a11y fixes.

Deployed 2026-11-06; the live-DB audit found nothing. Left: Q23 (poll votes of deleted members); the
*Open* rows of the triage file (device checks: nav-bar inset, predictive back).

### State (checked 2026-11-06)

- **Live** on `ns2-up-net-nz` (https://chorus.vayne.garden): server + web from `b141e58`
  (D-119 security fix), Android **0.1.728** (deployed 2026-11-06). Push main to GitHub after
  each commit (M12.2; check `git status -sb`).
- **Checkouts:** the main checkout (clean) and `F:/DunBuild/chorus-buckets` (branch `ui13`, at
  a9c3c2e, plus the untracked audit file above). Work happens on `main`; use a worktree only when
  something must stay out of a deploy.
- **Never checked on a device or in a real browser:** UI11c bucket editor and audience menus,
  UI13 (friends' pictures/profiles, thread action rows), D-117 default audience. The owner's
  phone holds their real account: look, don't post.
- **Disk:** C: is full (~26 MB free on 2026-11-05) and F: has ~7 GB. `deploy-linux.ps1 -Android`
  stages the 43 MB APK on C: (`~/selfhost/chorus/app/android`), so free space (or ask the owner)
  before an Android deploy. See NOTES §Disks.

### Owner's standing instructions

- Ship in slices: commit → full gate (`verify.py --android`) → `pwsh scripts/deploy-linux.ps1
  [-Android] [-NoBuild]` → log line with the Android version. Phones update on next app start.
- No release (`v*` tag) until the owner says so. Spec changes that alter user-visible behaviour
  go through `docs/OPEN_QUESTIONS.md` (open now: Q21, Q22, Q23, Q24).
- Production checks use the test account `claudetest` (its device is the owner's Chrome
  profile), never the owner's account. Mutations go to the emulator or an isolated demo server
  (`docs/OPS.md` §8 "Demo data"; `F:/DunBuild/ui8-demo` is a seeded two-account demo on 5283).
- Parked by the owner: **D-096 step 3, tighten ntfy** (Settings → Notifications "how this phone
  gets them", ntfy vs Google choice, ntfy.sh/login/refused endpoints, guide for Google-free
  friends); PluralSpec implementation M14.5–M14.9; Chorus Home M13.5.

### Branches holding unmerged work (kept for reference; everything else is merged or superseded)

| Branch | What |
| --- | --- |
| `wip/privacy-audit` | the audit test, now on main (SEC1); can be deleted |
| `wip/push-transport-choice` | Android "Notifications arrive by" choice (unfinished; its D-098 number is taken — renumber) |
| `wip/ui7-slice3` | UI7 slice 3 Journal composer, partial |
| `wip/ui7-slice1` | UI7 slice 1 chat feel, mostly redone in UI8 slice 6 |
| `integrate/local-search-on-redesign` + `stash@{0}` | local-search WIP (D-078), deferred by the owner |
| `feat/links`, `t3/journal-singlet-search-privacy` | Sol's uncommitted UI11a/UI11c work as `wip:` commits, superseded by main |

---

## Board

Legend: `[x]` done · `[~]` in progress · `[ ]` to do · `[-]` dropped (say why in log)

Done and collapsed (details in git history): S1–S12 spec; A1 audit; M0.1–M0.5; M1.1–M1.10;
M2.1–M2.6, M2.8; M3.1–M3.5; M4.1–M4.2; M5.1–M5.7, M5.9–M5.13; M6.1–M6.3; M7.2d, M7.4, M7.5;
M8.3; M9.1–M9.4; M10.1–M10.3; M11.1; M12.1; M13.1–M13.4, M13.6; M14.1–M14.3, M14.11; L6; UI1,
UI4, UI5, UI8, UI9, UI10, UI11a–c, UI12a–d, UI13.

### Security

- [x] SEC1 Cross-account writes and reads found by the privacy audit and Gemini's review (`docs/handoff/gemini-review-triage.md`).

### CI

- [x] CI1 GitHub CI green again (owner, 2026-11-07). The web job failed on a timezone-dependent Insights test since 2026-10-30 (fixed). The e2e job fails since D-093 (2026-10-31): enrol is fixed, but the suite still walks the September screens (Add member on Home, `form.add`, `button.member`, "Change who's here"); 9 tests fail at their first member step and 20 behind them haven't run. Rewrite each spec's walkthrough against the current UI (UI8–UI17), run locally with `CHORUS_E2E_CHANNEL=chrome CARGO_TARGET_DIR='F:\DunBuild\chorus-target' bash scripts/e2e-web.sh --no-build` (36 min when everything times out; pass `-- e2e/<spec>.ts` for one).

### UI — Owner's Figma redesign

- [x] UI23 Hold to act as several members (owner, 2026-11-09, D-142): hold a heart, a message reaction or a poll option for a drawer/sheet of our members (Everyone · Fronting · Nobody, Save) on Android and web; each member's part is its own ordinary op. Branch `t3/multi-member-action-drawer`; web checked on an isolated demo, Android compiled and unit-tested but not seen on a device.
- [x] UI22 Lock Members list banner backgrounds to two-thirds of each row panel, anchored bottom-right (owner, 2026-11-08), on web and Android.
- [~] UI21 Figma parity pass, Android then web (owner, 2026-11-08): every current Flows frame (F0–F11) checked against both apps; missing features and looks built; screens without a frame get the same styling; trim explanatory text, cut taps. Reference images: `F:/DunBuild/figma-cache` (no Figma connector needed). Audits in `docs/handoff/ui21-*.md`.
- [~] UI20 Friends as the Figma F7 screens (owner, 2026-11-07, D-128): Friends list with Around right now (F7.1), a friend's page for a system or a person (F7.2, F7.3, F7.5), a person's Following / Followers / Requests (F7.4), Requests with an accept drawer (F7.7), Sharing with buckets (F7.8), sharing with one friend (F7.9, new `GET /follows/{id}/view`), a bucket's page (F7.10), notifications from one friend (F7.12). Android, server and web deployed 2026-11-08 (b212f83, web `index-DK_ZDRUV.js`, Android 0.1.808); checked on the emulator and in Chrome (1360 and 400 px) against an isolated demo (`F:/DunBuild/ui20-demo`, server 5299, vite 5300, `shot.cjs`). Left: a look on the owner's phone (inspect only); Q24 (hidden-member count); F7.6/F7.13/F7.14 not built (D-128).
- [x] UI19 Notifications screen and Journal replies (owner, 2026-11-07, D-127): the Figma F11 Notifications inbox (web + Android, a bottom-bar tab with a badge; Needs you / Today / Earlier; chips; hold for options; read as another member; web side panel); Journal › Friends shows replies to our posts and what friends' replies answer; own posts count friends' replies and hearts; one action row everywhere with unavailable actions disabled; quoting another account's post, the quote readable only by who can read both. Server + Android deployed 2026-11-07 (8222e4f, Android 0.1.795); checked on the emulator against an isolated demo (`F:/DunBuild/ui19-demo`, port 5297). Web deployed too (0d436de), checked at 1360 and 400 px against the demo. Left: a look on the owner's phone (inspect only).
- [~] UI18 Member handles and private full names (owner, 2026-11-07, D-126): default `@member.subsystem.system` from core, claimable handles unique with account handles, shown in profiles, Journal and chat (web + Android); full names private unless shared, display names follow the profile. Merged (4268dee) and deployed (Android 0.1.791). Checked on web against an isolated demo copy (`F:/DunBuild/handles-demo`). Left: an Android emulator look (member editor Handles section, profile line, Journal rows); Android shared-chat mentions of another account's members by handle (web has them).
- [x] UI17 Owner's fix-and-polish batch (2026-11-07): Android chat icon differs between the list and the chat header; web sidebar squished on wide screens; web chat repaints and icons flashing; web swipe-to-reply cancelling on a hard/long swipe (iOS); Android form controls too large (compact); web Journal width jumping between sections; more transitions on web and Android.
- [~] UI16 Android drawers and forms (owner, 2026-11-07, D-124; deployed cd87097, Android 0.1.751): every dialog, dropdown and date/time picker is a drawer (`ui/Drawers.kt`; layered when opened from a drawer); forms and displays in the Figma style (filled fields, grouped cards, value rows, notes, wide buttons); singlets see no system words. Checked on the emulator (demo): front menu, switch sheet → date/time drawer (layered), chat row menu, delete question, message actions, message edit, new channel → space choice (layered). Left: a singlet account on a device (DM picker straight to friends); a pass over the remaining older sheets with their own headers (Switcher, Journal authors, PK import, subsystem sheet, chat attach/forward/reader sheets) to use `Drawer`.
- [~] UI15 Fronting as the Figma F3 screens (owner, 2026-11-06, D-122; deployed 9911350, Android 0.1.744): Fronting, switch sheet, member sheet + state, Fronts, Layers & display, Front history with lanes/sentences/reviews, Log a past switch (web + Android), Android front widget. Reviewed by a separate Opus agent against the Figma; its high/medium findings fixed (b6911fa, 28fca66). Checked on the emulator and the web demo at 400 px. Left: device check of the front widget on a home screen; F3.8's unread widget and lock-screen chip (not possible on phones); F3.3's rename/+ on separate layers; Fronts card blurb without the "since" time; web drag-to-reorder doesn't work on touch (arrange mode does).
- [x] UI14 Chat list, menus and profiles (owner, 2026-11-06, D-120): one Telegram-style list on every width with shared spaces as forum entries (web + Android); web channel names as written; web right-click / hold menus on messages and rows; faces in chat open the profile; one Twitter-like profile for our members and friends' (web + Android). Deployed 31f8b14 (server, web, Android 0.1.737); not yet seen on a phone (web checked at 1280 and 400 px on the demo).
- [~] UI2 Independent named fronts and layers (D-080, `docs/FRONTS.md`). Code complete and live. Left: a native/live visual check; Home shows no review cards for configured accounts (they are on History); follower revealed-front reads stay Body-only by design (D-084).
- [~] UI3 Polls (D-081/D-086, `docs/POLLS.md`). Complete; web live suite passes. Left: an Android device check.
- [~] UI6 Android feature pass: slices 1/2/7/8/9/10 done; 3 (members containing members), 4 (real handles), 5 (per-member restrictions), 6 (channel sides), 11 (per-chat wallpaper) open. `docs/handoff/ui6-implementation.md`.
- [~] UI7 Android polish (Telegram-level chat, Twitter-class Journal). Open slices in `docs/handoff/ui7-android-polish.md`.
- Known gaps and small follow-ups: web chat timeline still reads legacy Body history and group subject naming differs web/Android (from UI8); "preview as a friend" on one's own profile and formatted friend bios on Android (UI10); Android member editor edits only Location/websites among custom fields (UI9); drag to reorder chat folders on web (UI12); no way to set or change a handle after joining (UI6 slice 4); `blobs.rs` follower avatar rule reads only Body's view (`fv.front_id = ''`), so a member revealed only through a granted named front (D-084) can't have their picture fetched.

### M — v1 milestones still open

- [~] M2.7 Read API (REST) + follower views — most reads done (API.md is checked by `api-check.py`); re-check what's missing against API.md before ticking.
- [~] M4.3 Onboarding via invite link / QR — QR on web; in-app camera scanning not needed while the phone camera opens the link.
- [~] M4.4 Members, groups, switcher, front history on Android — implemented; device audit pending.
- [~] M4.5 Quick-switch widget (RemoteViews, D-058) — built + unit-tested; needs placing on a home screen for a device check.
- [~] M4.6 Search launcher + app shortcuts — basic launcher device-checked 2026-09-24; newer actions await a device audit.
- [~] M5.8 Search — server FTS, web + Android local search, scoped search (D-114) live; device audit pending.
- [~] M7.1 Profile page — done on both clients through UI7/UI10/UI13; tick after a device check.
- [~] M7.2 Journal web/Android parity — slices 1–6 deployed. Left (web): a "new posts" pill when the timeline grows while scrolled; Android's "posting as"/offline subtitle under the Journal title; check whether UI13 (a144b61) closed "another account's focused post lacks the large action row". Both clients: friends' posts aren't kept offline (server reads).
- [~] M7.3 Highlights, relationships + relationship types — web done; Android parity unverified.
- [~] M8.1 Push plumbing — FCM is the default and live (D-096); UnifiedPush/ntfy works when picked; D-096 step 3 parked (above).
- [~] M8.2 Rule resolution — core + server scheduler done; delivery via M8.1.
- [~] M8.4 Chat notifications + inline reply — done; inline reply device-checked with a debug push; real-push device check pending.
- [~] M12.2 Disclosure card, screenshots, README, publish — published at github.com/vaynealtapascine/Chorus; push main after each commit.
- [~] M12.3 In-app APK updates + silent OTA (D-060) — working on the owner's phone; first update after an `adb install` needs "Install unknown apps" (NOTES §Android).
- [ ] M13.5 Chorus Home Android: pinned self-signed certificate from `#pin=` invites; mDNS rediscovery (paused until the owner's designs).
- [~] M14.4 PluralSpec owner decisions — D10 (enums vs vocabularies) and D13 (chat scope) still proposed (`spec/DESIGN_NOTES.md`).
- [-] M14.5–M14.10 PluralSpec crate, fixtures, validator, converters, Chorus import/export, PluralPort proposals — paused by the owner until the spec is pinned down (`docs/handoff/opus-remote-5.md`).

### Later (not v1)

- [ ] L1 Voice messages, video messages
- [ ] L2 Link embeds / previews
- [ ] L3 Render stage to PNG in-app
- [ ] L4 Quick Settings tile, Wear OS
- [ ] L5 Sealed (client-encrypted) member-private entries

---

## Log

Newest last. Format: `YYYY-MM-DD agent — what happened (commit)`. Entries before 2026-11-03 are in
`git show a9c3c2e:PROGRESS.md`.

- 2026-11-03 claude-opus-5.5 — Fixed the 0.1.706 regression where every chat opened as "Private chat": core projection v5 stamps a space's creator; clients read membership from the `space_member` set; projection-shaped fixtures (NOTES §Fixtures). Deployed 0.1.709.
- 2026-11-03 claude-opus-5.5 — UI12b archived chats (D-111), UI12c reply jump trail (D-112), UI12d custom chat folders (D-113), Android + web. Deployed 0.1.713.
- 2026-11-03 claude-opus-5.5 — UI11c privacy buckets (D-115): reveals re-planned on policy changes, bucket icon (migration 0019) and colour, one Advanced ceiling editor. Deployed 0.1.715. `docs/handoff/ui11c-privacy-buckets.md`.
- 2026-11-03/04 claude-opus-5.5 — UI13: friends' pictures and profile taps, media/tags on opened posts, DM faces, friends' member rows; posts open from a member's profile; every post in a thread has its action row; web posts open on click.
- 2026-11-04 claude-opus-5.5 — D-116 web JS budget 300 KB; D-117 default audience for new Journal posts. Deployed fa4c6c0, Android 0.1.722.
- 2026-11-04 claude-opus-5.5 — Removed five unused worktrees (their WIP saved as `wip:` commits); D-118 faster gate (`gate` profile, `verify.py --changed`).
- 2026-11-05 claude-opus-5.5 — Compacted PROGRESS.md, docs/NOTES.md and docs/handoff/ (finished batch handoffs deleted; recoverable from a9c3c2e). Preserved the untracked privacy audit on `wip/privacy-audit` and made it the next task.
- 2026-11-06 claude-opus-5.5 — SEC1: ownership at ingest (D-119; privacy audit a01–a18 pass), blob possession (`blob_holder`), and the merited Gemini review findings (rate limit, Home rebinding, webhook DNS guard, auth writes, restore window, helper consolidation, three Android a11y fixes); verdicts in `docs/handoff/gemini-review-triage.md`, Q23 opened (285362d, 0778d34).
- 2026-11-06 claude-opus-5.5 — SEC1 Deployed b141e58 (server, web, Android 0.1.728) to ns2. scripts/privacy-audit.sql on the live DB (2571 ops, read-only): no op of the kinds D-119 now refuses, so the holes were not used.
- 2026-11-06 claude-opus-5.5 — UI14 (D-120), coordinated with the parallel SEC1 thread (it holds D-119/Q23): chat list with spaces as forums on web and Android, compact web uses the list instead of the spaces strip, web channel names as written, web right-click/hold menus, chat faces open profiles, one profile header everywhere. Branch `t3/chat-list-menus-profiles`, not deployed.
- 2026-11-06 claude-opus-5.5 — UI14 merged to main (rebased on SEC1, 31f8b14), full gate with Android passed, deployed to ns2: server and web `index-CzPlONX3.js`, Android 0.1.737. No migration.
- 2026-11-06 claude-opus-5.5 — UI15 (D-122): Fronting, switch sheet, member sheet and state, Fronts, Layers & display, Front history, Log a past switch on web and Android, Android front widget, all from Figma F3 (9c48ae0, 95ec6bb); a fresh Opus reviewer found two highs (web times blank after load, Android Back from History) and mediums (primary kept on moving out, unguarded Merge, templates), fixed in b6911fa and 28fca66. Full gate with Android passed.
- 2026-11-06 claude-opus-5.5 — UI15 deployed 9911350 (server, web `index-4oXZzXWJ.js`, Android 0.1.744) after a first attempt failed at the release dexer (NOTES: no labelled return out of `key {}`); coordinated with the SEC thread (ea9c0f16), which deploys next. Not yet seen on the owner's phone.
- 2026-11-06 claude-opus-5.5 — SEC (D-123): reviewed gemini-4-argon-medium's second privacy audit (uncommitted in worktree t3-d4a47c55) and kept it: payloads can't write managed columns and edits can't move a message or change its visibility (core), asides/private posts refuse foreign reactions, forwards, threads, reposts, quotes and highlights, relationship types stay in their account, the restore window keeps the ownership checks (amends D-067), imports refuse foreign ops; privacy_audit a19–a23. ingest_fuzz then found an older bug (empty `relationship.set` twice → internal error), fixed with a24. Full gate with Android passed on the tree combined with UI15; deployed 5c677d8 after the F3 thread's deploy (coordinated): server, web `index-4oXZzXWJ.js` + new core wasm, Android 0.1.747. No migration.
- 2026-11-07 claude-opus-5.5 — UI16 (D-124): every Android dialog, dropdown and date/time picker is a drawer (ui/Drawers.kt, layered when opened from a drawer); forms in the Figma F3.5/F6.7 style (ChorusField replaces OutlinedTextField; cards, value rows, notes, wide buttons); singlet wording in the DM picker, chat notes, delete and profile fields. Emulator check found and fixed an invisible field in older sheets, a see-through year list and the clipped rich-editor label (76d4300). Gate passed; deployed cd87097, Android 0.1.751. No server change.
- 2026-11-07 claude-opus-5.5 — UI17 web: swipe-to-reply survives hard/long swipes (iOS), sidebar keeps 240 px past 1600 px, Journal set width, avatars share one object URL and the message menu builds only when open (no flashes), icons warmed, motion layer; Android: chat header disc = list disc, compact fields/switches (D-125), chat/members/fronting motion. Emulator + Chrome checked on the demo; full gate with Android passed. Deployed f5963b4 (web index-DHHJFpzU.js, Android 0.1.761).
- 2026-11-07 claude-opus-5.5 — Device check on the owner's phone (0.1.761, inspect only): Fronting, history, drawers, switcher, Members, chat list/header, profile, relationships, search, settings, DM picker, buckets, default audience, quick-switch launcher, real FCM pushes all fine. Fixed what it found: invisible fields/choice rows inside same-colour cards (fields are now an ink wash), long drawers under the status bar (Android 15 screenHeightDp counts the bars), front time 0 h for named-front accounts, raw `--08-05` birthdays, search not focusing, quick-switch Add/Remove for everyone, Settings wording. Owner's report: Journal › Friends stopped at 5 posts per account; now pages (web + Android). Not checkable as inspection: widgets (not placed), polls (none visible), inline reply.
- 2026-11-07 claude-opus-5.5 — Deployed 6cc5211 (server, web `index-C2uTL0SY.js`, Android 0.1.767): the phone-check fixes and Friends paging. No migration.
- 2026-11-07 claude-opus-5.5 — CI1 e2e suite rewritten for the current screens (shared helpers: addMember, switchIn, fronting, openChat, message, newChannel, newFront, switchSheet, shareWithFriends); 30/30 pass locally in 2.3 min. It found three web bugs, fixed: Front history lines never opened their actions ($state proxy vs ===), a friend's heart missing on your own post's thread page, follower-view 404s for DM partners we don't follow. Web job: timezone-dependent Insights test pinned to UTC.
- 2026-11-07 claude-opus-5.5 — CI1 finished on GitHub: first green run since 2026-09-26 (42360317206: rust, web, e2e). Last fixes: the 50k first-screen timing test runs alone and best-of-three (CI scales budgets 2x with CHORUS_BUDGET_SCALE), e2e navigation/reconnect races, and a real web data-loss bug the suite found (an offline send followed at once by a reload lost the message; unsaved ops now journal to localStorage on pagehide). Deployed ae04b8a (server, web `index-CIJWwgr6.js`, Android 0.1.785).
- 2026-11-07 claude-opus-5.5 — UI18 (D-126): member handles. Core `handles.rs` derives `@member.subsystem.system` from the display name, innermost subsystem and account handle (twins `-2`), exported to wasm/FFI. `member.handles` claims (migration 0021, ≤5, `[a-z0-9_]{2,32}`), one namespace with account handles, refused at ingest as `conflict`. The server writes `account.set {handle}` at enrolment and backfills it at start-up. Cards to other accounts carry `handle`/`handles`. Full names are a `name` profile part, private unless chosen. Display names follow the profile, and every cross-account card applies this (profiles, directories, relationships, shared-chat and post authors). Web and Android: profile handle line, Journal `Name @handle`, chat mention suggestions/resolution, member editor Handles section and name audiences. Full gate with Android passed (bindings regenerated for the fresh worktree). Web demo check: claim, profile, Journal, friend view, and the full name hidden from a friend (69c52ec).
- 2026-11-07 claude-opus-5.5 — UI18 (D-126, owner): default handles name every subsystem on the member's path, innermost first (`moss.burrow.tiny-ones.littles.stars`), so each member has one path; plain groups on the path name nothing.
- 2026-11-07 claude-opus-5.5 — UI18 (owner): displays show only the one shown handle (no "also" lists on profiles, web or Android); the member editor alone lists them all.
- 2026-11-07 claude-opus-5.5 — UI18 merged to main (4268dee, over CI1's last commits), full gate with Android passed, deployed to ns2: server and web `index-DuOJjB7M.js`, Android 0.1.791. Migration 0021 (additive `member.handles`); account handles backfilled into the op log on start.
- 2026-11-07 claude-opus-5.5: UI19 server + Android deployed (a7bc600, 8110d79, 8222e4f; Android 0.1.795): Notifications tab (F11), replies to our posts in Journal › Friends with what they answer, one action row with disabled actions, quoting other accounts' posts (the quote borrows the quoted audience), reply notifications, per-post Random delay / Don't notify (D-127).
- 2026-11-07 claude-opus-5.5: UI19 web deployed (0d436de): Notifications page with the F11.5 side panel and sidebar badge, Friends replies with their parents, one action row, quoting other accounts' posts, the composer's notifications choice. `scripts/demo-journal-activity.mjs` seeds cross-account Journal activity on a demo (OPS §8).
- 2026-11-07 claude-opus-5.5: UI19 follow-up (f6fbda9, owner): DMs, member DMs and all-message channels are pushed but no longer listed in Notifications; mentions and replies still are. Deployed (Android 0.1.800).
- 2026-11-08 claude-opus-5.5: UI20 server + Android deployed (7ae85bb, e1b06af; Android 0.1.804): the Figma F7 Friends screens on Android (D-128), `GET /follows/{id}/view` for "What they see right now", accept drawer shared with the Notifications inbox, `follow.start_bucket` pref; Q24 opened (hidden-member count). Web next.
- 2026-11-08 claude-opus-5.5: UI20 web deployed (b212f83, `index-DK_ZDRUV.js`) with Android 0.1.808: the F7 Friends screens on web under #/people (D-128), one `Sheet`, the accept sheet shared with Notifications, browser push moved to Settings. The e2e run found a person's page had no Between you (fixed on both clients, 2261ab0); e2e follows the new screens and the redesign spec's stale Journal-in-More step is fixed (CI's e2e had been red since 0d436de). Full gate with Android passed.
- 2026-11-08 claude-opus-5.5: Owner bug fixes: Android private chats with nobody listed on the friend's side show the friend's picture (`ChatPresentation.rowIcon`); web chat rows show whose a private chat is ("• Kai"); Friends is both ways (D-129): `friends` and `following` audiences, `followers` reads "Followers", Follow back offered after accepting, one-way follows listed apart. Full gate passed.
- 2026-11-08 claude-opus-5.5: Owner bug fixes and D-129 deployed (f3edd7e, web `index-BpTssOuR.js`, Android 0.1.823). Checked on the isolated ui20 demo (web): Friends sections, Follow back after accepting, audience menu, the "• Foxglove" chat row. Not checked on a phone or on the owner's account.
- 2026-11-08 claude-opus-5.5: UI21 wave 1 on Android (4164a53 forward hides the source chat name in another space, web too; ef5b4df–2dba4b5 text trims + icon-led `EmptyState` + small tap cuts; 4d806c0 connection banner and slow-down toast). Live with Android 0.1.823. Owner answered the UI21 questions: D-130–D-138, Q24 closed.
- 2026-11-08 claude-opus-5.5: UI21 wave 2 deployed (871f90e, Android 0.1.828, server + web too): Chorus opens on Home (D-130, F0.1–F0.6) with the sections drawer; checked on the emulator against an isolated demo (`F:/DunBuild/ui21-home-demo`, port 5301). Design for the approved new features in `docs/handoff/ui21-design.md`.
- 2026-11-08 gpt-6-sol — UI22 Implemented fixed two-thirds-width, bottom-right banner backgrounds in both clients, including friends member lists (D-140). Deployment requested; integrate latest main and run the full Android gate before shipping.
- 2026-11-08 gemini-4-argon-medium — Deployed UI21 wave 3 (Android Members directory, Groups tab, Edit member, Who can see, Archive, Subsystem sheet/reorder: da99de1, d9c9d52, 12643cb) and UI22 (two-thirds-width Members banners on web and Android: c3a0acd, D-140) at a8839ac to ns2: server, web `index-GaV1UZH1.js`, Android 0.1.835.
- 2026-11-09 claude-opus-5.5: UI21 wave 4 deployed (3a23a56, Android 0.1.845, server + web): Android chat New message / New shared space pages (F5.18, F5.10), aside pill + audience bar (F5.4, F5.16), eight message actions + mention list (F5.6, F5.13), Forward/Reply in grid with inline warning and comment (F5.20), attach sheet with caption (F5.8), search with tabs and chips (F5.9), chat info tiles + Polls + per-member unread (F5.26). Checked on the emulator against the 5301 demo. Web to fix later: a forward with the author hidden shows "Forwarded from " with nothing after it.
- 2026-11-09 claude-opus-5.5: UI21 wave 5 deployed (12b8fda, Android 0.1.855, server + web): More/Settings/Appearance/bottom-bar editor (F10.1, F10.2), Trash with "Deleted by" (core `deleted_by_member_id`, F9.5), Insights (F9.1), On this device / Sync issues with Retry / update drawer with `android/whats-new.txt` (F9.6, F10.4, F10.5), one-screen welcome + Link a device QR (F1.1, F1.5, F1.6, new dep play-services-code-scanner), System settings, system setup + words (F1.2–F1.4). The first upload failed on a Tailscale SSH re-check; the owner approved it and the same bundle was installed by hand.
- 2026-11-09 claude-opus-5.5: UI23 (D-142, owner request): hold a post heart, a message reaction (chip or emoji row) or a poll option to choose which of our members do it, Android drawer + web sheet (right click too); writes one ordinary op per member, no server/core change. Web checked in headless Chrome on a private copy of the UI21 demo (`F:/DunBuild/many-members-demo`, 5311): chat ❤️ 2→4, Dinner poll Pasta for two, a post heart for all four; it caught a web bug before commit (the sheet read a `{@const}` after clearing its state). Android not seen on a device (the emulator was busy with UI21). Not deployed (owner: merge after UI21's parity pass finishes). D-141 is UI21's (poll closing, on main); this rebases over it, and the long-press on a closed poll option must then also check `!closed`.
- 2026-11-09 claude-opus-5.5 — UI23 and UI21 wave 6 so far deployed (03b4256, Android 0.1.864, server + web `index-0EYZ6R_E.js`, no migration): hold to act as several members; Journal composer and reading polish, System board (D-135), Feeds, Relationships, Lists, headings (D-134). Full gate with Android passed on ea1a81b; 03b4256 only rewrites whats-new.txt. Not seen on a phone.
- 2026-11-09 claude-opus-5.5 — UI21 wave 6 finished and deployed (25e670e, Android 0.1.869, server + web): poll closing time and early close (D-136, D-141), Nudge our own members who haven't voted (D-143), the real CHORUS wordmark on the welcome screen (F1.1).
- 2026-11-09 claude-opus-5.5 — UI21 wave 7 Stage slices 3-5 (not deployed): Android Stage as F8 (five tool tabs, round checks, capture pills, Save drawer, Stage… above Delete), definition additions through core with fixtures/stage (black-outs, custom palette, width, by-hand times, fake-name colours, hide reactions/thread previews/dates; D-144), and a post's stage from its ⋯ menu with nested replies, reply levels, only replies from and hide counts (D-145). Web draws black-outs/hidden dates/custom palette only.
- 2026-11-10 claude-opus-5.5 — UI21 wave 7 finished on Android (not deployed, not seen on a device): last trims (28f2bcc); notification taps open the friend page / Notifications / Front history and review rows say "Phone says X, laptop says Y" (0d6c777); the remaining Fronting/Friends sheets are drawers, PurposeDrawer and PickChip shared (0316c56); the search launcher is the Switch drawer with layers and an Unread widget (e143aec, CLIENTS §3.4–3.5); read-as rows with layer and count, stacked faces, Needs-you block, hold-menu kind, "Can be picked as speaker in chat" (slice 11).
- 2026-11-10 gemini-3.8-flash-high — UI21 wave 7 deployed (b4bc942, Android 0.1.883, server + web): Stage tabs/palettes/black-outs, post staging, member states & note, custom notification ring kinds, remaining drawers, Switch drawer launcher & Unread widget. Server and Caddy active on ns2-up-net-nz.
- 2026-11-12 claude-fable-5.2 — Audit pass merged and deployed (5f2e61d, Android 0.1.922, server + web; migration 0023). Found by an audit of server, core and both clients; fixed by three claude-opus-5.5 agents on `t3/fix-server-audit-findings`, `t3/web-sync-lifecycle` and `t3/android-signed-out-chat-fixture`. Server: chat notifications and post alerts follow the sync visibility rule at delivery, pronouns and shown names follow the profile rule, deleted members' handle claims stay reserved (D-146), notifications follow current visibility (D-147, D-149), a 30-minute quiet cooldown on repeated follow requests (D-150), forwards notify (D-151), a signed-out device keeps its copy two days or erases at once (D-152), `bad_key` and the invite peek's `account_id` (D-153), reaction names only for visible members (D-154), D-098 private chats DM-only and D-097 by the front as switched (D-155). Web: one connection state machine with a 25 s ping, a signed-out state that returns to onboarding, the unsaved-ops journal merges instead of overwriting. Android: the same signed-out state and erase, `fixtures/ui_chat_visible.json` shared with web. Q25–Q28 answered by the owner. Full gate with --android and the browser suite's v1 and grants specs passed on the merged tree (e2e helpers brought up to D-129). Not seen on a phone or emulator; the Android erase steps have only unit tests. Not built: signed-out devices in `GET /me`, a `gone` list for Android's no-push poller.
