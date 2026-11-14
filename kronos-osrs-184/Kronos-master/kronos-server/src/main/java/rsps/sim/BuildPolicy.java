package rsps.sim;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable training goals and XP eligibility for a simulated character.
 *
 * <p>The server supplies its complete skill-to-target map and an exhaustive
 * combat-skill set. IDs are normalized with trim() and Locale.ROOT lowercase;
 * they must then match [a-z][a-z0-9_]*. Configuration entries that normalize to
 * the same ID are rejected rather than silently overwritten.</p>
 *
 * <p>This is a preflight policy, not an XP interception mechanism. A production
 * adapter must declare every possible XP reward of an activity and enforce the
 * same policy at the authoritative reward boundary. Skiller combat XP and base
 * levels must also be recorded at creation and checked against that baseline.</p>
 */
public final class BuildPolicy {
    private static final Pattern SKILL_ID = Pattern.compile("[a-z][a-z0-9_]*");

    public enum Archetype { MAIN, SKILLER, HYBRID }

    public enum CombatStyle { BALANCED, MELEE, RANGED, MAGIC }

    public final Archetype archetype;
    public final CombatStyle combatStyle;
    public final Map<String, Integer> targets;
    public final Map<String, Double> preferences;
    public final Set<String> forbiddenXpSkills;

    private final Set<String> serverSkills;

    private BuildPolicy(Archetype archetype, CombatStyle combatStyle,
                        Map<String, Integer> targets,
                        Map<String, Double> preferences,
                        Set<String> forbiddenXpSkills,
                        Set<String> serverSkills) {
        this.archetype = archetype;
        this.combatStyle = combatStyle;
        this.targets = Collections.unmodifiableMap(
                new LinkedHashMap<String, Integer>(targets));
        this.preferences = Collections.unmodifiableMap(
                new LinkedHashMap<String, Double>(preferences));
        this.forbiddenXpSkills = Collections.unmodifiableSet(
                new LinkedHashSet<String>(forbiddenXpSkills));
        this.serverSkills = Collections.unmodifiableSet(
                new LinkedHashSet<String>(serverSkills));
    }

    /**
     * Creates a build from server-defined, positive level targets.
     *
     * <p>Main and hybrid builds retain all targets. Mains prefer combat by a
     * factor of 1.7; hybrids have equal base weights. A selected combat style
     * doubles the weight of its associated skills without forbidding any other
     * skill. MELEE recognizes attack, strength and defence; RANGED recognizes
     * ranged; MAGIC recognizes magic.</p>
     *
     * <p>Skillers retain every noncombat target, including slayer if supplied,
     * and forbid every combat skill. They always use BALANCED because no combat
     * style applies. An unreachable noncombat target remains a target: the
     * controller must report its missing eligible activity rather than silently
     * treating it as complete.</p>
     *
     * @throws IllegalArgumentException for missing/invalid configuration,
     *         unknown combat skills, or an empty resulting target map
     */
    public static BuildPolicy create(Archetype archetype, CombatStyle combatStyle,
                                     Map<String, Integer> serverTargets,
                                     Set<String> combatSkills) {
        if (archetype == null || combatStyle == null) {
            throw new IllegalArgumentException("Archetype and combat style are required");
        }
        if (serverTargets == null || serverTargets.isEmpty()) {
            throw new IllegalArgumentException("At least one server skill target is required");
        }
        if (combatSkills == null || combatSkills.isEmpty()) {
            throw new IllegalArgumentException("An exhaustive, nonempty combat-skill set is required");
        }

        Map<String, Integer> allTargets = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : serverTargets.entrySet()) {
            String skill = canonicalId(entry.getKey());
            Integer level = entry.getValue();
            if (level == null || level.intValue() < 1) {
                throw new IllegalArgumentException("Target level must be positive for " + skill);
            }
            if (allTargets.containsKey(skill)) {
                throw new IllegalArgumentException("Duplicate normalized skill ID: " + skill);
            }
            allTargets.put(skill, level);
        }

