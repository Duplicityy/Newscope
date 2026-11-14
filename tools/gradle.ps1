param(
    [ValidateSet('server', 'client')][string]$Project = 'server',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks = @('tasks')
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = (Get-ChildItem (Join-Path $PSScriptRoot 'jdk8') -Directory | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME = Join-Path $workspace '.gradle-home'
$projectRoot = Join-Path $workspace 'kronos-osrs-184/Kronos-master'
if ($Project -eq 'client') {
    $arguments = @('-p', (Join-Path $projectRoot 'runelite'))
} else {
    $arguments = @('-p', $projectRoot)
}
& (Join-Path $projectRoot 'gradlew.bat') @arguments --console=plain --max-workers=2 --no-daemon @Tasks
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
