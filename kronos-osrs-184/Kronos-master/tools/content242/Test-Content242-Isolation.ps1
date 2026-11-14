param([ValidatePattern('^[a-z0-9-]+$')][string]$StageName='content242-test',[switch]$CacheOnly,[switch]$ProductionPaused)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Workspace.ps1"
$stage=Join-Path $root ('local-runtime/'+$StageName)
$m=Get-Content -LiteralPath "$stage/content242-test.json" -Raw | ConvertFrom-Json
$gamePaused=$false
foreach($baseline in $m.production){
    if($ProductionPaused -and $baseline.port -eq 13302){
        $candidate=Get-Process -Id $baseline.pid -ErrorAction SilentlyContinue
        $originalAlive=$candidate -and $candidate.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$baseline.startedUtc).ToUniversalTime().Ticks
        if(!$originalAlive){
            if(Get-NetTCPConnection -State Listen -LocalPort 13302 -ErrorAction SilentlyContinue){throw 'Expected paused production game, but port13302 has a listener'}
            $gamePaused=$true
            continue
        }
    }
    $p=Get-Process -Id $baseline.pid -ErrorAction Stop
    if($p.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$baseline.startedUtc).ToUniversalTime().Ticks){throw 'A production process changed since preparation'}
    if(!(Get-NetTCPConnection -State Listen -LocalPort $baseline.port -OwningProcess $baseline.pid -ErrorAction SilentlyContinue)){throw 'A production listener changed since preparation'}
}
$components=if($CacheOnly){@('cache')}else{@('cache','game')}
foreach($component in $components){
    $stagePid=[int](Get-Content -LiteralPath "$stage/$component.pid")
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$stagePid"
    if(!$process -or !$process.CommandLine.Contains('-Dkronos.content242.test=true') -or !$process.CommandLine.Contains("-Dkronos.content242.stage=$StageName")){throw "Unverified staging $component process"}
    $port=if($component -eq 'cache'){17304}else{14302}
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $port -OwningProcess $stagePid -ErrorAction SilentlyContinue)
    if(!$listeners -or @($listeners | Where-Object {$_.LocalAddress -notin '127.0.0.1','::1'}).Count){throw "Staging $component must listen only on loopback port $port"}
}
$socket=[Net.Sockets.TcpClient]::new()
try{
    $socket.ReceiveTimeout=5000;$socket.SendTimeout=5000;$socket.Connect('127.0.0.1',17304)
    $stream=$socket.GetStream();$revision=[int]$m.cacheRevision
    $handshake=[byte[]](15,(($revision -shr 24) -band 255),(($revision -shr 16) -band 255),(($revision -shr 8) -band 255),($revision -band 255))
    $stream.Write($handshake,0,$handshake.Length)
    if($stream.ReadByte() -ne 0){throw 'Staged cache rejected the extended242 capability handshake'}
}finally{$socket.Dispose()}
$legacy=[Net.Sockets.TcpClient]::new()
try{
    $legacy.ReceiveTimeout=5000;$legacy.Connect('127.0.0.1',17304)
    $stream=$legacy.GetStream();$handshake=[byte[]](15,0,0,0,184)
    $stream.Write($handshake,0,$handshake.Length)
    if($stream.ReadByte() -eq 0){throw 'Old client was incorrectly allowed to download extended cache records'}
}finally{$legacy.Dispose()}
if($gamePaused){"PASS: staged listeners isolated; extended cache accepted and old client rejected; production cache unchanged; production game paused."}
else{"PASS: staged listeners isolated; extended cache accepted and old client rejected; production listeners unchanged."}
