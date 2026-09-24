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
- **you:** CI, **M5.10 channel permissions** (moved to you: the owner says your limits are
  higher, so the long tasks are yours), the server/web gaps below, the export bundle, and a
  browser end-to-end suite that makes the v1 run repeatable.
- **local Claude:** audits and merges, the end-to-end v1 run on the owner's server with the
  phone and a desktop browser, Web Push in the owner's Chrome, deploys, the landing page. The
  offline PWA (server down) is done: CLIENTS.md §4.3.

## Don't touch

Anything under `android/`; `web/src/lib/data.ts`, `MemberEditor.svelte`, `Profile.svelte`
(Sol's V7); `web/src/lib/sync/blobs.ts` (local Claude's offline blob cache: use it, don't
rework it); `deploy/`, `scripts/*.ps1`. Migrations: take **0007** and up if you need them; Sol
takes 0008 only if it asks first in its log, so check `migrations/` after each `git pull`.

## Tasks (in this order)

- **R8 · make CI run.** `.github/workflows/ci.yml` has never run a job: every push fails in 0 s
  with "workflow file issue". The job-level `if: ${{ hashFiles(...) }}` isn't allowed there;
  drop it. Then make both jobs pass on `ubuntu-latest`: pin Rust 1.98 (DECISIONS §Versions), not
  `stable`; the web job needs the wasm pkg (build `chorus-wasm` and run `wasm-bindgen-cli
  0.2.128`, cached); `backup_cli.rs` and `purge_cli.rs` `expect()` `CARGO_TARGET_DIR`, so move
  them to `CARGO_TARGET_TMPDIR` like the others; add `scripts/projection-check.py` and
  `scripts/api-check.py`. Keep it under ~15 min (rust-cache; `CHORUS_SIM_SEEDS` as now). Once it's
  green on your branch, say so in §5.
- **R9 · M5.10 channel permissions** (was Sol's U3, then local Claude's; D-047, DATA_MODEL
  `channel_permission`, `space.roles`). Roles and per-role/per-account allow/deny overrides in
  shared spaces, including sharing one internal channel with outside accounts. Today
  `scope_access` grants a whole space, so enforce in every path at once or you leave a hole:
  writes (`ingest::can_write`), sync fan-out and catch-up (`visibility::op_visible_to` and the
  batched `visible_digest`, which must still equal applying `op_visible_to` per op), search,
  blobs, the REST message reads and webhooks. One rule in one place, like `PUBLIC_MESSAGE_SQL`.
  The projection (`channel.set_permission` → `channel_permission` rows) is already in
  `project.rs::special`. Two-account e2e tests like `friends_share_spaces_and_dms`, a property
  test that no op leaks to an account the rule denies, and the web UI (a channel's permission
  editor in shared spaces; Advanced). Write the rule into DATA_MODEL/SYNC as you go.
- **R10 · post search.** `post_fts` exists in `0001_init.sql` but nothing writes it (DATA_MODEL
  says so since Sol's B7). Fill it from the post projection (a new function in `project.rs`
  called from the `post` arm of `write`, plus the rebuild's bulk fill next to `message_fts`),
  `GET /search/posts` with the same cursor shape as `/search/messages` and every row through
  `posts::readable_sql`, and a Posts tab in the web `Search.svelte`. Tests: an unreadable post
  never matches; a rebuild keeps search working.
- **R11 · shareable feeds (M7.4).** SPEC §6.4: a feed has a visibility. A server endpoint that
  evaluates a shared feed definition for a reader over posts they can read (`readable_sql`, then
  the core filter `chorus_core::feed`), and in `JournalFeeds.svelte` a share control and a
  "feeds shared with me" list. Follower-visible only through the existing follow ceilings.
- **R12 · REST reads for M7 (M2.7).** Profiles, posts, lists and feeds for API tokens
  (`read:journal` or the scope API.md already names; check before inventing one). API.md and
  `api-check.py` stay in step.
- **R13 · web end-to-end suite.** Playwright (pin it; `web/e2e/`, a `scripts/e2e-web.sh`) against
  a server started on a temp data dir: onboarding by invite, a switch reaching a second device
  (two browser contexts), a follower seeing a switch only after its delay, a shared space and a
  DM between two accounts, a post with a reply and a reaction across accounts, search, and the
  CSP (no console errors). Run it in CI as a separate job. This is what local Claude will rerun
  against the real server before calling v1.
- **R14 · webhook task shutdown.** Sol found that `app::router`'s webhook task keeps the shared
  SQLite connection alive after the HTTP task ends (tests can't delete their data dir on
  Windows). Give `Shared` a shutdown signal the task watches; the tests then clean up at once.
- **R15 · export bundle (D-068, approved for v1).** Build the design in the appendix below with
  its defaults (the owner approved the feature; the sub-choices stay as proposed). Migration
  0007 (or the next free number) for `export_job`. The web *Your data* page gets "Prepare a full
  export" with progress and a download link; Android can come later (Sol).

- **R16 · push endpoints get the webhook target rules (security).** `push::register` accepts
  any `http(s)://` endpoint, so on a public server any signed-in device can make the server POST
  (encrypted, blind) to internal addresses: SSRF, like webhooks before `security.webhook_targets`.
  `POST /devices/push/test` (local Claude, 2026-09-24) makes it triggerable on demand, rate
  limited. Apply the same rule as webhooks (`webhooks.rs`: resolve, check against
  `webhook_targets`, pin the checked address, no redirects) at register *and* send time, https
  only outside `any`; keep the owner's setup working (ntfy at `https://ntfy.vayne.garden`, a
  tailnet name) and the loopback test in `sync_e2e.rs` (`webhook_targets = any`). Do this right
  after R9.

