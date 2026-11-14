package io.ruin.model.activities.hollowdepths;

import com.google.gson.annotations.Expose;
import com.newscape.dungeons.Rewards;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Receipts live beside the inventory and skills in the same atomic player document. */
public final class HollowDepthsState {
    public static final int MAX_RECEIPTS = 100000;
    @Expose public int schema = 1;
    @Expose public String accountId;
    @Expose public long revision;
    @Expose public Map<String, Long> redeemed = new TreeMap<>();
    @Expose public Map<String, String> redemptionReceipts = new TreeMap<>();
    @Expose public Map<String, Integer> xpReceipts = new TreeMap<>();
    @Expose public String recoveryRunId;
    @Expose public int lobbyX, lobbyY, lobbyZ;
    @Expose public String pendingTransaction;
    @Expose public Map<String, Long> pendingRewards = new TreeMap<>();

    public HollowDepthsState() { }
    public HollowDepthsState(String accountId) { this.accountId = accountId; }

    public HollowDepthsState copy() {
        HollowDepthsState copy = new HollowDepthsState(accountId);
        copy.schema = schema;
        copy.revision = revision;
        copy.redeemed = new TreeMap<>(redeemed);
        copy.redemptionReceipts = new TreeMap<>(redemptionReceipts);
        copy.xpReceipts = new TreeMap<>(xpReceipts);
        copy.recoveryRunId = recoveryRunId;
        copy.lobbyX = lobbyX; copy.lobbyY = lobbyY; copy.lobbyZ = lobbyZ;
        copy.pendingTransaction = pendingTransaction;
        copy.pendingRewards = new TreeMap<>(pendingRewards);
        return copy;
    }

    public void validate(String expectedAccount) {
        if (schema != 1 || !expectedAccount.equals(accountId) || revision < 0
                || redeemed == null || redemptionReceipts == null || xpReceipts == null || pendingRewards == null)
            throw new IllegalStateException("Invalid Hollow Depths account record");
        new Rewards.Bundle(redeemed);
        new Rewards.Bundle(pendingRewards);
        if (redemptionReceipts.size() > MAX_RECEIPTS || xpReceipts.size() > MAX_RECEIPTS)
            throw new IllegalStateException("Hollow Depths receipts require migration; never erase them");
        for (Map.Entry<String, String> receipt : redemptionReceipts.entrySet()) {
            if (!receipt.getKey().matches("claim:[0-9]+") || receipt.getValue() == null
                    || !receipt.getValue().matches("[0-9a-f]{64}"))
                throw new IllegalStateException("Invalid redemption receipt");
        }
        for (Map.Entry<String, Integer> receipt : xpReceipts.entrySet()) {
            validAwardId(receipt.getKey());
            if (receipt.getValue() == null || receipt.getValue() < 0 || receipt.getValue() > 1000000)
                throw new IllegalStateException("Invalid agility receipt");
        }
        if (recoveryRunId != null) {
            UUID.fromString(recoveryRunId);
            if (lobbyX < 0 || lobbyX > 16383 || lobbyY < 0 || lobbyY > 16383 || lobbyZ < 0 || lobbyZ > 3)
                throw new IllegalStateException("Invalid dungeon recovery destination");
        }
        if (pendingTransaction == null && !pendingRewards.isEmpty())
            throw new IllegalStateException("Reward payload has no transaction identity");
        if (pendingTransaction != null) validTransaction(pendingTransaction);
    }

    static void validAwardId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}:agility:[0-9]+"))
            throw new IllegalArgumentException("Invalid dungeon agility award identity");
        UUID.fromString(id.substring(0, 36));
    }

    static void validTransaction(String id) {
        if (id == null || !id.matches("dungeon:[0-9a-fA-F-]{36}:extraction"))
            throw new IllegalArgumentException("Invalid extraction transaction identity");
        UUID.fromString(id.substring(8, 44));
    }
}
