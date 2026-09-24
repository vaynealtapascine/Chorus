# Chorus — entities and their properties

A designer's reference (owner request, 2026-09-24): every kind of thing in Chorus and what it
carries, in plain value types. It is derived from the database schema (`crates/chorus-server/
migrations/`, the source of truth) and DATA_MODEL.md, NOTIFICATIONS.md and CLIENTS.md. Corrections
and additions are welcome: write them next to the entity (or in Figma) and an agent turns them into
a decision (DECISIONS.md) and a migration.

## Value types

| Type | Means | Example |
| --- | --- | --- |
| **id** | A unique id made on the device that created the thing. "→ X" says what it points to. | `0192f8c2-7d1e-…` |
| **text** | Plain text. "short" = a name or label (a line); "long" = paragraphs. | |
| **rich text** | Text plus formatting spans (bold, italic, links, mentions, custom emoji, spoilers, code, quotes). See *Shapes*. | |
| **number** | A whole number unless noted. | |
| **yes/no** | A switch. | |
| **time** | A moment (date + time, stored in UTC; shown in the viewer's time zone). | |
| **date** | A calendar date; a birthday may have no year. | `1999-04-02`, `--04-02` |
| **duration** | Seconds. | |
| **colour** | `#RRGGBB`; adapted for light and dark themes (DESIGN §2). | `#c0694e` |
| **image** | An uploaded file (avatar, banner, attachment), stored by its hash. | |
| **emoji** | A Unicode emoji or a server custom emoji. | `🌌`, `custom:<id>` |
| **one of** | A fixed choice; the allowed values are listed. | |
| **list of X** | An ordered list. | |
| **visibility** | Who can see it. See *Shapes*. | `{"mode":"followers"}` |

Almost everything can be **deleted** (it goes to Trash with a *deleted at* time and can be
restored, D-053), and most things carry *created at*. Those two are left out below unless they
matter to a screen.

---

## 1. People and devices

### Account — a system, or a single person
| Property | Type | Notes |
| --- | --- | --- |
| kind | one of: `system`, `person` | A person account has one "self" member and no switcher. |
| handle | text, short | `@stars` — for mentions across spaces; unique on the server. |
| display name | text, short | |
| avatar | image | |
| is admin | yes/no | Server admins (the first account, and whoever they choose). |
| settings | settings (see *Shapes*) | Default follower ceiling, notification prefs, etc. |

### System — the profile of a system account (one per system account)
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | "The Stars" |
| tag | text, short | Shown after member names in shared spaces ("Kai · Stars"). |
| description | rich text | |
| colour | colour | |
| time zone | text | IANA name (`Asia/Manila`), for "today" and daily stats. |
| terminology | terminology (see *Shapes*) | Custom words for member / front / switch / subsystem / co-con / present. |

### Device — a phone or browser signed in to an account
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | "Pixel 9", "Firefox on desk PC" |
| platform | one of: `android`, `web`, `cli` | |
| last seen | time | |
| push | on/off (an endpoint) | Whether this device gets notifications. |
| signed out | time | A lost device can be signed out from another. |

### Invite — a one-use link or QR
| Property | Type | Notes |
| --- | --- | --- |
| kind | one of: `system`, `person`, `device` | `device` adds a device to an existing account. |
| expires | time | 1 day for devices, 7 days by default for new accounts. |
| uses / max uses | number | |
| (Chorus Home) wifi link | text | `chorus://<address>/i/<code>#pin=…` for phones on the home wifi. |

### API token — for scripts, dashboards and stream overlays
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | |
| scopes | list of one of: `read:front`, `read:members`, `read:messages`, `read:posts`, `write:front`, `write:messages`, `export`, … | API.md §2.3. |
| last used | time | |
| revoked | time | The secret is shown once, at creation. |

### Webhook
| Property | Type | Notes |
| --- | --- | --- |
| url | text | |
| events | list of event names | e.g. `front.switch`, `member.created` |
| enabled | yes/no | |
| last status / last error | number / text | For "is it working?". |

---

## 2. Members and groups

### Member
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | Required. |
| display name | text, short | Shown instead of name where set. |
| pronouns | text, short | |
| description | rich text | The bio. |
| colour | colour | Used for name ink, avatar ring, tints. |
| avatar | image | |
| banner | image | Profile header. |
| birthday | date | Year optional. |
| sigils | list of emoji/short text | `🌌` — typed before a message to speak as this member; also the avatar fallback. |
| proxy tags | list of {prefix, suffix} | `k:text` or `[text]` — PluralKit-style. |
| pinned post | id → Post | Shown first on the profile. |
| order | (a sort position) | Manual ordering in lists. |
| is self | yes/no | The one member of a person account. |
| is locked | yes/no | Behind a PIN on the device. |
| visibility | visibility | Who outside the system can see this member at all. |
| field visibility | per custom field: visibility | |
| notification policy | member policy (see *Shapes*) | Announce to everyone / nobody / chosen buckets; announce leaving. |
| short id | text | 7 letters; Advanced only (D-049). |
| PluralKit id | text | When imported. |
| archived | time | Archived members hide from the switcher and lists. |
| custom field values | per field: value | See *Custom field*. |
| groups | list of → Group | Via group membership. |

### Group (or subsystem)
| Property | Type | Notes |
| --- | --- | --- |
| kind | one of: `group`, `subsystem` | Subsystems nest and can front as a unit. |
| parent | id → Group | Nesting (cycles are ignored). |
| name | text, short | |
| description | rich text | |
| colour | colour | |
| icon | emoji | |
| avatar | image | |
| can front | yes/no | Whether it can be switched in as a whole. |
| visibility | visibility | |
| members | list of → Member | |

### Custom field (definition) — set up once, filled in per member
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | "Age", "Role", "Likes" |
| type | one of: `text`, `long_text`, `number`, `date`, `select`, `multi_select`, `boolean`, `color`, `url`, `member_ref`, `rating` | |
| options | depends on type | Choices for selects; min/max for numbers and ratings. |
| default visibility | visibility | |
| order | (a sort position) | |

### Custom state — e.g. "tired", "nonverbal", "blurry"
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | |
| description | text | |
| colour | colour | |
| icon | emoji | |

A state can be "fronting" like a member (subject type `state`).

---

## 3. Fronting

**Subject** = what can front: a member, a group/subsystem, or a custom state.
**Level** = one of `front` (fronting), `cocon` (co-conscious), `present` (around, not fronting).

### Switch — one change to who's fronting (the switch log)
| Property | Type | Notes |
| --- | --- | --- |
| kind | one of: `switch` (replace all), `add`, `remove`, `update` | |
| when | time | Can be backdated. |
| entries | list of front entries (see *Shapes*) | What this switch changed. |
| resulting front | list of front entries | Who was fronting afterwards, in order; one may be primary. |
| note | text | |
| notify | one of: `default`, `silent`, `now`, `extra_delay` | How followers hear about it. |
| made offline | yes/no | |
| from device | → Device | |
| retracted / amended | yes/no | Undo, or edited later (time or entries). |

### Front interval (derived) — one continuous stretch of one subject at one level
| Property | Type |
| --- | --- |
| subject, level, is primary, position (fronting order) | as above |
| from / until | time (until empty = still going) |

### Daily front time (derived) — for Insights
| Property | Type |
| --- | --- |
| day (local), subject, level | |
| seconds, seconds as primary | duration |

### Review card — two switches that probably meant the same thing
| Property | Type | Notes |
| --- | --- | --- |
| switch A, switch B | → Switch | Made on two devices at nearly the same time. |
| resolution | one of: `keep_both`, `keep_a`, `keep_b`, `merge` | Empty until someone decides. |

---

## 4. Chat

### Space — a place with channels
| Property | Type | Notes |
| --- | --- | --- |
| kind | one of: `internal` (the system's own), `shared` (with other accounts), `dm` | |
| owner | → Account | |
| name, description | text | |
| icon | emoji | |
| colour | colour | |
| roles | list of roles | For channel permissions (being built, M5.10). |
| settings | settings | |
| members | list of {account, role} | role: one of `owner`, `admin`, `member`, `read_only`. |

### Channel
| Property | Type | Notes |
| --- | --- | --- |
| space | → Space | |
| kind | one of: `text`, `thread` (under a message), `member_dm` (between members of one system) | |
| category | text, short | A label to group channels. |
| name, topic | text | |
| icon | emoji | |
| colour | colour | |
| participants | list of → Member | For member DMs. |
| settings | settings | Autoproxy, sticky speaker, slow mode, lock. |
| archived | time | |
| permissions | list of {role or account, allow, deny} | allow/deny each a list of: `view`, `send`, `react`, `thread`, `pin`, `manage`. |

### Message
| Property | Type | Notes |
| --- | --- | --- |
| channel | → Channel | |
| authors | list of → Member | One or several ("Kai & Moss"); segments can split a message between speakers. |
| segments | list of {text range, authors} | Who said which part (D-045). |
| kind | one of: `text`, `voice`, `video`, `system` | Voice/video are later (L1). |
| text | rich text | |
| content warning | text, short | The body stays collapsed until tapped. |
| reply to | → Message | May be in another channel. |
| quote | a quoted part of another message | |
| forwarded | list of message snapshots | |
| visibility | visibility (message modes) | Everyone in the space / chosen members / only my system (an aside). |
| attachments | list of → Attachment | |
| reactions | list of {emoji, member} | |
| mentions | list of {member / group / account / @front} | |
| when | time | |
| sent offline | yes/no | Shows "sent offline · synced 14:32". |
| edited | time + number of revisions | Revisions are kept. |
| pinned | time + by which member | |
| thread | → Channel | If it has one. |

### Attachment
| Property | Type | Notes |
| --- | --- | --- |
| file | image or file | |
| filename | text | |
| type | text | `image/png`, … |
| size | number (bytes) | |
| width, height | number | Images and video. |
| duration | number (ms) | Voice/video. |
| thumbnail | image | |
| alt text | text | |
| is spoiler | yes/no | Hidden until tapped. |

### Custom emoji (server-wide)
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | `:blobwave:` |
| aliases | list of text | |
| category | text, short | |
| image | image | 128 px; may be animated. |
| retired | time | Old messages keep showing it. |

### Read state — where each reader is in a channel
| Property | Type | Notes |
| --- | --- | --- |
| channel, account | | |
| reader member | → Member | Optional: per-member read positions. |
| last read message | → Message + time | For the "new messages" jump pill. |

### Draft — an unsent message (syncs between devices)
| Property | Type |
| --- | --- |
| where (channel/post), authors, text | |
| updated | time |

---

## 5. Profiles and journals

### Post — a note or a long-form journal entry
| Property | Type | Notes |
| --- | --- | --- |
| kind | one of: `note`, `entry` | Entries have titles. |
| authors | list of → Member | |
| title | text, short | Entries. |
| text | rich text | |
| mood | text, short | |
| tags | list of text | |
| content warning | text, short | |
| reply to | → Post | May be another account's (if readable). |
| quote / repost of | → Post | |
| who was fronting | list of front entries | A snapshot when it was written; hidden from other accounts. |
| visibility | visibility | Only us / followers / chosen buckets / everyone on the server. |
| attachments | list of → Attachment | |
| reactions | list of {emoji, member} | Readers see who reacted. |
| when, edited, sent offline | as for messages | |

### Highlight — a post curated onto a member's profile
| Property | Type |
| --- | --- |
| profile member | → Member |
| post | → Post |
| added by member | → Member |
| order | (a sort position) |

### Relationship — between members, or to someone outside
| Property | Type | Notes |
| --- | --- | --- |
| from | → Member | |
| to | → Member (any system), → Account, or a label | to kind: one of `member`, `account`, `external`. |
| type | → Relationship type | |
| note | text | |
| visibility | visibility | |

### Relationship type
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | "sibling", "partner", "protector" |
| inverse name | text, short | "protected by" |
| is symmetric | yes/no | Same both ways. |
| colour | colour | |
| icon | emoji | |

### List — a curated list of members (and accounts)
| Property | Type |
| --- | --- |
| name, description | text |
| visibility | visibility |
| members | list of → Member |

### Feed — a saved filter over posts and messages
| Property | Type | Notes |
| --- | --- | --- |
| name, description | text | |
| query | text | The filter language: `from:@kai tag:art fronting:moss after:2026-01-01 …` (SPEC §6.4). |
| visibility | visibility | Shared feeds open for followers; a `fronting:` filter shows a warning (D-069). |

### Stage — a saved screenshot setup
| Property | Type | Notes |
| --- | --- | --- |
| name | text, short | |
| definition | stage definition | Selected messages, context-only/hidden, reply depth, style preset, redaction, fake names and times (SPEC §7). |

---

## 6. Sharing and notifications

### Follow — one account following another
| Property | Type | Notes |
| --- | --- | --- |
| follower, followed | → Account | |
| status | one of: `requested`, `active`, `declined`, `ended`, `blocked` | |
| ceiling | ceiling (see *Shapes*) | Set by the followed account: the most this follower may see. |
| prefs | follower prefs (see *Shapes*) | Set by the follower: what they want to hear. Private to them. |

### Bucket — a named group of followers ("close friends", "partners")
| Property | Type |
| --- | --- |
| name | text, short |
| colour | colour |
| ceiling | ceiling (empty = the account default) |
| followers | list of → Account |

### Follower view (derived) — what a follower may know about who's fronting, and since when
| Property | Type | Notes |
| --- | --- | --- |
| who's around | list of front entries | Already privacy-filtered. |
| shown since | time | Fuzzed per the ceiling. |
| revealed at | time | When it became visible (after the delay). |
| history, stats | lists | Only if the ceiling shares them. |

### Notification — an inbox item and a push
| Property | Type | Notes |
| --- | --- | --- |
| kind | one of: `switch`, `digest`, `mention`, `dm`, `reply`, `follow`, `review` | |
| content | title + text | Already filtered for the recipient. |
| due | time | After settle + delay + quiet hours. |
| shown time | time | The (fuzzed) time the recipient sees. |
| delivered / cancelled / folded into | time / time / → Notification | A burst of switches folds into one. |

### Pref — one account setting (D-063)
| Property | Type | Notes |
| --- | --- | --- |
| key | text | e.g. `notify_chat`, `own_switch`, `reply`, `reply_as_mentioned`, `chat.cw_auto_expand`, `chat.segment_parsing`, `follow_ceiling`, `share_history`, `share_stats`, `notify_member:<id>` |
| device | → Device or empty | Empty = the whole account. |
| value | depends on key | |

---

## 7. Shapes (the structured values used above)

**Visibility**
- `private` — only this account (and, for members, "only us")
- `followers` — anyone following this account (within their ceiling)
- `buckets` + list of → Bucket — chosen groups of followers
- `server` — everyone signed in to this server
- messages only: `members` + list of → Member (soft, inside the system); `system_only` (an aside
  in a shared space)

**Rich text** = text + a list of spans `{type, offset, length}` where type is one of: `bold`,
`italic`, `underline`, `strikethrough`, `spoiler`, `code`, `pre` (with language), `blockquote`,
`expandable_blockquote`, `url`, `text_link` (with url), `mention` (member / group / account /
`front`, with id), `custom_emoji` (with emoji id).

**Front entry** = `{subject type: member|group|state, subject, level: front|cocon|present, is
primary}`; lists are in fronting order.

**Terminology** = the words for: member(s), front/fronting, switch(es), subsystem(s), co-con,
present — singular and plural where it matters.

**Member notification policy** = announce: everyone / nobody / chosen buckets; announce leaving:
yes/no; an optional extra delay range.

**Ceiling** (what a follower may see; presets Close, Gentle, Private, Digest, Off):
switch notifications on/off · levels shown (front, co-con, present) · hidden members omitted or
shown as "someone" · announce leaving · delay (min–max) · extra delay (min–max) · allow "now" ·
time shown (exact / rounded to N min / jittered / part of day / hidden) · stacking (collapse /
sequence) · digest only · max per day · quiet hours (from–to) · share current front · share
history · share stats.

**Follower prefs** (what the follower wants): on/off · subjects (all / only these / all except) ·
levels · switch-outs · delivery (each / hourly digest / daily digest) · digest time · quiet hours
and what to do in them (hold / drop) · mute until · notification channel.

---

## Appendix: internal records (no screens)

For completeness; these are bookkeeping, never shown directly: the **op log** (every change, the
source of truth: id, kind, scope, payload, clock stamps, device, timezone, status), **sessions**,
**sign-in nonces**, **blobs** (stored files by hash), **scope access**, **item–attachment
links**, **message/post revisions**, **follower front log**, **server meta** (schema version,
instance id), and the search indexes.
