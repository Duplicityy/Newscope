param(
    [switch]$Prepare,
    [switch]$Apply,
    [string]$ContentPath='',
    [ValidatePattern('^[a-z0-9-]+$')][string]$TestStageName='content242-test'
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$project=Join-Path $root 'kronos-osrs-184/Kronos-master'
$runtime=Join-Path $root 'local-runtime'
if(!$ContentPath){$ContentPath=Join-Path $runtime 'content242-stage'}
$content=(Resolve-Path -LiteralPath $ContentPath).Path
$manifestPath=Join-Path $content 'deployment-manifest.json'
if($Prepare -and $Apply){throw 'Choose Prepare or Apply, never both'}
function Assert-Contained([string]$Path,[string]$Parent){
    $full=[IO.Path]::GetFullPath($Path)
    $prefix=[IO.Path]::GetFullPath($Parent).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if(!$full.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'A deployment path is outside its approved parent'}
    $cursor=$full
    while($cursor -and $cursor.Length -ge $prefix.Length){
        if(Test-Path -LiteralPath $cursor){if((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Deployment paths cannot use junctions or symlinks'}}
        $cursor=Split-Path $cursor -Parent
    }
}
Assert-Contained $content $runtime
function Shared-FileHash([string]$Path){
    $before=Get-Item -LiteralPath $Path -Force
    $lengthBefore=$before.Length
    $writtenBefore=$before.LastWriteTimeUtc.Ticks
    $stream=$null;$algorithm=$null
    try{
        # Java RandomAccessFile keeps the cache open for writing even when serving
        # it read-only. Share access with that handle without opening for writes.
        $stream=[IO.FileStream]::new($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
        if($stream.Length -ne $lengthBefore){throw 'Content length changed before hashing'}
        $algorithm=[Security.Cryptography.SHA256]::Create()
        $hash=[BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','')
    }finally{
        if($stream){$stream.Dispose()}
        if($algorithm){$algorithm.Dispose()}
    }
    $after=Get-Item -LiteralPath $Path -Force
    if($after.Length -ne $lengthBefore -or $after.LastWriteTimeUtc.Ticks -ne $writtenBefore){throw "Content changed while hashing: $Path"}
    [pscustomobject]@{bytes=$lengthBefore;sha256=$hash}
}
function Tree-Manifest([string]$Base){
    if(Get-ChildItem -LiteralPath $Base -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue){throw 'Content/release trees cannot contain junctions or symlinks'}
    @(Get-ChildItem -LiteralPath $Base -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        $fingerprint=Shared-FileHash $_.FullName
        [pscustomobject]@{path=[IO.Path]::GetRelativePath($Base,$_.FullName).Replace('\','/');bytes=$fingerprint.bytes;sha256=$fingerprint.sha256}
    })
}
function Assert-Tree([string]$Base,$Entries){
    $actual=@(Tree-Manifest $Base)
    if($actual.Count -ne @($Entries).Count){throw "Content file count changed: $Base"}
    $expected=@{};foreach($entry in $Entries){$expected[$entry.path]=$entry.sha256}
    foreach($entry in $actual){if(!$expected.ContainsKey($entry.path) -or $expected[$entry.path] -ne $entry.sha256){throw "Content hash changed: $($entry.path)"}}
}
function Get-Server([string]$Role){
    $name=if($Role -eq 'game'){'game-server'}else{'update-server'}
    $main=if($Role -eq 'game'){'io.ruin.Server'}else{'io.ruin.update.Server'}
    $port=if($Role -eq 'game'){13302}else{7304}
    $pidFile=Join-Path $runtime "$name.pid"
    if(!(Test-Path -LiteralPath $pidFile)){return $null}
    $serverPid=[int](Get-Content -LiteralPath $pidFile)
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    if(!$process){return $null}
    if(!$process.CommandLine.Trim().EndsWith($main) -or $process.CommandLine.Contains('kronos.content242.test') -or !$process.ExecutablePath.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){throw "Unexpected $Role PID identity"}
    [pscustomobject]@{pid=$serverPid;startedUtc=(Get-Process -Id $serverPid).StartTime.ToUniversalTime().ToString('o');port=$port;pidFile=$pidFile;main=$main}
}
function Config-Path([string]$Key){
    $line=Get-Content -LiteralPath "$project/server.properties" | Where-Object {$_ -match ('^'+[regex]::Escape($Key)+'=')} | Select-Object -First 1
    if(!$line){throw "Missing server path property: $Key"}
    $value=$line.Substring($line.IndexOf('=')+1).Trim().Replace('\\','\')
    if(![IO.Path]::IsPathRooted($value)){$value=Join-Path $project $value}
    [IO.Path]::GetFullPath($value).TrimEnd('\','/')
}
$liveCache=[IO.Path]::GetFullPath((Join-Path $project 'Cache'))
$liveData=[IO.Path]::GetFullPath((Join-Path $project 'kronos-server/data'))
$releaseFile=Join-Path $runtime 'game-server-release.txt'
if((Config-Path 'cache_path') -ne $liveCache -or (Config-Path 'data_path') -ne $liveData){throw 'Current server config uses unexpected Cache/data paths; deployment refused'}
foreach($path in @($liveCache,$liveData,(Join-Path $content 'Cache'),(Join-Path $content 'data'))){Assert-Contained $path $root;if(!(Test-Path -LiteralPath $path -PathType Container)){throw 'Cache/data input directory missing'}}
if($Prepare){
    $test=Join-Path $runtime $TestStageName
    $testManifest=Get-Content -LiteralPath "$test/content242-test.json" -Raw | ConvertFrom-Json
    if($testManifest.content -ne $content){throw 'The tested content directory does not match this deployment'}
    if(!(Select-String -LiteralPath "$test/client.out.log" -SimpleMatch 'CONTENT242 SMOKE PASS' -Quiet)){throw 'Actual revision184 home/gear/map smoke test has not passed'}
    foreach($phase in @('home','gear','map')){if(!(Test-Path -LiteralPath "$test/captures/$phase.png")){throw "Missing verified $phase screenshot"}}
    $game=Get-Server 'game';$cacheServer=Get-Server 'cache'
    if(!$game -or !$cacheServer){throw 'Both original live server processes must be running when sealing deployment'}
    $releaseLib=[IO.Path]::GetFullPath($testManifest.gameLib)
    Assert-Contained $releaseLib (Join-Path $runtime 'server-releases')
    $releaseName=Split-Path (Split-Path $releaseLib -Parent) -Leaf
    if($releaseName -notmatch '^[a-zA-Z0-9_-]+$' -or (Split-Path $releaseLib -Leaf) -ne 'lib' -or !(Get-ChildItem -LiteralPath $releaseLib -Filter 'kronos-server*.jar')){throw 'Tested server release is incomplete'}
    $oldRelease=if(Test-Path -LiteralPath $releaseFile){(Get-Content -LiteralPath $releaseFile -Raw).Trim()}else{''}
    [pscustomobject]@{
        version=1;content=$content;preparedUtc=[DateTime]::UtcNow.ToString('o');testStage=$TestStageName
        targetCache=$liveCache;targetData=$liveData;originalGame=$game;originalCacheServer=$cacheServer
        stagedCache=@(Tree-Manifest "$content/Cache");stagedData=@(Tree-Manifest "$content/data")
        originalCache=@(Tree-Manifest $liveCache);originalData=@(Tree-Manifest $liveData)
        clientSha256=$testManifest.clientSha256
        serverRelease=$releaseName;serverReleaseLib=$releaseLib;serverReleaseFiles=@(Tree-Manifest $releaseLib)
        originalReleaseExists=(Test-Path -LiteralPath $releaseFile);originalReleaseName=$oldRelease
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    "Prepared deployment manifest. No live process or file changed. Apply later with: tools/content242/Apply-Content242.ps1 -Apply -ContentPath `"$content`""
    return
}
if(!$Apply){"No deployment performed. -Prepare seals passed staging tests; -Apply is the explicit one-step production import.";return}
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if($manifest.version -ne 1 -or $manifest.content -ne $content -or $manifest.targetCache -ne $liveCache -or $manifest.targetData -ne $liveData){throw 'Deployment manifest identity mismatch'}
Assert-Tree "$content/Cache" $manifest.stagedCache
Assert-Tree "$content/data" $manifest.stagedData
Assert-Tree $liveCache $manifest.originalCache
Assert-Tree $liveData $manifest.originalData
Assert-Contained $manifest.serverReleaseLib (Join-Path $runtime 'server-releases')
Assert-Tree $manifest.serverReleaseLib $manifest.serverReleaseFiles
$currentRelease=if(Test-Path -LiteralPath $releaseFile){(Get-Content -LiteralPath $releaseFile -Raw).Trim()}else{''}
if($currentRelease -ne $manifest.originalReleaseName -or (Test-Path -LiteralPath $releaseFile) -ne $manifest.originalReleaseExists){throw 'Server release selection changed since sealing'}
foreach($role in @('game','cache')){
    $current=Get-Server $role
    $expected=if($role -eq 'game'){$manifest.originalGame}else{$manifest.originalCacheServer}
    if(!$current -or $current.pid -ne $expected.pid -or ([DateTime]$current.startedUtc).ToUniversalTime().Ticks -ne ([DateTime]$expected.startedUtc).ToUniversalTime().Ticks){throw 'Live process changed since sealing; prepare a fresh reviewed manifest'}
}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$backup=Join-Path $root "server-backups/before-content242-$stamp"
$newCache="$liveCache.content242-new-$stamp";$oldCache="$liveCache.content242-old-$stamp"
$newData="$liveData.content242-new-$stamp";$oldData="$liveData.content242-old-$stamp"
foreach($path in @($backup,$newCache,$oldCache,$newData,$oldData,"$liveCache.content242-failed-$stamp","$liveData.content242-failed-$stamp")){Assert-Contained $path $root;if(Test-Path -LiteralPath $path){throw 'Deployment backup/candidate path already exists'}}
function Copy-Tree([string]$From,[string]$To){
    & robocopy.exe $From $To /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if($LASTEXITCODE -ge 8){throw "Failed to copy directory: $From"}
}
New-Item -ItemType Directory -Path $backup -Force | Out-Null
Copy-Tree "$content/Cache" $newCache;Copy-Tree "$content/data" $newData
Assert-Tree $newCache $manifest.stagedCache;Assert-Tree $newData $manifest.stagedData
Copy-Tree $liveCache "$backup/Cache";Copy-Tree $liveData "$backup/data"
Copy-Item -LiteralPath "$project/server.properties",$manifestPath -Destination $backup
Assert-Tree "$backup/Cache" $manifest.originalCache;Assert-Tree "$backup/data" $manifest.originalData
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$helper=Join-Path $backup 'shutdown-helper'
New-Item -ItemType Directory -Path $helper -Force | Out-Null
& "$jdk/bin/javac.exe" -J-Xmx128m -cp "$jdk/lib/tools.jar" -d $helper "$root/tools/RestartKronos.java" "$PSScriptRoot/Content242Shutdown.java"
if($LASTEXITCODE){throw 'Graceful shutdown helper compile failed; live server untouched'}
foreach($agent in @('RestartKronos','Content242Shutdown')){
    [IO.File]::WriteAllText("$helper/$agent.mf","Manifest-Version: 1.0`nAgent-Class: $agent`n`n",[Text.Encoding]::ASCII)
    & "$jdk/bin/jar.exe" -J-Xmx64m cfm "$helper/$agent.jar" "$helper/$agent.mf" -C $helper "$agent.class"
    if($LASTEXITCODE){throw 'Shutdown helper packaging failed; live server untouched'}
}
function Stop-Verified([string]$Role,[bool]$Countdown=$true,$ExpectedIdentity=$null){
    $server=Get-Server $Role;if(!$server){return}
    if($ExpectedIdentity -and ($server.pid -ne $ExpectedIdentity.pid -or ([DateTime]$server.startedUtc).ToUniversalTime().Ticks -ne ([DateTime]$ExpectedIdentity.startedUtc).ToUniversalTime().Ticks)){throw "Original $Role process changed during backup; no shutdown requested"}
    if($Role -eq 'game' -and $Countdown){
        & "$jdk/bin/java.exe" -Xmx64m -cp "$helper;$jdk/lib/tools.jar" RestartKronos $server.pid "$helper/RestartKronos.jar"
    }else{
        & "$jdk/bin/java.exe" -Xmx64m -cp "$helper;$jdk/lib/tools.jar" Content242Shutdown $server.pid "$helper/Content242Shutdown.jar" $Role
    }
    if($LASTEXITCODE){throw "Graceful $Role shutdown request failed"}
    $deadline=[DateTime]::UtcNow.AddSeconds(130)
    while($true){
        $candidate=Get-Process -Id $server.pid -ErrorAction SilentlyContinue
        if(!$candidate -or $candidate.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$server.startedUtc).ToUniversalTime().Ticks){break}
        if([DateTime]::UtcNow -gt $deadline){throw "Graceful $Role shutdown timed out; no forced kill performed"}
        Start-Sleep -Milliseconds 500
    }
    if(Test-Path -LiteralPath $server.pidFile){Remove-Item -LiteralPath $server.pidFile}
}
$shutdownStarted=$false;$cacheMoved=$false;$dataMoved=$false;$releaseChanged=$false
try{
    $shutdownStarted=$true
    Stop-Verified 'game' $true $manifest.originalGame
    Stop-Verified 'cache' $false $manifest.originalCacheServer
    # Capture final saved player/bot state privately after normal game logout/save.
    foreach($name in @('player-data','home/Desktop/kronos')){if(Test-Path -LiteralPath (Join-Path $runtime $name)){Copy-Tree (Join-Path $runtime $name) (Join-Path "$backup/runtime" $name)}}
    New-Item -ItemType Directory -Path "$backup/runtime" -Force | Out-Null
    foreach($name in @('accounts.properties','network.json','adventurers.properties','game-server-release.txt')){if(Test-Path -LiteralPath (Join-Path $runtime $name)){Copy-Item -LiteralPath (Join-Path $runtime $name) -Destination "$backup/runtime"}}
    $adventurerConfig=Join-Path $runtime 'adventurers.properties'
    if(Test-Path -LiteralPath $adventurerConfig){
        $storage=Get-Content -LiteralPath $adventurerConfig | Where-Object {$_ -match '^storage='} | Select-Object -First 1
        if($storage){$storage=$storage.Substring(8).Trim();if(![IO.Path]::IsPathRooted($storage)){$storage=Join-Path $project $storage};Assert-Contained $storage $root;if(Test-Path -LiteralPath $storage){Copy-Tree $storage "$backup/runtime/adventurer-storage"}}
    }
    Assert-Tree $liveCache $manifest.originalCache;Assert-Tree $liveData $manifest.originalData
    # These four exact paths were resolved and checked inside this workspace above.
    Move-Item -LiteralPath $liveCache -Destination $oldCache;$cacheMoved=$true
    Move-Item -LiteralPath $newCache -Destination $liveCache
    Move-Item -LiteralPath $liveData -Destination $oldData;$dataMoved=$true
    Move-Item -LiteralPath $newData -Destination $liveData
    $releaseChanged=$true
    Set-Content -LiteralPath $releaseFile -Value $manifest.serverRelease -Encoding ASCII
    & "$root/Start-Kronos.ps1" -NoClient
    & "$root/Test-Kronos.ps1"
    Assert-Tree $liveCache $manifest.stagedCache;Assert-Tree $liveData $manifest.stagedData
    [pscustomobject]@{appliedUtc=[DateTime]::UtcNow.ToString('o');backup=$backup;oldCache=$oldCache;oldData=$oldData;verified=$true} | ConvertTo-Json | Set-Content -LiteralPath "$backup/APPLIED.json"
    "Content242 Cache/data and tested immutable server release selected; handshakes verified. Player, account, home and bot state were preserved. Backup: $backup"
}catch{
    $failure=$_.Exception.Message
    if($cacheMoved -or $dataMoved -or $releaseChanged){
        try{
            Stop-Verified 'game' $false;Stop-Verified 'cache' $false
            if($cacheMoved){if(Test-Path -LiteralPath $liveCache){Move-Item -LiteralPath $liveCache -Destination "$liveCache.content242-failed-$stamp"};Move-Item -LiteralPath $oldCache -Destination $liveCache}
            if($dataMoved){if(Test-Path -LiteralPath $liveData){Move-Item -LiteralPath $liveData -Destination "$liveData.content242-failed-$stamp"};Move-Item -LiteralPath $oldData -Destination $liveData}
            if($releaseChanged){
                if($manifest.originalReleaseExists){Set-Content -LiteralPath $releaseFile -Value $manifest.originalReleaseName -Encoding ASCII}
                elseif(Test-Path -LiteralPath $releaseFile){Remove-Item -LiteralPath $releaseFile}
            }
            & "$root/Start-Kronos.ps1" -NoClient
            & "$root/Test-Kronos.ps1"
        }catch{throw "Deployment/rollback requires inspection: $($_.Exception.Message). All retained directories and private backup: $backup"}
        throw "Deployment failed and original Cache/data/server selection was restored: $failure. Private backup: $backup"
    }
    if($shutdownStarted){& "$root/Start-Kronos.ps1" -NoClient}
    throw "Deployment stopped before swapping content: $failure. Backup: $backup"
}
