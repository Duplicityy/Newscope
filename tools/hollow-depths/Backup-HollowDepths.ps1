param()
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$project=Join-Path $root 'kronos-osrs-184/Kronos-master'
$runtime=Join-Path $root 'local-runtime'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$backup=Join-Path $root "server-backups/before-hollow-depths-$stamp"
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$release=(Get-Content -LiteralPath "$runtime/game-server-release.txt" -Raw).Trim()
if($release -notmatch '^[a-zA-Z0-9_-]+$'){throw 'Invalid selected live release'}
$releaseSource=Join-Path $runtime "server-releases/$release"
if(!(Test-Path -LiteralPath "$releaseSource/lib")){throw 'Selected live release is missing'}
function Identity([string]$Name,[int]$Port,[string]$Main){
    $serverPid=[int](Get-Content -LiteralPath "$runtime/$Name.pid")
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    if(!$process -or !$process.CommandLine.Trim().EndsWith($Main) -or $process.CommandLine -match 'kronos\..*\.test=true'){throw "Unexpected live $Name identity"}
    if(!(Get-NetTCPConnection -State Listen -LocalPort $Port -OwningProcess $serverPid -ErrorAction SilentlyContinue)){throw "Live $Name listener is missing"}
    [pscustomobject]@{name=$Name;pid=$serverPid;port=$Port;startedUtc=(Get-Process -Id $serverPid).StartTime.ToUniversalTime().ToString('o');main=$Main}
}
$identities=@((Identity 'game-server' 13302 'io.ruin.Server'),(Identity 'update-server' 7304 'io.ruin.update.Server'))
$gameProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($identities[0].pid)"
if(!$gameProcess.CommandLine.Contains($release)){throw 'Release pointer does not match the running game server'}
if(Test-Path -LiteralPath $backup){throw 'Backup destination already exists'}
New-Item -ItemType Directory -Path $backup | Out-Null
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $backup '/inheritance:r' '/grant:r' "*$($sid):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if($LASTEXITCODE){throw 'Could not restrict private backup permissions'}
function Copy-Tree([string]$Source,[string]$Destination,[string[]]$Exclude=@()){
    if(!(Test-Path -LiteralPath $Source)){return}
    $args=@($Source,$Destination,'/E','/COPY:DAT','/DCOPY:DAT','/XJ','/R:2','/W:1','/NFL','/NDL','/NJH','/NJS','/NP')
    if($Exclude.Count){$args+='/XD';$args+=$Exclude}
    & robocopy.exe @args | Out-Null
    if($LASTEXITCODE -ge 8){throw "Backup copy failed: $Source"}
}
function Shared-Hash([string]$Path){
    $before=Get-Item -LiteralPath $Path -Force
    $bytes=$before.Length;$written=$before.LastWriteTimeUtc.Ticks
    $stream=$null;$sha=$null
    try{
        $stream=[IO.FileStream]::new($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
        $sha=[Security.Cryptography.SHA256]::Create()
        $hash=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant()
    }finally{if($stream){$stream.Dispose()};if($sha){$sha.Dispose()}}
    $after=Get-Item -LiteralPath $Path -Force
    if($after.Length -ne $bytes -or $after.LastWriteTimeUtc.Ticks -ne $written){throw "File changed while hashing: $Path"}
    [pscustomobject]@{bytes=$bytes;sha256=$hash}
}
# Source includes Git history and dirty/untracked work; generated build caches are replaceable.
Copy-Tree "$root/kronos-osrs-184" "$backup/server" @('.gradle','.idea','build','out')
New-Item -ItemType Directory -Path "$backup/evidence","$backup/runtime","$backup/launchers","$backup/tools" -Force | Out-Null
& git -C "$root/kronos-osrs-184" status --porcelain=v1 --untracked-files=all | Set-Content -LiteralPath "$backup/evidence/server-git-status.txt"
& git -C "$root/kronos-osrs-184" rev-parse HEAD | Set-Content -LiteralPath "$backup/evidence/server-git-head.txt"
& git -C "$root/kronos-osrs-184" branch --show-current | Set-Content -LiteralPath "$backup/evidence/server-git-branch.txt"
Set-Content -LiteralPath "$backup/evidence/source-copy-complete.txt" -Value ([DateTime]::UtcNow.ToString('o'))
Write-Output "Server dirty worktree captured: $backup/server"
Copy-Tree $releaseSource "$backup/runtime/server-releases/$release"
Copy-Tree "$project/kronos-update-server/build/install/kronos-update-server" "$backup/update-distribution"
Copy-Tree "$root/windows-release/Kronos-Windows/app" "$backup/desktop-app"
Copy-Tree "$root/kronos-mobile" "$backup/mobile-source" @('.gradle','.idea','build','out','.cxx','node_modules')
foreach($directory in @('player-data','autonomous-adventurers','home')){Copy-Tree "$runtime/$directory" "$backup/runtime/$directory"}
foreach($file in @('accounts.properties','adventurers.properties','network.json','game-server-release.txt')){
    if(Test-Path -LiteralPath "$runtime/$file"){Copy-Item -LiteralPath "$runtime/$file" -Destination "$backup/runtime/$file"}
}
Get-ChildItem -LiteralPath $root -File | Where-Object {$_.Extension -in '.ps1','.cmd','.txt'} | Copy-Item -Destination "$backup/launchers"
foreach($file in @('SnapshotKronos.java','RestartKronos.java','gradle.ps1','install-toolchain.ps1')){
    if(Test-Path -LiteralPath "$root/tools/$file"){Copy-Item -LiteralPath "$root/tools/$file" -Destination "$backup/tools/$file"}
}
Copy-Item -LiteralPath $PSCommandPath -Destination "$backup/tools/Backup-HollowDepths.ps1"
# Compile only the small existing snapshot helper, then attach to the validated live process.
$build=Join-Path $backup 'snapshot-tool'
New-Item -ItemType Directory -Path $build | Out-Null
& "$jdk/bin/javac.exe" -J-Xmx128m -cp "$jdk/lib/tools.jar" -d $build "$root/tools/SnapshotKronos.java"
if($LASTEXITCODE){throw 'Snapshot helper compilation failed'}
[IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: SnapshotKronos`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" cfm "$build/snapshot.jar" "$build/MANIFEST.MF" -C $build SnapshotKronos.class
if($LASTEXITCODE){throw 'Snapshot helper packaging failed'}
$gameNow=Identity 'game-server' 13302 'io.ruin.Server'
if($gameNow.pid -ne $identities[0].pid -or $gameNow.startedUtc -ne $identities[0].startedUtc){throw 'Live game process changed before snapshot'}
& "$jdk/bin/java.exe" -Xmx128m -cp "$build/snapshot.jar;$jdk/lib/tools.jar" SnapshotKronos $gameNow.pid "$build/snapshot.jar" $backup
if($LASTEXITCODE -or !(Test-Path -LiteralPath "$backup/live-snapshot.properties")){throw 'Live world snapshot failed'}
Copy-Item -LiteralPath "$runtime/accounts.properties" -Destination "$backup/runtime/accounts.properties" -Force
# Verify immutable runtime assets against their live inputs, including shared-open cache files.
$critical=@(
    @{source="$project/Cache";target="$backup/server/Kronos-master/Cache"},
    @{source="$project/kronos-server/data";target="$backup/server/Kronos-master/kronos-server/data"},
    @{source=$releaseSource;target="$backup/runtime/server-releases/$release"}
)
$criticalFiles=0
foreach($pair in $critical){
    foreach($file in Get-ChildItem -LiteralPath $pair.source -File -Recurse -Force){
        $relative=[IO.Path]::GetRelativePath($pair.source,$file.FullName)
        $original=Shared-Hash $file.FullName;$copied=Shared-Hash (Join-Path $pair.target $relative)
        if($original.bytes -ne $copied.bytes -or $original.sha256 -ne $copied.sha256){throw "Source and backup differ: $relative"}
        $criticalFiles++
    }
}
foreach($baseline in $identities){
    $current=Identity $baseline.name $baseline.port $baseline.main
    if($current.pid -ne $baseline.pid -or $current.startedUtc -ne $baseline.startedUtc){throw 'A live process changed during backup'}
}
if((Get-Content -LiteralPath "$runtime/game-server-release.txt" -Raw).Trim() -ne $release){throw 'Live release pointer changed during backup'}
$identities | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$backup/evidence/live-process-identities.json"
$notes=@"
PRIVATE verified revision-184 recovery snapshot: $stamp
Original workspace: $root
Selected live release: $release

server/ contains the server Git repository and dirty/untracked source, original184 Cache, data and server.properties. Generated build/out and IDE/Gradle caches are excluded. mobile-source/ contains the mobile Git repository and source. update-distribution/ and desktop-app/ preserve active build artifacts. runtime/ contains only the selected immutable server release, server home, accounts, player saves, autonomous checkpoints and live launch configuration. No242 stage or unrelated runtime test directory is included.
Online human saves and autonomous brain/player checkpoints were captured on the existing world executor using SnapshotKronos; see live-snapshot.properties. Offline saves were copied from disk. This is a recovery checkpoint, not a filesystem-wide atomic snapshot. The server continued serving throughout; no release pointer, live cache/data, process or live save file was replaced.

RECOVERY: coordinate a graceful live game shutdown first. Restore the selected runtime/server-releases/$release and original runtime/game-server-release.txt, server/Kronos-master/Cache and server/Kronos-master/kronos-server/data if needed; restore update-distribution to the original update-server build/install directory if required. Preserve current accounts/player/autonomous state when rolling back code only. Restore runtime/accounts.properties, player-data and autonomous-adventurers together only when intentionally rolling progression back to this snapshot. Full source recovery copies server/ to kronos-osrs-184/ and mobile-source/ to kronos-mobile/ at the original workspace, preserving a separate copy of later work. Restore launchers only when needed. Never use stale PID files. Run Start-Kronos.ps1 -NoClient, then Test-Kronos.ps1. Existing Java8 toolchain at tools/jdk8/jdk8u512-b09 is required and is not duplicated here.

PRIVATE: directory ACL permits current Windows user, SYSTEM and Administrators. Contains account credentials/private configuration and must never be published or put in download roots. SHA256-MANIFEST.json covers all payload files except itself and VERIFIED.json; VERIFIED.json seals its hash. Verify every payload hash before recovery.
"@
[IO.File]::WriteAllText("$backup/RESTORE.txt",$notes,[Text.UTF8Encoding]::new($false))
Write-Output 'Hashing and verifying private recovery snapshot.'
$manifest=@(Get-ChildItem -LiteralPath $backup -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
    $hash=Shared-Hash $_.FullName
    [pscustomobject]@{path=[IO.Path]::GetRelativePath($backup,$_.FullName).Replace('\','/');bytes=$hash.bytes;sha256=$hash.sha256}
})
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$backup/SHA256-MANIFEST.json" -Encoding UTF8
foreach($entry in $manifest){
    $actual=Shared-Hash (Join-Path $backup $entry.path)
    if($actual.bytes -ne $entry.bytes -or $actual.sha256 -ne $entry.sha256){throw "Backup verification failed: $($entry.path)"}
}
$report=[pscustomobject]@{backup=$backup;verifiedUtc=[DateTime]::UtcNow.ToString('o');files=$manifest.Count;bytes=($manifest | Measure-Object bytes -Sum).Sum;criticalFilesComparedToSource=$criticalFiles;manifestSha256=(Shared-Hash "$backup/SHA256-MANIFEST.json").sha256;release=$release;private=$true;productionUnchanged=$true;liveSnapshot=@(Get-Content -LiteralPath "$backup/live-snapshot.properties" | Where-Object {$_ -notlike '#*'})}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$backup/VERIFIED.json" -Encoding UTF8
Set-Content -LiteralPath "$runtime/latest-hollow-depths-backup.txt" -Value $backup
$report | ConvertTo-Json -Depth 5
