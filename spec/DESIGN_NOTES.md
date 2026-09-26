# PluralSpec — design notes and decisions

The choices behind the draft, with a status each:
**settled** (the owner decided; don't re-ask), **provisional** (settled for now, the owner will
revisit), **proposed** (in the draft, waiting for the owner's review), **open**.
Settled items that change Chorus also get a `D-xxx` in `docs/DECISIONS.md`.

Owner answers of 2026-09-26 are quoted where they settle something.

---

## D0 · Name — settled: PluralSpec

> "PluralSpec then since i guess it's not taken."

Checked 2026-09-26: no plural-community spec, repository, npm package or crate uses the name (a
Go i18n library has an unrelated internal type `PluralSpec`). Package `pluralspec.v1`, URI scheme
`pluralspec:`, files `.pluralspec` / `.pluralspec.json`, `ext` namespace `pluralspec`.

## D1 · Relationship to PluralPort — settled: superset, changes offered as proposals

> "PluralPort superset, with changes submitted as proposals."

PluralPort is the new name of OpenPlural (PluralSpace/openplural; the PluralPort repository says
so). PluralSpec reads every PluralPort v0.1 file without loss (SPEC Appendix A.1) and writes
PluralPort with warnings. The fixes in PRIOR_ART.md go to PluralPort as proposals **only after
the owner has reviewed the spec**; nothing is sent upstream before then.

## D2 · Schema language: proto3, JSON first-class — settled (owner asked for ProtoBuf)

proto3 is the source of truth; canonical JSON and delimited binary are its encodings (SPEC §3).
The JSON quirks that matter, and how the spec handles them:

- printers emit `lowerCamelCase` unless told otherwise → the spec requires `snake_case` output
  and camelCase acceptance (verified: `buf convert` reads snake_case, prints camelCase);
- defaults are omitted (`position: 0` disappears) → readers treat absent as default;
- enums print long names and strict parsers reject unknown ones → unknown values are non-fatal;
- 64-bit integers print as strings → readers accept numbers too.

Generated artifacts, from the same `.proto`: JSON Schema (for JSON-only apps), TypeScript
(protobuf-es), Rust (prost + protox, no `protoc` needed), Kotlin (protobuf-kotlin-lite).

## D3 · Stable ids — settled

> "Yes, they should stay persistent."

UUIDs that never change for a record; UUIDv5 derivation for sources without UUIDs, which also
makes converting the same foreign export twice idempotent (SPEC §2.2).

## D4 · Fronting truth: spans, with switches as annotations — provisional

> "That's fine for now, but I will go over this more later."

`FrontSpan` is the truth; `FrontSwitch` is an optional log; a normative fold turns switch-only
files into spans (SPEC §5). Why spans: most apps store intervals; durations need no replay;
independent co-fronting needs no ordering tricks. What only the switch log keeps: note-only
switches and pure reorderings, which is why the log stays as an optional record. Note that with
history (D11) a file can now also carry every change to spans and switches as events.

## D5 · Subsystem fronting — proposed (owner asked "Fields for subsystem fronting?")

Before: a subsystem could only front as a unit (a span whose subject is the group, which needs
`can_front`). Added in this draft:

- **`scope_group_id`** on `FrontSpan` and `FrontSwitch`: each span belongs to a *front* — the
  system's (empty) or a subsystem's **internal front** (the subsystem's id). A subsystem can be
  out as a unit in the system front while its own members take turns at its internal front, and
  it can have internal front history while it isn't out at all.
- The fold and the invariants run per front (SPEC §5.3–5.5).
- Existing fields that describe subsystems: `Group.kind = SUBSYSTEM`, `can_front`,
  `Subsystem.structure` (with / without a main member), `main_member_id`, and a subsystem `tag`.

SPEC §5.3 has a table of the four situations this covers. For review: are there subsystem
fronting situations it doesn't cover? Candidates we have not modelled: blends (members merged
into one), and a member fronting "for" a subsystem without the subsystem itself being out.

## D6 · Custom fronts are their own record — settled

> "Yes, own record."

`State` (SPEC §4.3).

## D7 · Text positions — settled: declared unit, UTF-16 by default

> "Maybe let's have a 'format option' to declare if UTF-8 or UTF-16, but interpret as UTF-16 if
> unspecified."

`Manifest.text_offset_unit` = UTF16 (default) or UTF8, applying to every position in the file:
entity spans, message segments, quote ranges (SPEC §2.6). Chorus writes UTF16 and needs no
conversion. A third option (Unicode code points, native to Python and Swift) can be added later
as a new enum value without breaking anything.

## D8 · Visibility classes are records — proposed (owner: "customizable … their own record")

> "Visibilities classes should be customizable and so they should be their own record."

Replaces the fixed tiers and `Bucket` of the first draft:

- **`VisibilityClass`** record: `name`, `description`, `color`, `emoji`, `sort_key`, and a
  `kind` that says who is in it — CUSTOM (contacts assigned via `Contact.class_ids`), FRIENDS,
  INSTANCE, PUBLIC. Built-in kinds are records too, so a system can rename "Friends" to
  "Followers" or colour "Public". `includes_class_ids` nests classes ("Partners" ⊂ "Close
  friends").
- **`Audience`** = `class_ids` (the system plus everyone in those classes) or `member_ids` (an
  in-system limit), or empty for "only the system".
- Narrowing when a target can't express a class uses the kinds' subset order (SPEC §4.7), so a
  custom class is never widened to "friends".

For review: whether classes should also carry *permissions* (PluralSpace's roles decide what a
friend can do, not only see — e.g. "can see front history"). The draft expresses those as the
audience of each record type, which covers seeing but not acting.

## D9 · Files: a ZIP container with a record stream, optional age encryption — proposed

`manifest.json` + `data.jsonl` or `data.binpb` + content-addressed `blobs/`, or a single JSON
document for small files (SPEC §3.3).

> "What do you mean by age layer?"

**age** ([age-encryption.org](https://age-encryption.org/v1)) is a small, modern, widely reviewed
format and tool for encrypting a whole file, with implementations in Go, Rust (rage), TypeScript
(typage) and others. "An age layer" means: take the finished `.pluralspec` file and encrypt the
whole thing with age, giving `system.pluralspec.age`, with either a passphrase or a public key.
Decrypting gives back the ordinary file. PluralSpec would recommend it for FULL exports (which
contain everything private) instead of inventing its own encryption or relying on ZIP's
password protection, which is weak or unevenly supported. For review: keep age as the
recommended encryption, or leave encryption out of the spec entirely?

## D10 · Closed enums vs open vocabularies — proposed

Enums where the set is closed and meaning drives behaviour (levels, class kinds, field types,
switch kinds, post and channel kinds, change kinds); strings with a registry where apps keep
inventing values (label kinds, warning codes, app ids, custom event types).

## D11 · History from the start — proposed (owner: cover history now)

> "I would like to cover history as early as now, with custom events able to be converted
> forwards into future spec versions."

Module `history` (SPEC §9, `record.proto`):

- **`Event`**: immutable; `at`, `created_at`, `type`, `actor_member_id`, `origin`, `targets`,
  and a body.
- **Standard events**: `<record type>.<change>` (`member.update`) with the whole record `after`
  the change, the changed `fields`, and optionally `before`. Because `after` is a whole record,
  every record type has history, including types added later, with no new event types. Message
  and post edit history is `before`/`after` of `*.update` events (the first draft's
  `MessageRevision` is gone).
- **Custom events**: `<namespace>:<name>`, a payload `version`, a JSON `payload`, a readable
  `summary`, and the standard `effects` they had. Consumers must keep custom events they don't
  understand and apply their effects when replaying.
- **Forward conversion**: apps register custom types they want standardised; when a later spec
  version adds a standard form, it ships an *upgrade* (`from` type + version → `to` type + field
  mapping), and consumers apply upgrades to older files, keeping the original under
  `ext.pluralspec.upgraded_from` (SPEC §9.5).
- Events merge by union on id and keep the producer's order; `manifest.history` says whether
  replaying them reproduces the records.

Deliberately not a sync protocol (SPEC §9.7): no clocks or causality between devices.

## D12 · Merge rule: newest record wins — settled

> "Yes."

Whole-record last-writer-wins by `updated_at`, match by id then by source ref; events merge by
union (SPEC §10.5).

## D13 · Chat scope — proposed

A system's own channels and messages, plus what it can read in shared channels (marked
`shared`, external authors as display names). Spaces, roles and permissions between accounts
are server state and out of scope.

## D14 · Licence — settled: CC BY-NC-SA 4.0

> "CC BY-SA-NC."

The whole `spec/` directory (text, schema, examples) is under CC BY-NC-SA 4.0 (`spec/LICENSE`);
the rest of the Chorus repository stays MIT. Consequences worth knowing before 1.0:

- The `.proto` files are copied into implementations and compiled into their code, so
  **NonCommercial also applies to the schema**: an app that charges money (as Simply Plural's
  paid tier did) could not use the schema files as they are. If that is not the intent, the
  schema could be licensed separately (e.g. MIT) while the prose stays BY-NC-SA.
- PluralPort is MIT. Proposals sent there are the owner's to license as the owner likes, but
  text copied from this spec into PluralPort would need the owner's permission under MIT terms.

## D15 · Where the spec lives — settled

> "Leave in Chorus, then move to new repo once we completely pin it down."

---

## Readiness path

Owner, 2026-09-26: work through step 4, then stop; the owner reviews the spec before anything
goes to PluralPort.

| Step | Done when | Status |
| --- | --- | --- |
| 0 · Draft | SPEC.md, `.proto` building and lint-clean (`buf build`, `buf lint` STANDARD), the example round-tripping JSON → binary → JSON | done 2026-09-26 (second revision: decisions D0–D15 applied) |
| 1 · JSON Schema + fixtures | JSON Schema generated from the proto (snake_case); a fixture per source app: PluralKit datafile v2, Simply Plural export, PluralSpace GDPR export, PluralPort v0.1, each with its expected PluralSpec output | |
| 2 · Validator | CLI checking V1–V12 (SPEC §10.2) and printing an ImportReport-shaped result; runs in CI on the fixtures | |
| 3 · Converters | PluralKit v2, Simply Plural, PluralPort v0.1, PluralSpace → PluralSpec; PluralSpec → PluralPort | |
| 4 · Chorus | export (FULL and SHARED, with history) and import (PluralSpec and, through the converters, the rest) | |
| 5 · PluralPort proposals | after the owner's review | **on hold** |
| 6 · 1.0 | two independent implementations interoperate on the fixtures | on hold |

## Chorus implementation sketch

- A `pluralspec` crate in the workspace (generated types via `prost` + `protox`, the validator,
  the converters), kept out of `chorus-core` so the core stays small; a `pluralspec` binary for
  the validator.
- Export: projections → records (SPEC Appendix A.5), `front_interval` → spans, `switch` →
  switches, op log → events; ids reused as they are (Chorus ids are UUIDv7); a new export kind
  beside the D-068 bundle.
- Import: PluralSpec (and converted formats) → Chorus ops through the normal ingestion path, so
  imports sync and can be undone like anything else; events become ops where they map, custom
  events stay in an archive table.
- Fixtures under `fixtures/pluralspec/`, shared by the validator, the converters and Chorus's
  round-trip test.
