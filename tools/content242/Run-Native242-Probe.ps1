param(
 [Parameter(Mandatory=$true)][string]$Stage,
 [ValidateSet('readiness','inventory-audit','boss-readiness','gear','scurrius','amoxliatl','muspah','preview-scurrius','preview-amoxliatl','preview-muspah')][string]$Mode='readiness',
 [switch]$CompileOnly,
 [switch]$Capture
)
$ErrorActionPreference='Stop'
$stagePath=(Resolve-Path -LiteralPath $Stage).Path
$name=Split-Path $stagePath -Leaf
if($name -notmatch '^content242-expanded-test-[a-z0-9-]+$'){throw 'Explicit expanded242 stage required'}
$m=Get-Content -LiteralPath (Join-Path $stagePath 'content242-test.json') -Raw | ConvertFrom-Json
if([IO.Path]::GetFullPath($m.stage) -ne $stagePath -or $m.gamePort -ne 14302 -or $m.cachePort -ne 17304){throw 'Stage identity/ports mismatch'}
$jdk=Split-Path (Split-Path $m.java -Parent) -Parent
$source=Join-Path $PSScriptRoot 'Content242NativeProbe.java'
$className='Content242NativeProbe_'+(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.Substring(0,8).ToLowerInvariant()+'_'+[guid]::NewGuid().ToString('N').Substring(0,8)
$build=Join-Path $stagePath "native-probe-$className"
New-Item -ItemType Directory -Force $build | Out-Null
[IO.File]::WriteAllText((Join-Path $build "$className.java"),[IO.File]::ReadAllText($source).Replace('Content242NativeProbe',$className),[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-XX:+UseSerialGC -J-Xmx128m -proc:none -encoding UTF-8 -cp "$($m.gameLib)/*;$jdk/lib/tools.jar" -d $build "$build/$className.java" "$PSScriptRoot/Content242ProbeLoader.java"
if($LASTEXITCODE){throw 'Native242 probe compilation failed'}
[IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: Content242ProbeLoader`n`n",[Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" -J-XX:+UseSerialGC -J-Xmx48m cfm "$build/$className.jar" "$build/MANIFEST.MF" -C $build io -C $build Content242ProbeLoader.class
if($LASTEXITCODE){throw 'Native242 probe packaging failed'}
if($CompileOnly){Write-Output "Compiled native242 $Mode probe only; no process attached.";return}
$gamePid=[int](Get-Content -LiteralPath "$stagePath/game.pid")
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$gamePid"
if(!$process -or $process.CommandLine -notlike "*-Dkronos.content242.stage=$name*" -or $process.CommandLine -notlike '*-Dkronos.port=14302*' -or $process.CommandLine -notlike '*-Dkronos.bind=127.0.0.1*'){throw 'Target process does not match isolated stage'}
$listener=Get-NetTCPConnection -State Listen -LocalPort 14302 -ErrorAction Stop
if($listener.OwningProcess -ne $gamePid -or $listener.LocalAddress -ne '127.0.0.1'){throw 'Stage loopback listener mismatch'}
$captureFlag=Join-Path $stagePath 'native242-capture.enabled'
$createdFlag=$Capture -and !(Test-Path -LiteralPath $captureFlag)
if($createdFlag){[IO.File]::WriteAllText($captureFlag,'enabled')}
try{
 & "$jdk/bin/java.exe" -XX:+UseSerialGC -Xmx96m -cp "$build;$jdk/lib/tools.jar" Content242ProbeLoader $gamePid "$build/$className.jar" "io.ruin.content.content242.bosses.$className" $stagePath $Mode
 if($LASTEXITCODE){throw 'Native242 probe failed; inspect native242 report and stage log'}
}finally{if($createdFlag){Remove-Item -LiteralPath $captureFlag}}
Get-Content -LiteralPath "$stagePath/native242-$Mode.json"
