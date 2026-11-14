package net.runelite.standalone;
import java.io.*;
import java.lang.reflect.*;
public final class Npc242SkeletalProbe {
 static byte[]blob(DataInputStream in)throws IOException{int n=in.readInt();byte[]r=new byte[n];in.readFully(r);return r;}
 public static void main(String[]args)throws Exception {
  int frames=0,npcs;
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))) {
   if(in.readInt()!=0x4b324e50)throw new AssertionError("fixture magic");npcs=in.readInt();
   for(int n=0;n<npcs;n++) {
    int npc=in.readInt(),parts=in.readInt();ModelData[]data=new ModelData[parts];for(int i=0;i<parts;i++)data[i]=new ModelData(blob(in));
    Model original=new ModelData(data,parts).copy$toModel(64,768,-50,-10,-50);
    int sequences=in.readInt(),changed=0;
    for(int s=0;s<sequences;s++) {
     int seqId=in.readInt(),base=in.readInt();SequenceDefinition seq=new SequenceDefinition();seq.method4658(new Buffer(blob(in)));seq.method4687();
     Skeleton skeleton=new Skeleton(base,blob(in));Skeletal242Animation animation=new Skeletal242Animation(blob(in),skeleton);
     int duration=seq.skeletalEnd242-seq.skeletalStart242;
     for(int frame:new int[]{0,duration/4,duration/2,Math.max(0,duration-1)}) {
      Model animated=original.method2355(false);animation.apply(animated,frame);int moved=0;
      for(int v=0;v<original.verticesCount;v++) {
       if(Math.abs((long)animated.verticesX[v])>100000 || Math.abs((long)animated.verticesY[v])>100000 || Math.abs((long)animated.verticesZ[v])>100000)throw new AssertionError("unbounded deformation "+npc+"/"+seqId);
       if(animated.verticesX[v]!=original.verticesX[v] || animated.verticesY[v]!=original.verticesY[v] || animated.verticesZ[v]!=original.verticesZ[v])moved++;
      }
      if(moved>0)changed++;frames++;
     }
    }
    if(changed==0)throw new AssertionError("Static weighted NPC "+npc);
    System.out.println("npc="+npc+" vertices="+original.verticesCount+" faces="+original.indicesCount+" changedPoses="+changed);
   }
  }
  // Exercise capacities at the exact legacy overflow boundaries without a GPU/window.
  Model large=new Model();large.verticesCount=6000;large.indicesCount=6000;
  Method capacity=Model.class.getDeclaredMethod("ensureRenderCapacity242");capacity.setAccessible(true);capacity.invoke(large);
  if(Model.modelViewportXs.length<6000 || Model.field1437.length<6000)throw new AssertionError("Large mesh scratch capacity");
  Model.field1421[0]=0;Method bucket=Model.class.getDeclaredMethod("addDepthFace242",int.class,int.class);bucket.setAccessible(true);
  for(int i=0;i<600;i++)bucket.invoke(null,0,i);
  if(Model.field1421[0]!=600 || Model.field1422[0][599]!=599)throw new AssertionError("Depth bucket512 overflow");
  ModelData smooth=new ModelData();smooth.verticesCount=10001;smooth.faceCount=1;
  smooth.verticesX=new int[10001];smooth.verticesY=new int[10001];smooth.verticesZ=new int[10001];
  smooth.verticesX[10000]=20;smooth.verticesY[9999]=20;smooth.verticesZ[9998]=20;
  smooth.indices1=new int[]{10000};smooth.indices2=new int[]{9999};smooth.indices3=new int[]{9998};
  ModelData.method2777(smooth,smooth,0,0,0,false);
  if(ModelData.field1639.length<10001 || ModelData.field1640.length<10001)throw new AssertionError("Normal merge10000 overflow");
  System.out.println("actualNpcAssemblies="+npcs+" appliedWeightedPoses="+frames+" boundedScratchAndBucket=PASS");
 }
}
