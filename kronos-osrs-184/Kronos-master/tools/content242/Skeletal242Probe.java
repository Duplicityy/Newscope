package net.runelite.standalone;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real donor-cache decoder coverage plus independent mathematical/lifecycle assertions. */
public final class Skeletal242Probe {
    static int checks;
    static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
    static byte[] blob(DataInputStream in)throws IOException {int n=in.readInt();if(n<0||n>16777216)throw new IOException("blob bound");byte[] b=new byte[n];in.readFully(b);return b;}
    static Model model() {Model m=new Model();m.verticesCount=1;m.verticesX=new int[]{10};m.verticesY=new int[]{20};m.verticesZ=new int[]{30};m.skeletalBones242=new int[][]{{0}};m.skeletalWeights242=new int[][]{{255}};return m;}
    static void math() {
        float[] t=Skeletal242Math.identity();t[12]=7;t[13]=11;t[14]=13;
        Model m=model();m.applySkeletal242(new float[][]{t});check(m.verticesX[0]==17&&m.verticesY[0]==9&&m.verticesZ[0]==17,"coordinate signs and translation");
        float[] combined=Skeletal242Math.multiply(t,Skeletal242Math.inverse(t));
        for(int i=0;i<16;i++)check(Math.abs(combined[i]-((i%5)==0?1:0))<.00001,"matrix inverse");
        m=model();m.skeletalBones242[0]=new int[]{0,1};m.skeletalWeights242[0]=new int[]{127,128};
        float[] t2=Skeletal242Math.identity();t2[12]=-7;
        m.applySkeletal242(new float[][]{t,t2});check(m.verticesX[0]==9,"normalized multi-bone weight");
        try {new Skeletal242Reader(new byte[1]).u16();throw new AssertionError("truncation accepted");}catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args)throws Exception {
        Path folder=Paths.get(args[0]);math();int sequences=0,alpha=0,keys=0,weightedModels=0,models=0,oversize=0;
        List<String> failures=new ArrayList<>();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(folder.resolve("animations.bin"))))) {
            check(in.readInt()==0x4b32414e,"animation fixture magic");int count=in.readInt();
            for(int i=0;i<count;i++) {
                int id=in.readInt(),base=in.readInt();byte[] metadata=blob(in),bind=blob(in),raw=blob(in);
                try {
                    Skeleton skeleton=new Skeleton(base,bind);Skeletal242Animation a=new Skeletal242Animation(raw,skeleton);
                    SequenceDefinition seq=new SequenceDefinition();seq.method4658(new Buffer(metadata));seq.method4687();
                    check(seq.frameLengths.length==seq.skeletalEnd242-seq.skeletalStart242,"timeline "+id);
                    check(seq.skeletalId242>=0,"weighted marker "+id);
                    for(Skeletal242Curve[] row:a.bones)if(row!=null)for(Skeletal242Curve c:row)if(c!=null)keys+=c.times.length;
                    for(Skeletal242Curve[] row:a.other)if(row!=null)for(Skeletal242Curve c:row)if(c!=null)keys+=c.times.length;
                    int end=seq.skeletalEnd242;
                    for(int frame=seq.skeletalStart242;frame<end;frame++) {
                        for(Skeletal242Curve[] row:a.bones)if(row!=null)for(Skeletal242Curve c:row)if(c!=null)check(Float.isFinite(c.value(frame)),"curve finite "+id);
                        for(Skeletal242Curve[] row:a.other)if(row!=null)for(Skeletal242Curve c:row)if(c!=null)check(Float.isFinite(c.value(frame)),"alpha finite "+id);
                    }
                    for(int frame:new int[]{seq.skeletalStart242,(seq.skeletalStart242+end)/2,end-1}) {
                        float[][] transformed=a.rig.transforms(a.local(frame),a.pose);
                        for(float[] matrix:transformed)for(float value:matrix)check(Float.isFinite(value),"pose finite "+id);
                        Model m=model();a.apply(m,frame);check(m.skeletalBones242[0][0]==0,"weights immutable");
                    }
                    if(a.alpha)alpha++;sequences++;
                } catch(Throwable e) {failures.add("sequence "+id+": "+e.toString());}
            }
            check(in.read()==-1,"animation fixture fully read");
        }
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(folder.resolve("models.bin"))))) {
            check(in.readInt()==0x4b324d4f,"model fixture magic");int count=in.readInt();
            for(int i=0;i<count;i++) {
                int id=in.readInt(),vertices=in.readInt(),faces=in.readInt();byte[] raw=blob(in);
                try {
                    ModelData d=new ModelData(raw);check(d.verticesCount==vertices && d.faceCount==faces,"geometry counts "+id);
                    ModelData copy=new ModelData(d,false,false,false,false);
                    check(copy.skeletalBones242==d.skeletalBones242,"copy retains weights "+id);
                    ModelData merged=new ModelData(new ModelData[]{d,copy},2);
                    Model lit=d.copy$toModel(64,768,-50,-10,-50);
                    check(lit.verticesCount==vertices && lit.indicesCount==faces,"large mesh lighting topology "+id);
                    if(d.skeletalBones242!=null) {
                        check(merged.skeletalBones242!=null,"merge retains weights "+id);
                        check(lit.skeletalBones242==d.skeletalBones242,"lit weights "+id);
                        Model shared=lit.method2355(false);check(shared.skeletalBones242==lit.skeletalBones242,"shared weights "+id);
                        weightedModels++;
                    }
                    if(Math.max(vertices,faces)>4700)oversize++;
                    models++;
                } catch(Throwable e){failures.add("model "+id+": "+e.toString());}
            }
        }
        System.out.println("sequences="+sequences+" alpha="+alpha+" curveKeys="+keys+" models="+models+" weightedModels="+weightedModels+" oversize="+oversize+" assertions="+checks+" failures="+failures.size());
        for(String failure:failures)System.out.println(failure);
        if(!failures.isEmpty())throw new AssertionError("Real cache verification failed");
    }
}
