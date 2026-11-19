package io.ruin.model.activities.raids.toa.port;

import io.ruin.cache.ItemDef;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import java.util.*;

/** Native whole-player atomic save is the commit barrier for earned loot and partial withdrawals. */
final class ToaRewardClaims {
    private static final Set<Player> BUSY=Collections.newSetFromMap(new IdentityHashMap<Player,Boolean>());
    private ToaRewardClaims(){}
    static boolean busy(Player p){return BUSY.contains(p)||PlayerFile.isSaveQuarantined(p);}
    static boolean hasRewards(Player p){return p.toaProgress!=null&&p.toaProgress.pendingRewards!=null&&p.toaProgress.pendingRewards.length>0;}
    private static TombsOfAmascutRaid.Progress copy(TombsOfAmascutRaid.Progress old){
        TombsOfAmascutRaid.Progress next=new TombsOfAmascutRaid.Progress();
        if(old!=null){next.completions=old.completions;next.claimToken=old.claimToken;next.pendingRewards=copy(old.pendingRewards);next.supplyEffects=old.supplyEffects;}return next;
    }
    private static Item[] copy(Item[] items){if(items==null)return null;Item[] out=new Item[items.length];for(int i=0;i<items.length;i++)if(items[i]!=null)out[i]=items[i].copy();return out;}
    static void record(Player p,Item[] earned,Runnable done){
        if(busy(p))return;
        if(hasRewards(p)){p.sendMessage("Collect your existing Tombs rewards before recording another completion.");return;}
        TombsOfAmascutRaid.Progress previous=p.toaProgress,next=copy(previous);
        next.completions++;next.pendingRewards=copy(earned);next.claimToken=UUID.randomUUID().toString();
        p.toaProgress=next;
        persist(p,()->p.toaProgress=previous,done);
    }
    static void open(Player p){
        if(busy(p))return;
        if(!hasRewards(p)){p.sendMessage("You have no unclaimed Tombs rewards.");leaveOption(p);return;}
        List<Option> options=new ArrayList<>();
        options.add(new Option("Collect rewards (noted when possible)",()->claim(p)));
        options.add(new Option("View earned rewards",()->{
            List<String> lines=new ArrayList<>();for(Item item:p.toaProgress.pendingRewards)if(item!=null&&item.getAmount()>0){ItemDef d=ItemDef.get(item.getId());lines.add(item.getAmount()+" x "+(d==null?"item "+item.getId():d.name));}
            p.sendScroll("Unclaimed Tombs rewards",lines.toArray(new String[0]));
        }));
        if(TombsOfAmascutRaid.isInRaid(p))options.add(new Option("Leave safely; keep unclaimed rewards",()->TombsOfAmascutRaid.leave(p)));
        options.add(new Option("Close"));
        p.dialogue(new OptionsDialogue("Tombs rewards - saved until collected",options));
    }
    private static void leaveOption(Player p){if(TombsOfAmascutRaid.isInRaid(p))p.dialogue(new OptionsDialogue("Tombs complete",new Option("Leave safely",()->TombsOfAmascutRaid.leave(p)),new Option("Stay")));}
    private static int withdrawalId(int id){
        ItemDef base=ItemDef.get(id);if(base==null)throw new IllegalStateException("Saved reward definition missing: "+id);
        if(!base.stackable&&!base.isNote()&&base.notedId>=0){ItemDef note=ItemDef.get(base.notedId);if(note!=null&&note.isNote()&&note.notedId==id)return note.id;}
        return id;
    }
    static void claim(Player p){
        if(busy(p)||!hasRewards(p)||p.isLocked()||!p.isOnline()||p.getCombat().isDead()||p.wildernessLevel>0||p.pvpAttackZone)return;
        final TombsOfAmascutRaid.Progress previous=p.toaProgress,next=copy(previous);
        final Item[] inventory=copy(p.getInventory().getItems());
        List<Item> remaining=new ArrayList<>();int moved=0;
        p.lock(LockType.FULL_NULLIFY_DAMAGE);
        try{
            for(Item reward:previous.pendingRewards){
                if(reward==null||reward.getAmount()<=0)continue;
                int id=reward.copyOfAttributes().isEmpty()?withdrawalId(reward.getId()):reward.getId();
                int quantity=Math.min(reward.getAmount(),Math.max(0,p.getInventory().getCapacityFor(id)));
                int added=quantity==0?0:p.getInventory().add(id,quantity,reward.copyOfAttributes());
                if(added<0||added>quantity)throw new IllegalStateException("Invalid reward transfer amount");
                moved+=added;
                if(added<reward.getAmount())remaining.add(new Item(reward.getId(),reward.getAmount()-added,reward.copyOfAttributes()));
            }
        }catch(RuntimeException failure){restoreInventory(p,inventory);p.unlock();io.ruin.Server.logError("ToA reward claim prepare",failure);p.sendMessage("The reward transfer was cancelled safely.");return;}
        if(moved==0){p.unlock();p.sendMessage("Make room in your inventory. Your rewards remain saved.");return;}
        next.pendingRewards=remaining.toArray(new Item[0]);p.toaProgress=next;
        persist(p,()->{restoreInventory(p,inventory);p.toaProgress=previous;},()->{
            p.sendMessage(hasRewards(p)?"Some rewards were collected. Make room for the remaining saved rewards.":"All Tombs rewards collected.");leaveOption(p);
        });
    }
    private static void restoreInventory(Player p,Item[] before){p.getInventory().clear();for(int i=0;i<before.length;i++)if(before[i]!=null)p.getInventory().set(i,before[i].copy());}
    private static void persist(Player p,Runnable rollback,Runnable committed){
        if(!BUSY.add(p))throw new IllegalStateException("Duplicate ToA reward transaction");
        p.lock(LockType.FULL_NULLIFY_DAMAGE);
        try{PlayerFile.saveDurably(p);BUSY.remove(p);p.unlock();committed.run();}
        catch(PlayerFile.DurableSaveException failed){
            if(!failed.uncertain){rollback.run();BUSY.remove(p);p.unlock();p.sendMessage("Your Tombs save did not commit. Nothing was transferred; please retry.");io.ruin.Server.logError("ToA reward durable save",failed);return;}
            // A timed-out write may still commit. Keep the account quarantined until its exact future resolves.
            World.startEvent(event->{
                PlayerFile.SaveResolution result;
                do{event.delay(1);result=PlayerFile.resolveDurableSave(p);}while(result==PlayerFile.SaveResolution.PENDING);
                if(result==PlayerFile.SaveResolution.UNKNOWN){io.ruin.Server.logError("ToA reward outcome needs operator verification",failed);return;}
                BUSY.remove(p);
                if(result==PlayerFile.SaveResolution.NOT_COMMITTED){rollback.run();p.unlock();p.sendMessage("Your Tombs save did not commit. Please retry.");}
                else{p.unlock();committed.run();}
            });
        }
    }
}
