package io.ruin.model.activities.automation;

import io.ruin.cache.ItemDef;
import io.ruin.model.World;
import io.ruin.model.activities.bosses.slayer.ThermonuclearSmokeDevil;
import io.ruin.model.activities.miscpvm.BasicCombat;
import io.ruin.model.activities.wilderness.Wilderness;
import io.ruin.model.combat.AttackSet;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.combat.AttackType;
import io.ruin.model.combat.WeaponType;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.skills.slayer.Slayer;
import io.ruin.model.skills.slayer.SlayerTask;
import io.ruin.model.stat.StatType;
import io.ruin.network.incoming.handlers.NPCActionHandler;
import io.ruin.process.event.Event;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Local owner PvE planner. Native combat, consumables, rewards and pickup own all game mutations. */
final class CombatAutomation implements AutomationDriver {
    private static final int COMBAT_RADIUS = 8, ANCHOR_RADIUS = 14, RESCAN_TICKS = 4;
    private static final int NO_DAMAGE_TICKS = 45, NO_TARGET_TICKS = 180, LOOT_TICKS = 14, MAX_LOOT = 8;
    private static final int SMOKE_DEVIL_ID = 499, FACEMASK = 4164, MAX_BOSS_KILLS = 100;
    static final int[] FOODS = {315, 2140, 2142, 2309, 333, 329, 361, 379, 373,
            7946, 3144, 385, 13441, 397, 391, 11936};
    private static final int[] HEALING = {3, 4, 4, 5, 7, 9, 10, 12, 14, 16, 18, 20, 22, 21, 22, 22};
    private static final Prayer[] OVERHEADS = {Prayer.PROTECT_FROM_MELEE, Prayer.PROTECT_FROM_MISSILES,
            Prayer.PROTECT_FROM_MAGIC, Prayer.RETRIBUTION, Prayer.REDEMPTION, Prayer.SMITE};

    private final Player player;
    private final StatType skill;
    private final int goal;
    private final boolean smokeDevil;
    private final int startingKills;
    private final PlayerCombat combat;
    private final AutomationActionSource actions;
    private final Position anchor;
    private final SlayerTask assignment;
    private final SlayerLoadout slayerRules;
    private final SlayerSettings slayerSettings;
    private final Map<Item, Integer> slayerLoot;
    private final Map<NPC, Long> rejected = new IdentityHashMap<>();
    private final Set<GroundItem> attemptedDrops = Collections.newSetFromMap(new IdentityHashMap<>());
    private NPC target;
    private Event lootEvent;
    private Prayer ownPrayer;
    private Position dropTile;
    private int targetHp;
    private double skillXp;
    private long nextScan, lastProgress, fightProgress, lootReady, lootDeadline;
    private long nextFinisher;
    private boolean stopped;
    private String activity = "Finding a target";

    CombatAutomation(Player player, StatType skill, int goal, long tick) {
        this(player, skill, goal, tick, false);
    }

    static CombatAutomation smokeDevil(Player player, int kills, long tick) {
        if (kills < 1 || kills > MAX_BOSS_KILLS) throw new IllegalArgumentException("Kill goal must be 1-100");
        return new CombatAutomation(player, StatType.Hitpoints, kills, tick, true);
    }

    private CombatAutomation(Player player, StatType skill, int goal, long tick, boolean smokeDevil) {
        this(player, skill, goal, tick, smokeDevil, null, null, null);
    }

    static CombatAutomation slayer(Player p, long tick, SlayerLoadout rules, SlayerSettings settings,
                                   Map<Item, Integer> drops) {
        return new CombatAutomation(p, StatType.Slayer, AutomationPolicy.NO_LEVEL_LIMIT, tick, false, rules, settings, drops);
    }

    private CombatAutomation(Player player, StatType skill, int goal, long tick, boolean smokeDevil,
                             SlayerLoadout slayerRules, SlayerSettings slayerSettings, Map<Item, Integer> slayerLoot) {
        if (!smokeDevil && !supports(skill)) throw new IllegalArgumentException("Unsupported combat skill");
        this.player = player;
        this.skill = skill;
        this.goal = goal;
        this.smokeDevil = smokeDevil;
        this.slayerRules = slayerRules;
        this.slayerSettings = slayerSettings;
        this.slayerLoot = slayerLoot;
        combat = player.getCombat();
        actions = (AutomationActionSource) (Object) player;
        anchor = player.getPosition().copy();
        assignment = skill == StatType.Slayer || smokeDevil ? Slayer.getTask(player) : null;
        startingKills = smokeDevil ? player.thermonuclearSmokeDevilKills.getKills() : 0;
        nextScan = lastProgress = tick;
        skillXp = player.getStats().get(skill).experience;
    }

