Chorus on a Linux server
========================

What's in this bundle
  install.sh     Installs or updates Chorus (run as root). Safe to rerun.
  app/           chorus-server (static x86_64 Linux build), the web app, Android update files.
  files/         The systemd unit and the Caddy site, filled in by install.sh.
  import/        Only in bundles packed with -WithData: a verified snapshot of the PC's data.

Where things go on the server
  /opt/chorus/app        the program (replaced by every install.sh run)
  /opt/chorus/data       database and attachments (never touched by updates)
  /opt/chorus/backups    nightly snapshots (same disk: copy them elsewhere too)
  /etc/chorus/chorus.toml   settings (written once, then yours)
  /etc/caddy/sites/chorus.caddy   the https site (Caddy gets the certificate itself)
  service: chorus         systemctl status chorus / journalctl -u chorus -f

Needs: Debian 12+ or Ubuntu 22.04+ on x86_64, ports 80 and 443 open, a DNS A record for the
domain pointing at the server (DNS only, not proxied), and root.


First install (empty server)
  1. On the PC:      pwsh scripts/pack-linux.ps1
  2. Copy:           scp F:\DunBuild\chorus-linux\chorus-linux-*.tar.gz you@server:
  3. On the server:  tar xzf chorus-linux-*.tar.gz && cd chorus
                     sudo bash install.sh --domain chorus.example.org --email you@example.org
  4. It prints your first invite. Open it on your phone or in a browser.


Moving from the PC (keeps everything; devices stay signed in)
  Keep the same domain (chorus.vayne.garden): devices remember the address, so they carry on
  once its DNS record points at the new server.

  Rehearsal (the PC keeps running; nothing changes for anyone):
    pwsh scripts/pack-linux.ps1 -WithData
    copy and unpack on the server, then
    sudo bash install.sh --domain chorus.vayne.garden --import
    Check it answers (curl -s http://127.0.0.1:5250/api/v1/server on the server) and that
    journalctl -u chorus shows no errors. The DNS record still points at the PC, so nobody
    uses the server yet.

  The move:
    1. On the PC, stop the Chorus service (Services app: Chorus > Stop), so nothing is
       written there after the snapshot.
    2. pwsh scripts/pack-linux.ps1 -WithData -NoBuild   (reuse the rehearsal's build)
    3. On the server: sudo bash install.sh --import --replace-data
       (the rehearsal's data is kept aside as /opt/chorus/data.before-import-<time>)
    4. Point the DNS A record for chorus.vayne.garden at the server's public IP (DNS only).
       Caddy gets a certificate within a minute of the record resolving.
    5. Phones and browsers reconnect by themselves. The import bumps the server's epoch, so
       every device also re-sends anything it has that the snapshot didn't (SYNC.md §7.3).
    6. Once it works: set the PC's Chorus service to Manual, and remove its block from the PC
       Caddyfile.

  Push notifications: devices register their ntfy endpoint with the server; nothing to move.
  If ntfy also moves to this server (the selfhost VPS bundle), phones re-register on their own.


Updating
  pwsh scripts/pack-linux.ps1, copy, unpack over the old folder, sudo bash install.sh
  The service restarts with the new program; schema migrations run by themselves after an
  automatic backup.


On the public internet (unlike the PC, which only the tailnet could reach)
  - Anyone can reach the sign-in page, but accounts only come from invites (144-bit codes);
    there is no sign-up form.
  - Webhooks may only point to public addresses (webhook_targets = "public" in chorus.toml),
    so nobody can use them to reach this server's own services.
  - API requests are rate limited per token, session or address (429 when over; see
    rate_burst / rate_per_second in chorus.toml).
  - The service runs as its own user in a systemd sandbox that can only write its data and
    backups.
  - Keep the OS patched (unattended-upgrades) and SSH on keys only; the selfhost VPS bundle
    sets both up if you use it.


Useful commands
  sudo -u chorus /opt/chorus/app/chorus-server --config /etc/chorus/chorus.toml invite --kind system|person
  sudo -u chorus /opt/chorus/app/chorus-server --config /etc/chorus/chorus.toml backup
  sudo -u chorus /opt/chorus/app/chorus-server --config /etc/chorus/chorus.toml check
  sudo systemctl restart chorus
  sudo journalctl -u chorus -f

Removing it
  sudo systemctl disable --now chorus && sudo rm /etc/systemd/system/chorus.service
  sudo rm /etc/caddy/sites/chorus.caddy && sudo systemctl reload caddy
  (/opt/chorus and /etc/chorus hold your data and settings; delete them yourself when sure.)
