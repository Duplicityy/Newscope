package io.ruin.model.activities.dungeoneering;

import com.google.gson.Gson;
import io.ruin.cache.ItemDef;
import io.ruin.cache.ObjectDef;
import io.ruin.content.pets.UniversalPets;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.actions.ItemItemAction;
import io.ruin.model.item.actions.ItemNPCAction;
import io.ruin.model.item.actions.ItemObjectAction;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.map.route.routes.DumbRoute;
import java.util.*;

/** Original pre-EOC reward bags and object-pet lifecycle, using per-item save attributes.
 * Source: pinned Darkan CoalBag.java, GemBag.kt and pets/{Pets,Pet,PetManager}.java.
 * Ordinary gem bag is100 TOTAL uncut sapphires/emeralds/rubies/diamonds; coal bag27.
 * The source Sneakerpeeper grows0.05% and gains0.025% hunger per game tick;
 * eye of newt reduces hunger15%. There is no modern appearance-unlock shortcut.
 */
public final class DungeoneeringUtilityRewards {
    private static DungeoneeringAssets assets;
    private static int gems=-1,coal=-1,baby=-1,adult=-1;
    private static final int[] GEMS={1623,1621,1619,1617};
    private static final String BAG="dung.bag.",GROWTH="dung.pet.growthMilli",HUNGER="dung.pet.hungerMilli";
    private static final Map<Player,NPC> FOLLOWERS=new IdentityHashMap<>();
    private static final Gson JSON=new Gson();
    private static final class Units { List<Map<String,String>> items=new ArrayList<>(); }
    private DungeoneeringUtilityRewards(){}
    public static Set<Integer> implementedSourceIds(){return new HashSet<>(Arrays.asList(18338,18339,19894));}

