package io.ruin.model.activities.hollowdepths;

import io.ruin.cache.NPCDef;
import io.ruin.api.buffer.OutBuffer;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.*;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.entity.shared.masks.*;
import io.ruin.model.map.Position;
import java.nio.file.*;
import java.util.*;

/** Detached native regression: no Server.main, cache, sockets, accounts, World entries or attach. */
public final class HitsplatRegression {
    private static final List<String> results=new ArrayList<>();
    private static int assertions,failures;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static final class FixtureCombat extends NPCCombat {
        int defendCalls,retaliationCalls;
        public void init(){} public void follow(){} public boolean attack(){return false;}
        @Override public int getDefendAnimation(){defendCalls++;return -1;}
        @Override public boolean allowRetaliate(Entity attacker){retaliationCalls++;return false;}
        @Override public void startDeath(Hit hit){setDead(true);}
    }
    private static final class FixtureNpc extends NPC {
        private static final NPCDef DEF=new NPCDef();
        private int hp=80,max=100;
        private final FixtureCombat combat=new FixtureCombat();
        private final NPCMovement movement=new NPCMovement(this);
        FixtureNpc(){super(0);position=new Position(2043,3576,0);hitsUpdate=new HitsUpdate();animationUpdate=new AnimationUpdate();unlock();}
        @Override public NPCDef getDef(){return DEF;}
        @Override public int getHp(){return hp;}
        @Override public int getMaxHp(){return max;}
        @Override public int setHp(int value){return hp=value;}
        @Override public int setMaxHp(int value){return max=value;}
        @Override public NPCCombat getCombat(){return combat;}
        @Override public NPCMovement getMovement(){return movement;}
        void processNative(){super.processHits();}
    }
    private static Hit damage(int amount,int delay){return new Hit().fixedDamage(amount).delay(delay).setResetActions(false);}
    private static void wireSplat(FixtureNpc n,HitType type,int damage){
        for(boolean player:new boolean[]{false,true}){
            OutBuffer out=new OutBuffer(64);n.hitsUpdate.send(out,player);byte[] bytes=out.toByteArray();
            int count=player?((bytes[0]-128)&255):((-bytes[0])&255);
            check(count==1,"serialized native hit count must be one for "+(player?"player":"NPC")+" mask");
            check((bytes[1]&255)==type.ordinal()&&(bytes[2]&255)==damage,"serialized hitsplat type/damage");
        }
    }
    private static void test(String name,Runnable body){
        HollowDepths.port=null;
        try{body.run();results.add("PASS "+name);}catch(Throwable failure){failures++;results.add("FAIL "+name+": "+failure);if(!(failure instanceof AssertionError))for(StackTraceElement frame:failure.getStackTrace())results.add("  at "+frame);}
        finally{HollowDepths.port=null;}
    }
    private static void cases(){
        test("ordinary damage queues native hitsplat",()->{
            FixtureNpc n=new FixtureNpc();Hit h=damage(9,0);n.hit(h);n.processNative();
            check(n.getHp()==71,"damage applied");check(n.hitsUpdate.hasUpdate(false),"native NPC hits update absent after successful damage");
            check(h.removed&&!h.isNullified(),"normally completed hit must not become nullified");
            wireSplat(n,HitType.DAMAGE,9);
        });
        test("zero damage emits blocked hitsplat",()->{
            FixtureNpc n=new FixtureNpc();Hit h=damage(0,0);n.hit(h);n.processNative();
            check(n.getHp()==80,"blocked HP unchanged");check(h.type==HitType.BLOCKED,"native zero-damage conversion");check(n.hitsUpdate.hasUpdate(false),"blocked splat absent");
            wireSplat(n,HitType.BLOCKED,0);
        });
        test("delayed hit preserves exact due tick",()->{
            FixtureNpc n=new FixtureNpc();Hit h=damage(8,2);n.hit(h);n.processNative();
            check(n.getHp()==80&&!n.hitsUpdate.hasUpdate(false)&&h.getTicks()==1,"first delay tick");n.processNative();
            check(n.getHp()==80&&!n.hitsUpdate.hasUpdate(false)&&h.getTicks()==0,"second delay tick");n.processNative();
            check(n.getHp()==72&&n.hitsUpdate.hasUpdate(false),"due tick HP and splat");
        });
        test("heal emits native hitsplat",()->{
            FixtureNpc n=new FixtureNpc();Hit h=new Hit(HitType.HEAL).fixedDamage(7).delay(0);n.hit(h);n.processNative();
            check(n.getHp()==87,"heal applied");check(n.hitsUpdate.hasUpdate(false),"heal splat absent");
            wireSplat(n,HitType.HEAL,7);
        });
        test("hidden damage stays hidden",()->{
            FixtureNpc n=new FixtureNpc();n.hit(damage(5,0).hide());n.processNative();
            check(n.getHp()==75&&!n.hitsUpdate.hasUpdate(false),"hidden hit changed semantics");
        });
        test("pre-nullified hit remains rejected",()->{
            FixtureNpc n=new FixtureNpc();Hit h=damage(5,0).nullify();n.hit(h);n.processNative();
            check(n.getHp()==80&&!n.hitsUpdate.hasUpdate(false),"nullified hit leaked damage or splat");
        });
        test("queued explicit nullification remains rejected",()->{
            FixtureNpc n=new FixtureNpc();Hit h=damage(5,2);n.hit(h);h.nullify();n.processNative();
            check(n.getHp()==80&&!n.hitsUpdate.hasUpdate(false)&&h.removed,"queued nullified hit leaked");
        });
        test("temporary damage delay resumes due hit",()->{
            FixtureNpc n=new FixtureNpc();Hit h=damage(6,0);n.hit(h);n.lock(LockType.FULL_DELAY_DAMAGE);n.processNative();
            check(n.getHp()==80&&!n.hitsUpdate.hasUpdate(false),"delay lock failed");n.unlock();n.processNative();
            check(n.getHp()==74&&n.hitsUpdate.hasUpdate(false),"due hit lost during delay lock");
        });
        test("normal combat reaches defend animation and retaliation",()->{
            FixtureNpc n=new FixtureNpc(),attacker=new FixtureNpc();
            n.hit(new Hit(attacker,AttackStyle.SLASH).fixedDamage(4).ignoreDefence().delay(0).setResetActions(false));n.processNative();
            check(n.getHp()==76&&n.hitsUpdate.hasUpdate(false),"normal NPC-owned combat splat");
            check(n.combat.defendCalls==1&&n.combat.retaliationCalls==1,"post-hit defend/retaliation skipped");
        });
        test("orphan dungeon actor rejected at impact",()->{
            HollowDepths.port=new HollowDepthsGamePort(null,null);
            FixtureNpc n=new FixtureNpc(),attacker=new FixtureNpc();Hit h=new Hit(attacker).fixedDamage(9).delay(0).setResetActions(false);
            n.hit(h);attacker.hollowDepthsActor=true; // Actual production late-impact gate now rejects this unregistered actor.
            n.processNative();check(n.getHp()==80&&!n.hitsUpdate.hasUpdate(false)&&h.isNullified()&&h.removed,"late orphan hit escaped native gate");
            check(n.combat.defendCalls==0&&n.combat.retaliationCalls==0,"rejected hit produced combat side effects");
        });
        test("orphan dungeon actor rejected at launch",()->{
            HollowDepths.port=new HollowDepthsGamePort(null,null);
            FixtureNpc n=new FixtureNpc(),attacker=new FixtureNpc();attacker.hollowDepthsActor=true;
            n.hit(new Hit(attacker).fixedDamage(9).delay(0).setResetActions(false));n.processNative();
            check(n.getHp()==80&&!n.hitsUpdate.hasUpdate(false),"orphan launch escaped filter");
        });
    }
    public static void main(String[] args)throws Exception{
        int exit=1;
        try{
            if(HollowDepths.port!=null)throw new IllegalStateException("Detached process required");
            results.add("Native Hit origin="+Hit.class.getProtectionDomain().getCodeSource().getLocation());
            results.add("Native Entity origin="+Entity.class.getProtectionDomain().getCodeSource().getLocation());
            cases();results.add((failures==0?"PASS":"FAIL")+" native hitsplat regression: assertions="+assertions+" failedCases="+failures);
            results.add("Fixture NPC supplies isolated HP/combat state; actual Entity.hit/processHits, Hit.defend/finish, HitsUpdate and HollowDepths gates execute unchanged from candidate classes.");
            Files.write(Paths.get(args[0]),results);for(String line:results)System.out.println(line);exit=failures==0?0:1;
        }finally{System.exit(exit);}
    }
}
