package com.newscape.dungeons.demo;

import com.newscape.dungeons.*;
import com.newscape.dungeons.DungeonLayout.*;
import com.newscape.dungeons.DungeonPorts.*;
import java.util.*;

/**
 * Executable deterministic TEST WORLD, not a Kronos adapter. It exercises actual service
 * decisions with asynchronous arrivals, scripted steps, owned NPCs and collision policies.
 * It does not simulate cache rendering, network packets, normal combat DPS or human pacing.
 */
public final class HeadlessWorld implements GamePort {
    public static final class Person {
        public final String account;
        public String instance;
        public Tile tile = new Tile(0, 0);
        public int plane, combat = 90, agility = 1, currentAgility = 1, hp = 99, maxHp = 99;
        public boolean online = true, busy, atPortal = true;
        public UUID frozenBy;
        public int agilityXp;
        public Person(String account) { this.account = account; }
    }
    private static final class PendingEntry {
        final Instance instance; final Tile tile; int ticks;
        PendingEntry(Instance instance, Tile tile, int ticks) { this.instance = instance; this.tile = tile; this.ticks = ticks; }
    }
    private final Thread worldThread = Thread.currentThread();
    public final Map<String, Person> people = new LinkedHashMap<String, Person>();
    public final Map<String, NpcTicket> actors = new LinkedHashMap<String, NpcTicket>();
    public final Map<String, Integer> npcHealth = new HashMap<String, Integer>();
    public final Map<String, Boolean> openDoors = new HashMap<String, Boolean>();
    public final List<String> events = new ArrayList<String>();
    public final Map<String, Motion> motions = new HashMap<String, Motion>();
    public final Set<String> xpReceipts = new HashSet<String>();
    public final Map<String, Rewards.Bundle> extractionIntents = new LinkedHashMap<String, Rewards.Bundle>();
    public final List<String> extractionAttempts = new ArrayList<String>();
    public int failPrepareBeforeCommit;
    public boolean failPrepareAfterCommitOnce;
    public final Map<String, List<Tile>> telegraphs = new LinkedHashMap<String, List<Tile>>();
    public int hitCalls;
    public final Map<String, UUID> recovery = new HashMap<String, UUID>();
    private final Map<String, Instance> leases = new HashMap<String, Instance>();
    private final Map<String, Integer> builtChunks = new HashMap<String, Integer>();
    private final Map<String, PendingEntry> entries = new HashMap<String, PendingEntry>();
    public DungeonService service;
    public int released, doorOpens, doorCloses, entryDelay = 2, chunksBuiltThisTick, maxChunksBuiltInTick;
    public boolean neverBuild, invalidMap, failFreezeOnce, failUnfreezeOnce, failMessageOnce;
    public boolean failSpawnOnce, failEvacuateOnce, synchronousDeathOnSpawn, reenterOnEvacuate;
    public int failDespawnCount;
    public int cancelDelay;
    public String deploymentProblem;
    private long tick = -1;

