package net.runelite.standalone;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cached-model animations are separate from 184 Frames and never enter its decoder. */
final class Skeletal242Animation {
    private static final Map<Integer,Skeletal242Animation> CACHE=new LinkedHashMap<Integer,Skeletal242Animation>(32,.75f,true);
    private static int cachedKeys;
    final Skeleton skeleton;
    final Skeletal242Rig rig;
    final int pose;
    final Skeletal242Curve[][] bones,other;
    final boolean alpha;
    final int curveKeys;
    Skeletal242Animation(byte[] raw,Skeleton skeleton) {
        this.skeleton=skeleton;rig=skeleton.skeletal242;
        if(rig==null)throw new IllegalArgumentException("Missing skeletal bind pose");
        Skeletal242Reader in=new Skeletal242Reader(raw);
        int version=in.u8(),base=in.u16();
        if(version<1 || version>2 || base!=skeleton.id)throw new IllegalArgumentException("Skeletal sequence identity/version");
        in.u16();in.u16();pose=in.u8();int count=in.u16();
        if(pose>=rig.poses)throw new IllegalArgumentException("Skeletal pose index");
        bones=new Skeletal242Curve[rig.parent.length][];other=new Skeletal242Curve[skeleton.count][];
        boolean hasAlpha=false; int keys=0;
        int[] channelIndex={-1,0,1,2,3,4,5,6,7,8,0,1,2,3,4,5,0},widths={0,9,3,6,1,3};
        for(int i=0;i<count;i++) {
            int type=in.u8(),bone=in.smart(),channel=in.u8();
            if(type<1 || type>=widths.length || channel<1 || channel>=channelIndex.length || channelIndex[channel]>=widths[type])
                throw new IllegalArgumentException("Skeletal curve channel");
            Skeletal242Curve[][] target=type==1?bones:other;
            if(bone<0 || bone>=target.length)throw new IllegalArgumentException("Skeletal curve bone index");
            if(target[bone]==null)target[bone]=new Skeletal242Curve[widths[type]];
            if(target[bone].length!=widths[type] || target[bone][channelIndex[channel]]!=null)
                throw new IllegalArgumentException("Duplicate skeletal curve");
            target[bone][channelIndex[channel]]=new Skeletal242Curve(in);
            keys+=target[bone][channelIndex[channel]].times.length;
            hasAlpha|=type==4;
        }
        in.end();alpha=hasAlpha;curveKeys=keys;
    }
    static Skeletal242Animation load(int id) {
        synchronized(CACHE) {
            Skeletal242Animation cached=CACHE.get(id);if(cached!=null)return cached;
            AbstractArchive animations=SequenceDefinition.SequenceDefinition_animationsArchive,bases=class183.SequenceDefinition_skeletonsArchive;
            if(animations==null || bases==null)return null;
            byte[] raw=animations.method4020(id>>>16,id&65535,(short)0);if(raw==null)return null;
            Skeletal242Reader in=new Skeletal242Reader(raw);in.u8();int base=in.u16();
            byte[] bind=bases.method4020(base,0,(short)0);if(bind==null)return null;
            cached=new Skeletal242Animation(raw,new Skeleton(base,bind));CACHE.put(id,cached);cachedKeys+=cached.curveKeys;
            java.util.Iterator<Map.Entry<Integer,Skeletal242Animation>> entries=CACHE.entrySet().iterator();
            while(CACHE.size()>1 && (CACHE.size()>32 || cachedKeys>600000)) {
                Map.Entry<Integer,Skeletal242Animation> eldest=entries.next();cachedKeys-=eldest.getValue().curveKeys;entries.remove();
            }
            return cached;
        }
    }
    float[][] local(float time) {
        float[][] result=new float[rig.parent.length][];
        for(int b=0;b<result.length;b++)result[b]=bone(b,time);
        return result;
    }
    float[] bone(int b,float time) {
        float[] values=rig.defaults[b][pose].clone();
        if(bones[b]!=null)for(int i=0;i<9;i++)if(bones[b][i]!=null)values[i]=bones[b][i].value(time);
        return Skeletal242Math.compose(values);
    }
    void apply(Model model,float time) { apply(model,time,null,0,null); }
    void apply(Model model,float time,Skeletal242Animation secondary,float secondaryTime,boolean[] mask) {
        float[][] local=local(time);
        if(secondary!=null && mask!=null && skeleton.id==secondary.skeleton.id && pose==secondary.pose && rig.parent.length==secondary.rig.parent.length) {
            for(int b=0;b<local.length && b<mask.length;b++)if(mask[b])local[b]=secondary.bone(b,secondaryTime);
        }
        model.applySkeletal242(rig.transforms(local,pose));
        applyAlpha(model,time);
    }
    void applyMasked(Model model,float time,boolean[] mask,boolean selected,boolean alpha) {
        if(mask==null) {
            model.applySkeletal242(rig.transforms(local(time),pose));
        } else {
            float[][] local=new float[rig.parent.length][];
            for(int b=0;b<local.length;b++) local[b]=(b<mask.length && mask[b])==selected?bone(b,time):rig.local[b][pose];
            model.applySkeletal242(rig.transforms(local,pose));
        }
        if(alpha)applyAlpha(model,time);
    }
    void applyAlpha(Model model,float time) {
        if(!alpha || model.faceLabelsAlpha==null || model.faceAlphas==null)return;
        for(int i=0;i<skeleton.count;i++)if(skeleton.transformTypes[i]==5 && other[i]!=null && other[i][0]!=null) {
            float change=other[i][0].value(time)*255;
            for(int label:skeleton.labels[i])if(label<model.faceLabelsAlpha.length)
                for(int face:model.faceLabelsAlpha[label])model.faceAlphas[face]=(byte)Math.max(0,Math.min(255,(int)((model.faceAlphas[face]&255)+change)));
        }
    }
}
