# Read-only, explicitly coordinated live home/cache audit. No setters or map loading.
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$backup=(Get-Content -LiteralPath "$root/local-runtime/latest-hollow-depths-backup.txt" -Raw).Trim()
$expected=Get-Content -LiteralPath "$backup/evidence/live-process-identities.json" -Raw | ConvertFrom-Json | Where-Object {$_.name -eq 'game-server'}
$live=Get-CimInstance Win32_Process -Filter "ProcessId=$($expected.pid)"
if(!$live -or !$live.CommandLine.Trim().EndsWith('io.ruin.Server') -or (Get-Process -Id $expected.pid).StartTime.ToUniversalTime().Ticks -ne ([DateTime]$expected.startedUtc).ToUniversalTime().Ticks){throw 'Original live process identity changed'}
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$build=Join-Path $root 'local-runtime/hollow-depths-test-01/home-audit'
New-Item -ItemType Directory -Path $build -Force | Out-Null
$source=Join-Path $PSScriptRoot 'ReadHollowDepthsHome.java'
$name='ReadHollowDepthsHome_'+(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.Substring(0,8)
[IO.File]::WriteAllText("$build/$name.java",[IO.File]::ReadAllText($source).Replace('ReadHollowDepthsHome',$name),[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx128m -cp "$jdk/lib/tools.jar" -d $build "$build/$name.java"
if($LASTEXITCODE){throw 'Readonly probe compile failed'}
[IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: $name`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" -J-Xmx64m cfm "$build/$name.jar" "$build/MANIFEST.MF" -C $build "$name.class"
if($LASTEXITCODE){throw 'Readonly probe packaging failed'}
& "$jdk/bin/java.exe" -Xmx64m -cp "$build;$jdk/lib/tools.jar" $name $expected.pid "$build/$name.jar" "$build/live-home-readonly.txt"
if($LASTEXITCODE){throw 'Readonly live probe failed'}
"Read existing live map state into $build/live-home-readonly.txt"