    public Person addPlayer(String id, int agility) {
        Person p = new Person(id); p.agility = p.currentAgility = agility; people.put(id, p); return p;
    }
    public void attach(DungeonService service) { this.service = service; advance(); }
    public int activeLeaseCount() { return leases.size(); }
    public void advance() {
        chunksBuiltThisTick = 0;
        service.pulse(++tick);
        maxChunksBuiltInTick = Math.max(maxChunksBuiltInTick, chunksBuiltThisTick);
        for (String account : new ArrayList<String>(entries.keySet())) {
            PendingEntry entry = entries.get(account);
            if (entry == null || --entry.ticks > 0) continue;
            entries.remove(account); Person p = people.get(account);
            if (service.allowEntryArrival(account, entry.instance.runId, entry.instance.id, 0, entry.tile)) {
                p.instance = entry.instance.id; p.tile = entry.tile; p.plane = 0; p.atPortal = false;
            }
        }
        for (String account : new ArrayList<String>(motions.keySet())) {
            Motion motion = motions.get(account); if (motion == null) continue;
            Person p = people.get(account); if (!p.online) continue;
            int index = motion.path.indexOf(p.tile);
            if (index < 0 || index == motion.path.size() - 1) continue;
            Tile next = motion.path.get(index + 1);
            DungeonService.RunView run = service.inspect(account);
            if (run != null && service.allowPlayerStep(account, run.runId, p.tile, next, motion.permit)) {
                p.tile = next; service.onPlayerMoved(account);
            }
        }
    }
    public void advance(int count) { for (int i = 0; i < count; i++) advance(); }
    public DungeonService.RunView enterRun(String account, Rewards.Tier tier) {
        if (service.clickEntrancePortal(account, tier) != DungeonService.Result.ACCEPTED)
            throw new IllegalStateException("Entry denied for " + account);
        for (int i = 0; i < 800; i++) {
            advance(); DungeonService.RunView run = service.inspect(account);
            if (run == null) throw new IllegalStateException("Run disappeared during setup");
            if (run.state == DungeonService.State.ACTIVE) return run;
        }
        throw new IllegalStateException("Run did not activate");
    }
    /** Test-fixture relocation; deliberately bypasses ordinary movement, never used in live wiring. */
    public void placeFixture(String account, Tile tile) {
        Person p = people.get(account); p.tile = tile; service.onPlayerMoved(account);
    }
    public boolean step(String account, Tile tile) {
        Person p = people.get(account); DungeonService.RunView run = service.inspect(account);
        if (run == null || p.frozenBy != null || !service.allowPlayerStep(account, run.runId, p.tile, tile, null)) return false;
        p.tile = tile; service.onPlayerMoved(account); return true;
    }
    public void kill(String account, String token) {
        NpcTicket actor = actors.get(token);
        if (actor == null) return;
        if (!service.allowCombat(actor.runId, token, account)) throw new IllegalStateException("Combat boundary rejected " + token);
        npcHealth.put(token, 0); service.onNpcDeath(actor.runId, token, account);
    }
    public void defeatRoom(String account, Room room) {
        for (int wait = 0; wait < 30; wait++) {
            advance();
            for (NpcTicket actor : new ArrayList<NpcTicket>(actors.values()))
                if (actor.accountId.equals(account) && actor.roomId == room.id && !actor.gauntletGuard) kill(account, actor.token);
        }
    }
    /** Walk a true cardinal path through terrain. Door crossings call the click handler then scripted steps. */
    public void walkTo(String account, Tile target) {
        for (int safety = 0; safety < 3000; safety++) {
            Person p = people.get(account); if (target.equals(p.tile)) return;
            DungeonService.RunView run = service.inspect(account);
            if (run == null) throw new IllegalStateException("Run ended while navigating");
            List<Tile> path = findPath(run.layout, p.tile, target, run.guardians < 3);
            if (path.size() < 2) throw new IllegalStateException("No walking path " + p.tile + " to " + target);
            Tile next = path.get(1);
            String door = doorAt(run.layout, next);
            if (door != null) {
                advance(3);
                DungeonService.Result result = service.clickBypassDoor(account, run.runId, door);
                if (result != DungeonService.Result.ACCEPTED) throw new IllegalStateException("Door rejected " + door + " at " + p.tile + ": " + result);
                advance(3);
                // In this test harness combat is explicit, without simulated client animation/DPS.
                for (NpcTicket actor : new ArrayList<NpcTicket>(actors.values()))
                    if (actor.accountId.equals(account) && actor.gauntletGuard && service.allowCombat(run.runId, actor.token, account)) kill(account, actor.token);
            } else {
                if (!step(account, next)) throw new IllegalStateException("Step rejected " + p.tile + " -> " + next);
                advance();
            }
        }
        throw new IllegalStateException("Navigation budget exceeded");
    }
    public static String doorAt(DungeonLayout layout, Tile tile) {
        for (Room r : layout.rooms) for (TrapSpawn t : r.traps) {
            if (t.gauntlet.entranceDoor.equals(tile)) return "trap:" + t.slot + ":entry";
            if (t.gauntlet.exitDoor.equals(tile)) return "trap:" + t.slot + ":exit";
        }
        return null;
    }
    public static List<Tile> findPath(DungeonLayout layout, Tile start, Tile target, boolean blockBoss) {
        ArrayDeque<Tile> queue = new ArrayDeque<Tile>(); Map<Tile, Tile> prior = new HashMap<Tile, Tile>();
        queue.add(start); prior.put(start, null);
        while (!queue.isEmpty()) {
            Tile tile = queue.remove(); if (tile.equals(target)) break;
            for (int[] d : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
                Tile next = new Tile(tile.x + d[0], tile.y + d[1]);
                if (!layout.walkable(next) || prior.containsKey(next)
                        || blockBoss && layout.room(layout.bossRoomId).contains(next)) continue;
                prior.put(next, tile); queue.add(next);
            }
        }
        if (!prior.containsKey(target)) return Collections.emptyList();
        List<Tile> result = new ArrayList<Tile>();
        for (Tile t = target; t != null; t = prior.get(t)) result.add(t);
        Collections.reverse(result); return result;
    }

