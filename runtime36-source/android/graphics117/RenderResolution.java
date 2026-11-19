package rs117.hd.android;

/** Render target size only. Gameplay, UI layout and picking keep the canvas size. */
public enum RenderResolution {
    STANDARD("Standard"),
    SHARP("Sharp"),
    DISPLAY("Display resolution");

    private static final int MAX_EDGE = 4096;
    private static final int MAX_PIXELS = 4_608_000;
    private static final double SHARP_SCALE = 1.5;

    private final String label;

    RenderResolution(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    long target(int width, int height, long windowSize) {
        int windowWidth = width(windowSize);
        int windowHeight = height(windowSize);
        if (this == STANDARD || windowWidth <= width || windowHeight <= height) {
            return pack(width, height);
        }
        double scale = Math.min((double) Math.min(windowWidth, MAX_EDGE) / width,
                (double) Math.min(windowHeight, MAX_EDGE) / height);
        scale = Math.min(scale, Math.sqrt((double) MAX_PIXELS / ((long) width * height)));
        if (this == SHARP) {
            scale = Math.min(scale, SHARP_SCALE);
        }
        if (scale <= 1) {
            return pack(width, height);
        }
        if (this == DISPLAY) {
            // Match the Activity's aspect-fit rounding where the budget permits.
            int renderWidth = (int) Math.round(width * scale);
            int renderHeight = (int) Math.round(height * scale);
            if ((long) renderWidth * renderHeight <= MAX_PIXELS) {
                return pack(renderWidth, renderHeight);
            }
        }
        return pack((int) Math.floor(width * scale), (int) Math.floor(height * scale));
    }

    public static int edge(int position, int canvas, int target) {
        return (int) Math.round((double) position * target / canvas);
    }

    static long pack(int width, int height) {
        return (long) width << 32 | (height & 0xffffffffL);
    }

    public static int width(long size) { return (int) (size >>> 32); }
    public static int height(long size) { return (int) size; }
}
