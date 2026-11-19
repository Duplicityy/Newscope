package rs117.hd.android;

import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import rs117.hd.HdPluginConfig;

public final class RenderResolutionCheck {
    public static void main(String[] args) throws Exception {
        long qhd = RenderResolution.pack(3120, 1440);
        expect(RenderResolution.STANDARD.target(1090, 503, qhd), 1090, 503);
        expect(RenderResolution.SHARP.target(1090, 503, qhd), 1635, 754);
        expect(RenderResolution.DISPLAY.target(1090, 503, qhd), 3120, 1440);
        expect(RenderResolution.DISPLAY.target(765, 503, qhd), 2190, 1440);
        expect(RenderResolution.DISPLAY.target(1090, 503, 0), 1090, 503);
        expect(RenderResolution.DISPLAY.target(1090, 503, RenderResolution.pack(1090, 500)), 1090, 503);
        expect(RenderResolution.SHARP.target(1090, 503, RenderResolution.pack(1200, 555)), 1200, 553);

        long bounded = RenderResolution.DISPLAY.target(1600, 900, RenderResolution.pack(7680, 4320));
        int width = RenderResolution.width(bounded), height = RenderResolution.height(bounded);
        check(width <= 4096 && height <= 4096 && (long) width * height <= 4_608_000, "allocation bound");
        check(RenderResolution.edge(503, 503, 1440) == 1440, "canvas bottom edge");
        check(RenderResolution.edge(0, 1090, 3120) == 0, "canvas left edge");
        check(RenderResolution.edge(71, 1090, 3120) == 203, "viewport offset");
        check(Math.abs(RenderResolution.edge(501, 1090, 3120) / 3120.0 - 501 / 1090.0) < 1 / 3120.0,
                "normalized position");

        java.lang.reflect.Method setting = HdPluginConfig.class.getMethod("androidRenderResolution");
        ConfigItem item = setting.getAnnotation(ConfigItem.class);
        check(setting.isDefault() && item != null && item.keyName().equals("androidRenderResolution"),
                "saved configuration descriptor");
        check(HdPluginConfig.class.getAnnotation(ConfigGroup.class).value().equals("hd"), "owner plugin config group");
        java.lang.reflect.Method encode = ConfigManager.class.getDeclaredMethod("objectToString", Object.class);
        java.lang.reflect.Method decode = ConfigManager.class.getDeclaredMethod("stringToObject", String.class, Class.class);
        encode.setAccessible(true);
        decode.setAccessible(true);
        for (RenderResolution value : RenderResolution.values()) {
            String encoded = (String) encode.invoke(null, value);
            check(decode.invoke(null, encoded, RenderResolution.class) == value, "option round trip: " + value);
        }
        System.out.println("117 HD configuration, aspect, viewport, direct fallback and allocation limits passed.");
    }

    private static void expect(long actual, int width, int height) {
        check(actual == RenderResolution.pack(width, height),
                "expected " + width + "x" + height + ", got "
                        + RenderResolution.width(actual) + "x" + RenderResolution.height(actual));
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
}
