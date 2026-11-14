package io.ruin.content.content242.bosses;

import io.ruin.Server;
import io.ruin.content.content242.ModernBurn;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.*;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import java.util.*;

/** Encounter-owned hazards; pending impacts are cancelled on reset/death/instance disposal. */
abstract class Boss242Combat extends NPCCombat implements ModernBurn.StatusTarget {
    protected boolean ready;
    protected final List<NPC> children=new ArrayList<>();
    protected final List<GameObject> scenery=new ArrayList<>();
    private final List<Due> pending=new ArrayList<>();
    private final Map<Hit,Integer> launched=new WeakHashMap<>();
    private int epoch;
    private Player owner;
    protected long tick;
    protected int dx,dy,plane,lease;
    private static final class Due {final long at;final Runnable action;Due(long at,Runnable action){this.at=at;this.action=action;}}
    protected void begin(boolean valid,int sourceX,int sourceY){
        ready=valid;dx=npc.spawnPosition.getX()-sourceX;dy=npc.spawnPosition.getY()-sourceY;plane=npc.getHeight();lease=npc.getPosition().getRegion().dynamicIndex;
        if(!ready){npc.setHidden(true);return;}
        if(npc.hitListener==null)npc.hitListener=new HitListener();
        npc.attackNpcListener=(player,actor,message)->valid(player);
        npc.hitListener.preDefend(this::guardIncoming);
        npc.hitListener.preDamage(this::guardIncoming);
        npc.hitListener.preTargetDefend((hit,victim)->launched.put(hit,epoch));
        npc.hitListener.preTargetDamage((hit,victim)->{Integer version=launched.remove(hit);if(version==null||version!=epoch||npc.isRemoved()||isDead()||!valid(victim.player))hit.damage=0;});
        npc.addEvent(event->{while(!npc.isRemoved()){event.delay(1);tick++;if(!isDead()&&npc.getPosition().getRegion().dynamicIndex==lease){
            List<Due> due=new ArrayList<>();for(Due d:pending)if(d.at<=tick)due.add(d);pending.removeAll(due);for(Due d:due)if(!isDead())d.action.run();pulse();
        }}});
    }
    protected void pulse(){}
    protected void after(int ticks,Runnable action){pending.add(new Due(tick+Math.max(1,ticks),action));}
    protected Position tile(int sourceX,int sourceY){return new Position(sourceX+dx,sourceY+dy,plane);}
    protected abstract boolean arena(Position p);
    final void bindOwner(Player player){if(owner!=null&&owner!=player)throw new IllegalStateException("Encounter already owned");owner=player;}
    protected boolean valid(Player p){return p!=null&&p==owner&&p.isOnline()&&p.getHp()>0&&p.getHeight()==plane&&p.getPosition().getRegion().dynamicIndex==lease&&arena(p.getPosition());}
    final void guardIncoming(Hit hit){
        if(hit.type==HitType.HEAL&&hit.attacker==null)return;
        if(hit instanceof ModernBurn.BurnHit&&allowsBurn(owner)&&((ModernBurn.BurnHit)hit).validForOwner(owner))return;
        if(npc.isRemoved()||isDead()||hit.attacker==null||!valid(hit.attacker.player))reject(hit);
    }
    @Override public boolean allowsBurn(Player source){return ready&&!isDead()&&!npc.isRemoved()&&valid(source);}
    @Override public int beforeBurn(int damage){return allowsBurn(owner)?Math.max(0,damage):0;}
    static void reject(Hit hit){hit.block().nullify().setResetActions(false);hit.attacker=null;}
    protected List<Player> players(){List<Player> list=new ArrayList<>();for(Player p:npc.localPlayers())if(valid(p))list.add(p);return list;}
    protected int animation(int source){return Boss242Assets.id("animations",source);}
    protected int graphic(int source){return Boss242Assets.id("graphics",source);}
    protected void tileGraphic(int source,Position pos,int delay){for(Player p:players())p.getPacketSender().sendGraphics(graphic(source),0,delay,pos.getX(),pos.getY(),pos.getZ());}
    protected boolean free(Position p){Tile t=Tile.get(p.getX(),p.getY(),p.getZ(),false);return arena(p)&&(t==null||t.isFloorFree());}
    @Override public boolean canAttack(Entity target){return ready&&target!=null&&target.player!=null&&valid(target.player)&&super.canAttack(target);}
    public void close(){epoch++;launched.clear();pending.clear();for(NPC child:new ArrayList<>(children))if(!child.isRemoved())child.remove();children.clear();for(GameObject object:scenery)object.remove();scenery.clear();}
    @Override public void reset(){close();super.reset();}
    @Override public void startDeath(Hit hit){close();super.startDeath(hit);}
    @Override public int getAttackBoundsRange(){return 28;}
}
