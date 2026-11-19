package io.ruin.model.entity.npc.actions.traveling;

import io.ruin.Server;
import io.ruin.cache.ItemID;
import io.ruin.cache.NPCDef;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.dialogue.NPCDialogue;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;

/** Fixed passenger crossings with their own crew and dock landings. */
public final class PassengerBoats {
    private static final Position SARIM_MUSA_DOCK = Position.of(3029, 3217, 0);
    private static final Position MUSA_DOCK = Position.of(2956, 3146, 0);
    private static final Position SARIM_VOID_DOCK = Position.of(3041, 3202, 0);
    private static final Position VOID_DOCK = Position.of(2659, 2676, 0);
    private static final int STANDARD_FARE = 30;
    private static final int DISCOUNT_FARE = 15;

    static {
        Crossing musa = new Crossing("Musa Point", MUSA_DOCK, true, false);
        Crossing sarimCustoms = new Crossing("Port Sarim", SARIM_MUSA_DOCK, true, true);
        Crossing voidOutpost = new Crossing("the Void Knights' Outpost", VOID_DOCK, false, false);
        Crossing sarim = new Crossing("Port Sarim", SARIM_VOID_DOCK, false, false);
        register(3644, Position.of(3026, 3216, 0), musa);
        register(3645, Position.of(3026, 3221, 0), musa);
        register(3646, Position.of(3026, 3216, 0), musa);
        register(3648, Position.of(2953, 3146, 0), sarimCustoms);
        register(1770, SARIM_VOID_DOCK, voidOutpost);
        register(1769, Position.of(2658, 2675, 0), sarim);

        registerGangplank(2083, 2084, SARIM_MUSA_DOCK, Position.of(3032, 3217, 1));
        registerGangplank(2081, 2082, MUSA_DOCK, Position.of(2956, 3143, 1));
        registerGangplank(14304, 14305, SARIM_VOID_DOCK, Position.of(3041, 3199, 1));
        registerGangplank(14306, 14307, VOID_DOCK, Position.of(2662, 2676, 1));
    }

    private PassengerBoats() {}

    private static void register(int npcId, Position origin, Crossing crossing) {
        bind(npcId, "Talk-to", (player, npc) -> {
            if (!atCrew(player, npc, origin)) return;
            player.dialogue(new NPCDialogue(npc, "This boat is headed to " + crossing.destination + "."),
                    new OptionsDialogue("Passenger boat",
                            new Option("Ask to board", p -> offerFare(p, npc, origin, crossing, true)),
                            new Option("Stay here")));
        });
        bind(npcId, crossing.paid ? "Pay-fare" : "Travel", (player, npc) -> {
            if (crossing.paid) offerFare(player, npc, origin, crossing, false);
            else board(player, npc, origin, crossing, 0);
        });
    }

    private static void bind(int npcId, String label, NPCAction action) {
        NPCDef definition = NPCDef.get(npcId);
        if (definition == null) return;
        int slot = definition.getOption(label);
        if (slot < 1 || definition.defaultActions != null && definition.defaultActions[slot - 1] != null) return;
        NPCAction.register(npcId, slot, action);
    }

    private static void registerGangplank(int shoreId, int deckId, Position shore, Position deck) {
        int dx = Integer.signum(deck.getX() - shore.getX());
        int dy = Integer.signum(deck.getY() - shore.getY());
        GameObject embark = Tile.getObject(shoreId, shore.getX() + dx, shore.getY() + dy, shore.getZ());
        GameObject disembark = Tile.getObject(deckId, deck.getX() - dx, deck.getY() - dy, deck.getZ());
        int embarkOption = unusedCrossOption(embark);
        int disembarkOption = unusedCrossOption(disembark);
        if (embarkOption < 1 || disembarkOption < 1 || !walkable(shore) || !walkable(deck)) return;
        embark.walkTo = shore;
        disembark.walkTo = deck;
        ObjectAction.register(embark, embarkOption, (player, obj) -> cross(player, obj, shore, deck));
        ObjectAction.register(disembark, disembarkOption, (player, obj) -> cross(player, obj, deck, shore));
    }

    private static int unusedCrossOption(GameObject object) {
        if (object == null || object.walkTo != null) return -1;
        int slot = object.getDef().getOption("Cross");
        if (slot < 1 || object.actions != null && object.actions[slot - 1] != null) return -1;
        ObjectAction[] defaults = object.getDef().defaultActions;
        return defaults == null || defaults[slot - 1] == null ? slot : -1;
    }

    private static void cross(Player player, GameObject object, Position origin, Position destination) {
        if (!player.isLocked() && !object.isRemoved() && player.getPosition().equals(origin) && walkable(destination)) {
            player.getMovement().teleport(destination.getX(), destination.getY(), destination.getZ());
        }
    }

