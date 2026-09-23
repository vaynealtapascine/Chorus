# Batch 2 for gpt-6-sol (branch `sol/batch-2`, worktree `F:\DunBuild\Chorus-sol`)

Assigned by claude-opus-5.5 on 2026-09-23 at commit `6b528f2`. Claude will audit and merge this
branch afterwards, so **this file is your log**: under each task, record status
(`todo` / `doing` / `done` / `blocked` / `partial`), your commits, the tests you added, what you
**verified by running it** vs. only compiled, and anything the auditor should look at first.

## Ground rules (read these first; they differ from a normal turn)

1. **Isolation.**
   - Work only in `F:\DunBuild\Chorus-sol` on branch `sol/batch-2`.
   - Never touch the main checkout `C:\Users\pcuser\source\repos\Chorus`, never switch or merge
     `main`, never rebase, never push.
   - Never deploy to `~\selfhost`, never touch Windows services.
2. **Board.**
   - Do **not** edit the *Now* block or the board in `PROGRESS.md`; Claude reconciles them.
   - Log here instead.
   - New traps go into `docs/NOTES.md` as usual.
   - Proposed decisions go here as `D-S2-n` (Claude assigns real numbers).
3. **Disk: C: is full.** Set these in every shell:
   ```
   CARGO_TARGET_DIR=F:\DunBuild\chorus-target-sol
   CHORUS_GRADLE_BUILD_DIR=F:\DunBuild\chorus-gradle-sol
   GRADLE_USER_HOME=F:\DunBuild\gradle
   JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
   PYTHONIOENCODING=utf-8
   ```
   Gradle must run with `--offline`, because dl.google.com is unreachable. If a task needs a
   new Android library that isn't cached, record it as blocked and move on.
4. **Your own dev servers.** Claude's run on 5251/5252 with `data-dev/`; don't use those.
   - Server: `cargo run -p chorus-server -- --config sol-dev.toml serve` (port 5261, data in
     `data-dev-sol/`).
   - Web: in `web/`, `CHORUS_PORT=5261 npm run dev -- --port 5262`.
   - Invites: `%CARGO_TARGET_DIR%\debug\chorus-server.exe --config sol-dev.toml invite --kind system|person`.
   - Headless checks: Playwright from `C:/Users/pcuser/source/repos/DisclosureStudio`'s
     node_modules, with a persistent profile per account.
5. **Commit discipline.**
   - `python scripts/verify.py --quick` passes before every commit.
   - One logical change per commit, conventional prefix, tests with the code.
   - Never leave uncommitted work. Prefix a commit with `wip:` if you must stop mid-task.
   - After adding UniFFI/wasm exports, run `scripts/build-android-core.ps1` or
     `scripts/build-web-core.ps1`. Both honour `CARGO_TARGET_DIR`.
6. **Settled code.**
   - Don't change the semantics of `chorus_core::{sync, model, notify, stage, front, text}`
     except to fix a bug proven by a test. Explain any such fix here.
   - Privacy invariants (NOTIFICATIONS.md §5) and "never lose data" (AGENTS.md) are hard
     requirements.
7. **When blocked, don't stop.**
   - Blocked on network, the device, or an owner question: write it down, use the documented
     default, and continue with the next task.
   - When the list is done, spend remaining time on tests: property tests, e2e, Playwright
     flows.
8. **Phone.**
   - It's a Samsung A56, serial `RRCY500455P`. It may be on adb, and is usually locked.
   - Never try to unlock it. Verify with `adb logcat` and `am start`.
   - adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

## Tasks, in priority order

Each task lists its spec references and what "done" means.

### T1 · M2.6 blob store (API.md §5; DATA_MODEL `blob`)
Build a content-addressed store under `data_dir/blobs/ab/cd/<sha256>`:
- `HEAD` returns 200 when complete, 206 with `Upload-Offset` when partial, 404 otherwise.
- `PUT` takes chunks with `Content-Range`, is resumable, and verifies the sha256 on completion
  (a mismatch deletes the partial upload and returns 400).
