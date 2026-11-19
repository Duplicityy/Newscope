import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.netty.channel.embedded.EmbeddedChannel;
import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.login.LoginInfo;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.api.utils.JsonUtils;
import io.ruin.cache.AnimDef;
import io.ruin.cache.InterfaceDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.ItemID;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.Varpbit;
import io.ruin.content.content242.WorldTravel242;
import io.ruin.content.worldquest.WorldQuestBook;
import io.ruin.model.World;
import io.ruin.model.activities.cluescrolls.ClueObjects;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.data.impl.npcs.npc_drops;
import io.ruin.data.impl.npcs.npc_spawns;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.handlers.TeleportInterface.Catagory;
import io.ruin.model.inter.handlers.TeleportInterface.Teleport;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.map.Location;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.shop.Currency;
import io.ruin.model.shop.Shop;
import io.ruin.model.shop.ShopItem;
import io.ruin.model.shop.ShopManager;
import io.ruin.model.skills.magic.spells.HomeTeleport;
import io.ruin.utility.OfflineMode;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Native cache, interface actions, and movement using a detached player and channel. */
public final class World242AccessChecks {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        try {
            Thread.currentThread().setName("server-worker #1");
            run(Paths.get(args[0]));
            System.out.println("PASS native world entry, home return, River Watch playthrough, shops and calf records");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(Path stage) throws Exception {
        Server.dataFolder = stage.resolve("data").toFile();
        Server.fileStore = new FileStore(stage.resolve("Cache").toString(), 8 * 1024 * 1024);
        World.type = WorldType.PVP;
        World.stage = io.ruin.api.protocol.world.WorldStage.DEV;
        Varpbit.load();
        InterfaceDef.load();
        ObjectDef.load();
        NPCDef.load();
        ItemDef.load();
        AnimDef.load();
        // Same fallback as the native item metadata loader for shops with no explicit price.
        ItemDef.forEach(def -> {
            if (def.value > 0) def.highAlchValue = (def.value * 3) / 5;
        });
        try (Reader file = Files.newBufferedReader(stage.resolve("data/items/item_info.json"), StandardCharsets.UTF_8)) {
            for (JsonElement row : new Gson().fromJson(file, JsonArray.class)) {
                JsonObject item = row.getAsJsonObject();
                if (item.has("equip_slot") && !item.get("equip_slot").isJsonNull())
                    ItemDef.get(item.get("id").getAsInt()).equipSlot = item.get("equip_slot").getAsInt();
            }
        }
        Map<Integer, int[]> keys;
        try (Reader file = Files.newBufferedReader(stage.resolve("data/region_keys.json"), StandardCharsets.UTF_8)) {
            keys = new Gson().fromJson(file, new TypeToken<Map<Integer, int[]>>() {}.getType());
        }
        for (int i = 0; i < Region.LOADED.length; i++) {
            Region region = new Region(i);
            region.keys = keys.get(i);
            Region.LOADED[i] = region;
        }
        loadNearby(World.HOME.getX(), World.HOME.getY());
        Location arrival = Location.LUMBRIDGE;
        loadNearby(arrival.x, arrival.y);
        // Player save decoding initializes the native wilderness obelisk enum.
        // Load its real map objects in this selective region fixture as well.
        Set<Integer> obeliskRegions = new HashSet<>();
        for (int[] center : new int[][]{{3156, 3620}, {3227, 3667}, {3035, 3732},
                {3106, 3794}, {2980, 3866}, {3307, 3916}}) {
            for (int dx : new int[]{-2, 2}) for (int dy : new int[]{-2, 2})
                obeliskRegions.add(Region.getId(center[0] + dx, center[1] + dy));
        }
        for (int region : obeliskRegions) Region.get(region).init();
        MapListener.LISTENERS = new MapListener[0];
        OfflineMode.enabled = true;
        OfflineMode.setPath();
        // ClueObjects also registers position-specific doors and stairs during startup.
        for (int[] site : new int[][]{{2973, 3385}, {3255, 3421}, {2966, 3215}, {3035, 3363}, {2558, 3300}}) {
            Region.get(site[0], site[1]).init();
        }
        Class.forName(ClueObjects.class.getName());
        GameObject ledger = Tile.getObject(366, 3228, 3205, 0, 10, 1);
        int searchIndex = ledger.getDef().getOption("Search") - 1;
        ObjectAction standardSearch = ledger.getDef().defaultActions[searchIndex];
        check(ClueObjects.isGenericSearch(standardSearch), "Ledger has the startup clue-search fallback");
        WorldQuestBook.load();
        check(WorldQuestBook.available(), "Quest handles all installed landmarks");
        check(ledger.actions[searchIndex] != null && ledger.getDef().defaultActions[searchIndex] == standardSearch,
                "Ledger adds an instance action while retaining the shared clue search");
        check(World.HOME.getX() == 2028 && World.HOME.getY() == 3577 && World.HOME.getZ() == 0, "Home moved");
        check(WorldTravel242.worldArrivalAvailable(), "Native arrival collision and map");
        JsonObject travel;
        try (Reader file = Files.newBufferedReader(stage.resolve("data/content242-world.json"), StandardCharsets.UTF_8)) {
            travel = new Gson().fromJson(file, JsonObject.class);
        }
        Set<Integer> travelRegions = new HashSet<>();
        for (JsonElement place : travel.getAsJsonArray("destinations")) {
            JsonArray tile = place.getAsJsonObject().getAsJsonArray("tile");
            int region = Region.getId(tile.get(0).getAsInt(), tile.get(1).getAsInt());
            if (travelRegions.add(region)) Region.get(region).init();
        }
        Method initializeTravel = WorldTravel242.class.getDeclaredMethod("initialize", JsonObject.class);
        initializeTravel.setAccessible(true);
        initializeTravel.invoke(null, travel);
        Set<Integer> slots = new HashSet<>();
        for (Teleport destination : Teleport.forCatagory(Catagory.MINIGAMES)) {
            check(slots.add(destination.getSlot()), "Duplicate minigame slot");
        }
        check(slots.size() == 14 && Teleport.forCatagory(Catagory.MINIGAMES).contains(Teleport.OSRS_WORLD)
                && !Teleport.forCatagory(Catagory.MINIGAMES).contains(Teleport.ARCEUUS_TEASER_HOME),
                "OSRS World and the minigame entries without the teaser");
        check(!Teleport.forCatagory(Catagory.DUNGEONS).contains(Teleport.NEW_LANDS), "New lands removed from Dungeons");
        check(Teleport.valueOf("THEATRE_OF_BLOOD") != null && Teleport.valueOf("TOMBS_OF_AMASCUT") != null,
                "Existing raid lobby entries");

        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
        };
        try {
            LoginInfo login = new LoginInfo(channel, "worldfixture", "", "", "", "", 0, false, 0, 1, new int[4]);
            login.update(1001, "worldfixture", null, Collections.singletonList(PlayerGroup.REGISTERED.id), 0);
            Player player = PlayerFile.decode("{\"position\":{\"x\":2028,\"y\":3577,\"z\":0}}", Player.class);
            player.init(login);
            Config.load(player);
            player.setOnline(true);
            player.getTeleports().setSelectedTeleport(Teleport.ARCEUUS_TEASER_HOME);
            player.getTeleports().sendInterface();
            check(player.getTeleports().getSelectedTeleport() == null, "Retired teaser selection cleared");
            Method previous = player.getTeleports().getClass().getDeclaredMethod("teleportPrevious");
            previous.setAccessible(true);
            player.getTeleports().setLastTeleport(Teleport.ARCEUUS_TEASER_HOME);
            previous.invoke(player.getTeleports());
            check(player.getTeleports().getLastTeleport() == null && !player.getMovement().isTeleportQueued(),
                    "Retired teaser cannot be repeated");
            player.getTeleports().setLastTeleport(Teleport.NEW_LANDS);
            previous.invoke(player.getTeleports());
            check(player.getTeleports().getLastTeleport() == Teleport.OSRS_WORLD && player.optionsDialogue != null,
                    "Previous New lands travel opens OSRS World");
            player.closeInterfaces();
            player.getTeleports().sendInterface(Catagory.MINIGAMES);
            int selector = Teleport.OSRS_WORLD.getSlot();
            java.lang.reflect.Field components = player.getTeleports().getClass().getDeclaredField("BUTTON_COMPONENTS");
            components.setAccessible(true);
            int button = ((int[]) components.get(null))[selector];
            click(player, button);
            check(player.getTeleports().getSelectedTeleport() == Teleport.OSRS_WORLD, "Registered minigame selector");
            player.wildernessLevel = 1;
            click(player, 155);
            choose(player, 1);
            check(!player.isLocked() && !player.getMovement().isTeleportQueued(), "PvP origin blocked");
            player.wildernessLevel = 0;
            player.getTeleports().sendInterface(Catagory.MINIGAMES);
            click(player, button);
            click(player, 155);
            choose(player, 1);
            finishTeleport(player);
            check(player.isAt(arrival.x, arrival.y) && player.getHeight() == arrival.z, "Actual arrival position");
            check(player.getTeleports().getLastTeleport() == Teleport.OSRS_WORLD, "Previous teleport selection");
            checkRiverWatch(player, login);
            checkShopActions(stage, player);
            WorldNpcDialogueChecks.run(stage, player);
            checkCalves(stage);
            player.closeInterfaces();
            previous.invoke(player.getTeleports());
            choose(player, 2);
            choose(player, 2);
            finishTeleport(player);
            check(player.isAt(144, 122) && player.getHeight() == 0, "Civitas illa Fortis retains its mapped regional route");
            HomeTeleport.MODERN.clickAction.accept(player, null);
            finishTeleport(player);
            check(player.isAt(World.HOME.getX(), World.HOME.getY()) && player.getHeight() == World.HOME.getZ(),
                    "Actual return via existing Home spell");
            checkLokarTravel(stage, player);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void checkLokarTravel(Path stage, Player player) throws Exception {
        for (int[] site : new int[][]{{2620, 3693}, {2214, 3794}}) Region.get(site[0], site[1]).init();
        Class.forName("io.ruin.model.entity.npc.actions.traveling.LokarSearunner");
        NPC mainland = spawnNpc(stage, "rellekka.json", 3855);
        NPC cove = spawnNpc(stage, "pirates_cove.json", 3855);
        NPCDef def = mainland.getDef();
        int talk = def.getOption("Talk-to") - 1;
        int travel = def.getOption("Travel") - 1;
        check(talk >= 0 && travel >= 0 && def.defaultActions[talk] != null && def.defaultActions[travel] != null,
                "Lokar has separate Talk-to and Travel actions");
        player.getPosition().set(2620, 3692, 0);
        def.defaultActions[talk].handle(player, mainland);
        check(player.hasDialogue() && !player.getMovement().isTeleportQueued(), "Talk-to waits for a voyage choice");
        player.continueDialogue();
        choose(player, 1);
        finishTeleport(player);
        check(player.isAt(2213, 3794) && player.getHeight() == 0, "Voyage takes the player to Pirates' Cove");
        def.defaultActions[travel].handle(player, cove);
        finishTeleport(player);
        check(player.isAt(2621, 3686) && player.getHeight() == 0, "Travel takes the player back to Rellekka");
    }

    private static void checkShopActions(Path stage, Player player) throws Exception {
        ShopManager.registerUI();
        verifyDialogueShop(stage, player, 8684, 1, 1,
                "io.ruin.model.entity.npc.actions.guild.fishing.Roachey", "Fishing_Guild_Shop.yaml",
                "fishing_guild.json");
        verifyDialogueShop(stage, player, 4293, 4, 3,
                "io.ruin.model.entity.npc.actions.piscatoris.ArnoldLydspor", "Arnold's_Electic_Supplies.yaml",
                "fishing_colony.json");
        verifyDialogueShop(stage, player, 6964, 1, 1,
                "io.ruin.model.entity.npc.actions.zeah.Tynan", "Tynan's_Fishing_Supplies.yaml",
                "zeah.json");

        check(NPCDef.get(7317).getOption("Trade") == 4, "Lisa has the client-visible Trade slot");
        check(NPCDef.get(7317).getOption("Talk-to") == 1 && NPCDef.get(7317).getOption("Join") == 3,
                "Lisa retains her other options");
        Shop lms = loadShop(stage, "LMS_Reward_Shop.yaml");
        check(lms.currency == Currency.SURVIVAL_TOKENS, "Native LMS reward currency");
        NPC lisa = spawnNpc(stage, "clan_wars.json", 7317);
        lisa.actions[3].handle(player, lisa);
        check(player.viewingShop == lms, "Lisa's real spawn directs Trade to the LMS shop");
        buyOne(player, lms, ItemID.SURVIVAL_TOKEN);
    }

    private static void verifyDialogueShop(Path stage, Player player, int id, int option, int continues,
                                           String className, String shopFile, String spawns) throws Exception {
        Shop shop = loadShop(stage, shopFile);
        check(shop.currency == Currency.COINS, "Original shop uses coins");
        Class.forName(className);
        NPC npc = spawnNpc(stage, spawns, id);
        int talk = NPCDef.get(id).getOption("Talk-to");
        int trade = NPCDef.get(id).getOption("Trade");
        check(talk > 0 && trade > 0 && npc.actions[trade - 1] != null, "Installed Talk-to and Trade options");
        npc.actions[trade - 1].handle(player, npc);
        check(player.viewingShop == shop, "Direct Trade opens the authored shop");
        player.closeInterfaces();

        NPCDef.get(id).defaultActions[talk - 1].handle(player, npc);
        player.continueDialogue();
        choose(player, option);
        for (int step = 1; step < continues; step++) player.continueDialogue();
        check(player.viewingShop == shop, "Dialogue opens the same shop as Trade");
        buyOne(player, shop, ItemID.COINS_995);
        player.closeInterfaces();
    }

    private static void buyOne(Player player, Shop shop, int currency) {
        ShopItem stock = null;
        for (int slot = 0; slot < ShopManager.SHOP_MAX_CAPACITY; slot++) {
            ShopItem candidate = shop.shopItems.getSafe(slot);
            if (candidate != null && candidate.getAmount() > 1 && shop.getSellPrice(candidate) > 0) {
                stock = candidate;
                break;
            }
        }
        check(stock != null, "Positive-price stock is available");
        int price = shop.getSellPrice(stock);
        check(player.getInventory().add(currency, price) == price, "Fixture supplies the correct currency");
        int beforeCurrency = player.getInventory().getAmount(currency);
        int beforeItem = player.getInventory().getAmount(stock.getId());
        int beforeStock = stock.getAmount();
        InterfaceHandler.getAction(player, 300, 16).handleClick(player, 2, stock.getSlot(), stock.getId());
        check(player.getInventory().getAmount(currency) == beforeCurrency - price, "Native shop debited its currency");
        check(player.getInventory().getAmount(stock.getId()) == beforeItem + 1, "Native shop delivered the item");
        check(stock.getAmount() == beforeStock - 1, "Native shop decremented its stock");
    }

    private static void checkCalves(Path stage) throws Exception {
        String fileName = "Cow_calf.json";
        Path data = stage.resolve("data/npcs");
        new npc_combat().fromJson(fileName, readText(data.resolve("combat").resolve(fileName)));
        NPC lumbridge = spawnNpc(stage, "lumbridge.json", 2801);
        NPC ardougne = spawnNpc(stage, "ardougne.json", 2794);
        for (NPC calf : Arrays.asList(lumbridge, ardougne)) {
            check(calf.getDef().getOption("Attack") > 0 && calf.getCombat() != null, "Calf is attackable");
            check(calf.getHp() == 6 && calf.getCombat().getInfo().attack_animation == 5849,
                    "Calf reuses the native combat profile");
        }
        for (String config : Arrays.asList("pvp", "eco")) {
            new npc_drops().fromJson(fileName, readText(data.resolve("drops").resolve(config).resolve(fileName)));
            for (NPC calf : Arrays.asList(lumbridge, ardougne)) {
                Set<Integer> drops = new HashSet<>();
                calf.getDef().lootTable.rollItems(true).forEach(item -> drops.add(item.getId()));
                check(drops.equals(new HashSet<>(Arrays.asList(1739, 2132, 526))),
                        "Calf uses all three guaranteed drops for " + config);
            }
        }
    }

    private static String readText(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Shop loadShop(Path stage, String fileName) throws Exception {
        Shop shop = new ObjectMapper(new YAMLFactory())
                .readValue(stage.resolve("data/shops").resolve(fileName).toFile(), Shop.class);
        shop.init();
        shop.populate();
        ShopManager.getShops().put(shop.identifier, shop);
        return shop;
    }

    private static NPC spawnNpc(Path stage, String fileName, int id) throws Exception {
        Path source = stage.resolve("data/npcs/spawns").resolve(fileName);
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            for (JsonElement row : new Gson().fromJson(reader, JsonArray.class)) {
                JsonObject fields = row.getAsJsonObject();
                if (fields.get("id").getAsInt() != id) continue;
                new npc_spawns().fromJson(source.toFile(), fileName, "[" + row + "]");
                for (NPC npc : World.npcs.entityList) {
                    if (npc != null && npc.getId() == id && npc.getSpawnPosition().equals(
                            fields.get("x").getAsInt(), fields.get("y").getAsInt(), fields.get("z").getAsInt())) return npc;
                }
            }
        }
        throw new AssertionError("No configured NPC " + id + " in " + fileName);
    }

    private static void checkRiverWatch(Player player, LoginInfo login) throws Exception {
        GameObject notice = Tile.getObject(18493, 3235, 3228, 0, 10, 0);
        GameObject gauge = Tile.getObject(18493, 3261, 3230, 0, 10, 2);
        GameObject crate = Tile.getObject(366, 3228, 3205, 0, 10, 1);
        check(player.worldQuests == null, "Existing saves do not allocate quest state");
        read(player, crate);
        check(player.worldQuests == null, "No progress before accepting");

        read(player, notice);
        player.continueDialogue();
        approach(player, crate);
        choose(player, 1);
        check(player.worldQuests == null, "Stale acceptance cannot advance away from the notice");

        read(player, notice);
        player.continueDialogue();
        choose(player, 1);
        stage(player, "ACCEPTED");
        read(player, crate);
        stage(player, "DOWNSTREAM_READ");
        read(player, crate);
        stage(player, "DOWNSTREAM_READ");
        read(player, gauge);
        stage(player, "SURVEYED");

        read(player, notice);
        player.continueDialogue();
        choose(player, 2);
        stage(player, "SURVEYED");
        read(player, notice);
        player.continueDialogue();
        choose(player, 1);
        stage(player, "DIAGNOSED");
        read(player, gauge);
        choose(player, 1);
        stage(player, "DIAGNOSED");
        check(player.getInventory().add(ItemID.HAMMER, 1) == 1, "Fixture adds a hammer");
        read(player, gauge);
        choose(player, 1);
        stage(player, "REPAIRED");
        check(player.getInventory().getAmount(ItemID.HAMMER) == 1, "Hammer is retained");

        check(player.getInventory().add(ItemID.HAMMER, 27) == 27, "Fixture fills its inventory");
        read(player, notice);
        choose(player, 1);
        stage(player, "REPAIRED");
        check(player.getInventory().getAmount(ItemID.COINS_995) == 0, "No partial payment");
        check(player.getInventory().remove(ItemID.HAMMER, 27) == 27, "Fixture makes one free slot or more");
        read(player, notice);
        choose(player, 1);
        stage(player, "COMPLETE");
        check(player.getInventory().getAmount(ItemID.COINS_995) == 1250, "Exactly one native payout");

        Path save = OfflineMode.playerSavePath(player.getName());
        Player restored = PlayerFile.decode(readText(save), Player.class);
        restored.init(login);
        Config.load(restored);
        restored.setOnline(true);
        stage(restored, "COMPLETE");
        check(restored.getInventory().getAmount(ItemID.COINS_995) == 1250, "Completion and payout saved together");
        read(restored, notice);
        check(restored.optionsDialogue == null, "Claim option is absent after reload");
    }

    private static void choose(Player player, int choice) {
        check(player.optionsDialogue != null, "Native options available");
        InterfaceHandler.getAction(player, Interface.OPTIONS_DIALOGUE, 1).handleClick(player, 1, choice, -1);
    }

    private static void read(Player player, GameObject object) {
        approach(player, object);
        check(object.actions != null && object.actions[0] != null, "Native quest action registered");
        object.actions[0].handle(player, object);
    }

    private static void approach(Player player, GameObject object) {
        for (int[] offset : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            int x = object.x + offset[0];
            int y = object.y + offset[1];
            Tile tile = Tile.get(x, y, object.z);
            if (tile == null || tile.isFloorFreeCheckDecor()) {
                player.getPosition().set(x, y, object.z);
                return;
            }
        }
        throw new AssertionError("No open approach to quest landmark");
    }

    private static void stage(Player player, String expected) {
        JsonObject stages = JsonUtils.GSON_EXPOSE.toJsonTree(player.worldQuests).getAsJsonObject().getAsJsonObject("stages");
        check(expected.equals(stages.get("river-watch").getAsString()), "Expected River Watch stage " + expected);
    }

    private static void loadNearby(int x, int y) {
        for (int rx = (x >> 6) - 1; rx <= (x >> 6) + 1; rx++) {
            for (int ry = (y >> 6) - 1; ry <= (y >> 6) + 1; ry++) Region.get(rx << 8 | ry).init();
        }
    }

    private static void click(Player player, int component) {
        InterfaceHandler.getAction(player, Interface.TELEPORTS, component).handleClick(player, 1, -1, -1);
    }

    private static void finishTeleport(Player player) throws Exception {
        Method tick = Entity.class.getDeclaredMethod("processEvent");
        tick.setAccessible(true);
        for (int i = 0; i < 8 && !player.getMovement().isTeleportQueued(); i++) tick.invoke(player);
        check(player.getMovement().finishTeleport(player.getPosition()), "Native movement did not complete");
        player.getMovement().finishTeleport(player.getPosition());
        check(!player.isLocked(), "Native teleport lock not released");
    }
}
