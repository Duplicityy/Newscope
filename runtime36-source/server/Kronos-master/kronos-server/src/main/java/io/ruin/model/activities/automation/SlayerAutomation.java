package io.ruin.model.activities.automation;

import io.ruin.cache.NPCDef;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.actions.edgeville.SlayerMaster;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCounter;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.item.Item;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.impl.Door;
import io.ruin.model.map.route.routes.TargetRoute;
import io.ruin.model.skills.magic.spells.HomeTeleport;
import io.ruin.model.skills.slayer.Slayer;
import io.ruin.model.skills.slayer.SlayerTask;
import io.ruin.network.incoming.handlers.NPCActionHandler;
import io.ruin.process.event.Event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs only on the world worker. The player save and native task service own all Slayer progress. */
final class SlayerAutomation implements AutomationDriver {
    private enum Step { INSPECT, MASTER, PREPARE, TASK, FIGHT, BANK, RESTOCK }

    private static final int WALK_TICKS = 65, INTERACTION_TICKS = 18, MAX_RETRIES = 3;
    private final Player player;
    private final SlayerSettings settings;
    private final AutomationActionSource actions;
    private final Map<Item, Integer> loot;
    private final int initialCompletions;
    private final Set<NPC> rejected = new HashSet<>();
    private final Set<String> excludedAreas = new HashSet<>();
    private final Set<SlayerLoadout> unavailableLoadouts = EnumSet.noneOf(SlayerLoadout.class);

    private Step step = Step.INSPECT;
    private SlayerTask assignment;
    private long revision;
    private int observedCompletions;
    private int selectedMaster;
    private int retries;
    private int bankTrips;
    private int transitions;
    private int combatReplans;
    private Event action;
    private boolean walked, stopped, openedBank, bankPending, prayerRestockBlocked;
    private long deadline;
    private long progressDeadline, dialogueDeadline;
    private int lastRemaining;
    private Iterator<NPC> search;
    private NPC bestDestination;
    private Position expectedArrival;
    private GameObject pendingDoor;
    private NPC destination;
    private NPC huntDestination;
    private SlayerLoadout loadout;
    private String combatArea;
    private SlayerWorld.Route route;
    private CombatAutomation combat;
    private String status = "Checking current assignment";
    private String bankReason = "";
    private String loadoutProblem;
    private String routeProblem;

    SlayerAutomation(Player player, SlayerSettings settings, long tick, Map<Item, Integer> loot, int initialCompletions) {
        this.player = player;
        this.settings = settings;
        this.loot = loot;
        this.initialCompletions = initialCompletions;
        actions = (AutomationActionSource) (Object) player;
        observedCompletions = completions(player);
        deadline = tick;
    }

    static int completions(Player player) { return PlayerCounter.SLAYER_TASKS_COMPLETED.get(player); }

    static String masterName(int id) {
        NPCDef def = id == 0 ? null : NPCDef.get(id);
        return def == null ? "Choose a master" : def.name;
    }

    static String masterUnavailable(Player p, int id, SlayerSettings settings, long tick) {
        if (id == 0) return "Choose a Slayer master before requesting a new assignment.";
        SlayerTask.Type tier = SlayerMaster.assignmentTier(p, settings.tier);
        if (p.getCombat().getLevel() < tier.minimumCombatLevel)
            return "Requires combat level " + tier.minimumCombatLevel + " for " + tier.name().toLowerCase() + " tasks.";
        SlayerWorld world = SlayerWorld.current(tick);
        List<NPC> spawns = world.masters(id);
        if (spawns.isEmpty()) return "No active spawn for this master at the main Home.";
        for (NPC npc : spawns) if (SlayerWorld.present(npc) && world.routeToHomeMaster(p, npc, settings.useTeleports) != null) return null;
        return "Travel to the main Home or enable free travel.";
    }

    @Override
    public String activity() {
        return masterName(settings.master) + " / "
                + (assignment == null ? "No task" : assignment.name + " (" + player.slayerTaskRemaining + " left)")
                + " / " + status + " / " + (completions(player) - initialCompletions) + " completed"
                + (bankTrips == 0 ? "" : " / " + bankTrips + " bank visits");
    }

    @Override
    public boolean ownsNativeLock() { return expectedArrival != null || pendingDoor != null; }

    @Override
    public boolean ownsNativeTravel() { return ownsNativeLock(); }

