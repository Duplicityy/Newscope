package io.ruin.model.map.route;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.netty.channel.embedded.EmbeddedChannel;
import io.ruin.Server;
import io.ruin.api.buffer.InBuffer;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.process.ProcessWorker;
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
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.map.ClipUtils;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.object.actions.impl.Door;
import io.ruin.model.map.route.routes.DumbRoute;
import io.ruin.network.incoming.Incoming;
import io.ruin.network.incoming.handlers.WalkHandler;
import io.ruin.utility.OfflineMode;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class MapWalkChecks {
    private static int checks;

    public static void main(String[] args) {
        try {
            pureChecks();
            if (args.length != 0) nativeChecks(Paths.get(args[0]));
            System.out.println("PASS " + checks + " world/minimap movement assertions");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void require(boolean expected, String message) {
        if (!expected) throw new AssertionError(message);
        checks++;
    }

    private static void pureChecks() {
        // The only gap in this wall is 90 tiles from both origin and destination.
        ClipUtils aroundWall = new ClipUtils() {
            @Override public int getAbs(int x, int y, int z) {
                return x < 100 || x > 360 || y < 900 || y > 1040 || x == 200 && y < 1040
                        ? RouteFinder.UNMOVABLE_MASK : 0;
            }
        };
        MapWalkPath.Search forward = new MapWalkPath.Search(aroundWall, 0, 1, 160, 950, 320, 950, null);
        MapWalkPath.Path longPath = finish(forward);
        require(longPath != null && longPath.tiles.length > 180 && !longPath.stopsAtDoor, "long path around the wall");
        verifySteps(aroundWall, longPath, 160, 950, 320, 950);
        MapWalkPath.Path reverse = finish(new MapWalkPath.Search(aroundWall, 0, 1, 320, 950, 160, 950, null, true));
        require(reverse != null && reverse.tiles.length == longPath.tiles.length, "reverse shortest route length");
        verifySteps(aroundWall, reverse, 160, 950, 320, 950);

        ClipUtils gate = new ClipUtils() {
            @Override public int getAbs(int x, int y, int z) {
                if (y != 950 || x < 160 || x > 220) return RouteFinder.UNMOVABLE_MASK;
                if (x == 174 || x == 185) return 8;
                if (x == 175 || x == 186) return 128;
                return 0;
            }
        };
        MapWalkPath.DoorEdges registered = (x, y, nx, ny) -> x == 174 && nx == 175 || x == 185 && nx == 186;
        MapWalkPath.Path approach = finish(new MapWalkPath.Search(gate, 0, 1, 160, 950, 220, 950, registered));
        require(approach != null && approach.stopsAtDoor && approach.tiles.length == 14, "first gate stops the route");
        verifySteps(gate, approach, 160, 950, 174, 950);
        require(finish(new MapWalkPath.Search(gate, 0, 1, 160, 950, 220, 950, null)) == null,
                "strict search does not walk to an approximation");
        require(finish(new MapWalkPath.Search(gate, 0, 1, 220, 950, 160, 950, null, true)) == null,
                "reverse proof detects disconnected goal side");
        require(finish(new MapWalkPath.Search(gate, 0, 1, 160, 950, 220, 950,
                (x, y, nx, ny) -> x == 174 && nx == 175)) == null, "a gate alone does not prove the rest of the route");
        require(!new MapWalkPath.Search(gate, 0, 1, 160, 950, 200, 950, null).advance(0),
                "zero budget defers work");
        require(!MapWalkRoute.validCoordinates(-1, 950) && !MapWalkRoute.validCoordinates(8192, 950)
                && !MapWalkRoute.validCoordinates(160, 16384), "world array bounds");
    }

    private static MapWalkPath.Path finish(MapWalkPath.Search search) {
        for (int i = 0; i < 2000; i++) {
            if (search.advance(57)) return search.result();
            require(search.processed == 57, "incremental node limit");
        }
        throw new AssertionError("bounded test did not finish");
    }

    private static void verifySteps(ClipUtils clipping, MapWalkPath.Path path, int x, int y, int targetX, int targetY) {
        for (int packed : path.tiles) {
            int nx = MapWalkPath.x(packed), ny = MapWalkPath.y(packed);
            require(Math.max(Math.abs(x - nx), Math.abs(y - ny)) == 1, "adjacent path");
            require(DumbRoute.getDirection(clipping, x, y, 0, 1, nx, ny) != null, "native clipping of each path tile");
            x = nx;
            y = ny;
        }
        require(x == targetX && y == targetY, "exact destination");
    }

    private static void nativeChecks(Path fixture) throws Exception {
        Thread.currentThread().setName("server-worker #1");
        Server.dataFolder = fixture.resolve("data").toFile();
        Server.fileStore = new FileStore(fixture.resolve("Cache").toString(), 8 * 1024 * 1024);
        World.type = WorldType.PVP;
        World.stage = WorldStage.DEV;
        Varpbit.load();
        InterfaceDef.load();
        ObjectDef.load();
        NPCDef.load();
        ItemDef.load();
        AnimDef.load();
        try (Reader file = Files.newBufferedReader(fixture.resolve("data/items/item_info.json"), StandardCharsets.UTF_8)) {
            for (JsonElement row : new Gson().fromJson(file, JsonArray.class)) {
                JsonObject item = row.getAsJsonObject();
                if (item.has("equip_slot") && !item.get("equip_slot").isJsonNull())
                    ItemDef.get(item.get("id").getAsInt()).equipSlot = item.get("equip_slot").getAsInt();
            }
        }
        Map<Integer, int[]> keys;
        try (Reader file = Files.newBufferedReader(fixture.resolve("data/region_keys.json"), StandardCharsets.UTF_8)) {
            keys = new Gson().fromJson(file, new TypeToken<Map<Integer, int[]>>() {}.getType());
        }
        for (int i = 0; i < Region.LOADED.length; i++) {
            Region region = new Region(i);
            region.keys = keys.get(i);
            region.empty = true;
            Region.LOADED[i] = region;
        }
        // Cache objects used by the native door override and by wilderness setup.
        Set<Integer> nativeRegions = new HashSet<>();
        for (int[] point : new int[][]{{2319, 3690}, {3156, 3620}, {3227, 3667}, {3035, 3732},
                {3106, 3794}, {2980, 3866}, {3307, 3916}}) {
            for (int dx : new int[]{-2, 2}) for (int dy : new int[]{-2, 2})
                nativeRegions.add(Region.getId(point[0] + dx, point[1] + dy));
        }
        for (int region : nativeRegions) Region.get(region).init();
        Class.forName(Door.class.getName());
        Class.forName(io.ruin.model.inter.handlers.WorldMap.class.getName());
        MapListener.LISTENERS = new MapListener[0];
        OfflineMode.enabled = true;
        OfflineMode.setPath();
        Incoming.load();
        require(Incoming.SIZES[69] == 4 && Incoming.SIZES[91] == -1, "native exact packet lengths");
        require(Incoming.HANDLERS[69] instanceof WalkHandler && Incoming.HANDLERS[91] instanceof WalkHandler
                && Incoming.HANDLERS[98] instanceof WalkHandler, "exact runtime handler registrations");

        int startX = 4050, targetX = 4230, y = 4050, doorX = 4100;
        for (int x = startX - 1; x <= targetX + 1; x++) {
            Region.get(x, y).empty = false;
            Tile.get(x, y - 1, 0, true).flagUnmovable();
            Tile.get(x, y + 1, 0, true).flagUnmovable();
        }
        Tile.get(startX - 1, y, 0, true).flagUnmovable();
        Tile.get(targetX + 1, y, 0, true).flagUnmovable();
        int[] doorId = {-1};
        ObjectDef.forEach(def -> {
            if (doorId[0] == -1 && Door.hasStandardOpening(new GameObject(def.id, doorX, y, 0, 0, 0)))
                doorId[0] = def.id;
        });
        require(doorId[0] != -1, "installed standard closed door");
        GameObject door = new GameObject(doorId[0], doorX, y, 0, 0, 0);
        Tile.get(doorX, y, 0, true).addObject(door);
        int openOption = door.getDef().getOption("open");

        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override protected SocketAddress remoteAddress0() {
                return new InetSocketAddress("127.0.0.1", 12345);
            }
        };
        LoginInfo login = new LoginInfo(channel, "worldfixture", "", "", "", "", 0, false, 0, 1, new int[4]);
        login.update(1001, "worldfixture", null, Collections.singletonList(PlayerGroup.REGISTERED.id), 0);
        Player player = PlayerFile.decode("{\"position\":{\"x\":4050,\"y\":4050,\"z\":0}}", Player.class);
        player.init(login);
        Config.load(player);
        player.setOnline(true);
        try {
            worldClick(player, targetX, y, 0);
            require(!player.hasActiveAction(), "expanded map packet outside its UI ignored");
            player.openInterface(InterfaceType.WORLD_MAP, Interface.WORLD_MAP);
            worldClick(player, targetX, y, 1);
            require(!player.hasActiveAction(), "expanded map rejects wrong plane");
            ObjectAction.register(door, openOption, (p, obj) -> {});
            minimapClick(player, targetX, y);
            pulse(player, 22);
            require(player.isAt(startX, y), "permission-specific door does not trigger approach");
            door.actions = null;
            minimapClick(player, targetX, y);
            pulse(player, 260);
            require(player.isAt(doorX - 1, y), "native minimap route stops on reachable door side");
            require(door.id == doorId[0] && Door.hasStandardOpening(door), "planner never opens or changes door");
            Tile.get(doorX, y, 0, false).removeObject(door);

            player.openInterface(InterfaceType.WORLD_MAP, Interface.WORLD_MAP);
            worldClick(player, targetX, y, 0);
            pulse(player, 360);
            require(player.isAt(targetX, y), "expanded map walks exact target across regions and queue segments");

            minimapClick(player, startX, y);
            pulse(player, 6);
            int interruptedX = player.getAbsX();
            require(interruptedX < targetX, "new native route begins");
            player.resetActions(true, true, true);
            pulse(player, 40);
            require(player.isAt(interruptedX, y), "ordinary manual reset cancels pending segments");

            minimapClick(player, startX, y);
            pulse(player, 5);
            int obstructedX = player.getAbsX();
            Tile.get(obstructedX - 1, y, 0, true).flagUnmovable();
            pulse(player, 40);
            require(player.isAt(obstructedX, y), "a new obstacle is revalidated and stops movement");

            // Near this gate the open U-shaped path is longer than the weighted
            // gate-assisted candidate. The native action must prefer it anyway.
            int laneY = 4100;
            for (int x = 4488; x <= 4512; x++) {
                for (int ny = laneY - 2; ny <= laneY + 37; ny++) {
                    Region.get(x, ny).empty = false;
                    Tile.get(x, ny, 0, true).flagUnmovable();
                }
            }
            for (int x = 4490; x <= 4510; x++) Tile.get(x, laneY, 0, false).unflagUnmovable();
            for (int ny = laneY; ny <= laneY + 35; ny++) {
                Tile.get(4495, ny, 0, false).unflagUnmovable();
                Tile.get(4505, ny, 0, false).unflagUnmovable();
            }
            for (int x = 4495; x <= 4505; x++) Tile.get(x, laneY + 35, 0, false).unflagUnmovable();
            GameObject detourGate = new GameObject(doorId[0], 4500, laneY, 0, 0, 0);
            Tile.get(4500, laneY, 0, false).addObject(detourGate);
            player.getMovement().teleport(4490, laneY, 0);
            pulse(player, 4);
            require(player.isAt(4490, laneY), "new fixture lane");
            minimapClick(player, 4510, laneY);
            pulse(player, 240);
            require(player.isAt(4510, laneY) && Door.hasStandardOpening(detourGate),
                    "long walkable detour wins over closed gate shortcut");

            minimapClick(player, 4490, laneY);
            pulse(player, 5);
            player.getMovement().teleport(startX, y, 0);
            pulse(player, 35);
            require(player.isAt(startX, y), "teleport cancels the remaining route");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void minimapClick(Player player, int x, int y) {
        byte[] bytes = new byte[18];
        bytes[0] = (byte) (x + 128);
        bytes[1] = (byte) (x >>> 8);
        bytes[2] = (byte) y;
        bytes[3] = (byte) (y >>> 8);
        Incoming.HANDLERS[91].handle(player, new InBuffer(bytes), 91);
    }

    private static void worldClick(Player player, int x, int y, int plane) {
        int packed = plane << 28 | x << 14 | y;
        byte[] bytes = ByteBuffer.allocate(4).putInt(packed).array();
        Incoming.HANDLERS[69].handle(player, new InBuffer(bytes), 69);
    }

    private static void pulse(Player player, int ticks) throws Exception {
        Method entityEvent = Entity.class.getDeclaredMethod("processEvent");
        entityEvent.setAccessible(true);
        Field executions = ProcessWorker.class.getDeclaredField("executions");
        executions.setAccessible(true);
        for (int i = 0; i < ticks; i++) {
            entityEvent.invoke(player);
            player.getMovement().process();
            executions.setLong(Server.worker, Server.currentTick() + 1);
        }
    }
}
