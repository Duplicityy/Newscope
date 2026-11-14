package com.newscape.dungeons;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Revision-independent dungeon rewards. Values are account-bound mailbox credits,
 * not live item IDs. Keep redemption in the same transaction as the player's save.
 */
public final class Rewards {
    public enum Tier { NOVICE, VETERAN, NIGHTMARE }
    public enum Table { BASIC, GUARDED, AGILITY, ELITE, BOSS }
    public enum DepositResult { APPLIED, ALREADY_APPLIED }

    /** The receipt and every balance change must commit together. */
    public interface Store {
        DepositResult depositOnce(String accountId, String transactionId, Bundle rewards)
                throws IOException;
        Bundle balance(String accountId) throws IOException;
    }

    public static final int MAX_ASSETS = 64;
    public static final long MAX_ASSET_BALANCE = 1_000_000_000_000L;
    public static final long MAX_COIN_VOUCHERS_PER_ROLL = 18_000L;
    public static final long MAX_SHARDS_PER_ROLL = 120L;

    /** Immutable, canonical, bounded reward vector. Zero entries are omitted. */
    public static final class Bundle {
        public final Map<String, Long> amounts;

        public Bundle(Map<String, Long> amounts) {
            Objects.requireNonNull(amounts, "amounts");
            if (amounts.size() > MAX_ASSETS) throw new IllegalArgumentException("too many assets");
            TreeMap<String, Long> copy = new TreeMap<String, Long>();
            for (Map.Entry<String, Long> entry : amounts.entrySet()) {
                validateAssetKey(entry.getKey());
                Long value = Objects.requireNonNull(entry.getValue(), "asset amount");
                if (value < 0 || value > MAX_ASSET_BALANCE)
                    throw new IllegalArgumentException("asset amount outside supported bounds");
                if (value != 0) copy.put(entry.getKey(), value);
            }
            this.amounts = Collections.unmodifiableMap(copy);
        }

        public static Bundle empty() { return new Bundle(Collections.<String, Long>emptyMap()); }

        public static Bundle of(String key, long amount) {
            Map<String, Long> values = new TreeMap<String, Long>();
            values.put(key, amount);
            return new Bundle(values);
        }

        public long amount(String key) {
            Long value = amounts.get(key);
            return value == null ? 0L : value;
        }

        public Bundle plus(Bundle other) {
            Objects.requireNonNull(other, "other");
            Map<String, Long> sum = new TreeMap<String, Long>(amounts);
            for (Map.Entry<String, Long> entry : other.amounts.entrySet()) {
                long next = Math.addExact(amount(entry.getKey()), entry.getValue());
                if (next > MAX_ASSET_BALANCE) throw new ArithmeticException("asset balance limit exceeded");
                sum.put(entry.getKey(), next);
            }
            return new Bundle(sum);
        }

        /** Integer floor per asset; arbitrary nonnegative ratios, without multiply overflow. */
        public Bundle scaled(int numerator, int denominator) {
            if (numerator < 0 || denominator <= 0)
                throw new IllegalArgumentException("invalid scale ratio");
            Map<String, Long> scaled = new TreeMap<String, Long>();
            for (Map.Entry<String, Long> entry : amounts.entrySet()) {
                BigInteger value = BigInteger.valueOf(entry.getValue())
                        .multiply(BigInteger.valueOf(numerator)).divide(BigInteger.valueOf(denominator));
                if (value.compareTo(BigInteger.valueOf(MAX_ASSET_BALANCE)) > 0)
                    throw new ArithmeticException("scaled asset balance limit exceeded");
                scaled.put(entry.getKey(), value.longValueExact());
            }
            return new Bundle(scaled);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Bundle && amounts.equals(((Bundle) other).amounts);
        }
        @Override public int hashCode() { return amounts.hashCode(); }
        @Override public String toString() { return amounts.toString(); }
    }

    public static final class LootEntry {
        public final String key;
        public final int minimum, maximum, weight;
        private LootEntry(String key, int minimum, int maximum, int weight) {
            validateAssetKey(key);
            if (minimum <= 0 || maximum < minimum || maximum > 100_000 || weight <= 0)
                throw new IllegalArgumentException("invalid loot entry");
            this.key = key; this.minimum = minimum; this.maximum = maximum; this.weight = weight;
        }
    }

    private final Map<Table, List<LootEntry>> tables;

