package io.ruin.model.activities.automation;

import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.map.object.actions.ObjectAction;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import io.ruin.model.skills.farming.Farming;
import io.ruin.model.skills.farming.crop.Crop;
import io.ruin.model.skills.farming.crop.TreeCrop;
import io.ruin.model.skills.farming.patch.Patch;
import io.ruin.model.skills.farming.patch.PatchData;
import io.ruin.model.skills.farming.patch.PatchGroup;
import io.ruin.model.skills.farming.patch.impl.AllotmentPatch;
import io.ruin.model.skills.farming.patch.impl.FlowerPatch;
import io.ruin.model.skills.farming.patch.impl.HerbPatch;
import io.ruin.model.skills.farming.patch.impl.HopsPatch;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

/** Runs one locally reachable native crop patch, then yields control during real growth. */
final class FarmingAutomation implements AutomationDriver {
    private static final int SCAN_RADIUS = 12;
    private static final int RAKE = 5341, DIBBER = 5343, SPADE = 952, PLANT_CURE = 6036;
    private static final int COMPOST = 6032, SUPERCOMPOST = 6034;

    private final Player player;
    private final AutomationActionSource events;
    private final int goal;
    private final AutomationPolicy.Budget budget;
    private final Set<GameObject> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
    private Candidate selected;
    private Event action;
    private Job pendingJob;
    private long snapshot, routeDeadline;
    private int finishX, finishY;
    private boolean moving;
    private String activity = "Looking for a registered crop patch";

    FarmingAutomation(Player player, int goal, long tick) {
        this.player = player;
        this.goal = goal;
        this.budget = new AutomationPolicy.Budget(tick);
        this.events = (AutomationActionSource) (Object) player;
    }

    @Override
    public String activity() { return activity; }

    @Override
    public String pulse(long tick) {
        if (player.getStats().get(StatType.Farming).fixedLevel >= goal)
            return "Farming goal reached";
        if (player.getFarming() == null) return "Go to a registered farming area";
        if (action != null) {
            if (events.ownerAutomationAction() == action)
                return budget.expired(tick) ? "The native Farming action did not finish" : null;
            action = null;
            if (!live(selected) || snapshot == progress(selected.patch))
                return "The Farming action made no progress. Check the patch and your supplies.";
            budget.progress(tick);
            if (pendingJob == Job.PLANT) selected = null;
            pendingJob = null;
        }
        if (moving) return continueRoute(tick);
        if (player.hasActiveAction() || !player.getMovement().isAtDestination())
            return "Another action took control";
        if (selected == null) {
            Farming farming = player.getFarming();
            PatchGroup group = farming.getVisibleGroup();
            if (group == null || !player.getPosition().inBounds(group.getBounds()))
                return "Go to a registered allotment, herb, flower, or hops patch first";
            selected = choose(group);
            if (selected == null)
                return "No supported native crop patch within 12 tiles";
        }
        if (!live(selected)) return "The selected Farming patch is no longer available";
        Job job = nextJob(selected.patch);
        if (job == Job.WAIT) return "This crop is still growing. Return when it is mature.";
        if (job == Job.BLOCKED) return blocker(selected.patch);
        RouteType route = route();
        if (!route.reachable) {
            return rejectRoute();
        }
        if (route.finished(player.getPosition())) return perform(tick);
        finishX = route.finishX;
        finishY = route.finishY;
        routeDeadline = tick + AutomationPolicy.ROUTE_TICKS;
        moving = true;
        activity = "Walking to the selected Farming patch";
        return null;
    }

    @Override
    public void stop() {
        if (action != null && events.cancelOwnerAutomationAction(action))
            player.resetAnimation();
        if (moving) player.getMovement().reset();
        moving = false;
        action = null;
    }

    private String continueRoute(long tick) {
        if (tick >= routeDeadline || !live(selected)) return rejectRoute();
        if (!player.getMovement().isAtDestination()) return null;
        if (!player.isAt(finishX, finishY)) return rejectRoute();
        RouteType route = route();
        if (!route.reachable || !route.finished(player.getPosition()))
            return rejectRoute();
        moving = false;
        return perform(tick);
    }

