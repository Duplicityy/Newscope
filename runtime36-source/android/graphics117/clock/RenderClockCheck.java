package net.runelite.standalone;

public final class RenderClockCheck {
    public static void main(String[] args) {
        for (int fps : new int[]{30, 60, 90, 120, 240}) {
            AndroidRenderClock.Timeline timeline = new AndroidRenderClock.Timeline(0);
            int cycles = 0;
            long now = 0;
            for (int frame = 0; frame < fps * 60; frame++) {
                now = frame * 1000000000L / fps;
                cycles += timeline.advance(now, 20000000L, 1000000000L / fps);
            }
            int expected = (int)(now / 20000000L) + 1;
            if (cycles != expected) throw new AssertionError(fps + " fps changed simulation cycles " + cycles);
            if (timeline.advance(now + 100000000000L, 20000000L, 1000000000L / fps) != 10)
                throw new AssertionError("unbounded catch-up");
        }
        System.out.println("PASS independent render scheduling at 30/60/90/120/240 FPS with native 20ms ticks and bounded catch-up");
    }
}
