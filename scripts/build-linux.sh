#!/usr/bin/env bash
# Build a static Linux chorus-server (x86_64, musl) inside the rust image; scripts/pack-linux.ps1
# runs this with the repo at /src (read-only), CARGO_TARGET_DIR=/target and a registry cache.
# A static binary runs on any x86_64 distribution, whatever its glibc.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v musl-gcc > /dev/null; then
  apt-get update -q > /dev/null
  apt-get install -y -q musl-tools > /dev/null
fi
rustup target add x86_64-unknown-linux-musl > /dev/null
# musl-gcc compiles the C parts (SQLite, aws-lc); linking stays Rust's own static, self-contained
# musl link (setting musl-gcc as the linker gave a dynamic PIE that needs ld-musl at run time)
export CC_x86_64_unknown_linux_musl=musl-gcc
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="-C target-feature=+crt-static"
cd /src
cargo build --release --locked -p chorus-server --target x86_64-unknown-linux-musl
out="${CARGO_TARGET_DIR:-/src/target}/x86_64-unknown-linux-musl/release/chorus-server"
strip "$out"
file "$out" 2> /dev/null || true
if file "$out" 2> /dev/null | grep -q "dynamically linked"; then echo "not a static binary" >&2; exit 1; fi
"$out" --help > /dev/null
echo "built $out ($(du -h "$out" | cut -f1))"
