# Instructions for agents working on Chorus

Chorus is built by two AI agents taking turns — **Claude Opus 5.5** and **GPT-6-sol** — with a human
owner (Vayne) who answers product questions. Either agent may stop at any point because it ran out
of usage. Everything the next agent needs must therefore be **in the repo**, not in a chat.

## Read order (every session, before touching code)

1. `PROGRESS.md` — what is done, what is in progress, the next concrete step.
2. `docs/DECISIONS.md` — settled decisions. **Do not re-litigate or re-ask these.**
3. The spec doc(s) for the task you are on (the board names them).
4. `docs/OPEN_QUESTIONS.md` — if your task touches one, use the stated default and move on.

## The spec set

| Doc | What it owns |
| --- | --- |
| `docs/SPEC.md` | Product: concepts, features, what is v1 and what is later |
| `docs/DATA_MODEL.md` | SQLite schema, op catalogue, analysis views, naming conventions |
| `docs/SYNC.md` | Offline-first sync protocol, clocks, merge rules, convergence tests |
| `docs/API.md` | HTTP/WebSocket/SSE surface, webhooks, exports |
| `docs/NOTIFICATIONS.md` | Switch + chat notifications, privacy ceilings, delay/fuzz/digest |
| `docs/DESIGN.md` | Visual system, screen inventory, Basic vs Advanced settings |
| `docs/CLIENTS.md` | Android app, home-screen widget, web PWA specifics |
| `docs/OPS.md` | Running on the owner's PC over Tailscale, backups |

If two docs disagree: DECISIONS > DATA_MODEL/SYNC (for data) > SPEC > the rest. Fix the loser in the
same commit and note it in the log.

## Handoff protocol

- **Starting a task:** `python scripts/board.py start <id> <you> "<next step>"` (or edit *Now* in
  `PROGRESS.md` by hand: task id, your name, date, next concrete step; mark it `[~]`). Commit.
- **Finishing an atomic piece:** `python scripts/board.py done <id> <you> "<log line>"` (ticks
  `[x]`, adds a log line); commit together with the work.
- **Stopping mid-task:** update *Now* → *Next concrete step* + *Notes* with: files touched, what is
  half-done, what the failing test says, what you were about to try. Commit even if the work is
  WIP (prefix the message `wip:`); never leave uncommitted changes.
- **Discovering something non-obvious** (a library trap, a platform limit, a reason a design had to
  change): write it into the relevant doc or `docs/NOTES.md`, not just the commit message.
- **Changing the spec:** add a `D-xxx` entry to `docs/DECISIONS.md` (what, why, who) and update the
  doc. Spec changes that alter user-visible behaviour need the owner's approval — add them to
  `docs/OPEN_QUESTIONS.md` and use the existing spec until answered.

## Engineering rules

- **Atomic commits**, conventional-ish prefixes (`feat(core):`, `fix(server):`, `docs:`, `test:`,
  `chore:`). One logical change per commit. Tests land with the code they test.
- **`python scripts/verify.py` must pass before every commit** (`--quick` while iterating; the full run uses release builds for the simulator).
- **`chorus-core` is pure**: no IO, no clocks, no randomness except through passed-in parameters.
  Everything that must behave identically on server, Android and web lives there.
- **Conformance fixtures** in `fixtures/` are the cross-language contract (see SYNC.md §9). If a
  Kotlin or TypeScript fallback ever reimplements core logic, it must pass the same fixtures.
- **Never lose user data.** No destructive migration without a backup step; the op log is
  append-only; "delete" is a tombstone op; hard deletes happen only through explicit purge ops.
- **Pin versions.** Record pinned toolchain/library versions in `docs/DECISIONS.md` §Versions when
  you add them. Do not bump majors casually.
- **Performance budgets** in SPEC.md §9 are requirements, not aspirations. Add a bench or test when
  you implement something on a budget path.
- **Privacy invariants** in NOTIFICATIONS.md §5 are requirements. A follower must never be able to
  learn more (or earlier) through the API than through their notifications.
- Keep the UI cozy and quiet: fewer controls in Basic, everything else behind Advanced
  (DESIGN.md §6). When unsure whether an option is Basic, it is Advanced.

## Environment notes (owner's machine)

- Windows 11, PowerShell + Git Bash. The server runs on this PC as a service; clients reach it over
  Tailscale through Caddy (see OPS.md).
- Android device is a Samsung phone. `JAVA_HOME` may point at an old JRE — use Android Studio's
  bundled JBR for Gradle.
- Agents must not install Windows services or change system settings; write the script and tell
  the owner to run it.
- The public repo follows the owner's house standard (README skeleton, MIT, disclosure card) —
  see README.md.
