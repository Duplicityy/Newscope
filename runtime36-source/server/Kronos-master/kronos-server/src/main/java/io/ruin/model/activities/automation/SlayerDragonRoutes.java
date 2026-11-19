package io.ruin.model.activities.automation;

import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;

/** Native routes to smaller dragons with known combat profiles. */
final class SlayerDragonRoutes {
    private static final int BABY_BLACK_DRAGON = 1872;
    private static final int BLACK_DRAGON_STAIR_AGILITY = 80;
    private static final SlayerWorld.Passage BLACK_DRAGON_STAIRS = new SlayerWorld.Passage(
            30189, 2881, 9825, 0, "climb", 2880, 9825, 1) {
        @Override
        boolean use(Player player) {
            return blackDragonsReady(player) && super.use(player);
        }
    };

    private SlayerDragonRoutes() {}

    static boolean blackDragonsReady(Player player) {
        Stat agility = player.getStats().get(StatType.Agility);
        return agility.fixedLevel >= BLACK_DRAGON_STAIR_AGILITY && agility.currentLevel >= BLACK_DRAGON_STAIR_AGILITY;
    }

    static boolean safeFor(Player player, NPC npc) {
        return npc.getId() != BABY_BLACK_DRAGON || blackDragonsReady(player);
    }

    static SlayerWorld.Route route(SlayerWorld world, Player player, NPC npc, boolean allowTeleports) {
        if (npc.getId() != BABY_BLACK_DRAGON || !blackDragonsReady(player)) return null;
        Position from = player.getPosition();
        Position target = npc.isHidden() ? npc.spawnPosition : npc.getPosition();
        GameObject stairs = BLACK_DRAGON_STAIRS.object();
        if (stairs == null || !SlayerWorld.reachable(BLACK_DRAGON_STAIRS.exit, target, npc.getSize())) return null;
        if (SlayerWorld.reachable(from, stairs))
            return SlayerWorld.Route.passage(BLACK_DRAGON_STAIRS, SlayerWorld.distance(from, BLACK_DRAGON_STAIRS.entry) + 20);
        SlayerWorld.Portal portal = allowTeleports ? world.portal("Taverley Dungeon") : null;
        return portal != null && !from.isWithinDistance(portal.tile, 2) && SlayerWorld.reachable(portal.tile, stairs)
                ? SlayerWorld.Route.portal(portal, 65) : null;
    }
}
