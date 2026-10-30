# UI6 R1a/R1b corrective follow-up — 2026-10-30

Implementer: GPT-6.1-sol, no subagents. Checkout F:/DunBuild/chorus-ui-redesign,
feat/figma-ui-redesign, starting 228ab45. Existing UI6 claim retained.
This implements the remaining R1 corrections; independent acceptance is pending a NEW Sol-6.1
review. Slices 7/9 remain pending, 1/2/8/10 accepted, and 3/4/5/6/11 unimplemented. UI6 is not done.
No work on slice 5 or the other remaining features.

## R1a — shared explicit rich undo/redo

RichEditorField uses state-based Compose input with exact ChangeList replacement ranges.
Only an explicit undo/redo action restores older provenance, text and selection together.
Keyboard Ctrl/Meta+Z, Shift+Z/Y, IME InputConnection context actions and touch Undo/Redo controls
share RichEditorController/RichEditHistory. Native text-only undo is cleared. Ordinary selection,
keyboard and composition remain handled by BasicTextField; no text/history-match intent guess.
Profile and every message author segment use the same controller; message edit opening data and
histories are durable. Identical selected retyping is a dirty semantic edit even if text matches.
Original entity IDs, parser offsets, escaping/code and directory resolution contracts remain.
The original review's failing undo sequence is promoted into RichEditHistoryTest with explicit
event semantics, alongside selected replacement/undo/redo, branch history, repeated member/group/
custom-emoji duplicates, selection-only events, IME ranges and save/recreation regressions.

## R1b — bounded activity state and durable scoped drafts

ProfileEditorDraft retains its immutable opening baseline, field bindings and full rich data in
one encrypted replica KV record. MessageEditorDraft does the same per message/author segment;
all chat composer draft maps also moved out of saved state. DurableEditor saves only a random
reference. Keys length-prefix server/account/purpose/session scope. Coalesced background autosaves
are revision ordered; the save-state callback captures the current UI state/selection and joins
a final atomic SQLCipher checkpoint, preventing a delayed autosave from racing background kill.
Errors are visible, retry preserves data, cancellation/failure retains drafts, and confirmed
Save/Discard deletes only the exact own record. Worker-side owner guards prevent cross-account
profile/edit writes. No supported text/entity limit was reduced, cache substituted or dependency
added. History is bounded to 32 frames and a conservative 1 MiB encoded-cost budget, retaining
all current text and immutable original rich data.

Live typing maps exact changed ranges in linear time. Formatting supplies exact linear source
maps, including many-line quotes/lists/links; it does not run quadratic diff on the UI thread.
Opening rich parsing, save parsing and serialization run in the background. Parsing uses indexed
atom/literal spans instead of scanning all entities for every occurrence. No core change, native
library/binding change, WASM export or budget increase. R2/D-091 and the R3 latest reserved-ID
value guard are preserved.

## Validation checkpoint

Native final-APK verification and the complete artifact manifest are being completed by this
same implementer before handback. Evidence belongs at
F:/DunBuild/chorus-ui6-evidence/batch2/r1-followup/. Host targeted regressions already passed;
this checkpoint does not claim native acceptance or independent review.

## Wrap-up — claude-opus-5.5, 2026-10-30

Sol's usage ended before native verification and commit. Claude ran the quick/offline Android
gate (exit 0), then corrected one regression before committing: chat composer drafts used a random
per-instance reference, so every return to the Chat tab (SaveableStateHolder disposes it) reloaded
them asynchronously behind an "Opening draft…" screen and the Saver blocked the main thread on a
checkpoint when leaving; a fresh app start also lost every draft and left an orphan record. Chat
drafts are now one stable record per account (`ChatDraftCache` in NavigationDrafts.kt), cached for
the process and autosaved through the same coalescing worker. Profile and message-edit editors keep
Sol's per-session references. Accepted by the owner's request to wrap up the branch; slices 7/9 are
treated as accepted on Claude's review rather than a further Sol-6.1 review.
