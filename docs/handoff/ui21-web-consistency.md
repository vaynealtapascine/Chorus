# UI21 web design consistency pass, stage 1 (code-level sweep)

Style refactor only: no behaviour change. Counts are grep-driven over `web/src/lib/ui/**/*.svelte` (minus `kit/`) and
`App.svelte`, 136 files; the counting script is the one-liners in the "How counted" note at the end.
Stage 2 is the browser tour (list at the bottom).

## Before / after

| What | before (n / files) | after (n / files) |
| --- | --- | --- |
| `border-radius` px literal (134 of the rest are `50%` circles) | 444 / 101 | 213 / 74 |
| `border-radius` from `--r-*` | 116 / 34 | 288 / 90 |
| `font-size` px literal | 488 / 89 | 136 / 51 |
| `font-size` from `--fs-*` | 198 / 39 | 528 / 99 |
| padding / margin / gap with a scale-value px literal | 818 / 105 | 24 / 19 |
| `var(--s-*)` uses | 275 / 29 | 1204 / 112 |
| hand-rolled button rules (`.go .primary .neutral .pill .quiet .danger`) | 143 / 57 | 62 / 32 (most are not buttons: `router.go`, menu rows, member-colour pills) |
| `<Button>` kit uses | 0 | 86 / 42 |
| `<Chip>` kit uses | 8 / 5 (stage only) | 21 / 11 |
| `<TextField>` kit uses | 0 | 13 / 11 |
| `<SectionLabel>` / `<ValueRow>` / `<Switch>` / `<UnderlineTabs>` / `<Segmented>` | 48 / 29 / 53 / 8 / 16 | 75 / 41 / 61 / 10 / 18 |
| `<PageHeader>` / `<h1>` hand-rolled | 14 / 19 | 17 / 16 |
| `role="switch"` hand-rolled | 10 / 6 | 2 / 2 |
| `rgb()` literal | 17 / 13 | 10 / 7 |
| `var(--token, fallback)` on tokens that always exist | 8+ | 0 |
| `friends.css` private kit (`fr-btn fr-chip fr-pill fr-toggle fr-switch fr-section fr-tabs fr-count fr-note`) | 9 families | 0 (rules deleted) |

## Canonical component for each pattern

| Pattern | Canonical | Notes |
| --- | --- | --- |
| Button: primary / neutral / quiet / danger / danger-soft / soft; sizes sm (36 pill) md (44) lg (48); `round`, `grow`, `block`; renders `<a>` with `href` | `kit/Button.svelte` (new) | disabled = surface-3 fill, ink-3 text for every variant |
| Chip (choice, toggle, add-one, link) | `kit/Chip.svelte` (was `stage/Chip.svelte`) | role/aria passed through; `on` for the teal state |
| Pill (read-only label) | `kit/Pill.svelte` (new) | `quiet` / `accent` |
| Text field | `kit/TextField.svelte` (new) | filled; `outlined`, `tall`, `multiline` |
| Form card + rows | `FormCard` + `ValueRow` (now with a `sub` line) + `Switch` | |
| Section label | `kit/SectionLabel.svelte` (now `aside`, `inset`) | |
| Note | `kit/FormNote.svelte` (now `quiet`, `inset`) | |
| Sheet + header/footer | `Sheet.svelte`, `ConfirmSheet` (now built on `Button`) | |
| Tabs | `UnderlineTabs` | |
| Segmented | `Segmented` | |
| Switch | `Switch` | |
| Empty state | `EmptyState` | |
| Page header (settings and top-level pages) | `PageHeader` (22 px, type scale) | |
| Icon button | `.icon-button` in `base.css` / `.fr-icon` in `friends.css` | not yet one thing, see "left" |
| Radii | `--r-sm 10`, `--r-row 12` (new), `--r-control 14` (new), `--r-md 16`, `--r-lg 22`, `--r-full`; `50%` for circles | tokens.json, DESIGN.md frontmatter |
| Type | `--fs-xs 12 .. --fs-3xl 36` (they scale with Text size) | |
| Space | `--s-1 .. --s-12` (they scale with Density) | |
| Overlays | `--scrim` (new), `--shadow-menu` (new), `--shadow-pop` | |

## Converted (commits, in order)

1. Tokens: radii, type sizes and spacing replaced by `var(--r-*)`, `var(--fs-*)`, `var(--s-*)` where the literal equals a token
   (script over every `<style>` block and `friends.css`); added `--r-row` (12) and `--r-control` (14) because 81 and 37 places used
   them and no token fit.
2. Buttons: `Button` in the kit; sheet footers, page pills, `.fr-btn`, dialog actions, request asks, `ConfirmSheet`; the unstyled
   buttons on This computer now match.
3. Chip and Pill in the kit; `.fr-chip`, `.fr-pill`, per-screen audience / type / trash pills.
4. Friends screens: `Switch` (8), `ValueRow` + `sub` (9), `SectionLabel` + `aside`/`inset` (27).
5. Friends tabs (`UnderlineTabs`, 2) and notes (`FormNote quiet`, 4); More, Insights, This computer take `PageHeader`.
6. `TextField` (13 fields in sheets and settings).
7. `--scrim`, `--shadow-menu`; fallbacks dropped from `var(--surface-3, ...)` and friends (20 files); notification rules `Segmented`.

## Still hand-rolled (left on purpose, with file:line from the last count)

