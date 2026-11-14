# Rearchitecture plan from the decision worksheet

**Status: a draft for the owner, not authorization to build.** Written 2026-11-14 by
claude-fable-5.2 from [DECISIONS_REVISIT.md](../DECISIONS_REVISIT.md): the owner's written answers
for R01–R06 and an interview the same day for the rest (recorded in the worksheet's answer
blocks). Background for tracks G, E and F is [SCALING.md](../SCALING.md), a review that read the
code and measured nothing.

Nothing here supersedes DECISIONS.md. Each phase below starts by writing its `D-xxx` entries from
the "decisions to approve" list, and the owner approves that wording before code is written
(worksheet, "How to record an answer"). The only decision carried over so far is D-161 (Q21–Q23).

This plan starts after UI21 wave 8 ships. One build at a time: the PC has been short of memory
(PROGRESS, *Now*).

---

## 1. What the answers add up to

Four tracks, in the order the owner chose:

| Track | Worksheet | What it is | Size |
| --- | --- | --- | --- |
| **G** Groundwork | R12 | Metrics, load test, the six server hot paths, offsite backups, quotas, a cold archive for old read marks | M |
| **E** Encryption | R10 | Devices encrypt content; the server keeps structure. Recovery key plus friends. Old history sealed in place | XL |
| **I** Identity | R01–R06, R08, R09 | One resolver in the core for lineage, relations, blends, subsystems, layers and views | XL |
| **F** Federation | R11 | Portable accounts, remote follows, shared spaces across servers | XL |

Automation (R07) is parked. If it returns, rules run on one chosen device (§7).

Three architectural moves carry all of it:

1. **Content and structure are separated** (track E). Every payload field is one or the other.
   Structure (ids, kinds, scopes, times, flags, permissions) stays plain and is what the server
   reads, checks and schedules on. Content (text, names, notes, labels, files) is sealed on the
   device. Every record designed in tracks I and F follows this split from its first version.
2. **One resolver answers "who counts as whom, where, when"** (track I). Today that answer is
   spread over `front_system` (purpose pools), `speaker`, `reading`, the `member_subsystem:` pref
   and its client expanders (`Subsystems.kt`, `subsystems.ts`), `chat.hide_private`, and the
   server's `perms::listed_in` / `kept_to` / `private_chat` and `search_view::CurrentView`. Remap,
   functional relations, blends and per-subsystem effects each need to change that answer, so it
   moves into one core module with fixtures, and those call sites become its consumers.
3. **A scope is the unit of hosting** (track F). Scopes already have independent logs and an
   order-independent merge. A remote server joins a space as a device of its account, so the sync
   protocol is the federation protocol.

Why this order holds together: G splits the database lock and `app.rs`, which every later track
edits. E before F keeps plain text off other operators' servers. E before I means the server's
enforcement surface is already small (it no longer searches or writes notification text) when the
resolver is wired into it, and no identity record is ever stored plain.

One option the owner may want: the first identity slice (I1) adds only flags and client screens,
no content. It could run before E without storing anything plain. The plan keeps the chosen order.

## 2. Track G — groundwork (R12)

Target: a few hundred accounts on the current VPS with SQLite. Proposed test figure: 500 accounts,
1,500 connected devices, 2 M ops, with SPEC §9's budgets holding. The VPS's size has not been
checked; do that first.

| Id | Work | Done when |
| --- | --- | --- |
| G1 | `/metrics` (lock wait and hold, peers, catch-up time, socket queue depth, push latency); a many-devices load mode in the chaos harness (SYNC §9.3) | A baseline run is recorded in NOTES with numbers |
| G2 | `Db` handle with one writer and a pool of read-only WAL connections; `app.rs` split into sync, HTTP and jobs modules | Load run shows no request waiting on a catch-up; all tests pass unchanged |
| G3 | Incremental digests per (scope, account), checked against a full recompute in the chaos test; fan-out indexed by scope; bounded socket queue with paged catch-up; streamed blob downloads; concurrent pushes with a timeout | The load target passes; G1's numbers are recorded again |
| G4 | Offsite backups: nightly to S3-compatible storage, encrypted with an `age` key only the owner holds, monthly automatic restore check. Per-account file quotas, a disk alarm, `LimitNOFILE`, account suspension in the admin CLI | A restore from offsite is verified on a scratch directory; OPS.md describes it |
| G5 | Cold archive (below) | Rebuild from live log plus archive equals rebuild from the full log |

