$ErrorActionPreference = 'Stop'
$downloads = Join-Path $PSScriptRoot 'downloads'
$jdkPackage = Get-Content (Join-Path $downloads 'jdk8-package.json') -Raw | ConvertFrom-Json
$ideaRelease = (Get-Content (Join-Path $downloads 'intellij-release.json') -Raw | ConvertFrom-Json).IIC[0]
$packages = @(
    @{ Name = 'jdk8'; Url = $jdkPackage.link; Hash = $jdkPackage.checksum; Archive = 'jdk8.zip' },
    @{ Name = 'intellij'; Url = $ideaRelease.downloads.windowsZip.link; HashUrl = $ideaRelease.downloads.windowsZip.checksumLink; Archive = 'intellij.zip' }
)
foreach ($package in $packages) {
    $archive = Join-Path $downloads $package.Archive
    Write-Output "Downloading $($package.Name)..."
    & curl.exe --fail --location --silent --show-error --retry 3 --connect-timeout 30 --continue-at - --output $archive $package.Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $($package.Name)" }
    $expected = $package.Hash
    if (!$expected) { $expected = ((Invoke-WebRequest $package.HashUrl).Content -split '\s+')[0] }
    $actual = (Get-FileHash $archive -Algorithm SHA256).Hash
    if ($actual -ne $expected) { throw "Checksum mismatch: $($package.Name)" }
    $destination = Join-Path $PSScriptRoot $package.Name
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Write-Output "Extracting $($package.Name)..."
    & tar.exe -xf $archive -C $destination
    if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $($package.Name)" }
    Write-Output "Installed and verified $($package.Name)."
}
