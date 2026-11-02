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


## UI11b independent review corrections (GPT-6-sol, 2026-11-02)

Task baseline: `759502afab0a83c4abb50692aff2a03989534d73`, existing `feat/links`
checkout `F:/DunBuild/chorus-avatars`. Start record is `9482d1c`. Only this checkout was
modified. No subagents, deployment, device install or UI11c work.

All six findings are addressed together:

1. Web Search owns each asynchronous request with a **raw** context object. Deep `$state`
   proxies cannot serve as projection identity tokens. First page, pagination, cursors,
   author cards and ancestor dependencies all require the same active context. The mounted
   real Search component is tested in headless Chromium; SSR alone missed this defect.
2. Search subscribes to both projection deltas and SyncClient's ordinary status/session
   notifications. SyncClient is a nonreactive class. A raw connection snapshot plus a fresh
   request context distinguishes disconnect/reconnect and same-query/same-cursor/session
   ABA changes. Headers and URL are captured, both request paths share cancellation, retained
   results disappear immediately, reconnect retries the unchanged query, and subscriptions
   are removed on unmount. A projection delta also invalidates the context when its object
   reference is retained. Tests deliberately resolve cancelled requests out of order.
3. Android keeps its ordinary 100-message model window. `Model.channelWindow(around=...)`,
   `Chorus.searchWindow` and `SearchJump` load a separate at-most-100-message window containing
   the chosen target. The list waits for the target before consuming `jumpTo`. Online fallback
   first reads the guarded exact target, then its surrounding page, filters each row through
   current SearchVisibility and deduplicates the target. Windows belong to model/session/
   channel; Compose cancellation and owner checks discard stale loads. Existing durable draft
   objects and per-channel list states stay in place. Current permissions also guard retained
   conversations before replica eviction. Missing-parent thread jumps use validated dependencies.
4. Both clients fetch absent thread parents through guarded `GET /messages/{id}` and recurse
   through ancestry with a cycle/depth bound. Until validation completes the result stays
   hidden. These cards are ephemeral, carry no sync grant and expire with the request/model/
   session. Existing local restrictions, malformed rows and tombstones take precedence over
   remote parents. Private/archived/denied parent channels are checked again locally. Android's
   list-only ChatOrganize guard cannot validate hydrated parents; search uses SearchVisibility
   directly instead. Web limits dependency walks to candidate channels and their ancestors.
5. Server validates eligible channels/ancestors once, then applies message audience, current
   front, token ownership and clear cutoff **inside** the FTS/filter SQL before chronological
   LIMIT. It selects bounded id/time anchors, materializing rich cards only for returned rows.
   Neighbor IDs are built once per channel using the existing channel/time index; binary
   lookup replaces repeated scans, and rich cards are cached only for this immutable request.
   No pre-visibility candidate cap, hidden rank/cursor/count or persistent cache was added.
6. Web excludes archived channels before matches/context/counts, including inherited ancestry,
   matching server and Android behavior.

### Reproduction and regression evidence

Original mounted web tests: 5 failures across real Search lifecycle, archived channels and
missing-parent visibility (`F:/DunBuild/chorus-ui11b-review-web-original.log`). A separate
raw-owner-only probe demonstrates the independent second P1: the foreign post remains after
session replacement/disconnect, there is no offline banner and there are zero status subscribers
(`chorus-ui11b-review-raw-only.log`). Original Android loader/visibility tests fail for a target
at message 10 in a 250-message conversation and for an authorized missing parent (2 failures,
`chorus-ui11b-review-android-original.log`). The original 50,000-message/one-visible-per-2,000
release regression returns the correct 25 hits but holds the DB for 2.12 seconds and fails the
150 ms SPEC first-screen budget (`chorus-ui11b-review-perf-original.log`). The independent
reviewer executable also reproduces about 2.4 seconds for 25 hits. Reviewer sources were read
from `F:/DunBuild/chorus-ui11b-review-readonly`; scratch probes/logs were written outside it.