    @Override
    public String pulse(long tick) {
        if (stopped) return "Stopped.";
        SlayerTask current = Slayer.getTask(player);
        long currentRevision = Slayer.assignmentRevision(player);
        int completed = completions(player);
        if (pendingDoor != null) {
            if (player.isLocked() || action != null && actions.ownerAutomationAction() == action)
                return tick < deadline ? null : retry("The door interaction did not finish.");
            if (pendingDoor.tile != null && Door.hasStandardOpening(pendingDoor))
                return retry("The door could not be opened through its normal interaction.");
            pendingDoor = null;
            action = null;
            route = null;
        }
        if (expectedArrival != null) {
            if (player.isLocked() || player.getMovement().isTeleportQueued()) {
                return tick < deadline ? null : "The native travel interaction did not finish.";
            }
            int tolerance = route != null && (route.waypoint || route.passage != null && route.passage.approach != null) ? 0 : 5;
            if (!player.getPosition().isWithinDistance(expectedArrival, tolerance)) {
                if (route != null && route.waypoint && !player.getMovement().isAtDestination() && tick < deadline)
                    return null;
                if (route != null && route.passage != null && tick < deadline) {
                    // Object walking can hand off to a separate event that animates the crossing.
                    Event crossing = actions.ownerAutomationAction();
                    if (crossing != null) action = crossing;
                    return null;
                }
                if (action != null && actions.ownerAutomationAction() == action && tick < deadline) return null;
                return retry("The travel interaction did not reach its expected destination.");
            }
            if (route != null && route.passage != null && route.passage.id == StrongholdSlayerRoutes.ENTRANCE
                    && player.lastDialogue instanceof io.ruin.model.inter.dialogue.MessageDialogue) player.continueDialogue();
            expectedArrival = null;
            action = null;
            route = null;
        }
        if (step == Step.INSPECT || currentRevision != revision || current != assignment) {
            if (combat != null && current == null && completed > observedCompletions && combat.settlingLoot()
                    && combat.pulse(tick) == null) return null;
            boolean justCompleted = completed > observedCompletions;
            resetPlan(current, currentRevision);
            observedCompletions = completed;
            if (current == null && justCompleted && !loot.isEmpty()) requestBank("Assignment complete. Depositing loot");
        }
        if (assignment != null && (player.slayerTaskDangerous || player.slayerTaskRemaining < 1))
            return "This assignment requires Wilderness travel or manual boss setup.";
        if (player.isLocked() || player.hasDialogue() && step != Step.BANK) {
            if (dialogueDeadline == 0) dialogueDeadline = tick + 60;
            return tick < dialogueDeadline ? null : "An action or dialogue is holding the character.";
        }
        dialogueDeadline = 0;
        if (player.isPoisoned() && !player.hasActiveAction() && SlayerLoadout.drinkAntipoison(player)) {
            status = "Treating poison";
            return null;
        }
        if (step != Step.FIGHT && step != Step.RESTOCK && !player.hasActiveAction() && !bankPending && !walked
                && player.getHp() * 100L <= player.getMaxHp() * (long) settings.healthPercent()
                && CombatAutomation.eatDuringTravel(player)) {
            status = "Recovering health before travel";
            return null;
        }
        switch (step) {
            case MASTER: return master(tick);
            case PREPARE: return prepare(tick);
            case TASK: return task(tick);
            case FIGHT: return fight(tick);
            case BANK: return bank(tick);
            case RESTOCK: return restock();
            default: throw new IllegalStateException("Unexpected Slayer state " + step);
        }
    }

    private void resetPlan(SlayerTask current, long currentRevision) {
        closeBank();
        clearTravel();
        clearCombat();
        assignment = current;
        revision = currentRevision;
        lastRemaining = player.slayerTaskRemaining;
        progressDeadline = 0;
        rejected.clear();
        excludedAreas.clear();
        unavailableLoadouts.clear();
        loadoutProblem = null;
        routeProblem = null;
        retries = 0;
        combatReplans = 0;
        destination = null;
        huntDestination = null;
        loadout = null;
        step = current == null ? Step.MASTER : Step.PREPARE;
    }

    private String master(long tick) {
        if (settings.master != selectedMaster) {
            clearTravel();
            rejected.clear();
            retries = 0;
            destination = null;
            selectedMaster = settings.master;
        }
        if (destination == null) {
            String blocker = masterUnavailable(player, selectedMaster, settings, tick);
            if (blocker != null) return blocker;
            if (!choose(SlayerWorld.current(tick).masters(selectedMaster), tick))
                return search != null ? null : "No reachable spawn remains for " + masterName(selectedMaster) + ".";
        }
        status = "Travelling to " + masterName(selectedMaster) + " at Home";
        if (!SlayerWorld.present(destination) || destination.isHidden()) return retry("The selected master moved out of reach.");
        if (!SlayerWorld.withinInteraction(player.getPosition(), destination.getPosition(), destination.getSize()))
            return navigate(tick);
        if (Slayer.getTask(player) != null) return null;
        status = "Requesting an assignment from " + masterName(selectedMaster);
        // This service acts synchronously. The next pulse always reconciles the server task first.
        String result = SlayerMaster.requestSafeAssignment(player, destination, settings.tier);
        if (result != null) return result;
        return Slayer.getTask(player) == null ? "The master did not assign a task." : null;
    }

