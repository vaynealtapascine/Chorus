---
name: Chorus
description: Quiet charcoal and teal for an offline-first plural system app.
colors:
  bg: "#15161A"
  surface: "#1E2026"
  surface-2: "#272A31"
  surface-3: "#30343C"
  line: "#34373F"
  ink: "#FBF3E5"
  ink-2: "#B1ACA1"
  ink-3: "#A39E92"
  accent: "#42A397"
  accent-soft: "#1D2F30"
  ok: "#86BFA5"
  warn: "#E0B560"
  danger: "#E8776D"
  on-accent: "#101018"
  light-bg: "#F7F6F2"
  light-surface: "#FFFFFF"
  light-surface-2: "#EFEEE9"
  light-surface-3: "#E4E7E2"
  light-line: "#D8DAD4"
  light-ink: "#202529"
  light-ink-2: "#565F60"
  light-ink-3: "#626A68"
  light-accent: "#247D73"
  light-accent-soft: "#E0EFEB"
  light-ok: "#5E8C61"
  light-warn: "#C99A3B"
  light-danger: "#B5524A"
  light-on-accent: "#FFFFFF"
typography:
  headline:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "28px"
    fontWeight: 700
    lineHeight: 1.2
  title:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.2
  app-title:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: "26px"
  section:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.2
  body:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.45
  content:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.45
  label:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.45
  metadata:
    fontFamily: "Hanken Grotesk, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.45
  mono:
    fontFamily: "JetBrains Mono Variable, ui-monospace, monospace"
rounded:
  sm: "10px"
  md: "16px"
  lg: "22px"
  full: "999px"
spacing:
  "1": "4px"
  "2": "8px"
  "3": "12px"
  "4": "16px"
  "5": "20px"
  "6": "24px"
  "8": "32px"
  "12": "48px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.full}"
    padding: "12px 20px"
    height: "48px"
  button-secondary:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "4px 12px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.accent}"
  input-search:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "12px 16px"
  navigation-item:
    textColor: "{colors.ink-2}"
    typography: "{typography.content}"
    rounded: "{rounded.full}"
    padding: "12px"
    height: "48px"
  navigation-item-active:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.ink}"
  chip:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-2}"
    rounded: "{rounded.full}"
    padding: "4px 12px"
  chip-selected:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.ink}"
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "16px"
  member-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "12px"
    padding: "14px 16px"
  front-container:
    backgroundColor: "{colors.accent-soft}"
    rounded: "{rounded.md}"
    padding: "10px"
  message-actions:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "6px"
    width: "280px"
---
# Design System: Chorus

## Overview

**Creative North Star: "Quiet charcoal & teal"**

Member identity and current presence are easy to read while the surrounding app stays quiet.
The owner's Figma guide establishes charcoal canvas, stepped panels, teal actions, warm text,
Hanken Grotesk, round member-colour avatar discs and containment-style fronting (D-079).
Dark is the default; Light and Follow device are explicit per-device choices.

This document merges the implemented UI1 visual foundation with the existing v1 design spec.
Observed foundation rules come from `design/tokens.json`, generated CSS/Kotlin, the Svelte and
Compose sources, and the web review captures. `scripts/gen-tokens.mjs` remains the executable
source-of-truth bridge; frontmatter records its palette, radii and spacing. The type roles and
component entries additionally record reused values in the shipped web source. See
[UI_REDESIGN.md](UI_REDESIGN.md) for source frames, implementation boundary and review limits.
The screen inventory and broad settings catalogue below remain specification, with completion
tracked in `PROGRESS.md`; they are not a claim that every control has been implemented.
Legacy numbered references resolve as follows: §1 → frontmatter and Colors/Typography/Shapes;
§2 → Layout; §3–§6 → Components (inventory, patterns, display styles, Basic/Advanced);
§7–§8 → Accessibility and voice under Do's and Don'ts.

**Key Characteristics:**

