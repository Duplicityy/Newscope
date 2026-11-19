import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Read-only world snapshot from the initialized, isolated Windows server. */
public final class WorldNpcAudit {
    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            vm.loadAgent(args[1], args[2]);
        } finally {
            vm.detach();
        }
    }

    public static void agentmain(String output, Instrumentation instrumentation) throws Exception {
        if (!"127.0.0.1".equals(System.getProperty("kronos.bind"))
                || !"16302".equals(System.getProperty("kronos.port"))
                || Boolean.getBoolean("kronos.public")
                || !System.getProperty("user.home", "").replace('\\', '/').endsWith("runtime-private-expedition/home")) {
            throw new IllegalStateException("Expected the isolated Windows world");
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            ClassLoader loader = type.getClassLoader();
            if (!type.getName().equals("io.ruin.Server") || loader == null
                    || !loader.getClass().getName().equals("kilim.WeavingClassLoader")) {
                continue;
            }
            Object worker = type.getField("worker").get(null);
            if (worker == null) continue;
            ExecutorService executor = (ExecutorService) worker.getClass().getMethod("getExecutor").invoke(worker);
            Map<String, String> snapshot = executor.submit(() -> inspect(loader)).get(30, TimeUnit.SECONDS);
            Path directory = Paths.get(output);
            Files.createDirectories(directory);
            for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                Files.write(directory.resolve(entry.getKey()), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        throw new IllegalStateException("Native worker unavailable");
    }

    private static Map<String, String> inspect(ClassLoader loader) throws Exception {
        Class<?> travel = loader.loadClass("io.ruin.content.content242.WorldTravel242");
        if (!Boolean.TRUE.equals(travel.getMethod("available").invoke(null))) {
            throw new IllegalStateException("The world startup has not loaded its travel bindings");
        }
        Class<?> defType = loader.loadClass("io.ruin.cache.NPCDef");
        Class<?> npcType = loader.loadClass("io.ruin.model.entity.npc.NPC");
        Class<?> combatType = loader.loadClass("io.ruin.model.entity.npc.NPCCombat");
        Class<?> posType = loader.loadClass("io.ruin.model.map.Position");
        Class<?> infoType = loader.loadClass("io.ruin.data.impl.npcs.npc_combat$Info");
        Class<?> worldType = loader.loadClass("io.ruin.model.World");
        Field[] def = fields(defType, "id", "name", "models", "combatLevel", "options", "attackOption", "standAnimation",
                "walkAnimation", "walkBackAnimation", "walkLeftAnimation", "walkRightAnimation", "anInt3564", "anInt3565",
                "combatHandlerClass", "combatInfo", "defaultActions", "anagram", "cryptic", "showIds");
        Field[] info = fields(infoType, "attack_animation", "defend_animation", "death_animation", "attack_style");
        Field[] npc = fields(npcType, "defaultSpawn", "walkRange", "actions");
        Method getDef = npcType.getMethod("getDef");
        Method getCombat = npcType.getMethod("getCombat");
        Method getCombatInfo = combatType.getMethod("getInfo");
        Method getSpawn = npcType.getMethod("getSpawnPosition");
        Method x = posType.getMethod("getX"), y = posType.getMethod("getY"), z = posType.getMethod("getZ");
        StringBuilder defs = new StringBuilder("id\tname\tmodels\tcombat_level\toptions\tattack_option\tstand\twalk\tback\tleft\tright\tturn_a\tturn_b\thandler\tattack\tdefend\tdeath\tstyle\tdefinition_action_slots\tclue\tmorphs\n");
        for (Object d : ((Map<?, ?>) defType.getField("cached").get(null)).values()) {
            Object combat = def[14].get(d);
            row(defs, def[0].get(d), def[1].get(d), vector(def[2].get(d)), def[3].get(d),
                    vector(def[4].get(d)), def[5].get(d), def[6].get(d), def[7].get(d), def[8].get(d),
                    def[9].get(d), def[10].get(d), def[11].get(d), def[12].get(d), className(def[13].get(d)),
                    value(info[0], combat), value(info[1], combat), value(info[2], combat), value(info[3], combat),
                    slots(def[15].get(d)), def[16].get(d) != null || def[17].get(d) != null, vector(def[18].get(d)));
        }
        StringBuilder spawns = new StringBuilder("id\tdefault_spawn\tx\ty\tz\twalk_range\tinstance_action_slots\tcombat_class\tattack\tdefend\tdeath\tstyle\n");
        int spawnCount = 0;
        for (Object n : (Iterable<?>) worldType.getField("npcs").get(null)) {
            Object d = getDef.invoke(n);
            Object p = getSpawn.invoke(n);
            if (d == null || p == null) continue;
            spawnCount++;
            Object combat = getCombat.invoke(n);
            Object combatInfo = combat == null ? null : getCombatInfo.invoke(combat);
            row(spawns, def[0].get(d), npc[0].get(n), x.invoke(p), y.invoke(p), z.invoke(p), npc[1].get(n),
                    slots(npc[2].get(n)), combat == null ? "" : combat.getClass().getName(), value(info[0], combatInfo),
                    value(info[1], combatInfo), value(info[2], combatInfo), value(info[3], combatInfo));
        }
        if (spawnCount == 0) throw new IllegalStateException("The world startup has not loaded any NPC spawns");
        StringBuilder summary = new StringBuilder("definition\ttotal\tnamed\texamine_field_nonempty\n");
        summarizeDefinitions(summary, loader.loadClass("io.ruin.cache.ItemDef"), true);
        summarizeDefinitions(summary, loader.loadClass("io.ruin.cache.ObjectDef"), false);
        summarizeDefinitions(summary, defType, false);
        Map<String, String> snapshot = new TreeMap<>();
        snapshot.put("npc-definitions.tsv", defs.toString());
        snapshot.put("npc-spawns.tsv", spawns.toString());
        snapshot.put("definition-counts.tsv", summary.toString());
        return snapshot;
    }

    private static Field[] fields(Class<?> type, String... names) throws NoSuchFieldException {
        Field[] result = new Field[names.length];
        for (int i = 0; i < names.length; i++) result[i] = type.getField(names[i]);
        return result;
    }

    private static String vector(Object value) {
        if (value instanceof int[]) return Arrays.toString((int[]) value);
        if (value instanceof Object[]) return Arrays.toString((Object[]) value);
        return "";
    }

    private static String className(Object type) {
        return type == null ? "" : ((Class<?>) type).getName();
    }

    private static Object value(Field field, Object on) throws IllegalAccessException {
        return on == null ? "" : field.get(on);
    }

    private static String slots(Object actions) {
        if (actions == null) return "";
        Object[] array = (Object[]) actions;
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < array.length; i++) if (array[i] != null) slots.append(i + 1);
        return slots.toString();
    }

    private static void row(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i != 0) out.append('\t');
            if (cells[i] != null) out.append(cells[i].toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
        }
        out.append('\n');
    }

    private static void summarizeDefinitions(StringBuilder out, Class<?> type, boolean hasExamine) throws Exception {
        Field name = type.getField("name");
        Field examine = hasExamine ? type.getField("examine") : null;
        int[] counts = new int[3];
        type.getMethod("forEach", Consumer.class).invoke(null, (Consumer<Object>) definition -> {
            try {
                counts[0]++;
                if (nonempty(name.get(definition))) counts[1]++;
                if (examine != null && nonempty(examine.get(definition))) counts[2]++;
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        });
        row(out, type.getSimpleName(), counts[0], counts[1], hasExamine ? counts[2] : "n/a");
    }

    private static boolean nonempty(Object value) {
        if (value == null) return false;
        String text = value.toString().trim();
        return !text.isEmpty() && !"null".equalsIgnoreCase(text);
    }
}
