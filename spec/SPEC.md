# PluralSpec

**An open data format for plural systems · Draft 0.1.0 · 2026-09-26**
**Text: CC BY-NC-SA 4.0 · Schema and examples: CC BY 4.0** (see `LICENSE`)

PluralSpec is a format for storing and moving the data of plural systems: members, groups and
subsystems, custom fields, visibility classes, front history, journals, chat, the files they use,
and the history of how all of it changed. The schema is `proto/pluralspec/v1/*.proto`; this
document says what the fields mean and what producers and consumers must do. Where this text and
the `.proto` comments disagree, this text wins until the next draft fixes one of them.

---

## 1. Introduction

### 1.1 Purpose

Plural systems keep years of their lives in apps: who they are, who was fronting when, what they
wrote to each other. Apps close (Simply Plural stopped in July 2026), change, or stop fitting.
PluralSpec lets that data leave one app and arrive in another **without losing meaning**, and lets
an app keep its own data in a documented, durable shape.

Goals:

1. **Lossless where it matters**: everything an app can express about members, fronting and
   writing has a place, and anything that doesn't is kept (`ext`, custom events) and reported
   (warnings).
2. **Mergeable**: ids are stable, so importing a newer export of the same system updates it
   instead of duplicating it.
3. **Historical**: a file can carry not only how things are, but how they came to be (§9).
4. **Formal**: a schema tools can generate code from, requirements with RFC 2119 keywords,
   validation rules, and conformance classes.
5. **A superset of PluralPort v0.1** (formerly OpenPlural): every PluralPort file converts to
   PluralSpec without loss, and PluralSpec converts to PluralPort with declared warnings
   (Appendix A.1). Changes PluralSpec makes are to be offered to PluralPort as proposals.
6. **Private by default**: nothing becomes more visible by being exported or imported.

### 1.2 Scope

In scope: a system's own data — its profile, members, custom states, groups and subsystems,
labels, custom fields, visibility classes and contacts, front history, posts (journals, notes,
board messages), reactions, relationships, polls, chat channels and messages, custom emoji,
files, and the history of changes to all of these.

Out of scope for 0.1: accounts, devices, sessions, API tokens, webhooks, follows and notification
settings (service state, owned by the server); live sync protocols (§9.7); reminders, habits,
safety plans and Discord proxy configuration (future modules, §13.3; until then, custom events
and `ext`).

### 1.3 Terms

- **System**: a plural system, or a singlet using the same tools.
- **Member**: someone in a system (alter, headmate, part…). Apps may call them something else
  (`Terminology`).
- **State**: something that can front but is not a member: a custom front or status.
- **Subsystem**: a system within the system: a `Group` of kind SUBSYSTEM.
- **Subject**: whatever a front, a label or a field is about: a member, a group, a state, or the
  system (`SubjectRef`).
- **Front**: who is at the front of the system, or at the front *within* a subsystem (§5.3).
- **Span**: one continuous stretch of one subject at one level in one front (`FrontSpan`).
- **Switch**: a recorded change of a front at a moment (`FrontSwitch`).
- **Visibility class**: a named audience the system defines (`VisibilityClass`).
- **Event**: one entry in the history of changes (`Event`).
- **Producer**: software that writes a PluralSpec file. **Consumer**: software that reads one.
- **Module**: a named set of record types that a file may or may not contain (§3.4).

### 1.4 Requirements language

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, MAY
and OPTIONAL are to be interpreted as described in BCP 14 (RFC 2119, RFC 8174) when, and only
when, they appear in all capitals.

---

## 2. Conventions

### 2.1 Schema and naming

The proto3 files under `proto/pluralspec/v1/` are normative for field names, types and numbers.
Package `pluralspec.v1`; field names are `lower_snake_case`. Field numbers follow one convention
in every record: 1–9 the common header (§2.4), 10–899 the record's own fields, 900
`source_refs`, 901 `ext`. Numbers are never reused within a major version; removed fields are
`reserved`.

A record's **type name** is its field name in `Record` (`record.proto`): `member`, `front_span`,
`visibility_class`, `event`, … Type names are used by `RecordRef.type`, `pluralspec:` URIs, event
types and warnings.

### 2.2 Identifiers

- Every record's `id` MUST be a UUID (RFC 9562) in lowercase hyphenated form.
- Producers SHOULD use UUIDv7 for records they create.
- An id MUST NOT change between exports of the same record. This is what makes re-import a merge
  (§10.5) and not a copy.
- A producer converting records that have no UUID (numeric ids, Mongo ObjectIds, PluralKit short
  ids) SHOULD derive a UUIDv5 in the PluralSpec namespace `ae3a509b-4cf9-4cb2-868a-a67de27591a5`
  over `<app>|<collection>|<source id>` (e.g. `pluralkit|members|kaiabc` →
  `613b4f78-bbdd-5a20-8466-d8342b2dec99`), and keep the source id in `source_refs`. Converting
  the same foreign export twice then yields the same ids.
- Ids of options inside a record (`FieldOption.id`, `PollOption.id`) need only be unique within
  that record.
- All record ids in a file MUST be unique across record types.

### 2.3 Time

