#!/usr/bin/env bash
# Chorus on a Debian 12+ / Ubuntu 22.04+ server (x86_64), behind Caddy on the public internet
# (or only on your tailnet, with --tailnet). Run it as root from the unpacked bundle
# (scripts/pack-linux.ps1 makes the bundle):
#
#   sudo bash install.sh --domain chorus.example.org [--email you@example.org]
#                        [--ntfy https://ntfy.example.org] [--import] [--replace-data]
#                        [--tailnet --cf-token-file /root/cf-token]
#
# First run: installs Caddy if missing, creates the `chorus` user, /opt/chorus/{app,data,backups},
# /etc/chorus/chorus.toml and the chorus systemd service, adds the site to Caddy and prints your
# first invite. Later runs (from a newer bundle) replace app/ and restart; data and config stay.
#
#   --import        bring in the snapshot packed with `pack-linux.ps1 -WithData` (import/ in the
#                   bundle). Only into an empty data directory unless --replace-data is given, in
#                   which case the current data is kept aside as data.before-import-<time>.
#   --email         for Let's Encrypt notices (only used when this script writes a new Caddyfile)
#   --ntfy          your ntfy server, if push notifications should go through it
#   --no-caddy      don't touch Caddy (you proxy https://DOMAIN to 127.0.0.1:5250 yourself)
#   --tailnet       reachable only over Tailscale, like Chorus on the PC: the DNS record points at
#                   this machine's Tailscale address, Caddy gets the certificate through a Cloudflare
#                   DNS challenge (adds the caddy-dns/cloudflare module and holds the caddy package
#                   so apt can't drop it; update Caddy with `caddy upgrade`), and the firewall is
#                   left alone. Webhooks keep the PC's default: tailnet/LAN targets only.
#   --cf-token-file a file holding a Cloudflare API token that can edit the zone's DNS; copied to
#                   /etc/caddy/cloudflare.env (root only). Needed the first time with --tailnet.
set -euo pipefail

say() { printf '\n\033[36m== %s\033[0m\n' "$*"; }
ok() { printf '\033[32m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
note() { printf '\033[90m   %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
OPT=/opt/chorus
ETC=/etc/chorus
TOML="$ETC/chorus.toml"
UNIT=/etc/systemd/system/chorus.service
EXE="$OPT/app/chorus-server"

DOMAIN="" EMAIL="" NTFY="" IMPORT=0 REPLACE=0 CADDY=1 TAILNET=0 CF_TOKEN_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tailnet) TAILNET=1; shift ;;
    --cf-token-file) CF_TOKEN_FILE="${2:?--cf-token-file needs a value}"; shift 2 ;;
    --domain) DOMAIN="${2:?--domain needs a value}"; shift 2 ;;
    --email) EMAIL="${2:?--email needs a value}"; shift 2 ;;
    --ntfy) NTFY="${2:?--ntfy needs a value}"; shift 2 ;;
    --import) IMPORT=1; shift ;;
    --replace-data) REPLACE=1; shift ;;
    --no-caddy) CADDY=0; shift ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) fail "unknown option $1 (see --help)" ;;
  esac
done

[ "$(id -u)" = 0 ] || fail "run as root: sudo bash install.sh ..."
command -v systemctl > /dev/null || fail "this needs systemd"
[ "$(uname -m)" = x86_64 ] || fail "the bundled server is built for x86_64, this machine is $(uname -m)"
[ -x "$HERE/app/chorus-server" ] || [ -f "$HERE/app/chorus-server" ] || fail "no app/chorus-server next to install.sh; unpack the whole bundle"
CF_ENV=/etc/caddy/cloudflare.env
# an update keeps a tailnet install a tailnet install
if [ "$TAILNET" = 0 ] && [ "$CADDY" = 1 ] && grep -qs 'dns cloudflare' /etc/caddy/sites/chorus.caddy; then
  TAILNET=1
  note "keeping --tailnet from the last install"
fi
if [ "$TAILNET" = 1 ]; then
  command -v tailscale > /dev/null || fail "--tailnet needs Tailscale on this machine"
  [ "$CADDY" = 1 ] || fail "--tailnet sets up Caddy; leave out --no-caddy"
  if [ -n "$CF_TOKEN_FILE" ]; then
    [ -s "$CF_TOKEN_FILE" ] || fail "no token in $CF_TOKEN_FILE"
  elif [ ! -s "$CF_ENV" ]; then
    fail "--tailnet needs --cf-token-file the first time (a Cloudflare token that can edit the zone's DNS)"
  fi
