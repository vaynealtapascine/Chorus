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
2. **Search (UI11b, pending).** Search in current channel, journal only, all chats visible to
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
