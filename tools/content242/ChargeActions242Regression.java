package io.ruin.content.content242;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.api.buffer.InBuffer;
import io.ruin.api.filestore.FileStore;
import io.ruin.cache.ItemDef;
import io.ruin.data.impl.items.item_info;
import io.ruin.data.impl.items.shield_types;
import io.ruin.data.impl.items.weapon_types;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.actions.ItemItemAction;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.Inventory;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reads staged definitions; detached actors exercise the actual registered item handlers.
 * Never connects, starts a world, writes staged data, or creates an account. */
public final class ChargeActions242Regression {
    private static int assertions;
    private static final int[] WEAPONS={27275,27277,25865,25862,25867};
    private static final int[] SOURCES={27275,27277,25865,25862,25867,562,566,23962};
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private static String read(Path file)throws Exception{return new String(Files.readAllBytes(file),StandardCharsets.UTF_8);}
    private static int id(int source){int target=ModernCharges.item(source);check(target>=0,"mapped source "+source);return target;}
    private static ItemDef def(int source){return ItemDef.get(id(source));}
    private static final class QuietEquipment extends Equipment {
        public boolean sendUpdates(){return true;}
    }
    private static final class Actor extends Player {
        Inventory inventory;
        Equipment equipment;
        String message;
        public Inventory getInventory(){return inventory;}
        public Equipment getEquipment(){return equipment;}
        public void sendMessage(String message){this.message=message;}
    }
    private static Actor actor()throws Exception{
        Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        Actor actor=(Actor)((sun.misc.Unsafe)field.get(null)).allocateInstance(Actor.class);
        actor.inventory=new Inventory();actor.inventory.init(28,false);
        actor.equipment=new QuietEquipment();actor.equipment.init(14,false);return actor;
    }
    private static ItemAction action(int source,String option){
        ItemDef definition=def(source);int slot=definition.getOption(option);
        check(slot>=1&&slot<=5,"visible "+source+" "+option);
        check(definition.inventoryActions!=null&&definition.inventoryActions[slot-1]!=null,"bound "+source+" "+option);
        return definition.inventoryActions[slot-1];
    }
    private static void load(Path cache,Path data)throws Exception{
        Server.dataFolder=data.toFile();Server.fileStore=new FileStore(cache.toString());
        check(ModernGear.feature("shadow")&&ModernGear.feature("bowfa"),"staged feature flags enabled");
        Method decode=ItemDef.class.getDeclaredMethod("decode",InBuffer.class);decode.setAccessible(true);
        Set<Integer> selected=new HashSet<>();
        for(int source:SOURCES){int target=id(source);selected.add(target);byte[] bytes=Server.fileStore.get(2).getFile(10,target);
            check(bytes!=null&&bytes.length>0,"staged raw item "+source);
            ItemDef definition=new ItemDef();definition.id=target;decode.invoke(definition,new InBuffer(bytes));ItemDef.cached.put(target,definition);
        }
        JsonArray rows=new JsonParser().parse(read(data.resolve("items/item_info.json"))).getAsJsonArray(),filtered=new JsonArray();
        Set<Integer> metadata=new HashSet<>();
        for(JsonElement row:rows){int target=row.getAsJsonObject().get("id").getAsInt();if(selected.contains(target)){
            check(metadata.add(target),"no duplicate metadata row "+target);filtered.add(row);
        }}
        // Native currency/material definitions can legitimately rely solely on raw cache fields.
        for(int source:WEAPONS)check(metadata.contains(id(source)),"weapon metadata present "+source);
        new weapon_types().fromJson("weapon_types.json",read(data.resolve("items/weapon_types.json")));
        shield_types.MAP=new HashMap<>();
        new item_info().fromJson("item_info.json",filtered.toString());
        for(int source:WEAPONS){ItemDef definition=def(source);
            check(definition.equipSlot==3&&definition.twoHanded,"native weapon slot and two handed "+source);
            check(definition.equipOption==definition.getOption("wield")&&definition.equipOption>0,"native equipment option "+source);
            check(definition.weaponType!=null&&definition.equipBonuses!=null&&definition.equipReqs!=null,"profile bonuses requirements "+source);
        }
        check(def(27275).equipBonuses[3]==35&&def(27277).equipBonuses[3]==35,"Shadow raw magic attack");
        check(def(25865).equipBonuses[4]==128&&def(25867).equipBonuses[4]==128,"Bowfa raw range attack");
        check(def(25862).equipBonuses[4]==0,"inactive Bowfa retains zero raw attack");
    }
    private static void cases()throws Exception{
        ModernCharges.initializeShadow();ModernCharges.initializeCrystalPair(25865,25862,"bowfa",10000);
        action(27275,"check");action(27275,"uncharge");action(25865,"check");
        for(int source:new int[]{27277,25862,25867})check(def(source).getOption("check")==-1,"no fabricated Check "+source);
        for(int source:WEAPONS)check(def(source).getOption("charge","revert")==-1,"unimplemented menu stays absent "+source);
        for(int source:new int[]{25865,25862,25867})check(def(source).getOption("uncharge")==-1,"unimplemented Bowfa Uncharge stays absent "+source);
        for(int source:new int[]{27275,25865}){
            ItemDef definition=def(source);
            // Imported184 definitions currently omit249 worn-menu parameters. Never invent them.
            boolean visible=definition.attributes!=null&&"Check".equalsIgnoreCase((String)definition.attributes.get(451));
            check(!visible||(definition.equipmentActions!=null&&definition.equipmentActions[1]!=null),"any visible worn Check is bound "+source);
        }
        // Regression reproducer: the old stripped cache has no binding even though initialize runs.
        ItemDef active=def(27275);String[] savedOptions=active.inventoryOptions;ItemAction[] savedActions=active.inventoryActions;
        active.inventoryOptions=new String[5];active.inventoryActions=null;
        check(!ItemAction.registerInventory(active.id,"check",(p,item)->{}),"stripped Check cannot silently count as ready");
        check(active.inventoryActions==null,"missing option has no hidden handler");active.inventoryOptions=savedOptions;active.inventoryActions=savedActions;

        Actor p=actor();Item staff=new Item(id(27277));p.inventory.set(0,staff);
        p.inventory.set(1,new Item(id(566),21));p.inventory.set(2,new Item(id(562),52));
        ItemItemAction.handleAction(p,p.inventory.get(1),staff);
        check(staff.getId()==id(27275)&&ModernCharges.get(staff,0)==10,"rune-on-empty staff uses actual registered pair");
        check(ModernCharges.plainAmount(p.inventory,id(566))==1&&ModernCharges.plainAmount(p.inventory,id(562))==2,"charge payment exact");
        action(27275,"check").handle(p,staff);check(p.message.contains("10 charges"),"native Check reads actual charge attribute");
        action(27275,"uncharge").handle(p,staff);
        check(staff.getId()==id(27277)&&ModernCharges.get(staff,0)==0,"native Uncharge returns empty item");
        check(ModernCharges.plainAmount(p.inventory,id(566))==21&&ModernCharges.plainAmount(p.inventory,id(562))==52,"native Uncharge refunds exactly");
        ItemItemAction.handleAction(p,staff,p.inventory.get(2));check(ModernCharges.get(staff,0)==10,"staff-on-rune reverse order works");
        for(int slot=1;slot<28;slot++)p.inventory.set(slot,new Item(id(25862)));
        action(27275,"uncharge").handle(p,staff);
        check(staff.getId()==id(27275)&&ModernCharges.get(staff,0)==10,"full inventory Uncharge keeps paid weapon intact");

        Actor q=actor();Item bow=new Item(id(25862));q.inventory.set(0,bow);q.inventory.set(1,new Item(id(23962),2));
        ItemItemAction.handleAction(q,bow,q.inventory.get(1));
        check(bow.getId()==id(25865)&&ModernCharges.get(bow,0)==200,"shards activate empty Bowfa through native pair");
        check(ModernCharges.plainAmount(q.inventory,id(23962))==0,"shards paid exactly");
        action(25865,"check").handle(q,bow);check(q.message.contains("200 charges"),"Bowfa Check reads actual charge attribute");
        ModernCharges.set(bow,19999);q.inventory.set(1,new Item(id(23962),2));ItemItemAction.handleAction(q,q.inventory.get(1),bow);
        check(ModernCharges.get(bow,0)==20000&&ModernCharges.plainAmount(q.inventory,id(23962))==1,"one shard reaches cap and remaining shard survives");
        Item detached=new Item(id(27277));int souls=ModernCharges.plainAmount(p.inventory,id(566));
        ItemItemAction.handleAction(p,detached,new Item(id(566)));check(ModernCharges.get(detached,0)==0&&ModernCharges.plainAmount(p.inventory,id(566))==souls,"detached staff cannot charge");
    }
    public static void main(String[] args){int result=1;try{
        if(args.length!=3)throw new IllegalArgumentException("cache data output-file required");
        load(Paths.get(args[0]),Paths.get(args[1]));cases();
        String report="PASS charge action regression assertions="+assertions+"; actual staged cache/metadata and native registered handlers; detached actors only, no account/server/client mutation.";
        Path output=Paths.get(args[2]);Files.createDirectories(output.getParent());Files.write(output,Arrays.asList(report),StandardCharsets.UTF_8);System.out.println(report);result=0;
    }catch(Throwable error){error.printStackTrace();}finally{System.exit(result);}}
}
