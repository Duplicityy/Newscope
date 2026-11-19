package local.kronos.mobile;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The embedded JVM belongs to the process/session, never to one Activity instance. */
final class GameRuntime {
    private static volatile boolean preparing, engineIssued;
    private static volatile String preparation = "Ready", failure;
    private static boolean crashHandlerInstalled;
    private static boolean closing;
    static synchronized void installCrashHandler(Context context) {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (PrintWriter out = new PrintWriter(new File(app.getFilesDir(), "android-crash.log"))) { error.printStackTrace(out); }
            catch (IOException ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
    static synchronized void begin(Context context, String host) {
        if (closing || preparing || engineIssued) return;
        if(!OwnerEndpoint.HOST.equals(host)) {failure="This owner test app connects only to "+OwnerEndpoint.HOST;return;}
        final Context app = context.getApplicationContext();
        preparing = true; failure = null; preparation = "Checking server…";
        new Thread(() -> {
            try {
                for (int port : new int[]{OwnerEndpoint.CACHE_PORT, OwnerEndpoint.GAME_PORT}) {
                    try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), 5000); }
                }
                preparation = "Preparing game…";
                File root = app.getFilesDir();
                File tmp = new File(root, "tmp");
                if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("Cannot create temporary directory");
                String tag = assetText(app, "jvm/runtime.tag").trim();
                File saved = new File(root, "runtime.tag");
                String current = saved.isFile() ? new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8).trim() : "";
                if (!tag.equals(current)) {
                    copy(app, "jvm", new File(root, "jvm"));
                    Files.write(saved.toPath(), tag.getBytes(StandardCharsets.UTF_8));
                }
                engineIssued = true;
                MainActivity.launchRuntime(root.getAbsolutePath(), host);
            } catch (Throwable e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                try (PrintWriter out = new PrintWriter(new File(app.getFilesDir(), "android-crash.log"))) { e.printStackTrace(out); }
                catch (IOException ignored) {}
            } finally { preparing = false; }
        }, "kronos-start").start();
    }
    static boolean active() { return preparing || engineIssued; }
    static boolean preparing() { return preparing; }
    static String failure() { return failure; }
    static String status() { return engineIssued ? MainActivity.nativeStatus() : preparation; }
    static void activityDestroyed(Context context, boolean finishing, boolean changingConfiguration, long owner) {
        // A recreated Activity releases its UI only; an explicit finish ends the game.
        if (finishing && !changingConfiguration && BackgroundSessionPolicy.owns(owner)) close(context);
    }
    static synchronized void close(Context context) {
        if (closing) return;
        closing = true;
        try { GameSessionService.end(context.getApplicationContext()); }
        finally {
            // HotSpot and its game/network threads cannot be restarted in this process.
            // End only our own process so reopening always starts a fresh engine.
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
    private static String assetText(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            return out.toString("UTF-8");
        }
    }
    private static void copy(Context context, String path, File target) throws IOException {
        String[] children = context.getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create " + target);
            for (String child : children) copy(context, path + "/" + child, new File(target, child));
        } else {
            try (InputStream in = context.getAssets().open(path); OutputStream out = new FileOutputStream(target)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            }
        }
    }
    private GameRuntime() {}
}
