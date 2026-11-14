param(
    [ValidatePattern('^content242-test-[a-z0-9-]+$')][string]$StageName='content242-test-04',
    [ValidateRange(0,65023)][int]$WeaponId=26219
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root ('local-runtime/'+$StageName)
$manifest=Get-Content -LiteralPath "$stage/content242-test.json" -Raw | ConvertFrom-Json
if($manifest.stage -ne $stage){throw 'Staging manifest path mismatch'}
$targetPid=[int](Get-Content -LiteralPath "$stage/game.pid")
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$targetPid"
foreach($flag in @('-Dkronos.content242.test=true','-Dkronos.adventurers.test=true',('-Dkronos.content242.stage='+$StageName),'-Dkronos.port=14302','-Dkronos.bind=127.0.0.1')){
    if(!$process -or !$process.CommandLine.Contains($flag)){throw "Target process is missing required staging guard: $flag"}
}
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$token=[Guid]::NewGuid().ToString('N').Substring(0,8)
$className='Content242CombatProbe_'+$token
$classes=Join-Path $stage ('combat-probe-classes-'+$token)
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$classpath="$jdk/lib/tools.jar;$($manifest.gameLib)/*"
$generated=Join-Path $classes ($className+'.java')
$source=[IO.File]::ReadAllText("$PSScriptRoot/Content242CombatProbe.java").Replace('Content242CombatProbe',$className)
[IO.File]::WriteAllText($generated,$source,[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" '-J-Xmx128m' -encoding UTF-8 -cp $classpath -d $classes $generated
if($LASTEXITCODE){throw 'Combat probe compilation failed'}
$agentJar=Join-Path $stage ('combat-probe-'+$token+'.jar')
$agentManifest=Join-Path $stage ('combat-probe-manifest-'+$token+'.mf')
[IO.File]::WriteAllText($agentManifest,"Manifest-Version: 1.0`nAgent-Class: $className`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" '-J-Xmx64m' cfm $agentJar $agentManifest -C $classes .
if($LASTEXITCODE){throw 'Combat probe packaging failed'}
$request=Join-Path $stage 'combat-probe.properties'
[IO.File]::WriteAllText($request,"stage=$StageName`nweapon=$WeaponId`n",[Text.Encoding]::ASCII)
& "$jdk/bin/java.exe" '-Xmx64m' -cp "$classes;$classpath" $className $targetPid $agentJar $request
if($LASTEXITCODE){throw 'Combat probe failed; inspect combat-proof.json and staging game logs'}
$proof=Get-Content -LiteralPath "$stage/combat-proof.json" -Raw | ConvertFrom-Json
if(!$proof.passed -or !$proof.temporary_npc_removed -or $proof.direct_damage_written){throw 'Combat proof or cleanup did not pass'}
$proof | ConvertTo-Json
