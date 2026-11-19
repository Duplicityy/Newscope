package net.runelite.standalone;

import java.util.concurrent.locks.LockSupport;
import rs117.hd.android.AndroidGl;

/** Decouples render deadlines from the existing simulation period for 117 only. */
public final class AndroidRenderClock {
    private static Clock owner;
    private static Timeline timeline;

    public static int waitCycles(Clock clock, int cycleMillis, int minimumWaitMillis) {
        if (!AndroidGl.active() || !MobileFrame.foreground()) {
            if (timeline != null) {
                clock.vmethod3510();
                timeline = null;
                owner = null;
            }
            return clock.vmethod3511(cycleMillis, minimumWaitMillis);
        }
        if (owner != clock || timeline == null) {
            timeline = new Timeline(System.nanoTime());
            owner = clock;
        }
        long now = System.nanoTime();
        while (now < timeline.nextFrame && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(timeline.nextFrame - now);
            now = System.nanoTime();
        }
        return timeline.advance(now, cycleMillis * 1000000L, 1000000000L / AndroidGl.targetFps());
    }

    static final class Timeline {
        long nextTick;
        long nextFrame;
        Timeline(long now) { nextTick = nextFrame = now; }
        int advance(long now, long tickPeriod, long framePeriod) {
            int cycles = 0;
            while (nextTick <= now && cycles < 10) {
                nextTick += tickPeriod;
                cycles++;
            }
            // Match the native clock's bounded catch-up after long suspensions.
            if (nextTick < now) nextTick = now;
            nextFrame = Math.max(nextFrame + framePeriod, now);
            return cycles;
        }
    }
    private AndroidRenderClock() {}
}
