param([switch]$Cacio,[switch]$InputTest,[switch]$Remote,[switch]$ControlsTest,[switch]$RegistrationTest,[switch]$MobileUiTest,[switch]$DialogTest)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$assets=Join-Path $root 'kronos-mobile/android/kronos/src/main/assets/jvm'
$test=Join-Path $root $(if($DialogTest){'local-runtime/mobile-dialog-harness'}else{'local-runtime/mobile-test'})
New-Item -ItemType Directory -Force $test | Out-Null
$heap=if($DialogTest){'-Xmx512m'}else{'-Xmx768m'}
$options=@($heap,"-Duser.home=$test",'-Dkronos.host=127.0.0.1','--add-opens=java.base/java.lang=ALL-UNNAMED','--add-opens=java.base/java.lang.reflect=ALL-UNNAMED','--add-opens=java.base/java.util=ALL-UNNAMED','--add-opens=java.desktop/java.awt=ALL-UNNAMED','--add-opens=java.desktop/sun.java2d=ALL-UNNAMED','-Dcacio.managed.screensize=2304x1728')
foreach($pkg in @('java.awt.peer','java.awt.dnd.peer','sun.awt','sun.awt.image','sun.awt.event','sun.awt.datatransfer','sun.java2d')) { $options+="--add-exports=java.desktop/$pkg=ALL-UNNAMED" }
$options+='--add-exports=java.base/sun.security.action=ALL-UNNAMED'
if(!$Cacio){$options+='-Dkronos.desktopTest=true'}
if($DialogTest){
    if($Cacio -or $Remote -or $RegistrationTest){throw 'Dialog tests require the isolated desktop harness'}
    $stagingPid=[int](Get-Content -LiteralPath "$root/local-runtime/mobile-dialog-test/server.pid")
    $staging=Get-CimInstance Win32_Process -Filter "ProcessId=$stagingPid"
    if(!$staging -or $staging.CommandLine -notlike '*-Dkronos.adventurers.test=true*' -or !(Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 14302 -OwningProcess $stagingPid -ErrorAction SilentlyContinue)){
        throw 'Isolated dialog test server must be listening on loopback port 14302'
    }
    $testName='dialog'+[guid]::NewGuid().ToString('N').Substring(0,6)
    $options+=@('-Dkronos.testGamePort=14302','-Dkronos.testDialogs=true',"-Dkronos.testUser=$testName")
}
if($ControlsTest){$options+='-Dkronos.testControls=true'}
if($InputTest){$options+='-Dkronos.testInput=true'}
if($MobileUiTest){$options+='-Dkronos.testMobileUi=true'}
if($RegistrationTest){
    $options+='-Dkronos.testRegistration=true'
    $Remote=$true
}
if($Remote){
    $access=Get-Content "$root/local-runtime/remote-access.json" -Raw | ConvertFrom-Json
    $options=$options | Where-Object {$_ -notlike '-Dkronos.host=*'}
    if($MobileUiTest){$options+='-Dkronos.testMobileUi=true'}
if($RegistrationTest){
        $saved=Join-Path $test 'registration-access.json'
        if(Test-Path $saved){$access=Get-Content $saved -Raw | ConvertFrom-Json}
        else{
            $access=[pscustomobject]@{host='127.0.0.1';username=('touch'+[guid]::NewGuid().ToString('N').Substring(0,6));password=[guid]::NewGuid().ToString('N').Substring(0,18)}
            $access | ConvertTo-Json | Set-Content $saved
        }
    }
    $secret=Join-Path $test 'test-password.tmp'
    [IO.File]::WriteAllText($secret,$access.password)
    $options+=@("-Dkronos.host=$($access.host)","-Dkronos.testUser=$($access.username)","-Dkronos.testPasswordFile=$secret")
}
$java=(Get-ChildItem "$root/tools/jdk21" -Directory | Select-Object -First 1).FullName + '/bin/java.exe'
try{ & $java @options -cp "$assets/bridge.jar;$assets/client.jar;$assets/cacio-tta-1.18.jar;$assets/cacio-shared-1.18.jar" KronosBoot "$test/login.png" *> "$test/harness.log" }
finally{if($Remote -and (Test-Path $secret)){Remove-Item -LiteralPath $secret}}
exit $LASTEXITCODE
