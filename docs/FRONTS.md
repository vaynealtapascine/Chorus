# Named fronts and independent layers

Implementation brief for UI2, grounded in owner decision D-080 and the F3 Figma screens.
This is an implementation plan; unfinished items remain on `PROGRESS.md`.

## Settled behavior

- Each front has its own ordered presence set. Body is the compatibility front for the existing
  account timeline; additional fronts are named by the system, rather than a fixed set of names.
- A front can follow another front in the same system. Following uses the source's effective
  ordered members and layer identifiers, including through a chain of following fronts.
- Manual changes to a following front create an override. Source changes and ordinary settings
  edits do not clear it. Only **Resume following** clears the override while following is enabled.
- Layers are system-wide definitions with stable identifiers, editable names and configurable
  levels/order. Fronting, Co-conscious and Present are defaults. A custom layer has no implied
  fronting/co-conscious/present role. Renaming or reordering a layer never rewrites its history.
- Each front selects its authorship and switch-notification layers independently. Channel speaker
  defaults select a front, defaulting to Body. Explicit sigils still override the default speaker.
- Existing accounts retain their current behavior until configured. Person accounts continue to
  hide fronting controls. Existing notification privacy ceilings, delays and reveal rules apply.

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
3. Server storage/read paths and notifications: account ownership, migrations/rebuild, named-front
   privacy ceilings and reveal-time-only follower surfaces. New fronts must not publish their raw
   state or metadata through an unfiltered API or sync frame.
4. Web and Android: front selection, management, layer editing, following/override status, Resume
   following, switcher/history, per-channel authorship front and per-front purpose selections.
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
