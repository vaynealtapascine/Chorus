# Chorus — user flows to design

For the owner's Figma pass (2026-09-24). Every flow the v1 app has or will have, grouped by area.
Whoever implements a design (gpt-6-sol for most UI; Claude audits) finds its frame by the flow ID.

**How to hand designs back.** Name each Figma frame by flow ID and platform, e.g.
`F3.2 · Android · dark` or `F3.2 · Web desktop · light`. Where a flow has states, one frame per
state (`F3.2 · Android · empty`). Platforms: **A** = Android app (phone, ~390 px), **W** = web
app on a desktop browser (~1280 px, the installed PWA), **Wm** = web on a phone browser, **H** =
Chorus Home (the one-click home-PC install). Both themes (light "Paper", dark "Ink", DESIGN §1)
matter, but one theme per flow is enough where the other is a straight token swap.

**States worth a frame wherever they apply:** empty (first use), loading, offline / server
asleep ("saved on this device"), error with what happens next, and — for anything with
privacy consequences — the moment the person chooses who can see it.

Existing screens are working but unstyled beyond the tokens; nothing below is locked in.

---

## F1 · Getting started

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F1.1 | Open an invite link → name the system (or yourself) → handle → this device's name → Home | A, W | The link may arrive by QR, paste or tap. System vs person invite changes the wording. |
| F1.2 | Choose terminology (system/collective/…, fronting/switching words) | A, W | SPEC §3.1; applies everywhere, notifications included. |
| F1.3 | Add the first members (quick: sigil, name, colour) or import from PluralKit | A, W | PK import: pick file → preview counts → import → result (M11). |
| F1.4 | Set the first front | A, W | Ends onboarding. |
| F1.5 | Person account onboarding (no members; "for systems" notes where member UI would be) | A, W | Person accounts hide the switcher, Members, History, widget tiles. |
| F1.6 | Link another device: show QR/link on one device → scan/open on the other | A, W | 1-day, one-use. Also "Sign out" a lost device from the list. |

