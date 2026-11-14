package io.ruin.model.activities.hollowdepths;

import com.newscape.dungeons.*;
import com.newscape.dungeons.DungeonPorts.*;
import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.combat.Hit;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.dialogue.YesNoDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.map.Position;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Revision-184 adapter. All mutable leases and actors belong exclusively to the world thread. */
public final class HollowDepths {
    static HollowDepthsGamePort port;
    static HollowDepthsRewards rewards;
    static DungeonService service;
    static DungeonWorkers workers;
    static Thread worldThread;
    static boolean faulted;
    private static long lastFaultLog = Long.MIN_VALUE;
    private static String unavailable = "Hollow Depths is not enabled on this world.";
    private static final Set<Player> relocating = Collections.newSetFromMap(new IdentityHashMap<Player, Boolean>());
    private static final Map<Player, Arrival> arrivals = new IdentityHashMap<>();
    private static final class Arrival {
        final Position position;
        final UUID runId, motionPermit;
        final String instanceId;
        Arrival(Position position, UUID runId, String instanceId, UUID motionPermit) {
            this.position=position; this.runId=runId; this.instanceId=instanceId; this.motionPermit=motionPermit;
        }
    }
    private static final Map<Player, RecoveryRetry> recoveryRetries = new IdentityHashMap<>();
    private HollowDepths() { }

    public static void prepare() {
        if (Server.dataOnlyMode || rewards != null) return;
        Path data = Server.dataFolder.toPath().resolve("hollow-depths");
        try {
            if (!io.ruin.utility.OfflineMode.enabled || io.ruin.utility.OfflineMode.path == null) return;
            rewards = new HollowDepthsRewards(java.nio.file.Paths.get(io.ruin.utility.OfflineMode.path,"hollow-depths-mailbox"));
            port = new HollowDepthsGamePort(null, rewards);
            // Recovery must remain available even when assets are deliberately disabled or missing.
            if (!java.nio.file.Files.isRegularFile(data.resolve("revision-184.properties"))) return;
            HollowDepthsAssets assets = new HollowDepthsAssets(data.resolve("revision-184.properties"));
            unavailable = assets.deploymentDenial();
            HollowDepthsRewards.validateMappings();
            port = new HollowDepthsGamePort(assets, rewards);
            workers = new DungeonWorkers(32);
            // This VM also hosts the existing world: bound simultaneous 96x96 leases.
            int capacity = Math.max(1,Math.min(16,Integer.getInteger("kronos.hollowDepths.maxInstances",4)));
            DungeonConfig policy = new DungeonConfig(capacity,100,600,3600,300,900,8,16,32,12,3,15,64,true);
            service = new DungeonService(port, rewards, policy, workers.planners(), workers.persistence());
            Server.println("Hollow Depths: " + (unavailable == null ? "184 assets verified; private dungeons enabled." : unavailable));
        } catch (Exception failure) {
            unavailable = "Hollow Depths asset verification failed; entry is disabled.";
            Server.logError(unavailable, failure);
        }
    }

    public static void pulse() {
        if (rewards == null) return;
        if (worldThread == null) worldThread = Thread.currentThread();
        port.assertWorldThread();
        rewards.pulse();
        retryRecoveries();
        if (service != null) service.pulse(Server.currentTick());
        port.pulse();
    }

    public static void fault(Throwable failure) {
        boolean first = !faulted;
        faulted = true;
        if (first || Server.currentTick()-lastFaultLog>=50) {
            lastFaultLog=Server.currentTick();
            Server.logError("Hollow Depths entered maintenance after a runtime fault",failure);
        }
        if (service != null) try { service.beginMaintenanceDrain(); }
        catch (RuntimeException cleanupFailure) { if(first) Server.logError("Dungeon cleanup will retry on subsequent ticks",cleanupFailure); }
    }

    static String account(Player player) {
        if (player == null) return null;
        return port == null ? null : port.account(player);
    }

    public static boolean inRun(Player player) {
        String account = account(player);
        return service != null && account != null && service.inspect(account) != null;
    }

    public static DungeonService.RunView inspect(Player player) {
        String account = account(player);
        return service == null || account == null ? null : service.inspect(account);
    }

