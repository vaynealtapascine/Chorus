# Chorus — sync

Offline-first. Every client can do everything it is allowed to do while disconnected, for as long as
it likes, and converges cleanly when it reconnects. The server can also be off (the PC is asleep)
while phones keep working.

---

## 1. Invariants (tests enforce these)

1. **No lost writes.** An op created on any device is eventually stored on the server, unless the
   server rejects it for permission/validation, in which case the device shows it in *Sync issues*.
2. **Idempotent.** Delivering the same op any number of times, in any order, has the same effect as
   once. The op `id` is the idempotency key.
3. **Order-independent projection.** Projected state is a deterministic function of the *set* of
   ops, not of arrival order. (This is what makes resync boring.)
4. **Convergence.** Two replicas holding the same set of ops for a scope have byte-identical
   projections for that scope. Checked with digests (§6.4).
5. **Local-first latency.** A local action updates the local projection before any network I/O.
6. **Privacy at the boundary.** A device never receives an op or view it may not read (§4.2).

## 2. Topology

```
 Android (full replica of own account)  ─┐
 Web PWA (windowed replica)             ─┼─ WebSocket /api/v1/sync ─ chorus-server ─ SQLite
 Other accounts' devices                ─┘                               │
                                                                        ntfy (UnifiedPush tickles)
```

Star topology; devices never sync peer-to-peer. The server is the only place ops get a `seq`.

## 3. Time

### 3.1 HLC

Every op carries a hybrid logical clock used for LWW tie-breaks and deterministic ordering.

- Encoding: `"<physical ms, 12 hex>-<counter, 4 hex>-<device short_id, 8 hex>"`, e.g.
  `01a0c27d1e3f-0002-a1b2c3d4`. String comparison = clock order; the device id makes it total.
- Send/create: `pt = max(prev.pt, now)`; `c = (pt == prev.pt) ? prev.c + 1 : 0`.
- Receive: standard HLC merge with the remote HLC, so a device's clocks never go backwards even if
  its wall clock does.
- Guard: if a remote HLC is more than 24 h ahead of local `now`, accept the op but don't advance the
  local HLC past `now + 1 min` (a single broken clock must not poison everyone).

HLC is for *ordering writes*. It is **not** the time shown to humans.

### 3.2 Human time: `occurred_at`

Each op records:

| Field | Meaning |
| --- | --- |
| `device_at` | device wall clock at creation |
| `mono` | monotonic clock at creation (Android `SystemClock.elapsedRealtime()`; web omits) |
| `boot_id` | Android boot count/ID (`Settings.Global.BOOT_COUNT`) so `mono` is comparable |
| `tz_offset_min` | device UTC offset at creation |
| `time_source` | `auto` (now) or `user` (the user typed a time, e.g. "switched at 13:40") |

On every handshake and every ~60 s ping, the device and server exchange a clock sample
(NTP-style: `t0` device send, `t1` server, `t2` device receive; `offset = t1 − (t0 + t2)/2`). The
device stores `(offset, wall, mono, boot_id)` of its latest sample.

The server computes `occurred_at` for each incoming op:

1. `time_source = user` → use the user's time as-is (they typed the real time).
2. Op has `mono` and its `boot_id` equals the current session's boot id →
   `occurred_at = sample.server_time − (sample.mono − op.mono)`. Immune to wall-clock changes.
3. Else → `occurred_at = device_at + offset`, where `offset` is the sample taken on the connection
   that uploaded the op (the best estimate we have). If `|offset| < 2 s`, use `device_at` untouched.
4. Clamp to `≤ received_at`. If the result is > 1 year from `received_at`, use `received_at` and
   mark the op `time_suspect` (shown as a small "time uncertain" note).

The ack returns `occurred_at`; the client rewrites its local row, so all replicas agree. Clients
apply the same rules locally *before* the ack when they already have a fresh sample, so the jump
is usually zero.

## 4. Scopes, cursors, visibility

### 4.1 Scopes

Every op belongs to exactly one scope:

| Scope | Contains | Replicated to |
| --- | --- | --- |
| `account:<id>` | members, groups, states, fields, front ops, posts, relationships, lists, feeds, buckets, follows (as target), prefs, drafts, stages | that account's devices only |
| `space:<id>` | channels, messages, reactions, read marks, attachments of that space (incl. the internal space) | devices of accounts that are present members of the space |

Followers never receive `account:` ops of others. They receive **views** (§4.3).

### 4.2 Filtering inside a scope

`chorus-core::visibility::can_read(account, op, state)` is the one function deciding delivery.
Notable filters inside `space:` scopes: `system_only` messages go only to the author's account;
reactions/edits to such messages likewise. Because different accounts see different subsets, the
digest (§6.4) is computed per *(scope, account)*.

### 4.3 Views (read-only, for other accounts)