    static boolean supports(StatType skill) {
        return skill == StatType.Attack || skill == StatType.Strength || skill == StatType.Defence
                || skill == StatType.Hitpoints || skill == StatType.Ranged || skill == StatType.Magic
                || skill == StatType.Slayer;
    }

    @Override
    public String activity() {
        if (smokeDevil)
            return activity + " / " + bossKills() + "/" + goal + " kills";
        return skill == StatType.Slayer ? activity + " / " + player.slayerTaskRemaining + " remaining" : activity;
    }

    @Override
    public String pulse(long tick) {
        if (stopped) return "Combat automation stopped";
        if (!local(player.getPosition(), ANCHOR_RADIUS) || Wilderness.getLevel(player.getPosition()) > 0)
            return "Left the safe combat area";
        if (player.getDuel().stage >= 4 || player.joinedTournament)
            return "Combat automation cannot run during a duel or tournament";
        if (combat.getTarget() != null && combat.getTarget() != target) {
            if (slayerRules == null || combat.getTarget() != combat.lastAttacker || combat.getTarget().npc == null)
                return "Another attack took control";
            // Native auto-retaliation can fire between actions on the way back from a bank.
            combat.reset();
            clearTarget();
        }
        if (player.hasActiveAction() && actions.ownerAutomationAction() != lootEvent)
            return "Another action took control";

        String requirements = checkRequirements();
        if (requirements != null) return requirements;
        if (target != null && (!available(target) || target.getHp() <= 0 || target.getCombat().isDead())) {
            if (target.getCombat() != null && (target.getCombat().isDead() || target.getHp() <= 0)) {
                dropTile = target.getPosition().copy();
                if (slayerRules == SlayerLoadout.CAVE_KRAKEN && target.getCombat().isDead()) {
                    io.ruin.model.combat.Killer killer = target.getCombat().getKiller();
                    if (killer != null && killer.player == player) dropTile = target.getCombat().getDropPosition();
                }
                lootReady = tick + Math.min(12, Math.max(0, target.getCombat().getInfo().death_ticks)) + 2;
                lootDeadline = lootReady + LOOT_TICKS;
                attemptedDrops.clear();
                lastProgress = tick;
            }
            clearTarget();
        }

        if (isGoalReached() && target != null) return goalMessage();
        int threat = currentThreat();
        if (slayerRules != null && (!slayerRules.dragonfireReady(player) || !needsFood(threat))
                && slayerRules.refreshProtection(player)) {
            activity = "Refreshing dragonfire protection";
            return null;
        }
        if (slayerRules != null && !slayerRules.dragonfireReady(player))
            return "Full dragonfire protection is about to expire. Banking for more antifire.";
        if (threat > 0 && needsFood(threat)) {
            Item food = bestFood(player.getMaxHp() - player.getHp(), player.getHp() <= threat * 2);
            if (food == null) return "Health is low and no supported food remains. Take manual control.";
            NPC interrupted = target;
            if (eatWhenReady(food) && interrupted != null && combat.getTarget() == null
                    && available(interrupted) && local(interrupted.getPosition(), ANCHOR_RADIUS)
                    && Wilderness.getLevel(interrupted.getPosition()) == 0 && combat.canAttack(interrupted, false)) {
                // The normal food handler clears combat but still applies its native attack delay.
                NPCActionHandler.handleAction(player, interrupted.getDef().attackOption, interrupted.getIndex(), 0);
            }
            activity = "Recovering health";
        }

        if (target != null) {
            if (slayerRules != null && !needsFood(threat)) chooseProtection(target);
            if (slayerRules != null && tick >= nextFinisher && slayerRules.finish(player, target)) {
                nextFinisher = tick + 4;
                fightProgress = lastProgress = tick;
                activity = "Using the required finishing item";
                return null;
            }
            return watchFight(tick);
        }
        clearPrayer();
        if (dropTile != null && pollLoot(tick)) return null;
        if (isGoalReached()) return goalMessage();
        if ((smokeDevil || skill == StatType.Slayer) && player.slayerTaskRemaining <= 0) return "Current Slayer assignment complete";
        if (tick - lastProgress >= NO_TARGET_TICKS)
            return "No eligible, reachable target nearby. Move to a suitable area and restart.";
        if (tick < nextScan || !player.getMovement().isAtDestination()) return null;
        nextScan = tick + RESCAN_TICKS;

        NPC next = chooseTarget(tick);
        if (next == null) {
            activity = "Waiting for a suitable target";
            return null;
        }
        if (!selectStyle()) return styleMessage();
        int risk = maxDamage(next);
        if ((smokeDevil || risk > 0 && needsFood(risk))
                && bestFood(20, true) == null)
            return "Bring food before engaging " + next.getDef().name;
        if (needsFood(risk)) {
            eatWhenReady(bestFood(player.getMaxHp() - player.getHp(), true));
            activity = "Recovering health before engaging";
            return null;
        }
        target = next;
        targetHp = next.getHp();
        skillXp = player.getStats().get(skill).experience;
        fightProgress = tick;
        chooseProtection(next);
        NPCActionHandler.handleAction(player, next.getDef().attackOption, next.getIndex(), 0);
        if (combat.getTarget() != next) {
            rejected.put(next, tick + NO_DAMAGE_TICKS);
            clearTarget();
            return null;
        }
        activity = "Fighting " + next.getDef().name;
        return null;
    }

