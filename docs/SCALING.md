# Chorus — scaling, custody and decentralisation (proposal)

**Status: a proposal, not a spec.** Nothing here is decided or built. It records an architecture
review asked for by the owner on 2026-11-11 (claude-fable-5.2), covering four questions: what has
to change to host a few hundred users, then a few thousand; how to run the VPS; how the operator
could be made unable to read users' data; and how Chorus could become decentralised.

The findings come from reading the specs and the server code at `19fbac6`. **Nothing was load
tested**, and the capacity estimates are estimates. Line numbers are as of that commit; function
names are given so they can be found after the files move.

Anything adopted from this document needs its own `D-xxx` entry. §9 lists the decisions that are
the owner's.

---

## 1. Summary

- **Keep the stack.** One Rust binary on SQLite carries a few hundred users on a small VPS, and
  probably low thousands on a larger one. No Postgres, replicas or containers.
- **Fix six paths in the server first** (§2). They do work proportional to total history or to
  every connected device, all behind one database lock.
- **Operations** (§3): offsite backups, blob quotas and operator tooling matter more than
  capacity.
- **Encryption** (§4): disk encryption does not stop the operator reading data. Client-side
  encryption of content does, with two stated limits.
- **Sharding** (§5): not needed at a few thousand users. Op-log growth is the real issue.
- **Device caching** (§6): helps for cross-account reads only; the design already exists as
  SYNC §4.3 "views", which was never built.
- **Decentralisation** (§7): federate small servers. Not peer-to-peer, and not Matrix-style
  replicated rooms.

All four questions point the same way: the server keeps structure (ids, scopes, times,
permissions) and devices take on content and reading.

## 2. Server hot paths

The live server held 2,571 ops on 2026-11-06, so none of this is felt today. Each row below grows
with users, history or connected devices.

The root is one connection behind one lock: `Shared.db: Mutex<Connection>`
(`crates/chorus-server/src/app.rs:57`). DECISIONS §Versions already says "revisit with a writer
thread + read pool if it contends".

| Where | What happens | Change |
| --- | --- | --- |
| `app.rs:57`, about 109 `s.db()` call sites | Every REST read, blob check, push, catch-up and notifier tick waits on one lock, taken on async worker threads (only 5 `spawn_blocking` uses) | One writer connection plus a pool of read-only WAL connections, behind a `Db` handle with `read(..)` and `write(..)` |
| `visibility::visible_digest_in` (`visibility.rs:397`), `oplog::digest` (`oplog.rs:158`) | Every connect recomputes each scope's digest by walking that scope's whole op log. Cost is history × reconnects, and phones reconnect constantly | Keep digests incrementally per (scope, account), invalidated by the op kinds that already trigger a recheck |
| `fan_out` (`app.rs:2447`) | Every push loops over every connected device, with a `scopes_of` query per connected account and an `op_visible_to` query per (device, op) | Index peers by scope; recompute scopes only when a batch changes them; decide visibility once per account |
| `run_socket` (`app.rs:2211`), `hello` (`app.rs:2356`) | A new device's whole catch-up is built into an unbounded queue while the lock is held, so every other request waits and memory is unbounded | Bounded channel; page the catch-up from a read snapshot; take the lock only for the tail and the peer registration |
| `get_blob` (`blobs.rs:425`) | A download reads the whole file (up to `max_blob_mb`, 100 MB) into memory with blocking IO | Stream the file |
| `run_notifier` (`app.rs:1975`) | Pushes are sent one at a time inside the 5 s tick; one slow endpoint delays the rest | Bounded concurrency with a timeout |

The incremental digest is the one to design carefully. An op's visibility can change after the
fact (a public send makes earlier edits readable, `backfill_for_public_send`), so the cache needs
checking against a full recompute in the chaos test (SYNC §9.3).

**Measure before changing anything.** OPS §7 lists an optional `/metrics`, but nothing in the
server implements it. Wanted: lock wait and hold time, connected peers, catch-up duration, socket
queue depth, push latency. `tests/perf.rs` covers only ingest and rebuild; add a many-devices
load mode to the chaos harness.

**Structure.** `app.rs` is 2,618 lines holding the router, every handler, the sync socket,
fan-out and the background loops. The lock work is the moment to split it into sync, HTTP and
jobs modules, since every `s.db()` call site is touched anyway.

## 3. Running the VPS

- **Backups are on the same disk as the data** (D-043: no offsite in v1). At hundreds of users
  that should be superseded: Litestream for the database, or restic of `backups/` and the blobs
  to object storage. Encrypt them.
- **Disk is the likeliest outage.** Blobs will dominate. Add per-account quotas and a disk alarm.
  Later, move blobs to S3-compatible storage behind the same access-checked endpoint.
- **No high availability needed.** Clients are offline-first, so a restart or a short outage is a
  non-event.
- **File descriptors.** A few thousand users with two or three devices each is several thousand
  sockets; check the systemd unit's `LimitNOFILE` and Caddy's limits before then.
