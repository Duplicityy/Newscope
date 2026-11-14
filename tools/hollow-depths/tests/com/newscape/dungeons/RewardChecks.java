package com.newscape.dungeons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** No testing framework and no reliance on the JVM -ea flag. */
public final class RewardChecks {
    private static int checks;
    private RewardChecks() {}

    public static void main(String[] arguments) throws Exception { run(); }

    public static void run() throws Exception {
        checks = 0;
        bundles();
        deterministicTables();
        durableTransactions();
        concurrentDuplicateCalls();
        corruptionAndIdentityGuards();
        System.out.println("RewardChecks: " + checks + " assertions passed");
    }

    private static void bundles() throws Exception {
        Map<String, Long> mutable = new HashMap<String, Long>();
        mutable.put("COIN_VOUCHER", 100L);
        Rewards.Bundle value = new Rewards.Bundle(mutable);
        mutable.put("COIN_VOUCHER", 999L);
        check(value.amount("COIN_VOUCHER") == 100, "bundle must defensively copy");
        expect(UnsupportedOperationException.class, () -> value.amounts.put("DUNGEON_SHARD", 1L));
        check(value.plus(Rewards.Bundle.of("COIN_VOUCHER", 25)).amount("COIN_VOUCHER") == 125, "bundle addition");
        check(value.scaled(1, 3).amount("COIN_VOUCHER") == 33, "scaling rounds down");
        check(value.scaled(0, 3).equals(Rewards.Bundle.empty()), "zero scaling removes zero entries");
        check(value.amount("MISSING_ASSET") == 0, "missing asset is zero");
        check(Rewards.Bundle.of("DUNGEON_SHARD", 0).amounts.isEmpty(), "zero entry canonicalization");
        expect(IllegalArgumentException.class, () -> Rewards.Bundle.of("../../COINS", 1));
        expect(IllegalArgumentException.class, () -> Rewards.Bundle.of("COIN_VOUCHER", -1));
        expect(IllegalArgumentException.class, () -> Rewards.Bundle.of("COIN_VOUCHER", Long.MAX_VALUE));
        expect(IllegalArgumentException.class, () -> value.scaled(1, 0));
        expect(IllegalArgumentException.class, () -> value.scaled(-1, 1));
        Rewards.Bundle maximum = Rewards.Bundle.of("COIN_VOUCHER", Rewards.MAX_ASSET_BALANCE);
        check(maximum.scaled(Integer.MAX_VALUE, Integer.MAX_VALUE).equals(maximum), "scaling avoids intermediate overflow");
        expect(ArithmeticException.class, () -> maximum.scaled(2, 1));
        expect(ArithmeticException.class, () -> maximum.plus(Rewards.Bundle.of("COIN_VOUCHER", 1)));
        Map<String, Long> tooMany = new HashMap<String, Long>();
        for (int i = 0; i <= Rewards.MAX_ASSETS; i++) tooMany.put("ASSET_" + i, 1L);
        expect(IllegalArgumentException.class, () -> new Rewards.Bundle(tooMany));
    }

