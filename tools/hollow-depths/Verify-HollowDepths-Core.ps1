param()
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$core=Join-Path $root 'kronos-osrs-184/Kronos-master/kronos-server/src/main/java/com/newscape/dungeons'
$output=Join-Path $root 'local-runtime/hollow-depths-core-checks'
New-Item -ItemType Directory -Path "$output/classes" -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath $core -File -Filter '*.java' | Select-Object -ExpandProperty FullName)
$sources+=@(Get-ChildItem -LiteralPath "$PSScriptRoot/tests/com/newscape/dungeons" -File -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& "$jdk/bin/javac.exe" -J-Xmx256m -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$output/classes" @sources
if($LASTEXITCODE){throw 'Actual integrated core verification compile failed'}
& "$jdk/bin/java.exe" -Xmx512m -cp "$output/classes" com.newscape.dungeons.AllChecks 2>&1 | Tee-Object -FilePath "$output/results.txt"
if($LASTEXITCODE){throw 'Actual integrated core checks failed'}
"Tested the actual integrated core on Java8; donor package was not modified. Results: $output/results.txt"