- **Operator role.** With no end-to-end encryption, the operator can read every user's fronting
  history, messages and private journals. Hosting other people needs account suspension, abuse
  handling and a plain statement of that custody. This is the strongest argument for §4 and §7.
- **Server-wide features that stop fitting socially** as the instance grows: the single custom
  emoji set (D-054), the "Everyone here" audience, and one handle namespace.

## 4. Encryption: an operator who can't read the data

### 4.1 What does and doesn't work

Encrypting the server's disk or database protects against a stolen disk or a leaked backup. It
does not stop the operator: the running server holds the key. It is still worth doing for
backups.

What stops the operator is encryption on the devices, with keys the server never has. That
reverses D-033 ("no E2E") and SPEC's v1 exclusions.

### 4.2 Recommended shape: encrypt content, leave structure plain

The server is not a dumb relay. It validates ownership at ingest, resolves permissions, schedules
delayed and fuzzed reveals, tallies polls and computes digests. All of that needs structure, not
content.

- **Stays plain:** ids, op kinds, scopes, times, permissions, handles (they are public
  identifiers and must be unique).
- **Encrypted:** message and post text, names, bios, field values, notes, poll option labels,
  files. The per-kind field list in `crates/chorus-core/src/op.rs` (`KindSpec.fields`) is the
  natural place to mark which fields are sealed.
- **Still visible to the operator:** who talks to whom and when, sizes, and switch times against
  opaque member ids.

Existing pieces to build on: every device has a P-256 keypair (`auth.rs`); push already uses
ECDH, HKDF and AES-GCM in both Rust and the JVM (D-059); `chorus-core` is shared by every client,
so the crypto can live there with randomness passed in.

### 4.3 Build order

1. **Account-private data** under one account key: private entries, asides, the internal space,
   prefs, private profile parts. This is L5, widened. A new device receives the key wrapped to
   its device public key during Link a device.
2. **Shared data** under per-group keys: DMs, shared spaces, post audiences (a key per bucket or
   audience). Keys rotate when someone is removed. This is the largest piece.
3. **Fronting names.** The server keeps computing follower views on opaque member ids, so the
   NOTIFICATIONS §5 invariant stays enforced where it is today. The follower's device resolves
   names from member cards it holds under an audience key.

### 4.4 What moves to the device

- Search (own-replica search is already local; server FTS over content goes away).
- Notification text. The server composes it today with member names
  (`notifier::describe`, `notifier.rs:561`); devices would render it from ids.
- Post previews and edit-history diffs (D-107), which are server-derived today.
- Exports (CSV, JSONL, SQLite). The server could only export ciphertext.
- Text filters in feeds. Structural filters such as `fronting:` can stay on the server.
- API tokens, webhooks and the SSE stream would deliver ciphertext, unless the consumer holds a
  key.

### 4.5 Limits

- **Recovery.** Losing every device and the recovery key loses the data. A recovery key has to be
  mandatory, or this breaks "never lose user data". It must not be escrowed with the operator.
- **The operator ships the client.** The web app is served from the server, and Android updates
  come from it signed with the debug key (D-060). The honest claim is "can't read the database or
  the backups", not "can't ever": reading would take an active, detectable change to the client.
  Release-signed APKs from GitHub (D-072) narrow this on Android; the web cannot fully close it.
- **Revoked devices** have already seen the account key. Revoking has to rotate it.

## 5. A few thousand users: sharding and growth

**Sharding is not needed.** The ingest budget is 2,000 ops/s (SPEC §9), far above what a few
thousand people produce. With §2 fixed, one larger VPS should carry low thousands. That is an
estimate until the load test exists.

**Op-log growth is the real issue.** Read marks (`read.mark`, `read.set`) and prefs (`pref.set`)
are permanent ops, and per-member read tracking multiplies them. Nothing compacts them: the
`superseded` status exists in the `op` table's schema but no server code uses it. A long log
slows the nightly backup, the pre-migration backup and `rebuild` (budget: 1M ops in 60 s).
Compacting superseded read marks would bend the append-only rule, so it needs a decision.

**If a split is ever needed, the shard key exists: the scope.** Every op belongs to one scope and
a scope's visibility depends only on its own ops. The shape would be a small directory database
(accounts, devices, sessions, handles, `scope_access`, the notification queue, the blob index)
plus one database per account and per space. Costs:

- Cross-account reads lose their single query: the friends' timeline, and the audience checks in
  `posts::readable_sql` that join another account's follows and buckets.
- Cross-scope writes lose their single transaction: poll tallies (D-086) and `follow.set_prefs`
  (D-066).

Not recommended. Three federated homes of 1,000 users (§7) is the same split with no new
mechanism.

## 6. Caching on devices

Own data is already cached: every device is an op replica of its scopes, and blobs are served
`immutable`.

