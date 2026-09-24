#!/usr/bin/env bash
# Inside the test VPS (scripts/test-linux.ps1): is Chorus answering through Caddy, sandboxed, and quiet in its log?
set -u
echo "--- https://localhost/api/v1/server (through Caddy)"
curl -sk https://localhost/api/v1/server; echo
echo "--- web app"
curl -sk https://localhost/ | grep -o '<title>[^<]*</title>'
echo "--- headers"
curl -skI https://localhost/ | grep -iE '^(strict-transport|x-content-type|server:)' || true
echo "--- invite page"
curl -sk -o /dev/null -w '%{http_code}\n' https://localhost/i/doesnotexist
echo "--- service"
systemctl show chorus -p ActiveState -p SubState -p User -p MainPID
systemd-analyze security chorus 2>/dev/null | tail -1
echo "--- files"
ls -la /opt/chorus /opt/chorus/data | head -20
stat -c '%U:%G %a %n' /etc/chorus/chorus.toml
echo "--- log"
journalctl -u chorus --no-pager -n 15 -o cat
echo "--- sync websocket through Caddy"
curl -sk -i -N --http1.1 --max-time 2 -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" https://localhost/api/v1/sync | head -n1
echo "--- data"
runuser -u chorus -- /opt/chorus/app/chorus-server --config /etc/chorus/chorus.toml check
