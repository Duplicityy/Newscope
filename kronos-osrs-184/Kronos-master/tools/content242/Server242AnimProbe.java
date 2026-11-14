package io.ruin.cache;
import java.io.*;
import io.ruin.api.buffer.InBuffer;
public final class Server242AnimProbe {
 static byte[] blob(DataInputStream in)throws IOException{int n=in.readInt();byte[]r=new byte[n];in.readFully(r);return r;}
 public static void main(String[]args)throws Exception {
  int count;
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))) {
   if(in.readInt()!=0x4b32414e)throw new AssertionError("fixture magic");count=in.readInt();
   for(int i=0;i<count;i++) {
    int id=in.readInt();in.readInt();byte[] raw=blob(in);blob(in);blob(in);
    AnimDef a=new AnimDef();a.method4576(new InBuffer(raw));a.method4578();
    if(a.skeletalId242==-1 || a.frameData.length!=a.skeletalEnd242-a.skeletalStart242 || a.getDuration()!=a.frameData.length*20)
      throw new AssertionError("weighted duration "+id);
   }
  }
  AnimDef original=new AnimDef();original.method4576(new InBuffer(new byte[]{1,0,2,0,3,0,4,0,0,0,1,0,1,0,1,0}));original.method4578();
  if(original.getDuration()!=140 || original.frameData.length!=2)throw new AssertionError("legacy duration changed");
  System.out.println("serverWeightedTimelines="+count+" legacyDuration=140 PASS");
 }
}