    public static void register(DungeoneeringAssets data){
        assets=data;gems=data.item(18338);coal=data.item(18339);baby=data.item(19894);adult=data.item(19895);
        ItemAction.registerInventory(gems,1,(p,item)->bagMenu(p,item));
        ItemAction.registerInventory(gems,2,(p,item)->withdrawMenu(p,item));
        ItemAction.registerInventory(gems,3,(p,item)->empty(p,item,p.isVisibleInterface(Interface.BANK)));
        ItemAction.registerInventory(coal,1,(p,item)->bagMenu(p,item));
        ItemAction.registerInventory(coal,2,(p,item)->withdraw(p,item,453,1,false));
        ItemAction.registerInventory(coal,3,(p,item)->p.integerInput("How much coal?",n->{if(n>0)withdraw(p,item,453,n,false);}));
        for(int gem:GEMS)ItemItemAction.register(gems,gem,(p,bag,stone)->fill(p,bag,stone.getId()));
        ItemItemAction.register(coal,453,(p,bag,ore)->fill(p,bag,453));
        ObjectDef.forEach(def->{if(def.name!=null&&(def.name.toLowerCase(Locale.ROOT).contains("bank")||def.hasOption("bank","deposit"))){
            ItemObjectAction.register(gems,def.id,(p,item,obj)->empty(p,item,true));
            ItemObjectAction.register(coal,def.id,(p,item,obj)->empty(p,item,true));
        }});
        for(int id:new int[]{baby,adult}){
            // The original item's Drop action releases its pet instead of creating a ground item.
            ItemAction.registerInventory(id,5,DungeoneeringUtilityRewards::release);
        }
        for(int source:new int[]{13089,13090}){
            int id=data.npc(source);
            NPCAction.register(id,1,(p,n)->{if(owns(p,n))pickup(p,false);else p.sendMessage("That is not your pet.");});
            NPCAction.register(id,3,(p,n)->{if(owns(p,n))petMenu(p);});
            ItemNPCAction.register(221,id,(p,item,n)->{if(owns(p,n))feed(p);else p.sendMessage("That is not your pet.");});
        }
    }
    private static boolean inventory(Player p,Item item){return item!=null&&item.getAmount()==1&&p.getInventory().get(item.getSlot())==item&&!Dungeoneering.inRun(p)&&!PlayerFile.isSaveQuarantined(p);}
    private static int capacity(Item bag){return bag.getId()==gems?100:27;}
    private static int[] contents(Item bag){return bag.getId()==gems?GEMS:new int[]{453};}
    private static int stored(Item bag,int id){try{int n=bag.getAttributeInt(BAG+id,0);return n<0||n>capacity(bag)?-1:n;}catch(NumberFormatException bad){return -1;}}
    private static Units units(Item bag,int id){
        try{int count=stored(bag,id);if(count<0)return null;String raw=bag.getAttributeString(BAG+id+".attributes","");Units result=raw.isEmpty()?new Units():JSON.fromJson(raw,Units.class);if(result==null||result.items==null)return null;if(raw.isEmpty())while(result.items.size()<count)result.items.add(new HashMap<>());if(result.items.size()!=count)return null;for(Map<String,String> attr:result.items)if(attr==null)return null;return result;}catch(RuntimeException invalid){return null;}
    }
    private static void store(Item bag,int id,Units units){bag.putAttribute(BAG+id,units.items.size());bag.putAttribute(BAG+id+".attributes",JSON.toJson(units));bag.update();}
    private static int total(Item bag){int n=0;for(int id:contents(bag)){Units units=units(bag,id);if(units==null)return -1;n+=units.items.size();}return n>capacity(bag)?-1:n;}
    private static boolean validBag(Player p,Item bag){if(!inventory(p,bag))return false;if(total(bag)<0){p.sendMessage("This bag's saved contents need staff attention. They have been left unchanged.");return false;}return true;}
    private static void fill(Player p,Item bag,int only){
        if(!validBag(p,bag))return;
        int room=capacity(bag)-total(bag),moved=0;
        for(int id:contents(bag))if(only<0||id==only){Units units=units(bag,id);for(Item source:p.getInventory().getItems())if(source!=null&&source.getId()==id&&room>0){int count=Math.min(room,source.getAmount());Map<String,String> attr=source.copyOfAttributes();int actual=source.remove(count);for(int n=0;n<actual;n++)units.items.add(attr==null?new HashMap<>():new HashMap<>(attr));room-=actual;moved+=actual;}store(bag,id,units);}
        bag.update();p.sendMessage(moved==0?"No eligible items fit in this bag.":"Stored "+moved+" item"+(moved==1?".":"s."));
    }
    private static void withdraw(Player p,Item bag,int id,int requested,boolean bank){
        if(!validBag(p,bag)||requested<=0)return;
        Units units=units(bag,id);int amount=Math.min(units.items.size(),requested);if(amount<=0){p.sendMessage("The bag has none of that item.");return;}
        int moved=0;while(moved<amount){Map<String,String> attr=units.items.get(0);int actual=bank?p.getBank().add(id,1,attr):p.getInventory().add(id,1,attr);if(actual!=1)break;units.items.remove(0);moved++;}
        store(bag,id,units);
        if(moved<amount)p.sendMessage(bank?"Your bank cannot hold the remaining contents.":"Your inventory cannot hold the remaining contents.");
    }
    private static void empty(Player p,Item bag,boolean bank){if(!validBag(p,bag))return;for(int id:contents(bag))if(stored(bag,id)>0)withdraw(p,bag,id,Integer.MAX_VALUE,bank);}
    public static boolean emptyAtBank(Player p,Item bag){if(assets==null||bag==null||bag.getId()!=gems&&bag.getId()!=coal)return false;if(p.isVisibleInterface(Interface.BANK))empty(p,bag,true);return true;}
    private static void bagMenu(Player p,Item bag){
        if(!validBag(p,bag))return;List<Option> menu=new ArrayList<>();int count=total(bag);
        menu.add(new Option("Fill from inventory ("+count+"/"+capacity(bag)+")",()->fill(p,bag,-1)));
        menu.add(new Option("Withdraw contents",()->withdrawMenu(p,bag)));
        if(p.isVisibleInterface(Interface.BANK))menu.add(new Option("Empty contents into bank",()->empty(p,bag,true)));
        List<String> lines=new ArrayList<>();for(int id:contents(bag))lines.add(ItemDef.get(id).name+": "+stored(bag,id));
        menu.add(new Option("Inspect stored items",()->p.sendScroll(bag.getDef().name,lines.toArray(new String[0]))));
        OptionScroll.open(p,bag.getDef().name,false,menu);
    }
    private static void withdrawMenu(Player p,Item bag){if(!validBag(p,bag))return;List<Option> menu=new ArrayList<>();for(int id:contents(bag)){final int type=id;menu.add(new Option(ItemDef.get(id).name+" ("+stored(bag,id)+")",()->withdraw(p,bag,type,Integer.MAX_VALUE,false)));}OptionScroll.open(p,"Withdraw stored items",false,menu);}

