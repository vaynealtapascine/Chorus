# Try the Linux bundle on a throwaway "VPS": a Debian 12 container booting systemd, in Docker under
# WSL. Installs the newest bundle from pack-linux.ps1 for https://localhost (Caddy's local CA),
# checks it through Caddy, reruns it as an update, then imports a snapshot of ./data-dev.
#
#   pwsh scripts/pack-linux.ps1 && pwsh scripts/test-linux.ps1 [-Keep]
#
# -Keep leaves the container running (docker exec -it chorus-vps bash) instead of removing it.
param(
    [string]$Out = 'F:\DunBuild\chorus-linux',
    [string]$Distro = 'Ubuntu',
    [switch]$Keep
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

function ToWsl([string]$p) {
    $full = [IO.Path]::GetFullPath($p)
    '/mnt/' + $full.Substring(0, 1).ToLower() + $full.Substring(2).Replace('\', '/')
}
function Wsl([string[]]$argv) {
    & wsl.exe -d $Distro -- @argv
    if ($LASTEXITCODE) { throw "failed: $($argv -join ' ')" }
}

if (-not (Get-ChildItem $Out -Filter 'chorus-linux-*.tar.gz' -ErrorAction SilentlyContinue)) {
    throw "no bundle in $Out; run scripts/pack-linux.ps1 first"
}
$test = Join-Path $root 'deploy\linux\test'

Write-Host '== snapshot of data-dev to import' -ForegroundColor Cyan
$work = Join-Path $Out 'test-work'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null
Copy-Item -Recurse (Join-Path $root 'data-dev') (Join-Path $work 'data')
Set-Content (Join-Path $work 'dev.toml') "[server]`ndata_dir = `"$((Join-Path $work 'data').Replace('\', '/'))`"`n"
$exe = Join-Path ($env:CARGO_TARGET_DIR ?? 'F:\DunBuild\chorus-target') 'debug\chorus-server.exe'
if (-not (Test-Path $exe)) { throw "build the Windows server first (cargo build -p chorus-server): $exe" }
$import = Join-Path $Out 'import-test'
if (Test-Path $import) { Remove-Item -Recurse -Force $import }
& $exe --config (Join-Path $work 'dev.toml') backup --to $import | Out-Null
if ($LASTEXITCODE) { throw 'snapshot failed' }
# restore wants the snapshot folder itself as import/<snapshot>, blobs next to it
Remove-Item -Recurse -Force $work

Write-Host '== test VPS' -ForegroundColor Cyan
Wsl @('docker', 'build', '-q', '-t', 'chorus-vps-test', (ToWsl $test))
& wsl.exe -d $Distro -- docker rm -f chorus-vps 2>$null | Out-Null
Wsl @('docker', 'run', '-d', '--name', 'chorus-vps', '--privileged', '--cgroupns=host',
    '-v', '/sys/fs/cgroup:/sys/fs/cgroup:rw', '-v', "$(ToWsl $Out):/bundle:ro", '-v', "$(ToWsl $test):/t:ro",
    'chorus-vps-test')
Start-Sleep -Seconds 4
try {
    Write-Host '== first install' -ForegroundColor Cyan
    Wsl @('docker', 'exec', 'chorus-vps', 'bash', '/t/install.sh', '--domain', 'localhost', '--email', 'test@example.org')
    Wsl @('docker', 'exec', 'chorus-vps', 'bash', '/t/check.sh')
    Write-Host '== update (same bundle again)' -ForegroundColor Cyan
    Wsl @('docker', 'exec', 'chorus-vps', 'bash', '/t/install.sh')
    Write-Host '== import' -ForegroundColor Cyan
    Wsl @('docker', 'exec', '-e', 'IMPORT=1', 'chorus-vps', 'bash', '/t/install.sh', '--import', '--replace-data')
    Wsl @('docker', 'exec', 'chorus-vps', 'bash', '/t/check.sh')
    Write-Host '== passed' -ForegroundColor Green
} finally {
    if (-not $Keep) { & wsl.exe -d $Distro -- docker rm -f chorus-vps 2>$null | Out-Null }
    Remove-Item -Recurse -Force $import -ErrorAction SilentlyContinue
}
