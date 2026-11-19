package io.ruin.model.activities.automation;

import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.item.Item;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Shared local routing, event ownership and observed-output banking for ordinary gathering sites. */
abstract class LocalGatheringAutomation implements AutomationDriver {
    private static final int SITE_RADIUS = 28;
    private static final int SCAN_RADIUS = 8;
    private static final int BANK_RADIUS = 12;
    private static final int SCAN_DELAY = 6;
    private static final int RETRY_DELAY = 45;

    final Player player;
    private final AutomationActionSource events;
    private final AutomationPolicy.Budget budget;
    private final StatType skill;
    private final int goal, anchorX, anchorY, plane;
    private final Map<Object, Long> rejected = new IdentityHashMap<>();
    private final Map<Integer, Integer> protectedInventory = new HashMap<>();
    private final Map<Integer, Integer> gathered = new HashMap<>();
    private GatheringTarget target;
    private GatheringTarget workingTarget;
    private Event action;
    private int[] previousOutput;
    private double previousXp;
    private int finishX, finishY;
    private long routeDeadline, nextScan;
    private boolean returningFromBank;
    private String activity = "Looking for nearby resources";

    LocalGatheringAutomation(Player player, StatType skill, int goal, long tick) {
        this.player = player;
        this.skill = skill;
        this.goal = goal;
        this.events = (AutomationActionSource)(Object) player;
        this.budget = new AutomationPolicy.Budget(tick);
        this.anchorX = player.getAbsX();
        this.anchorY = player.getAbsY();
        this.plane = player.getHeight();
        for (Item item : player.getInventory().getItems()) {
            if (item != null)
                protectedInventory.merge(item.getId(), item.getAmount(), Integer::sum);
        }
    }

    @Override
    public final String activity() {
        return activity;
    }

    @Override
    public final String pulse(long tick) {
        if (player.getStats().get(skill).fixedLevel >= goal)
            return skill.name() + " goal reached";
        if (player.getHeight() != plane || distance(anchorX, anchorY) > SITE_RADIUS)
            return "Moved outside the local gathering site";
        if (action != null) {
            observeOutput(tick);
            if (events.ownerAutomationAction() == action && workingTarget.stillUsable()) {
                if (budget.expired(tick)) return "No gathering progress. Try another spot.";
                return null;
            }
            if (events.cancelOwnerAutomationAction(action))
                player.resetAnimation();
            action = null;
            workingTarget = null;
        }
        String immediatelyBlocked = precondition();
        if (immediatelyBlocked != null) return immediatelyBlocked;
        if (target != null)
            return continueRoute(tick);
        if (player.hasActiveAction() || !player.getMovement().isAtDestination())
            return "Another action took control";
        if (budget.expired(tick))
            return activity + ". Move nearer to a supported gathering site or bank.";
        if (tick < nextScan)
            return null;

        nextScan = tick + SCAN_DELAY;
        rejected.values().removeIf(expiry -> expiry <= tick);
        boolean needsBank = player.getInventory().isFull();
        if (needsBank && (gathered.isEmpty() || player.getGameMode().isUltimateIronman()))
            return "Make room in your inventory first";
        int radius = needsBank || returningFromBank ? BANK_RADIUS : SCAN_RADIUS;
        GatheringTarget chosen = needsBank ? chooseBank(radius) : chooseResource(radius);
        if (chosen == null) {
            activity = needsBank ? "No usable bank within " + radius + " tiles" : noTargetMessage();
            return null;
        }
        return startRoute(chosen, tick);
    }

    @Override
    public final void stop() {
        if (action != null && events.cancelOwnerAutomationAction(action))
            player.resetAnimation();
        action = null;
        workingTarget = null;
        if (target != null)
            player.getMovement().reset();
        target = null;
    }

    abstract GatheringTarget chooseResource(int radius);

    String precondition() {
        return null;
    }

    String noTargetMessage() {
        return "Waiting for suitable nearby resources";
    }

    final int distance(int x, int y) {
        return Math.max(Math.abs(player.getAbsX() - x), Math.abs(player.getAbsY() - y));
    }

    final boolean rejected(Object identity) {
        return rejected.containsKey(identity);
    }

    final int plane() {
        return plane;
    }

    final boolean validTile(int x, int y) {
        return x > 0 && x < 8192 && y > 0 && y < 16383;
    }

