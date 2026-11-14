package io.ruin.content.content242;

import io.ruin.Server;
import io.ruin.api.utils.Random;
import io.ruin.cache.AnimDef;
import io.ruin.cache.ItemDef;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import java.util.*;
import java.util.function.Predicate;

/** New, explicitly mapped equipment only; native hit completion and unrelated gear stay native. */
public final class ModernGear {
    public static final int FANG=26219,FANG_ORNAMENT=27246,VOIDWAKER=27690;
    public static final int INQUISITOR_HELM=24419,INQUISITOR_BODY=24420,INQUISITOR_LEGS=24421;
    private static final Set<Integer> installed=new HashSet<>();
    private static Predicate<Player> tombsContext=player->false;
    private ModernGear(){}

    /** A future native ToA encounter supplies its actual owner/instance membership. */
    public static void bindTombsContext(Predicate<Player> context){tombsContext=Objects.requireNonNull(context);}
    public static boolean insideTombs(Player player){return tombsContext.test(player);}
    public static boolean isFang(int target){Content242Registry r=Content242Registry.current();return r.matches("items",FANG,target)||r.matches("items",FANG_ORNAMENT,target);}
    public static boolean isVoidwaker(int target){return Content242Registry.current().matches("items",VOIDWAKER,target);}
    public static boolean feature(String name){return Content242Registry.current().feature(name);}
    public static int animation(int source){return Content242Registry.current().resolve("animations",source);}
    public static int graphic(int source){return Content242Registry.current().resolve("graphics",source);}
    public static boolean specialAssets(String feature,int animation,int... graphics){
        if(!feature(feature))return false;
        int mapped=animation(animation);
        if(AnimDef.LOADED==null||mapped<0||AnimDef.get(mapped)==null||AnimDef.get(mapped).frameData==null)return false;
        if(Server.fileStore==null)return false;
        for(int source:graphics){int id=graphic(source);if(id<0||Server.fileStore.get(2).getFile(13,id)==null)return false;}
        return true;
    }
    public static boolean named(ItemDef def,int source,String expected){
        return def!=null&&Content242Registry.current().matches("items",source,def.id)&&expected.equalsIgnoreCase(def.name)
            &&def.equipSlot>=0&&def.id<=65023;
    }
    public static synchronized void initialize(){
        Content242Registry r=Content242Registry.current();
        if(r.feature("fang")){
            installFang(FANG,"Osmumten's fang");installFang(FANG_ORNAMENT,"Osmumten's fang (or)");
        }
        if(r.feature("inquisitor")){
            installInquisitor(INQUISITOR_HELM,"Inquisitor's great helm");
            installInquisitor(INQUISITOR_BODY,"Inquisitor's hauberk");
            installInquisitor(INQUISITOR_LEGS,"Inquisitor's plateskirt");
        }
        ModernChargedWeapons.initialize();
        ModernSoulreaper.initialize();
        ModernBurn.initialize();
    }
    private static void installFang(int source,String name){
        int target=Content242Registry.current().resolve("items",source);ItemDef def=target<0?null:ItemDef.get(target);
        if(!named(def,source,name)||!installed.add(target))return;
        def.addPreTargetDefendListener((player,item,hit,victim)->{
            if(hit instanceof FangSpecialHit||hit.attackWeapon==null||!isFang(hit.attackWeapon.id))return;
            prepareFang(player,hit,false);
        });
    }
    private static void installInquisitor(int source,String name){
        int target=Content242Registry.current().resolve("items",source);ItemDef def=target<0?null:ItemDef.get(target);
        if(!named(def,source,name)||!installed.add(target))return;
        def.addPreTargetDefendListener((player,item,hit,victim)->{
            if(hit.attackStyle!=AttackStyle.CRUSH)return;
            // Only the first worn piece applies the aggregate bonus, avoiding multiplicative pieces.
            int first=firstInquisitor(player);if(item.getId()!=first)return;
            double bonus=inquisitorBonus(player);
            hit.boostAttack((1+hit.getAttackBoost())*bonus).boostDamage((1+hit.getDamageBoost())*bonus);
        });
    }
    private static boolean worn(Player p,int slot,int source){int mapped=Content242Registry.current().resolve("items",source);return mapped>=0&&p.getEquipment().getId(slot)==mapped;}
    private static int firstInquisitor(Player p){
        if(worn(p,Equipment.SLOT_HAT,INQUISITOR_HELM))return p.getEquipment().getId(Equipment.SLOT_HAT);
        if(worn(p,Equipment.SLOT_CHEST,INQUISITOR_BODY))return p.getEquipment().getId(Equipment.SLOT_CHEST);
        return worn(p,Equipment.SLOT_LEGS,INQUISITOR_LEGS)?p.getEquipment().getId(Equipment.SLOT_LEGS):-1;
    }
    public static double inquisitorBonus(Player p){return ModernGearRules.inquisitorBonus(worn(p,Equipment.SLOT_HAT,INQUISITOR_HELM),worn(p,Equipment.SLOT_CHEST,INQUISITOR_BODY),worn(p,Equipment.SLOT_LEGS,INQUISITOR_LEGS));}

    public static final class FangSpecialHit extends Hit {
        public FangSpecialHit(Player attacker,AttackStyle style,AttackType type){super(attacker,style,type);}
    }
    /** Roll physical melee maximum/boosts first, then expose magic damage to native protection/XP. */
    public static Hit voidwakerHit(Player attacker,AttackStyle selected,AttackType type,int maximum,ItemDef weapon){
        Hit hit=new Hit(attacker,selected,type).randDamage(maximum).ignoreDefence().setAttackWeapon(weapon);
        hit.postDefend(target->{
            hit.attackStyle=AttackStyle.MAGIC;
            if(!hit.isBlocked()&&!hit.isNullified())hit.damage=Random.get(ModernGearRules.voidwakerMinimum(hit.maxDamage),ModernGearRules.voidwakerMaximum(hit.maxDamage));
        });
        return hit;
    }
    public static void prepareFang(Player attacker,Hit hit,boolean special){
        if(!feature("fang")||hit.attackStyle==null||!hit.attackStyle.isMelee()||hit.isBlocked()||hit.isNullified()||hit.hasFixedDamage())return;
        boolean reroll=hit.attackStyle==AttackStyle.STAB&&!hit.defenceIgnored;
        if(reroll)hit.ignoreDefence();
        // Runs after native pre-hit equipment boosts and before target prayer/absorbs.
        hit.postDefend(target->{
            if(hit.isBlocked()||hit.isNullified())return;
            if(reroll){
                double attack=hit.getAttackBonus()*(1+hit.getAttackBoost());
                if(target.player!=null&&Hit.PVP_MELEE_ACCURACY_MODIFIER!=0)attack*=Hit.PVP_MELEE_ACCURACY_MODIFIER;
                double defence=hit.getDefenceBonus(target)*(1+hit.getDefenceBoost());
                if(!ModernGearRules.fangAccurate(boundedRoll(attack),boundedRoll(defence),tombsContext.test(attacker),Random::get)){hit.block();return;}
            }
            int maximum=Math.max(0,hit.maxDamage),minimum=ModernGearRules.fangMinimum(maximum);
            hit.damage=Random.get(minimum,ModernGearRules.fangMaximum(maximum,special));
        });
    }
    private static int boundedRoll(double value){return (int)Math.max(0,Math.min(Integer.MAX_VALUE-1,Math.floor(value)));}
}
