package io.ruin.model.activities.automation;

import io.ruin.data.impl.teleports;
import io.ruin.model.World;
import io.ruin.model.activities.wilderness.Wilderness;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.actions.edgeville.SlayerMaster;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.ClipUtils;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.ObjectType;
import io.ruin.model.map.object.actions.impl.Door;
import io.ruin.model.map.route.MapWalkRoute;
import io.ruin.model.map.route.RouteFinder;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.map.route.types.RouteAbsolute;
import io.ruin.model.map.route.types.RouteEntity;
import io.ruin.model.map.route.types.RouteObject;
import io.ruin.model.skills.magic.spells.HomeTeleport;
import io.ruin.model.skills.slayer.SlayerTask;
import io.ruin.network.incoming.handlers.ActionButtonHandler;
import io.ruin.network.incoming.handlers.ObjectActionHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registered world content used by the owner Slayer controller. */
final class SlayerWorld {
    private static final int WALK_RADIUS = 58;
    private static final int PORTAL_WIDGET = 583 << 16;
    static final Passage KALPHITE_CAVE_ENTRY = new Passage(30180, 3319, 3121, 0, "enter", 3305, 9497, 0, true);
    private static final Passage[] PASSAGES = {
            new Passage(2114, 3434, 3537, 0, "climb-up", 3433, 3537, 1),
            new Passage(2118, 3434, 3537, 1, "climb-down", 3438, 3537, 0),
            new Passage(2119, 3413, 3540, 1, "climb-up", 3417, 3540, 2),
            new Passage(2120, 3415, 3540, 2, "climb-down", 3412, 3540, 1),
            new Passage(30191, 3417, 3535, 0, "climb-down", 3412, 9932, 3),
            new Passage(30192, 3412, 9931, 3, "climb-up", 3417, 3536, 0),
            new Passage(17384, 3116, 3452, 0, "climb-down", 3117, 9852, 0),
            new Passage(17385, 3116, 9852, 0, "climb-up", 3117, 3452, 0),
            new Passage(17385, 3097, 9867, 0, "climb-up", 3096, 3468, 0),
            new Passage(7142, 3230, 3504, 0, "climb-down", 3229, 9904, 0),
            new Passage(17385, 3230, 9904, 0, "climb-up", 3230, 3505, 0),
            new Passage(11806, 3237, 9858, 0, "climb-up", 3236, 3458, 0),
            new Passage(30176, 2411, 3061, 0, "enter", 2404, 9415, 0),
            new Passage(534, 2404, 9414, 0, "use", 2412, 3060, 0),
            KALPHITE_CAVE_ENTRY,
            new Passage(26712, 3306, 9497, 0, "use", 3321, 3122, 0, true),
            new Passage(16680, 2884, 3397, 0, "climb-down", 2884, 9798, 0),
            new Passage(17385, 2884, 9797, 0, "climb-up", 2884, 3398, 0),
            new Passage(17384, 2842, 3424, 0, "climb-down", 2841, 9824, 0),
            new Passage(17385, 2842, 9824, 0, "climb-up", 2841, 3424, 0),
            new Passage(26709, 2428, 3423, 0, "enter", 2429, 9824, 0),
            new Passage(27257, 2430, 9824, 0, "use", 2430, 3424, 0),
            new Passage(1738, 3008, 3150, 0, "climb-down", 3009, 9550, 0),
            new Passage(17385, 3008, 9550, 0, "climb-up", 3009, 3150, 0)
    };

    private static SlayerWorld index;
    private static long indexTick;
    private final Map<Integer, List<NPC>> masters = new HashMap<>();
    private final Map<SlayerTask, List<NPC>> monsters = new HashMap<>();
    private final List<NPC> banks = new ArrayList<>();
    private final List<Portal> portals = new ArrayList<>();

    static SlayerWorld current(long tick) {
        if (index == null || tick - indexTick >= 600) {
            index = new SlayerWorld();
            indexTick = tick;
        }
        return index;
    }