For content owned by another account (their profile, posts, member directory, follower front
state), the server sends **view records**: already-privacy-filtered projected rows.

- Kinds: `member`, `group`, `post`, `relationship`, `list`, `feed`, `field_def`, `front`.
- Each has `(kind, id, view_version, row | null)`; `null` = no longer visible (evict).
- One cursor per account: `view_seq`. The server keeps a `view_log(account_id, view_seq, kind, id)`
  and regenerates rows on demand.
- `front` views come from `follower_front_view` only — they already include delay/fuzz
  (NOTIFICATIONS.md §5). There is no path to the raw front for a follower.

### 4.4 Cursors

Each device stores `cursor[scope] = highest seq applied` and `view_seq`. Seq is global on the
server; a scope's ops are a subsequence. New scope (joined a space) → cursor 0 → snapshot (§6.3).

## 5. Merge rules

All merge logic is in `chorus-core` and runs identically on server and clients.

### 5.1 LWW-F (field-level last-writer-wins)

Row has `clocks: {field: hlc}`. An op setting field `f` with `hlc h` applies iff `h > clocks[f]`.
Two offline edits to *different* fields both survive; to the *same* field, the later HLC wins. Row
creation is itself an op; a `set` for a row that doesn't exist yet is kept in the log and applies
when the create arrives (projection is over the set of ops, §1.3).

Text fields (bios, descriptions) are LWW as a whole — no character-level merge. The loser is not
lost: the client that lost shows a one-time "your edit to Kai's bio was replaced by a newer edit
from Desk PC · View" notice, and the old text is in the op log.

### 5.2 SET (LWW element set)

Membership-like relations (group members, reactions, bucket assignment, list items, highlights,
space membership) keep `added_hlc` / `removed_hlc` per element; present iff
`added_hlc > removed_hlc`. Add wins over a concurrent remove only if it is later.

### 5.3 Groups tree cycle guard

`parent_id` is LWW. Two offline moves can create a cycle (A under B on one device, B under A on
another). Projection computes `effective_parent_id`: for every cycle in the raw parent graph, the
edge whose `parent_id` clock is **highest** is ignored (that group shows at the root). Deterministic
regardless of order. The UI shows a gentle "Stars was moved to the top level because of
conflicting moves" notice.

### 5.4 APP (append-only objects)

Messages, posts, attachments, switches: the create op creates the row keyed by op/entity id.
Edits append a revision; the displayed revision is the one with the highest HLC (`revision_count`
counts all). Delete is LWW on `deleted_at`; an edit after a delete doesn't resurrect.

Message order in a channel: `(occurred_at, id)`. Offline messages therefore slot in at their
original time with `sent_offline = 1` (D-039). Unread computation uses `received_at`, not position,
so late-arriving old messages still count as unread and the channel shows a "3 new messages above"
jump pill.

### 5.5 Read marks

`read.mark` moves forward only: the stored mark is the max by message order. Never backwards
(unless the user explicitly "mark unread", which is `read.set` — LWW).

### 5.6 TL — the front timeline fold

Input: all front ops of an account. Steps (in `chorus-core::front`):

1. Drop ops targeted by a `front.retract` (retracts themselves are LWW: a retract can be
   un-retracted by `front.unretract` in Advanced).
2. Apply `front.amend`s: for each target, the amend with the highest HLC defines its
   `occurred_at` / `entries` / `note`.
3. Sort by `(occurred_at, hlc, id)`.
4. Fold from an empty front:
   - `switch`: state := entries (normalized).
   - `add`: if subject present → update its level/primary; else insert at `position` (default
     end of its level group).
   - `remove`: delete subject if present (no-op otherwise).
   - `update`: modify level/primary/position of a present subject (no-op if absent).
   - Normalize after each step: stable order `front` entries, then `cocon`, then `present`,
     preserving relative order; at most one `is_primary` (keep the first; if none and there are
     `front` entries, none is primary — the UI then uses the first as default speaker).
5. Each step yields a snapshot → `switch.resulting_front`.
6. Diff consecutive snapshots → `front_interval` rows. A subject's interval continues while its
   `(level, is_primary)` is unchanged; order changes don't split intervals.
7. `front_daily` recomputed for the local days touched.

Incremental: when an op lands at time `t`, recompute from the last snapshot strictly before `t`.
Back-dated inserts typically touch a handful of switches.

### 5.7 Concurrent switch review (D-037)

Every op carries `seen_seq`: the device's cursor for that scope when it created the op. Two front
ops A and B are **concurrent** iff they come from different devices and neither had seen the
other: `A.seq > B.seen_seq && B.seq > A.seen_seq` (ops not yet acked count as unseen).

