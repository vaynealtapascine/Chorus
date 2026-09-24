# Hand-off: remote Claude Opus 5.5, batch R1 (2026-09-24)

From: Claude Opus 5.5 working on the owner's PC ("local Claude").
To: a Claude Opus 5.5 agent working remotely, from the GitHub repo only.

You have the repository and nothing else: not the owner's PC, the F: drive, the phone, the
running Chorus service, WSL or the owner's servers. Everything below is doable from a Linux
machine with Rust, Node and Python. Read `AGENTS.md` first (read order, handoff protocol,
engineering rules); this file adds what is specific to working remotely.

## 0. How we exchange work

- This file lives on branch **`handoff/opus-remote-1`** (cut from `main` at the commit that
  added it). Create your working branch from it: `git switch -c opus-remote/batch-1`.
- **Push only to `opus-remote/batch-1`.** Never push to `main` or `sol/*`. Local Claude
  audits your branch and merges it (the owner asked for `main` to be pushed after each commit,
  so a merge publishes your work: keep secrets, real hostnames and personal data out).
- **Don't edit `PROGRESS.md`.** Three agents write to it and it would conflict on every merge.
  Instead, append to §5 *Report* at the bottom of this file: one line per finished piece
  (`R? — what, commit`), plus anything non-obvious. Local Claude moves it into PROGRESS on merge.
  Other docs (`docs/*.md`, `NOTES.md`, `DECISIONS.md`) you do edit, as AGENTS.md says.
- Atomic commits with the repo's prefixes (`feat(server):`, `fix(web):`, `perf(server):`,
  `test:`, `docs:`); tests land with the code. End each commit message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Push after every commit, so a stop for usage loses nothing. If you stop mid-task, commit what
  you have with a `wip:` prefix and write the next step in §5.

## 1. Setting up (Linux)

```bash
rustup toolchain install 1.98 && rustup default 1.98        # DECISIONS §Versions: Rust 1.98
rustup target add wasm32-unknown-unknown
cargo install wasm-bindgen-cli --version 0.2.128 --locked   # must equal the crate's version
# the web app's wasm core is git-ignored; this is scripts/build-web-core.ps1 in bash:
cargo build -p chorus-wasm --target wasm32-unknown-unknown --profile wasm
wasm-bindgen target/wasm32-unknown-unknown/wasm/chorus_wasm.wasm --out-dir web/src/lib/core/pkg --target web
(cd web && npm ci)                                          # Node 22
python3 scripts/verify.py --quick                           # must pass before every commit
```

- Rebuild the wasm package whenever you change `chorus-core` or `chorus-wasm`, before the web
  checks, or `npm run check` sees stale bindings.
- Android (`--android`) needs JDK 17 + the Android SDK/NDK; if you don't have them, skip it and
  don't touch Kotlin. None of your tasks need Android.
- `scripts/*.ps1` are the owner's Windows scripts; don't run or "port" them unless a task says so.

## 2. Where things stand (read these, don't redo them)

- `main` at hand-off: everything through the phone device checks (see the end of `PROGRESS.md`
  *Log*). SPEC §9 server budgets are all met and measured: ingest ~4 600 ops/s and rebuild 57 s
  at 1M ops (`crates/chorus-server/tests/perf.rs`, ignored), 5 000-op reconnect 1.3 s and switch
  fan-out ~1 ms (`tests/sync_e2e.rs` `sync_budgets`, ignored). `docs/NOTES.md` has the profiles.
- Recent server work you'll build on: `ratelimit.rs` (API rate limits), `reconcile.rs`
  (restore window, D-067), `purge.rs`, the prepare/write split and reader thread in
  `project.rs::rebuild_in`, migration `0005_op_message_ref`.
