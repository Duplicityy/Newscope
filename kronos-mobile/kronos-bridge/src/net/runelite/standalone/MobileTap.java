package net.runelite.standalone;

import java.util.ArrayDeque;

/** Delivers a touch press after the client has drawn and picked at its coordinates. */
public final class MobileTap {
    private static final ArrayDeque<Tap> queue = new ArrayDeque<>();
    private static final long MAX_AGE_NANOS = 750_000_000L;
    private static Tap pending;
    private static Tap pressed;
    private static boolean frameReady;
    private static boolean sceneReady;
    private static int state, root, width, height, baseX, baseY, plane;

    private MobileTap() {}

    public static synchronized void enqueue(int x, int y, int button) {
        if ((button == 1 || button == 3) && queue.size() < 8)
            queue.addLast(new Tap(x, y, button));
    }

    public static synchronized void cancel() {
        queue.clear();
        pending = null;
    }

    /** Runs just after the engine samples the native mouse, before it handles a press. */
    static synchronized void poll() {
        pressed = null;
        if (MobileLayout.editing() || Client.isLoading && Client.gameState == 30) {
            cancel();
            return;
        }
        if (pending != null && (pending.expired() || !sameLayout())) pending = null;
        if (pending == null) {
            do {
                pending = queue.pollFirst();
            } while (pending != null && pending.expired());
            if (pending == null) return;
            state = Client.gameState;
            root = Client.rootInterface;
            width = FloorDecoration.canvasWidth;
            height = WallDecoration.canvasHeight;
            frameReady = false;
            sceneReady = false;
        }
        if (pending.x < 0 || pending.y < 0 || pending.x >= width || pending.y >= height) {
            pending = null;
            return;
        }
        if (MouseHandler.MouseHandler_instance == null) return;
        synchronized (MouseHandler.MouseHandler_instance) {
            MouseHandler.MouseHandler_x = MouseHandler.MouseHandler_xVolatile = pending.x;
            MouseHandler.MouseHandler_y = MouseHandler.MouseHandler_yVolatile = pending.y;
        }
        if (!frameReady && Client.gameState == 30) return;

        pressed = pending;
        pending = null;
        synchronized (MouseHandler.MouseHandler_instance) {
            MouseHandler.MouseHandler_idleCycles = 0;
            // Native walking reads the last press again after the scene has picked a tile.
            MouseHandler.MouseHandler_lastPressedX = MouseHandler.MouseHandler_lastPressedXVolatile = pressed.x;
            MouseHandler.MouseHandler_lastPressedY = MouseHandler.MouseHandler_lastPressedYVolatile = pressed.y;
            MouseHandler.MouseHandler_lastPressedTimeMillis = MouseHandler.MouseHandler_lastPressedTimeMillisVolatile = class33.method680();
        }
        MouseHandler.MouseHandler_lastButton = pressed.button == 3 ? 2 : 1;
        MouseHandler.MouseHandler_currentButton = MouseHandler.MouseHandler_lastButton;
        if (pressed.button == 1 && state != 30) MobileUi.request(pressed.x, pressed.y);
    }

    static synchronized void sceneDrawn() {
        if (pending == null) return;
        sceneReady = ViewportMouse.ViewportMouse_isInViewport
                && ViewportMouse.ViewportMouse_x + Client.viewportOffsetX == pending.x
                && ViewportMouse.ViewportMouse_y + Client.viewportOffsetY == pending.y;
        baseX = class215.baseX;
        baseY = class304.baseY;
        plane = WorldMapRectangle.plane;
    }

    public static synchronized void frameDrawn() {
        if (pending != null && sameLayout()) frameReady = true;
    }

    static synchronized void validatePress() {
        if (pressed == null) return;
        if (!sameLayout() || Client.isLoading) {
            MouseHandler.MouseHandler_lastButton = 0;
            MouseHandler.MouseHandler_currentButton = 0;
            pressed = null;
        } else if (pressed.button == 1 && state == 30) {
            MobileUi.request(pressed.x, pressed.y);
        }
    }

    static synchronized boolean canUseWorldPick() {
        return pressed == null || sceneReady && baseX == class215.baseX && baseY == class304.baseY
                && plane == WorldMapRectangle.plane && ViewportMouse.ViewportMouse_isInViewport
                && ViewportMouse.ViewportMouse_x + Client.viewportOffsetX == pressed.x
                && ViewportMouse.ViewportMouse_y + Client.viewportOffsetY == pressed.y;
    }

    private static boolean sameLayout() {
        return state == Client.gameState && root == Client.rootInterface && width == FloorDecoration.canvasWidth
                && height == WallDecoration.canvasHeight;
    }

    private static final class Tap {
        final int x, y, button;
        final long queuedAt = System.nanoTime();

        Tap(int x, int y, int button) {
            this.x = x;
            this.y = y;
            this.button = button;
        }

        boolean expired() {
            return System.nanoTime() - queuedAt > MAX_AGE_NANOS;
        }
    }
}
