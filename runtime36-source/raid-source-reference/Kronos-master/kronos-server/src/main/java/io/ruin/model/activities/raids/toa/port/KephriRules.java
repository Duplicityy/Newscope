package io.ruin.model.activities.raids.toa.port;

/** Default-level timing from pinned NR Kephri; no invocation effects are implied. */
final class KephriRules {
    int phase,shieldTicks;
    boolean depleted(){if(phase>=4||shieldTicks>0)return false;phase++;if(phase==1||phase==2)shieldTicks=52;return true;}
    void tick(){if(shieldTicks>0)shieldTicks--;}
    boolean complete(){return phase==4;}
    static int bombBase(boolean regular,boolean magicPrayer){int base=regular?8:5;return magicPrayer?base/3:base;}
    static boolean inArena(int x,int y){return x>=3542&&x<=3560&&y>=5398&&y<=5417;}
    static java.util.List<int[]> dungPath(int x,int y,int endX,int endY){
        java.util.List<int[]> path=new java.util.ArrayList<>();
        int dx=endX-x,dy=endY-y,ax=Math.abs(dx),ay=Math.abs(dy),distance=Math.max(ax,ay);
        int ix=Integer.signum(dx),iy=Integer.signum(dy),smaller=0;boolean xMajor=ax>ay;
        float step=xMajor?(ax+1F)/(ay+1F):(ay+1F)/(ax+1F),threshold=step;
        for(int n=0;n<distance;n++){
            boolean side=false;
            if(ax==ay||(n==0&&Math.abs(ay-ax)<=1)||(smaller<(xMajor?ay:ax)&&n+1>threshold)){threshold+=step;side=true;smaller++;}
            if(xMajor){x+=ix;if(side)y+=iy;}else{if(side)x+=ix;y+=iy;}
            path.add(new int[]{x,y});
        }
        return path;
    }
}
