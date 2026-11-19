import com.google.gson.*;
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
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.Varpbit;
import io.ruin.model.World;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.network.incoming.handlers.ObjectActionHandler;
import io.ruin.utility.OfflineMode;

import java.io.Reader;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Detached object-action and runtime movement check on the frozen world cache. */
public final class WorldVerticalTravelChecks {
    public static void main(String[] args) {
        try {
            run(Paths.get(args[0]), Paths.get(args[1]));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(Path baseline, Path fixtureData) throws Exception {
        Thread.currentThread().setName("server-worker #1");
        Server.fileStore = new FileStore(baseline.resolve("Cache").toString(), 8 * 1024 * 1024);
        Server.dataFolder = fixtureData.toFile();
        World.type = WorldType.PVP;
        World.stage = WorldStage.DEV;
        Varpbit.load();
        InterfaceDef.load();
        ObjectDef.load();
        NPCDef.load();
        ItemDef.load();
        AnimDef.load();
        try (Reader reader = Files.newBufferedReader(baseline.resolve("data/items/item_info.json"), StandardCharsets.UTF_8)) {
            for (JsonElement value : new JsonParser().parse(reader).getAsJsonArray()) {
                JsonObject row = value.getAsJsonObject();
                if (!row.has("equip_slot") || row.get("equip_slot").isJsonNull()) continue;
                ItemDef def = ItemDef.get(row.get("id").getAsInt());
                if (def != null) def.equipSlot = row.get("equip_slot").getAsInt();
            }
        }
        Map<Integer, int[]> keys;
        try (Reader reader = Files.newBufferedReader(baseline.resolve("data/region_keys.json"), StandardCharsets.UTF_8)) {
            keys = new Gson().fromJson(reader, new TypeToken<Map<Integer, int[]>>() {}.getType());
        }
        for (int id = 0; id < Region.LOADED.length; id++) {
            Region region = new Region(id);
            region.keys = keys.get(id);
            Region.LOADED[id] = region;
        }
        JsonArray pairs;
        try (Reader reader = Files.newBufferedReader(fixtureData.resolve("world-vertical-travel.json"), StandardCharsets.UTF_8)) {
            pairs = new JsonParser().parse(reader).getAsJsonObject().getAsJsonArray("pairs");
        }
        Set<Integer> regions = new HashSet<>();
        for (JsonElement pair : pairs) {
            addRegion(regions, pair.getAsJsonObject().getAsJsonObject("first").getAsJsonArray("at"));
            addRegion(regions, pair.getAsJsonObject().getAsJsonObject("second").getAsJsonArray("at"));
        }
        for (int[] anchor : new int[][]{{3097,9867},{3088,9971},{3088,3571},{2715,3472},{2213,3795},
                {2127,3893},{2118,3894},{2572,3325},{2021,3566},{3154,3743},
                {3084,3272},{3084,9672},{3118,3244},{3118,9643}}) {
            regions.add(Region.getId(anchor[0], anchor[1]));
        }
        for (int[] center : new int[][]{{3156,3620},{3227,3667},{3035,3732},{3097,3850},{3307,3916},{3320,3887},
                {3106,3794},{2980,3731},{3307,3916},{2980,3866},{3391,3882}}) {
            for (int x : new int[]{center[0]-2,center[0]+2}) for (int y : new int[]{center[1]-2,center[1]+2})
                regions.add(Region.getId(x,y));
        }
        for (int region : regions) Region.get(region).init();
        Class.forName("io.ruin.model.map.object.actions.impl.Ladder");
        MapListener.LISTENERS = new MapListener[0];
        OfflineMode.enabled = true;
        OfflineMode.setPath();

        JsonObject reserved = pairs.get(pairs.size() - 1).getAsJsonObject();
        JsonObject reservedFirst = reserved.getAsJsonObject("first");
        JsonObject reservedSecond = reserved.getAsJsonObject("second");
        ObjectAction existing = (player, object) -> {};
        ObjectAction.register(object(reservedFirst), slot(reservedFirst), existing);
        Class<?> vertical = Class.forName("io.ruin.content.content242.WorldVerticalTravel");
        Method initialize = vertical.getDeclaredMethod("initialize");
        initialize.setAccessible(true);
        initialize.invoke(null);
        require(callback(reservedFirst) == existing && callback(reservedSecond) == null,
                "An existing position-specific action preserves itself and prevents an unsafe half-link");

        for (int i = 0; i < pairs.size() - 1; i++) {
            JsonObject pair = pairs.get(i).getAsJsonObject();
            require(callback(pair.getAsJsonObject("first")) != null && callback(pair.getAsJsonObject("second")) != null,
                    "Both installed object actions are bound for pair " + i);
        }

        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
        };
        try {
            LoginInfo login = new LoginInfo(channel, "worldfixture", "", "", "", "", 0, false, 0, 1, new int[4]);
            login.update(1001, "worldfixture", null, Collections.singletonList(PlayerGroup.REGISTERED.id), 0);
            Player player = PlayerFile.decode("{\"position\":{\"x\":3205,\"y\":3228,\"z\":0}}", Player.class);
            player.init(login);
            Config.load(player);
            player.setOnline(true);
            verifyRoundTrip(player, find(pairs, 16671, 3204, 3229, 0), "Lumbridge castle stairs");
            verifyRoundTrip(player, find(pairs, 24084, 3027, 3353, 1), "Falador ladder with different XY landings");
            verifyRoundTrip(player, find(pairs, 14880, 3209, 3216, 0), "Lumbridge cellar trapdoor");
            verifyRoundTrip(player, find(pairs, 2148, 3103, 9576, 0), "Wizards' Tower basement ladder");
            verifySewer(player, 3084, 3272, 17385, 9672, Position.of(3085, 3272, 0), Position.of(3085, 9672, 0));
            verifySewer(player, 3118, 3244, 6436, 9643, Position.of(3118, 3243, 0), Position.of(3118, 9644, 0));
        } finally {
            channel.finishAndReleaseAll();
        }
        System.out.println("PASS " + (pairs.size() - 1) + " reciprocal bindings, existing action priority, six native round trips, and both timed sewer hatches.");
    }

    private static void verifySewer(Player player, int x, int y, int ladder, int ladderY,
                                    Position surface, Position sewer) throws Exception {
        player.getPosition().set(surface.getX(), surface.getY(), surface.getZ());
        player.getMovement().reset();
        GameObject hatch = Tile.getObject(6434, x, y, 0);
        require(hatch != null && hatch.actions != null, "Draynor sewer hatch is registered while closed");
        ObjectActionHandler.handleAction(player, 1, 6434, x, y, 0);
        require(hatch.id == 6435 && player.getPosition().equals(surface) && !player.getMovement().isTeleportQueued(),
                "Draynor hatch opens from an adjacent tile without moving the player");
        ObjectActionHandler.handleAction(player, 1, 6435, x, y, 0);
        finish(player);
        require(player.getPosition().equals(sewer), "Draynor hatch leads to the correct sewer entrance");
        for (int i = 0; i < 302; i++) World.process();
        require(hatch.id == 6434, "Draynor hatch closes through the native event queue");
        ObjectActionHandler.handleAction(player, 1, ladder, x, ladderY, 0);
        finish(player);
        require(player.getPosition().equals(surface), "Draynor ladder returns to the correct hatch even after closure");
    }

    private static void verifyRoundTrip(Player player, JsonObject pair, String title) throws Exception {
        JsonObject landing = pair.getAsJsonArray("landings").get(0).getAsJsonObject();
        Position first = point(landing.getAsJsonArray("first")), second = point(landing.getAsJsonArray("second"));
        JsonObject firstObject = pair.getAsJsonObject("first"), secondObject = pair.getAsJsonObject("second");
        player.getPosition().set(first.getX(), first.getY(), first.getZ());
        player.getMovement().reset();
        click(player, firstObject);
        finish(player);
        require(player.getPosition().equals(second), title + " outward landing");
        click(player, secondObject);
        finish(player);
        require(player.getPosition().equals(first), title + " return landing");
        Tile blocked = Tile.get(second.getX(), second.getY(), second.getZ(), true);
        int clipping = blocked.clipping;
        blocked.flagUnmovable();
        try {
            click(player, firstObject);
            require(!player.getMovement().isTeleportQueued() && !player.isLocked(), title + " rejects a clipped landing");
        } finally {
            blocked.clipping = clipping;
        }
    }

    private static void click(Player player, JsonObject endpoint) {
        Position at = point(endpoint.getAsJsonArray("at"));
        ObjectActionHandler.handleAction(player, slot(endpoint), endpoint.get("id").getAsInt(), at.getX(), at.getY(), 0);
    }

    private static void finish(Player player) throws Exception {
        Method tick = Entity.class.getDeclaredMethod("processEvent");
        tick.setAccessible(true);
        for (int i = 0; i < 12 && !player.getMovement().isTeleportQueued(); i++) tick.invoke(player);
        require(player.getMovement().finishTeleport(player.getPosition()), "Object interaction did not queue native movement");
        player.getMovement().finishTeleport(player.getPosition());
        require(!player.isLocked(), "Traversal retained its movement lock");
    }

    private static JsonObject find(JsonArray pairs, int id, int x, int y, int z) {
        for (JsonElement item : pairs) {
            JsonObject pair = item.getAsJsonObject();
            for (String key : new String[]{"first", "second"}) {
                JsonObject endpoint = pair.getAsJsonObject(key);
                if (endpoint.get("id").getAsInt() == id && point(endpoint.getAsJsonArray("at")).equals(x, y, z))
                    return pair;
            }
        }
        throw new AssertionError("Unknown test endpoint " + id);
    }

    private static void addRegion(Set<Integer> regions, JsonArray at) {
        Position p = point(at);
        regions.add(Region.getId(p.getX(), p.getY()));
    }

    private static Position point(JsonArray at) {
        return Position.of(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt());
    }

    private static GameObject object(JsonObject row) {
        Position p = point(row.getAsJsonArray("at"));
        return Tile.getObject(row.get("id").getAsInt(), p.getX(), p.getY(), p.getZ(),
                row.get("type").getAsInt(), row.get("rotation").getAsInt());
    }

    private static int slot(JsonObject row) { return row.get("slot").getAsInt(); }

    private static ObjectAction callback(JsonObject row) {
        GameObject object = object(row);
        return object == null || object.actions == null ? null : object.actions[slot(row) - 1];
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
