import local.kronos.mobile.*;
import java.util.*;
public final class MobileControlsTest {
    static final List<String> events=new ArrayList<>();
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    static TouchControls controls(){
        events.clear();
        TouchControls t=new TouchControls(new TouchControls.Sink(){
            public void tap(int b,float x,float y){events.add("tap:"+b+":"+(int)x);}
            public void drag(int p,float x,float y){events.add("drag:"+p);}
            public void wheel(float x,float y,int s){events.add("wheel:"+s);}
            public void camera(int k){events.add("camera:"+k);}
        });t.configure(1100,503,10);return t;
    }
    public static void main(String[] args){
        int[][] screens={{1920,1080},{2340,1080},{3120,1440},{2400,1080},{2640,1080},{2208,1768},{1280,800}};
        for(int[] screen:screens){
            int[] c=ScreenLayout.canvas(screen[0],screen[1]),f=ScreenLayout.fit(screen[0],screen[1],c[0],c[1]);
            check(c[0]>=765&&c[1]>=503,"minimum game canvas");
            check(Math.abs((double)f[0]/f[1]-(double)c[0]/c[1])<.004,"undistorted fit");
            check(Math.abs(f[0]-screen[0])<=3&&Math.abs(f[1]-screen[1])<=3,"fills common screen shape");
        }
        TouchControls t=controls();
        t.down(11,300,150,0);t.down(7,1000,430,10);
        t.up(11,300,150,40);t.up(7,1000,430,60);
        check(events.contains("tap:1:300")&&events.contains("tap:1:1000"),"both pointer IDs tap");
        t=controls();t.down(5,200,100,0);t.down(9,350,100,10);
        t.position(5,240,100);t.position(9,390,100);t.moved(40);
        t.down(17,1000,440,50);t.up(17,1000,440,80);
        t.up(5,240,100,100);t.up(9,390,100,110);
        check(events.contains("camera:39")&&events.contains("tap:1:1000"),"third finger taps while camera moves");
        check(events.stream().filter(x->x.startsWith("tap:")).count()==1,"no camera phantom clicks");
        t=controls();t.down(3,200,100,0);t.down(8,300,100,10);
        t.position(3,160,100);t.position(8,340,100);t.moved(50);
        check(events.contains("wheel:-1"),"pinch zoom");
        t.cancel();check(events.get(events.size()-1).equals("camera:0"),"cancel releases camera");
        t=controls();t.down(4,900,300,0);t.position(4,950,330);t.moved(100);t.cancel();
        check(events.contains("drag:0")&&events.contains("drag:2")&&events.contains("drag:1"),"drag cancellation releases mouse");
        t=controls();t.down(2,700,200,0);t.tick(501);t.up(2,700,200,550);
        check(events.contains("tap:3:700")&&events.stream().filter(x->x.startsWith("tap:")).count()==1,"hold opens options once");
        System.out.println("PASS: seven screen shapes, independent pointer taps, camera plus third finger, pinch, drag cancellation, long press.");
    }
}
