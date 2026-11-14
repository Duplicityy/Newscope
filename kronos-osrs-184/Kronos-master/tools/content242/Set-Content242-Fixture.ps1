param([ValidatePattern('^[a-z0-9-]+$')][string]$StageName='content242-test',[ValidatePattern('^[a-z0-9-]{1,40}$')][string]$Phase='gear',[int[]]$ItemIds=@(26382,26384,26386,27226,27229,27232,26219,24417),[int[]]$EquipIds=@(26382,26384,26386,26219),[int[]]$Destination=@(),[switch]$ProductionPaused,[switch]$SourceIds,[ValidateRange(0,13)][int]$ExpectedSlot=3)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Workspace.ps1"
$stage=Join-Path $root ('local-runtime/'+$StageName)
& "$PSScriptRoot/Test-Content242-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused
$stagePid=[int](Get-Content -LiteralPath "$stage/game.pid")
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$stagePid"
if(!$process.CommandLine.Contains('-Dkronos.adventurers.test=true')){throw 'Game process is not test-attach enabled'}
$m=Get-Content -LiteralPath "$stage/content242-test.json" -Raw | ConvertFrom-Json
if($SourceIds){
    $bindings=(Get-Content -LiteralPath "$($m.content)/data/content242.json" -Raw | ConvertFrom-Json).mappings.items
    $ItemIds=@($ItemIds | ForEach-Object {$mapped=$bindings.PSObject.Properties[[string]$_];if(!$mapped){throw "Unmapped item $_"};[int]$mapped.Value})
    $EquipIds=@($EquipIds | ForEach-Object {$mapped=$bindings.PSObject.Properties[[string]$_];if(!$mapped){throw "Unmapped item $_"};[int]$mapped.Value})
}
if($Phase -eq 'map'){
    $ItemIds=@();$EquipIds=@()
    if(!$Destination){
        $m=Get-Content -LiteralPath "$stage/content242-test.json" -Raw | ConvertFrom-Json
        $Destination=@((Get-Content -LiteralPath "$($m.content)/data/content242.json" -Raw | ConvertFrom-Json).landing)
    }
}
if($Destination.Count -notin 0,3){throw 'Destination requires x,y,z'}
$request=Join-Path $stage 'fixture.properties'
$lines=@()
$lines+=('items='+($ItemIds -join ','))
$lines+=('equip='+($EquipIds -join ','))
$lines+=('location='+($Destination -join ','))
$lines | Set-Content -LiteralPath $request -Encoding ASCII
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
$classes=Join-Path $stage 'fixture-classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$fixtureSource=Join-Path $PSScriptRoot 'Content242Fixture.java'
$className='Content242Fixture_'+(Get-FileHash -LiteralPath $fixtureSource -Algorithm SHA256).Hash.Substring(0,8).ToLowerInvariant()
$generated=Join-Path $stage 'fixture-generated'
New-Item -ItemType Directory -Path $generated -Force | Out-Null
$sourceText=[IO.File]::ReadAllText($fixtureSource).Replace('Content242Fixture',$className)
[IO.File]::WriteAllText("$generated/$className.java",$sourceText,[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx128m -cp "$jdk/lib/tools.jar" -d $classes "$generated/$className.java"
if($LASTEXITCODE){throw 'Fixture compile failed'}
[IO.File]::WriteAllText("$stage/fixture-manifest.mf","Manifest-Version: 1.0`nAgent-Class: $className`n`n",[Text.Encoding]::ASCII)
$agentJar=Join-Path $stage ($className+'.jar')
& "$jdk/bin/jar.exe" -J-Xmx64m cfm $agentJar "$stage/fixture-manifest.mf" -C $classes .
if($LASTEXITCODE){throw 'Fixture packaging failed'}
& "$jdk/bin/java.exe" -Xmx64m -cp "$classes;$jdk/lib/tools.jar" $className $stagePid $agentJar $request
if($LASTEXITCODE){throw 'Fixture application failed; inspect isolated game.err.log'}
New-Item -ItemType Directory -Path "$stage/captures" -Force | Out-Null
Set-Content -LiteralPath "$stage/captures/$Phase.slot" -Value $ExpectedSlot -Encoding ASCII
Set-Content -LiteralPath "$stage/captures/$Phase.ready" -Value 'fixture complete'
"Applied $Phase fixture only to disposable staging character c242qa"
