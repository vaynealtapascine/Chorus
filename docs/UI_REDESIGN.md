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

- Android now has reactions (F5.6 quick row + emoji grid, "❤️ 2" chips), pins (F5.2 banner,
  Pin/Unpin), per-chat mute (notify_channel prefs, bell-off rows), forward (snapshot like web,
  one-time outward confirmation) and delete with Undo (2026-10-28). It still has no pending-ack
  field: do not fabricate delivery/read ticks.
- Voice recording and live typing presence are absent. The composer keeps an inactive Send
  affordance when empty; only the actual sync connection can supply a live status subtitle.
- Threads (F5.7): "Reply in thread"/"Open thread" opens or creates the parent's thread (id =
  parent id, as on web); parents show last repliers and the count; thread channels are not
  listed as chats. "Also send to <channel>" posts a second message replying to the parent in the
  parent channel — an Android reading of the Figma, pending Q22.
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
only messages already visible in this view and conceal warned bodies. A tap or long press opens (D-105; its header carries the date, time and edit history)
a dimmed native dialog with existing reply/private reply/edit, platform copy, own-account read
receipts and selection into stage mode. Polls, held-message cancellation and edit history retain
their existing paths. Presence labels describe the current selected front, not historical presence.
The back arrow is a locally authored geometric Android vector, matching the existing 24 dp icon
stroke; no image-generation or new palette tokens were used.

The composer uses an inline attachment control, a decorative smile icon, and a round Send with
an explicit disabled state when empty. The native send-as sheet lists current channel-front
speakers first, then other own-account members, keeping pronouns, actual layer labels and the
current selection. It reuses ChatSpeaker defaults and ChatCompose parsing; no system or unsigned
message semantics were added. Configured front sections read the core-resolved authorship pool.
The attachment/options sheet retains file picking, alt text, spoiler flags, CW, polls, audience,
autoproxy and the channel front picker. The new-message FAB opens existing chats and the existing
external DM/shared-space creation flow, including when no external connections are available.
Reply preview includes the actual names and a safe text excerpt with a 48 dp close control.

Additional gaps: the Android send composer already resolves `@front` through the core, so this
pass completes that target only. It has no member/group names resolver or `@everyone` target;
those suggestions are omitted. Reaction, pin, forward, delete and thread actions are
offered without a permission projection; the server refuses what a member may not do. The model has no chat
system-event items. Android uses the existing system file picker rather than a fabricated recent
photo grid, and the emoji decoration does not imply an app-specific emoji picker. The smile and
check vectors are locally authored geometry, recorded with the other assets.

Catch-up snapshots the selected reader's existing read position when the conversation opens,
keeps the divider while the existing core-driven read writer advances, and limits all labels to
messages still visible in the local window. A Change control reuses the soft viewing-as setting;
it does not write a new reader or change the core-selected reading pool. The opening unread
position, explicit member-mention jump and latest-message jump use the same reversed message list.
Folder chips sum visible unread messages, with yellow on the selected unread folder. Account-wide
read tracking continues to use the empty reader key when per-member tracking is off. Row times
use Today/time, Yesterday, weekday or a short date in the device's calendar zone. Poll previews
obey CW before showing a question. Warning badges keep readable ink in both palettes.

Chat info presents existing local data only: a large channel disc, Search, current own-member
layer labels and caught-up positions for internal chats, or directory account membership for
shared spaces. At 840 dp it is a third 220 dp column beside the 240 dp list and flexible
conversation; compact widths open it from overflow. Person accounts hide system/member controls.
There are no new network calls, data operations, notification rules or product semantics.

Further parity limits: the model has no channel membership/role projection for a complete member
count, no live “reading now” signal. Chat info has Media, Files and Links
tabs over every locally synced message the reader can see in the channel (not only the newest 100);
content behind a spoiler or content warning stays covered until tapped. The info pane labels
known own members without claiming channel membership, and omits those unsupported controls.
Read markers contain the last-read message time, so catch-up says “read through” rather than
inventing the time of a reading session. Mention badges count explicit member entities only;
historical `@front` membership cannot be inferred from today's presence. Current-front badges
are descriptive; configurable layers have no invented legacy ring meaning. All counts remain
bounded to the existing up-to-100-message window. Acknowledge/delivery clocks and read ticks
remain omitted; the existing held/slow-mode timer has its real clock icon.

Validation: ChatPresentation has ten JVM tests covering folder classification, CW/poll preview
privacy, own-speaker alignment, grouping boundaries, supported mention completion, independent
layer labels/unavailable presence, reader/timestamp-tie snapshots, typed mention entities,
calendar zones and subtitle/date labels. The repository gate runs Android lint and the complete
existing JVM suite, including speaker parsing, polls, edits, visibility and stage coverage.
No device screenshot acceptance is claimed. The parent owns installation and visual verification
on SM-A566B (also check expanded width, light theme, larger text and keyboard insets). No install,
deploy, push, merge or device/system setting changes were performed in this task.

## UI5 Android implementation and native inspection (2026-10-28)

The owner explicitly authorized phone interaction, overwrite installation and a bounded keep-awake
helper for this pass. Those permissions supersede the older session-specific device restrictions
above. Both the SM-A566B and existing Chorus_A56 emulator were inspected; only the isolated demo
received test messages, posts and channel/image writes. No production deployment was performed.

Channel creation, image change/removal/fallbacks (D-088), native navigation motion, journal feed,
editor and long-read refinement are implemented. Native inspection also corrected the Hanken
variable-font weight axes, duplicate Journal chrome, presence-strip fill and long-title truncation.
The shared core's authorship, visibility, read tracking, sync and existing chat actions remain in use.

The implementation/self-inspection report, source frame IDs, evidence paths, validation and remaining
Figma gaps are in [the UI5 handoff](handoff/ui5-android-2026-10-28.md). This is a first implementation
pass; parent review owns visual acceptance. In particular the full selection-formatting toolbar,
unsupported bookmark/repost/share actions, hardware frame-time measurement, tablet rendering and a
reduced-animation setting-toggle check are not implied by the captures.
