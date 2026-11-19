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
import io.ruin.model.skills.prayer.Prayer;
import java.util.*;

/** Native level0 encounter adapted from NR f577de7 Kephri and its scarab controllers. */
public final class KephriEncounter extends NativeToaEncounter {
    private final KephriRules rules=new KephriRules();
    private final List<Helper> helpers=new ArrayList<>();
    private final List<Egg> eggs=new ArrayList<>();
    private final Set<Position> dung=new HashSet<>();
    private int attackTicks=6,specialCycle=3,swarmTick=5,swarmIndex,flyTick=8;
    private boolean nextDung=true;
    private static final int[][] SWARMS={{3543,5404},{3543,5410},{3547,5416},{3553,5416},{3559,5410},{3559,5404},{3553,5400},{3547,5400}};
    private static final int[][] EGGS={{3551,5404},{3549,5402},{3545,5406},{3551,5412},{3549,5414},{3545,5410},{3547,5408},{3553,5402},{3557,5406},{3553,5414},{3555,5408},{3557,5410}};
    private static final int[][] ARCANE={{3556,5401},{3544,5413},{3550,5401},{3556,5413}};
    public static void install(){TombsOfAmascutRaid.register(ToaRoom.KEPHRI,new TombsOfAmascutRaid.Factory(){
        public List<String> missingAssets(){return KephriEncounter.missingAssets();}
        public ToaEncounter create(TombsOfAmascutRaid.Session s){return new KephriEncounter(s);}
    });}
    public static List<String> missingAssets(){
        List<String> missing=assets(ToaRoom.KEPHRI,new int[]{11719,11720,11722,11723,11724,11725,11726,11727,11728,11729},
            new int[]{9577,9578,9579,9581,9582,9587,9588,9589,9594,9596,9597,9605,9607,8630,9799},
            new int[]{1447,1481,2266,2143,2144,2145,2146,2147,2148,2149,2150,2152,2156,2157,2164,2165,2166},new int[]{45504});
        for(int id:new int[]{11719,11723,11724,11725,11726,11727,11728,11729})if(NearRealityRaidAssets.combatInfo(id)==null)missing.add("donor combat statistics "+id);
        return missing;
    }
    public KephriEncounter(TombsOfAmascutRaid.Session session){super(session);}
    /** NR skips projectile clipping only against Kephri from its challenge rectangle. */
    boolean ignoreProjectileClip(Player attacker,NPC target){
        if(attacker==null||target==null||target!=boss||!valid(attacker)||session.encounter!=this
                ||session.room!=ToaRoom.KEPHRI||target.isRemoved()||target.getHeight()!=attacker.getHeight()
                ||target.getPosition().getRegion().dynamicIndex!=session.lease
                ||session.map.swRegion.dynamicIndex!=session.lease)return false;
        int x=x(attacker.getPosition()),y=y(attacker.getPosition());
        return x>=3543&&x<=3559&&y>=5400&&y<=5416;
    }
    @Override public void spawn(){
        boss=actor(11719,3549,5406);npc_combat.Info stats=info(11719);maxHp=stats.hitpoints;
        boss.installCombat(new KephriCombat(),stats);
        boss.attackNpcListener=(p,n,message)->valid(p)&&!invulnerable;
        boss.hitListener=new HitListener().preDefend(this::incoming).preDamage(this::incoming);
        session.player.sendMessage("Kephri shields itself between phases. Stop scarabs from restoring its shield.");
    }
    @Override protected void pulse(){
        tickHelpers();tickEggs();
        if(rules.shieldTicks>0){
            if(rules.shieldTicks==50)boss.transformVisual(NearRealityRaidAssets.npc(11720));
            if(rules.shieldTicks<49&&rules.shieldTicks>3&&--flyTick<=0){flyTick=12;bomb(false);}
            if(rules.shieldTicks>3&&--swarmTick<=0){swarmTick=Random.get(2,3);int[] source=SWARMS[swarmIndex++%SWARMS.length];spawnHelper(11723,source[0],source[1]);}
            rules.tick();
            if(rules.shieldTicks==3){boss.animate(anim(9581));graphic(boss.getPosition(),2144);for(Helper h:helpers)if(h.source==11723)remove(h.npc);}
            if(rules.shieldTicks==0){invulnerable=false;boss.transformVisual(NearRealityRaidAssets.npc(11719));attackTicks=6;}
            return;
        }
        if(--attackTicks<=0){
            attackTicks=6;
            if(specialCycle--==0){specialCycle=rules.phase==3?2:Random.get(4,5);if(nextDung){nextDung=false;dungAttack();}else{nextDung=true;eggAttack();}}
            else{boss.animate(anim(9577));projectile(tile(3550,5409),tile(3551,5408),1481,175,250,39,50,51);later(2,()->{if(!invulnerable)bomb(true);});}
        }
    }
    private void bomb(boolean regular){
        final Position marked=session.player.getPosition().copy();graphic(marked,1447);
        projectile(regular?tile(3551,5408):tile(3542,5417),marked,regular?2266:2147,regular?250:112,9,0,14,120);
        later(3,()->{graphic(marked,2157);if(!regular)graphic(marked,2148);if(marked.equals(session.player.getPosition())){
            int base=KephriRules.bombBase(regular,session.player.getPrayer().isActive(Prayer.PROTECT_FROM_MAGIC));fixedHit(Random.get(base,base*2));
        }});
    }
    private void beginPhase(){
        if(!rules.depleted())return;
        clearPending();
        if(rules.complete()){boss.animate(anim(9582));dead=true;clearHelpers();clearEggs();for(GameObject o:objects)o.remove();objects.clear();dung.clear();session.completed();return;}
        if(rules.phase<3){
            invulnerable=true;boss.setHp(1);boss.animate(anim(9579));graphic(boss.getPosition(),2143);swarmTick=5;flyTick=8;
            if(rules.phase==1)spawnHelper(11725,3556,5413);
            else{spawnHelper(11726,3556,5401);spawnHelper(11724,3556,5407);}
        }else{
            npc_combat.Info stats=info(11719);stats.hitpoints=80;boss.installCombat(new KephriCombat(),stats);maxHp=80;invulnerable=false;specialCycle=2;
            dungAttack();
        }
    }
    private final class KephriCombat extends NPCCombat {
        public void init(){setAllowRespawn(false);setAllowRetaliate(false);}
        public void follow(){}
        public boolean attack(){return false;}
        public boolean canAttack(Entity target){return target!=null&&valid(target.player);}
        public int getAttackBoundsRange(){return 64;}
        public void startDeath(Hit hit){if(dead||closed||rules.shieldTicks>0)return;beginPhase();if(dead)setDead(true);}
    }
    private void spawnHelper(int source,int x,int y){
        NPC n=actor(source,x,y);Helper helper=new Helper(n,source);helpers.add(helper);passiveCombat(n,source,-1,()->{});
        n.attackNpcListener=(p,actor,message)->valid(p)&&helper.flight<=0;
        n.hitListener=new HitListener().preDefend(hit->{if(helper.flight>0||hit.attacker==null||!valid(hit.attacker.player))hit.block().nullify();})
            .preDamage(hit->{if(helper.flight>0||hit.attacker==null||!valid(hit.attacker.player))hit.block().nullify();})
            .postDamage(hit->{if(source==11726&&!n.isRemoved()&&n.getHp()>0&&(hit.damage>=40||(hit.damage>=15&&++helper.moderate>=3)))flyAway(helper);});
        n.animate(anim(source==11723?9605:source==11726?9596:9589));
    }
    private void tickHelpers(){
        for(Helper h:new ArrayList<>(helpers)){
            if(h.npc.isRemoved()||h.npc.getCombat().isDead()){helpers.remove(h);continue;}
            if(h.source==11723){
                if(++h.age<4)continue;
                int sx=x(h.npc.getPosition()),sy=y(h.npc.getPosition()),dx=Integer.signum(3551-sx),dy=Integer.signum(5408-sy);
                if(Math.max(Math.abs(sx-3551),Math.abs(sy-5408))<=3){if(rules.shieldTicks>0)healBoss(.08,.10,1);remove(h.npc);helpers.remove(h);}
                else h.npc.getMovement().teleport(tile(sx+dx,sy+dy));
            }else if(h.source==11725){
                if(--h.delay<=0){h.delay=3;h.npc.animate(anim(9588));projectile(h.npc.getPosition(),session.player.getPosition(),2152,24,31,36,16,14);typedFrom(h.npc,AttackStyle.RANGED,10,2,.2);later(1,()->session.player.poison(5));}
            }else if(h.source==11724){
                if(++h.age>=13&&(h.age-13)%6==0){projectile(h.npc.getPosition(),boss.getPosition(),2150,25,62,0,2,30);healBoss(.05,.07,h.npc.getHp()/(double)Math.max(1,h.npc.getMaxHp()));}
                int sx=x(h.npc.getPosition()),sy=y(h.npc.getPosition()),px=x(session.player.getPosition()),py=y(session.player.getPosition());
                if(Math.max(Math.abs(sx-px),Math.abs(sy-py))>1){int dx=Integer.signum(px-sx),dy=Integer.signum(py-sy);if(free(sx+dx,sy+dy)&&!onDung(sx+dx,sy+dy))h.npc.getMovement().teleport(tile(sx+dx,sy+dy));}
                else if(--h.delay<=0){h.delay=6;h.npc.animate(anim(9587));typedFrom(h.npc,AttackStyle.SLASH,17,0,0);}
            }else if(h.source==11726){
                if(h.flight>0){if(--h.flight==0){int[] to=ARCANE[h.location++%ARCANE.length];h.npc.getMovement().teleport(tile(to[0],to[1]));h.npc.animate(anim(9596));h.delay=5;}continue;}
                if(--h.delay<=0){h.delay=3;h.charges++;graphic(h.npc.getPosition(),2149);if(h.charges>=9){
                    projectile(h.npc.getPosition(),session.player.getPosition(),2149,0,0,0,20,90);
                    later(2,()->{int damage=Random.get(32,65);if(session.player.getPrayer().isActive(Prayer.PROTECT_FROM_MAGIC))damage=(int)Math.floor(damage*.33);fixedHit(damage);});flyAway(h);
                }}
            }else if(h.source==11727&&--h.delay<=0){h.delay=4;h.npc.animate(anim(9594));projectile(h.npc.getPosition(),session.player.getPosition(),2152,10,31,60,16,2);typedFrom(h.npc,AttackStyle.RANGED,5,2,0);}
        }
    }
    private void flyAway(Helper h){h.npc.animate(anim(9597));h.charges=0;h.moderate=0;h.flight=2;}
    private void typedFrom(NPC source,AttackStyle style,int max,int delay,double prayerMultiplier){
        if(!live()||source.isRemoved())return;
        Hit hit=new Hit(source,style).randDamage(max).delay(delay);
        if(prayerMultiplier>0){boolean protect=style==AttackStyle.MAGIC?session.player.getPrayer().isActive(Prayer.PROTECT_FROM_MAGIC):session.player.getPrayer().isActive(Prayer.PROTECT_FROM_MISSILES);
            if(protect)hit.fixedDamage(Random.get((int)Math.floor(max*prayerMultiplier)));hit.ignorePrayer();}
        hit.preDamage(p->{if(!valid(p.player))hit.block().nullify();});session.player.hit(hit);
    }
    private void healBoss(double min,double max,double factor){
        int cap=(int)Math.floor(maxHp*1.15);int amount=Random.get((int)Math.floor(maxHp*min*factor),(int)Math.floor(maxHp*max*factor));
        boss.setHp(Math.min(cap,boss.getHp()+amount));if(boss.getHp()>=cap&&rules.shieldTicks>0)rules.shieldTicks=Math.min(4,rules.shieldTicks);
    }
    private void eggAttack(){
        boss.animate(anim(9578));List<int[]> choices=new ArrayList<>(Arrays.asList(EGGS));Collections.shuffle(choices);
        for(int i=0;i<choices.size();i++){
            int[] p=choices.get(i);if(onDung(p[0],p[1]))continue;final int source=i<2?11729:11728;final int x=p[0],y=p[1];
            projectile(tile(3551,5408),tile(x,y),source==11729?2164:2165,86,0,70,14,50);
            later(3,()->{NPC egg=actor(source,x,y);passiveCombat(egg,source,-1,()->graphic(egg.getPosition(),2166));egg.animate(anim(8630));eggs.add(new Egg(egg,source,cycle+16));if(egg.getPosition().equals(session.player.getPosition()))fixedHit(2);});
        }
    }
    private void tickEggs(){
        for(Egg egg:new ArrayList<>(eggs)){
            if(egg.npc.isRemoved()){eggs.remove(egg);continue;}
            if(cycle>=egg.explode){Position p=egg.npc.getPosition().copy();remove(egg.npc);eggs.remove(egg);
                if(egg.source==11729){graphic(p,2166);spawnHelper(11727,x(p),y(p));}
                else{graphic(p,2156);if(p.isWithinDistance(session.player.getPosition(),1))fixedHit(8);}
            }
        }
    }
    private void dungAttack(){
        attackTicks+=7;graphic(session.player.getPosition(),2146);session.player.sendMessage("Kephri gathers dung. Move to choose where the wall lands!");
        later(3,()->{
            boss.animate(anim(9578));int px=x(session.player.getPosition()),py=y(session.player.getPosition()),rawX=px-3551,rawY=py-5408,dx=rawX,dy=rawY;
            if(Math.abs(Math.abs(dx)-Math.abs(dy))<=1){dx=Integer.signum(dx);dy=Integer.signum(dy);}
            else if(Math.abs(dx)>Math.abs(dy)){dx=Integer.signum(dx);dy=0;}else{dy=Integer.signum(dy);dx=0;}
            if(dx==0&&dy==0)dx=-1;
            int steps=Math.max(0,8-Math.max(Math.abs(px-3551),Math.abs(py-5408))),ex=px,ey=py;
            for(int n=0;n<steps;n++){if(!KephriRules.inArena(ex+dx,ey+dy)||!free(ex+dx,ey+dy)||onDung(ex+dx,ey+dy))break;ex+=dx;ey+=dy;}
            session.player.animate(anim(9799));session.player.getMovement().teleport(tile(ex,ey));
            int sx=3551+Math.max(-2,Math.min(2,rawX)),sy=5408+Math.max(-2,Math.min(2,rawY));int n=0;
            for(int[] cell:KephriRules.dungPath(sx,sy,ex,ey)){
                final int tx=cell[0],ty=cell[1];if(!KephriRules.inArena(tx,ty)||!free(tx,ty))continue;graphic(tile(tx,ty),2145);
                later(++n<=3?4:5,()->addDung(tx,ty));
            }
        });
    }
    private boolean onDung(int x,int y){return dung.contains(tile(x,y));}
    private void addDung(int x,int y){Position p=tile(x,y);if(dung.add(p))objects.add(new GameObject(NearRealityRaidAssets.object(45504),p.getX(),p.getY(),p.getZ(),10,Random.get(3)).spawn());}
    private void clearHelpers(){for(Helper h:helpers)remove(h.npc);helpers.clear();}
    private void clearEggs(){for(Egg e:eggs)remove(e.npc);eggs.clear();}
    @Override public void close(){clearHelpers();clearEggs();dung.clear();super.close();}
    private static final class Helper {final NPC npc;final int source;int age,delay=5,charges,moderate,flight,location=1;Helper(NPC npc,int source){this.npc=npc;this.source=source;}}
    private static final class Egg {final NPC npc;final int source,explode;Egg(NPC n,int source,int explode){npc=n;this.source=source;this.explode=explode;}}
}
