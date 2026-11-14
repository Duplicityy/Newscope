param([Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$CandidateLib)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$lib=(Resolve-Path $CandidateLib).Path
$out=Join-Path $PSScriptRoot 'test-output/account-roles'
New-Item -ItemType Directory -Force $out | Out-Null
$sources=@('LocalAccounts','OfflineMode') | ForEach-Object {Join-Path $project "kronos-server/src/main/java/io/ruin/utility/$_.java"}
$sources+=Join-Path $PSScriptRoot 'AccountRole242Regression.java'
& (Join-Path $JavaHome 'bin/javac.exe') -J-XX:+UseSerialGC -J-Xmx128m -encoding UTF-8 -cp "$lib/*" -d $out @sources
if($LASTEXITCODE -ne 0){throw 'Account role compilation failed'}
$result=& (Join-Path $JavaHome 'bin/java.exe') -XX:+UseSerialGC -Xmx128m -cp "$out;$lib/*" io.ruin.utility.AccountRole242Regression
if($LASTEXITCODE -ne 0){throw 'Account role regression failed'}
$result | Set-Content -Encoding UTF8 (Join-Path $out 'result.txt')
$result
