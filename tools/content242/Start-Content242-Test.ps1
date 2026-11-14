param([ValidatePattern('^[a-z0-9-]+$')][string]$StageName='content242-test',[ValidateSet('Cache','Game','Client')][string]$Component='Cache',[ValidateRange(256,5120)][int]$HeapMb=768,[switch]$ProductionPaused,[ValidatePattern('^[a-z0-9,-]+$')][string]$Phases='gear,map')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Workspace.ps1"
$stage=Join-Path $root ('local-runtime/'+$StageName)
$m=Get-Content -LiteralPath "$stage/content242-test.json" -Raw | ConvertFrom-Json
if($m.stage -ne $stage){throw 'Staging manifest path mismatch'}
$memory=Get-CimInstance Win32_OperatingSystem
$commitMb=[math]::Floor($memory.FreeVirtualMemory/1024)
if($commitMb -lt ($HeapMb+512)){throw "Insufficient commit headroom for staging: $commitMb MB available"}
if($Component -eq 'Client'){
    & "$PSScriptRoot/Test-Content242-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused
    if((Get-FileHash -LiteralPath $m.client -Algorithm SHA256).Hash -ne $m.clientSha256){throw 'The actual 184 client jar changed since preparation'}
    if(!$m.supportSha256 -or (Get-FileHash -LiteralPath $m.support -Algorithm SHA256).Hash -ne $m.supportSha256){throw 'The Windows client overlay changed since preparation'}
    $arguments=@("-Xmx${HeapMb}m",'-Dsun.java2d.d3d=false','-Dkronos.content242.test=true','-Dkronos.host=127.0.0.1',('-Duser.home="'+$stage+'/client-home"'),('-Dkronos.content242.output="'+$stage+'/captures"'),'-cp',('"'+$stage+'/classes;'+$m.support+';'+$m.client+'"'),'net.runelite.standalone.Content242ClientSmoke')
    $arguments=@(('-Dkronos.content242.phases='+$Phases))+$arguments
}else{
    $port=if($Component -eq 'Cache'){17304}else{14302}
    if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Staging port $port is occupied"}
    $freeMb=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
    if($freeMb -lt ($HeapMb+768)){throw "Insufficient free memory for staging: $freeMb MB available"}
    $arguments=@("-Xmx${HeapMb}m",'-Dkronos.bind=127.0.0.1','-Dkronos.content242.test=true',('-Dkronos.content242.stage='+$StageName),('-Duser.home="'+$stage+'/home"'))
    if($Component -eq 'Cache'){
        $arguments+=@('-cp',('"'+$stage+'/classes;'+$m.cacheLib+'/*"'),'io.ruin.update.Content242CacheServer')
    }else{
        $arguments+=@('-Dkronos.port=14302','-Dkronos.adventurers.test=true',('-Dkronos.adventurers.config="'+$stage+'/adventurers.properties"'),('-Dkronos.playerData="'+$stage+'/players"'),'-cp',('"'+$m.gameLib+'/*"'),'io.ruin.Server')
    }
}
$stem=$Component.ToLowerInvariant()
$process=Start-Process -FilePath $m.java -ArgumentList $arguments -WorkingDirectory $stage -WindowStyle Hidden -PassThru -RedirectStandardOutput "$stage/$stem.out.log" -RedirectStandardError "$stage/$stem.err.log"
Set-Content -LiteralPath "$stage/$stem.pid" -Value $process.Id
if($Component -eq 'Game'){Set-Content -LiteralPath "$stage/server.pid" -Value $process.Id}
"Started isolated $Component process $($process.Id); logs: $stage/$stem.out.log"
