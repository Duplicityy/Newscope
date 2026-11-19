package io.ruin.model.inter.loot;

import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.model.World;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.item.Item;
import io.ruin.model.item.loot.LootTable;
import io.ruin.model.map.Position;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static io.ruin.model.inter.loot.LootCompendium.Group;
import static io.ruin.model.inter.loot.LootCompendium.Drop;
import static io.ruin.model.inter.loot.LootCompendium.Source;

/** Read-only providers over actual configured tables; never rolls/grants or changes gameplay. */
public final class LootCompendiumSources {
    private static final Set<String> REGISTERED=new HashSet<>();
    private static final Map<String,String> WARNINGS=new LinkedHashMap<>();
    private static final String TOA="io.ruin.model.activities.raids.toa.port.";
    private static final String TOB="io.ruin.model.activities.raids.tob.port.";
    private LootCompendiumSources(){}

    /** Called lazily after native activities have prepared their real registries. */
    public static void install(){
        attempt("boss-classification",LootCompendiumSources::bosses);
        attempt("toa",LootCompendiumSources::tombs);
        attempt("tob",LootCompendiumSources::theatre);
        attempt("cox",LootCompendiumSources::xeric);
        attempt("barrows",LootCompendiumSources::barrows);
        attempt("crystal",LootCompendiumSources::crystal);
        attempt("larran",()->table("larran","Larran's chest",Group.CHESTS,"Larran chest (object 34832)","Requires Larran's key; one configured chest roll.",-1,23490,
                (LootTable)type("io.ruin.model.activities.wilderness.LarranChestLoot").newInstance(),null));
        attempt("supply",()->table("supply","Wilderness supply chest",Group.CHESTS,"Active wilderness supply-chest event",
                "Requires the event chest to be unlocked; base selection per configured roll.",-1,995,
                (LootTable)field(type("io.ruin.model.activities.wilderness.SupplyChest"),"SUPPLY_CHEST_TABLE"),null));
        attempt("pest-control",LootCompendiumSources::pest);
        attempt("dungeoneering",LootCompendiumSources::dungeoneering);
        attempt("hollow-depths",LootCompendiumSources::hollowDepths);
    }
    public static Map<String,String> diagnostics(){return Collections.unmodifiableMap(new LinkedHashMap<>(WARNINGS));}
    private interface Task{void run()throws Exception;}
    private static void attempt(String key,Task task){try{task.run();WARNINGS.remove(key);}catch(Exception error){WARNINGS.put(key,error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()));}}
    private static void add(Source source){if(REGISTERED.add(source.key))LootCompendium.register(source);}

    private static void bosses(){
        NPCDef.forEach(def->{
            if(def==null||def.combatHandlerClass==null)return;
            String handler=def.combatHandlerClass.getName();
            // These are authored boss-encounter namespaces, not inferred combat levels.
            if(!handler.contains(".activities.bosses.")&&!handler.contains(".activities.wilderness.bosses.")&&!handler.contains(".activities.godwars."))return;
            List<Drop> drops=drops(def.lootTable,null);
            if(def.combatInfo!=null&&def.combatInfo.pet!=null&&def.combatInfo.pet.dropAverage>0)
                drops.add(new Drop(def.combatInfo.pet.itemId,1,1,1D/def.combatInfo.pet.dropAverage,"Separate base pet unlock roll"));
            if(!drops.isEmpty())add(new Source("npc:"+def.id,plain(def.name),Group.BOSSES,spawn(def.id),
                    "Authored boss-encounter NPC table. Base selection per roll; player bonuses and extra rolls can differ.",def.id,-1,drops,def.id));
        });
    }
    private static void tombs()throws Exception {
        Class<?> rewards=type(TOA+"NativeToaRewards");Object[] common=(Object[])field(rewards,"COMMON");List<Drop> normal=new ArrayList<>(),unique=new ArrayList<>(),extra=new ArrayList<>();
        for(Object pair:common){int id=toaItem(pair);int divisor=number(pair,"divisor");normal.add(new Drop(id,1,Math.max(1,64000/divisor),Double.NaN,
                "floor(points / "+divisor+") × raid-level quantity factor","Three eligible common picks if no unique; only entries with points >= "+divisor+" participate."));}
        for(String key:new String[]{"FANG","LIGHTBEARER","WARD","MASK","BODY","CHAPS","SHADOW"}){
            Object pair=field(rewards,key);String note=key.equals("FANG")||key.equals("LIGHTBEARER")?"Lower-level unique eligibility branch; raid level below50 adds an extra eligibility roll.":"Higher unique branch; raid level below150 adds an extra eligibility roll.";
            unique.add(new Drop(toaItem(pair),1,1,Double.NaN,note+" Overall chance depends on score, raid level and configured unique multiplier."));
        }
        for(String key:new String[]{"THREAD","SCARAB","SUN","CORRUPTOR","CLUE","DUNG"})extra.add(new Drop(toaItem(field(rewards,key)),1,1,Double.NaN,
                key.equals("DUNG")?"Fallback when score is below1500.":"Separate completion extra; eligibility/capacity checks and additional rolls apply."));
        int[] bosses=toaNpcs();int icon=bosses.length==0?-1:bosses[0];
        add(new Source("toa:common","Tombs of Amascut — common rewards",Group.MINIGAMES,"Completed Tombs raid; private burial-tomb reward room",
                "Completion rewards, not direct NPC drops. Current solo base route is level0; isolated boss tests award none.",icon,normal.get(0).itemId,normal,true,bosses));
        add(new Source("toa:unique","Tombs of Amascut — unique rewards",Group.BOSSES,"Completed Tombs raid; private burial-tomb reward room",
                "Conditional purple/reward selection from the actual NativeToaRewards registry; no flat per-kill rate is claimed.",icon,unique.get(0).itemId,unique,true,bosses));
        add(new Source("toa:extras","Tombs of Amascut — extra rewards",Group.MINIGAMES,"Completed Tombs raid; private burial-tomb reward room",
                "Actual completion extras/fallback registry. See each reward's conditions.",icon,extra.get(0).itemId,extra,true,bosses));
    }
    private static int toaItem(Object pair)throws Exception {return ((Number)call(pair,"id")).intValue();}
    private static int[] toaNpcs(){Set<Integer> ids=new LinkedHashSet<>();for(int source:new int[]{11719,11720,11721,11722,11730,11731,11746,11747,11748,11749,11753,11754,11755,11756,11757,11758,11761,11762,11763,11764,11778,11779,11780,11789,11790,11791,11792}){int id=NearRealityRaidAssets.npc(source);if(id>=0&&NPCDef.get(id)!=null)ids.add(id);}return integers(ids);}
    private static void theatre()throws Exception {
        Class<?> raid=type(TOB+"TheatreOfBloodRaid");List<int[]> award=arrays(type(TOB+"TheatreOfBloodRaid$Run"),"award");
        int[] ids=null,weights=null;
        for(int[] candidate:award){boolean items=candidate.length>0;for(int id:candidate)if(id<1000||ItemDef.get(id)==null)items=false;if(items){ids=candidate;break;}}
        if(ids==null)throw new IllegalStateException("Compiled Theatre award item array unavailable");
        for(int[] candidate:award)if(candidate.length==ids.length){boolean positive=true;for(int weight:candidate)if(weight<=0||weight>1000)positive=false;if(positive){weights=candidate;break;}}
        if(weights==null)throw new IllegalStateException("Compiled Theatre award weights unavailable");
        int total=Arrays.stream(weights).sum();List<Drop> uniques=new ArrayList<>();for(int i=0;i<ids.length;i++)uniques.add(new Drop(ids[i],1,1,(double)weights[i]/total,"Within the unique table AFTER this player receives the party's purple. Not a per-kill chance."));
        List<int[]> rows=arrays(raid,"commonReward");double factor=singleFraction(raid,"commonReward",1,5);List<Drop> common=new ArrayList<>();int sum=0;for(int[] row:rows)if(row.length==4&&ItemDef.get(row[0])!=null)sum+=row[3];
        if(sum<=0)throw new IllegalStateException("Compiled Theatre common table unavailable");
        for(int[] row:rows)if(row.length==4&&ItemDef.get(row[0])!=null){ItemDef item=ItemDef.get(row[0]);int nativeId=item.notedId>0?item.notedId:item.id;
            int min=Double.isFinite(factor)?(int)Math.ceil(row[1]*factor):row[1],max=Double.isFinite(factor)?(int)Math.ceil(row[2]*factor):row[2];
            common.add(new Drop(nativeId,min,max,(double)row[3]/sum,Double.isFinite(factor)?null:"Configured completion scaling",
                    "Per common-table pick; three picks for a contributing player who does not receive the unique."));}
        Set<Integer> linked=new LinkedHashSet<>();for(Object room:type(TOB+"TheatreOfBloodRaid$Room").getEnumConstants()){int id=number(room,"npc");if(id>=0)for(int alias:aliases(id))linked.add(alias);}
        int[] npcs=integers(linked);int icon=npcs.length==0?-1:npcs[0];
        add(new Source("tob:unique","Theatre of Blood — purple rewards",Group.BOSSES,"Completed Theatre raid; Verzik's vault",
                "Only full completion awards loot. Party contribution, MVP points and deaths decide purple chance/recipient; weights shown are conditional within the purple table.",icon,ids[0],uniques,true,npcs));
        add(new Source("tob:common","Theatre of Blood — common rewards",Group.MINIGAMES,"Completed Theatre raid; Verzik's vault",
                "Completion chest rewards, not direct room-boss drops. Tables are read from the actual compiled roller, without rolling it.",icon,common.get(0).itemId,common,true,npcs));
    }
    private static void xeric()throws Exception {
        Class<?> rewards=type("io.ruin.model.activities.raids.xeric.XericRewards");int[] olms=namedAliases("great olm");List<Drop> common=new ArrayList<>();
        for(LootCompendiumRates.Roll roll:LootCompendiumRates.read((LootTable)field(rewards,"regularTable")))common.add(new Drop(roll.item.id,1,Integer.MAX_VALUE,roll.probability,
                "ceil(max(131071, personal points) / "+roll.item.min+")","The native completion roller applies a 131071-point minimum before this divisor; two common rolls only when this player receives no unique."));
        add(new Source("cox:common","Chambers of Xeric — common rewards",Group.MINIGAMES,"Chambers completion reward chest",
                "Actual point-scaled completion rewards; points determine quantity and party unique selection can replace commons.",olms.length==0?-1:olms[0],995,common,true,olms));
        table("cox:unique","Chambers of Xeric — purple rewards",Group.BOSSES,"Chambers completion reward chest",
                "Weights shown only after a purple roll. Party/personal points affect chance/recipient; boss-only entry halves the whole purple outcome.",olms.length==0?-1:olms[0],-1,
                (LootTable)field(rewards,"uniqueTable"),"Conditional on receiving a purple; not a per-NPC kill rate.",true,olms);
    }
    private static void barrows()throws Exception {
        Class<?> rewards=type("io.ruin.model.activities.barrows.BarrowsRewards");List<Drop> pieces=new ArrayList<>();List<Integer> brothers=new ArrayList<>();
        for(Object brother:type("io.ruin.model.activities.barrows.BarrowsBrother").getEnumConstants()){
            brothers.add(number(brother,"npcId"));for(Integer id:(Integer[])field(brother,"pieces"))pieces.add(new Drop(id,1,1,Double.NaN,"Only pieces belonging to killed brothers enter the chest pool; brother count controls rolls and equipment chance."));}
        int[] npcs=brothers.stream().mapToInt(Integer::intValue).toArray();
        add(new Source("barrows:gear","Barrows chest — equipment",Group.CHESTS,"Barrows tunnel reward chest",
                "Completion chest, not direct brother drops. Killed brothers and shuffled equipment pool make the overall per-piece rate conditional.",npcs[0],pieces.get(0).itemId,pieces,true,npcs));
        List<Drop> misc=new ArrayList<>();
        for(LootCompendiumRates.Roll roll:LootCompendiumRates.read((LootTable)field(rewards,"miscTable")))misc.add(new Drop(roll.item.id,0,roll.item.max/6,roll.probability,
                "floor(floor(rolled x"+roll.item.min+"-"+roll.item.max+" / 6) × killed brothers / 6)",
                "Within the misc table only; the native roller first divides the rolled amount by six using integer division, then scales it by killed brothers. Equipment and misc-unique branches can replace this roll."));
        add(new Source("barrows:misc","Barrows chest — supplies",Group.CHESTS,"Barrows tunnel reward chest","Conditional common rolls; the displayed formula preserves the native integer quantity scaling.",npcs[0],995,misc,true,npcs));
        table("barrows:misc-unique","Barrows chest — other rares",Group.CHESTS,"Barrows tunnel reward chest","At most one misc-unique reward per chest; eligibility depends on previous rolls.",npcs[0],-1,(LootTable)field(rewards,"miscUniqueTable"),"Within the misc-unique attempt, including its configured empty branch.",true,npcs);
    }
    private static void crystal()throws Exception {
        Class<?> chest=type("io.ruin.model.map.object.actions.impl.edgeville.CrystalKeyChest");Item[][] bundles=(Item[][])field(chest,"LOOTS");Item[] rare=(Item[])field(chest,"RARE_LOOT");
        double gate=singleFraction(chest,null,0,1);Map<Integer,int[]> ranges=new LinkedHashMap<>();Map<Integer,Integer> occurrences=new HashMap<>();
        for(Item[] bundle:bundles){Set<Integer> seen=new HashSet<>();for(Item item:bundle){int[] range=ranges.computeIfAbsent(item.getId(),k->new int[]{item.getAmount(),item.getAmount()});range[0]=Math.min(range[0],item.getAmount());range[1]=Math.max(range[1],item.getAmount());if(seen.add(item.getId()))occurrences.merge(item.getId(),1,Integer::sum);}}
        List<Drop> drops=new ArrayList<>();for(Map.Entry<Integer,int[]> entry:ranges.entrySet())drops.add(new Drop(entry.getKey(),entry.getValue()[0],entry.getValue()[1],Double.isFinite(gate)?(1-gate)*occurrences.get(entry.getKey())/bundles.length:Double.NaN,"A complete common bundle is selected; items within a bundle arrive together."));
        for(Item item:rare)drops.add(new Drop(item.getId(),item.getAmount(),item.getAmount(),Double.isFinite(gate)?gate/rare.length:Double.NaN,"Rare branch, one uniformly selected rare reward."));
        add(new Source("crystal","Crystal key chest",Group.CHESTS,"Crystal chest (object 172)","Requires one crystal key. Rates and item bundles read from the actual native chest roller.",-1,989,drops));
    }
    private static void pest()throws Exception {
        List<Drop> drops=new ArrayList<>();for(Object reward:type("io.ruin.model.activities.pestcontrol.PestControlRewards").getEnumConstants())drops.add(new Drop(((Number)call(reward,"itemId")).intValue(),1,1,Double.NaN,"Purchase for "+call(reward,"cost")+" Pest Control commendations; not a random drop."));
        add(new Source("pest:shop","Pest Control reward shop",Group.MINIGAMES,"Pest Control reward interface","Deterministic commendation purchases from the actual reward enum.",-1,drops.get(0).itemId,drops));
    }
    private static void dungeoneering()throws Exception {
        Class<?> rewards=type("io.ruin.model.activities.dungeoneering.DungeoneeringRewards");Object assets=field(rewards,"assets"),registry=field(rewards,"rewards");if(assets==null||registry==null)throw new IllegalStateException("Dungeoneering rewards not prepared yet");List<Drop> drops=new ArrayList<>();
        for(Object reward:(Object[])registry){if(!(Boolean)field(reward,"enabled"))continue;int id=((Number)call(assets,"item",new Class[]{int.class},number(reward,"sourceItem"))).intValue();drops.add(new Drop(id,1,1,Double.NaN,"Purchase for "+number(reward,"cost")+" tokens; Dungeoneering level "+number(reward,"level")+" required. Additional item-specific requirements may apply."));}
        if(!drops.isEmpty())add(new Source("dungeoneering:shop","Dungeoneering token rewards",Group.MINIGAMES,"Dungeoneering lobby reward menu","Only enabled entries from the frozen native reward registry. Token purchase, not a boss drop.",-1,drops.get(0).itemId,drops));
    }
    private static void hollowDepths()throws Exception {
        Map<?,?> registry=(Map<?,?>)field(type("io.ruin.model.activities.hollowdepths.HollowDepthsRewards"),"MAPPINGS");List<Drop> drops=new ArrayList<>();
        for(Object mapping:registry.values()){int id=((Number)call(mapping,"deliveryId")).intValue();drops.add(new Drop(id,1,Integer.MAX_VALUE,Double.NaN,"Earned resource-credit redemption","Amount depends on credits actually earned in a completed run; account-bound unmapped credits are not fabricated items."));}
        if(!drops.isEmpty())add(new Source("hollow-depths:mailbox","Hollow Depths resource mailbox",Group.MINIGAMES,"Hollow Depths lobby reward mailbox","Actual validated native resource-delivery mappings; no fixed boss/kill rate is claimed.",-1,drops.get(0).itemId,drops));
    }
    private static List<Drop> drops(LootTable table,String note){List<Drop> out=new ArrayList<>();for(LootCompendiumRates.Roll roll:LootCompendiumRates.read(table))out.add(new Drop(roll.item.id,roll.item.min,roll.item.max,roll.probability,note==null?roll.note:note+" "+roll.note));return out;}
    private static void table(String key,String title,Group group,String location,String conditions,int npcIcon,int itemIcon,LootTable table,String note,int... linked){table(key,title,group,location,conditions,npcIcon,itemIcon,table,note,false,linked);}
    private static void table(String key,String title,Group group,String location,String conditions,int npcIcon,int itemIcon,LootTable table,String note,boolean conditionalRate,int... linked){List<Drop> drops=drops(table,note);if(!drops.isEmpty())add(new Source(key,title,group,location,conditions,npcIcon,itemIcon,drops,conditionalRate,linked));}
    private static String spawn(int id){for(NPC npc:World.npcs)if(npc!=null&&npc.getId()==id&&npc.spawnPosition!=null&&npc.spawnPosition.getX()<6400){Position at=npc.spawnPosition;return at.getX()+", "+at.getY()+" (plane "+at.getZ()+")";}return "Instance or scripted spawn";}
    private static int[] aliases(int id){NPCDef base=NPCDef.get(id);if(base==null)return new int[0];return namedAliases(plain(base.name));}
    private static int[] namedAliases(String name){Set<Integer> ids=new LinkedHashSet<>();String expected=plain(name).toLowerCase(Locale.ROOT);NPCDef.forEach(def->{if(def!=null&&def.id>=0&&def.id<=16383&&plain(def.name).toLowerCase(Locale.ROOT).equals(expected))ids.add(def.id);});return integers(ids);}
    private static int[] integers(Collection<Integer> values){return values.stream().mapToInt(Integer::intValue).toArray();}
    private static String plain(String text){return text==null?"":text.replaceAll("<[^>]*>","").trim();}
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,LootCompendiumSources.class.getClassLoader());}
    private static int number(Object target,String name)throws Exception{return ((Number)field(target,name)).intValue();}
    private static Object field(Object target,String name)throws Exception {Class<?> owner=target instanceof Class?(Class<?>)target:target.getClass();Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class?null:target);}
    private static Object call(Object target,String name)throws Exception{return call(target,name,new Class[0]);}
    private static Object call(Object target,String name,Class<?>[] args,Object... values)throws Exception {Method m=target.getClass().getDeclaredMethod(name,args);m.setAccessible(true);return m.invoke(target,values);}

    /** Literal arrays come from the actual compiled roller, not copied catalogue tables. */
    private static ClassNode code(Class<?> owner)throws IOException {
        String path="/"+owner.getName().replace('.','/')+".class";try(InputStream input=owner.getResourceAsStream(path)){
            if(input==null)throw new IOException("Missing compiled reward resource "+owner.getName());ClassNode node=new ClassNode();new ClassReader(input).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;
        }
    }
    private static AbstractInsnNode next(AbstractInsnNode node){do{node=node==null?null:node.getNext();}while(node!=null&&node.getOpcode()<0);return node;}
    private static AbstractInsnNode previous(AbstractInsnNode node){do{node=node==null?null:node.getPrevious();}while(node!=null&&node.getOpcode()<0);return node;}
    private static Integer integer(AbstractInsnNode node){if(node==null)return null;int op=node.getOpcode();if(op>=Opcodes.ICONST_M1&&op<=Opcodes.ICONST_5)return op-Opcodes.ICONST_0;if(node instanceof IntInsnNode&&(op==Opcodes.BIPUSH||op==Opcodes.SIPUSH))return ((IntInsnNode)node).operand;if(node instanceof LdcInsnNode&&((LdcInsnNode)node).cst instanceof Integer)return(Integer)((LdcInsnNode)node).cst;return null;}
    static List<int[]> arrays(Class<?> owner,String method)throws IOException {
        List<int[]> out=new ArrayList<>();for(MethodNode body:code(owner).methods){if(!body.name.equals(method))continue;
            for(AbstractInsnNode node=body.instructions.getFirst();node!=null;node=node.getNext()){
                if(!(node instanceof IntInsnNode)||node.getOpcode()!=Opcodes.NEWARRAY||((IntInsnNode)node).operand!=Opcodes.T_INT)continue;Integer count=integer(previous(node));if(count==null||count<1||count>1024)continue;
                int[] row=new int[count];AbstractInsnNode at=node;boolean valid=true;
                for(int i=0;i<count;i++){at=next(at);if(at==null||at.getOpcode()!=Opcodes.DUP){valid=false;break;}Integer index=integer(at=next(at)),value=integer(at=next(at));at=next(at);if(index==null||index!=i||value==null||at==null||at.getOpcode()!=Opcodes.IASTORE){valid=false;break;}row[i]=value;}
                if(valid)out.add(row);
            }
        }return out;
    }
    private static double singleFraction(Class<?> owner,String method,double min,double max)throws IOException {
        Set<Double> found=new HashSet<>();for(MethodNode body:code(owner).methods)if(method==null||body.name.equals(method))for(AbstractInsnNode node=body.instructions.getFirst();node!=null;node=node.getNext())if(node instanceof LdcInsnNode&&((LdcInsnNode)node).cst instanceof Double){double value=(Double)((LdcInsnNode)node).cst;if(value>min&&value<max)found.add(value);}return found.size()==1?found.iterator().next():Double.NaN;
    }
}
