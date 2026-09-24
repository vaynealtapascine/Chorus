# Hand-off: remote Claude Opus 5.5, batch R3 (2026-09-25)

From: local Claude (on the owner's PC). To: the remote Claude that did batches R1 and R2.

R2 (R9–R19) is merged into `main` (fast-forward, then `c30f56b` fixed two Windows-only test
issues: the zip tests found the Microsoft Store `python3` stub, and read cp1252 output). It was
excellent. The rebuild re-measured on the owner's PC: 58.5 s at 1M ops, commit 0.4 s (NOTES.md,
2026-09-25): the replay itself is now the whole cost on Windows, so the 60 s budget holds by a
small margin. Keep that in mind if you touch `project.rs`.

`opus-remote-1.md` §0–§1 still apply (setup, commit rules, no `PROGRESS.md` edits, push after
every commit), and you keep pushing to **`handoff/opus-remote-1`** (fast-forwarded to `main`;
start with `git pull`). Report in §5 at the bottom of **this** file.

## The goal of this batch

The owner is redesigning the whole UI in Figma (`docs/FLOWS.md`, `docs/ENTITIES.md`) and will
not tag a release until that lands. Until then (owner, 2026-09-25): **"continue working on
features and making the underlying core and messaging/channels robust, so it's ready when the
visual overhaul lands."** So this batch is about the engine, not screens:

- Prove sync is robust with the real server under failure, across accounts, shared spaces and
  channel permissions (SYNC §9.3 was required and never built).
- Give every chat feature in SPEC §5 its semantics in core/server/API, so the new UI only has to
  draw them. Web UI for new things stays **plain** (tokens, simple layout, Advanced where
  DESIGN §6 says); the redesign replaces it.

Split: **you** R20–R24 below. **Local Claude**: validation hardening (op payload limits and
fuzzing of every op kind through core and ingest), audits and merges, Windows. **gpt-6-sol**:
Android (`sol-batch-5.md`).

## Don't touch

Anything under `android/` (Sol); `deploy/`, `scripts/*.ps1`; `crates/chorus-server/src/{home,
home_install,tls}.rs` (Chorus Home, paused until the designs); `web/src/lib/sync/blobs.ts`. Payload
validation in `chorus-core/src/model.rs` and `ingest.rs` is local Claude's this batch: if you
need a validation change, note it in §5 instead of editing it. Migrations: **0009** is Sol's if
asked for; take **0010** and up.

## Tasks (in this order)

