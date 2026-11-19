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

/** Native no-invocation Akkha styles, shadows, memory, trails and final stand; Savions NR f577de7. */
public final class AkkhaEncounter extends NativeToaEncounter {
    private static final int[][] SHADOWS={{3685,5412},{3685,5401},{3674,5401},{3674,5412}};
    private static final int[][] SYMBOLS={{3682,5409,45871},{3682,5406,45869},{3679,5406,45870},{3679,5409,45868}};
    private static final int[] QUAD_GFX={2257,2256,2259,2258},TRAIL_IDS={11801,11800,11803,11802};
    private final NPC[] shadows=new NPC[4],fake=new NPC[4];
    private final int[] vulnerable=new int[4],shadowTimer=new int[4];
    private final List<Orb> orbs=new ArrayList<>();
    private final List<Trail> trails=new ArrayList<>();
    private int style,nextAttack=6,attackCycles=5+Random.get(2),specialIndex=Random.get(1),shadowPhase;
    private int memoryTick=-1,trailUntil,lastX,lastY,finalHits,finalIndex;
    private int[] memory;
    private boolean finalPhase;
    private static final class Orb{final NPC npc;final int dx,dy;Orb(NPC n,int dx,int dy){npc=n;this.dx=dx;this.dy=dy;}}
    private static final class Trail{final NPC npc;final int expiry;Trail(NPC n,int expiry){npc=n;this.expiry=expiry;}}
    AkkhaEncounter(TombsOfAmascutRaid.Session s){super(s);}
    public static void install(){TombsOfAmascutRaid.register(ToaRoom.AKKHA,new TombsOfAmascutRaid.Factory(){
        public List<String> missingAssets(){return AkkhaEncounter.missingAssets();}
        public ToaEncounter create(TombsOfAmascutRaid.Session s){return new AkkhaEncounter(s);}
    });}
    public static List<String> missingAssets(){
        List<String> out=assets(ToaRoom.AKKHA,new int[]{11789,11791,11792,11795,11797,11798,11800,11801,11802,11803,11804},
                new int[]{9770,9772,9774,9777,9778,9779,9784,9785,9792},
                new int[]{2253,2255,2256,2257,2258,2259,2260},new int[]{45868,45869,45870,45871});
        for(int id:new int[]{11789,11795,11797})if(NearRealityRaidAssets.combatInfo(id)==null)out.add("combat stats "+id);
        return out;
    }
    @Override public void spawn(){mainBoss(11789,3673,5407,true);lastX=x(session.player.getPosition());lastY=y(session.player.getPosition());}
    public static int quadrant(int x,int y){return x>=3681?(y>=5408?0:1):(y>=5408?3:2);}
    private boolean protectedQuadrant(){NPC shadow=shadows[quadrant(x(boss.getPosition())+boss.getSize()/2,y(boss.getPosition())+boss.getSize()/2)];return shadow!=null&&!shadow.isRemoved();}
    @Override protected void incoming(Hit h){
        super.incoming(h);if(h.isNullified()||h.type==HitType.HEAL)return;
        boolean styleAllowed=finalPhase?(h.attackStyle!=null&&h.attackStyle.isMelee()):
                style==0?h.attackStyle==AttackStyle.MAGIC:style==1?h.attackStyle!=null&&h.attackStyle.isMelee():h.attackStyle==AttackStyle.RANGED;
        if(!styleAllowed||(!finalPhase&&protectedQuadrant()))h.block().nullify();
    }
    @Override protected void pulse(){
        if(finalPhase){finalOrbs();return;}
        shadows();trails();
        if(shadowPhase<4&&boss.getHp()*100<=maxHp*(80-shadowPhase*20)){spawnShadows(shadowPhase++==0);}
        if(memoryTick>=0){memory();return;}
        if(--nextAttack>0)return;nextAttack=6;
        if(style==0&&!touching())return;
        if(--attackCycles<=0){
            attackCycles=5+Random.get(2);
            if(specialIndex++%2==0)startMemory();else{boss.animate(anim(9778));trailUntil=cycle+22;session.player.sendMessage("Magical orbs begin to materialise behind you!");}
            changeStyle();return;
        }
        AttackStyle attack=style==0?AttackStyle.SLASH:style==1?AttackStyle.RANGED:AttackStyle.MAGIC;
        boss.animate(anim(style==0?9770:style==1?9772:9774));
        if(style!=0)projectile(boss.getPosition(),session.player.getPosition(),style==1?2255:2253,101,34,0,12,30);
        Hit hit=new Hit(boss,attack).randDamage(22).ignorePrayer().delay(style==0?1:2);
        hit.postDefend(victim->{Prayer protection=attack.isMelee()?Prayer.PROTECT_FROM_MELEE:attack==AttackStyle.RANGED?Prayer.PROTECT_FROM_MISSILES:Prayer.PROTECT_FROM_MAGIC;
            if(victim.player!=null&&victim.player.getPrayer().isActive(protection))hit.damage=(int)(hit.damage*(attack.isMelee()?.25:.15));});
        hit.preDamage(victim->{if(!valid(victim.player))hit.block().nullify();});session.player.hit(hit);
    }
    private boolean touching(){int dx=Math.max(0,Math.max(boss.getAbsX()-session.player.getAbsX(),session.player.getAbsX()-(boss.getAbsX()+boss.getSize()-1)));
        int dy=Math.max(0,Math.max(boss.getAbsY()-session.player.getAbsY(),session.player.getAbsY()-(boss.getAbsY()+boss.getSize()-1)));return Math.max(dx,dy)<=1;}
    private void changeStyle(){
        style=(style+1)%3;boss.transformVisual(NearRealityRaidAssets.npc(style==0?11789:style==1?11791:11792));
        session.player.sendMessage(style==0?"Akkha attacks with melee; use magic.":style==1?"Akkha attacks with ranged; use melee.":"Akkha attacks with magic; use ranged.");
    }
    private void spawnShadows(boolean first){
        session.player.sendMessage(first?"Shadows appear throughout the room!":"The shadows are restored!");
        for(int i=0;i<4;i++){
            if(shadows[i]==null||shadows[i].isRemoved()){
                final int index=i;NPC n=actor(11797,SHADOWS[i][0],SHADOWS[i][1]);shadows[i]=n;vulnerable[i]=first?0:2;
                passiveCombat(n,11797,-1,()->shadowDefeated(index));
                n.hitListener=new HitListener().preDefend(h->{if(h.attacker==null||!valid(h.attacker.player)||vulnerable[index]!=0)h.block().nullify();})
                        .preDamage(h->{if(!live()||vulnerable[index]!=0)h.block().nullify();}).postDamage(h->session.recordDamage(h,1));
            }else{shadows[i].setHp(shadows[i].getMaxHp());if(vulnerable[i]!=2)vulnerable[i]=0;}
            shadowTimer[i]=0;
        }
    }
    private void shadowDefeated(int index){for(int i=0;i<4;i++)if(i!=index&&vulnerable[i]!=2)vulnerable[i]=1;}
    private void shadows(){
        if(memoryTick>=0)return;
        for(int i=0;i<4;i++)if(shadows[i]!=null&&!shadows[i].isRemoved()&&++shadowTimer[i]>=50){shadowTimer[i]=0;blast(i);}
    }
    private void startMemory(){
        memory=new int[4];memory[0]=Random.get(3);
        for(int i=1;i<4;i++)memory[i]=(memory[i-1]+(Random.get(1)==0?3:1))%4;
        memoryTick=0;movementBlocked=true;invulnerable=true;boss.resetActions(true,true);boss.animate(anim(9777));
        session.player.sendMessage("Remember the glowing quadrants. Repeat the safe sequence!");
    }
    private void memory(){
        int t=memoryTick++;
        if(t==3)boss.animate(anim(9784));
        if(t>=4&&t<=10&&(t-4)%2==0){int[] s=SYMBOLS[memory[(t-4)/2]];Position p=tile(s[0],s[1]);
            GameObject symbol=new GameObject(NearRealityRaidAssets.object(s[2]),p.getX(),p.getY(),p.getZ(),10,0).spawn();objects.add(symbol);
            later(1,()->{symbol.remove();objects.remove(symbol);});}
        if(t>=12&&t<=24&&(t-12)%4==0){int safe=memory[(t-12)/4];for(int q=0;q<4;q++)if(q!=safe)blast(q);}
        if(t>=27){boss.getMovement().teleport(tile(3680,5406));boss.animate(anim(9785));memoryTick=-1;movementBlocked=false;invulnerable=false;nextAttack=6;}
    }
    private void blast(int quadrant){
        for(int sx=3670;sx<=3691;sx++)for(int sy=5395;sy<=5419;sy++)if(quadrant(sx,sy)==quadrant&&free(sx,sy))graphic(tile(sx,sy),QUAD_GFX[quadrant]);
        if(quadrant(x(session.player.getPosition()),y(session.player.getPosition()))==quadrant)fixedHit(14+Random.get(2));
    }
    private void trails(){
        Position p=session.player.getPosition();int px=x(p),py=y(p);
        if(cycle<trailUntil&&cycle>=trailUntil-20&&(px!=lastX||py!=lastY)&&free(lastX,lastY)){
            int id=TRAIL_IDS[quadrant(lastX,lastY)];trails.add(new Trail(actor(id,lastX,lastY),cycle+8));
        }
        lastX=px;lastY=py;
        Iterator<Trail> it=trails.iterator();while(it.hasNext()){
            Trail trail=it.next();boolean collision=trail.npc.getPosition().equals(p);
            if(collision){graphic(p,QUAD_GFX[quadrant(px,py)]);fixedHit(13+Random.get(2));}
            if(collision||cycle>=trail.expiry){remove(trail.npc);it.remove();}
        }
    }
    @Override protected void bossDied(){
        if(finalPhase){for(Orb orb:orbs)remove(orb.npc);for(NPC n:fake)remove(n);super.bossDied();return;}
        clearPending();for(NPC n:shadows)remove(n);for(Trail trail:trails)remove(trail.npc);trails.clear();
        remove(boss);finalPhase=true;dead=false;invulnerable=false;movementBlocked=true;memoryTick=-1;finalIndex=Random.get(3);finalHits=0;
        mainBoss(11795,SHADOWS[finalIndex][0],SHADOWS[finalIndex][1],false);
        for(int i=0;i<4;i++)if(i!=finalIndex){fake[i]=actor(11798,SHADOWS[i][0],SHADOWS[i][1]);fake[i].attackNpcListener=(p,n,message)->false;}
        boss.hitListener=new HitListener().preDefend(this::incoming).preDamage(this::incoming).postDamage(h->{
            session.recordDamage(h,1);
            if(h.damage>0&&++finalHits>=3){finalHits=0;later(1,this::moveFinalShadow);}
        });
        session.player.sendMessage("Akkha makes his final stand. Use melee and avoid the unstable orbs!");
    }
    private void moveFinalShadow(){
        int next=(finalIndex+1+Random.get(2))%4;
        if(fake[next]!=null){fake[next].getMovement().teleport(tile(SHADOWS[finalIndex][0],SHADOWS[finalIndex][1]));fake[finalIndex]=fake[next];fake[next]=null;}
        boss.animate(anim(9784));boss.getMovement().teleport(tile(SHADOWS[next][0],SHADOWS[next][1]));boss.animate(anim(9785));finalIndex=next;
    }
    private void finalOrbs(){
        if(cycle%3==0){List<int[]> paths=new ArrayList<>(Arrays.asList(ToaGeometry.UNSTABLE_ORBS));Collections.shuffle(paths);
            for(int i=0;i<Math.min(6,paths.size());i++){int[] p=paths.get(i);orbs.add(new Orb(actor(11804,p[0],p[1]),p[2],p[3]));}}
        Iterator<Orb> it=orbs.iterator();while(it.hasNext()){
            Orb orb=it.next();Position at=orb.npc.getPosition();boolean collision=at.equals(session.player.getPosition());
            if(collision)fixedHit(13+Random.get(2));
            int nx=x(at)+orb.dx,ny=y(at)+orb.dy;
            if(collision||!free(nx,ny)){graphic(at,2260);remove(orb.npc);it.remove();}else orb.npc.getMovement().teleport(tile(nx,ny));
        }
    }
    @Override public void close(){super.close();orbs.clear();trails.clear();}
}
