param(
    [ValidatePattern('^hollow-depths-[a-z0-9-]+$')][string]$StageName='hollow-depths-test-01',
    [ValidateSet('hdqa1','hdqa2')][string]$Account='hdqa1',
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]{1,70}$')][string]$Phase,
    [ValidateSet('capture','click','widget','menu','exit')][string]$Action='capture',
    [int]$X=0,[int]$Y=0,[string]$Text='',
    [int]$Param0=0,[int]$Param1=0,[int]$Type=0,[int]$Id=0,[string]$Option='',[string]$Target='',
    [ValidateRange(0,15000)][int]$WaitMs=1000,
    [ValidateRange(0,3000)][int]$SettleMs=3000
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stage=Join-Path $root "local-runtime/$StageName"
$output=Join-Path $stage "captures-$Account"
if(!(Test-Path -LiteralPath "$output/client.ready")){throw 'The disposable184 client is not ready'}
$clientPid=[int](Get-Content -LiteralPath "$stage/client-$Account.pid")
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$clientPid"
if(!$process -or !$process.CommandLine.Contains('-Dkronos.hollowDepths.test=true') -or !$process.CommandLine.Contains("-Dkronos.hollowDepths.account=$Account") -or !$process.CommandLine.Contains($stage)){throw 'Unverified disposable client process'}
foreach($value in @($Text,$Option,$Target)){if($value.Contains("`n") -or $value.Contains("`r") -or $value.Contains('\')){throw 'Action text cannot contain newlines or escape characters'}}
$file=Join-Path $output "commands/$Phase.cmd"
if(Get-ChildItem -LiteralPath "$output/commands" -Filter "$Phase.*"){throw 'Use a fresh phase name for every action'}
$lines=@("action=$Action","x=$X","y=$Y","text=$Text","param0=$Param0","param1=$Param1","type=$Type","id=$Id","option=$Option","target=$Target","waitMs=$WaitMs","settleMs=$SettleMs")
[IO.File]::WriteAllLines("$file.tmp",$lines,[Text.Encoding]::ASCII)
Move-Item -LiteralPath "$file.tmp" -Destination $file
"Queued $Action for $Account; result files will be in $output."
