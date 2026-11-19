package io.ruin.model.activities.dungeoneering;

import io.ruin.Server;
import io.ruin.api.buffer.InBuffer;
import io.ruin.api.buffer.OutBuffer;
import io.ruin.model.activities.raids.auto.RaidAutoAccess;
import io.ruin.model.activities.raids.auto.RaidAutoActions;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import io.ruin.network.incoming.handlers.ActionButtonHandler;
import io.ruin.network.incoming.handlers.GroundItemActionHandler;
import io.ruin.network.incoming.handlers.InterfaceOnObjectHandler;
import java.util.*;

/** Normal client action paths only. No dungeon state, drops or equipment are manufactured here. */
final class IDungeonActions {
    private final Player p;
    private String last="";
    private long submitted=-100;
    private static final GroundItemActionHandler GROUND=new GroundItemActionHandler();
    private static final InterfaceOnObjectHandler.FromItem USE=new InterfaceOnObjectHandler.FromItem();
    IDungeonActions(Player p){this.p=p;}
    static boolean ready(Player p){return IDungeon.owner(p)&&p.isOnline()&&p.logoutStage==0&&p.getHp()>0&&!p.isLocked()&&!p.getCombat().isDead();}
    private boolean once(String key,int cooldown){long tick=Server.currentTick();if(key.equals(last)&&tick-submitted<cooldown)return false;last=key;submitted=tick;return true;}
    static boolean select(Player p,String prefix){
        if(!ready(p))return false;
        Option[] options=null;int widget=187,child=3,offset=0;
        if(p.optionScroll!=null&&p.isVisibleInterface(187))options=p.optionScroll.options;
        else if(p.optionsDialogue!=null&&p.isVisibleInterface(Interface.OPTIONS_DIALOGUE)){
            try{options=(Option[])RaidAutoAccess.get(p.optionsDialogue,"options");}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            widget=Interface.OPTIONS_DIALOGUE;child=1;offset=1;
        }
        if(options==null)return false;int found=-1;
        for(int i=0;i<options.length;i++)if(options[i]!=null&&options[i].name.startsWith(prefix)){if(found>=0)throw new IllegalStateException("Ambiguous native choice: "+prefix);found=i;}
        if(found<0)return false;
        ActionButtonHandler.handleAction(p,1,widget<<16|child,found+offset,-1,false);return true;
    }
    boolean walk(Position at){
        if(!ready(p)||at==null)return false;
        if(p.getPosition().equals(at))return true;
        if(!once("walk:"+at,2))return true;
        return RaidAutoActions.walk(p,at.getX(),at.getY());
    }
    boolean move(DungeoneeringFloor.RoomState r,Position target){return move(r,target,Collections.<Long>emptySet());}
    boolean move(DungeoneeringFloor.RoomState r,Position target,Set<Long> avoid){
        if(p.getPosition().equals(target))return true;
        IDungeonPath.Step next=IDungeonPath.next(r,p.getPosition(),target,0,avoid);
        if(next==null)return false;
        if(next.object!=null)return submitObject(next.object,1);
        if(avoid.isEmpty()&&!next.at.equals(target)){
            IDungeonPath.Step second=IDungeonPath.next(r,next.at,target,0,avoid);
            if(second!=null&&second.object==null)next=second;
        }
        return walk(next.at);
    }
    boolean object(DungeoneeringFloor.RoomState r,GameObject object,int option){
        if(!resident(r,object))return false;
        // Let the host's object router handle the final approach/footprint.
        if(p.getPosition().isWithinDistance(object.getPosition(),2))return submitObject(object,option);
        IDungeonPath.Step next=IDungeonPath.next(r,p.getPosition(),object.getPosition(),1,Collections.<Long>emptySet());
        if(next==null)return false;
        return advance(r,next,object.getPosition(),1);
    }
    private boolean submitObject(GameObject object,int option){
        if(!once("object:"+object.x+":"+object.y+":"+object.id+":"+option,3))return true;
        return RaidAutoActions.object(p,object,option);
    }
    boolean npc(DungeoneeringFloor.RoomState r,NPC npc,int option){
        if(npc==null||npc.isRemoved()||r.dungeon().actors.get(npc)!=r)return false;
        if(!p.getPosition().isWithinDistance(npc.getPosition(),2)){
            IDungeonPath.Step next=IDungeonPath.next(r,p.getPosition(),npc.getPosition(),1,Collections.<Long>emptySet());
            if(next==null)return false;
            return advance(r,next,npc.getPosition(),1);
        }
        if(!once("npc:"+npc.getIndex()+":"+option,3))return true;
        return RaidAutoActions.npc(p,npc,option,true);
    }
    boolean attack(NPC npc){
        if(p.getCombat().getTarget()==npc)return true;
        int option=npc.getDef().attackOption;
        if(option<1)throw new IllegalStateException("No normal Attack option for "+npc.getDef().name);
        return RaidAutoActions.npc(p,npc,option,true);
    }
    boolean pickup(DungeoneeringFloor.RoomState r,GroundItem item){
        if(!ready(p)||item==null||item.isRemoved()||item.id<0||item.id>=65535||item.z!=p.getHeight()
                ||r.dungeon().room(new Position(item.x,item.y,item.z))!=r)return false;
        Tile tile=Tile.get(item.x,item.y,item.z,false);
        if(tile==null||tile.getPickupItem(item.id,p.getUserId())!=item)return false;
        if(!p.getPosition().isWithinDistance(new Position(item.x,item.y,item.z),1)){
            IDungeonPath.Step step=IDungeonPath.next(r,p.getPosition(),new Position(item.x,item.y,item.z),0,Collections.<Long>emptySet());
            if(step==null)step=IDungeonPath.next(r,p.getPosition(),new Position(item.x,item.y,item.z),1,Collections.<Long>emptySet());
            if(step==null)return false;
            return advance(r,step,new Position(item.x,item.y,item.z),1);
        }
        int option=io.ruin.cache.ItemDef.get(item.id).pickupOption;
        if(option<1||option>5)throw new IllegalStateException("Ground item has no native Take option");
        if(!once("pickup:"+item.id+":"+item.x+":"+item.y,3))return true;
        OutBuffer b=new OutBuffer(7);int opcode;
        switch(option){
            case 1:opcode=97;b.addByteS(1);b.addLEShort(item.y);b.addShortA(item.x);b.addLEShortA(item.id);break;
            case 2:opcode=27;b.addByteA(1);b.addShortA(item.x);b.addShortA(item.id);b.addShortA(item.y);break;
            case 3:opcode=18;b.addByteC(1);b.addLEShortA(item.x);b.addShortA(item.y);b.addLEShort(item.id);break;
            case 4:opcode=63;b.addLEShortA(item.x);b.addShortA(item.y);b.addByteS(1);b.addShort(item.id);break;
            default:opcode=24;b.addLEShort(item.x);b.addLEShort(item.y);b.addLEShortA(item.id);b.addByteS(1);break;
        }
        GROUND.handle(p,new InBuffer(b.toByteArray()),opcode);return true;
    }
    boolean use(DungeoneeringFloor.RoomState r,Item item,GameObject object){
        if(!ready(p)||!resident(r,object)||item==null||item.getContainer()!=p.getInventory()
                ||p.getInventory().get(item.getSlot())!=item||item.getId()<0||item.getId()>=65535)return false;
        if(!p.getPosition().isWithinDistance(object.getPosition(),2))return approach(r,object.getPosition());
        if(!once("use:"+item.getId()+":"+object.x+":"+object.y+":"+object.id,3))return true;
        OutBuffer b=new OutBuffer(17);b.addLEShort(object.x);b.addShort(object.y);b.addShortA(item.getId());
        b.addInt1(Interface.INVENTORY<<16);b.addShort(item.getSlot());b.addLEInt(object.id);b.addByteC(1);
        USE.handle(p,new InBuffer(b.toByteArray()),46);return true;
    }
    boolean approach(DungeoneeringFloor.RoomState r,Position target){
        IDungeonPath.Step step=IDungeonPath.next(r,p.getPosition(),target,1,Collections.<Long>emptySet());
        if(step==null)return false;return advance(r,step,target,1);
    }
    private boolean advance(DungeoneeringFloor.RoomState r,IDungeonPath.Step step,Position target,int radius){
        if(step.object!=null)return submitObject(step.object,1);
        if(!step.at.isWithinDistance(target,radius)){
            IDungeonPath.Step second=IDungeonPath.next(r,step.at,target,radius,Collections.<Long>emptySet());
            if(second!=null&&second.object==null)step=second;
        }
        return walk(step.at);
    }
    static boolean resident(DungeoneeringFloor.RoomState r,GameObject o){return o!=null&&o.id>=0&&o.id<65535&&r.dungeon().room(o.getPosition())==r&&Tile.getObject(o.id,o.x,o.y,o.z)==o;}
    static List<GroundItem> ground(DungeoneeringFloor.RoomState r){
        // Includes native combat drops as well as the floor's own table/key items.
        List<GroundItem> result=new ArrayList<>();Set<GroundItem> seen=Collections.newSetFromMap(new IdentityHashMap<GroundItem,Boolean>());
        for(int x=0;x<16;x++)for(int y=0;y<16;y++){
            Position at=r.unrotatedTile(x,y);Tile tile=Tile.get(at);
            if(tile==null)continue;
            if(tile.groundItems!=null)for(GroundItem g:tile.groundItems)if(!g.isRemoved()&&seen.add(g))result.add(g);
        }
        return result;
    }
}
