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

#### T9 · done
- Commit `884f3e7` adds `GET /search/messages` backed by the existing message FTS5 table. It applies `scope_access` and the structured public-or-own visibility rule, handles channel/author/date/attachment filters, and gives API tokens a separate `read:messages` scope. A real HTTP integration test runs Alice, Bob and an outsider through the endpoint and checks that private asides, deleted messages and inaccessible spaces do not leak. `verify.py --quick` passed before that commit.
- The finish piece builds a local token index once from the replica, then applies the same core projection deltas that update the web view; message edits, deletion, late attachment metadata and name changes affect only their touched keys. Search parses `from:`, `in:`, `has:`, `before:` and `after:` and shows local results offline plus FTS results online. Search results respect the T8 soft member filter and collapse CW bodies. Result links carry a message ID; locally held messages scroll into view, while `GET /messages/{id}` provides a scoped, read-only detail when the replica lacks the row.
- Vitest covered delta updates and measured a 500-member indexed query against the 16 ms input budget. A Playwright run on 5261/5262 searched saved messages, observed an authenticated 200 response with a server FTS result, followed a result to the exact chat message, disconnected network and still found it locally. The same run confirmed a restricted CW result stayed absent until viewing as its member and did not show its body. The HTTP test ran the new detail route against public, private-aside and outsider cases. The read-only server-history fallback compiled and passed the HTTP test but was not exercised by Playwright with an evicted local message.
- Audit first: the server FTS returns at most 100 ranked hits, without paging; large histories need cursor paging. Local offline `from:` by name uses synced member rows, so names of other accounts' authors may need author-card indexing. The 16 ms benchmark is a 500-member indexed query on this host, not an Android performance measurement. No core semantics changed and no new decision proposed.

#### T10 · done
- This commit adds an Insights route with front time by day and week, co-front pairs, and switches per day. Each chart has a data table and CSV download. The page reads `projection.fronts[accountId].intervals` and switches from deltas; it does not re-project. The wasm adapter exposes `front::daily` and supplies offset transitions from the system's IANA timezone, splitting intervals at DST changes before core day allocation.
- Svelte check and all 29 web tests passed, including wasm daily splitting and an America/New_York DST transition. A Playwright run on the isolated 5261/5262 servers made a backdated co-front switch, then observed all four populated charts, a daily table, and a downloaded `front-by-day.csv` with member data. A later 390px browser screenshot after the timezone update showed the dashboard and six navigation links without horizontal overflow. `verify.py --quick` passes before this commit.
- Audit first: compare a DST crossing with server `front_daily`, which currently uses a latest-offset approximation, and inspect large-history chart costs. The Android dashboard is outside this web task. No core semantics changed and no decision proposed.

#### T11 · done (device startup checked; account flows remain unverified on-device)
- This commit adds an Android switcher sheet that snapshots the current front and offers front/co-con/present levels, primary selection, subsystem subjects, group-filtered member search, typed local time, note and notification choice. History now shows Undo/Redo for each switch through `front.retract`/`front.unretract`. The top bar makes a one-use link from `POST /devices/invite` and shows a selectable/shareable URL; the existing `Push.ensure` and `Updater.schedule` calls remain in `MainActivity`.
- `:app:assembleDebug :app:testDebugUnitTest --offline` passed. New JVM tests executed switch payload serialization and strict typed-time parsing. `adb.exe devices -l` at the Android SDK path listed no devices, so install, launch, widget provider and logcat checks could not run; the UI was compiled and unit tested only. The quick verifier passes before this commit.
- Audit first: visually check the tall sheet and Android's material colors on a handset; exercise the link flow with a fresh session and History Undo/Redo on device. The sheet shows the first 30 matching subjects and asks the user to search to narrow larger lists. No core semantics changed, no new dependency or decision proposed.
- Device follow-up, 2026-09-23: adb saw Samsung A56 `RRCY500455P` as an authorized device. Rebuilt `:app:assembleDebug :app:testDebugUnitTest --offline` and installed the debug APK as an update (versionCode 131, previous 102). `am start -W` cold-launched `MainActivity` (713 ms, then 498 ms on the final APK) and `SearchActivity` (624 ms); both remained resumed and logcat showed no Chorus `AndroidRuntime` crash. UI hierarchy showed the expected onboarding screen and SearchActivity's empty `No members yet` state. `dumpsys appwidget` listed `QuickSwitchWidget` as a provider, with no bound widget instance. The phone was already unlocked; no unlock was attempted.
- The app had no device/account record, so switcher, History, linking, sync, push and an actual widget update could not be exercised. A temporary debug-only localhost network rule built successfully, but an automatic approval check rejected the adb command that would send a one-use isolated invite URL to the phone (no specific reason supplied). No invite was redeemed. The temporary rule was removed, the normal debug APK rebuilt and reinstalled, the `adb reverse` tunnel removed, and the isolated 5261 server stopped. The phone was returned to Home. No source or core semantics changed in this follow-up; audit the in-account UI and placed widget when a test device is enrolled.

#### T12 · done (cross-account post delivery awaits server views)
- Commit `0887b46` fixes the server SQL post projector: `post.create` carries `reply_to`, while the table uses `reply_to_id`. A regression test creates an entry and reply through ingest, verifies the link, rebuilds the projection and verifies it again. The quick verifier passed before that atomic commit. Rebuilding the isolated dev database re-projected 67 ops and restored its existing reply link in SQL.
- This web finish commit adds a Journal composer with note/entry, multiple member authors, CW, mood/tags and private/bucket/follower/server visibility; a combined system timeline of posts, front switches and pins; a member profile with Posts and Replies tabs; post reactions; and Card Stage for posts with redaction, capture and save/load. The five-section mobile nav now leads to a More page for History, Insights, People and other tools. The author controls exclude foreign or archived members.
- Svelte check and all 32 web tests passed. Playwright on 5261/5262 posted a CW entry, revealed it, reacted and reloaded to find the reaction, opened the member profile to read the body, posted a reply and found it under Replies, opened Card Stage and entered/exited capture, saved a card, and posted a two-author follower-visible note and a bucket-visible note. SQL checks confirmed two `post_author` rows and the stored `followers`/`bucket_ids` visibility JSON. A 390px browser screenshot showed five navigation links and no horizontal overflow. The quick verifier passes before this finish commit.
- Audit first: post visibility is stored, but the server's `GET /posts`, follower post view and cross-account reaction delivery are not implemented yet (API.md marks those routes not yet served). Do not assume a follower can see a new post or reaction until those privacy-scoped views exist. Review larger timeline rendering and Card Stage controls on a handset. No core semantics changed and no new decision proposed.

#### T13 · done
- Commit `3764c0a` broadens the 300-seed lossy-network convergence simulator to generate concurrent first writes and updates for all seven create-less upsert tables (`bucket`, `stage`, `feed`, `draft`, `list`, `relationship_type`, `relationship`). The incremental-projector property generator now also covers the latter five tables plus post creation/reaction. `cargo test -p chorus-core --test converge --offline` passed all 300 seeds; `projector_props` passed. An extra `CHORUS_SIM_SEEDS=1000` run passed in 553 seconds.
- A new 30-case proptest runs arbitrary binary payloads and chunk partitions over the real blob HTTP API. It checks partial offsets, stale-chunk conflicts, completed bytes, and HEAD completion. `cargo test -p chorus-server --test blobs arbitrary_chunk_partitions_resume_without_duplication --offline` passed.
- Playwright ran a two-profile follow request, owner acceptance with a Close ceiling, and a switch. After the server's 15-second settle window and a fresh People-page load, the second account saw both the delivered `New member is fronting` notification and the revealed current-front card. The first polling script stayed on the same route and missed the UI refresh; a new browser load confirmed delivery. A separate Playwright run saved a message Stage with transcript style, wide width and name redaction, reset it, reloaded the browser, loaded the saved plan, and verified all three settings plus Capture/Escape. These were runtime checks against the isolated 5261/5262 servers; the browser scripts and profiles remain in ignored `data-dev-sol/`.
- `python scripts/verify.py --quick` passed before this commit. No core implementation semantics changed and no new decision is proposed. Audit first: confirm that the random generators exercise the intended valid upsert payloads, and consider adding a checked-in Playwright fixture if a portable two-account test environment is established.

