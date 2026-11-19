package io.ruin.model.inter.loot;

import com.google.gson.Gson;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.*;
import io.ruin.model.map.Position;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.item.loot.LootItem;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only, server-authoritative loot catalogue. It never rolls, grants or modifies loot. */
public final class LootCompendium {
    public static final int INTERFACE=383;
    public static final String PREFIX="@LC1:";
    public enum Group{MONSTERS("Monster Drops",536),BOSSES("Boss Drops",964),MINIGAMES("Minigame Rewards",995),CHESTS("Chest Loot",995),OTHER("Other Sources",995);
        final String title;final int icon;Group(String title,int icon){this.title=title;this.icon=icon;}}
    public static final class Drop{
        public final int itemId,min,max;public final double probability;public final String quantity,condition;
        public Drop(int id,int min,int max,double chance,String condition){this(id,min,max,chance,null,condition);}
        public Drop(int id,int min,int max,double chance,String quantity,String condition){this.itemId=id;this.min=min;this.max=max;probability=chance;this.quantity=quantity;this.condition=condition;}
    }
    public static final class Source{
        public final String key,title,location,conditions;public final Group group;public final int npcIcon,itemIcon;
        public final List<Drop> drops;public final int[] linkedNpcIds;public final boolean conditionalRate;
        public Source(String key,String title,Group group,String location,String conditions,int npcIcon,int itemIcon,List<Drop> drops,int... linkedNpcIds){
            this(key,title,group,location,conditions,npcIcon,itemIcon,drops,false,linkedNpcIds);
        }
        /** Numeric probabilities are within an eligibility/branch table, not the whole reward outcome. */
        public Source(String key,String title,Group group,String location,String conditions,int npcIcon,int itemIcon,List<Drop> drops,boolean conditionalRate,int... linkedNpcIds){
            this.key=Objects.requireNonNull(key);this.title=title;this.group=group;this.location=location;this.conditions=conditions;this.npcIcon=npcIcon;this.itemIcon=itemIcon;
            this.drops=Collections.unmodifiableList(new ArrayList<>(drops));this.linkedNpcIds=linkedNpcIds==null?new int[0]:linkedNpcIds.clone();this.conditionalRate=conditionalRate;
        }
    }
    private static final Map<String,Source> SOURCES=new LinkedHashMap<>();
    private static final Map<Integer,List<Source>> BY_ITEM=new HashMap<>(),BY_NPC=new HashMap<>();
    private static final Map<Player,State> STATES=new WeakHashMap<>();
    private static final Map<Player,State> SEARCHES=new WeakHashMap<>();
    private static final Map<Player,Long> LAST_OPEN=new WeakHashMap<>();
    private static final Gson GSON=new Gson();
    private static boolean indexed,initialized;
    private static int nextToken=1;
    private LootCompendium(){}
    private static final class State{
        boolean monsters,noteMonsters;String query="",note;int selected=-1,resultPage,detailPage,noteSelected=-2;long lastAction;
        final Set<String> collapsed=new HashSet<>();
        final Map<Integer,Action> actions=new HashMap<>();
        final Map<Integer,Action> infos=new HashMap<>();
    }
    private interface Action{void run(Player p,State s);}
    public static void init(){
        if(initialized)return;
        final InterfaceHandler previous=InterfaceHandler.HANDLERS[INTERFACE];
        InterfaceHandler.register(INTERFACE,h->{
            if(previous!=null)System.arraycopy(previous.actions,0,h.actions,0,Math.min(previous.actions.length,h.actions.length));
            for(int child=0;child<=3&&child<h.actions.length;child++)h.actions[child]=new LegacyAwareAction(h.actions[child]);
            h.closedAction=(p,id)->{STATES.remove(p);SEARCHES.remove(p);if(previous!=null&&previous.closedAction!=null)previous.closedAction.accept(p,id);};
        });
        initialized=true;
    }
    /** Preserve every legacy383 action path when the compendium does not own this modal. */
    private static final class LegacyAwareAction implements InterfaceAction{
        private final InterfaceAction legacy;
        LegacyAwareAction(InterfaceAction legacy){this.legacy=legacy;}
        public void handleClick(Player p,int option,int slot,int itemId){
            State s=STATES.get(p);if(s==null){if(legacy!=null)legacy.handleClick(p,option,slot,itemId);return;}
            if(itemId<1||itemId>=65000||(option!=1&&option!=2))return;
            long now=System.nanoTime();if(now-s.lastAction<150_000_000L)return;
            Action action=(option==1?s.actions:s.infos).get(itemId);
            if(action!=null){s.lastAction=now;try{action.run(p,s);}catch(RuntimeException|LinkageError invalid){failed(p,invalid);}}
        }
        public void handleDrag(Player p,int fromSlot,int fromItem,int toInterface,int toChild,int toSlot,int toItem){if(!STATES.containsKey(p)&&legacy!=null)legacy.handleDrag(p,fromSlot,fromItem,toInterface,toChild,toSlot,toItem);}
        public void handleOnInterface(Player p,int fromSlot,int fromItem,int toInterface,int toChild,int toSlot,int toItem){if(!STATES.containsKey(p)&&legacy!=null)legacy.handleOnInterface(p,fromSlot,fromItem,toInterface,toChild,toSlot,toItem);}
        public void handleOnGroundItem(Player p,int slot,int itemId,GroundItem item,int distance){if(!STATES.containsKey(p)&&legacy!=null)legacy.handleOnGroundItem(p,slot,itemId,item,distance);}
        public void handleOnObject(Player p,int slot,int itemId,GameObject object){if(!STATES.containsKey(p)&&legacy!=null)legacy.handleOnObject(p,slot,itemId,object);}
        public void handleOnEntity(Player p,Entity entity,int slot,int itemId){if(!STATES.containsKey(p)&&legacy!=null)legacy.handleOnEntity(p,entity,slot,itemId);}
    }
    public static void register(Source source){
        if(SOURCES.putIfAbsent(source.key,source)!=null)throw new IllegalArgumentException("Duplicate loot source "+source.key);
        if(indexed)index(source);
    }
    private static void ensureIndex(){
        if(indexed)return;
        try{Class.forName("io.ruin.model.inter.loot.LootCompendiumSources").getMethod("install").invoke(null);}
        catch(ClassNotFoundException absent){}
        catch(ReflectiveOperationException invalid){io.ruin.Server.logError("Loot compendium reward providers",invalid);}
        indexed=true;
        NPCDef.forEach(def->{
            if(def==null||!named(def.name)||(def.lootTable==null&&(def.combatInfo==null||def.combatInfo.pet==null)))return;
            List<Drop> drops=new ArrayList<>();
            for(LootCompendiumRates.Roll roll:LootCompendiumRates.read(def.lootTable))drops.add(new Drop(roll.item.id,roll.item.min,roll.item.max,roll.probability,roll.note));
            if(def.combatInfo!=null&&def.combatInfo.pet!=null&&def.combatInfo.pet.dropAverage>0)drops.add(new Drop(def.combatInfo.pet.itemId,1,1,1D/def.combatInfo.pet.dropAverage,"Separate base pet unlock roll"));
            if(drops.isEmpty())return;
            Source source=new Source("npc:"+def.id,plain(def.name),Group.MONSTERS,spawnLocation(def.id),"Base table selection per roll. Player bonuses, extra rolls and location/event drops may differ.",def.id,-1,drops,def.id);
            if(!SOURCES.containsKey(source.key))SOURCES.put(source.key,source);
        });
        for(Source source:SOURCES.values())index(source);
    }
    private static void index(Source source){
        Set<Integer> seen=new HashSet<>();for(Drop drop:source.drops)if(drop.itemId>=0&&drop.itemId<65535&&ItemDef.get(drop.itemId)!=null&&seen.add(itemKey(drop.itemId)))BY_ITEM.computeIfAbsent(itemKey(drop.itemId),k->new ArrayList<>()).add(source);
        for(int id:source.linkedNpcIds)BY_NPC.computeIfAbsent(id,k->new ArrayList<>()).add(source);
    }
    public static void open(Player p){
        if(!allowOpen(p))return;
        try{ensureIndex();show(p,new State());}catch(RuntimeException|LinkageError invalid){failed(p,invalid);}
    }
    public static void examine(Player p,int nativeNpcId){
        if(nativeNpcId<0||nativeNpcId>16383||!allowOpen(p))return;
        try{ensureIndex();NPCDef def=NPCDef.get(nativeNpcId);if(def==null||!named(def.name))return;
            State s=new State();s.monsters=true;s.selected=nativeNpcId;s.query=plain(def.name);show(p,s);
        }catch(RuntimeException|LinkageError invalid){failed(p,invalid);}
    }
    private static void show(Player p,State state){
        SEARCHES.remove(p);
        // openInterface invokes the previous modal's close callback. Install our state after it,
        // otherwise reopening383 would remove the freshly created action map.
        p.openInterface(InterfaceType.MAIN,INTERFACE);STATES.put(p,state);render(p,state);
    }
    private static void search(Player p,State state){
        if(STATES.get(p)!=state||!p.isVisibleInterface(INTERFACE))return;
        // Native meslayer_onkey rejects typing while MAIN has a subinterface.
        // Its close callback removes STATES; retain this page only for this input.
        p.closeInterface(InterfaceType.MAIN);SEARCHES.put(p,state);
        p.stringInput("Search for an item or monster:",query->{
            if(SEARCHES.get(p)!=state)return;SEARCHES.remove(p);
            if(!p.isOnline()||STATES.containsKey(p))return;
            String bounded=query==null?"":query.substring(0,Math.min(64,query.length()));
            state.query=plain(bounded);state.resultPage=state.detailPage=0;state.selected=-1;
            show(p,state);
        });
    }
    private static void failed(Player p,Throwable invalid){io.ruin.Server.logError("Loot compendium page",invalid);p.sendMessage("The loot catalogue is temporarily unavailable.");}
    private static boolean allowOpen(Player p){if(p==null||!p.isOnline())return false;long now=System.nanoTime(),previous=LAST_OPEN.getOrDefault(p,0L);if(now-previous<400_000_000L)return false;LAST_OPEN.put(p,now);return true;}
    private static int action(State state,Action action){int token=nextToken++;if(nextToken>=65000)nextToken=1;state.actions.put(token,action);return token;}
    private static final class Result{int id;String name,sub;boolean npc;int action;}
    private static final class Row{int id=-1,npc=-1,action;String title,location,rate,quantity;transient String note;}
    private static final class Section{String key,title;int icon,action,count;boolean collapsed;List<Row> rows=new ArrayList<>();}
    private static final class View{
        int v=1;boolean monsters;String query,title,description,meta,notice,resultLabel,detailLabel,note;int item=-1,npc=-1,selected=-1;
        int itemsAction,monstersAction,searchAction,closeAction,previousAction,nextAction,detailPreviousAction,detailNextAction;
        List<Result> results=new ArrayList<>();List<Section> sections=new ArrayList<>();
    }
    private static void render(Player p,State s){
        if(!p.isOnline()||!p.isVisibleInterface(INTERFACE))return;
        Map<Integer,Action> priorActions=new HashMap<>(s.actions),priorInfos=new HashMap<>(s.infos);
        s.actions.clear();s.infos.clear();View v=new View();v.monsters=s.monsters;v.query=s.query;
        v.itemsAction=action(s,(player,state)->{state.monsters=false;state.query="";state.selected=-1;state.resultPage=state.detailPage=0;render(player,state);});
        v.monstersAction=action(s,(player,state)->{state.monsters=true;state.query="";state.selected=-1;state.resultPage=state.detailPage=0;render(player,state);});
        v.closeAction=action(s,(player,state)->{STATES.remove(player);player.closeInterface(InterfaceType.MAIN);});
        v.searchAction=action(s,LootCompendium::search);
        List<Integer> results=new ArrayList<>();String query=normal(s.query);
        if(s.monsters){NPCDef.forEach(def->{if(def!=null&&named(def.name)&&(def.combatInfo!=null||BY_NPC.containsKey(def.id))&&normal(def.name).contains(query))results.add(def.id);});}
        else for(int id:BY_ITEM.keySet()){ItemDef def=ItemDef.get(id);if(def!=null&&named(def.name)&&normal(def.name).contains(query))results.add(id);}
        results.sort((a,b)->{String x=s.monsters?plain(NPCDef.get(a).name):plain(ItemDef.get(a).name),y=s.monsters?plain(NPCDef.get(b).name):plain(ItemDef.get(b).name);int c=x.compareToIgnoreCase(y);return c==0?Integer.compare(a,b):c;});
        final int size=30;int pages=Math.max(1,(results.size()+size-1)/size);s.resultPage=Math.max(0,Math.min(s.resultPage,pages-1));
        if(s.selected<0&&!results.isEmpty())s.selected=results.get(s.resultPage*size);
        v.resultLabel="Search Results ("+results.size()+") - "+(s.resultPage+1)+"/"+pages;
        if(s.resultPage>0)v.previousAction=action(s,(player,state)->{state.resultPage--;render(player,state);});
        if(s.resultPage+1<pages)v.nextAction=action(s,(player,state)->{state.resultPage++;render(player,state);});
        for(int i=s.resultPage*size;i<Math.min(results.size(),(s.resultPage+1)*size);i++){
            final int id=results.get(i);Result result=new Result();result.id=id;result.npc=s.monsters;result.name=s.monsters?plain(NPCDef.get(id).name):plain(ItemDef.get(id).name);
            result.sub=s.monsters?"Level "+Math.max(0,NPCDef.get(id).combatLevel):BY_ITEM.get(id).size()+" source(s)";
            result.action=action(s,(player,state)->{state.selected=id;state.detailPage=0;render(player,state);});v.results.add(result);
        }
        v.notice="Base rates per roll. * Conditional table chance; right-click Details for eligibility.";
        if(s.monsters)monsterView(s,v);else itemView(s,v);v.selected=s.selected;
        if(s.noteSelected==s.selected&&s.noteMonsters==s.monsters)v.note=s.note;
        String json=GSON.toJson(v);String envelope=PREFIX+Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        if(envelope.length()>62000){s.actions.clear();s.actions.putAll(priorActions);s.infos.clear();s.infos.putAll(priorInfos);p.sendMessage("This source has too many rows for one page. Please narrow the search.");return;}
        p.getPacketSender().sendDropTable(envelope,-1,0,Collections.emptyList());
    }
    private static void itemView(State s,View v){
        ItemDef def=ItemDef.get(s.selected);if(def==null){v.title="Search the compendium";v.description="Find an item to see its real sources.";return;}
        v.item=def.id;v.title=plain(def.name);v.description=def.examine==null?"Item sources and configured loot rates.":plain(def.examine);
        v.meta="Base value: "+def.value+" gp  |  Tradeable: "+(def.tradeable?"Yes":"No");
        List<Source> sources=BY_ITEM.getOrDefault(def.id,Collections.emptyList());
        Map<Group,Section> sections=new EnumMap<>(Group.class);
        for(Source source:sources)for(Drop drop:source.drops)if(itemKey(drop.itemId)==def.id){
            Section section=sections.computeIfAbsent(source.group,g->section(s,g.name(),g.title,g.icon));Row row=new Row();
            row.npc=source.npcIcon;row.id=source.itemIcon;row.title=source.title;row.location=empty(source.location,"Conditional source");row.rate=rate(source,drop);row.quantity=quantity(drop)+(ItemDef.get(drop.itemId).isNote()?" (noted)":"");row.note=join(source.conditions,drop.condition);
            if(source.linkedNpcIds.length>0&&NPCDef.get(source.linkedNpcIds[0])!=null){final int id=source.linkedNpcIds[0];row.action=action(s,(p,state)->{state.monsters=true;state.selected=id;state.query=plain(NPCDef.get(id).name);state.resultPage=state.detailPage=0;render(p,state);});}
            info(s,row);
            section.rows.add(row);
        }
        v.sections.addAll(sections.values());limitRows(s,v);
    }
    private static void monsterView(State s,View v){
        NPCDef def=NPCDef.get(s.selected);if(def==null){v.title="Search monsters";v.description="Choose a monster to inspect its real tables.";return;}
        v.npc=def.id;v.title=plain(def.name);v.description="Combat level: "+Math.max(0,def.combatLevel);
        v.meta=def.combatInfo==null?"Combat stats not published": "HP: "+def.combatInfo.hitpoints+"  |  Max standard hit: "+def.combatInfo.max_damage;
        List<Source> sources=BY_NPC.getOrDefault(def.id,Collections.emptyList());
        for(Source source:sources){Section section=section(s,source.key,source.title+" - "+source.group.title,source.itemIcon>=0?source.itemIcon:536);
            for(Drop drop:source.drops){ItemDef item=ItemDef.get(drop.itemId);if(item==null)continue;final int id=itemKey(item.id);Row row=new Row();row.id=item.id;row.title=plain(item.name)+(item.isNote()?" (noted)":"");row.location=quantity(drop);row.rate=rate(source,drop);row.note=join(source.conditions,drop.condition);row.action=action(s,(p,state)->{state.monsters=false;state.selected=id;state.query=plain(item.name);state.resultPage=state.detailPage=0;render(p,state);});info(s,row);section.rows.add(row);}v.sections.add(section);
        }
        if(sources.isEmpty())v.description+=". No direct loot table is registered for this NPC.";
        limitRows(s,v);
    }
    private static void info(State s,Row row){
        final String text=plain(row.title)+": "+plain(row.rate)+". "+plain(row.quantity)+" "+plain(row.note);
        Action show=(p,state)->{state.note=text;state.noteSelected=state.selected;state.noteMonsters=state.monsters;render(p,state);};
        if(row.action==0)row.action=action(s,show);s.infos.put(row.action,show);
    }
    private static Section section(State s,String key,String title,int icon){Section out=new Section();out.key=key;out.title=title;out.icon=icon;out.collapsed=s.collapsed.contains(key);out.action=action(s,(p,state)->{if(!state.collapsed.add(key))state.collapsed.remove(key);render(p,state);});return out;}
    private static void limitRows(State s,View v){
        int total=0;for(Section section:v.sections)if(!section.collapsed)total+=section.rows.size();int pages=Math.max(1,(total+59)/60);s.detailPage=Math.max(0,Math.min(s.detailPage,pages-1));
        int start=s.detailPage*60,end=start+60,index=0;for(Section section:v.sections){section.count=section.rows.size();List<Row> kept=new ArrayList<>();for(Row row:section.rows)if(!section.collapsed){if(index>=start&&index<end)kept.add(row);index++;}section.rows=kept;}
        v.detailLabel="Source rows "+(total==0?0:start+1)+"-"+Math.min(total,end)+" / "+total;
        if(s.detailPage>0)v.detailPreviousAction=action(s,(p,state)->{state.detailPage--;render(p,state);});if(s.detailPage+1<pages)v.detailNextAction=action(s,(p,state)->{state.detailPage++;render(p,state);});
    }
    private static String rate(Source source,Drop drop){return LootCompendiumRates.display(drop.probability)+(source.conditionalRate&&Double.isFinite(drop.probability)&&drop.probability>0?"*":"");}
    private static String quantity(Drop drop){return drop.quantity!=null?drop.quantity:drop.min==drop.max?"x"+drop.min:"x"+drop.min+"-"+drop.max;}
    private static int itemKey(int id){ItemDef def=ItemDef.get(id);return def!=null&&def.isNote()&&ItemDef.get(def.notedId)!=null?def.notedId:id;}
    private static String spawnLocation(int npcId){
        LinkedHashSet<String> locations=new LinkedHashSet<>();for(NPC npc:World.npcs)if(npc!=null&&npc.getId()==npcId&&npc.spawnPosition!=null&&npc.spawnPosition.getX()<6400){Position p=npc.spawnPosition;locations.add(p.getX()+", "+p.getY()+" (plane "+p.getZ()+")");if(locations.size()==2)break;}
        return locations.isEmpty()?"Instance or scripted spawn":String.join(" / ",locations);
    }
    private static boolean named(String name){return name!=null&&!plain(name).isEmpty()&&!plain(name).equalsIgnoreCase("null");}
    private static String plain(String text){return text==null?"":text.replaceAll("<[^>]*>","").replace('\u0000',' ').trim();}
    private static String normal(String text){return plain(text).toLowerCase(Locale.ROOT).replace("'","");}
    private static String empty(String text,String fallback){return text==null||text.isEmpty()?fallback:text;}
    private static String join(String a,String b){return empty(a,"")+(a!=null&&b!=null?" ":"")+empty(b,"");}
}
