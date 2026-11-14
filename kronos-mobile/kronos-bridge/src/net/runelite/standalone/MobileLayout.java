package net.runelite.standalone;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import local.kronos.ui.MobileGeometry;
import local.kronos.ui.MobileGeometry.Box;
import net.runelite.api.VarClientInt;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.client.RuneLite;

/** Revision-184 widget adaptation. Widget IDs, CS2 ops and server actions stay intact. */
public final class MobileLayout {
 public static final String[] NAMES={"Combat","Skills","Quests / RSPS","Inventory","Equipment","Prayer","Spellbook","Clan chat","Account","Friends","Logout","Settings","Emotes","Music"};
 private static final int[] BUTTONS={51,52,53,54,55,56,57,35,36,37,38,39,40,41};
 private static final int[] ICONS={58,59,60,61,62,63,64,42,43,44,45,46,47,48};
 private static boolean enabled=!Boolean.getBoolean("kronos.classicLayout"),expanded=true,panelOpen=true,chatOpen=true,applying;
 private static int[] hotkeys={2,3,1,5,6};
 private static final IdentityHashMap<Widget,Saved> saved=new IdentityHashMap<>();
 private static final Map<String,Sprite> skins=new HashMap<>();
 private static final BufferedImage[] icons=new BufferedImage[14];
 private static final Object[][] operations=new Object[14][];
 private static final int ROOT = 161;
 private static final int MAIN_LAYER = 10; // Native parent of the main dialog and world map.
 private static Widget[] base,mobileWidgets;
 private static boolean mainLayerInFront;
 private static boolean chatBodyVisible,chatThrough;
 private static boolean chatHostVisible,chatStateReady;
 private static int chatHostId=-1;
 private static Rectangle chatBackground=new Rectangle();
 private static java.util.List<Rectangle> chatControls=Collections.emptyList();
 private static volatile int[] interactions={0,0,0,0,0,765,503,0};
 private static int requestSeq,requestType,requestArg;
 private static double zoom=1;
 private static volatile boolean editing;
 private static final MobileGeometry.Positions positions=new MobileGeometry.Positions(),beforeEdit=new MobileGeometry.Positions();
 public static volatile MobileGeometry geometry=new MobileGeometry(765,503,true);
 private static final class Saved {
  final int parent,x,y,w,h,rawX,rawY,rawW,rawH,xa,ya,wa,ha,alpha,sprite;
  final boolean hidden,noClick;
  final Object[] op;
  Saved(Widget a){parent=a.parentId;x=a.x;y=a.y;w=a.width;h=a.height;rawX=a.rawX;rawY=a.rawY;rawW=a.rawWidth;rawH=a.rawHeight;xa=a.xAlignment;ya=a.yAlignment;wa=a.widthAlignment;ha=a.heightAlignment;alpha=a.transparencyTop;sprite=a.spriteId2;hidden=a.isHidden;noClick=a.noClickThrough;op=a.onOp;}
  void restore(Widget a){a.parentId=parent;a.x=x;a.y=y;a.width=w;a.height=h;a.rawX=rawX;a.rawY=rawY;a.rawWidth=rawW;a.rawHeight=rawH;a.xAlignment=xa;a.yAlignment=ya;a.widthAlignment=wa;a.heightAlignment=ha;a.transparencyTop=alpha;a.spriteId2=sprite;a.isHidden=hidden;a.noClickThrough=noClick;a.onOp=op;}
 }
 static {load();}
 private static Client c(){return (Client)RuneLite.getInjector().getInstance(net.runelite.api.Client.class);}
 private static void remember(Widget w){if(w!=null&&!saved.containsKey(w))saved.put(w,new Saved(w));}
 private static Widget w(int group,int child){return (Widget)c().getWidget(group,child);}
 public static boolean enabled(){return enabled;}
 public static boolean editing(){return editing;}
 public static void style(int id,int size,int opacity){if(editing){positions.style(id,size,opacity);apply();}}
 public static void move(int id,int x,int y){if(!editing)return;positions.move(id,x,y,geometry.editable.get(id),geometry.width,geometry.height);apply();}
 public static int[] interactions(){return interactions;}
 private static void request(int type,int arg){requestType=type;requestArg=arg;requestSeq++;}
 public static void configure(int setting,int value){
  if(setting==0){enabled=value!=0;if(!enabled)restore();}
  else if(setting==1){expanded=value!=0;}
  else if(setting==3&&editing){select(Math.floorMod(value,14));panelOpen=true;}
  else if(setting==2){
   if(value==1&&!editing){beforeEdit.copyFrom(positions);editing=true;}
   else if(value==0){editing=false;}
   else if(value==2){positions.copyFrom(beforeEdit);editing=false;}
   else if(value==3&&editing){positions.clear();}
  }
  else if(setting>=10&&setting<15){hotkeys[setting-10]=Math.floorMod(value,14);}
  if(!editing)save();apply();
 }
 private static void restore(){
  for(Map.Entry<Widget,Saved> entry:saved.entrySet())entry.getValue().restore(entry.getKey());
  if(base!=null&&UserComparator5.Widget_interfaceComponents[ROOT]==mobileWidgets)
   UserComparator5.Widget_interfaceComponents[ROOT]=base;
  for(Integer id:new ArrayList<Integer>(Client.widgetSpriteOverrides.keySet()))if((id>>>16)==161)Client.widgetSpriteOverrides.remove(id);
  saved.clear();base=null;mobileWidgets=null;mainLayerInFront=false;skins.clear();
  // Recalculate the shared chat children against the active frame's own host.
  Widget chat=w(162,0);
  Widget host=chat==null?null:(Widget)chat.getParent();
  if(host!=null)GameShell.method1005(UserComparator5.Widget_interfaceComponents[host.id>>>16],host,false);
 }
 private static void load(){
  Properties p=new Properties();try(InputStream in=new FileInputStream(new File(System.getProperty("user.home"),".kronos/mobile-ui.properties"))){
   p.load(in);positions.load(p);expanded=Boolean.parseBoolean(p.getProperty("expanded","true"));
   for(int i=0;i<5;i++)hotkeys[i]=Math.floorMod(Integer.parseInt(p.getProperty("hotkey."+i,Integer.toString(hotkeys[i]))),14);
  }catch(Exception ignored){}
 }
 private static void save(){
  Properties p=new Properties();positions.store(p);p.setProperty("expanded",Boolean.toString(expanded));
  for(int i=0;i<5;i++)p.setProperty("hotkey."+i,Integer.toString(hotkeys[i]));
  File f=new File(System.getProperty("user.home"),".kronos/mobile-ui.properties");
  try(OutputStream out=new FileOutputStream(f)){p.store(out,"Mobile UI preferences");}catch(IOException e){System.err.println("Could not save mobile UI preferences");}
 }
 /** Visit the native main layer last in both traversals, keeping component IDs and array indices intact. */
 static int widgetIndex(Widget[] widgets,int parent,int index){
  if(!mainLayerInFront||parent!=-1||widgets!=mobileWidgets||index<MAIN_LAYER)return index;
  return index==widgets.length-1?MAIN_LAYER:index+1;
 }
 private static boolean hasInterface(int child){
  return Client.interfaceParents.method6346((long)((ROOT<<16)|child))!=null;
 }
 static boolean mainInterfaceCovers(int x,int y){
  int root=Client.rootInterface;
  for(int id:root==548?new int[]{21,22}:new int[]{13,14}){
   if(Client.interfaceParents.method6346((long)((root<<16)|id))==null)continue;
   Widget host=w(root,id);
   if(host!=null&&!host.isHidden()&&host.getBounds().contains(x,y))return true;
  }
  return false;
 }
 private static boolean mobileChatActive(){return enabled&&Client.gameState==30&&Client.rootInterface==ROOT;}
 static boolean chatEditorVisible(){return chatStateReady&&chatHostVisible&&chatBodyVisible;}
 /** Used by both native noClickThrough and hosted-interface menu clearing. */
 public static boolean blocksNativeInput(Widget widget,int x,int y){
  if(!chatStateReady||Client.gameState!=30||widget==null||widget.id!=chatHostId&&widget.id>>>16!=162)return true;
  return MobileChat.consumes(chatBodyVisible,chatThrough,chatBackground,chatControls,x,y);
 }
 static boolean acceptsEditor(net.runelite.api.widgets.Widget widget,int x,int y){
  return widget!=null&&!widget.isHidden()&&widget.getBounds().contains(x,y)&&!Client.isMenuOpen&&!mainInterfaceCovers(x,y)&&chatEditorVisible();
 }
 private static void refreshChat(boolean active){
  Widget root=w(162,0),body=w(162,37),host=active?w(ROOT,29):root==null?null:(Widget)root.getParent();
  chatStateReady=true;chatHostId=host==null?-1:host.id;
  chatHostVisible=host!=null&&!host.isHidden()&&root!=null&&!root.isHidden()&&(!active||chatOpen);
  chatBackground=active?new Rectangle(geometry.chat.x,geometry.chat.y,geometry.chat.w,geometry.chat.h):host==null?new Rectangle():host.getBounds();
  chatThrough=c().getVarbit(MobileChat.CLICK_THROUGH_VARBIT)==1;
  chatBodyVisible=chatHostVisible&&body!=null&&!body.isHidden();
  chatControls=chatHostVisible?MobileChat.controls(UserComparator5.Widget_interfaceComponents[162],chatBackground):Collections.emptyList();
  // A server dialogue hosted inside chat remains an opaque input surface.
  Widget[] chat=UserComparator5.Widget_interfaceComponents[162];
  if(chatHostVisible&&chat!=null)for(Widget widget:chat){
   if(widget==null||widget.isHidden())continue;
   InterfaceParent node=(InterfaceParent)Client.interfaceParents.method6346((long)widget.id);
   if(node!=null&&node.type==0){Rectangle r=chatBackground.intersection(widget.getBounds());if(!r.isEmpty())chatControls.add(r);}
  }
  if(host!=null)host.noClickThrough=MobileChat.consumes(chatBodyVisible,chatThrough,chatBackground,chatControls,MouseHandler.MouseHandler_x,MouseHandler.MouseHandler_y);
 }
 public static void apply(){
  if(applying)return;
  if(!enabled||Client.gameState!=30||c().getWidgetRoot()!=ROOT){
   if(base!=null)restore();
   if(Client.gameState==30)refreshChat(false);else{chatStateReady=false;chatControls=Collections.emptyList();}
   publish(false);return;
  }
  applying=true;
  try{
   Widget[] current=UserComparator5.Widget_interfaceComponents[ROOT];
   if(current==null||current.length<82)return;
   if(current!=mobileWidgets){
    if(base!=null)restore();
    base=current;
    Widget[] extended=Arrays.copyOf(current,92);
    for(int i=0;i<14;i++){
     Widget icon=base[ICONS[i]];if(icon.getSprite()!=null)icons[i]=icon.getSprite().toBufferedImage();
     Widget button=base[BUTTONS[i]];remember(button);operations[i]=button.onOp;
     final int tab=i;
     button.setOnOpListener(new Object[]{(JavaScriptCallback)e->activate(tab,e.getOp())});
    }
    for(int i=82;i<92;i++){
     Widget button=new Widget();button.id=(161<<16)|i;button.parentId=-1;button.isIf3=true;button.type=5;button.spriteId2=1;button.hasListener=true;button.clickMask=6;button.noClickThrough=true;
     final int action=i-82;button.setAction(0,action<5?NAMES[hotkeys[action]]:action==5?"Collapse / expand columns":action==6?"Mobile controls":action==7?"Show / hide chat":action==8?"Keyboard":"Logout");
     if(action<5)button.setAction(1,"Customize hotkey");
     button.setOnOpListener(new Object[]{(JavaScriptCallback)e->{
      if(action<5){if(e.getOp()==2)request(2,action);else select(hotkeys[action]);}
      else if(action==5){expanded=!expanded;save();}
      else if(action==6)request(1,0);
      else if(action==7)chatOpen=!chatOpen;
      else if(action==8){chatOpen=true;MobileUi.openField(3);}
      else select(10);
     }});
     extended[i]=button;
    }
    UserComparator5.Widget_interfaceComponents[ROOT]=mobileWidgets=extended;
   }
   mainLayerInFront=hasInterface(13)||hasInterface(14);
   geometry=new MobileGeometry(FloorDecoration.canvasWidth,WallDecoration.canvasHeight,expanded||editing,c().getVar(VarClientInt.INTERFACE_TAB),positions);
   MobileGeometry g=geometry;
   int selected=c().getVar(VarClientInt.INTERFACE_TAB);
   for(int i=0;i<14;i++){
    Widget button=w(161,BUTTONS[i]);Box box=g.tabs[i];
    remember(button);button.parentId=-1;button.noClickThrough=true;
    button.isHidden=box==null;button.spriteId2=1;
    if(box!=null){button.transparencyTop=alpha(i);place(button,box);Client.widgetSpriteOverrides.put(button.id,skin(box.w,box.h,i,panelOpen&&selected==i,""));}
    Widget icon=w(161,ICONS[i]);remember(icon);icon.isHidden=true;
   }
   for(int id:new int[]{31,32,33,34,49,50}){Widget a=w(161,id);remember(a);a.isHidden=true;}
   place(w(161,30),g.panel);w(161,30).isHidden=!panelOpen;w(161,30).spriteId2=1;w(161,30).transparencyTop=70;
   Client.widgetSpriteOverrides.put(w(161,30).id,panelSkin());
   place(w(161,65),g.inventory);w(161,65).isHidden=!panelOpen;w(161,65).noClickThrough=true;
   // The actual minimap keeps its native hit mask, compass, orb ops and drawing path.
   place(w(161,17),g.minimap);
   place(w(161,29),g.chat);w(161,29).isHidden=!chatOpen;
   // Move the native chat controls without changing the game's sprites, colors or opacity.
   place(w(162,1),new Box(0,0,g.chat.w,23));
   place(w(162,37),new Box(0,23,g.chat.w,142));
   refreshChat(true);
   for(int i=0;i<5;i++){
    Widget a=w(161,82+i);a.setAction(0,NAMES[hotkeys[i]]);a.transparencyTop=alpha(14+i);place(a,g.hotkeys[i]);
    Client.widgetSpriteOverrides.put(a.id,skin(g.hotkeys[i].w,g.hotkeys[i].h,hotkeys[i],panelOpen&&selected==hotkeys[i],""));
   }
   custom(87,g.collapse,-1,false,expanded?"<":">");
   custom(88,g.menu,-1,false,"menu");
   custom(89,g.chatToggle,-1,chatOpen,"chat");
   custom(90,g.keyboard,-1,false,"keys");
   // Logout stays accessible in a compact button below the minimap.
   custom(91,g.logout,10,false,"");
   Client.zoomHeight=(short)Math.round(256*zoom);Client.zoomWidth=(short)Math.round(320*zoom);
   publish(true);
  }finally{applying=false;}
 }
 private static int alpha(int id){return (100-positions.opacity(id))*256/100;}
 private static void custom(int id,Box box,int icon,boolean selected,String glyph){
  Widget a=w(161,id);a.isHidden=false;a.transparencyTop=alpha(id-65);place(a,box);Client.widgetSpriteOverrides.put(a.id,skin(box.w,box.h,icon,selected,glyph));
 }
 private static void place(Widget a,Box box){
  if(a==null)return;remember(a);
  a.xAlignment=a.yAlignment=a.widthAlignment=a.heightAlignment=0;
  a.rawX=a.x=box.x;a.rawY=a.y=box.y;a.rawWidth=a.width=box.w;a.rawHeight=a.height=box.h;
 }
 private static void activate(int tab,int op){
  int old=c().getVar(VarClientInt.INTERFACE_TAB);
  boolean close=op==1&&panelOpen&&old==tab;
  if(operations[tab]!=null){
   ScriptEvent e=new ScriptEvent();e.widget=w(161,BUTTONS[tab]);e.opIndex=op;e.args=operations[tab];c().runScript(e,500000);
  }
  panelOpen=!close;
 }
 public static void select(int tab){
  if(tab<0||tab>=14)return;
  Widget button=w(161,BUTTONS[tab]);
  c().invokeMenuAction(-1,button.id,57,1,NAMES[tab],"",button.getBounds().x+10,button.getBounds().y+10);
 }
 private static Sprite panelSkin(){
  Sprite found=skins.get("panel");if(found!=null)return found;
  BufferedImage image=new BufferedImage(200,281,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
  g.setColor(new Color(0x484335));g.fillRect(0,0,200,281);
  g.setColor(new Color(0x211e16));g.drawRect(0,0,199,280);g.drawRect(1,1,197,278);
  g.setColor(new Color(0x9a8e6f));g.drawRect(2,2,195,276);
  g.setColor(new Color(0x625b46));g.drawRect(3,3,193,274);
  g.setColor(new Color(0x25221a));g.drawRect(4,4,191,272);
  g.dispose();Sprite s=new Sprite(image.getRGB(0,0,200,281,null,0,200),200,281);skins.put("panel",s);return s;
 }
 private static Sprite skin(int width,int height,int icon,boolean selected,String glyph){
  String key=width+":"+height+":"+icon+":"+selected+":"+glyph;
  Sprite found=skins.get(key);if(found!=null)return found;
  BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
  Graphics2D g=image.createGraphics();
  g.setPaint(new GradientPaint(0,0,new Color(selected?0x6a3927:0x69665a),0,height,new Color(selected?0x382219:0x3e3b32)));
  g.fillRoundRect(1,1,width-2,height-2,5,5);
  g.setColor(new Color(0x28261e));g.drawRoundRect(0,0,width-1,height-1,5,5);
  g.setColor(new Color(selected?0xb29850:0x8e8874));g.drawLine(3,2,width-4,2);g.drawLine(2,3,2,height-4);
  g.setColor(new Color(0x211f19));g.drawLine(3,height-3,width-3,height-3);g.drawLine(width-3,3,width-3,height-3);
  if(icon>=0&&icons[icon]!=null){
   BufferedImage src=icons[icon];double scale=Math.min(1.8,Math.min((width-8.0)/src.getWidth(),(height-6.0)/src.getHeight()));
   int sw=(int)(src.getWidth()*scale),sh=(int)(src.getHeight()*scale);
   g.drawImage(src,(width-sw)/2,(height-sh)/2,sw,sh,null);
  }else{
   g.setColor(new Color(0xe1d6b9));int cx=width/2,cy=height/2;
   if(glyph.equals("menu")){for(int j=-1;j<=1;j++)g.fillRect(cx-10,cy+j*5-1,20,2);}
   else if(glyph.equals("keys")){g.drawRect(cx-13,cy-7,26,14);for(int row=0;row<2;row++)for(int col=0;col<6;col++)g.fillRect(cx-10+col*4,cy-4+row*4,2,2);g.fillRect(cx-7,cy+4,14,2);}
   else if(glyph.equals("chat")){g.drawRoundRect(cx-12,cy-8,24,14,4,4);g.drawLine(cx-7,cy+6,cx-11,cy+10);g.drawLine(cx-11,cy+10,cx,cy+6);for(int j=-1;j<=1;j++)g.fillRect(cx+j*6-1,cy-2,2,2);}
   else{int sign=glyph.equals("<")?-1:1;g.fillPolygon(new int[]{cx+sign*6,cx-sign*5,cx-sign*5},new int[]{cy,cy-9,cy+9},3);}
  }
  g.dispose();Sprite sprite=new Sprite(image.getRGB(0,0,width,height,null,0,width),width,height);skins.put(key,sprite);return sprite;
 }
 private static void add(java.util.List<Integer> out,Rectangle r,int kind){
  if(r==null||r.width<=0||r.height<=0||r.x<0||r.y<0)return;
  out.add(r.x);out.add(r.y);out.add(r.width);out.add(r.height);out.add(kind);
 }
 private static void add(java.util.List<Integer> out,Box b,int kind){if(b!=null)add(out,new Rectangle(b.x,b.y,b.w,b.h),kind);}
 private static void addOverlayControls(java.util.List<Integer> out,Widget[] widgets,int parent,Rectangle clip){
  for(Widget widget:widgets){
   if(widget==null||widget.parentId!=parent||widget.isHidden||widget.id==chatHostId)continue;
   Rectangle bounds=clip.intersection(widget.getBounds());
   if(bounds.isEmpty())continue;
   if(widget.contentType==1338||widget.onClick!=null||widget.onScroll!=null||class12.method148(widget)!=0
       ||widget.scrollHeight>widget.height){
    add(out,bounds,1);
   }else if(widget.type==0){
    addOverlayControls(out,widgets,widget.id,bounds);
    if(widget.children!=null)addOverlayControls(out,widget.children,widget.id,bounds);
   }
  }
 }
 private static void addHostedInterface(java.util.List<Integer> out,Widget host,Rectangle canvas,boolean entireWindow){
  if(host==null||host.isHidden())return;
  InterfaceParent node=(InterfaceParent)Client.interfaceParents.method6346((long)host.id);
  if(node==null)return;
  Widget[] children=UserComparator5.Widget_interfaceComponents[node.group];
  if(children==null)return;
  Rectangle clip=canvas.intersection(host.getBounds());
  if(clip.isEmpty())return;
  if(node.type==0||entireWindow){
   for(Widget child:children)
    if(child!=null&&child.parentId==-1&&!child.isHidden())add(out,clip.intersection(child.getBounds()),1);
  }else{
   // Transparent status overlay containers are not touch targets.
   addOverlayControls(out,children,-1,clip);
  }
 }
 private static void publish(boolean active){
  java.util.List<Integer> data=new ArrayList<>();
  Collections.addAll(data,active?1:0,Client.gameState,requestSeq,requestType,requestArg,FloorDecoration.canvasWidth,WallDecoration.canvasHeight,Client.isMenuOpen?1:0);
  if(Client.gameState==30){
   if(Client.isMenuOpen)add(data,new Rectangle(UrlRequester.menuX,class37.menuY,FriendSystem.menuWidth,WorldMapDecoration.menuHeight),2);
   int root=Client.rootInterface;
   Widget[] widgets=root<0?null:UserComparator5.Widget_interfaceComponents[root];
   Rectangle canvas=new Rectangle(0,0,FloorDecoration.canvasWidth,WallDecoration.canvasHeight);
   if(widgets!=null){
    for(Widget widget:widgets)
     if(widget!=null&&widget.contentType==1337&&!widget.isHidden())
      add(data,canvas.intersection(widget.getBounds()),0);
   }
   if(active){
    MobileGeometry g=geometry;
    for(Box b:g.tabs)add(data,b,1);
    for(Box b:g.hotkeys)add(data,b,1);
    add(data,g.collapse,1);add(data,g.menu,1);add(data,g.chatToggle,1);add(data,g.keyboard,1);
    add(data,g.minimap,1);add(data,g.logout,1);
    if(panelOpen)add(data,g.panel,1);
    if(editing)for(Map.Entry<Integer,Box> entry:g.editable.entrySet()){
     if(entry.getKey()>=40&&!panelOpen)continue;
     if(entry.getKey()==21&&!chatOpen)continue;
     add(data,entry.getValue(),100+entry.getKey());
     Collections.addAll(data,positions.size(entry.getKey()),positions.opacity(entry.getKey()),0,0,200+entry.getKey());
    }
   }
   if(chatBodyVisible&&!chatThrough)add(data,chatBackground,1);
   for(Rectangle control:chatControls)add(data,control,1);
   if(widgets!=null){
    // Native roots retain their own hit regions, including the fixed viewport.
    if(!active)addOverlayControls(data,widgets,-1,canvas);
    int main=root==548?21:13;
    int map=root==548?22:14;
    for(Widget host:widgets)
     if(host!=null&&host.id!=chatHostId) {
      int child=host.id&65535;
      if(!active||child!=29&&child!=65&&child!=66&&child!=77&&child!=28)
       addHostedInterface(data,host,canvas,child==main||child==map);
     }
   }
  }
  int[] next=new int[data.size()];for(int i=0;i<next.length;i++)next[i]=data.get(i);interactions=next;
 }
 public static void camera(int dxMilli,int dyMilli){
  if(editing||Client.gameState!=30||Client.isMenuOpen||Client.isCameraLocked)return;
  Client.camAngleDY=Client.camAngleDX=0;
  Client.camAngleY=(Client.camAngleY-(int)Math.round(dxMilli/1000.0))&2047;
  Client.camAngleX=Math.max(128,Math.min(383,Client.camAngleX+(int)Math.round(dyMilli/1000.0)));
  Client.onCameraPitchTargetChanged(-1);
 }
 public static void zoom(int logDeltaMilli){
  if(editing||Client.gameState!=30||Client.isMenuOpen||Client.isCameraLocked)return;
  zoom=Math.max(.5,Math.min(2.5,zoom*Math.exp(logDeltaMilli/1000.0)));
  Client.zoomHeight=(short)Math.round(256*zoom);Client.zoomWidth=(short)Math.round(320*zoom);
 }
}
