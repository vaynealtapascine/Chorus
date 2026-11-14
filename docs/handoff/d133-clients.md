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

## 7. Status

- Built: the permissions (`attach`, `poll`, `aside`, `invite`, `history`), space overrides, the
  core rule and its exports, `space.set_sharing` storage, invite through REST, and the history
  rule: a member without `history` simply never receives (sync, REST, search, files) messages the
  server got before its latest join, so clients need no filter; a chat may want a quiet "history
  starts here" marker at the first message it has, and shouldn't page back past it (REST pages
  before the join come back empty, slowly).
- Not built yet: the effect of `member_names`, the shared fronting
  status (`GET /spaces/{id}/fronts`). Write the settings, but don't promise their effect in the UI
  until this section says they are built.
