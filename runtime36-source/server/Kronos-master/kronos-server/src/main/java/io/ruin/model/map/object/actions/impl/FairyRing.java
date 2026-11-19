package io.ruin.model.map.object.actions.impl;

import io.ruin.cache.ItemID;
import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.actions.SimpleAction;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.skills.construction.House;

import java.util.Arrays;

public enum FairyRing {

    /* 'a' combinations */
    MUDSKIPPER_POINT(13, new int[]{0, 0, 3}, new Position(2996, 3114, 0), "Asgarnia: Mudskipper Point", 3),
    ARDOUGNE_ISLANDS(15, new int[]{0, 0, 2}, new Position(2700, 3247, 0), "Islands: South-east of Ardougne", 2),
    DORGESH_KAAN_CAVE(21, new int[]{0, 3, 3}, new Position(2735, 5221, 0), "Dungeons: Dorgesh-Kaan cave", 15),
    KANDARIN_SLAYER_CAVE(23, new int[]{0, 3, 2}, new Position(2780, 3613, 0), "Kandarin: Slayer cave near Rellekka", 14),
    PENGUIN_ISLANDS(25, new int[]{0, 3, 1}, new Position(2500, 3896, 0), "Islands: Penguins", 13),
    PISCATORIS_HUNTER_AREA(29, new int[]{0, 2, 3}, new Position(2319, 3619, 0), "Kandarin: Piscatoris Hunter area", 11),
    FELDIP_HUNTER_AREA(33, new int[]{0, 2, 1}, new Position(2571, 2956, 0), "Feldip Hills: Jungle Hunter area", 9),
    LIGHTHOUSE(35, new int[]{0, 1, 0}, new Position(2503, 3636, 0), "Islands: Lighthouse", 4),
    HAUNTED_WOODS(37, new int[]{0, 1, 3}, new Position(3597, 3495, 0), "Morytania: Haunted Woods", 7),
    ABYSSAL_AREA(39, new int[]{0, 1, 2}, new Position(3059, 4875, 0), "Other Realms: Abyss", 6),
    MCGRUBOR_WOODS(41, new int[]{0, 1, 1}, new Position(2644, 3495, 0), "Kandarin: McGrubor's Wood", 5),

    /* 'b' combinations */
    ISLAND_SALVE(43, new int[]{3, 0, 0}, new Position(3410, 3324, 0), "Islands: River Salve", 48),
    DESERT(45, new int[]{3, 0, 3}, new Position(3251, 3095, 0), "Kharidian Desert: Near the Kalphite Hive", 51),
    ARDOUGNE_ZOO(49, new int[]{3, 0, 1}, new Position(2635, 3266, 0), "Kandarin: Ardougne Zoo unicorns", 49),
    FISHER_KING(55, new int[]{3, 3, 2}, new Position(0, 0, 0), "Other Realms: Fisher King", 62),
    ZUL_ANDRA(57, new int[]{3, 3, 1}, new Position(2150, 3070, 0), "Islands: Near Zul-Andra", 61),
    CASTLE_WARS(59, new int[]{3, 2, 0}, new Position(2385, 3035, 0), "Feldip Hills: South of Castle Wars", 56),
    ENCHANTED_VALLEY(61, new int[]{3, 2, 3}, new Position(3041, 4532, 0), "Other Realms: Enchanted Valley", 59),
    MORYTANIA_MYRE(63, new int[]{3, 2, 2}, new Position(3469, 3431, 0), "Morytania: Mort Myre", 58),
    ZANARIS(65, new int[]{3, 2, 1}, new Position(2412, 4434, 0), "Other Realms: Zanaris", 57),
    TZHAAR(67, new int[]{3, 1, 0}, new Position(2437, 5126, 0), "Dungeons: TzHaar area", 52),
    LEGENDS_GUILD(71, new int[]{3, 1, 2}, new Position(2740, 3351, 0), "Kandarin: Legends' Guild", 54),

