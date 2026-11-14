<#
.SYNOPSIS
Prepare an optional normal-player production-client smoke harness. Default: compile only.
.DESCRIPTION
Only -Launch creates a random, non-owner QA identity/secret and starts the unchanged184
client. Registration occurs through normal client login, never an account-file edit.
Do not launch until final deployment is explicitly authorized and verified.
The source client.jar and server are never modified. No agents, fixtures, stats,
grants, teleports or server commands other than ordinary ::hollow are provided.

After authorized launch, drive normal UI with Send-HollowDepths-LiveClientAction.ps1:
  home portal -> Minigames -> Hollow Depths -> Novice -> capture/review clear entry;
  command -Text '::hollow' -> Abandon run -> confirmation -> capture lobby -> exit.
Exit refuses while the client is instanced and performs normal logout via UI first.
This uses a fresh ordinary starter; organic combat completion is outside this smoke.
#>
[CmdletBinding()]
param(
    [switch]$Launch,
    [switch]$CompileOnly,
    [ValidatePattern('^([a-fA-F0-9]{64})?$')][string]$ExpectedServerSha256='',
    [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedClientSha256='9dc00d8c4d2b58eaa82cf1ce5c117798a9ed994b338aadca88d9c00ef6b93c9f',
    [ValidateRange(512,1536)][int]$HeapMb=768
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($Launch -and $CompileOnly){throw 'Choose preparation/CompileOnly or explicit Launch, not both.'}
$root=[IO.Path]::GetFullPath((Split-Path (Split-Path $PSScriptRoot -Parent) -Parent))
$runtime=Join-Path $root 'local-runtime'
$client=Join-Path $root 'windows-release/Kronos-Windows/app/client.jar'
$support=Join-Path $root 'windows-release/Kronos-Windows/app/windows-support.jar'
$jdk=Join-Path $root 'tools/jdk8/jdk8u512-b09'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Replace-Once([string]$Value,[string]$Old,[string]$New){
    if([regex]::Matches($Value,[regex]::Escape($Old)).Count -ne 1){throw 'Reviewed client-harness source anchor changed; inspect before adapting.'}
    return $Value.Replace($Old,$New)
}
function Private-Directory([string]$Path){
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
    foreach($identity in @([Security.Principal.WindowsIdentity]::GetCurrent().User,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))){
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,[Security.AccessControl.FileSystemRights]::FullControl,
            ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),
            [Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    if(!(Get-Acl -LiteralPath $Path).AreAccessRulesProtected){throw 'Private runtime ACL was not applied.'}
}
if((Hash $client) -ne $ExpectedClientSha256.ToLowerInvariant()){throw 'Actual184 client.jar differs from the unchanged reviewed client.'}
$stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$smoke=Join-Path $runtime "hollow-depths-live-client/$stamp"
Private-Directory $smoke
foreach($folder in @('classes','generated/net/runelite/standalone','home/Desktop/kronos','captures/commands')){New-Item -ItemType Directory -Path (Join-Path $smoke $folder) -Force | Out-Null}
$name='HollowDepthsLiveClient_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$sourceFile=Join-Path $PSScriptRoot 'HollowDepthsClientSmoke.java'
$source=[IO.File]::ReadAllText($sourceFile).Replace('HollowDepthsClientSmoke',$name).Replace('kronos.hollowDepths.output','kronos.hollowDepths.liveSmoke.output')
$extension=@'
    private static String liveAccount,livePassword;
    private static void initializeLiveSmoke() throws Exception {
        if(!Boolean.getBoolean("kronos.hollowDepths.liveSmoke")||Boolean.getBoolean("kronos.hollowDepths.test")
                ||!"127.0.0.1".equals(System.getProperty("kronos.host")))
            throw new IllegalStateException("Explicit localhost production client smoke required");
        Path root=Paths.get(System.getProperty("kronos.hollowDepths.liveSmoke.directory")).toRealPath();
        Path credentials=Paths.get(System.getProperty("kronos.hollowDepths.liveSmoke.credentials")).toRealPath();
        if(!credentials.getParent().equals(root)||!credentials.getFileName().toString().equals("credentials.properties")
                ||!Paths.get(System.getProperty("user.home")).toRealPath().equals(root.resolve("home"))
                ||!Paths.get(System.getProperty("kronos.hollowDepths.liveSmoke.output")).toRealPath().equals(root.resolve("captures")))
            throw new IllegalStateException("Private client smoke paths do not agree");
        java.util.Properties secret=new java.util.Properties();
        try(java.io.InputStream in=Files.newInputStream(credentials)){secret.load(in);}
        liveAccount=secret.getProperty("account","");livePassword=secret.getProperty("password","");secret.clear();
        if(!liveAccount.matches("hdl[0-9a-f]{8}")||!livePassword.matches("[0-9a-f]{20}"))
            throw new IllegalStateException("Fresh ordinary smoke credentials are invalid");
    }
    private static void typeHollowCommand(String command) throws Exception {
        if(!"::hollow".equals(command))throw new IllegalStateException("Only ordinary ::hollow is permitted");
        if(client.getGameState()!=GameState.LOGGED_IN)throw new IllegalStateException("Ordinary login required");
        java.awt.Canvas canvas=client.getCanvas();
        EventQueue.invokeAndWait(()->canvas.requestFocusInWindow());
        for(char character:command.toCharArray()){
            EventQueue.invokeAndWait(()->{
                KeyEvent event=new KeyEvent(canvas,KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,character);
                for(KeyListener listener:canvas.getKeyListeners())listener.keyTyped(event);
            });Thread.sleep(25);
        }
        EventQueue.invokeAndWait(()->{
            long now=System.currentTimeMillis();
            KeyEvent press=new KeyEvent(canvas,KeyEvent.KEY_PRESSED,now,0,KeyEvent.VK_ENTER,'\n');
            KeyEvent release=new KeyEvent(canvas,KeyEvent.KEY_RELEASED,now+1,0,KeyEvent.VK_ENTER,'\n');
            for(KeyListener listener:canvas.getKeyListeners()){listener.keyPressed(press);listener.keyReleased(release);}
        });
    }
    private static UiTarget liveWidget(net.runelite.api.widgets.WidgetInfo info) throws Exception {
        CompletableFuture<UiTarget> future=new CompletableFuture<>();
        RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{
            Widget widget=client.getWidget(info);
            future.complete(widget==null||widget.isHidden()||widget.getBounds()==null?null:new UiTarget(widget));
        });return future.get(10,TimeUnit.SECONDS);
    }
    private static void normalLogout() throws Exception {
        if(client.getGameState()!=GameState.LOGGED_IN)throw new IllegalStateException("Connected ordinary player required for confirmed normal UI logout");
        if(client.isInInstancedRegion())throw new IllegalStateException("Abandon the dungeon through ::hollow before normal logout");
        UiTarget button=liveWidget(net.runelite.api.widgets.WidgetInfo.LOGOUT_BUTTON);
        if(button==null){
            UiTarget tab=liveWidget(net.runelite.api.widgets.WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB);
            if(tab==null)tab=liveWidget(net.runelite.api.widgets.WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB);
            if(tab==null)throw new IllegalStateException("Visible normal logout tab unavailable");
            click((int)tab.bounds.getCenterX(),(int)tab.bounds.getCenterY());
            button=liveWidget(net.runelite.api.widgets.WidgetInfo.LOGOUT_BUTTON);
        }
        if(button==null)throw new IllegalStateException("Visible normal logout button unavailable");
        click((int)button.bounds.getCenterX(),(int)button.bounds.getCenterY());
        waitState(GameState.LOGIN_SCREEN,30000);
        Files.write(output.resolve("normal-logout-confirmed.txt"),"PASS: normal client logout reached LOGIN_SCREEN\n".getBytes("UTF-8"));
    }
'@
$source=Replace-Once $source '    private static net.runelite.api.Client client;' ("    private static net.runelite.api.Client client;`n"+$extension)
$source=Replace-Once $source 'if(!Boolean.getBoolean("kronos.hollowDepths.test"))throw new IllegalStateException("Test mode required");' 'initializeLiveSmoke();'
$source=Replace-Once $source 'String account=System.getProperty("kronos.hollowDepths.account", "hdqa1");' 'String account=liveAccount;'
$source=Replace-Once $source 'if(!account.matches("hdqa[12]"))throw new IllegalStateException("Disposable test account required");' 'if(!account.matches("hdl[0-9a-f]{8}"))throw new IllegalStateException("Fresh production QA identity required");'
$source=Replace-Once $source 'client.setUsername(System.getProperty("kronos.hollowDepths.account", "hdqa1"));client.setPassword("local-test-only");ready.complete(null);' 'client.setUsername(liveAccount);client.setPassword(livePassword);livePassword=null;ready.complete(null);'
$source=Replace-Once $source 'Files.move(entry,commands.resolve(phase+".done"));return;' 'normalLogout();Files.move(entry,commands.resolve(phase+".done"));return;'
$source=Replace-Once $source '}else if(action.equals("click")){' '}else if(action.equals("command")){typeHollowCommand(p.getProperty("text",""));}else if(action.equals("click")){'
$source=Replace-Once $source 'HOLLOW DEPTHS SMOKE PASS: actual184 login and requested UI verification actions completed' 'HOLLOW DEPTHS LIVE UI SMOKE PASS: ordinary184 login, requested UI actions and normal logout completed; no organic combat claim'
$generated=Join-Path $smoke "generated/net/runelite/standalone/$name.java"
[IO.File]::WriteAllText($generated,$source,[Text.UTF8Encoding]::new($false))
$taskSource=Join-Path $root 'kronos-osrs-184/Kronos-master/runelite/runelite-client/src/main/java/net/runelite/standalone/TaskHandler.java'
$task=[IO.File]::ReadAllText($taskSource)
$routing=@'
if (!Boolean.getBoolean("kronos.hollowDepths.liveSmoke") || Boolean.getBoolean("kronos.hollowDepths.test"))
         throw new IllegalStateException("Production smoke socket guard required");
      if (var2 != 7304 && var2 != 13302)
         throw new IllegalArgumentException("Only original local production ports are permitted");
      return this.method3417(1, var2, 0, "127.0.0.1");
'@
$task=Replace-Once $task 'return this.method3417(1, var2, 0, var1);' $routing
$generatedTask=Join-Path $smoke 'generated/net/runelite/standalone/TaskHandler.java'
[IO.File]::WriteAllText($generatedTask,$task,[Text.UTF8Encoding]::new($false))
& "$jdk/bin/javac.exe" -J-Xmx192m -encoding UTF-8 -cp $client -d "$smoke/classes" $generatedTask $generated
if($LASTEXITCODE){throw 'Optional client-only live smoke harness failed Java8 compilation.'}
$manifest=[ordered]@{version=1;directory=$smoke;launchAuthorized=[bool]$Launch;client=$client;clientSha256=(Hash $client);sourceHarnessSha256=(Hash $sourceFile);taskHandlerSourceSha256=(Hash $taskSource);className="net.runelite.standalone.$name";createdUtc=[DateTime]::UtcNow.ToString('o');ports=@(7304,13302)}
[IO.File]::WriteAllText("$smoke/client-smoke.json",($manifest | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
if(!$Launch){"PASS: optional live client harness compiled only; no credentials/account/client process created. Private folder: $smoke";return}
if(!$ExpectedServerSha256){throw 'Explicit launch requires the reviewed final ExpectedServerSha256.'}
$release=([IO.File]::ReadAllText("$runtime/game-server-release.txt")).Trim()
if($release -notmatch '^hollow-depths-184-[a-zA-Z0-9_-]+$'){throw 'Final Hollow Depths184 release must be selected before ordinary live smoke.'}
$jars=@(Get-ChildItem -LiteralPath "$runtime/server-releases/$release/lib" -Filter 'kronos-server*.jar')
if($jars.Count -ne 1 -or (Hash $jars[0].FullName) -ne $ExpectedServerSha256.ToLowerInvariant()){throw 'Selected server bytes differ from the reviewed final deployment.'}
$gamePid=[int]([IO.File]::ReadAllText("$runtime/game-server.pid").Trim())
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$gamePid"
if(!$process -or !$process.CommandLine.Contains($release) -or !$process.CommandLine.Trim().EndsWith('io.ruin.Server') -or $process.CommandLine -match '\.test=true' -or !$process.CommandLine.Contains('-Dkronos.registration=true')){throw 'Normal production game with public registration is required.'}
if(!(Get-NetTCPConnection -State Listen -LocalPort 13302 -OwningProcess $gamePid -ErrorAction SilentlyContinue)){throw 'Reviewed production game PID does not own the original game listener.'}
foreach($port in @(7304,13302)){if(!(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)){throw "Original production listener$port is unavailable."}}
$profile=Get-Content -LiteralPath "$root/kronos-osrs-184/Kronos-master/kronos-server/data/hollow-depths/revision-184.properties"
if(@($profile | Where-Object {$_ -ceq 'production.enabled=true'}).Count -ne 1 -or @($profile | Where-Object {$_ -ceq ('server.build-id='+$ExpectedServerSha256.ToLowerInvariant())}).Count -ne 1){throw 'Enabled production entry profile must match the reviewed final release before creating an ordinary QA login.'}
if([math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024) -lt ($HeapMb+512)){throw 'Insufficient free memory for optional actual client.'}
# Credential material is generated only after explicit launch and all live guards.
$random=[Security.Cryptography.RandomNumberGenerator]::Create()
try{
    do{$id=[byte[]]::new(4);$random.GetBytes($id);$account='hdl'+[BitConverter]::ToString($id).Replace('-','').ToLowerInvariant()}
    while(Select-String -LiteralPath "$runtime/accounts.properties" -Pattern ('^'+$account+'\.') -Quiet)
    $bytes=[byte[]]::new(10);$random.GetBytes($bytes);$password=[BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
}finally{$random.Dispose()}
[IO.File]::WriteAllText("$smoke/credentials.properties","account=$account`npassword=$password`n",[Text.Encoding]::ASCII)
$password=$null
$manifest.serverSha256=$ExpectedServerSha256.ToLowerInvariant();$manifest.gamePid=$gamePid
[IO.File]::WriteAllText("$smoke/client-smoke.json",($manifest | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
$arguments=@("-Xmx${HeapMb}m",'-Dsun.java2d.d3d=false','-Dkronos.host=127.0.0.1','-Dkronos.hollowDepths.liveSmoke=true',
    ('-Duser.home="'+$smoke+'/home"'),('-Dkronos.hollowDepths.liveSmoke.directory="'+$smoke+'"'),
    ('-Dkronos.hollowDepths.liveSmoke.credentials="'+$smoke+'/credentials.properties"'),
    ('-Dkronos.hollowDepths.liveSmoke.output="'+$smoke+'/captures"'),'-cp',('"'+$smoke+'/classes;'+$support+';'+$client+'"'),$manifest.className)
$started=Start-Process -FilePath "$jdk/bin/java.exe" -ArgumentList $arguments -WorkingDirectory $smoke -WindowStyle Hidden -PassThru -RedirectStandardOutput "$smoke/client.out.log" -RedirectStandardError "$smoke/client.err.log"
[IO.File]::WriteAllText("$smoke/client.pid",[string]$started.Id,[Text.Encoding]::ASCII)
"Started optional ordinary-player client only. Credentials remain in its private file. UI control/captures: $smoke"
