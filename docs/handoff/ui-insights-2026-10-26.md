# UI2 Insights handoff — 2026-10-26

Worktree: `F:/DunBuild/chorus-ui-redesign`, branch `feat/figma-ui-redesign`.
Source inspected against HEAD `7c4a62b37f8f2398bdc7a011ba1df196743263b5` and the current
Insights working-tree changes. UI2 remains in progress; UI3 polls remain pending.

This is the **inline Impeccable documenter fallback**: no custom-agent selector was available.
The documenter role and `reference/document.md` were read directly, followed by project authority,
source, existing captures' metadata and supplied logs. This pass writes only this handoff. It runs
no tests, builds, detector, browser, server or emulator, performs no delegation and makes no commit.
The original `C:/Users/pcuser/source/repos/Chorus` checkout and its unrelated owner WIP are untouched.

## Result and review boundary

Configured Insights now reads the canonical selected front's history on web and Android, with an
exact time-layer selection and separate **Front history** / **Saved entries** views. Existing
unconfigured accounts keep their previous front-only presentation and CSV format.

The supplied fresh review found one **P2**: configured scopes had lost the explanation that stacked
day/week totals are member-hours. Both client sources now say:

> Day and week bars show member-hours; simultaneous members add together.

The actual-component regression asserts that sentence. The supplied fresh post-correction verdict
is **SHIP for that one scored correction only**, with no scored fixes remaining. The parent/reviewer
opened all four replacement captures at their exact dimensions, confirmed the explanation wraps,
and reported TYPE / MATERIAL / GROUND match. This document records that disposition without starting
another audit. It does not approve all Insights behavior, native rendering or the rest of UI2.
The earlier [History handoff](ui-history-2026-10-26.md) has its own bounded approval and does not
approve Insights or native rendering.

## Inherited system — five-line summary

- **Palette:** existing charcoal/teal dark default, explicit light palette and member colours; shared generated tokens remain authoritative.
- **Type:** existing local Hanken Grotesk; generated web scale 12 / 13 / 15 / 16 / 18 / 22 / 28 / 36 px, with 13 px hints, 12 px chart rows and 18 px chart headings here; Android retains themed Material typography.
- **Layout:** existing four-point spacing and compact density; web scope fields wrap, headers wrap and chart padding contracts below 520 px; Android retains native menus and vertical panels.
- **Material:** existing flat stepped panels, restrained hairlines and shared corners; member-colour stacks and teal comparison bars use incumbent styles.
- **Rules:** preserve the existing “Members Are the Colour” rule, quiet concrete copy and Basic/Advanced split; this extension creates no new global rule or QUALITY BAR contract.

This is an ordinary **Operate** extension. No new visual world, tokens, reusable component contract
or shipping raster assets are introduced. [PRODUCT.md](../../PRODUCT.md),
[docs/DESIGN.md](../DESIGN.md), [the sidecar](../../.impeccable/design.json),
`design/tokens.json` and generated CSS/Kotlin retain their authority. The summary above describes
their use on this surface; it does not replace or regenerate them.

## Behavior compared with the previous screen

| Concern | Configured Insights now | Compatibility boundary |
| --- | --- | --- |
| Scope | Front, Time layer and History selections; Body / exact `front` ID / effective history by default | Body retains the account ID; unconfigured UI retains its old controls |
| History | Front history uses effective/inherited intervals and changes; Saved entries uses this front's own intervals and non-retracted saved changes | No own/effective substitution or stale legacy fallback for empty/unavailable canonical data |
| Layers | Day, week and together charts use the selected exact layer ID | Names and display levels never imply front/co-conscious/present semantics |
| Definitions | Front/layer options retain deleted definitions with a “(deleted)” label; historical IDs remain readable | Selection is read access to retained history, not management or restoration UI |
| Counts | Changes by day covers all layers of the selected front, in the chosen history view | The visible explanation states this; selecting an empty time layer can still show changes |
| CSV | All configured exports carry `front_id` and `history` (`own` / `effective`); time exports also carry `layer_id` | Change-count exports omit layer scope because they count the whole front; legacy filenames/CSV shape remain unchanged |

