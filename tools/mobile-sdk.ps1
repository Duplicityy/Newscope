param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Packages)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$env:JAVA_HOME = Join-Path $root 'tools/intellij/jbr'
$env:ANDROID_USER_HOME = Join-Path $root 'tools/android-user'
$env:ANDROID_HOME = Join-Path $root 'tools/android-sdk'
New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME | Out-Null
& "$env:ANDROID_HOME/cmdline-tools/bin/sdkmanager.bat" "--sdk_root=$env:ANDROID_HOME" @Packages
exit $LASTEXITCODE
