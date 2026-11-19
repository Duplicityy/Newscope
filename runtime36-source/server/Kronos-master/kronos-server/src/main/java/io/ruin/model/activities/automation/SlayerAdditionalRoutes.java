package io.ruin.model.activities.automation;

import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.network.incoming.handlers.ObjectActionHandler;

/** Native passages and footpaths that need an activity-specific access check. */
final class SlayerAdditionalRoutes {
    private static final int EARTH_WARRIOR = 2840;
    private static final int BRASS_KEY = 983;
    private static final int GODWARS_ENTRANCE_ID = 26419;
    private static final int ROPE_ACTION = 1;
    private static final Position[] ICE_MOUNTAIN = {
            at(3064, 3446), at(3040, 3470), at(3009, 3472)
    };
    private static final SlayerWorld.Passage BRASS_DOOR = new BrassDoor();
    private static final SlayerWorld.Passage EARTH_LADDER = new SlayerWorld.Passage(
            17384, 3116, 3452, 0, "climb-down", 3117, 9852, 0);
    private static final SlayerWorld.Passage GODWARS_ENTRANCE = new GodwarsRope();
    private static final SlayerWorld.Passage KRAKEN_ENTRANCE = new SlayerWorld.Passage(
            30177, 2277, 3611, 0, "enter", 2276, 9988, 0);
    private static final SlayerWorld.Passage MITHRIL_STAIRS = new SlayerWorld.Passage(
            25339, 1778, 5344, 0, "climb-up", 1778, 5343, 1, true);

    private SlayerAdditionalRoutes() {}

    static boolean godwarsReady(Player p) {
        return Config.GODWARS_DUNGEON.get(p) != 0 && SlayerLoadout.godProtection(p);
    }

    static boolean earthWarriorsReady(Player p) {
        return p.getInventory().hasId(BRASS_KEY);
    }

    static boolean safeFor(Player p, NPC npc) {
        if (npc.getId() == EARTH_WARRIOR && !earthWarriorsReady(p)) return false;
        return (npc.getId() != EARTH_WARRIOR || earthWarriorsReady(p))
                && SlayerDragonRoutes.safeFor(p, npc) && (!godwars(npc.spawnPosition) || godwarsReady(p));
    }

    static SlayerWorld.Route route(SlayerWorld world, Player p, NPC npc, boolean allowTeleports) {
        Position from = p.getPosition();
        Position target = npc.isHidden() ? npc.spawnPosition : npc.getPosition();
        if (godwars(npc.spawnPosition) && godwarsReady(p))
            return godwarsRoute(world, from, npc, target, allowTeleports);
        if (npc.getId() == EARTH_WARRIOR && earthWarriorsReady(p))
            return earthRoute(world, from, target, npc.getSize(), allowTeleports);
        SlayerLoadout combat = SlayerLoadout.forNpc(npc);
        if (combat != null && combat.requiresRanged() && krakenCove(npc.spawnPosition))
            return krakenRoute(world, from, npc, allowTeleports);
        if (combat == SlayerLoadout.MITHRIL_DRAGON)
            return mithrilRoute(world, from, npc, allowTeleports);
        return SlayerDragonRoutes.route(world, p, npc, allowTeleports);
    }

    static SlayerWorld.Route corridor(Position from, Position to, int size, int cost) {
        if (!within(to, 2997, 3465, 3007, 3501, 0) || !within(from, 2990, 3405, 3115, 3518, 0))
            return null;
        int remaining = SlayerWorld.distance(ICE_MOUNTAIN[ICE_MOUNTAIN.length - 1], to);
        if (!SlayerWorld.reachable(ICE_MOUNTAIN[ICE_MOUNTAIN.length - 1], to, size)) return null;
        for (int i = ICE_MOUNTAIN.length - 1; i >= 0; i--) {
            Position waypoint = ICE_MOUNTAIN[i];
            if (from.equals(waypoint)) return null;
            if (SlayerWorld.walkable(from, waypoint))
                return SlayerWorld.Route.waypoint(waypoint, cost + remaining + SlayerWorld.distance(from, waypoint));
            if (i > 0 && !SlayerWorld.walkable(ICE_MOUNTAIN[i - 1], waypoint)) return null;
            if (i > 0) remaining += SlayerWorld.distance(ICE_MOUNTAIN[i - 1], waypoint);
        }
        return null;
    }

    private static SlayerWorld.Route earthRoute(SlayerWorld world, Position from, Position target, int size,
                                                 boolean allowTeleports) {
        GameObject door = BRASS_DOOR.object(), ladder = EARTH_LADDER.object();
        if (door == null || ladder == null || !SlayerWorld.reachable(EARTH_LADDER.exit, target, size)
                || !SlayerWorld.reachable(BRASS_DOOR.exit, ladder)) return null;
        if (from.equals(BRASS_DOOR.exit))
            return SlayerWorld.Route.passage(EARTH_LADDER, 33);
        if (from.equals(BRASS_DOOR.approach))
            return SlayerWorld.Route.passage(BRASS_DOOR, 36);
        if (within(from, 3070, 3405, 3150, 3453, 0) && SlayerWorld.walkable(from, BRASS_DOOR.approach))
            return SlayerWorld.Route.waypoint(BRASS_DOOR.approach, 36 + SlayerWorld.distance(from, BRASS_DOOR.approach));
        SlayerWorld.Portal village = allowTeleports ? world.portal("Barbarian Village") : null;
        return village != null && !from.isWithinDistance(village.tile, 2)
                && SlayerWorld.walkable(village.tile, BRASS_DOOR.approach)
                ? SlayerWorld.Route.portal(village, 112) : null;
    }

