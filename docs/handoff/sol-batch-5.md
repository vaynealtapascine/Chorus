# Batch 5 for gpt-6-sol: Android to v1 (runs in parallel with Claude and the remote Claude)

Written by claude-opus-5.5 on 2026-09-24, after merging your batch 4 work up to `38c02de` into
`main` (merge `42f3857`). The chat composer, replies and image preview read well; the
`ChatCompose` payload test is the right shape. One merge note: `tests/common::http_test_dir` now
roots its folders under `env!("CARGO_TARGET_TMPDIR")` (Cargo sets it for every integration test on
every platform), and the remote Claude's new admin-health test uses your helper too.

## Update — 2026-09-24 evening (read this first)

You ran out of usage mid-V1; local Claude picked up from your uncommitted work. Merge `main` into
`sol/batch-2` before anything else (your branch has nothing unmerged).

**Done since you stopped**
- **V1 (Claude):** your "More" fold was committed on your behalf (`93610b0`), then the chat screen
  was made compact and checked on the phone: one header row (space menu, channels, a whose-view
  menu), a one-line composer (speaker avatar menu · text · ⋯ options · send), reply in each
  card's header, and the tab bar hides while the keyboard is open so the composer sits on it.
  Sent as a chosen member with a CW and replied as another; both reached the server correctly.
- **V3 plumbing (Claude, `data/Blobs.kt`):** staging under SHA-256, a JPEG thumbnail, `UploadWork`
  (WorkManager, HEAD-resume, 4 MB chunks), a separate chat cache, and Attach in the composer's
  options (alt text, spoiler). Avatars now load through `Api.http` too. **Sent from the phone
  and checked:** the attachment op, its thumbnail and the fully uploaded blob reached the server
  and the image shows from the chat cache. The chat list now also follows new messages (a
  reversed LazyColumn kept its place, so a just-sent message landed out of view). Left for you:
  opening non-image files, and a share-into-Chorus intent. To pick a test file with adb, index it
  first: `adb shell content call --uri content://media --method scan_volume --arg external_primary`.
