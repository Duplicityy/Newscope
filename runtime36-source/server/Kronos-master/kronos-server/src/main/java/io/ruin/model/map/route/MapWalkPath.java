package io.ruin.model.map.route;

import gnu.trove.map.hash.TIntObjectHashMap;
import io.ruin.model.map.ClipUtils;
import io.ruin.model.map.Direction;
import io.ruin.model.map.route.routes.DumbRoute;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Incremental A* over the server clipping map. A door edge may be considered but is never queued. */
final class MapWalkPath {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int DOOR_STEP_COST = 41;

    interface DoorEdges {
        boolean canOpenBetween(int x, int y, int nextX, int nextY);
    }

    static final class Search {
        private static final Comparator<Node> ORDER = Comparator.comparingInt((Node node) -> node.score)
                .thenComparingInt(node -> node.estimate);
        private final ClipUtils clip;
        private final int plane, size, targetX, targetY, startTile;
        private final DoorEdges doors;
        private final boolean reverse;
        private final PriorityQueue<Node> open = new PriorityQueue<>(ORDER);
        private final TIntObjectHashMap<Node> best = new TIntObjectHashMap<>();
        private Path path;
        int processed;

        Search(ClipUtils clip, int plane, int size, int x, int y, int targetX, int targetY, DoorEdges doors) {
            this(clip, plane, size, x, y, targetX, targetY, doors, false);
        }

        Search(ClipUtils clip, int plane, int size, int x, int y, int targetX, int targetY, DoorEdges doors, boolean reverse) {
            this.clip = clip;
            this.reverse = reverse;
            this.plane = plane;
            this.size = size;
            this.targetX = targetX;
            this.targetY = targetY;
            this.doors = doors;
            startTile = pack(x, y);
            Node start = new Node(startTile, null, 0, distance(x, y), false);
            open.add(start);
            best.put(start.tile, start);
        }

        boolean advance(int budget) {
            for (processed = 0; processed < budget && !open.isEmpty(); processed++) {
                Node node = open.remove();
                if (best.get(node.tile) != node) continue;
                if (node.tile == pack(targetX, targetY)) {
                    processed++;
                    path = pathFrom(node);
                    if (reverse) path.reverseTo(startTile);
                    return true;
                }
                int x = x(node.tile), y = y(node.tile);
                for (Direction direction : DIRECTIONS) {
                    int nextX = x + direction.deltaX, nextY = y + direction.deltaY;
                    boolean blocked = reverse
                            ? DumbRoute.getDirection(clip, nextX, nextY, plane, size, x, y) == null
                            : DumbRoute.getDirection(clip, x, y, plane, size, nextX, nextY) == null;
                    // In a reverse search, the next search node is the *source* of this
                    // directed edge, so ordinary entry clipping does not check that tile.
                    if (reverse && pack(nextX, nextY) != pack(targetX, targetY)
                            && (clip.getAbs(nextX, nextY, plane) & RouteFinder.UNMOVABLE_MASK) != 0)
                        continue;
                    if (blocked && (doors == null || size != 1 || direction.deltaX * direction.deltaY != 0
                            || !doors.canOpenBetween(x, y, nextX, nextY))) continue;
                    int cost = node.cost + (blocked ? DOOR_STEP_COST : 1);
                    int tile = pack(nextX, nextY);
                    Node prior = best.get(tile);
                    if (prior != null && prior.cost <= cost) continue;
                    Node next = new Node(tile, node, cost, distance(nextX, nextY), blocked);
                    open.add(next);
                    best.put(tile, next);
                }
            }
            return open.isEmpty();
        }

        private int distance(int x, int y) {
            return Math.max(Math.abs(targetX - x), Math.abs(targetY - y));
        }

        Path result() {
            return path;
        }
    }

    private static Path pathFrom(Node destination) {
        Node last = destination;
        int firstDoor = -1;
        int steps = 0;
        for (Node node = destination; node.previous != null; node = node.previous) {
            if (node.closedDoor) {
                firstDoor = node.tile;
                last = node.previous;
                steps = 0;
            } else {
                steps++;
            }
        }
        int[] tiles = new int[steps];
        for (Node node = last; steps > 0; node = node.previous) {
            tiles[--steps] = node.tile;
        }
        return new Path(tiles, last != destination, last.tile, firstDoor);
    }

    static final class Path {
        final int[] tiles;
        final boolean stopsAtDoor;
        final int doorApproach, doorBeyond;

        Path(int[] tiles, boolean stopsAtDoor, int doorApproach, int doorBeyond) {
            this.tiles = tiles;
            this.stopsAtDoor = stopsAtDoor;
            this.doorApproach = doorApproach;
            this.doorBeyond = doorBeyond;
        }

        private void reverseTo(int finish) {
            for (int i = 0, j = tiles.length - 2; i < j; i++, j--) {
                int swap = tiles[i];
                tiles[i] = tiles[j];
                tiles[j] = swap;
            }
            if (tiles.length != 0) tiles[tiles.length - 1] = finish;
        }
    }

    private static final class Node {
        final int tile, cost, score, estimate;
        final Node previous;
        final boolean closedDoor;

        Node(int tile, Node previous, int cost, int estimate, boolean closedDoor) {
            this.tile = tile;
            this.previous = previous;
            this.cost = cost;
            this.estimate = estimate;
            this.score = cost + estimate;
            this.closedDoor = closedDoor;
        }
    }

    static int pack(int x, int y) {
        return (x << 14) | y;
    }

    static int x(int tile) {
        return tile >>> 14;
    }

    static int y(int tile) {
        return tile & 0x3fff;
    }
}
