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
`front_system::tests::large_graph_budget` check resolves a 5,000-front chain in about 3.35 ms on
the owner's PC (2026-10-25); the wasm build is 293 KB gz after this optimization, within 300 KB.

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

The three JSON helpers are exported through wasm and UniFFI. The web has typed wrappers in
`web/src/lib/core/fronts.ts` / `index.ts`; Rust, wasm and generated Kotlin all run the same ten
front-system/history fixtures. Additional bridge tests verify that amend/undo/redo keep following
through the real Rust implementation. Android libraries are generated for ARM64 and x86-64; Kotlin
conformance uses the host DLL, without a device launch.

These helpers are not wired into the operation catalogue, incremental projections, storage or UI
yet. The running clients still use the legacy single front. Effective/inherited
history and its statistics also remain pending: the new intervals describe **own presence only**.
Do not apply the current follow source retrospectively to own history to infer effective history.
The bulk fold is a conformance/history helper; client hot paths must consume the incremental
projection when it is integrated, rather than re-folding the account on every render/keystroke.

## Event contract implemented in core (not yet enabled for ingestion)

All events are private to `account:<system id>`. Body's stable front ID is the account ID.

| Kind | Key and payload |
| --- | --- |
| `front.define` | `entity_id` = front ID; sparse `{name?, follow_source?, authorship_layers?, notification_layers?, deleted?}` |
| `front.layer` | `{layer_id, name?, level?, deleted?}`; layer IDs are system-local, with string IDs `front`, `cocon`, `present` for the defaults |
| `front.change` | `entity_id` = front ID; `{action: switch/add/remove/update/resume/retract/unretract/amend, ...}`; op ID identifies the history item |

Definitions and layers merge per field by HLC. Missing fields leave the existing value untouched.
`follow_source: null` disables following. An explicit empty purpose selection selects nobody;
null restores the legacy purpose default (including its original-layer authorship fallback).
New fronts initially select `front` for authorship and
no notification layers. Tombstones keep the own history and identifiers intact.

New presence entries use `{subject_type, subject_id, layer_id, is_primary}`. Switch replaces the
own ordered set; Add inserts/moves a subject at an absolute optional position (default end);
Remove removes it; Update changes its layer/primary/optional absolute position without otherwise
moving it. No action infers a custom layer's meaning from its name or display level.

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
events remain unprojected. Ingestion must add catalogue and value/ownership validation together
with storage projection before clients emit these kinds. Do not enable the kinds alone.

The explicit release-mode `front_history::tests::ten_thousand_own_switches` check folded 10,000
pending switches over 500 member IDs in about 46 ms on the owner's PC (2026-10-25). Pending/single-
device histories skip the concurrent-review pair scan. This checks a bulk own-history fold;
append, transport, widget and cold-start budgets still need checks at runtime integration.

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
ID sets. An empty set means no layer is selected. A missing legacy setting keeps the old behavior.
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
