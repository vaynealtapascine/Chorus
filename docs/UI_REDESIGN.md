# Figma redesign implementation

Owner request, 2026-10-24. Source: [Chorus — Flows Copy](https://www.figma.com/design/389A1Z2QPTRpZw6d79Cz7k/Chorus--Flows--Copy).
Read `DECISIONS.md`, `FLOWS.md`, `ENTITIES.md`, `CLIENTS.md` and the Android handoffs first.

## Direction

Operate mode. Reference colours: canvas `#15161A`, panel `#1E2026`, raised `#272A31`,
line `#34373F`, foreground `#FBF3E5`, secondary `#A39E92`, teal `#42A397`, teal wash
`#1D2F30`, on-accent `#101018`. Hanken Grotesk, 15–16 px body, 20 px app titles, 12–13 px
metadata. Quiet separation, round avatar discs, 12–16 px rows and 20–22 px front containers.
Keep neutral controls; show active states with teal and text/shape as well as colour.

### Direction contract

THESIS: Member identity and current presence are easy to read while the surrounding app stays
quiet. Use the owner's specified Figma composition and visual system.

OWN-WORLD: Charcoal canvas, stepped panels, teal actions, warm readable foreground, Hanken
Grotesk, solid member-colour avatar discs, contained fronting layers and compact navigation.

STORY: Open a destination, see the current state, then switch, read or write as the intended
member. Audience choice remains visible before publishing. Saved changes work offline.

FIRST VIEWPORT: Persistent 240 px desktop navigation; contextual list, main content and optional
information column for chat. Compact web uses five bottom destinations with a 72 px app bar; Android uses native bars and switches to a rail from 840 dp.
Fronting shows nested presence levels and an explicit Switch action; Journal opens with a compact
composer and readable feed. Controls and content remain usable with long member names.

FORM: Owner-supplied Figma reference, not an invented alternative. Signature interaction: open
the switcher from the current-front summary, choose the ordered presence set, then switch with Undo.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the
verdict, DESIGN.md, and every shipping raster carrying its provenance.

Dark is the default. Retain a light palette and support an explicit theme choice. Use the
existing CSS/Kotlin token generator and Material components. Downloaded Figma icons stay local.
Do not ship screenshots as UI or demo account data as real content.

## Screen map

- F3.1 `3:321`, F3.2 `3:370`: Home/fronting and switcher; nested layer presentation.
- F4.1 `3:607`: member directory as readable avatar/name/metadata rows.
- F5.2 `3:822`, F5.21 `2019:1557`: native chat and desktop columns; persistent composer.
- F6.1 `3:1141`, F6.13 `2094:5066`: profile and journal; feed navigation and quiet separators.
- F10.1 `3:1730`: More destinations; full-row targets with descriptions.

Wide desktop uses persistent navigation plus contextual list/content columns. Compact web and
Android use five main destinations, with Journal and less frequent tools reachable from More.
Person accounts continue to hide system-only front/member controls (D-003).

## Implemented UI1 foundation

- Shared dark-default and light palettes generated from `design/tokens.json`; explicit per-device
  Dark / Light / Follow device preferences on web and Android. Hanken Grotesk is vendored on both
  clients, with the license and asset provenance recorded locally.
- Persistent desktop navigation and compact five-destination navigation; Journal and secondary
  tools remain reachable through More. Android uses a rail from 840 dp and yields the bottom bar
  to the keyboard. Person accounts preserve their existing system-control exclusions.
- Existing current-front data presented as nested Present / Co-conscious / Fronting containers,
  with primary member, timing, quick switch, ordered switcher and Undo retained.
- Member identity rows, uploaded avatars or solid colour discs with core-adapted foreground,
  neutral panel hierarchy and local Figma-derived navigation/action icons.
- Chat list/conversation/information columns, compact channel strip, persistent composer and
  bounded message-action popover; Journal divided feed, compact composer and entry expansion;
  More destination rows and the existing Basic/Advanced settings presentation.

The measured visual system is [DESIGN.md](DESIGN.md). Its normative palette/radius/spacing values
mirror the executable token source; `.impeccable/design.json` carries component previews, motion,
shadows, breakpoints and narrative. `design/assets.json` records downloaded SVGs, lossless 4×
Android PNG derivatives, font licensing and source frames. Review screenshots are evidence only.

## Evidence and limits

Web captures live under `.impeccable/review/`: Fronting, Members, Chat, Journal, More and Settings
at desktop and mobile widths, plus explicit light-theme persistence. `evidence.json` records no
page overflow or console errors in that capture pass. These are local review artifacts with test
account data, not production assets. Source and capture review cover the current UI1 foundation;
the finish verdict owns final visual acceptance.

The 2026-10-24 finish verdict cleared the four scored web corrections: keyboard/touch message
actions with focus restoration, visible compact pinned results, drawn command icons and
theme-coloured links/form controls. Native rendering remains outside that verdict.

Android compilation establishes source/build compatibility. Native visual access was disabled in
this session; no screenshot, emulator or device rendering check is claimed. Material typography
roles use the shared Hanken font, but are not an exact generated copy of the web type ramp.
Legacy event/selection glyphs remain outside the new icon pattern; alternate fonts/themes,
roundness and density editors in the v1 design catalogue are not implied to be shipped controls.

## Owner-approved pending features

- Independent ordered named fronts; optional following of another front, with overrides.
- Configurable per-system layer names and levels; the existing three levels are defaults.
- Poll author chooses single or multiple choice; each member gets one editable vote; eligibility
  comes from the parent audience. Preserve existing privacy checks for every read and mutation.

These remain pending after UI1. The current operation payloads and behavior continue to use the
legacy single front and its three presence levels. Independent configurable layers are not aliases
for those three levels. Per-front message-authorship and notification layer selection, following
and Resume following belong with UI2/D-080. Anonymous per-member polls belong with UI3/D-081;
reader-visible totals must not expose voter identities through synced data.

Implementation status and next steps belong in `PROGRESS.md`. Further behavior gaps require an
owner question unless existing decisions/handoffs already settle them. Nothing is deployed or
installed on the owner's phone by this task without the handoff's required device agreement.

## Android F5 chat parity (2026-10-27)

The supplied `F:/DunBuild/figma-chat/` captures are the visual authority for this pass.
Phone chat opens on a foldered list and pushes a conversation; at 840 dp the list stays
beside the conversation. Presence and send-as use the channel-selected front, preserving
independent layer names and the core's authorship rules. Read badges count only messages in
the current visible local chat window (up to 100), not an invented server total. List previews
respect account/member visibility and conceal content-warning bodies. Search opens the existing
local/server search screen. No new operations or schema are introduced.

### Chat parity gaps

- Android's current chat DTO has no pin, reaction, mute, forward or pending-ack fields. Do not
  fabricate pinned banners, reaction counts, muted/pinned row icons or delivery/read ticks.
- Voice recording and live typing presence are absent. The composer keeps an inactive Send
  affordance when empty; only the actual sync connection can supply a live status subtitle.
- Channel rows contain thread parent IDs, but Android has no thread-specific reply composer
  or create-thread action. Existing thread channels remain reachable in the list; no “also send”
  checkbox or new thread behavior is implied.
- Notes are ordinary existing channels, without a saved-notes type or tags navigation.
- System/unsigned authorship is unsupported by ChatCompose; send-as must list real eligible
  own-account members only. The internal space name supplies system context where available;
  the model has no separate system display-name field.
- Native screenshot acceptance belongs to the parent agent, which will install and inspect the
  APK on SM-A566B. This implementation task does not install or change device settings.

Conversation bubbles now group adjacent messages with the same ordered authors/account, audience,
calendar day and five-minute window; CW, reply and segmented messages break groups. Incoming
names use adapted member colour, with a 28 dp avatar on the final bubble; acting-speaker messages
align right in teal wash. Dates are centred and times stay inside the bubble. Reply quotes use
only messages already visible in this view and conceal warned bodies. Long press (or tap) opens
a dimmed native dialog with existing reply/private reply/edit, platform copy, own-account read
receipts and selection into stage mode. Polls, held-message cancellation and edit history retain
their existing paths. Presence labels describe the current selected front, not historical presence.
The back arrow is a locally authored geometric Android vector, matching the existing 24 dp icon
stroke; no image-generation or new palette tokens were used.
