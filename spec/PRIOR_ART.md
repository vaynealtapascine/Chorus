# PSDS — prior art

What already exists for storing and moving plural-system data, what each gets right, and what
PSDS takes or fixes. Surveyed 2026-09-26. Where a claim comes from someone else's research it
says so.

## Summary

| | Kind | Encoding | Fronting shape | Groups | Custom fields | Privacy | Portable? |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **PluralKit** datafile v2 | App export (Discord bot) | JSON | Switch events (ordered member list per timestamp) | Flat | None | Per-field public/private | Yes, widely imported |
| **Simply Plural** export | App export (MongoDB dump) | JSON keyed by collection | Per-member intervals; custom fronts as a separate collection | Nested (`parent: "root"`) | Definitions + values embedded on members, numeric type ids | Privacy buckets, friends | Raw; the app shut down 2026-07-01 |
| **PluralSpace** GDPR export | App export (Laravel/Postgres) | ZIP: `manifest.json` + `data.json` + `media/` | One row per member per interval; co-fronting = rows with equal times | Nested in the app, **flat in the export** | Definitions; value shape unverified | Numeric `visibility_level` (undocumented) | Regulatory dump, not a round-trip backup |
| **OpenPlural v0.1** (now also "PluralPort") | Community interchange proposal | JSON only | Both periods and events | Nested + taxonomy | Definitions + values, `value: unknown` | Conservative bucket + raw source | Yes: the purpose |
| **Owner's `plural.proto`** (2024) | Sketch | proto3 | — | Subsystems nested inside members, with main/no-main typology | — | — | Did not compile (see below) |
| **Chorus** | App (event-sourced) | Op log JSONL, SQLite, CSV | Switch ops folded into per-subject intervals with levels | Groups + nesting subsystems that can front | Typed definitions + values | Audience modes, buckets, per-field, follower ceilings | Own format + PluralKit import |

**PluralSpec:** not found. No spec, repository, package or page by that name turned up (web,
GitHub code/repos, npm, crates.io); the name only matches unrelated i18n pluralisation code.
Pending a link from the owner, it is not covered here.

---

## PluralKit

