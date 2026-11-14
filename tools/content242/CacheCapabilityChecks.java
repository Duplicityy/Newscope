package io.ruin.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CacheCapabilityChecks {
    public static void main(String[] args) throws Exception {
        Path cache = Files.createTempDirectory("k242-capability-check-");
        Path marker = cache.resolve(CacheCapability.MARKER);
        try {
            check(CacheCapability.revision(cache.toFile()) == 184, "Unmarked legacy cache");
            Files.write(marker, "format=k242-skeletal-v1\njs5_revision=184242\n".getBytes(StandardCharsets.US_ASCII));
            check(CacheCapability.revision(cache.toFile()) == 184242, "Extended cache");
            Files.write(marker, "format=k242-skeletal-v1\njs5_revision=184\n".getBytes(StandardCharsets.US_ASCII));
            reject(cache, "Mixed legacy handshake");
            Files.write(marker, "format=future-format\njs5_revision=184242\n".getBytes(StandardCharsets.US_ASCII));
            reject(cache, "Unknown format");
            Files.write(marker, new byte[0]);
            reject(cache, "Empty marker");
            Files.write(marker, new byte[4097]);
            reject(cache, "Oversized marker");
            System.out.println("Cache capability: 6 checks passed");
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(cache);
        }
    }
    private static void reject(Path cache, String reason) throws Exception {
        try { CacheCapability.revision(cache.toFile()); }
        catch (IOException expected) { return; }
        throw new AssertionError(reason);
    }
    private static void check(boolean passed, String reason) {
        if (!passed) throw new AssertionError(reason);
    }
}
