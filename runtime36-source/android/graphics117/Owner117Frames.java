import java.nio.ByteBuffer;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import rs117.hd.HdPlugin;
import rs117.hd.android.AndroidGl;

/** Only complete genuine117 scene+UI frames may replace the normal CPU publisher. */
public final class Owner117Frames {
    private static final java.lang.reflect.Field FRAME_LOCK,FRAME_SEQUENCE;
    private static long holdUntil=-1;
    // Presentation worker only. Describes the actual copy, including a disable
    // racing after that copy. The GL-active flag cannot identify frame ownership.
    private static boolean copied117;
    public static boolean copied117Frame(){return copied117;}
    private static int tagFrame(int result){
        if(result>0)copied117=true;
        return result==Integer.MIN_VALUE?0:result;
    }
    static {
        try{
            FRAME_LOCK=KronosBoot.class.getDeclaredField("frameLock");FRAME_LOCK.setAccessible(true);
            FRAME_SEQUENCE=KronosBoot.class.getDeclaredField("frameSequence");FRAME_SEQUENCE.setAccessible(true);
        }catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static long cpuSequence(){
        try{synchronized(FRAME_LOCK.get(null)){return FRAME_SEQUENCE.getLong(null);}}
        catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    public static int copyFrame(ByteBuffer destination,int[] dimensions,boolean refresh){
        // Failure may be observed before OR after the unfinished UI plane is published. Wait through
        // that sequence and the following boundary, not just one fast Android presentation iteration.
        if(HdPlugin.suppressCpuFrame()){holdUntil=cpuSequence()+1;return 0;}
        // AndroidGl publishes context/frame state through volatile/synchronized boundaries.
        // The native client's callback field is plain: it cannot be the cross-thread GPU gate.
        // During startup this also holds the last complete CPU frame until GL has a full scene.
        if(AndroidGl.active()){
            holdUntil=-1;
            int frame=AndroidGl.copyFrame(destination,dimensions,refresh);
            return tagFrame(frame);
        }
        Client client=RuneLite.getInjector()==null?null:RuneLite.getInjector().getInstance(Client.class);
        if(client==null||!(client.getDrawCallbacks() instanceof HdPlugin)){
            if(holdUntil>=0){if(cpuSequence()<=holdUntil)return 0;holdUntil=-1;}
            copied117=false;
            return Integer.MIN_VALUE;
        }
        holdUntil=-1;
        int result=AndroidGl.copyFrame(destination,dimensions,refresh);
        return tagFrame(result);
    }
    private Owner117Frames(){}
}
