package io.ruin.model.entity.player.autonomous;

import io.ruin.Server;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.XpMode;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.stat.StatType;
import rsps.sim.BrainScheduler;
import rsps.sim.ProgressionBrain;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Command-driven checks for an isolated staging server with the real cache/actions loaded.
 * No supplies, XP or healing are injected. Invoke on the world worker; never in production.
 * catalogue; begin:actorId; step (periodically); fishing-check; cooking; step; cooking-check; finish.
 */
public final class FishingCookingIntegrationChecks {
    private static Adventurer actor;
    private static KronosActivityPort originalPort;
    private static Properties originalBrain;
    private static double[] beforeXp;
    private static int beforeRaw, cookingRaw, cookingProducts;
    private static long beforeCoins;
    private static boolean startedWithNet, fullInventoryObserved;
    private static double beforeCookingXp;
    private static Map<String, Integer> savedRangePenalties;
    private static boolean preferNorth, northDoorOpenIssued, northRangeObserved;

    @SuppressWarnings("unchecked")
    private static Map<String, Adventurer> actors() throws Exception {
        Field field = Adventurers.class.getDeclaredField("actors");
        field.setAccessible(true);
        return (Map<String, Adventurer>) field.get(null);
    }

    private static BrainScheduler scheduler() throws Exception {
        Field field = Adventurers.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        return (BrainScheduler) field.get(null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void run(String command) throws Exception {
        check(Boolean.getBoolean("kronos.adventurers.test"), "Staging server only");
        long now = Server.currentTick();
        if (command.equals("catalogue")) {
            for (String fieldName : Arrays.asList("spots", "ranges")) {
                Field field = FishingCookingActivities.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                java.util.List<?> entries = (java.util.List<?>) field.get(null);
                int size = entries.size();
                check(size > 0, "Missing actual " + fieldName);
                System.out.println("FOOD CATALOGUE " + fieldName + "=" + size);
                for (int i = 0; i < Math.min(10, size); i++) {
                    Object entry = entries.get(i);
                    if (entry instanceof NPC) {
                        NPC npc = (NPC) entry;
                        System.out.println("FOOD SPOT id=" + npc.getId() + " position=" + npc.getPosition()
                                + " area=" + npc.fishingArea);
                    } else if (entry instanceof GameObject) {
                        GameObject object = (GameObject) entry;
                        System.out.println("FOOD RANGE id=" + object.id + " name=" + object.getDef().name
                                + " position=" + object.x + "," + object.y + "," + object.z);
                    }
                }
            }
            Field supplier = FishingCookingActivities.class.getDeclaredField("garret");
            supplier.setAccessible(true);
            check(supplier.get(null) != null, "Garret not present in real world");
            for (Adventurer p : actors().values()) {
                check(p.xpMode == XpMode.HARD, "XP mode changed");
                check(p.policy.permits(Collections.singleton("fishing")), "Fishing excluded from build");
                check(p.policy.permits(Collections.singleton("cooking")), "Cooking excluded from build");
            }
            System.out.println("CHECK PASS food catalogue: actual supplier, net spots, cooking actions, build permissions, HARD");
            return;
        }
        if (command.startsWith("begin:")) {
            check(actor == null, "Finish previous food check first");
            Adventurer selected = actors().get(command.substring(6));
            check(selected != null && selected.quarantineReason == null, "Missing or quarantined actor");
            check(!selected.isLocked() && !selected.getCombat().isDead()
                    && !selected.getCombat().isDefending(17), "Actor must be out of combat");
            check(!selected.isPoisoned() && selected.getHp() == selected.getMaxHp(),
                    "Let the actor recover first; food-conservation check requires full health");
            actor = selected;
            originalBrain = actor.brain.checkpoint(now);
            originalPort = actor.port;
            beforeXp = xp(actor);
            beforeRaw = count(actor, 317, 321);
            beforeCoins = (long) actor.getInventory().getAmount(995) + actor.getBank().getAmount(995);
            startedWithNet = count(actor, 303) > 0;
            fullInventoryObserved = false;
            savedRangePenalties = null;
            preferNorth = northDoorOpenIssued = northRangeObserved = false;
            scheduler().remove(actor.actorId);
            actor.resetActions(true, true, true);
            TrainingSites.release(actor);
            actor.port = new KronosActivityPort(actor);
            // Restore an initialized controller so periodic real actor saves remain valid.
            // A non-resting checkpoint keeps the authoritative XP guard enabled while
            // test commands, rather than the scheduler, drive normal activity actions.
            actor.brain = new ProgressionBrain(actor.actorId, actor.port,
                    ProgressionBrain.Config.humanDefaults(600), actor.policy, actor.seed);
            Properties initialized = new Properties();
            initialized.putAll(originalBrain);
            initialized.setProperty("state", "PLANNING");
            actor.brain.restore(initialized, now);
            check(actor.port.start(activity("fishing"), now), "Fishing start rejected");
            System.out.println("FOOD CHECK started actor=" + actor.actorId + " raw=" + beforeRaw + " coins=" + beforeCoins);
            return;
        }
        check(actor != null, "Run begin:actorId first");
        if (command.equals("step")) {
            if (actor.getInventory().getFreeSlots() == 0 && inventoryRaw(actor) > 0) fullInventoryObserved = true;
            ProgressionBrain.ActivityStatus status = actor.port.poll(now);
            check(actor.quarantineReason == null, "Food action quarantined actor: " + actor.quarantineReason);
            if (preferNorth) {
                if (actor.port.description().equals("opening Catherby north kitchen door")) northDoorOpenIssued = true;
                GameObject range = cookingTarget();
                if (range != null) {
                    check(range.x == 2818 && range.y == 3455, "North range unavailable; another range was selected");
                    northRangeObserved = true;
                }
            }
            System.out.println("FOOD STEP status=" + status + " position=" + actor.getPosition()
                    + " detail=" + actor.port.description() + " raw=" + inventoryRaw(actor)
                    + " cooked=" + count(actor, 315, 319) + " free=" + actor.getInventory().getFreeSlots());
        } else if (command.equals("fishing-check")) {
            check(count(actor, 317, 321) > beforeRaw, "No raw fish earned yet");
            check(actor.getStats().get(StatType.Fishing).experience > beforeXp[StatType.Fishing.ordinal()], "No real fishing XP");
            check(count(actor, 303) > 0, "Fishing without a net");
            if (!startedWithNet) check((long) actor.getInventory().getAmount(995) + actor.getBank().getAmount(995)
                    < beforeCoins, "Net did not cost owned coins");
            unchangedCombatXp();
            check(actor.xpMode == XpMode.HARD, "Fishing changed XP mode");
            System.out.println("CHECK PASS fishing: normal catches, paid/owned net, real HARD XP, no combat XP");
        } else if (command.equals("prefer-north")) {
            check(savedRangePenalties == null, "North preference already set");
            check(Tile.getObject(1535, 2815, 3448, 0) != null,
                    "North door already open: this fixture cannot verify an Open action");
            Field field = FishingCookingActivities.class.getDeclaredField("ranges");
            field.setAccessible(true);
            savedRangePenalties = new LinkedHashMap<>();
            boolean found = false;
            for (Object entry : (java.util.List<?>) field.get(null)) {
                GameObject range = (GameObject) entry;
                String key = range.originalId + "@" + range.x + "," + range.y;
                savedRangePenalties.put(key, actor.memory.failedRoutes.get(key));
                boolean north = range.x == 2818 && range.y == 3455 && range.z == 0;
                actor.memory.failedRoutes.put(key, north ? 0 : 100);
                found |= north;
            }
            check(found, "North range missing from actual catalogue");
            preferNorth = true;
            System.out.println("FOOD CHECK temporarily prefers north range; closed1535door verified; no supplies injected");
        } else if (command.equals("cooking")) {
            actor.resetActions(true, true, true);
            if (actor.getInventory().getFreeSlots() == 0 && inventoryRaw(actor) > 0) fullInventoryObserved = true;
            cookingRaw = count(actor, 317, 321);
            cookingProducts = count(actor, 315, 319, 7954, 323);
            beforeCookingXp = actor.getStats().get(StatType.Cooking).experience;
            check(cookingRaw > 0, "Catch fish first");
            check(actor.port.start(activity("cooking"), now), "Cooking start rejected");
            System.out.println("FOOD CHECK cooking raw=" + cookingRaw + " fullInventory=" + fullInventoryObserved);
        } else if (command.equals("cooking-check")) {
            int rawConsumed = cookingRaw - count(actor, 317, 321);
            int productsMade = count(actor, 315, 319, 7954, 323) - cookingProducts;
            check(rawConsumed > 0 && rawConsumed == productsMade, "Raw fish/product conservation failed or no cooking yet");
            check(actor.getStats().get(StatType.Cooking).experience > beforeCookingXp, "No successful real cooking yet");
            unchangedCombatXp();
            check(actor.xpMode == XpMode.HARD, "Cooking changed XP mode");
            if (preferNorth) {
                check(northRangeObserved && northDoorOpenIssued, "North range/ordinary Open action not observed yet");
                check(Tile.getObject(1535, 2815, 3448, 0) == null, "North door is still closed");
                System.out.println("CHECK PASS north kitchen: actor issued ordinary Open, reached selected range and cooked caught fish");
            }
            System.out.println("CHECK PASS cooking: real range/dialogue, raw consumed=" + rawConsumed
                    + ", products=" + productsMade + ", HARD XP, no combat XP; full-inventory observed=" + fullInventoryObserved);
        } else if (command.equals("finish")) {
            actor.resetActions(true, true, true);
            actor.closeInterfaces();
            TrainingSites.release(actor);
            if (savedRangePenalties != null) {
                for (Map.Entry<String, Integer> entry : savedRangePenalties.entrySet()) {
                    if (entry.getValue() == null) actor.memory.failedRoutes.remove(entry.getKey());
                    else actor.memory.failedRoutes.put(entry.getKey(), entry.getValue());
                }
            }
            actor.port = originalPort;
            actor.brain = new ProgressionBrain(actor.actorId, actor.port,
                    ProgressionBrain.Config.humanDefaults(600), actor.policy, actor.seed);
            actor.brain.restore(originalBrain, now);
            scheduler().add(actor.brain, now + 1);
            System.out.println("FOOD CHECK restored normal scheduler actor=" + actor.actorId);
            actor = null;
            savedRangePenalties = null;
        } else throw new IllegalArgumentException(command);
    }

    private static ProgressionBrain.Activity activity(String skill) {
        return new ProgressionBrain.Activity("food_check_" + skill, skill, Collections.singleton(skill), 1, 400);
    }

    private static GameObject cookingTarget() throws Exception {
        Field helper = KronosActivityPort.class.getDeclaredField("fishingCooking");
        helper.setAccessible(true);
        Field target = FishingCookingActivities.class.getDeclaredField("cookingTarget");
        target.setAccessible(true);
        return (GameObject) target.get(helper.get(actor.port));
    }

    private static int count(Adventurer p, int... ids) {
        int amount = 0;
        for (int id : ids) amount += p.getInventory().getAmount(id) + p.getBank().getAmount(id);
        return amount;
    }

    private static int inventoryRaw(Adventurer p) {
        return p.getInventory().getAmount(317) + p.getInventory().getAmount(321);
    }

    private static double[] xp(Adventurer p) {
        double[] result = new double[StatType.values().length];
        for (StatType type : StatType.values()) result[type.ordinal()] = p.getStats().get(type).experience;
        return result;
    }

    private static void unchangedCombatXp() {
        for (StatType type : StatType.values()) {
            if (type.isCombat()) check(actor.getStats().get(type).experience == beforeXp[type.ordinal()], "Unexpected " + type + " XP");
        }
    }
}
