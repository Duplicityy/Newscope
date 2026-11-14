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
public final class Content242ClientSmoke {
    private static net.runelite.api.Client client;
    private static Path output;
    private static void click(int x,int y) throws Exception {
        java.awt.Canvas canvas=client.getCanvas();
        EventQueue.invokeAndWait(()->{
            long now=System.currentTimeMillis();
            MouseEvent moved=new MouseEvent(canvas,MouseEvent.MOUSE_MOVED,now,0,x,y,0,false,0);
            for(MouseMotionListener listener:canvas.getMouseMotionListeners())listener.mouseMoved(moved);
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
        while(System.currentTimeMillis()<until){if(client.getGameState()==state)return;Thread.sleep(100);}
        throw new IllegalStateException("Expected "+state+", got "+client.getGameState());
    }
    private static void capture(String phase) throws Exception {
        waitState(GameState.LOGGED_IN,30000);
        Thread.sleep(3000);
        if(client.getLocalPlayer()==null)throw new IllegalStateException("No local player");
        CompletableFuture<BufferedImage> future=new CompletableFuture<>();
        final int[] settledFrames={0};
        RuneLite.getInjector().getInstance(net.runelite.client.ui.DrawManager.class).registerEveryFrameListener(()->{
            if(future.isDone())return;
            if(client.getGameState()!=GameState.LOGGED_IN||Client.isLoading
                    ||NetCache.NetCache_pendingPriorityWritesCount!=0||NetCache.NetCache_pendingPriorityResponsesCount!=0){
                settledFrames[0]=0;return;
            }
            if(++settledFrames[0]<10)return;
            net.runelite.api.BufferProvider buffer=client.getBufferProvider();
            BufferedImage image=new BufferedImage(buffer.getWidth(),buffer.getHeight(),BufferedImage.TYPE_INT_RGB);
            image.setRGB(0,0,buffer.getWidth(),buffer.getHeight(),buffer.getPixels(),0,buffer.getWidth());
            future.complete(image);
        });
        try{
            ImageIO.write(future.get(120,TimeUnit.SECONDS),"png",output.resolve(phase+".png").toFile());
        }catch(TimeoutException failure){
            throw new IllegalStateException("Scene did not settle: "+phase+" state="+client.getGameState()
                +" loading="+Client.isLoading+" priorityRequests="+NetCache.NetCache_pendingPriorityWritesCount
                +" priorityResponses="+NetCache.NetCache_pendingPriorityResponsesCount,failure);
        }
        String position=client.getLocalPlayer().getWorldLocation().toString();
        Files.write(output.resolve(phase+".captured"),(phase+" "+position+"\n").getBytes("UTF-8"));
        System.out.println("CONTENT242 CAPTURE "+phase+" position="+position);
    }
    private static void waitFixture(String phase) throws Exception {
        long until=System.currentTimeMillis()+600000;
        Path ready=output.resolve(phase+".ready");
        while(!Files.exists(ready)&&System.currentTimeMillis()<until)Thread.sleep(250);
        if(!Files.exists(ready))throw new IllegalStateException("Fixture not applied: "+phase);
        if(phase.startsWith("wear-"))exerciseInventoryWield(phase);
        capture(phase);
    }
    private static <T>T onClient(Callable<T> action) throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();
        RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{
            try{future.complete(action.call());}catch(Throwable error){future.completeExceptionally(error);}
        });
        return future.get(10,TimeUnit.SECONDS);
    }
    private static int equippedItem(int slot){
        net.runelite.api.ItemContainer container=client.getItemContainer(net.runelite.api.InventoryID.EQUIPMENT);
        net.runelite.api.Item[] items=container==null?null:container.getItems();
        return items==null||items.length<=slot||items[slot]==null?-1:items[slot].getId();
    }
    /** Normal mouse click, item-action packet and server equipment update; no direct equip call. */
    private static void exerciseInventoryWield(String phase) throws Exception {
        Thread.sleep(2000);
        Path slotFile=output.resolve(phase+".slot");
        final int equipSlot=Files.exists(slotFile)?Integer.parseInt(new String(Files.readAllBytes(slotFile),"UTF-8").trim()):3;
        if(equipSlot<0||equipSlot>13)throw new IllegalArgumentException("Invalid fixture equipment slot");
        Rectangle tab=onClient(()->{
            for(net.runelite.api.widgets.WidgetInfo info:new net.runelite.api.widgets.WidgetInfo[]{
                net.runelite.api.widgets.WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB,
                net.runelite.api.widgets.WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB,
                net.runelite.api.widgets.WidgetInfo.RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_TAB}){
                Widget widget=client.getWidget(info);
                if(widget!=null&&!widget.isHidden()&&widget.getBounds().width>0)return widget.getBounds();
            }
            throw new IllegalStateException("Inventory tab is not visible");
        });
        click((int)tab.getCenterX(),(int)tab.getCenterY());
        final int[] itemId={-1};
        Rectangle item=onClient(()->{
            Widget widget=client.getWidget(net.runelite.api.widgets.WidgetInfo.INVENTORY);
            if(widget==null||widget.isHidden())throw new IllegalStateException("Inventory is not open");
            net.runelite.api.widgets.WidgetItem first=widget.getWidgetItem(0);
            if(first==null||first.getId()<0)throw new IllegalStateException("Fixture slot0 is empty");
            itemId[0]=first.getId();
            if(equippedItem(equipSlot)==itemId[0])throw new IllegalStateException("Fixture item was already equipped");
            return first.getCanvasBounds();
        });
        click((int)item.getCenterX(),(int)item.getCenterY());
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until){
            if(onClient(()->equippedItem(equipSlot))==itemId[0]){
                Files.write(output.resolve(phase+".verified"),("Normal inventory mouse click equipped item="+itemId[0]+" slot="+equipSlot+" through actual server update\n").getBytes("UTF-8"));
                System.out.println("CONTENT242 NORMAL WIELD PASS "+phase+" item="+itemId[0]);return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Normal inventory click did not equip item="+itemId[0]);
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
                System.out.println("CONTENT242 ONBOARDING click="+selected.text+" widget="+selected.id);
                click((int)rectangle.getCenterX(),(int)rectangle.getCenterY());
                quietSince=0;Thread.sleep(600);
            }else if(appearance){
                // Original fixed-mode revision184 appearance Accept button.
                System.out.println("CONTENT242 ONBOARDING click=appearance Accept");
                click(260,285);quietSince=0;Thread.sleep(600);
            }else{
                if(quietSince==0)quietSince=System.currentTimeMillis();
                if(System.currentTimeMillis()-quietSince>5000){System.out.println("CONTENT242 ONBOARDING complete: no appearance or dialogue controls remain");return;}
                Thread.sleep(250);
            }
        }
        capture("onboarding-timeout");
        throw new IllegalStateException("Could not finish normal appearance/experience/game-mode/tutorial UI");
    }
    public static void main(String[] args){
        new java.util.Timer(true).schedule(new java.util.TimerTask(){public void run(){System.err.println("CONTENT242 SMOKE TIMEOUT");System.exit(2);}},1800000);
        try{
            if(!Boolean.getBoolean("kronos.content242.test"))throw new IllegalStateException("Test mode required");
            output=Paths.get(System.getProperty("kronos.content242.output"));Files.createDirectories(output);
            RuneLite.main(new String[]{"--no-splash"});
            client=RuneLite.getInjector().getInstance(net.runelite.api.Client.class);
            waitState(GameState.LOGIN_SCREEN,150000);
            click(client.getCanvas().getWidth()/2-80,291);
            if(client.getLoginIndex()!=2)throw new IllegalStateException("New User did not open the existing login UI");
            CompletableFuture<Void> ready=new CompletableFuture<>();
            RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{
                client.setUsername("c242qa");client.setPassword("local-test-only");ready.complete(null);
            });ready.get(10,TimeUnit.SECONDS);
            click(client.getCanvas().getWidth()/2-80,321);waitState(GameState.LOGGED_IN,60000);
            finishOnboarding();
            capture("home");
            for(String phase:System.getProperty("kronos.content242.phases","gear,map").split(",")){
                if(!phase.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Invalid fixture phase");
                waitFixture(phase);
            }
            System.out.println("CONTENT242 SMOKE PASS: actual184 login, home, gear and map frames captured");
            System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