fi

# The domain: from the command line, else from an earlier install.
if [ -z "$DOMAIN" ] && [ -f "$TOML" ]; then
  DOMAIN="$(sed -n 's#^public_url *= *"https://\([^"/]*\).*#\1#p' "$TOML" | head -n1)"
fi
[ -n "$DOMAIN" ] || fail "say which domain Chorus answers on: --domain chorus.example.org"
case "$DOMAIN" in *[!a-zA-Z0-9.-]*|.*|*.) fail "that doesn't look like a domain name: $DOMAIN" ;; esac

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fill() { sed -e "s#@DOMAIN@#$DOMAIN#g" "$1"; }

say "Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q > /dev/null
apt-get install -y -q curl ca-certificates gnupg > /dev/null
if [ "$CADDY" = 1 ] && ! command -v caddy > /dev/null; then
  note "installing Caddy from its official repository"
  curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q > /dev/null
  apt-get install -y -q caddy > /dev/null
fi
if [ "$TAILNET" = 1 ] && ! caddy list-modules 2> /dev/null | grep -qx 'dns.providers.cloudflare'; then
  # caddyserver.com builds the same Caddy with the module added; apt would put the plain one back
  note "adding the Cloudflare DNS module to Caddy"
  caddy add-package github.com/caddy-dns/cloudflare > /dev/null || fail "couldn't add caddy-dns/cloudflare to Caddy"
  apt-mark hold caddy > /dev/null
  note "caddy is held in apt now; update it with: caddy upgrade"
fi
[ "$CADDY" = 1 ] && ok "caddy $(caddy version | cut -d' ' -f1)"

say "User and folders"
id -u chorus > /dev/null 2>&1 || useradd --system --user-group --no-create-home --home-dir "$OPT" --shell /usr/sbin/nologin chorus
install -d -o root -g root -m 755 "$OPT" "$ETC"
install -d -o chorus -g chorus -m 750 "$OPT/data" "$OPT/backups"
ok "chorus user, $OPT/{app,data,backups}, $ETC"

say "Program"
was_running=0
systemctl is-active --quiet chorus && was_running=1
rm -rf "$OPT/app.new"
cp -a "$HERE/app" "$OPT/app.new"
chown -R root:root "$OPT/app.new"
chmod -R u=rwX,go=rX "$OPT/app.new"
chmod 755 "$OPT/app.new/chorus-server"
"$OPT/app.new/chorus-server" --help > /dev/null 2>&1 || fail "the new chorus-server doesn't run on this machine"
[ "$was_running" = 1 ] && systemctl stop chorus
rm -rf "$OPT/app.old"
[ -d "$OPT/app" ] && mv "$OPT/app" "$OPT/app.old"
mv "$OPT/app.new" "$OPT/app"
rm -rf "$OPT/app.old"
ok "$("$EXE" --version 2>/dev/null || echo chorus-server) in $OPT/app"

say "Settings"
if [ ! -f "$TOML" ]; then
  {
    echo "# Chorus settings (docs/OPS.md in the repository). Written once by install.sh; yours to edit."
    echo "[server]"
    echo "listen = \"127.0.0.1:5250\""
    echo "public_url = \"https://$DOMAIN\""
    echo "data_dir = \"$OPT/data\""
    echo "web_dir = \"$OPT/app/web\""
    echo "android_dir = \"$OPT/app/android\""
    echo
    echo "[push]"
    if [ -n "$NTFY" ]; then echo "ntfy_url = \"$NTFY\""; else echo "# ntfy_url = \"https://ntfy.example.org\""; fi
    echo
    echo "[backup]"
    echo "dir = \"$OPT/backups\"          # same disk: copy it somewhere else too"
    echo "time = \"04:00\""
    echo
    echo "[security]"
    if [ "$TAILNET" = 1 ]; then
      echo "# Only reachable over Tailscale: webhooks and push endpoints may point at tailnet/LAN"
      echo "# addresses (never this machine's loopback), as on the PC."
      echo "webhook_targets = \"internal\""
    else
      echo "# On the public internet, webhooks may only point to public addresses, never to this"
      echo "# machine's own services (Caddy's admin API, ntfy, ...)."
      echo "webhook_targets = \"public\""
    fi
  } > "$TMP/chorus.toml"
  install -o root -g chorus -m 640 "$TMP/chorus.toml" "$TOML"
  ok "wrote $TOML"
