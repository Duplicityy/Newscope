package io.ruin.model.activities.dungeoneering;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.data.impl.teleports;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.map.Direction;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Original Daemonheim courtyard and castle; visiting never allocates a dungeon. */
public final class DungeoneeringLobby {
    private static final int REWARDS_TRADER = 7599; // Mountain Guide with backpack.
    private static final int EXPEDITION_GUIDE = 3254; // Sigbert the Adventurer.
    private static final int CAMPFIRE = 26185;
    private static final Set<Integer> REGIONS=new HashSet<>();
    private static final Map<Integer,Integer> CONTROLS=new HashMap<>();
    private static int offsetX,offsetY;
    private static boolean ready;
    private DungeoneeringLobby(){}

    public static void install(){
        if(ready||!Dungeoneering.available())return;
        try{
            Path path=Server.dataFolder.toPath().resolve("dungeoneering/daemonheim-lobby.json");
            if(!Files.isRegularFile(path)||Files.size(path)>4*1024*1024)throw new IllegalStateException("Daemonheim lobby manifest unavailable");
            JsonObject data;try(java.io.Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){data=new JsonParser().parse(reader).getAsJsonObject();}
            if(data.get("schemaVersion").getAsInt()!=1||!data.get("complete").getAsBoolean()||!"k242-d727-lobby-v3".equals(data.get("requiredCapability").getAsString()))throw new IllegalStateException("Incompatible Daemonheim lobby manifest");
            JsonArray source=data.getAsJsonArray("sourceOrigin"),nativeOrigin=data.getAsJsonArray("nativeOrigin");
            offsetX=nativeOrigin.get(0).getAsInt()-source.get(0).getAsInt();offsetY=nativeOrigin.get(1).getAsInt()-source.get(1).getAsInt();
            for(JsonElement value:data.getAsJsonArray("regions")){
                int id=value.getAsJsonObject().get("id").getAsInt();Region region=Region.get(id);
                if(region==null||region.empty||region.dynamicIndex!=-1)throw new IllegalStateException("Missing static Daemonheim region "+id);
                REGIONS.add(id);
            }
            if(REGIONS.size()!=4)throw new IllegalStateException("Incomplete Daemonheim courtyard/castle footprint");
            JsonObject objects=data.getAsJsonObject("objectMappings");
            for(int id:new int[]{48496,48612,48611,50567,50568})CONTROLS.put(id,objects.get(Integer.toString(id)).getAsInt());
            Map<Integer,Integer> found=new HashMap<>();
            for(int id:REGIONS){Region region=Region.get(id);for(int z=0;z<4;z++)for(int x=region.baseX;x<region.baseX+64;x++)for(int y=region.baseY;y<region.baseY+64;y++){
                Tile tile=Tile.get(x,y,z,false);if(tile==null||tile.gameObjects==null)continue;
                for(GameObject object:tile.gameObjects)for(Map.Entry<Integer,Integer> control:CONTROLS.entrySet())if(object.id==control.getValue()){
                    final int sourceId=control.getKey();found.merge(sourceId,1,Integer::sum);
                    ObjectAction.register(object,1,(p,o)->{
                        if(!atLobby(p)||!usable(p))return;
                        if(sourceId==48496){openLedger(p);return;}
                        Position destination=sourceId==48612?map(3448,3738,1):sourceId==48611?map(3451,3738,1):sourceId==50567?map(3447,3732,0):map(3452,3732,0);
                        p.getMovement().startTeleport(0,event->{if(atLobby(p))p.getMovement().teleport(destination);});
                    });
                }
            }}
            for(int sourceId:CONTROLS.keySet())if(!found.containsKey(sourceId))throw new IllegalStateException("Missing Daemonheim entrance/staircase "+sourceId);
            Position arrival=destination();Tile tile=Tile.get(arrival,false);
            if(tile!=null&&!tile.isFloorFree())throw new IllegalStateException("Daemonheim arrival is obstructed");
            ready=true;Server.println("Daemonheim: original courtyard/castle lobby and native entrances enabled.");
        }catch(Exception failure){REGIONS.clear();CONTROLS.clear();ready=false;Server.logError("Daemonheim lobby unavailable; dungeon content retained",failure);}
        if (ready) {
            try {
                installServices();
            } catch (RuntimeException failure) {
                Server.logError("Daemonheim lobby services failed to load", failure);
            }
        }
    }

