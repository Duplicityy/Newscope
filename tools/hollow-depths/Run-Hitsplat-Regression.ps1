param(
    [string]$OldRelease='hollow-depths-184-candidate-09',
    [string]$NewRelease='hollow-depths-184-hitsplats-10'
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
foreach($release in @($OldRelease,$NewRelease)){if($release -notmatch '^[a-zA-Z0-9-]+$'){throw 'Release names must identify local immutable release directories'}}
$old=Join-Path $root "local-runtime/server-releases/$OldRelease/lib"
$new=Join-Path $root "local-runtime/server-releases/$NewRelease/lib"
$build=Join-Path $root 'local-runtime/hitsplat-regression'
$receiptDirectory=Join-Path $root 'local-runtime/hitsplat-hotfix-10'
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
New-Item -ItemType Directory -Path $build,$receiptDirectory -Force | Out-Null
& "$jdk/bin/javac.exe" -J-Xmx128m -proc:none -cp "$old/*" -d $build "$PSScriptRoot/HitsplatRegression.java"
if($LASTEXITCODE){throw 'Native regression compilation failed'}
& "$jdk/bin/java.exe" -Xmx256m -cp "$build;$old/*" io.ruin.model.activities.hollowdepths.HitsplatRegression "$build/candidate09.txt"
$oldExit=$LASTEXITCODE
& "$jdk/bin/java.exe" -Xmx256m -cp "$build;$new/*" io.ruin.model.activities.hollowdepths.HitsplatRegression "$build/hitsplats10.txt"
if($LASTEXITCODE -ne 0 -or $oldExit -eq 0){throw 'Expected old-build failure and corrected-build success were not both observed'}
$report=Get-Content -LiteralPath "$build/hitsplats10.txt"
$summary=$report | Where-Object {$_ -match '^PASS native hitsplat regression:'}
$count=[int]([regex]::Match($summary,'assertions=(\d+)').Groups[1].Value)
[ordered]@{
    result='PASS'
    oldJarSha256=(Get-FileHash -LiteralPath "$old/kronos-server-1.0.jar").Hash.ToLowerInvariant()
    newJarSha256=(Get-FileHash -LiteralPath "$new/kronos-server-1.0.jar").Hash.ToLowerInvariant()
    oldBuildReproduced=$true
    assertions=$count
    scenarios=@($report | Where-Object {$_ -match '^PASS ' -and $_ -notmatch '^PASS native '})
    observedUtc=(Get-Date).ToUniversalTime().ToString('o')
    scope='Detached native Entity/Hit/HitsUpdate/HollowDepths, synthetic unregistered NPC state. Actual NPC and player mask bytes tested. No Server.main, cache, network, live attach, or organic combat.'
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$receiptDirectory/regression.json"
"PASS: $count assertions; receipt $receiptDirectory/regression.json"
