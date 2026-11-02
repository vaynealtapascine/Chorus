# Owner's three slices — 2026-11-02

Owner explicitly approved these changes. Implement in order, web and Android together.
The parent independently reviews, commits fixes and deploys each slice before starting the next.
Implementation agents do not deploy or change services/settings.

1. **Journal and singlets (UI11a).** Editable notes/entries/replies with server-derived edit
   history stored as compact diffs. Keep the append-only op log intact. Distinct repost and
   switch icons. Timeline previews stop at 300 Unicode characters instead of three lines.
   Singlets can post and reach their own complete profile editor (avatar, colour, all custom
   fields). Each relationship has an audience including selected privacy buckets. Targets may
   be own members, visible on-server members/fragments/singlets, or external labels. Check
   target visibility on writes and every shared read; never publish a hidden identity, its
   blobs or history. Use the existing visible-member checks. No new fragment account kind.
2. **Search (UI11b implemented; parent review/deploy pending).** Search in current channel, journal only, all chats visible to
   the current fronter, and saved/bookmarked posts. Full message cards, with one visible
   message before and after as context, and full post cards. Enforce channel/member visibility
   independently for every result and each context message; no existence leaks from counts,
   context gaps, blobs, snippets or cursors. Shared core filters and conformance where needed.
3. **Privacy buckets (UI11c, pending).** Customizable notification controls, name and icon for
   each bucket; use the same bucket presentation everywhere privacy is controlled. Preserve
   system ceilings, follower filters, delay/fuzz/digest and reveal-time invariants. Update both
   clients, specs, tests and deployment notes.

## Review/deployment protocol

- Read PROGRESS, DECISIONS, task specs and OPEN_QUESTIONS in order each session.
- Full `python scripts/verify.py` before every commit; Android changes also need `--android`.
  Windows: dot-source `F:/DunBuild/chorus-env.ps1`, use Android Studio JBR and the F: Gradle
  cache/build dirs. The old `chorus-verify.ps1` wrapper forces `--quick`; for the full gate use
  its process environment followed by `python scripts/verify.py --android --offline` directly.
- Parent review especially checks privacy across API, search, blobs, sync, export and restore;
  rebuild/convergence; offline edits; Unicode/format ranges; singlet navigation.
- Deploy using `scripts/deploy-linux.ps1` after review; backup before migrations, publish the
  matching web bundle and Android APK. Parent handles owner-phone smoke checks.

## Storage contract

The log retains complete edit payloads forever for offline convergence and restore. Compaction
only reduces the **derived** post-history duplication, not the authoritative log or backups.
Base version plus forward text splices and changed fields reconstructs history on the server;
large replacements may be stored as whole field values when smaller. Rebuild regenerates
these diffs. No new full-history text index or blob grant is created.

Migration 0018 regenerates existing derived histories from source ops inside the migration
transaction, then clears only those legacy rows successfully replaced. A database missing
source ops retains its legacy rows and endpoint fallback rather than losing history. SQLite
freelist pages are reusable; this does not shrink the database file or run VACUUM. Very large
rewrites and entity arrays can still approach full-version size. Reading history reconstructs
all its versions on demand; retention is unchanged and no pagination was added in this slice.

## UI11a implementation and review map

- `crates/chorus-server/src/post_history.rs`: linear common-prefix/suffix text splices, changed
  fields, UTF-8 boundaries, migration/backfill and reconstruction. `project.rs` writes it;
  `messages.rs` reads after the existing post read check in `app.rs`. `tests/posts.rs` covers
  long-entry savings, title/CW clearing, migration, rebuild, unchanged ops and forged edits.
- `profiles.rs`: reuse Viewer/member profile rules for target cards and each shared relationship;
  live bucket/follow and target visibility rechecked per read. Target cards carry no history,
  fronting or timestamps. `api_journal.rs` filters the own-profile REST bundle too.
  `ingest.rs` rejects foreign post/relationship mutations and unavailable relationship targets.
  `tests/profiles.rs` covers bucket and target revocation, private blobs, singlets and fragments.
- Web `PostCard`, `PostEdit`, `PostHistory`, `PostPreviewBody`, `ProfileRelationships`, friend
  profiles and singlet Journal/Settings shortcuts. Android counterparts in `ui/Journal.kt`,
  `PostEditing.kt`, `ProfileRelationships.kt`, `FriendProfiles.kt`, `People.kt`, `MemberEditor.kt`.
