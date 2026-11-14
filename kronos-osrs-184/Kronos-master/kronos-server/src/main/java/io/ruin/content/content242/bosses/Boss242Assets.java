package io.ruin.content.content242.bosses;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.cache.*;
import io.ruin.content.content242.*;
import io.ruin.model.map.Region;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Source regions are explicit too: an old184 region at the same source ID is not accepted implicitly. */
final class Boss242Assets {
    static int id(String kind,int source){return Content242Registry.current().resolve(kind,source);}
    static int region(int source){
        try{JsonObject root=new JsonParser().parse(new String(Files.readAllBytes(Server.dataFolder.toPath().resolve("content242.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject binding=root.getAsJsonObject("mappings").getAsJsonObject("regions").getAsJsonObject(Integer.toString(source));
            int destination=binding.get("destination").getAsInt();
            if(destination<0||destination>=Region.LOADED.length||Server.fileStore==null)return -1;
            Region region=Region.LOADED[destination];if(region==null||region.empty||region.dynamicIndex!=-1)return -1;
            int archive=Server.fileStore.get(5).getArchiveId("m"+(destination>>8)+"_"+(destination&255));
            byte[] terrain=archive<0?null:Server.fileStore.get(5).getFile(archive,0);
            byte[] landscape=region.getLandscapeData();
            return terrain!=null&&terrain.length>0&&landscape!=null&&landscape.length>0?destination:-1;
        }catch(Exception invalid){return -1;}
    }
    static boolean ready(String feature,int[] npcs,int[] animations,int[] graphics,int[] objects,int sourceRegion){
        if(!ModernGear.feature(feature)||region(sourceRegion)<0)return false;
        for(int source:npcs){int mapped=id("npcs",source);if(mapped<0)return false;NPCDef def=NPCDef.get(mapped);if(def==null||def.combatInfo==null)return false;}
        for(int source:animations)if(!ModernGear.specialAssets(feature,source))return false;
        for(int source:graphics)if(id("graphics",source)<0||Server.fileStore==null||Server.fileStore.get(2).getFile(13,id("graphics",source))==null)return false;
        for(int source:objects)if(id("objects",source)<0||ObjectDef.get(id("objects",source))==null)return false;
        return true;
    }
}
