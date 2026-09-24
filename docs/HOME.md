# Chorus Home — one-click self-hosting on a home PC (D-071)

For systems who want Chorus on their own computer and home wifi without learning Caddy, Tailscale
or a terminal. The owner's words (2026-09-24): "install an exe, have it run as a background
service that starts on boot and serves on a local port (config in a UI) and loads up the PWA …
for people who just want to run it on their local wifi without thinking about it too hard."

The technical installs (OPS.md §1–§9: Windows + Caddy + Tailscale, Linux VPS) stay as they are.
Chorus Home is a third way, Windows first.

## 1. What the person does

1. Downloads `ChorusHome-<version>.exe` from the GitHub release and double-clicks it.
2. Windows asks once for permission (UAC). Chorus installs, starts, and opens the browser at
   `http://localhost:<port>/` on the **setup page** (port 5250, or the next free one if
   something already uses it).
3. The setup page asks for a name (the system's, or theirs), creates the first account on this
   PC's browser, and shows **Add a phone**: a QR code to scan with the Android app, which
   connects over the home wifi.
4. From then on Chorus starts with Windows (before anyone signs in), keeps running in the
   background, and a Start-menu shortcut **Chorus** opens it in the browser.

Nothing else to learn. Uninstall from *Settings → Apps* (data stays unless they tick "also
delete my Chorus data").

## 2. Pieces

- **One program.** `chorus-server.exe` gains a Windows service mode and a self-install mode; the
  download is that exe, signed later if the owner gets a code-signing certificate (until then
  SmartScreen shows "Windows protected your PC → More info → Run anyway", which the download
  page explains). No installer toolchain (Inno/WiX) is needed.
  - `chorus-server.exe` double-clicked (no arguments, not already installed) → elevates, copies
    itself to `%ProgramFiles%\Chorus\`, data to `%ProgramData%\Chorus\` (config
    `chorus.toml`, database, blobs, backups), registers the **Chorus Home** Windows service (`ChorusHome`, distinct from the technical install's `Chorus`)
    (automatic start, restart on failure), adds a firewall rule for **private** networks only,
    an *Apps* uninstall entry and a Start-menu shortcut, starts the service, opens the browser.
  - Run again when installed → updates in place (stop service, swap exe, start) or offers
    Uninstall.
- **Setup and settings page** (`/setup`, in the web app; only reachable from the PC itself, i.e.
  a loopback client): first-run account creation, then *This computer* settings for an admin:
  port, "Let devices on my wifi connect" (on by default), start with Windows, where data lives,
  backups (a folder and "keep N"), and **Add a device** (invite QR). Changes are written to
  `chorus.toml` and the service restarts itself. No hand-edited TOML needed.
- **Home wifi transport (the hard part).** Browsers only give a page service workers, WebCrypto
  and push on `https://` or `localhost`, and Android refuses cleartext to LAN addresses. So:
  - The PC's own browser uses `http://localhost:<port>` — a secure context, the full PWA.
  - The server also listens with **TLS on the LAN** using a certificate it generates for itself
    on first run (self-signed, 10 years, SANs for the PC's LAN addresses and hostname). The
    invite link/QR for a phone carries the certificate's SHA-256 **fingerprint**
    (`chorus://192.168.1.20:5251/i/<code>#pin=sha256/<b64url>`: the app's own scheme, so the camera
    opens the Chorus app rather than a browser that doesn't know the pin; https underneath); the Android app pins exactly that
    certificate for this server (no CA, no warning, still encrypted and authenticated). If the
    PC's address changes, the app follows mDNS (`chorus-<id>.local`) and the pin still holds.
  - Other browsers on the wifi (a laptop, an iPhone) would see a certificate warning and get no
    offline mode or push; the settings page explains that and points to Tailscale for them
    (the technical install's path). A later option: a relay with real certificates.
- **Reachability.** Home wifi only, by design: nothing is exposed to the internet. Away from
  home, phones queue changes and sync when they're back (the app already works offline).

## 3. Build order

1. Server: `[server] lan_listen` + self-signed TLS listener (`rcgen` + `tokio-rustls`), the
   certificate fingerprint in `GET /api/v1/server` and in LAN invite links; loopback-only
   `/setup` endpoints (first account, settings read/write, restart).
2. Windows: service mode (`windows-service`), self-install / update / uninstall, firewall rule,
   Start-menu shortcut, uninstall entry. Tested in a
   Windows Sandbox or a VM, never on the owner's PC (agents don't install services there, and
   the owner's own server is the OPS.md install on 5250).
3. Web: the setup page and *This computer* settings.
4. Android: pinned-certificate connections from `#pin=` invites (OkHttp `CertificatePinner` plus
   a trust manager that accepts only the pinned self-signed leaf), LAN rediscovery by mDNS.
5. Release: a GitHub Actions job builds `ChorusHome-<version>.exe` (Windows runner) and attaches
   it to a release; the landing page links it.