    private String prepare(long tick) {
        status = "Planning " + assignment.name + " and checking supplies";
        if (destination == null && SlayerWorld.present(huntDestination)) {
            route = SlayerWorld.current(tick).route(player, huntDestination, settings.useTeleports);
            if (route != null) destination = huntDestination;
        }
        if (destination == null) {
            List<NPC> spawns = SlayerWorld.current(tick).monsters(assignment);
            if (spawns.isEmpty()) return "No safe registered monster spawns for " + assignment.name + ".";
            List<NPC> supported = new ArrayList<>();
            Set<String> unsupported = new HashSet<>();
            for (NPC npc : spawns) {
                if (!SlayerWorld.present(npc) || !Slayer.isTask(player, npc)) continue;
                SlayerLoadout rules = SlayerLoadout.forNpc(npc);
                if (rules == null || !CombatAutomation.manageable(SlayerLoadout.combatRisk(npc), player.getMaxHp())) {
                    unsupported.add(npc.getDef().name);
                    continue;
                }
                if (!excludedAreas.contains(area(npc)) && !unavailableLoadouts.contains(rules)) supported.add(npc);
            }
            if (supported.isEmpty() && loadoutProblem != null) return loadoutProblem;
            if (supported.isEmpty()) return "No supported, survivable variant available for " + assignment.name
                    + (unsupported.isEmpty() ? "." : " (" + String.join(", ", unsupported) + ").");
            if (!choose(supported, tick))
                return search != null ? null : loadoutProblem != null ? loadoutProblem
                        : routeProblem != null ? routeProblem
                        : "No verified route to a qualifying " + assignment.name + " spawn.";
            loadout = SlayerLoadout.forNpc(destination);
            huntDestination = destination;
        }
        String problem = loadout.prepare(player, settings, false);
        if (problem != null || player.isVisibleInterface(Interface.BANK)
                || player.getInventory().getFreeSlots() < 2 && !loot.isEmpty()) {
            requestBank(problem != null ? problem : "Inventory space is low");
            return null;
        }
        if (!prayerRestockBlocked && SlayerLoadout.needsPrayerSupplies(player)
                && player.getInventory().getFreeSlots() > 2) {
            requestBank("Restocking prayer potions");
            return null;
        }
        step = Step.TASK;
        return null;
    }

    private String task(long tick) {
        if (!SlayerWorld.present(destination)) return alternate("The monster spawn is no longer available.");
        status = "Travelling to " + destination.getDef().name + " near " + coordinates(destination.spawnPosition);
        if (loadout.requiresRanged()) {
            if (!SlayerRangedTargeting.canFireFrom(player.getPosition(), destination)) {
                Position firing = SlayerRangedTargeting.firingPosition(player.getPosition(), destination);
                if (firing != null && expectedArrival == null) route = SlayerWorld.Route.waypoint(firing,
                        SlayerWorld.distance(player.getPosition(), firing));
                return navigate(tick);
            }
        } else if (!near(destination, 6)
                || !SlayerWorld.reachable(player.getPosition(), destination.getPosition(), destination.getSize())) {
            return navigate(tick);
        }
        combat = CombatAutomation.slayer(player, tick, loadout, settings, loot);
        combatArea = area(destination);
        progressDeadline = tick + 420;
        retries = 0;
        destination = null;
        clearTravel();
        step = Step.FIGHT;
        return null;
    }