    private static void offerFare(Player player, NPC npc, Position origin, Crossing crossing, boolean offerCharm) {
        if (!atCrew(player, npc, origin)) return;
        int fare = fare(player, crossing);
        String ticket = fare == 0 ? "Board for free" : "Board for " + fare + " coins";
        Option board = new Option(ticket, p -> board(p, npc, origin, crossing, fare));
        Option cancel = new Option("Stay here");
        if (crossing.paid && offerCharm && wearingCharos(player)) {
            player.dialogue(new OptionsDialogue("Travel to " + crossing.destination, board,
                    new Option("Persuade the crew to waive the fare", p -> board(p, npc, origin, crossing, 0)), cancel));
        } else {
            player.dialogue(new OptionsDialogue("Travel to " + crossing.destination, board, cancel));
        }
    }

    private static void board(Player player, NPC npc, Position origin, Crossing crossing, int fare) {
        if (!atCrew(player, npc, origin) || player.isLocked()) return;
        if (player.wildernessLevel != 0 || player.pvpAttackZone) {
            player.sendMessage("You cannot board from a PvP area.");
            return;
        }
        if (crossing.paid && fare == 0 && !wearingCharos(player)) {
            player.sendMessage("You need to be wearing the activated ring of Charos.");
            return;
        }
        if (fare > 0 && fare != fare(player, crossing)) {
            player.sendMessage("The fare has changed. Ask the crew for a new ticket.");
            return;
        }
        if (!canArrive(player, crossing, fare)) return;

        // Finish the fare and arrival together on the native, locked transport event.
        player.getMovement().startTeleport(0, event -> {
            player.getPacketSender().fadeOut();
            try {
                event.delay(2);
                if (!canArrive(player, crossing, fare)) return;
                Position landing = crossing.landing;
                player.getMovement().teleport(landing.getX(), landing.getY(), landing.getZ());
                if (fare > 0 && player.getMovement().isTeleportQueued()) player.getInventory().remove(ItemID.COINS_995, fare);
            } finally {
                player.getPacketSender().clearFade();
                player.getPacketSender().fadeIn();
            }
        });
    }

    private static boolean canArrive(Player player, Crossing crossing, int fare) {
        if (!walkable(crossing.landing)) {
            player.sendMessage("The landing is currently unavailable.");
            return false;
        }
        if (crossing.customs && (player.getInventory().hasId(ItemID.KARAMJAN_RUM)
                || player.getInventory().hasId(ItemID.KARAMJAN_RUM_3164)
                || player.getInventory().hasId(ItemID.KARAMJAN_RUM_3165))) {
            player.sendMessage("Customs will not let you bring Karamjan rum aboard.");
            return false;
        }
        if (player.getInventory().getAmount(ItemID.COINS_995) < fare) {
            player.sendMessage("You need " + fare + " coins to board.");
            return false;
        }
        return true;
    }

    private static int fare(Player player, Crossing crossing) {
        if (!crossing.paid) return 0;
        int gloves = player.getEquipment().getId(Equipment.SLOT_HANDS);
        switch (gloves) {
            case ItemID.KARAMJA_GLOVES_1:
            case ItemID.KARAMJA_GLOVES_2:
            case ItemID.KARAMJA_GLOVES_3:
            case ItemID.KARAMJA_GLOVES_4:
                return DISCOUNT_FARE;
            default:
                return STANDARD_FARE;
        }
    }

    private static boolean wearingCharos(Player player) {
        return player.getEquipment().getId(Equipment.SLOT_RING) == ItemID.RING_OF_CHAROSA;
    }

    private static boolean atCrew(Player player, NPC npc, Position origin) {
        return !npc.isRemoved() && npc.getSpawnPosition().equals(origin)
                && player.getPosition().isWithinDistance(npc.getPosition(), 3);
    }

    private static boolean walkable(Position point) {
        Region region = Region.get(point.getX(), point.getY());
        if (region == null || region.empty || Server.fileStore == null || Server.fileStore.get(5).getArchiveId(
                "m" + (point.getX() >> 6) + "_" + (point.getY() >> 6)) < 0) return false;
        Tile tile = Tile.get(point);
        return tile == null || tile.isFloorFreeCheckDecor();
    }

    private static final class Crossing {
        final String destination;
        final Position landing;
        final boolean paid, customs;

        Crossing(String destination, Position landing, boolean paid, boolean customs) {
            this.destination = destination;
            this.landing = landing;
            this.paid = paid;
            this.customs = customs;
        }
    }
}
