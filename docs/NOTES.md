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
  design: `\&` empty separator (Telegram uses `` for the same problem), whitespace at entity
  edges, url/mention atoms that would re-grow, code blocks only at top level, escape-aware scans.
- 2026-09-23 claude-opus-5.5 — tooling — Git Bash here mangles backslashes in heredocs passed to
  python; for Rust source edits use the Edit tool, not sed/python.
