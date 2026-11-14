package io.ruin.model.activities.hollowdepths;

import com.newscape.dungeons.*;
import com.newscape.dungeons.DungeonLayout.Tile;
import com.newscape.dungeons.DungeonPorts.*;
import io.ruin.Server;
import io.ruin.cache.ObjectID;
import io.ruin.model.World;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.entity.shared.StepType;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.stat.StatType;
import java.util.*;

/** Concrete map, movement, combat, interaction and persistence adapter for the existing 184 engine. */
public final class HollowDepthsGamePort implements GamePort {
    static final Position LOBBY = new Position(2043, 3576, 0);
    static final Position PORTAL = new Position(2044, 3576, 0);
    final HollowDepthsAssets assets;
    final HollowDepthsRewards rewards;
    final Map<String, HollowDepthsMap> maps = new LinkedHashMap<>();
    final Map<String, Player> players = new HashMap<>();
    final Map<Player, String> accounts = new WeakHashMap<>();
    final Map<NPC, Actor> actors = new WeakHashMap<>();
    final Map<Hit, HazardHit> hazardHits = new WeakHashMap<>();
    final Map<Player, Motion> motions = new IdentityHashMap<>();
    final Map<Player, LockType> motionLocks = new IdentityHashMap<>();
    final Map<Player, LockType> freezeLocks = new IdentityHashMap<>();
    final Map<String, Warning> telegraphs = new HashMap<>();
    private final Set<String> boundMaps = new HashSet<>();
    private final ContentCatalog catalog = new ContentCatalog();

