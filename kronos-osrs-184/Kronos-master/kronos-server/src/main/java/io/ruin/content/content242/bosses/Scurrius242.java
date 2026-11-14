package io.ruin.content.content242.bosses;

import io.ruin.api.utils.Random;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.*;
import io.ruin.model.map.route.routes.DumbRoute;
import java.util.*;

/** Native184 implementation of the captured242 Scurrius attack/feeding/enrage cycle. */
public final class Scurrius242 extends Boss242Combat {
    private static final int[] ANIMATIONS={10686,10693,10694,10695,10696,10697,10698,10699,10689,10703,10705,10706};
    private static final int[] GRAPHICS={2638,2639,2640,2641,2642,2643,2644};
    private static final int[][] FOOD={{3297,9873},{3304,9866},{3298,9860}};
    enum Phase {COMBAT,WALK_FOOD,FEEDING,BETWEEN,WALK_CENTRE,ENRAGED}
    private Phase phase=Phase.COMBAT;private Position destination;private int rounds,swings,waves;private long lastCall=-100;
    public static boolean available(){return Boss242Assets.ready("scurrius",new int[]{7222,7223},ANIMATIONS,GRAPHICS,new int[0],13210);}
    public static void open(Player p){if(!available()){p.sendMessage("Scurrius is waiting for its verified asset bindings.");return;}Boss242Instances.offer(p,"Scurrius",()->Boss242Instances.enter(p,13210,7222,3301,9867,3290,9868));}
    @Override public void init(){begin(available(),3301,9867);}
    @Override protected boolean arena(Position p){int x=p.getX()-dx,y=p.getY()-dy;return p.getZ()==plane&&x>=3289&&x<=3308&&y>=9858&&y<=9877;}
    private boolean floor(Position p){int x=p.getX()-dx,y=p.getY()-dy;return arena(p)&&x>=3290&&x<=3306&&y>=9860&&y<=9875;}
    private boolean publicBoss(){return npc.getId()==Boss242Assets.id("npcs",7221);}
    public static int phaseFor(int hp,int maximum){return hp*10<=maximum*3?2:hp*5<=maximum*4?1:0;}
    private void food(){int[] at=FOOD[Random.get(FOOD.length-1)];destination=tile(at[0],at[1]);phase=Phase.WALK_FOOD;}
    @Override public void follow(){
        int requested=phaseFor(npc.getHp(),npc.getMaxHp());
        if(requested==2&&phase!=Phase.WALK_CENTRE&&phase!=Phase.ENRAGED){phase=Phase.WALK_CENTRE;destination=tile(3298,9867);}
        else if(requested==1&&(phase==Phase.COMBAT||phase==Phase.ENRAGED))food();
        if(phase==Phase.WALK_FOOD||phase==Phase.WALK_CENTRE){
            if(npc.getPosition().equals(destination)){
                phase=phase==Phase.WALK_FOOD?Phase.FEEDING:Phase.ENRAGED;rounds=Random.get(3,5);updateLastAttack(2);
                if(phase==Phase.FEEDING)npc.animate(animation(10689));
            }else DumbRoute.route(npc,destination.getX(),destination.getY());
        }else if(phase==Phase.COMBAT||phase==Phase.BETWEEN)follow(1);
    }
    @Override public boolean attack(){
        if(!ready||target==null||!valid(target.player)||phase==Phase.WALK_FOOD||phase==Phase.WALK_CENTRE)return false;
        boolean feeding=phase==Phase.FEEDING;
        if(feeding){children.removeIf(n->n.isRemoved()||n.getHp()<=0);int heal=(children.isEmpty()?(Random.get(2)==0?5:10):5)*Math.max(1,players().size());npc.hit(new Hit(HitType.HEAL).fixedDamage(heal).delay(0));}
        int action;
        if(phase==Phase.COMBAT)action=withinDistance(1)&&Random.get(9)<7?0:3;
        else if(phase==Phase.BETWEEN)action=withinDistance(1)?0:3;
        else {int roll=Random.get(feeding?10:7);action=feeding?(roll<4?1:roll<8?2:roll<10?3:4):(roll<2?1:roll<4?2:roll<7?3:4);}
        if(action==4&&!canSummon())action=3;
        if(action==0){npc.animate(animation(10693));target.hit(new Hit(npc,AttackStyle.CRUSH,AttackType.ACCURATE).randDamage(13).delay(0));}
        else if(action==1||action==2)volley(action==1,feeding);
        else if(action==3)rocks(feeding);else summon(feeding);
        updateLastAttack(feeding?Random.get(6,7):4);
        if(feeding&&--rounds==0){phase=Phase.BETWEEN;swings=Random.get(3,5);}
        else if(phase==Phase.BETWEEN&&action==0&&--swings<=0)food();
        return false; // custom phase cadence was set above
    }
    private void volley(boolean magic,boolean feeding){
        npc.animate(animation(magic?(feeding?10697:10696):(feeding?10695:10694)));
        if(magic)npc.graphics(graphic(feeding?2639:2638));
        for(Player p:players()){
            new Projectile(graphic(magic?2640:2642),50,25,30,90,0,40,0).send(npc,p);
            after(4,()->{if(valid(p)){p.graphics(graphic(magic?2641:2643));p.hit(new Hit(npc,magic?AttackStyle.MAGIC:AttackStyle.RANGED,AttackType.ACCURATE).randDamage(magic?(publicBoss()?14:8):(publicBoss()?13:7)).delay(0));}});
        }
    }
    private void rocks(boolean feeding){
        npc.animate(animation(feeding?10699:10698));Set<Position> positions=new HashSet<>();for(Player p:players())positions.add(p.getPosition().copy());
        for(int tries=0;positions.size()<12&&tries<100;tries++){Position at=tile(Random.get(3290,3306),Random.get(9860,9875));if(free(at))positions.add(at);}
        for(Position at:positions)tileGraphic(2644,at,60);
        after(9,()->{for(Player p:players())for(Position at:positions)if(p.getPosition().equals(at)){p.hit(new Hit(npc).randDamage(15,publicBoss()?24:22).delay(0));break;}});
    }
    private boolean canSummon(){children.removeIf(n->n.isRemoved()||n.getHp()<=0);return waves<12&&tick-lastCall>=41&&children.isEmpty();}
    private void summon(boolean feeding){
        waves++;lastCall=tick;npc.animate(animation(feeding?10703:10706));
        after(2,()->{List<Player> opponents=players();if(opponents.isEmpty())return;Set<Position> taken=new HashSet<>();
            for(int count=0;count<6;count++)for(int tries=0;tries<40;tries++){
                Position at=tile(Random.get(3290,3305),Random.get(9860,9874));if(!floor(at)||!free(at)||!free(new Position(at.getX()+1,at.getY()+1,plane))||!taken.add(at))continue;
                NPC rat=new NPC(Boss242Assets.id("npcs",7223)).spawn(at);children.add(rat);if(rat.getCombat() instanceof GiantRat242)((GiantRat242)rat.getCombat()).owner=this;rat.getCombat().setAllowRespawn(false);rat.getCombat().setTarget(opponents.get(count%opponents.size()));break;
            }
        });
    }
    @Override public void reset(){super.reset();phase=Phase.COMBAT;destination=null;rounds=swings=waves=0;lastCall=-100;}
}