    private String fight(long tick) {
        status = combat.activity();
        if (lastRemaining != player.slayerTaskRemaining) {
            lastRemaining = player.slayerTaskRemaining;
            progressDeadline = tick + 420;
            combatReplans = 0;
        }
        if (tick >= progressDeadline) {
            excludedAreas.add(combatArea);
            return alternate("Fights did not produce native Slayer credit.");
        }
        String result = combat.pulse(tick);
        if (Slayer.getTask(player) == null) return null;
        if (result != null && (result.startsWith("Health is low") || result.startsWith("Bring food"))
                || SlayerLoadout.food(player) < settings.foodMinimum() || !loadout.canFight(player)
                || settings.loot && !loot.isEmpty() && player.getInventory().getFreeSlots() < 2) {
            requestBank(result != null ? result : "Food, finishing items, equipment or inventory space needs attention");
            return null;
        }
        if (!prayerRestockBlocked && player.getCombat().getTarget() == null
                && SlayerLoadout.lowPrayer(player)
                && player.getInventory().getFreeSlots() > 2 && SlayerLoadout.needsPrayerSupplies(player)) {
            requestBank("Restocking prayer potions");
            return null;
        }
        if (result != null && result.startsWith("No eligible")) {
            excludedAreas.add(combatArea);
            return alternate("No eligible targets or task progress in this area.");
        }
        if ("Left the safe combat area".equals(result) && SlayerWorld.safeTile(player.getPosition())
                && ++combatReplans <= MAX_RETRIES)
            return alternate("Replanning after a target moved beyond the combat area.");
        return result;
    }

    private void requestBank(String reason) {
        clearCombat();
        clearTravel();
        bankReason = reason;
        destination = null;
        retries = 0;
        rejected.clear();
        step = Step.BANK;
    }

    private String bank(long tick) {
        status = "Banking. " + bankReason;
        if (player.isVisibleInterface(Interface.BANK)) {
            openedBank = true;
            action = null;
            step = Step.RESTOCK;
            return null;
        }
        if (player.hasDialogue()) return "The banker opened a dialogue. Check the bank PIN or access requirement.";
        if (player.isVisibleInterface(213)) return "The bank PIN needs manual verification before restocking.";
        if (bankPending) {
            if (tick < deadline) return null;
            return retry("The banker did not open the bank. Check whether the bank PIN needs manual entry.");
        }
        if (destination == null && !choose(SlayerWorld.current(tick).banks(), tick))
            return search != null ? null : "No verified route to an available banker. " + bankReason;
        if (route == null || route.transition()) return navigate(tick);
        if (!SlayerWorld.present(destination) || destination.isHidden()) return retry("The banker is unavailable.");
        int option = SlayerWorld.bankOption(destination);
        if (option < 1) return retry("The banker has no working Bank action.");
        NPCActionHandler.handleAction(player, option, destination.getIndex(), 0);
        bankPending = true;
        deadline = tick + INTERACTION_TICKS + SlayerWorld.distance(player.getPosition(), destination.getPosition());
        return null;
    }

    private String restock() {
        status = "Depositing loot and checking stock";
        if (!player.isVisibleInterface(Interface.BANK)) return "Bank access was interrupted.";
        if (!SlayerLoadout.depositLoot(player, loot)) return "The bank could not accept the Slayer loot.";
        String problem = assignment == null || loadout == null ? null : loadout.prepare(player, settings, true);
        SlayerLoadout.restockPrayer(player);
        prayerRestockBlocked = SlayerLoadout.prayerDoses(player) == 0;
        closeBank();
        if (problem != null) {
            loadoutProblem = problem;
            unavailableLoadouts.add(loadout);
            huntDestination = null;
            loadout = null;
        }
        bankTrips++;
        clearTravel();
        destination = null;
        retries = 0;
        rejected.clear();
        step = assignment == null ? Step.MASTER : Step.PREPARE;
        return null;
    }

    private boolean choose(List<NPC> candidates, long tick) {
        if (search == null) {
            List<NPC> sorted = new ArrayList<>();
            for (NPC npc : candidates) if (SlayerWorld.present(npc) && !rejected.contains(npc)) sorted.add(npc);
            Position here = player.getPosition();
            sorted.sort(Comparator.comparingInt(npc -> SlayerWorld.distance(here, npc.spawnPosition)
                    + (here.getZ() == npc.getHeight() ? 0 : 30)));
            Map<String, Integer> examined = new HashMap<>();
            sorted.removeIf(npc -> examined.merge(area(npc), 1, Integer::sum) > 2);
            search = sorted.iterator();
        }
        for (int i = 0; i < 4 && search.hasNext(); i++) {
            NPC npc = search.next();
            SlayerWorld world = SlayerWorld.current(tick);
            SlayerWorld.Route candidate = step == Step.MASTER ? world.routeToHomeMaster(player, npc, settings.useTeleports)
                    : world.route(player, npc, settings.useTeleports);
            if (step == Step.BANK && settings.preferHomeBank && settings.useTeleports
                    && (candidate == null || candidate.transition())) {
                SlayerWorld.Route home = world.homeBankRoute(player, npc);
                if (SlayerWorld.better(home, candidate)) candidate = home;
            }
            if (!SlayerWorld.better(candidate, route)) continue;
            route = candidate;
            bestDestination = npc;
            if (route.cost <= 12) break;
        }
        if (search.hasNext() && (route == null || route.cost > 12)) return false;
        search = null;
        destination = bestDestination;
        bestDestination = null;
        return destination != null;
    }

