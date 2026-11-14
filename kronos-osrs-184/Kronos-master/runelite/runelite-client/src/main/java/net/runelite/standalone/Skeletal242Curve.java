package net.runelite.standalone;

/** Maya scalar tracks: Hermite/weighted Bezier, constant/step, and five infinity modes. */
final class Skeletal242Curve {
    final int before, after;
    final boolean bezier;
    final int[] times;
    final float[][] points;
    Skeletal242Curve(Skeletal242Reader in) {
        int count=in.u16();in.u8();before=in.u8();after=in.u8();bezier=in.u8()!=0;
        if(count<1 || count>32768 || before>4 || after>4)throw new IllegalArgumentException("Skeletal curve header");
        in.require(count*22);times=new int[count];points=new float[count][5];
        for(int i=0;i<count;i++) {
            // Nine real242 sequences contain repeated ticks. The upper-bound
            // lookup below selects the last key at that tick, avoiding zero spans.
            times[i]=in.i16();if(i>0 && times[i]<times[i-1])throw new IllegalArgumentException("Unordered skeletal keys");
            for(int j=0;j<5;j++)points[i][j]=in.f32();
        }
    }
    float value(float time) {
        int last=times.length-1;
        if(time<times[0])return outside(time,true);
        if(time>times[last])return outside(time,false);
        int low=0,high=last;
        while(low<high) { int mid=(low+high+1)>>>1;if(times[mid]<=time)low=mid;else high=mid-1; }
        if(low==last)return points[last][0];
        float[] a=points[low],b=points[low+1];
        if(a[3]==0 && a[4]==0)return a[0];
        if(a[3]==Float.MAX_VALUE && a[4]==Float.MAX_VALUE)return time==times[low]?a[0]:b[0];
        float span=times[low+1]-times[low],t=(time-times[low])/span;
        if(!bezier) {
            float slope0=a[3]==0?0:a[4]/a[3],slope1=b[1]==0?0:b[2]/b[1];
            float t2=t*t,t3=t2*t;
            return (2*t3-3*t2+1)*a[0]+(t3-2*t2+t)*span*slope0+(-2*t3+3*t2)*b[0]+(t3-t2)*span*slope1;
        }
        float x1=(a[3]*.33333334f)/span,x2=1-(b[1]*.33333334f)/span;
        float old1=x1,old2=x2,y1=a[0]+a[4]*.33333334f,y2=b[0]-b[2]*.33333334f;
        x1=Math.max(0,x1);x2=Math.min(1,x2);
        if(x1>1 || x2<-1) {
            float[] clamped=clamp(x1,x2);x1=clamped[0];x2=clamped[1];
        }
        if(old1!=x1 && old1!=0)y1=a[0]+(y1-a[0])*x1/old1;
        if(old2!=x2 && old2!=1)y2=b[0]-(b[0]-y2)*(1-x2)/(1-old2);
        float u=t;
        if(x1!=.33333334f || x2!=.6666667f) {
            // After the cache's monotonicity clamp the root on [0,1] is unique.
            float lo=0,hi=1;
            for(int i=0;i<32;i++) {u=(lo+hi)*.5f;float x=cubic(0,x1,x2,1,u);if(x<t)lo=u;else hi=u;}
            u=(lo+hi)*.5f;
        }
        return cubic(a[0],y1,y2,b[0],u);
    }
    private static float cubic(float a,float b,float c,float d,float t) {
        float q=1-t;return q*q*q*a+3*q*q*t*b+3*q*t*t*c+t*t*t*d;
    }
    private static float[] clamp(float x,float y) {
        final float epsilon=Math.ulp(1f); y=1-y;x=Math.max(0,x);y=Math.max(0,y);
        if(x>1 || y>1) {
            float discriminant=1+x*(x-2+y)+(y-2)*y;
            if(discriminant+epsilon>0) {
                if(x+epsilon<1.3333334f) {
                    float a=x-2,b=x-1,root=(float)Math.sqrt(Math.max(0,a*a-4*b*b));
                    float upper=.5f*(root-a),lower=.5f*(-a-root);
                    if(y+epsilon>upper)y=upper-epsilon;else if(y<lower+epsilon)y=lower+epsilon;
                } else {x=1.3333334f-epsilon;y=.33333334f-epsilon;}
            }
        }
        return new float[]{x,1-y};
    }
    private float outside(float time,boolean start) {
        int last=times.length-1,mode=start?before:after;
        float first=times[0],end=times[last],duration=end-first;
        if(mode==0 || duration==0)return points[start?0:last][0];
        if(mode==1) {
            float[] p=points[start?0:last];float dx=p[start?1:3],dy=p[start?2:4];
            return p[0]+(dx==0?0:(time-(start?first:end))*dy/dx);
        }
        float cycles=(time>end?(time-end):(time-first))/duration;
        int integral=(int)cycles;
        float phase=Math.abs(cycles-integral)*duration;
        float repeats=Math.abs(1+integral),half=repeats/2;
        boolean odd=half-(int)half!=0;
        float mapped;
        if(mode==4) mapped=start?(odd?first+phase:end-phase):(odd?end-phase:first+phase);
        else mapped=start?end-phase:first+phase;
        float result=value(mapped);
        if(mode==3)result+=(start?-1:1)*repeats*(points[last][0]-points[0][0]);
        return result;
    }
}
