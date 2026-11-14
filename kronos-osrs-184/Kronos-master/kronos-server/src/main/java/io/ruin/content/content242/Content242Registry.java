package io.ruin.content.content242;

import com.google.gson.*;
import io.ruin.Server;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Immutable startup bindings. Source IDs never double as unverified target IDs. */
public final class Content242Registry {
    private static final long MAX_BYTES=32L*1024*1024;
    private static final Content242Registry DISABLED=new Content242Registry(false,Collections.emptyMap(),Collections.emptySet());
    private static volatile Content242Registry loaded;
    private final boolean enabled;
    private final Map<String,Map<Integer,Integer>> mappings;
    private final Set<String> features;

    private Content242Registry(boolean enabled,Map<String,Map<Integer,Integer>> mappings,Set<String> features){
        this.enabled=enabled;this.mappings=mappings;this.features=features;
    }
    public static Content242Registry current(){
        Content242Registry value=loaded;if(value!=null)return value;
        if(Server.dataFolder==null)return DISABLED;
        synchronized(Content242Registry.class){
            if(loaded==null){
                try{
                    File file=new File(Server.dataFolder,"content242.json");
                    loaded=!file.isFile()||file.length()==0||file.length()>MAX_BYTES?DISABLED:
                        parse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                }catch(Exception malformed){loaded=DISABLED;System.err.println("Content242 mechanics disabled: invalid or unreadable manifest.");}
            }
            return loaded;
        }
    }
    public boolean feature(String name){return enabled&&features.contains(name);}
    public int resolve(String kind,int source){Map<Integer,Integer> map=mappings.get(kind);Integer target=map==null?null:map.get(source);return enabled&&target!=null?target:-1;}
    public boolean matches(String kind,int source,int target){return target>=0&&resolve(kind,source)==target;}

    /** Pure parser also used by detached regression checks. Invalid schemas fail closed. */
    public static Content242Registry parse(String json){
        try{
            JsonObject root=new JsonParser().parse(json).getAsJsonObject();
            if(!bool(root,"enabled")||integer(root.get("schemaVersion"))!=2||integer(root.get("sourceRevision"))!=242
                    ||integer(root.get("clientProtocol"))!=184)return DISABLED;
            JsonObject mechanics=root.getAsJsonObject("mechanics");if(mechanics==null||!bool(mechanics,"enabled"))return DISABLED;
            Set<String> features=new HashSet<>();JsonObject flags=mechanics.getAsJsonObject("features");
            if(flags==null)return DISABLED;
            for(Map.Entry<String,JsonElement> flag:flags.entrySet()){
                if(!flag.getValue().isJsonPrimitive()||!flag.getValue().getAsJsonPrimitive().isBoolean())return DISABLED;
                if(flag.getValue().getAsBoolean())features.add(flag.getKey());
            }
            JsonObject source=root.getAsJsonObject("mappings");if(source==null)return DISABLED;
            Map<String,Map<Integer,Integer>> mappings=new HashMap<>();
            for(String kind:Arrays.asList("items","npcs","objects","animations","graphics")){
                JsonObject input=source.getAsJsonObject(kind);Map<Integer,Integer> output=new HashMap<>();Set<Integer> destinations=new HashSet<>();
                if(input!=null)for(Map.Entry<String,JsonElement> entry:input.entrySet()){
                    int from=Integer.parseInt(entry.getKey()),to=integer(entry.getValue());
                    int limit=kind.equals("npcs")?16383:65534;
                    if(from<0||to<0||to>limit||output.put(from,to)!=null)return DISABLED;
                    // Equal animation/graphic payloads may intentionally share a target.
                    if(kind.equals("items")&&!destinations.add(to))return DISABLED;
                }
                mappings.put(kind,Collections.unmodifiableMap(output));
            }
            return new Content242Registry(true,Collections.unmodifiableMap(mappings),Collections.unmodifiableSet(features));
        }catch(RuntimeException malformed){return DISABLED;}
    }
    private static boolean bool(JsonObject object,String key){JsonElement value=object.get(key);return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()&&value.getAsBoolean();}
    private static int integer(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Integer required");return value.getAsBigDecimal().intValueExact();}
}
