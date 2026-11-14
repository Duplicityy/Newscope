$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$launcher = Join-Path $PSScriptRoot 'intellij/bin/idea64.exe'
Get-Process -Name idea64 -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $launcher -and $_.MainWindowHandle -eq 0 } | Stop-Process
# IntelliJ is the interactive editor the user needs to see and control.
Start-Process -FilePath $launcher -ArgumentList ('"' + (Join-Path $workspace 'kronos-osrs-184/Kronos-master') + '"') -WindowStyle Normal
