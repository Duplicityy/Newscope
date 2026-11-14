import java.util.*;
import local.kronos.ui.MobileGeometry;
import local.kronos.mobile.LayoutDrag;
public final class LayoutCustomizationTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  MobileGeometry.Positions p=new MobileGeometry.Positions();
  MobileGeometry g=new MobileGeometry(1090,503,true,3,p);
  p.move(3,110,210,g.tabs[3],1090,503);p.style(3,150,40);
  p.move(43,60,190,g.panel,1090,503);
  MobileGeometry a=new MobileGeometry(1090,503,true,3,p);
  check(a.tabs[3].w==57&&p.opacity(3)==40,"size / opacity");
  check(Math.abs(a.panel.x-60)<=1&&a.inventory.x==a.panel.x+5,"panel hit coordinates");
  check(new MobileGeometry(1090,503,true,0,p).panel.x!=a.panel.x,"per-panel position");
  Properties props=new Properties();p.store(props);MobileGeometry.Positions restored=new MobileGeometry.Positions();restored.load(props);
  check(restored.size(3)==150&&restored.opacity(3)==40,"persist styles");
  check(new MobileGeometry(1090,503,true,3,restored).panel.x==a.panel.x,"persist position");
  MobileGeometry.Positions backup=new MobileGeometry.Positions();backup.copyFrom(p);p.clear();check(p.size(3)==100,"reset");p.copyFrom(backup);check(p.size(3)==150,"cancel restores");
  for(int[] shape:new int[][]{{765,503},{894,503},{1230,503},{1363,629},{765,574}}){
   MobileGeometry resized=new MobileGeometry(shape[0],shape[1],true,3,p);
   for(MobileGeometry.Box b:resized.editable.values())check(b.x>=0&&b.y>=0&&b.x+b.w<=shape[0]&&b.y+b.h<=shape[1],"resize bounds");
  }
  p.style(3,999,-50);check(p.size(3)==180&&p.opacity(3)==10,"style clamps");p.move(3,9999,-99,a.tabs[3],1090,503);
  a=new MobileGeometry(1090,503,true,3,p);check(a.tabs[3].x+a.tabs[3].w==1090&&a.tabs[3].y==0,"position clamps");
  List<String> events=new ArrayList<>();LayoutDrag drag=new LayoutDrag((id,x,y)->events.add(id+":"+x+":"+y));
  int[] map={1,30,0,0,0,1090,503,0, 100,100,200,281,143, 110,110,38,38,103, 150,40,0,0,203};
  check(drag.down(7,120,120,map)==3,"overlap picks button over panel");drag.move(7,170,180);
  check(events.equals(Arrays.asList("3:160:170")),"drag preserves grab offset");drag.cancel();drag.move(7,200,200);check(events.size()==1,"multi-touch cancel");
  check(drag.down(9,600,300,map)==-1,"empty world not selected");drag.move(9,700,300);check(events.size()==1,"editor never moves world");
  check(drag.place(3,400,230,map)&&drag.selected()==3,"place inventory button");
  check(drag.place(43,600,300,map)&&drag.selected()==43,"place inventory panel");
  check(!drag.place(26,600,300,map),"placement requires a measured control");
  check(events.equals(Arrays.asList("3:160:170","3:381:211","43:500:160")),"move picker centers the selected control");
  System.out.println("PASS: customized positions, independent panels, size/opacity, save/load/reset/cancel, five aspect ratios, edge clamps, editor overlap, pointer cancellation, and Move picker placement.");
 }
}