- **R20 · the end-to-end chaos test (SYNC §9.3, required).** An integration test that runs the
  real server (the binary, so it can be killed mid-write: `kill -9`, not a graceful stop) with
  headless clients on the real `ClientEngine` (the Rust test client in `sync_e2e.rs` is a start).
  Seeded and deterministic in what it *does* (the timing is not): 3 accounts (two systems and a
  person), 6 devices, the internal spaces, a shared space, a DM, and random ops of every chat
  kind — channels created/renamed/archived/deleted/restored, `channel.set_permission` churn
  including a guest on one internal channel, threads, sends/edits/deletes/restores, forwards,
  reactions, pins, read marks, attachments with real blob uploads — plus front switches and
  follows so the follower views move too. Failures: socket drops mid-batch, duplicated pushes,
  devices offline for a while, the server killed and restarted, one restore from a backup taken
  mid-run (epoch bump, restore pushes). At quiescence assert what §9.2 asserts, per account:
  every device's replica equals what the server says that account may see (op ids and
  projection), digests equal, the outbox is empty, every rejected op has a reason, and **no
  device ever received an op its account was not allowed to see at the time it was sent**
  (record every `ops` frame; check against the rule's history, `perms.rs` + scope grants).
  Also: no scope is pulled more than a few times (repair loops), and bounded wall time. Default
  run short enough for CI (a few seeds, < 2 min); a long run by env var like `CHORUS_SIM_SEEDS`.
  Fix what it finds (each fix its own commit with a regression test), and write the harness up
  in SYNC §9.3.
- **R21 · the core simulator learns spaces and channels.** `chorus-core/tests/converge.rs` covers
  every merge kind of the account scope but none of the chat structure: add spaces (shared scope
  across accounts), channels and threads (`channel.*`, `parent_message_id`), `message.forward`,
  `attachment.*`, and `MemServer` scope grants/revocations (a device losing a scope, and the
  `Changes.removed` sweep you added in R9). If `MemServer` can't model channel permissions
  faithfully, model scope loss only and say so; R20 covers permissions for real.
- **R22 · chat semantics for SPEC §5 (core + server + API, plain web).** Each item: the rule in
  core where every client must agree, server enforcement where it's security, API.md, tests;
  Android notes for Sol in §5.
  1. **Edit history.** `message_revision` is projected; nothing reads it. `GET
     /messages/{id}/revisions` (same read rule as the message; tokens with `read:messages`) and
     a plain "edited" → history view on the web. Posts likewise if cheap (`post_revision`).
  2. **Search filters** (SPEC §5.3): `from:`, `in:`, `has:image|file|link`, `before:`/`after:`,
  `is:pinned`, parsed **once in core** (like the feed filter) so the server search, web local
     search and Android agree; server `/search/messages` applies them in SQL.
  3. **Mentions** `@group` (every member of the group), `@front` (the fronters when the message
     was *written*, `occurred_at`), `@account` in shared spaces: resolution in core from the
     entities, notifications in `activity.rs` for each, respecting channel `view` (a mention
     never notifies someone who can't view the channel).
  4. **Default speaker** (SPEC §5.2): the channel's sticky speaker and autoproxy mode (`off`,
     `front`, `latch`, `member`) as one pure core function the composers call; the fields on
     `channel.set` (check DATA_MODEL first; add them there if missing, as a D-entry).
  5. **Reply elsewhere / reply privately** (SPEC §5.3): a `reply_to` in another channel renders
     as a reference card only for readers who can view the original; the server read paths
     (`messages::one`, thread reads, search) must not leak the original's text through the
     reply to a reader who can't view it. Test it across accounts.
  6. **Per-member read state** (SPEC §5.3, Advanced "track reading per member"): check what
     `read.mark` carries today; if members aren't there, propose the payload as a D-entry with a
     default and build it.
  7. **Slow mode** needs an owner answer (offline-first makes it odd: a message written offline
     at 10:00 and synced at 12:00 — too soon or not?). Add it to OPEN_QUESTIONS with a default
     (proposal: enforced at ingest on the server's `received_at`, per account per channel,
     owner/admin/`manage` exempt, a refused send goes to *Sync issues* with the wait time) and
     build that default behind the channel setting.
  8. **Space roles.** Channel permissions (R9) apply per role, but nothing sends
     `space.set_role` or `space.set_roles`: no client can make someone an admin or `read_only`,
     or define a custom role. Add it (web: the space's settings, Advanced; owner/admin only;
     server rules already in `perms::write_denied`) with an e2e test. Note from local Claude's
     fuzzing (`0e22431`): the membership set used to count `space.set_role` as a leave, so a
     rebuild dropped such members; fixed, with a regression test in `projection.rs`.
- **R23 · windowed replica for browser tabs (SYNC §6.5, CLIENTS §4.3).** With "keep everything"
  off (a browser tab, not the installed app), messages older than the window are evicted
  locally and scrolling past it pages over REST. The hard part is the digest: an evicted op must
  not look like a missing op (repair loops) — decide how (e.g. the digest covers the account
  scope and the window's ops, or evicted ids are remembered compactly) and write it into SYNC.
  Measure a tab's IndexedDB size and open time before/after on a 100k-op account.
- **R24 · reconnect and open budgets on the chat path, at scale.** `sync_budgets` covers 5 000
  queued ops; add a device that was offline a week in a busy shared space (another account sent
  20 000 messages with edits/reactions/permission changes) and hold SPEC §9's 10 s; and the web
  open-from-snapshot path (R18) with 50k messages in one channel (the first screen ≤ 150 ms,
  `web/perf`). Fix what's slow.

## 5. Report (append below; newest last)

- 2026-09-25 local Claude — batch R3 written; `handoff/opus-remote-1` fast-forwarded to `main`.
- 2026-09-25 local Claude — validation hardening landed on `main` (`0e22431`): ingest refuses an
  op it can't project on its own (`unprocessable`) instead of failing the batch; `op::validate`
  checks values via `op::FIELD_RULES` (compared with the schema by a test) and per-kind rules;
  fuzz tests `tests/ingest_fuzz.rs` + `chorus-core/tests/payload_fuzz.rs` (DATA_MODEL §2.2).
  If a migration of yours adds a NOT NULL/JSON/IN constraint, add its rule, or
  `field_rules_match_the_schema` fails. R22 gained item 8 (space roles). Pull before starting.
