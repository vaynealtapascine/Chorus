# Named fronts and independent layers

Implementation brief for UI2, grounded in owner decision D-080 and the F3 Figma screens.
This is an implementation plan; unfinished items remain on `PROGRESS.md`.

## Current implementation

Slice 1 lives in `crates/chorus-core/src/front_system.rs`. Its typed state and
`api::resolve_front_system` JSON helper implement independent ordered presence, display-only layer
levels, purpose selections, following chains, sticky overrides, Resume following and deterministic
cycle handling. `fixtures/front_system/` records the JSON contract. Rust tests also cover 64
independent layers and a 5,000-front chain without recursive calls.

The graph resolver uses dense indexes with canonical ID ordering. A separate single-start walk
checks it over 512 generated graphs (cycles, missing/deleted sources and overrides). The release
`front_system::tests::large_graph_budget` check resolves a 5,000-front chain in about 7.1 ms on
the owner's PC (2026-10-26, including D-085 purpose pools). The initial graph optimization reduced
wasm to 293 KB gz. With histories, independent channel purposes, snapshot versioning and the
runtime current projection, shared collection/sort helpers keep it about 298.6 KB gz (300 KB limit).

An unresolved source or cycle retains the saved independent set as a recovery/display fallback,
with an explicit issue and no default authorship or notification candidates. An explicit manual
override is usable even while its configured source is unavailable. UI consumers must keep the
unavailable state visible instead of treating a fallback as a confirmed followed front.

Slice 2a lives in `crates/chorus-core/src/front_history.rs`. `api::fold_front_system` folds sparse
definitions and layer edits, new independent presence changes, legacy Body operations, history
amends/retractions, own intervals/daily totals and per-front concurrent-switch reviews. It supplies canonical
source-edge clocks and sticky control state to the resolver. JSON fixtures live in
`fixtures/front_history/`. Past-history edits, undo and redo keep following active and do not
resume an existing override (D-083). This is a shared-core rule, with no client policy switch.

`speaker::default_speaker_for_front` / its JSON helper implement channel front selection (Body
when absent), explicit authorship-layer candidates and normal member/latch/person defaults.
Configured candidates use primary/order without preferring any layer name. The null legacy
selection supplies the three original layer IDs to the existing speaker rule, retaining its
co-conscious/present fallback when no sendable member is fronting. Custom layers require explicit
selection. A missing/deleted selected front supplies no automatic candidates.

Slice 2b lives in `crates/chorus-core/src/front_effective.rs`. `api::front_effective_history`
derives one front's effective changes and intervals from the whole account log. Own presence
uses corrected/amended effective time; definition, override and Resume controls use record time
with canonical HLC/id precedence. Corrected record-time reordering replays that front's known
controls in write order. Equal-time causes are batched before resolving, preventing artificial
zero-length periods. Layer/name/purpose edits do not invent presence changes.

Effective changes retain source, follow state, issue, tombstone, relevant cause IDs and legacy
record-time quality. Their entries and intervals exclude unavailable/deleted recovery fallbacks.
An override still records the immediate source's availability status. Interval start/end IDs refer
to effective change IDs, not raw own-history operation IDs. Statistics consume these intervals;
`api::named_front_daily` splits at caller-supplied timezone transitions, clamps to now and keeps
independent front/layer IDs. Own statistics can use the same helper with own intervals.

The six JSON helpers are exported through wasm and UniFFI. The web has typed wrappers in
`web/src/lib/core/fronts.ts` / `index.ts`; the shared contract has twenty front-system/history/
effective fixtures. Additional bridge tests verify that amend/undo/redo keep following
through the real Rust implementation. Android libraries are generated for ARM64 and x86-64; Kotlin
conformance uses the host DLL, without a device launch.

The catalogue and runtime current projection are connected below. Front management and purpose
controls are not exposed in either client yet. Own and effective histories are
separate APIs: do not apply today's follow source retrospectively to own history, or feed an
owner's raw effective history/causes into a follower response. Follower history/statistics must be
derived from revealed records under D-084.
The bulk fold is a conformance/history helper; client hot paths must consume the incremental
projection when it is integrated, rather than re-folding the account on every render/keystroke.

