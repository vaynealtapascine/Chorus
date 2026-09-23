# Chorus — API

Base: `https://chorus.<domain>/api/v1`. JSON, UTF-8. Times are ms UTC integers (plus ISO strings in
export/CSV endpoints). IDs are UUIDv7 strings. All writes from apps go through **sync** (ops); REST
is for reads, auth, blobs, exports and third-party scripts.

---

## 1. Conventions

- Errors: `{"error":{"code":"forbidden","message":"…","retry":false}}` with a matching HTTP status.
  Codes: `bad_request`, `unauthenticated`, `forbidden`, `not_found`, `conflict`, `too_large`,
  `rate_limited`, `unsupported_version`, `internal`.
- Pagination: cursor-based, `?limit=100&before=<id>` / `after=<id>`; responses carry
  `{"items":[…],"next":"<cursor>|null"}`.
- Versioning: `/api/v1` is stable. Additive changes only; breaking ones get `/api/v2`. The sync
  protocol is versioned via `core` / `core_min` (SYNC.md §6.2).
- Rate limits (per token, Advanced-configurable): 50 req/s burst, 10 req/s sustained.
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

Link another device of the same account (any enrolled device; one use, 1 day):

```
POST /devices/invite            Authorization: Bearer <session>
→ 200 { "code":"…", "url":"https://chorus.…/i/<code>", "qr_svg":"<svg…>", "expires_at":… }
```

`qr_svg` encodes the URL (`qr.rs`: byte mode, level M, versions 1–10, no dependency) so a phone
can scan it from the web app. `CHORUS_QR_SAMPLES=<dir> cargo test -p chorus-server --lib qr`
followed by `python scripts/qr-check.py <dir>` decodes samples with OpenCV.

`GET /push/vapid` → `{public_key}` is the server's VAPID key for browsers' `pushManager.subscribe`
(D-061). Browsers then register below like any device.

Register this device for push (UnifiedPush endpoint + Web Push keys, NOTIFICATIONS.md §1):

```
PUT    /devices/push {endpoint, p256dh: <b64url uncompressed P-256>, auth: <b64url 16 bytes>}  → 204
DELETE /devices/push                                                                          → 204
```

