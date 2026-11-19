package io.ruin.model.activities.raids.auto;

import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.activities.raids.toa.port.ToaRaidAuto;
import io.ruin.model.activities.raids.toa.port.TombsOfAmascutRaid;
import io.ruin.model.activities.raids.tob.port.TheatreOfBloodRaid;
import io.ruin.model.activities.raids.tob.port.TheatreRaidAuto;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.utils.Config;
import io.ruin.network.incoming.Incoming;
import io.ruin.network.incoming.handlers.ActionButtonHandler;

import java.util.IdentityHashMap;
import java.util.Map;

/** Owner-requested normal raid actions; runtime state is never saved or resumed. */
public final class RaidAuto {
    public interface Controller {
        /** true keeps this controller active; false finishes it. */
        boolean pulse(long worldTick) throws Exception;
        String status();
        /** Cleanup only. Called once after this controller loses action ownership. */
        default void stopped(String reason) {}
    }

    private enum Raid { TOA, TOB }
    private static final Map<Player, Active> ACTIVE = new IdentityHashMap<>();
    private static final Map<Player, String> LAST = new IdentityHashMap<>();
    private static final long MAXIMUM_TICKS = 12000; // two hours at the ordinary600ms World tick
    private static final long LEAVE_TICKS = 100;

    private RaidAuto() {}

    public static boolean isOwner(Player player) {
        return player != null && player.isGroup(PlayerGroup.OWNER);
    }

    public static boolean isActive(Player player) {
        return ACTIVE.containsKey(player);
    }

    public static boolean command(Player player, String command, String[] args) {
        if (!"raid".equalsIgnoreCase(command) || args == null) return false;
        int first = 0;
        while (first < args.length && args[first].isEmpty()) first++;
        if (first == args.length || !"auto".equalsIgnoreCase(args[first])) return false;
        if (!isOwner(player)) {
            player.sendMessage("Raid automation is available only to the owner.");
            return true;
        }
        String subcommand = "start";
        boolean seen = false;
        for (int i = first + 1; i < args.length; i++) {
            if (args[i].isEmpty()) continue;
            if (seen) {
                usage(player);
                return true;
            }
            subcommand = args[i];
            seen = true;
        }
        try {
            if (subcommand.equalsIgnoreCase("status")) showStatus(player);
            else if (subcommand.equalsIgnoreCase("stop")) {
                if (!stop(player, "Stopped by owner", true)) player.sendMessage("Raid automation is already stopped.");
            } else if (subcommand.equalsIgnoreCase("leave")) leave(player);
            else if (subcommand.equalsIgnoreCase("start")) start(player);
            else usage(player);
        } catch (Exception failure) {
            stop(player, "Unable to continue: " + message(failure), false);
            player.sendMessage("Raid automation: " + message(failure));
            Server.logError("Owner raid automation command", failure);
        }
        return true;
    }

    private static void usage(Player player) {
        player.sendMessage("Use ::raid auto [start|stop|status|leave]. Enter ToA or ToB normally first.");
    }

    private static void start(Player player) throws Exception {
        if (ACTIVE.containsKey(player)) {
            showStatus(player);
            return;
        }
        if (!player.isOnline() || player.getHp() < 1 || player.getCombat().isDead()) {
            player.sendMessage("You must be alive and logged in to start raid automation.");
            return;
        }
        if (player.isLocked()) {
            player.sendMessage("Wait for the current action to finish before starting raid automation.");
            return;
        }
        Raid raid = currentRaid(player);
        if (raid == null) {
            player.sendMessage("Enter Tombs of Amascut or Theatre of Blood normally, then use ::raid auto.");
            return;
        }
        Object context = context(player, raid);
        Controller controller = raid == Raid.TOA ? ToaRaidAuto.create(player) : TheatreRaidAuto.create(player);
        if (controller == null || context == null) {
            player.sendMessage("The current raid context cannot be automated.");
            return;
        }
        Active active = new Active(player, raid, context, controller);
        ACTIVE.put(player, active);
        LAST.remove(player);
        player.sendMessage("Raid automation started for " + raid + ". Manual actions or ::raid auto stop take control back.");
        schedule(active);
    }

