package rs117.hd.android;

import com.jogamp.opengl.GL4;
import java.awt.Canvas;
import java.io.File;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.lwjgl.opengl.GLALL;
import net.runelite.rlawt.AWTContext;

/**
 * Android EGL/GLES backend for the pinned genuine 117 renderer. This is deliberately
 * an audited GL4 call subset, not a general-purpose desktop OpenGL implementation.
 * All GL work stays on the engine render thread. ART only consumes complete frames.
 */
public final class AndroidGl {
    private static final Object FRAMES=new Object();
    private static volatile String state="117 HD inactive";
    private static volatile boolean active;
    private static volatile int width=765,height=503;
    private static Thread owner;
    private static AWTContext context;
    private static Canvas canvas;
    private static int[] front,back;
    private static int publishedWidth,publishedHeight;
    private static long generation,sequence,copiedSequence=-1;
    private static GL4 adapter;
    private static boolean loaded;
    private static final Map<String,List<Method>> nativeMethods=new HashMap<>();
    private static final Set<String> registered=new HashSet<>(Arrays.asList(NativeBindings.SIGNATURES));
    private static final Set<String> auditedCalls=new HashSet<>(Arrays.asList((
        "glActiveTexture glAttachShader glBindBuffer glBindBufferBase glBindFramebuffer glBindRenderbuffer glBindTexture glBindVertexArray glBlendFunc glBlitFramebuffer glBufferData glBufferSubData glCheckFramebufferStatus glClear glClearColor glCompileShader glCreateProgram glCreateShader glCullFace glDeleteBuffers glDeleteFramebuffers glDeleteProgram glDeleteRenderbuffers glDeleteShader glDeleteTextures glDeleteVertexArrays glDetachShader glDisable glDispatchCompute glDrawArrays glDrawBuffer glEnable glEnableVertexAttribArray glFinish glFramebufferRenderbuffer glFramebufferTexture2D glGenBuffers glGenFramebuffers glGenRenderbuffers glGenTextures glGenVertexArrays glGenerateMipmap glGetError glGetFloatv glGetIntegerv glGetProgramInfoLog glGetProgramiv glGetShaderInfoLog glGetShaderiv glGetUniformBlockIndex glGetUniformLocation glLinkProgram glMapBuffer glMemoryBarrier glReadBuffer glReadPixels glRenderbufferStorageMultisample glShaderSource glTexImage2D glTexParameterf glTexParameterfv glTexParameteri glTexStorage3D glTexSubImage2D glTexSubImage3D glUniform1f glUniform1i glUniform2fv glUniform2i glUniform3f glUniform4f glUniformBlockBinding glUniformMatrix4fv glUnmapBuffer glUseProgram glValidateProgram glVertexAttribIPointer glVertexAttribPointer glViewport"
    ).split(" ")));
    private static final Set<Integer> SCALAR_INTEGER=new HashSet<>(Arrays.asList(32937,36183,3379,35071,34930,35661,35660,35376,37085,37083,37099,33375,33377));
    private static final Set<Integer> SCALAR_SHADER=new HashSet<>(Arrays.asList(35713,35716,35663,35720));
    private static final Set<Integer> SCALAR_PROGRAM=new HashSet<>(Arrays.asList(35714,35715,35716,35717,35718,35719,35382));
    static {
        for(Method m:GLALL.class.getDeclaredMethods())if(registered.contains(m.getName()+"|"+descriptor(m)))
            nativeMethods.computeIfAbsent(m.getName(),k->new ArrayList<>()).add(m);
    }
    private static native String nativeValidate();
    private static native boolean nativeBeginFrame();
    private static native int[] nativeSnapshot();
    private static native long nativeWindowSize(int logicalWidth, int logicalHeight);
    private static boolean enlargedTargetUnavailable;
    private static volatile long presentedRenderSize;
    private static volatile boolean directPresentation;
    private static volatile int targetFps=60;
    public static void targetFps(int target){targetFps=Math.max(1,Math.min(240,target));}
    public static int targetFps(){return targetFps;}
    public static void gpuDirectPublish(int w,int h){
        checkThread();
        synchronized(FRAMES){
            if(!directPresentation){front=back=null;copiedSequence=-1;}
            presentedRenderSize = RenderResolution.pack(width, height);
            directPresentation=true;publishedWidth=w;publishedHeight=h;sequence++;recordPresented();
        }
    }
    private static void recordPresented(){
        long now=System.nanoTime();metricFrames++;
        if(metricStart==0){metricStart=now;metricFrames=0;}
        else if(now-metricStart>=1000000000L){publishedFps=(int)(metricFrames*1000000000L/(now-metricStart));metricStart=now;metricFrames=0;}
    }
    public static boolean beginFrame(){checkThread();return nativeBeginFrame();}
    private static long metricStart;
    private static int metricFrames;
    private static volatile int publishedFps;
    public static String frameStatus() {
        String display = " | " + publishedFps + " fps | " + (directPresentation ? "GPU display" : "Buffered display");
        long size = presentedRenderSize;
        return directPresentation && size != 0
                ? display + " | " + RenderResolution.width(size) + "×" + RenderResolution.height(size)
                : display;
    }

