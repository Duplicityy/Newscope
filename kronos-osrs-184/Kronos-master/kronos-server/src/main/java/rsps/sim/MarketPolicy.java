package rsps.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Population-wide admission policy for simulated characters' surplus sell offers.
 * Java 8, no server dependencies, no inventory/gold mutation and no price invention.
 *
 * Construct ONE instance for the whole simulated population. On startup reconcile
 * its durable ledger with the real exchange before calling reconcile(). An empty
 * collection is legitimate only after confirming no previous admissions/orders.
 * Persist a successful reservation BEFORE submitting an idempotent exchange order.
 * PENDING means uncertain orders consume every budget until the exchange resolves
 * them. Never interpret a timeout as a confirmed rejection.
 *
 * Methods are synchronized to make admission atomic, but the real inventory and
 * exchange transaction must still run in the server's authoritative context. Lock
 * or escrow the admitted items atomically; revalidate ownership, reserve amounts,
 * tradability and price limits immediately before submission. An accepted offer's
 * items must leave the unescrowed ownership count before confirmAccepted() returns.
 *
 * This class cannot discover real human demand, prevent an exchange from matching
 * bots with bots, or guarantee economic stability. The adapter must supply honest
 * market data, block bot-to-bot/self trades, enforce authorization and handle fees.
 * The example limits are game-design defaults for a 25-bot starting population in
 * an assumed 500-player economy, not measured or calibrated economic parameters.
 */
public final class MarketPolicy {
    public static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    public enum Status { PENDING, OPEN, CLOSED, REJECTED }

    public static final class Config {
        /** 200 basis points means at most 2% of reported human 24-hour volume. */
        public final int demandShareBasisPoints;
        public final int globalActiveSlots;
        public final int perActorActiveSlots;
        public final long globalExposureCoins;
        public final long maxMarketDataAgeMillis;
        public final int maxLedgerRecords;

        public Config(int demandShareBasisPoints, int globalActiveSlots,
                      int perActorActiveSlots, long globalExposureCoins,
                      long maxMarketDataAgeMillis, int maxLedgerRecords) {
            if (demandShareBasisPoints < 1 || demandShareBasisPoints > 1000)
                throw new IllegalArgumentException("Demand share must be 1..1000 basis points");
            if (globalActiveSlots < 1 || perActorActiveSlots < 1
                    || perActorActiveSlots > globalActiveSlots
                    || globalExposureCoins < 1 || maxMarketDataAgeMillis < 1
                    || maxLedgerRecords < globalActiveSlots)
                throw new IllegalArgumentException("Invalid market limits");
            this.demandShareBasisPoints = demandShareBasisPoints;
            this.globalActiveSlots = globalActiveSlots;
            this.perActorActiveSlots = perActorActiveSlots;
            this.globalExposureCoins = globalExposureCoins;
            this.maxMarketDataAgeMillis = maxMarketDataAgeMillis;
            this.maxLedgerRecords = maxLedgerRecords;
        }

        public static Config conservativeExample() {
            return new Config(200, 8, 1, 1_000_000L, 10L * 60L * 1000L, 100_000);
        }
    }

    /** No implicit whitelist: the server operator explicitly registers each item. */
    public static final class ItemRule {
        public final long maxListedUnitsPer24Hours;
        public final long maxOpenUnits;
        public final long maxUnitsPerListing;

        public ItemRule(long maxListedUnitsPer24Hours, long maxOpenUnits,
                        long maxUnitsPerListing) {
            if (maxListedUnitsPer24Hours < 1 || maxOpenUnits < 1
                    || maxUnitsPerListing < 1 || maxUnitsPerListing > Integer.MAX_VALUE
                    || maxUnitsPerListing > maxOpenUnits
                    || maxUnitsPerListing > maxListedUnitsPer24Hours)
                throw new IllegalArgumentException("Invalid item limits");
            this.maxListedUnitsPer24Hours = maxListedUnitsPer24Hours;
            this.maxOpenUnits = maxOpenUnits;
            this.maxUnitsPerListing = maxUnitsPerListing;
        }
    }

