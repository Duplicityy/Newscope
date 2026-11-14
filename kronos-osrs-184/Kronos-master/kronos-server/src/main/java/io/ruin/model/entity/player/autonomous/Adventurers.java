package io.ruin.model.entity.player.autonomous;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.api.utils.JsonUtils;
import io.ruin.model.World;
import io.ruin.model.entity.player.*;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.stat.StatType;
import rsps.sim.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One world-thread registry, one capped scheduler, one bounded snapshot writer. */
public final class Adventurers {
    public static final int HARD_CAP = 25;
    private static final String[] NAMES = {"Alder Vale","Mira Ash","Rowan Pike","Nell Ember","Corin Oak","Lysa Reed","Toren Flint","Wren Fox","Bram Moss","Iris Glen","Orin Birch","Sera Brook","Finn Alder","Tess Willow","Arlo Stone","Eira Dawn","Reed Vale","Lark Pine","Nora Wisp","Owen Thorn","Rhea Fern","Hale Rune","Cora Lake","Evan Dusk","Isla Crest"};
    private static final Set<String> RESERVED = new HashSet<>();
    static { for (String n : NAMES) RESERVED.add(n.toLowerCase(Locale.ROOT)); }
    private static final Map<String,Adventurer> actors = new LinkedHashMap<>();
    private static final List<JsonObject> loaded = new ArrayList<>();
    private static final Map<String,String> pending = new LinkedHashMap<>();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "adventurer-saves"); t.setDaemon(true); return t; });
    private static boolean writing, booted, stopping;
    private static volatile String storageFailure;
    private static boolean enabled;
    private static int population;
    private static Path directory;
    private static FileChannel lockChannel;
    private static FileLock lock;
    private static BrainScheduler scheduler;
    private static int updatePauseCursor;
    private static long lastStatus, decisions, backlogTicks, slowestNanos, samples, totalNanos;

    public static boolean enabled() { return enabled; }
    public static boolean reservedName(String name) { return enabled && name != null && RESERVED.contains(name.replace('_',' ').trim().toLowerCase(Locale.ROOT)); }
    public static int count() { return actors.size(); }
    public static int humansOnline() {
        int count = 0;
        for (Player p : World.players) if (p != null && p.isOnline() && p.getChannel().id() != null) count++;
        return count;
    }

    /** Called before world processing starts: all disk reads and the global lease are here. */
    public static void prepare() throws IOException {
        String configPath = System.getProperty("kronos.adventurers.config");
        if (configPath == null) return;
        Properties settings = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(configPath))) { settings.load(in); }
        enabled = Boolean.parseBoolean(settings.getProperty("enabled", "false"));
        if (!enabled) return;
        population = Integer.parseInt(settings.getProperty("population", "5"));
        if (population < 1 || population > HARD_CAP) throw new IOException("Adventurer population must be 1..25");
        if (!"exclude".equals(settings.getProperty("skiller.slayer", "exclude"))) throw new IOException("Unsupported skiller Slayer policy");
        if (Boolean.parseBoolean(settings.getProperty("market.enabled", "false"))) throw new IOException("Automated market adapter is not enabled: durable receipts and trusted demand required");
                Properties accountNames = new Properties();
        String registry = System.getProperty("kronos.accounts");
        if (registry != null) try(InputStream in=Files.newInputStream(Paths.get(registry))) { accountNames.load(in); }
        for(String name:RESERVED) {
            if(accountNames.containsKey(name+".hash") || accountNames.containsKey(name.replace(' ','_')+".hash") || Files.exists(Paths.get(io.ruin.utility.OfflineMode.path,"players",name+".json")))
                throw new IOException("Autonomous name conflicts with an existing player: "+name);
        }
        directory = Paths.get(settings.getProperty("storage")).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        // Every world of this deployment uses the SAME configured directory. Exclusive
        // lease deliberately disables multi-world duplication rather than multiplying the cap.
        lockChannel = FileChannel.open(directory.resolve("population.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) throw new IOException("Another world owns the autonomous population");
        for (int i = 1; i <= HARD_CAP; i++) {
            Path file = directory.resolve(i + ".json");
            if (!Files.exists(file)) continue;
            JsonObject data = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            if (data.get("version").getAsInt() != 1 || !Integer.toString(i).equals(data.getAsJsonObject("player").get("actorId").getAsString())) throw new IOException("Actor identity mismatch: " + file);
            loaded.add(data);
        }
        if (loaded.size() > population) throw new IOException("Population below saved roster; explicit retirement required");
        scheduler = new BrainScheduler(HARD_CAP, 4, 2000000L);
        TrainingSites.prepareSafetyCatalogue();
        KronosActivityPort.prepareCatalogue();
    }

    public static void assertLegacyCapacity() {
        if (enabled) throw new IllegalStateException("Legacy test bots disabled while autonomous population owns the registry");
        int count = 0;
        for (Player p : World.players.entityList) if (p != null && p.getChannel() != null && p.getChannel().id() == null) count++;
        if (count >= HARD_CAP) throw new IllegalStateException("Simulated player cap 25 reached");
    }

    public static void tick() {
        if (!enabled || (!booted && World.updating)) return;
        if (stopping) {
            // A controller failure can happen during a locked animation/death.
            // Keep retrying cleanup after unlock even though decisions are parked.
            for (Adventurer p : actors.values())
                if (p.isOnline() && p.port != null && p.quarantineReason != null) p.quarantine(p.quarantineReason);
            return;
        }
        long now = Server.currentTick();
        if (!booted) {
            booted = true;
            // Restore identities first; invalid data aborts, never recreates a fresh actor.
            for (JsonObject data : loaded) {
                Adventurer p = PlayerFile.decode(data.get("player").toString(), Adventurer.class);
                Properties checkpoint = new Properties();
                for (Map.Entry<String,JsonElement> e : data.getAsJsonObject("brain").entrySet()) checkpoint.setProperty(e.getKey(), e.getValue().getAsString());
                register(p, checkpoint, now);
            }
            for (int i = 1; actors.size() < population && i <= HARD_CAP; i++) {
                if (actors.containsKey(Integer.toString(i))) continue;
                Adventurer p = new Adventurer();
                p.actorId = Integer.toString(i); p.displayName = NAMES[i - 1];
                p.seed = 68419L + i * 7919L;
                p.archetype = i % 4 == 2 ? BuildPolicy.Archetype.SKILLER : i % 4 == 3 ? BuildPolicy.Archetype.HYBRID : BuildPolicy.Archetype.MAIN;
                p.combatStyle = BuildPolicy.CombatStyle.BALANCED;
                register(p, null, now);
                p.getAppearance().colors = new int[] {i % 8, i % 10, (i * 3) % 10, i % 5, i % 4};
                p.getAppearance().update();
            }
            loaded.clear();
        }
        if (storageFailure != null) for (Adventurer p : actors.values()) p.quarantine("Storage failure: " + storageFailure);
        long started = System.nanoTime();
        for (Adventurer p : actors.values()) {
            p.memory.tick();
            if (p.quarantineReason != null) p.quarantine(p.quarantineReason);
            else if (p.isOnline() && p.safety != null) p.safety.tick(now);
        }
        if (World.updating) {
            // Stop ordinary jobs during an update countdown, but allow the
            // normal adapter to escape combat and finish locked death events.
            // One actor per tick keeps recovery/pathfinding bounded.
            if (!actors.isEmpty()) {
                int selected = Math.floorMod(updatePauseCursor++, actors.size());
                int index = 0;
                for (Adventurer p : actors.values()) if (index++ == selected) {
                    if (p.isOnline() && p.port != null && p.quarantineReason == null) p.port.pauseSafely(now);
                    break;
                }
            }
        } else decisions += scheduler.tick(now);
        long duration = System.nanoTime() - started;
        samples++; totalNanos += duration; slowestNanos = Math.max(slowestNanos, duration);
        if (scheduler.hasBacklog()) backlogTicks++;
        // At most one player serialization per tick, and only detached data leaves this thread.
        for (Adventurer p : actors.values()) {
            if (!p.ready && now > p.saveAt - 30) p.ready = true;
            if (p.ready && now >= p.saveAt) { save(p); p.saveAt = now + 100; break; }
        }
        if (now - lastStatus >= 20) { lastStatus = now; writeStatus(now); }
    }

        public static void fault(Throwable failure) {
        stopping = true;
        for(Adventurer p:actors.values())if(p.port!=null)p.quarantine("Controller failure: "+failure);
        Server.logError("Autonomous controller stopped; ordinary world processing continues",failure);
    }
    public static String populationSummary() { return humansOnline()+" players + "+count()+" simulated adventurers"; }
    static void detached(Adventurer p) {
        if (scheduler != null) scheduler.remove(p.actorId);
        TrainingSites.release(p);
        // Keep the durable identity registered and counted until explicit retirement.
        save(p);
    }
    private static void register(Adventurer p, Properties checkpoint, long now) {
        if (actors.size() >= HARD_CAP || actors.containsKey(p.actorId)) throw new IllegalStateException("Duplicate or excess simulated actor");
        int id = Integer.parseInt(p.actorId);
        if (id < 1 || id > HARD_CAP || !NAMES[id - 1].equals(p.displayName)) throw new IllegalStateException("Unrecognized actor identity");
        actors.put(p.actorId, p); // Faulted actors remain counted.
        p.attach(checkpoint, now);
        scheduler.add(p.brain, now + id * 2);
    }

    public static void save(Adventurer p) {
        if (!Thread.currentThread().getName().equals("server-worker #1")) { Server.worker.execute(() -> save(p)); return; }
        if (!enabled || !p.ready || p.brain == null) return;
        Config.save(p);
        JsonObject data = new JsonObject();
        data.addProperty("version", 1);
        data.addProperty("capturedTick", Server.currentTick());
        data.add("player", JsonUtils.GSON_EXPOSE.toJsonTree(p));
        data.add("brain", new Gson().toJsonTree(p.brain.checkpoint(Server.currentTick())));
        enqueue(p.actorId + ".json", data.toString());
    }

    private static void writeStatus(long now) {
        JsonObject report = new JsonObject();
        report.addProperty("tick", now); report.addProperty("humans", humansOnline());
        report.addProperty("simulated", actors.size()); report.addProperty("cap", HARD_CAP);
        report.addProperty("marketEnabled", false); report.addProperty("skillerSlayer", "excluded by owner request");
        report.addProperty("decisions", decisions); report.addProperty("backlogTicks", backlogTicks);
        report.addProperty("averageDecisionTickMs", samples == 0 ? 0 : totalNanos / (double)samples / 1e6);
        report.addProperty("slowestDecisionTickMs", slowestNanos / 1e6);
        report.addProperty("worldLastMs", Server.worker.getLastExecutionTime());
        report.addProperty("worldSlowTicks", Server.worker.getSlowExecutions());
        report.addProperty("worldSlowestMs", Server.worker.getSlowestExecutionTime());
        report.addProperty("storageFailure", storageFailure);
        JsonArray roster = new JsonArray();
        for (Adventurer p : actors.values()) {
            JsonObject a = new JsonObject(); a.addProperty("name", p.displayName); a.addProperty("build", p.archetype.name());
            if (p.brain == null || p.port == null) {
                a.addProperty("state", "FAULTED");
                a.addProperty("reason", "Actor initialization did not complete; administrative repair required");
                roster.add(a);
                continue;
            }
            a.addProperty("state", p.brain.state().name()); a.addProperty("reason", p.brain.reason());
            a.addProperty("activity", p.port.description()); a.addProperty("shop", p.viewingShop == null ? null : p.viewingShop.identifier); a.addProperty("actionRunning",p.hasActiveAction()); a.addProperty("locked",p.isLocked()); a.addProperty("quarantine", p.quarantineReason);
            a.addProperty("x", p.getAbsX()); a.addProperty("y", p.getAbsY());
            a.addProperty("routes", p.port.routes); a.addProperty("slowestRouteMs", p.port.slowestRouteNanos / 1e6);
            a.addProperty("bankTrips", p.port.bankTrips); a.addProperty("xpMode", p.xpMode.name()); a.add("learning", JsonUtils.GSON_EXPOSE.toJsonTree(p.memory));
            a.add("safety", JsonUtils.GSON_EXPOSE.toJsonTree(p.safety));
            JsonObject xp = new JsonObject(); for (StatType t : StatType.values()) xp.addProperty(t.name(), p.getStats().get(t).experience);
            a.add("xp", xp); a.add("blockedGoals", p.port.blockedGoals()); roster.add(a);
        }
        report.add("actors", roster); report.add("catalogue", KronosActivityPort.catalogueStatus());
        enqueue("status.json", new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static void enqueue(String name, String text) {
        synchronized (pending) {
            pending.put(name, text); // One latest detached checkpoint per actor, bounded to 26 entries.
            if (writing) return;
            writing = true;
        }
        writer.execute(() -> {
            while (true) {
                String name1, text1;
                synchronized (pending) {
                    if (pending.isEmpty()) { writing = false; return; }
                    name1 = pending.keySet().iterator().next(); text1 = pending.remove(name1);
                }
                try {
                    Path target = directory.resolve(name1), temp = directory.resolve(name1 + ".tmp");
                    byte[] bytes = text1.getBytes(StandardCharsets.UTF_8);
                    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.force(true);
                    }
                    // Windows readers/antivirus may briefly deny replacement. Retry only on
                    // this background writer; keep the previous atomic checkpoint intact.
                    for (int attempt = 0; ; attempt++) {
                        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); break; }
                        catch (AccessDeniedException busy) { if (attempt >= 9) throw busy; Thread.sleep(50L * (attempt + 1)); }
                    }
                } catch (Exception e) { storageFailure = e.toString(); Server.logError("Adventurer checkpoint failed", e); }
            }
        });
    }

    public static void shutdown() throws Exception {
        if (!enabled) return;
        Server.worker.getExecutor().submit(() -> {
            stopping = true;
            for (Adventurer p : actors.values()) save(p);
            writeStatus(Server.currentTick());
        }).get(15, TimeUnit.SECONDS);
        writer.submit(() -> {}).get(30, TimeUnit.SECONDS);
        // Keep population lease until JVM termination; no second world may start early.
    }
}