    private static void deterministicTables() throws Exception {
        Rewards rewards = new Rewards();
        Rewards.Bundle first = rewards.roll(451223L, "room-4/chest-2", Rewards.Table.AGILITY, Rewards.Tier.VETERAN);
        rewards.roll(99L, "an-unrelated-source", Rewards.Table.BOSS, Rewards.Tier.NIGHTMARE);
        Rewards.Bundle repeated = rewards.roll(451223L, "room-4/chest-2", Rewards.Table.AGILITY, Rewards.Tier.VETERAN);
        check(first.equals(repeated), "reward streams independent of call order");
        check(first.equals(new Rewards().roll(451223L, "room-4/chest-2", Rewards.Table.AGILITY,
                Rewards.Tier.VETERAN)), "repeat across engine instances");
        Set<String> keys = new HashSet<String>();
        int entries = 0;
        for (Rewards.Table table : Rewards.Table.values()) {
            check(!rewards.tables().get(table).isEmpty(), "every table has entries");
            for (Rewards.LootEntry entry : rewards.tables().get(table)) {
                entries++;
                keys.add(entry.key);
                check(entry.weight > 0 && entry.minimum > 0 && entry.maximum >= entry.minimum, "valid weighted range");
            }
            for (Rewards.Tier tier : Rewards.Tier.values()) {
                Set<Rewards.Bundle> variety = new HashSet<Rewards.Bundle>();
                for (long seed = 0; seed < 256; seed++) {
                    Rewards.Bundle result = rewards.roll(seed, "chest-7", table, tier);
                    variety.add(result);
                    check(result.amount("DUNGEON_SHARD") > 0, "guaranteed progress currency");
                    check(result.amount("DUNGEON_SHARD") <= Rewards.MAX_SHARDS_PER_ROLL, "shard source cap");
                    check(result.amount("COIN_VOUCHER") <= Rewards.MAX_COIN_VOUCHERS_PER_ROLL, "coin source cap");
                    check(result.amounts.size() <= Rewards.MAX_ASSETS, "bounded asset width");
                }
                check(variety.size() > 128, "seeded table variety");
            }
        }
        check(entries >= 25 && keys.size() >= 25, "substantial varied content catalog");
        expect(UnsupportedOperationException.class, () -> rewards.tables().clear());
        expect(UnsupportedOperationException.class, () -> rewards.tables().get(Rewards.Table.BASIC).clear());
        expect(IllegalArgumentException.class, () -> rewards.roll(1, "", Rewards.Table.BASIC, Rewards.Tier.NOVICE));
    }

    private static void durableTransactions() throws Exception {
        Path directory = Files.createTempDirectory("newscape-reward-check-");
        try {
            Rewards.Bundle amount = Rewards.Bundle.of("DUNGEON_SHARD", 20)
                    .plus(Rewards.Bundle.of("COIN_VOUCHER", 2_000));
            try (FileRewardStore store = new FileRewardStore(directory)) {
                check(store.balance("player-1").equals(Rewards.Bundle.empty()), "new mailbox empty");
                check(store.depositOnce("player-1", "run-A:extraction", amount)
                        == Rewards.DepositResult.APPLIED, "first grant applied");
                check(store.depositOnce("player-1", "run-A:extraction", amount)
                        == Rewards.DepositResult.ALREADY_APPLIED, "duplicate grant ignored");
                check(store.balance("player-1").equals(amount), "duplicate has not increased balance");
                expect(IOException.class, () -> store.depositOnce("player-1", "run-A:extraction",
                        Rewards.Bundle.of("DUNGEON_SHARD", 1)));
                expect(IOException.class, () -> {
                    try (FileRewardStore ignored = new FileRewardStore(directory)) { /* must fail before entry */ }
                });
                check(store.balance("player-2").equals(Rewards.Bundle.empty()), "different account isolated");
                check(store.depositOnce("player-2", "run-A:extraction", amount)
                        == Rewards.DepositResult.APPLIED, "receipt scope is per account");
                store.depositOnce("near-cap", "first", Rewards.Bundle.of("DUNGEON_SHARD", Rewards.MAX_ASSET_BALANCE));
                expect(ArithmeticException.class, () -> store.depositOnce("near-cap", "would-overflow",
                        Rewards.Bundle.of("DUNGEON_SHARD", 1)));
                check(store.balance("near-cap").amount("DUNGEON_SHARD") == Rewards.MAX_ASSET_BALANCE,
                        "overflow cannot partially change balance");
                check(store.depositOnce("near-cap", "would-overflow", Rewards.Bundle.empty())
                        == Rewards.DepositResult.APPLIED, "failed grant did not leave a receipt");
            }
            FileRewardStore reopened = new FileRewardStore(directory);
            check(reopened.balance("player-1").equals(amount), "balance survives reopen");
            check(reopened.depositOnce("player-1", "run-A:extraction", amount)
                    == Rewards.DepositResult.ALREADY_APPLIED, "receipt survives reopen");
            reopened.depositOnce("player-1", "run-B:extraction", amount);
            check(reopened.balance("player-1").equals(amount.plus(amount)), "distinct extraction applies once");
            reopened.close();
            reopened.close();
            expect(IOException.class, () -> reopened.balance("player-1"));
        } finally { deleteTree(directory); }
    }

