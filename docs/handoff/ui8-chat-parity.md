# UI8 — Chat to Telegram polish, web/Android parity, spaces and private chats

Owner request, 2026-11-01 (claude-opus-5.5): *"Get the chat experience to Telegram-levels of polish
and make sure Android and web have feature-parity. Make it possible to make and edit spaces and
member lists on both. Let a system member have private DMs with somebody without letting other
members in the same system see it or talk there. Polish the experience so I end up preferring it
over Telegram."*

Read with: `docs/SPEC.md` §5, `docs/DATA_MODEL.md` §4.4, `docs/NOTIFICATIONS.md` §7, D-097,
D-098 (private chats), D-099 (spaces and member lists). Work happens on `main` in the main
checkout (the UI7 worktree branch is behind main). Gate: `CHORUS_TREE=<this checkout>` with
`pwsh -File F:/DunBuild/chorus-verify.ps1 [--android]`.

"Member lists" is read as **who is in a chat**: the accounts in a shared space (add, remove,
roles), the members of a members' DM or group, and the members a members-only channel is kept to.

## Slices

| # | Slice | State |
| --- | --- | --- |
| 1 | Server: private chats (D-098) — `member_ids` on a DM space's channel must name members of the space's accounts; an account with members listed speaks there only as them (send, forward, edit segments, reactions); author cards include listed members; notifications follow each listed member's DM rule (default: only while they front) | done (`perms::private_chat`, `tests/private_chats.rs`) |
| 2 | Web: spaces and member lists (D-099) — chat info editor: name/description of a shared space, people with Add / Remove / role, Leave / Delete group; channel editor (name, topic, icon, colour, members it is kept to); New chat menu: channel, members' DM or group (picker), shared space, DM with a friend | done (`NewChat`, `ChannelEditor`, `SpacePanel`, `chat-manage.ts`); checked on an isolated seeded demo (F:/DunBuild/ui8-demo, port 5283/5284) |
| 3 | Web: private chats — "Private chat as …" from a DM, lock badge and privacy line, composer locked to the member, list rows titled for each side | done (`chat-visible.ts` filters list, pickers, search, Trash, Stage; locked chats show a lock screen) |
| 4 | Android: spaces and member lists — people in a space with Add / Remove / role, rename/describe, Leave / Delete; members-only channels and editing a members' DM's people in the channel editor | implemented; gate passed; independent review pending |
| 5 | Android: private chats, same rules as web | done; reviewed by an independent Sol 6.1 pass, its findings fixed (server re-create bypass, locked retained actions, thread inheritance) |
| 6 | Android feel (UI7 slice 1, parked on `wip/ui7-slice1`): swipe to reply, double-tap heart, haptics, Draft:/typing previews in the list | implemented; Android compile/lint/JVM and quick offline Android gate passed; independent review pending |
| 7 | Parity gaps (table below) | partly: web gained jump to latest, unread line, Up to edit, emoji picker, Files/Links; Android gained Advanced permissions and slow mode, composer emoji. Still missing on Android: quote (message or selection), reply in another channel, multi-select bundles, front-change lines |
| 8 | Mentions autocomplete on both (members, groups, `@front`) | web done (`chat-composer.ts`); Android still `@front` only |

## Parity matrix (2026-11-01 survey)

| Feature | Web | Android |
| --- | --- | --- |
| Reply / reply privately / threads / pins / reactions / edit+history / delete+undo | yes | yes |
| Reply in another channel | yes (select) | **no** |
| Quote message or selection; quote/forward a multi-message bundle | yes | **no** |
| Forward | select box | sheet |
| Emoji in composer | custom only, no Unicode picker | **icon does nothing** |
| Mentions autocomplete | **no** | `@front` only |
| Channel create | name only | editor (name, topic, image, colour) |
| Channel edit | **no** | yes |
| Members' DM / group create | Reply privately only | picker |
| Shared space create | People page | New chat sheet |
| Add / remove people, rename space, delete group | **no** | **no** |
| Roles, channel permissions, slow mode | yes | **no** (Advanced) |
| Jump to latest with unread count | **no** | yes |
| Unread divider | **no** | per-member only |
| Chat info: members by layer, media | yes | yes, plus Files and Links tabs |
| Front-change lines in the timeline | yes | **no** |
| Older history from the server (windowed tab) | yes | n/a (keeps everything) |
| Swipe to reply, double-tap react, haptics | n/a | implemented (slice 6), review pending |
| Edit last message with ↑ | **no** | n/a |

## Notes for the next agent

- `space.set` (name, description, icon, colour) is an ordinary op for the owner and admins of a
  shared space; `space.leave` with another account's id, written by the owner, ends that account's
  access (`project::space_access`); `POST /spaces/{id}/members` adds connected accounts (owner).
  `space.delete` is a tombstone: both clients already hide deleted spaces.
- Private chats are `member_dm` channels inside a `dm` space. Internal members' DMs keep their
  soft D-097 behaviour (anyone may speak; `chat.hide_private` can turn hiding off).

## Slice 6 implementation notes

Manually ported the parked gesture UX onto current Android: swipe toward logical start with
resistance, 64 dp displayed threshold, threshold haptic, cancel-safe release and interruptible
spring return; double-tap hearts through the current acting-member reaction callback; long-press
haptic and accessible Reply action. RichBody retains single-tap links/spoilers and supports the
same double-tap action. Held and noninteractive cards do not acquire gestures. Motion follows
the existing system/inspection setting; heart feedback is omitted with reduced motion.

Visible list rows show a device-local `Draft:` preview before received typing activity, then the
normal last-message preview. Reads use the existing durable drafts directly, so typing edits do
not require a model refresh. Offline typing is suppressed. D-098 filtering still precedes preview
creation; no typing transport or Q21 default changed. Tests cover pull/threshold/inverse,
cancel/release/retreat/disabled eligibility, RTL mirroring and preview priority.

Validation: `:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug --offline` passed (F:/DunBuild/ui8-slice6-android.log). Wrapper `verify.py --quick --offline --android` passed (F:/DunBuild/chorus-verify-Chorus.log, exit 0). No device interaction validation or deployment in this slice.
