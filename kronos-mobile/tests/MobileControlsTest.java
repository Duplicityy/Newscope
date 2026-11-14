import local.kronos.mobile.*;
import local.kronos.ui.MobileGeometry;
import java.util.*;
public final class MobileControlsTest {
 static final List<String> events=new ArrayList<>();
 static int region=0;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message+" "+events);}
 static TouchControls control(){
  events.clear();region=0;
  TouchControls t=new TouchControls(new TouchControls.Sink(){
   public int region(float x,float y){return region;}
   public void tap(int b,float x,float y){events.add("tap:"+b);}
   public void drag(int p,float x,float y){events.add("drag:"+p);}
   public void wheel(float x,float y,int s){events.add("scroll");}
   public void camera(float x,float y){events.add("camera");}
   public void zoom(float s){if(Math.abs(s)>.0001)events.add(s>0?"in":"out");}
  });t.configure(1100,503,8);t.settings(180,650,2);return t;
 }
 static boolean has(String value){return events.contains(value);}
 static void noWorldAction(){check(!has("tap:1")&&!has("drag:0"),"camera/pinch leaked a world action");}
 public static void main(String[] args){
  int[][] displays={{1920,1080},{2340,1080},{3120,1440},{2400,1080},{2640,1080},{2208,1768},{1280,800}};
  for(int[] d:displays)for(float scale:new float[]{1,.9f,.8f}){
   int[] c=ScreenLayout.canvas(d[0],d[1],scale),fit=ScreenLayout.fit(d[0],d[1],c[0],c[1]);
   check(Math.abs(fit[0]-d[0])<=3&&Math.abs(fit[1]-d[1])<=3,"aspect ratio");
   for(boolean expanded:new boolean[]{true,false}){
    MobileGeometry g=new MobileGeometry(c[0],c[1],expanded);
    for(MobileGeometry.Box b:g.tabs)if(b!=null)check(b.x>=0&&b.y>=0&&b.x+b.w<=c[0]&&b.y+b.h<=c[1],"tab outside canvas");
    check(g.panel.x>g.hotkeys[0].x+g.hotkeys[0].w,"panel overlaps hotkeys");
   }
  }
  TouchControls t=control();t.down(7,200,220,0);t.up(7,200,220,100);check(has("tap:1"),"ordinary tap");
  t=control();t.down(7,200,220,0);t.tick(190);t.position(7,220,240);t.moved(210);t.up(7,220,240,230);check(has("camera"),"hold drag");noWorldAction();
  t=control();t.down(7,200,220,0);t.position(7,220,240);t.moved(60);t.up(7,220,240,100);check(events.isEmpty(),"early swipe must not walk");
  t=control();t.down(7,200,220,0);t.tick(651);t.position(7,220,240);t.moved(700);t.up(7,220,240,750);check(events.equals(Arrays.asList("tap:3")),"context owns movement");
  t=control();t.down(4,200,200,0);t.down(9,300,200,100);t.tick(800);t.up(4,200,200,900);t.up(9,300,200,950);check(events.isEmpty(),"stationary two fingers must not click/hold");
  t=control();t.down(4,200,200,0);t.down(9,300,200,100);t.position(4,180,200);t.position(9,320,200);t.moved(150);check(has("in")&&!has("camera"),"spread zoom only");
  t.position(4,210,200);t.position(9,290,200);t.moved(180);check(has("out"),"pinch zoom out");
  t.up(9,290,200,200);t.position(4,260,250);t.moved(900);t.up(4,260,250,950);noWorldAction();check(!has("camera"),"pinch to one must not jump camera");
  t=control();t.down(4,200,200,0);t.position(4,220,220);t.moved(200);t.down(9,320,220,220);t.position(9,350,220);t.moved(240);t.up(4,220,220,260);t.up(9,350,220,280);check(has("camera")&&has("in"),"camera transitions to pinch");noWorldAction();
  t=control();region=1;t.down(4,900,300,0);t.position(4,930,310);t.moved(250);t.up(4,930,310,300);check(has("drag:0")&&has("drag:1")&&!has("camera"),"interface drag ownership");
  t=control();region=1;t.down(4,900,300,0);t.tick(700);t.up(4,900,300,750);check(events.equals(Arrays.asList("tap:3")),"item context hold");
  t=control();region=1;t.down(4,900,300,0);region=0;t.down(9,300,200,50);t.position(9,350,200);t.moved(80);t.up(4,900,300,100);t.up(9,350,200,120);check(events.isEmpty(),"interface-start never pinches");
  t=control();t.down(4,200,200,0);t.tick(190);t.position(4,220,220);t.moved(210);t.cancel();noWorldAction();
  t=control();region=2;t.down(4,200,200,0);t.up(4,200,200,50);check(has("tap:1"),"menu selection tap");
  System.out.println("PASS: 21 display/scale combinations; tab bounds; taps, hold-drag, context ownership, pinch, transitions, cancellation and interface routing.");
 }
}