    public static synchronized GL4 create(Canvas target) {
        if(context!=null)throw new IllegalStateException("117 HD context already exists");
        owner=Thread.currentThread();canvas=Objects.requireNonNull(target,"canvas");
        width=Math.max(1,target.getWidth());height=Math.max(1,target.getHeight());
        enlargedTargetUnavailable = false;
        presentedRenderSize = 0;
        state="Preparing genuine 117 HD";
        try {
            if(!loaded){
                String path=System.getProperty("newscape.117.library",new File(System.getProperty("user.home"),"jvm/jre/lib/libnewscape117.so").getAbsolutePath());
                System.load(path);loaded=true;
            }
            context=new AWTContext(target);context.configurePixelFormat(8,24,0);context.createGLContext();
            String caps=nativeValidate();
            adapter=(GL4)Proxy.newProxyInstance(AndroidGl.class.getClassLoader(),new Class<?>[]{GL4.class},AndroidGl::dispatch);
            synchronized(FRAMES){front=null;back=null;publishedWidth=publishedHeight=0;generation++;sequence=0;copiedSequence=-1;active=true;}
            state="117 HD 1.0.11 compatibility port | "+caps;
            return adapter;
        } catch(Throwable failure) {
            state="117 HD unavailable: "+message(failure);
            try{if(context!=null)context.destroy();}catch(Throwable suppressed){failure.addSuppressed(suppressed);}
            context=null;adapter=null;canvas=null;owner=null;
            synchronized(FRAMES){active=false;front=back=null;}
            throw unchecked(failure);
        }
    }
    public static void resize(int w,int h){
        checkThread();if(w<1||h<1||w>8192||h>8192)throw new IllegalArgumentException("117 HD framebuffer dimensions");
        width=w;height=h;context.getFramebuffer(false);
    }
    public static long resizeForDisplay(int logicalWidth, int logicalHeight, RenderResolution resolution) {
        checkThread();
        long window = nativeWindowSize(logicalWidth, logicalHeight);
        long logical = RenderResolution.pack(logicalWidth, logicalHeight);
        long target = enlargedTargetUnavailable ? logical : resolution.target(logicalWidth, logicalHeight, window);
        if (target != logical) {
            try {
                resize(RenderResolution.width(target), RenderResolution.height(target));
                return target;
            } catch (IllegalStateException cannotAllocateLargerTarget) {
                enlargedTargetUnavailable = true;
                System.err.println("117 HD switched to Standard resolution: " + cannotAllocateLargerTarget.getMessage());
            }
        }
        resize(logicalWidth, logicalHeight);
        return logical;
    }