A `front_review` is created when concurrent A and B satisfy `|A.occurred_at − B.occurred_at| ≤
review_window` (default 120 s, Advanced 0–30 min) **and** their `resulting_front`s differ. Nothing
is dropped: the timeline already contains both. The review card offers keep both / keep A / keep
B / merge (union of entries at the later time, as a new `front.switch`). Resolutions are ordinary
ops (`front.retract`, `front.switch`, `front.review_resolve`).

Reviews are computed by the server (it knows `seq`) and sync down as rows.

## 6. Protocol

### 6.1 Transport

- `wss://chorus.<domain>/api/v1/sync`, JSON text frames, permessage-deflate. One connection per
  device (web: one per browser profile, elected with the Web Locks API; other tabs talk to the
  leader over `BroadcastChannel`).
- Auth: `hello` carries a session token; if expired, the server replies `auth_challenge` and the
  device signs `nonce‖device_id‖server_instance_id` with its key (API.md §2).
- Fallback when WebSockets are blocked: `POST /api/v1/sync/push`, `GET /api/v1/sync/pull` long
  poll with the same payloads.

### 6.2 Frames

```jsonc
// C→S, first frame
{"t":"hello","device_id":"…","token":"…","epoch":"<server instance>:<epoch>",
 "cursors":{"account:…":1234,"space:…":998},"view_seq":77,
 "digests":{"account:…":{"count":1234,"xor":"9f…"}},
 "clock":{"wall":1790000000000,"mono":81234567,"boot_id":"41"},
 "core":"1.2.0","app":"android 1.0.3","outbox":12}

// S→C
{"t":"welcome","server_time":1790000000123,"epoch":"…","offset_ms":-340,
 "scopes":["account:…","space:…"],"snapshot":["space:new…"],"core_min":"1.0.0",
 "reconcile":false}

// C→S: outbox, in creation order, ≤ 500 ops or 1 MB per batch
{"t":"push","batch":"b-17","ops":[{…op envelope…}]}

// S→C
{"t":"ack","batch":"b-17","results":[
  {"id":"…","seq":1250,"occurred_at":1789999000000},
  {"id":"…","error":{"code":"forbidden","retry":false,"message":"…"}}]}

// S→C: catch-up and live, per scope, seq-ordered
{"t":"ops","scope":"space:…","ops":[{…,"seq":1251}],"to":1251}

// S→C: views
{"t":"views","records":[{"kind":"front","id":"acct…","ver":9,"row":{…}}],"to":80}

// S→C: snapshot for a scope (chunked)
{"t":"snap","scope":"space:…","chunk":1,"of":4,"tables":{"channel":[…],"message":[…]},"at_seq":1251}

// both directions, every ~60 s
{"t":"ping","clock":{…}}  /  {"t":"pong","server_time":…}

// S→C when membership changes
{"t":"scope","add":["space:…"],"remove":["space:…"]}
```

### 6.3 Session flow

1. **hello → welcome.** Server checks epoch (§7.3), computes offset, lists granted scopes.
2. **Push outbox** in creation order while the server streams catch-up. Batches are pipelined up to
   4 in flight. Each ack assigns `seq`/`occurred_at`; rejected ops move to *Sync issues* and are
   reverted locally (§6.5).
3. **Catch-up**: for each scope, server sends ops with `seq > cursor`, oldest first, in pages of
   1 000. If a scope's gap is larger than `snapshot_threshold` (default 20 000 ops) or the cursor is
   0, it sends a snapshot instead, then ops after `at_seq`.
4. **Digest check** after catch-up (§6.4); mismatch → re-snapshot that scope (outbox preserved).
5. **Live**: new ops are pushed as they're accepted (fan-out to every connected device that can
   read them, including other devices of the same account).

### 6.4 Digests

Per (scope, account): `count` and `xor` = XOR of `sha256(op.id)` truncated to 128 bits over all
applied, readable ops. Order-independent and incrementally maintainable on both sides. If counts
and XORs match, invariant 3 guarantees identical projections. A **full projection hash** (sha256
of canonical JSON rows) is available in Developer settings for debugging.

### 6.5 Client engine

Local tables mirror the server's (`op` with `seq` nullable for pending ops, plus projections).

```
user action
  → core builds op (id, hlc, device_at, mono, seen_seq…)
  → local transaction: insert op (pending) + apply to projection
  → UI updates (≤ budget)
  → outbox notifies sync worker
```

- **Apply**: `core.apply(op, state)` for the entity; for TL ops, `core.front.refold(from_t)`.
- **Revert** (rejected op or snapshot replacement): `core.reproject(entity_id, ops_for_entity)`
  rebuilds that entity from its remaining ops. This is the generic undo; there is no hand-written
  inverse per op.
- **Duplicates**: incoming op whose id is already local → set its `seq`, no re-apply.
- **Backoff**: reconnect immediately on network change (Android `ConnectivityManager` callback,
  web `online` event) and on a push tickle; otherwise exponential 1 s → 5 min with ±30 % jitter.
