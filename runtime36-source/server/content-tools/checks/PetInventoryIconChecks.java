package net.runelite.standalone;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ruin.api.filestore.FileStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Renders inventory sprites with the shipped client, without starting an app or session. */
public final class PetInventoryIconChecks {
    public static void main(String[] args) {
        try {
            checkIcons(args);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    static void loadCache(String path) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        Client client = (Client) unsafe.allocateInstance(Client.class);
        client.logger = org.slf4j.LoggerFactory.getLogger(PetInventoryIconChecks.class);
        client.callbacks = (net.runelite.api.hooks.Callbacks) Proxy.newProxyInstance(
                Client.class.getClassLoader(), new Class<?>[]{net.runelite.api.hooks.Callbacks.class},
                (target, method, values) -> method.getReturnType() == boolean.class ? false : null);
        ViewportMouse.client = client;

        FileStore store = new FileStore(path, 8 * 1024 * 1024);
        ItemDefinition.ItemDefinition_archive = new PetCatalogCapture.StoreArchive(store.get(2));
        ItemDefinition.ItemDefinition_modelArchive = new PetCatalogCapture.StoreArchive(store.get(7));
        class163.ItemDefinition_inMembersWorld = true;
        Rasterizer3D.method2949(.82);
        Rasterizer3D.method2948(new TextureProvider(new PetCatalogCapture.StoreArchive(store.get(9)),
                new PetCatalogCapture.StoreArchive(store.get(8)), 64, .82, 128));
        Rasterizer2D.method6409(new int[36 * 32], 36, 32);
        Rasterizer3D.method2944();
    }

    private static void checkIcons(String[] args) throws Exception {
        loadCache(args[0]);
        String json = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
        JsonObject definitions = new Gson().fromJson(json, JsonObject.class).getAsJsonObject("definitions");
        int[] legacyMole = pixels(12646);
        if (legacyMole == null || visiblePixels(legacyMole) < 12) throw new AssertionError("Giant mole reference");
        Set<Integer> spriteHashes = new HashSet<>();
        int blank = 0;
        for (Map.Entry<String, JsonElement> entry : definitions.entrySet()) {
            int item = entry.getValue().getAsJsonObject().get("item").getAsInt();
            int[] pixels = pixels(item);
            int size = pixels == null ? 0 : visiblePixels(pixels);
            if (size < 12) {
                System.out.println("UNREADABLE " + entry.getKey() + " " + item + " " + size + " "
                        + Occluder.getItemDefinition(item).name);
                blank++;
                continue;
            }
            if (Arrays.equals(legacyMole, pixels)) throw new AssertionError("Mole icon reused for item " + item);
            spriteHashes.add(Arrays.hashCode(pixels));
        }
        System.out.println(definitions.size() + " pet slots, " + spriteHashes.size()
                + " distinct sprites, " + blank + " unreadable");
        if (blank != 0 || spriteHashes.size() < 500) throw new AssertionError("Unreadable or shared pet icons");
    }

    static int[] pixels(int item) {
        Sprite sprite = GrandExchangeOfferWorldComparator.createSprite(item, 1, 1, 0, 0, false);
        return sprite == null ? null : sprite.pixels.clone();
    }

    static int visiblePixels(int[] pixels) {
        int visible = 0;
        for (int color : pixels) if (color != 0) visible++;
        return visible;
    }
}
