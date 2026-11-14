# D-132 for the clients: Handover and Notes to ourselves (F5.15, F5.19, F0.1)

The contract the web and Android screens build on. The core and server half is done (D-162,
DATA_MODEL §4.5, NOTIFICATIONS §7); this file says exactly what exists, what to write, where to
read it, and what is not built. Where it says "not built", it isn't: don't fake it on the client.

**Deploy order.** The server now gives every **person** an internal space (named "Notes") holding
only its Notes channel, and today's clients would show it as an "Internal" chat. Ship the client
work below before (or with) the server deploy that carries D-162.

## 1. Finding the two channels

Both are ordinary channels in the account's own internal space, made by the server (device
`server`) at enrolment and, for older accounts, at the first start of the D-162 server. They
reach every device through normal sync.

- **Which is which:** the projection row `rows.channel[<id>].fields.special` is `"handover"` or
  `"notes"` (absent/`null` on every other channel). Never match by name: older systems may also
  have ordinary channels a person named "Handover" or "Notes to ourselves"; those stay ordinary.
- **Which one to use:** the live one (`deleted_at` null) in a space of `kind: "internal"` the
  account owns (`created_by_account_id` = our account). There is at most one live of each per
  space. If the user deleted it, it is in Trash: offer **Restore** (`channel.restore`), never a
  new `channel.create` (the server refuses a second live one, and refuses a restore while
  another is live).
- **Names** as made: "Handover", "Notes to ourselves"; a person's is "Notes to self". Users may
  rename them (`channel.set {name}`), so show the row's `name`, with the role's icon/subtitle.
- **Persons (singlets, D-093):** a person's internal space exists only to hold Notes. Hide the
  space everywhere (no Internal folder, no space header, no members, no channel list, no
  "Internal · only …" info line); show only the Notes row in the chat list, and Notes as a
  destination in the forward sheet. A person has no Handover.
- **Nothing else changes in those chats:** replies, quotes, attachments, voice, search, pins,
  Trash and exports work as in any channel. The special channels can't be shared: hide "Share
  with…"/guest/permission UI for them (the server refuses `channel.set_permission` on them, and
  `space.join` of anyone else into an internal space). Hide "Delete" or confirm it as "moves to
  Trash".

## 2. Ops to write

All in the internal space's scope `space:<space id>`; envelope as usual.

| Action | Op | Entity | Payload |
| --- | --- | --- | --- |
| Send a handover | `message.send` | new message id | the usual message fields plus `handover: {deliver: "now" \| "next_switch", checklist?: [{id, text}]}`; only in the `handover` channel |
| Acknowledge / Read (Home) | `handover.ack` | the handover's message id | `{message_id: <same id>, member_id: <speaker>}` |
| Tick / untick an item | `checklist.set` | the handover's message id | `{message_id: <same id>, item_id, done: true \| false, member_id: <speaker>}` |
| Save to notes | `message.forward` | new message id | an ordinary forward into the `notes` channel (D-048 snapshot) |

Rules the server enforces (and core `validate_op` checks the shapes): `handover` only on
`message.send`, fixed at sending (an edit can't carry it; send another handover to change it,
A8); `deliver` exactly `now` or `next_switch`; `checklist` absent or 1–30 items, `id` 1–16
characters, `text` 1–200, ids unique (use short ids like `"a"`, `"b"`, or a counter);
`message_id` equal to the op's entity; `member_id` one of our own **live** members (the speaker:
the current primary fronter, as everywhere else); a tick's `item_id` one the checklist has. A
handover in any other channel, or an ack on a message that isn't a handover, is refused
(`forbidden`, shows as a sync issue). Acks and ticks are LWW: a second ack by the same member
only moves `acked_at`; the latest tick on an item wins.

There is no "un-acknowledge" op. Edits of a handover's text are ordinary `message.edit`s.

## 3. Where each value is read (core projection, as clients see it)

