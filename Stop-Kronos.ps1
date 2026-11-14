$ErrorActionPreference='Stop'
$runtime=Join-Path $PSScriptRoot 'local-runtime'
$jdk=(Get-Content -LiteralPath "$runtime/java-home.txt" -Raw).Trim()
$java=Join-Path $jdk 'bin/java.exe'
function OwnedProcess($name,$main){
    $pidFile=Join-Path $runtime "$name.pid"
    if(!(Test-Path -LiteralPath $pidFile)){return $null}
    $serverPid=[int](Get-Content -LiteralPath $pidFile)
    $p=Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    if(!$p){return $null}
    if($p.ExecutablePath -ne $java -or !$p.CommandLine.Contains($runtime) -or !$p.CommandLine.Contains($main)){throw "Refusing unrelated or reused PID for $name"}
    return $p
}
$game=OwnedProcess 'game-server' 'LocalServerBootstrap'
if($game){
    $build=Join-Path $runtime ('shutdown-'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $build|Out-Null
    & "$jdk/bin/javac.exe" -cp "$jdk/lib/tools.jar" -d $build "$PSScriptRoot/tools/RestartKronos.java"
    if($LASTEXITCODE){throw 'Graceful shutdown helper compilation failed'}
    "Manifest-Version: 1.0`nAgent-Class: RestartKronos`n"|Set-Content "$build/MANIFEST.MF" -Encoding ascii
    & "$jdk/bin/jar.exe" cfm "$build/shutdown.jar" "$build/MANIFEST.MF" -C $build RestartKronos.class
    if($LASTEXITCODE){throw 'Graceful shutdown helper packaging failed'}
    & $java -cp "$build;$jdk/lib/tools.jar" RestartKronos $game.ProcessId "$build/shutdown.jar"
    if($LASTEXITCODE){throw 'Normal server update request failed; no force stop performed'}
    $p=Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue
    if($p -and !$p.WaitForExit(90000)){throw 'Normal shutdown still pending; no force stop performed'}
}
$updater=OwnedProcess 'update-server' 'io.ruin.update.Server'
if($updater){Stop-Process -Id $updater.ProcessId}
'Game shutdown completed through its normal save/logout path; updater stopped.'