#### Journal read follow-up · done (attachments/reactions pending)
- Commit `f3ac5aa` added device-session `GET /posts` and `GET /posts/{id}` with author/kind/account/time/limit filters and nested replies. Every returned row is checked against its current audience: own, server-wide, active follower or assigned live bucket. Hidden/deleted rows return 404, ended follows lose access immediately, and unreadable reply/repost ids are masked. `front_snapshot` is omitted to preserve the delayed follower-front boundary. API tokens cannot call these cross-account routes.
- The new real-HTTP integration test uses three accounts to check private/follower/bucket/server/deleted/malformed visibility, direct 404s, nested reply filtering, author/time/limit filters, author cards, no front snapshot, authentication, and access after a follow ends. It, server clippy and `verify.py --quick` passed before the server commit.
- Server audit first: post attachment access and reaction detail. Verify whether a visible quote snapshot needs additional redaction. This read API does not change `chorus_core` semantics or a settled decision.
- The People page now reads the latest five posts per actively followed account from this API. It shows ordered author names, kind, time and text, and keeps content-warning bodies collapsed until opened. An older/offline server fails closed, and ending a follow removes that account's displayed posts from the active-follow loop. Svelte check and 32 web tests passed.
- Playwright on the isolated 5261/5262 servers verified the new web reader with two separate follower profiles: the general follower saw the two-author follower note but not a private entry or unassigned bucket note; the assigned follower saw the bucket note but not the private entry. A third browser run posted a follower-visible CW note, confirmed it was stored, then verified the follower saw a collapsed CW and could reveal its body. At 390 px width the page had zero horizontal overflow. These checks ran the UI and server, not only compilation.
- Audit first: the web reader currently uses plain text (rich entities, post attachments and reaction details are not rendered), shows only followed accounts rather than discovering server-visible posts elsewhere, and caps each preview at five. The server's post attachment blob authorization still needs an audience-aware path. No new decision proposed.

#### Journal attachment access follow-up · done
- This commit uses the same current-audience SQL for post reads and blob authorization. A referenced post attachment, including its thumbnail hash, is readable only when its post is readable to that device session; deleted posts, malformed audiences, ended follows and removed bucket assignments revoke access. Uploaders retain their existing own-blob access. No `chorus_core` semantics changed.
- The new real-HTTP blob test uploads bytes and then exercises private, server, follower and bucket audiences, an ended follow, removed bucket assignment, malformed visibility, a deleted post, and denied HEAD access. This is server runtime behaviour. The `blobs` and `posts` integration suites and `verify.py --quick` passed before the commit. No Android or browser UI was involved; the phone was unplugged for this work.
- Audit first: check that the shared `posts::readable_sql` predicate remains the single source for the post read and blob paths if post audience rules evolve. Attachment metadata and rendering remain pending. No proposed decision.

#### Journal attachment display follow-up · done
- This commit adds ordered post attachment metadata to `GET /posts` and `GET /posts/{id}` and shows follower-visible attachments in People, using the existing authenticated `AttachmentView`. A post content warning keeps its attachments inside the collapsed details; an attachment spoiler keeps its own reveal button. API values include blob and thumbnail hashes, filename, MIME, size, dimensions, alt text and a JSON boolean for the spoiler flag.
- A real HTTP post test verifies the metadata and that the private post remains absent for the follower. Server post tests, Svelte check, 32 web tests and `verify.py --quick` passed before the commit. On isolated 5261/5262 servers, Playwright used a follower profile: it saw the shared post, decoded a fetched PNG attachment, received HTTP 200 from `/blobs`, found the spoiler concealed, and revealed it on click. The test row was inserted only into `data-dev-sol/chorus.db` and removed afterward; the dev server was stopped. The phone was unplugged, so Android was not tested.
- Audit first: post attachments are rendered in the follower People preview, not yet in the owner's Journal timeline or member profile; the composer does not add post attachments. The browser check used a test-only SQL link to an existing isolated-dev blob, not a user-authored attachment op. No `chorus_core` semantics or proposed decision changed.

### Batch 4

#### Step 0 · done
- Commits `d2e97cc` and `67da8f2` finished the uncommitted journal attachment authorization and display work before merging. Both passed `verify.py --quick`; their sections above record the server and browser runtime checks.
- Merged `main` into `sol/batch-2` without switching branches. The only conflict was `web/src/lib/ui/People.svelte` imports; retained both the new `AttachmentRow` and main's `selfMember` import. Rebuilt wasm bindings and Android UniFFI/native core using the F: target directory. `verify.py --quick` and the offline Android debug build ran before the merge commit.
- Audit first: review the People import resolution and post attachment renderer with main's new person-account UI. The merge itself did not alter `chorus_core` semantics beyond those already brought from main. No proposed decision.

#### U1 · in progress
- **B4 done in this commit:** HTTP JSONL, CSV and SQLite exports authenticate briefly under the server mutex, then run in `spawn_blocking` on a separate read-only WAL connection. A read transaction keeps account/device/op rows consistent while the primary connection continues to accept writes. The CLI export path is unchanged.
- The HTTP export integration test now uses an on-disk database, and all three export routes still pass their authentication and account-isolation checks. A new test opened an export snapshot, inserted a row on the primary connection while the snapshot stayed open, and verified the writer saw the new row while the reader kept its original count. This is actual SQLite WAL behaviour, not compilation only. `cargo test -p chorus-server --test exports --offline` and `verify.py --quick` passed before the commit.
- Audit first: ensure `spawn_blocking` concurrency stays bounded under many simultaneous large exports; background export jobs are still deferred under D-060. No core semantics or new decision.
- **B5 done in this commit:** `visible_digest` returns the ordinary scope digest when every applied op in a shared scope is authored by that account. For mixed scopes it batches message, thread-channel and attachment visibility into three set queries per 1,000-op page. Live fan-out and the digest call the same `visible_with` rule, so the audience branches stay in one place. No `chorus_core` semantics changed.
- A new cross-page test compares the batched digest with applying `op_visible_to` to every op, including public and private messages, a private thread, reactions and attachments. The existing two-account `friends_share_spaces_and_dms` server test passed. The ignored manual timing test inserts 50,000 actual SQLite ops and ran successfully: 254 ms for one author and 460 ms for half mixed-author public edits on this machine; it compares each digest with the ordinary scope digest. `verify.py --quick` passed before the commit.
- Audit first: validate the 5-second manual timing threshold on slower hardware and keep `visible_with` as the single branching rule if channel permissions extend it. No proposed decision.
- **B7 done in this documentation commit:** DATA_MODEL's `message_fts` and `post_fts` definitions now match shipped migration `0001_init.sql`: both store their own content. `message_fts` is populated by the projector; `post_fts` exists but has no projection writer yet. No migration or runtime behaviour changed. `verify.py --quick` passed before the commit.
- **Search paging done in this commit:** `GET /search/messages` accepts `limit` (1–100) and an opaque cursor bound to the original search/filter values. The next page uses FTS rank, occurred time and id as a stable key; every page reapplies scope and message visibility. The web Search page requests 25 server hits at a time and exposes “Load more from server.”
- The real HTTP test paged Bob's three visible hits as 2+1, checked no duplicate/missing ids, a terminal null cursor, malformed and mismatched cursor rejection, and the existing outsider/API-token privacy cases. Svelte check and 33 web tests passed. Playwright on the isolated 5261/5262 servers seeded 27 temporary matches: the first Search page displayed 25, clicking Load more fetched a cursor URL and displayed 27. The rows were removed and the server stopped after the check. `verify.py --quick` passed before the commit.
- Audit first: the cursor captures the rank/time/id ordering within the same query; concurrent edits can move a hit between pages. The cursor is opaque but not signed, and every decoded query still goes through the full permission filter. No core semantics or proposed decision changed.

