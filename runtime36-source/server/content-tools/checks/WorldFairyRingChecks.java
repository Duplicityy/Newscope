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
import io.ruin.model.World;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceAction;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.impl.FairyRing;
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

/** Detached checks against the actual installed maps, portal objects and interface actions. */
public final class WorldFairyRingChecks {
    private static final Position MODERN_RING = Position.of(3284, 2706, 0);

    public static void main(String[] args) {
        try {
            Thread.currentThread().setName("server-worker #1");
            run(Paths.get(args[0]));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(Path release) throws Exception {
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
        try (Reader itemInfo = Files.newBufferedReader(release.resolve("data/items/item_info.json"), StandardCharsets.UTF_8)) {
            for (JsonElement row : new Gson().fromJson(itemInfo, JsonArray.class)) {
                JsonObject item = row.getAsJsonObject();
                if (item.has("equip_slot") && !item.get("equip_slot").isJsonNull())
                    ItemDef.get(item.get("id").getAsInt()).equipSlot = item.get("equip_slot").getAsInt();
            }
        }

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
        for (FairyRing entry : FairyRing.values())
            if (entry.position.getX() > 0) load(loaded, entry.position.getX(), entry.position.getY());
        load(loaded, MODERN_RING.getX(), MODERN_RING.getY());
        for (int[] center : new int[][]{{3156, 3620}, {3227, 3667}, {3035, 3732},
                {3106, 3794}, {2980, 3866}, {3307, 3916}})
            load(loaded, center[0], center[1]);
        MapListener.LISTENERS = new MapListener[0];
        OfflineMode.enabled = true;
        OfflineMode.setPath();

        Method landing = FairyRing.class.getDeclaredMethod("landing");
        landing.setAccessible(true);
        int available = 0;
        for (FairyRing entry : FairyRing.values()) {
            Position tile = (Position) landing.invoke(entry);
            if (entry == FairyRing.FISHER_KING || entry == FairyRing.ARCEUUS_LIBRARY || entry == FairyRing.PLAYER_OWNED_HOUSE) {
                check(tile == null, "Unavailable or instanced route must not expose a static destination: " + entry);
                continue;
            }
            check(tile != null, "Expected safe mapped landing for " + entry);
            check(!tile.equals(entry.position) && tile.isWithinDistance(entry.position, 1), "Land next to the return ring for " + entry);
            check(Tile.get(tile) == null || Tile.get(tile).isFloorFreeCheckDecor(), "Clear floor at " + entry);
            int id = entry == FairyRing.ZANARIS ? 29560 : 29495;
            check(Tile.getObject(id, entry.position.getX(), entry.position.getY(), entry.position.getZ()) != null,
                    "A real return ring exists at " + entry);
            available++;
        }
        check(available == 42, "Expected 42 loaded static destinations, got " + available);

        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
        };
        try {
            LoginInfo login = new LoginInfo(channel, "worldfixture", "", "", "", "", 0, false, 0, 1, new int[4]);
            login.update(1001, "worldfixture", null, Collections.singletonList(PlayerGroup.REGISTERED.id), 0);
            Player player = PlayerFile.decode("{}", Player.class);
            player.init(login);
            Config.load(player);
            player.setOnline(true);
            GameObject modern = ring(39320, MODERN_RING);
            player.getPosition().set(MODERN_RING.getX(), MODERN_RING.getY() + 1, 0);
            modern.getDef().defaultActions[1].handle(player, modern);
            check(!player.isVisibleInterface(Interface.FAIRY_RING_COMBINATION), "A fairy staff is required");

            player.getEquipment().set(Equipment.SLOT_WEAPON, new Item(ItemID.LUNAR_STAFF));
            modern.getDef().defaultActions[1].handle(player, modern);
            check(player.isVisibleInterface(Interface.FAIRY_RING_COMBINATION), "Modern Configure opens with a lunar staff");
            dial(player, FairyRing.SINCLAIR_WEST);
            player.wildernessLevel = 1;
            clickTeleport(player);
            check(Config.FAIRY_RING_LAST_DESTINATION.get(player) == 0
                    && player.unlockedFairyRingTeleports[FairyRing.SINCLAIR_WEST.ordinal()] == null,
                    "Blocked travel does not write discovery or last destination");
            player.wildernessLevel = 0;
            clickTeleport(player);
            finishTeleport(player);
            check(player.getPosition().equals((Position) landing.invoke(FairyRing.SINCLAIR_WEST)), "DJR reaches the Chasm of Fire");
            check(player.unlockedFairyRingTeleports[FairyRing.SINCLAIR_WEST.ordinal()] == FairyRing.SINCLAIR_WEST,
                    "Successful arrival writes the discovery");
            check(Config.FAIRY_RING_LAST_DESTINATION.get(player) == FairyRing.SINCLAIR_WEST.lastDestination,
                    "Successful arrival records last destination");

            GameObject chasm = ring(29495, FairyRing.SINCLAIR_WEST.position);
            chasm.getDef().defaultActions[0].handle(player, chasm);
            finishTeleport(player);
            check(player.getPosition().equals((Position) landing.invoke(FairyRing.ZANARIS)), "The actual Chasm ring returns to Zanaris");

            player.getEquipment().set(Equipment.SLOT_WEAPON, new Item(ItemID.DRAMEN_STAFF));
            GameObject zanaris = ring(29560, FairyRing.ZANARIS.position);
            zanaris.getDef().defaultActions[1].handle(player, zanaris);
            check(player.isVisibleInterface(Interface.FAIRY_RING_COMBINATION), "Legacy Configure opens with a dramen staff");
            dial(player, FairyRing.YANILLE);
            clickTeleport(player);
            finishTeleport(player);
            check(player.getPosition().equals((Position) landing.invoke(FairyRing.YANILLE)), "CIQ lands at the verified Yanille ring");

            GameObject yanille = ring(29495, FairyRing.YANILLE.position);
            yanille.getDef().defaultActions[1].handle(player, yanille);
            player.closeInterface(InterfaceType.MAIN);
            check(player.fairyRing == null, "Closing the panel invalidates its source");

            player.getPosition().set(MODERN_RING.getX(), MODERN_RING.getY() + 1, 0);
            modern.getDef().defaultActions[2].handle(player, modern);
            finishTeleport(player);
            check(player.getPosition().equals((Position) landing.invoke(FairyRing.YANILLE)),
                    "Modern Last-destination repeats the recorded route");

            Method snapshot = PlayerFile.class.getDeclaredMethod("snapshot", Player.class);
            snapshot.setAccessible(true);
            Player reloaded = PlayerFile.decode((String) snapshot.invoke(null, player), Player.class);
            check(reloaded.unlockedFairyRingTeleports[FairyRing.SINCLAIR_WEST.ordinal()] == FairyRing.SINCLAIR_WEST
                    && reloaded.unlockedFairyRingTeleports[FairyRing.YANILLE.ordinal()] == FairyRing.YANILLE,
                    "Native save decoding preserves both discoveries and the legacy enum key");
            check(Integer.valueOf(FairyRing.YANILLE.lastDestination).equals(reloaded.savedConfigs.get(5374 << 16 | 1)),
                    "Native save includes the last destination varbit");

            player.getPosition().set(MODERN_RING.getX(), MODERN_RING.getY() + 1, 0);
            modern.getDef().defaultActions[0].handle(player, modern);
            finishTeleport(player);
            check(player.getPosition().equals((Position) landing.invoke(FairyRing.ZANARIS)),
                    "Modern Zanaris action returns to the hub");
        } finally {
            channel.finishAndReleaseAll();
        }
        System.out.println("PASS " + available + " real static fairy-ring landings, both staves, modern controls, blocked discovery, round trips and save round trip");
    }

    private static void load(Set<Integer> loaded, int x, int y) {
        for (int dx : new int[]{-3, 3}) for (int dy : new int[]{-3, 3}) {
            Region region = Region.get(x + dx, y + dy);
            if (loaded.add(region.id)) region.init();
        }
    }

    private static GameObject ring(int id, Position position) {
        GameObject ring = Tile.getObject(id, position.getX(), position.getY(), position.getZ());
        check(ring != null, "Loaded ring object " + id + " at " + position);
        return ring;
    }

    private static void dial(Player player, FairyRing entry) {
        Config.FAIRY_RING_LEFT.set(player, entry.varpbitValues[0]);
        Config.FAIRY_RING_MIDDLE.set(player, entry.varpbitValues[1]);
        Config.FAIRY_RING_RIGHT.set(player, entry.varpbitValues[2]);
    }

    private static void clickTeleport(Player player) {
        InterfaceAction action = InterfaceHandler.getAction(player, Interface.FAIRY_RING_COMBINATION, 26);
        check(action != null, "Native combination plate is open");
        action.handleClick(player, 1, 0, 0);
    }

    private static void finishTeleport(Player player) throws Exception {
        Method tick = Entity.class.getDeclaredMethod("processEvent");
        tick.setAccessible(true);
        for (int attempt = 0; attempt < 12; attempt++) {
            tick.invoke(player);
            if (player.getMovement().isTeleportQueued()) {
                check(player.getMovement().finishTeleport(player.getPosition()), "Expected a native movement update");
                player.getMovement().finishTeleport(player.getPosition());
            }
            if (!player.isLocked() && attempt > 3) return;
        }
        throw new AssertionError("The normal teleport lock did not clear");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