    private SlayerWorld() {
        for (NPC npc : World.npcs.entityList) {
            if (npc == null || npc.spawnPosition == null || npc.getDef() == null || !safeTile(npc.spawnPosition)) continue;
            if (npc.spawnPosition.isWithinDistance(World.HOME, 14) && SlayerMaster.offersAssignments(npc))
                masters.computeIfAbsent(npc.getId(), ignored -> new ArrayList<>()).add(npc);
            if (bankOption(npc) > 0) banks.add(npc);
            if (npc.getCombat() == null || npc.getCombat().getInfo() == null
                    || npc.getCombat().getInfo().slayerTasks == null || npc.sixSystemsBossOwner != null
                    || npc.sixSystemsRewardless) continue;
            for (SlayerTask task : npc.getCombat().getInfo().slayerTasks)
                monsters.computeIfAbsent(task, ignored -> new ArrayList<>()).add(npc);
        }
        if (teleports.CATEGORIES == null) return;
        for (int c = 0; c < teleports.CATEGORIES.length; c++) {
            teleports.Category category = teleports.CATEGORIES[c];
            if (category.name.toLowerCase(Locale.ROOT).contains("wilderness")) continue;
            for (int s = 0; s < category.subcategories.length; s++) {
                teleports.Subcategory subcategory = category.subcategories[s];
                if (subcategory.name.equalsIgnoreCase("Bosses")) continue;
                teleports.Teleport[] choices = subcategory.teleports;
                for (int d = 0; d < choices.length; d++) {
                    teleports.Teleport choice = choices[d];
                    Position tile = new Position(choice.x, choice.y, choice.z);
                    if (choice.price == 0 && safeTile(tile)) portals.add(new Portal(c, s, d, choice.name, tile));
                }
            }
        }
    }

    List<NPC> masters(int id) { return masters.getOrDefault(id, Collections.emptyList()); }
    List<NPC> monsters(SlayerTask task) { return monsters.getOrDefault(task, Collections.emptyList()); }
    List<NPC> banks() { return banks; }

    static boolean present(NPC npc) {
        return npc != null && npc.getIndex() >= 0 && World.getNpc(npc.getIndex()) == npc && !npc.isRemoved();
    }

    static int bankOption(NPC npc) {
        int option = npc.getDef().getOption("Bank");
        if (option < 1) return -1;
        int slot = option - 1;
        return (npc.actions != null && npc.actions[slot] != null
                || npc.getDef().defaultActions != null && npc.getDef().defaultActions[slot] != null) ? option : -1;
    }

    static boolean safeTile(Position tile) {
        Region region = tile.getRegion();
        return region != null && !region.empty && region.dynamicIndex < 0 && Wilderness.getLevel(tile) == 0;
    }

    Route route(Player player, NPC npc, boolean allowTeleports) {
        Position from = player.getPosition();
        Position to = npc.isHidden() ? npc.spawnPosition : npc.getPosition();
        if (!safeTile(to) || !SlayerAdditionalRoutes.safeFor(player, npc)) return null;
        Route best = local(from, to, npc.getSize(), 0, 0);
        Route additional = SlayerAdditionalRoutes.route(this, player, npc, allowTeleports);
        if (better(additional, best)) best = additional;
        if (!allowTeleports) return best;
        Position home = homeDestination(player);
        Route viaHome = safeTile(home) ? local(home, to, npc.getSize(), 42, 0) : null;
        if (better(viaHome, best) && !from.isWithinDistance(home, 2)) best = Route.home(home, viaHome.cost);
        for (Portal portal : portals) {
            if (from.isWithinDistance(portal.tile, 2)) continue;
            Route via = local(portal.tile, to, npc.getSize(), 38, 0);
            if (better(via, best)) best = Route.portal(portal, via.cost);
        }
        return best;
    }

    Route routeToHomeMaster(Player player, NPC npc, boolean allowTeleports) {
        if (!npc.spawnPosition.isWithinDistance(World.HOME, 14)) return null;
        Position from = player.getPosition();
        Position to = npc.getPosition();
        if (from.isWithinDistance(World.HOME, 20)) {
            Route walk = local(from, to, npc.getSize(), 0, 0);
            if (walk != null) return walk;
        }
        if (!allowTeleports || from.isWithinDistance(World.HOME, 2)) return null;
        Route after = local(World.HOME, to, npc.getSize(), 20, 0);
        return after == null ? null : Route.home(World.HOME, after.cost);
    }

    static Position homeDestination(Player player) {
        Position override = HomeTeleport.getHomeTeleportOverride(player);
        return override == null ? player.edgeHome ? World.EDGEHOME : World.HOME : override;
    }

    Route homeBankRoute(Player p, NPC banker) {
        Position home = homeDestination(p);
        if (!safeTile(home) || p.getPosition().isWithinDistance(home, 12)) return null;
        Route after = local(home, banker.getPosition(), banker.getSize(), 0, 0);
        return after == null || after.transition() || after.cost > 16 ? null : Route.home(home, 15 + after.cost);
    }

    static boolean better(Route candidate, Route best) {
        return candidate != null && (best == null || candidate.cost < best.cost);
    }

    Portal portal(String name) {
        for (Portal portal : portals) if (portal.name.equals(name)) return portal;
        return null;
    }