    private static void installServices() {
        Position trader = map(3452, 3718, 0);
        Position fire = map(3452, 3719, 0);
        Position guide = map(3451, 3724, 0);
        checkServiceTile(trader);
        checkServiceTile(fire);
        checkServiceTile(guide);
        NPCDef traderDef = NPCDef.get(REWARDS_TRADER);
        NPCDef guideDef = NPCDef.get(EXPEDITION_GUIDE);
        int traderTalk = talkOption(traderDef);
        int guideTalk = talkOption(guideDef);
        if (ObjectDef.get(CAMPFIRE) == null) throw new IllegalStateException("Missing Daemonheim campfire definition");

        // Light the existing campfire model on the verge without adding collision to the walkway.
        GameObject.spawnUnclipped(CAMPFIRE, fire.getX(), fire.getY(), fire.getZ(), 10, 0);
        NPC traderNpc = new NPC(REWARDS_TRADER).spawn(trader.getX(), trader.getY(), trader.getZ(), Direction.WEST, 0);
        NPCAction.register(traderNpc, traderTalk, (p, npc) -> {
            if (atLobby(p) && usable(p)) DungeoneeringRewards.open(p);
        });
        NPC guideNpc = new NPC(EXPEDITION_GUIDE).spawn(guide.getX(), guide.getY(), guide.getZ(), Direction.SOUTH, 0);
        NPCAction.register(guideNpc, guideTalk, (p, npc) -> openLedger(p));
        Server.println("Daemonheim: campfire rewards trader and expedition guide enabled.");
    }

    private static int talkOption(NPCDef def) {
        if (def == null || def.size != 1 || def.getOption("Talk-to") < 1)
            throw new IllegalStateException("Invalid Daemonheim lobby NPC definition");
        return def.getOption("Talk-to");
    }

    private static void checkServiceTile(Position position) {
        Tile tile = Tile.get(position, false);
        if (tile == null) return;
        if (!tile.isFloorFree()) throw new IllegalStateException("Obstructed Daemonheim service tile " + position);
        if (tile.gameObjects == null) return;
        for (GameObject object : tile.gameObjects) {
            if (object.type == 10 || object.type == 11)
                throw new IllegalStateException("Occupied Daemonheim service tile " + position + " (object " + object.id + ")");
        }
    }

    private static void openLedger(Player player) {
        if (atLobby(player) && usable(player)) Dungeoneering.open(player);
    }
    public static Position map(int sourceX,int sourceY,int z){return new Position(sourceX+offsetX,sourceY+offsetY,z);}
    public static Position destination(){return map(3450,3718,0);}
    public static boolean available(){return ready;}
    public static boolean atLobby(Player p){return ready&&p!=null&&p.getPosition().getRegion().dynamicIndex==-1&&REGIONS.contains(p.getPosition().getRegion().id);}
    private static boolean usable(Player p){return p.isOnline()&&!p.isLocked()&&p.getHp()>0&&!p.getCombat().isDead()&&!PlayerFile.isSaveQuarantined(p);}
    public static void teleport(Player p){
        if(Dungeoneering.inRun(p)){Dungeoneering.open(p);return;}
        if(!Dungeoneering.available()||!ready){p.sendMessage("Daemonheim's lobby is unavailable on this world.");return;}
        if(!usable(p)||p.getCombat().isDefending(10)||p.wildernessLevel>0||p.pvpAttackZone||p.content242BossReturn!=null||p.getDuel().stage!=0||p.joinedTournament||p.supplyChestRestricted
                ||io.ruin.model.activities.hollowdepths.HollowDepths.inRun(p)||io.ruin.model.activities.raids.tob.port.TheatreOfBloodRaid.isInRaid(p)||io.ruin.model.activities.raids.toa.port.TombsOfAmascutRaid.isInRaid(p)){
            p.sendMessage("Leave your current activity and combat before visiting Daemonheim.");return;
        }
        teleports.teleport(p,destination());p.sendMessage("Welcome to Daemonheim. Talk to Sigbert or use a dungeon entrance to set up your expedition.");
    }
}
