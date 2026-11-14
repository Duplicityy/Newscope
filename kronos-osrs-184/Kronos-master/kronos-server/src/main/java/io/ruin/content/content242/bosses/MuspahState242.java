package io.ruin.content.content242.bosses;

/** Encounter state independent of visual NPC aliases;12080 can share12077's cache definition. */
final class MuspahState242 {
    enum Form {RANGED,MELEE,TELEPORT,SHIELDED,FINAL}
    Form form;final boolean beganRanged;
    boolean held,shockwaveStarted,shieldDone;int shield,specials,changes,switchDamage,switchHits,skipRestore;
    MuspahState242(boolean ranged){beganRanged=ranged;form=ranged?Form.RANGED:Form.MELEE;}
    boolean beforeShield(){return form==Form.RANGED||form==Form.MELEE;}
    boolean switchDue(){return beforeShield()&&switchDamage>=(form==Form.RANGED?100:80)&&(changes==0||switchHits>=4);}
    void switched(){form=form==Form.RANGED?Form.MELEE:Form.RANGED;changes++;resetSwitch();}
    void resetSwitch(){switchDamage=switchHits=0;}
    boolean specialIsClouds(){return (specials==0)==beganRanged;}
    boolean specialDue(int hp){return beforeShield()&&specials<2&&hp<=(specials==0?637:425);}
    boolean waveDue(int hp){return beforeShield()&&!shockwaveStarted&&(hp<127||skipRestore>0);}
    static final class Damage {final int dealt,restore;Damage(int damage,int hp){dealt=damage;restore=hp;}}
    Damage damage(int hp,int requested,boolean smite,boolean sapphire){
        int amount=Math.max(0,requested);
        if(form==Form.SHIELDED){drain((smite?amount/4:0)+(sapphire?33:0));return new Damage(0,0);}
        if(amount==0)return new Damage(0,0);
        if(!shieldDone&&skipRestore>0){int restore=skipRestore;skipRestore=0;return new Damage(0,restore);}
        if(!shieldDone&&amount>=hp){skipRestore=Math.min(hp,126);return new Damage(Math.max(0,hp-1),0);}
        if(beforeShield()&&!held){switchDamage+=amount;switchHits++;}
        return new Damage(amount,0);
    }
    void raiseShield(){held=false;if(skipRestore>0){skipRestore=0;shieldDone=true;form=Form.FINAL;shield=0;}else{shield=75;form=Form.SHIELDED;}}
    int drain(int amount){if(form!=Form.SHIELDED)return 0;int value=Math.min(shield,Math.max(0,amount));shield-=value;return value;}
    void breakShield(){if(form==Form.SHIELDED&&shield==0){shieldDone=true;form=Form.FINAL;}}
    static int freezeTicks(int original){return Math.max(1,(int)Math.floor(Math.max(1,original)*.66));}
    static int corruptionDrain(int base,int iteration){return base*iteration;}
}