**G5 design.** A server-derived op `scope.compact {cutoff}` (like the poll tallies of D-086) tells
every replica of a scope the same thing: a `read.mark`, `read.set` or `pref.set` that lost
last-writer-wins on its key and is older than `cutoff` (90 days) leaves the live log. The server
moves those rows to `archive/ops-<year>.db` (same schema, in the backup set, replayable); devices
drop them. The scope digest is defined over the live log, so replicas agree after applying the
same `scope.compact`. A late op that is already a loser and older than the cutoff goes straight to
the archive. Never eligible: switches, messages, posts, lineage, relations, anything not
superseded. G5 can wait until the log's growth is measured; it must land before F.

Decisions to approve: offsite backups (supersedes D-043); the archive (amends D-053 and the
append-only rule in AGENTS.md); quotas and suspension (new; the interview did not cover operator
powers, so the proposal is the minimum: suspend, set a quota, purge on request).

Docs: OPS.md, SYNC §6.4 and §5.5, DATA_MODEL §2, SCALING.md (mark what was measured).

## 3. Track E — encryption (R10)

Goal chosen: the operator cannot read content. Recovery: a required recovery key plus optional
trusted friends. Existing history: sealed in place.

### 3.1 Shape

- **Field classes.** `KindSpec` in `chorus-core/src/op.rs` gains a class per payload field: `plain`
  or `sealed`. DATA_MODEL §3 documents the table. Sealed: message and post text, entities, names,
  pronouns, bios, notes, field values, poll labels, filenames and alt text, file bytes. Plain: ids,
  kinds, scopes, times, flags, permissions, counts, claimed handles (public identifiers that must
  be unique). The `server` scope (custom emoji) and the `server` audience stay plain: anything
  every signed-in account may read, the operator may read.
- **Envelope.** A sealed field's value is `{"$s":1,"k":<key id>,"n":<nonce>,"c":<ciphertext>}`,
  AES-256-GCM, with the op id, kind and field path as associated data so a value can't be moved to
  another record. Randomness is passed in, so `chorus-core` stays pure.
- **Keys.** An account root key made on the first upgraded device, wrapped to each device's
  existing P-256 key (`auth.rs`; push already does ECDH, HKDF and AES-GCM in Rust and on the JVM,
  D-059). Under it: a key for account-private data; one key per audience (followers, friends, each
  bucket), wrapped to each account in that audience; one key per space, wrapped to each member
  account. Audience and space keys have epochs: removing someone starts a new epoch. A new space
  member receives older epochs only if they hold the `history` permission (D-133), so the two
  rules agree.
- **Files.** The device encrypts a file under a random key carried, sealed, in its attachment op.
  The blob hash is the hash of the ciphertext, so cross-account dedup ends.
- **Validation.** Rules on sealed fields (`validate_new`: ranges inside the text, shapes) can no
  longer run on the server. They run on the writing device and again on every reading device after
  decrypting; a record that fails is not rendered. The server checks sizes of ciphertext.
- **Linking a device.** The Link a device QR carries a one-time secret that wraps the root key, so
  the two devices need not be online together. Signing a device out starts new epochs.
- **Recovery.** A printable recovery phrase is required before encryption turns on. Optionally, the
  recovery secret is split (default any 2 of 3) into shares sealed to chosen friends' accounts;
  each friend approves a release to the new device. Without the phrase, enough friends, or a
  device, the content is gone. The operator holds nothing that helps.

### 3.2 What moves to the devices