    private String rejectRoute() {
        rejected.add(selected.object);
        selected = null;
        moving = false;
        player.getMovement().reset();
        return budget.retry() ? null : "Multiple Farming patch approaches are blocked";
    }

    private RouteType route() {
        GameObject object = selected.object;
        if (object.walkTo != null)
            return player.getRouteFinder().routeAbsolute(object.walkTo.getX(), object.walkTo.getY());
        return player.getRouteFinder().routeObject(object);
    }

    private String perform(long tick) {
        Patch patch = selected.patch;
        Job job = nextJob(patch);
        if (job == Job.WAIT) return "This crop is still growing. Return when it is mature.";
        if (job == Job.BLOCKED) return blocker(patch);
        snapshot = progress(patch);
        Event before = events.ownerAutomationAction();
        player.face(selected.object);
        if (job == Job.PLANT) {
            Item seed = bestSeed(patch);
            if (seed == null || !player.getFarming().itemOnPatch(seed, selected.object))
                return "The native crop patch rejected the seed";
        } else if (job == Job.COMPOST) {
            Item compost = plain(SUPERCOMPOST);
            if (compost == null) compost = plain(COMPOST);
            if (compost == null || !player.getFarming().itemOnPatch(compost, selected.object))
                return "The native crop patch rejected the compost";
        } else {
            if (nativeInteraction(selected.object) == null)
                return "No native Farming action is bound to this patch";
            player.getFarming().handleObject(selected.object, 1);
        }
        Event after = events.ownerAutomationAction();
        if (after == null || after == before) {
            if (snapshot == progress(patch))
                return "The Farming action made no progress. Check your supplies.";
            budget.progress(tick);
            return null;
        }
        action = after;
        pendingJob = job;
        activity = job.label;
        budget.progress(tick);
        return null;
    }

