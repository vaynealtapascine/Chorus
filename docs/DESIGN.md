# Chorus — design

**Soft & warm** (D-020). Chorus should feel like a lamp-lit room: warm paper in the day, deep warm
ink at night, rounded shapes, member colours as gentle accents, generous space, quiet motion.
Simple by default; everything else one tap away under **Advanced**.

Principles:

1. **Members are the colour.** The chrome is neutral and warm; colour comes from members (avatar
   rings, name tints, front bars). Never more than a few saturated elements on screen.
2. **Calm over clever.** No badges screaming red; unread uses a soft dot. Errors are gentle and
   say what will happen ("Saved on this phone — will sync when the server wakes up").
3. **One primary action per screen.** Front card → switch. Channel → write. Profile → post.
4. **Basic hides, never removes.** Anything hidden in Basic is reachable in Advanced and never
   changes behaviour just because it's hidden.
5. **Fast feels cozy.** Every tap responds within a frame; skeletons, not spinners.

---

## 1. Tokens

Tokens are defined once in `design/tokens.json` and generated into CSS custom properties (web) and
a Kotlin `ChorusTheme` object (Android) — one source of truth (M3.1/M4.1).

### 1.1 Colour — light ("Paper")

| Token | Value | Use |
| --- | --- | --- |
| `bg` | `#FBF7F2` | app background |
| `surface` | `#FFFFFF` | cards, sheets |
| `surface-2` | `#F4EDE4` | wells, composer, sidebars |
| `surface-3` | `#EADFD2` | pressed/selected |
| `line` | `#E4D8CA` | hairlines |
| `ink` | `#2B2521` | primary text |
| `ink-2` | `#6F6259` | secondary text |
| `ink-3` | `#A09184` | tertiary, timestamps |
| `accent` | `#C0694E` | default accent (terracotta), user-changeable |
| `accent-soft` | `#F6E1D8` | accent backgrounds |
| `ok` | `#5E8C61` | sage |
| `warn` | `#C99A3B` | honey |
| `danger` | `#B5524A` | brick (used sparingly) |
| `focus` | accent at 40 % | focus rings |

### 1.2 Colour — dark ("Ink")

| Token | Value |
| --- | --- |
| `bg` | `#1B1714` |
| `surface` | `#231E1A` |
| `surface-2` | `#2B2520` |
| `surface-3` | `#362E28` |
| `line` | `#3A322B` |
| `ink` | `#F1E9E0` |
| `ink-2` | `#BCAEA1` |
| `ink-3` | `#85776B` |
| `accent` | `#E08A6D` |
| `accent-soft` | `#3E2A22` |

Additional themes (Advanced): **Night sky** (deep blue-violet), **Paper journal** (cream + serif),
**Material You** (Android dynamic colour), **High contrast**, and a custom theme editor (every
token, with live preview and contrast warnings).

### 1.3 Member colours

Members pick any colour. `chorus-core::color::adapt(color, theme)` returns variants guaranteed to
meet WCAG AA for text on `bg`/`surface` in the current theme (M1.8) — adjust lightness in OKLCH,
keep hue and as much chroma as possible:

- `name` — for names in chat/posts (AA on surface).
- `ring` — avatar ring (3:1 on bg).
- `tint` — 8–12 % wash for selected rows, own-message backgrounds (Bubbles style), front bars.

"Member colour intensity" (Advanced): off · subtle (default) · vivid.

### 1.4 Type

- UI/body: **Figtree** (variable), fallback system-ui. Base 16 px web / 16 sp Android.
- Display (profile names, journal titles, empty states): **Fraunces** (soft, SOFT axis 100, WONK
  0), used sparingly.
- Mono: **JetBrains Mono** for code entities.
- Alternates (Advanced): Atkinson Hyperlegible, OpenDyslexic, system font.
- Scale: 12 / 13 / 15 / 16 / 18 / 22 / 28 / 36. Line height 1.45 body, 1.2 headings.
- Text size setting (Basic): 85 %–150 %; respects OS font scale.

### 1.5 Shape, space, elevation

- Radii: 8 (chips, inputs) · 14 (cards, messages in Bubbles) · 20 (sheets) · full (avatars,
  pills). "Roundness" slider in Advanced scales all radii 0.5×–1.5×.
- Spacing: 4-pt grid: 4, 8, 12, 16, 20, 24, 32, 48.
- Density (Advanced): cozy (default) · compact (chat rows −25 %).
- Elevation: almost none; separation by `surface` steps and hairlines. One soft shadow
  (`0 1px 2px rgb(43 37 33 / .06), 0 4px 16px rgb(43 37 33 / .06)`) for sheets/popovers only.
- Avatars: circle by default; squircle/rounded-square in Advanced. Ring = member colour.

### 1.6 Motion

150–220 ms, `cubic-bezier(.2,.8,.2,1)`; switch confirmation gets a small "settle" of avatars into
the front card (≤ 300 ms). Everything respects reduced motion (instant or fade only). Haptics on
Android: light tick on switch, none elsewhere by default.

### 1.7 Iconography and illustration

