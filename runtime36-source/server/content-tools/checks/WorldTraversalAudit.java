import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Records registered actions on actual loaded world objects without invoking them. */
public final class WorldTraversalAudit {
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
                || !System.getProperty("user.home", "").replace('\\', '/').endsWith("runtime-private-expedition/home"))
            throw new IllegalStateException("Expected the isolated Windows staging world");
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            ClassLoader loader = loaded.getClassLoader();
            if (!loaded.getName().equals("io.ruin.Server") || loader == null
                    || !loader.getClass().getName().equals("kilim.WeavingClassLoader")) continue;
            Object worker = loaded.getField("worker").get(null);
            if (worker == null) continue;
            ExecutorService executor = (ExecutorService) worker.getClass().getMethod("getExecutor").invoke(worker);
            executor.submit(() -> scan(loader, Paths.get(output))).get(40, TimeUnit.SECONDS);
            return;
        }
        throw new IllegalStateException("Native worker not available");
    }

    private static int scan(ClassLoader loader, Path output) throws Exception {
        Class<?> region = loader.loadClass("io.ruin.model.map.Region");
        Class<?> tile = loader.loadClass("io.ruin.model.map.Tile");
        Class<?> object = loader.loadClass("io.ruin.model.map.object.GameObject");
        Class<?> def = loader.loadClass("io.ruin.cache.ObjectDef");
        Field tiles = region.getField("tiles");
        Field dynamic = region.getField("dynamicIndex");
        Field objects = tile.getField("gameObjects");
        Field[] pos = fields(object, "id", "x", "y", "z", "type", "direction");
        Field[] properties = fields(def, "name", "doorOppositeId", "doorClosed", "clipType", "modelIds", "options");
        Field local = object.getField("actions");
        Field defaults = def.getField("defaultActions");
        Method getDef = object.getMethod("getDef");
        int written = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("id\tx\ty\tz\ttype\tdirection\tname\topposite\tclosed\tclip_type\tmodels\toptions\tinstance_handlers\tdefinition_handlers\n");
            for (Object square : (Object[]) region.getField("LOADED").get(null)) {
                if (square == null || dynamic.getInt(square) != -1) continue;
                Object[][][] floors = (Object[][][]) tiles.get(square);
                if (floors == null) continue;
                for (Object[][] floor : floors) for (Object[] row : floor) for (Object cell : row) {
                    if (cell == null) continue;
                    Iterable<?> placements = (Iterable<?>) objects.get(cell);
                    if (placements == null) continue;
                    for (Object placement : placements) {
                        if (pos[0].getInt(placement) < 0) continue;
                        Object definition = getDef.invoke(placement);
                        if (definition == null) continue;
                        String[] options = (String[]) properties[5].get(definition);
                        if (options == null || Arrays.stream(options).allMatch(o -> o == null || o.isEmpty())) continue;
                        for (Field value : pos) writer.write(value.getInt(placement) + "\t");
                        for (int i = 0; i < 4; i++) writer.write(clean(properties[i].get(definition)) + "\t");
                        writer.write(Arrays.toString((int[]) properties[4].get(definition)) + "\t");
                        writer.write(String.join("|", Arrays.stream(options).map(WorldTraversalAudit::clean).toArray(String[]::new)) + "\t");
                        writer.write(handlers((Object[]) local.get(placement)) + "\t" + handlers((Object[]) defaults.get(definition)) + "\n");
                        written++;
                    }
                }
            }
        }
        if (written == 0) throw new IllegalStateException("No interactive world objects loaded");
        writeDefinitions(def, properties, defaults, output.resolveSibling(output.getFileName() + ".definitions.tsv"));
        return written;
    }

    private static void writeDefinitions(Class<?> type, Field[] properties, Field defaults, Path output) throws Exception {
        Field id = type.getField("id");
        Field[] geometry = fields(type, "xLength", "yLength", "verticalFlip", "doorReversed");
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("id\tname\topposite\tclosed\tclip_type\tx_length\ty_length\tvertical_flip\tdoor_reversed\tmodels\toptions\tdefinition_handlers\n");
            for (Object definition : ((Map<?, ?>) type.getField("LOADED").get(null)).values()) {
                String[] options = (String[]) properties[5].get(definition);
                if (options == null || Arrays.stream(options).noneMatch(o -> o != null && !o.isEmpty())) continue;
                writer.write(id.getInt(definition) + "\t");
                for (int i = 0; i < 4; i++) writer.write(clean(properties[i].get(definition)) + "\t");
                for (Field value : geometry) writer.write(clean(value.get(definition)) + "\t");
                writer.write(Arrays.toString((int[]) properties[4].get(definition)) + "\t");
                writer.write(String.join("|", Arrays.stream(options).map(WorldTraversalAudit::clean).toArray(String[]::new)) + "\t");
                writer.write(handlers((Object[]) defaults.get(definition)) + "\n");
            }
        }
    }

    private static String handlers(Object[] values) {
        if (values == null) return "";
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i] == null ? "" : values[i].getClass().getName().split("\\$\\$Lambda\\$", 2)[0];
        }
        return String.join("|", names);
    }

    private static String clean(Object value) {
        return value == null ? "" : value.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private static Field[] fields(Class<?> type, String... names) throws Exception {
        Field[] result = new Field[names.length];
        for (int i = 0; i < names.length; i++) result[i] = type.getField(names[i]);
        return result;
    }
}
