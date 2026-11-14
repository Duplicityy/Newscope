package io.ruin.model.activities.hollowdepths;

import com.newscape.dungeons.FileRewardStore;
import com.newscape.dungeons.Rewards;
import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.item.Item;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;
import io.ruin.utility.OfflineMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Append-only earned mailbox + a player-owned redeemed ledger. A claim never debits a
 * different file: the inventory, cumulative redeemed amounts and receipt commit together.
 * All Player methods are world-thread operations. Store methods contain no Player access.
 */
public final class HollowDepthsRewards implements Rewards.Store, AutoCloseable {
    private final FileRewardStore mailbox;
    private final ExecutorService mailboxQueries = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
        Thread worker = new Thread(runnable, "hollow-depths-mailbox-query");
        worker.setDaemon(true);
        return worker;
    });
    private final Map<Player, PendingSave> pendingSaves = new IdentityHashMap<>();
    private static final Map<String, Mapping> MAPPINGS = mappings();

    public HollowDepthsRewards(Path directory) throws IOException { mailbox = new FileRewardStore(directory); }

    public static String accountId(Player player) {
        if (!OfflineMode.hasStableAccountRegistry() || player.getUserId() < 1000
                || player instanceof io.ruin.model.entity.player.autonomous.Adventurer)
            throw new IllegalStateException("Hollow Depths requires a registered local player account");
        return "local:" + player.getUserId();
    }

    private static void validAccount(String account) {
        if (account == null || !account.matches("local:[1-9][0-9]{3,9}"))
            throw new IllegalArgumentException("Invalid stable local account identity");
    }

    @Override public Rewards.DepositResult depositOnce(String account, String transaction, Rewards.Bundle bundle) throws IOException {
        validAccount(account);
        return mailbox.depositOnce(account, transaction, bundle);
    }

    /** Lifetime earned credits. Use available(Player) for the unclaimed player-facing balance. */
    @Override public Rewards.Bundle balance(String account) throws IOException {
        validAccount(account);
        return mailbox.balance(account);
    }

    private <T> T mailboxQuery(Callable<T> query) throws IOException {
        Future<T> future;
        try { future = mailboxQueries.submit(query); }
        catch (RejectedExecutionException stopped) { throw new IOException("Mailbox query worker is stopped", stopped); }
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Mailbox query interrupted", interrupted);
        } catch (TimeoutException timeout) {
            // A replay may still commit. The complete pending intent remains in the player save.
            throw new IOException("Mailbox query timed out; retry the same transaction", timeout);
        } catch (ExecutionException failed) {
            throw new IOException("Mailbox query failed", failed.getCause());
        }
    }

    private HollowDepthsState state(Player player) {
        if (PlayerFile.isSaveQuarantined(player)) throw new IllegalStateException("Player save is quarantined");
        String account = accountId(player);
        if (player.hollowDepths == null) player.hollowDepths = new HollowDepthsState(account);
        player.hollowDepths.validate(account);
        return player.hollowDepths;
    }

    public Rewards.Bundle available(Player player) throws IOException {
        HollowDepthsState state = state(player);
        Rewards.Bundle earned = mailboxQuery(() -> balance(state.accountId));
        Map<String, Long> available = new TreeMap<>(earned.amounts);
        for (Map.Entry<String, Long> redeemed : state.redeemed.entrySet()) {
            long left = earned.amount(redeemed.getKey()) - redeemed.getValue();
            if (left < 0) throw new IOException("Mailbox/player backup mismatch; restore a consistent backup");
            available.put(redeemed.getKey(), left);
        }
        return new Rewards.Bundle(available);
    }

    public void showBalance(Player player) {
        try {
            Rewards.Bundle credits = available(player);
            if (credits.amounts.isEmpty()) { player.sendMessage("Your Hollow Depths mailbox is empty."); return; }
            player.sendMessage("Hollow Depths mailbox (unclaimed; resources can be collected):");
            for (Map.Entry<String, Long> credit : credits.amounts.entrySet())
                player.sendMessage(credit.getValue() + " " + label(credit.getKey())
                        + (MAPPINGS.containsKey(credit.getKey()) ? "" : " (account-bound)"));
        } catch (IOException | RuntimeException failure) {
            Server.logError("Hollow Depths mailbox lookup failed", failure);
            player.sendMessage("Your reward mailbox is unavailable. Its credits have not been changed.");
        }
    }

    /** Partial collection is intentional: anything that does not fit stays in the mailbox. */
    public int redeem(Player player) {
        HollowDepthsState before = state(player);
        if (before.recoveryRunId != null || before.pendingTransaction != null || player.isLocked()
                || player.getCombat().isDead() || player.getCombat().isDefending(17)) {
            player.sendMessage("Collect your Hollow Depths rewards safely in the lobby after your run.");
            return 0;
        }
        if (before.redemptionReceipts.size() >= HollowDepthsState.MAX_RECEIPTS)
            throw new IllegalStateException("Reward receipt archive requires migration");
        final Rewards.Bundle credits;
        try { credits = available(player); }
        catch (IOException failure) { throw new IllegalStateException("Mailbox is unavailable; nothing was claimed", failure); }
        Item[] oldItems = copyItems(player);
        Map<String, Long> claimed = new TreeMap<>();
        HollowDepthsState next = before.copy();
        try {
            for (Map.Entry<String, Long> credit : credits.amounts.entrySet()) {
                Mapping mapping = MAPPINGS.get(credit.getKey());
                if (mapping == null) continue; // Progression credits never become guessed cache items.
                int itemId = mapping.deliveryId();
                Item stack = player.getInventory().findItem(itemId);
                long room = Integer.MAX_VALUE - (stack == null ? 0L : stack.getAmount());
                if (room <= 0 || (stack == null && player.getInventory().getFreeSlots() == 0)) continue;
                int amount = (int) Math.min(credit.getValue(), room);
                if (amount <= 0) continue;
                if (player.getInventory().add(itemId, amount) != amount)
                    throw new IllegalStateException("Validated reward did not fit its reserved inventory space");
                claimed.put(credit.getKey(), (long) amount);
                next.redeemed.put(credit.getKey(), Math.addExact(next.redeemed.getOrDefault(credit.getKey(), 0L), amount));
            }
            if (claimed.isEmpty()) {
                player.sendMessage("No resource rewards fit. Make inventory space; account-bound credits remain in your mailbox.");
                return 0;
            }
            next.redemptionReceipts.put("claim:" + Math.addExact(next.revision, 1), fingerprint(new Rewards.Bundle(claimed)));
            player.hollowDepths = next;
            commit(player, () -> { restoreItems(player, oldItems); player.hollowDepths = before; });
        } catch (RuntimeException failure) {
            if (!PlayerFile.isSaveQuarantined(player)) {
                restoreItems(player, oldItems);
                player.hollowDepths = before;
            }
            throw failure;
        }
        player.sendMessage("Collected " + claimed.size() + " resource reward types. Other credits stay in your mailbox.");
        return claimed.size();
    }

    /** Exact package XP, with no unrelated multiplier/token side effects from generic addXp. */
    public void awardAgilityXpOnce(Player player, String awardId, int xp) {
        HollowDepthsState.validAwardId(awardId);
        if (xp < 0 || xp > 1000000) throw new IllegalArgumentException("Invalid dungeon XP award");
        HollowDepthsState before = state(player);
        Integer previous = before.xpReceipts.get(awardId);
        if (previous != null) {
            if (previous != xp) throw new IllegalStateException("Agility receipt payload changed");
            return;
        }
        if (before.xpReceipts.size() >= HollowDepthsState.MAX_RECEIPTS)
            throw new IllegalStateException("Agility receipt archive requires migration");
        Stat agility = player.getStats().get(StatType.Agility);
        double oldXp = agility.experience;
        int oldFixed = agility.fixedLevel, oldCurrent = agility.currentLevel;
        HollowDepthsState next = before.copy();
        next.xpReceipts.put(awardId, xp);
        if (!player.experienceLock) {
            agility.experience = Math.min(Stat.MAX_XP, oldXp + xp);
            agility.fixedLevel = Stat.levelForXp(agility.experience);
            if (agility.currentLevel < agility.fixedLevel) agility.currentLevel += agility.fixedLevel - oldFixed;
            agility.updated = true;
        }
        player.hollowDepths = next;
        commit(player, () -> {
            player.hollowDepths = before;
            agility.experience = oldXp; agility.fixedLevel = oldFixed; agility.currentLevel = oldCurrent; agility.updated = true;
        });
        if (agility.fixedLevel > oldFixed)
            player.sendMessage("Your Agility level is now " + agility.fixedLevel + ".");
    }

    public void recordRecovery(Player player, UUID runId, int lobbyX, int lobbyY, int lobbyZ) {
        HollowDepthsState before = state(player);
        if (before.pendingTransaction != null) throw new IllegalStateException("Recover pending extraction before entering");
        if (before.recoveryRunId != null && !before.recoveryRunId.equals(runId.toString()))
            throw new IllegalStateException("An earlier dungeon still has a recovery marker");
        HollowDepthsState next = before.copy();
        next.recoveryRunId = runId.toString();
        next.lobbyX = lobbyX; next.lobbyY = lobbyY; next.lobbyZ = lobbyZ;
        player.hollowDepths = next;
        commit(player, () -> player.hollowDepths = before);
    }

    public void prepareExtraction(Player player, String transaction, Rewards.Bundle bundle) {
        HollowDepthsState.validTransaction(transaction);
        HollowDepthsState before = state(player);
        if (before.pendingTransaction != null) {
            if (!before.pendingTransaction.equals(transaction) || !new Rewards.Bundle(before.pendingRewards).equals(bundle))
                throw new IllegalStateException("Pending extraction identity or payload changed");
            return;
        }
        if (!transaction.equals("dungeon:" + before.recoveryRunId + ":extraction"))
            throw new IllegalStateException("Extraction does not match the recorded dungeon run");
        HollowDepthsState next = before.copy();
        next.pendingTransaction = transaction;
        next.pendingRewards = new TreeMap<>(bundle.amounts);
        player.hollowDepths = next;
        commit(player, () -> player.hollowDepths = before);
    }

    /** Replaying an already committed mailbox deposit is deliberately harmless. */
    public void recoverPendingExtraction(Player player) {
        HollowDepthsState before = state(player);
        if (before.pendingTransaction == null) return;
        try {
            mailboxQuery(() -> depositOnce(before.accountId, before.pendingTransaction, new Rewards.Bundle(before.pendingRewards)));
        } catch (IOException failure) {
            throw new IllegalStateException("Pending extraction remains recorded and must be retried", failure);
        }
        HollowDepthsState next = before.copy();
        next.pendingTransaction = null;
        next.pendingRewards.clear();
        player.hollowDepths = next;
        commit(player, () -> player.hollowDepths = before);
    }

    /** Call after evacuation has actually reached the lobby, not merely queued its teleport. */
    public void clearRecovery(Player player) {
        HollowDepthsState before = state(player);
        if (before.recoveryRunId == null) return;
        if (player.getMovement().isTeleportQueued() || player.getAbsX() != before.lobbyX
                || player.getAbsY() != before.lobbyY || player.getHeight() != before.lobbyZ)
            throw new IllegalStateException("Cannot clear dungeon recovery before actual lobby arrival");
        recoverPendingExtraction(player);
        before = state(player);
        final HollowDepthsState rollback = before;
        HollowDepthsState next = before.copy();
        next.recoveryRunId = null;
        player.hollowDepths = next;
        commit(player, () -> player.hollowDepths = rollback);
    }

    /** Only orphaned/expired logins: a live disconnected run is resumed by DungeonService first. */
    public void recoverLogin(Player player) {
        if (player.hollowDepths == null) return;
        HollowDepthsState state = state(player);
        recoverPendingExtraction(player);
        if (state.recoveryRunId == null) return;
        player.clearHits();
        player.getCombat().reset();
        player.getCombat().setDead(false);
        player.getCombat().setTruelyDead(false);
        player.getCombat().restore();
        player.getMovement().teleport(state.lobbyX, state.lobbyY, state.lobbyZ);
        player.getMovement().finishTeleport(player.getPosition());
        clearRecovery(player);
        player.sendMessage("Your previous Hollow Depths run ended. You have returned safely with your carried items.");
    }

    private static LockType currentLock(Player player) {
        for (LockType lock : LockType.values()) if (player.isLocked(lock)) return lock;
        return LockType.NONE;
    }

    private void commit(Player player, Runnable rollback) {
        LockType previousLock = currentLock(player);
        try {
            player.hollowDepths.revision = Math.addExact(player.hollowDepths.revision, 1);
            player.hollowDepths.validate(accountId(player));
            player.lock(LockType.FULL_NULLIFY_DAMAGE);
            PlayerFile.saveDurably(player);
        } catch (PlayerFile.DurableSaveException failure) {
            if (failure.uncertain) pendingSaves.put(player, new PendingSave(previousLock, rollback));
            else rollback.run();
            throw new IllegalStateException("Hollow Depths transaction did not receive a durable commit confirmation", failure);
        } catch (RuntimeException failure) {
            rollback.run();
            throw failure;
        } finally {
            if (!PlayerFile.isSaveQuarantined(player)) player.lock(previousLock);
        }
    }

    /** Call once per world pulse. Late successful writes can safely release their quarantine. */
    public void pulse() {
        Iterator<Map.Entry<Player, PendingSave>> iterator = pendingSaves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Player, PendingSave> entry = iterator.next();
            PlayerFile.SaveResolution result = PlayerFile.resolveDurableSave(entry.getKey());
            if (result != PlayerFile.SaveResolution.COMMITTED && result != PlayerFile.SaveResolution.NOT_COMMITTED) continue;
            if (result == PlayerFile.SaveResolution.NOT_COMMITTED) entry.getValue().rollback.run();
            entry.getKey().lock(entry.getValue().previousLock);
            entry.getKey().sendMessage(result == PlayerFile.SaveResolution.COMMITTED
                    ? "Your pending save was confirmed. Your dungeon transaction is safe."
                    : "The save failed before committing. No reward was spent; please retry.");
            iterator.remove();
        }
    }

    private static Item[] copyItems(Player player) {
        Item[] source = player.getInventory().getItems(), copy = new Item[source.length];
        for (int i = 0; i < source.length; i++) if (source[i] != null)
            copy[i] = new Item(source[i].getId(), source[i].getAmount(), source[i].copyOfAttributes());
        return copy;
    }

    private static void restoreItems(Player player, Item[] items) {
        for (int i = 0; i < items.length; i++) player.getInventory().set(i, items[i]);
    }

    private static String fingerprint(Rewards.Bundle rewards) {
        try {
            StringBuilder value = new StringBuilder("HOLLOW_DEPTHS_CLAIM_V1\n");
            for (Map.Entry<String, Long> item : rewards.amounts.entrySet())
                value.append(item.getKey()).append('=').append(item.getValue()).append('\n');
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String label(String key) { return key.replace("_VOUCHER", "").replace('_', ' ').toLowerCase(Locale.ROOT); }

    public static void validateMappings() { for (Mapping mapping : MAPPINGS.values()) mapping.deliveryId(); }

    private static final class PendingSave {
        final LockType previousLock;
        final Runnable rollback;
        PendingSave(LockType previousLock, Runnable rollback) { this.previousLock = previousLock; this.rollback = rollback; }
    }

    private static final class Mapping {
        final int baseId;
        final String expectedName;
        Mapping(int baseId, String expectedName) { this.baseId = baseId; this.expectedName = expectedName; }
        int deliveryId() {
            ItemDef base = ItemDef.get(baseId);
            if (base == null || !expectedName.equalsIgnoreCase(base.name))
                throw new IllegalStateException("Unverified 184 reward mapping: " + baseId);
            if (base.stackable) return baseId;
            ItemDef note = base.notedId > 0 ? ItemDef.get(base.notedId) : null;
            if (note == null || !note.stackable || !expectedName.equalsIgnoreCase(note.name)
                    || note.notedTemplateId < 0 || note.notedId != baseId)
                throw new IllegalStateException("Unverified noted reward mapping: " + baseId);
            return note.id;
        }
    }

    private static Map<String, Mapping> mappings() {
        Map<String, Mapping> map = new TreeMap<>();
        map.put("COIN_VOUCHER", new Mapping(995, "Coins"));
        map.put("IRON_ORE_VOUCHER", new Mapping(440, "Iron ore"));
        map.put("COAL_VOUCHER", new Mapping(453, "Coal"));
        map.put("MIND_RUNE_VOUCHER", new Mapping(558, "Mind rune"));
        map.put("AIR_RUNE_VOUCHER", new Mapping(556, "Air rune"));
        map.put("WATER_RUNE_VOUCHER", new Mapping(555, "Water rune"));
        map.put("EARTH_RUNE_VOUCHER", new Mapping(557, "Earth rune"));
        map.put("FIRE_RUNE_VOUCHER", new Mapping(554, "Fire rune"));
        map.put("LOBSTER_VOUCHER", new Mapping(379, "Lobster"));
        map.put("FLAX_VOUCHER", new Mapping(1779, "Flax"));
        map.put("OAK_LOG_VOUCHER", new Mapping(1521, "Oak logs"));
        map.put("UNCUT_SAPPHIRE_VOUCHER", new Mapping(1623, "Uncut sapphire"));
        map.put("MITHRIL_ORE_VOUCHER", new Mapping(447, "Mithril ore"));
        map.put("CHAOS_RUNE_VOUCHER", new Mapping(562, "Chaos rune"));
        map.put("NATURE_RUNE_VOUCHER", new Mapping(561, "Nature rune"));
        map.put("LAW_RUNE_VOUCHER", new Mapping(563, "Law rune"));
        map.put("SWORDFISH_VOUCHER", new Mapping(373, "Swordfish"));
        map.put("RANARR_SEED_VOUCHER", new Mapping(5295, "Ranarr seed"));
        map.put("HARRALANDER_VOUCHER", new Mapping(255, "Harralander"));
        map.put("WILLOW_LOG_VOUCHER", new Mapping(1519, "Willow logs"));
        map.put("UNCUT_EMERALD_VOUCHER", new Mapping(1621, "Uncut emerald"));
        map.put("COSMIC_RUNE_VOUCHER", new Mapping(564, "Cosmic rune"));
        map.put("ENERGY_POTION_VOUCHER", new Mapping(3008, "Energy potion(4)"));
        map.put("UNCUT_RUBY_VOUCHER", new Mapping(1619, "Uncut ruby"));
        map.put("TOADFLAX_VOUCHER", new Mapping(2998, "Toadflax"));
        map.put("SILVER_ORE_VOUCHER", new Mapping(442, "Silver ore"));
        map.put("GOLD_ORE_VOUCHER", new Mapping(444, "Gold ore"));
        map.put("ADAMANTITE_ORE_VOUCHER", new Mapping(449, "Adamantite ore"));
        map.put("DEATH_RUNE_VOUCHER", new Mapping(560, "Death rune"));
        map.put("SHARK_VOUCHER", new Mapping(385, "Shark"));
        map.put("PRAYER_POTION_VOUCHER", new Mapping(2434, "Prayer potion(4)"));
        map.put("SUPER_ATTACK_VOUCHER", new Mapping(2436, "Super attack(4)"));
        map.put("UNCUT_DIAMOND_VOUCHER", new Mapping(1617, "Uncut diamond"));
        map.put("IRIT_LEAF_VOUCHER", new Mapping(259, "Irit leaf"));
        map.put("BLOOD_RUNE_VOUCHER", new Mapping(565, "Blood rune"));
        map.put("RUNITE_ORE_VOUCHER", new Mapping(451, "Runite ore"));
        map.put("SUPER_RESTORE_VOUCHER", new Mapping(3024, "Super restore(4)"));
        map.put("SUPER_STRENGTH_VOUCHER", new Mapping(2440, "Super strength(4)"));
        map.put("YEW_LOG_VOUCHER", new Mapping(1515, "Yew logs"));
        return Collections.unmodifiableMap(map);
    }

    @Override public void close() throws IOException {
        mailboxQueries.shutdown();
        mailbox.close();
    }
}