Configured headings are **Time by day**, **Time by week**, **Time together** and **Changes by day**.
Daily and weekly stacks add each member's duration; pair time measures simultaneous overlap.
This is why a 1.5 h stack and 0.5 h together value can both be correct. The all-layer count
explanation is separate from the restored member-hours explanation.

Both screens retain 7/28-day ranges, data views and CSV actions. Web read failures show
“Could not load saved history.” with **Try again**; unavailable reads show **Preparing insights…**.
Charts/export controls are withheld during those states. Android also exposes read retry and
calculation/loading feedback. These are source observations, not proof of hydrated/native interaction.

## Implementation evidence and traps to preserve

Primary source changes are [Insights.svelte](../../web/src/lib/ui/Insights.svelte),
[InsightsScreen.kt](../../android/app/src/main/kotlin/garden/vayne/chorus/ui/InsightsScreen.kt)
and the `InsightsScreen(chorus, model)` call in
[MainActivity.kt](../../android/app/src/main/kotlin/garden/vayne/chorus/MainActivity.kt).
They connect the already-tested canonical history/daily helpers rather than introduce a client
implementation of following or layer meaning.

- Web uses `sync.frontHistory`, `namedFrontDays`, exact-ID week/pair adapters and the selected
  history's changes. Android uses `chorus.frontHistory` and
  `InsightsData.snapshot(history=..., layerId=..., own=...)`. Shared wasm/UniFFI named daily helpers
  own timezone-aware day splitting; adapters bound intervals to the requested range and now.
- [FrontHistoryCache](../../web/src/lib/front-history.ts) now retains the complete per-front
  snapshot identity until its account/front dependency changes. Unrelated member/chat changes
  retain history snapshots, avoiding repeated timezone-aware aggregation. The cache regression
  asserts identity after a member update; earlier history invalidation coverage remains relevant.
- Android `produceState` can retain its old value until a replacement coroutine starts. The screen
  keys the composition of load, calculation and chart results by remembered request identities,
  so scope changes clear stale results and available chart/export actions immediately. Do not
  replace this with coroutine keys alone. Native runtime rendering of this behavior remains unverified.
- Configured reads wait for canonical history instead of calling the legacy fallback with a null
  history. Empty exact-layer data must stay empty even if the old Body projection contains hours.
- These remain explicit account-local history reads. Keep bulk history folds out of current-front,
  composer and typing paths. No Rust/ABI change was made since the earlier History build.

Supporting sources inspected: [insights.ts](../../web/src/lib/insights.ts),
[InsightsData.kt](../../android/app/src/main/kotlin/garden/vayne/chorus/data/InsightsData.kt),
their existing helper regressions, web `design/base.css` / `design/tokens.css`, Android
`ChorusTheme.kt`, and the relevant `docs/CLIENTS.md` / `docs/NOTES.md` diff.
Settled behavior remains [D-079–085](../DECISIONS.md) and [FRONTS.md](../FRONTS.md);
the answered product questions were not reopened.

## Actual-component fixtures and captures

[Insights.test.ts](../../web/src/lib/ui/Insights.test.ts) renders the actual Svelte component with
the actual wasm replica and the deterministic authored-op log in
[insights-fixture.ts](../../web/src/lib/testing/insights-fixture.ts). Its fixed time is
`2026-10-26T04:00:00Z`. The fixture defines independent layer `quiet`, displayed as **Close by**,
at display level −2; its meaning is still its exact ID. Body follows Chorus after recording its
own earlier Fronting entry.

The regression covers source effective **1.5 h**, inherited Body effective **1.3 h** (1.25 rounded),
Body saved **2.0 h**, an exact-layer empty result that excludes seeded stale legacy **10.0 h**,
and legacy-only **10.0 h** with named controls absent. It also exercises controlled read failure
and unavailable/loading states. The final copy assertion is present. This is SSR coverage;
it does not exercise selection events, retry clicks, CSV downloads or hydration.

All four final captures are actual `Insights.svelte` SSR component fixtures, using the actual
wasm operation log and embedded application CSS, generated tokens and local Hanken font, viewed
through T3 `html_preview`. They are component fixtures, not a live app or shell capture, and
provide neither hydrated-control nor native-render proof.

