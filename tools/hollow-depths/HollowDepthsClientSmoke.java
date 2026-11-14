package net.runelite.standalone;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.api.widgets.Widget;

/** Actual 184 desktop login and completed-frame captures; bounded fixture handoffs. */
public final class HollowDepthsClientSmoke {
    private static net.runelite.api.Client client;
    private static Path output;
    private static volatile long lastFrameMillis;
    private static volatile String clientFailure;
    private static volatile byte[] diagnosticReserve=new byte[1024*1024];
    private static final java.util.concurrent.atomic.AtomicBoolean writingAutoFrame=new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicInteger autoSequence=new java.util.concurrent.atomic.AtomicInteger();
    private static volatile String lastAutoScene="";
    private static final ExecutorService diagnosticWriter=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"HollowDepthsCaptureWriter");t.setDaemon(true);return t;});
    private static String landArchiveIds(){
        try{Class<?> type=Class.forName("net.runelite.standalone.class47",false,client.getClass().getClassLoader());java.lang.reflect.Field field=type.getDeclaredField("regionLandArchiveIds");field.setAccessible(true);return java.util.Arrays.toString((int[])field.get(null));}
        catch(ReflectiveOperationException unavailable){return "unavailable: "+unavailable.getClass().getSimpleName();}
    }
    private static String mapMetadata(){
        return "observedUtc="+java.time.Instant.now()+"\ngameState="+client.getGameState()+"\nbase="+client.getBaseX()+","+client.getBaseY()+"\ninstanced="+client.isInInstancedRegion()
            +"\nregions="+java.util.Arrays.toString(client.getMapRegions())+"\nxteaKeys="+java.util.Arrays.deepToString(client.getXteaKeys())
            +"\nlandArchiveIds="+landArchiveIds()+"\ninstanceChunkTemplates="+java.util.Arrays.deepToString(client.getInstanceTemplateChunks())+"\n";
    }
    private static void failClient(String reason){
        if(clientFailure!=null)return;clientFailure=reason;diagnosticReserve=null;
        try{Files.deleteIfExists(output.resolve("client.ready"));Files.write(output.resolve("client-fatal.txt"),(reason+"\n"+mapMetadata()).getBytes("UTF-8"));}catch(Throwable ignored){}
        System.err.println("HOLLOW DEPTHS CLIENT FAILURE: "+reason);
    }
    private static void installDiagnostics(){
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            if(error instanceof OutOfMemoryError||thread.getName().toLowerCase().contains("client"))failClient(thread.getName()+": "+error);
            if(previous!=null)previous.uncaughtException(thread,error);else error.printStackTrace();
        });
        RuneLite.getInjector().getInstance(net.runelite.client.ui.DrawManager.class).registerEveryFrameListener(()->{
            lastFrameMillis=System.currentTimeMillis();
            if(!client.isInInstancedRegion()){lastAutoScene="";return;}
            if(client.getGameState()!=GameState.LOGGED_IN||client.getLocalPlayer()==null)return;
            net.runelite.api.coords.WorldPoint at=client.getLocalPlayer().getWorldLocation();
            String scene=client.getBaseX()+","+client.getBaseY()+":"+(at.getX()>>6)+","+(at.getY()>>6)+","+at.getPlane();
            if(scene.equals(lastAutoScene)||!writingAutoFrame.compareAndSet(false,true))return;
            try{
                net.runelite.api.BufferProvider buffer=client.getBufferProvider();BufferedImage frame=new BufferedImage(buffer.getWidth(),buffer.getHeight(),BufferedImage.TYPE_INT_RGB);
                frame.setRGB(0,0,buffer.getWidth(),buffer.getHeight(),buffer.getPixels(),0,buffer.getWidth());
                String metadata=mapMetadata()+"player="+at+"\nplayerX="+at.getX()+"\nplayerY="+at.getY()+"\nplayerPlane="+at.getPlane()+"\n";String phase=String.format("auto-instance-%03d",autoSequence.incrementAndGet());lastAutoScene=scene;
                diagnosticWriter.submit(()->{try{ImageIO.write(frame,"png",output.resolve(phase+".png").toFile());Files.write(output.resolve(phase+".state.txt"),metadata.getBytes("UTF-8"));}catch(Throwable error){System.err.println("Auto capture failed: "+error);}finally{writingAutoFrame.set(false);}});
            }catch(Throwable error){writingAutoFrame.set(false);failClient("Frame diagnostic: "+error);}
        });
        Thread watch=new Thread(()->{
            String prior="";
            while(clientFailure==null){
                try{
                    String key=client.getGameState()+":"+client.getBaseX()+","+client.getBaseY()+":"+java.util.Arrays.hashCode(client.getMapRegions());
                    if(!key.equals(prior)){Files.write(output.resolve("latest-map-decode.txt"),mapMetadata().getBytes("UTF-8"));prior=key;}
                    if(lastFrameMillis!=0&&System.currentTimeMillis()-lastFrameMillis>60000)failClient("No completed client frame for60seconds");
                    Thread.sleep(250);
                }catch(Throwable error){failClient("Diagnostic watcher: "+error);}
            }
        },"HollowDepthsFrameWatch");watch.setDaemon(true);watch.start();
    }
    private static void click(int x,int y) throws Exception {
        java.awt.Canvas canvas=client.getCanvas();
        EventQueue.invokeAndWait(()->{
            long now=System.currentTimeMillis();
            MouseEvent moved=new MouseEvent(canvas,MouseEvent.MOUSE_MOVED,now,0,x,y,0,false,0);
            for(MouseMotionListener listener:canvas.getMouseMotionListeners())listener.mouseMoved(moved);
        });
        // Let the real client rebuild its hover/picking menu before pressing at the new location.
        Thread.sleep(100);
        EventQueue.invokeAndWait(()->{
            long now=System.currentTimeMillis();
            MouseEvent press=new MouseEvent(canvas,MouseEvent.MOUSE_PRESSED,now,InputEvent.BUTTON1_DOWN_MASK,x,y,1,false,1);
            for(MouseListener listener:canvas.getMouseListeners())listener.mousePressed(press);
        });
        Thread.sleep(120);
        EventQueue.invokeAndWait(()->{
            MouseEvent release=new MouseEvent(canvas,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,x,y,1,false,1);
            for(MouseListener listener:canvas.getMouseListeners())listener.mouseReleased(release);
        });
        Thread.sleep(500);
    }
    private static void waitState(GameState state,long millis) throws Exception {
        long until=System.currentTimeMillis()+millis;
        while(System.currentTimeMillis()<until){if(clientFailure!=null)throw new IllegalStateException(clientFailure);if(client.getGameState()==state)return;Thread.sleep(100);}
        throw new IllegalStateException("Expected "+state+", got "+client.getGameState());
    }
    private static void capture(String phase) throws Exception {
        capture(phase,3000);
    }
    private static void capture(String phase,long settleMillis) throws Exception {
        waitState(GameState.LOGGED_IN,30000);
        Thread.sleep(Math.min(3000,Math.max(0,settleMillis)));
        if(client.getLocalPlayer()==null)throw new IllegalStateException("No local player");
        CompletableFuture<BufferedImage> future=new CompletableFuture<>();
        RuneLite.getInjector().getInstance(net.runelite.client.ui.DrawManager.class).registerEveryFrameListener(()->{
            if(future.isDone())return;
            net.runelite.api.BufferProvider buffer=client.getBufferProvider();
            BufferedImage image=new BufferedImage(buffer.getWidth(),buffer.getHeight(),BufferedImage.TYPE_INT_RGB);
            image.setRGB(0,0,buffer.getWidth(),buffer.getHeight(),buffer.getPixels(),0,buffer.getWidth());
            future.complete(image);
        });
        ImageIO.write(future.get(15,TimeUnit.SECONDS),"png",output.resolve(phase+".png").toFile());
        String position=client.getLocalPlayer().getWorldLocation().toString();
        Files.write(output.resolve(phase+".captured"),(phase+" "+position+"\n").getBytes("UTF-8"));
        System.out.println("HOLLOW DEPTHS CAPTURE "+phase+" position="+position);
    }
    private static void waitFixture(String phase) throws Exception {
        long until=System.currentTimeMillis()+180000;
        Path ready=output.resolve(phase+".ready");
        while(!Files.exists(ready)&&System.currentTimeMillis()<until)Thread.sleep(250);
        if(!Files.exists(ready))throw new IllegalStateException("Fixture not applied: "+phase);
        capture(phase);
    }
    private static void controlLoop() throws Exception {
        String account=System.getProperty("kronos.hollowDepths.account", "hdqa1");
        if(!account.matches("hdqa[12]"))throw new IllegalStateException("Disposable test account required");
        Path commands=output.resolve("commands");Files.createDirectories(commands);
        dumpState("home");
        Files.write(output.resolve("client.ready"),(account+"\n").getBytes("UTF-8"));
        long until=System.currentTimeMillis()+1500000;
        while(System.currentTimeMillis()<until){
            if(clientFailure!=null)throw new IllegalStateException(clientFailure);
            java.util.List<Path> pending=new java.util.ArrayList<>();
            try(java.nio.file.DirectoryStream<Path> entries=Files.newDirectoryStream(commands,"*.cmd")){
                for(Path entry:entries)pending.add(entry);
            }
            java.util.Collections.sort(pending);
            for(Path entry:pending){
                String phase=entry.getFileName().toString().replaceFirst("\\.cmd$","");
                if(!phase.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalStateException("Invalid test action name");
                java.util.Properties p=new java.util.Properties();
                try(java.io.InputStream in=Files.newInputStream(entry)){p.load(in);}
                String action=p.getProperty("action", "capture");
                try{
                    if(action.equals("exit")){
                        Files.move(entry,commands.resolve(phase+".done"));return;
                    }else if(action.equals("click")){
                        click(Integer.parseInt(p.getProperty("x")),Integer.parseInt(p.getProperty("y")));
                    }else if(action.equals("widget")){
                        String wanted=p.getProperty("text");UiTarget target=null;
                        for(UiTarget candidate:visibleWidgets())if(candidate.text.equalsIgnoreCase(wanted)){target=candidate;break;}
                        if(target==null)throw new IllegalStateException("Visible widget not found: "+wanted);
                        click((int)target.bounds.getCenterX(),(int)target.bounds.getCenterY());
                    }else if(action.equals("menu")){
                        CompletableFuture<Void> result=new CompletableFuture<>();
                        RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{
                            try{
                                client.invokeMenuAction(Integer.parseInt(p.getProperty("param0")),Integer.parseInt(p.getProperty("param1")),
                                    Integer.parseInt(p.getProperty("type")),Integer.parseInt(p.getProperty("id")),
                                    p.getProperty("option",""),p.getProperty("target",""),0,0);
                                result.complete(null);
                            }catch(Throwable error){result.completeExceptionally(error);}
                        });result.get(10,TimeUnit.SECONDS);
                    }else if(!action.equals("capture"))throw new IllegalStateException("Unknown test action: "+action);
                    Thread.sleep(Math.min(15000,Math.max(0,Integer.parseInt(p.getProperty("waitMs","1000")))));
                    capture(phase,Long.parseLong(p.getProperty("settleMs","3000")));dumpState(phase);
                    Files.move(entry,commands.resolve(phase+".done"));
                }catch(Throwable error){
                    Files.write(commands.resolve(phase+".error"),error.toString().getBytes("UTF-8"));
                    Files.move(entry,commands.resolve(phase+".failed"));throw error;
                }
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Client action window timed out");
    }
    private static void dumpState(String phase) throws Exception {
        CompletableFuture<String> result=new CompletableFuture<>();
        RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{
            try{
                StringBuilder state=new StringBuilder("gameState=").append(client.getGameState()).append('\n');
                if(client.getLocalPlayer()!=null)state.append("player=").append(client.getLocalPlayer().getWorldLocation()).append('\n');
                java.util.List<UiTarget> targets=new java.util.ArrayList<>();
                widgets(client.getWidgetRoots(),targets,java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Widget,Boolean>()));
                for(UiTarget target:targets)if(!target.text.isEmpty())state.append("widget ").append(target.id).append(' ').append(target.bounds).append(' ').append(target.text).append('\n');
                for(net.runelite.api.NPC npc:client.getNpcs())state.append("npc ").append(npc.getId()).append(" index=").append(npc.getIndex()).append(' ').append(npc.getName()).append(' ').append(npc.getWorldLocation()).append(" canvas=").append(npc.getCanvasTilePoly()==null?"none":npc.getCanvasTilePoly().getBounds()).append('\n');
                java.util.Set<net.runelite.api.TileObject> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<net.runelite.api.TileObject,Boolean>());
                net.runelite.api.Tile[][] plane=client.getScene().getTiles()[client.getPlane()];
                for(net.runelite.api.Tile[] row:plane)for(net.runelite.api.Tile tile:row)if(tile!=null){
                    java.util.List<net.runelite.api.TileObject> objects=new java.util.ArrayList<>();
                    if(tile.getGameObjects()!=null)java.util.Collections.addAll(objects,tile.getGameObjects());
                    objects.add(tile.getWallObject());objects.add(tile.getDecorativeObject());objects.add(tile.getGroundObject());
                    for(net.runelite.api.TileObject object:objects)if(object!=null&&seen.add(object)&&object.getCanvasLocation()!=null){
                        net.runelite.api.Point canvas=object.getCanvasLocation();
                        if(canvas.getX()<0||canvas.getY()<0||canvas.getX()>client.getCanvas().getWidth()||canvas.getY()>client.getCanvas().getHeight())continue;
                        state.append("object ").append(object.getId()).append(' ').append(client.getObjectDefinition(object.getId()).getName())
                            .append(' ').append(object.getWorldLocation()).append(" local=").append(object.getLocalLocation()).append(" canvas=").append(canvas).append('\n');
                    }
                }
                result.complete(state.toString());
            }catch(Throwable error){result.completeExceptionally(error);}
        });
        Files.write(output.resolve(phase+".state.txt"),result.get(10,TimeUnit.SECONDS).getBytes("UTF-8"));
    }
    private static final class UiTarget {
        final String text;final Rectangle bounds;final int id;
        UiTarget(Widget widget){text=widget.getText()==null?"":widget.getText().replaceAll("<[^>]*>","").trim();bounds=widget.getBounds();id=widget.getId();}
    }
    private static void widgets(Widget[] widgets,java.util.List<UiTarget> targets,java.util.Set<Widget> seen){
        if(widgets==null)return;
        for(Widget widget:widgets){
            if(widget==null||!seen.add(widget)||widget.isHidden())continue;
            Rectangle bounds=widget.getBounds();
            if(bounds!=null&&bounds.width>0&&bounds.height>0)targets.add(new UiTarget(widget));
            widgets(widget.getDynamicChildren(),targets,seen);
            widgets(widget.getStaticChildren(),targets,seen);
            widgets(widget.getNestedChildren(),targets,seen);
        }
    }
    private static java.util.List<UiTarget> visibleWidgets() throws Exception {
        CompletableFuture<java.util.List<UiTarget>> future=new CompletableFuture<>();
        RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{
            try{
                java.util.List<UiTarget> targets=new java.util.ArrayList<>();
                widgets(client.getWidgetRoots(),targets,java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Widget,Boolean>()));
                future.complete(targets);
            }catch(Throwable error){future.completeExceptionally(error);}
        });
        return future.get(10,TimeUnit.SECONDS);
    }
    private static void finishOnboarding() throws Exception {
        Thread.sleep(2500);
        long until=System.currentTimeMillis()+120000,quietSince=0;
        while(System.currentTimeMillis()<until){
            java.util.List<UiTarget> targets=visibleWidgets();
            UiTarget selected=null;boolean appearance=false;
            for(UiTarget target:targets){
                if((target.id>>>16)==269)appearance=true;
                if(target.text.equalsIgnoreCase("Accept")||(target.text.startsWith("Hard -"))
                        ||target.text.equalsIgnoreCase("No")||target.text.startsWith("No, I know")){
                    selected=target;break;
                }
            }
            if(selected==null)for(UiTarget target:targets){
                if(target.text.toLowerCase(java.util.Locale.ROOT).contains("click here to continue")){selected=target;break;}
            }
            if(selected!=null){
                Rectangle rectangle=selected.bounds;
                System.out.println("HOLLOW DEPTHS ONBOARDING click="+selected.text+" widget="+selected.id);
                click((int)rectangle.getCenterX(),(int)rectangle.getCenterY());
                quietSince=0;Thread.sleep(600);
            }else if(appearance){
                // Original fixed-mode revision184 appearance Accept button.
                System.out.println("HOLLOW DEPTHS ONBOARDING click=appearance Accept");
                click(260,285);quietSince=0;Thread.sleep(600);
            }else{
                if(quietSince==0)quietSince=System.currentTimeMillis();
                if(System.currentTimeMillis()-quietSince>5000){System.out.println("HOLLOW DEPTHS ONBOARDING complete: no appearance or dialogue controls remain");return;}
                Thread.sleep(250);
            }
        }
        capture("onboarding-timeout");
        throw new IllegalStateException("Could not finish normal appearance/experience/game-mode/tutorial UI");
    }
    public static void main(String[] args){
        new java.util.Timer(true).schedule(new java.util.TimerTask(){public void run(){System.err.println("HOLLOW DEPTHS SMOKE TIMEOUT");System.exit(2);}},1800000);
        try{
            if(!Boolean.getBoolean("kronos.hollowDepths.test"))throw new IllegalStateException("Test mode required");
            output=Paths.get(System.getProperty("kronos.hollowDepths.output"));Files.createDirectories(output);
            Files.deleteIfExists(output.resolve("client-fatal.txt"));Files.deleteIfExists(output.resolve("client.ready"));
            RuneLite.main(new String[]{"--no-splash"});
            client=RuneLite.getInjector().getInstance(net.runelite.api.Client.class);
            installDiagnostics();
            waitState(GameState.LOGIN_SCREEN,150000);
            click(client.getCanvas().getWidth()/2-80,291);
            if(client.getLoginIndex()!=2)throw new IllegalStateException("New User did not open the existing login UI");
            CompletableFuture<Void> ready=new CompletableFuture<>();
            RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{
                client.setUsername(System.getProperty("kronos.hollowDepths.account", "hdqa1"));client.setPassword("local-test-only");ready.complete(null);
            });ready.get(10,TimeUnit.SECONDS);
            click(client.getCanvas().getWidth()/2-80,321);waitState(GameState.LOGGED_IN,60000);
            finishOnboarding();
            capture("home");
            controlLoop();
            
            System.out.println("HOLLOW DEPTHS SMOKE PASS: actual184 login and requested UI verification actions completed");
            System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