`front_projection::Index` supplies a separate current-state fold and conservative append path
for that integration. The current fold shares presence/configuration rules but omits history
rows, intervals and review scans. Newest untargeted writes can append; backdates, same-clock
writes, corrections and history controls rebuild. Rejected/removed operations always rebuild
from the surviving set. Cached append guards retain late targets and both write/time high-water
marks. The caller must establish that an appended op ID is new.

A 512-case stepwise comparison checks append-or-rebuild against the full history fold. On the
owner's PC (2026-10-25), release `front_projection::tests::current_and_live_append_budgets`
projects 10,000 switches into current state in about 39.5 ms and appends 1,000 live switches in
about 5.8 ms (2026-10-26, all four release checks run together). These are pure core measurements;
storage/transport and rendering remain separate
integration budgets.

Slice 2c connects the index to `model::Projection.front_systems` and the incremental projector's
account dependency key. The optional field is absent for legacy-only accounts; every supported
legacy and named event participates in that account key. A genuinely new single event can
append without loading the key's historical operations. Multiple changes, backdates, same-clock
writes, server stamp corrections, targeted history controls and removals use the shared rebuild.
First load retains the computed append guards rather than immediately discarding them.
Reference, incremental, snapshot-adopted and delta-only clients are compared under shuffled
mixed histories. The release runtime check loads 10,000 named switches in 174 ms and applies
1,000 later local changes plus deltas in 43.5 ms (2026-10-26). A twenty-first shared fixture
in `fixtures/front_runtime/` checks the Rust, wasm and host-JNI projection shape.

Slice 3a is `chorus-server/src/fronts.rs` and migration `0010_named_fronts.sql`. It stores
account-scoped layer/front definitions, explicit purpose selections, ordered own/effective/
candidate presence and a versioned derived current-state cache. Startup backfills supported
named-front events already present in the log; full database rebuilds regenerate all these
tables. A per-account applied-front sequence watermark detects otherwise valid caches that
miss previously unprojected operations. Legacy-only accounts retain their existing path.

The normal append helper shares the core index; backdates, corrections and late targets rebuild.
Configuration, presence and cache writes participate in the caller's ingestion transaction.
The cache is written last and is always replaceable from the append-only log. Tests cover
transaction rollback, whole-database rebuild, account isolation, opaque newer/malformed events,
source tombstones, overrides and sparse purpose edits. `v_named_front_current` contains only
confirmed effective presence, excluding unavailable/deleted recovery fallbacks. These tables
and helpers are private owner data, not a follower view. Ingestion now calls this helper inside
its savepoint; bulk rebuild still backfills once. Migration 0011 adds an index for testing whether
an account has any named events, so legacy-only reads do not load the full switch log.
Migration 0012 preserves existing presence rows/cache and checks opaque purpose-ID suffixes by
byte length: SQLite text length stops at an embedded NUL, unlike Rust/JSON.

New writes validate strict action/field shapes, UUID subject/source/target references and auto
creation-time envelopes (human presence time goes in `effective_at`). Layers/purposes remain
arbitrary nonempty string IDs. Front names and layer names cannot be blank on new writes.
The server enforces the owning account even if another account accidentally has scope access;
known foreign subjects are refused. Unknown offline parents stay in the log but cannot resolve
another account's private names or become follower-visible through that reference.

`GET /front` uses canonical Body current state once configured and adds the own-account
`front_system`. Entries carry `layer_id`; the legacy `level` field is present only for the actual
three original IDs. `since` is null until a named-history time index is connected; do not invent
a timestamp from the old Body tables. Android reads the same current field and its quick actions
and widget undo emit `front.change` for configured Body. The app retains typed configuration and
resolved pools without reimplementing following. Web/native delta merging supports field removal.

The follower pipeline consumes each visible front's resolved notification pool. Body needs no
grant; additional fronts need the follower ceiling's `fronts` grant and custom layers its `layers`
grant (D-084, NOTIFICATIONS §3). `notify::Seen.level` is a layer ID string, so the original three
keep their stored JSON while custom layers keep their own IDs; `view_layers` gates original IDs by
`levels` and custom IDs by `layers`. Each granted front has its own revealed view and queue
(migration 0014). Follower history/statistics remain Body-only. A named Body can inherit from a private source, but neither its source metadata
nor a raw cause/op ID enters the follower payload, and its state is still revealed only when due.
Grants/reveals and channel purpose consumers are integrated. Owners grant fronts and custom
layers per follower under People → Additional fronts and layers (web and Android); followers see
granted fronts beside Body.