Regression coverage includes real mounted client state (foreign render/page, disconnect,
reconnect, first-page/session/pagination races, query ABA and retained-projection invalidation),
guarded dependency fetches/404s, pending local restrictions/deletes, private/archived ancestry,
250-message native jump windows, stale remote jump targets after delete/revoke, SQL/guard
agreement for malformed audiences/cutoffs, and the release 50k budget with exact visible hits,
neighbors and pagination. CI's web job installs Chromium from the already-pinned Playwright
package for the new mounted tests. Local tests use installed Chrome; no dependency was added.

Final full `python scripts/verify.py --android --offline` **passed** with the documented
process-only environment, Android Studio JBR and F: caches. Log:
`F:/DunBuild/chorus-ui11b-review-final.log`. Includes documentation checks, fmt/workspace clippy,
full release workspace/convergence/simulator/HTTP suites, WASM build, Svelte check (0 errors /
0 warnings), **242 web tests passed, 1 existing skip**, production build/budgets (**190 KB JS /
300 KB WASM gzipped**), Android lint and **355 app + 7 core-bridge JVM tests**, no failures.
Both this fix commit and start commit 9482d1c have a preceding full Android/offline gate; no
quick wrapper was used. Final benchmark log `chorus-ui11b-review-perf-final.log` records
**71 ms sparse / 133 ms sparse with a nonempty front / 95 ms dense**, each below 150 ms, with
all 25 authorized hits and correct nearest visible context/cursor behavior. Original sparse
case: 2.12 seconds. Tests also compare SQL selection with the canonical guard on malformed
JSON, member audiences and cutoffs. There is no pre-visibility cap.

Pitfalls for the next agent: dependency validation must include retained earlier pages when
pagination introduces another channel; replacing the dependency map with only the new page's
ancestors hides older authorized hits. The mounted regression covers that case. Browser startup
and cleanup hooks allow 30 seconds (one targeted run's cleanup exceeded the default 10 seconds
while release LTO saturated the machine; the final full suite passes). Cargo workspace feature
unification leaves several differently named test binaries in the shared target directory;
use the binary named by the exact verified run when recording timings. No failure was waived.

Remaining limits: no physical-device/IME/Back/animation smoke test was performed. Parent should
check old search hits opening in-place and globally, retained drafts/list position, nested-thread
missing parents, and permission loss during a jump. Offline missing ancestors remain hidden;
there is no way to validate an absent parent while disconnected. Server still evaluates SQL
predicates over matching indexed candidates and builds an ephemeral visible timeline per hit
channel; it does not promise constant work for arbitrarily larger histories. This is not a
persistent visibility index. UI11b still needs independent re-review and deployment with matching
artifacts before UI11c. No migration, schema, op-kind or library/version changes.


## UI11b four independent re-review corrections (GPT-6-sol, 2026-11-02)

Baseline `fb7efb85a772365ac2fbbb431f142c0f11b0e323`, start record `5b58c7b`.
Only `F:/DunBuild/chorus-avatars`, existing `feat/links`; no other checkout, subagents,
deployment, services/settings, phone install or UI11c work.

- `search_view.rs` no longer defaults an invalid `Vec<String>` parse to empty. Malformed
  membership closes the channel and inherited threads before SQL selection, exact guards,
  context and attachment grants. Channel-image blob grants now use this same current view,
  rather than the older permission-only SQL branch. Valid absent/null and empty memberships
  retain their existing meaning; new malformed `channel.create`/`channel.set` writes are
  rejected by `validate_new`, while legacy `validate`/projection replay remains compatible.
  Web/native channel adapters retain an explicit invalid flag. Native's prior strict JSON
  string getter also threw on a mixed array; the safe adapter keeps the model usable while
  every affected channel remains unavailable.
- `ui/SearchRequests.kt` is the controller used by ContentSearch for first pages and pagination.
  Each remembered search/connection/projection incarnation gets a fresh owner; each operation
  also gets an identity token. Both jobs cancel on disposal, and every result/error/finally
  write checks ownership, even when transport ignores cancellation. Query/section values are
  captured before suspension; core parses query validity independently of ancestor hydration, so
  adding a later page's dependency does not discard retained pages/cursors. Ancestor producers also check cancellation and a fresh owner.
