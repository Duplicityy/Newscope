package io.ruin.model.activities.automation;

import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.model.World;
import io.ruin.model.activities.bosses.vorkath.Vorkath;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.combat.RangedWeapon;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.map.route.routes.DumbRoute;
import io.ruin.model.skills.herblore.Potion;
import io.ruin.model.skills.magic.SpellBook;
import io.ruin.model.skills.magic.rune.RuneRemoval;
import io.ruin.model.skills.magic.spells.modern.CrumbleUndead;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import io.ruin.network.incoming.handlers.NPCActionHandler;
import io.ruin.process.event.Event;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Owner decisions for the original Vorkath encounter. All actions use normal combat and movement. */
public final class VorkathAutomation implements AutomationDriver {
    private enum Phase { NORMAL, ACID, FREEZE }
    private static final int SLEEPING = 8059, AWAKENED = 8061, SPAWN = 8063, ACID_POOL = 32000;
    private static final int DODGE_TICKS = 3, ACID_LIMIT = 36, FREEZE_LIMIT = 36, FIGHT_LIMIT = 650;
    private static final int ESCAPE_TABLET = 8007, MAX_LOOT = 8;
    private static final int[] FOOD = {391, 11936, 385, 13441, 7946, 373, 379};
    private static VorkathAutomation observer;

    private final Player player;
    private final PlayerCombat combat;
    private final AutomationActionSource actions;
    private final int killGoal, startKills, instance;
    private final Position origin;
    private final Set<Integer> pendingAcid = new HashSet<>();
    private final Set<GroundItem> attemptedLoot = Collections.newSetFromMap(new IdentityHashMap<>());
    private NPC boss, zombie;
    private TileCenter fireball;
    private Phase phase = Phase.NORMAL;
    private Event auxiliary;
    private long phaseDeadline, wakeDeadline, nextCast, combatStarted, lastDamage, retreatDeadline, lootDeadline;
    private int healthObserved, acidPreviousX, acidPreviousY;
    private boolean stopped, wokeBoss, steeredMovement, ownPrayer, retreating, looting;
    private String activity = "Preparing for Vorkath";

    VorkathAutomation(Player player, int kills, long tick) {
        if (kills < 1 || kills > 100) throw new IllegalArgumentException("Vorkath kill goal must be 1-100");
        this.player = player;
        killGoal = kills;
        startKills = player.vorkathKills.getKills();
        combat = player.getCombat();
        actions = (AutomationActionSource) (Object) player;
        origin = player.getPosition().copy();
        Region region = origin.getRegion();
        instance = region == null ? -1 : region.dynamicIndex;
        lastDamage = tick;
    }

    @Override public String activity() { return activity + " / " + (killGoal - remainingBossKills()) + "/" + killGoal + " kills"; }

    @Override public int remainingBossKills() {
        return Math.max(0, killGoal - (player.vorkathKills.getKills() - startKills));
    }

    @Override public boolean ownsNativeLock() {
        return retreating && auxiliary != null && actions.ownerAutomationAction() == auxiliary;
    }

    @Override public boolean ownsNativeTravel() { return ownsNativeLock(); }