- `GET` supports `Range` and sends `Cache-Control: immutable`.
- The size limit comes from config (default 100 MB).
- Access check: the caller may read a blob only if it can read something that references it
  (attachment, avatar, emoji), or if it uploaded it.
- **Done when** tests cover resuming after a partial upload, a hash mismatch, range reads, and
  a stranger getting 403. No data is ever deleted except that partial.

### T2 · M5.6 attachments and images, web (SPEC chat §5; `attachment.*` ops exist)
- Attach by picker, paste or drop. Hash with SubtleCrypto, then queue the upload in an
  IndexedDB outbox so it works offline and resumes after a reload.
- Send the message op referencing the blob. Upload a client-made thumbnail as a second blob.
- Support alt text and spoiler.
- Render in `Message.svelte`, `Stage.svelte` (respect the stage `blur` option for
  attachments) and forwards/quotes.
- **Done when** it's verified in the browser: an image sent while the server is stopped
  appears after reconnect.

### T3 · Member avatars and PluralKit avatars
- `avatar_blob` is shown wherever avatars appear on the web: front card, grid, chat, stage,
  People.
- The member editor can upload and crop an avatar.
- The PluralKit import fetches `avatar_url` in the browser and uploads it. If CORS blocks it,
  record a warning in the import notes and keep going; don't fail.
- Update the Android `Avatar` to load a blob when present (use `HttpURLConnection` plus a
  file cache; no new libraries).

### T4 · M5.13 custom emoji (D-054; `emoji.*` ops in `server` scope)
- Admin-only upload with a square crop, name, aliases and category; retire via delete.
- A picker in the composer, `:name:` autocomplete, rendering in text, and reactions with
  custom emoji.
- Names are unique among non-deleted emoji, enforced in core and covered by a test.

### T5 · M6.1 remainder: buckets
In the People page:
- Create, rename and delete buckets, each with a preset ceiling.
- Assign followers to buckets (`bucket.assign` / `bucket.unassign`).
- Set the account default ceiling (`account.set` → `settings.follow_ceiling`; this now
  projects, see commit `6b528f2`).

In the member editor, add "announce to buckets…" (`notify_policy.announce = {buckets:[…]}`).

**Done when** a server test in `crates/chorus-server/tests/notifier.rs` shows that bucket
ceilings (most permissive wins, inherited defaults) and bucket-restricted members behave as
`chorus_core::notify` says.

### T6 · M2.8 backups (OPS.md backups section)
- `chorus-server backup [--to DIR]` takes an online, consistent SQLite backup (the backup
  API, not a file copy) plus the blobs manifest.
- `chorus-server restore --from X --into NEWDIR` never overwrites. It verifies
  `PRAGMA integrity_check` and that `rebuild` reproduces the projections.
- `serve` makes a nightly backup to `backup.dir`, keeping the last N (configurable).
- Tests for each command.

### T7 · M10.3 exports (API.md exports; SPEC data section)
- Exports: a JSONL op log; tidy CSVs (members, groups, switches, front_intervals,
  front_daily, messages, posts); a filtered SQLite copy.
- Available as a CLI, and as `GET /api/v1/exports/...` limited to the caller's **own** scopes
  (a follower gets nothing extra).
- Document the columns in DATA_MODEL.
- Tests, including one proving another account's data never appears.

### T8 · M5.7 hidden messages (D-010)
- Spoilers: check what `text.rs` already supports.
- Content warnings shown collapsed.
- Member-visible messages: a soft, in-system filter.
- System-only asides inside shared spaces: never sent to other accounts. This is enforced on
  the **server** fan-out and catch-up, with a test.
- Stage mode respects all of these.

### T9 · M5.8 search, web
- Local search over the replica (fast, offline).
- `GET /search/messages` on the server FTS for history beyond the replica.
- Filters: `from:`, `in:`, `has:`, `before:`/`after:`.
- A results list that jumps to the message.

### T10 · M10.1 insights dashboard, web
- Front time per member by day and week (from intervals, via core's `front::daily` if it's
  exposed; expose it if not).
- Co-front pairs, and a switches-per-day chart.
- Each chart has a table and a CSV download.
- No chart libraries beyond what's already in `package.json`; plain SVG is fine.

