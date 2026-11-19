package io.ruin.model.activities.automation;

import io.ruin.model.entity.player.GameMode;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.entity.player.PlayerMovement;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.skills.construction.Buildable;
import io.ruin.model.skills.construction.House;
import io.ruin.model.skills.farming.Farming;
import io.ruin.model.skills.farming.crop.impl.AllotmentCrop;
import io.ruin.model.skills.farming.patch.Patch;
import io.ruin.model.skills.farming.patch.PatchData;
import io.ruin.network.PacketSender;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.Inventory;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatList;
import io.ruin.model.stat.StatType;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** Executes native production actions on detached loaded-cache players without networking or saves. */
public final class OwnerAutomationProductionChecks {
    private static int assertions;

    public static void main(String[] args) {
        try {
            Method load = OwnerAutomationNativeChecks.class.getDeclaredMethod("loadFixture", Path.class);
            load.setAccessible(true);
            load.invoke(null, Paths.get(args[0]));
            for (String skill : new String[]{"cooking.Cooking", "smithing.SmeltBar", "herblore.Potion"})
                Class.forName("io.ruin.model.skills." + skill);

            Position open = new Position(3229, 3147, 0);
            check(StatType.Crafting, 1, open, 1625, 1609, new int[]{1755, 1625});
            check(StatType.Fletching, 1, open, 1511, 52, new int[]{946, 1511});
            check(StatType.Fletching, 5, open, 50, 841, new int[]{50, 1777});
            check(StatType.Herblore, 1, open, 199, 249, new int[]{199});
            check(StatType.Herblore, 1, open, 91, 121, new int[]{91, 221});
            for (Position crack : new Position[]{new Position(1706,10077,0), new Position(1716,10057,0),
                    new Position(1646,10001,0), new Position(1648,10008,0)})
                Region.get(Region.getId(crack.getX(), crack.getY())).init();
            check(StatType.Prayer, 1, open, 526, -1, new int[]{526});
            check(StatType.Cooking, 50, approach(new Position(3211, 3213, 0), "range"), 317, 315, new int[]{317});
            check(StatType.Smithing, 1, approach(new Position(3109, 3499, 0), "furnace"), 436, 2349,
                    new int[]{436, 438});
            check(StatType.Firemaking, 25, open, 1511, -1, new int[]{590, 1511});
            checkConstruction();
            checkFarming();
            Probe noSupplies = new Probe(open);
            require(new ProductionAutomation(noSupplies, StatType.Crafting, 99, 0).pulse(0) != null,
                    "missing supplies stops promptly");
            require(noSupplies.getInventory().isEmpty(), "no rewards with empty inventory");
            System.out.println("PASS " + assertions + " native production assertions across nine skills.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void check(StatType type, int level, Position position, int input, int output, int[] stocks) {
        Probe p = new Probe(position);
        p.getStats().set(type, level, Stat.xpForLevel(level));
        for (int id : stocks) p.getInventory().add(id, 1);
        ProductionAutomation driver = new ProductionAutomation(p, type, 99, 0);
        int startingAmount = p.getInventory().getAmount(input);
        double startingXp = p.getStats().get(type).experience;
        String stop = driver.pulse(0);
        require(stop == null, type + " start: " + stop);
        for (int ticks = 0; ticks < 70 && p.getStats().get(type).experience <= startingXp; ticks++) {
            p.advanceAction();
            if (!p.getMovement().isAtDestination()) p.finishStep();
        }
        require(p.getStats().get(type).experience > startingXp, type + " native XP");
        require(p.getInventory().getAmount(input) < startingAmount, type + " consumed input");
        if (output != -1) require(p.getInventory().getAmount(output) > 0, type + " native output " + output);
        for (int ticks = 0; ticks < 4 && p.isLocked(); ticks++) p.advanceAction();
        driver.stop();
        require(!p.isLocked(), type + " native lock released");
        require(!p.hasActiveAction(), type + " owned event released");
        System.out.println(type + " verified");
    }

    private static void checkConstruction() throws ClassNotFoundException {
        io.ruin.cache.AnimDef.load();
        Class.forName("io.ruin.model.skills.construction.actions.Workshop");
        Position stand = new Position(3229, 3147, 0);
        Probe p = new Probe(stand);
        p.currentHouse = new House();
        p.admin = true;
        p.getInventory().add(2347, 1);
        p.getInventory().add(8794, 1);
        require(ConstructionAutomation.chairSlot(p, 20) == -1, "admin cannot waive chair materials");
        p.getInventory().add(960, 2);
        p.getInventory().add(4819, 2);
        require(ConstructionAutomation.chairSlot(p, 20) == 0, "planks and nails qualify");
        Region region = stand.getRegion();
        region.setHouse(p.currentHouse);
        int benchId = Buildable.WOODEN_WORKBENCH.getBuiltObjects()[0];
        GameObject bench = new GameObject(benchId, stand.getX() + 1, stand.getY(), 0, 10, 0).spawn();
        try {
            RouteType approach = p.getRouteFinder().routeObject(bench);
            require(approach.reachable, "fixture workbench approach");
            p.arrive(approach.finishX, approach.finishY);
            ConstructionAutomation driver = new ConstructionAutomation(p, 99, 0);
            String stop = driver.pulse(0);
            require(stop == null, "construction started: " + stop);
            require(p.hasActiveAction(), "construction scheduled native action");
            for (int i = 0; i < 24; i++) p.advanceAction();
            require(p.getInventory().getAmount(8496) == 1, "admin can produce exactly one paid flatpack");
            require(p.getInventory().getAmount(960) == 0 && p.getInventory().getAmount(4819) == 0,
                    "flatpack consumes exact materials");
            require(p.getStats().get(StatType.Construction).experience > 0, "native Construction XP");
            require(!p.hasActiveAction(), "native repeating event cancelled at delay before it could produce free stock");
            driver.stop();
            System.out.println("Construction verified");
        } finally {
            bench.remove();
            region.setHouse(null);
        }
    }

    private static void checkFarming() {
        int objectId = PatchData.FALADOR_NORTH.getObjectId();
        Position centre = new Position(3055, 3308, 0);
        Probe scout = new Probe(centre);
        Set<Integer> nearby = new HashSet<>();
        for (int dx = -8; dx <= 8; dx++)
            for (int dy = -8; dy <= 8; dy++)
                nearby.add(Region.getId(centre.getX() + dx, centre.getY() + dy));
        for (int id : nearby)
            Region.get(id).init();
        GameObject allotment = null;
        for (int dx = -8; dx <= 8 && allotment == null; dx++)
            for (int dy = -8; dy <= 8 && allotment == null; dy++)
                allotment = Tile.getObject(objectId, centre.getX() + dx, centre.getY() + dy, 0);
        require(allotment != null, "Falador allotment exists");
        RouteType route = scout.getRouteFinder().routeObject(allotment);
        require(route.reachable, "Falador allotment has native approach");
        Probe p = new Probe(new Position(route.finishX, route.finishY, 0));
        p.getFarming().init(p);
        Patch patch = p.getFarming().getPatch(allotment);
        require(patch != null, "player owns a real allotment state");
        patch.setRaked(3);
        p.getInventory().add(5343, 1);
        p.getInventory().add(952, 1);
        p.getInventory().add(AllotmentCrop.POTATO.getSeed(), 3);
        FarmingAutomation driver = new FarmingAutomation(p, 99, 0);
        String result = driver.pulse(0);
        require(result == null && p.hasActiveAction(), "plant event started: " + result);
        for (int i = 0; i < 5; i++) p.advanceAction();
        require(patch.getPlantedCrop() == AllotmentCrop.POTATO, "native event planted a potato crop");
        require(p.getInventory().getAmount(AllotmentCrop.POTATO.getSeed()) == 0, "native three-seed requirement consumed exactly");
        require(p.getStats().get(StatType.Farming).experience > 0, "planting awarded native XP");
        require(driver.pulse(5) != null && !p.hasActiveAction(), "no premature harvest");
        driver.stop();

        // Make the same detached crop mature so a real harvest can run without waiting for the clock.
        patch.setStage(AllotmentCrop.POTATO.getTotalStages());
        patch.setProduceCount(1);
        driver = new FarmingAutomation(p, 99, 6);
        result = driver.pulse(6);
        require(result == null && p.hasActiveAction(), "harvest event started: " + result);
        for (int i = 0; i < 8; i++) p.advanceAction();
        require(p.getInventory().getAmount(AllotmentCrop.POTATO.getProduceId()) == 1, "mature harvest received produce");
        require(patch.getProduceCount() == 0, "no second produce remained");
        driver.stop();
        System.out.println("Farming verified");
    }

    private static Position approach(Position start, String type) {
        Set<Integer> nearby = new HashSet<>();
        for (int dx = -9; dx <= 9; dx++)
            for (int dy = -9; dy <= 9; dy++)
                nearby.add(Region.getId(start.getX() + dx, start.getY() + dy));
        for (int id : nearby)
            Region.get(id).init();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                Tile tile = Tile.get(start.getX() + dx, start.getY() + dy, start.getZ());
                if (tile == null || tile.gameObjects == null) continue;
                for (GameObject obj : tile.gameObjects) {
                    String name = obj.getDef().name;
                    if (!type.equalsIgnoreCase(name) && !("range".equals(type)
                            && ("cooking range".equalsIgnoreCase(name) || "stove".equalsIgnoreCase(name)))) continue;
                    System.out.println("Candidate " + name + " " + obj.id + " at " + obj.x + ", " + obj.y);
                    Probe p = new Probe(start);
                    RouteType route = p.getRouteFinder().routeObject(obj);
                    if (route.reachable) {
                        System.out.println("Reachable " + type + " " + obj.id + " at " + obj.x + ", " + obj.y);
                        return new Position(route.finishX, route.finishY, start.getZ());
                    }
                }
            }
        }
        throw new AssertionError("No validated " + type + " near " + start);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        assertions++;
    }

