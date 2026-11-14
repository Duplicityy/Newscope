import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.*;
public final class AdventurerProbe {
 public static void agentmain(String command, Instrumentation inst) throws Exception {
  Class<?> server=null;
  for(Class<?> c:inst.getAllLoadedClasses())if(c.getName().equals("io.ruin.Server")){server=c;break;}
  if(server==null)throw new IllegalStateException("Server not found");
  if(!Boolean.getBoolean("kronos.adventurers.test"))throw new IllegalStateException("Test server only");
  if(command.equals("shutdown")){new Thread(()->System.exit(0),"staging-graceful-stop").start();return;}
    if(command.equals("state")) {
   final Class<?> world=Class.forName("io.ruin.model.World",false,server.getClassLoader());
   final Class<?> shops=Class.forName("io.ruin.model.shop.ShopManager",false,server.getClassLoader());
   Object worker1=server.getField("worker").get(null);
   ExecutorService ex=(ExecutorService)worker1.getClass().getMethod("getExecutor").invoke(worker1);
   ex.submit(()->{try {
    System.out.println("SHOPCOUNT " + ((java.util.Map<?,?>)shops.getMethod("getShops").invoke(null)).size());
    System.out.println("GENERALSHOP " + shops.getMethod("getByUUID",String.class).invoke(null,"e522b145-fbaa-4746-8d71-6ad9808d2338"));
    for(Object n:(Iterable<?>)world.getField("npcs").get(null)) if(((Integer)n.getClass().getMethod("getId").invoke(n))==2820) {
     Object def=n.getClass().getMethod("getDef").invoke(n);
     System.out.println("MERCHANT x="+n.getClass().getMethod("getAbsX").invoke(n)+" options="+java.util.Arrays.toString((Object[])def.getClass().getField("options").get(def))+" actions="+java.util.Arrays.toString((Object[])n.getClass().getField("actions").get(n)));
    }
    for(Object p:(Iterable<?>)world.getField("players").get(null)) {
    Class<?> c=p.getClass();Object shop=c.getField("viewingShop").get(p);
    System.out.println("PROBE "+c.getMethod("getName").invoke(p)+" shop="+(shop==null?"null":shop.getClass().getField("identifier").get(shop))+" event="+c.getMethod("hasActiveAction").invoke(p)+" lock="+c.getMethod("isLocked").invoke(p));
   }}catch(Exception e){throw new RuntimeException(e);}}).get();return;
  }
  String checkClass="AdventurerIntegrationChecks",argument=command;
  if(command.startsWith("safety:")){checkClass="SafetyIntegrationChecks";argument=command.substring(7);}
  else if(command.startsWith("supply:")){checkClass="ActivitySupplyIntegrationChecks";argument=command.substring(7);}
  else if(command.startsWith("fish:")){checkClass="FishingCookingIntegrationChecks";argument=command.substring(5);}
  else if(command.equals("terrain")){checkClass="TrainingSafetyChecks";}
  final String checkArgument=argument;
  final Class<?> checks=Class.forName("io.ruin.model.entity.player.autonomous."+checkClass,true,server.getClassLoader());
  Object worker=server.getField("worker").get(null);
  ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
  executor.submit(()->{try {if(command.equals("terrain"))checks.getMethod("run").invoke(null);else checks.getMethod("run",String.class).invoke(null,checkArgument);}catch(Exception e){throw new RuntimeException(e);}}).get(30,TimeUnit.SECONDS);
 }
 public static void main(String[] args)throws Exception {VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}}
}
