package io.ruin.model.entity.player.autonomous;

import io.ruin.cache.ItemDef;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.dialogue.skill.SkillDialogue;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemObjectAction;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.shop.Shop;
import io.ruin.model.skills.fishing.FishingArea;
import io.ruin.model.skills.fishing.FishingSpot;
import io.ruin.network.incoming.handlers.ActionButtonHandler;
import rsps.sim.ProgressionBrain.ActivityStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Food production through the same shop, fishing, bank and cooking actions as players. */
final class FishingCookingActivities {
    static final int NET = 303;
    static final int[] RAW = {317, 321};
    static final int[] COOKED = {315, 319};
    private static final int RAW_BATCH = 10;
    private static final String FISHING_SHOP = "4ea4cb73-305a-445c-a7ab-131a90a1d8b5";
    private static final List<NPC> spots = new ArrayList<>();
    private static final List<GameObject> ranges = new ArrayList<>();
    private static NPC garret;

    private final Adventurer p;
    private final KronosActivityPort port;
    private GameObject cookingTarget;
    private int cookingRawId = -1;
    private int rangeAttempts;
    private SkillDialogue pendingCookingDialogue;

    FishingCookingActivities(Adventurer p, KronosActivityPort port) {
        this.p = p;
        this.port = port;
    }

    static void prepareCatalogue() {
        spots.clear();
        ranges.clear();
        garret = null;
        // EntityList's indexed iterator is populated by the first world tick;
        // startup cataloguing must read already-spawned entities directly.
        for (NPC n : World.npcs.entityList) {
            if (n == null) continue;
            if (n.getId() == 2891 && n.getAbsX() == 2037 && n.getAbsY() == 3594 && n.getHeight() == 0)
                garret = n;
            if (n.getId() == FishingSpot.NET_BAIT
                    && (n.fishingArea == FishingArea.HOME || n.fishingArea == FishingArea.CATHERBY))
                spots.add(n);
        }
        for (TrainingSites.Site site : TrainingSites.sites) {
            if (!site.name.equals("Home") && !site.name.equals("Catherby")) continue;
            for (int x = site.x - site.radius; x <= site.x + site.radius; x++) {
                for (int y = site.y - site.radius; y <= site.y + site.radius; y++) {
                    Tile tile = Tile.get(x, y, 0);
                    if (tile == null || tile.gameObjects == null) continue;
                    for (GameObject object : tile.gameObjects) {
                        if (isCookingRange(object) && !ranges.contains(object)) ranges.add(object);
                    }
                }
            }
        }
    }

    private static boolean isCookingRange(GameObject object) {
        if (object.id < 0) return false;
        String name = object.getDef().name.toLowerCase(Locale.ROOT);
        return (name.equals("range") || name.equals("cooking range") || name.equals("stove"))
                && object.getDef().itemActions != null
                && object.getDef().itemActions.containsKey(RAW[0])
                && object.getDef().itemActions.containsKey(RAW[1]);
    }

    boolean canFish() {
        if (rawCount(false) >= RAW_BATCH || p.getInventory().getFreeSlots() < 2 || findSpot() == null)
            return false;
        // Enough money is only a candidate check; the real shop enforces price and stock.
        return p.getInventory().hasId(NET) || p.getBank().getAmount(NET) > 0
                || (garret != null && (long) p.getInventory().getAmount(995) + p.getBank().getAmount(995)
                >= Math.max(1, ItemDef.get(NET).highAlchValue));
    }

    boolean canCook() {
        return rawCount(true) > 0 && findRange() != null;
    }

    static boolean keepInInventory(int id) {
        return id == NET || id == COOKED[0] || id == COOKED[1];
    }

    int rawCount(boolean includeBank) {
        int count = 0;
        for (int id : RAW) count += p.getInventory().getAmount(id) + (includeBank ? p.getBank().getAmount(id) : 0);
        return count;
    }

    ActivityStatus fish(long tick) {
        if (rawCount(false) >= RAW_BATCH || (rawCount(false) > 0 && p.getInventory().getFreeSlots() < 2)) {
            p.resetActions(true, true, true);
            port.defer(tick + 1, "caught a batch of fish for cooking");
            return ActivityStatus.COMPLETE;
        }
        if (p.getInventory().getFreeSlots() < 2) return clearSpace(tick);
        if (!p.getInventory().hasId(NET)) return obtainNet(tick);
        NPC spot = findSpot();
        if (spot == null) {
            port.defer(tick + 4, "blocked: no unoccupied safe net fishing spot");
            return ActivityStatus.BLOCKED;
        }
        GameObject reservation = reservation(spot);
        if (!TrainingSites.claim(p, reservation)) return ActivityStatus.BLOCKED;
        int option = KronosActivityPort.option(spot.getDef().options, "small net");
        if (option < 1) return ActivityStatus.BLOCKED;
        port.defer(tick, "net fishing for food");
        port.interact(spot, option, tick);
        return ActivityStatus.RUNNING;
    }

