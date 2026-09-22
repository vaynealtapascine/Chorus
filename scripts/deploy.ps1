# Build Chorus and copy it into the self-hosting folder (default ~\selfhost\chorus).
#
#   pwsh scripts/deploy.ps1            build (release) + copy
#   pwsh scripts/deploy.ps1 -NoBuild   copy the existing build only
#
# Target layout (docs/OPS.md §1):
#   app\chorus-server.exe   the server (a running one is renamed aside; it notices and exits,
#                           and the service starts the new one — no admin needed per deploy)
#   app\web\                the built PWA, served straight from disk
#   data\                   database, blobs — never touched by deploys
#   install.cmd             one-time service + Caddy setup (double-click; asks for admin)
param([switch]$NoBuild, [string]$Target = (Join-Path $HOME 'selfhost\chorus'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

if (-not $NoBuild) {
    Push-Location $root
    try {
        cargo build --release -p chorus-server
        if ($LASTEXITCODE) { throw 'server build failed' }
        & (Join-Path $PSScriptRoot 'build-web-core.ps1')
        Push-Location web
        npm run build
        if ($LASTEXITCODE) { throw 'web build failed' }
        Pop-Location
    } finally { Pop-Location }
}

$app = Join-Path $Target 'app'
New-Item -ItemType Directory -Force $app, (Join-Path $Target 'data') | Out-Null

# Web first (served from disk; open pages keep working).
$web = Join-Path $app 'web'
if (Test-Path $web) { Remove-Item -Recurse -Force $web }
Copy-Item -Recurse (Join-Path $root 'web\dist') $web

# Deploy scripts
foreach ($f in 'install.ps1', 'install.cmd', 'check.cmd', 'README.txt') {
    Copy-Item (Join-Path $root "deploy\$f") (Join-Path $Target $f) -Force
}

# Server last. Windows lets us rename a running exe; the server sees its binary change and exits.
$exe = Join-Path $app 'chorus-server.exe'
$old = Join-Path $app 'chorus-server.old.exe'
if (Test-Path $old) { Remove-Item $old -Force -ErrorAction SilentlyContinue }
if (Test-Path $exe) { Rename-Item $exe 'chorus-server.old.exe' }
Copy-Item (Join-Path $root 'target\release\chorus-server.exe') $exe

$up = $false
foreach ($i in 1..20) {
    Start-Sleep -Milliseconds 500
    try { $s = Invoke-RestMethod 'http://127.0.0.1:5250/api/v1/server' -TimeoutSec 2; $up = $true; break } catch { }
}
if ($up) { Write-Host "Deployed; server $($s.version) is answering." -ForegroundColor Green }
else { Write-Host "Copied to $Target. If the service isn't installed yet, run $Target\install.cmd." -ForegroundColor Yellow }
