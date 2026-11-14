[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('127.0.0.1','0.0.0.0')][string]$Bind = '127.0.0.1',
    [ValidateRange(4096,16384)][int]$GameHeapMb = 4096,
    [ValidateRange(512,2048)][int]$CacheHeapMb = 512,
    [string]$RsaPrivateExponentFile = $env:KRONOS_RSA_PRIVATE_EXPONENT_FILE,
    [switch]$NoClient
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = $PSScriptRoot
$project = Join-Path $root 'kronos-osrs-184/Kronos-master'
$runtime = Join-Path $root 'local-runtime'
$private = Join-Path $runtime 'private'
$gameLib = Join-Path $project 'kronos-server/build/install/kronos-server/lib'
$cacheLib = Join-Path $project 'kronos-update-server/build/install/kronos-update-server/lib'
function Quote-Argument([string]$Value) {
    if ($Value.Contains('"') -or $Value.Contains("`n") -or $Value.Contains("`r")) { throw 'Unsupported quote or newline in launcher argument.' }
    return '"' + $Value.TrimEnd('\') + '"'
}
function Protect-Private([string]$Path, [bool]$Directory) {
    if ((Get-Item -LiteralPath $Path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Private path is a reparse point: $Path" }
    $acl = if ($Directory) { New-Object Security.AccessControl.DirectorySecurity } else { New-Object Security.AccessControl.FileSecurity }
    $acl.SetAccessRuleProtection($true,$false)
    $sids = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544') | Select-Object -Unique
    foreach ($sid in $sids) {
        $identity = New-Object Security.Principal.SecurityIdentifier($sid)
        $inheritance = if ($Directory) { [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit' } else { [Security.AccessControl.InheritanceFlags]::None }
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,[Security.AccessControl.FileSystemRights]::FullControl,$inheritance,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Owned-Process([string]$Name,[string]$Main,[string]$Library) {
    $pidFile = Join-Path $runtime "$Name.pid"
    if (!(Test-Path -LiteralPath $pidFile)) { return $null }
    $serverPid = 0
    if (![int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(),[ref]$serverPid)) { throw "Invalid PID file: $pidFile" }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid"
    if (!$process) { return $null }
    if ($process.ExecutablePath -ne $java -or !$process.CommandLine.Contains($Main) -or !$process.CommandLine.Contains($Library) -or !$process.CommandLine.Contains($runtime)) { throw "PID for $Name belongs to another process; no process was stopped." }
    return $process
}
function Assert-Port([int]$Port,$Process) {
    foreach ($listener in @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) {
        if (!$Process -or $listener.OwningProcess -ne $Process.ProcessId) { throw "Port $Port belongs to another process. Stop it explicitly before starting this clone." }
    }
}
function Wait-Port([int]$Port,[int]$ServerPid,[int]$Seconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if (!(Get-Process -Id $ServerPid -ErrorAction SilentlyContinue)) { throw "Server PID $ServerPid exited. See $runtime logs." }
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
        if (@($listeners | Where-Object OwningProcess -eq $ServerPid).Count) { return }
        if ($listeners.Count) { throw "Unexpected listener on port $Port. PID files retained for review." }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "PID $ServerPid has not opened port $Port. It was left running; review logs and use Stop-Kronos.ps1 for normal shutdown."
}
if (!$JavaHome) {
    $jdkRoot = Join-Path $root 'tools/jdk8'
    if (Test-Path -LiteralPath $jdkRoot) { $JavaHome = @(Get-ChildItem -LiteralPath $jdkRoot -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } | Select-Object -First 1 | ForEach-Object FullName) -join '' }
}
if (!$JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome with a 64-bit JDK 8 directory. Prepare the documented toolchain first.' }
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $javac)) { throw 'A full JDK 8 is required.' }
$versionInfo = New-Object Diagnostics.ProcessStartInfo
$versionInfo.FileName = $java
$versionInfo.Arguments = '-version'
$versionInfo.UseShellExecute = $false
$versionInfo.CreateNoWindow = $true
$versionInfo.RedirectStandardOutput = $true
$versionInfo.RedirectStandardError = $true
$versionProcess = [Diagnostics.Process]::Start($versionInfo)
$javaVersion = $versionProcess.StandardOutput.ReadToEnd() + $versionProcess.StandardError.ReadToEnd()
$versionProcess.WaitForExit()
$versionExit = $versionProcess.ExitCode
$versionProcess.Dispose()
if ($versionExit -ne 0 -or $javaVersion -notmatch 'version "1\.8\.' -or $javaVersion -notmatch '64-Bit') { throw 'This server requires a 64-bit JDK 8.' }
foreach ($lib in @($gameLib,$cacheLib)) {
    if (!(Test-Path -LiteralPath $lib) -or @(Get-ChildItem -LiteralPath $lib -Filter '*.jar').Count -eq 0) { throw "Missing built distribution: $lib. Run Build-Source.ps1 -Project server first." }
}
if (!$RsaPrivateExponentFile) { $RsaPrivateExponentFile = Join-Path $runtime 'rsa-private-exponent.txt' }
if (!$env:KRONOS_RSA_PRIVATE_EXPONENT -and !(Test-Path -LiteralPath $RsaPrivateExponentFile -PathType Leaf)) { throw 'Run New-Local-Rsa.ps1 and rebuild matching clients first, or configure your matching private exponent through KRONOS_RSA_PRIVATE_EXPONENT / -RsaPrivateExponentFile. No deployment private key is included.' }
if (Test-Path -LiteralPath $RsaPrivateExponentFile) { $RsaPrivateExponentFile = (Resolve-Path -LiteralPath $RsaPrivateExponentFile).Path }
# A per-clone mutex also protects the interval before a JVM opens its port.
$digest = [Security.Cryptography.SHA256]::Create()
try { $suffix = -join ($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($root.ToLowerInvariant())) | ForEach-Object { $_.ToString('x2') }) } finally { $digest.Dispose() }
$mutex = New-Object Threading.Mutex($false,"Local\Newscope242-$suffix")
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw 'Another launcher is already starting this clone.' }
    $settings = Join-Path $project 'server.properties'
    if (!(Test-Path -LiteralPath $settings)) { Copy-Item -LiteralPath (Join-Path $root 'server.properties.example') -Destination $settings }
    $properties = @{}
    foreach ($line in [IO.File]::ReadAllLines($settings)) {
        if ($line -match '^\s*[#!]' -or !$line.Trim()) { continue }
        if ($line -notmatch '^([^=]+)=(.*)$') { throw 'server.properties must use simple key=value lines.' }
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
    if ($properties.offline_mode -ne 'true') { throw 'This launcher requires offline_mode=true and its stable local account registry.' }
    if ($properties.world_address -notmatch ':([0-9]+)$') { throw 'world_address must include a game port.' }
    $gamePort = [int]$matches[1]
    $cachePort = [int]$properties.cache_port
    if ($gamePort -lt 1 -or $gamePort -gt 65535 -or $cachePort -lt 1 -or $cachePort -gt 65535 -or $gamePort -eq $cachePort) { throw 'Invalid game/cache ports.' }
    foreach ($key in @('cache_path','data_path')) {
        if (!$properties.ContainsKey($key)) { throw "Missing $key in server.properties." }
        $path = if ([IO.Path]::IsPathRooted($properties[$key])) { $properties[$key] } else { Join-Path $project $properties[$key] }
        if (!(Test-Path -LiteralPath $path -PathType Container)) { throw "Missing $key directory: $path. Restore the verified cache/data payload first." }
    }
    $game = Owned-Process 'game-server' 'LocalServerBootstrap' $gameLib
    $cache = Owned-Process 'update-server' 'io.ruin.update.Server' $cacheLib
    Assert-Port $gamePort $game
    Assert-Port $cachePort $cache
    if ($game -and $cache) { Write-Output 'Both clone JVMs are already running; no duplicate JVMs started.'; return }
    $neededMb = 1024 + $(if (!$game) { $GameHeapMb } else { 0 }) + $(if (!$cache) { $CacheHeapMb } else { 0 })
    $os = Get-CimInstance Win32_OperatingSystem
    $freeMb = [math]::Floor($os.FreePhysicalMemory / 1024)
    $perf = Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
    $commitMb = [math]::Floor(($perf.CommitLimit - $perf.CommittedBytes) / 1MB)
    if ($freeMb -lt $neededMb -or $commitMb -lt $neededMb) { throw "Insufficient headroom: need $neededMb MB physical and commit; available physical=$freeMb MB, commit=$commitMb MB. No JVM started." }
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    New-Item -ItemType Directory -Path $private -Force | Out-Null
    Protect-Private $private $true
    $accounts = Join-Path $private 'accounts.properties'
    if (!(Test-Path -LiteralPath $accounts)) { [IO.File]::WriteAllText($accounts,"schema=1`n",[Text.Encoding]::ASCII) }
    if ((Get-Item -LiteralPath $accounts).Length -eq 0) { throw 'Account registry is empty; restore it or initialize schema=1 explicitly.' }
    Protect-Private $accounts $false
    $playerData = Join-Path $private 'player-data'
    New-Item -ItemType Directory -Path $playerData -Force | Out-Null
    Protect-Private $playerData $true
    [IO.File]::WriteAllText((Join-Path $runtime 'java-home.txt'),$JavaHome,[Text.Encoding]::UTF8)
    # No exponent is inserted into command lines, generated source, or a JAR.
    $bootstrap = Join-Path $runtime 'bootstrap'
    New-Item -ItemType Directory -Path $bootstrap -Force | Out-Null
    $source = @'
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.lang.reflect.InvocationTargetException;
public final class LocalServerBootstrap {
    public static void main(String[] args) throws Throwable {
        String value = System.getenv("KRONOS_RSA_PRIVATE_EXPONENT");
        if (value == null || value.trim().isEmpty()) {
            Path path = Paths.get(System.getProperty("kronos.rsa.privateExponentFile"));
            if (Files.size(path) > 4096) throw new IllegalArgumentException("RSA exponent file exceeds limit");
            value = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII);
        }
        value = value.trim();
        if (value.length() > 3000 || !value.matches("[0-9]+")) throw new IllegalArgumentException("RSA exponent must be a bounded positive decimal integer");
        BigInteger exponent = new BigInteger(value);
        if (exponent.bitLength() < 256 || exponent.bitLength() > 8192) throw new IllegalArgumentException("RSA exponent has unsupported size");
        System.setProperty("kronos.rsa.privateExponent", value);
        try { Class.forName("io.ruin.Server").getMethod("main", String[].class).invoke(null, (Object) args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
'@
    [IO.File]::WriteAllText((Join-Path $bootstrap 'LocalServerBootstrap.java'),$source,[Text.Encoding]::ASCII)
    & $javac '-J-Xmx128m' '-d' $bootstrap (Join-Path $bootstrap 'LocalServerBootstrap.java')
    if ($LASTEXITCODE) { throw 'Bootstrap compilation failed; no JVM started.' }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $baseArgs = @('-Dfile.encoding=UTF-8',"-Dkronos.bind=$Bind","-Dkronos.runtime=$runtime")
    if (!$cache) {
        $arguments = @("-Xmx${CacheHeapMb}m") + $baseArgs + @('-cp',"$cacheLib/*",'io.ruin.update.Server')
        $cacheProcess = Start-Process -FilePath $java -ArgumentList (($arguments | ForEach-Object { Quote-Argument $_ }) -join ' ') -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput "$runtime/cache-$stamp.out.log" -RedirectStandardError "$runtime/cache-$stamp.err.log" -PassThru
        [IO.File]::WriteAllText((Join-Path $runtime 'update-server.pid'),[string]$cacheProcess.Id)
        Wait-Port $cachePort $cacheProcess.Id 45
    }
    if (!$game) {
        $arguments = @("-Xmx${GameHeapMb}m",'-Xss2m','-XX:+UseG1GC') + $baseArgs + @("-Dkronos.playerData=$playerData","-Dkronos.accounts=$accounts",'-Dkronos.registration=true',"-Dkronos.public=$($Bind -eq '0.0.0.0')","-Dkronos.rsa.privateExponentFile=$RsaPrivateExponentFile",'-cp',"$bootstrap;$gameLib/*",'LocalServerBootstrap')
        $gameProcess = Start-Process -FilePath $java -ArgumentList (($arguments | ForEach-Object { Quote-Argument $_ }) -join ' ') -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput "$runtime/game-$stamp.out.log" -RedirectStandardError "$runtime/game-$stamp.err.log" -PassThru
        [IO.File]::WriteAllText((Join-Path $runtime 'game-server.pid'),[string]$gameProcess.Id)
        Wait-Port $gamePort $gameProcess.Id 180
    }
    Write-Output "Listeners ready on $Bind (game $gamePort, cache $cachePort). Registration creates ordinary accounts with stable IDs; no owner/admin is granted."
    Write-Output 'Launch the separately built matching client. Use Stop-Kronos.ps1 for normal save/logout. Hollow Depths requires a new local audit before enabling its clone profile.'
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
