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

- **In progress:** M2.5
- **Owner:** claude-opus-5.5, 2026-09-23
- **Next concrete step:** sync WebSocket in crates/chorus-server/src/sync_ws.rs: hello/welcome/push/ack/ops/caught/pull/ping, fan-out to connected devices; axum app + serve command; e2e test with ClientEngine over a real socket
- **Notes:** Order change: doing M2 (server) before M0.3 (Android skeleton) so clients have something to sync with; M0.3 is still next after M2.5.

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
- [x] M0.2 `web/` Svelte 5 + Vite + TS skeleton (PWA plugin, strict TS)
- [ ] M0.3 `android/` Kotlin + Compose skeleton (Gradle version catalog, minSdk 29, targetSdk 36)
- [x] M0.4 `fixtures/` conformance vector format + runner in Rust (see SYNC.md §9)
- [x] M0.5 `scripts/verify` (fmt, clippy, tests, svelte-check, gradle lint/test) + CI workflow

### M1 — Core (pure Rust, no IO)

- [x] M1.1 IDs (UUIDv7), HLC type + string encoding, time types
- [x] M1.2 Op envelope + op catalogue types (serde, versioned)
- [x] M1.3 Front fold: switch/add/remove/update/retract/amend → snapshots → intervals
- [x] M1.4 LWW register + LWW element-set helpers, field-level merge
- [x] M1.5 Text model: plain text + entities; markup parser (Telegram parity) + serializer
- [x] M1.6 Speaker parsing: sigils (emoji), proxy tags, multi-author prefixes, newline segments (D-045)
- [x] M1.7 Feed filter language: parser → AST → evaluator
- [x] M1.8 Member colour contrast adjuster (WCAG AA both themes)
- [x] M1.9 Convergence simulator (N devices, random partitions, projection hash equality)
- [x] M1.10 Spike: UniFFI build for Android (arm64-v8a, x86_64) + wasm-bindgen build for web. Decide keep/fallback (DECISIONS D-040)

### M2 — Server

- [x] M2.1 Config (`chorus.toml`), SQLite open (WAL, pragmas), migrations
- [x] M2.2 Accounts, devices, invites, key-based auth, sessions
- [x] M2.3 Op ingestion: validate → permission → append → project (single writer task)
- [x] M2.4 Projections for all tables + rebuild-from-log command
- [~] M2.5 Sync WebSocket (hello/push/pull/ack/snapshot/hash) per SYNC.md
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
- 2026-09-23 claude-opus-5.5 — M1.2 op envelope, scope parsing, catalogue as data (core/src/op.rs), structural validation
- 2026-09-23 claude-opus-5.5 — M1.4 field-level LWW + LWW element set (core/src/lww.rs), order-independence proptest
- 2026-09-23 claude-opus-5.5 — M1.3 front fold (core/src/front.rs): switch/add/remove/update/retract/unretract/amend → switch rows, intervals, daily split, review detection; order-independence proptests
- 2026-09-23 claude-opus-5.5 — M1.5 rich text (core/src/text.rs): Telegram-parity entities (UTF-16), Chorus markup parser + serializer with \& separator; 20k-case round-trip proptest
- 2026-09-23 claude-opus-5.5 — M1.6 speaker parsing (core/src/speaker.rs): sigils, prefix tags, consecutive tags, suffix pairs, joint authors, newline segments, escapes, fences
- 2026-09-23 claude-opus-5.5 — M1.7 feed filter language (core/src/feed.rs): tokenizer, precedence parser with error positions, JSON AST, evaluator
- 2026-09-23 claude-opus-5.5 — M1.8 member colour adaptation (core/src/color.rs): OKLCH, WCAG contrast, name/ring/tint per theme; proptest over all colours
- 2026-09-23 claude-opus-5.5 — M1.9 reference projection (model.rs), sans-IO sync engine + MemServer (sync.rs), convergence simulator (tests/converge.rs): 3000 seeds green; found & fixed 3 protocol bugs (see NOTES)
- 2026-09-23 claude-opus-5.5 — M1.10 spike kept (D-056): chorus_core::api JSON layer; chorus-ffi (UniFFI) .so for arm64/x86_64 + Kotlin bindings; chorus-wasm runs in Node, 209 KB gz; build scripts
- 2026-09-23 claude-opus-5.5 — M0.4 48 language-neutral fixtures in fixtures/<area>/ (fn + JSON args + expect) + runner tests/fixtures.rs with bless mode; generator scripts/gen-fixtures.py
- 2026-09-23 claude-opus-5.5 — M0.5 rustfmt (max_width 120), scripts/verify.py (fmt, clippy -D warnings, tests, wasm build, web/android when present), .github/workflows/ci.yml
- 2026-09-23 claude-opus-5.5 — M0.2 web/ Svelte 5 + Vite 8 + TS 6 skeleton: design tokens (design/tokens.json → CSS/Kotlin via scripts/gen-tokens.mjs), wasm core loader + vitest, FrontCard + quick-switch demo verified in browser (switch works; member colours adapt per theme). Fixed PowerShell array-splat bug in build scripts.
- 2026-09-23 claude-opus-5.5 — M2.1 server config (chorus.toml w/ defaults, --dev), SQLite open+pragmas, forward-only migrations with pre-migrate backup, schema 0001, migrate/check CLI
- 2026-09-23 claude-opus-5.5 — M2.2 auth.rs: invites (hashed, TTL, uses), redeem → account (+internal space & #general as server ops) + device + session, sliding sessions, P-256 challenge/verify (raw or DER sigs), revoke; first account is admin; invite CLI
- 2026-09-23 claude-opus-5.5 — M2.3 ingest.rs accept (validate → scope/admin checks → time correction → insert → project hook) + server_op; tests: stamping/offset, idempotency, invalid, forbidden, server-scope admin-only, restore window
- 2026-09-23 claude-opus-5.5 — M2.4 project.rs: entity re-projection via core model, element sets, account front (switch/intervals/daily/reviews), specials (pins, follows, field values, prefs, permissions, roles, reviews, read states), group cycle guard, message authors/segments/mentions/FTS, space membership → scope access, rebuild CLI; tests/projection.rs SQL==model
