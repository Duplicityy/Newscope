package io.ruin.content.content242.bosses;

import io.ruin.model.World;
import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.DeathListener;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.map.*;
import io.ruin.model.map.dynamic.DynamicMap;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import java.util.*;

/** One private native map per owner, with explicit exit/logout/restart recovery. */
public final class Boss242Instances {
    private static final Map<Player,Session> sessions=new IdentityHashMap<>();
    private Boss242Instances(){}
    private static final class Session {
        final Player owner;final DynamicMap map;final Position back;final NPC boss;final DeathListener previousDeath;
        DeathListener ownedDeath;boolean closed;
        Session(Player p,DynamicMap m,Position b,NPC n){owner=p;map=m;back=b;boss=n;previousDeath=p.deathEndListener;}
        void close(boolean returnOwner){
            if(closed)return;closed=true;
            if(returnOwner)owner.getMovement().teleport(back);
            if(boss.getCombat() instanceof Boss242Combat)((Boss242Combat)boss.getCombat()).close();
            if(owner.deathEndListener==ownedDeath)owner.deathEndListener=previousDeath;
            sessions.remove(owner);
            // teleport() queues movement. Keep recovery marker until the owner actually leaves.
            World.startEvent(event->{event.delay(2);
                while(owner.isOnline()&&owner.getPosition().getRegion().id==map.swRegion.id)event.delay(1);
                if(owner.isOnline())owner.content242BossReturn=null;
                map.destroy();
            });
        }
    }
    public static void offer(Player p,String name,Runnable create){
        Session existing=sessions.get(p);
        if(existing!=null){p.dialogue(new OptionsDialogue("Leave your private encounter?",new Option("Leave",()->existing.close(true)),new Option("Stay")));return;}
        p.dialogue(new OptionsDialogue(name+" (private; safe deaths)",new Option("Enter",create),new Option("Cancel")));
    }
    static void enter(Player p,int sourceRegion,int sourceNpc,int bossX,int bossY,int entryX,int entryY){
        if(sessions.containsKey(p)||p.isLocked()||p.deathStartListener!=null||p.deathEndListener!=null||p.getCombat().isDead()||p.getAbsX()>=6400){p.sendMessage("Finish your current activity before entering.");return;}
        if(p.wildernessLevel!=0||p.pvpAttackZone){p.sendMessage("You cannot enter this encounter from a PvP area.");return;}
        // Native teleport permission handles teleblock, activity listeners, locks and special cooldown.
        // Build the map only after those checks accept; failed permission allocates nothing.
        p.getMovement().startTeleport(0,event->create(p,sourceRegion,sourceNpc,bossX,bossY,entryX,entryY));
    }
    private static void create(Player p,int sourceRegion,int sourceNpc,int bossX,int bossY,int entryX,int entryY){
        int mappedRegion=Boss242Assets.region(sourceRegion),mappedNpc=Boss242Assets.id("npcs",sourceNpc);if(mappedRegion<0||mappedNpc<0)return;
        DynamicMap map=null;Session session=null;
        try{
            map=new DynamicMap();
            map.setObjectConsumer(object->{ObjectDef def=ObjectDef.get(object.id);if(def!=null&&"Sewage water".equalsIgnoreCase(def.name))bindExit(object);});
            map.build(mappedRegion,sourceRegion==11330?1:0);
            // Equivalent source-local coordinate, even when the242 source archive was relocated.
            int bx=map.swRegion.baseX+(bossX&63),by=map.swRegion.baseY+(bossY&63);
            NPC boss=(sourceRegion==11330?new PhantomMuspah242.Actor(mappedNpc):new NPC(mappedNpc)).spawn(bx,by,0);map.addNpc(boss);
            if(!(boss.getCombat() instanceof Boss242Combat)||!((Boss242Combat)boss.getCombat()).ready)throw new IllegalStateException("Boss assets/handler unavailable");
            boss.getCombat().setAllowRespawn(false);
            ((Boss242Combat)boss.getCombat()).bindOwner(p);
            Position back=p.getPosition().copy();session=new Session(p,map,back,boss);Session active=session;
            p.content242BossReturn=new int[]{back.getX(),back.getY(),back.getZ()};sessions.put(p,session);
            session.ownedDeath=(entity,killer,hit)->active.close(true);p.deathEndListener=session.ownedDeath;
            int regionId=map.swRegion.id;
            p.addActiveMapListener(new MapListener(owner->!active.closed&&owner.getPosition().getRegion().id==regionId).onExit((owner,logout)->active.close(logout)));
            p.getMovement().teleport(map.swRegion.baseX+(entryX&63),map.swRegion.baseY+(entryY&63),0);
            if(sourceRegion==5446)for(int y=4510;y<=4512;y++){
                GameObject exit=new GameObject(Boss242Assets.id("objects",54282),map.swRegion.baseX+(1377&63),map.swRegion.baseY+(y&63),0,10,y==4510?3:1).spawn();bindExit(exit);
            }
            if(sourceRegion==11330){GameObject exit=new GameObject(Boss242Assets.id("objects",46599),map.swRegion.baseX+(2860&63),map.swRegion.baseY+(4258&63),0,10,0).spawn();bindExit(exit);}
            boss.getCombat().setTarget(p);boss.getCombat().updateLastAttack(4);
            p.sendMessage("Use the encounter exit or the New lands menu to leave. Death returns you safely to your entry point.");
        }catch(RuntimeException failure){if(session!=null)session.close(true);else if(map!=null)map.destroy();p.sendMessage("This encounter could not be created safely. Its assets may be incomplete.");}
    }
    private static void bindExit(GameObject object){object.actions=new ObjectAction[5];for(int i=0;i<object.actions.length;i++)object.actions[i]=(p,clicked)->leave(p);}
    public static void leave(Player p){Session session=sessions.get(p);if(session!=null)session.close(true);}
    public static void recoverLogin(Player p){
        if(p.content242BossReturn==null)return;
        int[] back=p.content242BossReturn;
        if(back.length==3&&back[0]>0&&back[0]<6400&&back[1]>0&&back[1]<16384&&back[2]>=0&&back[2]<4)p.getMovement().teleport(back);
        else p.getMovement().teleport(World.HOME);
        World.startEvent(event->{event.delay(2);if(p.content242BossReturn==back&&p.getAbsX()<6400)p.content242BossReturn=null;});
    }
}
