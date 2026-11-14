package io.ruin.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** A cache declares the decoder it needs; old clients must not receive extended records. */
public final class CacheCapability {
    public static final String MARKER = "content242-format.properties";
    public static final String FORMAT = "k242-skeletal-v1";
    public static final int LEGACY_REVISION = 184;
    public static final int EXTENDED_REVISION = 184242;

    private CacheCapability() { }

    public static int revision(File cache) throws IOException {
        if (!cache.isDirectory())
            throw new IOException("Cache directory does not exist: " + cache);
        File marker = new File(cache, MARKER);
        if (!marker.exists())
            return LEGACY_REVISION;
        if (!marker.isFile() || marker.length() == 0 || marker.length() > 4096)
            throw new IOException("Invalid cache capability marker");
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(marker)) {
            properties.load(input);
        }
        if (!FORMAT.equals(properties.getProperty("format"))
                || !Integer.toString(EXTENDED_REVISION).equals(properties.getProperty("js5_revision")))
            throw new IOException("Unsupported cache capability; refusing to serve incompatible assets");
        return EXTENDED_REVISION;
    }
}