Slice 3b is `chorus-server/src/api_fronts.rs`: on-demand owner history for the selected front.
Switches default to independently saved rows; intervals/daily default to effective history.
Explicit `history=own|effective` keeps the views distinguishable. Effective change IDs are
synthetic and not editable; raw own row IDs remain the amend/undo targets. Filtering happens
after the complete shared fold. Custom layer IDs remain independent in intervals and totals.
Daily reads clamp closed future intervals as well as open ones to now and the requested local
days, using one explicit/latest offset (IANA/DST remains unsupported by this server API).
Reviews fold per front and look up LWW resolutions from the own account log, including a
resolution delivered before its switches. Deleted fronts keep history. Unconfigured defaults
retain existing SQL shapes. Client history/Insights views are connected below, as are the web and Android profile/feed/search/review consumers, server feeds and exports;
these owner reads must never feed follower history/statistics or current/composer hot paths.

Slice 3c adds `frontSystemHistory` and `effectiveFrontHistory` to the device replica's wasm and
UniFFI bindings. They read locally visible operations directly: pending work participates and
refused/removed operations do not. These methods do not consume projection deltas or outbox
changes. The full folds are explicit history reads, never part of current projection or typing.
Web caches the own-account fold and per-front effective reads by the account's current dependency
object; Android uses a front-only revision counter on its store worker. A past correction must
invalidate that cache even when today's presence/configuration compares equal. Member/chat
changes retain it. Restore/rejection/removal and actual wasm/JNI cache tests cover that boundary.

Both History screens select an existing front (including deleted fronts with retained history),
show effective changes by default and separate editable Saved entries. Custom layers display their
own names. Time edits and undo/redo target original saved IDs through `front.change`; they never
pass synthetic effective IDs to an editing operation. Existing unconfigured Body writes retain
legacy kinds. The initial log is bounded to 200 entries with Show older; Android reads on its
store dispatcher. Browser checks include offline edit/undo/redo across reload and confirm that
following remains active. Profile/feed/search/review consumers are connected (next section); native visual rendering has not
been verified. This slice exposes read
selection and history corrections, not front/layer management or follower access settings.

The statistics adapters now accept a canonical selected-front history and an exact layer ID.
Web `namedFrontDays` and Android `InsightsData.snapshot(history=..., layerId=..., own=...)`
use the existing shared named daily helper with client timezone-transition schedules. Own and
effective intervals stay separate; other fronts/layers are excluded, and closed future ends
are clamped to now. Existing chart DTO fields named `level` retain the actual independent layer
ID, never a display level or a legacy alias. Week/pair adapters accept that same selection;
legacy calls keep their original `front` default. Empty/unavailable canonical histories do not
fall back to stale legacy intervals. Actual wasm/JNI tests cover independent quiet/front IDs,
wrong-front intervals, own/effective views, future ends, weekly/pair totals and change counts.
Insights now selects a front, exact time layer and own/effective history on both clients, using
these adapters and the shared on-demand history caches. Time-chart CSVs retain front/history/layer
IDs; whole-front change counts omit layer scope and are labelled accordingly. Day/week totals
explicitly explain member-hours. Actual-component SSR/wasm tests cover selected, empty, error and
legacy states; static layout captures cover compact/desktop in both themes. Live/hydrated Insights
and native rendering remain unverified under the recorded policy blocks. The scoped review and
verification record was `docs/handoff/ui-insights-2026-10-26.md` (in git history at a9c3c2e).

