package io.ruin.model.activities.dungeoneering;

import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.model.activities.raids.auto.RaidAutoActions;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.map.Position;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import java.util.*;

/** Solvers read current native puzzle state and submit only ordinary player actions. */
final class IDungeonPuzzlePlanner {
    final Player p;final IDungeonActions a;
    private DungeoneeringFloor.RoomState room;private List<Position> leverOrder;
    IDungeonPuzzlePlanner(Player p,IDungeonActions a){this.p=p;this.a=a;}
    void enter(DungeoneeringFloor.RoomState r){room=r;leverOrder=null;}
    boolean pulse(DungeoneeringFloor.RoomState r){
        DungeoneeringPuzzles puzzle=r.puzzle;
        if(puzzle instanceof DungeoneeringPuzzles.Levers)return levers((DungeoneeringPuzzles.Levers)puzzle);
        if(puzzle instanceof DungeoneeringPuzzles.FlipTiles)return flips((DungeoneeringPuzzles.FlipTiles)puzzle);
        if(puzzle instanceof DungeoneeringPuzzles.Crystals)return crystals((DungeoneeringPuzzles.Crystals)puzzle);
        if(puzzle instanceof DungeoneeringPuzzles.Camp)return camp((DungeoneeringPuzzles.Camp)puzzle);
        if(puzzle instanceof DungeoneeringPuzzles.Ghost)return ghost((DungeoneeringPuzzles.Ghost)puzzle);
        if(puzzle instanceof DungeoneeringPuzzles.Poltergeist)return poltergeist((DungeoneeringPuzzles.Poltergeist)puzzle);
        if(puzzle instanceof DungeoneeringPuzzles.Statues)return statues((DungeoneeringPuzzles.Statues)puzzle);
        if(puzzle instanceof DungeoneeringPuzzles.Ferret)return ferret((DungeoneeringPuzzles.Ferret)puzzle);
        if(puzzle instanceof DungeoneeringFlow)return flow((DungeoneeringFlow)puzzle);
        if(puzzle instanceof DungeoneeringMaze)return maze((DungeoneeringMaze)puzzle);
        throw new IllegalStateException("No solver for the current native puzzle");
    }
    String progress(DungeoneeringFloor.RoomState r){
        DungeoneeringPuzzles x=r.puzzle;if(x==null)return "none";if(x.complete)return "complete";
        if(x instanceof DungeoneeringPuzzles.Levers)return "levers:"+((DungeoneeringPuzzles.Levers)x).pulled.size();
        if(x instanceof DungeoneeringPuzzles.FlipTiles)return "flips:"+Arrays.deepToString(((DungeoneeringPuzzles.FlipTiles)x).green);
        if(x instanceof DungeoneeringPuzzles.Camp)return "camp:"+((DungeoneeringPuzzles.Camp)x).tasks.size();
        if(x instanceof DungeoneeringPuzzles.Ghost)return "ghost:"+((DungeoneeringPuzzles.Ghost)x).tasks.size();
        if(x instanceof DungeoneeringPuzzles.Poltergeist)return "censers:"+((DungeoneeringPuzzles.Poltergeist)x).lit.size();
        if(x instanceof DungeoneeringPuzzles.Statues){StringBuilder s=new StringBuilder("statues");for(NPC n:((DungeoneeringPuzzles.Statues)x).statues)if(n!=null)s.append(n.getPosition());return s.toString();}
        if(x instanceof DungeoneeringPuzzles.Ferret)return "ferret:"+((DungeoneeringPuzzles.Ferret)x).ferret.getPosition();
        if(x instanceof DungeoneeringFlow)return "flow:"+((DungeoneeringFlow)x).tasks;
        if(x instanceof DungeoneeringPuzzles.Crystals)return "crystals:"+Arrays.toString(((DungeoneeringPuzzles.Crystals)x).active);
        return x.getClass().getSimpleName();
    }
    private GameObject source(int... sources){for(GameObject o:room.objects())for(int id:sources)if(room.assets().sourceObject(o.id)==id)return o;return null;}
    private GameObject named(String name){for(GameObject o:room.objects())if(o.getDef().name.equalsIgnoreCase(name))return o;return null;}
    private Item held(int source){Integer id=room.assets().itemMappings.get(Integer.toString(source));if(id==null)return null;for(Item i:p.getInventory().getItems())if(i!=null&&i.getId()==id)return i;return null;}
    private GroundItem dropped(int source){int id=room.assets().item(source);for(GroundItem g:IDungeonActions.ground(room))if(g.id==id)return g;return null;}
    private NPC npc(int source){for(NPC n:room.npcs)if(!n.isRemoved()&&room.assets().sourceNpc(n.getId())==source)return n;return null;}
    private boolean object(GameObject o,int option){if(o==null)throw new IllegalStateException("Required native puzzle scenery is missing");if(!a.object(room,o,option))throw new IllegalStateException("No native route to "+o.getDef().name);return true;}
    private boolean levers(DungeoneeringPuzzles.Levers x){
        List<GameObject> remaining=new ArrayList<>();for(GameObject o:room.objects())if(o.getDef().name.equalsIgnoreCase("Switch")&&!x.pulled.containsKey(o))remaining.add(o);
        if(remaining.isEmpty())return true;
        if(x.pulled.isEmpty()||leverOrder==null){List<GameObject> best=new ArrayList<>();tour(p.getPosition(),remaining,new ArrayList<GameObject>(),0,new int[]{Integer.MAX_VALUE},best);leverOrder=new ArrayList<>();for(GameObject o:best)leverOrder.add(o.getPosition());}
        for(Position pos:leverOrder)for(GameObject o:remaining)if(o.getPosition().equals(pos))return object(o,1);
        return object(remaining.get(0),1);
    }
    private void tour(Position from,List<GameObject> left,List<GameObject> order,int cost,int[] minimum,List<GameObject> best){
        if(cost>=minimum[0])return;if(left.isEmpty()){minimum[0]=cost;best.clear();best.addAll(order);return;}
        for(GameObject o:new ArrayList<>(left)){left.remove(o);order.add(o);tour(o.getPosition(),left,order,cost+Math.max(0,IDungeon.distance(from,o.getPosition())-1),minimum,best);order.remove(order.size()-1);left.add(o);}
    }
    /** Gaussian elimination over GF(2), including either accepted uniform colour. */
    static long solveFlip(boolean[][] green,boolean goal){
        long[] rows=new long[25];for(int x=0;x<5;x++)for(int y=0;y<5;y++){
            int cell=x*5+y;long row=0;for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(Math.abs(dx)+Math.abs(dy)<=1){int xx=x+dx,yy=y+dy;if(xx>=0&&xx<5&&yy>=0&&yy<5)row|=1L<<(xx*5+yy);}
            if(green[x][y]!=goal)row|=1L<<25;rows[cell]=row;
        }
        int rank=0;int[] pivots=new int[25];for(int col=0;col<25;col++){
            int pivot=rank;while(pivot<25&&(rows[pivot]&(1L<<col))==0)pivot++;if(pivot==25)continue;
            long tmp=rows[rank];rows[rank]=rows[pivot];rows[pivot]=tmp;
            for(int row=0;row<25;row++)if(row!=rank&&(rows[row]&(1L<<col))!=0)rows[row]^=rows[rank];pivots[rank++]=col;
        }
        for(int i=rank;i<25;i++)if((rows[i]&((1L<<25)-1))==0&&(rows[i]&(1L<<25))!=0)return -1;
        long answer=0;for(int i=0;i<rank;i++)if((rows[i]&(1L<<25))!=0)answer|=1L<<pivots[i];return answer;
    }
    private boolean flips(DungeoneeringPuzzles.FlipTiles x){
        long yes=solveFlip(x.green,true),no=solveFlip(x.green,false),solution=yes<0?no:no<0?yes:Long.bitCount(yes)<=Long.bitCount(no)?yes:no;
        if(solution<0)throw new IllegalStateException("Native flip grid has no normal solution");GameObject best=null;int distance=Integer.MAX_VALUE;
        for(int i=0;i<25;i++)if((solution&(1L<<i))!=0){Position at=room.tile(x.ox+i/5,x.oy+i%5);for(GameObject o:room.objects())if(o.getPosition().equals(at)&&(room.assets().sourceObject(o.id)==49638||room.assets().sourceObject(o.id)==49642)){int d=IDungeon.distance(p.getPosition(),at);if(d<distance){best=o;distance=d;}}}
        return best==null||object(best,1);
    }
    private boolean crystals(DungeoneeringPuzzles.Crystals x){
        for(int i=0;i<4;i++)if(!x.active[i])return object(source(DungeoneeringPuzzles.Crystals.INACTIVE[i][room.theme()]),1);
        Set<Long> plates=new HashSet<>();int standing=-1;int[] phase=new int[4];for(int i=0;i<4;i++){Position plate=room.tile(DungeoneeringPuzzles.Crystals.PLATE[i][0],DungeoneeringPuzzles.Crystals.PLATE[i][1]);plates.add(IDungeonPath.key(plate));if(p.getPosition().equals(plate))standing=i;phase[i]=Math.abs(x.energy[i][0]-7)+Math.abs(x.energy[i][1]-8);}
        if(standing>=0){if(standing>0&&phase[standing]!=phase[0])return true;return stepOff(plates);}
        for(int i=1;i<4;i++)if(phase[i]!=phase[0]){Position at=room.tile(DungeoneeringPuzzles.Crystals.PLATE[i][0],DungeoneeringPuzzles.Crystals.PLATE[i][1]);Set<Long> avoid=new HashSet<>(plates);avoid.remove(IDungeonPath.key(at));if(!a.move(room,at,avoid))throw new IllegalStateException("No native route to the crystal pressure plate");return true;}
        return true; // Equal phase streams reach the centre through their native pulse.
    }
    private boolean stepOff(Set<Long> avoid){for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(dx!=0||dy!=0){Position at=p.getPosition().relative(dx,dy);if(room.dungeon().room(at)==room&&!avoid.contains(IDungeonPath.key(at))&&IDungeonPath.adjacent(p.getPosition(),at))return a.walk(at);}throw new IllegalStateException("No native step away from pressure plate");}
    private boolean camp(DungeoneeringPuzzles.Camp x){
        if(held(17754)==null||held(17883)==null){NPC scout=npc(11001);if(scout==null)throw new IllegalStateException("Camp scout missing");return a.npc(room,scout,1);}
        for(int i=0;i<3;i++)if(!x.tasks.contains(i))return object(source(DungeoneeringPuzzles.Camp.INPUT[i][room.theme()]),1);return true;
    }
    private boolean ghost(DungeoneeringPuzzles.Ghost x){
        if(!x.tasks.contains(1)&&held(19879)==null){GroundItem ring=dropped(19879);if(ring==null)throw new IllegalStateException("Find the ghost's native ring");return a.pickup(room,ring);}
        for(int i=0;i<5;i++)if(!x.tasks.contains(i))return object(source(DungeoneeringPuzzles.Ghost.SOURCES[i][room.theme()]),1);return true;
    }
    private boolean poltergeist(DungeoneeringPuzzles.Poltergeist x){
        if(x.lit.size()==4)return object(named("Sarcophagus"),2);
        GameObject full=source(DungeoneeringPuzzles.Poltergeist.CENSERS[room.theme()][1]);if(full!=null)return object(full,1);
        Item consecrated=held(19659);if(consecrated!=null)return a.use(room,consecrated,source(DungeoneeringPuzzles.Poltergeist.CENSERS[room.theme()][0]));
        Item herb=held(x.herb);if(herb!=null){int option=herb.getDef().getOption("Consecrate");if(option<1)throw new IllegalStateException("Native herb Consecrate option is missing");return RaidAutoActions.inventory(p,herb,"Consecrate");}
        if(x.available<=0)throw new IllegalStateException("No native puzzle herbs remain");
        if(IDungeonActions.select(p,ItemDef.get(room.assets().item(x.herb)).name))return true;
        return object(named("Herb patch"),1);
    }
    private boolean statues(DungeoneeringPuzzles.Statues x){
        // Joint two-statue BFS per bank avoids parking one statue across the other's route.
        for(int bank=0;bank<2;bank++){
            int offset=bank*2,bx=bank==0?2:9;int[] initial=new int[2],target=new int[2];
            for(int j=0;j<2;j++){int[] at=room.local(x.statues[offset+j+4].getPosition());initial[j]=(at[0]-bx)*5+at[1]-2;target[j]=(x.target[offset+j][0]-bx)*5+x.target[offset+j][1]-2;}
            if(Arrays.equals(initial,target))continue;
            int[] step=statueStep(initial,target);if(step==null)throw new IllegalStateException("Native statue bank has no legal solution");
            int[] dx={-1,0,1,0},dy={0,1,0,-1};int i=offset+step[0],d=step[1];NPC statue=x.statues[i+4];if(x.busy[i]>=Server.currentTick())return true;
            int[] local=room.local(statue.getPosition());Set<Long> occupied=new HashSet<>();for(NPC n:x.statues)occupied.add(IDungeonPath.key(n.getPosition()));
            Position push=room.tile(local[0]-dx[d],local[1]-dy[d]),pull=room.tile(local[0]+dx[d],local[1]+dy[d]);
            Position station=push;int option=1;
            if(occupied.contains(IDungeonPath.key(push))||IDungeonPath.next(room,p.getPosition(),push,0,occupied)==null){station=pull;option=2;}
            if(occupied.contains(IDungeonPath.key(station))||IDungeonPath.next(room,p.getPosition(),station,0,occupied)==null)throw new IllegalStateException("No legal player station for the next statue move");
            if(!p.getPosition().equals(station))return a.move(room,station,occupied);
            return a.npc(room,statue,option);
        }return true;
    }
    /** Joint bank state: two distinct cells on the native 5x5 marked area. */
    static int[] statueStep(int[] initial,int[] target){
        if(initial.length!=2||target.length!=2)return null;
        for(int cell:new int[]{initial[0],initial[1],target[0],target[1]})if(cell<0||cell>=25)return null;
        if(initial[0]==initial[1]||target[0]==target[1]||Arrays.equals(initial,target))return null;
        int start=initial[0]*25+initial[1],goal=target[0]*25+target[1];int[] previous=new int[625],moved=new int[625],direction=new int[625];Arrays.fill(previous,-1);ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(start);previous[start]=start;int[] dx={-1,0,1,0},dy={0,1,0,-1};
        while(!queue.isEmpty()&&previous[goal]<0){int state=queue.remove();int[] cells={state/25,state%25};for(int j=0;j<2;j++)for(int d=0;d<4;d++){int nx=cells[j]/5+dx[d],ny=cells[j]%5+dy[d];if(nx<0||nx>4||ny<0||ny>4||nx*5+ny==cells[1-j])continue;int next=j==0?(nx*5+ny)*25+cells[1]:cells[0]*25+nx*5+ny;if(previous[next]>=0)continue;previous[next]=state;moved[next]=j;direction[next]=d;queue.add(next);}}
        if(previous[goal]<0)return null;int first=goal;while(previous[first]!=start)first=previous[first];return new int[]{moved[first],direction[first]};
    }
    private boolean ferret(DungeoneeringPuzzles.Ferret x){
        if(!x.fish.isEmpty())return true;
        Item cooked=held(17375);if(cooked==null){Item raw=held(17374);if(raw!=null)return a.use(room,raw,named("Fire"));NPC spot=npc(1957);if(spot!=null)return a.npc(room,spot,1);return object(named("Fishing spot"),1);}
        Set<Long> holes=new HashSet<>();for(GameObject o:room.objects())if(o.getDef().name.equals("Hole"))holes.add(IDungeonPath.key(o.getPosition()));
        // Ferret uses DumbRoute, so choose one safe native neighbour as each actual bait target.
        IDungeonPath.Step next=IDungeonPath.next(room,x.ferret.getPosition(),x.plate,0,holes);
        if(next==null)throw new IllegalStateException("No hole-free native ferret route");
        for(GameObject o:room.objects())if(o.getPosition().equals(next.at)&&(o.getDef().name.equals("Tile")||o.getDef().name.equals("Pressure plate")))return a.use(room,cooked,o);
        throw new IllegalStateException("No native bait tile on the ferret route");
    }
    private boolean flow(DungeoneeringFlow x){GameObject o=source(DungeoneeringFlow.RUBBLE_FLOW[room.theme()],DungeoneeringFlow.RUBBLE[room.theme()],DungeoneeringFlow.PILLAR_BROKEN[room.theme()]);return o==null||object(o,1);}
    private boolean maze(DungeoneeringMaze x){return x.started?x.looted||object(named("Locked chest"),1):object(named("Switch"),1);}
}