## Appendix: export bundle design (Q14 → D-068) — the DATA_MODEL §7 "full backup" zip, as a background job

**What's in it.** `chorus-<handle>-<YYYYMMDD>.zip`:

```
manifest.json        format 1; account {id, handle, kind}; created_at; server version; instance_id;
                     ops {count, sha256}; blobs [{hash, size, mime, filenames: […]}]; csv [names]
ops.jsonl            exactly GET /exports/ops.jsonl (the account's authored ops, seq order)
csv/<name>.csv       the seven tidy tables of DATA_MODEL §7.1
blobs/<sha256>       every file the account's own ops point at
README.txt           three lines: what this is, that blobs/ is named by hash (manifest has names)
```

The SQLite copy stays its own download (it's derived: a rebuild of `ops.jsonl`). Blobs are named
by hash, not filename: two attachments may share a name, and the manifest keeps every filename a
blob was sent under, so a reader can restore names.

**Where the files come from.** The blob store (`data/blobs/`, content-addressed, D-064's pool
for backups), chosen from the **account's own ops**, the same set as `ops.jsonl`: `attachment.*`
ops' `blob_hash`/`thumb_blob_hash`, member/group/system `avatar_blob`/`banner_blob` in its
`.create`/`.set` ops, and custom emoji it created. Not other accounts' files, even ones it can
see in a shared space: those are theirs to export (same line as D-066 and the direct exports).
Each blob is checked against its hash while copied; a missing or damaged one is listed in the
manifest (`missing: […]`) instead of failing the whole export.

**Format and dependency.** A ZIP with *stored* entries (no compression) can be written in ~150
lines with no dependency: local headers, a CRC-32 per entry (a table-driven function), the
central directory, and ZIP64 records past 4 GB or 65 535 entries. Media files don't compress
anyway; `ops.jsonl` and the CSVs would (roughly 5–10× smaller), which is the only reason to take
the `zip` crate (2.x, pure Rust with `flate2`/`miniz_oxide` for deflate). Tar is simpler still
but Windows users open zips natively, so the proposal is a hand-written stored zip, tested by
reading it back with Python's `zipfile` and `unzip -t` in a test. The owner decides whether
compression is worth a dependency.

**Size.** No cap on what an account may export, but the server protects its disk: the job
streams straight into `data/exports/<job id>.zip.part` (never in memory), first estimating the
size (op bytes + blob sizes) and refusing with `too_large` if free space would fall below twice
that. One running job per account, two at a time server-wide (others queue). A finished file
is kept 24 h (or until an hour after its first full download), then deleted; an export is not a
backup (OPS §5 is).

**The job protocol** (API.md §4 already names it):

```
POST   /exports {kind: "full"}          → 202 {id}      (sessions, or tokens with `export`)
GET    /jobs/{id}                       → {id, kind, status: queued|running|done|failed,
                                           phase: "ops"|"csv"|"blobs"|"zip", done, total,
                                           bytes, error, result_url, expires_at}
GET    /exports/{id}/download           the zip; Range supported so a phone can resume
DELETE /jobs/{id}                       cancel, or delete the finished file now
```

Jobs live in a table (`export_job`, a migration numbered after Sol's `0006`) so a restart marks
running ones `failed` ("server restarted; start it again") instead of losing them. Progress is
counted in items (ops, then CSV tables, then blobs) and bytes; the web app polls `GET /jobs/{id}`
every 2 s while its Data page is open, and a finished export also becomes an in-app notification
(`kind: "export_ready"`), not a push: it's the account's own action, seconds to minutes long.

**Round trip.** The manifest and `ops.jsonl` carry everything an importer needs
(`chorus-server import-account --from <zip>`, later): ops keep their ids, authors and times,
blobs are verified by hash. The importer is separate work and not part of this proposal.

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
- 2026-09-24 local Claude — merged R8 + post search into `main` (audited; post search looks
  right). **Renumbered while you worked:** the owner moved M5.10 channel permissions to you
  (higher limits) and approved the export bundle, so the list above is now R9 = M5.10 (next),
  R10 = post search (done, the item you called R9), R11–R15 as listed. The Q14 design is now the
  appendix above; D-068 records the approval. `web/src/lib/sync/blobs.ts` is new (offline blob
  cache) — don't rework it.
- R10 — shareable feeds: `feeds.rs` with `GET /feeds` (own + shared with me) and
  `GET /feeds/{id}/items?limit=&cursor=`: the feed row is checked with `readable_sql` (feeds share
  like posts), results are the core filter over posts **the reader** can read, names resolve
  against the owner's members/groups/lists, dates in the owner's latest offset, at most 2 000
  posts looked at per request. A feed using `fronting:` evaluates only for its owner (400 for
  others) — new owner question **Q15** with that default. `JournalFeeds.svelte`: "Who can open
  it" (Only us / Our followers; locked private with `fronting:`) and a "Shared with you" list
  with paging. Test in `tests/posts.rs` (follower vs stranger vs private feed, name resolution,
  fronting, paging). Not yet looked at in a browser; R12's suite will cover it.
- 2026-09-24 local Claude — merged shareable feeds too (= R11 in the renumbered list; Q15 kept
  with your default). **Next for you: R9, M5.10 channel permissions**, then R12–R15. Pull `main`
  (fast-forwarded onto your branch) before you start.
