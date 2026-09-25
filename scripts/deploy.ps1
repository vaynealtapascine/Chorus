# Build Chorus and copy it into the self-hosting folder (default ~\selfhost\chorus).
#
#   pwsh scripts/deploy.ps1            build (release) + copy
#   pwsh scripts/deploy.ps1 -NoBuild   copy the existing build only
#   pwsh scripts/deploy.ps1 -Android   also build and publish the Android app for in-app updates
#
# Target layout (docs/OPS.md §1):
#   app\chorus-server.exe   the server (a running one is renamed aside; it notices and exits,
#                           and the service starts the new one — no admin needed per deploy)
#   app\web\                the built PWA, served straight from disk
#   app\android\            chorus.apk + chorus.json (version, sha256) for in-app updates
#   data\                   database, blobs — never touched by deploys
#   install.cmd             one-time service + Caddy setup (double-click; asks for admin)
param([switch]$NoBuild, [switch]$Android, [string]$Target = (Join-Path $HOME 'selfhost\chorus'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

# Build off the small C: drive when the F: build area exists (C: filling up made a deploy stop
# before copying anything). Its own target dir: a dev server running from another one locks it.
if (Test-Path 'F:\DunBuild') {
    if (-not $env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR = 'F:\DunBuild\chorus-target-deploy' }
    if (-not $env:CHORUS_GRADLE_BUILD_DIR) { $env:CHORUS_GRADLE_BUILD_DIR = 'F:\DunBuild\chorus-gradle-deploy' }
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = 'F:\DunBuild\gradle' }
    $env:CARGO_INCREMENTAL = '0'
    Write-Host "Building in $env:CARGO_TARGET_DIR"
}

# Build everything before copying anything, so a failed build leaves the live copy untouched
# (a web app newer than its server can call routes the server doesn't have yet).
if (-not $NoBuild) {
    Push-Location $root
    try {
        cargo build --release -p chorus-server
        if ($LASTEXITCODE) { throw 'server build failed' }
        & (Join-Path $PSScriptRoot 'build-web-core.ps1')
        Push-Location web
        try {
            npm run build
            if ($LASTEXITCODE) { throw 'web build failed' }
        } finally { Pop-Location }
        if ($Android) {
            # AGP needs Java 11+; the machine JAVA_HOME may be an old Java 8 JRE, so prefer Studio's JBR
            $jbr = 'C:\Program Files\Android\Android Studio\jbr'
            if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
            elseif (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw 'no JDK found: install Android Studio or set JAVA_HOME to a Java 17+ JDK' }
            if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = 'F:\DunBuild\gradle' }
            & (Join-Path $PSScriptRoot 'build-android-core.ps1')
            Push-Location android
            try {
                .\gradlew.bat assembleRelease --offline --console=plain -q
                if ($LASTEXITCODE) { throw 'android build failed' }
            } finally { Pop-Location }
        }
    } finally { Pop-Location }
}

# Check every input exists before copying (a -NoBuild run may lack one).
# Honour CARGO_TARGET_DIR: builds may live off the small C: drive (see NOTES).
$targetRoot = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $root 'target' }
$serverExe = Join-Path $targetRoot 'release\chorus-server.exe'
if (-not (Test-Path $serverExe)) { throw "no server build at $serverExe" }
if (-not (Test-Path (Join-Path $root 'web\dist\index.html'))) { throw 'no web build in web\dist' }
if ($Android) {
    $outRoot = if ($env:CHORUS_GRADLE_BUILD_DIR) { Join-Path $env:CHORUS_GRADLE_BUILD_DIR 'app' } else { Join-Path $root 'android\app\build' }
    $apk = Get-ChildItem (Join-Path $outRoot 'outputs\apk\release') -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $apk) { throw "no release APK under $outRoot" }
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

# Android: phones on the tailnet pick this up within hours (or on next app start)
if ($Android) {
    $dir = Join-Path $app 'android'
    New-Item -ItemType Directory -Force $dir | Out-Null
    Copy-Item $apk.FullName (Join-Path $dir 'chorus.apk') -Force
    $count = [int](git -C $root rev-list --count HEAD)
    $meta = [ordered]@{
        version_code = $count
        version_name = "0.1.$count"
        sha256 = (Get-FileHash (Join-Path $dir 'chorus.apk') -Algorithm SHA256).Hash.ToLower()
        size = (Get-Item (Join-Path $dir 'chorus.apk')).Length
        changelog = (git -C $root log -5 --format='%s') -join "`n"
    }
    [IO.File]::WriteAllText((Join-Path $dir 'chorus.json'), ($meta | ConvertTo-Json))
    Write-Host "Published Android $($meta.version_name) ($([math]::Round($meta.size / 1MB, 1)) MB)."
}

# Server last. Windows lets us rename a running exe; the server sees its binary change and exits.
$exe = Join-Path $app 'chorus-server.exe'
$old = Join-Path $app 'chorus-server.old.exe'
if (Test-Path $old) { Remove-Item $old -Force -ErrorAction SilentlyContinue }
if (Test-Path $exe) { Rename-Item $exe 'chorus-server.old.exe' }
Copy-Item $serverExe $exe

$up = $false
foreach ($i in 1..20) {
    Start-Sleep -Milliseconds 500
    try { $s = Invoke-RestMethod 'http://127.0.0.1:5250/api/v1/server' -TimeoutSec 2; $up = $true; break } catch { }
}
if ($up) { Write-Host "Deployed; server $($s.version) is answering." -ForegroundColor Green }
else { Write-Host "Copied to $Target. If the service isn't installed yet, run $Target\install.cmd." -ForegroundColor Yellow }
