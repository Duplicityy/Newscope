package io.ruin.model.activities.dungeoneering;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Paths;

public final class ExpeditionChecks {
    private static int checks;

    private static void verify(boolean condition, String problem) {
        if (!condition) throw new AssertionError(problem);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        verify(DungeoneeringExpedition.skipCost(1, 1) == 4, "minimum skip cost");
        verify(DungeoneeringExpedition.skipCost(99, 50) == 19800, "level 99 skip cost");
        verify(DungeoneeringExpedition.skipCost(100, 50) == 25000, "level 100 skip cost");
        verify(DungeoneeringExpedition.skipCost(120, 60) == 43200, "level 120 skip cost");
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        DungeoneeringProgress legacy = gson.fromJson("{\"version\":1,\"tokens\":5,\"completedFloors\":[1]}", DungeoneeringProgress.class);
        legacy.normalize();
        verify(legacy.tokens == 5 && legacy.completedFloors.contains(1), "legacy progress preserved");
        verify(legacy.bestFloorTicks.length == 60 && legacy.smugglerStorage.length == 15, "nullable state migrated");
        verify(legacy.miningBeltTier == -1 && legacy.woodcuttingBeltTier == -1, "legacy belt defaults empty");
        legacy.bestFloorTicks[0] = 810;
        legacy.miningBeltTier = 4;
        legacy.lastFloorResult = "Completed";
        DungeoneeringProgress reloaded = gson.fromJson(gson.toJson(legacy), DungeoneeringProgress.class);
        reloaded.normalize();
        verify(reloaded.bestFloorTicks[0] == 810 && reloaded.miningBeltTier == 4 &&
            "Completed".equals(reloaded.lastFloorResult), "saved progression round trip");
        for (int tier = 0; tier <= 10; tier++) {
            verify(DungeoneeringExpeditionSupplies.toolTier(16295 + tier * 2, true) == tier, "mining native tier");
            verify(DungeoneeringExpeditionSupplies.toolTier(16361 + tier * 2, false) == tier, "woodcutting native tier");
            verify(DungeoneeringExpeditionSupplies.toolTier(16296 + tier * 2, true) == -1, "reject non-pickaxe");
            verify(DungeoneeringExpeditionSupplies.toolLevel(tier) == (tier == 10 ? 99 : Math.max(1, tier * 10)), "native tool level");
        }
        for (int[] boundary : new int[][]{{1,3},{9,3},{10,6},{39,6},{40,9},{79,9},{80,12},{109,12},{110,15},{120,15}})
            verify(DungeoneeringExpeditionSupplies.capacity(boundary[0]) == boundary[1], "storage capacity boundary");
        verify(DungeoneeringExpeditionSupplies.amountToStore(true, 80, 35) == 65, "partial stack");
        verify(DungeoneeringExpeditionSupplies.amountToStore(true, 100, 100) == 0, "complete stack");
        verify(DungeoneeringExpeditionSupplies.amountToStore(false, 1, 3) == 1, "independent equipment slot");
        DungeoneeringAssets assets = DungeoneeringAssets.load(Paths.get(args[0]));
        java.lang.reflect.Field combatAssets = DungeoneeringCombat.class.getDeclaredField("assets");
        combatAssets.setAccessible(true);
        combatAssets.set(null, assets);
        int options = 0;
        for (int floor = 1; floor <= 60; floor++) for (int complexity = 1; complexity <= 6; complexity++) {
            for (DungeoneeringAssets.Room room : assets.select("BossRoom", floor, complexity)) {
                if (room.doors.length != 1 || room.npcSpawns == null || room.npcSpawns.length == 0) continue;
                if (!java.util.Arrays.stream(room.npcSpawns).allMatch(spawn -> DungeoneeringCombat.supportedBoss(spawn.id))) continue;
                verify(room.minFloor <= floor && room.theme.equals(DungeoneeringAssets.theme(floor)), "native boss candidate scope");
                options++;
            }
        }
        verify(options > 0, "selectable native boss templates");
        System.out.println("PASS " + checks + " expedition checks; " + options + " compatible boss template combinations.");
    }
}