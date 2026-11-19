package net.runelite.standalone;

/** Bounded frame-local emitter selection. Coordinates use the scene's camera-relative axes, with Y down. */
final class UltraSceneLights {
    static final int COUNT = 16; // Original capture metadata occupies words 0..15.
    static final int LIMIT = 4;
    static final int METADATA = COUNT + 1 + 4 * LIMIT;
    private static final int RANGE = 32 * 128;
    private final long[] distances = new long[LIMIT];
    private int focusX, focusY, focusZ;

    void begin(net.runelite.api.Client client, int cameraX, int cameraY, int cameraZ, int plane) {
        int x = client.getOculusOrbFocalPointX(), z = client.getOculusOrbFocalPointY();
        focusX = x - cameraX;
        focusZ = z - cameraZ;
        int[][][] heights = client.getTileHeights();
        int tx = x >> 7, tz = z >> 7;
        focusY = -cameraY;
        if (heights != null && plane >= 0 && plane < heights.length && heights[plane] != null
                && tx >= 0 && tx < heights[plane].length && heights[plane][tx] != null
                && tz >= 0 && tz < heights[plane][tx].length) {
            focusY += heights[plane][tx][tz];
        }
    }

    void observe(int[] frame, long tag, int x, int y, int z) {
        if (((tag >>> 14) & 3) != 2) return; // Only a scene location can be one of the registered fire sources.
        int style = style((int)(tag >>> 17));
        if (style == 0) return;
        y -= style == 7 ? 144 : style == 8 ? 88 : 40;
        long dx = (long)x - focusX, dy = (long)y - focusY, dz = (long)z - focusZ;
        long distance = dx * dx + dy * dy + dz * dz;
        if (distance > (long)RANGE * RANGE) return;

        int count = frame[COUNT], slot = 0;
        for (; slot < count; slot++) {
            int at = COUNT + 1 + 4 * slot;
            long existing = distances[slot];
            // Stable ties keep equal-distance emitters independent of scene traversal order.
            int order = Long.compare(distance, existing);
            if (order == 0) order = Integer.compare(x, frame[at]);
            if (order == 0) order = Integer.compare(y, frame[at + 1]);
            if (order == 0) order = Integer.compare(z, frame[at + 2]);
            if (order == 0) order = Integer.compare(style, frame[at + 3]);
            if (order == 0) return;
            if (order < 0) break;
        }
        if (slot == LIMIT) return;
        int last = Math.min(count, LIMIT - 1);
        if (last > slot) {
            System.arraycopy(distances, slot, distances, slot + 1, last - slot);
            System.arraycopy(frame, COUNT + 1 + slot * 4, frame, COUNT + 1 + (slot + 1) * 4, (last - slot) * 4);
        }
        int at = COUNT + 1 + slot * 4;
        distances[slot] = distance;
        frame[at] = x; frame[at + 1] = y; frame[at + 2] = z; frame[at + 3] = style;
        frame[COUNT] = Math.min(count + 1, LIMIT);
    }

    // Native installed location IDs, including the mapped 242 variants. Unlit and morph-parent IDs are excluded.
    private static int style(int id) {
        switch (id) {
            case 3769: case 19881: case 19884: case 25374: case 26185: case 29085:
            case 37865: case 39094: case 39260: return 1; // fire or campfire
            case 26186: return 2; // red, green, blue, white and purple firemaking logs
            case 26575: return 3;
            case 26576: return 4;
            case 20000: return 5;
            case 20001: return 6;
            case 10658: case 13201: case 13203: case 13205: case 13207: case 13341: case 17237:
            case 32118: case 34345: case 34856: case 38844: case 39485: return 7; // lit torch
            case 27361: case 28818: case 29314: case 29748: case 31926: case 51052: return 8; // lit brazier
            default: return 0;
        }
    }
}
