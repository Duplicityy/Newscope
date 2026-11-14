package io.ruin.cache;

/** Versioned wide terrain is confined to explicitly imported regions. */
public final class Content242Terrain {
    private static final byte[] MAGIC = {'K','2','4','2','T','E','R','R'};
    public static final int HEADER_LENGTH = 9;
    private Content242Terrain() { }
    public static boolean wide(byte[] data) {
        if (data.length < HEADER_LENGTH) return false;
        for (int i=0;i<MAGIC.length;i++) if (data[i] != MAGIC[i]) return false;
        if (data[8] != 1) throw new IllegalArgumentException("Unsupported extended terrain version");
        return true;
    }
}
