package com.newscape.dungeons;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded workers for a live adapter; close only after the service's maintenance drain completes. */
public final class DungeonWorkers implements AutoCloseable {
    private final ThreadPoolExecutor planners, persistence;
    public DungeonWorkers(int queueCapacity) {
        if (queueCapacity < 1 || queueCapacity > 256) throw new IllegalArgumentException("queue capacity 1..256 required");
        planners = pool("dungeon-planner",queueCapacity);
        persistence = pool("dungeon-rewards",queueCapacity);
    }
    public Executor planners() { return planners; }
    public Executor persistence() { return persistence; }
    public int queuedPlans() { return planners.getQueue().size(); }
    public int queuedRewardWrites() { return persistence.getQueue().size(); }
    public boolean isTerminated() { return planners.isTerminated() && persistence.isTerminated(); }
    /** Nonblocking shutdown, never shutdownNow: accepted reward writes must not be silently discarded. */
    @Override public void close() { planners.shutdown(); persistence.shutdown(); }
    private static ThreadPoolExecutor pool(final String name,int capacity) {
        final AtomicInteger count = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread worker = new Thread(task,name + "-" + count.incrementAndGet());
            worker.setDaemon(false); return worker;
        };
        return new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(capacity),factory,new ThreadPoolExecutor.AbortPolicy());
    }
}
