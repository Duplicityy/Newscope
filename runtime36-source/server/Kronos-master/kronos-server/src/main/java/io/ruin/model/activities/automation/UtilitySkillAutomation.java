package io.ruin.model.activities.automation;

import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.skills.runecrafting.Altars;
import io.ruin.model.skills.runecrafting.Essence;
import io.ruin.model.skills.thieving.Stall;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Native local stall theft, altar crafting, and the original Gnome agility course. */
final class UtilitySkillAutomation implements AutomationDriver {
    private static final int RADIUS = 10;
    private static final int[] COURSE = {23145, 23134, 23559, 23557, 23560, 23135, 23138};
    private static final String[] COURSE_OPTIONS = {"walk-across", "climb-over", "climb", "walk-on",
            "climb-down", "climb-over", "squeeze-through"};
    private final Player player;
    private final StatType skill;
    private final int goal;
    private final AutomationActionSource actions;
    private final AutomationPolicy.Budget budget;
    private final Map<Integer, Stall> stalls = new HashMap<>();
    private final Map<Integer, Altars> altars = new HashMap<>();
    private final Map<GameObject, Long> excluded = new IdentityHashMap<>();
    private Target target;
    private Event event;
    private String status = "Looking nearby";
    private long rescanAt, routeDeadline, actionDeadline;
    private int courseStep, finishX, finishY;
    private double beforeXp;

    UtilitySkillAutomation(Player player, StatType skill, int goal, long tick) {
        this.player = player;
        this.skill = skill;
        this.goal = goal;
        actions = (AutomationActionSource) (Object) player;
        budget = new AutomationPolicy.Budget(tick);
        if (skill == StatType.Thieving) {
            for (Stall stall : Stall.values())
                for (int[] ids : stall.objIDs) stalls.put(ids[0], stall);
        } else if (skill == StatType.Runecrafting) {
            for (Altars altar : Altars.values()) {
                if (altar.essence != Essence.DARK) altars.put(altar.altarObj, altar);
            }
        } else {
            courseStep = resumeCourseStep(player.lastAgilityObjId);
        }
    }

    static boolean supports(StatType skill) {
        return skill == StatType.Thieving || skill == StatType.Runecrafting || skill == StatType.Agility;
    }

    static int resumeCourseStep(int previous) {
        for (int i = 0; i < COURSE.length - 1; i++)
            if (COURSE[i] == previous) return i + 1;
        return 0;
    }

    @Override public String activity() { return status; }

    @Override public boolean ownsNativeLock() {
        return event != null && actions.ownerAutomationAction() == event;
    }

    @Override public boolean ownsNativeTravel() {
        // The genuine obstacle event may queue a plane change just before it ends.
        return skill == StatType.Agility && event != null;
    }

    @Override public String pulse(long tick) {
        if (player.getStats().get(skill).fixedLevel >= goal) return skill.name() + " goal reached";
        if (event != null) {
            if (actions.ownerAutomationAction() == event)
                return tick < actionDeadline ? null : "Native action stalled";
            if (ownsNativeTravel() && player.getMovement().isTeleportQueued())
                return tick < actionDeadline ? null : "Native travel stalled";
            event = null;
            if (xp() > beforeXp) {
                budget.progress(tick);
                if (skill == StatType.Agility) courseStep = (courseStep + 1) % COURSE.length;
            } else if (!budget.retry()) {
                return "The activity repeatedly failed to earn XP";
            }
            rescanAt = tick + 4;
        }
        if (skill == StatType.Thieving) {
            if (player.getInventory().isFull()) return "Bank your stall loot before continuing";
            if (player.edgevilleStallCooldown.isDelayed()) return null;
        } else if (skill == StatType.Runecrafting && !hasEssence()) {
            return "Bring unnoted rune or pure essence to the altar";
        } else if (skill == StatType.Agility && !insideCourse()) {
            return "Stand at the Gnome Stronghold agility course first";
        }
        if (target != null) return finishRoute(tick);
        if (player.hasActiveAction() || !player.getMovement().isAtDestination()) return "Another action took control";
        if (budget.expired(tick)) return status + ". Reposition and try again.";
        if (tick < rescanAt) return null;
        rescanAt = tick + 6;
        target = choose(tick);
        if (target == null) {
            status = skill == StatType.Agility ? "Waiting for course obstacle " + (courseStep + 1)
                    : skill == StatType.Thieving ? "No eligible stocked stall nearby" : "No eligible altar nearby";
            return null;
        }
        status = "Walking to " + target.name;
        return route(tick);
    }

    @Override public void stop() {
        // Native obstacle/stall cleanup must finish if it has already locked or queued travel.
        if (event != null && !player.isLocked() && !player.getMovement().isTeleportQueued())
            actions.cancelOwnerAutomationAction(event);
        event = null;
        if (target != null) player.getMovement().reset();
        target = null;
    }

    private boolean hasEssence() {
        return player.getInventory().getAmount(Essence.REGULAR.id) > 0
                || player.getInventory().getAmount(Essence.PURE.id) > 0;
    }

