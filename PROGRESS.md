# Chorus — progress & handoff board

This file is the baton. Two agents (Claude Opus 5.5 and GPT-6-sol) take turns on this repo, and
either may run out of usage mid-task. **Read [AGENTS.md](AGENTS.md) first.**

Rules (short form):

1. Before starting a task, set **Now** to that task (id, who, when, what the next concrete step is).
2. After each atomic accomplishment: tick it on the board, add a line to the log, commit.
3. If you stop mid-task, leave **Now** pointing at it with enough notes that a cold agent can finish it.
4. Only one task is "in progress" at a time unless the notes say why.

---

## Now

- **In progress:** M1.2
- **Owner:** claude-opus-5.5, 2026-09-23
- **Next concrete step:** core/src/op.rs: envelope + payload structs; unknown kinds kept as raw JSON
- **Notes:** Read AGENTS.md → DECISIONS.md → SYNC.md + DATA_MODEL.md before M0/M1. Open questions (docs/OPEN_QUESTIONS.md) have defaults; don't block on them. README disclosure card image is not generated yet (M12.2).

---

## Board

Legend: `[x]` done · `[~]` in progress · `[ ]` to do · `[-]` dropped (say why in log)

### S — Spec (Claude, 2026-09-23)

- [x] S1 Q&A with the user (answers recorded in docs/DECISIONS.md)
- [x] S2 Repo created, license, handoff files (this file, AGENTS.md, CLAUDE.md)
- [x] S3 docs/SPEC.md — product spec, feature scope, v1 vs later
- [x] S4 docs/DATA_MODEL.md — SQLite schema, op catalogue, analysis views
- [x] S5 docs/SYNC.md — offline-first sync protocol, clocks, conflict rules, test harness
- [x] S6 docs/API.md — REST, sync socket, stream, webhooks, exports
- [x] S7 docs/NOTIFICATIONS.md — switch notifications, privacy ceilings, delay/fuzz/digest
- [x] S8 docs/DESIGN.md — visual system, screens, Basic vs Advanced settings
- [x] S9 docs/CLIENTS.md — Android app, widget, web PWA specifics
- [x] S10 docs/OPS.md — deploy on the PC, Caddy, ntfy, backups
- [x] S11 docs/OPEN_QUESTIONS.md — non-blocking questions for the user
- [x] S12 README skeleton (house standard; disclosure card still to generate)

### M0 — Scaffolding

- [x] M0.1 Cargo workspace: `crates/chorus-core`, `crates/chorus-server`; rust-toolchain pinned
- [ ] M0.2 `web/` Svelte 5 + Vite + TS skeleton (PWA plugin, strict TS)
- [ ] M0.3 `android/` Kotlin + Compose skeleton (Gradle version catalog, minSdk 29, targetSdk 36)
- [ ] M0.4 `fixtures/` conformance vector format + runner in Rust (see SYNC.md §9)
- [ ] M0.5 `scripts/verify` (fmt, clippy, tests, svelte-check, gradle lint/test) + CI workflow

### M1 — Core (pure Rust, no IO)

- [x] M1.1 IDs (UUIDv7), HLC type + string encoding, time types
- [~] M1.2 Op envelope + op catalogue types (serde, versioned)
- [ ] M1.3 Front fold: switch/add/remove/update/retract/amend → snapshots → intervals
- [ ] M1.4 LWW register + LWW element-set helpers, field-level merge
- [ ] M1.5 Text model: plain text + entities; markup parser (Telegram parity) + serializer
- [ ] M1.6 Speaker parsing: sigils (emoji), proxy tags, multi-author prefixes, newline segments (D-045)
- [ ] M1.7 Feed filter language: parser → AST → evaluator
- [ ] M1.8 Member colour contrast adjuster (WCAG AA both themes)
- [ ] M1.9 Convergence simulator (N devices, random partitions, projection hash equality)
- [ ] M1.10 Spike: UniFFI build for Android (arm64-v8a, x86_64) + wasm-bindgen build for web. Decide keep/fallback (DECISIONS D-040)

### M2 — Server

- [ ] M2.1 Config (`chorus.toml`), SQLite open (WAL, pragmas), migrations
- [ ] M2.2 Accounts, devices, invites, key-based auth, sessions
- [ ] M2.3 Op ingestion: validate → permission → append → project (single writer task)
- [ ] M2.4 Projections for all tables + rebuild-from-log command
- [ ] M2.5 Sync WebSocket (hello/push/pull/ack/snapshot/hash) per SYNC.md
- [ ] M2.6 Blob store (content-addressed, resumable upload)
- [ ] M2.7 Read API (REST) + follower views
- [ ] M2.8 Nightly backups + `chorus-server backup|restore|rebuild|export` CLI

### M3 — Web client foundation

- [ ] M3.1 Design tokens + base components (DESIGN.md)
- [ ] M3.2 Local store (IndexedDB) + outbox + sync engine (via core wasm)
- [ ] M3.3 Onboarding (invite → device key), system setup, terminology
- [ ] M3.4 Members list/grid, groups tree, member editor, custom fields
- [ ] M3.5 Front card, switcher, front history timeline, review cards

