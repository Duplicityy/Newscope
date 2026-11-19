package io.ruin.content.pets;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.model.World;
import io.ruin.model.activities.dungeoneering.Dungeoneering;
import io.ruin.model.activities.dungeoneering.DungeoneeringUtilityRewards;
import io.ruin.model.activities.hollowdepths.HollowDepths;
import io.ruin.model.activities.raids.tob.port.TheatreOfBloodRaid;
import io.ruin.model.activities.raids.toa.port.TombsOfAmascutRaid;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.map.route.routes.DumbRoute;
import io.ruin.utility.OfflineMode;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** World-thread follower and award actions for paired pet-definition clients. */
public final class UniversalPets {
    private static final int PAGE_SIZE = 40;
    private static final int SAVE_RESOLUTION_TICKS = 50;
    private static final Map<Player, NPC> FOLLOWERS = new IdentityHashMap<>();
    private static Registry registry;
    private static boolean registryFailed;
    private static final Map<NPC, Long> ROLLED = new WeakHashMap<>();

    public static final class Save {
        @Expose public Set<Integer> owned = new TreeSet<>();
        @Expose public Set<Integer> pending = new TreeSet<>();
        @Expose public int active;
        @Expose private Map<String, String> activeAttributes;
    }

    static final class Definition {
        int npc, item, rate, attack = -1, appearance = -1;
    }

    static final class Registry {
        int defaultRate = 5000;
        Map<Integer, Definition> definitions;
        final Map<Integer, Integer> sourceByItem = new HashMap<>();
    }

    private static Registry registry() {
        if (!Boolean.getBoolean("kronos.universalPets") || registryFailed) return null;
        if (registry != null) return registry;
        try {
            registry = loadRegistry();
            return registry;
        } catch (IOException | RuntimeException e) {
            registryFailed = true;
            Server.logError("Universal pets disabled because the paired cache or registry is invalid", e);
            return null;
        }
    }

    private static Registry loadRegistry() throws IOException {
        Registry loaded;
        try (Reader reader = Files.newBufferedReader(Server.dataFolder.toPath().resolve("pet-definitions.json"), StandardCharsets.UTF_8)) {
            loaded = new Gson().fromJson(reader, Registry.class);
        }
        if (loaded == null || loaded.definitions == null || loaded.defaultRate <= 0)
            throw new IllegalStateException("Invalid pet manifest");
        Set<Integer> npcs = new HashSet<>();
        Set<Integer> items = new HashSet<>();
        for (Map.Entry<Integer, Definition> entry : loaded.definitions.entrySet()) {
            int source = entry.getKey();
            Definition def = entry.getValue();
            NPCDef npc = NPCDef.get(def.npc);
            ItemDef item = ItemDef.get(def.item);
            if (source <= 0 || NPCDef.get(source) == null || def.npc <= 0 || def.npc >= 16383
                    || def.item <= 0 || def.item >= 65535 || npc == null || npc.combatInfo != null
                    || item == null || item.pet != null || item.tradeable || item.stackable
                    || item.getOption("drop") < 0
                    || !npcs.add(def.npc) || !items.add(def.item) || def.rate < 0)
                throw new IllegalStateException("Invalid generated pet pair " + def.npc + "/" + def.item);
        }
        if (!Collections.disjoint(npcs, loaded.definitions.keySet()))
            throw new IllegalStateException("Pet NPC overlaps a killable source");
        for (Map.Entry<Integer, Definition> entry : loaded.definitions.entrySet()) {
            final int source = entry.getKey();
            Definition def = entry.getValue();
            loaded.sourceByItem.put(def.item, source);
            NPCDef npc = NPCDef.get(def.npc);
            npc.occupyTiles = false;
            npc.ignoreOccupiedTiles = true;
            ItemAction.registerInventory(def.item, "drop", (p, item) -> release(p, source, item));
            NPCAction.register(def.npc, 1, (p, follower) -> {
                if (FOLLOWERS.get(p) == follower && !follower.isRemoved()) pickup(p, source);
            });
            NPCAction.register(def.npc, 2, (p, follower) -> {
                if (FOLLOWERS.get(p) != follower) return;
                NPCDef original = NPCDef.get(def.appearance >= 0 ? def.appearance : source);
                int animation = def.attack >= 0 ? def.attack :
                        original == null || original.combatInfo == null ? -1 : original.combatInfo.attack_animation;
                if (animation > 0) follower.animate(animation);
                else p.sendMessage("This pet has no recorded attack animation.");
            });
        }
        return loaded;
    }

