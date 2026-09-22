# Chorus — product spec

> A cozy home for plural systems: talk to each other, keep journals, switch in one tap, and let the
> people you trust know who's around — on your terms.

Status: v1 spec, 2026-09-23. Decisions referenced as `D-xxx` live in [DECISIONS.md](DECISIONS.md).

---

## 1. Goals and non-goals

**Goals**

1. Replace PluralKit for day-to-day system life, *outside* Discord: switching, member profiles,
   and a chat that the system owns.
2. Discord-grade chat inside the system and with friends, with every message sent *as* members.
3. Twitter/Bluesky-style member profiles and journals that feel like a small social network the
   system lives in.
4. Screenshot-ready "stage" views so a system can share a conversation or post without exposing
   everything around it.
5. Quick switching from the Android home screen, even with hundreds of members and deep
   subsystems.
6. Switch notifications for friends and partners, with privacy controls (delay, fuzzing, digests)
   that are honest: nothing leaks through a side door.
7. Works fully offline on the phone and the web; resyncs without drama; never loses data.
8. Data that is pleasant to analyse: plain SQL tables, documented views, exports, a live stream.
9. Cozy, calm, beautiful; simple by default, deeply customizable under **Advanced**.

**Non-goals (v1)**

- Discord integration of any kind (D-005). PluralKit is import-only.
- Public internet exposure. Chorus lives on a tailnet (D-033).
- End-to-end encryption (D-033). Devices encrypt at rest.
- iOS app. The PWA is the answer for non-Android users.
- Voice/video calls. (Voice/video *messages* are L1.)

## 2. Concepts and vocabulary

| Concept | Meaning |
| --- | --- |
| **Server** | One Chorus instance on the owner's PC, reachable over Tailscale. |
| **Account** | A login identity. Kind `system` (plural system) or `person` (singlet). |
| **Device** | A phone/browser enrolled to an account with its own keypair. |
| **Member** | A person inside an account. Systems have many; person accounts have one (D-003). The word is customizable (D-004). |
| **Group** | A named set of members. Kind `subsystem` (tree, can front as a unit) or `group` (tag-like, flat or nested). A member can be in many. |
| **Custom state** | A non-member front entry ("blurry", "dissociated", "unknown"), with colour and icon. |
| **Front** | Who is present right now, as ordered entries with a **level**: `front`, `cocon` (co-conscious), `present` (present-transient). One entry may be **primary**. Subjects are members, groups (subsystem fronts) or custom states (D-011). |
| **Switch** | A recorded change to the front. Full-snapshot or delta (add/remove/update). |
| **Space** | A container of channels. Each system has one `internal` space; `shared` spaces are shared between accounts; a `dm` space is a DM between two accounts. |
| **Channel** | A text channel in a space. Kinds: `text`, `thread`, `member_dm` (a DM between two members inside one system). |
| **Message** | Sent as one or more members (multi-author, D-007). |
| **Post** | A profile post: short **note** or long-form **entry**. |
| **Follower** | An account that follows a system (or person) and may receive notifications and see permitted content. |
| **Bucket** | A named privacy audience ("Partners", "Friends") a system assigns followers into. |
| **Stage** | A screenshot view of a conversation/post with selected items shown and others hidden or context-only. |

## 3. Accounts, onboarding, privacy

### 3.1 Onboarding

1. The server admin runs `chorus-server invite --kind system|person` or uses the Admin screen. An
   invite is a one-time URL (`https://chorus.<domain>/i/<code>`) and a QR code.
2. The new user opens it in the Android app (deep link) or the browser. The device generates a
   keypair (Android Keystore / WebCrypto non-extractable), registers the public key, and becomes
   the first device of a new account.
3. System setup (systems only, 4 short steps, all skippable): name + avatar, terminology, add a few
   members (or **Import from PluralKit**), pick your first front.
4. Additional devices: from Settings → Devices → "Add a device" produces a device-invite QR for
   the same account.

### 3.2 Privacy model

