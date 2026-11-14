param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$CandidateLib,
    [Parameter(Mandatory=$true)][string]$StageCache,
    [Parameter(Mandatory=$true)][string]$StageData
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$lib=(Resolve-Path $CandidateLib).Path
$out=Join-Path $PSScriptRoot 'test-output/charge-actions'
New-Item -ItemType Directory -Force $out | Out-Null
$sources=@('ModernCharges','Content242Registry') | ForEach-Object {Join-Path $project "kronos-server/src/main/java/io/ruin/content/content242/$_.java"}
$sources+=Join-Path $PSScriptRoot 'ChargeActions242Regression.java'
& (Join-Path $JavaHome 'bin/javac.exe') -J-XX:+UseSerialGC -J-Xmx128m -encoding UTF-8 -cp "$lib/*" -d $out @sources
if($LASTEXITCODE -ne 0){throw 'Charge action fixture compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -XX:+UseSerialGC -Xmx128m -cp "$out;$lib/*" io.ruin.content.content242.ChargeActions242Regression (Resolve-Path $StageCache).Path (Resolve-Path $StageData).Path (Join-Path $out 'result.txt')
if($LASTEXITCODE -ne 0){throw 'Charge action registration/readiness regression failed'}
