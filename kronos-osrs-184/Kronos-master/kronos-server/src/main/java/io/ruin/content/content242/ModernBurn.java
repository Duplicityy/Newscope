package io.ruin.content.content242;

import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.model.World;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.skills.slayer.Slayer;
import java.util.*;

/** New mapped weapon burns only. No persisted damage, ordinary hit changes or extra combat procs. */
public final class ModernBurn {
    public static final int CLAWS=29577, BOW=29591;
    private static final Set<String> IMMUNE=new HashSet<>(Arrays.asList("tzkal-zuk","slug prince","gemstone crab","mad angel","eclipse moon","blue moon","blood moon","cerberus"));
    private static final BurnTimeline<Entity,Source> burns=new BurnTimeline<>((target,source)->source.valid(target),ModernBurn::damage);
    private static boolean initialized;
    private ModernBurn(){}
    /** New encounters explicitly gate ownership and status damage without ordinary attack procs. */
    public interface StatusTarget {
        boolean allowsBurn(Player source);
        /** May reduce/block damage for a shield or a phase boundary; cannot amplify a burn. */
        int beforeBurn(int damage);
    }
    /** Registration identity and instance lease are captured independently for each actor. */
    private static final class Lease {
        final Entity entity;final int index,plane,dynamic;
        Lease(Entity e){entity=e;index=e.getIndex();plane=e.getHeight();dynamic=e.getPosition().getRegion().dynamicIndex;}
        boolean valid(){return alive(entity)&&entity.getIndex()==index&&entity.getHeight()==plane&&entity.getPosition().getRegion().dynamicIndex==dynamic;}
    }
    private static final class Source {
        final Lease attacker,target;
        Source(Player a,Entity t){attacker=new Lease(a);target=new Lease(t);}
        boolean valid(Entity t){
            return target.entity==t&&attacker.valid()&&target.valid()
                &&(!(t.getCombat() instanceof StatusTarget)||((StatusTarget)t.getCombat()).allowsBurn(attacker.entity.player));
        }
    }
    private static boolean alive(Entity entity){
        if(entity==null||entity.getPosition()==null||entity.getHp()<=0||entity.getCombat()==null||entity.getCombat().isDead())return false;
        if(entity.player!=null)return entity.player.isOnline()&&World.players.get(entity.getIndex())==entity;
        return entity.npc!=null&&!entity.npc.isRemoved()&&World.npcs.get(entity.getIndex())==entity;
    }
    public static boolean demon(Entity target){return target!=null&&target.npc!=null&&target.npc.getDef().demon;}
    public static boolean immune(Entity target){
        if(target==null||target.npc==null)return false;
        String name=target.npc.getDef().name;return name!=null&&IMMUNE.contains(name.toLowerCase(Locale.ROOT))
            ||Content242Registry.current().matches("npcs",8620,target.npc.getId());
    }
    public static boolean apply(Player attacker,Entity target,int damage){
        if(!alive(attacker)||!alive(target)||immune(target)||attacker.getHeight()!=target.getHeight()
            ||attacker.getPosition().getRegion().dynamicIndex!=target.getPosition().getRegion().dynamicIndex)return false;
        return burns.add(target,new Source(attacker,target),Server.currentTick(),damage);
    }
    public static void immediate(Player attacker,Entity target){
        if(alive(attacker)&&alive(target)&&!immune(target))damage(target,Collections.singletonList(new Source(attacker,target)));
    }
    public static void pulse(){if(burns.targets()!=0)burns.pulse(Server.currentTick());}
    public static void clear(Entity entity){burns.clear(entity);}
    public static int remaining(Entity target){return burns.remaining(target);}
    private static void damage(Entity target,List<Source> sources){
        List<Source> valid=new ArrayList<>(5);for(Source source:sources)if(source.valid(target))valid.add(source);
        if(!valid.isEmpty())target.hit(new BurnHit(valid));
    }
    /** Native queue and hitsplat delivery; status completion avoids attack/XP/reflection listeners. */
    public static final class BurnHit extends Hit {
        private final List<Source> sources;private boolean completed;
        private BurnHit(List<Source> sources){super(HitType.DAMAGE);this.sources=sources;fixedDamage(sources.size());delay(0);setResetActions(false);ignoreDefence();ignorePrayer();}
        private int validDamage(Entity target){int count=0;for(Source source:sources)if(source.valid(target))count++;return Math.min(count,Math.max(0,damage));}
        /** For private encounter defence guards; attacker remains null for native status delivery. */
        public boolean validForOwner(Player owner){
            if(owner==null)return false;
            for(Source source:sources)if(source.attacker.entity==owner&&source.valid(source.target.entity))return true;
            return false;
        }
        @Override public boolean defend(Entity target){
            if(immune(target)||!alive(target)){nullify();return false;}
            // NPC reductions use the donor's ranged status classification, without an attacker
            // or equipment callbacks. Player protection prayers do not reduce status damage.
            attackStyle=target.npc==null?null:AttackStyle.RANGED;
            try{return super.defend(target);}finally{attackStyle=null;}
        }
        @Override public boolean finish(Entity target){
            if(completed||isNullified()){nullify();return true;}
            if(!alive(target)||immune(target)||target.isLocked(LockType.FULL_NULLIFY_DAMAGE)||target.isInvincible()){completed=true;nullify();return true;}
            if(target.isLocked(LockType.FULL_DELAY_DAMAGE)){completed=true;nullify();return true;} // never retain a delayed burn beyond its lease
            completed=true;damage=Math.min(target.getHp(),validDamage(target));
            if(damage<=0){nullify();return true;}
            if(target.getCombat() instanceof StatusTarget){
                damage=Math.max(0,Math.min(damage,((StatusTarget)target.getCombat()).beforeBurn(damage)));
                damage=Math.min(damage,validDamage(target));
                if(damage<=0){nullify();return true;}
            }
            int credited=0;for(Source source:sources)if(source.valid(target)&&credited++<damage)target.getCombat().addKiller(source.attacker.entity,1);
            if(target.incrementHp(-damage)==0){clear(target);target.getCombat().startDeath(this);}
            return true;
        }
    }
    public static synchronized void initialize(){
        if(initialized)return;initialized=true;
        install(CLAWS,"Burning claws","burning_claws",false);install(BOW,"Scorching bow","scorching_bow",true);
    }
    private static void install(int source,String name,String feature,boolean bow){
        if(!ModernGear.feature(feature))return;
        int id=ModernCharges.item(source);ItemDef def=id<0?null:ItemDef.get(id);if(!ModernGear.named(def,source,name))return;
        if(bow){
            def.rangedWeapon=RangedWeapon.NORMAL_BOW;def.rangedLevel=77;
            if(def.weaponType!=null&&ModernGear.specialAssets(feature,426)){
                WeaponType old=def.weaponType,copy=new WeaponType();
                copy.config=old.config;copy.maxDistance=10;copy.attackTicks=5;copy.attackAnimation=ModernGear.animation(426);
                copy.defendAnimation=old.defendAnimation;copy.equipSound=old.equipSound;copy.attackSound=old.attackSound;
                copy.attackSets=old.attackSets;copy.renderAnimations=old.renderAnimations;def.weaponType=copy;
            }
        }
        def.addPreTargetDefendListener((player,item,hit,target)->{
            if(hit.attackWeapon!=def||!demon(target)||hit.attackStyle==null||bow!=hit.attackStyle.isRanged()||!bow&&!hit.attackStyle.isMelee())return;
            boolean slayer=false;ItemDef helm=player.getEquipment().getDef(Equipment.SLOT_HAT);
            if(bow&&helm!=null&&helm.slayerBoostAll)slayer=Slayer.isTask(player,target.npc)||target.npc.getId()==7413;
            hit.boostAttack((1+hit.getAttackBoost())*(bow?.30:.05));
            hit.boostDamage(ModernBurnRules.demonDamageBoost(hit.getDamageBoost(),bow,slayer));
        });
    }
}