Web current and history consumers now read Body canonically once an account is configured.
`front-view.ts` `bodyPresence` (display, Chat/Search/Stage viewer filters, Members' Here badge) and
`bodyAuthors` (Body's authorship pool: journal, post composer, People reactions) replace legacy
`projection.fronts[...].current` reads; custom layers keep their IDs, so the viewer filter's
front/co-conscious rule never maps them into a role. Profile hours/last fronted, Journal switch
items and feed `author_fronting` read Body's effective intervals/changes on the exact `front`
layer ID; Journal search reads Body's saved records (with notes) and refreshes on a
`front_systems` delta. A configured account whose history is still loading shows nothing rather
than the stale legacy timeline. Named concurrent-switch review cards appear on the History screen
for the selected front (the fold that already serves it) and resolve through `front.change`
retract/switch plus `front.review_resolve`; Home shows legacy cards only, keeping the bulk fold
off Home's current path. Android's `model.current` was already canonical Body; its notification
reply speaker now uses Body's authorship pool, and Journal switch items and local switch search read
Body's effective changes / saved records through `Chorus.frontHistory` (refreshed by
`frontHistoryRevision`), again showing nothing while loading. Android has no review cards or profile
front statistics, and its saved feeds are evaluated by the server. Server feed `fronting:` for the
owner folds Body's effective history once per evaluation (exact `front` layer ID); followers keep
their revealed states. Exports add `named_switches` / `named_front_intervals` (DATA_MODEL §7.1),
folded from own ops: each front's saved records and own intervals, never inherited history.

Server current readers: the REST message default author (`messages.rs` `default_author`) uses the
authorship pool of the channel's selected front for a configured account (owned members only; an
explicit empty selection selects nobody). Own-switch notifications (`notify_chat.own_switch`) also
fire for Body `front.change` presence edits (not `history_only`, not other fronts); a retract by
latest HLC silences a pending one, and the text names Body's Fronting layer by its exact ID and
every other layer by its own name.

Saved web UI projections now carry core's independent `ProjectionDigest {version, ops}`.
Compatibility is checked before the first cached display and again when adopting the indexed
ops. Old unversioned, malformed and future-version caches rebuild without discarding operations;
compatible caches keep the existing fast opening path. `PROJECTION_VERSION` is now 2 for the
runtime named-front field/kinds. Bump it for later shape/recognition/semantic changes even if
the saved op identities/stamps match.
The network scope digest and server SQL cache version are separate contracts.

## Fronting display (D-122, Figma F3)

Display choices are account prefs, so no server change and no new op kinds:

- `fronting.display` (one value, last write wins, like `chat_folders`): `layers` maps a layer ID
  to `{style: solid|dashed|faint|plain, about, separate, color}`; `fronts` maps a front ID to
  `{icon, about, hidden, preset: simple|detailed|complete, hidden_layers, hide_empty,
  collapse_away, show_time, show_others}`; `order` is the fronts' order; `hint_dismissed`. The
  pseudo-layer `away` (never a real layer ID) may appear in `hidden_layers`. Missing values fall
  back to: the first nested layer solid, the second dashed, the rest faint, separate layers plain;
  the original three layers have built-in descriptions; Body's icon is `target`, a template's
  name picks its icon, otherwise `layers`; Detailed with every switch on.
- `member_status:<member id>` = `{tags, note, at, clear_on_leave, text}` or null: a member's states while here
  (F3.10). `tags` are built-in keys (`tired`, `nonverbal`, `busy`, `low_spoons`, `needs_quiet`, `out_and_about`),
  `state:<custom state id>` or the member's own words; `text` is the joined line, kept for older readers. An
  older `{text, at}` reads as a note. With `clear_on_leave` the state stops showing once they left and came back.

Parsers: Android `data/FrontingDisplay.kt` (with `FrontScene`, `FrontStory`), web
`lib/fronting-display.ts`; both have matching unit tests (`FrontingDisplayTest`,
`fronting-display.test.ts`), including the history sentences. Nothing here changes layer meaning
or purpose pools: reordering layers on Layers & display writes `front.layer` levels, and
"Suggested speakers" writes the front's `authorship_layers`.

Fronting now reads the shown front's effective history to show when each member arrived; both
clients cache it per front revision (Android `frontHistoryRevision`, web `FrontHistoryCache`), so
it folds again only when fronts change, never per render or keystroke.

