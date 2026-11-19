import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Normal native saves/logout for one explicitly owner-authorized public raid release. */
public final class StopRaidReleaseGame {
    private static String hash(Path path)throws Exception{
        StringBuilder text=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))text.append(String.format("%02X",value&255));return text.toString();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> json(ClassLoader loader,Path path)throws Exception{
        Class<?> type=Class.forName("com.google.gson.Gson",true,loader);Object gson=type.newInstance();
        return (Map<String,Object>)type.getMethod("fromJson",String.class,Class.class).invoke(gson,new String(Files.readAllBytes(path),StandardCharsets.UTF_8),Map.class);
    }
    public static void agentmain(String request,Instrumentation instrumentation)throws Exception{
        String[] args=request.split("\\|",3);if(args.length!=3)throw new IllegalArgumentException("Expected PID/install/readinessSHA256");
        if(!java.lang.management.ManagementFactory.getRuntimeMXBean().getName().startsWith(args[0]+"@"))throw new IllegalStateException("Release game PID mismatch");
        final Path root=Paths.get(args[1]).toRealPath();
        if(!Boolean.getBoolean("kronos.public")||!"0.0.0.0".equals(System.getProperty("kronos.bind"))||!Paths.get(System.getProperty("user.dir")).toRealPath().equals(root.resolve("workdir").toRealPath())||!Paths.get(System.getProperty("newscape.raids.install","")).toRealPath().equals(root))throw new IllegalStateException("Exact owner-authorized public raid release only");
        Path readiness=root.resolve("PUBLIC-READINESS.json");if(!hash(readiness).equalsIgnoreCase(args[2]))throw new IllegalStateException("Public readiness identity changed");
        Class<?> found=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("io.ruin.model.World")&&type.getClassLoader()!=null&&type.getClassLoader().getClass().getName().equals("kilim.WeavingClassLoader")){if(found!=null)throw new IllegalStateException("Ambiguous native world");found=type;}
        if(found==null)throw new IllegalStateException("Woven world is not initialized");
        final Class<?> world=found;ClassLoader loader=world.getClassLoader();
        Map<String,Object> ready=json(loader,readiness),installed=json(loader,root.resolve("INSTALL-RECEIPT.json"));
        if(!"OWNER_AUTHORIZED_READY_FOR_PUBLIC".equals(ready.get("status"))||!"INSTALLED_PRIVATE_ONLY".equals(installed.get("status"))||!root.equals(Paths.get(String.valueOf(ready.get("installPath"))).toRealPath())||!Objects.equals(ready.get("packManifestSha256"),installed.get("packManifestSha256"))||!hash(root.resolve("RELEASE.json")).equalsIgnoreCase(String.valueOf(ready.get("installedReleaseSha256"))))throw new IllegalStateException("Completed owner-authorized release receipt required");
        Object worker=Class.forName("io.ruin.Server",false,loader).getField("worker").get(null);
        final ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
        executor.submit(()->{try{world.getField("updating").setBoolean(null,true);}catch(Exception failure){throw new RuntimeException(failure);}}).get(15,TimeUnit.SECONDS);
        new Thread(()->{
            try{
                for(int attempt=0;attempt<90;attempt++){
                    int count=executor.submit(()->{
                        ArrayList<Object> players=new ArrayList<>();Object registry=world.getField("players").get(null);for(Object player:(Iterable<?>)registry)players.add(player);
                        for(Object player:players){player.getClass().getField("logoutStage").setInt(player,-1);player.getClass().getMethod("forceLogout").invoke(player);player.getClass().getMethod("checkLogout").invoke(player);}
                        return ((Number)registry.getClass().getMethod("count").invoke(registry)).intValue();
                    }).get(20,TimeUnit.SECONDS);
                    if(count==0){executor.submit(()->{world.getMethod("removePlayers").invoke(null);return null;}).get(20,TimeUnit.SECONDS);Files.write(root.resolve("NORMAL-PUBLIC-STOP.txt"),java.time.Instant.now().toString().getBytes(StandardCharsets.UTF_8));System.exit(0);return;}
                    Thread.sleep(500);
                }
                throw new IllegalStateException("Native saves remain pending; no forced termination performed by this helper");
            }catch(Throwable failure){try{Files.write(root.resolve("PUBLIC-STOP-BLOCKED.txt"),failure.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}failure.printStackTrace();}
        },"owner-authorized-release-normal-save-stop").start();
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("Expected PID/agentJar/releaseRoot");Path root=Paths.get(args[2]).toRealPath();String identity=hash(root.resolve("PUBLIC-READINESS.json"));
        VirtualMachine vm=VirtualMachine.attach(args[0]);try{
            Properties target=vm.getSystemProperties();if(!"true".equals(target.getProperty("kronos.public"))||!root.equals(Paths.get(target.getProperty("newscape.raids.install","")).toRealPath()))throw new IllegalStateException("Target is not the authorized public install");
            vm.loadAgent(Paths.get(args[1]).toRealPath().toString(),args[0]+"|"+root+"|"+identity);
        }finally{vm.detach();}
    }
}