else
  ok "kept $TOML"
  [ "$TAILNET" = 1 ] || grep -q '^webhook_targets' "$TOML" || warn "$TOML has no webhook_targets; add webhook_targets = \"public\" under [security]"
fi

fresh=0
if [ "$IMPORT" = 1 ]; then
  say "Importing data"
  snap="$(find "$HERE/import" -mindepth 1 -maxdepth 1 -type d -name 'chorus-*' 2> /dev/null | sort | tail -n1)"
  [ -n "$snap" ] && [ -f "$snap/manifest.json" ] || fail "no snapshot in $HERE/import (pack with -WithData)"
  if [ -e "$OPT/data/chorus.db" ]; then
    [ "$REPLACE" = 1 ] || fail "$OPT/data already has a database; add --replace-data to set it aside and import"
    aside="$OPT/data.before-import-$(date +%Y%m%d-%H%M%S)"
    mv "$OPT/data" "$aside"
    install -d -o chorus -g chorus -m 750 "$OPT/data"
    warn "the previous data is in $aside"
  fi
  # restore verifies checksums and the op log, and bumps the epoch so devices re-send anything
  # newer than the snapshot (SYNC.md §7.3)
  work="$OPT/import-work"
  rm -rf "$work" && cp -a "$HERE/import" "$work" && chown -R chorus:chorus "$work"
  if ! runuser -u chorus -- "$EXE" --config "$TOML" restore --from "$work/$(basename "$snap")" --into "$work/restored"; then
    rm -rf "$work"
    fail "the snapshot didn't verify; nothing was imported"
  fi
  [ -z "$(ls -A "$OPT/data")" ] || fail "$OPT/data isn't empty; nothing was imported"
  rmdir "$OPT/data"
  mv "$work/restored" "$OPT/data"
  chown -R chorus:chorus "$OPT/data"
  chmod 750 "$OPT/data"
  rm -rf "$work"
  ok "imported $(basename "$snap")"
  note "devices hand back what the snapshot missed as they reconnect; follow it with 'reconcile-status' (README.txt, step 5)"
elif [ ! -e "$OPT/data/chorus.db" ]; then
  fresh=1
fi

say "Service"
fill "$HERE/files/chorus.service" > "$TMP/chorus.service"
install -m 644 "$TMP/chorus.service" "$UNIT"
systemctl daemon-reload
systemctl enable -q chorus
systemctl restart chorus
for _ in $(seq 1 30); do
  curl -fsS -o /dev/null http://127.0.0.1:5250/api/v1/server 2> /dev/null && break
  sleep 1
done
if ! curl -fsS -o /dev/null http://127.0.0.1:5250/api/v1/server 2> /dev/null; then
  journalctl -u chorus -n 40 --no-pager >&2 || true
  fail "Chorus didn't answer on 127.0.0.1:5250 (log above)"
fi
ok "chorus is running (systemctl status chorus, journalctl -u chorus)"