| Today on the server | After |
| --- | --- |
| Full-text search | Local search only (web and Android already have it); server FTS tables dropped |
| Notification text (`notifier::describe`) | The push carries ids; the device renders from records it holds. The web service worker fetches the record and decrypts with WebCrypto |
| Friends' profiles, directories, follower front names, friends' posts (REST reads) | SYNC §4.3 views, built at last: sealed records pushed with a `view_seq` cursor, kept offline, evicted by a null record |
| Default handles derived from names (D-126) | Worked out on the viewer's device from cards it can read; only claimed handles are known to the server |
| Post previews, edit-history diffs (D-107), saved-feed text filters | Core on the device; structural filters (`fronting:`) stay on the server |
| Exports (CSV, JSONL, SQLite, PluralSpec) | Built on the device |
| API tokens, webhooks, SSE (D-019) | Structure plain, content sealed. A token may be granted a wrapped key and a small helper built on the core decrypts. Proposal; not asked in the interview |

What does not move: ownership checks at ingest (D-119, D-123), permission resolution, delayed and
fuzzed reveals, poll tallies (counts over option ids), digests, held-switch and resolver checks
(track I). All of those read structure.

### 3.3 Phases

| Id | Work | Done when |
| --- | --- | --- |
| E0 | `docs/ENCRYPTION.md`: threat model, field table, key tree, the claim we can honestly make; D-entries approved | Owner approval |
| E1 | Core: envelope, key derivation, wrap and unwrap, fixtures with test vectors run through Rust, wasm, UniFFI and WebCrypto | Vectors pass on all four |
| E2 | Root key, device wraps, Link a device, recovery phrase, the version gate (§4.9) | A second device and a recovery-phrase restore work on the emulator and web |
| E3 | Account-private data sealed for new writes (private entries, asides, the internal space, prefs, private profile parts); device-side exports | The server's copy of a new private entry is ciphertext; export from a device matches |
| E4 | Views on devices plus audience keys: profiles, member cards, posts, follower front names; notifications rendered on the device | privacy_audit passes with new cases; a friend's profile opens offline |
| E5 | Space keys: DMs and shared spaces, epochs on removal, files | Two accounts chat on an isolated demo with the server holding only ciphertext |
| E6 | Seal in place: each account's devices re-upload old content sealed; server projections keep structural columns only; FTS removed; old plain backups deleted after a sealed backup is verified | A scan of the database and the offsite backup finds no plain content for sealed accounts |
| E7 | Trusted-friend recovery | Restore with 2 of 3 friends on a demo |
| E8 | Tokens with content access and the helper | A webhook consumer reads a message through the helper |

**E6 details.** `POST /ops/{id}/seal` replaces a stored payload with its sealed form, checked to
come from the op's own author account, keeping id, seq and HLC. Digests cover ids, so they do not
change (the purge already relies on this, DATA_MODEL §2). An account that never comes back stays
plain and is listed as such. Deleting the old backups is destructive: the agent writes the script
and the owner runs it.

Decisions to approve: client-side encryption (supersedes D-033; amends D-019, D-107, D-126's
server-derived defaults and D-053 for the seal); the recovery rule; SYNC §4.3 views built (closes
D-086's note); the custody statement on the disclosure card.

**The limit to state plainly** (SCALING §4.5): the operator ships the web app and the in-app
Android update (D-060). The claim is "cannot read the database or the backups"; reading would
take an active, detectable change to a client. Release-signed APKs (D-072) narrow it on Android.

## 4. Track I — the identity model (R01–R06, R08, R09)

### 4.1 The resolver

New core module `chorus_core::identity`, pure, with `fixtures/identity/`, exported to wasm and
UniFFI like the front helpers. Stages, each a pure function of account state and a time:

1. **Lineage**: a recorded member id at time *t* → the identities it reads as now (§4.3).
2. **Presence**: fronts × layers, with per-layer following and locks (§4.6). Extends
   `front_system::resolve`.
3. **Expansion**: from who is present to who *counts*, per effect, through containment, relation
   and blend edges (§4.4, §4.5). Effects: `see`, `act`, `shown`, `side`.
4. **Access**: which chats and members are visible and who may speak, from stage 3 and each
   chat's lists. An explicit exclusion beats any inclusion (R01's Chat Xa).
