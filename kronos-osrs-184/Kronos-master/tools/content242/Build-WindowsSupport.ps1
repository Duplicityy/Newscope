param([Parameter(Mandatory=$true)][string]$ClientJar,[Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Workspace.ps1"
if(Test-Path -LiteralPath $OutputPath){throw 'Choose a fresh Windows support output directory'}
$out=[IO.Path]::GetFullPath($OutputPath)
$jar=(Resolve-Path -LiteralPath $ClientJar).Path
$source=Join-Path $project 'runelite/runelite-client/src/main/java/net/runelite/standalone'
New-Item -ItemType Directory -Path "$out/classes","$out/generated/net/runelite/standalone" -Force | Out-Null
$client=[IO.File]::ReadAllText("$source/Client.java")
if(!$client.Contains('Content242Capabilities.CACHE_HANDSHAKE_REVISION')){throw 'Extended client source required'}
$pattern='InetAddress ip = InetAddress\.getLocalHost\(\);\s+NetworkInterface network = NetworkInterface\.getByInetAddress\(ip\);\s+byte\[\] macAddress = network\.getHardwareAddress\(\);'
if([regex]::Matches($client,$pattern).Count -ne 1){throw 'Desktop identity anchor changed'}
$client=[regex]::Replace($client,$pattern,'byte[] macAddress = DesktopIdentity.address();')
$client=$client.Replace('var5.packetBuffer.writeString(field10222);','var5.packetBuffer.writeString(DesktopIdentity.uuid(field10222));')
[IO.File]::WriteAllText("$out/generated/net/runelite/standalone/Client.java",$client,[Text.UTF8Encoding]::new($false))
$login=[IO.File]::ReadAllText("$source/LoginPacket.java")
$anchor='ArchiveDiskActionHandler.method4341("https://community.kronos.rip/index.php?register/", true, false);'
if(!$login.Contains($anchor)){throw 'Existing registration anchor changed'}
$login=$login.Replace($anchor,'TilePaint.method2911(true); Login.Login_username = ""; Login.currentLoginField = 0; Login.Login_response2 = "New names create an account on login.";')
[IO.File]::WriteAllText("$out/generated/net/runelite/standalone/LoginPacket.java",$login,[Text.UTF8Encoding]::new($false))
$sources=@(Get-ChildItem (Join-Path $root 'windows-client/src'),"$out/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
& "$jdk/bin/javac.exe" -J-Xmx256m -encoding UTF-8 -cp $jar -d "$out/classes" @sources
if($LASTEXITCODE){throw 'Extended Windows support compilation failed'}
& "$jdk/bin/jar.exe" -J-Xmx64m cf "$out/windows-support.jar" -C "$out/classes" .
if($LASTEXITCODE){throw 'Extended Windows support packaging failed'}
[pscustomobject]@{clientJarSha256=(Get-FileHash $jar -Algorithm SHA256).Hash;sourceClientSha256=(Get-FileHash "$source/Client.java" -Algorithm SHA256).Hash;supportSha256=(Get-FileHash "$out/windows-support.jar" -Algorithm SHA256).Hash;capability='k242-skeletal-v1'} | ConvertTo-Json | Set-Content "$out/build-receipt.json"
"Built extended Windows support: $out/windows-support.jar"
