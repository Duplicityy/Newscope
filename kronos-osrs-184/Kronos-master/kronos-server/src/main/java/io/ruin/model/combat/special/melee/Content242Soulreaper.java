package io.ruin.model.combat.special.melee;
import io.ruin.cache.ItemDef;
import io.ruin.content.content242.*;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.Special;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;
public final class Content242Soulreaper implements Special {
    private int graphic(ItemDef def){return Content242Registry.current().matches("items",ModernSoulreaper.ORNAMENT,def.id)?4008:2430;}
    public boolean accept(ItemDef def,String name){return def!=null&&ModernSoulreaper.isAxe(def.id)&&ModernGear.specialAssets("soulreaper",10173,graphic(def));}
    public boolean handle(Player p,Entity target,AttackStyle style,AttackType type,int maximum){
        ItemDef weapon=p.getEquipment().getDef(Equipment.SLOT_WEAPON);if(!accept(weapon,weapon==null?"":weapon.name))return false;
        p.animate(ModernGear.animation(10173));p.graphics(ModernGear.graphic(graphic(weapon)));
        Hit hit=ModernSoulreaper.special(p,type,maximum,weapon);target.hit(hit);
        if(!hit.isNullified()&&p.content242Soulreaper!=null)p.content242Soulreaper.consume();return true;
    }
    public int getDrainAmount(){return 0;}
}
