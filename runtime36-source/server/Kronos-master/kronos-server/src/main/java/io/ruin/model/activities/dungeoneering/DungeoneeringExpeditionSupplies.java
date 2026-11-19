package io.ruin.model.activities.dungeoneering;

import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.stat.StatType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Personal dungeon storage and the two native harvesting tool families. */
final class DungeoneeringExpeditionSupplies {

    private DungeoneeringExpeditionSupplies() {}

    static int toolTier(int source, boolean mining) {
        int delta = source - (mining ? 16295 : 16361);
        return delta < 0 || delta > 20 || (delta & 1) != 0 ? -1 : delta / 2;
    }

    static int toolLevel(int tier) {
        return tier == 10 ? 99 : Math.max(1, tier * 10);
    }

    private static int savedTier(DungeoneeringProgress state, boolean mining) {
        return mining ? state.miningBeltTier : state.woodcuttingBeltTier;
    }

    static boolean beltTool(Player p, boolean mining) {
        if (!Dungeoneering.inRun(p)) return false;
        int tier = savedTier(Dungeoneering.progress(p), mining);
        return tier >= 0 && p.getStats().get(mining ? StatType.Mining : StatType.Woodcutting).currentLevel >= toolLevel(tier);
    }

    static void tools(Player p) {
        DungeoneeringFloor run = Dungeoneering.floor(p);
        if (run == null || !Dungeoneering.inRun(p)) return;
        DungeoneeringProgress state = Dungeoneering.progress(p);
        List<Option> options = new ArrayList<>();
        options.add(new Option("Stored pickaxe / " + label(state.miningBeltTier), () -> tools(p)));
        options.add(new Option("Stored hatchet / " + label(state.woodcuttingBeltTier), () -> tools(p)));
        for (Item item : p.getInventory().getItems()) if (item != null) {
            int source = run.assets.sourceItem(item.getId());
            for (boolean mining : new boolean[]{true, false}) {
                int tier = toolTier(source, mining);
                if (tier < 0 || tier <= savedTier(state, mining)) continue;
                int slot = item.getSlot(), id = item.getId();
                options.add(new Option("Store " + item.getDef().name + " in your tool belt",
                    () -> storeTool(p, run, item, slot, id, mining, tier)));
            }
        }
        options.add(new Option("Back to expedition", () -> Dungeoneering.open(p)));
        OptionScroll.open(p, "Dungeon tools", false, options);
    }

    private static String label(int tier) { return tier < 0 ? "Empty" : "Requires level " + toolLevel(tier); }

    private static void storeTool(Player p, DungeoneeringFloor run, Item expected, int slot, int id, boolean mining, int tier) {
        if (Dungeoneering.floor(p) != run || !Dungeoneering.inRun(p) || p.isLocked() ||
            PlayerFile.isSaveQuarantined(p) || p.getInventory().get(slot, id) != expected) return;
        DungeoneeringProgress state = Dungeoneering.progress(p);
        if (tier <= savedTier(state, mining)) return;
        if (p.getStats().get(mining ? StatType.Mining : StatType.Woodcutting).fixedLevel < toolLevel(tier)) {
            p.sendMessage("Your skill level is too low for this tool.");
            return;
        }
        if (expected.remove(1) != 1) return;
        if (mining) state.miningBeltTier = tier;
        else state.woodcuttingBeltTier = tier;
        Dungeoneering.save(p, () -> { p.sendMessage("The tool is saved for future floors."); tools(p); });
    }

    static boolean atStart(Player p, DungeoneeringFloor run) {
        return run != null && Dungeoneering.floor(p) == run && Dungeoneering.inRun(p) &&
            run.entered.contains(p) && run.room(p.getPosition()) == run.rooms[run.graph.startId] &&
            !p.isLocked() && !PlayerFile.isSaveQuarantined(p) && !p.getCombat().isDefending(10);
    }

