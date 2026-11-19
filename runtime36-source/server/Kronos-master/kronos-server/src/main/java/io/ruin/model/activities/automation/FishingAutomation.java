package io.ruin.model.activities.automation;

import io.ruin.cache.NPCDef;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.skills.fishing.FishingCatch;
import io.ruin.model.skills.fishing.FishingSpot;
import io.ruin.model.skills.fishing.FishingTool;
import io.ruin.model.stat.StatType;

import java.util.Arrays;

/** Chooses a legal native spot and option from the fishing NPCs the player can see. */
final class FishingAutomation extends LocalGatheringAutomation {
    private static final Method[] METHODS = {
            new Method(FishingSpot.NET_BAIT, "small net", FishingTool.SMALL_FISHING_NET, FishingCatch.SHRIMPS, FishingCatch.ANCHOVIES),
            new Method(FishingSpot.NET_BAIT, "bait", FishingTool.FISHING_ROD, FishingCatch.SARDINE, FishingCatch.HERRING),
            new Method(FishingSpot.LURE_BAIT, "lure", FishingTool.FLY_FISHING_ROD, FishingCatch.TROUT, FishingCatch.SALMON),
            new Method(FishingSpot.LURE_BAIT, "bait", FishingTool.FISHING_ROD, FishingCatch.PIKE),
            new Method(FishingSpot.CAGE_HARPOON, "cage", FishingTool.LOBSTER_POT, FishingCatch.LOBSTER),
            new Method(FishingSpot.CAGE_HARPOON, "harpoon", FishingTool.HARPOON, FishingCatch.TUNA, FishingCatch.SWORDFISH),
            new Method(FishingSpot.BIG_NET_HARPOON, "big net", FishingTool.BIG_FISHING_NET, FishingCatch.MACKEREL, FishingCatch.COD, FishingCatch.BASS),
            new Method(FishingSpot.BIG_NET_HARPOON, "harpoon", FishingTool.HARPOON, FishingCatch.SHARK),
            new Method(FishingSpot.SMALL_NET_HARPOON, "net", FishingTool.SMALL_FISHING_NET, FishingCatch.MONKFISH),
            new Method(FishingSpot.SMALL_NET_HARPOON, "harpoon", FishingTool.HARPOON, FishingCatch.SWORDFISH),
            new Method(FishingSpot.USE_ROD, "use-rod", FishingTool.BARBARIAN_ROD,
                    FishingCatch.LEAPING_TROUT, FishingCatch.LEAPING_SALMON, FishingCatch.LEAPING_STURGEON),
            new Method(FishingSpot.BAIT, "bait", FishingTool.ANGLER_ROD, FishingCatch.ANGLERFISH),
            new Method(FishingSpot.KARAMBWAN_SPOT, "fish", FishingTool.KARAMBWAN_VESSEL, FishingCatch.KARAMBWAN),
            new Method(FishingSpot.INFERNO_EEL, "bait", FishingTool.OILY_FISHING_ROD, FishingCatch.INFERNAL_EEL)
    };

    private boolean sawSpot;

    FishingAutomation(Player player, int goal, long tick) {
        super(player, StatType.Fishing, goal, tick);
    }

    @Override
    String noTargetMessage() {
        return sawSpot ? "Nearby spots need a higher Fishing level, tool, or bait"
                : "Waiting for a supported fishing spot nearby";
    }