#### U2 · in progress
- **Admin health done in this commit:** device-session admins can read `GET /admin/health`; the response counts applied ops, connected WebSocket devices and pending notifications, measures DB/WAL bytes, reads the newest valid snapshot manifest for backup time and total referenced size, reports the last recorded error, and probes configured ntfy with a two-second HEAD timeout. Your data shows the panel only for admins and includes Refresh. Nightly backup errors are recorded in `server_meta.last_error`; there is no general server-error ledger yet, so `last_error` can be null even if another subsystem logged an error.
- A real HTTP test checked no auth (401), ordinary session (403), admin (200), counts, backup size/time, a recorded backup error and a reachable local ntfy probe. Svelte check and web tests passed. Playwright on isolated 5261/5262 saw the admin panel and HTTP 200 with live counts and last backup on Your data; an ordinary follower profile had no panel. The isolated server was stopped afterward. `verify.py --quick` passed before this commit.
- Disk note: F: had about 2.7 GB free before this work. I measured my isolated `F:\DunBuild\chorus-target-sol\debug\incremental` at 13 GB and verified it was inside my target directory, but automatic command review rejected removing it without giving a specific reason. I left the cache untouched. No core semantics or proposed decision changed.
- Audit first: decide whether to persist a broader last-error ledger; the current value covers nightly backup failures. Confirm ntfy HEAD behaviour against the production service, since the test used a local mock.
- **Seed command done in this commit:** `chorus-server seed --members N --switches N --messages N [--to NEW_DIR]` creates a new data directory, a system account/device and internal space/channel, then generates ordinary member, front and message ops through `ingest::accept` in 100-op transactions. It refuses an existing destination, even if empty. OPS §8 documents the invocation. This does not change core semantics.
- The CLI integration test ran the binary with 3 members, 4 switches and 5 messages, checked 14 stored ops and projection row counts, rebuilt projections successfully, then confirmed a second invocation refused to overwrite the directory. The command itself ran; the full 120,000-op default seed and SPEC §9 timing were not run because F: disk space is low. `verify.py --quick` passes before this commit.
- Audit first: check whether the 100-op transaction size is representative for manual performance measurements and run a default-size seed when there is sufficient disk. No new decision proposed.

#### U3 · deferred to Claude-owned ingest/projector work
- The channel-permission task requires changing `ingest::can_write` and the channel-permission projection in `project.rs`, both explicitly reserved in `sol-batch-4.md` for Claude's concurrent work. The existing `scope_access` grant gives a whole space, so adding read filters alone would leave an unauthorized write path and could leak channels in sync. I made no partial permission change. Claude should start with those ownership and projection points, then cover `visibility::op_visible_to`/digest, search and blob authorization with a two-account test. No proposed decision.

#### U4 · in progress
- **Visible post reactions and rich follower text done in this commit:** `GET /posts` and detail replies now include present reactions (emoji and reactor member id/name) for each already-readable post. The query only runs for rows admitted by `posts::readable_sql`; removed reactions are excluded. The People follower preview renders the post's rich text entities and reaction names. Content-warning bodies, attachments and reactions remain inside the collapsed warning.
- A real HTTP test used a Bob reaction to Alice's server-visible post: both Alice and Bob received it, while a removed Carol reaction stayed absent and Bob still could not read Alice's private post. `cargo test -p chorus-server --test posts --offline` and `npm run check --prefix web` passed. Playwright on the isolated 5261/5262 servers loaded a follower profile and saw a bold rich-text span plus a reaction from another account. That browser check temporarily changed only ignored `data-dev-sol/chorus.db` rows and restored them afterward. The web UI ran; reaction creation through cross-account ingest and replies delivered to the author were not exercised by this check.
- Audit first: validate that `post.react`/`post.unreact` ingestion rejects forged member ids and guesses of unreadable post ids. Completing that guard may need Claude-owned `ingest.rs`; also decide whether reactions should expose reactor member names to every post reader. Cross-account reply author delivery, journal profile fields, highlights, relationships, lists and feeds remain. No core semantics or proposed decision changed.

#### U5 · in progress
- **Person-account Android shell done in this commit:** the Android projection parser carries `member.is_self`, and a person account hides the Home switcher, Members and History tabs. The quick-switch widget has no tiles or actions for a person account and instead shows a short “for systems” note; the search launcher does the same. System-account screens retain their existing paths.
- Offline Gradle `:app:assembleDebug :app:testDebugUnitTest` passed. New JVM tests parse a self member and check that person accounts have no widget tiles. The phone was unplugged, so app/widget/search behaviour was compiled and unit tested, not verified on a handset. Audit first: inspect the person shell's wording and widget RemoteViews visibility on device; ensure the server-created self row arrives before showing switch controls after first enrolment. Shared spaces/DMs, attachment display, T8 controls and M9.4 Stage renderer remain. No core semantics or decision proposed.

#### U6 · done in this commit
- Added a Settings route from More. Basic contains chat notification kinds, quiet hours for the accounts one follows, and a new-follower sharing preset for system accounts. Advanced contains own-switch pings, follower history/stats sharing, CW auto-expand and speaker segment parsing. Account preferences keep their existing `pref.set` keys (`notify_chat`, `follow_ceiling`, `chat.cw_auto_expand`, `chat.segment_parsing`); quiet hours still use each active follow's `/follows/{id}/prefs` endpoint because they belong to that relationship. People and Chat now link to Settings where those account controls used to appear; per-channel and per-bucket controls stay in context.
- Svelte check passed with zero diagnostics. Playwright on the isolated 5261/5262 servers loaded Settings in a system profile, changed CW auto-expand, reloaded to verify persistence and restored it. At 390 px it had no horizontal overflow. A person profile had no system sharing controls; it saved quiet hours for an active follow, reloaded to verify persistence, then turned them off again. The first browser run exposed a bug: follow prefs PUT returns an empty body; Settings now parses an empty successful response as `{}`. Both final browser runs passed. The phone remained unplugged.
- Audit first: assess whether the default sharing preset needs clearer timing explanations. The account default still means the existing follower ceiling; per-post visibility defaults remain a separate unfinished feature. No core semantics or new decision proposed.

