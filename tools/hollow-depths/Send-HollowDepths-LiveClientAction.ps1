<# Queue ordinary client UI input only. Never attaches to or invokes a server fixture. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SmokeDirectory,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]{1,70}$')][string]$Phase,
    [ValidateSet('capture','click','widget','menu','command','exit')][string]$Action='capture',
    [ValidateRange(0,4096)][int]$X=0,[ValidateRange(0,4096)][int]$Y=0,[string]$Text='',
    [int]$Param0=0,[int]$Param1=0,[int]$Type=0,[int]$Id=0,[string]$Option='',[string]$Target='',
    [ValidateRange(0,15000)][int]$WaitMs=1000,
    [ValidateRange(0,3000)][int]$SettleMs=3000
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=[IO.Path]::GetFullPath((Split-Path (Split-Path $PSScriptRoot -Parent) -Parent))
$parent=[IO.Path]::GetFullPath((Join-Path $root 'local-runtime/hollow-depths-live-client'))
$smoke=[IO.Path]::GetFullPath($SmokeDirectory)
if((Split-Path $smoke -Parent) -ne $parent){throw 'Client commands must remain in a private live-smoke runtime directory.'}
$manifest=Get-Content -LiteralPath "$smoke/client-smoke.json" -Raw | ConvertFrom-Json
if($manifest.directory -ne $smoke -or !$manifest.launchAuthorized){throw 'This harness has not been explicitly launched for a production smoke.'}
$output=Join-Path $smoke 'captures'
if(!(Test-Path -LiteralPath "$output/client.ready")){throw 'Ordinary client login/onboarding has not reached its UI control loop.'}
if(Test-Path -LiteralPath "$output/client-fatal.txt"){throw 'Client reported a fatal failure; inspect its private report before continuing.'}
$clientPid=[int]([IO.File]::ReadAllText("$smoke/client.pid").Trim())
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$clientPid"
if(!$process -or !$process.CommandLine.Contains('-Dkronos.hollowDepths.liveSmoke=true') -or $process.CommandLine.Contains('-Dkronos.hollowDepths.test=true') -or !$process.CommandLine.Contains($smoke) -or !$process.CommandLine.Trim().EndsWith($manifest.className)){throw 'Unverified ordinary-player smoke client process.'}
if($Action -eq 'command' -and $Text -cne '::hollow'){throw 'Keyboard command action permits only ordinary ::hollow.'}
foreach($value in @($Text,$Option,$Target)){
    if($value -match '[\r\n\\]' -or $value -match '[^\x20-\x7e]'){throw 'UI action text must be printable ASCII without escapes or newlines.'}
}
$file=Join-Path $output "commands/$Phase.cmd"
if(Get-ChildItem -LiteralPath "$output/commands" -Filter "$Phase.*"){throw 'Use a fresh phase name for each UI action.'}
$lines=@("action=$Action","x=$X","y=$Y","text=$Text","param0=$Param0","param1=$Param1","type=$Type","id=$Id","option=$Option","target=$Target","waitMs=$WaitMs","settleMs=$SettleMs")
[IO.File]::WriteAllLines("$file.tmp",$lines,[Text.Encoding]::ASCII)
Move-Item -LiteralPath "$file.tmp" -Destination $file
"Queued ordinary client UI action $Action. Private result/capture directory: $output"
