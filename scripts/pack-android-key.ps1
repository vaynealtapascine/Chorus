# Turn an existing Android keystore (.jks or .p12) into the one value Infisical keeps as
# ANDROID_SIGNING_KEY (D-072). The owner runs this; nothing is written to disk.
#
#   pwsh scripts/pack-android-key.ps1 path\to\release.jks
#
# Asks for the keystore password (and the key's, if different), checks them with keytool, then
# puts {"alias", "password", "key_password", "type", "keystore": <base64>} on the clipboard.
# Paste it into Infisical (project chorus-cec1) as the value of ANDROID_SIGNING_KEY, replacing
# what is there. Keep the .jks and its passwords somewhere safe as well: every future update of
# the published app must be signed with this key.
param([Parameter(Mandatory)][string]$Keystore)
$ErrorActionPreference = 'Stop'

$jbr = 'C:\Program Files\Android\Android Studio\jbr'
$keytool = if (Test-Path "$jbr\bin\keytool.exe") { "$jbr\bin\keytool.exe" }
           elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) { "$env:JAVA_HOME\bin\keytool.exe" }
           else { throw 'no keytool: install Android Studio or set JAVA_HOME to a JDK' }
$path = (Resolve-Path $Keystore).Path
$bytes = [IO.File]::ReadAllBytes($path)
$type = if ($bytes.Length -ge 4 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xED -and $bytes[2] -eq 0xFE -and $bytes[3] -eq 0xED) { 'jks' } else { 'pkcs12' }

function Plain([securestring]$s) { [Net.NetworkCredential]::new('', $s).Password }
$password = Plain (Read-Host 'Keystore password' -AsSecureString)
$list = & $keytool -list -storetype $type -keystore $path -storepass $password 2>&1
if ($LASTEXITCODE) { throw "keytool couldn't open the keystore (wrong password?): $($list | Select-Object -Last 1)" }
$aliases = @($list | Select-String '^([^,]+), .*PrivateKeyEntry' | ForEach-Object { $_.Matches[0].Groups[1].Value })
if ($aliases.Count -eq 0) { throw 'no private key in this keystore' }
$alias = if ($aliases.Count -eq 1) { $aliases[0] } else {
    Write-Host "Keys in it: $($aliases -join ', ')"
    Read-Host 'Which alias signs Chorus'
}
$keyPassword = Plain (Read-Host 'Key password (Enter if the same as the keystore password)' -AsSecureString)
if (-not $keyPassword) { $keyPassword = $password }
# a certificate request needs the private key, so it proves the key password
$check = & $keytool -certreq -storetype $type -keystore $path -storepass $password -alias $alias -keypass $keyPassword 2>&1
if ($LASTEXITCODE) { throw "keytool couldn't use key '$alias' (wrong key password?): $($check | Select-Object -Last 1)" }

$json = [ordered]@{ alias = $alias; password = $password; key_password = $keyPassword; type = $type; keystore = [Convert]::ToBase64String($bytes) } |
    ConvertTo-Json -Compress
Set-Clipboard -Value $json
$fp = (& $keytool -list -v -storetype $type -keystore $path -storepass $password -alias $alias) -match 'SHA256:'
Write-Host "Copied to the clipboard ($type, alias '$alias')."
Write-Host "Certificate $($fp[0].Trim())"
Write-Host 'Paste it into Infisical as the value of ANDROID_SIGNING_KEY, then clear the clipboard (copy something else).'
