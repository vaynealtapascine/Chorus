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
→ 200 { "code":"…", "url":"https://chorus.…/i/<code>", "expires_at":… }
```

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
GET  /messages/{id}/thread
GET  /search/messages?q=&in=&from=&before=&after=&has=
GET  /pins?channel=

GET  /posts?author=&kind=&before=&limit=
GET  /posts/{id}                           with replies ?depth=
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

## 4. Writes (non-sync)

```
POST /front/switch     (API tokens with write:front)  {entries, occurred_at?, note?, notify?}
POST /channels/{id}/messages (write:messages)          {authors?, text, format:"markup"|"plain"|"entities", entities?}
POST /import/pluralkit  multipart file | {token}       → job id
POST /exports           {kind:"full"|"csv"|"sqlite"|"pluralkit", from?, to?} → job id
GET  /jobs/{id}         progress, result URL
POST /invites           (admin) {kind, expires_in_s, max_uses}  → {url, qr_svg}
```

`format: "markup"` parses Chorus markup with the same core parser the apps use.

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

## 8. Admin

```
GET  /admin/accounts   /admin/devices   /admin/jobs
POST /admin/backup                      run a backup now
GET  /admin/health                      db size, WAL size, op count, last backup, ntfy reachability
POST /admin/rebuild                     rebuild projections from the op log (maintenance mode)
```

Same operations exist on the CLI (`chorus-server --help`, OPS.md).
