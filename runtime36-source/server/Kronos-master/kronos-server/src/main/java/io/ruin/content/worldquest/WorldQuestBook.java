package io.ruin.content.worldquest;

import io.ruin.Server;
import io.ruin.cache.AnimDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.ItemID;
import io.ruin.model.World;
import io.ruin.model.activities.cluescrolls.ClueObjects;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.autonomous.Adventurer;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.inter.dialogue.MessageDialogue;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Inventory;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.utility.OfflineMode;

import java.util.function.Consumer;

import static io.ruin.content.worldquest.RiverWatch.Action;
import static io.ruin.content.worldquest.RiverWatch.Stage;

/** Native object bindings and player journal for Newscape's authored world quests. */
public final class WorldQuestBook {
    private static final Site NOTICE = new Site(18493, 3235, 3228, 0, "Read");
    private static final Site GAUGE = new Site(18493, 3261, 3230, 2, "Read");
    private static final Site CRATE = new Site(366, 3228, 3205, 1, "Search");
    private static final int REPAIR_ANIMATION = 898;
    private static final int SAVE_RESOLUTION_TICKS = 50;

    private static boolean loaded;

    private WorldQuestBook() {}

    public static void load() {
        if (loaded || !OfflineMode.hasStableAccountRegistry()) return;

        GameObject notice = NOTICE.resolve(false);
        GameObject gauge = GAUGE.resolve(false);
        GameObject crate = CRATE.resolve(true);
        ItemDef coins = ItemDef.get(ItemID.COINS_995);
        if (notice == null || gauge == null || crate == null || coins == null || !coins.stackable
                || ItemDef.get(ItemID.HAMMER) == null || AnimDef.get(REPAIR_ANIMATION) == null) {
            Server.println("River Watch disabled: missing landmark, occupied action, or missing equipment definition.");
            return;
        }

        NOTICE.bind(notice, player -> openNotice(player, notice));
        GAUGE.bind(gauge, player -> inspectGauge(player, gauge));
        CRATE.bind(crate, player -> {
            if (!ClueObjects.advanceSearchClue(player, crate)) inspectCrate(player);
        });
        loaded = true;
        Server.println("River Watch: signposts and ledger search enabled.");
    }

    public static boolean available() {
        return loaded;
    }

    public static void onWorldArrival(Player player) {
        if (available() && stage(player) == Stage.NOT_STARTED)
            player.sendMessage("A River Watch commission is posted at the signpost by Lumbridge's west bridge approach.");
    }

    public static void openJournal(Player player) {
        if (!available()) {
            player.sendMessage("World quests are currently unavailable.");
            return;
        }
        player.dialogue(new MessageDialogue("River Watch<br>" + stage(player).journal));
    }

    private static Stage stage(Player player) {
        return player.worldQuests == null ? Stage.NOT_STARTED : player.worldQuests.riverWatch();
    }

    private static void openNotice(Player player, GameObject notice) {
        switch (stage(player)) {
            case NOT_STARTED:
                player.dialogue(new MessageDialogue("The River Watch needs a surveyor. A flood alarm keeps ringing under clear skies. Inspect both gauges and report what you find."),
                        new OptionsDialogue(RiverWatch.TITLE,
                                new Option("Accept the commission", p -> atSite(p, notice, () -> advance(p, Action.ACCEPT))),
                                new Option("Read the journal", WorldQuestBook::openJournal),
                                new Option("Leave")));
                break;
            case SURVEYED:
                diagnose(player, notice);
                break;
            case REPAIRED:
                player.dialogue(new OptionsDialogue(RiverWatch.TITLE,
                        new Option("Collect 1,250 coins", p -> atSite(p, notice, () -> claim(p))),
                        new Option("Read the journal", WorldQuestBook::openJournal),
                        new Option("Leave")));
                break;
            default:
                openJournal(player);
        }
    }