- Neutral stepped surfaces support member identity and readable content.
- Teal marks actions, selected destinations and current presence.
- Rounded rows and containers use restrained borders and almost no elevation.
- Basic stays small; additional controls remain reachable in Advanced.
- Quiet, short copy describes the saved state and the next action.

## Colors

The primary teal sits against warm foreground text and charcoal neutral surfaces. Frontmatter
uses unprefixed keys for the default dark palette and `light-` keys for the explicit light palette;
these names correspond to `color.dark` and `color.light` in `design/tokens.json`.

### Primary

- **Teal** (`accent`): primary actions, active iconography, links and the front boundary.
- **Teal wash** (`accent-soft`): selected chips, own-message surfaces and the innermost front layer.
- **On teal** (`on-accent`): contrast-specific text on filled actions; do not substitute page ink.

### Neutral

- **Charcoal canvas** (`bg`): app canvas and quiet journal rows.
- **Panel** (`surface`): app bar, destination rows, settings cards and front card.
- **Raised panel** (`surface-2`): message blocks, control wells and active desktop navigation.
- **Selected panel** (`surface-3`): selected channel rows.
- **Hairline** (`line`): dividers and quiet container boundaries.
- **Warm foreground** (`ink`): main content; **secondary** (`ink-2`) for supporting labels;
  **tertiary** (`ink-3`) for readable timestamps and metadata.

### Status and member colour

`ok`, `warn` and `danger` carry success/live status, review/pin attention and destructive actions.
The matching light palette preserves these semantic roles. These status colours are not substitutes
for readable body text.

Members choose their own colour. `chorus-core::color::adapt` supplies theme-aware `name`, `ring`,
`tint` and `avatar_ink`: AA names on surfaces, 3:1 avatar rings against the canvas, a restrained
selection wash and readable initial/sigil foreground. Shared core owns this adaptation; clients
must not approximate it independently. Uploaded avatars retain their member-colour ring.
"Member colour intensity" (Advanced) remains the spec: off · subtle (default) · vivid.

**The Members Are the Colour Rule.** Keep chrome neutral; member colour belongs to identity,
selection and presence rather than unrelated decoration.

## Typography

**Display and body font:** Hanken Grotesk, with system-ui and sans-serif fallback.
**Code font stack:** JetBrains Mono Variable, ui-monospace, monospace. The named mono binary is
not vendored; the platform monospace fallback is the currently available face.

Hanken Grotesk is vendored locally for web and Android, with the OFL-1.1 license in
`web/public/fonts/OFL-HankenGrotesk.txt`. The shared binary's pinned hash is in DECISIONS §Versions;
asset provenance is in `design/assets.json`. Product titles use the same family as body content.

### Hierarchy

The frontmatter records reused web roles: headline for editor/directory headings, title for sheets
and section titles, app-title for the app bar, section for smaller headings, body/content for reading,
and label/metadata for supporting text. Bold display text uses tight tracking (−.02em); headings
use a compact line height while body text has room to read. Names generally use weight 600–700;
metadata stays regular. Time values use tabular numerals.

The complete generated web size scale is 12 / 13 / 15 / 16 / 18 / 22 / 28 / 36 px; the app bar
adds an observed 20 px title. Android applies the same font to Material 3 typography roles and
uses screen-specific sp sizes; the web size scale is not generated into Kotlin typography.
OS text scaling remains an accessibility requirement. Text-size controls (85%–150%) and alternate
fonts (Atkinson Hyperlegible, OpenDyslexic, system) remain planned settings rather than UI1 controls.

## Layout

The shared spacing rhythm is a four-point scale from `spacing` in frontmatter. Core content uses
16 px compact or 24 px wide padding, with list rows denser than the surrounding screen.

- **Wide web (≥1024 px):** persistent navigation starts at 240 px; the app bar is at least 76 px.
  Main content has a 1200 px maximum width. At ≥1600 px the outer navigation gutter expands.
