param(
    [Parameter(Mandatory=$true)][string]$Workspace,
    [Parameter(Mandatory=$true)][string]$Work,
    [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $Workspace).Path
$workRoot=(Resolve-Path -LiteralPath $Work).Path
$out=[IO.Path]::GetFullPath($Output)
if(Test-Path -LiteralPath $out){throw 'Choose a new package directory; existing files are preserved'}
$baseline="$workRoot/clients-candidate-05/Kronos-Windows"
$cache="$workRoot/full-area/native-cache-v14"
$packed=Get-Content -LiteralPath "$cache/PACKED.json" -Raw|ConvertFrom-Json
if(!$packed.complete){throw 'Cache snapshot is not complete'}
foreach($entry in $packed.cacheHashes.PSObject.Properties){
    if((Get-FileHash -LiteralPath "$cache/Cache/$($entry.Name)").Hash -ne $entry.Value){throw 'Frozen cache hash mismatch'}
}
$bundle="$out/Newscape-Windows"
New-Item -ItemType Directory -Path "$bundle/cache/Cache","$out/build/selector","$out/build/bootstrap" -Force|Out-Null
foreach($name in @('app','runtime','licenses')){Copy-Item -LiteralPath "$baseline/$name" -Destination $bundle -Recurse}
$files=@(Get-ChildItem -LiteralPath "$cache/Cache" -File|Sort-Object Name)
$manifest=@('release=184728-fresh1',"count=$($files.Count)")
for($n=0;$n -lt $files.Count;$n++){
    $file=$files[$n]
    Copy-Item -LiteralPath $file.FullName -Destination "$bundle/cache/Cache/$($file.Name)"
    $manifest+=@("file.$n=$($file.Name)","sha256.$n=$((Get-FileHash -LiteralPath $file.FullName).Hash.ToLower())","bytes.$n=$($file.Length)")
}
$manifest|Set-Content -LiteralPath "$bundle/cache/cache-manifest.properties" -Encoding ascii
$jdk="$root/tools/jdk8/jdk8u512-b09/bin"
$source="$PSScriptRoot/../../runelite/runelite-client/src/main/java/net/runelite/standalone/WorldMapRegion.java"
& "$jdk/javac.exe" -J-Xmx256m -cp "$bundle/app/client.jar" -d "$out/build/selector" $source
if($LASTEXITCODE -ne 0){throw 'Cache selector compile failed'}
& "$jdk/jar.exe" uf "$bundle/app/client.jar" -C "$out/build/selector" net/runelite/standalone/WorldMapRegion.class
if($LASTEXITCODE -ne 0){throw 'Cache selector package failed'}
& "$jdk/javac.exe" -J-Xmx128m -d "$out/build/bootstrap" "$PSScriptRoot/windows/WindowsFreshCacheLauncher.java"
if($LASTEXITCODE -ne 0){throw 'Bootstrap compile failed'}
& "$jdk/jar.exe" cf "$bundle/app/newscape-launcher.jar" -C "$out/build/bootstrap" .
if($LASTEXITCODE -ne 0){throw 'Bootstrap package failed'}
foreach($name in @('Play Newscape.cmd','Fresh Cache.cmd','READ ME.txt')){Copy-Item -LiteralPath "$PSScriptRoot/windows/$name" -Destination "$bundle/$name"}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip="$out/Newscape-Windows-Full.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($bundle,$zip,[IO.Compression.CompressionLevel]::Optimal,$false)
[ordered]@{edition='Windows full fresh cache';cacheRelease='184728-fresh1';zipSha256=(Get-FileHash -LiteralPath $zip).Hash;zipBytes=(Get-Item -LiteralPath $zip).Length;clientSha256=(Get-FileHash -LiteralPath "$bundle/app/client.jar").Hash;cacheFiles=$files.Count;android='Unchanged code16';server='Unchanged'}|ConvertTo-Json|Set-Content -LiteralPath "$out/package-receipt.json" -Encoding utf8
