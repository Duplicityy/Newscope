package io.ruin.model.activities.automation;

import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.GameMode;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.entity.player.PlayerMovement;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.Inventory;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.skills.fishing.FishingSpot;
import io.ruin.model.skills.hunter.creature.impl.Bird;
import io.ruin.model.skills.hunter.traps.Trap;
import io.ruin.model.skills.woodcutting.Tree;
import io.ruin.model.stat.StatList;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;

/** Runs the real selected native events in a detached process with in-memory players. */
public final class OwnerGatheringNativeChecks {
    private static int assertions;

    public static void main(String[] args) {
        try {
            Method fixture = OwnerAutomationNativeChecks.class.getDeclaredMethod("loadFixture", Path.class);
            fixture.setAccessible(true);
            fixture.invoke(null, Paths.get(args[0]));
            Class.forName("io.ruin.model.skills.woodcutting.Woodcutting");
            Class.forName(FishingSpot.class.getName());
            woodcutting();
            fishing();
            hunter();
            System.out.println("PASS " + assertions + " detached native Woodcutting, Fishing and Hunter assertions.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void woodcutting() {
        require(WoodcuttingAutomation.treeFor(1276, "Tree") == Tree.REGULAR, "legacy tree mapping");
        require(WoodcuttingAutomation.treeFor(10820, "Oak tree") == Tree.OAK, "native oak mapping");
        require(WoodcuttingAutomation.treeFor(1276, "Tree stump") == null, "reject depleted tree");
        Probe player = new Probe(new Position(3229, 3147, 0));
        player.inventory.add(1351, 1);
        for (Position site : new Position[]{new Position(3229,3147,0), new Position(3284,3371,0),
                new Position(3177,3371,0), new Position(3093,3491,0)}) {
            player.relocate(site.getX(),site.getY());
            WoodcuttingAutomation driver = new WoodcuttingAutomation(player, 15, 0);
            LocalGatheringAutomation.GatheringTarget next = driver.chooseResource(8);
            if (next == null) continue;
            require(next.outputs[0] == 1511, "real level-one tree near fixture");
            stepTo(player, next);
            require(driver.pulse(0) == null, "driver starts native woodcutting");
            earn(driver, player, StatType.Woodcutting, 1511);
            return;
        }
        throw new AssertionError("No native tree near sampled sites");
    }

    private static void fishing() {
        NPC spot = new NPC(FishingSpot.NET_BAIT).spawn(3229, 3147, 0);
        Probe player = new Probe(new Position(3228, 3147, 0));
        player.visibleNpcs = Collections.singletonList(spot);
        FishingAutomation driver = new FishingAutomation(player, 15, 0);
        require(driver.chooseResource(8) == null, "fishing without required equipment refuses");
        player.inventory.add(307, 1);
        require(driver.chooseResource(8) == null, "rod alone at level one cannot fish");
        player.inventory.add(303, 1);
        LocalGatheringAutomation.GatheringTarget next = driver.chooseResource(8);
        require(next != null, "native level-one small net action");
        stepTo(player, next);
        require(driver.pulse(0) == null, "driver starts native fishing");
        earn(driver, player, StatType.Fishing, 317);
    }

    private static void hunter() {
        NPC bird = new NPC(5549).spawn(3228, 3147, 0);
        Probe player = new Probe(new Position(3229, 3147, 0));
        player.visibleNpcs = Collections.singletonList(bird);
        player.inventory.add(10006, 1);
        HunterAutomation driver = new HunterAutomation(player, 15, 0);
        require(driver.pulse(0) == null, "hunter chooses a legal nearby trap tile");
        player.tickNativeEvents();
        require(player.isLocked() && driver.ownsNativeLock(), "hunter tracks its native placement lock");
        for (int tick = 1; tick <= 8; tick++) player.tickNativeEvents();
        require(!player.isLocked() && player.traps.size() == 1, "native place created one owned trap");
        require(player.inventory.getAmount(10006) == 0, "native placement consumes the trap");
        String settled = driver.pulse(10);
        require(settled == null, "waiting on the native placed trap: " + settled);
        player.settleNativeSteps();
        require(driver.pulse(12) == null, "native placement movement settled");

        // Reproduce the object and ownership state after the native bird AI lands.
        Trap trap = player.traps.iterator().next();
        trap.setTrappedCreature(new Bird("crimson swift", 5549, 1, 34, 9349, 9373, 10088, .5, 30, null));
        trap.getObject().setId(9373);
        require(driver.pulse(18) == null, "hunter dispatches native check on its owned successful trap");
        player.tickNativeEvents();
        require(player.isLocked() && driver.ownsNativeLock(), "hunter tracks its native collection lock");
        driver.stop();
        require(player.isLocked(), "stop does not abort a locked native transaction");
        for (int tick = 17; tick <= 23; tick++) player.tickNativeEvents();
        require(!player.isLocked() && player.traps.isEmpty(), "the in-flight native check completed and cleared the trap");
        require(player.stats.get(StatType.Hunter).experience > 0, "native check awarded Hunter experience");
        require(player.inventory.getAmount(10006) == 1 && player.inventory.getAmount(10088) > 0,
                "native check returned the trap and awarded feathers");
    }

    private static void stepTo(Probe player, LocalGatheringAutomation.GatheringTarget next) {
        RouteType route = next.route(player);
        require(route.reachable, "native route to exact interaction approach");
        player.relocate(route.finishX, route.finishY);
        player.movement.reset();
    }

    private static void earn(AutomationDriver driver, Probe player, StatType stat, int item) {
        AutomationActionSource lease = (AutomationActionSource) (Object) player;
        Event action = lease.ownerAutomationAction();
        require(action != null, "action is owned");
        for (int tick = 1; tick <= 250 && player.stats.get(stat).experience == 0; tick++) {
            if (!action.tick()) break;
        }
        require(player.stats.get(stat).experience > 0, "native event awarded experience");
        require(player.inventory.getAmount(item) > 0, "native event awarded item");
        driver.stop();
        require(lease.ownerAutomationAction() == null, "stop released exact owned event");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
    }

    private static final class Probe extends Player {
        final PlayerMovement movement = new PlayerMovement(this);
        final Inventory inventory = new Inventory();
        final Equipment equipment = new Equipment();
        final StatList stats = new StatList();
        Collection<NPC> visibleNpcs = Collections.emptyList();

        Probe(Position position) {
            this.position = position.copy();
            inventory.init(28, false);
            equipment.init(14, false);
            stats.init(this);
        }

        void tickNativeEvents() { processEvent(); }

        void settleNativeSteps() {
            if (!movement.isAtDestination() && movement.writeOffset > 0)
                relocate(movement.getStepsX()[movement.writeOffset - 1], movement.getStepsY()[movement.writeOffset - 1]);
            movement.reset();
        }

        void relocate(int x, int y) { this.position = new Position(x, y, getHeight()); }

        @Override public PlayerMovement getMovement() { return movement; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public Equipment getEquipment() { return equipment; }
        @Override public StatList getStats() { return stats; }
        @Override public GameMode getGameMode() { return GameMode.STANDARD; }
        @Override public Collection<NPC> localNpcs() { return visibleNpcs; }
        @Override public boolean isGroup(PlayerGroup group) { return false; }
        @Override public void face(GameObject object) {}
        @Override public void face(Entity entity) {}
        @Override public int animate(int id, int delay) { return 0; }
        @Override public void graphics(int id, int height, int delay) {}
        @Override public void sendMessage(String message) {}
        @Override public void sendFilteredMessage(String message) {}
        @Override public void publicSound(int id, int range, int delay) {}
        @Override public boolean isMovementBlocked(boolean message, boolean ignoreFreeze) { return false; }
    }
}
