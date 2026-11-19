package net.runelite.standalone;

/** Destination selection for the expanded world map, shared by mouse and touch. */
final class WorldMapConfirm {
    private static final int TARGET = 1, YES = 2, NO = 3;
    private static final int BUTTON = 29, GAP = 4, MARKER_GAP = 16;
    private static final int TAP_SLOP = 8;
    private static final int SHADOW = 0xff20170e, GOLD = 0xffffcd42;
    private static WorldMap map;
    private static WorldMapArea area;
    private static Coord destination, pressedDestination;
    private static int pressKind, pressX, pressY;
    private static boolean dragged, buttonsVisible;
    private static int yesX, noX, buttonY, drawCycle = Integer.MIN_VALUE;
    private static int width, height;

    private WorldMapConfirm() {}

    static boolean nativePress(boolean pressed) {
        return pressed && Client.staffModLevel >= 2
                && KeyHandler.KeyHandler_pressedKeys[82] && KeyHandler.KeyHandler_pressedKeys[81];
    }

    /** True while an on-map button owns the gesture, so the map does not pan under it. */
    static boolean input(WorldMap owner, int x, int y, boolean held, boolean pressed) {
        if (!current(owner)) {
            reset();
            return false;
        }
        if (Client.isMenuOpen || nativePress(pressed)) {
            pressKind = 0;
            return false;
        }
        if (pressed) {
            pressKind = hit(x, y);
            if (pressKind == 0 && validTile(owner, x, y)) {
                pressKind = TARGET;
                pressedDestination = owner.mouseCoord;
            }
            pressX = x;
            pressY = y;
            dragged = false;
        }
        if (pressKind == 0)
            return false;
        int kind = pressKind;
        dragged |= Math.abs(x - pressX) > TAP_SLOP || Math.abs(y - pressY) > TAP_SLOP;
        // The Android AWT bridge can deliver press and release in a single client tick.
        if (!held) {
            pressKind = 0;
            if (!dragged && kind == TARGET)
                destination = pressedDestination;
            else if (!dragged && hit(x, y) == kind) {
                Coord chosen = destination;
                destination = null;
                buttonsVisible = false;
                if (kind == YES && chosen != null)
                    send(chosen);
            }
            pressedDestination = null;
        }
        return kind == YES || kind == NO;
    }

    private static boolean current(WorldMap owner) {
        return Client.gameState == 30 && map == owner && area == owner.currentMapArea
                && Client.cycle - drawCycle >= 0 && Client.cycle - drawCycle <= 3;
    }

    private static void reset() {
        destination = pressedDestination = null;
        pressKind = 0;
        buttonsVisible = false;
    }

    private static boolean validTile(WorldMap owner, int x, int y) {
        return owner.mouseCoord != null && x >= owner.worldMapDisplayX && y >= owner.worldMapDisplayY
                && x < owner.worldMapDisplayX + width && y < owner.worldMapDisplayY + height;
    }

    private static int hit(int x, int y) {
        if (!buttonsVisible || y < buttonY || y >= buttonY + BUTTON)
            return 0;
        if (x >= yesX && x < yesX + BUTTON) return YES;
        if (x >= noX && x < noX + BUTTON) return NO;
        return 0;
    }

    private static void send(Coord tile) {
        PacketBufferNode packet = InterfaceParent.method1140(ClientPacket.field2409, Client.packetWriter.isaacCipher);
        packet.packetBuffer.writeInt(tile.method3913());
        Client.packetWriter.method1622(packet);
    }

    static void draw(WorldMap owner, int left, int top, int w, int h) {
        if (!current(owner))
            reset();
        map = owner;
        area = owner.currentMapArea;
        drawCycle = Client.cycle;
        width = w;
        height = h;
        buttonsVisible = false;
        if (destination == null || area == null || w < BUTTON * 2 + GAP + 8 || h < BUTTON + 8)
            return;

        int[] point = area.method369(destination.plane, destination.x, destination.y);
        if (point == null)
            return;
        int tileX = point[0] - area.method360() * 64;
        int tileY = point[1] - area.method362() * 64;
        int x = left + Math.round((tileX + .5f - owner.centerTileX + owner.method6009() / 2f) * owner.zoom);
        int y = top + Math.round((owner.centerTileY - tileY - .5f + owner.method5967() / 2f) * owner.zoom);
        if (x < left || x >= left + w || y < top || y >= top + h)
            return;

        crosshair(x, y, 2, SHADOW);
        crosshair(x, y, 0, GOLD);

        int buttonGroup = BUTTON * 2 + GAP;
        int adjacent = x + MARKER_GAP;
        if (adjacent + buttonGroup > left + w - 4)
            adjacent = x - MARKER_GAP - buttonGroup;
        yesX = Math.max(left + 4, Math.min(left + w - buttonGroup - 4, adjacent));
        noX = yesX + BUTTON + GAP;
        buttonY = Math.max(top + 4, Math.min(top + h - BUTTON - 4, y - BUTTON / 2));
        buttonsVisible = true;
        button(yesX, buttonY, 0xff254a27);
        button(noX, buttonY, 0xff552523);
        int midY = buttonY + BUTTON / 2;
        line(yesX + 7, midY, yesX + 12, midY + 5, 0xffb9fc96);
        line(yesX + 12, midY + 5, yesX + 22, midY - 6, 0xffb9fc96);
        line(noX + 8, midY - 6, noX + 20, midY + 6, 0xffffaaa4);
        line(noX + 8, midY + 6, noX + 20, midY - 6, 0xffffaaa4);
    }

    private static void crosshair(int x, int y, int border, int color) {
        int size = 2 + 2 * border;
        fill(x - 11 - border, y - border, 7 + 2 * border, size, color);
        fill(x + 5 - border, y - border, 7 + 2 * border, size, color);
        fill(x - border, y - 11 - border, size, 7 + 2 * border, color);
        fill(x - border, y + 5 - border, size, 7 + 2 * border, color);
        fill(x - border, y - border, size, size, color);
    }

    private static void button(int x, int y, int color) {
        fill(x, y, BUTTON, BUTTON, SHADOW);
        fill(x + 1, y + 1, BUTTON - 2, BUTTON - 2, 0xff9e824b);
        fill(x + 2, y + 2, BUTTON - 4, BUTTON - 4, color);
    }

    private static void line(int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++)
            fill(x1 + i * (x2 - x1) / steps, y1 + i * (y2 - y1) / steps, 2, 2, color);
    }

    private static void fill(int x, int y, int w, int h, int color) {
        Rasterizer2D.method6469(x, y, w, h, color);
    }
}
