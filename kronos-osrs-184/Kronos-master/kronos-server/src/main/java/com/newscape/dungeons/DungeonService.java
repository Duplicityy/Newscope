package com.newscape.dungeons;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import com.newscape.dungeons.DungeonLayout.*;
import com.newscape.dungeons.DungeonPorts.*;

/**
 * Authoritative solo run state machine. Call public methods on the Kronos world thread.
 * Packet handlers must pass authenticated account/object identities, never packet-supplied stats.
 * Only immutable map planning and reward persistence run on workers. Completion callbacks are
 * marshalled back by pulse(). No worker touches players, clipping, NPCs or map leases.
 */
public final class DungeonService {
    public enum State { GENERATING, BUILDING, ENTERING, ACTIVE, DISCONNECTED, EXTRACTING, REWARD_RETRY, CLOSING }
    public enum Result { ACCEPTED, DENIED, BUSY, ALREADY_DONE }

    public static final class RunView {
        public final UUID runId;
        public final State state;
        public final DungeonLayout layout;
        public final String instanceId;
        public final int guardians, discoveredRooms, openedChests, livingNpcs;
        public final boolean bossDefeated;
        public final Rewards.Bundle unbankedLoot;
        private RunView(Run r) {
            runId = r.id; state = r.state; layout = r.layout;
            instanceId = r.instance == null ? null : r.instance.id;
            guardians = r.guardians.size(); discoveredRooms = r.visited.size();
            openedChests = r.openedChests.size(); livingNpcs = r.npcs.size();
            bossDefeated = r.bossDefeated; unbankedLoot = r.escrow;
        }
    }

    private static final class Actor {
        final NpcTicket ticket;
        final Gauntlet gauntlet;
        boolean processingDeath;
        Actor(NpcTicket ticket, Gauntlet gauntlet) { this.ticket = ticket; this.gauntlet = gauntlet; }
    }
    private static final class Door {
        final String id;
        final Tile tile, outside, inside;
        final TrapSpawn trap;
        boolean open;
        Door(String id, Tile tile, Tile outside, Tile inside, TrapSpawn trap) {
            this.id = id; this.tile = tile; this.outside = outside; this.inside = inside; this.trap = trap;
        }
    }
    private static final class Action {
        final Motion motion;
        final Door door;
        final TrapSpawn trap;
        final Tile safe, destination;
        int index;
        boolean cancelling;
        Action(Motion motion, Door door, TrapSpawn trap) {
            this.motion = motion; this.door = door; this.trap = trap;
            this.safe = motion.path.get(0); this.destination = motion.path.get(motion.path.size() - 1);
        }
    }
    private static final class Hazard {
        final String id;
        final Set<Tile> tiles;
        final long due;
        final int damage;
        final DamageKind kind;
        Hazard(String id, Set<Tile> tiles, long due, int damage, DamageKind kind) {
            this.id = id; this.tiles = tiles; this.due = due; this.damage = damage; this.kind = kind;
        }
    }
    private static final class Run {
        final UUID id = UUID.randomUUID();
        final String account;
        final long seed, createdAt;
        final int combatAtEntry;
        final Rewards.Tier tier;
        final SplittableRandom trapRandom, bossRandom;
        State state = State.GENERATING;
        long stateSince, startedAt, lastActivity, lastInteraction = -1000, disconnectedAt, retryAt;
        DungeonLayout layout;
        Instance instance;
        Tile lastSafe;
        final Set<Integer> visited = new HashSet<Integer>(), cleared = new HashSet<Integer>();
        final Set<Integer> guardians = new HashSet<Integer>(), openedChests = new HashSet<Integer>();
        final Set<Integer> activatedGauntlets = new HashSet<Integer>(), completedTraps = new HashSet<Integer>();
        final Set<Integer> usedShrines = new HashSet<Integer>();
        final Map<Integer, Long> trapCooldowns = new HashMap<Integer, Long>();
        final LinkedHashMap<String, Actor> npcs = new LinkedHashMap<String, Actor>();
        final ArrayDeque<Actor> spawnQueue = new ArrayDeque<Actor>();
        final Map<String, Door> doors = new LinkedHashMap<String, Door>();
        final List<Hazard> hazards = new ArrayList<Hazard>();
        Action action;
        boolean bossDefeated, rewardInFlight, cleanupInProgress, hasEntered, freezeHeld, resumeRequested;
        long nextBossAttack;
        int hazardSequence, bossPhase, rewardFailures;
        String bossToken, closeReason;
        Rewards.Bundle escrow = Rewards.Bundle.empty(), extractionBundle;
        Run(String account, long seed, long tick, int combatLevel, Rewards.Tier tier) {
            this.account = account; this.seed = seed; createdAt = stateSince = lastActivity = tick;
            this.combatAtEntry = combatLevel; this.tier = tier;
            trapRandom = new SplittableRandom(seed ^ 0x747261705f726e67L);
            bossRandom = new SplittableRandom(seed ^ 0x626f73735f726e67L);
        }
    }

    private final GamePort port;
    private final Rewards.Store store;
    private final DungeonConfig config;
    private final ContentCatalog catalog;
    private final DungeonGenerator generator;
    private final Rewards rewards = new Rewards();
    private final Executor generationExecutor, persistenceExecutor;
    private final LongSupplier seedSource;
    private final Map<String, Run> byAccount = new LinkedHashMap<String, Run>();
    private final Map<UUID, Run> byId = new HashMap<UUID, Run>();
    private final Map<String, Long> entryCooldowns = new HashMap<String, Long>();
    private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<Runnable>();
    private long now = -1;
    private int spawnRoundRobin, buildRoundRobin, remainingBuildBudget, perBuildSlice;
    private boolean draining;
    private long auditFailures;

