# Chorus — operations

The server runs on the owner's Windows PC, same pattern as Arbor: an NSSM service behind Caddy,
reachable only over Tailscale.

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
webhooks_allow_external = false
```

Every option also has a default in code; the file may be empty.

## 4. CLI

```
chorus-server serve                     # what the service runs
chorus-server invite --kind system|person [--expires 7d]   # prints URL + QR in terminal
chorus-server backup [--to path]        # online backup (SQLite backup API) + blob manifest
chorus-server restore <backup>          # stops if serving; bumps epoch (SYNC.md §7.3)
chorus-server rebuild                   # rebuild all projections from the op log
chorus-server export --account <id> --kind full|csv|sqlite
chorus-server check                     # integrity_check, digests, orphan blobs, config
chorus-server reconcile-status          # devices that re-synced since last restore
chorus-server purge --message <id> | --op <id>   # the only true erase (D-053); asks to confirm, logged
chorus-server migrate                   # run pending schema migrations (also automatic on serve)
```

## 5. Backups (D-043)

- Nightly at `backup.time`: SQLite online backup → `backups/chorus-YYYYMMDD-HHMM.db.zst` plus a
  blob manifest; blobs themselves are immutable and copied incrementally to `backups/blobs/`.
- Rotation per `keep_*`.
- Before every migration: automatic backup, refuse to migrate if it fails.
- `GET /admin/health` and Settings → Data show "Last backup: 04:00 today · 212 MB".
- Phone replica: the owner's Android holds the full account scope; after a restore, it
  reconciles anything newer than the backup (SYNC.md §7.3).
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
- Seed data: `chorus-server seed --members 300 --switches 20000 --messages 100000` for
  performance testing against the SPEC §9 budgets.
