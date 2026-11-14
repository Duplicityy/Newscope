package io.ruin.model.combat.special.melee;

import io.ruin.api.utils.Random;
import io.ruin.cache.ItemDef;
import io.ruin.content.content242.*;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.Special;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;

/** Burning barrage: first landing slash roll selects the native three-hit split. */
public final class Content242BurningClaws implements Special {
    public boolean accept(ItemDef def,String name){return ModernGear.named(def,ModernBurn.CLAWS,"Burning claws")&&ModernGear.specialAssets("burning_claws",11140,2814);}
    public int getDrainAmount(){return ModernBurnRules.CLAWS_DRAIN;}
    public boolean handle(Player player,Entity target,AttackStyle style,AttackType type,int maximum){
        ItemDef weapon=player.getEquipment().getDef(Equipment.SLOT_WEAPON);
        if(!accept(weapon,weapon==null?null:weapon.name)||style==null||!style.isMelee())return false;
        player.animate(ModernGear.animation(11140));player.graphics(ModernGear.graphic(2814));
        final int[] branch={-1};final int[][] split={new int[3]};
        Hit first=new Hit(player,style,type){
            @Override public double getDefenceBonus(Entity victim){return CombatUtils.getDefenceBonus(victim,AttackStyle.SLASH);}
        }.randDamage(Math.max(0,maximum)).ignoreDefence().setAttackWeapon(weapon);
        first.postDefend(victim->{
            if(first.isBlocked()||first.isNullified())return;
            double attack=first.getAttackBonus()*(1+first.getAttackBoost());
            if(victim.player!=null&&Hit.PVP_MELEE_ACCURACY_MODIFIER!=0)attack*=Hit.PVP_MELEE_ACCURACY_MODIFIER;
            double defence=first.getDefenceBonus(victim)*(1+first.getDefenceBoost());
            double chance=ModernBurnRules.accuracy(attack,defence);
            for(int roll=0;roll<3;roll++)if(Random.get()<chance){branch[0]=roll;break;}
            if(branch[0]<0)split[0]=ModernBurnRules.miss(Random.get());
            else{
                int max=Math.max(0,first.maxDamage);
                split[0]=ModernBurnRules.split(branch[0],Random.get(ModernBurnRules.minimum(max,branch[0]),ModernBurnRules.maximum(max,branch[0])));
            }
            first.damage=split[0][0];
        });
        first.afterPostDamage(victim->{if(ModernBurnRules.burns(branch[0],Random.get()))ModernBurn.apply(player,victim,10);});
        target.hit(first);
        if(first.isNullified())return true;
        for(int index=1;index<3;index++){
            Hit follow=new Hit(player,style,type).fixedDamage(split[0][index]).ignoreDefence().setAttackWeapon(weapon);
            follow.afterPostDamage(victim->{if(ModernBurnRules.burns(branch[0],Random.get()))ModernBurn.apply(player,victim,10);});
            target.hit(follow);
        }
        return true;
    }
}
