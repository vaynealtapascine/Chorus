# D-133 for the clients: space permissions and sharing (F5.11)

The contract the web and Android screens build on. The server and core half is done (D-160);
this file says exactly what exists, what to write, where to read it, and what to gate. Where it
says "not built", it isn't: don't fake it on the client.

## 1. What F5.11 maps to

F5.11 ("Permissions · <space> · you manage this space") has three sections. Rows map like this:

| F5.11 row | What it is | Op written | Who may change it |
| --- | --- | --- | --- |
| Send messages | permission `send` | `space.set_permission` | admins (owner, `admin` role) |
| Send photos and files | permission `attach` | `space.set_permission` | admins |
| Create polls | permission `poll` | `space.set_permission` | admins |
| Pin messages | permission `pin` | `space.set_permission` | admins |
| Invite other systems | permission `invite` (whole space only) | `space.set_permission` | admins |
| History before they joined | permission `history` (whole space only) | `space.set_permission` | admins |
| Allow system asides | permission `aside` | `space.set_permission` | admins |
| Member names on each message | **our own** sharing setting `member_names` | `space.set_sharing` | every member, for itself |
| Our fronting status | **our own** sharing setting `fronts` (+ `fronts_delay_min`) | `space.set_sharing` | every member, for itself |

The two "What they can see" rows are not permissions (owner, D-133): each system decides them
for its own messages and front, so a non-admin member sees the dialog with only those two rows
editable (or a separate "What we share here" sheet; the design choice is the client's). There is
no rename permission: renaming the space and its channels stays with `manage` (admins).

The F5.11 toggles for "everyone" write the `everyone` role override; per-account exceptions write
an `account` override; the custom-roles screen keeps writing `space.set_roles`.

## 2. Ops to write

All in the space's scope `space:<space id>`, entity id = the space id.

```jsonc
// admins only; any permission; LWW per (space, target_type, target_id): send the whole lists
{"kind": "space.set_permission", "entity_id": "<space>",
 "payload": {"target_type": "role" | "account", "target_id": "everyone" | "<role id>" | "<account id>",
             "allow": ["invite"], "deny": ["history", "attach"]}}

// any present member of a shared space, about itself; LWW per (space, account, field):
// send only the fields you change
{"kind": "space.set_sharing", "entity_id": "<space>",
 "payload": {"member_names": false}}            // or {"fronts": true, "fronts_delay_min": 30}
```

- Permission names: `view send react thread pin manage attach poll aside invite history`.
  `channel.set_permission` keeps working and now also takes `attach`, `poll`, `aside`; it refuses
  `invite` and `history` (`validate_op` says so).
- `space.set_sharing` accepts only `member_names` (bool), `fronts` (bool), `fronts_delay_min`
  (integer 0–1440) and refuses an empty payload; it is refused outside `shared` spaces and from
  non-members. `null` resets a field to the default.
- Like any edit of an override, rebase on the newest row (Android's `ChannelPermissions.patch`
  does this for channels; do the same for the space).

## 3. Where to read it (core projection, v8)

| Value | Projection (`project` / replica) |
| --- | --- |
| space overrides | `rows.space_permission["<space>|<target_type>|<target_id>"].fields = {allow, deny}` |
| channel overrides | `rows.channel_permission["<channel>|<target_type>|<target_id>"].fields = {allow, deny}` (as before) |
| sharing settings | `rows.space_sharing["<space>|<account>"].fields`: only the keys ever written (`member_names`, `fronts`, `fronts_delay_min`); absent = default |
| membership | `sets.space_member["<space>|{\"account_id\":\"<account>\"}"] === true` |
| role | `rows.space_member["<space>|<account>"].fields.role`, else `member` while present |
| custom roles | `rows.space[<space>].fields.roles` |
| owner | `rows.space[<space>].fields.created_by_account_id` (or `owner_account_id` from `GET /spaces`) |

Defaults when nothing is written: every role keeps what it could do before (members: view send
react thread pin attach poll aside history; read-only: view react attach poll aside history;
custom roles: their list plus attach poll aside history); `invite` off for everyone but admins;
`member_names` on; `fronts` off; `fronts_delay_min` 15.

`PROJECTION_VERSION` is 8: clients rebuild their cached UI snapshot once.

## 4. The rule, through core

Don't reimplement the rule. Build the levels and call core:

- web: `spacePerms(levelsJson: string): string` (from `web/src/lib/core/pkg`)
- Android: `uniffi.chorus_ffi.spacePerms(levelsJson: String): String` (throws `CoreException`)

Input (every level is `{allow: [...], deny: [...]}` or absent/`null`):

```jsonc
{
  "manager": false,          // owner, an "admin", or any participant of a "dm" space
  "role": "member",          // the role while present; null when the account isn't in the space
  "roles": [ /* rows.space[space].fields.roles */ ],
  "channel": {"account": …, "role": …, "everyone": …},   // omit for space-wide questions; a thread: its parent message's channel
  "space":   {"account": …, "role": …, "everyone": …}
}
```

`account` = the override whose target is our account, `role` = the one whose target is our role,
`everyone` = `target_type: role, target_id: everyone`. Output: the allowed permissions, in the
order above, e.g. `["view","send","react","thread","pin","attach","poll","aside","history"]`.
Channel permissions in the output already include the `view` requirement; `invite` and
`history` are space-wide and ignore `channel`. The vectors in `fixtures/perms/` are the cases.

## 5. What to gate

| Control | Shown when |
| --- | --- |
| Composer at all | `send` |
| Attach (photos, files, camera) | `send` and `attach` |
| New poll | `send` and `poll` |
| Aside / visibility chooser (system-only, members) | `send` and `aside` |
| Add people to the space | shared space and `invite` |
| Permissions dialog (F5.11 permission rows) | `manager` (owner or admin) |
| "Member names" / "Our fronting status" rows | any present member of a shared space |

The server refuses what the controls hide (`forbidden` acks with a reason), so a stale client
fails safely; show the reason like other refused sends.

## 6. REST

- `POST /spaces/{id}/members {accounts: [...]}`: owner, admins, or members with `invite`; each
  account must be connected to the inviter (follow either way). `403` otherwise.
- `GET /spaces/{id}/fronts` (present members of a shared space; else `404`):

  ```jsonc
  {"items": [{"account_id": "<other account>",
              "entries": [{"t": "subject", "subject_type": "member", "subject_id": "<id>",
                           "level": "front", "is_primary": true,
                           "name": "Kairos", "color": "#aa5500", "glyph": "🌙"},
                          {"t": "someone", "level": "cocon", "name": "Someone", "color": null, "glyph": null}]}]}
  ```

  One item per other account sharing its fronting status here and already revealed (an empty
  `entries` = nobody it may show is fronting). Our own account is never in it (show our front
  from our own data). It changes only at reveal times, minutes after a switch: fetch when the space
  opens or its member list shows, and every few minutes while it stays open; there is no socket
  frame. Use it for "who's fronting" on that space's member list and header, nowhere else (not as
  a follower view; the follow paths keep `/accounts/{id}/view`). No avatars: draw the glyph/colour
  disc. Ids are opaque here too: don't link them to profiles unless the profile path allows it.

