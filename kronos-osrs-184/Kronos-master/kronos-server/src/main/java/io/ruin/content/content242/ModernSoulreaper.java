package io.ruin.content.content242;

import com.google.gson.annotations.Expose;
import io.ruin.api.utils.Random;
import io.ruin.cache.ItemDef;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.melee.Content242Soulreaper;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;

/** July2026 Soulreaper rules. No modern varp is sent to the184 client. */
public final class ModernSoulreaper {
    public static final int AXE=28338,ORNAMENT=33335;
    private static boolean initialized;
    public static final class State {
        @Expose public int count;
        @Expose public int decay;
        public void gain(){count=Math.min(5,Math.max(0,count)+1);decay=50;}
        public void pulse(){count=Math.max(0,Math.min(5,count));if(count==0){decay=0;return;}if(--decay<=0){count--;decay=count==0?0:50;}}
        public int consume(){int n=Math.max(0,Math.min(5,count));count=decay=0;return n;}
    }
    private ModernSoulreaper(){}
    public static boolean isAxe(int id){return ModernGear.feature("soulreaper")&&(ModernGear.named(ItemDef.get(id),AXE,"Soulreaper axe")||ModernGear.named(ItemDef.get(id),ORNAMENT,"Soulreaper axe (o)"));}
    public static int stacks(Player p){return p.content242Soulreaper==null?0:Math.max(0,Math.min(5,p.content242Soulreaper.count));}
    public static void pulse(Player p){if(p.content242Soulreaper==null)return;if(p.getHp()<=0)p.content242Soulreaper=null;else p.content242Soulreaper.pulse();}
    public static double extraStrength(Entity entity){
        Player p=entity.player;
        if(p==null||!isAxe(p.getEquipment().getId(Equipment.SLOT_WEAPON))||p.getCombat().specialActive() instanceof Content242Soulreaper)return 0;
        return stacks(p)*.06;
    }
    public static void initialize(){
        if(initialized)return;initialized=true;
        if(!ModernGear.feature("soulreaper"))return;
        for(int source:new int[]{AXE,ORNAMENT}){
            ItemDef def=ItemDef.get(ModernCharges.item(source));if(def==null||!isAxe(def.id))continue;
            def.addPostTargetDefendListener((p,item,hit,target)->{
                if(hit instanceof BeheadHit||hit.attackWeapon==null||!isAxe(hit.attackWeapon.id)||hit.attackStyle==null||!hit.attackStyle.isMelee()||hit.isNullified())return;
                if(p.content242Soulreaper==null)p.content242Soulreaper=new State();p.content242Soulreaper.gain();
            });
        }
    }
    public static final class BeheadHit extends Hit {public BeheadHit(Player p,AttackType type){super(p,AttackStyle.SLASH,type);}}
    public static Hit special(Player p,AttackType type,int maximum,ItemDef weapon){
        int count=stacks(p);Hit hit=new BeheadHit(p,type).randDamage(maximum).boostAttack(.12*count).boostDamage(.06*count).setAttackWeapon(weapon);
        hit.postDefend(target->{if(!hit.isBlocked()&&!hit.isNullified())hit.damage=Random.get((int)Math.floor(hit.maxDamage*.06*count),hit.maxDamage);});
        return hit;
    }
}
