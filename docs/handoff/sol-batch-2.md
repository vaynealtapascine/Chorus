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