### T11 · M4.4 Android parity (build offline; device-verify only if the phone is on adb)
- A switcher sheet with levels (front, co-con, present), primary, subsystems and a typed
  time.
- Members filtered by group.
- History with undo/retract.
- Link another device (show the link from `POST /devices/invite`).
- If the phone is attached: install the debug APK, then launch it and check logcat for
  crashes, including adding the widget provider (`QuickSwitchWidget`).

### T12 · M7 journals, web (SPEC journals section; `post.*` ops exist)
- Compose a post as member(s), with the note/entry toggle, CW and visibility.
- A system timeline.
- A member profile page with posts and replies.
- Reactions on posts.
- Stage mode for posts (the Card style).

### T13 · Hardening (only once T1–T12 are done or blocked)
- More `converge.rs` scenarios, including the new upsert tables.
- Property tests for blob resume.
- Playwright e2e for follow → notification → reveal, and stage save/load.

## Log

(Append below: `### Tn · status` then bullets.)

### T1 · done
- Commit: `42ff76a` (`feat(server): add resumable content-addressed blob store`).
- Added real HTTP server tests in `crates/chorus-server/tests/blobs.rs`: partial HEAD and resume, hash mismatch cleanup, range GET, size limit, stranger 403, and a follower avatar denied until its member is revealed.
- Ran those HTTP tests (3 passed) and `python scripts/verify.py --quick` (passed: Rust fmt, clippy, workspace tests, wasm build, Svelte check, web tests). This verifies request behaviour through a listening server, not just compilation.
- Access checks cover the uploader, own account/member/group/attachment references, shared message attachments via `scope_access`, custom emoji, and member avatars after follower reveal. Audit this SQL first, especially visibility when T8 adds system-only asides.
- The API's optional `?thumb=480` server fallback is not implemented; T2 produces client thumbnails. GET currently buffers up to the configured 100 MB limit in memory. Consider streaming if large blob traffic warrants it.
- The ignored wasm package was absent in the isolated worktree, so `pwsh scripts/build-web-core.ps1` was run before the passing verifier. No core semantics changed. No proposed decision.

### T2 · done
- Commits: `9fd5b0a` (SQL message/post attachment links and rebuild test), `c8a994f` (web upload queue, composer, thumbnails, rendering).
- Added server test for an attachment link arriving before its attachment op and surviving projection rebuild; added web test for attachment metadata, alt text, and spoiler mapping. `python scripts/verify.py --quick` passed before both code commits.
- Ran an actual Playwright browser flow on the isolated 5261/5262 servers: stopped 5261, picked an image and sent a message as a member, observed the local message/image and one IndexedDB outbox blob, restarted 5261, reloaded the persistent browser profile, observed the image decode (natural width 1) and outbox count fall to zero. Also ran `imageThumbnail` in Chromium with a generated 900×600 PNG: it made a distinct 480×320 WebP blob. These are runtime checks.
- Picker, paste/drop, alt text, spoiler, quote/forward previews, and Stage attachment blur compile and pass Svelte checks; they were not each clicked through in the browser. Audit forwarded attachments across two accounts and the `AttachmentView` object-URL lifecycle first.
- No core semantics changed. No proposed decision. The first quick verifier attempt while the dev server was running hit Windows' executable lock; stopping only the isolated server allowed the next run to pass.

### T3 · done
- Commits: `6f6a812` (server follower/avatar privacy), `80c32ac` (web avatar display, crop and PluralKit fetch), `f675517` (Android authenticated blob file cache and Avatar display). `python scripts/verify.py --quick` passed before each.
- Tests added: a server test proves a follower receives `avatar_blob` only at reveal time; another verifies an account avatar is readable to an active follower but returns 403 before following. Both ran and passed, along with the existing blob/notifier suites.
- Ran Playwright on 5262: picked and cropped a 600×300 PNG offline, observed a 256×256 avatar immediately in the editor, restarted the isolated 5261 server, reloaded, observed a decoded 256×256 avatar with the outbox empty. Imported a PK export containing an unreachable `avatar_url`: the member imported, and the dialog showed a fetch warning rather than failing the import. CORS-success from a real PK host was not tested.
- Generated ignored UniFFI bindings/libs with `scripts/build-android-core.ps1 -Debug`, then ran `android/gradlew.bat :app:assembleDebug --offline` successfully. `adb devices -l` listed no phone, so Android runtime rendering and cache behaviour were compiled only. Audit `AvatarBlobs.kt` cache lifetime and session retry first.
- No core semantics changed. No proposed decision.