        Set<String> combat = new LinkedHashSet<String>();
        for (String rawSkill : combatSkills) {
            String skill = canonicalId(rawSkill);
            if (!allTargets.containsKey(skill)) {
                throw new IllegalArgumentException("Combat skill is absent from server targets: " + skill);
            }
            if (!combat.add(skill)) {
                throw new IllegalArgumentException("Duplicate normalized combat skill ID: " + skill);
            }
        }

        boolean skiller = archetype == Archetype.SKILLER;
        CombatStyle actualStyle = skiller ? CombatStyle.BALANCED : combatStyle;
        Set<String> forbidden = skiller
                ? combat : Collections.<String>emptySet();
        Map<String, Integer> goals = new LinkedHashMap<String, Integer>();
        Map<String, Double> weights = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, Integer> entry : allTargets.entrySet()) {
            String skill = entry.getKey();
            if (forbidden.contains(skill)) {
                continue;
            }
            goals.put(skill, entry.getValue());
            double weight = archetype == Archetype.MAIN && combat.contains(skill)
                    ? 1.7 : 1.0;
            if (combat.contains(skill) && matchesStyle(actualStyle, skill)) {
                weight *= 2.0;
            }
            weights.put(skill, Double.valueOf(weight));
        }
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("Build has no remaining skill targets");
        }
        return new BuildPolicy(archetype, actualStyle, goals, weights,
                forbidden, allTargets.keySet());
    }

    /**
     * Chooses an illustrative population mix: 45% main, 25% skiller and 30%
     * hybrid. These are example design weights, not measured player behavior.
     * Main and hybrid styles are sampled uniformly; skillers use BALANCED.
     */
    public static BuildPolicy choose(Random random,
                                     Map<String, Integer> serverTargets,
                                     Set<String> combatSkills) {
        if (random == null) {
            throw new IllegalArgumentException("Random source is required");
        }
        int roll = random.nextInt(100);
        Archetype archetype = roll < 45 ? Archetype.MAIN
                : roll < 70 ? Archetype.SKILLER : Archetype.HYBRID;
        CombatStyle[] styles = CombatStyle.values();
        CombatStyle style = archetype == Archetype.SKILLER
                ? CombatStyle.BALANCED : styles[random.nextInt(styles.length)];
        return create(archetype, style, serverTargets, combatSkills);
    }

    /**
     * Returns true only for a nonempty, fully known reward set containing no
     * forbidden skills. Include all possible primary, secondary and bonus XP
     * rewards. Null, malformed and unknown IDs fail closed.
     */
    public boolean permits(Set<String> possibleXpSkills) {
        if (possibleXpSkills == null || possibleXpSkills.isEmpty()) {
            return false;
        }
        for (String rawSkill : possibleXpSkills) {
            final String skill;
            try {
                skill = canonicalId(rawSkill);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            if (!serverSkills.contains(skill) || forbiddenXpSkills.contains(skill)) {
                return false;
            }
        }
        return true;
    }

    /** Returns a goal's positive selection weight, or zero for an ineligible ID. */
    public double preference(String skill) {
        final String canonical;
        try {
            canonical = canonicalId(skill);
        } catch (IllegalArgumentException invalid) {
            return 0.0;
        }
        Double weight = preferences.get(canonical);
        return weight == null ? 0.0 : weight.doubleValue();
    }

    private static boolean matchesStyle(CombatStyle style, String skill) {
        switch (style) {
            case MELEE:
                return "attack".equals(skill) || "strength".equals(skill)
                        || "defence".equals(skill);
            case RANGED:
                return "ranged".equals(skill);
            case MAGIC:
                return "magic".equals(skill);
            default:
                return false;
        }
    }

    private static String canonicalId(String rawSkill) {
        if (rawSkill == null) {
            throw new IllegalArgumentException("Skill ID is required");
        }
        String skill = rawSkill.trim().toLowerCase(Locale.ROOT);
        if (!SKILL_ID.matcher(skill).matches()) {
            throw new IllegalArgumentException("Invalid skill ID: " + rawSkill);
        }
        return skill;
    }
}