- Post edit source provenance preserves existing mention/emoji occurrences; native reuses the
  state-based rich editor, including formatting and undo. Web gets source offsets from core as
  a typed array, parses rich text through core, and tracks each input replacement. Shared
  `fixtures/ui_post_edit.json` covers duplicate labels and identical selected retyping.
- Owner explicitly raised the WASM limit to **500 KB gzipped** (D-108). JS stays **200 KB**.
  `verify.py` enforces both. Core only builds source maps for mapped reads; normal parses avoid
  that extra allocation. No dependency/version changes.

## Validation and deployment prerequisites

Initial full `python scripts/verify.py --android --offline` passed after regenerating stale
ignored UniFFI Kotlin bindings from the host library. The initial checkout lacked usable web
dependencies; `npm ci --prefer-offline` installed the pinned lockfile packages before validating
the implementation. Process-only Windows build environment: dot-source
`F:/DunBuild/chorus-env.ps1`; Android Studio JBR; `GRADLE_USER_HOME=F:/DunBuild/gradle`.
Never use the old wrapper's implicit `--quick` for the final gate.

Parent: independently review, fix/commit, then deploy `scripts/deploy-linux.ps1 -Android` with
the normal pre-migration backup. Regenerate matching web WASM and Android native libraries /
bindings (`scripts/build-web-core.ps1`, `scripts/build-android-core.ps1`) before publishing.
This implementation agent has not deployed, installed on a device, changed services/settings,
or run live browser/native visual checks. Owner-phone smoke checks should cover singlet
posting/profile photo/colour/typed fields; note/entry/reply editing + history/CW gates; offline
edit then reconnect; 299/300/301-character previews; and bucket/target revocation.

The new post-edit dialog drafts stay in memory until Save; existing saved posts and queued
edit ops are durable. Closing the editor discards its unsaved changes. History requires a
connection; it does not silently fall back to a potentially stale local history. Shared target
cards require a connection when choosing them; own members remain available offline. Existing
op replicas retain identities already known to their account; revocation prevents future reads
and does not erase the append-only record. Repost/switch icon overrides are intentional D-107
changes to the original Figma assets (`web/public/icons/swap.svg`, native `ic_switch_arrows`
and `ic_repost`); preserve them when re-exporting design assets.

## Final implementation gate (2026-11-02)

Passed full `python scripts/verify.py --android --offline` with the process-only Windows build
wrapper environment (no `--quick`): token/projection/API documentation checks, cargo fmt and
workspace clippy, full release workspace tests/convergence/simulators, WASM build, Svelte check
(0 errors/warnings), web tests (181 passed, 1 existing skip), production web build and bundle
budgets (about 175 KB JS / 300 KB WASM gzipped), Android lint and JVM unit tests. Final log on
this machine: `F:/DunBuild/chorus-owner-final.log`. Targeted codec/property, migration/rebuild,
profile visibility/blob, target-write and cross-client rich-edit/Unicode cases also passed.

UI11a is implemented and committed for independent parent review. UI11b/UI11c remain pending;
start UI11b only after parent review fixes and deployment of UI11a. No deployment was performed.

## Independent review fixes (2026-11-02)

Follow-up to base `66cec2917c2f3674d8c3d780708b1d3778647786`, implementing the parent's six
findings without deployment or changing the approved scope:

- Legacy compaction checks every retained version's clock, time, title, text and entities against
  the reconstructed fold before deleting anything. Missing middle ops and same-count content
  mismatches retain legacy rows. Reads merge retained versions with source-derived/new edits,
  preferring the retained contents at shared clocks. Later writes repeat the completeness check;
  rebuild leaves legacy rows for that check instead of clearing them up front. Legacy CW was
  never stored, so retained histories (including uncertain reconstructed versions) receive an
  explicit, collapsed "content warning unavailable" gate. Unedited history carries current CW.
- Web post editing records beforeinput type/selection and validates the actual replacement,
  including collapsed backward/forward deletions and UTF-16 lengths of supplementary characters
  and emoji sequences. Ambiguous input without a valid range discards identity provenance rather
  than guessing by equal text. The mention boundary rule follows core's Unicode token lexer;
  shared web/Android fixtures cover preceding letters, supplementary letters/digits, hyphens,
  dots, underscores and punctuation. Selected identical retyping remains new input.