    public static void openEntry(Player player) {
        if (service == null) { player.sendMessage(unavailable); return; }
        if (PlayerFile.isSaveQuarantined(player)) { player.sendMessage("Your account save is still being confirmed. Please wait."); return; }
        String account = account(player);
        if (account == null) { player.sendMessage("A locally registered account is required."); return; }
        DungeonService.RunView run = service.inspect(account);
        player.closeInterface(InterfaceType.MAIN);
        if (run != null) {
            player.dialogue(new OptionsDialogue("Hollow Depths",
                new Option("Run progress", () -> {
                    DungeonService.RunView current = service.inspect(account);
                    if (current != null) player.sendMessage("Hollow Depths: " + current.guardians + "/3 sigils, " + current.openedChests + " chests, " + current.discoveredRooms + " rooms explored. " + current.state);
                }),
                new Option("Abandon run (lose unbanked treasure)", () -> player.dialogue(new YesNoDialogue(
                    "Leave Hollow Depths?", "Your equipment is safe. Unbanked dungeon treasure will be lost.", 995, 1, () -> service.abandon(account, run.runId)))),
                new Option("Continue exploring", player::closeDialogue)));
            return;
        }
        player.dialogue(new OptionsDialogue("Hollow Depths - private random dungeon",
            new Option("Novice", () -> enter(player, Rewards.Tier.NOVICE)),
            new Option("Veteran", () -> enter(player, Rewards.Tier.VETERAN)),
            new Option("Nightmare", () -> enter(player, Rewards.Tier.NIGHTMARE)),
            new Option("Collect secured rewards", () -> { rewards.showBalance(player); rewards.redeem(player); }),
            new Option("How to play", () -> player.sendMessage("Explore, defeat three sigil guardians, then defeat the final boss, open its chest and use the exit. Loot stays unbanked until extraction. ::hollow shows progress or abandons safely."))));
    }

    private static void enter(Player player, Rewards.Tier tier) {
        player.closeDialogue();
        service.clickEntrancePortal(account(player), tier);
    }

    public static void onLogin(Player player) {
        if (player instanceof io.ruin.model.entity.player.autonomous.Adventurer) return;
        if (rewards == null) {
            if (player.hollowDepths != null && player.hollowDepths.recoveryRunId != null)
                PlayerFile.quarantine(player,"Dungeon recovery storage is unavailable");
            return;
        }
        port.register(player);
        String account = account(player);
        if (account == null) return;
        LockType previousLock = LockType.NONE;
        for (LockType type : LockType.values()) if (player.isLocked(type)) { previousLock = type; break; }
        attemptRecovery(player, new RecoveryRetry(account, previousLock));
    }

    private static final class RecoveryRetry {
        final String account;
        final LockType previousLock;
        long retryAt;
        int failures;
        RecoveryRetry(String account, LockType previousLock) { this.account = account; this.previousLock = previousLock; }
    }

    private static void attemptRecovery(Player player, RecoveryRetry retry) {
        try {
            if (PlayerFile.isSaveQuarantined(player)) throw new IllegalStateException("Waiting for an outstanding player save");
            // Release only this recovery's lock. DungeonService may legitimately acquire its
            // own extraction lock, which must survive a successful recovery attempt.
            player.lock(retry.previousLock);
            rewards.recoverPendingExtraction(player);
            if (service != null) service.onLogin(retry.account);
            else authorizedRelocation(player,()->rewards.recoverLogin(player));
            recoveryRetries.remove(player);
        } catch (RuntimeException failure) {
            retry.failures++;
            retry.retryAt = Server.currentTick() + Math.min(500L, 50L * retry.failures);
            recoveryRetries.put(player, retry);
            player.lock(LockType.FULL_NULLIFY_DAMAGE);
            player.stopEvent(true);
            player.resetActions(true, true, true);
            player.clearHits();
            if (retry.failures == 1) {
                Server.logError("Hollow Depths login recovery is held for account " + player.getUserId(), failure);
                player.sendMessage("Your dungeon recovery is waiting for storage. Your items and claim are protected; it will retry automatically.");
            }
        }
    }

    private static void retryRecoveries() {
        boolean attempted = false;
        for (Map.Entry<Player, RecoveryRetry> entry : new ArrayList<>(recoveryRetries.entrySet())) {
            Player player = entry.getKey();
            if (!player.isOnline()) { recoveryRetries.remove(player); continue; }
            // A late successful save may just have released its own quarantine. Keep recovery
            // locked until this separate lifecycle operation has also finished.
            player.lock(LockType.FULL_NULLIFY_DAMAGE);
            if (attempted || PlayerFile.isSaveQuarantined(player) || Server.currentTick() < entry.getValue().retryAt) continue;
            attemptRecovery(player, entry.getValue());
            attempted = true;
        }
    }

    public static void onLogout(Player player) {
        arrivals.remove(player);
        recoveryRetries.remove(player);
        if (service == null) return;
        String account = account(player);
        if (account != null) service.onLogout(account);
    }

