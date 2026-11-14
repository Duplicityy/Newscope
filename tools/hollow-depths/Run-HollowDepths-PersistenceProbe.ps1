param(
    [ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-02',
    [ValidateSet('hdqa1','hdqa2')][string]$Account='hdqa1',
    [ValidateSet('checks','prepare-restart','verify-restart')][string]$Mode='checks',
    [switch]$ProductionPaused,
    [switch]$CompileOnly
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root "local-runtime/$StageName"
$manifest=Get-Content -LiteralPath "$stage/hollow-depths-test.json" -Raw | ConvertFrom-Json
if($manifest.stage -ne $stage -or $manifest.revision -ne 184){throw 'Staging manifest identity mismatch'}
if(-not $CompileOnly){& "$PSScriptRoot/Test-HollowDepths-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused}
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$source=Join-Path $PSScriptRoot 'HollowDepthsPersistenceProbe.java'
$simpleName='HollowDepthsPersistenceProbe_'+(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.Substring(0,8).ToLowerInvariant()+'_'+(Get-FileHash -LiteralPath "$PSScriptRoot/HollowDepthsProbeLoader.java" -Algorithm SHA256).Hash.Substring(0,8).ToLowerInvariant()+'_'+([guid]::NewGuid().ToString('N').Substring(0,8))
$classes=Join-Path $stage "persistence-probe-$simpleName"
$generated=$classes
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$className='io.ruin.model.activities.hollowdepths.'+$simpleName
[IO.File]::WriteAllText("$generated/$simpleName.java",[IO.File]::ReadAllText($source).Replace('HollowDepthsPersistenceProbe',$simpleName),[Text.UTF8Encoding]::new($false))
$compileClasspath="$jdk/lib/tools.jar;$($manifest.gameLib)/*"
& "$jdk/bin/javac.exe" -J-Xmx192m -proc:none -cp $compileClasspath -d $classes "$generated/$simpleName.java" "$PSScriptRoot/HollowDepthsProbeLoader.java"
if($LASTEXITCODE){throw 'Persistence probe compilation failed'}
[IO.File]::WriteAllText("$stage/persistence-probe-manifest.mf","Manifest-Version: 1.0`nAgent-Class: HollowDepthsProbeLoader`n`n",[Text.Encoding]::ASCII)
$agentJar=Join-Path $stage "$simpleName.jar"
& "$jdk/bin/jar.exe" -J-Xmx64m cfm $agentJar "$stage/persistence-probe-manifest.mf" -C $classes io -C $classes HollowDepthsProbeLoader.class
if($LASTEXITCODE){throw 'Persistence probe packaging failed'}
if($CompileOnly){"Compiled isolated persistence probe: $agentJar";return}
$request=Join-Path $stage 'persistence-probe-request.properties'
@("account=$Account","mode=$Mode") | Set-Content -LiteralPath $request -Encoding ASCII
$stagePid=[int](Get-Content -LiteralPath "$stage/game.pid")
& "$jdk/bin/java.exe" -Xmx96m -cp "$classes;$jdk/lib/tools.jar" HollowDepthsProbeLoader $stagePid $agentJar $className $stage
if($LASTEXITCODE){throw 'Persistence probe failed; inspect isolated report and game.err.log'}
Get-Content -LiteralPath "$stage/persistence-$Account-$Mode.properties"
