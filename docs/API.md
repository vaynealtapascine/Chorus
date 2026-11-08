# Chorus — API

D-090 profile details continue to use ordinary typed custom fields and account-scoped sync ops;
`GET /members/{id}` and `/profiles/{id}` return them in `fields`. These routes remain own-account
only, including when the caller follows the member's account. `/members` and `/members/{id}` now
include nullable `sort_key`; list responses retain name/ID order so consumers explicitly choose
manual versus other order. Follower-front and public author-card metadata are not widened.
Avatar/banner hashes retain private authenticated blob reads and existing export references.

Base: `https://chorus.<domain>/api/v1`. JSON, UTF-8. Times are ms UTC integers (plus ISO strings in
export/CSV endpoints). IDs are UUIDv7 strings. All writes from apps go through **sync** (ops); REST
is for reads, auth, blobs, exports and third-party scripts.

---

## 1. Conventions

- Errors: `{"error":{"code":"forbidden","message":"…","retry":false}}` with a matching HTTP status.
  In a sync `ack`, a per-op error may add `retry_after_ms` (with `retry: true`): the device holds
  the op and sends it again no sooner (slow mode, D-076).
  Codes: `bad_request`, `unauthenticated`, `forbidden`, `not_found`, `conflict`, `too_large`,
  `rate_limited`, `too_many_connections`, `unsupported_version`, `internal`; per op in a sync
  `ack`, also `unprocessable` (valid-looking, but the server couldn't store it; never retried,
  and a gap in `op::validate` to fix: DATA_MODEL §2.2).
- Pagination: cursor-based, `?limit=100&before=<id>` / `after=<id>`; responses carry
  `{"items":[…],"next":"<cursor>|null"}`.
- Versioning: `/api/v1` is stable. Additive changes only; breaking ones get `/api/v2`. The sync
  protocol is versioned via `core` / `core_min` (SYNC.md §6.2).
- Rate limits (per token or session, else per client address): 50 requests burst, 10/s
  sustained (`security.rate_burst` / `rate_per_second`); sign-in (`/auth/*`) 20 burst, 1/s per
  address. Over the limit: `429 rate_limited` with `Retry-After` (seconds). Blob reads are not
  counted.
- CORS: same-origin only, except `GET` on `/stream` and read endpoints for API tokens when
  `api.cors_origins` is set.

## 2. Auth

### 2.1 Enrol a device (redeem an invite)

```
POST /auth/redeem
{ "code": "…", "device": {"name":"Pixel 9","platform":"android","public_key":"<SPKI b64, P-256>"},
  "account": {"kind":"system","display_name":"The Stars","handle":"stars"} }   // omitted for device invites
→ 201 { "account_id":"…", "device_id":"…", "short_id":"a1b2c3d4", "session":"<token>", "expires_at":… }
```

`account.kind` is the joiner's own answer (D-093): `system` or `person` on either kind of account
invite; omitted, the invite's kind stands. Anything else is `400 bad_request` and leaves the invite
unused. A person account gets one self member and no internal space (D-003).

Look at an invite without using it, so onboarding can ask the right question (a device invite
needs only the link):

```
POST /auth/invite { "code": "…" }
→ 200 { "kind": "system" | "person" | "device" }
```

Unknown, used up and expired codes are all the same `400 bad_request`. Like redeeming, it counts
against the `/auth/*` rate limit.

Link another device of the same account (any enrolled device; one use, 1 day):

```
POST /devices/invite            Authorization: Bearer <session>
→ 200 { "code":"…", "url":"https://chorus.…/i/<code>", "lan_url":null, "qr_svg":"<svg…>", "expires_at":… }
```

On a Chorus Home server (`[server] lan_listen`, D-071) `lan_url` is the home-wifi invite a phone
opens, `chorus://<lan ip>:<port>/i/<code>#pin=sha256/<b64url>` (https underneath; the app's own
scheme so a camera opens the app, not a browser), and `qr_svg` encodes it. The app trusts exactly
that certificate (its SHA-256, the `tls_pin` in `GET /server`), not a CA.

`qr_svg` encodes the URL (`qr.rs`: byte mode, level M, versions 1–10, no dependency) so a phone
can scan it from the web app. `CHORUS_QR_SAMPLES=<dir> cargo test -p chorus-server --lib qr`
followed by `python scripts/qr-check.py <dir>` decodes samples with OpenCV.

Sign out another device of the same account (a lost phone): `POST /devices/{id}/revoke` → 204.
Its sessions end, it can't renew, and its open sync socket is closed on its next frame. Revoking
the device you're calling from is a 400; another account's device is a 404. `GET /me` lists the
account's devices.

`GET /push/vapid` → `{public_key}` is the server's VAPID key for browsers' `pushManager.subscribe`
(D-061). Browsers then register below like any device.

Register this device for push (UnifiedPush endpoint + Web Push keys, NOTIFICATIONS.md §1):

