package io.ruin.model.activities.raids.auto;

import io.ruin.api.buffer.InBuffer;
import io.ruin.api.buffer.OutBuffer;
import io.ruin.model.World;
import io.ruin.model.activities.dungeoneering.Dungeoneering;
import io.ruin.model.activities.raids.toa.port.TombsOfAmascutRaid;
import io.ruin.model.activities.raids.tob.port.TheatreOfBloodRaid;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.network.incoming.handlers.ActionButtonHandler;
import io.ruin.network.incoming.handlers.NPCActionHandler;
import io.ruin.network.incoming.handlers.ObjectActionHandler;
import io.ruin.network.incoming.handlers.WalkHandler;

import java.lang.reflect.Field;

/** Ordinary owner actions. A true result means submitted, never that an effect succeeded. */
public final class RaidAutoActions {
    private static final Field DIALOGUE_OPTIONS = dialogueOptions();
    private static final WalkHandler WALK = new WalkHandler();
    private static final NPCActionHandler NPC_ACTION = new NPCActionHandler();
    private static final ObjectActionHandler OBJECT_ACTION = new ObjectActionHandler();
    private static final ActionButtonHandler.InventoryHandler INVENTORY = new ActionButtonHandler.InventoryHandler();

    private RaidAutoActions() {}

