package io.ruin.model.entity.player;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.api.process.ProcessFactory;
import io.ruin.api.utils.JsonUtils;
import io.ruin.model.World;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.skills.construction.RoomDefinition;
import io.ruin.model.skills.construction.room.Room;
import io.ruin.network.central.CentralClient;
import io.ruin.utility.OfflineMode;
import io.ruin.utility.AtomicPlayerSave;
import io.ruin.model.entity.shared.LockType;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PlayerFile {

    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(new ProcessFactory("save-worker", Thread.NORM_PRIORITY - 1));
    private static final Map<Player, String> QUARANTINED = Collections.synchronizedMap(new IdentityHashMap<Player, String>());
    private static final Map<Player, Future<?>> PENDING_DURABLE = Collections.synchronizedMap(new IdentityHashMap<Player, Future<?>>());
    private static final long DURABLE_WAIT_MILLIS = Math.max(100L, Math.min(10000L,
            Long.getLong("kronos.durableSaveTimeoutMillis", 5000L)));
    public static volatile long lastDurableSaveMillis, maximumDurableSaveMillis;
    private static final Gson GSON_LOADER = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().registerTypeAdapter(Room.class, new JsonDeserializer<Room>() {
        @Override
        public Room deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            JsonObject obj = jsonElement.getAsJsonObject();
            RoomDefinition definition = RoomDefinition.valueOf(obj.get("definition").getAsString());
            return jsonDeserializationContext.deserialize(jsonElement, definition.getHandler());
        }
    }).create();

    public static <T extends Player> T decode(String json, Class<T> type) { return GSON_LOADER.fromJson(json, type); }

    public static boolean isSaveQuarantined(Player player) { return QUARANTINED.containsKey(player); }

    public enum SaveResolution { PENDING, COMMITTED, NOT_COMMITTED, UNKNOWN }

    /** World-thread, nonblocking completion check. The transaction owner restores its own before-image. */
    public static SaveResolution resolveDurableSave(Player player) {
        Future<?> pending = PENDING_DURABLE.get(player);
        if (pending == null) return SaveResolution.UNKNOWN;
        if (!pending.isDone()) return SaveResolution.PENDING;
        SaveResolution result;
        try {
            pending.get();
            result = SaveResolution.COMMITTED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SaveResolution.PENDING;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AtomicPlayerSave.UnknownOutcomeException) return SaveResolution.UNKNOWN;
            result = SaveResolution.NOT_COMMITTED;
        }
        PENDING_DURABLE.remove(player);
        QUARANTINED.remove(player);
        return result;
    }

    public static void quarantine(Player player, String reason) {
        QUARANTINED.put(player, reason);
        player.lock(LockType.FULL_NULLIFY_DAMAGE);
        player.stopEvent(true);
        player.resetActions(true, true, true);
        player.clearHits();
        Server.logError("Player save quarantined for account " + player.getUserId() + ": " + reason,
                new IOException("Persistence requires operator verification or process restart"));
        player.sendMessage("Your save could not be confirmed. Your account is locked to protect its items; please contact staff.");
    }

    public static final class DurableSaveException extends IOException {
        public final boolean uncertain;
        public DurableSaveException(String message, Throwable cause, boolean uncertain) {
            super(message, cause);
            this.uncertain = uncertain;
        }
    }

    /** Capture only on the world thread, before handing detached text to the save worker. */
    private static String snapshot(Player player) {
        Config.save(player);
        return JsonUtils.GSON_EXPOSE.toJson(player);
    }

    /**
     * Strict transaction barrier for local, registered accounts. All earlier queued snapshots
     * finish first. The caller cannot expose its new inventory/XP until this method succeeds.
     * A timeout does NOT cancel a possibly committing write and must never trigger rollback.
     */
    public static void saveDurably(Player player) throws DurableSaveException {
        if (isSaveQuarantined(player))
            throw new DurableSaveException("Account save is quarantined", null, true);
        if (!OfflineMode.hasStableAccountRegistry() || player.getUserId() < 1000)
            throw new DurableSaveException("Durable rewards require registered local accounts", null, false);
        final String json;
        final Path target;
        try {
            target = OfflineMode.playerSavePath(player.getName());
            json = snapshot(player);
        } catch (Exception failure) {
            throw new DurableSaveException("Cannot prepare player snapshot", failure, false);
        }
        long started = System.nanoTime();
        Future<?> commit;
        try {
            commit = SAVE_EXECUTOR.submit(() -> {
                OfflineMode.writePlayerSnapshot(target, json);
                return null;
            });
        } catch (RuntimeException rejected) {
            throw new DurableSaveException("Save worker rejected transaction", rejected, false);
        }
        try {
            commit.get(DURABLE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException unknown) {
            if (unknown instanceof InterruptedException) Thread.currentThread().interrupt();
            PENDING_DURABLE.put(player, commit);
            quarantine(player, "Durable save wait interrupted or timed out");
            throw new DurableSaveException("Player save may still commit", unknown, true);
        } catch (ExecutionException failed) {
            boolean uncertain = failed.getCause() instanceof AtomicPlayerSave.UnknownOutcomeException;
            if (uncertain) {
                PENDING_DURABLE.put(player, commit);
                quarantine(player, "Atomic replacement outcome is uncertain");
            }
            throw new DurableSaveException("Durable player save failed", failed.getCause(), uncertain);
        } finally {
            lastDurableSaveMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            maximumDurableSaveMillis = Math.max(maximumDurableSaveMillis, lastDurableSaveMillis);
        }
    }

    public static void save(Player player, int logoutAttempt) {
        if (player instanceof io.ruin.model.entity.player.autonomous.Adventurer) {
            // Autonomous periodic snapshots are staggered by their registry.
            // The global World save sweep must not serialize all 25 in one tick.
            if (logoutAttempt != -1)
                io.ruin.model.entity.player.autonomous.Adventurers.save((io.ruin.model.entity.player.autonomous.Adventurer) player);
            return;
        }
        if (isSaveQuarantined(player) || player.getChannel() == null || player.getChannel().id() == null) return;
        final String json;
        final Path localTarget;
        final int userId = player.getUserId();
        try {
            json = snapshot(player);
            localTarget = OfflineMode.enabled ? OfflineMode.playerSavePath(player.getName()) : null;
        } catch (Exception failure) {
            Server.logError("Unable to prepare player save", failure);
            return;
        }
        SAVE_EXECUTOR.execute(() -> {
            try {
                if (localTarget != null) {
                    OfflineMode.writePlayerSnapshot(localTarget, json);
                    if (logoutAttempt != -1) player.finishLogout(logoutAttempt);
                } else {
                    CentralClient.sendSave(userId, logoutAttempt, json);
                }
            } catch(Exception e) {
                // A failed save must not release the session; checkLogout retries its snapshot.
                Server.logError("Player save failed; logout remains pending", e);
            }
        });
    }

    public static Player load(PlayerLogin login) {
        try {
            Player player;
            if(login.info.saved == null || login.info.saved.isEmpty() || login.info.userId == 25) //todo remove this test user force reset
                player = new Player();
            else
                player =  GSON_LOADER.fromJson(login.info.saved, Player.class);
            Config.load(player);
            return player;
        } catch(Throwable t) {
            Server.logError("", t);
            return null;
        }
    }

}
