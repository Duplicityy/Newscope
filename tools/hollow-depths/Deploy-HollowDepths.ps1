<#
.SYNOPSIS
Review/preflight an immutable Hollow Depths 184 release; changes require -Apply.
.DESCRIPTION
Default is read-only. Deploy copies only the retained asset profile/collision proof and
changes the release pointer. It never copies staging accounts, saves, bot state, or cache.
It never stops a process. -Start additionally invokes the existing launcher with -NoClient.

Deployment checklist:
  1. Finish native lifecycle and real-client checks against the exact selected jar.
  2. Run this script without -Apply; resolve every reported blocker.
  3. Gracefully drain/stop the staging and production GAME processes; keep original cache.
  4. Re-run preflight, then explicitly use -Apply -Start when deployment is authorized.
  5. Verify startup asset acceptance, lobby/portal, ordinary login, bots and one safe run.
     Retain local-runtime/hollow-depths-deployments/<timestamp>/receipt.json and logs.

The stage must contain client-entry-review.json, written after viewing both actual
clients successfully inside their private dungeon on this exact build:
  {"result":"PASS","serverBuildId":"<jar SHA256>","cacheSha256":"<profile fingerprint>",
   "clientSha256":"<actual184 client SHA256>","captures":[
     {"account":"hdqa1","path":"captures-hdqa1/dungeon-entry.png","sha256":"<image SHA256>"},
     {"account":"hdqa2","path":"captures-hdqa2/dungeon-entry.png","sha256":"<image SHA256>"}]}
This is a retained human/agent image review, not a substitute for native lifecycle proof.

Emergency disable / rollback checklist:
  * Gracefully drain and stop the GAME first; do not use Stop-Kronos.ps1 (forceful, stops cache).
  * Use -Action DisableEntries -Apply -Start. This keeps the currently selected new binary
    and all receipt/recovery code, and changes only production.enabled=false in its profile.
  * Never restore the old pre-feature binary once new player records exist: it can discard
    exposed reward receipts/recovery markers on its next ordinary save.
  * Never restore player JSON alone, nor mailbox alone. A true data rollback requires a
    consistent stopped-world snapshot of player-data (including mailbox), account registry
    and autonomous-adventurers together, with explicit acceptance of lost later progress.
  * The verified pre-feature backup is disaster recovery, not an automatic reward rollback.

