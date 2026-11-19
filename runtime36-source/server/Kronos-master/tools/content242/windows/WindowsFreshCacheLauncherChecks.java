import java.io.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real bounded filesystem fixtures; no production home, account or server access. */
public final class WindowsFreshCacheLauncherChecks {
    private static int checks;
    private static void check(boolean passed, String label) {
        checks++; if (!passed) throw new AssertionError(label);
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", value & 255));
        return out.toString();
    }
    private static Properties seed(Path bundle) throws Exception {
        Path cache = bundle.resolve("cache/Cache"); Files.createDirectories(cache);
        Properties p = new Properties(); p.setProperty("release", WindowsFreshCacheLauncher.RELEASE); p.setProperty("count", "3");
        String[] names = {"main_file_cache.dat2", "main_file_cache.idx255", "content242-format.properties"};
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = ("correct frozen file " + i).getBytes("UTF-8"); Files.write(cache.resolve(names[i]), bytes);
            p.setProperty("file." + i, names[i]); p.setProperty("sha256." + i, sha(bytes)); p.setProperty("bytes." + i, "" + bytes.length);
        }
        save(bundle, p); return p;
    }
    private static void save(Path bundle, Properties p) throws Exception {
        try (OutputStream out = Files.newOutputStream(bundle.resolve("cache/cache-manifest.properties"))) { p.store(out, "Fixture"); }
    }
    private static void refused(Path bundle, Path home, String label) throws Exception {
        try { WindowsFreshCacheLauncher.prepare(bundle, home, false, System.out); }
        catch (Exception expected) { check(true, label); return; }
        throw new AssertionError("Accepted " + label);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("newscape-launcher-fixtures-");
        Path bundle = root.resolve("bundle"), home = root.resolve("private-test-home");
        Properties p = seed(bundle);
        Files.createDirectories(home.resolve(".runelite"));
        byte[] settings = "real settings must survive".getBytes("UTF-8");
        Files.write(home.resolve(".runelite/settings.properties"), settings);
        Files.createDirectories(home.resolve(".kronos/oldschool/live"));
        Files.write(home.resolve(".kronos/oldschool/live/main_file_cache.dat2"), "stale".getBytes("UTF-8"));
        Path target = WindowsFreshCacheLauncher.prepare(bundle, home, false, System.out);
        check(target.equals(home.resolve("newscape-cache/" + WindowsFreshCacheLauncher.RELEASE)), "Dedicated new cache location");
        for (int i = 0; i < 3; i++) check(sha(Files.readAllBytes(target.resolve(p.getProperty("file." + i)))).equals(p.getProperty("sha256." + i)), "Installed payload " + i);
        check(Arrays.equals(settings, Files.readAllBytes(home.resolve(".runelite/settings.properties"))), "Settings retained");
        check(new String(Files.readAllBytes(home.resolve(".kronos/oldschool/live/main_file_cache.dat2")), "UTF-8").equals("stale"), "Legacy profile untouched");
        byte[] mutable = "normal runtime cache change".getBytes("UTF-8");
        Files.write(target.resolve("main_file_cache.dat2"), mutable);
        WindowsFreshCacheLauncher.prepare(bundle, home, false, System.out);
        check(Arrays.equals(mutable, Files.readAllBytes(target.resolve("main_file_cache.dat2"))), "Matching marker avoids reset each launch");
        WindowsFreshCacheLauncher.prepare(bundle, home, true, System.out);
        check(!Arrays.equals(mutable, Files.readAllBytes(target.resolve("main_file_cache.dat2"))), "Reset installs frozen payload");
        try (java.util.stream.Stream<Path> paths = Files.list(target.getParent())) { check(paths.anyMatch(path -> path.getFileName().toString().startsWith(WindowsFreshCacheLauncher.RELEASE + ".previous-")), "Previous cache archived"); }
        try (FileChannel channel = FileChannel.open(target.getParent().resolve(".install.lock"), StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            refused(bundle, home, "Concurrent installer");
        }
        p.setProperty("package-note", "new verified manifest identity"); save(bundle, p);
        WindowsFreshCacheLauncher.prepare(bundle, home, false, System.out);
        try (java.util.stream.Stream<Path> paths = Files.list(target.getParent())) { check(paths.filter(path -> path.getFileName().toString().startsWith(WindowsFreshCacheLauncher.RELEASE + ".previous-")).count() == 2, "Manifest change installs once and archives managed cache"); }
        Path unmarkedHome = root.resolve("unmarked-home");
        Path unmarked = unmarkedHome.resolve("newscape-cache/" + WindowsFreshCacheLauncher.RELEASE);
        Files.createDirectories(unmarked); Files.write(unmarked.resolve("untouchable-private-fixture"), new byte[]{1,2,3});
        WindowsFreshCacheLauncher.prepare(bundle, unmarkedHome, false, System.out);
        check(!Files.exists(unmarked.resolve("untouchable-private-fixture")), "Unmarked cache is replaced");
        try (java.util.stream.Stream<Path> paths = Files.list(unmarked.getParent())) { check(paths.anyMatch(path -> Files.isRegularFile(path.resolve("untouchable-private-fixture"))), "Unmarked content preserved in archive"); }
        byte[] before = Files.readAllBytes(target.resolve("main_file_cache.dat2"));
        byte[] corrupt = before.clone(); corrupt[0] ^= 1;
        Files.write(bundle.resolve("cache/Cache/main_file_cache.dat2"), corrupt);
        try { WindowsFreshCacheLauncher.prepare(bundle, home, true, System.out); throw new AssertionError("Wrong digest accepted"); }
        catch (IOException expected) { check(Arrays.equals(before, Files.readAllBytes(target.resolve("main_file_cache.dat2"))), "Same-length wrong digest cannot replace existing cache"); }
        Files.write(bundle.resolve("cache/Cache/main_file_cache.dat2"), "bad".getBytes("UTF-8"));
        try { WindowsFreshCacheLauncher.prepare(bundle, home, true, System.out); throw new AssertionError("Bad seed accepted"); }
        catch (IOException expected) { check(Arrays.equals(before, Files.readAllBytes(target.resolve("main_file_cache.dat2"))), "Bad seed cannot replace existing cache"); }
        Path badHome = root.resolve("bad-home");
        refused(bundle, badHome, "Corrupt payload");
        p = seed(bundle); p.setProperty("file.0", "../escape.dat"); save(bundle, p);
        refused(bundle, badHome, "Path traversal");
        p = seed(bundle); p.setProperty("file.0", p.getProperty("file.1")); save(bundle, p);
        refused(bundle, badHome, "Duplicate payload name");
        p = seed(bundle); p.setProperty("release", "../../outside"); save(bundle, p);
        refused(bundle, badHome, "Invalid release");
        p = seed(bundle); p.setProperty("sha256.0", "not-a-sha"); save(bundle, p);
        refused(bundle, badHome, "Invalid checksum");
        p = seed(bundle); p.setProperty("bytes.0", "-1"); save(bundle, p);
        refused(bundle, badHome, "Invalid length");
        p = seed(bundle); p.setProperty("count", "0"); save(bundle, p);
        refused(bundle, badHome, "Invalid count");
        check(Arrays.equals(settings, Files.readAllBytes(home.resolve(".runelite/settings.properties"))), "Settings unchanged after all failures");
        Path sessionHome = root.resolve("session-home");
        Path ready = root.resolve("session-ready"), release = root.resolve("session-release");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process holder = new ProcessBuilder(java, "-Xmx64m", "-cp", System.getProperty("java.class.path"),
                "WindowsFreshCacheLauncherChecks$LockHolder", sessionHome.toString(), ready.toString(), release.toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("session-child.log").toFile()).start();
        long until = System.currentTimeMillis() + 5000;
        while (!Files.exists(ready) && holder.isAlive() && System.currentTimeMillis() < until) Thread.sleep(25);
        check(Files.exists(ready), "Child holds profile lock after acquisition method returns");
        try { WindowsFreshCacheLauncher.acquireSessionLock(sessionHome); throw new AssertionError("Concurrent game session accepted"); }
        catch (IOException expected) { check(true, "Second JVM cannot reset active game cache"); }
        Files.write(release, new byte[]{1});
        check(holder.waitFor(5, TimeUnit.SECONDS) && holder.exitValue() == 0, "Child exits normally without explicit unlock");
        WindowsFreshCacheLauncher.acquireSessionLock(sessionHome);
        check(true, "Normal JVM exit releases profile lock");
        try { WindowsFreshCacheLauncher.acquireSessionLock(sessionHome); throw new AssertionError("Same-JVM duplicate lock accepted"); }
        catch (IOException expected) { check(true, "Strong session lock retained after method returns"); }
        System.out.println("WindowsFreshCacheLauncherChecks PASS " + checks + "; fixtures retained at " + root);
    }

    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            WindowsFreshCacheLauncher.acquireSessionLock(Paths.get(args[0]));
            Files.write(Paths.get(args[1]), new byte[]{1});
            long until = System.currentTimeMillis() + 10000;
            while (!Files.exists(Paths.get(args[2])) && System.currentTimeMillis() < until) Thread.sleep(25);
            if (!Files.exists(Paths.get(args[2]))) throw new IOException("Session fixture deadline expired");
        }
    }
}
