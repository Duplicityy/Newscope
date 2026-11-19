package net.runelite.standalone;

import java.io.File;
import java.io.IOException;

/** Selects the managed seed while retaining the normal resolver outside the standalone launch. */
final class ManagedCacheDirectory {
    static File select() {
        String path = System.getProperty("newscape.cache.dir");
        if (path == null) {
            return null;
        }
        try {
            File requested = new File(path);
            File base = new File(System.getProperty("user.home"), "newscape-cache").getCanonicalFile();
            File selected = requested.getCanonicalFile();
            if (!requested.isAbsolute() || selected.equals(base)
                || !selected.toPath().startsWith(base.toPath())
                || !selected.isDirectory() || !selected.canRead() || !selected.canWrite()) {
                throw new IOException("Invalid installed cache directory");
            }
            return selected;
        } catch (IOException error) {
            throw new IllegalStateException("The Newscape cache could not be opened.", error);
        }
    }
}
