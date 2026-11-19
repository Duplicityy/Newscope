package io.ruin.model.activities.automation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.world.WorldStage;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.InterfaceDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.Varpbit;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerMovement;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.Inventory;
import io.ruin.model.map.ClipUtils;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.route.RouteFinder;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.skills.mining.Mining;
import io.ruin.model.skills.mining.Pickaxe;
import io.ruin.model.skills.mining.Rock;
import io.ruin.model.stat.StatList;
import io.ruin.model.stat.StatType;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reads the detached cache and runs host definitions and routing without logging in; includes a native action on a detached player. */
public final class OwnerAutomationNativeChecks {
    private static final Position LUMBRIDGE_SWAMP = new Position(3229, 3147, 0);
    private static final Position VARROCK_EAST_MINE = new Position(3284, 3371, 0);
    private static final Position VARROCK_EAST_BANK = new Position(3253, 3420, 0);
    private static final Position EDGEVILLE_BANK = new Position(3093, 3491, 0);
    private static int checks;

    private OwnerAutomationNativeChecks() {}

    public static void main(String[] args) {
        try {
            loadFixture(Paths.get(args[0]));
            checkPickaxes();
            require(MiningAutomation.rockFor(11390, "Rocks") == null, "depleted native rock excluded");
            require(MiningAutomation.rockFor(11364, "Rocks") == Rock.IRON, "native steel-grey rocks map to Iron");
            require(countRoutable(LUMBRIDGE_SWAMP, 8, false) > 0, "Lumbridge starter mine");
            require(countRoutable(VARROCK_EAST_MINE, 8, false) > 0, "Varrock east starter mine");
            require(countRoutable(VARROCK_EAST_BANK, 12, true) > 0, "Varrock east bank");
            require(countRoutable(EDGEVILLE_BANK, 12, true) > 0, "Edgeville bank");
            checkNativeMining();
            checkNativeBankCapacity();
            checkUtilityBindings();
            checkCoverage();
            System.out.println("PASS " + checks + " native definition, routing, Mining, bank and utility-skill assertions.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void loadFixture(Path fixture) throws Exception {
        Thread.currentThread().setName("server-worker #1");
        Server.dataFolder = fixture.resolve("data").toFile();
        Server.fileStore = new FileStore(fixture.resolve("Cache").toString(), 8 * 1024 * 1024);
        World.type = WorldType.PVP;
        World.stage = WorldStage.DEV;
        Varpbit.load();
        InterfaceDef.load();
        ObjectDef.load();
        ItemDef.load();
        NPCDef.load();

        Map<Integer, int[]> keys;
        try (Reader reader = Files.newBufferedReader(fixture.resolve("data/region_keys.json"), StandardCharsets.UTF_8)) {
            keys = new Gson().fromJson(reader, new TypeToken<Map<Integer, int[]>>() {}.getType());
        }
        for (int i = 0; i < Region.LOADED.length; i++) {
            Region region = new Region(i);
            region.keys = keys.get(i);
            region.empty = true;
            Region.LOADED[i] = region;
        }
        Set<Integer> sampleRegions = new HashSet<>();
        for (Position pos : new Position[]{LUMBRIDGE_SWAMP, VARROCK_EAST_MINE, VARROCK_EAST_BANK, EDGEVILLE_BANK, new Position(2660,3311,0), new Position(2841,4830,0), new Position(2475,3429,0), new Position(3019,3339,0), new Position(3019,9739,0), new Position(3058,3376,0), new Position(3059,9776,0), new Position(3046,9756,0)}) {
            for (int dx = -12; dx <= 12; dx++) {
                for (int dy = -12; dy <= 12; dy++)
                    sampleRegions.add(Region.getId(pos.getX() + dx, pos.getY() + dy));
            }
        }
        for (int region : sampleRegions)
            Region.get(region).init();
        MapListener.LISTENERS = new MapListener[0];
        Class.forName(Mining.class.getName());
        Class.forName("io.ruin.model.item.containers.bank.BankActions");
    }

    private static void checkPickaxes() {
        Probe player = new Probe(LUMBRIDGE_SWAMP);
        require(Pickaxe.find(player) == null, "empty inventory has no pickaxe");
        player.inventory.add(Pickaxe.STEEL.id, 1);
        require(Pickaxe.find(player) == null, "level one excludes steel pickaxe");
        player.inventory.add(Pickaxe.BRONZE.id, 1);
        require(Pickaxe.find(player) == Pickaxe.BRONZE, "level one uses a real bronze pickaxe");
        player.stats.set(StatType.Mining, Pickaxe.STEEL.levelReq, 0);
        require(Pickaxe.find(player) == Pickaxe.STEEL, "eligible stronger pickaxe takes priority");
        player.equipment.set(Equipment.SLOT_WEAPON, player.inventory.findItem(Pickaxe.BRONZE.id).copy());
        player.inventory.remove(Pickaxe.BRONZE.id, 1);
        player.stats.set(StatType.Mining, 1, 0);
        require(Pickaxe.find(player) == Pickaxe.BRONZE, "equipped pickaxe is eligible");
    }

    private static int countRoutable(Position start, int radius, boolean bank) {
        Probe player = new Probe(start);
        require((ClipUtils.REGULAR.getAbs(start.getX(), start.getY(), start.getZ()) &
                (RouteFinder.UNMOVABLE_MASK | RouteFinder.OBJECT_MASK | RouteFinder.DECORATION_MASK)) == 0,
                "unblocked start " + start);
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                Tile tile = Tile.get(start.getX() + dx, start.getY() + dy, start.getZ());
                if (tile == null || tile.gameObjects == null) continue;
                for (GameObject object : tile.gameObjects) {
                    ObjectDef def = object.getDef();
                    if (object.isRemoved() || def == null || def.name == null || def.varpBitId != -1 || def.varpId != -1)
                        continue;
                    Rock rock = bank ? null : MiningAutomation.rockFor(object.id, def.name);
                    if (bank ? !def.bank : rock == null || rock.levelReq > 1) continue;
                    int option = def.getOption(bank ? "use" : "mine");
                    if (bank && option == -1) option = def.getOption("bank");
                    if (option == -1 || binding(object, option - 1) == null) continue;
                    try {
                        RouteType route = player.getRouteFinder().routeObject(object);
                        if (!route.reachable) continue;
                        require(route.finishX > 0 && route.finishY > 0, "native route yielded an approach");
                        require(object.x == start.getX() + dx && object.y == start.getY() + dy, "cache object origin");
                        found++;
                        if (found == 1)
                            System.out.printf("%s from (%d,%d,%d): %s #%d at (%d,%d), approach (%d,%d)%n",
                                    bank ? "Bank" : "Starter rock", start.getX(), start.getY(), start.getZ(), def.name,
                                    object.id, object.x, object.y, route.finishX, route.finishY);
                    } finally {
                        player.movement.reset();
                    }
                }
            }
        }
        return found;
    }