Payloads are RFC 8291 `aes128gcm`, one record, ≤ 3 KB plaintext (else `{"t":"sync"}`). A 404/410
from the endpoint clears the registration.

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
`read:messages`, `stream`, `write:front`, `export` (see §4);
`POST /tokens {name, scopes}` → `{id, token}` (shown once), `GET /tokens`, `DELETE /tokens/{id}` —
from a signed-in device only (tokens can't mint tokens). Reads: `GET /front`, `/front/switches`,
`/front/intervals`, `/members`. `GET /stream` sends `event: front` (current front first, then each
change); EventSource clients pass `?token=`. `/overlay/front?token=…` is a transparent OBS pill.

## 3. Read endpoints

```
GET  /me                                   account, devices, capabilities
GET  /server                               name, version, instance_id, core_min, features

GET  /members?include_archived=0
GET  /members/{id}
GET  /groups                               tree with effective_parent_id
GET  /fields                               field definitions
GET  /states                               custom front states

GET  /front                                current front (resolved names, levels, since)
GET  /front/switches?from=&to=&limit=
GET  /front/intervals?from=&to=&subject=&level=
GET  /front/daily?from=YYYY-MM-DD&to=…&level=
GET  /front/reviews?open=1

GET  /spaces
GET  /spaces/{id}/channels
GET  /channels/{id}/messages?before=&after=&around=&limit=
GET  /messages/{id}                        incl. revisions if ?revisions=1
  The current read-only history view returns the same scoped message fields as search;
  revisions are reserved for a later API pass.
GET  /messages/{id}/thread
GET  /search/messages?q=&in=&from=&before=&after=&has=
  → {items:[{id,channel_id,space_id,account_id,occurred_at,text,cw,visibility,authors}]}
  Uses FTS5; `in` accepts a channel id/name, `from` a member id/name, before/after are
  exclusive epoch milliseconds, and `has` is attachment/image/file. Results are capped at
  100 and limited to accessible spaces plus public or own messages. API tokens need
  `read:messages`; device sessions inherit access.
GET  /pins?channel=

GET  /posts?author=&kind=&before=&limit=
GET  /posts/{id}                           with replies ?depth=
  Device-session reads now return posts visible to the caller: own posts, server-visible posts,
  posts shared with active followers, and posts for an assigned, live bucket. `account=` narrows
  the list to one account. `before` is an exclusive occurred-at millisecond value; `limit` is
  clamped to 1–100. Detail replies are filtered by the same rule (`depth` 0–3, at most 50 per
  level). Hidden/deleted posts return 404. Responses omit `front_snapshot` and remove unreadable
  parent/repost links. Authors include ordered member ids and small author cards. API tokens do
  not use these cross-account routes.
GET  /timeline?before=&limit=              combined system timeline
GET  /profiles/{member_id}                 profile bundle (fields, stats, highlights, relationships)
GET  /lists  /lists/{id}/timeline
GET  /feeds  /feeds/{id}/items?before=     evaluates the feed query
POST /feeds/preview {query}                → parsed AST + first 20 items (for the editor)

GET  /emoji                                the server-wide custom emoji set
GET  /accounts/{id}/view                   follower view of another account (privacy-filtered, §5 NOTIFICATIONS)
GET  /follows                               following + followers ({following:[…], followers:[…]}, open ones)
POST /follows {target:"@handle"|account_id}  ask to follow (201; 200 with the open follow's id if one exists)
PUT  /follows/{id}/prefs {…}                 the follower's own prefs (NOTIFICATIONS.md §4)
DELETE /follows/{id}                         unfollow (follower) or remove (target)
GET  /notifications?before=
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
- **Other accounts:** `/accounts/{id}/view`, `/follows`, `/notifications`.
- `/front/intervals` also takes `subject` (an id) and `level`.
- **Shared spaces and DMs** (M6.2, `spaces.rs`, signed-in devices only):
  - `GET /spaces` returns your spaces with the accounts in each.
  - `POST /spaces {kind: "shared"|"dm", name?, accounts: [account ids]}` answers
    `201 {id}`, or `200 {id}` when that DM already exists. It works only with accounts connected
    to you by an active follow, in either direction.
  - `POST /spaces/{id}/members {accounts}` is for a shared space's owner only.
  - `DELETE /spaces/{id}/members/me` leaves. The owner of a shared space can't leave it, and
    nobody can leave their home space.
  - `GET /spaces/{id}/authors` returns `{accounts, members}`. `members` holds author cards (name,
    display name, pronouns, colour, sigils, avatar) for other accounts' members who wrote a message
    everyone in the space can read.
  - A space you aren't in is a 404.
- **Not yet:** message list/thread reads, profiles, feeds and insights, which come with
  M5.7/M5.8/M7/M10.1.

## 4. Writes (non-sync)

```
POST /front/switch     (API tokens with write:front)  {entries, occurred_at?, note?, notify?}
POST /channels/{id}/messages (write:messages)          {authors?, text, format:"markup"|"plain"|"entities", entities?}
POST /import/pluralkit  multipart file | {token}       → job id
POST /exports           {kind:"full"|"csv"|"sqlite"|"pluralkit", from?, to?} → job id
GET  /jobs/{id}         progress, result URL
POST /invites           (admin) {kind, expires_in_s, max_uses}  → {url, qr_svg}
```

**Direct exports (M10.3):** `GET /exports/ops.jsonl`, `GET /exports/csv/{name}` (seven names
in DATA_MODEL.md §7.1), and `GET /exports/account.sqlite` accept a device session or an API
token with the `export` scope. Each contains only the principal account's authored data. They
return attachment filenames. The `POST /exports` job protocol above is planned for larger
archives and is not yet served.

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
`POST /channels/{id}/messages` waits for M5.7 visibility.

## 5. Blobs

```
HEAD /blobs/{sha256}                  200 complete | 206 + Upload-Offset partial | 404
PUT  /blobs/{sha256}                  Content-Range chunks (≤ 4 MB each); verifies hash on completion
GET  /blobs/{sha256}                  supports Range; Cache-Control immutable
GET  /blobs/{sha256}?thumb=480        server-side fallback thumbnail if client didn't upload one
```

Max blob size: 100 MB default (Advanced). Access check: the caller must be able to read at least
one attachment/avatar referencing the blob.

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

Also over WebSocket at `/stream/ws` for clients that prefer it. Followers' streams carry only
revealed follower-view events (NOTIFICATIONS.md §5). An OBS/overlay example page lives at
`/overlay/front?token=…&style=pill` (Advanced; token must be `read:front` + `stream`).

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
in-app notice. Webhook URLs may point outside the tailnet only if `webhooks.allow_external` is on.

Implemented (M10.2, `webhooks.rs`):

- **Endpoints** (signed-in devices only, never API tokens):
  - `GET/POST /webhooks {url, events}` → `{id, secret}`; the secret is shown once.
  - `PUT /webhooks/{id} {enabled?, events?}` and `DELETE /webhooks/{id}`.
  - `POST /webhooks/{id}/test` sends a `ping` and answers `{ok, status, error}`. A failed test
    never counts towards turning the webhook off.
- **Events so far:** `front.switch`, `member.created`, `member.updated`, `follow.requested`.
  `message.created` waits for M5.7 visibility, `post.created` for M7, and `front.review` for
  later. The body also carries `delivery` (the same value as `Chorus-Delivery`).
- **Internal URLs** are loopback, RFC 1918, link-local, Tailscale's 100.64/10 and fd00::/8, bare
  names, and `.ts.net`/`.local`/`.lan`/`.internal`/`.home.arpa`. Any other name is resolved and
  must resolve only to internal addresses; this is checked on save and before each delivery. The
  switch is `security.webhooks_allow_external` in `chorus.toml`.
- **Retries** are kept in memory, so a restart drops pending retries.

## 8. Admin

```
GET  /admin/accounts   /admin/devices   /admin/jobs
POST /admin/backup                      run a backup now
GET  /admin/health                      db size, WAL size, op count, last backup, ntfy reachability
POST /admin/rebuild                     rebuild projections from the op log (maintenance mode)
```

Same operations exist on the CLI (`chorus-server --help`, OPS.md).
