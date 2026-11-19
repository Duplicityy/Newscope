param(
    [Parameter(Mandatory)][string]$Build,
    [Parameter(Mandatory)][string]$Output
)

$ErrorActionPreference = 'Stop'
$buildPath = (Resolve-Path -LiteralPath $Build).Path
$outputPath = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $outputPath) { throw 'Select a fresh check output directory' }
if ($outputPath.StartsWith($buildPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Write the test results outside the development build'
}
$manifest = Get-Content -LiteralPath "$buildPath/WORLD242-DEV.json" -Raw | ConvertFrom-Json
if ($manifest.kind -ne 'NEWSCAPE_WORLD242_DEVELOPMENT' -or $manifest.path -ne $buildPath -or $manifest.copiedPlayerData) {
    throw 'Expected an isolated development build'
}
$jdk = $manifest.jdk
$cp = $manifest.gameClasspath
New-Item -ItemType Directory -Path "$outputPath/classes","$outputPath/home","$outputPath/player-data/players" | Out-Null
& "$jdk/bin/javac.exe" '-J-Xmx96m' -cp $cp -d "$outputPath/classes" "$PSScriptRoot/checks/World242AccessChecks.java" "$PSScriptRoot/checks/WorldNpcDialogueChecks.java"
if ($LASTEXITCODE -ne 0) { throw 'World access check compilation failed' }
& "$jdk/bin/java.exe" '-Xmx512m' '-XX:+UseSerialGC' '-Xverify:all' "-Duser.home=$outputPath/home" "-Dkronos.playerData=$outputPath/player-data" "-Dkronos.accounts=$buildPath/state/accounts.properties" -cp "$outputPath/classes;$cp" kilim.tools.Kilim World242AccessChecks $buildPath *> "$outputPath/access.log"
$testExit = $LASTEXITCODE
Get-Content -LiteralPath "$outputPath/access.log" -Tail 35
if ($testExit -ne 0) { throw 'Native access checks failed' }
