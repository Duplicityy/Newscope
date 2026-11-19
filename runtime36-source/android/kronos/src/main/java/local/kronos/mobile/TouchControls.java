package local.kronos.mobile;
import java.util.LinkedHashMap;
import java.util.Map;

/** No world mouse-down is emitted until a short tap is complete. */
public final class TouchControls {
 public interface Sink {
  int region(float x,float y); // 0 world, 1 interface, 2 open context menu, 3 unknown/loading
  void tap(int button,float x,float y);
  void drag(int phase,float x,float y);
  void wheel(float x,float y,int steps);
  void camera(float dx,float dy);
  void zoom(float logarithmicScale);
 }
 private enum Mode {IDLE,WORLD,UI,DRAG,CAMERA,PINCH,CONTEXT,SUPPRESSED}
 private static final class Finger {float x,y;Finger(float x,float y){this.x=x;this.y=y;}}
 private final Map<Integer,Finger> fingers=new LinkedHashMap<>();
 private final Sink sink;
 private Mode mode=Mode.IDLE;
 private int primary=-1,secondary=-1;
 private float startX,startY,lastX,lastY,maxDistance,pinchDistance,slop=8,sensitivity=2;
 private long since,armMillis=180,contextMillis=650;
 private boolean observer;
 public TouchControls(Sink sink){this.sink=sink;}
 /** Camera-only world gestures while observing a bot. Interface actions retain normal handling. */
 public void observer(boolean enabled){if(observer!=enabled){cancel();observer=enabled;}}
 public void configure(int width,int height,float threshold){slop=Math.max(2,threshold);}
 public void settings(long arm,long context,float sensitivity){
  armMillis=Math.max(80,Math.min(400,arm));
  contextMillis=Math.max(armMillis+150,Math.min(1500,context));
  this.sensitivity=Math.max(.25f,Math.min(5,sensitivity));
 }
 public void down(int id,float x,float y,long time){
  fingers.put(id,new Finger(x,y));
  if(fingers.size()==1){
   primary=id;secondary=-1;startX=lastX=x;startY=lastY=y;maxDistance=0;since=time;
   int region=sink.region(x,y);mode=observer&&region==3?Mode.SUPPRESSED:region==0?Mode.WORLD:Mode.UI;return;
  }
  if(mode==Mode.WORLD||mode==Mode.CAMERA){
   // The second finger claims the entire gesture even before zoom movement.
   if(sink.region(x,y)!=0){mode=Mode.SUPPRESSED;return;}
   secondary=id;pinchDistance=distance();mode=Mode.PINCH;return;
  }
  if(mode==Mode.DRAG){sink.drag(1,lastX,lastY);mode=Mode.SUPPRESSED;}
  else if(mode==Mode.UI||mode==Mode.CONTEXT)mode=Mode.SUPPRESSED;
 }
 public void position(int id,float x,float y){Finger f=fingers.get(id);if(f!=null){f.x=x;f.y=y;}}
 public void moved(long time){
  Finger f=fingers.get(primary);if(f==null)return;
  if(mode==Mode.PINCH){
   float current=distance();
   if(current>slop&&pinchDistance>slop)sink.zoom((float)Math.log(current/pinchDistance));
   pinchDistance=current;return;
  }
  float x=f.x,y=f.y,dx=x-lastX,dy=y-lastY;
  maxDistance=Math.max(maxDistance,(float)Math.hypot(x-startX,y-startY));
  if(mode==Mode.WORLD&&(observer||time-since>=armMillis)&&maxDistance>slop)mode=Mode.CAMERA;
  if(mode==Mode.CAMERA){if(dx!=0||dy!=0)sink.camera(dx*sensitivity,dy*sensitivity);}
  else if(mode==Mode.UI&&maxDistance>slop){mode=Mode.DRAG;sink.drag(0,startX,startY);sink.drag(2,x,y);}
  else if(mode==Mode.DRAG)sink.drag(2,x,y);
  lastX=x;lastY=y;
 }
 public void tick(long time){
  if(fingers.size()!=1)return;
  if((mode==Mode.WORLD&&!observer||mode==Mode.UI)&&maxDistance<=slop&&time-since>=contextMillis){
   mode=Mode.CONTEXT;sink.tap(3,startX,startY);
  }
 }
 public void up(int id,float x,float y,long time){
  if(observer&&id==primary&&(mode==Mode.WORLD||mode==Mode.CAMERA)){position(id,x,y);moved(time);}
  Mode previous=mode;
  if(id==primary){
   if(previous==Mode.DRAG)sink.drag(1,x,y);
   else if((previous==Mode.WORLD&&!observer||previous==Mode.UI)&&maxDistance<=slop&&Math.hypot(x-startX,y-startY)<=slop)sink.tap(1,x,y);
  }
  fingers.remove(id);
  if(previous==Mode.PINCH&&(id==primary||id==secondary))mode=Mode.SUPPRESSED;
  else if(id==primary)mode=Mode.SUPPRESSED;
  if(fingers.isEmpty()){mode=Mode.IDLE;primary=secondary=-1;}
 }
 public void cancel(){
  if(mode==Mode.DRAG)sink.drag(1,lastX,lastY);
  fingers.clear();mode=Mode.IDLE;primary=secondary=-1;
 }
 private float distance(){
  Finger a=fingers.get(primary),b=fingers.get(secondary);
  return a==null||b==null?0:(float)Math.hypot(a.x-b.x,a.y-b.y);
 }
}
