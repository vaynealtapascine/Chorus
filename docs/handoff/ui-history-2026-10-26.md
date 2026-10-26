# UI2 History handoff — 2026-10-26

Worktree: `F:/DunBuild/chorus-ui-redesign`, branch `feat/figma-ui-redesign`.
Source inspected over base commit `8da383e1816a7b2cbd92962ed13946f67c4d5bdc`, with the
History implementation still in the working tree. This records an ordinary extension of the
existing Operate world. UI2 remains in progress.

The supplied fresh finish-review result is **VERDICT PASS / disposition SHIP for the two
scored fixes**: both resolved, no regression evident, no material fix remaining in that set.
This is a bounded review result, not approval of every UI2 surface or native rendering.

## Scope and correctness

Primary artifacts:

- Web: [History.svelte](../../web/src/lib/ui/History.svelte),
  [App.svelte](../../web/src/App.svelte), [front-view.ts](../../web/src/lib/front-view.ts),
  [front-history.ts](../../web/src/lib/front-history.ts).
- Android: [data/NamedHistory.kt](../../android/app/src/main/kotlin/garden/vayne/chorus/data/NamedHistory.kt)
  and [ui/History.kt](../../android/app/src/main/kotlin/garden/vayne/chorus/ui/History.kt),
  with store/action integration in `data/Chorus.kt` and `data/Front.kt`.

Both clients read full locally visible shared-core histories through replica
`frontSystemHistory` / `effectiveFrontHistory` methods in wasm/UniFFI. Pending operations
participate; refused/removed operations are excluded. Explicit history reads stay separate from
current projection, current-front rendering and composer paths. Web caches by the account's front dependency;
Android caches by a front-only store revision. Past corrections invalidate history even when
current presence stays equal; unrelated member/chat changes retain the cache.

History selects existing fronts, including deleted fronts with retained records, and displays
actual layer names. **Front history** defaults to read-only effective transitions, including
inherited changes. **Saved entries** exposes original own-record IDs for time edits, Undo and
Redo; synthetic effective IDs never become edit targets. Configured accounts write
`front.change`; unconfigured Body keeps legacy operations. Web uses `datetime-local`; Android
uses native date/time dialogs. The initial log renders 200 rows, with Show older entries in
200-row increments; this does not truncate the shared-core fold.

D-080/D-083 remain authoritative: fronts/layers are independent; optional following uses
resolved presence; manual overrides persist until Resume following. Saved-history edits,
undo and redo neither pause following nor resume an existing override. The shell's Body footer
now reads canonical confirmed effective presence and actual layer labels through `frontView`.
Deleted/unavailable fronts show explicit states. This display list does not define authorship,
mention, reading or notification eligibility; those retain separate purpose pools.

## Comparison with the existing design system

Authority: [PRODUCT.md](../../PRODUCT.md), [DESIGN.md](../DESIGN.md),
[design.json](../../.impeccable/design.json), [FRONTS.md](../FRONTS.md) and D-079–085.
Source comparison sampled generated `tokens.css`, `base.css`, History/App styles and Compose
History. No new composition exploration, visual world, system tokens, dependencies or shipping
raster assets were introduced by this extension.

| System aspect | Observed continuation |
| --- | --- |
| Palette | Quiet charcoal panels, warm ink, teal actions and member identity colours; dark default with Light/Follow device retained. Existing `surface`, `line`, `ink-*` and `accent` roles supply History. |
| Type | Hanken Grotesk inherits the existing ramp: web History heading 28 px, base 16 px, controls/row times 13 px and axis 12 px. Android uses the existing palette/type family and native controls. |
| Material | Tonal separation and hairlines; shared radii of 22 px for the timeline, 16 px for rows and 10 px for fields. No new elevation language. |
| Form | Labelled front/view/window controls, wrapping log rows and reachable edit actions preserve compact reflow and the effective-versus-editable split. Basic stays small; management is still pending. |
| Named rule | **The Members Are the Colour Rule** remains intact: neutral chrome supports member identity. Quiet copy, visible state labels and Basic/Advanced remain inherited constraints. |

The reviewer retained **TYPE / MATERIAL / GROUND matched**, compact reflow and the editability
split. `docs/DESIGN.md` and `.impeccable/design.json` are preserved: this slice needs no token or
global rule update. Their UI1-era descriptions still mark named fronts broadly as pending;
that existing inventory wording must be read alongside the partial implementation status here
and in `PROGRESS.md`, not promoted into a new rule or silently repaired in this record.

## Scored fixes and capture evidence