    private Route local(Position from, Position to, int size, int cost, int depth) {
        int distance = distance(from, to);
        if (from.getZ() == to.getZ() && distance <= WALK_RADIUS) {
            if (reachable(from, to, size)) return Route.walk(to, cost + distance);
            if (distance <= 36) {
                for (int[] edge : ADJACENT) {
                    Position adjacent = new Position(to.getX() + edge[0], to.getY() + edge[1], to.getZ());
                    if (!safeTile(adjacent) || !withinInteraction(adjacent, to, size)) continue;
                    GameObject door = MapWalkRoute.firstSafeDoor(from, adjacent, 640);
                    if (door != null) return Route.door(door, cost + distance + 14);
                }
            }
        }
        Route best = SlayerRouteCorridors.route(from, to, size, cost);
        Route karuulm = SlayerKaruulmRoutes.corridor(from, to, size, cost);
        if (better(karuulm, best)) best = karuulm;
        Route additional = SlayerAdditionalRoutes.corridor(from, to, size, cost);
        if (better(additional, best)) best = additional;
        Route stronghold = StrongholdSlayerRoutes.route(from, to, size, cost);
        if (better(stronghold, best)) best = stronghold;
        if (depth >= 2) return best;
        for (Passage passage : PASSAGES) {
            if (from.getZ() != passage.entry.getZ() || distance(from, passage.entry) > WALK_RADIUS
                    || !safeTile(passage.exit)) continue;
            GameObject entrance = passage.object();
            if (entrance == null) continue;
            boolean reachable = reachable(from, entrance);
            GameObject door = null;
            if (!reachable) {
                door = doorBeforeEntrance(from, entrance);
                if (door == null) continue;
            }
            Route after = local(passage.exit, to, size, cost + distance(from, passage.entry) + 10, depth + 1);
            if (after == null) continue;
            Route first = door == null ? Route.passage(passage, after.cost) : Route.door(door, after.cost + 14);
            if (better(first, best)) best = first;
        }
        return best;
    }

    private static GameObject doorBeforeEntrance(Position from, GameObject entrance) {
        if (distance(from, new Position(entrance.x, entrance.y, entrance.z)) > 36) return null;
        for (int[] edge : ADJACENT) {
            Position side = new Position(entrance.x + edge[0], entrance.y + edge[1], entrance.z);
            if (!safeTile(side) || Routes.INSTANCE.objectSteps(side, entrance) != 0) continue;
            GameObject door = MapWalkRoute.firstSafeDoor(from, side, 640);
            if (door != null) return door;
        }
        return null;
    }

    private static final int[][] ADJACENT = {{-1,0}, {1,0}, {0,-1}, {0,1}};

    static boolean reachable(Position from, Position to, int size) {
        if (from.getZ() != to.getZ() || distance(from, to) > WALK_RADIUS) return false;
        return Routes.INSTANCE.steps(from, to, size) >= 0;
    }

    static boolean reachable(Position from, GameObject object) {
        return from.getZ() == object.z && distance(from, new Position(object.x, object.y, object.z)) <= WALK_RADIUS
                && Routes.INSTANCE.objectSteps(from, object) >= 0;
    }

    static boolean withinInteraction(Position from, GameObject object) {
        return from.getZ() == object.z && Routes.INSTANCE.objectSteps(from, object) == 0;
    }

    static boolean walkable(Position from, Position to) {
        return from.getZ() == to.getZ() && distance(from, to) <= WALK_RADIUS && safeTile(to)
                && Routes.INSTANCE.absoluteSteps(from, to) >= 0;
    }

    static boolean withinInteraction(Position from, Position to, int size) {
        return from.getZ() == to.getZ() && distance(from, to) <= 1 && !from.equals(to)
                && Routes.INSTANCE.steps(from, to, size) == 0;
    }

    // Uses the native pathfinder with detached step buffers. This never changes a player's route.
    private static final class Routes {
        static final Routes INSTANCE = new Routes();
        final RouteFinder finder = RouteFinder.detached();
        final ClipUtils clip = finder.getClipUtils();
        final RouteEntity goal = new RouteEntity();
        final RouteAbsolute absolute = new RouteAbsolute();
        final RouteObject object = new RouteObject();
        final int[] x = new int[4096], y = new int[4096];

        int steps(Position from, Position to, int size) {
            goal.set(to.getX(), to.getY(), size, size, 0);
            return steps(from, goal);
        }

        int objectSteps(Position from, GameObject entrance) {
            if (entrance.walkTo != null) {
                return absoluteSteps(from, entrance.walkTo);
            }
            int width = 0, height = 0;
            Integer access = null;
            if (entrance.type == 10 || entrance.type == 11 || entrance.type == 22) {
                boolean rotated = (entrance.direction & 1) != 0;
                width = rotated ? entrance.getDef().yLength : entrance.getDef().xLength;
                height = rotated ? entrance.getDef().xLength : entrance.getDef().yLength;
                int mask = entrance.getDef().someDirection;
                access = entrance.direction == 0 ? mask
                        : (15 & mask << entrance.direction) + (mask >> (4 - entrance.direction));
            }
            object.set(entrance.x, entrance.y, width, height, ObjectType.values()[entrance.type], entrance.direction, access);
            return steps(from, object);
        }