The fresh reads are the cross-account ones. In the web app they are the calls to `/accounts/…`
(about 10 call sites), `/follows` (9) and `/posts` (4): friend profiles, member directories,
follower views and friends' posts. PROGRESS notes friends' posts aren't kept offline. The server
sends no ETags on any of them.

- **The fix is already designed.** SYNC §4.3 "views": the server pushes privacy-filtered records
  over the sync socket with a `view_seq` cursor, the device stores them, and a null record evicts
  one. D-086 notes it was never built. Build that rather than ad-hoc HTTP caching: it carries
  revocation.
- **Interim:** ETags with 304 answers on those reads, and one batched request for Home in place
  of one per followed account.
- **Privacy.** A cached view can only be stale, never earlier or fuller than the server allowed.
  Something hidden later stays on an offline device until it next syncs, as chat already does.
- **What this does not fix:** the per-reconnect digest work in §2, which is the larger cost.
- **With §4** the device has to hold and decrypt these records anyway, so the two fit together.

## 7. Decentralisation

Three things get called decentralisation. Distributed infrastructure isn't needed (§5).
Peer-to-peer doesn't fit (§7.2). Federation of small servers does.

Chorus is closer to federation than it looks:

- Ids are global UUIDv7s, and `import-account` already moves an account between servers keeping
  its ids (OPS §9).
- Each scope is its own op log, and merge is order-independent (SYNC §1).
- Devices authenticate with keypairs, not passwords.
- Chorus Home (D-071) is already the "small server" product.
- Followers only ever receive server-filtered views, so the followed account's server is the
  natural place to enforce privacy. Raw front data never has to leave it.

### 7.1 Steps

Start with an allowlist of peered servers. That fits invite-only and avoids spam and moderation
problems at the start.

1. **Portable identity.** Each account gets a signing key, an `@handle@host` address, and a
   signed "moved to" record. Servers publish a discovery document. Account moves then keep their
   follows.
2. **Remote follows.** The followed account's server delivers the already-filtered views and
   notifications to the follower's server over signed HTTP; that server pushes to its devices.
   NOTIFICATIONS §5 holds unchanged, because the same code produces what is sent.
3. **Remote members in spaces.** A space lives on one server, its creator's. A remote member's
   home server joins as a device of that account, running the existing `sync::ClientEngine` from
   core, and mirrors the scope to its own devices. The sync protocol becomes the federation
   protocol: catch-up, digests, the outbox and offline behaviour come for free.

Step 3 is a sketch, not a worked design. Open parts:

- Trust between servers, and how a host verifies that an account lives where it claims.
- Re-auditing the D-119 and D-123 ownership checks for accounts that are only shadows locally.
  `tests/privacy_audit.rs` is the harness for it.
- Blobs: fetched through the home server and cached there.
- FCM: a self-hosted server can't hold the app's Firebase credentials, so Google push needs a
  shared relay. The payload is already RFC 8291 ciphertext, so the relay would see nothing.
- The operator of the server hosting a space can read that space, unless §4 step 2 is built.

### 7.2 Not recommended

- **Matrix-style replicated rooms.** Authorising concurrent writes across servers is the hard
  problem there. Keeping each space on one server avoids it.
- **Peer-to-peer.** The delay, fuzz and digest rules need a scheduler that is always on.
- **Blinding the server completely.** It computes privacy views, tallies and digests; §4.2 keeps
  those working by leaving structure plain.

## 8. Suggested order

1. Metrics and a many-devices load test (§2).
2. The database lock split, incremental digests and indexed fan-out (§2).
3. Bounded sockets, streamed blobs, concurrent pushes (§2).
4. Offsite backups, blob quotas, operator tooling (§3).
5. Views on devices (§6).
6. Account-private encryption (§4.3 step 1).
7. Portable identity, then remote follows (§7.1 steps 1 and 2).
8. Shared-data encryption and mirrored spaces (§4.3 step 2, §7.1 step 3).

Steps 1 to 4 change nothing users see. From step 5 on, each needs a decision.

## 9. Decisions for the owner

| Question | Touches |
| --- | --- |
| Is the goal custody (not holding everyone's data), resilience, or both? Custody points to §4 and §7; resilience is mostly offsite backups. | — |
| Adopt client-side encryption of content, with a mandatory recovery key and server search gone? | Supersedes D-033; changes D-019, D-041, D-107 |
| Offsite backups? | Supersedes D-043 |
| May superseded read marks be compacted out of the op log? | The append-only rule (AGENTS.md), D-053 |
| Federate, and start with an allowlist of peers? | New; touches D-002, D-054, D-126 |
| Build SYNC §4.3 views, or correct the spec to say they are REST reads? | SYNC §4.3, D-086 |

## 10. Not verified

- No load test was run; every capacity statement is an estimate from reading code.
- The size of the current VPS wasn't checked (no production access was used).
- Whether `push::send` has its own timeout wasn't checked; only that pushes go one at a time.
- The Android client's REST reads weren't surveyed; §6's call counts are from the web app.
- §4 and §7 are designs on paper. Neither has a prototype.