#### U4 · cross-account reaction follow-up done in this commit
- `visibility::related_write_allowed`, which ingest already calls, now checks that a post reactor owns the specified member and that any existing target post is readable under `posts::readable_sql` at write time. An absent target is allowed so out-of-order offline ops can converge; it reveals no post data. The People follower preview offers a 💜 reaction from the person's self member or the current front member, updates immediately, and can remove it. It remains inside a CW's collapsed detail.
- A new ingest test rejects Bob reacting to Alice's private post or forging Alice's member, accepts a reaction to Alice's visible post, and confirms Alice's post read view contains Bob's reaction. The real HTTP post test still passes. Playwright on the isolated 5261/5262 servers used a person follower profile to react to a shared note, reloaded and saw the server-returned reaction with the reactor name, removed it, and reloaded to see the removal. The test ops remain in ignored dev data, but the final reaction state is absent. `npm run check --prefix web` passed; `verify.py --quick` passes before this commit.
- Audit first: decide whether an absent target should remain allowed indefinitely or be tied to a pending local post op; test races where a visible post is made private between local reaction and sync. This server guard changes only authorization of new post reactions, not `chorus_core` semantics. Cross-account replies and the rest of U4 remain.

#### U4 · profile basics done in this commit
- Member profiles now render an uploaded banner, ordered custom field values, a pinned post, and local front/message stats (7-day and 28-day front hours, latest front, message count). The member editor uploads or removes the banner with the existing blob queue and `member.set`; profile Pin/Unpin writes `member.set.pinned_post_id`. This reads existing projection rows and reuses `frontDaily`, with no core or projector change.
- `npm run check --prefix web` passed. Playwright on isolated 5261/5262 servers used a system profile to pin a post, reload and see the pin, unpin it, upload a small PNG banner, decode it on the profile, then remove the banner. At 390 px the profile had zero horizontal overflow. The final pin and banner fields were restored to empty; only the append-only test ops and uploaded test blob remain in ignored `data-dev-sol/`. `verify.py --quick` passes before this commit.
- Audit first: custom fields and front stats were rendered from the existing projection but not edited in this browser check; inspect profile stats on large histories and their visibility before exposing cross-account member profiles. The stats are not individually hideable yet. Highlights, relationships, lists, feeds and cross-account reply delivery remain. No proposed decision.

#### U4 · local profile highlights done in this commit
- A profile Highlights tab reads the existing `highlight` set from the core projection, filtering malformed keys and removals. A profile owner can add/remove one of the posts currently present on their replica; add and remove use the same `{profile_member_id, post_id}` element payload. No core or server projector semantics changed.
- Playwright on isolated 5261/5262 servers added a highlight, saw it in the tab, reloaded and saw it persist, removed it, then reloaded to see an empty tab. The final highlight state was restored to absent in ignored dev data. A Vitest case covers present, removed, foreign-profile and malformed set keys. `npm run check --prefix web` and `verify.py --quick` pass before this commit.
- Audit first: highlight ordering and curation of another account's post are not available in the local-only profile surface; cross-account profile reads will need `posts::readable_sql` and a new view. Relationships, lists/feeds and cross-account replies remain. No proposed decision.

#### U4 · cross-account reply write guard done in this commit
- `visibility::related_write_allowed`, already called by ingest, now rejects a `post.create` reply when its existing parent is deleted or unreadable to the writer. It reuses `posts::readable_sql`, the same predicate used by reads. A missing parent remains allowed for out-of-order offline ops. This changes server authorization only; no `chorus_core` semantics changed.
- A new integration test ingested Bob's replies to Alice's private, deleted and server-visible posts. The first two returned forbidden; the visible reply was accepted. Live HTTP `GET /posts/server?depth=1` returned Bob's reply to both Alice and Bob. `cargo test -p chorus-server --test posts --offline` and `verify.py --quick` passed before this commit. Browser reply composition and author-side UI were not run in this slice.
- Audit first: assess whether an absent target should remain allowed indefinitely or require a pending parent proof. The post author can currently read cross-account replies through detail HTTP, but the web Journal still reads its local projection, so cross-account reply display remains to be added. No proposed decision.

#### U4 · cross-account reply web path done in this commit
- A follower can open an inline `PostComposer` on a shared post in People and write from their self/current-front member. The reply selects the explicit `server` audience by default and says it is visible to everyone on the server; the user can change it before posting. Journal and member profiles have an on-demand Reply thread that fetches `GET /posts/{id}?depth=1`, so an author can read replies written in another account without copying foreign ops into their local replica. It renders names, rich text, CWs and attachments and offers Refresh.
- `npm run check --prefix web` reported zero diagnostics, and 34 web Vitest cases passed. On the isolated 5261/5262 servers, two persistent Playwright profiles ran the path: the person follower replied to `Sol T12 shared note`, the composer showed server audience, and the system author opened its Journal reply thread and read the reply from the server. This was browser plus live ingest/read behaviour. `verify.py --quick` passes before this commit. The phone remained unplugged.
- Audit first: decide whether replies need a narrower explicit recipient audience than `server`; the existing visibility model has only private, follower, bucket and server modes. A private or follower reply may be invisible to the parent author, so the composer explains its default. The thread currently loads one level on demand, and People does not yet display other replies under a shared post. No core semantics or proposed decision changed.

#### U4 · local relationships and types done in this commit
- Added typed views for live `relationship_type` and `relationship` projection rows. Member profiles have a Relationships tab with a small link map, directed/inverse labels and notes. Owners can define symmetric or directed types, link their member to another local member or an external person, and remove links and unused types. New links explicitly store private visibility. This is local account UI; cross-account profile reads and account/member targets in other systems remain pending.
- Vitest covers tombstoned/incomplete rows, type symmetry and the private link shape. `npm run check --prefix web` reported zero diagnostics. Playwright on isolated 5261/5262 servers created an external link, reloaded to verify persistence, removed the link and type; a second run created a directed member link, navigated to the other member's profile and saw its inverse name, then removed it. Read-only SQL confirmed a test link and type reached the server with `deleted_at` after removal. Failed browser script setup initially left three unused test types; all three were deleted through the UI, and the final dev state has no active `Sol mentor` test types. `verify.py --quick` passes before this commit. No phone test was possible.
- Audit first: relationship type editing beyond create/delete, links to a different account's member or account, per-link audience controls, and a filtered cross-account `/profiles/{member_id}` view still need work. The current map is a small list-like graph for local data only; inspect layout with large graphs. No `chorus_core` semantics or proposed decision changed.

#### U4 · local member lists done in this commit
- Journal now has a Lists section. A system/person account can create a private named list, add or remove its local members with the existing `list.add`/`list.remove` set ops, and see posts in the current replica authored by those members. Lists and memberships use projection rows/sets; deleting a list uses the existing tombstone op. No core or server projector semantics changed.
- Vitest checks live/deleted list rows and present/removed/malformed set elements. Playwright on 5261/5262 created a list, added a member, saw `Sol T12 shared note` in the filtered timeline, reloaded and found the membership and post, then removed the member and deleted the list. Read-only SQLite confirmed the list had `deleted_at` and the membership `is_present=0`. `npm run check --prefix web` and `verify.py --quick` passed before this commit. The phone was unplugged.
- Audit first: only members and posts already in the local replica can appear; cross-account list members and server-filtered list timelines need a privacy-checked API. List sharing is not exposed yet, despite the schema visibility field. Feed filters and shareable feeds remain. No proposed decision.

#### U4 · core feed evaluator binding done in this commit
- Added a JSON batch adapter around the existing pure `chorus_core::feed::eval`: callers pass the parsed AST, candidate items and resolved member/list/date context, and receive matching indexes. The web wasm binding `feedFilter` avoids a TypeScript reimplementation of the filter language. `feed::Item` now derives serde with defaults for absent optional candidate fields; the parser and evaluator semantics were unchanged.
- A Rust test exercised a saved-list source, kind, relative time and negated tag plus an absolute date. A web test called the rebuilt wasm package and got the same selection on three items. `cargo test -p chorus-core feed_batch_filter --offline`, `pwsh scripts/build-web-core.ps1`, 37 web tests and `npm run check --prefix web` passed. `verify.py --quick` passes before this commit. This is runtime evaluation in Rust and wasm tests; no browser feed UI yet.
- Audit first: the caller must supply date starts in its chosen local timezone and a privacy-filtered candidate set; this adapter itself makes no access decision. No new decision.

