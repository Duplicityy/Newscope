package io.ruin.model.activities.dungeoneering;

import io.ruin.Server;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.model.combat.Hit;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.map.*;
import io.ruin.model.map.dynamic.*;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.stat.StatType;
import java.util.*;

/** A 128x128 maximum native dynamic-map lease shared by one party. */
public final class DungeoneeringFloor {
    public final String id=UUID.randomUUID().toString();
    public final Dungeoneering.Party party;
    public final DungeoneeringAssets assets;
    public final DungeonGraph graph;
    public final long seed,created=Server.currentTick();
    public final int startingPartyCount,minStartingCombat,maxStartingCombat;
    public final Map<Integer,Integer> deaths=new HashMap<>();
    final Random random;
    final DynamicMap map;
    final RoomState[] rooms;
    final Set<Player> entered=Collections.newSetFromMap(new IdentityHashMap<Player,Boolean>());
    final Map<Player,Position> gatestones=new IdentityHashMap<>();
    final Set<Integer> keys=new HashSet<>();
    final Map<Long,Edge> edges=new HashMap<>();
    final Map<NPC,RoomState> actors=new IdentityHashMap<>();
    boolean destroyed;
    public boolean complete;
    private static final int[] DX={-1,0,1,0},DY={0,1,0,-1};
    private static final int[] DOORS={50342,50343,50344,53948,55762}, KEY_DOORS={50353,50417,50481,53884,55675}, GUARDIAN_DOORS={50346,50347,50348,53949,55763};
    private static final StatType[] DOOR_SKILLS={StatType.Runecrafting,StatType.Strength,StatType.Mining,StatType.Firemaking,StatType.Magic,StatType.Prayer,StatType.Woodcutting,StatType.Smithing,StatType.Crafting,StatType.Agility,StatType.Thieving,StatType.Herblore,StatType.Farming,StatType.Construction};
    private static final int[][] SKILL_DOORS={
        {50278,50279,50280,53953,55741},{50272,50273,50274,53951,55739},{50305,50306,50307,53962,55750},
        {50314,50315,50316,53965,55753},{50329,50330,50331,53970,55758},{50332,50333,50334,53971,55759},
        {50317,50318,50319,53966,55754},{50308,50309,50310,53963,55751},{50299,50300,50301,53960,55748},
        {-1,50288,50289,53956,55744},{-1,50294,50295,53958,55746},{-1,50336,50337,53972,55760},
        {-1,50324,50325,53968,55756},{-1,50282,50283,53954,55742},{-1,50327,50328,53969,55757}};
    static final class Edge {
        final int a,b,key;int skill=-1,level=1;boolean guardian,open;
        Edge(int a,int b,int key){this.a=a;this.b=b;this.key=key;}
    }
    private static long edgeKey(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    static void collectRuntimeAssets(DungeoneeringRuntimeAssets.Required r){
        r.add("objects",DOORS);r.add("objects",GUARDIAN_DOORS);for(int base:KEY_DOORS)r.range("objects",base,base+63);for(int[] doors:SKILL_DOORS)r.add("objects",doors);
        for(int key=0;key<64;key++)r.add("items",18202+key*2);r.add("objects",49696,49698,49700,53748,55484);r.add("npcs",11226);
    }
    public static Map<String,int[]> requiredRuntimeAssets(DungeoneeringAssets assets){return DungeoneeringRuntimeAssets.requiredAssets(assets);}

    public DungeoneeringFloor(Dungeoneering.Party party,DungeoneeringAssets assets,long seed){
        this.party=party;this.assets=assets;this.seed=seed;random=new Random(seed);graph=DungeonGraph.generate(seed,party.size,party.complexity);rooms=new RoomState[graph.nodes.size()];
        startingPartyCount=party.members.size();int minimum=126,maximum=1;for(Player member:party.members){int level=member.getCombat().getLevel();minimum=Math.min(minimum,level);maximum=Math.max(maximum,level);}minStartingCombat=minimum;maxStartingCombat=maximum;
        DynamicMap built=null;
        try{
            for(DungeonGraph.Node node:graph.nodes){
                boolean puzzle=node.id!=graph.startId&&!node.boss&&node.neighbours.size()>1&&party.complexity==6&&random.nextInt(10)==0;
                String kind=node.id==graph.startId?"StartRoom":node.boss?"BossRoom":puzzle?"PuzzleRoom":"NormalRoom";
                List<RoomState> matches=matching(node,kind);
                if(matches.isEmpty()&&puzzle)matches=matching(node,"NormalRoom");
                if(matches.isEmpty())throw new IllegalStateException("No supported native template for "+kind+" "+node.id+" / "+DungeoneeringAssets.theme(party.floor));
                rooms[node.id]=matches.get(random.nextInt(matches.size()));
                if(node.parent>=0){Edge edge=new Edge(node.parent,node.id,node.lock);if(edge.key<0&&party.complexity>=5&&random.nextInt(3)==0){
                    List<Integer> available=new ArrayList<>();for(int s=0;s<SKILL_DOORS.length;s++)if(SKILL_DOORS[s][DungeoneeringAssets.themeIndex(party.floor)]>=0)available.add(s);
                    edge.skill=available.get(random.nextInt(available.size()));int max=1;for(Player p:party.members)max=Math.max(max,edge.skill==DOOR_SKILLS.length?DungeoneeringSummoning.level(p):p.getStats().get(DOOR_SKILLS[edge.skill]).fixedLevel);
                    edge.level=(node.critical||party.fullClear)?Math.max(1,max-random.nextInt(10)):1+random.nextInt(99);
                }else if(edge.key<0&&party.complexity>=3&&random.nextBoolean())edge.guardian=true;
                edges.put(edgeKey(edge.a,edge.b),edge);}
            }
            built=new DynamicMap();map=built;
            List<DynamicChunk> chunks=new ArrayList<>();
            for(RoomState room:rooms)for(int z=0;z<2;z++)for(int sx=0;sx<2;sx++)for(int sy=0;sy<2;sy++){
                int tx=room.rotation==0?sx:room.rotation==1?sy:room.rotation==2?1-sx:1-sy;
                int ty=room.rotation==0?sy:room.rotation==1?1-sx:room.rotation==2?1-sy:sx;
                chunks.add(new DynamicChunk(room.template.chunk[0]+sx,room.template.chunk[1]+sy,z).pos(room.node.x*2+tx,room.node.y*2+ty,z).rotate(room.rotation));
            }
            map.build(chunks);
            for(RoomState room:rooms){
                for(int x=0;x<16;x++)for(int y=0;y<16;y++){Position p=room.unrotatedTile(x,y);Tile.get(p.getX(),p.getY(),0,true).multi=true;}
                room.installDoors();
            }
        }catch(RuntimeException e){if(built!=null)built.destroy();throw e;}
    }
    private List<RoomState> matching(DungeonGraph.Node node,String kind){
        int required=0;for(int other:node.neighbours)required|=1<<graph.direction(node.id,other);
        List<RoomState> matches=new ArrayList<>();
        for(DungeoneeringAssets.Room template:assets.select(kind,party.floor,party.complexity)){
            if("PuzzleRoom".equals(kind)&&!DungeoneeringPuzzles.supported(template.puzzle))continue;
            if("BossRoom".equals(kind)){
                if(template.npcSpawns==null||template.npcSpawns.length==0)continue;
                boolean supported=true;for(DungeoneeringAssets.Spawn spawn:template.npcSpawns)if(!DungeoneeringCombat.supportedBoss(spawn.id))supported=false;
                if(!supported||party.selectedBoss>=0&&template.npcSpawns[0].id!=party.selectedBoss)continue;
            }
            for(int rotation=0;rotation<4;rotation++){int mask=0;for(int door:template.doors)mask|=1<<((door+rotation)&3);if(mask==required)matches.add(new RoomState(node,template,rotation));}
        }return matches;
    }
    public Position startTile(){return rooms[graph.startId].tile(7,7);}
    public boolean contains(Player p){return contains(p.getAbsX(),p.getAbsY(),p.getHeight());}
    public boolean contains(int x,int y,int z){return !destroyed&&map.swRegion!=null&&z==0&&x>=map.swRegion.baseX&&y>=map.swRegion.baseY&&x<map.swRegion.baseX+graph.width*16&&y<map.swRegion.baseY+graph.height*16;}
    public boolean owns(NPC npc){return actors.containsKey(npc);}
    public void entered(Player p){if(destroyed){Dungeoneering.exit(p,false);return;}entered.add(p);rooms[graph.startId].open();}
    public void departed(Player p){entered.remove(p);gatestones.remove(p);if(entered.isEmpty())destroy();}
    public void abort(String message){if(destroyed)return;for(Player p:new ArrayList<>(party.members))if(Dungeoneering.inRun(p)){p.sendMessage(message);Dungeoneering.exit(p,false);if(!p.isOnline())entered.remove(p);}if(entered.isEmpty())destroy();}
    private void destroy(){if(destroyed)return;destroyed=true;for(RoomState r:rooms){if(r.puzzle!=null)r.puzzle.destroy();DungeoneeringSkills.clear(r);}DungeoneeringSpells.close(this);disposeGroundItems();actors.clear();map.destroy();Dungeoneering.closed(this);}
    /** Invalidate ground-item timers before Region.destroy clears its tile lists. */
    private void disposeGroundItems(){Region origin=map.swRegion;if(origin==null)return;int lease=origin.dynamicIndex;if(lease<0)throw new IllegalStateException("Dungeon map has no active region lease");for(int offset:new int[]{0,256,1,257}){Region owned=Region.LOADED[origin.id+offset];if(owned==null||owned.dynamicIndex!=lease)throw new IllegalStateException("Refusing cleanup outside the dungeon's owned region lease");for(int z=0;z<4;z++)for(int x=owned.baseX;x<owned.baseX+64;x++)for(int y=owned.baseY;y<owned.baseY+64;y++){Tile tile=Tile.get(x,y,z);if(tile!=null&&tile.groundItems!=null)for(GroundItem item:new ArrayList<>(tile.groundItems))if(!item.isRemoved())item.remove();}}}
    public void pulse(){
        if(destroyed)return;
        if(Server.currentTick()-created>12000){abort("Your dungeon expired after two hours. Your original equipment is restored.");return;}
        for(Player p:new ArrayList<>(entered))if(p.isOnline()&&contains(p)){RoomState room=room(p.getPosition());if(room!=null)room.open();}
        for(RoomState room:rooms)if(room.opened){if(room.puzzle!=null)room.puzzle.pulse();DungeoneeringCombat.pulse(room);DungeoneeringGathering.pulse(room);}
    }
    RoomState room(Position position){if(!contains(position.getX(),position.getY(),position.getZ()))return null;int x=(position.getX()-map.swRegion.baseX)/16,y=(position.getY()-map.swRegion.baseY)/16;for(RoomState room:rooms)if(room.node.x==x&&room.node.y==y)return room;return null;}
    public void status(Player p){
        List<String> text=new ArrayList<>();text.add("Floor "+party.floor+" - "+DungeoneeringAssets.theme(party.floor)+", complexity "+party.complexity);text.add("Seed: "+seed);text.add("Rooms explored: "+openedCount()+" / "+rooms.length);text.add("Boss: "+(complete?"defeated":"alive"));text.add("Shared keys: "+keys.size());
        for(int y=graph.height-1;y>=0;y--){StringBuilder line=new StringBuilder();for(int x=0;x<graph.width;x++){RoomState found=null;for(RoomState room:rooms)if(room.node.x==x&&room.node.y==y)found=room;line.append(found==null?" . ":!found.opened?" ? ":found.node.id==graph.startId?" S ":found.node.boss?" B ":found.puzzle!=null&&!found.puzzle.complete?" P ":" + ");}text.add(line.toString());}
        text.add("S=start, B=boss, P=unsolved puzzle, +=explored, ?=unexplored.");p.sendScroll("Dungeon party map",text.toArray(new String[0]));
    }
    boolean mayClaim(){return complete&&(!party.fullClear||openedCount()==rooms.length);}
    public int openedCount(){int count=0;for(RoomState r:rooms)if(r.opened)count++;return count;}
    public int bonusOpened(){int count=0;for(RoomState r:rooms)if(r.opened&&!r.node.critical)count++;return count;}
    public void setGatestone(Player p){if(!entered.contains(p)||!contains(p)||p.getCombat().isDefending(10))return;gatestones.put(p,p.getPosition().copy());p.sendMessage("Personal gatestone placed.");}
    public void gatestone(Player p,boolean personal){if(!entered.contains(p)||!contains(p))return;if(p.getCombat().isDefending(10)){p.sendMessage("You cannot use a gatestone while under attack.");return;}Position target=personal?gatestones.get(p):startTile();if(target==null){p.sendMessage("Place a personal gatestone first.");return;}Dungeoneering.relocate(p,target);}
    static void register(DungeoneeringAssets assets){
        for(int id:assets.objectMappings.values())for(int option=1;option<=5;option++){final int selected=option;ObjectAction.register(id,option,(p,o)->{DungeoneeringFloor floor=Dungeoneering.floor(p);if(floor!=null&&floor.contains(p))floor.clickObject(p,o,selected);});}
        for(int id:assets.npcMappings.values())for(int option=1;option<=5;option++){final int selected=option;NPCAction.register(id,option,(p,npc)->{DungeoneeringFloor floor=Dungeoneering.floor(p);if(floor!=null&&floor.contains(p))floor.clickNpc(p,npc,selected);});}
    }
    public boolean clickObject(Player p,GameObject object,int option){
        RoomState room=room(object.getPosition());if(room==null||!entered.contains(p))return false;
        int source=assets.sourceObject(object.id);if(source<0)return false;
        if(DungeoneeringCombat.clickObject(room,p,object,source,option))return true;
        if(room.puzzle!=null&&room.puzzle.object(p,object,source,option))return true;
        String name=object.getDef().name.toLowerCase(Locale.ROOT);
        if(name.contains("ladder")||name.equals("dungeon exit")){if(complete)Dungeoneering.exit(p,true);else Dungeoneering.open(p);return true;}
        int localX=object.x-map.swRegion.baseX-room.node.x*16,localY=object.y-map.swRegion.baseY-room.node.y*16;
        if(isDoor(source)||name.endsWith("door")||name.equals("magical barrier")){
            int direction=localX<=1?0:localY>=14?1:localX>=14?2:localY<=1?3:-1;
            if(direction>=0){for(int target:room.node.neighbours)if(graph.direction(room.node.id,target)==direction){cross(p,room,rooms[target]);return true;}}
        }
        if(DungeoneeringSkills.object(room,p,object,source,option))return true;
        p.sendMessage("There is no useful interaction with that scenery.");return true;
    }
    public boolean clickNpc(Player p,NPC npc,int option){RoomState room=actors.get(npc);if(room==null||!entered.contains(p))return false;int source=assets.sourceNpc(npc.getId());if(DungeoneeringCombat.clickNpc(room,p,npc,source,option))return true;if(room.puzzle!=null&&room.puzzle.npc(p,npc,source,option))return true;if(source==11226){DungeoneeringSkills.smuggler(p,this);return true;}return DungeoneeringSkills.npc(room,p,npc,source,option);}
    public boolean itemObject(Player p,Item item,GameObject object){RoomState room=room(object.getPosition());if(room==null||!entered.contains(p))return false;int source=assets.sourceObject(object.id);if(room.puzzle!=null&&room.puzzle.itemObject(p,item,object,source))return true;return DungeoneeringSkills.itemObject(room,p,item,object,source);}
    public boolean pickup(Player p,GroundItem item,int distance){
        int source=assets.sourceItem(item.id);if(source<18202||source>18328||(source-18202)%2!=0)return false;
        if(!entered.contains(p)||distance>1||item.isRemoved())return true;
        int key=(source-18202)/2;if(keys.add(key)){item.remove();party.say("Shared key found: "+io.ruin.cache.ItemDef.get(item.id).name+".");}else item.remove();return true;
    }
    private static boolean isDoor(int id){for(int v:DOORS)if(v==id)return true;for(int v:KEY_DOORS)if(id>=v&&id<v+64)return true;for(int v:GUARDIAN_DOORS)if(v==id)return true;for(int[] group:SKILL_DOORS)for(int v:group)if(id==v)return true;return id>=50208&&id<50272;}
    private void cross(Player p,RoomState from,RoomState to){
        if(!contains(p)||!entered.contains(p))return;
        Edge edge=edges.get(edgeKey(from.node.id,to.node.id));if(edge==null)return;
        if(!edge.open){
            if(edge.key>=0&&!keys.contains(edge.key)){p.sendMessage("This door needs the "+io.ruin.cache.ItemDef.get(assets.item(18202+edge.key*2)).name+".");return;}
            if(from.puzzle!=null&&!from.puzzle.complete&&to.node.parent==from.node.id){p.sendMessage("Complete this room's puzzle before continuing.");return;}
            if(edge.guardian&&from.aliveGuardians()>0){p.sendMessage("Defeat the guardians in this room first.");return;}
            if(edge.skill>=0){boolean summoning=edge.skill==DOOR_SKILLS.length;StatType skill=summoning?null:DOOR_SKILLS[edge.skill];int current=summoning?DungeoneeringSummoning.level(p):DungeoneeringSkills.effectiveLevel(p,skill);if(current<edge.level){p.sendMessage("You need "+(summoning?"Summoning":skill.name())+" level "+edge.level+" to open this door.");return;}if(summoning)DungeoneeringSummoning.addXp(p,edge.level*5.0+10);else DungeoneeringSkills.xp(p,skill,edge.level*5.0+10);p.animate(832);}
            edge.open=true;
        }
        to.open();int direction=graph.direction(from.node.id,to.node.id);int x=direction==0?13:direction==2?2:7,y=direction==1?2:direction==3?13:7;Dungeoneering.relocate(p,to.unrotatedTile(x,y));
    }

    public final class RoomState implements DungeoneeringCombat.Arena {
        public final DungeonGraph.Node node;
        public final DungeoneeringAssets.Room template;
        public final int rotation;
        final Set<NPC> npcs=Collections.newSetFromMap(new IdentityHashMap<NPC,Boolean>()),bosses=Collections.newSetFromMap(new IdentityHashMap<NPC,Boolean>()),dead=Collections.newSetFromMap(new IdentityHashMap<NPC,Boolean>()),guardians=Collections.newSetFromMap(new IdentityHashMap<NPC,Boolean>());
        final List<GroundItem> groundItems=new ArrayList<>();
        public DungeoneeringPuzzles puzzle;
        public boolean opened;
        RoomState(DungeonGraph.Node node,DungeoneeringAssets.Room template,int rotation){this.node=node;this.template=template;this.rotation=rotation;}
        public DungeoneeringFloor dungeon(){return DungeoneeringFloor.this;}
        public boolean active(){return !destroyed&&opened;}
        public boolean contains(Player p){return entered.contains(p)&&room(p.getPosition())==this;}
        public List<Player> players(){List<Player> result=new ArrayList<>();for(Player p:entered)if(p.isOnline()&&contains(p))result.add(p);return result;}
        public DungeoneeringAssets assets(){return assets;}
        public Random random(){return random;}
        public int floor(){return party.floor;}
        boolean supplyKit(){return party.supplyKit;}
        public int complexity(){return party.complexity;}
        public int theme(){return DungeoneeringAssets.themeIndex(party.floor);}
        public Position unrotatedTile(int x,int y){return new Position(map.swRegion.baseX+node.x*16+x,map.swRegion.baseY+node.y*16+y,0);}
        public Position tile(int x,int y){return position(x,y,1,1,0);}
        private Position position(int x,int y,int width,int height,int direction){
            if((direction&1)==1){int old=width;width=height;height=old;}
            int tx=rotation==0?x:rotation==1?y:rotation==2?15-x-(width-1):15-y-(height-1);
            int ty=rotation==0?y:rotation==1?15-x-(width-1):rotation==2?15-y-(height-1):x;return unrotatedTile(tx,ty);
        }
        public int[] local(Position position){int x=position.getX()-map.swRegion.baseX-node.x*16,y=position.getY()-map.swRegion.baseY-node.y*16;return rotation==0?new int[]{x,y}:rotation==1?new int[]{15-y,x}:rotation==2?new int[]{15-x,15-y}:new int[]{y,15-x};}
        public NPC spawn(int source,int x,int y){
            boolean mainBoss=node.boss&&isMainBoss(source);source=DungeoneeringCombat.selectNpc(this,source,mainBoss);
            int target=assets.npc(source),size=NPCDef.get(target).size;Position position=position(x,y,size,size,0);
            NPC npc=new NPC(target).spawn(position);npc.sixSystemsRewardless=true;map.addNpc(npc);npcs.add(npc);actors.put(npc,this);DungeoneeringCombat.attach(npc,this,source,mainBoss);return npc;
        }
        private boolean isMainBoss(int source){if(template.npcSpawns!=null)for(DungeoneeringAssets.Spawn spawn:template.npcSpawns)if(spawn.id==source)return true;return false;}
        public void npcDefeated(NPC npc){
            if(!npcs.contains(npc)||!dead.add(npc))return;
            if(bosses.contains(npc)){boolean done=true;for(NPC boss:bosses)if(!dead.contains(boss))done=false;if(done&&!complete){complete=true;party.say("The dungeon boss has been defeated! Use the exit ladder or ::dung to claim your floor rewards.");object(new int[]{49696,49698,49700,53748,55484}[theme()],10,0,7,7);}}
            else if(guardians.contains(npc))DungeoneeringSkills.monsterDrop(this,npc);
        }
        public void object(int source,int shape,int direction,int x,int y){ObjectDef def=ObjectDef.get(assets.object(source));Position position=position(x,y,def.xLength,def.yLength,direction);GameObject old=Tile.getObject(-1,position.getX(),position.getY(),0,shape,-1);if(old!=null)old.remove();new GameObject(assets.object(source),position,shape,(direction+rotation)&3).spawn();}
        public GameObject getObject(int x,int y,int shape){for(GameObject object:objects())if(object.type==shape){int[] local=localObject(object);if(local[0]==x&&local[1]==y)return object;}return null;}
        public int[] localObject(GameObject object){int x=object.x-map.swRegion.baseX-node.x*16,y=object.y-map.swRegion.baseY-node.y*16;ObjectDef def=object.getDef();int width=def.xLength,height=def.yLength;if(((object.direction-rotation)&1)==1){int old=width;width=height;height=old;}return rotation==0?new int[]{x,y}:rotation==1?new int[]{15-y-(width-1),x}:rotation==2?new int[]{15-x-(width-1),15-y-(height-1)}:new int[]{y,15-x-(height-1)};}
        public void removeObject(int x,int y,int shape){GameObject object=getObject(x,y,shape);if(object!=null)object.remove();}
        public List<GameObject> objects(){List<GameObject> result=new ArrayList<>();for(int x=0;x<16;x++)for(int y=0;y<16;y++){Position pos=unrotatedTile(x,y);Tile tile=Tile.get(pos);if(tile!=null&&tile.gameObjects!=null)for(GameObject obj:tile.gameObjects)if(obj.id>=0)result.add(obj);}return result;}
        public void replace(GameObject old,int source){old.setId(assets.object(source));}
        public void item(int source,int amount,int x,int y){GroundItem ground=new GroundItem(assets.item(source),amount).position(tile(x,y)).spawn(0);groundItems.add(ground);}
        void installDoors(){
            int theme=theme();for(int next:node.neighbours){Edge edge=edges.get(edgeKey(node.id,next));int source=edge.key>=0?KEY_DOORS[theme]+edge.key:edge.skill>=0?SKILL_DOORS[edge.skill][theme]:edge.guardian?GUARDIAN_DOORS[theme]:DOORS[theme];int direction=graph.direction(node.id,next);
                int x=direction==0?0:direction==2?15:7,y=direction==1?15:direction==3?0:7;
                Position pos=unrotatedTile(x,y);GameObject old=Tile.getObject(-1,pos.getX(),pos.getY(),0,10,-1);if(old!=null)old.remove();new GameObject(assets.object(source),pos,10,direction).spawn();
            }
        }
        void open(){
            if(opened||destroyed)return;opened=true;
            if(node.id==graph.startId){spawn(11226,8,8);DungeoneeringSkills.startTable(this);}
            else if(node.boss){for(DungeoneeringAssets.Spawn spawn:template.npcSpawns){NPC npc=spawn(spawn.id,spawn.x,spawn.y);bosses.add(npc);}}
            else{
                if("PuzzleRoom".equals(template.kind)){puzzle=DungeoneeringPuzzles.create(this);puzzle.open();}
                DungeoneeringSkills.populate(this);
            }
            if(node.key>=0){int[] spot=template.keySpot==null?new int[]{7,1}:template.keySpot;item(18202+node.key*2,1,spot[0],spot[1]);}
        }
        public int aliveGuardians(){int count=0;for(NPC npc:guardians)if(!npc.isRemoved()&&!dead.contains(npc)&&npc.getCombat()!=null&&!npc.getCombat().isDead())count++;return count;}
        public boolean requirement(Player p,StatType skill){int maximum=1;for(Player member:party.members)maximum=Math.max(maximum,member.getStats().get(skill).fixedLevel);int level=Math.max(1,maximum-10);if(p.getStats().get(skill).currentLevel<level){p.sendMessage("You need "+skill.name()+" level "+level+" for this puzzle.");return false;}return true;}
        public void say(String text){for(Player p:players())p.sendMessage(text);}
        public void damage(Player p,double percent){if(contains(p))p.hit(new Hit().fixedDamage(Math.max(1,(int)(p.getMaxHp()*percent))));}
    }
}
