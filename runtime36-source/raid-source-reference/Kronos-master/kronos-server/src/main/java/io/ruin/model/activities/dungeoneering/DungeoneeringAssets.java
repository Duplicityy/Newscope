package io.ruin.model.activities.dungeoneering;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.ItemDef;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Native IDs only come from the audited cache importer, never collide with the old content. */
public final class DungeoneeringAssets {
    public int schemaVersion;
    public String requiredCapability;
    public Room[] rooms;
    public Map<String,Integer> objectMappings, npcMappings, itemMappings, animationMappings, spotanimMappings;
    public Map<String,JsonObject> npcDefinitions, itemDefinitions;
    public List<String> limitations;
    private final Map<Integer,Integer> sourceObjects=new HashMap<>(), sourceNpcs=new HashMap<>(),sourceItems=new HashMap<>();
    public static final class Spawn { public int id,x,y; }
    public static final class Room {
        public String id,kind,theme,puzzle,handler;
        public int[] chunk,sourceChunk,doors,keySpot,complexity;
        public int minFloor,music;
        public Spawn[] npcSpawns;
        public int[] dynamicObjects;
        public boolean supports(int floor,int complexity) {
            if(!theme.equals(theme(floor)))return false;
            if(this.complexity!=null && Arrays.stream(this.complexity).noneMatch(c->c==complexity))return false;
            return !"BossRoom".equals(kind) || minFloor<=floor;
        }
    }
    public static DungeoneeringAssets load(Path path) throws Exception {
        if(!Files.isRegularFile(path) || Files.size(path)>32*1024*1024L)throw new IllegalStateException("Dungeoneering asset manifest is unavailable or oversized");
        DungeoneeringAssets data;
        try(java.io.Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            data=new Gson().fromJson(reader,DungeoneeringAssets.class);
        }
        if(data==null || data.schemaVersion!=1 || data.rooms==null || data.rooms.length<1000)throw new IllegalStateException("The complete donor room manifest is required");
        data.validate();return data;
    }
    private void validate() {
        validateMap(objectMappings,sourceObjects,"objects");validateMap(npcMappings,sourceNpcs,"NPCs");validateMap(itemMappings,sourceItems,"items");
        Set<String> ids=new HashSet<>();
        for(Room r:rooms) {
            if(r.id==null||!ids.add(r.id)||r.chunk==null||r.chunk.length!=2||r.doors==null||r.theme==null)throw new IllegalStateException("Invalid Dungeoneering room manifest");
            if(r.chunk[0]<0||r.chunk[1]<0||r.chunk[0]>=800||r.chunk[1]>=2048)throw new IllegalStateException("Dungeoneering source chunks must be outside the dynamic pool");
            for(int d:r.doors)if(d<0||d>3)throw new IllegalStateException("Invalid room direction");
            if(r.npcSpawns!=null)for(Spawn s:r.npcSpawns)npc(s.id);
        }
        for(int target:sourceNpcs.keySet())if(target>=16384)throw new IllegalStateException("NPC exceeds the revision184 14-bit spawn field");
    }
    private static void validateMap(Map<String,Integer> map,Map<Integer,Integer> reverse,String kind) {
        if(map==null||map.isEmpty())throw new IllegalStateException("Missing Dungeoneering "+kind);
        for(Map.Entry<String,Integer> entry:map.entrySet()) {
            int source=Integer.parseInt(entry.getKey()),target=entry.getValue();
            if(source<0||target<0||target>=65535||reverse.put(target,source)!=null)throw new IllegalStateException("Invalid or duplicate native "+kind+" mapping");
        }
    }
    private int resolve(Map<String,Integer> map,int source,String kind) {
        Integer target=map==null?null:map.get(Integer.toString(source));
        if(target==null)throw new IllegalStateException("Missing Dungeoneering "+kind+" asset "+source);
        return target;
    }
    public int object(int source) { return resolve(objectMappings,source,"object"); }
    public int npc(int source) { return resolve(npcMappings,source,"NPC"); }
    public int item(int source) { return resolve(itemMappings,source,"item"); }
    public int animation(int source) { return source<0?-1:resolve(animationMappings,source,"animation"); }
    public int spotanim(int source) { return source<0?-1:resolve(spotanimMappings,source,"spot animation"); }
    public int sourceObject(int target){return sourceObjects.getOrDefault(target,-1);}
    public int sourceNpc(int target){return sourceNpcs.getOrDefault(target,-1);}
    public int sourceItem(int target){return sourceItems.getOrDefault(target,-1);}
    public static String theme(int floor){return floor<=11?"Frozen":floor<=17?"Abandoned":floor<=29?"Furnished":floor<=35?"Abandoned":floor<=47?"Occult":"Warped";}
    public static int themeIndex(int floor){return Arrays.asList("Frozen","Abandoned","Furnished","Occult","Warped").indexOf(theme(floor));}
    public List<Room> select(String kind,int floor,int complexity){List<Room> found=new ArrayList<>();for(Room r:rooms)if(kind.equals(r.kind)&&r.supports(floor,complexity))found.add(r);return found;}
    public JsonObject npcInfo(int source){return npcDefinitions==null?null:npcDefinitions.get(Integer.toString(source));}
}
