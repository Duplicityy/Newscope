package io.ruin.model.activities.raids.tob.port;

import io.ruin.cache.ItemDef;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.Special;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.inter.handlers.EquipmentStats;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.map.Projectile;
import io.ruin.model.stat.StatType;
import java.util.*;

/** Donor powered casts and special, restricted to this run's live Verzik P1 shield. */
public final class TheatreDawnbringer implements Special {
    private static final Set<Hit> SHIELD_HITS=Collections.newSetFromMap(new WeakHashMap<Hit,Boolean>());
    static void install(){
        int id=NearRealityRaidAssets.item(22516,"Dawnbringer");if(id<0)return;
        ItemDef def=ItemDef.get(id);
        // Native cache supplies the model and Wield action, but the host has no item_info entry.
        // Fill only its missing equipment metadata; never share or mutate another staff's type.
        if(def.equipSlot<0){
            def.equipSlot=Equipment.SLOT_WEAPON;def.twoHanded=false;
            def.weightInventory=def.weightEquipment=1.5;
        }
        if(def.equipOption<0)def.equipOption=def.getOption("wield");
        if(def.equipBonuses==null)def.equipBonuses=new int[]{0,0,0,25,0,2,3,1,15,0,0,0,0,0,0,0};
        if(def.weaponType==null)def.weaponType=poweredType();
        if(def.special==null)def.special=new TheatreDawnbringer();
    }
    static WeaponType poweredType(){
        WeaponType type=new WeaponType();type.config=23;type.maxDistance=6;type.attackTicks=3;
        type.attackAnimation=1167;type.defendAnimation=424;type.equipSound=type.attackSound=-1;
        type.renderAnimations=new int[]{813,823,1205,820,821,821,1210};
        type.attackSets=new AttackSet[4];
        for(int index:new int[]{0,1,3}){
            AttackSet set=new AttackSet();set.child=index*4+3;set.style=AttackStyle.MAGIC;
            set.type=index==3?AttackType.DEFENSIVE:AttackType.ACCURATE;type.attackSets[index]=set;
        }
        return type;
    }
    static boolean shieldHit(Hit hit){return SHIELD_HITS.remove(hit);}
    public static boolean equipped(Player player){
        if(player==null||!Boolean.getBoolean("newscape.raids.enabled"))return false;
        Item weapon=player.getEquipment().get(Equipment.SLOT_WEAPON);
        int id=NearRealityRaidAssets.item(22516,"Dawnbringer");
        return id>=0&&weapon!=null&&weapon.getId()==id;
    }
    static TheatreCombat.Actor shield(Player player,Entity target){
        TheatreOfBloodRaid.Run run=TheatreOfBloodRaid.RUNS.get(player);
        if(run==null||run.closed||run.cleared||run.stage!=5||run.encounter==null||target==null||target.npc!=run.encounter.boss||!run.valid(player))return null;
        TheatreCombat.Actor actor=run.encounter.actors.get(target.npc);
        return actor!=null&&actor.behavior instanceof TheatreCombat.Verzik&&((TheatreCombat.Verzik)actor.behavior).phase==1?actor:null;
    }
    public static boolean allowAttack(Player player,Entity target,boolean message){
        if(!equipped(player)||shield(player,target)!=null)return true;
        if(message)player.sendMessage("The Dawnbringer can only be used against Verzik's first-phase shield in your Theatre run.");
        return false;
    }
    public static int range(Player player,int normalRange){
        return equipped(player)?(Config.ATTACK_SET.get(player)==3?8:6):normalRange;
    }
    static int baseDamage(int magicLevel){return Math.max(20,(int)Math.floor(magicLevel/3.0F-5));}
    public static int maximumDamage(Player player){
        int bonus=Math.min(100,player.getEquipment().bonuses[EquipmentStats.MAGIC_DAMAGE]);
        return (int)(baseDamage(player.getStats().get(StatType.Magic).currentLevel)*(1.0F+bonus/100.0F));
    }
    public static void cast(Player player,Entity target,int maximum){
        TheatreCombat.Actor actor=shield(player,target);
        if(actor==null||!equipped(player))return;
        int animation=NearRealityRaidAssets.animation(1167),gfx=NearRealityRaidAssets.graphic(1543),projectile=NearRealityRaidAssets.graphic(1544),impact=NearRealityRaidAssets.graphic(1545);
        if(animation<0||gfx<0||projectile<0||impact<0)return;
        player.animate(animation);player.graphics(gfx,92,0);
        int delay=new Projectile(projectile,23,15,51,23,25,64,5).send(player,target);
        Hit hit=new Hit(player,AttackStyle.MAGIC,player.getCombat().getAttackType()).randDamage(maximum).clientDelay(delay).setAttackWeapon(player.getEquipment().getDef(Equipment.SLOT_WEAPON));
        SHIELD_HITS.add(hit);
        hit.preDamage(victim->{if(shield(player,target)!=actor){hit.damage=0;hit.nullify();SHIELD_HITS.remove(hit);}});
        int damage=io.ruin.content.endgame.EndgameArmor.attack(target,hit);
        if(damage>0)target.graphics(impact,60,delay);
        else target.graphics(85,92,delay);
    }
    @Override public boolean accept(ItemDef def,String name){return def.id==NearRealityRaidAssets.item(22516,"Dawnbringer");}
    @Override public int getDrainAmount(){return 35;}
    @Override public boolean handle(Player player,Entity target,AttackStyle style,AttackType type,int maxDamage){
        TheatreOfBloodRaid.Run run=TheatreOfBloodRaid.RUNS.get(player);
        if(run==null||run.closed||run.cleared||run.stage!=5||run.encounter==null||target==null||target.npc!=run.encounter.boss||!run.valid(player))return false;
        TheatreCombat.Actor actor=run.encounter.actors.get(target.npc);
        if(actor==null||!(actor.behavior instanceof TheatreCombat.Verzik)||((TheatreCombat.Verzik)actor.behavior).phase!=1)return false;
        int animation=NearRealityRaidAssets.animation(1167),gfx=NearRealityRaidAssets.graphic(1543),projectile=NearRealityRaidAssets.graphic(1544);
        if(animation<0||gfx<0||projectile<0)return false;
        player.animate(animation);player.graphics(gfx,92,0);
        int delay=new Projectile(projectile,23,15,51,23,25,64,5).send(player,target);
        Hit hit=new Hit(player,AttackStyle.MAGIC).randDamage(75,150).ignoreDefence().ignorePrayer().clientDelay(delay);
        SHIELD_HITS.add(hit);
        hit.preDamage(victim->{if(run.closed||run.cleared||!run.valid(player)||actor.entity.isRemoved()||((TheatreCombat.Verzik)actor.behavior).phase!=1){hit.damage=0;hit.nullify();SHIELD_HITS.remove(hit);}});
        target.hit(hit);return true;
    }
}
