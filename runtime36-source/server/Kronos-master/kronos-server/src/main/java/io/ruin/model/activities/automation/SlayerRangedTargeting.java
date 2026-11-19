package io.ruin.model.activities.automation;

import io.ruin.model.entity.npc.NPC;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.route.routes.ProjectileRoute;
import io.ruin.model.map.route.routes.TargetRoute;

/** Firing positions for task profiles whose targets cannot be fought in melee. */
final class SlayerRangedTargeting {
    static final int SHOOT_RANGE = 6;

    private SlayerRangedTargeting() {}

    static boolean canFireFrom(Position from, NPC npc) {
        Position target = npc.isHidden() ? npc.spawnPosition : npc.getPosition();
        if (from.getZ() != target.getZ() || !SlayerWorld.safeTile(from)
                || !from.isWithinDistance(target, SHOOT_RANGE)) return false;
        int dx = from.getX() - target.getX(), dy = from.getY() - target.getY();
        if (dx >= 0 && dx < npc.getSize() && dy >= 0 && dy < npc.getSize()) return false;
        if (SlayerLoadout.forNpc(npc) == SlayerLoadout.MITHRIL_DRAGON && TargetRoute.inRange(
                from.getX(), from.getY(), 1, target.getX(), target.getY(), npc.getSize(), 1)) return false;
        Tile tile = Tile.get(from.getX(), from.getY(), from.getZ(), false);
        return (tile == null || tile.isFloorFree()) && ProjectileRoute.allow(from.getX(), from.getY(), from.getZ(),
                1, target.getX(), target.getY(), npc.getSize());
    }

    static Position firingPosition(Position from, NPC npc) {
        if (canFireFrom(from, npc)) return from;
        Position target = npc.isHidden() ? npc.spawnPosition : npc.getPosition();
        if (from.getZ() != target.getZ() || SlayerWorld.distance(from, target) > 58 + SHOOT_RANGE) return null;
        Position best = null;
        int distance = Integer.MAX_VALUE;
        for (int x = target.getX() - SHOOT_RANGE; x <= target.getX() + SHOOT_RANGE; x++) {
            for (int y = target.getY() - SHOOT_RANGE; y <= target.getY() + SHOOT_RANGE; y++) {
                Position candidate = new Position(x, y, from.getZ());
                int steps = SlayerWorld.distance(from, candidate);
                if (steps >= distance || !canFireFrom(candidate, npc) || !SlayerWorld.walkable(from, candidate)) continue;
                best = candidate;
                distance = steps;
            }
        }
        return best;
    }
}
