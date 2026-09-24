# Make Chorus's Android release key (D-072, Q16), once. The owner runs this, not an agent.
#
#   pwsh scripts/new-android-key.ps1 [-Out chorus-android-signing.json]
#
# Writes one JSON value, {"alias", "password", "keystore": <base64 PKCS12>}, to paste into
# Infisical (project chorus-cec1) as the secret ANDROID_SIGNING_KEY; .github/workflows/
# release-android.yml reads it from there. Keep a second copy somewhere safe and offline (a
# password manager): every future update of the published app must be signed with this key, and
# if it is lost, everyone who installed Chorus from GitHub has to uninstall and reinstall.
# Then delete the file this script wrote.
param([string]$Out = 'chorus-android-signing.json')
$ErrorActionPreference = 'Stop'

if (Test-Path $Out) { throw "$Out already exists; a second key would not update apps signed with the first" }
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
$keytool = if (Test-Path "$jbr\bin\keytool.exe") { "$jbr\bin\keytool.exe" }
           elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) { "$env:JAVA_HOME\bin\keytool.exe" }
           else { throw 'no keytool: install Android Studio or set JAVA_HOME to a JDK' }

$bytes = [byte[]]::new(24)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$password = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$tmp = Join-Path ([IO.Path]::GetTempPath()) "chorus-release-$([guid]::NewGuid()).p12"
try {
    # PKCS12 has one password for the store and the key; RSA 4096, valid ~100 years
    & $keytool -genkeypair -storetype PKCS12 -keystore $tmp -storepass $password -alias chorus `
        -keyalg RSA -keysize 4096 -validity 36500 -dname 'CN=Chorus, O=Chorus' | Out-Null
    if ($LASTEXITCODE) { throw 'keytool failed' }
    $json = [ordered]@{ alias = 'chorus'; password = $password; keystore = [Convert]::ToBase64String([IO.File]::ReadAllBytes($tmp)) } |
        ConvertTo-Json -Compress
    Set-Content -Path $Out -Value $json -NoNewline -Encoding utf8NoBOM
    $fp = (& $keytool -list -v -storetype PKCS12 -keystore $tmp -storepass $password -alias chorus) -match 'SHA256:'
    Write-Host "Wrote $Out"
    Write-Host "Certificate $($fp[0].Trim())"
    Write-Host 'Next: paste the whole file into Infisical as ANDROID_SIGNING_KEY, keep an offline copy, delete the file.'
} finally {
    Remove-Item $tmp -ErrorAction SilentlyContinue
}
