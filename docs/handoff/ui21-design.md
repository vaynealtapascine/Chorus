> **Owner answers after this draft (2026-11-08, D-133):** "Member names on each message" is a display setting (which member wrote vs only the system), not a rename permission. "See fronting status" shares the system's current front with everyone in the space when on (new sharing path, own delay, off by default) — not only through follows. Where the draft below says otherwise, these win.

# UI21 wave 8: technical design draft (D-131 to D-138)

Draft by claude-opus-5.5, 2026-11-08. Nothing in the repo is changed. Read against `AGENTS.md`,
`docs/DECISIONS.md` (D-131 to D-138, plus D-047, D-052, D-053, D-081, D-086, D-087, D-101, D-119,
D-122, D-129), `DATA_MODEL.md`, `SYNC.md`, `NOTIFICATIONS.md` §5, `POLLS.md`, and the code cited
below. Figma: `F:/DunBuild/figma-cache/frames/` F5.11, F5.15, F5.17, F5.19, F5.23, F6.2, F6.9,
F6.10, F6.11, F3.8, F3.10, F0.1.

The latest migration is `0021_member_handles.sql`. Numbers are **reserved up front** so parallel
tracks don't collide: **0022** polls (D-136), **0023** handover/notes (D-132), **0024** space
permissions (D-133). Ticks, headings, member states, the system board and the lock-screen
notification need no migration.

Every new op kind goes through the same steps. Each feature's checklist assumes them:
`chorus-core/src/op.rs` `CATALOGUE` (table, scope, merge) + `FIELD_RULES` (the schema test
compares them with the SQL) + `validate_new` per-kind rules; `model.rs` reference rows;
`chorus-server/src/project.rs`; the `ingest_fuzz.rs` field list; `payload_fuzz.rs`; the
`converge.rs` op generator; `fixtures/schema/` regenerated; the D-119 checks in `ingest.rs`
(`foreign_scope`, `foreign_reference`); `exports::AUTHORED_OPS`; a bump of the client UI
projection cache version (as D-088 did, v4 → v5) when a client projects a new row type.

---

## 1. D-131 Message ticks, with opt-in friends' reads

### What the ticks mean

| Tick | Condition | Source |
| --- | --- | --- |
| clock | own message op not yet acked (no server stamp) | client outbox. Web already treats "no `account_id`" as pending (`web/src/lib/data.ts:824`); Android uses the same replica state |
| ✓ | stored on the server (has `seq` / account stamp) | local |
| ✓✓ | (a) a member of **our** account who is not an author of the message has a read mark at/after it, or (b) another account has, and **both** accounts have `share_reads: true` | (a) local `read_state` member rows; (b) the new receipts view below |

Ticks only appear on our own messages (our side of the chat, D-103).

### (a) Our own members: no new data

`read_state` rows with `reader_member_id <> ''` already sync to our own devices when "track
reading per member" (`chat.read_per_member`, `chorus_core::reading::readers`) is on. ✓✓ means
"some member mark ≥ (occurred_at, id) whose member isn't in the message's authors". **With
per-member tracking off there is only the account row, and our own account always reads its own
message, so own-system ✓✓ can't be known.** Default: then only (b) can make ✓✓ (see §9, A5).

### (b) Other accounts: a receipts view, not ops

Read marks stay account-private ops (SYNC §4.2, `visibility.rs:234` keeps refusing `read.*` to
other accounts). Cross-account reads are served as a **view computed on request and relayed live
over the socket**, like typing (D-087), never as ops:

- **Why not ops** (unlike poll tallies, D-086): who may see a receipt depends on *two* prefs that
  can change at any moment. An op in the space log would be either visible to everyone (wrong)
  or need per-account filtering that changes when a pref flips, which breaks per-(scope, account)
  digests (SYNC §6.4) and needs re-pulls. Losing receipts offline is fine: the message shows ✓
  until the next fetch.
- **Pref:** account-level `pref.set {key: "share_reads", value: true}` (`device: ""`), default
  absent = off. Label: "Show friends when we've read", under Advanced next to "Show when we're
  typing in shared chats" (DESIGN §6: when unsure, Advanced).
- **Only the account row** (`reader_member_id = ''`) ever crosses accounts. Member rows would say
  who was fronting (NOTIFICATIONS §5.8). This rule is in code and in a test.
- **Clamped to the viewer's own messages.** The server never returns the reader's raw mark (it
  could point at the reader's own aside, or at a message the viewer can't see because of the
  history rule in D-133). It returns `upto`: the **latest message by the viewer's account** at
  or before the reader's mark. The viewer learns nothing except "they've read up to my message
  X", which is exactly the ✓✓.
- **Eligibility, checked at every query and relay:** a channel in a `shared` or `dm` space
  (never `internal`, guests included, the same as typing), both accounts can view the channel
  now (`perms::can(.., "view")` + `ingest::can_access`), and `share_reads` is on for both.

**Endpoint** (`crates/chorus-server/src/api_reads.rs`, route in `app.rs` next to
`/channels/{id}/messages`):

```
GET /channels/{id}/reads  →  {"reads":[{"account_id":"…","message_id":"…","at":1790000000000}]}
```

One row for each other eligible account that has read past at least one of our messages. 404 if
the viewer can't view the channel. API tokens need `read:messages`. Cache headers: `no-store`.

**Live frame** (new `crates/chorus-server/src/app/reads.rs`, called from the same post-commit
fan-out as ops): after a `read.mark`/`read.set` is accepted in a shared/DM space, for each
connected device of another eligible account, recompute that account's `upto` for the reader.
Send `{"t":"read","channel":"…","account":"…","message_id":"…","at":…}` **only when it
changed**. A mark that moves over the reader's own messages, or over nobody's, sends nothing, so
less activity leaks. Nothing is persisted, sent over SSE, sent to webhooks or exported. A pref
flip needs no frame: the next query or relay checks the prefs again. Clients drop the receipts
they hold for a channel when it is re-fetched.

