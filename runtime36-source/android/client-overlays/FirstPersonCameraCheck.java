package net.runelite.standalone;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Arrays;
import net.runelite.client.config.ConfigItem;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import sun.misc.Unsafe;

public final class FirstPersonCameraCheck {
    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run() throws Exception {
        Class.forName("net.runelite.standalone.Friend", false, FirstPersonCameraCheck.class.getClassLoader())
            .getDeclaredMethods();
        Class.forName("net.runelite.standalone.Scene", false, FirstPersonCameraCheck.class.getClassLoader())
            .getDeclaredMethods();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Client client = (Client) unsafe.allocateInstance(Client.class);
        Player player = (Player) unsafe.allocateInstance(Player.class);
        ViewportMouse.client = client;
        class215.localPlayer = player;
        player.x = 1600;
        int yEncoder = BigInteger.valueOf(682054857).modInverse(BigInteger.ONE.shiftLeft(32)).intValue();
        player.y = 1664 * yEncoder;
        Client.gameState = 30;
        Client.renderSelf = true;
        Client.zoomHeight = 256;
        Client.zoomWidth = 320;
        Client.camAngleX = 200;
        Client.camAngleY = 100;
        Tiles.Tiles_heights = new int[4][105][105];
        Tiles.Tiles_renderFlags = new byte[4][104][104];
        for (int[] row : Tiles.Tiles_heights[1]) Arrays.fill(row, 448);
        Tiles.Tiles_renderFlags[1][12][13] = 2;
        WorldMapRectangle.plane = 0;
        client.setGpu(true);
        HdPlugin hd = new HdPlugin();
        client.setDrawCallbacks(hd);

        check(FirstPersonCamera.renderSelf(), "default shows local player");
        check(!FirstPersonCamera.keepRawPitch(), "default native pitch limit");
        FirstPersonCamera.position(1200, 40, 1250, 200, 100, 900, 400);
        int orbitX = GrandExchangeOfferOwnWorldComparator.cameraX;
        int orbitY = WorldMapIcon_1.cameraZ;
        int orbitHeight = Varcs.cameraY;
        ConfigItem item = HdPluginConfig.class.getMethod("firstPersonCamera").getAnnotation(ConfigItem.class);
        check(item != null && item.keyName().equals("firstPersonCamera"), "plugin setting exposed");

        FirstPersonCamera.setRequested(true);
        check(!FirstPersonCamera.renderSelf(), "local model hidden while renderSelf preference preserved");
        MobileLayout.camera(4000, -70000);
        check(Client.camAngleY == 96 && Client.camAngleX == 200, "gesture uses native yaw, preserves orbit pitch");
        FirstPersonCamera.position(1200, 40, 1250, 200, 100, 900, 400);
        check(GrandExchangeOfferOwnWorldComparator.cameraX == 1600 && WorldMapIcon_1.cameraZ == 1664,
                "eye attached to moving actor");
        check(Varcs.cameraY == 280, "eye elevation is bridge-aware");
        check(FirstPersonCamera.keepRawPitch() && IgnoreList.cameraPitch == (32 - 70 & 2047),
                "view can look above the horizon");
        FirstPersonCamera.restoreOrbitPitch(-1);
        check(GrandExchangeOfferOwnWorldComparator.cameraX == orbitX && WorldMapIcon_1.cameraZ == orbitY
            && Varcs.cameraY == orbitHeight && IgnoreList.cameraPitch == 200, "restores native orbit globals");
        check(Client.renderSelf, "preserves local render preference");

        Client.isCameraLocked = true;
        check(FirstPersonCamera.renderSelf() && !FirstPersonCamera.keepRawPitch(), "scripted camera stays native");
        Client.isCameraLocked = false;
        Client.field1043[4] = true;
        check(FirstPersonCamera.renderSelf(), "scripted shake stays native");
        Client.field1043[4] = false;
        Client.oculusOrbState = 1;
        check(FirstPersonCamera.renderSelf(), "orb control retained");
        Client.oculusOrbState = 0;
        Client.gameState = 25;
        check(FirstPersonCamera.renderSelf(), "loading falls back");
        Client.gameState = 30;
        client.setGpu(false);
        check(FirstPersonCamera.renderSelf(), "classic does not use GPU viewpoints");
        check(!FirstPersonCamera.inScene(-1, 128) && !FirstPersonCamera.inScene(128, 104 * 128), "scene bounds");
        check(FirstPersonCamera.nextPitch(-50, Integer.MIN_VALUE) == -160, "upper gaze bound");
        check(FirstPersonCamera.nextPitch(50, Integer.MAX_VALUE) == 192, "lower gaze bound");
        System.out.println("PASS first-person option, camera pose, bridge height, native gesture detour, restore and guards");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