- Every PostCard defaults to a 300-Unicode-character preview with Show more, covering profile
  Posts/Replies/Highlights/pins, Journal, Lists and local feed previews. Thread detail cards
  explicitly request full text. Remote preview cards already use PostPreviewBody.
- Singlet self editors hide switching, front-sharing, member chat-notification rules,
  system membership/folders/proxy controls and archive/trash. Android hides system-only More
  details (including Archive). Ingest refuses live person member archive/delete even before
  self creation arrives, while retaining profile edits, posting, recovery and historical restore
  replay. Existing system member lifecycle controls remain available (D-003/D-093).

Regression coverage: `tests/posts.rs` exercises migration with missing middle/all source ops and
same-count text/title/entity mismatches, repeat reads, later edits, rebuild and warned unedited
posts. `tests/ingest.rs` covers person archive/delete before and after creation, continued editing
and posting, and system lifecycle operations. `post-edit.test.ts` covers distinct IDs on repeated
emoji, forward/backward deletion of supplementary characters and whole emoji sequences, plus
the expanded `ui_post_edit.json` consumed by Android's PostEditingTest. `JournalReview.test.ts`
renders timeline/default/detail cards, profile pins/posts, Lists and the singlet/system editors.

Reproduction against the base implementation: 11 web regression failures
(`F:/DunBuild/chorus-ui11a-reproduce-web.log`); person lifecycle rejection fails
(`chorus-ui11a-reproduce-rust.log`); missing-middle migration reports zero retained rows instead
of three and unedited history returns null CW (`chorus-ui11a-reproduce-posts.log`, same directory).
Fixed targeted suites pass: 24 server integration tests, 28 web cases and Android's shared
PostEditingTest fixture (14 vectors). Initial full gate passed at
`F:/DunBuild/chorus-ui11a-review-start.log`.

Final full `python scripts/verify.py --android --offline` passed with the documented process-only
environment: documentation checks, fmt/clippy, release workspace tests/convergence, WASM build,
Svelte check (0 errors/warnings), web tests (204 passed, 1 existing skip), production build and
budgets (175 KB JS / 300 KB WASM gzipped), Android lint and JVM unit tests. Full log:
`F:/DunBuild/chorus-ui11a-review-final.log`. No quick wrapper was used.

Remaining limits: these fixes cannot recover legacy rows already deleted by an earlier migration
without a backup. Unknown legacy CW stays conservatively gated, and ambiguous browser rewrites
without a valid replacement range drop atomic identities instead of assigning them to the wrong
occurrence. No deployment, browser/native smoke check or device installation was performed.
Parent still independently re-reviews, builds matching platform libraries, deploys and performs
device checks before UI11b/UI11c.

## Second re-review fixes (claude-opus-5.5, 2026-11-02)

