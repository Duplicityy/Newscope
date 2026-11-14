package io.ruin.content.content242;

/** Pure rules from pinned TSPS BurningClaws.SpecialAttack and PendingHit branch tables. */
public final class ModernBurnRules {
    public static final int CLAWS_DRAIN=35, BOW_DRAIN=25, BOW_BIND_TICKS=20;
    private ModernBurnRules(){}
    public static int minimum(int maximum,int roll){return bound(maximum,roll,3);}
    public static int maximum(int maximum,int roll){return bound(maximum,roll,7);}
    private static int bound(int maximum,int roll,int numerator){
        if(roll<0||roll>2||maximum<0||maximum>1000000)throw new IllegalArgumentException("Claw bound");
        return maximum*(numerator-roll)/4;
    }
    public static int[] split(int roll,int damage){
        if(damage<0||damage>2000000)throw new IllegalArgumentException("Claw damage");
        switch(roll){
            case 0:return new int[]{damage/4,damage/4,damage/2};
            case 1:return new int[]{Math.max(0,damage/2-1),Math.max(0,damage/2-1),2};
            case 2:return new int[]{1,1,Math.max(0,damage-2)};
            default:throw new IllegalArgumentException("Claw accuracy branch");
        }
    }
    public static int[] miss(double random){return new int[]{0,0,random<.2?0:random<.6?1:2};}
    public static boolean burns(int roll,double random){return roll>=0&&roll<3&&random<new double[]{.15,.30,.45}[roll];}
    public static double accuracy(double attack,double defence){
        attack=Math.max(0,attack);defence=Math.max(0,defence);
        return attack>defence?1-(defence+2)/(2*(attack+1)):attack/(2*(defence+1));
    }
    /** Adds the bow's30% to the imbued helm's15%, rather than multiplying those two. */
    public static double demonDamageBoost(double prior,boolean bow,boolean imbuedTask){
        return bow&&imbuedTask?(1+prior)/1.15*.30:(1+prior)*(bow?.30:.05);
    }
}
