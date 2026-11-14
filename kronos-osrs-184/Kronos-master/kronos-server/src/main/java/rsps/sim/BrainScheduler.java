package rsps.sim;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Bounded, single-world-thread decision scheduling for simulated characters.
 *
 * <p>Call {@link #tick(long)} once per world tick, with strictly increasing
 * nonnegative tick numbers. The scheduler owns no threads and never sleeps.
 * Every brain is registered once and has at most one priority-queue entry.
 * Give different firstDueTick values to {@link #add(ProgressionBrain, long)}
 * to stagger a population's first decisions.</p>
 *
 * <p>The time budget is cooperative: elapsed time is checked between calls to
 * a brain, and at least one due brain is allowed to run. An individual slow
 * brain/adapter can therefore exceed the budget. This is not a hard deadline
 * and does not bound the cost of world updates, pathfinding or combat.</p>
 *
 * <p>A brain returning Long.MAX_VALUE is parked: its registration remains in
 * {@link #size()}, but it consumes no subsequent decisions. {@link #wake}
 * does not revive parked brains. Remove and re-add a repaired/replacement
 * brain to resume one. MAX_VALUE is reserved for this unscheduled state.</p>
 *
 * <p>All calls must be confined to the same world thread. Reentrant tick calls
 * are rejected. Adding/removing/waking other actors during a brain's step is
 * supported; waking the actor currently being stepped is a no-op.</p>
 */
public final class BrainScheduler {
    private static final class Entry {
        final String id;
        final ProgressionBrain brain;
        final long sequence;
        long due;
        long lastSteppedTick = -1L;
        boolean queued;

        Entry(String id, ProgressionBrain brain, long sequence, long due) {
            this.id = id;
            this.brain = brain;
            this.sequence = sequence;
            this.due = due;
        }
    }

    private final int maxPopulation;
    private final int maxDecisionsPerTick;
    private final long maxNanosPerTick;
    private final Map<String, Entry> registered = new HashMap<String, Entry>();
    private final PriorityQueue<Entry> due = new PriorityQueue<Entry>(
            (a, b) -> {
                int order = Long.compare(a.due, b.due);
                return order != 0 ? order : Long.compare(a.sequence, b.sequence);
            });

    private long nextSequence;
    private long lastWorldTick = -1L;
    private long lastTickNanos;
    private boolean ticking;

    /** Starts with the requested population cap of 25 registered actors. */
    public BrainScheduler(int maxDecisionsPerTick, long maxNanosPerTick) {
        this(25, maxDecisionsPerTick, maxNanosPerTick);
    }

    /** Explicit population cap allows a later, deliberate capacity increase. */
    public BrainScheduler(int maxPopulation, int maxDecisionsPerTick, long maxNanosPerTick) {
        if (maxPopulation <= 0 || maxDecisionsPerTick <= 0 || maxNanosPerTick <= 0) {
            throw new IllegalArgumentException("Population, decision and time budgets must be positive");
        }
        this.maxPopulation = maxPopulation;
        this.maxDecisionsPerTick = maxDecisionsPerTick;
        this.maxNanosPerTick = maxNanosPerTick;
    }

    /**
     * Registers a unique, stable actor ID. A due time in the past runs at the
     * next available tick. Long.MAX_VALUE explicitly registers a parked actor.
     */
    public void add(ProgressionBrain brain, long firstDueTick) {
        Objects.requireNonNull(brain, "brain");
        if (firstDueTick < 0) {
            throw new IllegalArgumentException("firstDueTick must be nonnegative");
        }
        String id = brain.id();
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Brain ID must be nonblank");
        }
        if (registered.containsKey(id)) {
            throw new IllegalArgumentException("A brain is already registered as " + id);
        }
        if (registered.size() >= maxPopulation) {
            throw new IllegalStateException("Simulated-player population cap reached: " + maxPopulation);
        }
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Scheduler registration sequence exhausted");
        }
        Entry entry = new Entry(id, brain, nextSequence++, firstDueTick);
        registered.put(id, entry);
        if (firstDueTick != Long.MAX_VALUE) {
            enqueue(entry, firstDueTick);
        }
    }

    /**
     * Removes the actor's registration and pending decision, if present.
     * This administrative/event operation is O(n) because PriorityQueue has
     * no indexed removal; normal due processing remains O(log n) per actor.
     * The caller must separately stop the actor's underlying world activity.
     */
    public void remove(String id) {
        Entry entry = registered.remove(id);
        if (entry != null && entry.queued) {
            due.remove(entry);
            entry.queued = false;
        }
    }

    /**
     * Brings an already scheduled decision forward, for example after damage.
     * Returns true only if its deadline changed. Unknown, parked, currently
     * executing, or already-earlier actors return false. Does not duplicate
     * entries or reset the population budget. An actor already stepped during
     * this tick cannot run again until a later tick. O(n) event operation.
     *
     * <p>When called after tick(now), it is processed on a later tick; calling
     * tick(now) a second time is deliberately forbidden.</p>
     */
    public boolean wake(String id, long now) {
        requireWorldTime(now);
        Entry entry = registered.get(id);
        if (entry == null || !entry.queued) {
            return false;
        }
        // A later actor may wake this actor from a callback in the same tick.
        // Preserve the brain's strictly increasing tick contract in that case.
        long earliest = Math.max(now, entry.lastSteppedTick + 1L);
        if (entry.due <= earliest) {
            return false;
        }
        due.remove(entry);
        entry.queued = false;
        enqueue(entry, earliest);
        return true;
    }

    /**
     * Runs due decisions within the count and cooperative time budgets.
     * Returns the number of brains stepped. Calls with the same or an earlier
     * tick throw, so callers cannot accidentally reset a world's tick budget.
     *
     * <p>ProgressionBrain catches its adapter RuntimeExceptions internally.
     * An unexpected escaping exception, or a brain violating its strictly
     * later-deadline contract, is surfaced to the caller and leaves that brain
     * parked. Timing bookkeeping is still completed in that case.</p>
     */
    public int tick(long now) {
        requireWorldTime(now);
        if (ticking) {
            throw new IllegalStateException("Reentrant scheduler tick");
        }
        if (now <= lastWorldTick) {
            throw new IllegalArgumentException("World ticks must strictly increase");
        }
        lastWorldTick = now;
        ticking = true;
        long started = System.nanoTime();
        int decisions = 0;
        try {
            while (decisions < maxDecisionsPerTick && !due.isEmpty()
                    && due.peek().due <= now) {
                // Let at least one actor progress even with a tiny time budget.
                if (decisions > 0 && System.nanoTime() - started >= maxNanosPerTick) {
                    break;
                }
                Entry entry = due.remove();
                entry.queued = false;
                entry.lastSteppedTick = now;
                long nextDue = entry.brain.step(now);
                decisions++;
                if (nextDue != Long.MAX_VALUE && nextDue <= now) {
                    throw new IllegalStateException(
                            "Brain " + entry.id + " returned a non-future deadline");
                }
                // A callback may have removed/replaced this registration.
                if (registered.get(entry.id) == entry && nextDue != Long.MAX_VALUE) {
                    enqueue(entry, nextDue);
                }
            }
            return decisions;
        } finally {
            lastTickNanos = System.nanoTime() - started;
            ticking = false;
        }
    }

    /** Number registered, including parked actors. */
    public int size() {
        return registered.size();
    }

    /** Maximum registrations, including parked actors. */
    public int maxPopulation() {
        return maxPopulation;
    }

    /**
     * Whether work is still due at the most recently processed world tick.
     * False before the first tick. The result reflects removals and wakes
     * since that tick and does not scan the population.
     */
    public boolean hasBacklog() {
        return lastWorldTick >= 0 && !due.isEmpty() && due.peek().due <= lastWorldTick;
    }

    /** Measured scheduler duration of the last accepted tick call. */
    public long lastTickNanos() {
        return lastTickNanos;
    }

    private void enqueue(Entry entry, long when) {
        entry.due = when;
        entry.queued = true;
        due.add(entry);
    }

    private static void requireWorldTime(long tick) {
        if (tick < 0 || tick == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "World time must be in [0, Long.MAX_VALUE - 1]");
        }
    }
}
