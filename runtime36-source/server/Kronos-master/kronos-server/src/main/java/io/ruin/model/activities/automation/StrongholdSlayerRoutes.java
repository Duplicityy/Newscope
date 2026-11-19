package io.ruin.model.activities.automation;

import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;

import static io.ruin.model.activities.automation.SlayerWorld.Passage;
import static io.ruin.model.activities.automation.SlayerWorld.Route;

/** Native Stronghold of Security actions, including the approached side of each custom gate. */
final class StrongholdSlayerRoutes {
    static final int ENTRANCE = 20790;
    private static final Passage ENTER = new Passage(ENTRANCE, 3081, 3420, 0, "climb-down", 1859, 5243, 0, true);
    private static final Passage FIRST_PORTAL = new Passage(20786, 1863, 5238, 0, "use", 1914, 5222, 0);
    private static final Passage SECOND_FLOOR = new Passage(20785, 1902, 5222, 0, "climb-down", 2042, 5245, 0);
    private static final Passage SECOND_PORTAL = new Passage(19005, 2039, 5240, 0, "use", 2021, 5223, 0);
    private static final Passage THIRD_FLOOR = new Passage(19004, 2026, 5218, 0, "climb-down", 2123, 5252, 0);
    private static final Passage[] FIRST = {
            ENTER, gate(19206, 1858, 5238, 1858, 5239, 1858, 5238),
            gate(19206, 1858, 5235, 1858, 5236, 1858, 5235),
            gate(19207, 1865, 5227, 1864, 5227, 1865, 5227),
            gate(19207, 1868, 5227, 1867, 5227, 1868, 5227)
    };
    private static final Passage[] SECOND = {
            ENTER, FIRST_PORTAL, SECOND_FLOOR,
            gate(17009, 2044, 5239, 2044, 5240, 2044, 5239),
            gate(17100, 2044, 5237, 2044, 5237, 2044, 5236)
    };
    private static final Passage[] SECOND_NORTH = {
            ENTER, FIRST_PORTAL, SECOND_FLOOR,
            gate(17100, 2039, 5244, 2040, 5244, 2039, 5244),
            gate(17100, 2037, 5245, 2037, 5245, 2036, 5245)
    };
    private static final Passage[] THIRD = {
            ENTER, FIRST_PORTAL, SECOND_FLOOR, SECOND_PORTAL, THIRD_FLOOR,
            gate(23653, 2132, 5257, 2132, 5256, 2132, 5257),
            gate(23653, 2132, 5260, 2132, 5259, 2132, 5260),
            gate(23654, 2137, 5263, 2137, 5263, 2138, 5263),
            gate(23654, 2140, 5263, 2140, 5263, 2141, 5263)
    };

    private StrongholdSlayerRoutes() {}

    static Route route(Position from, Position to, int size, int cost) {
        if (from.getZ() != 0 || to.getZ() != 0 || !stronghold(to)) return null;
        if (!stronghold(from) && SlayerWorld.distance(from, ENTER.entry) > 58) return null;
        Passage[] corridor;
        if (to.getX() < 1930) corridor = FIRST;
        else if (to.getX() < 2090) {
            Route south = route(from, to, size, cost, SECOND);
            Route north = route(from, to, size, cost, SECOND_NORTH);
            return SlayerWorld.better(north, south) ? north : south;
        }
        else corridor = THIRD;
        return route(from, to, size, cost, corridor);
    }

    private static Route route(Position from, Position to, int size, int cost, Passage[] corridor) {
        for (int last = corridor.length - 1; last >= 0; last--) {
            if (!SlayerWorld.reachable(corridor[last].exit, to, size)) continue;
            Route route = follow(from, to, cost, corridor, last);
            if (route != null) return route;
        }
        return null;
    }

    private static Route follow(Position from, Position goal, int cost, Passage[] corridor, int last) {
        int remaining = SlayerWorld.distance(corridor[last].exit, goal);
        for (int i = last; i >= 0; i--) {
            Passage step = corridor[i];
            if (from.equals(step.exit)) return null;
            if (canApproach(from, step)) {
                int stepCost = cost + remaining + 10 + SlayerWorld.distance(from, step.entry);
                if (step.approach != null && !from.equals(step.approach)) return Route.waypoint(step.approach, stepCost);
                return Route.passage(step, stepCost);
            }
            if (i == 0 || !canApproach(corridor[i - 1].exit, step)) return null;
            remaining += 10 + SlayerWorld.distance(corridor[i - 1].exit, step.entry);
        }
        return null;
    }

    private static boolean canApproach(Position from, Passage step) {
        if (SlayerWorld.distance(from, step.entry) > 58) return false;
        GameObject object = step.object();
        if (object == null || !SlayerWorld.safeTile(step.exit)) return false;
        if (step.approach == null) return SlayerWorld.reachable(from, object);
        return SlayerWorld.walkable(from, step.approach) && SlayerWorld.withinInteraction(step.approach, object);
    }

    private static boolean stronghold(Position tile) {
        return tile.getX() >= 1850 && tile.getX() <= 2180 && tile.getY() >= 5170 && tile.getY() <= 5310;
    }

    private static Passage gate(int id, int x, int y, int approachX, int approachY, int exitX, int exitY) {
        return new Passage(id, x, y, 0, "open", exitX, exitY, 0, true, new Position(approachX, approachY, 0));
    }
}