| Pattern | Where | Why left |
| --- | --- | --- |
| tabs (role=tablist) | ChatList:239 folder tabs, Fronting:112, History:113 (zoom, e2e uses role tab), Members:184 and Notifications:153 (filter chips with counts), PostThread:211, stage/StageShell:52 | different looks (chips, icons, pills); e2e selects some by role |
| radiogroups drawn as chips/tiles/swatches | AcceptSheet:39, BucketIconPicker:19/31, BucketPage:57, CeilingField:42, Chat:1594, FormattingSheet:16, ForwardSheet:53, Fronts:139, LayersDisplay:92/133, NewChat:244, PlaceSheet:55, AppearancePage:41, Setup:101/110, stage/StyleTab:16 | not segmented controls; choice tiles. A `Tile` kit component is the next candidate (needs 3+ identical looks; they are not) |
| `.fr-seg` (role=group, aria-pressed) | AdvancedSharing:27, BucketPage:66/73, FriendNotify:98, FriendSharing:84 | converting to `Segmented` changes role to radio; a test could select it. Held back. |
| switch | Notifications:203 (checkbox role=switch), Onboarding:153 (own palette) | |
| `.fr-toggle` / `.fr-link` | BucketPage:80 (face + check row), FriendNotify:119, FriendPage:186, People rows | rows with faces, no kit FaceRow yet |
| round icon buttons | AttachSheet `.go`, ForwardSheet `.go`, `.fr-icon`, `.icon-btn` rules (11 files), `.bar-icon` | no kit IconButton; 13 sites for `.fr-icon` alone, next candidate |
| top bars with a back arrow | `fr-bar` (6), Chat head, SystemBoard, MemberReorder, MemberAudience, MemberEditor | each has its own title size (17, 19, 20) |
| empty text lines | Fronting:137/177/207, HomeDashboard:226, Members:209, Stage:270, PostStage:265, FrontCard:53 | inline lines, not list empties |
| Onboarding / Setup | own cream-on-teal palette, `.go`, `.chip` | the welcome screens are their own scene (`WelcomeBlobs`) |
| hex literals | 97, nearly all data: member colour default `#A09184` (27), swatch lists, `<input type=color>` defaults, Onboarding/Setup palette, QR white, white on photo overlays | not theme colours |
| font sizes | 14px (62), 11px (35), 20/17/19/10/9/24/26 | no token between 13 and 15; kit and screens both use 14. Needs a decision (snap to 13 or 15) |
| radii | 8px (23), 4/6/9/18/20 | small tags and bars; not in the radius scale |

## Behaviour and look changes to expect (deliberate, check in the tour)

- Every `--fs-*` and `--s-*` use now follows Text size and Density, so Large/Larger text and Compact density reach far more of the app
  than before (they used to reach only the screens that already used the variables).
- Button sizes: sheet footers 44, wide settings buttons 48, pills 36; disabled buttons one look; danger = red text on neutral
  (filled red only in `ConfirmSheet`). Small in-form buttons that were 36-40 with `--r-sm` are now pills.
- PageHeader title 24 px -> 22 px (type scale); More lost its 8 px bottom margin under the title.
- Pills: `quiet` is surface-3 (was surface-2 / surface), friends pills are full radius (were 8).
- Scrims: one `--scrim` (.45); Sheet's was .55, dialogs' .4.
- Friend chips 14 px -> 13 px, 32 px min height.
- `Switch` rows now flip locally before the saved value comes back (kit Switch behaviour).

## Selector-visible changes held back

None needed. Class names on converted buttons (`go`, `primary`, `pill`, `danger`, `quiet`) were kept as extra classes; `.fr-btn`,
`.fr-chip`, `.fr-pill`, `.fr-toggle` (except BucketPage), `.fr-section`, `.fr-tabs`, `.fr-note`, `.fr-count` no longer exist (no test
selected them). Roles, labels, text and order are unchanged. Not converted because they would change a role: `.fr-seg` (5).

## Left for the browser tour (needs eyes)

Desktop 1280 and phone width, light and dark, plus Large text and Compact density:

1. Sheet footers: Button heights and widths (CustomFields, CustomFronts, GroupMembers, MemberAudience, MemberFields, MemberGroups, NewGroup, Subsystem, UpdateSheet two-up, SaveSheet and TimeSheet two-up, HomeSections, ChatFolders).
2. Friends screens: People tabs bar background, Switch rows, ValueRow sub line, SectionLabel inset padding, Chips wrapping, quiet notes margin, Pill sizes beside names.
3. More, Insights, This computer: title spacing under PageHeader.
4. TextField: Members search, folder name row, Trash search, Webhooks / token sheets, Save stage and Time sheets (48 tall).
5. Compact density and Larger text on the dense screens (Chat composer, Fronting, History, Message): fixed heights with scaled text and spacing.
6. Contrast: `quiet` pill on surface-2 cards, `danger-soft` button on surface-2, disabled buttons (surface-3 on surface-2 sheets).
7. Dialogs (`PostCard` heart, `RemotePostActions`, audience dialog): Cancel and primary as small pills.
8. Notifications asks (Accept / Decline) and the Export bundle Download link (now a `Button` link).
9. MemberEditor: banner "Add a banner" label pill, Change picture pill, notification rule segments.
10. Decide: 14 px type step, the 8 px radius, a kit IconButton, a kit FaceRow.

## How counted

`grep -hoE` per pattern over the 136 files (kit excluded), run before the first commit and after the last; the list above names
files and lines of what remains.
