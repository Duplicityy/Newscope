$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$backup=Join-Path $root "server-backups/before-autonomous-$stamp"
$jdk=(Get-ChildItem "$root/tools/jdk8" -Directory | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force "$backup/server","$backup/runtime","$backup/launchers","$backup/tools","$root/local-runtime/snapshot-tool" | Out-Null
function CopyTree($source,$destination,$exclude=@()){
 $arguments=@($source,$destination,'/E','/COPY:DAT','/DCOPY:DAT','/R:2','/W:1','/NFL','/NDL','/NJH','/NJS','/NP')
 if($exclude.Count){$arguments+='/XD';$arguments+=$exclude}
 & robocopy.exe @arguments
 if($LASTEXITCODE -ge 8){throw "Backup copy failed: $source"}
}
CopyTree "$root/kronos-osrs-184" "$backup/server" @('.gradle','.idea')
CopyTree "$root/local-runtime" "$backup/runtime" @('snapshot-tool')
CopyTree "$root/tools/jdk8" "$backup/tools/jdk8"
Get-ChildItem -LiteralPath $root -File | Where-Object {$_.Extension -in @('.ps1','.cmd','.txt')} | Copy-Item -Destination "$backup/launchers"
Copy-Item -LiteralPath "$root/tools/gradle.ps1","$root/tools/install-toolchain.ps1" -Destination "$backup/tools"
$build=Join-Path $root 'local-runtime/snapshot-tool'
& "$jdk/bin/javac.exe" -cp "$jdk/lib/tools.jar" -d $build "$root/tools/SnapshotKronos.java"
if($LASTEXITCODE){throw 'Snapshot helper compilation failed'}
[IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: SnapshotKronos`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" cfm "$build/snapshot.jar" "$build/MANIFEST.MF" -C $build SnapshotKronos.class
if($LASTEXITCODE){throw 'Snapshot helper packaging failed'}
$serverPid=[int](Get-Content "$root/local-runtime/game-server.pid")
$process=Get-CimInstance Win32_Process -Filter "ProcessId = $serverPid"
if(!$process -or $process.CommandLine -notlike '*io.ruin.Server*'){throw 'Game server PID does not match'}
& "$jdk/bin/java.exe" -cp "$build/snapshot.jar;$jdk/lib/tools.jar" SnapshotKronos $serverPid "$build/snapshot.jar" $backup
if($LASTEXITCODE){throw 'Live player snapshot failed'}
Copy-Item -LiteralPath "$root/local-runtime/accounts.properties" -Destination "$backup/runtime/accounts.properties" -Force
$manifest=@(Get-ChildItem -LiteralPath $backup -File -Recurse -Force | ForEach-Object {
 [pscustomobject]@{path=[IO.Path]::GetRelativePath($backup,$_.FullName);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()}
})
$manifest | ConvertTo-Json -Depth 4 | Set-Content "$backup/SHA256-MANIFEST.json"
$instructions=@"
PRIVATE SERVER BACKUP - $stamp
Includes server source + Git history + current uncommitted files, compiled server/cache distributions, game cache and data, server configuration, account registry, all runtime player saves (including owner), and Java 8.
Any players online at snapshot time were serialized on the world executor into runtime/player-data/players; see live-snapshot.properties.
IDE metadata and disposable .gradle directories were excluded. No original source, player save, or server process was removed or stopped.
This backup contains private credentials. It is outside the public download folders and is not served by the download service.

Rollback: gracefully stop the live game server first. Restore server/ to the original workspace's kronos-osrs-184/; runtime/ to local-runtime/; launchers/ to the workspace root; tools/ to tools/. Use the original workspace location so configured absolute paths remain valid. Ignore/remove stale runtime PID files after confirming their processes are stopped. Start with Start-Kronos.ps1 -NoClient and run Test-Kronos.ps1. Restore player/account data together only when intentionally rolling back progression.
Verify SHA256-MANIFEST.json before restoring. SHA256 entries cover files captured before the manifest and this note were written.
"@
[IO.File]::WriteAllText("$backup/RESTORE.txt",$instructions)
foreach($entry in $manifest){$file=Join-Path $backup $entry.path;if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower() -ne $entry.sha256){throw "Backup verification failed: $($entry.path)"}}
[pscustomobject]@{backup=$backup;verifiedFiles=$manifest.Count;bytes=($manifest | Measure-Object bytes -Sum).Sum;liveSnapshot=(Get-Content "$backup/live-snapshot.properties" | Where-Object {$_ -notlike '#*'});private=$true} | ConvertTo-Json
Set-Content "$root/local-runtime/latest-server-backup.txt" $backup