    public static void present(){checkThread();context.swapBuffers();}
    public static int setSwapInterval(int interval){checkThread();return context.setSwapInterval(interval);}
    public static synchronized void destroy(){
        if(context==null){synchronized(FRAMES){active=false;front=back=null;}return;}
        checkThread();
        try{context.destroy();}
        finally{context=null;adapter=null;canvas=null;owner=null;synchronized(FRAMES){active=false;front=back=null;publishedWidth=publishedHeight=0;generation++;}state="117 HD inactive";directPresentation=false;publishedFps=metricFrames=0;metricStart=0;}
    }
    public static String status(){return state;}
    public static boolean active(){return active;}
    public static long sequence(){synchronized(FRAMES){return sequence;}}
    public static int[] dimensions(){synchronized(FRAMES){return new int[]{publishedWidth,publishedHeight};}}
    public static int[] latestFrame(){synchronized(FRAMES){return directPresentation||front==null?null:front.clone();}}
    /** Coherent optional snapshot for tools; the live app should use copyFrame without this allocation. */
    public static int[] frame(){if(directPresentation){checkThread();return nativeSnapshot();}synchronized(FRAMES){if(!active||front==null)return null;int[] out=new int[front.length+2];out[0]=publishedWidth;out[1]=publishedHeight;System.arraycopy(front,0,out,2,front.length);return out;}}
    /** MIN_VALUE only means inactive. While active/no frame, hold the last complete CPU presentation. */
    public static int copyFrame(ByteBuffer destination,int[] dimensions,boolean refresh){
        synchronized(FRAMES){
            if(!active)return Integer.MIN_VALUE;
            if(front==null)return 0;
            if(dimensions==null||dimensions.length<2)throw new IllegalArgumentException("frame dimensions buffer");
            dimensions[0]=publishedWidth;dimensions[1]=publishedHeight;
            int bytes=Math.multiplyExact(front.length,4);
            if(destination==null||destination.capacity()<bytes)return -bytes;
            if(!refresh&&copiedSequence==sequence)return 0;
            ByteBuffer out=destination.duplicate().order(ByteOrder.nativeOrder());out.clear();out.asIntBuffer().put(front);
            copiedSequence=sequence;return bytes;
        }
    }
    // Called only by this library on the owning engine thread. Native writes back, never front.
    public static int[] gpuFrameBackBuffer(int w,int h){
        checkThread();if(w<1||h<1||w>8192||h>8192)throw new IllegalArgumentException("117 HD native frame dimensions");
        synchronized(FRAMES){int count=Math.multiplyExact(w,h);if(back==null||back.length!=count)back=new int[count];return back;}
    }
    public static void gpuPublish(int w,int h){checkThread();synchronized(FRAMES){if(back==null||back.length!=w*h)throw new IllegalStateException("117 HD frame publication mismatch");int[] old=front;front=back;back=old;publishedWidth=w;publishedHeight=h;sequence++;
        directPresentation=false;recordPresented();
    }}
    public static int canvasWidth(){return width;}
    public static int canvasHeight(){return height;}
    private static void checkThread(){if(owner!=Thread.currentThread()||context==null)throw new IllegalStateException("117 HD GL call outside the owning render thread");}
    private static RuntimeException unchecked(Throwable t){return t instanceof RuntimeException?(RuntimeException)t:new IllegalStateException("117 HD graphics failure",t);}
    private static String message(Throwable t){return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}