- **Compact web (<1024 px):** fixed bottom navigation, a 72 px app bar, 16 px content padding and
  safe-area-aware bottom clearance. Destinations are Members · Fronting · Chat · Friends · More.
  Journal and secondary tools are reachable through More; desktop adds direct Journal/Search/Settings.
- **Android:** bottom navigation below 840 dp and a navigation rail from 840 dp. Defaults are
  Members · Fronting · Chat · Journal · More (D-089). Settings → This device → Bottom navigation
  opens a staged editor with preview, choose/reorder/remove controls and Restore defaults.
  Up to four chosen destinations precede fixed More; all removed destinations remain in More.
  Choices are device/account-local and do not reset destination drafts or scroll positions.
  Focused phone conversation/info and journal editor/detail routes hide the destination bar,
  which returns on pop to their roots; the system navigation/IME insets remain. Expanded layouts retain the rail.
- **Person accounts:** Journal · Chat · Friends · More; system-only front/member controls stay
  hidden as required by D-003.
- **Chat:** 260 px channel list and flexible conversation at wide widths. From 1280 px an open
  information panel occupies another 260 px column; below that it overlays from the right. Compact
  web channels form a horizontal strip. Android opens a foldered chat list, then pushes the chosen
  conversation; from 840 dp a 240 dp list, flexible conversation and 220 dp read-only context
  pane sit beside one another. Android actions use a native dimmed dialog; send-as and attachment
  options use native bottom sheets. The conversation scrolls within its area, keeping the composer
  reachable above navigation/keyboard insets.
- **Journal:** readable feed (up to 760 px), plus a 280 px context column from 1280 px. Below this,
  shortcuts remain reachable through the feed tabs and app navigation.
- **Members:** responsive row cards with a minimum desired column width of 320 px; long names wrap.
- **Android member profiles (D-090):** Members and Journal share the profile header: a quiet wide
  banner well, overlapping member-colour avatar, prominent name, pronouns and rich bio. Edit
  profile is a labelled 48 dp action. The full-screen editor has pinned Cancel/Save, scrollable
  fields and IME clearance; additional identity details stay under More details.
  Bio and message-part editors retain native selection/composition, with labelled 48 dp Undo/Redo
  controls and keyboard equivalents restoring rich occurrence identity along with text. Crop sheets
  show the actual image with zoom/position controls and staged Apply/Cancel. Reorder uses a
  full active-member list with visible drag handles, short interruptible displacement (instant
  under reduced motion), accessible Earlier/Later and pinned Apply/Cancel.
- **Switcher:** bottom sheet on compact web; centered from 641 px, maximum width 560 px and height
  88vh. Android uses native sheets/dialogs and system Back.

Web source currently wires Ctrl/⌘S to the switcher. The existing keyboard spec also calls for
Ctrl/⌘K command palette, Alt+↑/↓ channels, E edit last message, R reply and Esc close; completion
must be checked against `PROGRESS.md`, not inferred from this catalogue.

## Elevation & Depth

Tonal steps and hairlines create most separation. The shared soft `pop` shadow is reserved for
sheets, popovers and overlaid information panels; its exact value lives in `.impeccable/design.json`
and `design/tokens.json`. It is not a card decoration. Front containment uses outline style and
teal wash rather than a lifted stack: web outer levels are dashed, while the front edge is solid;
Android uses nested tonal/outlined containers.

Shared motion tokens are fast (150 ms), base (200 ms), slow (280 ms) and the existing ease curve.
The current web member-hover treatment uses the fast token. Reduced-motion CSS collapses animation
and transition duration. The existing spec's switch settle (≤300 ms) and Android light switch haptic
remain product guidance; UI1 does not introduce a new motion or haptic system.

## Shapes