    private static void diagnose(Player player, GameObject notice) {
        player.dialogue(new MessageDialogue("The upstream gauge moves freely. At the landing, the flood marks are dry and the alarm stays red even with the float lowered."),
                new OptionsDialogue("What should the Watch repair?",
                        new Option("The alarm latch", p -> atSite(p, notice, () -> advance(p, Action.DIAGNOSE_LATCH))),
                        new Option("The upstream gauge", p -> atSite(p, notice, () -> explainDiagnosis(p))),
                        new Option("Both floats", p -> atSite(p, notice, () -> explainDiagnosis(p))),
                        new Option("Consult the journal", WorldQuestBook::openJournal)));
    }

    private static void explainDiagnosis(Player player) {
        if (stage(player) == Stage.SURVEYED)
            player.dialogue(new MessageDialogue("The readings change when the floats move. The alarm is the only part that stays in the same position."));
    }

    private static void inspectGauge(Player player, GameObject gauge) {
        Stage stage = stage(player);
        if (stage == Stage.DIAGNOSED) {
            player.dialogue(new OptionsDialogue(RiverWatch.TITLE,
                    new Option("Free the alarm latch", p -> atSite(p, gauge, () -> repair(p))),
                    new Option("Read the journal", WorldQuestBook::openJournal),
                    new Option("Leave")));
            return;
        }
        if (stage == Stage.ACCEPTED || stage == Stage.DOWNSTREAM_READ) {
            advance(player, Action.READ_UPSTREAM);
            return;
        }
        if (stage == Stage.REPAIRED || stage == Stage.COMPLETE) {
            player.sendMessage("The repaired latch resets as the gauge falls.");
            return;
        }
        player.sendMessage("A survey gauge is strapped to the signpost on the east road.");
    }

    private static void inspectCrate(Player player) {
        Stage stage = stage(player);
        if (stage == Stage.ACCEPTED || stage == Stage.UPSTREAM_READ) {
            advance(player, Action.READ_DOWNSTREAM);
            return;
        }
        player.sendMessage("The crate contains the River Watch's flood mark ledger.");
    }

    private static void repair(Player player) {
        if (stage(player) != Stage.DIAGNOSED) return;
        if (!player.getInventory().hasId(ItemID.HAMMER)) {
            player.sendMessage("You need a hammer in your inventory to free the latch.");
            return;
        }
        player.animate(REPAIR_ANIMATION);
        advance(player, Action.REPAIR);
    }

    private static void advance(Player player, Action action) {
        if (!canUseQuest(player)) return;
        if (player.worldQuests == null) {
            if (action != Action.ACCEPT) return;
            player.worldQuests = new WorldQuestState();
        }
        if (!player.worldQuests.applyRiverWatch(action)) return;
        PlayerFile.save(player, -1);
        switch (action) {
            case READ_UPSTREAM:
                player.sendMessage("You lift the upstream float. The gauge moves freely and drops back to its resting mark.");
                break;
            case READ_DOWNSTREAM:
                player.sendMessage("The ledger records dry flood marks. The downstream alarm stayed red with its float lowered.");
                break;
            case REPAIR:
                player.sendMessage("You tap the latch pin straight. The alarm drops out of the red.");
                break;
            default:
                break;
        }
        openJournal(player);
    }

    private static void claim(Player player) {
        if (!canUseQuest(player) || stage(player) != Stage.REPAIRED) return;
        Inventory inventory = player.getInventory();
        Item coins = inventory.findItem(ItemID.COINS_995);
        int slot = coins == null ? inventory.freeSlot() : coins.getSlot();
        if (slot == -1 || (coins != null && coins.getAmount() > Integer.MAX_VALUE - RiverWatch.COIN_REWARD)) {
            player.sendMessage("Make room for 1,250 coins in your inventory. Your reward will stay on the notice.");
            return;
        }

        Item before = coins == null ? null : coins.copy();
        WorldQuestState progress = player.worldQuests;
        if (!progress.applyRiverWatch(Action.CLAIM)) return;
        if (inventory.add(ItemID.COINS_995, RiverWatch.COIN_REWARD) != RiverWatch.COIN_REWARD) {
            restoreClaim(inventory, slot, before, progress);
            player.sendMessage("Your coin pouch could not receive the payment. Please try again.");
            return;
        }

        try {
            PlayerFile.saveDurably(player);
        } catch (PlayerFile.DurableSaveException failure) {
            if (failure.uncertain) {
                resolveClaim(player, inventory, slot, before, progress, failure);
            } else {
                restoreClaim(inventory, slot, before, progress);
                player.sendMessage("Your reward could not be saved. Please try again.");
                Server.logError("River Watch reward save failed before committing", failure);
            }
            return;
        }
        showCompletion(player);
    }

