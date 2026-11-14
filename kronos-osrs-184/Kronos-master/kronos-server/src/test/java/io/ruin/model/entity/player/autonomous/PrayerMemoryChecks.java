package io.ruin.model.entity.player.autonomous;

import io.ruin.model.combat.AttackStyle;
import io.ruin.model.skills.prayer.Prayer;

/** Pure evidence checks; callable from staging integration tests without editing players. */
public final class PrayerMemoryChecks {
    private static void check(boolean ok, String message) {if(!ok)throw new AssertionError(message);}
    public static void run() {
        check(ProtectionPrayerPolicy.forStyle(AttackStyle.STAB)==Prayer.PROTECT_FROM_MELEE,"melee mapping");
        check(ProtectionPrayerPolicy.forStyle(AttackStyle.MAGICAL_RANGED)==Prayer.PROTECT_FROM_MISSILES,"magical ranged mapping follows server defence rules");
        check(ProtectionPrayerPolicy.forStyle(AttackStyle.MAGIC)==Prayer.PROTECT_FROM_MAGIC,"magic mapping");
        check(ProtectionPrayerPolicy.forStyle(AttackStyle.DRAGONFIRE)==null,"dragonfire must not be guessed");
        AdventurerMemory.Encounter e=new AdventurerMemory.Encounter();e.deaths=1;
        AdventurerMemory.DamageSample s=new AdventurerMemory.DamageSample();
        s.style="MAGIC";s.hpBefore=4;s.maxHp=10;s.damage=5;s.food=0;s.prayerLevel=50;s.prayerPoints=0;s.protectionPrayer="none";
        AdventurerMemory.diagnose(e,s);
        check(e.riskFactors.contains("NO_EDIBLE_FOOD_OR_DRINK"),"missing supply evidence");
        check(e.riskFactors.contains("LOW_HP_AT_FATAL_HIT"),"missing low health evidence");
        check(e.riskFactors.contains("NO_PRAYER_POINTS_AT_DAMAGE"),"missing exhausted prayer evidence");
        check(!e.riskFactors.contains("PROTECTION_MISMATCH_AT_DAMAGE"),"exhausted points misclassified as wrong prayer");
        s.prayerPoints=50;s.protectionPrayer="PROTECT_FROM_MELEE";
        AdventurerMemory.diagnose(e,s);
        check(e.riskFactors.contains("PROTECTION_MISMATCH_AT_DAMAGE"),"wrong overhead snapshot not recorded");
        s.prayerIgnored=true;AdventurerMemory.diagnose(e,s);
        check(e.riskFactors.contains("ATTACK_IGNORES_PROTECTION"),"ignored protection not identified");
        check(!e.riskFactors.contains("PROTECTION_MISMATCH_AT_DAMAGE"),"ignored prayer incorrectly blamed");
        s.prayerIgnored=false;s.prayerLevel=1;AdventurerMemory.diagnose(e,s);
        check(e.riskFactors.contains("PROTECTION_LEVEL_NOT_REACHED"),"missing level requirement evidence");
        s.damage=20;AdventurerMemory.diagnose(e,s);
        check(e.riskFactors.contains("FATAL_HIT_AT_LEAST_MAX_HP"),"one-shot evidence");
        AdventurerMemory.diagnose(e,null);check(e.riskFactors.isEmpty(),"invented evidence without telemetry");
        e.deaths=3;AdventurerMemory.diagnose(e,s);check(e.nextAttempt.contains("disabled until reviewed"),"repeat failure safeguard");
        System.out.println("CHECK PASS prayer/death evidence classification: supplies, health, points, level, mismatch, ignored protection, unsupported styles, unknown data, repeated failure");
    }
}