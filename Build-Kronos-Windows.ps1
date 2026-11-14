$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$source=Join-Path $root 'windows-client'
$build=Join-Path $source 'build'
$folder=Join-Path $root 'windows-release/Kronos-Windows'
if(Test-Path $folder){throw 'Release folder already exists; preserve it and choose a new output before rebuilding.'}
$jdk=(Get-ChildItem "$root/tools/jdk21" -Directory | Select-Object -First 1).FullName
$jdk8=(Get-ChildItem "$root/tools/jdk8" -Directory | Select-Object -First 1).FullName
$clientRoot=Join-Path $root 'kronos-osrs-184/Kronos-master/runelite'
$jar=Join-Path $clientRoot 'runelite-client/build/libs/runelite-client-1.5.37-SNAPSHOT-shaded.jar'
New-Item -ItemType Directory -Force "$build/classes","$build/generated/net/runelite/standalone","$folder/app","$folder/licenses" | Out-Null
# Only client classes are overlaid. No mobile layout, server, account or profile data.
$client=[IO.File]::ReadAllText("$clientRoot/runelite-client/src/main/java/net/runelite/standalone/Client.java")
$pattern='InetAddress ip = InetAddress\.getLocalHost\(\);\s+NetworkInterface network = NetworkInterface\.getByInetAddress\(ip\);\s+byte\[\] macAddress = network\.getHardwareAddress\(\);'
if([regex]::Matches($client,$pattern).Count -ne 1){throw 'Client identity anchor changed'}
$client=[regex]::Replace($client,$pattern,'byte[] macAddress = DesktopIdentity.address();')
$client=$client.Replace('var5.packetBuffer.writeString(field10222);','var5.packetBuffer.writeString(DesktopIdentity.uuid(field10222));')
[IO.File]::WriteAllText("$build/generated/net/runelite/standalone/Client.java",$client)
$login=[IO.File]::ReadAllText("$clientRoot/runelite-client/src/main/java/net/runelite/standalone/LoginPacket.java")
$anchor='ArchiveDiskActionHandler.method4341("https://community.kronos.rip/index.php?register/", true, false);'
if(!$login.Contains($anchor)){throw 'Registration anchor changed'}
$login=$login.Replace($anchor,'TilePaint.method2911(true); Login.Login_username = ""; Login.currentLoginField = 0; Login.Login_response2 = "New names create an account on login.";')
[IO.File]::WriteAllText("$build/generated/net/runelite/standalone/LoginPacket.java",$login)
$sources=@(Get-ChildItem "$source/src","$build/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$jdk/bin/javac.exe" --release 8 -encoding UTF-8 -cp $jar -d "$build/classes" @sources
if($LASTEXITCODE){throw 'Windows overlay compilation failed'}
& "$jdk8/bin/jar.exe" cf "$folder/app/windows-support.jar" -C "$build/classes" .
if($LASTEXITCODE){throw 'Windows support packaging failed'}
Copy-Item -LiteralPath $jar -Destination "$folder/app/client.jar"
Copy-Item -LiteralPath "$jdk8/jre" -Destination "$folder/runtime" -Recurse
Copy-Item -LiteralPath "$clientRoot/LICENSE" -Destination "$folder/licenses/RuneLite-LICENSE.txt"
$launcher=@"
@echo off
setlocal
cd /d "%~dp0"
if not exist "%LOCALAPPDATA%\Kronos-Windows" mkdir "%LOCALAPPDATA%\Kronos-Windows"
start "" "%~dp0runtime\bin\javaw.exe" -Xmx1g -Dsun.java2d.d3d=false "-Duser.home=%LOCALAPPDATA%\Kronos-Windows" -Dkronos.host=127.0.0.1 -cp "%~dp0app\windows-support.jar;%~dp0app\client.jar" net.runelite.client.RuneLite --no-splash
endlocal
"@
[IO.File]::WriteAllText("$folder/Play Kronos.cmd",$launcher,[Text.Encoding]::ASCII)
$readme=@"
KRONOS FOR WINDOWS - portable desktop client

1. Extract the entire ZIP first (right-click > Extract All).
2. Open the Kronos-Windows folder and double-click Play Kronos.cmd.
3. Choose Existing User to log in, or New User to create an account.
   An unused game username and password creates a regular account on first login.
   Use that same password on later logins. This is a game account, not your Windows sign-in.

No separate Java installation, administrator access, or server setup is needed.
Windows 10/11, 64-bit Intel/AMD PC. Approximately 300 MB free space plus downloaded game cache.
First startup downloads the game cache and may take a little longer.
Server: 127.0.0.1 (game TCP 13302; cache TCP 7304).

Your settings, cache and logs are stored under:
  %LOCALAPPDATA%\Kronos-Windows
They are separate from this shareable client folder. No account is prefilled.
To share: send the original ZIP or this original folder, not your profile directory.
If the game fails to open, check %LOCALAPPDATA%\Kronos-Windows\.runelite\logs\client.log.

CONTENTS
Play Kronos.cmd        launcher
app/client.jar         RuneLite-based revision-184 client
app/windows-support.jar server connection and first-login compatibility
runtime/               Eclipse Temurin OpenJDK 8 Windows x64 runtime, including license files
licenses/              client license

No server code, server configuration, owner credentials, account database, player saves,
private signing keys, or existing client profiles are included.
"@
[IO.File]::WriteAllText("$folder/READ ME.txt",$readme,[Text.Encoding]::UTF8)
Write-Output "Windows folder prepared: $folder"