if [ "$CADDY" = 1 ]; then
  say "Caddy"
  install -d -m 755 /etc/caddy/sites
  install -d -o caddy -g caddy -m 750 /var/log/caddy
  fill "$HERE/files/chorus.caddy" > /etc/caddy/sites/chorus.caddy
  if [ "$TAILNET" = 1 ]; then
    # the record points at a tailnet address Let's Encrypt can't reach: prove the domain in DNS
    sed -i 's|^\t# @TLS@$|\ttls {\n\t\tdns cloudflare {env.CF_API_TOKEN}\n\t\tresolvers 1.1.1.1\n\t}|' /etc/caddy/sites/chorus.caddy
    if [ -n "$CF_TOKEN_FILE" ]; then
      printf 'CF_API_TOKEN=%s\n' "$(tr -d ' \r\n' < "$CF_TOKEN_FILE")" > "$TMP/cloudflare.env"
      install -o root -g root -m 600 "$TMP/cloudflare.env" "$CF_ENV"
    fi
    install -d -m 755 /etc/systemd/system/caddy.service.d
    printf '[Service]\nEnvironmentFile=%s\n' "$CF_ENV" > /etc/systemd/system/caddy.service.d/cloudflare.conf
    systemctl daemon-reload
  else
    sed -i '/# @TLS@/d' /etc/caddy/sites/chorus.caddy
  fi
  CF=/etc/caddy/Caddyfile
  if [ -f "$CF" ] && grep -q 'import /etc/caddy/sites/\*\.caddy' "$CF"; then
    note "the Caddyfile already imports /etc/caddy/sites"
  elif [ ! -f "$CF" ] || grep -q '/usr/share/caddy' "$CF"; then
    # the package's placeholder site: replace it with one that only imports the sites folder
    [ -f "$CF" ] && cp "$CF" "$CF.before-chorus"
    {
      echo "# /etc/caddy/Caddyfile - sites live in /etc/caddy/sites/*.caddy"
      if [ -n "$EMAIL" ]; then printf '{\n\temail %s\n}\n\n' "$EMAIL"; fi
      echo "import /etc/caddy/sites/*.caddy"
    } > "$CF"
    note "wrote a new $CF (the old one is $CF.before-chorus)"
  else
    cp "$CF" "$CF.before-chorus"
    printf '\n# Chorus and other sites installed as files\nimport /etc/caddy/sites/*.caddy\n' >> "$CF"
    note "added 'import /etc/caddy/sites/*.caddy' to $CF (the old one is $CF.before-chorus)"
  fi
  if ! (if [ "$TAILNET" = 1 ]; then set -a; . "$CF_ENV"; set +a; fi; caddy validate --config "$CF" --adapter caddyfile) > "$TMP/validate.log" 2>&1; then
    cat "$TMP/validate.log" >&2
    [ -f "$CF.before-chorus" ] && cp "$CF.before-chorus" "$CF"
    rm -f /etc/caddy/sites/chorus.caddy
    fail "Caddy rejected the configuration; it was put back as it was"
  fi
  # validating as root creates the site's log file as root; the caddy user must own it
  chown -R caddy:caddy /var/log/caddy
  systemctl enable -q caddy
  # restart rather than reload when Caddy must pick up its environment file
  if [ "$TAILNET" = 1 ]; then systemctl restart caddy; else systemctl reload caddy 2> /dev/null || systemctl restart caddy; fi
  ok "https://$DOMAIN -> 127.0.0.1:5250"

  if [ "$TAILNET" = 1 ]; then
    note "firewall untouched: Chorus is for the tailnet only"
  elif command -v ufw > /dev/null && ufw status | grep -q '^Status: active'; then
    ufw allow 80/tcp comment 'caddy (certificates, redirect)' > /dev/null
    ufw allow 443/tcp comment 'caddy' > /dev/null
    ufw allow 443/udp comment 'caddy http/3' > /dev/null
    note "ufw: 80/tcp and 443/tcp+udp are open"
  else
    note "no active ufw firewall; make sure ports 80 and 443 reach this machine"
  fi
fi

say "DNS"
resolved="$(getent ahostsv4 "$DOMAIN" 2> /dev/null | awk 'NR==1 {print $1}')"
mine="$(hostname -I 2> /dev/null | tr ' ' '\n' | grep -v '^$' || true)"
if [ -n "$resolved" ] && printf '%s\n' "$mine" | grep -qx "$resolved"; then
  ok "$DOMAIN points here ($resolved)"
elif [ "$TAILNET" = 1 ]; then
  warn "$DOMAIN resolves to '${resolved:-nothing}'. When this server is ready, point its A record at"
  note "this machine's Tailscale address, $(tailscale ip -4 2> /dev/null | head -n1) (DNS only / not proxied)."
  note "The certificate doesn't wait for that: Caddy proves the domain through Cloudflare's DNS."
else
  warn "$DOMAIN resolves to '${resolved:-nothing}'. Point an A record at this server's public IPv4"
  note "(DNS only / not proxied), then: systemctl reload caddy. Caddy fetches the certificate itself."
fi

if [ "$fresh" = 1 ]; then
  say "Your first invite"
  invite="$(runuser -u chorus -- "$EXE" --config "$TOML" invite --kind system 2> /dev/null | tail -n1 || true)"
  [ -n "$invite" ] || invite="(none made; use the command below)"
  echo "   $invite"
fi
echo
note "More invites: sudo -u chorus $EXE --config $TOML invite --kind system|person"
note "Backups go to $OPT/backups nightly; copy them off this machine too."
ok "Done."