- **gpt-6-sol** is working on batch 4 on its own branch (`docs/handoff/sol-batch-4.md`, U1–U6):
  search paging and export locking (U1), channel permissions (U3, may add migration `0006`),
  journals M7 (U4), Android parity (U5). **Don't touch** what it owns: `search.rs`, `posts.rs`,
  `visibility.rs` beyond reading, `web/src/lib/data.ts`, `MemberEditor.svelte`,
  `Profile.svelte`, anything under `android/`, and don't add a migration numbered `0006` (use
  `0007` if you truly need one, and say so in §5).

## 3. Rules that bite

- `chorus-core` is pure (no IO, clocks or randomness except passed in); the web and Android run
  the same code through wasm/UniFFI.
- Op log is append-only; projections are derived. A rebuild must reproduce exactly what live
  ingest produced: `tests/backup_cli.rs` (restore verification) and `tests/projection.rs` check it.
- Privacy invariants (NOTIFICATIONS.md §5) and visibility rules (`visibility.rs`,
  `PUBLIC_MESSAGE_SQL`) are requirements. A follower never learns more through the API than
  through notifications.
- Pin any new dependency's version in DECISIONS §Versions, and prefer none.
- The UI is cozy and quiet: new controls go behind Advanced unless obviously Basic (DESIGN §6).
- Spec changes that alter visible behaviour need the owner: write them into
  `docs/OPEN_QUESTIONS.md` with a default, use the default, and mention it in §5.

## 4. Tasks, in this order

Each is independent; if one blocks, note it in §5 and go on.

### R1 · Rebuild margin (SPEC §9: 1M-op rebuild ≤ 60 s; now 57 s on the owner's PC)

The margin is thin. Profile at 1M (`CHORUS_PERF_OPS=1000000 cargo test --release -p
chorus-server --test perf -- --ignored --nocapture`, needs ~5 GB free; your hardware differs,
so report before/after on *your* machine). Last profile on the owner's PC: `message.send` 24 s,
commit ~10 s (about half WAL checkpoint), fronts 7 s (30 k switches, 0.23 ms each), search index
5 s, clear 1.3 s. Ideas, unverified:
- fronts: `append_front` per switch; see whether the per-switch SQL (interval upserts, daily
  rows) can batch during `REBUILDING` without changing results. NOTES explains why a single fold
  at the end is *not* allowed (review cards).
- search index: FTS5 bulk insert tuning (`automerge`, `crisismerge`, insert order), or building
  it on the reader connection's side is impossible (one writer), so measure options.
- commit: fewer dirty pages (e.g. `DELETE` vs recreating derived tables, see NOTES).
Acceptance: `verify.py --quick` passes; `tests/backup_cli.rs` restore verification passes;
at 1M your rebuild improves ≥ 10 % with ingest not worse; numbers in NOTES.md.

### R2 · The web app copes with `429 rate_limited`

The server now answers 429 with `Retry-After` (API.md §1). The web app calls `fetch` directly in
about a dozen places and shows a raw error. Add one small helper (e.g. `web/src/lib/http.ts`):
retries idempotent GET/HEAD after `Retry-After` (capped, once or twice), and turns a 429 on a
write into a friendly message. Use it at the call sites *except* in `data.ts`,
`MemberEditor.svelte` and `Profile.svelte` (Sol). Blob reads are exempt server-side, so leave
`AvatarImage`/`AttachmentView`/`EmojiImage` alone unless trivial. Vitest for the helper.

### R3 · Restore window in the admin view

`reconcile.rs` has the restore window (SYNC §7.3, D-067) but only a CLI. Add it to
`GET /api/v1/admin/health` (admins only; `health.rs`): `restore_window: {open, closes_at,
devices: [{account, name, platform, last_seen_at, back_at}]}`, plus
`POST /api/v1/admin/reconcile/close` (admin only, 204). Show it on the web "Your data" page's
admin section (`DataPage.svelte`) only while open: "Restore in progress — N of M devices back"
with a Close button and a one-line explanation. E2E test for the endpoint (non-admins get 403),
API.md and OPS.md updated.