Shared small, medium and large radii apply to controls, cards and sheets respectively; full is
reserved for avatars, pills and navigation. Observed member/destination rows use 12 px corners;
front containment adds 20 px outer outlines around the 16 px front surface. Member discs are
circular, with a coloured fill and ring and either a photo or readable initial/sigil.

The Advanced roundness multiplier (0.5×–1.5×), avatar alternatives (squircle/rounded square) and
compact density setting remain the existing spec, not newly delivered UI1 options.

## Components

### Buttons

Filled teal marks the primary action, with `on-accent` text, a pill silhouette and a 48 px Switch
target. Smaller existing primary buttons use 8 px × 16 px padding. Secondary controls use neutral
wells and hairlines; ghost actions retain teal text without a filled surface. Hover brightens eligible
web buttons slightly. Keyboard focus is a 2 px, 60%-accent outline with a 2 px offset. Disabled Send
is visibly muted. Error/destructive actions use the danger role and a clear text label.

### Inputs / Fields

Fields use panel or raised-panel fill, an ink foreground, hairline border and a small or medium
radius. Main search fields use 12 px × 16 px padding. Placeholder and caret follow `ink-3` and
`accent`. Use real labels, visible focus and native controls where suitable. Android uses Material
fields, with the existing 48 dp interaction target requirement.

### Chips

Filters are full-radius pills with a neutral fill and border. Selected filters gain teal wash and a
teal outline while keeping readable ink text. Presence chips have text labels; colour alone never
carries the level. The composer speaker chip shows the intended author before Send.

### Cards / Containers

Front and settings cards use the large radius and 16 px internal padding. Member rows use
44 px discs, name and pronouns, optional front badge and a chevron; their full row is the target.
More uses a 40 px teal-wash icon disc beside a title and short description. Hover steps to the next
neutral surface. Journal posts are flat divided rows rather than standalone floating cards.

### Navigation

Desktop destinations use pill rows, muted text and icons, a stronger neutral active surface and
teal active icon. Compact navigation stacks icon over label and uses both label and icon to show
selection. Keep accessible names, `aria-current`, visible focus and the skip-to-content target.
Native navigation uses Material components with the shared palette and local icons.

### Current front and switcher

The implemented front card shows the existing **single** current front within three nested levels:
Present contains Co-conscious, which contains Fronting. Fronting member rows show identity,
primary designation when set, since time and elapsed minutes. Empty levels say "No one".
The card and explicit Switch action open the existing ordered-set switcher; quick switch and Undo
remain available. Named fronts, independent configurable layers, following/resume-following and
per-purpose layer selection are approved pending work (D-080), not UI1 behavior.

### Chat and actions

Message blocks use medium corners, 12 px padding, a maximum width of min(90%, 72ch), and a member
identity header with a readable timestamp. Own-speaker blocks use teal wash. Existing reply,
quote, attachment, CW and visibility controls preserve their operation. Speaker parsing previews
stay visible before Send. Advanced chat and custom emoji tools remain collapsed behind disclosures.

The message actions trigger opens a browser popover with text-labelled 48 px rows and downloaded
icons. It is bounded to the viewport, scrolls when tall and reserves danger colour for Delete.
Copy/quote/forward/reply/thread/pin/edit actions remain subject to existing permissions. Android retains its native menus and sheets. Poll creation sits in the composer
options ("Add a poll") and voting on the poll card under the message (D-081, POLLS.md), not in these message controls.

### Journal and settings

Journal opens on the existing Timeline / Lists / Feeds navigation and compact post composer.
Audience selection remains visible before posting. Long entry text collapses to a short preview with
Show more / Show less. Settings starts with the explicit per-device theme choice, then the existing
notifications/sharing controls, with extra options behind Advanced. This changes presentation while
preserving existing privacy and saved-preference behavior.

### Existing screen specification

This inventory is retained from the v1 design spec; `PROGRESS.md` owns its implementation status.

