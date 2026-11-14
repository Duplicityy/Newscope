import com.sun.tools.attach.VirtualMachine;
import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.combat.Combat;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded normal-combat probe, exclusively for the disposable staging account.
 * It never writes damage or modifies production data. The only NPC it removes
 * is the one it creates. Compile this single class against the staged game JARs.
 */
public final class Content242CombatProbe {
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    public static void agentmain(String request, Instrumentation instrumentation) throws Exception {
        require(Boolean.getBoolean("kronos.content242.test") && Boolean.getBoolean("kronos.adventurers.test")
                && Integer.getInteger("kronos.port", 0) == 14302
                && "127.0.0.1".equals(System.getProperty("kronos.bind")), "Isolated content242 game required");
        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get(request))) { config.load(input); }
        String stageName = config.getProperty("stage", "");
        require(stageName.matches("content242-test-[a-z0-9-]+")
                && stageName.equals(System.getProperty("kronos.content242.stage")), "Exact staging identity required");
        Path root = Paths.get(request).toAbsolutePath().getParent();
        require(root.getFileName().toString().equals(stageName), "Probe request must live in the named stage");
        int weapon = Integer.parseInt(config.getProperty("weapon", "26219"));
        ScheduledExecutorService executor = Server.worker.getExecutor();
        Probe probe = executor.submit(() -> new Probe(weapon, root.resolve("combat-proof.json"))).get(10, TimeUnit.SECONDS);
        try {
            executor.submit(probe::start).get(10, TimeUnit.SECONDS);
            if (probe.finished) probe.done.get(1, TimeUnit.SECONDS);
            probe.poller = executor.scheduleAtFixedRate(probe::poll, 100, 100, TimeUnit.MILLISECONDS);
            probe.done.get(25, TimeUnit.SECONDS);
        } catch (Exception error) {
            executor.submit(() -> probe.finish(false, "Probe failed: " + error.toString())).get(10, TimeUnit.SECONDS);
            throw error;
        }
    }

    private static final class Probe {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final int expectedWeapon;
        final Path output;
        final Field lastAttack;
        ScheduledFuture<?> poller;
        Player player;
        NPC dummy;
        Position original;
        long startTick, attackBefore, attackAfter, endTick;
        int initialHp, finalHp, npcIndex = -1, expectedAnimation, observedAttacks;
        int matchingRecords, connectedRecords;
        boolean finished;

        Probe(int weapon, Path output) throws Exception {
            this.expectedWeapon = weapon; this.output = output;
            lastAttack = Combat.class.getDeclaredField("lastAttackTick"); lastAttack.setAccessible(true);
        }

        void start() {
            try {
                // Iterate actual slots, not the tick's compacted index list.
                // Failed/retried logins can leave a disconnected record until
                // the next indexing pass; never overwrite the live candidate.
                for (Player candidate : World.players.entityList) {
                    if (candidate == null) continue;
                    boolean connected = candidate.isOnline() && candidate.getChannel() != null
                            && candidate.getChannel().isActive() && candidate.getChannel().id() != null;
                    if (!"c242qa".equalsIgnoreCase(candidate.getName())) {
                        require(!connected, "Another player is online in the staging world");
                        continue;
                    }
                    matchingRecords++;
                    if (connected) { connectedRecords++; if (player == null) player = candidate; }
                }
                require(player != null && connectedRecords == 1, "Expected one connected online c242qa; matching records="
                        + matchingRecords + ", connected online records=" + connectedRecords);
                require(player.getCombat().getTarget() == null, "Player already has a combat target");
                require(!player.getCombat().useSpell() && player.getCombat().specialActive() == null,
                        "Ordinary melee, without queued spell or special, required");
                Item weapon = player.getEquipment().get(Equipment.SLOT_WEAPON);
                require(weapon != null && weapon.getId() == expectedWeapon && weapon.getDef().name.equalsIgnoreCase("Osmumten's fang"),
                        "Expected imported Fang must already be equipped through normal rules");
                require(weapon.getDef().special == null && player.getCombat().getAttackStyle().isMelee(), "Imported base melee required");
                original = player.getPosition().copy();
                Tile origin = Tile.get(original, true);
                require(origin.isTileFree(), "Player needs an unobstructed test tile");
                Position spawn = null;
                int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] step : directions) {
                    Position candidate = new Position(original.getX()+step[0], original.getY()+step[1], original.getZ());
                    Tile tile = Tile.get(candidate, true);
                    if (tile.isTileFree() && tile.playerCount == 0 && tile.npcCount == 0) { spawn = candidate; break; }
                }
                require(spawn != null, "No free adjacent staging combat tile");
                dummy = new NPC(3078).spawn(spawn);
                npcIndex = dummy.getIndex();
                require(dummy.getCombat() != null && dummy.getDef().name.equalsIgnoreCase("Man"), "Standard Man combat definition required");
                dummy.getCombat().setAllowRetaliate(false);
                dummy.getCombat().setAllowRespawn(false);
                // Stat.set clamps to 99; this is enough to survive the first
                // normal Fang hit, and the probe stops immediately on damage.
                dummy.setMaxHp(99);
                dummy.setHp(99);
                initialHp = finalHp = dummy.getHp();
                require(initialHp == 99 && player.getCombat().canAttack(dummy, false), "Normal player combat rejected the temporary target");
                expectedAnimation = player.getCombat().weaponType.attackAnimation;
                startTick = Server.currentTick();
                attackBefore = attackAfter = lastAttack.getLong(player.getCombat());
                player.getCombat().setTarget(dummy);
                System.out.println("CONTENT242 COMBAT START weapon="+expectedWeapon+" npc="+npcIndex+" hp="+initialHp+" tick="+startTick);
            } catch (Throwable error) { finish(false, "Setup failed: " + error.toString()); }
        }

        void poll() {
            if (finished) return;
            try {
                endTick = Server.currentTick();
                require(World.players.get(player.getIndex()) == player && player.isOnline() && player.getChannel() != null
                        && player.getChannel().isActive() && !dummy.isRemoved(), "Player or temporary target disappeared");
                long attack = lastAttack.getLong(player.getCombat());
                if (attack != attackAfter) { observedAttacks++; attackAfter = attack; }
                finalHp = dummy.getHp();
                if (finalHp < initialHp) {
                    require(dummy.getCombat().lastAttacker == player, "Observed damage was not attributed to the test player");
                    require(attackAfter >= startTick && attackAfter != attackBefore, "Damage observed without a new ordinary attack");
                    finish(true, "Normal PlayerCombat Fang attack reduced temporary NPC HP");
                } else if (endTick - startTick >= 20) finish(false, "No actual damage within 20 world ticks");
            } catch (Throwable error) { finish(false, "Observation failed: " + error.toString()); }
        }

        void finish(boolean passed, String reason) {
            if (finished) return;
            finished = true;
            if (poller != null) poller.cancel(false);
            Throwable cleanupError = null;
            try {
                if (player != null && dummy != null && player.getCombat().getTarget() == dummy) player.getCombat().reset();
                if (dummy != null) { dummy.stopEvent(true); if (dummy.getCombat() != null) dummy.getCombat().reset(); dummy.remove(); }
                if (player != null && original != null && (player.getAbsX() != original.getX()
                        || player.getAbsY() != original.getY() || player.getHeight() != original.getZ()))
                    player.getMovement().teleport(original.getX(), original.getY(), original.getZ());
            } catch (Throwable error) { cleanupError = error; passed = false; reason += "; cleanup failed: " + error.toString(); }
            try {
                endTick = Server.currentTick();
                boolean removed = dummy == null || dummy.isRemoved();
                String escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
                String json = "{\n  \"passed\": "+passed+",\n  \"reason\": \""+escaped+"\",\n"
                    +"  \"matching_player_records\": "+matchingRecords+",\n  \"connected_online_records\": "+connectedRecords+",\n"
                    +"  \"weapon_id\": "+expectedWeapon+",\n  \"expected_attack_animation\": "+expectedAnimation+",\n"
                    +"  \"npc_id\": 3078,\n  \"temporary_npc_index\": "+npcIndex+",\n"
                    +"  \"initial_hp\": "+initialHp+",\n  \"final_hp\": "+finalHp+",\n  \"damage_observed\": "+(initialHp-finalHp)+",\n"
                    +"  \"world_ticks\": "+(startTick == 0 ? 0 : endTick-startTick)+",\n  \"ordinary_attack_events\": "+observedAttacks+",\n"
                    +"  \"temporary_npc_removed\": "+removed+",\n  \"direct_damage_written\": false\n}\n";
                Files.write(output, json.getBytes(StandardCharsets.UTF_8));
                System.out.println("CONTENT242 COMBAT "+(passed?"PASS":"FAIL")+" damage="+(initialHp-finalHp)+" ticks="+(endTick-startTick)+" removed="+removed+" "+reason);
                if (passed && cleanupError == null) done.complete(null);
                else done.completeExceptionally(new IllegalStateException(reason));
            } catch (Throwable error) { done.completeExceptionally(error); }
        }
    }

    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
}
