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
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.network.incoming.handlers.ObjectActionHandler;

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

/** Native interactions and movement on the installed docks using a detached player. */
public final class PassengerBoatChecks {
    public static void main(String[] args) {
        try {
            Thread.currentThread().setName("server-worker #1");
            checkRoutes(Paths.get(args[0]));
            System.out.println("PASS both passenger round trips, fares, discounts, customs, restricted departures and four local gangplanks");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void checkRoutes(Path release) throws Exception {
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
        try (Reader file = Files.newBufferedReader(release.resolve("data/items/item_info.json"), StandardCharsets.UTF_8)) {
            for (JsonElement row : new Gson().fromJson(file, JsonArray.class)) {
                JsonObject item = row.getAsJsonObject();
                if (item.has("equip_slot") && !item.get("equip_slot").isJsonNull()) {
                    ItemDef.get(item.get("id").getAsInt()).equipSlot = item.get("equip_slot").getAsInt();
                }
            }
        }
        check(ItemDef.get(ItemID.KARAMJAN_RUM).name.equalsIgnoreCase("Karamjan rum"), "Customs item ID");
        loadDocks(release);
        MapListener.LISTENERS = new MapListener[0];
        Class.forName("io.ruin.model.entity.npc.actions.traveling.PassengerBoats");
        NPC captain = spawnCrew(release, "port_sarim.json", 3644);
        NPC lorris = spawnCrew(release, "port_sarim.json", 3645);
        NPC thresnor = spawnCrew(release, "port_sarim.json", 3646);
        NPC customs = spawnCrew(release, "karamja.json", 3648);
        NPC sarimSquire = spawnCrew(release, "port_sarim.json", 1770);
        NPC voidSquire = spawnCrew(release, "pest_control.json", 1769);
        for (NPC crew : new NPC[]{captain, lorris, thresnor, customs}) {
            getAction(crew, "Talk-to");
            getAction(crew, "Pay-fare");
        }
        for (NPC squire : new NPC[]{sarimSquire, voidSquire}) {
            getAction(squire, "Talk-to");
            getAction(squire, "Travel");
        }

        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override
            protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
        };
        try {
            LoginInfo login = new LoginInfo(channel, "boatfixture", "", "", "", "", 0, false, 0, 1, new int[4]);
            login.update(1001, "boatfixture", null, Collections.singletonList(PlayerGroup.REGISTERED.id), 0);
            Player player = PlayerFile.decode("{\"position\":{\"x\":3027,\"y\":3216,\"z\":0}}", Player.class);
            player.init(login);
            Config.load(player);
            player.setOnline(true);
            // The regular movement tick classifies dock tiles before NPC actions are available.
            player.wildernessLevel = 0;
            player.getInventory().add(ItemID.COINS_995, 29);
            interact(player, captain, "Pay-fare");
            choose(player, 1);
            unchanged(player, 29, "Insufficient funds");

            player.getInventory().add(ItemID.COINS_995, 71);
            interact(player, captain, "Pay-fare");
            choose(player, 2);
            unchanged(player, 100, "Stay on shore");
            player.teleportListener = p -> false;
            interact(player, captain, "Pay-fare");
            choose(player, 1);
            unchanged(player, 100, "Native travel listener veto");
            player.teleportListener = null;
            player.pvpAttackZone = true;
            interact(player, captain, "Pay-fare");
            choose(player, 1);
            unchanged(player, 100, "PvP departure blocked");
            player.pvpAttackZone = false;

            interact(player, captain, "Pay-fare");
            player.getPosition().set(3027, 3216, 1);
            choose(player, 1);
            unchanged(player, 100, "Stale ticket on another plane");
            player.getPosition().set(3027, 3216, 0);
            interact(player, captain, "Talk-to");
            player.continueDialogue();
            choose(player, 1);
            choose(player, 1);
            finish(player, 2956, 3146, 70);

            player.getInventory().add(ItemID.KARAMJAN_RUM, 1);
            interact(player, customs, "Pay-fare");
            choose(player, 1);
            unchanged(player, 70, "Customs denied passage with rum");
            check(player.getInventory().getAmount(ItemID.KARAMJAN_RUM) == 1, "Rejected voyage keeps rum");
            player.getInventory().remove(ItemID.KARAMJAN_RUM, 1);
            player.getEquipment().set(Equipment.SLOT_HANDS, new Item(ItemID.KARAMJA_GLOVES_1));
            interact(player, customs, "Pay-fare");
            player.getEquipment().set(Equipment.SLOT_HANDS, null);
            choose(player, 1);
            unchanged(player, 70, "Changed fare requires a fresh ticket");
            player.getEquipment().set(Equipment.SLOT_HANDS, new Item(ItemID.KARAMJA_GLOVES_1));
            interact(player, customs, "Pay-fare");
            choose(player, 1);
            finish(player, 3029, 3217, 55);

            player.getEquipment().set(Equipment.SLOT_HANDS, null);
            player.getEquipment().set(Equipment.SLOT_RING, new Item(ItemID.RING_OF_CHAROSA));
            player.getPosition().set(3027, 3216, 0);
            interact(player, thresnor, "Talk-to");
            player.continueDialogue();
            choose(player, 1);
            choose(player, 2);
            finish(player, 2956, 3146, 55);

            player.getPosition().set(3040, 3202, 0);
            interact(player, sarimSquire, "Talk-to");
            player.continueDialogue();
            choose(player, 1);
            choose(player, 1);
            finish(player, 2659, 2676, 55);
            interact(player, voidSquire, "Travel");
            finish(player, 3041, 3202, 55);

            checkGangplank(player, 2083, 3030, 3217, 2084, 3031, 3217, 3029, 3217, 3032, 3217);
            checkGangplank(player, 2081, 2956, 3145, 2082, 2956, 3144, 2956, 3146, 2956, 3143);
            checkGangplank(player, 14304, 3041, 3201, 14305, 3041, 3200, 3041, 3202, 3041, 3199);
            checkGangplank(player, 14306, 2660, 2676, 14307, 2661, 2676, 2659, 2676, 2662, 2676);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void checkGangplank(Player player, int outbound, int outboundX, int outboundY,
                                      int inbound, int inboundX, int inboundY, int shoreX, int shoreY, int deckX, int deckY) {
        player.getPosition().set(shoreX, shoreY, 0);
        crossGangplank(player, outbound, outboundX, outboundY, deckX, deckY, 1);
        crossGangplank(player, inbound, inboundX, inboundY, shoreX, shoreY, 0);
    }

    private static void crossGangplank(Player player, int id, int x, int y, int destX, int destY, int destPlane) {
        GameObject plank = Tile.getObject(id, x, y, player.getHeight());
        check(plank != null && plank.actions != null, "Native gangplank has a Cross action for " + id);
        check(plank.walkTo.equals(player.getPosition()), "Native route reaches the gangplank approach");
        ObjectActionHandler.handleAction(player, plank.getDef().getOption("Cross"), id, x, y, 0);
        check(player.getMovement().finishTeleport(player.getPosition()), "Gangplank moved player for " + id);
        player.getMovement().finishTeleport(player.getPosition());
        check(player.getPosition().equals(destX, destY, destPlane), "Gangplank reached correct plane and tile for " + id);
    }

    private static NPC spawnCrew(Path release, String fileName, int id) throws Exception {
        Path source = release.resolve("data/npcs/spawns").resolve(fileName);
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            for (JsonElement row : new Gson().fromJson(reader, JsonArray.class)) {
                if (row.getAsJsonObject().get("id").getAsInt() != id) continue;
                new npc_spawns().fromJson(source.toFile(), fileName, "[" + row + "]");
                for (NPC npc : World.npcs.entityList) {
                    if (npc != null && npc.getId() == id) return npc;
                }
            }
        }
        throw new AssertionError("Crew " + id + " missing from " + fileName);
    }

    private static void loadDocks(Path release) throws Exception {
        Map<Integer, int[]> keys;
        try (Reader file = Files.newBufferedReader(release.resolve("data/region_keys.json"), StandardCharsets.UTF_8)) {
            keys = new Gson().fromJson(file, new TypeToken<Map<Integer, int[]>>() {}.getType());
        }
        for (int id = 0; id < Region.LOADED.length; id++) {
            Region region = new Region(id);
            region.keys = keys.get(id);
            Region.LOADED[id] = region;
        }
        Set<Integer> loaded = new HashSet<>();
        for (int[] center : new int[][]{{3029, 3217}, {2956, 3146}, {3041, 3202}, {2659, 2676},
                {3032, 3217}, {2956, 3143}, {3041, 3199}, {2662, 2676},
                {3156, 3620}, {3227, 3667}, {3035, 3732}, {3106, 3794}, {2980, 3866}, {3307, 3916}}) {
            for (int dx : new int[]{-2, 2}) {
                for (int dy : new int[]{-2, 2}) {
                    int regionId = Region.getId(center[0] + dx, center[1] + dy);
                    if (loaded.add(regionId)) Region.get(regionId).init();
                }
            }
        }
        for (int[] dock : new int[][]{{3029, 3217}, {2956, 3146}, {3041, 3202}, {2659, 2676}}) {
            Tile tile = Tile.get(dock[0], dock[1], 0);
            check(!Region.get(dock[0], dock[1]).empty && (tile == null || tile.isFloorFreeCheckDecor()), "Walkable dock");
        }
    }

    private static NPCAction getAction(NPC npc, String label) {
        NPCDef def = npc.getDef();
        int slot = def.getOption(label);
        check(slot > 0 && def.defaultActions != null && def.defaultActions[slot - 1] != null, "Bound " + label + " for " + def.name);
        return def.defaultActions[slot - 1];
    }

    private static void interact(Player player, NPC npc, String label) {
        getAction(npc, label).handle(player, npc);
    }

    private static void choose(Player player, int choice) {
        check(player.optionsDialogue != null, "Native options offered");
        InterfaceHandler.getAction(player, Interface.OPTIONS_DIALOGUE, 1).handleClick(player, 1, choice, -1);
    }

    private static void unchanged(Player player, int coins, String label) {
        check(!player.isLocked() && !player.getMovement().isTeleportQueued()
                && player.getInventory().getAmount(ItemID.COINS_995) == coins, label);
    }

    private static void finish(Player player, int x, int y, int coins) throws Exception {
        check(player.isLocked(), "Native voyage acquired movement lock");
        Method tick = Entity.class.getDeclaredMethod("processEvent");
        tick.setAccessible(true);
        for (int i = 0; i < 8 && !player.getMovement().isTeleportQueued(); i++) tick.invoke(player);
        check(player.getMovement().finishTeleport(player.getPosition()), "Movement landed");
        player.getMovement().finishTeleport(player.getPosition());
        check(player.isAt(x, y) && player.getHeight() == 0 && !player.isLocked(), "Arrived at proper dock");
        check(player.getInventory().getAmount(ItemID.COINS_995) == coins, "Exact fare paid once");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
