package io.ruin.content.content242.bosses;

import io.ruin.Server;
import io.ruin.api.utils.Random;
import io.ruin.cache.NPCDef;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.*;
import io.ruin.model.map.route.routes.ProjectileRoute;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import java.util.*;

/** Pinned242 Muspah mechanics adapted to native184 packets/combat; see TSPS-NOTICE.txt.
 * No modern interfaces, health bars, varbits or splat IDs are sent to the184 client. */
public final class PhantomMuspah242 extends Boss242Combat {
    private static final int[] ANIMATIONS={9938,9922,9918,9920,9936,9937,1114,9923,9928,9930,9932,9948,9925,9941};
    private static final int[] GRAPHICS={2293,2319,2320,2321,2322,2323,2324,2325,2326,2327,2328,2329,2330,2331,2332,2333,2334,2335};
    private static final int[] OBJECTS={46599,46695,46693,46694,46697};
    private static final int[][] JUMPS={{2854,4257},{2840,4263},{2845,4249},{2852,4263},{2838,4257},{2851,4251},{2846,4262},{2839,4250}};
    MuspahState242 state;
    final MuspahSpikes242 spikes=new MuspahSpikes242(this);
    private final Map<Hit,Integer> rolls=new WeakHashMap<>();
    private final List<Cloud> clouds=new ArrayList<>();
    private long lastAction=-100,switchReady,specialReady,lastShieldMessage=-100,corruptionVersion;
    private Position lastTile;private int stillTicks,growthAttacks;private boolean ending;
    private static final class Cloud {final NPC npc;final int dx,dy;Position at;Cloud(NPC n,Position p,int x,int y){npc=n;at=p;dx=x;dy=y;}}

