import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;

/** One-minute native update, normal logout/save, then the existing shutdown hook. */
public final class GracefulHollowRestart {
    public static void agentmain(String ignored, Instrumentation instrumentation) throws Exception {
        if (Boolean.getBoolean("kronos.hollowDepths.test") || Integer.getInteger("kronos.port",13302)!=13302)
            throw new IllegalStateException("Production13302 only");
        Class<?> live=null;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (!type.getName().equals("io.ruin.model.activities.hollowdepths.HollowDepths")) continue;
            Field thread=type.getDeclaredField("worldThread"); thread.setAccessible(true);
            if (thread.get(null)!=null) {
                Class<?> candidate=Class.forName("io.ruin.Server",false,type.getClassLoader());
                if(live!=null&&live!=candidate) throw new IllegalStateException("Ambiguous runtime");
                live=candidate;
            }
        }
        if(live==null) throw new IllegalStateException("Live dungeon runtime unavailable");
        final Class<?> world=Class.forName("io.ruin.model.World",false,live.getClassLoader());
        Object worker=live.getField("worker").get(null);
        ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
        boolean started=executor.submit(()->(Boolean)world.getMethod("update",int.class).invoke(null,1)).get();
        if(!started) throw new IllegalStateException("An update is already running; no second shutdown scheduled");
        new Thread(()->{
            try { Thread.sleep(65000); } catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
            System.exit(0);
        },"hitsplat-normal-restart").start();
    }
    public static void main(String[] args)throws Exception{
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(args[1]);}finally{vm.detach();}
        System.out.println("Native one-minute update started; normal logout/save precedes shutdown.");
    }
}
