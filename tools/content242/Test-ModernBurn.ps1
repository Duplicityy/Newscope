param([Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$CandidateLib)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$lib=(Resolve-Path $CandidateLib).Path
$out=Join-Path $PSScriptRoot 'burn-test-classes'
New-Item -ItemType Directory -Force $out | Out-Null
$sources=@('BurnTimeline','ModernBurnRules','ModernBurn','ModernGear') | ForEach-Object {Join-Path $project "kronos-server/src/main/java/io/ruin/content/content242/$_.java"}
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/combat/special/melee/Content242BurningClaws.java'
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/combat/special/ranged/Content242ScorchingBow.java'
$sources+=Join-Path $PSScriptRoot 'Burn242Regression.java'
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx256m -encoding UTF-8 -cp "$lib/*" -d $out @sources
if($LASTEXITCODE -ne 0){throw 'Detached burn fixture compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx256m -cp "$out;$lib/*" io.ruin.content.content242.Burn242Regression
if($LASTEXITCODE -ne 0){throw 'Detached burn fixture failed'}
