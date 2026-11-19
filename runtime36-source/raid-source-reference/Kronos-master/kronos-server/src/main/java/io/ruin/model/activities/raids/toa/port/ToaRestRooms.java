package io.ruin.model.activities.raids.toa.port;

import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import java.util.*;

/** The donor's actual main hall and burial tomb; controls are scoped to each copied object. */
final class ToaRestRooms implements ToaEncounter {
    private final TombsOfAmascutRaid.Session s;
    private boolean greeted,closed;
    private NPC spirit;
    private ToaRestRooms(TombsOfAmascutRaid.Session s){this.s=s;}
    static void install(){
        for(ToaRoom room:new ToaRoom[]{ToaRoom.MAIN_HALL,ToaRoom.REWARDS})TombsOfAmascutRaid.register(room,new TombsOfAmascutRaid.Factory(){
            public List<String> missingAssets(){return NearRealityRaidAssets.region(room.sourceRegion)>=0?Collections.emptyList():Collections.singletonList("source region "+room.sourceRegion);}
            public ToaEncounter create(TombsOfAmascutRaid.Session s){return new ToaRestRooms(s);}
        });
    }
    public void spawn(){
        if(s.room==ToaRoom.MAIN_HALL){
            bind(3562,5146,()->s.choosePath(ToaRoom.APMEKEN));
            bind(3559,5155,()->s.choosePath(ToaRoom.SCABARAS));
            bind(3539,5146,()->s.choosePath(ToaRoom.HET));
            bind(3541,5155,()->s.choosePath(ToaRoom.CRONDIS));
            bind(3548,5134,s::enterWardens);
            if(s.supplies.available()){
                int id=NearRealityRaidAssets.npc(11694,"Helpful spirit");
                if(id<0)throw new IllegalStateException("Missing actual helpful spirit");
                spirit=new NPC(id).spawn(s.tile(3548,5154));spirit.sixSystemsRewardless=true;s.map.addNpc(spirit);
                if(spirit.getCombat()!=null){spirit.getCombat().setAllowRespawn(false);spirit.getCombat().setAllowRetaliate(false);spirit.getCombat().reset();}
                spirit.attackNpcListener=(p,n,m)->false;
                for(int option=1;option<=5;option++)NPCAction.register(spirit,option,(p,n)->{if(!closed&&p==s.player&&s.valid())s.supplies.openSelection();});
            }
        }else{
            Set<Integer> chests=new HashSet<>();
            for(int id:new int[]{46082,46217,44545,46215,46219,46218,44547,29994,46216,46224}){
                int nativeId=NearRealityRaidAssets.object(id);if(nativeId>=0)chests.add(nativeId);
            }
            for(Region region:s.map.getRegions())if(region!=null)for(int x=region.baseX;x<region.baseX+64;x++)for(int y=region.baseY;y<region.baseY+64;y++){
                Tile t=Tile.get(x,y,s.room.plane,false);if(t==null||t.gameObjects==null)continue;
                for(GameObject object:t.gameObjects)if(chests.contains(object.id))bind(object,()->ToaRewardClaims.open(s.player));
            }
        }
    }
    private void bind(int x,int y,Runnable action){
        Position p=s.tile(x,y);Tile tile=Tile.get(p,false);
        if(tile!=null&&tile.gameObjects!=null)for(GameObject o:tile.gameObjects)if(o.type==10&&o.id>=0)bind(o,action);
    }
    private void bind(GameObject object,Runnable action){
        for(int option=1;option<=5;option++)ObjectAction.register(object,option,(p,o)->{
            if(!closed&&p==s.player&&s.valid())action.run();
        });
    }
    public void tick(){if(!closed&&!greeted&&s.valid()){greeted=true;s.menu();}}
    public void close(){closed=true;if(spirit!=null&&!spirit.isRemoved())spirit.remove();}
}