    private static void resolveClaim(Player player, Inventory inventory, int slot, Item before,
                                     WorldQuestState progress, PlayerFile.DurableSaveException failure) {
        World.startEvent(event -> {
            for (int tick = 0; tick < SAVE_RESOLUTION_TICKS; tick++) {
                event.delay(1);
                PlayerFile.SaveResolution result = PlayerFile.resolveDurableSave(player);
                if (result == PlayerFile.SaveResolution.PENDING) continue;
                if (result == PlayerFile.SaveResolution.UNKNOWN) break;
                if (result == PlayerFile.SaveResolution.NOT_COMMITTED) restoreClaim(inventory, slot, before, progress);
                if (player.isLocked(LockType.FULL_NULLIFY_DAMAGE)) player.unlock();
                if (result == PlayerFile.SaveResolution.COMMITTED) {
                    player.sendMessage("River Watch complete. Your payment of 1,250 coins was saved.");
                } else {
                    player.sendMessage("Your reward could not be saved. Please try again.");
                }
                return;
            }
            Server.logError("River Watch reward save needs review for account " + player.getUserId(), failure);
        });
    }

    private static void restoreClaim(Inventory inventory, int slot, Item before, WorldQuestState progress) {
        inventory.set(slot, null);
        if (before != null) inventory.set(slot, before);
        progress.undoRiverWatchClaim();
    }

    private static void showCompletion(Player player) {
        player.dialogue(new MessageDialogue("River Watch complete!<br>The Watch paid you 1,250 coins. The bridge alarm is back in service."));
    }

    private static boolean canUseQuest(Player player) {
        return available() && player.isOnline() && player.getUserId() >= 1000 && !(player instanceof Adventurer)
                && !player.isLocked() && !PlayerFile.isSaveQuarantined(player) && !player.getCombat().isDead()
                && !player.getMovement().isTeleportQueued() && !player.pvpAttackZone && player.wildernessLevel == 0;
    }

    private static void atSite(Player player, GameObject object, Runnable action) {
        if (!canUseQuest(player) || !player.getPosition().isWithinDistance(object.getPosition(), 2)
                || Tile.getObject(object.id, object.x, object.y, object.z, object.type, object.direction) != object)
            return;
        action.run();
    }

    private static final class Site {
        private final int id;
        private final int x;
        private final int y;
        private final int direction;
        private final String option;

        private Site(int id, int x, int y, int direction, String option) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.option = option;
        }

        private GameObject resolve(boolean allowGenericSearch) {
            GameObject object = Tile.getObject(id, x, y, 0, 10, direction);
            if (object == null) return null;
            int index = object.getDef().getOption(option) - 1;
            if (index < 0 || actionAt(object.actions, index) != null) return null;
            ObjectAction fallback = actionAt(object.getDef().defaultActions, index);
            if (fallback != null && (!allowGenericSearch || !ClueObjects.isGenericSearch(fallback))) return null;
            return object;
        }

        private void bind(GameObject object, Consumer<Player> action) {
            ObjectAction.register(object, option, (player, clicked) -> {
                if (clicked == object) atSite(player, object, () -> action.accept(player));
            });
        }

        private static ObjectAction actionAt(ObjectAction[] actions, int index) {
            return actions == null ? null : actions[index];
        }
    }
}
