param(
    [ValidateSet('Preflight','Apply','Rollback')][string]$Mode='Preflight',
    [Parameter(Mandatory=$true)][string]$Bundle,
    [ValidateSet(4096,5120)][int]$GameHeapMb=5120,
    [string]$RollbackReceipt=''
)
$ErrorActionPreference='Stop'
$bundlePath=(Resolve-Path -LiteralPath $Bundle).Path
$manifest=Get-Content -LiteralPath "$bundlePath/release-manifest.json" -Raw | ConvertFrom-Json
$workspace=$manifest.workspace;$runtime=Join-Path $workspace 'local-runtime';$project=Join-Path $workspace 'kronos-osrs-184/Kronos-master'
function Assert-Contained([string]$Path,[string]$Parent){
    $full=[IO.Path]::GetFullPath($Path);$prefix=[IO.Path]::GetFullPath($Parent).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if(!$full.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'A transaction path escapes its approved parent'}
    $cursor=$full
    while($cursor){if(Test-Path -LiteralPath $cursor){if((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse points are not valid deployment paths'}};$cursor=Split-Path $cursor -Parent}
}
Assert-Contained $bundlePath (Join-Path $runtime 'content242-releases')
$python=Join-Path $workspace 'tools/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/python3/python.exe'
& $python -B "$bundlePath/scripts/release_bundle.py" verify --bundle $bundlePath
if($LASTEXITCODE){throw 'Immutable bundle verification failed'}
if($manifest.workspace -ne [IO.Path]::GetFullPath($workspace) -or $manifest.bundle -ne $bundlePath){throw 'Manifest workspace identity mismatch'}
$liveCache=Join-Path $project 'Cache';$liveData=Join-Path $project 'kronos-server/data'
$javaHome=Join-Path $workspace 'tools/jdk8/jdk8u512-b09';$java=Join-Path $javaHome 'bin/java.exe'
function File-Sha([string]$Path){
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
    try{$algorithm=[Security.Cryptography.SHA256]::Create();try{[BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose()}}finally{$stream.Dispose()}
}
function Assert-Tree([string]$Base,$Entries){
    Assert-Contained $Base $workspace
    if(Get-ChildItem -LiteralPath $Base -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue){throw 'Artifact tree contains a reparse point'}
    $actual=@(Get-ChildItem -LiteralPath $Base -Recurse -File -Force)
    if($actual.Count -ne @($Entries).Count){throw "Artifact file count changed: $Base"}
    foreach($e in $Entries){
        $path=Join-Path $Base $e.path;Assert-Contained $path $Base
        if(!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -ne $e.bytes -or (File-Sha $path) -ne $e.sha256){throw "Artifact mismatch: $($e.path)"}
    }
}
function Assert-BaselineBinaries([string]$LauncherPath){
    if(!$manifest.baselineBinaries){throw 'The original game/updater/launcher were not pinned at sealing'}
    foreach($role in @('game','updater')){Assert-Tree $manifest.baselineBinaries.$role.path $manifest.baselineBinaries.$role.files}
    Assert-Contained $LauncherPath $workspace
    if((File-Sha $LauncherPath) -ne $manifest.baselineBinaries.launcher.sha256){throw 'Original launcher bytes changed; rollback/release provenance must be reviewed'}
}
function Copy-Tree([string]$From,[string]$To){
    Assert-Contained $From $workspace;Assert-Contained $To $workspace
    if(Get-ChildItem -LiteralPath $From -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue){throw 'Source copy tree contains a reparse point'}
    & robocopy.exe $From $To /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if($LASTEXITCODE -ge 8){throw 'Verified tree copy failed'}
}
function Restrict-Private([string]$Folder){
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $Folder '/inheritance:r' '/grant:r' ('*'+$sid+':(OI)(CI)F') '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '/T' '/Q' | Out-Null
    if($LASTEXITCODE){throw 'Private backup ACL could not be restricted before copying state'}
    if(@((Get-Acl -LiteralPath $Folder).Access).Count -eq 0){throw 'Private backup has an empty ACL'}
    foreach($rule in (Get-Acl -LiteralPath $Folder).Access){if($rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @($sid,'S-1-5-18','S-1-5-32-544')){throw 'Private backup has an unexpected ACL principal'}}
}
function Assert-ServersStopped{
    foreach($role in @('game','cache')){if(Server-Identity $role){throw 'A recorded production process still exists before port binding; mutation is blocked'}}
    if(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in @(13302,7304)}){throw 'A production listener remains; no Cache/data/account mutation or rollback is permitted'}
    foreach($p in @(Get-CimInstance Win32_Process -Filter "Name='java.exe'")){
        if(!$p.CommandLine){continue};$command=$p.CommandLine.Replace('\','/').ToLowerInvariant()
        $privatePath=(Join-Path $runtime 'player-data').Replace('\','/').ToLowerInvariant()
        $homePath=(Join-Path $runtime 'home').Replace('\','/').ToLowerInvariant()
        if(($command.Trim().EndsWith('io.ruin.server') -and $command.Contains($privatePath)) -or ($command.Trim().EndsWith('io.ruin.update.server') -and $command.Contains($homePath))){throw 'A production JVM remains before its port is ready; mutation is blocked'}
    }
}
function Private-PlayerState{
    $base=Join-Path $runtime 'player-data';Assert-Contained $base $workspace
    if(Get-ChildItem -LiteralPath $base -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue){throw 'Private player directory contains a reparse point'}
    @((Get-ChildItem -LiteralPath $base -Recurse -File -Force | Where-Object {!$_.FullName.StartsWith((Join-Path $base 'content242-owner-grants')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)} | Sort-Object FullName) | ForEach-Object {
        [pscustomobject]@{path=$_.FullName.Substring($base.TrimEnd('\','/').Length+1).Replace('\','/');sha256=(File-Sha $_.FullName)}
    })
}
function Assert-PrivatePlayerState($Expected,[string]$IgnorePath=''){
    $actual=@(Private-PlayerState | Where-Object path -ne $IgnorePath);$wanted=@($Expected | Where-Object path -ne $IgnorePath)
    if($actual.Count -ne $wanted.Count){throw 'Player files changed since the deployment checkpoint; rollback to the old cache is blocked'}
    $expectedHashes=@{};foreach($entry in $wanted){$expectedHashes[$entry.path]=$entry.sha256}
    foreach($entry in $actual){if(!$expectedHashes.ContainsKey($entry.path) -or $expectedHashes[$entry.path] -ne $entry.sha256){throw 'Player progress changed; keep the242 release stopped for reviewed recovery instead of starting the old cache'}}
}
function Owner-Grant([string]$Action,[int]$StoppedPid){
    if(!$manifest.ownerGrant){return}
    Assert-ServersStopped
    $grant=$manifest.ownerGrant
    foreach($path in @($grant.playerSave,$grant.accountRegistry)){Assert-Contained $path $runtime}
    $arguments=@{Python=$python;Stage=$bundlePath;Payload="$bundlePath/owner-grant/payload.json";PlayerSave=$grant.playerSave}
    if($Action -ne 'Preflight'){
        $arguments.AccountRegistry=$grant.accountRegistry
        $arguments.GameJar=Join-Path $runtime "server-releases/$($manifest.releaseName)/lib/kronos-server-1.0.jar"
        $arguments.ExpectedGameJarSha256=$manifest.bindings.serverJarSha256
        $arguments.ReleasePointer=Join-Path $runtime 'game-server-release.txt'
        $arguments.StoppedGamePid=$StoppedPid;$arguments.Confirm242Deployment=$true
        $arguments.ExpectedPayloadSha256=$grant.files.'owner-grant/payload.json'
        $arguments[$Action]=$true
    }
    & "$bundlePath/scripts/Apply-OwnerGrant242.ps1" @arguments
    if(!$? -or $LASTEXITCODE){throw "Owner grant $Action failed closed"}
}
function Undo-OwnerGrant($State,[int]$StoppedPid){
    if(!$State -or !$State.attempted){return}
    if((File-Sha $manifest.ownerGrant.playerSave) -eq $State.playerBeforeSha256){return}
    $payload=Get-Content -LiteralPath "$bundlePath/owner-grant/payload.json" -Raw | ConvertFrom-Json
    $journalPath=Join-Path $runtime "player-data/content242-owner-grants/$($payload.account)/$($payload.grantId)/grant-journal.json"
    if(!(Test-Path -LiteralPath $journalPath)){throw 'Owner save differs without a grant journal; rollback is blocked'}
    $grantJournal=Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
    if($grantJournal.beforeSha256 -ne $State.playerBeforeSha256){throw 'Grant journal belongs to an earlier account state; rollback is blocked'}
    Owner-Grant 'CheckRollback' $StoppedPid
    Owner-Grant 'Rollback' $StoppedPid
    if((File-Sha $manifest.ownerGrant.playerSave) -ne $State.playerBeforeSha256){throw 'Owner rollback could not be verified'}
}
function Server-Identity([string]$Role){
    $name=if($Role -eq 'game'){'game-server'}else{'update-server'};$main=if($Role -eq 'game'){'io.ruin.Server'}else{'io.ruin.update.Server'}
    $file=Join-Path $runtime "$name.pid"
    if(!(Test-Path -LiteralPath $file)){return $null}
    $serverPid=[int](Get-Content -LiteralPath $file);$p=Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    if(!$p){return $null}
    if($p.ExecutablePath -ne $java -or !$p.CommandLine.Trim().EndsWith($main) -or $p.CommandLine.Contains('kronos.content242.test')){throw 'Unexpected production process identity; nothing stopped'}
    [pscustomobject]@{pid=$serverPid;started=(Get-Process -Id $serverPid).StartTime.ToUniversalTime().Ticks;pidFile=$file;command=$p.CommandLine}
}
function Assert-ConfiguredPaths{
    foreach($pair in @(@('cache_path',$liveCache),@('data_path',$liveData))){
        $lines=@(Get-Content -LiteralPath "$project/server.properties" | Where-Object {$_ -match ('^'+$pair[0]+'=')})
        if($lines.Count -ne 1){throw 'Ambiguous live Cache/data configuration'}
        $value=$lines[0].Substring($lines[0].IndexOf('=')+1).Trim().Replace('\\','\')
        if(![IO.Path]::IsPathRooted($value)){$value=Join-Path $project $value}
        if([IO.Path]::GetFullPath($value).TrimEnd('\','/') -ne $pair[1]){throw 'Live Cache/data paths differ from the reviewed layout'}
    }
}
function Compile-StopHelpers([string]$Folder){
    New-Item -ItemType Directory -Path $Folder | Out-Null
    & "$javaHome/bin/javac.exe" -J-Xmx128m -cp "$javaHome/lib/tools.jar" -d $Folder "$bundlePath/scripts/RestartKronos.java" "$bundlePath/scripts/Content242Shutdown.java"
    if($LASTEXITCODE){throw 'Normal shutdown helper compilation failed before any process change'}
    foreach($agent in @('RestartKronos','Content242Shutdown')){
        [IO.File]::WriteAllText("$Folder/$agent.mf","Manifest-Version: 1.0`nAgent-Class: $agent`n`n",[Text.Encoding]::ASCII)
        & "$javaHome/bin/jar.exe" -J-Xmx64m cfm "$Folder/$agent.jar" "$Folder/$agent.mf" -C $Folder "$agent.class"
        if($LASTEXITCODE){throw 'Normal shutdown helper packaging failed'}
    }
}
function Stop-Server([string]$Role,[string]$Helpers,$Expected=$null,[bool]$Countdown=$true){
    $p=Server-Identity $Role;if(!$p){return}
    if($Expected -and ($p.pid -ne $Expected.pid -or $p.started -ne $Expected.started)){throw 'Process changed since preflight; nothing stopped'}
    if($Role -eq 'game' -and $Countdown){& $java -Xmx64m -cp "$Helpers;$javaHome/lib/tools.jar" RestartKronos $p.pid "$Helpers/RestartKronos.jar"}
    else{& $java -Xmx64m -cp "$Helpers;$javaHome/lib/tools.jar" Content242Shutdown $p.pid "$Helpers/Content242Shutdown.jar" $Role}
    if($LASTEXITCODE){throw 'Normal shutdown request failed'}
    $deadline=[DateTime]::UtcNow.AddSeconds(140)
    while($true){$active=Get-Process -Id $p.pid -ErrorAction SilentlyContinue;if(!$active -or $active.StartTime.ToUniversalTime().Ticks -ne $p.started){break};if([DateTime]::UtcNow -gt $deadline){throw 'Normal shutdown timed out; no forced kill performed'};Start-Sleep -Milliseconds 500}
    if(Test-Path -LiteralPath $p.pidFile){Remove-Item -LiteralPath $p.pidFile}
}
function Handshake([int]$Port,[byte[]]$Payload,[int]$Expected){
    $client=[Net.Sockets.TcpClient]::new()
    try{$client.ReceiveTimeout=10000;$client.SendTimeout=10000;$client.Connect('127.0.0.1',$Port);$s=$client.GetStream();$s.Write($Payload,0,$Payload.Length);$value=$s.ReadByte();if($value -ne $Expected){throw "Handshake on $Port returned $value, expected $Expected"}}
    finally{$client.Dispose()}
}
function Test-NewProtocol{
    # JS5 request opcode15 plus big-endian184242; legacy184 must be rejected.
    Handshake 7304 ([byte[]]@(15,0,2,207,178)) 0
    Handshake 7304 ([byte[]]@(15,0,0,0,184)) 6
    Handshake 13302 ([byte[]]@(14)) 0
}
function Restore-Selection([string]$Backup){
    foreach($name in @('game-server-release.txt','content242-active.json')){
        $saved=Join-Path "$Backup/runtime" $name;$target=Join-Path $runtime $name
        if(Test-Path -LiteralPath $saved){Copy-Item -LiteralPath $saved -Destination $target -Force}
        elseif(Test-Path -LiteralPath $target){Remove-Item -LiteralPath $target}
    }
    Copy-Item -LiteralPath "$Backup/Start-Kronos.ps1" -Destination "$workspace/Start-Kronos.ps1" -Force
}
Assert-ConfiguredPaths
if($Mode -eq 'Rollback'){
    if(!$RollbackReceipt){throw 'Rollback requires the private APPLIED.json from this transaction'}
    $receiptPath=(Resolve-Path -LiteralPath $RollbackReceipt).Path;Assert-Contained $receiptPath (Join-Path $workspace 'server-backups')
    $r=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
    if($r.bundle -ne $bundlePath -or $r.manifestSha256 -ne (File-Sha "$bundlePath/release-manifest.json")){throw 'Rollback receipt does not match this bundle'}
    $backup=Split-Path $receiptPath -Parent
    Assert-BaselineBinaries "$backup/Start-Kronos.ps1"
    foreach($path in @($r.oldCache,$r.oldData)){Assert-Contained $path $project}
    Assert-Tree $r.oldCache $manifest.baseline.Cache;Assert-Tree $r.oldData $manifest.baseline.data
    $current=Get-Content -LiteralPath "$runtime/content242-active.json" -Raw | ConvertFrom-Json
    if($current.bundle -ne $bundlePath){throw 'Another release is selected; rollback refused'}
    $helpers="$backup/rollback-helper-$(Get-Date -Format 'yyyyMMdd-HHmmss')";Compile-StopHelpers $helpers
    $rollbackGame=Server-Identity 'game';$stoppedPid=if($rollbackGame){$rollbackGame.pid}else{$r.gamePid}
    Stop-Server 'game' $helpers;Stop-Server 'cache' $helpers
    Assert-ServersStopped
    # No old-cache restart is allowed after any player has progressed on242.
    # The grant journal itself is kept separately and excluded below.
    $ignoreOwner=if($r.ownerGrant -and $r.ownerGrant.attempted){'players/owner.json'}else{''}
    Assert-PrivatePlayerState $r.playerStateAfter $ignoreOwner
    if((File-Sha "$runtime/accounts.properties") -ne $r.accountRegistrySha256){throw 'Account registry changed after deployment; reviewed recovery is required'}
    Undo-OwnerGrant $r.ownerGrant $stoppedPid
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss';$failedCache="$liveCache.rolled-back-$stamp";$failedData="$liveData.rolled-back-$stamp"
    foreach($path in @($failedCache,$failedData)){Assert-Contained $path $project;if(Test-Path -LiteralPath $path){throw 'Rollback retention path exists'}}
    Move-Item -LiteralPath $liveCache -Destination $failedCache;Move-Item -LiteralPath $r.oldCache -Destination $liveCache
    Move-Item -LiteralPath $liveData -Destination $failedData;Move-Item -LiteralPath $r.oldData -Destination $liveData
    Restore-Selection $backup
    & "$workspace/Start-Kronos.ps1" -NoClient
    if(!$?){throw 'Original launcher failed; retained private backups require inspection'}
    'Original server selection and Cache/data restored. Current player/account state was preserved; private pre-change copies remain available.'
    return
}
Assert-Tree $liveCache $manifest.baseline.Cache;Assert-Tree $liveData $manifest.baseline.data
Assert-BaselineBinaries "$workspace/Start-Kronos.ps1"
if((Get-Content -LiteralPath "$runtime/game-server-release.txt" -Raw).Trim() -ne $manifest.baselineRelease){throw 'Original release selection changed since sealing'}
$game=Server-Identity 'game';$cache=Server-Identity 'cache'
if(!$game -or !$cache){throw 'Both original processes must be identified before an apply transaction'}
if(!$game.command.Replace('\','/').ToLowerInvariant().Contains($manifest.baselineBinaries.game.path.Replace('\','/').ToLowerInvariant())){throw 'Running game classpath does not match the sealed baseline release'}
if(!$cache.command.Replace('\','/').ToLowerInvariant().Contains($manifest.baselineBinaries.updater.path.Replace('\','/').ToLowerInvariant())){throw 'Running updater classpath does not match the sealed baseline updater'}
$os=Get-CimInstance Win32_OperatingSystem;$memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
$reclaimPhysical=0;$reclaimCommit=0
foreach($p in @($game,$cache)){$process=Get-Process -Id $p.pid;$reclaimPhysical+=$process.WorkingSet64;$reclaimCommit+=$process.PrivateMemorySize64}
$physical=[math]::Floor($os.FreePhysicalMemory/1024+$reclaimPhysical/1MB);$commit=[math]::Floor(($memory.CommitLimit-$memory.CommittedBytes+$reclaimCommit)/1MB)
$required=$GameHeapMb+512+1024
if($physical -lt $required -or $commit -lt $required){throw "Capacity preflight failed even after estimated original-server reclamation: required=$required MB, physical=$physical MB, commit=$commit MB. Original processes unchanged."}
if($manifest.ownerGrant){
    # Read-only composition check is safe while the current game is still up;
    # the helper repeats identity/capacity checks after normal save/stop.
    & "$bundlePath/scripts/Apply-OwnerGrant242.ps1" -Python $python -Stage $bundlePath -Payload "$bundlePath/owner-grant/payload.json" -PlayerSave $manifest.ownerGrant.playerSave
    if(!$? -or $LASTEXITCODE){throw 'Owner grant read-only preflight failed'}
}
if($Mode -eq 'Preflight'){'Preflight passed. No process, launcher, live data, account, player, or client profile changed.';return}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$backup=Join-Path $workspace "server-backups/before-content242-full-$stamp"
$newCache="$liveCache.content242-new-$stamp";$oldCache="$liveCache.content242-old-$stamp";$failedCache="$liveCache.content242-failed-$stamp"
$newData="$liveData.content242-new-$stamp";$oldData="$liveData.content242-old-$stamp";$failedData="$liveData.content242-failed-$stamp"
foreach($path in @($backup,$newCache,$oldCache,$failedCache,$newData,$oldData,$failedData)){Assert-Contained $path $workspace;if(Test-Path -LiteralPath $path){throw 'Transaction path already exists'}}
New-Item -ItemType Directory -Path $backup | Out-Null;Restrict-Private $backup
New-Item -ItemType Directory -Path "$backup/runtime" | Out-Null
Copy-Tree "$bundlePath/Cache" $newCache;Copy-Tree "$bundlePath/data" $newData
Assert-Tree $newCache $manifest.trees.Cache;Assert-Tree $newData $manifest.trees.data
Copy-Item -LiteralPath "$workspace/Start-Kronos.ps1","$project/server.properties","$bundlePath/release-manifest.json" -Destination $backup
foreach($name in @('accounts.properties','network.json','adventurers.properties','game-server-release.txt','content242-active.json')){if(Test-Path -LiteralPath "$runtime/$name"){Copy-Item -LiteralPath "$runtime/$name" -Destination "$backup/runtime"}}
$helpers="$backup/shutdown-helper";Compile-StopHelpers $helpers
$journal=[ordered]@{schemaVersion=1;bundle=$bundlePath;manifestSha256=(File-Sha "$bundlePath/release-manifest.json");startedUtc=[DateTime]::UtcNow.ToString('o');oldCache=$oldCache;oldData=$oldData;newCache=$newCache;newData=$newData;phase='prepared';gamePid=$game.pid;cachePid=$cache.pid}
function Save-Journal([string]$Phase){$journal.phase=$Phase;$journal | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$backup/TRANSACTION.json" -Encoding UTF8}
Save-Journal 'prepared'
$stopping=$false;$cacheMoved=$false;$dataMoved=$false;$selectionChanged=$false
$ownerState=$null;$playerStateBefore=$null;$playerStateAfter=$null;$registryCheckpoint=$null
try{
    Save-Journal 'normal-save-and-stop'
    $stopping=$true;Stop-Server 'game' $helpers $game;Stop-Server 'cache' $helpers $cache
    Assert-ServersStopped
    # Fresh private state is captured after the normal logout/save. It is never
    # replaced by staged accounts, test players, or public client distribution.
    foreach($name in @('player-data','home/Desktop/kronos')){if(Test-Path -LiteralPath "$runtime/$name"){Copy-Tree "$runtime/$name" "$backup/runtime/$name"}}
    foreach($name in @('accounts.properties','network.json','adventurers.properties')){if(Test-Path -LiteralPath "$runtime/$name"){Copy-Item -LiteralPath "$runtime/$name" -Destination "$backup/runtime" -Force}}
    if(Test-Path -LiteralPath "$runtime/logs"){Copy-Tree "$runtime/logs" "$backup/runtime/logs"}
    $playerStateBefore=@(Private-PlayerState);$registryCheckpoint=File-Sha "$runtime/accounts.properties"
    if(Test-Path -LiteralPath "$runtime/adventurers.properties"){
        $line=Get-Content -LiteralPath "$runtime/adventurers.properties" | Where-Object {$_ -match '^storage='} | Select-Object -First 1
        if($line){$storage=$line.Substring(8).Trim();if(![IO.Path]::IsPathRooted($storage)){$storage=Join-Path $project $storage};Assert-Contained $storage $workspace;if(Test-Path -LiteralPath $storage){Copy-Tree $storage "$backup/runtime/adventurer-storage"}}
    }
    Assert-Tree $liveCache $manifest.baseline.Cache;Assert-Tree $liveData $manifest.baseline.data
    Save-Journal 'swapping-cache-and-data'
    Move-Item -LiteralPath $liveCache -Destination $oldCache;$cacheMoved=$true;Move-Item -LiteralPath $newCache -Destination $liveCache
    Move-Item -LiteralPath $liveData -Destination $oldData;$dataMoved=$true;Move-Item -LiteralPath $newData -Destination $liveData
    $selectionChanged=$true
    Save-Journal 'selecting-new-launcher'
    [pscustomobject]@{bundle=$bundlePath;manifestSha256=(File-Sha "$bundlePath/release-manifest.json");gameHeapMb=$GameHeapMb} | ConvertTo-Json | Set-Content -LiteralPath "$runtime/content242-active.json" -Encoding UTF8
    # Keep the existing game release pointer truthful for diagnostics. The new
    # launcher pins BOTH libraries directly to the immutable bundle.
    $releaseLib=Join-Path $runtime "server-releases/$($manifest.releaseName)/lib"
    if(Test-Path -LiteralPath $releaseLib){Assert-Tree $releaseLib $manifest.trees.game}else{Copy-Tree "$bundlePath/game" $releaseLib;Assert-Tree $releaseLib $manifest.trees.game}
    Set-Content -LiteralPath "$runtime/game-server-release.txt" -Value $manifest.releaseName -Encoding ASCII
    $quoted=$bundlePath.Replace("'","''")
    [IO.File]::WriteAllText("$workspace/Start-Kronos.ps1",("param([switch]`$NoClient)`r`n& '"+$quoted+"/scripts/Start-FullContent242.ps1' -Bundle '"+$quoted+"' -GameHeapMb "+$GameHeapMb+" -NoClient:`$NoClient`r`n"),[Text.UTF8Encoding]::new($false))
    if($manifest.ownerGrant){
        Assert-ServersStopped;Assert-Tree $liveCache $manifest.trees.Cache;Assert-Tree $liveData $manifest.trees.data
        $ownerState=[ordered]@{attempted=$true;playerBeforeSha256=(File-Sha $manifest.ownerGrant.playerSave)}
        $journal.ownerGrant=$ownerState;Save-Journal 'applying-authorized-owner-grant'
        Owner-Grant 'Apply' $game.pid
        $ownerState.playerAfterSha256=File-Sha $manifest.ownerGrant.playerSave
    }
    $playerStateAfter=@(Private-PlayerState)
    $journal.playerStateAfter=$playerStateAfter;$journal.accountRegistrySha256=$registryCheckpoint
    Save-Journal 'private-state-checkpoint-before-start'
    & "$workspace/Start-Kronos.ps1" -NoClient
    if(!$?){throw 'Selected server launcher failed'}
    Test-NewProtocol
    Save-Journal 'protocol-passed'
    Assert-Tree $liveCache $manifest.trees.Cache;Assert-Tree $liveData $manifest.trees.data
    [pscustomobject]@{schemaVersion=1;bundle=$bundlePath;manifestSha256=(File-Sha "$bundlePath/release-manifest.json");appliedUtc=[DateTime]::UtcNow.ToString('o');oldCache=$oldCache;oldData=$oldData;privateStatePreserved=$true;protocolPassed=$true;ownerGrant=$ownerState;playerStateAfter=$playerStateAfter;accountRegistrySha256=$registryCheckpoint;gamePid=(Server-Identity 'game').pid} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$backup/APPLIED.json" -Encoding UTF8
    Save-Journal 'applied'
    "Applied exact sealed release. Private backup and rollback receipt: $backup/APPLIED.json. Versioned Windows and Android outputs remain in the bundle."
}catch{
    $failure=$_.Exception.Message
    Save-Journal 'rollback-required'
    try{
        if($cacheMoved -or $dataMoved -or $selectionChanged){
            Stop-Server 'game' $helpers $null $false;Stop-Server 'cache' $helpers $null $false
            Assert-ServersStopped
            Assert-BaselineBinaries "$backup/Start-Kronos.ps1"
            if($playerStateAfter){$ignoreOwner=if($ownerState -and $ownerState.attempted){'players/owner.json'}else{''};Assert-PrivatePlayerState $playerStateAfter $ignoreOwner}
            elseif($playerStateBefore){$ignoreOwner=if($ownerState -and $ownerState.attempted){'players/owner.json'}else{''};Assert-PrivatePlayerState $playerStateBefore $ignoreOwner}
            if($registryCheckpoint -and (File-Sha "$runtime/accounts.properties") -ne $registryCheckpoint){throw 'Account registry changed; old-cache rollback is blocked'}
            Undo-OwnerGrant $ownerState $game.pid
            if($cacheMoved){if(Test-Path -LiteralPath $liveCache){Move-Item -LiteralPath $liveCache -Destination $failedCache};Move-Item -LiteralPath $oldCache -Destination $liveCache}
            if($dataMoved){if(Test-Path -LiteralPath $liveData){Move-Item -LiteralPath $liveData -Destination $failedData};Move-Item -LiteralPath $oldData -Destination $liveData}
            if($selectionChanged){Restore-Selection $backup}
        }
        if($stopping){& "$workspace/Start-Kronos.ps1" -NoClient;if(!$?){throw 'Original server restart failed'}}
        Save-Journal 'original-selection-restored'
    }catch{throw "Transaction and rollback need inspection: $($_.Exception.Message). Retained private backup: $backup. Original failure: $failure"}
    throw "Transaction aborted and original selection restored: $failure. Private backup: $backup"
}
