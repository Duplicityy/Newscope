param([int]$Population=1,[ValidatePattern('^[a-z0-9-]+$')][string]$StageName='adventurer-test',[string]$ReleaseName='')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$project=Join-Path $root 'kronos-osrs-184/Kronos-master'
$stage=Join-Path $root ('local-runtime/'+$StageName)
New-Item -ItemType Directory -Force $stage,(Join-Path $stage 'home/Desktop/kronos'),(Join-Path $stage 'players') | Out-Null
if(!(Test-Path (Join-Path $stage 'data'))) {
 Copy-Item (Join-Path $project 'kronos-server/data') (Join-Path $stage 'data') -Recurse
}
$properties=[IO.File]::ReadAllText((Join-Path $project 'server.properties'))
$properties=$properties -replace '(?m)^cache_path=.*$',('cache_path='+((Join-Path $project 'Cache') -replace '\\','/'))
$properties=$properties -replace '(?m)^data_path=.*$',('data_path='+((Join-Path $stage 'data') -replace '\\','/'))
$properties=$properties -replace '(?m)^world_address=.*$','world_address=127.0.0.1:14302'
[IO.File]::WriteAllText((Join-Path $stage 'server.properties'),$properties)
$store=(Join-Path $stage 'actors') -replace '\\','/'
"enabled=true`npopulation=$Population`nstorage=$store`nskiller.slayer=exclude`nmarket.enabled=false" | Set-Content (Join-Path $stage 'adventurers.properties')
$existing=Get-NetTCPConnection -LocalPort 14302 -State Listen -ErrorAction SilentlyContinue
if($existing){throw 'Staging port is occupied'}
$java=Join-Path $root 'tools/jdk8/jdk8u512-b09/bin/java.exe'
$gameLib=Join-Path $project 'kronos-server/build/install/kronos-server/lib/*'
if($ReleaseName) {
 if($ReleaseName -notmatch '^[a-zA-Z0-9_-]+$'){throw 'Invalid release name'}
 $releaseLib=Join-Path $root ('local-runtime/server-releases/'+$ReleaseName+'/lib')
 if(!(Test-Path -LiteralPath $releaseLib) -or !(Get-ChildItem -LiteralPath $releaseLib -Filter 'kronos-server*.jar')){throw 'Incomplete staging release'}
 $gameLib=Join-Path $releaseLib '*'
}
$arguments=@('-Xmx2g','-Dkronos.bind=127.0.0.1','-Dkronos.port=14302','-Dkronos.adventurers.test=true',('-Dkronos.adventurers.config="'+(Join-Path $stage 'adventurers.properties')+'"'),('-Dkronos.playerData="'+(Join-Path $stage 'players')+'"'),('-Duser.home="'+(Join-Path $stage 'home')+'"'),'-cp',('"'+((Join-Path $project 'kronos-server/build/classes/java/test')+';'+$gameLib)+'"'),'io.ruin.Server')
$p=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $stage -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stage 'server.out.log') -RedirectStandardError (Join-Path $stage 'server.err.log')
Set-Content (Join-Path $stage 'server.pid') $p.Id
"Staging process $($p.Id), population $Population, isolated port 14302"
