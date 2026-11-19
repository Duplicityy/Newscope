package io.ruin.model.entity.npc.actions.traveling;

import io.ruin.Server;
import io.ruin.cache.ItemID;
import io.ruin.cache.NPCDef;
import io.ruin.cache.Varpbit;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Paid charter bookings at established docks. Prices are indexed in Port declaration order. */
public final class CharterTravel {
    enum Port {
        PORT_SARIM("Port Sarim", 3038, 3192, false),
        BRIMHAVEN("Brimhaven", 2760, 3238, false),
        CATHERBY("Catherby", 2792, 3414, false),
        MUSA_POINT("Musa Point", 2954, 3158, false),
        PORT_KHAZARD("Port Khazard", 2674, 3144, false),
        CORSAIR_COVE("Corsair Cove", 2589, 2851, false),
        PISCARILIUS("Port Piscarilius", 1824, 3691, true),
        LANDS_END("Land's End", 1504, 3399, true);

        final String name;
        final Position landing;
        final boolean kourend;

        Port(String name, int x, int y, boolean kourend) {
            this.name = name;
            this.landing = Position.of(x, y, 0);
            this.kourend = kourend;
        }
    }

    // Zero denotes an unoffered leg, including ports served by the existing fixed ferry.
    private static final int[][] FARES = {
            {0, 1600, 1000, 0, 1280, 1200, 0, 0},
            {1600, 0, 480, 480, 400, 680, 2000, 2200},
            {1000, 480, 0, 480, 1600, 1000, 2000, 2200},
            {0, 480, 480, 0, 400, 800, 2500, 2700},
            {1280, 400, 1600, 400, 0, 600, 1800, 2000},
            {1200, 680, 1000, 800, 600, 0, 1500, 1800},
            {0, 2000, 2000, 2500, 1800, 1500, 0, 0},
            {0, 2200, 2200, 2700, 2000, 1800, 0, 0}
    };
    private static final int ZEAH_PLAYERHASVISITED = 4897;
    private static final Config KOUREND_VISITED = Varpbit.get(ZEAH_PLAYERHASVISITED) == null
            ? null : Config.varpbit(ZEAH_PLAYERHASVISITED, true);
    private static final Port[] PORTS = Port.values();
    private static final Set<Port> ACTIVE_PORTS = EnumSet.noneOf(Port.class);

    static {
        int crews = 0;
        for (NPC npc : World.npcs.entityList) {
            if (npc == null || !npc.defaultSpawn || !isCrew(npc.getDef())) continue;
            Port port = dockOf(npc.getSpawnPosition());
            if (port == null || !walkable(port.landing) || !bind(npc, "Charter", port)) continue;
            bind(npc, "Talk-to", port);
            ACTIVE_PORTS.add(port);
            crews++;
        }
        Server.println("Charter travel: " + ACTIVE_PORTS.size() + " of " + PORTS.length
                + " ports, " + crews + " crew placements.");
    }

    private CharterTravel() {
    }

    private static boolean isCrew(NPCDef definition) {
        return definition != null && (definition.name.equalsIgnoreCase("Trader Crewmember")
                || definition.name.equalsIgnoreCase("Trader Stan"));
    }

    private static Port dockOf(Position spawn) {
        for (Port port : PORTS)
            if (spawn.isWithinDistance(port.landing, 8)) return port;
        return null;
    }

    private static boolean bind(NPC npc, String label, Port port) {
        NPCDef def = npc.getDef();
        int option = def.getOption(label);
        if (option <= 0 || npc.actions != null && npc.actions[option - 1] != null
                || def.defaultActions != null && def.defaultActions[option - 1] != null) return false;
        NPCAction.register(npc, option, (player, crew) -> open(player, crew, port, 0));
        return true;
    }

    static int baseFare(Port origin, Port destination) {
        return FARES[origin.ordinal()][destination.ordinal()];
    }

    static int fare(Player player, Port origin, Port destination) {
        int amount = baseFare(origin, destination);
        if (player.getEquipment().getId(Equipment.SLOT_RING) == ItemID.RING_OF_CHAROSA) amount /= 2;
        return amount;
    }

    private static boolean accessible(Player player, Port origin, Port destination) {
        return baseFare(origin, destination) > 0 && ACTIVE_PORTS.contains(destination)
                && (!destination.kourend || KOUREND_VISITED != null && KOUREND_VISITED.get(player) == 1);
    }

