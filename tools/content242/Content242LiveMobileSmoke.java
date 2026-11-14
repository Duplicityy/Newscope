import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;

/** External observation/login harness. No client class overrides or server calls. */
public final class Content242LiveMobileSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Boolean.getBoolean("kronos.liveSmoke")
                || !Boolean.getBoolean("kronos.desktopTest") || !Boolean.getBoolean("kronos.testInput")
                || !"127.0.0.1".equals(System.getProperty("kronos.host"))
                || Integer.getInteger("kronos.testGamePort", 13302) != 13302
                || Boolean.getBoolean("kronos.content242.test"))
            throw new IllegalStateException("Explicit production loopback smoke guard required");
        final File output = new File(args[0]);
        java.util.Timer watchdog = new java.util.Timer(true);
        watchdog.schedule(new java.util.TimerTask() {
            public void run() { System.err.println("LIVE-MOBILE-SMOKE TIMEOUT"); System.exit(2); }
        }, 180000);
        int exit = 1;
        try {
            KronosBoot.boot();
            Client client = RuneLite.getInjector().getInstance(Client.class);
            await(() -> "LOGIN_SCREEN".equals(KronosBoot.status()) && KronosBoot.frame() != null, 110000, "login screen");
            capture(new File(output, "login.png"));
            Method input = KronosBoot.class.getDeclaredMethod("inputTest", String.class);
            input.setAccessible(true);
            input.invoke(null, new File(output, "login.ingame.png").getAbsolutePath());
            AtomicInteger completeFrames = new AtomicInteger();
            RuneLite.getInjector().getInstance(DrawManager.class).registerEveryFrameListener(() -> {
                if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null
                        && client.isResized() && client.getBufferProvider() != null
                        && client.getBufferProvider().getWidth() == 894 && client.getBufferProvider().getHeight() == 503)
                    completeFrames.incrementAndGet();
            });
            KronosBoot.input(7, 894, 503, 0, "");
            await(() -> completeFrames.get() >= 25, 15000, "complete mobile frames at894x503");
            Thread.sleep(1200);
            int[] frame = KronosBoot.frame();
            if (frame == null || frame[0] != 894 || frame[1] != 503 || !client.isResized()
                    || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
                throw new IllegalStateException("Mobile frame/login state changed");
            capture(new File(output, "mobile-ingame.png"));
            String result = "{\"result\":\"PASS\",\"login\":true,\"width\":894,\"height\":503,"
                    + "\"resized\":true,\"completeLoggedInFrames\":" + completeFrames.get()
                    + ",\"serverFixtures\":false,\"stagedTaskHandler\":false,\"physicalAndroidDevice\":false}";
            Files.write(new File(output, "client-result.json").toPath(), result.getBytes(StandardCharsets.UTF_8));
            System.out.println("LIVE-MOBILE-SMOKE PASS: ordinary login; completed894x503 mobile frame; exiting this QA client");
            exit = 0;
        } catch (Throwable failure) {
            failure.printStackTrace();
        } finally {
            watchdog.cancel();
            System.exit(exit);
        }
    }
    private interface Condition { boolean ready(); }
    private static void await(Condition condition, long duration, String description) throws Exception {
        long until = System.currentTimeMillis() + duration;
        while (System.currentTimeMillis() < until) {
            if (condition.ready()) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for " + description);
    }
    private static void capture(File destination) throws Exception {
        int[] frame = KronosBoot.frame();
        if (frame == null || frame.length != 2 + frame[0] * frame[1]) throw new IllegalStateException("Incomplete frame");
        BufferedImage image = new BufferedImage(frame[0], frame[1], BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, frame[0], frame[1], frame, 2, frame[0]);
        ImageIO.write(image, "png", destination);
    }
}
