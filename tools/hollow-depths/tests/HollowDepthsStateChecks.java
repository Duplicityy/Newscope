package io.ruin.model.activities.hollowdepths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.UUID;

public final class HollowDepthsStateChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Malformed account transaction was accepted");
    }
    public static void main(String[] args) {
        HollowDepthsState state = new HollowDepthsState("local:1001");
        String run = UUID.randomUUID().toString();
        state.recoveryRunId = run;
        state.lobbyX = 3090; state.lobbyY = 3500;
        state.pendingTransaction = "dungeon:" + run + ":extraction";
        state.pendingRewards.put("COIN_VOUCHER", 100L);
        state.xpReceipts.put(run + ":agility:0", 35);
        state.validate("local:1001");
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        HollowDepthsState reloaded = gson.fromJson(gson.toJson(state), HollowDepthsState.class);
        reloaded.validate("local:1001");
        require(reloaded.pendingRewards.equals(state.pendingRewards), "Pending payload lost on JSON reload");
        require(reloaded.xpReceipts.equals(state.xpReceipts), "XP receipt lost on JSON reload");
        HollowDepthsState copy = state.copy();
        copy.pendingRewards.put("COIN_VOUCHER", 200L);
        require(state.pendingRewards.get("COIN_VOUCHER") == 100L, "Transaction before-image aliases mutable payload");
        rejects(() -> state.validate("local:1002"));
        copy = state.copy(); copy.redeemed.put("COIN_VOUCHER", -1L);
        final HollowDepthsState negative = copy;
        rejects(() -> negative.validate("local:1001"));
        copy = state.copy(); copy.pendingTransaction = null;
        final HollowDepthsState orphanPayload = copy;
        rejects(() -> orphanPayload.validate("local:1001"));
        rejects(() -> HollowDepthsState.validAwardId("untrusted award"));
        rejects(() -> HollowDepthsState.validTransaction("replacement-transaction"));
        System.out.println("PASS: durable state JSON roundtrip, deep copy, account binding, negative balance, orphan payload and invalid identity rejection");
    }
}
