import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.imageio.ImageIO;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.widgets.*;
import net.runelite.api.vars.*;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.standalone.MobileLayout;
import local.kronos.ui.MobileGeometry;

public final class MobileLayoutTest {
 private static Client c;
 private static String out;
 private static <T>T game(Supplier<T> action)throws Exception{
  CompletableFuture<T> f=new CompletableFuture<>();
  RuneLite.getInjector().getInstance(ClientThread.class).invokeLater(()->{try{f.complete(action.get());}catch(Throwable e){f.completeExceptionally(e);}});
  return f.get(10,TimeUnit.SECONDS);
 }
 private static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 private static void tap(int button,int x,int y)throws Exception{
  KronosBoot.input(18,x,y,button,"");Thread.sleep(270);
 }
 private static void click(Widget widget)throws Exception{
  Rectangle r=game(()->widget.getBounds());require(r.x>=0&&r.width>0,"Widget not visible "+widget.getId());tap(1,r.x+r.width/2,r.y+r.height/2);
 }
 private static void capture(String name)throws Exception{
  if(!game(()->c.isMenuOpen()))KronosBoot.input(2,550,180,0,"");Thread.sleep(400);int[] p=KronosBoot.frame();BufferedImage image=new BufferedImage(p[0],p[1],BufferedImage.TYPE_INT_RGB);
  image.setRGB(0,0,p[0],p[1],p,2,p[0]);ImageIO.write(image,"png",new File(out,name+".png"));
 }
 private static String plain(String text){return text==null?"":text.replaceAll("<[^>]+>","").trim();}
 private static Widget findText(String text,boolean contains)throws Exception{
  return game(()->{
   net.runelite.rs.api.RSWidget[][] all=((net.runelite.rs.api.RSClient)c).getWidgets();
   for(net.runelite.rs.api.RSWidget[] group:all)if(group!=null)for(Widget w:group)if(w!=null&&!w.isHidden()){
    String s=plain(w.getText());if(contains?s.contains(text):s.equalsIgnoreCase(text))return w;
    for(Widget child:w.getDynamicChildren())if(child!=null&&!child.isHidden()){String t=plain(child.getText());if(contains?t.contains(text):t.equalsIgnoreCase(text))return child;}
   }return null;
  });
 }
 private static void tab(int tab)throws Exception{
  MobileGeometry g=MobileLayout.geometry;MobileGeometry.Box b=g.tabs[tab];
  if(tab==10)tap(1,g.width-18,186);else {require(b!=null,"Tab missing "+tab);tap(1,b.x+b.w/2,b.y+b.h/2);}
  require(game(()->c.getVar(VarClientInt.INTERFACE_TAB))==tab,"Tab action mismatch "+tab);
 }
 private static void settle(long millis)throws Exception{Thread.sleep(millis);}
 private static void await(BooleanSupplier condition,String detail)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
  while(System.nanoTime()<deadline){if(game(()->condition.getAsBoolean()))return;settle(100);}
  throw new AssertionError(detail);
 }
 private static Widget visible(int group,int child){
  Widget widget=c.getWidget(group,child);
  return widget==null||widget.isHidden()?null:widget;
 }
 private static void command(String command)throws Exception{
  game(()->{c.setVar(VarClientStr.CHATBOX_TYPED_TEXT,"::"+command);return null;});
  KronosBoot.input(10,0,0,10,"");settle(450);
 }
 private static boolean regionContains(int x,int y,int kind){
  int[] regions=MobileLayout.interactions();
  for(int i=8;i+4<regions.length;i+=5)
   if(regions[i+4]==kind&&new Rectangle(regions[i],regions[i+1],regions[i+2],regions[i+3]).contains(x,y))return true;
  return false;
 }
 private static void teleport(int slot,int x,int y,boolean minimizeChat)throws Exception{
  command("teleports");
  await(()->visible(719,slot)!=null,"Teleport Manager did not open");
  if(minimizeChat){
   MobileGeometry.Box button=MobileLayout.geometry.chatToggle;
   tap(1,button.x+12,button.y+12);
   await(()->visible(161,29)==null,"Chat did not minimize");
  }
  click(game(()->visible(719,35))); // Monsters
  click(game(()->visible(719,slot)));
  await(()->{Widget info=visible(719,159);return info!=null&&plain(info.getText()).equals("Teleport Cost: Free");},
      "Destination tap did not select a teleport");
  click(game(()->visible(719,155)));
  await(()->{
   net.runelite.api.coords.WorldPoint point=c.getLocalPlayer().getWorldLocation();
   return point.getX()==x&&point.getY()==y&&visible(719,155)==null;
  },"Teleport confirmation did not move the player");
 }
 public static void dialogs(String directory)throws Exception{
  out=directory;c=RuneLite.getInjector().getInstance(Client.class);
  await(()->((net.runelite.rs.api.RSClient)c).getWidgetRoot()==161,"Mobile frame not active");
  for(int i=0;i<20;i++){
   Widget choice=findText("Accept",false);
   if(choice==null)choice=findText("Easy - 100x",true);
   if(choice==null)choice=findText("No, I know what",true);
   if(choice==null)choice=findText("No",false);
   if(choice==null)choice=findText("Click here to continue",true);
   if(choice==null)break;
   click(choice);settle(650);
  }
  teleport(41,2676,3711,false); // Rock Crabs with chat open.
  settle(1500); // The destination scene and its UI must finish loading before the next tap.
  MobileGeometry g=MobileLayout.geometry;
  teleport(42,1723,3464,true); // Sand Crabs with chat minimized.
  settle(1500);
  System.out.println("MOBILE-UI PASS: real teleport category, destination and confirmation taps with chat open and minimized.");
  int centerX=g.width/2,centerY=g.height/2;
  require(regionContains(centerX,centerY,0)&&!regionContains(centerX,centerY,1),
      "Center of the viewport is reserved by an interface");
  int yaw=game(()->c.getCameraYaw());
  Object destination=game(()->c.getLocalDestinationLocation());
  KronosBoot.input(13,90000,30000,0,"");settle(300);
  require(game(()->c.getCameraYaw())!=yaw&&Objects.equals(destination,game(()->c.getLocalDestinationLocation())),
      "Camera rotation did not preserve world destination");
  verifyWalkCross(-1);
  verifyWalkCross(1);
  System.out.println("MOBILE-UI PASS: native yellow walk cross follows both ground taps.");
  tap(1,g.chatToggle.x+12,g.chatToggle.y+12);
  game(()->{net.runelite.standalone.MobileLayoutProbe.fixed(true);return null;});
  await(()->((net.runelite.rs.api.RSClient)c).getWidgetRoot()==548&&visible(162,38)!=null,"Native fixed chat did not return");
  require(game(()->visible(162,38).getType()==0),"Fixed chat background container was replaced");
  KronosBoot.input(7,1080,510,0,"");
  settle(650);
  require(game(()->((net.runelite.rs.api.RSClient)c).getWidgetRoot()==548),"Surface resize switched out of fixed mode");
  capture("fixed-native-chat");
  game(()->{net.runelite.standalone.MobileLayoutProbe.fixed(false);return null;});
  await(()->((net.runelite.rs.api.RSClient)c).getWidgetRoot()==161&&visible(161,54)!=null,"Could not switch back to fullscreen");
  capture("fullscreen-native-chat");
  System.out.println("MOBILE-UI PASS: camera at the viewport center, native fixed chat, and fullscreen restoration.");
  worldTapsAndMap();
 }
 private static void verifyWalkCross(int side)throws Exception{
  Point target=game(()->{
   LocalPoint start=c.getLocalPlayer().getLocalLocation();
   int plane=c.getPlane();
   int[][] flags=c.getCollisionMaps()[plane].getFlags();
   Tile[][] tiles=c.getScene().getTiles()[plane];
   Point best=null;
   int bestOffset=0;
   for(int dx=-7;dx<=7;dx++)for(int dy=-7;dy<=7;dy++){
    if(Math.abs(dx)+Math.abs(dy)<4)continue;
    int x=start.getSceneX()+dx,y=start.getSceneY()+dy;
    if((flags[x][y]&CollisionDataFlag.BLOCK_MOVEMENT_FULL)!=0)continue;
    Tile tile=tiles[x][y];
    if(tile==null||tile.getWallObject()!=null||tile.getDecorativeObject()!=null||tile.getGroundObject()!=null
        ||Arrays.stream(tile.getGameObjects()).anyMatch(Objects::nonNull))continue;
    Point p=Perspective.localToCanvas(c,LocalPoint.fromScene(x,y),plane);
    if(p!=null&&regionContains(p.getX(),p.getY(),0)&&!regionContains(p.getX(),p.getY(),1)){
     int offset=side*(p.getX()-MobileLayout.geometry.width/2);
     if(offset>bestOffset){best=p;bestOffset=offset;}
    }
   }
   return best;
  });
  require(target!=null,"No clear ground in half "+side);
  KronosBoot.input(18,target.getX(),target.getY(),1,"");
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  String observed="";
  while(System.nanoTime()<deadline){
   if(game(()->net.runelite.standalone.MobileLayoutProbe.walkCrossAt(target.getX(),target.getY())
       &&c.getLocalDestinationLocation()!=null))return;
   String cross=game(net.runelite.standalone.MobileLayoutProbe::walkCross);
   if(cross.startsWith("1 "))observed=cross;
  }
  throw new AssertionError("Native yellow walk cross missing at "+target+"; observed "+observed);
 }
 private static void worldTapsAndMap()throws Exception{
  command("tele 2009 3578 0");
  await(()->c.getLocalPlayer().getWorldLocation().getX()==2009,"Could not reach the home bank");
  settle(1600);
  click(game(()->visible(160,43)));
  await(()->visible(595,37)!=null,"Floating map did not open");
  await(()->net.runelite.standalone.MobileLayoutProbe.localMapShown(),"Custom home did not show its actual local map");
  capture("home-local-map");
  click(game(()->visible(595,37)));
  await(()->visible(595,37)==null,"Floating map did not close");
  KronosBoot.input(14,0,0,-600,"");
  game(()->{net.runelite.standalone.MobileLayoutProbe.bankCamera();return null;});
  MobileGeometry.Box chatButton=MobileLayout.geometry.chatToggle;
  tap(1,chatButton.x+12,chatButton.y+12);
  settle(400);
  NPC banker=game(()->c.getNpcs().stream()
      .filter(npc->npc.getId()==3888)
      .filter(npc->{
       net.runelite.api.Point point=Perspective.localToCanvas(c,npc.getLocalLocation(),c.getPlane(),npc.getLogicalHeight()/2);
       return point!=null&&regionContains(point.getX(),point.getY(),0)&&!regionContains(point.getX(),point.getY(),1);
      })
      .min(Comparator.comparingInt(npc->npc.getWorldLocation().distanceTo(c.getLocalPlayer().getWorldLocation())))
      .orElseThrow(()->new AssertionError("No home banker in the unobstructed viewport")));
  net.runelite.api.Point target=game(()->Perspective.localToCanvas(c,banker.getLocalLocation(),c.getPlane(),banker.getLogicalHeight()/2));
  capture("home-bank-before-tap");
  require(target!=null&&regionContains(target.getX(),target.getY(),0)&&!regionContains(target.getX(),target.getY(),1),
      "Banker is outside the unobstructed viewport "+target);
  // The first press has no AWT hover event. The scene must pick it at the new coordinates.
  tap(3,target.getX(),target.getY());
  require(game(()->c.isMenuOpen()&&Arrays.stream(c.getMenuEntries())
      .anyMatch(entry->entry.getIdentifier()==banker.getIndex()&&plain(entry.getTarget()).equals("Banker"))),
      "Fresh press did not pick the intended banker at "+target);
  tap(1,1,1);
  KronosBoot.input(2,MobileLayout.geometry.width/2,MobileLayout.geometry.height-35,0,"");
  settle(150);
  tap(1,target.getX(),target.getY());
  await(()->{
   Actor actor=c.getLocalPlayer().getInteracting();
   return actor==banker||visible(12,0)!=null||visible(231,0)!=null;
  },"Banker tap did not reach its intended interaction");
  int[] dimensions=new int[2];
  int needed=-KronosBoot.copyFrame(null,dimensions,true);
  require(needed>0,"No reusable Android frame");
  java.nio.ByteBuffer pixels=java.nio.ByteBuffer.allocateDirect(needed);
  require(game(()->{
   int[] frame=KronosBoot.frame();
   return KronosBoot.copyFrame(pixels,dimensions,true)==needed
       &&dimensions[0]*dimensions[1]*4==needed
       &&pixels.getInt(0)==frame[2]
       &&KronosBoot.copyFrame(pixels,dimensions,false)==0;
  }),"Android frame transfer or duplicate-frame check failed");
  System.out.println("MOBILE-UI PASS: local map at custom home and fresh banker targeting.");
  System.out.println("MOBILE-UI PASS: reusable Android frame transfer and duplicate-frame suppression.");
 }
 public static void run(String directory)throws Exception{
  out=directory;new File(out).mkdirs();c=RuneLite.getInjector().getInstance(Client.class);
  KronosBoot.input(7,1090,503,0,"");settle(800);
  game(()->{MobileLayout.configure(2,1);MobileLayout.configure(2,3);MobileLayout.configure(2,0);MobileLayout.configure(1,1);return null;});
  int empty=0;
  for(int i=0;i<48&&empty<5;i++){
   Widget choice=findText("Accept",false);
   if(choice==null)choice=findText("Easy - 100x",true);
   if(choice==null)choice=findText("No, I know what",true);
   if(choice==null)choice=findText("No",false);
   if(choice==null)choice=findText("Click here to continue",true);
   if(choice==null){empty++;settle(500);}else{System.out.println("MOBILE-UI onboarding: "+plain(choice.getText()));click(choice);settle(600);empty=0;}
  }
  capture("01-overview");
  require(game(()->c.getItemContainer(InventoryID.INVENTORY)!=null&&c.getItemContainer(InventoryID.INVENTORY).getItems().length>0),"Onboarding did not provide inventory");
  for(int tab=0;tab<14;tab++){tab(tab);capture("tab-"+tab);}
  tab(3);if(game(()->c.getWidget(161,65).isHidden()))tab(3);
  MobileGeometry g=MobileLayout.geometry;
  Rectangle inv=game(()->c.getWidget(149,0).getBounds());
  require(inv.x>=g.panel.x&&inv.x<g.panel.x+g.panel.w,"Inventory not relocated");
  // Selected tab closes and reopens its real container.
  tab(3);require(game(()->c.getWidget(161,65).isHidden()),"Selected tab did not close");tab(3);
  // Extra column toggles without losing current panel.
  tap(1,g.collapse.x+19,g.collapse.y+19);require(MobileLayout.geometry.tabs[1]==null,"Column did not collapse");capture("02-collapsed");
  g=MobileLayout.geometry;tap(1,g.collapse.x+19,g.collapse.y+19);require(MobileLayout.geometry.tabs[1]!=null,"Column did not expand");
  System.out.println("MOBILE-UI PASS: all fourteen real tab actions, panel toggle, column collapse and inventory placement.");
  // Reordered native inventory: move coins to an empty grid slot, then equip a staff.
  Widget inventory=game(()->c.getWidget(149,0));
  WidgetItem first=game(()->inventory.getWidgetItem(0));
  if(first!=null){
   Rectangle r=first.getCanvasBounds();
   tap(3,r.x+12,r.y+12);
   require(game(()->c.isMenuOpen()),"Item context menu did not open");capture("03-item-context");
   require(game(()->Arrays.stream(c.getMenuEntries()).anyMatch(e->e.getOption().equals("Examine"))),"Item menu lost actions");
   KronosBoot.input(2,550,180,0,"");settle(500);
  }
  tab(4);tab(3);
  int staff=game(()->{Item[] items=c.getItemContainer(InventoryID.INVENTORY).getItems();for(int i=0;i<items.length;i++)if(items[i].getId()==1381)return i;return -1;});
  if(staff>=0){WidgetItem item=game(()->c.getWidget(149,0).getWidgetItem(staff));Rectangle r=item.getCanvasBounds();tap(1,r.x+14,r.y+14);settle(700);require(game(()->Arrays.stream(c.getItemContainer(InventoryID.EQUIPMENT).getItems()).anyMatch(i->i.getId()==1381)),"Equipment action failed");}
  tab(5);Widget prayer=game(()->c.getWidget(WidgetInfo.PRAYER_THICK_SKIN));click(prayer);settle(600);
  require(game(()->c.isPrayerActive(Prayer.THICK_SKIN)),"Prayer action failed");click(prayer);
  tab(6);click(game(()->c.getWidget(WidgetInfo.SPELL_WIND_STRIKE)));
  require(game(()->c.isSpellSelected()),"Spell selection failed");
  KronosBoot.input(10,0,0,27,"");settle(150);
  System.out.println("MOBILE-UI PASS: item context actions, equipment, prayer toggle and spell target selection.");
  // The engine camera path must not create a movement destination or select a spell.
  game(()->{((net.runelite.rs.api.RSClient)c).setSpellSelected(false);return null;});
  settle(4000);
  Object destination=game(()->c.getLocalDestinationLocation());
  int yaw=game(()->c.getCameraYaw());
  KronosBoot.input(13,90000,30000,0,"");settle(300);
  require(game(()->c.getCameraYaw())!=yaw,"Camera did not rotate");
  require(Objects.equals(destination,game(()->c.getLocalDestinationLocation())),"Camera destination changed: before="+destination+" after="+game(()->c.getLocalDestinationLocation()));
  require(!game(()->c.isSpellSelected()),"Camera selected a spell");
  KronosBoot.input(14,0,0,250,"");settle(200);capture("04-camera-zoom");
  KronosBoot.input(14,0,0,-250,"");
  System.out.println("MOBILE-UI PASS: camera changes without world action.");
  for(int[] shape:new int[][]{{1090,503},{894,503},{1230,503},{765,574},{1363,629}}){
   KronosBoot.input(7,shape[0],shape[1],0,"");settle(800);tab(3);
   if(game(()->c.getWidget(161,65).isHidden()))tab(3);
   capture("layout-"+shape[0]+"x"+shape[1]);
   MobileGeometry now=MobileLayout.geometry;
   require(game(()->c.getWidget(149,0).getBounds()).x==now.inventory.x+game(()->c.getWidget(149,0).getRelativeX()),"Draw/hit bounds drift");
  }
  // Exercise the actual editable engine widgets, including persistence and cancellation.
  KronosBoot.input(7,1090,503,0,"");settle(600);tab(3);
  if(game(()->c.getWidget(161,65).isHidden()))tab(3);
  KronosBoot.input(15,2,0,1,"");settle(200);
  KronosBoot.input(16,70,210,43,"");
  KronosBoot.input(16,300,260,3,"");KronosBoot.input(17,150,45,3,"");
  KronosBoot.input(16,370,260,0,"");KronosBoot.input(17,130,65,0,"");
  KronosBoot.input(16,300,335,14,"");settle(300);
  require(game(()->c.getWidget(161,54).getWidth())==57,"Button size not applied");
  require(game(()->c.getWidget(161,54).getOpacity())>100,"Button opacity not applied");
  require(game(()->c.getWidget(149,0).getBounds()).x==MobileLayout.geometry.inventory.x+game(()->c.getWidget(149,0).getRelativeX()),"Moved panel lost hit geometry");
  capture("07-customized-layout");
  KronosBoot.input(15,2,0,0,"");settle(200);
  Properties saved=new Properties();try(java.io.InputStream in=new java.io.FileInputStream(new File(System.getProperty("user.home"),".kronos/mobile-ui.properties"))){saved.load(in);}
  require("150,45".equals(saved.getProperty("style.3")),"Style did not save");
  tab(0);tab(3);require(MobileLayout.geometry.panel.x<100,"Per-tab panel position lost");
  KronosBoot.input(15,2,0,1,"");settle(100);KronosBoot.input(17,70,100,3,"");KronosBoot.input(15,2,0,2,"");settle(200);
  require(game(()->c.getWidget(161,54).getWidth())==57,"Cancel did not restore size");
  KronosBoot.input(15,2,0,1,"");settle(100);KronosBoot.input(15,2,0,3,"");KronosBoot.input(15,2,0,0,"");settle(200);
  require(game(()->c.getWidget(161,54).getWidth())==38,"Reset did not restore size");
  System.out.println("MOBILE-UI PASS: moved functional buttons/panels, size, opacity, save, cancel and reset.");
  // Classic mode restores the original hierarchy and mobile can be selected again.
  KronosBoot.input(15,0,0,0,"");settle(500);require(game(()->c.getWidget(161,54).getParentId())==(161<<16|50),"Classic widget hierarchy was not restored");capture("05-classic");
  KronosBoot.input(15,0,0,1,"");settle(500);capture("06-mobile-restored");
  System.out.println("MOBILE-UI PASS: five landscape layouts and classic/mobile restoration.");
 }
}