### R4 · API.md can't drift from the router

Write `scripts/api-check.py`: collect every `.route("…", …)` path and method in
`crates/chorus-server/src/app.rs` (and nested routers) and fail if one isn't documented in
`docs/API.md`, listing the gaps. Wire it into `verify.py` like `gen-tokens.mjs --check`. Then
fix the gaps it finds (document, don't delete routes). Keep the parser simple and tested on the
real file.

### R5 · Socket-level limits for a public server

HTTP requests are rate limited; the sync WebSocket isn't. Add, with config defaults in
`config.rs` `Security` and docs in OPS §9:
- at most N concurrent sync sockets per account (default 20; oldest closed with a `Frame::Error`
  `too_many_connections`), and per client address before hello (default 30);
- a frame budget per socket (e.g. 200 frames/s burst, 50/s sustained; reuse the bucket logic in
  `ratelimit.rs`), closing with `rate_limited` when exceeded. Normal clients send a handful of
  frames per second; check `ClientEngine` in `chorus-core/src/sync.rs` so a big catch-up (the
  5 000-op reconnect test) stays well inside the budget.
E2E tests in `tests/sync_e2e.rs` for both, and `sync_budgets` still passing in release.

### R6 · (optional) A bash runner for the Linux container test

`scripts/test-linux.ps1` runs `deploy/linux/test/` in Docker under WSL. Write
`scripts/test-linux.sh` doing the same on a Linux host with Docker (build the bundle the way
`pack-linux.ps1` does, then the same container steps). Only if your machine has Docker; run
it and paste the result summary into §5. Don't change `install.sh` behaviour.

### R7 · (optional, ask first via OPEN_QUESTIONS) Export bundle with files

D-065 left "the DATA_MODEL §7 zip with blobs and `POST /exports` background jobs" for later.
If R1–R5 are done, write the design (not code) into `docs/OPEN_QUESTIONS.md` as a proposal:
format, where files come from, size limits, how a background job reports progress, and whether
a new dependency (a zip crate) is acceptable. The owner decides.

## 5. Report (append below; newest last)

- 2026-09-24 local Claude — hand-off written; branch `handoff/opus-remote-1` cut from `main`.
- 2026-09-24 remote Claude — working on branch `handoff/opus-remote-1` (this session may push
  only there, not to `opus-remote/batch-1`); merge from it. Tests that read `CARGO_TARGET_DIR`
  (posts, search, seed_cli, exports) need it set on Linux: `export CARGO_TARGET_DIR=$PWD/target`.
- R4 — `scripts/api-check.py` (run by `verify.py`): every `.route()` in `app.rs` (nested prefixes
  resolved) must appear with its method in API.md; self-test on a sample and the real router.
  It found 4 gaps, now documented: `GET /android/latest`, `GET /sync` (new §6a), and the two
  routes outside `/api/v1` (new §9). `/stream/ws` is marked planned (not served).
- R2 — `web/src/lib/http.ts` `apiFetch` (+ vitest): GET/HEAD 429s retried after `Retry-After`
  (twice, ≤ 10 s each), writes and exhausted reads throw a friendly `RateLimitedError`. Used by
  every REST call except `data.ts`, `MemberEditor`, `Profile` (Sol) and blob reads; blob uploads
  back off for `Retry-After`. Chat history and search now show `error.message`, not `Error: …`.
  CLIENTS.md §4.2 describes it. **For Sol:** `data.ts`, `MemberEditor.svelte` and
  `Profile.svelte` can switch `fetch` → `apiFetch` the same way.
- R3 — restore window in the admin view: `GET /admin/health` → `restore_window {open, closes_at,
  devices[{account, name, platform, last_seen_at, back_at}]}` (`health::restore_window`),
  `POST /admin/reconcile/close` (admin device sessions, 204); *Your data* shows "Restore in
  progress — N of M devices back" + Close (asks first while devices are missing) only while
  open. Tests in `tests/admin_health.rs` (401/403/204, devices, closed shape); those tests now
  use `CARGO_TARGET_TMPDIR`. API.md §8, OPS.md §4–5, SYNC.md §7.3 updated. Checked in Chromium
  (Playwright, light and dark, a served `web/dist`): "Restore in progress — 0 of 2 devices back",
  the confirm while devices are missing, gone after Close. The health card now reloads when the
  sync socket goes live, so the device you're on shows as back without a Refresh.
- R1 — rebuild at 1M on this box (4-core Xeon 2.1 GHz container, not the owner's PC): full test
  run **63.8 s → 43.3 s**; rebuild-only runs of a checkpointed copy (`CHORUS_PERF_DB`) **63–66 s
  → ~31 s**. Ingest 7 386–7 585 → 7 400 ops/s (not worse; switches 0.25 → 0.18 ms). Causes and
  fixes in NOTES.md: cross-thread frees (spent batches go back to the reader), FTS table
  recreated instead of DELETE, daily totals once per account, two uncached per-switch queries,
  message indexes dropped and rebuilt around the replay. `tests/projection.rs` gained a
  file-backed rebuild test (reader thread, schema identical after, search works) and a rebuild
  check in `in_order_switches_match_refolding`. **To check on the owner's PC**: the 1M test
  (`CHORUS_PERF_OPS=1000000 cargo test --release -p chorus-server --test perf -- --ignored
  --nocapture`); glibc's arena behaviour is Linux-specific, Windows' allocator may gain less.
- Fix found during R1: a rebuild refolded the front from the whole log (later ops included), so
  review cards could differ from live ingest when retracts/amends exist, and restore
  verification (it compares `front_review`) would refuse such a backup. The replay now folds the
  log up to the op being projected (`REPLAYED_UPTO`); `in_order_switches_match_refolding`
  compares all four front tables after a rebuild (it failed before the fix). NOTES.md has it.
- R5 — sync socket limits (`[security]` `sync_sockets_per_account` 20, `sync_sockets_per_address`
  30, `sync_frame_burst` 200, `sync_frames_per_second` 50; 0 = off): an account's 21st socket
  closes its oldest with `Frame::Error too_many_connections`; unsigned sockets per address past
  30 get `429 too_many_connections` at the upgrade (signing in or closing frees the slot); a
  socket over its frame budget (`ratelimit::Bucket`, now shared) gets `rate_limited` and is
  closed. Also fixed on the way: a socket closing removed its device's peer registration even
  when the device had already connected again (only the same channel is removed now). E2E tests
  for all three; `sync_budgets` in release: switch p95 0.98 ms, 5 000-op reconnect 1.1 s.
  OPS §3/§9, API.md §1/§6a, SYNC.md §6.2.
- R7 — design only: `docs/OPEN_QUESTIONS.md` Q14, the export bundle with files (zip layout,
  blobs from the account's own ops only, hand-written *stored* zip with no dependency vs the
  `zip` crate for deflate, disk guard and 24 h retention, `POST /exports` / `GET /jobs/{id}` /
  download with Range, an `export_job` table numbered after Sol's 0006). Nothing built; the
  owner decides.
- R6 — `scripts/test-linux.sh` (bash twin of `test-linux.ps1`: web build, static musl build in
  `rust:1.98-bookworm`, bundle like `pack-linux.ps1`, a seeded data dir snapshotted for the
  import, then the same `deploy/linux/test/` container steps; `--out`, `--no-build`, `--keep`).
  **Not run to the end**: this cloud container has Docker, but its egress policy blocks
  deb.debian.org (403), so neither the build image (musl-tools) nor the test VPS image (systemd)
  can install packages. The web build, seed and snapshot steps ran. Worth one run on a Linux box
  or WSL with open network before relying on it; `install.sh` is untouched.
