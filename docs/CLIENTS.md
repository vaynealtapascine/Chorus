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

## 3. The quick-switch widget (Glance)

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

### 4.3 Offline window

Account scope is fully replicated; `space:` scopes keep the last N days (default 90) of messages
plus everything pinned or starred. Scrolling beyond fetches from REST and caches read-only.

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