    private String navigate(long tick) {
        if (walked) {
            if (tick < deadline && !player.getMovement().isAtDestination()) return null;
            if (!SlayerWorld.withinInteraction(player.getPosition(), destination.getPosition(), destination.getSize()))
                return retry("Local movement stalled before reaching the destination.");
            walked = false;
            return null;
        }
        if (route == null) {
            SlayerWorld world = SlayerWorld.current(tick);
            route = step == Step.MASTER ? world.routeToHomeMaster(player, destination, settings.useTeleports)
                    : world.route(player, destination, settings.useTeleports);
        }
        if (route == null) return retry("There is no confirmed path to the destination.");
        if (route.transition()) {
            if (++transitions > 16) return retry("The route repeated too many doors or transitions.");
            if (route.door != null) {
                if (!route.useDoor(player)) return retry("The openable door changed before interaction.");
                pendingDoor = route.door;
                action = actions.ownerAutomationAction();
                deadline = tick + WALK_TICKS;
                status = "Opening a door on the route";
                return null;
            }
            expectedArrival = route.arrival;
            deadline = tick + WALK_TICKS;
            if (route.waypoint) {
                deadline = tick + 180;
                if (!player.getRouteFinder().routeAbsolute(route.arrival.getX(), route.arrival.getY()).reachable)
                    return retry("Collision blocked a walking waypoint.");
            } else if (route.home) {
                if (step == Step.MASTER) HomeTeleport.castPrimaryHome(player);
                else HomeTeleport.MODERN.clickAction.accept(player, 0);
            } else if (route.portal != null) {
                status = "Taking the " + route.portal.name + " teleport";
                route.portal.use(player);
            } else if (!route.passage.use(player)) {
                return retry("The registered entrance is unavailable.");
            }
            if (route.passage != null && route.passage.id == SlayerTowerRoutes.FIRST_STAIR) deadline = tick + 180;
            action = actions.ownerAutomationAction();
            return null;
        }
        if (SlayerWorld.withinInteraction(player.getPosition(), destination.getPosition(), destination.getSize())) return null;
        if (!player.getRouteFinder().routeEntity(destination).reachable)
            return retry("Collision blocked the path to the destination.");
        walked = true;
        deadline = tick + WALK_TICKS;
        return null;
    }

    private boolean near(NPC npc, int range) {
        if (!SlayerWorld.present(npc)) return false;
        Position tile = npc.isHidden() ? npc.spawnPosition : npc.getPosition();
        return player.getPosition().isWithinDistance(tile, range);
    }

    private String retry(String reason) {
        routeProblem = reason;
        clearTravel();
        if (destination != null) rejected.add(destination);
        destination = null;
        if (step == Step.TASK || step == Step.FIGHT) {
            huntDestination = null;
            step = Step.PREPARE;
        }
        return ++retries >= MAX_RETRIES ? reason : null;
    }

    private String alternate(String reason) {
        clearCombat();
        return retry(reason);
    }

    private static String area(NPC npc) { return area(npc.spawnPosition); }
    private static String area(Position p) { return p.getX() / 16 + "/" + p.getY() / 16 + "/" + p.getZ(); }
    private static String coordinates(Position p) { return p.getX() + "," + p.getY() + " plane " + p.getZ(); }

    private void clearTravel() {
        TargetRoute targetRoute = player.getRouteFinder().targetRoute;
        if (bankPending && targetRoute != null && targetRoute.target == destination) {
            TargetRoute.reset(player);
            player.getMovement().reset();
        }
        bankPending = false;
        if (action != null && !player.isLocked() && !player.getMovement().isTeleportQueued()
                && actions.cancelOwnerAutomationAction(action)) player.getMovement().reset();
        if (walked || route != null && route.waypoint && expectedArrival != null) player.getMovement().reset();
        walked = false;
        transitions = 0;
        action = null;
        expectedArrival = null;
        pendingDoor = null;
        route = null;
        search = null;
        bestDestination = null;
    }

    private void clearCombat() {
        if (combat != null) combat.stop();
        combat = null;
    }

    private void closeBank() {
        if (openedBank && player.isVisibleInterface(Interface.BANK)) player.closeInterface(InterfaceType.MAIN);
        openedBank = false;
    }

    @Override
    public void stop() {
        stopped = true;
        clearCombat();
        clearTravel();
    }
}