```
PUT    /devices/push {endpoint, p256dh: <b64url uncompressed P-256>, auth: <b64url 16 bytes>}  → 204
DELETE /devices/push                                                                          → 204
POST   /devices/push/test                                                                     → 204
```

`POST /devices/push/test` sends a test notification (`{"t":"test","title","text"}`) to the
calling device only, to check the whole push path without a switch or a mention: `409 no_push`
when it has no registration, `502 push_gone` / `push_failed` when the push service refuses.

Payloads are RFC 8291 `aes128gcm`, one record, ≤ 3 KB plaintext (else `{"t":"sync"}`). A 404/410
from the endpoint clears the registration.

Where an endpoint may point follows `security.webhook_targets` (§7), checked at `PUT` (`400
bad_request` with the reason) and again before every send, with the same resolve-pin-no-redirect
handling: **https only** unless `any`; under `public` only globally routable addresses; under
`internal` the tailnet/LAN or public addresses (browser push services are on the internet), never
loopback or link-local; and always the operator's own `push.ntfy_url` host and port, whatever it
resolves to. A device can't make the server POST to the host's own services.

### 2.2 Sessions

- Session token: opaque random 256-bit, stored hashed, 30-day sliding expiry, bound to a device.
- Renewal (no password ever):
  `POST /auth/challenge {device_id}` → `{nonce}`;
  `POST /auth/session {device_id, nonce, signature}` (ECDSA P-256 over
  `"chorus-auth\n" + nonce + "\n" + device_id + "\n" + instance_id`) → `{session, expires_at}`.
- Private keys: Android Keystore (non-exportable, optional user-auth binding in Advanced); web
  WebCrypto non-extractable key in IndexedDB.
- Optional second factor (Advanced, per account): **Tailscale identity**. Caddy forwards
  `X-Forwarded-For`; the server asks the local `tailscaled` LocalAPI `whois` for that address and
  requires the tailnet login to match the one recorded at enrolment.
- `DELETE /auth/session` — log out this device. `POST /devices/{id}/revoke` — from another device.

### 2.3 API tokens (scripts, Grafana, overlays)

`Authorization: Bearer chorus_<random>`. Created in Settings → Advanced → API tokens. Scopes:

| Scope | Grants |
| --- | --- |
| `read:front` | current front, switches, intervals, stats |
| `read:members` | members, groups, fields, relationships |
| `read:messages` | channels and messages the account can read |
| `read:posts` | posts, lists, feeds |
| `stream` | SSE stream (filtered by other read scopes) |
| `write:front` | create switches (e.g. an NFC tag or Tasker) |
| `write:messages` | send messages (bots, bridges) |
| `export` | exports |
| `admin` | server admin endpoints (admin accounts only) |

Writes through tokens are turned into ops server-side with the token's pseudo-device id.

