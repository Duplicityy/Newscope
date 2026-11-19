package net.runelite.standalone;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;

/** Checks the real client frame, managed seed and selected hosts without logging in. */
public final class StandaloneLoginCheck {
    public static void main(String[] args) {
        int exit = 1;
        try {
            Class.forName("WindowsFreshCacheLauncher").getMethod("main", String[].class)
                .invoke(null, (Object) new String[0]);
            net.runelite.api.Client client = RuneLite.getInjector().getInstance(net.runelite.api.Client.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (client.getGameState() != GameState.LOGIN_SCREEN && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            if (client.getGameState() != GameState.LOGIN_SCREEN) {
                throw new AssertionError("Login screen not reached: " + client.getGameState());
            }
            File expected = new File(System.getProperty("newscape.cache.dir")).getCanonicalFile();
            if (!FileSystem.FileSystem_cacheDir.getCanonicalFile().equals(expected)) {
                throw new AssertionError("Native client did not select the installed cache");
            }
            if (!args[0].equals(class158.worldHost)
                || !args[0].equals(CustomMain.worldType.getFileServerAddress())) {
                throw new AssertionError("Native client did not select the expected server");
            }
            CompletableFuture<java.awt.Image> frame = new CompletableFuture<>();
            RuneLite.getInjector().getInstance(DrawManager.class).requestNextFrameListener(frame::complete);
            if (frame.get(10, TimeUnit.SECONDS).getWidth(null) <= 0) {
                throw new AssertionError("The login frame did not render");
            }
            Files.write(Paths.get(args[1]), Collections.singletonList(
                "PASS: bundled JVM, managed cache, Ubuntu endpoint and rendered login frame"), StandardCharsets.UTF_8);
            exit = 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
        } finally {
            System.exit(exit);
        }
    }
}
