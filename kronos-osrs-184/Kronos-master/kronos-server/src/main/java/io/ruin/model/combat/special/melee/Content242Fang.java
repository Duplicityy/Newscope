package io.ruin.model.combat.special.melee;

import io.ruin.cache.ItemDef;
import io.ruin.content.content242.ModernGear;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.Special;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;

/** Eviscerate, registered only for explicit source-to-target item and visual bindings. */
public final class Content242Fang implements Special {
    public boolean accept(ItemDef def,String name){
        return (ModernGear.named(def,ModernGear.FANG,"Osmumten's fang")||ModernGear.named(def,ModernGear.FANG_ORNAMENT,"Osmumten's fang (or)"))
            &&ModernGear.specialAssets("fang",11222,2833);
    }
    public boolean handle(Player player,Entity target,AttackStyle style,AttackType type,int maximum){
        ItemDef weapon=player.getEquipment().getDef(Equipment.SLOT_WEAPON);
        if(weapon==null||!accept(weapon,weapon.name)||style==null||!style.isMelee())return false;
        player.animate(ModernGear.animation(11222));player.graphics(ModernGear.graphic(2833));
        Hit hit=new ModernGear.FangSpecialHit(player,AttackStyle.STAB,type).randDamage(maximum).boostAttack(0.5).setAttackWeapon(weapon);
        ModernGear.prepareFang(player,hit,true);target.hit(hit);return true;
    }
    public int getDrainAmount(){return 25;}
}