    private String checkRequirements() {
        if (smokeDevil) {
            if (player.getStats().get(StatType.Slayer).currentLevel < 93)
                return "Thermonuclear Smoke Devil requires level 93 Slayer";
            if (player.getEquipment().getId(Equipment.SLOT_HAT) != FACEMASK && !Slayer.hasSlayerHelmEquipped(player))
                return "Equip a facemask or Slayer helmet before fighting the Smoke Devil";
            if (!isGoalReached() && assignment == null) return "Get the appropriate Slayer assignment first";
        } else if (skill == StatType.Slayer) {
            if (player.slayerTaskDangerous) return "Wilderness Slayer is outside this mode";
            if (assignment == null) return "Get a Slayer assignment in game first";
        }
        if (assignment != null && player.slayerTaskRemaining > 0 && Slayer.getTask(player) != assignment)
            return "The Slayer assignment changed";
        return null;
    }

    private String watchFight(long tick) {
        if (combat.getTarget() != target || !local(target.getPosition(), ANCHOR_RADIUS)
                || Wilderness.getLevel(target.getPosition()) > 0
                || slayerRules != null && slayerRules.requiresRanged()
                        && !SlayerRangedTargeting.canFireFrom(player.getPosition(), target)) {
            rejectTarget(tick);
            return null;
        }
        double xp = player.getStats().get(skill).experience;
        if (target.getHp() < targetHp || xp > skillXp) {
            targetHp = target.getHp();
            skillXp = xp;
            fightProgress = lastProgress = tick;
        } else if (tick - fightProgress >= NO_DAMAGE_TICKS) {
            rejectTarget(tick);
            if (smokeDevil) return "No damage dealt. Check gear and reachability before retrying the boss.";
            activity = "Trying another target after a stalled fight";
            return null;
        }
        if (!activity.equals("Recovering health") || !needsFood(currentThreat()))
            activity = "Fighting " + target.getDef().name;
        return null;
    }

