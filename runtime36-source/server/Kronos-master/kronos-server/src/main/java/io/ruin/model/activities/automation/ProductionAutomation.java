package io.ruin.model.activities.automation;

import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.actions.DefaultAction;
import io.ruin.model.inter.dialogue.skill.SkillDialogue;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.actions.ItemItemAction;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.skills.Tool;
import io.ruin.model.skills.cooking.Cooking;
import io.ruin.model.skills.cooking.Food;
import io.ruin.model.skills.crafting.Gem;
import io.ruin.model.skills.firemaking.Burning;
import io.ruin.model.skills.fletching.LogCutting;
import io.ruin.model.skills.fletching.StringBow;
import io.ruin.model.skills.herblore.Herb;
import io.ruin.model.skills.herblore.Potion;
import io.ruin.model.skills.prayer.Bone;
import io.ruin.model.skills.smithing.SmeltBar;
import io.ruin.model.skills.smithing.SmithBar;
import io.ruin.model.stat.StatType;
import io.ruin.process.event.Event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Trains from supplies already in the pack through bounded, native production actions. */
final class ProductionAutomation implements AutomationDriver {
    private static final int STATION_RADIUS = 8;
    private static final int ACTION_TICKS = 72;
    private static final int BOW_STRING = 1777;
    private static final Map<StatType, String> SUPPLIES = new EnumMap<>(StatType.class);

    static {
        SUPPLIES.put(StatType.Cooking, "Bring cookable food and stand near a range or stove");
        SUPPLIES.put(StatType.Smithing, "Bring an eligible combination of ores to a furnace");
        SUPPLIES.put(StatType.Fletching, "Bring a knife and logs, or unstrung bows and bow strings");
        SUPPLIES.put(StatType.Crafting, "Bring a chisel and level-eligible uncut gems");
        SUPPLIES.put(StatType.Herblore, "Bring eligible grimy herbs or matching unfinished potions and secondaries");
        SUPPLIES.put(StatType.Firemaking, "Bring a tinderbox and eligible logs to open ground");
        SUPPLIES.put(StatType.Prayer, "Bring unnoted bones");
    }

    private final Player player;
    private final StatType skill;
    private final int goal, plane;
    private final AutomationActionSource actions;
    private final Set<GameObject> failedStations = Collections.newSetFromMap(new IdentityHashMap<>());
    private GameObject station;
    private int stationId, finishX, finishY;
    private long readyAt, deadline;
    private Event event;
    private boolean expectsNativeLock, finishFireStep;
    private Recipe dispatched;
    private int previousInput;
    private double previousXp;
    private String activity = "Checking supplies";

    ProductionAutomation(Player player, StatType skill, int goal, long tick) {
        if (!supports(skill)) throw new IllegalArgumentException("Unsupported production skill: " + skill);
        this.player = player;
        this.skill = skill;
        this.goal = goal;
        this.plane = player.getHeight();
        readyAt = tick;
        actions = (AutomationActionSource) (Object) player;
    }

    static boolean supports(StatType skill) {
        return SUPPLIES.containsKey(skill);
    }

    @Override
    public String activity() { return activity; }