| Screen | Contents | Primary action |
| --- | --- | --- |
| **Home** | Front card (avatars, levels, since/duration), quick switch row (pinned + recent), review cards, "today" timeline snippet, unread summary | Switch |
| **Switcher** | Search, pinned, recent, groups tree, selected tray (drag order, level toggle, star primary), note, time, notify choice | Switch |
| **Front history** | Lane timeline (zoom day/week/month), list view, edit/insert switch | — |
| **Chat** | Spaces rail, channel list, message list, composer | Send |
| **Thread / Pins / Search** | Side panel (web) / full screen (Android) | — |
| **Members** | Grid/list toggle, search, filter chips (group, state, archived), sort | Add member |
| **Member editor** | Basic fields; sections for Proxy & sigils, Custom fields, Privacy, Notifications | Save draft (Android) / auto-save (web) |
| **Profile** | Banner, avatar, names, pronouns, sigils, bio, fields, stats, tabs (Posts, Replies, Journal, Highlights, Media, Relationships) | Post as this member |
| **Journal** | System timeline, feeds tabs (Following, Lists, custom feeds), compose | Write |
| **Compose post** | Author chip(s), note/entry toggle, title (entry), body, mood, tags, CW, visibility | Post |
| **Feed editor** | Query field with syntax highlight + chips, live preview, visibility | Save |
| **Stage** | Selection overlay, context-only/hidden toggles, filters, style presets, redaction, fake names/times, "Capture" | Capture |
| **Emoji** (Settings → Server) | Server-wide custom emoji: upload with crop, name, aliases, category, retire | Upload |
| **Trash** | Deleted items by type, search, restore | Restore |
| **Insights** | Dashboard cards; each opens a full chart with table + CSV | — |
| **Sharing** | Followers, requests, buckets, ceilings (presets), following + prefs | — |
| **Notifications** | History list, per-kind settings | — |
| **Settings** | Basic list + "Advanced" entry at the bottom | — |
| **Sync issues** | Rejected ops with reason, retry/discard/copy | — |
| **Onboarding** | Invite, system name, terminology, first members / PK import, first front | Continue |

### Display styles (chat and stage specification)

Presets apply to the chat itself (per device) and to stage mode:

| Style | Description |
| --- | --- |
| Chorus | Default described above. |
| Discord-ish | Compact rows, left avatars, names coloured, no bubbles. |
| Bubbles | iMessage-like: own-system messages right in member `tint`, others left. |
| Card | Twitter-style card per item (for posts/stage). |
| Transcript | `Name: text` lines, no avatars, monospace optional. |
| Minimal | Text only with thin member-colour bar at left. |

The current UI1 chat presentation is the message-block pattern described above. The preset catalogue
is retained as product specification, not a claim that a chat-style selector exists on both clients.

### Basic vs Advanced settings (specification)

**Basic** (one screen, ~12 rows):

1. Appearance: theme (dark by default / light / follow device), accent, text size.
2. Words: terminology (member/front/switch/subsystem).
3. Switching: widget default mode, undo duration shown as "Undo for 10 s".
4. Notifications: on/off per kind (switches of people I follow, mentions, DMs), quiet hours.
5. Sharing: followers (with presets), privacy default for new things.
6. Data: export, backup status.
7. Devices: this device, add a device.
8. Advanced ›

**Advanced** (grouped, searchable):

- *Appearance*: theme editor, fonts, density, roundness, member-colour intensity, avatar shape,
  chat style, message grouping window, 12/24 h, date format, week start, reduced motion, haptics,
  show seconds in durations, relative vs absolute timestamps.
- *Terminology*: every term incl. levels ("co-con", "present"), plural forms.
- *Switching*: review window, settle time, default level for added members, subsystem-front
  behaviour, primary-fronter rules, "time override" default, recent-front-sets count.
- *Widget*: per-widget scope (all / subsystem / group), columns, avatar size, label style, show
  current front header, mode-chip reset time.