    public DungeonService(GamePort port, Rewards.Store store, DungeonConfig config,
            Executor generationExecutor, Executor persistenceExecutor) {
        this(port, store, config, generationExecutor, persistenceExecutor, new SecureRandom()::nextLong);
    }

    /** seedSource exists for deterministic tests/admin reproducibility; never wire it to a player packet. */
    public DungeonService(GamePort port, Rewards.Store store, DungeonConfig config,
            Executor generationExecutor, Executor persistenceExecutor, LongSupplier seedSource) {
        this.port = Objects.requireNonNull(port); this.store = Objects.requireNonNull(store);
        this.config = Objects.requireNonNull(config); this.generationExecutor = Objects.requireNonNull(generationExecutor);
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor); this.seedSource = Objects.requireNonNull(seedSource);
        this.catalog = new ContentCatalog(); this.generator = new DungeonGenerator(catalog);
    }

    /** One call per server tick; duplicate/stale ticks do no work. A run seed is chosen ONLY by the server. */
    public void pulse(long serverTick) {
        thread();
        if (serverTick <= now) return;
        now = serverTick;
        remainingBuildBudget = config.chunksPerTick;
        for (int i = 0; i < config.maxCallbacksPerTick; i++) {
            Runnable callback = callbacks.poll(); if (callback == null) break;
            try { callback.run(); }
            catch (RuntimeException callbackFailure) { auditFailures++; }
        }
        List<Run> snapshot = new ArrayList<Run>(byAccount.values());
        int builders = 0; for (Run run : snapshot) if (run.state == State.BUILDING) builders++;
        perBuildSlice = Math.max(1, config.chunksPerTick / Math.max(1, builders));
        if (!snapshot.isEmpty()) Collections.rotate(snapshot, Math.floorMod(buildRoundRobin++, snapshot.size()));
        for (Run run : snapshot) {
            if (!byId.containsKey(run.id)) continue;
            try { tickRun(run); }
            catch (RuntimeException failure) {
                audit("run_fault", run, failure.getClass().getSimpleName() + ": " + failure.getMessage());
                if (run.state == State.EXTRACTING || run.state == State.REWARD_RETRY) {
                    // A receipt may already be durable. Never turn an uncertain commit into a new grant.
                    run.state = State.REWARD_RETRY; run.retryAt = now + 50;
                } else close(run, "A dungeon service error ended this run; carried equipment is retained.");
            }
        }
        // A GLOBAL budget, fairly rotated across runs. Hidden rooms never spawn at portal entry.
        int budget = config.npcSpawnsPerTick;
        if (!snapshot.isEmpty()) {
            int start = Math.floorMod(spawnRoundRobin++, snapshot.size());
            for (int i = 0; i < snapshot.size() && budget > 0; i++) {
                Run run = snapshot.get((start + i) % snapshot.size());
                if (run.state != State.ACTIVE || !byId.containsKey(run.id)) continue;
                int quota = Math.min(2, budget);
                while (quota-- > 0 && budget > 0 && !run.spawnQueue.isEmpty()
                        && run.npcs.size() < config.maxLiveNpcsPerRun) {
                    Actor actor = run.spawnQueue.peek();
                    try {
                        // Ticket is installed first: synchronous engine callbacks must see its ownership.
                        run.spawnQueue.remove();
                        run.npcs.put(actor.ticket.token, actor);
                        port.spawnNpc(run.instance, actor.ticket);
                        budget--;
                    } catch (RuntimeException failure) {
                        close(run, "An encounter could not be created safely."); break;
                    }
                }
            }
        }
        if (now % 100 == 0) entryCooldowns.entrySet().removeIf(e -> e.getValue() <= now);
    }

    public Result clickEntrancePortal(final String account, final Rewards.Tier tier) {
        thread(); Objects.requireNonNull(account); Objects.requireNonNull(tier);
        if (now < 0) throw new IllegalStateException("pulse once before accepting players");
        if (draining) return deny(account, "The dungeon is closing for maintenance.");
        if (byAccount.containsKey(account)) return Result.BUSY;
        Long cooldown = entryCooldowns.get(account);
        if (cooldown != null && cooldown > now) return Result.BUSY;
        if (byAccount.size() >= config.maxInstances) return deny(account, "All dungeon slots are occupied. Try again shortly.");
        String deployment = port.deploymentDenial();
        if (deployment != null) return deny(account, "Dungeons are not ready: " + deployment);
        PlayerView player = port.player(account);
        if (player == null || !player.online || player.hp == 0) return Result.DENIED;
        String denial = port.entryDenial(account, tier);
        if (denial != null) return deny(account, denial);
        final Run run = new Run(account, seedSource.getAsLong(), now, player.combatLevel, tier);
        byAccount.put(account, run); byId.put(run.id, run);
        entryCooldowns.put(account, now + config.entryCooldownTicks);
        message(account, "The Hollow Depths are taking shape. Your dungeon belongs only to you.");
        try {
            generationExecutor.execute(() -> {
                try {
                    final DungeonLayout plan = generator.generate(run.seed);
                    callbacks.add(() -> acceptPlan(run, plan));
                } catch (final RuntimeException failure) {
                    callbacks.add(() -> { if (current(run)) close(run, "Dungeon generation failed safely. Please try again."); });
                }
            });
        } catch (RuntimeException rejected) { close(run, "The dungeon builder is busy. Please try again."); }
        audit("run_requested", run, "tier=" + tier);
        return Result.ACCEPTED;
    }

    private void acceptPlan(Run run, DungeonLayout plan) {
        if (!current(run) || run.state != State.GENERATING) return;
        try {
            PlayerView player = port.player(run.account);
            String denial = port.entryDenial(run.account, run.tier);
            if (player == null || !player.online || player.hp == 0 || denial != null) {
                close(run, "Entry was cancelled because your character is no longer ready."); return;
            }
            run.layout = plan; run.lastSafe = plan.entry;
            run.instance = port.reserve(run.id, run.account, plan);
            if (run.instance == null || !run.id.equals(run.instance.runId))
                throw new IllegalStateException("instance lease identity mismatch");
            for (Room room : plan.rooms) for (TrapSpawn trap : room.traps) {
                Gauntlet g = trap.gauntlet;
                Door a = new Door("trap:" + trap.slot + ":entry", g.entranceDoor, g.entranceOutside, g.entranceInside, trap);
                Door b = new Door("trap:" + trap.slot + ":exit", g.exitDoor, g.exitOutside, g.exitInside, trap);
                run.doors.put(a.id, a); run.doors.put(b.id, b);
            }
            run.state = State.BUILDING; run.stateSince = now;
        } catch (RuntimeException failure) { close(run, "The instance could not be reserved safely."); }
    }

    private void tickRun(Run run) {
        switch (run.state) {
            case GENERATING:
                if (now - run.stateSince > config.generationTimeoutTicks) close(run, "Dungeon generation timed out.");
                return;
            case BUILDING:
                if (now - run.stateSince > config.buildTimeoutTicks) { close(run, "Dungeon map construction timed out."); return; }
                if (remainingBuildBudget == 0) return;
                int allocation = Math.min(perBuildSlice, remainingBuildBudget);
                remainingBuildBudget -= allocation;
                if (!port.buildStep(run.instance, run.layout, allocation)) return;
                String mapError = port.validateBuiltMap(run.instance, run.layout);
                if (mapError != null) { audit("map_rejected", run, mapError); close(run, "This dungeon map failed its safety checks."); return; }
                PlayerView ready = port.player(run.account);
                if (ready == null || !ready.online || ready.hp == 0 || port.entryDenial(run.account, run.tier) != null) {
                    close(run, "Entry was cancelled because your character is no longer ready."); return;
                }
                for (Door door : run.doors.values()) port.door(run.instance, door.id, door.tile, false);
                port.recordRecovery(run.account, run.id, run.instance);
                run.state = State.ENTERING; run.stateSince = now;
                port.enter(run.account, run.instance, run.layout.entry);
                return;
            case ENTERING:
                PlayerView arriving = port.player(run.account);
                if (arriving == null || !arriving.online || arriving.hp == 0) { close(run, "Dungeon arrival was cancelled."); return; }
                if (!inInstance(run, arriving) || !run.lastSafe.equals(arriving.tile)) {
                    if (now - run.stateSince > config.buildTimeoutTicks) close(run, "Dungeon arrival timed out.");
                    return;
                }
                run.state = State.ACTIVE; run.lastActivity = now;
                if (!run.hasEntered) { run.startedAt = now; run.hasEntered = true; }
                activateRoom(run, roomAt(run, arriving.tile).id);
                message(run.account, "Find three guardian sigils, defeat the final boss, then claim your unbanked treasure at the exit.");
                audit("run_entered", run, "rooms=" + run.layout.rooms.size() + ", layout=" + run.layout.fingerprint());
                return;
            case DISCONNECTED:
                if (run.action != null) cancelAction(run);
                if (now - run.disconnectedAt >= config.disconnectGraceTicks || now - run.startedAt >= config.maxRunTicks) {
                    close(run, "Your disconnected dungeon expired. Unbanked treasure was lost.");
                    return;
                }
                if (run.resumeRequested && run.action == null) onLogin(run.account);
                return;
            case EXTRACTING:
                return; // No world mutation/hits while an uncertain durable receipt is in flight.
            case REWARD_RETRY:
                if (!run.rewardInFlight && now >= run.retryAt) submitReward(run);
                return;
            case CLOSING:
                if (now >= run.retryAt) cleanup(run);
                return;
            case ACTIVE:
                break;
            default: throw new IllegalStateException("Unhandled run state");
        }
        PlayerView p = port.player(run.account);
        if (p == null || !p.online) { disconnect(run); return; }
        if (!inInstance(run, p)) { close(run, "You left the dungeon. Unbanked treasure was lost."); return; }
        if (p.hp == 0) { close(run, "You fell in the dungeon. Equipment is retained; unbanked treasure is lost."); return; }
        if (now - run.startedAt >= config.maxRunTicks) { close(run, "The dungeon has collapsed after its time limit."); return; }
        if (now - run.lastActivity >= config.idleTicks) { close(run, "Your inactive dungeon has closed."); return; }
        if (run.action != null) {
            Action a = run.action;
            if (a.cancelling) cancelAction(run);
            else if (a.destination.equals(p.tile) && a.index == a.motion.path.size() - 1) finishAction(run);
            else if (now >= a.motion.expiresAtTick) cancelAction(run);
        }
        resolveHazards(run);
        if (run.state == State.ACTIVE && config.enableBossSpecials) tickBoss(run);
    }

    /** Called after an approved authoritative movement step, not on arbitrary click requests. */
    public void onPlayerMoved(String account) {
        thread(); Run run = byAccount.get(account); if (run == null || run.state != State.ACTIVE) return;
        PlayerView p = port.player(account); if (!inInstance(run, p) || p.tile == null) return;
        run.lastActivity = now;
        if (run.action != null) {
            Action a = run.action;
            if (a.cancelling) { if (a.safe.equals(p.tile)) cancelAction(run); return; }
            if (a.index + 1 < a.motion.path.size() && p.tile.equals(a.motion.path.get(a.index + 1))) a.index++;
            if (a.destination.equals(p.tile) && a.index == a.motion.path.size() - 1) finishAction(run);
        }
        if (run.layout.walkable(p.tile) && !isDoorTile(run, p.tile) && run.action == null) run.lastSafe = p.tile;
        Room room = roomAt(run, p.tile);
        if (room != null) activateRoom(run, room.id);
    }

    /** Apply before EVERY movement type, including forced walk/run. Normal clipping checks still follow. */
    public boolean allowPlayerStep(String account, UUID runId, Tile from, Tile to, UUID motionPermit) {
        thread(); Run run = owned(account, runId);
        if (run == null || run.state != State.ACTIVE || from == null || to == null) return false;
        PlayerView p = port.player(account);
        if (!inInstance(run, p) || !from.equals(p.tile)) return false;
        if (run.action != null) {
            Action a = run.action;
            return !a.cancelling && now < a.motion.expiresAtTick && a.motion.permit.equals(motionPermit)
                    && a.index + 1 < a.motion.path.size() && from.equals(a.motion.path.get(a.index))
                    && to.equals(a.motion.path.get(a.index + 1));
        }
        if (motionPermit != null || from.chebyshevDistance(to) != 1 || !run.layout.walkable(from) || !run.layout.walkable(to)) return false;
        if (isDoorTile(run, from) || isDoorTile(run, to)) return false; // even visually open doors require the script permit
        // No diagonal corner-cutting around barriers or door apertures.
        if (from.x != to.x && from.y != to.y) {
            Tile a = new Tile(from.x, to.y), b = new Tile(to.x, from.y);
            if (!run.layout.walkable(a) || !run.layout.walkable(b) || isDoorTile(run, a) || isDoorTile(run, b)) return false;
        }
        Room target = roomAt(run, to);
        return target != null && (target.id != run.layout.bossRoomId || run.guardians.size() >= 3);
    }

    /** Teleport/forced relocation gate; normal spells inside runs are denied except the registered motion. */
    public boolean allowScriptRelocation(String account, UUID runId, Tile from, Tile to, UUID permit) {
        thread();
        Run run = owned(account, runId);
        if (run != null && run.action != null && run.action.cancelling) {
            Action a = run.action; PlayerView p = port.player(account);
            return p != null && run.instance != null && run.instance.id.equals(p.instanceId)
                    && from != null && from.equals(p.tile) && a.motion.path.contains(from)
                    && a.safe.equals(to) && a.motion.permit.equals(permit);
        }
        return allowPlayerStep(account, runId, from, to, permit);
    }

    /** Narrow gate for the server-requested asynchronous instance arrival, never a normal teleport spell. */
    public boolean allowEntryArrival(String account, UUID runId, String instanceId, int plane, Tile destination) {
        thread(); Run run = owned(account, runId);
        return run != null && run.state == State.ENTERING && run.instance != null
                && plane == 0 && run.instance.id.equals(instanceId) && run.lastSafe.equals(destination)
                && now - run.stateSince <= config.buildTimeoutTicks;
    }

    public Result clickBypassDoor(String account, UUID runId, String doorId) {
        thread(); Run run = ownedActive(account, runId); if (run == null) return Result.DENIED;
        Door door = run.doors.get(doorId); if (door == null) return Result.DENIED;
        if (!canInteract(run)) return Result.BUSY;
        PlayerView p = port.player(account);
        // The object handler paths to one of these adjacent approach tiles before invoking this method.
        Tile target;
        if (door.outside.equals(p.tile)) target = door.inside;
        else if (door.inside.equals(p.tile)) target = door.outside;
        else return deny(account, "Walk to the door before opening it.");
        activateGauntlet(run, door.trap);
        Motion motion = new Motion(UUID.randomUUID(), MotionKind.DOOR_WALK,
                Arrays.asList(p.tile, door.tile, target), now + config.doorTimeoutTicks);
        run.action = new Action(motion, door, null); run.lastInteraction = run.lastActivity = now;
        try {
            door.open = true; port.door(run.instance, door.id, door.tile, true);
            port.startMotion(account, run.instance, motion);
            audit("door_passage_started", run, door.id);
            return Result.ACCEPTED;
        } catch (RuntimeException failure) { cancelAction(run); close(run, "The door could not be traversed safely."); return Result.DENIED; }
    }

    public Result clickAgilityTrap(String account, UUID runId, int trapSlot) {
        thread(); Run run = ownedActive(account, runId); if (run == null) return Result.DENIED;
        TrapSpawn trap = findTrap(run, trapSlot); if (trap == null) return Result.DENIED;
        if (!canInteract(run)) return Result.BUSY;
        PlayerView p = port.player(account);
        Tile destination;
        if (trap.trigger.equals(p.tile)) destination = trap.landing;
        else if (trap.landing.equals(p.tile)) destination = trap.trigger;
        else return deny(account, "Stand at the edge of the obstacle to attempt it.");
        ContentCatalog.TrapDef def = catalog.traps.get(trap.trapKey);
        // Base level is the unlock; drained current level must ALSO meet it. Temporary boosts cannot unlock.
        if (p.baseAgility < def.requiredAgility || p.currentAgility < def.requiredAgility)
            return deny(account, "This crossing needs " + def.requiredAgility + " Agility. Use the guarded route through the doors.");
        Long next = run.trapCooldowns.get(trapSlot); if (next != null && next > now) return Result.BUSY;
        run.trapCooldowns.put(trapSlot, now + def.cooldownTicks);
        run.lastInteraction = run.lastActivity = now;
        int effective = Math.min(p.baseAgility, p.currentAgility);
        if (run.trapRandom.nextInt(1000) >= def.successPermille(effective)) {
            int damage = def.damageAt(effective, run.trapRandom.nextInt());
            message(account, "You mistime the " + def.name.toLowerCase(Locale.ROOT) + " and are struck by the trap.");
            port.hit(account, damage, DamageKind.TRAP, run.id + ":trap:" + trapSlot + ":" + now);
            return Result.ACCEPTED;
        }
        Motion motion = new Motion(UUID.randomUUID(), MotionKind.AGILITY_CROSSING,
                Arrays.asList(p.tile, destination), now + config.doorTimeoutTicks);
        run.action = new Action(motion, null, trap);
        try { port.startMotion(account, run.instance, motion); }
        catch (RuntimeException failure) { cancelAction(run); return Result.DENIED; }
        return Result.ACCEPTED;
    }

    private void finishAction(Run run) {
        Action a = run.action; if (a == null) return;
        if (a.door != null) { port.door(run.instance, a.door.id, a.door.tile, false); a.door.open = false; }
        port.finishMotion(run.account, run.instance, a.motion);
        run.action = null; run.lastSafe = a.destination;
        if (a.trap != null) {
            ContentCatalog.TrapDef def = catalog.traps.get(a.trap.trapKey);
            // Adapter receipt and XP save are one transaction; retrying cannot farm XP.
            if (!run.completedTraps.contains(a.trap.slot)) {
                port.awardAgilityXpOnce(run.account, run.id + ":agility:" + a.trap.slot, def.xp);
                run.completedTraps.add(a.trap.slot);
            }
            message(run.account, "You cross the " + def.name.toLowerCase(Locale.ROOT) + " safely.");
        }
    }

    private boolean cancelAction(Run run) {
        Action a = run.action; if (a == null) return true;
        // Keep its permit until cancellation has restored a legal tile.
        a.cancelling = true;
        if (!port.cancelMotion(run.account, run.instance, a.motion, a.safe)) return false;
        if (a.door != null) { port.door(run.instance, a.door.id, a.door.tile, false); a.door.open = false; }
        run.action = null; run.lastSafe = a.safe;
        return true;
    }

    private void activateRoom(Run run, int roomId) {
        Room room = run.layout.room(roomId);
        if (room.kind == RoomKind.BOSS && run.guardians.size() < 3) return;
        if (!run.visited.add(roomId)) return;
        for (NpcSpawn spawn : room.npcs) queueActor(run, room, spawn, null);
        if (room.npcs.isEmpty()) run.cleared.add(roomId);
        if (room.kind == RoomKind.TRAP) message(run.account, "Agility offers a quick crossing. The longer passage is guarded; click either door to walk through it.");
        if (room.kind == RoomKind.BOSS) run.nextBossAttack = now + 12;
    }

    private void activateGauntlet(Run run, TrapSpawn trap) {
        if (!run.activatedGauntlets.add(trap.slot)) return;
        Room room = roomAt(run, trap.trigger);
        for (NpcSpawn guard : trap.gauntlet.guards) queueActor(run, room, guard, trap.gauntlet);
        message(run.account, "The sealed passage is full of guardians. They cannot pursue you beyond its doors.");
    }

    private void queueActor(Run run, Room room, NpcSpawn spawn, Gauntlet gauntlet) {
        ContentCatalog.NpcDef def = catalog.npcs.get(spawn.archetypeKey);
        if (def == null) throw new IllegalStateException("unmapped archetype " + spawn.archetypeKey);
        String token = run.id + ":npc:" + spawn.slot;
        double tier = new double[] {0.75, 1.0, 1.3}[run.tier.ordinal()];
        double scaling = tier * (0.75 + run.combatAtEntry / 252.0);
        NpcStats stats = new NpcStats(Math.max(10, (int) Math.round(def.hp * scaling)),
                Math.min(60, Math.max(1, (int) Math.round(def.maxHit * scaling))), def.attackTicks);
        boolean boss = room.kind == RoomKind.BOSS && def.boss;
        NpcTicket ticket = new NpcTicket(run.id, token, run.account, def.key, room.id, spawn.tile, stats, gauntlet != null, boss);
        run.spawnQueue.add(new Actor(ticket, gauntlet));
        if (boss) run.bossToken = token;
    }

    /** Must be called by authenticated NPC-death handling BEFORE generic drops, respawn and kill rewards. */
    public Result onNpcDeath(UUID runId, String token, String creditedAccount) {
        thread(); Run run = byId.get(runId);
        if (run == null || (run.state != State.ACTIVE && run.state != State.DISCONNECTED)) return Result.DENIED;
        Actor actor = run.npcs.get(token);
        if (actor == null || actor.processingDeath) return Result.ALREADY_DONE;
        if (!run.account.equals(creditedAccount) || port.npcHp(run.instance, token) > 0) return Result.DENIED;
        actor.processingDeath = true;
        try { port.despawnNpc(run.instance, token); }
        catch (RuntimeException failure) { close(run, "An encounter could not be cleaned up safely."); return Result.DENIED; }
        run.npcs.remove(token); run.lastActivity = now;
        if (actor.ticket.boss) { run.bossDefeated = true; clearHazards(run); message(run.account, "The final seal breaks. Open the boss chest, then claim your treasure at the exit."); }
        if (actor.gauntlet == null && roomClear(run, actor.ticket.roomId)) {
            run.cleared.add(actor.ticket.roomId);
            Room room = run.layout.room(actor.ticket.roomId);
            if (room.kind == RoomKind.GUARDIAN && run.guardians.add(room.id))
                message(run.account, "Guardian sigil recovered: " + run.guardians.size() + "/3.");
        }
        audit("npc_defeated", run, token);
        return Result.ACCEPTED;
    }

    private boolean roomClear(Run run, int roomId) {
        for (Actor a : run.npcs.values()) if (a.gauntlet == null && a.ticket.roomId == roomId) return false;
        for (Actor a : run.spawnQueue) if (a.gauntlet == null && a.ticket.roomId == roomId) return false;
        return run.visited.contains(roomId);
    }

    /** Check EVERY occupied tile, not just the NPC southwest anchor. Forced steps/teleports use this too. */
    public boolean allowNpcFootprint(UUID runId, String token, Collection<Tile> footprint) {
        thread(); Run run = byId.get(runId); if (run == null || run.state != State.ACTIVE || footprint == null || footprint.isEmpty()) return false;
        Actor actor = run.npcs.get(token); if (actor == null) return false;
        Room room = run.layout.room(actor.ticket.roomId);
        for (Tile tile : footprint) if (!run.layout.walkable(tile) || isDoorTile(run, tile)
                || !room.contains(tile) || (actor.gauntlet != null && !actor.gauntlet.contains(tile))) return false;
        return true;
    }

    /** Apply to launch AND impact for player, pet, NPC and reflected damage. Engine LOS checks still apply. */
    public boolean allowCombat(UUID runId, String npcToken, String playerAccount) {
        thread(); Run run = ownedActive(playerAccount, runId); if (run == null || run.action != null) return false;
        Actor actor = run.npcs.get(npcToken); if (actor == null) return false;
        PlayerView p = port.player(playerAccount);
        if (p.hp == 0 || !run.layout.room(actor.ticket.roomId).contains(p.tile)) return false;
        return actor.gauntlet == null || actor.gauntlet.contains(p.tile);
    }

    public Result clickChest(String account, UUID runId, int chestSlot) {
        thread(); Run run = ownedActive(account, runId); if (run == null) return Result.DENIED;
        if (run.openedChests.contains(chestSlot)) return Result.ALREADY_DONE;
        if (!canInteract(run)) return Result.BUSY;
        ChestSpawn chest = null; Room owner = null;
        for (Room room : run.layout.rooms) for (ChestSpawn c : room.chests) if (c.slot == chestSlot) { chest = c; owner = room; }
        if (chest == null || !run.visited.contains(owner.id)) return Result.DENIED;
        PlayerView p = port.player(account);
        if (!adjacentInteractable(run, p.tile, chest.tile)) return Result.DENIED;
        if (chest.requiresClear && !roomClear(run, owner.id)) return deny(account, "Defeat this room's guardians before opening the chest.");
        if (p.baseAgility < chest.requiredAgility) return deny(account, "This optional cache needs " + chest.requiredAgility + " Agility.");
        if (owner.kind == RoomKind.BOSS && !run.bossDefeated) return Result.DENIED;
        Rewards.Bundle roll = rewards.roll(run.seed, "chest:" + chest.slot, Rewards.Table.valueOf(chest.lootTableKey), run.tier);
        Rewards.Bundle next = run.escrow.plus(roll); // validate overflow BEFORE setting opened
        run.openedChests.add(chest.slot); run.escrow = next; run.lastInteraction = run.lastActivity = now;
        port.openedChest(run.instance, chest.slot, chest.tile);
        message(account, "Treasure added to your unbanked haul. Defeat the boss and use the exit to secure it.");
        audit("chest_opened", run, "slot=" + chest.slot);
        return Result.ACCEPTED;
    }

    /** One-use shrine credit. Redemption into healing items is an explicit game-economy integration. */
    public Result clickShrine(String account, UUID runId, int roomId) {
        thread(); Run run = ownedActive(account, runId); if (run == null) return Result.DENIED;
        Room room;
        try { room = run.layout.room(roomId); } catch (IllegalArgumentException missing) { return Result.DENIED; }
        if (room.kind != RoomKind.SHRINE || !run.visited.contains(roomId)) return Result.DENIED;
        if (run.usedShrines.contains(roomId)) return Result.ALREADY_DONE;
        if (!canInteract(run) || !adjacentInteractable(run, port.player(account).tile, room.center())) return Result.DENIED;
        // A shrine grants a fixed small bundle once; healing is an integration item-use policy, not a hidden stat mutation.
        run.usedShrines.add(roomId); run.lastInteraction = run.lastActivity = now;
        run.escrow = run.escrow.plus(Rewards.Bundle.of("SHRINE_ASH", 1));
        message(account, "You collect a blessed ash from the shrine for your secured haul.");
        return Result.ACCEPTED;
    }

    public Result clickExit(String account, UUID runId) {
        thread(); Run run = ownedActive(account, runId); if (run == null) return Result.DENIED;
        if (!canInteract(run)) return Result.BUSY;
        PlayerView p = port.player(account);
        Room room = roomAt(run, p.tile);
        if (room == null || room.id != run.layout.bossRoomId || !run.bossDefeated
                || p.tile.chebyshevDistance(room.center()) > 2) return deny(account, "The extraction portal opens beside the defeated final boss.");
        for (ChestSpawn chest : room.chests) if (!run.openedChests.contains(chest.slot))
            return deny(account, "Open the final treasure chest before extracting.");
        run.extractionBundle = run.escrow;
        clearHazards(run);
        submitReward(run);
        return Result.ACCEPTED;
    }

    private void submitReward(final Run run) {
        if (!current(run) || run.rewardInFlight) return;
        run.state = State.EXTRACTING; run.rewardInFlight = true;
        try {
            run.freezeHeld = true;
            port.freeze(run.account, run.id, true);
            port.prepareExtraction(run.account, "dungeon:" + run.id + ":extraction", run.extractionBundle);
            persistenceExecutor.execute(() -> {
                try {
                    final Rewards.DepositResult result = store.depositOnce(run.account,
                            "dungeon:" + run.id + ":extraction", run.extractionBundle);
                    callbacks.add(() -> {
                        if (!current(run)) return;
                        run.rewardInFlight = false;
                        run.state = State.CLOSING;
                        run.closeReason = "Dungeon complete."; run.retryAt = now;
                        audit("reward_committed", run, result.name());
                        message(run.account, "Your dungeon rewards have been secured in your account's reward mailbox.");
                        cleanup(run);
                    });
                } catch (final Exception failure) {
                    callbacks.add(() -> {
                        if (!current(run)) return;
                        run.rewardInFlight = false; run.state = State.REWARD_RETRY;
                        run.rewardFailures++; run.retryAt = now + Math.min(500, 50L * run.rewardFailures);
                        audit("reward_retry", run, failure.getClass().getSimpleName());
                        message(run.account, "Reward storage is temporarily unavailable. Your claim is held and will retry safely.");
                    });
                }
            });
        } catch (RuntimeException rejected) {
            run.rewardInFlight = false; run.state = State.REWARD_RETRY; run.retryAt = now + 50;
        }
    }

    /** Separate explicit leave/abandon action, not a reward-extraction shortcut. */
    public Result abandon(String account, UUID runId) {
        thread(); Run run = owned(account, runId); if (run == null) return Result.DENIED;
        if (run.state == State.CLOSING) return Result.ALREADY_DONE;
        if (run.state == State.EXTRACTING || run.state == State.REWARD_RETRY) return Result.BUSY;
        close(run, "You abandon the run. Unbanked dungeon loot is lost; carried equipment is retained.");
        return Result.ACCEPTED;
    }

    /** Consume dungeon death before normal inventory loss/grave/ground-drop code. */
    public boolean onPlayerDeath(String account, UUID runId) {
        thread(); Run run = owned(account, runId); if (run == null) return false;
        // The service owns this lock even when death precedes map reservation. All
        // terminal paths, including no-instance generation failure, must release it.
        if (!run.freezeHeld) {
            run.freezeHeld = true;
            port.freeze(account, runId, true);
        }
        if (run.state != State.EXTRACTING && run.state != State.REWARD_RETRY)
            close(run, "You fell in the dungeon. Equipment is retained; unbanked treasure is lost.");
        return true;
    }

    public void onLogout(String account) {
        thread(); Run run = byAccount.get(account); if (run == null) return;
        run.resumeRequested = false;
        if (run.state == State.ACTIVE) disconnect(run);
        else if (run.state == State.GENERATING || run.state == State.BUILDING || run.state == State.ENTERING)
            close(run, "Entry cancelled on logout.");
    }

    private void disconnect(Run run) {
        cancelAction(run); clearHazards(run);
        run.state = State.DISCONNECTED; run.disconnectedAt = now;
        audit("run_disconnected", run, "graceTicks=" + config.disconnectGraceTicks);
    }

    public void onLogin(String account) {
        thread(); Run run = byAccount.get(account);
        if (run != null && run.state == State.DISCONNECTED && now - run.disconnectedAt < config.disconnectGraceTicks
                && now - run.startedAt < config.maxRunTicks) {
            run.resumeRequested = true;
            if (!cancelAction(run)) { message(account, "Finishing your interrupted doorway crossing before resuming."); return; }
            PlayerView p = port.player(account);
            if (p != null && p.online && p.hp > 0) {
                run.resumeRequested = false;
                run.state = State.ENTERING; run.stateSince = now;
                port.enter(account, run.instance, run.lastSafe);
                run.lastActivity = now; run.nextBossAttack = now + 12;
                message(account, "You return to your existing dungeon. Its original timer continues.");
                return;
            }
        }
        if (run != null && (run.state == State.EXTRACTING || run.state == State.REWARD_RETRY)) {
            run.freezeHeld = true; port.freeze(account, run.id, true); return;
        }
        if (run != null) close(run, "Previous dungeon expired.");
        port.recoverOrphanedLogin(account);
    }

    private void close(Run run, String reason) {
        if (!current(run)) return;
        // Never free a lease while a persistence worker could still be crediting it.
        if (run.rewardInFlight) return;
        if (run.state == State.CLOSING) { if (!run.cleanupInProgress) cleanup(run); return; }
        run.closeReason = reason; run.state = State.CLOSING; run.retryAt = now;
        cleanup(run);
    }

    private void cleanup(Run run) {
        if (run.cleanupInProgress || !current(run)) return;
        run.cleanupInProgress = true;
        try {
            if (run.instance != null) {
                if (!cancelAction(run)) { run.retryAt = now + 1; return; }
                clearHazards(run);
                for (Door door : run.doors.values()) if (door.open) {
                    port.door(run.instance, door.id, door.tile, false); door.open = false;
                }
                for (String token : new ArrayList<String>(run.npcs.keySet())) {
                    port.despawnNpc(run.instance, token); run.npcs.remove(token);
                }
                run.spawnQueue.clear();
                if (!port.evacuate(run.instance, run.account)) { run.retryAt = now + 5; return; }
                port.release(run.instance);
                run.instance = null;
            }
            if (run.freezeHeld) { port.freeze(run.account, run.id, false); run.freezeHeld = false; }
            byAccount.remove(run.account); byId.remove(run.id);
            message(run.account, run.closeReason);
            audit("run_closed", run, run.closeReason);
        } catch (RuntimeException failure) {
            run.state = State.CLOSING; run.retryAt = now + 10;
            audit("cleanup_retry", run, failure.getClass().getSimpleName());
        } finally {
            run.cleanupInProgress = false;
        }
    }

    public void beginMaintenanceDrain() {
        thread(); draining = true;
        for (Run run : new ArrayList<Run>(byAccount.values())) {
            if (run.state != State.EXTRACTING && run.state != State.REWARD_RETRY)
                close(run, "The dungeon is closing for maintenance. Carried equipment is retained.");
        }
    }
    public boolean isDrained() { thread(); return byAccount.isEmpty() && callbacks.isEmpty(); }
    public int activeRunCount() { thread(); return byAccount.size(); }
    public long tick() { thread(); return now; }
    public long auditFailureCount() { thread(); return auditFailures; }
    /** Privileged diagnostics only: contains hidden seed/layout. Do not expose to public player packets. */
    public RunView inspect(String account) { thread(); Run r = byAccount.get(account); return r == null ? null : new RunView(r); }
    public List<NpcTicket> livingNpcs(String account) {
        thread(); Run r = byAccount.get(account); List<NpcTicket> result = new ArrayList<NpcTicket>();
        if (r != null) for (Actor a : r.npcs.values()) result.add(a.ticket);
        return Collections.unmodifiableList(result);
    }
    public List<String> doorIds(String account) {
        thread(); Run r = byAccount.get(account);
        return r == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(r.doors.keySet()));
    }

    private void tickBoss(Run run) {
        if (run.bossDefeated || run.bossToken == null || !run.npcs.containsKey(run.bossToken)) return;
        PlayerView p = port.player(run.account); Room room = run.layout.room(run.layout.bossRoomId);
        if (!room.contains(p.tile) || run.action != null) return;
        Actor boss = run.npcs.get(run.bossToken);
        int hp = port.npcHp(run.instance, run.bossToken); if (hp <= 0) return;
        int phase = hp * 100L <= boss.ticket.stats.hp * 35L ? 2 : hp * 100L <= boss.ticket.stats.hp * 70L ? 1 : 0;
        if (phase > run.bossPhase) {
            run.bossPhase = phase;
            message(run.account, phase == 1 ? "The warden fractures the floor. Watch the marked tiles!" : "The warden unleashes its final shadow storm!");
        }
        if (now < run.nextBossAttack || run.hazards.size() >= 3) return;
        Set<Tile> tiles = new LinkedHashSet<Tile>();
        int pattern = Math.floorMod(run.hazardSequence, 3);
        Tile anchor = p.tile;
        for (int x = room.gridX * 24 + 2; x < room.gridX * 24 + 22; x++) {
            for (int y = room.gridY * 24 + 2; y < room.gridY * 24 + 22; y++) {
                Tile tile = new Tile(x, y); if (!run.layout.walkable(tile)) continue;
                int distance = tile.chebyshevDistance(anchor);
                boolean mark = pattern == 0 ? distance <= 1 : pattern == 1 ? x == anchor.x || y == anchor.y : distance == 3 || distance == 4;
                if (mark) tiles.add(tile);
            }
        }
        String id = run.id + ":hazard:" + run.hazardSequence++;
        DamageKind kind = pattern == 1 ? DamageKind.RANGED : DamageKind.MAGIC;
        int damage = 5 + run.tier.ordinal() * 3 + phase * 2 + run.bossRandom.nextInt(4);
        Hazard hazard = new Hazard(id, tiles, now + (phase == 2 ? 3 : 4), damage, kind);
        run.hazards.add(hazard);
        port.telegraph(run.instance, id, new ArrayList<Tile>(tiles), kind, hazard.due);
        run.nextBossAttack = now + (phase == 2 ? 9 : phase == 1 ? 12 : 16);
    }

    private void resolveHazards(Run run) {
        for (Iterator<Hazard> it = run.hazards.iterator(); it.hasNext();) {
            Hazard hazard = it.next(); if (now < hazard.due) continue;
            it.remove(); port.clearTelegraph(run.instance, hazard.id);
            PlayerView p = port.player(run.account);
            if (run.state == State.ACTIVE && !run.bossDefeated && inInstance(run, p) && p.hp > 0
                    && run.action == null && hazard.tiles.contains(p.tile)) {
                port.hit(run.account, hazard.damage, hazard.kind, hazard.id);
                // A hit can synchronously end the run; avoid touching invalid iterators after cleanup.
                if (run.state != State.ACTIVE) return;
            }
        }
    }
    private void clearHazards(Run run) {
        for (Hazard h : run.hazards) port.clearTelegraph(run.instance, h.id);
        run.hazards.clear();
    }

    private boolean adjacentInteractable(Run run, Tile from, Tile object) {
        if (from == null || from.chebyshevDistance(object) > 1) return false;
        if (!run.layout.walkable(from) || isDoorTile(run, from)) return false;
        if (from.x != object.x && from.y != object.y)
            return run.layout.walkable(new Tile(from.x, object.y)) && run.layout.walkable(new Tile(object.x, from.y));
        return true;
    }
    private boolean canInteract(Run run) {
        PlayerView p = port.player(run.account);
        return run.state == State.ACTIVE && run.action == null && inInstance(run, p) && !p.busy && p.hp > 0
                && now - run.lastInteraction >= config.actionCooldownTicks;
    }
    private boolean isDoorTile(Run run, Tile tile) {
        for (Door d : run.doors.values()) if (d.tile.equals(tile)) return true;
        return false;
    }
    private Room roomAt(Run run, Tile tile) {
        for (Room room : run.layout.rooms) if (room.contains(tile)) return room;
        return null;
    }
    private TrapSpawn findTrap(Run run, int slot) {
        for (Room room : run.layout.rooms) for (TrapSpawn trap : room.traps) if (trap.slot == slot) return trap;
        return null;
    }
    private Run owned(String account, UUID id) {
        Run run = byId.get(id); return run != null && run.account.equals(account) ? run : null;
    }
    private Run ownedActive(String account, UUID id) {
        Run run = owned(account, id);
        return run != null && run.state == State.ACTIVE && inInstance(run, port.player(account)) ? run : null;
    }
    private boolean current(Run run) { return byId.get(run.id) == run && byAccount.get(run.account) == run; }
    private boolean inInstance(Run run, PlayerView p) {
        return p != null && p.online && p.plane == 0 && run.instance != null && run.instance.id.equals(p.instanceId) && run.account.equals(p.accountId);
    }
    private Result deny(String account, String reason) { message(account, reason); return Result.DENIED; }
    private void message(String account, String text) {
        try { port.message(account, text); } catch (RuntimeException ignored) { auditFailures++; }
    }
    private void thread() { port.assertWorldThread(); }
    private void audit(String event, Run run, String detail) {
        try { port.audit(event, run.account, run.id, detail); } catch (RuntimeException failure) { auditFailures++; }
    }
}