| Value | Where |
| --- | --- |
| The channel's role | `rows.channel[<channel>].fields.special` |
| A handover's definition | `rows.message[<id>].fields.handover` = `{deliver, checklist?}`; `null`/absent = an ordinary message |
| Its authors | `rows.message[<id>].fields.authors` |
| When it was sent (`sent_at`) | `rows.message[<id>].fields.occurred_at` |
| Acks | `rows.handover_ack["<message>|<member>"].fields` = `{message_id, member_id, acked_at}` |
| Ticks | `rows.checklist_item_state["<message>|<item>"].fields` = `{message_id, item_id, done, member_id}` |
| "From chats" in Notes | `rows.message[<id>].fields.forward_of_id` set (non-null) |

`PROJECTION_VERSION` is 9: clients rebuild their cached snapshot once. Server REST message reads
(`/channels/{id}/messages`) don't add these fields; read them from the local projection.

## 4. Core functions (wasm / UniFFI)

Two exports, each one JSON-in, JSON-out call. Pinned by `fixtures/handover/` and
`fixtures/text/hashtags.json` (hand-written; the cases are the spec).

### `handoverView(input_json)` (wasm) / `handover_view(input_json: String) -> String` (UniFFI)

Input:

```json
{
  "body": "<account id>",
  "intervals": [{"subject_id": "…", "layer": "front", "start_at": 0, "end_at": null, "front": "<front id>"}],
  "handovers": [{"id": "…", "sent_at": 0, "deliver": "next_switch", "authors": ["…"], "checklist": [{"id": "a", "text": "Keys"}]}],
  "acks": [{"message_id": "…", "member_id": "…", "acked_at": 0}],
  "checks": [{"message_id": "…", "item_id": "a", "done": true, "member_id": "…"}],
  "member": "<member id, for last_fronted>"
}
```

- `intervals`: **the Body front's folded history**, every interval (closed and open), so
  retracts and back-dated switches resolve the same way everywhere. Configured accounts (named
  fronts, D-080): `frontEffectiveHistory(account, account, ops)` / `front_effective_history`
  → its `intervals`, with `layer` = the interval's `layer_id`. Legacy accounts:
  `foldFront(ops)` / `fold_front` → `intervals` with `layer` = its `level` (`front`, `cocon`,
  `present`). Only `front` and `cocon` count; other layers are ignored, so passing them is fine.
  `front` and `body` are optional: when both are given, intervals of other fronts are dropped.
  Pass only intervals that end after the oldest handover's `sent_at` if the history is long.
- `handovers`: every message in the Handover channel with a `handover` field (skip deleted ones),
  `deliver` and `checklist` copied from it, `sent_at` = its `occurred_at`.
- `acks` / `checks`: the projection rows above (all of them, or those for these messages).

Output:

```json
{
  "handovers": [{"id": "…", "delivered_at": 0, "acks": [{"member_id": "…", "acked_at": 0}], "acked": true,
                 "checklist": [{"id": "a", "text": "Keys", "done": true, "by": "…"}]}],
  "unread": ["<message id>", "…"],
  "last_fronted": 0
}
```

- `delivered_at`: `now` → `sent_at`; `next_switch` → the first moment after `sent_at` at which
  the Body front's fronting and co-conscious members (who, and on which of those two layers)
  differ from those at `sent_at`; `null` = **still waiting** (show "waiting for next switch").
- `acked`: some member outside the handover's authors acknowledged it (one ack closes it for the
  whole system). An author's own ack doesn't.
- `unread`: delivered and not `acked`, in delivery order (then id).
- `checklist`: in written order, `done` and who last ticked it (`by`); ticks for unknown items are
  ignored.