| Final capture under `.impeccable/review/` | Exact dimensions | Scope and visible evidence |
| --- | --- | --- |
| [insights-effective-desktop-dark.png](../../.impeccable/review/insights-effective-desktop-dark.png) | 1200 × 1130 | Chorus / Close by / effective; 1.5 h member-hours and 0.5 h together |
| [insights-saved-mobile-dark.png](../../.impeccable/review/insights-saved-mobile-dark.png) | 390 × 1200 | Body / Fronting / own Saved entries; 2.0 h |
| [insights-effective-mobile-light.png](../../.impeccable/review/insights-effective-mobile-light.png) | 390 × 1199 | Body / Close by / effective; 1.3 h member-hours and 0.5 h together |
| [insights-empty-desktop-light.png](../../.impeccable/review/insights-empty-desktop-light.png) | 1200 × 1133 | Chorus / Fronting / effective; zero selected-layer time, 3 changes across all layers |

PNG header dimensions were read in this documentation pass. The parent/reviewer supplied the
visual/console observations: all four opened, `scrollWidth == width`, scope controls and buttons
at least 44 px high, font loaded and no console errors. The replacements include the corrected
explanation; first-pass captures remain in
[insights-round1](../../.impeccable/review/insights-round1/). These files are review evidence only.

## Existing verification and packaging evidence

No check was rerun for this document. Existing logs were read directly:

| Evidence | Recorded result and limit |
| --- | --- |
| [Initial verifier](F:/DunBuild/chorus-insights-ui-verify.log) | `python scripts/verify.py --quick --android --offline` passed before the final member-hours copy correction |
| [Final correction verifier](F:/DunBuild/chorus-insights-ui-final-verify.log) | Now complete: `BUILD SUCCESSFUL in 1m 3s` and `verify passed`; log last write 2026-10-26 15:29:40 +08:00 |
| Final web/Android checks | 19 web test files passed / 1 skipped; 87 tests passed / 1 skipped; Android JVM/JNI and lint completed; verifier reports JS 120 KB gz / wasm 299 KB gz within its budgets |
| [Web design detector](F:/DunBuild/chorus-insights-design-detect.log) | `[]`; native detector skipped. This does not establish native visual quality |
| [Final APK assembly log](F:/DunBuild/chorus-insights-apk-final.log) | Parent confirmed `assembleDebug` passed after the final member-hours copy correction; no install or deployment |

APK location: [app-debug.apk](F:/DunBuild/chorus-ui-gradle/app/outputs/apk/debug/app-debug.apk).
No install or deployment occurred. The completed final verifier and assembly establish corrected
source checks and a built APK, not a verified installed app or native screenshot.

## Outstanding work and unchanged authority

Live Insights E2E, hydrated interactions and native rendering remain unverified. Earlier automatic
approval review rejected the isolated emulator launch and, later, the isolated test-server restart
with **“blocked by policy”**. Neither action was retried or bypassed; the owner's service and device
were left untouched. Static SSR inspection was an independent, limited method, not a workaround
launch. Both policy boundaries remain unresolved.

This slice does **not** complete canonical profile/feed/search/review consumers, named SQL/export
history, D-084 additional-front follower grants/reveals, D-085 historical channel-purpose consumers,
or front/layer/follow/purpose management. Scoped chart CSV is not completion of named SQL/history
export integration. UI3 polls and broader UI2 remain pending. The board's next work also includes
legacy review-resolution ordering/late parents; this handoff does not claim it was resolved.

**Drift not repaired or canonized:** the preexisting UI1 inventory in `docs/DESIGN.md` and the
sidecar still broadly describe named fronts/layers/following as pending. That inventory lags this
bounded implementation; it does not make management, purpose consumers or polls complete. Global
authority remains unchanged. The parent subsequently updated the implementation status in
`docs/FRONTS.md` to reflect connected Insights. Earlier `PROGRESS.md` pending-review wording was superseded
by the board's final-verifier/bounded-SHIP update while this documentation pass was reading it;
the documenter did not edit the board.

Paths written by this pass: **`docs/handoff/ui-insights-2026-10-26.md` only**. No design change,
new audit, authority repair, commit or original-checkout edit was performed.
