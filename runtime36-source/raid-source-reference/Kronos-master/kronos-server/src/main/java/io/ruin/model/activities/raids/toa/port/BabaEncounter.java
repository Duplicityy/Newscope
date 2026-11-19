package io.ruin.model.activities.raids.toa.port;

import io.ruin.api.utils.Random;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.prayer.Prayer;
import java.util.*;

/** Base Ba-ba patterns translated from Near Reality f577de7; optional invocations remain off. */
public final class BabaEncounter extends NativeToaEncounter {
    private static final int[][][] RUBBLE={
        {{3798,5413},{3798,5407},{3798,5401},{3803,5401},{3803,5413},{3805,5407}},
        {{3816,5413},{3812,5413},{3812,5406},{3812,5401},{3817,5401},{3810,5405}}};
    private final List<Rock> rolling=new ArrayList<>();
    private final List<Rubble> rubble=new ArrayList<>();
    private final List<NPC> baboons=new ArrayList<>();
    private int nextAttack=6,shadowCycle=2,boulderStage,boulderTick=-1,lastCracked=Random.get(4),nextBaboon=-1;
    private boolean throwingRock;
    private static final class Rock{final NPC npc;int safeUntil;Rock(NPC n){npc=n;}}
    private static final class Rubble{final NPC npc;final GameObject block;Rubble(NPC n,GameObject b){npc=n;block=b;}}
    BabaEncounter(TombsOfAmascutRaid.Session s){super(s);}
    public static void install(){TombsOfAmascutRaid.register(ToaRoom.BABA,new TombsOfAmascutRaid.Factory(){
        public List<String> missingAssets(){return BabaEncounter.missingAssets();}
        public ToaEncounter create(TombsOfAmascutRaid.Session s){return new BabaEncounter(s);}
    });}
    public static List<String> missingAssets(){
        List<String> out=assets(ToaRoom.BABA,new int[]{11778,11781,11782,11783,11784},
                new int[]{9752,9743,9744,9745,9748,9749,9799,9518,1114},new int[]{1103,1447,1446,2111,2243,2244,2245,2250,1463},new int[]{42838});
        for(int id:new int[]{11778,11781,11782,11783})if(NearRealityRaidAssets.combatInfo(id)==null)out.add("combat stats "+id);
        return out;
    }
    @Override public void spawn(){mainBoss(11778,3815,5406,true);boss.animate(anim(9752));}
    private boolean touching(NPC n,int distance){
        int dx=Math.max(0,Math.max(n.getAbsX()-session.player.getAbsX(),session.player.getAbsX()-(n.getAbsX()+n.getSize()-1)));
        int dy=Math.max(0,Math.max(n.getAbsY()-session.player.getAbsY(),session.player.getAbsY()-(n.getAbsY()+n.getSize()-1)));
        return Math.max(dx,dy)<=distance;
    }
    @Override protected void incoming(Hit hit){
        super.incoming(hit);
        // Reaching Ba-ba beyond the boulder start permits the authored early cancellation.
        if(!hit.isNullified()&&boulderTick>=5&&hit.attacker!=null&&hit.attacker.player==session.player&&x(session.player.getPosition())>=3815)endBoulders();
    }
    @Override protected void pulse(){
        moveRocks();
        if(boulderTick>=0){boulders();return;}
        if(boulderStage<2&&boss.getHp()*100<=maxHp*(boulderStage==0?66:33)){
            boulderStage++;boulderTick=0;clearPending();clearRubble();movementBlocked=true;invulnerable=true;
            boss.resetActions(true,true);boss.animate(anim(9748));return;
        }
        if(nextBaboon<0&&boss.getHp()*4<=maxHp*3)nextBaboon=cycle+1;
        if(nextBaboon>0&&cycle>=nextBaboon){spawnBaboon();nextBaboon=cycle+325;}
        tickBaboons();
        if(--nextAttack>0||movementBlocked||!touching(boss,1))return;
        nextAttack=6;
        if(--shadowCycle<=0){shadowCycle=5;shadow();return;}
        if(shadowCycle==3){dropRubble();later(13,this::throwRock);}
        boss.animate(anim(9743));
        Hit hit=new Hit(boss,AttackStyle.SLASH).randDamage(24).ignorePrayer();
        hit.postDefend(target->{if(target.player!=null&&target.player.getPrayer().isActive(Prayer.PROTECT_FROM_MELEE))hit.damage=(int)(hit.damage*.20);});
        hit.preDamage(target->{if(!valid(target.player))hit.block().nullify();});session.player.hit(hit);
    }
    private void shadow(){
        Position centre=session.player.getPosition().copy();boolean under=touching(boss,0);int delay=under?2:4;
        movementBlocked=true;boss.resetActions(true,true);graphic(centre,under?2111:1447);
        session.player.sendMessage("A shadow gathers beneath you. Move away!");
        for(int d=1;d<=2;d++)for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
            graphic(tile(x(centre)+dir[0]*d,y(centre)+dir[1]*d),d==1&&!under?1446:2111);
        later(delay,()->{
            boss.animate(anim(9743));Position p=session.player.getPosition();int dx=x(p)-x(centre),dy=y(p)-y(centre);
            for(int sx=-2;sx<=2;sx++)for(int sy=-2;sy<=2;sy++)if(!((Math.abs(sx)==2&&sy!=0)||(Math.abs(sy)==2&&sx!=0)))graphic(tile(x(centre)+sx,y(centre)+sy),1103);
            if(Math.abs(dx)<=2&&Math.abs(dy)<=2&&!((Math.abs(dx)==2&&dy!=0)||(Math.abs(dy)==2&&dx!=0)))
                fixedHit((int)(17*(1-.1*Math.max(Math.abs(dx),Math.abs(dy))))+Random.get(5));
            movementBlocked=false;nextAttack=3;
        });
    }
    private void dropRubble(){
        Position marked=session.player.getPosition().copy();graphic(marked,2250);
        List<int[]> chosen=new ArrayList<>();
        for(int[][] bank:RUBBLE){List<int[]> options=new ArrayList<>(Arrays.asList(bank));Collections.shuffle(options);for(int[] p:options)if(free(p[0],p[1])){chosen.add(p);graphic(tile(p[0],p[1]),2250);break;}}
        later(6,()->{
            if(session.player.getPosition().equals(marked))fixedHit(19+Random.get(5));
            clearRubble();
            for(int[] at:chosen){
                NPC n=actor(11784,at[0],at[1]);Position p=tile(at[0],at[1]);
                GameObject block=new GameObject(NearRealityRaidAssets.object(42838),p.getX(),p.getY(),p.getZ(),10,2).spawn();objects.add(block);
                rubble.add(new Rubble(n,block));
                // A delayed landing must not leave the player trapped under its clipping.
                int px=x(session.player.getPosition()),py=y(session.player.getPosition());
                if(px>=at[0]&&px<at[0]+3&&py>=at[1]&&py<at[1]+3)moveAside(at[0],at[1]);
            }
        });
    }
    private void moveAside(int originX,int originY){
        for(int radius=1;radius<5;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++)
            if(free(originX+dx,originY+dy)){session.player.getMovement().teleport(tile(originX+dx,originY+dy));return;}
    }
    private void throwRock(){
        if(boulderTick>=0)return;throwingRock=true;boss.animate(anim(9744));session.player.sendMessage("Ba-ba throws a large boulder. Stand beside rubble!");
        projectile(boss.getPosition(),session.player.getPosition(),2244,100,31,90,40,120);
        later(7,()->{
            if(boulderTick>=0)return;int damage=39;
            for(Rubble r:rubble)if(!r.npc.isRemoved()&&touching(r.npc,1)){damage=(int)(39*.05);break;}
            graphic(session.player.getPosition(),1463);fixedHit(damage);throwingRock=false;clearRubble();
        });
    }
    private void boulders(){
        int t=boulderTick++;
        if(t==3)boss.getMovement().teleport(tile(3815,5406));
        if(t==4){
            int py=y(session.player.getPosition());int tx=3798;
            if(free(tx,py))session.player.getMovement().teleport(tile(tx,py));
            session.player.animate(anim(9799));invulnerable=false;
        }
        if(t>=5&&t<85&&(t-5)%8==0){
            lastCracked=Math.max(0,Math.min(4,lastCracked+Random.get(4)-2));boss.animate(anim(9749));
            int cracked=lastCracked;
            later(2,()->{for(int lane=0;lane<5;lane++)spawnRock(lane==cracked,3813,5401+lane*3);});
        }
        if(t>=87)endBoulders();
    }
    private void spawnRock(boolean cracked,int sx,int sy){
        NPC n=actor(cracked?11783:11782,sx,sy);passiveCombat(n,cracked?11783:11782,cracked?1:-1,()->{});
        if(cracked){HitListener listener=n.hitListener;listener.preDefend(hit->{
            if(hit.attacker==null||!valid(hit.attacker.player)){hit.block().nullify();return;}
            if(hit.attackStyle==AttackStyle.RANGED)hit.ignoreDefence().fixedDamage(1);
        });}
        rolling.add(new Rock(n));
    }
    private void moveRocks(){
        Iterator<Rock> it=rolling.iterator();while(it.hasNext()){
            Rock r=it.next();NPC n=r.npc;if(n.isRemoved()){it.remove();continue;}
            int sx=x(n.getPosition()),sy=y(n.getPosition()),px=x(session.player.getPosition()),py=y(session.player.getPosition());
            if(cycle>=r.safeUntil&&px>=sx&&px<=sx+1&&py>=sy&&py<=sy+2){
                int tx=Math.max(3798,px-6);if(free(tx,py))session.player.getMovement().teleport(tile(tx,py));
                session.player.animate(anim(1114));fixedHit(14);r.safeUntil=cycle+2;
            }
            if(sx<=3797||!free(sx-1,sy)){remove(n);it.remove();continue;}
            n.animate(anim(9518));n.getMovement().teleport(tile(sx-1,sy));
        }
    }
    private void endBoulders(){boulderTick=-1;movementBlocked=false;invulnerable=false;nextAttack=6;for(Rock r:rolling)remove(r.npc);rolling.clear();}
    private void spawnBaboon(){
        baboons.removeIf(NPC::isRemoved);if(baboons.size()>=2)return;
        int sx=3808+Random.get(5),sy=5404+Random.get(8);if(!free(sx,sy))return;
        NPC n=actor(11781,sx,sy);passiveCombat(n,11781,-1,()->{});baboons.add(n);
    }
    private void tickBaboons(){
        if(cycle%6!=0)return;
        for(NPC n:baboons)if(!n.isRemoved()){
            n.animate(anim(9745));projectile(n.getPosition(),session.player.getPosition(),2243,36,36,67,8,34);
            typedHit(AttackStyle.RANGED,12,2);
        }
    }
    private void clearRubble(){for(Rubble r:rubble){remove(r.npc);r.block.remove();objects.remove(r.block);}rubble.clear();}
    @Override protected void bossDied(){endBoulders();clearRubble();for(NPC n:baboons)remove(n);super.bossDied();}
    @Override public void close(){clearRubble();super.close();rolling.clear();baboons.clear();}
}
