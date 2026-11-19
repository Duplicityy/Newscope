package io.ruin.model.map.route;

import io.ruin.Server;
import io.ruin.model.activities.wilderness.Wilderness;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.Movement;
import io.ruin.model.entity.shared.StepType;
import io.ruin.model.map.ClipUtils;
import io.ruin.model.map.Direction;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.impl.Door;
import io.ruin.process.event.Event;
import kilim.Pausable;

/** Exact or door-approach routing for clicks on the two client maps. */
public final class MapWalkRoute {
    private static final int GLOBAL_TICK_BUDGET = 4096;
    private static final int PER_ROUTE_TICK_BUDGET = 2048;
    private static final int MAX_SEARCH_TICKS = 65;
    private static final int SEGMENT_TILES = 24;

    private static long budgetTick = -1;
    private static int usedBudget;

    private MapWalkRoute() {}

    public static boolean validCoordinates(int x, int y) {
        return x >= 0 && x < 8192 && y >= 0 && y < 16384
                && Region.getId(x, y) < Region.LOADED.length;
    }

    public static void start(Player player, int targetX, int targetY) {
        if (!validCoordinates(targetX, targetY) || player.isMovementBlocked(true, false))
            return;
        // A persistent gameplay action can survive resetActions. Do not replace it.
        if (player.hasActiveAction() || player.getMovement().isTeleportQueued()) return;
        Position start = player.getPosition().copy();
        WalkClip clip = new WalkClip(player.getRouteFinder().getClipUtils(), start);
        if (!clip.walkableDomain(targetX, targetY, start.getZ())) {
            unreachable(player);
            return;
        }
        player.startEvent(event -> walk(event, player, start, clip, targetX, targetY));
    }

    private static void walk(Event event, Player player, Position start, WalkClip clip, int targetX, int targetY) throws Pausable {
        long deadline = Server.currentTick() + MAX_SEARCH_TICKS;
        int plane = start.getZ(), size = player.getSize(), startX = start.getX(), startY = start.getY();
        MapWalkPath.Search candidate = new MapWalkPath.Search(clip, plane, size, startX, startY, targetX, targetY,
                (x, y, nextX, nextY) -> closedDoor(clip, plane, x, y, nextX, nextY));
        while (player.isAt(startX, startY) && canContinue(player, clip, plane)) {
            if (advance(candidate, searchBudget())) {
                MapWalkPath.Path path = candidate.result();
                if (path == null) {
                    unreachable(player);
                    return;
                }
                if (path.stopsAtDoor) {
                    path = preferOpenPath(event, player, start, clip, targetX, targetY, deadline, path);
                    if (path == null) return;
                }
                runPath(event, player, clip, start, path);
                return;
            }
            if (Server.currentTick() >= deadline) {
                timedOut(player);
                return;
            }
            event.delay(1);
        }
    }

    private static MapWalkPath.Path preferOpenPath(Event event, Player player, Position start, WalkClip clip,
            int targetX, int targetY, long deadline, MapWalkPath.Path doorPath) throws Pausable {
        // Before using a door stop, prove there is no fully open path. Searching
        // from both sides lets an enclosed building prove that quickly.
        int plane = start.getZ(), size = player.getSize(), startX = start.getX(), startY = start.getY();
        MapWalkPath.Search forward = new MapWalkPath.Search(clip, plane, size, startX, startY, targetX, targetY, null);
        MapWalkPath.Search reverse = new MapWalkPath.Search(clip, plane, size, targetX, targetY, startX, startY, null, true);
        while (player.isAt(startX, startY) && canContinue(player, clip, plane)) {
            int grant = searchBudget();
            if (advance(forward, grant / 2)) {
                usedBudget -= grant - grant / 2;
                return forward.result() == null ? doorPath : forward.result();
            }
            if (advance(reverse, grant - grant / 2))
                return reverse.result() == null ? doorPath : reverse.result();
            if (Server.currentTick() >= deadline) {
                timedOut(player);
                return null;
            }
            event.delay(1);
        }
        return null;
    }

    private static boolean advance(MapWalkPath.Search search, int grant) {
        boolean complete = search.advance(grant);
        usedBudget -= grant - search.processed;
        return complete;
    }

    private static void timedOut(Player player) {
        player.getPacketSender().resetMapFlag();
        player.sendMessage("This walking route took too long to find.");
    }

