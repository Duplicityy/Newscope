import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
/** Stage-only bootstrap: typed probes must share the live Kilim classloader. */
public final class HollowDepthsProbeLoader {
    public static void agentmain(String arguments,Instrumentation instrumentation)throws Exception {
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||Integer.getInteger("kronos.port",0)!=14312
                ||!"127.0.0.1".equals(System.getProperty("kronos.bind")))throw new IllegalStateException("Isolated game required");
        String[] parts=arguments.split("\\|",3);if(parts.length!=3)throw new IllegalArgumentException("jar|class|stage required");
        Class<?> server=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("io.ruin.Server")&&type.getField("worker").get(null)!=null){server=type;break;}
        if(server==null)throw new IllegalStateException("Running Server class missing");
        ClassLoader loader=server.getClassLoader();
        Path stage=Paths.get(parts[2]).toRealPath();
        if(!Paths.get(System.getProperty("user.home")).toRealPath().getParent().equals(stage))throw new IllegalStateException("Stage home mismatch");
        String origin="Server protectionDomain="+server.getProtectionDomain()+"\nServer resource="+server.getResource("Server.class")+"\nServer loader resource="+loader.getResource("io/ruin/Server.class")+"\n";
        Files.write(stage.resolve("probe-class-origin.txt"),origin.getBytes("UTF-8"));
        Method define=ClassLoader.class.getDeclaredMethod("defineClass",String.class,byte[].class,int.class,int.class);define.setAccessible(true);
        try(JarFile jar=new JarFile(parts[0])){
            Enumeration<JarEntry> entries=jar.entries();
            while(entries.hasMoreElements()){
                JarEntry entry=entries.nextElement();
                if(!entry.getName().endsWith(".class")||entry.getName().startsWith("HollowDepthsProbeLoader"))continue;
                byte[] bytes;try(java.io.InputStream in=jar.getInputStream(entry);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                    byte[] block=new byte[8192];int count;while((count=in.read(block))!=-1)out.write(block,0,count);bytes=out.toByteArray();
                }
                define.invoke(loader,entry.getName().replace('/','.').replaceAll("\\.class$",""),bytes,0,bytes.length);
            }
        }
        final Method entry=Class.forName(parts[1],true,loader).getMethod("agentmain",String.class,Instrumentation.class);
        final Path status=stage.resolve("probe-status");Files.createDirectories(status);
        final String probe=parts[1].substring(parts[1].lastIndexOf('.')+1);
        Files.write(status.resolve(probe+".running"),"running\n".getBytes("UTF-8"));
        Thread worker=new Thread(()->{
            try{entry.invoke(null,parts[2],instrumentation);Files.write(status.resolve(probe+".done"),"PASS\n".getBytes("UTF-8"));}
            catch(Throwable failure){
                Throwable cause=failure instanceof InvocationTargetException?failure.getCause():failure;
                try{Files.write(status.resolve(probe+".error"),(cause.toString()+"\n").getBytes("UTF-8"));}catch(Exception writeFailure){writeFailure.printStackTrace();}
                cause.printStackTrace();
            }finally{try{Files.deleteIfExists(status.resolve(probe+".running"));}catch(Exception ignored){}}
        },"HollowDepthsProbe-"+probe);
        worker.setContextClassLoader(loader);worker.setDaemon(true);worker.start();
    }
    public static void main(String[] args)throws Exception{
        VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[1]+"|"+args[2]+"|"+args[3]);}finally{vm.detach();}
        Path status=Paths.get(args[3],"probe-status");String probe=args[2].substring(args[2].lastIndexOf('.')+1);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(40);
        while(System.nanoTime()<deadline){
            Path error=status.resolve(probe+".error"),done=status.resolve(probe+".done");
            if(Files.exists(error))throw new IllegalStateException(new String(Files.readAllBytes(error),"UTF-8"));
            if(Files.exists(done)){System.out.println("PASS "+probe);return;}
            Thread.sleep(200);
        }
        throw new IllegalStateException("Probe completion timed out; inspect stage/probe-status and game log");
    }
}