### T4 · done
- Commits: `9cf2aea` (core name rule, server ingest/admin flag, web upload, crop, picker, autocomplete, rendering, reactions); `dc80f2d` (authenticated `GET /api/v1/emoji` catalogue). `python scripts/verify.py --quick` passed before each code commit.
- Tests added: core name syntax and uniqueness among live rows; core op validation; server ingest duplicate rejection, retired-name reuse and restore conflict; web stable-ID parser and retired-row mapping; HTTP catalogue authentication and active-only listing. The Rust workspace tests and 18 web tests ran through the quick verifier.
- Ran Playwright on the isolated 5261/5262 servers with an existing admin device: uploaded a 256×128 PNG, observed a 128×128 crop and a working horizontal crop control (centre pixel changed from blue to red), sent `:name:` as a member, observed a decoded 128×128 image, added `custom:<id>` reaction, reloaded and observed both persist, used autocomplete, retired the emoji, and reloaded to confirm the old message still rendered. The picker, GIF path, and offline emoji upload were compiled but not clicked through. Server ingest and HTTP catalogue behaviour ran in tests.
- Audit first: emoji blobs and retired-row access in `blobs.rs`; the `EmojiImage` object-URL lifecycle; `RichText`'s hidden token used to preserve quote-selection offsets; aliases colliding with each other (primary names take precedence). The browser manager accepts animated GIF only when already square and at most 128 px, preserving animation. Server ingest currently checks names and admin scope; image format/size are enforced by the web uploader, not by op ingestion.
- No changes to `chorus_core::{sync,model,notify,stage,front,text}` semantics. No proposed decision.

### T5 · done
- Commit: `0b5dc83` (People bucket create/rename/delete, preset ceilings, follower assignment, account default; member editor bucket-restricted announcements). `python scripts/verify.py --quick` passed before the code commit.
- Added a server notifier test that runs account default inheritance, two buckets with a most-permissive fold, assignment/unassignment, and a member restricted to one bucket. It checks actual reveal timing and visible names through `notifier::process_due`, and passed with the server suite. Added web data tests for active buckets and LWW assignments; all 19 web tests passed.
- Ran Playwright on the isolated 5261/5262 servers: created a Close bucket, renamed it, selected Private as account default, reloaded and saw both persist; selected "Only selected buckets" for a member, checked the bucket, reloaded and saw the policy persist. Follower assignment checkboxes compiled and the server exercised the underlying assignment ops, but the checkbox was not clicked in the browser because the dev account has no followers.
- Audit first: follower assignment UI with a real second account, especially the account-ID payload; preset matching after sync; account `settings` replacement when two devices edit different settings concurrently. Newly accepted followers now use `{}` to inherit the account default rather than an unconditional Gentle override.
- No core semantics changed. No proposed decision.

### Batch 3

#### T6 · done (format decision proposed)
- Commit `a06942c` replaced `VACUUM INTO` with rusqlite's online backup API and drafted a snapshot/manifest, restore verification, rotation and nightly scheduler. That commit was WIP; `cargo check -p chorus-server --offline` and `verify.py --quick` passed before it.
- This finish commit adds a CLI integration test running `backup` and `restore` on an on-disk migrated database with an immutable blob. It verifies manifest and copied bytes, unchanged account data, epoch increment, `restore_open`, refusal to overwrite, rejection of a corrupted blob, rejection of a checksum-valid database whose projection differs from its op log, and cleanup after failed restores. A rotation test verifies configurable daily retention while preserving the shared blob pool.
- Ran the actual commands against `data-dev-sol/chorus.db` (40 ops): created a snapshot under `data-dev-sol/backup-run`, restored into a new directory, and the CLI's SQLite integrity and projection-rebuild checks passed. The Rust CLI integration and rotation tests passed; the server's scheduler was compiled and the rotation algorithm was unit tested, but a clock-triggered nightly run was not observed.
- Proposed decision **D-S2-2**: keep the manifest-versioned snapshot directory (`chorus.db` plus `manifest.json`, immutable blobs pooled at `backups/blobs`) as the backup format for v1. OPS.md currently says `.db.zst`; no zstd library or executable is pinned in the offline toolchain. The directory format gives a consistent online copy and incremental blobs now, and a future compressed format can be introduced under another manifest version. This is an OPS format discrepancy for the owner/auditor to settle before deployment; no OPS spec text was silently changed.
- Audit first: decide D-S2-2 and align OPS.md or add compression; inspect the nightly catch-up rule after a missed backup time and the pre-migration `.db` backup distinction. No core semantics changed.

