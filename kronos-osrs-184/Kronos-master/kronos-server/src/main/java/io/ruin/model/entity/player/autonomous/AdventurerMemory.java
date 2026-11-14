package io.ruin.model.entity.player.autonomous;

import com.google.gson.annotations.Expose;
import io.ruin.Server;
import io.ruin.model.combat.Hit;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import io.ruin.model.item.Item;
import java.util.*;

/** Bounded, persistent evidence and conservative retry policy; no fabricated causes. */
public final class AdventurerMemory {
    public static final class DamageSample {
        @Expose public long tick;
        @Expose public String enemy, style, activity;
        @Expose public int damage, hpBefore, maxHp, food, x, y, z, prayerPoints, prayerLevel;
        @Expose public String protectionPrayer;
        @Expose public boolean prayerIgnored;
        @Expose public boolean usableRecoveryCountVerified;
        @Expose public List<Integer> equipment = new ArrayList<>();
    }
    public static final class Encounter {
        @Expose public int deaths;
        @Expose public long cooldownTicks;
        @Expose public String observation, nextAttempt;
        @Expose public List<String> riskFactors = new ArrayList<>();
        @Expose public List<DamageSample> finalHits = new ArrayList<>();
    }
    /** Nonfatal observations are separate from death evidence and encounter counts. */
    public static final class AvoidedThreat {
        @Expose public String enemy, observation;
        @Expose public int x, y, z, observations;
        @Expose public long cooldownTicks;
    }
    @Expose public Map<String,Encounter> encounters = new LinkedHashMap<>();
    @Expose public Map<String,Integer> failedRoutes = new LinkedHashMap<>();
    @Expose public Map<String,AvoidedThreat> avoidedThreats = new LinkedHashMap<>();
    private transient Deque<DamageSample> recent;

