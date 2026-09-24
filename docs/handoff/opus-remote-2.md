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
- R8 — CI (`.github/workflows/ci.yml`): the job-level `hashFiles()` is gone; runs on every push;
  Rust pinned to 1.98; the web job builds the wasm core (prebuilt `wasm-bindgen` 0.2.128 via
  `taiki-e/install-action`) then check, vitest, build and the bundle budgets; the rust job runs
  gen-tokens/projection-check/api-check first. `backup_cli`, `purge_cli` and the backup rotation
  unit test no longer need `CARGO_TARGET_DIR`. Green status reported below once seen.
- R9 — post search: `post_fts` filled from the post projection (`post_search` in `project.rs`'s
  `post` arm, bulk fill + drop/recreate in the rebuild), migration **0007** indexes existing
  posts; `GET /search/posts` (`posts::search`, cursor like messages, every hit through
  `readable_sql`, tokens need the new `read:posts` scope and see only their own posts); a
  Messages/Posts tab in `Search.svelte`. Test in `tests/posts.rs` (unreadable never matches,
  tags, paging, token scope, delete, rebuild).
- R10 — shareable feeds: `feeds.rs` with `GET /feeds` (own + shared with me) and
  `GET /feeds/{id}/items?limit=&cursor=`: the feed row is checked with `readable_sql` (feeds share
  like posts), results are the core filter over posts **the reader** can read, names resolve
  against the owner's members/groups/lists, dates in the owner's latest offset, at most 2 000
  posts looked at per request. A feed using `fronting:` evaluates only for its owner (400 for
  others) — new owner question **Q15** with that default. `JournalFeeds.svelte`: "Who can open
  it" (Only us / Our followers; locked private with `fronting:`) and a "Shared with you" list
  with paging. Test in `tests/posts.rs` (follower vs stranger vs private feed, name resolution,
  fronting, paging). Not yet looked at in a browser; R12's suite will cover it.
- R11 — REST reads for M7 (`api_journal.rs`): `GET /profiles/{member_id}` (`read:members`;
  member + relationships + stats, highlights only with `read:posts`), `GET /lists` and
  `GET /lists/{id}/timeline` (`read:posts`); `GET /posts` and `/posts/{id}` now take API tokens
  with `read:posts` (own account's posts and replies only; a device session still reads
  cross-account). No new scope beyond `read:posts`, which API.md already named. Tests in
  `tests/posts.rs`; API.md and `api-check.py` in step (68 routes).
- R13 — `Shared::shutdown()` (a `watch` flag): the webhook task and the notifier stop on it, and
  both now hold the state only weakly, so dropping every other handle also ends them; `serve`
  calls it on SIGTERM/Ctrl-C. `tests/common::release(state, dir)` shuts down, waits until the
  test holds the last handle (asserting it), then deletes the data dir strictly; used by the
  HTTP tests in `admin_health.rs`, `posts.rs` and `exports.rs` (`search.rs` left alone: M5.10
  territory; it can switch to `release` the same way).
- R12 — browser suite: `web/e2e/v1.spec.ts` (Playwright, `@playwright/test` pinned 1.56.1,
  DECISIONS §Versions) run by `scripts/e2e-web.sh` against a server on a temp data dir, and as
  the `e2e` CI job. Nine serial tests over three browser contexts: onboarding by invite +
  members + switch; the switch on a linked second device; a follower (Close preset) not seeing
  a switch before the 15 s settle and seeing it after (asserts ≥ 14 s); a DM; a shared space; a
  followers post the follower reacts to and replies to, both seen by the author; message and
  post search; a feed shared with followers; the CSP header and zero console errors/page errors
  across all pages (checked that a CSP violation does land there). Passes here in ~22 s.
  Handles carry a per-run suffix, so local Claude can run it against the real server:
  `web/e2e/README.md` has the command (`CHORUS_E2E_BASE`, `CHORUS_E2E_CLI`) and the cleanup.
  Found on the way: the author had no way to see other accounts' reactions to their posts; the
  thread view (`PostReplies.svelte`) now lists them from `GET /posts/{id}`.
- CI is **green** on `handoff/opus-remote-1` (run 35993624647 at `d50b33a`): rust 10 min with a
  warm cache (14 min cold; the release test step dominates), web < 1 min, e2e 3 min. Pushes in
  quick succession cancel older runs (`concurrency`), so the first rust cache was only saved once
  a run finished. `scripts/e2e-web.sh` also handles Git Bash on Windows (`.exe`, `cygpath`).
- R14 skipped: Q14 isn't answered.