    private ActivityStatus obtainNet(long tick) {
        if (p.getBank().getAmount(NET) > 0) {
            port.withdrawOwned(NET, 2, tick); // Exact one, regardless of default quantity.
            return ActivityStatus.RUNNING;
        }
        if (garret == null || garret.isRemoved()) {
            port.defer(tick + 4, "blocked: fishing supplier missing");
            return ActivityStatus.BLOCKED;
        }
        if (p.getInventory().getAmount(995) < Math.max(1, ItemDef.get(NET).highAlchValue)) {
            if (p.getBank().getAmount(995) > 0) {
                port.withdrawOwned(995, 7, tick);
                return ActivityStatus.RUNNING;
            }
            port.defer(tick + 4, "blocked: earn coins before buying a fishing net");
            return ActivityStatus.BLOCKED;
        }
        Shop shop = p.viewingShop;
        if (shop != null && FISHING_SHOP.equals(shop.identifier)
                && p.getPosition().isWithinDistance(garret.getPosition(), 2)
                && io.ruin.model.map.route.routes.ProjectileRoute.allow(p, garret)) {
            shop.buyFromShop(p, new Item(NET, 1));
            p.closeInterfaces();
            boolean bought = p.getInventory().hasId(NET);
            port.defer(tick + 4, bought ? "purchased fishing net from Garret" : "blocked: fishing net unaffordable or out of stock");
            return bought ? ActivityStatus.RUNNING : ActivityStatus.BLOCKED;
        }
        int trade = KronosActivityPort.option(garret.getDef().options, "trade");
        if (trade < 1) return ActivityStatus.BLOCKED;
        ActivityStatus entrance = enterRoom(tick, "fishing supply", 7452, 2037, 3597, 2037, 3598,
                2035, 3593, 2039, 3596);
        if (entrance != null) return entrance;
        port.defer(tick, "walking to fishing supplier");
        port.interact(garret, trade, tick);
        return ActivityStatus.RUNNING;
    }

    ActivityStatus cook(long tick) {
        if (handleCookingDialogue(tick)) return ActivityStatus.RUNNING;
        Item raw = firstRaw();
        if (raw == null) {
            if (rawCount(true) == 0) {
                cookingTarget = null;
                cookingRawId = -1;
                port.defer(tick + 1, "finished cooking caught fish");
                return ActivityStatus.COMPLETE;
            }
            if (p.getInventory().getFreeSlots() < 2) return clearSpace(tick);
            for (int id : RAW) {
                if (p.getBank().getAmount(id) > 0) {
                    port.withdrawOwned(id, 4, tick); // Existing withdraw-ten action.
                    break;
                }
            }
            return ActivityStatus.RUNNING;
        }
        GameObject range = findRange();
        if (range == null || !TrainingSites.claim(p, range)) {
            port.defer(tick + 4, "blocked: no unoccupied cooking range");
            return ActivityStatus.BLOCKED;
        }
        port.markRoute(range);
        if (range.z == 0 && range.y == 3594 && (range.x == 2018 || range.x == 2020)) {
            ActivityStatus entrance = enterRoom(tick, "home kitchen", 7452, 2019, 3589, 2019, 3588,
                    2017, 3590, 2021, 3594);
            if (entrance != null) return entrance;
        } else if (range.z == 0 && range.x == 2818 && range.y == 3455) {
            ActivityStatus entrance = enterRoom(tick, "Catherby north kitchen", 1535, 2815, 3448, 2815, 3447,
                    2812, 3449, 2820, 3455);
            if (entrance != null) return entrance;
        }
        if (TrainingSites.travel(p, range.x, range.y, range.z)) {
            port.defer(tick + 10, "travelling to cooking range");
            return ActivityStatus.RUNNING;
        }
        if (cookingTarget == range && ++rangeAttempts > 3) {
            p.memory.routeFailed(range.originalId + "@" + range.x + "," + range.y);
            rangeAttempts = 0;
            return ActivityStatus.BLOCKED;
        }
        if (cookingTarget != range) rangeAttempts = 0;
        cookingTarget = range;
        cookingRawId = raw.getId();
        final int rawId = cookingRawId;
        p.resetActions(true, true, true);
        p.getRouteFinder().routeObject(range, () -> {
            Item stillOwned = p.getInventory().findItem(rawId);
            if (stillOwned == null || range.id < 0 || !isCookingRange(range)) return;
            rangeAttempts = 0;
            ItemObjectAction.handleAction(p, stillOwned, range);
            pendingCookingDialogue = p.skillDialogue;
        });
        port.defer(tick + 4, "cooking caught fish at " + range.getDef().name);
        return ActivityStatus.RUNNING;
    }

