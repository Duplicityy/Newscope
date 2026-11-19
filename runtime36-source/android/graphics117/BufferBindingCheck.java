import java.lang.reflect.*;
import java.nio.*;
public class BufferBindingCheck {
 public static void main(String[] args)throws Exception {
  Class<?> gl=Class.forName("rs117.hd.android.AndroidGl");
  Method bounded=gl.getDeclaredMethod("bounded",Buffer.class,long.class);bounded.setAccessible(true);
  Method call=gl.getDeclaredMethod("call",String.class,Object[].class);call.setAccessible(true);
  int count=0;
  for(boolean direct:new boolean[]{false,true}) {
   ByteBuffer bytes=direct?ByteBuffer.allocateDirect(64):ByteBuffer.allocate(64);
   for(int i=0;i<64;i++)bytes.put(i,(byte)i);
   bytes.position(7);bytes.limit(43);
   for(int size:new int[]{0,1,17,36}) {
    ByteBuffer result=(ByteBuffer)bounded.invoke(null,bytes,(long)size);
    if(!result.isDirect()||result.remaining()!=size||bytes.position()!=7||bytes.limit()!=43)throw new AssertionError("bounds");
    for(int i=0;i<size;i++)if(result.get(i)!=(byte)(7+i))throw new AssertionError("contents");
    verify(call,"glBufferData",34962,result,35044);
    verify(call,"glBufferSubData",34962,12L,result);count+=2;
   }
   try{bounded.invoke(null,bytes,37L);throw new AssertionError("oversize accepted");}catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException))throw e;}
  }
  for(Buffer b:new Buffer[]{ByteBuffer.allocateDirect(16).asIntBuffer(),ByteBuffer.allocateDirect(16).asFloatBuffer()}) {
   verify(call,"glBufferData",34962,b,35044);verify(call,"glBufferSubData",34962,0L,b);count+=2;
  }
  verify(call,"glBufferData",34962,64L,35044);
  System.out.println("PASS "+(count+1)+" binding resolutions, heap/direct offsets, lengths and overflow rejection. JNI invocation reaches expected unlinked native boundary on host.");
 }
 static void verify(Method call,String name,Object... args)throws Exception {
  try {call.invoke(null,name,args);throw new AssertionError("unexpected native execution");}
  catch(InvocationTargetException e){if(!(e.getCause() instanceof UnsatisfiedLinkError))throw e;}
 }
}
