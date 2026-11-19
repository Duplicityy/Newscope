package io.ruin.model.activities.automation;


import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.item.Item;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.skills.mining.Mining;
import io.ruin.model.skills.mining.Pickaxe;
import io.ruin.model.skills.mining.Rock;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded, local Mining strategy. Actions run through native object handlers. */
final class MiningAutomation implements AutomationDriver {
    private static final int MINE_RADIUS = 8, BANK_RADIUS = 12;
    private static final int RESCAN_TICKS = 6, EXCLUDE_TICKS = 45;
    private static final Map<String, Rock> ROCKS = new HashMap<>();
    private static final Map<Integer, Rock> LEGACY_ROCKS = new HashMap<>();

    static {
        // Mining registers these original 184 ore variants under the generic cache name "Rocks".
        legacy(Rock.COPPER, 11161, 10943);
        legacy(Rock.TIN, 11360, 11361);
        legacy(Rock.CLAY, 11362, 11363);
        legacy(Rock.IRON, 11364, 11365);
        legacy(Rock.COAL, 11366, 11367);
        legacy(Rock.SILVER, 11368, 11369);
        legacy(Rock.GOLD, 11370, 11371);
        legacy(Rock.MITHRIL, 11372, 11373);
        legacy(Rock.ADAMANT, 11374, 11375);
        legacy(Rock.RUNE, 11376, 11377);
        legacy(Rock.LOVAKITE, 28596, 28597);
        for (Rock rock : Rock.values()) {
            if (rock.ore <= 0 || rock == Rock.CORRUPTED || rock == Rock.AMETHYST) continue;
            String name = rock == Rock.RUNE ? "runite" : rock == Rock.ADAMANT ? "adamantite" : rock.rockName;
            ROCKS.put(name + " rocks", rock);
            ROCKS.put(name + " rock", rock);
        }
    }

    private static void legacy(Rock rock, int first, int second) {
        LEGACY_ROCKS.put(first, rock);
        LEGACY_ROCKS.put(second, rock);
    }

    static Rock rockFor(int id, String name) {
        if ("rocks".equalsIgnoreCase(name))
            return LEGACY_ROCKS.get(id);
        return ROCKS.get(name.toLowerCase(Locale.ROOT));
    }

    private final Player player;
    private final int goal;
    private final AutomationActionSource actions;
    private final AutomationPolicy.Budget budget;
    private final Map<GameObject, Long> rejected = new IdentityHashMap<>();
    private final Map<Integer, Integer> protectedOre = new HashMap<>();
    private final Map<Integer, Integer> acquiredOre = new HashMap<>();
    private Event miningEvent;
    private Candidate destination;
    private int finishX, finishY, anchorX, anchorY, plane;
    private long routeDeadline, nextScan;
    private int observedOre, observedAmount;
    private double observedXp;
    private String activity = "Looking for a mine";
    private boolean returningFromBank;

    MiningAutomation(Player player, int goal, long tick) {
        this.player = player;
        this.goal = goal;
        actions = (AutomationActionSource) (Object) player;
        budget = new AutomationPolicy.Budget(tick);
        anchorX = player.getAbsX();
        anchorY = player.getAbsY();
        plane = player.getHeight();
        for (Item item : player.getInventory().getItems()) {
            if (item != null)
                protectedOre.merge(item.getId(), item.getAmount(), Integer::sum);
        }
    }

    @Override public String activity() { return activity; }