#### U4 · local feed editor and preview done in this commit
- Journal has a Feeds section for creating, editing and deleting private saved filters. It stores the original query and the AST from `chorus_core::feed::parse` through `feed.set`, previews the newest 20 matches with `chorus_core::feed::eval` through the new wasm batch adapter, and displays position-aware syntax errors. Candidate items include local posts and messages, with member/group/list name resolution, relative and absolute dates in the configured system timezone, tags, mood, replies, channel, free text, fronting intervals and attachment/link hints. Users can copy query text; there is no server-sharing claim in the UI.
- New web tests filter a mixed post/message set through a named list and check New York midnight on both sides of a DST change. Svelte check reported zero diagnostics and 39 web tests passed. Playwright on 5261/5262 previewed `Sol T12 shared note`, saved and reloaded a feed, rejected `kind:banana`, previewed messages, deleted the feed, and found zero horizontal overflow at 390 px. Read-only SQLite confirmed the saved query/AST and subsequent tombstone. `verify.py --quick` passes before this commit. The phone was unplugged.
- Audit first: the preview evaluates only rows in the current replica. Cross-account posts not replicated to this account, post attachments stored solely in SQL, and other accounts' front intervals cannot fully participate. The saved `visibility` remains private; follower-readable `/feeds` and shareable feed results need a server endpoint applying `posts::readable_sql` and the equivalent message rule. Large-history feed preview costs need measurement. No `chorus_core::feed` semantics or proposed decision changed.

#### U5 · Android chat projection model done in this commit
- The Android `Model` now extracts live internal/shared/DM spaces, channels and the newest 100 non-deleted messages per channel from the core projection. Message rows carry authors, CW, structured member visibility, reply target and ordered attachment metadata (hashes, MIME, alt text and spoiler). The existing constructor has default chat values, so widget/front paths remain compatible. This is a read model only; no core semantics changed.
- New JVM tests parsed a shared channel and DM, a member-visible CW message with a spoiler image, and 120 messages to confirm the newest 100 are retained in order. The first test run failed only because its expected channel name order was reversed; the assertion was corrected and `:app:testDebugUnitTest --offline` passed. `verify.py --quick` passes before this commit. No handset was attached, so no Android UI/runtime navigation was exercised.
- Audit first: parsing still scans all projected messages before keeping the newest 100 and needs a 50k-message timing check against SPEC §9. The chat screen, attachment renderer and T8 controls are next. No proposed decision.

#### U5 · Android local chat reader done in this commit
- Added a Compose Chat tab for both system and person accounts. It navigates internal/shared/DM spaces and channels from the local projection and renders the latest 100 messages in each channel. Internal spaces have a "viewing as" member picker and a soft-privacy explanation. CW bodies and attachment names/alt text stay collapsed until revealed. A second account's old `system_only` aside is hidden locally even if an old replica still holds it. Person accounts can enter Chat while Members/History stay hidden.
- JVM tests exercise member visibility for front, co-con, explicit viewing-as and `present`, and the old foreign-aside guard. `:app:assembleDebug :app:testDebugUnitTest --offline` passed. `verify.py --quick` passes before this commit. The phone is unplugged, so navigation, layout, message rows and CW reveal are compiled but not visually or interactively checked on device.
- Audit first: shared-space foreign author cards and DM account names still need `/spaces` and `/spaces/{id}/authors` reads; attachment image preview and sending are next. Channel permission filtering depends on U3. No core semantics or new decision.

#### U5 · Android local chat composer (2026-09-24)
- Added a Compose message composer to the local Chat tab. It chooses an active member (defaulting to the primary front), routes typed text through the UniFFI `chorus_core::speaker::compose` binding for markup, sigils, proxy tags and segments, and writes `message.send` to the selected space scope. It exposes a CW, selected-member visibility inside internal spaces and system-only asides in shared/DM spaces. A shared ordinary message explicitly says its speaker is visible to everyone there. The existing notification inline Reply path was not changed.
- JVM tests cover the constructed rich-text/visibility payload and reject an internal aside; the composer function is injected in those tests because app JVM tests cannot load the Android native library. `:app:assembleDebug :app:testDebugUnitTest --offline` passes. The core bridge already has a native compose test, but this slice's UI/send operation was only compiled, not run on device.
- Device check: adb authorized the Samsung A56; the installed app opened cold and showed its enrolled Home screen. Installing this branch's debug APK failed with `INSTALL_FAILED_VERSION_DOWNGRADE` (branch version code 188, installed 206). I did not force a downgrade or replace its enrolled data. An earlier handoff said the phone had no account, but the current installed build is enrolled. This branch's composer still needs a device run on a build whose version is at least 206.
- Read Claude's newer main-only notes via `git diff HEAD..main -- docs/handoff`: `main` is 34 commits ahead. His Batch 4 audit flags leftover HTTP test data folders, reaction/front privacy wording and low F: disk space. New main message REST routes and webhooks must be covered by U3 channel permissions. I did not merge main or change branches. Audit first: check the composer layout on a handset and send an internal restricted message plus a shared aside; confirm the core compose output includes the chosen authors and the server accepts its visibility. No new decision or `chorus_core` semantic change.

#### Batch 4 audit · reaction privacy wording (2026-09-24)
- In the People shared-post preview, each React control now says that reacting shows the chosen member to post readers right away, including inside an expanded CW. This addresses Claude's Batch 4 audit note; reaction behaviour and privacy checks are unchanged. `npm run check` and `verify.py --quick` pass before this commit. The wording is compiled and typechecked; this slice has no browser interaction test. Audit first: check the note's density on a narrow phone screen. No new decision.

#### Batch 4 audit · HTTP test data cleanup (2026-09-24)
- The admin health, export, post and search HTTP tests now stop/await their server task, try to remove their randomly named data folder and sweep folders with their exact prefix on a later run once older than five minutes. The sweep accepts only 16-hex random suffixes. This addresses Claude's audit of test folders accumulating under `CARGO_TARGET_DIR` without touching other directories.
- The first implementation required immediate removal and failed on Windows (`Os code 32`) because `app::router` starts a webhook task that retains the shared SQLite connection after the HTTP task exits. The next-run sweep accounts for that handle lifetime. Four affected integration test files then passed. At the end of that run, F: had about 3.8 GB free and five current/recent export/health folders remained; they become eligible on a later run. `verify.py --quick` passes before commit. Audit first: decide whether the production router should give its webhook task an explicit shutdown handle; that is outside this test-only patch and may overlap Claude's `app.rs` work. No core semantics or new decision.

#### U5 · Android chat image preview (2026-09-24)
- Chat attachment rows now show filename and alt text, and an image can be loaded on demand from the authenticated thumbnail hash (or original hash when there is no thumbnail). Spoiler attachments initially hide filename and alt text as well as the image; revealing and hiding stays local to the row. Existing `AvatarBlobs.load` supplies the bounded 10 MB download and at-most-512-pixel decode. Non-image files still show metadata only; Android file opening, gallery and queued upload remain unfinished.
- `:app:assembleDebug :app:testDebugUnitTest --offline` passes. The phone still holds version code 206 while this branch builds 188, so this preview was compiled but not exercised on device. Audit first: run with a real image, spoiler and non-image attachment on an installed build at least version 206; check whether the shared avatar cache should be split into an account-specific attachment cache before shipping. `verify.py --quick` passes before commit. No new decision or core semantics change.

