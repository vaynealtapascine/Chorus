# Browser end-to-end suite

`v1.spec.ts` is the v1 run made repeatable: two accounts in separate browsers (a system and a
person), a second device of the system, and the flows v1 promises:

1. onboarding by invite, adding members, a switch;
2. the switch reaching the system's second device;
3. a follower (Close preset) seeing a switch only after the 15 s settle, never before;
4. a DM, and a shared space, between the two accounts;
5. a journal post (followers) that the follower reacts to and replies to, both seen by the author;
6. message search and post search (a followers-only post found by the follower);
7. a feed shared with followers;
8. no console errors or uncaught exceptions in any page (CSP violations are console errors), and
   the web app served with its Content-Security-Policy.

Handles carry a per-run suffix, so the suite can run again against the same server.

**Locally / in CI:** `bash scripts/e2e-web.sh` builds the server and the web app, starts a server
on a temporary data directory (port `CHORUS_E2E_PORT`, default 5399) and runs Playwright
(`--no-build` skips the builds; arguments after `--` go to Playwright, e.g. `-- --headed`).
`CHORUS_E2E_KEEP=1` keeps the data directory. Playwright is pinned (`@playwright/test` 1.56.1);
install its Chromium once with `npx playwright install chromium`, or point
`PLAYWRIGHT_BROWSERS_PATH` at one.

**Against a running server** (the v1 check on the owner's server): from `web/`,

```bash
CHORUS_E2E_BASE=https://chorus.example.org \
CHORUS_E2E_CLI="/path/to/chorus-server --config /path/to/chorus.toml" \
npx playwright test
```

The suite makes its accounts from invites it mints with that CLI (it must reach the server's
database, so run it on the server's machine). Afterwards remove them with
`chorus-server purge --account <id>` (`GET /api/v1/me` in each browser shows the id, or look for
handles ending in the run's suffix). On a failure, `e2e/results/` has a trace per test
(`npx playwright show-trace …`).
