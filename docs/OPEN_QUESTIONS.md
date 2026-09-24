# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| Q14 | **Export bundle with files** (D-065 left it for later; proposal below, from the remote batch R7). Zip or tar? Only files this account uploaded, or also ones it can see in shared spaces? Is a zip dependency acceptable? | Not built until answered. If built before an answer: zip, *stored* (uncompressed) and written by hand (no new dependency), own files only, one job per account, kept 24 h. |
| Q15 | A shared feed whose filter uses `fronting:` would tell followers when members fronted, which follow ceilings (NOTIFICATIONS §3) otherwise govern. Share it anyway (evaluated with the follower's ceiling), or keep such feeds private? | Kept private: the web app won't share them and `GET /feeds/{id}/items` answers 400 to anyone but the owner (remote batch R10). |

### Q14 proposal — the DATA_MODEL §7 "full backup" zip, as a background job

**What's in it.** `chorus-<handle>-<YYYYMMDD>.zip`:

```
manifest.json        format 1; account {id, handle, kind}; created_at; server version; instance_id;
                     ops {count, sha256}; blobs [{hash, size, mime, filenames: […]}]; csv [names]
ops.jsonl            exactly GET /exports/ops.jsonl (the account's authored ops, seq order)
csv/<name>.csv       the seven tidy tables of DATA_MODEL §7.1
blobs/<sha256>       every file the account's own ops point at
README.txt           three lines: what this is, that blobs/ is named by hash (manifest has names)
```

The SQLite copy stays its own download (it's derived: a rebuild of `ops.jsonl`). Blobs are named
by hash, not filename: two attachments may share a name, and the manifest keeps every filename a
blob was sent under, so a reader can restore names.

**Where the files come from.** The blob store (`data/blobs/`, content-addressed, D-064's pool
for backups), chosen from the **account's own ops**, the same set as `ops.jsonl`: `attachment.*`
ops' `blob_hash`/`thumb_blob_hash`, member/group/system `avatar_blob`/`banner_blob` in its
`.create`/`.set` ops, and custom emoji it created. Not other accounts' files, even ones it can
see in a shared space: those are theirs to export (same line as D-066 and the direct exports).
Each blob is checked against its hash while copied; a missing or damaged one is listed in the
manifest (`missing: […]`) instead of failing the whole export.

**Format and dependency.** A ZIP with *stored* entries (no compression) can be written in ~150
lines with no dependency: local headers, a CRC-32 per entry (a table-driven function), the
central directory, and ZIP64 records past 4 GB or 65 535 entries. Media files don't compress
anyway; `ops.jsonl` and the CSVs would (roughly 5–10× smaller), which is the only reason to take
the `zip` crate (2.x, pure Rust with `flate2`/`miniz_oxide` for deflate). Tar is simpler still
but Windows users open zips natively, so the proposal is a hand-written stored zip, tested by
reading it back with Python's `zipfile` and `unzip -t` in a test. The owner decides whether
compression is worth a dependency.

**Size.** No cap on what an account may export, but the server protects its disk: the job
streams straight into `data/exports/<job id>.zip.part` (never in memory), first estimating the
size (op bytes + blob sizes) and refusing with `too_large` if free space would fall below twice
that. One running job per account, two at a time server-wide (others queue). A finished file
is kept 24 h (or until an hour after its first full download), then deleted; an export is not a
backup (OPS §5 is).

**The job protocol** (API.md §4 already names it):

```
POST   /exports {kind: "full"}          → 202 {id}      (sessions, or tokens with `export`)
GET    /jobs/{id}                       → {id, kind, status: queued|running|done|failed,
                                           phase: "ops"|"csv"|"blobs"|"zip", done, total,
                                           bytes, error, result_url, expires_at}
GET    /exports/{id}/download           the zip; Range supported so a phone can resume
DELETE /jobs/{id}                       cancel, or delete the finished file now
```

Jobs live in a table (`export_job`, a migration numbered after Sol's `0006`) so a restart marks
running ones `failed` ("server restarted; start it again") instead of losing them. Progress is
counted in items (ops, then CSV tables, then blobs) and bytes; the web app polls `GET /jobs/{id}`
every 2 s while its Data page is open, and a finished export also becomes an in-app notification
(`kind: "export_ready"`), not a push: it's the account's own action, seconds to minutes long.

**Round trip.** The manifest and `ops.jsonl` carry everything an importer needs
(`chorus-server import-account --from <zip>`, later): ops keep their ids, authors and times,
blobs are verified by hash. The importer is separate work and not part of this proposal.