    private static SlayerWorld.Route godwarsRoute(SlayerWorld world, Position from, NPC npc, Position target,
                                                    boolean allowTeleports) {
        if (godwars(from)) return rangedRoute(from, npc);
        GameObject entrance = GODWARS_ENTRANCE.object();
        if (entrance == null || !SlayerWorld.safeTile(GODWARS_ENTRANCE.exit)) return null;
        SlayerLoadout combat = SlayerLoadout.forNpc(npc);
        if (combat == null || !(combat.requiresRanged()
                ? SlayerRangedTargeting.firingPosition(GODWARS_ENTRANCE.exit, npc) != null
                : SlayerWorld.reachable(GODWARS_ENTRANCE.exit, target, npc.getSize()))) return null;
        return throughEntrance(world, from, GODWARS_ENTRANCE, entrance, "Godwars Dungeon", allowTeleports, 28);
    }

    private static SlayerWorld.Route krakenRoute(SlayerWorld world, Position from, NPC npc, boolean allowTeleports) {
        if (krakenCove(from)) return rangedRoute(from, npc);
        GameObject entrance = KRAKEN_ENTRANCE.object();
        if (entrance == null || !SlayerWorld.safeTile(KRAKEN_ENTRANCE.exit)
                || SlayerRangedTargeting.firingPosition(KRAKEN_ENTRANCE.exit, npc) == null) return null;
        return throughEntrance(world, from, KRAKEN_ENTRANCE, entrance, "Kraken Cove", allowTeleports, 40);
    }

    private static SlayerWorld.Route mithrilRoute(SlayerWorld world, Position from, NPC npc, boolean allowTeleports) {
        if (within(from, 1745, 5310, 1797, 5369, 1)) return rangedRoute(from, npc);
        GameObject stairs = MITHRIL_STAIRS.object();
        if (stairs == null || !SlayerWorld.safeTile(MITHRIL_STAIRS.exit)
                || SlayerRangedTargeting.firingPosition(MITHRIL_STAIRS.exit, npc) == null) return null;
        return throughEntrance(world, from, MITHRIL_STAIRS, stairs, "Ancient Cavern", allowTeleports, 28);
    }

    private static SlayerWorld.Route rangedRoute(Position from, NPC npc) {
        SlayerLoadout combat = SlayerLoadout.forNpc(npc);
        if (combat == null || !combat.requiresRanged()) return null;
        Position firing = SlayerRangedTargeting.firingPosition(from, npc);
        if (firing == null) return null;
        return firing.equals(from) ? SlayerWorld.Route.walk(firing, 0)
                : SlayerWorld.Route.waypoint(firing, SlayerWorld.distance(from, firing));
    }

    private static SlayerWorld.Route throughEntrance(SlayerWorld world, Position from, SlayerWorld.Passage passage,
                                                       GameObject entrance, String portalName, boolean allowTeleports,
                                                       int cost) {
        if (SlayerWorld.reachable(from, entrance))
            return SlayerWorld.Route.passage(passage, SlayerWorld.distance(from, passage.entry) + cost);
        SlayerWorld.Portal portal = allowTeleports ? world.portal(portalName) : null;
        return portal != null && !from.isWithinDistance(portal.tile, 2) && SlayerWorld.reachable(portal.tile, entrance)
                ? SlayerWorld.Route.portal(portal, cost + 42) : null;
    }

    private static Position at(int x, int y) { return new Position(x, y, 0); }

    private static boolean godwars(Position tile) { return within(tile, 2816, 5249, 2943, 5375, 2); }

    private static boolean krakenCove(Position tile) { return within(tile, 2235, 9987, 2310, 10019, 0); }

    private static boolean within(Position p, int x1, int y1, int x2, int y2, int z) {
        return p.getZ() == z && p.getX() >= x1 && p.getX() <= x2 && p.getY() >= y1 && p.getY() <= y2;
    }

    private static final class BrassDoor extends SlayerWorld.Passage {
        BrassDoor() {
            super(1804, 3115, 3450, 0, "open", 3115, 3450, 0, false, at(3115, 3449));
        }

        @Override
        boolean use(Player player) {
            return earthWarriorsReady(player) && super.use(player);
        }
    }

    private static final class GodwarsRope extends SlayerWorld.Passage {
        GodwarsRope() {
            super(GODWARS_ENTRANCE_ID, 2917, 3745, 0, "climb-down", 2882, 5311, 2, true);
        }

        @Override
        GameObject object() {
            GameObject hole = Tile.getObject(id, entry.getX(), entry.getY(), entry.getZ());
            // Native entrance registers the first option on the base ID. The client's rope varbit selects its morph.
            return hole != null && hole.getDef().defaultActions != null
                    && hole.getDef().defaultActions[ROPE_ACTION - 1] != null ? hole : null;
        }

        @Override
        boolean use(Player player) {
            if (!godwarsReady(player) || object() == null) return false;
            ObjectActionHandler.handleAction(player, ROPE_ACTION, id, entry.getX(), entry.getY(), 0);
            return true;
        }
    }
}
