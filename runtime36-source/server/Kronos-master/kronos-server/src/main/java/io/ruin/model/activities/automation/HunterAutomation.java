package io.ruin.model.activities.automation;

import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.map.route.routes.ProjectileRoute;
import io.ruin.model.skills.hunter.Hunter;
import io.ruin.model.skills.hunter.traps.Trap;
import io.ruin.model.skills.hunter.traps.TrapType;
import io.ruin.model.skills.hunter.traps.impl.BirdSnare;
import io.ruin.model.skills.hunter.traps.impl.BoxTrap;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

import java.util.HashMap;
import java.util.Map;

/** Native one-trap Hunter loop for birds and safe grey/red chinchompas. */
final class HunterAutomation implements AutomationDriver {
    private static final int LOCAL_RADIUS = 10;
    private static final int MAX_SITE_DISTANCE = 24;
    private static final int SELECT_PERIOD = 6;
    private static final int RESET_AFTER_TICKS = 72;
    private static final int MINIMUM_FREE_SLOTS = 4;
    private static final Animal[] ANIMALS = {
            new Animal(5549, 1, false),
            new Animal(5551, 5, false),
            new Animal(5552, 9, false),
            new Animal(5550, 11, false),
            new Animal(5548, 19, false),
            new Animal(2910, 53, true),
            new Animal(2911, 63, true)
    };

    private final Player player;
    private final AutomationActionSource events;
    private final AutomationPolicy.Budget budget;
    private final Position origin;
    private final int goal;
    private final Map<Position, Long> rejected = new HashMap<>();
    private Event action;
    private Plan plan;
    private Trap trap;
    private Step settling;
    private Position plantedAt;
    private long plantedTick, routeDeadline, nextSelect, settleDeadline;
    private int finishX, finishY;
    private boolean moving;
    private double lastXp;
    private String activity = "Looking for nearby Hunter creatures";

    HunterAutomation(Player player, int goal, long tick) {
        this.player = player;
        this.goal = goal;
        this.budget = new AutomationPolicy.Budget(tick);
        this.events = (AutomationActionSource) (Object) player;
        this.origin = player.getPosition().copy();
        this.lastXp = player.getStats().get(StatType.Hunter).experience;
    }

    @Override
    public String activity() { return activity; }

    @Override
    public boolean ownsNativeLock() {
        return action != null && events.ownerAutomationAction() == action;
    }

    @Override
    public void stop() {
        // A native trap action locks while it transfers trap equipment and changes the object.
        // Let just that already-started transaction finish if it is in that phase.
        if (action != null && !player.isLocked() && events.cancelOwnerAutomationAction(action))
            player.resetAnimation();
        if (moving) player.getMovement().reset();
        action = null;
        plan = null;
        moving = false;
    }

    @Override
    public String pulse(long tick) {
        if (player.getStats().get(StatType.Hunter).fixedLevel >= goal)
            return "Hunter goal reached";
        if (player.getHeight() != origin.getZ() || distance(player.getPosition(), origin) > MAX_SITE_DISTANCE)
            return "Moved outside the local Hunter site";
        double xp = player.getStats().get(StatType.Hunter).experience;
        if (xp > lastXp) budget.progress(tick);
        lastXp = xp;
        if (action != null) {
            if (ownsNativeLock()) {
                if (budget.expired(tick)) return "Hunter transaction did not complete";
                return null;
            }
            if (settling == Step.LAY && !player.getMovement().isAtDestination())
                return tick < settleDeadline ? null : "Native trap placement movement did not finish";
            action = null;
            if (settling == Step.LAY) {
                trap = findPlacedTrap();
                if (trap == null) return "Could not place a trap. Check the training tile.";
                plantedTick = tick;
            } else if (ownsTrap(trap)) {
                return "Trap collection did not finish. Check your inventory and trap.";
            } else {
                trap = null;
            }
            settling = null;
        }
        if (moving)
            return finishWalk(tick);
        if (player.hasActiveAction() || !player.getMovement().isAtDestination())
            return "Another action took control";
        if (budget.expired(tick))
            return activity + ". Check your traps and move nearer to a supported site.";
        if (tick < nextSelect)
            return null;
        nextSelect = tick + SELECT_PERIOD;
        if (trap != null)
            return inspectTrap(tick);
        if (!player.traps.isEmpty())
            return "Collect your existing traps before starting the Hunter goal";
        if (player.getInventory().getFreeSlots() < MINIMUM_FREE_SLOTS)
            return "Make at least four inventory spaces for the trap and its catch";
        if (!player.getInventory().hasId(BirdSnare.INSTANCE.getItemId())
                && !player.getInventory().hasId(BoxTrap.INSTANCE.getItemId()))
            return "Bring a bird snare or box trap";
        Plan selected = choosePlacement(tick);
        if (selected == null) {
            activity = "Waiting near eligible birds or grey/red chinchompas";
            return null;
        }
        return startWalk(selected, tick);
    }

