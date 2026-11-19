package local.kronos.mobile;

/** Pure session policy: preserving a connection never fabricates player input. */
final class BackgroundSessionPolicy {
    static final long MAX_BACKGROUND_WAKE_MILLIS = 10 * 60 * 1000L;
    private static volatile boolean visible = true;
    private static volatile long hiddenAt = -1;
    private static long owner;
    static synchronized void visibility(boolean shown, long now) { visibility(shown, now, owner); }
    static synchronized void visibility(boolean shown, long now, long activityOwner) {
        if (activityOwner < owner) return;
        owner = activityOwner;
        if (shown) hiddenAt = -1;
        else if (visible || hiddenAt < 0) hiddenAt = now;
        visible = shown;
    }
    static boolean visible() { return visible; }
    static synchronized boolean owns(long activityOwner) { return owner == activityOwner; }
    static boolean needsWakeLock(String state, long now) {
        return !visible && hiddenAt >= 0 && now >= hiddenAt
                && now - hiddenAt < MAX_BACKGROUND_WAKE_MILLIS
                && ("LOGGED_IN".equals(state) || "LOGGING_IN".equals(state));
    }
    private BackgroundSessionPolicy() {}
}
