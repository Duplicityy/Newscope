package io.ruin.model.entity.player.autonomous;

import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.combat.Combat;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.shared.listeners.TeleportListener;
import io.ruin.model.item.Item;
import io.ruin.model.map.Position;
import io.ruin.model.skills.herblore.Potion;
import io.ruin.model.stat.StatType;
import rsps.sim.BuildPolicy;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

/** Controlled staging fixtures only; never packaged or invoked on production. */
public final class SafetyIntegrationChecks {
    private SafetyIntegrationChecks() {}

    @SuppressWarnings("unchecked")
    public static void run(String command) throws Exception {
        check(Boolean.getBoolean("kronos.adventurers.test"), "staging only");
        Field actorsField = Adventurers.class.getDeclaredField("actors");
        actorsField.setAccessible(true);
        Map<String, Adventurer> actors = (Map<String, Adventurer>) actorsField.get(null);
        Adventurer p = actors.values().stream().filter(a -> a.isOnline() && !a.isLocked()
                && !a.getCombat().isDead() && !a.getCombat().isDefending(17) && !a.isPoisoned()
                && a.getCombat().getTarget() == null && a.quarantineReason == null)
                .findFirst().orElseThrow(() -> new AssertionError("no safe unlocked staging actor"));
        if (command.equals("memory")) { memory(); return; }
        if (command.equals("food")) food(p);
        else if (command.equals("threat")) threat(p);
        else throw new IllegalArgumentException(command);
    }

    private static void food(Adventurer p) throws Exception {
        Item[] inventory = p.getInventory().getItems().clone();
        int hp = p.getHp();
        double[] xp = xp(p);
        long now = Server.currentTick();
        long eatEnd = now + p.eatDelay.remaining(), karamEnd = now + p.karamDelay.remaining(), potEnd = now + p.potDelay.remaining();
        Field attackDelay = Combat.class.getDeclaredField("attackDelayTicks");
        attackDelay.setAccessible(true);
        int savedAttackDelay = attackDelay.getInt(p.getCombat());
        try {
            p.resetActions(true, true, true);
            clearInventory(p);
            check(AdventurerSafety.isRecoveryItem(new Item(361)), "normal tuna not recognized");
            check(!AdventurerSafety.isRecoveryItem(new Item(362)), "noted tuna incorrectly edible");
            check(!AdventurerSafety.isRecoveryItem(new Item(712)), "unimplemented tea incorrectly counted as recovery");
            check(!AdventurerSafety.isRecoveryItem(new Item(Potion.PRAYER.vialIds[0])), "prayer-only drink incorrectly counted as food");
            check(AdventurerSafety.isRecoveryItem(new Item(Potion.SARADOMIN_BREW.vialIds[0])), "implemented healing drink missing");
            p.getInventory().set(0, new Item(361));
            p.getInventory().set(1, new Item(361));
            p.setHp(3);
            p.eatDelay.delay(3);
            p.karamDelay.reset();
            p.potDelay.reset();
            AdventurerSafety blocked = new AdventurerSafety(p);
            check(!blocked.heal() && p.getHp() == 3 && AdventurerSafety.recoveryCount(p) == 2,
                    "food delay bypassed or rejected click reported as heal");
            p.eatDelay.reset();
            AdventurerSafety eating = new AdventurerSafety(p);
            check(eating.heal(), "ordinary eating failed");
            check(p.getHp() == Math.min(p.getMaxHp(), 13) && AdventurerSafety.recoveryCount(p) == 1,
                    "normal food quantity or healing changed");
            p.setHp(3);
            check(!new AdventurerSafety(p).heal() && AdventurerSafety.recoveryCount(p) == 1,
                    "repeated safety call bypassed normal eat cooldown");
            clearInventory(p);
            AdventurerSafety empty = new AdventurerSafety(p);
            empty.tick(Server.currentTick());
            check(empty.needsRecovery() && !empty.isBusy(), "passive low health deadlocks bank resupply");
            check(Arrays.equals(xp, xp(p)), "safety healing granted XP");
            System.out.println("CHECK PASS safety food: actual healing and consumption, normal cooldown, rejected-click detection, unimplemented/noted/non-healing items excluded, passive resupply, no XP");
        } finally {
            clearInventory(p);
            for (int i = 0; i < inventory.length; i++) p.getInventory().set(i, inventory[i]);
            p.setHp(hp);
            p.eatDelay.setEnd(eatEnd); p.karamDelay.setEnd(karamEnd); p.potDelay.setEnd(potEnd);
            attackDelay.setInt(p.getCombat(), savedAttackDelay);
        }
    }