    private static Object dispatch(Object proxy,Method method,Object[] input)throws Throwable{
        String name=method.getName();Object[] a=input==null?new Object[0]:input;
        if(name.equals("toString"))return "Newscape audited GLES 117 backend";
        if(name.equals("hashCode"))return System.identityHashCode(proxy);
        if(name.equals("equals"))return proxy==a[0];
        checkThread();
        if(name.equals("setSwapInterval")){int result=setSwapInterval((Integer)a[0]);return method.getReturnType()==void.class?null:result;}
        if(name.equals("isExtensionAvailable")){
            String ext=(String)a[0];
            if(Arrays.asList("GL_ARB_texture_storage","GL_ARB_compute_shader","GL_ARB_shader_storage_buffer_object","GL_ARB_explicit_attrib_location","GL_ARB_shader_image_load_store").contains(ext))return true; // actual GLES3.2 core equivalents, validated at create()
            String extensions=(String)call("glGetString",7939);return Arrays.asList(extensions.split(" ")).contains(ext);
        }
        if(name.equals("getGL")||name.equals("getGL4"))return proxy;
        if(!auditedCalls.contains(name))throw unsupported(method,"outside the pinned renderer's audited GLES call subset");
        if(name.startsWith("glGen")&&a.length>=2&&(a[1] instanceof int[]||a[1] instanceof IntBuffer)){
            int count=i(a[0]),offset=a.length>2?i(a[2]):((IntBuffer)a[1]).position();
            for(int n=0;n<count;n++)putInt(a[1],offset+n,(Integer)call(name));return null;
        }
        if(name.startsWith("glDelete")&&a.length>=2&&(a[1] instanceof int[]||a[1] instanceof IntBuffer)){
            int count=i(a[0]),offset=a.length>2?i(a[2]):((IntBuffer)a[1]).position();
            for(int n=0;n<count;n++)call(name,getInt(a[1],offset+n));return null;
        }
        if(name.equals("glGetIntegerv")){
            int parameter=i(a[0]);if(!SCALAR_INTEGER.contains(parameter))throw unsupported(method,"un-audited vector/integer query "+parameter);
            putInt(a[1],a.length>2?i(a[2]):((IntBuffer)a[1]).position(),(Integer)call("glGetInteger",parameter));return null;
        }
        if(name.equals("glGetFloatv")){
            if(i(a[0])!=34047)throw unsupported(method,"un-audited float query");
            putFloat(a[1],a.length>2?i(a[2]):((FloatBuffer)a[1]).position(),(Float)call("glGetFloat",a[0]));return null;
        }
        if(name.equals("glGetShaderiv")||name.equals("glGetProgramiv")){
            if(!(name.equals("glGetShaderiv")?SCALAR_SHADER:SCALAR_PROGRAM).contains(i(a[1])))throw unsupported(method,"un-audited shader/program query");
            putInt(a[2],a.length>3?i(a[3]):((IntBuffer)a[2]).position(),(Integer)call(name.equals("glGetShaderiv")?"glGetShaderi":"glGetProgrami",a[0],a[1]));return null;
        }
        if(name.equals("glGetShaderInfoLog")||name.equals("glGetProgramInfoLog")){
            String log=(String)call(name,a[0]);byte[] bytes=log.getBytes(StandardCharsets.UTF_8);int maximum=i(a[1]);
            int lengthOffset=a.length==6?i(a[3]):((IntBuffer)a[2]).position();Object output=a.length==6?a[4]:a[3];int outputOffset=a.length==6?i(a[5]):((ByteBuffer)output).position();
            int count=Math.max(0,Math.min(bytes.length,maximum-1));
            for(int x=0;x<count;x++)putByte(output,outputOffset+x,bytes[x]);if(maximum>0)putByte(output,outputOffset+count,(byte)0);
            if(a[2]!=null)putInt(a[2],lengthOffset,count);return null;
        }
        if(name.equals("glShaderSource")){
            int count=i(a[1]);String[] sources=(String[])a[2];if(count<0||count>sources.length)throw new IllegalArgumentException("shader source count");StringBuilder combined=new StringBuilder();
            for(int x=0;x<count;x++){
                String s=sources[x];int len=-1;
                if(a[3] instanceof IntBuffer)len=((IntBuffer)a[3]).get(((IntBuffer)a[3]).position()+x);
                else if(a[3] instanceof int[])len=((int[])a[3])[(a.length>4?i(a[4]):0)+x];
                combined.append(len<0?s:s.substring(0,len));
            }
            return call(name,a[0],combined.toString());
        }
        if(name.equals("glBufferData")){
            if(a[2]==null)return call(name,a[0],((Number)a[1]).longValue(),a[3]);
            return call(name,a[0],bounded((Buffer)a[2],((Number)a[1]).longValue()),a[3]);
        }
        if(name.equals("glBufferSubData"))return call(name,a[0],((Number)a[1]).longValue(),bounded((Buffer)a[3],((Number)a[2]).longValue()));
        if(name.equals("glUniform2fv"))return call(name,a[0],floats(a[2],a.length>3?i(a[3]):((FloatBuffer)a[2]).position(),Math.multiplyExact(i(a[1]),2)));
        if(name.equals("glUniformMatrix4fv"))return call(name,a[0],a[2],floats(a[3],a.length>4?i(a[4]):((FloatBuffer)a[3]).position(),Math.multiplyExact(i(a[1]),16)));
        if(name.equals("glTexParameterfv"))return call(name,a[0],a[1],floats(a[2],a.length>3?i(a[3]):((FloatBuffer)a[2]).position(),4));
        if(name.equals("glBindFramebuffer")){
            return call(name,a[0],i(a[1])==0?context.getFramebuffer(false):a[1]);
        }
        if(name.equals("glReadBuffer")||name.equals("glDrawBuffer")){
            int buffer=i(a[0]);return call(name,buffer==1024||buffer==1026||buffer==1028||buffer==1029?36064:buffer);
        }
        if(name.equals("glTexImage2D")||name.equals("glTexSubImage2D")){
            Object[] normalized=a.clone();Object pointer=a[a.length-1];
            if(pointer==null)normalized[a.length-1]=0L;
            else if(pointer instanceof Number)normalized[a.length-1]=((Number)pointer).longValue();
            else if(pointer instanceof Buffer){
                Buffer direct=bounded((Buffer)pointer,-1);
                // The registered overload takes an address. Keep the direct
                // allocation rooted by its live monitor until JNI returns.
                synchronized(direct){normalized[a.length-1]=org.lwjgl.system.MemoryUtil.memAddress0(direct);return call(name,normalized);}
            }
            return call(name,normalized);
        }
        if(name.equals("glTexSubImage3D")){
            Object[] normalized=a.clone();normalized[a.length-1]=bounded((Buffer)a[a.length-1],-1);return call(name,normalized);
        }
        if(name.equals("glReadPixels")){
            if(!(a[6] instanceof ByteBuffer))throw unsupported(method,"only the renderer's byte readback is exposed");
            ByteBuffer original=(ByteBuffer)a[6];ByteBuffer buffer=(ByteBuffer)bounded(original,-1);Object[] normalized=a.clone();normalized[6]=buffer;call(name,normalized);
            if(!original.isDirect()){ByteBuffer dest=original.duplicate();dest.put(buffer.duplicate());}return null;
        }
        if(name.equals("glVertexAttribPointer")||name.equals("glVertexAttribIPointer")){
            if(!(a[a.length-1] instanceof Number))throw unsupported(method,"client-memory vertex arrays are not allowed");Object[] normalized=a.clone();normalized[a.length-1]=((Number)a[a.length-1]).longValue();return call(name,normalized);
        }
        Object result=call(name,a);
        if(result instanceof ByteBuffer)((ByteBuffer)result).order(ByteOrder.nativeOrder());
        return result;
    }
    private static Object call(String name,Object... arguments)throws Throwable{
        List<Method> methods=nativeMethods.get(name);
        if(methods!=null)for(Method method:methods){
            Class<?>[] types=method.getParameterTypes();if(types.length!=arguments.length)continue;
            boolean match=true;
            for(int x=0;x<types.length;x++)if(!accepts(types[x],arguments[x])){match=false;break;}
            if(match)try{return method.invoke(null,arguments);}catch(InvocationTargetException error){throw error.getCause();}
        }
        throw new UnsupportedOperationException("Unregistered GLES overload: "+name+Arrays.toString(Arrays.stream(arguments).map(x->x==null?null:x.getClass().getName()).toArray()));
    }
    private static boolean accepts(Class<?> expected,Object value){if(!expected.isPrimitive())return value==null||expected.isInstance(value);return expected==int.class?value instanceof Integer:expected==long.class?value instanceof Long:expected==float.class?value instanceof Float:expected==double.class?value instanceof Double:expected==boolean.class&&value instanceof Boolean;}
    private static UnsupportedOperationException unsupported(Method method,String reason){return new UnsupportedOperationException("117 HD GLES call "+method+": "+reason);}
    private static int i(Object n){return ((Number)n).intValue();}
    private static int getInt(Object a,int index){return a instanceof int[]?((int[])a)[index]:((IntBuffer)a).get(index);}
    private static void putInt(Object a,int index,int value){if(a instanceof int[])((int[])a)[index]=value;else ((IntBuffer)a).put(index,value);}
    private static void putFloat(Object a,int index,float value){if(a instanceof float[])((float[])a)[index]=value;else ((FloatBuffer)a).put(index,value);}
    private static void putByte(Object a,int index,byte value){if(a instanceof byte[])((byte[])a)[index]=value;else ((ByteBuffer)a).put(index,value);}
    private static float[] floats(Object source,int offset,int count){if(count<0)throw new IllegalArgumentException("uniform count");float[] out=new float[count];for(int x=0;x<count;x++)out[x]=source instanceof float[]?((float[])source)[offset+x]:((FloatBuffer)source).get(offset+x);return out;}
    private static Buffer bounded(Buffer data,long requestedBytes){
        if(data==null)throw new IllegalArgumentException("missing buffer");
        int bytesPerElement=data instanceof ByteBuffer?1:data instanceof ShortBuffer?2:data instanceof IntBuffer||data instanceof FloatBuffer?4:8;
        long available=(long)data.remaining()*bytesPerElement;long requested=requestedBytes<0?available:requestedBytes;
        if(requested<0||requested>available||requested%bytesPerElement!=0||requested>Integer.MAX_VALUE)throw new IllegalArgumentException("GL buffer size exceeds supplied data");
        int elements=(int)(requested/bytesPerElement);
        if(data instanceof ByteBuffer){ByteBuffer b=((ByteBuffer)data).duplicate();b.limit(b.position()+elements);b=b.slice().order(ByteOrder.nativeOrder());if(b.isDirect())return b;ByteBuffer d=ByteBuffer.allocateDirect((int)requested).order(ByteOrder.nativeOrder());d.put(b).flip();return d;}
        if(data instanceof IntBuffer){IntBuffer b=((IntBuffer)data).duplicate();b.limit(b.position()+elements);b=b.slice();if(b.isDirect())return b;IntBuffer d=ByteBuffer.allocateDirect((int)requested).order(ByteOrder.nativeOrder()).asIntBuffer();d.put(b).flip();return d;}
        if(data instanceof FloatBuffer){FloatBuffer b=((FloatBuffer)data).duplicate();b.limit(b.position()+elements);b=b.slice();if(b.isDirect())return b;FloatBuffer d=ByteBuffer.allocateDirect((int)requested).order(ByteOrder.nativeOrder()).asFloatBuffer();d.put(b).flip();return d;}
        throw new UnsupportedOperationException("Unaudited 117 GL buffer type: "+data.getClass());
    }
    private static String descriptor(Method method){StringBuilder b=new StringBuilder("(");for(Class<?> c:method.getParameterTypes())b.append(type(c));return b.append(')').append(type(method.getReturnType())).toString();}
    private static String type(Class<?> c){if(c.isArray())return c.getName().replace('.','/');if(!c.isPrimitive())return "L"+c.getName().replace('.','/')+";";if(c==void.class)return "V";if(c==boolean.class)return "Z";if(c==byte.class)return "B";if(c==short.class)return "S";if(c==char.class)return "C";if(c==int.class)return "I";if(c==long.class)return "J";if(c==float.class)return "F";if(c==double.class)return "D";throw new AssertionError(c);}
    private AndroidGl(){}
}