Sources: [PluralKit API models](https://pluralkit.me/api/models/), [source](https://github.com/PluralKit/PluralKit),
OpenPlural's [research notes](https://github.com/PluralSpace/openplural/blob/main/docs/apps/pluralkit.md).

- **Datafile v2**: `{version: 2, id, uuid, name, description, tag, pronouns, avatar_url, banner,
  color, created, privacy, members[], groups[], switches[], accounts[], config}`.
- **Members**: short `id` (5–6 letters) + `uuid`; `name`, `display_name`, `color`, `birthday`
  (a sentinel year hides the year), `pronouns`, `avatar_url`, `webhook_avatar_url`, `banner`,
  `description`, `proxy_tags[{prefix, suffix}]`, `keep_proxy`, `tts`, `autoproxy_enabled`,
  message count, and a privacy object per field (`name_privacy`, `birthday_privacy`, …).
- **Groups**: flat; `members` listed on the group.
- **Switches**: `{timestamp, members: [ids in fronting order]}`. Durations are inferred from the
  next switch; an empty list is a switch-out.

**Takes:** proxy tags as a list; both ids preserved (`SourceRef.id` + `SourceRef.uuid`); per-field
privacy (`field_audience`); switch-out as an empty REPLACE; fronting order as position.
**Gaps for a general format:** no custom fields, no nesting, no co-fronting levels, no notes.

## Simply Plural

Sources: [API source](https://github.com/ApparyllisOrg/SimplyPluralApi), [discontinuation notice](https://apparyllis.com/simply-plural-will-be-discontinued/),
OpenPlural's [research notes](https://github.com/PluralSpace/openplural/blob/main/docs/apps/simply-plural.md).

- The export is the account's MongoDB collections: `members`, `frontStatuses` (custom fronts),
  `frontHistory`, `groups`, `customFields`, `notes`, `comments`, `polls`, `channels`,
  `channelCategories`, `chatMessages`, `boardMessages`, reminders, `privacyBuckets`, `friends`.
- `frontHistory` rows: one member (or custom front) per row, `startTime`/`endTime` in epoch ms,
  `live`, `custom`, `customStatus`. Co-fronting = overlapping rows.
- Custom field types are numbers 0–7 (text, colour, date, month, year, month-year, timestamp,
  month-day); values live on the member as `info: {fieldId: value}`.
- Privacy moved from per-record flags (`private`, `preventTrusted`) to buckets.

Simply Plural stopped on 2026-07-01, so its exports are now a fixed, finite target: many people
are holding one and looking for a home. That makes a lossless importer valuable.

**Takes:** custom fronts as a separate kind of subject (PSDS `State`); per-subject intervals;
date precision on fields; board messages and member notes as distinct post kinds; buckets.

## PluralSpace

Sources: [pluralspace.app](https://pluralspace.app/), [features](https://pluralspace.app/features),
OpenPlural's [research notes](https://github.com/PluralSpace/openplural/blob/main/docs/apps/pluralspace.md)
(built from two inspected GDPR exports and maintainer notes; the server is closed source).

- ZIP with `manifest.json` (`format_version: "1.0"`, GDPR article citations), `data.json`
  (`system`, `members`, `fronts`, `journal_entries`, `chat_channels`, `polls`, `thoughts`,
  `member_groups`, `custom_fields`, `media_files`) and `media/`.
- Numeric ids; ISO-8601 times with offsets; snake_case.
- `members[].role` is a list of free-text strings; `members[].groups` are group **names**.
- Fronts: one row per member per interval, `type`/`type_name` (only "front" seen), `comment`
  copied onto every co-fronting row.
- Chat messages carry `member_name` only, no member id; journal `members` are `{id, name}`
  snapshots; group nesting exists in the app but is dropped from the export.

**Takes:** the ZIP container with a manifest in front; journals with a separate logical `date`;
polls at system level.
**What it shows PSDS must forbid:** identity by name (breaks on rename), denormalised copies that
can disagree, dropping structure silently. PSDS requires ids for every reference and warnings for
every loss.

## OpenPlural v0.1

Sources: [site](https://skylartaylor.github.io/openplural/), [repository](https://github.com/PluralSpace/openplural)
(MIT; the PluralPort repository now says "PluralPort is the new name of the OpenPlural Spec").
Research-backed: it maps Prism, Sheaf, PluralSpace, Simply Plural, PluralKit, Octocon, Plural
Star, Lighthouse, OpenSelves, Ampersand and Tupperbox.

- One JSON envelope: `openplural_version`, `producer`, `capabilities.modules`, typed arrays
  (`systems`, `members`, `groups`, `group_memberships`, `taxonomy_terms`,
  `taxonomy_assignments`, `custom_fields`, `custom_field_values`, `front_periods`,
  `front_events`, `front_comments`, `notes`, `assets`), optional modules (`chat`, `boards`,
  `relationships`, and sketches of polls, reminders, habits, proxy, sharing, safety), and
  `extensions` + `warnings`.
- Every record may carry `source_refs`, namespaced `extensions`, and `privacy`.
- An importer contract with an `ImportResult` (counts per module, warnings).

This is the closest thing to a standard and PSDS should stay compatible with it (DESIGN_NOTES
D1). What PSDS changes, and why:

| # | OpenPlural v0.1 | Problem | PSDS |
| --- | --- | --- | --- |
| 1 | Descriptive tables, no normative language, no conformance levels | Two apps can both "follow the spec" and still not interoperate | RFC 2119 requirements, validation rules (SPEC §9.2), conformance classes (§11) |
| 2 | JSON only | No compact binary form for large histories; no schema that tools can generate code from | proto3 schema is the source of truth; canonical JSON and delimited binary are both encodings of it (§3) |
| 3 | IDs are **file-local** | Re-importing a later export duplicates everything; no merge | Ids are stable UUIDs across exports; re-import merges (§2.2, §9.5) |
| 4 | `front_periods` **and** `front_events`, no rule when both are present | Two sources of truth that can disagree | Spans are the truth; switches are annotations; a normative fold turns switch-only files into spans (§5) |
| 5 | `front_role` mixes tiers (`co_conscious`) with ordering (`primary`) | Can't say "the primary co-conscious member", nor order co-fronters | `level` + `primary` + `position` are separate (§5.2) |
| 6 | Custom fronts are a `member.is_custom_front` flag | They get counted, listed and authored as members | A separate `State` subject (§4.3) |
| 7 | Groups (`parent_group_id`) **and** nested systems (`parent_system_id`) | Two ways to model a subsystem | One: `Group` with `kind: SUBSYSTEM`, which can front (§4.4) |
| 8 | `CustomFieldValue.value: unknown` | Every importer re-invents type checks | A typed `oneof` per field type; select values point at option ids (§4.6) |
| 9 | Text is "markdown if flagged", no mention syntax | Mentions and formatting don't survive, ids get lost in text | `RichText`: plain, PSDS markdown (with `psds:` mention links) or entity spans (§2.6) |
| 10 | `Note.member_id` means "subject", `author_member_ids` means "writers" (noted as an open question) | Board messages, notes and journals blur | `Post` with explicit authors / about / recipients and a kind (§6) |
| 11 | Privacy rounds to one of five words; buckets only in raw `source` | Buckets are lost on every import | `Audience` with buckets and in-system member limits; a strictness order; import never widens (§4.7, §10) |
| 12 | Assets inline as base64 or by URL | Large files bloat JSON; URLs rot | Content-addressed blobs in a ZIP container; inline bytes only in single-document files (§8) |
| 13 | No tombstones in core | A merge can't tell "deleted" from "not exported" | Common header with `deleted_at` and `archived_at` (§2.4) |
| 14 | Chat messages have one author | Joint and segmented messages (Chorus) can't be represented | Several authors and per-range segments (§7) |

## The owner's `plural.proto` (February 2024)

A first sketch at `D:\!!Self\dev\plural-system\plural.proto`: `PluralSystem {name, display_name,
description, members, origin, avatar_url, pluralkit_url, other_information_urls}` and
`SystemMember {name, username, description, emoji, color, avatar_url, is_subsystem,
subsystem_typology, members}` with `SubsystemType {NOT_SUBSYSTEM, IS_MAIN, HAS_MAIN, NO_MAIN}`.

It does not compile: `avatar_url = 4` and `is_subsystem = 5` reuse field numbers 4 and 5, and
`optional SystemMember members = 7` holds one child instead of a list. The ideas carry over:

- **Subsystem typology** → `Subsystem.structure` (`WITH_MAIN` / `WITHOUT_MAIN`) and
  `Subsystem.main_member_id` (the "IS_MAIN" member), on a `Group` of kind `SUBSYSTEM` rather than
  a member that contains members, so a member can also sit in ordinary groups.
- **Profile links** (`pluralkit_url`, `other_information_urls`) → `links[]` on systems and
  members; the PluralKit id itself goes in `source_refs`.
- **Username** → Chorus handles are account-level; per-member handles are left to `ext`.
- **Origin** → a custom field or a `Label` of kind `source`.

## Chorus

Its own design (docs/ENTITIES.md, DATA_MODEL.md) is the most complete model surveyed for
fronting and chat, and PSDS borrows its shapes: levels (front, co-con, present), primary +
position, subjects that are members, groups or states, per-subject intervals derived from
switches, multi-author messages with segments, plain text + entity spans, audience modes with
buckets and per-field visibility. What Chorus keeps outside PSDS — accounts, devices, follows,
notification ceilings, sessions, webhooks, the op log — is service state, not the system's data
(SPEC §1.2).
