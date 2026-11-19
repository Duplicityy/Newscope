package io.ruin.model.activities.automation;

import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.impl.Door;

/** Approach to the tower stairs through the native paired entrance doors. */
final class SlayerTowerRoutes {
    static final int FIRST_STAIR = 2114;
    private static final Position COURTYARD = new Position(3428, 3534, 0);
    private static final Position UPPER_LANDING = new Position(3433, 3537, 1);
    private static final int[] TOWER_DOORS = {2108, 2111};

    private SlayerTowerRoutes() {}

    static SlayerWorld.Route route(Position from, Position to, int size, int cost) {
        if (!from.isWithinDistance(COURTYARD, 10) || to.getZ() != UPPER_LANDING.getZ()
                || SlayerWorld.distance(UPPER_LANDING, to) > 45
                || !SlayerWorld.reachable(UPPER_LANDING, to, size)) return null;
        GameObject staircase = Tile.getObject(FIRST_STAIR, 3434, 3537, 0);
        if (staircase == null || SlayerWorld.reachable(from, staircase)) return null;
        for (int i = 0; i < TOWER_DOORS.length; i++) {
            GameObject door = Tile.getObject(TOWER_DOORS[i], 3428 + i, 3535, 0);
            if (door != null && Door.hasStandardOpening(door) && SlayerWorld.reachable(from, door))
                return SlayerWorld.Route.door(door, cost + 24 + SlayerWorld.distance(UPPER_LANDING, to));
        }
        return null;
    }
}