    public static final class SaleRequest {
        public final String requestId;
        public final String actorId;
        public final String itemId;
        /** Authoritative inventory + bank total, excluding already escrowed offers. */
        public final long ownedUnescrowedQuantity;
        /** Proven earned portion of that same total; no purchases or borrowed items. */
        public final long earnedUnescrowedQuantity;
        /** Gear, consumables, future training/build needs; always retain at least one. */
        public final long keepQuantity;
        public final boolean tradable;
        public final boolean protectedItem;

        public SaleRequest(String requestId, String actorId, String itemId,
                           long ownedUnescrowedQuantity, long earnedUnescrowedQuantity,
                           long keepQuantity, boolean tradable, boolean protectedItem) {
            this.requestId = id(requestId);
            this.actorId = id(actorId);
            this.itemId = id(itemId);
            if (ownedUnescrowedQuantity < 0 || earnedUnescrowedQuantity < 0
                    || earnedUnescrowedQuantity > ownedUnescrowedQuantity
                    || keepQuantity < 0)
                throw new IllegalArgumentException("Invalid inventory quantities");
            this.ownedUnescrowedQuantity = ownedUnescrowedQuantity;
            this.earnedUnescrowedQuantity = earnedUnescrowedQuantity;
            this.keepQuantity = keepQuantity;
            this.tradable = tradable;
            this.protectedItem = protectedItem;
        }
    }

    public static final class MarketSnapshot {
        public final String itemId;
        public final long humanCompletedUnitsLast24Hours;
        public final long trustedReferenceUnitPrice;
        public final long asOfMillis;
        /** False for unavailable data, bot-contaminated volume, or unstable prices. */
        public final boolean trustedHumanOnlyData;

        public MarketSnapshot(String itemId, long humanCompletedUnitsLast24Hours,
                              long trustedReferenceUnitPrice, long asOfMillis,
                              boolean trustedHumanOnlyData) {
            this.itemId = id(itemId);
            if (humanCompletedUnitsLast24Hours < 0 || trustedReferenceUnitPrice < 1
                    || asOfMillis < 0)
                throw new IllegalArgumentException("Invalid market data");
            this.humanCompletedUnitsLast24Hours = humanCompletedUnitsLast24Hours;
            this.trustedReferenceUnitPrice = trustedReferenceUnitPrice;
            this.asOfMillis = asOfMillis;
            this.trustedHumanOnlyData = trustedHumanOnlyData;
        }
    }

    /** Detached serializable-by-the-host receipt. Store all fields durably. */
    public static final class LedgerEntry {
        public final String requestId;
        public final String actorId;
        public final String itemId;
        public final long quantity;
        public final long unitPrice;
        public final long admittedAtMillis;
        public final Status status;

        public LedgerEntry(String requestId, String actorId, String itemId,
                           long quantity, long unitPrice, long admittedAtMillis,
                           Status status) {
            this.requestId = id(requestId);
            this.actorId = id(actorId);
            this.itemId = id(itemId);
            if (quantity < 1 || unitPrice < 1 || admittedAtMillis < 0)
                throw new IllegalArgumentException("Invalid admission receipt");
            Math.multiplyExact(quantity, unitPrice);
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.admittedAtMillis = admittedAtMillis;
            this.status = Objects.requireNonNull(status, "status");
        }
    }

    public static final class Decision {
        /** True only for a NEW reservation; known request IDs must not be resubmitted. */
        public final boolean approved;
        public final String reason;
        public final LedgerEntry entry;

        private Decision(boolean approved, String reason, LedgerEntry entry) {
            this.approved = approved;
            this.reason = reason;
            this.entry = entry;
        }

        private static Decision deny(String reason) {
            return new Decision(false, reason, null);
        }
    }

