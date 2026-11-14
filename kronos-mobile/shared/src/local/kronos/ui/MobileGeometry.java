package local.kronos.ui;
import java.util.*;
/** One source of truth for drawing, hit testing and the layout editor. */
public final class MobileGeometry {
 public static final int[] PRIMARY={3,4,5,6,0,2},SECONDARY={1,12,13,8,9,7,11};
 public static final int PANEL=40;
 public static final class Box {
  public final int x,y,w,h;
  public Box(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;}
  public boolean contains(float px,float py){return px>=x&&py>=y&&px<x+w&&py<y+h;}
 }
 /** Positions are fractions of available travel, so right/bottom anchors survive resizing. */
 public static final class Positions {
  private final Map<Integer,int[]> values=new HashMap<>(),styles=new HashMap<>();
  public void clear(){values.clear();styles.clear();}
  public void copyFrom(Positions other){values.clear();for(Map.Entry<Integer,int[]> e:other.values.entrySet())values.put(e.getKey(),e.getValue().clone());styles.clear();for(Map.Entry<Integer,int[]> e:other.styles.entrySet())styles.put(e.getKey(),e.getValue().clone());}
  public static boolean button(int id){return id>=0&&id<=18||id>=22&&id<=26;}
  public int size(int id){return styles.containsKey(id)?styles.get(id)[0]:100;}
  public int opacity(int id){return styles.containsKey(id)?styles.get(id)[1]:100;}
  public void style(int id,int size,int opacity){if(button(id))styles.put(id,new int[]{Math.max(60,Math.min(180,size)),Math.max(10,Math.min(100,opacity))});}
  public Box sized(int id,Box box){return !button(id)?box:new Box(box.x,box.y,Math.max(1,box.w*size(id)/100),Math.max(1,box.h*size(id)/100));}
  public void move(int id,int x,int y,Box box,int width,int height){
   if(box==null)return;
   values.put(id,new int[]{fraction(x,width-box.w),fraction(y,height-box.h)});
  }
  private int fraction(int value,int space){return space<=0?0:Math.max(0,Math.min(10000,(int)Math.round(value*10000.0/space)));}
  public Box place(int id,Box fallback,int width,int height){
   if(fallback==null)return null;int[] p=values.get(id);if(p==null)return new Box(Math.min(fallback.x,Math.max(0,width-fallback.w)),Math.min(fallback.y,Math.max(0,height-fallback.h)),fallback.w,fallback.h);
   return new Box((int)Math.round(Math.max(0,width-fallback.w)*p[0]/10000.0),(int)Math.round(Math.max(0,height-fallback.h)*p[1]/10000.0),fallback.w,fallback.h);
  }
  public void load(Properties p){clear();for(String key:p.stringPropertyNames())if(key.startsWith("style."))try{String[] pair=p.getProperty(key).split(",");style(Integer.parseInt(key.substring(6)),Integer.parseInt(pair[0]),Integer.parseInt(pair[1]));}catch(RuntimeException ignored){}for(String key:p.stringPropertyNames())if(key.startsWith("position."))try{
   int id=Integer.parseInt(key.substring(9));String[] pair=p.getProperty(key).split(",");
   int x=Integer.parseInt(pair[0]),y=Integer.parseInt(pair[1]);
   if(id>=0&&id<54&&x>=0&&x<=10000&&y>=0&&y<=10000)values.put(id,new int[]{x,y});
  }catch(RuntimeException ignored){}}
  public void store(Properties p){for(Map.Entry<Integer,int[]> e:styles.entrySet())p.setProperty("style."+e.getKey(),e.getValue()[0]+","+e.getValue()[1]);for(Map.Entry<Integer,int[]> e:values.entrySet())p.setProperty("position."+e.getKey(),e.getValue()[0]+","+e.getValue()[1]);}
 }
 public final int width,height,stone=38,gap=2,margin=5;
 public final Box panel,inventory,minimap,chat,collapse,menu,chatToggle,keyboard,logout;
 public final Box[] tabs=new Box[14],hotkeys=new Box[5];
 public final Map<Integer,Box> editable=new LinkedHashMap<>();
 private final Positions positions;
 private Box at(int id,Box box){Box result=positions.place(id,positions.sized(id,box),width,height);if(result!=null)editable.put(id,result);return result;}
 public MobileGeometry(int width,int height,boolean expanded){this(width,height,expanded,3,new Positions());}
 public MobileGeometry(int width,int height,boolean expanded,int selected,Positions positions){
  this.width=width;this.height=height;this.positions=positions;
  int edge=width-margin,mainX=edge-(expanded?2:1)*(stone+gap)+gap,top=height-margin-7*(stone+gap)+gap;
  for(int row=0;row<PRIMARY.length;row++)tabs[PRIMARY[row]]=at(PRIMARY[row],new Box(mainX,top+row*(stone+gap),stone,stone));
  if(expanded)for(int row=0;row<SECONDARY.length;row++)tabs[SECONDARY[row]]=at(SECONDARY[row],new Box(mainX+stone+gap,top+row*(stone+gap),stone,stone));
  for(int i=0;i<5;i++)hotkeys[i]=at(14+i,new Box(margin,height-margin-(5-i)*(stone+gap),stone+8,stone));
  collapse=at(22,new Box(mainX,top+6*(stone+gap),stone,stone));
  menu=at(23,new Box(margin,5,stone+8,34));
  chatToggle=at(24,new Box(margin,41,stone+8,34));
  keyboard=at(25,new Box(margin,77,stone+8,34));
  logout=at(26,new Box(width-30,174,25,25));
  minimap=at(20,new Box(width-211,0,211,173));
  chat=at(21,new Box(margin+stone+12,5,Math.min(519,width-211-(margin+stone+12)-8),165));
  panel=at(PANEL+Math.floorMod(selected,14),new Box(mainX-gap-200,height-margin-281,200,281));
  inventory=new Box(panel.x+5,panel.y+10,190,261);
 }
}