package io.ruin.model.activities.raids.toa.port;

import io.ruin.api.utils.Random;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.*;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.prayer.Prayer;
import java.util.*;

/**
 * Native, instance-owned base Wardens, adapted from Near Reality f577de7 (Savions).
 * Coordinate patterns are copied exactly in WardensPatternData. No invocation modifiers,
 * foreign engine objects, replacement player inventories, or fabricated raid loot.
 */
public final class WardensEncounter extends NativeToaEncounter {
    private enum Phase { OBELISK, SHIELD, CORE, FLYING, SKULLS, ENRAGED, TRANSITION, COMPLETE }
    private static final Map<TombsOfAmascutRaid.Session,Integer> FINAL_FORMS=new WeakHashMap<>();
    private final boolean finalRoom;
    private final List<Orb> orbs=new ArrayList<>();
    private final List<NPC> skulls=new ArrayList<>();
    private final List<Phantom> phantoms=new ArrayList<>();
    private final int[] charge=new int[2],fullCharges=new int[2],orbPath=new int[2];
    private Phase phase;
    private NPC obelisk,left,right,core;
    private int attackIn=6,chargeIn=3,orbIn=2,coreHealth=4500,coreUntil,shieldCycles,stoneUntil;
    private int finalForm=11761,shieldForm,shieldHp,floorRotation,skullIndex,skullDeadline,skullsRemaining;
    private int obeliskIn=40,thunderLeft,specialIndex=Random.get(2),specialUntil,wheelRotation;
    private int thunderIn=7,floorIn=10,floorIndex,epoch;
    private boolean canSpecial,thresholdPending,handoff,ownedFreeze;
    private final Set<Position> occupiedThunder=new HashSet<>();
    private static final class Orb {
        final NPC npc;final int side;final Position[] path;int point;
        Orb(NPC npc,int side,Position[] path){this.npc=npc;this.side=side;this.path=path;}
    }
    private static final class Phantom {
        final NPC npc;final int source;int attackIn;boolean magic;int attacks;
        Phantom(NPC npc,int source){this.npc=npc;this.source=source;attackIn=source==11774?8:7;magic=Random.get(1)==0;}
    }
    WardensEncounter(TombsOfAmascutRaid.Session s){
        super(s);finalRoom=s.room==ToaRoom.WARDENS_FINAL;
        Integer inherited=FINAL_FORMS.remove(s);if(inherited!=null)finalForm=inherited;
    }
    public static void install(){
        TombsOfAmascutRaid.register(ToaRoom.WARDENS,factory(false));
        TombsOfAmascutRaid.register(ToaRoom.WARDENS_FINAL,factory(true));
    }
    private static TombsOfAmascutRaid.Factory factory(final boolean last){return new TombsOfAmascutRaid.Factory(){
        public List<String> missingAssets(){return WardensEncounter.missingAssets(last);}
        public ToaEncounter create(TombsOfAmascutRaid.Session s){return new WardensEncounter(s);}
    };}
    public static List<String> missingAssets(){return missingAssets(false);}
    public static List<String> missingAssets(boolean last){
        int[] npcs=last?new int[]{11761,11762,11763,11764,11772,11774,11775,11776,11777}:
                new int[]{11746,11747,11748,11749,11750,11751,11752,11753,11754,11755,11756,11757,11758,11769,11771};
        int[] animations=last?new int[]{9674,9676,9678,9680,9681,9682,9684,9685,9626,9772,9774,9777,9743,9577}:
                new int[]{7571,9735,9662,9663,9659,9661,9660,9670,9672,9667,9713,9714,9715,9723,9724,9725,9726,9727,9728,9729};
        int[] graphics=last?new int[]{1446,1447,2197,2219,2220,2221,2222,2223,2226,2227,2158,2176,2178,2181,2187,2250,2253,2255,1481,2266,2157}:
                new int[]{1447,2210,2212,2214,2215,2198,2204,2206,2208,2224,2225,2241,2240,2234,2235,2236,2237,2238,1605};
        int[] objectIds=last?new int[]{45726,45727,45728,45730,45731,45732,45734,45735,45736,45737,45738}:
                new int[]{45748,45749,45750,45751};
        List<String> out=assets(last?ToaRoom.WARDENS_FINAL:ToaRoom.WARDENS,npcs,animations,graphics,objectIds);
        if(NearRealityRaidAssets.animation(1114)<0)out.add("animation 1114");
        for(int source:last?new int[]{11761,11762,11772}:new int[]{11750,11753,11754,11756,11757,11771})
            if(NearRealityRaidAssets.combatInfo(source)==null)out.add("combat stats "+source);
        // A first-room victory must always have an authored destination ready.
        if(!last)out.addAll(missingAssets(true));
        return out;
    }
    @Override public void spawn(){
        if(finalRoom){phase=Phase.FLYING;setMain(finalForm,finalForm,3934,5152,false);movementBlocked=true;}
        else{
            phase=Phase.OBELISK;setMain(11750,11750,3807,5153,false);visual(boss,11751);obelisk=boss;
            left=actor(11748,3792,5152);right=actor(11749,3820,5152);
            unattackable(left);unattackable(right);
            session.player.sendMessage("Destroy the obelisk. Standing in a charging orb's path prevents its charge.");
        }
    }
    private void unattackable(NPC n){
        n.attackNpcListener=(p,npc,message)->false;
        n.hitListener=new HitListener().preDefend(h->h.block().nullify()).preDamage(h->h.block().nullify());
    }
    private void setMain(int source,int statsSource,int sx,int sy,boolean follows){
        boss=actor(source,sx,sy);npc_combat.Info stats=info(statsSource);maxHp=stats.hitpoints;
        boss.installCombat(new WardenCombat(follows),stats);
        boss.attackNpcListener=(p,n,message)->valid(p)&&!invulnerable&&cycle>=stoneUntil;
        final double pointMultiplier=statsSource==11750?1.5:statsSource==11761||statsSource==11762?2.5:2;
        boss.hitListener=new HitListener().preDefend(this::incoming).preDamage(hit->{
            incoming(hit);if(!hit.isNullified())capFlyingThreshold(hit);
        }).postDamage(hit->session.recordDamage(hit,pointMultiplier));
    }
    @Override protected void incoming(Hit hit){
        super.incoming(hit);
        if(hit.isNullified()||hit.type==HitType.HEAL)return;
        if(cycle<stoneUntil){hit.block().nullify();return;}
        if(phase==Phase.SHIELD){
            boolean magic=shieldForm==11753||shieldForm==11756;
            if(hit.attackStyle!=(magic?AttackStyle.MAGIC:AttackStyle.RANGED))hit.block().nullify();
        }
    }
    /** Clamp at authored thresholds before damage; at most one transition can be armed per tick. */
    private void capFlyingThreshold(Hit hit){
        if(phase!=Phase.FLYING||thresholdPending||hit.damage<=0||hit.type==HitType.HEAL)return;
        int threshold=skullIndex<4?maxHp*(80-skullIndex*20)/100:maxHp*5/100;
        if(boss.getHp()-hit.damage>threshold)return;
        hit.damage=Math.max(0,boss.getHp()-threshold);thresholdPending=true;invulnerable=true;
        ownedLater(1,()->{thresholdPending=false;if(skullIndex<4)sendSkulls(skullIndex++);else startEnrage();});
    }
    private void visual(NPC n,int source){int id=NearRealityRaidAssets.npc(source);if(id<0)throw new IllegalStateException("Missing Warden form "+source);n.transformVisual(id);}
    private void ownedLater(int ticks,Runnable action){final int generation=epoch;later(ticks,()->{if(generation==epoch)action.run();});}
    private void nextPhase(Phase next){epoch++;clearPending();phase=next;thresholdPending=false;}
    @Override protected void pulse(){
        if(cycle%50==0)actors.removeIf(NPC::isRemoved);
        if(cycle<stoneUntil)session.player.animate(anim(cycle<stoneUntil-1?9714:9715));
        switch(phase){
            case OBELISK:tickObelisk();break;
            case SHIELD:tickShield();break;
            case CORE:if(cycle>=coreUntil)recallCore();break;
            case FLYING:if(--attackIn<=0){attackIn=6;floorAttack();}tickPhantoms();break;
            case SKULLS:if(cycle>=skullDeadline)skullFailure();tickPhantoms();break;
            case ENRAGED:tickEnrage();tickPhantoms();break;
            default:break;
        }
    }
    private void tickObelisk(){
        if(--orbIn<=0){orbIn=2;for(int side=0;side<2;side++)spawnOrb(side);}
        for(Iterator<Orb> it=orbs.iterator();it.hasNext();){
            Orb orb=it.next();if(orb.npc.isRemoved()){it.remove();continue;}
            if(orb.npc.getPosition().equals(session.player.getPosition())){fixedHit(3);remove(orb.npc);it.remove();continue;}
            if(orb.point>=orb.path.length){addCharge(orb.side,1);orb.npc.animate(anim(9735));NPC done=orb.npc;ownedLater(1,()->remove(done));it.remove();continue;}
            orb.npc.animate(anim(7571));orb.npc.getMovement().teleport(orb.path[orb.point++]);
        }
        if(--chargeIn<=0){chargeIn=2;addCharge(0,2);addCharge(1,2);}
    }
    private void spawnOrb(int side){
        Position[][] paths=side==0?WardensPatternData.ELIDINIS_ORB_PATH:WardensPatternData.TUMEKEN_ORB_PATH;
        Position[] bends=paths[orbPath[side]++%3];int sx=side==0?3806:3810,sy=5154;
        List<Position> route=new ArrayList<>();
        for(Position bend:bends){while(sx!=bend.getX()||sy!=bend.getY()){
            sx+=Integer.signum(bend.getX()-sx);sy+=Integer.signum(bend.getY()-sy);route.add(tile(sx,sy));
        }}
        NPC n=actor(11769,side==0?3806:3810,5154);unattackable(n);orbs.add(new Orb(n,side,route.toArray(new Position[0])));
    }
    private void addCharge(int side,int amount){
        charge[side]=Math.min(100,charge[side]+amount);if(charge[side]<100)return;
        NPC warden=side==0?left:right;warden.animate(anim(9667));
        if(fullCharges[side]++%2==0){warden.graphics(gfx(2214));rotatingTraps(side);}else{warden.graphics(gfx(2215));energyBall(side);}
        charge[side]=0;
    }
    private GameObject object(int source,Position at,int type,int rotation){
        // Replace only the instance's object of this shape; stacking floor copies retains old clipping.
        for(GameObject owned:objects)if(owned.x==at.getX()&&owned.y==at.getY()&&owned.z==at.getZ()&&owned.type==type){
            if(owned.direction==rotation){owned.setId(NearRealityRaidAssets.object(source));return owned;}
            removeObject(owned);break;
        }
        GameObject existing=io.ruin.model.map.Tile.getObject(-1,at.getX(),at.getY(),at.getZ(),type,-1);
        if(existing!=null&&existing.direction==rotation){existing.setId(NearRealityRaidAssets.object(source));objects.add(existing);return existing;}
        if(existing!=null)existing.remove();
        GameObject o=new GameObject(NearRealityRaidAssets.object(source),at.getX(),at.getY(),at.getZ(),type,rotation).spawn();objects.add(o);return o;
    }
    private void removeObject(GameObject object){if(object!=null){object.remove();objects.remove(object);}}
    private void rotatingTraps(int side){
        Position[] pattern=side==0?WardensPatternData.ELIDINIS_ROTATING_TRAP_LOCATIONS:WardensPatternData.TUMEKEN_ROTATING_TRAP_LOCATIONS;
        final List<GameObject> traps=new ArrayList<>();
        ownedLater(2,()->{for(Position p:pattern)traps.add(object(side==0?45748:45749,tile(p.getX(),p.getY()),10,0));});
        ownedLater(3,()->{for(GameObject o:traps)removeObject(o);traps.clear();for(Position p:pattern)traps.add(object(side==0?45750:45751,tile(p.getX(),p.getY()),10,0));});
        for(int tick=5;tick<9;tick++)ownedLater(tick,()->{
            int px=x(session.player.getPosition()),py=y(session.player.getPosition());
            for(Position p:pattern)if(px>=p.getX()&&px<=p.getX()+2&&py>=p.getY()&&py<=p.getY()+2){fixedHit(11+Random.get(2));break;}
        });
        ownedLater(10,()->{for(GameObject o:traps)removeObject(o);});
    }
    private void energyBall(int side){
        NPC caster=side==0?left:right;
        projectile(caster.getPosition(),session.player.getPosition(),side==0?2238:2237,87,20,30,40,390);
        session.player.sendMessage("A large ball of energy is shot your way...");
        ownedLater(13,()->{session.player.graphics(gfx(1605));if(side==0){fixedHit(4);fixedHit(4);fixedHit(3);fixedHit(3);}else fixedHit(13);});
    }
    @Override protected void bossDied(){
        if(closed)return;
        // The native death callback marks the actor dead before this method. Phase actors stay live.
        if(phase==Phase.OBELISK){startShield();return;}
        if(phase==Phase.SHIELD){exposeCore();return;}
        if(phase==Phase.ENRAGED){nextPhase(Phase.COMPLETE);for(Phantom p:phantoms)remove(p.npc);super.bossDied();return;}
        if(phase==Phase.FLYING){boss.getCombat().setDead(false);boss.setHp(Math.max(1,maxHp*5/100));startEnrage();}
    }
    private void clearOrbs(){for(Orb orb:orbs)remove(orb.npc);orbs.clear();}
    private void startShield(){
        nextPhase(Phase.SHIELD);clearOrbs();
        for(GameObject o:new ArrayList<>(objects))removeObject(o);
        HitListener obeliskHits=obelisk.hitListener;
        obelisk.getCombat().setDead(false);obelisk.setHp(1);visual(obelisk,11752);unattackable(obelisk);obelisk.animate(anim(9723));
        // Native lethal-hit scoring runs after this synchronous shield transition.
        if(obeliskHits!=null)obelisk.hitListener.postDamage=obeliskHits.postDamage;
        boolean elidinis=fullCharges[0]*100+charge[0]>fullCharges[1]*100+charge[1];
        finalForm=elidinis?11762:11761;shieldForm=elidinis?11753:11757;
        remove(left);remove(right);setMain(shieldForm,shieldForm,elidinis?3797:3814,5152,true);shieldHp=maxHp;
        boss.animate(anim(9663));invulnerable=false;attackIn=2;obeliskIn=40;specialUntil=0;thunderLeft=0;
        session.player.sendMessage("The Warden powers up. Break its shield with "+(magicShield()?"magic":"ranged")+", then attack its exposed core.");
    }
    private boolean magicShield(){return shieldForm==11753||shieldForm==11756;}
    private void tickShield(){
        tickObeliskSpecial();if(--attackIn>0)return;attackIn=6;
        if(touching(boss,1)&&Random.get(3)==0){boss.animate(anim(9659));prayerHit(boss,AttackStyle.CRUSH,16,0,.15);return;}
        if(canSpecial&&Random.get(5)==0){canSpecial=false;boss.animate(anim(9661));if(shieldCycles<1||Random.get(1)==0)divineAttack();else imprisonment();}
        else{canSpecial=true;boolean magic=Random.get(1)==0;boss.animate(anim(magic?9661:9660));
            projectile(boss.getPosition(),session.player.getPosition(),magic?2224:2241,100,25,0,12,90);
            prayerHit(boss,magic?AttackStyle.MAGIC:AttackStyle.RANGED,22,4,.15);}
    }
    private void prayerHit(NPC source,AttackStyle style,int max,int delay,double protectedMultiplier){
        // Donor phantom attacks are direct random hits; they do not have NPC combat-stat records.
        final int generation=epoch;Hit h=new Hit(source.getCombat()==null?boss:source,style).randDamage(max).delay(delay).ignorePrayer();
        if(source!=boss)h.ignoreDefence();
        h.preDamage(target->{if(generation!=epoch||!valid(target.player))h.block().nullify();else if(protecting(style))h.damage=(int)(h.damage*protectedMultiplier);});session.player.hit(h);
    }
    private boolean protecting(AttackStyle style){return session.player.getPrayer().isActive(style==AttackStyle.MAGIC?Prayer.PROTECT_FROM_MAGIC:style==AttackStyle.RANGED?Prayer.PROTECT_FROM_MISSILES:Prayer.PROTECT_FROM_MELEE);}
    private void divineAttack(){
        int style=Random.get(2);AttackStyle type=style==0?AttackStyle.CRUSH:style==1?AttackStyle.MAGIC:AttackStyle.RANGED;
        session.player.getPrayer().deactivateProtectionPrayer();
        session.player.sendMessage(style==0?"The warden throws an arcane scimitar.":style==1?"The warden launches an arcane spell.":"The warden fires an arcane arrow.");
        projectile(boss.getPosition(),session.player.getPosition(),style==0?2204:style==1?2208:2206,100,25,0,12,120);
        ownedLater(5,()->{if(!protecting(type))fixedHit(22+Random.get(10));});
    }
    private void imprisonment(){
        Position mark=session.player.getPosition().copy();graphic(mark,1447);projectile(boss.getPosition(),mark,2210,100,10,0,12,120);
        ownedLater(5,()->{
            if(session.player.getPosition().equals(mark)){
                stoneUntil=cycle+7;session.player.animate(anim(9713));session.player.freezeTicks(7,boss);ownedFreeze=true;
                session.player.sendMessage("You've been entombed in stone!");
            }else graphic(mark,2212);
        });
    }
    private void exposeCore(){
        nextPhase(Phase.CORE);boss.getCombat().setDead(false);boss.setMaxHp(4500);boss.setHp(coreHealth);boss.getMovement().reset();movementBlocked=true;invulnerable=true;
        visual(boss,shieldForm<=11754?11755:11758);boss.animate(anim(9670));shieldCycles++;
        coreUntil=cycle+4+coreTicks(coreHealth);
        ownedLater(4,()->{
            int sx=x(boss.getPosition())+(x(session.player.getPosition())<x(boss.getPosition())?-3:3),sy=y(boss.getPosition());
            Position at=freeNear(sx,sy,6);if(at==null)throw new IllegalStateException("Wardens core has no free authored tile");
            core=actor(11771,x(at),y(at));passiveCombat(core,11771,-1,()->ownedLater(1,()->{if(phase==Phase.CORE)recallCore();}));
            core.hitListener=new HitListener().preDefend(h->{if(h.attacker==null||!valid(h.attacker.player)||phase!=Phase.CORE)h.block().nullify();})
                    .preDamage(h->{if(h.attacker==null||!valid(h.attacker.player)||phase!=Phase.CORE)h.block().nullify();})
                    .postDamage(h->{if(!h.isNullified()&&phase==Phase.CORE&&h.type!=HitType.HEAL){coreHealth=Math.max(0,coreHealth-Math.max(0,h.damage)*5);boss.setHp(Math.max(1,coreHealth));if(coreHealth==0)finishMoving();}});
            if(core.getPosition().equals(session.player.getPosition()))moveAside(x(at),y(at),2);
            session.player.sendMessage("The core is exposed! Its damage is multiplied by five.");
        });
    }
    static int coreTicks(int remaining){return remaining>3600?21:remaining>2700?29:remaining>1800?37:remaining>900?45:53;}
    private void recallCore(){
        if(phase!=Phase.CORE)return;if(core!=null){projectile(core.getPosition(),boss.getPosition(),2240,20,62,0,12,30);remove(core);core=null;}
        nextPhase(Phase.SHIELD);shieldForm=shieldForm==11753?11754:shieldForm==11754?11753:shieldForm==11756?11757:11756;
        visual(boss,shieldForm);boss.getCombat().updateInfo(info(shieldForm));boss.setMaxHp(shieldHp);boss.setHp(shieldHp);boss.animate(anim(9672));
        invulnerable=true;ownedLater(3,()->{invulnerable=false;movementBlocked=false;attackIn=2;obeliskIn=40;specialUntil=0;thunderLeft=0;
            session.player.sendMessage("The shield reforms. Use "+(magicShield()?"magic":"ranged")+".");});
    }
    private void finishMoving(){
        nextPhase(Phase.TRANSITION);invulnerable=true;if(core!=null){remove(core);core=null;}
        boss.getCombat().setDead(false);boss.setHp(1);boss.animate(anim(9662));
        session.player.sendMessage("The defeated Warden restores its fallen counterpart!");
        ownedLater(7,()->{handoff=true;FINAL_FORMS.put(session,finalForm);session.transition(ToaRoom.WARDENS_FINAL);});
    }
    private void tickObeliskSpecial(){
        if(cycle<specialUntil)return;
        if(thunderLeft>0){if(--thunderLeft==0){specialIndex=(specialIndex+1)%3;obeliskSpecial(specialIndex);}else thundersAroundObelisk();return;}
        if(--obeliskIn<=0){obelisk.animate(anim(9727));thunderLeft=5;}
        else obelisk.animate(anim(obeliskIn>26?9724:obeliskIn>13?9725:9726));
    }
    private void thundersAroundObelisk(){
        List<Position> ring=new ArrayList<>();for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)if(Math.abs(dx)==2||Math.abs(dy)==2)ring.add(tile(3808+dx,5154+dy));
        Collections.shuffle(ring);for(Position p:ring.subList(0,3+Random.get(2))){graphic(p,2198);if(session.player.getPosition().equals(p))fixedHit(9+Random.get(3));}
    }
    private void obeliskSpecial(int special){
        if(special==0){specialUntil=cycle+20;isolation(true,12,0);isolation(false,11,6);}
        else if(special==1){specialUntil=cycle+27;int start=Random.get(WardensPatternData.INITIAL_BOMB_LOCATIONS.length-1);
            for(int i=0;i<WardensPatternData.INITIAL_BOMB_LOCATIONS.length;i++){final Position p=WardensPatternData.INITIAL_BOMB_LOCATIONS[(start+i)%WardensPatternData.INITIAL_BOMB_LOCATIONS.length];ownedLater(i*3+1,()->bomb(tile(p.getX(),p.getY())));}
            specialUntil=cycle+38;
        }else{specialUntil=cycle+37;for(int i=0;i<12;i++){final int rotation=(wheelRotation+i)%5;ownedLater(i*3+1,()->wheel(rotation));}wheelRotation=(wheelRotation+12)%5;}
        obeliskIn=40;thunderLeft=0;
    }
    private void isolation(boolean vertical,int extent,int start){
        for(int d=2;d<=extent;d++){graphicDelayed(tile(3808+(vertical?0:d),5154+(vertical?d:0)),2234,d*2);graphicDelayed(tile(3808-(vertical?0:d),5154-(vertical?d:0)),2234,d*2);}
        for(int step=0;step<=extent;step++){final int delta=extent-step,distance=step;
            ownedLater(start+step+1,()->{
                Set<Position> wave=new HashSet<>();int ax=3808+(vertical?0:delta),ay=5154+(vertical?delta:0),bx=3808-(vertical?0:delta),by=5154-(vertical?delta:0);
                wave.add(tile(ax,ay));wave.add(tile(bx,by));
                for(int n=1;n<=distance;n++)if(vertical){wave.add(tile(ax-n,ay+n));wave.add(tile(ax+n,ay+n));wave.add(tile(bx-n,by-n));wave.add(tile(bx+n,by-n));}
                else{wave.add(tile(ax+n,ay+n));wave.add(tile(ax+n,ay-n));wave.add(tile(bx-n,by+n));wave.add(tile(bx-n,by-n));}
                for(Position p:wave){graphic(p,2235);if(p.equals(session.player.getPosition()))fixedHit(13+Random.get(3));}
            });
        }
    }
    private void bomb(Position mark){
        projectile(obelisk.getPosition(),mark,2225,100,3,23,40,97);graphic(mark,1447);
        ownedLater(3,()->{
            Set<Position> inner=new HashSet<>(),outer=new HashSet<>();
            for(int dx=-3;dx<=3;dx++)for(int dy=-3;dy<=3;dy++){
                if((dx==0&&dy!=0&&Math.abs(dy)<3)||(dy==0&&dx!=0&&Math.abs(dx)<3))continue;
                Position p=tile(x(mark)+dx,y(mark)+dy);boolean edge=Math.abs(dx)==3||Math.abs(dy)==3;
                (edge?outer:inner).add(p);graphicDelayed(p,2198,edge?30:Math.max(Math.abs(dx),Math.abs(dy))*10);
            }
            if(inner.contains(session.player.getPosition()))fixedHit(13+Random.get(3));
            ownedLater(1,()->{if(outer.contains(session.player.getPosition()))fixedHit(13+Random.get(3));});
        });
    }
    private void wheel(int rotation){
        Position[] pattern=WardensPatternData.WHEEL_LOCATIONS[rotation];for(Position p:pattern)graphicDelayed(tile(p.getX(),p.getY()),2236,30);
        ownedLater(3,()->{boolean hit=false;for(Position p:pattern){Position at=tile(p.getX(),p.getY());graphicDelayed(at,2234,2*Math.max(Math.abs(p.getX()-3808),Math.abs(p.getY()-5154)));if(at.equals(session.player.getPosition()))hit=true;}if(hit)fixedHit(13+Random.get(3));});
    }
    private void graphicDelayed(Position at,int source,int delay){if(live())session.player.getPacketSender().sendGraphics(gfx(source),0,delay,at.getX(),at.getY(),at.getZ());}
    private void floorAttack(){
        int rotation=floorRotation;floorRotation=(floorRotation+1)%3;boss.animate(anim(9674+rotation*2));
        List<Position> first=new ArrayList<>(),second=new ArrayList<>();int effect=2220;
        for(Position[] group:WardensPatternData.REGULAR_FLOOR_ATTACK_LOCATIONS[rotation]){
            for(Position p:group){int distance=Math.max(Math.abs(p.getX()-3936),Math.abs(p.getY()-5157));int delay=90+distance*6-(rotation==2?6:0);
                Position at=tile(p.getX(),p.getY());(delay<120?first:second).add(at);graphicDelayed(at,effect,delay);}
            effect++;
        }
        ownedLater(2,()->{if(phase==Phase.FLYING&&first.contains(session.player.getPosition()))fixedHit(18+Random.get(3));});
        ownedLater(3,()->{if(phase==Phase.FLYING&&second.contains(session.player.getPosition()))fixedHit(18+Random.get(3));});
    }
    private void sendSkulls(int index){
        nextPhase(Phase.SKULLS);invulnerable=true;floorRotation=0;boss.animate(anim(9682));visual(boss,finalForm+2);
        session.player.getCombat().reset();Position[] positions=WardensPatternData.SKULL_LOCATIONS[0][index];
        skullsRemaining=positions.length;skullDeadline=Integer.MAX_VALUE;
        ownedLater(1,()->{
            for(Position p:positions){Position at=tile(p.getX(),p.getY());graphic(at,1447);projectile(boss.getPosition(),at,2226,87,20,30,20,90);}
            if(index==1)spawnPhantom(finalForm==11762?11774:11777,3943,5153);
            else if(index==2)spawnPhantom(finalForm==11762?11775:11776,3925,5153);
        });
        ownedLater(5,()->{
            for(Position p:positions){
                NPC skull=actor(11772,p.getX(),p.getY());passiveCombat(skull,11772,1,()->skullDied());skulls.add(skull);
                skull.hitListener=new HitListener().preDefend(h->{if(h.attacker==null||!valid(h.attacker.player)||phase!=Phase.SKULLS||h.attackStyle==null||!h.attackStyle.isMelee())h.block().nullify();else h.ignoreDefence();})
                        .postDefend(h->{if(!h.isNullified())h.fixedDamage(1);})
                        .preDamage(h->{if(phase!=Phase.SKULLS||h.attacker==null||!valid(h.attacker.player))h.block().nullify();});
                if(skull.getPosition().equals(session.player.getPosition()))moveAside(p.getX(),p.getY(),2);
            }
            skullDeadline=cycle+3+positions.length*3;
            session.player.sendMessage("Destroy every energy siphon with melee before it is too late!");
        });
    }
    private void skullDied(){
        if(phase!=Phase.SKULLS||--skullsRemaining>0)return;skullDeadline=Integer.MAX_VALUE;
        ownedLater(1,()->{for(NPC n:skulls)if(!n.isRemoved())projectile(n.getPosition(),boss.getPosition(),2226,20,87,0,20,58);});
        ownedLater(2,()->{boss.animate(anim(9680));visual(boss,finalForm);});
        ownedLater(3,()->{for(NPC n:skulls)remove(n);skulls.clear();nextPhase(Phase.FLYING);invulnerable=false;attackIn=8;boss.incrementHp(-maxHp*5/100);});
    }
    private void skullFailure(){
        if(phase!=Phase.SKULLS||skullsRemaining<=0)return;skullDeadline=Integer.MAX_VALUE;
        final int damage=11*skullsRemaining;boss.animate(anim(9681));
        for(NPC skull:skulls)if(!skull.isRemoved()){graphic(skull.getPosition(),2158);remove(skull);}skulls.clear();
        List<Position> first=new ArrayList<>(),second=new ArrayList<>();int effect=2220;
        for(Position[] group:WardensPatternData.FULL_FLOOR__ATTACK_LOCATIONS){for(Position p:group){int delay=30+Math.max(Math.abs(p.getX()-3936),Math.abs(p.getY()-5157))*6;
            Position at=tile(p.getX(),p.getY());(delay<60?first:second).add(at);graphicDelayed(at,effect,delay);}effect++;}
        if(first.contains(session.player.getPosition()))fixedHit(damage);
        ownedLater(1,()->{if(second.contains(session.player.getPosition()))fixedHit(damage);});
        ownedLater(3,()->visual(boss,finalForm));
        ownedLater(4,()->{nextPhase(Phase.FLYING);invulnerable=false;attackIn=9;});
    }
    private void startEnrage(){
        nextPhase(Phase.ENRAGED);invulnerable=false;boss.getCombat().setDead(false);boss.incrementHp(maxHp*20/100);boss.animate(anim(9684));
        session.player.sendMessage("The Warden restores its energy. The platform is collapsing!");
        ownedLater(3,()->boss.animate(anim(9685)));
    }
    private void tickEnrage(){
        if(floorIndex<WardensPatternData.FLOOR_REPLACEMENT_OBJECTS.length&&--floorIn<=0){floorIn=15;
            int currentRow=5165-floorIndex/4,start=floorIndex;
            for(int i=0;i<4&&start+i<WardensPatternData.FLOOR_REPLACEMENT_OBJECTS.length;i++){
                final int index=start+i;ownedLater(i+1,()->{erodeFloor(index,currentRow);floorIndex=index+1;});
            }
        }
        if(--thunderIn==2||thunderIn==1||thunderIn==0){
            if(thunderIn==0){thunderIn=6;occupiedThunder.clear();}
            List<Position> available=new ArrayList<>();int top=5165-floorIndex/4;
            for(int sy=5157;sy<=top;sy++)for(int sx=3926;sx<=3946;sx++){Position p=tile(sx,sy);if(free(sx,sy)&&!occupiedThunder.contains(p))available.add(p);}
            Collections.shuffle(available);int count=Math.min(available.size(),Math.max(2,(int)(available.size()*.15)));
            List<Position> selected=new ArrayList<>(available.subList(0,count));occupiedThunder.addAll(selected);
            for(Position p:selected)graphic(p,1446);
            ownedLater(4,()->{for(Position p:selected){graphic(p,2197);if(p.equals(session.player.getPosition()))fixedHit(9+Random.get(4));}});
        }
    }
    private void erodeFloor(int index,int currentRow){
        for(int[] spec:WardensPatternData.FLOOR_REPLACEMENT_OBJECTS[index]){
            Position at=tile(spec[3],spec[4]);object(spec[0],at,spec[1],spec[2]);
            if(at.equals(session.player.getPosition())){
                int px=x(session.player.getPosition());for(int radius=0;radius<=2;radius++){
                    if(free(px-radius,currentRow-1)){session.player.animate(anim(1114));session.player.getMovement().teleport(tile(px-radius,currentRow-1));break;}
                    if(free(px+radius,currentRow-1)){session.player.animate(anim(1114));session.player.getMovement().teleport(tile(px+radius,currentRow-1));break;}
                }
            }
        }
    }
    private void spawnPhantom(int source,int sx,int sy){NPC npc=actor(source,sx,sy);unattackable(npc);phantoms.add(new Phantom(npc,source));}
    private void tickPhantoms(){
        for(Phantom p:phantoms){if(p.npc.isRemoved()||--p.attackIn>0)continue;
            p.attackIn=p.source==11774?8:7;
            if(p.source==11774){if(Random.get(2)==0)p.magic=!p.magic;final boolean magic=p.magic;
                p.npc.animate(anim(9626));Position split=tile(3941,5159);projectile(p.npc.getPosition(),split,magic?2176:2178,50,125,30,20,90);
                ownedLater(3,()->{projectile(split,session.player.getPosition(),magic?2181:2187,125,22,0,1,90);prayerHit(p.npc,magic?AttackStyle.MAGIC:AttackStyle.RANGED,20,2,0);});
            }else if(p.source==11777){
                if(++p.attacks>3){p.attacks=0;p.magic=!p.magic;p.npc.animate(anim(9777));}
                else{p.npc.animate(anim(p.magic?9774:9772));projectile(p.npc.getPosition(),session.player.getPosition(),p.magic?2253:2255,101,34,0,12,54);prayerHit(p.npc,p.magic?AttackStyle.MAGIC:AttackStyle.RANGED,20,2,.15);}
            }else if(p.source==11775){
                p.npc.animate(anim(9743));Position marked=session.player.getPosition().copy();graphicDelayed(marked,2250,20);
                ownedLater(5,()->{if(marked.equals(session.player.getPosition()))fixedHit(19+Random.get(5));});
            }else{
                p.npc.animate(anim(9577));Position launch=tile(3928,5156);projectile(p.npc.getPosition(),launch,1481,175,250,39,50,51);
                ownedLater(2,()->{Position mark=session.player.getPosition().copy();projectile(launch,mark,2266,250,9,0,50,120);graphic(mark,1447);
                    ownedLater(3,()->{graphic(mark,2157);if(free(x(mark),y(mark))&&mark.equals(session.player.getPosition())){
                        int damage=20+Random.get(3);if(protecting(AttackStyle.MAGIC))damage=(int)(damage*.75);fixedHit(damage);
                    }});});
            }
        }
    }
    private boolean touching(NPC n,int range){
        int px=session.player.getAbsX(),py=session.player.getAbsY();
        return Math.max(Math.max(0,Math.max(n.getAbsX()-px,px-(n.getAbsX()+n.getSize()-1))),Math.max(0,Math.max(n.getAbsY()-py,py-(n.getAbsY()+n.getSize()-1))))<=range;
    }
    private Position freeNear(int sx,int sy,int radius){
        for(int r=0;r<=radius;r++)for(int dx=-r;dx<=r;dx++)for(int dy=-r;dy<=r;dy++)if(free(sx+dx,sy+dy))return tile(sx+dx,sy+dy);
        return null;
    }
    private void moveAside(int sx,int sy,int radius){Position next=freeNear(sx+1,sy,radius);if(next!=null)session.player.getMovement().teleport(next);}
    @Override public void close(){
        if(closed)return;if(!handoff)FINAL_FORMS.remove(session);
        if(ownedFreeze&&cycle<stoneUntil)session.player.resetFreeze();
        clearOrbs();skulls.clear();phantoms.clear();super.close();
    }
    private final class WardenCombat extends NPCCombat {
        final boolean follows;WardenCombat(boolean follows){this.follows=follows;}
        public void init(){setAllowRespawn(false);setAllowRetaliate(false);}
        public void follow(){if(follows&&!movementBlocked&&live()&&phase==Phase.SHIELD)follow(8);}
        public boolean attack(){return false;}
        public int getAttackBoundsRange(){return 64;}
        public boolean canAttack(Entity target){return target!=null&&valid(target.player);}
        public void startDeath(Hit hit){if(isDead()||closed)return;setDead(true);bossDied();}
    }
}
