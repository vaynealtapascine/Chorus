# Build chorus-core for the web: wasm + JS/TS bindings into web/src/lib/core/pkg.
#
#   pwsh scripts/build-web-core.ps1 [-Debug]
#
# Needs: Rust target wasm32-unknown-unknown and wasm-bindgen-cli at the SAME version as the
# wasm-bindgen crate (`cargo install wasm-bindgen-cli --version 0.2.128 --locked`).
param([switch]$Debug)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
# @(...) keeps it an array: a bare `if` would unwrap to a string, which splats as characters
$cargoArgs = @(if (-not $Debug) { '--profile'; 'wasm' })
$dir = if ($Debug) { 'debug' } else { 'wasm' }  # the size-optimised profile (Cargo.toml)
$out = Join-Path $root 'web\src\lib\core\pkg'

Push-Location $root
try {
    cargo build -p chorus-wasm --target wasm32-unknown-unknown @cargoArgs
    if ($LASTEXITCODE) { throw 'wasm build failed' }
    wasm-bindgen "target\wasm32-unknown-unknown\$dir\chorus_wasm.wasm" --out-dir $out --target web
    if ($LASTEXITCODE) { throw 'wasm-bindgen failed' }
    $wasm = Join-Path $out 'chorus_wasm_bg.wasm'
    Write-Host ("wasm: {0:N0} bytes" -f (Get-Item $wasm).Length)
} finally { Pop-Location }