    /** Select only the cooking dialogue that this helper opened, before active-action gating. */
    boolean handleCookingDialogue(long tick) {
        if (cookingTarget == null || cookingRawId < 0 || pendingCookingDialogue == null
                || p.skillDialogue != pendingCookingDialogue
                || !p.isVisibleInterface(Interface.MAKE_DIALOGUE)) return false;
        int amount = p.getInventory().getAmount(cookingRawId);
        if (amount < 1) return false;
        ActionButtonHandler.handleAction(p, 1, (Interface.MAKE_DIALOGUE << 16) | 14,
                Math.min(28, amount), cookingRawId, false);
        pendingCookingDialogue = null;
        port.defer(tick + 4, "cooking caught fish");
        return true;
    }

    /** Null means ordinary target routing can continue; never close an open door. */
    private ActivityStatus enterRoom(long tick, String room, int closedDoorId, int doorX, int doorY, int outsideX, int outsideY,
                                     int minX, int minY, int maxX, int maxY) {
        if (p.getHeight() == 0 && p.getAbsX() >= minX && p.getAbsX() <= maxX
                && p.getAbsY() >= minY && p.getAbsY() <= maxY) return null;
        Tile tile = Tile.get(doorX, doorY, 0);
        GameObject closedDoor = null;
        int open = -1;
        if (tile != null && tile.gameObjects != null) {
            for (GameObject object : tile.gameObjects) {
                if (object.id != closedDoorId || object.type != 0) continue;
                int option = KronosActivityPort.option(object.getDef().options, "open");
                if (option > 0) { closedDoor = object; open = option; break; }
            }
        }
        if (closedDoor == null) return null;
        if (!TrainingSites.safeToWork(p, outsideX, outsideY, 0)
                || !TrainingSites.canTravelTo(p, outsideX, outsideY, 0)) {
            port.defer(tick + 4, "blocked: unsafe " + room + " entrance");
            return ActivityStatus.BLOCKED;
        }
        port.markRoute(closedDoor);
        if (TrainingSites.travel(p, outsideX, outsideY, 0)) {
            port.defer(tick + 10, "travelling to " + room + " entrance");
            return ActivityStatus.RUNNING;
        }
        if (p.getAbsX() != outsideX || p.getAbsY() != outsideY || p.getHeight() != 0) {
            p.resetActions(true, true, true);
            long started = System.nanoTime();
            io.ruin.model.map.route.RouteType route = p.getRouteFinder().routeAbsolute(outsideX, outsideY);
            port.routes++;
            port.slowestRouteNanos = Math.max(port.slowestRouteNanos, System.nanoTime() - started);
            if (!route.reachable) {
                p.getMovement().reset();
                p.memory.routeFailed(closedDoor.originalId + "@" + doorX + "," + doorY);
                port.defer(tick + 4, "blocked: " + room + " entrance is unreachable");
                return ActivityStatus.BLOCKED;
            }
            port.defer(tick + 8, "walking to " + room + " entrance");
            return ActivityStatus.RUNNING;
        }
        port.defer(tick, "opening " + room + " door");
        port.interact(closedDoor, open, tick);
        return ActivityStatus.RUNNING;
    }

    private ActivityStatus clearSpace(long tick) {
        if (!port.bankSurplus(tick, -1)) return ActivityStatus.RUNNING;
        return p.getInventory().getFreeSlots() >= 2 ? ActivityStatus.RUNNING : ActivityStatus.BLOCKED;
    }

    private Item firstRaw() {
        for (int id : RAW) {
            Item item = p.getInventory().findItem(id);
            if (item != null) return item;
        }
        return null;
    }

    private NPC findSpot() {
        NPC best = null;
        int bestScore = Integer.MAX_VALUE;
        for (NPC n : spots) {
            if (n.isRemoved() || TrainingSites.at(n.getAbsX(), n.getAbsY(), n.getHeight()) == null
                    || !TrainingSites.safeToWork(p, n.getAbsX(), n.getAbsY(), n.getHeight())) continue;
            GameObject target = reservation(n);
            if (!TrainingSites.available(p, target)) continue;
            int score = score(target, KronosActivityPort.npcRouteKey(n));
            if (score < bestScore) { best = n; bestScore = score; }
        }
        return best;
    }

    private GameObject findRange() {
        GameObject best = null;
        int bestScore = Integer.MAX_VALUE;
        for (GameObject range : ranges) {
            if (!isCookingRange(range) || !TrainingSites.available(p, range)
                    || !TrainingSites.safeToWork(p, range.x, range.y, range.z)) continue;
            int score = score(range);
            if (score < bestScore) { best = range; bestScore = score; }
        }
        return best;
    }

    private int score(GameObject target) {
        return score(target, target.originalId + "@" + target.x + "," + target.y);
    }

    private int score(GameObject target, String routeKey) {
        return TrainingSites.preference(p, target)
                + Math.min(5000, Math.max(Math.abs(p.getAbsX() - target.x), Math.abs(p.getAbsY() - target.y))) * 8
                + p.memory.failedRoutes.getOrDefault(routeKey, 0) * 100000;
    }

    private static GameObject reservation(NPC npc) {
        return new GameObject(npc.getId(), npc.getAbsX(), npc.getAbsY(), npc.getHeight(), 0, 0);
    }
}