### M4 — Android foundation

- [ ] M4.1 Theme + components mirroring DESIGN.md
- [ ] M4.2 Room (SQLCipher) + outbox + sync engine (via core UniFFI) + WorkManager
- [ ] M4.3 Onboarding via invite link / QR
- [ ] M4.4 Members, groups, switcher, front history
- [ ] M4.5 Quick-switch widget (Glance): pinned+recent grid, folders, mode chip, undo
- [ ] M4.6 Search launcher activity + app shortcuts

### M5 — Chat (internal space)

- [ ] M5.1 Channels, categories, member DMs
- [ ] M5.2 Composer: speaker chip, proxy tags, sigils, multi-author, formatting
- [ ] M5.3 Replies (incl. cross-channel), quotes (full/partial), forwards, edits+history, deletes, pins
- [ ] M5.4 Reactions, mentions, read states, unread badges
- [ ] M5.5 Threads
- [ ] M5.6 Attachments + images (offline-queued upload)
- [ ] M5.7 Hidden messages: spoilers, CW/collapsed, member-visible, system-only
- [ ] M5.8 Search (FTS5 server, local search on Android)
- [ ] M5.9 Segmented messages (newline annotations) — parse, store `message_segment`, render
- [ ] M5.10 Channel permissions (roles + overrides) incl. sharing one internal channel outward
- [ ] M5.11 Forward/quote a selection (range or multi-message bundle)
- [ ] M5.12 Trash + restore for messages, posts, members, groups, channels
- [ ] M5.13 Custom emoji: server-wide set, upload/crop, picker + `:name:` autocomplete, reactions (D-054)

### M6 — Sharing

- [ ] M6.1 Person accounts, follows, privacy buckets
- [ ] M6.2 Shared spaces + DMs between accounts
- [ ] M6.3 Follower views (delayed/fuzzed front state per NOTIFICATIONS.md §5)

### M7 — Profiles & journals

- [ ] M7.1 Profile page (banner, fields, pinned, stats)
- [ ] M7.2 Posts (notes) + long-form entries, replies/quotes/reposts/reactions
- [ ] M7.3 Highlights, relationships + relationship types
- [ ] M7.4 Lists, feeds (filter language), sharing feeds
- [ ] M7.5 Combined system timeline

### M8 — Notifications

- [ ] M8.1 ntfy/UnifiedPush plumbing (server publisher, Android distributor registration)
- [ ] M8.2 Rule resolution: per-switch × per-member × system ceiling × recipient prefs
- [ ] M8.3 Random delay, time fuzzing, supersede/collapse, digests, quiet hours
- [ ] M8.4 Chat notifications (mentions, DMs, replies), inline reply

### M9 — Stage (screenshot) mode

- [ ] M9.1 Selection, context-only/hidden, crop reply chains
- [ ] M9.2 Style presets + redaction
- [ ] M9.3 Saved stages
- [ ] M9.4 Fake names and timestamps (view-only overrides)

### M10 — Data

- [ ] M10.1 Insights dashboards
- [ ] M10.2 API tokens, SSE stream, webhooks
- [ ] M10.3 Exports (JSONL op log, tidy CSVs, SQLite copy), documented views

### M11 — Import

- [ ] M11.1 PluralKit import (export file + API token)

### M12 — Ship

- [ ] M12.1 Deploy scripts (NSSM service, Caddy snippet, ntfy)
- [ ] M12.2 Disclosure card, screenshots, README, publish to GitHub
- [ ] M12.3 In-app APK updates + silent OTA (CLIENTS.md §5a)

### Later (not v1)

- [ ] L1 Voice messages, video messages
- [ ] L2 Link embeds / previews
- [ ] L3 Render stage to PNG in-app
- [ ] L4 Quick Settings tile, Wear OS
- [ ] L5 Sealed (client-encrypted) member-private entries
- [ ] L6 Web push for the PWA

---

## Log

Newest last. Format: `YYYY-MM-DD agent — what happened (commit)`.

- 2026-09-23 claude-opus-5.5 — Q&A with owner (6 rounds); wrote spec set S1–S12 (SPEC, DECISIONS, DATA_MODEL, SYNC, NOTIFICATIONS, API, DESIGN, CLIENTS, OPS, OPEN_QUESTIONS, README). Spec phase done.
- 2026-09-23 claude-opus-5.5 — Owner answered open questions Q1–Q5, Q7–Q12 → D-045…D-053; spec docs updated; new board tasks M5.9–M5.12, M9.4, M12.3. Q6 (custom emoji) still open.
- 2026-09-23 claude-opus-5.5 — Q6 answered: server-wide custom emoji, no stickers (D-054); docs + M5.13 added. No open questions remain.
- 2026-09-23 claude-opus-5.5 — M0.1 Cargo workspace (core + server crates, lints, release profile).
- 2026-09-23 claude-opus-5.5 — M1.1 ids, HLC, occurred_at correction (core: id.rs, hlc.rs, time.rs)
