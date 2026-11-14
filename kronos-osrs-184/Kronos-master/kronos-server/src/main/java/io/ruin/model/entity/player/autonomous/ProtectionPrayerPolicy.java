package io.ruin.model.entity.player.autonomous;

import io.ruin.model.combat.AttackStyle;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import rsps.sim.BuildPolicy;

/** Uses ordinary prayer activation/drain rules; never reads an unannounced queued hit. */
final class ProtectionPrayerPolicy {
    static final Prayer[] PROTECTION_PRAYERS = {Prayer.PROTECT_FROM_MAGIC, Prayer.PROTECT_FROM_MISSILES, Prayer.PROTECT_FROM_MELEE};
    static Prayer forStyle(AttackStyle style) {
        if (style == null) return null;
        if (style.isMelee() || style.isMagicalMelee()) return Prayer.PROTECT_FROM_MELEE;
        if (style.isRanged() || style.isMagicalRanged()) return Prayer.PROTECT_FROM_MISSILES;
        if (style.isMagic()) return Prayer.PROTECT_FROM_MAGIC;
        return null; // Dragonfire and special attacks need encounter-specific rules.
    }
    static void update(Adventurer p, NPC enemy) {
        AttackStyle style = null;
        if (enemy != null && !enemy.isRemoved() && enemy.getCombat() != null && !enemy.getCombat().isDead()) {
            // BasicCombat has one fixed style. A custom boss's default style is not
            // a reliable prediction: use only a recently observed landed attack.
            if (enemy.getCombat().getClass() == io.ruin.model.activities.miscpvm.BasicCombat.class)
                style = enemy.getCombat().getAttackStyle();
            else style = p.memory.recentStyle("npc:" + enemy.getId());
        }
        apply(p, style, enemy != null);
    }
    static void apply(Adventurer p, AttackStyle style, boolean threatened) {
        if (p.isLocked() || p.isStunned()) return; // Same input restriction as PlayerPrayer.toggle.
        Prayer wanted = threatened ? forStyle(style) : null;
        if (p.archetype == BuildPolicy.Archetype.SKILLER || p.quarantineReason != null || p.getCombat().isDead()) wanted = null;
        if (wanted != null && (p.getStats().get(StatType.Prayer).fixedLevel < wanted.level || p.getStats().get(StatType.Prayer).currentLevel <= 0)) wanted = null;
        for (Prayer prayer : PROTECTION_PRAYERS)
            if (prayer != wanted && p.getPrayer().isActive(prayer)) p.getPrayer().toggle(prayer);
        if (wanted != null && !p.getPrayer().isActive(wanted)) p.getPrayer().toggle(wanted);
    }
}