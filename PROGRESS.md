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

- **In progress:** -
- **Owner:** -
- **Next concrete step:** fix the cross-account write holes found by the privacy audit (see *Open: privacy audit* below), then the phone/browser checks listed under *State*.
- **Notes:** handoff compacted 2026-11-05 by claude-opus-5.5; state below checked against the repo and the live server that day.

### Open: privacy audit (top priority, security)

`crates/chorus-server/tests/privacy_audit.rs` is on branch **`wip/privacy-audit`** (232a384; it was
left untracked in `F:/DunBuild/chorus-buckets` on 2026-11-04 and is still there). An ordinary
account (Mallory) pushes ops through the real `ingest::accept` at another account's (Alice's)
entities. On main a9c3c2e **11 of 12 attacks succeed** (log: `F:/DunBuild/af/privacy-audit-1.log`):
approve her own follow request (`follow.accept`), rewrite her follow ceiling (`follow.set_ceiling`),
make Alice's private member public / rename / delete it (`member.set`, `member.delete`), add
herself to Alice's bucket (`bucket.assign`), change a bucket's ceiling (`bucket.set`), share
Alice's private feed (`feed.set`), write a field value on Alice's member (`field.set_value`), end
Bob's follow of Alice (`follow.end`), attach Alice's private file to her own public post
(`post.create` with a foreign attachment id), highlight a post on Alice's member
(`highlight.add`). Only a06 (opening a private custom field with `field.set`) had no effect, and
even there the op was accepted. The likely pattern (not yet confirmed in code): the op is in Mallory's own scope, but projection applies it to the entity id wherever it lives. The server is
public (D-095), so a01 alone lets anyone follow anyone and read followers-only posts and fronts.
Fix at ingest (entity ownership per kind, the way D-073 did for message edits), make every case
pass, add the test to the gate, deploy, and record the rule in DATA_MODEL/NOTES.

### State (checked 2026-11-05)

- **Live** on `ns2-up-net-nz` (https://chorus.vayne.garden): server + web from `fa4c6c0`
  (bundle `F:/DunBuild/chorus-linux/chorus-linux-fa4c6c0.tar.gz`), Android **0.1.722**.
  `main` = `a9c3c2e` = deployed code + D-118 gate change (no app change). GitHub: the local
  `origin/main` ref is `fa4c6c0`, so main is ahead of what was last pushed (M12.2 asks for a push
  after each commit; check `git status -sb`).
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
  go through `docs/OPEN_QUESTIONS.md` (open now: Q21, Q22).
- Production checks use the test account `claudetest` (its device is the owner's Chrome
  profile), never the owner's account. Mutations go to the emulator or an isolated demo server
  (`docs/OPS.md` §8 "Demo data"; `F:/DunBuild/ui8-demo` is a seeded two-account demo on 5283).
- Parked by the owner: **D-096 step 3, tighten ntfy** (Settings → Notifications "how this phone
  gets them", ntfy vs Google choice, ntfy.sh/login/refused endpoints, guide for Google-free
  friends); PluralSpec implementation M14.5–M14.9; Chorus Home M13.5.

### Branches holding unmerged work (kept for reference; everything else is merged or superseded)

| Branch | What |
| --- | --- |
| `wip/privacy-audit` | the audit above — next task |
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

### UI — Owner's Figma redesign

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
