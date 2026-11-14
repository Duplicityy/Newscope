param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$BaselineLib,
    [Parameter(Mandatory=$true)][string]$LombokJar
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$out=Join-Path $PSScriptRoot 'test-output'
$classes=Join-Path $out 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$javac=Join-Path $JavaHome 'bin/javac.exe'
$java=Join-Path $JavaHome 'bin/java.exe'
$javap=Join-Path $JavaHome 'bin/javap.exe'
$base=(Resolve-Path $BaselineLib).Path
$sources=@(Get-ChildItem (Join-Path $project 'kronos-server/src/main/java/io/ruin/content/content242') -Filter '*.java' | Where-Object {$_.Name -like 'Modern*' -or $_.Name -eq 'Content242Registry.java' -or $_.Name -eq 'BurnTimeline.java'} | ForEach-Object FullName)
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/combat/Hit.java'
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/entity/player/PlayerCombat.java'
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/entity/player/Player.java'
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/entity/Entity.java'
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/combat/CombatUtils.java'
$sources+=Join-Path $project 'kronos-server/src/main/java/io/ruin/model/skills/magic/spells/TargetSpell.java'
$sources+=@(Get-ChildItem (Join-Path $project 'kronos-server/src/main/java/io/ruin/model/combat/special/melee') -Filter 'Content242*.java' | ForEach-Object FullName)
$sources+=@(Get-ChildItem (Join-Path $project 'kronos-server/src/main/java/io/ruin/model/combat/special/ranged') -Filter 'Content242*.java' | ForEach-Object FullName)
$sources+=Join-Path $PSScriptRoot 'ModernGearRegression.java'
& $javac -J-XX:+UseSerialGC -J-Xmx192m -encoding UTF-8 -cp "$base/*;$LombokJar" -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Modern gear compilation failed'}
$before=(& $javap -classpath "$base/*" -c io.ruin.model.combat.Hit) -join "`n"
$after=(& $javap -classpath "$classes;$base/*" -c io.ruin.model.combat.Hit) -join "`n"
function Finish-Code([string]$text){
    $part=[regex]::Match($text,'(?s)public boolean finish\(io\.ruin\.model\.entity\.Entity\);.*?(?=\n  public |\n})').Value
    if(!$part){throw 'Could not isolate native Hit.finish bytecode'}
    return [regex]::Replace($part,'#\d+','#constant')
}
if((Finish-Code $before) -cne (Finish-Code $after)){throw 'Native Hit.finish instructions changed'}
'PASS native Hit.finish instructions and symbolic references unchanged' | Set-Content -Encoding UTF8 (Join-Path $out 'finish-bytecode.txt')
& $java -XX:+UseSerialGC -Xmx96m -cp "$classes;$base/*" io.ruin.content.content242.ModernGearRegression (Join-Path $out 'fixture')
if($LASTEXITCODE -ne 0){throw 'Modern gear native regression failed'}
