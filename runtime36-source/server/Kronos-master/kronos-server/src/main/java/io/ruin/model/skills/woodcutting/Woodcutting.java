package io.ruin.model.skills.woodcutting;

import io.ruin.api.utils.NumberUtils;
import io.ruin.api.utils.Random;
import io.ruin.content.content242.World242ObjectActions;
import io.ruin.model.World;
import io.ruin.model.content.upgrade.ItemEffect;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCounter;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.impl.BirdNest;
import io.ruin.model.item.actions.impl.skillcapes.WoodcuttingSkillCape;
import io.ruin.model.item.attributes.AttributeExtensions;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.route.RouteFinder;
import io.ruin.model.skills.CapePerks;
import io.ruin.model.skills.firemaking.Burning;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.EventConsumer;

import java.util.List;
import java.util.function.Supplier;

public class Woodcutting {

    private static void chop(Tree treeData, Player player, GameObject tree, int treeStump) {
        chop(treeData, player, () -> tree.id == treeStump, worldEvent -> {
            tree.setId(treeStump);
            player.publicSound(2734, 1, 0);
            worldEvent.delay(treeData.respawnTime);
            tree.setId(tree.originalId);
        });
    }

    /**
     * This method was modified so farmed trees could share the same chopping code as normal trees in the world. Only differences should be in the parameters specified below
     *  @param treeDeadCheck Checks if the tree was removed during the time in between the actions of the given player. Only relevant if the tree can lose logs through means other than the given player chopping it
     * @param treeDeadAction What to do when the given player chops the tree's last logs. Should include changing the object into a stump and handle the respawning.
     */
    public static void chop(Tree treeData, Player player, Supplier<Boolean> treeDeadCheck, EventConsumer treeDeadAction) {
        Hatchet hatchet = Hatchet.find(player);

        if (hatchet == null) {
            player.sendMessage("You need an axe to chop down this tree.");
            player.sendMessage("You do not have an axe which you have the woodcutting level to use.");
            player.privateSound(2277);
            return;
        }

        Stat stat = player.getStats().get(StatType.Woodcutting);
        if (stat.currentLevel < treeData.levelReq) {
            player.sendMessage("You need a Woodcutting level of " + treeData.levelReq + " to chop down this tree.");
            player.privateSound(2277);
            return;
        }

        if (player.getInventory().isFull()) {
            player.sendMessage("Your inventory is too full to hold any logs.");
            player.privateSound(2277);
            return;
        }

        player.startEvent(event -> {
            int attempts = 0;
            while (true) {
                int effectiveLevel = getEffectiveLevel(player, treeData, hatchet);
                if (player.debug) {
                    double chance = chance(effectiveLevel, treeData, hatchet) / 100;
                    double logsPerTick = chance / 2;
                    double logsPerHour = 100 * 60 * logsPerTick;
                    double xpPerTick = logsPerTick * treeData.experience * StatType.Woodcutting.defaultXpMultiplier;
                    double xpPerHour = 100 * 60 * xpPerTick;
                    player.sendMessage("difficulty=" + treeData.difficulty + ", chance=" + NumberUtils.formatTwoPlaces(chance) + ", xp/tick=" + NumberUtils.formatNumber((long) xpPerTick) + "");
                    player.sendMessage("logsPerHour=" + NumberUtils.formatTwoPlaces(logsPerHour) + ", xpPerHour=" + NumberUtils.formatNumber((long) xpPerHour));
                }
                if (player.getInventory().isFull()) {
                    player.sendMessage("Your inventory is too full to hold any more logs.");
                    player.resetAnimation();
                    return;
                }
                if (treeDeadCheck.get()) {
                    player.resetAnimation();
                    return;
                }

                if (attempts == 0) {
                    player.animate(hatchet.animationId);
                    player.sendFilteredMessage("You swing your axe at the tree.");
                    event.delay(1);
                }
                if (attempts % 2 == 0 && successfullyCutTree(effectiveLevel, treeData, hatchet)) {
                    if (hatchet == Hatchet.INFERNAL && (Random.rollDie(3, 1) || player.infernalAxeSpecial > 0)) {
                        Burning burning = Burning.get(treeData.log);
                        if (burning != null) {
                            player.sendFilteredMessage("The infernal axe incinerates some logs.");
                            player.graphics(580, 50, 0);
                            player.getStats().addXp(StatType.Firemaking, burning.exp, true);
                            //TODO: take away an item charge?
                        }
                    } else {
                        player.sendFilteredMessage("You get some " + treeData.treeName + ".");
                        if(player.getInventory().add(treeData.log, 1)>0) {
                            io.ruin.content.sixsystems.Athletics.gathered(player,treeData.log,1);
                            io.ruin.content.sixsystems.MasteryService.gathered(player,StatType.Woodcutting,treeData.log);
                        }
                        player.collectResource(new Item(treeData.log, 1));
                        if (Random.rollDie(nestChance(player), 1)) {
                            new GroundItem(BirdNest.getRandomNest(treeData), 1)
                                    .owner(player).position(RouteFinder.findWalkable(player.getPosition()))
                                    .spawn();
                            player.sendFilteredMessage("A bird's nest falls out of the tree.");
                            PlayerCounter.ACQUIRED_BIRDS_NESTS.increment(player, 1);
                        }
                        if (player.dragonAxeSpecial > 0 && Random.rollPercent(50)) {
                            player.sendFilteredMessage("Your axe's buff allows you to chop some additional logs!");
                            player.getInventory().add(treeData.log, 1);
                            player.getStats().addXp(StatType.Woodcutting, treeData.experience / 2.0, true);
                        }
                    }
                    treeData.counter.increment(player, 1);
                    double xp = treeData.experience;
                    if (player.infernalAxeSpecial > 0)
                        xp *= 1.1;
                    player.getStats().addXp(StatType.Woodcutting, xp, true);
                    if (treeData.single || Random.get(10) == 3) {
                        player.resetAnimation();
                        World.startEvent(treeDeadAction);
                        return;
                    }
                }
                if (attempts++ % 4 == 0)
                    player.animate(hatchet.animationId);

                event.delay(1);
            }
        });
    }

