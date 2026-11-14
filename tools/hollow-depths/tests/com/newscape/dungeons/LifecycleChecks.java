package com.newscape.dungeons;

import com.newscape.dungeons.DungeonLayout.*;
import com.newscape.dungeons.DungeonPorts.*;
import com.newscape.dungeons.demo.HeadlessWorld;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Exploit/failure tests of actual service transitions, not a second implementation of the service. */
public final class LifecycleChecks {
    private static int checks;
    private static final Executor DIRECT = Runnable::run;
    private LifecycleChecks() { }
    public static final class MemoryStore implements Rewards.Store {
        final Map<String, Rewards.Bundle> receipts = new HashMap<String, Rewards.Bundle>();
        final Map<String, Rewards.Bundle> balances = new HashMap<String, Rewards.Bundle>();
        public int calls;
        public boolean throwAfterFirstCommit;
        public HeadlessWorld intentWorld;
        @Override public synchronized Rewards.DepositResult depositOnce(String account, String tx, Rewards.Bundle bundle) throws IOException {
            calls++; String key = account + "|" + tx;
            if (intentWorld != null && !bundle.equals(intentWorld.extractionIntents.get(key)))
                throw new AssertionError("Reward store ran without the matching durable intent");
            Rewards.Bundle prior = receipts.get(key);
            if (prior != null) {
                if (!prior.equals(bundle)) throw new IOException("changed retry payload");
                return Rewards.DepositResult.ALREADY_APPLIED;
            }
            balances.put(account, balance(account).plus(bundle)); receipts.put(key, bundle);
            if (throwAfterFirstCommit) { throwAfterFirstCommit = false; throw new IOException("simulated lost commit acknowledgement"); }
            return Rewards.DepositResult.APPLIED;
        }
        @Override public synchronized Rewards.Bundle balance(String account) {
            Rewards.Bundle b = balances.get(account); return b == null ? Rewards.Bundle.empty() : b;
        }
    }
    private static final class Fixture {
        final HeadlessWorld world = new HeadlessWorld();
        final MemoryStore store;
        final DungeonService service;
        Fixture(long seed, int agility) { this(seed, agility, new MemoryStore()); }
        Fixture(long seed, int agility, MemoryStore store) {
            this.store = store; store.intentWorld = world; world.addPlayer("alice", agility);
            service = new DungeonService(world, store, quietConfig(), DIRECT, DIRECT, () -> seed);
            world.attach(service);
        }
        DungeonService.RunView enter() { return world.enterRun("alice", Rewards.Tier.VETERAN); }
    }
    public static void run() throws Exception {
        portalAndOwnership(); trapRoutes(); agilityReceipt(); interruptedDoor();
        chestsAndDeaths(); extractionRetries(); extractionIntentOrdering(); cleanupFailures(); asynchronousEntryAndDisconnect();
        generationAndBuildFailures(); deathBeforeInstanceReservation(); globalBudgets(); synchronousSpawnDeath(); realWalkingRun();
        delayedRollbackAndResume(); bossTelegraphs(); npcEventFailures();
        System.out.println("LifecycleChecks passed: " + checks + " assertions across portal, movement, traps, deaths, rewards and cleanup.");
    }
    private static void portalAndOwnership() throws Exception {
        Fixture f = new Fixture(42, 1);
        f.world.people.get("alice").atPortal = false;
        check(f.service.clickEntrancePortal("alice", Rewards.Tier.NOVICE) == DungeonService.Result.DENIED, "remote portal click");
        f.world.people.get("alice").atPortal = true;
        DungeonService.RunView a = f.enter();
        check(f.service.clickEntrancePortal("alice", Rewards.Tier.NOVICE) == DungeonService.Result.BUSY, "one run/account");
        f.world.addPlayer("bob", 99);
        DungeonService.RunView b = f.world.enterRun("bob", Rewards.Tier.NOVICE);
        check(!a.instanceId.equals(b.instanceId) && !a.runId.equals(b.runId), "private lease identity");
        check(f.service.clickChest("bob", a.runId, 0) == DungeonService.Result.DENIED, "foreign chest claim");
        check(!f.service.allowPlayerStep("bob", a.runId, b.layout.entry, b.layout.entry, null), "foreign movement");
        check(f.service.clickExit("alice", a.runId) == DungeonService.Result.DENIED, "premature boss extraction");
        final boolean[] rejected = {false};
        Thread wrong = new Thread(() -> { try { f.service.inspect("alice"); } catch (IllegalStateException expected) { rejected[0] = true; } });
        wrong.start(); wrong.join(); check(rejected[0], "wrong-thread access rejected");
        f.service.beginMaintenanceDrain(); check(f.service.isDrained(), "drain clears two leases");
    }
    private static void trapRoutes() {
        Fixture f = new Fixture(9001, 1); DungeonService.RunView run = f.enter(); TrapSpawn trap = firstTrap(run.layout);
        f.world.placeFixture("alice", trap.trigger);
        check(!f.service.allowPlayerStep("alice", run.runId, trap.trigger, trap.landing, null), "cannot ordinary-walk gap");
        check(f.service.clickAgilityTrap("alice", run.runId, trap.slot) == DungeonService.Result.DENIED, "required agility enforced");
        check(f.world.actors.isEmpty(), "bypass mobs remain lazy for outside player");
        f.world.placeFixture("alice", trap.gauntlet.entranceOutside);
        String id = "trap:" + trap.slot + ":entry";
        check(!f.service.allowPlayerStep("alice", run.runId, trap.gauntlet.entranceOutside, trap.gauntlet.entranceDoor, null), "closed door blocks normal step");
        check(f.service.clickBypassDoor("alice", run.runId, id) == DungeonService.Result.ACCEPTED, "click starts passage");
        Motion motion = f.world.motions.get("alice");
        check(motion != null && f.world.openDoors.get(run.instanceId + ":" + id), "door opens only after click");
        check(!f.service.allowPlayerStep("alice", run.runId, trap.gauntlet.entranceOutside, trap.gauntlet.entranceDoor, UUID.randomUUID()), "forged motion nonce");
        f.world.advance();
        check(f.world.people.get("alice").tile.equals(trap.gauntlet.entranceDoor), "actual intermediate walk tile");
        for (NpcTicket npc : f.world.actors.values()) if (npc.gauntletGuard) {
            check(!f.service.allowNpcFootprint(run.runId, npc.token, Collections.singleton(trap.gauntlet.entranceDoor)), "guard cannot occupy even open door");
            check(!f.service.allowNpcFootprint(run.runId, npc.token, Arrays.asList(npc.tile, trap.gauntlet.entranceOutside)), "entire footprint constrained");
        }
        f.world.advance(3);
        check(f.world.people.get("alice").tile.equals(trap.gauntlet.entranceInside), "script walks inside");
        check(!f.world.openDoors.get(run.instanceId + ":" + id), "door closes behind player");
        check(f.world.motions.isEmpty(), "motion lock released");
        check(!f.world.actors.isEmpty(), "combat bypass populated");
        for (NpcTicket npc : f.world.actors.values()) if (npc.gauntletGuard) check(f.service.allowCombat(run.runId, npc.token, "alice"), "inside corridor combat");
        f.world.placeFixture("alice", trap.gauntlet.exitOutside);
        for (NpcTicket npc : f.world.actors.values()) if (npc.gauntletGuard) check(!f.service.allowCombat(run.runId, npc.token, "alice"), "outside cannot attack/receive corridor projectile");
        f.service.abandon("alice", run.runId); check(f.world.actors.isEmpty(), "bypass NPC cleanup");
    }
    private static void agilityReceipt() {
        Fixture f = new Fixture(55, 99); DungeonService.RunView run = f.enter(); TrapSpawn trap = firstTrap(run.layout);
        ContentCatalog.TrapDef definition = new ContentCatalog().traps.get(trap.trapKey);
        f.world.placeFixture("alice", trap.trigger);
        cross(f, run, trap, trap.landing, definition.cooldownTicks);
        int xp = f.world.people.get("alice").agilityXp;
        check(xp == definition.xp, "successful crossing awards configured XP");
        check(f.world.doorOpens == 0 && f.world.actors.isEmpty(), "agility route leaves bypass sealed and unspawned");
        cross(f, run, trap, trap.trigger, definition.cooldownTicks);
        check(f.world.people.get("alice").agilityXp == xp, "back-and-forth XP farm blocked");
        f.world.people.get("alice").currentAgility = 0; f.world.advance(definition.cooldownTicks + 3);
        check(f.service.clickAgilityTrap("alice", run.runId, trap.slot) == DungeonService.Result.DENIED, "drained level enforced");
        f.service.abandon("alice", run.runId);
    }
    private static void cross(Fixture f, DungeonService.RunView run, TrapSpawn trap, Tile destination, int wait) {
        for (int attempts = 0; attempts < 40; attempts++) {
            f.world.advance(wait + 3); f.world.people.get("alice").hp = 99;
            f.service.clickAgilityTrap("alice", run.runId, trap.slot); f.world.advance(3);
            if (f.world.people.get("alice").tile.equals(destination)) return;
        }
        throw new AssertionError("deterministic agility attempts never succeeded");
    }
    private static void interruptedDoor() {
        Fixture f = new Fixture(8080, 1); DungeonService.RunView run = f.enter(); TrapSpawn trap = firstTrap(run.layout);
        f.world.placeFixture("alice", trap.gauntlet.entranceOutside);
        String id = "trap:" + trap.slot + ":entry";
        f.service.clickBypassDoor("alice", run.runId, id); f.world.advance();
        f.world.people.get("alice").online = false; f.service.onLogout("alice");
        check(f.world.people.get("alice").tile.equals(trap.gauntlet.entranceOutside), "mid-door logout restores safe bank");
        check(!f.world.openDoors.get(run.instanceId + ":" + id), "logout closes door");
        check(f.world.motions.isEmpty(), "logout cancels only its motion");
        f.world.advance(10); f.world.people.get("alice").online = true; f.service.onLogin("alice"); f.world.advance(4);
        check(f.service.inspect("alice").runId.equals(run.runId), "reconnect resumes same run");
        f.world.advance(3); f.service.clickBypassDoor("alice", run.runId, id); f.world.advance();
        f.world.motions.clear(); // simulate an engine movement that never completes
        f.world.advance(13);
        check(f.world.people.get("alice").tile.equals(trap.gauntlet.entranceOutside), "stalled door times out safely");
        check(!f.world.openDoors.get(run.instanceId + ":" + id), "stalled door reclosed");
        f.service.abandon("alice", run.runId);
    }
    private static void chestsAndDeaths() {
        Fixture f = new Fixture(94, 1); DungeonService.RunView run = f.enter();
        Room treasure = firstRoom(run.layout, RoomKind.TREASURE);
        ChestSpawn chest = treasure.chests.get(0);
        check(f.service.clickChest("alice", run.runId, chest.slot) == DungeonService.Result.DENIED, "unvisited remote chest");
        f.world.placeFixture("alice", treasure.center()); f.world.defeatRoom("alice", treasure);
        f.world.placeFixture("alice", chest.tile); f.world.advance(3);
        check(f.service.clickChest("alice", run.runId, chest.slot) == DungeonService.Result.ACCEPTED, "chest opens");
        Rewards.Bundle once = f.service.inspect("alice").unbankedLoot;
        check(f.service.clickChest("alice", run.runId, chest.slot) == DungeonService.Result.ALREADY_DONE, "repeat click ignored");
        check(once.equals(f.service.inspect("alice").unbankedLoot), "repeat keeps same escrow");
        check(f.store.balance("alice").amounts.isEmpty(), "unextracted treasure not credited");
        f.world.people.get("alice").plane = 1;
        check(f.service.clickChest("alice", run.runId, treasure.chests.get(1).slot) == DungeonService.Result.DENIED, "wrong plane denied");
        f.world.people.get("alice").plane = 0;
        check(f.service.onPlayerDeath("alice", run.runId), "safe death consumed before vanilla death");
        check(f.service.inspect("alice") == null && f.world.people.get("alice").instance == null, "death evacuates");
        check(f.store.balance("alice").amounts.isEmpty(), "death forfeits escrow");
    }
    private static void extractionRetries() {
        MemoryStore uncertain = new MemoryStore(); uncertain.throwAfterFirstCommit = true;
        Fixture f = new Fixture(2001, 1, uncertain); DungeonService.RunView run = prepareExtraction(f);
        Rewards.Bundle expected = run.unbankedLoot;
        check(f.service.clickExit("alice", run.runId) == DungeonService.Result.ACCEPTED, "boss extraction accepted");
        f.world.advance();
        check(f.service.inspect("alice").state == DungeonService.State.REWARD_RETRY, "uncertain acknowledgement retries");
        check(f.world.people.get("alice").frozenBy.equals(run.runId), "freeze held during commit uncertainty");
        f.world.advance(55);
        check(f.service.inspect("alice") == null && uncertain.calls == 2, "same transaction retried then cleaned");
        check(uncertain.balance("alice").equals(expected), "uncertain retry does not double credit");
        check(f.world.people.get("alice").frozenBy == null, "extraction unlocks owned freeze");

        Fixture freeze = new Fixture(2002, 1); DungeonService.RunView r = prepareExtraction(freeze);
        freeze.world.failFreezeOnce = true; freeze.service.clickExit("alice", r.runId);
        check(freeze.service.inspect("alice").state == DungeonService.State.REWARD_RETRY, "freeze error does not fabricate in-flight worker");
        freeze.world.advance(55); check(freeze.service.inspect("alice") == null, "freeze failure recovered");

        Fixture notify = new Fixture(2003, 1); r = prepareExtraction(notify);
        notify.service.clickExit("alice", r.runId); notify.world.failMessageOnce = true; notify.world.advance();
        check(notify.service.inspect("alice") == null, "notification failure cannot strand committed claim");
    }
    private static void extractionIntentOrdering() {
        Fixture failed = new Fixture(2011, 1);
        DungeonService.RunView run = prepareExtraction(failed);
        Rewards.Bundle expected = run.unbankedLoot;
        failed.world.failPrepareBeforeCommit = 2;
        check(failed.service.clickExit("alice", run.runId) == DungeonService.Result.ACCEPTED, "extraction remains pending when intent save fails");
        check(failed.store.calls == 0 && failed.world.extractionIntents.isEmpty(), "no deposit before durable extraction intent");
        check(failed.service.inspect("alice").state == DungeonService.State.REWARD_RETRY, "intent save failure retains retry state");
        check(failed.service.abandon("alice", run.runId) == DungeonService.Result.BUSY, "cannot abandon unresolved extraction intent");
        failed.world.advance(51);
        check(failed.store.calls == 0, "second intent failure still prevents mailbox submission");
        failed.world.advance(55);
        check(failed.service.inspect("alice") == null, "intent retry eventually completes extraction");
        check(failed.store.calls == 1 && failed.store.balance("alice").equals(expected), "recovered intent deposits exactly once");
        check(failed.world.extractionAttempts.size() == 3 && new HashSet<String>(failed.world.extractionAttempts).size() == 1,
                "intent retries keep the exact account and run transaction identity");
        check(failed.world.extractionIntents.values().iterator().next().equals(expected), "intent payload stays frozen through retries");

        Fixture uncertain = new Fixture(2012, 1);
        DungeonService.RunView other = prepareExtraction(uncertain);
        uncertain.world.failPrepareAfterCommitOnce = true;
        uncertain.service.clickExit("alice", other.runId);
        check(uncertain.store.calls == 0 && uncertain.world.extractionIntents.size() == 1,
                "uncertain intent acknowledgement cannot start an unconfirmed deposit");
        uncertain.world.advance(55);
        check(uncertain.service.inspect("alice") == null && uncertain.store.calls == 1,
                "same persisted intent retries before the mailbox write");
        check(uncertain.store.balance("alice").equals(other.unbankedLoot), "uncertain intent cannot duplicate rewards");
    }
    private static DungeonService.RunView prepareExtraction(Fixture f) {
        DungeonService.RunView run = f.enter();
        for (Room room : run.layout.rooms) if (room.kind == RoomKind.GUARDIAN) {
            f.world.placeFixture("alice", room.center()); f.world.defeatRoom("alice", room);
        }
        check(f.service.inspect("alice").guardians == 3, "three independent guardian sigils");
        Room boss = run.layout.room(run.layout.bossRoomId);
        f.world.placeFixture("alice", boss.center()); f.world.defeatRoom("alice", boss);
        check(f.service.inspect("alice").bossDefeated, "final boss defeat");
        for (ChestSpawn chest : boss.chests) {
            f.world.placeFixture("alice", chest.tile); f.world.advance(3);
            check(f.service.clickChest("alice", run.runId, chest.slot) == DungeonService.Result.ACCEPTED, "boss chest claim");
        }
        f.world.placeFixture("alice", boss.center()); f.world.advance(3);
        return f.service.inspect("alice");
    }
    private static void cleanupFailures() {
        Fixture f = new Fixture(3000, 1); DungeonService.RunView run = f.enter();
        f.world.failEvacuateOnce = true; f.world.reenterOnEvacuate = true;
        f.service.abandon("alice", run.runId);
        check(f.service.inspect("alice").state == DungeonService.State.CLOSING, "lease retained until evacuation confirmed");
        f.world.advance(6); check(f.service.inspect("alice") == null && f.world.released == 1, "reentrant cleanup safely completes once");

        Fixture unlock = new Fixture(3001, 1); run = prepareExtraction(unlock);
        unlock.world.failUnfreezeOnce = true; unlock.service.clickExit("alice", run.runId); unlock.world.advance();
        check(unlock.service.inspect("alice").state == DungeonService.State.CLOSING, "unlock failure retries after map release");
        unlock.world.advance(11); check(unlock.service.inspect("alice") == null && unlock.world.released == 1, "released lease not reused during cleanup retry");

        Fixture foreign = new Fixture(3002, 1); run = foreign.enter();
        UUID otherController = UUID.randomUUID(); foreign.world.people.get("alice").frozenBy = otherController;
        foreign.service.abandon("alice", run.runId);
        check(otherController.equals(foreign.world.people.get("alice").frozenBy), "cleanup cannot unlock another activity");
    }
    private static void asynchronousEntryAndDisconnect() {
        Fixture f = new Fixture(4000, 1); f.world.entryDelay = 8; DungeonService.RunView run = f.enter();
        check(run.state == DungeonService.State.ACTIVE, "queued teleport arrival confirmed before ACTIVE");
        f.world.people.get("alice").online = false; f.service.onLogout("alice");
        f.world.advance(301);
        check(f.service.inspect("alice") == null && f.world.people.get("alice").instance == null, "disconnect expiry recovers offline character");
        f.world.people.get("alice").online = true; f.service.onLogin("alice");
        check(f.world.people.get("alice").instance == null, "expired instance cannot resurrect");
    }
    private static void generationAndBuildFailures() {
        HeadlessWorld world = new HeadlessWorld(); world.addPlayer("alice", 1);
        Executor reject = task -> { throw new RejectedExecutionException("capacity"); };
        DungeonService service = new DungeonService(world, new MemoryStore(), quietConfig(), reject, DIRECT, () -> 1);
        world.attach(service); service.clickEntrancePortal("alice", Rewards.Tier.NOVICE);
        check(service.inspect("alice") == null, "generation rejection frees reservation");
        Fixture f = new Fixture(5000, 1); f.world.invalidMap = true;
        f.service.clickEntrancePortal("alice", Rewards.Tier.NOVICE); f.world.advance(50);
        check(f.service.inspect("alice") == null && f.world.people.get("alice").instance == null, "collision mismatch refuses entry");
        check(f.world.released == 1, "failed map lease reclaimed");
        Fixture deployment = new Fixture(5001, 1); deployment.world.deploymentProblem = "unmapped 242 cache";
        check(deployment.service.clickEntrancePortal("alice", Rewards.Tier.NOVICE) == DungeonService.Result.DENIED, "unverified revision fail closed");
    }
    private static void deathBeforeInstanceReservation() {
        HeadlessWorld world = new HeadlessWorld(); world.addPlayer("alice", 1);
        List<Runnable> plans = new ArrayList<Runnable>();
        DungeonService service = new DungeonService(world, new MemoryStore(), quietConfig(),
                plans::add, DIRECT, () -> 5010L);
        world.attach(service);
        check(service.clickEntrancePortal("alice", Rewards.Tier.NOVICE) == DungeonService.Result.ACCEPTED,
                "entry can await a background generation result");
        DungeonService.RunView run = service.inspect("alice");
        check(run.state == DungeonService.State.GENERATING && run.instanceId == null && plans.size() == 1,
                "death fixture has no instance to evacuate or release");

        // Native poison can kill while generation is pending. A failed first thaw must
        // retain ownership and retry even though no instance evacuation ever occurs.
        world.people.get("alice").hp = 0;
        world.failUnfreezeOnce = true;
        check(service.onPlayerDeath("alice", run.runId), "pre-reservation death is consumed by the dungeon");
        check(service.inspect("alice").state == DungeonService.State.CLOSING
                        && run.runId.equals(world.people.get("alice").frozenBy),
                "death owns its freeze until cleanup receives an unfreeze acknowledgement");
        check(!world.failUnfreezeOnce && world.activeLeaseCount() == 0 && world.released == 0,
                "cleanup attempts death thaw without inventing an instance evacuation");
        world.advance(11);
        check(service.inspect("alice") == null && world.people.get("alice").frozenBy == null,
                "pre-reservation death retries cleanup and releases its owned lock");

        plans.remove(0).run(); world.advance();
        check(service.inspect("alice") == null && world.activeLeaseCount() == 0 && world.released == 0,
                "late generation callback cannot resurrect a dead run or reserve a lease");
    }
    private static void globalBudgets() {
        Fixture f = new Fixture(6000, 1);
        for (int i = 0; i < 8; i++) { String name = "budget" + i; f.world.addPlayer(name, 1); f.service.clickEntrancePortal(name, Rewards.Tier.NOVICE); }
        f.world.advance(120);
        check(f.world.maxChunksBuiltInTick <= quietConfig().chunksPerTick, "global chunk construction budget");
        f.service.beginMaintenanceDrain(); check(f.service.isDrained(), "many-building-runs drain");
    }
    private static void synchronousSpawnDeath() {
        Fixture f = new Fixture(7000, 1); DungeonService.RunView run = f.enter();
        f.world.synchronousDeathOnSpawn = true;
        Room guardian = firstRoom(run.layout, RoomKind.GUARDIAN);
        f.world.placeFixture("alice", guardian.center()); f.world.advance(5);
        check(f.service.inspect("alice").guardians == 1, "synchronous death does not leave queued actor ghost");
        f.service.abandon("alice", run.runId);
    }
    private static void realWalkingRun() {
        Fixture f = new Fixture(121212, 1); DungeonService.RunView run = f.enter();
        // All approaches use actual allowed cardinal steps and click-to-pass door scripts.
        for (Room room : run.layout.rooms) if (room.kind == RoomKind.GUARDIAN) {
            f.world.walkTo("alice", room.center()); f.world.defeatRoom("alice", room);
        }
        Room boss = run.layout.room(run.layout.bossRoomId);
        f.world.walkTo("alice", boss.center()); f.world.defeatRoom("alice", boss);
        for (ChestSpawn chest : boss.chests) {
            f.world.walkTo("alice", chest.tile); f.world.advance(3);
            check(f.service.clickChest("alice", run.runId, chest.slot) == DungeonService.Result.ACCEPTED, "walked end-to-end boss reward");
        }
        f.world.walkTo("alice", boss.center()); f.world.advance(3);
        check(f.service.clickExit("alice", run.runId) == DungeonService.Result.ACCEPTED, "Agility1 full run extractable");
        f.world.advance();
        check(!f.store.balance("alice").amounts.isEmpty() && f.service.inspect("alice") == null, "real-path run commits and releases");
    }
    private static void delayedRollbackAndResume() {
        Fixture f = new Fixture(8081, 1); DungeonService.RunView run = f.enter(); TrapSpawn trap = firstTrap(run.layout);
        f.world.placeFixture("alice", trap.gauntlet.entranceOutside);
        String door = "trap:" + trap.slot + ":entry";
        f.service.clickBypassDoor("alice", run.runId, door); f.world.advance();
        Motion motion = f.world.motions.get("alice"); f.world.cancelDelay = 4;
        f.world.people.get("alice").online = false; f.service.onLogout("alice");
        f.world.people.get("alice").online = true; f.service.onLogin("alice");
        check(f.service.inspect("alice").state == DungeonService.State.DISCONNECTED, "resumption waits for queued rollback");
        check(!f.service.allowPlayerStep("alice", run.runId, trap.gauntlet.entranceDoor, trap.gauntlet.entranceInside, motion.permit), "stale forward script denied while rollback pending");
        f.world.advance(8);
        check(f.service.inspect("alice").state == DungeonService.State.ACTIVE, "deferred resume request eventually activates");
        check(f.world.people.get("alice").tile.equals(trap.gauntlet.entranceOutside), "async rollback bank preserved on resume");
        check(!f.world.openDoors.get(run.instanceId + ":" + door), "async rollback closes door after safe arrival");
        f.service.abandon("alice", run.runId);
    }
    private static void bossTelegraphs() {
        HeadlessWorld w = new HeadlessWorld(); w.addPlayer("alice", 99);
        DungeonService s = new DungeonService(w, new MemoryStore(), new DungeonConfig(), DIRECT, DIRECT, () -> 424242);
        w.attach(s); DungeonService.RunView run = w.enterRun("alice", Rewards.Tier.NOVICE);
        for (Room room : run.layout.rooms) if (room.kind == RoomKind.GUARDIAN) { w.placeFixture("alice", room.center()); w.defeatRoom("alice", room); }
        Room boss = run.layout.room(run.layout.bossRoomId); w.placeFixture("alice", boss.center());
        for (int i = 0; i < 20 && w.telegraphs.isEmpty(); i++) w.advance();
        check(!w.telegraphs.isEmpty(), "boss marks dangerous tiles before damage");
        List<Tile> marked = w.telegraphs.values().iterator().next();
        Tile safe = null;
        for (int x = boss.gridX * 24 + 2; x < boss.gridX * 24 + 22 && safe == null; x++)
            for (int y = boss.gridY * 24 + 2; y < boss.gridY * 24 + 22 && safe == null; y++) {
                Tile candidate = new Tile(x,y); if (run.layout.walkable(candidate) && !marked.contains(candidate)) safe = candidate;
            }
        check(safe != null, "telegraph retains safe arena tiles");
        w.placeFixture("alice", safe); int before = w.hitCalls; w.advance(4);
        check(w.hitCalls == before, "moving out of telegraph avoids hit");
        for (int i = 0; i < 20 && w.telegraphs.isEmpty(); i++) w.advance();
        Tile danger = w.telegraphs.values().iterator().next().get(0); w.placeFixture("alice", danger); w.advance(4);
        check(w.hitCalls == before + 1, "standing in telegraph receives one hit");
        s.pulse(s.tick()); check(w.hitCalls == before + 1, "duplicate world tick cannot duplicate hit");
        NpcTicket bossNpc = null;
        for (NpcTicket npc : w.actors.values()) if (npc.boss) bossNpc = npc;
        check(bossNpc != null, "boss actor registered");
        w.npcHealth.put(bossNpc.token, bossNpc.stats.hp / 2); w.advance();
        check(w.events.stream().anyMatch(e -> e.contains("fractures the floor")), "phase two health threshold");
        w.npcHealth.put(bossNpc.token, bossNpc.stats.hp / 5); w.advance();
        check(w.events.stream().anyMatch(e -> e.contains("final shadow storm")), "phase three health threshold");
        w.npcHealth.put(bossNpc.token, 0); s.onNpcDeath(run.runId, bossNpc.token, "alice");
        check(w.telegraphs.isEmpty(), "boss death cancels outstanding hazards");
        s.abandon("alice", run.runId);
    }
    public static DungeonConfig quietConfig() {
        return new DungeonConfig(32,100,600,3600,300,900,8,16,32,12,3,15,64,false);
    }
    private static void npcEventFailures() {
        Fixture f = new Fixture(9000,1); DungeonService.RunView run = f.enter();
        Room guardian = firstRoom(run.layout,RoomKind.GUARDIAN);
        f.world.placeFixture("alice",guardian.center()); f.world.advance(2);
        NpcTicket actor = f.world.actors.values().iterator().next();
        check(f.service.onNpcDeath(run.runId,actor.token,"alice") == DungeonService.Result.DENIED,"living NPC cannot yield fake death credit");
        f.world.npcHealth.put(actor.token,0);
        check(f.service.onNpcDeath(run.runId,actor.token,"intruder") == DungeonService.Result.DENIED,"foreign NPC credit denied");
        f.world.failDespawnCount = 1;
        check(f.service.onNpcDeath(run.runId,actor.token,"alice") == DungeonService.Result.DENIED,"despawn failure safely closes affected run");
        check(f.service.inspect("alice") == null && f.world.actors.isEmpty(),"failed death cleanup retains and removes all handles");
        Fixture spawn = new Fixture(9002,1); DungeonService.RunView other = spawn.enter();
        spawn.world.failSpawnOnce = true;
        spawn.world.placeFixture("alice",firstRoom(other.layout,RoomKind.COMBAT).center()); spawn.world.advance();
        check(spawn.service.inspect("alice") == null && spawn.world.released == 1,"spawn exception cannot leak a map lease");
    }
    private static Room firstRoom(DungeonLayout layout, RoomKind kind) {
        for (Room r : layout.rooms) if (r.kind == kind) return r;
        throw new AssertionError("missing room kind " + kind);
    }
    private static TrapSpawn firstTrap(DungeonLayout layout) { return firstRoom(layout, RoomKind.TRAP).traps.get(0); }
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception { run(); }
}