    private static final class Probe extends Player {
        private final PlayerMovement movement = new PlayerMovement(this);
        private final Inventory inventory = new Inventory();
        private final Equipment equipment = new Equipment();
        private final StatList stats = new StatList();
        private final Farming farming = new Farming();
        private final PacketSender packets = new PacketSender(this, null) {
            @Override public void sendClientScript(int id, String type, Object... params) {}
        };
        private int mainInterface = -1;
        House currentHouse;
        boolean admin;
        private final PlayerCombat combat = new PlayerCombat() {
            @Override public void updateLevel() {}
        };

        Probe(Position pos) {
            position = pos.copy();
            varps = new int[12000];
            inventory.init(28, false);
            equipment.init(14, false);
            stats.init(this);
        }

        void arrive(int x, int y) { position = new Position(x, y, getHeight()); movement.reset(); }
        void advanceAction() { processEvent(); }
        void finishStep() { movement.reset(); }
        @Override public PlayerMovement getMovement() { return movement; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public Equipment getEquipment() { return equipment; }
        @Override public StatList getStats() { return stats; }
        @Override public Farming getFarming() { return farming; }
        @Override public void updateVarp(int id) {}
        @Override public PlayerCombat getCombat() { return combat; }
        @Override public GameMode getGameMode() { return GameMode.STANDARD; }
        @Override public void face(GameObject object) {}
        @Override public int animate(int id, int delay) { return 0; }
        @Override public void graphics(int id, int height, int delay) {}
        @Override public void sendMessage(String message) { System.out.println(message); }
        @Override public void sendFilteredMessage(String message) {}
        @Override public void privateSound(int soundId) {}
        @Override public boolean isAdmin() { return admin; }
        @Override public boolean isInOwnHouse() { return currentHouse != null; }
        @Override public House getCurrentHouse() { return currentHouse; }
        @Override public PacketSender getPacketSender() { return packets; }
        @Override public void openInterface(InterfaceType type, int id) {
            if (type == InterfaceType.MAIN) mainInterface = id;
        }
        @Override public boolean isVisibleInterface(int id) { return id == mainInterface; }
        @Override public void closeInterfaces() { mainInterface = -1; }
        @Override public void closeInterface(InterfaceType type) {
            if (type == InterfaceType.MAIN) mainInterface = -1;
        }
        @Override public void resetActions(boolean close, boolean face, boolean combat) { stopEvent(combat); }
        @Override public boolean isMovementBlocked(boolean message, boolean ignoreFreeze) { return false; }
    }
}