### Privacy reasoning (NOTIFICATIONS §5)

Receipts are not a front surface. They reveal that an account was active, never which member,
so they fall in the same category as §5.6 (chat side channel) and §5.7 (typing): an explicit,
reciprocal opt-in. Add **§5.9** to NOTIFICATIONS: "Read receipts (D-131): account level only,
reciprocal `share_reads`, shared/DM spaces only, clamped to the viewer's own messages, never
persisted, exported, sent over SSE or webhooks; they grant or bypass no follower-front access."
D-119: there are no new ops, so nothing new at ingest.

### Core (pure)

New `chorus-core/src/ticks.rs`:
`tick(own: &Msg{at,id,authors,acked}, member_marks: &[reading::Mark], foreign: &[Mark]) -> Tick {Pending, Saved, Read}`,
and `upto(reader_mark, own_msgs_sorted) -> Option<(at,id)>` (the server uses the same function
for the clamp). Exported through `chorus-wasm` and `chorus-ffi`. Keep it tiny for the wasm
budget (SPEC §9).

Fixtures: `fixtures/ticks/` with: pending/saved/read; a mark by an author doesn't count; a
non-author member mark counts; a foreign mark clamps to our latest message; a mark over only
foreign messages gives nothing; equal `occurred_at` broken by id.

### Clients

- **Android:** a tick glyph in the bubble meta row (`ui/Chat.kt` bubble footer; `ui/ChatRows.kt`
  / `ui/ChatChrome.kt` for the chat-list last message, F5.1/F5.23). Receipts in a small
  `data/ReadReceipts.kt`: fetched when a shared/DM chat opens, updated by the `read` frame in the
  sync socket handler (where `data/Typing.kt` is fed), kept in memory only. The pref goes in
  `data/AccountPrefs.kt` (next to `share_typing`, line 63) and `ui/SettingsScreen.kt`.
- **Web:** `ui/Message.svelte` meta, `ui/ChatList.svelte` row; a store `lib/read-receipts.ts`
  with its test; `ui/Settings.svelte` toggle.
- F5.17's "Not sent · tap to retry" is the existing rejected/held state. The clock covers only
  "queued, not yet acked".

