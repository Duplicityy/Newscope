package io.ruin.model.entity.player.autonomous;

import com.google.gson.annotations.Expose;
import io.ruin.Server;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.item.Item;
import io.ruin.model.skills.herblore.Potion;
import io.ruin.model.skills.magic.spells.HomeTeleport;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import rsps.sim.BuildPolicy;
import rsps.sim.ProgressionBrain;

/** Cheap world-thread safety pass, independent of the slower activity scheduler. */
public final class AdventurerSafety {
    private final Adventurer p;
    private long lastTick = -1, lastConsumptionAttempt = -1, consumedTick = -1, nextEscape;
    private boolean retreating, busy, trainingCombat, threatRecorded;
    @Expose public int hp, maxHp, food, prayerPoints;
    @Expose public long heals, prayerDoses, antidoteDoses, escapeAttempts, escapes;
    @Expose public String detail = "checking safety", threat = "none";
    @Expose public boolean recovering;
    @Expose public String lastRetreatThreat = "none";
    @Expose public int lastRetreatX, lastRetreatY, lastRetreatZ;
    @Expose public long lastRetreatTick;

    AdventurerSafety(Adventurer p) { this.p = p; }

    public void tick(long now) {
        if (now <= lastTick) return;
        lastTick = now;
        busy = false;
        snapshot();
        if (!p.isOnline() || p.quarantineReason != null) return;
        if (p.getCombat().isDead() || p.getHp() <= 0) {
            retreating = false;
            threatRecorded = false;
            busy = recovering = true;
            detail = "waiting for ordinary death recovery";
            return;
        }

        Entity current = p.getCombat().getTarget();
        Entity lastAttacker = p.getCombat().isDefending(17) ? p.getCombat().lastAttacker : null;
        boolean attacked = closeThreat(lastAttacker);
        // Normal eating/drinking clears PlayerCombat.target. The port still owns
        // that encounter, so a returning hit must not become unexpected aggro.
        NPC ownTarget = current != null ? current.npc : attacked ? lastAttacker.npc : null;
        boolean knownEncounter = ownTarget != null && p.port != null && p.port.intentionalCombat(ownTarget)
                && p.archetype != BuildPolicy.Archetype.SKILLER;
        boolean intentional = knownEncounter && p.brain != null && p.brain.state() == ProgressionBrain.State.TRAINING;
        trainingCombat = intentional;
        NPC enemy = attacked && lastAttacker.npc != null ? lastAttacker.npc : intentional ? ownTarget : null;
        threat = attacked ? enemyKey(lastAttacker) : intentional ? enemyKey(ownTarget) : "none";
        boolean unexpected = attacked && (!knownEncounter || lastAttacker != ownTarget);
        boolean leavingEncounter = attacked && knownEncounter && !intentional;
        boolean unsafeAttack = current != null && !intentional;
        int healAt = (int)Math.ceil(p.getMaxHp() * Math.max(.65, p.memory.healingThreshold(threat)));
        boolean lowHp = p.getHp() <= healAt;

        if (unexpected && !threatRecorded) {
            lastRetreatThreat = enemyKey(lastAttacker);
            lastRetreatX = p.getAbsX(); lastRetreatY = p.getAbsY(); lastRetreatZ = p.getHeight();
            lastRetreatTick = now;
            p.memory.avoidThreat(lastRetreatThreat, lastRetreatX, lastRetreatY, lastRetreatZ);
            if (p.port != null) p.port.onSafetyRetreat();
            threatRecorded = true;
        }

        if (unexpected || leavingEncounter || unsafeAttack || (intentional && !p.memory.mayAttempt(enemyKey(ownTarget)))) {
            retreating = true;
            detail = unexpected ? "escaping an unexpected attacker" : leavingEncounter
                    ? "leaving the current encounter before resting" : "stopping an unsupported combat action";
        }
        if (p.isLocked()) {
            busy = retreating;
            recovering = retreating || p.isPoisoned() || lowHp;
            return; // Never interrupt death, teleport or other locked mechanics.
        }

        // Use only ordinary consumable handlers. A rejected click is not a heal.
        if (lowHp || ((retreating || p.isPoisoned()) && p.getHp() < recoveryHp())) heal();
        if (p.isPoisoned()) {
            if (drink(Potion.SUPER_ANTI_VENOM, Potion.ANTI_VENOM, Potion.ANTIDOTE_PLUS_PLUS,
                    Potion.ANTIDOTE_PLUS, Potion.SUPER_ANTIPOISON, Potion.ANTIPOISON,
                    Potion.SANFEW_SERUM, Potion.GUTHIX_REST)) antidoteDoses++;
            if (p.isPoisoned()) {
                retreating = true;
                detail = "poison recovery: use owned antidote and recovery supplies";
            }
        }

        AttackStyle style = knownStyle(enemy);
        Prayer protection = ProtectionPrayerPolicy.forStyle(style);
        if (protection != null && p.archetype != BuildPolicy.Archetype.SKILLER
                && p.getStats().get(StatType.Prayer).fixedLevel >= protection.level
                && p.getStats().get(StatType.Prayer).currentLevel <= Math.max(1, p.getStats().get(StatType.Prayer).fixedLevel / 10)) {
            if (drink(Potion.PRAYER, Potion.SUPER_RESTORE, Potion.SANFEW_SERUM)) prayerDoses++;
        }
        ProtectionPrayerPolicy.update(p, enemy);

        food = recoveryCount(p);
        if ((attacked || intentional) && ((food == 0 && p.getHp() <= healAt)
                || p.getHp() <= Math.ceil(p.getMaxHp() * .35))) {
            retreating = true;
            detail = food == 0 ? "retreating to replenish recovery supplies" : "retreating from critical health";
        }
        if (retreating) {
            // Cancel the old action immediately; do not wait for Port.nextAction.
            if (attacked || current != null) {
                p.resetActions(true, true, true);
                TrainingSites.release(p);
                if (now >= nextEscape) {
                    escapeAttempts++;
                    HomeTeleport.MODERN.clickAction.accept(p, 0);
                    nextEscape = now + 5;
                    if (p.isLocked()) {
                        escapes++;
                        detail = "normal home teleport started";
                    } else detail = "normal escape rejected; maintaining recovery and retrying";
                }
            }
            // Retained combat timeout prevents an unsafe RESTING state. At home,
            // allow the port to walk to its bank while waiting for food or regen.
            busy = p.isLocked() || attacked || p.getCombat().isDefending(17);
            if (!busy && !p.isPoisoned() && p.getHp() >= recoveryHp()) {
                retreating = false;
                threatRecorded = false;
                detail = "recovered safely";
            }
        } else if (!intentional && !attacked) detail = "safe";
        busy |= consumedTick == Server.currentTick();
        recovering = needsRecovery();
        snapshot();
    }