- `ui/ChatHistory.kt` owns temporary history mode and its guarded window. The always-available
  accessible latest button while in history clears the target/window; successful send/forward
  takes the same return-to-live path. Room drafts and per-room LazyListState remain owned by
  the existing durable/navigation objects. Already-authorized local history stays stable during model refresh, revalidated without old
  remote ancestor grants; history responses cannot re-enter after latest/send,
  model/connection replacement or disposal, including ignored cancellation. New messages use
  the ordinary newest-100 model once history ends.
- `SearchAncestors.load` walks only explicit candidate channel ancestry. Chat passes the target
  channel alone, so unrelated missing parents cannot delay an ordinary local target. ContentSearch
  passes only channels containing candidate local/returned messages (or its exact room), allowing ordinary
  authorized local matches before remote dependencies finish. Missing target ancestry still hides
  its thread; local tombstones/restrictions retain precedence over hydrated parents.
- The SQL member/front match uses an uncorrelated `IN (SELECT value FROM json_each(front))`
  lookup instead of expanding the identical front JSON inside a per-candidate join. Visibility
  still precedes LIMIT and context selection; the 50k/150ms requirement is unchanged.

Baseline reproduction logs: `F:/DunBuild/chorus-ui11b-rereview-original-rust.log` (server exposes
mixed membership with no fronter; core accepts malformed new writes), `...-original-web.log`
(local malformed membership becomes unrestricted), `...-original-android.log` (mixed membership
crashes parsing). Those new regressions ran before their implementation changes. The deterministic
native controller tests exercise delayed first/page success and errors, ignored cancellation,
reconnect/query ABA/projection replacement, reused cursors, return-latest/send with message250
arriving after a message10 history jump, unchanged drafts/reading position, and stalled unrelated
ancestry while a local jump succeeds. No native device or UI instrumentation smoke test was run.

Final full gate and benchmark results are recorded below when complete. Parent review should
focus on ContentSearch owner wiring/disposal, history/latest/send integration and live new messages,
malformed legacy membership across all grants, and exact target ancestry under permission loss.


### Concurrent main deployment coordination — owner relay, 2026-11-02

Claude Opus is deploying main `0ac29cc` / Android `.698` through `deploy-linux.ps1 -Android`.
This task must not deploy, package, or write shared `F:/DunBuild/chorus-linux` or PC `app/android`.
Those paths were not touched. Main and other worktrees were not overwritten/reset/merged.
More main work is forthcoming: archived chats (D-111), reply jump stack and custom folders.
**Parent MUST merge latest main into the reviewed UI11b branch, resolve semantics/conflicts and
rerun the full Android/offline gate before any next packaging/deployment.** Do not deploy this
unmerged task head merely because its isolated gate passed.

Read-only inspection of `0ac29cc` confirms new `perms::kept_to -> listed_in + with_parts` and
`tests/private_chats.rs::a_subsystem_s_chat_includes_who_is_inside_it`. New main D-110 expands a
listed subsystem through `member_subsystem:` prefs to present active nested members, never upward.
Main native `ChatOrganize.privateVisible` accepts `inside`, and `shown` supplies
`Subsystems.including`. This branch predates those helpers. Its fail-closed shape validation must
stay BEFORE expansion; valid listed members must use the merged semantics. At integration:

- Expose/reuse main's `with_parts` (currently private) for CurrentView's validated own listed
  members, including internal member chats; simply calling `kept_to` misses internal channels.
- Pass `inside = { Subsystems.including(model,it) }` from native SearchVisibility to the merged
  `privateVisible`. Reconcile web's corresponding local guard/expansion too (main's decision
  explicitly says web currently lists only named members).
- Add server/web/native search/context/ancestor/blob tests for recursive inside membership,
  removal/archive/deletion, malformed mixed lists and the never-upward case; retain recorded
  Body-front-only search semantics under the new membership relation.