Round 1 disposition was **fix** for two findings, corrected in one batch:

1. The shell footer used legacy/ambiguous presence. It now names Body's confirmed canonical
   presence with actual layer labels, independently of the History selection.
2. Whole-row opacity on undone records reduced Redo/metadata contrast. Only descriptive text
   now receives the secondary ink/strike-through treatment; actionable Redo and time metadata
   retain their token colours and full opacity.

Supplied final measured contrast: **Redo 5.36:1 dark / 4.93:1 light; metadata 6.10:1 dark /
5.55:1 light**. These clear AA for the measured text; they are not a whole-surface accessibility
certification. The verdict and measurements are carried from the supplied reviewer handoff,
not a new audit by this documenter.

All four final PNGs exist; their headers confirm the dimensions below. Their validation and
fixture interpretation come from the existing review/test evidence. Old captures remain in
`.impeccable/review/history-round1/` and are superseded.

| Final capture | Size | Expected fixture state |
| --- | --- | --- |
| [Effective desktop, dark](../../.impeccable/review/history-effective-desktop-dark.png) | 1440×900 | Body inherits Kai + Moon, both labelled Close by; footer agrees. |
| [Saved mobile, dark](../../.impeccable/review/history-saved-mobile-dark.png) | 390×844 | Body saved row undone; Redo remains available. |
| [Effective mobile, light](../../.impeccable/review/history-effective-mobile-light.png) | 390×844 | Effective history of Chorus. |
| [Saved desktop, light](../../.impeccable/review/history-saved-desktop-light.png) | 1440×900 | Chorus's latest own switch undone; Body now inherits Kai only. |

The differing desktop footers reflect legitimate fixture chronology: undoing Chorus's source
record changes Body's inherited presence. They are not contradictory simultaneous states.

## Existing verification and boundaries

These final results completed after the review packet was sent. Logs were read for this record;
no checks were rerun.

- `F:/DunBuild/chorus-history-client-final-verify.log`: **PASS**, reported invocation
  `python scripts/verify.py --quick --android --offline`; core/server tests, actual wasm web
  tests, JNI unit tests and Android lint. Web: **85 passed, 1 skipped**. The final bundle gate
  reports **JS 119 KB gz / wasm 299 KB gz** (wasm approximately 298.9 KB). The supplied earlier
  summary said JS 118 KB; the final log's 119 KB is recorded here. Both gates pass. This quick
  run does not establish a new full release-simulator/performance run.
- `F:/DunBuild/chorus-history-e2e-clean.log`: **20 passed (37.2 s)**, including offline saved
  edits/undo/redo across reload without interrupting following, appearance, sender and existing
  v1/privacy flows. T3 preview snapshots had timed out twice; the documented repository
  Playwright workflow used `CHORUS_E2E_CHANNEL=chrome`. An initial fixture leaked expected
  offline WebSocket errors into the shared v1 console list; the fixture now isolates its context
  and expected offline errors locally. The clean suite, including its console check, passed.
- `F:/DunBuild/chorus-history-design-detect.log`: one web detector run on `History.svelte`,
  result `[]`. Native detector skipped per skill. This is detector evidence, not visual approval.
- ARM64/x86-64 libraries and Kotlin bindings were rebuilt. `F:/DunBuild/chorus-history-apk.log`
  records **assembleDebug / BUILD SUCCESSFUL**; APK exists at
  `F:/DunBuild/chorus-ui-gradle/app/outputs/apk/debug/app-debug.apk`. No install or deployment.

**Native rendering remains UNVERIFIED.** A prior emulator launch was automatically rejected as
`blocked by policy`. It was not retried or bypassed. Build/JNI evidence and mobile web captures
do not establish Android visual parity.

This inline degraded-documenter pass read the required guidance and existing evidence and
created only this handoff. It performed no tests, browser work, screenshots, asset work,
delegation, new UI audit or commit. The unrelated `C:/Users/pcuser/source/repos/Chorus` checkout
was untouched. No new product decision or approval is required for this record.

## Next work

Continue UI2 with canonical statistics/profile/feed/search/review consumers and named SQL/export
history; full follower grants/reveals under D-084; D-085 channel purpose consumers, including
historical configuration at message time; then front/layer/follow/purpose management UI. Keep
bulk histories off hot paths and management gated on the remaining integration. UI3 polls remain
pending. This handoff claims no completion of those features.

Not canonized or repaired: existing UI1 inventory lag and native visual uncertainty remain
explicit boundaries; neither becomes a design-system rule. No material fix remains in the
reviewer's scored set.