    /* 'c' combinations */
    MISCELLANIA(75, new int[]{2, 0, 0}, new Position(2513, 3884, 0), "Islands: Miscellania", 32),
    YANILLE(77, new int[]{2, 0, 3}, new Position(2528, 3127, 0), "Kandarin: North-west of Yanille", 35),
    ARCEUUS_LIBRARY(81, new int[]{2, 0, 1}, new Position(0, 0, 0), "Arceuus Library", 33),
    SINCLAIR_MANSION(87, new int[]{2, 3, 2}, new Position(2705, 3576, 0), "Kandarin: Sinclair Mansion (east)", 46),
    COSMIC_PLANE(91, new int[]{2, 2, 0}, new Position(2075, 4848, 0), "Other Realms: Cosmic Entity's plane", 40),
    KARAMJA(95, new int[]{2, 2, 2}, new Position(2801, 3003, 0), "Karamja: Tai Bwo Wannai Village", 42),
    CANIFIS(97, new int[]{2, 2, 1}, new Position(3447, 3470, 0), "Morytania: Canifis", 41),
    ISLAND_DRAYNOR(99, new int[]{2, 1, 0}, new Position(3082, 3206, 0), "Islands: South of Draynor Village", 36),
    APE_ATOLL(103, new int[]{2, 1, 2}, new Position(2740, 2738, 0), "Islands: Ape Atoll", 38),
    HAZELMERE(105, new int[]{2, 1, 1}, new Position(2682, 3081, 0), "Islands: Hazelmere's home", 37),
    FARMING_GUILD(106, new int[]{2, 0, 2}, new Position(1302, 3762, 0), "Kebos Lowlands: South of Mount Karuulm", 34),

    /* 'd' combinations */
    ABBY_NEXUS(107, new int[]{1, 0, 0}, new Position(3037, 4763, 0), "Other Realms: Abyssal Nexus", 16),
    GORAK_PLANE(111, new int[]{1, 0, 2}, new Position(3038, 5348, 0), "Other Realms: Goraks' plane", 18),
    WIZARD_TOWER(113, new int[]{1, 0, 1}, new Position(3108, 3149, 0), "Misthalin: Wizards' Tower", 17),
    TOWER_OF_LIFE(115, new int[]{1, 3, 0}, new Position(2658, 3230, 0), "Kandarin: Tower of Life", 28),
    // Keep the persisted enum name for older travel logs.
    SINCLAIR_WEST(119, new int[]{1, 3, 2}, new Position(1455, 3658, 0), "Great Kourend: Chasm of Fire", 30),
    GNOME_GLIDER_KARAMAJA(123, new int[]{1, 2, 0}, new Position(2900, 3111, 0), "Karamja: Gnome Glider", 24),
    EDGEVILLE(127, new int[]{1, 2, 2}, new Position(3129, 3496, 0), "Misthalin: Edgeville", 26),
    POLAR_HUNTER(129, new int[]{1, 2, 1}, new Position(2744, 3719, 0), "Kandarin: Polar Hunter area", 25),
    NARDAH(133, new int[]{1, 1, 3}, new Position(3423, 3016, 0), "Kharidian Desert: North of Nardah", 23),
    POISON_WASTE(135, new int[]{1, 1, 2}, new Position(2213, 3099, 0), "Islands: Poison Waste", 22),
    HOLLOWS_HIDEOUT(137, new int[]{1, 1, 1}, new Position(3501, 9821, 3), "Dungeons: Myreque hideout under The Hollows", 21),
    PLAYER_OWNED_HOUSE(109, new int[]{1, 0, 3}, new Position(3200, 3200, 0), "Player Owned House", 19);

    public final int childId;
    public final int[] varpbitValues;
    public final Position position;
    public final String targetName;
    public final int lastDestination;

    FairyRing(int childId, int[] varpbitValues, Position position, String targetName, int lastDestination) {
        this.childId = childId;
        this.varpbitValues = varpbitValues;
        this.position = position;
        this.targetName = targetName;
        this.lastDestination = lastDestination;
    }