- Decision-ID collision (resolved by the integration below): scoped search was originally this
  branch's D-110 and is now D-112. Main's D-110 remains subsystem chat expansion; D-111 stays
  reserved for archived chats. All active search references use D-112.

These are integration requirements from the owner's latest relay, not features merged into this
four-finding patch. No other thread was contacted and no deployment tools were run.

Package-only release benchmark (`chorus-ui11b-rereview-perf.log`, binary
`search-65219cc9d57bfc7c.exe`): 57.7546 ms sparse, 116.3804 ms sparse/nonempty front,
91.6928 ms dense. All exact hits/context/cursor assertions and the unchanged 150 ms limit pass.

The core `validate_new` change also requires freshly built matching web WASM and Android native
libraries at the parent integration/deployment step (`build-web-core.ps1`, `build-android-core.ps1`).
The ordinary verification builds WASM and host JNI for tests but is not permission to reuse the
previously deployed core binaries or to package in the concurrently occupied staging directories.

Full-workspace release test executable from this exact gate (`search-612d2eda150e474f.exe`),
run once with `--nocapture` after its suite passed: 68.3495 ms sparse, 115.0459 ms nonempty front,
104.3725 ms dense. Log `F:/DunBuild/chorus-ui11b-rereview-perf-workspace.log`. This distinguishes
workspace feature unification from the package-only executable; neither budget was weakened.


### Final four-finding gate / handback — 2026-11-02

Full `python scripts/verify.py --android --offline` **passed**, process-only
`F:/DunBuild/chorus-env.ps1`, Studio JBR and F: caches, no quick wrapper. Final log:
`F:/DunBuild/chorus-ui11b-rereview-final2.log`. Includes fmt/clippy, full release workspace /
convergence / simulator / HTTP suites, WASM build, Svelte diagnostics (0 errors, 0 warnings),
**243 web tests passed + 1 existing skip**, production budgets (**190 KB JS / 300 KB WASM gz**),
Android lint and **363 app + 7 bridge JVM tests**, zero failures/errors/skips in JVM reports.
The start commit also followed a full passing gate (`chorus-ui11b-rereview-start.log`).

The first final attempt passed release Rust but found a setup error in the strengthened web
regression (assigning `fronts.account.current` before creating `fronts.account`). The fixture
setup was corrected and the full gate rerun; no check or failure was waived. The isolated native
regressions passed before the final gate as well (`...-target-android3.log`).

All four findings are committed for parent review in the implementation commit following
`5b58c7b`. No WIP remains. Main integration/new subsystem semantics and phone/IME/Back/scroll
smoke checks remain the parent's work; the isolated task head must not be deployed before that
integration. No schema/op-kind/dependency/version change or data migration. No deployment or
packaging/staging write was performed; UI11c remains gated.


## UI11b main integration — GPT-6-sol, 2026-11-02

Integrated source is pinned to committed main `0ac29cc4d7340a7fd3a66781812ffabaf593477c`,
merged with `--no-commit --no-ff` onto `feat/links` at
`ea317fba882418ce2646506518df914f90571b39`. No separate start commit was made in the open
merge. This section supersedes the earlier instruction to perform that specific merge.

The three textual conflicts were resolved by keeping both board entries, preserving main's
D-110 subsystem decision verbatim and renumbering scoped search to D-112, and combining
Android's strict `invalidMembership` rejection with main's `inside` callback. All search spec,
API, model, client, design, sync, source and board references now use D-112. The historical
D-110 collision description above is retained as history; D-111 is still reserved.

Main's UI12a changes are present: ChorusSheet, ordered Journal coauthors, full photos/tags,
switch hairlines, spoiler bars/dark content colours and private-chat labels. Its subsystem
speaker/notification expansion and private_chats regression remain intact. No archive/reply
stack/custom-folder implementation is present in this pinned main snapshot. Current search
continues to exclude archived channels and inherited threads on server/web/native. Later
main commits must be reconciled by the parent before deployment; do not silently assume this
merge contains them.