- Every visible object (member, field value, group, post, relationship, front info, profile
  section) has a **visibility**: `private` (only the owning account) · `buckets` (listed buckets)
  · `followers` (all accepted followers) · `server` (every account on the server).
- Defaults per object type are set in Settings → Privacy (Basic: one "new things are…" choice;
  Advanced: per type and per field).
- Inside a system, everything is visible to the system except **member-locked** content: a member
  may set a PIN/biometric gate for their private journal entries, DMs and locked channels (D-021).
- Follows are requests; the system accepts, assigns buckets, and sets the notification ceiling
  (NOTIFICATIONS.md).
- A follower sees a **follower view** of the system that respects delay/fuzz rules (D-016).

## 4. Members, groups, fronting

### 4.1 Members

Fields: name, display name, pronouns, avatar, banner, colour, description (rich text), birthday,
**sigils** (one or more emoji/short strings, D-007), **proxy tags** (prefix/suffix pairs), custom
fields (typed: text, long text, number, date, select, multi-select, boolean, colour, URL, member
reference, rating), pinned post, archived flag, sort key, per-field visibility.

Lists at 500 members must stay instant: virtualised grid/list, fuzzy search on name/display
name/pronouns/sigil/tags, filters by group/state/archived, sort by name/recent front/front time/
custom order.

### 4.2 Groups and subsystems

- Tree of subsystems (any depth) plus free groups. Drag to reorder/nest (web), long-press move
  (Android).