    private static void concurrentDuplicateCalls() throws Exception {
        Path directory = Files.createTempDirectory("newscape-reward-concurrency-");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try (FileRewardStore store = new FileRewardStore(directory)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Rewards.DepositResult>> futures = new ArrayList<Future<Rewards.DepositResult>>();
            Rewards.Bundle amount = Rewards.Bundle.of("DUNGEON_SHARD", 11);
            for (int i = 0; i < 64; i++) futures.add(executor.submit(() -> {
                start.await(); return store.depositOnce("race-player", "same-run", amount);
            }));
            start.countDown();
            int applied = 0;
            for (Future<Rewards.DepositResult> future : futures)
                if (future.get(15, TimeUnit.SECONDS) == Rewards.DepositResult.APPLIED) applied++;
            check(applied == 1, "concurrent duplicate callers produce one application");
            check(store.balance("race-player").equals(amount), "race balance consistent");
        } finally {
            executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS); deleteTree(directory);
        }
    }

    private static void corruptionAndIdentityGuards() throws Exception {
        Path directory = Files.createTempDirectory("newscape-reward-corruption-");
        try (FileRewardStore store = new FileRewardStore(directory)) {
            store.depositOnce("alice", "grant-1", Rewards.Bundle.of("COIN_VOUCHER", 123));
            Path file = mailbox(directory, "alice");
            byte[] original = Files.readAllBytes(file);
            byte[] damaged = original.clone(); damaged[damaged.length - 1] ^= 1;
            Files.write(file, damaged, StandardOpenOption.TRUNCATE_EXISTING);
            expect(IOException.class, () -> store.balance("alice"));
            expect(IOException.class, () -> store.depositOnce("alice", "grant-2", Rewards.Bundle.of("COIN_VOUCHER", 1)));
            check(Arrays.equals(Files.readAllBytes(file), damaged), "corruption is never silently overwritten/reset");
            // A syntactically checksummed but wrong schema must still fail closed.
            damaged = original.clone(); damaged[7] = 99; recomputeChecksum(damaged);
            Files.write(file, damaged, StandardOpenOption.TRUNCATE_EXISTING);
            expect(IOException.class, () -> store.balance("alice"));
            // Header is 8 bytes, account hash is 32 bytes, then a 4-byte asset count.
            damaged = original.clone(); damaged[40] = 0; damaged[41] = 0; damaged[42] = 0; damaged[43] = 65;
            recomputeChecksum(damaged); Files.write(file, damaged, StandardOpenOption.TRUNCATE_EXISTING);
            expect(IOException.class, () -> store.balance("alice"));
            Files.write(file, original, StandardOpenOption.TRUNCATE_EXISTING);
            check(store.balance("alice").amount("COIN_VOUCHER") == 123, "verified test backup restores balance");
            Files.copy(file, mailbox(directory, "bob"));
            expect(IOException.class, () -> store.balance("bob"));
            expect(IllegalArgumentException.class, () -> store.balance("\nmalformed"));
            expect(IllegalArgumentException.class, () -> store.depositOnce("alice", "trailing ", Rewards.Bundle.empty()));
            // IDs never enter a path: even path-looking IDs hash to this directory.
            store.depositOnce("../not-a-path", "safe-receipt", Rewards.Bundle.of("DUNGEON_SHARD", 1));
            check(Files.exists(mailbox(directory, "../not-a-path")), "account filenames are hashed");
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Path link = mailbox(directory, "symlink-account");
                Files.createSymbolicLink(link, file);
                expect(IOException.class, () -> store.balance("symlink-account"));
                check(Files.getPosixFilePermissions(file).equals(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "mailbox owner-only permissions");
            }
        } finally { deleteTree(directory); }
    }

    private static Path mailbox(Path directory, String account) {
        return directory.resolve(Rewards.hex(Rewards.digest(account.getBytes(StandardCharsets.UTF_8))) + ".mailbox");
    }
    private static void recomputeChecksum(byte[] bytes) {
        byte[] digest = Rewards.digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
    }
    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            Path[] sorted = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : sorted) Files.deleteIfExists(path);
        }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> expected, CheckedAction action) throws Exception {
        checks++;
        try { action.run(); }
        catch (Throwable actual) {
            if (expected.isInstance(actual)) return;
            throw new AssertionError("expected " + expected.getSimpleName() + ", got " + actual, actual);
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " was not thrown");
    }
    private interface CheckedAction { void run() throws Exception; }
}