    public void hit(Adventurer p, Hit hit) {
        if (recent == null) recent = new ArrayDeque<>();
        DamageSample s = new DamageSample();
        s.tick = Server.currentTick(); s.enemy = enemy(hit);
        s.style = hit.attackStyle == null ? "unknown" : hit.attackStyle.name();
        s.damage = hit.damage; s.hpBefore = p.getHp(); s.maxHp = p.getMaxHp();
        s.prayerPoints = p.getStats().get(StatType.Prayer).currentLevel;
        s.prayerLevel = p.getStats().get(StatType.Prayer).fixedLevel;
        s.prayerIgnored = hit.prayerIgnored;
        s.protectionPrayer = "none";
        for(Prayer prayer : ProtectionPrayerPolicy.PROTECTION_PRAYERS)
            if(p.getPrayer().isActive(prayer)) s.protectionPrayer=prayer.name();
        s.x = p.getAbsX(); s.y = p.getAbsY(); s.z = p.getHeight();
        s.activity = p.port == null ? "unknown" : p.port.description();
        s.food = AdventurerSafety.recoveryCount(p);
        s.usableRecoveryCountVerified = true;
        for (Item item : p.getEquipment().getItems()) if (item != null) s.equipment.add(item.getId());
        recent.addLast(s); while (recent.size() > 12) recent.removeFirst();
    }
    public void died(Hit hit) {
        String key = enemy(hit);
        if (!encounters.containsKey(key) && encounters.size() >= 32) encounters.remove(encounters.keySet().iterator().next());
        Encounter e = encounters.computeIfAbsent(key, ignored -> new Encounter());
        e.deaths++; e.cooldownTicks = Math.min(144000, 3000L << Math.min(e.deaths - 1, 5));
        e.finalHits.clear();
        if (recent != null) for (DamageSample s : recent) if (Server.currentTick() - s.tick <= 20) e.finalHits.add(s);
        DamageSample last = e.finalHits.isEmpty() ? null : e.finalHits.get(e.finalHits.size() - 1);
        e.observation = last == null ? "Unknown: no recent damage telemetry" :
                "Fatal " + last.damage + " damage at " + last.hpBefore + "/" + last.maxHp + " HP; style=" + last.style + "; " +
                        (last.usableRecoveryCountVerified ? "usable recovery items=" : "legacy food/drink labels (usability unverified)=") +
                        last.food + "; protection=" + last.protectionPrayer + "; prayer points=" + last.prayerPoints + ". Mechanic cause not established.";
        diagnose(e, last);
        if (recent != null) recent.clear();
    }
    /** Conditions observed at damage delivery, not speculative boss-mechanic causes. */
    static void diagnose(Encounter e, DamageSample last) {
        if(e.riskFactors == null) e.riskFactors = new ArrayList<>();
        e.riskFactors.clear();
        List<String> actions = new ArrayList<>();
        actions.add("Cooldown and restore health");
        if(last != null) {
            if(last.food == 0) {e.riskFactors.add("NO_EDIBLE_FOOD_OR_DRINK");actions.add("obtain edible recovery supplies before retrying");}
            if(last.hpBefore < Math.ceil(last.maxHp * .5)) {e.riskFactors.add("LOW_HP_AT_FATAL_HIT");actions.add("heal earlier");}
            if(last.maxHp > 0 && last.damage >= last.maxHp) {e.riskFactors.add("FATAL_HIT_AT_LEAST_MAX_HP");actions.add("healing alone cannot prevent this recorded hit; review avoidance or mitigation");}
            Prayer expected = ProtectionPrayerPolicy.forStyle(parseStyle(last.style));
            if(last.prayerIgnored) {e.riskFactors.add("ATTACK_IGNORES_PROTECTION");actions.add("do not rely on protection prayer against this attack");}
            else if(expected != null) {
                if(last.prayerLevel < expected.level) {e.riskFactors.add("PROTECTION_LEVEL_NOT_REACHED");actions.add("train Prayer legitimately before relying on " + expected.name());}
                else if(last.prayerPoints <= 0) {e.riskFactors.add("NO_PRAYER_POINTS_AT_DAMAGE");actions.add("recharge Prayer before retrying");}
                else if(!expected.name().equals(last.protectionPrayer)) {e.riskFactors.add("PROTECTION_MISMATCH_AT_DAMAGE");actions.add("use " + expected.name() + " for this observed style; damage-time snapshot does not establish defence-time timing");}
            }
        }
        actions.add("no boss retry without a supported encounter plan");
        e.nextAttempt = e.deaths >= 3 ? "Repeated failure: encounter disabled until reviewed; choose an easier activity" : String.join("; ", actions);
    }
    private static AttackStyle parseStyle(String style) {
        if(style == null) return null;
        try {return AttackStyle.valueOf(style);} catch(IllegalArgumentException ignored) {return null;}
    }
    public AttackStyle recentStyle(String enemy) {
        if (recent == null || enemy == null) return null;
        Iterator<DamageSample> it=recent.descendingIterator();
        while(it.hasNext()) { DamageSample s=it.next(); if(Server.currentTick()-s.tick>8)break;
            if(enemy.equals(s.enemy))return parseStyle(s.style);
        }
        return null;
    }
    public void tick() {
        for (Encounter e : encounters.values()) if (e.cooldownTicks > 0) e.cooldownTicks--;
        if (avoidedThreats != null) for (AvoidedThreat e : avoidedThreats.values()) if (e.cooldownTicks > 0) e.cooldownTicks--;
    }
    public boolean mayAttempt(String enemy) { Encounter e = encounters.get(enemy); return e == null || (e.deaths < 3 && e.cooldownTicks == 0); }
    /** A recorded dangerous location is avoided while that encounter is parked. */
    public boolean avoidsLocation(int x,int y,int z) {
        if (avoidedThreats != null) for (AvoidedThreat threat : avoidedThreats.values())
            if (threat.cooldownTicks > 0 && threat.z == z
                    && Math.max(Math.abs(threat.x-x), Math.abs(threat.y-y)) <= 12) return true;
        for(Encounter encounter:encounters.values()) {
            if((encounter.deaths<3 && encounter.cooldownTicks<=0) || encounter.finalHits==null || encounter.finalHits.isEmpty())continue;
            DamageSample last=encounter.finalHits.get(encounter.finalHits.size()-1);
            if(last.z==z && Math.max(Math.abs(last.x-x),Math.abs(last.y-y))<=12)return true;
        }
        return false;
    }
    /** Called once when an unexpected observed attacker causes a retreat. */
    public void avoidThreat(String enemy, int x, int y, int z) {
        if (avoidedThreats == null) avoidedThreats = new LinkedHashMap<>();
        String key = enemy + "@" + Math.floorDiv(x,8) + "," + Math.floorDiv(y,8) + "," + z;
        if (!avoidedThreats.containsKey(key) && avoidedThreats.size() >= 32)
            avoidedThreats.remove(avoidedThreats.keySet().iterator().next());
        AvoidedThreat threat = avoidedThreats.computeIfAbsent(key, ignored -> new AvoidedThreat());
        threat.enemy = enemy; threat.x = x; threat.y = y; threat.z = z;
        threat.observations = Math.min(100, threat.observations + 1);
        threat.cooldownTicks = Math.min(6000L, 600L << Math.min(threat.observations - 1, 4));
        threat.observation = "Unexpected attack observed at this position; activity abandoned and normal escape requested. This is not a recorded death.";
    }
    public double healingThreshold(String enemy) { Encounter e = encounters.get(enemy); return e == null ? .50 : Math.min(.80, .50 + e.deaths * .10); }
    public void routeFailed(String key) {
        if (!failedRoutes.containsKey(key) && failedRoutes.size() >= 64) failedRoutes.remove(failedRoutes.keySet().iterator().next());
        failedRoutes.put(key, Math.min(100, failedRoutes.getOrDefault(key, 0) + 1));
    }
    private static String enemy(Hit hit) {
        if (hit == null || hit.attacker == null) return "unknown";
        if (hit.attacker.npc != null) return "npc:" + hit.attacker.npc.getId();
        return "player";
    }
}
