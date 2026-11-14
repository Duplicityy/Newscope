param([int]$StaySeconds=2)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$test=Join-Path $root 'local-runtime/windows-test'
$access=Get-Content "$test/access.json" -Raw | ConvertFrom-Json
$jdk=(Get-ChildItem "$root/tools/jdk21" -Directory | Select-Object -First 1).FullName
$bundle=Join-Path $root 'windows-release/Kronos-Windows'
& "$jdk/bin/javac.exe" --release 8 -cp "$bundle/app/windows-support.jar;$bundle/app/client.jar" -d "$test/classes" "$root/windows-client/tests/WindowsSmoke.java"
if($LASTEXITCODE){throw 'Windows smoke test did not compile'}
$arguments=@('-Xmx1g',"-Dkronos.smoke.staySeconds=$StaySeconds",'-Dsun.java2d.d3d=false',"-Duser.home=$test/home",'-Dkronos.host=127.0.0.1',"-Dkronos.smoke.user=$($access.username)","-Dkronos.smoke.passwordFile=$test/password.tmp","-Dkronos.smoke.screenshot=$test/ingame-$StaySeconds-seconds.png",'-cp',"$test/classes;$bundle/app/windows-support.jar;$bundle/app/client.jar",'net.runelite.standalone.WindowsSmoke')
& "$bundle/runtime/bin/java.exe" @arguments *> "$test/smoke.log"
if($LASTEXITCODE){Get-Content "$test/smoke.log" -Tail 30;throw 'Windows smoke test failed'}
Get-Content "$test/smoke.log" | Select-String 'WINDOWS PASS'