    static final class Actor {
        final NPC npc; final NpcTicket ticket; final HollowDepthsMap map;
        boolean retired;
        Actor(NPC npc, NpcTicket ticket, HollowDepthsMap map) { this.npc=npc; this.ticket=ticket; this.map=map; }
    }
    static final class HazardHit {
        final UUID runId; final String account;
        HazardHit(UUID runId, String account) { this.runId=runId; this.account=account; }
    }
    static final class Warning {
        final HollowDepthsMap map; final List<Tile> tiles; final long due;
        Warning(HollowDepthsMap map,List<Tile> tiles,long due) { this.map=map; this.tiles=new ArrayList<>(tiles); this.due=due; }
    }
    public HollowDepthsGamePort(HollowDepthsAssets assets, HollowDepthsRewards rewards) { this.assets=assets; this.rewards=rewards; }
    @Override public void assertWorldThread() {
        if (Thread.currentThread() != HollowDepths.worldThread) throw new IllegalStateException("Hollow Depths world-thread ownership violation");
    }
    String account(Player player) {
        String id = accounts.get(player);
        if (id == null && !(player instanceof io.ruin.model.entity.player.autonomous.Adventurer)) {
            try { id=HollowDepthsRewards.accountId(player); } catch (RuntimeException missing) { return null; }
            if (id != null) accounts.put(player,id);
        }
        return id;
    }
    void register(Player player) {
        String id=account(player);
        if(id!=null) {
            Player previous=players.put(id,player);
            if(previous!=null&&previous!=player) { motions.remove(previous); motionLocks.remove(previous); freezeLocks.remove(previous); }
        }
    }
    Player requirePlayer(String account) {
        Player player=players.get(account);
        if(player==null) throw new IllegalStateException("Missing authoritative player " + account);
        return player;
    }
    HollowDepthsMap mapAt(Position position) {
        if(position==null) return null;
        for(HollowDepthsMap map:maps.values()) {
            Tile local=map.local(position);
            if(local!=null) return map;
        }
        return null;
    }
    HollowDepthsMap requireMap(Instance instance) {
        HollowDepthsMap map=maps.get(instance.id);
        if(map==null || map.identity!=instance) throw new IllegalStateException("Stale dungeon lease");
        return map;
    }
    @Override public PlayerView player(String account) {
        Player p=players.get(account); if(p==null) return null;
        HollowDepthsMap map=mapAt(p.getPosition());
        return new PlayerView(account,map==null?null:map.identity.id,map==null?null:map.local(p.getPosition()),p.getHeight(),
            CombatUtils.getCombatLevel(p),p.getStats().get(StatType.Agility).fixedLevel,p.getStats().get(StatType.Agility).currentLevel,
            Math.max(0,p.getHp()),Math.max(p.getMaxHp(),p.getHp()),p.isOnline(),p.isLocked()||PlayerFile.isSaveQuarantined(p));
    }
    @Override public String entryDenial(String account, Rewards.Tier tier) {
        Player p=players.get(account);
        if(p==null||!p.isOnline()) return "Please sign in again before entering.";
        if(p.getHp()<=0||p.getCombat().isDead()||p.isLocked()||PlayerFile.isSaveQuarantined(p)) return "Finish your current action before entering.";
        if(p.getCombat().isDefending(17)||p.getCombat().getTarget()!=null) return "Leave combat before entering.";
        if(p.pet!=null) return "Please pick up your follower before entering this solo dungeon.";
        GameObject portal=io.ruin.model.map.Tile.getObject(ObjectID.KRONOS_TELEPORTER,PORTAL.getX(),PORTAL.getY(),0);
        if(portal==null||p.getHeight()!=0||Math.max(Math.abs(p.getAbsX()-PORTAL.getX()),Math.abs(p.getAbsY()-PORTAL.getY()))>6)
            return "Stand beside the red home portal to enter Hollow Depths.";
        return null;
    }
    @Override public String deploymentDenial() {
        if(HollowDepths.faulted) return "Hollow Depths is in maintenance; current runs are being returned safely.";
        return assets==null||assets.deploymentDenial()!=null ? "Hollow Depths is temporarily unavailable while its maps are being verified." : null;
    }
    @Override public Instance reserve(UUID runId,String account,DungeonLayout layout) {
        assertWorldThread(); Instance identity=new Instance("hollow:"+runId,runId);
        HollowDepthsMap map=new HollowDepthsMap(identity,account,layout,assets);
        maps.put(identity.id,map); return identity;
    }
    @Override public boolean buildStep(Instance instance,DungeonLayout layout,int budget) {
        HollowDepthsMap map=requireMap(instance);
        boolean finished=map.step(budget);
        if(finished&&boundMaps.add(instance.id)) for(HollowDepthsMap.ObjectHandle object:map.objects()) bind(map,object);
        return finished;
    }
    @Override public String validateBuiltMap(Instance instance,DungeonLayout layout) { return requireMap(instance).validate(); }
    @Override public void recordRecovery(String account,UUID runId,Instance instance) {
        requireMap(instance); rewards.recordRecovery(requirePlayer(account),runId,LOBBY.getX(),LOBBY.getY(),LOBBY.getZ());
    }
    @Override public void enter(String account,Instance instance,Tile tile) {
        HollowDepthsMap map=requireMap(instance); Player p=requirePlayer(account);
        if(!map.account.equals(account)||!HollowDepths.service.allowEntryArrival(account,instance.runId,instance.id,0,tile)) throw new IllegalStateException("Unauthorized instance arrival");
        p.resetActions(true,true,true); p.getMovement().reset(); p.clearHits();
        HollowDepths.relocate(p,map.world(tile));
    }
    @Override public boolean evacuate(Instance instance,String account) {
        HollowDepthsMap map=requireMap(instance); Player owner=players.get(account);
        for(Player p:World.players) if(map.contains(p)) {
            if(PlayerFile.isSaveQuarantined(p)) return false;
            p.resetActions(true,true,true); p.clearHits(); p.getCombat().reset();
            if(!p.isOnline()) {
                // Logged-out entities can remain registered until their save is acknowledged;
                // they will never run another movement tick to apply a queued teleport.
                if(p.hollowDepths!=null&&p.hollowDepths.recoveryRunId!=null) {
                    HollowDepths.authorizedRelocation(p,()->rewards.recoverLogin(p));
                } else {
                    // Even an unexpected occupant gets a durable return before its position
                    // changes, so failure cannot strand it in a subsequently recycled lease.
                    rewards.recordRecovery(p,instance.runId,LOBBY.getX(),LOBBY.getY(),LOBBY.getZ());
                    HollowDepths.authorizedRelocation(p,()->rewards.recoverLogin(p));
                }
                continue;
            }
            HollowDepths.relocate(p,LOBBY); return false;
        }
        if(owner!=null) {
            if(PlayerFile.isSaveQuarantined(owner)) return false;
            if(map.contains(owner)) {
                // Offline owner cannot process a movement tick. The recorded lobby is persisted atomically.
                if(owner.isOnline()) { HollowDepths.relocate(owner,LOBBY); return false; }
                HollowDepths.authorizedRelocation(owner,()->rewards.recoverLogin(owner));
            }
            if(owner.hollowDepths!=null&&owner.hollowDepths.recoveryRunId!=null) {
                if(owner.getMovement().isTeleportQueued()) return false;
                if(owner.getAbsX()!=LOBBY.getX()||owner.getAbsY()!=LOBBY.getY()||owner.getHeight()!=LOBBY.getZ()) {
                    if(owner.isOnline()) { HollowDepths.relocate(owner,LOBBY); return false; }
                    HollowDepths.authorizedRelocation(owner,()->rewards.recoverLogin(owner));
                }
                rewards.clearRecovery(owner);
            }
            thaw(owner,freezeLocks,LockType.FULL_NULLIFY_DAMAGE);
        }
        return true;
    }
    @Override public void recoverOrphanedLogin(String account) {
        Player p=requirePlayer(account);
        HollowDepths.authorizedRelocation(p,()->rewards.recoverLogin(p));
    }
    @Override public void release(Instance instance) {
        HollowDepthsMap map=maps.get(instance.id); if(map==null) return;
        if(map.identity!=instance) throw new IllegalStateException("Lease identity changed");
        for(Player p:World.players) if(map.contains(p)) throw new IllegalStateException("Cannot release occupied dungeon");
        for(Actor actor:actors.values()) if(actor.map==map) {
            if(!actor.retired) throw new IllegalStateException("Cannot release live actors");
            if(!actor.npc.isRemoved()) actor.npc.remove();
        }
        map.destroy(); maps.remove(instance.id); boundMaps.remove(instance.id);
    }
    @Override public void spawnNpc(Instance instance,NpcTicket ticket) {
        HollowDepthsMap map=requireMap(instance); Position at=map.world(ticket.tile);
        if(!instance.runId.equals(ticket.runId)||!map.account.equals(ticket.accountId)) throw new IllegalStateException("NPC ownership mismatch");
        NPC npc=new NPC(assets.npcId(ticket.archetypeKey));
        npc.hollowDepthsActor=true;
        if(npc.getSize()!=1) throw new IllegalStateException("NPC footprint mismatch");
        npc.spawn(at.getX(),at.getY(),at.getZ(),0);
        Actor actor=new Actor(npc,ticket,map); actors.put(npc,actor);
        try {
            HollowDepthsCombat combat=new HollowDepthsCombat(ticket,catalog.npc(ticket.archetypeKey),npc.getDef().combatInfo);
            npc.installCombat(combat,combat.scaledInfo());
            npc.getCombat().setAllowRespawn(false);
            npc.getCombat().setTarget(requirePlayer(ticket.accountId));
        } catch(RuntimeException failure) { actor.retired=true; npc.remove(); throw failure; }
    }
    @Override public void despawnNpc(Instance instance,String token) {
        HollowDepthsMap map=requireMap(instance);
        for(Actor actor:actors.values()) if(actor.map==map&&actor.ticket.token.equals(token)) {
            actor.retired=true;
            DungeonService.RunView run=HollowDepths.service.inspect(map.account);
            if(!actor.npc.isRemoved()&&actor.npc.getHp()<=0&&run!=null&&run.state==DungeonService.State.ACTIVE) {
                actor.npc.getCombat().setDead(true); actor.npc.getCombat().reset(); actor.npc.getMovement().reset();
                actor.npc.animate(actor.npc.getCombat().getInfo().death_animation);
                actor.npc.startEvent(event->{event.delay(Math.max(1,Math.min(5,actor.npc.getCombat().getInfo().death_ticks))); if(!actor.npc.isRemoved())actor.npc.remove();});
            } else if(!actor.npc.isRemoved()) actor.npc.remove();
            return;
        }
    }
    @Override public int npcHp(Instance instance,String token) {
        HollowDepthsMap map=requireMap(instance);
        for(Actor actor:actors.values()) if(actor.map==map&&actor.ticket.token.equals(token)) return actor.retired?0:actor.npc.getHp();
        return 0;
    }
    @Override public void door(Instance instance,String id,Tile tile,boolean open) { requireMap(instance).setDoor(id,open); }
    @Override public void startMotion(String account,Instance instance,Motion motion) {
        Player p=requirePlayer(account); HollowDepthsMap map=requireMap(instance);
        if(!map.contains(p)||!map.local(p.getPosition()).equals(motion.path.get(0))||motions.containsKey(p)) throw new IllegalStateException("Invalid motion start");
        p.resetActions(true,true,true); p.getMovement().reset();
        motionLocks.put(p,currentLock(p)); p.lock(LockType.FULL_REGULAR_DAMAGE); motions.put(p,motion);
        if(motion.kind==MotionKind.AGILITY_CROSSING) {
            p.animate(839);
            Position destination=map.world(motion.path.get(motion.path.size()-1));
            int dx=destination.getX()-p.getAbsX(), dy=destination.getY()-p.getAbsY();
            HollowDepths.authorizedRelocation(p,()->p.getMovement().force(0,0,dx,dy,0,30,io.ruin.model.map.Direction.getDirection(dx,dy)));
        }
    }
    @Override public void finishMotion(String account,Instance instance,Motion motion) {
        Player p=requirePlayer(account); HollowDepthsMap map=requireMap(instance);
        if(motions.get(p)!=motion||!map.local(p.getPosition()).equals(motion.path.get(motion.path.size()-1))) throw new IllegalStateException("Motion not complete");
        motions.remove(p); p.getMovement().reset(); p.resetAnimation(); thaw(p,motionLocks,LockType.FULL_REGULAR_DAMAGE);
    }
    @Override public boolean cancelMotion(String account,Instance instance,Motion motion,Tile safeTile) {
        Player p=requirePlayer(account); HollowDepthsMap map=requireMap(instance);
        if(PlayerFile.isSaveQuarantined(p)) return false;
        if(!safeTile.equals(map.local(p.getPosition()))) {
            if(!HollowDepths.service.allowScriptRelocation(account,instance.runId,map.local(p.getPosition()),safeTile,motion.permit)) return false;
            p.getMovement().reset(); HollowDepths.relocate(p,map.world(safeTile));
            if(!p.isOnline()) p.getMovement().finishTeleport(p.getPosition());
            return false;
        }
        motions.remove(p); p.getMovement().reset(); p.resetAnimation(); thaw(p,motionLocks,LockType.FULL_REGULAR_DAMAGE); return true;
    }
    @Override public void freeze(String account,UUID runId,boolean freeze) {
        Player p=requirePlayer(account);
        if(freeze) {
            if(!freezeLocks.containsKey(p)) {
                LockType previous=currentLock(p);
                // Cleanup cancels the motion before releasing the enclosing death/extraction
                // freeze. Preserve the lock that preceded that motion, not its temporary lock.
                if(previous==LockType.FULL_REGULAR_DAMAGE && motionLocks.containsKey(p)) previous=motionLocks.get(p);
                freezeLocks.put(p,previous);
            }
            p.resetActions(true,true,true); p.getMovement().reset(); p.clearHits(); p.getCombat().reset(); p.lock(LockType.FULL_NULLIFY_DAMAGE);
        } else thaw(p,freezeLocks,LockType.FULL_NULLIFY_DAMAGE);
    }
    private static LockType currentLock(Player p) { for(LockType type:LockType.values()) if(p.isLocked(type)) return type; return LockType.NONE; }
    private static void thaw(Player p,Map<Player,LockType> held,LockType lock) {
        if(PlayerFile.isSaveQuarantined(p)) return;
        LockType previous=held.remove(p);
        if(previous!=null&&p.isLocked(lock)) p.lock(previous);
    }
    @Override public void prepareExtraction(String account,String transaction,Rewards.Bundle bundle) { rewards.prepareExtraction(requirePlayer(account),transaction,bundle); }
    @Override public void message(String account,String text) { Player p=players.get(account); if(p!=null&&p.isOnline())p.sendMessage(text); }
    @Override public void awardAgilityXpOnce(String account,String award,int xp) { rewards.awardAgilityXpOnce(requirePlayer(account),award,xp); }
    @Override public void hit(String account,int damage,DamageKind kind,String sourceId) {
        Player p=requirePlayer(account); DungeonService.RunView run=HollowDepths.service.inspect(account);
        if(run==null||run.state!=DungeonService.State.ACTIVE) return;
        AttackStyle style=kind==DamageKind.MAGIC?AttackStyle.MAGIC:kind==DamageKind.RANGED?AttackStyle.RANGED:kind==DamageKind.MELEE?AttackStyle.CRUSH:null;
        Hit hit=new Hit(null,style).fixedDamage(damage).setResetActions(false);
        hazardHits.put(hit,new HazardHit(run.runId,account)); p.hit(hit);
    }
    @Override public void telegraph(Instance instance,String id,List<Tile> tiles,DamageKind kind,long due) {
        HollowDepthsMap map=requireMap(instance); Warning warning=new Warning(map,tiles,due); telegraphs.put(id,warning);
        Player p=requirePlayer(map.account); drawWarning(warning);
        p.sendMessage("The floor flares: move clear of the marked tiles!");
    }
    @Override public void clearTelegraph(Instance instance,String id) { requireMap(instance); telegraphs.remove(id); }
    @Override public void openedChest(Instance instance,int slot,Tile tile) { requireMap(instance).openedChest(slot); }
    @Override public void audit(String event,String account,UUID runId,String detail) { Server.println("[HollowDepths] "+event+" account="+account+" run="+runId+" "+detail); }