    @Override
    public String pulse(long tick) {
        if (player.getStats().get(skill).fixedLevel >= goal)
            return skill + " goal reached";
        if (player.getHeight() != plane)
            return "Moved away from the production area";
        if (event != null) {
            Event current = actions.ownerAutomationAction();
            if (event == current)
                return tick >= deadline ? "Native action did not finish. Check the work area." : null;
            event = null;
            expectsNativeLock = false;
            if (current != null) return "Another action took control";
            if (!changed()) return "Action made no progress. Check supplies and level requirements.";
        }
        if (player.hasActiveAction()) return "Another action took control";
        if (finishFireStep && !player.getMovement().isAtDestination())
            return tick >= deadline ? "The native step away from the fire did not finish" : null;
        finishFireStep = false;
        if (station != null) {
            if (!stationPresent() || tick >= deadline) return rejectStation(tick);
            if (!player.getMovement().isAtDestination()) return null;
            if (!player.isAt(finishX, finishY)) return rejectStation(tick);
            RouteType approach = player.getRouteFinder().routeObject(station);
            if (!approach.reachable || !approach.finished(player.getPosition())) return rejectStation(tick);
        }
        if (!player.getMovement().isAtDestination()) return "Another movement took control";
        if (tick < readyAt) return null;

        Recipe next = bestRecipe();
        if (next == null) return SUPPLIES.get(skill);
        if (skill == StatType.Firemaking && (plane != 0 || !Tile.allowObjectPlacement(player.getPosition())))
            return "Move to clear ground on the surface to light a fire";
        if (requiresStation() && station == null) {
            station = nearestStation(next.input);
            if (station == null) return skill == StatType.Cooking
                    ? "No reachable cooking range or stove within 8 tiles" : "No reachable furnace within 8 tiles";
            stationId = station.id;
            RouteType approach = player.getRouteFinder().routeObject(station);
            if (!approach.reachable) return rejectStation(tick);
            if (!approach.finished(player.getPosition())) {
                finishX = approach.finishX;
                finishY = approach.finishY;
                deadline = tick + AutomationPolicy.ROUTE_TICKS;
                activity = skill == StatType.Cooking ? "Walking to the range" : "Walking to the furnace";
                return null;
            }
        }

        previousInput = amount(next.input);
        previousXp = player.getStats().get(skill).experience;
        dispatched = next;
        GameObject object = station;
        station = null;
        if (object != null) player.face(object);
        SkillDialogue oldDialogue = player.skillDialogue;
        next.action.run(object);
        // Gem cutting, bow stringing and standard potions each offer one exact native product.
        if (player.skillDialogue != null && player.skillDialogue != oldDialogue) {
            Object handler = InterfaceHandler.getAction(player, Interface.MAKE_DIALOGUE, 14);
            if (!next.singleMakeDialogue || !(handler instanceof DefaultAction)) {
                player.closeDialogue();
                return "Native crafting selection changed. Complete this recipe manually.";
            }
            ((DefaultAction) handler).handleClick(player, 1, 1, -1);
        }
        event = actions.ownerAutomationAction();
        expectsNativeLock = next.nativeLock && event != null;
        finishFireStep = skill == StatType.Firemaking && event != null;
        if (event == null && !changed())
            return "Action made no progress. Check supplies and station requirements.";
        deadline = tick + ACTION_TICKS;
        readyAt = tick + 2;
        activity = next.description;
        failedStations.clear();
        return null;
    }

    @Override
    public boolean ownsNativeLock() {
        return expectsNativeLock && event != null && actions.ownerAutomationAction() == event;
    }

    @Override
    public void stop() {
        if (event != null && !player.isLocked() && actions.cancelOwnerAutomationAction(event))
            player.resetAnimation();
        // A native locked single-action tail must release its own lock. Cancelling it
        // there can leave the owner unable to move or leave an in-flight fire on the ground.
        event = null;
        expectsNativeLock = false;
        finishFireStep = false;
        if (station != null) player.getMovement().reset();
        station = null;
    }

    private boolean changed() {
        return amount(dispatched.input) != previousInput || player.getStats().get(skill).experience > previousXp;
    }

    private boolean requiresStation() {
        return skill == StatType.Cooking || skill == StatType.Smithing;
    }

    private boolean stationPresent() {
        return !station.isRemoved() && station.id == stationId
                && Tile.getObject(stationId, station.x, station.y, station.z, station.type, station.direction) == station;
    }

    private String rejectStation(long tick) {
        failedStations.add(station);
        station = null;
        player.getMovement().reset();
        readyAt = tick + 2;
        return failedStations.size() < AutomationPolicy.MAX_ROUTE_FAILURES ? null
                : "Production stations are obstructed. Move beside a working station.";
    }

    private GameObject nearestStation(int ingredient) {
        GameObject nearest = null;
        int best = STATION_RADIUS + 1;
        int x = player.getAbsX(), y = player.getAbsY();
        for (int dx = -STATION_RADIUS; dx <= STATION_RADIUS; dx++) {
            for (int dy = -STATION_RADIUS; dy <= STATION_RADIUS; dy++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                if (distance >= best || x + dx < 1 || x + dx >= 8192 || y + dy < 1 || y + dy >= 16383)
                    continue;
                Tile tile = Tile.get(x + dx, y + dy, plane);
                if (tile == null || tile.gameObjects == null) continue;
                for (GameObject candidate : tile.gameObjects) {
                    if (candidate.isRemoved() || failedStations.contains(candidate)) continue;
                    ObjectDef def = candidate.getDef();
                    if (def == null || def.name == null || def.varpBitId != -1 || def.varpId != -1) continue;
                    String name = def.name.toLowerCase(Locale.ROOT);
                    if (skill == StatType.Cooking) {
                        if (!(name.equals("range") || name.equals("cooking range") || name.equals("stove"))
                                || candidate.defaultItemAction != null || candidate.itemActions != null
                                || def.itemActions == null || !def.itemActions.containsKey(ingredient))
                            continue;
                    } else {
                        if (!(name.equals("furnace") || name.equals("lava forge"))
                                || smeltAction(candidate, def) == null)
                            continue;
                    }
                    nearest = candidate;
                    best = distance;
                    break;
                }
            }
        }
        return nearest;
    }