Rounded-stroke icons (Phosphor "regular" or Lucide, 1.75 px). Empty states use one small, soft
line illustration + one sentence + one action. No mascots.

## 2. Layout

- **Android**: bottom navigation — **Home · Chat · Members · Journal · More**. Front card pinned at
  top of Home; a small front pill in every top bar (tap → switcher).
- **Web, wide (≥ 1024 px)**: left rail (spaces + nav) · list column (channels / members / feeds) ·
  main content · optional right panel (profile, thread, pins, members online). Front pill top-left.
- **Web, narrow**: same as Android.
- Keyboard (web): `Ctrl/⌘K` command palette (switch, jump to channel/member, actions), `Ctrl/⌘S`
  switcher, `Alt+↑/↓` channels, `E` edit last message, `R` reply, `Esc` close.

## 3. Screen inventory

| Screen | Contents | Primary action |
| --- | --- | --- |
| **Home** | Front card (avatars, levels, since/duration), quick switch row (pinned + recent), review cards, "today" timeline snippet, unread summary | Switch |
| **Switcher** | Search, pinned, recent, groups tree, selected tray (drag order, level toggle, star primary), note, time, notify choice | Switch |
| **Front history** | Lane timeline (zoom day/week/month), list view, edit/insert switch | — |
| **Chat** | Spaces rail, channel list, message list, composer | Send |
| **Thread / Pins / Search** | Side panel (web) / full screen (Android) | — |
| **Members** | Grid/list toggle, search, filter chips (group, state, archived), sort | Add member |
| **Member editor** | Basic fields; sections for Proxy & sigils, Custom fields, Privacy, Notifications | Save (auto-save) |
| **Profile** | Banner, avatar, names, pronouns, sigils, bio, fields, stats, tabs (Posts, Replies, Journal, Highlights, Media, Relationships) | Post as this member |
| **Journal** | System timeline, feeds tabs (Following, Lists, custom feeds), compose | Write |
| **Compose post** | Author chip(s), note/entry toggle, title (entry), body, mood, tags, CW, visibility | Post |
| **Feed editor** | Query field with syntax highlight + chips, live preview, visibility | Save |
| **Stage** | Selection overlay, context-only/hidden toggles, filters, style presets, redaction, fake names/times, "Capture" | Capture |
| **Trash** | Deleted items by type, search, restore | Restore |
| **Insights** | Dashboard cards; each opens a full chart with table + CSV | — |
| **Sharing** | Followers, requests, buckets, ceilings (presets), following + prefs | — |
| **Notifications** | History list, per-kind settings | — |
| **Settings** | Basic list + "Advanced" entry at the bottom | — |
| **Sync issues** | Rejected ops with reason, retry/discard/copy | — |
| **Onboarding** | Invite, system name, terminology, first members / PK import, first front | Continue |

## 4. Key components

- **Front card**: stacked avatars (primary largest, ring glow), names with level badges
  ("co-con", "present" in `ink-2`), duration ticking once a minute. Tap → switcher; long-press →
  add co-fronter.
- **Message row (Chorus style)**: avatar(s) left (multi-author = overlapping avatar stack up to 3,
  then "+2"), names in member `name` colour joined with " & ", timestamp `ink-3`, grouped
  consecutive messages by same author set within 5 min. Offline marker: small cloud-slash icon +
  "sent offline · synced 14:32" on hover/tap.
- **Speaker chip**: avatar + name; tapping opens a popover of fronters then recents; when the
  composer text starts with a sigil/tag, the chip previews the parsed authors live.
- **Context-only row** (stage): 40 % opacity, text clamped to one line or replaced with
  "3 messages" pill.
- **Review card**: soft `warn` tint, both switches side by side ("Phone 14:02 · Desk 14:03"),
  buttons Keep both / Keep phone / Keep desk / Merge.
- **Empty/offline banners**: `surface-2` bar with icon + one sentence; never modal.

## 5. Display styles (chat and stage)

Presets apply to the chat itself (per device) and to stage mode:

| Style | Description |
| --- | --- |
| Chorus | Default described above. |
| Discord-ish | Compact rows, left avatars, names coloured, no bubbles. |
| Bubbles | iMessage-like: own-system messages right in member `tint`, others left. |
| Card | Twitter-style card per item (for posts/stage). |
| Transcript | `Name: text` lines, no avatars, monospace optional. |
| Minimal | Text only with thin member-colour bar at left. |

## 6. Basic vs Advanced settings

**Basic** (one screen, ~12 rows):

1. Appearance: theme (auto/light/dark), accent, text size.
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
  shortcuts on/off, slow mode.
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

## 7. Accessibility

WCAG 2.2 AA: contrast (enforced for member colours via `adapt`), 48 dp touch targets, TalkBack
and screen-reader labels (avatars read as "Kai, fronting, primary"), full keyboard support on
web, no information by colour alone (levels have text badges), captions/alt text prompts on
images, reduced motion honoured.

## 8. Copy voice

Warm, short, plain. "Kai is here" not "Kai has been registered as fronting". Offline: "Saved on
this phone". Errors say what happens next. Never guilt, never urgency. Terminology substitutions
apply everywhere including notifications.
