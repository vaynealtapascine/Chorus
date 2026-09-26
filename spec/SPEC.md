# PSDS — Plural Systems Data Specification

**Draft 0.1.0 · 2026-09-26 · working title, not yet named (DESIGN_NOTES D0)**

An open format for storing and moving the data of plural systems: members, groups and
subsystems, custom fields, front history, journals, chat, and the files they use. The schema
is `proto/psds/v1/*.proto`; this document says what the fields mean and what producers and
consumers must do. Where this text and the `.proto` comments disagree, this text wins until the
next draft fixes one of them.

---

## 1. Introduction

### 1.1 Purpose

Plural systems keep years of their lives in apps: who they are, who was fronting when, what they
wrote to each other. Apps close (Simply Plural stopped in July 2026), change, or stop fitting.
PSDS lets that data leave one app and arrive in another **without losing meaning**, and lets an
app keep its own data in a documented, durable shape.

Goals:

1. **Lossless where it matters**: everything an app can express about members, fronting and
   writing has a place, and anything that doesn't is kept (`ext`) and reported (warnings).
2. **Mergeable**: ids are stable, so importing a newer export of the same system updates it
   instead of duplicating it.
3. **Formal**: a schema tools can generate code from, requirements with RFC 2119 keywords,
   validation rules, and conformance classes.
4. **Compatible with OpenPlural v0.1**: every OpenPlural file converts to PSDS without loss, and
   PSDS converts to OpenPlural with declared warnings (Appendix A).
5. **Private by default**: nothing becomes more visible by being exported or imported.

### 1.2 Scope

In scope: a system's own data — its profile, members, custom states, groups and subsystems,
labels, custom fields, sharing buckets, front history, posts (journals, notes, board messages),
reactions, relationships, contacts, polls, chat channels and messages, custom emoji, and files.

Out of scope for 0.1: accounts, devices, sessions, API tokens, webhooks, follows and notification
settings (service state, owned by the server); sync protocols and operation logs (see DESIGN_NOTES
D11); reminders, habits, safety plans and Discord proxy configuration (future modules, §12.3).

### 1.3 Terms

- **System**: a plural system, or a singlet using the same tools.
- **Member**: someone in a system (alter, headmate, part…). Apps may call them something else
  (`Terminology`).
- **State**: something that can front but is not a member: a custom front or status.
- **Subject**: whatever a front, a label or a field is about: a member, a group, a state, or the
  system (`SubjectRef`).
- **Span**: one continuous stretch of one subject at one front level (`FrontSpan`).
- **Switch**: a recorded change of front at a moment (`FrontSwitch`).
- **Producer**: software that writes a PSDS file. **Consumer**: software that reads one.
- **Module**: a named set of record types that a file may or may not contain (§3.4).

### 1.4 Requirements language

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY
and OPTIONAL are to be interpreted as described in BCP 14 (RFC 2119, RFC 8174) when, and only
when, they appear in all capitals.

---

## 2. Conventions

### 2.1 Schema and naming

The proto3 files under `proto/psds/v1/` are normative for field names, types and numbers.
Package `psds.v1`; field names are `lower_snake_case`. Field numbers follow one convention in
every record: 1–9 the common header (§2.4), 10–899 the record's own fields, 900 `source_refs`,
901 `ext`. Numbers are never reused within a major version.

### 2.2 Identifiers

- Every record's `id` MUST be a UUID (RFC 9562) in lowercase hyphenated form.
- Producers SHOULD use UUIDv7 for records they create.
- An id MUST NOT change between exports of the same record. This is what makes re-import a merge
  (§9.5) and not a copy.
- A producer converting records that have no UUID (numeric ids, Mongo ObjectIds, PluralKit short
  ids) SHOULD derive a UUIDv5 in the PSDS namespace `ae3a509b-4cf9-4cb2-868a-a67de27591a5` over
  `<app>|<collection>|<source id>` (e.g. `pluralkit|members|kaiabc` →
  `613b4f78-bbdd-5a20-8466-d8342b2dec99`), and keep the source id in `source_refs`. Converting the
  same foreign export twice then yields the same ids.
- Ids of options inside a record (`FieldOption.id`, `PollOption.id`) need only be unique within
  that record.
- All record ids in a file MUST be unique across record types.

### 2.3 Time

- Instants are `google.protobuf.Timestamp`: RFC 3339 with `Z` in JSON. Producers SHOULD keep at
  least millisecond precision where the source has it.
- Where local time matters (a span, a switch, a post, a message), `*utc_offset_minutes` MAY record
  the offset in force at that moment. `System.timezone` (IANA) gives the default for local days.
- Calendar dates with unknown parts are `PartialDate`, where 0 means unknown:
  `{month: 4, day: 2}` is 2 April with no year.

