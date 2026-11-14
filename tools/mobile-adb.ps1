param([Parameter(ValueFromRemainingArguments=$true)][string[]]$AdbArguments)
$root = Split-Path $PSScriptRoot
$env:ANDROID_USER_HOME = Join-Path $root 'tools/android-user'
$env:ANDROID_SDK_HOME = Join-Path $root 'tools/android-user'
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null
& "$root/tools/android-sdk/platform-tools/adb.exe" @AdbArguments
exit $LASTEXITCODE