    private static boolean owns(Player p,NPC n){return FOLLOWERS.get(p)==n&&n.ownerId==p.getUserId();}
    private static int value(Item item,String key,int fallback){try{return Math.max(0,Math.min(100000,item.getAttributeInt(key,fallback)));}catch(NumberFormatException e){return fallback;}}
    private static boolean requirements(Player p){if(Dungeoneering.progress(p).level()<80||DungeoneeringSummoning.level(p)<80){p.sendMessage("You need level80 Dungeoneering and dungeon Summoning to care for a Sneakerpeeper.");return false;}return true;}
    private static void release(Player p,Item item){
        if(!inventory(p,item)||!requirements(p))return;
        if(p.pet!=null||UniversalPets.hasActivePet(p)||Dungeoneering.progress(p).utilityPet!=null){p.sendMessage("Pick up your current follower first. Use ::dungpet for a stored Sneakerpeeper.");return;}
        Item owned=item.copy();owned.setAmount(1);if(item.remove(1)!=1)return;
        Dungeoneering.progress(p).utilityPet=owned;Dungeoneering.save(p,()->spawn(p));
    }
    private static void spawn(Player p){
        if(assets==null||!p.isOnline()||p.pet!=null||UniversalPets.hasActivePet(p)||Dungeoneering.inRun(p)||PlayerFile.isSaveQuarantined(p)||FOLLOWERS.containsKey(p))return;
        Item owned=Dungeoneering.progress(p).utilityPet;if(owned==null||owned.getId()!=baby&&owned.getId()!=adult)return;
        final NPC npc=new NPC(assets.npc(owned.getId()==adult?13090:13089));npc.ownerId=p.getUserId();npc.spawn(p.getAbsX(),p.getAbsY(),p.getHeight());npc.face(p);FOLLOWERS.put(p,npc);Config.PET_NPC_INDEX.set(p,npc.getIndex());
        npc.attackNpcListener=(who,target,message)->false;
        npc.addEvent(e->{
            while(p.isOnline()&&FOLLOWERS.get(p)==npc&&Dungeoneering.progress(p).utilityPet==owned&&!npc.isRemoved()){
                if(p.pet!=null||Dungeoneering.inRun(p))break;
                if(!p.getCombat().isDead()&&!p.getMovement().isTeleportQueued()){
                    int growth=value(owned,GROWTH,owned.getId()==adult?100000:0),hunger=value(owned,HUNGER,0);
                    owned.putAttribute(HUNGER,Math.min(100000,hunger+25));
                    if(owned.getId()==baby){growth=Math.min(100000,growth+50);owned.putAttribute(GROWTH,growth);if(growth==100000){owned.setId(adult);npc.transform(assets.npc(13090));p.sendMessage("Your Sneakerpeeper has grown larger.");}}
                    if(p.callPet||!npc.getPosition().isWithinDistance(p.getPosition(),12)){p.callPet=false;npc.getMovement().teleport(p.getAbsX(),p.getAbsY(),p.getHeight());}
                    else if(!npc.getPosition().isWithinDistance(p.getPosition(),1))DumbRoute.step(npc,p,1);
                    if(p.hidePet){p.hidePet=false;npc.setHidden(true);}if(p.showPet){p.showPet=false;npc.setHidden(false);}
                }
                e.delay(1);
            }
            if(FOLLOWERS.get(p)==npc){FOLLOWERS.remove(p);if(Config.PET_NPC_INDEX.get(p)==npc.getIndex())Config.PET_NPC_INDEX.set(p,-1);}npc.remove();
        });
    }
    private static void suspend(Player p){NPC npc=FOLLOWERS.remove(p);if(npc!=null){if(Config.PET_NPC_INDEX.get(p)==npc.getIndex())Config.PET_NPC_INDEX.set(p,-1);npc.remove();}}
    public static boolean hasFollower(Player p){NPC npc=FOLLOWERS.get(p);return npc!=null&&!npc.isRemoved();}
    public static void beforeOtherPet(Player p){suspend(p);}
    public static void beforeDungeon(Player p){suspend(p);}
    public static void logout(Player p){suspend(p);}
    public static void login(Player p){if(assets!=null&&Dungeoneering.progress(p).utilityPet!=null)p.addEvent(e->{e.delay(1);spawn(p);});}
    private static void pickup(Player p,boolean bank){
        if(Dungeoneering.inRun(p)||PlayerFile.isSaveQuarantined(p))return;
        Item owned=Dungeoneering.progress(p).utilityPet;if(owned==null)return;
        int added=bank?p.getBank().add(owned.getId(),1,owned.copyOfAttributes()):p.getInventory().add(owned.copy());
        if(added!=1){p.sendMessage(bank?"Make room in your bank first.":"Make room in your inventory first.");return;}
        suspend(p);Dungeoneering.progress(p).utilityPet=null;Dungeoneering.save(p,()->p.sendMessage("You pick up your Sneakerpeeper."));
    }
    private static void feed(Player p){Item owned=Dungeoneering.progress(p).utilityPet;if(owned==null||!FOLLOWERS.containsKey(p))return;if(p.getInventory().remove(221,1)!=1){p.sendMessage("Your Sneakerpeeper eats eye of newt.");return;}owned.putAttribute(HUNGER,Math.max(0,value(owned,HUNGER,0)-15000));p.sendMessage("Your Sneakerpeeper happily eats the eye of newt.");}
    public static void petMenu(Player p){
        if(assets==null)return;Item owned=Dungeoneering.progress(p).utilityPet;if(owned==null){p.sendMessage("Release a Sneakerpeeper from your inventory to have it follow you.");return;}
        List<Option> menu=new ArrayList<>();menu.add(new Option("Growth: "+value(owned,GROWTH,owned.getId()==adult?100000:0)/1000.+"% / hunger: "+value(owned,HUNGER,0)/1000.+"%",()->{}));
        if(!FOLLOWERS.containsKey(p))menu.add(new Option("Resume following",()->spawn(p)));
        menu.add(new Option("Pick up / recover to inventory",()->pickup(p,false)));
        if(p.isVisibleInterface(Interface.BANK))menu.add(new Option("Store safely in bank",()->pickup(p,true)));
        menu.add(new Option("Feed an eye of newt",()->feed(p)));OptionScroll.open(p,"Sneakerpeeper",false,menu);
    }
}
