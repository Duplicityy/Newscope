import com.sun.tools.attach.VirtualMachine;
import java.io.FileInputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Controlled disposable-character fixture; refuses every non-staging JVM. */
public final class HollowDepthsFixture {
    public static void agentmain(String request, Instrumentation instrumentation) throws Exception {
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||!Boolean.getBoolean("kronos.adventurers.test")
                ||Integer.getInteger("kronos.port",0)!=14312||!"127.0.0.1".equals(System.getProperty("kronos.bind")))
            throw new IllegalStateException("Isolated Hollow Depths game process required");
        Properties config=new Properties();
        try(FileInputStream input=new FileInputStream(request)){config.load(input);}
        Class<?> server=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("io.ruin.Server")){server=type;break;}
        if(server==null)throw new IllegalStateException("Server class missing");
        final ClassLoader loader=server.getClassLoader();
        Object worker=server.getField("worker").get(null);
        ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
        executor.submit(()->{try{apply(loader,config);}catch(Exception error){throw new RuntimeException(error);}}).get(30,TimeUnit.SECONDS);
    }
    private static void apply(ClassLoader loader,Properties config) throws Exception {
        String account=config.getProperty("account", "hdqa1"); if(!account.matches("hdqa[12]"))throw new IllegalArgumentException("Disposable account required"); Object player=null;
        Class<?> world=Class.forName("io.ruin.model.World",false,loader);
        for(Object candidate:(Iterable<?>)world.getField("players").get(null)){
            if(account.equalsIgnoreCase((String)candidate.getClass().getMethod("getName").invoke(candidate))){player=candidate;break;}
        }
        if(player==null)throw new IllegalStateException("Disposable test character is not online");
        Class<?> pc=player.getClass();
        pc.getMethod("closeDialogue").invoke(player);
        Object stats=pc.getMethod("getStats").invoke(player);
        int statCount=Class.forName("io.ruin.model.stat.StatType",false,loader).getEnumConstants().length;
        for(int i=0;i<statCount;i++){
            Object stat=stats.getClass().getMethod("get",int.class).invoke(stats,i);
            int level=(i==16)?Integer.parseInt(config.getProperty("agility", "1")):99; stat.getClass().getField("currentLevel").setInt(stat,level);
            stat.getClass().getField("fixedLevel").setInt(stat,level);
            stat.getClass().getField("experience").setDouble(stat,level==1?0d:13034431d);
            stat.getClass().getField("updated").setBoolean(stat,true);
        }
        Object inventory=pc.getMethod("getInventory").invoke(player);
        int[] added=ids(config.getProperty("items",""));
        if(added.length>0)inventory.getClass().getMethod("clear").invoke(inventory);
        for(int id:added)inventory.getClass().getMethod("add",int.class,int.class).invoke(inventory,id,1);
        Object equipment=pc.getMethod("getEquipment").invoke(player);
        Class<?> itemClass=Class.forName("io.ruin.model.item.Item",false,loader);
        for(int id:ids(config.getProperty("equip",""))){
            Object item=inventory.getClass().getMethod("findItem",int.class).invoke(inventory,id);
            if(item==null)throw new IllegalStateException("Fixture inventory is missing item "+id);
            equipment.getClass().getMethod("equip",itemClass).invoke(equipment,item);
            if(equipment.getClass().getMethod("findItem",int.class).invoke(equipment,id)==null)
                throw new IllegalStateException("Normal equipment rules rejected imported item "+id);
        }
        String location=config.getProperty("location","");
        if(!location.isEmpty()){
            int[] xyz=ids(location);if(xyz.length!=3||xyz[0]<0||xyz[1]<0||xyz[2]>3)throw new IllegalArgumentException("Invalid fixture destination");
            Object movement=pc.getMethod("getMovement").invoke(player);
            movement.getClass().getMethod("teleport",int.class,int.class,int.class).invoke(movement,xyz[0],xyz[1],xyz[2]);
        }
        System.out.println("HOLLOW DEPTHS FIXTURE OK items="+config.getProperty("items","")+" equip="+config.getProperty("equip","")+" location="+location);
    }
    private static int[] ids(String value){
        if(value.trim().isEmpty())return new int[0];
        String[] words=value.split(",");int[] result=new int[words.length];
        for(int i=0;i<words.length;i++)result[i]=Integer.parseInt(words[i].trim());
        return result;
    }
    public static void main(String[] args) throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}
    }
}
