#!/usr/bin/env bash
# scripts/test-linux.ps1 for a Linux host with Docker: pack the Linux bundle the way
# scripts/pack-linux.ps1 does (static musl chorus-server built in rust:1.98-bookworm, the web app,
# deploy/linux/install.sh), then try it on a throwaway "VPS" (deploy/linux/test/: Debian 12 booting
# systemd): install for https://localhost (Caddy's local CA), check it through Caddy, rerun it as an
# update, then import a snapshot of a seeded data directory and check again.
#
#   bash scripts/test-linux.sh [--out DIR] [--no-build] [--keep]
#
# --out       where the build cache, bundle and test files go (default: target/linux-test)
# --no-build  reuse the last Linux build and web bundle
# --keep      leave the container running (docker exec -it chorus-vps bash) instead of removing it
#
# Needs docker (a running daemon), cargo, wasm-bindgen-cli and npm (web/node_modules installed),
# and containers that can reach deb.debian.org (the build installs musl-tools, the test image
# systemd, install.sh Caddy). Not yet run to the end: its first run (2026-09-24, a cloud
# container) had deb.debian.org blocked; the web build and the seeded snapshot worked.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/target/linux-test"
build=1
keep=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --no-build) build=0; shift ;;
    --keep) keep=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
mkdir -p "$out/target" "$out/cargo-registry"
out="$(cd "$out" && pwd)"
image="rust:1.98-bookworm"
bin="$out/target/x86_64-unknown-linux-musl/release/chorus-server"
say() { printf '\n\033[36m== %s\033[0m\n' "$*"; }

if [ "$build" = 1 ]; then
  say "web app"
  (cd "$root" && cargo build -p chorus-wasm --target wasm32-unknown-unknown --profile wasm)
  wasm="${CARGO_TARGET_DIR:-$root/target}/wasm32-unknown-unknown/wasm/chorus_wasm.wasm"
  wasm-bindgen "$wasm" --out-dir "$root/web/src/lib/core/pkg" --target web
  (cd "$root/web" && npm run build)

  say "Linux server ($image)"
  docker run --rm \
    -v "$root:/src:ro" \
    -v "$out/target:/target" \
    -v "$out/cargo-registry:/usr/local/cargo/registry" \
    -e CARGO_TARGET_DIR=/target \
    "$image" bash /src/scripts/build-linux.sh
fi
[ -x "$bin" ] || { echo "no Linux build at $bin (run without --no-build)" >&2; exit 1; }
[ -f "$root/web/dist/index.html" ] || { echo "no web build in web/dist (run without --no-build)" >&2; exit 1; }

say "bundle"
stage="$out/chorus"
rm -rf "$stage"
mkdir -p "$stage/app"
cp "$bin" "$stage/app/chorus-server"
cp -r "$root/web/dist" "$stage/app/web"
cp "$root/deploy/linux/install.sh" "$root/deploy/linux/README.txt" "$stage/"
cp -r "$root/deploy/linux/files" "$stage/files"
sed -i 's/\r$//' "$stage/install.sh"
version="$(git -C "$root" describe --always --dirty 2> /dev/null || date +%Y%m%d-%H%M)"
rm -f "$out"/chorus-linux-*.tar.gz
tar -czf "$out/chorus-linux-$version.tar.gz" -C "$out" chorus
echo "$out/chorus-linux-$version.tar.gz ($(du -h "$out/chorus-linux-$version.tar.gz" | cut -f1))"

say "snapshot of seeded data to import"
# test-linux.ps1 imports the PC's ./data-dev; here a small seeded directory stands in for it
(cd "$root" && cargo build -q -p chorus-server)
host="${CARGO_TARGET_DIR:-$root/target}/debug/chorus-server"
work="$out/test-work"
import="$out/import-test"
rm -rf "$work" "$import"
mkdir -p "$work"
printf '[server]\ndata_dir = "%s"\n' "$work/data" > "$work/dev.toml"
"$host" --config "$work/dev.toml" seed --members 12 --switches 300 --messages 2000 --to "$work/data" > /dev/null
"$host" --config "$work/dev.toml" backup --to "$import" > /dev/null
rm -rf "$work"

say "test VPS"
test="$root/deploy/linux/test"
docker build -q -t chorus-vps-test "$test"
docker rm -f chorus-vps > /dev/null 2>&1 || true
docker run -d --name chorus-vps --privileged --cgroupns=host \
  -v /sys/fs/cgroup:/sys/fs/cgroup:rw -v "$out:/bundle:ro" -v "$test:/t:ro" chorus-vps-test > /dev/null
cleanup() {
  if [ "$keep" = 0 ]; then docker rm -f chorus-vps > /dev/null 2>&1 || true; fi
  rm -rf "$import"
}
trap cleanup EXIT
sleep 4
say "first install"
docker exec chorus-vps bash /t/install.sh --domain localhost --email test@example.org
docker exec chorus-vps bash /t/check.sh
say "update (same bundle again)"
docker exec chorus-vps bash /t/install.sh
say "import"
docker exec -e IMPORT=1 chorus-vps bash /t/install.sh --import --replace-data
docker exec chorus-vps bash /t/check.sh
printf '\n\033[32m== passed\033[0m\n'
