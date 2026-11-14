<#
.SYNOPSIS
Read-only health check after an explicitly reviewed Hollow Depths184 deployment.
.DESCRIPTION
Does not start/stop servers, log an account in, invoke portal actions, enter dungeons,
save players, create maps, or award anything. Uses anonymous cache/login handshakes
and a reflection-only JVM agent that reads the actual world executor/Kilim classloader.
Writes only a private health report and compilation files under local-runtime.
This is operational verification, not evidence of organic combat/pacing or user login.

Example after deployment:
  .\tools\hollow-depths\Check-HollowDepths-Live.ps1 -ExpectedJarSha256 <reviewed SHA> `
    -DeploymentReceipt <local-runtime/hollow-depths-deployments/.../receipt.json>
Use -CompileOnly to validate the helper without inspecting or attaching to any server.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^([a-fA-F0-9]{64})?$')][string]$ExpectedJarSha256='',
    [string]$DeploymentReceipt='',
    [switch]$ExpectEntriesDisabled,
    [switch]$CompileOnly
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Split-Path (Split-Path $PSScriptRoot -Parent) -Parent))
$runtime=Join-Path $root 'local-runtime'
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Read-Properties([string]$Path){
    $values=@{};foreach($line in [IO.File]::ReadAllLines($Path)){if($line -match '^\s*([^#!\s][^=]*?)\s*=\s*(.*?)\s*$'){$values[$matches[1]]=$matches[2]}};return $values
}
function Private-Directory([string]$Path){
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
    foreach($identity in @([Security.Principal.WindowsIdentity]::GetCurrent().User,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))){
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,[Security.AccessControl.FileSystemRights]::FullControl,
            ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),
            [Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Listener([string]$Name,[int]$Port,[string]$Main){
    $processId=[int]([IO.File]::ReadAllText("$runtime/$Name.pid").Trim())
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
    if(!$process -or !$process.CommandLine.Trim().EndsWith($Main) -or $process.CommandLine -match 'kronos\.[^\s]*\.test=true'){throw "Unrecognized production $Name process; no attach attempted."}
    if(!(Get-NetTCPConnection -State Listen -LocalPort $Port -OwningProcess $processId -ErrorAction SilentlyContinue)){throw "Production $Name is not listening on expected port$Port."}
    return [pscustomobject]@{pid=$processId;startedUtc=(Get-Process -Id $processId).StartTime.ToUniversalTime().ToString('o');command=$process.CommandLine}
}
function Handshake([int]$Port,[byte[]]$Request,[int]$ResponseLength){
    $socket=[Net.Sockets.TcpClient]::new()
    try{
        $socket.ReceiveTimeout=5000;$socket.SendTimeout=5000
        $pending=$socket.BeginConnect('127.0.0.1',$Port,$null,$null)
        if(!$pending.AsyncWaitHandle.WaitOne(5000)){throw "Local handshake timed out on port$Port."}
        $socket.EndConnect($pending);$stream=$socket.GetStream();$stream.Write($Request,0,$Request.Length)
        $reply=[byte[]]::new($ResponseLength);$offset=0
        while($offset -lt $reply.Length){$read=$stream.Read($reply,$offset,$reply.Length-$offset);if($read -le 0){throw "Handshake closed early on port$Port."};$offset+=$read}
        if($reply[0] -ne 0){throw "Unexpected handshake status on port$Port."}
        # The login challenge is deliberately neither displayed nor retained.
    }finally{$socket.Dispose()}
}
function Compare-SavedXp([string]$Before,[string]$After,[bool]$Bot){
    try{
        $prior=Get-Content -LiteralPath $Before -Raw | ConvertFrom-Json
        $actual=Get-Content -LiteralPath $After -Raw | ConvertFrom-Json
    }catch{throw 'A private player save is missing or unreadable; inspect protected local files without displaying their contents.'}
    if($Bot){$prior=$prior.player;$actual=$actual.player}
    $a=@($prior.stats.stats);$b=@($actual.stats.stats)
    if($a.Count -ne 23 -or $b.Count -ne 23){throw 'Saved player skill vector is incomplete.'}
    for($i=0;$i -lt 23;$i++){
        if([double]$b[$i].experience+0.0001 -lt [double]$a[$i].experience){throw 'A saved skill has less XP than the deployment snapshot; inspect private state before declaring health.'}
    }
    return $a
}
$stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$output=Join-Path $runtime "hollow-depths-live-health/$stamp"
Private-Directory $output
$name='HollowDepthsLiveHealth_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$source=[IO.File]::ReadAllText("$PSScriptRoot/HollowDepthsLiveHealth.java").Replace('HollowDepthsLiveHealth',$name)
[IO.File]::WriteAllText("$output/$name.java",$source,[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx128m -encoding UTF-8 -cp "$jdk/lib/tools.jar" -d $output "$output/$name.java"
if($LASTEXITCODE){throw 'Read-only production health helper failed Java8 compilation.'}
[IO.File]::WriteAllText("$output/MANIFEST.MF","Manifest-Version: 1.0`nAgent-Class: $name`n`n",[Text.Encoding]::ASCII)
$jarArgs=@('-J-Xmx64m','cfm',"$output/$name.jar","$output/MANIFEST.MF")
foreach($class in Get-ChildItem -LiteralPath $output -Filter "$name*.class"){$jarArgs+=@('-C',$output,$class.Name)}
& "$jdk/bin/jar.exe" @jarArgs
if($LASTEXITCODE){throw 'Read-only production health helper packaging failed.'}
if($CompileOnly){"PASS: read-only Java8 helper compiled; no live process inspected, handshake sent or agent attached. $output";return}
if(!$ExpectedJarSha256 -or !$DeploymentReceipt){throw 'ExpectedJarSha256 and DeploymentReceipt are required for production verification.'}
$expected=$ExpectedJarSha256.ToLowerInvariant()
$receiptPath=[IO.Path]::GetFullPath($DeploymentReceipt)
if(!$receiptPath.StartsWith((Join-Path $runtime 'hollow-depths-deployments')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Deployment receipt must remain in the private local deployment directory.'}
$receipt=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
if($receipt.jarSha256 -ne $expected -or $receipt.status -ne 'APPLIED_GAME_STOPPED'){throw 'Deployment receipt is not an applied selection of this reviewed hash.'}
$release=([IO.File]::ReadAllText("$runtime/game-server-release.txt")).Trim()
if($release -notmatch '^hollow-depths-184-[a-zA-Z0-9_-]+$' -or $release -ne $receipt.selectedRelease){throw 'Production pointer is not the applied Hollow Depths184 release.'}
$jars=@(Get-ChildItem -LiteralPath "$runtime/server-releases/$release/lib" -Filter 'kronos-server*.jar')
if($jars.Count -ne 1 -or (Hash $jars[0].FullName) -ne $expected){throw 'Selected server jar does not match expected final bytes.'}
$game=Listener 'game-server' 13302 'io.ruin.Server'
$cache=Listener 'update-server' 7304 'io.ruin.update.Server'
if(!$game.command.Contains($release)){throw 'Running production game command does not use the selected immutable release.'}
$original=Get-Content -LiteralPath "$root/server-backups/before-hollow-depths-20261113-023753/evidence/live-process-identities.json" -Raw | ConvertFrom-Json | Where-Object name -eq 'update-server'
if($cache.pid -ne $original.pid -or ([DateTime]$cache.startedUtc).ToUniversalTime().Ticks -ne ([DateTime]$original.startedUtc).ToUniversalTime().Ticks){throw 'Original cache process identity changed; review this independently before declaring unchanged-cache health.'}
$profile=Read-Properties "$root/kronos-osrs-184/Kronos-master/kronos-server/data/hollow-depths/revision-184.properties"
if($profile['server.build-id'] -ne $expected){throw 'Production asset profile does not match the selected build.'}
$botConfig=Read-Properties "$runtime/adventurers.properties"
if($botConfig['enabled'] -ne 'true'){throw 'Expected ordinary autonomous population is not enabled.'}
$population=[int]$botConfig['population']
$privateState=[IO.Path]::GetFullPath($receipt.privateState)
if(!$privateState.StartsWith((Split-Path $receiptPath -Parent)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Private snapshot lies outside its deployment receipt directory.'}
$baselineBots=Join-Path $privateState 'local-runtime/autonomous-adventurers'
$baselinePlayers=Join-Path $privateState 'local-runtime/player-data/players'
$request=@("workspace=$($root.Replace('\','/'))","pid=$($game.pid)","jar=$($jars[0].FullName.Replace('\','/'))","sha256=$expected",("entries.enabled="+(!$ExpectEntriesDisabled).ToString().ToLowerInvariant()),"cache.sha256=$($profile['cache.sha256'])","bots.expected=$population")
$botFiles=@(Get-ChildItem -LiteralPath $baselineBots -File -Filter '*.json' | Where-Object BaseName -Match '^(?:[1-9]|1[0-9]|2[0-5])$')
if($botFiles.Count -ne $population){throw 'Fresh stopped-world bot snapshot does not match the expected population.'}
foreach($file in $botFiles){
    $prior=Compare-SavedXp $file.FullName "$runtime/autonomous-adventurers/$($file.Name)" $true
    $xp=@($prior | ForEach-Object {([double]$_.experience).ToString('R',[Globalization.CultureInfo]::InvariantCulture)}) -join ','
    $request+="baseline.bot.$($file.BaseName).xp=$xp"
}
$ordinarySaves=0
if(Test-Path -LiteralPath $baselinePlayers){foreach($file in Get-ChildItem -LiteralPath $baselinePlayers -File -Filter '*.json'){
    $null=Compare-SavedXp $file.FullName "$runtime/player-data/players/$($file.Name)" $false;$ordinarySaves++
}}
[IO.File]::WriteAllLines("$output/request.properties",$request,[Text.Encoding]::ASCII)
Handshake 7304 ([byte[]](15,0,0,0,184)) 1
Handshake 13302 ([byte[]](14)) 9
# Revalidate process start time immediately before attaching; PID reuse must fail closed.
if((Get-Process -Id $game.pid).StartTime.ToUniversalTime().Ticks -ne ([DateTime]$game.startedUtc).ToUniversalTime().Ticks){throw 'Production game identity changed before read-only attach.'}
& "$jdk/bin/java.exe" -Xmx96m -cp "$output;$jdk/lib/tools.jar" $name $game.pid "$output/$name.jar" "$output/request.properties"
if($LASTEXITCODE){throw "Read-only native health failed. Private report directory: $output"}
$native=Read-Properties "$output/native-health.properties"
if($native['result'] -ne 'PASS'){throw 'Native health report is not PASS.'}
$summary=[ordered]@{result='PASS';scope='Operational health only; no gameplay, authenticated-login or organic-combat claim';release=$release;jarSha256=$expected;gamePid=$game.pid;originalCachePid=$cache.pid;cache184Handshake='PASS';anonymousGameHandshake='PASS';entriesEnabled=$native['entries.enabled'];recoveryAvailable=$native['recovery.available'];portalBinding=$native['portal.binding'];tickBefore=[long]$native['tick.before'];tickAfter=[long]$native['tick.after'];onlineHumans=[int]$native['online.humans'];onlineBots=[int]$native['online.bots'];botSavedAndLiveProgression='retained';ordinarySavedSkillVectorsChecked=$ordinarySaves;privateReport=$output}
$json=$summary | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText("$output/summary.json",$json,[Text.UTF8Encoding]::new($false))
$json
