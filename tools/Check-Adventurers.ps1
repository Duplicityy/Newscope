param([ValidatePattern('^[a-z0-9:-]+$')][string]$Check='state',[ValidatePattern('^[a-z0-9-]+$')][string]$StageName='adventurer-test')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$probe=Join-Path $root 'local-runtime/adventurer-probe'
$stage=Join-Path $root ('local-runtime/'+$StageName)
$pidFile=Join-Path $stage 'server.pid'
$testPid=[int](Get-Content $pidFile)
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$testPid"
if(!$process -or $process.CommandLine -notlike '*-Dkronos.adventurers.test=true*'){throw 'PID is not the isolated test server'}
New-Item -ItemType Directory -Path $probe -Force | Out-Null
& "$jdk/bin/javac.exe" -J-Xmx128m -cp "$jdk/lib/tools.jar" -d $probe (Join-Path $root 'tools/AdventurerProbe.java')
if($LASTEXITCODE){throw 'Integration probe compile failed'}
[IO.File]::WriteAllText((Join-Path $probe 'probe-manifest.mf'),"Manifest-Version: 1.0`nAgent-Class: AdventurerProbe`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" -J-Xmx64m cfm (Join-Path $probe 'probe.jar') (Join-Path $probe 'probe-manifest.mf') -C $probe AdventurerProbe.class
if($LASTEXITCODE){throw 'Integration probe package failed'}
& "$jdk/bin/java.exe" -Xmx64m -cp "$probe;$jdk/lib/tools.jar" AdventurerProbe $testPid (Join-Path $probe 'probe.jar') $Check
if($LASTEXITCODE -ne 0){
    Get-Content (Join-Path $stage 'server.err.log') -Tail 24
    throw "Integration probe failed: $Check"
}
Get-Content (Join-Path $stage 'server.out.log') -Tail 5