- *Chat*: segment parsing (newline annotations) on/off, autoproxy defaults, proxy tag case sensitivity, sigil parsing on/off, multi-author
  parsing on/off, strip tags, per-member read tracking, delete behaviour (placeholder/vanish),
  link handling, spoiler auto-reveal, CW auto-expand, "delay send" per space, formatting
  shortcuts on/off, slow mode, “Show when we're typing in shared chats” (`share_typing`, account-level, default off; D-087).
- *Journal*: note length limit, attach front snapshot to entries, default visibility per member,
  drafts retention.
- *Privacy*: per-object-type defaults, per-field visibility, member locks (PIN/biometric), lock
  timeout, screenshot blocking (`FLAG_SECURE`) for locked content.
- *Notifications*: delivery method (UnifiedPush / foreground / polling), per-member mention rules,
  own-switch notifications, digests, channels.
- *Sync*: server URL, snapshot threshold, web offline window (days), metered-network behaviour,
  attachment download rules, clock-skew correction on/off, background sync interval, "sync
  now", sync log.
- *Data*: API tokens, webhooks, exports schedule, import (PluralKit), Trash, local storage
  (evict old media/messages on this device — server copy is kept).
- *Security*: device list/revoke, Tailscale identity check, session length.
- *Identity*: show member short ids (7 letters).
- *Developer*: op log viewer (filter by kind/entity), digests + full projection hash, force
  re-snapshot, debug bundle export, feature flags.

The catalogue above is the existing target. UI1 ships the theme choice and preserves the settings
already present; font, density, roundness and custom-theme editors are not implied by this list.
Additional themes remain planned Advanced options: Night sky, Paper journal, Material You, High
contrast and a custom theme editor with preview and contrast warnings.

### Other retained patterns

- Multi-author identity uses overlapping avatars (up to three, then a count) and names joined with
  " & "; the existing grouping spec groups consecutive identical author sets within five minutes.
- Review cards use a soft warning cue, both competing switches and Keep both / Keep phone /
  Keep desk / Merge actions. Empty/offline banners use a neutral well, icon, short sentence and
  next action instead of blocking the app.
- Stage context-only rows remain at 40% opacity with a one-line summary or count. The chat/stage
  preset and selection behavior stays governed by the existing spec and implementation.
- The original empty-state illustration guidance remains a future asset requirement: a small soft
  line illustration, one sentence and one action; no mascot. UI1 adds no generated illustration.

## Do's and Don'ts

### Do

- Do use the shared generated palette and per-device Dark / Light / Follow device setting.
- Do adapt member names, rings and avatar foreground through shared core.
- Do keep Basic small and expose additional controls through Advanced.
- Do retain visible audience, author and presence labels before an action is sent.
- Do preserve keyboard access, visible focus, OS text scaling and reduced motion.
- Do use the local Figma-derived icon set and record asset provenance.

### Don't

- Don't ship reference screenshots or review fixtures as application UI assets.
- Don't use colour as the only indication of presence, selection or an error.
- Don't imply that approved pending named fronts, independent layers or polls already exist.
- Don't turn a legacy implementation exception into a new system-wide visual rule.

### Accessibility and voice

The existing requirement is WCAG 2.2 AA: readable contrast (member names enforced by core),
48 dp native touch targets, TalkBack/screen-reader labels, full web keyboard access and no information
by colour alone. Avatar labels describe the member and presence; images prompt for captions/alt
text. Preserve system Back, keyboard/window insets and reduced motion.

Copy remains warm, short and plain: "Kai is here", "Saved on this phone". Errors say what happens
next. Never guilt or urgency. Terminology substitutions apply everywhere, including notifications.

### Not canonized

Legacy event/selection Unicode glyphs and unsupplied alternate font/theme controls are not new
UI1 system rules. Android has build evidence but no native visual verification in this session;
web capture evidence cannot establish native rendering parity. These limits are carried forward
in `UI_REDESIGN.md` instead of being described as verified design behavior.
