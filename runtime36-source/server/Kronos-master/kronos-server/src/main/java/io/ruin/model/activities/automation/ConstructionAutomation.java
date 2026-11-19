package io.ruin.model.activities.automation;

import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.actions.SimpleAction;
import io.ruin.model.item.Item;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.skills.construction.Buildable;
import io.ruin.model.skills.construction.Construction;
import io.ruin.model.skills.construction.Flatpack;
import io.ruin.model.skills.construction.House;
import io.ruin.model.skills.construction.Material;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Uses the owner's workbench to produce one material-backed native flatpack at a time. */
final class ConstructionAutomation implements AutomationDriver {
    private static final int RADIUS = 6;
    private static final int CHAIRS_CATEGORY = 111;
    private static final Flatpack[] CHAIRS = {Flatpack.CRUDE_CHAIR, Flatpack.WOODEN_CHAIR,
            Flatpack.ROCKING_CHAIR, Flatpack.OAK_CHAIR, Flatpack.OAK_ARMCHAIR,
            Flatpack.TEAK_ARMCHAIR, Flatpack.MAHOGANY_ARMCHAIR};
    private static final int[] NAILS = {4819, 4820, 1539, 4821, 4822, 4823, 4824};

    private final Player player;
    private final int goal, plane;
    private final House house;
    private final AutomationActionSource actions;
    private final Set<GameObject> failed = Collections.newSetFromMap(new IdentityHashMap<>());
    private GameObject station;
    private int stationId, finishX, finishY;
    private long readyAt, deadline;
    private Event event;
    private double beforeXp;
    private String activity = "Checking workbench and supplies";

    ConstructionAutomation(Player player, int goal, long tick) {
        this.player = player;
        this.goal = goal;
        plane = player.getHeight();
        house = player.getCurrentHouse();
        actions = (AutomationActionSource) (Object) player;
        readyAt = tick;
    }

    @Override
    public String activity() { return activity; }

    @Override
    public String pulse(long tick) {
        if (player.getStats().get(StatType.Construction).fixedLevel >= goal)
            return "Construction goal reached";
        if (house == null || player.getCurrentHouse() != house || !player.isInOwnHouse() || player.getHeight() != plane)
            return "Stand inside your own house near a workbench";

        if (event != null) {
            Event current = actions.ownerAutomationAction();
            if (current == event) return tick < deadline ? null : "Workbench action timed out";
            event = null;
            if (current != null) return "Another action took control";
            if (player.getStats().get(StatType.Construction).experience <= beforeXp)
                return "Workbench made no progress. Check tools, level and materials.";
        }
        if (player.hasActiveAction()) return "Another action took control";
        if (station != null) {
            if (!validStation() || tick >= deadline) return reject(tick);
            if (!player.getMovement().isAtDestination()) return null;
            if (!player.isAt(finishX, finishY)) return reject(tick);
            RouteType approach = player.getRouteFinder().routeObject(station);
            if (!approach.reachable || !approach.finished(player.getPosition())) return reject(tick);
        }
        if (!player.getMovement().isAtDestination()) return "Another movement took control";
        if (tick < readyAt) return null;
        if (station == null) {
            station = nearestWorkbench();
            if (station == null) return "No eligible workbench within 6 tiles. Check your bench, tools and planks.";
            stationId = station.id;
            RouteType approach = player.getRouteFinder().routeObject(station);
            if (!approach.reachable) return reject(tick);
            if (!approach.finished(player.getPosition())) {
                finishX = approach.finishX;
                finishY = approach.finishY;
                deadline = tick + AutomationPolicy.ROUTE_TICKS;
                activity = "Walking to the workbench";
                return null;
            }
        }
        return craft(tick);
    }

    @Override
    public void stop() {
        if (event != null && actions.cancelOwnerAutomationAction(event)) player.resetAnimation();
        event = null;
        if (station != null) player.getMovement().reset();
        station = null;
    }

