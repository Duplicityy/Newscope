param(
    [ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-01',
    [ValidateSet('hdqa1','hdqa2')][string]$Account='hdqa1',
    [ValidateSet(1,99)][int]$Agility=1,
    [int[]]$ItemIds=@(4151,1187,1127,1079,1163,11840,7462,6585),
    [int[]]$EquipIds=@(4151,1187,1127,1079,1163,11840,7462,6585),
    [int[]]$Destination=@(),[switch]$ProductionPaused
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root "local-runtime/$StageName"
& "$PSScriptRoot/Test-HollowDepths-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused
if($Destination.Count -notin 0,3){throw 'Destination requires x,y,z'}
$request=Join-Path $stage "fixture-$Account.properties"
@("account=$Account","agility=$Agility",('items='+($ItemIds -join ',')),('equip='+($EquipIds -join ',')),('location='+($Destination -join ','))) | Set-Content -LiteralPath $request -Encoding ASCII
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$classes=Join-Path $stage 'fixture-classes';$generated=Join-Path $stage 'fixture-generated'
New-Item -ItemType Directory -Path $classes,$generated -Force | Out-Null
$source=Join-Path $PSScriptRoot 'HollowDepthsFixture.java'
$className='HollowDepthsFixture_'+(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.Substring(0,8).ToLowerInvariant()
[IO.File]::WriteAllText("$generated/$className.java",[IO.File]::ReadAllText($source).Replace('HollowDepthsFixture',$className),[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx128m -cp "$jdk/lib/tools.jar" -d $classes "$generated/$className.java"
if($LASTEXITCODE){throw 'Disposable fixture compile failed'}
[IO.File]::WriteAllText("$stage/fixture-manifest.mf","Manifest-Version: 1.0`nAgent-Class: $className`n`n",[Text.Encoding]::ASCII)
$agentJar=Join-Path $stage "$className.jar"
& "$jdk/bin/jar.exe" -J-Xmx64m cfm $agentJar "$stage/fixture-manifest.mf" -C $classes .
if($LASTEXITCODE){throw 'Disposable fixture packaging failed'}
$stagePid=[int](Get-Content -LiteralPath "$stage/game.pid")
& "$jdk/bin/java.exe" -Xmx64m -cp "$classes;$jdk/lib/tools.jar" $className $stagePid $agentJar $request
if($LASTEXITCODE){throw 'Disposable fixture failed; inspect isolated game.err.log'}
"Applied original184 equipment and Agility$Agility fixture to isolated $Account only."
