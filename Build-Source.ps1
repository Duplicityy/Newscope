param([ValidateSet('server','client','all')][string]$Project='all',[switch]$Offline)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$env:JAVA_HOME=(Get-ChildItem "$root/tools/jdk8" -Directory|Select-Object -First 1).FullName
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:GRADLE_USER_HOME=Join-Path $root '.gradle-home'
$env:NEWSCOPE_MAVEN_REPO=Join-Path $root 'vendor/maven'
$game=Join-Path $root 'kronos-osrs-184/Kronos-master'
$common=@('--no-daemon','--max-workers=1','--console=plain','-I',"$root/tools/dependencies.init.gradle",'-Dorg.gradle.jvmargs=-Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8')
if($Offline){$common+='--offline'}
if($Project -in @('server','all')){& "$game/gradlew.bat" -p $game @common :kronos-server:installDist :kronos-update-server:installDist;if($LASTEXITCODE){throw 'Server build failed'}}
if($Project -in @('client','all')){& "$game/gradlew.bat" -p "$game/runelite" @common :runelite-client:shadowJar;if($LASTEXITCODE){throw 'Client build failed'}}
