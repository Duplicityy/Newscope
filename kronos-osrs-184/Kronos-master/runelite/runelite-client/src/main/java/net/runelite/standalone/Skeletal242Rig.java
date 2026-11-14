package net.runelite.standalone;

/** Immutable hierarchy and bind poses; animation matrices are per application. */
final class Skeletal242Rig {
    final int poses;
    final int[] parent;
    final float[][][] local, inverseBind, defaults;
    Skeletal242Rig(Skeletal242Reader in, int count) {
        if(count<1 || count>1024)throw new IllegalArgumentException("Skeletal bone count");
        poses=in.u8(); if(poses<1 || poses>32)throw new IllegalArgumentException("Skeletal pose count");
        parent=new int[count]; local=new float[count][poses][]; inverseBind=new float[count][poses][]; defaults=new float[count][poses][];
        for(int b=0;b<count;b++) {
            parent[b]=in.i16();
            if(parent[b]<-1 || parent[b]>=count || parent[b]==b)throw new IllegalArgumentException("Skeletal parent index");
            for(int p=0;p<poses;p++) {
                float[] m=new float[16];for(int i=0;i<16;i++)m[i]=in.f32();local[b][p]=m;
                in.f32();in.f32();in.f32();
                float[] r=Skeletal242Math.rotation(Skeletal242Math.inverse(m)),s=Skeletal242Math.scaling(m);
                defaults[b][p]=new float[]{r[0],r[1],r[2],m[12],m[13],m[14],s[0],s[1],s[2]};
            }
        }
        for(int p=0;p<poses;p++) {
            float[][] world=new float[count][];byte[] marks=new byte[count];
            for(int b=0;b<count;b++) inverseBind[b][p]=Skeletal242Math.inverse(bind(b,p,world,marks));
        }
    }
    private float[] bind(int b,int p,float[][] world,byte[] marks) {
        if(marks[b]==1)throw new IllegalArgumentException("Cyclic skeletal hierarchy");
        if(marks[b]==2)return world[b]; marks[b]=1;
        world[b]=parent[b]<0 ? local[b][p] : Skeletal242Math.multiply(bind(parent[b],p,world,marks),local[b][p]);
        marks[b]=2;return world[b];
    }
    float[][] transforms(float[][] animated,int pose) {
        float[][] world=new float[parent.length][],result=new float[parent.length][];
        for(int b=0;b<parent.length;b++) result[b]=Skeletal242Math.multiply(world(b,animated,world),inverseBind[b][pose]);
        return result;
    }
    private float[] world(int b,float[][] animated,float[][] result) {
        if(result[b]==null) result[b]=parent[b]<0?animated[b]:Skeletal242Math.multiply(world(parent[b],animated,result),animated[b]);
        return result[b];
    }
}