- `last_fronted`: when `member` last **stopped** fronting (front or co-con; touching intervals are
  one stretch; a stretch still open doesn't count); `null` = never. The "Since Foxglove last
  fronted" divider goes before the first message with `occurred_at` after it.

### `noteTags(messages_json)` (wasm) / `note_tags(messages_json: String) -> String` (UniFFI)

Input `[{text, entities}]` (the Notes messages, in display order). Output
`{tags: [[tag, …] per message], chips: [{tag, count}]}`: a tag is `#` at the start or after a
non-letter/digit/`_`, then 1–64 letters, digits or `_` with at least one non-digit, outside
`code`, `pre` and `url` entities, ASCII-lowercased (D-091; "Été" stays "Été"), once per message,
without the `#`. `chips` are most used first, then by tag.

## 5. Notification

Kind `handover` (own account only), in pushes and `GET /notifications`:
`{t: "message", kind: "handover", title: "Handover from Amarynthe", text: <first line, or
"Checklist · N items">, message_id, channel_id, scope, chat, sender}`. Because `t` is `message`,
existing message handling already shows it and opens the channel on tap; do open the Handover
chat scrolled to `message_id`, and (Android) stack it under `chat:<channel_id>` like other chat
pings. It is sent once, when a live front op delivers a `next_switch` handover, never on the
device that switched (and for one already delivered when it arrived, never on the sending
device). The switch `notify_chat.handover` (default on) turns it off: add it next to the other
`notify_chat` kinds in Advanced (copy: "Handovers when the front changes"). `deliver: now`
handovers ring as ordinary `message` pings. Default channel levels: Handover `all`, Notes `none`
(the user can change them with the usual `notify_channel:<id>` pref).

## 6. The screens, element by element

**Handover (F5.15, F3.8)**

| Element | Data |
| --- | --- |
| Chat-list row: document icon, "notes between fronts" | `special == "handover"` |
| Header "Handover · notes between fronts · N members" | channel `name`; member count as for the internal space |
| Pinned bar ("Pinned · today's handover") | the existing pin bar (`message.pin`) |
| Ordinary messages (e.g. "Evening: dishes done…") | ordinary `message.send` in the channel |
| "Since Foxglove last fronted" divider | `last_fronted` for the viewing speaker |
| A handover bubble | `handover` field set; a "waiting for next switch" mark while `delivered_at` is null |
| Checklist rows on the bubble, tappable | `checklist` from `handoverView`; tap writes `checklist.set` as the speaker |
| Service line "Foxglove acknowledged the handover · 10:30" | each entry of `acks` (not authors'), rendered between messages at `acked_at`; not a message (D-003) |
| ✓✓ ticks on our messages | D-131 as everywhere |
| Composer chip **Acknowledge** | writes `handover.ack` (speaker) for the newest unread handover (`unread`'s last); disabled when `unread` is empty |
| Composer chip **Checklist** | opens the item editor (a list of rows in the composer); the items go into `handover.checklist` |
| Composer chip **At next switch** | toggles `deliver` between `now` (off) and `next_switch` (on) |
| Placeholder "Write a handover…" | — |
| Sending | `message.send` with `handover: {deliver, checklist?}`; a plain message without either chip may stay an ordinary message (no `handover` field) — *choice for the client*: we suggest every send from this composer is a handover (`deliver: now` when the chip is off), so Home lists it |

**Home card (F0.1)**: "Handover from Amarynthe" (authors' names), the first line (or
"Checklist · N items"), **Read**. Show the newest of `unread` (or a count when several). Read
writes `handover.ack` as the current primary fronter (the speaker rule); the card goes when
`unread` no longer has it. Tapping the card opens the Handover chat at it. This replaces the
interim pref `home.handover_read`: stop writing it, ignore it when reading.

**Notes to ourselves (F5.19)**

| Element | Data |
| --- | --- |
| Bookmark icon; "saved for every member" | `special == "notes"` |
| Chips **All · #tags · #from chats** | `noteTags(...).chips` (most used first); "#from chats" = `forward_of_id` set |
| Filtering by a chip | a message matches a tag when `tags[i]` contains it |
| "Forwarded from Amarynthe · General · Monday" | the forward snapshot, as in any chat (D-048) |
| Placeholder "Save a note…" | — |
| **Save to notes** | first destination in the forward sheet: an ordinary `message.forward` into the Notes channel. Don't copy `handover` from a forwarded handover into the snapshot |

**Chat-folder suggestion (D-113)**: "Handover and notes" (web `lib/chat-folders.ts:102`, Android
`data/ChatFolders.kt:97`) starts with our chats whose `special` is set, instead of the name
regex.

**Widget and lock-screen (D-122, D-137)**: the Handover action opens the Handover chat with the
composer focused and **At next switch** on. On the lock-screen front notification (D-137) add a
"Handover" action doing the same (it needs unlocking, A12).

## 7. Not built

- No server REST fields for `special`/`handover`/acks: clients read the projection.
- No "edit a checklist after sending" (A8) and no "un-acknowledge".
- `fixtures/schema/` (JSON Schemas) does not exist in the repo; the shapes are in DATA_MODEL §3
  and core `validate_op`.
- Clients don't hide a person's Notes space yet, don't render any of the above, and still use the
  name regex for the folder suggestion.

## 8. Web (done 2026-11-15, claude-sonnet-5.5)

What `web/` now does, and what Android should copy. Files: `lib/special.ts` (roles, person home, subtitles,
notification defaults, Notes chip rule), `lib/handover.ts` (builds `handoverView`'s input, Home card, ack and tick
payloads), `ui/HandoverBar.svelte`, `ui/HandoverList.svelte`, `ui/NoteChips.svelte`; tests `special.test.ts`,
`handover.test.ts` (models built from ops through `project`, helper `lib/testing/d132-ops.ts`) and
`e2e/handover.spec.ts`.

- **Person (D-093).** The internal space is found as an internal space whose `created_by_account_id` is ours (a
  guest's view of another system's internal space is not ours) and the account has a live `is_self` member. Hidden
  everywhere: no Internal folder (rail, tabs, folder editor), no space panel, no Members tab, no "Internal · only…"
  line, no "Viewing as", no visibility picker, no Stage "Viewing as". What remains is the Notes row (title = the
  channel's `name`, bookmark icon, no "saved for every member" line, since a person has one member). Notes sits
  under All chats (its folder kind is still `internal`).
- **Rows.** `special` gives the icon (`handover` document, `bookmark`); an empty special chat says its subtitle
  ("notes between fronts", "saved for every member") where the preview would say "No messages yet". Header:
  "Handover · notes between fronts · N members", "Notes to ourselves · saved for every member". Default levels
  `all` / `none` when no `notify_channel:<id>` pref exists (the Notes row shows no muted bell for its default).
  Delete reads "Move to Trash" (a tombstone); a deleted one is restored from Trash (`channel.restore`), never remade.
  Their edit sheet has no "who it's for"; Share, permissions and slow mode are hidden for them. **Renaming is
  allowed** (the contract and the server allow it).
- **Folder suggestion** goes by `special`, not by name. The first chat opened with no channel in the address is not a
  special one.
- **Handover chat.** Intervals for `handoverView` are `sync.frontHistory(account).effective.intervals` when
  the account has named fronts (`front_systems`), else `projection.fronts[account].intervals`; `layer` = `layer_id ?? level`.
  `member` is the speaker. The "Since X last fronted" divider is inserted at `last_fronted + 1` (before the first
  message strictly after it) and never at the end; ack lines ("Foxglove acknowledged the handover · 10:30") come from
  `acks` whose member is not an author, at `acked_at`, between messages (not messages). Every send from the Handover
  composer carries `handover` (`deliver: now` unless At next switch is on; `checklist` ids `c1`, `c2`…); a
  checklist-only send has empty text. **Acknowledge** writes `handover.ack` as the speaker for `unread.at(-1)`,
  disabled when `unread` is empty. A tick writes `checklist.set {done: !done}` as the speaker. "Waiting for next switch"
  while `delivered_at` is null.
- **Home.** The `handover` section is no longer hidden (`UNBUILT` is empty): the newest unread with its authors'
  names, first line or "Checklist · N items", "N more waiting" when several, **Read** = `handover.ack` as the reader
  (primary fronter, else first own member). Android's `HomeDashboardTest` still lists it as unbuilt: flip that
  case too. `home.handover_read` was never written by web.
- **Notes.** Tag chips (All · #tags · "#from chats") over core `noteTags` for every live message in the channel;
  the chip row shows only when there is a tag or a forward; "#from chats" = `forward_of_id` set.
  **Save to notes** is the first forward destination ("Save to notes", bookmark), an ordinary `message.forward`;
  `handover` is never copied (the snapshot has no such field).
- **Notifications.** The Notifications page lists a `handover` row (system chip) that opens the Handover chat at its
  message; a Web Push for kind `handover` opens `#/chat/<channel>/<message>`; Settings › Notifications has
  "Handovers when the front changes" (`notify_chat.handover`, systems only).
- **Not built on web:** the widget/lock-screen Handover actions (Android only).
- **Size.** The opening script was 295.0 KB gz before this work; the feature needed room, so NewChat, ChannelEditor and
  ChatFolders moved to `lazy-screens.ts`.
