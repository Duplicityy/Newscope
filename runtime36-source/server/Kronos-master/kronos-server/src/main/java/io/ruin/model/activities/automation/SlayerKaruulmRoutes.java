package io.ruin.model.activities.automation;

import io.ruin.model.entity.player.Player;
import io.ruin.model.map.Position;

/** The native Karuulm rock crossings on the safe approach to Wyrms and Drakes. */
final class SlayerKaruulmRoutes {
    private static final SlayerWorld.Passage WEST_ROCK = new ProtectedRock(1302, 1303, 1301);
    private static final SlayerWorld.Passage EAST_ROCK = new ProtectedRock(1321, 1320, 1322);
    private static final SlayerWorld.Passage DRAKE_STAIR = new SlayerWorld.Passage(
            34530, 1330, 10205, 0, "climb", 1334, 10205, 1);

    private SlayerKaruulmRoutes() {}

    static SlayerWorld.Route corridor(Position from, Position to, int size, int cost) {
        if (from.getZ() != 0 || from.getX() < 1303 || from.getX() > 1334
                || from.getY() < 10187 || from.getY() > 10215) return null;
        if (to.getZ() == 0 && to.getX() >= 1248 && to.getX() <= 1288 && to.getY() >= 10147 && to.getY() <= 10198
                && SlayerWorld.reachable(WEST_ROCK.exit, to, size))
            return rock(from, WEST_ROCK, cost + SlayerWorld.distance(WEST_ROCK.exit, to));
        if (to.getZ() == 1 && to.getX() >= 1304 && to.getX() <= 1366 && to.getY() >= 10223 && to.getY() <= 10255
                && DRAKE_STAIR.object() != null && SlayerWorld.reachable(DRAKE_STAIR.exit, to, size)
                && SlayerWorld.reachable(EAST_ROCK.exit, DRAKE_STAIR.object())) {
            if (SlayerWorld.reachable(from, DRAKE_STAIR.object()))
                return SlayerWorld.Route.passage(DRAKE_STAIR, cost + SlayerWorld.distance(from, DRAKE_STAIR.entry) + 35);
            return rock(from, EAST_ROCK, cost + 45);
        }
        return null;
    }

    private static SlayerWorld.Route rock(Position from, SlayerWorld.Passage rock, int cost) {
        if (rock.object() == null || !SlayerWorld.walkable(from, rock.approach)) return null;
        if (from.equals(rock.approach)) return SlayerWorld.Route.passage(rock, cost + 8);
        return SlayerWorld.Route.waypoint(rock.approach, cost + SlayerWorld.distance(from, rock.approach) + 8);
    }

    private static final class ProtectedRock extends SlayerWorld.Passage {
        ProtectedRock(int objectX, int approachX, int exitX) {
            super(34544, objectX, 10205, 0, "climb", exitX, 10205, 0, true, new Position(approachX, 10205, 0));
        }

        @Override
        boolean use(Player player) {
            return SlayerLoadout.WYRM.protection(player) && super.use(player);
        }
    }
}
