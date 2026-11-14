param([Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$UpdaterLib)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$lib=(Resolve-Path $UpdaterLib).Path
$out=Join-Path $PSScriptRoot 'js5-test-classes'
$fixture=Join-Path $PSScriptRoot ('fixtures-generated/js5-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $out | Out-Null
$sources=@('kronos-update-server/src/main/java/io/ruin/update/CacheManager.java','kronos-update-server/src/main/java/io/ruin/update/Js5Decoder.java','kronos-api/src/main/java/io/ruin/api/filestore/FileStore.java','kronos-api/src/main/java/io/ruin/api/filestore/IndexFile.java','tools/content242/Content242CacheServer.java','tools/content242/Js5CacheChecks.java') | ForEach-Object {Join-Path $project $_}
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp "$lib/*" -d $out @sources
if($LASTEXITCODE -ne 0){throw 'JS5 fixture compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -XX:MaxDirectMemorySize=32m -cp "$out;$lib/*" io.ruin.update.Js5CacheChecks $fixture
if($LASTEXITCODE -ne 0){throw 'JS5 detached checks failed'}
