package io.ruin.model.activities.raids.toa.port;

/** Near Reality f577de7 Zebak/TOANPC level-zero core rules, separated for deterministic verification. */
public final class ZebakRules {
    private ZebakRules() {}
    public static int attackSpeed(int pathLevel,boolean enraged) {
        if(pathLevel<0 || pathLevel>6)throw new IllegalArgumentException("Path level outside source bounds");
        int speed=7-Math.min(2,pathLevel/2);
        return enraged?Math.max(2,speed-3):speed;
    }
    public static int specialThreshold(int index) {
        if(index<0 || index>3)throw new IllegalArgumentException("Only four source special thresholds");
        return 85-index*15;
    }
    public static boolean behindBoulder(int x,int y,int bx,int by) { return y==by && x>=bx && x<=bx+3; }
    public static boolean waveGap(int x,int gap,int holes) {
        if(x<4)return false;
        for(int i=0;i<holes;i++)if(x-4==gap+(gap<6?i:-i))return true;
        return false;
    }
}