- Instants are `google.protobuf.Timestamp`: RFC 3339 with `Z` in JSON. Producers SHOULD keep at
  least millisecond precision where the source has it.
- Where local time matters (a span, a switch, a post, a message, an event),
  `*utc_offset_minutes` MAY record the offset in force at that moment. `System.timezone` (IANA)
  gives the default for local days.
- Calendar dates with unknown parts are `PartialDate`, where 0 means unknown:
  `{month: 4, day: 2}` is 2 April with no year.

### 2.4 The common header, deletion and archiving

Every record has `id` (1), `created_at` (2), `updated_at` (3) and `deleted_at` (4); records that
can be archived also have `archived_at` (5). Events (§9) have only `id` and `created_at`: they
never change.

- `created_at` is when the record came into being in its first app. `updated_at` is the last
  change to any field; producers SHOULD set it when they know it (merges depend on it).
- **Deleted** (`deleted_at` set) means removed by the user but restorable: a tombstone. A FULL
  export SHOULD include tombstones so merges can delete (§10.5). Consumers MUST NOT show a
  deleted record as live.
- **Archived** (`archived_at` set) means kept and visible in its archive, but out of the way
  (a dormant member, an old channel). It is not deletion.
- A consumer that has no archive or trash MUST NOT drop a record's content because it is
  archived or deleted without a warning.

### 2.5 Small value types

- **Colour**: `#rrggbb`. Producers MUST write six lowercase hex digits; consumers SHOULD accept
  uppercase and `#rgb`.
- **Sort key**: records sort by `sort_key` compared by Unicode code point, ties by `id`. Keys are
  fractional indexes (a new key can always be made between two others). A producer converting an
  integer order SHOULD write fixed-width zero-padded decimals (`"0000000007"`).
- **Emoji**: a Unicode emoji sequence, or `pluralspec:emoji/<id>` for a `CustomEmoji`.

### 2.6 Text

`RichText` holds exactly one of:

- `plain`: no formatting.
- `markdown`: **PluralSpec markdown** = CommonMark 0.31.2 with the GFM extensions for tables,
  strikethrough, task lists and autolinks, plus:
  - `||text||` is a spoiler;
  - link targets with the `pluralspec:` scheme refer to records: `[@Kai](pluralspec:member/<id>)`
    is a mention, `![:blobwave:](pluralspec:emoji/<id>)` a custom emoji.
  - Consumers MUST NOT render raw HTML found in markdown.
- `entities`: `text` plus `spans` (bold, italic, links, mentions, custom emoji, …), the model
  Chorus and Telegram use. A span's `url` holds the link target, or a `pluralspec:` URI for
  mentions and custom emoji.

A consumer MUST be able to show every form: rendered, or at least as its plain characters (the
markdown source, or `entities.text`). A producer SHOULD write the form it stores natively rather
than converting.

**Text positions.** Every position in a file — span offsets and lengths, message segments,
quote ranges — is counted in one unit, declared by `manifest.text_offset_unit`:

| Value | Counts | Native to |
| --- | --- | --- |
| UTF16 (and when unset) | UTF-16 code units | JavaScript, Kotlin/Java, Telegram, Chorus |
| UTF8 | UTF-8 bytes | Rust, Go |

A consumer MUST read positions in the declared unit and convert them to its own. A position that
falls inside a character (between the halves of a surrogate pair, or inside a multi-byte UTF-8
sequence) is invalid (V7).

**`pluralspec:` URIs** are `pluralspec:<type name>/<id>` (`pluralspec:member/<id>`,
`pluralspec:post/<id>`, …).

### 2.7 References

Fields ending `_id` or `_ids`, `SubjectRef`, `RecordRef` and `MessageAuthor.member_id` refer to
records by id.

- Producers MUST NOT refer to a record by name, and MUST NOT write a reference they know will not
  resolve without also writing a `reference_unresolved` warning.
- When a source identifies something only by name (PluralSpace chat authors), a converter SHOULD
  create a **placeholder** record (an archived member named after the source string, with
  `ext.pluralspec.placeholder = true`) and refer to that, rather than dropping the link.
- Consumers MUST tolerate references that do not resolve, treating them as unknown.

### 2.8 Keeping what doesn't fit: `source_refs` and `ext`

- `source_refs` lists the record's ids in the apps it came from. Producers MUST keep the
  source refs of records they imported. Consumers SHOULD use them to recognise records they have
  seen before (§10.5).
- `ext` is `map<string, Struct>` keyed by a namespace: a registered app id (§13.2) or a
  reverse-DNS name. `pluralspec` is reserved for this spec. Producers put data PluralSpec has no
  field for under their namespace. A consumer that re-exports a record SHOULD carry `ext` entries
  it did not understand.
- Data that is an *occurrence* rather than a field (something that happened) belongs in a custom
  event (§9.4) rather than `ext`, so it can later become a standard event.

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

A PluralSpec file takes one of two forms. Consumers MUST read both.

**Container** (`.pluralspec`, a ZIP archive):

```
manifest.json           Manifest, canonical JSON; the first entry in the archive
data.jsonl              one Record per line (canonical JSON)      ─┐ exactly one,
data.binpb              delimited Records (§3.2)                  ─┘ per manifest.body_encoding
blobs/sha256/<hex>      the bytes of each Asset, named by its sha256
```

