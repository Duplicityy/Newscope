param(
    [switch]$Run,
    [string]$PreparedDirectory,
    [string]$MobileRoot='C:\K242-Mobile-QA',
    [string]$Apk
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Workspace.ps1"
$base=Join-Path $root 'local-runtime/content242-live-smoke'
$assets=Join-Path $MobileRoot 'android/kronos/src/main/assets/jvm'
$jdk=(Get-ChildItem "$root/tools/jdk21" -Directory | Select-Object -First 1).FullName
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Restrict([string]$Path){
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    foreach($item in @((Get-Item -LiteralPath $Path)) + @(Get-ChildItem -LiteralPath $Path -Recurse -Force)){
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Smoke paths cannot be reparse points'}
        $acl=if($item.PSIsContainer){[Security.AccessControl.DirectorySecurity]::new()}else{[Security.AccessControl.FileSecurity]::new()}
        $acl.SetAccessRuleProtection($true,$false)
        $inherit=if($item.PSIsContainer){[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'}else{[Security.AccessControl.InheritanceFlags]::None}
        foreach($identity in @($sid,'S-1-5-18','S-1-5-32-544')){
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($identity),[Security.AccessControl.FileSystemRights]::FullControl,$inherit,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
        }
        Set-Acl -LiteralPath $item.FullName -AclObject $acl
        if(@((Get-Acl -LiteralPath $item.FullName).Access).Count -ne 3){throw 'Unexpected smoke ACL rule count'}
    }
}
if(!$PreparedDirectory){
    if($Run){throw 'Prepare first, then supply its exact directory with -Run'}
    New-Item -ItemType Directory -Force $base | Out-Null
    Restrict $base
    $PreparedDirectory=Join-Path $base ((Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory $PreparedDirectory | Out-Null
    $username='m242'+[guid]::NewGuid().ToString('N').Substring(0,8)
    $secretBytes=New-Object byte[] 12;$random=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$random.GetBytes($secretBytes)}finally{$random.Dispose()}
    [IO.File]::WriteAllText((Join-Path $PreparedDirectory 'password.txt'),[Convert]::ToBase64String($secretBytes),[Text.UTF8Encoding]::new($false))
    [Array]::Clear($secretBytes,0,$secretBytes.Length)
    [IO.File]::WriteAllText((Join-Path $PreparedDirectory 'account.txt'),$username,[Text.UTF8Encoding]::new($false))
}
$out=(Resolve-Path -LiteralPath $PreparedDirectory).Path
if(!$out.StartsWith(([IO.Path]::GetFullPath($base)+[IO.Path]::DirectorySeparatorChar),[StringComparison]::OrdinalIgnoreCase)){throw 'Smoke output must be inside its private runtime folder'}
Restrict $out
$username=(Get-Content -LiteralPath "$out/account.txt" -Raw).Trim()
if($username -notmatch '^m242[a-f0-9]{8}$'){throw 'Only the newly generated disposable account is allowed'}
$build=Join-Path $out 'classes';New-Item -ItemType Directory -Force $build | Out-Null
& "$jdk/bin/javac.exe" -J-Xmx128m -cp "$assets/bridge.jar;$assets/client.jar" -d $build "$PSScriptRoot/Content242LiveMobileSmoke.java"
if($LASTEXITCODE){throw 'External smoke harness compilation failed'}
if(Get-ChildItem -LiteralPath $build -Recurse -Filter TaskHandler.class){throw 'A staged socket router must never enter production smoke'}
if(!$Run){"Prepared bounded smoke only; no network login/account creation. Directory: $out";return}
if(!$Apk){throw 'The final selected APK must be supplied explicitly'}
$runtime=Join-Path $root 'local-runtime'
$active=Get-Content -LiteralPath "$runtime/content242-active.json" -Raw | ConvertFrom-Json
$bundle=(Resolve-Path -LiteralPath $active.bundle).Path
$manifest=Get-Content -LiteralPath "$bundle/release-manifest.json" -Raw | ConvertFrom-Json
if((Hash "$bundle/release-manifest.json") -ne $active.manifestSha256 -or !$manifest.sealed){throw 'Selected sealed bundle identity differs'}
if((Hash $Apk) -ne $manifest.bindings.androidApkSha256 -or (Hash "$assets/client.jar") -ne $manifest.bindings.clientJarSha256){throw 'Mobile APK/client do not match selected release'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
try{
    foreach($name in @('client.jar','bridge.jar','cacio-tta-1.18.jar','cacio-shared-1.18.jar')){
        $entry=$archive.GetEntry("assets/jvm/$name");if(!$entry){throw 'Final APK is missing a JVM asset'}
        $stream=$entry.Open();$algorithm=[Security.Cryptography.SHA256]::Create()
        try{$digest=[BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose();$stream.Dispose()}
        if($digest -ne (Hash "$assets/$name")){throw 'Desktop smoke asset differs from final APK'}
    }
}finally{$archive.Dispose()}
foreach($role in @(@('game-server',13302,'io.ruin.Server'),@('update-server',7304,'io.ruin.update.Server'))){
    $serverPid=[int](Get-Content -LiteralPath "$runtime/$($role[0]).pid")
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    $listeners=@(Get-NetTCPConnection -LocalPort $role[1] -State Listen -ErrorAction SilentlyContinue)
    if(!$process -or !$process.CommandLine.Contains($bundle) -or !$process.CommandLine.Trim().EndsWith($role[2]) -or !$listeners -or @($listeners | Where-Object OwningProcess -ne $serverPid).Count){throw 'Expected production bundle is not listening'}
}
if(Test-Path -LiteralPath "$out/result.json"){throw 'This completed smoke account is not reusable'}
$registryPath=Join-Path $runtime 'accounts.properties'
if(Select-String -LiteralPath $registryPath -Pattern ('^'+[regex]::Escape($username)+'\.') -Quiet){throw 'Generated account name already exists; prepare a fresh smoke'}
New-Item -ItemType Directory -Force "$out/home" | Out-Null
$options=@('-Xmx512m','-Dsun.java2d.d3d=false','-Dkronos.liveSmoke=true','-Dkronos.desktopTest=true','-Dkronos.testInput=true','-Dkronos.host=127.0.0.1','-Dkronos.testGamePort=13302',('-Duser.home="'+$out+'/home"'),('-Dkronos.testUser='+$username),('-Dkronos.testPasswordFile="'+$out+'/password.txt"'),'-Dcacio.managed.screensize=2304x1728')
foreach($pkg in @('java.base/java.lang','java.base/java.lang.reflect','java.base/java.util','java.desktop/java.awt','java.desktop/sun.java2d')){$options+="--add-opens=$pkg=ALL-UNNAMED"}
foreach($pkg in @('java.awt.peer','java.awt.dnd.peer','sun.awt','sun.awt.image','sun.awt.event','sun.awt.datatransfer','sun.java2d')){$options+="--add-exports=java.desktop/$pkg=ALL-UNNAMED"}
$options+='--add-exports=java.base/sun.security.action=ALL-UNNAMED'
$options+=@('-cp',('"'+$build+';'+$assets+'/bridge.jar;'+$assets+'/client.jar;'+$assets+'/cacio-tta-1.18.jar;'+$assets+'/cacio-shared-1.18.jar"'),'Content242LiveMobileSmoke',('"'+$out+'"'))
$started=[DateTime]::UtcNow.ToString('o')
$client=Start-Process -FilePath "$jdk/bin/java.exe" -ArgumentList $options -WorkingDirectory $out -WindowStyle Hidden -PassThru -RedirectStandardOutput "$out/client.out.log" -RedirectStandardError "$out/client.err.log"
Set-Content -LiteralPath "$out/client.pid" -Value $client.Id -Encoding ASCII
if(!$client.WaitForExit(200000)){
    # Only the owned child may be terminated on a stuck external harness.
    $client.Kill();$client.WaitForExit();throw 'Bounded mobile smoke timed out; only its QA client was stopped'
}
if($client.ExitCode -ne 0){throw 'Mobile smoke failed; inspect its private logs'}
$native=Get-Content -LiteralPath "$out/client-result.json" -Raw | ConvertFrom-Json
if($native.result -ne 'PASS'){throw 'No completed mobile frame proof'}
$roleLines=@(Get-Content -LiteralPath $registryPath | Where-Object {$_ -match ('^'+[regex]::Escape($username)+'\.(owner|admin|id)=')})
$roles=@{};foreach($line in $roleLines){$pair=$line.Split('=',2);$roles[$pair[0].Substring($username.Length+1)]=$pair[1].Trim()}
if($roles.owner -ne 'false' -or $roles.admin -ne 'false' -or [int]$roles.id -lt 1000){throw 'Fresh account did not retain ordinary registry privileges'}
@{schemaVersion=1;result='PASS';startedUtc=$started;finishedUtc=[DateTime]::UtcNow.ToString('o');serverJarSha256=$manifest.bindings.serverJarSha256;apkSha256=(Hash $Apk);clientJarSha256=(Hash "$assets/client.jar");bridgeSha256=(Hash "$assets/bridge.jar");screenshotSha256=(Hash "$out/mobile-ingame.png");ordinaryAccount=$true;admin=$false;owner=$false;clientExited=$true;scope='Actual ordinary production login using desktop-hosted final mobile bridge; not physical Android validation; visual review pending'} | ConvertTo-Json | Set-Content -LiteralPath "$out/result.json" -Encoding UTF8
"Mobile production smoke completed and its QA client exited. Evidence: $out"