    public Rewards() {
        EnumMap<Table, List<LootEntry>> values = new EnumMap<Table, List<LootEntry>>(Table.class);
        values.put(Table.BASIC, entries(
                e("COIN_VOUCHER", 250, 700, 22), e("IRON_ORE_VOUCHER", 4, 10, 10),
                e("COAL_VOUCHER", 3, 8, 9), e("MIND_RUNE_VOUCHER", 30, 65, 9),
                e("AIR_RUNE_VOUCHER", 35, 75, 9), e("WATER_RUNE_VOUCHER", 30, 65, 8),
                e("EARTH_RUNE_VOUCHER", 30, 65, 8), e("FIRE_RUNE_VOUCHER", 30, 65, 8),
                e("LOBSTER_VOUCHER", 2, 4, 7), e("FLAX_VOUCHER", 6, 16, 5),
                e("OAK_LOG_VOUCHER", 4, 10, 5), e("DUSTY_RELIC", 1, 2, 3),
                e("UNCUT_SAPPHIRE_VOUCHER", 1, 2, 2)));
        values.put(Table.GUARDED, entries(
                e("COIN_VOUCHER", 600, 1_300, 22), e("COAL_VOUCHER", 6, 14, 10),
                e("MITHRIL_ORE_VOUCHER", 3, 8, 8), e("CHAOS_RUNE_VOUCHER", 15, 35, 10),
                e("NATURE_RUNE_VOUCHER", 8, 18, 7), e("LAW_RUNE_VOUCHER", 8, 18, 7),
                e("LOBSTER_VOUCHER", 3, 6, 7), e("SWORDFISH_VOUCHER", 2, 4, 6),
                e("RANARR_SEED_VOUCHER", 1, 1, 2), e("HARRALANDER_VOUCHER", 3, 7, 8),
                e("WILLOW_LOG_VOUCHER", 6, 14, 6), e("UNCUT_EMERALD_VOUCHER", 1, 2, 4),
                e("DUSTY_RELIC", 2, 4, 5), e("TATTERED_MAP_FRAGMENT", 1, 1, 3)));
        values.put(Table.AGILITY, entries(
                e("COIN_VOUCHER", 700, 1_500, 19), e("DUNGEON_SHARD", 3, 7, 13),
                e("LAW_RUNE_VOUCHER", 12, 26, 9), e("NATURE_RUNE_VOUCHER", 12, 26, 9),
                e("COSMIC_RUNE_VOUCHER", 15, 35, 10), e("ENERGY_POTION_VOUCHER", 1, 2, 8),
                e("SWORDFISH_VOUCHER", 3, 6, 8), e("UNCUT_EMERALD_VOUCHER", 1, 3, 7),
                e("UNCUT_RUBY_VOUCHER", 1, 2, 4), e("TOADFLAX_VOUCHER", 2, 4, 6),
                e("SILVER_ORE_VOUCHER", 5, 12, 6), e("GOLD_ORE_VOUCHER", 4, 8, 5),
                e("TATTERED_MAP_FRAGMENT", 1, 2, 4), e("CRYPT_INSCRIPTION", 1, 1, 2)));
        values.put(Table.ELITE, entries(
                e("COIN_VOUCHER", 1_000, 2_200, 20), e("DUNGEON_SHARD", 5, 10, 12),
                e("MITHRIL_ORE_VOUCHER", 6, 14, 8), e("ADAMANTITE_ORE_VOUCHER", 2, 5, 5),
                e("DEATH_RUNE_VOUCHER", 10, 24, 9), e("CHAOS_RUNE_VOUCHER", 25, 50, 10),
                e("NATURE_RUNE_VOUCHER", 18, 35, 8), e("SHARK_VOUCHER", 2, 5, 7),
                e("PRAYER_POTION_VOUCHER", 1, 2, 4), e("SUPER_ATTACK_VOUCHER", 1, 2, 5),
                e("UNCUT_RUBY_VOUCHER", 1, 3, 6), e("UNCUT_DIAMOND_VOUCHER", 1, 1, 2),
                e("IRIT_LEAF_VOUCHER", 3, 6, 5),
                e("CRYPT_INSCRIPTION", 1, 2, 4), e("DARK_RELIC_FRAGMENT", 1, 1, 2)));
        values.put(Table.BOSS, entries(
                e("COIN_VOUCHER", 1_500, 3_000, 19), e("DUNGEON_SHARD", 8, 16, 14),
                e("DEATH_RUNE_VOUCHER", 20, 40, 10), e("BLOOD_RUNE_VOUCHER", 12, 28, 7),
                e("ADAMANTITE_ORE_VOUCHER", 4, 8, 7), e("RUNITE_ORE_VOUCHER", 1, 2, 2),
                e("SHARK_VOUCHER", 4, 8, 9), e("PRAYER_POTION_VOUCHER", 2, 3, 6),
                e("SUPER_RESTORE_VOUCHER", 1, 2, 4), e("SUPER_STRENGTH_VOUCHER", 1, 2, 5),
                e("UNCUT_DIAMOND_VOUCHER", 1, 2, 4), e("RANARR_SEED_VOUCHER", 1, 1, 3),
                e("YEW_LOG_VOUCHER", 8, 16, 6), e("DARK_RELIC_FRAGMENT", 1, 2, 5),
                e("CRYPT_INSCRIPTION", 2, 3, 5), e("WARDEN_COSMETIC_FRAGMENT", 1, 1, 1)));
        for (Table table : Table.values()) {
            int total = 0;
            for (LootEntry entry : values.get(table)) total = Math.addExact(total, entry.weight);
            if (total <= 0) throw new IllegalStateException("empty loot table");
        }
        tables = Collections.unmodifiableMap(values);
    }