    private static final int[] WORLD_RING_IDS = {29495, 29560, 39320};
    private static final int[][] ADJACENT = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};

    public static void teleport(Player player, FairyRing destination, GameObject origin) {
        if (!canUse(player, origin)) return;
        if (destination == null) {
            player.sendFilteredMessage("No fairy ring matches that code.");
            return;
        }
        House house = destination == PLAYER_OWNED_HOUSE ? player.house : null;
        if (destination == PLAYER_OWNED_HOUSE && (house == null || house.getFairyRingPosition() == null)) {
            player.sendFilteredMessage("Your house needs a built fairy ring.");
            return;
        }
        if (house == null && destination.landing() == null) {
            player.sendFilteredMessage("That fairy ring destination is unavailable.");
            return;
        }
        player.getMovement().startTeleport(0, event -> {
            player.animate(3265, 30);
            player.graphics(569);
            event.delay(3);
            resetCombinationPanel(player);
            if (house != null) {
                // The house loader owns its own event and lock once the travel event has finished.
                player.addEvent(enter -> {
                    enter.delay(1);
                    if (player.house == house && house.getFairyRingPosition() != null && canUse(player, origin))
                        house.buildAndEnter(player, house.getFairyRingPosition(), false, () -> remember(player, destination));
                });
                return;
            }
            Position landing = destination.landing();
            if (landing == null) return;
            player.getMovement().teleport(landing);
            remember(player, destination);
            player.animate(3266);
            event.delay(1);
        });
    }

    /** The map must contain the actual return ring. Use an adjacent unclipped square. */
    Position landing() {
        if (this == PLAYER_OWNED_HOUSE || position.getX() == 0) return null;
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        for (int id : WORLD_RING_IDS) {
            if (Tile.getObject(id, x, y, z, 10, -1) == null) continue;
            for (int[] offset : ADJACENT) {
                Position candidate = Position.of(x + offset[0], y + offset[1], z);
                Tile tile = Tile.get(candidate);
                if (tile == null || tile.isFloorFreeCheckDecor()) return candidate;
            }
        }
        return null;
    }

    private static void remember(Player player, FairyRing destination) {
        Config.FAIRY_RING_LAST_DESTINATION.set(player, destination.lastDestination);
        FairyRing[] unlocked = player.unlockedFairyRingTeleports;
        if (unlocked == null) unlocked = new FairyRing[values().length];
        else if (unlocked.length < values().length) unlocked = Arrays.copyOf(unlocked, values().length);
        unlocked[destination.ordinal()] = destination;
        player.unlockedFairyRingTeleports = unlocked;
    }

    private static boolean canUse(Player player, GameObject origin) {
        if (origin == null || player.isLocked() || !player.getPosition().isWithinDistance(origin.getPosition(), 3)
                || Tile.getObject(origin.id, origin.x, origin.y, origin.z, origin.type, origin.direction) != origin)
            return false;
        int weapon = player.getEquipment().getId(Equipment.SLOT_WEAPON);
        if (weapon != ItemID.DRAMEN_STAFF && weapon != ItemID.LUNAR_STAFF) {
            player.sendFilteredMessage("Equip a dramen staff or lunar staff to use the fairy ring.");
            return false;
        }
        return true;
    }

    public static void openCombinationPanel(Player player, GameObject fairyRing) {
        if (!canUse(player, fairyRing)) return;
        if (player.unlockedFairyRingTeleports != null) {
            for (FairyRing ring : player.unlockedFairyRingTeleports)
                if (ring != null && (ring == PLAYER_OWNED_HOUSE || ring.landing() != null))
                    player.getPacketSender().sendString(Interface.FAIRY_RING_TRAVEL_LOG, ring.childId, (ring.targetName.equals("") ? "" : "<br>" + ring.targetName));
        }

        resetCombinationPanel(player);
        player.openInterface(InterfaceType.MAIN, Interface.FAIRY_RING_COMBINATION);
        player.openInterface(InterfaceType.INVENTORY, Interface.FAIRY_RING_TRAVEL_LOG);
        player.fairyRing = fairyRing;
    }

    public static void repeat(Player player, GameObject origin) {
        int last = Config.FAIRY_RING_LAST_DESTINATION.get(player);
        for (FairyRing destination : values()) {
            if (destination.lastDestination == last) {
                teleport(player, destination, origin);
                return;
            }
        }
        player.sendFilteredMessage("Configure the fairy ring to choose a destination first.");
    }

    private static void resetCombinationPanel(Player player) {
        Config.FAIRY_RING_LEFT.set(player, 0);
        Config.FAIRY_RING_RIGHT.set(player, 0);
        Config.FAIRY_RING_MIDDLE.set(player, 0);
    }

    private static int leftButton(Player player) {
        return Config.FAIRY_RING_LEFT.get(player);
    }

    private static int rightButton(Player player) {
        return Config.FAIRY_RING_RIGHT.get(player);
    }

    private static int middleButton(Player player) {
        return Config.FAIRY_RING_MIDDLE.get(player);
    }

    static {
        // The legacy morph parents carry no labels. The client renders their option slots from transformed definitions.
        for (int id : new int[]{29495, 29560, 29228}) {
            if (ObjectDef.get(id) == null) continue;
            if (id != 29560) ObjectAction.register(id, 1, (player, obj) -> teleport(player, ZANARIS, obj));
            ObjectAction.register(id, 2, FairyRing::openCombinationPanel);
            ObjectAction.register(id, 3, FairyRing::repeat);
        }
        if (ObjectDef.get(39320) != null) {
            ObjectAction.register(39320, "Zanaris", (player, obj) -> teleport(player, ZANARIS, obj));
            ObjectAction.register(39320, "Configure", FairyRing::openCombinationPanel);
            ObjectAction.register(39320, "Last-destination", FairyRing::repeat);
            ObjectAction.register(39320, "Favourites", (player, obj) -> {
                if (canUse(player, obj)) player.sendFilteredMessage("Use Configure to view your fairy ring travel log.");
            });
        }

        InterfaceHandler.register(Interface.FAIRY_RING_COMBINATION, h -> {
            h.closedAction = (p, ignored) -> p.fairyRing = null;
            /**
             * Left wheel
             */
            h.actions[19] = (SimpleAction) p -> Config.FAIRY_RING_LEFT.set(p, (leftButton(p) == 3 ? 0 : leftButton(p) + 1));
            h.actions[20] = (SimpleAction) p -> Config.FAIRY_RING_LEFT.set(p, (leftButton(p) == 0 ? 3 : leftButton(p) - 1));
            /**
             * Middle wheel
             */
            h.actions[21] = (SimpleAction) p -> Config.FAIRY_RING_MIDDLE.set(p, (middleButton(p) == 3 ? 0 : middleButton(p) + 1));
            h.actions[22] = (SimpleAction) p -> Config.FAIRY_RING_MIDDLE.set(p, (middleButton(p) == 0 ? 3 : middleButton(p) - 1));
            /**
             * Right wheel
             */
            h.actions[23] = (SimpleAction) p -> Config.FAIRY_RING_RIGHT.set(p, (rightButton(p) == 3 ? 0 : rightButton(p) + 1));
            h.actions[24] = (SimpleAction) p -> Config.FAIRY_RING_RIGHT.set(p, (rightButton(p) == 0 ? 3 : rightButton(p) - 1));
            /**
             * Teleport
             */
            h.actions[26] = (SimpleAction) p -> {
                for (FairyRing fairyRing : values()) {
                    if (fairyRing.varpbitValues[0] == leftButton(p) && fairyRing.varpbitValues[1] == middleButton(p) && fairyRing.varpbitValues[2] == rightButton(p)) {
                        teleport(p, fairyRing, p.fairyRing);
                        return;
                    }
                }

                resetCombinationPanel(p);
                teleport(p, null, p.fairyRing);
            };
        });

        InterfaceHandler.register(Interface.FAIRY_RING_TRAVEL_LOG, h -> {
            for (FairyRing fairyRing : values()) {
                h.actions[fairyRing.childId] = (SimpleAction) p -> {
                    if (!p.isVisibleInterface(Interface.FAIRY_RING_COMBINATION) || p.fairyRing == null) return;
                    Config.FAIRY_RING_LEFT.set(p, fairyRing.varpbitValues[0]);
                    Config.FAIRY_RING_MIDDLE.set(p, fairyRing.varpbitValues[1]);
                    Config.FAIRY_RING_RIGHT.set(p, fairyRing.varpbitValues[2]);
                };
            }
        });
    }

}