    private static void checkNativeMining() {
        Probe player = new Probe(new Position(3223, 3147, 0));
        player.inventory.add(Pickaxe.BRONZE.id, 1);
        MiningAutomation run = new MiningAutomation(player, 15, 0);
        require(run.pulse(0) == null, "native Mining selection continues");
        AutomationActionSource lease = (AutomationActionSource) (Object) player;
        io.ruin.process.event.Event mining = lease.ownerAutomationAction();
        require(mining != null, "real rock interaction scheduled an owned Mining event");
        require(run.activity().startsWith("Mining "), "actual native mining action");
        for (int tick = 1; tick <= 250 && player.stats.get(StatType.Mining).experience == 0; tick++) {
            if (!mining.tick()) break;
        }
        require(player.stats.get(StatType.Mining).experience > 0, "native event awarded XP");
        require(player.inventory.getAmount(436) + player.inventory.getAmount(438) > 0, "native event awarded ore");
        run.stop();
        require(lease.ownerAutomationAction() == null, "stop canceled only the owned native event");
    }

    private static void checkNativeBankCapacity() {
        Probe player = new Probe(VARROCK_EAST_BANK);
        io.ruin.model.item.containers.bank.Bank bank = player.getBank();
        bank.init(player, 1, -1, -1, -1, true);
        player.inventory.add(436, 2);
        player.inventory.add(438, 1);
        require(bank.deposit(player.inventory.findItem(436), 1, false) == 1, "native deposit moves ore");
        require(bank.getAmount(436) == 1 && player.inventory.getAmount(436) == 1, "copper conserved");
        require(bank.deposit(player.inventory.findItem(438), 1, false) == 0, "native full bank rejects new stack");
        require(player.inventory.getAmount(438) == 1 && bank.getAmount(438) == 0, "capacity failure conserves tin");
    }