Placement (member sheet, Add-mode switch sheet, a layer's +) writes `front.change`
add/update/remove (legacy accounts: `front.add`/`update`/`remove`), several as one undo (and
whatever was written stays undoable if a later write fails). Leaving the innermost layer sends
`is_primary: false` (core keeps the flag otherwise, so the default speaker would stay) and the
first to enter an innermost layer with no primary gets `true` (`withPrimary` on both clients).
The Online template follows Body; the first front an unconfigured account adds asks first,
since it moves Body onto named fronts. A legacy review Merge is a backdated, silent switch. Log a
past switch writes a `history_only` switch at From and, unless a saved change already lies in
(From, Until], another at Until restoring what was saved then (legacy accounts: backdated silent
`front.switch` ops).

## Event contract

All events are private to `account:<system id>`. Body's stable front ID is the account ID.

| Kind | Key and payload |
| --- | --- |
| `front.define` | `entity_id` = front ID; sparse `{name?, follow_source?, authorship_layers?, notification_layers?, purpose_layers?, deleted?}` |
| `front.layer` | `{layer_id, name?, level?, deleted?}`; layer IDs are system-local, with string IDs `front`, `cocon`, `present` for the defaults |
| `front.change` | `entity_id` = front ID; `{action: switch/add/remove/update/resume/retract/unretract/amend, ...}`; op ID identifies the history item |

Definitions and layers merge per field by HLC. Missing fields leave the existing value untouched.
`follow_source: null` disables following. An explicit empty purpose selection selects nobody;
null restores the legacy purpose default (including its original-layer authorship fallback).
New fronts initially select `front` for authorship and
no notification layers. Tombstones keep the own history and identifiers intact.

D-085 adds `purpose_layers` to front definitions/state: a sparse map of purpose ID to selected
layer IDs or null. `mentions`, `reading` and `chat_alerts` each default to the original
front/co-conscious IDs when missing/null; `[]` selects nobody. Entries merge independently by
purpose and HLC, so editing one never overwrites the others. Future purpose IDs are accepted but
default to nobody; they do not acquire a role from their name. Authorship and switch alerts keep
their existing `authorship_layers` / `notification_layers` fields, which cannot be duplicated as
keys in this map. Each resolved front supplies `purpose_candidates`; following uses the source's
presence with the destination front's selections. Unavailable/deleted fallbacks qualify for none.

`reading::readers_for_front` / `api::read_readers_for_front` consume the selected reading pool
without mapping any custom layer into a legacy role, filter to own member IDs, retain order and
mark the account once.

Channel consumers are connected. A channel's front is the private account pref
`authorship_front:<channel id>` (a front ID string; Body when absent), separate from
`autoproxy:<channel id>`. `front_system::purpose_members` reads one purpose pool of that front.
Both composers call `default_speaker_for_front` with it, and per-member reads use its `reading`
pool through `read_readers_for_front` (web `channel-front.ts`, Android `ChatSpeaker` /
`ReadTracking.readersFor`); unconfigured accounts keep the legacy rules unchanged. On the server,
`fronts::purpose_members` resolves `@front` mentions from the `mentions` pool and the
"only while fronting" chat rule from the `chat_alerts` pool, for the mentioned/recipient
account's own choice of front in that channel. When front events were recorded after the
message time, it re-folds only the ops recorded by then (configuration and presence as they
stood at message time); otherwise the cached current state is used. Channel settings show a
front picker only when the account has more than one live front.

New presence entries use `{subject_type, subject_id, layer_id, is_primary}`. Switch replaces the
own ordered set; Add inserts/moves a subject at an absolute optional position (default end);
Remove removes it; Update changes its layer/primary/optional absolute position without otherwise
moving it. No action infers a custom layer's meaning from its name or display level.
Oversized positions retain end-of-set behavior after a portable wire normalization, so 32-bit
wasm and 64-bit native builds accept the same operations. Legacy layer-relative positions follow
the same rule without overflowing the layer's starting offset.

Amend/retract/unretract targets are isolated to the named front, including when the target arrives
late. The latest amendment bundle wins, matching the legacy timeline. Own presence is folded by
amended human time, HLC and op ID; sticky following controls use write order, so backdating a new
switch does not move it before an already configured follow source or a later Resume command.
Review cards compare concurrent own-presence outcomes only within the same front.

Presence changes may include `effective_at` (an integer timestamp) and `history_only` (a boolean).
These fields apply only to Switch/Add/Remove/Update. New writers keep the envelope in auto time,
so the server corrects the **record time**, and put an explicitly chosen presence time in
`effective_at`. A missed entry inserted with `history_only: true` changes saved presence without
pausing following or resuming an override. Amend/undo/redo always have that control behavior.

History rows include `recorded_at` when it differs from the effective/amended time. Old
`time_source: user` envelopes have no corrected creation time: the core uses HLC physical time
bounded by server receipt as an estimate and marks `recorded_time_estimated: true`. This estimate
must remain distinguishable from a corrected record time. A later server stamp can change an
auto record time without moving the presence's explicit effective time.

Legacy operations still affect Body only. Legacy Add/Update preserve unrelated custom entries
and their relative order while retaining the old default-layer position/primary rules. A legacy
full switch remains an explicit Body replacement. Named fronts cannot overwrite one another.
The randomized Body-bridge test compares complete outcomes, intervals, notes and reviews against
the original fold over 512 generated histories. In particular, an update of an absent member is
a complete no-op and must not clear the current primary member during the compatibility merge.

The core parser ignores other account scopes, unknown versions and purged payloads; malformed
events remain unprojected. The catalogue, new-write validation and current-state storage now
recognize these kinds. Do not expose management UI until named history, purpose consumers and
follower grants/reveals are integrated as well.

The explicit release-mode `front_history::tests::ten_thousand_own_switches` check folded 10,000
pending switches over 500 member IDs in about 76 ms on the owner's PC (2026-10-26). Pending/single-
device histories skip the concurrent-review pair scan. This checks a bulk own-history fold;
append, transport, widget and cold-start budgets still need checks at runtime integration.

Effective history is checked against an independent time-slice reconstruction over 512 generated
histories with reordered record/effective/write times, sparse definitions, cycles, deletions and
history corrections. The separate release `front_effective::tests::ten_thousand_followed_switches`
benchmark checks 10,000 followed switches against the 300 ms history-read ceiling. The bulk
check took about 173 ms on the owner's PC (2026-10-26). The bulk history path can replay controls
on out-of-order record times; do not use it for current-state updates or rendering.

## Settled behavior

- Each front has its own ordered presence set. Body is the compatibility front for the existing
  account timeline; additional fronts are named by the system, rather than a fixed set of names.
- A front can follow another front in the same system. Following uses the source's effective
  ordered members and layer identifiers, including through a chain of following fronts.
- Manual changes to a following front create an override. Source changes and ordinary settings
  edits do not clear it. Only **Resume following** clears the override while following is enabled.
- Editing, undoing or restoring past history does not pause following or resume an existing
  override (D-083). It changes the saved own history.
- Layers are system-wide definitions with stable identifiers, editable names and configurable
  levels/order. Fronting, Co-conscious and Present are defaults. A custom layer has no implied
  fronting/co-conscious/present role. Renaming or reordering a layer never rewrites its history.
- Each front selects its authorship and switch-notification layers independently. Channel speaker
  defaults select a front, defaulting to Body. Explicit sigils still override the default speaker.
- A channel's selected front also drives `@front` mentions, per-member read tracking and
  fronting-dependent chat alerts. Those three purposes each have independent per-front layer
  selections; legacy defaults use the original front/co-conscious IDs (D-085). Historical
  mentions/alerts resolve the selected front and its purpose configuration at message time.
- Existing accounts retain their current behavior until configured. Person accounts continue to
  hide fronting controls. Existing notification privacy ceilings, delays and reveal rules apply.
- Existing followers can access Body only until the owner explicitly allows additional fronts in
  their visibility settings. Selecting notification layers does not grant front access (D-084).

## Model boundary

Keep these concepts distinct in the shared core:

| Concept | Data |
| --- | --- |
| Layer | Stable ID, name, display level/order, tombstone |
| Front definition | Stable ID, name, optional follow source, selected authorship/notification layer IDs, tombstone |
| Own presence | Ordered subject IDs, layer IDs and primary selection written for this front |
| Following state | Independent, following, overridden, or source unavailable; derived from recorded controls |
| Effective presence | Own presence or the followed source's resolved presence |
| History | Own operations and effective changes; retain the cause of changes inherited from another front |

Display level/order is not an authorship or notification role. Those purposes use explicit layer
ID sets. An empty set means no layer is selected. A configured empty authorship pool must not fall back
to the only active system member. Only a person account's self member keeps that fallback
(D-003); unconfigured/legacy consumers keep their prior defaults. Server REST message authors
and Android inline replies follow this rule. A missing legacy setting keeps the old behavior.
Layer and front deletion are tombstones; historical entries retain their identifiers and names
can still be resolved for deleted definitions. No presence data is silently removed.

## Compatibility and sync

New named-front operations must have new kind names. Adding only a `front_id` field to existing
v1 `front.switch` would be unsafe: an older core ignores unknown payload fields and would apply
the switch to Body. Continue accepting legacy operations as Body changes on upgraded clients.

Build the new state in `chorus-core`, then expose it through the incremental projector and both
bindings. UI code must not implement following, override precedence or layer-purpose selection
independently. Reference and incremental projections must agree for shuffled and rejected ops.

Follow graphs must converge even if two offline edits make a cycle. Use a deterministic cycle
guard, with the ignored edge visible to the owner, rather than recursion failure or lost ops.
Manual state and its history stay available when a source is deleted or unavailable.

Keep the legacy projection unchanged for unconfigured systems. Extend the server with rebuildable
projections and a forward migration, retaining the existing pre-migration backup path. Neither
old op-log entries nor user-authored history may be rewritten.

## Delivery slices

1. Shared-core types and resolver: independent sets, configurable layers, follow chains, sticky
   overrides, Resume following, purpose selection and deterministic cycle handling. Tests cover
   the compatibility defaults and arbitrary custom layer counts.
2. Event/history fold and incremental projection: new operation catalogue, legacy Body bridge,
   backdates/amends/retracts, effective intervals/statistics, snapshots and conformance fixtures.
   Effective history needs both corrected operation/record time and presence effective time;
   do not substitute HLC physical time for corrected human time. Keep history-only corrections
   separate from live override controls (D-083). Invalidate incompatible saved projections when
   adding a new projection shape, including previously opaque named-front operations. Keep
   current-state updates small and history reads separate from render/composer hot paths.
3. Server storage/read paths and notifications: account ownership, migrations/rebuild, named-front
   privacy ceilings and reveal-time-only follower surfaces. New fronts must not publish their raw
   state or metadata through an unfiltered API or sync frame. Existing own-account Body read
   APIs/widgets must read canonical Body state after new-kind Body changes; leaving them on an
   old-kind-only timeline would report stale data. Never translate custom IDs into legacy roles.
   D-084 requires explicit additional-front grants per follower; preserve existing Body visibility.
4. Web and Android: front selection, management, layer editing, following/override status, Resume
   following, switcher/history, per-channel authorship front and per-front purpose selections.
   Store channel front choice as a separate private account pref (`authorship_front:<channel id>`)
   so changing the existing `autoproxy:<channel id>` mode does not overwrite it, or vice versa.
5. Cross-client tests and visual checks, then integration with the original checkout while
   preserving its unrelated local-search work.

## Required verification

- Independent fronts keep different membership/order and never overwrite one another.
- Custom layers remain independent even when renamed, reordered or given default-like names.
- Following chains update; manual overrides survive source changes/reloads/sync; Resume follows
  the current source. Cycles and unavailable sources preserve data and produce a usable state.
- Authorship and notification layer selections are separate and per front. Channels can choose
  different fronts. Legacy account/channel defaults and explicit multi-sender sigils still work.
- Event permutations, duplicate delivery, offline edits, late parents, retractions and rebuilds
  converge. Backdated records and inherited changes produce explainable effective history.
- A follower cannot learn a new front's state, member identities, switch time or metadata earlier
  than its allowed reveal. Test REST, sync, SSE, history, statistics and notification delivery.
- Check send/switch and initial-load budgets with representative history; use the same fixtures
  through wasm and UniFFI. Run `scripts/verify.py` before each commit.


Own-switch notifications check canonical Body saved-history liveness once configured, including
legacy targets and mixed legacy/new retract/unretract controls. A legacy `switch.retracted` row
is no longer authoritative for those accounts. Undo before settling stays quiet; restoring before
settling retains the pending ping. Unconfigured accounts keep the legacy SQL read.


Named-front review Merge is a historical replacement using existing operations: append the union
of both saved resulting sets as `front.change switch` with `effective_at` equal to the later
reviewed effective time and `history_only: true`, then retract both original saved IDs and
resolve the card. The originals remain in history/log. This preserves later saved presence and
both following and an existing override (D-083); it creates no current-time manual switch.
An amend can replace entries only on a switch, while reviews can include add/remove/update
rows, so a history-only replacement handles all sides consistently without a new op. Missing or
already-retracted sides cannot merge. Unconfigured legacy review behavior is unchanged.
