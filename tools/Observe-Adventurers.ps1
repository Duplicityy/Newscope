param([Parameter(Mandatory)][string]$Directory,[ValidateSet('snapshot','observe')][string]$Mode='snapshot',[ValidateRange(1,25)][int]$Bot=1)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$directoryPath=[IO.Path]::GetFullPath($Directory)
$parent=[IO.Path]::GetFullPath("$root/local-runtime/hollow-depths-live-client")
if((Split-Path $directoryPath -Parent) -ne $parent){throw 'Use an existing private ordinary QA client runtime directory'}
$gamePid=[int](Get-Content "$root/local-runtime/game-server.pid")
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$gamePid"
if(!$process -or !$process.CommandLine.Trim().EndsWith('io.ruin.Server') -or $process.CommandLine -match '\.test=true' -or !(Get-NetTCPConnection -State Listen -LocalPort 13302 -OwningProcess $gamePid -ErrorAction SilentlyContinue)){throw 'Live production game identity mismatch'}
$observer=''
if($Mode -eq 'observe'){
    $accountLine=Get-Content -LiteralPath "$directoryPath/credentials.properties" | Where-Object {$_ -match '^account=hdl[0-9a-f]{8}$'}
    if(@($accountLine).Count -ne 1){throw 'Private ordinary QA observer identity missing'}
    $observer=$accountLine.Substring(8)
}
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$name='AdventurerObservation_'+(Get-FileHash "$PSScriptRoot/AdventurerObservation.java" -Algorithm SHA256).Hash.Substring(0,12)
$build=Join-Path $directoryPath 'observation-helper'
New-Item -ItemType Directory -Path $build -Force | Out-Null
if(!(Test-Path "$build/$name.jar")){
    [IO.File]::WriteAllText("$build/$name.java",[IO.File]::ReadAllText("$PSScriptRoot/AdventurerObservation.java").Replace('AdventurerObservation',$name),[Text.UTF8Encoding]::new($false))
    & "$jdk/bin/javac.exe" -J-Xmx128m -cp "$jdk/lib/tools.jar" -d $build "$build/$name.java"
    if($LASTEXITCODE){throw 'Observation helper compilation failed'}
    [IO.File]::WriteAllText("$build/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: $name`n`n",[Text.Encoding]::ASCII)
    & "$jdk/bin/jar.exe" -J-Xmx64m cfm "$build/$name.jar" "$build/MANIFEST.MF" -C $build "$name.class"
    if($LASTEXITCODE){throw 'Observation helper packaging failed'}
}
$output='bots-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+$Bot+'.json'
[IO.File]::WriteAllLines("$directoryPath/observation-request.properties",@("mode=$Mode","bot=$Bot","observer=$observer","output=$output"),[Text.Encoding]::ASCII)
& "$jdk/bin/java.exe" -Xmx64m -cp "$build;$jdk/lib/tools.jar" $name $gamePid "$build/$name.jar" "$directoryPath/observation-request.properties"
if($LASTEXITCODE){throw 'Live bot observation failed'}
"$directoryPath/$output"
