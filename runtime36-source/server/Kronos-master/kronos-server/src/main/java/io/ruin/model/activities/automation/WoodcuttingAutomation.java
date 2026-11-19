package io.ruin.model.activities.automation;

import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.woodcutting.Hatchet;
import io.ruin.model.skills.woodcutting.Tree;
import io.ruin.model.stat.StatType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Uses fully registered tree actions rather than awarding logs or XP directly. */
final class WoodcuttingAutomation extends LocalGatheringAutomation {
    private static final Map<Integer, Tree> LEGACY_TREES = new HashMap<>();
    private static final Map<String, Tree> NAMED_TREES = new HashMap<>();

    static {
        legacy(Tree.REGULAR, 1278, 1276, 2091, 1286, 1282, 1383, 1289);
        legacy(Tree.ACHEY, 2023);
        legacy(Tree.OAK, 10820);
        legacy(Tree.WILLOW, 10819, 10833, 10831, 10829);
        legacy(Tree.TEAK, 15062, 9036);
        legacy(Tree.JUNIPER, 27499);
        legacy(Tree.MAPLE, 10832);
        legacy(Tree.YEW, 10822, 1754);
        legacy(Tree.MAGIC, 10834, 1761, 1762);
        legacy(Tree.REDWOOD, 29668, 29670);
        legacy(Tree.MAHOGANY, 9034);
        NAMED_TREES.put("tree", Tree.REGULAR);
        NAMED_TREES.put("dead tree", Tree.REGULAR);
        NAMED_TREES.put("evergreen tree", Tree.REGULAR);
        for (Tree tree : new Tree[]{Tree.OAK, Tree.WILLOW, Tree.TEAK, Tree.MAPLE, Tree.MAHOGANY, Tree.YEW, Tree.MAGIC})
            NAMED_TREES.put(tree.name().toLowerCase(Locale.ROOT) + " tree", tree);
    }

    WoodcuttingAutomation(Player player, int goal, long tick) {
        super(player, StatType.Woodcutting, goal, tick);
    }

    @Override
    String precondition() {
        return Hatchet.find(player) == null ? "Bring an axe you can use" : null;
    }

    private static void legacy(Tree tree, int... ids) {
        for (int id : ids) LEGACY_TREES.put(id, tree);
    }

    static Tree treeFor(int id, String name) {
        if (name == null || name.toLowerCase(Locale.ROOT).contains("stump")) return null;
        Tree tree = NAMED_TREES.get(name.toLowerCase(Locale.ROOT));
        return tree != null ? tree : LEGACY_TREES.get(id);
    }

    @Override
    String noTargetMessage() {
        return "Waiting for a suitable, live tree";
    }

    @Override
    GatheringTarget chooseResource(int radius) {
        Hatchet axe = Hatchet.find(player);
        if (axe == null) return null;
        int level = player.getStats().get(StatType.Woodcutting).currentLevel;
        int x = player.getAbsX(), y = player.getAbsY();
        GatheringTarget best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (!validTile(x + dx, y + dy)) continue;
                Tile tile = Tile.get(x + dx, y + dy, plane());
                if (tile == null || tile.gameObjects == null) continue;
                for (GameObject treeObject : tile.gameObjects) {
                    if (treeObject.isRemoved() || rejected(treeObject)) continue;
                    ObjectDef def = treeObject.getDef();
                    if (def == null || def.varpBitId != -1 || def.varpId != -1) continue;
                    Tree tree = treeFor(treeObject.id, def.name);
                    if (tree == null || tree.levelReq > level) continue;
                    int option = def.getOption("chop down", "chop", "cut");
                    if (binding(treeObject, option) == null) continue;
                    double chance = Math.min(.95, (level - tree.levelReq + 1.0 + axe.points) / tree.difficulty) * 100;
                    int distance = Math.max(Math.abs(dx), Math.abs(dy));
                    double score = AutomationPolicy.rate(tree.experience, chance, distance,
                            tree.single ? 1.0 : .1, tree.respawnTime);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new ObjectTarget(treeObject, option, def.name, new int[]{tree.log});
                    }
                }
            }
        }
        return best;
    }
}
