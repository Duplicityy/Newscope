package net.runelite.standalone;

/** Keeps the native atlas and adds tap-to-walk to the displayed local scene map. */
final class MobileMap {
    private static final int WORLD_MAP_GROUP = 595;
    private static final int SPRITE_EDGE = 512;
    private static final int MAP_SPRITE_LEFT = 48;
    private static final int MAP_SPRITE_BOTTOM = 463;

    private static boolean open;
    private static boolean localView;
    private static InterfaceParent renderedMap;
    private static int renderedX, renderedY, renderedWidth, renderedHeight;
    private static int renderedBaseX, renderedBaseY, renderedPlane;
    private static long routedPressTime = Long.MIN_VALUE;

    private MobileMap() {}

    static void draw(int x, int y, int width, int height, int cycle) {
        renderedMap = null;
        Player player = class215.localPlayer;
        if (player == null) return;
        int worldX = class215.baseX + (player.x >> 7);
        int worldY = class304.baseY + (player.getY() >> 7);
        int plane = player.plane;
        WorldMap atlas = Tiles.worldMap;
        boolean home = (worldX >> 6) == 31 && (worldY >> 6) == 55;
        WorldMapArea area = home ? null : atlas.method5918(plane, worldX, worldY);
        if (!open || localView != (area == null)) {
            if (area != null) atlas.method5881(plane, worldX, worldY, true);
            open = true;
            localView = area == null;
        }

        if (!localView) {
            atlas.method5913(x, y, width, height, cycle);
            return;
        }

        Rasterizer2D.method6474(x, y, x + width, y + height);
        Rasterizer2D.method6469(x, y, width, height, 2238240);
        int side = Math.min(width, height);
        int left = x + (width - side) / 2;
        int top = y + (height - side) / 2;
        ObjectSound.sceneMinimapSprite.method6110(left, top, side, side);

        int markerX = left + (player.x / 32 + MAP_SPRITE_LEFT) * side / SPRITE_EDGE;
        int markerY = top + (MAP_SPRITE_BOTTOM + 1 - player.getY() / 32) * side / SPRITE_EDGE;
        Rasterizer2D.method6469(markerX - 3, markerY - 3, 7, 7, 1381653);
        Rasterizer2D.method6469(markerX - 2, markerY - 2, 5, 5, 16777215);
        GraphicsDefaults.fontPlain12.method5329("Local map  |  You: " + worldX + ", " + worldY,
                left + 4, top + side + 15, 16777215, 0);

        renderedMap = parent();
        renderedBaseX = class215.baseX;
        renderedBaseY = class304.baseY;
        renderedPlane = plane;
        renderedX = x;
        renderedY = y;
        renderedWidth = width;
        renderedHeight = height;
    }

    static void update() {
        if (parent() == null) {
            open = false;
            localView = false;
            renderedMap = null;
        }
    }

    static boolean atlasInteractive() {
        return !localView;
    }

    // Installed only at the native atlas press dispatch, where widget clipping
    // and pointer-in-widget checks have already run.
    static boolean handleWidgetInput(Widget widget, int x, int y, boolean insidePress) {
        if (!localView) return true;
        int tapX = MouseHandler.MouseHandler_lastPressedX, tapY = MouseHandler.MouseHandler_lastPressedY;
        if (!insidePress || !MobileTap.releasedLeftAt(tapX, tapY)) return false;
        route(widget, x, y, tapX, tapY);
        return false;
    }

    static void route(Widget widget, int x, int y, int tapX, int tapY) {
        Player player = class215.localPlayer;
        long pressTime = MouseHandler.MouseHandler_lastPressedTimeMillis;
        if (Client.gameState != 30 || Client.isLoading || Client.isMenuOpen || player == null
                || renderedMap == null || renderedMap != parent() || (widget.id >>> 16) != WORLD_MAP_GROUP
                || x != renderedX || y != renderedY || widget.width != renderedWidth || widget.height != renderedHeight
                || renderedBaseX != class215.baseX || renderedBaseY != class304.baseY
                || renderedPlane != player.plane || renderedPlane != WorldMapRectangle.plane
                || routedPressTime == pressTime) return;

        int side = Math.min(renderedWidth, renderedHeight);
        int left = renderedX + (renderedWidth - side) / 2;
        int top = renderedY + (renderedHeight - side) / 2;
        int tile = tileAt(tapX, tapY, left, top, side, renderedBaseX, renderedBaseY, renderedPlane);
        if (tile == -1) return;

        PacketBufferNode packet = InterfaceParent.method1140(ClientPacket.field2409, Client.packetWriter.isaacCipher);
        packet.packetBuffer.writeInt(tile);
        Client.packetWriter.method1622(packet);
        routedPressTime = pressTime;
    }

    static int tileAt(int x, int y, int left, int top, int side, int baseX, int baseY, int plane) {
        int dx = x - left, dy = y - top;
        if (dx < 0 || dy < 0 || dx >= side || dy >= side || plane < 0 || plane > 3) return -1;
        // Sprite.method6110 uses a truncated 16.16 increment per destination pixel.
        int step = (SPRITE_EDGE << 16) / side;
        int tileX = ((dx * step >>> 16) - MAP_SPRITE_LEFT) >> 2;
        int tileY = (MAP_SPRITE_BOTTOM - (dy * step >>> 16)) >> 2;
        // The native minimap rasterizer only fills local scene tiles 1–102.
        if (tileX < 1 || tileX > 102 || tileY < 1 || tileY > 102) return -1;
        int absX = tileX + baseX, absY = tileY + baseY;
        if (absX < 0 || absX > 0x3fff || absY < 0 || absY > 0x3fff) return -1;
        return plane << 28 | absX << 14 | absY;
    }

    private static InterfaceParent parent() {
        int root = Client.rootInterface;
        if (Client.interfaceParents == null) return null;
        int component = root == 548 ? 22 : 14;
        InterfaceParent parent = (InterfaceParent) Client.interfaceParents.method6346(((long) root << 16) | component);
        return parent != null && parent.group == WORLD_MAP_GROUP ? parent : null;
    }
}
