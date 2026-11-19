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

/**
 * Native core Zebak encounter adapted from Savions' Near Reality f577de7 source.
 * Level0/no optional invocations. The parent activity deliberately calls this an
 * isolated encounter beta: this is not a completed raid and does not mint raid loot.
 */
final class ZebakEncounter implements ToaEncounter {
    private final TombsOfAmascutRaid.Session session;
    private final List<NPC> actors=new ArrayList<>();
    private final List<GameObject> poison=new ArrayList<>();
    private final List<GameObject> boulderBlocks=new ArrayList<>();
    private final List<NPC> boulders=new ArrayList<>(),jugs=new ArrayList<>();
    private final Set<NPC> movingJugs=Collections.newSetFromMap(new IdentityHashMap<NPC,Boolean>());
    private final List<Wave> waves=new ArrayList<>();
    private final List<Due> due=new ArrayList<>();
    private NPC boss,tail;
    private int cycle,nextAttack=7,specialCount,specialTick=-1;
    private int maxHp,bleedUntil,lastX,lastY;
    private boolean dead,closed,enraged,mage=Random.get(1)==0,jugSpecial=Random.get(1)==0,specialJugs,wavesSouth=Random.get(1)==0;
    private int waveGap=-1;
    private static final class Due {final int at;final Runnable task;Due(int at,Runnable task){this.at=at;this.task=task;}}
    private static final class Wave {final NPC actor;final int direction;int remaining=23;Wave(NPC n,int d){actor=n;direction=d;}}