    public Map<Table, List<LootEntry>> tables() { return tables; }

    /**
     * The caller supplies a server-created secret run seed and immutable source ID.
     * Opening another chest first cannot change this source's roll.
     */
    public Bundle roll(long dungeonSeed, String stableSource, Table table, Tier tier) {
        validateIdentity(stableSource, 256, "reward source");
        Objects.requireNonNull(table, "table"); Objects.requireNonNull(tier, "tier");
        SplittableRandom random = new SplittableRandom(streamSeed(dungeonSeed, stableSource, table, tier));
        int rolls = new int[] {2, 3, 3, 4, 5}[table.ordinal()];
        int[] minimumShards = {2, 4, 5, 7, 18};
        int[] maximumShards = {4, 7, 8, 12, 26};
        int scale = new int[] {100, 125, 150}[tier.ordinal()];
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        result.put("DUNGEON_SHARD", (long) random.nextInt(minimumShards[table.ordinal()],
                maximumShards[table.ordinal()] + 1));
        List<LootEntry> entries = tables.get(table);
        int total = 0;
        for (LootEntry entry : entries) total += entry.weight;
        for (int i = 0; i < rolls; i++) {
            int pick = random.nextInt(total);
            LootEntry chosen = null;
            for (LootEntry entry : entries) {
                pick -= entry.weight;
                if (pick < 0) { chosen = entry; break; }
            }
            if (chosen == null) throw new IllegalStateException("loot weights are inconsistent");
            long quantity = random.nextInt(chosen.minimum, chosen.maximum + 1);
            Long previous = result.get(chosen.key);
            result.put(chosen.key, (previous == null ? 0 : previous) + quantity);
        }
        Bundle scaled = new Bundle(result).scaled(scale, 100);
        Map<String, Long> capped = new TreeMap<String, Long>(scaled.amounts);
        if (capped.containsKey("COIN_VOUCHER"))
            capped.put("COIN_VOUCHER", Math.min(MAX_COIN_VOUCHERS_PER_ROLL, capped.get("COIN_VOUCHER")));
        capped.put("DUNGEON_SHARD", Math.min(MAX_SHARDS_PER_ROLL, capped.get("DUNGEON_SHARD")));
        return new Bundle(capped);
    }

    private static LootEntry e(String key, int minimum, int maximum, int weight) {
        return new LootEntry(key, minimum, maximum, weight);
    }
    private static List<LootEntry> entries(LootEntry... entries) {
        List<LootEntry> result = new ArrayList<LootEntry>();
        Collections.addAll(result, entries);
        return Collections.unmodifiableList(result);
    }
    static void validateAssetKey(String key) {
        if (key == null || !key.matches("[A-Z][A-Z0-9_]{1,47}"))
            throw new IllegalArgumentException("invalid symbolic reward key");
    }
    static void validateIdentity(String value, int maximumBytes, String label) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())
                || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
            throw new IllegalArgumentException("invalid " + label);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c))
                throw new IllegalArgumentException("unsupported character in " + label);
        }
    }
    static byte[] canonical(Bundle bundle) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(bundle.amounts.size());
            for (Map.Entry<String, Long> entry : bundle.amounts.entrySet()) {
                out.writeUTF(entry.getKey()); out.writeLong(entry.getValue());
            }
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            result[2 * i] = alphabet[(bytes[i] >>> 4) & 15];
            result[2 * i + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }
    static byte[] unhex(String hex) {
        if (hex == null || hex.length() != 64) throw new IllegalArgumentException("invalid SHA-256 digest");
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) {
            int a = Character.digit(hex.charAt(2 * i), 16);
            int b = Character.digit(hex.charAt(2 * i + 1), 16);
            if (a < 0 || b < 0) throw new IllegalArgumentException("invalid SHA-256 encoding");
            result[i] = (byte) ((a << 4) | b);
        }
        return result;
    }
    private static long streamSeed(long seed, String source, Table table, Tier tier) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("HOLLOW_DEPTHS_REWARDS_V1"); out.writeLong(seed);
            out.writeUTF(source); out.writeUTF(table.name()); out.writeUTF(tier.name()); out.flush();
            byte[] digest = digest(bytes.toByteArray());
            long mixed = 0;
            for (int i = 0; i < 8; i++) mixed = (mixed << 8) | (digest[i] & 255L);
            return mixed;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
}
