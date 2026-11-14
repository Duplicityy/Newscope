$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.StartsWith($workspace, [StringComparison]::OrdinalIgnoreCase) -and
    $_.CommandLine -match 'org\.gradle\.launcher\.daemon|org\.jetbrains\.kotlin\.daemon\.KotlinCompileDaemon'
} | ForEach-Object {
    Stop-Process -Id $_.ProcessId
    Write-Output "Stopped finished build process $($_.ProcessId)."
}