- The manifest is always JSON so a person can open the file and see what it is.
- `manifest.body_sha256` is the SHA-256 of the body file. `blobs/` entries MUST match their name.
- Records other than events may appear in any order. Producers SHOULD write referenced records
  first (systems, then members and groups, then the rest); consumers MUST resolve references only
  after reading the whole body. Events keep their order (§9.2).
- Consumers MUST refuse entries with absolute paths or `..` segments and MUST NOT write blobs
  outside their own storage.

**Single document** (`.pluralspec.json`): one `Archive` JSON object with the manifest and typed
arrays (`members`, `front_spans`, `events`, …). Asset bytes, if any, are inline in `Asset.data`.
Suited to small exports, hand-written fixtures and PluralPort-style tooling.

### 3.4 Modules

`manifest.modules` lists what the file contains, so a consumer can decide before reading
records:

| Module | Records |
| --- | --- |
| `core` (REQUIRED) | System, Member, State, Group, GroupMembership, Label, LabelAssignment, VisibilityClass |
| `fields` | FieldDefinition, FieldValue |
| `fronting` | FrontSpan, FrontSwitch, FrontComment |
| `posts` | Post |
| `reactions` | Reaction |
| `relationships` | RelationshipType, Relationship |
| `contacts` | Contact |
| `polls` | Poll, Vote |
| `chat` | Channel, Message, CustomEmoji |
| `assets` | Asset (and blobs) |
| `history` | Event |

A module listed is complete for the systems in the file (for `history`, within
`manifest.history.since`): a consumer may take a missing record as not existing. A module not
listed tells the consumer nothing.

### 3.5 Media types and encryption

`.pluralspec` / `application/vnd.pluralspec+zip` for containers, `.pluralspec.json` /
`application/vnd.pluralspec+json` for single documents (to be registered once the spec is
stable).