    private static void leave(Player player) throws Exception {
        Raid raid = currentRaid(player);
        if (raid == null) {
            stop(player, "No active raid to leave", false);
            player.sendMessage("You are not in a ToA or ToB raid.");
            return;
        }
        Object context = context(player, raid);
        if (context == null) {
            player.sendMessage("The current raid context has ended.");
            return;
        }
        stop(player, "Normal raid leave requested", false);
        Active active = new Active(player, raid, context, null);
        active.leaveSince = Server.currentTick();
        ACTIVE.put(player, active);
        player.sendMessage("Raid automation stopped. A normal raid leave is pending any current native action.");
        schedule(active);
    }

    private static void schedule(Active active) {
        World.startEvent(event -> {
            // Never run a fresh controller inline inside a command or network packet.
            event.delay(1);
            while (ACTIVE.get(active.player) == active) {
                try {
                    if (!pulse(active)) return;
                } catch (Exception failure) {
                    String reason = "Stopped: " + message(failure);
                    if (!stop(active.player, reason, true)) {
                        // A native leave is consumed before its callback. Preserve
                        // an honest error even if that consumed callback throws.
                        LAST.put(active.player, reason);
                        if (active.player.isOnline()) active.player.sendMessage("Raid automation: " + reason + ".");
                    }
                    Server.logError("Owner raid automation stopped", failure);
                    return;
                }
                event.delay(1);
            }
        });
    }

    private static boolean pulse(Active active) throws Exception {
        Player player = active.player;
        if (!isOwner(player) || !player.isOnline() || player.logoutStage != 0) {
            stop(player, "Owner logged out or permission ended", false);
            return false;
        }
        if (player.getHp() < 1 || player.getCombat().isDead()) {
            stop(player, "Stopped on death", true);
            return false;
        }
        if (context(player, active.raid) != active.context) {
            stop(player, "Raid session ended or changed", true);
            return false;
        }
        long now = Server.currentTick();
        if (active.leaveSince >= 0) {
            if (now - active.leaveSince >= LEAVE_TICKS) {
                stop(player, "Normal raid leave timed out; take control manually", true);
                return false;
            }
            if (player.isLocked()) return true;
            // Consume before invoking a callback: an exception must not repeat a leave.
            ACTIVE.remove(player);
            if (active.raid == Raid.TOA) TombsOfAmascutRaid.open(player);
            else TheatreOfBloodRaid.open(player);
            boolean submitted = RaidAutoActions.select(player,
                    active.raid == Raid.TOA ? "Leave safely" : "Leave the Theatre");
            String result = submitted ? "Normal raid leave submitted" : "Normal leave menu unavailable; take control manually";
            LAST.put(player, result);
            player.sendMessage("Raid automation: " + result + ".");
            return false;
        }
        if (now - active.started >= MAXIMUM_TICKS) {
            stop(player, "Duration limit reached; take control manually", true);
            return false;
        }
        if (player.isLocked()) return true;
        if (!active.retaliationReady) {
            // Match the normal combat-tab button. ON is config0 in this host;
            // never write the config directly or fight a later manual toggle.
            if (Config.AUTO_RETALIATE.get(player) == 0) {
                if (!RaidAutoActions.button(player, 593, 30, -1, 1, -1))
                    throw new IllegalStateException("Open the combat controls before starting raid automation");
                return true;
            }
            active.retaliationReady = true;
        }
        if (!active.controller.pulse(now)) {
            stop(player, active.controller.status(), true);
            return false;
        }
        return true;
    }

