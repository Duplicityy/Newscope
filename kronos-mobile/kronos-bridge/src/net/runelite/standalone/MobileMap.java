package net.runelite.standalone;

/** Uses the current scene when the stock atlas has no coverage of a custom region. */
final class MobileMap {
    private static boolean open;
    private static boolean localView;

    private MobileMap() {}

    static void draw(int x, int y, int width, int height, int cycle) {
        Player player = class215.localPlayer;
        if (player == null) return;
        int worldX = class215.baseX + (player.x >> 7);
        int worldY = class304.baseY + (player.getY() >> 7);
        int plane = player.plane;
        WorldMap map = Tiles.worldMap;
        // Kronos replaces the home scene in region 7991. The stock atlas has
        // a section for those coordinates but describes the original terrain.
        boolean customHome = (worldX >> 6) == 31 && (worldY >> 6) == 55;
        WorldMapArea area = customHome ? null : map.method5918(plane, worldX, worldY);
        if (!open || localView != (area == null)) {
            if (area != null) map.method5881(plane, worldX, worldY, true);
            open = true;
            localView = area == null;
        }
        if (!localView) {
            map.method5913(x, y, width, height, cycle);
            return;
        }

        Rasterizer2D.method6474(x, y, x + width, y + height);
        Rasterizer2D.method6469(x, y, width, height, 0x222720);
        int size = Math.min(width, height);
        int left = x + (width - size) / 2;
        int top = y + (height - size) / 2;
        ObjectSound.sceneMinimapSprite.method6110(left, top, size, size);
        int dotX = left + (player.x / 32 + 48) * size / 512;
        int dotY = top + (464 - player.getY() / 32) * size / 512;
        Rasterizer2D.method6469(dotX - 3, dotY - 3, 7, 7, 0x151515);
        Rasterizer2D.method6469(dotX - 2, dotY - 2, 5, 5, 0xffffff);
        GraphicsDefaults.fontPlain12.method5329("Local map  |  You: " + worldX + ", " + worldY,
                left + 4, top + size + 15, 0xffffff, 0);
    }

    static void update() {
        int host = Client.rootInterface == 548 ? 22 : 14;
        InterfaceParent map = (InterfaceParent) Client.interfaceParents.method6346(
                (long) ((Client.rootInterface << 16) | host));
        if (map == null || map.group != 595) {
            open = false;
            localView = false;
        }
    }

    static boolean atlasInteractive() {
        return !localView;
    }
}
