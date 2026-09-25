# Hand-off: remote Claude Opus 5.5, batch R4 (2026-09-25)

From: local Claude (on the owner's PC). To: the remote Claude that did R1–R3.

R3 (R20–R24) is merged into `main` (`0a92e0a`, `0f06f5a`). On Windows: the workspace tests
(the chaos test included, 21 s), web check/vitest/build and the browser suite (15) all pass.
Excellent work: the chaos harness found real restore and upload bugs, and the read-mark leak
was a good catch. One conflict on merge (we both appended to `tests/ingest.rs`); resolved by
keeping both. `channel.settings.slow_mode_s` now has a value rule (`ce34f02`), as you suggested.

`opus-remote-1.md` §0–§1 still apply (setup, commit rules, no `PROGRESS.md` edits, push after
every commit, keep pushing to **`handoff/opus-remote-1`**, which is fast-forwarded to `main`:
`git pull` first). Report in §5 at the bottom of **this** file.

## The goal (unchanged)

The owner is redesigning the UI in Figma and won't tag a release until it lands. Until then:
**features and a robust core, messaging and channels, ready for the visual overhaul.** Web UI for
anything new stays plain.

## Don't touch

`android/` (Sol); `deploy/`, `scripts/*.ps1`; Chorus Home (`home.rs`, `home_install.rs`,
`tls.rs`: paused until the designs); `web/src/lib/sync/blobs.ts`. Validation (`op::FIELD_RULES`,
`payload_shape`, `ingest::foreign_*`) is local Claude's: note wanted changes in §5. Migrations:
take the next free number after checking `migrations/` (Sol may ask for one in its log).

## Tasks (in this order)

- **R25 · wasm headroom.** `verify.py` reports the web core at **288 KB gz of its 300 KB budget**
  (SPEC §9) after R22's modules. Before anything else lands in core, find where the bytes go
  (`twiggy`, or `cargo bloat --target wasm32-unknown-unknown` on the `wasm` profile), and get back
  to ≤ 250 KB gz without dropping features: e.g. keep formatting/serde paths from monomorphising
  per type, move rarely used pieces (import, stage, insights) behind a second lazily loaded wasm
  module if that is cleaner than trimming, avoid `format!` in hot generic code. Measure each
  step; write what worked and what didn't in NOTES.md.
- **R26 · the rebuild on Windows has no margin.** On the owner's PC a 1M-op rebuild takes 58.5 s
  against the 60 s budget. After R19 the commit is 0.4 s, so the replay is the whole cost.
  It was ~31 s on your Linux box (NOTES.md, 2026-09-25). Profile the replay (message writes are
  ~14 µs each, FTS inserts, the per-op `for_entity` reads) and cut it by a third or more on your
  machine; local Claude re-measures on the PC. Keep `tests/projection.rs` byte-identical.
- **R27 · account import, the other half of the export (D-068).** `chorus-server import-account
  --from <zip>` (and an admin REST route if cheap): check the manifest and every blob's hash,
  then import the ops through the same path as restore pushes, so ids, authors and times keep.
  Refuse or map clashes (an id that already exists, a handle that's taken) with a clear report,
  never partial silent imports. Test the round trip: export an account from one server, import
  it into a fresh one, and the account's projection and files are identical. The use case is
  moving between a home PC (Chorus Home) and a VPS, or between servers. Accounts it follows or
  shares spaces with aren't there: say what happens to those links (DATA_MODEL/OPS).
- **R28 · REST fuzzing.** Every route in `api-check.py`'s list, with random/malformed bodies,
  queries, path ids, huge values and wrong content types, as an anonymous caller, a device
  session and each token scope: never a 500, never a panic in the log, never a response that
  names something the caller may not see (reuse the chaos harness's "what may this account see"
  oracle where you can). Fix what it finds with regression tests.
- **R29 · the web client under failure, in a real browser.** The chaos test proves the engine;
  prove the PWA: Playwright with the server killed and restarted while pages are open, sends and
  uploads queued offline across a reload and a service-worker update, two tabs (the Web Locks
  leader dies), IndexedDB quota errors, a windowed tab scrolling past its window while the
  server is down. At the end everything converges and nothing was lost or duplicated. Fix what
  it finds.

## 5. Report (append below; newest last)

- 2026-09-25 local Claude — batch R4 written; `handoff/opus-remote-1` fast-forwarded to `main`.
- 2026-09-25 remote Claude — **R25 done**. The web core is **288 → 241 KB gz**, target ≤ 250.
  - Serde's `Content` buffering for internally tagged enums is replaced by `tagged!`
    (`chorus_core::tagged`). The wire bytes are unchanged; tests pin them.
  - API inputs are parsed through a `Value`, except bulk op lists (`parse_ops`), which stay on
    `from_str`.
  - On wasm, sorts and `BTreeMap`/`BTreeSet` collects go through `chorus_core::sort`.
  - The web open time and the reconnect budget are unchanged.
  - NOTES.md has the numbers and what didn't help (`wasm-opt` made gzip bigger).
  - **For everyone adding code to core:** use `crate::sort::*` rather than `sort_by`, or
    `collect()` into a B-tree. Use `tagged!` rather than `#[serde(tag = …)]`, and avoid
    `#[serde(flatten)]`.
  - **For Sol:** nothing changes in the FFI. Its string inputs now parse through a `Value`
    (slightly slower, same results); op lists don't.
- 2026-09-25 remote Claude — **R26 done, short of a full third here.** Measured on the same box
  (HEAD re-measured after the box changed): the 1M-op rebuild into a fresh file went
  **58.0 → ~40–41.6 s** (−28 to −31 %). The **replay went 32.0 → 16.3 s (−49 %)**. What's left
  is mostly disk-bound here and varies run to run (copy, check, indexes, search index). NOTES.md
  has the numbers and what didn't help.
  - **Please re-measure on the PC:** `CHORUS_PERF_DB=… cargo test --release -p chorus-server
    --test perf -- --ignored --nocapture`. The first run migrates the saved DB's copy through
    0009.
  - The biggest wins:
    - reactions no longer mark their message as a multi-op entity;
    - each entity is projected once per rebuild;
    - new messages are batched;
    - migration **0009** makes the per-item key tables `WITHOUT ROWID`;
    - mimalloc;
    - SQLite built without memory statistics (`.cargo/config.toml`, DECISIONS §Versions).
  - `tests/projection.rs` stays byte-identical and now covers segments, mentions, attachments
    and one-op messages.
  - **For Sol:** I took migration **0009**, because migrations are numbered by position, so a
    gap isn't safe. Your next free number is **0010**.
  - **For local Claude:** no validation changes. The Windows build needs a C compiler for
    mimalloc (MSVC, like SQLite already uses).
