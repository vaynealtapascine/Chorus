# Batch 3 for gpt-6-sol (same branch `sol/batch-2`, same worktree `F:\DunBuild\Chorus-sol`)

Written by claude-opus-5.5 on 2026-09-23 after auditing and merging batch 2 into `main` (merge
`18e4d6e`). The ground rules in `docs/handoff/sol-batch-2.md` still apply: isolation, the F:
build directories, your own dev servers on 5261/5262, `verify.py --quick` before every commit, and
logging in your handoff file rather than the board. **Keep logging in `sol-batch-2.md`**
(append `### Batch 3` below the existing log) so there is one place to audit.

## Step 0: sync with main (do this first)

1. Finish or `wip:`-commit your uncommitted T6 work (`crates/chorus-server/src/backup.rs` and
   the edits to `Cargo.toml`, `app.rs`, `db.rs`, `lib.rs`, `main.rs`, `project.rs`).
2. `git merge main`. Main now also has:
   - incremental projection (`chorus_core::projector`), plus store `touched` ids and projection
     deltas on web and Android;
   - encrypted UnifiedPush delivery (`push.rs`, migration `0002_push_keys.sql`), with the
     Android connector in `data/Push.kt`;
   - digests;
   - cross-account mention/reply/DM notifications (`activity.rs`);
   - in-app Android updates (`Updater.kt`, `/api/v1/android/latest`, `deploy.ps1 -Android`);
   - Stage mode (`stage.rs`, `Stage.svelte`);
   - `.set` upserts for create-less tables.
3. Resolve conflicts, then:
   - run `scripts/build-web-core.ps1` and `scripts/build-android-core.ps1` (the committed wasm
     pkg must be rebuilt from the merged source);
   - run `verify.py --quick`;
   - build Android with `assembleDebug :app:testDebugUnitTest --offline`.
4. Commit the merge.

Two new rules that come with the merge:

- **Projector invariant.** Every op's effect must stay inside the keys it writes: its row, set
  element, read state, or account front (see the module doc in `projector.rs`). A new op kind or
  table whose projection reads *another* row breaks incremental projection silently. If you add
  op kinds, add them to the generator in `crates/chorus-core/tests/projector_props.rs`. Both
  that test and `tests/converge.rs` (which now checks each device's projector) must pass.
- **Migrations.** Your T6 schema changes must be a new additive file
  (`0003_….sql` or later) registered in `db.rs` `MIGRATIONS`. Never edit `0001`/`0002`.

## Audit findings from batch 2 (do these before T7)

- **A1 · shared-space avatars.** `blobs::can_read` has no rule for avatars of *other accounts'*
  members who author messages in a space the reader can access, so friends' avatars don't
  render in shared chat.
  - Add a rule: the member has authored a non-deleted message, visible to the reader, in a
    channel of a space the reader has `scope_access` to.
  - Add tests for both the positive case and a reader outside the space.
  - *Added on main after this was written:* shared spaces and DMs now exist (M6.2,
    `crates/chorus-server/src/spaces.rs`, `POST /spaces`). `GET /spaces/{id}/authors` already
    serves author cards, including `avatar_blob`, using the rule "wrote a non-deleted message with
    `visibility IS NULL` in a channel of the space". Make `can_read` use the same rule, ideally
    through one shared SQL helper in `spaces.rs`. `tests/sync_e2e.rs`
    `friends_share_spaces_and_dms` sets up a DM with a message; extend it for the avatar. When T8
    defines visibility, update both places together.
- **A2 · structured visibility.** `can_read` checks attachments with
  `m.visibility NOT LIKE '%system_only%'`. Replace that string match with a proper check once
  T8 defines the visibility JSON.
  - `activity.rs` currently skips **every** message with a non-null `visibility`. T8 must update
    it so a message visible to an account can still notify it, and a system-only aside never
    does.
  - Add tests for both.
- **A3 · settings clobbering.** `account.set` replaces the whole `settings` object (LWW per
  field). Two devices editing different settings, such as `follow_ceiling` and anything added
  later, overwrite each other.
  - Propose a fix (for example a dedicated `follow_ceiling` account field, or per-key
    `pref.set` at account scope) as `D-S2-1` in your log.
  - Implement it with a migration-free op change if possible, update `notifier::ceiling_for`,
    and add a convergence test with two devices editing different settings concurrently.
- **A4 · second-account check for bucket assignment.** You didn't click the follower
  bucket-assignment checkboxes with a real second account. Do it with two Playwright profiles
  (`scripts` has examples in git history: `friend.mjs`-style) and log the result.

## Mid-batch review (Claude, 2026-09-23, of `sol/batch-2` up to ec92235)

Merge `main` again to see this. What I checked holds up:

- Backups: online API, hash-checked blobs, restore only into a new directory.
- Exports: every query is tied to the caller's account; the SQLite copy is built fresh.
- A1: done well, with the author rule shared by cards and avatars.
- The per-account sync filter, including backfill and the filtered digest.

Please fix these, in this order:

- **B1 · privacy · threads under private asides.** `visibility::op_visible_to` returns `true` for
  every kind it doesn't list. So `channel.create` with a `parent_message_id` of a non-public
  message is delivered to everyone in the space, which reveals the thread and its name. Messages
  sent in that thread are then delivered as public.
  - Fix: a thread channel inherits its parent message's visibility. Filter its `channel.*` ops,
    and treat messages in it as no more visible than the parent.
  - Add an e2e test: an aside with a thread, as seen by another account.
- **B2 · privacy · tokens reading friends' messages.** `read:messages` tokens search whole shared
  spaces, so a token can read *other accounts'* messages. This breaks the token rule in API.md
  §2.3, and the "only ever reach your own account" promise on the Your data page.
  - Fix: for a token principal (`!p.is_device()`), limit `search::messages` and `message_by_id`
    to `m.account_id = caller`. Devices keep the full, visibility-filtered search.
  - Test both.
- **B3 · search misses segment authors.** The `from` filter and `authors` only read
  `message_author`, so segmented multi-author messages (D-045) are missed. Union
  `message_segment_author`, as `spaces::public_author_source` does.
- **B4 · availability · export under the global lock.** `export_sqlite`, and the other exports,
  run inside `s.db()`, the one server mutex. A large account's `project::rebuild` then freezes
  sync for everyone.
  - Fix: open a separate read connection (WAL allows it) inside `spawn_blocking`.
- **B5 · performance · `visible_digest`.** It walks the whole scope with one `message` query per
  op on every catch-up.
  - Fix: skip the filter when the account wrote every op in the scope (the internal space), and
    look up message visibility once per page instead of once per op.
  - Add a bench or timing test at around 50k ops.
- **B6 · nit.** The exported `device` rows include `push_endpoint`, `push_p256dh` and `push_auth`.
  Null them in the copy.
- **B7 · docs.** DATA_MODEL says `message_fts` uses `content='message'`, but the schema stores its
  own copy (`0001_init.sql`). Fix the doc.

Merge note (for whoever merges): `main`'s People page now has "share history" and "share stats"
toggles (`setSharing`, M6.3), and `setDefault` keeps them. Both still write through `account.set`
settings. When merging e97b698, move both to your `pref.set` `follow_ceiling` value.

Second merge note: `main`'s chat now renders only the newest pages (`Chat.svelte` `PAGE`,
`shown`, `first`; older pages load on scroll-up), and `data.ts` `messages()` is cached per
channel and patched from deltas (`sync/delta.ts` `patchedFrom`). Your `focusId` jump (ec92235)
scrolls to `[data-message-id]`, which may not be rendered. When merging, widen the window first,
for example `shown = Math.max(shown, msgs.length - msgs.findIndex((m) => m.id === focusId) + PAGE / 2)`,
then scroll after `tick()`.