    public static boolean onPlayerDeath(Player player) {
        DungeonService.RunView run = inspect(player);
        if (run == null) return false;
        player.resetActions(true, true, true);
        player.getMovement().reset();
        player.getCombat().reset();
        player.getCombat().resetKillers();
        player.getCombat().setDead(false);
        player.getCombat().setTruelyDead(false);
        player.setHp(player.getMaxHp());
        player.curePoison(0); player.cureVenom(0);
        service.onPlayerDeath(account(player), run.runId);
        return true;
    }

    public static boolean onNpcDeath(NPC npc, Hit hit) {
        HollowDepthsGamePort.Actor actor = port == null ? null : port.actors.get(npc);
        if (actor == null) {
            if (!npc.hollowDepthsActor) return false;
            if (!npc.isRemoved()) npc.remove();
            return true;
        }
        if (!actor.retired) {
            npc.getCombat().setDead(true);
            npc.getCombat().reset();
            // Ownership was checked at attack and impact. Never run generic drops/slayer/respawn here.
            DungeonService.Result result = service.onNpcDeath(actor.ticket.runId, actor.ticket.token, actor.ticket.accountId);
            actor.retired = true;
            if (result != DungeonService.Result.ACCEPTED && !npc.isRemoved()) npc.remove();
        }
        return true;
    }

    public static boolean allowCombat(Entity from, Entity to) {
        if (port == null) return true;
        if (from == null || to == null) return true;
        HollowDepthsGamePort.Actor actor = from.npc != null ? port.actors.get(from.npc) : null;
        Player player = to.player;
        if (actor == null && to.npc != null) { actor = port.actors.get(to.npc); player = from.player; }
        if (actor != null) return !faulted && !actor.retired && player != null && !PlayerFile.isSaveQuarantined(player)
            && actor.map.contains(player) && actor.map.contains(actor.npc)
            && service.allowCombat(actor.ticket.runId, actor.ticket.token, account(player));
        if ((from.npc != null && from.npc.hollowDepthsActor) || (to.npc != null && to.npc.hollowDepthsActor)) return false;
        // No foreign NPC, follower or player can participate across a private boundary.
        return port.mapAt(from.getPosition()) == null && port.mapAt(to.getPosition()) == null
            && !(from.player != null && inRun(from.player)) && !(to.player != null && inRun(to.player));
    }

    public static boolean allowHit(Hit hit, Entity target) {
        if (port == null) return true;
        HollowDepthsGamePort.HazardHit hazard = port.hazardHits.get(hit);
        if (hazard != null) {
            DungeonService.RunView run = target.player == null ? null : inspect(target.player);
            return run != null && run.state == DungeonService.State.ACTIVE && hazard.runId.equals(run.runId)
                && hazard.account.equals(account(target.player)) && port.mapAt(target.getPosition()) != null;
        }
        if (hit.attacker == null) return true; // ordinary poison/healing remains native; dungeon hazards above are lease-bound.
        return allowCombat(hit.attacker, target);
    }

    public static Hit[] filterHits(Hit[] hits, Entity target) {
        if (port == null) return hits;
        for (int i=0;i<hits.length;i++) if (!allowHit(hits[i],target)) {
            List<Hit> accepted = new ArrayList<>();
            for (int j=0;j<i;j++) accepted.add(hits[j]);
            for (int j=i+1;j<hits.length;j++) if (allowHit(hits[j],target)) accepted.add(hits[j]);
            return accepted.toArray(new Hit[accepted.size()]);
        }
        return hits;
    }

    public static boolean allowStep(Entity entity, int x, int y) {
        if (port == null) return true;
        Position target = new Position(x, y, entity.getHeight());
        HollowDepthsMap map = port.mapAt(entity.getPosition());
        HollowDepthsMap destination = port.mapAt(target);
        if (entity.npc != null) {
            HollowDepthsGamePort.Actor actor = port.actors.get(entity.npc);
            if (actor == null) return !entity.npc.hollowDepthsActor && map == null && destination == null;
            if (actor.retired || actor.map != map || actor.map != destination) return false;
            List<DungeonLayout.Tile> footprint = new ArrayList<>();
            for (int dx = 0; dx < entity.getSize(); dx++) for (int dy = 0; dy < entity.getSize(); dy++)
                footprint.add(map.local(new Position(x + dx, y + dy, entity.getHeight())));
            return service.allowNpcFootprint(actor.ticket.runId, actor.ticket.token, footprint);
        }
        if (entity.player == null) return map == null && destination == null;
        Player player = entity.player;
        DungeonService.RunView run = inspect(player);
        if (run == null) return map == null && destination == null;
        if (map == null || map != destination || !map.account.equals(account(player)) || PlayerFile.isSaveQuarantined(player)) return false;
        Motion motion = port.motions.get(player);
        return service.allowPlayerStep(account(player), run.runId, map.local(entity.getPosition()), map.local(target), motion == null ? null : motion.permit);
    }

