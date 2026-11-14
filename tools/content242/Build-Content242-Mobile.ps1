param(
 [switch]$StageOnly,
 [string]$WorkspaceRoot=(Resolve-Path "$PSScriptRoot/../../../../..").Path,
 [string]$MobileRoot='C:\K242-Mobile-QA',
 [string]$ClientRoot=(Resolve-Path "$PSScriptRoot/../..").Path,
 [string]$ExpectedClientSha256='54f3a2a594303455992e279f2ff634a902a10354140ceffbfd5b2251dc7c7ba2'
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($WorkspaceRoot)
$mobile=(Resolve-Path -LiteralPath $MobileRoot).Path
$clientRoot=(Resolve-Path -LiteralPath $ClientRoot).Path
$release=Join-Path $root 'local-runtime/content242-mobile-release'
if($mobile -eq (Join-Path $root 'kronos-mobile')){throw 'Original mobile checkout is forbidden'}
if((& git -C $mobile branch --show-current).Trim() -ne 'codex/content242-mobile-isolated'){throw 'Isolated mobile branch required'}
New-Item -ItemType Directory -Force $release|Out-Null
$sdk=Join-Path $root 'tools/android-sdk'
$jdk=(Get-ChildItem "$root/tools/jdk21" -Directory | Select-Object -First 1).FullName
$assets=Join-Path $mobile 'android/kronos/src/main/assets/jvm'
$classes=Join-Path $mobile ('kronos-bridge/build/classes-content242-'+[guid]::NewGuid().ToString('N'))
$env:JAVA_HOME=$jdk
$env:ANDROID_HOME=$sdk
$env:ANDROID_USER_HOME=Join-Path $root 'tools/android-user'
$env:GRADLE_USER_HOME=Join-Path $root '.gradle-home-mobile'
New-Item -ItemType Directory -Force $assets,$classes,$env:ANDROID_USER_HOME | Out-Null
function Check-Hash($file,$expected) {if((Get-FileHash $file -Algorithm SHA256).Hash.ToLower() -ne $expected){throw "Checksum mismatch: $file"}}
$snapshot=Get-Content -LiteralPath "$release/source-snapshot.json" -Raw|ConvertFrom-Json
$deltaReview=Get-Content -LiteralPath "$release/source-delta-review.json" -Raw|ConvertFrom-Json
if($deltaReview.scope -ne 'content242-mobile-chat-fix' -or !$deltaReview.reviewed){throw 'An explicit mobile source delta review is required'}
$sourceDeltas=@{}
foreach($entry in $deltaReview.files){
    if($sourceDeltas.ContainsKey($entry.path) -or $entry.path -match '(^|/)\.\.(/|$)|^[A-Za-z]:|^[/\\]'){throw 'Invalid mobile source delta path'}
    $sourceDeltas[$entry.path]=$entry
}
function Verify-MobileSources {
    $known=@{}
    foreach($entry in $snapshot.files){
        Check-Hash (Join-Path $snapshot.source $entry.path) $entry.sha256
        $known[$entry.path]=$true
        if($sourceDeltas.ContainsKey($entry.path)){
            $delta=$sourceDeltas[$entry.path]
            if($delta.beforeSha256 -ne $entry.sha256){throw 'Source delta does not match preserved mobile baseline'}
            Check-Hash (Join-Path $mobile $entry.path) $delta.afterSha256
        }else{Check-Hash (Join-Path $mobile $entry.path) $entry.sha256}
    }
    foreach($entry in $deltaReview.files){
        if(!$known.ContainsKey($entry.path)){
            if($entry.beforeSha256 -or (Test-Path -LiteralPath (Join-Path $snapshot.source $entry.path))){throw 'New mobile source unexpectedly exists in original checkout'}
            Check-Hash (Join-Path $mobile $entry.path) $entry.afterSha256
        }
    }
}
Verify-MobileSources
Check-Hash "$root/tools/downloads/jre21-android-arm64.tar.xz" 'db7862e4ec68a71a46cf1fec078938443410805cb61e8f99b75f10893c708c9a'
Check-Hash "$root/tools/downloads/cacio-tta-1.18.jar" '2dc226227d86af25d2ae9dbd87aa4e210c86d734c1d7775f80359b6474805228'
Check-Hash "$root/tools/downloads/cacio-shared-1.18.jar" '6f3a26dfaba0d0287d97922ef316b751db06d93f183e8fa274ddec53b74bd845'
if(!(Test-Path "$assets/jre/.extracted")) {
    New-Item -ItemType Directory -Force "$assets/jre" | Out-Null
    & tar.exe -xf "$root/tools/downloads/jre21-android-arm64.tar.xz" -C "$assets/jre" --exclude './legal/*/LICENSE' --exclude './legal/*/ASSEMBLY_EXCEPTION' --exclude './legal/*/ADDITIONAL_LICENSE_INFO'
    if($LASTEXITCODE){throw 'JRE extraction failed'}
    & tar.exe -xf "$root/tools/downloads/jre21-android-arm64.tar.xz" -C "$assets/jre" './legal/java.base/LICENSE' './legal/java.base/ASSEMBLY_EXCEPTION' './legal/java.base/ADDITIONAL_LICENSE_INFO'
    if($LASTEXITCODE){throw 'JRE license extraction failed'}
    Get-ChildItem "$assets/jre/legal" -Directory | Where-Object Name -ne 'java.base' | ForEach-Object { Copy-Item "$assets/jre/legal/java.base/LICENSE","$assets/jre/legal/java.base/ASSEMBLY_EXCEPTION","$assets/jre/legal/java.base/ADDITIONAL_LICENSE_INFO" $_.FullName -Force }
    Set-Content "$assets/jre/.extracted" 'SHA-verified runtime; legal symlinks materialized for Windows and Android assets.'
}
$clang=Join-Path $sdk 'ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
& $clang --target=aarch64-linux-android28 -shared -fPIC -O2 -o "$assets/jre/lib/libawt_xawt.so" "$mobile/android/dev-tools/fake-xawt/xawt_fake.c" '-Wl,-soname,libawt_xawt.so' '-Wl,-z,max-page-size=16384' "-L$assets/jre/lib" "-L$assets/jre/lib/server" '-Wl,--no-as-needed' -lawt_headless '-Wl,--allow-shlib-undefined'
if($LASTEXITCODE){throw 'AWT native support build failed'}
$client=Join-Path $clientRoot 'runelite/runelite-client/build/libs/runelite-client-1.5.37-SNAPSHOT-shaded.jar'
Check-Hash $client $ExpectedClientSha256
$originalApk=Join-Path $root 'mobile-release/Kronos-Mobile.apk'
$originalApkHash=if(Test-Path -LiteralPath $originalApk){(Get-FileHash -LiteralPath $originalApk -Algorithm SHA256).Hash}else{$null}
Copy-Item $client "$assets/client.jar" -Force
Copy-Item "$root/tools/downloads/cacio-tta-1.18.jar","$root/tools/downloads/cacio-shared-1.18.jar" $assets -Force
# Compile a mobile-only Client overlay from the original source. Fail if the upstream
# login block changes; the desktop source/jar remains untouched.
$generated=Join-Path $mobile 'kronos-bridge/build/generated/net/runelite/standalone'
New-Item -ItemType Directory -Force $generated | Out-Null
$clientSource=[IO.File]::ReadAllText("$clientRoot/runelite/runelite-client/src/main/java/net/runelite/standalone/Client.java")
$macPattern='InetAddress ip = InetAddress\.getLocalHost\(\);\s+NetworkInterface network = NetworkInterface\.getByInetAddress\(ip\);\s+byte\[\] macAddress = network\.getHardwareAddress\(\);'
if([regex]::Matches($clientSource,$macPattern).Count -ne 1){throw 'Mobile login patch no longer matches upstream Client'}
$clientSource=[regex]::Replace($clientSource,$macPattern,'byte[] macAddress = MobileIdentity.address();')
$uuidCall='var5.packetBuffer.writeString(field10222);'
if(!$clientSource.Contains($uuidCall)){throw 'Mobile UUID patch no longer matches upstream Client'}
$clientSource=$clientSource.Replace($uuidCall,'var5.packetBuffer.writeString(MobileIdentity.uuid(field10222));')
$gameSocket='GameShell.taskHandler.method3418(class158.worldHost, 13302)'
if(!$clientSource.Contains($gameSocket)){throw 'Mobile test connection hook no longer matches'}
$clientSource=$clientSource.Replace($gameSocket,'GameShell.taskHandler.method3418(class158.worldHost, Boolean.getBoolean("kronos.desktopTest") && "127.0.0.1".equals(class158.worldHost) ? Integer.getInteger("kronos.testGamePort", 13302) : 13302)')
$mouseSample='            VertexNormal.method2465();'
if(!$clientSource.Contains($mouseSample)){throw 'Mobile mouse sampling hook no longer matches'}
$clientSource=$clientSource.Replace($mouseSample,$mouseSample+[Environment]::NewLine+'            MobileTap.poll();')
$inputAnchor='                     AbstractWorldMapData.method3325(rootInterface, 0, 0, FloorDecoration.canvasWidth, WallDecoration.canvasHeight, 0, 0);'
if(!$clientSource.Contains($inputAnchor)){throw 'Mobile widget input hook no longer matches'}
$clientSource=$clientSource.Replace($inputAnchor,'                     WorldMapData_0.method171(rootInterface);'+[Environment]::NewLine+'                     MobileLayout.apply();'+[Environment]::NewLine+'                     MobileMap.update();'+[Environment]::NewLine+'                     MobileTap.validatePress();'+[Environment]::NewLine+$inputAnchor)
[IO.File]::WriteAllText("$generated/Client.java",$clientSource)
$sources=@(Get-ChildItem "$mobile/kronos-bridge/src" -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+=@(Get-ChildItem "$mobile/shared/src" -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources+="$generated/Client.java"
# Use the same layer order in the native renderer and input traversal.
# Leave the actual indexed widget arrays and the desktop client intact.
foreach($layer in @(
    @{File='ScriptEvent.java'; Anchor='Widget var10 = var0[var9];'; Replacement='Widget var10 = var0[MobileLayout.widgetIndex(var0, var1, var9)];'},
    @{File='MusicPatchNode.java'; Anchor='Widget var9 = var0[var8];'; Replacement='Widget var9 = var0[MobileLayout.widgetIndex(var0, var1, var8)];'}
)){
    $source=[IO.File]::ReadAllText("$clientRoot/runelite/runelite-client/src/main/java/net/runelite/standalone/$($layer.File)")
    if([regex]::Matches($source,[regex]::Escape($layer.Anchor)).Count -ne 1){throw "Mobile layer hook no longer matches $($layer.File)"}
    $source=$source.Replace($layer.Anchor,$layer.Replacement)
    if($layer.File -eq 'ScriptEvent.java'){
        $scene='Friend.method5176(var12, var13, var10.width, var10.height);'
        if(!$source.Contains($scene)){throw 'Mobile scene picking hook no longer matches'}
        $source=$source.Replace($scene,$scene+[Environment]::NewLine+'                        MobileTap.sceneDrawn();')
        $mapDraw='Tiles.worldMap.method5913(var12, var13, var10.width, var10.height, Client.cycle);'
        if(!$source.Contains($mapDraw)){throw 'Mobile world map drawing hook no longer matches'}
        $source=$source.Replace($mapDraw,'var10.noClickThrough = true;'+[Environment]::NewLine+'                        MobileMap.draw(var12, var13, var10.width, var10.height, Client.cycle);')
    }else{
        foreach($chatHook in @(
            @{Anchor='if(var9.noClickThrough) {';Replacement='if(var9.noClickThrough && MobileLayout.blocksNativeInput(var9, MouseHandler.MouseHandler_x, MouseHandler.MouseHandler_y)) {'},
            @{Anchor='if(var30.type == 0 && MouseHandler';Replacement='if(var30.type == 0 && MobileLayout.blocksNativeInput(var9, MouseHandler.MouseHandler_x, MouseHandler.MouseHandler_y) && MouseHandler'}
        )){
            if([regex]::Matches($source,[regex]::Escape($chatHook.Anchor)).Count -ne 1){throw 'Mobile native chat traversal hook no longer matches'}
            $source=$source.Replace($chatHook.Anchor,$chatHook.Replacement)
        }
        $pick='if(!Client.isLoading && !Client.isMenuOpen && var33) {'
        if(!$source.Contains($pick)){throw 'Mobile target picking guard no longer matches'}
        $source=$source.Replace($pick,'if(!Client.isLoading && !Client.isMenuOpen && var33 && MobileTap.canUseWorldPick()) {')
        $source=$source.Replace('if(var9.contentType == 1400) {','if(var9.contentType == 1400 && MobileMap.atlasInteractive()) {')
    }
    $path=Join-Path $generated $layer.File
    [IO.File]::WriteAllText($path,$source)
    $sources+=$path
}
$loginSource=[IO.File]::ReadAllText("$clientRoot/runelite/runelite-client/src/main/java/net/runelite/standalone/LoginPacket.java")
$oldRegister='ArchiveDiskActionHandler.method4341("https://community.kronos.rip/index.php?register/", true, false);'
if(!$loginSource.Contains($oldRegister)){throw 'Mobile registration button patch no longer matches'}
$loginSource=$loginSource.Replace($oldRegister,'TilePaint.method2911(true); Login.Login_username = ""; Login.currentLoginField = 0; Login.Login_response2 = "New names create an account on login.";')
[IO.File]::WriteAllText("$generated/LoginPacket.java",$loginSource)
$sources+="$generated/LoginPacket.java"
$bankSource=[IO.File]::ReadAllText("$clientRoot/runelite/runelite-client/src/main/java/net/runelite/client/plugins/bank/ContainerCalculation.java")
$bankAnchor='// Returns last calculation if inventory hasn''t changed'
if(!$bankSource.Contains($bankAnchor)){throw 'Mobile bank calculation hook no longer matches'}
$bankSource=$bankSource.Replace($bankAnchor,'if (items == null) return null;'+[Environment]::NewLine+"`t`t"+$bankAnchor)
$bankGenerated=Join-Path $mobile 'kronos-bridge/build/generated/net/runelite/client/plugins/bank'
New-Item -ItemType Directory -Force $bankGenerated | Out-Null
[IO.File]::WriteAllText("$bankGenerated/ContainerCalculation.java",$bankSource)
$sources+="$bankGenerated/ContainerCalculation.java"
$sources+="$mobile/ios/dev-tools/awtsmoke/UnsafePokeActivation.java"
$sources+="$mobile/ios/dev-tools/awtsmoke/AwtActivation.java"
& "$jdk/bin/javac.exe" -J-Xmx384m -J-XX:+UseSerialGC --release 8 -encoding UTF-8 -cp "$assets/client.jar;$assets/cacio-tta-1.18.jar;$assets/cacio-shared-1.18.jar" -d $classes @sources
if($LASTEXITCODE){throw 'Java bridge compilation failed'}
$jarTool=(Get-ChildItem "$root/tools/jdk8" -Directory | Select-Object -First 1).FullName + '/bin/jar.exe'
& $jarTool cf "$assets/bridge.jar" -C $classes .
if($LASTEXITCODE){throw 'Java bridge packaging failed'}
$hashes=Get-ChildItem $assets -File -Filter '*.jar' | Sort-Object Name | Get-FileHash -Algorithm SHA256
(($hashes.Hash -join '-') + '-' + (Get-FileHash "$assets/jre/lib/libawt_xawt.so").Hash + '-db7862e4ec68a71a46cf1fec078938443410805cb61e8f99b75f10893c708c9a') | Set-Content "$assets/runtime.tag" -Encoding ascii
Copy-Item "$mobile/LICENSE" "$assets/RuneMobile-LICENSE.txt" -Force
Copy-Item "$mobile/THIRD-PARTY-NOTICES.md" "$assets/THIRD-PARTY-NOTICES.md" -Force
"sdk.dir=$($sdk.Replace('\','/'))" | Set-Content "$mobile/android/local.properties" -Encoding ascii
if($StageOnly){Write-Output 'Mobile runtime and Kronos bridge staged.';exit 0}
Push-Location "$mobile/android"
try { & ./gradlew.bat :kronos:assembleDebug --offline --no-daemon --max-workers=1 --console=plain '-Dorg.gradle.jvmargs=-Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8'; if($LASTEXITCODE){throw 'APK build failed'} }
finally {Pop-Location}
New-Item -ItemType Directory -Force "$release" | Out-Null
Copy-Item "$mobile/android/kronos/build/outputs/apk/debug/kronos-debug.apk" "$release/Kronos-Mobile-242-staged.apk" -Force
Get-FileHash "$release/Kronos-Mobile-242-staged.apk" -Algorithm SHA256

Check-Hash $client $ExpectedClientSha256
Check-Hash "$assets/client.jar" $ExpectedClientSha256
if($originalApkHash -and (Get-FileHash -LiteralPath $originalApk -Algorithm SHA256).Hash -ne $originalApkHash){throw 'Existing mobile release unexpectedly changed'}
Verify-MobileSources
@{builtUtc=[DateTime]::UtcNow.ToString('o');mobile=$mobile;clientSource=$clientRoot;clientSha256=$ExpectedClientSha256;bridgeSha256=(Get-FileHash "$assets/bridge.jar" -Algorithm SHA256).Hash.ToLowerInvariant();apkSha256=(Get-FileHash "$release/Kronos-Mobile-242-staged.apk" -Algorithm SHA256).Hash.ToLowerInvariant();originalApkUnchanged=$true;originalSourceSnapshotUnchanged=$true;sourceDeltaReviewSha256=(Get-FileHash "$release/source-delta-review.json" -Algorithm SHA256).Hash.ToLowerInvariant();changedSources=$deltaReview.files;applicationId='local.kronos.mobile';endpoint='Original current mobile endpoint/preferences retained';signing='Existing private Android debug signing identity; key not copied into release';scope='Build only; no handset execution. Authorized chat input ownership fix; original visual appearance retained.'}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath "$release/build-receipt.json" -Encoding utf8