## F2 · Chorus Home (one-click install on a home PC, HOME.md)

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F2.1 | Download page: what Chorus Home is, "Windows protected your PC → More info → Run anyway" explained | landing page | Until the download is code-signed. |
| F2.2 | First run in the browser: welcome → name → creates the first account on this PC | H (W) | Replaces F1.1 on a fresh Home install (no invite needed). |
| F2.3 | Add a phone: install the app (link) → scan this QR → the phone joins over the wifi | H, A | The QR opens the app (chorus:// link); explain "same wifi as this computer". |
| F2.4 | *This computer* settings: address/port, "Let devices on my wifi connect", backups (folder, how many), version, restart notice | H | Only visible on the PC itself; admin only. Saving restarts Chorus (a few seconds). |
| F2.5 | Phone away from home: works offline, "will sync when you're home" | A | No error tone; it's expected. |
| F2.6 | Other browsers on the wifi (a laptop, an iPhone) — what to tell them (Tailscale, or the Android app) | H | A short explainer, not a blocker. |
| F2.7 | Uninstall (Windows *Apps*) — keep or delete data | H | Windows' own UI plus our confirmation wording. |

## F3 · Fronting and switching

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F3.1 | Home: front card (who, since, duration), quick-switch row, review cards, today snippet, unread summary | A, W | The most-seen screen. |
| F3.2 | One-tap switch from quick switch, with Undo toast | A, W | Undo is always one tap. |
| F3.3 | Full switcher: search, pinned, recent, groups tree, selected tray (order, level front/co-con/present, primary star), note, time (now / earlier), notify choice | A, W | Big systems: hundreds of members, nested subsystems. |
| F3.4 | Add a co-fronter (long-press the front card) / remove one | A, W | |
| F3.5 | Log a switch in the past (backdate) | A, W | |
| F3.6 | Review card: two devices switched at nearly the same time → keep both / keep one / merge | A, W | DESIGN §4. |
| F3.7 | Front history: lane timeline (day/week/month zoom), list view, edit / insert / retract a switch | A, W | |
| F3.8 | Home-screen widget: grid of recents/pins, folders, mode chip (replace/add), undo, "for systems" note on person accounts | A | Several sizes; RemoteViews limits (CLIENTS §3). |
| F3.9 | Search launcher over the home screen (from the widget or the app shortcut): type → switch | A | ~110 ms to appear. |
| F3.10 | Member states (e.g. "tired", "nonverbal") alongside fronting | A, W | Custom states from Settings. |

## F4 · Members and groups

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F4.1 | Members list: grid/list, search, filter chips (group, state, archived), sort | A, W | |
| F4.2 | Add / edit a member: basics (name, display name, pronouns, colour, avatar crop, sigils) | A, W | Auto-save. |
| F4.3 | Member editor sections: proxy tags & sigils, custom fields, privacy (who can see), notifications (always/fronting/never) | A, W | Privacy choice deserves its own frame. |
| F4.4 | Groups and subsystems: create, nest, colour, move members | A, W | |
| F4.5 | Archive / restore a member | A, W | |
| F4.6 | Custom fields definitions (Settings) | W | Advanced. |

## F5 · Chat

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F5.1 | Spaces and channels: internal space, shared spaces, DMs; switching between them | A, W | Android now has a one-row header with a space menu (2026-09-24); design it properly. |
| F5.2 | Message list: grouping, multi-author rows, replies, forwards, pins, reactions, edited, sent-offline marker | A, W | DESIGN §4 message row. |
| F5.3 | Composer: who's speaking (chip + fronters/recents), live preview of sigils/proxy tags, formatting, emoji, attachments, send | A, W | Android composer is one line now; options behind "⋯". |
| F5.4 | Hidden messages: CW (collapsed until tapped), visible to chosen members only, asides (only my system) in shared spaces | A, W | Make "who can see this" unmistakable before sending. |
| F5.5 | "Viewing as" a member (soft visibility view) in the internal space | A, W | |
| F5.6 | Reply, quote, forward (with snapshot), edit, delete (→ Trash), react | A, W | |
| F5.7 | Threads (side panel on web, full screen on Android) and Pins | A, W | |
| F5.8 | Attachments: pick, alt text, spoiler, upload progress (resumes), viewer | A, W | |
| F5.9 | Search messages: filters (from:, in:, has:, before/after), results, jump to message, "load more from server" | A, W | Offline: searches this device (D-070). |
| F5.10 | Create a shared space with other accounts; start a DM from People; leave | A, W | |
| F5.11 | Channel permissions: roles, per-role/per-account allow/deny, share one internal channel outward | W | Advanced; being built (M5.10). |
| F5.12 | Custom emoji: upload with crop, name, aliases, category, retire (server admins) | W | Settings → Server. |
| F5.13 | Mentions (@member, @front) and read state / unread jump pill | A, W | |

## F6 · Profiles and journals

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F6.1 | Member profile: banner, avatar, names, pronouns, sigils, bio, fields, stats, pinned post, tabs (Posts, Replies, Journal, Highlights, Media, Relationships) | A, W | |
| F6.2 | Write a post: note or long-form entry, author(s), title, body, mood, tags, CW, audience (only us / followers / chosen groups / everyone on server) | A, W | Audience choice = privacy moment. |
| F6.3 | Journal timeline: system timeline, Following, Lists, custom feeds | A, W | |
| F6.4 | Replies, quotes, reposts, reactions across accounts | A, W | Reactions show the reacting member to readers — say so. |
| F6.5 | Highlights curation; relationships and relationship types | A, W | |
| F6.6 | Lists: create, add members/accounts | A, W | |
| F6.7 | Feed editor: filter language with chips + syntax help, live preview, share with followers | A, W | A feed that filters by who was fronting shows a warning when shared and when opened (D-069). |
| F6.8 | Feeds shared with me | A, W | |

## F7 · Sharing and notifications (people you trust)

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F7.1 | People: follow someone, follow requests (accept with a preset), followers, following | A, W | |
| F7.2 | Per-follower ceilings: presets (Close / Gentle / …), delay, time fuzzing, digest, buckets, per-member overrides | A, W | Explain delay and fuzzing plainly. |
| F7.3 | Following someone: their view (who's around, as far as they share), my notification prefs for them, quiet hours | A, W | |
| F7.4 | Notifications inbox (history) and per-kind settings (mentions, DMs, replies, own-switch pings) | A, W | |
| F7.5 | Turn on notifications on this device (Android distributor / browser permission), "Send a test" | A, W | Test exists on the web (2026-09-24). |
| F7.6 | Notification content itself: switch, digest, mention, DM, reply — with inline Reply and "reply as" | A, W | System notification layouts. |

## F8 · Stage mode (screenshots)

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F8.1 | Enter stage from a chat or a post → select messages → context-only / hidden → style preset → redact names / fake names and times → capture | A, W | SPEC §7. |
| F8.2 | Reply-depth crop and filters | A, W | |

## F9 · Insights, data and history

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F9.1 | Insights dashboard (cards) → full chart with table and CSV | A, W | |
| F9.2 | Your data: exports (op log, CSV, SQLite), full export bundle with files (prepare → progress → download) | W (A later) | Bundle approved for v1 (D-068). |
| F9.3 | API tokens (create, scopes, copy once, revoke) and stream overlays | W | Advanced. |
| F9.4 | Webhooks: add, events, test, deliveries | W | Advanced. |
| F9.5 | Trash: by type, search, restore, "gone for good" purge wording | A, W | D-053. |
| F9.6 | Keep everything on this device: setting, "Sync everything now", progress, space used (D-070) | A, W | |

## F10 · Settings and system states

| ID | Flow | Platforms | Notes |
| --- | --- | --- | --- |
| F10.1 | Settings: Basic list + Advanced (DESIGN §6) | A, W | One place for account prefs. |
| F10.2 | Appearance: theme, display style (chat and stage), density | A, W | |
| F10.3 | Offline / server asleep banner; reconnecting; "saved on this device" | A, W | Never modal. |
| F10.4 | Sync issues: rejected ops with reason → retry / discard / copy | A, W | |
| F10.5 | App update available (Android in-app updater): what's new → install | A | M12.3. |
| F10.6 | Admin: server health (backups, errors, connected devices), restore in progress (N of M devices back → Close) | W | Admin only. |
| F10.7 | Rate limited ("catching its breath — try again in N seconds") and other errors | A, W | Tone: DESIGN §8. |
| F10.8 | Install the web app (PWA) prompt / "Add to home screen" | W, Wm | |

---

## Suggested order

The flows used every day first: F3.1–F3.3, F5.1–F5.4, F3.8 (widget), F6.2–F6.3, F7.1–F7.2, then
the Chorus Home path (F2.2–F2.4) so it can ship with v1, then the rest.
