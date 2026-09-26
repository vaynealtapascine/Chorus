# PSDS — design notes and open decisions

The choices the 0.1 draft makes, the alternatives, and what is still open. Each has a status:
**proposed** (in the draft, waiting for the owner), **open** (no default yet), or **settled**.
When one is settled, record it here and, if it changes Chorus, as a `D-xxx` in
`docs/DECISIONS.md`.

---

## D0 · Name — open

"PSDS" (Plural Systems Data Specification) is a working title. It shows up in the proto
package (`psds.v1`), the `psds:` URI scheme, the `.psds` extension and the UUID namespace
comment, so renaming is a search-and-replace now and a breaking change after 1.0. Wanted: short,
unclaimed in the plural-app space, and not "OpenPlural"-adjacent unless it becomes OpenPlural
(D1).

## D1 · Relationship to OpenPlural — proposed: compatible superset, offered upstream

OpenPlural v0.1 is a community effort with maintainers of several apps involved, and its own
README asks for a JSON Schema, fixtures and conformance tests next. A competing standard would
split a small community.

Options:

1. **Superset** (the draft): PSDS stays a lossless target for every OpenPlural file (Appendix A.1),
   exports to OpenPlural with warnings, and its fixes (PRIOR_ART table) are proposed upstream as
   OpenPlural v0.2 issues. If OpenPlural adopts them, PSDS can become "OpenPlural's schema".
2. **Adopt OpenPlural as is** and put Chorus-specific data in `extensions`: least work, but
   inherits the two-sources-of-truth fronting, file-local ids and untyped values.
3. **Independent spec**: freedom, at the cost of fragmentation.

Recommendation: 1. The first concrete step is to open a discussion on the OpenPlural repository
with the table in PRIOR_ART.md once the owner is happy with the draft.

## D2 · Schema language: proto3, with JSON as a first-class encoding — proposed

The owner asked for ProtoBuf. What it buys: one schema that generates types for Rust (prost),
Kotlin/Java, TypeScript (protobuf-es), Swift, Python and Go; a compact binary form for years of
chat; well-defined evolution rules (field numbers). What it costs: proto3's JSON mapping has
quirks that matter for a human-facing format:

- **Field names**: printers emit `lowerCamelCase` unless told to keep proto names. The spec
  requires `snake_case` output (matching OpenPlural and PluralSpace) and camelCase acceptance.
  Verified: `buf convert` round-trips `examples/small-system.json` (snake_case in) and prints
  camelCase out.
- **Defaults are omitted**: `position: 0`, `offset: 0` disappear from printed JSON. Harmless
  for proto readers; hand-written readers must treat absent as default.
- **Enums print long names** (`FRONT_LEVEL_CO_CONSCIOUS`) and strict parsers reject unknown
  names. The spec makes unknown names non-fatal (§3.1), but it depends on consumers setting
  "ignore unknown" options.
- **64-bit integers print as strings.**

Other general implementations, generated from the same `.proto`:

| Artifact | How | Status |
| --- | --- | --- |
| JSON Schema (for people who won't touch protobuf) | `protoc-gen-jsonschema` or buf's JSON Schema plugin, snake_case | to do |
| TypeScript types + codec | `@bufbuild/protobuf` (protobuf-es) | to do |
| Rust types | `prost` + `prost-build` or `protox` (pure Rust, no `protoc` binary) | to do (Chorus) |
| Kotlin | `protobuf-kotlin-lite` | to do (Chorus Android) |
| SQLite reference layout | one table per record type, `ext`/`source_refs` as JSON columns | optional appendix |

Alternative considered: JSON Schema as the source of truth (OpenPlural's plan). Rejected as the
*source* because it can't generate a binary form or strongly typed code across languages as well,
but it should be *generated* for JSON-only apps.

## D3 · Stable ids — proposed

OpenPlural ids are file-local, so re-importing next month's export duplicates everything. PSDS
ids are UUIDs that never change for a record, with UUIDv5 derivation for sources that lack UUIDs
(§2.2), which also makes converting the same foreign export twice idempotent. Cost: producers
must persist ids, which every app with a database already does.

## D4 · Fronting truth: spans, with switches as annotations — proposed

The hardest call. Two shapes exist in the wild: switch events (PluralKit, and Chorus internally)
and per-subject intervals (almost everyone else). OpenPlural carries both with no rule for
conflicts.

Draft: `FrontSpan` is the truth; `FrontSwitch` is optional history of how it was recorded; a
normative fold (§5.4) turns switch-only files into spans.

Why spans: they are what most apps store; they are directly analysable (duration = end − start,
no replay); co-fronting with independent start and end times needs no ordering tricks.
What is lost: a switch that changed nothing (a note-only switch) and pure reorderings exist only
in the switch log, so the log is kept as an optional record type rather than dropped.

Alternative: switches as truth (Chorus's own model). Rejected for the interchange format because
every interval-based app would have to synthesise events, and "who fronted when" would need the
fold to read at all. Chorus keeps its op log internally and exports both (Appendix A.5).

## D5 · Level, primary and position are separate — proposed

OpenPlural's `front_role` mixes a tier (`co_conscious`) with rank (`primary`). PSDS splits:
`level` (FRONTING, CO_CONSCIOUS, INFLUENCING, PRESENT), `primary` (bool), `position` (order).
INFLUENCING is Ampersand's; PRESENT is Chorus's "present-transient". Open sub-question: are four
levels enough? Candidates seen elsewhere (`background`, `muted`, `asleep`) look like *states*
rather than levels and map to `State` subjects in the draft.

## D6 · Custom fronts are their own record — proposed

`State`, not a flagged member (§4.3), so they don't inflate member counts, can't author, and
don't need member fields. Chorus already works this way.

## D7 · Text: three forms, offsets in code points — proposed

Most apps store markdown; Chorus stores plain text + entity spans. Forcing either loses
something (spans can't express headings or lists; markdown can't carry a mention's id without a
convention). So `RichText` is plain, PSDS markdown (with `psds:` links for mentions and emoji) or
entities, and every reader can at least show the characters.

Offsets: the draft counts **Unicode code points**, the language-neutral choice. Chorus, JavaScript,
Kotlin and Telegram count **UTF-16 code units**, so Chorus converts at export and import. The
other way round would make Rust, Python and Go convert. Open for the owner: code points (draft)
or UTF-16?

## D8 · Audience — proposed

Six ordered tiers (MEMBERS, PRIVATE, BUCKETS, FRIENDS, INSTANCE, PUBLIC), bucket lists, in-system
member lists, and per-field overrides including sub-fields (`birthday.year`). The ordering gives
a precise "round to stricter" rule (§4.7), and PluralKit's per-field privacy, Simply Plural's
buckets and Chorus's modes all map without loss. Follower *ceilings* (Chorus's delay, fuzz,
digest) are service behaviour and stay out.

## D9 · Files: a ZIP container with a record stream — proposed

`manifest.json` + `data.jsonl` or `data.binpb` + content-addressed `blobs/`, or a single JSON
document for small files. The stream form lets a consumer import millions of messages without
holding one giant JSON array in memory; the document form stays OpenPlural-like and easy to
write by hand. Both carry the same records. Encryption is the whole file with age (§3.5), not a
PSDS-specific scheme.

## D10 · Closed enums vs open vocabularies — proposed, worth a second look

Enums where the set is closed and meaning matters for behaviour (levels, audience tiers, field
types, switch kinds, post and channel kinds); strings with a registry where apps will keep
inventing values (label kinds, warning codes, app ids). Risk: a new level or post kind needs a
spec release, and old strict parsers drop it. Mitigation: unknown enum values are non-fatal and
preserved in binary.

## D11 · Snapshots now, change logs later — proposed

0.1 stores **state**: every record as it is now, plus tombstones. It does not standardise a
sync protocol or an operation log. Chorus's op log could become a future `ops` module (a
PSDS-shaped change feed), but only once a second app wants it.

## D12 · Merge rule: newest record wins — proposed

Whole-record last-writer-wins by `updated_at` (§9.5), deliberately simple so every app can do it;
apps with field-level merging may do better. Open: should a consumer that finds a *different*
record with the same `source_refs` but a different id treat them as the same? The draft says yes
(match by id, then by source ref).

## D13 · Chat scope — proposed

A system's own channels and messages, including the messages it can read in shared channels
(marked `shared`, with external authors as display names and optional contact links). Spaces,
roles and channel permissions between accounts are server state and out of scope.

## D14 · Licence and governance — open

Suggested: schema and examples under MIT (like OpenPlural and Chorus), prose under CC BY 4.0;
changes by pull request with a public changelog; app ids registered by PR. To decide together
with D1.

## D15 · Where the spec lives — proposed: here for now, its own repository at 0.2

It starts in `Chorus/spec/` so the Chorus implementation can move with it. Once named (D0) and
discussed with OpenPlural (D1), it should move to its own repository so other apps don't have to
depend on Chorus's.

---

## Readiness path

| Stage | Done when | Status |
| --- | --- | --- |
| 0.1 draft | SPEC.md, `.proto` compiling and lint-clean (`buf build`, `buf lint` STANDARD), one example round-tripping JSON → binary → JSON | **done 2026-09-26** |
| Decisions | D0–D15 settled by the owner | next |
| 0.2 schema freeze candidate | fixes from review; generated JSON Schema; a fixture per mapped app (hand-written from the researched shapes) | |
| Validator | CLI: checks V1–V10, prints an ImportReport-shaped result; runs in CI on the fixtures | |
| Reference converters | PluralKit v2, Simply Plural, OpenPlural v0.1, PluralSpace → PSDS; PSDS → OpenPlural | |
| Chorus implementation | export (FULL and SHARED profiles) and import (PSDS + OpenPlural) in Chorus, round-trip test against its own exports | |
| Upstream | OpenPlural discussion opened with the PRIOR_ART table; second app implements | |
| 1.0 | two independent implementations interoperate on the fixture suite | |

## Chorus implementation sketch (for when the decisions are in)

- A `chorus-psds` crate generating Rust types from `spec/proto` with `prost` + `protox` (no
  `protoc` on the build machines), kept out of `chorus-core` so the core stays small.
- Export: projections → PSDS records (Appendix A.5), `front_interval` → spans, `switch` →
  switches; ids reused as is (Chorus ids are already UUIDv7); a new background export kind next
  to the D-068 bundle.
- Import: PSDS and OpenPlural → Chorus ops (member.create, front.switch, …) through the normal
  ingestion path, so imports sync and are undoable like anything else; a switch log is preferred
  for Chorus's timeline when present, else spans become switches (§5.5, keeping levels).
- Fixtures under `fixtures/psds/` shared with the validator.