    private static final class Record {
        private LedgerEntry entry;
        private boolean charged;
        private Record(LedgerEntry entry) { this.entry = entry; }
        private void status(Status status) {
            entry = new LedgerEntry(entry.requestId, entry.actorId, entry.itemId,
                    entry.quantity, entry.unitPrice, entry.admittedAtMillis, status);
        }
    }

    private final Config config;
    private final Map<String, ItemRule> whitelist;
    private final Map<String, Record> records = new LinkedHashMap<String, Record>();
    private final Map<String, Long> rollingUnits = new HashMap<String, Long>();
    private final Map<String, Long> openUnits = new HashMap<String, Long>();
    private final Map<String, Long> actorSlots = new HashMap<String, Long>();
    private final Map<String, Long> pendingUnits = new HashMap<String, Long>();
    private final Deque<Record> quotaExpiry = new ArrayDeque<Record>();
    private int activeSlots;
    private long activeExposure;
    private long lastTime = -1;
    private boolean reconciled;

    public MarketPolicy(Config config, Map<String, ItemRule> whitelist) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(whitelist, "whitelist");
        Map<String, ItemRule> copy = new LinkedHashMap<String, ItemRule>();
        for (Map.Entry<String, ItemRule> e : whitelist.entrySet())
            copy.put(id(e.getKey()), Objects.requireNonNull(e.getValue(), "item rule"));
        this.whitelist = Collections.unmodifiableMap(copy);
    }

    /**
     * Startup only. Include ALL still-open/uncertain offers, recent closed offers,
     * and retained request-ID tombstones. Reconcile against exchange truth first.
     * Existing exposure can exceed newly lowered limits: it is counted faithfully
     * and new admissions stop. A malformed restore leaves the policy inactive.
     */
    public synchronized void reconcile(Collection<LedgerEntry> durableLedger, long nowMillis) {
        if (reconciled) throw new IllegalStateException("Reconciliation is startup-only");
        Objects.requireNonNull(durableLedger, "durableLedger");
        if (nowMillis < 0 || durableLedger.size() > config.maxLedgerRecords)
            throw new IllegalArgumentException("Invalid restore time or ledger size");
        clear();
        try {
            List<LedgerEntry> ordered = new ArrayList<LedgerEntry>(durableLedger);
            Collections.sort(ordered, new Comparator<LedgerEntry>() {
                public int compare(LedgerEntry a, LedgerEntry b) {
                    return Long.compare(a.admittedAtMillis, b.admittedAtMillis);
                }
            });
            for (LedgerEntry entry : ordered) {
                Objects.requireNonNull(entry, "ledger entry");
                if (entry.admittedAtMillis > nowMillis || records.containsKey(entry.requestId))
                    throw new IllegalArgumentException("Future or duplicate ledger receipt");
                Record r = new Record(entry);
                records.put(entry.requestId, r);
                if (entry.status != Status.REJECTED
                        && nowMillis - entry.admittedAtMillis < DAY_MILLIS) charge(r);
                if (isActive(entry.status)) addActive(r);
            }
            lastTime = nowMillis;
            reconciled = true;
        } catch (RuntimeException failure) {
            clear();
            throw failure;
        }
    }

    /** Sparse sale-planning call, not a per-character/per-tick world scan. */
    public synchronized Decision reserve(SaleRequest request, MarketSnapshot market,
                                         long nowMillis) {
        Objects.requireNonNull(request, "request");
        if (!reconciled) return Decision.deny("Market ledger must be reconciled first");
        advance(nowMillis);
        Record existing = records.get(request.requestId);
        if (existing != null) {
            if (!existing.entry.actorId.equals(request.actorId)
                    || !existing.entry.itemId.equals(request.itemId))
                throw new IllegalArgumentException("Request ID belongs to another sale");
            return new Decision(false, "Known request: inspect its receipt; do not submit again",
                    existing.entry);
        }
        ItemRule rule = whitelist.get(request.itemId);
        if (rule == null) return Decision.deny("Item is not explicitly permitted for bot sales");
        if (!request.tradable || request.protectedItem)
            return Decision.deny("Item is untradeable or protected for this character");
        long keep = Math.max(1L, request.keepQuantity);
        if (request.ownedUnescrowedQuantity <= keep)
            return Decision.deny("No surplus duplicate after retained supplies");
        long surplus = Math.min(request.ownedUnescrowedQuantity - keep,
                request.earnedUnescrowedQuantity);
        surplus -= value(pendingUnits, pair(request.actorId, request.itemId));
        if (surplus < 1) return Decision.deny("No unreserved, proven-earned surplus");
        if (market == null || !market.itemId.equals(request.itemId)
                || !market.trustedHumanOnlyData)
            return Decision.deny("Fresh trustworthy human-only market data is required");
        if (market.asOfMillis > nowMillis
                || nowMillis - market.asOfMillis > config.maxMarketDataAgeMillis)
            return Decision.deny("Market data is stale or dated in the future");
        if (records.size() >= config.maxLedgerRecords)
            return Decision.deny("Ledger capacity reached; durable retention maintenance required");
        if (activeSlots >= config.globalActiveSlots
                || value(actorSlots, request.actorId) >= config.perActorActiveSlots)
            return Decision.deny("Population or character offer slots are full");

        long demandCap = fraction(market.humanCompletedUnitsLast24Hours,
                config.demandShareBasisPoints);
        long dailyCap = Math.min(rule.maxListedUnitsPer24Hours, demandCap);
        long dailyRemaining = dailyCap - value(rollingUnits, request.itemId);
        long openRemaining = rule.maxOpenUnits - value(openUnits, request.itemId);
        long coinRoom = config.globalExposureCoins - activeExposure;
        if (dailyRemaining < 1 || openRemaining < 1)
            return Decision.deny("Shared item supply quota is exhausted or demand is too small");
        if (coinRoom < market.trustedReferenceUnitPrice)
            return Decision.deny("Shared outstanding coin exposure limit is exhausted");
        long quantity = Math.min(surplus, Math.min(rule.maxUnitsPerListing,
                Math.min(dailyRemaining, Math.min(openRemaining,
                        coinRoom / market.trustedReferenceUnitPrice))));
        LedgerEntry entry = new LedgerEntry(request.requestId, request.actorId,
                request.itemId, quantity, market.trustedReferenceUnitPrice, nowMillis,
                Status.PENDING);
        Record r = new Record(entry);
        records.put(entry.requestId, r);
        charge(r);
        addActive(r);
        return new Decision(true, "Reserved; persist receipt and escrow items before submission", entry);
    }

    /** Call only once the real exchange confirms acceptance and escrow. Idempotent. */
    public synchronized void confirmAccepted(String requestId, long nowMillis) {
        advance(nowMillis);
        Record r = known(requestId);
        if (r.entry.status == Status.OPEN || r.entry.status == Status.CLOSED) return;
        if (r.entry.status != Status.PENDING)
            throw new IllegalStateException("A rejected receipt cannot become accepted");
        add(pendingUnits, pair(r.entry.actorId, r.entry.itemId), -r.entry.quantity);
        r.status(Status.OPEN);
    }

    /**
     * Release ONLY after conclusive proof that no order was ever accepted. A timeout
     * is not proof. Accepted/canceled offers may only use close(), which keeps quota.
     */
    public synchronized void confirmRejected(String requestId, long nowMillis) {
        advance(nowMillis);
        Record r = known(requestId);
        if (r.entry.status == Status.REJECTED) return;
        if (r.entry.status != Status.PENDING)
            throw new IllegalStateException("Accepted offers retain their rolling supply charge");
        removeActive(r);
        if (r.charged) {
            add(rollingUnits, r.entry.itemId, -r.entry.quantity);
            r.charged = false;
        }
        r.status(Status.REJECTED);
    }

    /**
     * Sold out or confirmed canceled: free active exposure, KEEP the 24-hour charge.
     * Partial fills conservatively retain the original offer's entire exposure until
     * closure. Do not call until the exchange confirms it is no longer fillable.
     */
    public synchronized void close(String requestId, long nowMillis) {
        advance(nowMillis);
        Record r = known(requestId);
        if (r.entry.status == Status.CLOSED) return;
        if (r.entry.status != Status.OPEN)
            throw new IllegalStateException("Resolve pending offers before closing them");
        removeActive(r);
        r.status(Status.CLOSED);
    }

    /** Immutable receipts in a detached list; no filesystem or database work here. */
    public synchronized List<LedgerEntry> snapshotLedger() {
        List<LedgerEntry> snapshot = new ArrayList<LedgerEntry>(records.size());
        for (Record r : records.values()) snapshot.add(r.entry);
        return Collections.unmodifiableList(snapshot);
    }

    public synchronized int activeListings() { return activeSlots; }
    public synchronized long activeExposureCoins() { return activeExposure; }
    public synchronized long rollingListedUnits(String itemId, long nowMillis) {
        advance(nowMillis);
        return value(rollingUnits, id(itemId));
    }

    private void advance(long now) {
        if (!reconciled) throw new IllegalStateException("Reconcile before market operations");
        if (now < 0 || now < lastTime)
            throw new IllegalArgumentException("Market clock moved backwards; pause and reconcile");
        lastTime = now;
        while (!quotaExpiry.isEmpty()
                && now - quotaExpiry.peekFirst().entry.admittedAtMillis >= DAY_MILLIS) {
            Record old = quotaExpiry.removeFirst();
            if (old.charged) {
                add(rollingUnits, old.entry.itemId, -old.entry.quantity);
                old.charged = false;
            }
        }
    }

    private Record known(String requestId) {
        Record r = records.get(id(requestId));
        if (r == null) throw new IllegalArgumentException("Unknown market request ID");
        return r;
    }

    private void charge(Record r) {
        add(rollingUnits, r.entry.itemId, r.entry.quantity);
        r.charged = true;
        quotaExpiry.addLast(r);
    }

    private void addActive(Record r) {
        LedgerEntry e = r.entry;
        activeSlots = Math.addExact(activeSlots, 1);
        activeExposure = Math.addExact(activeExposure, Math.multiplyExact(e.quantity, e.unitPrice));
        add(actorSlots, e.actorId, 1);
        add(openUnits, e.itemId, e.quantity);
        if (e.status == Status.PENDING) add(pendingUnits, pair(e.actorId, e.itemId), e.quantity);
    }

    private void removeActive(Record r) {
        LedgerEntry e = r.entry;
        activeSlots--;
        activeExposure -= Math.multiplyExact(e.quantity, e.unitPrice);
        add(actorSlots, e.actorId, -1);
        add(openUnits, e.itemId, -e.quantity);
        if (e.status == Status.PENDING) add(pendingUnits, pair(e.actorId, e.itemId), -e.quantity);
    }

    private void clear() {
        records.clear(); rollingUnits.clear(); openUnits.clear(); actorSlots.clear();
        pendingUnits.clear(); quotaExpiry.clear(); activeSlots = 0; activeExposure = 0;
        lastTime = -1; reconciled = false;
    }

    private static boolean isActive(Status status) {
        return status == Status.PENDING || status == Status.OPEN;
    }

    private static String pair(String actorId, String itemId) { return actorId + "\u0000" + itemId; }
    private static long value(Map<String, Long> map, String key) {
        Long v = map.get(key);
        return v == null ? 0L : v.longValue();
    }
    private static void add(Map<String, Long> map, String key, long delta) {
        long result = Math.addExact(value(map, key), delta);
        if (result < 0) throw new IllegalStateException("Negative market ledger balance");
        if (result == 0) map.remove(key); else map.put(key, Long.valueOf(result));
    }
    private static long fraction(long amount, int basisPoints) {
        // Avoid overflow from amount * basisPoints before dividing.
        return (amount / 10000L) * basisPoints + (amount % 10000L) * basisPoints / 10000L;
    }
    private static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Use a stable nonempty ID of at most 128 characters");
        return value;
    }
}
