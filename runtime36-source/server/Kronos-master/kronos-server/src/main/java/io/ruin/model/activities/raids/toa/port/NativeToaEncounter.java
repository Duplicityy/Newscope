package io.ruin.model.activities.raids.toa.port;

import io.ruin.api.utils.Random;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.*;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import java.util.*;

/** Shared native mechanics only; encounter patterns remain in their authored controller. */
abstract class NativeToaEncounter implements ToaEncounter {
    protected final TombsOfAmascutRaid.Session session;
    protected final List<NPC> actors=new ArrayList<>();
    protected final List<GameObject> objects=new ArrayList<>();
    private final List<Due> pending=new ArrayList<>();
    protected NPC boss;
    protected int cycle,maxHp;
    protected boolean dead,closed,movementBlocked,invulnerable;
    private static final class Due{final int at;final Runnable task;Due(int at,Runnable task){this.at=at;this.task=task;}}
    NativeToaEncounter(TombsOfAmascutRaid.Session session){this.session=session;}
    protected static List<String> assets(ToaRoom room,int[] npcs,int[] animations,int[] graphics,int[] objects){
        List<String> missing=new ArrayList<>();
        if(NearRealityRaidAssets.region(room.sourceRegion)<0)missing.add("source room "+room.sourceRegion);
        for(int id:npcs)if(NearRealityRaidAssets.npc(id)<0)missing.add("NPC "+id);
        for(int id:animations)if(NearRealityRaidAssets.animation(id)<0)missing.add("animation "+id);
        for(int id:graphics)if(NearRealityRaidAssets.graphic(id)<0)missing.add("graphic "+id);
        for(int id:objects)if(NearRealityRaidAssets.object(id)<0)missing.add("object "+id);
        return missing;
    }
    protected Position tile(int x,int y){return session.tile(x,y);}
    protected int x(Position p){return session.sourceX(p);}
    protected int y(Position p){return session.sourceY(p);}
    protected int anim(int source){return NearRealityRaidAssets.animation(source);}
    protected int gfx(int source){return NearRealityRaidAssets.graphic(source);}
    protected boolean live(){return !closed&&!dead&&session.valid()&&boss!=null&&!boss.isRemoved();}
    protected boolean valid(Player p){return p==session.player&&live();}
    protected void later(int ticks,Runnable action){pending.add(new Due(cycle+Math.max(1,ticks),action));}
    protected NPC actor(int source,int x,int y){
        int id=NearRealityRaidAssets.npc(source);if(id<0)throw new IllegalStateException("Missing ToA NPC "+source);
        NPC n=new NPC(id).spawn(tile(x,y));n.sixSystemsRewardless=true;
        if(n.getCombat()!=null){n.getCombat().setAllowRespawn(false);n.getCombat().setAllowRetaliate(false);n.getCombat().reset();}
        actors.add(n);session.map.addNpc(n);return n;
    }
    protected npc_combat.Info info(int source){
        npc_combat.Info info=NearRealityRaidAssets.combatInfo(source);
        if(info==null)throw new IllegalStateException("Missing actual ToA combat info "+source);
        return info;
    }
    protected void mainBoss(int source,int x,int y,boolean follows){
        boss=actor(source,x,y);npc_combat.Info info=info(source);maxHp=info.hitpoints;
        boss.installCombat(new ActorCombat(follows,true,null),info);
        boss.attackNpcListener=(p,n,message)->valid(p)&&!invulnerable;
        boss.hitListener=new HitListener().preDefend(this::incoming).preDamage(this::incoming)
                .postDamage(hit->session.recordDamage(hit,session.room==ToaRoom.BABA?2:1));
    }
    protected void incoming(Hit hit){
        if(hit.type==HitType.HEAL&&hit.attacker==null)return;
        if(invulnerable||hit.attacker==null||!valid(hit.attacker.player)){hit.block().nullify();hit.attacker=null;}
    }
    protected void passiveCombat(NPC n,int source,int hp,Runnable onDeath){
        npc_combat.Info stats=info(source);if(hp>0)stats.hitpoints=hp;
        n.installCombat(new ActorCombat(false,false,onDeath),stats);
        n.attackNpcListener=(p,actor,message)->valid(p);
        n.hitListener=new HitListener().preDefend(hit->{if(hit.attacker==null||!valid(hit.attacker.player))hit.block().nullify();})
                .postDamage(hit->{if(source==11797)session.recordDamage(hit,1);});
    }
    @Override public final void tick(){
        if(!live())return;cycle++;
        if(cycle%25==0){
            actors.removeIf(NPC::isRemoved);
            if(session.map.getNpcs()!=null)session.map.getNpcs().removeIf(NPC::isRemoved);
        }
        List<Due> ready=new ArrayList<>();for(Due due:pending)if(due.at<=cycle)ready.add(due);
        pending.removeAll(ready);for(Due due:ready){if(!live())return;due.task.run();}
        if(!live())return;
        if(boss.getCombat()!=null&&boss.getCombat().getTarget()!=session.player)boss.getCombat().setTarget(session.player);
        pulse();
    }
    @Override public NPC defeatedBoss(){return boss;}
    protected abstract void pulse();
    protected void bossDied(){dead=true;pending.clear();session.completed();}
    protected void typedHit(AttackStyle style,int max,int delay){
        if(!live())return;Hit hit=new Hit(boss,style).randDamage(max).delay(delay);
        hit.preDamage(p->{if(!valid(p.player))hit.block().nullify();});session.player.hit(hit);
    }
    protected void fixedHit(int damage){
        if(!live())return;Hit hit=new Hit(boss).fixedDamage(damage).ignorePrayer().ignoreDefence();
        hit.preDamage(p->{if(!valid(p.player))hit.block().nullify();});session.player.hit(hit);
    }
    protected void graphic(Position pos,int source){if(live())session.player.getPacketSender().sendGraphics(gfx(source),0,0,pos.getX(),pos.getY(),pos.getZ());}
    protected void projectile(Position from,Position to,int source,int start,int end,int delay,int duration,int increment){new Projectile(gfx(source),start,end,delay,duration,increment,0,0).send(from,to);}
    protected boolean free(int x,int y){Position p=tile(x,y);Tile t=Tile.get(p.getX(),p.getY(),p.getZ(),false);return t!=null&&t.isFloorFreeCheckDecor();}
    protected void remove(NPC n){if(n==null)return;if(!n.isRemoved())n.remove();actors.remove(n);if(session.map.getNpcs()!=null)session.map.getNpcs().remove(n);}
    /** Keep a dead actor visible through the native update that carries its final hit. */
    static void afterDeathUpdate(NPC npc,int deathTicks,Runnable cleanup){
        npc.getMovement().reset();npc.getCombat().reset();
        npc.addEvent(event->{event.delay(Math.max(2,deathTicks+1));if(!npc.isRemoved())cleanup.run();});
    }
    protected void clearPending(){pending.clear();}
    @Override public void close(){
        if(closed)return;closed=true;pending.clear();
        for(NPC actor:new ArrayList<>(actors))remove(actor);actors.clear();
        for(GameObject object:objects)object.remove();objects.clear();
    }
    private final class ActorCombat extends NPCCombat {
        final boolean follows,main;final Runnable onDeath;
        ActorCombat(boolean follows,boolean main,Runnable onDeath){this.follows=follows;this.main=main;this.onDeath=onDeath;}
        public void init(){setAllowRespawn(false);setAllowRetaliate(false);}
        public void follow(){if(follows&&!movementBlocked&&live())follow(1);}
        public boolean attack(){return false;}
        public boolean canAttack(Entity target){return target!=null&&valid(target.player);}
        public int getAttackBoundsRange(){return 64;}
        public void startDeath(Hit hit){
            if(isDead()||closed)return;setDead(true);
            if(info.death_animation>=0)npc.animate(info.death_animation);
            if(main)bossDied();else{
                session.rollPet(npc);
                if(onDeath!=null)onDeath.run();
                afterDeathUpdate(npc,info.death_ticks,()->NativeToaEncounter.this.remove(npc));
            }
        }
    }
}