#### Step 0 · done
- Merge commit `a4778fa` brought main `c548c9b` into `sol/batch-2`. The only conflict was `chorus-server/Cargo.toml`; retained main's `reqwest` dependency and the branch's rusqlite `backup` feature. No branch switch or rebase.
- Rebuilt ignored wasm and Android UniFFI outputs, ran `verify.py --quick` (passed), and ran `:app:assembleDebug :app:testDebugUnitTest --offline` (passed). Android was compiled and unit tested; no device runtime check was made for the merge.

#### A1 · done
- Shared the visible-author SQL source between `/spaces/{id}/authors` and blob avatar authorization. A reader can fetch another account's member avatar only after that member authored a non-deleted, unhidden message in an accessible space. The extended `friends_share_spaces_and_dms` HTTP/WebSocket test passed: DM membership alone and a stranger returned 403, an author avatar returned 200 after the message, and leaving the DM returned 403 again. This exercises behaviour on a running test server, not just compilation.
- Audit first: revisit both uses of `PUBLIC_AUTHOR_SOURCE` when T8 defines structured visibility. No core semantics changed; no proposed decision.

#### A2 · done with T8
- T8's first commit replaces the attachment `LIKE '%system_only%'` check with one structured public rule (`NULL` or `{"mode":"all"}`) shared with author cards; activity notifications apply the same rule. A live HTTP blob test shows that a shared attachment is readable for NULL/all and forbidden for system-only/members, and an ingest/notifier test shows explicit all mentions notify while system-only mentions do not. The next T8 piece must still filter system-only message ops from sync fan-out and catch-up.

#### A3 · done
- Proposed decision **D-S2-1**: write account preferences by key with the existing account-scoped `pref.set` op (`device: ''`), reserving `account.settings` as a legacy read fallback. This avoids whole-object LWW clobbering without a schema migration or new op kind. The web default follow ceiling now reads/writes `pref.follow_ceiling`; `notifier::ceiling_for` reads that value first, then the legacy account setting.
- Commit `e97b698`. A server integration test ingested two same-time edits from phone and laptop for different keys, confirmed both SQL rows and the effective follower time rule, rebuilt projections, and confirmed both rows and the rule again. This ran behaviour through ingest, projector and notifier. `verify.py --quick` passed. On the 5261/5262 dev servers, Playwright changed the default from Private to Close, reloaded and found Close persisted, then restored Private. The browser control and server preference path both ran.
- Audit first: ensure any future account setting editor also uses one `pref.set` key per independently editable setting; confirm migrated accounts keep their legacy default until explicitly changed.

#### A4 · done
- Used two persistent Playwright profiles against the isolated 5261/5262 servers: enrolled a person account `friend_sol`, requested to follow `soltest`, accepted in the system profile, checked the first follower bucket checkbox, and reloaded. It remained checked. A read-only SQLite query confirmed `bucket_assignment.follower_account_id` equals the second account ID and `is_present = 1`. This is browser plus live server behaviour, not just compiled UI.
- One test creates a blob under `crates/chorus-server/data/` even with the isolated dev config. Automatic approval review blocked deletion of that generated file; `.gitignore` now excludes this generated directory so the worktree remains clean. Audit first: revisit the test's output path if artifacts accumulate.