### 2.4 The common header, deletion and archiving

Every record has `id` (1), `created_at` (2), `updated_at` (3) and `deleted_at` (4); records that
can be archived also have `archived_at` (5).

- `created_at` is when the record came into being in its first app. `updated_at` is the last
  change to any field; producers SHOULD set it when they know it (merges depend on it).
- **Deleted** (`deleted_at` set) means removed by the user but restorable: a tombstone. A FULL
  export SHOULD include tombstones so merges can delete (§9.5). Consumers MUST NOT show a deleted
  record as live.
- **Archived** (`archived_at` set) means kept and visible in its archive, but out of the way
  (a dormant member, an old channel). It is not deletion.
- A consumer that has no archive or trash MUST NOT treat either as permission to drop the
  record's content from what it imports without a warning.

### 2.5 Small value types

- **Colour**: `#rrggbb`. Producers MUST write six lowercase hex digits; consumers SHOULD accept
  uppercase and `#rgb`.
- **Sort key**: records sort by `sort_key` compared by Unicode code point, ties by `id`. Keys are
  fractional indexes (a new key can always be made between two others). A producer converting an
  integer order SHOULD write fixed-width zero-padded decimals (`"0000000007"`).
- **Emoji**: a Unicode emoji sequence, or `psds:emoji/<id>` for a `CustomEmoji`.

### 2.6 Text

`RichText` holds exactly one of:

- `plain`: no formatting.
- `markdown`: **PSDS markdown** = CommonMark 0.31.2 with the GFM extensions for tables,
  strikethrough, task lists and autolinks, plus:
  - `||text||` is a spoiler;
  - link targets with the `psds:` scheme refer to records: `[@Kai](psds:member/<id>)` is a
    mention, `![:blobwave:](psds:emoji/<id>)` a custom emoji.
  - Consumers MUST NOT render raw HTML found in markdown.
- `entities`: `text` plus `spans` (bold, italic, links, mentions, custom emoji, …), the model
  Chorus and Telegram use. **Offsets and lengths count Unicode code points** of `text`
  (DESIGN_NOTES D7). A span's `url` holds the link target, or a `psds:` URI for mentions and
  custom emoji.

A consumer MUST be able to show every form: rendered, or at least as its plain characters (the
markdown source, or `entities.text`). A producer SHOULD write the form it stores natively rather
than converting.

**`psds:` URIs** are `psds:<type>/<id>`, where `<type>` is a record type as named in `Record`
(`member`, `group`, `state`, `system`, `post`, `message`, `channel`, `custom_emoji`,
`front_span`, …). `RecordRef.type` uses the same names.

### 2.7 References

Fields ending `_id` or `_ids`, `SubjectRef`, `RecordRef` and `MessageAuthor.member_id` refer to
records by id.

- Producers MUST NOT refer to a record by name, and MUST NOT write a reference they know will not
  resolve without also writing a `reference_unresolved` warning.
- When a source identifies something only by name (PluralSpace chat authors), a converter SHOULD
  create a **placeholder** record (an archived member named after the source string, with
  `ext.psds.placeholder = true`) and refer to that, rather than dropping the link.
- Consumers MUST tolerate references that do not resolve, treating them as unknown.

### 2.8 Keeping what doesn't fit: `source_refs` and `ext`

- `source_refs` lists the record's ids in the apps it came from. Producers MUST keep the
  source refs of records they imported. Consumers SHOULD use them to recognise records they have
  seen before (§9.5).
- `ext` is `map<string, Struct>` keyed by a namespace: a registered app id (§12.2) or a
  reverse-DNS name. `psds` is reserved for this spec. Producers put data PSDS has no field for
  under their namespace. A consumer that re-exports a record SHOULD carry `ext` entries it did
  not understand.

---

## 3. Encodings and files

### 3.1 Canonical JSON

The JSON encoding is the proto3 JSON mapping with these rules:

- Producers MUST write field names as they appear in the `.proto` files (`snake_case`).
  Consumers MUST also accept `lowerCamelCase`, which proto3 JSON parsers accept anyway.
  (Protobuf libraries print camelCase unless told otherwise: `preserving_proto_field_name` in
  C++/Python/Go, `useProtoFieldName` in protobuf-es.)
- Enum values are written as their names (`"FRONT_LEVEL_CO_CONSCIOUS"`). Consumers MUST accept
  the integer form too, and MUST NOT reject a file because of an enum name or field they do not
  know: they treat the value as UNSPECIFIED, ignore the field, and report `unknown_value`.
- 64-bit integers are strings in JSON (`"size_bytes": "18422"`); consumers MUST accept numbers.
- Absent fields have their default value. Producers MAY omit defaults.

