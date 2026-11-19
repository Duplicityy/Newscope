package net.runelite.standalone;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.TextureProvider;
import net.runelite.client.ui.DrawManager;

/** Offline fixture. Model, texture, UV, capture transforms and animated offsets use the real client. */
public final class UltraCapeCapture {
    private static final int[] YAWS = {0, 192, 832, 1024, 1216, 1856};
    private static final int WIDTH = 320, HEIGHT = 360, ZOOM = 560;

    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    private static void setRendererField(String name, Object value) throws ReflectiveOperationException {
        Field field = MobileHdRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static <T> T facade(Class<T> type, Map<String, Object> methods) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (methods.containsKey(method.getName())) return methods.get(method.getName());
                    if (method.getReturnType() == void.class) return null;
                    throw new AssertionError("Unexpected fixture call: " + method.getName());
                }));
    }

    private static Texture cacheTexture(Path dir, int textureId) throws Exception {
        Texture texture = new Texture(new Buffer(Files.readAllBytes(dir.resolve("texture-" + textureId + ".bin"))));
        final int spriteId = texture.fileIds[0];
        final byte[] sprite = Files.readAllBytes(dir.resolve("sprite-" + textureId + ".bin"));
        AbstractArchive spriteArchive = new AbstractArchive(false, false) {
            public byte[] method4029(int id) { return id == spriteId ? sprite : null; }
            public byte[] method4027(int id) { return id == spriteId ? sprite : null; }
        };
        require(texture.method2346(0.8, 128, spriteArchive), "Missing cache sprite " + spriteId);
        return texture;
    }

    private static MobileHdRenderer capture(Texture texture, int textureId) throws Exception {
        net.runelite.api.Texture[] atlas = new net.runelite.api.Texture[textureId + 1];
        atlas[textureId] = texture;
        Map<String, Object> provider = new HashMap<>();
        provider.put("getTextures", atlas);
        provider.put("load", texture.pixels);
        Map<String, Object> view = new HashMap<>();
        view.put("getTextureProvider", facade(TextureProvider.class, provider));
        view.put("getScene", facade(net.runelite.api.Scene.class, new HashMap<>()));
        view.put("getViewportWidth", WIDTH);
        view.put("getViewportHeight", HEIGHT);
        view.put("getCenterX", WIDTH / 2);
        view.put("getCenterY", HEIGHT / 2);
        view.put("getViewportXOffset", 0);
        view.put("getViewportYOffset", 0);
        view.put("getOculusOrbFocalPointX", 0);
        view.put("getOculusOrbFocalPointY", 0);
        view.put("getTileHeights", null);
        view.put("get3dZoom", ZOOM);
        view.put("getRasterizer3D_clipMidX2", WIDTH / 2);
        view.put("getRasterizer3D_clipMidY2", HEIGHT / 2);
        view.put("getRasterizer3D_clipNegativeMidX", -WIDTH / 2);
        view.put("getRasterizer3D_clipNegativeMidY", -HEIGHT / 2);
        MobileHdRenderer renderer = new MobileHdRenderer(facade(Client.class, view), new DrawManager());
        setRendererField("active", true);
        setRendererField("instance", renderer);
        return renderer;
    }

    private static void saveBuffer(Path path, ByteBuffer buffer, int size) throws Exception {
        byte[] bytes = new byte[size];
        buffer.position(0);
        buffer.get(bytes);
        Files.write(path, bytes);
    }

    private static void export(Path dir, String mode, int modelId, int textureId) throws Exception {
        ModelData data = new ModelData(Files.readAllBytes(dir.resolve("model-" + modelId + ".bin")));
        data.computeTextureUVCoordinates();
        Model model = data.copy$toModel(64, 850, -30, -50, -30);
        model.setFaceTextureUCoordinates(data.faceTextureUCoordinates);
        model.setFaceTextureVCoordinates(data.faceTextureVCoordinates);
        int textured = 0;
        for (int face = 0; face < model.indicesCount; face++) {
            if (model.faceTextures[face] < 0) continue;
            require(model.faceTextures[face] == textureId, "Unexpected wear-model texture");
            float[] u = data.faceTextureUCoordinates[face], v = data.faceTextureVCoordinates[face];
            double area = (u[1] - u[0]) * (v[2] - v[0]) - (u[2] - u[0]) * (v[1] - v[0]);
            require(Double.isFinite(area) && Math.abs(area) > 0.000001, "Missing authored UV on " + face);
            textured++;
        }
        require(textured == (textureId == 59 ? 30 : 24), "Unexpected cape fixture");
        Texture texture = cacheTexture(dir, textureId);
        MobileHdRenderer renderer = capture(texture, textureId);
        boolean candidate = !mode.equals("v39");
        boolean player = !mode.equals("scenery");
        if (candidate) MobileHdRenderer.class.getMethod("configureUltra", boolean.class).invoke(null, true);
        Map<String, Object> actor = new HashMap<>();
        actor.put("getModel", model);
        net.runelite.api.Entity entity = player ? facade(net.runelite.api.Player.class, actor) : model;
        int minY = Arrays.stream(model.verticesY).min().getAsInt();
        int maxY = Arrays.stream(model.verticesY).max().getAsInt();
        int heightOffset = -(minY + maxY) / 2;
        long frame = 0;
        for (int animationStep = 0; animationStep < 2; animationStep++) {
            if (animationStep > 0) {
                float oldV = texture.getV();
                renderer.animate(texture, 17);
                require(texture.getV() != oldV, "Real cape texture stopped animating");
            }
            for (int yaw : YAWS) {
                renderer.drawScene(0, 0, 0, 0, 0, 0);
                renderer.draw(entity, yaw, 0, 65536, 0, 65536, 0, heightOffset, 330, 0);
                MobileHdRenderer.commitFrame(++frame);
                int[] metadata = new int[16];
                int size = MobileHdRenderer.copyGeometry(null, metadata, frame);
                require(size > 0 && size == metadata[0] * 13 * 4, "Incomplete cape capture");
                ByteBuffer geometry = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
                require(MobileHdRenderer.copyGeometry(geometry, metadata, frame) == size, "Wrong geometry length");
                int texturedVertices = 0, solidVertices = 0;
                for (int at = 12 * 4; at < size; at += 13 * 4) {
                    float tag = geometry.getFloat(at);
                    if (tag == (candidate && player ? .25f : 0f)) texturedVertices++;
                    else if (tag == (candidate && player ? -2f : -1f)) solidVertices++;
                    else throw new AssertionError("Unexpected " + mode + " texture tag " + tag);
                }
                require(texturedVertices == textured * 3 && solidVertices > 0, "Failed to tag the full wearable");
                saveBuffer(dir.resolve(mode + "-" + modelId + "-" + yaw + "-" + animationStep + ".mesh"), geometry, size);
                if (frame == 1) {
                    ByteBuffer pixels = ByteBuffer.allocateDirect(128 * 128 * 4);
                    int copied = MobileHdRenderer.copyTexture(0, metadata[8], pixels);
                    require(copied == pixels.capacity(), "Incomplete cache texture");
                    saveBuffer(dir.resolve("atlas-" + textureId + ".rgba"), pixels, copied);
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            Path dir = Paths.get(args[0]);
            String mode = args[1];
            Rasterizer3D.method2949(0.8);
            export(dir, mode, 72204, 59);
            export(dir, mode, 72205, 59);
            export(dir, mode, 70827, 40);
            export(dir, mode, 70828, 40);
            System.out.println("Captured 48 " + mode + " male/female Infernal and Fire cape frames.");
            System.exit(0);
        } catch (Throwable failed) {
            failed.printStackTrace();
            System.exit(1);
        }
    }
}
