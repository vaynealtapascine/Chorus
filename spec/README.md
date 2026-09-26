# PluralSpec — an open data format for plural systems

**Status: draft 0.1.0 (2026-09-26), under review by the owner.** Nothing has been proposed to
PluralPort yet (DESIGN_NOTES D1).

PluralSpec describes how to store and move a plural system's data: its members, custom fronts,
groups and subsystems, custom fields, visibility classes, front history, journals and notes,
chat, files, and the history of how all of it changed. It is a superset of PluralPort v0.1
(formerly OpenPlural): every PluralPort file converts to PluralSpec without loss. Chorus is its
first implementation. It lives here until it is pinned down, then moves to its own repository
(D15).

| File | What |
| --- | --- |
| [SPEC.md](SPEC.md) | The specification: conventions, records, fronting, history, files, import/export rules, privacy, conformance, mappings |
| [proto/pluralspec/v1/](proto/pluralspec/v1/) | The schema (proto3), source of truth for names, types and field numbers: `common`, `system`, `fields`, `front`, `content`, `chat`, `asset`, `record` (the record union and history events), `archive` (manifest, file, import report) |
| [DESIGN_NOTES.md](DESIGN_NOTES.md) | Every decision, settled or waiting for review, and the readiness path |
| [PRIOR_ART.md](PRIOR_ART.md) | PluralKit, Simply Plural, PluralSpace, PluralPort, the owner's 2024 sketch, Chorus: what each does and what PluralSpec keeps or fixes |
| [examples/small-system.json](examples/small-system.json) | A small system in the single-document JSON form, with history events and a subsystem front |

## Checking the schema

With [buf](https://buf.build) installed (`npm i -g @bufbuild/buf` works without Go):

```sh
cd spec/proto
buf build && buf lint
# JSON -> binary -> JSON round trip of the example
buf convert . --type pluralspec.v1.Archive --from ../examples/small-system.json --to /tmp/small.binpb
buf convert . --type pluralspec.v1.Archive --from /tmp/small.binpb --to /tmp/small.json
```

## Licence

Everything in `spec/` is [CC BY-NC-SA 4.0](LICENSE); the rest of the Chorus repository is MIT.
See DESIGN_NOTES D14 for what NonCommercial means for the schema.