    /** Returns the reason to end this run, or null to continue. */
    @Override public String pulse(long tick) {
        if (player.getStats().get(StatType.Mining).fixedLevel >= goal)
            return "Mining goal reached";
        if (player.getHeight() != plane || distance(anchorX, anchorY, player.getAbsX(), player.getAbsY()) > 28)
            return "Moved out of the mining area";
        if (miningEvent != null) {
            observeOre(tick);
            if (actions.ownerAutomationAction() == miningEvent)
                return budget.expired(tick) ? "No mining progress. Try another rock or pickaxe." : null;
            miningEvent = null;
        }
        if (Pickaxe.find(player) == null)
            return "Bring a pickaxe you can use";
        if (destination != null)
            return advanceRoute(tick);
        if (player.hasActiveAction() || !player.getMovement().isAtDestination())
            return "Another action took control";
        if (budget.expired(tick))
            return activity + ". Start again beside a working mine or bank.";
        if (tick < nextScan)
            return null;
        nextScan = tick + RESCAN_TICKS;
        boolean bank = player.getInventory().isFull();
        if (bank && acquiredOre.values().stream().noneMatch(count -> count > 0))
            return "Make room in your inventory first";
        if (bank && player.getGameMode().isUltimateIronman())
            return "Inventory full. Your game mode cannot bank.";
        Candidate chosen = choose(bank, tick);
        if (chosen == null) {
            activity = bank ? "No usable bank within 12 tiles" : "Waiting for a suitable rock within " + mineRadius() + " tiles";
            return null;
        }
        return route(chosen, tick);
    }

    @Override public void stop() {
        if (miningEvent != null && actions.cancelOwnerAutomationAction(miningEvent))
            player.resetAnimation();
        miningEvent = null;
        if (destination != null)
            player.getMovement().reset();
        destination = null;
    }

    private int mineRadius() { return returningFromBank ? BANK_RADIUS : MINE_RADIUS; }

