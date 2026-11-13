# Chorus — clients

Android (native, primary), web PWA, and the home-screen widget. Both apps are thin shells over the
same model: a local SQLite/IndexedDB replica, an outbox, and `chorus-core` for every rule that
must agree across devices (SYNC.md, D-040).

---

## 1. Shared client architecture

```
UI (Compose / Svelte)
   │  observes
Repository layer  ── queries ──▶ local DB (Room+SQLCipher / IndexedDB)
   │  actions                          ▲
   ▼                                   │ apply / reproject
chorus-core (UniFFI / wasm) ── ops ──▶ local op table + outbox
                                       │
                                  Sync engine ── WebSocket ── server
```

- UI never talks to the network directly. Every write is `core.build_op → local transaction
  (op + projection) → outbox`.
- Reads are local queries, reactive (Room `Flow` / Svelte stores over IndexedDB change events).
- Paging for long lists (messages, timeline) from the local DB; beyond the local window, REST
  pages are fetched and cached.
- Which chats are shown (D-097 members' private chats, D-098 private chats in a DM, D-110 chats
  with a subsystem, D-102 the front as switched; D-155: both by the front as switched, and D-098 for
  DM spaces only, so a channel kept to some members in a shared space is ordinary) is written on each client (web `chat-list.ts`
  `privateVisible`, Android `ChatOrganize.privateVisible`) and held together by
  `fixtures/ui_chat_visible.json`: each case's space, channel, our members, front, Read as,
  `chat.hide_private` and subsystem expansion, with the expected `is_private` and `visible`.

Ticks on our own messages (D-131, D-157) are one core rule on both clients (`chorus_core::ticks`,
`ticks_for`, `fixtures/ticks`): a clock until the server has stamped the message, one tick once
stored, two once a member of ours who didn't write it has read it (per-member reading on) or, with
both accounts' "Show friends when we've read" on, a friend has. Other accounts' reads are fetched
when a shared or direct chat opens (`GET /channels/{id}/reads`) and kept up by the socket's `read`
frame, in memory only (web `read-receipts.ts`, Android `data/Ticks.kt`); they show on our side's
bubbles and on our last message in the chat list, and a client holds none while its own switch is off.

### 1.1 Named front history (D-080, D-083)

History pages read the device's visible operations through `frontSystemHistory` and
`effectiveFrontHistory` on the core replica (wasm/UniFFI). Pending offline work participates;
refused or removed copies do not. These are explicit on-demand reads, outside current front,
composer and Model.parse paths. Cache by the account front dependency revision, including a
past edit that leaves today's current state equal; unrelated chat/member changes retain it.

Both clients separate **Front history** (effective/inherited transitions) from **Saved entries**
(original editable IDs). Existing/deleted fronts can be selected for historical inspection.
Edit time, undo and redo apply to the selected front's saved history and keep following active;
only a manual current change overrides following. Custom layer labels retain independent IDs.
Show 200 log entries initially and load older rows on request. Android computes reads on its
store worker; web shares its account fold across selected fronts.

Insights uses the same on-demand histories. Configured accounts choose a front, an independent
time layer and Front history/Saved entries. Day/week/together charts measure only that layer;
change counts cover the whole selected front across all layers. CSVs carry `front_id` and
`history` (`own`/`effective`), plus `layer_id` for time charts. Deleted definitions remain
selectable for retained history; empty canonical data never uses old Body intervals. Unconfigured
accounts retain the previous front-only controls and CSV shape. Android clears old asynchronous
results immediately when the scope changes. Web retains stable history snapshots across unrelated
member/chat updates, so those updates do not recalculate timezone-aware totals.

Profile/feed/search/review consumers and front/layer management are still being integrated;
see FRONTS.md. Insights component fixtures have layout evidence in both themes and compact and
desktop widths; hydrated/live application and native rendering still require verification.

## 2. Android

### 2.1 Stack (pin exact versions in DECISIONS §Versions when adding)

