# Make Chorus invite links (docs/SPEC.md §3.1). Each prints one link per line.
#
#   pwsh scripts/invite.ps1                      one link for a new account
#   pwsh scripts/invite.ps1 -Count 5 -Days 14    five links, each good for two weeks
#   pwsh scripts/invite.ps1 -Uses 10             one link ten people can use
#   pwsh scripts/invite.ps1 -Kind person         same, but "Just me" is preselected
#   pwsh scripts/invite.ps1 -Kind device -Account <account id>
#                                                add a device to an existing account (any of its
#                                                devices can also do this from Settings → Devices)
#
# Whoever opens a system or person link chooses "We're a system" or "Just me" when they join
# (D-093); -Kind only picks which answer starts selected.
#
# Where the server is (D-094): chorus.vayne.garden runs on ns2-up-net-nz, reached over ssh.
#   -Remote root@other-server    another Linux install (deploy/linux, /opt/chorus)
#   -Local [-Target <folder>]    a Windows install on this PC (default ~\selfhost\chorus)
# $env:CHORUS_INVITE_REMOTE, when set, replaces the default server.
param(
    [ValidateSet('system', 'person', 'device')][string]$Kind = 'system',
    [ValidateRange(1, 100)][int]$Count = 1,
    [ValidateRange(1, 365)][int]$Days = 7,
    [ValidateRange(1, 1000)][int]$Uses = 1,
    [string]$Account,
    [string]$Remote = $(if ($env:CHORUS_INVITE_REMOTE) { $env:CHORUS_INVITE_REMOTE } else { 'root@ns2-up-net-nz' }),
    [switch]$Local,
    [string]$Target = (Join-Path $HOME 'selfhost\chorus')
)
$ErrorActionPreference = 'Stop'
if ($Kind -eq 'device' -and -not $Account) { throw '-Kind device needs -Account <account id> (the account the device joins)' }
if ($Account -and $Account -notmatch '^[0-9a-fA-F-]{36}$') { throw "-Account should be an account id (a UUID), not '$Account'" }

$cliArgs = @('invite', '--kind', $Kind, '--days', $Days, '--uses', $Uses)
if ($Account) { $cliArgs += @('--account', $Account) }

for ($i = 0; $i -lt $Count; $i++) {
    if (-not $Local) {
        # as the service user, so the database keeps its owner
        $line = "runuser -u chorus -- /opt/chorus/app/chorus-server --config /etc/chorus/chorus.toml $($cliArgs -join ' ')"
        $out = ssh -o BatchMode=yes $Remote $line
    } else {
        $exe = Join-Path $Target 'app\chorus-server.exe'
        if (-not (Test-Path $exe)) { throw "no Chorus server at $exe (pass -Target)" }
        $out = & $exe --config (Join-Path $Target 'chorus.toml') @cliArgs
    }
    if ($LASTEXITCODE) { throw "chorus-server invite failed (exit $LASTEXITCODE)" }
    # the link is the last line; anything before it is the server's own log
    ($out | Select-Object -Last 1).Trim()
}
