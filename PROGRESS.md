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

- **In progress:** (owner, 2026-09-25) no release until the owner's UI redesign lands (Figma, from docs/FLOWS.md + ENTITIES.md); until then features + a robust core and messaging/channels. **Remote Claude**: batch R3 (`docs/handoff/opus-remote-3.md`: SYNC §9.3 chaos test, simulator with spaces/channels, SPEC §5 chat semantics, windowed replica, chat-path budgets). **Claude (local)**: op payload validation hardening + fuzzing, audits/merges. **gpt-6-sol**: Android (`docs/handoff/sol-batch-5.md`). Chorus Home paused until the designs.
- **Owner:** deploy `main` to the PC service: `pwsh scripts/deploy.ps1 -Android` (the 2026-09-24 attempt failed on Java 8 from JAVA_HOME after copying only the web app; fixed in 63d1481/9bcc709, so rerun it). The server backs the database up to `backups/pre-migrate-<time>.db` and then applies migrations 0005 (an index) and 0006 (fills in reply/repost links). Behaviour changes to expect: API requests are rate limited (50 burst, 10/s per token; `security.rate_burst`/`rate_per_second` in chorus.toml), the web app is served with a strict CSP, an open restore window now closes by itself 7 days after a restore (`chorus-server reconcile-status` shows it), and blobs are no longer cacheable by shared caches. Phone: Battery Saver is off (2026-09-24); the owner is using the phone, so agree device time before adb work. Chrome notifications are allowed for chorus.vayne.garden: after the deploy, test Web Push from the People page opt-in.
- **Next concrete step:** see Notes
- **Notes:** State 2026-09-23 (end of Claude session 2). Since gpt-6-sol's turn: audited + finished M5.12 Trash (fixed early out-of-order restores being rejected permanently); Android quick-switch widget + search launcher (RemoteViews, D-058; built + unit-tested, **not yet run on a device** — the phone dropped off adb); follows end to end (M6.1: server-written requests/prefs, People page with presets; per-member "followers hear when X fronts"); switch notifications core (`chorus_core::notify`) + server scheduler (`notifier.rs`) + follower view/inbox on the web — verified in the browser that nothing is revealed before due. **C: has <1 GB free: always `CARGO_TARGET_DIR=F:\DunBuild\chorus-target` and `CHORUS_GRADLE_BUILD_DIR=F:\DunBuild\chorus-gradle`** (deploy/web/android scripts honour them now; the dev-server launch config runs the F: release exe, so stop it before `cargo build --release`). The stale `Chorus\target` on C: was cargo-cleaned on 2026-09-23 (C: had hit 0 bytes free), and deleted again later that day after verify runs had refilled it to 12.7 GB; `verify.py` now defaults `CARGO_TARGET_DIR` to `F:\DunBuild\chorus-target` when unset. Keep building on F:. Owner reran install.cmd on 2026-09-23; the Chorus service is running again. Session 3 (same day) added: webhooks, more REST reads, write:front tokens + POST /front/switch, Web Push (D-061, owner question Q13), shared spaces + DMs (M6.2 server + web), QR device invites, device sign-out — see the log. Suggested next: device check of M4.2/M4.5/M4.6 when the phone is on adb; M6.2 on Android; a real-browser Web Push check once Q13 is answered; M9 stage mode; M7 journals.

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
- [x] M0.3 `android/` Kotlin + Compose skeleton (Gradle version catalog, minSdk 29, targetSdk 36)
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
- [x] M2.5 Sync WebSocket (hello/push/pull/ack/snapshot/hash) per SYNC.md
- [x] M2.6 Blob store (content-addressed, resumable upload) — gpt-6-sol batch 2 T1 (merged 18e4d6e)
- [~] M2.7 Read API (REST) + follower views — /me, /members(/{id}), /groups, /fields, /states, /front, /front/switches|intervals|daily|reviews, /accounts/{id}/view done; channel/message/thread reads and POST /channels/{id}/messages (write:messages) done 2026-09-24; profiles/feeds wait for M7
- [x] M2.8 Nightly backups + `chorus-server backup|restore|rebuild|export` CLI — batch 3 T6 (snapshot directories, D-064)

### M3 — Web client foundation

- [x] M3.1 Design tokens + base components (DESIGN.md)
- [x] M3.2 Local store (IndexedDB) + outbox + sync engine (via core wasm)
- [x] M3.3 Onboarding (invite → device key), system setup, terminology
- [x] M3.4 Members list/grid, groups tree, member editor, custom fields
- [x] M3.5 Front card, switcher, front history timeline, review cards

### M4 — Android foundation