### 3.2 Binary

The binary encoding is standard protobuf. A body of several records is a sequence of `Record`
messages, each preceded by its length as a varint (the "delimited" format of
`writeDelimitedTo` / `parseDelimitedFrom`). Unknown fields MUST be preserved by consumers that
re-export records.

### 3.3 Files

A PSDS file takes one of two forms. Consumers MUST read both.

**Container** (`.psds`, a ZIP archive):

```
manifest.json           Manifest, canonical JSON; the first entry in the archive
data.jsonl              one Record per line (canonical JSON)      ─┐ exactly one,
data.binpb              delimited Records (§3.2)                  ─┘ per manifest.body_encoding
blobs/sha256/<hex>      the bytes of each Asset, named by its sha256
```

- The manifest is always JSON so a person can open the file and see what it is.
- `manifest.body_sha256` is the SHA-256 of the body file. `blobs/` entries MUST match their name.
- Records may appear in any order. Producers SHOULD write referenced records first (systems, then
  members and groups, then the rest); consumers MUST resolve references only after reading the
  whole body.
- Consumers MUST refuse entries with absolute paths or `..` segments and MUST NOT write blobs
  outside their own storage.

**Single document** (`.psds.json`): one `Archive` JSON object with the manifest and typed arrays
(`members`, `front_spans`, …). Asset bytes, if any, are inline in `Asset.data`. Suited to small
exports, hand-written fixtures and OpenPlural-style tooling.

### 3.4 Modules

`manifest.modules` lists what the file contains, so a consumer can decide before reading
records:

| Module | Records |
| --- | --- |
| `core` (REQUIRED) | System, Member, State, Group, GroupMembership, Label, LabelAssignment, Bucket |
| `fields` | FieldDefinition, FieldValue |
| `fronting` | FrontSpan, FrontSwitch, FrontComment |
| `posts` | Post |
| `reactions` | Reaction |
| `relationships` | RelationshipType, Relationship |
| `contacts` | Contact |
| `polls` | Poll, Vote |
| `chat` | Channel, Message, CustomEmoji |
| `history` | MessageRevision |
| `assets` | Asset (and blobs) |

A module listed is complete for the systems in the file: a consumer may take a missing record as
not existing. A module not listed tells the consumer nothing.

### 3.5 Media types and encryption

Working names until the spec is named: `.psds` / `application/vnd.psds+zip` for containers,
`.psds.json` / `application/vnd.psds+json` for single documents.

