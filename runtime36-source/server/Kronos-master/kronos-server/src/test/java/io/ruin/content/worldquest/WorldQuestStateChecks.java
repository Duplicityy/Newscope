package io.ruin.content.worldquest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import static io.ruin.content.worldquest.RiverWatch.Action;
import static io.ruin.content.worldquest.RiverWatch.Stage;

public final class WorldQuestStateChecks {
    private static final Gson SAVES = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private static int checks;

    private static final class PlayerSave {
        @Expose WorldQuestState worldQuests;
        @Expose int otherProgress;
    }

    public static void main(String[] args) {
        PlayerSave old = SAVES.fromJson("{\"otherProgress\":42}", PlayerSave.class);
        check(old.worldQuests == null && old.otherProgress == 42, "legacy saves leave the quest field absent");
        check(!SAVES.toJson(old).contains("worldQuests"), "an untouched save does not allocate a quest record");
        check(reload("{}").riverWatch() == Stage.NOT_STARTED, "an empty quest record can start");

        checkPath(Action.READ_UPSTREAM, Action.READ_DOWNSTREAM, Stage.UPSTREAM_READ);
        checkPath(Action.READ_DOWNSTREAM, Action.READ_UPSTREAM, Stage.DOWNSTREAM_READ);

        WorldQuestState otherQuest = reload("{\"stages\":{\"future-quest\":\"STARTED\"}}");
        check(otherQuest.applyRiverWatch(Action.ACCEPT), "unknown quest ids do not block known quests");
        check(SAVES.toJson(otherQuest).contains("\"future-quest\":\"STARTED\""), "other quest state survives");
        unavailable("{\"stages\":{\"river-watch\":\"UNKNOWN_STAGE\"}}");
        unavailable("{\"stages\":{\"river-watch\":null}}");
        unavailable("{\"stages\":null}");
        System.out.println("River Watch state and save checks passed: " + checks);
    }

    private static void checkPath(Action first, Action second, Stage middle) {
        WorldQuestState progress = new WorldQuestState();
        rejectsExcept(progress, Action.ACCEPT);
        progress = applyAndReload(progress, Action.ACCEPT, Stage.ACCEPTED);
        rejectsExcept(progress, first, second);
        progress = applyAndReload(progress, first, middle);
        rejectsExcept(progress, second);
        progress = applyAndReload(progress, second, Stage.SURVEYED);
        rejectsExcept(progress, Action.DIAGNOSE_LATCH);
        progress = applyAndReload(progress, Action.DIAGNOSE_LATCH, Stage.DIAGNOSED);
        rejectsExcept(progress, Action.REPAIR);
        progress = applyAndReload(progress, Action.REPAIR, Stage.REPAIRED);
        rejectsExcept(progress, Action.CLAIM);

        check(progress.applyRiverWatch(Action.CLAIM), "begin the reward transaction");
        progress.undoRiverWatchClaim();
        check(progress.riverWatch() == Stage.REPAIRED, "a definite save failure restores the unpaid state");
        progress = applyAndReload(progress, Action.CLAIM, Stage.COMPLETE);
        rejectsExcept(progress);
    }

    private static WorldQuestState applyAndReload(WorldQuestState state, Action action, Stage expected) {
        check(state.applyRiverWatch(action), "accept " + action);
        WorldQuestState after = reload(SAVES.toJson(state));
        check(after.riverWatch() == expected, "reload " + expected);
        return after;
    }

    private static WorldQuestState reload(String json) {
        return SAVES.fromJson(json, WorldQuestState.class);
    }

    private static void unavailable(String json) {
        WorldQuestState state = reload(json);
        check(state.riverWatch() == Stage.UNAVAILABLE, "invalid progress is unavailable");
        rejectsExcept(state);
    }

    private static void rejectsExcept(WorldQuestState progress, Action... permitted) {
        Stage before = progress.riverWatch();
        for (Action action : Action.values()) {
            boolean allowed = false;
            for (Action valid : permitted) allowed |= action == valid;
            if (!allowed) {
                check(!progress.applyRiverWatch(action), "reject " + action + " from " + before);
                check(progress.riverWatch() == before, "rejected action must preserve " + before);
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
