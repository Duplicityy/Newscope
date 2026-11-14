package net.runelite.standalone;
import java.io.*;
import java.util.*;
/** Checks the actual inventory option slots consumed by the unmodified input UI. */
public final class ChargedItem242Probe {
 public static void main(String[] args)throws Exception {
  int count;
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))) {
   count=in.readInt();
   for(int n=0;n<count;n++) {
    int source=in.readInt(),target=in.readInt(),length=in.readInt();byte[] raw=new byte[length];in.readFully(raw);
    ItemDefinition def=new ItemDefinition();Buffer buffer=new Buffer(raw);def.method4527(buffer);
    if(buffer.offset!=length)throw new AssertionError("Incomplete item decode "+target);
    for(int slot=0;slot<5;slot++) {
     String value=in.readUTF();String expected=value.isEmpty()?null:value;
     if(!Objects.equals(def.inventoryActions[slot],expected))throw new AssertionError("Wrong native action "+target+"/"+slot);
    }
    if(!"Wield".equals(def.inventoryActions[1]))throw new AssertionError("No normal Wield action "+target);
    System.out.println("item="+source+" target="+target+" nativeActions="+Arrays.toString(def.inventoryActions));
   }
   if(in.read()!=-1)throw new AssertionError("Trailing fixture data");
  }
  System.out.println("PASS native charged item actions="+count+"; normal Wield opcode slot retained");
 }
}
