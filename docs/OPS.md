# Chorus — operations

Two supported ways to run the server (D-062):

- **Windows PC** (§1–§6): an NSSM service behind Caddy, reachable only over Tailscale, same
  pattern as Arbor. This is how it started.
- **Linux server on the public internet** (§9): a systemd service behind Caddy, built and packed
  on the PC by `scripts/pack-linux.ps1`, installed by `deploy/linux/install.sh`.

---

## 1. Layout on the PC

```
~/selfhost/
  Caddyfile                     existing; add the chorus block below
  chorus/
    app/chorus-server.exe       deployed binary (+ web/ static build)
    data/chorus.db              SQLite (WAL: chorus.db-wal, chorus.db-shm)
    data/blobs/ab/cd/<sha256>   content-addressed attachments
    backups/                    nightly snapshots
    chorus.toml                 config
    logs/
    install.ps1 / uninstall.ps1 / check.cmd   (written by M12.1; the owner runs them as admin)
```

Existing services the owner already runs: Caddy (`*.vayne.garden`, Cloudflare DNS-01 TLS) and
**ntfy at `ntfy.vayne.garden` → 127.0.0.1:2586**. Chorus reuses that ntfy as its UnifiedPush
distributor; no second ntfy.

## 2. Network

- Chorus listens on `127.0.0.1:5250` only.
- Caddy block (M12.1 appends it; mirrors Arbor):

  ```
  chorus.vayne.garden {
  	import common
  	reverse_proxy 127.0.0.1:5250
  }
  ```

- DNS: Cloudflare A record `chorus` → the PC's Tailscale IP (DNS only, not proxied), same as
  memos/arbor. Result: valid TLS (needed for PWA service workers and App Links), reachable only by
  tailnet members.
- App Links: the server serves `/.well-known/assetlinks.json` with the release signing cert.
- Other people on the tailnet: they need to be invited to the tailnet (or the node shared with
  them) by the owner — Tailscale admin step, documented in the README.

## 3. Config (`chorus.toml`)

```toml
[server]
listen = "127.0.0.1:5250"
# lan_listen = "0.0.0.0:5251"             # Chorus Home only (D-071, HOME.md): TLS for phones on the wifi
public_url = "https://chorus.vayne.garden"
data_dir = "C:/Users/pcuser/selfhost/chorus/data"

[push]
ntfy_url = "https://ntfy.vayne.garden"      # UnifiedPush distributor
# ntfy_token = "…"                           # if ntfy has auth enabled

[backup]
dir = "C:/Users/pcuser/selfhost/chorus/backups"
time = "04:00"
keep_daily = 14
keep_weekly = 8
keep_monthly = 12

[limits]
max_blob_mb = 100
snapshot_threshold = 20000

[security]
tailscale_whois = false                      # D-034 optional second factor
webhook_targets = "internal"                 # internal (tailnet/LAN, never loopback) | public | any; push endpoints too (API.md §2.1)
rate_burst = 50                              # API requests per token/session (else per address)
rate_per_second = 10                         # sustained; 0 turns request limits off
sync_sockets_per_account = 20                # open sync sockets per account; a new one closes the oldest
sync_sockets_per_address = 30                # sync sockets per address not signed in yet; more get 429
sync_frame_burst = 200                       # frames a sync socket may send at once …
sync_frames_per_second = 50                  # … and sustained; 0 turns the frame budget off
```

Every option also has a default in code; the file may be empty.

## 4. CLI

