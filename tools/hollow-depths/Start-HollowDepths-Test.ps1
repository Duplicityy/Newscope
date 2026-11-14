param(
    [ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-01',
    [ValidateSet('Cache','Game','Client')][string]$Component='Cache',
    [ValidateRange(256,4096)][int]$HeapMb=512,
    [ValidateSet('hdqa1','hdqa2')][string]$Account='hdqa1',
    [switch]$ProductionPaused
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root "local-runtime/$StageName"
$m=Get-Content -LiteralPath "$stage/hollow-depths-test.json" -Raw | ConvertFrom-Json
if($m.stage -ne $stage -or $m.revision -ne 184){throw 'Staging manifest identity mismatch'}
if($Component -eq 'Client'){
    & "$PSScriptRoot/Test-HollowDepths-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused
    if((Get-FileHash -LiteralPath $m.client -Algorithm SHA256).Hash -ne $m.clientSha256){throw 'Actual184 client changed since preparation'}
    $clientHome="$stage/client-home-$Account";$output="$stage/captures-$Account"
    New-Item -ItemType Directory -Path $clientHome,$output -Force | Out-Null
    foreach($marker in @('client.ready','client-fatal.txt')){if(Test-Path -LiteralPath (Join-Path $output $marker)){Remove-Item -LiteralPath (Join-Path $output $marker)}}
    $arguments=@("-Xmx${HeapMb}m",'-Dsun.java2d.d3d=false','-Dkronos.hollowDepths.test=true','-Dkronos.host=127.0.0.1',("-Dkronos.hollowDepths.account=$Account"),('-Duser.home="'+$clientHome+'"'),('-Dkronos.hollowDepths.output="'+$output+'"'),'-cp',('"'+$stage+'/classes;'+$m.support+';'+$m.client+'"'),'net.runelite.standalone.HollowDepthsClientSmoke')
    $stem="client-$Account"
}else{
    $port=if($Component -eq 'Cache'){17314}else{14312}
    if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Staging port $port is occupied"}
    $freeMb=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
    if($freeMb -lt ($HeapMb+768)){throw "Insufficient free memory for staging: $freeMb MB available"}
    $arguments=@("-Xmx${HeapMb}m",'-Dkronos.bind=127.0.0.1','-Dkronos.hollowDepths.test=true',("-Dkronos.hollowDepths.stage=$StageName"),('-Duser.home="'+$stage+'/home"'))
    if($Component -eq 'Cache'){$arguments+=@('-cp',('"'+$stage+'/classes;'+$m.cacheLib+'/*"'),'io.ruin.update.HollowDepthsCacheServer')}
    else{$arguments+=@('-Dkronos.port=14312','-Dkronos.adventurers.test=true','-Dkronos.registration=true',('-Dkronos.accounts="'+$stage+'/accounts.properties"'),('-Dkronos.adventurers.config="'+$stage+'/adventurers.properties"'),('-Dkronos.playerData="'+$stage+'/players"'),'-cp',('"'+$m.gameLib+'/*"'),'io.ruin.Server')}
    $stem=$Component.ToLowerInvariant()
}
$process=Start-Process -FilePath $m.java -ArgumentList $arguments -WorkingDirectory $stage -WindowStyle Hidden -PassThru -RedirectStandardOutput "$stage/$stem.out.log" -RedirectStandardError "$stage/$stem.err.log"
Set-Content -LiteralPath "$stage/$stem.pid" -Value $process.Id
if($Component -eq 'Game'){Set-Content -LiteralPath "$stage/server.pid" -Value $process.Id}
"Started isolated184 $Component process $($process.Id); logs: $stage/$stem.out.log"