Build fingerprint: the profile and proof hash the entire server jar, not its name/path.
Any rebuild needs new matching retained evidence; moving identical bytes is harmless.
Cache.sha256 is a reference-table fingerprint, not the dat2 file hash. This helper also
compares every native cache file between the audited stage and production byte-for-byte.
#>
[CmdletBinding()]
param(
    [ValidateSet('Deploy','DisableEntries')][string]$Action='Deploy',
    [ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-02',
    [ValidatePattern('^(hollow-depths-184-[a-zA-Z0-9_-]+)?$')][string]$ReleaseName='',
    [ValidatePattern('^([a-fA-F0-9]{64})?$')][string]$ExpectedJarSha256='',
    [switch]$HitOnlyHotfix,
    [switch]$Apply,
    [switch]$Start
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($Start -and !$Apply){throw '-Start requires explicit -Apply; default preflight never starts anything.'}
$root=[IO.Path]::GetFullPath((Split-Path (Split-Path $PSScriptRoot -Parent) -Parent))
$runtime=Join-Path $root 'local-runtime'
$project=Join-Path $root 'kronos-osrs-184/Kronos-master'
$stage=Join-Path $runtime $StageName
$profileFolder=Join-Path $project 'kronos-server/data/hollow-depths'
$pointer=Join-Path $runtime 'game-server-release.txt'
$backup=Join-Path $root 'server-backups/before-hollow-depths-20261113-023753'
$blockers=[Collections.Generic.List[string]]::new()
function Block([string]$Reason){$blockers.Add($Reason)}
function Hash([string]$Path){
    # Java keeps native caches open for read/write access; grant compatible sharing
    # while this helper itself opens read-only. Detect concurrent file replacement.
    $before=Get-Item -LiteralPath $Path
    $length=$before.Length;$written=$before.LastWriteTimeUtc
    $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
    $digest=[Security.Cryptography.SHA256]::Create()
    try{$hash=[BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}
    finally{$stream.Dispose();$digest.Dispose()}
    $after=Get-Item -LiteralPath $Path
    if($after.Length -ne $length -or $after.LastWriteTimeUtc -ne $written){throw 'A reviewed file changed while it was being hashed.'}
    return $hash
}
function Read-Properties([string]$Path){
    $result=@{}
    foreach($line in [IO.File]::ReadAllLines($Path)){
        if($line -match '^\s*([^#!\s][^=]*?)\s*=\s*(.*?)\s*$'){$result[$matches[1]]=$matches[2]}
    }
    return $result
}
function Require-Equal($Actual,$Expected,[string]$Reason){if($Actual -cne $Expected){Block $Reason}}
function Assert-OrdinaryPath([string]$Path){
    $full=[IO.Path]::GetFullPath($Path)
    if(!$full.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Path escapes the approved workspace.'}
    $cursor=$full
    while($cursor -and $cursor.Length -ge $root.Length){
        if(Test-Path -LiteralPath $cursor){
            if((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint){throw "Reparse points are not permitted for deployment: $cursor"}
        }
        if($cursor -eq $root){break};$cursor=Split-Path $cursor -Parent
    }
}
function Native-Proof([string]$Path,[string]$Pattern,[string]$Label){
    if(!(Test-Path -LiteralPath $Path)){Block "$Label evidence is missing.";return ''}
    $text=[IO.File]::ReadAllText($Path)
    if($text -notmatch $Pattern -or $text -match '(?m)^FAIL\b'){Block "$Label has not passed."}
    return $text
}
function Protected-State {
    # Private filenames/hashes remain in the local deployment receipt, never console output.
    $found=@{}
    $paths=@("$runtime/accounts.properties","$runtime/adventurers.properties","$runtime/network.json","$runtime/player-data","$runtime/autonomous-adventurers")
    foreach($path in $paths){
        Assert-OrdinaryPath $path
        if(!(Test-Path -LiteralPath $path)){continue}
        $item=Get-Item -LiteralPath $path
        $files=if($item.PSIsContainer){@(Get-ChildItem -LiteralPath $path -File -Recurse)}else{@($item)}
        foreach($file in $files){Assert-OrdinaryPath $file.FullName;$found[$file.FullName.Substring($root.Length+1)]=Hash $file.FullName}
    }
    return $found
}
function Restrict-PrivateDirectory([string]$Path){
    $acl=[Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $identities=@([Security.Principal.WindowsIdentity]::GetCurrent().User,
        [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
        [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
    foreach($identity in $identities){
        $rule=[Security.AccessControl.FileSystemAccessRule]::new($identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),
            [Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    if(!(Get-Acl -LiteralPath $Path).AreAccessRulesProtected){throw 'Private deployment backup ACL did not become protected.'}
}
function Atomic-Bytes([string]$Path,[byte[]]$Bytes){
    Assert-OrdinaryPath $Path
    $temporary=$Path+'.hd-'+[Guid]::NewGuid().ToString('N')+'.tmp'
    try{
        $stream=[IO.File]::Open($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try{$stream.Write($Bytes,0,$Bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
        if([IO.File]::Exists($Path)){[IO.File]::Replace($temporary,$Path,[NullString]::Value)}else{[IO.File]::Move($temporary,$Path)}
    }finally{if([IO.File]::Exists($temporary)){[IO.File]::Delete($temporary)}}
}
foreach($path in @($runtime,$project,$stage,$profileFolder,$pointer,$backup)){Assert-OrdinaryPath $path}
$verification=Get-Content -LiteralPath "$backup/VERIFIED.json" -Raw | ConvertFrom-Json
if(!$verification.private -or !$verification.productionUnchanged -or $verification.backup -ne $backup){Block 'Pre-feature backup verification identity is invalid.'}
Require-Equal (Hash "$backup/SHA256-MANIFEST.json") $verification.manifestSha256 'Verified backup manifest changed.'
$selected=([IO.File]::ReadAllText($pointer)).Trim()
if($selected -notmatch '^[a-zA-Z0-9_-]+$' -or $selected -match '242'){throw 'Current release pointer is invalid or not revision184.'}
$manifest=Get-Content -LiteralPath "$stage/hollow-depths-test.json" -Raw | ConvertFrom-Json
if(!$ReleaseName){$ReleaseName=Split-Path (Split-Path $manifest.gameLib -Parent) -Leaf}
if($Action -eq 'DisableEntries'){
    if($selected -notmatch '^hollow-depths-184-'){Block 'Emergency disable requires a currently selected Hollow Depths recovery-capable binary.'}
    $ReleaseName=$selected
}
if($ReleaseName -notmatch '^hollow-depths-184-[a-zA-Z0-9_-]+$' -or $ReleaseName -match '242'){throw 'An explicitly isolated Hollow Depths184 release is required.'}
$releaseLib=Join-Path $runtime "server-releases/$ReleaseName/lib"
Assert-OrdinaryPath $releaseLib
$jars=@(Get-ChildItem -LiteralPath $releaseLib -File -Filter 'kronos-server*.jar')
if($jars.Count -ne 1){throw 'Exactly one immutable server jar is required.'}
$jarHash=Hash $jars[0].FullName
$lifecycleExpectedHash=$jarHash
$hotfixEvidence=$null
if($Action -eq 'Deploy'){
    if(!$ExpectedJarSha256){Block 'Supply the independently reviewed final -ExpectedJarSha256; observed stage identity alone cannot approve deployment.'}
    else{Require-Equal $jarHash $ExpectedJarSha256.ToLowerInvariant() 'Candidate jar differs from the explicitly reviewed SHA256.'}
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
if($HitOnlyHotfix){
    if($Action -ne 'Deploy' -or $selected -ne 'hollow-depths-184-candidate-09') { throw 'Hit-only hotfix requires the reviewed09 production baseline.' }
    $baselineLib=Join-Path $runtime "server-releases/$selected/lib"
    $baselineJar=Join-Path $baselineLib 'kronos-server-1.0.jar'
    $baselineHash=Hash $baselineJar
    Require-Equal $baselineHash '302c6194771c71e8138bbfb88a2f0040efe57985cdf8077848ddbed708a9604b' 'Hit-only baseline jar changed.'
    $oldZip=[IO.Compression.ZipFile]::OpenRead($baselineJar)
    $newZip=[IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
    try {
        if($oldZip.Entries.Count -ne $newZip.Entries.Count){throw 'Hit-only patch changed the archive entry set.'}
        $changed=[Collections.Generic.List[string]]::new()
        foreach($entry in $oldZip.Entries){
            $peer=$newZip.GetEntry($entry.FullName)
            if(!$peer){throw 'Hit-only patch removed an archive entry.'}
            $a=$entry.Open();$b=$peer.Open();$digest=[Security.Cryptography.SHA256]::Create()
            try{$ah=[Convert]::ToBase64String($digest.ComputeHash($a));$bh=[Convert]::ToBase64String($digest.ComputeHash($b))}finally{$a.Dispose();$b.Dispose();$digest.Dispose()}
            if($ah -cne $bh){$changed.Add($entry.FullName)}
        }
        if($changed.Count -ne 1 -or $changed[0] -cne 'io/ruin/model/combat/Hit.class'){throw 'Scoped deployment allows only Hit.class to differ.'}
    }finally{$oldZip.Dispose();$newZip.Dispose()}
    $oldDeps=@(Get-ChildItem -LiteralPath $baselineLib -File | Where-Object Name -ne 'kronos-server-1.0.jar')
    $newDeps=@(Get-ChildItem -LiteralPath $releaseLib -File | Where-Object Name -ne 'kronos-server-1.0.jar')
    if($oldDeps.Count -ne $newDeps.Count){throw 'Hotfix changed the dependency file set.'}
    foreach($dep in $oldDeps){Require-Equal (Hash (Join-Path $releaseLib $dep.Name)) (Hash $dep.FullName) 'Hotfix changed a dependency.'}
    $hotfixEvidence=Get-Content -LiteralPath "$runtime/hitsplat-hotfix-10/regression.json" -Raw | ConvertFrom-Json
    Require-Equal $hotfixEvidence.result 'PASS' 'Native hitsplat regression has not passed.'
    Require-Equal $hotfixEvidence.oldJarSha256 $baselineHash 'Regression used a different baseline.'
    Require-Equal $hotfixEvidence.newJarSha256 $jarHash 'Regression used a different fixed jar.'
    if(!$hotfixEvidence.oldBuildReproduced -or $hotfixEvidence.assertions -lt 10){Block 'Regression must reproduce09 and assert the native hit/mask behavior.'}
    # Full dungeon lifecycle and entry screenshots are retained09 evidence, not
    # relabelled10 results. The exact-only-Hit delta and new native regression
    # justify reuse. A derived map proof must preserve every baseline result
    # and explicitly record its provenance; it is not a new map execution.
    $lifecycleExpectedHash=$baselineHash
}
$archive=[IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
try{
    foreach($entry in @('io/ruin/model/activities/hollowdepths/HollowDepthsRewards.class','io/ruin/model/activities/hollowdepths/HollowDepthsState.class','io/ruin/utility/AtomicPlayerSave.class')){
        if(!$archive.GetEntry($entry)){Block 'Selected jar lacks durable Hollow Depths recovery support.'}
    }
}finally{$archive.Dispose()}
$serverProperties=Read-Properties "$project/server.properties"
Require-Equal $serverProperties['offline_mode'] 'true' 'Production must use the reviewed local persistence mode.'
Require-Equal $serverProperties['cache_path'] 'Cache' 'Production cache path differs from the reviewed original184 location.'
Require-Equal $serverProperties['data_path'] 'kronos-server/data' 'Production data path differs from the reviewed location.'
$network=Get-Content -LiteralPath "$runtime/network.json" -Raw | ConvertFrom-Json
Require-Equal $network.bindAddress '0.0.0.0' 'Current launcher only supplies the stable account registry in configured public mode.'
if(!(Test-Path -LiteralPath "$runtime/accounts.properties")){Block 'Production stable account registry is missing.'}
$bots=Read-Properties "$runtime/adventurers.properties"
Require-Equal ([IO.Path]::GetFullPath($bots['storage'])) ([IO.Path]::GetFullPath("$runtime/autonomous-adventurers")) 'Bot storage differs from the protected production path.'

if($manifest.stage -ne $stage -or $manifest.revision -ne 184 -or $manifest.backup -ne $backup){Block 'Stage is not the verified original184 test fixture.'}
$cacheBaseline=@($manifest.production | Where-Object port -eq 7304)
if($cacheBaseline.Count -ne 1){Block 'Expected original cache process identity is missing.'}
else{
    $cacheProcess=Get-Process -Id $cacheBaseline[0].pid -ErrorAction SilentlyContinue
    if(!$cacheProcess -or $cacheProcess.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$cacheBaseline[0].startedUtc).ToUniversalTime().Ticks){Block 'Original cache process identity changed; review before applying.'}
    if(!(Get-NetTCPConnection -State Listen -LocalPort 7304 -OwningProcess $cacheBaseline[0].pid -ErrorAction SilentlyContinue)){Block 'Original cache listener is unavailable.'}
}
if(Get-NetTCPConnection -State Listen -LocalPort 13302 -ErrorAction SilentlyContinue){Block 'Production game must be gracefully stopped before applying.'}
if(Test-Path -LiteralPath "$runtime/game-server.pid"){
    $oldPid=[int]([IO.File]::ReadAllText("$runtime/game-server.pid").Trim())
    if(Get-Process -Id $oldPid -ErrorAction SilentlyContinue){Block 'Production game PID still exists; confirm graceful stop and rule out PID reuse.'}
}
if(Get-NetTCPConnection -State Listen -LocalPort 14312 -ErrorAction SilentlyContinue){Block 'Stop the staging game gracefully before applying; avoid overlapping game heaps.'}

$profileSource=Join-Path $profileFolder 'revision-184.properties'
$proofSource=Join-Path $profileFolder 'collision-audit.properties'
if($Action -eq 'Deploy'){
    Require-Equal ([IO.Path]::GetFullPath($manifest.gameLib)) ([IO.Path]::GetFullPath($releaseLib)) 'Stage manifest is not using the selected immutable candidate.'
    $profileSource=Join-Path $stage 'data/hollow-depths/revision-184.properties'
    $proofSource=Join-Path $stage 'data/hollow-depths/collision-audit.properties'
    $profile=Read-Properties $profileSource;$proof=Read-Properties $proofSource
    if($HitOnlyHotfix){
        $baselineProfile=Read-Properties "$profileFolder/revision-184.properties"
        $baselineProof=Read-Properties "$profileFolder/collision-audit.properties"
        Require-Equal $baselineProof['server.build-id'] $lifecycleExpectedHash 'Inherited collision proof must belong to the current09 baseline.'
        Require-Equal $proof['audit.inherited-from.build-id'] $lifecycleExpectedHash 'Derived collision proof must explicitly identify the09 baseline.'
        if(!$proof['audit.scope']){Block 'Derived collision proof must explain its reuse scope.'}
        if($profile.Count -ne $baselineProfile.Count){Block 'Hit-only fix changed the profile key set.'}
        foreach($key in $baselineProfile.Keys){if($key -ne 'server.build-id'){Require-Equal $profile[$key] $baselineProfile[$key] 'Hit-only fix changed a non-build asset setting.'}}
        foreach($key in $baselineProof.Keys){if($key -ne 'server.build-id'){Require-Equal $proof[$key] $baselineProof[$key] 'Derived map proof changed a baseline result.'}}
        foreach($key in $proof.Keys){if(!$baselineProof.ContainsKey($key) -and $key -notin @('audit.inherited-from.build-id','audit.scope')){Block 'Unexpected derived collision proof field.'}}
    }
    Require-Equal $profile['production.enabled'] 'true' 'Audited profile does not enable entry.'
    Require-Equal $profile['cache.revision'] '184' 'Profile revision is not184.'
    Require-Equal $profile['server.build-id'] $jarHash 'Asset profile fingerprint does not match the selected jar.'
    Require-Equal $proof['server.build-id'] $jarHash 'Collision evidence fingerprint does not match the selected jar.'
    Require-Equal $proof['cache.sha256'] $profile['cache.sha256'] 'Profile and collision evidence identify different caches.'
    Require-Equal $proof['maps.passed'] 'true' 'Retained collision evidence has not passed.'
    Require-Equal $proof['audit.version'] '2' 'Retained proof predates the client source-archive verification gate.'
    Require-Equal $proof['client.source-archives.passed'] 'true' 'Encrypted source archives have not passed the bounded native/client decoder checks.'
    $sourceRegionCount=@($profile.Keys | Where-Object {$_ -match '^source\.region\.[0-9]+\.landscape-sha256$'}).Count
    if($sourceRegionCount -lt 1 -or [int]$proof['client.source-regions.count'] -ne $sourceRegionCount){
        Block 'Client source-archive audit must cover exactly the unique source regions fingerprinted in the reviewed profile, with at least one region.'
    }
    if([int]$proof['maps.count'] -lt 23 -or @($proof.Keys | Where-Object {$_ -like 'template.*'}).Count -lt 20){Block 'Collision corpus is smaller than the reviewed 23 maps / 20 variants.'}
    $mapReport=Native-Proof "$stage/native-map-audit.txt" '(?m)^PASS native184 maps=\d+\b' 'Native map audit'
    if($mapReport -notmatch '(?m)^PASS native184 maps=(\d+)\b' -or [int]$matches[1] -lt 23){Block 'Native map audit must include at least23 maps.'}
    $null=Native-Proof "$stage/native-dynamic-regression.txt" '(?m)^PASS native184:' 'Dynamic-map allocator regression'
    $lifecycle=Native-Proof "$stage/native-lifecycle-probe.txt" '(?m)^PASS native lifecycle assertions=' 'Native gameplay/lifecycle'
    if($lifecycle -notmatch ('(?m)^server\.build-id='+[regex]::Escape($lifecycleExpectedHash)+'\s*$')){Block 'Lifecycle proof does not match the reviewed build or verified Hit-only baseline.'}
    if($lifecycle -notmatch ('(?m)^cache\.sha256='+[regex]::Escape($profile['cache.sha256'])+'\s*$')){Block 'Lifecycle proof is not bound to this cache fingerprint.'}
    $clientReviewPath=Join-Path $stage 'client-entry-review.json'
    if(!(Test-Path -LiteralPath $clientReviewPath)){Block 'Reviewed actual-client dungeon entry evidence for both QA clients is missing.'}
    else{
        try{
            $review=Get-Content -LiteralPath $clientReviewPath -Raw | ConvertFrom-Json
            Require-Equal $review.result 'PASS' 'Actual-client image review has not passed.'
            Require-Equal $review.serverBuildId $lifecycleExpectedHash 'Client image review belongs to a different reviewed build.'
            Require-Equal $review.cacheSha256 $profile['cache.sha256'] 'Client image review belongs to a different cache.'
            Require-Equal $review.clientSha256.ToLowerInvariant() (Hash $manifest.client) 'Client image review belongs to a different actual184 client.'
            foreach($account in @('hdqa1','hdqa2')){
                $captures=@($review.captures | Where-Object account -eq $account)
                if($captures.Count -ne 1){Block 'Exactly one reviewed dungeon-entry capture is required for each QA client.';continue}
                $capture=$captures[0]
                if([IO.Path]::IsPathRooted($capture.path)){throw 'Capture path must be stage-relative.'}
                $image=[IO.Path]::GetFullPath((Join-Path $stage $capture.path))
                if(!$image.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetExtension($image) -ne '.png'){throw 'Capture path must identify a PNG inside the stage.'}
                Assert-OrdinaryPath $image
                Require-Equal (Hash $image) $capture.sha256.ToLowerInvariant() 'Reviewed dungeon-entry capture changed.'
            }
        }catch{Block 'Actual-client review file is invalid or a retained capture is unavailable.'}
    }
    $null=Native-Proof "$stage/persistence-hdqa1-checks.properties" '(?m)^result=PASS\s*$' 'Native persistence'
    $null=Native-Proof "$stage/persistence-hdqa1-verify-restart.properties" '(?m)^result=PASS\s*$' 'Actual process-restart recovery'
    $stageCache=@(Get-ChildItem -LiteralPath "$stage/Cache" -File -Filter 'main_file_cache.*' | Sort-Object Name)
    $liveCache=@(Get-ChildItem -LiteralPath "$project/Cache" -File -Filter 'main_file_cache.*' | Sort-Object Name)
    if($stageCache.Count -ne $liveCache.Count -or $stageCache.Count -lt 2){Block 'Stage and production cache file sets differ.'}
    else{foreach($file in $stageCache){$peer=Join-Path "$project/Cache" $file.Name;if(!(Test-Path -LiteralPath $peer) -or (Hash $file.FullName) -ne (Hash $peer)){Block "Original184 cache differs: $($file.Name)"}}}
    if(Test-Path -LiteralPath "$project/kronos-server/data/content242.json"){Block 'Unexpected242 asset manifest in production data.'}
}
if($Start -and ([math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024) -lt 3840)){Block 'At least3840 MB free physical memory is required before starting the3GB game heap.'}
$status=if($blockers.Count){'BLOCKED'}else{'READY'}
[pscustomobject]@{mode=if($Apply){'EXPLICIT APPLY'}else{'READ-ONLY PREFLIGHT'};action=$Action;status=$status;release=$ReleaseName;jarSha256=$jarHash;protectedMailbox="$runtime/player-data/hollow-depths-mailbox";blockers=@($blockers)} | ConvertTo-Json -Depth 4
if(!$Apply){return}
if($blockers.Count){throw 'No deployment changes made: resolve all listed blockers and repeat preflight.'}

# Recheck just before mutation; the helper never terminates any process.
if(Get-NetTCPConnection -State Listen -LocalPort 13302,14312 -ErrorAction SilentlyContinue){throw 'A game listener appeared after preflight.'}
if((Hash $jars[0].FullName) -ne $jarHash){throw 'Candidate changed during preflight.'}
$before=Protected-State
$stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$receiptFolder=Join-Path $runtime "hollow-depths-deployments/$stamp"
Assert-OrdinaryPath $receiptFolder
New-Item -ItemType Directory -Path $receiptFolder -Force | Out-Null
Restrict-PrivateDirectory $receiptFolder
$privateState=Join-Path $receiptFolder 'private-state'
New-Item -ItemType Directory -Path $privateState -Force | Out-Null
Restrict-PrivateDirectory $privateState
# A fresh stopped-world snapshot, not merely the older pre-feature source backup.
foreach($relative in $before.Keys){
    $destination=Join-Path $privateState $relative
    New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $root $relative) -Destination $destination
    if((Hash $destination) -ne $before[$relative]){throw 'Private snapshot verification failed; no release/data selection changed.'}
}
$snapshotSource=Protected-State
if($before.Count -ne $snapshotSource.Count -or @($before.Keys | Where-Object {!$snapshotSource.ContainsKey($_) -or $before[$_] -ne $snapshotSource[$_]}).Count){throw 'Protected state changed during the stopped-world backup; no release/data selection changed.'}
$priorFiles=@($pointer,"$profileFolder/revision-184.properties","$profileFolder/collision-audit.properties")
foreach($file in $priorFiles){if(Test-Path -LiteralPath $file){Copy-Item -LiteralPath $file -Destination (Join-Path $receiptFolder ([IO.Path]::GetFileName($file)))}}
$priorLogs=@{}
foreach($name in @('game-server.out.log','game-server.err.log')){
    $source=Join-Path "$runtime/logs" $name
    if(Test-Path -LiteralPath $source){
        $destination=Join-Path $receiptFolder $name
        Copy-Item -LiteralPath $source -Destination $destination
        $priorLogs[$name]=Hash $destination
        if($priorLogs[$name] -ne (Hash $source)){throw 'Stopped production log changed while preserving startup/rollback evidence.'}
    }
}
$receipt=[ordered]@{createdUtc=[DateTime]::UtcNow.ToString('o');action=$Action;priorRelease=$selected;selectedRelease=$ReleaseName;jarSha256=$jarHash;status='PRIVATE_SNAPSHOT_VERIFIED';privateState=$privateState;protectedState=$before;priorLogs=$priorLogs}
if($HitOnlyHotfix){$receipt['verificationScope']='Only Hit.class differs; fresh native hit/mask regression; inherited09 map/lifecycle/client entry evidence justified by exact unchanged map/cache/resource/dependency bytes. No new full map/lifecycle run claimed.';$receipt['hotfixRegression']=$hotfixEvidence}
[IO.File]::WriteAllText("$receiptFolder/receipt.json",($receipt | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
New-Item -ItemType Directory -Path $profileFolder -Force | Out-Null
if($Action -eq 'Deploy'){
    Atomic-Bytes "$profileFolder/collision-audit.properties" ([IO.File]::ReadAllBytes($proofSource))
    Atomic-Bytes "$profileFolder/revision-184.properties" ([IO.File]::ReadAllBytes($profileSource))
    Atomic-Bytes $pointer ([Text.Encoding]::ASCII.GetBytes($ReleaseName+"`r`n"))
}else{
    $text=[IO.File]::ReadAllText($profileSource)
    if([regex]::Matches($text,'(?m)^production\.enabled=').Count -ne 1){throw 'Emergency disable requires exactly one explicit production.enabled setting.'}
    $text=[regex]::Replace($text,'(?m)^production\.enabled=[^\r\n]*','production.enabled=false')
    Atomic-Bytes $profileSource ([Text.UTF8Encoding]::new($false).GetBytes($text))
}
$after=Protected-State
if($before.Count -ne $after.Count -or @($before.Keys | Where-Object {!$after.ContainsKey($_) -or $before[$_] -ne $after[$_]}).Count){throw "Protected account/bot/player state changed unexpectedly; do not start. Review $receiptFolder"}
$receipt.status='APPLIED_GAME_STOPPED';$receipt.profileSha256=Hash "$profileFolder/revision-184.properties"
[IO.File]::WriteAllText("$receiptFolder/receipt.json",($receipt | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
"Applied only profile/proof/release selection. Private player/account/bot bytes unchanged. Receipt: $receiptFolder/receipt.json"
if($Start){& "$root/Start-Kronos.ps1" -NoClient}
