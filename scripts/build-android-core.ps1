# Build chorus-core for Android: native libs (cargo-ndk) + Kotlin bindings (UniFFI).
#
#   pwsh scripts/build-android-core.ps1 [-Debug]
#
# Needs: cargo-ndk (`cargo install cargo-ndk`), Rust targets aarch64-linux-android and
# x86_64-linux-android, and an NDK. NDK lookup: $env:ANDROID_NDK_HOME, then $env:NDK_HOME,
# then the newest under F:\DunBuild\android-sdk\ndk (shared with Dun), then the SDK's ndk dir.
param([switch]$Debug)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

function Find-Ndk {
    foreach ($c in @($env:ANDROID_NDK_HOME, $env:NDK_HOME)) { if ($c -and (Test-Path $c)) { return $c } }
    foreach ($dir in @('F:\DunBuild\android-sdk\ndk', (Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk'))) {
        if (Test-Path $dir) {
            $v = Get-ChildItem $dir -Directory | Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } | Select-Object -Last 1
            if ($v) { return $v.FullName }
        }
    }
    throw 'No Android NDK found. Set ANDROID_NDK_HOME.'
}

$env:ANDROID_NDK_HOME = Find-Ndk
Write-Host "NDK: $env:ANDROID_NDK_HOME"
$profile = if ($Debug) { @() } else { @('--release') }
$out = Join-Path $root 'android\core-bridge\src\main\jniLibs'
New-Item -ItemType Directory -Force $out | Out-Null

Push-Location $root
try {
    cargo ndk -t arm64-v8a -t x86_64 -o $out build -p chorus-ffi @profile
    if ($LASTEXITCODE) { throw 'cargo ndk failed' }
    # Bindings are generated from the host build of the same crate (library mode).
    cargo build -p chorus-ffi @profile
    if ($LASTEXITCODE) { throw 'host build failed' }
    $dir = if ($Debug) { 'debug' } else { 'release' }
    $lib = Join-Path $root "target\$dir\chorus_ffi.dll"
    $kt = Join-Path $root 'android\core-bridge\src\main\kotlin'
    cargo run -q -p chorus-ffi --bin uniffi-bindgen -- generate --library $lib --language kotlin --out-dir $kt --no-format
    if ($LASTEXITCODE) { throw 'uniffi-bindgen failed' }
    Write-Host "Native libs: $out"
    Write-Host "Kotlin bindings: $kt"
} finally { Pop-Location }