    private Candidate choose(PatchGroup group) {
        Candidate best = null;
        int bestScore = Integer.MIN_VALUE;
        int x = player.getAbsX(), y = player.getAbsY(), z = player.getHeight();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                int tx = x + dx, ty = y + dy;
                if (!insideMap(tx, ty)) continue;
                Tile tile = Tile.get(tx, ty, z);
                if (tile == null || tile.gameObjects == null) continue;
                for (GameObject object : tile.gameObjects) {
                    if (object.isRemoved() || rejected.contains(object) || !contains(group, object.id)) continue;
                    Patch patch = player.getFarming().getPatch(object);
                    if (!supported(patch)) continue;
                    int score = nextJob(patch).priority * 100 - Math.max(Math.abs(dx), Math.abs(dy));
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Candidate(object, patch, group);
                    }
                }
            }
        }
        return best;
    }

    private static boolean contains(PatchGroup group, int objectId) {
        for (PatchData patch : group.getPatches()) if (patch.getObjectId() == objectId) return true;
        return false;
    }

    private static boolean supported(Patch patch) {
        return patch instanceof AllotmentPatch || patch instanceof HerbPatch
                || patch instanceof FlowerPatch || patch instanceof HopsPatch;
    }

    private boolean live(Candidate candidate) {
        GameObject object = candidate.object;
        return player.getHeight() == object.z && player.getPosition().inBounds(candidate.group.getBounds())
                && player.getFarming().getPatch(object) == candidate.patch && !object.isRemoved()
                && object.id == candidate.id
                && Tile.getObject(candidate.id, object.x, object.y, object.z, object.type, object.direction) == object;
    }

    private Job nextJob(Patch patch) {
        Crop crop = patch.getPlantedCrop();
        if (patch.isDead()) return has(SPADE) ? Job.CLEAR : Job.BLOCKED;
        if (patch.isDiseased()) return has(PLANT_CURE) ? Job.CURE : Job.BLOCKED;
        if (crop != null) {
            if (patch.getStage() < crop.getTotalStages()) return Job.WAIT;
            boolean spadeRequired = patch instanceof AllotmentPatch || patch instanceof HopsPatch;
            return player.getInventory().getFreeSlots() > 0 && (!spadeRequired || has(SPADE))
                    ? Job.HARVEST : Job.BLOCKED;
        }
        if (!patch.isRaked())
            return has(RAKE) && player.getInventory().getFreeSlots() >= 3 - patch.getRaked()
                    ? Job.RAKE : Job.BLOCKED;
        if (!has(DIBBER) || bestSeed(patch) == null) return Job.BLOCKED;
        if (patch.getCompost() == 0 && (has(COMPOST) || has(SUPERCOMPOST))) return Job.COMPOST;
        return Job.PLANT;
    }

    private boolean has(int itemId) { return plain(itemId) != null; }

    private Item plain(int itemId) {
        Item item = player.getInventory().findItem(itemId);
        return item != null && item.getDef() != null && !item.getDef().isNote() && !item.hasAttributes() ? item : null;
    }

    private static ObjectAction nativeInteraction(GameObject object) {
        if (object.actions != null && object.actions.length != 0 && object.actions[0] != null)
            return object.actions[0];
        ObjectAction[] defaults = object.getDef().defaultActions;
        return defaults == null || defaults.length == 0 ? null : defaults[0];
    }

    private Item bestSeed(Patch patch) {
        int level = player.getStats().get(StatType.Farming).currentLevel;
        Crop best = null;
        Item seed = null;
        for (Item item : player.getInventory().getItems()) {
            if (item == null || item.getDef() == null || item.getDef().isNote() || item.hasAttributes()) continue;
            Crop crop = item.getDef().seedType;
            if (crop == null || crop instanceof TreeCrop || crop.getSeed() != item.getId()
                    || crop.getLevelReq() > level || !patch.canPlant(crop)) continue;
            if (player.getInventory().getAmount(item.getId()) < seedCount(patch, crop.getSeed())) continue;
            if (best == null || crop.getPlantXP() > best.getPlantXP()) {
                best = crop;
                seed = item;
            }
        }
        return seed;
    }

    static int seedCount(Patch patch, int seed) {
        if (patch instanceof AllotmentPatch) return 3;
        if (patch instanceof HopsPatch) return seed == 5306 ? 3 : 4;
        return 1;
    }

    private static long progress(Patch patch) {
        Crop crop = patch.getPlantedCrop();
        long stamp = crop == null ? 0 : crop.getSeed();
        stamp = stamp * 31 + patch.getStage();
        stamp = stamp * 31 + patch.getRaked();
        stamp = stamp * 31 + patch.getCompost();
        stamp = stamp * 31 + patch.getDiseaseStage();
        return stamp * 31 + patch.getProduceCount();
    }

    private static String blocker(Patch patch) {
        if (patch.isDead()) return "Bring a spade to clear the dead crop";
        if (patch.isDiseased()) return "Bring plant cure for the diseased crop";
        if (patch.getPlantedCrop() != null) return "Free inventory space and bring a spade if the crop requires one";
        if (!patch.isRaked()) return "Bring a rake and make room for weeds";
        return "Bring a seed dibber and enough level-eligible seeds for this patch";
    }

    private static boolean insideMap(int x, int y) { return x > 0 && x < 8192 && y > 0 && y < 16383; }

    private enum Job {
        HARVEST(6, "Harvesting the crop"),
        CLEAR(5, "Clearing a dead crop"),
        CURE(5, "Curing the crop"),
        RAKE(4, "Raking the patch"),
        COMPOST(3, "Treating the patch with compost"),
        PLANT(3, "Planting the crop"),
        WAIT(1, "Waiting for real crop growth"),
        BLOCKED(0, "Checking Farming supplies");

        final int priority;
        final String label;

        Job(int priority, String label) {
            this.priority = priority;
            this.label = label;
        }
    }

    private static final class Candidate {
        final GameObject object;
        final Patch patch;
        final PatchGroup group;
        final int id;

        Candidate(GameObject object, Patch patch, PatchGroup group) {
            this.object = object;
            this.patch = patch;
            this.group = group;
            this.id = object.id;
        }
    }
}