    private Candidate choose(boolean bank, long tick) {
        rejected.entrySet().removeIf(entry -> entry.getValue() <= tick);
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int radius = bank ? BANK_RADIUS : mineRadius();
        int x = player.getAbsX(), y = player.getAbsY();
        Pickaxe pickaxe = Pickaxe.find(player);
        int level = player.getStats().get(StatType.Mining).currentLevel;
        int effective = Mining.getEffectiveLevel(player);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int candidateX = x + dx, candidateY = y + dy;
                if (candidateX < 1 || candidateX > 8191 || candidateY < 1 || candidateY > 16382)
                    continue;
                Tile tile = Tile.get(candidateX, candidateY, plane);
                if (tile == null || tile.gameObjects == null)
                    continue;
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                for (GameObject object : tile.gameObjects) {
                    if (object.isRemoved() || rejected.containsKey(object))
                        continue;
                    ObjectDef def = object.getDef();
                    if (def == null || def.name == null || def.varpBitId != -1 || def.varpId != -1)
                        continue;
                    Rock rock = bank ? null : rockFor(object.id, def.name);
                    if (bank ? !def.bank : rock == null || level < rock.levelReq)
                        continue;
                    int option = def.getOption(bank ? "use" : "mine");
                    if (bank && option == -1)
                        option = def.getOption("bank");
                    if (option < 1 || option > 5 || lookup(object, option - 1) == null)
                        continue;
                    double score = bank ? -distance : AutomationPolicy.rate(rock.experience,
                            Mining.chance(effective, rock, pickaxe), distance, rock.depleteChance, rock.respawnTime);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Candidate(object, object.id, option - 1, rock);
                    }
                }
            }
        }
        return best;
    }

    private String route(Candidate chosen, long tick) {
        destination = chosen;
        activity = chosen.rock == null ? "Walking to bank" : "Walking to " + chosen.rock.rockName;
        if (!live(chosen))
            return reject(tick);
        RouteType route = player.getRouteFinder().routeObject(chosen.object);
        if (!route.reachable)
            return reject(tick);
        if (route.finished(player.getPosition()))
            return interact(chosen, tick);
        finishX = route.finishX;
        finishY = route.finishY;
        routeDeadline = tick + AutomationPolicy.ROUTE_TICKS;
        return null;
    }

    private String advanceRoute(long tick) {
        if (!live(destination) || tick >= routeDeadline)
            return reject(tick);
        if (!player.getMovement().isAtDestination())
            return null;
        if (!player.isAt(finishX, finishY))
            return reject(tick);
        // Recheck native reachability at the point of action; nearby doors can change in flight.
        RouteType route = player.getRouteFinder().routeObject(destination.object);
        if (!route.reachable || !route.finished(player.getPosition()))
            return reject(tick);
        return interact(destination, tick);
    }

    private String interact(Candidate chosen, long tick) {
        ObjectAction action = live(chosen) ? lookup(chosen.object, chosen.option) : null;
        if (action == null)
            return reject(tick);
        player.face(chosen.object);
        if (chosen.rock == null) {
            if (player.isVisibleInterface(Interface.BANK)) {
                destination = null;
                return "Close the already open bank before starting again";
            }
            action.handle(player, chosen.object);
            destination = null;
            if (!player.isVisibleInterface(Interface.BANK))
                return "Open the bank manually and complete any PIN prompt";
            int deposited = deposit();
            player.closeInterface(InterfaceType.MAIN);
            player.closeInterface(InterfaceType.INVENTORY);
            if (deposited == 0 || player.getInventory().isFull())
                return "Banking could not free a slot. Check your bank space.";
            returningFromBank = true;
            activity = "Banked mined ore";
            budget.progress(tick);
            return null;
        }
        observedOre = chosen.rock.ore;
        observedAmount = player.getInventory().getAmount(observedOre);
        observedXp = player.getStats().get(StatType.Mining).experience;
        Event before = actions.ownerAutomationAction();
        action.handle(player, chosen.object);
        Event after = actions.ownerAutomationAction();
        if (after == null || after == before)
            return reject(tick);
        miningEvent = after;
        returningFromBank = false;
        destination = null;
        activity = "Mining " + chosen.rock.rockName;
        return null;
    }

    private void observeOre(long tick) {
        int amount = player.getInventory().getAmount(observedOre);
        double xp = player.getStats().get(StatType.Mining).experience;
        if (xp > observedXp) {
            if (amount > observedAmount)
                acquiredOre.merge(observedOre, amount - observedAmount, Integer::sum);
            budget.progress(tick);
        }
        observedXp = xp;
        observedAmount = amount;
    }

    private int deposit() {
        int deposited = 0;
        // The bank moves each item atomically, with native capacity checks, preserving attributes.
        for (Item item : player.getInventory().getItems()) {
            if (item == null || item.hasAttributes()) continue;
            int id = item.getId();
            int owned = acquiredOre.getOrDefault(id, 0);
            if (owned == 0) continue;
            int amount = AutomationPolicy.depositAmount(item.getAmount(), owned,
                    protectedOre.getOrDefault(id, 0), player.getInventory().getAmount(id));
            if (amount == 0) continue;
            int moved = player.getBank().deposit(item, amount, false);
            if (moved > 0) {
                acquiredOre.put(id, owned - moved);
                deposited += moved;
            }
        }
        return deposited;
    }

    private String reject(long tick) {
        if (destination != null)
            rejected.put(destination.object, tick + EXCLUDE_TICKS);
        player.getMovement().reset();
        destination = null;
        nextScan = tick + RESCAN_TICKS;
        activity = "Trying another reachable target";
        return budget.retry() ? null : "Several targets were unreachable. Move nearer and restart.";
    }

    private static boolean live(Candidate candidate) {
        GameObject object = candidate.object;
        return !object.isRemoved() && object.id == candidate.id
                && Tile.getObject(candidate.id, object.x, object.y, object.z, object.type, object.direction) == object;
    }

    private static ObjectAction lookup(GameObject object, int index) {
        ObjectAction[] specific = object.actions;
        if (specific != null && specific[index] != null)
            return specific[index];
        ObjectAction[] fallback = object.getDef().defaultActions;
        return fallback == null ? null : fallback[index];
    }

    private static int distance(int ax, int ay, int bx, int by) {
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }

    private static final class Candidate {
        final GameObject object;
        final int id, option;
        final Rock rock;
        Candidate(GameObject object, int id, int option, Rock rock) {
            this.object = object;
            this.id = id;
            this.option = option;
            this.rock = rock;
        }
    }
}