## 7. Status

- Built: the permissions (`attach`, `poll`, `aside`, `invite`, `history`), space overrides, the
  core rule and its exports, `space.set_sharing` storage, invite through REST, and the history
  rule: a member without `history` simply never receives (sync, REST, search, files) messages the
  server got before its latest join, so clients need no filter; a chat may want a quiet "history
  starts here" marker at the first message it has, and shouldn't page back past it (REST pages
  before the join come back empty, slowly).
- Built: `member_names` off. Other accounts get no author cards for our members in that space
  (`GET /spaces/{id}/authors` leaves them out), notifications name the system, typing relays
  carry `member: null`. Clients on the reading side need nothing new: a message whose author id
  has no card already falls back to the account (show the account's name and avatar, never "Unknown
  member"; check that fallback). On the writing side, our own client keeps showing our names. The
  setting row should say plainly that others who can see our members elsewhere (a profile, a front
  view) can still match them, because the opaque ids stay in the messages.
- Built: the shared fronting status. `fronts` on starts a delayed reveal (default 15 minutes,
  `fronts_delay_min` 0–1440); off, leaving or removal withdraws it at once. The F5.11 row "Our
  fronting status" is a switch plus, in Advanced, the delay; say in its help text that everyone in
  the space sees who is fronting after that delay, as a follower with our default sharing would.

## 8. Defaults for a new space

Nothing is written for a space by default; every value above is the default when absent. The
F5.11 frame's values for a *new* space (e.g. history "From when they join", invite off) are the
new-space flow's to write right after `POST /spaces`, as `space.set_permission` for `everyone`;
existing spaces keep today's behaviour.

## 9. Web status (done 2026-11-14)

- [x] **Dialog** `ui/SpacePermissions.svelte` (lazy, `lazy-screens.ts`), opened from chat info → "Permissions"
  (admins and owner) or "What we share" (any other present member of a shared space). Rows as F5.11:
  Send messages / photos and files / polls / pin / invite → `space.set_permission` for `everyone` (whole lists,
  only the changed names touched, others kept); History before they joined (switch, "Everything" / "From when
  they join") and Allow system asides likewise; Member names and Our fronting status (+ delay segmented, shown
  when on) → `space.set_sharing`, only changed fields. Switches show what a plain member gets now
  (`space-perms.ts` `memberSwitches`, through `spacePerms`). Exceptions row → the existing override editor
  (`ChannelPermissions.svelte` without a channel = `space.set_permission`, all eleven names, unknown ones kept).
  One Save; Cancel/Close. Non-admins see only the two sharing rows.
- [x] **Gating** in `Chat.svelte` via `core.spacePerms` (levels from `rows.space_permission`, `rows.channel_permission`,
  role; a thread asks for its parent's channel): attach button / drop / paste need `attach`, Poll tile and the
  options poll need `poll`, the Aside bar and `system_only` need `aside`, Add people needs `invite`
  (`SpacePanel`, now also for admins and inviting members). Only for shared spaces and guest channels, and
  only once the space directory has loaded. The composer itself is **not** hidden without `send`: not built
  (the server's refusal shows in "Not sent" as before).
- [x] **Others' fronts**: `spaces.ts` `spaceFronts` (`GET /spaces/{id}/fronts`, `[]` on any error), polled
  every 3 minutes by `SpacePanel` while chat info is open; a glyph/colour disc and name under each account in the
  member list. Nothing is drawn when the endpoint returns nothing. Not in the chat header (no frame for it).
- [x] **History**: with `history` denied, "History starts here" shows above the first message once the server
  has nothing older (or the replica holds the whole chat). The "Older messages" button is kept: the server
  answers it with an empty page.
- [x] **Reading side of `member_names` off**: authors without a card fall back to their account's name and
  picture (`space-fronts.ts` `accountStandIns`), after the cards have loaded; they are not mentionable.
- [x] **New shared space** (`NewChat.svelte`): writes `space.set_permission` for `everyone` denying `invite` and
  `history` right after `POST /spaces` (`NEW_SPACE_EVERYONE`).
- Tests: `space-perms.test.ts` (rule through core over ops built with `project`), `space-fronts.test.ts`,
  `ui/SpacePermissions.test.ts`.

## 10. Android status (done 2026-11-14)

Android does what web does (section 9), in the D-124 drawer style:

- [x] **Form** `ui/SpacePermissionsDrawer.kt`, opened from chat info of a shared space: "Permissions" (owner, admins) or
  "What we share" (any other present member). The same rows and the same writes as web (`data/SpacePerms.kt`:
  `memberSwitches` through core `spacePerms`, `everyonePayload` with only the changed names touched and the rest of the
  lists kept; `Sharing.changes` sends only changed fields; the delay is a choice drawer: Now, 15 min, 1 hour, 6 hours,
  1 day, plus any value another client set). One Save. Exceptions opens `ChatPermissions.kt`'s override editor over
  `space_permission` (`SpaceExceptions`, all eleven names, unknown ones kept); the channel editor in Advanced is the
  same editor over `channel_permission` and now also knows `attach`, `poll`, `aside`.
- [x] **Gating** by core `spacePerms` (`SpacePerms.here`, levels from `rows.space_permission` / `channel_permission` and the
  role; a thread asks about its parent's channel; only shared spaces and guest channels, once the directory has loaded):
  the attach sheet drops its photo grid, Gallery and File without `attach` (the other tiles stay: Android's sheet also
  carries Warning, Journal, Who sees, Speaker), shared-in files are refused with a line, the Poll tile and chip need
  `poll`, the Aside bar and "Only my system" need `aside` (an aside kept in a draft goes back to everyone), Add people
  needs `invite` (removing people stays with the owner). Not built, as on web: hiding the composer without `send`.
- [x] **Others' fronts**: `Spaces.fronts` (`GET /spaces/{id}/fronts`, empty on any error), asked when chat info's people
  list opens and every 3 minutes while it stays; a glyph/colour disc and name under each account. Nothing is drawn when it
  returns nothing.
- [x] **History**: with `history` denied, "History starts here" above the oldest message once the replica holds nothing
  older. Android's chat never pages back to the server (it reads its replica), so there is no older page to hold back.
- [x] **Reading side of `member_names` off**: authors without a card fall back to their account's name and picture
  (`SpaceFronts.standIns`), once the cards have loaded. Android's `@` suggestions only ever offer our own members and
  groups, so nothing foreign is mentionable.
- [x] **New shared space**: `space.set_permission` for `everyone` denying `invite` and `history`, right after `POST /spaces`.
- [x] **Person accounts** (D-093) see no system words: no member-names or fronting rows (so a non-admin person has no
  "What we share" row), no "Allow system asides", and "Invite others" for "Invite other systems".
- Tests: `SpacePermsTest` (rule through core over ops built with `project`), `SpaceFrontsTest`, `ChannelPermissionsTest`.
