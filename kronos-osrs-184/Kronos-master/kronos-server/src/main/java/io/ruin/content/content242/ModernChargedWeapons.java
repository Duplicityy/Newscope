package io.ruin.content.content242;

import io.ruin.cache.ItemDef;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.handlers.EquipmentStats;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Projectile;
import io.ruin.model.stat.StatType;

/** New mapped powered/ranged weapons use native hit listeners, XP, projectiles and charge persistence. */
public final class ModernChargedWeapons {
    public static final int SHADOW=27275,SHADOW_EMPTY=27277,BOWFA=25865,BOWFA_EMPTY=25862,BOWFA_CORRUPT=25867;
    private static boolean initialized;
    private ModernChargedWeapons(){}
    private static boolean mapped(int source,int id){return Content242Registry.current().matches("items",source,id);}
    private static boolean boundName(int source,int id,String name){ItemDef def=ItemDef.get(id);return mapped(source,id)&&def!=null&&id<=65023&&name.equalsIgnoreCase(def.name);}
    private static boolean shadow(int id){return ModernGear.feature("shadow")&&(boundName(SHADOW,id,"Tumeken's shadow")||boundName(SHADOW_EMPTY,id,"Tumeken's shadow (uncharged)"));}
    public static boolean bowfa(int id){return ModernGear.feature("bowfa")&&(boundName(BOWFA,id,"Bow of Faerdhinen")||boundName(BOWFA_EMPTY,id,"Bow of Faerdhinen (inactive)")||boundName(BOWFA_CORRUPT,id,"Bow of Faerdhinen (c)"));}
    public static synchronized void initialize(){
        if(initialized)return;initialized=true;
        if(ModernGear.feature("shadow")&&ModernCharges.definitions(SHADOW,SHADOW_EMPTY)){
            copyType(SHADOW,11907,8,5);copyType(SHADOW_EMPTY,11907,8,5);ModernCharges.initializeShadow();
        }
        if(ModernGear.feature("bowfa")&&ModernCharges.definitions(BOWFA,BOWFA_EMPTY)){
            for(int source:new int[]{BOWFA,BOWFA_EMPTY,BOWFA_CORRUPT}){
                copyType(source,4212,10,5);
                ItemDef def=ItemDef.get(ModernCharges.item(source));if(def!=null&&bowfa(def.id))def.rangedWeapon=RangedWeapon.CRYSTAL_BOW; // suppress equipped arrow strength; actual shot is mapped below
            }
            ModernCharges.initializeCrystalPair(BOWFA,BOWFA_EMPTY,"bowfa",10000);
        }
        ModernCrystalArmour.initialize();
    }
    /** Copy the established184 UI type; never mutate shared native weapon metadata. */
    private static void copyType(int source,int nativePrototype,int range,int ticks){
        ItemDef target=ItemDef.get(ModernCharges.item(source)),prototype=ItemDef.get(nativePrototype);
        if(target==null||prototype==null||prototype.weaponType==null)return;
        WeaponType p=prototype.weaponType,t=new WeaponType();t.config=p.config;t.maxDistance=range;t.attackTicks=ticks;
        t.attackAnimation=p.attackAnimation;t.defendAnimation=p.defendAnimation;t.equipSound=p.equipSound;t.attackSound=p.attackSound;
        t.attackSets=p.attackSets;t.renderAnimations=p.renderAnimations;target.weaponType=t;
    }
    /** Returns the original range for every unrelated weapon. */
    public static int range(Player player,int original){
        int id=player.getEquipment().getId(Equipment.SLOT_WEAPON);
        if(shadow(id))return player.getCombat().getAttackType()==AttackType.LONG_RANGED?10:8;
        if(bowfa(id))return 10;
        return original;
    }
    /** True means this mapped weapon owns the attempt, including empty/unsupported attempts. */
    public static boolean attack(Player player,Entity target){
        Item weapon=player.getEquipment().get(Equipment.SLOT_WEAPON);if(weapon==null)return false;
        boolean staff=shadow(weapon.getId()),bow=bowfa(weapon.getId());if(!staff&&!bow)return false;
        if(player.mageArena&&!staff){unavailable(player,"You can only use magic inside the arena!");return true;}
        if(player.getCombat().hasAttackDelay())return true;
        if(staff)castShadow(player,target,weapon);else shootBowfa(player,target,weapon);
        return true;
    }
    private static void unavailable(Player player,String message){player.sendMessage(message);player.getCombat().reset();}
    public static int shadowMax(int level,int magicDamage,boolean inTombs){
        return Math.max(0,(int)Math.floor((Math.max(0,level)/3+1)*(1+Math.min(100,magicDamage*(inTombs?4:3))/100d)));
    }
    private static void castShadow(Player player,Entity target,Item weapon){
        if(target.isPlayer()){unavailable(player,"Tumeken's shadow cannot be used against other players.");return;}
        if(!mapped(SHADOW,weapon.getId())||ModernCharges.get(weapon,0)<=0){unavailable(player,"Tumeken's shadow has no charges. Use soul and chaos runes to charge it.");return;}
        if(!ModernGear.specialAssets("shadow",9493,2125,2126,2127)){unavailable(player,"This staff's mapped spell assets are unavailable.");return;}
        AttackType type=player.getCombat().getAttackType();boolean inTombs=ModernGear.insideTombs(player);
        int bonus=player.getEquipment().bonuses[EquipmentStats.MAGIC_ATTACK],multiplier=inTombs?4:3;
        int maximum=shadowMax(player.getStats().get(StatType.Magic).currentLevel,player.getEquipment().bonuses[EquipmentStats.MAGIC_DAMAGE],inTombs);
        // The +64 roll base is not tripled. Native effective level/prayers remain native.
        Hit hit=new Hit(player,AttackStyle.MAGIC,type){
            @Override public double getAttackBonus(){
                double nativeRoll=super.getAttackBonus();
                if(bonus!=-64)return nativeRoll*(bonus*multiplier+64d)/(bonus+64d);
                return 0; // At -64 equipment accuracy the staff cannot produce a positive roll.
            }
        }.randDamage(maximum).setAttackWeapon(weapon.getDef());
        player.animate(ModernGear.animation(9493));player.graphics(ModernGear.graphic(2125));
        int duration=new Projectile(ModernGear.graphic(2126),62,31,56,26,10,32,40).send(player,target);
        hit.clientDelay(duration,20);target.hit(hit);
        if(!hit.isNullified()){
            target.graphics(ModernGear.graphic(2127),124,duration);
            ModernCharges.set(weapon,ModernCharges.get(weapon,0)-1);
            if(ModernCharges.get(weapon,0)==0){weapon.setId(ModernCharges.item(SHADOW_EMPTY));player.sendMessage("Your Tumeken's shadow has run out of charges.");}
        }
        player.getCombat().updateLastAttack(5);
    }
    private static void shootBowfa(Player player,Entity target,Item weapon){
        boolean corrupted=mapped(BOWFA_CORRUPT,weapon.getId());
        if(!corrupted&&(!mapped(BOWFA,weapon.getId())||ModernCharges.get(weapon,10000)<=0)){unavailable(player,"Your bow has no charges. Use crystal shards to charge it.");return;}
        int travel=corrupted?1922:1887,launch=corrupted?1923:1888;
        if(!ModernGear.specialAssets("bowfa",426,travel,launch)){unavailable(player,"This bow's mapped projectile assets are unavailable.");return;}
        AttackType type=player.getCombat().getAttackType();
        player.animate(ModernGear.animation(426));player.graphics(ModernGear.graphic(launch),96,0);
        int duration=Projectile.arrow(ModernGear.graphic(travel))[0].send(player,target);
        Hit hit=new Hit(player,AttackStyle.RANGED,type).randDamage(CombatUtils.getMaxDamage(player,AttackStyle.RANGED,type)).setAttackWeapon(weapon.getDef()).clientDelay(duration);
        target.hit(hit);
        if(!corrupted&&!hit.isNullified()){
            ModernCharges.set(weapon,ModernCharges.get(weapon,10000)-1);
            if(ModernCharges.get(weapon,0)==0){weapon.setId(ModernCharges.item(BOWFA_EMPTY));player.sendMessage("Your bow of Faerdhinen has run out of charges.");}
        }
        player.getCombat().updateLastAttack(type==AttackType.RAPID_RANGED?4:5);
    }
}
