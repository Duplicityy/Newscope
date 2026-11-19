package io.ruin.model.activities.automation;

import io.ruin.Server;
import io.ruin.cache.AnimDef;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.activities.bosses.vorkath.Vorkath;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.entity.player.PlayerMovement;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.Inventory;
import io.ruin.model.stat.StatList;
import io.ruin.model.skills.magic.spells.modern.CrumbleUndead;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Detached movement decision checks against native interval and path constraints. */
public final class OwnerVorkathAutomationChecks {
    private static int checks;
    private static final VorkathAutomation.StepCheck CLEAR = (x, y, toX, toY) -> true;

    private OwnerVorkathAutomationChecks() {}

    public static void main(String[] args) {
        try {
            checkFireballs();
            checkAcid();
            if (args.length > 0) checkRuntimeHook(Paths.get(args[0]));
            System.out.println("PASS " + checks + " Vorkath hazard " +
                    (args.length > 0 ? "decision and detached native-hook" : "decision") + " assertions.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void checkFireballs() {
        for (int centerX = 0; centerX < 32; centerX++) {
            for (int centerY = 0; centerY < 32; centerY++) {
                VorkathAutomation.TileCenter fire = new VorkathAutomation.TileCenter(centerX, centerY, 3);
                int[] step1 = VorkathAutomation.chooseStep(centerX, centerY, fire, true, CLEAR);
                verify(step1 != null && distance(centerX, centerY, step1[0], step1[1]) == 1);
                int[] step2 = VorkathAutomation.chooseStep(step1[0], step1[1], fire, true, CLEAR);
                verify(step2 != null && distance(centerX, centerY, step2[0], step2[1]) > 1);
                verify(VorkathAutomation.chooseStep(centerX, centerY, fire, true, (x, y, a, b) -> false) == null);
                VorkathAutomation.StepCheck onlyEast = (x, y, a, b) -> a == x + 1 && b == y;
                int[] east = VorkathAutomation.chooseStep(centerX, centerY, fire, true, onlyEast);
                verify(east != null && east[0] == centerX + 1 && east[1] == centerY);
            }
        }
    }

    private static void checkAcid() {
        for (int seed = 0; seed < 32; seed++) {
            Set<Integer> safe = new HashSet<>();
            for (int dx = 0; dx < 3; dx++) {
                for (int dy = 0; dy < 3; dy++) safe.add(key(seed + dx, seed + dy));
            }
            VorkathAutomation.StepCheck allowed = (x, y, a, b) -> safe.contains(key(a, b));
            int x = seed + 1, y = seed + 1, previousX = -1, previousY = -1;
            for (int tick = 0; tick < 25; tick++) {
                int[] step = VorkathAutomation.chooseAcidStep(x, y, previousX, previousY, null, false, allowed);
                verify(step != null && allowed.canStep(x, y, step[0], step[1]));
                verify(step[0] != previousX || step[1] != previousY);
                previousX = x;
                previousY = y;
                x = step[0];
                y = step[1];
            }
        }
        VorkathAutomation.StepCheck culDeSac = (x, y, a, b) -> b == 0 && (a == 0 || a == 1);
        verify(VorkathAutomation.chooseAcidStep(0, 0, -1, -1, null, false, culDeSac) == null);
        VorkathAutomation.TileCenter fire = new VorkathAutomation.TileCenter(0, 0, 3);
        int[] step = VorkathAutomation.chooseAcidStep(1, 0, 0, 0, fire, true, CLEAR);
        verify(step != null && distance(0, 0, step[0], step[1]) > 1);
    }

    private static void checkRuntimeHook(Path fixture) throws Exception {
        Method configure = OwnerCombatAutomationChecks.class.getDeclaredMethod("configure", Path.class);
        configure.setAccessible(true);
        configure.invoke(null, fixture);
        AnimDef.load();
        npc_combat data = new npc_combat();
        data.fromJson("Vorkath", new String(Files.readAllBytes(fixture.resolve("data/npcs/combat/Vorkath.json")),
                StandardCharsets.UTF_8));
        data.fromJson("Zombified_spawn", new String(Files.readAllBytes(fixture.resolve("data/npcs/combat/Zombified_spawn.json")),
                StandardCharsets.UTF_8));
        NPC nativeBoss = new NPC(8061).spawn(2269, 4062, 0);
        verify(nativeBoss.getCombat() instanceof Vorkath);
        TestOwner player = new TestOwner(new Position(2272, 4057, 0));
        TestOwner other = new TestOwner(new Position(2270, 4057, 0));
        player.getPosition().getRegion().dynamicIndex = 123;
        VorkathAutomation run = new VorkathAutomation(player, 2, Server.currentTick());
        field("boss").set(run, nativeBoss);
        field("observer").set(null, run);
        Method aerial = Vorkath.class.getDeclaredMethod("fireballAttack");
        aerial.setAccessible(true);
        nativeBoss.getCombat().setTarget(player);
        aerial.invoke(nativeBoss.getCombat());
        VorkathAutomation.TileCenter hit = (VorkathAutomation.TileCenter) field("fireball").get(run);
        verify(hit != null && hit.x == player.getAbsX() && hit.y == player.getAbsY()
                && hit.deadline == Server.currentTick() + 3);
        nativeBoss.getCombat().setTarget(other);
        aerial.invoke(nativeBoss.getCombat());
        verify(field("fireball").get(run) == hit);

        GameObject pool = new GameObject(32000, player.getAbsX(), player.getAbsY(), player.getHeight(), 10, 0);
        VorkathAutomation.observeAcid(nativeBoss, other, Arrays.asList(pool));
        verify(((Set<?>) field("pendingAcid").get(run)).isEmpty());
        Method acid = Vorkath.class.getDeclaredMethod("poisonAttack");
        acid.setAccessible(true);
        nativeBoss.getCombat().setTarget(player);
        acid.invoke(nativeBoss.getCombat());
        Method process = Entity.class.getDeclaredMethod("processEvent");
        process.setAccessible(true);
        process.invoke(player);
        verify(field("phase").get(run).toString().equals("ACID") && ((Set<?>) field("pendingAcid").get(run)).size() >= 39);
        verify(player.getPosition().getTile().getObject(32000, 10, -1) == null);
        VorkathAutomation.observeAcidEnd(nativeBoss, player);
        verify(field("phase").get(run).toString().equals("NORMAL") && ((Set<?>) field("pendingAcid").get(run)).isEmpty());
        Field background = Entity.class.getDeclaredField("backgroundEvents");
        background.setAccessible(true);
        // Isolate the zombie event from the earlier aerial events, which already checked their dispatch.
        ((List<?>) background.get(nativeBoss)).clear();
        Method freeze = Vorkath.class.getDeclaredMethod("zombieSpawn");
        freeze.setAccessible(true);
        freeze.invoke(nativeBoss.getCombat());
        verify(field("phase").get(run).toString().equals("FREEZE") && player.isFrozen());
        for (int tick = 0; tick < 5 && field("zombie").get(run) == null; tick++) process.invoke(nativeBoss);
        NPC zombie = (NPC) field("zombie").get(run);
        verify(zombie != null && zombie.getId() == 8063 && zombie.getCombat().getTarget() == player);
        player.getInventory().add(562, 3);
        player.getInventory().add(557, 6);
        player.getInventory().add(556, 6);
        Method handleFreeze = VorkathAutomation.class.getDeclaredMethod("handleFreeze", long.class);
        handleFreeze.setAccessible(true);
        verify(handleFreeze.invoke(run, Server.currentTick()) == null);
        verify(player.getCombat().getTarget() == zombie && player.getCombat().queuedSpell == CrumbleUndead.INSTANCE);
        VorkathAutomation.observeFreezeEnd(nativeBoss, player);
        verify(field("phase").get(run).toString().equals("NORMAL"));
        run.stop();
        nativeBoss.getCombat().setTarget(player);
        aerial.invoke(nativeBoss.getCombat());
        verify(field("fireball").get(run) == null && field("observer").get(null) == null);
        zombie.remove();
        nativeBoss.remove();
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = VorkathAutomation.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static class TestOwner extends Player {
        private final PlayerCombat combat = new PlayerCombat();
        private final PlayerMovement movement = new PlayerMovement(this);
        private final Inventory inventory = new Inventory();
        private final Equipment equipment = new Equipment();
        private final StatList stats = new StatList();
        TestOwner(Position origin) {
            position = origin.copy();
            inventory.init(28, false);
            equipment.init(14, false);
            stats.init(this);
            combat.init(this);
        }
        @Override public PlayerCombat getCombat() { return combat; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public Equipment getEquipment() { return equipment; }
        @Override public StatList getStats() { return stats; }
        @Override public void updateVarp(int id) {}
        @Override public void faceNone(boolean delay) {}
        @Override public PlayerMovement getMovement() { return movement; }
        @Override public void graphics(int id, int height, int delay) {}
    }

    private static int key(int x, int y) { return (x << 16) ^ y; }

    private static int distance(int x, int y, int a, int b) {
        return Math.max(Math.abs(x - a), Math.abs(y - b));
    }

    private static void verify(boolean value) {
        if (!value) throw new AssertionError("Check " + checks + " failed");
        checks++;
    }
}
