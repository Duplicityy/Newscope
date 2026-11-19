import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.DrawManager;
import net.runelite.rlawt.AWTContext;
import rs117.hd.HdPlugin;
import rs117.hd.android.AndroidGl;

/** Exercises real publication and skipped-render callbacks without a client or GL context. */
public final class FramePublicationCheck {
    private static void field(Class<?> type, Object instance, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
    public static void main(String[] args) throws Exception {
        field(AndroidGl.class, null, "owner", Thread.currentThread());
        field(AndroidGl.class, null, "context", new AWTContext(null));
        field(AndroidGl.class, null, "active", true);
        field(KronosBoot.class, null, "copiedSequence", 71L);
        ByteBuffer destination = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        int[] dimensions = new int[2];
        for (int frame = 1; frame <= 200; frame++) {
            int w = frame % 2 == 0 ? 4 : 2;
            int[] back = AndroidGl.gpuFrameBackBuffer(w, 2);
            for (int i = 0; i < back.length; i++) back[i] = (frame << 16) | i;
            AndroidGl.gpuPublish(w, 2);
            if (Owner117Frames.copyFrame(destination, dimensions, false) != w * 8) throw new AssertionError("size");
            if (!Owner117Frames.copied117Frame() || KronosBoot.copySequence()!=71L) throw new AssertionError("117 routing must bypass stale custom-HD sequence");
            if (dimensions[0] != w || dimensions[1] != 2) throw new AssertionError("dimensions");
            for (int i = 0; i < back.length; i++) if (destination.getInt(i * 4) != ((frame << 16) | i)) throw new AssertionError("torn frame");
            if (AndroidGl.copyFrame(destination, dimensions, false) != 0) throw new AssertionError("duplicate frame");
            if (AndroidGl.copyFrame(destination, dimensions, true) != w * 8) throw new AssertionError("refresh");
            if (AndroidGl.copyFrame(null, dimensions, true) != -w * 8) throw new AssertionError("resize request");
        }
        for(int frame=0;frame<100;frame++){
            AndroidGl.gpuDirectPublish(4,2);
            if(AndroidGl.latestFrame()!=null)throw new AssertionError("direct mode retained stale pixel arrays");
            int[] size=AndroidGl.dimensions();
            if(size[0]!=4 || size[1]!=2)throw new AssertionError("direct touch dimensions");
            if(!AndroidGl.frameStatus().contains("GPU display"))throw new AssertionError("direct mode status");
        }
        int[] fallback=AndroidGl.gpuFrameBackBuffer(4,2);
        java.util.Arrays.fill(fallback,123);
        AndroidGl.gpuPublish(4,2);
        if(Owner117Frames.copyFrame(destination,dimensions,true)!=32)throw new AssertionError("direct-to-buffered fallback");
        for(int i=0;i<8;i++)if(destination.getInt(i*4)!=123)throw new AssertionError("stale direct frame on fallback");
        field(AndroidGl.class, null, "active", false);
        if(!Owner117Frames.copied117Frame())throw new AssertionError("disable raced copied frame");
        if (AndroidGl.copyFrame(destination, dimensions, true) != Integer.MIN_VALUE) throw new AssertionError("fallback gate");

        if(Owner117Frames.copyFrame(destination,dimensions,true)!=Integer.MIN_VALUE || Owner117Frames.copied117Frame())throw new AssertionError("classic frame route");

        HdPlugin plugin = new HdPlugin();
        for (String name : new String[]{"vertexBuffer","uvBuffer","normalBuffer","modelBuffer","modelBufferSmall","modelBufferUnordered"}) {
            Field buffer = HdPlugin.class.getDeclaredField(name);
            buffer.setAccessible(true);
            java.lang.reflect.Constructor<?> constructor=buffer.getType().getDeclaredConstructor();constructor.setAccessible(true);buffer.set(plugin, constructor.newInstance());
        }
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, input) -> method.getName().equals("getGameState") ? GameState.LOGGED_IN : null);
        field(HdPlugin.class, plugin, "client", client);
        field(HdPlugin.class, plugin, "startUpCompleted", true);
        field(HdPlugin.class, plugin, "skipGpuFrame", true);
        DrawManager manager = new DrawManager();
        AtomicInteger frames = new AtomicInteger();
        manager.registerEveryFrameListener(frames::incrementAndGet);
        field(HdPlugin.class, plugin, "drawManager", manager);
        // GL and other services are deliberately absent. A busy frame must bypass them.
        for (int i = 0; i < 100; i++) plugin.draw();
        if (frames.get() != 100) throw new AssertionError("mobile frame callbacks stopped under backpressure");
        System.out.println("PASS 200 coherent publications/resizes and 100 busy frames retaining callbacks; no native/game initialization");
    }
}