```
chorus-server serve                     # what the service runs
chorus-server invite --kind system|person [--expires 7d]   # prints URL + QR in terminal
chorus-server backup [--to path]        # online backup (SQLite backup API) + blob manifest
chorus-server restore --from <snapshot> --into <new dir>   # verifies, bumps epoch (SYNC.md §7.3)
chorus-server rebuild                   # rebuild all projections from the op log
chorus-server export --account <id> --kind full|csv|sqlite
chorus-server check                     # integrity_check, digests, orphan blobs, config
chorus-server purge --message <id> | --op <id> | --account <id> [--yes]   # the only true erase (D-053): asks to
                                        # confirm, rebuilds projections, deletes files nothing uses,
                                        # logs to data/purge.log; devices and old backups keep copies
                                        # --account removes a non-admin account (e.g. a test
                                        # account) with everything it wrote; admins are refused
chorus-server seed --to <new dir>       # test data for the SPEC §9 budgets
chorus-server reconcile-status          # after a restore: is the restore window open, which devices are back
chorus-server reconcile-close           # close it once they all are (it closes by itself 7 days after)
                                        # (both also in the web app: Your data → Server health, admins)
chorus-server migrate                   # run pending schema migrations (also automatic on serve)
```

## 5. Backups (D-043)

- Nightly at `backup.time`: a snapshot directory `backups/chorus-<stamp>-<rand>/` with
  `chorus.db` (SQLite online backup) and `manifest.json` (database sha256, blob list); blobs are
  immutable and copied once into `backups/blobs/` (D-064). `chorus-server restore --from <snapshot>
  --into <new dir>` verifies checksums, integrity and a projection rebuild, then bumps the epoch.
- Rotation per `keep_*`.
- Before every migration: automatic backup, refuse to migrate if it fails.
- `GET /admin/health` and Settings → Data show "Last backup: 04:00 today · 212 MB".
- Phone replica: the owner's Android holds the full account scope; after a restore, it
  reconciles anything newer than the backup (SYNC.md §7.3). While the restore window is open, an
  admin's *Your data* page shows "Restore in progress — N of M devices back" with a Close button
  (`GET /admin/health` → `restore_window`, `POST /admin/reconcile/close`); close it once every
  device that matters is back.
- Export on demand from any client (DATA_MODEL.md §7).

## 6. Deploy / update

- `scripts/deploy.ps1` (M12.1): builds the release binary and web bundle, stops the service,
  copies to `~/selfhost/chorus/app`, starts the service, checks `/api/v1/server`.
- Migrations are forward-only, run automatically on start after the pre-migration backup.
- Android releases: signed APK built locally (`scripts/android-release.ps1`); served for friends
  at `https://chorus.vayne.garden/download/android` with an in-app "Update available" check
  (compares `/api/v1/server` → `android_latest`), silent OTA where Android allows it
  (CLIENTS.md §5a). `scripts/android-release.ps1` also copies the APK to
  `~/selfhost/chorus/app/releases/` and bumps `android_latest`. Obtainium-compatible.
- Service install: the owner runs `install.ps1` as admin (agents never install services).

## 7. Observability

- Structured logs (JSON lines) in `logs/`, rolled daily, 14 days.
- `GET /admin/health`: DB/WAL size, op count, connected devices, pending notifications, ntfy
  reachability, last backup, last error.
- Optional Prometheus `/metrics` (Advanced, off by default).

## 8. Dev setup (for agents)

- `cargo run -p chorus-server -- serve --dev` → data in `./data-dev`, port 5251, relaxed auth
  (dev invite printed on start), CORS for Vite.
