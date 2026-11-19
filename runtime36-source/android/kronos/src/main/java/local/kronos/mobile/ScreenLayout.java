package local.kronos.mobile;

/** Aspect-correct logical canvas sizing, independent of density and phone model. */
public final class ScreenLayout {
    private ScreenLayout() {}
    public static int[] canvas(int width,int height) {return canvas(width,height,1f);}
    public static int[] canvas(int width,int height,float uiScale) {
        double ratio=(double)Math.max(1,width)/Math.max(1,height);
        int w=Math.round(765/Math.max(.8f,Math.min(1f,uiScale))),h=Math.round(503/Math.max(.8f,Math.min(1f,uiScale)));
        if(ratio>=765.0/503)w=(int)Math.round(h*ratio);
        else h=(int)Math.round(w/ratio);
        // Keep software rendering bounded. Extreme ratios use a fitted canvas.
        return new int[]{Math.min(2048,w),Math.min(1536,h)};
    }
    public static int[] fit(int width,int height,int canvasWidth,int canvasHeight) {
        double scale=Math.min((double)width/canvasWidth,(double)height/canvasHeight);
        return new int[]{Math.max(1,(int)Math.round(canvasWidth*scale)),Math.max(1,(int)Math.round(canvasHeight*scale))};
    }
}