- [x] M4.1 Theme + components mirroring DESIGN.md
- [x] M4.2 Room (SQLCipher) + outbox + sync engine (via core UniFFI) + WorkManager — device-checked 2026-09-24 (Galaxy A56 against the dev server over `adb reverse`): enrol, switch → server ~0.4 s, web switch → phone live, switch while the server was down synced 1 s after it came back
- [~] M4.3 Onboarding via invite link / QR — the web "Link another device" now shows a QR of the one-use link (server qr.rs, decoded with OpenCV); in-app camera scanning not needed while the phone camera opens the link
- [~] M4.4 Members, groups, switcher, front history — Android switcher sheet, History undo/redo, device linking (batch 3 T11); person-account hiding and full parity in batch 4 U5
- [~] M4.5 Quick-switch widget (RemoteViews, D-058): recent grid, folders, mode chip, undo — built + unit-tested; needs a device check; pins not done
- [~] M4.6 Search launcher activity + app shortcuts — SearchActivity + static "Switch…" shortcut; dynamic pinned shortcuts not done; device-checked 2026-09-24 (opens over the home screen in ~110 ms, filters, Enter switches and closes)

### M5 — Chat (internal space)

- [x] M5.1 Channels, categories, member DMs
- [x] M5.2 Composer: speaker chip, proxy tags, sigils, multi-author, formatting
- [x] M5.3 Replies (incl. cross-channel), quotes (full/partial), forwards, edits+history, deletes, pins
- [x] M5.4 Reactions, mentions, read states, unread badges
- [x] M5.5 Threads
- [x] M5.6 Attachments + images (offline-queued upload) — web (batch 2 T2); Android display not yet
- [x] M5.7 Hidden messages: spoilers, CW/collapsed, member-visible, system-only — batch 3 T8 (server filters + web); threads inherit their parent (B1 fix); Android controls in batch 4 U5
- [~] M5.8 Search (FTS5 server, local search on Android) — server FTS + web local index (batch 3 T9); tokens own-account only (B2); Android local search and paging (batch 4 U1) not yet
- [x] M5.9 Segmented messages (newline annotations) — parse, store `message_segment`, render
- [x] M5.10 Channel permissions (roles + overrides) incl. sharing one internal channel outward — remote Claude R9: one rule `perms.rs` on every path, property-tested; web editor (merged 2026-09-25)
- [x] M5.11 Forward/quote a selection (range or multi-message bundle)
- [x] M5.12 Trash + restore for messages, posts, members, groups, channels
- [x] M5.13 Custom emoji: server-wide set, upload/crop, picker + `:name:` autocomplete, reactions (D-054) — web (batch 2 T4)

### M6 — Sharing

- [x] M6.1 Person accounts, follows, privacy buckets — follows (Claude) + buckets, account default ceiling, per-member bucket announce (batch 2 T5)
- [x] M6.2 Shared spaces + DMs between accounts — server (spaces.rs: create/add/leave/authors, connected accounts only) + web (spaces rail, DM from People, new shared space, author cards) done; Android (gpt-6-sol V2, merged 2026-09-25) done; roles/permissions M5.10 done
- [x] M6.3 Follower views (delayed/fuzzed front state per NOTIFICATIONS.md §5) — server reveal + GET /accounts/{id}/view + web People page; shared history and whole-day stats from the reveal-time log (share_history / share_stats, migration 0003)

### M7 — Profiles & journals

- [~] M7.1 Profile page (banner, fields, pinned, stats) — member profile with Posts/Replies tabs (batch 3 T12); banner, pinned post and stats (batch 4 U4, merged 2026-09-24)
- [~] M7.2 Posts (notes) + long-form entries, replies/quotes/reposts/reactions — web composer, audiences, GET /posts with per-read audience checks (batch 3); cross-account reactions and replies (readable-parent check) merged 2026-09-24; reposts/quotes UI not yet
- [~] M7.3 Highlights, relationships + relationship types — local highlights curation and profile relationships on the web (batch 4 U4, merged 2026-09-24)
- [x] M7.4 Lists, feeds (filter language), sharing feeds — private member lists and saved feeds with the core filter (wasm `feedFilter`) on the web, over the local replica (batch 4 U4, merged 2026-09-24); sharing feeds needs a server endpoint with `posts::readable_sql` Shareable feeds incl. fronting feeds (D-069, remote R11/R17) merged 2026-09-25.
- [x] M7.5 Combined system timeline — web Journal (batch 3 T12)

### M8 — Notifications

