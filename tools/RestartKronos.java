import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.ExecutorService;
/** Invoke the server's own update/logout path before its normal shutdown hook. */
public final class RestartKronos {
    public static void agentmain(String ignored,Instrumentation instrumentation) throws Exception {
        Class<?> server=null;
        for(Class<?> candidate:instrumentation.getAllLoadedClasses())if(candidate.getName().equals("io.ruin.Server")){server=candidate;break;}
        if(server==null)throw new IllegalStateException("Running server class not found");
        final Class<?> world=Class.forName("io.ruin.model.World",false,server.getClassLoader());
        Object worker=server.getField("worker").get(null);
        ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
        executor.submit(()->{try{world.getMethod("update",int.class).invoke(null,1);}catch(Exception e){throw new RuntimeException(e);}}).get();
        new Thread(()->{try{Thread.sleep(65000);}catch(InterruptedException e){Thread.currentThread().interrupt();}System.exit(0);},"kronos-controlled-restart").start();
    }
    public static void main(String[] args)throws Exception{
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(args[1]);}finally{vm.detach();}
        System.out.println("Server update countdown started; normal logout/save and shutdown follow.");
    }
}
