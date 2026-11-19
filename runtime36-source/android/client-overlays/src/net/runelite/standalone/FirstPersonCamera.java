package net.runelite.standalone;

import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import rs117.hd.HdPlugin;

/** Keeps 117 HD, native visibility and native hit testing on the same camera. */
public final class FirstPersonCamera {
    private static final int EYE_HEIGHT = 168;
    private static final int SCENE_SIZE = 104 * 128;
    private static volatile boolean requested;
    // The viewport render and MobileLayout gestures run on the client thread.
    private static boolean active;
    private static boolean positioned;
    private static int pitch = 32;
    private static int savedX, savedHeight, savedY, savedPitch;

    private FirstPersonCamera() {}

    public static void setRequested(boolean enabled) {
        requested = enabled;
    }

    public static boolean active() {
        return active;
    }

    static boolean renderSelf() {
        active = eligible();
        positioned = false;
        return Client.renderSelf && !active;
    }

    private static boolean eligible() {
        if (!requested || Client.gameState != 30 || Client.isCameraLocked || Client.oculusOrbState != 0
            || ViewportMouse.client == null || class215.localPlayer == null
            || !(ViewportMouse.client.getDrawCallbacks() instanceof HdPlugin) || !ViewportMouse.client.isGpu()) {
            return false;
        }
        // The native camera owns its scripted shake and cutscene frames.
        for (boolean shaking : Client.field1043) {
            if (shaking) return false;
        }
        LocalPoint position = class215.localPlayer.getLocalLocation();
        return inScene(position.getX(), position.getY());
    }

    static boolean inScene(int x, int y) {
        return x >= 0 && x < SCENE_SIZE && y >= 0 && y < SCENE_SIZE;
    }

    static void position(int x, int height, int y, int orbitPitch, int yaw, int distance, int viewportHeight) {
        NPCDefinition.method4403(x, height, y, orbitPitch, yaw, distance, viewportHeight);
        if (!active) return;

        // Save the original camera, including the pitch relaxer's normal state.
        savedX = GrandExchangeOfferOwnWorldComparator.cameraX;
        savedHeight = Varcs.cameraY;
        savedY = WorldMapIcon_1.cameraZ;
        savedPitch = IgnoreList.cameraPitch;
        LocalPoint position = class215.localPlayer.getLocalLocation();
        GrandExchangeOfferOwnWorldComparator.cameraX = position.getX();
        Varcs.cameraY = Perspective.getTileHeight(ViewportMouse.client, position, WorldMapRectangle.plane)
            - EYE_HEIGHT;
        WorldMapIcon_1.cameraZ = position.getY();
        IgnoreList.cameraPitch = pitch & 2047;
        positioned = true;
    }

    static boolean keepRawPitch() {
        return active || Client.pitchRelaxEnabled;
    }

    static int orbitPitchDelta(int delta) {
        if (!eligible()) return delta;
        pitch = nextPitch(pitch, delta);
        return 0;
    }

    static int nextPitch(int current, int delta) {
        return (int) Math.max(-160L, Math.min(192L, (long) current + delta));
    }

    static void restoreOrbitPitch(int unused) {
        if (positioned) {
            GrandExchangeOfferOwnWorldComparator.cameraX = savedX;
            Varcs.cameraY = savedHeight;
            WorldMapIcon_1.cameraZ = savedY;
            IgnoreList.cameraPitch = savedPitch;
            positioned = false;
        }
        Client.onCameraPitchChanged(unused);
    }
}
