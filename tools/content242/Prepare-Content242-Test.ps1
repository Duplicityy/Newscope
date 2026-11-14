param(
    [ValidatePattern('^[a-z0-9-]+$')][string]$StageName='content242-test',
    [string]$ContentPath='',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$ReleaseName='content242-full-staged',
    [string]$ClientJar=''
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Workspace.ps1"
if(!$ContentPath){$ContentPath=Join-Path $root 'local-runtime/content242-stage'}
$content=(Resolve-Path -LiteralPath $ContentPath).Path
$runtimeRoot=[IO.Path]::GetFullPath((Join-Path $root 'local-runtime'))+[IO.Path]::DirectorySeparatorChar
if(!$content.StartsWith($runtimeRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Content must be an isolated directory under local-runtime'}
$cache=Join-Path $content 'Cache'
$data=Join-Path $content 'data'
foreach($path in @((Join-Path $cache 'main_file_cache.dat2'),(Join-Path $cache 'main_file_cache.idx255'),$data)){
    if(!(Test-Path -LiteralPath $path)){throw "Staging input is missing: $path"}
}
$stage=Join-Path $root ('local-runtime/'+$StageName)
if(Test-Path -LiteralPath $stage){throw 'Choose a new test stage; existing stages are preserved'}
$jdk8=Join-Path $root 'tools/jdk8/jdk8u512-b09'
if(!$ClientJar){throw 'Pass the freshly built extended client with -ClientJar'}
$client=(Resolve-Path -LiteralPath $ClientJar).Path
$support=Join-Path $root 'windows-release/Kronos-Windows/app/windows-support.jar'
$gameLib=Join-Path $root ('local-runtime/server-releases/'+$ReleaseName+'/lib')
$cacheLib=Join-Path $project 'kronos-update-server/build/install/kronos-update-server/lib'
foreach($path in @("$jdk8/bin/javac.exe",$client,$support,$gameLib,$cacheLib)){
    if(!(Test-Path -LiteralPath $path)){throw "Build prerequisite is missing: $path"}
}
New-Item -ItemType Directory -Path $stage,"$stage/home/Desktop/kronos","$stage/client-home","$stage/players","$stage/classes","$stage/generated/net/runelite/standalone" -Force | Out-Null
New-Item -ItemType Directory -Path "$stage/client-build","$stage/cache-lib" -Force | Out-Null
Copy-Item -LiteralPath $client -Destination "$stage/client-build/client.jar"
& "$PSScriptRoot/Build-WindowsSupport.ps1" -ClientJar $client -OutputPath "$stage/windows-support-build"
Copy-Item -LiteralPath "$stage/windows-support-build/windows-support.jar" -Destination "$stage/client-build/windows-support.jar"
Get-ChildItem -LiteralPath $cacheLib -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination "$stage/cache-lib" }
$client=Join-Path $stage 'client-build/client.jar'
$support=Join-Path $stage 'client-build/windows-support.jar'
$cacheLib=Join-Path $stage 'cache-lib'
# Copy only nonsecret world settings, then force isolated offline state.
$allowed='^(world_id|world_name|world_stage|world_type|world_flag|world_settings|halloween|christmas|login_set)='
$safe=@(Get-Content -LiteralPath (Join-Path $root 'kronos-osrs-184/Kronos-master/server.properties') | Where-Object {$_ -match $allowed})
$safe+=@('offline_mode=true','world_address=127.0.0.1:14302','central_address=127.0.0.1:1','cache_port=17304',('cache_path='+$cache.Replace('\','/')),('data_path='+$data.Replace('\','/')))
[IO.File]::WriteAllLines("$stage/server.properties",$safe,[Text.Encoding]::ASCII)
[IO.File]::WriteAllText("$stage/adventurers.properties",("enabled=false`npopulation=1`nstorage="+(Join-Path $stage 'actors').Replace('\','/')+"`nskiller.slayer=exclude`nmarket.enabled=false`n"),[Text.Encoding]::ASCII)
$source=Join-Path $project 'runelite/runelite-client/src/main/java/net/runelite/standalone/TaskHandler.java'
$task=[IO.File]::ReadAllText($source)
$anchor='return this.method3417(1, var2, 0, var1);'
if([regex]::Matches($task,[regex]::Escape($anchor)).Count -ne 1){throw 'Revision-184 TaskHandler routing anchor changed'}
$replacement=@'
if (!Boolean.getBoolean("kronos.content242.test"))
         throw new IllegalStateException("Isolated content test routing requires explicit test mode");
      if (var2 == 7304) var2 = 17304;
      else if (var2 == 13302 || var2 == 14302) var2 = 14302;
      else throw new IllegalArgumentException("Unexpected test socket port: " + var2);
      return this.method3417(1, var2, 0, "127.0.0.1");
'@
$generated=Join-Path $stage 'generated/net/runelite/standalone/TaskHandler.java'
[IO.File]::WriteAllText($generated,$task.Replace($anchor,$replacement),[Text.UTF8Encoding]::new($false))
& "$jdk8/bin/javac.exe" -J-Xmx128m -encoding UTF-8 -cp "$cacheLib/*" -d "$stage/classes" "$PSScriptRoot/Content242CacheServer.java"
if($LASTEXITCODE){throw 'Isolated update entry point compilation failed'}
& "$jdk8/bin/javac.exe" -J-Xmx128m -encoding UTF-8 -cp $client -d "$stage/classes" $generated "$PSScriptRoot/Content242ClientSmoke.java"
if($LASTEXITCODE){throw 'Isolated socket routing compilation failed'}
$production=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in 13302,7304} | ForEach-Object {
    $p=Get-Process -Id $_.OwningProcess
    [pscustomobject]@{port=$_.LocalPort;pid=$p.Id;startedUtc=$p.StartTime.ToUniversalTime().ToString('o')}
})
[pscustomobject]@{
    stage=$stage;content=$content;java="$jdk8/bin/java.exe";gameLib=$gameLib;cacheLib=$cacheLib
    client=$client;support=$support;clientSha256=(Get-FileHash -LiteralPath $client -Algorithm SHA256).Hash
    supportSha256=(Get-FileHash -LiteralPath $support -Algorithm SHA256).Hash
    production=$production;gamePort=14302;cachePort=17304;cacheRevision=184242
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$stage/content242-test.json" -Encoding UTF8
"Prepared $stage. Servers were not started."
