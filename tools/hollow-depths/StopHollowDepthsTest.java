import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
/** Runs normal shutdown hooks, exclusively in the isolated Hollow Depths game JVM. */
public final class StopHollowDepthsTest {
    public static void agentmain(String stage,Instrumentation ignored) {
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||Integer.getInteger("kronos.port",0)!=14312
            ||!stage.equals(System.getProperty("kronos.hollowDepths.stage")))
            throw new IllegalStateException("Isolated Hollow Depths game identity required");
        new Thread(()->System.exit(0),"hollow-depths-test-graceful-stop").start();
    }
    public static void main(String[] args)throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1],args[2]); } finally { vm.detach(); }
    }
}