        int absoluteSteps(Position from, Position to) {
            absolute.set(to.getX(), to.getY());
            return steps(from, absolute);
        }

        private int steps(Position from, RouteType goal) {
            int baseX = (from.getFirstChunkX() - 6) * 8;
            int baseY = (from.getFirstChunkY() - 6) * 8;
            clip.update(baseX, baseY, from.getZ());
            int steps = finder.findRoute(from.getBaseLocalX(), from.getBaseLocalY(), 1, goal, clip, false, x, y);
            if (steps < 0) return -1;
            int priorX = from.getX(), priorY = from.getY();
            for (int i = 0; i < steps; i++) {
                while (priorX != x[i] || priorY != y[i]) {
                    priorX += Integer.compare(x[i], priorX);
                    priorY += Integer.compare(y[i], priorY);
                    if (Wilderness.getLevel(new Position(priorX, priorY, from.getZ())) > 0) return -1;
                }
            }
            return steps;
        }
    }

    static int distance(Position a, Position b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    static class Passage {
        final int id;
        final Position entry, exit, approach;
        final String action;
        final boolean definitionAction;

        Passage(int id, int x, int y, int z, String action, int outX, int outY, int outZ) {
            this(id, x, y, z, action, outX, outY, outZ, false);
        }

        Passage(int id, int x, int y, int z, String action, int outX, int outY, int outZ, boolean definitionAction) {
            this(id, x, y, z, action, outX, outY, outZ, definitionAction, null);
        }

        Passage(int id, int x, int y, int z, String action, int outX, int outY, int outZ,
                boolean definitionAction, Position approach) {
            this.id = id;
            this.entry = new Position(x, y, z);
            this.exit = new Position(outX, outY, outZ);
            this.action = action;
            this.definitionAction = definitionAction;
            this.approach = approach;
        }

        GameObject object() {
            GameObject object = Tile.getObject(id, entry.getX(), entry.getY(), entry.getZ());
            if (object == null) return null;
            int option = object.getDef().getOption(action) - 1;
            if (option < 0) return null;
            if (object.actions != null && object.actions[option] != null) return object;
            return definitionAction && object.getDef().defaultActions != null
                    && object.getDef().defaultActions[option] != null ? object : null;
        }

        boolean use(Player player) {
            GameObject object = object();
            if (object == null || approach != null && !player.getPosition().equals(approach)) return false;
            ObjectActionHandler.handleAction(player, object.getDef().getOption(action), id,
                    entry.getX(), entry.getY(), 0);
            return true;
        }

    }

    static final class Portal {
        final int category, subcategory, slot;
        final String name;
        final Position tile;

        Portal(int category, int subcategory, int slot, String name, Position tile) {
            this.category = category;
            this.subcategory = subcategory;
            this.slot = slot;
            this.name = name;
            this.tile = tile;
        }

        void use(Player player) {
            player.teleportCategoryIndex = category;
            player.teleportSubcategoryIndex = subcategory;
            teleports.open(player);
            ActionButtonHandler.handleAction(player, 1, PORTAL_WIDGET | 63, slot, -1, false);
        }
    }

    static final class Route {
        final Position arrival;
        final int cost;
        final Passage passage;
        final Portal portal;
        final GameObject door;
        final boolean home, waypoint;

        private Route(Position arrival, int cost, Passage passage, Portal portal, GameObject door, boolean home, boolean waypoint) {
            this.arrival = arrival;
            this.cost = cost;
            this.passage = passage;
            this.portal = portal;
            this.door = door;
            this.home = home;
            this.waypoint = waypoint;
        }

        static Route walk(Position tile, int cost) { return new Route(tile, cost, null, null, null, false, false); }
        static Route waypoint(Position tile, int cost) { return new Route(tile, cost, null, null, null, false, true); }
        static Route passage(Passage passage, int cost) { return new Route(passage.exit, cost, passage, null, null, false, false); }
        static Route portal(Portal portal, int cost) { return new Route(portal.tile, cost, null, portal, null, false, false); }
        static Route home(Position home, int cost) { return new Route(home, cost, null, null, null, true, false); }
        static Route door(GameObject door, int cost) { return new Route(null, cost, null, null, door, false, false); }
        boolean transition() { return waypoint || home || passage != null || portal != null || door != null; }

        boolean useDoor(Player p) {
            if (!Door.hasStandardOpening(door)) return false;
            ObjectActionHandler.handleAction(p, door.getDef().getOption("open"), door.id, door.x, door.y, 0);
            return true;
        }
    }
}