#### U5 · Android chat reply composition (2026-09-24)
- Visible chat messages now offer Reply. The composer carries the target into `message.send.reply_to`, shows a neutral reply banner that cannot reveal a collapsed CW/spoiler body, and clears the target on send, cancel or channel/space change. If the target drops out of the visible 100-message window, Send stays disabled until the reply is cancelled. The existing push-notification inline Reply still uses `data/Reply.kt` unchanged.
- The Android JVM payload test checks `reply_to`; `:app:assembleDebug :app:testDebugUnitTest --offline` passes. The phone version-code mismatch still prevents this branch's APK from running there, so UI behaviour is compiled and unit tested only. Audit first: run a reply in an internal channel and a shared space on a current-version handset, including a collapsed CW parent. `verify.py --quick` passes before commit. No new decision or core semantics change.

#### Batch 4 audit · test target fallback (2026-09-24)
- Read Claude's newer `main` audit notes without switching branches. He fixed the older HTTP tests to use the system temp directory when `CARGO_TARGET_DIR` is absent, because his Linux machine does not set it. The new `tests/common` sweep helper now follows that pattern too; our Windows verification still sets `CARGO_TARGET_DIR` to F:. `verify.py --quick` passes before commit. This is a test-only portability fix; no core or server runtime semantics changed.

#### Batch 4 audit · Feeds copy (2026-09-24)
- Claude's browser smoke test found that the Feeds hint said both "on this device" and "sync to your devices." The hint now says definitions sync while each device evaluates data it has received, matching the implemented local evaluator. `verify.py --quick` passes before commit. Copy-only; no behaviour, core semantics or decision changed.

### Batch 5

#### V2 · Android spaces and DMs (2026-09-24 evening)
- **Status: done, pending audit.** Android Chat reads the signed-in `/spaces` directory and active connections from both sides of `/follows`. Its New chat dialog starts a DM with a connected account or creates a named shared space with selected connected accounts; the header uses the other account's name for a DM and offers Leave only when the server directory says it is allowed. It reads `/spaces/{id}/authors` for public foreign member names and clears those cards on space or account changes. No `chorus_core` semantics changed and no D-S2 decision is proposed.
- **Verified by running:** `python scripts/verify.py --quick --android --offline` and `:app:assembleDebug :app:testDebugUnitTest --offline` passed. On the owner's approved, already-unlocked Samsung phone, `adb install -r` upgraded the debug build from version code 286 to 287 without clearing data; adb reverse restored 5251 to Claude's already-running demo server. The New chat dialog listed the two active demo connections. Starting a DM opened its existing `#dm` channel with the other account's name and a Leave control. A foreign author first appeared as literal `null`; explicit JSON-null checks fixed it, and the reinstalled build displayed the account name. Existing shared spaces showed Leave for a non-owner and hid it for the owner. `cargo test -p chorus-server --test sync_e2e friends_share_spaces_and_dms --offline` passed against a running test server; it covers server creation, DM reuse, leaving and author-card access.
- **Compiled/unit-tested only:** The Android Create shared space and Leave buttons were inspected on the phone but not pressed, to avoid leaving permanent demo spaces or changing the owner's existing DM membership. The server integration test exercised those operations through the same HTTP routes. Audit first: verify the Compose dialog's account selection on a phone with many connections, and test create/leave on a disposable account; check that member cards refresh when a new foreign author first posts while the space remains open. The phone's app remains enrolled, with its data intact.

#### V4 · Android People and follow relationships, first slice (2026-09-25)
- **Status: partial.** Added People as an Android tab for both system and person accounts. It reads `/follows`, requests a follow by handle, accepts or declines inbound requests through account-scope ops, unfollows through `DELETE /follows/{id}`, removes an inbound follower with `follow.end`, and opens an existing or new DM from either active direction. Following cards show only front names from the server's privacy-filtered `/accounts/{id}/view`; the screen does not use raw switches or times. Accept currently inherits the account's default ceiling. Per-follower preset controls, follower history/stats, notifications, buckets and journals remain for later V4 slices. No `chorus_core` semantics or proposed D-S2 decision changed.
- **Verified by running:** Android `:app:compileDebugKotlin :app:testDebugUnitTest --offline` and `:app:assembleDebug :app:testDebugUnitTest --offline` passed. `cargo test -p chorus-server --test sync_e2e a_friend_follows_a_system --offline` passed against the running test server. With the owner's approval, `adb install -r` upgraded the enrolled phone from version code 287 to 288 without clearing data. The five-tab bar fit its 1080 px screen; People loaded two followers and one followed account, and its Message control opened the existing DM in Chat. No follow relationship was changed on the phone. The follow request, accept, decline and remove buttons were compiled and backed by the existing server test, not pressed on the enrolled phone.
- **Audit first:** check the People screen on a person account and with many follow rows; verify an inbound request can be accepted with the default ceiling and appears as active after sync. Android currently offers no preset override when accepting. The UI intentionally omits displayed front times until it can render the server's fuzz rule without sharpening it. The server's follower view remains the only cross-account presence source.

#### V4 · Per-follower sharing presets (2026-09-25)
- **Status: done for Basic presets; V4 remains open.** Android's People screen now offers Account default, Close, Gentle, Private, Digest and Off when accepting a request and on each active follower. The JSON values come from the existing `chorus_core::api::notify_preset` through a new UniFFI export; no preset rules were copied or changed. Own `follow.ceiling` projection fields identify the current choice. Switching a preset preserves `share_history` and `share_stats` overrides, which are separate Advanced controls. Unknown custom overrides display as Custom (Advanced). A one-line explanation distinguishes delay from fuzzing. No `chorus_core` semantic change or D-S2 decision proposed.
- **Verified by running:** `pwsh scripts/build-android-core.ps1 -Debug` rebuilt ARM64 and x86 native libs and generated Kotlin bindings in ignored build outputs; Android `:app:compileDebugKotlin :app:testDebugUnitTest --offline` passed. JVM tests compare JSON structurally across key orders and check that selecting Off keeps separate history/stats flags. `cargo test -p chorus-server --test notifier --offline` passed all 12 server notification tests. With the owner's separate approval, `adb install -r` installed this APK over the enrolled phone without clearing data. People showed the existing followers' Close choice; opening the menu showed all six choices and caused no crash. I dismissed it without writing a ceiling or changing a relationship.
- **Audit first:** test saving each preset and accepting a request on a disposable account, especially a prior custom ceiling. The phone check inspected choices only. Follow history/stats toggles, buckets, per-member overrides, follower notification prefs and journal work remain. The handoff's earlier V4 note that Android had no preset override is superseded by this slice.

