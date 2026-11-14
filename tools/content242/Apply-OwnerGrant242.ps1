param(
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$Stage,
    [Parameter(Mandatory=$true)][string]$Payload,
    [Parameter(Mandatory=$true)][string]$PlayerSave,
    [string]$AccountRegistry,
    [string]$GameJar,
    [string]$ExpectedGameJarSha256,
    [string]$ExpectedPayloadSha256,
    [string]$ExpectedJournalSha256,
    [string]$ReleasePointer,
    [int]$StoppedGamePid,
    [switch]$Confirm242Deployment,
    [switch]$Apply,
    [switch]$Rollback,
    [switch]$CheckRollback
)
$ErrorActionPreference='Stop'
function Restrict-GrantReceipt([string]$Folder){
    $current=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    foreach($entry in @((Get-Item -LiteralPath $Folder)) + @(Get-ChildItem -LiteralPath $Folder -Recurse -Force)){
        if($entry.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Private grant receipt cannot contain a reparse point'}
        $acl=if($entry.PSIsContainer){[Security.AccessControl.DirectorySecurity]::new()}else{[Security.AccessControl.FileSecurity]::new()}
        $acl.SetAccessRuleProtection($true,$false)
        $inherit=if($entry.PSIsContainer){[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'}else{[Security.AccessControl.InheritanceFlags]::None}
        foreach($identity in @($current,'S-1-5-18','S-1-5-32-544')){
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($identity),[Security.AccessControl.FileSystemRights]::FullControl,$inherit,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
        }
        Set-Acl -LiteralPath $entry.FullName -AclObject $acl
    }
}
$script=Join-Path $PSScriptRoot 'owner_grant.py'
$payloadPath=(Resolve-Path -LiteralPath $Payload).Path
$playerPath=(Resolve-Path -LiteralPath $PlayerSave).Path
$stagePath=(Resolve-Path -LiteralPath $Stage).Path
if(([int]$Apply.IsPresent+[int]$Rollback.IsPresent+[int]$CheckRollback.IsPresent) -gt 1){throw 'Choose only one grant action'}
if(!$Apply -and !$Rollback -and !$CheckRollback){
    & $Python -B $script preflight --stage $stagePath --payload $payloadPath --player $playerPath
    if($LASTEXITCODE -ne 0){throw 'Owner grant preflight failed'}
    return
}
if(!$Confirm242Deployment -or !$AccountRegistry -or !$GameJar -or !$ReleasePointer -or $StoppedGamePid -le 0 -or $ExpectedGameJarSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $ExpectedPayloadSha256 -notmatch '^[a-fA-F0-9]{64}$'){
    throw 'Grant action requires explicit242 deployment confirmation, registry, exact selected release/payload hashes and stopped game PID'
}
if((Get-FileHash -LiteralPath $payloadPath -Algorithm SHA256).Hash -ine $ExpectedPayloadSha256){throw 'Grant payload hash differs'}
if(Get-Process -Id $StoppedGamePid -ErrorAction SilentlyContinue){throw 'The previously running game process has not exited'}
if(Get-NetTCPConnection -LocalPort 13302 -State Listen -ErrorAction SilentlyContinue){throw 'Production game port is still listening'}
$playerRoot=Split-Path (Split-Path $playerPath -Parent) -Parent
$normalizedPlayerRoot=$playerRoot.Replace('\','/').ToLowerInvariant()
foreach($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe'")){
    if($process.CommandLine -and $process.CommandLine.Trim().EndsWith('io.ruin.Server') -and $process.CommandLine.Replace('\','/').ToLowerInvariant().Contains($normalizedPlayerRoot)){
        throw 'A game process still references this player-data directory, even though its port may not be ready'
    }
}
$jar=(Resolve-Path -LiteralPath $GameJar).Path
if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ine $ExpectedGameJarSha256){throw 'Selected deployment game jar hash differs'}
$release=(Get-Content -LiteralPath $ReleasePointer -Raw).Trim()
if(!$release -or $release -notmatch '^[A-Za-z0-9._-]+$' -or (Split-Path (Split-Path (Split-Path $jar -Parent) -Parent) -Leaf) -cne $release){throw 'Release pointer does not select the supplied242 game jar'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try{if(!$zip.GetEntry('io/ruin/content/content242/ModernChargedWeapons.class')){throw 'Selected binary lacks native242 mechanics'}}finally{$zip.Dispose()}
$grant=Get-Content -LiteralPath $payloadPath -Raw | ConvertFrom-Json
if($grant.account -notmatch '^[a-z0-9_]{3,12}$' -or $grant.grantId -cne 'content242-owner-supported-kit-v1'){throw 'Invalid account-bound grant identity'}
$receipt=Join-Path $playerRoot (Join-Path 'content242-owner-grants' (Join-Path $grant.account $grant.grantId))
if($Apply){New-Item -ItemType Directory -Path $receipt -Force | Out-Null}
elseif(!(Test-Path -LiteralPath $receipt -PathType Container)){throw 'Rollback journal is absent; no account will be restored'}
$journal=Join-Path $receipt 'grant-journal.json'
if($ExpectedJournalSha256){
    if($ExpectedJournalSha256 -notmatch '^[a-fA-F0-9]{64}$' -or !(Test-Path -LiteralPath $journal -PathType Leaf) -or (Get-FileHash -LiteralPath $journal -Algorithm SHA256).Hash -ine $ExpectedJournalSha256){throw 'Grant journal hash differs'}
}
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
if($Apply){
    Restrict-GrantReceipt $receipt
}
$allowed=@($sid,'S-1-5-18','S-1-5-32-544')
foreach($entry in @((Get-Item -LiteralPath $receipt)) + @(Get-ChildItem -LiteralPath $receipt -Recurse -Force)){
    $rules=@((Get-Acl -LiteralPath $entry.FullName).Access)
    if(!$rules.Count){throw 'Private receipt entry has an empty ACL'}
    foreach($rule in $rules){
        $identity=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if($identity -notin $allowed){throw 'Private receipt folder contains an unexpected ACL principal'}
    }
}
$before=$env:KRONOS_OWNER_GRANT_STOPPED_GATE
try{
    $env:KRONOS_OWNER_GRANT_STOPPED_GATE='242'
    $action=if($CheckRollback){'check-rollback-stopped'}elseif($Rollback){'rollback-stopped'}else{'apply-stopped'}
    & $Python -B $script $action --stage $stagePath --payload $payloadPath --player $playerPath --registry (Resolve-Path -LiteralPath $AccountRegistry).Path --receipt-folder $receipt
    if($LASTEXITCODE -ne 0){throw 'Owner grant/rollback failed closed; keep242 selected and stopped, inspect its private journal before any retry'}
}finally{$env:KRONOS_OWNER_GRANT_STOPPED_GATE=$before}