    private static void threat(Adventurer p) throws Exception {
        NPC enemy = null;
        for (NPC n : World.npcs) if (n != null && n.getCombat() != null && !n.isRemoved()
                && !n.getCombat().isDead() && n.getCombat().getClass() == io.ruin.model.activities.miscpvm.BasicCombat.class) { enemy = n; break; }
        check(enemy != null, "no basic enemy fixture");
        Entity attacker = p.getCombat().lastAttacker;
        Field lastDefend = Combat.class.getDeclaredField("lastDefendTick");
        lastDefend.setAccessible(true);
        long defendTick = lastDefend.getLong(p.getCombat());
        Position position = p.getPosition().copy();
        TeleportListener listener = p.teleportListener;
        BuildPolicy.Archetype archetype = p.archetype;
        int hp = p.getHp();
        double[] xp = xp(p);
        AdventurerMemory originalMemory = p.memory;
        try {
            p.resetActions(true, true, true);
            p.setHp(p.getMaxHp());
            p.memory = new AdventurerMemory(); // Fixture aggression must not persist as world evidence.
            // Movement.teleport only queues a locked teleport. This synchronous
            // fixture must start unlocked, so move its position and restore it in
            // the same world-thread call without running a movement tick.
            p.getPosition().set(enemy.getAbsX(), enemy.getAbsY(), enemy.getHeight());
            p.teleportListener = player -> false; // Test the genuine rejection boundary.
            p.archetype = BuildPolicy.Archetype.SKILLER;
            p.getCombat().updateLastDefend(enemy);
            p.getCombat().setTarget(enemy); // Deliberately inject the forbidden stale action.
            AdventurerSafety safety = new AdventurerSafety(p);
            safety.tick(Server.currentTick());
            check(p.getCombat().getTarget() == null, "skiller retained an attacking target");
            check(safety.escapeAttempts == 1 && safety.escapes == 0 && !p.isLocked(), "teleport listener bypassed");
            check(safety.needsRecovery() && safety.isBusy(), "unexpected threat not gated");
            safety.tick(Server.currentTick());
            check(safety.escapeAttempts == 1, "same-tick processing repeated escape");
            for (io.ruin.model.skills.prayer.Prayer prayer : ProtectionPrayerPolicy.PROTECTION_PRAYERS)
                check(!p.getPrayer().isActive(prayer), "skiller protection activated");
            check(Arrays.equals(xp, xp(p)), "safety response granted combat XP");
            System.out.println("CHECK PASS safety threat: same-tick unexpected-threat response, stale skiller attack cancelled, ordinary teleport rejection respected, no retry flood, no XP");
        } finally {
            p.teleportListener = listener;
            p.archetype = archetype;
            p.memory = originalMemory;
            p.getCombat().lastAttacker = attacker;
            lastDefend.setLong(p.getCombat(), defendTick);
            p.getPosition().set(position.getX(), position.getY(), position.getZ());
            p.setHp(hp);
        }
    }

    private static void memory() {
        AdventurerMemory memory = new AdventurerMemory();
        memory.avoidThreat("npc:4276", 3125, 3253, 0);
        check(memory.encounters.isEmpty(), "nonfatal retreat invented a death");
        check(memory.avoidsLocation(3126, 3252, 0), "unexpected threat location not avoided");
        check(!memory.avoidsLocation(3126, 3252, 1), "threat leaked to another plane");
        AdventurerMemory saved = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(memory), AdventurerMemory.class);
        check(saved.avoidsLocation(3126, 3252, 0), "threat evidence did not survive serialization");
        for (int i = 0; i < 600; i++) saved.tick();
        check(!saved.avoidsLocation(3126, 3252, 0), "nonfatal cooldown never expires");
        saved.avoidThreat("npc:4276", 3125, 3253, 0);
        check(saved.avoidedThreats.values().iterator().next().cooldownTicks == 1200, "repeated hazard cooldown did not adapt");
        for (int i = 0; i < 40; i++) saved.avoidThreat("npc:" + i, i * 32, 3200, 0);
        check(saved.avoidedThreats.size() == 32, "threat memory unbounded");
        check(saved.encounters.isEmpty(), "retreats changed death count");
        System.out.println("CHECK PASS safety memory: observed nonfatal locations, separate death evidence, expiry, repeated-hazard adaptation, persistence, 32-entry bound");
    }

    private static void clearInventory(Adventurer p) { for (int i = 0; i < p.getInventory().getItems().length; i++) p.getInventory().set(i, null); }
    private static double[] xp(Adventurer p) { double[] values = new double[StatType.values().length]; for (StatType t : StatType.values()) values[t.ordinal()] = p.getStats().get(t).experience; return values; }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