- `npm run dev` in `web/` → Vite on 5252 proxying `/api` to 5251.
- Android emulator reaches the dev server at `http://10.0.2.2:5251` (cleartext allowed only in
  the debug build's network security config).
- Seed data: `chorus-server seed --members 300 --switches 20000 --messages 100000 --to data-seed`
  for performance testing against the SPEC §9 budgets. The destination must be a new directory;
  the command refuses to overwrite even an empty existing directory. Omit `--to` only when the
  configured data directory does not exist yet.
- Browser end-to-end suite (the v1 flows in three browsers): `bash scripts/e2e-web.sh` starts
  its own server on a temporary data directory; `web/e2e/README.md` also shows how to run it
  against a live server. CI runs it as the `e2e` job.

## 9. Linux server (public VPS)

The walkthrough for the owner is `deploy/linux/README.txt` (first install, moving from the PC,
updates). What differs from the PC:

- **Build**: `pwsh scripts/pack-linux.ps1 [-WithData] [-NoBuild]` builds a static x86_64 musl
  binary in the `rust:1.98-bookworm` image through Docker in WSL (repo mounted read-only; target
  and registry cache in `F:\DunBuild\chorus-linux`), builds the web app, and writes
  `chorus-linux-<git describe>.tar.gz` with `install.sh`, `files/` and `README.txt`.
  `-WithData` adds `chorus-server backup` output from the PC install as `import/`.
- **Test**: `pwsh scripts/test-linux.ps1 [-Keep]` boots a throwaway Debian 12 container running
  systemd (Docker in WSL, `deploy/linux/test/`), installs the newest bundle for
  `https://localhost` (Caddy's local CA), checks HTTPS, headers, the sync WebSocket and the
  systemd sandbox score through Caddy, reruns it as an update, then imports a snapshot of
  `./data-dev`. First run 2026-09-24: all passed (after fixing the static link and Caddy's log
  file ownership).
- **Layout**: `/opt/chorus/{app,data,backups}` (data and backups owned by the `chorus` user),
  `/etc/chorus/chorus.toml`, unit `chorus.service` (sandboxed: only data and backups writable),
  Caddy site `/etc/caddy/sites/chorus.caddy`, imported from the main Caddyfile.
- **Updates**: rerun `install.sh` from a newer bundle; it swaps `app/` and restarts. No binary
  watcher (`CHORUS_RESTART_ON_CHANGE` is a Windows deploy trick). The server stops cleanly on SIGTERM.
- **Moving data**: `install.sh --import [--replace-data]` goes through `chorus-server restore`
  (checksums, integrity, projection rebuild, epoch bump), so devices re-send anything newer.
  Keep the same domain and move its DNS record; devices remember the address.
- **Exposure**: public, not tailnet-only. Invites stay the only way in; `webhook_targets =
  "public"` keeps webhooks and push endpoints off the host's own services (Caddy admin API,
  other apps; the configured `ntfy_url` is the one exception, reached at its checked address);
  `tailscale_whois` is meaningless there. Backups land on the same disk: copy them off the box.
  Caddy's access log redacts `?token=` (the OBS overlay and EventSource streams carry API tokens
  in the URL).
  Sync sockets that don't sign in within 15 s are closed; each device keeps at most 5 open
  sign-in challenges. API requests are rate limited in the server (`ratelimit.rs`): 50 burst /
  10 per second per token or session (else per client address), sign-in 20 burst / 1 per second
  per address; over it, `429 rate_limited` with `Retry-After`. Blob reads aren't counted.
  The sync socket has its own limits (`[security]` `sync_*`, above): an account keeps at most 20
  open sockets (a 21st closes its oldest with the error frame `too_many_connections`); an
  address may hold 30 sockets that haven't signed in yet (more are refused with `429
  too_many_connections`; signing in or closing frees the slot); and each socket may send 200
  frames at once, 50 a second sustained, else it's closed with `rate_limited`. Apps send a
  handful of frames a second (pushes go 500 ops a frame, 4 in flight; a 5 000-op catch-up is
  about a dozen frames), so only a broken or hostile client meets these. The web app is served with a strict Content-Security-Policy (same origin
  only, plus `wasm-unsafe-eval` for the core); blob downloads are `private`, `nosniff` and
  sandboxed, so an uploaded file never runs as a page. The address comes from Caddy's `X-Forwarded-For`, trusted only on
  loopback connections. For floods below HTTP (SYN, many sockets), use the provider's firewall
  or fail2ban on `/var/log/caddy/chorus.log`.
- **Coexisting with the selfhost VPS bundle** (memos, ntfy, Arbor, …): that bundle's Caddyfile
  imports `/etc/caddy/sites/*.caddy`, so Chorus's site survives its reinstalls. Chorus can use
  that ntfy as its push distributor (`ntfy_url`).
