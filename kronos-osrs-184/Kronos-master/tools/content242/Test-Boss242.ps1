param([Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$CandidateLib)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$base=(Resolve-Path -LiteralPath $CandidateLib).Path
$classes=Join-Path $PSScriptRoot 'test-output/boss-classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$sources=@(Get-ChildItem (Join-Path $project 'kronos-server/src/main/java/io/ruin/content/content242/bosses') -Filter '*.java' | ForEach-Object FullName)
$sources+=@('ModernBurn','ModernBurnRules','BurnTimeline') | ForEach-Object {Join-Path $project "kronos-server/src/main/java/io/ruin/content/content242/$_.java"}
$sources+=Join-Path $PSScriptRoot 'Boss242Regression.java'
$sources+=Join-Path $PSScriptRoot 'Muspah242Regression.java'
& "$JavaHome/bin/javac.exe" -J-XX:+UseSerialGC -J-Xmx128m -proc:none -encoding UTF-8 -cp "$base/*" -d $classes @sources
if($LASTEXITCODE){throw 'Boss242 regression compilation failed'}
& "$JavaHome/bin/java.exe" -XX:+UseSerialGC -Xmx96m -cp "$classes;$base/*" io.ruin.content.content242.bosses.Boss242Regression (Join-Path $PSScriptRoot 'test-output/boss-regression.txt')
if($LASTEXITCODE){throw 'Boss242 regression failed'}
& "$JavaHome/bin/java.exe" -XX:+UseSerialGC -Xmx96m -cp "$classes;$base/*" io.ruin.content.content242.bosses.Muspah242Regression (Join-Path $PSScriptRoot 'test-output/muspah-regression.txt')
if($LASTEXITCODE){throw 'Muspah242 regression failed'}
