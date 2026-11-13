# Chorus — data model

Two layers:

1. **The op log** (`op` table): every change ever made, append-only, in order of arrival. This is
   what syncs.
2. **Projected tables**: plain, normalised SQL tables that represent *current state* (plus a few
   derived history tables like `front_interval`). These are what apps render and what humans query.
   They are fully **rebuildable** from the log (`chorus-server rebuild`).

Design rule: someone who knows basic SQL should be able to open `chorus.db` in DB Browser, DuckDB
or Datasette and answer "how long did Kai front last month?" without reading code. Hence: readable
column names, no bitfields, no opaque blobs for queryable data, JSON only for genuinely
schemaless things, and a set of `v_*` views (§6) that pre-join the common questions.

---

## 1. Conventions

| Thing | Convention |
| --- | --- |
| IDs | UUIDv7, lowercase hyphenated TEXT (`0192f8c2-7d1e-7abc-9def-0123456789ab`). Generated on the client that creates the object (offline-safe). Time-sortable. |
| Times | INTEGER ms since Unix epoch, UTC. Column names end in `_at`. Local-time analysis uses `tz_offset_min` stored alongside where it matters (switches, messages, posts). |
| Durations | INTEGER seconds, columns end in `_s`. |
| Booleans | INTEGER 0/1, columns start `is_`/`can_`/`has_`. |
| Enums | TEXT with a `CHECK (x IN (...))`. Never integer codes. |
| JSON | TEXT with `CHECK (json_valid(x))`. Used for: entities, settings, visibility, clocks, snapshots. |
| Soft delete | `deleted_at` (tombstone). Queries/views exclude them. |
| Trash stamps | For `message`, `post`, `member`, `member_group` and `channel` the reference projection also carries `created_by_account_id` (first create) and `deleted_by_account_id` (latest delete, cleared on restore), plus `deleted_by_member_id` (the delete op's acting `member_id`, null when none was named; cleared on restore; projection v6) for Trash's "Deleted by". Only those two accounts may restore (`core::restore::allowed`, D-053). |
| Space owner and members | The reference projection stamps `space` rows with `created_by_account_id` as well: the creator, which is the server's `space.owner_account_id`. Who is in a space is the `space_member` **set** (`space.join`/`space.leave`, keyed `<space>|{"account_id":…}`); `space_member` rows carry only the role from `space.set_role` and never `exists`. A role counts while the join is present, else it is `member` (perms.rs). Since projection v5. |
| LWW clocks | Tables with field-level last-writer-wins carry `clocks` JSON: `{"name": "<hlc>", ...}`. Only the merge code reads it. |
| HLC | String `"<physical_ms, 12 hex>-<counter, 4 hex>-<device short_id, 8 hex>"`, zero-padded, e.g. `"01a0c27d1e3f-0003-a1b2c3d4"`; plain string comparison = HLC order. See SYNC.md §3. |
| Rich text | `text` (plain UTF-8) + `entities` JSON array `[{"type":"bold","offset":0,"length":5}, ...]`. Offsets/lengths are in **UTF-16 code units** (Telegram-compatible; what Android and JS use natively). Posts and journal entries may also carry `{"type":"heading","level":1|2,"offset","length"}` over one whole line (markup `# ` / `## `, D-134); `validate_new` refuses it on messages, at other levels, or over part of a line, and chat keeps `# ` literal. Renderers and `to_markup` drop entity types they don't know (`Rich::lenient`) instead of failing the text. |
| Names | snake_case, singular table names. |

SQLite pragmas (server and Android): `journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`,
`busy_timeout=5000`, `temp_store=MEMORY`, `mmap_size=268435456`.

## 2. The op log

```sql
CREATE TABLE op (
  seq            INTEGER PRIMARY KEY,          -- server arrival order, gapless per server
  id             TEXT NOT NULL UNIQUE,          -- UUIDv7 from the client; idempotency key
  scope          TEXT NOT NULL,                 -- 'account:<id>' | 'space:<id>' (see SYNC.md §4)
  kind           TEXT NOT NULL,                 -- e.g. 'front.switch' (catalogue in §3)
  entity_id      TEXT,                          -- primary object the op touches
  payload        TEXT NOT NULL CHECK (json_valid(payload)),
  v              INTEGER NOT NULL DEFAULT 1,    -- payload schema version for this kind
  hlc            TEXT NOT NULL,
  account_id     TEXT NOT NULL,                 -- who did it
  device_id      TEXT NOT NULL,
  member_id      TEXT,                          -- acting member if meaningful (speaker, editor)
  occurred_at    INTEGER NOT NULL,              -- corrected time (SYNC.md §3)
  device_at      INTEGER NOT NULL,              -- raw device wall clock at creation
  tz_offset_min  INTEGER NOT NULL,              -- device UTC offset at creation
  mono           INTEGER,                       -- device monotonic ms at creation (Android)
  boot_id        TEXT,                          -- device boot id, makes mono comparable
  time_source    TEXT NOT NULL DEFAULT 'auto' CHECK (time_source IN ('auto','user')),
  time_suspect   INTEGER NOT NULL DEFAULT 0,    -- clock looked broken (SYNC.md §3.2)
  seen_seq       INTEGER NOT NULL DEFAULT 0,    -- device's cursor for this scope at creation (SYNC.md §5.7)
  received_at    INTEGER NOT NULL,              -- server arrival
  status         TEXT NOT NULL DEFAULT 'applied'
                 CHECK (status IN ('applied','rejected','superseded'))
);
CREATE INDEX op_scope_seq ON op(scope, seq);
CREATE INDEX op_entity ON op(entity_id);
-- ops pointing at a message (0005): late public sends release them, purge finds them
CREATE INDEX op_message_ref ON op(json_extract(payload, '$.message_id'))
  WHERE json_extract(payload, '$.message_id') IS NOT NULL;
```

Rules:

- Ops are never updated except `status`. Never deleted except by an explicit purge from the
  server CLI only (`chorus-server purge`, `purge.rs`; logged to `data/purge.log`): it rewrites
  the payloads of purged ops to `{"purged":true}` and keeps their ids, so sync digests don't
  change. Core treats such an op as opaque (`op::is_purged`): stored, never projected, on the
  server and on every device that receives it. Apps cannot purge (D-053).
- Unknown `kind` or newer `v` from a newer client: stored and forwarded, projection skips it
  (forward compatibility). Older `v` is upcast in `chorus-core` before projection.
- `rejected` ops (permission/validation failure) stay in the log for debugging, are sent back to
  the originating device with a reason, and are never forwarded.

### 2.1 Op envelope (wire + core type)

```json
{
  "id": "0192f8c2-...",
  "kind": "front.switch",
  "v": 1,
  "scope": "account:0192...",
  "entity_id": "0192f8c2-...",
  "hlc": "01a0c27d1e3f-0000-a1b2c3d4",
  "device_at": 1790000000000,
  "tz_offset_min": 120,
  "mono": 81234567,
  "boot_id": "41",
  "time_source": "auto",
  "seen_seq": 1233,
  "member_id": null,
  "payload": { }
}
```

The server adds `seq`, `account_id`, `device_id` (from the authenticated session — never trusted
from the payload), `occurred_at`, `received_at`.

### 2.2 Payload rules

`op::validate_new` (core, run by every client when creating an op and by the server at ingest)
checks more than `op::validate`'s field names: every payload field stored in a constrained column obeys
`op::FIELD_RULES` (never `null` for a NOT NULL column, an object or list for a JSON column,
`true`/`false` for a flag, one of the allowed values for an `IN (…)` check), plus per-kind rules
(`channel.set_permission` needs a `target_type`/`target_id` and known permissions, reactions a
`target_type`/`target_id`/`emoji`, message and post `text` is text and `entities`/`tags` lists, entities and segments are
`{offset, length}` ranges (UTF-16) inside the text with a `type`/`authors` (so another
account's message can't make a renderer slice out of range),
`space.set_roles` a list of `{id, name, perms}`, a channel's `settings.slow_mode_s` whole seconds
up to 21 600). A test compares `FIELD_RULES` with the SQL
schema, so a migration that adds a constraint must add its rule. The rules apply to **new** ops
only: projection (`model::apply_op`, the server's rebuild) and restore pushes use the structural
`op::validate`, so an op stored before a rule existed keeps projecting exactly as before.

Why it matters: the server stores and projects each pushed op in its own savepoint. An op the
projection can't apply is refused on its own (`unprocessable`, logged) instead of failing the
batch, which would otherwise be resent forever with the device's outbox stuck behind it. Two
fuzz tests keep this honest: `chorus-server/tests/ingest_fuzz.rs` (random ops of every kind
through ingest: never a failed batch, never `unprocessable`, and a rebuild reproduces the live
tables) and `chorus-core/tests/payload_fuzz.rs` (the client projection: no panic, same state in
any order). `CHORUS_FUZZ_CASES=<n>` runs the server one longer.

## 3. Op catalogue (v1)

UI6 profile details (D-090) reuse `field.define` / `field.set_value`: Location (`text`), Website,
Website 2 and Website 3 (`url`). Reuse the matching live definition with the smallest ID;
otherwise its ID is UUIDv3 of UTF-8 `chorus.profile:<account-id>:<field-name>` (Java's
`UUID.nameUUIDFromBytes`, MD5 without an additional namespace). New definitions are private.
Existing definitions, default visibility and member field-visibility maps remain untouched.
D-101 gives those maps meaning: `member.visibility` is who may open the profile, and
`member.field_visibility` maps a part key (`display_name`, `pronouns`, `description`, `birthday`,
`banner`) or a custom field id to its own audience (unset: the profile's for parts, the field's
`default_visibility` for fields). Friend reads apply them on the server (`profiles.rs`); shared
fixture `fixtures/ui_profile_audience.json`.
At Save, preflight **all changed** field slots before any member/field op, on the replica's
serial worker. Every reused live ID must have the expected type, even when absent at opening;
a missing formerly-live ID or a tombstone requires reopening. An absent-at-open ID newly used
for another name also requires reopening. Keep established IDs even if another matching ID
appears. If an absent slot's reserved ID is still unused and a matching definition appeared at
another ID, reuse the smallest live matching ID only when neither old nor target has an existing
value to rebind/overwrite; otherwise reopen. Never redefine a live field or overwrite its metadata.
No additional member columns are needed.

Manual member positions use existing `member.set.sort_key`. D-090 keys start `~ui6:` followed by
a fractional printable-ASCII position, never ending in space. Compare the position plus `!` plus
four-hex-digit UTF-16 units of the member ID by code units, not locale collation. Legacy/unset
positions encode a non-null sort key **verbatim**, including empty/invalid/prefix-only keys.
For an unset key, take display-name if non-null (even empty), otherwise name; fold only ASCII
A–Z UTF-16 units by adding 32. Preserve all other units, with no Unicode normalization/casing
or locale/runtime tables (D-091). Encode each unit as four lowercase hex digits. This includes
unpaired surrogates on JS/JVM; the pure-core reference accepts UTF-16 units explicitly. Identity suffixes permit insertion between otherwise equal positions without renumbering.
Choose a printable midpoint between complete neighbouring keys; only moved members are written.
The shared `fixtures/ui_member_order.json` fixes Android/web Unicode and tie behavior. Ordinary
field-level LWW resolves competing edits of one member; different members may converge at the
same position, resolved by ID. These positions govern Members, not front/speaker/recent order.

Merge column: **LWW-F** = field-level last-writer-wins by HLC · **SET** = LWW element set
(add/remove per element, highest HLC wins) · **APP** = append-only, each op creates a new row ·
**TL** = timeline fold (front, SYNC.md §5).

| Kind | Payload (summary) | Merge |
| --- | --- | --- |
| `account.set` | `{fields}` display_name, handle, avatar, settings; the server writes one `{handle}` at enrolment (and once for older accounts) so devices know it offline (D-126) | LWW-F |
| `system.set` | `{fields}` name, tag, description, colour, terminology, timezone | LWW-F |
| `member.create` | full initial fields | APP (then LWW-F) |
| `member.set` | `{fields}` any subset of member columns; `handles` must be ≤5 distinct claims, each unique on the server, a deleted member's claims included (ingest acks `conflict`, D-126, D-146) | LWW-F |
| `member.archive` / `member.unarchive` | `{}` | LWW-F on `archived_at` |
| `member.delete` | `{}` tombstone; content authored stays, shows "deleted member" | LWW-F |
| `group.create` / `group.set` / `group.delete` | fields incl. `parent_id` | LWW-F (cycle guard §5.3 of SYNC) |
| `group.add_member` / `group.remove_member` | `{member_id}` | SET |
| `state.create` / `state.set` / `state.delete` | custom front states | LWW-F |
| `field.define` / `field.set_def` / `field.delete_def` | custom field definitions | LWW-F |
| `field.set_value` | `{member_id, field_id, value}` | LWW-F per (member, field) |
| `poll.vote` | `{member_id, choices:[option_id]}` — entity = the poll's parent post/message; in the **voter's** `account:` scope; `[]` withdraws (D-081, D-086, POLLS.md) | LWW per (poll, member) |
| `front.switch` | `{entries:[{subject_type, subject_id, level, is_primary}], based_on, note, notify}` full snapshot, array order = fronting order | TL |
| `front.add` | `{entry, position?}` | TL |
| `front.remove` | `{subject_type, subject_id}` | TL |
| `front.update` | `{subject_type, subject_id, level?, is_primary?, position?}` | TL |
| `front.retract` | `{target_op_id}` (undo) | TL |
| `front.amend` | `{target_op_id, occurred_at?, entries?, note?}` edit a past switch | TL |
| `front.review_resolve` | `{review_id, resolution}`; `resolution` ∈ `keep_both`/`keep_a`/`keep_b`/`merge` | LWW-F per account in canonical (HLC, id) order — the smaller id wins an equal clock. A choice may arrive before its card and is applied when the card appears; unknown values or versions stay in the log but never close a card |
| `front.define` | sparse `{name?, follow_source?, authorship_layers?, notification_layers?, purpose_layers?, deleted?}`; entity is the named front | LWW-F, including each purpose separately |
| `front.layer` | `{layer_id, name?, level?, deleted?}`; display level only, system-local string ID | LWW-F |
| `front.change` | `{action, ...}`; independent presence, Resume or own-history controls, with optional `effective_at`/`history_only` on presence (FRONTS.md) | TL + write-ordered controls |
| `space.create` / `space.set` / `space.delete` | | LWW-F |
| `space.join` / `space.leave` / `space.set_role` | `{account_id, role}` | SET / LWW-F |
| `channel.create` / `channel.set` / `channel.archive` / `channel.delete` / `channel.restore` | | LWW-F |
| `channel.set_permission` | `{target_type: role or account, target_id, allow:[…], deny:[…]}` | LWW-F per (channel, target) |
| `space.set_roles` | custom roles for shared spaces `{roles:[{id,name,color,perms}]}` | LWW-F |
| `message.send` | `{channel_id, authors:[member_id], text, entities, segments?:[{offset,length,authors}], reply_to?, quote?, forward?, cw?, visibility?, attachments?:[attachment_id], sent_offline:bool, poll?:{question?, options:[{id,text}], multiple, closes_at?}}`; segment offsets/lengths are UTF-16. `poll` is fixed at creation (edits may not carry it; POLLS.md). `quote` is a single `{message_id,offset,length,text}` or a bundle `{items:[snapshot]}` (D-048). | APP |
| `message.edit` | `{message_id, text, entities, segments?, cw?}` → new revision; supply updated segments when editing text so attribution and offsets stay aligned (D-045); structural fields (`channel_id`, `visibility`, `reply_to`, `quote`, `forward_*`, `poll`…) and managed columns in the payload are ignored (D-123) | APP (latest HLC shown) |
| `message.delete` / `message.restore` | `{message_id}` — restore clears `deleted_at` with a newer HLC | LWW-F |
| `message.forward` | `{channel_id, authors, text, entities, forward_of_id, forward_snapshot:[{message_id, channel_name?, authors, text, entities, offset?, length?, occurred_at}]}` → a new message with source snapshots captured by the sending client (D-048) | APP |
| `*.restore` | `post.restore`, `member.restore`, `group.restore`, `space.restore` — same rule as messages | LWW-F |
| `message.pin` / `message.unpin` | `{message_id}` | LWW-F |
| `reaction.add` / `reaction.remove` | `{target_type: message, target_id, emoji, member_id}` — in the message's `space:` scope | SET |
| `post.react` / `post.unreact` | `{target_type: post, target_id, emoji, member_id}` — in the **reactor's** `account:` scope (they may not write to the post owner's scope); owners see them through views | SET |
| `front.unretract` | `{target_op_id}` (undo an undo, Advanced) | TL |
| `post.attachment` | attachment row for a post, same payload as `attachment.create` | APP |
| `read.mark` | `{channel_id, reader_member_id?, message_id}` | max-by-message-order |
| `attachment.create` | `{blob_hash, filename, mime, size, width?, height?, duration_ms?, alt_text?}` | APP |
| `attachment.set` | `{alt_text?, is_spoiler?}` | LWW-F |
| `post.create` | `{kind: note or entry, authors, title?, text, entities, mood?, tags?, cw?, visibility, reply_to?, quote?, repost_of?, attachments?, poll?, alerts?}` (`alerts`: `none` · `delayed`, D-127; read from the op at live ingest, not stored) | APP |
| `message.poll_tally` / `post.poll_tally` | `{channel_id?, counts:{option_id: n}, voters}` — server-derived only, in the parent's scope; never pushed by devices; no voter identities (D-086) | LWW per poll |
| `poll.nudge` | `{member_ids:[1–30 member_id], by_member_id?}` — entity = the poll's parent; in the **nudger's** `account:` scope; LWW per (poll, member) on `nudged_at` (D-136, D-143, POLLS.md) |
| `message.poll_close` / `post.poll_close` | `{channel_id}` (message) / `{}` (post) — the parent's author closes its poll early, in the parent's scope; no reopen (D-136, D-141). `poll.closes_at` (ms) on `message.send`/`post.create` is the planned close; votes the server receives at or after the earlier of the two are refused and the last tally is final. Table `poll_close (poll_id, closed_at, hlc)` (migration 0022) | earliest `occurred_at` wins (not LWW) |
| `post.edit` / `post.delete` / `post.set_visibility` | | APP / LWW-F |
| `draft.set` / `draft.delete` | composer drafts (per channel or post), synced | LWW-F |
| `highlight.add` / `highlight.remove` / `highlight.reorder` | `{profile_member_id, post_id, sort_key}` | SET + LWW-F |
| `relationship.set` / `relationship.delete` | | LWW-F |
| `reltype.set` / `reltype.delete` | | LWW-F |
| `list.set` / `list.delete` / `list.add` / `list.remove` | | LWW-F / SET |
| `feed.set` / `feed.delete` | `{name, query, visibility}` | LWW-F |
| `bucket.set` / `bucket.delete` / `bucket.assign` / `bucket.unassign` | | LWW-F / SET |
| `follow.request` / `follow.accept` / `follow.set_ceiling` / `follow.set_prefs` / `follow.end` | NOTIFICATIONS.md | LWW-F |
| `emoji.create` / `emoji.set` / `emoji.delete` / `emoji.restore` | `{name, aliases, category, blob_hash, is_animated}` | LWW-F |
| `stage.save` / `stage.delete` | stage view definitions | LWW-F |
| `pref.set` | per-account or per-device preferences (`{scope:'account'|'device', key, value}`). Journal saves are one account pref per post, `bookmark:<post id>` = `true` (null clears): private to the account's own devices, never visible to the post's author (UI7). A member who is a subsystem is `member_subsystem:<member id>` = subsystem group id (D-092). The Members list (D-100): `members.view` = `{pills, role_field?, bio, banners, flat}`, `member_folders` = ordered `[{id, name}]`, `member_folder:<member id>` = folder id | LWW-F |
| `admin.*` | invites, device revoke, purge | server-only |

The authoritative list is `CATALOGUE` in `chorus-core/src/op.rs` (kind → table, scope, merge action, settable fields); this table documents it. Every payload type is a Rust struct in `chorus-core` with serde; the JSON Schema for each is
generated into `fixtures/schema/` so Kotlin/TS can validate.

## 4. Projected tables (server schema, SQLite)

> **Authoritative DDL:** `crates/chorus-server/migrations/*.sql`. The listing below is the design
> reference. The migration differs in a few deliberate ways: data columns are nullable (rows are
> written by re-projecting an entity's ops through `chorus_core::model`, and validation happens
> on ops); `op.restored` marks ops re-pushed in a restore window; server-only tables `session`,
> `auth_nonce` and `scope_access` exist; `member_group.effective_parent_id` holds the cycle-guarded
> parent; `space.roles` holds custom roles; `device.platform` allows `token` (API-token writes);
> since migration 0009 (R26) the per-item key tables (`message_author`, `message_segment`,
> `message_segment_author`, `mention`, `post_author`, `item_attachment`) are `WITHOUT ROWID`:
> the same columns and keys, stored in their primary key's B-tree alone. A rebuilt database
> (`chorus-server rebuild`) has 8 KB pages.

Android's Room schema mirrors these tables for the scopes the device holds. The web client keeps
the same shapes as IndexedDB object stores. Column names are identical everywhere.

```sql
-- ─── accounts & devices ──────────────────────────────────────────────
CREATE TABLE account (
  id            TEXT PRIMARY KEY,
  kind          TEXT NOT NULL CHECK (kind IN ('system','person')),
  handle        TEXT NOT NULL UNIQUE,        -- @handle, for mentions across spaces
  display_name  TEXT NOT NULL,
  avatar_blob   TEXT,
  is_admin      INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  settings      TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(settings)),
  clocks        TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE device (
  id               TEXT PRIMARY KEY,
  account_id       TEXT NOT NULL REFERENCES account(id),
  short_id         TEXT NOT NULL UNIQUE,     -- 8 hex, used in HLCs
  name             TEXT NOT NULL,            -- "Pixel", "Firefox on desk PC"
  platform         TEXT NOT NULL CHECK (platform IN ('android','web','cli')),
  public_key       TEXT NOT NULL,            -- SPKI base64 (P-256)
  created_at       INTEGER NOT NULL,
  last_seen_at     INTEGER,
  clock_offset_ms  INTEGER NOT NULL DEFAULT 0,   -- server_time - device_time, smoothed
  push_endpoint    TEXT,                     -- UnifiedPush endpoint URL
  revoked_at       INTEGER,
  wipe_on_signout  INTEGER NOT NULL DEFAULT 0 -- signed out with "erase now" (D-152, migration 0023)
);

CREATE TABLE invite (
  code_hash     TEXT PRIMARY KEY,            -- sha256 of the code; code itself never stored
  kind          TEXT NOT NULL CHECK (kind IN ('system','person','device')),
  account_id    TEXT,                        -- for 'device' invites
  created_by    TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL,
  max_uses      INTEGER NOT NULL DEFAULT 1,
  uses          INTEGER NOT NULL DEFAULT 0
);

-- ─── systems, members, groups ────────────────────────────────────────
CREATE TABLE system (
  account_id    TEXT PRIMARY KEY REFERENCES account(id),
  name          TEXT NOT NULL,
  tag           TEXT,                        -- short suffix shown after names in shared spaces
  description   TEXT, description_entities TEXT,
  color         TEXT,                        -- '#RRGGBB'
  timezone      TEXT,                        -- IANA, for local-day analytics
  terminology   TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(terminology)),
  clocks        TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE member (
  id                  TEXT PRIMARY KEY,
  account_id          TEXT NOT NULL REFERENCES account(id),
  name                TEXT NOT NULL,
  display_name        TEXT,
  pronouns            TEXT,
  description         TEXT, description_entities TEXT,
  color               TEXT,
  avatar_blob         TEXT,
  banner_blob         TEXT,
  birthday            TEXT,                  -- 'YYYY-MM-DD' or '--MM-DD'
  sigils              TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(sigils)),       -- ["🌌"]
  proxy_tags          TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(proxy_tags)),   -- [{"prefix":"k:","suffix":""}]
  pinned_post_id      TEXT,
  sort_key            TEXT,                  -- fractional index string
  is_self             INTEGER NOT NULL DEFAULT 0,   -- the one member of a person account
  is_locked           INTEGER NOT NULL DEFAULT 0,   -- has a PIN gate (the PIN itself never syncs in clear; see CLIENTS.md)
  visibility          TEXT NOT NULL DEFAULT '{"mode":"private"}' CHECK (json_valid(visibility)),
  field_visibility    TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(field_visibility)),
  notify_policy       TEXT NOT NULL DEFAULT '{"announce":"everyone","announce_leaving":false}' CHECK (json_valid(notify_policy)),  -- NOTIFICATIONS.md §2.4
  short_id            TEXT NOT NULL,         -- 7 lowercase letters, unique per server; Advanced-only in UI (D-049)
  handles             TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(handles)),  -- claimed @handles, ≤5 of [a-z0-9_]{2,32}, first shown (D-126)
  pk_id               TEXT,                  -- PluralKit 5/6-char id when imported
  created_at          INTEGER NOT NULL,
  archived_at         INTEGER,
  deleted_at          INTEGER,
  clocks              TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX member_account ON member(account_id);

CREATE TABLE member_group (                    -- 'group' is an SQL keyword
  id            TEXT PRIMARY KEY,
  account_id    TEXT NOT NULL REFERENCES account(id),
  kind          TEXT NOT NULL CHECK (kind IN ('subsystem','group')),
  parent_id     TEXT REFERENCES member_group(id),
  name          TEXT NOT NULL,
  description   TEXT, description_entities TEXT,
  color         TEXT, icon TEXT, avatar_blob TEXT,
  can_front     INTEGER NOT NULL DEFAULT 1,
  sort_key      TEXT,
  visibility    TEXT NOT NULL DEFAULT '{"mode":"private"}',
  created_at    INTEGER NOT NULL,
  deleted_at    INTEGER,
  clocks        TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE group_membership (                -- LWW element set; present iff added_hlc > removed_hlc
  group_id      TEXT NOT NULL REFERENCES member_group(id),
  member_id     TEXT NOT NULL REFERENCES member(id),
  added_hlc     TEXT,
  removed_hlc   TEXT,
  is_present    INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (group_id, member_id)
);

CREATE TABLE custom_state (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL,
  description TEXT, color TEXT, icon TEXT, sort_key TEXT,
  created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE field_def (
  id            TEXT PRIMARY KEY,
  account_id    TEXT NOT NULL,
  name          TEXT NOT NULL,
  type          TEXT NOT NULL CHECK (type IN ('text','long_text','number','date','select','multi_select','boolean','color','url','member_ref','rating')),
  options       TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(options)),   -- choices, min/max, etc.
  default_visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  sort_key      TEXT,
  deleted_at    INTEGER,
  clocks        TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE field_value (
  member_id     TEXT NOT NULL REFERENCES member(id),
  field_id      TEXT NOT NULL REFERENCES field_def(id),
  value         TEXT NOT NULL CHECK (json_valid(value)),   -- JSON scalar/array per type
  value_text    TEXT GENERATED ALWAYS AS (json_extract(value, '$')) VIRTUAL,  -- easy querying
  hlc           TEXT NOT NULL,
  PRIMARY KEY (member_id, field_id)
);

-- ─── front ───────────────────────────────────────────────────────────
-- One row per front op that survives (not retracted). Human-readable "switch log".
CREATE TABLE switch (
  id               TEXT PRIMARY KEY,           -- = op id
  account_id       TEXT NOT NULL,
  kind             TEXT NOT NULL CHECK (kind IN ('switch','add','remove','update')),
  occurred_at      INTEGER NOT NULL,           -- after amend
  tz_offset_min    INTEGER NOT NULL,
  device_id        TEXT NOT NULL,
  based_on         TEXT,                       -- switch id this device saw as current
  entries          TEXT NOT NULL CHECK (json_valid(entries)),   -- op payload entries (delta or snapshot)
  resulting_front  TEXT NOT NULL CHECK (json_valid(resulting_front)),  -- full snapshot after applying
  note             TEXT,
  notify           TEXT NOT NULL DEFAULT 'default' CHECK (notify IN ('default','silent','now','extra_delay')),
  was_offline      INTEGER NOT NULL DEFAULT 0,
  retracted_at     INTEGER,                    -- undo; row kept for audit, ignored by folds
  amended_at       INTEGER
);
CREATE INDEX switch_account_time ON switch(account_id, occurred_at);

-- Derived: one row per continuous presence of one subject at one level.
-- Rebuilt for the affected time range whenever a switch is inserted/amended/retracted in the past.
CREATE TABLE front_interval (
  id               TEXT PRIMARY KEY,           -- deterministic: hash(account, subject, level, start_switch_id)
  account_id       TEXT NOT NULL,
  subject_type     TEXT NOT NULL CHECK (subject_type IN ('member','group','state')),
  subject_id       TEXT NOT NULL,
  level            TEXT NOT NULL CHECK (level IN ('front','cocon','present')),
  is_primary       INTEGER NOT NULL,           -- primary at interval start (changes split intervals)
  position         INTEGER NOT NULL,           -- fronting order at start (0 = first)
  start_at         INTEGER NOT NULL,
  end_at           INTEGER,                    -- NULL = ongoing
  start_switch_id  TEXT NOT NULL,
  end_switch_id    TEXT,
  start_tz_offset_min INTEGER NOT NULL
);
CREATE INDEX fi_account_time ON front_interval(account_id, start_at);
CREATE INDEX fi_subject ON front_interval(subject_id, start_at);
-- migration 0004: a live switch folds one step on the open intervals (server append path)
CREATE INDEX fi_open ON front_interval(account_id) WHERE end_at IS NULL;
CREATE INDEX fi_account_end ON front_interval(account_id, end_at);

-- Derived: seconds per subject per local day (split across midnights in the system timezone).
CREATE TABLE front_daily (
  account_id TEXT NOT NULL, day TEXT NOT NULL,   -- 'YYYY-MM-DD' local
  subject_type TEXT NOT NULL, subject_id TEXT NOT NULL, level TEXT NOT NULL,
  seconds INTEGER NOT NULL, as_primary_seconds INTEGER NOT NULL,
  PRIMARY KEY (account_id, day, subject_type, subject_id, level)
);

CREATE TABLE front_review (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
  switch_a TEXT NOT NULL, switch_b TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  resolution TEXT CHECK (resolution IN ('keep_both','keep_a','keep_b','merge')),
  resolved_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

-- ─── spaces, channels, messages ──────────────────────────────────────
CREATE TABLE space (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('internal','shared','dm')),
  owner_account_id TEXT NOT NULL,
  name TEXT, icon TEXT, color TEXT, description TEXT,
  settings TEXT NOT NULL DEFAULT '{}',
  created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE space_member (
  space_id TEXT NOT NULL, account_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('owner','admin','member','read_only')),
  joined_hlc TEXT, left_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (joined_hlc IS NOT NULL AND (left_hlc IS NULL OR joined_hlc > left_hlc)) STORED,
  PRIMARY KEY (space_id, account_id)
);

CREATE TABLE channel (
  id TEXT PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES space(id),
  kind TEXT NOT NULL CHECK (kind IN ('text','thread','member_dm')),
  category TEXT,                                -- category name (categories are just labels + sort)
  name TEXT NOT NULL, topic TEXT, icon TEXT, color TEXT, avatar_blob TEXT, -- optional channel image (D-088)
  parent_message_id TEXT,                       -- threads
  member_ids TEXT CHECK (member_ids IS NULL OR json_valid(member_ids)),  -- member_dm participants
  settings TEXT NOT NULL DEFAULT '{}',          -- slow_mode_s (Q17), lock (autoproxy is the account's pref, D-074)
  sort_key TEXT,
  created_at INTEGER NOT NULL, archived_at INTEGER, deleted_at INTEGER,
  clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE message (
  id               TEXT PRIMARY KEY,
  channel_id       TEXT NOT NULL REFERENCES channel(id),
  account_id       TEXT NOT NULL,
  device_id        TEXT NOT NULL,
  occurred_at      INTEGER NOT NULL,            -- sort key (with id tie-break)
  tz_offset_min    INTEGER NOT NULL,
  received_at      INTEGER NOT NULL,
  sent_offline     INTEGER NOT NULL DEFAULT 0,  -- shows "sent offline · synced HH:MM"
  kind             TEXT NOT NULL DEFAULT 'text' CHECK (kind IN ('text','voice','video','system')),
  text             TEXT NOT NULL,               -- current revision, plain
  entities         TEXT NOT NULL DEFAULT '[]',
  cw               TEXT,
  reply_to_id      TEXT,                        -- may be in another channel
  quote            TEXT CHECK (quote IS NULL OR json_valid(quote)),      -- {message_id, offset, length, text} or {items:[snapshot]}
  forward_of_id    TEXT,                        -- first forwarded item, for simple queries
  forward_snapshot TEXT CHECK (forward_snapshot IS NULL OR json_valid(forward_snapshot)),  -- [{message_id, channel_name, authors:[member_id], text, entities, offset?, length?, occurred_at}]
  visibility       TEXT CHECK (visibility IS NULL OR json_valid(visibility)),  -- {"mode":"members","member_ids":[...]} | {"mode":"system_only"}
  revision_count   INTEGER NOT NULL DEFAULT 1,
  edited_at        INTEGER,
  pinned_at        INTEGER, pinned_by_member TEXT,
  deleted_at       INTEGER,
  thread_channel_id TEXT,
  char_count       INTEGER GENERATED ALWAYS AS (length(text)) VIRTUAL,
  clocks           TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX message_channel_time ON message(channel_id, occurred_at, id);
CREATE INDEX message_reply ON message(reply_to_id);

-- Segments (D-045). Every message has >= 1 segment; a joint message has exactly one.
-- message_author is the union of segment authors in order of first appearance.
CREATE TABLE message_segment (
  message_id TEXT NOT NULL REFERENCES message(id),
  idx        INTEGER NOT NULL,                  -- 0-based order
  offset_u16 INTEGER NOT NULL, length_u16 INTEGER NOT NULL,   -- range in message.text (UTF-16, like entities)
  text       TEXT NOT NULL,                     -- copy of that range, for easy analysis
  PRIMARY KEY (message_id, idx)
);
CREATE TABLE message_segment_author (
  message_id TEXT NOT NULL, idx INTEGER NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (message_id, idx, member_id)
);
CREATE INDEX msa_member ON message_segment_author(member_id);

CREATE TABLE channel_permission (                -- Discord-style overrides (D-047)
  channel_id  TEXT NOT NULL REFERENCES channel(id),
  target_type TEXT NOT NULL CHECK (target_type IN ('role','account')),
  target_id   TEXT NOT NULL,                     -- role id/name or account id
  allow       TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(allow)),   -- ["view","send","react","thread","pin","manage"]
  deny        TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(deny)),
  hlc         TEXT NOT NULL,
  PRIMARY KEY (channel_id, target_type, target_id)
);

CREATE TABLE message_author (
  message_id TEXT NOT NULL REFERENCES message(id),
  member_id  TEXT NOT NULL,
  position   INTEGER NOT NULL,
  PRIMARY KEY (message_id, member_id)
);
CREATE INDEX message_author_member ON message_author(member_id);

-- every version of an *edited* message (unedited ones have no rows: their row is their only
-- version), from chorus_core::revisions: the fold of its send and edits after each op in HLC
-- order, so the last equals the message. Post history now uses post_revision_diff (D-107)
-- below; legacy post_revision rows are retained if any source version cannot be reconstructed
-- with matching clock/time/title/text/entities. Later edits and rebuild preserve them too.
-- Their old schema omitted CW, so the read API conservatively gates unknown warning state.
-- Both are read by GET …/revisions under the current item's read rule.
CREATE TABLE message_revision (
  message_id TEXT NOT NULL, rev INTEGER NOT NULL,
  text TEXT NOT NULL, entities TEXT NOT NULL, cw TEXT,
  edited_at INTEGER NOT NULL, hlc TEXT NOT NULL, device_id TEXT NOT NULL,
  PRIMARY KEY (message_id, rev)
);

CREATE TABLE reaction (                          -- LWW element set
  target_type TEXT NOT NULL CHECK (target_type IN ('message','post')),
  target_id TEXT NOT NULL, emoji TEXT NOT NULL, member_id TEXT NOT NULL,
  added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (target_type, target_id, emoji, member_id)
);

CREATE TABLE mention (                            -- derived from entities on send/edit
  source_type TEXT NOT NULL, source_id TEXT NOT NULL,
  target_type TEXT NOT NULL CHECK (target_type IN ('member','group','account','front')),
  target_id TEXT, PRIMARY KEY (source_type, source_id, target_type, target_id)
);

-- D-131: cross-account receipts are NOT stored. They are computed from this table's account row
-- (reader_member_id = '') plus both accounts' `share_reads` prefs when asked (receipts.rs).
CREATE TABLE read_state (
  channel_id TEXT NOT NULL, account_id TEXT NOT NULL,
  reader_member_id TEXT NOT NULL DEFAULT '',     -- '' = account-level
  last_read_message_id TEXT NOT NULL, last_read_message_at INTEGER NOT NULL,
  -- server only (migration 0004): the parts kept so one op at a time suffices — the furthest
  -- mark after the manual set, and that set (model::read_best / read_effective); state_ok = 0
  -- on rows from before 0004, which are recomputed from the log when next touched
  mark_at INTEGER, mark_id TEXT, mark_hlc TEXT, set_at INTEGER, set_id TEXT, set_hlc TEXT,
  state_ok INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (channel_id, account_id, reader_member_id)
);

CREATE TABLE blob (
  hash TEXT PRIMARY KEY,                          -- sha256 hex
  size INTEGER NOT NULL, mime TEXT NOT NULL,
  stored_at INTEGER NOT NULL, uploaded_by TEXT NOT NULL,
  is_complete INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE blob_holder (                        -- sent a stored blob's bytes again (D-119)
  hash TEXT NOT NULL, account_id TEXT NOT NULL,
  received INTEGER NOT NULL DEFAULT 0, matches INTEGER NOT NULL DEFAULT 1,
  is_complete INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (hash, account_id)
);

CREATE TABLE attachment (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
  blob_hash TEXT NOT NULL, filename TEXT, mime TEXT NOT NULL, size INTEGER NOT NULL,
  width INTEGER, height INTEGER, duration_ms INTEGER,
  waveform TEXT,                                  -- reserved for voice (L1)
  thumb_blob_hash TEXT, alt_text TEXT, is_spoiler INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE item_attachment (
  owner_type TEXT NOT NULL CHECK (owner_type IN ('message','post')),
  owner_id TEXT NOT NULL, attachment_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (owner_type, owner_id, attachment_id)
);

-- Full-text search (server; Android keeps its own FTS table for local scopes).
-- These FTS5 tables store their own content; the projector writes matching rowids explicitly.
CREATE VIRTUAL TABLE message_fts USING fts5(text, cw, tokenize='unicode61 remove_diacritics 2');
CREATE VIRTUAL TABLE post_fts USING fts5(title, text, tags, tokenize='unicode61 remove_diacritics 2');
-- The server projection fills both: message_fts (text, cw) per message, post_fts (title, text,
-- tags joined by spaces) per post; deleted rows drop out. Migration 0007 indexed existing posts.

-- ─── posts, profiles, social ─────────────────────────────────────────
CREATE TABLE post (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, device_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('note','entry')),
  title TEXT, text TEXT NOT NULL, entities TEXT NOT NULL DEFAULT '[]',
  mood TEXT, tags TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(tags)),
  cw TEXT,
  reply_to_id TEXT, quote TEXT, repost_of_id TEXT,
  front_snapshot TEXT,                             -- who was fronting when written (entries JSON)
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  occurred_at INTEGER NOT NULL, tz_offset_min INTEGER NOT NULL, received_at INTEGER NOT NULL,
  sent_offline INTEGER NOT NULL DEFAULT 0,
  revision_count INTEGER NOT NULL DEFAULT 1, edited_at INTEGER, deleted_at INTEGER,
  char_count INTEGER GENERATED ALWAYS AS (length(text)) VIRTUAL,
  clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX post_account_time ON post(account_id, occurred_at);

CREATE TABLE post_author (post_id TEXT NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (post_id, member_id));
CREATE TABLE post_revision (post_id TEXT NOT NULL, rev INTEGER NOT NULL, title TEXT, text TEXT NOT NULL, entities TEXT NOT NULL, edited_at INTEGER NOT NULL, hlc TEXT NOT NULL, PRIMARY KEY (post_id, rev));
-- D-107 / migration 0018 replaces full derived post_revision contents with:
-- post_revision_diff(post_id, rev, delta JSON, edited_at, hlc), primary key (post_id, rev).
-- Rev 0 sets all version fields; later deltas set only changed fields and optionally splice
-- text at UTF-8 byte boundaries. Revisions are reconstructed on the server, never indexed for
-- search or used for blob permissions. The legacy table is emptied transactionally after
-- regeneration from ops. Append-only op payloads and account exports remain intact.

CREATE TABLE highlight (
  profile_member_id TEXT NOT NULL, post_id TEXT NOT NULL,
  added_by_member TEXT NOT NULL, sort_key TEXT,
  added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (profile_member_id, post_id)
);

CREATE TABLE relationship_type (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, inverse_name TEXT,
  is_symmetric INTEGER NOT NULL DEFAULT 0, color TEXT, icon TEXT, deleted_at INTEGER,
  clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE relationship (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
  from_member_id TEXT NOT NULL,
  to_kind TEXT NOT NULL CHECK (to_kind IN ('member','account','external')),
  to_id TEXT,                                      -- member id (any system) or account id
  to_label TEXT,                                   -- for 'external'
  type_id TEXT NOT NULL, note TEXT,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE member_list (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, description TEXT,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}', deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE member_list_item (
  list_id TEXT NOT NULL, member_id TEXT NOT NULL, added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (list_id, member_id)
);

CREATE TABLE feed (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, description TEXT,
  query TEXT NOT NULL,                             -- source text as the user wrote it
  query_ast TEXT NOT NULL CHECK (json_valid(query_ast)),   -- parsed by chorus-core
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

-- ─── privacy, follows, notifications ─────────────────────────────────
CREATE TABLE bucket (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT, color TEXT, sort_key TEXT, ceiling TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(ceiling)), deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}');
CREATE TABLE bucket_assignment (bucket_id TEXT NOT NULL, follower_account_id TEXT NOT NULL, added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (bucket_id, follower_account_id));
-- bucket.icon (migration 0019, D-115): a built-in icon from fixtures/ui_bucket_controls.json or the bucket's own emoji;
--   NULL or anything else reads as the default. bucket.color: #RRGGBB or NULL. Both are presentation only.

CREATE TABLE follow (
  id TEXT PRIMARY KEY,
  follower_account_id TEXT NOT NULL, target_account_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('requested','active','declined','ended','blocked')),
  ceiling TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(ceiling)),   -- set by target (NOTIFICATIONS.md §3)
  prefs TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(prefs)),       -- set by follower (§4)
  created_at INTEGER NOT NULL, clocks TEXT NOT NULL DEFAULT '{}',
  UNIQUE (follower_account_id, target_account_id)
);

CREATE TABLE notification (                        -- server-side delivery queue + history
  id TEXT PRIMARY KEY, recipient_account_id TEXT NOT NULL,
  kind TEXT NOT NULL,                              -- 'switch','digest','mention','dm','reply','follow','review'
  source_op_id TEXT, payload TEXT NOT NULL,        -- rendered, already privacy-filtered
  created_at INTEGER NOT NULL, due_at INTEGER NOT NULL,
  displayed_time INTEGER,                          -- the (fuzzed) time the recipient is shown
  delivered_at INTEGER, collapsed_into TEXT, cancelled_at INTEGER
);
CREATE INDEX notification_due ON notification(due_at) WHERE delivered_at IS NULL AND cancelled_at IS NULL;

-- Follower-visible front state (NOTIFICATIONS.md §5). What a follower may know, and since when.
CREATE TABLE follower_front_view (
  follower_account_id TEXT NOT NULL, target_account_id TEXT NOT NULL,
  front_id TEXT NOT NULL DEFAULT '',               -- '' = Body; else a granted front (D-084, 0014)
  front_name TEXT,                                 -- that front's name as revealed
  entries TEXT NOT NULL,                           -- privacy-filtered snapshot
  displayed_since INTEGER,                         -- fuzzed
  revealed_at INTEGER NOT NULL,                    -- when this state became visible to the follower
  PRIMARY KEY (follower_account_id, target_account_id, front_id)
);
-- follower_front_log also carries front_id ('' = Body); history/stats read Body rows only.

-- ─── custom emoji (server-wide, D-054) ───────────────────────────────
CREATE TABLE custom_emoji (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,                     -- unique among non-deleted (enforced in core + partial index)
  aliases     TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(aliases)),
  category    TEXT,
  blob_hash   TEXT NOT NULL,                     -- 128 px PNG/WebP/GIF
  is_animated INTEGER NOT NULL DEFAULT 0,
  created_by  TEXT NOT NULL,                     -- account id
  created_at  INTEGER NOT NULL,
  deleted_at  INTEGER,
  clocks      TEXT NOT NULL DEFAULT '{}'
);
CREATE UNIQUE INDEX custom_emoji_name ON custom_emoji(name) WHERE deleted_at IS NULL;
-- In text: entity {"type":"custom_emoji","offset":…,"length":…,"emoji_id":"…"} over the `:name:`
-- text (so plain `text` stays readable). In reactions: reaction.emoji = "custom:<emoji_id>".

-- ─── misc ────────────────────────────────────────────────────────────
CREATE TABLE draft (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, context TEXT NOT NULL, authors TEXT, text TEXT NOT NULL, entities TEXT NOT NULL DEFAULT '[]', updated_at INTEGER NOT NULL, clocks TEXT NOT NULL DEFAULT '{}');
CREATE TABLE stage (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, definition TEXT NOT NULL CHECK (json_valid(definition)), created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}');
CREATE TABLE pref (account_id TEXT NOT NULL, device_id TEXT NOT NULL DEFAULT '', key TEXT NOT NULL, value TEXT NOT NULL CHECK (json_valid(value)), hlc TEXT NOT NULL, PRIMARY KEY (account_id, device_id, key));
CREATE TABLE api_token (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, scopes TEXT NOT NULL, created_at INTEGER NOT NULL, last_used_at INTEGER, revoked_at INTEGER);
CREATE TABLE webhook (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, url TEXT NOT NULL, secret TEXT NOT NULL, events TEXT NOT NULL, is_enabled INTEGER NOT NULL DEFAULT 1, last_status INTEGER, last_error TEXT, created_at INTEGER NOT NULL);
CREATE TABLE export_job (                           -- the export bundle (0008, D-068): server state,
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL,     -- not a projection; rebuilds leave it alone
  kind TEXT NOT NULL CHECK (kind IN ('full')),
  status TEXT NOT NULL CHECK (status IN ('queued','running','done','failed','cancelled','expired')),
  phase TEXT, done INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0,
  error TEXT, file_name TEXT, download_key TEXT,     -- the key is the download URL's credential
  created_at INTEGER NOT NULL, finished_at INTEGER, expires_at INTEGER, downloaded_at INTEGER
);
CREATE TABLE server_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);  -- schema_version, instance_id, …
```

### 4.1 Visibility JSON

```json
{"mode": "private"}
{"mode": "buckets", "bucket_ids": ["…"]}
{"mode": "friends"}
{"mode": "followers"}
{"mode": "following"}
{"mode": "server"}
```

`friends` is both ways; `followers` follow this account; `following` are those it follows (D-129).

Message-only extra modes: `{"mode":"members","member_ids":[…]}` (soft in-system),
`{"mode":"system_only"}` (aside in a shared space).

### 4.2 Front entry JSON

The following is the legacy front shape. D-080's named-front model is under implementation;
its current-state JSON contract is documented in [FRONTS.md](FRONTS.md) and
`fixtures/front_system/`. Named presence uses an independent `layer_id`; a layer definition's
numeric display `level` is not a fronting/co-conscious/present semantic role. Migration 0010 adds
rebuildable current-state tables: `front_layer`, `named_front`, `named_front_purpose_config`
(null/legacy versus explicit selections), `named_front_purpose` (selected layer IDs),
`named_front_presence` (ordered own/effective/purpose pools) and `front_system_cache` (versioned
core state and append guards, with an applied-front sequence watermark). Every key includes
`account_id`; `v_named_front_current` exposes confirmed effective presence, excluding deleted
fronts and unavailable recovery fallbacks. This is own-account data, never a follower view.
Startup/backfill and full rebuild regenerate it without rewriting the log. Ingestion validates
and updates this current state atomically; migration 0011 indexes the named-kind existence check
for legacy-only accounts. Runtime projections expose an optional `front_systems` account map.
Named history has no materialized SQL table: owner APIs, feeds and exports (§7.1) fold the
account's own ops on demand through the shared core. See FRONTS.md for the integration boundary.

```json
{"subject_type": "member", "subject_id": "…", "level": "front", "is_primary": true}
```

`entries` arrays are ordered: index = fronting order. `resulting_front` has the same shape.

### 4.3 Terminology JSON

```json
{"member": ["member","members"], "front": ["front","fronting"], "switch": ["switch","switches"],
 "subsystem": ["subsystem","subsystems"], "cocon": "co-con", "present": "present",
 "system": ["system","systems"]}
```

`system` is what the system calls itself ("collective", "household"); `front[1]` is the word for being
in control ("fronting", "steering"). Android's setup (F1.2) and System settings write both, keeping
the other keys.

### 4.4 Channel permissions: the rule (D-047, M5.10)

One rule, `chorus-server/src/perms.rs::can_sql`, is spliced into every server path that decides
what an account may see or do in a space: writes (`perms::write_denied`, from `ingest::accept`),
sync fan-out and catch-up (`visibility::op_visible_to` and the batched `visible_digest`), the REST
message reads, search, blobs, author cards and notification recipients. Before any of it, a
write must stay in its own space (`perms::foreign_space`, for everyone including the owner): the
channel, message or thread parent an op names, and a `channel.create`'s `space_id`, belong to the
op's `space:` scope; and the members a message, reaction or post speaks as (`authors`, segment
`authors`, `member_id`, the envelope's `member_id`) are the author account's own or not yet known
(`ingest::foreign_speaker`); and a message or attachment stays its creator's: no other account
sets an attachment's fields or re-creates an existing id (`ingest::foreign_item`). `can(account, channel,
perm)` holds when both `perm` and `view` resolve to allow:

1. A thread uses its parent message's channel.
2. The space's owner, an `admin`, and any participant of a `dm` space: everything.
3. Otherwise the most specific override that mentions `perm` decides — the account's own
   override, then its role's, then the role `everyone` — and at one level `deny` beats `allow`.
4. With no override: the role's base set. `member`: view, send, react, thread, pin. `read_only`:
   view, react. A custom role (`space.roles[].perms`): its list. `manage` is never in a base set.
5. An account that isn't a present member of the space gets only what an account override
   allows — a **guest**. Allowing a guest `view` gives them the space's scope (`scope_access`,
   `perms::refresh_guest`), but only that channel's ops sync to them (plus `space.create` and
   `space.set`, so they can name it). Removing the last `view` removes the scope.

Writes: `space.*` needs an admin (never in a DM; leaving is your own); `channel.create` needs a
manager (admin, or a DM participant), or `thread` on the parent's channel for a thread; other
`channel.*` need `manage`; `message.send`/`forward` need `send`; `message.pin`/`unpin` need `pin`;
other `message.*` need `view` on your own message and `manage` on someone else's, except
`message.edit`, which only the message's author may send (D-073); `reaction.*`
needs `react`; `read.*` needs `view`. Outside the internal space, a channel whose `member_ids`
name some of the author account's members is a private chat (D-098, `perms::kept_to`; a thread
uses its parent's channel): that account's `message.send`/`forward`, edit segments and
`reaction.*` may only speak as those members; `member_ids` may only name members of accounts in
the space; and a `channel.set` or repeated `channel.create` against an existing channel may change only the author account's own entries in it. Existing channels keep their create-only structural fields (`space_id`, `kind`, `parent_message_id`); ingest checks their stored scope rather than trusting the incoming scope. This
applies to the owner too. A refused op is acked `forbidden` with the reason.
Property-tested against an independent model of these rules in `tests/permissions.rs`.

## 5. What each device stores

| Device | Holds |
| --- | --- |
| Server | Everything. |
| Owner's Android | Full replica of its own `account:` scope (D-043) + joined `space:` scopes + follower views + blobs on demand (thumbnails always). |
| Web PWA | Same scopes as Android; in a browser tab without "keep everything" (CLIENTS §4.3), message history is windowed to what arrived in the last 90 days (SYNC §6.5, D-075) to keep IndexedDB small; older history reads over REST. |

## 6. Analysis views (stable, documented; exported as CSV)

These views are a **public contract**: renaming a column requires a DECISIONS entry. All times are
provided in both UTC ms (`*_at`) and ISO local strings (`*_local`) for spreadsheet users.

| View | Columns (abbrev.) | Answers |
| --- | --- | --- |
| `v_member` | id, account_id, name, display_name, pronouns, color, groups (comma list), is_archived, created_local | Member directory |
| `v_group_member` | group_id, group_name, group_kind, group_path ("Stars / Inner"), member_id, member_name | Flattened membership incl. subsystem path |
| `v_switch` | id, occurred_at, occurred_local, kind, fronters (names, ordered), primary_name, note, was_offline, device_name | Switch log as you'd read it |
| `v_front_interval` | id, subject_type, subject_id, subject_name, level, is_primary, position, start_at, end_at, start_local, end_local, duration_s (ongoing → now) | **The** table for front analytics |
| `v_current_front` | subject_name, level, is_primary, position, since_local, duration_s | Who's here now |
| `v_front_daily` | day, subject_name, level, seconds, hours, as_primary_seconds | Day totals, local days |
| `v_cofront_pair` | a_name, b_name, overlap_s, overlap_count | Co-front graph edges |
| `v_message_segment` | message_id, idx, authors (names), text, char_count | Who said which part of a segmented message |
| `v_message` | id, space_name, channel_name, occurred_local, authors (names), author_count, text, char_count, word_count, has_attachment, reply_to_id, is_edited, is_pinned, sent_offline | Message analytics |
| `v_post` | id, kind, authors, title, text, mood, tags, occurred_local, reply_count, reaction_count | Journal analytics |
| `v_reaction` | target, emoji (custom ones as `:name:`), member_name, target_author_names | Who reacts to whom |
| `v_emoji_usage` | emoji, is_custom, uses_in_text, uses_as_reaction, by_member | Emoji stats |
| `v_mention` | source, author_names, mentioned_name | Mention graph |
| `v_relationship` | from_name, type, to_name, mutual | Relationship graph |

`word_count` uses a SQLite function registered by the server (`chorus_word_count`) and a pure-SQL
approximation in exported copies.

### 6.1 Example queries

```sql
-- Front hours per member in August 2026 (local days)
SELECT subject_name, round(sum(seconds)/3600.0, 1) AS hours
FROM v_front_daily WHERE day BETWEEN '2026-08-01' AND '2026-08-31' AND level = 'front'
GROUP BY subject_name ORDER BY hours DESC;

