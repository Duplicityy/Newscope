package net.runelite.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.UUID;

/** Protocol device identifier, scoped to this installation; never reads a hardware MAC. */
public final class DesktopIdentity {
    private static byte[] identifier;
    private DesktopIdentity() {}

    public static synchronized byte[] address() throws IOException {
        if (identifier == null) {
            Path directory = Paths.get(System.getProperty("user.home"), ".kronos");
            Files.createDirectories(directory);
            Path file = directory.resolve("windows-device-id");
            if (Files.exists(file)) {
                identifier = Files.readAllBytes(file);
                if (identifier.length != 6) {
                    identifier = null;
                    throw new IOException("Invalid saved desktop device identifier");
                }
            } else {
                byte[] generated = new byte[6];
                new SecureRandom().nextBytes(generated);
                generated[0] = (byte)((generated[0] & 0xfc) | 0x02);
                Files.write(file, generated, StandardOpenOption.CREATE_NEW);
                identifier = generated;
            }
        }
        return identifier.clone();
    }

    public static String uuid(String existing) throws IOException {
        if (existing != null && !existing.isEmpty()) return existing;
        return UUID.nameUUIDFromBytes(address()).toString();
    }
}