5. **Views**: the soft rule list (§4.7). Clients only; the server never runs this stage.

Every result carries its reasons (the ids of the edges and rules that produced it), so a client
can say in plain words why a chat shows.

**Bounded iteration.** Exclusive effects make expansion non-monotone, so each stage iterates to a
fixed point with a limit: 8 by default, an account setting. A write that would not settle is
refused on the device before it is saved, showing each step (R01, R02, R05). Two offline devices
can each make a valid change that together do not settle; neither can be refused, so the later
one (by HLC) is **held** (§4.8), never dropped. The resolver always returns a usable state.

**Server use.** Relations are hard rules (interview). The server calls the same core functions
natively and keeps a derived per-account cache, invalidated by lineage, relation, composition and
presence ops, as `fronts.rs` does today. Consumers: `perms::write_denied` (who may speak in a
D-098 chat), the chat notification rules, follower and space front views, poll tallies. Stages
1–4 read only structure, so they work on sealed accounts.

### 4.2 Records

All in the `account:` scope. Names and notes are sealed; everything the resolver reads is plain.

| Record | Change |
| --- | --- |
| `remap.create` / `remap.reverse` | New. A remap and its cancellation (§4.3) |
| `reltype.set`, `relationship.set` | Extended with effects, arity, flags, conditions, time bounds (§4.4) |
| `member.set` `composition` | New field: `{kind: blend/collective, parts: [{member_id, share?}], audience: widen/narrow, alerts: loosen/tighten, on_overlap}` (§4.5) |
| `front.change` v2 | An entry holds several `layer_ids`; `blend_id`, `annotation`; `break_locks` / `break_stuck` on the op (§4.6) |
| `front.lock` | New. `{front, subject, layer, strength: none/lock/stuck}`, last writer wins per key |
| `front.define` `follow_layers` | New field: destination layer → `{front, layer}` |
| `front.review_resolve` | Extended: `apply` / `discard` with op ids, for held changes (§4.8) |
| `view.rule` | New. A saved view condition (§4.7) |

The `member_subsystem:<id>` pref becomes a containment relation (UI6 slice 3 already planned a
real field); the pref is read as a fallback and migrated by a written op, never deleted.

### 4.3 Remap (R02)

- Ids never change and no op is rewritten. `remap.create {effective_at, until?, entries, maintainer?}`
  where each entry is `{from: [ids], to: [ids], mode, past, counts}`: `mode` is dormant, recreated,
  replaced, shared, unknown or ended; `past` says whether earlier records keep their original
  identity or read as the heirs; `counts` says whether a converging group's votes and reactions
  are merged or split-counted (interview: chosen per pair or group in the preview).
- `lineage::resolve` works in epochs between remaps: a date is resolved against the last remap
  before it. A swap inside one remap is a permutation, applied once. Time-bounded mappings that
  loop go through the bounded iteration.
- **Access follows the mapping.** Chat lists, private chats and permissions that name a `from` id
  read through the lineage, so the heirs gain what the mapping gives. New permissions name the new
  members.
- **Maintainer.** A member appointed to receive references that would map to nobody. Without one,
  a member-listed chat whose list on our side becomes empty closes for us with a
  "<Member> is no longer present" system message.
- **Preview.** `remap::preview` (core) returns access gained, dangling references, converging
  groups with their doubled votes and reactions, handle conflicts and each iteration step. The
  remap screen is built from it, and asks for a full export first.
- **Reversing** is `remap.reverse`: everything written since stays and reads through the earlier
  lineage (interview). The export is a safety copy.
- **Other accounts** see the current state: cards for an old id resolve to the heir when the entry
  says so. A remap sends no notification, and what a follower sees of a fronting member changes
  only at a reveal (§4.10).
- **Poll tallies** (D-086) are recomputed by the server when a remap with merged counts lands.