    private static Raid currentRaid(Player player) {
        boolean toa = TombsOfAmascutRaid.isInRaid(player);
        boolean tob = TheatreOfBloodRaid.isInRaid(player);
        if (toa && tob) throw new IllegalStateException("Ambiguous raid ownership");
        return toa ? Raid.TOA : tob ? Raid.TOB : null;
    }

    private static Object context(Player player, Raid raid) throws ReflectiveOperationException {
        Object states = raid == Raid.TOA ? RaidAutoAccess.get(TombsOfAmascutRaid.class, "SESSIONS")
                : RaidAutoAccess.get(TheatreOfBloodRaid.class, "RUNS");
        if (!(states instanceof Map)) throw new IllegalStateException("Native raid context registry unavailable");
        return ((Map<?, ?>) states).get(player);
    }

    public static boolean stop(Player player, String reason, boolean notify) {
        Active active = ACTIVE.remove(player);
        if (active == null) return false;
        LAST.put(player, reason == null ? "Stopped" : reason);
        // Reset only this player's native target/route. Do not close interfaces:
        // manual input is dispatched immediately after this hook and must retain
        // its original menu. Locked raid traversal and incoming hits remain native.
        if (!player.isLocked()) player.resetActions(false, true, true);
        else player.getCombat().reset();
        if (active.controller != null) {
            try { active.controller.stopped(reason); }
            catch (RuntimeException failure) { Server.logError("Owner raid automation cleanup", failure); }
        }
        if (notify && player.isOnline()) player.sendMessage("Raid automation: " + LAST.get(player) + ".");
        return true;
    }

    public static void onLogout(Player player) {
        stop(player, "Logged out", false);
        LAST.remove(player);
    }

    public static void onDeath(Player player) {
        stop(player, "Stopped on death", true);
    }

    /** Called only from the real incoming decoder; ordinary actions bypass it. */
    public static void manualInput(Player player, Incoming handler, int opcode) {
        if (!ACTIVE.containsKey(player) || !isGameplayInput(handler)) return;
        stop(player, "Stopped for manual control", true);
    }

    public static boolean isGameplayInput(Incoming handler) {
        if (handler == null) return false;
        if (handler.getClass().getEnclosingClass() == ActionButtonHandler.class) return true;
        Class<?> family = handler.getClass();
        if (family.getEnclosingClass() != null) family = family.getEnclosingClass();
        switch (family.getSimpleName()) {
            case "WalkHandler":
            case "NPCActionHandler":
            case "ObjectActionHandler":
            case "PlayerActionHandler":
            case "GroundItemActionHandler":
            case "InterfaceOnObjectHandler":
            case "InterfaceOnEntityHandler":
            case "InterfaceOnInterfaceHandler":
            case "InterfaceOnGroundItemHandler":
            case "InterfaceDragHandler":
            case "CloseInterfaceHandler":
            case "TeleportHandler":
            case "InputHandler": return true;
            default: return false; // chat, camera, focus, display and keepalives do not seize control
        }
    }

    private static void showStatus(Player player) {
        Active active = ACTIVE.get(player);
        if (active == null) {
            String last = LAST.get(player);
            player.sendMessage("Raid automation stopped" + (last == null ? "." : ": " + last + "."));
        } else if (active.leaveSince >= 0) {
            player.sendMessage("Raid automation: awaiting a normal " + active.raid + " leave.");
        } else player.sendMessage("Raid automation " + active.raid + ": " + active.controller.status());
    }

    private static String message(Exception failure) {
        String value = failure.getMessage();
        return value == null || value.trim().isEmpty() ? failure.getClass().getSimpleName() : value;
    }

    private static final class Active {
        final Player player;
        final Raid raid;
        final Object context;
        final Controller controller;
        final long started = Server.currentTick();
        long leaveSince = -1;
        boolean retaliationReady;

        Active(Player player, Raid raid, Object context, Controller controller) {
            this.player = player;
            this.raid = raid;
            this.context = context;
            this.controller = controller;
        }
    }
}
