param(
    [Parameter(Mandatory)][string]$Workspace,
    [string]$Python = "$env:LOCALAPPDATA/../../.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe"
)
$ErrorActionPreference = 'Stop'
$out = $PSScriptRoot.Replace('\','/')
$stage = Split-Path $out -Parent
$android = "$Workspace/tools/android-sdk/build-tools/35.0.0"
$env:JAVA_HOME = "$Workspace/tools/jdk21/jdk-21.0.13+9"
$baseline = "$stage/Newscape-Mobile-Ultra-Expedition-v46-STAGED.apk"
$client = "$out/android-release/client.jar"
$unsigned = "$out/android-release/lobby-unsigned.apk"
$aligned = "$out/android-release/lobby-aligned.apk"
$signed = "$out/Newscape-Mobile-Ultra-Expedition-Lobby-v46-STAGED.apk"

& $Python "$out/package_apk.py" build $baseline $client $unsigned
if ($LASTEXITCODE) { throw 'APK packaging failed' }
& "$android/zipalign.exe" -f -P 16 4 $unsigned $aligned
if ($LASTEXITCODE) { throw 'APK alignment failed' }

$signing = Get-Content -LiteralPath 'D:/Newscape-Owner-Keys/desktop-test-20261117/signing-private.json' -Raw | ConvertFrom-Json
try {
    $env:EXPEDITION_LOBBY_STORE_PASSWORD = $signing.storePassword
    $env:EXPEDITION_LOBBY_KEY_PASSWORD = $signing.keyPassword
    & "$android/apksigner.bat" sign --ks $signing.storeFile --ks-key-alias $signing.keyAlias --ks-pass env:EXPEDITION_LOBBY_STORE_PASSWORD --key-pass env:EXPEDITION_LOBBY_KEY_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out $signed $aligned
    if ($LASTEXITCODE) { throw 'APK signing failed' }
} finally {
    Remove-Item Env:EXPEDITION_LOBBY_STORE_PASSWORD,Env:EXPEDITION_LOBBY_KEY_PASSWORD -ErrorAction SilentlyContinue
    $signing = $null
}

$report = & "$android/apksigner.bat" verify --verbose --print-certs $signed
if ($LASTEXITCODE -ne 0 -or !($report -match 'certificate SHA-256 digest: b4ce4bedf30f79524c0c394428e077514a51c2ae2259748a6a2f8599b3aa479a')) {
    throw 'Unexpected private owner APK certificate'
}
$report | Set-Content "$out/checks/apk-signature.txt"
& "$android/zipalign.exe" -c -P 16 4 $signed
if ($LASTEXITCODE) { throw 'Signed APK failed the alignment check' }
& $Python "$out/package_apk.py" verify $baseline $client $signed | Tee-Object -FilePath "$out/checks/apk-payload.txt"
if ($LASTEXITCODE) { throw 'Signed APK changed more than the expected client assets' }
& "$android/aapt.exe" dump badging $signed | Select-String '^package:|^native-code:' | Set-Content "$out/checks/apk-manifest.txt"
if ($LASTEXITCODE) { throw 'Could not verify the signed APK manifest' }
Get-Content "$out/checks/apk-manifest.txt"