## Then continue

Continue with **T6 (finish) → T7 → T8 (with A2) → T9 → T10 → T11 → T12 → T13** as written in
`sol-batch-2.md`.

Additional notes for those tasks:

- **T7 exports:** reuse `api_data::principal` (sessions and `chorus_…` API tokens) for
  `GET /api/v1/exports/...`. Add an `export` scope to `api_data::SCOPES` and to the web "Your
  data" page (`DataPage.svelte`) rather than a second auth path.
- **T9 search:** the web client keeps its projection as a plain object updated by deltas
  (`web/src/lib/sync/delta.ts`, `App.svelte` uses `$state.raw`). Build any search index
  incrementally from the same deltas; don't re-scan the whole projection per keystroke. Budget:
  500 members ≤ 16 ms per keystroke (SPEC §9).
- **T10 insights:** read intervals from `projection.fronts[acct].intervals`, which is updated by
  deltas. The dashboard must not trigger a full re-projection.
- **T11 Android:**
  - `data/Chorus.kt` now applies deltas (`Model.applyDelta`), so keep using `chorus.model`.
  - Push and the updater live in `data/Push.kt` and `data/Updater.kt`, and `MainActivity`
    calls `Push.ensure` and `Updater.schedule`. Keep those calls when editing `MainActivity`.
  - *Added on main:* person accounts now have one `is_self` member (D-003), created by the
    server. On the web (`web/src/lib/data.ts` `selfMember`), its presence hides members, history,
    the front card and quick switch, and chat speaks as it. Do the same on Android. The widget
    and search launcher make no sense for a person, so show a short "for systems" note instead.
- **Migrations:** main now has `0003_follower_history`. Number any new ones `0004` and up, and
  keep the list in `db.rs` in order when merging.
- **Android dependencies:** `settings.gradle.kts` now limits `google()` to Google's groups, so
  a library from **Maven Central** can be fetched online (drop `--offline` once) even while
  dl.google.com is down. Pin any you add in DECISIONS §Versions. AndroidX libraries still need
  to be in the cache.

When everything is done or blocked, spend the remaining time on T13 hardening. Log what you
verified by running vs only compiled, as before; that part of batch 2 was excellent.
