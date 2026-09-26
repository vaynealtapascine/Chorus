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

## D5 · Subsystem fronting — internal fronts proposed; blends, "fronting for" and decoherence open

> "Fields for subsystem fronting?" … "What would be a means to model blends, fronting for a
> subsystem, etc? More broadly, how does this model subsystems that 'decohere' into separate
> parts (like ours)?"

**In the draft now:** a subsystem can front as a unit (a span whose subject is the group, which
needs `can_front`), and each subsystem can have an **internal front** (`scope_group_id` on
spans and switches: who is at the front *inside* it). SPEC §5.3.

**What the draft cannot say about a subsystem that decoheres into separate parts:**

- *Coherent*: the subsystem fronts as its group, but a group is not a full someone: it can't
  author messages or posts, and has no pronouns, proxy tags or member fields.
- *Decohered*: its parts front as ordinary members, and nothing ties those spans back to the
  subsystem, so "was the subsystem out?" misses those hours.
- *Partly decohered*: a group span and a member span at the same time can't say that the member
  is no longer inside the group.
- *Blends* (members merged for a while): no way at all.

**Proposal: composite identities** (not in the schema yet; waiting for the questions below).

1. **`Member.composition`** `{kind, member_ids, group_id}`: a member who *is* several members
   together. Because it is a Member, it can speak, write, have pronouns, proxy tags, fields and a
   profile, and front like anyone.
   - `COLLECTIVE`: a subsystem's coherent self. `group_id` points at the subsystem and its parts
     follow the group's membership; `Subsystem.collective_member_id` points back.
   - `BLEND`: two or more members merged for a while. May be unnamed (apps show "Kai + Moss"),
     and is reused when the same parts blend again.
   - `FUSION`: a lasting merge; the parts are usually archived with a reason.
2. **`FrontSpan.parts`**: for a composite subject, which parts are in it during this span
   (empty = all). This is what makes *partial* decoherence expressible.
3. **`FrontSpan.for_group_id`**: this subject is out *as part of*, or *on behalf of*, a subsystem
   without the subsystem itself being the subject. Covers both "fronting for a subsystem" and
   the parts of a decohered subsystem.
4. **Invariant**: in each front, a member is in one place at a time: not inside a composite's
   `parts` and also in a span of their own.
5. Internal fronts (`scope_group_id`) stay, for what happens inside a subsystem.

A decohering day, with "the Garden" (Kai, Moss, Wren) and its collective self "Garden":

| Time | Spans in the system front |
| --- | --- |
| 09:00–11:00 | Garden (collective), parts: all |
| 11:00–12:30 | Garden, parts: Kai, Wren · Moss (for the Garden) |
| 12:30–14:00 | Kai, Moss, Wren, each for the Garden |
| 14:00– | Garden, parts: all |
| 16:00–16:45 | Kai + Moss (a blend) · Wren (for the Garden) |

"Was the Garden out?" = spans of its collective, of the group itself, or `for_group_id` = it.
"Who was actually there?" = the parts plus everyone with a span of their own.

Apps without composites keep a composite as an ordinary member and put `parts` / `for_group_id`
in `ext` with a `composition_flattened` warning, so nothing is lost on the way through.

Alternatives considered: a span-level `blend_key` (spans sharing it fronted blended) is lighter
for one-off blends but can't be named or speak; making every member able to contain members (the
2024 sketch) is the most direct model but no other app could map it, while a composite member
linked to a group gives the same result and stays mappable.

**Questions for the owner** (the answers decide the details):

1. When your subsystem is coherent, is it its own someone (a name, pronouns, speaking as itself),
   or the parts together without an identity of their own?
2. Can it partly decohere (some parts split off while the rest stays together)?
3. When decohered, do the parts front "as the subsystem" (it still counts as out) or simply as
   themselves?
4. While it is coherent, are the parts still there in some sense (aware inside, reachable), or not
   present at all?
5. Should blends be their own someone (named, able to post), or only a record that these
   members fronted blended?

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

## D8 · Visibility classes with permissions — settled

> "Visibilities classes should be customizable and so they should be their own record." … "yes,
> it should also be able to carry perms."

- **`VisibilityClass`** record: `name`, `description`, `color`, `emoji`, `sort_key`, a `kind`
  that says who is in it — CUSTOM (contacts assigned via `Contact.class_ids`), FRIENDS,
  INSTANCE, PUBLIC — and `includes_class_ids` for nesting ("Partners" ⊂ "Close friends").
  Built-in kinds are records too, so a system can rename or colour them.
- **`Audience`** = `class_ids` (the system plus everyone in those classes) or `member_ids` (an
  in-system limit), or empty for "only the system". Narrowing never widens (SPEC §4.7).
- **Permissions** (SPEC §4.8): each class carries `grants`, each a registered or namespaced
  `permission` with optional `params`: `front.view`, `front.history`, `front.stats`,
  `front.notify` (params for delays and digests — Chorus's follower ceilings live here),
  `front.log`, `members.list`, `groups.list`, `posts.reply`, `posts.react`, `messages.send`,
  `polls.vote`. Grants add up; there are no denials; the audience of each record stays the
  ceiling, so a grant never reveals a record hidden from the class.

## D9 · Files: ZIP container with a record stream; age for encryption — settled

`manifest.json` + `data.jsonl` or `data.binpb` + content-addressed `blobs/`, or a single JSON
document for small files (SPEC §3.3).

> "What do you mean by age layer?" … "ah ok. yeah."

Encryption is the whole finished file encrypted with **age** ([age-encryption.org](https://age-encryption.org/v1)),
by passphrase or public key: `system.pluralspec.age`; decrypting gives back the ordinary file.
Recommended for FULL exports; PluralSpec defines no encryption of its own (SPEC §3.5).

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

## D14 · Licence — settled: schema CC BY 4.0, text CC BY-NC-SA 4.0

> "CC BY-SA-NC." … "I think the schema should be CC-BY, but the text should be NC."

- **Schema and data** — `proto/`, generated schemas (`schema/`), `examples/`, and fixtures
  published with the spec — are **CC BY 4.0**: any app, commercial or not, may compile them into
  its code, with attribution. Each `.proto` carries `SPDX-License-Identifier: CC-BY-4.0`.
- **Specification text** — the Markdown documents — is **CC BY-NC-SA 4.0**.
- The rest of the Chorus repository stays MIT. `spec/LICENSE` says all of this.
- PluralPort is MIT; proposals sent there are the owner's to license, and text copied from this
  spec into PluralPort needs the owner's permission under MIT terms.

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