    static int capacity(int level) {
        return level >= 110 ? 15 : level >= 80 ? 12 : level >= 40 ? 9 : level >= 10 ? 6 : 3;
    }

    static int amountToStore(boolean stackable, int carried, int stored) {
        return Math.max(0, Math.min(carried, stackable ? 100 - stored : 1));
    }

    private static int storedAmount(Item[] items, int id) {
        int amount = 0;
        for (Item item : items) if (item != null && item.getId() == id) amount += item.getAmount();
        return amount;
    }

    private static boolean storable(Player p, DungeoneeringFloor run, Item item) {
        if (run.assets.sourceItem(item.getId()) < 0) return false;
        for (Item bound : Dungeoneering.progress(p).boundItems)
            if (bound != null && bound.getId() == item.getId()) return false;
        String name = item.getDef().name.toLowerCase(Locale.ROOT);
        return !name.contains("key") && !name.contains("kinship") && !name.contains("gatestone") && !name.contains("(b)");
    }

    static void storage(Player p, DungeoneeringFloor run) {
        if (!atStart(p, run)) return;
        DungeoneeringProgress state = Dungeoneering.progress(p);
        List<Option> options = new ArrayList<>();
        int used = 0;
        for (int idx = 0; idx < state.smugglerStorage.length; idx++) {
            int slot = idx;
            Item item = state.smugglerStorage[slot];
            if (item == null) continue;
            used++;
            options.add(new Option("Retrieve " + item.getDef().name + " x" + item.getAmount(),
                () -> retrieve(p, run, state, slot, item)));
        }
        for (Item item : p.getInventory().getItems()) {
            if (item == null || !storable(p, run, item)) continue;
            int slot = item.getSlot(), id = item.getId();
            options.add(new Option("Store " + item.getDef().name + " x" + item.getAmount(),
                () -> store(p, run, state, slot, id, item)));
        }
        options.add(new Option("Back to expedition", () -> Dungeoneering.open(p)));
        OptionScroll.open(p, "Smuggler storage / " + used + " of " + capacity(state.level()), false, options);
    }

    private static void retrieve(Player p, DungeoneeringFloor run, DungeoneeringProgress state, int slot, Item item) {
        if (!atStart(p, run) || state.smugglerStorage[slot] != item || run.assets.sourceItem(item.getId()) < 0) return;
        int moved = p.getInventory().add(item.copy());
        if (moved < 1) {
            p.sendMessage("Make room in your inventory first.");
            storage(p, run);
            return;
        }
        if (moved == item.getAmount()) state.smugglerStorage[slot] = null;
        else item.setAmount(item.getAmount() - moved);
        Dungeoneering.save(p, () -> storage(p, run));
    }

    private static void store(Player p, DungeoneeringFloor run, DungeoneeringProgress state, int slot, int id, Item item) {
        if (!atStart(p, run) || p.getInventory().get(slot, id) != item || !storable(p, run, item)) return;
        boolean stackable = item.getDef().stackable;
        int target = -1;
        for (int i = 0; i < capacity(state.level()); i++) {
            Item saved = state.smugglerStorage[i];
            if (saved == null) {
                if (target < 0) target = i;
            } else if (stackable && saved.getId() == id && java.util.Objects.equals(saved.copyOfAttributes(), item.copyOfAttributes())) {
                target = i;
                break;
            }
        }
        int amount = amountToStore(stackable, item.getAmount(), storedAmount(state.smugglerStorage, id));
        if (target < 0 || amount == 0) {
            p.sendMessage("There is no space for this item in storage.");
            storage(p, run);
            return;
        }
        Item snapshot = item.copy();
        int moved = item.remove(amount);
        if (moved < 1) return;
        Item saved = state.smugglerStorage[target];
        if (saved == null) {
            snapshot.setAmount(moved);
            state.smugglerStorage[target] = snapshot;
        } else saved.setAmount(saved.getAmount() + moved);
        Dungeoneering.save(p, () -> storage(p, run));
    }
}