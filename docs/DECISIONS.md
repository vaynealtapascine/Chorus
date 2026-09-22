# Decisions

Settled. Do not re-ask the owner about anything here. To change one, add a new entry that
supersedes it (and say why), don't edit history.

`Owner` = answered by the owner in the 2026-09-23 Q&A. `Spec` = chosen by the spec author as a
default the owner has not contradicted; these may be revisited if implementation proves them wrong.

## Product

| ID | Decision | Source |
| --- | --- | --- |
| D-001 | Chorus replaces PluralKit for the owner's system. Focus: Discord-style chat/channels, Twitter-style member profiles + journals, screenshot "stage" modes, quick switching, switch notifications for others. | Owner |
| D-002 | Multi-tenant: the server hosts several **systems** and **person** (singlet) accounts. Friends/partners follow systems and get notifications. | Owner |
| D-003 | Every account has ≥1 member. A person account has exactly one member (themself), whose member UI is hidden. Authorship is therefore always `member_id` everywhere. | Spec |
| D-004 | Default term is "member", customizable per system (singular + plural, and for "front", "switch", "subsystem"). | Owner |
| D-005 | PluralKit **import** only (file + API token). No ongoing PK sync, no Discord bot. Simply Plural import not in v1. | Owner |
| D-006 | Chat scope: each system has a private **internal space**; **shared spaces** and **DMs** exist between accounts. Messages are always sent *as* one or more members. | Owner |
| D-007 | Speaker selection: defaults to current primary fronter; per-message speaker chip; PK-style proxy tags; **emoji sigils**; **multi-author messages** by stacking sigils/tags at the start (`🌌🔖❤️‍🔥 text` = three co-authors). | Owner |
| D-008 | Chat v1: replies, reply in a different channel, reply privately (in DM), quotes (full and partial), forwards (to channel/space/DM), edits with history, deletes, pins, reactions, threads, attachments + images, search, mentions, Telegram formatting parity, hidden messages. | Owner |
| D-009 | Voice/video messages and link embeds are "if easy" → scheduled **after v1** (L1, L2). The data model reserves space for them now. | Owner/Spec |
| D-010 | "Hidden" means all of: spoilers; content-warning collapsed; visible only to chosen members (soft, in-system); system-only asides inside shared spaces; and stage-mode "context-only"/hidden for screenshots (a view state, not stored on the message). | Owner |
| D-011 | Front model: co-fronting, **fronting order**, primary fronter, **co-conscious**, **present-transient** (present but not fronting, typically for rarely-present members), **custom states** (blurry, dissociated…), **subsystem fronts** (a group fronts as a unit). | Owner |
| D-012 | Scale target: 50–500 members per system, subsystems nested to any depth, members in many groups. | Owner |
| D-013 | Widget: pinned + recent grid, subsystem folders (drill in), search launcher. Tap switches **immediately** with an Undo chip. | Owner |
| D-014 | Because Android widgets cannot handle long-press on items, co-front from the widget uses a mode chip in the widget header: **Replace · Add · Remove** (Replace default, resets after 30 s). | Spec |
| D-015 | Switch-notification privacy: **random delay**, **time fuzzing**, **digest batching**, quiet hours. Rules: **system sets a ceiling** per follower, follower filters down. Per-switch, per-member and per-recipient controls all exist. | Owner |
| D-016 | A follower can never see more, or earlier, via any view or API than their notification rules allow ("follower view" invariant, NOTIFICATIONS.md §5). | Spec |
| D-017 | Profiles/journals v1: posts + replies/quotes/reactions from members, long-form entries, rich profile, combined system timeline, relationships (internal + external), custom typed fields, Twitter-style lists, Bluesky-style **feeds** from a simple filter language (shareable), profile **highlights** (own and others' posts). | Owner |
| D-018 | Stage mode: select + hide (context-only) then native screenshot; style presets; redaction. In-app render-to-PNG is later (L3). Filter "only these members" to hide other members' replies. | Owner |
| D-019 | Data: built-in dashboards, live API + SSE stream, webhooks, analysis-friendly exports (tidy CSV, JSONL, SQLite copy, documented SQL views). | Owner |
| D-020 | Look: **soft & warm** — warm neutrals, rounded, member colours as gentle accents, generous spacing, subtle motion, deep warm ink for dark mode. | Owner |
| D-021 | Per-member PIN/biometric lock for member-private content (UI gate in v1; client-side sealing later, L5). | Owner |
| D-045 | Messages are **joint** by default (one annotation = all say it together). **Segments**: a new line starting with an annotation (sigils/prefix tags, optional `=>`) starts a segment with its own single or multiple authors. Stored as `message_segment` rows. | Owner (Q1) |
| D-046 | Person accounts have full profiles and can write posts/journals (their single member). Friends may reply/react to visible posts; systems can turn replies off per post or account. | Owner (Q2, Q3) |
| D-047 | Discord-style **channel permissions** (roles + per-role/per-account overrides). A single internal channel can be shared with outside accounts this way. | Owner (Q4) |
| D-048 | Any member can forward or quote a **selection** (a message, a text range, or several messages as a bundle) to another channel/space/DM; the recipient sees a snapshot. | Owner (Q4) |
| D-049 | Members get a 7-letter `short_id`, shown only in Advanced. | Owner (Q5) |
| D-050 | Stage mode may show **fake names and fake timestamps** (view-only overrides, never stored on real data). | Owner (Q8) |
| D-051 | Android distribution: APK download from the server, in-app update check, and silent OTA over the tailnet where Android permits (`USER_ACTION_NOT_REQUIRED` after the first self-update). | Owner (Q9) |
| D-052 | Follows are account-to-account; name/icon as proposed; Insights shows no rankings by default. | Owner (Q7, Q10, Q11) |
| D-053 | **Permanent server copies.** Retention is forever; deletes are tombstones; local eviction never deletes; everything deleted is restorable from Trash on Android or web. True erase only via server admin CLI `purge`. | Owner (Q12) |
| D-054 | **Custom emoji**: one server-wide set of uploaded image emoji (`:name:`), usable in messages, posts, display names/bios and reactions. Managed by admins (server setting can let every account add). No per-space/per-account sets, no stickers. | Owner (Q6) |

## Architecture

| ID | Decision | Source |
| --- | --- | --- |
| D-030 | Server: **Rust** (axum + tokio) with **SQLite** (WAL) via rusqlite. Single binary. | Owner |
| D-031 | Web: **Svelte 5 + Vite + TypeScript PWA**, offline-capable, local store in IndexedDB, syncs on reconnect. | Owner |
| D-032 | Android: **native Kotlin + Jetpack Compose**, Room over SQLCipher, Glance widgets, WorkManager. | Owner |
| D-033 | Transport: server reachable only on the tailnet via Caddy (TLS). Encryption at rest **on devices** (SQLCipher, Keystore); no E2E. | Owner |
| D-034 | Auth: **invite link + per-device keypair** (challenge/response), revocable sessions, no passwords. Optional Tailscale identity check as second factor (Advanced). | Owner |
| D-035 | Push: **UnifiedPush via self-hosted ntfy** on the tailnet. Fallback: foreground WebSocket service (opt-in), then WorkManager polling. | Owner |
| D-036 | Data is an **append-only op log** (event-sourced) + **projected tables** that are plain, readable SQL. Projections are rebuildable from the log. | Spec |
| D-037 | Conflicts on switches: **merge by real time**; both kept; current = latest; overlapping independent switches within the review window (default 2 min) produce a gentle **review card**, never a silent drop. | Owner |
| D-038 | Timestamps: device wall time + HLC; server measures per-device clock offset and stores raw + corrected time. | Owner |
| D-039 | Offline-sent messages appear at their **original time** with a "sent offline · synced HH:MM" marker; unread counts still bump. | Owner |
| D-040 | Shared logic lives in `chorus-core` (pure Rust) → native on server, **UniFFI** on Android, **wasm-bindgen** on web. Spike in M1.10; fallback is Kotlin/TS ports gated by the same conformance fixtures. | Spec |
| D-041 | Rich text stored as **plain text + entity ranges** (Telegram model), not markdown. The plain `text` column is directly analysable. | Spec |
| D-042 | IDs are **UUIDv7** as lowercase TEXT; times are **INTEGER milliseconds UTC** in columns ending `_at`, plus `tz_offset_min` where local time matters. | Spec |
| D-043 | Backups: nightly SQLite snapshots with rotation (keep N), phone keeps a full replica of the owner's own system, export on demand. No offsite in v1. | Owner |
| D-044 | Repo: `source/repos/Chorus`, public GitHub, MIT, owner's house README standard. | Owner |
| D-055 | Snapshots/catch-up are **op pages**, not table rows: every device is an op replica for its scopes (needed for `reproject` and for restoring a server). Table-row snapshots may be added later as an optimization. | Spec (M1.9) |
| D-056 | M1.10 spike **kept** the shared-core plan: `chorus-ffi` (UniFFI 0.32, JSON-string API over `chorus_core::api`) builds for arm64-v8a (0.96 MB) and x86_64; Kotlin bindings generate in library mode; `chorus-wasm` builds, runs in Node, 209 KB gzipped without wasm-opt (budget 300 KB). The on-device call is verified in M0.3. | Spec (M1.10) |
| D-057 | After a server restore, a **restore window** lets devices re-push ops with their original author and times (SYNC.md §7.3); it is closed by the admin with `chorus-server reconcile-close`. Outside it, pushes are always stamped as the pusher's. | Spec (M1.9) |

## Versions

Pin here as they are adopted (tool/library → version → date → why).

| Component | Version | Pinned | Notes |
| --- | --- | --- | --- |
| Android targetSdk | 36 | 2026-09-23 | Matches Dun |
| Android minSdk | 29 | 2026-09-23 | Android 10+; covers owner's Samsung and keeps Keystore/biometric APIs simple |
| Rust | 1.98 (MSRV via `rust-version`), edition 2024, resolver 3 | 2026-09-23 | No `rust-toolchain.toml`: the machine's stable is 1.98.1 and pinning an exact toolchain would force a second download. |
| serde / serde_json | 1.0.x | 2026-09-23 | |
| uuid | 1.x (v7 via `Builder::from_unix_timestamp_millis`) | 2026-09-23 | Core stays pure: callers pass time + random bytes. |
| sha2 | 0.10 | 2026-09-23 | Digests, blob hashes. |
| thiserror | 2.x | 2026-09-23 | |
| proptest | 1.x (dev) | 2026-09-23 | Convergence simulator. |
| uniffi | 0.32.1 (proc-macro, library-mode bindgen; bin `uniffi-bindgen` in chorus-ffi) | 2026-09-23 | Kotlin bindings need JNA on Android (`net.java.dev.jna:jna:5.x@aar`). |
| wasm-bindgen | 0.2.128 (crate) + wasm-bindgen-cli 0.2.128 | 2026-09-23 | CLI version must equal the crate version. |
| cargo-ndk | 4.1.2 | 2026-09-23 | `cargo install cargo-ndk --locked` |
| Android NDK | 28.2.13676358 at `F:/DunBuild/android-sdk/ndk` (shared with Dun) | 2026-09-23 | `scripts/build-android-core.ps1` finds it. |