Search canonical guards now reuse `perms::with_parts` after strict array validation, for
internal and external private channels. Optimized SQL selection/context inherit the same
allowed-channel result. Web and native eligibility apply the same recursive downward relation;
normal guarded chat jumps/lists agree. Do not expand the actual Body front or message-level
member audiences. Explicit ownership of listed members is retained after tombstones (native
Model now retains deleted member IDs); deleting the last own listed member cannot turn an
external private channel into an unrestricted one. Added subsystem parts must remain active,
nondeleted, present in a live linked subsystem group. Never expand upward.

Shared `fixtures/ui_search_subsystems.json` has 23 cases consumed by Rust canonical reads,
SQL/FTS/context, web and Android: nested/co-conscious members, present exclusion, never upward,
member/group removal/archive/deletion, link clearing, cycles, malformed mixed/nonarray lists,
archive/clear inheritance, literal message audiences and retained root ownership. HTTP blob
GET/HEAD cases independently revoke a retained hash through nested membership, thread parents,
member/group deletion, malformed lists, archives and never-upward access. Existing native
request identity/disconnect/query ABA, latest/send history-to-live and target-scoped ancestry
controllers were preserved; their regressions remain in the full suite.

Targeted checks passed: server search/private_chats/blobs (25 tests), web search-view cases,
and Android SearchVisibilityTest. The 50k package-release search timings were 74/106/83 ms
(sparse/nonempty front/dense), under the unchanged 150 ms requirement. These targeted runs
preceded the final two retained-ownership fixture cases; the full gate covers those too.
Full `python scripts/verify.py --android --offline` **passed**; final evidence follows below.
Process-only environment: dot-source `F:/DunBuild/chorus-env.ps1`, Studio JBR,
`GRADLE_USER_HOME=F:/DunBuild/gradle`, existing F: Cargo/Gradle build dirs and
`CARGO_INCREMENTAL=0`. No quick wrapper. Full log: `F:/DunBuild/chorus-ui11b-merge-full.log`.

No packaging, deployment, shared chorus-linux or PC app/android write, device installation,
service/system-setting change, other-checkout or main-ref mutation, subagent or UI11c work.
Parent independently reviews the combined implementation, checks any newly advanced main,
builds matching WASM/native libraries and release artifacts, takes the verified backup and
deploys. Phone/browser smoke checks remain pending (keyboard/Back/scroll, latest/send from
history, live arrivals, pending-page front changes, nested-membership revocation, media and
Journal author ordering). UI11c remains gated on that review and deployment.


### Final integration gate / commit handback

Full `python scripts/verify.py --android --offline` passed, with no quick wrapper or waived
failure. Includes documentation checks, fmt/clippy, release workspace tests/convergence/HTTP,
all 23 shared subsystem cases in canonical/SQL/FTS/context, WASM build, Svelte diagnostics
(0 errors/warnings), **266 web tests + 1 existing skip**, production budgets (**190 KB JS /
300 KB WASM gz**), Android lint and **365 app + 7 bridge JVM tests** (zero failures/errors/skips
in the JVM XML reports). Log: `F:/DunBuild/chorus-ui11b-merge-full.log`. The full workspace's
50k search budget regression passed without changing its 150 ms bound. The final 35-case web
search-view suite and native SearchVisibilityTest also passed independently with the two new
tombstone-ownership cases (`chorus-ui11b-merge-web-final.log`, `...-android-final.log`).

Main still resolved to `0ac29cc4d7340a7fd3a66781812ffabaf593477c` at the final gate / before
commit; no later committed main was chased. The merge commit has first parent
`ea317fba882418ce2646506518df914f90571b39` and second parent that exact main. No WIP remains.
The board is ready for parent review. The parent must independently re-review this combined
source, check for any subsequently advanced main (especially D-111/reply stack/folders and
potential new decision numbers), then build matching platform libraries/artifacts and handle
verified backup/deployment and browser/phone smoke checks. UI11c remains gated. No deployment,
packaging, shared staging/PC selfhost writes or other-checkout/main-ref changes were performed.
