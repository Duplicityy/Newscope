package local.kronos.mobile;
/** Edit gestures never emit game mouse events; a second finger cancels the drag. */
public final class LayoutDrag {
 public interface Sink {void move(int id,int x,int y);}
 private final Sink sink;
 private int pointer=-1,id=-1;
 private float offsetX,offsetY;
 public LayoutDrag(Sink sink){this.sink=sink;}
 public int selected(){return id;}
 public int down(int pointer,float x,float y,int[] regions){
  this.pointer=pointer;id=-1;int area=Integer.MAX_VALUE;
  if(regions!=null)for(int i=8;i+4<regions.length;i+=5){
   int kind=regions[i+4],w=regions[i+2],h=regions[i+3];
   if(kind>=100&&kind<200&&x>=regions[i]&&y>=regions[i+1]&&x<regions[i]+w&&y<regions[i+1]+h&&w*h<area){
    id=kind-100;area=w*h;offsetX=x-regions[i];offsetY=y-regions[i+1];
   }
  }return id;
 }
 public void move(int pointer,float x,float y){if(pointer==this.pointer&&id>=0)sink.move(id,Math.round(x-offsetX),Math.round(y-offsetY));}
 public boolean place(int control,float x,float y,int[] regions){
  if(regions==null)return false;
  for(int i=8;i+4<regions.length;i+=5){
   if(regions[i+4]==100+control){
    id=control;
    cancel();
    sink.move(control,Math.round(x-regions[i+2]/2f),Math.round(y-regions[i+3]/2f));
    return true;
   }
  }
  return false;
 }
 public void cancel(){pointer=-1;}
}
