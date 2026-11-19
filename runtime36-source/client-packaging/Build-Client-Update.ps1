param(
    [Parameter(Mandatory)][string]$Workspace,
    [string]$Python = "$env:LOCALAPPDATA/../../.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe"
)
$ErrorActionPreference = 'Stop'
$out = $PSScriptRoot.Replace('\','/')
$stage = Split-Path $out -Parent
$jdk = "$Workspace/tools/jdk8/jdk8u512-b09/bin"
$runtime = 'D:/Newscape-Ubuntu/20261119-runtime35/release'
$asm = "$runtime/lib/game/asm-7.1.jar;$runtime/lib/game/asm-tree-7.1.jar"
$assets = "$stage/android/kronos/src/main/assets/jvm"
$src = "$stage/android/client-overlays"
New-Item -ItemType Directory -Force -Path "$out/patch-tools","$out/windows-update","$out/android-release" | Out-Null
& "$jdk/javac.exe" -proc:none -cp $asm -d "$out/patch-tools" "$src/PatchExpeditionOffer.java"
if ($LASTEXITCODE) { throw 'Script hook compilation failed' }

foreach ($platform in @('Android','Windows')) {
    $base = "$out/Client-Base-$platform.jar"
    $classes = "$out/client-classes-$platform"
    $tests = "$out/client-test-classes-$platform"
    $patched = "$classes/net/runelite/standalone/KeyHandler.class"
    $input = "$out/KeyHandler-$platform.class"
    $jar = if ($platform -eq 'Android') {"$out/android-release/client.jar"} else {"$out/windows-update/client-expedition.jar"}
    if (Test-Path -LiteralPath $jar) { throw "Candidate output already exists at $jar" }
    New-Item -ItemType Directory -Force -Path $classes,$tests | Out-Null
    if (!(Test-Path -LiteralPath $base)) {
        if ($platform -eq 'Windows') {
            Copy-Item -LiteralPath "$stage/windows-update/client-expedition.jar" -Destination $base
        } else {
            & $Python -c 'import sys,zipfile; from pathlib import Path; f,o=sys.argv[1:]; Path(o).write_bytes(zipfile.ZipFile(f).read("assets/jvm/client.jar"))' "$stage/Newscape-Mobile-Ultra-Expedition-v46-STAGED.apk" $base
            if ($LASTEXITCODE) { throw 'Could not read staged Android client' }
        }
    }
    $cp = "$base;$assets/bridge.jar;$assets/owner-plugins.jar;$assets/owner-graphics.jar;$runtime/lib/game/*"
    & "$jdk/javac.exe" -J-Xmx300m -encoding UTF-8 -proc:none -sourcepath "$stage/empty" -cp $cp -d $classes "$src/src/net/runelite/standalone/CustomDungeoneeringExpedition.java"
    if ($LASTEXITCODE) { throw "$platform ledger compilation failed" }
    & $Python -c 'import sys,zipfile; from pathlib import Path; f,o=sys.argv[1:]; Path(o).write_bytes(zipfile.ZipFile(f).read("net/runelite/standalone/KeyHandler.class"))' $base $input
    if ($LASTEXITCODE) { throw "$platform script entry point missing" }
    & "$jdk/java.exe" -Xmx80m -cp "$out/patch-tools;$asm" PatchExpeditionOffer $input $patched
    if ($LASTEXITCODE) { throw "$platform script hook patch failed" }
    & $Python "$stage/checks/merge_client_widgets.py" $classes $base $jar
    if ($LASTEXITCODE) { throw "$platform JAR verification failed" }
    & "$jdk/javac.exe" -encoding UTF-8 -proc:none -cp "$jar;$cp" -d $tests "$stage/checks/ExpeditionWidgetChecks.java"
    if ($LASTEXITCODE) { throw "$platform ledger test compilation failed" }
    & "$jdk/java.exe" -Xverify:all -Xmx128m -cp "$tests;$jar;$cp" net.runelite.standalone.ExpeditionWidgetChecks | Tee-Object -FilePath "$out/checks/client-$platform.txt"
    if ($LASTEXITCODE) { throw "$platform ledger checks failed" }
}
Copy-Item -LiteralPath "$stage/windows-update/Play Newscape Expedition.cmd" -Destination "$out/windows-update/Play Newscape Expedition.cmd"
