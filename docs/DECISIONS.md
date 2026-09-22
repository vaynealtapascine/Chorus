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

## Versions

Pin here as they are adopted (tool/library → version → date → why).

| Component | Version | Pinned | Notes |
| --- | --- | --- | --- |
| Android targetSdk | 36 | 2026-09-23 | Matches Dun |
| Android minSdk | 29 | 2026-09-23 | Android 10+; covers owner's Samsung and keeps Keystore/biometric APIs simple |