    @Override public String pulse(long tick) {
        if (stopped) return "Vorkath automation stopped";
        if (retreating) {
            if (!local(player.getPosition())) return "Escaped Vorkath using a teleport tablet";
            if (tick >= retreatDeadline || auxiliary == null || actions.ownerAutomationAction() != auxiliary)
                return "Escape did not complete. Take manual control.";
            return null;
        }
        if (instance < 0 || !local(player.getPosition()))
            return "Enter your private Vorkath instance before starting";
        if (player.getDuel().stage >= 4 || player.joinedTournament)
            return "Vorkath mode is unavailable during a duel or tournament";
        if (boss == null) {
            boss = findBoss();
            if (boss == null) return "No native Vorkath found in this instance";
            if (boss.getId() != SLEEPING || boss.isLocked())
                return "Start while Vorkath is sleeping so no existing hazards are missed";
            observer = this;
        }
        if (boss.isRemoved() || !local(boss.getPosition()))
            return "Vorkath left the private instance";
        if (combat.getTarget() != null && combat.getTarget() != boss && combat.getTarget() != zombie)
            return "Another attack took control";
        if (phase != Phase.FREEZE && zombie != null) {
            if (combat.getTarget() == zombie) clearAttack();
            zombie = null;
        }
        if (player.hasActiveAction() && actions.ownerAutomationAction() != auxiliary)
            return "Another native action took control";

        if (boss.getCombat().isDead() || boss.isHidden()) {
            clearAttack();
            clearPrayer();
            resetObservedHazards();
            if (!looting) {
                looting = true;
                lootDeadline = tick + 20;
                attemptedLoot.clear();
            }
            if (collectLoot(tick)) return null;
            return remainingBossKills() == 0 ? "Vorkath kill goal reached" : null;
        }
        if (remainingBossKills() == 0) return "Vorkath kill goal reached";
        if (looting) {
            looting = false;
            wokeBoss = false;
            combatStarted = 0;
            auxiliary = null;
        }

        if (boss.getId() == SLEEPING) return prepareAndWake(tick);
        if (boss.getId() != AWAKENED) return "Unexpected Vorkath form";
        if (combatStarted == 0) {
            combatStarted = lastDamage = tick;
            healthObserved = boss.getHp();
        }
        if (boss.getHp() < healthObserved) lastDamage = tick;
        healthObserved = boss.getHp();
        if (tick - combatStarted >= FIGHT_LIMIT) return retreat(tick, "The encounter took too long");
        if (fireball != null && tick > fireball.deadline) fireball = null;

        if (fireball != null && chebyshev(player.getAbsX(), player.getAbsY(), fireball.x, fireball.y) <= 1) {
            clearAttack();
            if (!stepAway(true)) return retreat(tick, "No safe fireball escape step");
            activity = "Dodging the fireball";
            return null;
        }
        if (phase == Phase.ACID) {
            clearAttack();
            if (tick > phaseDeadline || !stepAway(false)) return retreat(tick, "No safe acid movement route");
            activity = "Walking clear of acid and fireballs";
            return null;
        }
        if (phase == Phase.FREEZE) return handleFreeze(tick);
        if (tick - lastDamage > 65) return retreat(tick, "No recent damage to Vorkath");

        if (player.getHp() <= 52) {
            Item food = food();
            if (food == null) return retreat(tick, "Food is depleted");
            if (canConsume()) use(food, "eat");
            activity = "Recovering health";
            return null;
        }
        if (player.superAntifireTicks < 10) {
            Item potion = findPotion(Potion.EXTENDED_SUPER_ANTIFIRE, Potion.SUPER_ANTIFIRE);
            if (potion == null) return retreat(tick, "Super antifire is depleted");
            if (canConsume()) use(potion, "drink");
            activity = "Refreshing dragonfire protection";
            return null;
        }
        if (!player.isVenomImmune() || player.poisonLevel > 0) {
            Item potion = findPotion(Potion.SUPER_ANTI_VENOM);
            if (potion == null) return retreat(tick, "Anti-venom+ is depleted");
            if (canConsume()) use(potion, "drink");
            activity = "Refreshing venom protection";
            return null;
        }
        if (combat.getDragonfireResistance() < 1.3) return retreat(tick, "Dragonfire protection is inadequate");
        if (!hasNativeAmmo()) return retreat(tick, "Ranged ammunition is depleted or incompatible");
        protectMagic();
        if (!combat.canAttack(boss, false)) return retreat(tick, "Native combat refused Vorkath");
        if (combat.getTarget() != boss)
            NPCActionHandler.handleAction(player, boss.getDef().attackOption, boss.getIndex(), 0);
        steeredMovement = false;
        activity = "Fighting Vorkath";
        return null;
    }

    private String prepareAndWake(long tick) {
        if (wokeBoss) {
            if (tick >= wakeDeadline) return "Vorkath did not wake up. Check reachability.";
            activity = "Waiting for Vorkath to awaken";
            return null;
        }
        if (action(player.getInventory().findItem(ESCAPE_TABLET), "break") == null)
            return "Carry a Varrock teleport tablet for emergencies";
        if (!SpellBook.MODERN.isActive(player) || player.getStats().get(StatType.Magic).currentLevel < 39
                || RuneRemoval.get(player, CrumbleUndead.INSTANCE.getRuneItems()) == null)
            return "Use the standard spellbook and bring Crumble Undead runes";
        if (combat.weaponType == null || combat.getAttackStyle() != AttackStyle.RANGED
                || combat.autocastSpell != null || !hasNativeAmmo())
            return "Equip a ranged weapon with ammunition and disable autocast";
        if (player.getMaxHp() < 70 || food() == null)
            return "Bring healing food and at least 70 base Hitpoints";
        if (player.getHp() + 12 < player.getMaxHp()) {
            if (canConsume()) use(food(), "eat");
            activity = "Healing before the encounter";
            return null;
        }
        if (player.superAntifireTicks < 20) {
            Item potion = findPotion(Potion.EXTENDED_SUPER_ANTIFIRE, Potion.SUPER_ANTIFIRE);
            if (potion == null) return "Bring super antifire";
            if (canConsume()) use(potion, "drink");
            activity = "Drinking super antifire";
            return null;
        }
        if (combat.getDragonfireResistance() < 1.3)
            return "Equip an antifire shield that works with your ranged weapon";
        if (!player.isVenomImmune() || player.poisonLevel > 0) {
            Item potion = findPotion(Potion.SUPER_ANTI_VENOM);
            if (potion == null) return "Bring anti-venom+";
            if (canConsume()) use(potion, "drink");
            activity = "Drinking anti-venom+";
            return null;
        }
        int poke = boss.getDef().getOption("poke");
        if (poke < 1 || poke > 5) return "Native Vorkath wake action is unavailable";
        NPCActionHandler.handleAction(player, poke, boss.getIndex(), 0);
        auxiliary = actions.ownerAutomationAction();
        wokeBoss = true;
        wakeDeadline = tick + 25;
        activity = "Waking Vorkath";
        return null;
    }

