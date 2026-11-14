param(
 [Parameter(Mandatory=$true)][ValidatePattern('^content242-expanded-test-[a-z0-9-]+$')][string]$StageName,
 [string]$MobileRoot='C:\K242-Mobile-QA',
 [switch]$InputTest,
 [switch]$ControlsTest,
 [string]$PasswordFile,
 [switch]$ProductionPaused
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Workspace.ps1"
$stage=Join-Path $root "local-runtime/$StageName"
$m=Get-Content -LiteralPath "$stage/content242-test.json" -Raw|ConvertFrom-Json
& "$PSScriptRoot/Test-Content242-Isolation.ps1" -StageName $StageName -ProductionPaused:$ProductionPaused
$assets=Join-Path $MobileRoot 'android/kronos/src/main/assets/jvm'
if((Get-FileHash -LiteralPath "$assets/client.jar" -Algorithm SHA256).Hash -ne $m.clientSha256){throw 'Mobile and actual staged desktop client hashes differ'}
if(!(Test-Path -LiteralPath "$stage/classes/net/runelite/standalone/TaskHandler.class")){throw 'Explicit isolated socket router is missing'}
if($InputTest){
 if(!$PasswordFile){throw 'Private disposable c242qa password file is required for input login'}
 $secret=(Resolve-Path -LiteralPath $PasswordFile).Path
 if(!$secret.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Password file must belong to this isolated stage'}
}
$out=Join-Path $stage 'mobile-bridge-smoke';New-Item -ItemType Directory -Force $out|Out-Null
$java=(Get-ChildItem "$root/tools/jdk21" -Directory|Select-Object -First 1).FullName+'/bin/java.exe'
$options=@('-Xmx512m','-Dsun.java2d.d3d=false','-Dkronos.desktopTest=true','-Dkronos.content242.test=true','-Dkronos.host=127.0.0.1',"-Duser.home=$out/home",'-Dkronos.testGamePort=14302','-Dcacio.managed.screensize=2304x1728')
foreach($pkg in @('java.base/java.lang','java.base/java.lang.reflect','java.base/java.util','java.desktop/java.awt','java.desktop/sun.java2d')){$options+="--add-opens=$pkg=ALL-UNNAMED"}
foreach($pkg in @('java.awt.peer','java.awt.dnd.peer','sun.awt','sun.awt.image','sun.awt.event','sun.awt.datatransfer','sun.java2d')){$options+="--add-exports=java.desktop/$pkg=ALL-UNNAMED"}
$options+='--add-exports=java.base/sun.security.action=ALL-UNNAMED'
if($ControlsTest){$options+='-Dkronos.testControls=true'}
if($InputTest){$options+=@('-Dkronos.testInput=true','-Dkronos.testUser=c242qa',"-Dkronos.testPasswordFile=$secret")}
$started=[DateTime]::UtcNow.ToString('o')
& $java @options -cp "$stage/classes;$assets/bridge.jar;$assets/client.jar;$assets/cacio-tta-1.18.jar;$assets/cacio-shared-1.18.jar" KronosBoot "$out/login.png" *> "$out/harness.log"
if($LASTEXITCODE){throw "Isolated mobile bridge smoke failed; inspect $out/harness.log"}
@{result='PASS';startedUtc=$started;finishedUtc=[DateTime]::UtcNow.ToString('o');clientSha256=$m.clientSha256;bridgeSha256=(Get-FileHash "$assets/bridge.jar" -Algorithm SHA256).Hash;inputLogin=[bool]$InputTest;controlsTest=[bool]$ControlsTest;scope='Desktop-hosted current mobile bridge, isolated loopback cache/game; not ARM Android device validation'}|ConvertTo-Json|Set-Content -LiteralPath "$out/result.json" -Encoding utf8
Write-Output "Isolated mobile bridge smoke passed; images and log: $out"