- Kotlin 2.x, Jetpack Compose (Material 3 as a base, fully themed by Chorus tokens), Navigation
  Compose, Hilt (or manual DI — implementer's choice, record it), Coroutines/Flow.
- Room over **SQLCipher** (`net.zetetic:sqlcipher-android`); the DB key is random 256-bit,
  wrapped by an Android Keystore AES key.
- OkHttp WebSocket for sync; Ktor or OkHttp for REST; kotlinx.serialization.
- `chorus-core` via **UniFFI** (`.so` for `arm64-v8a`, `x86_64`), built by `cargo-ndk` in a Gradle
  task. Fallback (if M1.10 spike fails): Kotlin port gated by fixtures.
- WorkManager (sync, uploads, exports), Glance (widgets), `org.unifiedpush.android:connector`.
- Coil for images (with a disk cache keyed by blob hash).
- minSdk 29, targetSdk 36.

### 2.2 Modules

```
android/
  app/                 navigation, DI, activities, deep links
  core-bridge/         UniFFI bindings + Kotlin wrappers
  data/                Room entities/DAOs (mirror DATA_MODEL), repositories, sync engine, outbox
  designsystem/        tokens (generated), components (FrontCard, Avatar, MessageRow, SpeakerChip…)
  feature-home/ feature-switch/ feature-chat/ feature-members/ feature-journal/
  feature-stage/ feature-insights/ feature-settings/
  widget/              Glance widgets + actions
```

### 2.2.1 Native member and chat controls (UI6)

- Members rows open the shared Journal profile with banner, overlapping avatar, rich bio,
  typed fields and existing posts/highlights/media/relationships. Edit profile is explicit.
  Location and three website slots persist through existing typed fields, not local preferences.
  Saved opening baselines survive recreation and omit untouched fields from patches. Bio/chat
  edits retain identity per original mention/custom-emoji occurrence using durable UTF-16
  character provenance and core parser source maps, through text/formatting edits. Explicit
  Undo/Redo restores text, selection and provenance together; identical selected retyping is new
  input. Keyboard shortcuts, IME context actions and touch controls share that history. Newly typed
  tokens use the normal directory; code/escaped/retyped tokens cannot inherit stale IDs.
  Profile opening snapshots, rich histories and message edit segments live in
  account/server/purpose/session-scoped encrypted replica KV records. Chat composer drafts are one
  stable record per account, cached for the process: returning to Chat never reloads them, and a
  draft survives an app restart. Activity saved state
  carries only bounded references. Autosaves coalesce; saving activity state joins an exact latest
  checkpoint before background process death. Save/Discard removes only that own editor session;
  failed or cancelled saves retain it. Rich undo has a 32-frame / conservative 1 MiB history bound
  without truncating the current text or original rich data. Formatter source maps are linear;
  rich parsing and draft serialization run off the UI thread.
  Journal uses a stable account/server-scoped encrypted editor record too: source markup and
  selection, note/entry/title, ordered explicit authors (distinct from default intent), audience
  bucket IDs, CW/tags/mood, reply/quote, poll options and URI references with alt/spoiler survive
  reopening. Restore gates editing/autosave. Activity replacement transfers a writer lease;
  accepted writes drain before the replacement reads, and old callbacks cannot write afterward.
  Cancel/Back closes the composer while retaining its draft. Incoming shares append only after
  review when a draft exists; new-entry/reply/quote launches ask before changing its context,
  preserving existing text and author choices. Launches queue during posting. Unreadable metadata
  retains the exact original for recovery; missing file grants retain references and ask for
  reselection/removal, never silently omit a file. A durable chosen post ID is reconciled against
  persisted own-account create ops after cancellation/restart, including deleted posts. These
  editor checkpoints are device-local; cross-device Journal draft sync (SPEC §6.2) remains pending.
  All changed custom-field slots preflight against the latest definitions before any save op;
  incompatible/deleted/rebound definitions ask to cancel and reopen. Metadata/privacy is preserved.
- Avatar/banner selection copies at most 10 MB into an app-cache draft, decodes with API29
  ImageDecoder (EXIF-aware, source capped at 100 MP, output at 1600 px), then crops before
  staging PNG through the ordinary offline upload queue. Avatar output is at most 512 px wide;
  banners at most 1500 px, aspect 3:1. Zoom and position sliders provide accessible framing.
  Cancel crop leaves the editor draft alone; Apply changes the draft, Save writes references.
  Missing/cache-evicted sources ask the user to choose again. Member banners join durable offline
  retention and restore catalogues. Unsaved edits survive recreation; Back asks before discard.
- Reorder is available on the full active own-member list. Handles drag with displacement and
  frame-clock edge autoscroll; Earlier/Later buttons and accessibility actions provide alternatives.
  Apply emits sparse member.set patches; Cancel/Back discard the draft, gesture cancellation
  restores the pre-drag draft, and recreation cancels an interrupted drag. Saved drafts retain
  completed moves. Concurrent list/order changes require reopening. Filtered/archive/search
  views offer Show all active members to reorder. Search keeps relevance and name/ID ties.

- Navigation choices follow D-089: up to four device/account-local destinations plus fixed More.
  The chooser stages edits until Apply; Cancel leaves the current bar intact. Stable route keys
  retain drafts and scroll independently of tab placement.
- Internal chat has one **Read as…** entry opening a searchable, lazy member sheet with current
  selection and **Everyone present**. Selecting a reader retains the existing reading semantics.
- **New → Direct message** selects a sender and one or more own-system recipients, then reuses
  an existing `member_dm` with the same participant set or creates one through the ordinary
  offline op path. Connected-account DMs retain their online API path. These are the existing
  D-010/D-021 soft in-system privacy rules; UI6 channel restrictions remain separate work.
- Member and channel colours use a staged visual picker: palette, recent colours, hue,
  saturation, brightness, contrast-aware preview and optional hex precision. Applying a colour
  changes the parent editor draft; saving that editor writes the existing field. Cancel does
  not change the draft. Recent colours stay on this device.

### 2.3 Sync on Android

- A single `SyncManager` owns the socket while the app is foregrounded or while a sync work runs.
- Triggers: app start, network available (`ConnectivityManager.NetworkCallback`), outbox
  non-empty (expedited `OneTimeWorkRequest`), UnifiedPush tickle, periodic 15 min backstop.
- Mono clock: `SystemClock.elapsedRealtime()`; boot id: `Settings.Global.BOOT_COUNT`.
- Everything is on Room transactions; the widget and notification actions use the same repository
  (no Activity needed).
- The socket's lifecycle is one pure reducer, `data/SyncLink.kt` (state, events, effects; JVM-tested
  in `SyncLinkTest`). `Chorus` changes it only through `transition()`, under one lock, from the main
  and store threads alike: status, the current socket (numbered), backoff and a renewal in flight
  can't disagree, a frame or close from a socket that is no longer current changes nothing, and
  "caught up" is reported only while the socket that delivered the frame is still the live one.
