param([Parameter(Mandatory=$true)][string]$WorkspaceRoot)
$ErrorActionPreference='Stop'
$mobile=Split-Path $PSScriptRoot -Parent
$jdk=(Get-ChildItem "$WorkspaceRoot/tools/jdk21" -Directory|Select-Object -First 1).FullName
$assets="$mobile/android/kronos/src/main/assets/jvm"
$build="$mobile/kronos-bridge/build/chat-routing-tests";New-Item -ItemType Directory -Force $build|Out-Null
$native=[IO.File]::ReadAllText("$mobile/kronos-bridge/build/generated/net/runelite/standalone/MusicPatchNode.java")
$old='if(var9.noClickThrough) {';$replacement='if(var9.noClickThrough && MobileLayout.blocksNativeInput(var9, MouseHandler.MouseHandler_x, MouseHandler.MouseHandler_y)) {'
if($native.Contains($old)){$native=$native.Replace($old,$replacement)}elseif(!$native.Contains($replacement)){throw 'Native widget blocker hook missing'}
$old='if(var30.type == 0 && MouseHandler';$replacement='if(var30.type == 0 && MobileLayout.blocksNativeInput(var9, MouseHandler.MouseHandler_x, MouseHandler.MouseHandler_y) && MouseHandler'
if($native.Contains($old)){$native=$native.Replace($old,$replacement)}elseif(!$native.Contains($replacement)){throw 'Native hosted blocker hook missing'}
[IO.File]::WriteAllText("$build/MusicPatchNode.java",$native,[Text.UTF8Encoding]::new($false))
$sources=@("$build/MusicPatchNode.java","$mobile/kronos-bridge/src/net/runelite/standalone/MobileChat.java","$mobile/kronos-bridge/src/net/runelite/standalone/MobileLayout.java","$mobile/kronos-bridge/src/net/runelite/standalone/MobileUi.java","$mobile/android/kronos/src/main/java/local/kronos/mobile/TouchRegions.java","$mobile/android/kronos/src/main/java/local/kronos/mobile/TouchControls.java","$PSScriptRoot/MobileChatRoutingTest.java")
& "$jdk/bin/javac.exe" -J-Xmx256m -J-XX:+UseSerialGC --release 8 -encoding UTF-8 -cp "$assets/bridge.jar;$assets/client.jar" -d $build @sources
if($LASTEXITCODE){throw 'Chat regression compilation failed'}
& "$jdk/bin/java.exe" -Xmx128m -XX:+UseSerialGC "-Duser.home=$build/home" -cp "$build;$assets/bridge.jar;$assets/client.jar" net.runelite.standalone.MobileChatRoutingTest
if($LASTEXITCODE){throw 'Chat regression failed'}
& "$jdk/bin/javac.exe" -J-Xmx128m --release 8 -encoding UTF-8 -cp $build -d $build "$mobile/shared/src/local/kronos/ui/MobileGeometry.java" "$mobile/android/kronos/src/main/java/local/kronos/mobile/ScreenLayout.java" "$mobile/android/kronos/src/main/java/local/kronos/mobile/LayoutDrag.java" "$PSScriptRoot/MobileControlsTest.java" "$PSScriptRoot/LayoutCustomizationTest.java"
if($LASTEXITCODE){throw 'Existing control regression compilation failed'}
foreach($test in @('MobileControlsTest','LayoutCustomizationTest')){& "$jdk/bin/java.exe" -Xmx64m -cp $build $test;if($LASTEXITCODE){throw "Existing regression failed: $test"}}
