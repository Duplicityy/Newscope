param(
    [Parameter(Mandatory=$true)][string]$Bundle,
    [ValidateSet(4096,5120)][int]$GameHeapMb=5120,
    [switch]$NoClient
)
$ErrorActionPreference='Stop'
$bundlePath=(Resolve-Path -LiteralPath $Bundle).Path
$m=Get-Content -LiteralPath "$bundlePath/release-manifest.json" -Raw | ConvertFrom-Json
if(!$m.sealed -or $m.bundle -ne $bundlePath){throw 'An exact sealed bundle is required'}
$hash=(Get-FileHash -LiteralPath "$bundlePath/release-manifest.json" -Algorithm SHA256).Hash
if($hash -ne (Get-Content -LiteralPath "$bundlePath/release-manifest.sha256" -Raw).Trim()){throw 'Bundle manifest hash changed'}
$workspace=$m.workspace;$runtime=Join-Path $workspace 'local-runtime';$project=Join-Path $workspace 'kronos-osrs-184/Kronos-master'
$selection=Get-Content -LiteralPath "$runtime/content242-active.json" -Raw | ConvertFrom-Json
if($selection.bundle -ne $bundlePath -or $selection.manifestSha256 -ne $hash){throw 'This bundle is not the selected release; use the explicit deployment entry'}
foreach($role in @('game','updater')){
    $folder=Join-Path $bundlePath $role
    if(Get-ChildItem -LiteralPath $folder -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue){throw 'Library tree contains a reparse point'}
    $entries=@($m.trees.$role)
    if(@(Get-ChildItem -LiteralPath $folder -Recurse -File -Force).Count -ne $entries.Count){throw 'Selected library file set changed'}
    foreach($entry in $entries){
        $file=[IO.Path]::GetFullPath((Join-Path $folder $entry.path))
        if(!$file.StartsWith($folder+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.sha256){throw 'Selected library bytes changed; launch refused'}
    }
}
$java=Join-Path $workspace 'tools/jdk8/jdk8u512-b09/bin/java.exe'
$bind='127.0.0.1'
if(Test-Path -LiteralPath "$runtime/network.json"){$bind=(Get-Content -LiteralPath "$runtime/network.json" -Raw | ConvertFrom-Json).bindAddress}
if($bind -notin @('127.0.0.1','0.0.0.0')){throw 'Unexpected bind address'}
if(!(Test-Path -LiteralPath "$runtime/accounts.properties")){throw 'The selected production release requires the existing stable account registry, including localhost mode'}
$common=@("-Dkronos.bind=$bind",('-Duser.home="'+$runtime+'/home"'),('-Dkronos.accounts="'+$runtime+'/accounts.properties"'))
if($bind -eq '0.0.0.0'){
    $common+=@('-Dkronos.public=true','-Dkronos.registration=true')
}
$running=@{}
foreach($role in @('game','updater')){
    $name=if($role -eq 'game'){'game-server'}else{'update-server'}
    $main=if($role -eq 'game'){'io.ruin.Server'}else{'io.ruin.update.Server'}
    $port=if($role -eq 'game'){13302}else{7304}
    $listener=@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    $pidFile=Join-Path $runtime "$name.pid";$process=$null
    if(Test-Path -LiteralPath $pidFile){
        $serverPid=[int](Get-Content -LiteralPath $pidFile)
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    }
    if($process){
        if($process.ExecutablePath -ne $java -or !$process.CommandLine.Contains($bundlePath) -or !$process.CommandLine.Trim().EndsWith($main)){throw 'Existing process does not belong to this release'}
        if(!$listener){throw "$name already has a live selected PID before its port is ready; a duplicate JVM will not be launched"}
        if(@($listener | Where-Object OwningProcess -ne $serverPid).Count){throw 'Port is owned by another process'}
        $running[$role]=$true
    }elseif($listener){throw "Port $port occupied without the selected server identity"}
}
$requiredHeap=0
if(!$running.ContainsKey('game')){$requiredHeap+=$GameHeapMb}
if(!$running.ContainsKey('updater')){$requiredHeap+=512}
if($requiredHeap){
    $os=Get-CimInstance Win32_OperatingSystem;$memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
    $physical=[math]::Floor($os.FreePhysicalMemory/1024);$commit=[math]::Floor(($memory.CommitLimit-$memory.CommittedBytes)/1MB)
    if($physical -lt ($requiredHeap+1024) -or $commit -lt ($requiredHeap+1024)){throw "Capacity guard: need $($requiredHeap+1024) MB physical and commit headroom; available physical=$physical MB, commit=$commit MB. No heap overcommit attempted."}
}
$logs=Join-Path $runtime 'logs';New-Item -ItemType Directory -Force -Path $logs | Out-Null
foreach($role in @('updater','game')){
    if($running.ContainsKey($role)){continue}
    $name=if($role -eq 'game'){'game-server'}else{'update-server'}
    $main=if($role -eq 'game'){'io.ruin.Server'}else{'io.ruin.update.Server'}
    $port=if($role -eq 'game'){13302}else{7304}
    $heap=if($role -eq 'game'){$GameHeapMb}else{512}
    $arguments=$common+@("-Xmx${heap}m",'-cp',('"'+$bundlePath+'/'+$role+'/*"'),$main)
    if($role -eq 'game'){
        $options=@(('-Dkronos.playerData="'+$runtime+'/player-data"'))
        if(Test-Path -LiteralPath "$runtime/adventurers.properties"){$options+=('-Dkronos.adventurers.config="'+$runtime+'/adventurers.properties"')}
        $arguments=$options+$arguments
    }
    $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput "$logs/$name.out.log" -RedirectStandardError "$logs/$name.err.log"
    Set-Content -LiteralPath "$runtime/$name.pid" -Value $process.Id -Encoding ASCII
    $deadline=[DateTime]::UtcNow.AddSeconds(180)
    while($true){
        $process.Refresh();if($process.HasExited){throw "$name exited; inspect its private runtime logs"}
        $listener=@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $process.Id)
        if($listener){break}
        if([DateTime]::UtcNow -gt $deadline){throw "$name startup timed out; no forced termination performed"}
        Start-Sleep -Milliseconds 500
    }
    "$name ready on port $port with ${heap}MB heap."
}
# Desktop and Android clients are separate versioned outputs. Never rewrite their
# saved profiles, Android app data, control layouts, or an open client process.
