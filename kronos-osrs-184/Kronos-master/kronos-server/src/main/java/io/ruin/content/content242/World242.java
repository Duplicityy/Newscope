package io.ruin.content.content242;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.item.loot.LootItem;
import io.ruin.model.item.loot.LootTable;
import io.ruin.model.shop.ShopManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;

/** Additive world bindings generated from verified242 NPC/item mappings. */
public final class World242 {
    private static final int MAX_FILE_BYTES=32*1024*1024;
    private World242(){}

    public static void initialize(){
        if(!Content242Registry.current().feature("world"))return;
        File file=new File(Server.dataFolder,"content242-world.json");
        if(!file.isFile()||file.length()<=0||file.length()>MAX_FILE_BYTES)
            throw new IllegalStateException("Enabled242 world data is missing or oversized");
        try{
            JsonObject root=new JsonParser().parse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)).getAsJsonObject();
            if(integer(root.get("schemaVersion"),1,1)!=1||!root.get("enabled").getAsBoolean())return;
            Map<Integer,ModernLoot> pending=new LinkedHashMap<>();
            JsonObject loot=root.getAsJsonObject("loot");
            if(loot==null||loot.size()>16384)throw new IllegalArgumentException("Invalid242 loot catalogue");
            for(Map.Entry<String,JsonElement> entry:loot.entrySet()){
                int npc=Integer.parseInt(entry.getKey());
                if(npc<0||npc>16383||!NPCDef.cached.containsKey(npc))throw new IllegalArgumentException("Unknown242 NPC");
                pending.put(npc,ModernLoot.parse(entry.getValue().getAsJsonArray()));
            }
            List<ShopBinding> bindings=new ArrayList<>();
            for(JsonElement element:array(root,"shopBindings",20000)){
                JsonObject row=element.getAsJsonObject();
                int npc=integer(row.get("npc"),0,16383),option=integer(row.get("option"),1,5);
                if(!NPCDef.cached.containsKey(npc))throw new IllegalArgumentException("Unknown242 shop NPC");
                List<String> pages=new ArrayList<>();
                for(JsonElement page:array(row,"shops",100)){
                    String key=page.getAsString();
                    if(!key.matches("content242-shop-[0-9]+-[0-9]+")||ShopManager.getByUUID(key)==null)
                        throw new IllegalArgumentException("Unregistered242 shop page");
                    pages.add(key);
                }
                if(!pages.isEmpty())bindings.add(new ShopBinding(npc,option,pages));
            }
            // Complete validation before changing any definition's handlers.
            pending.forEach((id,table)->NPCDef.get(id).lootTable=table);
            for(ShopBinding binding:bindings)
                NPCAction.register(binding.npc,binding.option,(player,npc)->openShop(player,binding.pages,0));
            WorldTravel242.initialize(root);
            Server.println("Content242 world: "+pending.size()+" loot tables, "+bindings.size()+" shop bindings enabled.");
        }catch(RuntimeException malformed){throw malformed;}
        catch(Exception failure){throw new IllegalStateException("Cannot load242 world data",failure);}
    }

    private static final class ShopBinding{
        final int npc,option; final List<String> pages;
        ShopBinding(int npc,int option,List<String> pages){this.npc=npc;this.option=option;this.pages=Collections.unmodifiableList(pages);}
    }
    private static void openShop(Player player,List<String> pages,int offset){
        if(pages.size()==1){ShopManager.openIfExists(player,pages.get(0));return;}
        List<Option> options=new ArrayList<>();
        for(int i=offset;i<Math.min(offset+3,pages.size());i++){
            final String key=pages.get(i);
            options.add(new Option(ShopManager.getByUUID(key).title,p->ShopManager.openIfExists(p,key)));
        }
        if(offset+3<pages.size())options.add(new Option("Next pages",p->openShop(p,pages,offset+3)));
        if(offset>0)options.add(new Option("Previous pages",p->openShop(p,pages,Math.max(0,offset-3))));
        player.dialogue(new OptionsDialogue("Shop stock",options));
    }

    static int integer(JsonElement value,int min,int max){
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Integer required");
        int result=value.getAsBigDecimal().intValueExact();
        if(result<min||result>max)throw new IllegalArgumentException("Integer out of bounds");
        return result;
    }
    static JsonArray array(JsonObject object,String key,int max){
        JsonArray array=object.getAsJsonArray(key);
        if(array==null||array.size()>max)throw new IllegalArgumentException("Invalid bounded array "+key);
        return array;
    }

    /** Main rolls preserve empty probability; tertiary rolls are independent. */
    public static final class ModernLoot extends LootTable{
        private final List<Group> groups;
        private ModernLoot(List<Group> groups){this.groups=Collections.unmodifiableList(groups);}
        public static ModernLoot parse(JsonArray source){
            return parse(source,id->ItemDef.get(id)!=null);
        }
        static ModernLoot parse(JsonArray source,IntPredicate knownItem){
            if(source.size()>64)throw new IllegalArgumentException("Too many drop groups");
            List<Group> groups=new ArrayList<>();
            int budget=0;
            for(JsonElement element:source){Group group=group(element.getAsJsonObject(),knownItem,0);groups.add(group);budget+=group.budget;}
            if(budget>256)throw new IllegalArgumentException("Too many possible rewards per kill");
            return new ModernLoot(groups);
        }
        private static Group group(JsonObject row,IntPredicate knownItem,int depth){
            if(depth>6)throw new IllegalArgumentException("Shared table nesting too deep");
            Group group=new Group(integer(row.get("denominator"),0,Integer.MAX_VALUE),
                entries(array(row,"guaranteed",512),0,knownItem,depth),entries(array(row,"main",4096),1,knownItem,depth),
                entries(array(row,"independent",512),2,knownItem,depth));
            long weight=0;for(Entry entry:group.main)weight+=entry.weight;
            if(weight>group.denominator)throw new IllegalArgumentException("Main drop weights exceed denominator");
            return group;
        }
        private static List<Entry> entries(JsonArray rows,int mode,IntPredicate knownItem,int depth){
            List<Entry> result=new ArrayList<>();
            for(JsonElement value:rows){
                JsonObject row=value.getAsJsonObject();
                int weight=mode==0?1:integer(row.get("weight"),0,Integer.MAX_VALUE);
                int denominator=mode==2?integer(row.get("denominator"),1,Integer.MAX_VALUE):1;
                if(mode==2&&weight>denominator)throw new IllegalArgumentException("Reward probability exceeds one");
                if(row.has("table")){
                    Group nested=group(row.getAsJsonObject("table"),knownItem,depth+1);
                    List<int[]> counts=new ArrayList<>();long total=0;
                    if(row.has("rollCounts"))for(JsonElement count:array(row,"rollCounts",8)){
                        JsonObject choice=count.getAsJsonObject();
                        int rolls=integer(choice.get("rolls"),1,8),w=integer(choice.get("weight"),1,Integer.MAX_VALUE);
                        total+=w;counts.add(new int[]{rolls,w});
                    }
                    if(row.has("rollCounts")&&(counts.isEmpty()||total>Integer.MAX_VALUE))throw new IllegalArgumentException("Invalid shared roll counts");
                    result.add(new Entry(weight,denominator,nested,counts,(int)total));
                }else{
                    int id=integer(row.get("id"),0,65534),min=integer(row.get("min"),1,Integer.MAX_VALUE),max=integer(row.get("max"),min,Integer.MAX_VALUE);
                    if(!knownItem.test(id))throw new IllegalArgumentException("Unknown reward item");
                    result.add(new Entry(id,min,max,weight,denominator));
                }
            }
            return Collections.unmodifiableList(result);
        }
        @Override public List<Item> rollItems(boolean allowGuaranteed){return roll(allowGuaranteed,()->ThreadLocalRandom.current().nextDouble());}
        public List<Item> roll(boolean allowGuaranteed,DoubleSupplier random){
            List<Item> result=new ArrayList<>();
            for(Group group:groups)group.roll(result,allowGuaranteed,random);
            return result;
        }
        @Override public Item rollItem(){List<Item> items=rollItems(false);return items.isEmpty()?null:items.get(0);}
        @Override public List<LootItem> getLootItems(){
            List<LootItem> result=new ArrayList<>();
            for(Group group:groups)group.catalogue(result);
            return result;
        }
        private static double draw(DoubleSupplier source){double value=source.getAsDouble();if(!Double.isFinite(value)||value<0||value>=1)throw new IllegalArgumentException("Random draw outside [0,1)");return value;}
    }
    private static final class Group{
        final int denominator,budget;final List<Entry> guaranteed,main,independent;
        Group(int denominator,List<Entry> guaranteed,List<Entry> main,List<Entry> independent){
            this.denominator=denominator;this.guaranteed=guaranteed;this.main=main;this.independent=independent;
            int maximum=0,total=0;
            for(Entry e:main)maximum=Math.max(maximum,e.budget);
            for(Entry e:guaranteed)total+=e.budget;
            for(Entry e:independent)total+=e.budget;
            budget=maximum+total;
            if(budget>256)throw new IllegalArgumentException("Shared rewards exceed bounded kill budget");
        }
        void roll(List<Item> result,boolean allowGuaranteed,DoubleSupplier random){
            if(allowGuaranteed)for(Entry entry:guaranteed)entry.add(result,random);
            if(denominator>0){
                double roll=ModernLoot.draw(random)*denominator;
                for(Entry entry:main){if(roll<entry.weight){entry.add(result,random);break;}roll-=entry.weight;}
            }
            for(Entry entry:independent)if(ModernLoot.draw(random)*entry.denominator<entry.weight)entry.add(result,random);
        }
        void catalogue(List<LootItem> result){
            for(List<Entry> entries:Arrays.asList(guaranteed,main,independent))for(Entry entry:entries){
                if(entry.table!=null)entry.table.catalogue(result);
                else result.add(new LootItem(entry.id,entry.min,entry.max,entry.weight));
            }
        }
    }
    private static final class Entry{
        final int id,min,max,weight,denominator,budget,countWeight;
        final Group table;final List<int[]> counts;
        Entry(int id,int min,int max,int weight,int denominator){
            this.id=id;this.min=min;this.max=max;this.weight=weight;this.denominator=denominator;
            this.table=null;this.counts=Collections.emptyList();this.countWeight=0;this.budget=1;
        }
        Entry(int weight,int denominator,Group table,List<int[]> counts,int countWeight){
            this.id=this.min=this.max=-1;this.weight=weight;this.denominator=denominator;this.table=table;
            this.counts=Collections.unmodifiableList(counts);this.countWeight=countWeight;
            int maxRolls=1;for(int[] count:counts)maxRolls=Math.max(maxRolls,count[0]);
            this.budget=maxRolls*table.budget;
            if(budget>256)throw new IllegalArgumentException("Shared roll count exceeds kill budget");
        }
        void add(List<Item> result,DoubleSupplier random){
            if(table==null){
                long width=(long)max-min+1;
                result.add(new Item(id,min==max?min:(int)(min+(long)(ModernLoot.draw(random)*width))));return;
            }
            int rolls=1;
            if(!counts.isEmpty()){
                double roll=ModernLoot.draw(random)*countWeight;
                for(int[] choice:counts){if(roll<choice[1]){rolls=choice[0];break;}roll-=choice[1];}
            }
            for(int i=0;i<rolls;i++)table.roll(result,true,random);
        }
    }
}
