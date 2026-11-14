package rsps.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Server-independent, Java 8 decision logic. Call only on the world thread.
 * This class NEVER awards XP, spawns items, moves entities, or logs anyone out.
 * Port implementations connect decisions to the fork's normal game actions.
 */
public final class ProgressionBrain {
    public enum State { PLANNING, TRAINING, SETTLING, RESTING, MAXED_IDLE, FAULTED }
    public enum ActivityStatus { RUNNING, COMPLETE, BLOCKED }
    public enum PauseStatus { PENDING, SAFE }
    public enum IdleStyle { STAND, LOOK_AROUND, CHECK_EQUIPMENT }
    public enum RestKind { MICRO, SHORT, LONG }
    private enum AfterPause { PLAN, REST, MAXED }

    public interface Port {
        /** Snapshot of permanent levels/XP, never temporary boosts/drains. */
        View observe();
        /** Small cached catalogue of currently viable activities, NOT a world scan. */
        List<Activity> candidates(View view);
        /** Revalidate requirements and ALL possible XP rewards before starting. */
        boolean start(Activity activity, long tick);
        /** Advance/observe the existing activity with a bounded amount of work. */
        ActivityStatus poll(long tick);
        /**
         * Idempotently stop XP-producing jobs, finish/leave combat and reach a
         * configured safe position. SAFE means all of those are confirmed.
         * This also owns ordinary death/recovery handling. Never just freeze.
         */
        PauseStatus pauseSafely(long tick);
        /** Optional cheap cosmetic action: no XP, items, pathfinding or trade. */
        void idle(IdleStyle style, long tick);
        /** Queue short telemetry; do not perform blocking I/O here. */
        void onEvent(String actorId, String event);
        /**
         * Cancel/quarantine underlying jobs and alert an administrator.
         * The brain is parked after a fault; cleanup is the adapter's duty.
         */
        void onFault(String actorId, RuntimeException error);
    }

    public static final class Skill {
        public final int baseLevel;
        public final double xp;
        public Skill(int baseLevel, double xp) {
            if (baseLevel < 1 || !Double.isFinite(xp) || xp < 0) {
                throw new IllegalArgumentException("Invalid permanent skill progress");
            }
            this.baseLevel = baseLevel;
            this.xp = xp;
        }
    }

    public static final class View {
        public final Map<String, Skill> skills;
        /** Changes for genuine route, supply, action or XP progress. */
        public final long progressMarker;
        /** Alive, outside danger, stationary, and no rewarding action remains. */
        public final boolean safeToIdle;
        /** E.g. death, critical health or a missing actor; not ordinary combat. */
        public final boolean needsRecovery;
        public View(Map<String, Skill> skills, long progressMarker,
                    boolean safeToIdle, boolean needsRecovery) {
            if (skills == null || skills.isEmpty() || skills.containsValue(null)) {
                throw new IllegalArgumentException("A complete skill snapshot is required");
            }
            for (String id : skills.keySet()) requireId(id);
            this.skills = Collections.unmodifiableMap(new LinkedHashMap<String, Skill>(skills));
            this.progressMarker = progressMarker;
            this.safeToIdle = safeToIdle;
            this.needsRecovery = needsRecovery;
        }
    }

