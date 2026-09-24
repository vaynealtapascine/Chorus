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

- **R17 · fronting feeds are shareable (D-069, the owner's answer to your Q15).** Replace the
  owner-only 400: evaluate `fronting:` for a reader against only what that reader's follow
  ceiling has revealed (the same data as their notifications and `/accounts/{id}/view`, never
  earlier or finer), and show a banner in `JournalFeeds.svelte` when sharing such a feed and when
  a reader opens one ("This feed shows who was fronting when these posts were written").
  Tests: a follower with a delay sees a post match only once the switch is revealed to them.
- **R18 · keep everything on this device (D-070), web + core.** Read D-070. A per-device
  setting (default on for the installed PWA), "Sync everything now" in *Your data*: re-check
  every scope's digest, fetch what's missing, optionally fill the offline blob cache
  (`sync/blobs.ts` `keepBlob`), progress and space used. Offline search in `search.ts` gains
  posts and switches. Scale: today `persist.ts` `load()` reads every op and
  `WebReplica.restore` holds them all in memory; measure open time at 10k/100k ops and make a
  100k-op device open in ≤ 2 s (e.g. persist the projection and load ops lazily). Sol does the
  Android side from your notes, so write the protocol/UX in CLIENTS.md §4.3 as you go.

- **R19 · rebuild on Windows (after R9/R16–R18, or whenever convenient).** Your R1 changes
  measured on the owner's PC: rebuild 58.7 s at 1M (was 57 s), because the rebuild's single
  commit takes 20 s on Windows (NOTES.md, 2026-09-24 "measured on the owner's Windows PC").
  Rebuild into a fresh file with journal off and synchronous off, verify it, then swap it in
  (the restore path already swaps directories safely), so no ~GB WAL is committed. Keep the
  rebuild's output byte-identical (`tests/projection.rs`). Local Claude will re-measure on the PC.

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
- (My labels above follow the list as it was when I started: my R11 = REST reads (now R12),
  R12 = the browser suite (now R13), R13 = webhook shutdown (now R14). My "R14 skipped" was
  before D-068 approved the export bundle; it's R15 in the current list.)
- R9 — channel permissions (M5.10, D-047). One rule, `chorus-server/src/perms.rs`
  (`can_sql` for SQL, `can` for Rust, `write_denied` for writes), written up in DATA_MODEL §4.4;
  every path goes through it: pushes (`ingest::accept` → `forbidden` with a reason), sync fan-out
  and catch-up (`op_visible_to`, and the batched `visible_digest` — now two passes over one
  `Lookups` trait, so it can't drift from the per-op check), REST message reads, search, blobs,
  author cards and notification recipients. Webhooks only ever carry the author's own messages,
  so nothing to do there. Guests: an account override allowing `view` to someone outside the
  space gives them the space's scope (`perms::refresh_guest`) but only that channel's ops;
  `GET /spaces` lists such spaces with `guest: true`. A permission change sends connected
  devices the scope's digest (`app.rs` `fan_out`), and a device that lost a channel sweeps it
  (a **core change**: repair used to loop forever when a device held ops it may no longer see;
  SYNC §6.5, `Changes.removed`). **Sol:** Android `Store.save` should delete the ids in
  `changes.removed` (web does in `persist.ts`); without it an evicted op comes back after a
  restart until the next repair sweeps it again — no loop, just wasted work.
  Tests: `tests/permissions.rs` (60 random spaces/roles/overrides/logs against an independent
  model of the rule; checks `can`, `op_visible_to`, the batched digest, `messages::one` and
  search — a mutation of the rule fails it), `sync_e2e.rs`
  `channel_permissions_through_the_real_server` (private channel, live gain and loss, a guest
  on one internal channel, send refused until allowed), core unit tests for the sweep, and a
  browser test sharing one internal channel with the follower. Fixtures in `activity`, `blobs`,
  `search`, `visibility_digest` needed real space/member rows (the rule rightly denied them).
  Web: `ChannelPermissions.svelte` in the channel ⋯ menu (Advanced; owner/admin, or a DM
  participant), per-target allow/deny/inherit, outsiders get a "shares just this channel" hint;
  guest spaces show as "{owner} · shared with you"; the new-channel field shows only to those
  who may add channels; the ⋯ menu scrolls now (it overflowed the screen).
- R16 — push endpoints get the webhook target rules. `push::check_endpoint` runs at `PUT
  /devices/push` (400 with the reason) and again in `push::send` before every POST (both the
  notifier and `/devices/push/test`); it shares `webhooks::resolve_checked` (resolve, every
  address must pass, pin the first) and `pinned_client` (no redirects, 15 s) with webhooks.
  https only outside `any`. What passes: `public` → public addresses only; `internal` → the
  tailnet/LAN **or** public addresses, never loopback or link-local — webhooks' `internal` is
  tailnet-only, but browser push services (FCM, Mozilla, Apple) are on the internet, and Web Push
  works in production on the owner's `internal` server, so the literal webhook rule would have
  broken it; the SSRF risk is the host's own services, which stay refused. The operator's own
  `push.ntfy_url` host+port always passes (pinned to what it resolves to), so
  `https://ntfy.vayne.garden` keeps working whether it resolves to the tailnet, the internet or
  the host. An endpoint refused at send time counts as a failed push (logged). Tests:
  `push::tests::endpoints_follow_the_target_rules` (17 cases), and the loopback test in
  `sync_e2e.rs` now runs with `webhook_targets = any` after checking that a default server
  refuses loopback endpoints. API.md §2.1, NOTIFICATIONS §1, OPS §3 updated.
- R17 — fronting feeds are shareable (D-069). `feeds::items` no longer answers 400: `fronting:`
  is evaluated per post from the reader's knowledge — the reader's own members against
  `front_interval`, another account's members only against `notifier::revealed_fronts` (the
  reader's `follower_front_log` for that account: the states shown, at the shown, delayed and
  fuzzed start, or at the reveal time when the time was hidden; members only, `front` level,
  "someone" entries name nobody; nothing without an active follow whose ceiling shares the
  current front). This also fixes a leak the owner-only rule had: the *owner's* own fronting feed
  used other accounts' raw `front_interval` for their posts. Web: the share select is no longer
  locked for such feeds; a note ("This feed shows who was fronting when these posts were
  written") shows when sharing one and when a reader opens one. Tests: `tests/notifier.rs`
  `a_shared_fronting_feed_waits_for_the_reveal` (owner matches at once; the follower matches
  nothing before the reveal, then only the announced member's post, never the hidden member's),
  `posts.rs` updated, and the browser feed test checks both notes. Q15 was already marked
  answered in OPEN_QUESTIONS; API.md and SPEC §6.4 updated.
- R18 — keep everything on this device (D-070), web + core; protocol and numbers in CLIENTS.md
  §4.3. **Open time:** `web/perf/open.spec.ts` (seeds IndexedDB, reads `chorus:*` marks) measured
  10k ops 0.6 s and 100k ops 6.7 s before (IndexedDB 1 s, restore 1 s, first projection 3.5–6 s
  in wasm). Now the client saves the projection it shows plus the core's `projectionDigest()`
  (10 s after the last change, and when the page is hidden) and opens from it: 100k ops mounted
  in **0.35 s**, ready to sync at 4.4 s in the background, no task over 100 ms after mounting.
  Core: `Replica::begin/add_ops/index_step/adopt` + a *partial* projector (only keys recomputed
  since the snapshot; exactness property-tested, incl. stale snapshots and ops created while
  opening), `projection()` serialised directly (the `canonical()` Value detour cost ~0.5 s native
  at 100k). A stale snapshot (killed before the next save) falls back to the old full open once.
  **Setting + Sync everything now** (*Your data → This device*): per-device IndexedDB setting,
  default on in the installed app; on = files (attachments ≤ 20 MB, thumbnails, avatars, emoji)
  fetched into the offline blob cache once a session (`sync/keep.ts`, via `loadBlob`; blobs.ts
  untouched). The button sends the core's `recheck()` (a `pull` per scope; the `caught` digest
  repairs any mismatch; `repairing()` for progress), then fills files, then shows
  `storage.estimate()`. **Offline search:** `JournalIndex` (posts: title/text/tags/CW, from:,
  dates; switches: member names and note, dates) — the Posts tab merges it with the server
  search, a new Switches tab is local. Tests: core unit tests (recheck, open from a snapshot, the
  overflow the first wasm run hit), `projector_props` snapshot property, vitest for the journal
  index (incl. 5k posts + 10k switches < 16 ms), browser tests for the Switches tab and Sync
  everything now. **Sol:** `recheck`/`repairing` are in `chorus-ffi` too; the Android open path
  can use `begin/add_ops/index_step/adopt` the same way (not exposed in FFI yet — say if wanted).
- R15 — the export bundle (D-068), built to the appendix with its defaults. Migration
  **0008_export_job** (no one had claimed 0008; Sol: the next free number is 0009).
  `export_job.rs`: `POST /exports {kind:"full"}` → 202 `{id}` (409 while one runs; one per
  account, two server-wide via a semaphore in `Shared`), `GET /jobs/{id}`, `GET /exports/latest`,
  `DELETE /jobs/{id}` (cancel, or delete the file now), `GET /exports/{id}/download?key=…`
  (streamed from disk with Range; the random key is the credential, so a browser downloads it
  natively; it dies with the file). The zip (`zip.rs`, hand-written: stored entries, data
  descriptors, CRC-32, ZIP64 when needed — no dependency) streams into
  `data/exports/<id>.zip.part`: README, `ops.jsonl` (same as the direct export, now streamed),
  seven CSVs, `blobs/<sha256>` for every blob key in the account's **own** applied ops
  (`blob_hash`, `thumb_blob_hash`, `avatar_blob`, `banner_blob`; never another account's), and
  `manifest.json` (ops count + sha256, blobs with every filename, `missing`, `damaged`). A free
  disk check refuses a job that would leave less than twice its estimate free — Unix via
  `rustix::fs::statvfs` (already in the lockfile; the workspace forbids `unsafe`), none on Windows
  (a full disk fails the job, the part file is removed). Kept 24 h or 1 h after a complete
  download; expiry runs in the notifier loop; a restart fails running jobs; purge deletes an
  account's bundles; a finished one is an in-app `export_ready` notification. Web: "Full export
  with files" in *Your data → Export your data*, polling every 2 s. Tests: `zip.rs` (CRC check
  value, DOS dates, Python `zipfile` reads classic and ZIP64 archives back), `tests/export_bundle.rs`
  (HTTP end to end with Python checking contents, other account's file absent, missing/damaged
  listed, Range, key, expiry after download, delete; the job table's one-at-a-time, cancel,
  restart and expiry), a browser test downloading the zip. API.md §4, DATA_MODEL (§7 + table),
  OPS layout, DECISIONS §Versions updated. The importer (`import-account --from <zip>`) is
  separate later work, as the design said.
- R19 — `chorus-server rebuild` builds into a fresh file and swaps it in
  (`project::rebuild_swap`; `--in-place` keeps the old way; purge/restore still rebuild in place,
  on copies or in their own transactions). Details and traps in NOTES.md (2026-09-24,
  "`chorus-server rebuild` now builds into a fresh file"). Byte-identical: `tests/projection.rs`
  `a_swapped_rebuild_equals_an_in_place_one` compares every table (restore's exceptions: daily
  totals, review stamps), the search index and the schema, checks it refuses while the file is
  open elsewhere, and leaves no stray files. Measured at 1M ops here: 35.9 s → 31.3 s, commit
  5.8 s → 0.3 s; `tests/perf.rs` with `CHORUS_PERF_DB` now times both ways on copies, so **local
  Claude**: rerun it on the PC (`CHORUS_PERF_DB=<saved 1M db> cargo test --release -p
  chorus-server --test perf -- --ignored --nocapture`).
- CI: `ci` and `chorus-home-windows` both **green** on `a76b210` (R9–R19 plus main merged in,
  2026-09-24). Every item in this list is done; the report entries above say what's left for
  Sol (Android: `Changes.removed`, the open-from-snapshot path, recheck/offline search) and for
  local Claude (re-measure the rebuild on the PC).
- 2026-09-25 local Claude — **merged** R9–R19 into `main` (fast-forward to `c30f56b`). Audited
  perms.rs, the push endpoint rule, the export key and keep.ts; on Windows ran the workspace
  tests, web check/vitest/build, the Android build + unit tests against the new core, and the
  browser suite (12/12 with `CHORUS_E2E_CHANNEL=chrome`, new: an installed Chrome instead of
  Playwright's download). One fix: the zip tests found the Microsoft Store `python3` stub and
  read cp1252 stdout as UTF-8 (both fixed). Excellent batch. Next for you: see batch R3 when
  it's written; until then nothing is assigned.
