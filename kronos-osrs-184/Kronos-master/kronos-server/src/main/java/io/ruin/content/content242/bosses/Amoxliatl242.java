package io.ruin.content.content242.bosses;

import io.ruin.api.utils.Random;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import java.util.*;

/** Native magic, delayed spikes, persistent pools, prayer-reactive icicle and breakable unstable ice. */
public final class Amoxliatl242 extends Boss242Combat {
    private static final int[] ANIMATIONS={11527,11530,11531,11533,11536,11035};
    private static final int[] GRAPHICS={2942,2938,2936,2935};
    private static final int[][] ROWS={{4517,1363,1367},{4516,1362,1368},{4515,1361,1369},{4514,1360,1370},{4513,1359,1371},{4512,1359,1376},{4511,1359,1376},{4510,1359,1376},{4509,1359,1371},{4508,1360,1370},{4507,1361,1369},{4506,1362,1368},{4505,1363,1367}};
    private final Map<Position,GameObject> pools=new HashMap<>();
    private int attacks,threshold=3;private boolean nextIcicle;
    public static boolean available(){return Boss242Assets.ready("amoxliatl",new int[]{13685,13688},ANIMATIONS,GRAPHICS,new int[]{54279,54282},5446);}
    public static void open(Player p){if(!available()){p.sendMessage("Amoxliatl is waiting for its verified asset bindings.");return;}Boss242Instances.offer(p,"Amoxliatl",()->Boss242Instances.enter(p,5446,13685,1362,4510,1376,4511));}
    public static boolean sourceFloor(int x,int y){for(int[] row:ROWS)if(y==row[0]&&x>=row[1]&&x<=row[2])return true;return false;}
    @Override public void init(){threshold=Random.get(2,4);nextIcicle=Random.get(1)==0;begin(available(),1362,4510);}
    @Override protected boolean arena(Position p){return p.getZ()==plane&&sourceFloor(p.getX()-dx,p.getY()-dy);}
    @Override public void follow(){follow(1);}
    @Override public boolean attack(){
        if(!ready||target==null||!valid(target.player)||!withinDistance(1))return false;
        Player victim=target.player;
        if(attacks>=threshold){attacks=0;threshold=Random.get(2,4);if(nextIcicle||victim.getAbsX()-dx>=1372)icicle(victim);else unstable(victim);nextIcicle=!nextIcicle;}
        else{attacks++;standard(victim);}
        updateLastAttack(8);return false;
    }
    private void standard(Player victim){
        npc.animate(animation(11530));Position stood=victim.getPosition().copy();Set<Position> spikes=new HashSet<>();
        for(int[] row:ROWS)for(int x=row[1];x<=row[2];x++){
            Position at=tile(x,row[0]);int distance=Math.max(Math.abs(at.getX()-stood.getX()),Math.abs(at.getY()-stood.getY()));
            if(distance<=6&&Random.get(99)<(distance==0?77:distance==1?64:28)){spikes.add(at);tileGraphic(2942,at,0);}
        }
        victim.hit(new Hit(npc,AttackStyle.MAGIC,AttackType.ACCURATE).randDamage(22).delay(0));
        after(3,()->{for(Player p:players())if(spikes.contains(p.getPosition()))p.hit(new Hit(npc).randDamage(6,10).delay(0));pool(stood);});
    }
    private void icicle(Player victim){
        npc.animate(animation(11531));victim.getPrayer().deactivateAll();victim.sendMessage("<col=ff3045>Amoxliatl disables your prayers and launches an icicle attack!");
        new Projectile(graphic(2936),112,0,50,100,0,128,83).send(npc,victim);
        after(5,()->{if(valid(victim)){victim.graphics(graphic(2935));victim.hit(new Hit(npc,AttackStyle.MAGIC,AttackType.ACCURATE).randDamage(34).delay(0));}});
    }
    private void unstable(Player victim){
        npc.animate(animation(11533));victim.sendMessage("<col=00ffff>Amoxliatl forms unstable ice blocks around you.");
        List<Position> choices=new ArrayList<>();Position at=victim.getPosition();
        for(int x=at.getX()-4;x<=at.getX()+4;x++)for(int y=at.getY()-4;y<=at.getY()+4;y++){
            Position candidate=new Position(x,y,plane);if(x-dx>=1372||candidate.equals(at)||!free(candidate))continue;
            if(x>=npc.getAbsX()&&x<npc.getAbsX()+npc.getSize()&&y>=npc.getAbsY()&&y<npc.getAbsY()+npc.getSize())continue;
            boolean taken=false;for(NPC child:children)if(!child.isRemoved()&&child.getPosition().equals(candidate)){taken=true;break;}if(!taken)choices.add(candidate);
        }
        Collections.shuffle(choices);List<Position> selected=choices.subList(0,Math.min(choices.size(),Random.get(1,4)));
        for(Position pos:selected){new Projectile(graphic(2936),112,0,50,40,0,255,83).send(npc,pos);tileGraphic(2935,pos,90);}
        after(3,()->{for(Position pos:selected){if(!free(pos))continue;NPC ice=new NPC(Boss242Assets.id("npcs",13688)).spawn(pos);children.add(ice);ice.getCombat().setAllowRespawn(false);
            if(!(ice.getCombat() instanceof UnstableIce242)){ice.remove();continue;}((UnstableIce242)ice.getCombat()).owner=this;ice.animate(animation(11536));after(15,()->shatter(ice));}
        });
    }
    void broken(NPC ice){if(!children.remove(ice))return;Position pos=ice.getPosition().copy();after(1,()->{ice.remove();pool(pos);});}
    private void shatter(NPC ice){
        if(ice.isRemoved()||ice.getHp()<=0||!children.remove(ice))return;
        Position pos=ice.getPosition().copy();ice.animate(animation(11035));npc.hit(new Hit(HitType.HEAL).fixedDamage(Random.get(16,25)).delay(0));
        after(1,()->{ice.remove();for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)pool(new Position(pos.getX()+x,pos.getY()+y,plane));});
    }
    private void pool(Position pos){
        if(isDead()||pools.containsKey(pos)||!free(pos))return;
        GameObject object=new GameObject(Boss242Assets.id("objects",54279),pos,10,0).skipClipping(true).spawn();pools.put(pos.copy(),object);scenery.add(object);
    }
    @Override protected void pulse(){for(Player p:players())if(pools.containsKey(p.getPosition()))p.hit(new Hit(npc).randDamage(6,10).delay(0));}
    @Override public void close(){for(Position pos:pools.keySet())tileGraphic(2938,pos,0);super.close();pools.clear();}
    @Override public void reset(){super.reset();attacks=0;threshold=Random.get(2,4);}
}