Implemented so far (M10.2 and M5.8, `api_data.rs`): scopes `read:front`, `read:members`,
`read:messages`, `read:posts`, `stream`, `write:front`, `export` (see §4);
`POST /tokens {name, scopes}` → `{id, token}` (shown once), `GET /tokens`, `DELETE /tokens/{id}` —
from a signed-in device only (tokens can't mint tokens). Reads: `GET /front`, `/front/switches`,
`/front/intervals`, `/members`. `GET /stream` sends `event: front` (current front first, then each
change; needs `read:front`) and `event: message` (the account's own new messages, with
`channel` name and `channel_id`; needs `read:messages`). `?events=front,message` picks (default:
all you may read; asking for one you can't read is a 403), `?channels=<ids>` filters messages.
EventSource clients pass `?token=`. `/overlay/front?token=…` is a transparent OBS pill.

## 3. Read endpoints

```
GET  /me                                   account, devices, capabilities
GET  /server                               name, version, instance_id, core_min, capabilities

GET  /members?include_archived=0
GET  /members/{id}
GET  /groups                               tree with effective_parent_id
GET  /fields                               field definitions
GET  /states                               custom front states

GET  /front                                current front (resolved names, levels, since)
GET  /front/switches?front=&history=own|effective&from=&to=&limit=
GET  /front/intervals?front=&history=effective|own&from=&to=&subject=&layer_id=
GET  /front/daily?front=&history=effective|own&from=YYYY-MM-DD&to=…&layer_id=&tz=
GET  /front/reviews?front=&open=1

GET  /spaces
GET  /spaces/{id}/channels
GET  /channels/{id}/messages?before=&after=&around=&limit=
GET  /messages/{id}                        the message (same scoped fields as search)
GET  /messages/{id}/revisions              edit history, oldest first; the last is what it says now
  → {items:[{rev,text,entities,cw,at}]}; an unedited message has one item (itself). Same read
    rule as the message (a stranger or another account's aside: 404); tokens need read:messages.
GET  /messages/{id}/thread
GET  /search/messages?q=&tz=&in=&from=&before=&after=&has=&limit=&cursor=
  → {items:[{id,channel_id,space_id,account_id,occurred_at,text,cw,visibility,authors}],next_cursor}
  `q` is a search box, read by core (`chorus_core::search`, SPEC §5.3) exactly as the apps'
  local search reads it: words (each a prefix of a word of the text or content warning, case
  and accents ignored; through FTS5) and `from:` (member name or id; several = any),
  `in:` (channel name or id, `#` optional; several = any), `has:image|file|attachment|link`
  (all must hold), `before:`/`after:` (a date `2026-09-01` in the `tz` time zone, minutes east
  of UTC, default 0: before its start / after its end; or an age `30d`, `12h`, `2w`),
  `is:pinned`. Filters alone (no words) list the newest matches. A bad box is a 400 with where
  and why. The older `in`, `from`, `has` (same values) and `before`/`after` (exclusive epoch
  ms) parameters still narrow the search. Pages contain 1–100
  results (default 100). Pass `next_cursor` back with the same search and filters to continue;
  a null cursor means the results are exhausted. Results are ordered by occurred time then id, newest first, and pass the current-front
  channel/message view (D-114). Cursors carry no corpus-dependent FTS rank. API tokens need
  `read:messages`; device sessions inherit access.
GET  /pins?channel=
GET  /search/posts?q=&account=&kind=&before=&after=&limit=&cursor=
  → {items:[post, as GET /posts],next_cursor}
  FTS5 over post titles, text and tags (post_fts). Every hit passes the post's audience check
  (`posts::readable_sql`, as `/posts`): own posts, server-visible ones, and ones shared with an
  active follower or an assigned bucket; deleted posts never match. Chronological order (newest first), pages (1–100,
  default 100) and the opaque cursor work like `/search/messages`. API tokens need `read:posts`
  and search only their own account's posts.

GET  /posts?author=&kind=&before=&limit=   each item with `quoted` (when readable), `parent` (the post
                                            it answers, when readable; no media) and `reply_count`
GET  /posts/activity?before=&limit=        other accounts' readable replies, quotes, reposts and
                                            reactions on the caller's posts, newest first (D-127):
                                            {items:[{kind: reply|quote|repost|reaction, at, post?,
                                            emoji?, member_id?, member_name?, target:{id,kind,title,
                                            text,cw}}], reply_counts:{post:n}, reactions:{post:[…]}};
                                            devices only (tokens get empty lists)
GET  /posts/{id}                           with replies ?depth=
                                           + reply_count on it and each reply, and ancestors (what it
                                           answers, oldest first; ends at the first unreadable parent)
                                           + quotes (readable posts quoting it, newest first) and, on
                                           every post it returns, quoted (the quoted post, if readable)
GET  /posts/{id}/revisions                 {items:[{rev,title,text,entities,cw,at}]}, same read rule as the post
  D-107: reconstructed from compact server-kept diffs. Deleted/unreadable posts return 404;
  history does not grant access to prior attachments or broaden the post's audience.
  Device-session reads now return posts visible to the caller: own posts, server-visible posts,
  posts shared with active followers, and posts for an assigned, live bucket. `account=` narrows
  the list to one account. `before` is an exclusive occurred-at millisecond value; `limit` is
  clamped to 1–100. Detail replies are filtered by the same rule (`depth` 0–3, at most 50 per
  level). Hidden/deleted posts return 404. Responses omit `front_snapshot` and remove unreadable
  parent/repost links. Authors include ordered member ids and small author cards; a card says
  `profile: true` and carries `avatar_blob` only when the reader may open that member's profile
  (D-101, the rule the blob read uses), else `profile: false` and no picture. Each post also
  includes ordered attachment metadata and blob hashes, plus present post reactions with emoji and
  reactor member id/name; blob downloads enforce the same current
  audience. API tokens (`read:posts`) get only their own account's posts here, and only their
  own account's replies under one.
GET  /timeline?before=&limit=              combined system timeline
GET  /profiles/{member_id}                 profile bundle (fields, stats, highlights, relationships)
GET  /lists  /lists/{id}/timeline
GET  /feeds  /feeds/{id}/items?limit=&cursor=   evaluates the feed query (below)
POST /feeds/preview {query}                → parsed AST + first 20 items (for the editor)

GET  /android/latest                       the published Android build for in-app updates (no auth):
                                           {version_code, version_name, sha256, size, changelog, url}; 404 if none
GET  /emoji                                the server-wide custom emoji set
GET  /accounts/{id}/view                   follower view of another account (privacy-filtered, §5 NOTIFICATIONS)
GET  /accounts/{id}/members                the members of that account shared with you (D-101): {items:[{id, name,
                                           display_name, pronouns, color, sigil, avatar_blob, banner_blob, roles,
                                           handle, handles}]}, unshared parts null; roles are the pills their Members
                                           list shows (D-100: its roles field and pill setting), [] when that field
                                           isn't shared. D-126: `name` is the full name only where its `name` part
                                           reaches you, else the display name; `handle` is the member's shown handle
                                           (first claim, else default), `handles` their claims; your own members'
                                           cards also carry `default_handle`
GET  /accounts/{id}/members/{member}       one shared profile: the card plus description(+_entities), birthday,
                                           banner_blob and fields:[{id,name,type,value}] shared with you; 404 alike
                                           for missing and unshared. Never fronting, history or timestamps.
                                           Another account's profile also says post_alerts: none|requested|active.
  D-107: includes relationships allowed by their individual audience and the target profile's
  visibility. Hidden/deleted targets omit the entire relationship, including notes and ids.
GET  /relationship-targets                 device-session-only {items:[{id,account_id,name,display_name,...}]};
                                           on-server profiles this account may see, including singlets.
                                           No fronting, history, private target ids or search index.
PUT  /accounts/{id}/members/{member}/alerts  ask to hear about that member's new posts (D-106): {post_alerts};
                                           404 unless you may open the profile; the owner hears once
DELETE /accounts/{id}/members/{member}/alerts  stop asking / turn alerts off → {post_alerts}
GET  /accounts/{id}/alerts                 a person you follow, by account (their own member):
                                           {member_id, post_alerts}; 404 if you don't follow them
PUT  /accounts/{id}/alerts                 ask about that person's posts → {member_id, post_alerts}
DELETE /accounts/{id}/alerts               stop asking / turn alerts off → {member_id, post_alerts}
GET  /alerts                               who asked about, or hears about, your members:
                                           {items:[{member_id, status: requested|active, created_at, account}]}
PUT  /alerts/{member}/{account}            say yes (they're told) → 204
DELETE /alerts/{member}/{account}          say no, or stop their alerts (not told; they keep seeing
                                           "requested") → 204
GET  /follows                               following + followers ({following:[…], followers:[…]}, open ones)
POST /follows {target:"@handle"|account_id}  ask to follow (201; 200 with the open follow's id if one exists)
PUT  /follows/{id}/prefs {…}                 the follower's own prefs (NOTIFICATIONS.md §4)
GET  /follows/{id}/view                      what that follower sees of you right now (Figma F7.9): the
                                             same view as their `/accounts/{you}/view`; only the followed
                                             account may ask, 404 otherwise (UI20)
DELETE /follows/{id}                         unfollow (follower) or remove (target)
GET  /notifications?after=<ms>             delivered notifications, newest first (100); `after` keeps
                                            only newer ones, and pings caused by the calling device's
                                            own writes are left out (D-096: phones without push poll it)
GET  /insights/{chart}?from=&to=           dashboard data (same numbers as the views)
```

Reads of another account's content always go through the view layer (same privacy as sync views).

Implemented so far (`api_data.rs`, `api_reads.rs`; sessions or API tokens):

- **Account:** `/me` returns `{account, via: "device"|"token", scopes, devices}`. `devices` is
  shown to devices only.
- **Members** (`read:members`):
  - `/members`;
  - `/members/{id}`, which adds `groups` (ids) and `fields` (`[{field_id, name, type, value}]`);
  - `/groups` (a flat list with `effective_parent_id` and `member_ids`);
  - `/fields`.
- **Front** (`read:front`):
  - `/states`;
  - `/front`, `/front/switches`, `/front/intervals`;
  - `/front/daily?from=&to=&level=` (days are `YYYY-MM-DD`, `to` inclusive);
  - `/front/reviews?open=1`.
  - Once named fronts are configured, `/front` reads canonical Body current state and adds the
    private own-account `front_system: {system, resolved}`. Entries include `layer_id`; `level`
    is supplied only for the actual original front/cocon/present IDs. A custom layer never gets
    a legacy role from its name/display level. `since` is currently null on this new current-only
    path. Management controls remain unexposed in the clients.
  - History reads accept `front=<id>` (Body/account ID when omitted). Front IDs are scoped to the
    caller's own account; missing fronts return 400, while deleted fronts retain their history.
    `/front/switches` defaults to `history=own`: amended/retracted saved entries retain original
    IDs and `editable: true`. `history=effective` returns inherited transitions, private source/
    cause metadata and synthetic change IDs with `editable: false`; edit the saved cause instead.
    The time range is applied after the complete fold, so a correction outside it still applies.
  - `/front/intervals` and `/front/daily` default to effective history; `history=own` explicitly
    selects independently saved presence. They accept `layer_id`; `level` remains a filtering alias
    for the same stable ID. Conflicting aliases return 400. Returned `level` exists only for the
    actual original three IDs; independent names/display levels never merge totals.
  - Named daily totals clamp both open and closed intervals to now and the requested local days.
    `tz` is one fixed offset in minutes east of UTC (−1440…1440); absent uses the selected history's
    most recent offset. Dates are real Gregorian `YYYY-MM-DD` days in 0000…9999, `to` inclusive.
    These reads do not yet resolve IANA timezone/DST schedules. `/front/reviews` selects a front's
    own concurrent-switch reviews and resolves them from account-scoped ops, including a
    resolution that arrived before its switches.
  - Named responses add `front_id`, and switches/intervals/daily add `history`. Unconfigured
    accounts retain the original SQL response unless they explicitly request a front/history
    (or daily `tz`). These APIs are on-demand owner reads, never follower history/statistics;
    follower records must pass the reveal rules. Native/web offline History, Insights and review cards use the replica’s shared-core reads;
    exports add the folded `named_*` tables (DATA_MODEL §7.1).
- **Other accounts:** `/accounts/{id}/view`, `/follows`, `/notifications`. The view is
  `{entries, since, revealed_at, time}`, plus `history: [{entries, time}]` and
  `stats: {days, members: [{name, share_pct}]}` when the ceiling has `share_history` or
  `share_stats` (NOTIFICATIONS §3). When the ceiling grants additional fronts (D-084), the view
  adds `fronts: [{id, name, entries, since, revealed_at}]` with only revealed states of those
  fronts, under the name each had when revealed; `history`/`stats` stay Body's.
- `/front/intervals` also takes `subject` (an id) and `level`.
- **Shared spaces and DMs** (M6.2, `spaces.rs`, signed-in devices only):
  - `GET /spaces` returns your spaces with the accounts in each (id, handle, display_name, kind,
    avatar_blob), plus spaces where a channel is shared with you (`guest: true`; DATA_MODEL §4.4).
  - `POST /spaces {kind: "shared"|"dm", name?, accounts: [account ids]}` answers
    `201 {id}`, or `200 {id}` when that DM already exists. It works only with accounts connected
    to you by an active follow, in either direction.
  - `POST /spaces/{id}/members {accounts}` is for a shared space's owner only.
  - `DELETE /spaces/{id}/members/me` leaves. The owner of a shared space can't leave it, and
    nobody can leave their home space.
  - `GET /spaces/{id}/authors` returns `{accounts, members}`. `members` holds author cards (name,
    display name, pronouns, colour, sigils, avatar, handle, handles; the name by the D-126 rule
    above) for other accounts' members who wrote a message
    everyone in the space can read, or whom a private chat you may view is kept to (D-098, its
    `member_ids`), while their account is in the space.
  - A space you aren't in is a 404.
Channel list items also carry nullable `avatar_blob` (D-088). Fetch that hash through the ordinary
blob endpoint; current channel visibility, including thread-parent privacy, gates image reads.
Updated `GET /server` responses include `capabilities:["channel_images"]`. Clients must gate image
changes on that capability; older responses omit it. Omit `avatar_blob` on an image-less create,
since schema-15 servers reject the unknown field even if its value is null.

- **Messages** (`messages.rs`, 2026-09-24): `GET /spaces/{id}/channels`,
  `GET /channels/{id}/messages?before=&after=&around=&limit=` (epoch ms, exclusive; `around` is a
  message id; oldest first, 1–100, default 50) and `GET /messages/{id}/thread`. Same rule as
  search: public or own messages in channels you may `view` (channel permissions, DATA_MODEL
  §4.4; a guest sees only the channels shared with them), threads under messages you can't see
  are hidden (404). A reply (`reply_to`) keeps its link, plus the original's channel
  (`reply_to_channel_id`, for a "reply elsewhere" reference card), only for a reader who can read
  the original; anyone else gets `reply_to: null` (SPEC §5.3). A send into a channel in slow
  mode (`settings.slow_mode_s`) too soon after the account's last one there, counted on arrival,
  is acked `slow_mode` with the wait (OPEN_QUESTIONS Q17; those who may `manage` it are exempt).
  A pushed op the permissions refuse is acked `forbidden` with the reason
  (e.g. "you don't have the send permission in this channel"). API tokens with `read:messages`
  get only their own account's messages (§2.3).
- **Handles** (`handles.rs`, D-126): a `member.create`/`member.set` whose `handles` holds a handle
  another member claims or an account has, or an `account.set` taking a handle a member claims or
  another account has, is acked `conflict` ("@x is taken"). Post author cards carry `handle` too.
- **Feeds** (`feeds.rs`, M7.4): `GET /feeds` lists your feeds and the ones other accounts share
  with you (`shared: true`, with `owner {handle, display_name}`). A feed is shared like a post:
  its `visibility` (`private`, `friends`, `followers`, `following`, `buckets`, `server`) is checked with the same rule.
  `GET /feeds/{id}/items?limit=&cursor=` evaluates it with the core filter (`chorus_core::feed`)
  over the posts **the caller** can read (never the owner's view), newest first, 1–100 a page
  (default 50) with an opaque `next_cursor`; one request looks at up to 2 000 posts, so a sparse
  feed may return a short page with a cursor. Names (`from:@kai`, groups, `list:"…"`) resolve
  against the owner's members, groups and lists; dates use the owner's latest UTC offset.
  `fronting:` (D-069) evaluates per reader: posts by the reader's own members against their
  front timeline, anyone else's only against the front states that reader's follow has revealed
  to them (the states their notifications showed, at the delayed, fuzzed times shown; nothing if
  the follow is gone or its ceiling hides the current front), so a feed never shows more or
  sooner than the notifications did. Clients show a note on such feeds. A feed you can't read
  is a 404. Tokens need `read:posts` and
  see only their own account's feeds and posts.
- **Journal** (`api_journal.rs`, M7; the caller's own account):
  - `GET /profiles/{member_id}` (`read:members`): `{member}` as `/members/{id}` (groups,
    fields), `relationships` from that member (`to_kind`, `to_id`, `to_label`, `to_name`, `note`,
    `type {id, name, inverse_name, symmetric}`), `stats {posts, entries, notes, first_post_at,
    last_post_at}`, and with `read:posts` too `highlights` (readable posts, `sort_key` order;
    `null` without the scope). Another account's member is a 404.
  - `GET /lists` (`read:posts`): `[{id, name, description, visibility, member_ids}]`.
  - `GET /lists/{id}/timeline?before=&limit=` (`read:posts`): posts by the list's members that
    the caller can read, newest first, 1–100 (default 50); `before` is an exclusive occurred-at.
- **Not yet:** `/timeline` and `POST /feeds/preview`.

## 4. Writes (non-sync)

```
POST /front/switch     (API tokens with write:front)  {entries, occurred_at?, note?, notify?}
POST /channels/{id}/messages (write:messages)          {authors?, text, format:"markup"|"plain"|"entities", entities?}
POST /import/pluralkit  multipart file | {token}       → job id
POST /exports           {kind:"full"}                  → 202 {id}   (409 conflict while one runs)
GET  /exports/latest    the account's latest export job (204 if none)
GET  /jobs/{id}         {id, kind, status, phase, done, total, bytes, error, file_name,
                         result_url, created_at, finished_at, expires_at}
DELETE /jobs/{id}       cancel it, or delete its finished file now          → 204
GET  /exports/{id}/download?key=…   the zip (no Authorization: the key is the credential; Range)
POST /invites           (admin) {kind, expires_in_s, max_uses}  → {url, qr_svg}
```

**The export bundle (D-068, `export_job.rs`)** — the DATA_MODEL §7 full backup as a background
job, for a device session or a token with `export`. `chorus-<handle>-<YYYYMMDD>.zip`, stored (no
compression, ZIP64 when needed): `README.txt`, `ops.jsonl` (exactly the direct export below),
`csv/<name>.csv` (the nine tables), `blobs/<sha256>` (every file the account's **own** ops point
at — attachments and their thumbnails, avatars, banners, custom emoji; never another account's,
even one visible in a shared space), and `manifest.json` (format 1: account, times, server
version, instance, `ops {count, sha256}`, `blobs [{hash, size, mime, filenames, file}]`,
`missing` and `damaged` hashes — a file that's gone or fails its hash is listed, not fatal).
`status`: `queued` → `running` (`phase` `ops`, `csv`, `blobs`, `zip`; `done`/`total` count ops,
then tables, then files; `bytes` written) → `done` | `failed` (`error` says why, e.g. not enough
free disk: a job refuses to leave less free space than twice its estimated size) | `cancelled`
| `expired`. One job per account at a time, two server-wide (others wait `queued`); a restart
fails running ones ("the server restarted; start it again"). `result_url` (while `done`) carries
a random key; the file is kept 24 h, or an hour after its first complete download. A finished
export is also an in-app notification (`kind: "export_ready"`), not a push. PluralKit, CSV-only
and SQLite jobs aren't served: those are the direct exports.

**Direct exports (M10.3):** `GET /exports/ops.jsonl`, `GET /exports/csv/{name}` (nine names
in DATA_MODEL.md §7.1), and `GET /exports/account.sqlite` accept a device session or an API
token with the `export` scope. Each contains only the principal account's authored data. They
return attachment filenames. Files come only in the bundle above.

`format: "markup"` parses Chorus markup with the same core parser the apps use.

**`POST /front/switch`** is implemented (`api_writes.rs`). The body is
`{entries, occurred_at?, note?, notify?}`:

- **Entries:** each entry is `{subject_type, subject_id}` or, for scripts, exactly one of
  `{member: "Kai"}`, `{group: "…"}` or `{state: "…"}`. Names match case-insensitively on name or
  display name, and an ambiguous name is a 400. Each entry may also give `level` (default `front`)
  and `is_primary`; the first fronting entry becomes primary if none is.
- **Switching out:** empty `entries`.
- **`occurred_at`:** a typed time (`TimeSource::User`), at most a minute ahead.

The switch becomes an ordinary `front.switch` op attributed to `token:<token id>` (or to `server`
from a session), so devices, followers, the stream and webhooks see it like a switch from the app.
The answer is `201 {switch_id, op_id, occurred_at}`, plus `front` if the caller may `read:front`.
**`POST /channels/{id}/messages`** is implemented (`messages.rs`): API tokens need
`write:messages`. `authors` are member ids or names of the account's own members (default: the
primary fronter, else a person account's own member); `format` is `markup` (default, parsed with
the apps' parser, mentioning your own members and the server's emoji), `plain` or `entities`;
`reply_to` must be a message you can read. It becomes an ordinary `message.send` op attributed to
`token:<id>` and answers `201 {message_id, op_id, message}`.

## 5. Blobs

```
HEAD /blobs/{sha256}                  200 complete | 206 + Upload-Offset partial | 404 missing or unreadable
PUT  /blobs/{sha256}                  Content-Range chunks (≤ 4 MB each); verifies hash on completion;
                                      200 if already complete and the caller uploaded or holds it;
                                      another account's finished blob answers like a new upload (202
                                      per chunk, 201, or 400 hash_mismatch at the end), comparing the
                                      bytes, and the caller then holds it (D-119); 403 into another
                                      account's unfinished upload
GET  /blobs/{sha256}                  supports Range; Cache-Control immutable
GET  /blobs/{sha256}?thumb=480        server-side fallback thumbnail if client didn't upload one
```

Max blob size: 100 MB default (Advanced). Access check: the caller must be able to read at least
one attachment/avatar referencing the blob. Post attachments (including thumbnail blobs) follow
the post's current private, follower, bucket, or server audience; deleting the post or ending a
follow revokes that access.

## 6. Live stream (SSE)

```
GET /stream?events=front,message,post,member&channels=…      Accept: text/event-stream
```

Events (one JSON object per `data:` line, `id:` = server seq for resume via `Last-Event-ID`):

```
event: front
data: {"type":"front","at":1790000000000,"front":[{"name":"Kai","member_id":"…","level":"front","is_primary":true}],"switch_id":"…"}

event: message
data: {"type":"message","id":"…","channel":"general","authors":["Kai"],"text":"…","at":…}
```

Also over WebSocket at `/stream/ws` for clients that prefer it (planned; not served yet).
Followers' streams carry only revealed follower-view events (NOTIFICATIONS.md §5). An
OBS/overlay example page lives at `GET /overlay/front?token=…&style=pill`, outside `/api/v1` (Advanced; token must be `read:front` +
`stream`): a static page that reads `/stream` with that token.

## 6a. Sync socket

```
GET /sync                             WebSocket upgrade; frames are JSON (SYNC.md §6)
```

The device signs in with its first frame (`Hello` with its session), within 15 s or the socket is
closed. Everything after that is SYNC.md's protocol: `Push`/`Ack`, `Pull`/`Ops`/`Caught`, `Ping`.
The upgrade request counts against the rate limit (§1) like any other request. The socket has
its own limits (OPS.md §9): per client address, sockets that haven't signed in yet (default 30;
more are refused with `429 too_many_connections`); per account, open sockets (default 20; the
oldest is closed with an error frame `too_many_connections`); per socket, frames (200 burst,
50/s; over it an error frame `rate_limited`, then the socket closes).

## 7. Webhooks

Owner accounts only. Configured in Settings → Advanced → Webhooks.

```
POST <url>
Content-Type: application/json
Chorus-Event: front.switch
Chorus-Delivery: <uuid>
Chorus-Signature: t=1790000000,v1=<hex HMAC-SHA256(secret, t + "." + body)>

{"event":"front.switch","account_id":"…","occurred_at":…,"data":{…same shape as SSE…}}
```

Events: `front.switch`, `front.review`, `member.created`, `member.updated`, `message.created`,
`post.created`, `follow.requested`. Retries: 1 m, 5 m, 30 m, 2 h, 12 h; then disabled with an
in-app notice. Where webhook URLs may point is `security.webhook_targets` (below).

Implemented (M10.2, `webhooks.rs`):

- **Endpoints** (signed-in devices only, never API tokens):
  - `GET/POST /webhooks {url, events}` → `{id, secret}`; the secret is shown once.
  - `PUT /webhooks/{id} {enabled?, events?}` and `DELETE /webhooks/{id}`.
  - `POST /webhooks/{id}/test` sends a `ping` and answers `{ok, status, error}`. A failed test
    never counts towards turning the webhook off.
- **Events so far:** `front.switch`, `member.created`, `member.updated`, `follow.requested`,
  `message.created` and `post.created`. The last two carry the account's *own* messages (in
  any space, private asides included) and posts, shaped like `GET /messages/{id}` and
  `GET /posts/{id}` for their author; other accounts' messages never trigger them.
  `front.review` comes later. The body also carries `delivery` (the same value as `Chorus-Delivery`).
- **Targets** (`security.webhook_targets` in `chorus.toml`, D-062), checked on save and before
  each delivery:
  - `internal` (default): RFC 1918, link-local, Tailscale's 100.64/10 and fd00::/8, bare names,
    and `.ts.net`/`.local`/`.lan`/`.internal`/`.home.arpa`. **Never loopback** (`localhost`,
    127/8, ::1), which would reach the host's own admin ports.
  - `public` (the Linux/VPS install): only globally routable addresses; no loopback, private,
    link-local (cloud metadata), CGNAT, multicast, documentation or NAT64 ranges.
  - `any`: everything (the legacy `webhooks_allow_external = true` means this).
  Names are resolved and every address must pass; the delivery then connects to the checked
  address (no DNS rebinding) and doesn't follow redirects.
- **Retries** are kept in memory, so a restart drops pending retries.

## 8. Admin

```
GET  /admin/accounts   /admin/devices   /admin/jobs
POST /admin/backup                      run a backup now
GET  /admin/health                      admin device session only; DB/WAL bytes, applied op count,
                                        connected devices, pending notifications, latest backup
                                        {at,size_bytes}, last recorded error, ntfy reachability,
                                        restore_window (below)
POST /admin/reconcile/close             admin device session only; close the restore window → 204
POST /admin/rebuild                     rebuild projections from the op log (maintenance mode)
```

`restore_window` is `{open, closes_at, devices: [{account, name, platform, last_seen_at,
back_at}]}` (SYNC.md §7.3, D-067): `open` while restored devices may still hand back ops with
their original authors, `closes_at` when it closes by itself, and every signed-in device (API
tokens aren't listed) with `back_at` set once it has said hello with the new epoch and an empty
outbox. Closed, it is `{open: false, closes_at: null, devices: []}`. The web app's *Your data*
page shows it to admins while open ("Restore in progress — N of M devices back", with Close).
Non-admins get 403 from both endpoints; API tokens can't call them.

Same operations exist on the CLI (`chorus-server --help`, OPS.md).

### 8a. Chorus Home (D-071, HOME.md)

Only on a Chorus Home install (`[server] home = true`), and only for requests from the PC itself
(a loopback peer with no `X-Forwarded-For`/`Forwarded`; anything else gets `403
not_this_computer`). A technical install behind Caddy never has these routes: every request
there looks local.

```
GET  /home            → {needs_setup, version, port, lan, lan_port, lan_address, pin, data_dir,
                         backup: {dir, keep_daily}}
POST /home/setup      → {code}   one-use system invite for the first account; 409 once one exists
PUT  /home/settings   {port?, lan?, keep_daily?}   admin device session → 202 {url}
```

`PUT /home/settings` rewrites `chorus.toml` (`listen = 127.0.0.1:<port>`, `lan_listen =
0.0.0.0:<port+1>` when `lan`) after checking the new file loads, answers, then restarts the server
in place with it (a fresh runtime, so nothing of the old run is left listening).

## 9. Outside the API

```
GET /download/android                 the published APK (no auth; D-060), 404 if none
GET /overlay/front                    the OBS overlay page (§6)
```

Everything else outside `/api/v1` is the web app (its files, and `index.html` for any other path).

`python scripts/api-check.py` (run by `verify.py`) fails when a route in `app.rs` isn't in this
file; `--list` prints the router's routes.


### Ephemeral typing on the sync WebSocket (D-087)

`/api/v1/sync` accepts `{"t":"typing","channel":"<id>","member":null,"audience":"all","state":"start"}`
after hello. `member` is an own live member ID or null; audience is `all` or `system` (also used
for chosen-member drafts), state is `start` or `stop`. Relays contain `t`, `channel`, `account`,
`member`, `state`, and `until` (server milliseconds; start = now + 6000). See SYNC §6 for validation,
view/scope checks, the 2-second per-device/channel throttle and disconnect stops.

Same-account other devices receive it. Cross-account delivery requires a non-internal space,
`audience: "all"`, and account-level `share_typing: true` (pref.set with `device: ""`, default off).
Internal typing stays own-account even when a channel has guests. Typing is never persisted,
exported, sent through notifications, SSE or webhooks, or replayed. HTTP push/pull fallback
ignores typing (the current router does not implement those fallback routes).

### UI11b search view (D-114)

`GET /search/messages` additionally accepts `current=true` and `channel_id=<exact id>`.
All searches use chronological newest-first ordering and rechecks current permissions, Body
front/private chats, inherited thread parents, message audience and clear cutoffs before paging.
Each item is a rich message with `context_before` and `context_after` (one visible message or
null). Context is chronological in the same channel and ignores the search terms. Cursors bind
the scope/query; only returned visible anchors can appear. It exposes no raw match totals or
hidden rank scores. Every message search now enforces this view, including callers omitting `current` or sending false. `channel_id` is an exact
additional restriction, intersecting `in:` rather than broadening it.

`GET /search/posts` additionally accepts `saved=true`, narrowing through this account's
account-wide `bookmark:<id> = true` preferences before paging and the ordinary live post audience
check. Cursors bind this flag. Saved preferences grant no post/blob access.

D-114: blob GET/HEAD return the same 404 for missing and unreadable hashes; a hash retained
from a search card does not bypass current recipient-front/channel permission checks. Upload
ownership failures remain 403. Uploaders retain their existing own-file access.