- **Chorus Home (Claude, D-071, `docs/HOME.md`):** one-click install on a home PC; the Android
  side is `data/Pins.kt` (a `chorus://<lan ip>:<port>/i/<code>#pin=sha256/…` invite pins the
  server's self-signed certificate). Unit-tested, not run on a phone yet (below).

**Correction about the phone.** The Chorus app installed on the owner's phone is enrolled on
**Claude's dev server** (`http://127.0.0.1:5251` through `adb reverse tcp:5251 tcp:5251`, demo
members Kai/Moss/Rin/…), not the owner's real server. Sending test messages there is fine. The
phone's account lives in the main checkout's `data-dev/` (your 5261 server has other data), so
for phone sessions run that one on 5251 (`chorus-server --dev serve` from the main checkout, when
Claude isn't running it — ask the owner), keep the reverse, and reopen the app to reconnect. The owner's
quick-switch widget is placed on their home screen: don't move it. Still ask before each session;
never clear the app's data or uninstall it.

**Designs are coming.** The owner is designing the app in Figma from `docs/FLOWS.md` (every flow,
by ID; frames named `F3.2 · Android · dark`) with `docs/ENTITIES.md` as the data reference. When
frames exist, Claude will write you a hand-off mapping frames to code, and you'll implement them.
Until then: favour the data, sync and logic side of each task, keep new screens plain (tokens,
simple layout), and don't spend time on visual polish that a design will replace.

**Update 2026-09-25:** the remote Claude's R9–R19 are merged into `main` (`git pull`). Three
things for Android, from its report (`opus-remote-2.md` §5):

- **V0 · `Changes.removed` (do this first, it's small).** Channel permissions (M5.10) can take a
  channel away from an account; the core now reports ops a device may no longer see in
  `changes.removed`. Android's `Store.save` must delete those ids (web does it in `persist.ts`);
  without it an evicted op comes back after a restart until the next repair sweeps it again.
- **V8 is unblocked.** CLIENTS.md §4.3 has the protocol and numbers. `recheck`/`repairing` are in
  `chorus-ffi` already; opening from a projection snapshot (`begin/add_ops/index_step/adopt`)
  isn't exposed through FFI yet: ask in your log if you want it, and Claude or the remote adds it.
- Migrations 0007 (post search) and 0008 (export jobs) are taken: **your next free number is 0009**.

**Order now:** V0 → V2 → V4 (the biggest gap) → V5 → V3's file opening → V6 → V9 →
V7 → V8. V1 is done.

- **V9 · Chorus Home on a phone (M13.5).** Once the owner agrees to a session with an app that
  isn't enrolled (a second phone, or the owner re-enrolling theirs): run a Chorus Home test server
  with `lan_listen` on the wifi, scan the QR from its *This computer* page, and check the phone
  joins over TLS with the pinned certificate, syncs, and loads avatars and attachments. Then mDNS
  rediscovery when the PC's address changes (HOME.md §2), which isn't built yet.

## The goal: v1

The owner asked for one full, stable build that works end to end on the phone, the web and the
desktop (the desktop is the installed PWA; there is no separate desktop app). CLIENTS.md calls
Android the **primary** client, and it is the biggest gap: the web has Journal, People, Profile,
Search, Settings, Stage, Insights, Trash and Your data; Android has Home, Switcher, Members,
History, Chat and device linking. This batch is yours because you have the phone and the Android
toolchain. The remote Claude does channel permissions (M5.10), CI, search, feeds, the export
bundle and a browser e2e suite (`docs/handoff/opus-remote-2.md`); local Claude does audits, the
end-to-end v1 run on the real server, Web Push and the landing page.

## Step 0

1. `sol/batch-2` has been fast-forwarded to `main` (your worktree was clean). Check
   `git log -1` shows `main`'s head or later, then keep working on `sol/batch-2`.
2. Rebuild the wasm pkg and the Android core, run `verify.py --quick --android`.
3. **The phone:** the owner uses it day to day now (Battery Saver is off). Ask the owner before
   each device session, keep sessions short, and never uninstall the app or clear its data (the
   installed build is enrolled on the owner's real server). Install over it with
   `adb install -r`; a debug build's version code is the commit count, so it installs over the
   release as long as your branch is at least as new as `main`. Don't describe personal content
   you see on screen in logs or commits.
4. F: now has room (77 GB free on 2026-09-24), so the disk is no longer a constraint, but keep
   using your own `chorus-target-sol`.

Same rules as before: dev servers on 5261/5262, `verify.py --quick --android` before every
commit, log in `sol-batch-2.md` under a new `### Batch 5` heading, what ran vs what only compiled.
Migrations: the remote Claude takes **0007** and up (channel permissions, export jobs). If you
need one, say so in your log first and check `migrations/` after merging `main`.

## Don't touch

- **Remote Claude:** M5.10 channel permissions (`ingest.rs`, `visibility.rs`, `spaces.rs`,
  `project.rs`, `search.rs`, `blobs.rs` and their tests; if Android needs a server change, write
  it in your log), and `app.rs`, `api_reads.rs`, `posts.rs`, `webhooks.rs`, `exports.rs`, web
  `Journal*.svelte`, `Search.svelte`, `DataPage.svelte`, `.github/`, `web/e2e/`.
- **Local Claude:** `web/src/lib/sync/blobs.ts` (the offline blob cache); Chorus Home (`tls.rs`,
  `home.rs`, `home_install.rs`, `web/src/lib/home.ts`, `ThisComputer.svelte`). `data/Blobs.kt` and
  `data/Pins.kt` are yours to extend (V3, V9); say what you changed in your log.
- `deploy/`, `scripts/deploy.ps1`, `scripts/pack-linux.ps1`.

## Tasks (in this order)

- ~~**V1 · device-check batch 4 on the phone.**~~ Done (see the update above). Send in an internal channel as a chosen member,
  with a CW, with "Chosen members", and an aside in a shared space; reply to a message (also one
  whose parent has a collapsed CW); view an image and a spoiler image. Fix what you find. The
  composer stacks two text fields, chips and a button under the list: on a phone it should
  collapse (CW and audience behind one "More" row, DESIGN §6 Basic vs Advanced).
- **V2 · Android shared spaces and DMs (M6.2).** Create a shared space, start a DM from a
  followed account, leave a space; foreign author cards from `/spaces/{id}/authors`, the same
  endpoints the web's `SpaceRail.svelte` and `People.svelte` use.
- **V3 · Android attachments out (M5.6).** Pick an image or file, stage it like
  `web/src/lib/sync/uploads.ts` (persist before queueing the op, HEAD for the resume offset,
  4 MB chunks, WorkManager so it survives the app closing), alt text and spoiler. Move chat
  images to their own disk cache keyed by blob hash (not `AvatarBlobs`); CLIENTS.md §2.1 says
  Coil, which is fine if it fits the offline cache.
- **V4 · Android People, follows and journals (M6.1, M7).** Following and follow requests,
  per-follow ceilings, the follower's view of a system; the journal timeline, posting (audiences,
  CW, replies, reactions) and member profiles. Reuse the web's server reads; every cross-account
  read already goes through `posts::readable_sql` on the server.
- **V5 · Android settings and search.** One Settings screen for the account prefs the web's
  `Settings.svelte` has (same `pref.set` keys, D-063; Basic vs Advanced), and local search
  (M5.8) over the Room replica with the server's `GET /search/messages` cursor for older hits.
- **V6 · Android Stage (M9.4)** with fake names and timestamps (CLIENTS.md §5), and the widget
  pins plus dynamic pinned shortcuts (M4.5, M4.6).
- **V7 · web leftovers.** Switch `data.ts`, `MemberEditor.svelte` and `Profile.svelte` from
  `fetch` to `apiFetch` (`web/src/lib/http.ts`, the remote Claude's R2) so 429s read well.

- **V8 · keep everything on this device, Android (D-070).** After the remote Claude's R18 lands
  (CLIENTS.md §4.3 will describe it): the same setting and "Sync everything now" in Settings,
  progress and space used, attachments optionally kept in the chat image cache (V3), and local
  search (V5) over messages, posts and switches.

## When done or blocked

Log it, then pick the next Android gap against the web's screen list. Insights on Android is
last (read-only charts over the local replica).
