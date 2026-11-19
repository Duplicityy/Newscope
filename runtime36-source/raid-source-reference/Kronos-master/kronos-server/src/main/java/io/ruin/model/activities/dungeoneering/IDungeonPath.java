package io.ruin.model.activities.dungeoneering;

import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.routes.DumbRoute;
import java.util.*;

/** Bounded native eight-direction routes. Maze passes remain real object clicks. */
final class IDungeonPath {
    static final class Step {final Position at;final GameObject object;Step(Position at,GameObject object){this.at=at;this.object=object;}}
    static long key(Position p){return key(p.getX(),p.getY());}
    static long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
    static boolean adjacent(Position from,Position to){return Math.max(Math.abs(from.getX()-to.getX()),Math.abs(from.getY()-to.getY()))==1&&DumbRoute.getDirection(from.getX(),from.getY(),from.getZ(),1,to.getX(),to.getY())!=null;}
    static Step next(DungeoneeringFloor.RoomState r,Position from,Position goal,int radius,Set<Long> avoid){
        long start=key(from),found=Long.MIN_VALUE;
        Map<Long,Long> prev=new HashMap<>();Map<Long,GameObject> special=new HashMap<>();ArrayDeque<Position> queue=new ArrayDeque<>();
        prev.put(start,start);queue.add(from);
        List<GameObject> barriers=new ArrayList<>();
        if(r.puzzle instanceof DungeoneeringMaze)for(GameObject o:r.objects()){
            int source=r.assets().sourceObject(o.id);
            if(source==49341||source==49344&&((DungeoneeringMaze)r.puzzle).started)barriers.add(o);
        }
        while(!queue.isEmpty()){
            Position at=queue.remove();long parent=key(at);
            if(at.isWithinDistance(goal,radius)){found=parent;break;}
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0)continue;Position to=at.relative(dx,dy);long cell=key(to);
                if(r.dungeon().room(to)!=r||prev.containsKey(cell)||avoid.contains(cell)||!adjacent(at,to))continue;
                prev.put(cell,parent);queue.add(to);
            }
            for(GameObject o:barriers){
                Position on=o.getPosition(),to=null;
                if(at.equals(on)){int[] dx={-1,0,1,0},dy={0,1,0,-1};to=on.relative(dx[o.direction],dy[o.direction]);}
                else if(at.isWithinDistance(on,1))to=on;
                if(to==null||r.dungeon().room(to)!=r||prev.containsKey(key(to))||avoid.contains(key(to)))continue;
                prev.put(key(to),parent);special.put(key(to),o);queue.add(to);
            }
        }
        if(found==Long.MIN_VALUE)return null;
        if(found==start)return new Step(from,null);
        long at=found;while(prev.get(at)!=start)at=prev.get(at);
        return new Step(new Position((int)(at>>32),(int)at,from.getZ()),special.get(at));
    }
    private IDungeonPath(){}
}
