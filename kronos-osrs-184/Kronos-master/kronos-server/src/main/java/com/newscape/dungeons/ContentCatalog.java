package com.newscape.dungeons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Revision-independent encounter data. Keys are semantic identifiers, never cache IDs.
 * Stats are deliberately modest baseline stats; the runtime applies a run's difficulty
 * snapshot once, rather than scaling against equipment changes during combat.
 */
public final class ContentCatalog {
    public enum Style { MELEE, RANGED, MAGIC, MIXED }

    public static final class NpcDef {
        public final String key, name, description;
        public final Style style;
        public final int hp, maxHit, attackTicks, weight, attackRange;
        public final boolean elite, boss;

        public NpcDef(String key, String name, Style style, int hp, int maxHit,
                      int attackTicks, int weight, int attackRange, boolean elite,
                      boolean boss, String description) {
            if (key == null || key.isEmpty() || name == null || style == null ||
                    hp < 1 || maxHit < 1 || attackTicks < 1 || weight < 1 ||
                    attackRange < 1 || attackRange > 10 || description == null) {
                throw new IllegalArgumentException("Invalid NPC definition: " + key);
            }
            this.key = key; this.name = name; this.style = style; this.hp = hp;
            this.maxHit = maxHit; this.attackTicks = attackTicks; this.weight = weight;
            this.attackRange = attackRange; this.elite = elite; this.boss = boss;
            this.description = description;
        }
    }

    public static final class TrapDef {
        public final String key, name, description;
        public final int requiredAgility, baseSuccessPermille, maxSuccessPermille;
        public final int baseDamage, maxDamage, xp, cooldownTicks;

        public TrapDef(String key, String name, int requiredAgility,
                       int baseSuccessPermille, int maxSuccessPermille,
                       int baseDamage, int maxDamage, int xp, int cooldownTicks,
                       String description) {
            if (key == null || key.isEmpty() || name == null || description == null ||
                    requiredAgility < 1 || requiredAgility > 99 ||
                    baseSuccessPermille < 1 || baseSuccessPermille > maxSuccessPermille ||
                    maxSuccessPermille > 990 || baseDamage < 1 || maxDamage < baseDamage ||
                    xp < 1 || cooldownTicks < 1) {
                throw new IllegalArgumentException("Invalid trap definition: " + key);
            }
            this.key = key; this.name = name; this.requiredAgility = requiredAgility;
            this.baseSuccessPermille = baseSuccessPermille;
            this.maxSuccessPermille = maxSuccessPermille;
            this.baseDamage = baseDamage; this.maxDamage = maxDamage;
            this.xp = xp; this.cooldownTicks = cooldownTicks; this.description = description;
        }

        /** Real current Agility is supplied by the authoritative game server. */
        public int successPermille(int agility) {
            if (agility < requiredAgility) return 0;
            int level = Math.min(99, agility);
            return Math.min(maxSuccessPermille,
                    baseSuccessPermille + (level - requiredAgility) * 5);
        }

        /** A failed crossing still benefits from Agility; callers roll server-side. */
        public int damageAt(int agility, int randomRange) {
            int raw = baseDamage + Math.floorMod(randomRange, maxDamage - baseDamage + 1);
            int mitigation = Math.max(0, Math.min(99, agility) - requiredAgility) / 12;
            return Math.max(1, raw - mitigation);
        }
    }

    public final Map<String, NpcDef> npcs;
    public final Map<String, TrapDef> traps;
    public final List<String> roomTemplates, themes;
    public final List<String> normalNpcKeys, eliteNpcKeys, guardianNpcKeys, bossNpcKeys;

