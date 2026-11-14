param([switch]$DialogTest)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$env:JAVA_HOME=(Get-ChildItem "$root/tools/jdk21" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME=Join-Path $root 'tools/android-sdk'
$env:ANDROID_USER_HOME=Join-Path $root 'tools/android-user'
$env:GRADLE_USER_HOME=Join-Path $root '.gradle-home-mobile'
$apk=Join-Path $root 'mobile-release/Kronos-Mobile.apk'
& "$env:ANDROID_HOME/build-tools/35.0.0/apksigner.bat" verify --verbose --print-certs $apk
if($LASTEXITCODE){throw 'APK signature verification failed'}
$badging=& "$env:ANDROID_HOME/build-tools/35.0.0/aapt.exe" dump badging $apk
if($LASTEXITCODE -ne 0){throw 'APK metadata verification failed'}
$badging | Select-String 'package:|sdkVersion:|targetSdkVersion:|native-code:|application-label:|launchable-activity:'
$version=[regex]::Match(($badging -join "\n"),"versionName='([^']+)'").Groups[1].Value
Push-Location "$root/kronos-mobile/android"
try{ & ./gradlew.bat :kronos:lintDebug --no-daemon --max-workers=2 --console=plain; if($LASTEXITCODE){throw 'Android lint failed'} }
finally{Pop-Location}
$file=Get-Item $apk
$harness=if($DialogTest){'mobile-dialog-harness'}else{'mobile-test'}
$log=Get-Content "$root/local-runtime/$harness/harness.log" -Raw
$verification=[ordered]@{file=$file.Name;bytes=$file.Length;sha256=(Get-FileHash $apk).Hash.ToLower();package='local.kronos.mobile';version=$version;abi='arm64-v8a';minAndroid=9;targetAndroid=15;signatureVerified=$true;lintPassed=$true;offscreenLoginTest=$log.Contains('PASS: revision 184 login screen rendered');mobileInputRemoteLogin=$log.Contains('PASS: fresh regular account authenticated using corrected native-editor password');adaptiveLayoutAndEditing=$log.Contains('PASS: five landscape layouts and classic/mobile restoration.');layoutCustomization=$log.Contains('PASS: moved functional buttons/panels, size, opacity, save, cancel and reset.');teleportWithChatOpenAndMinimized=$log.Contains('PASS: real teleport category, destination and confirmation taps with chat open and minimized.');centerCameraAndNativeFixedChat=$log.Contains('PASS: camera at the viewport center, native fixed chat, and fullscreen restoration.');physicalDeviceTest='This build awaits a physical Android device retest.'}
$verification.localMapAndBankerTap=$log.Contains('PASS: local map at custom home and fresh banker targeting.')
$verification.frameTransferAndDeduplication=$log.Contains('PASS: reusable Android frame transfer and duplicate-frame suppression.')
$verification.nativeWalkCross=$log.Contains('PASS: native yellow walk cross follows both ground taps.')
$verification | ConvertTo-Json | Set-Content "$root/mobile-release/verification.json"
$verification | ConvertTo-Json
