import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Isolated242 bootstrap. Probe classes share the actual running Kilim classloader. */
public final class Content242ProbeLoader {
    public static void agentmain(String arguments,Instrumentation instrumentation)throws Exception {
        String[] parts=arguments.split("\\|",4);if(parts.length!=4)throw new IllegalArgumentException("jar|class|stage|mode required");
        Path stage=Paths.get(parts[2]).toRealPath();String name=stage.getFileName().toString();
        if(!name.matches("content242-expanded-test-[a-z0-9-]+")||!name.equals(System.getProperty("kronos.content242.stage"))
            ||Integer.getInteger("kronos.port",0)!=14302||!"127.0.0.1".equals(System.getProperty("kronos.bind"))
            ||!Paths.get(System.getProperty("user.dir")).toRealPath().equals(stage))throw new IllegalStateException("Exact isolated242 game required");
        Class<?> server=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("io.ruin.Server")&&type.getField("worker").get(null)!=null){server=type;break;}
        if(server==null)throw new IllegalStateException("Running Server class missing");
        Path data=((java.io.File)server.getField("dataFolder").get(null)).toPath().toRealPath();
        if(!data.getFileName().toString().equals("data")||!data.getParent().getFileName().toString().matches("content242-playable-stage-[a-z0-9-]+")
            ||!data.getParent().getParent().equals(stage.getParent()))throw new IllegalStateException("Isolated playable data required");
        Path probeJar=Paths.get(parts[0]).toRealPath();if(!probeJar.startsWith(stage))throw new IllegalStateException("Probe must be built inside its stage");
        ClassLoader loader=server.getClassLoader();
        Method define=ClassLoader.class.getDeclaredMethod("defineClass",String.class,byte[].class,int.class,int.class);define.setAccessible(true);
        try(JarFile jar=new JarFile(probeJar.toFile())){
            Enumeration<JarEntry> entries=jar.entries();
            while(entries.hasMoreElements()){
                JarEntry entry=entries.nextElement();if(!entry.getName().endsWith(".class")||entry.getName().startsWith("Content242ProbeLoader"))continue;
                if(!entry.getName().startsWith("io/ruin/content/content242/bosses/Content242NativeProbe_"))throw new IllegalStateException("Unexpected probe class");
                byte[] bytes;try(java.io.InputStream in=jar.getInputStream(entry);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);bytes=out.toByteArray();}
                define.invoke(loader,entry.getName().replace('/','.').replaceAll("\\.class$",""),bytes,0,bytes.length);
            }
        }
        Method entry=Class.forName(parts[1],true,loader).getMethod("agentmain",String.class,Instrumentation.class);
        Path status=stage.resolve("probe-status");Files.createDirectories(status);String probe=parts[1].substring(parts[1].lastIndexOf('.')+1);
        Files.write(status.resolve(probe+".running"),"running\n".getBytes("UTF-8"));
        Thread thread=new Thread(()->{
            try{entry.invoke(null,parts[2]+"|"+parts[3],instrumentation);Files.write(status.resolve(probe+".done"),"PASS\n".getBytes("UTF-8"));}
            catch(Throwable failure){Throwable cause=failure instanceof InvocationTargetException?failure.getCause():failure;
                try{Files.write(status.resolve(probe+".error"),(cause.toString()+"\n").getBytes("UTF-8"));}catch(Exception ignored){}cause.printStackTrace();}
            finally{try{Files.deleteIfExists(status.resolve(probe+".running"));}catch(Exception ignored){}}
        },"Content242Probe-"+probe);thread.setContextClassLoader(loader);thread.setDaemon(true);thread.start();
    }
    public static void main(String[] args)throws Exception{
        VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[1]+"|"+args[2]+"|"+args[3]+"|"+args[4]);}finally{vm.detach();}
        Path status=Paths.get(args[3],"probe-status");String name=args[2].substring(args[2].lastIndexOf('.')+1);
        long deadline=System.currentTimeMillis()+10*60*1000;
        while(System.currentTimeMillis()<deadline){
            if(Files.exists(status.resolve(name+".error")))throw new IllegalStateException(new String(Files.readAllBytes(status.resolve(name+".error")),"UTF-8"));
            if(Files.exists(status.resolve(name+".done"))){System.out.println("PASS "+args[4]);return;}Thread.sleep(250);
        }
        throw new IllegalStateException("Probe completion timed out; inspect stage proof and game log");
    }
}