    public boolean needsRecovery() {
        return retreating || p.getCombat().isDead() || p.isPoisoned() || (!trainingCombat && p.getHp() < recoveryHp());
    }

    /** Passive low health with no carried food must not prevent bank resupply. */
    public boolean isBusy() { return busy || p.getCombat().isDead(); }
    public String description() { return detail; }

    public boolean heal() {
        if (p.getHp() >= p.getMaxHp() || p.isLocked() || p.isStunned()) return false;
        for (Item item : p.getInventory().getItems()) {
            if (isRecoveryItem(item) && consume(item, recoveryOption(item))) {
                heals++;
                detail = "consumed an owned recovery item";
                return true;
            }
        }
        return false;
    }

    public static int recoveryCount(Adventurer p) {
        int count = 0;
        for (Item item : p.getInventory().getItems()) if (isRecoveryItem(item)) count += item.getAmount();
        return count;
    }

    public static boolean isRecoveryItem(Item item) { return recoveryOption(item) > 0; }

    private static int recoveryOption(Item item) {
        if (item == null || item.getAmount() <= 0 || item.getDef().isNote()) return -1;
        int eat = registeredOption(item, "eat");
        if (eat > 0 && item.getDef().consumable) return eat;
        // Do not mistake every Drink label (beer, prayer potions, unimplemented
        // cups of tea) for food. These healing drinks have real normal handlers.
        if (matches(item, Potion.SARADOMIN_BREW, Potion.GUTHIX_REST)) return registeredOption(item, "drink");
        return -1;
    }

    private boolean drink(Potion... types) {
        if (p.isLocked() || p.isStunned()) return false;
        for (Item item : p.getInventory().getItems()) if (matches(item, types)
                && consume(item, registeredOption(item, "drink"))) return true;
        return false;
    }

    private boolean consume(Item item, int option) {
        long now = Server.currentTick();
        if (option < 1 || lastConsumptionAttempt == now || p.isLocked() || p.isStunned()) return false;
        lastConsumptionAttempt = now;
        int slot = item.getSlot(), id = item.getId(), amount = item.getAmount(), beforeHp = p.getHp();
        int beforePrayer = p.getStats().get(StatType.Prayer).currentLevel, beforePoison = p.poisonDamage;
        item.getDef().inventoryActions[option - 1].handle(p, item);
        Item after = slot < 0 ? null : p.getInventory().get(slot);
        boolean changed = after != item || item.getId() != id || item.getAmount() != amount
                || p.getHp() != beforeHp || p.getStats().get(StatType.Prayer).currentLevel != beforePrayer
                || p.poisonDamage != beforePoison;
        if (changed) consumedTick = now;
        return changed;
    }

    private static int registeredOption(Item item, String name) {
        if (item == null || item.getDef().inventoryActions == null) return -1;
        int option = KronosActivityPort.option(item.getDef().inventoryOptions, name);
        return option > 0 && option <= item.getDef().inventoryActions.length
                && item.getDef().inventoryActions[option - 1] != null ? option : -1;
    }

    private static boolean matches(Item item, Potion... potions) {
        if (item == null || item.getDef().isNote()) return false;
        for (Potion potion : potions) for (int id : potion.vialIds) if (id > 0 && item.getId() == id) return true;
        return false;
    }

    private int recoveryHp() { return (int)Math.ceil(p.getMaxHp() * .80); }
    private void snapshot() {
        hp = p.getHp(); maxHp = p.getMaxHp(); food = recoveryCount(p);
        prayerPoints = p.getStats().get(StatType.Prayer).currentLevel;
    }
    private boolean closeThreat(Entity entity) {
        return entity != null && entity.getCombat() != null && !entity.getCombat().isDead()
                && !entity.isHidden() && entity.getHeight() == p.getHeight()
                && entity.getPosition().isWithinDistance(p.getPosition(), 16)
                && (entity.npc == null || !entity.npc.isRemoved());
    }
    private AttackStyle knownStyle(NPC enemy) {
        if (enemy == null || enemy.isRemoved() || enemy.getCombat() == null || enemy.getCombat().isDead()) return null;
        return enemy.getCombat().getClass() == io.ruin.model.activities.miscpvm.BasicCombat.class
                ? enemy.getCombat().getAttackStyle() : p.memory.recentStyle(enemyKey(enemy));
    }
    private static String enemyKey(Entity enemy) { return enemy == null ? "unknown" : enemy.npc == null ? "player" : "npc:" + enemy.npc.getId(); }
}