    private static Field dialogueOptions() {
        try {
            Field field = OptionsDialogue.class.getDeclaredField("options");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static boolean ready(Player player) {
        return RaidAuto.isOwner(player) && player.isOnline() && player.getHp() > 0
                && !player.getCombat().isDead() && !player.isLocked()
                && (TombsOfAmascutRaid.isInRaid(player) || TheatreOfBloodRaid.isInRaid(player)
                || Dungeoneering.inRun(player) && Dungeoneering.floor(player) != null
                && Dungeoneering.floor(player).contains(player));
    }

    private static InBuffer input(OutBuffer buffer) {
        return new InBuffer(buffer.toByteArray());
    }

    public static boolean walk(Player player, int x, int y) {
        if (!ready(player) || player.isStunned()) return false;
        if (x < 0 || x > 65535 || y < 0 || y > 65535)
            throw new IllegalArgumentException("Invalid native walk coordinate");
        OutBuffer buffer = new OutBuffer(5);
        buffer.addLEShortA(x);
        buffer.addLEShort(y);
        buffer.addByteC(1); // ordinary Ctrl-run; never privileged type2 teleport
        WALK.handle(player, input(buffer), 98);
        return true;
    }

    public static boolean stopAttack(Player player) {
        return player != null && walk(player, player.getAbsX(), player.getAbsY());
    }

    public static boolean npc(Player player, NPC npc, int option) {
        return npc(player, npc, option, false);
    }

    public static boolean npc(Player player, NPC npc, int option, boolean ctrlRun) {
        if (!ready(player)) return false;
        if (npc == null || npc.isRemoved() || World.getNpc(npc.getIndex()) != npc
                || npc.getHeight() != player.getHeight()
                || npc.getPosition().getRegion().dynamicIndex != player.getPosition().getRegion().dynamicIndex)
            throw new IllegalArgumentException("Native NPC is not in the owner's current raid lease");
        if (option < 1 || option > 5) throw new IllegalArgumentException("Invalid native NPC option");
        OutBuffer buffer = new OutBuffer(3);
        if (option == 1 || option == 2 || option == 5) buffer.addByteS(ctrlRun ? 1 : 0);
        else buffer.addByte(ctrlRun ? 1 : 0);
        if (option == 2) buffer.addShortA(npc.getIndex());
        else buffer.addLEShort(npc.getIndex());
        int[] opcodes = {89, 96, 35, 79, 37};
        NPC_ACTION.handle(player, input(buffer), opcodes[option - 1]);
        return true;
    }

    public static boolean object(Player player, GameObject object, int option) {
        if (!ready(player)) return false;
        if (object == null || object.id < 0 || object.id >= 65535 || object.z != player.getHeight()
                || Tile.getObject(object.id, object.x, object.y, object.z) != object
                || object.getPosition().getRegion().dynamicIndex != player.getPosition().getRegion().dynamicIndex)
            throw new IllegalArgumentException("Native object is not in the owner's current raid lease");
        if (option < 1 || option > 5) throw new IllegalArgumentException("Invalid native object option");
        OutBuffer buffer = new OutBuffer(7);
        int opcode;
        switch (option) {
            case 1:
                opcode = 51; buffer.addByteC(0); buffer.addShort(object.id);
                buffer.addShortA(object.y); buffer.addLEShortA(object.x); break;
            case 2:
                opcode = 6; buffer.addLEShortA(object.id); buffer.addShort(object.x);
                buffer.addByteS(0); buffer.addShortA(object.y); break;
            case 3:
                opcode = 42; buffer.addShort(object.id); buffer.addShortA(object.x);
                buffer.addLEShortA(object.y); buffer.addByteA(0); break;
            case 4:
                opcode = 95; buffer.addShort(object.x); buffer.addByteC(0);
                buffer.addShortA(object.id); buffer.addShortA(object.y); break;
            default:
                opcode = 50; buffer.addShort(object.y); buffer.addShort(object.x);
                buffer.addShort(object.id); buffer.addByteA(0); break;
        }
        OBJECT_ACTION.handle(player, input(buffer), opcode);
        return true;
    }

    public static boolean inventory(Player player, int id, String action) {
        if (!ready(player)) return false;
        for (Item item : player.getInventory().getItems())
            if (item != null && item.getId() == id) return inventory(player, item, action);
        throw new IllegalStateException("Required carried item is missing: " + id);
    }

    public static boolean inventory(Player player, Item item, String action) {
        if (!ready(player)) return false;
        if (action == null || action.isEmpty()) throw new IllegalArgumentException("Native item action required");
        if (action.equalsIgnoreCase("Drop") || action.equalsIgnoreCase("Destroy"))
            throw new IllegalArgumentException("Raid automation does not discard the owner's carried items");
        if (item == null || item.getContainer() != player.getInventory()
                || player.getInventory().get(item.getSlot()) != item)
            throw new IllegalStateException("Native inventory action requires the exact resident item");
        int option = item.getDef().getOption(action);
        if (option < 1 || option > 5) throw new IllegalStateException("Item has no native action: " + action);
        return inventoryOption(player, item, option);
    }

    public static boolean equip(Player player, Item item) {
        if (!ready(player)) return false;
        if (item == null || item.getContainer() != player.getInventory()
                || player.getInventory().get(item.getSlot()) != item)
            throw new IllegalStateException("Equipping requires the exact carried item");
        int option = item.getDef().equipOption;
        if (option < 1 || option > 5) throw new IllegalStateException("Item has no native Wear/Wield option");
        return inventoryOption(player, item, option);
    }

    private static boolean inventoryOption(Player player, Item item, int option) {
        int id = item.getId(), slot = item.getSlot(), hash = Interface.INVENTORY << 16;
        if (id < 0 || id >= 65535) throw new IllegalArgumentException("Invalid unsigned inventory item ID");
        OutBuffer buffer = new OutBuffer(8);
        int opcode;
        switch (option) {
            case 1:
                opcode = 88; buffer.addInt(hash); buffer.addLEShort(slot); buffer.addLEShort(id); break;
            case 2:
                opcode = 70; buffer.addInt(hash); buffer.addLEShort(slot); buffer.addShortA(id); break;
            case 3:
                opcode = 5; buffer.addLEShort(id); buffer.addLEInt(hash); buffer.addShort(slot); break;
            case 4:
                opcode = 14; buffer.addLEShortA(id); buffer.addLEShort(slot); buffer.addInt1(hash); break;
            case 5:
                opcode = 76; buffer.addShortA(slot); buffer.addInt1(hash); buffer.addShortA(id); break;
            default: throw new IllegalArgumentException("Invalid native inventory option");
        }
        INVENTORY.handle(player, input(buffer), opcode);
        return true;
    }

    public static boolean button(Player player, int widget, int child, int slot, int option, int itemId) {
        if (!ready(player)) return false;
        if (widget < 0 || widget > 65535 || child < 0 || child > 65535
                || slot < -1 || slot > 65535 || itemId < -1 || itemId > 65535 || option < 1 || option > 10)
            throw new IllegalArgumentException("Invalid native button action");
        if (!player.isVisibleInterface(widget)) return false;
        ActionButtonHandler.handleAction(player, option, widget << 16 | child, slot, itemId, false);
        return true;
    }

    public static boolean prayer(Player player, Prayer prayer) {
        if (!ready(player)) return false;
        if (prayer == null) throw new IllegalArgumentException("Native prayer required");
        if (player.getPrayer().isActive(prayer)) return true;
        return button(player, Interface.PRAYER, prayer.ordinal() + 5, -1, 1, -1);
    }

    private static Option[] options(OptionsDialogue dialogue) {
        try { return (Option[]) DIALOGUE_OPTIONS.get(dialogue); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Native dialogue read failed", failure); }
    }

    private static int match(Option[] options, String prefix) {
        int found = -1;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == null || !options[i].name.startsWith(prefix)) continue;
            if (found != -1) throw new IllegalStateException("Ambiguous native dialogue option: " + prefix);
            found = i;
        }
        return found;
    }

    public static boolean hasOption(Player player, String prefix) {
        return menu(player, prefix) != null;
    }

    private static int[] menu(Player player, String prefix) {
        if (player == null || prefix == null || prefix.isEmpty()) return null;
        int[] found = null;
        if (player.optionsDialogue != null && player.isVisibleInterface(Interface.OPTIONS_DIALOGUE)) {
            int index = match(options(player.optionsDialogue), prefix);
            if (index >= 0) found = new int[]{Interface.OPTIONS_DIALOGUE, 1, index + 1};
        }
        OptionScroll scroll = player.optionScroll;
        if (scroll != null && player.isVisibleInterface(187)) {
            int index = match(scroll.options, prefix);
            if (index >= 0) {
                if (found != null) throw new IllegalStateException("Ambiguous active native menus: " + prefix);
                found = new int[]{187, 3, index};
            }
        }
        return found;
    }

    public static boolean select(Player player, String prefix) {
        if (!ready(player)) return false;
        int[] selected = menu(player, prefix);
        return selected != null && button(player, selected[0], selected[1], selected[2], 1, -1);
    }
}