A producer MAY encrypt a whole file with [age](https://age-encryption.org/v1), with a passphrase
(scrypt recipient) or a public key, adding `.age` to the name. Consumers SHOULD support
passphrase-encrypted files. PSDS defines no other encryption.

---

## 4. Core records

### 4.1 System

`kind` is PLURAL or SINGLET. A SINGLET system MUST have exactly one live member with `is_self`.
`tag` is shown after member names where several systems meet (PluralKit's system tag).
`terminology` holds the system's own words (member, front, switch, subsystem, co-conscious,
present, group; anything else under `other`); consumers SHOULD use them where they have the same
concept. A file MAY contain several systems (`manifest.system_ids`).

### 4.2 Member

`name` MAY be empty (some systems have nameless members); consumers derive a label from
`display_name`, `pronouns` or a placeholder. `pronouns` and `age` are free text. `birthday` is a
`PartialDate`. `proxy_tags` are PluralKit-style `{prefix, suffix}`; `sigils` are short prefixes
(usually an emoji) typed before a message to speak as the member. `locked` says the owner's apps
gate the member behind a PIN; consumers that support locks SHOULD honour it. `archived_at` +
`archived_reason` cover dormant and integrated members.

### 4.3 State (custom fronts and statuses)

A `State` can front like a member but is not one: it is not counted as a member, cannot author,
and has no profile beyond name, description, colour, emoji and avatar. Simply Plural's custom
fronts, Ampersand's custom statuses and Prism's sleep map here. Converters MUST NOT turn states
into members; a consumer with nowhere to put them keeps their spans with a
`front_states_dropped` warning.

### 4.4 Groups and subsystems

A `Group` has `kind` GROUP (organisation) or SUBSYSTEM (a system within the system). Groups nest
through `parent_group_id`; a member may be in many groups. A group with `can_front` may be a
front subject, fronting as a unit.

A subsystem MAY describe its `structure` (WITH_MAIN or WITHOUT_MAIN) and `main_member_id`. The
main member SHOULD also be a member of the group.

A parent cycle is invalid; consumers break it at the record with the largest id and warn
`hierarchy_repaired`. Nested systems in apps that model subsystems as whole systems (Lighthouse,
Ampersand) are converted to SUBSYSTEM groups; their profile fields go on the group, and anything
without a place into `ext`.

`GroupMembership` is one record per edge (a member or a state in a group), with its own
`sort_key` for order within the group.

### 4.5 Labels

A `Label` is a reusable tag of some `kind`, attached to records by `LabelAssignment`
(`target` is a `RecordRef`). Registered kinds: `role` (host, protector, little…), `tag`, `source`,
`topic`, `identity`, `status`. Other kinds MUST be namespaced (`myapp:mood`). Labels nest through
`parent_label_id`. Free-text role lists (PluralSpace) become one label per distinct string plus
assignments.

### 4.6 Custom fields

A `FieldDefinition` gives a field's `name`, `type`, and for SELECT / MULTI_SELECT its `options`
(each with a stable `id`), for NUMBER / RATING optional `min_value` / `max_value`, and for dates
the `date_precision` people are asked for. `applies_to` lists the subject kinds it can be set on
(empty = members).

A `FieldValue` holds one value for one subject. The `value` case MUST match the definition's type:

| Field type | Value case |
| --- | --- |
| TEXT | `text` |
| RICH_TEXT | `rich_text` |
| NUMBER, RATING | `number` |
| BOOLEAN | `boolean` |
| DATE | `date` |
| DATE_RANGE | `date_range` |
| TIMESTAMP | `timestamp` |
| COLOR | `color` |
| URL | `url` |
| SELECT | `option_ids` with exactly one id |
| MULTI_SELECT | `option_ids` |
| MEMBER_REF | `member_ids` |
| JSON | `json` |

Converters from untyped sources (Ampersand and Octocon store strings) SHOULD keep TEXT rather
than guess, and MAY add a typed copy under `ext`.

### 4.7 Audience and buckets

`Audience` says who may see a record or field. Tiers, from most to least strict:

| Tier | Who |
| --- | --- |
| MEMBERS | only the listed members of the system (a soft, in-system limit) |
| PRIVATE | the system itself |
| BUCKETS | the listed buckets (named groups of friends) |
| FRIENDS | everyone the system has accepted as a friend or follower |
| INSTANCE | everyone signed in to the same server or instance |
| PUBLIC | anyone |

- A missing audience, or tier UNSPECIFIED, means PRIVATE.
- `field_audience` overrides the record's audience for one field, keyed by field name
  (`"birthday"`, `"pronouns"`); a key MAY address a part of a field (`"birthday.year"`, for
  PluralKit's hidden birth years).
- Buckets are `Bucket` records; who is in them is `Contact.bucket_ids` (module `contacts`).
- A consumer that cannot express an audience exactly MUST use the nearest **stricter** one it can
  express and warn `privacy_narrowed` (§10.3).

---

## 5. Fronting

### 5.1 Model

**`FrontSpan` is the record of who was fronting when.** A span is one continuous stretch of one
subject at one level: `subject`, `level`, `primary`, `position`, `started_at`, and `ended_at`
(unset while ongoing). Co-fronting is several spans that overlap.

`FrontSwitch` is an OPTIONAL log of the moments someone recorded a change, with its kind, the
entries, a note and who recorded it. It annotates the history; it does not define it. A file that
has spans for a period MUST NOT expect consumers to recompute them from its switches, and if the
two disagree, **spans win**.

A file that has switches and no spans (a PluralKit conversion) is turned into spans by the fold
in §5.4. Consumers MUST implement the fold.

### 5.2 Levels, primary and position

- `level`: FRONTING (in control), CO_CONSCIOUS (aware, not in control), INFLUENCING (affecting
  from the background), PRESENT (around but not fronting). UNSPECIFIED is read as FRONTING.
- `primary`: the main fronter. Only FRONTING spans may be primary.
- `position`: fronting order at the start of the span, 0 first, among spans at the same level.
  A change of order alone does not end a span; a change of level or of `primary` does.

### 5.3 Invariants

For each system:

1. `ended_at`, when set, is later than `started_at`.
2. Spans of the same subject do not overlap in time (a subject is at one level at a time).
3. At most one FRONTING span is `primary` at any instant.
4. A group subject has `can_front`.
5. At most one span per subject is open (no `ended_at`), and it is the subject's latest.

Validators report violations (§9.2). Consumers SHOULD import a file with violations by trimming
the earlier of two overlapping spans to end where the later starts, warning `spans_repaired`.

### 5.4 The fold: switches to spans

Input: the system's switches that are not deleted, sorted by `at`, then by `id`. State: the
current front, an ordered list of entries `(subject, level, primary)`, initially empty.

For each switch `s`:

1. Compute the new front `F'` from the current `F`:
   - REPLACE: `F'` = `s.entries` in order.
   - ADD: for each entry, if its subject is in `F`, replace that entry in place; otherwise append
     it.
   - REMOVE: `F'` = `F` without the entries' subjects.
   - UPDATE: for each entry whose subject is in `F`, set its level and primary in place; entries
     for subjects not in `F` are ignored with a `switch_update_ignored` warning.
   - In every case, if an entry of `s` is primary, every other entry of `F'` loses primary.
2. For each subject in `F` that is not in `F'`, or whose level or primary differs: end its open
   span at `s.at` with `end_switch_id = s.id`.
3. For each subject in `F'` that was not in `F`, or whose level or primary differs: start a span
   at `s.at` with `start_switch_id = s.id` and `position` = its index among the entries of
   `F'` at the same level.
4. `F` = `F'`.

Spans still open at the end have no `ended_at`. A producer writing spans derived this way SHOULD
give them deterministic ids: UUIDv5 in the PSDS namespace over
`<system_id>|<subject id>|<level name>|<start_switch_id>`.

### 5.5 Spans to switches

Consumers that store switches (PluralKit-style) derive them from spans: at every instant where
any span starts or ends, a REPLACE switch whose entries are the FRONTING spans open just after it,
in `position` order. Levels, states or groups the target cannot hold are dropped with
`front_levels_flattened` / `front_states_dropped`.

### 5.6 Converting other shapes

| Source shape | Examples | To PSDS |
| --- | --- | --- |
| Switch events | PluralKit | FrontSwitch (REPLACE) records, then the fold (§5.4). Spans carry `ext.psds.derived = true`. |
| Per-subject intervals | Simply Plural, PluralSpace, Prism, Octocon, OpenSelves | One FrontSpan per row. Custom-front rows → State subjects. Rows of the same subject that overlap are merged. |
| Grouped intervals | Sheaf | One FrontSpan per member of the period, same times. |
| Tiered periods | Plural Star | primary tier → FRONTING + primary; co-front → FRONTING; co-conscious → CO_CONSCIOUS. |
| Presence metadata | Ampersand | main → primary; influencing → INFLUENCING; custom status → State subject. |

Comments attached to a whole period in the source (Simply Plural front comments, PluralSpace's
duplicated `comment`) become one `FrontComment` at the period's start, anchored to one of its
spans, not one per member.

### 5.7 Front comments

A `FrontComment` is about a moment (`at`) and MAY be anchored to a span or a switch. Its author
is optional. A switch's own note stays on the switch.

---

## 6. Posts (module `posts`)

A `Post` is anything written that is not a chat message. `kind`:

| Kind | Is | Typical source |
| --- | --- | --- |
| POST | a short status or microblog post | Chorus notes |
| ENTRY | a long-form journal entry, usually titled | journals in Chorus, Sheaf, PluralSpace, Plural Star |
| NOTE | a note kept about or for a member | Simply Plural and Prism notes |
| BOARD | a message left on a member's board | Simply Plural board messages, Prism and Sheaf boards |

Three member lists keep their meanings apart: `author_member_ids` (who wrote it; empty when
unknown), `about_member_ids` (who it is about) and `recipient_member_ids` (who it is addressed
to). `written_at` is the author's own date when it differs from `created_at` (backdated or
imported); `entry_date` is the day a journal entry is for. `fronting` MAY record who was fronting
when it was written. Replies, quotes and reposts refer to other posts by id. Reactions are
`Reaction` records (module `reactions`).

---

## 7. Chat (module `chat`)

### 7.1 Channels

A `Channel` has a `kind`: TEXT, THREAD (hanging from `thread_root_message_id`), DIRECT (between
`participant_member_ids`), FORUM, or PROXY_LOG (messages proxied elsewhere, e.g. by PluralKit).
`category` groups channels in a list. A channel in a space shared with other accounts is marked
`shared`; spaces themselves are out of scope.

### 7.2 Messages

A `Message` has `authors` (members, or external people in shared channels), a `body`, and
optional reply, quote (a range of another message plus the quoted text as it was), forwarded
snapshots, content warning, attachments, pin and edit times, and a narrower `audience` than its
channel (e.g. visible only to chosen members).

### 7.3 Several authors and segments

Several `authors` speak a message **jointly**. `segments` assign parts of the body to other
authors: each segment is a range of the body's plain characters (code points of the markdown
source or of `entities.text`) with its own authors; ranges MUST NOT overlap; text outside every
segment belongs to `authors`. A consumer that supports one author per message SHOULD split a
segmented message into consecutive messages, one per segment, and warn `message_split`.