- [~] M8.1 ntfy/UnifiedPush plumbing (server publisher, Android distributor registration) — server encrypt+send and Android connector/decrypt/notification done (D-059); device check 2026-09-24: registration works (fixed: a fresh sign-in didn't register until the next app start), the endpoint was on ntfy.sh (the ntfy app's default); delivery untested because the phone's DNS was down (no host resolved, IP fine)
- [~] M8.2 Rule resolution: per-switch × per-member × system ceiling × recipient prefs — core + server scheduler done (notifier.rs: queue on front change, reveal/deliver loop, GET /notifications); push delivery is M8.1
- [x] M8.3 Random delay, time fuzzing, supersede/collapse, digests, quiet hours — core rules + property tests; server scheduler with digests (one summary per follower/account), quiet hours (follower's own time zone via prefs.tz_offset_min), collapse/sequence; §5 invariant test over random sequences
- [~] M8.4 Chat notifications (mentions, DMs, replies), inline reply — cross-account mentions/replies/DMs queued on ingest, inbox + push (activity.rs); per-channel level (all/mentions/none) + per-kind switches as account prefs, web controls; own internal space: member mentions, @front and member DMs under per-member rules (always/fronting/never), push skips the writing device; opt-in own-switch pings after the settle (undo stays quiet); Android inline reply built + unit-tested (needs a device check), including Advanced "reply as mentioned member" (reply_as in the push)

### M9 — Stage (screenshot) mode

- [x] M9.1 Selection, context-only/hidden, crop reply chains (web; core `stage::plan`; reply bars only point at items on stage; `reply_depth` crops chains to N levels)
- [x] M9.2 Style presets + redaction (web: 6 styles, theme, width, redacted names, blurred avatars, hide header/reply bars)
- [x] M9.3 Saved stages (web)
- [x] M9.4 Fake names and timestamps (view-only overrides) (web; Android renderer not yet)

### M10 — Data

- [x] M10.1 Insights dashboards — web (batch 3 T10), DST-aware days
- [x] M10.2 API tokens, SSE stream, webhooks — tokens + webhooks on the web "Your data" page, front/members reads, SSE front stream, OBS overlay; message.created/post.created webhook events for the account's own messages and posts (2026-09-24)
- [x] M10.3 Exports (JSONL op log, tidy CSVs, SQLite copy), documented views — batch 3 T7 (direct downloads, D-065); full export zip with files as a background job (D-068, remote R15, merged 2026-09-25)

### M11 — Import

- [x] M11.1 PluralKit import (export file + API token)

### M12 — Ship

- [x] M12.1 Deploy scripts (NSSM service, Caddy snippet, ntfy)
- [~] M12.2 Disclosure card, screenshots, README, publish to GitHub — card (Disclosure Studio: claude-opus-5.5 + gpt-6-sol), fresh screenshots and README done; published 2026-09-24 at github.com/vaynealtapascine/Chorus (public; push main after each commit)
- [~] M12.3 In-app APK updates + silent OTA (CLIENTS.md §5a) — server endpoints, deploy -Android, app updater (D-060) done; needs a device check


### M13 · Chorus Home (D-071, docs/HOME.md)

- [x] M13.1 LAN TLS listener with a self-signed certificate; fingerprint in `/server` and LAN invites
- [x] M13.2 Loopback-only setup + *This computer* settings API (first account, port, wifi on/off, backups, restart)
- [x] M13.3 Windows service mode + self-install / update / uninstall (ChorusHome service, firewall, shortcut)
- [x] M13.4 Web setup page and settings
- [ ] M13.5 Android: pinned self-signed certificate from `#pin=` invites; mDNS rediscovery
- [x] M13.6 Release build (`ChorusHome-<version>.exe`) in CI, linked from the landing page
### Later (not v1)

- [ ] L1 Voice messages, video messages
- [ ] L2 Link embeds / previews
- [ ] L3 Render stage to PNG in-app
- [ ] L4 Quick Settings tile, Wear OS
- [ ] L5 Sealed (client-encrypted) member-private entries
- [x] L6 Web push for the PWA — server VAPID + service-worker push/click handlers + People-page opt-in (D-061); verified 2026-09-24 in the owner's real Chrome against the production server (test account `claudetest`, "Send a test" → shown by the service worker)

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
- 2026-09-23 claude-opus-5.5 — M2.5 app.rs: axum router (/server, /auth/redeem|challenge|session, /sync WS, optional static web dir), socket loop (hello/welcome/catch-up/caught, push/ack, pull, ping), fan-out + live scope changes under the db lock; serve CLI (dev prints an invite); tests/sync_e2e.rs drives ClientEngine against the real server
- 2026-09-23 claude-opus-5.5 — M0.3 android/: Gradle 8.14.3 + AGP 8.11 + Kotlin 2.0.21 + Compose; modules app/core-bridge/designsystem; generated Tokens.kt + ChorusTheme; debug APK builds; core-bridge JVM tests call the real Rust core through the generated UniFFI bindings (3/3). No emulator/device attached, so on-device launch not yet observed.
- 2026-09-23 claude-opus-5.5 — M3.2 web sync: WebReplica (core) + IndexedDB write-behind (persist.ts) + SyncClient (WS, backoff, online event, session renewal). Verified in the browser against chorus-server --dev: live sync, reload restore, offline edits pushed on reconnect.
- 2026-09-23 claude-opus-5.5 — M3.3 onboarding: invite link/code → WebCrypto P-256 device key (non-extractable) → redeem → live. Verified end to end.
- 2026-09-23 claude-opus-5.5 — M3.4 web: nav shell (hash router, bottom nav on phones), Members (fuzzy search, group/archived filters, subsystem/group management), MemberEditor (identity, colour, birthday, rich description via core markup, sigils w/ clash warning, proxy tags, group toggles, custom fields incl. adding fields, archive/Trash/restore, switch in). Verified in browser + server SQL.
- 2026-09-23 claude-opus-5.5 — M3.5 web: Switcher sheet (search, multi-select, level cycle, primary, order, subsystems, note, typed time via user_time, notify choice, switch out; Ctrl/Cmd+S), undo toast, History (24h/7d lanes, log with edit time/undo/redo), review cards (core computes reviews into the projection). Verified in browser except review cards (need two devices; core-tested).
- 2026-09-23 claude-opus-5.5 — M5.1 web chat: internal space + channel list + create channel, message list with author grouping (5 min), multi-author avatars, segment sub-rows, offline marker, RichText renderer (UTF-16 entity pieces, safe links, click-to-reveal spoilers)
- 2026-09-23 claude-opus-5.5 — M5.2 composer on core.compose: speaker chip (default primary fronter, picker override), live 'as …' preview, sigils/tags/joint/newline segments, @mentions via names resolver, Enter to send. Verified: server stores segments, authors, mentions, FTS.
- 2026-09-23 claude-opus-5.5 — M5.3 web: Message component + actions: reply (reply bar, cross-channel rendering), full/partial quote (selection), edit via markup round-trip (edited marker), delete→restore, pin/unpin + pinned panel, forward to any channel (snapshot). Verified in browser. Still to add: 'reply in another channel' picker and an edit-history viewer (revisions aren't in the client projection yet).
- 2026-09-23 claude-opus-5.5 — M5.4 web: reactions as current speaker (palette, toggle chips, element-set keys), read marks when visible (forward-only, no op loop; headless-verified), unread badges per channel. Mention inbox still to do.
- 2026-09-23 claude-opus-5.5 — M12.1 deploy: scripts/deploy.ps1 (release build + web + copy to ~/selfhost/chorus; binary swap w/o admin), deploy/install.ps1 (+cmd/check/README: chorus.toml, NSSM service, Caddy site, DNS hint, first invite). Deployed files to ~/selfhost/chorus; owner still needs to run install.cmd as admin + add DNS record.
- 2026-09-23 claude-opus-5.5 — M11.1 PluralKit import: core/import.rs (members w/ colour/birthday/pronouns/proxy tags/pk_id, groups + membership, switches as silent user-time front.switch, system name/tag), uuidv5 ids → re-import adds nothing; web 'Import from PluralKit…' with preview. E2E-verified. Avatars not imported yet (needs M2.6 blobs).
- 2026-09-23 gpt-6-sol — recovered Opus's uncommitted Android first pass and device-linking flow; regenerated UniFFI bridge, fixed invite deep-link handling and auth renewal, built debug APK, recorded M4.2 as WIP.
- 2026-09-23 gpt-6-sol — M4.2 continued: encrypted Room replica with Keystore key and checked plaintext migration; durable-before-send local writes; serial socket persistence; bounded foreground/WorkManager sync. APK, bridge JVM tests, Android lint/unit tests, and full Rust/web verifier pass; device test pending.
- 2026-09-23 gpt-6-sol — M4.2 follow-up: background WorkManager now acquires the socket lease before awaiting startup, so a fresh process can connect without an Activity. APK, Android lint/unit tests, and full verifier pass again.
- 2026-09-23 gpt-6-sol — M5.5 thread channels, parent reverse links, reply previews, and thread navigation/composer; server arrival-order/rebuild and 50k-reply web tests pass
- 2026-09-23 gpt-6-sol — M5.9 segmented-message Advanced toggle, per-segment avatars and editing with preserved UTF-16 offsets/authors; web and server projection tests pass
- 2026-09-23 gpt-6-sol — M5.11 range and multi-message quote/forward snapshots, destination picker, outward-share confirmation, UTF-16 clipping and projection tests
- 2026-09-23 claude-opus-5.5 — M5.12 M5.12 Trash (finished gpt-6-sol's WIP): restore limited to creator/latest deleter (core::restore, both servers), searchable web Trash + per-channel 'Show deleted'; fixed early out-of-order restores being rejected permanently; fixtures blessed for the new trash stamps
- 2026-09-23 claude-opus-5.5 — perf (SPEC §9): incremental projector (core/projector.rs) + store `touched` ids + projection deltas to the web; send-message cost on a 55k-op history 640 ms → 0.12 ms native; checked against the reference by a property test and inside the convergence simulator (3000 seeds).
- 2026-09-23 claude-opus-5.5 — audited + merged gpt-6-sol batch 2 (T1 blobs, T2 attachments, T3 avatars incl. PK + Android display, T4 custom emoji, T5 buckets); T6 backups was in progress, uncommitted, left in the worktree. Android now applies projection deltas too.
- 2026-09-23 claude-opus-5.5 — M10.2 webhooks (webhooks.rs): signed POSTs (HMAC-SHA256 of t.body) for front.switch/member.created/member.updated/follow.requested, test ping, retries 1m…12h then off with the reason, tailnet-only URLs (resolved on save and per delivery); web Your data section. E2E test + verified in the browser against a local receiver. Also freed C: (0 MB free blocked writes) by cargo-cleaning the stale Chorus\target (17 GB of build output).
- 2026-09-23 claude-opus-5.5 — M2.7 more reads (api_reads.rs): /me, /members/{id} (groups + field values), /groups, /fields, /states, /front/daily, /front/reviews; scope-checked for tokens. E2E test.
- 2026-09-23 claude-opus-5.5 — write:front tokens + POST /front/switch (api_writes.rs; ingest::op_as generalises server_op): entries by id or by member/group/state name, typed time, note, notify; attributed to token:<id>; web Your data scope + example. E2E test; verified in the browser (switch posted with a token appears live in History).
- 2026-09-23 claude-opus-5.5 — L6 Web Push (D-061, Q13): VAPID key in server_meta + GET /push/vapid, RFC 8292 header for web devices only, SW push/notificationclick handlers, web/src/lib/push.ts + People-page opt-in. Unit tests (JWT verifies, web-only VAPID) + SW handlers run in a Node harness; real browser push not tried (the pane has notifications denied).
- 2026-09-23 claude-opus-5.5 — M6.2 shared spaces + DMs: POST /spaces (shared|dm, one DM per pair, follow-connected accounts only), add (owner), leave (self-leave now revokes access), author cards for other accounts' members; web spaces rail + DM header, People 'Message' + 'Start a shared space'. E2E test (live scope grant, message delivery, cards, leave); verified in the browser (DM + Book club created, message sent). Sol's A1 note updated to share the author rule.
- 2026-09-23 claude-opus-5.5 — QR codes for device invites: dependency-free encoder (qr.rs, byte/M/v1–10, masks scored) → qr_svg on POST /devices/invite, shown in Link another device; samples decoded by OpenCV (scripts/qr-check.py) incl. versions 7–9. Shared-space hint about the chat side channel (NOTIFICATIONS §5.6).
- 2026-09-23 claude-opus-5.5 — Device sign-out: POST /devices/{id}/revoke (own account, not the calling device) ends sessions, drops the peer and closes its socket on the next frame; web 'Your devices' list with Sign out beside Link another device. E2E test; verified in the browser with a throwaway linked device.
- 2026-09-23 claude-opus-5.5 — M8.3 closed: follower quiet hours/digest time now use the follower's UTC offset (Prefs.tz_offset_min; the ceiling's stay in system time), and the NOTIFICATIONS §5 invariant test (random switch sequences × ceilings: ingest never changes follower surfaces, changes only in passes that handled something due, view shows a real past state ≥ settle+min delay old in order, hidden member never named). Verified the test fails on a 1-minute-early mutation.
- 2026-09-23 claude-opus-5.5 — Web quiet hours (DESIGN §6 Basic): one control under People → Following, saved into every follow's prefs with this device's UTC offset (re-saved when the offset changes, e.g. DST); merges the rest of each follow's prefs. Verified in the browser with a second person account (127.0.0.1 origin) following/accepted: on, reload, off.
- 2026-09-23 claude-opus-5.5 — D-003 implemented: person accounts get one is_self member at enrolment (and a start-up backfill for older ones); the web hides Members/History/front card/quick switch for a person (detected from the synced self member, so offline and old devices work) and chat speaks as the self member. Verified in the browser with a person account (127.0.0.1 origin): backfill, person home, DM as the self member, seen by name on the system side; live scope grant for a new shared space.
- 2026-09-23 claude-opus-5.5 — M6.3 done: follower_front_log (migration 0003, written only at reveal) feeds view.history (30 days, newest first, fuzzed times) and view.stats (share of revealed front time, whole days only, 5 % steps) when the ceiling shares them; web: follower card shows 'Most often' + 'Earlier', system toggles in Advanced sharing default (kept out of preset matching). §5 invariant test now covers both. Verified in the browser (person follows the system, Close preset: Moss now, Kai earlier). Note for Sol: use migration 0004+.
- 2026-09-23 claude-opus-5.5 — M8.4 settings: per-channel all/mentions/none (pref notify_channel:<id>, DMs default all) and per-kind switches (pref notify_chat) read by activity.rs; 'message' kind for plain chatter at level all; web: channel ⋯ menu + People 'Chat pings for'. Test + verified in the browser (Live check set to all, plain message from the person account reached stars' inbox). Also reviewed sol/batch-2 (B1–B7 in the batch-3 handoff) and, on the phone: build 1 was the M0.3 skeleton (sample data, never enrolled); installed 0.1.102 over it (same cert), sync + update workers run, widget provider and Switch… shortcut registered; enrolment pending the owner's choice.
- 2026-09-23 claude-opus-5.5 — M8.4 own-account chat pings: mentions/@front/member DMs in the internal space under per-member rules (pref notify_member:<id>), writing device skipped (push::prepare_except); member editor "Chat notifications"; test + browser check (Moss mentioning Kai reached the inbox)
- 2026-09-23 claude-opus-5.5 — M8.4 own switches from other devices (opt-in notify_chat.own_switch): settle-delayed, superseded, dropped on retract, text from the front at delivery; People checkbox; test + browser check ("Front changed on Browser: Rin")
- 2026-09-23 claude-opus-5.5 — M8.4 Android inline reply: RemoteInput action on chat notifications → queued message.send (reply_to) as the primary fronter; ReplyReceiver; ReplyTest; assembleDebug + unit tests pass, not run on a device yet
- 2026-09-23 claude-opus-5.5 — M3.1 + M4.1 ticked after an audit: design/tokens.json → tokens.css + base.css (web) and Tokens.kt + ChorusTheme (Android) have been in place since M0; verify.py now fails on token drift (gen-tokens.mjs --check)
- 2026-09-23 claude-opus-5.5 — web chat at scale (SPEC §9): only the newest 100 messages are rendered (older pages on scroll-up / button, scroll position kept); messages() cached per channel and patched from delta-touched keys; messageById no longer rebuilds the channel per reply; unread() stops at the read mark. data.perf.test.ts (PERF=1): 50k channel opens in ~90 ms, send ~33 ms (was ~90). Randomized incremental-vs-fresh test. Merge note for Sol's focusId jump in sol-batch-3.md
- 2026-09-24 claude-opus-5.5 — SPEC §9 server budgets measured (crates/chorus-server/tests/perf.rs, ignored): front and read-state projections made incremental (core front::append + review_of, model::read_best/read_effective; migration 0004_incremental_projections; project.rs fast path tested against refold-on-every-op). 100k ops: 327 → ~2 500 ops/s. Still short: 1M ops average 1 734 ops/s (member.set entity refold 8 ms), rebuild ~4–5× the 60 s budget (needs batch rebuild). verify.py builds on F: by default; C: target deleted (12.7 GB)
- 2026-09-24 claude-opus-5.5 — audited and merged gpt-6-sol batch 3 (cda38c8: backups, exports, hidden messages, search, insights, Android switcher, journals, T13); fixed B1/B2/B3/B6 on main (935d04c); accepted D-S2-1..3 as D-063..D-065; Linux/VPS deployment (e8db205, D-062) incl. webhook target policy and SIGTERM; batch 4 handoff for Sol (sol-batch-4.md)
- 2026-09-24 claude-opus-5.5 — SPEC §9 at 1M ops: ingest 4 465 ops/s (budget met); rebuild 89.8 s (was ~260 s; budget 60 s, next steps in NOTES). Fixed along the way: restore failed on time-dependent projections and on older-schema snapshots (ec96958, 68ffdf8); rebuild left stale FTS rows (c3c5676)
- 2026-09-24 claude-opus-5.5 — Linux deployment tested for real (owner OK'd WSL): static musl build in rust:1.98-bookworm (fixed: musl-gcc as linker made a dynamic PIE that segfaulted), install.sh on a Debian 12 systemd container (fixed: Caddy couldn't open its log created by validate as root), update rerun (clean SIGTERM stop), import of a Windows-made schema-3 snapshot (147 ops, migrated). scripts/test-linux.ps1 repeats it; logs are plain text off a terminal
- 2026-09-24 claude-opus-5.5 — merged gpt-6-sol batch 4 progress (b074b24; audit notes in sol-batch-4.md); D-066 privacy fix: follower prefs no longer sync to or list for the followed account (268f8d1); message.created/post.created webhooks; rebuild single-op path (1M rebuild 84 s)
- 2026-09-24 claude-opus-5.5 — public-server hardening: Caddy log redacts ?token= (tested on the VPS container), silent sync sockets closed after 15 s, ≤5 sign-in challenges per device; REST message API (channels, messages, threads, POST with write:messages) and message events on the SSE stream
- 2026-09-24 claude-opus-5.5 — `chorus-server purge --message|--op` (D-053's true erase): payloads -> {"purged":true} (core treats them as opaque), projections rebuilt, unused attachment files deleted, purge.log; CLI test on a real database
- 2026-09-24 claude-opus-5.5 — M9.1 finished: stage `reply_depth` crops reply chains to N levels (core + web Replies select). Reactions and thread previews are never drawn on stage, so those filters are already met.
- 2026-09-24 claude-opus-5.5 — Published: created public github.com/vaynealtapascine/Chorus (owner's request), pushed main (history scanned for secrets first), topics set. sol/batch-2 stays local.
- 2026-09-24 claude-opus-5.5 — SPEC §9 met at 1M ops: rebuild 84 → 57 s (budget 60): one thread-link pass at the end, log decoded and single-op entity rows prepared on a reader thread (second read-only connection), upsert SQL cached per column shape. Ingest 4 600 ops/s.
- 2026-09-24 claude-opus-5.5 — M8.4: Advanced "reply as mentioned member" — server adds `reply_as` to chat pushes when `notify_chat.reply_as_mentioned` is on; Android inline reply speaks as that member while active; Settings → Advanced toggle.
- 2026-09-24 claude-opus-5.5 — Public-server hardening: API rate limits (ratelimit.rs; API.md §1 numbers, now enforced): token bucket per token/session, else per client address (X-Forwarded-For trusted from loopback only); stricter sign-in bucket; 429 rate_limited + Retry-After; blob reads exempt; `security.rate_burst` / `rate_per_second`.
- 2026-09-24 claude-opus-5.5 — Linux harness fix: test/install.sh picked the bundle by name (commit hashes), so container runs between 09:50 and now installed an older build; now newest by time. Rerun: install, update, import and the new rate-limit checks through Caddy (20 sign-ins pass, then 429; spoofed X-Forwarded-For doesn't help) all pass.
- 2026-09-24 claude-opus-5.5 — SPEC §9 sync budgets measured (sync_e2e sync_budgets): switch → other device ~1 ms p95 (loopback), reconnect with 5 000 queued ops 11 s → 1.3 s after migration 0005 indexes op payload.message_id (late-send backfill scanned the scope per message).
- 2026-09-24 claude-opus-5.5 — Restore window (SYNC §7.3): `reconcile-status` / `reconcile-close` built (reconcile.rs); devices count as back at hello with the new epoch and an empty outbox; the window now closes by itself 7 days after a restore (D-067: an import on the VPS used to leave authorship-preserving restore pushes open forever); `check` and install.sh --import point at it.
- 2026-09-24 claude-opus-5.5 — Hardening: the server sends a strict CSP with the web app (checked in the browser pane against a scratch copy of data-dev: sign-in, live sync socket, blob/data images, all pages, no violations); blob responses are Cache-Control private + nosniff + CSP sandbox. (The pane itself can't register service workers, with or without the CSP.)
- 2026-09-24 claude-opus-5.5 — Phone device checks (Galaxy A56, debug build 0.1.204, against data-dev via `adb reverse tcp:5251 tcp:5251`; debug builds now allow cleartext to 127.0.0.1/localhost only): M4.2 sync and M4.6 search launcher pass; push registration fixed (Push.ensure after enrolment); the widget (needs placing on the owner's home screen) still to do.
- 2026-09-24 claude-opus-5.5 — Hand-off for a remote Claude Opus 5.5 (batch R1: rebuild margin, web 429 handling, restore window in admin health, API.md drift check, sync-socket limits; optional Linux test runner and export-bundle proposal) pushed as branch `handoff/opus-remote-1` (docs/handoff/opus-remote-1.md); its work comes back on `opus-remote/batch-1` for audit and merge.
- 2026-09-24 claude-opus-5.5 — Inline reply device-checked (debug-only `DebugPushReceiver` shows a plaintext push payload through the real notification code; adb-shell only via DUMP): the notification shows Reply, "reply as mentioned member" spoke as Kai, the queued reply synced as soon as the app could reach the server. Background sync waited while Battery Saver blocked the app's network (WorkManager keeps the job; OS behaviour). Found and fixed: messages' `reply_to_id` was never projected (migration 0006 backfills), so the REST API and exports lacked reply links. Real push delivery still waits for the phone's DNS (Tailscale can't reconnect under Battery Saver).
- 2026-09-24 claude-opus-5.5 — Projection drift: posts' `repost_of` was dropped too (same cause as messages' `reply_to`); both mapped, migration 0006 backfills both, and `scripts/projection-check.py` (in verify.py) now fails when a DATA_MODEL catalogue field has no SQL column and no listed handler.
- 2026-09-24 claude-opus-5.5 — Merged gpt-6-sol batch 4 up to 36c56ba (profile banner/pins/stats, highlights, relationships, member lists, custom feeds with the core filter, cross-account post replies with a readable-parent check, Android chat/attachment parsing). Audit: tests no longer need CARGO_TARGET_DIR; notes in sol-batch-4.md.
- 2026-09-24 claude-opus-5.5 — Merged the remote Claude's batch R1–R7 (`handoff/opus-remote-1`, report in its §5): rebuild at 1M 64 → ~31–43 s on Linux (index drop/rebuild, FTS recreate, daily totals once, frees on the allocating thread) and a real fix — a rebuild refolded the front from the whole log, so review cards could differ from live ingest (`REPLAYED_UPTO`); restore window in `/admin/health` + `POST /admin/reconcile/close` + Your data card; web `apiFetch` (429 retry for reads, friendly error for writes); `scripts/api-check.py` in verify; sync socket limits (per account 20, unsigned per address 30, frames 200 burst / 50/s); `scripts/test-linux.sh` (not run to the end: the cloud box can't reach deb.debian.org); Q14 export-bundle proposal. Merge fix: sync sockets now send Close and drain the client before dropping — on Windows, closing with unread data reset the connection and the client lost the `rate_limited` error frame (the test failed 2 in 3 here).
- 2026-09-24 claude-opus-5.5 — Merged gpt-6-sol up to 38c02de: Android chat composer (core `compose` for sigils/proxy tags/segments, CW, chosen members, asides), replies, on-demand image preview; HTTP tests clean their data dirs (`tests/common::http_test_dir`, now under `CARGO_TARGET_TMPDIR`); Feeds copy fix. Deploy script fixed twice today: it used Java 8 from JAVA_HOME, and it copied the web app before building Android, so a failed build left the live web ahead of the server; all builds now run before any copy. Owner deployed main 2026-09-24.
- 2026-09-24 claude-opus-5.5 — Offline PWA (the owner's "desktop" for v1, D-068): checked against a stopped server — the app loads, Home/Chat/Journal work, changes made offline sync on restart; added `sync/blobs.ts` (blob cache keyed by hash, uploads kept) so images show offline, and a persistent-storage request (CLIENTS §4.3). "Send a test" notification (`POST /devices/push/test`, People page) for checking Web Push/UnifiedPush without a switch. Merged the remote Claude's R8 (CI runs; first green `main` run 35992660252), post search (`/search/posts`, migration 0007) and shareable feeds (`/feeds`, Q15 opened with a private default). M5.10 and the export bundle moved to the remote Claude (owner: it has higher limits); R16 added: push endpoints need the webhook SSRF rules.
- 2026-09-24 claude-opus-5.5 — Web Push verified for real: a test system account `claudetest` (created with the owner's OK via `chorus-server invite` on the production server; its only device is the owner's Chrome profile) subscribed from the People page, and "Send a test" arrived through Chrome's push service and was shown by the service worker. Use this account for future production checks; never the owner's own.
- 2026-09-24 claude-opus-5.5 — M13.1 Chorus Home LAN TLS: [server] lan_listen serves the same app over TLS with a self-signed certificate made once in <data>/tls (rcgen, aws-lc provider named explicitly); handshakes on their own tasks with a 10 s deadline; tls_pin in GET /server; device invites get lan_url https://<lan ip>:<port>/i/<code>#pin=sha256/… and the QR encodes it; CLI invite prints it. tests/home_tls.rs.
- 2026-09-24 claude-opus-5.5 — M13.2 Chorus Home setup/settings API (home.rs): [server] home = true adds GET /home, POST /home/setup (first-run system invite, once), PUT /home/settings (admin; port, home wifi, backups; rewrites chorus.toml after checking it loads, then restarts in place with a fresh runtime; the TLS listener frees its port). Local-only = loopback peer and nothing forwarded; a technical install (behind Caddy) has no such routes. tests/home_setup.rs.
- 2026-09-24 claude-opus-5.5 — M13.4 Chorus Home web: first run needs no invite on the PC itself (Onboarding asks GET /home, redeems POST /home/setup's code), then This computer (#/computer, linked from More only on that PC): add a phone (QR of the chorus:// invite with the pin), settings (wifi on/off, daily backups, port under Advanced) that save, restart and reload. Browser-checked on a scratch Home server: setup, QR, save with wifi off (file rewritten, TLS port freed, page back).
- 2026-09-24 claude-opus-5.5 — M13.3 Chorus Home installer and service verified on a real Windows runner (home-windows.yml, run 36012245167): install as the ChorusHome service (automatic), both ports answer (TLS with the pinned certificate), update in place, clean stop via the service manager, uninstall --delete-data leaves no service, data, program folder, shortcut or firewall rule. Fixed on the way: cmd.exe needs the cleanup command raw.
- 2026-09-24 claude-opus-5.5 — M13.6 release-home.yml: a Windows runner builds the web app, bundles it into ChorusHome-<version>.exe (--features home-bundle), installs that exact file, checks the web app is served with its CSP, uninstalls; a v* tag attaches it to the GitHub release (not tagged yet: the owner's call). The landing page links it at v1. Android APK on releases waits for a signing key (Q16).
- 2026-09-24 claude-opus-5.5 — Chorus Home (D-071, docs/HOME.md) in a day: M13.1 LAN TLS with a pinned self-signed certificate, M13.2 setup/settings API (restart in place), M13.3 Windows install/update/uninstall + ChorusHome service (verified on a Windows runner), M13.4 web first run + This computer page (browser-checked), M13.6 release build. Left: M13.5's device check (the Android pinning is unit-tested; a phone run needs an un-enrolled app — ask the owner), and Q16 (a release signing key) before the APK can be published. Also today: docs/FLOWS.md (every user flow, for the owner's Figma pass) and docs/ENTITIES.md (every entity and property in plain types). Sol's V1 composer finished and device-checked on the phone (compact chat layout).
- 2026-09-25 claude-opus-5.5 — Merged remote batch R2 (R9–R19) into main by fast-forward after an audit and a full Windows run: workspace tests, web check + vitest + build, Android build + unit tests against the new core, and the 12-test browser suite (with an installed Chrome, `CHORUS_E2E_CHANNEL=chrome`). Fixed on the way: the zip tests picked the Store's python3 stub and read cp1252 output. Brings M5.10 channel permissions, push endpoint SSRF rules, post search, shareable (incl. fronting) feeds, REST journal reads, full export zip, keep-everything + open from a projection snapshot, swapped rebuild. Left for Sol: Android `Changes.removed`, open-from-snapshot, recheck/offline search (opus-remote-2.md §5).
- 2026-09-25 claude-opus-5.5 — Q16 → D-072: the Android release key lives in Infisical (`chorus-cec1`, `ANDROID_SIGNING_KEY`); `release-android.yml` signs on tags via OIDC (`github-ci-handler`), refuses a debug signature, publishes `Chorus-<tag>.apk`. `scripts/new-android-key.ps1` makes the key (owner runs it). Signing path checked locally with a throwaway key (deleted). Chorus Home work paused until the owner's designs (owner, 2026-09-25).
- 2026-09-25 claude-opus-5.5 — Merged gpt-6-sol V2 (Android shared spaces + DMs) and V4 slices (People tab, follow requests/accept/unfollow, per-follower Basic presets from core's `notify_preset` via a new FFI export). Audit: follower presence comes only from the server's filtered `/accounts/{id}/view`; Android build + unit tests pass after the merge. Sol's uncommitted journal work in its worktree is untouched.
- 2026-09-25 claude-opus-5.5 — D-072 verified: release-android.yml run 36031678867 (manual) built and signed the APK with the owner's key from Infisical (V2 signature, SHA-256 dfb8485a…fbac), kept as a workflow artifact. A `v*` tag will publish it.
