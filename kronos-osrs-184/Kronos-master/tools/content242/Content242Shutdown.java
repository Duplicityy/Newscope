import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;

/** Graceful JVM shutdown hook entry, used only by the explicit deployment command. */
public final class Content242Shutdown {
    public static void agentmain(String role, Instrumentation instrumentation) {
        String expected="cache".equals(role)?"io.ruin.update.Server":"io.ruin.Server";
        if(!"cache".equals(role)&&!"game".equals(role))throw new IllegalArgumentException("Unknown server role");
        boolean found=false;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals(expected)){found=true;break;}
        if(!found)throw new IllegalStateException("Target does not contain the expected server class");
        new Thread(()->System.exit(0),"content242-graceful-shutdown").start();
    }
    public static void main(String[] args) throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}
    }
}
