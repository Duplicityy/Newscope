param(
    [ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-01',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$ReleaseName=''
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$project=Join-Path $root 'kronos-osrs-184/Kronos-master'
$runtime=Join-Path $root 'local-runtime'
$backup=(Get-Content -LiteralPath "$runtime/latest-hollow-depths-backup.txt" -Raw).Trim()
$verification=Get-Content -LiteralPath "$backup/VERIFIED.json" -Raw | ConvertFrom-Json
if(!$verification.private -or !$verification.productionUnchanged -or $verification.backup -ne $backup){throw 'Verified private184 backup is required'}
if((Get-FileHash -LiteralPath "$backup/SHA256-MANIFEST.json" -Algorithm SHA256).Hash -ne $verification.manifestSha256){throw 'Recovery manifest changed'}
if(!$ReleaseName){$ReleaseName=(Get-Content -LiteralPath "$runtime/game-server-release.txt" -Raw).Trim()}
if($ReleaseName -notmatch '^[a-zA-Z0-9_-]+$' -or $ReleaseName -match '242'){throw 'Only an explicitly selected revision184 release is allowed'}
$stage=Join-Path $runtime $StageName
if(Test-Path -LiteralPath $stage){throw 'Choose a new test stage; existing stages are preserved'}
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$client=Join-Path $root 'windows-release/Kronos-Windows/app/client.jar'
$support=Join-Path $root 'windows-release/Kronos-Windows/app/windows-support.jar'
$gameLib=Join-Path $runtime "server-releases/$ReleaseName/lib"
$cacheLib=Join-Path $project 'kronos-update-server/build/install/kronos-update-server/lib'
foreach($path in @("$jdk/bin/javac.exe",$client,$support,$gameLib,$cacheLib)){if(!(Test-Path -LiteralPath $path)){throw "Missing test prerequisite: $path"}}
New-Item -ItemType Directory -Path $stage,"$stage/home/Desktop/kronos","$stage/players","$stage/classes","$stage/generated/net/runelite/standalone" -Force | Out-Null
function Copy-Tree([string]$Source,[string]$Destination){
    & robocopy.exe $Source $Destination /E /COPY:DAT /DCOPY:DAT /XJ /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if($LASTEXITCODE -ge 8){throw 'Staging asset copy failed'}
}
Copy-Tree "$backup/server/Kronos-master/Cache" "$stage/Cache"
Copy-Tree "$backup/server/Kronos-master/kronos-server/data" "$stage/data"
if(Test-Path -LiteralPath "$stage/data/content242.json"){throw 'The184 baseline unexpectedly contains a242 content manifest'}
$allowed='^(world_id|world_name|world_stage|world_type|world_flag|world_settings|halloween|christmas|login_set)='
$safe=@(Get-Content -LiteralPath "$backup/server/Kronos-master/server.properties" | Where-Object {$_ -match $allowed})
$safe+=@('offline_mode=true','world_address=127.0.0.1:14312','central_address=127.0.0.1:1',('cache_path='+"$stage/Cache".Replace('\','/')),('data_path='+"$stage/data".Replace('\','/')))
[IO.File]::WriteAllLines("$stage/server.properties",$safe,[Text.Encoding]::ASCII)
[IO.File]::WriteAllText("$stage/adventurers.properties",("enabled=false`npopulation=1`nstorage="+"$stage/actors".Replace('\','/')+"`nskiller.slayer=exclude`nmarket.enabled=false`n"),[Text.Encoding]::ASCII)
[IO.File]::WriteAllText("$stage/accounts.properties","stage=true`n",[Text.Encoding]::ASCII)
$task=[IO.File]::ReadAllText("$project/runelite/runelite-client/src/main/java/net/runelite/standalone/TaskHandler.java")
$anchor='return this.method3417(1, var2, 0, var1);'
if([regex]::Matches($task,[regex]::Escape($anchor)).Count -ne 1){throw 'Revision184 TaskHandler routing anchor changed'}
$routing=@'
if (!Boolean.getBoolean("kronos.hollowDepths.test"))
         throw new IllegalStateException("Hollow Depths isolated routing requires test mode");
      if (var2 == 7304) var2 = 17314;
      else if (var2 == 13302 || var2 == 14312) var2 = 14312;
      else throw new IllegalArgumentException("Unexpected test socket port: " + var2);
      return this.method3417(1, var2, 0, "127.0.0.1");
'@
$generated=Join-Path $stage 'generated/net/runelite/standalone/TaskHandler.java'
[IO.File]::WriteAllText($generated,$task.Replace($anchor,$routing),[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx128m -encoding UTF-8 -cp "$cacheLib/*" -d "$stage/classes" "$PSScriptRoot/HollowDepthsCacheServer.java"
if($LASTEXITCODE){throw 'Isolated update entry compilation failed'}
& "$jdk/bin/javac.exe" -J-Xmx128m -encoding UTF-8 -cp $client -d "$stage/classes" $generated "$PSScriptRoot/HollowDepthsClientSmoke.java"
if($LASTEXITCODE){throw 'Actual184 client harness compilation failed'}
$production=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in 13302,7304} | ForEach-Object {
    $p=Get-Process -Id $_.OwningProcess
    [pscustomobject]@{port=$_.LocalPort;pid=$p.Id;startedUtc=$p.StartTime.ToUniversalTime().ToString('o')}
})
if(@($production | Select-Object -ExpandProperty port -Unique).Count -ne 2){throw 'Both original live184 listeners must be present during preparation'}
[pscustomobject]@{
    stage=$stage;backup=$backup;java="$jdk/bin/java.exe";gameLib=$gameLib;cacheLib=$cacheLib
    client=$client;support=$support;clientSha256=(Get-FileHash -LiteralPath $client -Algorithm SHA256).Hash
    original184CacheSha256=(Get-FileHash -LiteralPath "$stage/Cache/main_file_cache.dat2" -Algorithm SHA256).Hash
    production=$production;gamePort=14312;cachePort=17314;revision=184
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$stage/hollow-depths-test.json" -Encoding UTF8
"Prepared isolated184 stage $stage. No servers were started."
