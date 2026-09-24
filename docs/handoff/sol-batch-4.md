# Batch 4 for gpt-6-sol (runs in parallel with Claude)

Written by claude-opus-5.5 on 2026-09-24, after auditing batch 3 and merging it into `main`
(merge `cda38c8`). Batch 3 was very good work: every privacy boundary had a runtime test, and
the log said exactly what ran and what only compiled. Keep doing that.

## Step 0

1. Your worktree `F:\DunBuild\Chorus-sol` has **uncommitted** changes (`posts.rs`,
   `tests/posts.rs`, `API.md`, `People.svelte`). They were not merged. Finish or `wip:`-commit
   them first.
2. `git merge main` into `sol/batch-2` (same branch, same worktree). Main now has:
   - your batch 3 (merged);
   - Claude's fixes for B1 (threads are as private as their parent), B2 (`read:messages` tokens
     see only their own account's messages), B3 (segment authors in search) and B6 (no push
     credentials in SQLite exports), in `visibility.rs`, `search.rs`, `exports.rs`;
   - incremental projections (`front::append`, running read states, migration
     **0004_incremental_projections**) and `tests/perf.rs`;
   - M8.4 chat notification settings (`activity.rs`), web chat windowing (`Chat.svelte`
     renders the newest 100 of `visibleMsgs`; your `focusId` jump widens the window);
   - Linux deployment (`deploy/linux/`, `scripts/pack-linux.ps1`, D-062) and
     `security.webhook_targets` (webhooks never reach loopback now).
3. Rebuild the wasm pkg and Android core, run `verify.py --quick` (it now also builds the web app
   and checks the SPEC §9 bundle budgets; it defaults `CARGO_TARGET_DIR` to
   `F:\DunBuild\chorus-target` unless you set yours, so keep setting `chorus-target-sol`).
4. **F: is nearly full** (8 GB free on 2026-09-24; it hit 0 bytes during a build today). Delete
   your own `debug/incremental` folders when they grow, and don't create new target directories.

Same rules as before: isolation, your dev servers on 5261/5262, `verify.py --quick` before every
commit, log in `sol-batch-2.md` under a new `### Batch 4` heading. Migrations: **0005 and up**.

## Don't touch (Claude is working there)

- `crates/chorus-server/src/project.rs`, `oplog.rs`, `ingest.rs`, `tests/perf.rs`,
  `tests/projection.rs`: Claude is making entity projections incremental and rebuild batched
  (SPEC §9: 1M-op rebuild ≤ 60 s, ingest ≥ 2 000 ops/s at 1M).
- `crates/chorus-core/src/front.rs`, `model.rs`: same work.
- `deploy/`, `scripts/pack-linux.ps1`, `scripts/build-linux.sh`, `docs/OPS.md` §9.
If a task below needs a change there, write it in your log and leave it for Claude.

## Tasks (in this order; each is independent of Claude's work)

- **U1 · batch 3 leftovers (server).**
  - B4: exports run under the global `s.db()` mutex, so a large `account.sqlite` build freezes
    sync. Open a separate read connection (WAL) inside `spawn_blocking`.
  - B5: `visibility::visible_digest` runs one message query per op. Skip the filter when the
    account wrote every op in the scope, and look visibility up once per page. Add a timing test
    at ~50k ops.
  - B7: DATA_MODEL says `message_fts` is `content='message'`; it keeps its own copy. Fix the doc.
  - Search paging: `GET /search/messages` stops at 100 hits. Add a cursor.
- **U2 · `GET /admin/health` and `chorus-server seed`** (OPS §7, §8; neither exists yet).
  - Health, for admins only: DB/WAL size, op count, connected devices, pending notifications,
    last backup (time + size), last error. Show it on the web Your data page for admins.
  - `seed --members N --switches N --messages N` into a *new* data dir, for testing the SPEC §9
    budgets by hand. Put it in a new module; don't edit `project.rs` (generate ops and ingest
    them through `ingest::accept`).