    ZebakEncounter(TombsOfAmascutRaid.Session s){session=s;}
    static List<String> missingAssets(){
        List<String> result=new ArrayList<>();
        int[] npcs={11730,11731,11735,11736,11737,11738};
        String[] names={"Zebak","Zebak's tail","Jug","Moving jug","Boulder","Wave"};
        for(int i=0;i<npcs.length;i++)if(NearRealityRaidAssets.npc(npcs[i])<0)result.add("NPC "+npcs[i]+" "+names[i]);
        if(NearRealityRaidAssets.combatInfo(11730)==null)result.add("source Zebak combat statistics");
        if(NearRealityRaidAssets.combatInfo(11735)==null)result.add("source jug combat statistics");
        if(NearRealityRaidAssets.region(15700)<0)result.add("source Zebak room15700");
        for(int id:new int[]{9620,9621,9624,9625,9628,9629,9630,9631,9634,9635,4177,834})
            if(NearRealityRaidAssets.animation(id)<0)result.add("animation "+id);
        for(int id:new int[]{2172,2173,2176,2178,2181,2184,2185,2186,2187,2192,2193,2194,2195,68,95,131,1103})
            if(NearRealityRaidAssets.graphic(id)<0)result.add("graphic "+id);
        if(NearRealityRaidAssets.object(45570)<0)result.add("poison pool45570");
        if(NearRealityRaidAssets.object(43876)<0)result.add("boulder collision43876");
        return result;
    }
    private static int id(int source,String name){int value=NearRealityRaidAssets.npc(source);if(value<0)throw new IllegalStateException("Missing NPC "+source+" "+name);return value;}
    private static int anim(int source){return NearRealityRaidAssets.animation(source);}
    private static int gfx(int source){return NearRealityRaidAssets.graphic(source);}
    private Position tile(int x,int y){return session.tile(x,y);}
    private int sourceX(Position p){return session.sourceX(p);}
    private int sourceY(Position p){return session.sourceY(p);}
    private boolean floor(int x,int y){
        if(x<3926||x>3942||y<5398||y>5418)return false;
        Position p=tile(x,y);Tile t=Tile.get(p.getX(),p.getY(),0,false);
        return t!=null && t.isFloorFreeCheckDecor();
    }
    private NPC actor(int source,String name,int x,int y){
        NPC n=new NPC(id(source,name)).spawn(tile(x,y));
        n.sixSystemsRewardless=true;
        if(n.getCombat()!=null){n.getCombat().setAllowRespawn(false);n.getCombat().reset();}
        session.map.addNpc(n);actors.add(n);return n;
    }
    public void spawn(){
        boss=actor(11730,"Zebak",3918,5404);tail=actor(11731,"Zebak's tail",3909,5403);
        npc_combat.Info info=NearRealityRaidAssets.combatInfo(11730);
        if(info==null)throw new IllegalStateException("Missing donor Zebak combat info");
        // The adapter supplies a fresh copy; these edits never alter shared NPC definitions.
        info.attack_ticks=7;info.attack_animation=anim(9624);info.death_animation=anim(9634);
        info.respawn_ticks=-1;info.death_ticks=3;maxHp=info.hitpoints;
        boss.installCombat(new Combat(),info);
        boss.attackNpcListener=(p,n,message)->valid(p);
        boss.hitListener=new HitListener().preDefend(this::guard).preDamage(this::guard).postDamage(h->session.recordDamage(h,1.5));
        lastX=sourceX(session.player.getPosition());lastY=sourceY(session.player.getPosition());
    }
    private boolean valid(Player p){return !dead&&!closed&&p==session.player&&session.valid();}
    private void guard(Hit h){
        if(h.type==HitType.HEAL&&h.attacker==null)return;
        if(h.attacker==null||!valid(h.attacker.player)){h.block().nullify();h.attacker=null;}
    }
    private boolean live(){return !closed&&!dead&&boss!=null&&!boss.isRemoved()&&session.valid();}
    private void later(int delay,Runnable task){due.add(new Due(cycle+delay,task));}
    public void tick(){
        if(!live())return;cycle++;
        List<Due> ready=new ArrayList<>();for(Due task:due)if(task.at<=cycle)ready.add(task);
        due.removeAll(ready);for(Due task:ready){if(!live())return;task.task.run();}
        Player p=session.player;int x=sourceX(p.getPosition()),y=sourceY(p.getPosition());
        if(cycle<bleedUntil && (x!=lastX||y!=lastY))damage(5,12,false);
        lastX=x;lastY=y;
        if(onPoison(x,y)){damage(10,17,true);p.poison(2);}
        tickWaves();
        if(!enraged&&boss.getHp()*4<=maxHp){enraged=true;p.sendMessage("Zebak becomes enraged!");}
        if(specialTick>=0){special();return;}
        if(--nextAttack>0)return;
        nextAttack=ZebakRules.attackSpeed(0,enraged);
        if(!enraged&&specialCount<4&&boss.getHp()*100<=maxHp*ZebakRules.specialThreshold(specialCount)){
            specialCount++;specialTick=0;specialJugs=jugSpecial;jugSpecial=!jugSpecial;waveGap=-1;clearHazards();special();return;
        }
        normalAttack();
    }
    private void normalAttack(){
        Player p=session.player;
        if(Random.get(2)==0)mage=!mage;
        // Zebak's model occupies eight tiles; native entity bounds determine melee reach.
        int dx=Math.max(0,Math.max(boss.getAbsX()-p.getAbsX(),p.getAbsX()-(boss.getAbsX()+boss.getSize()-1)));
        int dy=Math.max(0,Math.max(boss.getAbsY()-p.getAbsY(),p.getAbsY()-(boss.getAbsY()+boss.getSize()-1)));
        if(Math.max(dx,dy)<=1&&Random.get(2)==0){
            boss.animate(anim(9621));tail.animate(anim(9620));
            later(1,()->{typedHit(AttackStyle.SLASH,34);if(Random.get(3)==0){bleedUntil=cycle+10;session.player.sendMessage("Zebak's fangs cause you to bleed. Moving worsens the wound.");}});
            return;
        }
        final boolean magic=mage;
        boss.animate(anim(9624));tail.animate(anim(9625));
        projectile(tile(3925,5408),tile(3933,5408),magic?2176:2178,50,175,60,30,60);
        later(3,()->{
            graphic(tile(3933,5408),magic?2186:2185);
            projectile(tile(3933,5408),session.player.getPosition(),magic?2181:2187,175,22,0,1,90);
            later(2,()->{graphic(session.player.getPosition(),magic?131:1103);typedHit(magic?AttackStyle.MAGIC:AttackStyle.RANGED,32);});
        });
    }
    private void typedHit(AttackStyle style,int max){
        if(!live())return;
        Hit hit=new Hit(boss,style).randDamage(max);
        // Validate both when queued and when applied, including map departure/death.
        hit.preDamage(target->{if(!valid(target.player))hit.block().nullify();});session.player.hit(hit);
    }
    private void damage(int min,int max,boolean poisonHit){
        if(!live())return;
        Hit hit=new Hit(boss).fixedDamage(Random.get(min,max)).ignorePrayer().ignoreDefence();
        if(poisonHit)hit.type=HitType.POISON;
        hit.preDamage(target->{if(!valid(target.player))hit.block().nullify();});session.player.hit(hit);
    }
    private void special(){
        int t=specialTick++;
        if(t==0){boss.animate(anim(9624));tail.animate(-1);nextAttack=16;}
        if(specialJugs){
            if(t==5)spawnRoarHazards();
            if(t==33){boss.animate(anim(9628));tail.animate(anim(9629));session.player.sendMessage("Zebak inhales. Hide directly behind a boulder!");}
            if(t==36||t==38||t==40)roar();
            if(t==49){specialTick=-1;clearBoulders();nextAttack=ZebakRules.attackSpeed(0,enraged);}
        }else{
            if(t==4){boss.animate(anim(9630));tail.animate(anim(9631));}
            if(t==5)spawnPoisonAndJugs(16);
            if(t==6){for(int x=3923;x<3944;x+=3)graphic(tile(x,wavesSouth?5397:5419),68);session.player.sendMessage("Waves approach. Move into the gaps!");}
            if(t==13||t==20||t==27)spawnWave();
            if(t==47){specialTick=-1;wavesSouth=!wavesSouth;nextAttack=ZebakRules.attackSpeed(0,enraged);}
        }
    }
    private void spawnRoarHazards(){
        List<Position> options=freeTiles();Collections.shuffle(options);
        for(Position p:options){if(boulders.size()==3)break;boolean apart=true;for(NPC n:boulders)if(Math.abs(sourceY(n.getPosition())-p.getY())<3)apart=false;
            if(apart&&p.getX()<=3937&&p.getY()>=5400&&p.getY()<=5414&&floor(p.getX()+1,p.getY())&&floor(p.getX()+2,p.getY())&&floor(p.getX()+3,p.getY()))
                {boulders.add(actor(11737,"Boulder",p.getX(),p.getY()));Position at=tile(p.getX(),p.getY());
                 boulderBlocks.add(new GameObject(NearRealityRaidAssets.object(43876),at.getX(),at.getY(),at.getZ(),10,0).spawn());}}
        if(boulders.isEmpty()){specialTick=-1;nextAttack=7;return;}
        spawnPoisonAndJugs(6);
        for(NPC b:boulders){int x=sourceX(b.getPosition()),y=sourceY(b.getPosition());
            addPoison(x+2,y);
            // Guarantee an adjacent breakable jug can clear the protected lane.
            if(floor(x+2,y+1))addJug(x+2,y+1);else if(floor(x+2,y-1))addJug(x+2,y-1);
        }
    }
    private List<Position> freeTiles(){List<Position> out=new ArrayList<>();for(int x=3927;x<=3940;x++)for(int y=5400;y<=5416;y++)if(floor(x,y))out.add(new Position(x,y,0));return out;}
    private void spawnPoisonAndJugs(int poisonCount){
        List<Position> options=freeTiles();Collections.shuffle(options);
        for(Position p:options){if(poison.size()>=poisonCount)break;if(boulderAt(p.getX(),p.getY()))continue;addPoison(p.getX(),p.getY());}
        Collections.shuffle(options);int amount=6+Random.get(2);
        for(Position p:options){if(jugs.size()>=amount)break;if(boulderAt(p.getX(),p.getY()))continue;
            addJug(p.getX(),p.getY());
        }
    }
    private void addJug(int x,int y){
        for(NPC old:jugs)if(!old.isRemoved()&&sourceX(old.getPosition())==x&&sourceY(old.getPosition())==y)return;
        NPC jug=actor(11735,"Jug",x,y);jugs.add(jug);
        NPCAction.register(jug,"Push",(p,n)->{if(valid(p))pushJug(n,true);});
        NPCAction.register(jug,"Pull",(p,n)->{if(valid(p))pushJug(n,false);});
        NPCAction.register(jug,"Hit",(p,n)->{if(valid(p))p.getCombat().setTarget(n);});
        npc_combat.Info stats=NearRealityRaidAssets.combatInfo(11735);
        if(stats==null)throw new IllegalStateException("Missing donor jug combat info");
        jug.installCombat(new NPCCombat(){
            public void init(){setAllowRespawn(false);setAllowRetaliate(false);}
            public void follow(){}public boolean attack(){return false;}
            public void startDeath(Hit h){if(!isDead()){setDead(true);breakJug(npc);}}
        },stats);
        jug.attackNpcListener=(p,n,message)->valid(p);
        jug.hitListener=new HitListener().preDefend(this::guard).preDamage(h->{
            guard(h);if(!h.isNullified()&&h.type!=HitType.HEAL)h.fixedDamage(jug.getHp());
        });
    }
    private boolean boulderAt(int x,int y){for(NPC n:boulders)if(!n.isRemoved()&&sourceX(n.getPosition())==x&&sourceY(n.getPosition())==y)return true;return false;}
    private void addPoison(int x,int y){if(!floor(x,y)||onPoison(x,y))return;Position p=tile(x,y);int id=NearRealityRaidAssets.object(45570);poison.add(new GameObject(id,p.getX(),p.getY(),0,10,0).spawn());}
    private boolean onPoison(int x,int y){for(GameObject o:poison)if(sourceX(o.getPosition())==x&&sourceY(o.getPosition())==y)return true;return false;}
    private void breakJug(NPC jug){
        if(jug.isRemoved())return;Position at=jug.getPosition().copy();graphic(at,2192);
        Iterator<GameObject> it=poison.iterator();while(it.hasNext()){GameObject pool=it.next();if(Math.abs(pool.x-at.getX())<=2&&Math.abs(pool.y-at.getY())<=2){graphic(pool.getPosition(),95);pool.remove();it.remove();}}
        jug.remove();jugs.remove(jug);movingJugs.remove(jug);
    }
    private void pushJug(NPC jug,boolean push){
        if(jug.isRemoved()||movingJugs.contains(jug))return;Player p=session.player;int dx=Integer.signum(jug.getAbsX()-p.getAbsX()),dy=Integer.signum(jug.getAbsY()-p.getAbsY());
        if(!push){dx=-dx;dy=-dy;}final int moveX=dx,moveY=dy;
        if(dx==0&&dy==0)return;p.animate(anim(834));movingJugs.add(jug);jug.transformVisual(id(11736,"Moving jug"));
        for(int i=1;i<=24;i++){final int step=i;later(i,()->{if(jug.isRemoved())return;int x=sourceX(jug.getPosition())+moveX,y=sourceY(jug.getPosition())+moveY;
            if(!floor(x,y)||boulderAt(x,y)||step==24){breakJug(jug);return;}jug.getMovement().teleport(tile(x,y));});}
    }
    private void roar(){
        Player p=session.player;int x=sourceX(p.getPosition()),y=sourceY(p.getPosition());boolean protectedTile=false;
        for(NPC b:boulders)if(!b.isRemoved()&&ZebakRules.behindBoulder(x,y,sourceX(b.getPosition()),sourceY(b.getPosition())))protectedTile=true;
        for(int tx=3926;tx<=3942;tx++)for(int ty=5398;ty<=5418;ty++)if(floor(tx,ty))graphic(tile(tx,ty),2184);
        if(!protectedTile){push(1,0,2);damage(20,30,false);}
        // Source roars also break exposed jugs, clearing their local poison.
        for(NPC jug:new ArrayList<>(jugs))breakJug(jug);
    }
    private void spawnWave(){
        int gap=waveGap<0?Random.get(12):12-waveGap;waveGap=waveGap<0?gap:-1;
        for(int x=0;x<21;x++)if(!ZebakRules.waveGap(x,gap,3))waves.add(new Wave(actor(11738,"Wave",3923+x,wavesSouth?5397:5419),wavesSouth?1:-1));
    }
    private void tickWaves(){
        Iterator<Wave> it=waves.iterator();while(it.hasNext()){Wave w=it.next();
            if(w.actor.isRemoved()||--w.remaining<=0){w.actor.remove();it.remove();continue;}
            Position at=w.actor.getPosition();if(at.equals(session.player.getPosition())){push(0,w.direction,4);damage(8,18,false);}
            int x=sourceX(at),y=sourceY(at)+w.direction;w.actor.getMovement().teleport(tile(x,y));
        }
    }
    private void push(int dx,int dy,int distance){
        Player p=session.player;int x=sourceX(p.getPosition()),y=sourceY(p.getPosition());
        for(int i=0;i<distance;i++){if(!floor(x+dx,y+dy))break;x+=dx;y+=dy;}
        p.getMovement().teleport(tile(x,y));p.animate(anim(4177));
    }
    private void graphic(Position p,int source){Player player=session.player;if(!live())return;player.getPacketSender().sendGraphics(gfx(source),0,0,p.getX(),p.getY(),p.getZ());}
    private void projectile(Position from,Position to,int source,int start,int end,int delay,int duration,int increment){new Projectile(gfx(source),start,end,delay,duration,increment,0,0).send(from,to);}
    private void clearActors(List<NPC> list){for(NPC n:list)if(!n.isRemoved())n.remove();list.clear();}
    private void clearBoulders(){clearActors(boulders);for(GameObject block:boulderBlocks)block.remove();boulderBlocks.clear();}
    private void clearHazards(){for(GameObject o:poison)o.remove();poison.clear();clearBoulders();clearActors(jugs);for(Wave w:waves)w.actor.remove();waves.clear();}
    public void close(){if(closed)return;closed=true;due.clear();clearHazards();for(NPC actor:actors)if(!actor.isRemoved())actor.remove();actors.clear();}
    private final class Combat extends NPCCombat {
        public void init(){setAllowRespawn(false);setAllowRetaliate(false);}
        public void follow(){}
        public boolean attack(){return false;}
        public boolean canAttack(Entity target){return target!=null&&valid(target.player);}
        public int getAttackBoundsRange(){return 40;}
        public void startDeath(Hit killHit){
            if(dead||closed)return;dead=true;setDead(true);due.clear();clearHazards();
            boss.animate(anim(9634));tail.animate(anim(9635));session.completed();
        }
    }
}
