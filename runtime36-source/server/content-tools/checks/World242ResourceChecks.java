import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.ItemDef;
import io.ruin.cache.ObjectDef;
import io.ruin.content.content242.Content242Registry;
import io.ruin.content.content242.World242ObjectActions;
import io.ruin.model.World;
import io.ruin.model.map.object.actions.ObjectAction;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/** Detached checks against the installed cache, mappings, and native skill initializers. */
public final class World242ResourceChecks {
    private static int checks;

    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Server.dataFolder = new File(args[0], "data");
        Server.fileStore = new FileStore(new File(args[0], "Cache").getPath());
        World.type = WorldType.PVP;
        ItemDef.load();
        ObjectDef.load();
        require(Content242Registry.current().feature("world"), "World integration is enabled");
        Class.forName("io.ruin.model.skills.mining.Mining");
        Class.forName("io.ruin.model.skills.woodcutting.Woodcutting");
        Class.forName("io.ruin.model.skills.cooking.Cooking");

        int[] rocks = {38327, 38338, 38346, 38347, 38348, 38349, 38350, 38351, 38352, 38353,
                38354, 38355, 38356, 38357, 38358, 38359, 38360, 38361, 38362, 38363, 38364, 38365,
                38366, 38367, 38368, 38369};
        for (int id : rocks) action(id, "Mine");
        int[] trees = {37648, 37649, 37650, 37652, 37653, 37745, 38131, 38132, 38213, 38316,
                38318, 38319, 38320, 38321, 38322};
        for (int id : trees) action(id, "Chop down");
        int[] ranges = {37995, 37996, 38207, 38804, 39090, 39093, 39169, 39507, 39764, 40598, 40607};
        for (int id : ranges) {
            action(id, "Cook");
            require(ObjectDef.get(id).itemActions.containsKey(317), "Range delegates to existing raw shrimp cooking");
            require(!ObjectDef.get(id).itemActions.containsKey(30086), "Range excludes cauldron-only molten eel");
        }

        require(ObjectDef.get(38366).name.equals("Sandstone rocks"), "Sandstone target uses the modern definition");
        require(ObjectDef.get(38367).name.equals("Granite rocks"), "Granite target uses the modern definition");
        action(11364, "Mine");
        action(1276, "Chop down");
        require(ObjectDef.get(50006).defaultActions != null, "Custom rock actions remain registered");
        require(ObjectDef.get(50008).defaultActions != null, "Custom tree actions remain registered");
        require(ObjectDef.get(41318).defaultActions == null, "Conflicting source ID gains no mining action");
        require(ObjectDef.get(41333).defaultActions == null, "Conflicting source ID gains no chopping action");

        ObjectAction existing = action(38327, "Mine");
        World242ObjectActions.bind(10943, "mine", def -> true, (p, obj) -> {});
        require(existing == action(38327, "Mine"), "An explicit existing action takes precedence");

        AtomicInteger selected = new AtomicInteger(-1);
        World242ObjectActions.bind(10943, 11391, "mine", def -> def.name.equals("Copper rocks"),
                def -> def.name.equalsIgnoreCase("rocks"), id -> {
                    selected.set(id);
                    return existing;
                });
        require(selected.get() == 38369, "Mining prefers the matching mapped depleted rock");
        World242ObjectActions.bind(1276, 1342, "chop down", def -> def.name.equals("Tree"),
                def -> def.name.equalsIgnoreCase("tree stump"), id -> {
                    selected.set(id);
                    return existing;
                });
        require(selected.get() == 1342, "Woodcutting uses the defined native stump when no alias exists");
        System.out.println("PASS " + checks + " detached cache and native action checks");
    }

    private static ObjectAction action(int id, String option) {
        ObjectDef def = ObjectDef.get(id);
        int slot = def.getOption(option);
        require(slot > 0 && def.defaultActions != null && def.defaultActions[slot - 1] != null,
                "Missing " + option + " action for " + id);
        return def.defaultActions[slot - 1];
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
