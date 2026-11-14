param([ValidatePattern('^[a-z0-9-]+$')][string]$StageName='hollow-depths-test-01',[switch]$CacheOnly,[switch]$ProductionPaused)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root ('local-runtime/'+$StageName)
$m=Get-Content -LiteralPath "$stage/hollow-depths-test.json" -Raw | ConvertFrom-Json
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
    if(!$process -or !$process.CommandLine.Contains('-Dkronos.hollowDepths.test=true') -or !$process.CommandLine.Contains("-Dkronos.hollowDepths.stage=$StageName")){throw "Unverified staging $component process"}
    $port=if($component -eq 'cache'){17314}else{14312}
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $port -OwningProcess $stagePid -ErrorAction SilentlyContinue)
    if(!$listeners -or @($listeners | Where-Object {$_.LocalAddress -notin '127.0.0.1','::1'}).Count){throw "Staging $component must listen only on loopback port $port"}
}
$socket=[Net.Sockets.TcpClient]::new()
try{
    $socket.ReceiveTimeout=5000;$socket.SendTimeout=5000;$socket.Connect('127.0.0.1',17314)
    $stream=$socket.GetStream();$handshake=[byte[]](15,0,0,0,184)
    $stream.Write($handshake,0,$handshake.Length)
    if($stream.ReadByte() -ne 0){throw 'Staged cache rejected the revision-184 handshake'}
}finally{$socket.Dispose()}
if($gamePaused){"PASS: staging listeners isolated; revision-184 cache handshake accepted; original production cache unchanged; production game temporarily paused and port13302 closed."}
else{"PASS: staging listeners isolated; revision-184 cache handshake accepted; production listeners unchanged."}
