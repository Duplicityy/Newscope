package net.runelite.standalone;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import sun.misc.Unsafe;

public final class MobileMapInputCheck {
    public static void main(String[] args) {
        try {
            checkProjection();
            checkWidgetPress();
            System.out.println("PASS native local-map raster coordinates, clipped widget dispatch and opcode 69 payload");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void checkProjection() {
        int side = 431, left = 180, top = 30;
        Sprite source = new Sprite(512, 512);
        for (int i = 0; i < source.pixels.length; i++) source.pixels[i] = i + 1;
        int width = 800, height = 500;
        int[] draw = new int[width * height];
        Rasterizer2D.method6409(draw, width, height);
        source.method6110(left, top, side, side);
        int checks = 0;
        for (int y = top; y < top + side; y += 3) {
            for (int x = left; x < left + side; x += 5) {
                int sample = (draw[y * width + x] & 0xffffff) - 1;
                int localX = ((sample & 511) - 48) >> 2;
                int localY = (463 - (sample >>> 9)) >> 2;
                int expected = localX < 1 || localX > 102 || localY < 1 || localY > 102
                        ? -1 : (2 << 28) | (2000 + localX) << 14 | (3500 + localY);
                check(MobileMap.tileAt(x, y, left, top, side, 2000, 3500, 2) == expected,
                        "tap corresponds to native sample: " + x + "," + y + " draw=" + draw[y * width + x] + " expected=" + expected + " got=" + MobileMap.tileAt(x, y, left, top, side, 2000, 3500, 2));
                checks++;
            }
        }
        check(checks > 10000, "projection sample coverage");
        check(MobileMap.tileAt(left - 1, top + 220, left, top, side, 0, 0, 0) == -1, "outside square");
        check(MobileMap.tileAt(left + side / 2, top, left, top, side, 0, 0, 0) == -1, "blank border");
        check(MobileMap.tileAt(left, top, left, top, 0, 0, 0, 0) == -1, "empty view");
    }

    private static void checkWidgetPress() throws Exception {
        // Recreate the map widget and state normally published by the draw callback.
        // Touch presses still pass through the original MobileTap and widget traversal.
        int width = 900, height = 431, left = 20, top = 16;
        int squareLeft = left + (width - height) / 2;
        int tapX = squareLeft + height / 2, tapY = top + height / 2;
        Client.gameState = 30;
        Client.isLoading = false;
        Client.isMenuOpen = false;
        Client.rootInterface = 161;
        Client.interfaceParents = new NodeHashTable(8);
        InterfaceParent map = new InterfaceParent();
        map.group = 595;
        Client.interfaceParents.put(map, ((long) Client.rootInterface << 16) | 14);
        // The constructor emits RuneLite callbacks, which require a running shell.
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        ViewportMouse.client = (Client) unsafe.allocateInstance(Client.class);
        class215.localPlayer = (Player) unsafe.allocateInstance(Player.class);
        class215.localPlayer.plane = 0;
        FloorDecoration.canvasWidth = 1024;
        WallDecoration.canvasHeight = 512;
        class215.baseX = 2000;
        class304.baseY = 3500;
        WorldMapRectangle.plane = 0;
        Client.packetWriter.isaacCipher = new IsaacCipher(new int[4]);

        Widget widget = new Widget();
        widget.type = 0;
        widget.isIf3 = true;
        widget.parentId = -1;
        widget.id = 595 << 16 | 1;
        widget.contentType = 1400;
        widget.x = left;
        widget.y = top;
        widget.width = width;
        widget.height = height;
        state("localView", true);
        state("renderedMap", map);
        state("renderedX", left);
        state("renderedY", top);
        state("renderedWidth", width);
        state("renderedHeight", height);
        state("renderedBaseX", 2000);
        state("renderedBaseY", 3500);
        state("renderedPlane", 0);

        MouseHandler.MouseHandler_lastButton = 1;
        MouseHandler.MouseHandler_lastPressedX = MouseHandler.MouseHandler_x = tapX;
        MouseHandler.MouseHandler_lastPressedY = MouseHandler.MouseHandler_y = tapY;
        traverse(widget);
        check(Client.packetWriter.packetBufferNodes.method5044() == null, "raw drag press is ignored");

        release(tapX, tapY + 1, 1);
        traverse(widget);
        PacketBufferNode packet = (PacketBufferNode) Client.packetWriter.packetBufferNodes.method5044();
        check(packet != null && packet.clientPacket == ClientPacket.field2409
                && packet.clientPacketLength == 4 && packet.index == 5, "native world map opcode packet");
        int expected = MobileMap.tileAt(tapX, tapY + 1, squareLeft, top, height, 2000, 3500, 0);
        check(ByteBuffer.wrap(packet.packetBuffer.array, 1, 4).getInt() == expected, "existing packed coordinate encoding");
        packet.method3497();
        traverse(widget);
        check(Client.packetWriter.packetBufferNodes.method5044() == null, "one packet per completed tap");

        release(tapX + 2, top, 1);
        traverse(widget);
        check(Client.packetWriter.packetBufferNodes.method5044() == null, "blank margin is ignored");
        release(tapX + 3, tapY + 3, 3);
        traverse(widget);
        check(Client.packetWriter.packetBufferNodes.method5044() == null, "context press is ignored");
        class215.baseX += 8;
        release(tapX + 4, tapY + 4, 1);
        traverse(widget);
        check(Client.packetWriter.packetBufferNodes.method5044() == null, "stale scene base is ignored");

        state("localView", false);
        check(MobileMap.atlasInteractive() && MobileMap.handleWidgetInput(widget, left, top, true),
                "native atlas dispatch remains enabled");
    }

    private static void traverse(Widget widget) {
        MusicPatchNode.method3844(new Widget[]{widget}, -1, 0, 0, 1024, 512, 0, 0);
    }

    private static void release(int x, int y, int button) {
        MobileTap.enqueue(x, y, button);
        MobileTap.poll();
        MobileTap.frameDrawn();
        MobileTap.poll();
        check(MobileTap.releasedLeftAt(x, y) == (button == 1), "tap recognized from the client queue");
    }

    private static void state(String name, Object value) throws Exception {
        Field field = MobileMap.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