- **Android background**: WorkManager unique work `sync` with `NetworkType.CONNECTED`, expedited
  when the outbox is non-empty; the widget's switch op enqueues it.
- **Windowed web replica**: messages older than the window are evicted locally (not ops of the
  account scope, which are small); scrolling up past the window fetches pages over REST.

## 7. Failure scenarios

| Scenario | Behaviour |
| --- | --- |
| Phone offline for days | Works normally; ops queue; on reconnect pushes (pipelined), pulls, digest check. Budget: 5 000 ops ≤ 10 s. |
| PC/server off | All clients offline-mode; banner "Server asleep · changes saved on this device". Notifications for others resume when it's back (delay rules applied from real switch time, never "catching up" with a flood — see NOTIFICATIONS.md §6). |
| Both offline, both switch | Both kept, merged by real time; review card if concurrent and close (§5.7). |
| Phone clock wrong by hours | Corrected via mono/boot or offset (§3.2); HLC guard prevents poisoning. |
| Phone rebooted while offline | `boot_id` differs → offset method; accuracy ≈ drift since last sample. |
| Connection drops mid-batch | Unacked ops stay pending, re-sent; server dedupes by id. |
| Server crashes mid-ingest | Each batch is one SQLite transaction (op insert + projection); either fully applied or not. |
| Op rejected (e.g. lost permission while offline) | Shown in *Sync issues* with reason; local state reverted; user can copy text of a rejected message. |
| New device joins | Snapshot of each scope + views, then live. |
| Newer client, older server | Server stores unknown kinds; if `core_min` > client core, client shows "Update Chorus" and stays read-only for sync (keeps queuing locally). |
| Device revoked | Server closes the socket with `revoked`; device wipes local data after telling the user (Advanced: keep an export first). |
| Server restored from an older backup | See §7.3. |

### 7.3 Epochs and restore

`server_meta` stores `instance_id` and `epoch`. Restoring a backup bumps `epoch`. A device whose
`hello.epoch` differs (or whose cursor exceeds the server's max seq) enters **reconcile**:

1. Server replies `welcome` with `reconcile: true` and its max seq per scope.
2. Device re-pushes every op it holds for those scopes whose local seq is above the server's max
   (or null). The server accepts unknown ids as new ops (new seqs); known ids are no-ops.
3. Device then re-snapshots. Pending outbox is kept throughout.

This is how "phone as full replica" (D-043) restores data written after the last backup. A CLI
`chorus-server reconcile-status` shows which devices have reconciled since the restore.

## 8. Attachments and blobs

- Blobs are content-addressed (`sha256`). Upload: `HEAD /api/v1/blobs/<hash>` → missing/partial
  → `PUT` chunks with `Content-Range` (resumable). Client uploads the blob *before* pushing the
  `attachment.create` op when online; when offline both queue, blob first.
- An op may reference a blob the server doesn't have yet (uploaded from a device that went offline
  mid-upload): viewers see a placeholder "Still uploading from Juniper's phone".
- Thumbnails (≤ 480 px WebP) are generated by the uploading client and uploaded as their own blob,
  so viewers never need the original to render a list.
- Download rules (Advanced): originals on Wi-Fi only / always / never; thumbnails always.

## 9. Testing the sync (required, M1.9 + M2)

### 9.1 Conformance fixtures

`fixtures/<area>/<case>.json`:

```json
{
  "description": "add co-fronter back-dated before a switch",
  "ops": [ { "…op envelope with occurred_at…" } ],
  "expect": { "current_front": [ … ], "front_interval": [ … ], "reviews": [] }
}
```

Areas: `hlc`, `time`, `lww`, `set`, `groups_cycle`, `front`, `review`, `text_entities`,
`speaker_parse`, `feed_query`, `visibility`, `digest`. Rust runs all of them in CI. Any non-Rust
reimplementation must run them too.

### 9.2 Convergence simulator

A deterministic, seeded simulator in `chorus-core` tests (proptest):

- N devices (2–6) across 1–3 accounts, one server model.
- Random ops (all kinds), random wall-clock skew (±3 h) and drift, random reboots.
- Random network: partitions, drops mid-batch, duplicated and reordered delivery, server restarts,
  one "restore from backup" event.
- At quiescence assert: every device's projection for each scope it holds == server projection
  == projection built from the server log sorted by seq **and** by a random shuffle; digests equal;
  no op lost; each rejected op has a reason.

Run with 10 000 seeds in CI (fast mode 500). A failing seed is written to `fixtures/regressions/`.

### 9.3 End-to-end chaos test

An integration test starts the real server binary, spins up headless clients (Rust test client
using the same core), and kills/restarts the server and sockets at random points. Same assertions.
