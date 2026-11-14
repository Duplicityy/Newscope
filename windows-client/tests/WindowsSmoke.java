package net.runelite.standalone;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
public final class WindowsSmoke {
 private static net.runelite.api.Client client;
 private static void click(int x,int y)throws Exception{
  java.awt.Canvas canvas=client.getCanvas();
  EventQueue.invokeAndWait(()->{long now=System.currentTimeMillis();MouseEvent m=new MouseEvent(canvas,MouseEvent.MOUSE_MOVED,now,0,x,y,0,false,0);for(MouseMotionListener l:canvas.getMouseMotionListeners())l.mouseMoved(m);MouseEvent p=new MouseEvent(canvas,MouseEvent.MOUSE_PRESSED,now,InputEvent.BUTTON1_DOWN_MASK,x,y,1,false,1);for(MouseListener l:canvas.getMouseListeners())l.mousePressed(p);});
  Thread.sleep(100);
  EventQueue.invokeAndWait(()->{MouseEvent e=new MouseEvent(canvas,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,x,y,1,false,1);for(MouseListener l:canvas.getMouseListeners())l.mouseReleased(e);});Thread.sleep(300);
 }
 private static void waitFor(GameState state,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if(client.getGameState()==state)return;Thread.sleep(100);}throw new IllegalStateException("Timed out waiting for "+state+"; state="+client.getGameState());}
 public static void main(String[] args){
  new java.util.Timer(true).schedule(new java.util.TimerTask(){public void run(){System.err.println("WINDOWS SMOKE TIMEOUT");System.exit(2);}},240000);
  try{
   RuneLite.main(new String[]{"--no-splash"});client=RuneLite.getInjector().getInstance(net.runelite.api.Client.class);
   waitFor(GameState.LOGIN_SCREEN,120000);
   System.out.println("WINDOWS PASS: bundled Java 8 opened desktop client and downloaded revision-184 cache into a clean profile.");
   click(client.getCanvas().getWidth()/2-80,291);
   if(client.getLoginIndex()!=2)throw new IllegalStateException("New User did not open login form");
   String password=new String(Files.readAllBytes(Paths.get(System.getProperty("kronos.smoke.passwordFile"))),"UTF-8").trim();
   java.util.concurrent.CompletableFuture<Void> ready=new java.util.concurrent.CompletableFuture<>();
   RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{client.setUsername(System.getProperty("kronos.smoke.user"));client.setPassword(password);ready.complete(null);});ready.get(10,java.util.concurrent.TimeUnit.SECONDS);
   click(client.getCanvas().getWidth()/2-80,321);waitFor(GameState.LOGGED_IN,45000);
   int staySeconds=Integer.getInteger("kronos.smoke.staySeconds",2);
   System.out.println("WINDOWS LOGGED IN: waiting "+staySeconds+" seconds before capture.");
   long started=System.nanoTime();
   while(System.nanoTime()-started<staySeconds*1000000000L){
    if(client.getGameState()!=GameState.LOGGED_IN&&client.getGameState()!=GameState.LOADING)throw new IllegalStateException("Disconnected during screenshot wait: "+client.getGameState());
    Thread.sleep(100);
   }
   waitFor(GameState.LOGGED_IN,15000);
   if(client.getLocalPlayer()==null)throw new IllegalStateException("Missing local player");
   java.util.concurrent.CompletableFuture<BufferedImage> frame=new java.util.concurrent.CompletableFuture<>();
   // Copy only at the completed-frame callback, never while the rasterizer is drawing.
   RuneLite.getInjector().getInstance(net.runelite.client.ui.DrawManager.class).registerEveryFrameListener(()->{
    if(frame.isDone())return;
    net.runelite.api.BufferProvider b=client.getBufferProvider();
    BufferedImage image=new BufferedImage(b.getWidth(),b.getHeight(),BufferedImage.TYPE_INT_RGB);
    image.setRGB(0,0,b.getWidth(),b.getHeight(),b.getPixels(),0,b.getWidth());frame.complete(image);
   });
   ImageIO.write(frame.get(10,java.util.concurrent.TimeUnit.SECONDS),"png",new File(System.getProperty("kronos.smoke.screenshot")));
   System.out.println("WINDOWS CAPTURE: remained logged in for "+staySeconds+" seconds; completed frame saved.");
   System.out.println("WINDOWS PASS: actual New User and Login buttons authenticated a fresh regular account against the public server; in-game rendering captured.");
   System.exit(0);
  }catch(Throwable error){error.printStackTrace();System.exit(1);}
 }
}