package io.ruin.utility;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** A complete, forced player snapshot replaces its predecessor in one filesystem operation. */
public final class AtomicPlayerSave {
    private AtomicPlayerSave() { }

    /** New saves are UTF-8; older Windows Java 8 saves used the platform's Cp1252 writer. */
    public static String decodeSnapshot(byte[] snapshot) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot)).toString();
        } catch (CharacterCodingException legacyBytes) {
            Charset legacy = Charset.defaultCharset();
            if (legacy.equals(StandardCharsets.UTF_8)) throw new IOException("Invalid UTF-8 player snapshot", legacyBytes);
            return legacy.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot)).toString();
        }
    }

    /** The caller must retain/lock its transaction state: the disk outcome could not be established. */
    public static final class UnknownOutcomeException extends IOException {
        public UnknownOutcomeException(String message, Throwable cause) { super(message, cause); }
    }

    public static void write(Path target, byte[] snapshot) throws IOException {
        write(target, snapshot, () -> { });
    }

    /** Package-private fault injection exercises the actual pre-replacement failure boundary. */
    static void write(Path target, byte[] snapshot, BeforeReplace beforeReplace) throws IOException {
        target = target.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || snapshot == null) throw new IOException("Invalid player snapshot destination");
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(target))
            throw new IOException("Player saves must not be symbolic links");
        byte[] predecessor = Files.exists(target) ? Files.readAllBytes(target) : null;
        Path temporary = Files.createTempFile(parent, ".player-save-", ".tmp");
        boolean replaced = false;
        try {
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(snapshot);
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            beforeReplace.run();
            replace(temporary, target, predecessor, snapshot);
            replaced = true;
            // Windows does not offer directory fsync through this provider. File forcing and
            // atomic replacement protect process crashes; power-loss metadata guarantees vary.
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException unsupported) {
                // Replacement has committed. Never turn this into a rollback/regrant opportunity.
            }
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanupFailure) {
                if (!replaced) temporary.toFile().deleteOnExit();
            }
        }
    }

    private static void replace(Path temporary, Path target, byte[] predecessor, byte[] snapshot) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Player saves require atomic replacement; no unsafe fallback", unsupported);
            } catch (IOException moveFailure) {
                final byte[] stored;
                try { stored = Files.exists(target) ? Files.readAllBytes(target) : null; }
                catch (IOException unreadable) {
                    moveFailure.addSuppressed(unreadable);
                    throw new UnknownOutcomeException("Cannot verify player snapshot replacement", moveFailure);
                }
                if (Arrays.equals(stored, snapshot)) return;
                if (!Arrays.equals(stored, predecessor))
                    throw new UnknownOutcomeException("Player snapshot changed unexpectedly during replacement", moveFailure);
                // Windows readers/virus scanners may briefly deny rename. Only retry after
                // verifying that the complete predecessor still exists; never truncate it.
                if (!(moveFailure instanceof AccessDeniedException) || attempt >= 7) throw moveFailure;
                try { Thread.sleep(5L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted before player snapshot replacement", interrupted);
                }
            }
        }
    }

    interface BeforeReplace { void run() throws IOException; }
}