    private static boolean eligible(Player p) {
        return p != null && p.isOnline() && p.getUserId() >= 1000
                && !(p instanceof io.ruin.model.entity.player.autonomous.Adventurer)
                && OfflineMode.hasStableAccountRegistry() && !PlayerFile.isSaveQuarantined(p);
    }

    /** The selected follower retains its slot while suspended or completing a durable pickup. */
    public static boolean hasActivePet(Player p) {
        return Boolean.getBoolean("kronos.universalPets")
                && ((p.universalPets != null && p.universalPets.active != 0) || FOLLOWERS.containsKey(p));
    }

    private static void save(Player p, Runnable committed, Runnable rollback) {
        LockType priorLock = LockType.NONE;
        for (LockType type : LockType.values()) {
            if (p.isLocked(type)) {
                priorLock = type;
                break;
            }
        }
        try {
            PlayerFile.saveDurably(p);
        } catch (PlayerFile.DurableSaveException failure) {
            if (failure.uncertain) {
                resolveSave(p, priorLock, committed, rollback, failure);
            } else {
                rollback.run();
                p.sendMessage("The pet change could not be saved. Please try again.");
                Server.logError("Universal pet save failed before committing", failure);
            }
            return;
        }
        committed.run();
    }

    private static void resolveSave(Player p, LockType priorLock, Runnable committed, Runnable rollback,
                                    PlayerFile.DurableSaveException failure) {
        // A timeout can leave a successful write in flight. The world event also runs while
        // the player is locked or disconnecting. It never waits on the disk or retries a write.
        World.startEvent(event -> {
            for (int tick = 0; tick < SAVE_RESOLUTION_TICKS; tick++) {
                event.delay(1);
                PlayerFile.SaveResolution result = PlayerFile.resolveDurableSave(p);
                if (result == PlayerFile.SaveResolution.PENDING) continue;
                if (result == PlayerFile.SaveResolution.UNKNOWN) break;
                boolean saved = result == PlayerFile.SaveResolution.COMMITTED;
                if (!saved) rollback.run();
                if (p.isLocked(LockType.FULL_NULLIFY_DAMAGE)) p.lock(priorLock);
                if (saved) committed.run();
                else p.sendMessage("The pet change could not be saved. Please try again.");
                return;
            }
            p.sendMessage("Your pet save needs staff attention. Your items are protected.");
            Server.logError("Universal pet save outcome could not be confirmed for account " + p.getUserId(), failure);
        });
    }

    public static void killed(NPC npc, Player killer) {
        if (!npc.sixSystemsRewardless) encounterKilled(npc, killer, npc.getId());
    }

    /** For scripted terminal defeats with the encounter's own participation checks. */
    public static void encounterKilled(NPC npc, Player killer, int source) {
        if (npc.ownerId >= 0 || !eligible(killer)) return;
        Registry definitions = registry();
        if (definitions == null) return;
        Definition def = definitions.definitions.get(source);
        if (def == null) return;
        Long previousSpawn = ROLLED.put(npc, npc.endgameSpawnId);
        if (previousSpawn != null && previousSpawn == npc.endgameSpawnId) return;
        Save state = killer.universalPets;
        if (state != null && state.owned.contains(source)) return;
        int rate = def.rate > 0 ? def.rate : definitions.defaultRate;
        if (ThreadLocalRandom.current().nextInt(rate) != 0) return;
        if (state == null) killer.universalPets = state = new Save();
        final Save earned = state;
        earned.owned.add(source);
        earned.pending.add(source);
        save(killer, () -> killer.sendMessage("You earned " + ItemDef.get(def.item).name + ". Use ::npcpets to claim it."),
                () -> { earned.owned.remove(source); earned.pending.remove(source); });
    }

