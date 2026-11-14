package com.newscape.dungeons;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single-world, process-exclusive durable mailbox. Blocking disk I/O: call on a
 * bounded persistence executor, then marshal completion back to the game thread.
 * A grant receipt and its balance changes share one checksummed atomic document.
 */
public final class FileRewardStore implements Rewards.Store, AutoCloseable {
    private static final int MAGIC = 0x4e535257; // NSRW
    private static final int SCHEMA = 1;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_RECEIPTS_PER_ACCOUNT = 100_000;

    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock writerLock;
    private final boolean directoryFsyncSupported;
    private boolean closed;

    public FileRewardStore(Path directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("directory is required");
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new IOException("mailbox directory must not be a symbolic link");
        Files.createDirectories(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("mailbox path is not a directory");
        this.directory = absolute.toRealPath();
        ownerOnly(this.directory, true);
        Path lockPath = this.directory.resolve(".writer.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("writer lock must not be a symbolic link");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            ownerOnly(lockPath, false);
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException conflict) {
                throw new IOException("another mailbox writer already owns this directory", conflict);
            }
            if (acquired == null) throw new IOException("another process already owns this mailbox directory");
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        lockChannel = channel; writerLock = acquired;
        boolean supported;
        try { forceDirectory(); supported = true; }
        catch (IOException | UnsupportedOperationException unsupported) { supported = false; }
        directoryFsyncSupported = supported;
    }

    /** False on some Windows/file providers: atomic updates remain required, power-loss durability is weaker. */
    public boolean supportsDirectoryFsync() { return directoryFsyncSupported; }

    @Override public synchronized Rewards.DepositResult depositOnce(String accountId,
            String transactionId, Rewards.Bundle rewards) throws IOException {
        ensureOpen();
        Rewards.validateIdentity(accountId, 256, "canonical account ID");
        Rewards.validateIdentity(transactionId, 512, "transaction ID");
        if (rewards == null) throw new IllegalArgumentException("rewards are required");
        String accountHash = hash(accountId);
        String receipt = hash(transactionId);
        String fingerprint = Rewards.hex(Rewards.digest(Rewards.canonical(rewards)));
        Account state = read(accountHash);
        String prior = state.receipts.get(receipt);
        if (prior != null) {
            if (!prior.equals(fingerprint))
                throw new IOException("transaction ID reused with different reward contents");
            return Rewards.DepositResult.ALREADY_APPLIED;
        }
        if (state.receipts.size() >= MAX_RECEIPTS_PER_ACCOUNT)
            throw new IOException("mailbox receipt limit reached; migrate store without dropping receipts");
        // Compute completely before mutating disk; overflow never partially applies.
        Rewards.Bundle next = state.balance.plus(rewards);
        Map<String, String> receipts = new TreeMap<String, String>(state.receipts);
        receipts.put(receipt, fingerprint);
        write(accountHash, new Account(next, receipts));
        return Rewards.DepositResult.APPLIED;
    }

    @Override public synchronized Rewards.Bundle balance(String accountId) throws IOException {
        ensureOpen(); Rewards.validateIdentity(accountId, 256, "canonical account ID");
        return read(hash(accountId)).balance;
    }

    private Account read(String accountHash) throws IOException {
        Path file = directory.resolve(accountHash + ".mailbox");
        if (Files.isSymbolicLink(file)) throw new IOException("mailbox file must not be a symbolic link");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return new Account(Rewards.Bundle.empty(), new TreeMap<String, String>());
        byte[] encoded;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = channel.size();
            if (length < 80 || length > MAX_FILE_BYTES) throw new IOException("mailbox size outside supported bounds");
            ByteBuffer buffer = ByteBuffer.allocate((int) length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new EOFException("mailbox truncated during read");
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1)
                throw new IOException("mailbox changed during read");
            encoded = buffer.array();
        }
        byte[] payload = Arrays.copyOf(encoded, encoded.length - CHECKSUM_BYTES);
        byte[] checksum = Arrays.copyOfRange(encoded, payload.length, encoded.length);
        if (!MessageDigest.isEqual(checksum, Rewards.digest(payload)))
            throw new IOException("mailbox checksum mismatch; recovery requires a verified backup");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC || in.readInt() != SCHEMA)
                throw new IOException("unsupported mailbox format or schema");
            byte[] identity = new byte[32]; in.readFully(identity);
            if (!Rewards.hex(identity).equals(accountHash)) throw new IOException("mailbox account identity mismatch");
            int assetCount = in.readInt();
            if (assetCount < 0 || assetCount > Rewards.MAX_ASSETS) throw new IOException("invalid asset count");
            Map<String, Long> amounts = new TreeMap<String, Long>();
            for (int i = 0; i < assetCount; i++) {
                int keyLength = in.readUnsignedByte();
                if (keyLength < 2 || keyLength > 48) throw new IOException("invalid asset key length");
                byte[] keyBytes = new byte[keyLength]; in.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.US_ASCII);
                Rewards.validateAssetKey(key);
                long amount = in.readLong();
                if (amount <= 0 || amount > Rewards.MAX_ASSET_BALANCE)
                    throw new IOException("invalid stored asset balance");
                if (amounts.put(key, amount) != null) throw new IOException("duplicate stored asset key");
            }
            int receiptCount = in.readInt();
            if (receiptCount < 0 || receiptCount > MAX_RECEIPTS_PER_ACCOUNT
                    || (long) receiptCount * 64L > in.available())
                throw new IOException("invalid receipt count");
            Map<String, String> receipts = new TreeMap<String, String>();
            for (int i = 0; i < receiptCount; i++) {
                byte[] transaction = new byte[32], reward = new byte[32];
                in.readFully(transaction); in.readFully(reward);
                if (receipts.put(Rewards.hex(transaction), Rewards.hex(reward)) != null)
                    throw new IOException("duplicate stored transaction receipt");
            }
            if (in.read() != -1) throw new IOException("unexpected trailing mailbox payload");
            return new Account(new Rewards.Bundle(amounts), receipts);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid mailbox data", invalid);
        }
    }

    private void write(String accountHash, Account state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(SCHEMA); out.write(Rewards.unhex(accountHash));
            out.writeInt(state.balance.amounts.size());
            for (Map.Entry<String, Long> asset : state.balance.amounts.entrySet()) {
                byte[] key = asset.getKey().getBytes(StandardCharsets.US_ASCII);
                out.writeByte(key.length); out.write(key); out.writeLong(asset.getValue());
            }
            out.writeInt(state.receipts.size());
            for (Map.Entry<String, String> receipt : state.receipts.entrySet()) {
                out.write(Rewards.unhex(receipt.getKey())); out.write(Rewards.unhex(receipt.getValue()));
            }
            out.flush();
        }
        byte[] payload = bytes.toByteArray();
        if ((long) payload.length + CHECKSUM_BYTES > MAX_FILE_BYTES) throw new IOException("mailbox file limit exceeded");
        Path temporary = Files.createTempFile(directory, ".mailbox-write-", ".tmp", privateFileAttributes());
        boolean moved = false;
        try {
            ownerOnly(temporary, false);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                writeFully(channel, ByteBuffer.wrap(payload));
                writeFully(channel, ByteBuffer.wrap(Rewards.digest(payload)));
                channel.force(true);
            }
            Path target = directory.resolve(accountHash + ".mailbox");
            if (Files.isSymbolicLink(target)) throw new IOException("mailbox target must not be a symbolic link");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("filesystem must support atomic replacement; no unsafe fallback is used", unsupported);
            }
            moved = true;
            if (directoryFsyncSupported) forceDirectory();
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private FileAttribute<?>[] privateFileAttributes() throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
        return new FileAttribute<?>[0];
    }
    private static void ownerOnly(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private static String hash(String text) { return Rewards.hex(Rewards.digest(text.getBytes(StandardCharsets.UTF_8))); }
    private void ensureOpen() throws IOException {
        if (closed || !writerLock.isValid()) throw new IOException("mailbox store is closed or writer lock is invalid");
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException error = null;
        try { writerLock.release(); } catch (IOException failure) { error = failure; }
        try { lockChannel.close(); } catch (IOException failure) {
            if (error == null) error = failure; else error.addSuppressed(failure);
        }
        if (error != null) throw error;
    }

    private static final class Account {
        final Rewards.Bundle balance;
        final Map<String, String> receipts;
        Account(Rewards.Bundle balance, Map<String, String> receipts) {
            this.balance = balance; this.receipts = receipts;
        }
    }
}
