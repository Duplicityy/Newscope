param([Parameter(Mandatory)][string]$Build)

$ErrorActionPreference = 'Stop'
$stage = (Resolve-Path -LiteralPath $Build).Path
$manifest = Get-Content -LiteralPath "$stage/WORLD242-DEV.json" -Raw | ConvertFrom-Json
if ($manifest.kind -ne 'NEWSCAPE_WORLD242_DEVELOPMENT' -or $manifest.path -ne $stage -or
    $manifest.copiedPlayerData -or $manifest.gamePort -ne 14302 -or $manifest.cachePort -ne 14304) {
    throw 'Expected an isolated development build'
}
if (Get-NetTCPConnection -State Listen -LocalPort 14302,14304 -ErrorAction SilentlyContinue) {
    throw 'Development ports are already in use'
}
function Get-UsedMemory {
    $os = Get-CimInstance Win32_OperatingSystem
    return 100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize)
}
$baseline = Get-UsedMemory
if ($baseline -gt 60) { throw 'Host exceeds the server startup memory limit of 60 percent' }
$peak = $baseline
$jdk = $manifest.jdk
$maintenance = Join-Path (Split-Path $PSScriptRoot -Parent) 'Kronos-master/tools/content242/Content242Shutdown.java'
New-Item -ItemType Directory -Path "$stage/shutdown" -Force | Out-Null
& "$jdk/bin/javac.exe" -cp "$jdk/lib/tools.jar" -d "$stage/shutdown" $maintenance
if ($LASTEXITCODE -ne 0) { throw 'Shutdown helper compilation failed' }
'Agent-Class: Content242Shutdown' | Set-Content -LiteralPath "$stage/shutdown.mf" -Encoding ascii
& "$jdk/bin/jar.exe" cfm "$stage/shutdown.jar" "$stage/shutdown.mf" -C "$stage/shutdown" .
if ($LASTEXITCODE -ne 0) { throw 'Shutdown helper packaging failed' }

function Start-DevelopmentProcess($role, $mainClass, $heap, $classPath, $options) {
    $flags = @('-Dkronos.bind=127.0.0.1','-Dkronos.public=false','-Dkronos.registration=false',
        ('-Duser.home="'+$stage+'/home"'),'-Xms128m',"-Xmx${heap}m",'-XX:+UseParallelGC',
        '-XX:GCTimeRatio=9','-XX:MinHeapFreeRatio=10','-XX:MaxHeapFreeRatio=20')
    $flags += $options
    $flags += @('-cp',('"'+$classPath+'"'),$mainClass)
    Start-Process -FilePath "$jdk/bin/java.exe" -ArgumentList $flags -WorkingDirectory "$stage/workdir" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$stage/$role.out.log" -RedirectStandardError "$stage/$role.err.log"
}
function Wait-Listener($process, $port) {
    $until = [DateTime]::UtcNow.AddSeconds(150)
    while ([DateTime]::UtcNow -lt $until) {
        $process.Refresh()
        if ($process.HasExited) { throw "Development process on port $port exited" }
        $used = Get-UsedMemory
        $script:peak = [Math]::Max($script:peak, $used)
        if ($used -ge 82) { throw 'Host crossed the 82 percent memory threshold' }
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        if ($listeners.Count -gt 0) {
            if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1' -or
                $listeners[0].OwningProcess -ne $process.Id) { throw 'Unexpected development listener' }
            return
        }
        Start-Sleep -Milliseconds 800
    }
    throw "Timed out waiting for development port $port"
}
function Test-Handshake($port, [byte[]]$request) {
    $client = [Net.Sockets.TcpClient]::new('127.0.0.1',$port)
    try {
        $stream = $client.GetStream()
        $stream.ReadTimeout = 3000
        $stream.Write($request,0,$request.Length)
        if ($stream.ReadByte() -ne 0) { throw "Invalid reply on development port $port" }
    } finally { $client.Dispose() }
}
function Stop-DevelopmentProcess($process, $role) {
    if ($null -eq $process) { return }
    $process.Refresh()
    if ($process.HasExited) { return }
    & "$jdk/bin/java.exe" '-Xmx64m' -cp "$stage/shutdown;$jdk/lib/tools.jar" Content242Shutdown $process.Id "$stage/shutdown.jar" $role
    if ($LASTEXITCODE -ne 0 -or !$process.WaitForExit(10000)) {
        throw "Development $role did not shut down normally. Check process $($process.Id)."
    }
}

$game = $null
$cache = $null
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    $cache = Start-DevelopmentProcess 'cache' 'io.ruin.update.Server' 256 $manifest.cacheClasspath @()
    Wait-Listener $cache 14304
    $gameFlags = @('-Dkronos.port=14302','-Dkronos.universalPets=true','-Dnewscape.raids.enabled=true',
        '-Dkronos.dungeoneering.enabled=true','-Dkronos.dungeoneering.staffTesting=true','-Dkronos.homeTesting=true',
        '-Dkronos.six.pets=true','-Dkronos.six.mastery=true','-Dkronos.six.armor=true','-Dkronos.six.legend=true',
        '-Dkronos.six.athletics=true','-Dkronos.six.athleticsSpeed=true','-Dkronos.six.thanks=true',
        '-Dkronos.six.cosmeticIcons=true','-Dkronos.endgameArmor.enabled=true',
        ('-Dkronos.accounts="'+$stage+'/state/accounts.properties"'),
        ('-Dkronos.playerData="'+$stage+'/state/player-data"'),
        ('-Dkronos.six.data="'+$stage+'/state/six-systems"'),
        ('-Dkronos.adventurers.config="'+$stage+'/state/adventurers.properties"'))
    $game = Start-DevelopmentProcess 'game' 'io.ruin.Server' 3584 $manifest.gameClasspath $gameFlags
    Wait-Listener $game 14302
    $startup = $timer.Elapsed.TotalSeconds
    Test-Handshake 14302 ([byte[]]@(14))
    # JS5 opcode 15 followed by cache revision 184729 as a big-endian int.
    Test-Handshake 14304 ([byte[]]@(15,0,2,209,153))
} finally {
    try { Stop-DevelopmentProcess $game 'game' } finally { Stop-DevelopmentProcess $cache 'cache' }
}
$receipt = [ordered]@{status='PASS'; startupSeconds=[Math]::Round($startup,2);
    startingHostMemoryPercent=[Math]::Round($baseline,2); peakHostMemoryPercent=[Math]::Round($peak,2);
    gamePort=14302; cachePort=14304; listenersStopped=$true; playerSessions=0}
$receipt | ConvertTo-Json | Set-Content -LiteralPath "$stage/BOOT-CHECK.json"
$receipt | ConvertTo-Json
