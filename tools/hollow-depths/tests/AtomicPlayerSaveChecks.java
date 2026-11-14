package io.ruin.utility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real filesystem checks; writes only a fresh temporary directory, never a player directory. */
public final class AtomicPlayerSaveChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("crash-before-replace")) {
            Path target = Paths.get(args[1]), ready = Paths.get(args[2]);
            AtomicPlayerSave.write(target, "new-transaction-with-receipt".getBytes(StandardCharsets.UTF_8), () -> {
                Files.write(ready, new byte[] {1});
                try { Thread.sleep(60000L); }
                catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            });
            throw new AssertionError("Child should have been terminated before replacement");
        }
        Path directory = Files.createTempDirectory("hollow-depths-atomic-check-").toAbsolutePath().normalize();
        Path target = directory.resolve("player.json");
        byte[] old = "old-inventory-and-receipt".getBytes(StandardCharsets.UTF_8);
        byte[] next = "new-inventory-and-receipt".getBytes(StandardCharsets.UTF_8);
        String unicode = "{\"title\":\"caf\u00e9\"}";
        require(AtomicPlayerSave.decodeSnapshot(unicode.getBytes(StandardCharsets.UTF_8)).equals(unicode), "UTF-8 player text changed");
        if (Charset.defaultCharset().newEncoder().canEncode(unicode))
            require(AtomicPlayerSave.decodeSnapshot(unicode.getBytes(Charset.defaultCharset())).equals(unicode), "Legacy platform player text changed");
        AtomicPlayerSave.write(target, old);
        require(Arrays.equals(Files.readAllBytes(target), old), "Initial snapshot mismatch");

        try {
            AtomicPlayerSave.write(target, next, () -> { throw new IOException("Injected pre-commit failure"); });
            throw new AssertionError("Injected failure did not escape");
        } catch (IOException expected) {
            require(!(expected instanceof AtomicPlayerSave.UnknownOutcomeException), "Pre-commit failure should be definite");
        }
        require(Arrays.equals(Files.readAllBytes(target), old), "Failure destroyed previous player snapshot");

        byte[] a = new byte[65536], b = new byte[65536];
        Arrays.fill(a, (byte) 'A'); Arrays.fill(b, (byte) 'B');
        AtomicPlayerSave.write(target, a);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                while (!stop.get()) {
                    byte[] found = Files.readAllBytes(target);
                    if (!Arrays.equals(found, a) && !Arrays.equals(found, b))
                        throw new AssertionError("Reader observed a truncated/mixed player snapshot");
                    // Windows readers deny replacement while their handle is open. Leave a
                    // scheduling gap so this also exercises successful concurrent renames.
                    Thread.sleep(2L);
                }
            } catch (Throwable failure) { readerFailure.set(failure); }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            for (int i = 0; i < 60; i++) AtomicPlayerSave.write(target, i % 2 == 0 ? b : a);
        } finally { stop.set(true); reader.join(5000L); }
        require(!reader.isAlive(), "Reader failed to stop");
        require(readerFailure.get() == null, "Concurrent reader failed: " + readerFailure.get());

        AtomicPlayerSave.write(target, old);
        Path ready = directory.resolve("child-ready");
        String executable = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Paths.get(executable))) executable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                AtomicPlayerSaveChecks.class.getName(), "crash-before-replace", target.toString(), ready.toString())
                .redirectErrorStream(true).start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(10L);
        require(Files.exists(ready), "Crash child did not reach forced-file boundary");
        child.destroyForcibly().waitFor();
        require(Arrays.equals(Files.readAllBytes(target), old), "Killed writer damaged committed predecessor");
        AtomicPlayerSave.write(target, next);
        require(Arrays.equals(Files.readAllBytes(target), next), "Recovery write did not commit");

        // The directory was freshly created above; only delete its direct test artifacts.
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                require(entry.toAbsolutePath().normalize().getParent().equals(directory), "Cleanup escaped fixture directory");
                Files.delete(entry);
            }
        }
        Files.delete(directory);
        System.out.println("PASS: UTF-8/legacy platform decoding, initial commit, definite failure rollback boundary, 60 concurrent-reader atomic replacements, process death before rename, recovery commit");
    }
}
