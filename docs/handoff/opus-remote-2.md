# Hand-off: remote Claude Opus 5.5, batch R2 (2026-09-24)

From: local Claude (on the owner's PC). To: the remote Claude that did batch R1.

Batch R1 is merged into `main` (merge `210886c`) and was very good: every change had a test,
the report said exactly what ran and what didn't, and the `REPLAYED_UPTO` fix was a real
correctness bug found while doing perf work. Two things from the audit:

- **Merge fix in `app.rs`:** `a_socket_over_its_frame_budget_is_closed` failed 2 runs in 3 on
  Windows. Closing a TCP socket with unread incoming data resets it there, and the client lost
  the `rate_limited` frame. `run_socket` now gets the sink back from the writer task, sends
  Close, and drains the stream for up to 2 s before dropping. Keep that if you touch the socket.
- `tests/common::http_test_dir` (gpt-6-sol's helper) now roots under `CARGO_TARGET_TMPDIR`, and
  your restore-window test uses it.

Everything in `opus-remote-1.md` §0–§1 still applies (setup, commit rules, no `PROGRESS.md`
edits, push after every commit), with one change: **keep pushing to `handoff/opus-remote-1`**
(your session could only push there last time; that's fine). It has been fast-forwarded to
`main`, so start with `git pull`. Report in §5 at the bottom of **this** file.

## The goal: v1

The owner asked for one full, stable build that works end to end on phone, web and desktop (the
installed PWA). After that, local Claude builds a GitHub Pages landing page. Split:

- **gpt-6-sol** (`docs/handoff/sol-batch-5.md`): Android parity, on the owner's phone.
- **local Claude:** M5.10 channel permissions (`ingest.rs`, `visibility.rs`, `spaces.rs`,
  `project.rs` permission projection, `search.rs`, `blobs.rs`), then the end-to-end v1 run on
  the owner's server with the phone and a desktop browser, Web Push, the landing page.
- **you:** CI, the server/web gaps below, and a browser end-to-end suite that makes the v1 run
  repeatable.

## Don't touch

`ingest.rs`, `visibility.rs`, `spaces.rs`, `search.rs`, `blobs.rs` (local Claude, M5.10);
`project.rs` except where R5 below says; anything under `android/`; `web/src/lib/data.ts`,
`MemberEditor.svelte`, `Profile.svelte` (Sol's V7); `deploy/`, `scripts/*.ps1`. Migrations:
take **0007** if you need one; Sol takes 0008.

## Tasks (in this order)

- **R8 · make CI run.** `.github/workflows/ci.yml` has never run a job: every push fails in 0 s
  with "workflow file issue". The job-level `if: ${{ hashFiles(...) }}` isn't allowed there;
  drop it. Then make both jobs pass on `ubuntu-latest`: pin Rust 1.98 (DECISIONS §Versions), not
  `stable`; the web job needs the wasm pkg (build `chorus-wasm` and run `wasm-bindgen-cli
  0.2.128`, cached); `backup_cli.rs` and `purge_cli.rs` `expect()` `CARGO_TARGET_DIR`, so move
  them to `CARGO_TARGET_TMPDIR` like the others; add `scripts/projection-check.py` and
  `scripts/api-check.py`. Keep it under ~15 min (rust-cache; `CHORUS_SIM_SEEDS` as now). Once it's
  green on your branch, say so in §5.
- **R9 · post search.** `post_fts` exists in `0001_init.sql` but nothing writes it (DATA_MODEL
  says so since Sol's B7). Fill it from the post projection (a new function in `project.rs`
  called from the `post` arm of `write`, plus the rebuild's bulk fill next to `message_fts`),
  `GET /search/posts` with the same cursor shape as `/search/messages` and every row through
  `posts::readable_sql`, and a Posts tab in the web `Search.svelte`. Tests: an unreadable post
  never matches; a rebuild keeps search working.
- **R10 · shareable feeds (M7.4).** SPEC §6.4: a feed has a visibility. A server endpoint that
  evaluates a shared feed definition for a reader over posts they can read (`readable_sql`, then
  the core filter `chorus_core::feed`), and in `JournalFeeds.svelte` a share control and a
  "feeds shared with me" list. Follower-visible only through the existing follow ceilings.
- **R11 · REST reads for M7 (M2.7).** Profiles, posts, lists and feeds for API tokens
  (`read:journal` or the scope API.md already names; check before inventing one). API.md and
  `api-check.py` stay in step.
- **R12 · web end-to-end suite.** Playwright (pin it; `web/e2e/`, a `scripts/e2e-web.sh`) against
  a server started on a temp data dir: onboarding by invite, a switch reaching a second device
  (two browser contexts), a follower seeing a switch only after its delay, a shared space and a
  DM between two accounts, a post with a reply and a reaction across accounts, search, and the
  CSP (no console errors). Run it in CI as a separate job. This is what local Claude will rerun
  against the real server before calling v1.
- **R13 · webhook task shutdown.** Sol found that `app::router`'s webhook task keeps the shared
  SQLite connection alive after the HTTP task ends (tests can't delete their data dir on
  Windows). Give `Shared` a shutdown signal the task watches; the tests then clean up at once.
- **R14 · export bundle (Q14)** only if `docs/OPEN_QUESTIONS.md` Q14 is marked answered by the
  owner when you get there; otherwise skip it.

## 5. Report (append below; newest last)

- 2026-09-24 local Claude — batch R2 written; `handoff/opus-remote-1` fast-forwarded to `main`.
