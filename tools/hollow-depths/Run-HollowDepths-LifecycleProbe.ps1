param([ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-02',[switch]$ProductionPaused,[ValidateSet('none','trap','chest','status','abandon')][string]$Preview='none')
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root "local-runtime/$StageName"
& "$PSScriptRoot/Test-HollowDepths-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused
$m=Get-Content -LiteralPath "$stage/hollow-depths-test.json" -Raw | ConvertFrom-Json
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$source=Join-Path $PSScriptRoot 'HollowDepthsLifecycleProbe.java'
$name='HollowDepthsLifecycleProbe_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$build=Join-Path $stage "lifecycle-$name"
New-Item -ItemType Directory -Path $build -Force | Out-Null
$sourceText=[IO.File]::ReadAllText($source).Replace('HollowDepthsLifecycleProbe',$name)
if($Preview -ne 'none'){
    $sourceText=$sourceText.Replace('public static void agentmain(','public static void fullAgentmain(')
    $entry='public static void agentmain(String directory,Instrumentation ignored)throws Exception{preview(directory,"'+$Preview+'");}'
    $sourceText=$sourceText.Insert($sourceText.LastIndexOf('}'),$entry)
}
[IO.File]::WriteAllText("$build/$name.java",$sourceText,[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx192m -proc:none -cp "$($m.gameLib)/*;$jdk/lib/tools.jar" -d $build "$build/$name.java" "$PSScriptRoot/HollowDepthsProbeLoader.java"
if($LASTEXITCODE){throw 'Native lifecycle probe must compile against actual candidate'}
$main="io.ruin.model.activities.hollowdepths.$name"
[IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: HollowDepthsProbeLoader`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" -J-Xmx64m cfm "$build/$name.jar" "$build/MANIFEST.MF" -C $build io -C $build HollowDepthsProbeLoader.class
if($LASTEXITCODE){throw 'Native lifecycle probe packaging failed'}
$gamePid=[int](Get-Content -LiteralPath "$stage/game.pid")
& "$jdk/bin/java.exe" -Xmx128m -cp "$build;$jdk/lib/tools.jar" HollowDepthsProbeLoader $gamePid "$build/$name.jar" $main $stage
if($LASTEXITCODE){throw 'Native lifecycle probe failed; retain stage report and game logs'}
if($Preview -eq 'none'){Get-Content -LiteralPath "$stage/native-lifecycle-probe.txt"}
else{Get-Content -LiteralPath "$stage/native-preview-$Preview.txt"}