- **Signed out (D-148, D-152, D-153).** When the socket answers `unauthenticated`, `Chorus` renews
  the session. The device is signed out for good when the server answers `signed_out` (403 from
  `/auth/session`, with `at` and `wipe_at`), a bare `forbidden` (an older server, or a device it no
  longer has; no times) or `bad_key` (401: the device key no longer matches; no times)
  (`SignedOut.refusal`). Anything else (no network, 5xx, rate limit, an expired nonce) keeps the
  usual backoff. Signed out, it is `Status.SignedOut` with a `SignedOutRecord` on disk
  (`sync:signed_out`: `at`, `wipe_at`, and the server's clock offset where the app knew it), so a cold
  start asks nothing: no socket, no retries, `syncOnce()` returns at once, `authedCall` fails with
  code `signed_out`, uploads and the notification check skip.
  - The app shows the ordinary welcome screen, as a phone with no device does, with one line: this
    phone was signed out from another device, and, with a `wipe_at`, that what it still holds will be
    erased on that date unless it is linked to the same account again first. What it keeps is not
    shown meanwhile: the model is empty, widgets included.
  - **Erasing** (`Chorus.erase`, steps in `data/Erase.kt`) runs only for a `wipe_at` that has passed
    (checked when the answer arrives, at every app start, and by the one-off `WipeWork` at the
    deadline, so with the app closed too) or for the enrolment rule below. It removes the encrypted
    store (ops, metadata, drafts, settings, push state), its key and the device key, downloaded and
    queued files, the app's preferences (widgets, pins, setup, navigation), the account's
    WorkManager work, push registration (UnifiedPush unregister, FCM token), shown notifications and
    launcher shortcuts; unsynced changes go too (the owner's choice). A marker set first and cleared
    last makes an interrupted erase finish at the next start. Afterwards the app is a fresh install's.
    With no `wipe_at`, nothing is erased on a timer.
  - **Linking again** is the ordinary onboarding. The invite is peeked first (`POST /auth/invite`,
    which names the account of a `device` invite): a device link of the kept copy's account enrols and
    picks the copy back up (new device id and HLC node over the same store; ops never sent go out,
    since core relists unsent ops at restore and the server stamps the device on ack; `PickUpTest`).
    Any other invite, or a device invite whose account the server didn't name, erases the kept copy
    first, after a confirmation that says so (`SignedOut.enrol`).
  - **Signing another device out** (F1.6) offers "Also erase its copy now" (off by default; sends
    `{"wipe": true}`): it happens the next time that device connects; otherwise its copy is erased two
    days after it was signed out.

### 2.4 Deep links & intents

- `https://chorus.<domain>/i/<code>` (App Links) and `chorus://invite/<code>` → onboarding.
- `chorus://switch?member=<id>&mode=replace|add|remove` (for Tasker/NFC; confirmation toast).
- `chorus://c/<channel>/<message>`, `chorus://m/<member>`, `chorus://p/<post>`.
- Shared links (D-109): `https://chorus.<domain>/post/<id>` and `/message/<id>` ("Copy link" in a
  message's actions and behind a post's Share) open the post in Journal or the message's chat at it
  (only where `ChatOrganize.actionMessage` lets this reader see it now). Inside the app every rich
  text hands such links to `LocalChorusLinks` instead of the browser, and a chat message shows what
  it links to under its text (`ChatLinkEmbeds`, `data/ChorusLinks.kt`). The web app does the same
  (`share-links.ts`, `LinkEmbeds.svelte`; the server's SPA fallback serves it at those paths).
- **Share target**: text/images/files → pick "Post as…" or "Send to channel…".
- **App shortcuts**: static "Switch…", "Switch out", "New entry"; dynamic: top 4 pinned members.

### 2.5 Member locks

- Lock = PIN (4–12 digits) and/or biometric (`BiometricPrompt`, class 3). PIN is stored as
  Argon2id hash + salt in `pref` (`member:<id>:lock`), synced so it's the same PIN everywhere.
  It is a UI gate, not encryption (sealing is L5) — the settings screen says so.
- Locked content: member-private posts/entries, member DMs, locked channels. Unlock lasts until
  timeout (default 5 min) or app backgrounded (Advanced).
- `FLAG_SECURE` on screens showing locked content (Advanced toggle, default on).

### 2.6 Notifications

See NOTIFICATIONS.md §7. Avatars for `Person` icons are pre-rendered circular bitmaps with the
member ring, cached by `(blob_hash, color)`.

The Notifications list, its badge and the unread widget come from one full read
(`Inbox.refresh`, `GET /notifications` without `after=`) that replaces the list each time, so a
notification whose message or post was deleted or stopped being readable leaves at the next read
(D-149; the server filters at read time). Only `NotifyCheck`'s look for new pushes is incremental
(`?after=`). Offline, the last read stays until a read succeeds; system notifications already shown
are left as they are.

## 3. The quick-switch widget (RemoteViews, D-058)

### 3.1 Layout

```
┌───────────────────────────────────────────┐
│ (Kai)(June)  Kai & June · 2h   [Replace▾] 🔍 │  ← header: current front, mode chip, search
├───────────────────────────────────────────┤
│  (★Kai)  (★June)  (★Rin)  (Ash)           │  ← pinned first (★), then recents
│  (Moss)  (Wren)   [Stars ▸] [Deep ▸]      │  ← subsystem folders (if enabled)
└───────────────────────────────────────────┘
```

- Sizes: from 2×1 (header + 2 avatars) up to 5×5+. Columns derive from width; avatar size from
  Advanced setting (S/M/L).
- **Root**: pinned (manual order) → recent (distinct subjects most recently switched in, excluding
  pinned) → folder tiles for top-level subsystems (toggle).
- **Folder** (tap a subsystem tile): header shows breadcrumb `‹ Stars`; first tile "Whole
  subsystem" (if `can_front`), then sub-folders, then members sorted by most recent front. State
  (current folder) is per widget instance.
- **Per-widget scope** (Advanced, in the widget config activity): all / a subsystem / a group.

### 3.2 Behaviour (D-013, D-014)

- Tap a member/subsystem tile → **immediately** applies per the mode chip:
  - Replace (default): `front.switch` with that subject as the sole primary fronter.
  - Add: `front.add` at level `front`, not primary.
  - Remove: `front.remove`.
  Tapping someone already in front in Replace mode makes them primary and removes others (same
  as switching to them).
- Once Body is configured, the corresponding quick actions and undo use `front.change` with
  the stable Body ID and independent `layer_id`; unconfigured accounts retain the legacy kinds
  above.
- The mode chip cycles Replace → Add → Remove on tap and resets to Replace 30 s after last use.
- After a tap, the header shows "Switched to Kai · **Undo**" for 10 s. Undo → `front.retract`.
  Undo taps after 30 s are ignored (guard against stale widget UI when the clear update runs
  late).
- The action callback writes through the repository (Room transaction via core) and calls
  `updateAll` for all Chorus widgets, then enqueues expedited sync. Budget: ≤ 150 ms to redraw.
- Search button → **Search launcher** (§3.4).

### 3.3 Implementation traps (record new ones here)

- RemoteViews has a bitmap memory cap; don't pass many large bitmaps. Pre-render 96–144 px
  circular avatars with rings to files and serve them through a `FileProvider` URI
  (`ImageProvider(uri)`), or keep bitmaps tiny.
- No long-press on widget items — hence the mode chip.
- Glance recomposes off the main thread in a short-lived session; don't do DB work in composition —
  read a prepared `WidgetSnapshot` (current front, pinned, recents, folder contents) from a small
  cache table updated whenever the front or members change.
- Clearing the Undo row: schedule a one-off update (WorkManager with 10 s delay, or
  `AlarmManager.set` inexact); lateness is fine because Undo checks its deadline.

### 3.4 Search launcher

A translucent Activity (`excludeFromRecents`, `taskAffinity` empty) that opens the app's Switch
drawer (F3.2) with its search field focused: fuzzy search over members/subsystems/states (core's
matcher, ≤ 16 ms per keystroke at 500), the face grid with layer tags on Body's layers, Replace /
Add, and **Switch** with its summary (UI21). Enter places the top match, a second Enter switches.
Also reachable from app shortcuts and the in-app front pill.

### 3.5 Unread widget

A small RemoteViews widget (F3.8 "Chorus · 3 unread for Foxglove"): the Notifications count for
the default reader, the same number as the bottom bar's badge; tap opens Notifications. The app
redraws it when the count or reader changes; a placed widget with no inbox read yet reads it once.

## 4. Web PWA

### 4.1 Stack

- Svelte 5 (runes) + Vite + TypeScript strict. Router: a small hash/history router (as in
  Arbor) or SvelteKit in SPA mode — implementer's choice, record it in DECISIONS.
- **Tabs** (built R29; the worker architecture is still later): each tab runs the client
  (`sync/client.ts`) and the wasm core on its main thread, and **one tab per browser profile
  syncs**: the holder of the `chorus-sync` Web Lock.
  - **The syncing tab** runs the socket and owns the shared metadata (cursors, outbox order,
    clock) in IndexedDB.
  - **Other tabs** stay fully usable. They save only the ops they make, send them over the
    `chorus-sync` `BroadcastChannel` (the leader `adoptLocal`s and sends them), take in what the
    leader saved (`absorb`), and show its status.
  - **When the syncing tab closes,** the next one gets the lock and takes over. It reads the
    saved ops and metadata again (`reloadMeta`) first, so an op the old leader made and never
    sent still goes out.
  - **No Web Locks** (a plain-http address): every tab syncs, as before.
  - **The safety net under all of this is in core:** when a replica opens, any unconfirmed op
    that the saved metadata doesn't list goes back in the outbox (`relist_unsent`). Two writers
    of the metadata can't lose an op.
  - **A save that fails** (the browser's storage is full) is kept and tried again with the next
    one, and the app says so until one succeeds.
  - **Ops not yet in IndexedDB** when a tab goes away are journalled to localStorage
    (`chorus-unsaved-ops`) on `pagehide`. Tabs share the key, so each adds its ops to what is
    there, by op id, instead of replacing it (a quitting browser hides every tab at once).
  - **The connection** (SYNC §6.5, D-148): the syncing tab pings a socket that has been quiet for
    25 s and drops it 10 s later if nothing came back, so a laptop waking from sleep or a dropped
    idle socket doesn't stay "Live".
  - **Signed out from another device** (D-152, D-153; SYNC §6.5): the browser stops syncing for
    good (status `signed-out`, no retries) and shows the ordinary onboarding screen, with one
    quiet line: it was signed out from another device and, when the server gave a date, what it
    still holds is erased then unless it is linked to the same account again before. The kept
    copy isn't shown meanwhile. A key that no longer matches (`bad_key`) or an older server's
    `forbidden` does the same without a date, and nothing is erased on a timer. When the date
    passes the copy is erased (`sync/erase.ts`, the only place that erases), every tab follows,
    and onboarding still says why it is there. Linking again is onboarding as usual: a device
    link of the same account picks the copy up, and what was written here and never sent goes
    out under the new device; any other invite erases the copy first, after a confirmation that
    says it includes what never synced.
  - **Signing a device out** (*Devices and data* → the device → *Sign out*): an inline
    confirmation says it needs a new link to come back and that its copy is erased from it two
    days later unless it's linked again; *Erase its copy now instead* (off by default) sends
    `{"wipe": true}`, which takes effect the next time that device connects.
    Tested in a real browser at the end of `web/e2e/v1.spec.ts` (sign out, onboarding, reload,
    link again, sign out with erase at once).
  - Tested in a real browser with the server killed, a reload, a service-worker update, a
    closing tab and a full disk: `web/e2e/failure.spec.ts`.
- IndexedDB through `idb`; object stores mirror DATA_MODEL tables with indexes for the hot
  queries (`message: [channel_id, occurred_at, id]`, `front_interval: [account_id, start_at]`…).
- Service worker precaches the app shell and fonts; the app is fully usable offline after first
  load. Update flow: "A new version of Chorus is ready · Reload".
- Virtualised lists for messages/members/timeline; images lazy with blurhash-like placeholders
  (average colour from the thumbnail).
- Charts (Insights): a small SVG chart layer (no heavy chart library); data from the same views.

### 4.2 Structure

```
web/
  src/lib/core/        wasm bindings + TS types generated from fixtures/schema
  src/lib/worker/      db, sync engine, outbox, rpc server
  src/lib/stores/      reactive queries (rpc client)
  src/lib/design/      tokens.css (generated), components
  src/routes/          home, chat, members, profile, journal, stage, insights, settings, onboarding
```

REST calls go through `src/lib/http.ts` (`apiFetch`), which copes with the server's rate limit
(API.md §1): a `429` on a GET/HEAD is retried after `Retry-After` (at most twice, never waiting
more than 10 s), and a `429` on a write, or a read still limited, throws `RateLimitedError`, whose
message is fit to show ("… Try again in N seconds."). Blob uploads wait at least that long before
resuming. Blob reads (`AvatarImage`, `AttachmentView`, `EmojiImage`) use plain `fetch`: the
server doesn't count them.

Journal composer drafts (M7.2) follow the chat composer's precedent: one draft per account and
composer context (the main composer, or a thread's reply box) in `sessionStorage`, so a draft
survives reloads and navigation in that tab but not closing it (Android keeps its Journal draft in
the encrypted replica store; SPEC §6.2's cross-device draft sync is pending on both). A draft keeps
text, kind/title, CW/mood/tags, audience and buckets, authors (`null` = follow the current front
until someone picks, as on Android), quote and poll. Files can't be stored, so the draft records how
many were left and says they need attaching again. A post's files are `post.attachment` rows in the
account scope; chat's `attachment.create` belongs to a space and is rejected there.

### 4.3 Offline window

Account scope is fully replicated. A browser tab without "keep everything" (below) keeps the
messages, reactions, attachments and read marks of `space:` scopes that arrived in the last 90
days (SYNC §6.5, D-075: the device says its window in `hello`, the server leaves the same ops
out, so digests agree); channels, spaces and permissions are always kept. Older history:
*Older messages (from the server)* at the top of a channel pages `GET /channels/{id}/messages
?before=` in, read-only and not stored. Pinned messages older than the window aren't kept (the
window rule must be decidable from the op alone, on both sides); `GET /pins` has them.
Measured (2026-09-25, `web/perf` "a year of 100000 ops", headless Chromium in the cloud box): a
year of history held whole is 100 000 ops, ~31 MB of IndexedDB, 9.3 s to open cold (no
snapshot); the same account in a windowed tab is 36 227 ops, ~11 MB, 3.4 s cold. From the
snapshot (R18) both mount in well under a second.

With the server down (or no network) the installed PWA keeps working (owner, 2026-09-24: this is
what "desktop" means for v1): the service worker serves the app shell and wasm core, the replica
and outbox live in IndexedDB, and every change queues until the socket is back. Blobs (avatars,
emoji, attachments ≤ 20 MB) are read through `sync/blobs.ts`: the upload queue, then the
`blobs-v1` Cache Storage copy keyed by hash, then the server; a finished upload is kept there
too. The name must not start with `chorus-`, which the service worker deletes on update. At
start the client asks for persistent storage (`navigator.storage.persist()`); Chrome grants it
silently to installed apps and engaged sites, so an uninstalled tab may still be evicted.
Checked 2026-09-24: enrol, add a member, stop the server, reload — Home, Chat, Journal and two
image attachments render, a member added and switched in offline reached the server on restart.

**Keep everything on this device** (D-070, R18). On, every op the account may see is kept (no
window); off, the window above applies (turning it on reconnects and the digest mismatch pulls
everything again). The setting is per device — kept in IndexedDB (`kv["setting:keep_everything"]`), never
synced — and defaults to on in the installed app (`display-mode: standalone`), off in a tab. On,
it also keeps the *files*: once per session, 5 s after the socket is live, every attachment
(thumbnail, and the file itself up to 20 MB), avatar and custom emoji the projection refers to is
fetched into the offline blob cache (`sync/keep.ts` `fillFiles`, three at a time, through
`loadBlob`, so files already kept cost a cache lookup).

*Your data → This device* has the toggle and **Sync everything now**:

1. *Re-check every scope.* The core's `recheck()` gives one `pull {scope, after: cursor}` per
   scope. Each answer ends with `caught` and the server's digest for this account; a mismatch
   starts the usual repair (re-pull from 0, then sweep, SYNC §6.5), and `repairing()` lists the
   scopes still in it. Progress is "checked N of M" = scopes answered minus scopes repairing; done
   when all answered and none repairing (2 min timeout).
2. *Files*, when the setting is on: `fillFiles` with "Files: N of M (k not available)".
3. *Space used*: `navigator.storage.estimate()` — what the whole site stores (replica, snapshot,
   blob cache) and what the browser allows.

**Offline search** (`search.ts`): messages as before (`SearchIndex`); `JournalIndex` adds this
account's posts (title, text, tags, CW; `from:`, `before:`, `after:`) and switches (names of who
was in them at search time, and the note; `before:`/`after:`), both from the replica. The Posts
tab merges these with the server's `/search/posts` (other accounts' readable posts) when online;
the Switches tab is local only. 5 000 posts + 10 000 switches search in < 16 ms (unit test).

Android (Sol): same protocol — a per-device setting (default on), the file fill after connect,
"Sync everything now" as the same `pull` per scope with progress from `caught` frames and the
engine's repairing list (`JsonReplica::recheck`/`repairing` are in the FFI facade too), offline
search over posts and switches from the replica.

**Opening a big replica** (R18, D-070: a 100k-op device opens in ≤ 2 s). Projecting 100k ops
takes seconds in wasm, so the app opens from the last projection it showed:

1. *Snapshot.* The client saves `kv.snapshot = {projection (JSON text), digest, at}` 10 s after
   the last change and whenever the page is hidden, chained after the op writes. `digest` is the
   core's `projectionDigest()`: `{version, ops}`. `ops` is a `Digest` over each projected op's id
   and server stamp, kept incrementally by the projector, so it names exactly the op copies the
   projection came from. `version` identifies the projection shape and semantics, independently
   of the network sync digest. Bump `projector::PROJECTION_VERSION` when adding recognized kinds
   or changing projection behavior/shape; equal operations alone do not make an old UI cache valid.
2. *Open.* `start()` reads only the head (device, meta, hlc, snapshot). Before parsing or showing
   the saved projection, core's `projectionSnapshotCompatible` checks its versioned digest.
   Unversioned, malformed or future-version caches rebuild from the retained ops; invalid JSON
   or missing top-level projection sections also rebuild. A compatible snapshot mounts at once.
   The core replica starts empty (`WebReplica.begin`).
3. *Background, in slices.* Ops are read 5 000 at a time in id order (`loadOps`) into the core
   (`addOps`), then indexed 2 000 at a time (`indexStep`: each op's keys, as the projector needs
   for later updates; nothing is projected), yielding to the page between slices.
4. *Adopt.* `adopt(digest)`: if the projection version is current and the indexed ops — minus any created while opening — are exactly
   the snapshot's, the projector goes on from the snapshot, holding only keys recomputed from
   then on (a *partial* projection; `projection()` materialises it if anyone asks), and the
   ops created while opening arrive as the next delta. Otherwise (ops saved after the snapshot,
   e.g. the page was killed before its next snapshot) it projects everything and the UI re-reads
   it whole: the old, slow path, once. Only then does the socket connect.

Local writes work while opening (they queue and show once adopted); nothing syncs until then.
With no snapshot yet (first start) the app waits for the ops, as before. Exactness is
property-tested in `chorus-core/tests/projector_props.rs` (`opening_from_a_snapshot_continues_exactly`).

Measured with `web/perf/open.spec.ts` (headless Chromium on the remote box; `cd web && npm run
build && npx playwright test -c perf/playwright.config.ts`), 2026-09-24:

| Ops | No snapshot (before R18) | From the snapshot: app mounted | Ready to sync (background) | Longest task after mounting |
| --- | --- | --- | --- | --- |
| 10 000 | 0.6 s | 0.09 s | 0.4 s | 74 ms |
| 100 000 | 6.7 s | 0.35 s | 4.4 s | 93 ms |

At 100k the snapshot is ~32 MB of JSON; reading and parsing it is ~0.3 s of the open. The ops
themselves still load into memory (the core needs them for updates and repair); lazy loading of
old ops is a later step if memory becomes the limit.

**A big channel (R24, SPEC §9).** `web/perf` "open a channel of 50000 messages" seeds one channel
with 50k messages, opens the app from its snapshot, then measures switching to that channel (to
its first screen) and sending into it (to the new row in the DOM). The rules that hold these:

- The chat builds only the page it shows (`data.ts messagePage`: the newest 100 of the channel's
  ordered ids), not a row per message; pins, thread previews and unread badges read the
  per-channel order (`orderOf`) rather than scanning the message table.
- The message table is patched **in place** by `applyDelta` (`sync/delta.ts IN_PLACE`), with a
  version and a short log of what each key was, and `orderOf` catches up from that log. Copying
  a 50k-key object costs ~25 ms plus its garbage on every delta; every other table stays
  copy-on-write. Never `for…in` or `Object.keys` a big table on the send path: listing 50k keys
  is ~15 ms by itself.
- Lists derived from the order (pins, unread counts, built rows) are cached per list array: a
  channel's array is new exactly when one of its rows changed.
- Per-message work is shared: one `Intl.DateTimeFormat`, and each adapted colour computed once.

Measured 2026-09-25 (headless Chromium, remote box, minified build, three runs):

| | Before R24 | After |
| --- | --- | --- |
| Open a channel of 50k messages → first screen (≤ 150 ms) | 187–224 ms | 51–74 ms |
| Send into it → on the page (≤ 50 ms) | 68–97 ms | 28–38 ms (painted by 35–46 ms) |

## 5. Stage mode implementation notes

- Stage is a *view state* over the normal list (no data copies): `{selected: Set<id>,
  unselected_mode: 'hidden'|'context'|'visible', filters, style, redaction}` → persisted only
  if the user saves it (`stage.save`).
- "Capture" hides app chrome (top bar, composer, nav), switches to a clean container with padding
  and the chosen width, and on Android shows a small floating "Done" pill that is excluded from
  screenshots (`FLAG_SECURE` can't exclude a view — instead the pill fades out after 2 s and
  reappears on tap).
- Redacted names use stable placeholders per stage session ("Member A", "Member B") and
  placeholder avatars in neutral colours.
- Fake names/times are a render-time override map in the stage state
  (`{names:{member_id→{label,color}}, time:{mode:'shift'|'start'|'manual', …}}`); the renderer
  substitutes them at display time only. Nothing is written to messages.
- Black-outs and a custom theme (D-144) also go through core: the definition holds raw UTF-16
  ranges and three colours, `stagePlan` returns merged ranges per row and the derived palette.
  Android lays a square or wide stage out at its web width and shrinks it to fit the screen, so a
  screenshot keeps the web's proportions.

## 5a. App updates (Android, D-051)

- The server hosts release APKs (`/download/android`, plus `/api/v1/server` → `android_latest`
  with version code, size, sha256, changelog). The server announces a new version over sync (and
  a push tickle), so phones hear about it within seconds when on the tailnet.
- In-app: "Update ready · 12 MB · What's new" card → downloads over the tailnet (Wi-Fi only by
  default), verifies sha256 and signing cert, installs via a `PackageInstaller` session.
- **OTA without prompts where Android allows it**: the first self-update needs the user's tap
  (and "install unknown apps" permission for Chorus). After that Chorus is the installer of
  record, and on Android 12+ it requests `USER_ACTION_NOT_REQUIRED`, so later updates install
  silently in the background (Advanced toggle: "Install updates automatically", default on for
  the owner's account, off for others). Falls back to a one-tap prompt when Android refuses.
- Friends' phones get the same flow; Obtainium can also track the URL.
- **As built (UI7, 2026-10-31):** there is no sync announcement yet. Every app open checks
  `/android/latest` (at most every 5 minutes), fetches a newer build on unmetered networks (or when
  the banner's Download is tapped) and shows a banner: "Chorus 0.1.N is ready · Update". Updating
  closes the app, so it waits for the tap; leaving the app installs it silently when Chorus is the
  installer of record (Android 12+). If Android asks for a confirmation while the app is open, its
  dialog opens at once; in the background it is a notification. If "Install unknown apps" is off
  for Chorus, the banner's Allow opens that switch and the install resumes on return. A 12-hour
  WorkManager check on unmetered networks covers phones that stay closed. A build installed with
  `adb install` has no installer of record, so its first self-update may ask once.

## 6. PluralKit import (M11)

- Input: PK export JSON (file) or API token (fetches `/systems/@me`, members, groups, switches with
  pagination, respecting PK rate limits).
- Mapping: member → member (name, display_name, pronouns, description → text+entities via markdown
  import, color, avatar/banner downloaded into blobs, birthday, proxy_tags, `pk_id`); groups →
  groups (kind `group`; the user can mark some as subsystems in the review step); switches →
  `front.switch` ops with `time_source: user` and original timestamps (PK switches have no levels:
  all `front`, first member primary); privacy → visibility (`private` → private, `public` →
  followers by default, user can choose).
- Preview screen: counts, conflicts with existing members (match by `pk_id` then name), choices
  (merge/skip/duplicate), then import as one batch of ops (idempotent: op ids derived from
  `uuidv5(pk_id + field)` so re-importing doesn't duplicate).

D-114 scoped search is shared by web and Android: current-chat in-place search, All chats for
the recorded Body front, Journal posts/entries/replies and Saved bookmarked posts. Rich message
cards include up to one independently visible neighbor on each side, even outside search terms.
Local replicas work offline; other accounts' posts require a live audience check. Scope/front/
permission/session changes invalidate retained remote results and pagination. Counts refer only
to displayed visible matches. Windowed replicas may omit older context offline.