    void pulse() {
        if(Server.currentTick()%50==0) players.entrySet().removeIf(entry->{
            Player p=entry.getValue();
            boolean active=HollowDepths.service!=null&&HollowDepths.service.inspect(entry.getKey())!=null;
            boolean recovery=p.hollowDepths!=null&&(p.hollowDepths.recoveryRunId!=null||p.hollowDepths.pendingTransaction!=null);
            if(p.isOnline()||active||recovery||PlayerFile.isSaveQuarantined(p)) return false;
            motions.remove(p); motionLocks.remove(p); freezeLocks.remove(p); return true;
        });
        actors.entrySet().removeIf(entry -> entry.getValue().retired && entry.getKey().isRemoved());
        if(Server.currentTick()%2==0) for(Warning warning:telegraphs.values()) if(Server.currentTick()<warning.due) drawWarning(warning);
        for(Map.Entry<Player,Motion> entry:new ArrayList<>(motions.entrySet())) {
            Player p=entry.getKey(); Motion motion=entry.getValue();
            if(motion.kind==MotionKind.AGILITY_CROSSING) continue;
            if(!p.isOnline()||PlayerFile.isSaveQuarantined(p)||!p.getMovement().isAtDestination()||p.getMovement().isTeleportQueued()) continue;
            HollowDepthsMap map=mapAt(p.getPosition()); if(map==null) continue;
            int index=motion.path.indexOf(map.local(p.getPosition()));
            if(index<0||index+1>=motion.path.size()) continue;
            Position next=map.world(motion.path.get(index+1));
            p.getMovement().reset();
            p.getMovement().getStepsX()[0]=next.getX(); p.getMovement().getStepsY()[0]=next.getY();
            p.getMovement().writeOffset=1; p.getMovement().stepType=StepType.FORCE_WALK;
        }
    }

