package io.ruin.model.activities.raids;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.filestore.IndexFile;
import io.ruin.cache.*;
import io.ruin.content.content242.Content242Registry;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Verified cache bindings and detached donor stats; never edits shared definitions. */
public final class NearRealityRaidAssets {
    private static final Gson GSON=new Gson();
    private static volatile JsonObject data;
    private static volatile String dataError;
    private static volatile Map<Integer,JsonObject> regionBindings;
    private static volatile Map.Entry<FileStore,Set<Long>> verifiedRecords;
    private static final Set<Integer> validNativeRegions=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<Integer> ORIGINAL_TOB_REGIONS=new HashSet<>(Arrays.asList(
        12613,12869,13125,13122,13123,13379,12612,12611,12867));
    private static final Set<Integer> ORIGINAL_TOB_OBJECTS=new HashSet<>(Arrays.asList(33033,33034,33035,33036,32734,32687,32743,32744));
    private static JsonObject data() {
        JsonObject value=data;
        if(value!=null)return value;
        if(Server.dataFolder==null)return new JsonObject();
        synchronized(NearRealityRaidAssets.class) {
            if(data==null) {
                try {
                    Path file=Server.dataFolder.toPath().resolve("raids/near-reality-raid-assets.json");
                    if(Files.size(file)>8L*1024*1024)throw new IllegalArgumentException("Raid manifest too large");
                    JsonObject parsed=new JsonParser().parse(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject();
                    if(parsed.get("schemaVersion").getAsInt()!=1||parsed.get("sourceRevision").getAsInt()!=242)
                        throw new IllegalArgumentException("Raid asset schema mismatch");
                    data=parsed;
                } catch(Exception unavailable) {
                    dataError="Raid asset data unavailable: "+unavailable.getClass().getSimpleName()+": "+unavailable.getMessage();
                    System.err.println(dataError);data=new JsonObject();
                }
            }
            return data;
        }
    }
    private static int mapped(String kind,int source) {
        try {
            JsonObject maps=data().getAsJsonObject("mappings");
            if(maps!=null&&maps.has(kind)&&maps.getAsJsonObject(kind).has(Integer.toString(source)))
                return maps.getAsJsonObject(kind).get(Integer.toString(source)).getAsInt();
        } catch(RuntimeException invalid) { return -1; }
        return Content242Registry.current().resolve(kind,source);
    }
    private static boolean record(int group,int id) {
        if(id<0||id>=65535)return false;
        FileStore store=Server.fileStore;if(store==null)return false;
        try {
            IndexFile index=store.get(2);if(index==null)return false;
            Map.Entry<FileStore,Set<Long>> known=verifiedRecords;
            if(known==null||known.getKey()!=store)synchronized(NearRealityRaidAssets.class){
                known=verifiedRecords;
                if(known==null||known.getKey()!=store){
                    known=new AbstractMap.SimpleImmutableEntry<>(store,java.util.concurrent.ConcurrentHashMap.<Long>newKeySet());
                    verifiedRecords=known;
                }
            }
            long key=((long)group<<32)|(id&0xffffffffL);
            if(known.getValue().contains(key))return true;
            // Native getFile consumes its cached entry. Re-reading an already
            // verified animation can therefore inflate the whole archive again.
            byte[] bytes=index.getFile(group,id);
            if(bytes==null||bytes.length<=1)return false; // misses remain retryable
            known.getValue().add(key);return true;
        }
        catch(Exception invalid){return false;}
    }
    private static String normal(String value){return value==null?"":value.replaceAll("<[^>]*>","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private static boolean named(String actual,String expected){String a=normal(actual),e=normal(expected);return !a.isEmpty()&&!a.equals("null")&&!e.isEmpty()&&(a.equals(e)||a.contains(e));}
    public static int npc(int source,String expectedName) {
        int id=mapped("npcs",source);if(id<0)id=source;
        if(id<0||id>16383||!record(9,id))return -1;
        NPCDef def=NPCDef.get(id);return def!=null&&named(def.name,expectedName)?id:-1;
    }
    public static int npc(int source) {
        try {
            String expected=data().getAsJsonObject("npcNames").get(Integer.toString(source)).getAsString();
            if(!normal(expected).isEmpty()&&!normal(expected).equals("null"))return npc(source,expected);
            // Invisible energy/orb actors have no display name. Only a scoped,
            // explicitly imported mapping can authorize such an unnamed actor.
            JsonObject bindings=data().getAsJsonObject("mappings").getAsJsonObject("npcs");
            if(!bindings.has(Integer.toString(source)))return -1;
            int id=bindings.get(Integer.toString(source)).getAsInt();
            return id>=0&&id<=16383&&record(9,id)&&NPCDef.get(id)!=null?id:-1;
        }
        catch(RuntimeException absent){return -1;}
    }
    public static int object(int source,String expectedName) {
        if((expectedName==null||expectedName.isEmpty())&&ORIGINAL_TOB_OBJECTS.contains(source))return object(source);
        int id=mapped("objects",source);if(id<0)id=source;
        if(!record(6,id))return -1;
        ObjectDef def=ObjectDef.get(id);return def!=null&&named(def.name,expectedName)?id:-1;
    }
    /** Unnamed scenery is accepted only through an explicit 242 mapping. */
    public static int object(int source){int id=mapped("objects",source);if(id<0&&ORIGINAL_TOB_OBJECTS.contains(source))id=source;return record(6,id)&&ObjectDef.get(id)!=null?id:-1;}
    public static int item(int source,String expectedName) {
        int id=mapped("items",source);if(id<0)id=source;
        if(!record(10,id))return -1;
        ItemDef def=ItemDef.get(id);return def!=null&&named(def.name,expectedName)?id:-1;
    }
    public static int animation(int source) {if(source==-1||source==65535)return -1;int id=mapped("animations",source);return record(12,id)?id:-1;}
    public static int graphic(int source) {if(source==-1||source==65535)return -1;int id=mapped("graphics",source);return record(13,id)?id:-1;}
    private static JsonObject regionBinding(int source) {
        Map<Integer,JsonObject> bindings=regionBindings;
        if(bindings==null) {
            if(Server.dataFolder==null)return null;
            synchronized(NearRealityRaidAssets.class) {
                if(regionBindings==null) {
                    Map<Integer,JsonObject> loaded=new HashMap<>();
                    try {
                        JsonObject root=new JsonParser().parse(new String(Files.readAllBytes(Server.dataFolder.toPath().resolve("content242.json")),StandardCharsets.UTF_8)).getAsJsonObject();
                        if(root.get("schemaVersion").getAsInt()!=2||root.get("sourceRevision").getAsInt()!=242||root.get("clientProtocol").getAsInt()!=184)
                            throw new IllegalArgumentException("Region binding schema mismatch");
                        for(Map.Entry<String,JsonElement> row:root.getAsJsonObject("mappings").getAsJsonObject("regions").entrySet()) {
                            int from=Integer.parseInt(row.getKey());JsonObject value=row.getValue().getAsJsonObject();
                            int to=value.get("destination").getAsInt();
                            if(from<0||from>65535||to<0||to>65535||value.get("dx").getAsInt()!=((to>>8)-(from>>8))*64
                                    ||value.get("dy").getAsInt()!=((to&255)-(from&255))*64)throw new IllegalArgumentException("Region displacement mismatch");
                            loaded.put(from,value);
                        }
                        for(int original:ORIGINAL_TOB_REGIONS)if(!loaded.containsKey(original)) {
                            JsonObject value=new JsonObject();value.addProperty("destination",original);value.addProperty("dx",0);value.addProperty("dy",0);loaded.put(original,value);
                        }
                    } catch(Exception invalid) {loaded.clear();System.err.println("Raid region bindings unavailable: "+invalid.getMessage());}
                    regionBindings=Collections.unmodifiableMap(loaded);
                }
                bindings=regionBindings;
            }
        }
        return bindings.get(source);
    }
    public static int region(int source) {
        JsonObject binding=regionBinding(source);if(binding==null||Server.fileStore==null)return -1;
        try {
            int target=binding.get("destination").getAsInt();
            if(target<0||target>=Region.LOADED.length)return -1;
            if(validNativeRegions.contains(target))return target;
            Region region=Region.LOADED[target];
            if(region==null||region.empty||region.dynamicIndex!=-1)return -1;
            int archive=Server.fileStore.get(5).getArchiveId("m"+(target>>8)+"_"+(target&255));
            byte[] terrain=archive<0?null:Server.fileStore.get(5).getFile(archive,0),landscape=region.getLandscapeData();
            if(terrain!=null&&terrain.length>0&&landscape!=null&&landscape.length>0){validNativeRegions.add(target);return target;}
            return -1;
        } catch(Exception invalid){return -1;}
    }
    public static Position position(int sourceX,int sourceY,int plane) {
        if(sourceX<0||sourceY<0||plane<0||plane>3)return null;
        int source=((sourceX>>6)<<8)|(sourceY>>6);JsonObject binding=regionBinding(source);
        if(binding==null||region(source)<0)return null;
        try{return new Position(sourceX+binding.get("dx").getAsInt(),sourceY+binding.get("dy").getAsInt(),plane);}
        catch(RuntimeException invalid){return null;}
    }
    /** Every invocation returns an independent Info object for NPC.installCombat. */
    public static npc_combat.Info combatInfo(int source) {
        try {
            JsonObject stats=data().getAsJsonObject("combat").getAsJsonObject(Integer.toString(source));
            if(stats==null)return null;
            npc_combat.Info result=GSON.fromJson(stats,npc_combat.Info.class);
            if(result.hitpoints<=0||result.attack_ticks<0)return null;
            result.ids=new int[]{npc(source)};
            result.spawn_animation=animation(result.spawn_animation);
            result.attack_animation=animation(result.attack_animation);
            result.defend_animation=animation(result.defend_animation);
            result.death_animation=animation(result.death_animation);
            result.handler=null;result.respawn_ticks=-1;
            return result;
        } catch(RuntimeException unavailable){return null;}
    }
    public static String diagnostics() {
        JsonObject current=data();
        if(dataError!=null)return dataError;
        return current.has("combat")?"Pinned Near Reality raid asset data loaded; absent NPC/model/map bindings remain gated":"Raid asset data is not initialized";
    }
    private NearRealityRaidAssets(){}
}