    private static void runPath(Event event, Player player, WalkClip clip, Position start, MapWalkPath.Path path) throws Pausable {
        int plane = start.getZ(), expectedX = start.getX(), expectedY = start.getY();
        Movement movement = player.getMovement();
        int offset = 0;
        while (offset < path.tiles.length) {
            if (!canContinue(player, clip, plane) || !movement.isAtDestination()
                    || !player.isAt(expectedX, expectedY)) return;
            int[] stepsX = movement.getStepsX(), stepsY = movement.getStepsY();
            int count = Math.min(path.tiles.length - offset, SEGMENT_TILES);
            for (int i = 0; i < count; i++, offset++) {
                int tile = path.tiles[offset];
                stepsX[i] = MapWalkPath.x(tile);
                stepsY[i] = MapWalkPath.y(tile);
                if (!clip.walkableDomain(stepsX[i], stepsY[i], plane)) {
                    unreachable(player);
                    return;
                }
            }
            movement.readOffset = 0;
            movement.writeOffset = count;
            movement.stepType = StepType.NORMAL;
            int idleTicks = 0;
            while (!movement.isAtDestination()) {
                int previousX = player.getAbsX(), previousY = player.getAbsY();
                event.delay(1);
                if (!canContinue(player, clip, plane)) {
                    movement.reset();
                    player.getPacketSender().resetMapFlag();
                    return;
                }
                if (player.isAt(previousX, previousY)) {
                    if (++idleTicks >= 2) break;
                } else {
                    idleTicks = 0;
                }
            }
            expectedX = stepsX[count - 1];
            expectedY = stepsY[count - 1];
            if (!player.isAt(expectedX, expectedY)) {
                movement.reset();
                unreachable(player);
                return;
            }
        }
        if (path.stopsAtDoor) {
            player.getPacketSender().resetMapFlag();
            player.sendMessage("Stopped at the closed door or gate. Open it and tap your destination again.");
        }
    }

    private static boolean canContinue(Player player, WalkClip clip, int plane) {
        return player.getHeight() == plane && clip.walkableDomain(player.getAbsX(), player.getAbsY(), plane)
                && !player.isLocked() && !player.getMovement().isTeleportQueued()
                && !player.getCombat().isDead() && !player.isMovementBlocked(false, false);
    }

    private static int searchBudget() {
        long tick = Server.currentTick();
        if (tick != budgetTick) {
            budgetTick = tick;
            usedBudget = 0;
        }
        int grant = Math.min(PER_ROUTE_TICK_BUDGET, GLOBAL_TICK_BUDGET - usedBudget);
        usedBudget += grant;
        return grant;
    }

    private static void unreachable(Player player) {
        player.getPacketSender().resetMapFlag();
        player.getMovement().outOfReach();
    }

    private static boolean closedDoor(WalkClip clip, int plane, int x, int y, int nextX, int nextY) {
        if (!clip.walkableDomain(x, y, plane) || !clip.walkableDomain(nextX, nextY, plane)) return false;
        return doorBetween(clip, plane, x, y, nextX, nextY);
    }

    private static boolean doorBetween(ClipUtils clip, int plane, int x, int y, int nextX, int nextY) {
        Direction direction = Direction.getDirection(nextX - x, nextY - y);
        int entry, entryMask;
        switch (direction) {
            case WEST: entry = 8; entryMask = RouteFinder.WEST_MASK; break;
            case EAST: entry = 128; entryMask = RouteFinder.EAST_MASK; break;
            case NORTH: entry = 32; entryMask = RouteFinder.NORTH_MASK; break;
            case SOUTH: entry = 2; entryMask = RouteFinder.SOUTH_MASK; break;
            default: return false;
        }
        if ((clip.getAbs(nextX, nextY, plane) & entryMask) != entry) return false;
        Tile from = Tile.get(x, y, plane, false), to = Tile.get(nextX, nextY, plane, false);
        int fromDoors = standardWallDoor(from, direction);
        int toDoors = standardWallDoor(to, Direction.getDirection(x - nextX, y - nextY));
        return fromDoors >= 0 && toDoors >= 0 && fromDoors + toDoors == 1;
    }