To settle when I3 starts: whether a replaced member's claimed handles (D-126, D-146) pass to the
heir or stay reserved; proposed as a per-entry choice in the preview.

### 4.4 Relations with effects (R03) and subsystems (R05)

- A relation type carries: `symmetric`, `directional`, `transitive`, `exclusive`, `arity`
  (pair or group), a priority, and effects: `see` (sees the other's spaces), `act` (may act
  there), `inseparable` (one cannot be excluded without the other), `one_shown` with a scope
  (fronts, our lists, other accounts), `side` (bubble alignment). All editable; nothing is
  hard-wired to a type's name.
- Templates shipped as editable types, matching the owner's definitions: **Duality** (symmetric;
  see and act; `one_shown` set per type), **Equivalent** (symmetric, transitive; see and act;
  inseparable), **Overseer** (directional; see only; listed as overseer), **Contains** (a
  subsystem and its parts; `side` on as today under D-102, the new "parts' chats come with it" and
  "parts can be picked as speaker" off until a subsystem turns them on).
- A relationship's ends may be members, groups or states; it may hold a condition ("while in
  these layers"), `since` / `until`, a note and `certain: false`. Proposed: an uncertain relation
  has no effects. Several relations on the same pair resolve by the type's priority.
- R01's "attach viewing settings to people for when they front" is a `see` relation with a
  presence condition (A sees what C sees while A fronts).
- A change is either technical (edited in place) or semantic (the old edge gets an `until`, a new
  one starts, and it may be announced as a Journal post with a chosen audience).
- Effects apply inside the account only. Relationships to other accounts stay descriptive.
- R05's smaller items: a subsystem's profile lists its own posts and its parts'; a member in
  several subsystems and a member that is both a collective and an individual already work; the
  group-tree cycle guard (SYNC §5.3) is replaced by the bounded iteration; the status line is the
  existing member state note (D-122, D-138) shown beside the name. That reading is the agent's.

### 4.5 Blends (R04)

Three forms, as PluralSpec D5 already separates them: a composite member with `composition`; a
`blend_id` shared by presence entries of members who stay themselves; a preset (a saved switch).

- Switching a blend in beside one of its parts asks "Take A and B out?" with "don't ask for this
  blend again" (`on_overlap`: ask, take out, keep). Nothing is refused. Kept together, the card
  says so quietly and send-as offers the blend first.
- Parts can change over time (a semantic change) and blends can contain blends.
- Audience and alerts are computed on our side from the parts' settings (widen or narrow, loosen
  or tighten) and stored as the blend's own, so nothing about its parts leaves the account.
- Insights can attribute a blend's time to its parts by `share` (even by default); recorded time
  and attributed time are separate modes, so nothing is counted twice.
- Degree of blending is a note or a member state; no app support.

### 4.6 Layers, locks and the widget (R01)

- An entry may sit in several layers of one front. `front.change` v2 carries `layer_ids`; v1 reads
  as one layer. Primary is per front, and none or several are allowed; the default speaker is the
  first primary in order.
- Layers shown side by side are the display prefs' `separate` layers (D-122); no model change.
- **Locks.** `lock` is an edit guard private to our devices. `stuck` is part of the status and
  visible to whoever sees that layer and member. In the fold, a change that touches a locked
  entry without the matching break flag leaves that entry alone and is listed as held. Replace
  resets the front except locked entries; Remove asks first and then sends the flag; Undo always
  goes through.
- **Following per layer** through `follow_layers`. Renames keep the link (ids). Deleting one end
  warns that the follower will stop following.
- **Time** in a front counts once per member across layers (Insights and exports gain an
  any-layer total).
- **Wording.** No screen says a front state is invalid. A non-standard state gets a note
  (`annotation`).
- **Widget** (Android): cycles through fronts; a member row shows their layer and a tap cycles it;
  the change is written after five seconds without a tap; a Switch button opens the drawer.
- Purposes (speaking, mentions, reading, chat alerts, switch alerts) stay configurable per front
  (D-085), unchanged.

