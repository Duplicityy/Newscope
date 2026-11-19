package io.ruin.model.activities.raids.toa.port;

/** Pinned donor quotient with explicit bounds and fractional percentage (no <=0 chance bug). */
public final class ToaRewardMath {
    private ToaRewardMath(){}
    public static double uniqueProbability(int points,int raidLevel,double modifier){
        if(points<1500||!Double.isFinite(modifier)||modifier<=0)return 0;
        int level=Math.max(0,Math.min(550,raidLevel));
        double quotient=10500-20*(Math.min(400,level)+Math.max(0,level-400)/3.0);
        double percent=Math.max(0,Math.min(64000,points))/quotient;
        return Math.max(0,Math.min(.55,percent*Math.min(100,modifier)/100));
    }
    public static double quantityFactor(int raidLevel){int level=Math.max(0,Math.min(550,raidLevel));return level<300?1:1.15+.01*((level-300)/5.0);}
}
