package net.runelite.standalone;

import java.awt.Rectangle;
import java.lang.reflect.*;
import java.util.*;
import local.kronos.mobile.TouchControls;
import local.kronos.mobile.TouchRegions;
import net.runelite.api.hooks.Callbacks;
import net.runelite.rs.api.RSAbstractFont;

/** Actual Widget/native traversal plus the Android touch state machine; no game/server. */
public final class MobileChatRoutingTest {
 static int checks;static final Rectangle BOX=new Rectangle(100,100,400,200);
 static final byte[] METRICS=new byte[257];static final Font FONT;
 static{Arrays.fill(METRICS,(byte)6);METRICS[256]=12;FONT=new Font(METRICS);}
 static final class W extends Widget {
  W(int child,int parent,int type,int x,int y,int width,int height){id=(162<<16)|child;parentId=parent;this.type=type;this.x=x;this.y=y;this.width=width;this.height=height;setRenderX(x);setRenderY(y);isIf3=true;}
  @Override public RSAbstractFont getFont(){return FONT;}
  @Override public boolean isHidden(){return isHidden;}
 }
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void field(String name,Object value)throws Exception{Field f=MobileLayout.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 static void state(boolean body,boolean through,List<Rectangle> regions)throws Exception{
  field("chatStateReady",true);field("chatHostVisible",true);field("chatHostId",(161<<16)|29);field("chatBackground",BOX);field("chatBodyVisible",body);field("chatThrough",through);field("chatControls",regions);Client.gameState=30;Client.rootInterface=161;
 }
 static int[] map(boolean body,boolean through,List<Rectangle> controls,Rectangle modal,boolean menu){
  List<Integer> data=new ArrayList<>(Arrays.asList(1,30,0,0,0,765,503,menu?1:0,0,0,765,503,0));
  List<Rectangle> block=new ArrayList<>(controls);if(body&&!through)block.add(BOX);if(modal!=null)block.add(modal);
  for(Rectangle r:block)Collections.addAll(data,r.x,r.y,r.width,r.height,1);
  int[] result=new int[data.size()];for(int i=0;i<result.length;i++)result[i]=data.get(i);return result;
 }
 static List<String> gesture(int[] regions,int x,int y,boolean camera){
  List<String> events=new ArrayList<>();TouchControls touch=new TouchControls(new TouchControls.Sink(){
   public int region(float a,float b){return TouchRegions.at(regions,765,503,a,b);}
   public void tap(int button,float a,float b){events.add("tap"+button);}
   public void drag(int phase,float a,float b){events.add("drag"+phase);}
   public void wheel(float a,float b,int steps){}public void camera(float a,float b){events.add("camera");}public void zoom(float amount){events.add("zoom");}
  });touch.configure(765,503,8);touch.down(1,x,y,0);if(camera){touch.position(1,x+20,y+20);touch.moved(220);}touch.up(1,x+(camera?20:0),y+(camera?20:0),260);return events;
 }
 static void nativeTraversal(Widget widget,boolean hosted,int x,int y,boolean expectBlocked)throws Exception{
  Client.interfaceParents=new NodeHashTable(8);
  if(hosted){InterfaceParent node=new InterfaceParent();node.type=0;node.group=162;Client.interfaceParents.put(node,(long)widget.id);}
  MouseHandler.MouseHandler_x=x;MouseHandler.MouseHandler_y=y;MouseHandler.MouseHandler_lastButton=0;
  Client.menuOptionsCount=1;Client.menuActions[0]="Walk here";Client.isMenuOpen=false;
  MusicPatchNode.method3844(new Widget[]{widget},-1,0,0,765,503,0,0);
  check(expectBlocked?"Cancel".equals(Client.menuActions[0]):"Walk here".equals(Client.menuActions[0]),"Actual native "+(hosted?"hosted":"widget")+" traversal blocked="+expectBlocked);
 }
 public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
 static void run(String[] args)throws Exception{
  Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field unsafeField=unsafeType.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);Object unsafe=unsafeField.get(null);
  ViewportMouse.client=(Client)unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,Client.class);
  ViewportMouse.client.callbacks=(Callbacks)Proxy.newProxyInstance(Callbacks.class.getClassLoader(),new Class<?>[]{Callbacks.class},(p,m,a)->null);
  Client.widgetClickMasks=new NodeHashTable(8);ViewportMouse.Widget_loadedInterfaces=new boolean[200];ViewportMouse.Widget_loadedInterfaces[162]=true;
  UserComparator5.Widget_interfaceComponents=new Widget[200][];UserComparator5.Widget_interfaceComponents[162]=new Widget[0];
  W root=new W(0,-1,0,100,100,400,200),body=new W(37,root.id,0,100,123,400,177),button=new W(2,root.id,5,100,100,40,20);
  button.actions=new String[]{"Public"};W text=new W(100,body.id,4,110,130,300,14);text.text="Alice: hello";text.color=0x123456;text.transparencyTop=73;
  W input=new W(57,body.id,4,110,270,370,20);W scroll=new W(58,body.id,0,450,125,20,140);scroll.isIf3=false;scroll.scrollHeight=300;
  Widget[] widgets={root,body,button,text,input,scroll};List<Rectangle> controls=MobileChat.controls(widgets,BOX);
  check(MobileChat.CLICK_THROUGH_VARBIT==2570,"Existing native click-through varbit");
  for(boolean through:new boolean[]{false,true}){
   state(true,through,controls);int[] regions=map(true,through,controls,null,false);
   check(TouchRegions.at(regions,765,503,300,210)==(through?0:1),"Visible background honors native setting");
   List<String> events=gesture(regions,300,210,true);check(through?events.equals(Arrays.asList("camera")):events.contains("drag0")&&!events.contains("camera"),"Camera versus interface drag follows chat setting");
   check(!events.contains("tap1"),"Camera/drag never leaks walking tap");
   for(int[] point:new int[][]{{110,110},{120,135},{120,280},{475,150}}){check(TouchRegions.at(regions,765,503,point[0],point[1])==1,"Chat button/text/input/scrollbar consumes");check(!gesture(regions,point[0],point[1],true).contains("camera"),"Actual chat control owns camera gesture");}
   check(MobileLayout.blocksNativeInput(body,300,210)==!through,"Native background policy matches Android");
  }
  state(true,true,controls);check(!MobileChat.consumes(true,true,BOX,controls,350,135),"Blank remainder of text row passes through");
  check(text.color==0x123456&&text.transparencyTop==73,"Chat color/opacity untouched");
  body.isHidden=true;List<Rectangle> minimized=MobileChat.controls(widgets,BOX);
  for(boolean through:new boolean[]{false,true}){
   state(false,through,minimized);int[] regions=map(false,through,minimized,null,false);
   check(TouchRegions.at(regions,765,503,300,210)==0,"Native minimized body passes with either setting");check(gesture(regions,300,210,true).equals(Arrays.asList("camera")),"Minimized body camera passes");
   check(TouchRegions.at(regions,765,503,110,110)==1,"Still-visible channel button consumes");
   state(false,through,Collections.emptyList());regions=map(false,through,Collections.emptyList(),null,false);check(gesture(regions,300,210,false).equals(Arrays.asList("tap1")),"Custom closed chat permits normal tap");check(gesture(regions,300,210,true).equals(Arrays.asList("camera")),"Custom closed chat permits camera");
  }
  int[] modal=map(false,true,Collections.emptyList(),new Rectangle(200,180,200,100),false);check(TouchRegions.at(modal,765,503,300,210)==1&&!gesture(modal,300,210,true).contains("camera"),"Overlapping modal consumes despite closed chat");
  check(TouchRegions.at(map(true,true,controls,null,true),765,503,300,210)==2,"Open context menu consumes");
  check(TouchRegions.at(map(true,true,controls,null,false),800,503,300,210)==1,"Stale geometry fails closed");
  body.isHidden=false;W host=new W(29,-1,0,100,100,400,200);host.id=(161<<16)|29;host.noClickThrough=true;
  state(true,true,controls);nativeTraversal(host,false,300,210,false);nativeTraversal(host,false,120,135,true);
  state(true,false,controls);nativeTraversal(host,false,300,210,true);
  state(false,false,Collections.emptyList());host.isHidden=true;nativeTraversal(host,false,300,210,false);host.isHidden=false;nativeTraversal(host,false,300,210,false);
  host.noClickThrough=false;state(true,true,controls);nativeTraversal(host,true,300,210,false);state(true,false,controls);nativeTraversal(host,true,300,210,true);
  // Native/classic host IDs receive the same policy; unrelated modal widgets never change.
  host.id=(548<<16)|23;field("chatHostId",host.id);Client.rootInterface=548;state(false,false,Collections.emptyList());field("chatHostId",host.id);Client.rootInterface=548;nativeTraversal(host,true,300,210,false);
  W other=new W(12,-1,0,200,180,200,100);other.id=(999<<16)|12;check(MobileLayout.blocksNativeInput(other,300,210),"Unrelated modal native blocker preserved");
  System.out.println("PASS "+checks+" chat routing assertions: native widget+hosted traversal, Android camera/tap ownership, hidden parents, native setting, text/input/scroll/menu/modal, unchanged appearance.");
 }
}