    private Plan choosePlacement(long tick) {
        rejected.values().removeIf(expiry -> expiry <= tick);
        NPC best = null;
        Animal animal = null;
        int score = Integer.MIN_VALUE;
        int level = player.getStats().get(StatType.Hunter).currentLevel;
        for (NPC creature : player.localNpcs()) {
            if (!live(creature) || creature.getHeight() != player.getHeight()) continue;
            int distance = distance(creature.getPosition(), player.getPosition());
            if (distance > LOCAL_RADIUS) continue;
            for (Animal species : ANIMALS) {
                if (creature.getId() != species.id || level < species.level
                        || !player.getInventory().hasId(species.trap().getItemId())) continue;
                int value = species.level * 2 - distance;
                if (value > score) {
                    score = value;
                    best = creature;
                    animal = species;
                }
            }
        }
        if (best == null) return null;
        int x = player.getAbsX(), y = player.getAbsY(), z = player.getHeight();
        for (int width = 0; width <= 4; width++) {
            for (int dx = -width; dx <= width; dx++) {
                for (int dy = -width; dy <= width; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != width) continue;
                    Position tile = new Position(x + dx, y + dy, z);
                    if (!rejected.containsKey(tile) && placementAllowed(best, tile))
                        return new Plan(tile, best, animal.trap());
                }
            }
        }
        return null;
    }

    private String inspectTrap(long tick) {
        if (!ownsTrap(trap))
            return "The trap expired. Recover the trap equipment before restarting.";
        GameObject object = trap.getObject();
        TrapType type = trap.getTrapType();
        for (int success : type.getSuccessObjects()) {
            if (object.id == success && trap.getTrappedCreature() != null)
                return startWalk(new Plan(trap, Step.CHECK), tick);
        }
        if (!trap.isBusy() && (object.id == type.getFailedObjectId()
                || object.id == type.getActiveObjectId() && tick - plantedTick >= RESET_AFTER_TICKS))
            return startWalk(new Plan(trap, Step.DISMANTLE), tick);
        activity = "Waiting for an animal to approach your trap";
        return null;
    }

    private boolean placementAllowed(NPC creature, Position tile) {
        return live(creature) && tile.getX() > 0 && tile.getX() < 8192 && tile.getY() > 0 && tile.getY() < 16383
                && tile.getZ() == creature.getHeight() && distance(tile, creature.getPosition()) <= LOCAL_RADIUS
                && Tile.allowObjectPlacement(tile) && ProjectileRoute.allow(creature, tile.getX(), tile.getY());
    }

    private String startWalk(Plan next, long tick) {
        plan = next;
        if (!validPlan(next))
            return reject(tick);
        RouteType route = route(next);
        if (!route.reachable)
            return reject(tick);
        if (route.finished(player.getPosition()))
            return perform(tick);
        finishX = route.finishX;
        finishY = route.finishY;
        routeDeadline = tick + AutomationPolicy.ROUTE_TICKS;
        moving = true;
        activity = next.step == Step.LAY ? "Walking to a valid trap site" : "Walking to your trap";
        return null;
    }

    private String finishWalk(long tick) {
        if (tick >= routeDeadline || !validPlan(plan))
            return reject(tick);
        if (!player.getMovement().isAtDestination())
            return null;
        if (!player.isAt(finishX, finishY))
            return reject(tick);
        RouteType route = route(plan);
        if (!route.reachable || !route.finished(player.getPosition()))
            return reject(tick);
        return perform(tick);
    }

    private String perform(long tick) {
        Plan current = plan;
        if (!validPlan(current)) return reject(tick);
        Event before = events.ownerAutomationAction();
        if (current.step == Step.LAY) {
            Item item = player.getInventory().findItem(current.type.getItemId());
            if (item == null) return "Bring the required trap";
            plantedAt = current.position;
            Hunter.placeItemTrap(player, current.type, item);
            activity = "Laying a trap";
        } else {
            GameObject object = current.trap.getObject();
            player.face(object);
            if (current.step == Step.CHECK) {
                if (!current.trap.getTrappedCreature().hasRoomForLoot(player))
                    return "Free space to collect your catch";
                Hunter.checkTrap(player, object);
                activity = "Checking your trap";
            } else {
                Hunter.dismantleTrap(player, object);
                activity = "Reclaiming trap equipment";
            }
        }
        Event after = events.ownerAutomationAction();
        if (after == null || after == before) return reject(tick);
        action = after;
        settling = current.step;
        settleDeadline = tick + AutomationPolicy.ROUTE_TICKS;
        plan = null;
        moving = false;
        return null;
    }

    private RouteType route(Plan selected) {
        return selected.step == Step.LAY
                ? player.getRouteFinder().routeAbsolute(selected.position.getX(), selected.position.getY())
                : player.getRouteFinder().routeObject(selected.trap.getObject());
    }

    private boolean validPlan(Plan selected) {
        if (selected.step == Step.LAY)
            return player.traps.isEmpty() && placementAllowed(selected.creature, selected.position);
        if (!ownsTrap(selected.trap) || selected.trap.getObject().id != selected.expectedId)
            return false;
        return selected.step == Step.CHECK ? selected.trap.getTrappedCreature() != null : !selected.trap.isBusy();
    }

    private boolean ownsTrap(Trap target) {
        if (target == null || target.getOwner() != player || target.isRemoved() || !player.traps.contains(target))
            return false;
        GameObject object = target.getObject();
        return object != null && !object.isRemoved() && object.trap == target
                && Tile.getObject(object.id, object.x, object.y, object.z, object.type, object.direction) == object;
    }

    private Trap findPlacedTrap() {
        for (Trap next : player.traps) {
            if (!ownsTrap(next)) continue;
            GameObject object = next.getObject();
            if (object.x == plantedAt.getX() && object.y == plantedAt.getY() && object.z == plantedAt.getZ())
                return next;
        }
        return null;
    }

    private String reject(long tick) {
        if (plan != null) rejected.put(plan.position, tick + 35);
        player.getMovement().reset();
        plan = null;
        moving = false;
        nextSelect = tick + SELECT_PERIOD;
        activity = "Trying another reachable trap approach";
        return budget.retry() ? null : "No safe Hunter approach after several attempts";
    }

    private static boolean live(NPC npc) {
        return !npc.isRemoved() && !npc.isHidden() && World.getNpc(npc.getIndex()) == npc;
    }

    private static int distance(Position a, Position b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private enum Step { LAY, CHECK, DISMANTLE }

    private static final class Animal {
        final int id, level;
        final boolean box;

        Animal(int id, int level, boolean box) {
            this.id = id;
            this.level = level;
            this.box = box;
        }

        TrapType trap() { return box ? BoxTrap.INSTANCE : BirdSnare.INSTANCE; }
    }

    private static final class Plan {
        final Position position;
        final NPC creature;
        final TrapType type;
        final Trap trap;
        final int expectedId;
        final Step step;

        Plan(Position position, NPC creature, TrapType type) {
            this.position = position;
            this.creature = creature;
            this.type = type;
            trap = null;
            expectedId = -1;
            step = Step.LAY;
        }

        Plan(Trap trap, Step step) {
            GameObject object = trap.getObject();
            this.position = new Position(object.x, object.y, object.z);
            this.creature = null;
            this.type = trap.getTrapType();
            this.trap = trap;
            this.expectedId = object.id;
            this.step = step;
        }
    }
}
