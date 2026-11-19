package io.ruin.model.activities.raids.toa.port;

import io.ruin.cache.ItemDef;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.actions.impl.Consumable;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;
import java.util.*;

/** Actual NR supply choices and consumable effects, scoped to this native run's tagged loans. */
public final class ToaSupplies {
    private static final String LOAN="toa.supply.loan",SOURCE="toa.supply.source";
    private static final Map<Player,ToaSupplies> OWNED=new IdentityHashMap<>();
    private static final Set<Integer> BOUND=new HashSet<>();
    private static final StatType[] COMBAT={StatType.Attack,StatType.Strength,StatType.Defence,StatType.Ranged,StatType.Magic};
    private enum Kind{
        NECTAR("Nectar",27315,27317,27319,27321),SILK("Silk dressing",27323,27325),
        TEARS("Tears of Elidinis",27327,27329,27331,27333),SCARAB("Blessed crystal scarab",27335,27337),
        ADRENALINE("Liquid adrenaline",27339,27341),SALTS("Smelling salts",27343,27345),AMBROSIA("Ambrosia",27347,27349);
        final String name;final int[] doses;
        Kind(String name,int... ids){this.name=name;doses=ids;}
        int nativeId(int dose){return NearRealityRaidAssets.item(doses[dose],name);}
    }
    private final TombsOfAmascutRaid.Session s;
    private final String token=UUID.randomUUID().toString();
    private final List<Item> stored=new ArrayList<>();
    private final Set<Integer> claimed=new HashSet<>();
    private int salts,adrenaline,silk,scarab;
    private boolean closed,ambrosiaUsed,saltsUsed;
    ToaSupplies(TombsOfAmascutRaid.Session s){this.s=s;OWNED.put(s.player,this);}
    static List<String> missingAssets(){
        List<String> missing=new ArrayList<>();
        if(NearRealityRaidAssets.npc(11694,"Helpful spirit")<0)missing.add("Helpful spirit11694");
        for(Kind kind:Kind.values())for(int dose=0;dose<kind.doses.length;dose++){
            int id=kind.nativeId(dose);
            if(id<0)missing.add(kind.name+" "+kind.doses[dose]);
            else if(ItemDef.get(id).getOption("Drink","Eat","Apply","Crush","Crack")<1)missing.add(kind.name+" native consume action");
        }
        int bag=NearRealityRaidAssets.item(27314,"Supplies");
        if(bag<0||ItemDef.get(bag).getOption("Open")<1)missing.add("Supplies bag27314 native Open action");
        return missing;
    }
    static void install(){
        for(Kind kind:Kind.values())for(int i=0;i<kind.doses.length;i++){
            final int dose=i,id=kind.nativeId(i);if(id<0||!BOUND.add(id))continue;
            bind(id,new String[]{"Drink","Eat","Apply","Crush","Crack"},(p,item)->{
                ToaSupplies supplies=OWNED.get(p);
                if(supplies!=null&&supplies.owns(item))supplies.consume(item,kind,dose);
                else if(item.copyOfAttributes().containsKey(LOAN))p.sendMessage("These loaned supplies belong to a finished Tombs run.");
            });
        }
        int bag=NearRealityRaidAssets.item(27314,"Supplies");
        if(bag>=0&&BOUND.add(bag))for(String action:new String[]{"Open","Withdraw 1","Withdraw All","Resupply"}){
            final String selected=action;
            bind(bag,new String[]{action},(p,item)->{
                ToaSupplies supplies=OWNED.get(p);if(supplies==null||!supplies.owns(item))return;
                if(selected.equals("Open"))supplies.openBag();else supplies.withdraw(null,selected.equals("Withdraw 1")?1:28);
            });
        }
    }
    /** Previous native handlers remain authoritative for items outside the owned run. */
    private static void bind(int id,String[] names,ItemAction action){
        ItemDef def=ItemDef.get(id);
        Set<Integer> options=new HashSet<>();
        for(String name:names){int option=def.getOption(name);if(option<1||option>5||!options.add(option))continue;
            final ItemAction previous=def.inventoryActions==null?null:def.inventoryActions[option-1];
            ItemAction.registerInventory(id,option,(p,item)->{
                ToaSupplies supplies=OWNED.get(p);
                if(supplies!=null&&supplies.owns(item))action.handle(p,item);
                else if(previous!=null)previous.handle(p,item);
                else if(item.copyOfAttributes().containsKey(LOAN))p.sendMessage("These supplies can only be used in their original Tombs run.");
            });
        }
    }
    private boolean live(){return !closed&&s.valid();}
    private boolean owns(Item item){return live()&&item!=null&&item.getContainer()==s.player.getInventory()&&token.equals(item.getAttributeString(LOAN,null));}
    private Item loan(int source,String name){int id=NearRealityRaidAssets.item(source,name);if(id<0)throw new IllegalStateException("Missing actual supply "+source);Item item=new Item(id);item.putAttribute(LOAN,token);item.putAttribute(SOURCE,source);return item;}
    private Item bag(){for(Item item:s.player.getInventory().getItems())if(item!=null&&token.equals(item.getAttributeString(LOAN,null))&&item.getAttributeInt(SOURCE)==27314)return item;return null;}
    boolean available(){int checkpoint=s.pathsCompleted()>=4?4:s.pathsCompleted()>=2?2:0;return s.fullRaid&&checkpoint>0&&!claimed.contains(checkpoint);}
    void openSelection(){
        if(!live()||s.room!=ToaRoom.MAIN_HALL||!available())return;
        s.player.dialogue(new OptionsDialogue("Helpful spirit - choose one supply pack",
                new Option("Life: healing, prayer and recovery",()->select(0)),
                new Option("Chaos: nectar, tears and salts",()->select(1)),
                new Option("Power: salts and adrenaline",()->select(2)),new Option("Cancel")));
    }
    private static final int[][][] PACKS={
        {{27315,5},{27327,5},{27347,2},{27335,3},{27323,3}},
        {{27315,8},{27327,6},{27343,2}},
        {{27343,2},{27339,2}}
    };
    private static Kind kind(int source){for(Kind kind:Kind.values())for(int id:kind.doses)if(id==source)return kind;return null;}
    private void select(int choice){
        if(!live()||s.room!=ToaRoom.MAIN_HALL||!available()||choice<0||choice>=PACKS.length)return;
        int needed=0;for(int[] item:PACKS[choice])needed+=item[1];
        if(stored.size()+needed>28){s.player.sendMessage("Withdraw some supplies first. The supply bag holds28 items.");return;}
        if(bag()==null){if(s.player.getInventory().getFreeSlots()<1){s.player.sendMessage("You need one free inventory slot for the supply bag.");return;}
            if(s.player.getInventory().add(loan(27314,"Supplies"))!=1)return;}
        for(int[] item:PACKS[choice])for(int count=0;count<item[1];count++)stored.add(loan(item[0],kind(item[0]).name));
        claimed.add(s.pathsCompleted()>=4?4:2);
        s.player.sendMessage("Your chosen supplies are in the bag. Open it to withdraw them.");openBag();
    }
    void openBag(){
        if(!live()||bag()==null)return;
        s.player.dialogue(new OptionsDialogue("Supply bag: "+stored.size()+" / 28",
                new Option("Choose supplies to withdraw",()->chooseWithdrawal(0)),
                new Option("Withdraw all that fit",()->withdraw(null,28)),
                new Option("Store carried Tombs supplies",this::storeCarried),
                new Option("View contents",()->{List<String> lines=new ArrayList<>();for(Kind k:Kind.values())lines.add(k.name+": "+count(k));s.player.sendScroll("Supply bag",lines.toArray(new String[0]));}),new Option("Close")));
    }
    private int count(Kind kind){int count=0;for(Item item:stored)if(kind(item.getAttributeInt(SOURCE))==kind)count++;return count;}
    private void chooseWithdrawal(int offset){
        if(!live()||bag()==null)return;List<Option> options=new ArrayList<>();Kind[] kinds=Kind.values();
        for(int i=offset;i<Math.min(offset+3,kinds.length);i++){Kind k=kinds[i];options.add(new Option(k.name+" ("+count(k)+")",()->withdraw(k,28)));}
        if(offset+3<kinds.length)options.add(new Option("Next supplies",()->chooseWithdrawal(offset+3)));
        options.add(new Option("Back",this::openBag));s.player.dialogue(new OptionsDialogue("Withdraw supplies",options));
    }
    private void withdraw(Kind filter,int maximum){
        if(!live()||bag()==null||s.player.isLocked())return;int added=0;
        Iterator<Item> items=stored.iterator();while(items.hasNext()&&added<maximum&&s.player.getInventory().getFreeSlots()>0){Item item=items.next();if(filter!=null&&kind(item.getAttributeInt(SOURCE))!=filter)continue;
            if(s.player.getInventory().add(item.copy())!=1)break;items.remove();added++;}
        s.player.sendMessage(added>0?"You withdraw "+added+" supply items.":"No matching supplies fit in your inventory.");
    }
    private void storeCarried(){
        if(!live()||bag()==null||s.player.isLocked())return;int added=0;
        for(Item item:s.player.getInventory().getItems())if(stored.size()<28&&owns(item)&&item.getAttributeInt(SOURCE)!=27314){stored.add(item.copy());item.remove();added++;}
        s.player.sendMessage("You store "+added+" supply items in the bag.");
    }
    private void consume(Item item,Kind kind,int dose){
        Player p=s.player;if(!owns(item)||p.isLocked()||p.isStunned()||p.getCombat().isDead()||p.potDelay.isDelayed()||p.karamDelay.isDelayed())return;
        if(kind==Kind.SILK&&p.eatDelay.isDelayed())return;
        if(dose+1<kind.doses.length){int id=kind.nativeId(dose+1);if(id<0)return;item.setId(id);item.putAttribute(SOURCE,kind.doses[dose+1]);}
        else item.remove();
        p.potDelay.delay(3);Consumable.animEat(p);
        switch(kind){
            case NECTAR:ambrosiaUsed=true;markEffect(2);p.getStats().get(StatType.Hitpoints).boost(3,.15);for(StatType type:COMBAT){Stat stat=p.getStats().get(type);stat.drain(5+(int)(stat.fixedLevel*.05));}p.sendMessage("You drink some nectar. It was not made for mortals!");break;
            case TEARS:for(StatType type:StatType.values())if(type!=StatType.Hitpoints&&type!=StatType.Prayer){Stat stat=p.getStats().get(type);if(stat.currentLevel<stat.fixedLevel)stat.restore(3,.25);}p.getStats().get(StatType.Prayer).restore(p.getStats().get(StatType.Prayer).fixedLevel/4+10);break;
            case AMBROSIA:ambrosiaUsed=true;markEffect(2);p.getMovement().restoreEnergy(100);p.curePoison(1200);p.cureVenom(1200);p.setHp(p.getMaxHp()+(int)(p.getMaxHp()*.25)+2);p.getStats().get(StatType.Prayer).boost(5,.20);p.sendMessage("The ambrosia reinvigorates you.");break;
            case SALTS:saltsUsed=true;markEffect(1);salts=800;applySalts();p.sendMessage("You crush the salts. Your heart rate increases.");break;
            case ADRENALINE:adrenaline=250;p.sendMessage("Your special attacks use half their usual energy for150 seconds.");break;
            case SILK:silk=100;p.eatDelay.delay(3);p.getCombat().delayAttack(3);p.sendMessage("The silk dressing begins to heal your wounds.");break;
            case SCARAB:scarab=36;p.sendMessage("The blessed crystal scarab begins restoring your prayer.");break;
        }
    }
    private void applySalts(){for(StatType type:COMBAT){Stat stat=s.player.getStats().get(type);stat.alter(Math.min(stat.currentLevel,stat.fixedLevel)+11+(int)(stat.fixedLevel*.16));}}
    private void markEffect(int bit){if(s.player.toaProgress==null)s.player.toaProgress=new TombsOfAmascutRaid.Progress();s.player.toaProgress.supplyEffects|=bit;}
    void tick(){
        if(!live())return;
        if(salts>0){salts--;if(salts>0&&salts%25==0)applySalts();if(salts==0)s.player.sendMessage("The smelling salts stop refreshing your combat boosts.");}
        if(adrenaline>0&&--adrenaline==0)s.player.sendMessage("Your special attack energy costs have returned to normal.");
        if(silk>0&&--silk%5==0)s.player.incrementHp(5);
        if(scarab>0&&--scarab%4==0)s.player.getStats().get(StatType.Prayer).restore(8);
    }
    /** Costs use the host's existing 1000-unit special-energy scale. */
    public static int specialCost(Player p,int nativeCost){ToaSupplies supplies=OWNED.get(p);return nativeCost>0&&supplies!=null&&supplies.live()&&supplies.adrenaline>0?Math.max(1,(nativeCost+1)/2):nativeCost;}
    void died(){salts=adrenaline=silk=scarab=0;saltsUsed=ambrosiaUsed=false;if(s.player.toaProgress!=null)s.player.toaProgress.supplyEffects=0;}
    void close(){
        if(closed)return;closed=true;OWNED.remove(s.player,this);stored.clear();
        for(Item item:s.player.getInventory().getItems())if(item!=null&&token.equals(item.getAttributeString(LOAN,null)))item.remove();
        if(saltsUsed)for(StatType type:COMBAT){Stat stat=s.player.getStats().get(type);if(stat.currentLevel>stat.fixedLevel)stat.restore();}
        if(ambrosiaUsed){for(StatType type:new StatType[]{StatType.Hitpoints,StatType.Prayer}){Stat stat=s.player.getStats().get(type);if(stat.currentLevel>stat.fixedLevel)stat.restore();}}
        died();
    }
    static void recoverLoans(Player p){
        boolean found=p.toaProgress!=null&&p.toaProgress.supplyEffects!=0;for(Item item:p.getInventory().getItems())if(item!=null&&item.copyOfAttributes().containsKey(LOAN)){found=true;item.remove();}
        if(found)for(StatType type:new StatType[]{StatType.Attack,StatType.Strength,StatType.Defence,StatType.Ranged,StatType.Magic,StatType.Hitpoints,StatType.Prayer}){Stat stat=p.getStats().get(type);if(stat.currentLevel>stat.fixedLevel)stat.restore();}
        if(p.toaProgress!=null)p.toaProgress.supplyEffects=0;
    }
}