    private String craft(long tick) {
        GameObject bench = station;
        station = null;
        int level = workbenchLevel(bench.id);
        int slot = chairSlot(player, level);
        ObjectAction action = benchAction(bench);
        if (slot == -1 || action == null) return "Bring a saw, hammer and materials for an eligible chair";
        player.face(bench);
        action.handle(player, bench);
        Object select = InterfaceHandler.getAction(player, Interface.CONSTRUCTION_FLATPACK_CREATION, CHAIRS_CATEGORY);
        if (!(select instanceof SimpleAction) || player.get("WORKBENCH_LEVEL", 1) != level) {
            closeWorkbench();
            return "Workbench options changed. Select a recipe manually.";
        }
        ((SimpleAction) select).handle(player);
        Flatpack[] chosen = player.get("FLATPACK_SET");
        if (!player.isVisibleInterface(Interface.CONSTRUCTION_FURNITURE_CREATION)
                || chosen == null || slot >= chosen.length || chosen[slot] != CHAIRS[slot]
                || !hasMaterials(player, chosen[slot].getBuildable())) {
            closeWorkbench();
            return "Workbench recipe unavailable. Check materials.";
        }
        beforeXp = player.getStats().get(StatType.Construction).experience;
        Flatpack.make(player, slot + 1);
        event = actions.ownerAutomationAction();
        if (event == null) return "Native workbench declined the recipe";
        // Flatpack's manual event repeats and recognizes administrator privileges.
        // A fresh strict inventory check is required before every automated build.
        event.setCancelCondition(() -> true);
        activity = "Building " + chosen[slot].name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        deadline = tick + 24;
        readyAt = tick + 2;
        failed.clear();
        return null;
    }

    private void closeWorkbench() {
        if (player.isVisibleInterface(Interface.CONSTRUCTION_FLATPACK_CREATION)
                || player.isVisibleInterface(Interface.CONSTRUCTION_FURNITURE_CREATION))
            player.closeInterface(InterfaceType.MAIN);
    }

    private boolean validStation() {
        return !station.isRemoved() && station.id == stationId
                && Tile.getObject(stationId, station.x, station.y, station.z, station.type, station.direction) == station;
    }

    private String reject(long tick) {
        failed.add(station);
        station = null;
        player.getMovement().reset();
        readyAt = tick + 2;
        return failed.size() >= AutomationPolicy.MAX_ROUTE_FAILURES ? "Workbench path is obstructed" : null;
    }

    private GameObject nearestWorkbench() {
        GameObject nearest = null;
        int best = RADIUS + 1;
        int x = player.getAbsX(), y = player.getAbsY();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                if (distance >= best || x + dx < 1 || x + dx >= 8192 || y + dy < 1 || y + dy >= 16383)
                    continue;
                Tile tile = Tile.get(x + dx, y + dy, plane);
                if (tile == null || tile.region.getHouse() != house || tile.gameObjects == null) continue;
                for (GameObject object : tile.gameObjects) {
                    int level = workbenchLevel(object.id);
                    if (level == 0 || object.isRemoved() || failed.contains(object)
                            || chairSlot(player, level) == -1 || benchAction(object) == null) continue;
                    nearest = object;
                    best = distance;
                    break;
                }
            }
        }
        return nearest;
    }

    private static ObjectAction benchAction(GameObject bench) {
        if (bench.actions != null && bench.actions.length != 0 && bench.actions[0] != null)
            return bench.actions[0];
        ObjectAction[] defaults = bench.getDef().defaultActions;
        return defaults == null || defaults.length == 0 ? null : defaults[0];
    }

    static int chairSlot(Player player, int maxLevel) {
        for (int index = CHAIRS.length - 1; index >= 0; index--) {
            Buildable b = CHAIRS[index].getBuildable();
            if (b.getLevelReq() <= maxLevel && Construction.getEffectiveLevel(player, b) >= b.getLevelReq()
                    && b.hasTools(player) && b.canBuild(player) && hasMaterials(player, b))
                return index;
        }
        return -1;
    }

    static boolean hasMaterials(Player player, Buildable b) {
        for (Item material : b.getMaterials()) {
            int amount = 0;
            for (Item item : player.getInventory().getItems()) {
                if (item == null || !matches(material.getId(), item.getId())) continue;
                // Native material removal may consider any matching item, so reject attributed copies.
                if (item.hasAttributes()) return false;
                amount += item.getAmount();
            }
            if (amount < material.getAmount()) return false;
        }
        return true;
    }

    private static boolean matches(int material, int inventoryId) {
        if (material != Material.NAILS.getItemId()) return material == inventoryId;
        for (int id : NAILS) if (id == inventoryId) return true;
        return false;
    }

    private static int workbenchLevel(int id) {
        if (id == Buildable.WOODEN_WORKBENCH.getBuiltObjects()[0]) return 20;
        if (id == Buildable.OAK_WORKBENCH.getBuiltObjects()[0]) return 40;
        if (id == Buildable.STEEL_FRAMED_WORKBENCH.getBuiltObjects()[0]) return 60;
        if (id == Buildable.BENCH_WITH_VICE.getBuiltObjects()[0]) return 80;
        if (id == Buildable.BENCH_WITH_LATHE.getBuiltObjects()[0]) return 99;
        return 0;
    }
}