    public ContentCatalog() {
        LinkedHashMap<String, NpcDef> n = new LinkedHashMap<String, NpcDef>();
        add(n, "grave_thrall", "Grave thrall", Style.MELEE, 38, 5, 5, 14, 1, false, false, "Slow shambling front-line defender.");
        add(n, "crypt_rat", "Crypt rat", Style.MELEE, 24, 4, 4, 9, 1, false, false, "Low-health swarmer used sparingly in narrow passages.");
        add(n, "ash_reaver", "Ash reaver", Style.MELEE, 52, 7, 4, 12, 1, false, false, "Steady melee pressure in mixed patrols.");
        add(n, "bone_guard", "Bone guard", Style.MELEE, 64, 8, 5, 12, 1, false, false, "Armoured crypt sentry.");
        add(n, "rusted_knight", "Rusted knight", Style.MELEE, 82, 10, 6, 7, 1, false, false, "Slow, heavier strikes with a readable attack interval.");
        add(n, "hollow_swordsman", "Hollow swordsman", Style.MELEE, 56, 8, 4, 10, 1, false, false, "A restless duellist guarding a burial hall.");
        add(n, "fetid_crawler", "Fetid crawler", Style.MELEE, 34, 6, 4, 8, 1, false, false, "Close-range corridor creature; no implicit poison script.");
        add(n, "tomb_stalker", "Tomb stalker", Style.MELEE, 48, 7, 4, 9, 1, false, false, "A quick hunter in the outer vaults.");
        add(n, "chainbound_dead", "Chainbound dead", Style.MELEE, 72, 9, 5, 8, 1, false, false, "A durable member of a gauntlet guard pack.");
        add(n, "ossuary_archer", "Ossuary archer", Style.RANGED, 40, 7, 5, 12, 6, false, false, "Keeps ranged pressure from a fixed chamber.");
        add(n, "shard_slinger", "Shard slinger", Style.RANGED, 46, 8, 5, 10, 5, false, false, "Hurls broken runestone fragments.");
        add(n, "drowned_marksman", "Drowned marksman", Style.RANGED, 54, 9, 5, 8, 7, false, false, "An accurate long-range revenant.");
        add(n, "crypt_skirmisher", "Crypt skirmisher", Style.RANGED, 42, 6, 4, 10, 5, false, false, "Fast light ranged attacks.");
        add(n, "bone_javeliner", "Bone javeliner", Style.RANGED, 58, 10, 6, 6, 6, false, false, "Slow heavy ranged attacks.");
        add(n, "cinder_acolyte", "Cinder acolyte", Style.MAGIC, 38, 8, 5, 11, 6, false, false, "An acolyte channeling dim cinder magic.");
        add(n, "hollow_invoker", "Hollow invoker", Style.MAGIC, 52, 10, 6, 8, 7, false, false, "Long-range magical pressure.");
        add(n, "rune_wraith", "Rune wraith", Style.MAGIC, 44, 8, 5, 10, 6, false, false, "A spirit bound to the dungeon's runes.");
        add(n, "mourning_seer", "Mourning seer", Style.MAGIC, 60, 11, 6, 6, 7, false, false, "A slow caster accompanying melee sentries.");
        add(n, "veil_cultist", "Veil cultist", Style.MAGIC, 46, 7, 4, 9, 5, false, false, "Short-range rapid magic attacks.");
        add(n, "black_iron_sentinel", "Black iron sentinel", Style.MELEE, 114, 12, 5, 6, 1, true, false, "Elite heavy melee defender.");
        add(n, "crypt_executioner", "Crypt executioner", Style.MELEE, 126, 14, 6, 4, 1, true, false, "Elite slow attacker; large max hit demands attention.");
        add(n, "gloom_huntress", "Gloom huntress", Style.RANGED, 98, 12, 5, 5, 7, true, false, "Elite ranged sentinel.");
        add(n, "sepulchral_magus", "Sepulchral magus", Style.MAGIC, 102, 13, 6, 5, 7, true, false, "Elite magical vault defender.");
        add(n, "veil_knight", "Veil knight", Style.MIXED, 108, 12, 5, 5, 6, true, false, "Elite attacker whose style is chosen by the combat bridge.");
        add(n, "guardian_ash", "Ash sigil guardian", Style.MELEE, 160, 13, 5, 1, 1, true, false, "Holds one of the three run-bound sigils.");
        add(n, "guardian_bone", "Bone sigil guardian", Style.RANGED, 148, 12, 5, 1, 7, true, false, "Holds one of the three run-bound sigils.");
        add(n, "guardian_veil", "Veil sigil guardian", Style.MAGIC, 152, 13, 6, 1, 7, true, false, "Holds one of the three run-bound sigils.");
        add(n, "boss_hollow_warden", "The Hollow Warden", Style.MIXED, 420, 18, 5, 1, 7, true, true, "Final-boss profile: iron, ash, and ruin.");
        add(n, "boss_mourning_regent", "The Mourning Regent", Style.MIXED, 390, 19, 5, 1, 7, true, true, "Final-boss profile: a forgotten ruler of the crypt.");
        add(n, "boss_ashen_arbiter", "The Ashen Arbiter", Style.MIXED, 450, 17, 6, 1, 7, true, true, "Final-boss profile: a slow guardian of the last seal.");
        npcs = Collections.unmodifiableMap(n);

        LinkedHashMap<String, TrapDef> t = new LinkedHashMap<String, TrapDef>();
        trap(t, "cracked_flagstones", "Cracked flagstone leap", 1, 700, 970, 2, 5, 25, 6, "Leap across collapsing stone; a guarded corridor goes around it.");
        trap(t, "rotted_planks", "Rotted plank crossing", 10, 670, 960, 3, 7, 40, 7, "Keep balance over loose planks above a shallow crypt pit.");
        trap(t, "swinging_chains", "Swinging chain gap", 20, 650, 950, 4, 9, 60, 7, "Time a leap through chains sweeping the gap.");
        trap(t, "broken_bridge", "Broken bridge", 25, 640, 950, 5, 10, 75, 8, "Jump the broken central span; guards occupy the longer route.");
        trap(t, "dart_crossing", "Dart crossing", 30, 630, 950, 5, 11, 90, 8, "Cross while the dart slots are between volleys.");
        trap(t, "razor_wire", "Razor-wire vault", 35, 620, 945, 6, 12, 110, 8, "Vault a wire-covered collapse without touching the edges.");
        trap(t, "pendulum_blades", "Pendulum blade corridor", 40, 610, 940, 7, 14, 130, 9, "Pass the gap while the swinging blades are apart.");
        trap(t, "crumbling_ledge", "Crumbling ledge", 45, 600, 940, 7, 15, 150, 9, "Traverse a narrow ledge before the stone gives way.");
        trap(t, "soul_vents", "Soul-vent crossing", 50, 600, 935, 8, 16, 175, 10, "Move between pulses of cold spectral energy.");
        trap(t, "rolling_spikes", "Rolling spike beams", 55, 590, 930, 9, 17, 200, 10, "Leap clear of counter-rotating spiked beams.");
        trap(t, "shattered_stairs", "Shattered stair vault", 60, 580, 925, 9, 18, 230, 10, "Chain together jumps over a broken stairwell.");
        trap(t, "void_chain", "Void-chain traverse", 65, 580, 925, 10, 20, 260, 11, "Use a single suspended chain to cross a dark chasm.");
        trap(t, "executioners_sweep", "Executioner's sweep", 70, 570, 920, 11, 21, 300, 11, "Clear the crossing during a narrow blade timing window.");
        trap(t, "phantom_steps", "Phantom stepping stones", 75, 570, 920, 12, 23, 340, 12, "Jump between intermittently visible footholds.");
        trap(t, "abyssal_balance", "Abyssal balance rail", 85, 560, 900, 13, 25, 400, 12, "Balance along a precarious rail above the final vault.");
        trap(t, "wardens_gauntlet", "Warden's blade crossing", 90, 550, 890, 14, 27, 450, 13, "The highest-tier shortcut; its guarded alternative remains available.");
        traps = Collections.unmodifiableMap(t);

        roomTemplates = immutable("crypt_hall", "broken_refectory", "sunken_archive",
                "ossuary_crossing", "sentry_chamber", "sealed_reliquary", "ritual_chamber",
                "burial_gallery", "watchers_crypt", "marrow_avenue", "forgotten_shrine",
                "iron_vault", "gauntlet_straight", "gauntlet_corner");
        themes = immutable("ashen_catacombs", "drowned_ossuary", "ironbound_crypt", "hollow_sanctum");
        List<String> normal = new ArrayList<String>(), elite = new ArrayList<String>();
        for (NpcDef def : n.values()) {
            if (def.boss || def.key.startsWith("guardian_")) continue;
            (def.elite ? elite : normal).add(def.key);
        }
        normalNpcKeys = Collections.unmodifiableList(normal);
        eliteNpcKeys = Collections.unmodifiableList(elite);
        guardianNpcKeys = immutable("guardian_ash", "guardian_bone", "guardian_veil");
        bossNpcKeys = immutable("boss_hollow_warden", "boss_mourning_regent", "boss_ashen_arbiter");
    }

    public static ContentCatalog standard() { return new ContentCatalog(); }

    public NpcDef npc(String key) {
        NpcDef def = npcs.get(key);
        if (def == null) throw new IllegalArgumentException("Unknown NPC key: " + key);
        return def;
    }

    public TrapDef trap(String key) {
        TrapDef def = traps.get(key);
        if (def == null) throw new IllegalArgumentException("Unknown trap key: " + key);
        return def;
    }

    private static List<String> immutable(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private static void add(Map<String, NpcDef> target, String key, String name,
                            Style style, int hp, int hit, int ticks, int weight,
                            int range, boolean elite, boolean boss, String detail) {
        if (target.put(key, new NpcDef(key, name, style, hp, hit, ticks, weight,
                range, elite, boss, detail)) != null) {
            throw new IllegalArgumentException("Duplicate NPC key: " + key);
        }
    }

    private static void trap(Map<String, TrapDef> target, String key, String name,
                             int level, int base, int cap, int minDamage,
                             int maxDamage, int xp, int cooldown, String detail) {
        if (target.put(key, new TrapDef(key, name, level, base, cap, minDamage,
                maxDamage, xp, cooldown, detail)) != null) {
            throw new IllegalArgumentException("Duplicate trap key: " + key);
        }
    }
}