    private NPC chooseTarget(long tick) {
        rejected.values().removeIf(until -> until <= tick);
        NPC best = null;
        int bestScore = Integer.MAX_VALUE;
        Position here = player.getPosition();
        for (NPC npc : player.localNpcs()) {
            if (!available(npc) || rejected.containsKey(npc) || !local(npc.getPosition(), ANCHOR_RADIUS)
                    || Wilderness.getLevel(npc.getPosition()) > 0 || distance(here, npc.getPosition()) > COMBAT_RADIUS
                    || npc.getDef().attackOption < 1 || npc.getDef().attackOption > 5) continue;
            if (smokeDevil) {
                if (npc.getId() != SMOKE_DEVIL_ID || npc.sixSystemsRewardless || !(npc.getCombat() instanceof ThermonuclearSmokeDevil)
                        || assignment == null || !Slayer.isTask(player, npc)) continue;
            } else {
                if ((slayerRules == null ? npc.getCombat().getClass() != BasicCombat.class
                        : SlayerLoadout.forNpc(npc) != slayerRules || !slayerRules.canFight(player) || npc.sixSystemsRewardless)
                        || npc.sixSystemsBossOwner != null
                        || !manageable(maxDamage(npc), player.getMaxHp())
                        || (skill == StatType.Slayer && !Slayer.isTask(player, npc))) continue;
            }
            Entity recentAttacker = npc.getCombat().lastAttacker;
            if (npc.getCombat().isDefending(8) && recentAttacker != null && recentAttacker.player != null
                    && recentAttacker.player != player) continue;
            if (!combat.canAttack(npc, false)) continue;
            if (slayerRules != null && (!SlayerWorld.safeTile(npc.getPosition())
                    || (slayerRules.requiresRanged() ? !SlayerRangedTargeting.canFireFrom(here, npc)
                    : !SlayerWorld.reachable(here, npc.getPosition(), npc.getSize())))) continue;
            int score = distance(here, npc.getPosition()) * 8 + Math.max(0, maxDamage(npc));
            if (combat.lastAttacker == npc && combat.isDefending(5)) score -= 32;
            if (score < bestScore) {
                best = npc;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean available(NPC npc) {
        return npc != null && !npc.isRemoved() && !npc.isHidden() && npc.getHeight() == player.getHeight()
                && npc.getCombat() != null && npc.getCombat().getInfo() != null && npc.getDef() != null
                && npc.getIndex() >= 0 && World.getNpc(npc.getIndex()) == npc;
    }

    private int currentThreat() {
        int risk = 0;
        if (target != null && available(target)) risk = maxDamage(target);
        Entity attacker = combat.lastAttacker;
        if (combat.isDefending(5) && attacker != null && available(attacker.npc))
            risk = Math.max(risk, maxDamage(attacker.npc));
        return risk;
    }

    private int maxDamage(NPC npc) {
        if (slayerRules != null && SlayerLoadout.forNpc(npc) == SlayerLoadout.MITHRIL_DRAGON
                && !SlayerRangedTargeting.canFireFrom(player.getPosition(), npc))
            return npc.getCombat().getInfo().max_damage;
        return slayerRules == null ? npc.getCombat().getInfo().max_damage : SlayerLoadout.combatRisk(npc);
    }

    static boolean manageable(int maxDamage, int maxHp) {
        return maxDamage >= 0 && maxHp > 0 && (maxDamage == 0 || maxDamage <= Math.max(1, maxHp / 4));
    }

    static boolean needsFood(int hp, int maxHp, int maxDamage) {
        return maxDamage > 0 && hp < maxHp && (hp <= maxDamage * 2L || hp * 5L <= maxHp * 3L);
    }

    private boolean needsFood(int maxDamage) {
        return slayerSettings == null ? needsFood(player.getHp(), player.getMaxHp(), maxDamage)
                : maxDamage > 0 && player.getHp() < player.getMaxHp()
                && (player.getHp() <= maxDamage * 2L
                || player.getHp() * 100L <= player.getMaxHp() * (long) slayerSettings.healthPercent());
    }

    private Item bestFood(int missingHealth, boolean urgent) {
        Item best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Item item : player.getInventory().getItems()) {
            if (item == null) continue;
            for (int i = 0; i < FOODS.length; i++) {
                if (item.getId() != FOODS[i] || eatAction(item) == null) continue;
                int heal = HEALING[i];
                int score = urgent ? heal : Math.min(missingHealth, heal) * 32 - Math.max(0, heal - missingHealth);
                if (score > bestScore) {
                    best = item;
                    bestScore = score;
                }
                break;
            }
        }
        return best;
    }

    private boolean eatWhenReady(Item food) {
        return eatWhenReady(player, food);
    }

    static boolean eatDuringTravel(Player player) {
        for (int i = FOODS.length - 1; i >= 0; i--) {
            Item food = player.getInventory().findItem(FOODS[i]);
            if (food != null && eatWhenReady(player, food)) return true;
        }
        return false;
    }

    private static boolean eatWhenReady(Player player, Item food) {
        if (food == null || player.eatDelay.isDelayed() || player.karamDelay.isDelayed() || player.potDelay.isDelayed())
            return false;
        ItemAction eat = eatAction(food);
        if (eat == null) return false;
        int hp = player.getHp();
        eat.handle(player, food);
        return player.getHp() > hp;
    }

    private static ItemAction eatAction(Item item) {
        ItemDef def = item.getDef();
        if (def == null || def.isNote() || def.inventoryActions == null) return null;
        int choice = def.getOption("eat") - 1;
        return choice >= 0 && choice < def.inventoryActions.length ? def.inventoryActions[choice] : null;
    }

    private void chooseProtection(NPC npc) {
        if (smokeDevil || slayerRules == SlayerLoadout.CAVE_KRAKEN || npc.getCombat().getInfo().max_damage < 5) return;
        Prayer wanted = protectionFor(npc.getCombat().getAttackStyle());
        if (wanted == null || player.getStats().get(StatType.Prayer).fixedLevel < wanted.level) return;
        if (slayerRules != null) SlayerLoadout.drinkPrayer(player);
        if (player.getStats().get(StatType.Prayer).currentLevel < 3) return;
        for (Prayer overhead : OVERHEADS) if (player.getPrayer().isActive(overhead)) return;
        player.getPrayer().toggle(wanted);
        if (player.getPrayer().isActive(wanted)) ownPrayer = wanted;
    }

    private static Prayer protectionFor(AttackStyle style) {
        if (style == null) return null;
        if (style.isMelee()) return Prayer.PROTECT_FROM_MELEE;
        if (style == AttackStyle.RANGED || style == AttackStyle.MAGICAL_RANGED) return Prayer.PROTECT_FROM_MISSILES;
        if (style == AttackStyle.MAGIC) return Prayer.PROTECT_FROM_MAGIC;
        if (style == AttackStyle.MAGICAL_MELEE) return Prayer.PROTECT_FROM_MELEE;
        return null;
    }

    private void clearPrayer() {
        if (ownPrayer != null && player.getPrayer().isActive(ownPrayer))
            player.getPrayer().deactivate(ownPrayer);
        ownPrayer = null;
    }

    private boolean selectStyle() {
        if (slayerRules != null) return slayerRules.canFight(player);
        if (!smokeDevil && (skill == StatType.Hitpoints || skill == StatType.Slayer)) return true;
        WeaponType weapon = combat.weaponType;
        if (weapon == null || weapon.attackSets == null) return false;
        if (combat.autocastSpell != null) {
            return !smokeDevil && (skill == StatType.Magic
                    || skill == StatType.Defence && Config.DEFENSIVE_CAST.get(player) == 1);
        }
        int existing = priority(skill, smokeDevil, combat.getAttackStyle(), combat.getAttackType());
        int choice = -1;
        int priority = existing;
        for (int i = 0; i < weapon.attackSets.length; i++) {
            AttackSet set = weapon.attackSets[i];
            if (set == null) continue;
            int value = priority(skill, smokeDevil, set.style, set.type);
            if (value > priority) {
                priority = value;
                choice = i;
            }
        }
        if (choice >= 0) combat.changeAttackSet(choice);
        return priority > 0;
    }

    static int priority(StatType skill, boolean smokeDevil, AttackStyle style, AttackType type) {
        if (style == null || type == null) return 0;
        if (smokeDevil) return style.isMelee() ? 2 : 0;
        switch (skill) {
            case Attack:
                return style.isMelee() ? type == AttackType.ACCURATE ? 2 : type == AttackType.CONTROLLED ? 1 : 0 : 0;
            case Strength:
                return style.isMelee() ? type == AttackType.AGGRESSIVE ? 2 : type == AttackType.CONTROLLED ? 1 : 0 : 0;
            case Defence:
                if (style.isMelee()) return type == AttackType.DEFENSIVE ? 2 : type == AttackType.CONTROLLED ? 1 : 0;
                return (style.isMagic() || style.isRanged()) && type == AttackType.LONG_RANGED ? 1 : 0;
            case Ranged:
                return style.isRanged() ? type == AttackType.RAPID_RANGED ? 2 : 1 : 0;
            case Magic:
                return style.isMagic() ? 2 : 0;
            default:
                return 0;
        }
    }

    private String styleMessage() {
        return smokeDevil ? "Equip a melee weapon and disable autocast for this boss"
                : "Equip a weapon or configure autocast that trains " + skill;
    }

    private boolean pollLoot(long tick) {
        if (slayerSettings != null && !slayerSettings.loot) {
            dropTile = null;
            clearLootAction();
            return false;
        }
        if (lootEvent != null) {
            if (actions.ownerAutomationAction() == lootEvent && tick < lootDeadline) return true;
            clearLootAction();
        }
        if (tick < lootReady) {
            activity = "Waiting for drops";
            return true;
        }
        if (tick >= lootDeadline || attemptedDrops.size() >= MAX_LOOT || player.getInventory().isFull()
                || distance(player.getPosition(), dropTile) > COMBAT_RADIUS || player.getHeight() != dropTile.getZ()
                || combat.isDefending(4) && combat.lastAttacker != null && !combat.lastAttacker.getCombat().isDead()) {
            dropTile = null;
            return false;
        }
        Tile tile = Tile.get(dropTile.getX(), dropTile.getY(), dropTile.getZ());
        if (tile != null && tile.groundItems != null) {
            for (GroundItem drop : tile.groundItems) {
                if (!ownedDrop(drop) || !attemptedDrops.add(drop)) continue;
                player.getRouteFinder().routeGroundItem(drop, distance -> {
                    if (!stopped && combat.getTarget() == null && local(player.getPosition(), ANCHOR_RADIUS)
                            && Wilderness.getLevel(player.getPosition()) == 0 && ownedDrop(drop)) {
                        Map<Item, Integer> before = slayerLoot == null ? null : inventoryAmounts(drop.id);
                        int id = drop.id;
                        drop.pickup(player, distance);
                        if (before != null) recordDrop(id, before);
                    }
                });
                lootEvent = actions.ownerAutomationAction();
                activity = "Collecting owned drops";
                return true;
            }
        }
        dropTile = null;
        return false;
    }

    private boolean ownedDrop(GroundItem drop) {
        return drop != null && !drop.isRemoved() && drop.z == player.getHeight()
                && drop.getTimeDropped() == 0 && drop.originalOwner == player.getUserId()
                && (drop.activeOwner == -1 || drop.activeOwner == player.getUserId());
    }

    private Map<Item, Integer> inventoryAmounts(int id) {
        Map<Item, Integer> result = new IdentityHashMap<>();
        for (Item item : player.getInventory().getItems())
            if (item != null && item.getId() == id) result.put(item, item.getAmount());
        return result;
    }

    private void recordDrop(int id, Map<Item, Integer> before) {
        inventoryAmounts(id).forEach((item, amount) -> {
            int gained = amount - before.getOrDefault(item, 0);
            if (gained > 0) slayerLoot.merge(item, gained, Integer::sum);
        });
    }

    boolean settlingLoot() {
        return dropTile != null || target != null && (target.getHp() <= 0 || target.getCombat().isDead());
    }

    private void clearLootAction() {
        if (lootEvent != null && actions.cancelOwnerAutomationAction(lootEvent)) player.getMovement().reset();
        lootEvent = null;
    }

    private void rejectTarget(long tick) {
        rejected.put(target, tick + NO_DAMAGE_TICKS);
        clearTarget();
    }

    private void clearTarget() {
        if (target != null && combat.getTarget() == target) {
            combat.reset();
            player.getMovement().reset();
        }
        target = null;
        clearPrayer();
    }

    public int remainingBossKills() { return smokeDevil ? Math.max(0, goal - bossKills()) : 0; }

    private int bossKills() {
        return player.thermonuclearSmokeDevilKills.getKills() - startingKills;
    }

    private boolean isGoalReached() {
        return smokeDevil ? bossKills() >= goal : player.getStats().get(skill).fixedLevel >= goal;
    }

    private String goalMessage() {
        return smokeDevil ? "Thermonuclear Smoke Devil kill goal reached" : skill + " goal reached";
    }

    private boolean local(Position position, int radius) {
        return position.getZ() == anchor.getZ() && distance(anchor, position) <= radius;
    }

    private static int distance(Position a, Position b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    @Override
    public void stop() {
        stopped = true;
        clearTarget();
        clearLootAction();
    }
}
