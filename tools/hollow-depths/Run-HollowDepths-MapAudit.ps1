param([ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-02',[switch]$ProductionPaused)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root "local-runtime/$StageName"
& "$PSScriptRoot/Test-HollowDepths-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused
$m=Get-Content -LiteralPath "$stage/hollow-depths-test.json" -Raw | ConvertFrom-Json
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$source=Join-Path $PSScriptRoot 'HollowDepthsMapAudit.java'
$name='HollowDepthsMapAudit_'+(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.Substring(0,8).ToLowerInvariant()+'_'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$build=Join-Path $stage "map-audit-$name"
New-Item -ItemType Directory -Path $build -Force | Out-Null
[IO.File]::WriteAllText("$build/$name.java",[IO.File]::ReadAllText($source).Replace('HollowDepthsMapAudit',$name),[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx192m -proc:none -cp "$($m.gameLib)/*;$jdk/lib/tools.jar" -d $build "$build/$name.java" "$PSScriptRoot/HollowDepthsProbeLoader.java"
if($LASTEXITCODE){throw 'Native map audit must compile against the actual candidate release'}
$main="io.ruin.model.activities.hollowdepths.$name"
[IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: HollowDepthsProbeLoader`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" -J-Xmx64m cfm "$build/$name.jar" "$build/MANIFEST.MF" -C $build io -C $build HollowDepthsProbeLoader.class
if($LASTEXITCODE){throw 'Native map audit packaging failed'}
$gamePid=[int](Get-Content -LiteralPath "$stage/game.pid")
& "$jdk/bin/java.exe" -Xmx128m -cp "$build;$jdk/lib/tools.jar" HollowDepthsProbeLoader $gamePid "$build/$name.jar" $main $stage
if($LASTEXITCODE){throw 'Native map audit failed; inspect stage game.err.log. No proof should be accepted.'}
Get-Content -LiteralPath "$stage/native-map-audit.txt"