    public static int dropItem(int source) {
        Registry data = registry();
        Definition def = data == null ? null : data.definitions.get(source);
        return def == null ? -1 : def.item;
    }

    public static double dropChance(int source) {
        Registry data = registry();
        Definition def = data == null ? null : data.definitions.get(source);
        return def == null ? 0 : 1.0 / (def.rate > 0 ? def.rate : data.defaultRate);
    }

    public static boolean menu(Player p, String command) {
        if (!"npcpets".equals(command) || registry() == null) return false;
        if (eligible(p)) page(p, 0);
        return true;
    }

    private static void page(Player p, int page) {
        Save state = p.universalPets;
        List<Integer> ids = state == null ? Collections.emptyList() : new ArrayList<>(state.pending);
        List<Option> options = new ArrayList<>();
        int active = state == null ? 0 : state.active;
        if (active != 0) options.add(new Option("Pick up follower", () -> pickup(p, active)));
        int start = Math.min(page * PAGE_SIZE, ids.size());
        int end = Math.min(start + PAGE_SIZE, ids.size());
        for (int index = start; index < end; index++) {
            int source = ids.get(index);
            Definition def = registry.definitions.get(source);
            if (def != null) options.add(new Option(ItemDef.get(def.item).name, () -> claim(p, source)));
        }
        if (page > 0) options.add(new Option("Previous page", () -> page(p, page - 1)));
        if (end < ids.size()) options.add(new Option("Next page", () -> page(p, page + 1)));
        if (options.isEmpty()) p.sendMessage("You have no NPC pets waiting to be claimed.");
        else OptionScroll.open(p, "NPC pets (" + (page + 1) + "/" + Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE) + ")", options);
    }

    private static boolean inActivity(Player p) {
        return Dungeoneering.inRun(p) || HollowDepths.inRun(p)
                || TheatreOfBloodRaid.isInRaid(p) || TombsOfAmascutRaid.isInRaid(p);
    }

    private static void claim(Player p, int source) {
        if (!eligible(p) || p.universalPets == null || !p.universalPets.owned.contains(source)
                || !p.universalPets.pending.contains(source) || p.universalPets.active == source) return;
        if (inActivity(p) || p.isLocked()) {
            p.sendMessage("Leave the activity before claiming a pet.");
            return;
        }
        Definition def = registry.definitions.get(source);
        if (def == null || p.getBank().findFirst(def.item) != null || p.getInventory().findFirst(def.item) != null) return;
        if (p.getInventory().add(def.item, 1) != 1) {
            p.sendMessage("Make room in your inventory to claim this pet.");
            return;
        }
        p.universalPets.pending.remove(source);
        save(p, () -> {}, () -> {
            p.getInventory().remove(def.item, 1);
            p.universalPets.pending.add(source);
        });
    }

    private static void release(Player p, int source, Item item) {
        Save state = p.universalPets;
        if (!eligible(p) || state == null || !state.owned.contains(source)
                || item == null || item.getAmount() != 1 || p.getInventory().getSafe(item.getSlot()) != item) return;
        if (p.pet != null || hasActivePet(p) || DungeoneeringUtilityRewards.hasFollower(p)) {
            p.sendMessage("You can only have one follower at a time.");
            return;
        }
        if (inActivity(p) || p.joinedTournament || p.isLocked()) {
            p.sendMessage("You can't release a pet right now.");
            return;
        }
        Item token = item.copy();
        item.remove();
        state.active = source;
        state.activeAttributes = token.copyOfAttributes();
        save(p, () -> resume(p), () -> {
            state.active = 0;
            state.activeAttributes = null;
            p.getInventory().add(token);
        });
    }

    private static void pickup(Player p, int source) {
        Save state = p.universalPets;
        if (!eligible(p) || state == null || state.active != source || !state.owned.contains(source)) return;
        if (inActivity(p) || p.joinedTournament || p.isLocked()) {
            p.sendMessage("You can't pick up your pet right now.");
            return;
        }
        Registry data = registry();
        Definition def = data == null ? null : data.definitions.get(source);
        if (def == null) return;
        if (p.getInventory().findFirst(def.item) != null || p.getBank().findFirst(def.item) != null) {
            p.sendMessage("This pet already has a token in your inventory or bank.");
            return;
        }
        Map<String, String> attributes = state.activeAttributes;
        if (p.getInventory().add(def.item, 1, attributes) != 1) {
            p.sendMessage("You don't have enough inventory space to pick up your pet.");
            return;
        }
        boolean wasPending = state.pending.remove(source);
        state.active = 0;
        state.activeAttributes = null;
        save(p, () -> logout(p), () -> {
            p.getInventory().remove(def.item, 1, false, attributes);
            if (wasPending) state.pending.add(source);
            state.active = source;
            state.activeAttributes = attributes;
        });
    }

    /** Keep the ownership claim when death removes a carried pet token. */
    public static void storeTokensOnDeath(Player p) {
        Save state = p.universalPets;
        if (state == null || state.owned.isEmpty()) return;
        Registry data = registry();
        if (data == null) return;
        for (Item item : p.getInventory().getItems()) {
            if (item == null) continue;
            Integer source = data.sourceByItem.get(item.getId());
            if (source != null && state.owned.contains(source) && item.remove(1) == 1)
                state.pending.add(source);
        }
    }

    public static void initialize() { registry(); }

    public static void resume(Player p) {
        if (p.universalPets == null || p.universalPets.active == 0
                || !eligible(p) || p.pet != null || DungeoneeringUtilityRewards.hasFollower(p)
                || inActivity(p) || p.getCombat().isDead() || p.getMovement().isTeleportQueued()) return;
        NPC current = FOLLOWERS.get(p);
        if (current != null && !current.isRemoved()) return;
        Registry data = registry();
        if (data == null) return;
        Definition def = data.definitions.get(p.universalPets.active);
        if (def == null || !p.universalPets.owned.contains(p.universalPets.active)) return;
        NPC npc = new NPC(def.npc);
        npc.ownerId = p.getUserId();
        npc.attackNpcListener = (who, target, message) -> false;
        npc.sixSystemsRewardless = true;
        npc.spawn(p.getAbsX(), p.getAbsY(), p.getHeight());
        FOLLOWERS.put(p, npc);
        Config.PET_NPC_INDEX.set(p, npc.getIndex());
        npc.addEvent(event -> {
            while (p.isOnline() && FOLLOWERS.get(p) == npc && !npc.isRemoved()) {
                if (p.pet != null || inActivity(p)) break;
                if (!p.getCombat().isDead() && !p.getMovement().isTeleportQueued()) {
                    if (p.callPet || npc.getHeight() != p.getHeight()
                            || !npc.getPosition().isWithinDistance(p.getPosition(), 12)) {
                        p.callPet = false;
                        npc.getMovement().teleport(p.getAbsX(), p.getAbsY(), p.getHeight());
                    } else if (!npc.getPosition().isWithinDistance(p.getPosition(), 1)) {
                        DumbRoute.step(npc, p, 1);
                    }
                    if (p.hidePet) { p.hidePet = false; npc.setHidden(true); }
                    if (p.showPet) { p.showPet = false; npc.setHidden(false); }
                }
                event.delay(1);
            }
            if (FOLLOWERS.get(p) == npc) logout(p);
        });
    }

    public static void logout(Player p) {
        NPC npc = FOLLOWERS.remove(p);
        if (npc == null) return;
        if (Config.PET_NPC_INDEX.get(p) == npc.getIndex()) Config.PET_NPC_INDEX.set(p, -1);
        if (!npc.isRemoved()) npc.remove();
    }

    private UniversalPets() {}
}
