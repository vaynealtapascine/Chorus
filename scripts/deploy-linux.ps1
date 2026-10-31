# Update Chorus on the Linux server (deploy/linux, docs/OPS.md §9) from this PC: pack, copy over
# ssh, run install.sh there. Data and settings on the server stay; the service restarts.
#
#   pwsh scripts/deploy-linux.ps1              build, pack and install on root@ns2-up-net-nz
#   pwsh scripts/deploy-linux.ps1 -Android     also build and publish the Android app first, so
#                                              phones get it as an in-app update
#   pwsh scripts/deploy-linux.ps1 -NoBuild     reuse the last Linux build and web bundle
#   pwsh scripts/deploy-linux.ps1 -Remote root@other-server
#
# The Android app is staged in the PC install's app\android (deploy.ps1 -AndroidOnly) and packed
# from there, as pack-linux.ps1 always has.
param([switch]$NoBuild, [switch]$Android, [string]$Remote = 'root@ns2-up-net-nz')
$ErrorActionPreference = 'Stop'

if ($Android) {
    & (Join-Path $PSScriptRoot 'deploy.ps1') -AndroidOnly
    if ($LASTEXITCODE) { throw 'Android build failed' }
}
$packArgs = @{}
if ($NoBuild) { $packArgs.NoBuild = $true }
& (Join-Path $PSScriptRoot 'pack-linux.ps1') @packArgs
$tar = Get-ChildItem 'F:\DunBuild\chorus-linux\chorus-linux-*.tar.gz' | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $tar) { throw 'no bundle to send' }

Write-Host "== $($tar.Name) -> $Remote" -ForegroundColor Cyan
scp -q $tar.FullName "${Remote}:/root/chorus-update.tar.gz"
if ($LASTEXITCODE) { throw 'copy failed' }
# install.sh keeps the domain, the tailnet setup and the data from the last install
$remoteScript = 'set -e; rm -rf /root/chorus-update; mkdir /root/chorus-update; ' +
    'tar xzf /root/chorus-update.tar.gz -C /root/chorus-update; ' +
    'bash /root/chorus-update/chorus/install.sh; ' +
    'rm -rf /root/chorus-update /root/chorus-update.tar.gz'
ssh $Remote $remoteScript
if ($LASTEXITCODE) { throw "install.sh failed on $Remote (its output above says how far it got)" }
$server = ssh $Remote 'curl -fsS http://127.0.0.1:5250/api/v1/server'
Write-Host "== running: $server" -ForegroundColor Green