### 7.4 Revisions and emoji

Earlier versions of edited messages are `MessageRevision` records (module `history`), newest
last. `CustomEmoji` records name image emoji (`name`, `aliases`, `category`, `asset_id`) referred
to as `psds:emoji/<id>`.

---

## 8. Assets

An `Asset` describes a file: `sha256`, `mime_type`, `size_bytes`, optional `file_name`, `width`,
`height`, `duration_ms`, `alt_text`, `spoiler`. Records refer to assets by id.

- In a container, the bytes are at `blobs/sha256/<sha256>`; the same bytes are stored once,
  whatever number of assets use them.
- In a single document, the bytes MAY be inline in `data`.
- `uri` MAY point to where the bytes can be fetched. An asset with only a `uri` is not
  self-contained: producers MUST warn `asset_uri_only`, and consumers SHOULD fetch and store the
  bytes at import, since URLs rot.
- Consumers MUST check `sha256` against the bytes and treat a mismatch as a missing asset
  (`asset_corrupt`).

---

## 9. Producing and consuming

### 9.1 Producers

A producer MUST write a manifest with `psds_version`, `exported_at`, `producer`, `modules`,
`profile`, `system_ids` and `body_encoding`, and SHOULD write `counts`. It MUST add a warning for
everything it knowingly leaves out or degrades.

