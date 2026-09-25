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

### 2.3 Sync on Android

- A single `SyncManager` owns the socket while the app is foregrounded or while a sync work runs.
- Triggers: app start, network available (`ConnectivityManager.NetworkCallback`), outbox
  non-empty (expedited `OneTimeWorkRequest`), UnifiedPush tickle, periodic 15 min backstop.
- Mono clock: `SystemClock.elapsedRealtime()`; boot id: `Settings.Global.BOOT_COUNT`.
- Everything is on Room transactions; the widget and notification actions use the same repository
  (no Activity needed).

### 2.4 Deep links & intents

- `https://chorus.<domain>/i/<code>` (App Links) and `chorus://invite/<code>` → onboarding.
- `chorus://switch?member=<id>&mode=replace|add|remove` (for Tasker/NFC; confirmation toast).
- `chorus://c/<channel>/<message>`, `chorus://m/<member>`, `chorus://p/<post>`.
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

A translucent, dialog-styled Activity (`excludeFromRecents`, `taskAffinity` empty) that opens
with the keyboard up: fuzzy search over members/subsystems/states (core's matcher, ≤ 16 ms per
keystroke at 500), results with small **Replace · Add · Remove** buttons, and a "Multi" toggle to
stage several then **Switch**. Also reachable from app shortcuts and the in-app front pill.

## 4. Web PWA

### 4.1 Stack

- Svelte 5 (runes) + Vite + TypeScript strict. Router: a small hash/history router (as in
  Arbor) or SvelteKit in SPA mode — implementer's choice, record it in DECISIONS.
- **Worker architecture**: a dedicated worker owns IndexedDB, the wasm core and the sync socket;
  the UI talks to it via a typed message RPC. One leader per browser profile via Web Locks; other
  tabs attach through `BroadcastChannel`.
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
   core's `projectionDigest()`: a `Digest` over each projected op's id and server stamp, kept
   incrementally by the projector, so it names exactly the op copies the projection came from.
2. *Open.* `start()` reads only the head (device, meta, hlc, snapshot), parses the snapshot and
   mounts the app on it. The core replica starts empty (`WebReplica.begin`).
3. *Background, in slices.* Ops are read 5 000 at a time in id order (`loadOps`) into the core
   (`addOps`), then indexed 2 000 at a time (`indexStep`: each op's keys, as the projector needs
   for later updates; nothing is projected), yielding to the page between slices.
4. *Adopt.* `adopt(digest)`: if the indexed ops — minus any created while opening — are exactly
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
