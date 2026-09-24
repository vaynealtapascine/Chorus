#!/usr/bin/env bash
# Browser end-to-end suite (web/e2e/, R12): build the server and the web app, start a server on a
# temporary data directory, and run Playwright against it.
#
#   bash scripts/e2e-web.sh [--no-build] [-- <playwright args>]
#
# Against a server that's already running (the v1 check on the owner's server), skip this script:
#   cd web && CHORUS_E2E_BASE=https://chorus.example.org \
#     CHORUS_E2E_CLI="/path/to/chorus-server --config /path/to/chorus.toml" npx playwright test
# (the suite makes accounts through that CLI's invites; remove them afterwards with `purge --account`).
#
# Needs cargo, wasm-bindgen-cli, npm (web/node_modules) and Playwright's Chromium
# (`npx playwright install chromium`, or PLAYWRIGHT_BROWSERS_PATH pointing at one).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
build=1
if [ "${1:-}" = "--no-build" ]; then build=0; shift; fi
if [ "${1:-}" = "--" ]; then shift; fi
target="${CARGO_TARGET_DIR:-$root/target}"

if [ "$build" = 1 ]; then
  (cd "$root" && cargo build -p chorus-server)
  (cd "$root" && cargo build -p chorus-wasm --target wasm32-unknown-unknown --profile wasm)
  wasm-bindgen "$target/wasm32-unknown-unknown/wasm/chorus_wasm.wasm" --out-dir "$root/web/src/lib/core/pkg" --target web
  (cd "$root/web" && npm run build)
fi
server="$target/debug/chorus-server"
[ -x "$server" ] || server="$server.exe" # Git Bash on Windows
[ -x "$server" ] || { echo "no server build at $target/debug/chorus-server" >&2; exit 1; }

# paths the server (and node) read: Windows form under Git Bash
native() { if command -v cygpath > /dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
data="$(mktemp -d "${TMPDIR:-/tmp}/chorus-e2e-XXXXXX")"
port="${CHORUS_E2E_PORT:-5399}"
cat > "$data/chorus.toml" <<TOML
[server]
listen = "127.0.0.1:$port"
public_url = "http://127.0.0.1:$port"
data_dir = "$(native "$data/data")"
web_dir = "$(native "$root/web/dist")"
TOML
"$server" --config "$data/chorus.toml" serve > "$data/server.log" 2>&1 &
pid=$!
cleanup() {
  kill "$pid" 2> /dev/null || true
  wait "$pid" 2> /dev/null || true
  if [ "${CHORUS_E2E_KEEP:-0}" = 1 ]; then echo "kept $data"; else rm -rf "$data"; fi
}
trap cleanup EXIT
for _ in $(seq 1 50); do
  curl -sf "http://127.0.0.1:$port/api/v1/server" > /dev/null && break
  sleep 0.2
done
curl -sf "http://127.0.0.1:$port/api/v1/server" > /dev/null || { cat "$data/server.log"; exit 1; }

export CHORUS_E2E_BASE="http://127.0.0.1:$port"
export CHORUS_E2E_CLI="$(native "$server") --config $(native "$data/chorus.toml")"
set +e
(cd "$root/web" && npx playwright test "$@")
status=$?
set -e
if [ "$status" != 0 ]; then echo "--- server log (last 40 lines)"; tail -n 40 "$data/server.log"; fi
exit "$status"
