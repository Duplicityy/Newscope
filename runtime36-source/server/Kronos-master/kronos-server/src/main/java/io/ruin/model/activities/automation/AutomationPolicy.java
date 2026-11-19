package io.ruin.model.activities.automation;

/** Pure policy shared by the controller and detached checks. */
final class AutomationPolicy {
    static final int MAX_ROUTE_FAILURES = 5;
    static final int ROUTE_TICKS = 28;
    static final int NO_PROGRESS_TICKS = 180;
    static final int NO_LEVEL_LIMIT = Integer.MAX_VALUE;

    private AutomationPolicy() {}

    static boolean validGoal(int level) {
        return level >= 2 && level <= 99;
    }

    static int levelLimit(int goal, int currentLevel, boolean singleSkill, boolean slayer) {
        return singleSkill && (slayer || currentLevel >= 99) ? NO_LEVEL_LIMIT : goal;
    }

    static double rate(double xp, double chancePercent, int distance, double depletionChance, int respawnTicks) {
        return xp * (chancePercent / 100.0) / (2.0 + distance * .65 + depletionChance * respawnTicks * .12);
    }

    static int depositAmount(int stack, int owned, int protectedAmount, int currentTotal) {
        return Math.max(0, Math.min(stack, Math.min(owned, currentTotal - protectedAmount)));
    }

    static final class Budget {
        private long progressTick;
        private int failures;

        Budget(long tick) { progressTick = tick; }

        void progress(long tick) {
            progressTick = tick;
            failures = 0;
        }

        boolean retry() { return ++failures < MAX_ROUTE_FAILURES; }

        boolean expired(long tick) { return tick - progressTick >= NO_PROGRESS_TICKS; }
    }
}
