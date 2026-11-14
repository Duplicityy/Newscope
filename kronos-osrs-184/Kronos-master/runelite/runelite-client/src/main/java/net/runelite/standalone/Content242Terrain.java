package net.runelite.standalone;

/** An explicit per-region header selects wide palette IDs; ordinary 184 maps stay byte encoded. */
final class Content242Terrain {
   private static final byte[] MAGIC = {'K','2','4','2','T','E','R','R'};
   static final int HEADER_LENGTH = 9;
   private Content242Terrain() { }
   static boolean wide(Buffer input) {
      byte[] data = input.array;
      if(data.length < HEADER_LENGTH) return false;
      for(int i = 0; i < MAGIC.length; i++) if(data[i] != MAGIC[i]) return false;
      if(data[8] != 1) throw new IllegalArgumentException("Unsupported extended terrain version");
      if(input.offset == 0) input.offset = HEADER_LENGTH;
      return true;
   }
}