    private GatheringTarget chooseBank(int radius) {
        ObjectTarget nearest = null;
        int bestDistance = Integer.MAX_VALUE;
        int x = player.getAbsX(), y = player.getAbsY();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                if (distance >= bestDistance || !validTile(x + dx, y + dy)) continue;
                Tile tile = Tile.get(x + dx, y + dy, plane);
                if (tile == null || tile.gameObjects == null) continue;
                for (GameObject object : tile.gameObjects) {
                    if (object.isRemoved() || rejected(object)) continue;
                    ObjectDef def = object.getDef();
                    if (def == null || !def.bank || def.varpBitId != -1 || def.varpId != -1) continue;
                    int option = def.getOption("use");
                    if (option == -1) option = def.getOption("bank");
                    if (binding(object, option) == null) continue;
                    nearest = new ObjectTarget(object, option, "bank", new int[0]);
                    bestDistance = distance;
                    break;
                }
            }
        }
        return nearest;
    }

    private String startRoute(GatheringTarget chosen, long tick) {
        target = chosen;
        activity = "Walking to " + chosen.label;
        if (!chosen.stillUsable()) return reject(tick);
        RouteType route = chosen.route(player);
        if (!route.reachable) return reject(tick);
        if (route.finished(player.getPosition())) return act(chosen, tick);
        finishX = route.finishX;
        finishY = route.finishY;
        routeDeadline = tick + AutomationPolicy.ROUTE_TICKS;
        return null;
    }

    private String continueRoute(long tick) {
        if (!target.stillUsable() || tick >= routeDeadline) return reject(tick);
        if (!player.getMovement().isAtDestination()) return null;
        if (!player.isAt(finishX, finishY)) return reject(tick);
        RouteType route = target.route(player);
        if (!route.reachable || !route.finished(player.getPosition())) return reject(tick);
        return act(target, tick);
    }

    private String act(GatheringTarget chosen, long tick) {
        if (!chosen.stillUsable()) return reject(tick);
        if (chosen.outputs.length == 0) return bank(chosen, tick);
        previousXp = player.getStats().get(skill).experience;
        previousOutput = new int[chosen.outputs.length];
        for (int i = 0; i < chosen.outputs.length; i++)
            previousOutput[i] = player.getInventory().getAmount(chosen.outputs[i]);
        Event before = events.ownerAutomationAction();
        chosen.interact(player);
        Event after = events.ownerAutomationAction();
        if (after == null || after == before) return reject(tick);
        action = after;
        workingTarget = chosen;
        target = null;
        returningFromBank = false;
        activity = "Gathering at " + chosen.label;
        return null;
    }

    private String bank(GatheringTarget chosen, long tick) {
        target = null;
        if (player.isVisibleInterface(Interface.BANK)) return "Close the already open bank before starting again";
        chosen.interact(player);
        if (!player.isVisibleInterface(Interface.BANK))
            return "Open the bank manually and complete any PIN prompt";
        int total = 0;
        for (Item item : player.getInventory().getItems()) {
            if (item == null || item.hasAttributes()) continue;
            int id = item.getId(), owned = gathered.getOrDefault(id, 0);
            if (owned == 0) continue;
            int amount = AutomationPolicy.depositAmount(item.getAmount(), owned,
                    protectedInventory.getOrDefault(id, 0), player.getInventory().getAmount(id));
            if (amount == 0) continue;
            int moved = player.getBank().deposit(item, amount, false);
            if (moved > 0) {
                if (moved == owned) gathered.remove(id);
                else gathered.put(id, owned - moved);
                total += moved;
            }
        }
        player.closeInterface(InterfaceType.MAIN);
        player.closeInterface(InterfaceType.INVENTORY);
        if (total == 0 || player.getInventory().isFull())
            return "Banking could not free a slot. Check your bank space.";
        activity = "Banked gathered items";
        returningFromBank = true;
        budget.progress(tick);
        return null;
    }

    private void observeOutput(long tick) {
        double xp = player.getStats().get(skill).experience;
        if (xp > previousXp) budget.progress(tick);
        for (int i = 0; i < workingTarget.outputs.length; i++) {
            int id = workingTarget.outputs[i], amount = player.getInventory().getAmount(id);
            if (xp > previousXp && amount > previousOutput[i])
                gathered.merge(id, amount - previousOutput[i], Integer::sum);
            previousOutput[i] = amount;
        }
        previousXp = xp;
    }

    private String reject(long tick) {
        if (target != null) rejected.put(target.identity, tick + RETRY_DELAY);
        player.getMovement().reset();
        target = null;
        nextScan = tick + SCAN_DELAY;
        activity = "Trying another reachable target";
        return budget.retry() ? null : "Several targets were unavailable. Move nearer and restart.";
    }

    static ObjectAction binding(GameObject object, int option) {
        if (option < 1 || option > 5) return null;
        int slot = option - 1;
        if (object.actions != null && object.actions[slot] != null) return object.actions[slot];
        ObjectAction[] defaults = object.getDef().defaultActions;
        return defaults == null ? null : defaults[slot];
    }

    abstract static class GatheringTarget {
        final Object identity;
        final String label;
        final int[] outputs;

        GatheringTarget(Object identity, String label, int[] outputs) {
            this.identity = identity;
            this.label = label;
            this.outputs = outputs;
        }

        abstract boolean stillUsable();
        abstract RouteType route(Player player);
        abstract void interact(Player player);
    }

    static final class ObjectTarget extends GatheringTarget {
        private final GameObject object;
        private final int id, option;

        ObjectTarget(GameObject object, int option, String label, int[] outputs) {
            super(object, label, outputs);
            this.object = object;
            this.id = object.id;
            this.option = option;
        }

        @Override
        boolean stillUsable() {
            return !object.isRemoved() && object.id == id
                    && Tile.getObject(id, object.x, object.y, object.z, object.type, object.direction) == object
                    && binding(object, option) != null;
        }

        @Override
        RouteType route(Player player) {
            if (object.walkTo != null)
                return player.getRouteFinder().routeAbsolute(object.walkTo.getX(), object.walkTo.getY());
            return player.getRouteFinder().routeObject(object);
        }

        @Override
        void interact(Player player) {
            ObjectAction action = binding(object, option);
            if (action == null) return;
            player.face(object);
            action.handle(player, object);
        }
    }
}
