package io.ruin.content.content242.bosses;

import io.ruin.cache.NPCDef;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.map.*;
import java.nio.file.*;
import java.lang.reflect.Field;

/** Detached owner/lease regression using actual Hit.defend/finish. No accounts or world startup. */
public final class Boss242Regression {
    static int assertions;
    static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
    static final class Actor extends Player {
        Position at;boolean online;int hp;
        public Position getPosition(){return at;}
        public int getHeight(){return at.getZ();}
        public int getHp(){return hp;}
        public boolean isOnline(){return online;}
    }
    static final class Target extends NPC {
        static final NPCDef DEF=new NPCDef();int hp=100;Boss combat;
        Target(){super(0);hitListener=new HitListener();}
        public NPCDef getDef(){return DEF;}
        public int getHp(){return hp;}
        public int setHp(int value){return hp=value;}
        public Boss getCombat(){return combat;}
    }
    static final class Boss extends Boss242Combat {
        Boss(Target target){npc=target;target.combat=this;plane=0;lease=42;ready=true;
            target.hitListener.preDefend(this::guardIncoming).preDamage(this::guardIncoming);}
        public void init(){}public void follow(){}public boolean attack(){return false;}
        protected boolean arena(Position p){return p.getX()>=3200&&p.getX()<3210&&p.getY()>=3200&&p.getY()<3210;}
    }
    static Actor actor()throws Exception{
        Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        Actor p=(Actor)((sun.misc.Unsafe)f.get(null)).allocateInstance(Actor.class);
        p.player=p;p.at=new Position(3201,3201,0);p.online=true;p.hp=99;return p;
    }
    public static void main(String[] args)throws Exception{
        int exit=1;
        try{
            Region region=new Region(Region.getId(3201,3201));region.dynamicIndex=42;Region.LOADED[region.id]=region;
            Actor owner=actor(),stranger=actor();Target target=new Target();Boss boss=new Boss(target);
            check(!boss.valid(owner),"unbound encounter rejects everyone");boss.bindOwner(owner);boss.bindOwner(owner);
            check(boss.valid(owner)&&!boss.valid(stranger),"identity owner only, even on same tile and lease");
            check(boss.free(owner.at),"Sparse native tile without clipping is walkable");
            Tile.get(owner.at,true).flagUnmovable();check(!boss.free(owner.at),"Native blocked floor excludes hazard/spawn position");Tile.get(owner.at,true).unflagUnmovable();
            boolean second=false;try{boss.bindOwner(stranger);}catch(IllegalStateException expected){second=true;}check(second,"owner cannot change");
            Hit forbidden=new Hit(stranger).fixedDamage(17);check(!forbidden.defend(target)&&forbidden.isNullified()&&forbidden.attacker==null&&!forbidden.resetActions,"foreign launch rejected before XP/action attribution");
            Hit own=new Hit(owner).fixedDamage(17).delay(0);check(own.defend(target)&&own.damage==17,"owner native defend accepted");
            owner.at=new Position(3211,3201,0);check(!boss.valid(owner),"outside arena rejected");own.finish(target);
            check(own.isNullified()&&own.damage==0&&target.hp==100,"queued owner hit cancelled after arena exit");
            owner.at=new Position(3201,3201,0);Hit stale=new Hit(owner).fixedDamage(17).delay(0);check(stale.defend(target),"delayed hit launch in original lease");region.dynamicIndex=43;stale.finish(target);
            check(!boss.valid(owner)&&stale.isNullified()&&target.hp==100,"same coordinates with reused map lease rejected");region.dynamicIndex=42;
            owner.online=false;check(!boss.valid(owner),"offline owner rejected");owner.online=true;owner.hp=0;check(!boss.valid(owner),"dead owner rejected");owner.hp=99;
            owner.at=new Position(3201,3201,1);check(!boss.valid(owner),"other plane rejected");owner.at=new Position(3201,3201,0);
            Hit heal=new Hit(HitType.HEAL).fixedDamage(5);check(heal.defend(target)&&heal.damage==5,"scripted self healing preserved");
            boss.setDead(true);Hit dead=new Hit(owner).fixedDamage(17);check(!dead.defend(target),"dead boss rejects new hits");
            check(Scurrius242.phaseFor(500,500)==0&&Scurrius242.phaseFor(401,500)==0,"Scurrius first phase above80percent");
            check(Scurrius242.phaseFor(400,500)==1&&Scurrius242.phaseFor(151,500)==1,"Scurrius feeding thresholds");
            check(Scurrius242.phaseFor(150,500)==2&&Scurrius242.phaseFor(1,500)==2,"Scurrius enrage threshold");
            check(Amoxliatl242.sourceFloor(1376,4511)&&!Amoxliatl242.sourceFloor(1377,4511),"Amox entry inside, exit outside arena");
            check(Amoxliatl242.sourceFloor(1363,4517)&&!Amoxliatl242.sourceFloor(1362,4517),"Amox tapered arena row");
            String report="PASS boss detached regression assertions="+assertions+"; actual native Hit.defend/finish; synthetic actors, no client/server/account started.";
            Files.createDirectories(Paths.get(args[0]).getParent());Files.write(Paths.get(args[0]),report.getBytes("UTF-8"));System.out.println(report);exit=0;
        }catch(Throwable failure){failure.printStackTrace();}finally{System.exit(exit);}
    }
}
