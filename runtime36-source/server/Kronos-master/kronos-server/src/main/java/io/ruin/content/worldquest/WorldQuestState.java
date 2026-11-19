package io.ruin.content.worldquest;

import com.google.gson.annotations.Expose;

import java.util.HashMap;
import java.util.Map;

/** Saved with the player. Quest keys and stage names are stable save identifiers. */
public final class WorldQuestState {

    @Expose private Map<String, String> stages = new HashMap<>();

    RiverWatch.Stage riverWatch() {
        if (stages == null) return RiverWatch.Stage.UNAVAILABLE;
        if (!stages.containsKey(RiverWatch.ID)) return RiverWatch.Stage.NOT_STARTED;
        String stored = stages.get(RiverWatch.ID);
        if (stored == null) return RiverWatch.Stage.UNAVAILABLE;
        try {
            return RiverWatch.Stage.valueOf(stored);
        } catch (IllegalArgumentException invalid) {
            return RiverWatch.Stage.UNAVAILABLE;
        }
    }

    boolean applyRiverWatch(RiverWatch.Action action) {
        RiverWatch.Stage current = riverWatch();
        RiverWatch.Stage next = current.after(action);
        if (next == current) return false;
        stages.put(RiverWatch.ID, next.name());
        return true;
    }

    void undoRiverWatchClaim() {
        if (riverWatch() != RiverWatch.Stage.COMPLETE)
            throw new IllegalStateException("River Watch reward is not in progress");
        stages.put(RiverWatch.ID, RiverWatch.Stage.REPAIRED.name());
    }
}
