package net.runelite.standalone;

/** Extra model files use reserved ordinary JS5 groups, preserving the 184 wire format. */
public final class Content242Models {
   public static final int FIRST_LOGICAL_ID = 65536;
   public static final int FIRST_BANK_GROUP = 65408;
   public static final int BANK_FILES = 512;
   public static final int MAX_LOGICAL_ID = FIRST_LOGICAL_ID + 127 * BANK_FILES - 1;

   private Content242Models() { }

   public static int validate(int id) {
      if(id < 0 || id > MAX_LOGICAL_ID) throw new IllegalArgumentException("Invalid model reference: " + id);
      return id;
   }

   public static int group(int id) {
      validate(id);
      return id < FIRST_LOGICAL_ID ? id : FIRST_BANK_GROUP + (id - FIRST_LOGICAL_ID) / BANK_FILES;
   }

   public static int file(int id) {
      validate(id);
      return id < FIRST_LOGICAL_ID ? 0 : (id - FIRST_LOGICAL_ID) % BANK_FILES;
   }

   public static boolean ready(AbstractArchive archive, int id) {
      return archive.method4024(group(id), file(id));
   }

   public static byte[] load(AbstractArchive archive, int id) {
      return archive.method4020(group(id), file(id), (short)-9228);
   }
}