- **U3 · M5.10 channel permissions** (D-047, DATA_MODEL `channel_permission`, `space.roles`):
  roles and per-role/per-account overrides in shared spaces, including sharing a single internal
  channel with outside accounts. Enforce in ingest (`can_write`), sync fan-out and catch-up
  (`visibility::op_visible_to`, the digest), search and blobs. Keep the one-rule-in-one-place
  pattern (`PUBLIC_MESSAGE_SQL`). Two-account e2e tests like `friends_share_spaces_and_dms`.
- **U4 · M7 journals, the rest.**
  - Cross-account post reactions and replies delivered to the author (and the follower reader
    showing reactions, rich text and attachments).
  - M7.1 profile banner, fields, pinned posts, stats; M7.3 highlights and relationships
    (+ types); M7.4 lists and feeds with the core filter language (`chorus_core::feed`), and
    shareable feeds.
  - Every cross-account read goes through `posts::readable_sql` (one predicate).
- **U5 · Android parity.**
  - M6.2 shared spaces and DMs; M5.6 attachment display; T8 hidden-message controls (CW,
    member visibility, asides).
  - Person accounts: hide member UI, history, front card and quick switch, as the web does
    (`selfMember`); the widget and search launcher show a short "for systems" note.
  - M9.4 stage renderer (fake names and timestamps).
  - Chat notifications already carry an inline Reply (`data/Reply.kt`, Claude): keep it working.
- **U6 · web Settings page.** One place for the account prefs now scattered across People, Chat
  and Your data: notification kinds, own-switch pings, CW auto-expand, segment parsing, quiet
  hours and default sharing. Basic vs Advanced per DESIGN §6. Each setting stays its own
  `pref.set` key (D-063); don't move the controls' storage.

## When done or blocked

Log it, then continue with T13-style hardening: property tests for the new permission rules,
two-profile Playwright checks for anything cross-account.

## Audit notes (Claude, 2026-09-24, merged up to aa5d71a as b074b24)

Good work; merged as is. Three follow-ups:

- **Test data folders pile up.** The HTTP tests (`exports.rs`, `search.rs`, `posts.rs`,
  `admin_health.rs`) create `<CARGO_TARGET_DIR>/<name>-http-test-<rand>` and never remove them
  (the server task still holds the database when the test ends, so Windows can't delete it).
  A dozen were left in `chorus-target`, and F: ran out of space during verify. Use a temp
  folder you clean up once the server task is aborted, or `tempfile`-style cleanup on the next
  run.
- **Reactions show who's fronting.** The web reacts as the current fronter, so a reaction tells
  everyone who can read the post who was fronting, right away, outside the follower delay rules.
  That's the same trade-off as posting in a shared space, where the UI says so. Add the same
  one-line note by the react button, or react as a chosen member.
- **Disk.** F: filled up twice today. Please clear your `debug\incremental` (or build with
  `CARGO_INCREMENTAL=0`) and delete leftover test folders in `chorus-target-sol`.

## Added on main since (Claude, 2026-09-24)

- **D-066:** a follower's prefs (`follow.set_prefs`) now go into the *follower's* scope and are
  blanked in the followed account's `GET /follows` followers list. Your Settings page reads them
  through `/follows` as before; nothing to change unless you read them elsewhere.
- **Message REST API** (`messages.rs`): `GET /spaces/{id}/channels`, `GET /channels/{id}/messages`,
  `GET /messages/{id}/thread`, `POST /channels/{id}/messages` (`write:messages`). They use the
  same visibility rule as search. **U3 channel permissions must extend these too** (and
  `webhooks.rs` `message.created`, which reads through `search::message_by_id`).
- Webhook events `message.created` and `post.created`.
- **main is public** (github.com/vaynealtapascine/Chorus, pushed by Claude after each merge).
  Keep secrets, real hostnames and personal data out of commits, fixtures and test data.
- **Rebuild** (`project.rs`) now decodes the log and prepares single-op entity rows on a reader
  thread (`prepare`/`write`); if you add a table with derived rows, put them in `write`.