#### V4 · Offline Android Journal, first slice (2026-09-25)
- **Status: partial.** The new Journal tab reads own non-deleted posts from the local core projection and interleaves them with own switch events, so the timeline remains available offline. A full-screen editor writes notes and entries as a chosen own member, with optional title, CW, mood and comma-separated tags; Basic audiences are Only this account, Followers and Everyone on server. Core `parse_markup` produces rich-text entities. Reply opens the editor with the parent id. CW bodies, titles, mood and tags stay collapsed until revealed. The six-tab navigation uses one-line labels. Cross-account posts, buckets, attachments, reactions, reply threads, profiles, lists and feeds remain for later V4 work. No `chorus_core` semantic change or D-S2 decision proposed.
- **Verified by running:** Android `:app:assembleDebug :app:testDebugUnitTest --offline` passed; JVM tests check post projection/tombstone parsing, rich-text payload and rejection of a foreign member as author. `cargo test -p chorus-server --test posts --offline` passed all five live server post tests. With the owner's approval, `adb install -r` kept the phone's enrolled data. Journal showed the local switch timeline; I composed one explicitly labeled **private** demo note as Kai, saw it immediately in Journal while the app showed Live, then reinstalled the navigation fix and saw the note persist. The first phone screenshot found Journal and Members wrapping in the six-tab bar; reducing labels to one line and 12 sp fixed it on that device. Temporary screenshots and XML dumps were removed from the worktree and phone.
- **Audit first:** confirm the private test note arrived on the demo server independently of local persistence. The phone interaction and Live status alone do not prove delivery. Check that the editor keeps its text when switching tabs or the process is reclaimed, and test CW reveal plus a reply on a disposable account. The web still has more complete journal and profile surfaces; Android currently shows only the owning account's local posts.

#### Batch 5 merge and V0 · durable sync removals (2026-09-25)
- **Status: done, pending device audit.** With the owner's explicit approval, merged `main` at `03e05e1` into `sol/batch-2` as `011384e`; no main checkout, push or deployment. Rebuilt the wasm package and Android core debug bindings, built the Android debug APK, and ran `python scripts/verify.py --quick --android --offline` successfully before the merge commit. The branch was clean immediately afterward.
- Android `Store.save` now deletes each `Changes.removed` op id from Room in the same transaction as incoming ops and metadata. A new DAO batch-delete query handles the ids; an absent/empty removal list leaves ops alone. This follows `docs/SYNC.md` §6.5 repair and scope eviction and the web persistence path. No `chorus_core` semantics or proposed D-S2 decision changed.
- **Verified by running:** `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed before this commit. JVM tests exercise parsing multiple removed ids alongside incoming ops and a legacy changes object without removals. Room's generated DAO query compiled.
- **Compiled/unit-tested only:** SQLCipher deletion and restart after a lost channel were not run on the phone; the owner has taken the phone off adb for today. Audit first when a disposable channel/permission scenario and phone access are available: revoke channel view, allow repair, restart Android, and confirm the evicted messages stay absent. Do not expose the owner's private channels to set up this test. Next: resume V4 cross-account journals and profiles, then V5 per `sol-batch-5.md`.

#### V4 · readable shared posts in Android People (2026-09-25)
- **Status: partial V4, pending device audit.** Following cards now show up to five recent posts from `/posts?account=<id>`, which the server filters against current audience rules. The preview uses the server's author cards, kind and occurred time, and keeps title/body collapsed under a CW. Previews clear before refresh, when the signed-in account changes, and when loading the follow list fails. Foreign posts are not inserted into the local own-account replica. No `chorus_core` semantics or D-S2 decision changed.
- **Verified by running:** `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed after a missing Compose import was fixed. JVM tests parse a CW entry, a plain note, and nullable author/title fields. `cargo test -p chorus-server --test posts http_posts_enforce_audience_and_hide_front_snapshot --offline` passed against its test HTTP server; it covers readable and hidden cross-account posts and omission of `front_snapshot`.
- **Compiled/unit-tested only:** The new Android card layout, CW reveal and refresh were not used on a phone because the owner took it off adb for the night. Audit first: test a followed account with public, followers and private posts and revoke the follow while viewing People; check that only currently readable posts display and warnings do not expose titles/bodies. V4 still needs profile bundles, history/stats controls, journal reactions, reply threads, lists and feeds.

#### V4 · own-member profiles from Android Journal (2026-09-25)
- **Status: partial V4, pending device audit.** Tapping an own post's author now opens that member's profile. Name, pronouns, sigils, bio, groups and posts/replies/entries come from the local replica and remain available offline. When online, `GET /profiles/{member_id}` adds counts, relationships and readable highlights; highlights reuse the CW-collapsed post preview. The pane offers Post as this member and can reply to a local post. It only opens members in the signed-in account's local model. No core semantics or D-S2 decision changed.
- **Verified by running:** `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. JVM tests cover profile counts, relationship fallback names, readable highlights and the null highlights case. `cargo test -p chorus-server --test posts profiles_and_lists_read_through_the_api --offline` passed against the API implementation, including another account's profile returning 404.
- **Compiled/unit-tested only:** Navigation, scrolling and offline fallback were not run on a phone because adb is unavailable tonight. Audit first: inspect a multi-author post and a profile with many groups/relationships, then open it offline and confirm its local posts remain. This is a plain data-first screen; avatar/banner, custom fields, front-time stats, attachment media and profile editing/post highlights controls await the Figma handoff or a later V4 slice.

#### V4 · Advanced follower history and stats controls (2026-09-25)
- **Status: code complete, device audit pending.** A collapsed Advanced sharing section for each active follower toggles `share_history` or `share_stats` through `follow.set_ceiling`; `FollowPresets.withSharing` preserves the Basic preset and other permission. `Chorus.create` rebuilds the model from the local core projection immediately after the op, and People reads the new ceiling on recomposition. The subsequent sync projection follows the same path. The previous stopped-session details remain in `docs/handoff/sol-session-2026-09-25.md`.
- **Verified by running:** Android `:app:testDebugUnitTest :app:assembleDebug --offline`, the server's `shared_history_and_stats_only_contain_revealed_whole_days` test, and `python scripts/verify.py --quick --android --offline` passed before the WIP commit. A new JVM test applies a follow-row projection delta and checks both the selected flag and preservation of the other flag and preset fields; the full quick Android verifier passed again after adding it.
- **Compiled/unit-tested only:** No phone UI or real follower write was run. With an owner-approved device session and disposable follower, check an Advanced toggle, the saved ceiling after sync/restart, and that the other flag and preset stay intact. No core semantic change or D-S2 decision.

#### V4 · Android follower history and stats readout (2026-09-25)
- **Status: code complete, device audit pending.** People now parses the server-filtered `/accounts/{id}/view` once per active followed account and shows its shared fronting stats and an expandable earlier history when those fields are present. Missing fields remain absent; `shared:false` discards them. History times render only at the precision supplied by the server (`exact`, `approx`, part of day, or hidden), and the UI never derives them from own-account switch ops. The view cache clears before each refresh and on account changes or list failures, so an ended follow cannot leave an old preview on screen during loading. No core or server privacy semantics changed.
- **Verified by running:** `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed after the Android changes. JVM tests exercise absent and hidden fields, revealed names, server-provided percentages, and the no-clock part-of-day/hidden time display. The existing server `shared_history_and_stats_only_contain_revealed_whole_days` test remains the source of truth for what can enter the response.
- **Compiled/unit-tested only:** The phone was off adb. On a later disposable follow, check history expanded on a small screen and confirm a revoke clears the old history and stats after refresh. V4 still needs journal reactions and reply threads, lists and feeds, and the remaining profile details.

