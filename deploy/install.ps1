# Chorus - one-time setup (safe to run again; it updates in place).
# Double-click install.cmd next to this file; it asks for administrator rights, then:
#   1. writes chorus.toml (first time only)
#   2. installs or updates the "Chorus" Windows service (NSSM, starts with Windows)
#   3. adds the site to Caddy (HTTPS via your Cloudflare DNS token) and reloads Caddy
#   4. checks the server answers, says if the DNS record is missing, and prints your first invite
# check.cmd runs the same checks without changing anything.
param(
  # Defaults to chorus.<the domain your Caddyfile already serves>.
  [string]$HostName = '',
  [int]$Port = 5250,
  [switch]$Check
)

$ErrorActionPreference = 'Stop'

function Done([int]$code) {
  try { Stop-Transcript | Out-Null } catch { }
  Write-Host ''
  try { Read-Host 'Press Enter to close' | Out-Null } catch { }
  exit $code
}
trap {
  Write-Host ''
  Write-Host "FAILED: $_" -ForegroundColor Red
  Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
  Done 1
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $Check -and -not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Port', "$Port")
  if ($HostName) { $argList += @('-HostName', "`"$HostName`"") }
  try { Start-Process powershell.exe -Verb RunAs -ArgumentList $argList }
  catch {
    Write-Host 'Chorus needs administrator rights to register the service.' -ForegroundColor Yellow
    Write-Host 'Answer Yes to the Windows prompt, or right-click the file and pick "Run as administrator".'
    Done 1
  }
  exit
}

$Root = Split-Path -Parent $PSCommandPath
$logFile = Join-Path $Root 'install.log'
if (-not $Check) { try { Start-Transcript -Path $logFile -Force | Out-Null } catch { } }

$App = Join-Path $Root 'app'
$Data = Join-Path $Root 'data'
$Exe = Join-Path $App 'chorus-server.exe'
$Toml = Join-Path $Root 'chorus.toml'
$SelfHost = Split-Path -Parent $Root
$Caddyfile = Join-Path $SelfHost 'Caddyfile'
$Caddy = Join-Path $SelfHost 'caddy.exe'
$Service = 'Chorus'

# Borrow the domain (and an existing ntfy) from the sites Caddy already serves.
$ntfy = ''
if (Test-Path $Caddyfile) {
  foreach ($line in Get-Content $Caddyfile) {
    if ($line -match '^\s*([a-z0-9][a-z0-9.-]*\.[a-z]{2,})\s*\{') {
      $site = $Matches[1]
      if (-not $ntfy -and $site -like 'ntfy.*') { $ntfy = "https://$site" }
      if (-not $HostName) {
        $parts = $site.Split('.')
        $HostName = if ($parts.Length -ge 3) { 'chorus.' + ($parts[1..($parts.Length - 1)] -join '.') } else { "chorus.$site" }
      }
    }
  }
}

if (-not (Test-Path $Exe)) { throw "No server in $App. Run 'pwsh scripts/deploy.ps1' in the Chorus repo first." }
New-Item -ItemType Directory -Force $Data | Out-Null

$Nssm = (Get-Command nssm.exe -ErrorAction SilentlyContinue).Source
if (-not $Nssm) {
  foreach ($svc in @('Arbor', 'memos-remind', 'caddy', 'memos')) {
    $img = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\$svc" -ErrorAction SilentlyContinue).ImagePath
    if ($img -and $img -match 'nssm') { $Nssm = $img.Trim('"'); break }
  }
}
if (-not $Nssm -or -not (Test-Path $Nssm)) { throw 'nssm.exe not found (winget install NSSM.NSSM)' }

$busy = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
if ($Check) {
  Write-Host ''
  Write-Host 'Check only - nothing was changed.' -ForegroundColor Cyan
  $svc = Get-Service $Service -ErrorAction SilentlyContinue
  Write-Host "service:  $(if ($svc) { "$($svc.Status) (installed)" } else { 'not installed yet' })"
  Write-Host "site:     $(if ($HostName) { $HostName } else { 'none - pass -HostName chorus.your-domain' })"
  Write-Host "ntfy:     $(if ($ntfy) { $ntfy } else { 'none found in Caddyfile (push notifications off)' })"
  Write-Host "port:     $Port $(if ($busy) { "in use by $((Get-Process -Id $busy.OwningProcess -ErrorAction SilentlyContinue).ProcessName)" } else { 'free' })"
  Write-Host "config:   $(if (Test-Path $Toml) { $Toml } else { 'will be written on install' })"
  Done 0
}

# --- config (first time only; edit it freely afterwards)
if (-not (Test-Path $Toml)) {
  $fwd = { param($p) $p -replace '\\', '/' }
  $lines = @(
    '# Chorus server configuration (docs/OPS.md §3). Every option has a default.',
    '[server]',
    "listen = `"127.0.0.1:$Port`"",
    "public_url = `"https://$HostName`"",
    "data_dir = `"$(& $fwd $Data)`"",
    "web_dir = `"$(& $fwd (Join-Path $App 'web'))`"",
    '',
    '[push]'
  )
  $lines += if ($ntfy) { "ntfy_url = `"$ntfy`"" } else { '# ntfy_url = "https://ntfy.your-domain"' }
  $lines += @('', '[backup]', "dir = `"$(& $fwd (Join-Path $Root 'backups'))`"")
  [IO.File]::WriteAllLines($Toml, $lines)
  Write-Host "Wrote $Toml"
}

# --- service
$exists = [bool](Get-Service $Service -ErrorAction SilentlyContinue)
if ($exists) {
  Write-Host 'Updating the Chorus service...'
  & $Nssm stop $Service | Out-Null
} else {
  Write-Host 'Installing the Chorus service...'
  & $Nssm install $Service $Exe | Out-Null
}
& $Nssm set $Service Application $Exe | Out-Null
& $Nssm set $Service AppParameters "serve --config `"$Toml`"" | Out-Null
& $Nssm set $Service AppDirectory $Root | Out-Null
& $Nssm set $Service DisplayName 'Chorus' | Out-Null
& $Nssm set $Service Description "Chorus on http://127.0.0.1:$Port (https://$HostName via Caddy)" | Out-Null
& $Nssm set $Service Start SERVICE_AUTO_START | Out-Null
& $Nssm set $Service AppExit Default Restart | Out-Null
& $Nssm set $Service AppRestartDelay 1000 | Out-Null
& $Nssm set $Service AppStdout (Join-Path $Data 'chorus.log') | Out-Null
& $Nssm set $Service AppStderr (Join-Path $Data 'chorus.log') | Out-Null
& $Nssm set $Service AppRotateFiles 1 | Out-Null
& $Nssm set $Service AppRotateOnline 1 | Out-Null
& $Nssm set $Service AppRotateBytes 1048576 | Out-Null
# the server exits when deploy.ps1 swaps its binary; NSSM brings up the new one
& $Nssm set $Service AppEnvironmentExtra 'CHORUS_RESTART_ON_CHANGE=1' 'RUST_LOG=info' | Out-Null
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& sc.exe failure $Service reset= 86400 actions= restart/5000/restart/10000/restart/30000 2>&1 | Out-Null
$ErrorActionPreference = $previous
& $Nssm start $Service | Out-Null

$healthy = $false
for ($i = 0; $i -lt 20 -and -not $healthy; $i++) {
  Start-Sleep -Milliseconds 500
  try { $h = Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/server" -TimeoutSec 2; $healthy = $true } catch { }
}
if (-not $healthy) {
  Write-Host "The service didn't answer on http://127.0.0.1:$Port. Last lines of its log:" -ForegroundColor Red
  $svcLog = Join-Path $Data 'chorus.log'
  if (Test-Path $svcLog) { Get-Content $svcLog -Tail 15 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray } }
  throw "Service did not start. Full details in $logFile"
}
Write-Host "Service running (Chorus $($h.version))." -ForegroundColor Green

# --- Caddy
if ($HostName -and (Test-Path $Caddyfile) -and (Test-Path $Caddy)) {
  $text = [IO.File]::ReadAllText($Caddyfile)
  if ($text -notmatch "(?m)^\s*$([regex]::Escape($HostName))\s*\{") {
    Write-Host "Adding $HostName to Caddy..."
    Copy-Item $Caddyfile "$Caddyfile.before-chorus" -Force
    $nl = "`r`n"
    if ($text -notmatch "`n$") { $text += $nl }
    [IO.File]::WriteAllText($Caddyfile, $text + "$nl$HostName {$nl`timport common$nl`treverse_proxy 127.0.0.1:$Port$nl}$nl")
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $reload = & $Caddy reload --config $Caddyfile --adapter caddyfile 2>&1
    $reloadCode = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($reloadCode -ne 0) {
      Copy-Item "$Caddyfile.before-chorus" $Caddyfile -Force
      $reload | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }
      throw 'Caddy rejected the new config; the Caddyfile was restored unchanged.'
    }
    Write-Host 'Caddy reloaded.' -ForegroundColor Green
  } else { Write-Host "Caddy already serves $HostName." }
} else { Write-Host 'No Caddy/Caddyfile found next to this folder - skipping HTTPS setup.' -ForegroundColor Yellow }

# --- DNS
$tsIp = $null
try { $tsIp = (& 'C:\Program Files\Tailscale\tailscale.exe' ip -4 2>$null | Select-Object -First 1).Trim() } catch { }
$resolved = $null
try { $resolved = (Resolve-DnsName $HostName -Type A -ErrorAction Stop | Where-Object { $_.IPAddress } | Select-Object -First 1).IPAddress } catch { }
Write-Host ''
if (-not ($tsIp -and $resolved -eq $tsIp)) {
  Write-Host 'Add this DNS record at your DNS provider (same as your other sites):' -ForegroundColor Yellow
  Write-Host "   Type A   Name $($HostName.Split('.')[0])   IPv4 $(if ($tsIp) { $tsIp } else { '<this PC''s Tailscale IP>' })   Proxy: DNS only"
}

# --- first invite (only while nobody has an account)
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$invite = & $Exe --config $Toml invite --kind system 2>$null | Select-Object -Last 1
$ErrorActionPreference = $previous
Write-Host ''
Write-Host 'Your invite (open it on your phone or in a browser to set up your system):' -ForegroundColor Cyan
Write-Host "   $invite"
Write-Host "Make more with: $Exe --config `"$Toml`" invite --kind system|person"
Done 0
