import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.login.LoginInfo;
import io.ruin.cache.InterfaceDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.content.pets.UniversalPets;
import io.ruin.model.World;
import io.ruin.model.activities.dungeoneering.DungeoneeringUtilityRewards;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.impl.Pet;
import io.ruin.process.event.EventWorker;
import io.ruin.utility.LocalAccounts;
import io.ruin.utility.OfflineMode;
import java.lang.reflect.Field;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class PetRuntimeChecks {
    private static final String TOKEN_MARKER = "test.pet.return";
    private static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private static void claim(Player player, int source) throws Exception {
        Method method = UniversalPets.class.getDeclaredMethod("claim", Player.class, int.class);
        method.setAccessible(true);
        method.invoke(null, player, source);
    }
    private static void checkDelayedDrop(Player player, int source, int item, Path save,
                                         ExecutorService saves, boolean commit) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        saves.submit(() -> release.await(20, TimeUnit.SECONDS));
        String savePath = OfflineMode.path;
        try {
            if (!commit) OfflineMode.path = save.getParent().getParent().resolve("blocked-save").toString();
            ItemDef.get(item).inventoryActions[4].handle(player, player.getInventory().findFirst(item));
            check(PlayerFile.isSaveQuarantined(player) && player.isLocked()
                    && player.universalPets.active == source && Config.PET_NPC_INDEX.get(player) == -1
                    && player.getInventory().findFirst(item) == null, "A delayed drop stays pending before the save settles");
            EventWorker.process();
            check(PlayerFile.isSaveQuarantined(player), "A pending save is not mistaken for a successful write");
        } finally {
            OfflineMode.path = savePath;
            release.countDown();
        }
        saves.submit(() -> {}).get(10, TimeUnit.SECONDS);
        EventWorker.process();
        check(!PlayerFile.isSaveQuarantined(player) && !player.isLocked(), "A settled drop restores movement");
        Player persisted = PlayerFile.decode(read(save), Player.class);
        if (commit) {
            int index = Config.PET_NPC_INDEX.get(player);
            NPC pet = World.getNpc(index);
            check(pet != null && pet.ownerId == player.getUserId() && persisted.universalPets.active == source
                    && player.getInventory().findFirst(item) == null, "Delayed commit saves and releases exactly one pet");
            EventWorker.process();
            check(World.getNpc(index) == pet && Config.PET_NPC_INDEX.get(player) == index,
                    "Delayed drop completion does not run twice");
        } else {
            check(Config.PET_NPC_INDEX.get(player) == -1 && player.universalPets.active == 0
                    && hasMarkedToken(player, item) && persisted.universalPets.active == 0,
                    "Delayed failed write returns the token and never spawns the follower");
        }
    }

    private static void checkDelayedPickup(Player player, int source, int item, Path save,
                                            ExecutorService saves, boolean commit) throws Exception {
        int index = Config.PET_NPC_INDEX.get(player);
        NPC follower = World.getNpc(index);
        CountDownLatch release = new CountDownLatch(1);
        saves.submit(() -> release.await(20, TimeUnit.SECONDS));
        String savePath = OfflineMode.path;
        try {
            if (!commit) OfflineMode.path = save.getParent().getParent().resolve("blocked-save").toString();
            follower.getDef().defaultActions[0].handle(player, follower);
            check(PlayerFile.isSaveQuarantined(player) && player.isLocked()
                    && UniversalPets.hasActivePet(player) && !follower.isRemoved(),
                    "A pickup keeps the follower slot until the write settles");
            follower.getDef().defaultActions[0].handle(player, follower);
        } finally {
            OfflineMode.path = savePath;
            release.countDown();
        }
        saves.submit(() -> {}).get(10, TimeUnit.SECONDS);
        EventWorker.process();
        check(!PlayerFile.isSaveQuarantined(player) && !player.isLocked(), "Settled pickup restores movement");
        Player persisted = PlayerFile.decode(read(save), Player.class);
        if (commit) {
            check(player.universalPets.active == 0 && follower.isRemoved() && hasMarkedToken(player, item)
                    && hasMarkedToken(persisted, item) && !player.getInventory().hasMultiple(item)
                    && !player.universalPets.pending.contains(source),
                    "Confirmed pickup returns one attributed token to the inventory and save");
        } else {
            check(player.universalPets.active == source && Config.PET_NPC_INDEX.get(player) == index
                    && !follower.isRemoved() && player.getInventory().findFirst(item) == null
                    && persisted.universalPets.active == source,
                    "A failed pickup keeps the original follower and rolls back the attributed token");
        }
    }

    private static boolean hasMarkedToken(Player player, int item) {
        Item token = player.getInventory().findFirst(item);
        return token != null && token.getAmount() == 1 && "retained".equals(token.getAttributeString(TOKEN_MARKER, ""));
    }

    @SuppressWarnings("unchecked")
    private static void checkUtilityFollower(Player player, Item token) throws Exception {
        NPC sneakerpeeper = new NPC(11004);
        sneakerpeeper.ownerId = player.getUserId();
        sneakerpeeper.spawn(player.getAbsX(), player.getAbsY(), player.getHeight());
        Field tracked = DungeoneeringUtilityRewards.class.getDeclaredField("FOLLOWERS");
        tracked.setAccessible(true);
        ((Map<Player, NPC>) tracked.get(null)).put(player, sneakerpeeper);
        Config.PET_NPC_INDEX.set(player, sneakerpeeper.getIndex());
        check(sneakerpeeper.getIndex() == 0, "The first NPC index is a valid follower slot");
        ItemDef.get(token.getId()).inventoryActions[4].handle(player, token);
        check(player.getInventory().findFirst(token.getId()) == token && player.universalPets.active == 0
                && DungeoneeringUtilityRewards.hasFollower(player),
                "The utility follower blocks a new pet even when its index is zero");
        DungeoneeringUtilityRewards.logout(player);
        World.npcs.index(sneakerpeeper);
    }
    public static void main(String[] args) {
        try {
            Thread.currentThread().setName("server-worker #1");
            Path staged = Paths.get(args[0]);
            Path state = args.length > 3 ? Paths.get(args[3]) : staged.resolve("state");
            run(staged, state, Paths.get(args[1]), args[2]);
            System.out.println("PASS " + checks + " initialized runtime assertions");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
    private static void run(Path staged, Path state, Path temp, String testPassword) throws Exception {
        Path file = state.resolve("player-data/players/pet.json");
        Path tempPlayers = temp.resolve("players");
        Files.createDirectories(tempPlayers);
        Files.copy(file, tempPlayers.resolve("pet.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path copy = tempPlayers.resolve("pet.json");
        System.setProperty("kronos.accounts", state.resolve("accounts.properties").toString());
        System.setProperty("kronos.playerData", temp.toString());
        System.setProperty("kronos.universalPets", "true");
        OfflineMode.enabled = true;
        OfflineMode.setPath();
        LocalAccounts accounts = new LocalAccounts(state.resolve("accounts.properties"), false);
        check(accounts.authenticate("pet", testPassword) != null, "Authenticate pet");
        check(accounts.authenticate("pet", "wrong") == null && accounts.authenticate("other", testPassword) == null, "Reject wrong password and unknown account");

        // Exercise real cache decoders and ItemAction/NPCAction registrations.
        Path fixtureData = temp.resolve("data");
        Files.createDirectories(fixtureData);
        JsonObject manifest = new Gson().fromJson(read(staged.resolve("data/pet-definitions.json")), JsonObject.class);
        JsonObject definitions = manifest.getAsJsonObject("definitions");
        definitions.getAsJsonObject("70").addProperty("rate", 1);
        definitions.getAsJsonObject("1").addProperty("rate", 1);
        definitions.getAsJsonObject("2058").addProperty("attack", 422);
        Files.write(fixtureData.resolve("pet-definitions.json"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        Server.dataFolder = fixtureData.toFile();
        Server.fileStore = new FileStore(staged.resolve("Cache").toString());
        World.type = io.ruin.api.protocol.world.WorldType.PVP;
        io.ruin.cache.Varpbit.load();
        InterfaceDef.load();
        NPCDef.load();
        ItemDef.load();
        com.google.gson.JsonArray items = new Gson().fromJson(read(staged.resolve("data/items/item_info.json")), com.google.gson.JsonArray.class);
        for (com.google.gson.JsonElement record : items) {
            JsonObject object = record.getAsJsonObject();
            if (object.has("equip_slot") && !object.get("equip_slot").isJsonNull()) {
                ItemDef def = ItemDef.get(object.get("id").getAsInt());
                if (def != null) def.equipSlot = object.get("equip_slot").getAsInt();
            }
        }
        World.stage = io.ruin.api.protocol.world.WorldStage.DEV;
        io.ruin.cache.ObjectDef.load();
        io.ruin.cache.AnimDef.load();
        UniversalPets.initialize();
        Set<Integer> tokens = new HashSet<>();
        int oldAnimations = 0;
        for (Map.Entry<String, com.google.gson.JsonElement> entry : definitions.entrySet()) {
            int source = Integer.parseInt(entry.getKey());
            JsonObject def = entry.getValue().getAsJsonObject();
            int item = def.get("item").getAsInt();
            NPCDef follower = NPCDef.get(def.get("npc").getAsInt());
            check(UniversalPets.dropItem(source) == item && tokens.add(item), "Bound token " + source);
            check(follower.combatInfo == null && !follower.occupyTiles && follower.defaultActions != null, "Harmless registered follower " + source);
            check(ItemDef.get(item).inventoryActions[4] != null && !ItemDef.get(item).tradeable, "Registered unsigned token " + source);
            if(def.has("attack") && def.get("attack").getAsInt() > 0){
                check(Server.fileStore.get(2).getFile(12, def.get("attack").getAsInt()) != null, "Existing attack sequence " + source);
                oldAnimations++;
            }
        }
        check(definitions.size() == 3808 && oldAnimations > 100, "Coverage and mapped attack assets");

        io.ruin.model.map.Region[] regions = io.ruin.model.map.Region.LOADED;
        for(int i=0;i<regions.length;i++) regions[i]=new io.ruin.model.map.Region(i);
        int[][] obelisks={{14829,3156,3620},{14830,3227,3667},{14827,3035,3732},{14828,3106,3794},{14826,2980,3866},{14831,3307,3916}};
        for(int[] row:obelisks) for(int dx:new int[]{-2,2}) for(int dy:new int[]{-2,2}) {
            int x=row[1]+dx,y=row[2]+dy;
            io.ruin.model.map.Tile.get(x,y,0,true).addObject(new io.ruin.model.map.object.GameObject(row[0],x,y,0,10,0));
        }
        io.ruin.model.map.MapListener.LISTENERS = new io.ruin.model.map.MapListener[0];
        Player p = PlayerFile.decode(read(copy), Player.class);
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
        };
        LoginInfo info = new LoginInfo(channel, "pet", testPassword, "", "", "", 0, false, 0, 1, new int[4]);
        info.update(1001, "pet", null, Collections.singletonList(PlayerGroup.REGISTERED.id), 0);
        p.init(info);
        Config.load(p);
        p.setOnline(true);
        check(p.getBank().getItems().length == 800 && p.universalPets.owned.size() == 3808
                && p.universalPets.pending.size() == 3130, "Bank and overflow deserialized");
        for(Item item : p.getBank().getItems())
            check(item != null && item.getDef() != null && item.getAmount() == 1, "Bank pet slot");
        int overflow = p.universalPets.pending.iterator().next();
        int item = UniversalPets.dropItem(overflow);
        check(p.getInventory().add(385, 28) == 28, "Fill inventory");
        claim(p, overflow);
        check(p.universalPets.pending.contains(overflow) && !p.getInventory().contains(item), "Full inventory keeps claim");
        String validPath = OfflineMode.path;
        Path blocked = temp.resolve("blocked-save");
        Files.write(blocked, new byte[]{0});
        OfflineMode.path = blocked.toString();
        p.getInventory().remove(385, 1);
        try {
            claim(p, overflow);
            check(p.universalPets.pending.contains(overflow) && !p.getInventory().contains(item)
                    && !PlayerFile.isSaveQuarantined(p), "Definite save failure restores the inventory and queued claim");
        } finally {
            OfflineMode.path = validPath;
        }
        claim(p, overflow);
        check(p.getInventory().contains(item) && !p.universalPets.pending.contains(overflow), "Claim a high-ID token");
        Player persisted = PlayerFile.decode(read(copy), Player.class);
        check(!persisted.universalPets.pending.contains(overflow), "Durable claim reload");
        UniversalPets.storeTokensOnDeath(p);
        check(p.universalPets.pending.contains(overflow) && !p.getInventory().contains(item), "Death returns carried token to claims");

        p.universalPets.owned.remove(70);
        NPC skeleton = new NPC(70);
        skeleton.renewEndgameSpawnId();
        skeleton.sixSystemsRewardless = true;
        UniversalPets.killed(skeleton, p);
        check(!p.universalPets.owned.contains(70), "Ordinary path blocks rewardless actor");
        NPC owned = new NPC(70);
        owned.ownerId = p.getUserId();
        UniversalPets.encounterKilled(owned, p, 70);
        check(!p.universalPets.owned.contains(70), "Follower may not earn another pet");
        UniversalPets.encounterKilled(skeleton, p, 70);
        check(p.universalPets.owned.contains(70) && p.universalPets.pending.contains(70), "Scripted kill awards pet");
        p.universalPets.owned.remove(70);
        p.universalPets.pending.remove(70);
        UniversalPets.encounterKilled(skeleton, p, 70);
        check(!p.universalPets.owned.contains(70), "Second death callback skipped");
        skeleton.renewEndgameSpawnId();
        skeleton.sixSystemsRewardless = false;
        UniversalPets.killed(skeleton, p);
        check(p.universalPets.owned.contains(70), "Fresh normal spawn can award");
        persisted = PlayerFile.decode(read(copy), Player.class);
        check(persisted.universalPets.pending.contains(70), "Earned claim survives reload");
        check(Pet.values().length == 122, "Existing pets register normally");
        p.getInventory().clear();
        claim(p, overflow);
        int other = p.universalPets.pending.stream().filter(id -> id != 70).findFirst().get();
        int otherItem = UniversalPets.dropItem(other);
        claim(p, other);
        Item token = p.getInventory().findItem(item);
        check(token != null, "Carry claimed token");
        token.putAttribute(TOKEN_MARKER, "retained");
        checkUtilityFollower(p, token);
        ItemDef.get(item).inventoryActions[4].handle(p, token);
        int npcIndex = Config.PET_NPC_INDEX.get(p);
        NPC follower = World.getNpc(npcIndex);
        check(follower != null && follower.ownerId == p.getUserId() && follower.getCombat() == null
                && p.universalPets.active == overflow && p.getInventory().findFirst(item) == null, "Drop action releases one harmless follower");
        ItemDef.get(otherItem).inventoryActions[4].handle(p, p.getInventory().findItem(otherItem));
        check(p.getInventory().contains(otherItem) && p.universalPets.active == overflow
                && Config.PET_NPC_INDEX.get(p) == npcIndex && !follower.isRemoved(), "Second generated drop retains both pets");

        Pet mole = Pet.BABY_MOLE;
        check(p.getBank().remove(mole.itemId, 1) == 1 && p.getInventory().add(mole.itemId, 1) == 1,
                "Carry the legacy boss token");
        ItemDef.get(mole.itemId).inventoryActions[4].handle(p, p.getInventory().findItem(mole.itemId));
        check(p.getInventory().contains(mole.itemId) && p.pet == null && !follower.isRemoved()
                && Config.PET_NPC_INDEX.get(p) == npcIndex, "A legacy drop cannot replace a new follower");
        p.getInventory().remove(mole.itemId, 1);
        mole.unlock(p);
        check(p.getInventory().contains(mole.itemId) && p.pet == null && !follower.isRemoved(),
                "An earned boss pet goes to inventory while a new pet follows");
        Field animation = Entity.class.getDeclaredField("animationUpdate");
        animation.setAccessible(true);
        follower.getDef().defaultActions[1].handle(p, follower);
        check(((io.ruin.model.entity.shared.masks.AnimationUpdate)animation.get(follower)).id == 422,
                "Show attack uses the existing animation without combat");
        UniversalPets.resume(p);
        check(World.getNpc(npcIndex) == follower, "Resume does not duplicate the follower");
        persisted = PlayerFile.decode(read(copy), Player.class);
        check(persisted.universalPets.active == overflow, "Follower saved before spawn");
        UniversalPets.logout(p);
        check(follower.isRemoved() && Config.PET_NPC_INDEX.get(p) == -1 && UniversalPets.hasActivePet(p),
                "Logout removes the runtime follower and preserves its saved slot");
        p.universalPets = persisted.universalPets;
        p.setOnline(false);
        UniversalPets.resume(p);
        check(Config.PET_NPC_INDEX.get(p) == -1, "Offline follower remains absent");
        p.setOnline(true);
        UniversalPets.resume(p);
        follower = World.getNpc(Config.PET_NPC_INDEX.get(p));
        check(follower != null && !follower.isRemoved(), "Online state restores the saved follower");
        int free = p.getInventory().getFreeSlots();
        check(p.getInventory().add(385, free) == free, "Fill remaining slots before pickup");
        follower.getDef().defaultActions[0].handle(p, follower);
        check(p.universalPets.active == overflow && !follower.isRemoved() && p.getInventory().findFirst(item) == null,
                "A full inventory leaves the pet following");
        check(p.getInventory().remove(385, 1) == 1, "Free a single slot for the returning token");
        follower.getDef().defaultActions[0].handle(p, follower);
        follower.getDef().defaultActions[0].handle(p, follower);
        check(p.universalPets.active == 0 && !p.universalPets.pending.contains(overflow) && follower.isRemoved()
                && hasMarkedToken(p, item) && !p.getInventory().hasMultiple(item),
                "Pick up returns one original token immediately and ignores stale clicks");
        persisted = PlayerFile.decode(read(copy), Player.class);
        check(persisted.universalPets.active == 0 && !persisted.universalPets.pending.contains(overflow)
                && hasMarkedToken(persisted, item), "The pickup and token attributes persist together");
        p.getInventory().remove(385, free - 1);

        ItemDef.get(mole.itemId).inventoryActions[4].handle(p, p.getInventory().findItem(mole.itemId));
        int oldIndex = Config.PET_NPC_INDEX.get(p);
        NPC legacyFollower = World.getNpc(oldIndex);
        check(p.pet == mole && legacyFollower != null && legacyFollower.getId() == mole.npcId
                && !p.getInventory().contains(mole.itemId), "Original boss pet can still be dropped");
        ItemDef.get(item).inventoryActions[4].handle(p, p.getInventory().findFirst(item));
        check(hasMarkedToken(p, item) && p.universalPets.active == 0 && p.pet == mole
                && Config.PET_NPC_INDEX.get(p) == oldIndex, "A new token cannot replace a legacy follower");
        int pickupOption = legacyFollower.getDef().getOption("pick-up");
        legacyFollower.getDef().defaultActions[pickupOption - 1].handle(p, legacyFollower);
        Method processEvent = Entity.class.getDeclaredMethod("processEvent");
        processEvent.setAccessible(true);
        processEvent.invoke(p);
        processEvent.invoke(p);
        check(p.pet == null && legacyFollower.isRemoved() && p.getInventory().contains(mole.itemId),
                "Original boss pet pickup still returns the token");
        Field executor = PlayerFile.class.getDeclaredField("SAVE_EXECUTOR");
        executor.setAccessible(true);
        ExecutorService saves = (ExecutorService) executor.get(null);
        checkDelayedDrop(p, overflow, item, copy, saves, false);
        checkDelayedDrop(p, overflow, item, copy, saves, true);
        checkDelayedPickup(p, overflow, item, copy, saves, false);
        checkDelayedPickup(p, overflow, item, copy, saves, true);
        channel.finishAndReleaseAll();
    }
}