-- Switches per hour of day
SELECT strftime('%H', occurred_local) AS hour, count(*) FROM v_switch GROUP BY hour;
```

## 7. Exports

| Export | Format | Notes |
| --- | --- | --- |
| Full backup | `chorus-<account>-<date>.zip` = `ops.jsonl` + `blobs/` + `manifest.json` | Round-trips: importable into a fresh server. |
| Tidy data | `csv/v_*.csv` (all §6 views), UTF-8, header row, ISO times | For spreadsheets / pandas / R. |
| SQLite copy | `chorus-<account>.sqlite` with projected tables + views, filtered to the account | Open in DuckDB/Datasette. |
| PluralKit-compatible | `pluralkit.json` (members, groups, switches) | Escape hatch. |

Exports run as background jobs; the app shows progress and notifies when ready.

### 7.1 Current account-scoped direct exports (M10.3)

`GET /api/v1/exports/ops.jsonl` contains applied ops **authored by the requesting account** in
server sequence order, including its authored ops in shared spaces. It does not include another
account's ops. `GET /api/v1/exports/csv/<name>` returns the nine tidy tables below. All nine
are limited to the account; messages and posts omit rows with a deletion tombstone. CSV is UTF-8 with
CRLF row endings, a header row, and RFC 4180 escaping. Time numbers are UTC milliseconds;
`*_local` values are ISO wall times (switch/message/post time uses the stored offset; member and
group creation uses the server's local timezone in CSV, or the reader's local timezone in the
SQLite views, because those rows have no stored offset).

| CSV name | Columns, in order |
| --- | --- |
| `members` | id, name, display_name, pronouns, color, is_archived, created_at, created_local |
| `groups` | id, kind, parent_id, name, color, created_at, created_local |
| `switches` | id, occurred_at, occurred_local, kind, entries, resulting_front, note, was_offline, device_id |
| `front_intervals` | id, subject_type, subject_id, level, is_primary, position, start_at, end_at, start_local, end_local, duration_s |
| `front_daily` | day, subject_type, subject_id, level, seconds, hours, as_primary_seconds |
| `messages` | id, channel_id, occurred_at, occurred_local, text, cw, reply_to_id, revision_count, sent_offline |
| `posts` | id, kind, title, text, mood, occurred_at, occurred_local, reply_to_id, revision_count |
| `named_switches` | front_id, id, occurred_at, occurred_local, kind, entries, resulting_front, note, is_retracted, is_history_only, device_id |
| `named_front_intervals` | front_id, id, subject_type, subject_id, layer_id, is_primary, position, start_at, end_at, start_local, end_local, duration_s |

The two `named_*` tables (D-080) are folded from the account's own ops by the shared core rather
than read from projection tables: each front's own saved records and intervals (deleted fronts
included), keyed by front and exact layer ID. Effective/inherited history is derived from these
and the follow settings, so it is not exported. `entries`/`resulting_front` are JSON presence
lists with `layer_id`. An account without named fronts gets header-only files; its Body history
stays in the legacy tables.

`GET /api/v1/exports/account.sqlite` builds a fresh database from that account's record, devices
and applied authored ops, then rebuilds projections. It includes `v_member`, `v_group`,
`v_switch`, `v_front_interval`, `v_front_daily`, `v_message`, and `v_post` views and `v_named_switch` /
`v_named_front_interval` tables (integer-typed time/flag columns; empty values are NULL) with the CSV
columns above. No full-server pages are copied into the file. The CLI uses
`chorus-server export --account ID --kind full|csv|sqlite [--to DIR]`; `full` currently writes
`ops.jsonl`, `csv` writes nine files, and `sqlite` writes `account.sqlite`. The full backup
with files is the export bundle job (`POST /exports {kind:"full"}`, API.md §4, D-068):
`README.txt`, `ops.jsonl`, `csv/`, `blobs/<sha256>` (files the account's own ops point at) and
`manifest.json`, as a stored ZIP built in the background (`export_job`).

**Import** (R27, `chorus-server import-account`, `import.rs`) takes that bundle into another
server. It checks the manifest, `ops.jsonl` against its hash and count, every op (valid as
history, written by this account, stamped), and every file against its hash and size. Then it
refuses on clashes: the account id, the handle (unless mapped with `--handle`), any op id.
Nothing is half imported. The ops keep their ids, authors, devices and times, like restore
pushes (SYNC.md §7.3), and are projected in their old order. What the bundle can't carry:
- **Other accounts.** Its ops in spaces other accounts own are imported but stay unreadable
  until the owner's account is imported too. That owner's `space.join` then grants access, so
  moving a whole group one account at a time converges.
- **Follows** naming absent accounts wait the same way.
- **Server-scope ops** (custom emoji) stay with the old server.
- **Devices.** The import prints a one-time device invite instead. The round trip is tested
  (`tests/import.rs`): the same ops, CSV tables and files.

D-114 search adds read-only scopes and visible context, without changing sync delivery, the op
catalogue, projections or schema. Bookmarks remain ordinary account-scoped `pref.set` values;
a bookmark never grants post, author or blob access. Current-front search pages are ephemeral.

UI11b membership hardening: new `channel.create`/`channel.set` writes accept absent or null
`member_ids`, or an array containing only strings (including an empty array). Projection/replay
still accepts legacy ops without rewriting them. Current-front reads treat a non-array or mixed
persisted membership as unavailable, including through thread ancestors, context and known-hash
blob references; clients retain an explicit invalid flag rather than interpreting it as an empty
unrestricted list. SQL NULL remains the absent membership value.


UI11b/main integration (D-110 + D-114): current search validates the entire stored channel
membership array before any expansion, then expands its own listed members with the same
`perms::with_parts` relation used by `kept_to`, including internal member chats. Only present,
active members of a live linked subsystem group are added, recursively and never upward. A
listed member's ownership survives their tombstone, so deleting a listed member cannot turn a
restricted external chat into an unrestricted one. Explicit list entries keep their existing
meaning. The recorded Body front and message-level `visibility.member_ids` are never expanded.
Canonical point reads, SQL pre-limit selection, context, inherited thread parents and blob
reads use this relation. At integrated main `0ac29cc`, archived channels and their descendants
remain excluded from current search; forthcoming D-111 archive behavior is outside this merge.