#### V4 · Android own-post reply threads (2026-09-25)
- **Status: first reply-thread slice, device audit pending.** Journal posts now open a reply pane. `GET /posts/{id}?depth=1` supplies replies from other accounts through the server's current audience check; own-account replies are merged from the local projection so they remain visible offline and while waiting to sync. The reply pane can refresh, writes a reply through the existing post composer, and keeps CW titles and bodies collapsed. Remote replies never enter the own-account replica. No server or core semantics changed.
- **Verified by running:** Android `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. JVM tests cover detail parsing, a CW reply, duplicate suppression after an own reply reaches the server, and offline own replies. The full verifier ran `cross_account_replies_require_a_readable_parent_and_reach_its_author` and it passed.
- **Compiled/unit-tested only:** The phone was off adb. On a disposable account later, post a reply to an own post, check it in the pane before and after sync, then have a second account reply and confirm refresh shows that readable reply. People still needs a reply entry point and reactions; lists/feeds and profile details remain.

#### V4 · Shared-post reactions in Android People (2026-09-25)
- **Status: code complete, device audit pending.** Server-filtered shared posts now include their present reaction rows. A reader can add or remove a 💜 reaction as their current own member, using the same exact set payload for `post.react` and `post.unreact`; the card updates optimistically after the local op is saved. The control and reaction names stay inside a collapsed CW. The card explains that reacting shows the member to post readers right away. A manual refresh rereads the server after sync; no foreign post or reaction is copied into the own-account replica. No core or server semantics changed.
- **Verified by running:** `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. JVM tests parse present server reactions and check the add/remove payload and local toggle. The full verifier ran the server's `post_reactions_require_an_owned_member_and_a_readable_existing_post` authorization test, which passed.
- **Compiled/unit-tested only:** No phone interaction was possible tonight. On a disposable followed account, react under a CW, refresh after sync, then remove the reaction and confirm both accounts see the change. Journal's own-post reaction readout and the People reply entry point remain, along with lists/feeds and profile details.

#### V4 · Reply to a followed account's post from Android People (2026-09-25)
- **Status: code complete, device audit pending.** A readable shared post now opens the existing Journal composer with `reply_to` set to that foreign post, a fresh draft, and Everyone on server selected so the parent author can read it. The user can change the audience, and the editor asks them to check it before posting. The same core markup and own-member author validation apply. The Reply control stays inside a collapsed CW until the reader reveals it. The server continues to enforce that the parent is readable on write. No server or core semantics changed.
- **Verified by running:** `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. A new JVM payload test checks the own author, foreign parent id and server audience. The full verifier ran the existing `cross_account_replies_require_a_readable_parent_and_reach_its_author` server test successfully.
- **Compiled/unit-tested only:** The phone was off adb. Later, reply to a disposable followed post, check the selected audience and that the author reads it, then revoke the follow and confirm a new reply is refused. Lists/feeds and remaining profile details follow.

#### V4 · Read a followed post's reply thread in Android People (2026-09-25)
- **Status: code complete, device audit pending.** The same on-demand `GET /posts/{id}?depth=1` reply pane used by Journal now opens from a followed post in People. The server checks each reply against the reader's current audience; own replies remain visible locally while offline or pending sync. The Thread control stays inside the CW, and the pane distinguishes loading from an empty thread. No new read path or privacy rule was introduced.
- **Verified by running:** `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. The existing Android reply parser/merge tests and server post audience/reply tests cover the data path.
- **Compiled/unit-tested only:** No handset UI was run. Later, open a disposable followed post with both readable and private replies, refresh, and confirm only readable replies display. Journal reactions, lists/feeds and remaining profile details follow.

#### V4 · Android Journal and thread reactions (2026-09-25)
- **Status: code complete, device audit pending.** Local `reaction` set entries are parsed into the own-account model, so an own post's reaction badges and 💜 toggle update offline after `post.react`/`post.unreact`. A post thread also reads the parent post's full reaction list from the server-filtered detail response, including reactors in other accounts; an own pending reaction is merged without duplication. The same thread control works for a followed post in People. Malformed, removed and non-post set elements are ignored. Reaction controls say the chosen member becomes visible to readers immediately. No core or server semantics changed.
- **Verified by running:** Android `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. JVM tests cover projection filtering, server reaction parsing, local/server de-duplication and detail parsing. The full verifier ran the server reaction authorization test successfully.
- **Compiled/unit-tested only:** No handset UI was run. On disposable posts, add/remove a reaction offline and after sync, then check a second account's reaction appears in the thread detail. Profile cards display local badges but do not yet offer a reaction toggle. Lists/feeds and remaining profile details follow.

#### V4 · Offline Android member lists (2026-09-25)
- **Status: code complete, device audit pending.** Journal now has a Lists section backed by local `member_list` rows and `member_list_item` set elements. An account can create a private list, select its members, delete it with a tombstone op, and read their own posts in that list while offline. The list read model ignores deleted rows, absent membership and malformed set keys. List posts reuse the existing CW-collapsed post card and reply/thread routes. No core or server semantics changed.
- **Verified by running:** Android `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. A JVM test checks live/deleted list rows and present/removed/malformed membership keys.
- **Compiled/unit-tested only:** No phone UI was run. Later, create a disposable list, add/remove two members offline, reconnect and confirm the selected posts and list membership survive restart. This is own-account lists only; feeds, cross-account profile details and list sharing remain.

#### V4 · Android saved feeds and filtered readout (2026-09-25)
- **Status: partial feeds, device audit pending.** Journal now has a Feeds section. Own saved feed definitions come from the local projection and can be created, edited or tombstoned offline; the existing `chorus_core` UniFFI `feedParse` validates the query and supplies the AST for `feed.set`. The section also lists feeds shared with the account and reads matching post pages through `GET /feeds/{id}/items`, so server audience and D-069 fronting filters govern results. A fronting warning appears when sharing or opening such a feed, and post CW bodies remain collapsed. Foreign feed definitions/items are not put into the local replica. No core or server semantics changed.
- **Verified by running:** Android `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. JVM tests check live parsed definitions, core AST payload shape, fronting warning detection, shared list and page/cursor parsing. The full verifier ran `shared_feeds_evaluate_over_what_the_reader_can_read` successfully.
- **Compiled/unit-tested only:** No handset UI or live feed request was run. Later, save a disposable feed, sync, open its first and next page, check a shared `fronting:` feed under a delayed ceiling, and inspect narrow-screen layout. Offline feed **definitions** are available, but matching feed items are currently a server read; local evaluation and search remain part of V5/V8. Cross-account profile details remain.

#### V4 · Android own-member profile banner, fields and pinned post (2026-09-25)
- **Status: code complete, device audit pending.** Own member profiles now show their avatar and cached/downloaded banner, custom fields from the existing profile bundle, and a pinned post from the local projection. Pin and Unpin write `member.set.pinned_post_id`, so the pinned post remains available offline. Banner reads use the authenticated blob client and a dedicated profile cache. No core or server semantics changed.
- **Verified by running:** Android `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. JVM tests check local banner/pinned fields and profile bundle custom values.
- **Compiled/unit-tested only:** The phone was off adb, so banner dimensions, profile scrolling and pin controls need a device run. Custom fields require the server bundle and are not yet rendered from the local replica; front-time/message stats and highlight curation remain.

#### V4 · Offline profile highlights and curation (2026-09-25)
- **Status: code complete, device audit pending.** The local model now reads present highlight set elements, ignoring removals and malformed/mismatched keys. Own member profiles show local highlighted posts offline and still add server-filtered highlights for posts outside the local replica when online. Highlight/Remove uses the same `{profile_member_id, post_id}` element, and a removal hides an already fetched remote card while sync catches up. No core or server semantics changed.
- **Verified by running:** Android `:app:testDebugUnitTest :app:assembleDebug --offline` and `python scripts/verify.py --quick --android --offline` passed. A JVM test checks valid, removed and malformed set keys and the exact add/remove payload.
- **Compiled/unit-tested only:** The phone was off adb. Later, highlight a disposable own post offline, restart, remove it, reconnect and inspect a server-only highlight. Profile front-time/message stats and local custom field values remain.
