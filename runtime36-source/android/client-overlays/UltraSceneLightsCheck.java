package net.runelite.standalone;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.client.ui.DrawManager;

/** Runs with the actual jars shipped in the signed owner APK; no server or account is used. */
public final class UltraSceneLightsCheck {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static Field field(String name) throws Exception {
        Field field = MobileHdRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static long tag(int kind, int id) {
        return ((long) id << 17) | (kind << 14) | 73;
    }
    private static final int[] IDs = {26185, 26186, 26575, 26576, 20000, 20001, 13201, 29314};

    private static void select() {
        UltraSceneLights lights = new UltraSceneLights();
        int[] frame = new int[UltraSceneLights.METADATA];
        // Designed in camera-relative coordinates with the camera at (0,0,0).
        for (int i = 0; i < IDs.length; i++) {
            lights.observe(frame, tag(2, IDs[i]), 0, 0, 10);
            require(frame[16] == 1 && frame[20] == i + 1, "Native firemaking and scenery IDs");
            frame[16] = 0;
        }
        for (int id : new int[]{13200,13202,13204,13206,29312,29313,29747,1,65535})
            lights.observe(frame, tag(2, id), 0, 0, 10);
        for (int kind : new int[]{0,1,3}) lights.observe(frame, tag(kind,26185), 0, 0, 10);
        lights.observe(frame,tag(2,26185),5000,0,0);
        require(frame[16] == 0, "Exclude unlit objects, other scene entities and distant emitters");

        for (int direction : new int[]{-1,1}) {
            frame[16] = 0;
            for (int i = 0; i < 11; i++) {
                int x = direction * (i - 5);
                lights.observe(frame,tag(2,26185),x,40,0);
                lights.observe(frame,tag(2,26185),x,40,0);
            }
            require(frame[16] == 4 && frame[17] == 0 && frame[21] == -1 && frame[25] == 1 && frame[29] == -2,
                "Nearest four, stable ties and duplicate suppression");
        }
    }

    private static Object proxy(Class<?> type, java.lang.reflect.InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static void publish() throws Exception {
        TextureProvider textures = (TextureProvider) proxy(TextureProvider.class,(p,m,args) ->
                m.getName().equals("getTextures") ? new net.runelite.api.Texture[1] : null);
        Scene scene = (Scene) proxy(Scene.class,(p,m,args) -> null);
        int[][][] heights = new int[1][2][2];
        Client client = (Client) proxy(Client.class,(p,m,args) -> {
            switch (m.getName()) {
                case "getTextureProvider": return textures;
                case "getScene": return scene;
                case "getTileHeights": return heights;
                case "getOculusOrbFocalPointX": case "getOculusOrbFocalPointY": return 128;
                case "getViewportWidth": return 512;
                case "getViewportHeight": return 334;
                case "get3dZoom": return 512;
                case "getCenterX": return 256;
                case "getCenterY": return 167;
                case "getViewportXOffset": case "getViewportYOffset": return 4;
                default: throw new AssertionError(m.getName());
            }
        });
        MobileHdRenderer renderer = new MobileHdRenderer(client, new DrawManager());
        field("instance").set(null, renderer);
        field("active").setBoolean(null, true);
        UltraSceneLights lights = (UltraSceneLights)field("lights").get(renderer);
        int[] view = (int[])field("view").get(renderer);
        int[] firstPrefix = null;
        for (int i = 1; i <= 3; i++) {
            boolean ultra = i != 2;
            MobileHdRenderer.configureUltra(ultra);
            renderer.drawScene(128,0,128,128,0,0);
            require(field("ultraFrame").getBoolean(renderer) == ultra && view[16] == 0, "Coherent mode start clears prior-frame lights");
            if (ultra) lights.observe(view, tag(2,26185), i, 40, 7);
            ByteBuffer mesh = (ByteBuffer)field("geometry").get(renderer);
            for (int v = 0; v < 39; v++) mesh.putFloat(v == 6 ? 1f : 0f);
            field("count").setInt(renderer,3);
            MobileHdRenderer.commitFrame(i);
            int[] out = new int[33], prefix = new int[16], shortPrefix = new int[12];
            require(MobileHdRenderer.copyGeometry(null,out,i) == 156 && out[16] == (ultra ? 1 : 0), "Metadata matches current presented frame");
            if (ultra) require(out[17] == i && out[18] == 0 && out[19] == 7 && out[20] == 1, "Packed camera-relative flame coordinates, height and type");
            require(MobileHdRenderer.copyGeometry(null,prefix,i) == 156 && Arrays.equals(prefix, Arrays.copyOf(out,16)), "Existing 16-word clients");
            require(MobileHdRenderer.copyGeometry(null,shortPrefix,i) == 156 && Arrays.equals(shortPrefix, Arrays.copyOf(out,12)), "Existing 12-word clients");
            if (firstPrefix == null) firstPrefix=prefix;
            else require(Arrays.equals(firstPrefix,prefix), "Fixed metadata prefix during settings changes");
            if (i > 1) {
                require(MobileHdRenderer.copyGeometry(null,out,i-1) == 156 && out[16] == (i == 2 ? 1 : 0), "Previous frame retains its own light list");
            }
        }
        require(MobileHdRenderer.copyGeometry(null,new int[33],1) == -1, "Expired generation rejected");
    }
    public static void main(String[] args) throws Exception {
        select();
        publish();
        System.out.println("PASS emitter selection, camera coordinates, capture lifetimes, mode switch and 12/16/33 word consumers");
    }
}