    public static final class Activity {
        public final String id;
        public final String primarySkill;
        public final Set<String> possibleXpSkills;
        /** Positive relative suitability among methods of the SAME skill. */
        public final double suitability;
        /** Includes preparation/travel. Use a suitable bound for slow activities. */
        public final long noXpTimeoutTicks;
        public Activity(String id, String primarySkill, Set<String> possibleXpSkills,
                        double suitability, long noXpTimeoutTicks) {
            requireId(id);
            requireId(primarySkill);
            if (possibleXpSkills == null || possibleXpSkills.isEmpty()
                    || !possibleXpSkills.contains(primarySkill)
                    || !Double.isFinite(suitability) || suitability <= 0
                    || suitability > 1000000 || noXpTimeoutTicks <= 0) {
                throw new IllegalArgumentException("Invalid activity metadata");
            }
            for (String skill : possibleXpSkills) requireId(skill);
            this.id = id;
            this.primarySkill = primarySkill;
            this.possibleXpSkills = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(possibleXpSkills));
            this.suitability = suitability;
            this.noXpTimeoutTicks = noXpTimeoutTicks;
        }
    }

    /** All timings are ticks; their wall-clock meaning comes from the real server. */
    public static final class Range {
        public final long min, max;
        public Range(long min, long max) {
            if (min < 1 || max < min || max > 1000000000L) {
                throw new IllegalArgumentException("Invalid bounded tick range");
            }
            this.min = min;
            this.max = max;
        }
        @Override public String toString() { return min + ":" + max; }
    }

    public static final class Config {
        public final Range think, idleCheck, work, microEvery, microRest;
        public final Range shortRest, longRest, focus;
        public final long stuckTicks, retryTicks, pauseWarningTicks;
        public final int sessionsBeforeLongRest, maxCandidates;
        public Config(Range think, Range idleCheck, Range work, Range microEvery,
                      Range microRest, Range shortRest, Range longRest, Range focus,
                      long stuckTicks, long retryTicks, long pauseWarningTicks,
                      int sessionsBeforeLongRest, int maxCandidates) {
            if (think == null || idleCheck == null || work == null || microEvery == null
                    || microRest == null || shortRest == null || longRest == null || focus == null
                    || stuckTicks <= 0 || retryTicks <= 0 || pauseWarningTicks <= 0
                    || sessionsBeforeLongRest <= 0 || maxCandidates <= 0) {
                throw new IllegalArgumentException("Invalid behavior configuration");
            }
            this.think = think; this.idleCheck = idleCheck; this.work = work;
            this.microEvery = microEvery; this.microRest = microRest;
            this.shortRest = shortRest; this.longRest = longRest; this.focus = focus;
            this.stuckTicks = stuckTicks; this.retryTicks = retryTicks;
            this.pauseWarningTicks = pauseWarningTicks;
            this.sessionsBeforeLongRest = sessionsBeforeLongRest;
            this.maxCandidates = maxCandidates;
        }
        /** Authored defaults, not measurements of human behavior. */
        public static Config humanDefaults(long tickMillis) {
            if (tickMillis <= 0 || tickMillis > 60000) throw new IllegalArgumentException("tickMillis");
            return new Config(new Range(2, 5), new Range(8, 15),
                    seconds(20 * 60, 55 * 60, tickMillis),
                    seconds(4 * 60, 12 * 60, tickMillis),
                    seconds(10, 60, tickMillis), seconds(2 * 60, 8 * 60, tickMillis),
                    seconds(60 * 60, 3 * 60 * 60, tickMillis),
                    seconds(8 * 60, 20 * 60, tickMillis),
                    ticks(120, tickMillis), ticks(45, tickMillis), ticks(180, tickMillis), 5, 256);
        }
        /** Accelerated timing ONLY for the executable fake-world example/tests. */
        public static Config demo() {
            return new Config(new Range(1, 2), new Range(1, 2), new Range(24, 45),
                    new Range(7, 14), new Range(2, 4), new Range(4, 8),
                    new Range(14, 20), new Range(8, 16), 20, 8, 40, 3, 256);
        }
        private static Range seconds(long min, long max, long millis) {
            return new Range(ticks(min, millis), ticks(max, millis));
        }
        private static long ticks(long seconds, long millis) {
            return Math.max(1, (seconds * 1000 + millis - 1) / millis);
        }
        String signature() {
            return think + "/" + idleCheck + "/" + work + "/" + microEvery + "/"
                    + microRest + "/" + shortRest + "/" + longRest + "/" + focus + "/"
                    + stuckTicks + "/" + retryTicks + "/" + pauseWarningTicks + "/"
                    + sessionsBeforeLongRest + "/" + maxCandidates;
        }
    }

    private final String id;
    private final Port port;
    private final Config config;
    private final BuildPolicy build;
    private final Map<String, Skill> lockedSkills = new LinkedHashMap<String, Skill>();
    private final Map<String, Long> selections = new LinkedHashMap<String, Long>();
    private final Map<String, Long> cooldowns = new LinkedHashMap<String, Long>();
    private long randomState;
    private State state = State.PLANNING;
    private AfterPause afterPause = AfterPause.PLAN;
    private RestKind restKind = RestKind.MICRO;
    private boolean initialized;
    private long lastStep = -1, sessionEnd, microDue, restEnd, focusEnd;
    private long pendingRestTicks, resumeWorkTicks, pauseStarted, nextPauseWarning;
    private long lastProgressTick, lastXpTick, lastMarker;
    private long nextIdleGesture;
    private int completedSessions;
    private String focusSkill;
    private Activity active;
    private double lastXp;
    private String reason = "new character";
    private boolean preserveRestWork;

    public ProgressionBrain(String id, Port port, Config config, BuildPolicy build, long seed) {
        if (id == null || id.trim().isEmpty() || port == null || config == null || build == null) {
            throw new IllegalArgumentException("Identity, port, config and build are required");
        }
        this.id = id; this.port = port; this.config = config; this.build = build;
        this.randomState = seed;
        for (String skill : build.targets.keySet()) selections.put(skill, 0L);
    }

    public String id() { return id; }
    public State state() { return state; }
    public BuildPolicy build() { return build; }
    public String reason() { return reason; }
    public String focusSkill() { return focusSkill; }
    public long sessionEndTick() { return sessionEnd; }
    public long restEndTick() { return restEnd; }

    /** Returns a future decision tick, or MAX_VALUE to park a faulted actor. */
    public long step(long now) {
        if (now < 0 || now <= lastStep || now >= Long.MAX_VALUE - 1000000001L) {
            throw new IllegalArgumentException("Brain tick must strictly increase and leave deadline headroom");
        }
        lastStep = now;
        if (state == State.FAULTED) return Long.MAX_VALUE;
        try {
            View view = port.observe();
            validate(view);
            if (!initialized) initialize(view, now);
            verifyLocks(view);

            if (state == State.SETTLING) return settle(now);

            if (state == State.RESTING) {
                if (view.needsRecovery || !view.safeToIdle) {
                    pendingRestTicks = Math.max(0, restEnd - now);
                    preserveRestWork = true;
                    beginPause(AfterPause.REST, "interrupted rest: seek safety", now);
                    return next(now, config.think);
                }
                if (now >= restEnd) {
                    if (restKind == RestKind.MICRO) sessionEnd = plus(now, resumeWorkTicks);
                    else sessionEnd = plus(now, sample(config.work));
                    microDue = plus(now, sample(config.microEvery));
                    state = State.PLANNING;
                    reason = "rest complete";
                    event("resumed " + restKind.name().toLowerCase() + " rest");
                } else {
                    gesture(now);
                    return Math.min(restEnd, next(now, config.idleCheck));
                }
            }

            if (state == State.MAXED_IDLE) {
                if (view.needsRecovery || !view.safeToIdle) {
                    beginPause(AfterPause.MAXED, "maxed character needs safety", now);
                    return next(now, config.think);
                }
                if (!allTargetsMet(view)) {
                    beginPause(AfterPause.PLAN, "target no longer met", now);
                    return next(now, config.think);
                }
                gesture(now);
                return next(now, config.idleCheck);
            }

            if (view.needsRecovery) {
                beginPause(AfterPause.PLAN, "character needs recovery", now);
                return next(now, config.think);
            }
            if (allTargetsMet(view)) {
                beginPause(AfterPause.MAXED, "all build targets met", now);
                return next(now, config.think);
            }
            if (now >= sessionEnd) {
                completedSessions++;
                restKind = completedSessions >= config.sessionsBeforeLongRest ? RestKind.LONG : RestKind.SHORT;
                if (restKind == RestKind.LONG) completedSessions = 0;
                pendingRestTicks = sample(restKind == RestKind.LONG ? config.longRest : config.shortRest);
                beginPause(AfterPause.REST, "work session complete", now);
                return next(now, config.think);
            }
            if (now >= microDue) {
                restKind = RestKind.MICRO;
                pendingRestTicks = sample(config.microRest);
                beginPause(AfterPause.REST, "short attention break", now);
                return next(now, config.think);
            }

            if (state == State.TRAINING) return train(view, now);
            return plan(view, now);
        } catch (RuntimeException failure) {
            state = State.FAULTED;
            reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            try { port.onFault(id, failure); } catch (RuntimeException ignored) { /* quarantined */ }
            return Long.MAX_VALUE;
        }
    }

    private long train(View view, long now) {
        if (view.progressMarker != lastMarker) {
            lastMarker = view.progressMarker;
            lastProgressTick = now;
        }
        double xp = view.skills.get(active.primarySkill).xp;
        if (xp > lastXp) { lastXp = xp; lastXpTick = now; }
        if (view.skills.get(active.primarySkill).baseLevel >= build.targets.get(active.primarySkill)
                || now >= focusEnd) {
            focusSkill = null;
            beginPause(AfterPause.PLAN, "goal reached or focus period complete", now);
            return next(now, config.think);
        }
        if (now - lastProgressTick >= config.stuckTicks || now - lastXpTick >= active.noXpTimeoutTicks) {
            cooldowns.put(active.id, plus(now, config.retryTicks));
            focusSkill = null;
            beginPause(AfterPause.PLAN, "activity stalled: " + active.id, now);
            return next(now, config.think);
        }
        ActivityStatus status = port.poll(now);
        if (status == null) throw new IllegalStateException("Missing activity status");
        if (status != ActivityStatus.RUNNING) {
            if (status == ActivityStatus.BLOCKED) {
                cooldowns.put(active.id, plus(now, config.retryTicks));
                focusSkill = null;
            }
            beginPause(AfterPause.PLAN, "activity " + status.name().toLowerCase(), now);
        }
        return next(now, config.think);
    }

    private long plan(View view, long now) {
        // Do not start over an untracked activity (for example after a restart).
        if (!view.safeToIdle) {
            beginPause(AfterPause.PLAN, "reconcile existing world action", now);
            return next(now, config.think);
        }
        List<Activity> offered = port.candidates(view);
        if (offered == null || offered.size() > config.maxCandidates) {
            throw new IllegalStateException("Invalid or unbounded activity catalogue");
        }
        cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
        Map<String, List<Activity>> grouped = new LinkedHashMap<String, List<Activity>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Activity activity : offered) {
            if (activity == null || !seen.add(activity.id)) throw new IllegalStateException("Null/duplicate activity");
            Integer target = build.targets.get(activity.primarySkill);
            if (target == null || !build.permits(activity.possibleXpSkills)
                    || view.skills.get(activity.primarySkill).baseLevel >= target
                    || cooldowns.containsKey(activity.id)) continue;
            if (!grouped.containsKey(activity.primarySkill)) grouped.put(activity.primarySkill, new ArrayList<Activity>());
            grouped.get(activity.primarySkill).add(activity);
        }
        if (grouped.isEmpty()) {
            reason = "blocked: no eligible method for remaining build targets";
            event(reason);
            return plus(now, config.retryTicks);
        }
        if (focusSkill == null || now >= focusEnd || !grouped.containsKey(focusSkill)) {
            // Bounded preference: eligible neglected skills cannot lose forever.
            long least = Long.MAX_VALUE;
            for (String skill : grouped.keySet()) least = Math.min(least, selections.get(skill));
            Map<String, Double> weights = new LinkedHashMap<String, Double>();
            for (String skill : grouped.keySet()) {
                if (selections.get(skill) - least > 2) continue;
                int target = build.targets.get(skill);
                double remaining = (double)(target - view.skills.get(skill).baseLevel) / target;
                weights.put(skill, build.preference(skill) * (0.5 + remaining));
            }
            focusSkill = weightedSkill(weights);
            selections.put(focusSkill, Math.addExact(selections.get(focusSkill), 1L));
            focusEnd = plus(now, sample(config.focus));
        }
        List<Activity> methods = grouped.get(focusSkill);
        double total = 0;
        for (Activity method : methods) total += method.suitability;
        double roll = unit() * total;
        Activity chosen = methods.get(methods.size() - 1);
        for (Activity method : methods) {
            roll -= method.suitability;
            if (roll < 0) { chosen = method; break; }
        }
        if (!port.start(chosen, now)) {
            cooldowns.put(chosen.id, plus(now, config.retryTicks));
            beginPause(AfterPause.PLAN, "activity start rejected: " + chosen.id, now);
            return next(now, config.think);
        }
        active = chosen;
        lastMarker = view.progressMarker;
        lastProgressTick = lastXpTick = now;
        lastXp = view.skills.get(chosen.primarySkill).xp;
        state = State.TRAINING;
        reason = "training " + chosen.primarySkill;
        event("started " + chosen.id);
        return next(now, config.think);
    }

    private void beginPause(AfterPause after, String why, long now) {
        state = State.SETTLING;
        afterPause = after;
        reason = why;
        pauseStarted = now;
        nextPauseWarning = plus(now, config.pauseWarningTicks);
        event("settling: " + why);
    }

    private long settle(long now) {
        PauseStatus status = port.pauseSafely(now);
        if (status == null) throw new IllegalStateException("Missing pause status");
        if (status == PauseStatus.PENDING) {
            if (now >= nextPauseWarning) {
                event("waiting for safe stop for " + (now - pauseStarted) + " ticks: " + reason);
                nextPauseWarning = plus(now, config.pauseWarningTicks);
            }
            return next(now, config.think);
        }
        // Double-check the contract after the adapter's commands/cancellation.
        View confirmed = port.observe();
        validate(confirmed);
        verifyLocks(confirmed);
        if (!confirmed.safeToIdle || confirmed.needsRecovery) {
            throw new IllegalStateException("Adapter claimed SAFE while activity or danger remained");
        }
        active = null;
        if (afterPause == AfterPause.REST) {
            if (!preserveRestWork) resumeWorkTicks = Math.max(0, sessionEnd - now);
            preserveRestWork = false;
            restEnd = plus(now, pendingRestTicks);
            state = State.RESTING;
            nextIdleGesture = plus(now, sample(config.idleCheck) * 4);
            reason = restKind.name().toLowerCase() + " online rest";
            event(reason + " for " + pendingRestTicks + " ticks");
        } else if (afterPause == AfterPause.MAXED && allTargetsMet(confirmed)) {
            state = State.MAXED_IDLE;
            reason = "all build targets met; remaining online";
            event(reason);
        } else {
            state = State.PLANNING;
        }
        return next(now, config.think);
    }

    private void gesture(long now) {
        if (now >= nextIdleGesture) {
            IdleStyle[] styles = IdleStyle.values();
            port.idle(styles[(int)(unit() * styles.length)], now);
            nextIdleGesture = plus(now, sample(config.idleCheck) * 4);
        }
    }

    private void initialize(View view, long now) {
        for (String skill : build.forbiddenXpSkills) lockedSkills.put(skill, view.skills.get(skill));
        initialized = true;
        sessionEnd = plus(now, sample(config.work));
        microDue = plus(now, sample(config.microEvery));
        nextIdleGesture = now;
        event("created " + build.archetype + "/" + build.combatStyle);
    }

    private void validate(View view) {
        if (view == null) throw new IllegalStateException("Missing actor snapshot");
        for (String skill : build.targets.keySet()) {
            if (!view.skills.containsKey(skill)) throw new IllegalStateException("Missing required skill: " + skill);
        }
        for (String skill : build.forbiddenXpSkills) {
            if (!view.skills.containsKey(skill)) throw new IllegalStateException("Missing locked skill: " + skill);
        }
    }

    private void verifyLocks(View view) {
        for (Map.Entry<String, Skill> entry : lockedSkills.entrySet()) {
            Skill actual = view.skills.get(entry.getKey());
            Skill baseline = entry.getValue();
            if (actual.baseLevel != baseline.baseLevel || Double.compare(actual.xp, baseline.xp) != 0) {
                throw new IllegalStateException("Forbidden permanent skill change: " + entry.getKey());
            }
        }
    }

    private boolean allTargetsMet(View view) {
        for (Map.Entry<String, Integer> target : build.targets.entrySet()) {
            if (view.skills.get(target.getKey()).baseLevel < target.getValue()) return false;
        }
        return true;
    }

    private String weightedSkill(Map<String, Double> weights) {
        double total = 0;
        for (double weight : weights.values()) total += weight;
        double roll = unit() * total;
        String last = null;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            last = entry.getKey();
            roll -= entry.getValue();
            if (roll < 0) return last;
        }
        if (last == null) throw new IllegalStateException("No eligible weighted goal");
        return last;
    }

    /**
     * Capture on the world thread; write the detached Properties off-thread.
     * Skills, equipment, inventory and appearance belong in normal player saves.
     * Timers use remaining ticks: downtime grants no XP and does not consume rest.
     */
    public Properties checkpoint(long now) {
        if (!initialized || now < lastStep) throw new IllegalStateException("Checkpoint after initialization on current world tick");
        Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("id", id);
        p.setProperty("build", buildSignature()); p.setProperty("config", config.signature());
        p.setProperty("random", Long.toString(randomState));
        p.setProperty("state", state.name()); p.setProperty("after", afterPause.name());
        p.setProperty("restKind", restKind.name()); p.setProperty("reason", reason);
        p.setProperty("sessions", Integer.toString(completedSessions));
        boolean preserved = state == State.RESTING || (state == State.SETTLING && preserveRestWork);
        put(p, "work", preserved ? resumeWorkTicks : Math.max(0, sessionEnd - now));
        put(p, "micro", Math.max(0, microDue - now));
        put(p, "rest", state == State.RESTING ? Math.max(0, restEnd - now) : pendingRestTicks);
        put(p, "focus", Math.max(0, focusEnd - now));
        p.setProperty("focusSkill", focusSkill == null ? "" : focusSkill);
        for (Map.Entry<String, Long> entry : selections.entrySet()) put(p, "selected." + entry.getKey(), entry.getValue());
        for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
            if (entry.getValue() > now) put(p, "cooldown." + entry.getKey(), entry.getValue() - now);
        }
        for (Map.Entry<String, Skill> entry : lockedSkills.entrySet()) {
            p.setProperty("lockLevel." + entry.getKey(), Integer.toString(entry.getValue().baseLevel));
            p.setProperty("lockXp." + entry.getKey(), Double.toString(entry.getValue().xp));
        }
        return p;
    }

    /** Restore into a NEW brain with the same identity/build/config before scheduling. */
    public void restore(Properties p, long now) {
        if (initialized || lastStep >= 0 || now < 0 || p == null) throw new IllegalStateException("Restore requires a new brain");
        if (!"1".equals(p.getProperty("version")) || !id.equals(p.getProperty("id"))
                || !buildSignature().equals(p.getProperty("build"))
                || !config.signature().equals(p.getProperty("config"))) {
            throw new IllegalArgumentException("Checkpoint identity/configuration mismatch; migrate explicitly");
        }
        State saved = State.valueOf(required(p, "state"));
        AfterPause savedAfter = AfterPause.valueOf(required(p, "after"));
        restKind = RestKind.valueOf(required(p, "restKind"));
        randomState = Long.parseLong(required(p, "random"));
        completedSessions = Integer.parseInt(required(p, "sessions"));
        if (completedSessions < 0 || completedSessions >= config.sessionsBeforeLongRest) throw new IllegalArgumentException("Invalid session count");
        sessionEnd = plus(now, number(p, "work"));
        microDue = plus(now, number(p, "micro"));
        focusEnd = plus(now, number(p, "focus"));
        focusSkill = required(p, "focusSkill");
        if (focusSkill.isEmpty()) focusSkill = null;
        if (focusSkill != null && !build.targets.containsKey(focusSkill)) throw new IllegalArgumentException("Unknown saved goal");
        for (String skill : selections.keySet()) selections.put(skill, number(p, "selected." + skill));
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("cooldown.")) {
                String activityId = key.substring("cooldown.".length());
                requireId(activityId);
                if (cooldowns.size() >= config.maxCandidates) throw new IllegalArgumentException("Too many saved cooldowns");
                cooldowns.put(activityId, plus(now, number(p, key)));
            }
        }
        for (String skill : build.forbiddenXpSkills) {
            lockedSkills.put(skill, new Skill(Integer.parseInt(required(p, "lockLevel." + skill)),
                    Double.parseDouble(required(p, "lockXp." + skill))));
        }
        initialized = true;
        reason = "restored; reconcile world before acting";
        if (saved == State.FAULTED) {
            state = State.FAULTED;
            reason = required(p, "reason");
            return;
        }
        // Never restore running jobs or issue offline rewards.
        state = State.SETTLING;
        afterPause = saved == State.MAXED_IDLE || (saved == State.SETTLING && savedAfter == AfterPause.MAXED)
                ? AfterPause.MAXED : AfterPause.PLAN;
        if (saved == State.RESTING || (saved == State.SETTLING && savedAfter == AfterPause.REST)) {
            afterPause = AfterPause.REST;
            pendingRestTicks = number(p, "rest");
            resumeWorkTicks = number(p, "work");
            preserveRestWork = true;
        }
        pauseStarted = now;
        nextPauseWarning = plus(now, config.pauseWarningTicks);
    }

    private String buildSignature() {
        return build.archetype + "/" + build.combatStyle + "/" + new TreeMap<String, Integer>(build.targets)
                + "/" + new TreeMap<String, Double>(build.preferences) + "/" + new TreeSet<String>(build.forbiddenXpSkills);
    }
    private void event(String message) { port.onEvent(id, message); }
    private long next(long now, Range delay) { return plus(now, sample(delay)); }
    private long sample(Range range) { return range.min + (long)(unit() * (range.max - range.min + 1)); }
    /** SplitMix64: deterministic per-actor state, not for security. */
    private double unit() {
        long z = (randomState += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Use a canonical lowercase ID: " + id);
    }
    private static long plus(long now, long duration) {
        if (duration < 0) throw new IllegalArgumentException("Negative duration");
        return Math.addExact(now, duration);
    }
    private static void put(Properties p, String key, long value) { p.setProperty(key, Long.toString(value)); }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing checkpoint key: " + key);
        return value;
    }
    private static long number(Properties p, String key) {
        long value = Long.parseLong(required(p, key));
        if (value < 0 || value > 1000000000000L) throw new IllegalArgumentException("Invalid checkpoint value: " + key);
        return value;
    }
}
