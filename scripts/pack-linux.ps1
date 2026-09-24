# Pack Chorus for a Linux server (deploy/linux/README.txt): a static x86_64 chorus-server built in
# Docker under WSL, the web app, the Android update files, install.sh, and optionally a snapshot of
# this PC's data to import.
#
#   pwsh scripts/pack-linux.ps1              # program only (updates an existing server)
#   pwsh scripts/pack-linux.ps1 -WithData    # plus a verified snapshot of the PC's Chorus data
#   pwsh scripts/pack-linux.ps1 -NoBuild     # reuse the last Linux build and web bundle
#
# Output: <Out>\chorus-linux-<version>.tar.gz. Copy it to the server, then:
#   tar xzf chorus-linux-*.tar.gz && cd chorus && sudo bash install.sh --domain chorus.example.org [--import]
param(
    [switch]$WithData,
    [switch]$NoBuild,
    [string]$Out = 'F:\DunBuild\chorus-linux',
    [string]$Image = 'rust:1.98-bookworm',
    [string]$Distro = 'Ubuntu',
    [string]$PcInstall = (Join-Path $env:USERPROFILE 'selfhost\chorus')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

function ToWsl([string]$p) {
    $full = [IO.Path]::GetFullPath($p)
    '/mnt/' + $full.Substring(0, 1).ToLower() + $full.Substring(2).Replace('\', '/')
}

$target = Join-Path $Out 'target'
$registry = Join-Path $Out 'cargo-registry'
New-Item -ItemType Directory -Force $Out, $target, $registry | Out-Null
$bin = Join-Path $target 'x86_64-unknown-linux-musl\release\chorus-server'

if (-not $NoBuild) {
    Write-Host '== web app' -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'build-web-core.ps1')
    Push-Location (Join-Path $root 'web')
    try {
        npm run build
        if ($LASTEXITCODE) { throw 'web build failed' }
    } finally { Pop-Location }

    Write-Host "== Linux server ($Image in WSL $Distro)" -ForegroundColor Cyan
    $run = @(
        '-d', $Distro, '--', 'docker', 'run', '--rm',
        '-v', "$(ToWsl $root):/src:ro",
        '-v', "$(ToWsl $target):/target",
        '-v', "$(ToWsl $registry):/usr/local/cargo/registry",
        '-e', 'CARGO_TARGET_DIR=/target',
        $Image, 'bash', '/src/scripts/build-linux.sh'
    )
    & wsl.exe @run
    if ($LASTEXITCODE) { throw 'Linux build failed' }
}
if (-not (Test-Path $bin)) { throw "no Linux build at $bin (run without -NoBuild)" }
$dist = Join-Path $root 'web\dist'
if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw "no web build in $dist (run without -NoBuild)" }

Write-Host '== bundle' -ForegroundColor Cyan
$stage = Join-Path $Out 'chorus'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
$app = Join-Path $stage 'app'
New-Item -ItemType Directory -Force $app | Out-Null
Copy-Item $bin (Join-Path $app 'chorus-server')
Copy-Item -Recurse $dist (Join-Path $app 'web')
$android = Join-Path $PcInstall 'app\android'
if (Test-Path (Join-Path $android 'chorus.json')) {
    Copy-Item -Recurse $android (Join-Path $app 'android')
} else {
    Write-Host "   (no Android update files in $android; in-app updates stay off until deploy.ps1 -Android)" -ForegroundColor DarkGray
}
Copy-Item (Join-Path $root 'deploy\linux\install.sh') $stage
Copy-Item (Join-Path $root 'deploy\linux\README.txt') $stage
Copy-Item -Recurse (Join-Path $root 'deploy\linux\files') (Join-Path $stage 'files')

if ($WithData) {
    Write-Host '== data snapshot' -ForegroundColor Cyan
    $exe = Join-Path $PcInstall 'app\chorus-server.exe'
    $toml = Join-Path $PcInstall 'chorus.toml'
    if (-not (Test-Path $exe)) { throw "no PC install at $PcInstall" }
    $svc = Get-Service Chorus -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -eq 'Running') {
        Write-Host '   The PC server is still running. That is fine for a rehearsal: the import bumps the epoch,' -ForegroundColor Yellow
        Write-Host '   so devices re-send anything newer. For the real move, stop it first (Services app: Chorus > Stop).' -ForegroundColor Yellow
    }
    & $exe --config $toml backup --to (Join-Path $stage 'import')
    if ($LASTEXITCODE) { throw 'snapshot failed' }
}

# shell scripts must reach Linux with LF endings, whatever git's checkout did
foreach ($sh in @((Join-Path $stage 'install.sh'))) {
    $text = [IO.File]::ReadAllText($sh).Replace("`r`n", "`n")
    [IO.File]::WriteAllText($sh, $text, [Text.UTF8Encoding]::new($false))
}

$version = (git -C $root describe --always --dirty 2>$null)
if (-not $version) { $version = Get-Date -Format 'yyyyMMdd-HHmm' }
$tar = Join-Path $Out "chorus-linux-$version.tar.gz"
if (Test-Path $tar) { Remove-Item $tar }
tar.exe -czf $tar -C $Out chorus
if ($LASTEXITCODE) { throw 'tar failed' }
Write-Host ("== {0} ({1:N1} MB)" -f $tar, ((Get-Item $tar).Length / 1MB)) -ForegroundColor Green
Write-Host '   On the server: tar xzf <file> && cd chorus && sudo bash install.sh --domain <domain> [--import]'
