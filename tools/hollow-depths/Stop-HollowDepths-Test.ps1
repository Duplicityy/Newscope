param([ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-02')
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root "local-runtime/$StageName"
$gamePid=[int](Get-Content -LiteralPath "$stage/game.pid")
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$gamePid"
if(!$process -or !$process.CommandLine.Contains('-Dkronos.hollowDepths.test=true') -or !$process.CommandLine.Contains("-Dkronos.hollowDepths.stage=$StageName") -or !$process.CommandLine.Contains('-Dkronos.port=14312')){throw 'Isolated game process mismatch'}
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$build=Join-Path $stage 'shutdown-tool'
New-Item -ItemType Directory -Path $build -Force | Out-Null
& "$jdk/bin/javac.exe" -J-Xmx64m -cp "$jdk/lib/tools.jar" -d $build "$PSScriptRoot/StopHollowDepthsTest.java"
if($LASTEXITCODE){throw 'Shutdown helper compile failed'}
[IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: StopHollowDepthsTest`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" -J-Xmx64m cfm "$build/stop.jar" "$build/MANIFEST.MF" -C $build StopHollowDepthsTest.class
if($LASTEXITCODE){throw 'Shutdown helper packaging failed'}
& "$jdk/bin/java.exe" -Xmx64m -cp "$build;$jdk/lib/tools.jar" StopHollowDepthsTest $gamePid "$build/stop.jar" $StageName
if($LASTEXITCODE){throw 'Shutdown request failed'}
"Requested normal shutdown of isolated game $gamePid."
