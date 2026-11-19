import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.swing.JOptionPane;

/** Installs a verified, isolated game cache; never migrates or deletes old profiles. */
public final class WindowsFreshCacheLauncher {
    public static final String RELEASE = "184728-fresh1";
    private static final String MARKER = ".newscape-installed.properties";
    // RuneLite.main returns while the game threads remain alive. Do not scope
    // these resources to main or prepare: the OS releases them at JVM exit.
    private static FileChannel sessionChannel;
    private static FileLock sessionLock;

    public static void main(String[] args) {
        PrintStream log = System.err;
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        try {
            Files.createDirectories(home);
            log = new PrintStream(Files.newOutputStream(home.resolve("newscape-launcher.log"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, "UTF-8");
            System.setOut(log); System.setErr(log);
            final PrintStream finalLog = log;
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> showError(finalLog, failure));
            log.println("\nNewscape fresh-cache launcher " + RELEASE + " " + java.time.Instant.now());
            boolean reset = false, installOnly = false;
            for (String arg : args) {
                if (arg.equals("--reset")) reset = true;
                else if (arg.equals("--install-only")) installOnly = true;
                else throw new IllegalArgumentException("Unknown launcher option: " + arg);
            }
            Path jar = Paths.get(WindowsFreshCacheLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path bundle = jar.getParent().getParent();
            acquireSessionLock(home);
            Path cache = prepare(bundle, home, reset, log);
            System.setProperty("newscape.cache.dir", cache.toString());
            System.setProperty("kronos.dungeoneering.enabled", "true");
            System.setProperty("kronos.athletics.client", "true");
            System.setProperty("kronos.host", "51.91.58.148");
            System.setProperty("sun.java2d.d3d", "false");
            log.println("Using verified game cache: " + cache);
            if (installOnly) return;
            try {
                Class.forName("net.runelite.client.RuneLite").getMethod("main", String[].class)
                        .invoke(null, (Object)new String[]{"--no-splash"});
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        } catch (Throwable failure) {
            showError(log, failure);
            System.exit(1);
        }
    }

    private static void showError(PrintStream log, Throwable failure) {
        failure.printStackTrace(log); log.flush();
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                JOptionPane.showMessageDialog(null,
                        "Newscape could not start. Close any other Newscape window and try again.\n"
                        + "Details: " + System.getProperty("user.home") + "\\newscape-launcher.log\n\n"
                        + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()),
                        "Newscape startup", JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) { }
        }
    }

    /** Public for bounded fixture checks; callers must supply a private test home. */
    public static Path prepare(Path bundleInput, Path homeInput, boolean reset, PrintStream log) throws Exception {
        Path bundle = bundleInput.toAbsolutePath().normalize();
        Path home = homeInput.toAbsolutePath().normalize();
        Files.createDirectories(home);
        Path base = cacheBase(home);
        Path manifestPath = bundle.resolve("cache/cache-manifest.properties");
        requirePlainFile(manifestPath, bundle.resolve("cache"));
        if (Files.size(manifestPath) > 1024 * 1024) throw new IOException("Cache manifest is too large");
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        String manifestSha = hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes));
        Properties manifest = new Properties();
        try (InputStream in = new ByteArrayInputStream(manifestBytes)) { manifest.load(in); }
        if (!RELEASE.equals(manifest.getProperty("release"))) throw new IOException("Unexpected cache release");
        int count = Integer.parseInt(required(manifest, "count"));
        if (count < 1 || count > 300) throw new IOException("Invalid cache file count");
        Set<String> names = new HashSet<>();
        List<CacheFile> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = required(manifest, "file." + i);
            if (!name.matches("(?:main_file_cache\\.dat2|main_file_cache\\.idx(?:[0-9]{1,3})|content242-format\\.properties)"))
                throw new IOException("Invalid cache filename: " + name);
            if (!names.add(name)) throw new IOException("Duplicate cache file: " + name);
            String sha = required(manifest, "sha256." + i).toLowerCase(Locale.ROOT);
            if (!sha.matches("[a-f0-9]{64}")) throw new IOException("Invalid cache SHA-256");
            long bytes = Long.parseLong(required(manifest, "bytes." + i));
            if (bytes < 0 || bytes > 2147483648L) throw new IOException("Invalid cache length");
            files.add(new CacheFile(name, sha, bytes));
        }
        Path target = base.resolve(RELEASE);
        requireManagedPath(base, target);
        Path lockPath = base.resolve(".install.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Cache lock is a link");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another Newscape cache installation is running");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireManagedPath(base, target);
                if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Managed cache is not a directory");
                Path markerPath = target.resolve(MARKER);
                if (!reset && Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
                    requirePlainFile(markerPath, target);
                    Properties marker = new Properties();
                    try (InputStream in = Files.newInputStream(markerPath)) { marker.load(in); }
                    if (RELEASE.equals(marker.getProperty("release")) && manifestSha.equals(marker.getProperty("manifestSha256"))) {
                        log.println("Existing installed cache marker matches this package; retaining current cache.");
                        return target;
                    }
                }
            }
            // Verify the complete payload before archiving an existing managed cache.
            Path seed = bundle.resolve("cache/Cache");
            for (CacheFile file : files) verify(seed.resolve(file.name), seed, file);
            Path staged = Files.createTempDirectory(base, ".install-" + RELEASE + "-");
            requireManagedPath(base, staged);
            for (CacheFile file : files) {
                Path source = seed.resolve(file.name);
                requirePlainFile(source, seed);
                Path destination = staged.resolve(file.name);
                try (InputStream in = Files.newInputStream(source);
                     OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[131072]; int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                verify(destination, staged, file);
            }
            Properties marker = new Properties();
            marker.setProperty("release", RELEASE); marker.setProperty("manifestSha256", manifestSha);
            try (OutputStream out = Files.newOutputStream(staged.resolve(MARKER), StandardOpenOption.CREATE_NEW)) {
                marker.store(out, "Verified Newscape cache seed; normal game cache writes are allowed");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireManagedPath(base, target);
                Path archive = base.resolve(RELEASE + ".previous-" + UUID.randomUUID().toString());
                requireManagedPath(base, archive);
                moveDirectory(target, archive);
                log.println("Previous managed game cache archived: " + archive.getFileName());
            }
            moveDirectory(staged, target);
            log.println("Installed " + files.size() + " verified cache files from this package.");
            return target;
        } catch (OverlappingFileLockException busy) {
            throw new IOException("Another Newscape cache installation is running", busy);
        }
    }

    static synchronized void acquireSessionLock(Path home) throws IOException {
        if (sessionLock != null && sessionLock.isValid())
            throw new IOException("Newscape is already running for this profile");
        Path base = cacheBase(home.toAbsolutePath().normalize());
        Path path = base.resolve(".session.lock");
        requireManagedPath(base, path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) requirePlainFile(path, base);
        FileChannel candidate = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            requirePlainFile(path, base);
            FileLock locked;
            try { locked = candidate.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Newscape is already running for this profile", busy); }
            if (locked == null) throw new IOException("Newscape is already running for this profile. Close it before starting or resetting the cache.");
            sessionChannel = candidate; sessionLock = locked;
        } catch (IOException | RuntimeException failed) {
            candidate.close(); throw failed;
        }
    }

    private static Path cacheBase(Path home) throws IOException {
        Files.createDirectories(home);
        Path realHome = home.toRealPath();
        Path base = home.resolve("newscape-cache");
        Files.createDirectories(base);
        if (Files.isSymbolicLink(base) || !base.toRealPath().startsWith(realHome))
            throw new IOException("Cache base redirects outside the client profile");
        return base;
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, destination); }
    }

    private static void requireManagedPath(Path base, Path path) throws IOException {
        Path normalBase = base.toAbsolutePath().normalize(), normal = path.toAbsolutePath().normalize();
        if (!normal.getParent().equals(normalBase) || normal.equals(normalBase) || Files.isSymbolicLink(normal))
            throw new IOException("Cache path is outside the managed directory");
        if (Files.exists(normal, LinkOption.NOFOLLOW_LINKS) && !normal.toRealPath().startsWith(normalBase.toRealPath()))
            throw new IOException("Cache path redirects outside the managed directory");
    }

    private static void requirePlainFile(Path file, Path root) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || !file.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("Missing or redirected cache payload: " + file.getFileName());
    }

    private static void verify(Path path, Path root, CacheFile expected) throws Exception {
        requirePlainFile(path, root);
        if (Files.size(path) != expected.bytes) throw new IOException("Wrong cache file length: " + expected.name);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[131072]; int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        if (!hex(digest.digest()).equals(expected.sha)) throw new IOException("Cache checksum failed: " + expected.name);
    }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key); if (value == null || value.trim().isEmpty()) throw new IOException("Missing " + key);
        return value.trim();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    private static final class CacheFile {
        final String name, sha; final long bytes;
        CacheFile(String name, String sha, long bytes) { this.name = name; this.sha = sha; this.bytes = bytes; }
    }
}