- A subsystem has `can_front` (default on): it can be put in front as a unit ("the Stars are
  fronting") — shows as one entry with its avatar; members inside are not individually listed
  unless also added.
- Group colour/icon/avatar; collapsed/expanded state per device.

### 4.3 Front and switching

- **Front card** (home, everywhere at top on web): current entries in order, level badges, primary
  highlighted, "since 14:05 · 2h 13m".
- **Switcher** (full screen on Android, popover on web): search + recent + pinned + groups tree;
  tap to toggle; each selected entry has a level (front/cocon/present), drag to order, star for
  primary. Commit button: *Switch*. Optional note, time override ("switched at 13:40"), and
  notification choice for this switch (default/silent/notify now/extra delay).
- **Quick actions**: "switch out" (empty front), "add co-fronter", "same as yesterday evening"
  (recent front sets as chips).
- **Undo**: every switch shows an Undo snackbar for 10 s → emits `front.retract`.
- **History**: a horizontal timeline (lanes per member, colour bars; levels as bar
  opacity/pattern), plus a list. Edit a past switch (time/entries) → `front.amend`. Insert a
  missed switch in the past → normal switch with past `occurred_at`; later state is recomputed.
- **Review cards** (D-037): shown on the front card when independent switches from two devices
  landed within the review window. Actions: keep both (default, dismiss), keep one (retract the
  other), merge (union at the later time).

## 5. Chat

### 5.1 Structure

- Spaces rail (internal space first, then shared spaces, then DMs) → categories → channels.
- Channel settings: name, topic, icon, colour, autoproxy mode, sticky speaker, notification level,
  slow-mode (Advanced), archive, **permissions** (below).
- **Channel permissions** (Discord-style, D-047): each space has roles (`owner`, `admin`,
  `member`, `read_only`, plus custom roles in shared spaces); a channel can override permissions
  per role or per account. Permissions: `view`, `send`, `react`, `thread`, `pin`, `manage`.
  Deny beats allow at the same level; account overrides beat role overrides. This also lets a
  system share **one internal channel** with an outside account (e.g. a partner can view
  #announcements) without sharing the rest of the internal space.
- **Member DMs** inside the internal space: a `member_dm` channel between two members (or more:
  "member group DM").
- **Threads**: any message can spawn a thread (a `thread` channel with `parent_message_id`).
  Thread preview under the parent: reply count, last repliers' avatars.

### 5.2 Composer and speakers

- **Speaker chip** left of the input: shows the current speaker(s); defaults to primary fronter
  (or channel's sticky speaker, or autoproxy latch). Tap → quick picker (fronters first, then
  recent, then search). Choosing a speaker here never registers a switch.
- **Proxy tags** (PK-compatible): `k: hello`, `[hello]`, etc. Parsed on send; tags stripped.
- **Sigils**: a leading run of known sigils followed by whitespace sets authors:
  `🌌🔖❤️‍🔥 we all agree` → three authors, in that order.
- **Consecutive tags** for multi-author: `k: a: text` (each prefix-tag consumed left to right while
  it matches; suffix tags are only honoured for single-author messages).
- **Joint by default** (D-045): one annotation at the start = everyone in it says the whole
  message together.
- **Segments** (D-045): a *new line* that starts with an annotation (sigils or prefix tags, then
  whitespace or an optional `=>`) starts a new segment spoken by those members. Lines without an
  annotation continue the current segment. Each segment can itself be single- or multi-author:

  ```
  🌌 I think we should go
  🔖❤️‍🔥=> we don't
  and this line is still 🔖❤️‍🔥's
  ```

  → one message, two segments (🌌; 🔖+❤️‍🔥). The first line with no annotation uses the speaker
  chip. A backslash before a line-start sigil escapes it. Rendered as one message with small
  name/avatar sub-headers per segment. Toggle in Advanced (default on).
- Parsing lives in `chorus-core` (M1.6) so all clients agree. A preview above the composer shows
  who it will be sent as before sending.
- Autoproxy modes per channel (Advanced): `off` · `front` (primary fronter) · `latch` (last
  explicit speaker) · `member` (locked to one member).
- Formatting parity with Telegram: **bold**, *italic*, underline, ~~strike~~, spoiler, `code`,
  pre blocks with language, links with custom text, blockquote, expandable blockquote, mentions,
  custom emoji (the server-wide set, §5.5). Input via markdown-like shortcuts *and* a selection toolbar;
  stored as text + entities (D-041).

### 5.3 Message features

| Feature | Behaviour |
| --- | --- |
| Reply | Inline quote bar of the target. Jump to target. |
| Reply elsewhere | "Reply in…" picks another channel/thread/DM; the reply shows a cross-channel reference card linking back (only rendered for readers who can see the original). |
| Reply privately | Shortcut for replying in the DM with the author's account (shared spaces) or in a member DM (internal). |
| Quote | Full quote, or **partial**: select text in a message → "Quote" → the quote stores message id + range + text snapshot. |
| Forward | To any channel/space/DM the user can post in. Stores origin reference + snapshot; shows "Forwarded from Kai · #vent". Can forward **a selection**: one message, a text range of a message, or several messages as one bundle (D-048). The snapshot is what the recipient sees, so it works even when they can't read the original — forwarding member-visible, locked or internal content outward asks for confirmation once. |
| Edit | Keeps revisions; "edited" marker opens history. |
| Delete | Tombstone ("message deleted" placeholder, configurable to vanish). Always **restorable** from Trash (D-053). |
| Pin | Per channel, pinned list panel. |
| Reactions | Emoji or custom emoji, **reacted as a member** (defaults to current speaker). Hover shows who. |
| Mentions | `@member`, `@group` (all members in group), `@account` in shared spaces, `@front` (current fronters). Mention inbox per member. |
| Read states | Per account; optionally **per member** (Advanced: "track reading per member") → "Kai hasn't seen this" dot. |
| Attachments | Images (thumbnails, gallery, alt text), files; queued offline, upload resumable. |
| Search | Full-text (FTS5), filters: `from:`, `in:`, `has:image`, `before:`/`after:`, `is:pinned`. |
| Offline marker | Messages composed offline sort at original time, marked "sent offline · synced 14:32" (D-039). |

### 5.4 Hidden messages (D-010)

- **Spoiler** entity (tap to reveal), spoiler attachments.
- **Content warning**: message has `cw` label; body collapsed until tapped; per-user setting to
  auto-expand.
- **Visible to members**: in internal space, a message may be restricted to chosen members; it is
  shown only when one of them is fronting/co-con or selected as "viewing as" (soft privacy, not
  security — say so in the UI).
- **System-only aside**: in a shared space, a message only the sending system sees (e.g. members
  talking among themselves in context).
- **Stage hiding**: see §7; not stored on the message.

### 5.5 Custom emoji (D-054)

- One **server-wide** set of small uploaded images, used as `:name:` anywhere text is written
  (messages, posts, bios, display names) and as reactions. No per-space sets, no stickers.
- Upload: PNG/WebP/GIF (animated allowed), square-cropped in the uploader, stored at 128 px
  (≤ 256 KB). Names are unique, `a-z0-9_`, 2–32 chars; optional aliases and a category.
- Who manages: server admins. Server setting (Advanced): "Everyone can add emoji" (off by
  default); adders can edit/delete their own.
- Picker: tabs for recent, custom (by category), then standard emoji; search matches names and
  aliases. Typing `:ka` autocompletes.
- Deleting an emoji is a tombstone like everything else: old messages keep rendering it (greyed in
  the picker under "Retired"); restorable from Trash.
- Offline: the emoji set syncs to every device (it's small), so rendering and the picker work
  offline; new uploads queue like attachments.

## 6. Profiles and journals

### 6.1 Profile page (per member)

Banner, avatar with colour ring, display name, pronouns, sigils, bio, custom fields, groups,
relationships, **pinned post**, **stats** (front time this week/month, last fronted, message count
— each individually hideable), tabs:

- **Posts** — notes and entries by this member.
- **Replies** — their replies.
- **Journal** — long-form entries only (calendar strip, mood colouring).
- **Highlights** — posts curated onto this profile: their own or others' (by any member, or by
  permitted friends).
- **Media** — attachments.
- **Relationships** — list + small graph.

### 6.2 Posts

- **Note**: short post (soft limit 1000 chars, configurable), attachments, CW, mood, tags.
- **Entry**: long-form, title, rich text, mood, tags, optional "written during" front snapshot
  auto-attached.
- Replies, quotes (full/partial), reposts, reactions — all as members. Multi-author posts allowed.
- Visibility per post; default per member.
- Drafts sync across devices.

### 6.3 Relationships

Typed, directed or mutual links between a member and: another member of the same system, a member
of another system, a person account, or a free-text external person. Relationship types are
system-defined (name, inverse name, symmetric?, colour, icon). Examples: sibling, partner,
protector of / protected by, caretaker, source, friend.

### 6.4 Lists and feeds

- **Lists** (Twitter-style): named sets of members (own or others'). A list has a timeline.
- **Feeds** (Bluesky-style): saved filters written in a small language (M1.7), e.g.

  ```
  from:@stars kind:entry mood:tired -tag:vent since:30d
  from:list:"close friends" has:image
  (from:@kai or from:@juniper) reply:false
  ```

  Grammar: space = AND, `or` / parentheses, leading `-` = NOT. Keys: `from:` (member, group via
  `@group`, list via `list:`), `kind:` (note/entry/message), `tag:`, `mood:`, `has:` (image/
  attachment/link/poll), `reply:` (true/false), `in:` (channel), `since:`/`until:` (date or
  relative), `fronting:` (author was fronting when posted), free text. A feed has a visibility and
  can be shared with followers or copied by others. Editor has a live preview and chips for
  non-typists.

### 6.5 System timeline

One chronological stream of the system: posts, entries (title only), switches (compact rows), and
channel highlights (pinned messages). Filterable; this is also where review cards and "sent
offline" batches appear.

## 7. Stage mode (screenshots)

Enter from a channel, thread, post or profile ("Stage…").

1. **Select**: tap messages/posts to include. Unselected items become, per choice:
   *hidden* (removed, gap closes), *context-only* (greyed, collapsed to one line, "3 messages"),
   or *visible*.
2. **Filters**: "only these members" (hides other members' replies), hide reactions, hide reply
   bars, crop reply chains to N levels, hide thread previews.
3. **Style preset**: Chorus (default), Discord-ish (compact, left avatars), Bubbles (iMessage-like),
   Card (Twitter-style post card), Transcript (plain text-like, no avatars), Minimal. Theme:
   light/dark/custom palette; width presets (phone/square/wide).
4. **Redaction**: blur avatars, blur or replace names ("Member A/B"), hide timestamps, hide channel
   header, blur attachments, redact selected text spans.
5. **Fake names and times** (D-050): rename any author for this stage (free text, optional
   colour/placeholder avatar), and change shown timestamps — shift all by an offset, set a start
   time and keep gaps, or set each item by hand; also hide/replace dates and the channel name.
   These overrides live only in the stage (and in a saved stage); they never touch real data.
6. **Capture**: the app hides its own chrome, then the user takes a native screenshot; Android
   also offers the system long-screenshot. (Render-to-PNG is L3.)
7. **Save stage** (optional): named, re-openable, stored as a view definition (not a copy of data).

## 8. Insights and data

- **Dashboards** (Insights tab): front time per member (stacked bars by day/week/month), time by
  level, switches per day, hour × weekday heatmap, co-front network graph, longest/shortest fronts,
  "last seen" table, message/post volume per member, per-channel activity. Every chart has
  "View data" (table) and "Export CSV".
- **Live**: SSE stream + WebSocket for scripts, Grafana, OBS overlays (API.md).
- **Webhooks**: switch, message, post, member events; HMAC-signed.
- **Exports**: full JSONL op log, tidy CSV per view, SQLite copy of the account's data; API tokens
  with scopes. All documented in DATA_MODEL.md §6.

## 8a. Trash and permanence (D-053)

The server keeps a permanent canonical copy of everything: deleting on a device is a tombstone,
never an erase, and a device evicting old data locally to save space deletes nothing.

- **Trash** screen (Settings → Data → Trash, and "Show deleted" in a channel's menu): deleted
  messages, posts, entries, members, groups, channels — searchable, no expiry.
- **Restore** from Android or web puts the item back exactly where it was (same id, same time,
  revisions intact). Only the account that deleted it (or authored it) can restore it.
- Edit history is kept forever too.
- The only way to truly erase something is the server admin CLI (`chorus-server purge`), with a
  confirmation and a log entry. There is no purge in the apps.

## 9. Performance budgets (requirements)

| Path | Budget |
| --- | --- |
| Widget tap → switch recorded locally, widget shows new front | ≤ 150 ms |
| Switch → visible on other online devices of the same account | ≤ 1 s (p95, tailnet) |
| Send message → rendered locally | ≤ 50 ms |
| Open a channel with 50k messages → first screen | ≤ 150 ms (web, Android mid-range) |
| Scroll history | 60 fps; pages of 100 messages; virtualised |
| Member search over 500 members | ≤ 16 ms per keystroke |
| Android cold start to front card | ≤ 1 s |
| Web initial JS | ≤ 200 KB gz (routes lazy-loaded; wasm core loaded async, ≤ 300 KB gz) |
| Server op ingestion | ≥ 2 000 ops/s sustained on the owner's PC |
| Reconnect after 7 days offline with 5 000 queued local ops | fully synced ≤ 10 s |
| Rebuild all projections from a 1M-op log | ≤ 60 s |

## 10. Customization

Everything below exists; Basic shows only the starred items (DESIGN.md §6 has the full list).

- ★ Theme (light/dark/auto), ★ accent colour, ★ terminology, ★ text size, ★ notification basics.
- Density, corner roundness, font choice, bubble style, member-colour intensity, avatar shape,
  timestamp format, 12/24h, week start, reduced motion, haptic strength.
- Widget layouts, per-channel autoproxy, parsing rules, review window, clock-skew correction,
  sync intervals, metered-network behaviour, attachment auto-download rules, retention.
- Developer: op log viewer, projection hash, force resync, export debug bundle.

## 11. Release scope

- **v1** = milestones M0–M12 in PROGRESS.md.
- **Later** = L1–L6. The schema already reserves `kind`/fields so they don't need migrations of
  existing data.
