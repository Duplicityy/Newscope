import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.InterfaceDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.Varpbit;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.object.actions.impl.Door;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Exercises the native door state changes on the installed landscape. */
public final class WorldDoorChecks {
    private static final int[] WALL = {128, 2, 8, 32};
    private static final int[] ACROSS_X = {-1, 0, 1, 0};
    private static final int[] ACROSS_Y = {0, 1, 0, -1};

    public static void main(String[] args) {
        try {
            run(Paths.get(args[0]));
            System.out.println("PASS native Rellekka, Lunar galleon, farming shed and longhall doors, collision restoration, and protected neighboring actions.");
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(Path release) throws Exception {
        World.type = WorldType.PVP;
        Server.fileStore = new FileStore(release.resolve("Cache").toString());
        InterfaceDef.load();
        Varpbit.load();
        ItemDef.load();
        NPCDef.load();
        ObjectDef.load();
        Map<Integer, int[]> keys;
        try (Reader file = Files.newBufferedReader(release.resolve("data/region_keys.json"), StandardCharsets.UTF_8)) {
            keys = new Gson().fromJson(file, new TypeToken<Map<Integer, int[]>>() {}.getType());
        }
        for (int i = 0; i < Region.LOADED.length; i++) {
            Region region = new Region(i);
            region.keys = keys.get(i);
            Region.LOADED[i] = region;
        }
        Set<Integer> loaded = new HashSet<>();
        for (int[] tile : new int[][]{{2319,3690},{2627,3656},{2224,3790},{3147,3172},{1284,3758},{3078,3435}}) {
            int regionId = Region.getId(tile[0], tile[1]);
            if (loaded.add(regionId)) Region.get(regionId).init();
        }
        Class.forName(Door.class.getName());
        MapListener.LISTENERS = new MapListener[0];
        Player player = new Player() {
            @Override public void privateSound(int id) {}
            @Override public void sendMessage(String message) { throw new AssertionError(message); }
        };
        verifySingle(player, 4250, 4251, 2627, 3656, 0);
        verifySingle(player, 16902, 16903, 2224, 3790, 2);
        verifySingle(player, 15056, 11617, 3147, 3172, 0);
        verifySingle(player, 15056, 11617, 1284, 3758, 0);
        require(ObjectDef.get(11617).doorOppositeId == 11616, "Shared open model preserves its original pairing");

        GameObject west = placement(11621, 3078, 3435, 0);
        GameObject east = placement(11620, 3079, 3435, 0);
        Door.handle(player, east, true);
        GameObject openEast = replacement(east, 11624);
        replacement(west, 11625);
        require(!edgeBlocked(west) && !edgeBlocked(east), "Both longhall edges opened");
        Door.handle(player, openEast, true);
        require(west.id == 11621 && east.id == 11620 && edgeBlocked(west) && edgeBlocked(east),
                "Longhall closes from either leaf");

        ObjectAction.register(east, "Open", (p, object) -> {});
        Door.handle(player, west, true);
        GameObject openWest = replacement(west, 11625);
        require(east.id == 11620 && edgeBlocked(east), "A generic door does not open a protected adjacent leaf");
        Door.handle(player, openWest, true);
        require(west.id == 11621 && edgeBlocked(west), "Ordinary door still closes beside the protected leaf");
    }

    private static void verifySingle(Player player, int id, int openId, int x, int y, int z) {
        GameObject original = placement(id, x, y, z);
        require(edgeBlocked(original), "Original doorway clipped " + id + " at " + x + "," + y);
        Door.handle(player, original, true);
        GameObject opened = replacement(original, openId);
        require(!edgeBlocked(original), "Opening unclips the original doorway " + id + " at " + x + "," + y);
        Door.handle(player, opened, true);
        require(original.id == id && Tile.getObject(id, x, y, z) == original && edgeBlocked(original),
                "Close restores the same original object and clipping");
    }

    private static GameObject placement(int id, int x, int y, int z) {
        GameObject object = Tile.getObject(id, x, y, z);
        require(object != null && Door.hasStandardOpening(object), "Installed standard Open action " + id);
        return object;
    }

    private static boolean edgeBlocked(GameObject original) {
        int direction = original.direction;
        Tile a = Tile.get(original.x, original.y, original.z);
        Tile b = Tile.get(original.x + ACROSS_X[direction], original.y + ACROSS_Y[direction], original.z);
        return (a != null && (a.clipping & WALL[direction]) != 0)
                || (b != null && (b.clipping & WALL[(direction + 2) & 3]) != 0);
    }

    private static GameObject replacement(GameObject original, int id) {
        for (int x = original.x - 2; x <= original.x + 2; x++) for (int y = original.y - 2; y <= original.y + 2; y++) {
            Tile tile = Tile.get(x, y, original.z);
            if (tile == null || tile.gameObjects == null) continue;
            for (GameObject object : tile.gameObjects) {
                if (object.id == id && object.doorReplaced == original) return object;
            }
        }
        throw new AssertionError("No open model " + id + " for native door " + original.originalId);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
