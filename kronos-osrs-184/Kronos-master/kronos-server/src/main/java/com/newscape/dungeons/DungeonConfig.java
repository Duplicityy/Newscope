package com.newscape.dungeons;

/** Server policy. Durations are game ticks; the normal integration tick is 600 ms. */
public final class DungeonConfig {
    public final int maxInstances, generationTimeoutTicks, buildTimeoutTicks;
    public final int maxRunTicks, disconnectGraceTicks, idleTicks, npcSpawnsPerTick;
    public final int chunksPerTick, maxLiveNpcsPerRun, doorTimeoutTicks, actionCooldownTicks;
    public final int entryCooldownTicks, maxCallbacksPerTick;
    public final boolean enableBossSpecials;

    public DungeonConfig() {
        this(32, 100, 600, 3600, 300, 900, 8, 16, 32, 12, 3, 15, 64, true);
    }

    public DungeonConfig(int maxInstances, int generationTimeoutTicks, int buildTimeoutTicks,
            int maxRunTicks, int disconnectGraceTicks, int idleTicks, int npcSpawnsPerTick,
            int chunksPerTick, int maxLiveNpcsPerRun, int doorTimeoutTicks,
            int actionCooldownTicks, int entryCooldownTicks, int maxCallbacksPerTick,
            boolean enableBossSpecials) {
        int[] positive = {maxInstances, generationTimeoutTicks, buildTimeoutTicks, maxRunTicks,
                disconnectGraceTicks, idleTicks, npcSpawnsPerTick, chunksPerTick,
                maxLiveNpcsPerRun, doorTimeoutTicks, actionCooldownTicks, entryCooldownTicks,
                maxCallbacksPerTick};
        for (int value : positive) if (value < 1) throw new IllegalArgumentException("positive policy values required");
        if (maxInstances > 256 || maxLiveNpcsPerRun > 128 || npcSpawnsPerTick > 128)
            throw new IllegalArgumentException("unsafe population policy");
        this.maxInstances = maxInstances; this.generationTimeoutTicks = generationTimeoutTicks;
        this.buildTimeoutTicks = buildTimeoutTicks; this.maxRunTicks = maxRunTicks;
        this.disconnectGraceTicks = disconnectGraceTicks; this.idleTicks = idleTicks;
        this.npcSpawnsPerTick = npcSpawnsPerTick; this.chunksPerTick = chunksPerTick;
        this.maxLiveNpcsPerRun = maxLiveNpcsPerRun; this.doorTimeoutTicks = doorTimeoutTicks;
        this.actionCooldownTicks = actionCooldownTicks; this.entryCooldownTicks = entryCooldownTicks;
        this.maxCallbacksPerTick = maxCallbacksPerTick; this.enableBossSpecials = enableBossSpecials;
    }
}
