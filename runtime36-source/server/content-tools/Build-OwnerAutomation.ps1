param(
    [Parameter(Mandatory=$true)][string]$Workspace,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string]$BaselineClasspathFile,
    [Parameter(Mandatory=$true)][string]$BaselineOverlay,
    [Parameter(Mandatory=$true)][string]$AsmClasspath
)
$ErrorActionPreference='Stop'
$source=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root=(Resolve-Path $Workspace).Path
$baselinePath=(Resolve-Path $BaselineOverlay).Path
$baselineParent=Split-Path $baselinePath
$baseline=Get-Content $baselinePath -Raw|ConvertFrom-Json
if ($baseline.publicAuthorized -ne $false) { throw 'Expected a private baseline overlay' }
if(Test-Path $Output){throw 'Choose a new build output directory'}
$null=New-Item -ItemType Directory $Output
$out=(Resolve-Path $Output).Path
$buildCp=(Get-Content $BaselineClasspathFile -Raw).Trim()
$cp=(Join-Path $baselineParent 'classes') + ';' + $buildCp
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09/bin'
$overlay=Join-Path $out 'overlay'
$classOut=Join-Path $overlay 'classes'
$null=New-Item -ItemType Directory -Force $classOut,"$out/tool","$out/tests"
foreach ($entry in $baseline.files) {
    if ($entry.path -notmatch '^classes/(?:[A-Za-z0-9_$-]+/)*[A-Za-z0-9_$-]+\.class$') { throw 'Invalid baseline entry' }
    $before=Join-Path $baselineParent $entry.path
    if ((Get-FileHash $before).Hash -ne $entry.sha256) { throw "Baseline checksum mismatch: $($entry.path)" }
    $to=Join-Path $overlay $entry.path
    $null=New-Item -ItemType Directory -Force (Split-Path $to)
    Copy-Item -LiteralPath $before -Destination $to
}
$java=Join-Path $source 'Kronos-master/kronos-server/src/main/java/io/ruin/model/activities/automation'
$activityFiles=@((Get-ChildItem $java -File -Filter '*.java').FullName)
$activityFiles += Join-Path $source 'Kronos-master/kronos-server/src/main/java/io/ruin/model/activities/bosses/vorkath/Vorkath.java'
$activityFiles += Join-Path $source 'Kronos-master/kronos-server/src/main/java/io/ruin/model/activities/bosses/zulrah/Zulrah.java'
& "$jdk/javac.exe" -cp $cp -d $classOut $activityFiles
if($LASTEXITCODE){throw 'Automation compilation failed'}
& "$jdk/javac.exe" -cp $AsmClasspath -d "$out/tool" "$PSScriptRoot/PatchOwnerAutomation.java"
if($LASTEXITCODE){throw 'Hook compiler failed'}
& "$jdk/java.exe" -cp "$out/tool;$AsmClasspath;$cp" PatchOwnerAutomation $classOut
if($LASTEXITCODE){throw 'Baseline hook application failed'}
$checks=Join-Path $PSScriptRoot 'checks'
& "$jdk/javac.exe" -cp "$classOut;$cp" -d "$out/tests" "$checks/OwnerAutomationLeaseChecks.java" "$checks/OwnerAutomationPolicyChecks.java"
if($LASTEXITCODE){throw 'Check compilation failed'}
& "$jdk/java.exe" -Xverify:all -Xmx256m -cp "$out/tests;$classOut;$cp" kilim.tools.Kilim OwnerAutomationLeaseChecks
if($LASTEXITCODE){throw 'Action ownership checks failed'}
& "$jdk/java.exe" -Xverify:all -Xmx128m -cp "$out/tests;$classOut;$cp" io.ruin.model.activities.automation.OwnerAutomationPolicyChecks
if($LASTEXITCODE){throw 'Policy checks failed'}
$baseline.files=@(foreach($file in Get-ChildItem $classOut -Recurse -File){
    $relative=$file.FullName.Substring($overlay.Length+1).Replace('\','/')
    [ordered]@{path=$relative;sha256=(Get-FileHash $file.FullName).Hash}
})
$baseline|ConvertTo-Json -Depth 5|Set-Content -Encoding ascii "$overlay/MAP-WALK-OVERLAY.json"
Write-Output "Verified, inactive private server overlay at $overlay"
