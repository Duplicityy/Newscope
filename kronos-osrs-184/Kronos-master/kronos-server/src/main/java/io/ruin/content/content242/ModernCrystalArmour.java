package io.ruin.content.content242;

import io.ruin.cache.ItemDef;
import io.ruin.model.combat.*;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;

/** Canonical charged crystal armour only; cosmetic variants remain separately gated. */
public final class ModernCrystalArmour {
    private static final int[] ACTIVE={23971,23975,23979},EMPTY={23973,23977,23981},SLOTS={0,4,7};
    private static final double[] ACCURACY={.05,.15,.10},DAMAGE={.025,.075,.05};
    private ModernCrystalArmour(){}
    public static void initialize(){
        if(!ModernGear.feature("crystal_armour"))return;
        for(int i=0;i<3;i++){
            final int piece=i;if(!ModernCharges.definitions(ACTIVE[i],EMPTY[i]))continue;
            ModernCharges.initializeCrystalPair(ACTIVE[i],EMPTY[i],"crystal_armour",2500);
            ItemDef def=ItemDef.get(ModernCharges.item(ACTIVE[i]));
            def.addPreTargetDefendListener((p,item,hit,target)->{
                if(hit.attackStyle!=AttackStyle.RANGED||first(p)!=piece||!crystalWeapon(hit.attackWeapon))return;
                double accuracy=0,damage=0;for(int n=0;n<3;n++)if(worn(p,n)){accuracy+=ACCURACY[n];damage+=DAMAGE[n];}
                hit.boostAttack((1+hit.getAttackBoost())*accuracy).boostDamage((1+hit.getDamageBoost())*damage);
            });
            def.addPostDamageListener((p,item,hit)->{
                if(hit.damage<=0||hit.type!=HitType.DAMAGE||ModernCharges.get(item,2500)<=0)return;
                ModernCharges.set(item,ModernCharges.get(item,2500)-1);
                if(ModernCharges.get(item,0)==0){item.setId(ModernCharges.item(EMPTY[piece]));p.sendMessage("Your crystal armour has run out of charges.");}
            });
        }
    }
    private static boolean crystalWeapon(ItemDef def){return def!=null&&(ModernChargedWeapons.bowfa(def.id)||def.rangedWeapon==RangedWeapon.CRYSTAL_BOW);}
    private static boolean worn(Player p,int piece){Item item=p.getEquipment().get(SLOTS[piece]);return item!=null&&item.getId()==ModernCharges.item(ACTIVE[piece])&&ModernCharges.get(item,2500)>0;}
    private static int first(Player p){for(int n=0;n<3;n++)if(worn(p,n))return n;return -1;}
}