    /** Actor-specific freeze resistance, without changing the native NPC/Entity implementations. */
    public static final class Actor extends NPC {
        public Actor(int id){super(id);}
        @Override public void freeze(int seconds,Entity attacker){super.freezeTicks(MuspahState242.freezeTicks(Server.toTicks(seconds)),attacker);}
        @Override public void freezeTicks(int ticks,Entity attacker){super.freezeTicks(MuspahState242.freezeTicks(ticks),attacker);}
    }
    public static boolean available(){
        if(!Boss242Assets.ready("phantom_muspah",new int[]{12077,12078,12079},ANIMATIONS,GRAPHICS,OBJECTS,11330))return false;
        for(int source:new int[]{12080,12082,12083}){int mapped=Boss242Assets.id("npcs",source);if(mapped<0||NPCDef.get(mapped)==null)return false;}return true;
    }
    public static void open(Player player){if(!available()){player.sendMessage("Phantom Muspah is waiting for its verified asset bindings.");return;}
        Boss242Instances.offer(player,"Phantom Muspah",()->Boss242Instances.enter(player,11330,12077,2844,4258,2859,4259));}
    NPC actor(){return npc;}
    @Override public void init(){
        state=new MuspahState242(Random.get(1)==0);begin(available(),2844,4258);if(!ready)return;
        form(state.form);npc.animate(animation(9938));updateLastAttack(7);lastTile=npc.getPosition().copy();
        npc.attackNpcListener=(p,n,message)->tick>=3&&state.form!=MuspahState242.Form.TELEPORT&&valid(p);
        npc.hitListener.preDefend(hit->{guardIncoming(hit);if(hit.type!=HitType.HEAL&&(tick<3||state.form==MuspahState242.Form.TELEPORT))reject(hit);});
        npc.hitListener.postDefend(hit->{if(hit.type!=HitType.HEAL&&!hit.isNullified()){rolls.put(hit,hit.damage);if(state.form==MuspahState242.Form.SHIELDED)hit.damage=0;}});
        npc.hitListener.preDamage(this::damage);
        npc.hitListener.postTargetDamage((hit,target)->{if(state.form==MuspahState242.Form.SHIELDED&&valid(target.player)&&hit.attackStyle!=null){heal(hit.damage/2);drainShield(2);}});
        after(1,spikes::initial);
    }
    @Override protected boolean arena(Position p){int x=p.getX()-dx,y=p.getY()-dy;return p.getZ()==plane&&(MuspahArena242.floor(x,y)||(x>=2859&&x<=2860&&y>=4258&&y<=4259));}
    @Override protected boolean free(Position p){return MuspahArena242.floor(p.getX()-dx,p.getY()-dy)&&super.free(p);}
    boolean underBoss(Position p){return p.getZ()==plane&&p.getX()>=npc.getAbsX()&&p.getX()<npc.getAbsX()+npc.getSize()&&p.getY()>=npc.getAbsY()&&p.getY()<npc.getAbsY()+npc.getSize();}
    private void form(MuspahState242.Form value){
        int hp=npc.getHp(),defence=stats[StatType.Defence.ordinal()].currentLevel;state.form=value;int source;
        switch(value){case MELEE:source=12078;break;case TELEPORT:source=12082;break;case SHIELDED:source=12079;break;case FINAL:source=12080;break;default:source=12077;}
        npc.transform(Boss242Assets.id("npcs",source));npc.setHp(hp);stats[StatType.Defence.ordinal()]=new MuspahDefence242(stats[StatType.Defence.ordinal()].fixedLevel,defence);stillTicks=0;
    }
    private int speed(){return state.form==MuspahState242.Form.RANGED?6:5;}
    private void hold(boolean value){state.held=value;npc.getMovement().reset();if(!value)updateLastAttack(speed());}
    @Override public void follow(){if(ready&&tick>=3&&!state.held)follow(state.form==MuspahState242.Form.MELEE?1:10);}
    // Teleport/cloud phases span more than the native ordinary-NPC chase radius.
    // Ownership+arena+lease remain mandatory, and each actual attack checks reach/LOS separately.
    @Override public boolean canAttack(Entity target){return ready&&!isDead()&&target!=null&&valid(target.player)&&target.getCombat()!=null&&!target.getCombat().isDead();}
    @Override public boolean attack(){
        if(!ready||state.held||tick<7||target==null||!valid(target.player))return false;
        Player victim=target.player;boolean melee=state.form==MuspahState242.Form.MELEE;
        if(!withinDistance(melee?1:10)||(!melee&&!ProjectileRoute.allow(npc,victim)))return false;
        if(melee)melee(victim);else if(Random.get(4)==0)magic(victim);else ranged(victim);
        lastAction=tick;updateLastAttack(speed());if(state.form==MuspahState242.Form.FINAL&&++growthAttacks>=4){growthAttacks=0;spikes.spread(true);}return false;
    }
    private void ranged(Player p){npc.animate(animation(9922));int maximum=state.form==MuspahState242.Form.RANGED?61:72;
        new Projectile(graphic(2329),32,25,26,60,0,5,244).send(npc,p);
        after(2,()->{if(valid(p)){p.graphics(graphic(2293));land(p,AttackStyle.RANGED,maximum,0);}});
    }
    private void magic(Player p){npc.animate(animation(9918));npc.graphics(graphic(2319));int maximum=state.form==MuspahState242.Form.RANGED?72:79;
        after(2,()->{if(valid(p))new Projectile(graphic(2327),100,24,24,54,0,5,0).send(npc,p);});
        after(4,()->{if(valid(p)){p.graphics(graphic(2328),124,0);boolean protectedNow=p.getPrayer().isActive(Prayer.PROTECT_FROM_MAGIC);land(p,AttackStyle.MAGIC,maximum,0);if(!protectedNow)corrupt(p,3);}});
    }
    private void melee(Player p){npc.animate(animation(9920));npc.graphics(graphic(2320));int maximum=34+Math.min(20,stillTicks);double through=Math.min(.5,stillTicks*.05);
        after(1,()->{if(valid(p))land(p,AttackStyle.STAB,maximum,through);});
    }
    private void land(Player p,AttackStyle style,int maximum,double through){
        Hit hit=new Hit(npc,style,AttackType.ACCURATE).randDamage(maximum).delay(0);
        if(style.isMelee()&&through>0){hit.ignorePrayer();hit.postDefend(victim->{if(p.getPrayer().isActive(Prayer.PROTECT_FROM_MELEE))hit.damage=(int)(hit.damage*through);});}
        p.hit(hit);
    }
    void heal(int amount){if(amount>0&&!ending&&!isDead()&&state.skipRestore==0)npc.hit(new Hit(HitType.HEAL).fixedDamage(Math.min(amount,npc.getMaxHp()-npc.getHp())).delay(0));}
    private void damage(Hit hit){
        guardIncoming(hit);if(hit.isNullified()||hit.type==HitType.HEAL)return;
        Integer rolled=rolls.remove(hit);int raw=rolled==null?hit.damage:rolled;Player p=hit.attacker.player;
        boolean sapphire=state.form==MuspahState242.Form.SHIELDED&&hit.rangedAmmo!=null
            &&(hit.rangedAmmo.rangedAmmo==RangedAmmo.SAPPHIRE_BOLTS||hit.rangedAmmo.rangedAmmo==RangedAmmo.DRAGON_SAPPHIRE_BOLTS)&&Random.get(3)==0;
        int shieldBefore=state.shield;
        MuspahState242.Damage result=state.damage(npc.getHp(),raw,p.getPrayer().isActive(Prayer.SMITE),sapphire);hit.damage=result.dealt;
        if(result.restore>0)npc.setHp(result.restore);
        if(state.form==MuspahState242.Form.SHIELDED){
            if(state.shield<shieldBefore||tick-lastShieldMessage>=8){p.sendMessage("Muspah's prayer shield: "+state.shield+" / 75. Smite and enchanted sapphire bolts drain it.");lastShieldMessage=tick;}
            if(state.shield==0)after(1,this::breakShield);
        }
    }
    @Override public int beforeBurn(int amount){
        int allowed=super.beforeBurn(amount);if(allowed==0||tick<3||state.form==MuspahState242.Form.TELEPORT)return 0;
        MuspahState242.Damage result=state.damage(npc.getHp(),allowed,false,false);
        if(result.restore>0)npc.setHp(result.restore);return result.dealt;
    }
    private void drainShield(int amount){if(state.drain(amount)>0&&state.shield==0)after(1,this::breakShield);}
    private void breakShield(){if(state.form!=MuspahState242.Form.SHIELDED||state.shield>0)return;state.breakShield();form(state.form);growthAttacks=0;spikes.spread(true);message("The Phantom Muspah's prayer shield shatters!");}
    private void message(String text){for(Player p:players())p.sendMessage(text);}
    @Override protected void pulse(){
        if(!ready||ending)return;Position here=npc.getPosition();stillTicks=lastTile!=null&&here.equals(lastTile)?stillTicks+1:0;lastTile=here.copy();
        stats[StatType.Defence.ordinal()].currentLevel=Math.max(125,stats[StatType.Defence.ordinal()].currentLevel);
        spikes.pulse();cloudPulse();if(state.held||players().isEmpty()||tick-lastAction<2)return;
        if(state.waveDue(npc.getHp())){lastAction=tick;shockwave();}
        else if(tick>=specialReady&&state.specialDue(npc.getHp())){lastAction=tick;special();}
        else if(tick>=switchReady&&state.switchDue()){lastAction=tick;switchForm();}
    }
    private void switchForm(){npc.animate(animation(9936));hold(true);switchReady=tick+5;after(1,()->{state.switched();form(state.form);npc.animate(animation(9937));npc.graphics(graphic(2334));spikes.spread(false);hold(false);});}
    private void special(){boolean cloud=state.specialIsClouds();state.specials++;state.resetSwitch();specialReady=tick+6;
        message("The Phantom Muspah floods the room with darkness...");if(cloud)teleportClouds();else{npc.animate(animation(9923));npc.graphics(graphic(2321));spikes.homing();updateLastAttack(speed());}}
    private void move(int x,int y){npc.getMovement().reset();npc.getMovement().teleport(tile(x,y));lastTile=tile(x,y);npc.face(2847+dx,4258+dy);}
    private void teleportClouds(){
        MuspahState242.Form returning=state.form;hold(true);npc.animate(animation(9928));npc.graphics(graphic(2332));after(3,()->form(MuspahState242.Form.TELEPORT));
        for(int i=0;i<20;i++){final int step=i;after(4+i,()->{int[] at=JUMPS[step%JUMPS.length];move(at[0],at[1]);
            if(step==19){form(returning);npc.animate(animation(9932));npc.graphics(graphic(2334));hold(false);}else{npc.animate(animation(9930));npc.graphics(graphic(2333));sendClouds(at[0]+2,at[1]+2);}});}
    }
    private void sendClouds(int bx,int by){
        int count=Random.get(4);for(int i=0;i<count;i++){double fraction=.2+Random.get()*.6;int x=(int)Math.round(bx+(2847-bx)*fraction)+Random.get(-2,2),y=(int)Math.round(by+(4258-by)*fraction)+Random.get(-2,2);
            int vx=Integer.signum(x-bx),vy=Integer.signum(y-by);if(Random.get(1)==0){if(Math.abs(x-bx)>=Math.abs(y-by))vy=0;else vx=0;}if(vx==0&&vy==0||!MuspahArena242.floor(x,y))continue;
            Position at=tile(x,y);NPC cloud=new NPC(Boss242Assets.id("npcs",12083)).spawn(at);cloud.attackNpcListener=(p,n,message)->false;
            if(cloud.getCombat()!=null){cloud.getCombat().setAllowRespawn(false);cloud.getCombat().setAllowRetaliate(false);}children.add(cloud);clouds.add(new Cloud(cloud,at,vx,vy));
        }
    }
    private void cloudPulse(){for(Cloud cloud:new ArrayList<>(clouds)){
        Position next=new Position(cloud.at.getX()+cloud.dx,cloud.at.getY()+cloud.dy,plane);boolean gone=!arena(next);
        if(!gone){cloud.at=next;cloud.npc.getMovement().teleport(next);for(Player p:players())if(p.getPosition().equals(next)){p.hit(new Hit(npc).randDamage(1,20).delay(0));gone=true;}}
        if(gone){clouds.remove(cloud);cloud.npc.animate(animation(9948));after(1,()->{children.remove(cloud.npc);cloud.npc.remove();});}
    }}
    private void shockwave(){
        state.shockwaveStarted=true;hold(true);message("The Phantom Muspah gathers a shockwave. Shelter behind a spike!");npc.animate(animation(9928));npc.graphics(graphic(2332));
        after(3,()->{move(2845,4256);npc.animate(animation(9932));npc.graphics(graphic(2334));});
        after(5,()->{npc.animate(animation(9925));npc.graphics(graphic(2322));});after(10,this::releaseWave);
        after(13,()->{boolean skip=state.skipRestore>0;state.raiseShield();if(skip)npc.setHp(1);form(state.form);spikes.hardenAll();hold(false);
            if(skip){spikes.spread(true);message("The prayer shield is bypassed!");}else{stats[StatType.Defence.ordinal()].restore();message("The Phantom Muspah raises a 75-point prayer shield. Use Smite or sapphire bolts.");}});
    }
    private void releaseWave(){npc.graphics(graphic(2323));for(int[] source:MuspahArena242.tiles()){
        char kind=MuspahArena242.kind(source[0],source[1]);Position at=tile(source[0],source[1]);if(kind==' '||spikes.has(at)||spikes.sheltered(at))continue;
        int distance=Math.abs(source[0]-2847)+Math.abs(source[1]-4258);tileGraphic(kind=='o'?2330:2331,at,Math.max(0,distance*3-(kind=='o'?1:6)));}
        for(Player p:players())if(MuspahArena242.floor(p.getAbsX()-dx,p.getAbsY()-dy)&&!spikes.sheltered(p.getPosition()))after(1,()->{if(valid(p)){p.hit(new Hit(npc).randDamage(80).delay(0));corrupt(p,4);}});
    }
    private void corrupt(Player p,int base){long version=++corruptionVersion;p.sendMessage("You have been corrupted!");for(int i=1;i<=3;i++){final int iteration=i;
        after(i*10+1,()->{if(version!=corruptionVersion||!valid(p))return;p.getPrayer().drain(MuspahState242.corruptionDrain(base,iteration));if(iteration==3)p.sendMessage("You are no longer afflicted with corruption.");});}}
    @Override public void close(){ending=true;corruptionVersion++;rolls.clear();spikes.clear();clouds.clear();super.close();}
    @Override public void reset(){super.reset();}
}
