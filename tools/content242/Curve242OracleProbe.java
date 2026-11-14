package net.runelite.standalone;
import java.io.*;
public final class Curve242OracleProbe {
 public static void main(String[] args)throws Exception {
  int failures=0,samples=0;float max=0;
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))) {
   if(in.readInt()!=0x4b32434f)throw new AssertionError("oracle magic");int count=in.readInt();
   for(int i=0;i<count;i++) {
    int seq=in.readInt(),track=in.readInt(),size=in.readInt();byte[]raw=new byte[size];in.readFully(raw);
    Skeletal242Curve curve=new Skeletal242Curve(new Skeletal242Reader(raw));int n=in.readInt();
    for(int j=0;j<n;j++) {
     float t=in.readFloat(),expected=in.readFloat(),actual=curve.value(t);samples++;
     float difference=Math.abs(expected-actual),relative=difference/Math.max(1,Math.abs(expected));max=Math.max(max,relative);
     if(!Float.isFinite(actual)||relative>.0002f) {if(failures++<25)System.out.println("DIFF seq="+seq+" curve="+track+" t="+t+" expected="+expected+" actual="+actual);}
    }
   }
  }
  System.out.println("oracleSamples="+samples+" maxRelativeError="+max+" failures="+failures);
  if(failures!=0)throw new AssertionError("Independent donor curve mismatch");
 }
}
