package io.ruin.model.activities.automation;

/** One owner-controlled activity, pulsed on the server worker. */
interface AutomationDriver {
    String activity();
    String pulse(long tick);
    void stop();
    default boolean ownsNativeLock() { return false; }
    default boolean ownsNativeTravel() { return false; }
    default int remainingBossKills() { return 0; }
}
