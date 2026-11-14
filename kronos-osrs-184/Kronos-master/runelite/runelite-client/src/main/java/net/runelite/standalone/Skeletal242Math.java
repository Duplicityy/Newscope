package net.runelite.standalone;

/** Column-major affine matrices, matching the cache's bind-pose convention. */
final class Skeletal242Math {
    static float[] identity() { float[] m = new float[16]; m[0]=m[5]=m[10]=m[15]=1; return m; }
    static float[] multiply(float[] a, float[] b) {
        float[] c = new float[16];
        for (int column=0; column<4; column++) for (int row=0; row<4; row++)
            c[column*4+row] = a[row]*b[column*4] + a[4+row]*b[column*4+1]
                + a[8+row]*b[column*4+2] + a[12+row]*b[column*4+3];
        return c;
    }
    static float[] inverse(float[] a) {
        // Pivoted Gauss-Jordan avoids assuming a rigid (unscaled) bind matrix.
        double[][] work = new double[4][8];
        for(int r=0;r<4;r++) { for(int c=0;c<4;c++) work[r][c]=a[c*4+r]; work[r][r+4]=1; }
        for(int c=0;c<4;c++) {
            int pivot=c;
            for(int r=c+1;r<4;r++) if(Math.abs(work[r][c])>Math.abs(work[pivot][c])) pivot=r;
            if(Math.abs(work[pivot][c])<1e-12) throw new IllegalArgumentException("Singular skeletal bind matrix");
            double[] swap=work[pivot]; work[pivot]=work[c]; work[c]=swap;
            double divisor=work[c][c]; for(int k=0;k<8;k++) work[c][k]/=divisor;
            for(int r=0;r<4;r++) if(r!=c) { double factor=work[r][c]; for(int k=0;k<8;k++) work[r][k]-=factor*work[c][k]; }
        }
        float[] result=new float[16];
        for(int r=0;r<4;r++) for(int c=0;c<4;c++) result[c*4+r]=(float)work[r][c+4];
        return result;
    }
    static float[] rotation(float[] inverse) {
        float x=(float)-Math.asin(Math.max(-1,Math.min(1,inverse[6]))), y, z;
        if(Math.abs(Math.cos(x))>.005) { y=(float)Math.atan2(inverse[2],inverse[10]); z=(float)Math.atan2(inverse[4],inverse[5]); }
        else { y=(float)Math.atan2(inverse[1],inverse[0]); if(inverse[6]>=0)y=-y; z=0; }
        return new float[]{x,y,z};
    }
    static float[] scaling(float[] a) {
        return new float[]{length(a,0),length(a,4),length(a,8)};
    }
    private static float length(float[] a,int i) { return (float)Math.sqrt(a[i]*a[i]+a[i+1]*a[i+1]+a[i+2]*a[i+2]); }
    static float[] compose(float[] v) {
        // Cache rotation order: Ry * Rx * Rz; scale pre-multiplies rotation.
        float sx=(float)Math.sin(v[0]),cx=(float)Math.cos(v[0]),sy=(float)Math.sin(v[1]),cy=(float)Math.cos(v[1]);
        float sz=(float)Math.sin(v[2]),cz=(float)Math.cos(v[2]);
        float[] rx=identity(),ry=identity(),rz=identity();
        rx[5]=cx;rx[6]=sx;rx[9]=-sx;rx[10]=cx;
        ry[0]=cy;ry[2]=-sy;ry[8]=sy;ry[10]=cy;
        rz[0]=cz;rz[1]=sz;rz[4]=-sz;rz[5]=cz;
        float[] result=multiply(multiply(ry,rx),rz);
        for(int c=0;c<4;c++) {result[c*4]*=v[6];result[c*4+1]*=v[7];result[c*4+2]*=v[8];}
        result[12]=v[3];result[13]=v[4];result[14]=v[5];
        return result;
    }
}
