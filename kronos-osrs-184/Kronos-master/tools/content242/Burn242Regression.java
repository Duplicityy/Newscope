package io.ruin.content.content242;

import io.ruin.cache.NPCDef;
import io.ruin.model.World;
import io.ruin.model.activities.miscpvm.BasicCombat;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCCombat;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.entity.shared.masks.HitsUpdate;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import java.lang.reflect.*;
import java.util.*;

/** Detached rules and actual Entity.hit/processHits/HP/kill-credit tests; no accounts or server startup. */
public final class Burn242Regression {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static final class Token {boolean online=true;int lease;final int captured;Token(int lease){this.lease=captured=lease;}}
    private static void timeline(){
        Object target=new Object();List<Integer> hits=new ArrayList<>();
        BurnTimeline<Object,Token> clock=new BurnTimeline<>((t,v)->v.online&&v.lease==v.captured,(t,v)->hits.add(v.size()));
        Token a=new Token(1);check(clock.add(target,a,0,10),"first burn");
        for(int tick=0;tick<4;tick++)clock.pulse(tick);check(hits.isEmpty(),"no early damage");
        clock.pulse(4);clock.pulse(4);check(hits.equals(Arrays.asList(1)),"first tick and duplicate pulse");
        Token b=new Token(1);check(clock.add(target,b,5,4),"independent duration joins target clock");
        for(int tick=5;tick<=40;tick++)clock.pulse(tick);
        check(hits.equals(Arrays.asList(1,2,2,2,2,1,1,1,1,1)),"shared phase and independent10/4 damage");
        check(clock.targets()==0&&clock.remaining(target)==0,"expiry releases actors");
        for(int i=0;i<5;i++)check(clock.add(target,new Token(2),41,10),"stack accepted");
        check(!clock.add(target,new Token(2),41,10)&&clock.remaining(target)==50,"five-stack cap");
        clock.clear(target);check(clock.targets()==0,"explicit clear");
        Token offline=new Token(3);clock.add(target,offline,42,10);offline.online=false;clock.pulse(43);check(clock.targets()==0,"logout/death removes entry before due tick");
        Token moved=new Token(4);clock.add(target,moved,44,10);moved.lease=5;clock.pulse(45);check(clock.targets()==0,"instance lease invalidates stack");
        for(int i=0;i<BurnTimeline.MAX_TARGETS;i++)check(clock.add(new Object(),new Token(6),46,10),"bounded target accepted");
        check(!clock.add(new Object(),new Token(6),46,10),"global target cap");clock.pulse(1);check(clock.targets()==0,"clock rollback fails closed");
        check(!clock.add(target,new Token(1),2,11)&&!clock.add(target,new Token(1),-1,1),"bad duration/tick rejected");
    }
    private static void rules(){
        check(Arrays.equals(ModernBurnRules.split(0,29),new int[]{7,7,14}),"donor first branch29");
        check(Arrays.equals(ModernBurnRules.split(1,20),new int[]{9,9,2}),"donor second branch20");
        check(Arrays.equals(ModernBurnRules.split(2,20),new int[]{1,1,18}),"donor third branch20");
        for(int maximum=0;maximum<=200;maximum++)for(int roll=0;roll<3;roll++){
            int min=ModernBurnRules.minimum(maximum,roll),max=ModernBurnRules.maximum(maximum,roll);
            check(min==(int)Math.floor(maximum*(.75-.25*roll))&&max==(int)Math.floor(maximum*(1.75-.25*roll)),"inclusive branch bounds");
            for(int damage=min;damage<=max;damage++){
                int[] split=ModernBurnRules.split(roll,damage);check(split.length==3&&split[0]>=0&&split[1]>=0&&split[2]>=0,"nonnegative splits");
            }
        }
        for(int roll=0;roll<3;roll++){double chance=new double[]{.15,.3,.45}[roll];check(ModernBurnRules.burns(roll,chance-.0001)&&!ModernBurnRules.burns(roll,chance),"strict per-splat burn probability");}
        check(!ModernBurnRules.burns(-1,0),"all misses never burn");
        check(ModernBurnRules.miss(.199)[2]==0&&ModernBurnRules.miss(.2)[2]==1&&ModernBurnRules.miss(.6)[2]==2,"20/40/40 full-miss fallback");
        check(Math.abs(ModernBurnRules.demonDamageBoost(.15,true,true)-.30)<1e-12,"bow slayer bonus adds30percent");
        check(Math.abs(ModernBurnRules.demonDamageBoost(.15,true,false)-.345)<1e-12,"other ranged boosts multiply");
        check(ModernBurnRules.CLAWS_DRAIN==35&&ModernBurnRules.BOW_DRAIN==25&&ModernBurnRules.BOW_BIND_TICKS==20,"energy and bind constants");
    }
    private static final class Wielder extends Player {
        PlayerCombat combat;int hp;
        void initialize(int index){player=this;hp=100;combat=new PlayerCombat();position=new Position(3200,3200,0);setIndex(index);setOnline(true);World.players.set(index,this);}
        public int getHp(){return hp;}public int getUserId(){return getIndex();}public PlayerCombat getCombat(){return combat;}
    }
    private static final class Target extends NPC {
        static final NPCDef DEF=new NPCDef();static{DEF.name="Lesser demon";DEF.demon=true;DEF.size=1;}
        final Tracker combat=new Tracker(this);int hp=100;Hit last;
        Target(int index){super(1);position=new Position(3201,3200,0);setIndex(index);World.npcs.set(index,this);hitsUpdate=new HitsUpdate();}
        public NPCDef getDef(){return DEF;}public NPCCombat getCombat(){return combat;}
        public int getHp(){return hp;}public int getMaxHp(){return 100;}
        public int incrementHp(int amount){return hp=Math.max(0,hp+amount);}
        public int hit(Hit... hits){last=hits[0];return super.hit(hits);}
        void flush(){processHits();}
    }
    private static final class Tracker extends BasicCombat implements ModernBurn.StatusTarget {
        int deaths,damageAtDeath;
        Player owner;boolean allow=true;int cap=Integer.MAX_VALUE,statusCalls;
        Tracker(NPC target){npc=target;}
        public boolean allowsBurn(Player source){return allow&&(owner==null||owner==source);}
        public int beforeBurn(int damage){statusCalls++;return cap;}
        public void startDeath(Hit hit){deaths++;damageAtDeath=getKiller()==null?0:getKiller().damage;setDead(true);}
        public boolean allowRetaliate(Entity source){throw new AssertionError("Burn retaliated");}
    }
    private static void nativeStatus()throws Exception{
        Region region=new Region(Region.getId(3200,3200));Region.LOADED[region.id]=region;
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        Wielder source=(Wielder)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(Wielder.class);source.initialize(11);
        Target target=new Target(12);final int[] callbacks={0};
        source.hitListener=new HitListener().preTargetDefend((h,t)->callbacks[0]++).postTargetDefend((h,t)->callbacks[0]++).preTargetDamage((h,t)->callbacks[0]++).postTargetDamage((h,t)->callbacks[0]++);
        target.hitListener=new HitListener().postDamage(h->{throw new AssertionError("Burn triggered recoil/ordinary after-hit listener");});
        ModernBurn.immediate(source,target);check(target.hp==100,"native queued, not immediate HP mutation");
        check(target.last.attacker==null&&target.last.attackStyle==null&&!target.last.resetActions,"noXP/skull/attack-reset marker");
        target.flush();check(target.hp==99&&callbacks[0]==0&&target.combat.getKiller().damage==1,"native queue damage and exact attribution without procs");
        Field splats=HitsUpdate.class.getDeclaredField("splats");splats.setAccessible(true);List<?> delivered=(List<?>)splats.get(target.hitsUpdate);
        Object splat=delivered.get(0);Field type=splat.getClass().getDeclaredField("type");type.setAccessible(true);
        check(delivered.size()==1&&type.getInt(splat)==HitType.DAMAGE.ordinal(),"original184 damage splat delivered");
        ModernBurn.immediate(source,target);source.setOnline(false);target.flush();check(target.hp==99,"logout cancels already queued damage");source.setOnline(true);
        ModernBurn.immediate(source,target);region.dynamicIndex=123;target.flush();check(target.hp==99,"lease change cancels queued damage");region.dynamicIndex=-1;
        ModernBurn.immediate(source,target);source.combat.setDead(true);target.flush();check(target.hp==99,"source death cancels queued damage");source.combat.setDead(false);
        ModernBurn.immediate(source,target);World.npcs.set(target.getIndex(),new Target(13));target.flush();check(target.hp==99,"reused actor index cancels queued damage");World.npcs.set(target.getIndex(),target);
        target.hp=1;ModernBurn.immediate(source,target);target.flush();check(target.hp==0&&target.combat.deaths==1&&target.combat.damageAtDeath==2,"lethal status credits before native death exactly once");
        target.last.finish(target);check(target.combat.deaths==1,"repeated completion cannot kill twice");
        Target immune=new Target(14);Target.DEF.name="Cerberus";ModernBurn.immediate(source,immune);check(immune.last==null,"normal-burn immunity");Target.DEF.name="Lesser demon";
        check(!ModernBurn.apply(source,target,10),"dead target cannot acquire burn");
        Target shielded=new Target(15);shielded.combat.owner=source;
        Wielder outsider=(Wielder)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(Wielder.class);outsider.initialize(16);
        ModernBurn.immediate(outsider,shielded);check(shielded.last==null,"encounter ownership rejects outsider before queueing");
        shielded.hitListener=new HitListener().preDefend(hit->{
            if(!(hit instanceof ModernBurn.BurnHit)||!((ModernBurn.BurnHit)hit).validForOwner(source))hit.nullify();
        });
        ModernBurn.immediate(source,shielded);
        check(((ModernBurn.BurnHit)shielded.last).validForOwner(source)&&!((ModernBurn.BurnHit)shielded.last).validForOwner(outsider),"attacker-null status carries independently validated owner");
        shielded.combat.cap=0;shielded.flush();
        check(shielded.hp==100&&shielded.combat.statusCalls==1&&shielded.combat.getKiller()==null,"shield blocks before HP and kill credit");
        shielded.hp=1;ModernBurn.immediate(source,shielded);shielded.flush();
        check(shielded.hp==1&&shielded.combat.deaths==0,"status cannot bypass pre-shield lethal phase cap");
        shielded.combat.cap=100;shielded.hp=10;ModernBurn.immediate(source,shielded);shielded.flush();
        check(shielded.hp==9&&shielded.combat.getKiller().damage==1,"encounter callback cannot amplify status damage");
        ModernBurn.immediate(source,shielded);int calls=shielded.combat.statusCalls;shielded.combat.allow=false;shielded.flush();
        check(shielded.hp==9&&shielded.combat.statusCalls==calls,"ownership revocation cancels queued burn before shield callback");
    }
    public static void main(String[] args){int status=1;try{rules();timeline();nativeStatus();System.out.println("PASS burn rules/native status assertions="+checks+"; no server/account/client started; staged gameplay not tested");status=0;}catch(Throwable failure){failure.printStackTrace();}finally{System.exit(status);}}
}