    private static ObjectAction smeltAction(GameObject object, ObjectDef def) {
        int index = def.getOption("smelt") - 1;
        if (index < 0) return null;
        if (object.actions != null && index < object.actions.length && object.actions[index] != null)
            return object.actions[index];
        return def.defaultActions == null || index >= def.defaultActions.length ? null : def.defaultActions[index];
    }

    private Recipe bestRecipe() {
        switch (skill) {
            case Cooking: return food();
            case Smithing: return smelting();
            case Fletching: return fletching();
            case Crafting: return gems();
            case Herblore: return herbs();
            case Prayer: return bones();
            case Firemaking: return logs();
            default: return null;
        }
    }

    private Recipe food() {
        Recipe result = null;
        int level = currentLevel();
        for (Food food : Food.values()) {
            if (!food.name().startsWith("RAW_") || food == Food.RAW_MOLTEN_EEL
                    || food == Food.RAW_MEAT || food == Food.RAW_HERRING || level < food.levelRequirement
                    || plain(food.rawID) == null)
                continue;
            int until = Math.max(1, food.burnLevelRange - food.levelRequirement);
            double success = .48 + .52 * Math.min(1, (level - food.levelRequirement) / (double) until);
            result = better(result, food.rawID, food.experience * success, "Cooking " + food.rawName,
                    false, false, object -> NativeCooking.start(player, food, object));
        }
        return result;
    }

    private Recipe smelting() {
        Recipe result = null;
        int level = currentLevel();
        for (SmithBar bar : SmithBar.values()) {
            if (level < bar.smeltLevel || bar.itemId == 30112) continue;
            boolean available = true;
            for (Item component : bar.smeltItems) {
                if (plain(component.getId()) == null || amount(component.getId()) < component.getAmount()) {
                    available = false;
                    break;
                }
            }
            if (!available || bar.smeltItems.isEmpty()) continue;
            result = better(result, bar.smeltItems.get(0).getId(), bar.smeltXp,
                    "Smelting " + bar.name().toLowerCase(Locale.ROOT) + " bars", false, false,
                    object -> SmeltBar.smelt(player, bar, 1));
        }
        return result;
    }

    private Recipe fletching() {
        Recipe result = null;
        int level = currentLevel();
        if (plain(Tool.KNIFE) != null) {
            for (LogCutting log : LogCutting.values()) {
                if (level < log.levelReq || log == LogCutting.MAGIC_STAFF || log.logID == 30105
                        || plain(log.logID) == null) continue;
                result = better(result, log.logID, log.exp, "Cutting " + log.name, false, true,
                        object -> LogCutting.cut(player, log, 1));
            }
        }
        if (plain(BOW_STRING) != null) {
            for (StringBow bow : StringBow.values()) {
                if (level < bow.levelReq || bow.unstrung >= 30100 || plain(bow.unstrung) == null) continue;
                result = betterPair(result, bow.unstrung, BOW_STRING, bow.exp, "Stringing a bow", true, false);
            }
        }
        return result;
    }

    private Recipe gems() {
        Recipe result = null;
        if (plain(Tool.CHISEL) == null) return null;
        int level = currentLevel();
        for (Gem gem : Gem.values()) {
            if (level < gem.lvlReq || plain(gem.uncutId) == null) continue;
            result = betterPair(result, gem.uncutId, Tool.CHISEL, gem.xp,
                    "Cutting " + gem.pluralName, true, false);
        }
        return result;
    }

    private Recipe herbs() {
        Recipe result = null;
        int level = currentLevel();
        for (Herb herb : Herb.values()) {
            if (level < herb.lvlReq || plain(herb.grimyId) == null) continue;
            result = betterItem(result, herb.grimyId, herb.xp, "Cleaning herbs", "clean");
        }
        for (Potion potion : Potion.values()) {
            if (potion.raidsPotion || level < potion.lvlReq || potion.secondaryNames.length != 1
                    || !potion.primaryName.endsWith(" (unf)") || potion.vialIds[2] == 0) continue;
            Item primary = named(potion.primaryName);
            Item secondary = named(potion.secondaryNames[0]);
            if (primary == null || secondary == null) continue;
            result = betterPair(result, primary.getId(), secondary.getId(), potion.xp,
                    "Mixing " + potion.potionName, true, false);
        }
        return result;
    }

