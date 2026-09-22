# Notes

Non-obvious findings from implementation: library traps, platform limits, reasons a design had
to change. Newest last. Format: `YYYY-MM-DD agent — area — finding`.

- 2026-09-23 claude-opus-5.5 — sync — The convergence simulator found three protocol bugs before any
  server code existed: (1) acks must carry the full stamp (account, device, received_at) or local
  projections differ from the server's; (2) after a restore, old-epoch seqs are reused, so
  reconcile can't pick ops by seq — re-push everything; (3) fire-and-forget reconcile pushes are
  lost on disconnect — demote confirmed ops into the outbox instead. Digest-mismatch repairs occur
  only around restores (race between other devices' restore pushes and Caught); they self-heal.
- 2026-09-23 claude-opus-5.5 — text — The markup round-trip property test drove the serializer
  design: `\&` empty separator (Telegram uses `
` for the same problem), whitespace at entity
  edges, url/mention atoms that would re-grow, code blocks only at top level, escape-aware scans.
- 2026-09-23 claude-opus-5.5 — tooling — Git Bash here mangles backslashes in heredocs passed to
  python; for Rust source edits use the Edit tool, not sed/python.
- 2026-09-23 claude-opus-5.5 — tooling — PowerShell: `$x = if (...) { @() } else { @('--release') }` unwraps
  the one-element array into a string, and `@x` then splats it as single characters (cargo saw
  `-`). Use `$x = @(if (...) { '--release' })`. Also avoid naming a variable `$profile`.
- 2026-09-23 claude-opus-5.5 — web — The browser pane's colour-scheme emulation doesn't fire
  `matchMedia` change events; reload after switching it when testing theme-dependent colours.
- 2026-09-23 claude-opus-5.5 — server — Projections re-project the touched entity through
  `chorus_core::model` (SQL = model by construction; `tests/projection.rs` checks 40 scrambled
  seeds and that `rebuild` is byte-identical). Known shortcuts to revisit when they cost:
  (1) the front is refolded in full per front op (fine for thousands of switches; make it
  incremental from the op's time when accounts reach tens of thousands);
  (2) `front_daily` uses the account's latest UTC offset, not the system's IANA timezone (add
  `jiff` with tzdb on the server when DST-exact local days matter);
  (3) digests are computed per Hello by scanning a scope's op ids (cache per scope if slow).
- 2026-09-23 claude-opus-5.5 — server — FTS5 tables are plain (own copy of the text, rowid =
  message rowid), not external-content: a projection rewrite can't supply the old text that
  external-content deletes need.
- 2026-09-23 claude-opus-5.5 — android — UniFFI: an error variant field named `message` clashes with
  Kotlin's `Throwable.message` ("overload resolution ambiguity"); name it `reason`. Changing any
  exported type changes UniFFI checksums, so rebuild the .so and the bindings together.