    /** First permission-free door on a bounded route through safe static terrain. */
    public static GameObject firstSafeDoor(Position from, Position to, int budget) {
        int plane = from.getZ();
        if (plane != to.getZ() || !validCoordinates(to.getX(), to.getY())) return null;
        ClipUtils safe = new ClipUtils() {
            @Override
            public int getAbs(int x, int y, int z) {
                if (!validCoordinates(x, y) || Math.abs(x - from.getX()) > 61 || Math.abs(y - from.getY()) > 61)
                    return RouteFinder.UNMOVABLE_MASK;
                Region region = Region.get(x, y);
                return region == null || region.empty || region.dynamicIndex >= 0 || Wilderness.getLevel(x, y, z) > 0
                        ? RouteFinder.UNMOVABLE_MASK : ClipUtils.REGULAR.getAbs(x, y, z);
            }
        };
        MapWalkPath.Search search = new MapWalkPath.Search(safe, plane, 1, from.getX(), from.getY(),
                to.getX(), to.getY(), (x, y, nextX, nextY) -> doorBetween(safe, plane, x, y, nextX, nextY));
        if (!search.advance(Math.min(2048, budget))) return null;
        MapWalkPath.Path path = search.result();
        if (path == null || !path.stopsAtDoor) return null;
        int x = MapWalkPath.x(path.doorApproach), y = MapWalkPath.y(path.doorApproach);
        int nextX = MapWalkPath.x(path.doorBeyond), nextY = MapWalkPath.y(path.doorBeyond);
        Direction side = Direction.getDirection(nextX - x, nextY - y);
        GameObject door = standardDoorAt(Tile.get(x, y, plane, false), side);
        return door != null ? door : standardDoorAt(Tile.get(nextX, nextY, plane, false),
                Direction.getDirection(x - nextX, y - nextY));
    }

    private static GameObject standardDoorAt(Tile tile, Direction side) {
        if (tile == null || tile.gameObjects == null) return null;
        for (GameObject object : tile.gameObjects) {
            if (Direction.fromDoorDirection(object.direction) == side && Door.hasStandardOpening(object)) return object;
        }
        return null;
    }

    // -1 denotes another (possibly restricted) object occupying this edge.
    private static int standardWallDoor(Tile tile, Direction side) {
        if (tile == null || tile.gameObjects == null) return 0;
        int count = 0;
        for (GameObject object : tile.gameObjects) {
            if (object.id == -1 || (object.type != 0 && object.type != 2) || object.getDef() == null
                    || object.getDef().clipType == 0) continue;
            boolean occupiesEdge = Direction.fromDoorDirection(object.direction) == side
                    || (object.type == 2 && Direction.fromDoorDirection((object.direction + 1) & 3) == side);
            if (!occupiesEdge) continue;
            if (!Door.hasStandardOpening(object)) return -1;
            count++;
        }
        return count;
    }

    private static final class WalkClip extends ClipUtils {
        private final ClipUtils nativeClip;
        private final int lease, minX, maxX, minY, maxY;

        private WalkClip(ClipUtils nativeClip, Position start) {
            this.nativeClip = nativeClip;
            lease = Region.get(start.getX(), start.getY()).dynamicIndex;
            // Upper-floor terrain has no continuous world-map surface. Preserve the native
            // scene footprint there rather than treating absent upper floors as empty land.
            int sceneX = (start.getFirstChunkX() - 6) * 8;
            int sceneY = (start.getFirstChunkY() - 6) * 8;
            boolean higherStaticFloor = lease == -1 && start.getZ() != 0;
            minX = higherStaticFloor ? sceneX : 0;
            minY = higherStaticFloor ? sceneY : 0;
            maxX = higherStaticFloor ? sceneX + Region.CLIENT_SIZE : 8192;
            maxY = higherStaticFloor ? sceneY + Region.CLIENT_SIZE : 16384;
        }

        private boolean walkableDomain(int x, int y, int z) {
            if (!validCoordinates(x, y) || x < minX || y < minY || x >= maxX || y >= maxY)
                return false;
            Region region = Region.get(x, y);
            if (region == null || region.empty || region.dynamicIndex != lease) return false;
            if (lease == -1) return true;
            if (region.dynamicData == null) return false;
            int[] chunk = region.dynamicData[z][(x & 63) >> 3][(y & 63) >> 3];
            return chunk[0] != 0 || chunk[1] != 0;
        }

        @Override
        public int getAbs(int x, int y, int z) {
            return walkableDomain(x, y, z) ? nativeClip.getAbs(x, y, z) : RouteFinder.UNMOVABLE_MASK;
        }
    }
}