    public static boolean allowNpcRelocation(NPC npc, int x, int y, int z) {
        if (port == null) return true;
        HollowDepthsGamePort.Actor actor = port.actors.get(npc);
        if (actor == null) return !npc.hollowDepthsActor && port.mapAt(new Position(x,y,z)) == null && port.mapAt(npc.getPosition()) == null;
        return z == npc.getHeight() && allowStep(npc, x, y);
    }

    public static void onMoved(Entity entity) {
        if (port == null || entity.player == null) return;
        arrivals.remove(entity.player);
        String account = account(entity.player);
        if (service != null && account != null) service.onPlayerMoved(account);
    }

    /** Internal relocations are scoped, then checked again when their queued teleport applies. */
    static void authorizedRelocation(Player player, Runnable action) {
        if (!relocating.add(player)) { action.run(); return; }
        try { action.run(); } finally { relocating.remove(player); }
    }

    static void relocate(Player player, Position position) {
        authorizedRelocation(player, () -> player.getMovement().teleport(position));
    }

    public static boolean allowTeleport(Player player, int x, int y, int z) {
        if (port == null) return true;
        if (relocating.contains(player)) {
            Position position = new Position(x,y,z);
            HollowDepthsMap map = port.mapAt(position);
            DungeonService.RunView run = inspect(player);
            Motion motion = port.motions.get(player);
            if (map != null && (run == null || !map.identity.runId.equals(run.runId) || !map.account.equals(account(player)))) return false;
            Arrival arrival = new Arrival(position, run == null ? null : run.runId, map == null ? null : map.identity.id, motion == null ? null : motion.permit);
            arrivals.put(player, arrival);
            if (!allowTeleportArrival(player,x,y,z)) { arrivals.remove(player); return false; }
            return true;
        }
        return !inRun(player) && port.mapAt(player.getPosition()) == null && port.mapAt(new Position(x,y,z)) == null;
    }

    public static boolean allowTeleportArrival(Player player, int x, int y, int z) {
        if (port == null) return true;
        Arrival permit = arrivals.get(player);
        if (permit != null && permit.position.getX() == x && permit.position.getY() == y && permit.position.getZ() == z) {
            HollowDepthsMap destination = port.mapAt(permit.position);
            if (permit.instanceId == null) return destination == null;
            DungeonService.RunView run = inspect(player);
            if (destination == null || run == null || !permit.runId.equals(run.runId) || !permit.instanceId.equals(destination.identity.id)
                || !destination.identity.runId.equals(run.runId) || !destination.account.equals(account(player))) return false;
            if (run.state == DungeonService.State.ENTERING)
                return service.allowEntryArrival(account(player),run.runId,destination.identity.id,z,destination.local(permit.position));
            return permit.motionPermit != null && service.allowScriptRelocation(account(player),run.runId,
                destination.local(player.getPosition()),destination.local(permit.position),permit.motionPermit);
        }
        return !inRun(player) && port.mapAt(player.getPosition()) == null && port.mapAt(new Position(x,y,z)) == null;
    }

    /** Called off-thread by Server's normal shutdown hook while world ticks are still alive. */
    public static void shutdownDrain() {
        if (service == null) {
            if (rewards != null) try { rewards.close(); } catch (Exception failure) { Server.logError("Dungeon recovery storage shutdown failed",failure); }
            return;
        }
        try {
            Server.worker.getExecutor().submit(() -> service.beginMaintenanceDrain()).get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (!Server.worker.getExecutor().submit(() -> service.isDrained()).get(10, TimeUnit.SECONDS)) {
                if (System.nanoTime() >= deadline) {
                    Server.logWarning("Hollow Depths drain timed out; durable recovery intents retained for restart.");
                    break;
                }
                Thread.sleep(100);
            }
            workers.close(); // accepted reward writes are never discarded
            long writesDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!workers.isTerminated()&&System.nanoTime()<writesDeadline) Thread.sleep(50);
            if(workers.isTerminated()) rewards.close();
        } catch (Exception failure) { Server.logError("Hollow Depths shutdown recovery required", failure); }
    }
}