    private static void checkUtilityBindings() throws Exception {
        Class.forName("io.ruin.model.skills.agility.courses.GnomeStrongholdCourse");
        Class.forName("io.ruin.model.skills.thieving.Stall");
        Class.forName("io.ruin.model.skills.runecrafting.Altars");
        require(UtilitySkillAutomation.resumeCourseStep(-1) == 0, "fresh Gnome lap");
        require(UtilitySkillAutomation.resumeCourseStep(23145) == 1, "progress after Gnome log");
        require(UtilitySkillAutomation.resumeCourseStep(23560) == 5, "progress to exit net");
        require(UtilitySkillAutomation.resumeCourseStep(23135) == 6, "progress to exit pipe");
        require(findBound(new Position(2475,3436,0), 23145, "walk-across") != null, "native Gnome first obstacle");
        GameObject stall = findBound(new Position(2660,3311,0), 11730, "steal-from");
        if (stall == null) stall = findBound(new Position(2660,3311,0), 11730, "steal from");
        require(stall != null, "native Ardougne baker stall");
        require(findBound(new Position(2841,4830,0), 34760, "craft-rune") != null, "native air rune altar");
    }

    private static void checkCoverage() throws Exception {
        java.lang.reflect.Method supported = OwnerAutomation.class.getDeclaredMethod("supported", StatType.class);
        supported.setAccessible(true);
        for (StatType skill : StatType.values())
            require((Boolean) supported.invoke(null, skill), "available driver for " + skill);
    }

    private static GameObject findBound(Position center, int id, String option) {
        for (int x = center.getX() - 12; x <= center.getX() + 12; x++) {
            for (int y = center.getY() - 12; y <= center.getY() + 12; y++) {
                GameObject object = Tile.getObject(id, x, y, center.getZ());
                if (object == null) continue;
                int index = object.getDef().getOption(option) - 1;
                if (index >= 0 && binding(object, index) != null) return object;
            }
        }
        return null;
    }

    private static ObjectAction binding(GameObject object, int index) {
        if (object.actions != null && object.actions[index] != null)
            return object.actions[index];
        ObjectAction[] defaults = object.getDef().defaultActions;
        return defaults == null ? null : defaults[index];
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static final class Probe extends Player {
        final PlayerMovement movement = new PlayerMovement(this);
        final Inventory inventory = new Inventory();
        final Equipment equipment = new Equipment();
        final StatList stats = new StatList();
        final io.ruin.model.item.containers.bank.Bank bank = new io.ruin.model.item.containers.bank.Bank();

        Probe(Position position) {
            this.position = position.copy();
            inventory.init(28, false);
            equipment.init(14, false);
            stats.init(this);
        }

        @Override public PlayerMovement getMovement() { return movement; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public Equipment getEquipment() { return equipment; }
        @Override public StatList getStats() { return stats; }
        @Override public io.ruin.model.item.containers.bank.Bank getBank() { return bank; }
        @Override public io.ruin.model.entity.player.GameMode getGameMode() { return io.ruin.model.entity.player.GameMode.STANDARD; }
        @Override public void face(GameObject object) {}
        @Override public int animate(int id, int delay) { return 0; }
        @Override public void graphics(int id, int height, int delay) {}
        @Override public void sendMessage(String message) {}
        @Override public void sendFilteredMessage(String message) {}
        @Override public boolean isMovementBlocked(boolean message, boolean ignoreFreeze) { return false; }
    }
}