    private boolean insideCourse() {
        return player.getAbsX() >= 2468 && player.getAbsX() <= 2492
                && player.getAbsY() >= 3416 && player.getAbsY() <= 3444;
    }

    private Target choose(long tick) {
        excluded.entrySet().removeIf(entry -> entry.getValue() <= tick);
        int level = player.getStats().get(skill).currentLevel;
        int originX = player.getAbsX(), originY = player.getAbsY(), plane = player.getHeight();
        double best = Double.NEGATIVE_INFINITY;
        Target selected = null;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dy = -RADIUS; dy <= RADIUS; dy++) {
            int x = originX + dx, y = originY + dy;
            if (x < 1 || x >= 8192 || y < 1 || y >= 16384) continue;
            Tile tile = Tile.get(x, y, plane);
            if (tile == null || tile.gameObjects == null) continue;
            for (GameObject obj : tile.gameObjects) {
                if (obj.isRemoved() || excluded.containsKey(obj)) continue;
                ObjectDef def = obj.getDef();
                if (def == null || def.name == null || def.varpBitId != -1 || def.varpId != -1) continue;
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                int option;
                double score;
                if (skill == StatType.Agility) {
                    if (obj.id != COURSE[courseStep] && !(courseStep == 6 && obj.id == 23139)) continue;
                    if (courseStep == 6 && obj.y != 3431) continue;
                    option = def.getOption(COURSE_OPTIONS[courseStep]);
                    score = -distance;
                } else if (skill == StatType.Thieving) {
                    Stall stall = stalls.get(obj.id);
                    if (stall == null || level < stall.levelReq) continue;
                    option = def.getOption("steal-from");
                    if (option == -1) option = def.getOption("steal from");
                    score = stall.experience / (2.0 + distance + stall.respawnTime * .2);
                } else {
                    Altars altar = altars.get(obj.id);
                    if (altar == null || level < altar.levelRequirement
                            || altar.essence == Essence.PURE && player.getInventory().getAmount(Essence.PURE.id) == 0) continue;
                    option = def.getOption("craft-rune");
                    if (option == -1) option = def.getOption("bind");
                    score = altar.experience / (2.0 + distance);
                }
                if (option < 1 || option > 5 || action(obj, option - 1) == null || score <= best) continue;
                best = score;
                selected = new Target(obj, option - 1, def.name);
            }
        }
        return selected;
    }

    private String route(long tick) {
        RouteType route = approach(target.object);
        if (!route.reachable) return reject(tick);
        if (route.finished(player.getPosition())) return interact(tick);
        finishX = route.finishX;
        finishY = route.finishY;
        routeDeadline = tick + AutomationPolicy.ROUTE_TICKS;
        return null;
    }

    private String finishRoute(long tick) {
        if (!target.live() || tick >= routeDeadline) return reject(tick);
        if (!player.getMovement().isAtDestination()) return null;
        if (!player.isAt(finishX, finishY)) return reject(tick);
        RouteType route = approach(target.object);
        if (!route.reachable || !route.finished(player.getPosition())) return reject(tick);
        return interact(tick);
    }

    private RouteType approach(GameObject object) {
        Position exact = object.walkTo;
        if (skill == StatType.Agility && object.id == 23135)
            return player.getRouteFinder().routeAbsolute(object.x, 3425);
        return exact == null ? player.getRouteFinder().routeObject(object)
                : player.getRouteFinder().routeAbsolute(exact.getX(), exact.getY());
    }

    private String interact(long tick) {
        if (!target.live() || player.hasActiveAction()) return reject(tick);
        ObjectAction handler = action(target.object, target.option);
        if (handler == null) return reject(tick);
        beforeXp = xp();
        player.face(target.object);
        handler.handle(player, target.object);
        Event nativeEvent = actions.ownerAutomationAction();
        if (nativeEvent == null) return reject(tick);
        event = nativeEvent;
        actionDeadline = tick + 32;
        status = (skill == StatType.Agility ? "Crossing " : skill == StatType.Thieving ? "Stealing from " : "Crafting at ") + target.name;
        target = null;
        return null;
    }

    private String reject(long tick) {
        if (target != null) excluded.put(target.object, tick + 32);
        target = null;
        rescanAt = tick + 6;
        player.getMovement().reset();
        return budget.retry() ? null : "Repeated route or activity failure";
    }

    private double xp() { return player.getStats().get(skill).experience; }

    private static ObjectAction action(GameObject object, int index) {
        ObjectAction[] specific = object.actions;
        if (specific != null && specific[index] != null) return specific[index];
        ObjectAction[] fallback = object.getDef().defaultActions;
        return fallback == null ? null : fallback[index];
    }

    private static final class Target {
        final GameObject object;
        final int id, option;
        final String name;
        Target(GameObject object, int option, String name) {
            this.object = object;
            id = object.id;
            this.option = option;
            this.name = name;
        }
        boolean live() {
            return !object.isRemoved() && object.id == id
                    && Tile.getObject(id, object.x, object.y, object.z, object.type, object.direction) == object;
        }
    }
}