The re-review of 86c6aa6 left three findings; Sol's fix task stopped before writing code, so they
were fixed on `feat/links` after merging this branch (with main's web Journal work and D-109):

- **Swap rebuild dropped retained legacy history** (`project.rs`, `chorus-server rebuild`'s
  default). `post_revision` is now `RETAINED`: the swap copies it into the fresh file like the
  in-place path keeps it, and the row-count check skips it (a complete reconstruction may clear
  it). Test: `swap_rebuild_keeps_retained_legacy_history` (missing middle op; two swaps, a later
  edit, a third swap).
- **In-place rebuild stranded history of a post with no ops.** Both rebuilds now refuse before
  clearing anything when retained history belongs to a post with no applied `post.create`
  (`post_history::stranded`): the error names the posts, the database (and the swap's file) is
  unchanged. Nightly backup verification already failed for such a database (the post row can't be
  rebuilt); it now fails with this message. Test: `rebuilds_refuse_to_strand_a_posts_retained_history`.
  The account SQLite export (`exports::sqlite_copy`) also rebuilt from ops only; it now copies the
  account's retained rows for posts its log creates (`sqlite_export_carries_retained_legacy_history`).
- **Android mention boundaries** (`data/RichTextEdit.kt`): `Character.isLetterOrDigit` became
  `Character.isAlphabetic` or a Nd/Nl/No number, which is core's `char::is_alphanumeric` (and the
  web's `\p{Alphabetic}\p{N}`). `fixtures/ui_post_edit.json` gains ², Ⅰ and U+0345 appended and
  prepended. Remaining limit: Android's Unicode tables can lag Rust's for newly assigned characters.

## UI11a deployed / UI11b started (2026-11-02)

Parent deployed combined UI11a + D-109 from `521c7d2ff03b2988d9d0d9759373b7f2114b621f`
at 06:41 UTC using the existing `chorus-linux-521c7d2.tar.gz`; Android **0.1.688**.
Server healthy, migration 0018 applied, FCM active. Verified backup:
`/opt/chorus/backups/before-521c7d2/chorus-20261102-064135-c4c073a7`;
pre-migration backup `/opt/chorus/backups/pre-migrate-1793601704018.db`.
Parent fast-forwarded the unoccupied main ref from `743ae2e` to `521c7d2`.
The other main checkout remains `feat/chat-slices` and must remain untouched.

UI11b implementation started by GPT-6-sol in the initially clean deployed source checkout
`F:/DunBuild/chorus-avatars`, branch `feat/links`. No new checkout, deployment or subagents.
The interrupted alternate fixes in `C:/Users/pcuser/.t3/worktrees/Chorus/t3-e1bf1254`
are outside this task and must remain untouched. Owner authorized UI11b behavior/spec changes.
Next: implement clear web/Android scopes, canonical full cards and one independently visible
neighbor each side; test front/permission changes and hidden-result/count/cursor/blob safety.
UI11c stays pending until UI11b independent parent review and deployment.

UI11b implementation detail: all message-search callers enforce the current view; `current` is
accepted for compatibility but false cannot bypass it. Message jumps use the same guarded rich
read. Post search also orders chronologically, with no hidden-corpus rank in its cursor.

## UI11b implementation map (GPT-6-sol, 2026-11-02)

Baseline is deployed `521c7d2`; start/spec record is `2b4a702`. Work stays on `feat/links` in
`F:/DunBuild/chorus-avatars`. No alternate checkout was read or merged, no subagents were run,
and no deployment, phone install, service or system setting change was performed.

- Web: Search now has All chats / Journal / Saved / Switches. Room header/info opens This chat
  inside the room; exact `channel_id` intersects any `in:` query. Journal and Saved have scoped
  entry points. `search-view.ts` binds the existing Body presence, private-chat and member-view
  helpers to the current projection, checks permissions before sync eviction, handles inherited
  thread parents and clear cutoffs, and builds per-channel nearest-visible context once. The
  existing `Message` renderer has a read-only mode; post results use `PostCard` / `FriendPostCard`
  with canonical 300-character post previews. CW/spoilers, rich text, segments, media, forwards,
  quotes and polls remain in the canonical renderers. Open message/thread returns to detail.
- Android: room header/info opens ContentSearch inside Chat's current scene, preserving its
  drafts and list state; global and Journal/Saved entry points choose explicit scopes. Search
  documents retain their audience/account metadata rather than dropping it. `SearchVisibility`
  applies the existing ChatOrganize/member/account guards and ChannelPermissions before the
  core matcher limits results, and applies them again to every context/card. Full local message
  payloads are read on demand on Chorus's store worker, without enlarging the ordinary model's
  100-message chat windows. Remote payloads carry rich cards. Message and post results reuse
  ChatMessageCard / JournalPostCard / SharedPostPreview. JNI core still owns message query matching.
- Server: `search_view.rs` builds one ephemeral read view per request from canonical current
  Body (`api_data::current_front`), scope + `perms::can`, inherited parent availability,
  current member audience, private chats and cleared-chat cutoffs. Search filters candidates
  *before* page limits and cursor generation. Each hit has one independently guarded nearest
  chronological message each side; no gap marker or hidden anchor. Rich messages/jumps reuse
  `messages::one` and canonical author cards (`spaces::authors`), with scoped attachments,
  segments, stored quote/forward snapshots and counts-only poll totals. All message search calls
  enforce this, even `current=false`; API tokens retain own-account-only semantics.
- Post search supports `saved=true` through the account-wide bookmark pref plus ordinary live
  post audience checks before paging. Both message and post search order newest-first by time/id;
  no corpus-dependent bm25 score is exposed through cursors. Blob GET/HEAD use uniform 404 for
  missing/unreadable hashes and recheck the recipient's current message view for message grants.
  Upload ownership still returns 403; uploaders keep existing own-file access.
- Async results/cursors are scoped to query, scope, model/projection and session. Projection/front/
  permission changes immediately hide retained pages, abort the first-page read and start fresh.
  A pending local audience change takes precedence over an older server copy of the same row.

### Tests and review focus

`fixtures/ui_search.json` runs in web and Android: same-time ID ordering, skipping hidden/deleted
neighbors, current/front-empty/front-changed availability, member-private chats, inherited private
thread parents, denied channels, Journal note/entry/reply isolation and Saved narrowing. Existing
core message-search conformance remains the query contract. Web also renders real read-only
Message components with long rich text beyond the former excerpt limit and CW gates, and tests
pending local privacy against stale remote rows. Native API tests retain rich formatting/media,
per-message context and exact scope parameters. Server search tests prove adding hidden FTS rows
cannot change the visible page/cursor/context; check front removal/co-conscious availability,
private thread ancestry, exact scope intersecting `in:`, and permission revocation. Saved search
rechecks revocation. HTTP blob tests cover known-hash access before/during/after a recipient fronts,
plus existing channel/parent/follower/post-audience cases. No schema/op/dependency/version changes.

Parent independent review should emphasize the forced-current search path, soft member gates,
unreadable cursor anchors, per-context revalidation, offline pending changes, permission updates
before replica eviction, blob status/grants and async race handling. Diff from deployed `521c7d2`.

### Remaining limits / deployment prerequisites

- Local matches are capped at 100 after visibility (Saved after bookmark narrowing); online
  history pages add 25 at a time. Server filters may scan many candidates when most are hidden.
  No total-match count is emitted. Context is nearest visible in the available local replica
  when offline; windowed replicas cannot invent older neighbors. Shared posts are live reads,
  not a new durable foreign-post cache. Their presentation reflects their last authorized read
  until the next query/projection refresh; opening detail always rechecks on the server.
- The existing account-scoped op replica and own-upload rights remain soft-privacy boundaries:
  search does not erase already-held account data or cached files on a front change. Revocation
  while disconnected is learned by existing sync repair on reconnect. Search adds no grant.
- No hydrated browser or physical-device smoke check was performed in this implementation turn.
  Parent checks compact/desktop layouts and phone keyboard/Back, in-room close/jump preserving
  draft/scroll, all scopes, CW/spoilers/media/polls, offline context, front changes while a page
  is in flight, permission loss, Saved search and return-to-detail.
- Parent independently reviews and commits fixes, then builds/publishes matching server, web
  and Android artifacts from the reviewed head using the documented process environment and
  `scripts/deploy-linux.ps1 -Android`. There is no migration in UI11b; take the normal verified
  backup anyway. Do not reuse the deployed 521c7d2 bundle/APK for this source. Core interfaces
  did not change, so no new binding generation is required; normal platform build steps stand.
  UI11c starts only after this slice is reviewed and deployed.


### UI11b final gate — 2026-11-02

Full `python scripts/verify.py --android --offline` **passed**, using the process-only
`F:/DunBuild/chorus-env.ps1` environment, Android Studio JBR and F: caches. Log:
`F:/DunBuild/chorus-ui11b-final3.log`. Includes token/projection/API documentation checks,
fmt/workspace clippy, release workspace/convergence/simulator/HTTP suites, WASM build,
Svelte check (0 errors, 0 warnings), web tests (**234 passed, 1 existing skip**), production
build/budgets (**189 KB JS / 300 KB WASM gzipped**), Android lint and JVM unit tests.
The final native shared fixture also checks malformed audiences, tombstones overriding older
network cards, and a custom-role thread inheriting its parent's explicit account view grant.
Eight targeted web cases cover those adapters and real Message/Search component rendering.
Server coverage includes 5 search tests, 15 post tests and 10 blob tests, alongside the
workspace's broader API/sync/permissions/profile/rebuild suites. Existing sync end-to-end blob
assertions now expect the same unreadable/missing 404 while retaining their denial assertions.
No failure was waived. Earlier gate attempts exposed a test-harness private-method call, old
403 expectations, and a missing local in the empty-private-chat correction; all were fixed
before this final full pass. No quick wrapper was used.

Implementation is ready for the parent's independent review. The source and handoff are
committed on feat/links; read the implementation commit following 2b4a702. No deployment or
physical-device/browser smoke check was performed. UI11c remains pending.