**Tests:** `crates/chorus-server/tests/read_receipts.rs`: reciprocal gating (either side off
means nothing); internal space and guests never get receipts; member rows never leave; the clamp
(the reader's aside is never named); history-hidden messages (D-133) never named; a pref turned
off stops the next relay; no SSE/webhook/export path (grep-style test, as for typing). A
`privacy_audit.rs` case a19. Core unit tests and fixtures. **Size: M.**

---

## 2. D-134 Headings H1/H2

**Data:** a new entity type `{"type":"heading","level":1|2,"offset","length"}` in the existing
`entities` JSON. No migration: post `entities` is TEXT JSON.

**Core** (`chorus-core/src/text.rs`):
- `EntityKind::Heading { level: u8 }`. Markup: a line starting `# ` gives H1 and `## ` gives H2.
  The prefix is dropped from `text`, like `> ` for blockquote. `to_markup` writes it back. `\#`
  escapes. `###` and deeper stay literal text (two levels only).
- Normalization (`normalize_entities`): a heading covers exactly one whole line (from line start
  to before `\n`); it is never inside `pre` and never overlaps a blockquote; inline entities
  nest inside it. `validate_new`: level is 1 or 2, and the range is a whole line.
- **Where it applies.** `parse` takes a `blocks: Headings::Allow | Literal` option. Posts and
  journal entries (`post.create`/`post.edit`) allow headings. Chat messages keep `# ` literal,
  and `validate_new` refuses a `heading` entity on `message.send`/`message.edit`/`forward`
  (default; see §9, A9). Lists stay typed line prefixes (D-134).
- **Forward compatibility:** an Android build older than this one that receives a heading entity
  must not fail the whole entity list. Add a lenient read for display (unknown `type` → drop
  that entity) used by renderers and `to_markup`. Strict `validate_new` stays strict. Phones
  update on next start, but the lenient read stops this class of break for good.
- Search and plain-text export: the heading text is plain text already; nothing changes.
  PluralSpec: note the entity in `spec/` (its rich-text section) or map it to bold if PSDS has
  no heading. Record that in `spec/DESIGN_NOTES.md`.

**Fixtures:** `fixtures/text/headings.json`: H1/H2 parse and round-trip; `###` literal; an
escaped `\#`; inline bold inside a heading; a heading next to a quote; UTF-16 offsets with emoji;
the message context keeps it literal. **Server:** nothing beyond core validation (it runs in
`ingest::accept` through `op::validate_new`).

**Clients:**
- **Android:** render in `ui/RichBody.kt` (a `ParagraphStyle` plus a larger `SpanStyle`; the
  `when` at :71 gains `"heading"`); the editor in `data/RichTextEdit.kt` and
  `ui/RichEditorField.kt`; the toolbar in `ui/JournalFormatToolbar.kt` /
  `ui/EditorFormatting.kt` gets F6.11's *Paragraph styles* row: Body · Heading · Subheading ·
  Quote · Code (Heading = H1, Subheading = H2; Figma has one "Heading", and the owner asked for
  two).
- **Web:** `ui/RichText.svelte` (`<h3>`/`<h4>` inside the post body, so the page outline
  stays sane), the composer `ui/PostComposer.svelte` toolbar, `richtext.test.ts`.

**Tests:** core unit tests, fixtures, the `payload_fuzz` entity generator gains headings, and a
web render test. **Size: M** (core S, two editors M).

---

## 3. D-136 Poll closing time and Nudge

### Closing

**Definition:** the `poll` field gains optional `closes_at` (ms, UTC). Like options it is
**fixed at creation**: edits still refuse any `poll` key (POLLS.md). Older clients ignore it,
because `poll::definition` reads only known keys.

**Early close:** a new op `poll.close {}`, entity = the parent id, in the **parent's** scope
(`space:` for a message, the author's `account:` for a post), allowed only from the parent's
author account. Merge: LWW-F on a single `closed_at` field, where the first close wins (min).
It cannot be undone (default; see §9, A10). Figma F6.9's "Decided" label is a closed poll.

**Effective close** = `min(closes_at, occurred_at of the earliest poll.close)`. `occurred_at` is
server-corrected and the same on every replica, so projection is deterministic. Core:
`poll::closed_at(def, closes) -> Option<i64>`, `poll::is_open(def, closes, now)`.

**Server enforcement** (`crates/chorus-server/src/polls.rs`):
- `vote_denied`: refused (`forbidden`, "this poll has closed") when the server's `now` ≥ the
  effective close. That includes held `unknown_parent` votes that come back after it. The rule is
  the server's arrival time, as for slow mode (D-076).
- `publish`: returns `None` once closed, so no tally op is appended after the close. **The last
  tally before the close is final**, even if audiences change later. `after_op` also handles
  `poll.close`: it publishes one last recompute in the same transaction, then freezes.
- Ingest: `poll.close` from anyone except the parent's author account is `forbidden`; a close on
  a parent the server hasn't seen gets `unknown_parent` and a retry.

**Migration 0022_poll_close_nudge.sql:**

```sql
CREATE TABLE poll_close (poll_id TEXT PRIMARY KEY, closed_at INTEGER NOT NULL, hlc TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE poll_nudge (
  poll_id TEXT NOT NULL, account_id TEXT NOT NULL, member_id TEXT NOT NULL,
  nudged_at INTEGER NOT NULL, by_member_id TEXT, hlc TEXT NOT NULL,
  PRIMARY KEY (poll_id, account_id, member_id)) WITHOUT ROWID;
```

(`closes_at` lives in the existing `message.poll` / `post.poll` JSON.)

### Nudge

F6.10: the vote list shows our own members, with "Nudge" on each one who hasn't voted ("3 of 4
voted"). Voter identities are hidden across accounts (D-081), so **a nudge only reaches our own
members** (default; see §9, A3):

- Op `poll.nudge {member_ids: [..], by_member_id?}` in the **nudger's** `account:` scope
  (private, like `poll.vote`), entity = the parent id. Merge: LWW per (poll, member) on
  `nudged_at`.
- Allowed only on a poll whose eligible voters include our members (any parent our account can
  read), with each `member_id` an own, live, non-archived member.
- **Rate limit** (ingest, server clock): a member nudged on this poll less than **4 h** ago is
  refused (`later` with the wait, as slow mode does, so the client shows when the button comes
  back), and there is an account cap of **30 nudges/h** via `ratelimit.rs`. A closed poll can't
  be nudged. Members who have voted by the time the op is ingested are skipped silently.
- **Notification kind `poll_nudge`** (own account, `activity.rs::queue`): "Amarynthe nudged
  you: What shall we do Saturday?" delivered under the nudged member's **mentions** rule
  (`notify_member:<id>`, `always`/`fronting`/`never`, default `always`), skipping the writing
  device. Push `t: "poll"` with `post_id` or `message_id`, which opens the poll. Android uses the
  existing "Post alerts" channel for posts and the chat channel for chat polls. It is gated by
  `notify_chat.poll_nudge` (on unless false).
- Privacy: the op never leaves the account, the tally is unchanged, and no other account is
  pinged. NOTIFICATIONS §7 gains the row.

**Core:** extend `chorus-core/src/poll.rs`: `closes_at` in `Poll`, `POLL_RULE` text,
`check_nudge`, `closed_at`. Catalogue: `poll.close` (LWW-F; scope follows the parent),
`poll.nudge` (account). Fixtures in `fixtures/poll/`: `close_time.json` (closing time; early
close; min of the two; edits can't move it), `nudge.json` (shape, own members only, LWW). The
fixture README's adapters are extended.

**Clients:**
- **Android** (`data/Polls.kt`, `ui/PollCard.kt`): "closes Fri 18:00" / "Closed" line; buttons
  disabled once closed (local clock, display only); a closing-time row in `PollDraftFields`
  (Advanced, like the poll itself); the F6.10 poll page with the vote list (own members from
  local `poll_vote` rows, "Hasn't voted yet", Nudge chip with a cooldown from `poll_nudge`);
  Close poll in the author's ⋯ menu.
- **Web:** `lib/polls.ts`, `ui/PollCard.svelte`, `ui/RemotePostPoll.svelte` (closed state only),
  `polls.test.ts`.

**Tests:** `tests/polls.rs`: vote after close refused; held vote that lands after close refused;
tally frozen after close (audience change, delete/restore); only the author closes; nudge rate
limit and `later` wait; nudge of another account's member refused; nudge of a member who has
voted is skipped; `poll_nudge` respects `never`; no nudge or close data in another account's
scope or view. Update `POLLS.md` (§Data, §Slices). **Size: M.**

---

## 4. D-135 System board

**This is a client view over existing data. No server or core change.** F6.9 shows posts (with
handles, replies, reposts, likes, saves) whose audience is "Only our system", so the board is:

- **Posts tab:** our account's posts with `visibility.mode = private`, top level (not replies or
  reposts), both `note` and `entry` kinds, newest first. These are all in our own `account:`
  scope, so every device already has them offline.
- **Polls tab:** the same set with `poll IS NOT NULL`; closed ones carry the "Decided" label
  (D-136). No Pinned tab (D-135), even though F6.9 shows one.
- **"+"** opens the post composer with the audience preset to private and the kind set to note.
- Entry point: Journal's top bar or menu ("System board"). On web, route `#/journal/board`.
- Drop F6.9's explanatory banner ("Each member votes as themselves…"): owner's no-explanation
  rule.

The only shared logic is a predicate. Put `is_board_post(post)` in the client presentation
modules (Android `data/JournalPresentation.kt`, web `lib/journal-presentation.ts`) with a unit
test each. It isn't core-worthy: no sync or merge meaning.

**Clients:** Android `ui/SystemBoard.kt` (reuses `ui/Journal.kt` post cards and
`ui/PollCard.kt`), nav entry in `ui/AppNavigation.kt`; web `ui/SystemBoard.svelte` + router.
**Tests:** predicate tests on both clients. **Size: S** (M if F6.10's poll page is counted
here; I put it under D-136).

---

## 5. D-138 Member states with "Who sees this"

**Today (D-122):** account pref `member_status:<member id>` = `{text, at}`, private to our
devices. The F3.10 sheet edits it: chips (Tired, Nonverbal, Busy, Low spoons, Needs quiet, Out
and about, custom), a note, "Clear when Foxglove stops fronting", and "Who sees this".

**Storage: keep the pref and widen its value** (no migration; prefs are account-scoped,
LWW-F, and already stored on the server):

```jsonc
"member_status:<member>": {
  "tags": ["tired", "nonverbal", "state:<custom_state id>"],  // built-in keys or custom states
  "note": "Typing only today, please",                        // optional, ≤ 200 chars
  "at": 1790000000000,                                        // when set (own devices only)
  "clear_on_leave": true,                                     // F3.10 toggle, default true
  "audience": {"mode": "private"}                             // visibility JSON (§4.1); default private
}
```

A legacy `{text, at}` reads as `{note: text, tags: [], clear_on_leave: false, audience: private}`.

**Core:** a new `chorus-core/src/member_state.rs`:
- `parse(value) -> State` (fail closed: a malformed audience is private);
- `shown_own(state, presence_intervals, now)`: our own view. It is cleared when
  `clear_on_leave` is set and the member left the front after `at`. This is display only, so no
  op is written at switch time and there is no multi-device race;
- `shared_part(state) -> {tags, note}` (never `at`).

`validate_new` for `pref.set` with this key prefix checks the shape. Fixture:
`fixtures/member_state/` (legacy upcast, clear on leave, bad audience means private, tags
limit).

**Where friends see it: only through the follower front view, at reveal time.** F3.10 says
"shown next to their name while fronting", so a state is front-adjacent data. If a friend saw it
the moment it was set, they could learn that someone just switched in before the delayed reveal.
So the state rides on the reveal pipeline (NOTIFICATIONS §5.1–5.3):

- `notifier.rs`: `Shown` (line 30) gains `state: Option<{tags, note}>`. When a change is queued
  (`queue_for_front`, where names are snapshotted), each shown member's state is included **if
  that follower is in the state's audience**. The check uses the same rule as profile parts:
  `profiles::Viewer` / `posts::audience_sql` modes `private`, `friends`, `followers`,
  `following`, `buckets`, `server`. Anonymised "Someone" entries carry nothing.
- A **state change** (`pref.set` of a `member_status:` key) queues a **silent** reveal
  (`notify: false`) of the current front through `on_front_change`. A new
  `notifier::changes_state(o)` sits beside `changes_policy` in `ingest.rs:270`. The follower
  sees the new state at that reveal's `due_at`, with no ping. If the member isn't in the
  follower's revealed front, nothing is shown.
- A state-only reveal updates `follower_front_view.entries` but **does not append to
  `follower_front_log`** when subjects and levels are unchanged, so history and stats don't
  split.
- Surfaces that read the view pick it up with no change of their own: `GET /accounts/{id}/view`,
  `GET /follows/{id}/view` (F7.9 "What they see right now"), shared feeds' `fronting:`
  (states ignored). `GET /accounts/{id}/members[/{m}]` stays free of fronting data (D-101): no
  state there.
- Not shown: the speaking layer in a DM with another account (F7.6, D-138). Q24: a friend's page
  says "N members visible" and counts only what is shared. That is already the rule in
  `profiles::members`; check that the clients don't add a hidden count.
- D-119: `foreign_reference` refuses a `member_status:<id>` pref naming another account's member.

**Tests:** extend `notifier.rs`'s `follower_surfaces_only_change_at_reveal_times` `surfaces`
closure with the state (a state set between reveals must not show early); audience narrowing
(a bucket follower vs not); clear on leave in the friend view; a legacy value; no `at` in any
follower payload. `privacy_audit.rs` a20. **Size: M.**

**Clients:** Android `data/FrontingDisplay.kt` (`STATUS`), the F3.10 sheet (the member sheet
of D-122), `ui/AudienceChip.kt` for "Who sees this", and friends' cards (`data/Friends.kt`
`nowLine`, `data/FriendFronts.kt`, `ui/FriendPage.kt`, the Home "Friends right now" strip).
Web: `lib/fronting-display.ts`, `ui/PlaceSheet.svelte`, `ui/AudienceChip.svelte`,
`lib/friend-fronts.svelte.ts`, `ui/FriendPage.svelte`. Update the D-122 text "private to the
account's devices" through a new D entry.

---

## 6. D-137 Lock-screen front notification (Android)

**Client-only, confirmed:** it reads the local projection, makes no server call, adds no op, and
touches neither web nor core.

- **Pref:** per device (it is about this phone). Device-scoped `pref.set {device: <id>, key:
  "lock_front"}`, which keeps it in exports and lets it survive reinstall-restore, or local
  DataStore. Default **off**. Settings › Notifications › This phone: "Show who's fronting on the
  lock screen" (Basic: a single opt-in the owner asked for).
- **Notification:** its own channel `front_ongoing` (IMPORTANCE_LOW, no sound or vibration,
  hidden from badges), `setOngoing(true)`, `setOnlyAlertOnce(true)`,
  `VISIBILITY_PUBLIC` (shown on the lock screen, which is the point of the opt-in), category
  `CATEGORY_STATUS`. The content follows F3.8's "Lock screen glance": STEERING Foxglove,
  Amarynthe · CO-CONSCIOUS Nairon · NEARBY Violet. Layer names come from the system's layers
  (`FrontScene.build`, the same as the widget). Actions: **Switch**, which opens the switcher
  sheet (an Activity `PendingIntent`; Android asks to unlock first, which is correct), and
  **Handover** once D-132 ships (the same as the widget action, D-122). Tapping opens Home.
- **Updates:** post or refresh from the same place the widget updates after a local or synced
  front change (`data/Front.kt` / the widget updater, and `data/SyncWork.kt` after background
  sync). Cancel it when the pref goes off or the account signs out. It needs `POST_NOTIFICATIONS`
  (already requested for chat). On Android 14+, non-foreground-service ongoing notifications can
  be swiped away: accept that and re-post at the next front change. A foreground service for
  this alone isn't worth it, and Play policy would also question it.
- Person accounts never see the option.
- New `data/LockFront.kt` (builder + updater), with a unit test of the content line from a
  `FrontScene`. A manual device check per the standing rules, using the emulator, not the
  owner's account. **Size: S.**

---

## 7. D-132 Handover and Notes to ourselves

Both are **channels with a role**, built end to end: their own column, ops, core rules,
notification kind, and Home card. They are chat surfaces in Figma (F5.15/F5.19: replies, quotes,
attachments, voice, search, pins), so rebuilding chat for them would waste the existing message,
search, trash and export paths. What the owner rejected ("not a channel flag") was the audit's
pref-only proposal, with checklists deferred and "at next switch" dropped. This design builds
both. See §9, A6.

### Data model (migration 0023_handover_notes.sql)

```sql
ALTER TABLE channel ADD COLUMN special TEXT CHECK (special IS NULL OR special IN ('handover','notes'));
ALTER TABLE message ADD COLUMN handover TEXT CHECK (handover IS NULL OR json_valid(handover));
CREATE TABLE handover_ack (            -- who took the handover, LWW per (message, member)
  message_id TEXT NOT NULL, member_id TEXT NOT NULL, acked_at INTEGER NOT NULL, hlc TEXT NOT NULL,
  PRIMARY KEY (message_id, member_id)) WITHOUT ROWID;
CREATE TABLE checklist_item_state (    -- ticks on a handover's checklist, LWW per (message, item)
  message_id TEXT NOT NULL, item_id TEXT NOT NULL, done INTEGER NOT NULL, member_id TEXT,
  hlc TEXT NOT NULL, PRIMARY KEY (message_id, item_id)) WITHOUT ROWID;
CREATE INDEX channel_special ON channel(space_id, special) WHERE special IS NOT NULL;
```

**Ops:**

| Kind | Payload | Scope / merge |
| --- | --- | --- |
| `channel.create` (extended) | `special: "handover" \| "notes"` | create-only structural field, like `kind` (D-123 list); internal space only |
| `message.send` (extended) | `handover?: {deliver: "now" \| "next_switch", checklist?: [{id, text}]}` | APP. It is fixed at creation like `poll` (edits refuse it). 1–30 items, id ≤ 16, text 1–200, reusing the poll limits. Only in a `handover` channel |
| `handover.ack` | `{message_id, member_id}` | `space:` (internal), entity = message; LWW per (message, member) |
| `checklist.set` | `{message_id, item_id, done, member_id}` | `space:` (internal), entity = message; LWW per (message, item) |

Tombstones: the channel and its messages use the existing delete/restore and Trash (D-053).
Acks and checks of a deleted message stay in the log and hide with it. Purge (`purge.rs`) also
finds them through `op_message_ref` (they carry `message_id`). Exports: authored ops, so they
are included. PluralSpec: handover/notes export as ordinary channels and messages; acks and
checks are left out (note it in `spec/DESIGN_NOTES.md`).

**Creation:** clients can't make internal spaces, and two offline devices would otherwise make
two Handover channels. So **the server creates them**, as ordinary server ops:
- at enrolment (`auth.rs:212`): `handover` and `notes` for a system, `notes` for a person;
- once for existing accounts, at startup, the way D-126 backfilled handles.

A **person** has no internal space today (`auth.rs:212` makes one only for systems). The server
makes one holding only Notes. Clients hide a person's internal space everywhere except as the
Notes row (D-093: singlets never see system chrome). Deleting the channel is a tombstone;
clients offer Restore, never a re-create (ingest refuses a second live channel with the same
`special` in a space).

**Server enforcement** (`perms.rs::write_denied`, `ingest.rs`):
- `special` only on `channel.create` in an `internal` space, never changed by `channel.set`.
- **Never shared out:** `channel.set_permission` on a special channel is refused, so no guest
  ever gets it.
- `handover` on `message.send` only in a `handover` channel; `handover.ack` and `checklist.set`
  only on messages there, with an own live member (`foreign_speaker` covers `member_id`), and an
  item id that exists in the message's checklist.

### Core (pure): `chorus-core/src/handover.rs`

- `delivered_at(sent_at, deliver, intervals) -> Option<i64>`: `now` → `sent_at`. `next_switch`
  → the start of the first Body front state after `sent_at` whose set of fronting and
  co-conscious members differs from the state at `sent_at`. It is computed from the folded
  history (`front_history` / `front_effective` intervals, so retracts and back-dated switches
  resolve the same way everywhere). `None` = still waiting.
- `unread(handovers, acks) -> Vec<id>`: delivered, and acked by no member outside its authors.
  One ack closes it for the system, as in F5.15: "Foxglove acknowledged the handover".
- `last_fronted(member, intervals) -> Option<i64>` for the "Since Foxglove last fronted"
  divider.
- `checklist(def, states) -> [{id, text, done, by}]`.
- `text::hashtags(text, entities) -> Vec<String>` for Notes' tag chips: `#word` outside
  code/pre/url, folded to ASCII lowercase as in D-091 (no locale tables).

Fixtures: `fixtures/handover/` with `delivery.json` (now; next switch; a co-con-only change
counts; a retracted switch doesn't; a back-dated switch before `sent_at` doesn't; a named front
other than Body doesn't), `unread.json` (an author's own ack doesn't close it), and
`checklist_lww.json`. Also `fixtures/text/hashtags.json`. Convergence: add the new kinds to
`converge.rs`; `MemServer` needs nothing new.

### Notifications

- **`handover` kind** (own account): when a live front op (`ingest.rs:266`, beside
  `on_front_op`) makes `delivered_at` become `Some` for a `next_switch` handover, queue one
  notification: "Handover from Amarynthe", first line or checklist count, `message_id`. It is
  deduplicated by `source_op_id = message id`, skips the device that switched (which shows the
  Home card anyway), and is sent only from live ingest, never from a rebuild or restore (the
  same as §7 activity). It is gated by `notify_chat.handover` (on unless false).
- `deliver: "now"` handovers notify like channel messages. The `handover` channel's default
  level is `all`; `notes` is `none` (`activity::channel_level`).
- Privacy: everything here stays within the account (the internal space never crosses, and
  guests are refused). Nothing is a follower surface.

### Clients

**Handover** (F5.15, F0.1, F3.8):
- Chat-list row with the document icon and the subtitle "notes between fronts".
- Composer chips **Acknowledge · Checklist · At next switch**. The placeholder is "Write a
  handover…".
- The checklist editor is a list of item rows in the composer.
- Checklist rows render as tappable boxes on the bubble and write `checklist.set` as the
  speaker.
- The service line "Foxglove acknowledged the handover · 10:30" is rendered from `handover_ack`
  (not a message, so D-003 holds).
- The "Since X last fronted" divider. Pinning uses the existing pin bar.
- A "waiting for next switch" mark on undelivered ones.
- **Home card** (F0.1): the unread list with **Read**, which writes `handover.ack` as the
  current primary fronter (the speaker rule). It replaces the audit's interim pref
  `home.handover_read`.
- The widget and lock-screen Handover action opens the composer (D-122, D-137).

**Notes to ourselves** (F5.19):
- Bookmark icon; subtitle "saved for every member"; placeholder "Save a note…".
- Tag chips **All · #tags (most used first) · #from chats**, where "#from chats" means
  `forward_of_id` is set.
- **Save to notes** is the first destination in the forward sheet (an ordinary
  `message.forward` with its snapshot, D-048).
- Android: `ui/Chat.kt`, `ui/ChatChrome.kt`, `ui/ChatRows.kt`, `data/ChatForward.kt`,
  `ui/HomeScreen.kt` / `data/HomeDashboard.kt`, `data/ChatFolders.kt` (D-113's suggested
  "Handover and notes" folder becomes `special`-based instead of name-regex, `:97`).
- Web: `ui/Chat.svelte`, `ui/ChatList.svelte`, `ui/Message.svelte`, `ui/Home.svelte`,
  `lib/chat-folders.ts`.

**Tests:** server `tests/handover.rs`: specials only in internal; never shareable; one live
per space; the person's notes-only internal space; delivery notification exactly once, and not
after a rebuild; ack/check validation. `privacy_audit.rs` a21: another account can't
ack or check ours, and a guest grant on the handover channel is refused. Core fixtures. The
chaos test gets one handover and one ack. Write the design into `DATA_MODEL.md` (D-132 says
"design in DATA_MODEL when built"). **Size: L** (server/core M, two clients L).

---

## 8. D-133 Shared-space permissions, the F5.11 set

### The permissions

| Key | F5.11 row | Gates | `member` base | `read_only` base | Level |
| --- | --- | --- | --- | --- | --- |
| `attach` | Send photos and files | `message.send`/`forward` with non-empty `attachments`; `attachment.create` in a `space:` scope | yes | no | channel or space |
| `poll` | Create polls | `message.send` with `poll`; `poll.close` stays the author's | yes | no | channel or space |
| `invite` | Invite other systems | `POST /spaces/{id}/members` and `space.join` for another account by a non-admin | no | no | space |
| `rename` | (see §9, A1) | `channel.set` touching only `name`/`topic`/`icon`/`color`/`avatar_blob`; `space.set` name/icon/colour/description | no | no | channel or space |
| `fronts` | Our fronting status | the new space fronting surface below | no | no | space |
| `history` | History before they joined | messages received before the account's latest join | yes | yes | space |
| `aside` | Allow system asides | `message.send` with `visibility.mode` `system_only` or `members` in a non-internal space | yes | yes | channel or space |

The defaults keep today's behaviour wherever a permission is new: everyone can attach, poll,
aside and read history today. `invite`, `rename` and `fronts` start off, as in Figma. Owner,
admins and DM participants keep everything (rule step 2), so nothing an existing space does
narrows (the D-129 principle). The client's *new space* flow may write F5.11's defaults
explicitly (history "From when they join"). That is a UI choice to confirm against the frame.

### Space-level overrides (migration 0024_space_permissions.sql)

F5.11 is one dialog for the whole space; today's overrides are per channel. Add:

```sql
CREATE TABLE space_permission (
  space_id TEXT NOT NULL, target_type TEXT NOT NULL CHECK (target_type IN ('role','account')),
  target_id TEXT NOT NULL, allow TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(allow)),
  deny TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(deny)), hlc TEXT NOT NULL,
  PRIMARY KEY (space_id, target_type, target_id)) WITHOUT ROWID;
ALTER TABLE space_member ADD COLUMN joined_at INTEGER;  -- server received_at of the latest join op
```

- Op `space.set_permission {target_type, target_id, allow, deny}`, `space:` scope, LWW-F per
  (space, target). Admin-only (the `space.*` rule). The same validation as
  `channel.set_permission`.
- `joined_at` is projected from the `space.join` op's `received_at`, which is in the log, so a
  rebuild reproduces it. Backfill from the log in the migration step (`project.rs` rebuild of
  `space_member`).
- **Resolution** (DATA_MODEL §4.4, `perms.rs::one_sql`), most specific first: owner/admin/DM →
  channel account override → **space account override** → (not a member → deny) → channel role
  override → **space role override** → channel `everyone` → **space `everyone`** → role base set.
  At one level deny beats allow. Space-only keys (`invite`, `fronts`, `history`) read only the
  space levels, through a new `can_space(account, space, perm)` / `can_space_sql`.

### Core mirror (`chorus-core/src/perms.rs`, new)

`PERMISSIONS` moves here from `op.rs:791` (and `perms::PERMS` on the server imports it), plus
`SPACE_ONLY`, `base(role, custom_roles) -> set`, and `resolve(levels) -> bool`, the pure
version of the rule. Clients use it to hide composer controls (attach, poll, aside) and
menu items. The server stays the authority: its SQL rule is property-tested against core's
`resolve`. The independent model in `tests/permissions.rs` becomes core's function, so "mirrored
in core" is literal. Add `fixtures/perms/` (`resolve.json`: tables of levels → expected
per-perm results, including the new space level and space-only keys), run by Rust. The web and
Android call core through wasm/ffi, so they need no runner of their own.

### Enforcement points (all server)

- `perms.rs::write_denied`:
  - `message.send`/`forward`: `send`, plus `attach` if it has attachments, `poll` if it has a
    poll, and `aside` if its visibility isn't public.
  - `attachment.*` in a space needs `attach` on the channel of the message linking it. A
    pre-link create passes, and the send is checked.
  - `channel.set`: `manage`, or `rename` when every field is in the rename set.
  - `space.set`: an admin, or `rename` for the name fields only.
  - `space.join` of another account: an admin, or `invite` (plus `spaces::check_invitees`: the
    inviter must be connected to them). `space.set_role`/`set_roles` stay admin-only.
- `spaces.rs::add` (line 187, "only the space's owner can add people") → owner, admin, or
  `can_space(invite)`.
- **History** in `visibility.rs::visible_message_sql` (the one rule spliced into sync fan-out,
  `visible_digest`, REST message reads, search `search_view.rs`, blobs, author cards, poll
  `can_read`, notification recipients): add
  `(m.account_id = acct OR can_space(acct, space, 'history') OR m.received_at >= sm.joined_at)`.
  Reactions, edits, pins, threads, attachments and tallies of a hidden message already follow
  `message_accessible`. Guests (no `space_member`) are unaffected (default). A rejoin counts from
  the latest join, so it fails closed.
- `caught` re-pull triggers (SYNC §4.2): add `space.set_permission`, and `space.join` already
  triggers, so the joiner's digest is right.
- **`fronts`:** a new `GET /spaces/{id}/fronts` returns, for each other account in the space,
  **the viewer's own follower view of it** (`notifier::follower_view_at`) only when that account
  has `fronts` allowed for the viewer in this space. Otherwise it returns nothing. This only
  narrows: it never reveals anything a follow doesn't already reveal (NOTIFICATIONS §5), and a
  non-follower sees nothing. Clients show space member lists and headers in that space from this
  endpoint instead of from the account-wide view. See §9, A2.

### Clients

- **Web:** F5.11 is a desktop modal: `ui/ChannelPermissions.svelte` becomes the space dialog
  (toggles for *everyone*, per-account exceptions behind a row), with `ui/SpaceRoles.svelte`
  for custom role perms and `ui/SpacePanel.svelte` for the entry point. Composer gating in
  `lib/chat-composer.ts`.
- **Android:** `ui/ChatPermissions.kt` and `data/ChannelPermissions.kt` get the restyle the audit
  describes (`FormCard` + `ChorusSwitch`, explanation paragraphs removed). Gating in
  `ui/ChatAttach.kt`, `ui/PollCard.kt` draft and `data/ChatVisibility.kt`.

**Tests:**
- `tests/permissions.rs`: the property test of the SQL rule against core `resolve`, with space
  levels.
- `privacy_audit.rs` a22–a24: history-before-join is unreadable through REST, search, blobs,
  catch-up, digest, reactions, threads and poll tallies; a member without `invite` can't add
  through REST or a forged `space.join`; `fronts` never returns more than `follower_view_at`.
- `chaos.rs` gets a history-denied joiner.
- `perf.rs`: the visibility rule stays within the ingest budget (≥ 2 000 ops/s), because one
  `can_space` subquery joins every visibility check. Cache the space-level row per batch in
  `visible_digest` if needed.
- Update DATA_MODEL §4.4 and SPEC §5.1.

**Size: L.**

---

## 9. Ambiguities and the defaults taken

| # | Where | Ambiguity | Default |
| --- | --- | --- | --- |
| **A1** | D-133 | D-133 says "changing names", but F5.11's row is **"Member names on each message: Shows 'Juniper · The Junipers'"** under *What they can see*, which reads as a display or privacy setting (show only the system name on our messages), not a permission | `rename` = renaming channels and the space. Build nothing for "member names on each message" until answered. **Needs the owner.** |
| **A2** | D-133 | "Seeing fronting status": nothing in a shared space shows another account's front today. Should the space *grant* fronting to non-followers (that needs a new ceiling and reveal pipeline), or only *gate* what follows already reveal? | It only gates (`fronts`, follower view only, default off). Safe under NOTIFICATIONS §5. **Needs the owner** if a grant is wanted. |
| A3 | D-136 | Nudge across accounts? The server knows who hasn't voted, but D-081 hides voters and a post's audience can be all followers | Own system's members only (matches F6.10) |
| A4 | D-131 | Group chats: is ✓✓ "anyone else read" or "everyone read"? | Anyone (one eligible account, or one non-author member) |
| A5 | D-131 | Own-system ✓✓ with per-member reading off can't be known | ✓ only, unless a foreign receipt applies; ticks may hint "turn on per-member reading" in Advanced (no copy in Basic) |
| A6 | D-132 | "Not a channel flag" read as "not the pref-only shortcut": a channel *role column* with its own ops and core rules | As designed in §7 |
| A7 | D-133 | Existing spaces and history | Allowed as today; only new spaces get "From when they join", if the frame's value is meant as the default |
| A8 | D-132 | Can a checklist be edited after sending? | Fixed at send, like a poll; send another handover to change it |
| A9 | D-134 | Headings in chat messages? | No: posts and journal entries only; `# ` stays literal in chat |
| A10 | D-136 | Close early, reopen? | The author can close early (`poll.close`); no reopen |
| A11 | D-138 | Default audience: Figma says "Same as Foxglove's fronting status", the owner says only us | Only us (the owner wins); a "same as fronting" mode isn't built |
| A12 | D-137 | Switch on the lock screen without unlocking | Unlock is required (an Activity intent); no direct-switch broadcast from the lock screen |

---

## 10. Build order

Dependencies first, smallest risk first. Each item is one or more atomic commits with
`verify.py --changed`, then a full `verify.py --android` before each deploy.

1. **D-137 lock-screen front notification (S):** Android only. It touches no shared code, so
   it is the warm-up that fits between other tracks.
2. **D-134 headings (M):** core text and both editors. It is self-contained, and D-135's
   composer benefits from it.
3. **D-136 poll close + Nudge (M):** migration 0022, `polls.rs`, `poll.rs`, poll cards, and the
   first new notification kind (`poll_nudge`).
4. **D-135 system board (S):** client-only, on top of D-136's closed state and the F6.10 poll
   page.
5. **D-131 ticks (M):** core `ticks.rs`, a read-only endpoint and a socket frame. It is low risk
   because no ops are added.
6. **D-138 member state audience (M):** `notifier.rs`, the most privacy-sensitive surface, so it
   goes after the simpler ones, with the reveal-time test extended first.
7. **D-133 permissions (L):** migration 0024 and the visibility rule every path uses. It is the
   riskiest change to shared code, so it goes after ticks (D-131 calls `perms::can`, and the
   history clamp then gets added to receipts).
8. **D-132 handover + notes (L):** migration 0023, a new core module, the server backfill, two
   chat surfaces and the Home card. It needs Home (wave 2), and benefits from ticks (✓✓ in F5.15)
   and from D-133's refusal to share special channels (the same `perms.rs` function). It can
   start in parallel after step 5 if a second agent takes it.

**In parallel** (the code is disjoint; the migration numbers above are reserved):
- **Track A** (text + journal UI): D-134, then D-135.
- **Track B** (Android notification only): D-137.
- **Track C** (`polls.rs`, `poll.rs`, PollCard): D-136.
- **Track D** (`notifier.rs`, fronting display): D-138.
- **Track E** (`perms.rs`, `visibility.rs`, `spaces.rs`, permission UIs): D-133.
- **Track F** (`app/reads.rs`, `api_reads.rs`, `ticks.rs`, bubble meta): D-131.
- D-132 touches `ingest.rs` hooks (shared with C and D), `perms.rs` (shared with E) and the
  chat UI (shared with F), so run it last or alone.
- `ingest.rs` and `op.rs` `CATALOGUE` are edited by C, D, E and D-132: those are small additive
  hunks, but merge them sequentially.

Docs to update as each one lands: `DATA_MODEL.md` (catalogue, tables, §4.4), `SYNC.md` §4.2
(receipts, history) and §9.1 (new fixture areas `ticks`, `handover`, `perms`, `member_state`),
`API.md` (`/channels/{id}/reads`, `/spaces/{id}/fronts`, the `read` frame, the widened invite
rule), `NOTIFICATIONS.md` (§5.9 receipts, state on reveals, §7 rows `poll_nudge` and
`handover`), `POLLS.md`, `CLIENTS.md` (lock-screen notification), and `DECISIONS.md` (a D entry
for each implementation choice above, and §Versions if anything gets pinned).
