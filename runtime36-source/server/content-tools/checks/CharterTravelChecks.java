package io.ruin.model.entity.npc.actions.traveling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.netty.channel.embedded.EmbeddedChannel;
import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.login.LoginInfo;
import io.ruin.api.protocol.world.WorldStage;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.AnimDef;
import io.ruin.cache.InterfaceDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.ItemID;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.Varpbit;
import io.ruin.data.impl.npcs.npc_spawns;
import io.ruin.model.World;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.map.route.routes.TargetRoute;
import io.ruin.network.incoming.handlers.NPCActionHandler;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.ruin.model.entity.npc.actions.traveling.CharterTravel.Port;

/** Tests native crew and dialogue clicks, the real route finder, and native locked movement. */
public final class CharterTravelChecks {
    public static void main(String[] args) {
        try {
            Thread.currentThread().setName("server-worker #1");
            checkRoutes(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void checkRoutes(Path release, Path addedSpawns, Path reference) throws Exception {
        Server.dataFolder = release.resolve("data").toFile();
        Server.fileStore = new FileStore(release.resolve("Cache").toString(), 8 * 1024 * 1024);
        World.type = WorldType.PVP;
        World.stage = WorldStage.DEV;
        Varpbit.load();
        InterfaceDef.load();
        ObjectDef.load();
        NPCDef.load();
        ItemDef.load();
        AnimDef.load();
        MapListener.LISTENERS = new MapListener[0];
        Gson gson = new Gson();
        try (Reader source = Files.newBufferedReader(release.resolve("data/items/item_info.json"))) {
            for (JsonElement entry : gson.fromJson(source, JsonArray.class)) {
                JsonObject item = entry.getAsJsonObject();
                if (item.has("equip_slot") && !item.get("equip_slot").isJsonNull()) {
                    ItemDef def = ItemDef.get(item.get("id").getAsInt());
                    if (def != null) def.equipSlot = item.get("equip_slot").getAsInt();
                }
            }
        }
        Map<Integer, int[]> keys;
        try (Reader source = Files.newBufferedReader(release.resolve("data/region_keys.json"))) {
            keys = gson.fromJson(source, new TypeToken<Map<Integer, int[]>>() {}.getType());
        }
        for (int index = 0; index < Region.LOADED.length; index++) {
            Region region = new Region(index);
            region.keys = keys.get(index);
            Region.LOADED[index] = region;
        }
        Set<Integer> loaded = new HashSet<>();
        for (Port port : Port.values()) {
            for (int x = port.landing.getX() - 10; x <= port.landing.getX() + 10; x++) {
                for (int y = port.landing.getY() - 10; y <= port.landing.getY() + 10; y++) {
                    Region region = Region.get(x, y);
                    if (loaded.add(region.id)) region.init();
                }
            }
            require(Region.get(port.landing.getX(), port.landing.getY()).getLandscapeData() != null,
                    port.name + " has installed scenery");
        }
        // PlayerFile loads the wilderness obelisk scripts even for a player using a coastal dock.
        for (int[] site : new int[][]{{3156,3620},{3227,3667},{3035,3732},{3106,3794},{2980,3866},{3307,3916}}) {
            Region region = Region.get(site[0], site[1]);
            if (loaded.add(region.id)) region.init();
        }

        npc_spawns loader = new npc_spawns();
        try (Stream<Path> files = Files.walk(release.resolve("data/npcs/spawns"))) {
            files.filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> loadRelevantCrew(gson, loader, path));
        }
        loadRelevantCrew(gson, loader, addedSpawns);
        Class.forName(CharterTravel.class.getName());
        Map<Port, NPC> crew = new EnumMap<>(Port.class);
        for (NPC npc : World.npcs.entityList) {
            if (npc == null || npc.actions == null || npc.getDef().getOption("Charter") < 1
                    || npc.actions[npc.getDef().getOption("Charter") - 1] == null) continue;
            for (Port port : Port.values()) {
                if (npc.getSpawnPosition().isWithinDistance(port.landing, 8)
                        && !npc.getSpawnPosition().equals(port.landing)) crew.putIfAbsent(port, npc);
            }
        }
        require(crew.size() == 8, "All eight ports have local crew");

        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
        };
        try {
            LoginInfo login = new LoginInfo(channel, "worldfixture", "", "", "", "", 0, false, 0, 1, new int[4]);
            login.update(1001, "worldfixture", null, Collections.singletonList(PlayerGroup.REGISTERED.id), 0);
            Player player = PlayerFile.decode("{\"position\":{\"x\":2760,\"y\":3238,\"z\":0}}", Player.class);
            player.init(login);
            Config.load(player);
            player.setOnline(true);
            Port brim = Port.BRIMHAVEN, catherby = Port.CATHERBY, land = Port.LANDS_END;
            player.getInventory().add(ItemID.COINS_995, 100000);
            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, land);
            require(offers(player, "Back to destinations") && !offers(player, "Pay "), "Kourend denied before visit");
            unchanged(player, 100000);

            open(player, land, crew.get(land), "Talk-to");
            require(offers(player, "Brimhaven"), "Talk-to opens charter and records physical Kourend visit");
            Method snapshot = PlayerFile.class.getDeclaredMethod("snapshot", Player.class);
            snapshot.setAccessible(true);
            Player reloaded = PlayerFile.decode((String) snapshot.invoke(null, player), Player.class);
            Config.load(reloaded);
            Varpbit bit = Varpbit.get(4897);
            require((reloaded.varps[bit.varpId] >>> bit.leastSigBit & 1) == 1, "Kourend visit survives save and reload");

            Map<Port, Map<Port, Integer>> expected = fares(reference);
            int voyages = 0;
            for (Port origin : Port.values()) for (Port destination : Port.values()) {
                int fare = expected.get(origin).getOrDefault(destination, 0);
                require(CharterTravel.baseFare(origin, destination) == fare, "Reference fare " + origin + " to " + destination);
                if (fare == 0) continue;
                int coins = balance(player);
                open(player, origin, crew.get(origin), "Charter");
                pickPort(player, destination);
                choose(player, "Pay " + fare + " coins");
                finish(player, destination, coins - fare);
                voyages++;
            }
            require(voyages == 48, "All 48 supported fares and voyages");

            int coins = balance(player);
            player.getEquipment().set(Equipment.SLOT_RING, new Item(ItemID.RING_OF_CHAROS));
            require(CharterTravel.fare(player, brim, catherby) == 480, "Inactive ring charges full fare");
            player.getEquipment().set(Equipment.SLOT_RING, new Item(ItemID.RING_OF_CHAROSA));
            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, catherby);
            choose(player, "Pay 240 coins");
            finish(player, catherby, coins - 240);
            coins -= 240;

            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, catherby);
            player.getEquipment().set(Equipment.SLOT_RING, null);
            choose(player, "Pay 240 coins");
            unchanged(player, coins);
            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, catherby);
            player.teleportListener = p -> false;
            choose(player, "Pay 480 coins");
            unchanged(player, coins);
            player.teleportListener = null;
            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, catherby);
            player.pvpAttackZone = true;
            choose(player, "Pay 480 coins");
            unchanged(player, coins);
            player.pvpAttackZone = false;
            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, catherby);
            choose(player, "Pay 480 coins");
            require(player.isLocked(), "Ticket booked");
            Tile landing = Tile.get(catherby.landing.getX(), catherby.landing.getY(), 0, true);
            int clipping = landing.clipping;
            try {
                landing.flagUnmovable();
                tick(player);
                unchanged(player, coins);
            } finally {
                landing.clipping = clipping;
            }
            player.getInventory().remove(ItemID.COINS_995, coins - 479);
            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, catherby);
            choose(player, "Pay 480 coins");
            unchanged(player, 479);
            open(player, brim, crew.get(brim), "Charter");
            pickPort(player, catherby);
            choose(player, "Stay here");
            unchanged(player, 479);
        } finally {
            channel.finishAndReleaseAll();
        }
        System.out.println("PASS all eight docks and 48 native charter legs, verified fares, gate persistence, quote validation and rejected bookings");
    }

    private static void loadRelevantCrew(Gson gson, npc_spawns loader, Path path) {
        try (Reader source = Files.newBufferedReader(path)) {
            JsonArray entries = gson.fromJson(source, JsonArray.class);
            JsonArray selected = new JsonArray();
            for (JsonElement entry : entries) {
                JsonObject row = entry.getAsJsonObject();
                int id = row.get("id").getAsInt();
                NPCDef def = NPCDef.get(id);
                if (def == null || def.getOption("Charter") < 1) continue;
                for (Port port : Port.values()) {
                    if (port.landing.isWithinDistance(Position.of(row.get("x").getAsInt(),
                            row.get("y").getAsInt(), row.get("z").getAsInt()), 8)) {
                        selected.add(entry);
                        break;
                    }
                }
            }
            if (selected.size() > 0)
                loader.fromJson(path.toFile(), path.getFileName().toString(), gson.toJson(selected));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load native crew fixture " + path, failure);
        }
    }

    private static Map<Port, Map<Port, Integer>> fares(Path reference) throws Exception {
        Map<Port, Map<Port, Integer>> fares = new EnumMap<>(Port.class);
        for (Port port : Port.values()) fares.put(port, new EnumMap<>(Port.class));
        Port origin = null;
        for (String line : Files.readAllLines(reference)) {
            String[] parts = line.split("\t");
            if (line.startsWith("# ")) origin = named(parts[0].substring(2));
            else if (parts.length == 8 && origin != null) {
                Port destination = named(parts[7].substring(3));
                if (destination != null) fares.get(origin).put(destination, Integer.parseInt(parts[3].substring(6)));
            }
        }
        return fares;
    }

    private static Port named(String name) {
        for (Port port : Port.values()) if (port.name.equals(name)) return port;
        return null;
    }

    private static void open(Player player, Port port, NPC npc, String label) {
        player.closeDialogue();
        player.getPosition().set(port.landing.getX(), port.landing.getY(), 0);
        player.getMovement().reset();
        RouteType route = player.getRouteFinder().routeEntity(npc);
        require(route.reachable, port.name + " landing can reach crew");
        player.getPosition().set(route.finishX, route.finishY, 0);
        player.getMovement().reset();
        NPCActionHandler.handleAction(player, npc.getDef().getOption(label), npc.getIndex(), 0);
        TargetRoute.beforeMovement(player);
        TargetRoute.afterMovement(player);
        require(player.optionsDialogue != null, label + " opens native booking at " + port.name);
    }

    private static Option[] options(Player player) throws Exception {
        require(player.optionsDialogue != null, "Native options are open");
        Field field = OptionsDialogue.class.getDeclaredField("options");
        field.setAccessible(true);
        return (Option[]) field.get(player.optionsDialogue);
    }

    private static boolean offers(Player player, String prefix) throws Exception {
        for (Option option : options(player)) if (option.name.startsWith(prefix)) return true;
        return false;
    }

    private static void pickPort(Player player, Port destination) throws Exception {
        for (int page = 0; page < 3; page++) {
            if (offers(player, destination.name + " ")) {
                choose(player, destination.name + " ");
                return;
            }
            if (offers(player, "Next")) choose(player, "Next");
        }
        throw new AssertionError("Missing destination " + destination.name);
    }

    private static void choose(Player player, String prefix) throws Exception {
        Option[] options = options(player);
        for (int index = 0; index < options.length; index++) {
            if (!options[index].name.startsWith(prefix)) continue;
            InterfaceHandler.getAction(player, Interface.OPTIONS_DIALOGUE, 1).handleClick(player, 1, index + 1, -1);
            return;
        }
        throw new AssertionError("Missing booking choice " + prefix);
    }

    private static void tick(Player player) throws Exception {
        Method method = Entity.class.getDeclaredMethod("processEvent");
        method.setAccessible(true);
        for (int i = 0; i < 8 && player.isLocked() && !player.getMovement().isTeleportQueued(); i++)
            method.invoke(player);
    }

    private static void finish(Player player, Port destination, int coins) throws Exception {
        require(player.isLocked(), destination.name + " voyage acquired movement lock");
        tick(player);
        require(player.getMovement().finishTeleport(player.getPosition()), destination.name + " voyage moved");
        player.getMovement().finishTeleport(player.getPosition());
        require(player.getPosition().equals(destination.landing), "Correct landing " + destination.name);
        unchanged(player, coins);
    }

    private static void unchanged(Player player, int coins) {
        require(!player.isLocked() && !player.getMovement().isTeleportQueued() && balance(player) == coins,
                "Expected unlocked player, no queued movement and " + coins + " coins");
    }

    private static int balance(Player player) {
        return player.getInventory().getAmount(ItemID.COINS_995);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