    private void drawWarning(Warning warning) {
        if(maps.get(warning.map.identity.id)!=warning.map) return;
        Player p=players.get(warning.map.account);
        if(p==null||!p.isOnline()||!warning.map.contains(p)) return;
        int gfx=assets.gfxId("boss.warning");
        for(Tile tile:warning.tiles) { Position at=warning.map.world(tile); p.getPacketSender().sendGraphics(gfx,0,0,at.getX(),at.getY(),at.getZ()); }
    }

    private void bind(HollowDepthsMap map,HollowDepthsMap.ObjectHandle handle) {
        // Per-object actions leave the same cache assets elsewhere completely unchanged.
        ObjectAction action=(p,obj)-> {
            if(maps.get(map.identity.id)!=map||!map.account.equals(account(p))||!map.contains(p)||obj!=handle.object) return;
            switch(handle.kind) {
                case CHEST: HollowDepths.service.clickChest(map.account,map.identity.runId,handle.slot); break;
                case SHRINE: HollowDepths.service.clickShrine(map.account,map.identity.runId,handle.roomId); break;
                case EXIT: HollowDepths.service.clickExit(map.account,map.identity.runId); break;
                case TRAP: approachTrap(p,map,handle.slot); break;
                case DOOR: approachDoor(p,map,handle.key); break;
                default: throw new IllegalStateException("Unknown dungeon interaction");
            }
        };
        for(int option=1;option<=5;option++) ObjectAction.register(handle.object,option,action);
    }
    private void approachTrap(Player p,HollowDepthsMap map,int slot) {
        for(DungeonLayout.Room room:map.layout.rooms) for(DungeonLayout.TrapSpawn trap:room.traps) if(trap.slot==slot) {
            Tile current=map.local(p.getPosition());
            Tile approach=current.chebyshevDistance(trap.trigger)<=current.chebyshevDistance(trap.landing)?trap.trigger:trap.landing;
            walkThen(p,map,approach,()->HollowDepths.service.clickAgilityTrap(map.account,map.identity.runId,slot)); return;
        }
    }
    private void approachDoor(Player p,HollowDepthsMap map,String key) {
        for(DungeonLayout.Room room:map.layout.rooms) for(DungeonLayout.TrapSpawn trap:room.traps) {
            String prefix="trap:"+trap.slot+":";
            if(!key.startsWith(prefix)) continue;
            DungeonLayout.Gauntlet g=trap.gauntlet; boolean inside=g.contains(map.local(p.getPosition()));
            Tile approach=key.endsWith(":entry")?(inside?g.entranceInside:g.entranceOutside):(inside?g.exitInside:g.exitOutside);
            walkThen(p,map,approach,()->HollowDepths.service.clickBypassDoor(map.account,map.identity.runId,key)); return;
        }
    }
    private void walkThen(Player p,HollowDepthsMap map,Tile local,Runnable action) {
        if(local.equals(map.local(p.getPosition()))) { action.run(); return; }
        Position destination=map.world(local);
        p.getRouteFinder().routeAbsolute(destination.getX(),destination.getY(),false);
        p.startEvent(event->{
            int remaining=32;
            while(!p.getMovement().isAtDestination()&&remaining-->0) event.delay(1);
            if(maps.get(map.identity.id)==map&&map.contains(p)&&local.equals(map.local(p.getPosition()))) action.run();
            else p.sendMessage("Walk to the entrance of the obstacle first.");
        });
    }
}