### 9.2 Validation rules

A file is **valid** when:

| # | Rule |
| --- | --- |
| V1 | `psds_version` has a major version the consumer knows (consumers MUST refuse an unknown major, and SHOULD read a newer minor, ignoring what they don't know). |
| V2 | Every record `id` is a lowercase UUID and unique in the file. |
| V3 | Every reference resolves, or a `reference_unresolved` warning covers it. |
| V4 | Colours, sort keys and partial dates are well formed (§2.5, §2.3). |
| V5 | Every FieldValue's case matches its definition's type, and option ids exist in the definition. |
| V6 | Front spans satisfy §5.3. |
| V7 | Span offsets and segment ranges lie within their text, and segments don't overlap. |
| V8 | Group parents form no cycle. |
| V9 | In a container, `body_sha256` and every blob match their bytes, and every asset with a `sha256` and no `uri` has a blob. |
| V10 | A SINGLET system has exactly one live `is_self` member. |

Consumers SHOULD import invalid files as far as they safely can, reporting each violation as a
warning, rather than refusing them outright (except V1).

### 9.3 Warning codes

Registered codes (producers and consumers MAY add namespaced ones, `myapp:code`):
`module_not_supported`, `module_archived_only`, `field_dropped`, `field_truncated`,
`extension_only`, `unknown_value`, `reference_unresolved`, `identity_by_name`,
`placeholder_created`, `hierarchy_dropped`, `hierarchy_repaired`, `privacy_narrowed`,
`text_format_degraded`, `front_levels_flattened`, `front_states_dropped`, `spans_repaired`,
`switch_update_ignored`, `message_split`, `asset_uri_only`, `asset_missing`, `asset_corrupt`,
`merge_undated`.

### 9.4 The import report

After an import, a consumer SHOULD be able to produce an `ImportReport`: the file's manifest,
counts per module (`imported`, `merged`, `unchanged`, `degraded`, `archived_only`, `skipped`,
`failed`) and every warning. It SHOULD show the person a summary before or after committing the
import, and MUST NOT report success while silently dropping data.

### 9.5 Re-import and merge

A consumer importing a record MUST look for an existing one by `id`, then by `source_refs` (same
`app`, `collection` and `id`). If none is found, the record is new. If one is found:

- the copy with the later `updated_at` wins, whole-record (a newer tombstone deletes; an older
  copy never brings a deleted record back);
- if either copy lacks `updated_at`, the existing record wins and the consumer warns
  `merge_undated`.

Apps with finer-grained merging (Chorus merges per field) MAY do better than this, but MUST NOT
do worse: a re-import of an unchanged file changes nothing.

---

## 10. Privacy and safety

### 10.1 This is sensitive data

Plural-system data describes people's minds and often their health. Under the GDPR it may be
special-category data (Art. 9). Producers and consumers MUST treat a PSDS file as confidential:
don't upload it to third parties for processing, don't log its contents, and keep it out of
URLs.

### 10.2 Export profiles

`manifest.profile` says what a file is for:

- **FULL**: everything, including private records, for backups and moving between one's own
  apps. Producers SHOULD offer encryption (§3.5) and say plainly that the file holds private data.
- **SHARED**: only what a chosen audience may see, for giving to someone else. A SHARED export for
  tier T contains only records and fields whose audience is T or less strict, drops `ext` entries
  unless the producer knows they are safe, drops tombstones, and drops `source_refs` that could
  identify accounts elsewhere.

### 10.3 Never widen

Importing MUST NOT make anything more visible than its audience said. Where the target cannot
express an audience, it uses a stricter one (§4.7). A consumer MUST NOT publish, share or notify
followers about imported records merely because they were imported (no "Kai started fronting"
notifications for a history import).

### 10.4 Locks and member-private content

`Member.locked` and MEMBERS-tier audiences are soft, in-system limits, not encryption. Consumers
that cannot honour them SHOULD warn before import. Sealed (client-encrypted) content is out of
scope for 0.1.

---

## 11. Conformance

| Class | A consumer or producer that… |
| --- | --- |
| **Core producer** | writes valid files (§9.2) with the `core` module, stable ids, and warnings for loss. |
| **Core consumer** | reads both file forms and both body encodings, imports `core` and `fronting` (including the fold, §5.4), honours audiences (§10.3), merges by id (§9.5), and reports imports (§9.4). |
| **Module support** | for each module it claims, reads and/or writes every record of it. |
| **Round-trip** | re-exporting an imported file gives back every record, with the same ids, `source_refs` and unknown `ext`, apart from what its warnings declare. |

A conformance suite (fixtures + a validator) is planned (DESIGN_NOTES, readiness path).

---

## 12. Versioning and registries

### 12.1 Versions

`psds_version` is semantic: `MAJOR.MINOR.PATCH`. Within a major version the schema only grows:
new fields, records, enum values and modules; nothing is renamed, renumbered or retyped, and
removed fields are `reserved`. The proto package carries the major version (`psds.v1`). Drafts
before 1.0.0 may break anything and say so in their changelog.

### 12.2 Registered app ids

For `SourceRef.app`, `Producer.app_id` and `ext` namespaces; shared with OpenPlural:
`prism`, `sheaf`, `simply_plural`, `pluralkit`, `octocon`, `plural_star`, `lighthouse`,
`openselves`, `ampersand`, `pluralspace`, `tupperbox`, and `chorus`. Others use reverse-DNS names
until registered.

### 12.3 Future modules

Reserved names: `reminders`, `habits`, `safety`, `proxy` (Discord proxy settings and autoproxy),
`sharing` (friends and grants beyond buckets), `ops` (a change log for sync).

---

## Appendix A. Mappings

### A.1 OpenPlural v0.1 → PSDS

| OpenPlural | PSDS |
| --- | --- |
| envelope `openplural_version`, `producer`, `capabilities.modules`, `warnings` | `Manifest` (`psds_version` from the converter; OpenPlural version in `ext.psds.from_openplural`) |
| file-local `id` | UUIDv5 over `openplural|<record type>|<id>` unless already a UUID; original in `source_refs` (app `openplural`) |
| `systems[]` (`parent_system_id` set) | `System`; nested systems → `Group` kind SUBSYSTEM |
| `members[]` with `is_custom_front` | `Member`, or `State` when `is_custom_front` |
| `members[].birthday {value, precision, year_visible}` | `PartialDate`; `year_visible: false` → `field_audience["birthday.year"]` PRIVATE |
| `groups[]`, `group_memberships[]` | `Group` (kind GROUP), `GroupMembership` |
| `taxonomy_terms[]`, `taxonomy_assignments[]` | `Label`, `LabelAssignment` |
| `custom_fields[]`, `custom_field_values[]` | `FieldDefinition`, `FieldValue` (value case from `field_type`; option strings → option ids) |
| `front_periods[]` + `assignments[]` | one `FrontSpan` per assignment: `primary` → FRONTING+primary, `co_front`/`member` → FRONTING, `co_conscious` → CO_CONSCIOUS, `influencing` → INFLUENCING, `custom_status` → State subject |
| `front_events[]` | `FrontSwitch` (REPLACE), folded if no periods cover the time |
| `front_comments[]` | `FrontComment` |
| `notes[]` | `Post` kind NOTE (or ENTRY when `entry_date` or a title is set): `member_id` → `about_member_ids`, `author_member_ids` → same |
| `boards.posts[]` | `Post` kind BOARD: `target_member_id` → `recipient_member_ids` |
| `chat.conversations[]`, `chat.messages[]`, `chat.reactions[]` | `Channel`, `Message`, `Reaction` |
| `relationships.types[]`, `relationships.edges[]` | `RelationshipType`, `Relationship` |
| `assets[]` (`data_base64`/`data_uri`/`uri`) | `Asset` + blob; URI-only → warning |
| `privacy.visibility` | `Audience`: `private`→PRIVATE, `trusted`→BUCKETS (a bucket named "Trusted"), `friends`→FRIENDS, `public`→PUBLIC, `unknown`→PRIVATE; `privacy.source` → `ext.<app>.privacy` |
| `extensions` | `ext` |

### A.2 PluralKit datafile v2 → PSDS

| PluralKit | PSDS |
| --- | --- |
| system `id`, `uuid` | `System` + `source_refs {app: pluralkit, collection: system, id, uuid}` |
| `tag`, `pronouns`, `avatar_url`, `banner`, `color`, `description` | same fields; URLs → URI-only assets |
| `privacy.*_privacy` | `audience` / `field_audience` (`private` → PRIVATE, `public` → PUBLIC) |
| `members[]` | `Member`; `proxy_tags` as is; `keep_proxy`, `tts`, `autoproxy_enabled`, `webhook_avatar_url`, message count → `ext.pluralkit` |
| `groups[]` (`members` inline) | `Group` + `GroupMembership` per member; `icon` → `emoji` if an emoji, else an asset |
| `switches[] {timestamp, members}` | `FrontSwitch` REPLACE, entries FRONTING in PluralKit's order (which becomes `position`), none primary: PluralKit has order but no primary; then the fold |

### A.3 Simply Plural export → PSDS

| Simply Plural | PSDS |
| --- | --- |
| `users` + `private` | `System` (`username` → `name`), settings → `ext.simply_plural` |
| `members` | `Member`; `info {fieldId: value}` → `FieldValue` per entry; `archivedReason` → `archived_reason` |
| `frontStatuses` | `State` |
| `frontHistory` | `FrontSpan` per row (`custom` → State subject); `live` → no `ended_at` |
| `comments` (collection `frontHistory`) | `FrontComment` anchored to the span |
| `groups` (`parent: "root"`) | `Group` + memberships (members and states) |
| `customFields` type 0–7 | 0 TEXT · 1 COLOR · 2 DATE (DAY) · 3 DATE (MONTH) · 4 DATE (YEAR) · 5 DATE (YEAR_MONTH) · 6 TIMESTAMP · 7 DATE (MONTH_DAY) |
| `notes` | `Post` NOTE, `member` → `about_member_ids` |
| `boardMessages` | `Post` BOARD (`writtenBy` → author, `writtenFor` → recipient) |
| `channels`, `channelCategories`, `chatMessages` | `Channel` (`category` = category name), `Message` |
| `polls` | `Poll` + `Vote` |
| `privacyBuckets`, `friends` | `Bucket`, `Contact` |
| reminders | `ext.simply_plural` until the `reminders` module exists |

### A.4 PluralSpace GDPR export → PSDS

| PluralSpace | PSDS |
| --- | --- |
| `system` (`slug`, `visibility`) | `System`; `slug` → `ext.pluralspace.slug` |
| `members[]` (`role[]` strings, `groups[]` names) | `Member`; each role → `Label` kind `role` + assignment; group names only cross-check memberships |
| `fronts[]` | `FrontSpan` per row; the duplicated `comment` → one `FrontComment` per distinct interval |
| `journal_entries[]` (`members` snapshots, `date`) | `Post` ENTRY; `members` → `about_member_ids`; `date` → `entry_date`; `visibility_level` → `ext.pluralspace` and PRIVATE |
| `chat_channels[].messages[]` (`member_name` only) | `Channel`, `Message`; authors resolved by name → placeholders + `identity_by_name` |
| `member_groups[]` | `Group` (flat; `hierarchy_dropped`) + memberships from the group side |
| `polls[]` | `Poll` + `Vote` |
| `media_files[]`, `media/` | `Asset` + blobs |

### A.5 Chorus → PSDS

| Chorus | PSDS |
| --- | --- |
| `account` kind system / person + `system` profile | `System` kind PLURAL / SINGLET |
| `member` (`sigils`, `proxy_tags`, `short_id`, `pk_id`, `notify_policy`, `is_locked`) | `Member` (`short_id` → `ext.chorus`, `pk_id` → `source_refs`, `notify_policy` → `ext.chorus`, `is_locked` → `locked`) |
| `member_group` kind group / subsystem | `Group` |
| `state` | `State` |
| `field` definitions and values | `FieldDefinition`, `FieldValue` |
| `switch` rows | `FrontSwitch` (`notify`, `based_on`, device → `ext.chorus`; retracted → deleted) |
| `front_interval` | `FrontSpan` (`front` → FRONTING, `cocon` → CO_CONSCIOUS, `present` → PRESENT) |
| `post` kind note / entry | `Post` POST / ENTRY |
| `space` internal + `channel` + `message` (+ segments, revisions) | `Channel`, `Message`, `MessageRevision`; text + entities → `RichText.entities` (UTF-16 offsets converted to code points) |
| `reaction`, `emoji` | `Reaction`, `CustomEmoji` |
| `relationship`, `reltype` | `Relationship`, `RelationshipType` |
| `bucket` | `Bucket` |
| visibility JSON | `Audience` (`followers` → FRIENDS, `server` → INSTANCE, `members` → MEMBERS, `system_only` → PRIVATE) |
| blobs | `Asset` + blobs |
