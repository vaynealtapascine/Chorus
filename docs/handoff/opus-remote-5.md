# Hand-off: remote Claude Opus 5.5, batch R5 (2026-09-26)

From: local Claude (on the owner's PC). To: the remote Claude that did R1–R4.

R4 (R25–R29) is merged into `main` (`9610f3a`): thank you, the wasm and rebuild numbers held up on
the PC. `opus-remote-1.md` §0–§1 still apply (setup, commit rules, no `PROGRESS.md` edits, push
after every commit, keep pushing to **`handoff/opus-remote-1`**, which is fast-forwarded to
`main`: `git pull` first). Report in §5 at the bottom of **this** file.

## The goal

**PluralSpec**, an open data format for plural systems, drafted in `spec/` (read `spec/README.md`,
`spec/SPEC.md`, `spec/DESIGN_NOTES.md`, `spec/PRIOR_ART.md`, and the proto under
`spec/proto/pluralspec/v1/`). The owner settled most decisions on 2026-09-26 (DESIGN_NOTES
D0–D15) and wants the readiness path worked **through step 4 — JSON Schema and fixtures,
validator, converters, Chorus implementation — and then to stop**. Nothing goes to PluralPort
(the upstream community spec) until the owner has reviewed the spec. D-077 in
`docs/DECISIONS.md` records that Chorus implements it.

## Rules for this batch

- **The spec text is the owner's to change.** Implement it as written. Where it is wrong,
  ambiguous or unimplementable, don't rewrite SPEC.md: write the problem and your proposed fix in
  §5, implement the most conservative reading, and mark the code `// SPEC-QUESTION:`. Typos and
  broken cross-references you may fix directly.
- **Schema changes** (`spec/proto`) only when an implementation can't work without one; list each
  in §5. Keep `buf build` and `buf lint` (STANDARD) clean: `npm i -g @bufbuild/buf` (1.73 was used
  locally; there is no `protoc` on the owner's PC, and the build must not need one).
- Licences (D14): `spec/proto`, `spec/schema` (your generated JSON Schema), `spec/examples` and
  `fixtures/pluralspec/` are **CC BY 4.0** (put `SPDX-License-Identifier: CC-BY-4.0` where a file
  can carry a comment); the spec's Markdown is CC BY-NC-SA 4.0; code under `crates/` stays MIT.
- Updated 2026-09-26 after this file was first written: visibility classes carry permission
  `grants` (SPEC §4.8) — cover them in fixtures, the validator and the Chorus mapping (follower
  ceilings → `front.notify` params etc., Appendix A.5). Composite members, blends and fronting
  on behalf of a subsystem are now in the schema as a *proposal* (SPEC §5.3–5.4, DESIGN_NOTES D5):
  the validator checks their invariants (5.4 items 6–9, composition cycles, `blend_degree`
  in 0–1) and fixtures cover
  them; Chorus has none of them, so its export never writes them and its import flattens them
  with `composition_flattened` / `blend_flattened` warnings. Don't add them to Chorus's own data
  model.
- Pin every new dependency in `docs/DECISIONS.md` §Versions.

## Don't touch

`android/` (Sol); `deploy/`, `scripts/*.ps1`; Chorus Home (`home.rs`, `home_install.rs`,
`tls.rs`); `web/src/lib/sync/blobs.ts`. Validation (`op::FIELD_RULES`, `payload_shape`,
`ingest::foreign_*`) is local Claude's: note wanted changes in §5. Migrations: take the next free
number after checking `migrations/`.

## Tasks (in this order)

- **R30 · the `pluralspec` crate and generated types.** A new workspace crate
  `crates/pluralspec` (MIT), not a dependency of `chorus-core`. Rust types generated from
  `spec/proto` with `prost` + `protox` (pure Rust; no `protoc`), proto3 JSON with `pbjson` /
  `pbjson-build` (or equivalent) configured to **print proto field names (snake_case)** and to
  accept camelCase, integer enums and unknown enum names as SPEC §3.1 requires. Readers and
  writers for both file forms and both body encodings (SPEC §3.3: ZIP container with
  `manifest.json` + `data.jsonl` or delimited `data.binpb` + `blobs/sha256/`, and the single
  document), refusing zip-slip paths. Generate a **JSON Schema** (snake_case) into
  `spec/schema/` and make `verify.py` fail if it is stale (like the tokens check). Test: the
  example `spec/examples/small-system.json` reads, writes as each form, and reads back equal.
- **R31 · fixtures (readiness step 1).** Under `fixtures/pluralspec/`:
  - one directory per source app — `pluralkit` (datafile v2), `simply_plural` (collection
    export), `pluralspace` (GDPR zip layout), `pluralport` (v0.1 JSON) — each with a small but
    nasty `input` in that app's real shape and the `expected.pluralspec.json` it converts to.
    Build the inputs from the source-backed research linked in `spec/PRIOR_ART.md` (the
    PluralSpace/openplural `docs/apps/*.md` pages have field-level detail); cover custom fronts,
    co-fronting rows, switch-outs, hidden birth years, buckets and custom visibility, nested groups,
    name-only authors (PluralSpace), untyped field values, URI-only avatars;
  - `valid/` and `invalid/` PluralSpec files, one invalid file per validation rule V1–V12
    (SPEC §10.2), each named for the rule it breaks.
- **R32 · validator (step 2).** `pluralspec validate <file>` (a bin in the crate): checks V1–V12,
  including the per-front span invariants (§5.4), the fold (§5.5) for switch-only fronts, event
  replay for `history.complete` (§9.6), text positions on character boundaries in the declared
  unit (§2.6), and classes/groups without cycles. Prints an `ImportReport`-shaped JSON (§10.4).
  Runs over every fixture in `cargo test`; each `invalid/` file fails with exactly its rule.
- **R33 · converters (step 3).** In the crate: PluralKit v2, Simply Plural, PluralSpace and
  PluralPort v0.1 → PluralSpec, and PluralSpec → PluralPort, following SPEC Appendix A and §5.7,
  with deterministic UUIDv5 ids (§2.2), placeholders for name-only references (§2.7), and every
  loss reported as a warning (§10.3). Golden tests against R31's fixtures; converting the same
  input twice gives byte-identical output.
- **R34 · Chorus (step 4).**
  - **Export**: `chorus-server export --format pluralspec [--profile full|shared --for-class
    <id>...] [--encoding jsonl|binpb]`, and the same as a background export kind beside the D-068
    bundle (REST + the web "Your data" page, plain UI). Mapping per SPEC Appendix A.5:
    projections → records, `front_interval` → spans in the system front, `switch` → switches, the
    op log → events (standard where they map, `chorus:*` custom events otherwise, service ops
    left out, HLC in `ext.chorus.hlc`), buckets/follows → visibility classes,
    `text_offset_unit` UTF16. SHARED follows SPEC §11.2 (no leaks through `before`).
  - **Import**: `chorus-server import --format pluralspec|pluralkit|simply_plural|pluralspace|
    pluralport --account <id> <file>` and an admin/owner REST route: convert with R33, then write
    Chorus ops **through the normal ingestion path** as the importing account's device, so the
    import syncs, shows in history and can be deleted/restored like anything else. Merge per SPEC
    §10.5 (by id, then `source_refs`); never widen visibility (§11.3); send **no follower
    notifications** for imported fronts. Custom events Chorus doesn't know are kept (a table, or
    `ext`) and re-exported. Report with an `ImportReport`.
  - **Round trip** test: an account with members, groups, a subsystem, fields, states, co-fronting
    with levels, posts, chat with segments and edits, buckets → export → import into a fresh
    account → export again: records and events equal apart from ids the import had to mint.
  - Leave the existing PluralKit import (M11.1) in place; note in §5 whether it should later route
    through the converter.
- **R35 · flaky `rest_fuzz` on Windows.** Your R28 test fails about half the time on the owner's
  PC with a transport error, not a status: `POST /api/v1/devices/invite?… the connection failed
  (… An established connection was aborted … (os error 10053))`, or `POST /api/v1/auth/session …
  forcibly closed by the remote host (os error 10054)`, a different request each time.
  Unproven hypothesis: the server answers some requests (401/403/413/429) without reading the
  body, hyper closes the connection, and reqwest's pool reuses it. Find out whether it's the
  harness or a server behaviour real clients would hit, fix the right side, keep the test's
  intent. (Locally, `verify.py` run through a pipe also needs `PYTHONIOENCODING=utf-8` or it
  crashes printing `▶`; a one-line `sys.stdout.reconfigure(encoding="utf-8")` in verify.py would
  fix that too.)

## 5. Report (append below; newest last)