    private static int nestChance(Player player) {
        int chance = 200;
        if (player.isGroup(PlayerGroup.ZENYTE)) {
            chance = 170;
        } else if (player.isGroup(PlayerGroup.ONYX)) {
            chance = 175;
        } else if (player.isGroup(PlayerGroup.DRAGONSTONE)) {
            chance = 180;
        } else if (player.isGroup(PlayerGroup.DIAMOND)) {
            chance = 185;
        } else if (player.isGroup(PlayerGroup.RUBY)) {
            chance = 190;
        } else if (player.isGroup(PlayerGroup.EMERALD)) {
            chance = 195;
        } else if (player.isGroup(PlayerGroup.SAPPHIRE)) {
            chance = 197;
        }

        if (WoodcuttingSkillCape.wearsWoodcuttingCape(player)) {
            chance += chance / 10;
        }

        return chance;
    }

    private static int getEffectiveLevel(Player player, Tree treeData, Hatchet hatchet) {
        int base = player.getStats().get(StatType.Woodcutting).currentLevel;
        if (WoodcuttingGuild.hasBoost(player))
            base += 7;
        return base;
    }

    private static boolean successfullyCutTree(int level, Tree type, Hatchet hatchet) {
        return Random.get(100) <= chance(level, type, hatchet);
    }

    private static double chance(int level, Tree type, Hatchet hatchet) {
        double points = ((level - type.levelReq) + 1 + (double) hatchet.points);
        double denominator = type.difficulty;
        return (Math.min(0.95, points / denominator) * 100);
    }

    private static void register(int id, String option, Tree tree, int stump) {
        ObjectAction.register(id, option, (player, obj) -> chop(tree, player, obj, stump));
        switch (tree) {
            case REGULAR:
                World242ObjectActions.bind(id, stump, option, def -> def.name.equalsIgnoreCase("tree")
                                || def.name.equalsIgnoreCase("dead tree") || def.name.equalsIgnoreCase("evergreen tree"),
                        def -> def.name.equalsIgnoreCase("tree stump"), mapped -> (player, obj) -> chop(tree, player, obj, mapped));
                break;
            case OAK:
            case WILLOW:
            case MAPLE:
            case TEAK:
            case MAHOGANY:
            case YEW:
            case MAGIC:
                String name = tree.name() + " tree";
                World242ObjectActions.bind(id, stump, option, def -> def.name.equalsIgnoreCase(name),
                        def -> def.name.equalsIgnoreCase("tree stump"), mapped -> (player, obj) -> chop(tree, player, obj, mapped));
                break;
            default:
                break;
        }
    }

    static {
        register(1278, "chop down", Tree.REGULAR, 1342);
        register(1276, "chop down", Tree.REGULAR, 1342);
        register(2091, "chop down", Tree.REGULAR, 1342);
        register(1286, "chop down", Tree.REGULAR, 1351);
        register(1282, "chop down", Tree.REGULAR, 1347);
        register(1383, "chop down", Tree.REGULAR, 1358);
        register(1289, "chop down", Tree.REGULAR, 1353);
        register(2023, "chop", Tree.ACHEY, 1355);
        register(10820, "chop down", Tree.OAK, 1356);
        register(10819, "chop down", Tree.WILLOW, 9711);
        register(10833, "chop down", Tree.WILLOW, 9711);
        register(10831, "chop down", Tree.WILLOW, 9711);
        register(10829, "chop down", Tree.WILLOW, 9711);
        register(15062, "chop down", Tree.TEAK, 9037);
        register(9036, "chop down", Tree.TEAK, 9037);
        register(27499, "chop down", Tree.JUNIPER, 27500);
        register(10832, "chop down", Tree.MAPLE, 9712);
        register(10822, "chop down", Tree.YEW, 9714);
        register(1754, "chop down", Tree.YEW, 9714);
        register(10834, "chop down", Tree.MAGIC, 9713);
        register(1761, "chop down", Tree.MAGIC, 9713);
        register(1762, "chop down", Tree.MAGIC, 9713);
        register(29668, "cut", Tree.REDWOOD, 29669);
        register(29670, "cut", Tree.REDWOOD, 29671);
        register(29763, "chop", Tree.SAPLING, 29764);
        register(9034, "chop down", Tree.MAHOGANY, 9035);
        register(50008, "chop down", Tree.CORRUPT_TREE, 50009);
    }

}

