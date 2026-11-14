package io.ruin.content.content242.bosses;

import io.ruin.api.utils.Random;
import io.ruin.model.combat.Hit;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import java.util.*;

/** Per-encounter spike objects, explicit native clipping, moving hazards and shockwave shelter. */
final class MuspahSpikes242 {
    private static final int[][] CARDINAL={{0,1},{1,0},{0,-1},{-1,0}};
    private static final int[][] AROUND={{0,1},{1,0},{0,-1},{-1,0},{1,1},{1,-1},{-1,-1},{-1,1}};
    final PhantomMuspah242 fight;
    final Map<Position,Spike> spikes=new LinkedHashMap<>();
    final Map<Position,int[]> originalMasks=new HashMap<>();
    boolean closed;
    long lastKnock=-1;
    static final class Spike {Position at;int state;long next,until;GameObject object;Spike(Position p){at=p.copy();}}
    MuspahSpikes242(PhantomMuspah242 fight){this.fight=fight;}
    boolean has(Position p){return spikes.containsKey(p);}
    boolean open(Position p){return !closed&&fight.free(p)&&!has(p)&&!fight.underBoss(p);}
    void block(Position p){Tile tile=Tile.get(p,true);originalMasks.putIfAbsent(p.copy(),new int[]{tile.clipping,tile.projectileClipping});tile.clipping|=0x200100;tile.projectileClipping|=0x20100;}
    void unblock(Position p){int[] masks=originalMasks.remove(p);if(masks!=null){Tile tile=Tile.get(p,true);tile.clipping=masks[0];tile.projectileClipping=masks[1];}}
    void removeObject(Spike s){if(s.object!=null){s.object.remove();fight.scenery.remove(s.object);s.object=null;}}
    void visual(Spike s,int source){removeObject(s);s.object=new GameObject(Boss242Assets.id("objects",source),s.at,10,Random.get(3)).skipClipping(true).spawn();fight.scenery.add(s.object);}
    void wave(Collection<Position> positions){
        for(Position p:new LinkedHashSet<>(positions)){
            if(!open(p))continue;Spike spike=new Spike(p);spikes.put(spike.at,spike);fight.tileGraphic(2335,p,0);
            fight.after(2,()->{if(closed||spikes.get(spike.at)!=spike)return;if(fight.underBoss(spike.at)){spikes.remove(spike.at);return;}
                spike.state=1;visual(spike,46693);fight.tileGraphic(2324,spike.at,0);knockAt(spike.at);block(spike.at);});
            fight.after(3,()->stand(spike));
        }
    }
    void stand(Spike s){if(closed||spikes.get(s.at)!=s||s.state!=1)return;s.state=2;visual(s,46695);}
    void initial(){List<Position> choices=new ArrayList<>();for(int[] p:MuspahArena242.tiles())if(Math.max(Math.abs(p[0]-2847),Math.abs(p[1]-4258))>=8){Position at=fight.tile(p[0],p[1]);if(open(at))choices.add(at);}Collections.shuffle(choices);
        List<Position> selected=new ArrayList<>();for(Position at:choices){if(selected.size()==2)break;boolean near=false;for(Position old:selected)if(Math.abs(at.getX()-old.getX())+Math.abs(at.getY()-old.getY())<8)near=true;if(!near)selected.add(at);}wave(selected);}
    List<List<Position>> groups(){Set<Position> left=new HashSet<>();for(Spike s:spikes.values())if(s.state==2)left.add(s.at);List<List<Position>> groups=new ArrayList<>();
        while(!left.isEmpty()){Position first=left.iterator().next();left.remove(first);List<Position> group=new ArrayList<>();group.add(first);
            for(int i=0;i<group.size();i++)for(int[] d:AROUND){Position next=offset(group.get(i),d);if(left.remove(next))group.add(next);}groups.add(group);}return groups;}
    static Position offset(Position p,int[] d){return new Position(p.getX()+d[0],p.getY()+d[1],p.getZ());}
    void spread(boolean growth){List<Position> positions=new ArrayList<>();for(Player p:fight.players())if(open(p.getPosition()))positions.add(p.getPosition().copy());
        for(List<Position> group:groups()){Set<Position> options=new HashSet<>();for(Position p:group)for(int[] d:CARDINAL){Position at=offset(p,d);if(open(at))options.add(at);}List<Position> choices=new ArrayList<>(options);Collections.shuffle(choices);positions.addAll(choices.subList(0,Math.min(choices.size(),growth?Math.max(1,(group.size()+1)/2):1)));}wave(positions);}
    void knockAt(Position at){for(Player p:fight.players())if(p.getPosition().equals(at)){
        if(lastKnock==fight.tick)return;lastKnock=fight.tick;
        Position next=null;for(int[] d:AROUND){Position option=offset(at,d);if(open(option)){next=option;break;}}
        p.animate(fight.animation(1114));if(next!=null){int dx=next.getX()-at.getX(),dy=next.getY()-at.getY();Direction facing=Math.abs(dx)>Math.abs(dy)?(dx>0?Direction.WEST:Direction.EAST):(dy>0?Direction.SOUTH:Direction.NORTH);p.getMovement().force(dx,dy,0,0,0,29,facing);}
        Hit hit=new Hit(fight.actor()).randDamage(9,15).delay(0);hit.postDamage(victim->fight.heal(hit.damage*3/4));p.hit(hit);
    }}
    void homing(){List<Spike> options=new ArrayList<>();for(Spike s:spikes.values())if(s.state==2&&!fight.underBoss(s.at)){
        boolean near=false;for(Player p:fight.players())if(Math.max(Math.abs(p.getAbsX()-s.at.getX()),Math.abs(p.getAbsY()-s.at.getY()))<=1)near=true;if(!near)options.add(s);}
        Collections.shuffle(options);for(Spike s:options.subList(0,Math.min(options.size(),Random.get(2,4)))){
            s.state=3;visual(s,46694);fight.tileGraphic(2325,s.at,0);unblock(s.at);
            fight.after(2,()->{if(closed||spikes.get(s.at)!=s)return;s.state=4;s.next=fight.tick+2;s.until=fight.tick+34;visual(s,46697);block(s.at);});
        }
    }
    void pulse(){if(closed)return;for(Spike s:new ArrayList<>(spikes.values()))if(s.state==4){
        if(fight.tick>=s.until){harden(s);continue;}if(fight.tick<s.next)continue;s.next=fight.tick+2;
        List<Player> players=fight.players();if(players.isEmpty())continue;Position target=players.get(0).getPosition();int dx=Integer.signum(target.getX()-s.at.getX()),dy=Integer.signum(target.getY()-s.at.getY());
        int[][] steps=Math.abs(target.getX()-s.at.getX())>=Math.abs(target.getY()-s.at.getY())?new int[][]{{dx,0},{0,dy}}:new int[][]{{0,dy},{dx,0}};
        for(int[] step:steps){if(step[0]==0&&step[1]==0)continue;Position next=offset(s.at,step);if(!open(next))continue;Position old=s.at;spikes.remove(old);removeObject(s);s.at=next;spikes.put(next,s);visual(s,46697);fight.tileGraphic(2326,next,0);block(next);knockAt(next);fight.after(1,()->{if(!has(old))unblock(old);});break;}
    }}
    void harden(Spike s){if(s.state!=4&&s.state!=3)return;s.state=1;visual(s,46693);block(s.at);fight.tileGraphic(2324,s.at,0);fight.after(1,()->stand(s));}
    void hardenAll(){for(Spike s:new ArrayList<>(spikes.values()))harden(s);}
    boolean sheltered(Position at){return MuspahArena242.sheltered(at.getX()-fight.dx,at.getY()-fight.dy,(x,y)->has(fight.tile(x,y)));}
    void clear(){if(closed)return;closed=true;for(Spike s:spikes.values())removeObject(s);spikes.clear();for(Position at:new ArrayList<>(originalMasks.keySet()))unblock(at);}
}
