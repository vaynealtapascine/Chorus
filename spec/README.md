# PSDS — an open format for plural-system data

**Status: draft 0.1.0 (2026-09-26), working title.** Not yet reviewed by the owner or anyone
outside Chorus.

PSDS describes how to store and move a plural system's data: its members, custom fronts,
groups and subsystems, custom fields, front history, journals and notes, chat, and files. It is
meant to be implemented by Chorus first and offered to the wider community as a compatible,
more formal successor to OpenPlural v0.1 (see DESIGN_NOTES D1).

| File | What |
| --- | --- |
| [SPEC.md](SPEC.md) | The specification: conventions, records, fronting, files, import/export rules, privacy, conformance, mappings |
| [proto/psds/v1/](proto/psds/v1/) | The schema (proto3). Source of truth for names, types and field numbers |
| [PRIOR_ART.md](PRIOR_ART.md) | PluralKit, Simply Plural, PluralSpace, OpenPlural, the owner's 2024 sketch, Chorus: what each does and what PSDS keeps or fixes |
| [DESIGN_NOTES.md](DESIGN_NOTES.md) | Every open decision with options and a recommendation; the readiness path |
| [examples/small-system.json](examples/small-system.json) | A small system in the single-document JSON form |

## Checking the schema

With [buf](https://buf.build) installed (`npm i -g @bufbuild/buf` works without Go):

```sh
cd spec/proto
buf build && buf lint
# JSON -> binary -> JSON round trip of the example
buf convert . --type psds.v1.Archive --from ../examples/small-system.json --to /tmp/small.binpb
buf convert . --type psds.v1.Archive --from /tmp/small.binpb --to /tmp/small.json
```

## Licence

Same as the repository (MIT) for now; see DESIGN_NOTES D14.