    private String handleFreeze(long tick) {
        if (tick > phaseDeadline) return retreat(tick, "The frozen spawn phase took too long");
        activity = "Waiting for the zombified spawn";
        if (!liveZombie()) {
            clearAttack();
            return null;
        }
        activity = "Casting Crumble Undead on the spawn";
        if (tick < nextCast || combat.getTarget() == zombie) return null;
        if (RuneRemoval.get(player, CrumbleUndead.INSTANCE.getRuneItems()) == null)
            return retreat(tick, "Crumble Undead runes are depleted");
        if (!combat.canAttack(zombie, false))
            return retreat(tick, "The spawn is unreachable by normal combat");
        combat.queueSpell(CrumbleUndead.INSTANCE, zombie);
        nextCast = tick + 5;
        return null;
    }

    private boolean stepAway(boolean dodge) {
        int x = player.getAbsX(), y = player.getAbsY();
        int[] step = phase == Phase.ACID
                ? chooseAcidStep(x, y, acidPreviousX, acidPreviousY, fireball, dodge, this::safeStep)
                : chooseStep(x, y, fireball, dodge, this::safeStep);
        if (step == null) return false;
        RouteType route = player.getRouteFinder().routeAbsolute(step[0], step[1]);
        boolean usable = route.reachable && route.finishX == step[0] && route.finishY == step[1]
                && !player.getMovement().isAtDestination();
        steeredMovement = usable;
        if (usable && phase == Phase.ACID) {
            acidPreviousX = x;
            acidPreviousY = y;
        }
        if (!usable) player.getMovement().reset();
        return usable;
    }

    private boolean safeStep(int x, int y, int stepX, int stepY) {
        int z = player.getHeight();
        if (!local(new Position(stepX, stepY, z)) || pendingAcid.contains(tileKey(stepX, stepY))
                || Tile.getObject(ACID_POOL, stepX, stepY, z, 10, -1) != null)
            return false;
        return DumbRoute.getDirection(player.getRouteFinder().getClipUtils(), x, y, z,
                player.getSize(), stepX, stepY) != null;
    }

