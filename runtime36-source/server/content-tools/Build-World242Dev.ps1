param(
    [Parameter(Mandatory)][string]$Workspace,
    [Parameter(Mandatory)][string]$Baseline,
    [Parameter(Mandatory)][string]$Output,
    [Parameter(Mandatory)][string]$Lombok
)

$ErrorActionPreference = 'Stop'
$checkout = Split-Path $PSScriptRoot -Parent
$server = Join-Path $checkout 'Kronos-master/kronos-server'
$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
$out = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $out) { throw 'Select a fresh development output directory' }
if ($out.StartsWith($baselinePath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Select an output directory outside the baseline runtime'
}
$manifest = Get-Content -LiteralPath "$baselinePath/CANDIDATE.json" -Raw | ConvertFrom-Json
if ([IO.Path]::GetFullPath($manifest.candidate) -ne $baselinePath -or $manifest.publicAuthorized) {
    throw 'Expected the private baseline manifest'
}
$runtime = Join-Path $workspacePath 'local-runtime'
$bundle = Join-Path $runtime 'content242-releases/content242-full-final-01'
$six = Get-Content -LiteralPath "$runtime/six-systems-active.json" -Raw | ConvertFrom-Json
$jdk = Join-Path $workspacePath 'tools/jdk8/jdk8u512-b09'
$lombokJar = (Resolve-Path -LiteralPath $Lombok).Path
$base = $manifest.baseRelease
$layers = "$base/classes;$($six.classes);$runtime/endgame-armor-20261114-110851/classes;$runtime/mystery-home/home-test-stage/classes;$runtime/chat-rank-fix;$bundle/game/*"
$javaFiles = @(
    'io/ruin/content/content242/World242.java',
    'io/ruin/content/content242/WorldNpcDialogues.java',
    'io/ruin/content/content242/WorldTravel242.java',
    'io/ruin/content/content242/WorldVerticalTravel.java',
    'io/ruin/content/content242/World242ObjectActions.java',
    'io/ruin/content/worldquest/RiverWatch.java',
    'io/ruin/content/worldquest/WorldQuestBook.java',
    'io/ruin/content/worldquest/WorldQuestState.java',
    'io/ruin/cache/NPCDef.java',
    'io/ruin/model/activities/cluescrolls/ClueObjects.java',
    'io/ruin/model/entity/npc/actions/guild/fishing/Roachey.java',
    'io/ruin/model/entity/npc/actions/piscatoris/ArnoldLydspor.java',
    'io/ruin/model/entity/npc/actions/traveling/LokarSearunner.java',
    'io/ruin/model/entity/npc/actions/zeah/Tynan.java',
    'io/ruin/model/entity/player/Player.java',
    'io/ruin/model/inter/handlers/TeleportInterface.java',
    'io/ruin/model/map/object/actions/impl/Ladder.java',
    'io/ruin/model/skills/cooking/Cooking.java',
    'io/ruin/model/skills/mining/Mining.java',
    'io/ruin/model/skills/woodcutting/Woodcutting.java'
)
$inputs = @($javaFiles | ForEach-Object { Join-Path "$server/src/main/java" $_ })
New-Item -ItemType Directory -Path "$out/overlay","$out/workdir","$out/state","$out/home/Desktop/kronos" | Out-Null
& "$jdk/bin/javac.exe" '-J-Xmx256m' '-encoding' 'UTF-8' -cp "$baselinePath/classes;$layers;$lombokJar" -processorpath $lombokJar -d "$out/overlay" @inputs
if ($LASTEXITCODE -ne 0) { throw 'World development compilation failed' }
foreach ($part in @('Cache','classes','data','updater-classes')) {
    Copy-Item -LiteralPath "$baselinePath/$part" -Destination $out -Recurse
}
foreach ($entry in $manifest.files) {
    if ((Get-FileHash -LiteralPath (Join-Path $out $entry.path)).Hash -ne $entry.sha256) {
        throw "Baseline file changed during copy $($entry.path)"
    }
}
$dataFiles = @(
    'npcs/combat/Cow_calf.json',
    'npcs/drops/eco/Cow_calf.json',
    'npcs/drops/pvp/Cow_calf.json',
    'npcs/spawns/fishing_guild.json',
    'npcs/spawns/dungeons/gnome_stronghold.json',
    'npcs/spawns/dungeons/smoke_devil_dungeon.json',
    'world-vertical-travel.json'
)
foreach ($path in $dataFiles) {
    Copy-Item -LiteralPath "$server/data/$path" -Destination "$out/data/$path"
}

$repairPath = Join-Path $PSScriptRoot 'registries/world242-landscape-key-repairs.json'
$landscapes = Get-Content -LiteralPath $repairPath -Raw | ConvertFrom-Json
if ($landscapes.schemaVersion -ne 1) { throw 'Unrecognized landscape repair format' }
foreach ($proof in $landscapes.cacheFilesSha256.PSObject.Properties) {
    $cacheFile = $manifest.files | Where-Object path -eq "Cache/$($proof.Name)"
    if ($cacheFile.sha256 -ne $proof.Value) { throw "Landscape proofs require the reviewed cache bytes for $($proof.Name)" }
}
$keysPath = "$out/data/region_keys.json"
if ((Get-FileHash -LiteralPath $keysPath).Hash -ne $landscapes.baselineRegionKeysSha256) {
    throw 'Landscape repairs require the reviewed baseline key manifest'
}
$keys = Get-Content -LiteralPath $keysPath -Raw | ConvertFrom-Json -AsHashtable
foreach ($repair in $landscapes.repairs) {
    $square = [string]$repair.mapSquare
    if (($keys[$square] -join ',') -ne ($repair.oldKeys -join ',') -or $repair.keys.Count -ne 4) {
        throw "Unexpected landscape key input for $square"
    }
    $keys[$square] = $repair.keys
}
$keys | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $keysPath -Encoding utf8NoBOM
Copy-Item -LiteralPath $repairPath -Destination "$out/LANDSCAPE-REPAIRS.json"
$dataFiles += 'region_keys.json'

$cache = "$out/Cache".Replace([char]92,[char]47)
$data = "$out/data".Replace([char]92,[char]47)
$settings = Get-Content -LiteralPath "$baselinePath/workdir/server.properties" |
    Where-Object { $_ -notmatch '^(cache_path|data_path|cache_port|world_address)\s*=' }
$settings += "cache_path=$cache","data_path=$data","cache_port=14304",'world_address=127.0.0.1:14302'
$settings | Set-Content -LiteralPath "$out/workdir/server.properties" -Encoding ascii
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(18))
$salt = [Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
$kdf = [Security.Cryptography.Rfc2898DeriveBytes]::new($password, $salt, 210000, [Security.Cryptography.HashAlgorithmName]::SHA256)
try { $credentialHash = [Convert]::ToBase64String($kdf.GetBytes(32)) } finally { $kdf.Dispose() }
@('worldfixture.id=1001','worldfixture.owner=false','worldfixture.admin=false','worldfixture.iterations=210000',
    "worldfixture.salt=$([Convert]::ToBase64String($salt))","worldfixture.hash=$credentialHash") |
    Set-Content -LiteralPath "$out/state/accounts.properties" -Encoding ascii
@{username='worldfixture'; password=$password} | ConvertTo-Json | Set-Content -LiteralPath "$out/state/fixture-login.json"
'enabled=false' | Set-Content -LiteralPath "$out/state/adventurers.properties" -Encoding ascii
New-Item -ItemType Directory -Path "$out/state/player-data/players" | Out-Null
'{"position":{"x":2028,"y":3577,"z":0}}' | Set-Content -LiteralPath "$out/state/player-data/players/worldfixture.json" -Encoding ascii
$receipt = [ordered]@{
    kind = 'NEWSCAPE_WORLD242_DEVELOPMENT'
    baseline = $baselinePath
    baselineManifestSha256 = (Get-FileHash -LiteralPath "$baselinePath/CANDIDATE.json").Hash
    path = $out
    jdk = $jdk
    gameClasspath = "$out/overlay;$out/classes;$layers"
    cacheClasspath = "$out/updater-classes;$base/updater-classes;$bundle/updater/*"
    sources = $javaFiles
    data = $dataFiles
    repairedLandscapes = @($landscapes.repairs).Count
    cacheVerifiedFiles = @($manifest.files | Where-Object { $_.path -like 'Cache/*' }).Count
    gamePort = 14302
    cachePort = 14304
    copiedPlayerData = $false
}
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$out/WORLD242-DEV.json"
Write-Output "World development build ready at $out"
