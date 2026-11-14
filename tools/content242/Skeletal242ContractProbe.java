package net.runelite.standalone;
import java.io.*;
import java.util.*;

/** Constructed edge cases test semantics the cache's happy-path corpus cannot. */
public final class Skeletal242ContractProbe {
 static int checks;
 static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
 interface Write {void run(DataOutputStream out)throws Exception;}
 static byte[] bytes(Write w)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);w.run(d);return b.toByteArray();}
 static void reject(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
 static byte[] skeleton(int parent)throws Exception{return bytes(d->{d.write(new byte[]{1,5,1,0});d.writeShort(2);d.writeByte(1);for(int b=0;b<2;b++){d.writeShort(b==0?-1:parent);for(int i=0;i<16;i++)d.writeFloat(i%5==0?1:i==12?(b==0?10:2):0);for(int i=0;i<3;i++)d.writeFloat(0);}});}
 static byte[] curve(float y,int mode,boolean weighted)throws Exception{return bytes(d->{d.writeShort(2);d.writeByte(0);d.writeByte(mode);d.writeByte(mode);d.writeByte(weighted?1:0);for(int i=0;i<2;i++){d.writeShort(i*10);d.writeFloat(y+i*10);for(int j=0;j<4;j++)d.writeFloat(10);}});}
 public static void main(String[]args)throws Exception{
  Skeleton s=new Skeleton(7,skeleton(0));
  float[][] local={Skeletal242Math.identity(),Skeletal242Math.identity()};local[0][12]=20;local[1][12]=5;
  float[][] finalPose=s.skeletal242.transforms(local,0);
  check(Math.abs(finalPose[0][12]-10)<1e-6,"root bind inverse");check(Math.abs(finalPose[1][12]-13)<1e-6,"parent multiplied before child");
  byte[] cyclic=skeleton(1);reject(()->new Skeleton(7,cyclic),"self parent accepted");
  reject(()->new Skeleton(7,Arrays.copyOf(cyclic,cyclic.length-1)),"truncated skeleton accepted");
  byte[] nonfinite=skeleton(0);Arrays.fill(nonfinite,9,13,(byte)255);reject(()->new Skeleton(7,nonfinite),"nonfinite matrix accepted");
  for(boolean bezier:new boolean[]{false,true})for(int mode=0;mode<5;mode++){
   Skeletal242Curve c=new Skeletal242Curve(new Skeletal242Reader(curve(20,mode,bezier)));
   check(Math.abs(c.value(5)-25)<.0001,"linear interpolation");
   float before=c.value(-5),after=c.value(15);
   float expectedBefore=mode==0?20:mode==1||mode==3?15:25;
   float expectedAfter=mode==0?30:mode==1||mode==3?35:25;
   check(Math.abs(before-expectedBefore)<.0001,"pre-infinity mode "+mode);check(Math.abs(after-expectedAfter)<.0001,"post-infinity mode "+mode);
  }
  byte[] alpha=bytes(d->{d.writeByte(1);d.writeShort(7);d.writeShort(0);d.writeShort(10);d.writeByte(0);d.writeShort(1);d.writeByte(4);d.writeByte(64);d.writeByte(16);d.write(curve(.5f,0,false));});
  Skeletal242Animation animation=new Skeletal242Animation(alpha,s);Model m=new Model();m.faceAlphas=new byte[]{(byte)200};m.faceLabelsAlpha=new int[][]{{0}};
  animation.applyAlpha(m,0);check((m.faceAlphas[0]&255)==255,"alpha clamps to255");
  byte[] invalidChannel=alpha.clone();invalidChannel[12]=99;reject(()->new Skeletal242Animation(invalidChannel,s),"unknown channel accepted");
  Model original=new Model();original.verticesCount=1;original.verticesX=new int[]{1};original.verticesY=new int[]{2};original.verticesZ=new int[]{3};original.skeletalBones242=new int[][]{{0}};original.skeletalWeights242=new int[][]{{255}};original.faceAlphas=new byte[0];
  Model shared=original.method2355(false);float[] translation=Skeletal242Math.identity();translation[12]=10;shared.applySkeletal242(new float[][]{translation});check(original.verticesX[0]==1&&shared.verticesX[0]==11,"animation does not mutate cached bind mesh");
  System.out.println("skeletalContractAssertions="+checks+" PASS");
 }
}