    private static boolean besideCrew(Player player, NPC crew, Port origin) {
        return player.isOnline() && !player.isLocked() && !player.getCombat().isDead() && !crew.isRemoved()
                && dockOf(crew.getSpawnPosition()) == origin
                && player.getPosition().isWithinDistance(crew.getPosition(), 3);
    }

    private static void open(Player player, NPC crew, Port origin, int offset) {
        if (!besideCrew(player, crew, origin)) return;
        if (origin.kourend && KOUREND_VISITED != null && KOUREND_VISITED.get(player) == 0)
            KOUREND_VISITED.set(player, 1);

        List<Port> destinations = new ArrayList<>(PORTS.length - 1);
        for (Port port : PORTS) {
            if (baseFare(origin, port) > 0 && ACTIVE_PORTS.contains(port)) destinations.add(port);
        }
        int end = Math.min(offset + 3, destinations.size());
        List<Option> options = new ArrayList<>(5);
        for (int i = offset; i < end; i++) {
            Port port = destinations.get(i);
            String cost = accessible(player, origin, port) ? fare(player, origin, port) + " gp" : "Locked";
            options.add(new Option(port.name + "  (" + cost + ")", p -> quote(p, crew, origin, port, offset)));
        }
        if (offset > 0) options.add(new Option("Previous", p -> open(p, crew, origin, offset - 3)));
        if (end < destinations.size()) options.add(new Option("Next", p -> open(p, crew, origin, end)));
        else options.add(new Option("Stay here"));
        player.dialogue(new OptionsDialogue("Charter from " + origin.name, options));
    }

    private static void quote(Player player, NPC crew, Port origin, Port destination, int offset) {
        if (!besideCrew(player, crew, origin)) return;
        if (!accessible(player, origin, destination)) {
            player.dialogue(new OptionsDialogue("Visit Great Kourend before chartering there.",
                    new Option("Back to destinations", p -> open(p, crew, origin, offset)),
                    new Option("Stay here")));
            return;
        }
        int price = fare(player, origin, destination);
        player.dialogue(new OptionsDialogue("Charter to " + destination.name + "?",
                new Option("Pay " + price + " coins and board", p -> book(p, crew, origin, destination, price)),
                new Option("Back to destinations", p -> open(p, crew, origin, offset)),
                new Option("Stay here")));
    }

    private static boolean canSail(Player player, Port origin, Port destination, int price) {
        if (player.wildernessLevel > 0 || player.pvpAttackZone || player.getCombat().isDead()) return false;
        if (!accessible(player, origin, destination) || !walkable(destination.landing)) {
            player.sendMessage("That charter is currently unavailable.");
            return false;
        }
        if (fare(player, origin, destination) != price) {
            player.sendMessage("The fare has changed. Ask the crew for a new quote.");
            return false;
        }
        if (player.getInventory().getAmount(ItemID.COINS_995) < price) {
            player.sendMessage("You need " + price + " coins to board this charter.");
            return false;
        }
        return !player.getMovement().isTeleportQueued();
    }

    private static void book(Player player, NPC crew, Port origin, Port destination, int price) {
        if (!besideCrew(player, crew, origin) || !canSail(player, origin, destination, price)) return;
        Position departure = player.getPosition().copy();
        player.getMovement().startTeleport(0, event -> {
            player.getPacketSender().fadeOut();
            try {
                event.delay(2);
                if (!player.isOnline() || crew.isRemoved() || !player.getPosition().equals(departure)
                        || !canSail(player, origin, destination, price)) return;
                Position landing = destination.landing;
                player.getMovement().teleport(landing.getX(), landing.getY(), landing.getZ());
                if (player.getMovement().isTeleportQueued()) player.getInventory().remove(ItemID.COINS_995, price);
            } finally {
                player.getPacketSender().clearFade();
                player.getPacketSender().fadeIn();
            }
        });
    }

    private static boolean walkable(Position point) {
        int x = point.getX(), y = point.getY();
        Region region = Region.get(x, y);
        if (region == null || region.empty || region.dynamicIndex != -1 || Server.fileStore == null
                || Server.fileStore.get(5).getArchiveId("m" + (x >> 6) + "_" + (y >> 6)) == -1) return false;
        Tile tile = Tile.get(point);
        return tile == null || tile.isFloorFreeCheckDecor();
    }
}
