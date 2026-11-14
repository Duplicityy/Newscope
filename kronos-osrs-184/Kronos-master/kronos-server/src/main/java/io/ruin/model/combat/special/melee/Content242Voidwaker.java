package io.ruin.model.combat.special.melee;

import io.ruin.cache.ItemDef;
import io.ruin.content.content242.ModernGear;
import io.ruin.content.content242.ModernGearRules;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.Special;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;

/** Disrupt: native magic damage/prayer handling, using the supplied melee maximum. */
public final class Content242Voidwaker implements Special {
    public boolean accept(ItemDef def,String name){return ModernGear.named(def,ModernGear.VOIDWAKER,"Voidwaker")&&ModernGear.specialAssets("voidwaker",11275,2834,2363);}
    public boolean handle(Player player,Entity target,AttackStyle style,AttackType type,int maximum){
        ItemDef weapon=player.getEquipment().getDef(Equipment.SLOT_WEAPON);
        if(weapon==null||!accept(weapon,weapon.name)||style==null||!style.isMelee())return false;
        player.animate(ModernGear.animation(11275));player.graphics(ModernGear.graphic(2834));
        target.hit(ModernGear.voidwakerHit(player,style,type,maximum,weapon)
            .postDamage(victim->victim.graphics(ModernGear.graphic(2363))));
        return true;
    }
    public int getDrainAmount(){return 50;}
}
