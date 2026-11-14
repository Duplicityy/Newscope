package io.ruin.content.content242;

import java.util.function.IntUnaryOperator;

/** Revision242 gear rules; independent of engine/client state for deterministic verification. */
public final class ModernGearRules {
    private ModernGearRules(){}
    public static int fangMinimum(int maximum){return Math.max(0,maximum)*3/20;}
    public static int fangMaximum(int maximum,boolean special){return Math.max(0,maximum)-(special?0:fangMinimum(maximum));}
    public static int voidwakerMinimum(int maximum){return Math.max(0,maximum)/2;}
    public static int voidwakerMaximum(int maximum){return Math.max(0,maximum)*3/2;}
    public static double inquisitorBonus(boolean helm,boolean body,boolean legs){return (helm?0.005:0)+(body?0.01:0)+(legs?0.01:0);}
    /** Inclusive integer rolls; outside ToA both attacks face the SAME defence roll. */
    public static boolean fangAccurate(int attack,int defence,boolean insideTombs,IntUnaryOperator roll){
        int a=Math.max(0,attack),d=Math.max(0,defence);
        int defenceRoll=roll.applyAsInt(d);
        if(roll.applyAsInt(a)>defenceRoll)return true;
        return roll.applyAsInt(a)>(insideTombs?roll.applyAsInt(d):defenceRoll);
    }
}