    @Override public void assertWorldThread() {
        if (Thread.currentThread() != worldThread) throw new IllegalStateException("not the world thread");
    }
    @Override public PlayerView player(String account) {
        Person p = people.get(account); return p == null ? null : new PlayerView(p.account, p.instance, p.tile,
                p.plane, p.combat, p.agility, p.currentAgility, p.hp, p.maxHp, p.online, p.busy);
    }
    @Override public String entryDenial(String account, Rewards.Tier tier) {
        Person p = people.get(account);
        return p == null || !p.online || !p.atPortal || p.hp == 0 || p.busy || p.instance != null ? "You must stand at the entrance portal, alive and ready." : null;
    }
    @Override public String deploymentDenial() { return deploymentProblem; }
    @Override public Instance reserve(UUID runId, String account, DungeonLayout layout) {
        Instance instance = new Instance("headless:" + runId, runId); leases.put(instance.id, instance); builtChunks.put(instance.id, 0); return instance;
    }
    @Override public boolean buildStep(Instance instance, DungeonLayout layout, int budget) {
        chunksBuiltThisTick += budget;
        if (neverBuild) return false;
        int built = builtChunks.get(instance.id) + budget; builtChunks.put(instance.id, built); return built >= 144;
    }
    @Override public String validateBuiltMap(Instance instance, DungeonLayout layout) { return invalidMap ? "injected clipping mismatch" : null; }
    @Override public void recordRecovery(String account, UUID runId, Instance instance) { recovery.put(account, runId); }
    @Override public void enter(String account, Instance instance, Tile tile) { entries.put(account, new PendingEntry(instance, tile, entryDelay)); }
    @Override public boolean evacuate(Instance instance, String account) {
        if (failEvacuateOnce) { failEvacuateOnce = false; return false; }
        if (reenterOnEvacuate) service.abandon(account, instance.runId);
        entries.entrySet().removeIf(e -> e.getValue().instance.id.equals(instance.id));
        for (Person p : people.values()) if (instance.id.equals(p.instance)) { p.instance = null; p.tile = new Tile(0,0); p.plane = 0; p.atPortal = true; }
        recovery.remove(account); return true;
    }
    @Override public void recoverOrphanedLogin(String account) {
        if (recovery.remove(account) != null) { Person p = people.get(account); p.instance = null; p.tile = new Tile(0,0); p.plane = 0; p.atPortal = true; }
    }
    @Override public void release(Instance instance) {
        for (Person p : people.values()) if (instance.id.equals(p.instance)) throw new IllegalStateException("occupied map release");
        for (NpcTicket n : actors.values()) if (n.runId.equals(instance.runId)) throw new IllegalStateException("NPCs remain at release");
        if (leases.remove(instance.id) != null) released++;
        builtChunks.remove(instance.id); openDoors.keySet().removeIf(k -> k.startsWith(instance.id + ":"));
    }
    @Override public void spawnNpc(Instance instance, NpcTicket npc) {
        if (failSpawnOnce) { failSpawnOnce = false; throw new IllegalStateException("injected spawn failure"); }
        actors.put(npc.token, npc); npcHealth.put(npc.token, synchronousDeathOnSpawn ? 0 : npc.stats.hp);
        if (synchronousDeathOnSpawn) service.onNpcDeath(instance.runId, npc.token, npc.accountId);
    }
    @Override public void despawnNpc(Instance instance, String token) {
        if (failDespawnCount > 0) { failDespawnCount--; throw new IllegalStateException("injected despawn failure"); }
        actors.remove(token); npcHealth.remove(token);
    }
    @Override public int npcHp(Instance instance, String token) { Integer hp = npcHealth.get(token); return hp == null ? 0 : hp; }
    @Override public void door(Instance instance, String id, Tile tile, boolean open) {
        openDoors.put(instance.id + ":" + id, open); if (open) doorOpens++; else doorCloses++;
    }
    @Override public void startMotion(String account, Instance instance, Motion motion) { motions.put(account, motion); }
    @Override public void finishMotion(String account, Instance instance, Motion motion) {
        Motion existing = motions.get(account); if (existing != null && existing.permit.equals(motion.permit)) motions.remove(account);
    }
    @Override public boolean cancelMotion(String account, Instance instance, Motion motion, Tile safeTile) {
        if (cancelDelay > 0) { cancelDelay--; return false; }
        Person p = people.get(account);
        if (p != null && instance.id.equals(p.instance) && !safeTile.equals(p.tile)) {
            if (!service.allowScriptRelocation(account, instance.runId, p.tile, safeTile, motion.permit))
                throw new IllegalStateException("rollback not authorized");
            p.tile = safeTile;
        }
        finishMotion(account, instance, motion);
        return true;
    }
    @Override public void freeze(String account, UUID runId, boolean freeze) {
        if (freeze && failFreezeOnce) { failFreezeOnce = false; throw new IllegalStateException("injected freeze failure"); }
        if (!freeze && failUnfreezeOnce) { failUnfreezeOnce = false; throw new IllegalStateException("injected unfreeze failure"); }
        Person p = people.get(account); if (p == null) return;
        if (freeze) { if (p.frozenBy != null && !p.frozenBy.equals(runId)) throw new IllegalStateException("other controller owns freeze"); p.frozenBy = runId; }
        else if (runId.equals(p.frozenBy)) p.frozenBy = null;
    }
    @Override public void message(String account, String text) {
        if (failMessageOnce) { failMessageOnce = false; throw new IllegalStateException("injected message failure"); }
        if (events.size() < 10000) events.add("MESSAGE " + account + " " + text);
    }
    @Override public void prepareExtraction(String account, String transaction, Rewards.Bundle bundle) {
        assertWorldThread();
        Person person = people.get(account);
        if (person == null || person.frozenBy == null || !transaction.equals("dungeon:" + person.frozenBy + ":extraction"))
            throw new IllegalStateException("Extraction intent must belong to the frozen run");
        String key = account + "|" + transaction;
        extractionAttempts.add(key);
        if (failPrepareBeforeCommit > 0) { failPrepareBeforeCommit--; throw new IllegalStateException("intent storage unavailable before commit"); }
        Rewards.Bundle previous = extractionIntents.get(key);
        if (previous != null && !previous.equals(bundle)) throw new IllegalStateException("Retry changed frozen intent payload");
        extractionIntents.put(key, bundle);
        if (failPrepareAfterCommitOnce) { failPrepareAfterCommitOnce = false; throw new IllegalStateException("intent commit acknowledgement lost"); }
    }
    @Override public void awardAgilityXpOnce(String account, String award, int xp) {
        if (xpReceipts.add(account + ":" + award)) people.get(account).agilityXp += xp;
    }
    @Override public void hit(String account, int damage, DamageKind kind, String source) {
        hitCalls++;
        Person p = people.get(account); p.hp = Math.max(0, p.hp - damage);
        if (p.hp == 0) { DungeonService.RunView run = service.inspect(account); if (run != null) service.onPlayerDeath(account, run.runId); }
    }
    @Override public void telegraph(Instance instance, String id, List<Tile> tiles, DamageKind kind, long due) {
        telegraphs.put(id, new ArrayList<Tile>(tiles)); events.add("TELEGRAPH " + id + " " + tiles.size() + " tiles");
    }
    @Override public void clearTelegraph(Instance instance, String hazard) { telegraphs.remove(hazard); }
    @Override public void openedChest(Instance instance, int slot, Tile tile) { events.add("CHEST " + slot); }
    @Override public void audit(String event, String account, UUID runId, String detail) {
        if (events.size() < 10000) events.add("AUDIT " + event + " " + runId + " " + detail);
    }
}
