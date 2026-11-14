param([Parameter(Mandatory=$true)][string]$Python,[Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$CandidateLib,[Parameter(Mandatory=$true)][string]$Payload)
$ErrorActionPreference='Stop'
$out=Join-Path $PSScriptRoot 'test-output/owner-grant'
New-Item -ItemType Directory -Force $out | Out-Null
& $Python -B (Join-Path $PSScriptRoot 'test_owner_grant.py') -v
if($LASTEXITCODE -ne 0){throw 'Owner grant transaction checks failed'}
$lib=(Resolve-Path $CandidateLib).Path
& (Join-Path $JavaHome 'bin/javac.exe') -J-XX:+UseSerialGC -J-Xmx128m -encoding UTF-8 -cp "$lib/*" -d $out (Join-Path $PSScriptRoot 'OwnerGrantBank242Regression.java')
if($LASTEXITCODE -ne 0){throw 'Native bank fixture compilation failed'}
$result=& (Join-Path $JavaHome 'bin/java.exe') -XX:+UseSerialGC -Xmx96m -cp "$out;$lib/*" io.ruin.content.content242.OwnerGrantBank242Regression (Resolve-Path $Payload).Path
if($LASTEXITCODE -ne 0){throw 'Native grant bank serialization check failed'}
$result | Set-Content -Encoding UTF8 (Join-Path $out 'result.txt')
$result