### 4.7 View conditions (R06)

Soft only (interview). `view.rule {target, when, then, mode, on_unknown, order}`:

- Targets: member lists, chat lists, Journal feeds, profiles, widgets.
- `when` is a small expression tree: all / any / not, membership, comparison, time windows,
  relation traversal with a depth limit. Inputs: the reader (Read as), presence by front and
  layer, relations, custom fields, a manual view switched by hand, and what friends have already
  revealed to us.
- Rules run top to bottom; the first match decides. `on_unknown` picks the loosening or the
  tightening result when data is missing. `mode` is live (today's rules and lineage) or locked
  (the state at the item's time, later lineage applied), using the resolver with a time, as D-085
  does for old mentions.
- Evaluated by `chorus_core::view`, the same on web and Android, with reasons the client turns
  into a sentence.
- `chat.hide_private` (D-097) becomes a default rule. Private chats under D-098 and D-155 stay
  hard and belong to stage 4.

### 4.8 Held changes (R01, R02, R09)

One list on Fronting › History, "Waiting for you", for every change the model would not apply by
itself: a late offline switch that clashes with something recorded meanwhile, a change blocked by
a lock, a write that would not settle, an old-identity record with no single heir. Held changes
are in the log, out of the timeline and out of every follower surface until resolved with
`front.review_resolve`: apply all, apply some devices', or discard (confirmed; discarded items go
to Trash). A late switch that clashes with nothing merges by real time as now.

The clash test starts from the concurrent-switch detector of SYNC §5.7 without its two-minute
window, and must be decided identically on every replica. This amends D-037.

### 4.9 Old devices and interop (R09)

- An account records the model version it needs. A device announces its core version in `hello`;
  below the need, its pushes are refused with `upgrade_required`, and it shows what it understands
  read-only until it updates (interview).
- JSONL and SQLite exports carry every new record; the analysis views gain lineage and relation
  views. PluralSpec work is paused by the owner (M14.5–M14.10); composites and blends already
  have a place in it, and lineage, effects and locks would go under `ext.chorus.*` with a loss
  warning until proposed to the spec.
- Fixtures that must agree on web, Android and server: lineage resolution by date, access through
  relations, blend overlap, held switches, following cycles, locks, view rules with their reasons.

### 4.10 Privacy rules for this track (R08)

Requirements, tested in `tests/privacy_audit.rs` with each slice:

1. Everything new starts as "only us".
2. A friend who may see a blend sees an ordinary member. Composition, relations and lineage with
   a hidden end are not shown at all (interview).
3. Nothing new changes for a follower at the real time of a switch. A remap of someone fronting,
   a `stuck` mark, a duality's shown half and a blend's widened audience reach a follower only at
   a reveal under the ceiling (NOTIFICATIONS §5). Held changes reveal nothing.
4. View conditions never grant or remove access.
5. A change to a relation, a composition or the lineage re-judges pending reveals under the
   current rules (the principle of D-115 and D-147).

### 4.11 Slices

Android first, then web (interview); core and server lead each slice.

| Id | Slice | Notes |
| --- | --- | --- |
| I0 | `docs/IDENTITY.md` with the resolver contract and record shapes; D-entries approved | |
| I1 | Resolver skeleton with containment; per-subsystem effect switches; a subsystem's profile lists its parts' posts; status line beside names | The small wins. Migrates `member_subsystem:` |
| I2 | Version gate and the held list | Needed by everything after |
| I3 | Remap: lineage in core, preview, export prompt, maintainer, reverse, tallies | Medium priority in the worksheet |
| I4 | Relations with effects, wired into the server | |
| I5 | Blends | |
| I6 | Multi-layer presence, locks, per-layer following, held offline switches, the widget | |
| I7 | View conditions | |

Each slice: fixtures first, then core, server, Android, web, a privacy_audit case, an emulator
and browser check on an isolated demo, then the gate with `--android` and a deploy.

Budgets (SPEC §9 is a requirement): the resolver runs on send and switch paths, so I1 adds a
bench for a 500-member account; history reads may re-fold, hot paths may not.

## 5. Track F — federation (R11)

Scope chosen: everything, including shared spaces across servers. Peering: an allowlist per
account. A lost host: read-only mirrors, and an admin can re-home the space. SCALING §7 is a
sketch, so F starts with a design and a two-server prototype.

| Id | Work |
| --- | --- |
| F0 | `docs/FEDERATION.md`, a two-server demo harness, D-entries approved |
| F1 | Portable identity: an account signing key (kept under the root key from track E), `@handle@host`, a discovery document, a signed "moved to" record, account moves that keep ids, history and follows (extends `import-account`, OPS §9) |
| F2 | Peering: the operator's list of peered servers, each account's choice among them (none until chosen), signed server-to-server requests |
| F3 | Remote follows: the followed account's server sends its already-filtered views and alerts to the follower's server. NOTIFICATIONS §5 holds because the same code produces what is sent |
| F4 | Remote DMs and shared spaces: a space lives on its creator's server; a remote member's server joins as a device of that account (`sync::ClientEngine`) and mirrors the scope. D-119 and D-123 re-audited for accounts that are only shadows locally. Files fetched through the home server. A shared relay for Google push |
| F5 | Re-homing: a signed `space.move` by an admin, restored from that admin's mirror; other servers follow |

Server-wide features to rework before F3: the single custom emoji set (D-054), the "Everyone
here" audience and the one handle namespace (D-126) become per host. What cannot be promised and
must be said in the app: a copy on another server may outlive a deletion; removing a server from
the allowlist stops new data, not old copies.

Decisions to approve: federate at all (touches D-002); the peering rule; the per-host reading of
D-054 and D-126.

## 6. What is not settled

Asked and answered: see the worksheet. Still open, with the plan's working assumption:

| Topic | Assumption until the owner says otherwise |
| --- | --- |
| Uncertain relations | Descriptive only, no effects |
| A replaced member's claimed handles | Chosen per entry in the remap preview |
| "Drops all succeeding operations" (R02) | Set aside in Trash, restorable |
| Status line (R05) | The existing member state note, shown beside the name |
| Operator powers | Suspend, quota, purge on request |
| Tokens and webhooks under encryption | Structure plain; content through a granted key and a helper |
| Basic or Advanced | Advanced for all of track I (AGENTS.md) |
| Trusted friends | Any 2 of 3 by default |
| Archive cutoff | 90 days |
| Concrete examples for R02–R06 | Blank in the worksheet; each slice's fixtures need one from the owner |

## 7. Parked

**R07 automation.** No work. Recorded so it is not re-asked: if it returns, rules run on one
device the owner chooses and sleep while it is offline. The resolver's reasons and the fixed
action names of track I are kept stable so rules could use them.

## 8. Risks and things not verified

- **Wasm size.** The opening JS budget is 300 KB (D-116, D-156) and stood at 291 KB on
  2026-11-13. Encryption and the resolver both add core code. Use WebCrypto for the ciphers on
  web and load the resolver's editing screens on demand; measure in E1 and I1.
- **Web push under encryption** depends on the service worker holding keys and decrypting. Not
  prototyped.
- **Seal in place** rewrites stored payloads. It needs its own backup, a dry run on a copy of the
  live database, and the owner running the destructive step.
- **Two modes for a while.** From E3 to E6 the server handles sealed and plain accounts.
- **Nothing was measured.** No load test exists; the VPS size is unchecked; SCALING's capacity
  figures are estimates.
- **Android local search** is listed as live with a device audit pending; E6 removes the server
  search it could fall back on.
- **Scale of the work.** Three of the four tracks are each larger than UI21. The slices are sized
  to deploy one at a time; the tracks are not.

## 9. Next concrete step

Owner: read §6 and the "decisions to approve" lists, mark the worksheet sections Approved or
change them. Agent, once G is approved: check the VPS size, write G's D-entries, add G1–G5 to the
board and start G1.