    static int[] chooseAcidStep(int x, int y, int previousX, int previousY, TileCenter fire,
                                boolean dodge, StepCheck allowed) {
        // Acid fireballs compare the landing tile with the captured tile two ticks earlier.
        // Exclude A -> B -> A and do not commit to a step that has no safe exit.
        return chooseStep(x, y, fire, dodge, (fromX, fromY, stepX, stepY) -> {
            if (stepX == previousX && stepY == previousY || !allowed.canStep(fromX, fromY, stepX, stepY))
                return false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0 || stepX + dx == fromX && stepY + dy == fromY) continue;
                    if (fire != null && chebyshev(stepX + dx, stepY + dy, fire.x, fire.y) <= 1) continue;
                    if (allowed.canStep(stepX, stepY, stepX + dx, stepY + dy)) return true;
                }
            }
            return false;
        });
    }

    static int[] chooseStep(int x, int y, TileCenter fire, boolean dodge, StepCheck allowed) {
        int bestScore = Integer.MIN_VALUE, nextX = 0, nextY = 0;
        int before = fire == null ? 0 : chebyshev(x, y, fire.x, fire.y);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0 || !allowed.canStep(x, y, x + dx, y + dy)) continue;
                int after = fire == null ? 3 : chebyshev(x + dx, y + dy, fire.x, fire.y);
                if (dodge && after <= before || !dodge && fire != null && after <= 1) continue;
                int score = Math.min(3, after);
                if (dx == 0 || dy == 0) score += 1;
                if (score > bestScore) {
                    bestScore = score;
                    nextX = x + dx;
                    nextY = y + dy;
                }
            }
        }
        return bestScore == Integer.MIN_VALUE ? null : new int[]{nextX, nextY};
    }

    @FunctionalInterface
    interface StepCheck { boolean canStep(int x, int y, int stepX, int stepY); }

    static final class TileCenter {
        final int x, y;
        final long deadline;
        TileCenter(int x, int y, long deadline) { this.x = x; this.y = y; this.deadline = deadline; }
    }

    private NPC findBoss() {
        for (NPC npc : player.localNpcs()) {
            if (nativeBoss(npc) && local(npc.getPosition()) && npc.getPosition().isWithinDistance(origin, 16))
                return npc;
        }
        return null;
    }

    private static boolean nativeBoss(NPC npc) {
        return npc != null && !npc.isRemoved() && !npc.sixSystemsRewardless && npc.getIndex() >= 0
                && World.getNpc(npc.getIndex()) == npc && npc.getCombat() instanceof Vorkath
                && (npc.getId() == SLEEPING || npc.getId() == AWAKENED);
    }

    private boolean liveZombie() {
        return zombie != null && !zombie.isRemoved() && zombie.getId() == SPAWN
                && zombie.getCombat() != null && !zombie.getCombat().isDead() && local(zombie.getPosition());
    }

    private boolean local(Position position) {
        Region region = position.getRegion();
        return instance >= 0 && region != null && region.dynamicIndex == instance
                && position.isWithinDistance(origin, 24);
    }

    private static int tileKey(int x, int y) { return (x << 14) | y; }

    private static int chebyshev(int x, int y, int x2, int y2) {
        return Math.max(Math.abs(x - x2), Math.abs(y - y2));
    }

    private boolean hasNativeAmmo() {
        ItemDef weapon = player.getEquipment().getDef(Equipment.SLOT_WEAPON);
        RangedWeapon ranged = weapon == null ? null : weapon.rangedWeapon;
        if (ranged == null) return false;
        if (ranged.data != null) return true; // Native self-ammo types consume their equipped stack.
        ItemDef ammo = player.getEquipment().getDef(Equipment.SLOT_AMMO);
        return ammo != null && ammo.rangedAmmo != null && ranged.allowAmmo(ammo.rangedAmmo)
                && weapon.rangedLevel >= ammo.rangedLevel;
    }

    private boolean canConsume() {
        return !player.eatDelay.isDelayed() && !player.karamDelay.isDelayed() && !player.potDelay.isDelayed();
    }

    private Item food() {
        for (int id : FOOD) {
            Item item = player.getInventory().findItem(id);
            if (action(item, "eat") != null) return item;
        }
        return null;
    }

    private Item findPotion(Potion... potions) {
        for (Potion potion : potions) {
            for (int id : potion.vialIds) {
                Item item = player.getInventory().findItem(id);
                if (action(item, "drink") != null) return item;
            }
        }
        return null;
    }

    private void use(Item item, String option) {
        ItemAction action = action(item, option);
        if (action != null) action.handle(player, item);
    }

    private static ItemAction action(Item item, String option) {
        if (item == null) return null;
        ItemDef def = item.getDef();
        if (def == null || def.isNote() || def.inventoryActions == null) return null;
        int slot = def.getOption(option) - 1;
        return slot >= 0 && slot < def.inventoryActions.length ? def.inventoryActions[slot] : null;
    }

    private void protectMagic() {
        Prayer magic = Prayer.PROTECT_FROM_MAGIC;
        if (player.getPrayer().isActive(magic) || player.getStats().get(StatType.Prayer).fixedLevel < magic.level
                || player.getStats().get(StatType.Prayer).currentLevel < 2) return;
        if (player.getPrayer().isActive(Prayer.PROTECT_FROM_MISSILES)
                || player.getPrayer().isActive(Prayer.PROTECT_FROM_MELEE)
                || player.getPrayer().isActive(Prayer.RETRIBUTION) || player.getPrayer().isActive(Prayer.REDEMPTION)
                || player.getPrayer().isActive(Prayer.SMITE)) return;
        player.getPrayer().toggle(magic);
        ownPrayer = player.getPrayer().isActive(magic);
    }

    private String retreat(long tick, String cause) {
        clearAttack();
        clearPrayer();
        Item tablet = player.getInventory().findItem(ESCAPE_TABLET);
        if (action(tablet, "break") == null) return cause + ". Take manual control.";
        clearAuxiliary();
        use(tablet, "break");
        auxiliary = actions.ownerAutomationAction();
        if (auxiliary == null) return cause + ". Native teleport was refused. Take manual control.";
        retreating = true;
        retreatDeadline = tick + 12;
        activity = "Retreating: " + cause;
        return null;
    }

    private boolean collectLoot(long tick) {
        if (auxiliary != null && actions.ownerAutomationAction() == auxiliary) {
            if (tick < lootDeadline) return true;
            clearAuxiliary();
        }
        if (tick >= lootDeadline || attemptedLoot.size() >= MAX_LOOT || player.getInventory().isFull()) return false;
        Position drop = ((Vorkath) boss.getCombat()).getDropPosition();
        if (!local(drop)) return false;
        Tile tile = Tile.get(drop.getX(), drop.getY(), drop.getZ());
        if (tile == null || tile.groundItems == null) return false;
        for (GroundItem item : tile.groundItems) {
            if (!isOwnedLoot(item) || !attemptedLoot.add(item)) continue;
            player.getRouteFinder().routeGroundItem(item, distance -> {
                if (!stopped && local(player.getPosition()) && isOwnedLoot(item)) item.pickup(player, distance);
            });
            auxiliary = actions.ownerAutomationAction();
            activity = "Picking up credited Vorkath drops";
            return true;
        }
        return false;
    }

    private boolean isOwnedLoot(GroundItem item) {
        return item != null && !item.isRemoved() && item.z == player.getHeight() && item.getTimeDropped() == 0
                && item.originalOwner == player.getUserId() && (item.activeOwner == -1 || item.activeOwner == player.getUserId());
    }

    private void clearAttack() {
        if (combat.getTarget() != null && (combat.getTarget() == boss || combat.getTarget() == zombie)) {
            combat.reset();
            player.getMovement().reset();
        }
    }

    private void clearAuxiliary() {
        if (auxiliary != null && actions.cancelOwnerAutomationAction(auxiliary)) player.getMovement().reset();
        auxiliary = null;
    }

    private void clearPrayer() {
        if (ownPrayer && player.getPrayer().isActive(Prayer.PROTECT_FROM_MAGIC))
            player.getPrayer().deactivate(Prayer.PROTECT_FROM_MAGIC);
        ownPrayer = false;
    }

    private void resetObservedHazards() {
        fireball = null;
        pendingAcid.clear();
        zombie = null;
        phase = Phase.NORMAL;
    }

    @Override public void stop() {
        stopped = true;
        if (observer == this) observer = null;
        clearAttack();
        clearAuxiliary();
        clearPrayer();
        if (steeredMovement) player.getMovement().reset();
        resetObservedHazards();
    }

    private static VorkathAutomation observing(NPC boss, Entity target) {
        VorkathAutomation run = observer;
        return run != null && !run.stopped && !run.retreating && run.boss == boss && run.player == target ? run : null;
    }

    public static void observeFireball(NPC boss, Entity target, Position center) {
        VorkathAutomation run = observing(boss, target);
        if (run != null) run.fireball = new TileCenter(center.getX(), center.getY(), Server.currentTick() + DODGE_TICKS);
    }

    public static void observeAcid(NPC boss, Entity target, List<GameObject> pools) {
        VorkathAutomation run = observing(boss, target);
        if (run == null) return;
        run.phase = Phase.ACID;
        run.phaseDeadline = Server.currentTick() + ACID_LIMIT;
        run.acidPreviousX = -1;
        run.acidPreviousY = -1;
        run.pendingAcid.clear();
        for (GameObject pool : pools) run.pendingAcid.add(tileKey(pool.x, pool.y));
    }

    public static void observeAcidEnd(NPC boss, Entity target) {
        VorkathAutomation run = observing(boss, target);
        if (run != null && run.phase == Phase.ACID) {
            run.pendingAcid.clear();
            run.phase = Phase.NORMAL;
            run.lastDamage = Server.currentTick();
        }
    }

    public static void observeFreeze(NPC boss, Entity target) {
        VorkathAutomation run = observing(boss, target);
        if (run == null) return;
        run.phase = Phase.FREEZE;
        run.phaseDeadline = Server.currentTick() + FREEZE_LIMIT;
        run.zombie = null;
        run.nextCast = 0;
    }

    public static void observeZombie(NPC boss, Entity target, NPC spawn) {
        VorkathAutomation run = observing(boss, target);
        if (run != null && run.phase == Phase.FREEZE && spawn != null && spawn.getId() == SPAWN) run.zombie = spawn;
    }

    public static void observeFreezeEnd(NPC boss, Entity target) {
        VorkathAutomation run = observing(boss, target);
        if (run != null && run.phase == Phase.FREEZE) {
            run.phase = Phase.NORMAL;
            run.lastDamage = Server.currentTick();
        }
    }
}
