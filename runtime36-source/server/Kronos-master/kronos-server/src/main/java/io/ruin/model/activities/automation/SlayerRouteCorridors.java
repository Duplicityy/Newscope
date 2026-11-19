package io.ruin.model.activities.automation;

import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;

/** Waypoints on long connected routes that do not fit a single local pathfinder search. */
final class SlayerRouteCorridors {
    private static final Position[] FREMENNIK = {
            at(2789, 10039), at(2760, 10023), at(2744, 10020), at(2720, 10026)
    };
    private static final Position[] KALPHITE = {
            at(3265, 3132), at(3305, 3110)
    };

    private SlayerRouteCorridors() {}

    static SlayerWorld.Route route(Position from, Position to, int size, int cost) {
        SlayerWorld.Route tower = SlayerTowerRoutes.route(from, to, size, cost);
        if (tower != null) return tower;
        if (from.getZ() != 0 || to.getZ() != 0) return null;
        if (within(from, 2680, 9955, 2820, 10050) && within(to, 2680, 9955, 2765, 10020))
            return nextFremennikWaypoint(from, to, size, cost);
        if (!within(from, 3205, 3070, 3330, 3180) || !within(to, 3295, 9485, 3365, 9550))
            return null;
        GameObject entrance = SlayerWorld.KALPHITE_CAVE_ENTRY.object();
        if (entrance == null || !SlayerWorld.reachable(SlayerWorld.KALPHITE_CAVE_ENTRY.exit, to, size))
            return null;
        for (int i = 0; i < KALPHITE.length; i++) {
            Position waypoint = KALPHITE[i];
            if (!SlayerWorld.reachable(waypoint, entrance)) continue;
            SlayerWorld.Route route = follow(from, KALPHITE, i, cost + 20);
            if (route != null) return route;
        }
        return null;
    }

    private static SlayerWorld.Route nextFremennikWaypoint(Position from, Position to, int size, int cost) {
        for (int i = 0; i < FREMENNIK.length; i++) {
            if (!SlayerWorld.reachable(FREMENNIK[i], to, size)) continue;
            SlayerWorld.Route route = follow(from, FREMENNIK, i, cost + SlayerWorld.distance(FREMENNIK[i], to));
            if (route != null) return route;
        }
        return null;
    }

    private static SlayerWorld.Route follow(Position from, Position[] corridor, int last, int cost) {
        int remaining = 0;
        for (int i = last; i >= 0; i--) {
            Position waypoint = corridor[i];
            if (from.equals(waypoint)) return null;
            if (SlayerWorld.walkable(from, waypoint))
                return SlayerWorld.Route.waypoint(waypoint, cost + remaining + SlayerWorld.distance(from, waypoint));
            if (i == 0 || !SlayerWorld.walkable(corridor[i - 1], waypoint)) return null;
            remaining += SlayerWorld.distance(corridor[i - 1], waypoint);
        }
        return null;
    }

    private static Position at(int x, int y) { return new Position(x, y, 0); }

    private static boolean within(Position p, int x, int y, int maxX, int maxY) {
        return p.getX() >= x && p.getX() <= maxX && p.getY() >= y && p.getY() <= maxY;
    }
}
