package io.ruin.model.combat.special.ranged;

import io.ruin.cache.ItemDef;
import io.ruin.content.content242.*;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.Special;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Projectile;

/** Scorching shackles: demon-only bind/status burn; no ordinary shot or ammunition spent. */
public final class Content242ScorchingBow implements Special {
    public boolean accept(ItemDef def,String name){return ModernGear.named(def,ModernBurn.BOW,"Scorching bow")&&ModernGear.specialAssets("scorching_bow",11133,2806,2807,2908);}
    public int getDrainAmount(){return ModernBurnRules.BOW_DRAIN;}
    public boolean handle(Player player,Entity target,AttackStyle style,AttackType type,int maximum){
        ItemDef weapon=player.getEquipment().getDef(Equipment.SLOT_WEAPON);
        if(!accept(weapon,weapon==null?null:weapon.name)||style!=AttackStyle.RANGED)return false;
        if(!ModernBurn.demon(target)){player.sendMessage("Scorching shackles won't work against a non-demon enemy.");return false;}
        player.animate(ModernGear.animation(11133));player.graphics(ModernGear.graphic(2806));
        int end=new Projectile(ModernGear.graphic(2807),40,36,46,5,5,255,80).send(player,target);
        target.graphics(ModernGear.graphic(2908),0,end);
        if(!target.hasFreezeImmunity())target.freezeTicks(ModernBurnRules.BOW_BIND_TICKS,player);
        if(ModernBurn.apply(player,target,4))ModernBurn.immediate(player,target);
        return true;
    }
}
