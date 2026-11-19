import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.ObjectDef;
import io.ruin.model.World;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/** Checks the actual native archive reader and scenery/collision builder against staged keys. */
public final class World242LandscapeChecks {
    public static void main(String[] args) {
        try {
            Path stage = Paths.get(args[0]);
            Server.dataFolder = stage.resolve("data").toFile();
            Server.fileStore = new FileStore(stage.resolve("Cache").toString(), 8 * 1024 * 1024);
            World.type = WorldType.PVP;
            ObjectDef.load();
            Gson gson = new Gson();
            Map<Integer, int[]> keys;
            try (Reader reader = Files.newBufferedReader(stage.resolve("data/region_keys.json"), StandardCharsets.UTF_8)) {
                keys = gson.fromJson(reader, new TypeToken<Map<Integer, int[]>>() {}.getType());
            }
            JsonObject report;
            try (Reader reader = Files.newBufferedReader(Paths.get(args[1]), StandardCharsets.UTF_8)) {
                report = gson.fromJson(reader, JsonObject.class);
            }
            for (int i = 0; i < Region.LOADED.length; i++) {
                Region region = new Region(i);
                region.keys = keys.get(i);
                Region.LOADED[i] = region;
            }
            int tested = 0;
            long placements = 0;
            for (JsonElement result : report.getAsJsonArray("repairs")) {
                JsonObject repair = result.getAsJsonObject();
                int square = repair.get("mapSquare").getAsInt();
                Region region = Region.get(square);
                int[] expected = gson.fromJson(repair.get("keys"), int[].class);
                check(Arrays.equals(expected, region.keys), "Wrong staged key for " + square);
                byte[] scenery = region.getLandscapeData();
                check(scenery != null && hash(scenery).equals(repair.get("decodedSha256").getAsString()),
                      "Java did not decode the verified scenery for " + square);
                region.init();
                int nativeObjects = countObjects(region);
                int decodedObjects = repair.get("placements").getAsInt();
                check(!region.empty && nativeObjects > 0 && nativeObjects <= decodedObjects,
                      "Native placement failed for " + square);
                System.out.println(square + " decoded " + decodedObjects + " scenery records, placed " + nativeObjects);
                tested++;
                placements += nativeObjects;
            }
            check(tested > 0, "Missing repair checks");
            System.out.println("PASS " + tested + " native region builds with " + placements + " placements");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static int countObjects(Region region) {
        int count = 0;
        for (int plane = 0; plane < 4; plane++) {
            for (int x = region.baseX; x < region.baseX + 64; x++) {
                for (int y = region.baseY; y < region.baseY + 64; y++) {
                    Tile tile = region.getTile(x, y, plane, false);
                    if (tile != null && tile.gameObjects != null) count += tile.gameObjects.size();
                }
            }
        }
        return count;
    }

    private static String hash(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder encoded = new StringBuilder();
        for (byte b : digest) encoded.append(String.format("%02x", b & 255));
        return encoded.toString();
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
