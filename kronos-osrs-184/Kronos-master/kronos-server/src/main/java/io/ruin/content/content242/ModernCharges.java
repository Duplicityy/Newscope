package io.ruin.content.content242;

import io.ruin.cache.ItemDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.ItemContainer;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.actions.ItemItemAction;
import java.util.*;

/** Charges live in native serialized item attributes; payments/refunds never merge attributed stacks. */
public final class ModernCharges {
    public static final String KEY="content242-charges";
    public static final int MAX=20000;
    private ModernCharges(){}
    public static int get(Item item,int initial){return Math.max(0,Math.min(MAX,item.getAttributeInt(KEY,initial)));}
    public static void set(Item item,int count){if(count<0||count>MAX)throw new IllegalArgumentException("Charge range");item.putAttribute(KEY,count);item.update();}
    public static boolean carried(Player p,Item item){
        if(item==null||item.getAmount()!=1)return false;
        for(Item held:p.getInventory().getItems())if(held==item)return true;
        for(Item held:p.getEquipment().getItems())if(held==item)return true;
        return false;
    }
    public static int plainAmount(ItemContainer bag,int id){long amount=0;for(Item i:bag.getItems())if(i!=null&&i.getId()==id&&!i.hasAttributes())amount+=i.getAmount();return (int)Math.min(Integer.MAX_VALUE,amount);}
    private static void takePlain(ItemContainer bag,int id,int amount){
        for(Item i:bag.getItems())if(amount>0&&i!=null&&i.getId()==id&&!i.hasAttributes()){int take=Math.min(amount,i.getAmount());i.incrementAmount(-take);amount-=take;}
        if(amount!=0)throw new IllegalStateException("Previously validated charge payment changed");
    }
    /** No yields: all resources and capacity validated before any mutation. */
    public static int pay(ItemContainer bag,Item weapon,int initial,int... costs){
        int current=get(weapon,initial),count=MAX-current;
        for(int i=0;i<costs.length;i+=2)count=Math.min(count,plainAmount(bag,costs[i])/costs[i+1]);
        if(count<=0)return 0;
        for(int i=0;i<costs.length;i+=2)takePlain(bag,costs[i],count*costs[i+1]);
        set(weapon,current+count);return count;
    }
    public static boolean refund(ItemContainer bag,Item weapon,int... costs){
        int charges=get(weapon,0);if(charges<=0)return true;
        int slots=0;List<Item> stacks=new ArrayList<>();
        for(int i=0;i<costs.length;i+=2){Item match=null;for(Item item:bag.getItems())if(item!=null&&item.getId()==costs[i]&&!item.hasAttributes()){match=item;break;}
            if(match==null)slots++;else if((long)match.getAmount()+(long)charges*costs[i+1]>Integer.MAX_VALUE)return false;stacks.add(match);}
        if(bag.getFreeSlots()<slots)return false;
        for(int i=0;i<costs.length;i+=2){Item match=stacks.get(i/2);int amount=charges*costs[i+1];if(match!=null)match.incrementAmount(amount);else bag.set(bag.freeSlot(),new Item(costs[i],amount));}
        set(weapon,0);return true;
    }
    public static int item(int source){return Content242Registry.current().resolve("items",source);}
    public static boolean definitions(int... sources){for(int source:sources){int id=item(source);if(id<0||ItemDef.get(id)==null)return false;}return true;}
    private static void refresh(Player p){p.getEquipment().sendUpdates();}
    public static void initializeShadow(){
        if(!ModernGear.feature("shadow")||!definitions(27275,27277,562,566))return;
        int active=item(27275),empty=item(27277),chaos=item(562),soul=item(566);
        for(int id:new int[]{active,empty}){
            for(int rune:new int[]{chaos,soul})ItemItemAction.register(id,rune,(p,staff,used)->{
                if(!carried(p,staff)||used.hasAttributes())return;
                int added=pay(p.getInventory(),staff,0,soul,2,chaos,5);
                if(added>0){staff.setId(active);refresh(p);p.sendMessage("You add "+added+" charges to your Tumeken's shadow.");}
                else p.sendMessage("Each charge requires two soul runes and five chaos runes, up to 20,000 charges.");
            });
            ItemAction check=(p,staff)->p.sendMessage("Your Tumeken's shadow has "+get(staff,0)+" charges remaining.");
            ItemAction.registerInventory(id,"check",check);if(ItemDef.get(id).attributes!=null)ItemAction.registerEquipment(id,"check",check);
        }
        ItemAction.registerInventory(active,"uncharge",(p,staff)->{
            if(!carried(p,staff)||staff.getId()!=active)return;
            if(!refund(p.getInventory(),staff,soul,2,chaos,5)){p.sendMessage("You need enough inventory space for the returned runes.");return;}
            staff.setId(empty);refresh(p);p.sendMessage("You uncharge your Tumeken's shadow.");
        });
    }
    public static void initializeCrystalPair(int activeSource,int emptySource,String feature,int initial){
        if(!ModernGear.feature(feature)||!definitions(activeSource,emptySource,23962))return;
        int active=item(activeSource),empty=item(emptySource),shard=item(23962);
        for(int id:new int[]{active,empty}){
            ItemItemAction.register(id,shard,(p,gear,used)->{
                if(!carried(p,gear)||used.hasAttributes())return;
                int current=get(gear,gear.getId()==active?initial:0),count=Math.min((MAX-current+99)/100,plainAmount(p.getInventory(),shard));
                if(count<=0){p.sendMessage("No crystal shards can be added.");return;}
                takePlain(p.getInventory(),shard,count);set(gear,Math.min(MAX,current+count*100));gear.setId(active);refresh(p);
                p.sendMessage("Your "+gear.getDef().name+" now has "+get(gear,0)+" charges.");
            });
            ItemAction check=(p,gear)->p.sendMessage("Your "+gear.getDef().name+" has "+get(gear,gear.getId()==active?initial:0)+" charges remaining.");
            ItemAction.registerInventory(id,"check",check);if(ItemDef.get(id).attributes!=null)ItemAction.registerEquipment(id,"check",check);
        }
    }
}
