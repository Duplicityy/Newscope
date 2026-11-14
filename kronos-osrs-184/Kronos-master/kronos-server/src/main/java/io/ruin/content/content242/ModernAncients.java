package io.ruin.content.content242;

import io.ruin.api.utils.Random;
import io.ruin.model.combat.Hit;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.skills.magic.spells.TargetSpell;
import io.ruin.model.skills.magic.spells.ancient.*;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;

/** Native Ancient spell casts retain their runes, accuracy, XP and multi-target routing. */
public final class ModernAncients {
    private ModernAncients(){}
    public static boolean ancient(TargetSpell spell){return spell!=null&&spell.getClass().getPackage().getName().equals("io.ruin.model.skills.magic.spells.ancient");}
    public static int extraMagicDamage(Entity caster,TargetSpell spell){
        if(caster.player==null||!ModernGear.feature("virtus")||!ancient(spell))return 0;
        int[] slots={Equipment.SLOT_HAT,Equipment.SLOT_CHEST,Equipment.SLOT_LEGS},sources={26241,26243,26245};int bonus=0;
        for(int n=0;n<3;n++)if(Content242Registry.current().matches("items",sources[n],caster.player.getEquipment().getId(slots[n])))bonus+=3;
        return bonus;
    }
    public static boolean sceptre(Player p){return p!=null&&ModernGear.feature("ancient_sceptre")&&Content242Registry.current().matches("items",27624,p.getEquipment().getId(Equipment.SLOT_WEAPON));}
    public static int iceTicks(int tier){return new int[]{8,17,26,35}[tier];}
    public static int bloodHealing(int damage){return (int)Math.floor(Math.max(0,damage)*.275);}
    /** Returns true only when a mapped sceptre owns this spell's additional effect. */
    public static boolean afterHit(Hit hit,Entity target){
        if(hit.attacker==null||!sceptre(hit.attacker.player)||!ancient(hit.attackSpell))return false;
        if(hit.isBlocked()||hit.isNullified())return true;
        String name=hit.attackSpell.getClass().getSimpleName();
        int tier=name.endsWith("Rush")?0:name.endsWith("Burst")?1:name.endsWith("Blitz")?2:3;
        if(hit.attackSpell instanceof BloodSpell){if(hit.damage>0)hit.attacker.incrementHp(bloodHealing(hit.damage));return true;}
        if(hit.attackSpell instanceof SmokeSpell){
            // The184 poison engine stores damage per pulse rather than OSRS severity (severity/5, rounded up).
            if(hit.damage>0&&Random.rollDie(8))target.poison(tier<2?3:5);return true;
        }
        if(hit.attackSpell instanceof ShadowSpell){
            if(hit.damage>0){Stat attack=target.player!=null?target.player.getStats().get(StatType.Attack):target.npc.getCombat().getStat(StatType.Attack);
                double fraction=(tier<2?.10:.15)*1.1;int amount=Math.max(0,attack.currentLevel-(int)Math.floor(attack.fixedLevel*(1-fraction)));if(amount>0)attack.drain(amount);}
            return true;
        }
        if(name.startsWith("Ice")){
            if(!target.hasFreezeImmunity()){target.freezeTicks(iceTicks(tier),hit.attacker);if(target.player!=null)target.player.sendMessage("You have been frozen.");}
            return true;
        }
        return false;
    }
}