#### T7 · done (archive/job protocol deferred)
- Commit `63b16c5` added account-authored JSONL op export and seven account-filtered tidy CSVs under `GET /api/v1/exports/...`, with an `export` API-token scope and downloads on the web Your data page. It reuses `api_data::principal`, so both device sessions and scoped API tokens resolve to one account. The HTTP integration test ran a real server, checked token permission and no authentication, all seven CSV routes and CSV quote escaping, and proved Alice's exports contain no Bob-only member or op payload. `verify.py --quick` passed.
- This finish commit adds `account.sqlite`, built as a fresh migrated database from only the requesting account, its devices and its applied authored ops, then reprojects and adds seven CSV-compatible views. The CLI supports `export --account ID --kind full|csv|sqlite [--to DIR]` and refuses to overwrite files. HTTP and CLI tests use two accounts; they verify account/op/view counts and absence of Bob's private text. The SQLite copy is made from selected rows, never a full-server file with deleted pages.
- Ran the three CLI formats against the isolated dev database: 40 JSONL lines, seven CSV files, and a SQLite copy with one account, 40 ops, two members, five messages, seven views and `PRAGMA integrity_check = ok`. Playwright clicked the Your data buttons and received `ops.jsonl` (25,414 bytes), `account.sqlite` (610,304 bytes, SQLite header), and `members.csv` (258 bytes). This verifies actual browser downloads and CLI behaviour. The integration tests and `verify.py --quick` also run before this finish commit.
- Proposed decision **D-S2-3**: serve direct account-scoped exports now; reserve the DATA_MODEL §7 zip archive with blobs and API `POST /exports` background jobs for later. The direct GET routes specified by the Batch 3 handoff are implemented. `full` in the CLI currently means an applied authored-op JSONL file, not an importable zip; the auditor should settle this naming/contract before describing it as a full backup.
- Audit first: confirm authored ops across joined spaces are the desired account export boundary; inspect resource use for very large synchronous exports and decide when to introduce background jobs. No core semantics changed.

#### T8 · done
- Commit `68b989b` settles A2's structured visibility at the server read and notification boundaries. Tests exercise HTTP and ingest behaviour on running test servers.
- Commit `0af6be2` filters shared-space ops by recipient in live fan-out and catch-up, and computes the catch-up digest from exactly the visible op IDs. A public send backfills earlier attachment and related ops that were hidden until their message existed. Ingest rejects guessed edits to another account's private aside. The expanded `friends_share_spaces_and_dms` test ran two accounts and a second owner device through live delivery, offline catch-up, pre-send attachments, a rejected cross-account edit, and a digest check with zero repairs. The full quick verifier passed before the commit.
- This final T8 piece adds Advanced composer controls for CW labels, member visibility in internal spaces, and sending-system-only asides in shared spaces; a viewing-as control and soft-view explanation in Chat and Stage; CW collapse and account preference to auto-expand; and spoiler-link masking before link rendering. A browser run on the isolated 5261/5262 servers sent a restricted CW message and verified it was absent without a permitted viewer, present but collapsed when viewing as that member, revealed on click in Chat and Stage, and auto-expanded after a preference change and reload. The browser also sent a spoiler-wrapped URL and verified there was no link until reveal. `svelte-check` and Vitest passed. The server filters were verified by running the two-account WebSocket test; Android only retains the core's existing spoiler entity support and was not given T8 UI.
- Audit first: existing replicas that received private asides before this filter may need a migration that removes those already-confirmed ops; there is no known installed aside-producing UI yet. The scope cursor still reveals gaps in global sequence numbers. Check whether quoted snapshots from restricted or CW messages need additional masking, and whether Android should add T8 controls in a later parity pass. No core semantics changed and no new decision proposed.

#### T9 · doing
- The first server piece adds `GET /search/messages` backed by the existing message FTS5 table. It applies `scope_access` and the structured public-or-own visibility rule, handles channel/author/date/attachment filters, and gives API tokens a separate `read:messages` scope. A real HTTP integration test runs Alice, Bob and an outsider through the endpoint and checks that private asides, deleted messages and inaccessible spaces do not leak. Next: incremental local web index driven by projection deltas, search UI and jump-to-message behaviour. No core semantics changed and no new decision proposed.
