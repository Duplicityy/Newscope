import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ruin.Server;
import io.ruin.api.buffer.InBuffer;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.InterfaceDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.Varpbit;
import io.ruin.data.impl.world_examines;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Inventory;
import io.ruin.model.map.MapListener;
import io.ruin.network.incoming.Incoming;
import io.ruin.network.incoming.handlers.ActionButtonHandler;
import io.ruin.network.incoming.handlers.NPCActionHandler;
import io.ruin.network.incoming.handlers.ObjectActionHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/** Detached checks of actual examine encodings and non-destructive slot dispatch. */
public final class WorldExamineChecks {
    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        world_examines catalogue = new world_examines();
        String json = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        catalogue.fromJson("world-examines", json);
        check(world_examines.npc(" gobLIN ") != null, "NPC family case normalization");
        check(world_examines.object("Bank chest") != null, "Object description");
        check(world_examines.npc("Bank chest") == null, "Separate examine kinds");
        check(world_examines.object(null) == null && world_examines.object("null") == null, "No fabricated fallback");
        String before = world_examines.npc("Goblin");
        try {
            catalogue.fromJson("world-examines", "{\"schema\":1,\"npcs\":{\"Bad\":\"<br>\"},\"objects\":{}}");
            throw new AssertionError("Markup unexpectedly loaded");
        } catch (IllegalArgumentException expected) {
            check(before.equals(world_examines.npc("Goblin")), "Failed reload retained published data");
        }
        Incoming.OPTIONS[9] = 6;
        Incoming.OPTIONS[36] = 6;
        Incoming.OPTIONS[28] = 6;
        Server.fileStore = new FileStore(args[1], 8 * 1024 * 1024);
        World.type = WorldType.PVP;
        Varpbit.load();
        InterfaceDef.load();
        NPCDef.load();
        ObjectDef.load();
        MapListener.LISTENERS = new MapListener[0];
        AtomicInteger matchedObjects = new AtomicInteger();
        Set<String> reachableDescriptions = new TreeSet<>();
        ObjectDef.forEach(def -> {
            if (world_examines.object(def.name) != null) {
                matchedObjects.incrementAndGet();
                reachableDescriptions.add(def.name.trim().toLowerCase(Locale.ROOT));
            }
        });
        System.out.printf("Catalogue covers %d object families and %d loaded object IDs%n",
                reachableDescriptions.size(), matchedObjects.get());
        JsonObject authored = new JsonParser().parse(json).getAsJsonObject().getAsJsonObject("objects");
        for (String name : authored.keySet()) {
            if (!reachableDescriptions.contains(name.trim().toLowerCase(Locale.ROOT)))
                System.out.println("Unmatched object name: " + name);
        }
        Reader player = new Reader();
        NPCDef goblin = new NPCDef();
        goblin.id = 16383;
        goblin.name = "Goblin";
        NPCDef.cached.put(goblin.id, goblin);
        new NPCActionHandler().handle(player, new InBuffer(new byte[]{63, 127}), 9);
        check(before.equals(player.take()), "Transformed big-endian NPC examine");
        new NPCActionHandler().handle(player, new InBuffer(new byte[]{64, (byte)128}), 9);
        check(player.take() == null, "NPC protocol upper bound");

        ObjectDef chest = new ObjectDef();
        chest.id = 40000;
        chest.name = "Bank chest";
        ObjectDef.LOADED.put(chest.id, chest);
        int priorResets = player.resets;
        new ObjectActionHandler().handle(player, new InBuffer(new byte[]{64, (byte)156}), 36);
        check(world_examines.object("Bank chest").equals(player.take()), "Unsigned little-endian object examine");
        check(player.resets == priorResets, "Object examine preserves activity state");
        ObjectDef.LOADED.put(65535, chest);
        new ObjectActionHandler().handle(player, new InBuffer(new byte[]{(byte)255, (byte)255}), 36);
        check(player.take() == null, "Object no-target sentinel");

        ItemDef item = new ItemDef();
        item.id = 40000;
        item.examine = "Local fixture description";
        ItemDef.cached.put(item.id, item);
        ActionButtonHandler.InventoryHandler packet = new ActionButtonHandler.InventoryHandler();
        packet.handle(player, new InBuffer(new byte[]{64, (byte)156, 12, 0, 0, 0, 0, 24}), 28);
        check(item.examine.equals(player.take()), "Unsigned ground item ID with native scene coordinates");
        packet.handle(player, new InBuffer(new byte[]{(byte)255, (byte)255, 12, 0, 0, 0, 0, 24}), 28);
        check(player.take() == null, "Item no-target sentinel");
        packet.handle(player, new InBuffer(new byte[]{64, (byte)156, 0, 0, (byte)149, 0, 0, 27}), 28);
        check(player.take() == null, "Stale or empty inventory slot ignored");
        player.inventory.set(27, new Item(item.id, 1));
        packet.handle(player, new InBuffer(new byte[]{64, (byte)156, 0, 0, (byte)149, 0, 0, 27}), 28);
        check(item.examine.equals(player.take()), "Unsigned inventory item ID and validated slot");
        System.out.println("PASS 14 changed examine catalogue and packet-path checks");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class Reader extends Player {
        String message;
        int resets;
        final Inventory inventory = new Inventory();

        Reader() { inventory.init(28, false); }

        @Override public boolean isLocked() { return false; }
        @Override public void resetActions(boolean closeInterfaces, boolean resetMovement, boolean resetCombat) { resets++; }
        @Override public boolean isOnline() { return false; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public void sendMessage(String value) { message = value; }

        String take() {
            String value = message;
            message = null;
            return value;
        }
    }
}