A producer MAY encrypt a whole file with **age** ([age-encryption.org/v1](https://age-encryption.org/v1)),
adding `.age` to the name (`system.pluralspec.age`). age is a small, widely reviewed file
encryption format with implementations in Go, Rust (rage), TypeScript (typage) and others. It
encrypts to a passphrase (scrypt) or to a public key (X25519), and decrypting gives back the
ordinary `.pluralspec` file, so nothing else in this spec changes. Consumers SHOULD be able to
open passphrase-encrypted files. PluralSpec defines no encryption of its own.

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
front subject, fronting as a unit. A subsystem may also have its own internal front (§5.3).

A subsystem MAY describe its `structure` (WITH_MAIN or WITHOUT_MAIN), `main_member_id` and its
own `tag`. The main member SHOULD also be a member of the group.

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

### 4.7 Visibility classes and audiences

Who may see something is decided by **visibility classes**: `VisibilityClass` records the system
defines, names, colours and orders itself. Each has a `kind` that says who is in it:

| Kind | Who is in it |
| --- | --- |
| CUSTOM (and when unspecified) | the contacts assigned to it (`Contact.class_ids`): "Close friends", "Partners", "Trusted" |
| FRIENDS | everyone the system has accepted as a friend or follower |
| INSTANCE | everyone signed in to the same server or instance |
| PUBLIC | anyone |

A system SHOULD have at most one class of each built-in kind (FRIENDS, INSTANCE, PUBLIC), and any
number of CUSTOM classes. `includes_class_ids` nests classes: if "Close friends" includes
"Partners", everyone in Partners is also in Close friends. Includes MUST NOT form a cycle.

An `Audience` on a record or field says who may see it:

- empty (no classes, no members): **only the system itself**; this is also the meaning of a
  missing audience;
- `class_ids`: the system and everyone in any of these classes (including classes they include);
- `member_ids`: only these members of the system, a soft in-system limit (a message meant for
  some members); `class_ids` MUST then be empty.

`field_audience` overrides a record's audience for one field, keyed by field name
(`"birthday"`, `"pronouns"`); a key MAY address a part of a field (`"birthday.year"`, for
PluralKit's hidden birth years). Audiences refer to classes by id, so a file that uses a class
MUST include its `VisibilityClass` record.

**Narrowing, never widening.** A consumer that cannot express an audience exactly MUST use one
whose viewers are a subset of the original's, and warn `privacy_narrowed` (§11.3). For this, the
built-in kinds are ordered PUBLIC ⊇ INSTANCE, PUBLIC ⊇ FRIENDS ⊇ CUSTOM, and the system alone is
a subset of everything. A consumer without custom classes therefore maps a CUSTOM class to "only
the system", never to "friends". A consumer that has its own custom classes SHOULD create matching
ones and keep contacts' assignments.

### 4.8 Permissions

A visibility class also carries **grants**: what the people in it may see and do beyond reading
records whose audience includes the class. Each `Grant` names a `permission` and MAY carry
`params` (settings for it). Grants add up across the classes a person is in (and the classes
those include); there are no denials. **Audiences stay the ceiling**: a grant never shows a record
whose audience excludes the class. `front.view` lets a class see who is fronting, but a member
whose audience excludes the class still doesn't appear (apps show "someone" or leave them out).

Registered permissions (others MUST be namespaced, `myapp:permission`):

| Permission | Lets people in the class | Typical source |
| --- | --- | --- |
| `front.view` | see who is fronting now | PluralKit front privacy, Simply Plural friend settings, Chorus follows |
| `front.history` | see past fronting | PluralKit front-history privacy, Chorus `share_history` |
| `front.stats` | see fronting statistics | Chorus `share_stats` |
| `front.notify` | be notified of switches; `params` MAY limit how (delay, time shown, digest) | Simply Plural front notifications, Chorus follower ceilings |
| `front.log` | record switches on the system's behalf | trusted partners |
| `members.list` | see the list of members (each still subject to its audience) | PluralKit member-list privacy |
| `groups.list` | see the list of groups | PluralKit group-list privacy |
| `posts.reply` | reply to posts they can read | Chorus |
| `posts.react` | react to posts and messages they can read | Chorus |
| `messages.send` | message the system | Chorus DMs |
| `polls.vote` | vote in polls they can read | |

A consumer that cannot represent a grant drops it (never keeps a broader one in its place) and
warns `permission_dropped`. `params` are app-shaped: registered keys may be added in later
versions; until then, apps namespace them (`{"chorus": {...}}`).

---

## 5. Fronting

**Status:** the span model is adopted for now; the owner will revisit it (DESIGN_NOTES D4).

### 5.1 Model

**`FrontSpan` is the record of who was fronting when.** A span is one continuous stretch of one
subject at one level in one front: `subject`, `level`, `primary`, `position`, `started_at`,
`ended_at` (unset while ongoing) and `scope_group_id` (§5.3). Co-fronting is several spans that
overlap.

`FrontSwitch` is an OPTIONAL log of the moments someone recorded a change, with its kind, the
entries, a note and who recorded it. It annotates the history; it does not define it. A file that
has spans for a period MUST NOT expect consumers to recompute them from its switches, and if the
two disagree, **spans win**.

A file that has switches and no spans (a PluralKit conversion) is turned into spans by the fold
in §5.5. Consumers MUST implement the fold.

### 5.2 Levels, primary and position

- `level`: FRONTING (in control), CO_CONSCIOUS (aware, not in control), INFLUENCING (affecting
  from the background), PRESENT (around but not fronting). UNSPECIFIED is read as FRONTING.
- `primary`: the main fronter. Only FRONTING spans may be primary.
- `position`: fronting order at the start of the span, 0 first, among spans at the same level in
  the same front. A change of order alone does not end a span; a change of level or of `primary`
  does.

### 5.3 Fronts and subsystems

There is one **system front** (spans with no `scope_group_id`), and each subsystem MAY have its
own **internal front** (spans whose `scope_group_id` is that subsystem). This covers the ways
systems describe subsystem fronting:

| Situation | Spans |
| --- | --- |
| The subsystem fronts as one | a span in the system front whose `subject.group_id` is the subsystem (the group has `can_front`) |
| …and one of its members leads from within | the same, plus a span in the subsystem's internal front for that member (usually `primary`) |
| Members of the subsystem front individually | spans in the system front for those members; the subsystem is not a subject |
| Life inside a subsystem while it is not out | spans in the subsystem's internal front only |

- `scope_group_id` MUST refer to a Group of kind SUBSYSTEM. A span in a subsystem's internal
  front SHOULD have a subject that belongs to that subsystem (a member, a state, or a nested
  subsystem).
- A switch's `scope_group_id` says which front it changes. The fold (§5.5) runs separately for
  each front.
- A consumer without internal fronts keeps the system front and warns `subsystem_fronts_dropped`
  for the rest (or keeps them in its archive).

### 5.4 Invariants

For each front (the system front, and each subsystem's internal front):

1. `ended_at`, when set, is later than `started_at`.
2. Spans of the same subject do not overlap in time (a subject is at one level at a time).
3. At most one FRONTING span is `primary` at any instant.
4. A group subject has `can_front`.
5. At most one span per subject is open (no `ended_at`), and it is the subject's latest.

Validators report violations (§10.2). Consumers SHOULD import a file with violations by trimming
the earlier of two overlapping spans to end where the later starts, warning `spans_repaired`.

### 5.5 The fold: switches to spans

Run once per front. Input: that front's switches that are not deleted, sorted by `at`, then by
`id`. State: the current front `F`, an ordered list of entries `(subject, level, primary)`,
initially empty.

For each switch `s`:

1. Compute the new front `F'` from `F`:
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
   at `s.at` with `start_switch_id = s.id`, the switch's `scope_group_id`, and `position` = its
   index among the entries of `F'` at the same level.
4. `F` = `F'`.

Spans still open at the end have no `ended_at`. A producer writing spans derived this way SHOULD
give them deterministic ids: UUIDv5 in the PluralSpec namespace over
`<system_id>|<scope_group_id>|<subject id>|<level name>|<start_switch_id>`.

### 5.6 Spans to switches

Consumers that store switches (PluralKit-style) derive them from spans, per front: at every
instant where any span starts or ends, a REPLACE switch whose entries are the FRONTING spans open
just after it, in `position` order. Levels, states, groups or internal fronts the target cannot
hold are dropped with `front_levels_flattened`, `front_states_dropped` or
`subsystem_fronts_dropped`.

### 5.7 Converting other shapes

| Source shape | Examples | To PluralSpec |
| --- | --- | --- |
| Switch events | PluralKit | FrontSwitch (REPLACE) records, then the fold (§5.5). Spans carry `ext.pluralspec.derived = true`. |
| Per-subject intervals | Simply Plural, PluralSpace, Prism, Octocon, OpenSelves | One FrontSpan per row. Custom-front rows → State subjects. Rows of the same subject that overlap are merged. |
| Grouped intervals | Sheaf | One FrontSpan per member of the period, same times. |
| Tiered periods | Plural Star | primary tier → FRONTING + primary; co-front → FRONTING; co-conscious → CO_CONSCIOUS. |
| Presence metadata | Ampersand | main → primary; influencing → INFLUENCING; custom status → State subject. |

Comments attached to a whole period in the source (Simply Plural front comments, PluralSpace's
duplicated `comment`) become one `FrontComment` at the period's start, anchored to one of its
spans, not one per member.

### 5.8 Front comments

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
`Reaction` records (module `reactions`). Earlier versions of an edited post are history events
(§9).

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
snapshots, content warning, attachments, pin and edit times, and an `audience` narrower than its
channel (e.g. visible only to chosen members).

### 7.3 Several authors and segments

Several `authors` speak a message **jointly**. `segments` assign parts of the body to other
authors: each segment is a range of the body's plain characters (the markdown source, or
`entities.text`), counted in the file's text offset unit (§2.6), with its own authors; ranges
MUST NOT overlap; text outside every segment belongs to `authors`. A consumer that supports one
author per message SHOULD split a segmented message into consecutive messages, one per segment,
and warn `message_split`.

### 7.4 Edits and emoji

Earlier versions of edited messages are `message.update` events (§9.3) carrying the message
before and after. `CustomEmoji` records name image emoji (`name`, `aliases`, `category`,
`asset_id`) referred to as `pluralspec:emoji/<id>`.

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

## 9. History (module `history`)

The records in a file say how things are. **Events** say how they came to be: every creation,
edit, deletion and restoration, in order, with who did it and when. A file with history lets the
next app show an edit history, undo, audit, or rebuild its own state from the start.

### 9.1 Events

An `Event` is immutable. It has an `id` (UUIDv7 RECOMMENDED), `created_at` (when the producing app
stored it), `at` (when it happened; earlier than `created_at` when made offline or imported),
`type`, optional `actor_member_id` (who did it) and `origin` (an opaque device or client label),
the `targets` it touched, and a body: a standard `change` or a `custom` event. An event that fixes
or undoes an earlier one names it in `corrects_event_id`; the earlier event stays.

### 9.2 Order and merging

- Events in a file are in **replay order**: the order the producer applied them. Consumers MUST
  keep that order when replaying.
- A consumer merging events from several files (or from a file and its own log) takes the union
  by `id` (an event never changes, so the same id is the same event) and orders events it cannot
  otherwise relate by `at`, then `created_at`, then `id`.
- A consumer MUST NOT drop, reorder within one producer's log, or alter events it re-exports.

### 9.3 Standard events

A standard event's `type` is `<record type name>.<change>`, e.g. `member.create`,
`member.update`, `message.delete`, `front_span.update`, and its `change` holds:

| Change | `after` | `fields` |
| --- | --- | --- |
| CREATE | the new record | — |
| UPDATE | the whole record after the change | the fields that changed, by proto field name (`pronouns`, `body`) |
| DELETE | the tombstone (`deleted_at` set) | — |
| RESTORE, ARCHIVE, UNARCHIVE | the record after | — |
| PURGE | only the record's id (inside its type) | — |

`before` MAY carry the record as it was, for undo and edit history (a message's earlier text is
the `before` of its `message.update`). Because `after` is always a whole record, any record type
— including ones added in later versions — has history without new event types.

A PURGE event records that something was erased for good, never what it was: producers MUST
remove the purged record's content from every earlier event in the file (`after` and `before`)
when they write a PURGE.

### 9.4 Custom events

Anything an app records that is not a change to a standard record — a review decision, a check-in,
a reminder firing, an app-specific action — is a **custom event**:

- `type` is `<namespace>:<name>` (`chorus:front.review_resolve`), the namespace being a
  registered app id or a reverse-DNS name. A name MUST keep its meaning forever.
- `custom.version` is the version of the payload's shape; a producer that changes the shape bumps
  it.
- `custom.payload` is the data, as JSON.
- `custom.summary` is one line a person can read ("Kept both of two switches made at 07:00"), so
  any app can show the event in a history view without understanding it.
- `custom.effects` lists the standard record changes the event made, if it made any. A consumer
  that does not know the type applies the effects when replaying, so the data stays right.

Consumers MUST keep custom events they do not understand (store them, or at least carry them into
their next export) and count them as `archived_only` in the import report.

### 9.5 Converting custom events forward

A custom event is written so that a later version of this spec can turn it into a standard one:

1. An app that wants a custom type standardised registers it (§13.2): its namespace and name,
   each payload version, and what it means.
2. When a later spec version adds a standard record or event for it, the version ships an
   **upgrade** in the registry: `from` (`<namespace>:<name>` + payload version), `to` (a standard
   type), and how payload fields map to record fields.
3. A consumer reading a file older than its own spec version applies the upgrades between the
   two, replacing the custom event by its standard form and keeping the original under
   `ext.pluralspec.upgraded_from`. A consumer without a matching upgrade keeps the event as it
   is.

Upgrades never lose information: payload fields with no standard place move to the new record's
`ext` under the original namespace.

### 9.6 Coverage and replay

`manifest.history` says what the events cover: `since` (history starts there; unset = from the
beginning) and `complete`. When `complete` is true, replaying every event in order — starting
from nothing, or from the state at `since` — gives exactly the records in the file (V11). The
records stay authoritative: if replay disagrees, consumers keep the records and warn
`history_inconsistent`.

A producer that has history only for some record types (say, message edits) writes `complete:
false` and still lists module `history`.

### 9.7 What history is not

History is a record of what happened, not a sync protocol: it does not define clocks, causality
between devices, or how live replicas converge. Apps that sync (Chorus merges field by field with
hybrid logical clocks) keep their own ordering data in `ext` (e.g. `ext.chorus.hlc`) and export
events in their own replay order.

---

## 10. Producing and consuming

### 10.1 Producers

A producer MUST write a manifest with `pluralspec_version`, `exported_at`, `producer`, `modules`,
`profile`, `system_ids`, `body_encoding` and, when the file has any text positions,
`text_offset_unit`; it SHOULD write `counts`, and `history` when module `history` is listed. It
MUST add a warning for everything it knowingly leaves out or degrades.

### 10.2 Validation rules

A file is **valid** when:

| # | Rule |
| --- | --- |
| V1 | `pluralspec_version` has a major version the consumer knows (consumers MUST refuse an unknown major, and SHOULD read a newer minor, ignoring what they don't know). |
| V2 | Every record `id` is a lowercase UUID and unique in the file. |
| V3 | Every reference resolves, or a `reference_unresolved` warning covers it. |
| V4 | Colours, sort keys and partial dates are well formed (§2.5, §2.3). |
| V5 | Every FieldValue's case matches its definition's type, and option ids exist in the definition. |
| V6 | Front spans satisfy §5.4 in every front, and every `scope_group_id` is a SUBSYSTEM. |
| V7 | Text positions lie within their text, on character boundaries in the declared unit, and segments don't overlap. |
| V8 | Group parents and visibility-class includes form no cycle. |
| V9 | In a container, `body_sha256` and every blob match their bytes, and every asset with a `sha256` and no `uri` has a blob. |
| V10 | A SINGLET system has exactly one live `is_self` member. |
| V11 | If `manifest.history.complete`, replaying the events gives the records (§9.6). |
| V12 | Every event type is either `<known record type>.<change>` matching its `change`, or a namespaced custom type with a `custom` body; no audience has both `class_ids` and `member_ids`. |

Consumers SHOULD import invalid files as far as they safely can, reporting each violation as a
warning, rather than refusing them outright (except V1).

### 10.3 Warning codes

Registered codes (producers and consumers MAY add namespaced ones, `myapp:code`):
`module_not_supported`, `module_archived_only`, `field_dropped`, `field_truncated`,
`extension_only`, `unknown_value`, `reference_unresolved`, `identity_by_name`,
`placeholder_created`, `hierarchy_dropped`, `hierarchy_repaired`, `privacy_narrowed`,
`text_format_degraded`, `front_levels_flattened`, `front_states_dropped`,
`subsystem_fronts_dropped`, `spans_repaired`, `switch_update_ignored`, `message_split`,
`asset_uri_only`, `asset_missing`, `asset_corrupt`, `merge_undated`, `event_archived`,
`history_inconsistent`, `permission_dropped`.

### 10.4 The import report

After an import, a consumer SHOULD be able to produce an `ImportReport`: the file's manifest,
counts per module (`imported`, `merged`, `unchanged`, `degraded`, `archived_only`, `skipped`,
`failed`) and every warning. It SHOULD show the person a summary before or after committing the
import, and MUST NOT report success while silently dropping data.

### 10.5 Re-import and merge

A consumer importing a record MUST look for an existing one by `id`, then by `source_refs` (same
`app`, `collection` and `id`). If none is found, the record is new. If one is found:

- the copy with the later `updated_at` wins, whole-record (a newer tombstone deletes; an older
  copy never brings a deleted record back);
- if either copy lacks `updated_at`, the existing record wins and the consumer warns
  `merge_undated`.

Events merge by union (§9.2). Apps with finer-grained merging (Chorus merges per field) MAY do
better than this, but MUST NOT do worse: a re-import of an unchanged file changes nothing.

---

## 11. Privacy and safety

### 11.1 This is sensitive data

Plural-system data describes people's minds and often their health. Under the GDPR it may be
special-category data (Art. 9). Producers and consumers MUST treat a PluralSpec file as
confidential: don't upload it to third parties for processing, don't log its contents, and keep
it out of URLs.

### 11.2 Export profiles

`manifest.profile` says what a file is for:

- **FULL**: everything, including private records and full history, for backups and moving
  between one's own apps. Producers SHOULD offer encryption (§3.5) and say plainly that the file
  holds private data.
- **SHARED**: only what chosen visibility classes may see (`manifest.shared_for_class_ids`), for
  giving to someone else. A SHARED file contains only records and fields whose audience includes
  one of those classes (directly or through `includes_class_ids`), drops `ext` entries unless the
  producer knows they are safe, drops tombstones, drops `source_refs` that could identify
  accounts elsewhere, and contains no events unless every event's targets are themselves in the
  file (an earlier, more private version of a record MUST NOT leak through `before`).

### 11.3 Never widen

Importing MUST NOT make anything more visible than its audience said. Where the target cannot
express an audience, it narrows it (§4.7). A consumer MUST NOT publish, share or notify followers
about imported records merely because they were imported (no "Kai started fronting"
notifications for a history import).

### 11.4 Locks and member-private content

`Member.locked` and member-limited audiences are soft, in-system limits, not encryption.
Consumers that cannot honour them SHOULD warn before import. Sealed (client-encrypted) content is
out of scope for 0.1.

---

## 12. Conformance

| Class | A consumer or producer that… |
| --- | --- |
| **Core producer** | writes valid files (§10.2) with the `core` module, stable ids, and warnings for loss. |
| **Core consumer** | reads both file forms, both body encodings and both text offset units; imports `core` and `fronting` (including the fold, §5.5); honours audiences (§11.3); merges by id (§10.5); and reports imports (§10.4). |
| **Module support** | for each module it claims, reads and/or writes every record of it. For `history`: keeps event order, merges by id, applies custom events' effects, and keeps unknown custom events (§9). |
| **Round-trip** | re-exporting an imported file gives back every record and event, with the same ids, `source_refs`, unknown `ext` and custom events, apart from what its warnings declare. |

A conformance suite (fixtures + a validator) is planned (DESIGN_NOTES, readiness path).

---

## 13. Versioning and registries

### 13.1 Versions

`pluralspec_version` is semantic: `MAJOR.MINOR.PATCH`. Within a major version the schema only
grows: new fields, records, enum values and modules; nothing is renamed, renumbered or retyped,
and removed fields are `reserved`. The proto package carries the major version
(`pluralspec.v1`). Drafts before 1.0.0 may break anything and say so in their changelog.

### 13.2 Registries

Kept with the spec and changed by pull request:

- **App ids**, for `SourceRef.app`, `Producer.app_id`, `ext` namespaces and custom event
  namespaces; shared with PluralPort: `prism`, `sheaf`, `simply_plural`, `pluralkit`, `octocon`,
  `plural_star`, `lighthouse`, `openselves`, `ampersand`, `pluralspace`, `tupperbox`, and
  `chorus`. Others use reverse-DNS names until registered.
- **Label kinds** (§4.5), **permissions** (§4.8), **modules** (§3.4) and **warning codes**
  (§10.3).
- **Custom event types** apps want standardised, and the **upgrades** that standardise them
  (§9.5).

### 13.3 Future modules

Reserved names: `reminders`, `habits`, `safety`, `proxy` (Discord proxy settings and autoproxy),
`sharing` (grants beyond visibility classes).

---

## Appendix A. Mappings

### A.1 PluralPort (formerly OpenPlural) v0.1 → PluralSpec

| PluralPort | PluralSpec |
| --- | --- |
| envelope `openplural_version`, `producer`, `capabilities.modules`, `warnings` | `Manifest` (the source version in `ext.pluralspec.from_pluralport`); `text_offset_unit` UTF16 unless the converter re-counts |
| file-local `id` | UUIDv5 over `pluralport|<record type>|<id>` unless already a UUID; original in `source_refs` (app `pluralport`) |
| `systems[]` (`parent_system_id` set) | `System`; nested systems → `Group` kind SUBSYSTEM |
| `members[]` with `is_custom_front` | `Member`, or `State` when `is_custom_front` |
| `members[].birthday {value, precision, year_visible}` | `PartialDate`; `year_visible: false` → `field_audience["birthday.year"]` = only the system |
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
| `privacy.visibility` | `Audience`: `private`/`unknown` → only the system; `trusted` → a CUSTOM class "Trusted"; `friends` → the FRIENDS class; `public` → the PUBLIC class; `privacy.source` → `ext.<app>.privacy` |
| `extensions` | `ext` |

PluralSpec → PluralPort drops what PluralPort cannot hold, with warnings: custom visibility
classes (→ `trusted` or `private`), subsystem internal fronts, message segments and co-authors,
history, and the fields listed in PRIOR_ART.md.

### A.2 PluralKit datafile v2 → PluralSpec

| PluralKit | PluralSpec |
| --- | --- |
| system `id`, `uuid` | `System` + `source_refs {app: pluralkit, collection: system, id, uuid}` |
| `tag`, `pronouns`, `avatar_url`, `banner`, `color`, `description` | same fields; URLs → URI-only assets |
| `privacy.*_privacy` | `audience` / `field_audience`: `public` → the PUBLIC class (created if needed), `private` → only the system; system-level `front_privacy`, `front_history_privacy`, `member_list_privacy`, `group_list_privacy` = `public` → grants `front.view`, `front.history`, `members.list`, `groups.list` on the PUBLIC class |
| `members[]` | `Member`; `proxy_tags` as is; `keep_proxy`, `tts`, `autoproxy_enabled`, `webhook_avatar_url`, message count → `ext.pluralkit` |
| `groups[]` (`members` inline) | `Group` + `GroupMembership` per member; `icon` → `emoji` if an emoji, else an asset |
| `switches[] {timestamp, members}` | `FrontSwitch` REPLACE, entries FRONTING in PluralKit's order (which becomes `position`), none primary: PluralKit has order but no primary; then the fold |

### A.3 Simply Plural export → PluralSpec

| Simply Plural | PluralSpec |
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
| `privacyBuckets` | `VisibilityClass` CUSTOM each; legacy `private` / `preventTrusted` flags → only the system / a CUSTOM "Trusted" class |
| `friends` | `Contact` + the FRIENDS class; bucket assignments → `Contact.class_ids`; per-friend settings (see members, see front, front notifications) → grants on the matching class (`members.list`, `front.view`, `front.notify`) |
| reminders | custom events `simply_plural:reminder.*` and `ext.simply_plural` until the `reminders` module exists |

### A.4 PluralSpace GDPR export → PluralSpec

| PluralSpace | PluralSpec |
| --- | --- |
| `system` (`slug`, `visibility`) | `System`; `slug` → `ext.pluralspace.slug` |
| `members[]` (`role[]` strings, `groups[]` names) | `Member`; each role → `Label` kind `role` + assignment; group names only cross-check memberships |
| `fronts[]` | `FrontSpan` per row; the duplicated `comment` → one `FrontComment` per distinct interval |
| `journal_entries[]` (`members` snapshots, `date`) | `Post` ENTRY; `members` → `about_member_ids`; `date` → `entry_date`; `visibility_level` → `ext.pluralspace` and only the system |
| `chat_channels[].messages[]` (`member_name` only) | `Channel`, `Message`; authors resolved by name → placeholders + `identity_by_name` |
| `member_groups[]` | `Group` (flat; `hierarchy_dropped`) + memberships from the group side |
| `polls[]` | `Poll` + `Vote` |
| sharing roles (Owner, Partner, Trusted Friend, Friend) | `VisibilityClass` CUSTOM per role except Friend → FRIENDS; Owner is the system itself; each role's permissions → grants (unmatched ones namespaced `pluralspace:*`) |
| `media_files[]`, `media/` | `Asset` + blobs |

### A.5 Chorus → PluralSpec

| Chorus | PluralSpec |
| --- | --- |
| `account` kind system / person + `system` profile | `System` kind PLURAL / SINGLET |
| `member` (`sigils`, `proxy_tags`, `short_id`, `pk_id`, `notify_policy`, `is_locked`) | `Member` (`short_id` → `ext.chorus`, `pk_id` → `source_refs`, `notify_policy` → `ext.chorus`, `is_locked` → `locked`) |
| `member_group` kind group / subsystem | `Group` |
| `state` | `State` |
| `field` definitions and values | `FieldDefinition`, `FieldValue` |
| `switch` rows | `FrontSwitch` (`notify`, `based_on`, device → `ext.chorus`; retracted → deleted) |
| `front_interval` | `FrontSpan` in the system front (`front` → FRONTING, `cocon` → CO_CONSCIOUS, `present` → PRESENT) |
| `post` kind note / entry | `Post` POST / ENTRY |
| `space` internal + `channel` + `message` (+ segments) | `Channel`, `Message`; text + entities → `RichText.entities`, positions as they are (`text_offset_unit` UTF16) |
| `bucket`, follows | `VisibilityClass` CUSTOM per bucket, the FRIENDS class for followers, INSTANCE for `server`; follower ceilings → grants: `front.notify` (delay, fuzz, time shown, digest, quiet hours in `params.chorus`), `front.view`, `front.history`, `front.stats` from the ceiling's `share_current_front`, `share_history`, `share_stats` |
| visibility JSON | `Audience` (`followers` → FRIENDS class, `server` → INSTANCE class, `buckets` → their classes, `members` → `member_ids`, `private` / `system_only` → empty) |
| `reaction`, `emoji` | `Reaction`, `CustomEmoji` |
| `relationship`, `reltype` | `Relationship`, `RelationshipType` |
| op log | `Event`s in op order: `*.create` / `*.set` / `*.delete` / `*.restore` / archive ops → standard events with `fields` from the op payload; `message.edit` → `message.update` with `before`; `front.*` → `front_switch.*` events; Chorus-only ops (`front.review_resolve`, `highlight.*`, `feed.*`, `stage.*`, `draft.*`) → `chorus:*` custom events; service ops (`follow.*`, `pref.set`, `admin.*`) omitted; HLC → `ext.chorus.hlc` |
| blobs | `Asset` + blobs |
