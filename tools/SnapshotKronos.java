import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
/** Read a detached player snapshot on the existing world executor; write only to backup. */
public final class SnapshotKronos {
 public static void agentmain(String directory,Instrumentation instrumentation)throws Exception{
  Class<?> server=null;
  for(Class<?> candidate:instrumentation.getAllLoadedClasses())if(candidate.getName().equals("io.ruin.Server")){server=candidate;break;}
  if(server==null)throw new IllegalStateException("Running server class not found");
  ClassLoader loader=server.getClassLoader();
  Class<?> world=Class.forName("io.ruin.model.World",false,loader),config=Class.forName("io.ruin.model.inter.utils.Config",false,loader),playerClass=Class.forName("io.ruin.model.entity.player.Player",false,loader);
  Class<?> actorClass=null;
  try {actorClass=Class.forName("io.ruin.model.entity.player.autonomous.Adventurer",false,loader);} catch(ClassNotFoundException ignored) {}
  final Class<?> autonomousClass=actorClass;
  Object worker=server.getField("worker").get(null);
  ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
  Object gson=Class.forName("io.ruin.api.utils.JsonUtils",false,loader).getField("GSON_EXPOSE").get(null);
  Method toJson=gson.getClass().getMethod("toJson",Object.class),saveConfig=config.getMethod("save",playerClass);
  Map<String,String> detached=executor.submit(()->{
   Map<String,String> snapshots=new LinkedHashMap<>();
   for(Object player:(Iterable<?>)world.getField("players").get(null)){
    String name=(String)playerClass.getMethod("getName").invoke(player);
    if(name==null||!name.matches("[A-Za-z0-9_ ]{1,12}"))throw new IllegalStateException("Invalid snapshot name");
    saveConfig.invoke(null,player);
    if(autonomousClass!=null && autonomousClass.isInstance(player)) {
     String actorId=(String)autonomousClass.getField("actorId").get(player);
     if(actorId==null || !actorId.matches("(?:[1-9]|1[0-9]|2[0-5])"))throw new IllegalStateException("Invalid autonomous snapshot identity");
     Field brainField=autonomousClass.getDeclaredField("brain");brainField.setAccessible(true);
     Object brain=brainField.get(player);
     if(brain==null)throw new IllegalStateException("Autonomous checkpoint unavailable");
     long tick=((Number)serverTick(loader)).longValue();
     Map<String,Object> checkpoint=new LinkedHashMap<>();
     checkpoint.put("version",1);checkpoint.put("capturedTick",tick);checkpoint.put("player",player);
     checkpoint.put("brain",brain.getClass().getMethod("checkpoint",long.class).invoke(brain,tick));
     snapshots.put("autonomous-adventurers/"+actorId+".json",(String)toJson.invoke(gson,checkpoint));
    } else snapshots.put("player-data/players/"+name.toLowerCase(Locale.ROOT)+".json",(String)toJson.invoke(gson,player));
   }return snapshots;
  }).get(30,TimeUnit.SECONDS);
  Path root=Paths.get(directory).toAbsolutePath().normalize(),runtime=root.resolve("runtime");Files.createDirectories(runtime);
  int humans=0,actors=0;
  for(Map.Entry<String,String> entry:detached.entrySet()) {
   Path target=runtime.resolve(entry.getKey()).normalize();
   if(!target.startsWith(runtime))throw new IllegalStateException("Snapshot path outside backup");
   Files.createDirectories(target.getParent());Files.write(target,entry.getValue().getBytes(StandardCharsets.UTF_8));
   if(entry.getKey().startsWith("autonomous-adventurers/"))actors++;else humans++;
  }
  Properties report=new Properties();report.setProperty("capturedUtc",java.time.Instant.now().toString());report.setProperty("onlinePlayers",Integer.toString(humans));report.setProperty("autonomousPlayers",Integer.toString(actors));report.setProperty("worldTick",worker.getClass().getMethod("getExecutions").invoke(worker).toString());
  try(java.io.OutputStream out=Files.newOutputStream(root.resolve("live-snapshot.properties"))){report.store(out,"Detached world-thread player snapshot. Source server was not stopped.");}
 }
 private static Object serverTick(ClassLoader loader)throws Exception{return Class.forName("io.ruin.Server",false,loader).getMethod("currentTick").invoke(null);}
 public static void main(String[] args)throws Exception{VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}System.out.println("Live player snapshot captured.");}
}