    private Recipe bones() {
        Recipe result = null;
        for (Bone bone : Bone.values()) {
            if (bone == Bone.LONG_BONE || bone == Bone.CURVED_BONE || plain(bone.id) == null) continue;
            result = betterItem(result, bone.id, bone.exp, "Burying bones", "bury");
        }
        return result;
    }

    private Recipe logs() {
        Recipe result = null;
        if (plain(Tool.TINDER_BOX) == null) return null;
        int level = currentLevel();
        for (Burning log : Burning.values()) {
            if (log == Burning.KINDLING || log == Burning.CORRUPT_LOGS || level < log.levelReq
                    || plain(log.itemId) == null) continue;
            result = betterPair(result, log.itemId, Tool.TINDER_BOX, log.exp, "Lighting a fire", false, true);
        }
        return result;
    }

    private Recipe betterItem(Recipe result, int input, double xp, String description, String option) {
        Item item = plain(input);
        if (item == null || item.getDef().inventoryActions == null) return result;
        int index = item.getDef().getOption(option) - 1;
        if (index < 0 || index >= item.getDef().inventoryActions.length) return result;
        ItemAction action = item.getDef().inventoryActions[index];
        return action == null ? result : better(result, input, xp, description, false, false,
                object -> action.handle(player, item));
    }

    private Recipe betterPair(Recipe result, int input, int second, double xp, String description,
                              boolean singleMakeDialogue, boolean nativeLock) {
        Item firstItem = plain(input), secondItem = plain(second);
        if (firstItem == null || secondItem == null) return result;
        ItemItemAction nativeAction = ItemItemAction.match(firstItem.getDef().primaryItemItemActions,
                secondItem.getDef().secondaryItemItemActions);
        if (nativeAction != null)
            return better(result, input, xp, description, singleMakeDialogue, nativeLock,
                    object -> nativeAction.handle(player, firstItem, secondItem));
        ItemItemAction reverse = ItemItemAction.match(secondItem.getDef().primaryItemItemActions,
                firstItem.getDef().secondaryItemItemActions);
        if (reverse == null) return result;
        return better(result, input, xp, description, singleMakeDialogue, nativeLock,
                object -> reverse.handle(player, secondItem, firstItem));
    }

    private static Recipe better(Recipe current, int input, double xp, String description,
                                 boolean singleMakeDialogue, boolean nativeLock, NativeAction action) {
        if (current != null && xp <= current.xp) return current;
        return new Recipe(input, xp, description, singleMakeDialogue, nativeLock, action);
    }

    private Item plain(int id) {
        Item item = player.getInventory().findItem(id);
        return item != null && item.getDef() != null && !item.getDef().isNote() && !item.hasAttributes() ? item : null;
    }

    private Item named(String name) {
        for (Item item : player.getInventory().getItems()) {
            if (item != null && !item.hasAttributes() && item.getDef() != null && !item.getDef().isNote()
                    && name.equalsIgnoreCase(item.getDef().name)) return item;
        }
        return null;
    }

    private int amount(int id) { return player.getInventory().getAmount(id); }
    private int currentLevel() { return player.getStats().get(skill).currentLevel; }

    @FunctionalInterface
    private interface NativeAction { void run(GameObject station); }

    private static final class Recipe {
        final int input;
        final double xp;
        final String description;
        final boolean singleMakeDialogue, nativeLock;
        final NativeAction action;

        Recipe(int input, double xp, String description, boolean singleMakeDialogue, boolean nativeLock, NativeAction action) {
            this.input = input;
            this.xp = xp;
            this.description = description;
            this.singleMakeDialogue = singleMakeDialogue;
            this.nativeLock = nativeLock;
            this.action = action;
        }
    }

    /** The selected Cooking binding wraps this method in a second queued event after Make. */
    private static final class NativeCooking {
        private static final Method START = startMethod();

        private static Method startMethod() {
            try {
                Method method = Cooking.class.getDeclaredMethod("startCooking", Player.class, Food.class,
                        GameObject.class, int.class, int.class, boolean.class);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Selected native cooking signature has changed", failure);
            }
        }

        static void start(Player player, Food food, GameObject object) {
            try {
                START.invoke(null, player, food, object, 1, 896, false);
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("Native cooking failed", failure.getCause());
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Native cooking could not be linked", failure);
            }
        }
    }
}