    @Override
    GatheringTarget chooseResource(int radius) {
        int level = stat(StatType.Fishing);
        int agility = stat(StatType.Agility), strength = stat(StatType.Strength);
        GatheringTarget best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        sawSpot = false;
        for (NPC npc : player.localNpcs()) {
            if (!live(npc) || npc.getHeight() != plane() || rejected(npc)) continue;
            int distance = distance(npc.getAbsX(), npc.getAbsY());
            if (distance > radius) continue;
            NPCDef def = npc.getDef();
            if (def == null || def.showIds != null) continue;
            for (Method method : METHODS) {
                if (method.id != npc.getId()) continue;
                int option = def.getOption(method.option);
                if (binding(npc, option) == null) continue;
                sawSpot = true;
                if (!method.stocked(player)) continue;
                double xp = method.expectedXp(level, agility, strength);
                if (xp <= 0) continue;
                double score = xp / (3 + distance * .6);
                if (score > bestScore) {
                    best = new SpotTarget(player, npc, method, option);
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private int stat(StatType type) {
        return player.getStats().get(type).currentLevel;
    }

    private static boolean live(NPC npc) {
        return !npc.isRemoved() && !npc.isHidden() && World.getNpc(npc.getIndex()) == npc;
    }

    private static NPCAction binding(NPC npc, int option) {
        if (option < 1 || option > 5) return null;
        int slot = option - 1;
        if (npc.actions != null && npc.actions[slot] != null) return npc.actions[slot];
        NPCAction[] defaults = npc.getDef().defaultActions;
        return defaults == null ? null : defaults[slot];
    }

    private static final class Method {
        final int id;
        final String option;
        final FishingTool tool;
        final FishingCatch[] catches;
        final int[] outputs;

        Method(int id, String option, FishingTool tool, FishingCatch... catches) {
            this.id = id;
            this.option = option;
            this.tool = tool;
            this.catches = catches;
            outputs = Arrays.stream(catches).mapToInt(c -> c.id).distinct().toArray();
        }

        boolean stocked(Player player) {
            if (!player.getInventory().hasId(tool.id)
                    || (tool.secondaryId != -1 && !player.getInventory().hasId(tool.secondaryId)))
                return false;
            if (id == FishingSpot.INFERNO_EEL && !player.getEquipment().hasId(1580)) return false;
            // The native spot promotes a shared FishingSpot.tool when a dragon harpoon is present.
            // Keep automation out of that branch until native tool selection is scoped per action.
            if (tool == FishingTool.HARPOON) {
                int dragon = FishingTool.DRAGON_HARPOON.id;
                return !player.getInventory().hasId(dragon) && !player.getEquipment().hasId(dragon);
            }
            return true;
        }

        double expectedXp(int level, int agility, int strength) {
            double selectedRollBand = 0, xp = 0;
            // Native FishingSpot uses one random roll and prefers the highest catch that accepts it.
            for (int i = catches.length - 1; i >= 0; i--) {
                FishingCatch fish = catches[i];
                if (fish.levelReq > level || fish.agilityReq > agility || fish.strengthReq > strength) continue;
                double chance = fish.baseChance >= 1 ? 1 : Math.min(.9, fish.baseChance + (level - fish.levelReq) * .01);
                double newBand = Math.max(0, chance - selectedRollBand);
                xp += fish.xp * newBand;
                selectedRollBand += newBand;
            }
            return xp;
        }
    }

    private static final class SpotTarget extends GatheringTarget {
        private final Player owner;
        private final NPC spot;
        private final Method method;
        private final int option, x, y, z;

        SpotTarget(Player owner, NPC spot, Method method, int option) {
            super(spot, method.option + " fishing spot", method.outputs);
            this.owner = owner;
            this.spot = spot;
            this.method = method;
            this.option = option;
            x = spot.getAbsX();
            y = spot.getAbsY();
            z = spot.getHeight();
        }

        @Override
        boolean stillUsable() {
            return live(spot) && spot.getId() == method.id && spot.getHeight() == z && spot.isAt(x, y)
                    && method.stocked(owner) && binding(spot, option) != null;
        }

        @Override
        RouteType route(Player player) {
            return player.getRouteFinder().routeEntity(spot);
        }

        @Override
        void interact(Player player) {
            int distance = Math.abs(player.getAbsX() - x) + Math.abs(player.getAbsY() - y);
            NPCAction action = binding(spot, option);
            if (distance != 1 || action == null) return;
            player.face(spot);
            action.handle(player, spot);
        }
    }
}
