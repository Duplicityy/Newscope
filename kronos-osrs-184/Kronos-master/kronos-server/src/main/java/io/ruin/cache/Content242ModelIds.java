package io.ruin.cache;

/** Logical model references above 65535 address files in reserved index-7 banks. */
public final class Content242ModelIds {
    public static final int FIRST_LOGICAL_ID = 65536, FIRST_BANK_GROUP = 65408, BANK_FILES = 512;
    public static final int MAX_LOGICAL_ID = FIRST_LOGICAL_ID + 127 * BANK_FILES - 1;
    private Content242ModelIds() { }
    public static int validate(int id) {
        if (id < 0 || id > MAX_LOGICAL_ID) throw new IllegalArgumentException("Invalid model reference: " + id);
        return id;
    }
    public static int group(int id) { validate(id); return id < FIRST_LOGICAL_ID ? id : FIRST_BANK_GROUP + (id-FIRST_LOGICAL_ID)/BANK_FILES; }
    public static int file(int id) { validate(id); return id < FIRST_LOGICAL_ID ? 0 : (id-FIRST_LOGICAL_ID)%BANK_FILES; }
}
